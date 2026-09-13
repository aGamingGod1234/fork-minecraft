package dev.agaminggod.arenaagents.server.bridge;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.agaminggod.arenaagents.agent.AgentDomainException;
import dev.agaminggod.arenaagents.agent.AgentId;
import dev.agaminggod.arenaagents.protocol.ActionType;
import dev.agaminggod.arenaagents.protocol.ProtocolCodec;
import dev.agaminggod.arenaagents.server.runtime.ActionProvenance;
import dev.agaminggod.arenaagents.server.runtime.ServerActionRequest;
import dev.agaminggod.arenaagents.server.runtime.ServerActionResult;
import dev.agaminggod.arenaagents.server.runtime.ServerActionState;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Function;
import java.util.zip.CRC32C;

/** Synchronous write-ahead journal for coordinator-issued physical actions. */
final class DurableActionJournal implements AutoCloseable {
	static final int MAX_ENTRIES = 4_096;
	private static final int SCHEMA_VERSION = 1;
	private static final byte[] LOG_HEADER = "AAAJNL2\n".getBytes(StandardCharsets.US_ASCII);
	private static final int FRAME_HEADER_BYTES = Integer.BYTES * 2;
	private static final int MAX_FRAME_BYTES = 64 * 1024 * 1024;
	private final Path path;
	private final int maximumEntries;
	private final int compactionEventLimit;
	private LinkedHashMap<ActionKey, Entry> entries;
	private int persistedEventCount;
	private long persistedBytes;
	private FileChannel persistentChannel;
	private long appendCount;
	private long compactionCount;
	private long appendNanos;
	private long slowestAppendNanos;
	private long compactionNanos;
	private long slowestCompactionNanos;
	private boolean closed;

	private DurableActionJournal(Path path, int maximumEntries, int compactionEventLimit, Loaded loaded) {
		this.path = path;
		this.maximumEntries = maximumEntries;
		this.compactionEventLimit = compactionEventLimit;
		this.entries = loaded.entries();
		this.persistedEventCount = loaded.eventCount();
		this.persistedBytes = loaded.persistedBytes();
	}

	static DurableActionJournal open(Path path) {
		return open(path, MAX_ENTRIES);
	}

	static DurableActionJournal open(Path path, int maximumEntries) {
		return open(path, maximumEntries, Math.max(128, maximumEntries * 4));
	}

	static DurableActionJournal open(Path path, int maximumEntries, int compactionEventLimit) {
		Objects.requireNonNull(path, "path must not be null");
		if (maximumEntries <= 0) throw new IllegalArgumentException("maximumEntries must be positive");
		if (compactionEventLimit <= 0) throw new IllegalArgumentException("compactionEventLimit must be positive");
		Path normalized = path.toAbsolutePath().normalize();
		Loaded loaded = read(normalized, maximumEntries);
		if (loaded.legacy()) {
			writeSnapshot(normalized, loaded.entries());
			loaded = new Loaded(loaded.entries(), loaded.entries().size(), Files.exists(normalized) ? fileSize(normalized) : 0L, false);
		} else if (loaded.persistedBytes() >= 0L && Files.exists(normalized)) {
			truncateTail(normalized, loaded.persistedBytes());
		}
		return new DurableActionJournal(normalized, maximumEntries, compactionEventLimit, loaded);
	}

	static DurableActionJournal inMemory() {
		return new DurableActionJournal(null, MAX_ENTRIES, Integer.MAX_VALUE,
				new Loaded(new LinkedHashMap<>(), 0, 0L, false));
	}

	synchronized void accept(ServerActionRequest request, UUID logicalGoalId) {
		Objects.requireNonNull(request, "request must not be null");
		ActionKey key = ActionKey.from(request);
		Entry prior = entries.get(key);
		if (prior != null) {
			if (!prior.request().equals(request) || !Objects.equals(prior.logicalGoalId(), logicalGoalId)) {
				throw new AgentDomainException("ACTION_PROVENANCE_MISMATCH", "Action ID is already bound to a different durable request");
			}
			throw new AgentDomainException("ACTION_REPLAY", "Action ID has already been durably accepted");
		}
		for (Entry entry : entries.values()) {
			if (entry.request().agentId().equals(request.agentId())
					&& Objects.equals(entry.logicalGoalId(), logicalGoalId)
					&& entry.request().provenance().equals(request.provenance())) {
				throw new AgentDomainException("ACTION_REPLAY", "Program step is already bound to action ID " + entry.request().actionId());
			}
		}
		List<ActionKey> removed = admissionRemovals();
		Entry accepted = new Entry(request, logicalGoalId, Phase.ACCEPTED, null);
		persist(new Mutation(removed, List.of(accepted), List.of(), List.of()));
	}

