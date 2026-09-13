package dev.agaminggod.arenaagents.scenario.result;

import com.google.gson.JsonParser;
import com.google.gson.JsonParseException;
import java.io.BufferedReader;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;

public final class MatchResultWriter {
	private static final long MAX_TOTAL_JOURNAL_BYTES = 16L * 1_024L * 1_024L;
	private static final long MAX_ACTIVE_JOURNAL_BYTES = MAX_TOTAL_JOURNAL_BYTES / 2L;
	private static final long MAX_ARCHIVE_JOURNAL_BYTES = MAX_TOTAL_JOURNAL_BYTES - MAX_ACTIVE_JOURNAL_BYTES;
	private static final int MAX_JOURNAL_LINES = 10_000;
	private static final int MAX_JOURNAL_LINE_BYTES = 1 * 1_024 * 1_024;
	private final Path directory;

	public MatchResultWriter(Path directory) {
		this.directory = Objects.requireNonNull(directory, "directory must not be null").toAbsolutePath().normalize();
	}

	public synchronized Path write(MatchResultV1 result) throws IOException {
		Objects.requireNonNull(result, "result must not be null");
		Path artifact = ensureArtifact(result);
		String canonical = result.canonicalJson();
		writeJournalIdempotently(result.matchId(), result.canonicalSha256(), canonical);
		return artifact;
	}

	/**
	 * Completes the first durable stage. A later {@link #write(MatchResultV1)} call
	 * idempotently repairs a journal missing after a crash at this boundary.
	 */
	public synchronized Path ensureArtifact(MatchResultV1 result) throws IOException {
		Objects.requireNonNull(result, "result must not be null");
		Files.createDirectories(directory);
		String canonical = result.canonicalJson();
		Path artifact = directory.resolve("match-" + result.matchId() + ".json");
		if (Files.exists(artifact)) {
			String existing = Files.readString(artifact, StandardCharsets.UTF_8);
			if (!existing.equals(canonical)) {
				throw new IOException("MATCH_RESULT_CONFLICT: existing artifact differs for " + result.matchId());
			}
		} else {
			writeAtomic(artifact, canonical);
		}
		return artifact;
	}

	public CompletableFuture<Path> writeAsync(MatchResultV1 result, Executor executor) {
		Objects.requireNonNull(executor, "executor must not be null");
		return CompletableFuture.supplyAsync(() -> {
			try {
				return write(result);
			} catch (IOException exception) {
				throw new java.io.UncheckedIOException(exception);
			}
		}, executor);
	}

	private void writeJournalIdempotently(String matchId, String hash, String canonical) throws IOException {
		Path journal = directory.resolve("match-results.jsonl");
		Path previous = directory.resolve("match-results.previous.jsonl");
		byte[] entry = (canonical + "\n").getBytes(StandardCharsets.UTF_8);
		if (entry.length - 1 > MAX_JOURNAL_LINE_BYTES) {
			throw new IOException("MATCH_RESULT_TOO_LARGE: journal entry exceeds the line limit");
		}

		enforceJournalBudget(journal, previous);

		JournalScan archiveScan = scanJournal(previous, matchId, hash, canonical);
		if (archiveScan.invalid()) Files.deleteIfExists(previous);

		long journalBytes = Files.exists(journal) ? Files.size(journal) : 0L;
		JournalScan activeScan = scanJournal(journal, matchId, hash, canonical);
		if (activeScan.invalid()) {
			if (Files.exists(previous)) Files.deleteIfExists(journal);
			else rotateJournal(journal, previous);
			journalBytes = 0L;
		}
		if (archiveScan.matchFound() || activeScan.matchFound()) return;

		if (activeScan.lineCount() >= MAX_JOURNAL_LINES
				|| journalBytes + entry.length > MAX_ACTIVE_JOURNAL_BYTES) {
			rotateJournal(journal, previous);
		}
		try (FileChannel channel = FileChannel.open(
				journal, StandardOpenOption.CREATE, StandardOpenOption.WRITE, StandardOpenOption.APPEND
		)) {
			ByteBuffer buffer = ByteBuffer.wrap(entry);
			while (buffer.hasRemaining()) channel.write(buffer);
			channel.force(true);
		}
		enforceJournalBudget(journal, previous);
	}

