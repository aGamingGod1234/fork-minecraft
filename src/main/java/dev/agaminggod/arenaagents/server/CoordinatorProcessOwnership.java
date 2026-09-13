package dev.agaminggod.arenaagents.server;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Properties;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

/** Records the coordinator's Minecraft owner and removes only proven orphaned coordinator processes. */
final class CoordinatorProcessOwnership {
	private static final String OWNERSHIP_PATH = "runtime/coordinator-process.properties";
	private static final long EXIT_TIMEOUT_MS = 2_000L;
	/** Bounds persisted process identities while covering the coordinator's maximum practical provider fan-out. */
	static final int MAX_TRACKED_DESCENDANTS = 256;

	private CoordinatorProcessOwnership() {
	}

	@FunctionalInterface
	interface PosixSessionTerminator {
		PosixCoordinatorSession.Termination terminate(
				Path runtimeRoot,
				String launchId,
				ProcessIdentity sessionIdentity
		) throws IOException;
	}

	static Path ownershipFile(Path runtimeRoot) {
		return normalizeRoot(runtimeRoot).resolve(OWNERSHIP_PATH).normalize();
	}

	static void record(Path runtimeRoot, Process process, Path main) throws IOException {
		Path root = normalizeRoot(runtimeRoot);
		ProcessHandle owner = ProcessHandle.current();
		record(root, process, main, BundledCoordinatorInstaller.validate(root).generationId(),
				UUID.randomUUID().toString(), SupervisorIdentity.create(), owner.pid(), ownerStartEpochMs(owner));
	}

	static void record(Path runtimeRoot, Process process, Path main, long ownerPid) throws IOException {
		Path root = normalizeRoot(runtimeRoot);
		ProcessHandle owner = ProcessHandle.of(ownerPid)
				.orElseThrow(() -> new IOException("coordinator owner process is unavailable"));
		record(root, process, main, BundledCoordinatorInstaller.validate(root).generationId(),
				UUID.randomUUID().toString(), SupervisorIdentity.create(), ownerPid, ownerStartEpochMs(owner));
	}

	static void record(
			Path runtimeRoot,
			Process process,
			Path main,
			String generationId,
			String launchId
	) throws IOException {
		ProcessHandle owner = ProcessHandle.current();
		record(runtimeRoot, process, main, generationId, launchId, SupervisorIdentity.create(),
				owner.pid(), ownerStartEpochMs(owner));
	}

	static void record(
			Path runtimeRoot,
			Process process,
			Path main,
			String generationId,
			String launchId,
			SupervisorIdentity supervisorIdentity
	) throws IOException {
		ProcessHandle owner = ProcessHandle.current();
		record(runtimeRoot, process, main, generationId, launchId, supervisorIdentity,
				owner.pid(), ownerStartEpochMs(owner));
	}

	static void record(
			Path runtimeRoot,
			Process process,
			Path main,
			String generationId,
			String launchId,
			long ownerPid
	) throws IOException {
		ProcessHandle owner = ProcessHandle.of(ownerPid)
				.orElseThrow(() -> new IOException("coordinator owner process is unavailable"));
		record(runtimeRoot, process, main, generationId, launchId, SupervisorIdentity.create(),
				ownerPid, ownerStartEpochMs(owner));
	}

	private static synchronized void record(
			Path runtimeRoot,
			Process process,
			Path main,
			String generationId,
			String launchId,
			SupervisorIdentity supervisorIdentity,
			long ownerPid,
			long ownerStartedAtEpochMs
	) throws IOException {
		Path root = normalizeRoot(runtimeRoot);
		Path expectedMain = normalizeMain(root, main);
		String expectedGeneration = normalizeGeneration(generationId);
		String expectedLaunch = normalizeLaunchId(launchId);
		SupervisorIdentity expectedSupervisor = java.util.Objects.requireNonNull(
				supervisorIdentity, "supervisor identity must not be null"
		);
		if (ownerPid <= 0L) throw new IllegalArgumentException("coordinator owner pid must be positive");
		if (ownerStartedAtEpochMs <= 0L) throw new IllegalArgumentException("coordinator owner start time must be positive");
		long startedAtEpochMs = process.info().startInstant()
				.orElseThrow(() -> new IOException("coordinator process start time is unavailable"))
				.toEpochMilli();
		write(root, new Ownership(
				process.pid(), ownerPid, ownerStartedAtEpochMs, startedAtEpochMs,
				expectedGeneration, expectedLaunch, Optional.of(expectedSupervisor), Optional.empty(), List.of()
		), expectedMain);
	}