	synchronized boolean terminalIfAccepted(ServerActionResult result) {
		Objects.requireNonNull(result, "result must not be null");
		if (entries.get(ActionKey.from(result)) == null) return false;
		terminal(result);
		return true;
	}

	synchronized void terminal(ServerActionResult result) {
		Objects.requireNonNull(result, "result must not be null");
		ActionKey key = ActionKey.from(result);
		Entry prior = entries.get(key);
		if (prior == null) {
			throw new AgentDomainException("ACTION_JOURNAL_MISSING", "Terminal action has no durable acceptance record");
		}
		verifyResult(prior.request(), result);
		if (prior.phase() != Phase.ACCEPTED) {
			if (prior.result().equals(result)) return;
			throw new AgentDomainException("ACTION_RESULT_REPLAY_CONFLICT", "Action already has a different durable terminal result");
		}
		persist(new Mutation(List.of(), List.of(), List.of(result), List.of()));
	}

	synchronized boolean acknowledge(AgentId agentId, long goalRevision, String actionId) {
		ActionKey key = new ActionKey(agentId, goalRevision, actionId);
		Entry prior = entries.get(key);
		if (prior == null) return false;
		if (prior.phase() == Phase.ACCEPTED) return false;
		if (prior.phase() == Phase.ACKNOWLEDGED) return true;
		persist(new Mutation(List.of(), List.of(), List.of(), List.of(key)));
		return true;
	}

	/** Converts crash-stranded acceptances with one durable append and one fsync. */
	synchronized void terminalizeAccepted(Function<ServerActionRequest, ServerActionResult> resultFactory) {
		Objects.requireNonNull(resultFactory, "resultFactory must not be null");
		List<ServerActionResult> terminalized = new ArrayList<>();
		for (Entry entry : entries.values()) {
			if (entry.phase() != Phase.ACCEPTED) continue;
			ServerActionResult result = Objects.requireNonNull(resultFactory.apply(entry.request()), "recovery result must not be null");
			verifyResult(entry.request(), result);
			terminalized.add(result);
		}
		if (!terminalized.isEmpty()) persist(new Mutation(List.of(), List.of(), terminalized, List.of()));
	}

	synchronized boolean rollbackAccepted(ServerActionRequest request) {
		ActionKey key = ActionKey.from(request);
		Entry prior = entries.get(key);
		if (prior == null || prior.phase() != Phase.ACCEPTED || !prior.request().equals(request)) return false;
		persist(new Mutation(List.of(key), List.of(), List.of(), List.of()));
		return true;
	}

	synchronized void retainGoal(AgentId agentId, UUID logicalGoalId) {
		List<ActionKey> removed = new ArrayList<>();
		for (Map.Entry<ActionKey, Entry> entry : entries.entrySet()) {
			if (entry.getKey().agentId().equals(agentId)
					&& entry.getValue().phase() == Phase.ACKNOWLEDGED
					&& !Objects.equals(entry.getValue().logicalGoalId(), logicalGoalId)) {
				removed.add(entry.getKey());
			}
		}
		if (!removed.isEmpty()) persist(new Mutation(List.copyOf(removed), List.of(), List.of(), List.of()));
	}

	synchronized void remove(AgentId agentId) {
		List<ActionKey> removed = new ArrayList<>();
		for (ActionKey key : entries.keySet()) {
			if (key.agentId().equals(agentId)) removed.add(key);
		}
		if (!removed.isEmpty()) persist(new Mutation(List.copyOf(removed), List.of(), List.of(), List.of()));
	}

	synchronized List<Entry> snapshot() {
		return List.copyOf(entries.values());
	}

	synchronized int persistedEventCountForVerification() {
		return persistedEventCount;
	}