	private static JournalScan scanJournal(Path path, String matchId, String hash, String canonical) throws IOException {
		if (!Files.exists(path) || Files.size(path) == 0L) return new JournalScan(false, false, 0);
		int lineCount = 0;
		boolean matchFound = false;
		try (BufferedReader reader = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
			String line;
			while ((line = reader.readLine()) != null) {
				lineCount++;
				if (lineCount > MAX_JOURNAL_LINES || line.getBytes(StandardCharsets.UTF_8).length > MAX_JOURNAL_LINE_BYTES) {
					return new JournalScan(false, true, lineCount);
				}
				if (line.isBlank()) continue;
				try {
					if (matchesExisting(line, matchId, hash, canonical)) matchFound = true;
				} catch (InvalidJournalException exception) {
					return new JournalScan(false, true, lineCount);
				}
			}
		}
		return new JournalScan(matchFound, false, lineCount);
	}

	private static boolean matchesExisting(String line, String matchId, String hash, String canonical) throws IOException {
		try {
			var element = JsonParser.parseString(line);
			if (!element.isJsonObject()) {
				throw new InvalidJournalException("journal line is not an object");
			}
			var object = element.getAsJsonObject();
			if (!object.has("matchId") || !object.get("matchId").isJsonPrimitive()
					|| !object.get("matchId").getAsJsonPrimitive().isString()
					|| !object.get("matchId").getAsString().equals(matchId)) return false;
			String existingHash = object.has("canonicalSha256")
					&& object.get("canonicalSha256").isJsonPrimitive()
					&& object.get("canonicalSha256").getAsJsonPrimitive().isString()
					? object.get("canonicalSha256").getAsString() : "";
			if (!existingHash.equals(hash) || !line.equals(canonical)) {
				throw new IOException("MATCH_RESULT_CONFLICT: journal differs for " + matchId);
			}
			return true;
		} catch (JsonParseException | IllegalStateException exception) {
			throw new InvalidJournalException("malformed journal line", exception);
		}
	}

	private static void rotateJournal(Path journal, Path previous) throws IOException {
		if (!Files.exists(journal)) return;
		if (Files.size(journal) > MAX_ARCHIVE_JOURNAL_BYTES) {
			Files.delete(journal);
			return;
		}
		Files.move(journal, previous, StandardCopyOption.REPLACE_EXISTING);
	}

	private static void enforceJournalBudget(Path journal, Path previous) throws IOException {
		discardOversized(previous, MAX_ARCHIVE_JOURNAL_BYTES);
		discardOversized(journal, MAX_ACTIVE_JOURNAL_BYTES);
		long activeBytes = Files.exists(journal) ? Files.size(journal) : 0L;
		long archiveBytes = Files.exists(previous) ? Files.size(previous) : 0L;
		if (activeBytes + archiveBytes > MAX_TOTAL_JOURNAL_BYTES) {
			Files.deleteIfExists(previous);
		}
	}

	private static void discardOversized(Path path, long maximumBytes) throws IOException {
		if (Files.exists(path) && Files.size(path) > maximumBytes) Files.delete(path);
	}

	private static final class InvalidJournalException extends IOException {
		private InvalidJournalException(String message) {
			super("MATCH_RESULT_JOURNAL_INVALID: " + message);
		}

		private InvalidJournalException(String message, Throwable cause) {
			super("MATCH_RESULT_JOURNAL_INVALID: " + message, cause);
		}
	}

	private record JournalScan(boolean matchFound, boolean invalid, int lineCount) { }

	private static void writeAtomic(Path target, String value) throws IOException {
		Path parent = target.getParent();
		Path temporary = Files.createTempFile(parent, target.getFileName().toString() + ".", ".tmp");
		boolean moved = false;
		try {
			byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
			try (FileChannel channel = FileChannel.open(
					temporary, StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING
			)) {
				ByteBuffer buffer = ByteBuffer.wrap(bytes);
				while (buffer.hasRemaining()) channel.write(buffer);
				channel.force(true);
			}
			moveDurably(temporary, target, Files::move);
			moved = true;
		} finally {
			if (!moved) Files.deleteIfExists(temporary);
		}
	}

	/**
	 * Uses an atomic rename when the filesystem supports it. Filesystems that reject
	 * atomic moves fall back to a same-directory replacement of the already-synced
	 * temporary file. The fallback prevents a permanent capability mismatch from
	 * holding the scenario result latch forever.
	 */
	public static void moveDurably(Path temporary, Path target, MoveOperation move) throws IOException {
		Objects.requireNonNull(temporary, "temporary must not be null");
		Objects.requireNonNull(target, "target must not be null");
		Objects.requireNonNull(move, "move must not be null");
		try {
			move.move(temporary, target,
					StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
		} catch (AtomicMoveNotSupportedException unsupported) {
			move.move(temporary, target, StandardCopyOption.REPLACE_EXISTING);
		}
	}

	@FunctionalInterface
	public interface MoveOperation {
		Path move(Path source, Path target, java.nio.file.CopyOption... options) throws IOException;
	}
}