	static synchronized void recordPosix(
			Path runtimeRoot,
			Path main,
			String generationId,
			String launchId,
			SupervisorIdentity supervisorIdentity,
			ProcessIdentity sessionIdentity,
			ProcessIdentity rootIdentity
	) throws IOException {
		Path root = normalizeRoot(runtimeRoot);
		Path expectedMain = normalizeMain(root, main);
		String expectedGeneration = normalizeGeneration(generationId);
		String expectedLaunch = normalizeLaunchId(launchId);
		SupervisorIdentity expectedSupervisor = java.util.Objects.requireNonNull(
				supervisorIdentity, "supervisor identity must not be null"
		);
		ProcessIdentity expectedSession = java.util.Objects.requireNonNull(
				sessionIdentity, "POSIX session identity must not be null"
		);
		ProcessIdentity expectedRoot = java.util.Objects.requireNonNull(
				rootIdentity, "coordinator root identity must not be null"
		);
		expectedRoot.resolve().orElseThrow(() -> new IOException("POSIX coordinator root is unavailable"));
		ProcessHandle owner = ProcessHandle.current();
		Ownership ownership = new Ownership(
				expectedRoot.pid(), owner.pid(), ownerStartEpochMs(owner), expectedRoot.startedAtEpochMs(),
				expectedGeneration, expectedLaunch, Optional.of(expectedSupervisor), Optional.of(expectedSession), List.of()
		);
		writeNew(root, ownership, expectedMain);
	}

	private static void write(Path root, Ownership ownership, Path expectedMain) throws IOException {
		write(root, ownership, expectedMain, true);
	}

	private static void writeNew(Path root, Ownership ownership, Path expectedMain) throws IOException {
		write(root, ownership, expectedMain, false);
	}