	synchronized PerformanceSnapshot performanceSnapshotForVerification() {
		return new PerformanceSnapshot(
				persistedEventCount, persistedBytes, appendCount, compactionCount,
				appendNanos, slowestAppendNanos, compactionNanos, slowestCompactionNanos
		);
	}

	private List<ActionKey> admissionRemovals() {
		if (entries.size() < maximumEntries) return List.of();
		List<ActionKey> removed = new ArrayList<>(1);
		int retainedSize = entries.size();
		for (Map.Entry<ActionKey, Entry> candidate : entries.entrySet()) {
			if (retainedSize < maximumEntries) break;
			if (candidate.getValue().phase() != Phase.ACKNOWLEDGED) continue;
			removed.add(candidate.getKey());
			retainedSize--;
		}
		if (retainedSize >= maximumEntries) {
			throw new AgentDomainException("ACTION_JOURNAL_FULL", "Durable action journal is full of unacknowledged actions");
		}
		return List.copyOf(removed);
	}

	private void persist(Mutation mutation) {
		if (closed) throw new AgentDomainException("ACTION_JOURNAL_CLOSED", "Durable action journal is closed");
		if (path != null) {
			long started = System.nanoTime();
			if (persistedEventCount >= compactionEventLimit) compact();
			byte[] frame = encodeFrame(mutation);
			appendFrame(frame);
			long elapsed = Math.max(0L, System.nanoTime() - started);
			appendCount++;
			appendNanos += elapsed;
			slowestAppendNanos = Math.max(slowestAppendNanos, elapsed);
			persistedEventCount++;
		}
		applyMutation(entries, mutation);
	}

	private void compact() {
		long started = System.nanoTime();
		closePersistentChannel();
		writeSnapshot(path, entries);
		persistedEventCount = entries.size();
		persistedBytes = fileSize(path);
		openPersistentChannel();
		long elapsed = Math.max(0L, System.nanoTime() - started);
		compactionCount++;
		compactionNanos += elapsed;
		slowestCompactionNanos = Math.max(slowestCompactionNanos, elapsed);
	}

	private static Loaded read(Path path, int maximumEntries) {
		if (!Files.exists(path)) return new Loaded(new LinkedHashMap<>(), 0, 0L, false);
		try {
			byte[] bytes = Files.readAllBytes(path);
			if (bytes.length == 0) return new Loaded(new LinkedHashMap<>(), 0, 0L, false);
			if (bytes[0] == '{') {
				return new Loaded(readLegacy(new String(bytes, StandardCharsets.UTF_8), maximumEntries), 0, bytes.length, true);
			}
			if (bytes.length < LOG_HEADER.length) throw corrupt("log header is incomplete");
			for (int index = 0; index < LOG_HEADER.length; index++) {
				if (bytes[index] != LOG_HEADER[index]) throw corrupt("unsupported log header");
			}
			LinkedHashMap<ActionKey, Entry> decoded = new LinkedHashMap<>();
			int offset = LOG_HEADER.length;
			int eventCount = 0;
			while (offset < bytes.length) {
				int frameStart = offset;
				if (bytes.length - offset < FRAME_HEADER_BYTES) break;
				ByteBuffer header = ByteBuffer.wrap(bytes, offset, FRAME_HEADER_BYTES);
				int payloadLength = header.getInt();
				int expectedChecksum = header.getInt();
				offset += FRAME_HEADER_BYTES;
				if (payloadLength <= 0 || payloadLength > MAX_FRAME_BYTES) throw corrupt("invalid event frame length");
				if (bytes.length - offset < payloadLength) {
					offset = frameStart;
					break;
				}
				CRC32C checksum = new CRC32C();
				checksum.update(bytes, offset, payloadLength);
				if ((int) checksum.getValue() != expectedChecksum) {
					if (offset + payloadLength == bytes.length) {
						offset = frameStart;
						break;
					}
					throw corrupt("event frame checksum mismatch");
				}
				Mutation mutation = decodeMutation(new String(bytes, offset, payloadLength, StandardCharsets.UTF_8));
				apply(decoded, mutation, maximumEntries);
				offset += payloadLength;
				eventCount++;
			}
			return new Loaded(decoded, eventCount, offset, false);
		} catch (AgentDomainException exception) {
			throw exception;
		} catch (RuntimeException | IOException exception) {
			throw corrupt("could not read durable action journal", exception);
		}
	}

