package dev.agaminggod.arenaagents.server;

import java.io.IOException;
import java.io.Reader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Properties;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

/** Establishes a dedicated POSIX session before releasing coordinator code. */
final class PosixCoordinatorSession {
	private static final long START_TIMEOUT_MS = 5_000L;
	private static final long STOP_TIMEOUT_MS = 5_000L;
	private static final String CLAIM_PREFIX = "coordinator-posix-session-";

	private PosixCoordinatorSession() {
	}

	static boolean supported() {
		return !System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");
	}

	static Started start(
			CoordinatorProcessSupervisor.LaunchRequest request,
			CoordinatorProcessSupervisor.ProcessStarter processStarter
	) throws IOException {
		requireSupported();
		Path claim = claimFile(request.runtimeRoot(), request.launchId());
		Process process = processStarter.start(processBuilder(request, wrapperCommand(request, claim)));
		try {
			Descriptor descriptor = awaitDescriptor(claim, request.launchId(), process);
			if (descriptor.sessionPid() != process.pid()) {
				throw new IOException("POSIX coordinator wrapper reported the wrong session supervisor");
			}
			CoordinatorProcessOwnership.ProcessIdentity session = identity(process.toHandle(), "session supervisor");
			ProcessHandle root = ProcessHandle.of(descriptor.rootPid())
					.filter(ProcessHandle::isAlive)
					.orElseThrow(() -> new IOException("POSIX coordinator gate exited before ownership was recorded"));
			CoordinatorProcessOwnership.ProcessIdentity rootIdentity = identity(root, "coordinator gate");
			return new Started(process, session, rootIdentity, claim);
		} catch (IOException | RuntimeException failure) {
			terminateStarted(process);
			throw failure;
		}
	}

	static Termination terminateExisting(
			Path runtimeRoot,
			String launchId,
			CoordinatorProcessOwnership.ProcessIdentity expectedSession
	) throws IOException {
		if (!supported()) return Termination.ABSENT;
		ProcessHandle session = expectedSession.resolve().orElse(null);
		if (session == null) return Termination.ABSENT;
		Path expectedClaim = claimFile(runtimeRoot, launchId);
		Path expectedWrapper = normalizeRoot(runtimeRoot)
				.resolve("coordinator/src/posix-process-wrapper.mjs").normalize();
		if (!hasExactArguments(session, expectedWrapper, expectedClaim, launchId)) {
			throw new IOException("Could not verify the recorded POSIX coordinator session supervisor");
		}
		if (!session.destroy() && session.isAlive()) {
			throw new IOException("Could not signal the recorded POSIX coordinator session supervisor");
		}
		awaitExit(session, STOP_TIMEOUT_MS);
		return Termination.TERMINATED;
	}

	static Path claimFile(Path runtimeRoot, String launchId) {
		String normalizedLaunch = UUID.fromString(Objects.requireNonNull(
				launchId, "launch ID must not be null"
		)).toString();
		return normalizeRoot(runtimeRoot).resolve("runtime")
				.resolve(CLAIM_PREFIX + normalizedLaunch + ".properties").normalize();
	}

	static List<String> wrapperCommand(CoordinatorProcessSupervisor.LaunchRequest request, Path claim)
			throws IOException {
		List<String> command = request.command();
		if (command.size() < 2 || !samePathArgument(command.get(1), request.main())) {
			throw new IOException("Could not own POSIX coordinator launch: command does not own the configured main module");
		}
		Path wrapper = request.main().resolveSibling("posix-process-wrapper.mjs").toAbsolutePath().normalize();
		Path gate = request.main().resolveSibling("job-gate.mjs").toAbsolutePath().normalize();
		if (!Files.isRegularFile(wrapper)) {
			throw new IOException("Could not own POSIX coordinator launch: posix-process-wrapper.mjs is missing");
		}
		if (!Files.isRegularFile(gate)) {
			throw new IOException("Could not own POSIX coordinator launch: job-gate.mjs is missing");
		}
		ArrayList<String> wrapped = new ArrayList<>(command.size() + 4);
		wrapped.add(command.getFirst());
		wrapped.add(wrapper.toString());
		wrapped.add(claim.toString());
		wrapped.add(request.launchId());
		wrapped.add(gate.toString());
		wrapped.addAll(command.subList(1, command.size()));
		return List.copyOf(wrapped);
	}

	private static ProcessBuilder processBuilder(
			CoordinatorProcessSupervisor.LaunchRequest request,
			List<String> command
	) throws IOException {
		Files.createDirectories(claimFile(request.runtimeRoot(), request.launchId()).getParent());
		ProcessBuilder builder = new ProcessBuilder(command);
		builder.directory(request.workingDirectory().toFile());
		builder.environment().clear();
		builder.environment().putAll(request.environment());
		builder.redirectOutput(ProcessBuilder.Redirect.appendTo(request.standardOutput().toFile()));
		builder.redirectError(ProcessBuilder.Redirect.appendTo(request.standardError().toFile()));
		return builder;
	}

	private static Descriptor awaitDescriptor(Path claim, String launchId, Process process) throws IOException {
		long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(START_TIMEOUT_MS);
		IOException lastFailure = null;
		while (process.isAlive() && System.nanoTime() < deadline) {
			try {
				Descriptor descriptor = readDescriptor(claim);
				if (descriptor != null) {
					if (!descriptor.launchId().equals(launchId)) {
						throw new IOException("POSIX coordinator wrapper reported the wrong launch identity");
					}
					return descriptor;
				}
			} catch (IOException incomplete) {
				lastFailure = incomplete;
			}
			try {
				Thread.sleep(10L);
			} catch (InterruptedException interrupted) {
				Thread.currentThread().interrupt();
				throw new IOException("POSIX coordinator ownership setup was interrupted", interrupted);
			}
		}
		if (!process.isAlive()) {
			throw new IOException("POSIX coordinator wrapper exited before establishing ownership", lastFailure);
		}
		throw new IOException("Timed out waiting for POSIX coordinator ownership", lastFailure);
	}