	private static void write(Path root, Ownership ownership, Path expectedMain, boolean replace) throws IOException {
		Properties values = new Properties();
		values.setProperty("pid", Long.toString(ownership.pid()));
		values.setProperty("ownerPid", Long.toString(ownership.ownerPid()));
		values.setProperty("ownerStartedAtEpochMs", Long.toString(ownership.ownerStartedAtEpochMs()));
		values.setProperty("startedAtEpochMs", Long.toString(ownership.startedAtEpochMs()));
		values.setProperty("main", expectedMain.toString());
		values.setProperty("generationId", ownership.generationId());
		values.setProperty("launchId", ownership.launchId());
		ownership.supervisorIdentity().ifPresent(identity ->
				values.setProperty("supervisorId", identity.value()));
		ownership.posixSessionIdentity().ifPresent(identity -> {
			values.setProperty("posixSessionPid", Long.toString(identity.pid()));
			values.setProperty("posixSessionStartedAtEpochMs", Long.toString(identity.startedAtEpochMs()));
		});
		if (!ownership.descendants().isEmpty()) {
			values.setProperty("descendants", ownership.descendants().stream()
					.map(ProcessIdentity::serialized)
					.collect(java.util.stream.Collectors.joining(",")));
		}
		Path ownershipFile = ownershipFile(root);
		Files.createDirectories(ownershipFile.getParent());
		Path staging = ownershipFile.resolveSibling(ownershipFile.getFileName() + ".staging-" + UUID.randomUUID());
		try {
			try (Writer writer = Files.newBufferedWriter(staging, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)) {
				values.store(writer, "Arena Agents coordinator ownership");
			}
			if (replace) {
				try {
					Files.move(staging, ownershipFile, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
				} catch (java.nio.file.AtomicMoveNotSupportedException unsupported) {
					Files.move(staging, ownershipFile, StandardCopyOption.REPLACE_EXISTING);
				}
			} else {
				// Linking a fully-written staging inode publishes the claim atomically and
				// fails if any supervisor already owns this runtime root.
				Files.createLink(ownershipFile, staging);
			}
		} finally {
			Files.deleteIfExists(staging);
		}
	}

	static synchronized void trackDescendants(
			Path runtimeRoot,
			long processPid,
			long startedAtEpochMs,
			String generationId,
			String launchId,
			List<ProcessIdentity> descendants
	) throws IOException {
		Path root = normalizeRoot(runtimeRoot);
		String expectedGeneration = normalizeGeneration(generationId);
		String expectedLaunch = normalizeLaunchId(launchId);
		if (descendants.size() > MAX_TRACKED_DESCENDANTS) {
			throw new IllegalStateException("coordinator descendant count exceeds the ownership limit");
		}
		Ownership ownership = read(root);
		if (ownership == null || ownership.pid() != processPid
				|| ownership.startedAtEpochMs() != startedAtEpochMs
				|| !ownership.generationId().equals(expectedGeneration)
				|| !ownership.launchId().equals(expectedLaunch)) return;
		LinkedHashSet<ProcessIdentity> merged = new LinkedHashSet<>();
		ownership.descendants().stream().filter(ProcessIdentity::isAlive).forEach(merged::add);
		descendants.stream().filter(ProcessIdentity::isAlive).forEach(merged::add);
		if (merged.size() > MAX_TRACKED_DESCENDANTS) {
			throw new IllegalStateException("coordinator descendant count exceeds the ownership limit");
		}
		List<ProcessIdentity> tracked = List.copyOf(merged);
		if (tracked.equals(ownership.descendants())) return;
		write(root, ownership.withDescendants(tracked), root.resolve("coordinator/src/dynamic-main.mjs").normalize());
	}

	static List<ProcessIdentity> captureDescendants(ProcessHandle process) {
		List<ProcessHandle> handles = process.descendants().limit(MAX_TRACKED_DESCENDANTS + 1L).toList();
		if (handles.size() > MAX_TRACKED_DESCENDANTS) {
			throw new IllegalStateException("coordinator descendant count exceeds the ownership limit");
		}
		return handles.stream().map(ProcessIdentity::from).flatMap(Optional::stream).toList();
	}

	static synchronized int reapOrphaned(Path runtimeRoot) throws IOException {
		Path root = normalizeRoot(runtimeRoot);
		Path ownershipFile = ownershipFile(root);
		if (!Files.isRegularFile(ownershipFile)) return 0;
		Ownership ownership = read(root);
		if (ownership == null) {
			Files.deleteIfExists(ownershipFile);
			return 0;
		}
		if (ownerAlive(ownership)) return 0;
		// The recorded process identity is sufficient to prove ownership. Requiring the
		// active bundle to validate here prevents orphan cleanup precisely when a broken
		// active generation must be moved out of the way for rollback.
		return reapOrphanedRecord(root, ownership, PosixCoordinatorSession::terminateExisting);
	}

	static synchronized int reapOrphaned(Path runtimeRoot, String activeGenerationId) throws IOException {
		Path root = normalizeRoot(runtimeRoot);
		normalizeGeneration(activeGenerationId);
		Ownership ownership = read(root);
		if (ownership != null && ownerAlive(ownership)) return 0;
		if (ownership == null) return 0;
		return reapOrphanedRecord(root, ownership, PosixCoordinatorSession::terminateExisting);
	}

	static synchronized int reapOrphaned(Path runtimeRoot, SupervisorIdentity currentSupervisor) throws IOException {
		return reapOrphaned(runtimeRoot, currentSupervisor, PosixCoordinatorSession::terminateExisting);
	}

	static synchronized int reapOrphaned(
			Path runtimeRoot,
			SupervisorIdentity currentSupervisor,
			PosixSessionTerminator posixTerminator
	) throws IOException {
		Path root = normalizeRoot(runtimeRoot);
		Path ownershipFile = ownershipFile(root);
		if (!Files.isRegularFile(ownershipFile)) return 0;
		Ownership ownership = read(root);
		if (ownership == null) {
			Files.deleteIfExists(ownershipFile);
			return 0;
		}
		if (ownerAlive(ownership, currentSupervisor)) return 0;
		return reapOrphanedRecord(root, ownership, java.util.Objects.requireNonNull(
				posixTerminator, "POSIX session terminator must not be null"
		));
	}

	private static int reapOrphanedRecord(
			Path root,
			Ownership ownership,
			PosixSessionTerminator posixTerminator
	) throws IOException {
		Path expectedMain = root.resolve("coordinator/src/dynamic-main.mjs").normalize();
		int reaped = 0;
		if (WindowsCoordinatorJob.terminateExisting(ownership.launchId())) {
			clearIfMatching(root, ownership);
			return 1;
		}
		if (ownership.posixSessionIdentity().isPresent()) {
			PosixCoordinatorSession.Termination termination = posixTerminator.terminate(
					root, ownership.launchId(), ownership.posixSessionIdentity().orElseThrow()
			);
			if (termination == PosixCoordinatorSession.Termination.TERMINATED) {
				clearIfMatching(root, ownership);
				return 1;
			}
			if (Files.exists(PosixCoordinatorSession.claimFile(root, ownership.launchId()))) {
				throw new IOException("Recorded POSIX coordinator session disappeared before proving group cleanup");
			}
			// The wrapper removes its exclusive claim only after the process group is
			// empty. A missing wrapper plus a missing claim is therefore completed cleanup.
			clearIfMatching(root, ownership);
			return 1;
		}
		Optional<ProcessHandle> recorded = ProcessHandle.of(ownership.pid());
		if (recorded.isPresent() && matchesIdentity(recorded.get(), expectedMain, ownership.startedAtEpochMs())) {
			terminateTree(recorded.get(), ownership.descendants());
			reaped += 1;
		} else if (ownership.descendants().stream().anyMatch(ProcessIdentity::isAlive)) {
			terminateDescendants(ownership.descendants(), EXIT_TIMEOUT_MS);
			reaped += 1;
		}
		clearIfMatching(root, ownership);
		return reaped;
	}

	static void clear(Path runtimeRoot, long processPid) throws IOException {
		Optional<ProcessHandle> process = ProcessHandle.of(processPid);
		long startedAtEpochMs = process.flatMap(handle -> handle.info().startInstant())
				.map(start -> start.toEpochMilli())
				.orElse(-1L);
		clear(runtimeRoot, processPid, startedAtEpochMs);
	}

	static void clear(Path runtimeRoot, Process process) throws IOException {
		long startedAtEpochMs = process.info().startInstant()
				.map(start -> start.toEpochMilli())
				.orElse(-1L);
		clear(runtimeRoot, process.pid(), startedAtEpochMs);
	}

	static void clear(Path runtimeRoot, Process process, String generationId, String launchId) throws IOException {
		long startedAtEpochMs = process.info().startInstant()
				.map(start -> start.toEpochMilli())
				.orElse(-1L);
		clear(runtimeRoot, process.pid(), startedAtEpochMs, normalizeGeneration(generationId), normalizeLaunchId(launchId));
	}

	private static synchronized void clear(Path runtimeRoot, long processPid, long startedAtEpochMs) throws IOException {
		Path root = normalizeRoot(runtimeRoot);
		Ownership ownership = read(root);
		if (ownership == null || (ownership.pid() == processPid && ownership.startedAtEpochMs() == startedAtEpochMs)) {
			Files.deleteIfExists(ownershipFile(root));
		}
	}

	static synchronized void clear(
			Path runtimeRoot,
			long processPid,
			long startedAtEpochMs,
			String generationId,
			String launchId
	) throws IOException {
		Path root = normalizeRoot(runtimeRoot);
		Ownership ownership = read(root);
		if (ownership != null && ownership.pid() == processPid
				&& ownership.startedAtEpochMs() == startedAtEpochMs
				&& ownership.generationId().equals(generationId)
				&& ownership.launchId().equals(launchId)) {
			clearIfMatching(root, ownership);
		}
	}

	private static Ownership read(Path runtimeRoot) throws IOException {
		return read(runtimeRoot, ownershipFile(runtimeRoot));
	}

	private static Ownership read(Path runtimeRoot, Path file) throws IOException {
		if (!Files.isRegularFile(file)) return null;
		Properties values = new Properties();
		try (Reader reader = Files.newBufferedReader(file)) {
			values.load(reader);
		}
		try {
			long pid = Long.parseLong(values.getProperty("pid", ""));
			long ownerPid = Long.parseLong(values.getProperty("ownerPid", ""));
			String ownerStartedAtValue = values.getProperty("ownerStartedAtEpochMs");
			long ownerStartedAtEpochMs = ownerStartedAtValue == null
					? -1L : Long.parseLong(ownerStartedAtValue);
			long startedAtEpochMs = Long.parseLong(values.getProperty("startedAtEpochMs", ""));
			String generationId = normalizeGeneration(values.getProperty("generationId", ""));
			String launchId = normalizeLaunchId(values.getProperty("launchId", ""));
			String supervisorId = values.getProperty("supervisorId");
			Optional<SupervisorIdentity> supervisorIdentity = supervisorId == null
					? Optional.empty() : Optional.of(new SupervisorIdentity(supervisorId));
			String posixSessionPid = values.getProperty("posixSessionPid");
			String posixSessionStartedAt = values.getProperty("posixSessionStartedAtEpochMs");
			Optional<ProcessIdentity> posixSessionIdentity;
			if (posixSessionPid == null && posixSessionStartedAt == null) {
				posixSessionIdentity = Optional.empty();
			} else if (posixSessionPid != null && posixSessionStartedAt != null) {
				posixSessionIdentity = Optional.of(new ProcessIdentity(
						Long.parseLong(posixSessionPid), Long.parseLong(posixSessionStartedAt)
				));
			} else {
				return null;
			}
			List<ProcessIdentity> descendants = parseDescendants(values.getProperty("descendants", ""));
			Path main = Path.of(values.getProperty("main", "")).toAbsolutePath().normalize();
			Path expectedMain = normalizeRoot(runtimeRoot).resolve("coordinator/src/dynamic-main.mjs").normalize();
			if (pid <= 0L || ownerPid <= 0L || startedAtEpochMs <= 0L
					|| (ownerStartedAtValue != null && ownerStartedAtEpochMs <= 0L)
					|| !samePath(main, expectedMain)) return null;
			return new Ownership(
					pid, ownerPid, ownerStartedAtEpochMs, startedAtEpochMs, generationId, launchId,
					supervisorIdentity, posixSessionIdentity, descendants
			);
		} catch (RuntimeException invalid) {
			return null;
		}
	}

	private static boolean ownerAlive(Ownership ownership) {
		if (ownership.ownerStartedAtEpochMs() <= 0L) {
			// Legacy records predate the owner start identity. Only the process running
			// this cleanup may conservatively keep such a record alive; another process
			// with the old PID cannot suppress orphan cleanup without proof of identity.
			return ownership.ownerPid() == ProcessHandle.current().pid();
		}
		return ProcessHandle.of(ownership.ownerPid())
				.map(handle -> handle.isAlive() && sameStart(handle, ownership.ownerStartedAtEpochMs()))
				.orElse(false);
	}

	private static boolean ownerAlive(Ownership ownership, SupervisorIdentity currentSupervisor) {
		java.util.Objects.requireNonNull(currentSupervisor, "current supervisor identity must not be null");
		return ownership.supervisorIdentity().filter(currentSupervisor::equals).isPresent()
				&& ownerAlive(ownership);
	}

	private static long ownerStartEpochMs(ProcessHandle owner) throws IOException {
		return owner.info().startInstant()
				.orElseThrow(() -> new IOException("coordinator owner process start time is unavailable"))
				.toEpochMilli();
	}

	private static boolean sameStart(ProcessHandle handle, long expectedEpochMs) {
		return handle.info().startInstant().map(start -> start.toEpochMilli() == expectedEpochMs).orElse(false);
	}

	private static boolean matchesIdentity(ProcessHandle handle, Path expectedMain, long expectedEpochMs) {
		if (!handle.isAlive() || !sameStart(handle, expectedEpochMs)) return false;
		ProcessHandle.Info info = handle.info();
		if (info.arguments().isEmpty() && info.commandLine().isEmpty()) return true;
		return ownsMain(handle, expectedMain);
	}

	private static boolean ownsMain(ProcessHandle handle, Path expectedMain) {
		String[] arguments = handle.info().arguments().orElse(null);
		if (arguments != null && java.util.Arrays.stream(arguments).anyMatch(argument -> argumentMatchesMain(argument, expectedMain))) {
			return true;
		}
		String commandLine = handle.info().commandLine().orElse("");
		return containsPathAsArgument(commandLine, expectedMain);
	}

	private static boolean argumentMatchesMain(String argument, Path expectedMain) {
		try {
			return samePath(Path.of(argument).toAbsolutePath().normalize(), expectedMain);
		} catch (RuntimeException invalidPath) {
			return false;
		}
	}

	private static boolean containsPathAsArgument(String commandLine, Path expectedMain) {
		String command = normalizedText(commandLine);
		String expected = normalizedText(expectedMain.toString());
		int offset = command.indexOf(expected);
		while (offset >= 0) {
			int end = offset + expected.length();
			boolean startsArgument = offset == 0 || isCommandLineBoundary(command.charAt(offset - 1));
			boolean endsArgument = end == command.length() || isCommandLineBoundary(command.charAt(end));
			if (startsArgument && endsArgument) return true;
			offset = command.indexOf(expected, offset + 1);
		}
		return false;
	}

	private static boolean isCommandLineBoundary(char value) {
		return Character.isWhitespace(value) || value == '"' || value == '\'';
	}

	private static String normalizedText(String value) {
		String normalized = value.replace('/', '\\');
		return isWindows() ? normalized.toLowerCase(Locale.ROOT) : normalized;
	}

	private static boolean samePath(Path left, Path right) {
		if (isWindows()) return left.toString().equalsIgnoreCase(right.toString());
		return left.equals(right);
	}

	private static Path normalizeMain(Path root, Path main) {
		Path normalized = main.toAbsolutePath().normalize();
		Path expected = root.resolve("coordinator/src/dynamic-main.mjs").normalize();
		if (!samePath(normalized, expected)) throw new IllegalArgumentException("coordinator main must belong to its runtime root");
		return normalized;
	}

	private static Path normalizeRoot(Path runtimeRoot) {
		return runtimeRoot.toAbsolutePath().normalize();
	}

	private static boolean clearIfMatching(Path root, Ownership expected) throws IOException {
		Path ownership = ownershipFile(root);
		Path claimed = ownership.resolveSibling(ownership.getFileName() + ".clear-" + UUID.randomUUID());
		try {
			moveWithoutReplace(ownership, claimed);
		} catch (NoSuchFileException missing) {
			return false;
		}
		Ownership claimedOwnership = read(root, claimed);
		if (expected.equals(claimedOwnership)) {
			Files.deleteIfExists(claimed);
			return true;
		}
		try {
			Files.move(claimed, ownership);
		} catch (FileAlreadyExistsException newerOwnershipPublished) {
			Files.deleteIfExists(claimed);
		}
		return false;
	}

	private static void moveWithoutReplace(Path source, Path target) throws IOException {
		try {
			Files.move(source, target, StandardCopyOption.ATOMIC_MOVE);
		} catch (AtomicMoveNotSupportedException unsupported) {
			Files.move(source, target);
		}
	}

	private static String normalizeGeneration(String generationId) {
		String normalized = java.util.Objects.requireNonNull(generationId, "generation ID must not be null");
		if (!normalized.matches("[0-9a-f]{64}")) throw new IllegalArgumentException("coordinator generation ID is invalid");
		return normalized;
	}

	private static String normalizeLaunchId(String launchId) {
		return UUID.fromString(java.util.Objects.requireNonNull(launchId, "launch ID must not be null")).toString();
	}

	private static List<ProcessIdentity> parseDescendants(String serialized) {
		if (serialized == null || serialized.isBlank()) return List.of();
		String[] entries = serialized.split(",", -1);
		if (entries.length > MAX_TRACKED_DESCENDANTS) {
			throw new IllegalArgumentException("coordinator descendant count exceeds the ownership limit");
		}
		LinkedHashSet<ProcessIdentity> identities = new LinkedHashSet<>();
		for (String entry : entries) identities.add(ProcessIdentity.parse(entry));
		return List.copyOf(identities);
	}

	static void terminateTree(ProcessHandle process) {
		terminateTree(process, EXIT_TIMEOUT_MS);
	}

	static void terminateTree(ProcessHandle process, long exitTimeoutMs) {
		terminateTree(process, List.of(), exitTimeoutMs);
	}

	static void terminateTree(ProcessHandle process, List<ProcessIdentity> preservedDescendants) {
		terminateTree(process, preservedDescendants, EXIT_TIMEOUT_MS);
	}

	private static void terminateTree(
			ProcessHandle process,
			List<ProcessIdentity> preservedDescendants,
			long exitTimeoutMs
	) {
		terminateOwned(process, preservedDescendants, exitTimeoutMs);
	}

	private static void terminateDescendants(List<ProcessIdentity> identities, long exitTimeoutMs) {
		terminateOwned(null, identities, exitTimeoutMs);
	}

	private static void terminateOwned(
			ProcessHandle process,
			List<ProcessIdentity> preservedDescendants,
			long exitTimeoutMs
	) {
		if (exitTimeoutMs < 0L) throw new IllegalArgumentException("process exit timeout must not be negative");
		boolean interrupted = Thread.interrupted();
		try {
			LinkedHashSet<ProcessHandle> descendants = new LinkedHashSet<>();
			if (process != null) descendants.addAll(process.descendants().toList());
			resolveAlive(preservedDescendants).forEach(descendants::add);
			descendants.forEach(ProcessHandle::destroy);
			if (process != null && process.isAlive()) process.destroy();
			interrupted |= awaitExit(process, List.copyOf(descendants), exitTimeoutMs);
			if (process != null) descendants.addAll(process.descendants().toList());
			resolveAlive(preservedDescendants).forEach(descendants::add);
			descendants.stream().filter(ProcessHandle::isAlive).forEach(ProcessHandle::destroyForcibly);
			if (process != null && process.isAlive()) process.destroyForcibly();
			interrupted |= awaitExit(process, List.copyOf(descendants), exitTimeoutMs);
			if ((process != null && process.isAlive()) || descendants.stream().anyMatch(ProcessHandle::isAlive)) {
				throw new IllegalStateException("Owned coordinator process tree is still alive after termination");
			}
		} finally {
			if (interrupted) Thread.currentThread().interrupt();
		}
	}

	private static List<ProcessHandle> resolveAlive(List<ProcessIdentity> identities) {
		return identities.stream().map(ProcessIdentity::resolve).flatMap(Optional::stream).toList();
	}

	private static boolean awaitExit(ProcessHandle process, List<ProcessHandle> descendants, long timeoutMs) {
		boolean interrupted = false;
		long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMs);
		while (((process != null && process.isAlive()) || descendants.stream().anyMatch(ProcessHandle::isAlive))
				&& System.nanoTime() < deadline) {
			try {
				Thread.sleep(10L);
			} catch (InterruptedException interruption) {
				interrupted = true;
			}
		}
		return interrupted;
	}

