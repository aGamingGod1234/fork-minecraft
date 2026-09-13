package dev.agaminggod.arenaagents.server;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

/** Rotates coordinator output only when a log exceeds the bounded launch threshold. */
final class CoordinatorLogRotation {
	static final long ROTATION_THRESHOLD_BYTES = 8L * 1024L * 1024L;
	private static final int RETAINED_GENERATIONS = 3;
	private static final String STDOUT_FILE = "arena-agents-coordinator.log";
	private static final String STDERR_FILE = "arena-agents-coordinator-error.log";

	private CoordinatorLogRotation() {
	}

	static void rotate(Path logDirectory) throws IOException {
		Path directory = logDirectory.toAbsolutePath().normalize();
		rotateLog(directory.resolve(STDOUT_FILE));
		rotateLog(directory.resolve(STDERR_FILE));
	}

	private static void rotateLog(Path logFile) throws IOException {
		if (!Files.isRegularFile(logFile) || Files.size(logFile) <= ROTATION_THRESHOLD_BYTES) return;
		for (int generation = RETAINED_GENERATIONS; generation >= 1; generation--) {
			Path source = generationPath(logFile, generation - 1);
			Path target = generationPath(logFile, generation);
			if (generation == 1) source = logFile;
			if (!Files.exists(source)) continue;
			Files.move(source, target, StandardCopyOption.REPLACE_EXISTING);
		}
	}

	private static Path generationPath(Path logFile, int generation) {
		return logFile.resolveSibling(logFile.getFileName() + "." + generation);
	}
}
