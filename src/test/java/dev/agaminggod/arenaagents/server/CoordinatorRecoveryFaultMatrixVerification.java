package dev.agaminggod.arenaagents.server;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/** Runs the deterministic coordinator fault matrix and its bounded real-process smokes. */
public final class CoordinatorRecoveryFaultMatrixVerification {
	private static final long STARTUP_GRACE_MS = 3_000L;
	private static final long AUTHENTICATION_TIMEOUT_MS = 15_000L;
	private static final String GENERATION = "c".repeat(64);

	private CoordinatorRecoveryFaultMatrixVerification() {
	}

	public static void main(String[] arguments) throws Exception {
		System.out.println("PASS: " + verify() + " coordinator recovery fault-matrix assertions");
	}

	public static int verify() throws Exception {
		net.minecraft.SharedConstants.tryDetectVersion();
		net.minecraft.server.Bootstrap.bootStrap();
		int assertions = CoordinatorProcessSupervisorVerification.verifyFaultMatrix();
		assertions += verifyRealAuthenticationHangRecovers();
		return assertions;
	}

	private static int verifyRealAuthenticationHangRecovers() throws Exception {
		FakeClock clock = new FakeClock();
		RealProcessLauncher launcher = new RealProcessLauncher();
		AtomicInteger launchSequence = new AtomicInteger();
		CoordinatorProcessSupervisor supervisor = new CoordinatorProcessSupervisor(
				Path.of("build", "coordinator-real-auth-smoke"),
				Map.of(),
				clock,
				new ReadyDependencies(),
				launcher,
				() -> "00000000-0000-0000-0000-%012d".formatted(launchSequence.incrementAndGet())
		);
		try {
			clock.advance(STARTUP_GRACE_MS);
			supervisor.tick(false, null, 0L);
			assertEquals(1, launcher.processes.size(), "real authentication smoke launches one owned child");
			Process hung = launcher.processes.getFirst();
			assertTrue(hung.isAlive(), "real authentication smoke child remains alive while authentication hangs");

			clock.advance(AUTHENTICATION_TIMEOUT_MS);
			supervisor.tick(false, null, 0L);
			assertEquals("COORDINATOR_AUTHENTICATION_TIMEOUT", supervisor.failureCode(),
					"real authentication hang reports the exact boundary");
			assertTrue(hung.waitFor(5L, TimeUnit.SECONDS),
					"real hung child is gone before supervisor ownership advances");
			assertEquals(CoordinatorRecoveryState.BACKOFF, supervisor.snapshot().state(),
					"real authentication hang remains recoverable");

			clock.advance(1_000L);
			supervisor.tick(false, null, 0L);
			assertEquals(2, launcher.processes.size(), "real authentication hang starts one replacement after backoff");
			Process replacement = launcher.processes.getLast();
			assertTrue(replacement.isAlive(), "real authentication replacement is alive");
			String launchId = supervisor.snapshot().launchId();
			supervisor.tick(true, launchId, 1L);
			assertEquals(CoordinatorRecoveryState.HEALTHY, supervisor.snapshot().state(),
					"real authentication replacement returns automatically to healthy");
			assertEquals(GENERATION, supervisor.runtimeGenerationId(),
					"real authentication recovery retains the exact runtime generation");
			assertEquals(1L, launcher.processes.stream().filter(Process::isAlive).count(),
					"real authentication recovery owns exactly one live child generation");
			return 12;
		} finally {
			supervisor.close();
			for (Process process : launcher.processes) {
				if (process.isAlive()) CoordinatorProcessOwnership.terminateTree(process.toHandle());
				assertTrue(process.waitFor(5L, TimeUnit.SECONDS),
						"real authentication smoke releases child process " + process.pid());
			}
			assertTrue(launcher.processes.stream().noneMatch(Process::isAlive),
					"real authentication smoke releases every child process");
		}
	}

	private static final class FakeClock implements java.util.function.LongSupplier {
		private long now = 100_000L;

		@Override
		public long getAsLong() {
			return now;
		}

		private void advance(long millis) {
			now += millis;
		}
	}

	private static final class ReadyDependencies implements CoordinatorProcessSupervisor.DependencyResolver {
		private final CoordinatorProcessSupervisor.PreparedRuntime runtime = new CoordinatorProcessSupervisor.PreparedRuntime(
				Path.of("build", "coordinator-real-auth-runtime"),
				Path.of("build", "coordinator-real-auth-runtime", "coordinator"),
				Path.of("build", "coordinator-real-auth-runtime", "coordinator", "src", "dynamic-main.mjs"),
				Path.of("build", "coordinator-real-auth-runtime", "runtime", "dynamic-agents.json"),
				Path.of("build", "coordinator-real-auth-runtime", "runtime", "bridge-secret.txt"),
				Path.of(System.getProperty("java.home"), "bin", isWindows() ? "java.exe" : "java"),
				"s".repeat(32),
				GENERATION,
				false,
				false
		);

		@Override
		public String fingerprint() {
			return "real-auth-ready";
		}

		@Override
		public CoordinatorProcessSupervisor.DependencyResolution resolve() {
			return CoordinatorProcessSupervisor.DependencyResolution.ready(runtime);
		}
	}

	private static final class RealProcessLauncher implements CoordinatorProcessSupervisor.ProcessLauncher {
		private final List<Process> processes = new ArrayList<>();

		@Override
		public CoordinatorProcessSupervisor.ChildProcess launch(CoordinatorProcessSupervisor.LaunchRequest request)
				throws IOException {
			String java = Path.of(System.getProperty("java.home"), "bin", isWindows() ? "java.exe" : "java").toString();
			Process process = new ProcessBuilder(
					java,
					"-cp",
					System.getProperty("java.class.path"),
					Sleeper.class.getName(),
					request.launchId()
			).start();
			processes.add(process);
			return new CoordinatorProcessSupervisor.ChildProcess() {
				@Override
				public boolean isAlive() {
					return process.isAlive();
				}

				@Override
				public long pid() {
					return process.pid();
				}

				@Override
				public void terminate() {
					CoordinatorProcessOwnership.terminateTree(process.toHandle());
				}
			};
		}
	}

	public static final class Sleeper {
		private Sleeper() {
		}

		public static void main(String[] arguments) throws Exception {
			UUID.fromString(arguments[0]);
			Thread.sleep(TimeUnit.MINUTES.toMillis(5L));
		}
	}

	private static boolean isWindows() {
		return System.getProperty("os.name", "").toLowerCase(java.util.Locale.ROOT).contains("win");
	}

	private static void assertEquals(Object expected, Object actual, String label) {
		if (!java.util.Objects.equals(expected, actual)) {
			throw new AssertionError(label + ": expected <" + expected + "> but was <" + actual + ">");
		}
	}

	private static void assertTrue(boolean condition, String label) {
		if (!condition) throw new AssertionError(label);
	}
}