	private static LinkedHashMap<ActionKey, Entry> readLegacy(String serialized, int maximumEntries) {
		try {
			JsonElement parsed = JsonParser.parseString(serialized);
			if (!parsed.isJsonObject()) throw corrupt("root must be an object");
			JsonObject root = parsed.getAsJsonObject();
			if (requiredInt(root, "schemaVersion") != SCHEMA_VERSION) throw corrupt("unsupported schema version");
			JsonArray serializedEntries = requiredArray(root, "entries");
			if (serializedEntries.size() > maximumEntries) throw corrupt("entry limit exceeded");
			LinkedHashMap<ActionKey, Entry> decoded = new LinkedHashMap<>();
			for (JsonElement element : serializedEntries) {
				if (!element.isJsonObject()) throw corrupt("entry must be an object");
				Entry entry = decodeEntry(element.getAsJsonObject());
				if (decoded.put(ActionKey.from(entry.request()), entry) != null) throw corrupt("duplicate action identity");
			}
			return decoded;
		} catch (AgentDomainException exception) {
			throw exception;
		} catch (RuntimeException exception) {
			throw corrupt("could not read legacy durable action journal", exception);
		}
	}

	private static void writeSnapshot(Path path, Map<ActionKey, Entry> entries) {
		writeLog(path, channel -> {
			for (Entry entry : entries.values()) {
				writeFully(channel, ByteBuffer.wrap(encodeFrame(
						new Mutation(List.of(), List.of(entry), List.of(), List.of()))));
			}
		});
	}

	private void openPersistentChannel() {
		if (path == null || persistentChannel != null) return;
		try {
			FileChannel opened = FileChannel.open(path, StandardOpenOption.WRITE);
			try {
				if (opened.size() != persistedBytes) {
					throw new IOException("action journal changed after it was read");
				}
				opened.position(persistedBytes);
				persistentChannel = opened;
			} catch (IOException | RuntimeException | Error failure) {
				try {
					opened.close();
				} catch (IOException closeFailure) {
					failure.addSuppressed(closeFailure);
				}
				throw failure;
			}
		} catch (IOException exception) {
			throw ioFailure("Could not open action journal for durable appends", exception);
		}
	}

	private void closePersistentChannel() {
		FileChannel active = persistentChannel;
		persistentChannel = null;
		if (active == null) return;
		try {
			active.close();
		} catch (IOException exception) {
			throw ioFailure("Could not close action journal", exception);
		}
	}

