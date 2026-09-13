package dev.agaminggod.arenaagents.scenario.runtime;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.agaminggod.arenaagents.scenario.ScenarioLaunchCodec;
import dev.agaminggod.arenaagents.scenario.ScenarioLaunchRequest;
import dev.agaminggod.arenaagents.scenario.result.MatchResultWriter;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/** Synchronous owner record written before preparation can change the world. */
public final class ScenarioPreparationJournal {
	private static final int MAX_BYTES = 64 * 1_024;
	private static final Set<String> KEYS = Set.of(
			"schemaVersion", "sessionId", "operatorId", "dimensionId", "originX", "originY", "originZ",
			"request", "worldSeed", "eventSeed", "createdAtEpochMs", "agentIds", "cancellationRequested");
	private final Path path;

	public ScenarioPreparationJournal(Path serverDirectory) {
		path = Objects.requireNonNull(serverDirectory, "serverDirectory must not be null")
				.toAbsolutePath().normalize().resolve("runtime").resolve("scenario-preparation.json");
	}

	public synchronized void write(Snapshot snapshot) throws IOException {
		Objects.requireNonNull(snapshot, "snapshot must not be null");
		Files.createDirectories(path.getParent());
		byte[] bytes = encode(snapshot).getBytes(StandardCharsets.UTF_8);
		if (bytes.length > MAX_BYTES) throw new IOException("SCENARIO_PREPARATION_JOURNAL_TOO_LARGE");
		Path temporary = Files.createTempFile(path.getParent(), "scenario-preparation.", ".tmp");
		boolean moved = false;
		try {
			try (FileChannel channel = FileChannel.open(temporary, StandardOpenOption.WRITE)) {
				ByteBuffer buffer = ByteBuffer.wrap(bytes);
				while (buffer.hasRemaining()) channel.write(buffer);
				channel.force(true);
			}
			MatchResultWriter.moveDurably(temporary, path, Files::move);
			moved = true;
		} finally {
			if (!moved) Files.deleteIfExists(temporary);
		}
	}

	public synchronized Optional<Snapshot> load() throws IOException {
		if (!Files.exists(path)) return Optional.empty();
		long size = Files.size(path);
		if (size < 1L || size > MAX_BYTES) throw new IOException("SCENARIO_PREPARATION_JOURNAL_INVALID_SIZE");
		try {
			return Optional.of(decode(Files.readString(path, StandardCharsets.UTF_8)));
		} catch (RuntimeException exception) {
			throw new IOException("SCENARIO_PREPARATION_JOURNAL_INVALID", exception);
		}
	}

	public synchronized void clear() throws IOException { Files.deleteIfExists(path); }

	private static String encode(Snapshot snapshot) {
		JsonObject root = new JsonObject();
		root.addProperty("schemaVersion", 1);
		root.addProperty("sessionId", snapshot.sessionId().toString());
		root.addProperty("operatorId", snapshot.operatorId().toString());
		root.addProperty("dimensionId", snapshot.dimensionId());
		root.addProperty("originX", snapshot.origin().getX());
		root.addProperty("originY", snapshot.origin().getY());
		root.addProperty("originZ", snapshot.origin().getZ());
		root.addProperty("request", ScenarioLaunchCodec.encode(snapshot.request()));
		root.addProperty("worldSeed", snapshot.worldSeed());
		root.addProperty("eventSeed", snapshot.eventSeed());
		root.addProperty("createdAtEpochMs", snapshot.createdAtEpochMs());
		JsonArray ids = new JsonArray();
		for (String id : snapshot.agentIds()) ids.add(id);
		root.add("agentIds", ids);
		root.addProperty("cancellationRequested", snapshot.cancellationRequested());
		return root.toString();
	}

	private static Snapshot decode(String encoded) {
		JsonObject root = JsonParser.parseString(encoded).getAsJsonObject();
		if (!root.keySet().equals(KEYS) || root.get("schemaVersion").getAsInt() != 1) {
			throw new IllegalArgumentException("preparation journal schema is invalid");
		}
		ArrayList<String> agentIds = new ArrayList<>();
		for (var value : root.getAsJsonArray("agentIds")) agentIds.add(value.getAsString());
		return new Snapshot(
				UUID.fromString(root.get("sessionId").getAsString()),
				UUID.fromString(root.get("operatorId").getAsString()),
				root.get("dimensionId").getAsString(),
				new net.minecraft.core.BlockPos(root.get("originX").getAsInt(), root.get("originY").getAsInt(), root.get("originZ").getAsInt()),
				ScenarioLaunchCodec.decode(root.get("request").getAsString()),
				root.get("worldSeed").getAsLong(), root.get("eventSeed").getAsLong(),
				root.get("createdAtEpochMs").getAsLong(), agentIds,
				root.get("cancellationRequested").getAsBoolean());
	}

	public record Snapshot(
			UUID sessionId, UUID operatorId, String dimensionId, net.minecraft.core.BlockPos origin,
			ScenarioLaunchRequest request, long worldSeed, long eventSeed, long createdAtEpochMs,
			List<String> agentIds, boolean cancellationRequested
	) {
		public Snapshot {
			sessionId = Objects.requireNonNull(sessionId, "sessionId must not be null");
			operatorId = Objects.requireNonNull(operatorId, "operatorId must not be null");
			dimensionId = Objects.requireNonNull(dimensionId, "dimensionId must not be null");
			if (dimensionId.isBlank() || dimensionId.length() > 128) throw new IllegalArgumentException("dimensionId is invalid");
			origin = Objects.requireNonNull(origin, "origin must not be null").immutable();
			request = Objects.requireNonNull(request, "request must not be null");
			if (createdAtEpochMs < 0L) throw new IllegalArgumentException("createdAtEpochMs is invalid");
			agentIds = List.copyOf(Objects.requireNonNull(agentIds, "agentIds must not be null"));
			if (agentIds.size() > 16 || agentIds.stream().anyMatch(id -> id == null || id.isBlank() || id.length() > 80)
					|| agentIds.stream().distinct().count() != agentIds.size()) {
				throw new IllegalArgumentException("agentIds are invalid");
			}
		}

		public Snapshot withAgentId(String agentId) {
			if (agentIds.contains(agentId)) return this;
			ArrayList<String> next = new ArrayList<>(agentIds);
			next.add(agentId);
			return new Snapshot(sessionId, operatorId, dimensionId, origin, request, worldSeed, eventSeed,
					createdAtEpochMs, next, cancellationRequested);
		}

		public Snapshot cancelling() {
			return cancellationRequested ? this : new Snapshot(sessionId, operatorId, dimensionId, origin, request,
					worldSeed, eventSeed, createdAtEpochMs, agentIds, true);
		}
	}
}