	private static boolean isWindows() {
		return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");
	}

	private record Ownership(
			long pid,
			long ownerPid,
			long ownerStartedAtEpochMs,
			long startedAtEpochMs,
			String generationId,
			String launchId,
			Optional<SupervisorIdentity> supervisorIdentity,
			Optional<ProcessIdentity> posixSessionIdentity,
			List<ProcessIdentity> descendants
	) {
		private Ownership withDescendants(List<ProcessIdentity> tracked) {
			return new Ownership(
					pid, ownerPid, ownerStartedAtEpochMs, startedAtEpochMs, generationId, launchId,
					supervisorIdentity, posixSessionIdentity, List.copyOf(tracked)
			);
		}
	}

	record SupervisorIdentity(String value) {
		SupervisorIdentity {
			value = UUID.fromString(java.util.Objects.requireNonNull(
					value, "supervisor identity must not be null"
			)).toString();
		}

		static SupervisorIdentity create() {
			return new SupervisorIdentity(UUID.randomUUID().toString());
		}
	}

	record ProcessIdentity(long pid, long startedAtEpochMs) {
		ProcessIdentity {
			if (pid <= 0L || startedAtEpochMs <= 0L) {
				throw new IllegalArgumentException("coordinator descendant identity is invalid");
			}
		}

		static Optional<ProcessIdentity> from(ProcessHandle process) {
			if (!process.isAlive()) return Optional.empty();
			return process.info().startInstant()
					.map(start -> new ProcessIdentity(process.pid(), start.toEpochMilli()));
		}

		static ProcessIdentity parse(String serialized) {
			String[] values = serialized.split(":", -1);
			if (values.length != 2) throw new IllegalArgumentException("coordinator descendant identity is invalid");
			return new ProcessIdentity(Long.parseLong(values[0]), Long.parseLong(values[1]));
		}

		String serialized() {
			return pid + ":" + startedAtEpochMs;
		}

		Optional<ProcessHandle> resolve() {
			return ProcessHandle.of(pid).filter(process -> process.isAlive() && sameStart(process, startedAtEpochMs));
		}

		boolean isAlive() {
			return resolve().isPresent();
		}
	}
}