	private static void writeLog(Path path, FrameWriter frames) {
		Path parent = path.getParent();
		if (parent == null) throw new AgentDomainException("ACTION_JOURNAL_IO", "Action journal path has no parent directory");
		Path temporary = parent.resolve(path.getFileName() + ".tmp-" + UUID.randomUUID());
		try {
			Files.createDirectories(parent);
			try (FileChannel channel = FileChannel.open(temporary, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)) {
				writeFully(channel, ByteBuffer.wrap(LOG_HEADER));
				frames.write(channel);
				channel.force(true);
			}
			Files.move(temporary, path, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
		} catch (IOException exception) {
			try {
				Files.deleteIfExists(temporary);
			} catch (IOException suppressed) {
				exception.addSuppressed(suppressed);
			}
			throw new AgentDomainException("ACTION_JOURNAL_IO", "Could not durably write action journal: " + exception.getMessage());
		} catch (RuntimeException exception) {
			try {
				Files.deleteIfExists(temporary);
			} catch (IOException suppressed) {
				exception.addSuppressed(suppressed);
			}
			throw exception;
		}
	}

	private void appendFrame(byte[] frame) {
		if (persistentChannel == null) {
			if (!Files.exists(path) || persistedBytes == 0L) {
				writeLog(path, channel -> writeFully(channel, ByteBuffer.wrap(frame)));
				persistedBytes = fileSize(path);
				openPersistentChannel();
				return;
			}
			openPersistentChannel();
		}
		long originalSize = persistedBytes;
		try {
			persistentChannel.position(originalSize);
			writeFully(persistentChannel, ByteBuffer.wrap(frame));
			persistentChannel.force(true);
			persistedBytes += frame.length;
		} catch (IOException exception) {
			try {
				persistentChannel.position(originalSize);
				persistentChannel.truncate(originalSize);
				persistentChannel.force(true);
			} catch (IOException suppressed) {
				exception.addSuppressed(suppressed);
			}
			throw new AgentDomainException("ACTION_JOURNAL_IO", "Could not durably append action journal: " + exception.getMessage());
		}
	}

	private static AgentDomainException ioFailure(String message, IOException cause) {
		AgentDomainException failure = new AgentDomainException("ACTION_JOURNAL_IO", message + ": " + cause.getMessage());
		failure.initCause(cause);
		return failure;
	}

	private static byte[] encodeFrame(Mutation mutation) {
		byte[] payload = encodeMutation(mutation).getBytes(StandardCharsets.UTF_8);
		if (payload.length == 0 || payload.length > MAX_FRAME_BYTES) {
			throw new AgentDomainException("ACTION_JOURNAL_IO", "Action journal event exceeds the durable frame limit");
		}
		CRC32C checksum = new CRC32C();
		checksum.update(payload, 0, payload.length);
		ByteBuffer frame = ByteBuffer.allocate(FRAME_HEADER_BYTES + payload.length);
		frame.putInt(payload.length).putInt((int) checksum.getValue()).put(payload);
		return frame.array();
	}

	private static String encodeMutation(Mutation mutation) {
		JsonObject encoded = new JsonObject();
		JsonArray removed = new JsonArray();
		for (ActionKey key : mutation.removed()) removed.add(encodeKey(key));
		encoded.add("remove", removed);
		JsonArray put = new JsonArray();
		for (Entry entry : mutation.put()) put.add(encodeEntry(entry));
		encoded.add("put", put);
		JsonArray terminal = new JsonArray();
		for (ServerActionResult result : mutation.terminal()) terminal.add(encodeResult(result));
		encoded.add("terminal", terminal);
		JsonArray acknowledged = new JsonArray();
		for (ActionKey key : mutation.acknowledged()) acknowledged.add(encodeKey(key));
		encoded.add("acknowledged", acknowledged);
		return encoded.toString();
	}

	private static Mutation decodeMutation(String serialized) {
		JsonElement parsed = JsonParser.parseString(serialized);
		if (!parsed.isJsonObject()) throw corrupt("event must be an object");
		JsonObject encoded = parsed.getAsJsonObject();
		List<ActionKey> removed = new ArrayList<>();
		for (JsonElement element : requiredArray(encoded, "remove")) {
			if (!element.isJsonObject()) throw corrupt("removed identity must be an object");
			removed.add(decodeKey(element.getAsJsonObject()));
		}
		List<Entry> put = new ArrayList<>();
		for (JsonElement element : requiredArray(encoded, "put")) {
			if (!element.isJsonObject()) throw corrupt("put entry must be an object");
			put.add(decodeEntry(element.getAsJsonObject()));
		}
		List<ServerActionResult> terminal = new ArrayList<>();
		for (JsonElement element : requiredArray(encoded, "terminal")) {
			if (!element.isJsonObject()) throw corrupt("terminal result must be an object");
			terminal.add(decodeResult(element.getAsJsonObject()));
		}
		List<ActionKey> acknowledged = new ArrayList<>();
		for (JsonElement element : requiredArray(encoded, "acknowledged")) {
			if (!element.isJsonObject()) throw corrupt("acknowledged identity must be an object");
			acknowledged.add(decodeKey(element.getAsJsonObject()));
		}
		if (removed.isEmpty() && put.isEmpty() && terminal.isEmpty() && acknowledged.isEmpty()) {
			throw corrupt("event must change the journal");
		}
		return new Mutation(List.copyOf(removed), List.copyOf(put),
				List.copyOf(terminal), List.copyOf(acknowledged));
	}

	private static void apply(LinkedHashMap<ActionKey, Entry> entries, Mutation mutation, int maximumEntries) {
		for (ActionKey key : mutation.removed()) entries.remove(key);
		for (Entry entry : mutation.put()) entries.put(ActionKey.from(entry.request()), entry);
		for (ServerActionResult result : mutation.terminal()) {
			ActionKey key = ActionKey.from(result);
			Entry prior = entries.get(key);
			if (prior == null) throw corrupt("terminal result has no acceptance record");
			verifyResult(prior.request(), result);
			if (prior.phase() != Phase.ACCEPTED) {
				if (!prior.result().equals(result)) throw corrupt("terminal result conflicts with durable result");
				continue;
			}
			entries.put(key, new Entry(prior.request(), prior.logicalGoalId(), Phase.TERMINAL, result));
		}
		for (ActionKey key : mutation.acknowledged()) {
			Entry prior = entries.get(key);
			if (prior == null || prior.phase() == Phase.ACCEPTED) throw corrupt("acknowledgement has no terminal result");
			entries.put(key, new Entry(prior.request(), prior.logicalGoalId(), Phase.ACKNOWLEDGED, prior.result()));
		}
		if (entries.size() > maximumEntries) throw corrupt("entry limit exceeded");
	}

	private static void applyMutation(LinkedHashMap<ActionKey, Entry> entries, Mutation mutation) {
		for (ActionKey key : mutation.removed()) entries.remove(key);
		for (Entry entry : mutation.put()) entries.put(ActionKey.from(entry.request()), entry);
		for (ServerActionResult result : mutation.terminal()) {
			ActionKey key = ActionKey.from(result);
			Entry prior = entries.get(key);
			entries.put(key, new Entry(prior.request(), prior.logicalGoalId(), Phase.TERMINAL, result));
		}
		for (ActionKey key : mutation.acknowledged()) {
			Entry prior = entries.get(key);
			entries.put(key, new Entry(prior.request(), prior.logicalGoalId(), Phase.ACKNOWLEDGED, prior.result()));
		}
	}

	private static JsonObject encodeKey(ActionKey key) {
		JsonObject encoded = new JsonObject();
		encoded.addProperty("agentId", key.agentId().toString());
		encoded.addProperty("goalRevision", key.goalRevision());
		encoded.addProperty("actionId", key.actionId());
		return encoded;
	}

	private static ActionKey decodeKey(JsonObject encoded) {
		return new ActionKey(AgentId.parse(requiredString(encoded, "agentId")),
				requiredLong(encoded, "goalRevision"), requiredString(encoded, "actionId"));
	}

	private static void writeFully(FileChannel channel, ByteBuffer buffer) throws IOException {
		while (buffer.hasRemaining()) channel.write(buffer);
	}

	private static void truncateTail(Path path, long validBytes) {
		try {
			if (Files.size(path) == validBytes) return;
			try (FileChannel channel = FileChannel.open(path, StandardOpenOption.WRITE)) {
				channel.truncate(validBytes);
				channel.force(true);
			}
		} catch (IOException exception) {
			throw new AgentDomainException("ACTION_JOURNAL_IO", "Could not repair incomplete journal tail: " + exception.getMessage());
		}
	}

	private static long fileSize(Path path) {
		try {
			return Files.size(path);
		} catch (IOException exception) {
			throw new AgentDomainException("ACTION_JOURNAL_IO", "Could not inspect action journal: " + exception.getMessage());
		}
	}

	private static JsonObject encodeEntry(Entry entry) {
		JsonObject encoded = new JsonObject();
		if (entry.logicalGoalId() != null) encoded.addProperty("logicalGoalId", entry.logicalGoalId().toString());
		encoded.addProperty("phase", entry.phase().name());
		encoded.add("request", encodeRequest(entry.request()));
		if (entry.result() != null) encoded.add("result", encodeResult(entry.result()));
		return encoded;
	}

	private static Entry decodeEntry(JsonObject encoded) {
		UUID logicalGoalId = encoded.has("logicalGoalId") ? UUID.fromString(requiredString(encoded, "logicalGoalId")) : null;
		Phase phase = Phase.valueOf(requiredString(encoded, "phase"));
		ServerActionRequest request = decodeRequest(requiredObject(encoded, "request"));
		ServerActionResult result = encoded.has("result") ? decodeResult(requiredObject(encoded, "result")) : null;
		if ((phase == Phase.ACCEPTED) != (result == null)) throw corrupt("entry phase and result disagree");
		if (result != null) verifyResult(request, result);
		return new Entry(request, logicalGoalId, phase, result);
	}

	private static JsonObject encodeRequest(ServerActionRequest request) {
		JsonObject encoded = new JsonObject();
		encoded.addProperty("agentId", request.agentId().toString());
		encoded.addProperty("goalRevision", request.goalRevision());
		encoded.addProperty("actionId", request.actionId());
		encoded.addProperty("actionType", request.type().wireName());
		encoded.add("arguments", request.arguments());
		if (request.traceId() != null) encoded.addProperty("traceId", request.traceId());
		encoded.add("provenance", encodeProvenance(request.provenance()));
		return encoded;
	}

	private static ServerActionRequest decodeRequest(JsonObject encoded) {
		ActionType type = ActionType.fromWireName(requiredString(encoded, "actionType"))
				.orElseThrow(() -> corrupt("unknown action type"));
		JsonObject arguments = ProtocolCodec.validateActionArguments(type, requiredObject(encoded, "arguments"));
		ActionProvenance provenance = decodeProvenance(requiredObject(encoded, "provenance"));
		String traceId = encoded.has("traceId") ? requiredString(encoded, "traceId") : null;
		return new ServerActionRequest(
				AgentId.parse(requiredString(encoded, "agentId")), requiredLong(encoded, "goalRevision"),
				requiredString(encoded, "actionId"), type, arguments, provenance, traceId
		);
	}

	private static JsonObject encodeProvenance(ActionProvenance provenance) {
		JsonObject encoded = new JsonObject();
		encoded.addProperty("provider", provenance.provider());
		encoded.addProperty("model", provenance.model());
		encoded.addProperty("reasoningEffort", provenance.reasoningEffort());
		encoded.addProperty("serviceTier", provenance.serviceTier());
		encoded.addProperty("programId", provenance.programId());
		encoded.addProperty("programVersion", provenance.programVersion());
		encoded.addProperty("sourceStepId", provenance.sourceStepId());
		encoded.addProperty("eventSequence", provenance.eventSequence());
		if (provenance.traceId() != null) encoded.addProperty("traceId", provenance.traceId());
		if (provenance.watcherId() != null) encoded.addProperty("watcherId", provenance.watcherId());
		return encoded;
	}

	private static ActionProvenance decodeProvenance(JsonObject encoded) {
		return new ActionProvenance(
				requiredString(encoded, "provider"), requiredString(encoded, "model"),
				requiredString(encoded, "reasoningEffort"), requiredString(encoded, "serviceTier"),
				requiredString(encoded, "programId"), requiredLong(encoded, "programVersion"),
				requiredString(encoded, "sourceStepId"), requiredLong(encoded, "eventSequence"),
				encoded.has("traceId") ? requiredString(encoded, "traceId") : null,
				encoded.has("watcherId") ? requiredString(encoded, "watcherId") : null
		);
	}

	private static JsonObject encodeResult(ServerActionResult result) {
		JsonObject encoded = new JsonObject();
		encoded.addProperty("agentId", result.agentId().toString());
		encoded.addProperty("goalRevision", result.goalRevision());
		encoded.addProperty("actionId", result.actionId());
		encoded.addProperty("actionType", result.actionType().wireName());
		if (result.traceId() != null) encoded.addProperty("traceId", result.traceId());
		encoded.addProperty("state", result.state().name());
		encoded.addProperty("reasonCode", result.reasonCode());
		encoded.addProperty("message", result.message());
		encoded.addProperty("elapsedMs", result.elapsedMs());
		encoded.addProperty("observedAtEpochMs", result.observedAtEpochMs());
		encoded.addProperty("executionStarted", result.executionStarted());
		encoded.addProperty("physicalAttempted", result.physicalAttempted());
		return encoded;
	}

	private static ServerActionResult decodeResult(JsonObject encoded) {
		ActionType type = ActionType.fromWireName(requiredString(encoded, "actionType"))
				.orElseThrow(() -> corrupt("unknown result action type"));
		return new ServerActionResult(
				AgentId.parse(requiredString(encoded, "agentId")), requiredLong(encoded, "goalRevision"),
				requiredString(encoded, "actionId"), type,
				encoded.has("traceId") ? requiredString(encoded, "traceId") : null,
				ServerActionState.valueOf(requiredString(encoded, "state")), requiredString(encoded, "reasonCode"),
				requiredString(encoded, "message"), requiredLong(encoded, "elapsedMs"),
				requiredLong(encoded, "observedAtEpochMs"), requiredBoolean(encoded, "executionStarted"),
				requiredBoolean(encoded, "physicalAttempted")
		);
	}

	private static void verifyResult(ServerActionRequest request, ServerActionResult result) {
		if (!request.agentId().equals(result.agentId()) || request.goalRevision() != result.goalRevision()
				|| !request.actionId().equals(result.actionId()) || request.type() != result.actionType()
				|| !Objects.equals(request.traceId(), result.traceId())) {
			throw new AgentDomainException("ACTION_RESULT_REPLAY_CONFLICT", "Terminal result does not match its durable request");
		}
	}

	private static JsonObject requiredObject(JsonObject object, String field) {
		JsonElement value = object.get(field);
		if (value == null || !value.isJsonObject()) throw corrupt(field + " must be an object");
		return value.getAsJsonObject();
	}

	private static JsonArray requiredArray(JsonObject object, String field) {
		JsonElement value = object.get(field);
		if (value == null || !value.isJsonArray()) throw corrupt(field + " must be an array");
		return value.getAsJsonArray();
	}

	private static String requiredString(JsonObject object, String field) {
		JsonElement value = object.get(field);
		if (value == null || !value.isJsonPrimitive() || !value.getAsJsonPrimitive().isString()) throw corrupt(field + " must be a string");
		return value.getAsString();
	}

	private static long requiredLong(JsonObject object, String field) {
		JsonElement value = object.get(field);
		if (value == null || !value.isJsonPrimitive() || !value.getAsJsonPrimitive().isNumber()) throw corrupt(field + " must be an integer");
		return value.getAsLong();
	}

	private static int requiredInt(JsonObject object, String field) {
		return Math.toIntExact(requiredLong(object, field));
	}

	private static boolean requiredBoolean(JsonObject object, String field) {
		JsonElement value = object.get(field);
		if (value == null || !value.isJsonPrimitive() || !value.getAsJsonPrimitive().isBoolean()) throw corrupt(field + " must be a boolean");
		return value.getAsBoolean();
	}

	private static AgentDomainException corrupt(String message) {
		return new AgentDomainException("ACTION_JOURNAL_CORRUPT", "Durable action journal is corrupt: " + message);
	}

	private static AgentDomainException corrupt(String message, Exception cause) {
		AgentDomainException exception = corrupt(message);
		exception.initCause(cause);
		return exception;
	}

	@Override
	public synchronized void close() {
		if (closed) return;
		closed = true;
		closePersistentChannel();
	}

	enum Phase { ACCEPTED, TERMINAL, ACKNOWLEDGED }

	record Entry(ServerActionRequest request, UUID logicalGoalId, Phase phase, ServerActionResult result) {
		Entry {
			Objects.requireNonNull(request, "request must not be null");
			Objects.requireNonNull(phase, "phase must not be null");
		}
	}

	private record Mutation(
			List<ActionKey> removed,
			List<Entry> put,
			List<ServerActionResult> terminal,
			List<ActionKey> acknowledged
	) { }

	private record Loaded(
			LinkedHashMap<ActionKey, Entry> entries,
			int eventCount,
			long persistedBytes,
			boolean legacy
	) { }

	record PerformanceSnapshot(
			int persistedEventCount,
			long persistedBytes,
			long appendCount,
			long compactionCount,
			long appendNanos,
			long slowestAppendNanos,
			long compactionNanos,
			long slowestCompactionNanos
	) { }

	@FunctionalInterface
	private interface FrameWriter {
		void write(FileChannel channel) throws IOException;
	}

	private record ActionKey(AgentId agentId, long goalRevision, String actionId) {
		private static ActionKey from(ServerActionRequest request) {
			return new ActionKey(request.agentId(), request.goalRevision(), request.actionId());
		}

		private static ActionKey from(ServerActionResult result) {
			return new ActionKey(result.agentId(), result.goalRevision(), result.actionId());
		}
	}
}
