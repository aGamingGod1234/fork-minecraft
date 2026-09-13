package dev.agaminggod.arenaagents.server;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/** Verifies bounded coordinator stdout/stderr rotation before a new launch. */
public final class CoordinatorLogRotationVerification {
	private CoordinatorLogRotationVerification() {
	}

	public static int verify() throws Exception {
		Path directory = Files.createTempDirectory("arena-coordinator-log-rotation");
		try {
			return verifySmallLogsRemainUntouched(directory) + verifyGenerationsAreBounded(directory);
		} finally {
			try (var paths = Files.walk(directory)) {
				for (Path path : paths.sorted(java.util.Comparator.reverseOrder()).toList()) {
					Files.deleteIfExists(path);
				}
			}
		}
	}

	private static int verifySmallLogsRemainUntouched(Path directory) throws Exception {
		Path stdout = directory.resolve("arena-agents-coordinator.log");
		Path stderr = directory.resolve("arena-agents-coordinator-error.log");
		Files.writeString(stdout, "small stdout\n", StandardCharsets.UTF_8);
		Files.writeString(stderr, "small stderr\n", StandardCharsets.UTF_8);

		CoordinatorLogRotation.rotate(directory);

		assertEquals("small stdout\n", Files.readString(stdout), "small stdout remains in place");
		assertEquals("small stderr\n", Files.readString(stderr), "small stderr remains in place");
		assertTrue(!Files.exists(stdout.resolveSibling(stdout.getFileName() + ".1")),
				"small stdout does not create a generation");
		assertTrue(!Files.exists(stderr.resolveSibling(stderr.getFileName() + ".1")),
				"small stderr does not create a generation");
		return 4;
	}

	private static int verifyGenerationsAreBounded(Path directory) throws Exception {
		Path stdout = directory.resolve("arena-agents-coordinator.log");
		Path stderr = directory.resolve("arena-agents-coordinator-error.log");
		String oversized = "x".repeat(Math.toIntExact(CoordinatorLogRotation.ROTATION_THRESHOLD_BYTES + 1));
		Files.writeString(stdout, oversized, StandardCharsets.UTF_8);
		Files.writeString(stderr, oversized, StandardCharsets.UTF_8);
		writeGeneration(stdout, 1, "stdout-one");
		writeGeneration(stdout, 2, "stdout-two");
		writeGeneration(stdout, 3, "stdout-three");
		writeGeneration(stderr, 1, "stderr-one");
		writeGeneration(stderr, 2, "stderr-two");
		writeGeneration(stderr, 3, "stderr-three");

		CoordinatorLogRotation.rotate(directory);

		assertEquals(oversized, Files.readString(generation(stdout, 1)), "stdout current file becomes generation one");
		assertEquals("stdout-one", Files.readString(generation(stdout, 2)), "stdout generation one advances");
		assertEquals("stdout-two", Files.readString(generation(stdout, 3)), "stdout generation two advances");
		assertTrue(!Files.exists(generation(stdout, 4)), "stdout generation three is discarded");
		assertEquals(oversized, Files.readString(generation(stderr, 1)), "stderr current file becomes generation one");
		assertEquals("stderr-one", Files.readString(generation(stderr, 2)), "stderr generation one advances");
		assertEquals("stderr-two", Files.readString(generation(stderr, 3)), "stderr generation two advances");
		assertTrue(!Files.exists(generation(stderr, 4)), "stderr generation three is discarded");
		assertTrue(!Files.exists(stdout), "stdout current file is absent until the next launch");
		assertTrue(!Files.exists(stderr), "stderr current file is absent until the next launch");
		return 10;
	}

	private static void writeGeneration(Path log, int generation, String content) throws Exception {
		Files.writeString(generation(log, generation), content, StandardCharsets.UTF_8);
	}

	private static Path generation(Path log, int generation) {
		return log.resolveSibling(log.getFileName() + "." + generation);
	}

	private static void assertEquals(Object expected, Object actual, String label) {
		if (!java.util.Objects.equals(expected, actual)) {
			throw new AssertionError(label + ": expected=" + expected + ", actual=" + actual);
		}
	}

	private static void assertTrue(boolean condition, String label) {
		if (!condition) throw new AssertionError(label);
	}
}