	private static Descriptor readDescriptor(Path claim) throws IOException {
		if (!Files.isRegularFile(claim)) return null;
		Properties values = new Properties();
		try (Reader reader = Files.newBufferedReader(claim)) {
			values.load(reader);
		}
		if (!"1".equals(values.getProperty("version"))) return null;
		try {
			String launchId = UUID.fromString(values.getProperty("launchId", "")).toString();
			long sessionPid = Long.parseLong(values.getProperty("sessionPid", ""));
			long rootPid = Long.parseLong(values.getProperty("rootPid", ""));
			if (sessionPid <= 0L || rootPid <= 0L) return null;
			return new Descriptor(launchId, sessionPid, rootPid);
		} catch (RuntimeException incomplete) {
			return null;
		}
	}

	private static CoordinatorProcessOwnership.ProcessIdentity identity(ProcessHandle process, String label)
			throws IOException {
		return CoordinatorProcessOwnership.ProcessIdentity.from(process)
				.orElseThrow(() -> new IOException("POSIX coordinator " + label + " identity is unavailable"));
	}

	private static boolean hasExactArguments(
			ProcessHandle process,
			Path expectedWrapper,
			Path expectedClaim,
			String expectedLaunchId
	) {
		String[] arguments = process.info().arguments().orElse(null);
		if (arguments == null) return false;
		boolean wrapper = false;
		boolean claim = false;
		boolean launch = false;
		for (String argument : arguments) {
			wrapper |= samePathArgument(argument, expectedWrapper);
			claim |= samePathArgument(argument, expectedClaim);
			launch |= argument.equals(expectedLaunchId);
		}
		return wrapper && claim && launch;
	}

	private static void terminateStarted(Process process) {
		if (!process.isAlive()) return;
		process.destroy();
		try {
			if (!process.waitFor(STOP_TIMEOUT_MS, TimeUnit.MILLISECONDS) && process.isAlive()) {
				process.destroyForcibly();
				process.waitFor(STOP_TIMEOUT_MS, TimeUnit.MILLISECONDS);
			}
		} catch (InterruptedException interrupted) {
			Thread.currentThread().interrupt();
			if (process.isAlive()) process.destroyForcibly();
		}
	}

	private static void awaitExit(ProcessHandle process, long timeoutMs) throws IOException {
		long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMs);
		boolean interrupted = false;
		while (process.isAlive() && System.nanoTime() < deadline) {
			try {
				Thread.sleep(10L);
			} catch (InterruptedException interruption) {
				interrupted = true;
			}
		}
		if (interrupted) Thread.currentThread().interrupt();
		if (process.isAlive()) throw new IOException("POSIX coordinator session supervisor stayed alive after termination");
	}

	private static boolean samePathArgument(String argument, Path expected) {
		try {
			return Path.of(argument).toAbsolutePath().normalize().equals(expected.toAbsolutePath().normalize());
		} catch (RuntimeException invalid) {
			return false;
		}
	}

	private static Path normalizeRoot(Path root) {
		return Objects.requireNonNull(root, "runtime root must not be null").toAbsolutePath().normalize();
	}

	private static void requireSupported() throws IOException {
		if (!supported()) throw new IOException("POSIX coordinator ownership is unavailable on this platform");
	}

	enum Termination {
		ABSENT,
		TERMINATED
	}

	record Started(
			Process process,
			CoordinatorProcessOwnership.ProcessIdentity sessionIdentity,
			CoordinatorProcessOwnership.ProcessIdentity rootIdentity,
			Path claim
	) {
		Started {
			Objects.requireNonNull(process, "session supervisor process must not be null");
			Objects.requireNonNull(sessionIdentity, "session supervisor identity must not be null");
			Objects.requireNonNull(rootIdentity, "coordinator root identity must not be null");
			claim = Objects.requireNonNull(claim, "session claim must not be null").toAbsolutePath().normalize();
		}

		void release() throws IOException {
			process.getOutputStream().write(1);
			process.getOutputStream().flush();
		}

		void terminate() throws IOException {
			try {
				process.getOutputStream().close();
			} catch (IOException ignored) {
				// A wrapper which already observed EOF may have closed the pipe first.
			}
			try {
				if (!process.waitFor(STOP_TIMEOUT_MS, TimeUnit.MILLISECONDS) && process.isAlive()) {
					PosixCoordinatorSession.terminateExisting(
							claim.getParent().getParent(), launchIdFromClaim(claim), sessionIdentity
					);
				}
			} catch (InterruptedException interrupted) {
				Thread.currentThread().interrupt();
				throw new IOException("POSIX coordinator session termination was interrupted", interrupted);
			}
			if (process.isAlive()) throw new IOException("POSIX coordinator session supervisor stayed alive after termination");
		}
	}

	private static String launchIdFromClaim(Path claim) {
		String name = claim.getFileName().toString();
		return name.substring(CLAIM_PREFIX.length(), name.length() - ".properties".length());
	}

	private record Descriptor(String launchId, long sessionPid, long rootPid) {
	}
}
