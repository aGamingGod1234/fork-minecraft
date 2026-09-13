package dev.agaminggod.arenaagents.server;

import com.google.gson.JsonParser;
import com.google.gson.JsonObject;
import net.fabricmc.loader.api.FabricLoader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.LongSupplier;
import java.util.function.Supplier;

/** Owns coordinator availability until explicit Minecraft shutdown. */
final class CoordinatorProcessSupervisor implements AutoCloseable {
	private static final Logger LOGGER = LoggerFactory.getLogger(CoordinatorProcessSupervisor.class);
	private static final String FISH_API_KEY_FILE = "runtime/fish-api-key.txt";
	private static final long DEPENDENCY_RECHECK_MS = 5_000L;
	private static final long DEPENDENCY_MONITOR_INTERVAL_MS = 1_000L;
	private static final long AUTHENTICATION_TIMEOUT_MS = 15_000L;
	private static final long RECONNECT_TIMEOUT_MS = 10_000L;
	private static final long STABILITY_INTERVAL_MS = 30_000L;
	private static final long TERMINATION_RETRY_MS = 1_000L;
	private static final long SHUTDOWN_WAIT_MS = 10_000L;
	private static final int SHUTDOWN_TERMINATION_ATTEMPTS = 3;
	private static final long DESCENDANT_TRACK_INTERVAL_MS = 100L;
	private static final int MAX_BRIDGE_SECRET_LENGTH = 512;
	private static final int CANDIDATE_FAILURES_BEFORE_ROLLBACK = 3;
	private static final String VOICE_REQUEST_TIMEOUT_PROPERTY = "arenaagents.voiceRequestTimeoutMs";

	private final Path gameDirectory;
	private final Map<String, String> launchEnvironmentOverrides;
	private final LongSupplier clock;
	private final DependencyResolver dependencyResolver;
	private final ProcessLauncher processLauncher;
	private final Supplier<String> launchIds;
	private final MaintenanceWorker maintenanceWorker;
	private final OrphanReaper orphanReaper;
	private final DependencyChangeMonitor dependencyChangeMonitor;
	private final GenerationController generationController;
	private final CoordinatorProcessOwnership.SupervisorIdentity supervisorIdentity =
			CoordinatorProcessOwnership.SupervisorIdentity.create();
	private final long createdAtEpochMs;
	private final CoordinatorLaunchPolicy.RestartBudget restartBudget = new CoordinatorLaunchPolicy.RestartBudget();
	private final ConcurrentLinkedQueue<MaintenanceResult> maintenanceResults = new ConcurrentLinkedQueue<>();
	private final AtomicLong dependencyWakeGeneration = new AtomicLong();
	private final AtomicBoolean dependencyWakeQueued = new AtomicBoolean();
	private final Set<Path> reapedOrphanRoots = new LinkedHashSet<>();

	private PreparedRuntime runtime;
	private ChildProcess child;
	private CoordinatorRecoveryState state;
	private volatile boolean stopped;
	private boolean maintenancePending;
	private boolean orphanCleanupComplete;
	private boolean coordinatorReconciled;
	private boolean stabilityCredited;
	private long generation;
	private long nextRetryEpochMs;
	private long nextDependencyCheckEpochMs;
	private long lastStableEpochMs;
	private long processStartedEpochMs;
	private long authenticationDeadlineEpochMs;
	private long reconnectDeadlineEpochMs;
	private long authenticatedSinceEpochMs;
	private long authenticatedSessionGeneration;
	private String launchId;
	private String dependencyFingerprint;
	private String failureCode;
	private String failureMessage;
	private String failingBoundary;
	private ChildProcess pendingTermination;
	private long bridgeRevision;
	private long sharedSecretRevision;
	private int candidateFailures;
	private boolean candidateAttemptQualified;
	private boolean rollbackRequested;
	private long nextGenerationMutationEpochMs;
	private long nextTerminationRetryEpochMs;
	private boolean voiceEndpointExplicitOverride;
	private String managedVoiceEndpoint;
	private long voiceConfigurationRevision;
	private boolean initialBridgeListenerObserved;
	private String previousVoiceRequestTimeout;
	private String derivedVoiceRequestTimeout;
	private boolean ownsVoiceRequestTimeout;

	CoordinatorProcessSupervisor() {
		this(FabricLoader.getInstance().getGameDir());
	}

	CoordinatorProcessSupervisor(Path gameDirectory) {
		this(gameDirectory, Map.of());
	}

	/** Allows isolated startup fixtures to control inherited environment state without invoking a shell. */
	CoordinatorProcessSupervisor(Path gameDirectory, Map<String, String> launchEnvironmentOverrides) {
		this(
				gameDirectory,
				launchEnvironmentOverrides,
				System::currentTimeMillis,
				null,
				null,
				() -> UUID.randomUUID().toString(),
				new OwnedMaintenanceWorker(),
				null,
				new OwnedDependencyMonitorScheduler()
		);
	}

	CoordinatorProcessSupervisor(
			Path gameDirectory,
			Map<String, String> launchEnvironmentOverrides,
			LongSupplier clock,
			DependencyResolver dependencyResolver,
			ProcessLauncher processLauncher,
			Supplier<String> launchIds
	) {
		this(
				gameDirectory,
				launchEnvironmentOverrides,
				clock,
				dependencyResolver,
				processLauncher,
				launchIds,
				Runnable::run,
				null,
				task -> { }
		);
	}

	CoordinatorProcessSupervisor(
			Path gameDirectory,
			Map<String, String> launchEnvironmentOverrides,
			LongSupplier clock,
			DependencyResolver dependencyResolver,
			ProcessLauncher processLauncher,
			Supplier<String> launchIds,
			MaintenanceWorker maintenanceWorker,
			OrphanReaper orphanReaper
	) {
		this(
				gameDirectory, launchEnvironmentOverrides, clock, dependencyResolver, processLauncher, launchIds,
				maintenanceWorker, orphanReaper, task -> { }
		);
	}

	CoordinatorProcessSupervisor(
			Path gameDirectory,
			Map<String, String> launchEnvironmentOverrides,
			LongSupplier clock,
			DependencyResolver dependencyResolver,
			ProcessLauncher processLauncher,
			Supplier<String> launchIds,
			MaintenanceWorker maintenanceWorker,
			OrphanReaper orphanReaper,
			DependencyMonitorScheduler dependencyMonitorScheduler
	) {
		this(
				gameDirectory, launchEnvironmentOverrides, clock, dependencyResolver, processLauncher, launchIds,
				maintenanceWorker, orphanReaper, dependencyMonitorScheduler, new DefaultGenerationController()
		);
	}

	CoordinatorProcessSupervisor(
			Path gameDirectory,
			Map<String, String> launchEnvironmentOverrides,
			LongSupplier clock,
			DependencyResolver dependencyResolver,
			ProcessLauncher processLauncher,
			Supplier<String> launchIds,
			MaintenanceWorker maintenanceWorker,
			OrphanReaper orphanReaper,
			DependencyMonitorScheduler dependencyMonitorScheduler,
			GenerationController generationController
	) {
		this.gameDirectory = Objects.requireNonNull(gameDirectory, "game directory must not be null")
				.toAbsolutePath().normalize();
		this.launchEnvironmentOverrides = Map.copyOf(Objects.requireNonNull(
				launchEnvironmentOverrides,
				"launch environment overrides must not be null"
		));
		this.clock = Objects.requireNonNull(clock, "clock must not be null");
		this.dependencyResolver = dependencyResolver == null
				? new DefaultDependencyResolver(this.gameDirectory, this.launchEnvironmentOverrides)
				: dependencyResolver;
		this.processLauncher = processLauncher == null ? new DefaultProcessLauncher() : processLauncher;
		this.launchIds = Objects.requireNonNull(launchIds, "launch IDs must not be null");
		this.maintenanceWorker = Objects.requireNonNull(maintenanceWorker, "maintenance worker must not be null");
		this.orphanReaper = orphanReaper == null
				? root -> CoordinatorProcessOwnership.reapOrphaned(root, supervisorIdentity)
				: orphanReaper;
		this.generationController = Objects.requireNonNull(generationController,
				"generation controller must not be null");
		this.voiceEndpointExplicitOverride = configuredProperty("arenaagents.voiceUrl") != null;
		DependencyMonitorScheduler monitorScheduler = Objects.requireNonNull(
				dependencyMonitorScheduler, "dependency monitor scheduler must not be null"
		);
		this.createdAtEpochMs = now();
		if (!autoStartEnabled()) {
			stopped = true;
			state = CoordinatorRecoveryState.STOPPED;
			this.maintenanceWorker.close();
			monitorScheduler.close();
			this.dependencyChangeMonitor = null;
			return;
		}
		state = CoordinatorRecoveryState.STARTING;
		nextRetryEpochMs = createdAtEpochMs + CoordinatorLaunchPolicy.STARTUP_GRACE_MS;
		this.dependencyChangeMonitor = new DependencyChangeMonitor(
				this::safeMonitorFingerprint, this::publishDependencyFingerprintChange, monitorScheduler
		);
		submitDependencyMaintenance(createdAtEpochMs, true, true);
		drainMaintenanceResults(createdAtEpochMs);
	}

	@FunctionalInterface
	interface ProcessLauncher {
		ChildProcess launch(LaunchRequest request) throws IOException;
	}

	@FunctionalInterface
	interface ProcessStarter {
		Process start(ProcessBuilder builder) throws IOException;
	}

	interface ChildProcess {
		boolean isAlive();

		long pid();

		void terminate();
	}

	interface DependencyResolver {
		String fingerprint();

		default String monitorFingerprint() {
			return fingerprint();
		}

		default String monitorFingerprintFor(String resolvedFingerprint) {
			return resolvedFingerprint;
		}

		DependencyResolution resolve();

		default DependencyResolution resolve(String fingerprint) {
			return resolve();
		}
	}

	interface GenerationController {
		GenerationStatus promote(Path root, String generationId) throws IOException;

		GenerationStatus rollback(Path root, String generationId) throws IOException;
	}

	record GenerationStatus(String generationId, boolean candidate, boolean lastKnownGoodAvailable) {
		GenerationStatus {
			generationId = Objects.requireNonNull(generationId, "generation ID must not be null");
			if (!generationId.matches("[0-9a-f]{64}")) {
				throw new IllegalArgumentException("coordinator generation ID is invalid");
			}
		}
	}

	@FunctionalInterface
	interface MaintenanceWorker extends AutoCloseable {
		void execute(Runnable task);

		@Override
		default void close() {
		}
	}

	@FunctionalInterface
	interface DependencyMonitorScheduler extends AutoCloseable {
		void start(Runnable task);

		@Override
		default void close() {
		}
	}

	@FunctionalInterface
	interface OrphanReaper {
		int reap(Path runtimeRoot) throws IOException;
	}

	private sealed interface MaintenanceResult permits DependencyMaintenanceResult,
			DependencyFingerprintChanged, LaunchMaintenanceResult, TerminationMaintenanceResult,
			PromotionMaintenanceResult, RollbackMaintenanceResult {
	}

	private record DependencyMaintenanceResult(
			String fingerprint,
			DependencyResolution resolution,
			boolean fingerprintChanged,
			boolean initial,
			boolean orphanCleanupSucceeded,
			List<Path> orphanRootsReaped,
			long submittedWakeGeneration
	) implements MaintenanceResult {
	}

	private record DependencyFingerprintChanged(long generation) implements MaintenanceResult {
	}

	private record LaunchMaintenanceResult(
			ChildProcess child,
			String launchId,
			long startedAtEpochMs,
			String failureMessage
	) implements MaintenanceResult {
	}

	private record TerminationMaintenanceResult(ChildProcess child, String failureMessage) implements MaintenanceResult {
	}

	private record PromotionMaintenanceResult(
			GenerationStatus status,
			String generationId,
			String fingerprint,
			String failureMessage
	) implements MaintenanceResult {
	}

	private record RollbackMaintenanceResult(
			GenerationStatus status,
			String generationId,
			String fingerprint,
			String failureMessage
	) implements MaintenanceResult {
	}

	record DependencyResolution(
			PreparedRuntime runtime,
			String failureCode,
			String failureMessage
	) {
		DependencyResolution {
			if (runtime == null && (failureCode == null || failureCode.isBlank())) {
				throw new IllegalArgumentException("unavailable coordinator dependencies require a failure code");
			}
		}

		static DependencyResolution ready(PreparedRuntime runtime) {
			PreparedRuntime prepared = Objects.requireNonNull(runtime, "runtime must not be null");
			if (prepared.nodeExecutable() == null) throw new IllegalArgumentException("ready runtime requires Node");
			return new DependencyResolution(prepared, null, null);
		}

		static DependencyResolution blocked(String code, String message) {
			return blocked(null, code, message);
		}

		static DependencyResolution blocked(PreparedRuntime runtime, String code, String message) {
			return new DependencyResolution(
					runtime, Objects.requireNonNull(code, "failure code must not be null"), message
			);
		}

		boolean ready() {
			return runtime != null && runtime.nodeExecutable() != null && failureCode == null;
		}
	}

	record PreparedRuntime(
			Path root,
			Path coordinatorRoot,
			Path main,
			Path config,
			Path secret,
			Path voiceSecretPath,
			Path nodeExecutable,
			String bridgeSecret,
			String voiceSecret,
			int bridgePort,
			String generationId,
			boolean candidate,
			boolean lastKnownGoodAvailable
	) {
		PreparedRuntime(
				Path root,
				Path coordinatorRoot,
				Path main,
				Path config,
				Path secret,
				Path nodeExecutable,
				String bridgeSecret
		) {
			this(
					root, coordinatorRoot, main, config, secret, defaultVoiceSecretPath(secret), nodeExecutable,
					bridgeSecret, derivedVoiceSecret(bridgeSecret), 25_570,
					"0".repeat(64), false, false
			);
		}

		PreparedRuntime(
				Path root,
				Path coordinatorRoot,
				Path main,
				Path config,
				Path secret,
				Path nodeExecutable,
				String bridgeSecret,
				String generationId,
				boolean candidate,
				boolean lastKnownGoodAvailable
		) {
			this(root, coordinatorRoot, main, config, secret, defaultVoiceSecretPath(secret), nodeExecutable,
					bridgeSecret, derivedVoiceSecret(bridgeSecret), 25_570,
					generationId, candidate, lastKnownGoodAvailable);
		}

		PreparedRuntime(
				Path root,
				Path coordinatorRoot,
				Path main,
				Path config,
				Path secret,
				Path nodeExecutable,
				String bridgeSecret,
				int bridgePort,
				String generationId,
				boolean candidate,
				boolean lastKnownGoodAvailable
		) {
			this(root, coordinatorRoot, main, config, secret, defaultVoiceSecretPath(secret), nodeExecutable,
					bridgeSecret, derivedVoiceSecret(bridgeSecret), bridgePort,
					generationId, candidate, lastKnownGoodAvailable);
		}

		PreparedRuntime {
			root = normalized(root, "runtime root");
			coordinatorRoot = normalized(coordinatorRoot, "coordinator root");
			main = normalized(main, "coordinator main");
			config = normalized(config, "coordinator config");
			secret = normalized(secret, "bridge secret");
			voiceSecretPath = normalized(voiceSecretPath, "voice secret");
			nodeExecutable = nodeExecutable == null ? null : normalized(nodeExecutable, "Node executable");
			bridgeSecret = Objects.requireNonNull(bridgeSecret, "bridge secret value must not be null").strip();
			voiceSecret = Objects.requireNonNull(voiceSecret, "voice secret value must not be null").strip();
			if (bridgeSecret.length() < 32 || bridgeSecret.length() > MAX_BRIDGE_SECRET_LENGTH) {
				throw new IllegalArgumentException("bridge secret value is invalid");
			}
			if (voiceSecret.length() < 32 || voiceSecret.length() > MAX_BRIDGE_SECRET_LENGTH) {
				throw new IllegalArgumentException("voice secret value is invalid");
			}
			if (MessageDigest.isEqual(
					bridgeSecret.getBytes(StandardCharsets.UTF_8), voiceSecret.getBytes(StandardCharsets.UTF_8)
			)) {
				throw new IllegalArgumentException("bridge and voice secrets must be distinct");
			}
			if (bridgePort < 1_024 || bridgePort > 65_535) {
				throw new IllegalArgumentException("bridge port must be between 1024 and 65535");
			}
			generationId = Objects.requireNonNull(generationId, "generation ID must not be null");
			if (!generationId.matches("[0-9a-f]{64}")) {
				throw new IllegalArgumentException("coordinator generation ID is invalid");
			}
		}

		private static Path defaultVoiceSecretPath(Path bridgeSecretPath) {
			return Objects.requireNonNull(bridgeSecretPath, "bridge secret must not be null")
					.resolveSibling("voice-secret.txt");
		}

		private static String derivedVoiceSecret(String bridgeSecret) {
			try {
				MessageDigest digest = MessageDigest.getInstance("SHA-256");
				digest.update("arena-agents-voice-secret-v1\0".getBytes(StandardCharsets.UTF_8));
				return HexFormat.of().formatHex(digest.digest(
						Objects.requireNonNull(bridgeSecret, "bridge secret must not be null")
								.getBytes(StandardCharsets.UTF_8)
				));
			} catch (NoSuchAlgorithmException exception) {
				throw new IllegalStateException("SHA-256 is unavailable", exception);
			}
		}

		private static Path normalized(Path path, String label) {
			return Objects.requireNonNull(path, label + " must not be null").toAbsolutePath().normalize();
		}
	}

	record LaunchRequest(
			List<String> command,
			Path workingDirectory,
			Map<String, String> environment,
			Path standardOutput,
			Path standardError,
			Path runtimeRoot,
			Path main,
			String generationId,
			String launchId,
			CoordinatorProcessOwnership.SupervisorIdentity supervisorIdentity
	) {
		LaunchRequest {
			command = List.copyOf(command);
			workingDirectory = normalized(workingDirectory, "working directory");
			environment = Map.copyOf(environment);
			standardOutput = normalized(standardOutput, "standard output");
			standardError = normalized(standardError, "standard error");
			runtimeRoot = normalized(runtimeRoot, "runtime root");
			main = normalized(main, "coordinator main");
			generationId = Objects.requireNonNull(generationId, "generation ID must not be null");
			launchId = UUID.fromString(Objects.requireNonNull(launchId, "launch ID must not be null")).toString();
			supervisorIdentity = Objects.requireNonNull(supervisorIdentity, "supervisor identity must not be null");
		}

		private static Path normalized(Path path, String label) {
			return Objects.requireNonNull(path, label + " must not be null").toAbsolutePath().normalize();
		}
	}

	synchronized boolean configured() {
		return runtime != null && runtime.nodeExecutable() != null;
	}

	synchronized Path secretPath() {
		return runtime == null ? null : runtime.secret();
	}

	synchronized Path voiceSecretPath() {
		return runtime == null ? null : runtime.voiceSecretPath();
	}

	synchronized String bridgeSecret() {
		return runtime == null ? null : runtime.bridgeSecret();
	}

	synchronized int bridgePort() {
		return runtime == null ? 25_570 : runtime.bridgePort();
	}

	synchronized String failureCode() {
		return failureCode;
	}

	synchronized String failureMessage() {
		return failureMessage;
	}

	synchronized long bridgeRevision() {
		return bridgeRevision;
	}

	synchronized long sharedSecretRevision() {
		return sharedSecretRevision;
	}

	synchronized long voiceConfigurationRevision() {
		return voiceConfigurationRevision;
	}

	synchronized String runtimeGenerationId() {
		return runtime == null ? null : runtime.generationId();
	}

	void publishDependencyFingerprintChange() {
		if (stopped) return;
		long generation = dependencyWakeGeneration.incrementAndGet();
		if (dependencyWakeQueued.compareAndSet(false, true)) {
			maintenanceResults.add(new DependencyFingerprintChanged(generation));
		}
	}

	synchronized void tick(boolean bridgeAuthenticated) {
		tick(bridgeAuthenticated, bridgeAuthenticated ? launchId : null, bridgeAuthenticated ? 1L : 0L);
	}

	synchronized void tick(boolean bridgeAuthenticated, String authenticatedLaunchId) {
		tick(bridgeAuthenticated, authenticatedLaunchId, bridgeAuthenticated ? 1L : 0L);
	}

	synchronized void tick(boolean bridgeAuthenticated, String authenticatedLaunchId, long sessionGeneration) {
		// The legacy verification overload predates the coordinator readiness gate. Keep
		// it source-compatible for isolated supervisor tests; production uses the
		// explicit readiness-aware overload below.
		tick(bridgeAuthenticated, authenticatedLaunchId, sessionGeneration, true);
	}

	/**
	 * Advances supervision with the authenticated bridge's reconciliation boundary.
	 * Authentication proves transport ownership only; candidate generations are not
	 * promoted until the coordinator has also reconciled its catalog and roster.
	 */
	synchronized void tick(
			boolean bridgeAuthenticated,
			String authenticatedLaunchId,
			long sessionGeneration,
			boolean coordinatorReady
	) {
		tickInternal(bridgeAuthenticated, authenticatedLaunchId, sessionGeneration, coordinatorReady, true, false);
	}

	/**
	 * Production startup also reports whether the prepared bridge is listening.
	 * Owned launch begins as soon as that endpoint exists. Operators that own an
	 * external coordinator disable auto-start explicitly.
	 */
	synchronized void tickWithBridgeListener(
			boolean bridgeAuthenticated,
			String authenticatedLaunchId,
			long sessionGeneration,
			boolean coordinatorReady,
			boolean bridgeListenerAvailable
	) {
		tickInternal(
				bridgeAuthenticated,
				authenticatedLaunchId,
				sessionGeneration,
				coordinatorReady,
				bridgeListenerAvailable,
				true
		);
	}

	private void tickInternal(
			boolean bridgeAuthenticated,
			String authenticatedLaunchId,
			long sessionGeneration,
			boolean coordinatorReady,
			boolean bridgeListenerAvailable,
			boolean requireInitialBridgeListener
	) {
		if (stopped) return;
		long now = now();
		drainMaintenanceResults(now);

		if (child != null && !child.isAlive()) {
			queueTermination(detachChild());
			recordFailure(now, "COORDINATOR_EXITED", "Coordinator process exited unexpectedly", "process");
		}

		if (child != null) {
			observeOwnedChild(now, bridgeAuthenticated, authenticatedLaunchId, sessionGeneration, coordinatorReady);
			if (child != null) submitDependencyMaintenance(now, false, false);
		}
		if (pendingTermination != null) {
			if (now >= nextTerminationRetryEpochMs) submitTerminationMaintenance();
			drainMaintenanceResults(now);
			if (pendingTermination != null || maintenancePending) return;
		}
		if (rollbackRequested && child == null) {
			if (now < nextGenerationMutationEpochMs) return;
			submitRollbackMaintenance();
			drainMaintenanceResults(now);
			if (rollbackRequested || maintenancePending) return;
		}
		if (child != null) {
			return;
		}
		if (maintenancePending) return;

		if (runtime == null || runtime.nodeExecutable() == null) {
			if (now < nextDependencyCheckEpochMs) return;
			submitDependencyMaintenance(now, false, false);
			drainMaintenanceResults(now);
			if (maintenancePending || runtime == null || runtime.nodeExecutable() == null) return;
			if (pendingTermination != null) {
				submitTerminationMaintenance();
				drainMaintenanceResults(now);
				if (maintenancePending || pendingTermination != null) return;
			}
		}
		if (now >= nextDependencyCheckEpochMs) {
			submitDependencyMaintenance(now, false, false);
			drainMaintenanceResults(now);
			if (maintenancePending || runtime == null || runtime.nodeExecutable() == null) return;
			if (pendingTermination != null) {
				submitTerminationMaintenance();
				drainMaintenanceResults(now);
				if (maintenancePending || pendingTermination != null) return;
			}
		}
		if (requireInitialBridgeListener && !initialBridgeListenerObserved) {
			if (!bridgeListenerAvailable && !bridgeAuthenticated) return;
			initialBridgeListenerObserved = true;
			nextRetryEpochMs = Math.max(nextRetryEpochMs, now);
		}

		if (now >= nextRetryEpochMs) {
			submitLaunchMaintenance(now);
			drainMaintenanceResults(now);
			return;
		}
		submitDependencyMaintenance(now, false, false);
	}

	synchronized CoordinatorRecoverySnapshot snapshot() {
		return new CoordinatorRecoverySnapshot(
				state,
				generation,
				restartBudget.restartCount(),
				lastStableEpochMs,
				nextRetryEpochMs,
				failureCode,
				failureMessage,
				failingBoundary,
				child == null ? null : launchId,
				child == null ? -1L : child.pid(),
				child == null ? 0L : processStartedEpochMs,
				child == null ? 0L : authenticationDeadlineEpochMs,
				reconnectDeadlineEpochMs
		);
	}

	private void observeOwnedChild(
			long now,
			boolean bridgeAuthenticated,
			String authenticatedLaunchId,
			long sessionGeneration,
			boolean coordinatorReady
	) {
		boolean matchingAuthentication = bridgeAuthenticated
				&& launchId != null
				&& launchId.equals(authenticatedLaunchId)
				&& sessionGeneration > 0L;
		if (matchingAuthentication) {
			if (state != CoordinatorRecoveryState.HEALTHY
					|| authenticatedSessionGeneration != sessionGeneration) {
				state = CoordinatorRecoveryState.HEALTHY;
				authenticatedSinceEpochMs = now;
				stabilityCredited = false;
			}
			authenticatedSessionGeneration = sessionGeneration;
			if (!coordinatorReady) {
				authenticatedSinceEpochMs = 0L;
				stabilityCredited = false;
			} else if (!coordinatorReconciled) {
				authenticatedSinceEpochMs = now;
				stabilityCredited = false;
			}
			coordinatorReconciled = coordinatorReady;
			if (runtime != null && runtime.candidate() && coordinatorReady) {
				candidateAttemptQualified = true;
			}
			failingBoundary = null;
			clearDiagnostic();
			authenticationDeadlineEpochMs = 0L;
			reconnectDeadlineEpochMs = 0L;
			nextRetryEpochMs = 0L;
			if (!stabilityCredited && coordinatorReconciled
					&& now - authenticatedSinceEpochMs >= STABILITY_INTERVAL_MS) {
				if (runtime != null && runtime.candidate()) {
					if (now >= nextGenerationMutationEpochMs) submitPromotionMaintenance();
				} else {
					creditStability(now);
				}
			}
			return;
		}

		if (state == CoordinatorRecoveryState.HEALTHY || state == CoordinatorRecoveryState.DEGRADED) {
			if (state == CoordinatorRecoveryState.HEALTHY) {
				state = CoordinatorRecoveryState.DEGRADED;
				coordinatorReconciled = false;
				reconnectDeadlineEpochMs = now + RECONNECT_TIMEOUT_MS;
				authenticatedSinceEpochMs = 0L;
				stabilityCredited = false;
				setDiagnostic(
						"COORDINATOR_BRIDGE_DISCONNECTED",
						"Authenticated coordinator bridge disconnected; waiting for reconnection",
						"bridge_reconnect"
				);
			}
			if (now >= reconnectDeadlineEpochMs) {
				queueTermination(detachChild());
				recordFailure(now, "COORDINATOR_RECONNECT_TIMEOUT",
						"Coordinator stayed alive but did not restore its authenticated bridge", "bridge_reconnect");
			}
			return;
		}

		state = CoordinatorRecoveryState.AUTHENTICATING;
		coordinatorReconciled = false;
		if (now >= authenticationDeadlineEpochMs) {
			queueTermination(detachChild());
			recordFailure(now, "COORDINATOR_AUTHENTICATION_TIMEOUT",
					"Coordinator process did not authenticate before its deadline", "bridge_authentication");
		}
	}

	private void creditStability(long now) {
		restartBudget.resetAfterStability();
		lastStableEpochMs = now;
		stabilityCredited = true;
		candidateFailures = 0;
	}

	private boolean applyDependencyResolution(
			DependencyResolution resolution,
			long now,
			boolean initial,
			boolean fingerprintChanged
	) {
		nextDependencyCheckEpochMs = now + DEPENDENCY_RECHECK_MS;
		PreparedRuntime previous = runtime;
		runtime = resolution.runtime();
		boolean launchMaterialChanged = previous != null && runtime != null && (
				!Objects.equals(previous.root(), runtime.root())
						|| !Objects.equals(previous.coordinatorRoot(), runtime.coordinatorRoot())
						|| !Objects.equals(previous.main(), runtime.main())
						|| !Objects.equals(previous.config(), runtime.config())
						|| !Objects.equals(previous.secret(), runtime.secret())
						|| !Objects.equals(previous.voiceSecretPath(), runtime.voiceSecretPath())
						|| !Objects.equals(previous.nodeExecutable(), runtime.nodeExecutable())
						|| !Objects.equals(previous.bridgeSecret(), runtime.bridgeSecret())
						|| !Objects.equals(previous.voiceSecret(), runtime.voiceSecret())
						|| previous.bridgePort() != runtime.bridgePort()
						|| !Objects.equals(previous.generationId(), runtime.generationId())
		);
		if (runtime == null || previous == null || !previous.generationId().equals(runtime.generationId())) {
			candidateFailures = 0;
			candidateAttemptQualified = false;
			rollbackRequested = false;
		}
		if (runtime != null && !runtime.candidate()) {
			candidateFailures = 0;
			candidateAttemptQualified = false;
			rollbackRequested = false;
		}
		if (runtime != null) configureSharedSecretPaths(runtime.secret(), runtime.voiceSecretPath());
		if (runtime != null) configureSharedVoiceEndpoint(runtime.config());
		boolean sharedSecretChanged = runtime != null && (previous == null
				|| !previous.secret().equals(runtime.secret())
				|| !previous.voiceSecretPath().equals(runtime.voiceSecretPath())
				|| !previous.bridgeSecret().equals(runtime.bridgeSecret())
				|| !previous.voiceSecret().equals(runtime.voiceSecret()));
		if (sharedSecretChanged) sharedSecretRevision++;
		if (runtime != null && (sharedSecretChanged
				|| previous.bridgePort() != runtime.bridgePort())) {
			bridgeRevision++;
		}
		if (!resolution.ready()) {
			queueTermination(detachChild());
			state = CoordinatorRecoveryState.BLOCKED_RETRYABLE;
			nextRetryEpochMs = nextDependencyCheckEpochMs;
			authenticationDeadlineEpochMs = 0L;
			reconnectDeadlineEpochMs = 0L;
			authenticatedSinceEpochMs = 0L;
			stabilityCredited = false;
			setDiagnostic(resolution.failureCode(), resolution.failureMessage(), "startup_dependencies");
			return false;
		}

		if ((fingerprintChanged || launchMaterialChanged) && previous != null && child != null) {
			queueTermination(detachChild());
			state = CoordinatorRecoveryState.STARTING;
			nextRetryEpochMs = now;
		} else if (state == CoordinatorRecoveryState.BLOCKED_RETRYABLE) {
			state = CoordinatorRecoveryState.STARTING;
			nextRetryEpochMs = now;
		} else if (initial) {
			state = CoordinatorRecoveryState.STARTING;
			nextRetryEpochMs = createdAtEpochMs + CoordinatorLaunchPolicy.STARTUP_GRACE_MS;
		}
		return true;
	}

	private void submitDependencyMaintenance(long requestedAt, boolean force, boolean initial) {
		if (maintenancePending || stopped) return;
		// Honor the deadline before touching dependency files. A no-op completion keeps
		// the maintenance queue's ordering deterministic without calling safeFingerprint.
		if (!force && orphanCleanupComplete && now() < nextDependencyCheckEpochMs) {
			maintenancePending = true;
			String observedFingerprint = dependencyFingerprint;
			long submittedWakeGeneration = dependencyWakeGeneration.get();
			submitMaintenance(() -> publishMaintenanceResult(new DependencyMaintenanceResult(
					observedFingerprint, null, false, false, false, List.of(), submittedWakeGeneration
			)));
			return;
		}
		List<Path> cleanupRoots = orphanRuntimeRoots();
		Set<Path> rootsAlreadyReaped = Set.copyOf(reapedOrphanRoots);
		boolean reapRequired = !orphanCleanupComplete
				|| cleanupRoots.stream().anyMatch(root -> !rootsAlreadyReaped.contains(root));
		maintenancePending = true;
		String previousFingerprint = dependencyFingerprint;
		long scheduledCheck = nextDependencyCheckEpochMs;
		long submittedWakeGeneration = dependencyWakeGeneration.get();
		submitMaintenance(() -> {
			String currentFingerprint = safeFingerprint();
			dependencyChangeMonitor.observeSubmittedFingerprint(safeMonitorFingerprint(currentFingerprint));
			boolean changed = !Objects.equals(previousFingerprint, currentFingerprint);
			long checkedAt = now();
			if (!force && !reapRequired && !changed && checkedAt < scheduledCheck) {
				publishMaintenanceResult(new DependencyMaintenanceResult(
						currentFingerprint, null, false, initial, false, List.of(), submittedWakeGeneration
				));
				return;
			}
			boolean reaped = !reapRequired;
			if (reapRequired) {
				try {
					int count = 0;
					for (Path root : cleanupRoots) {
						if (rootsAlreadyReaped.contains(root)) continue;
						int rootCount = orphanReaper.reap(root);
						count += rootCount;
					}
					if (count > 0) {
						LOGGER.warn("Stopped {} orphaned Arena Agents coordinator process(es) before updating the runtime", count);
					}
					reaped = true;
				} catch (IOException | RuntimeException failure) {
					publishMaintenanceResult(new DependencyMaintenanceResult(
							currentFingerprint,
							DependencyResolution.blocked(
									"COORDINATOR_ORPHAN_CLEANUP_FAILED",
									failure.getMessage() == null ? "Owned coordinator cleanup is temporarily unavailable" : failure.getMessage()
								),
								changed,
								initial,
								false,
								List.of(),
								submittedWakeGeneration
							));
					return;
				}
			}
			publishMaintenanceResult(new DependencyMaintenanceResult(
				currentFingerprint, resolveDependencies(currentFingerprint), changed, initial, reaped,
				reaped ? List.copyOf(cleanupRoots) : List.of(), submittedWakeGeneration
			));
		});
	}

	private void submitLaunchMaintenance(long requestedAt) {
		if (maintenancePending || stopped) return;
		generation++;
		state = CoordinatorRecoveryState.STARTING;
		candidateAttemptQualified = false;
		String ownedLaunchId;
		try {
			ownedLaunchId = UUID.fromString(Objects.requireNonNull(launchIds.get(), "launch ID must not be null")).toString();
		} catch (RuntimeException failure) {
			recordFailure(requestedAt, "COORDINATOR_START_FAILED", "Could not start the Arena Agents coordinator", "process_start");
			return;
		}
		PreparedRuntime prepared = Objects.requireNonNull(runtime, "coordinator runtime is not prepared");
		launchId = ownedLaunchId;
		maintenancePending = true;
		submitMaintenance(() -> {
			try {
				LaunchRequest request = launchRequest(prepared, ownedLaunchId);
				ChildProcess started = Objects.requireNonNull(processLauncher.launch(request), "process launcher returned no child");
				publishMaintenanceResult(new LaunchMaintenanceResult(started, ownedLaunchId, now(), null));
			} catch (IOException | RuntimeException failure) {
				publishMaintenanceResult(new LaunchMaintenanceResult(
						null, ownedLaunchId, now(), failure.getMessage()
				));
			}
		});
	}

	private void submitTerminationMaintenance() {
		if (maintenancePending || pendingTermination == null || stopped) return;
		ChildProcess terminating = pendingTermination;
		maintenancePending = true;
		submitMaintenance(() -> {
			try {
				terminating.terminate();
				publishMaintenanceResult(new TerminationMaintenanceResult(terminating, null));
			} catch (RuntimeException failure) {
				publishMaintenanceResult(new TerminationMaintenanceResult(
						terminating, Objects.toString(failure.getMessage(), "Owned process-tree termination failed")
				));
			}
		});
	}

	private void submitPromotionMaintenance() {
		if (maintenancePending || stopped || runtime == null || !runtime.candidate()) return;
		PreparedRuntime promoting = runtime;
		maintenancePending = true;
		submitMaintenance(() -> {
			try {
				GenerationStatus status = generationController.promote(promoting.root(), promoting.generationId());
				publishMaintenanceResult(new PromotionMaintenanceResult(
						status, promoting.generationId(), safeFingerprint(), null
				));
			} catch (IOException | RuntimeException failure) {
				publishMaintenanceResult(new PromotionMaintenanceResult(
						null, promoting.generationId(), null,
						Objects.toString(failure.getMessage(), "Candidate promotion failed")
				));
			}
		});
	}

	private void submitRollbackMaintenance() {
		if (maintenancePending || stopped || runtime == null || !runtime.candidate()
				|| !runtime.lastKnownGoodAvailable()) {
			rollbackRequested = false;
			return;
		}
		PreparedRuntime failed = runtime;
		maintenancePending = true;
		submitMaintenance(() -> {
			try {
				GenerationStatus status = generationController.rollback(failed.root(), failed.generationId());
				publishMaintenanceResult(new RollbackMaintenanceResult(
						status, failed.generationId(), safeFingerprint(), null
				));
			} catch (IOException | RuntimeException failure) {
				publishMaintenanceResult(new RollbackMaintenanceResult(
						null, failed.generationId(), null,
						Objects.toString(failure.getMessage(), "Candidate rollback failed")
				));
			}
		});
	}

	private void submitMaintenance(Runnable task) {
		try {
			maintenanceWorker.execute(task);
		} catch (RuntimeException failure) {
			maintenancePending = false;
			state = CoordinatorRecoveryState.BLOCKED_RETRYABLE;
			nextRetryEpochMs = now() + DEPENDENCY_RECHECK_MS;
			setDiagnostic("COORDINATOR_MAINTENANCE_UNAVAILABLE", failure.getMessage(), "maintenance_worker");
		}
	}

	private void publishMaintenanceResult(MaintenanceResult result) {
		ChildProcess cleanup = null;
		synchronized (this) {
			if (stopped && result instanceof LaunchMaintenanceResult launch && launch.child() != null) {
				cleanup = launch.child();
			} else if (!stopped) {
				maintenanceResults.add(result);
			}
		}
		if (cleanup != null) terminateForShutdown(cleanup);
	}

	private void drainMaintenanceResults(long now) {
		MaintenanceResult result;
		while ((result = maintenanceResults.poll()) != null) {
			if (result instanceof DependencyFingerprintChanged) {
				dependencyWakeQueued.set(false);
				nextDependencyCheckEpochMs = 0L;
				continue;
			}
			maintenancePending = false;
			if (result instanceof DependencyMaintenanceResult dependency) {
				dependencyFingerprint = dependency.fingerprint();
				if (dependency.orphanCleanupSucceeded()) {
					reapedOrphanRoots.addAll(dependency.orphanRootsReaped());
					orphanCleanupComplete = orphanRuntimeRoots().stream().allMatch(reapedOrphanRoots::contains);
				}
				if (dependency.resolution() != null) {
					applyDependencyResolution(
							dependency.resolution(), now, dependency.initial(), dependency.fingerprintChanged()
					);
				}
				if (dependencyWakeGeneration.get() > dependency.submittedWakeGeneration()) {
					nextDependencyCheckEpochMs = 0L;
				}
			} else if (result instanceof LaunchMaintenanceResult launch) {
				if (launch.child() == null) {
					launchId = null;
					recordFailure(now, "COORDINATOR_START_FAILED", "Could not start the Arena Agents coordinator", "process_start");
					continue;
				}
				if (!Objects.equals(launchId, launch.launchId())) {
					queueTermination(launch.child());
					continue;
				}
				child = launch.child();
				processStartedEpochMs = launch.startedAtEpochMs();
				authenticationDeadlineEpochMs = launch.startedAtEpochMs() + AUTHENTICATION_TIMEOUT_MS;
				reconnectDeadlineEpochMs = 0L;
				authenticatedSinceEpochMs = 0L;
				coordinatorReconciled = false;
				stabilityCredited = false;
				nextRetryEpochMs = 0L;
				state = CoordinatorRecoveryState.AUTHENTICATING;
				failingBoundary = "bridge_authentication";
				LOGGER.info("Started the Arena Agents coordinator (pid {}, generation {})", child.pid(), generation);
			} else if (result instanceof TerminationMaintenanceResult termination) {
				if (pendingTermination != termination.child()) continue;
				if (termination.failureMessage() != null) {
					nextTerminationRetryEpochMs = now + TERMINATION_RETRY_MS;
					state = CoordinatorRecoveryState.BLOCKED_RETRYABLE;
					setDiagnostic("COORDINATOR_TERMINATION_FAILED", termination.failureMessage(), "process_termination");
					continue;
				}
				pendingTermination = null;
				nextTerminationRetryEpochMs = 0L;
			} else if (result instanceof PromotionMaintenanceResult promotion) {
				if (runtime == null || !promotion.generationId().equals(runtime.generationId())) continue;
				if (promotion.status() == null) {
					nextGenerationMutationEpochMs = now + DEPENDENCY_RECHECK_MS;
					setDiagnostic("COORDINATOR_GENERATION_PROMOTION_FAILED", promotion.failureMessage(), "runtime_promotion");
					continue;
				}
				if (!promotion.generationId().equals(promotion.status().generationId())) {
					nextGenerationMutationEpochMs = now + DEPENDENCY_RECHECK_MS;
					setDiagnostic("COORDINATOR_GENERATION_PROMOTION_FAILED",
							"Candidate promotion returned a different generation", "runtime_promotion");
					continue;
				}
				acceptOwnedDependencyFingerprint(promotion.fingerprint());
				runtime = withGeneration(runtime, promotion.status());
				nextGenerationMutationEpochMs = 0L;
				creditStability(now);
			} else if (result instanceof RollbackMaintenanceResult rollback) {
				if (runtime == null || !rollback.generationId().equals(runtime.generationId())) continue;
				if (rollback.status() == null) {
					nextGenerationMutationEpochMs = now + DEPENDENCY_RECHECK_MS;
					setDiagnostic("COORDINATOR_GENERATION_ROLLBACK_FAILED", rollback.failureMessage(), "runtime_rollback");
					continue;
				}
				acceptOwnedDependencyFingerprint(rollback.fingerprint());
				runtime = withGeneration(runtime, rollback.status());
				candidateFailures = 0;
				candidateAttemptQualified = false;
				rollbackRequested = false;
				nextGenerationMutationEpochMs = 0L;
				nextDependencyCheckEpochMs = now + DEPENDENCY_RECHECK_MS;
				nextRetryEpochMs = now;
				LOGGER.warn("Rolled back failed coordinator generation {} to verified generation {}",
						rollback.generationId(), rollback.status().generationId());
			}
		}
	}

	private void acceptOwnedDependencyFingerprint(String fingerprint) {
		dependencyFingerprint = Objects.requireNonNull(
				fingerprint, "owned dependency fingerprint must not be null"
		);
		dependencyChangeMonitor.acceptOwnedFingerprint(safeMonitorFingerprint(fingerprint));
	}

	private static PreparedRuntime withGeneration(PreparedRuntime runtime, GenerationStatus status) {
		return new PreparedRuntime(
				runtime.root(), runtime.coordinatorRoot(), runtime.main(), runtime.config(), runtime.secret(),
				runtime.voiceSecretPath(), runtime.nodeExecutable(), runtime.bridgeSecret(), runtime.voiceSecret(),
				runtime.bridgePort(), status.generationId(), status.candidate(),
				status.lastKnownGoodAvailable()
		);
	}

	private DependencyResolution resolveDependencies(String fingerprint) {
		try {
			DependencyResolution resolution = dependencyResolver.resolve(fingerprint);
			return Objects.requireNonNull(resolution, "dependency resolution must not be null");
		} catch (RuntimeException exception) {
			return DependencyResolution.blocked(
					"COORDINATOR_STARTUP_INVALID",
					exception.getMessage() == null ? "Coordinator startup dependencies are unavailable" : exception.getMessage()
			);
		}
	}

	/**
	 * Returns every runtime root that may contain an ownership record for this
	 * server. The installed game-local root is retained for upgrades, while the
	 * selected external package root covers packageRoot migrations without ever
	 * broadening cleanup beyond known roots.
	 */
	private synchronized List<Path> orphanRuntimeRoots() {
		return ownershipRoots(gameDirectory);
	}

	static List<Path> ownershipRoots(Path gameDirectory) {
		LinkedHashSet<Path> roots = new LinkedHashSet<>();
		String configured = System.getProperty("arenaagents.packageRoot");
		if (configured != null && !configured.isBlank()) {
			try {
				roots.add(Path.of(configured).toAbsolutePath().normalize());
			} catch (RuntimeException ignored) {
				// Dependency resolution reports the malformed path; cleanup remains bounded.
			}
		}
		Path discovered = findPackageRoot(gameDirectory);
		if (discovered != null) roots.add(discovered);
		roots.add(gameDirectory.resolve("arena-agents-runtime").toAbsolutePath().normalize());
		return List.copyOf(roots);
	}

	private String safeFingerprint() {
		try {
			return Objects.toString(dependencyResolver.fingerprint(), "");
		} catch (RuntimeException exception) {
			return "fingerprint-unavailable:" + exception.getClass().getName();
		}
	}

	private String safeMonitorFingerprint() {
		try {
			return Objects.toString(dependencyResolver.monitorFingerprint(), "");
		} catch (RuntimeException exception) {
			return "monitor-fingerprint-unavailable:" + exception.getClass().getName();
		}
	}

	private String safeMonitorFingerprint(String resolvedFingerprint) {
		try {
			return Objects.toString(dependencyResolver.monitorFingerprintFor(resolvedFingerprint), "");
		} catch (RuntimeException exception) {
			return "monitor-fingerprint-unavailable:" + exception.getClass().getName();
		}
	}

	private LaunchRequest launchRequest(PreparedRuntime prepared, String ownedLaunchId) {
		Map<String, String> environment = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
		environment.putAll(System.getenv());
		for (Map.Entry<String, String> override : launchEnvironmentOverrides.entrySet()) {
			environment.put(override.getKey(), override.getValue());
		}
		configureVoiceProviderCredential(prepared.root(), environment);
		environment.put("ARENA_AGENT_BRIDGE_SECRET", prepared.bridgeSecret());
		environment.put("ARENA_AGENT_VOICE_SECRET", prepared.voiceSecret());
		environment.put("ARENA_AGENT_COORDINATOR_LAUNCH_ID", ownedLaunchId);
		environment.put("ARENA_AGENT_COORDINATOR_RUNTIME_GENERATION", prepared.generationId());
		Path logs = gameDirectory.resolve("logs");
		return new LaunchRequest(
				List.of(
						prepared.nodeExecutable().toString(),
						prepared.main().toString(),
						"--config",
						prepared.config().toString()
				),
				prepared.coordinatorRoot(),
				environment,
				logs.resolve("arena-agents-coordinator.log"),
				logs.resolve("arena-agents-coordinator-error.log"),
				prepared.root(),
				prepared.main(),
				prepared.generationId(),
				ownedLaunchId,
				supervisorIdentity
		);
	}

	private void recordFailure(long now, String code, String message, String boundary) {
		restartBudget.recordUnexpectedExit();
		boolean candidateAttributable = candidateAttemptQualified && candidateRuntimeFailure(code);
		candidateAttemptQualified = false;
		if (runtime != null && runtime.candidate() && candidateAttributable) {
			candidateFailures++;
			if (candidateFailures >= CANDIDATE_FAILURES_BEFORE_ROLLBACK
					&& runtime.lastKnownGoodAvailable()) {
				rollbackRequested = true;
				nextGenerationMutationEpochMs = 0L;
			}
		}
		state = CoordinatorRecoveryState.BACKOFF;
		nextRetryEpochMs = now + restartBudget.nextDelayMs();
		processStartedEpochMs = 0L;
		authenticationDeadlineEpochMs = 0L;
		reconnectDeadlineEpochMs = 0L;
		authenticatedSinceEpochMs = 0L;
		coordinatorReconciled = false;
		stabilityCredited = false;
		setDiagnostic(code, message, boundary);
	}

	private static boolean candidateRuntimeFailure(String code) {
		return "COORDINATOR_EXITED".equals(code)
				|| "COORDINATOR_RECONNECT_TIMEOUT".equals(code);
	}

	private void setDiagnostic(String code, String message, String boundary) {
		boolean changed = !Objects.equals(failureCode, code)
				|| !Objects.equals(failureMessage, message)
				|| !Objects.equals(failingBoundary, boundary);
		failureCode = code;
		failureMessage = message;
		failingBoundary = boundary;
		if (changed && code != null) {
			LOGGER.warn("Arena Agents coordinator recovering [{}] at {}: {}", code, boundary,
					message == null ? "retry scheduled" : message);
		}
	}

	private void clearDiagnostic() {
		failureCode = null;
		failureMessage = null;
		failingBoundary = null;
	}

	private ChildProcess detachChild() {
		ChildProcess owned = child;
		if (owned == null) return null;
		child = null;
		launchId = null;
		processStartedEpochMs = 0L;
		authenticationDeadlineEpochMs = 0L;
		reconnectDeadlineEpochMs = 0L;
		return owned;
	}

	private void queueTermination(ChildProcess owned) {
		if (owned == null) return;
		if (pendingTermination != null && pendingTermination != owned) {
			throw new IllegalStateException("coordinator termination is already pending");
		}
		pendingTermination = owned;
	}

	private static void terminateForShutdown(ChildProcess process) {
		RuntimeException lastFailure = null;
		for (int attempt = 0; attempt < SHUTDOWN_TERMINATION_ATTEMPTS; attempt++) {
			try {
				process.terminate();
				return;
			} catch (RuntimeException failure) {
				lastFailure = failure;
			}
		}
		LOGGER.warn("Could not complete coordinator shutdown cleanup after {} attempts",
				SHUTDOWN_TERMINATION_ATTEMPTS, lastFailure);
	}

	static void terminateFailedStart(Process started) {
		CoordinatorProcessOwnership.terminateTree(started.toHandle());
	}

	static void configureVoiceProviderCredential(Path runtimeRoot, Map<String, String> environment) {
		if (nonBlankEnvironmentValue(environment, "FISH_AUDIO_API_KEY")
				|| nonBlankEnvironmentValue(environment, "FISH_API_KEY")) return;
		Path credentialFile = Objects.requireNonNull(runtimeRoot, "runtime root must not be null")
				.resolve(FISH_API_KEY_FILE).normalize();
		if (!Files.isRegularFile(credentialFile)) return;
		String credential;
		try {
			credential = Files.readString(credentialFile, StandardCharsets.UTF_8).trim();
		} catch (IOException exception) {
			LOGGER.warn("Ignoring unreadable optional Fish TTS credential; proximity speech will use its fallback");
			return;
		}
		if (credential.length() < 8 || credential.length() > 512) {
			LOGGER.warn("Ignoring malformed optional Fish TTS credential; proximity speech will use its fallback");
			return;
		}
		for (String existing : List.copyOf(environment.keySet())) {
			if (existing.equalsIgnoreCase("FISH_AUDIO_API_KEY")) environment.remove(existing);
		}
		environment.put("FISH_AUDIO_API_KEY", credential);
		LOGGER.info("Configured the Arena Agents TTS provider from the runtime credential file");
	}

	private static boolean nonBlankEnvironmentValue(Map<String, String> environment, String name) {
		for (Map.Entry<String, String> entry : environment.entrySet()) {
			if (entry.getKey().equalsIgnoreCase(name) && entry.getValue() != null && !entry.getValue().isBlank()) {
				return true;
			}
		}
		return false;
	}

	@Override
	public void close() {
		ArrayList<ChildProcess> cleanup = new ArrayList<>();
		boolean alreadyStopped;
		synchronized (this) {
			alreadyStopped = stopped;
			releaseManagedVoiceEndpoint();
			releaseDerivedVoiceRequestTimeout();
			if (alreadyStopped) return;
			stopped = true;
			state = CoordinatorRecoveryState.STOPPED;
			nextRetryEpochMs = 0L;
			nextDependencyCheckEpochMs = 0L;
			ChildProcess active = detachChild();
			if (active != null) cleanup.add(active);
			if (pendingTermination != null && !cleanup.contains(pendingTermination)) cleanup.add(pendingTermination);
			pendingTermination = null;
			MaintenanceResult result;
			while ((result = maintenanceResults.poll()) != null) {
				if (result instanceof LaunchMaintenanceResult launch && launch.child() != null
						&& !cleanup.contains(launch.child())) {
					cleanup.add(launch.child());
				}
			}
		}
		if (dependencyChangeMonitor != null) dependencyChangeMonitor.close();
		try {
			for (ChildProcess process : cleanup) {
				terminateForShutdown(process);
			}
		} finally {
			// Do not hold the supervisor monitor while waiting. An in-flight launch must
			// reacquire it to observe stopped=true, terminate its owned child, and clear
			// the ownership record before the daemon worker can finish.
			maintenanceWorker.close();
		}
	}

	private long now() {
		long value = clock.getAsLong();
		if (value < 0L) throw new IllegalStateException("coordinator clock must not be negative");
		return value;
	}

	private static boolean autoStartEnabled() {
		return !"false".equalsIgnoreCase(System.getProperty("arenaagents.coordinatorAutoStart"));
	}

	static void configureSharedSecretPaths(Path bridgeSecretPath, Path voiceSecretPath) {
		Path canonicalBridge = bridgeSecretPath.toAbsolutePath().normalize();
		Path canonicalVoice = voiceSecretPath.toAbsolutePath().normalize();
		System.setProperty("arenaagents.bridgeSecretFile", canonicalBridge.toString());
		System.setProperty("arenaagents.voiceSecretFile", canonicalVoice.toString());
	}

	void configureSharedVoiceEndpoint(Path configPath) {
		configureDerivedVoiceRequestTimeout(configPath);
		if (voiceEndpointExplicitOverride || !Files.isRegularFile(configPath)) return;
		String configured = configuredProperty("arenaagents.voiceUrl");
		if (!Objects.equals(configured, managedVoiceEndpoint)) {
			voiceEndpointExplicitOverride = true;
			managedVoiceEndpoint = null;
			return;
		}
		try {
			String endpoint = CoordinatorVoiceEndpoint.resolve(configPath, System.getenv(), launchEnvironmentOverrides)
					.orElse(null);
			if (Objects.equals(endpoint, managedVoiceEndpoint)) return;
			if (endpoint == null) System.clearProperty("arenaagents.voiceUrl");
			else System.setProperty("arenaagents.voiceUrl", endpoint);
			managedVoiceEndpoint = endpoint;
			voiceConfigurationRevision++;
		} catch (IOException | RuntimeException invalidVoiceConfiguration) {
			LOGGER.warn("Ignoring unavailable optional voice endpoint", invalidVoiceConfiguration);
		}
	}

	private void releaseManagedVoiceEndpoint() {
		if (managedVoiceEndpoint == null) return;
		if (Objects.equals(configuredProperty("arenaagents.voiceUrl"), managedVoiceEndpoint)) {
			System.clearProperty("arenaagents.voiceUrl");
		}
		managedVoiceEndpoint = null;
	}

	private static String configuredProperty(String name) {
		String value = System.getProperty(name);
		return value == null || value.isBlank() ? null : value;
	}

	private void configureDerivedVoiceRequestTimeout(Path configPath) {
		if (ownsVoiceRequestTimeout) return;
		String configured = System.getProperty(VOICE_REQUEST_TIMEOUT_PROPERTY);
		if (configured != null && !configured.isBlank()) return;
		try {
			derivedVoiceRequestTimeout = Integer.toString(CoordinatorVoiceEndpoint.requestTimeoutMs(configPath));
		} catch (IOException | RuntimeException invalidVoiceConfiguration) {
			LOGGER.warn("Ignoring unavailable optional voice request timeout", invalidVoiceConfiguration);
			return;
		}
		previousVoiceRequestTimeout = configured;
		System.setProperty(VOICE_REQUEST_TIMEOUT_PROPERTY, derivedVoiceRequestTimeout);
		ownsVoiceRequestTimeout = true;
	}

	private void releaseDerivedVoiceRequestTimeout() {
		if (!ownsVoiceRequestTimeout) return;
		if (Objects.equals(System.getProperty(VOICE_REQUEST_TIMEOUT_PROPERTY), derivedVoiceRequestTimeout)) {
			if (previousVoiceRequestTimeout == null) System.clearProperty(VOICE_REQUEST_TIMEOUT_PROPERTY);
			else System.setProperty(VOICE_REQUEST_TIMEOUT_PROPERTY, previousVoiceRequestTimeout);
		}
		previousVoiceRequestTimeout = null;
		derivedVoiceRequestTimeout = null;
		ownsVoiceRequestTimeout = false;
	}

	private static Path findPackageRoot(Path gameDirectory) {
		String configured = System.getProperty("arenaagents.packageRoot");
		if (configured != null && !configured.isBlank()) {
			try {
				return Path.of(configured).toAbsolutePath().normalize();
			} catch (RuntimeException exception) {
				return null;
			}
		}
		Path installedRuntime = gameDirectory.resolve("arena-agents-runtime");
		if (isPackageRoot(installedRuntime)) return installedRuntime;
		Path cursor = Path.of(System.getProperty("user.dir", ".")).toAbsolutePath().normalize();
		for (int depth = 0; depth < 8 && cursor != null; depth++, cursor = cursor.getParent()) {
			if (isPackageRoot(cursor)) return cursor;
		}
		Path sibling = gameDirectory.getParent() == null ? null : gameDirectory.getParent().resolve("agent arena");
		return sibling != null && isPackageRoot(sibling) ? sibling : null;
	}

	private static boolean isPackageRoot(Path candidate) {
		return Files.isRegularFile(candidate.resolve("runtime/bridge-secret.txt"))
				&& Files.isRegularFile(candidate.resolve("runtime/voice-secret.txt"))
				&& Files.isRegularFile(candidate.resolve("coordinator/src/dynamic-main.mjs"))
				&& Files.isRegularFile(candidate.resolve("runtime/dynamic-agents.json"));
	}

	private static String dependencyFailureCode(IOException failure) {
		String message = Objects.toString(failure.getMessage(), "").toLowerCase(java.util.Locale.ROOT);
		if (message.contains("secret")) return "BRIDGE_SECRET_INVALID";
		if (message.contains("config")) return "COORDINATOR_CONFIG_INVALID";
		if (message.contains("manifest") || message.contains("package") || message.contains("runtime")) {
			return "COORDINATOR_RUNTIME_INVALID";
		}
		return "COORDINATOR_STARTUP_INVALID";
	}

	static final class OwnedMaintenanceWorker implements MaintenanceWorker {
		private final ExecutorService executor = Executors.newSingleThreadExecutor(runnable -> {
			Thread thread = new Thread(runnable, "arenaagents-coordinator-maintenance");
			thread.setDaemon(true);
			return thread;
		});

		@Override
		public void execute(Runnable task) {
			executor.execute(Objects.requireNonNull(task, "maintenance task must not be null"));
		}

		@Override
		public void close() {
			executor.shutdown();
			boolean interrupted = Thread.interrupted();
			long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(SHUTDOWN_WAIT_MS);
			try {
				while (!executor.isTerminated()) {
					long remaining = deadline - System.nanoTime();
					if (remaining <= 0L) break;
					try {
						executor.awaitTermination(remaining, TimeUnit.NANOSECONDS);
					} catch (InterruptedException interruption) {
						interrupted = true;
					}
				}
				if (!executor.isTerminated()) {
					executor.shutdownNow();
					LOGGER.warn("Timed out waiting for coordinator shutdown cleanup");
				}
			} finally {
				if (interrupted) Thread.currentThread().interrupt();
			}
		}
	}

	private static final class DependencyChangeMonitor implements AutoCloseable {
		private final Supplier<String> fingerprint;
		private final Runnable changed;
		private final DependencyMonitorScheduler scheduler;
		private final AtomicBoolean closed = new AtomicBoolean();
		private final Object stateLock = new Object();
		private String previous;
		private boolean initialized;

		private DependencyChangeMonitor(
				Supplier<String> fingerprint,
				Runnable changed,
				DependencyMonitorScheduler scheduler
		) {
			this.fingerprint = Objects.requireNonNull(fingerprint, "dependency fingerprint must not be null");
			this.changed = Objects.requireNonNull(changed, "dependency wake callback must not be null");
			this.scheduler = Objects.requireNonNull(scheduler, "dependency monitor scheduler must not be null");
			this.scheduler.start(this::poll);
		}

		private void poll() {
			if (closed.get()) return;
			String current = fingerprint.get();
			if (closed.get()) return;
			if (observe(current)) changed.run();
		}

		private void observeSubmittedFingerprint(String submitted) {
			if (observe(submitted)) changed.run();
		}

		private void acceptOwnedFingerprint(String current) {
			synchronized (stateLock) {
				if (closed.get()) return;
				previous = current;
				initialized = true;
			}
		}

		private boolean observe(String current) {
			synchronized (stateLock) {
				if (closed.get()) return false;
				if (!initialized) {
					previous = current;
					initialized = true;
					return false;
				}
				if (Objects.equals(previous, current)) return false;
				previous = current;
				return true;
			}
		}

		@Override
		public void close() {
			if (!closed.compareAndSet(false, true)) return;
			scheduler.close();
		}
	}

	private static final class OwnedDependencyMonitorScheduler implements DependencyMonitorScheduler {
		private final ScheduledExecutorService executor = Executors.newSingleThreadScheduledExecutor(runnable -> {
			Thread thread = new Thread(runnable, "arenaagents-coordinator-dependency-monitor");
			thread.setDaemon(true);
			return thread;
		});
		private final AtomicBoolean started = new AtomicBoolean();

		@Override
		public void start(Runnable task) {
			if (!started.compareAndSet(false, true)) return;
			executor.scheduleWithFixedDelay(
					Objects.requireNonNull(task, "dependency monitor task must not be null"),
					0L,
					DEPENDENCY_MONITOR_INTERVAL_MS,
					TimeUnit.MILLISECONDS
			);
		}

		@Override
		public void close() {
			executor.shutdownNow();
		}
	}

	private static final class DefaultGenerationController implements GenerationController {
		@Override
		public GenerationStatus promote(Path root, String generationId) throws IOException {
			BundledCoordinatorInstaller.promote(root, generationId);
			return status(BundledCoordinatorInstaller.validate(root));
		}

		@Override
		public GenerationStatus rollback(Path root, String generationId) throws IOException {
			BundledCoordinatorInstaller.rollback(root, generationId);
			return status(BundledCoordinatorInstaller.validate(root));
		}

		private static GenerationStatus status(BundledCoordinatorInstaller.RuntimePackage runtime) {
			return new GenerationStatus(
					runtime.generationId(), runtime.candidate(), runtime.lastKnownGoodAvailable()
			);
		}
	}

	static final class DefaultProcessLauncher implements ProcessLauncher {
		private final ProcessStarter processStarter;
		private final boolean windowsJobOwnership;
		private final boolean posixSessionOwnership;

		DefaultProcessLauncher() {
			this(ProcessBuilder::start, WindowsCoordinatorJob.supported(), PosixCoordinatorSession.supported());
		}

		DefaultProcessLauncher(ProcessStarter processStarter) {
			this(processStarter, false, false);
		}

		DefaultProcessLauncher(
				ProcessStarter processStarter,
				boolean windowsJobOwnership,
				boolean posixSessionOwnership
		) {
			this.processStarter = Objects.requireNonNull(processStarter, "process starter must not be null");
			this.windowsJobOwnership = windowsJobOwnership;
			this.posixSessionOwnership = posixSessionOwnership;
			if (windowsJobOwnership && posixSessionOwnership) {
				throw new IllegalArgumentException("coordinator launch cannot use Windows and POSIX ownership together");
			}
		}

		@Override
		public ChildProcess launch(LaunchRequest request) throws IOException {
			Files.createDirectories(request.standardOutput().getParent());
			CoordinatorLogRotation.rotate(request.standardOutput().getParent());
			if (windowsJobOwnership) return launchInWindowsJob(request);
			if (posixSessionOwnership) return launchInPosixSession(request);
			ProcessBuilder builder = processBuilder(request, request.command());
			Process process = processStarter.start(builder);
			try {
				long startedAtEpochMs = processStart(process);
				CoordinatorProcessOwnership.record(
						request.runtimeRoot(), process, request.main(), request.generationId(), request.launchId(),
						request.supervisorIdentity()
				);
				return new OwnedProcessChild(
						request.runtimeRoot(), process, startedAtEpochMs, request.generationId(), request.launchId()
				);
			} catch (IOException | RuntimeException ownershipFailure) {
				terminateFailedStart(process);
				throw ownershipFailure;
			}
		}

		private ChildProcess launchInPosixSession(LaunchRequest request) throws IOException {
			PosixCoordinatorSession.Started started = null;
			boolean recorded = false;
			try {
				started = PosixCoordinatorSession.start(request, processStarter);
				CoordinatorProcessOwnership.recordPosix(
						request.runtimeRoot(), request.main(), request.generationId(), request.launchId(),
						request.supervisorIdentity(), started.sessionIdentity(), started.rootIdentity()
				);
				recorded = true;
				PosixSessionChild child = new PosixSessionChild(
						request.runtimeRoot(), started, request.generationId(), request.launchId()
				);
				started.release();
				return child;
			} catch (IOException | RuntimeException launchFailure) {
				if (started != null) {
					boolean cleanupComplete = false;
					try {
						started.terminate();
						cleanupComplete = true;
					} catch (IOException cleanupFailure) {
						launchFailure.addSuppressed(cleanupFailure);
					}
					if (recorded && cleanupComplete) {
						try {
							CoordinatorProcessOwnership.clear(
									request.runtimeRoot(), started.rootIdentity().pid(),
									started.rootIdentity().startedAtEpochMs(), request.generationId(), request.launchId()
							);
						} catch (IOException cleanupFailure) {
							launchFailure.addSuppressed(cleanupFailure);
						}
					}
				}
				throw launchFailure;
			}
		}

		private ChildProcess launchInWindowsJob(LaunchRequest request) throws IOException {
			List<String> gatedCommand = gatedCommand(request);
			WindowsCoordinatorJob job = WindowsCoordinatorJob.create(request.launchId());
			Process process = null;
			try {
				process = processStarter.start(processBuilder(request, gatedCommand));
				job.attach(process.pid());
				long startedAtEpochMs = processStart(process);
				CoordinatorProcessOwnership.record(
						request.runtimeRoot(), process, request.main(), request.generationId(), request.launchId(),
						request.supervisorIdentity()
				);
				WindowsJobChild child = new WindowsJobChild(
						request.runtimeRoot(), process, startedAtEpochMs, request.generationId(), request.launchId(), job
				);
				process.getOutputStream().write(1);
				process.getOutputStream().flush();
				process.getOutputStream().close();
				return child;
			} catch (IOException | RuntimeException launchFailure) {
				try {
					job.terminateAndClose();
				} catch (IOException cleanupFailure) {
					launchFailure.addSuppressed(cleanupFailure);
					try {
						job.closeHandle();
					} catch (IOException closeFailure) {
						launchFailure.addSuppressed(closeFailure);
					}
				}
				if (process != null && process.isAlive()) terminateFailedStart(process);
				throw launchFailure;
			}
		}

		private static ProcessBuilder processBuilder(LaunchRequest request, List<String> command) {
			ProcessBuilder builder = new ProcessBuilder(command);
			builder.directory(request.workingDirectory().toFile());
			builder.environment().clear();
			builder.environment().putAll(request.environment());
			builder.redirectOutput(ProcessBuilder.Redirect.appendTo(request.standardOutput().toFile()));
			builder.redirectError(ProcessBuilder.Redirect.appendTo(request.standardError().toFile()));
			return builder;
		}

		private static long processStart(Process process) throws IOException {
			return process.info().startInstant()
					.orElseThrow(() -> new IOException("coordinator process start time is unavailable"))
					.toEpochMilli();
		}

		private static List<String> gatedCommand(LaunchRequest request) throws IOException {
			List<String> command = request.command();
			if (command.size() < 2 || !samePathArgument(command.get(1), request.main())) {
				throw new IOException("Could not gate coordinator launch: command does not own the configured main module");
			}
			Path gate = request.main().resolveSibling("job-gate.mjs").toAbsolutePath().normalize();
			if (!Files.isRegularFile(gate)) {
				throw new IOException("Could not gate coordinator launch: job-gate.mjs is missing");
			}
			ArrayList<String> gated = new ArrayList<>(command.size() + 1);
			gated.add(command.getFirst());
			gated.add(gate.toString());
			gated.addAll(command.subList(1, command.size()));
			return List.copyOf(gated);
		}

		private static boolean samePathArgument(String argument, Path expected) {
			try {
				return Path.of(argument).toAbsolutePath().normalize().equals(expected.toAbsolutePath().normalize());
			} catch (RuntimeException invalid) {
				return false;
			}
		}
	}

	private static final class PosixSessionChild implements ChildProcess {
		private final Path runtimeRoot;
		private final PosixCoordinatorSession.Started started;
		private final String generationId;
		private final String launchId;
		private final AtomicBoolean terminated = new AtomicBoolean();

		private PosixSessionChild(
				Path runtimeRoot,
				PosixCoordinatorSession.Started started,
				String generationId,
				String launchId
		) {
			this.runtimeRoot = runtimeRoot;
			this.started = started;
			this.generationId = generationId;
			this.launchId = launchId;
		}

		@Override
		public boolean isAlive() {
			return started.rootIdentity().isAlive();
		}

		@Override
		public long pid() {
			return started.rootIdentity().pid();
		}

		@Override
		public synchronized void terminate() {
			if (terminated.get()) return;
			try {
				started.terminate();
				CoordinatorProcessOwnership.clear(
						runtimeRoot, started.rootIdentity().pid(), started.rootIdentity().startedAtEpochMs(),
						generationId, launchId
				);
				terminated.set(true);
			} catch (IOException exception) {
				throw new IllegalStateException("Could not terminate the owned POSIX coordinator session", exception);
			}
		}
	}

	private static final class WindowsJobChild implements ChildProcess {
		private final Path runtimeRoot;
		private final Process process;
		private final long startedAtEpochMs;
		private final String generationId;
		private final String launchId;
		private final WindowsCoordinatorJob job;
		private final AtomicBoolean terminated = new AtomicBoolean();

		private WindowsJobChild(
				Path runtimeRoot,
				Process process,
				long startedAtEpochMs,
				String generationId,
				String launchId,
				WindowsCoordinatorJob job
		) {
			this.runtimeRoot = runtimeRoot;
			this.process = process;
			this.startedAtEpochMs = startedAtEpochMs;
			this.generationId = generationId;
			this.launchId = launchId;
			this.job = job;
		}

		@Override
		public boolean isAlive() {
			return process.isAlive();
		}

		@Override
		public long pid() {
			return process.pid();
		}

		@Override
		public synchronized void terminate() {
			if (terminated.get()) return;
			try {
				job.terminateAndClose();
				awaitExit(process);
				CoordinatorProcessOwnership.clear(
						runtimeRoot, process.pid(), startedAtEpochMs, generationId, launchId
				);
				terminated.set(true);
			} catch (IOException exception) {
				throw new IllegalStateException("Could not terminate the owned coordinator job", exception);
			}
		}

		private static void awaitExit(Process process) throws IOException {
			try {
				if (!process.waitFor(2L, TimeUnit.SECONDS)) {
					throw new IOException("coordinator job root stayed alive after termination");
				}
			} catch (InterruptedException interrupted) {
				Thread.currentThread().interrupt();
				throw new IOException("coordinator job termination was interrupted", interrupted);
			}
		}
	}

	private static final class OwnedProcessChild implements ChildProcess {
		private final Path runtimeRoot;
		private final Process process;
		private final long startedAtEpochMs;
		private final String generationId;
		private final String launchId;
		private final AtomicBoolean terminated = new AtomicBoolean();
		private final AtomicBoolean trackingFailureLogged = new AtomicBoolean();
		private final LinkedHashSet<CoordinatorProcessOwnership.ProcessIdentity> descendants = new LinkedHashSet<>();

		private OwnedProcessChild(
				Path runtimeRoot,
				Process process,
				long startedAtEpochMs,
				String generationId,
				String launchId
		) throws IOException {
			this.runtimeRoot = runtimeRoot;
			this.process = process;
			this.startedAtEpochMs = startedAtEpochMs;
			this.generationId = generationId;
			this.launchId = launchId;
			captureDescendantsOrThrow();
			Thread.ofPlatform()
					.name("arenaagents-coordinator-descendants-" + process.pid())
					.daemon(true)
					.start(this::trackWhileRootLives);
		}

		@Override
		public boolean isAlive() {
			captureDescendants();
			return process.isAlive();
		}

		@Override
		public long pid() {
			return process.pid();
		}

		@Override
		public synchronized void terminate() {
			if (terminated.get()) return;
			captureDescendants();
			CoordinatorProcessOwnership.terminateTree(process.toHandle(), List.copyOf(descendants));
			try {
				CoordinatorProcessOwnership.clear(
						runtimeRoot, process.pid(), startedAtEpochMs, generationId, launchId
				);
			} catch (IOException exception) {
				throw new IllegalStateException("Could not safely clear coordinator ownership", exception);
			}
			terminated.set(true);
		}

		private void trackWhileRootLives() {
			while (!terminated.get() && process.isAlive()) {
				captureDescendants();
				try {
					Thread.sleep(DESCENDANT_TRACK_INTERVAL_MS);
				} catch (InterruptedException interrupted) {
					Thread.currentThread().interrupt();
					return;
				}
			}
			captureDescendants();
		}

		private synchronized void captureDescendants() {
			try {
				captureDescendantsOrThrow();
			} catch (IOException | RuntimeException failure) {
				if (trackingFailureLogged.compareAndSet(false, true)) {
					LOGGER.warn("Could not preserve coordinator descendant ownership", failure);
				}
			}
		}

		private synchronized void captureDescendantsOrThrow() throws IOException {
			List<CoordinatorProcessOwnership.ProcessIdentity> observed =
					CoordinatorProcessOwnership.captureDescendants(process.toHandle());
			boolean changed = descendants.removeIf(identity -> !identity.isAlive());
			changed |= descendants.addAll(observed);
			if (!changed) return;
			CoordinatorProcessOwnership.trackDescendants(
					runtimeRoot, process.pid(), startedAtEpochMs, generationId, launchId, List.copyOf(descendants)
			);
		}
	}

	static final class DefaultDependencyResolver implements DependencyResolver {
		private final Path gameDirectory;
		private final Map<String, String> environmentOverrides;
		private String cachedFingerprint;
		private DependencyResolution cachedReadyResolution;
		private String monitoredPackageRootProperty;
		private Path monitoredRoot;

		DefaultDependencyResolver(Path gameDirectory, Map<String, String> environmentOverrides) {
			this.gameDirectory = gameDirectory;
			this.environmentOverrides = environmentOverrides;
		}

		@Override
		public String fingerprint() {
			Path root = selectedRuntimeRoot(gameDirectory);
			ArrayList<String> values = new ArrayList<>();
			values.add(Objects.toString(System.getProperty("arenaagents.packageRoot"), ""));
			values.add(Objects.toString(System.getProperty(NodeRuntimeLocator.PROPERTY), ""));
			values.add(manifestFilesStamp(root.resolve("coordinator/.arena-agents-bundle-manifest")));
			values.add(manifestFilesStamp(root.resolve("coordinator.last-known-good/.arena-agents-bundle-manifest")));
			values.add(fileStamp(root.resolve("coordinator/src/dynamic-main.mjs")));
			values.add(fileStamp(root.resolve("runtime/coordinator-generation.properties")));
			values.add(fileStamp(root.resolve("runtime/dynamic-agents.json")));
			values.add(fileStamp(root.resolve("runtime/bridge-secret.txt")));
			values.add(fileStamp(root.resolve("runtime/voice-secret.txt")));
			values.add(fileStamp(bundledNode(root)));
			String explicit = System.getProperty(NodeRuntimeLocator.PROPERTY);
			if (explicit != null && !explicit.isBlank()) {
				try {
					values.add(fileStamp(Path.of(explicit)));
				} catch (RuntimeException invalid) {
					values.add("invalid-explicit-node");
				}
			}
			for (Map.Entry<String, String> override : environmentOverrides.entrySet()) {
				if (override.getKey().equalsIgnoreCase("PATH") || override.getKey().equalsIgnoreCase("APPDATA")) {
					values.add(override.getKey().toUpperCase(java.util.Locale.ROOT) + '=' + override.getValue());
				}
			}
			return String.join("|", values);
		}

		@Override
		public synchronized String monitorFingerprint() {
			String packageRootProperty = Objects.toString(System.getProperty("arenaagents.packageRoot"), "");
			if (monitoredRoot == null || !Objects.equals(monitoredPackageRootProperty, packageRootProperty)) {
				Path installedRoot = gameDirectory.resolve("arena-agents-runtime");
				Path packageRoot = findPackageRoot(gameDirectory);
				monitoredRoot = packageRoot == null ? installedRoot : packageRoot;
				monitoredPackageRootProperty = packageRootProperty;
			}
			StringBuilder token = new StringBuilder(512);
			appendFingerprintValue(token, packageRootProperty);
			String explicitNode = Objects.toString(System.getProperty(NodeRuntimeLocator.PROPERTY), "");
			appendFingerprintValue(token, explicitNode);
			appendFingerprintValue(token, fileStamp(monitoredRoot.resolve("coordinator/.arena-agents-bundle-manifest")));
			appendFingerprintValue(token, fileStamp(monitoredRoot.resolve("coordinator.last-known-good/.arena-agents-bundle-manifest")));
			appendFingerprintValue(token, fileStamp(monitoredRoot.resolve("coordinator/src/dynamic-main.mjs")));
			appendFingerprintValue(token, fileStamp(monitoredRoot.resolve("runtime/coordinator-generation.properties")));
			appendFingerprintValue(token, fileStamp(monitoredRoot.resolve("runtime/dynamic-agents.json")));
			appendFingerprintValue(token, fileStamp(monitoredRoot.resolve("runtime/bridge-secret.txt")));
			appendFingerprintValue(token, fileStamp(monitoredRoot.resolve("runtime/voice-secret.txt")));
			appendFingerprintValue(token, fileStamp(bundledNode(monitoredRoot)));
			if (!explicitNode.isBlank()) {
				try {
					appendFingerprintValue(token, fileStamp(Path.of(explicitNode)));
				} catch (RuntimeException invalid) {
					appendFingerprintValue(token, "invalid-explicit-node");
				}
			}
			for (Map.Entry<String, String> override : environmentOverrides.entrySet()) {
				if (override.getKey().equalsIgnoreCase("PATH") || override.getKey().equalsIgnoreCase("APPDATA")) {
					appendFingerprintValue(token,
							override.getKey().toUpperCase(java.util.Locale.ROOT) + '=' + override.getValue());
				}
			}
			return token.toString();
		}

		@Override
		public String monitorFingerprintFor(String resolvedFingerprint) {
			return monitorFingerprint();
		}

		private static void appendFingerprintValue(StringBuilder token, String value) {
			if (!token.isEmpty()) token.append('|');
			token.append(value);
		}

		@Override
		public DependencyResolution resolve() {
			return resolve(fingerprint());
		}

		@Override
		public synchronized DependencyResolution resolve(String fingerprint) {
			if (cachedReadyResolution != null && Objects.equals(cachedFingerprint, fingerprint)) {
				try {
					BundledCoordinatorInstaller.RuntimePackage validated =
							BundledCoordinatorInstaller.validate(cachedReadyResolution.runtime().root());
					if (validated.generationId().equals(cachedReadyResolution.runtime().generationId())) {
						return cachedReadyResolution;
					}
				} catch (IOException | RuntimeException invalidCachedRuntime) {
					// Fall through to installation/rollback recovery for an incomplete cached generation.
				}
				cachedFingerprint = null;
				cachedReadyResolution = null;
			}
			DependencyResolution resolution = resolveUncached();
			if (resolution.ready()) {
				cachedFingerprint = fingerprint;
				cachedReadyResolution = resolution;
			} else {
				cachedFingerprint = null;
				cachedReadyResolution = null;
			}
			return resolution;
		}

		private DependencyResolution resolveUncached() {
			BundledCoordinatorInstaller.RuntimePackage prepared = null;
			Path runtimeRoot = selectedRuntimeRoot(gameDirectory);
			NodeRuntimeLocator.LocatedNode node;
			try {
				if (recoverInterruptedDistributionNode(runtimeRoot)) {
					LOGGER.warn("Recovered the bundled Node.js runtime after an interrupted Arena Agents update");
				}
				node = NodeRuntimeLocator.locate(runtimeRoot);
			} catch (IOException | NodeRuntimeLocator.NodeRuntimeFailure failure) {
				String code = failure instanceof NodeRuntimeLocator.NodeRuntimeFailure nodeFailure
						? nodeFailure.code() : "NODE_RUNTIME_RECOVERY_FAILED";
				return DependencyResolution.blocked(null, code, failure.getMessage());
			}
			try {
				IOException installFailure = null;
				try {
					if (BundledCoordinatorInstaller.installBundled(runtimeRoot)) {
						LOGGER.info("Installed the bundled Arena Agents coordinator runtime");
					}
				} catch (IOException failure) {
					installFailure = failure;
					LOGGER.warn("Could not refresh the bundled Arena Agents runtime; attempting the last complete installed version", failure);
				}
				Path discoveredRoot = findPackageRoot(gameDirectory);
				if (discoveredRoot == null && installFailure != null) {
					try {
						if (BundledCoordinatorInstaller.restoreLastKnownGoodIfActiveInvalid(runtimeRoot)) {
							LOGGER.warn("Restored the verified Arena Agents coordinator runtime after the active installation became invalid");
							discoveredRoot = findPackageRoot(gameDirectory);
						}
					} catch (IOException restoreFailure) {
						installFailure.addSuppressed(restoreFailure);
					}
				}
				if (discoveredRoot == null) {
					if (installFailure != null) throw installFailure;
					throw new IOException("Coordinator runtime package is unavailable");
				}
				try {
					prepared = BundledCoordinatorInstaller.validate(discoveredRoot);
				} catch (IOException invalidRuntime) {
					try {
						if (!BundledCoordinatorInstaller.restoreLastKnownGoodIfActiveInvalid(discoveredRoot)) {
							throw invalidRuntime;
						}
						LOGGER.warn("Restored the verified Arena Agents coordinator runtime after active-generation corruption");
						prepared = BundledCoordinatorInstaller.validate(discoveredRoot);
					} catch (IOException restoreFailure) {
						if (restoreFailure != invalidRuntime) invalidRuntime.addSuppressed(restoreFailure);
						if (installFailure != null) invalidRuntime.addSuppressed(installFailure);
						throw invalidRuntime;
					}
				}
				validateConfig(prepared.config());
				String secret = Files.readString(prepared.secret(), StandardCharsets.UTF_8).trim();
				String voiceSecret = Files.readString(prepared.voiceSecret(), StandardCharsets.UTF_8).trim();
				return DependencyResolution.ready(prepared(prepared, node.executable(), secret, voiceSecret));
			} catch (IOException failure) {
				return DependencyResolution.blocked(
						prepared == null ? null : safePartial(prepared),
						dependencyFailureCode(failure),
						failure.getMessage() == null ? "Coordinator startup dependencies are unavailable" : failure.getMessage()
				);
			} catch (RuntimeException failure) {
				return DependencyResolution.blocked(
						"COORDINATOR_STARTUP_INVALID",
						failure.getMessage() == null ? "Coordinator startup dependencies are unavailable" : failure.getMessage()
				);
			}
		}

		static Path selectedRuntimeRoot(Path gameDirectory) {
			Path configuredOrDiscovered = findPackageRoot(gameDirectory);
			return configuredOrDiscovered == null
					? gameDirectory.resolve("arena-agents-runtime").toAbsolutePath().normalize()
					: configuredOrDiscovered;
		}

		static boolean recoverInterruptedDistributionNode(Path runtimeRoot) throws IOException {
			Path root = runtimeRoot.toAbsolutePath().normalize();
			Path active = root.resolve("runtime/toolchains/node").normalize();
			if (Files.exists(active)) return false;
			Path journal = root.resolve(".arena-runtime-transaction.json");
			if (!Files.isRegularFile(journal) || Files.size(journal) > 16_384L) return false;
			JsonObject document;
			try {
				document = JsonParser.parseString(Files.readString(journal, StandardCharsets.UTF_8)).getAsJsonObject();
			} catch (RuntimeException invalid) {
				throw new IOException("Interrupted runtime update journal is invalid", invalid);
			}
			JsonObject node = requiredJournalObject(document, "Node");
			Path recordedActive = containedJournalPath(root, node, "Active");
			Path backup = containedJournalPath(root, node, "Backup");
			containedJournalPath(root, node, "Staging");
			if (!recordedActive.equals(active)) {
				throw new IOException("Interrupted runtime update journal targets an unexpected Node.js directory");
			}
			boolean windows = System.getProperty("os.name", "").toLowerCase(java.util.Locale.ROOT).contains("win");
			Path backupExecutable = windows ? backup.resolve("node.exe") : backup.resolve("bin/node");
			if (!Files.isDirectory(backup) || Files.isSymbolicLink(backup)
					|| !Files.isRegularFile(backupExecutable)) {
				throw new IOException("Interrupted runtime update has no validated Node.js backup");
			}
			Files.createDirectories(active.getParent());
			Files.move(backup, active);
			return true;
		}

		private static JsonObject requiredJournalObject(JsonObject document, String field) throws IOException {
			if (!document.has(field) || !document.get(field).isJsonObject()) {
				throw new IOException("Interrupted runtime update journal is missing " + field);
			}
			return document.getAsJsonObject(field);
		}

		private static Path containedJournalPath(Path root, JsonObject object, String field) throws IOException {
			if (!object.has(field) || !object.get(field).isJsonPrimitive()
					|| !object.getAsJsonPrimitive(field).isString()) {
				throw new IOException("Interrupted runtime update journal has an invalid " + field + " path");
			}
			Path path;
			try {
				path = Path.of(object.get(field).getAsString()).toAbsolutePath().normalize();
			} catch (RuntimeException invalid) {
				throw new IOException("Interrupted runtime update journal has an invalid " + field + " path", invalid);
			}
			if (!path.startsWith(root) || path.equals(root)) {
				throw new IOException("Interrupted runtime update journal path escapes the package root");
			}
			return path;
		}

		private static PreparedRuntime prepared(
				BundledCoordinatorInstaller.RuntimePackage runtime,
				Path node,
				String secret,
				String voiceSecret
		) {
			return new PreparedRuntime(
					runtime.root(), runtime.coordinatorRoot(), runtime.main(), runtime.config(), runtime.secret(),
					runtime.voiceSecret(), node, secret, voiceSecret,
					bridgePort(runtime.config()), runtime.generationId(), runtime.candidate(), runtime.lastKnownGoodAvailable()
			);
		}

		private static PreparedRuntime safePartial(BundledCoordinatorInstaller.RuntimePackage runtime) {
			try {
				String secret = Files.readString(runtime.secret(), StandardCharsets.UTF_8).trim();
				String voiceSecret = Files.readString(runtime.voiceSecret(), StandardCharsets.UTF_8).trim();
				return prepared(runtime, null, secret, voiceSecret);
			} catch (IOException | RuntimeException ignored) {
				return null;
			}
		}

		private static void validateConfig(Path config) throws IOException {
			try {
				if (!JsonParser.parseString(Files.readString(config, StandardCharsets.UTF_8)).isJsonObject()) {
					throw new IOException("Coordinator config must be a JSON object");
				}
			} catch (com.google.gson.JsonParseException invalid) {
				throw new IOException("Coordinator config is invalid JSON", invalid);
			}
		}

		static int bridgePort(Path config) {
			try {
				JsonObject root = JsonParser.parseString(Files.readString(config, StandardCharsets.UTF_8)).getAsJsonObject();
				if (!root.has("bridge")) return 25_570;
				if (!root.get("bridge").isJsonObject()) throw new IllegalArgumentException("Coordinator config bridge section is invalid");
				JsonObject bridge = root.getAsJsonObject("bridge");
				if (!bridge.has("port") || !bridge.get("port").isJsonPrimitive()
						|| !bridge.getAsJsonPrimitive("port").isNumber()) {
					throw new IllegalArgumentException("Coordinator config bridge.port is missing");
				}
				int port;
				try {
					port = bridge.get("port").getAsBigDecimal().intValueExact();
				} catch (ArithmeticException invalidInteger) {
					throw new IllegalArgumentException("Coordinator config bridge.port is invalid", invalidInteger);
				}
				if (port < 1_024 || port > 65_535) {
					throw new IllegalArgumentException("Coordinator config bridge.port is invalid");
				}
				return port;
			} catch (IOException | com.google.gson.JsonParseException failure) {
				throw new IllegalArgumentException("Coordinator config bridge.port is invalid", failure);
			}
		}

		private static String fileStamp(Path path) {
			try {
				Path normalized = path.toAbsolutePath().normalize();
				if (!Files.exists(normalized)) return normalized + ":missing";
				return normalized + ":" + Files.size(normalized) + ":" + Files.getLastModifiedTime(normalized).toMillis();
			} catch (IOException | RuntimeException failure) {
				return Objects.toString(path) + ":unreadable";
			}
		}

		static String manifestFilesStamp(Path manifest) {
			StringBuilder stamp = new StringBuilder(fileStamp(manifest)).append(':').append(contentStamp(manifest));
			Path coordinator = manifest.toAbsolutePath().normalize().getParent();
			if (coordinator == null || !Files.isRegularFile(manifest)) return stamp.toString();
			try {
				for (String line : Files.readAllLines(manifest, StandardCharsets.UTF_8)) {
					if (line.isBlank()) continue;
					int separator = line.indexOf(' ');
					if (separator != 64 || line.length() <= 65) return stamp.append(":invalid").toString();
					Path file = coordinator.resolve(line.substring(separator + 1)).normalize();
					if (!file.startsWith(coordinator)) return stamp.append(":escaped").toString();
					stamp.append('|').append(coordinator.relativize(file)).append(':').append(fileStamp(file));
				}
			} catch (IOException | RuntimeException failure) {
				return stamp.append(":unreadable-files").toString();
			}
			return stamp.toString();
		}

		private static String contentStamp(Path path) {
			if (!Files.isRegularFile(path)) return "missing";
			try (InputStream input = Files.newInputStream(path)) {
				MessageDigest digest = MessageDigest.getInstance("SHA-256");
				byte[] buffer = new byte[16 * 1_024];
				for (int read; (read = input.read(buffer)) >= 0; ) {
					if (read > 0) digest.update(buffer, 0, read);
				}
				return java.util.HexFormat.of().formatHex(digest.digest());
			} catch (IOException | NoSuchAlgorithmException | RuntimeException failure) {
				return "unreadable";
			}
		}

		private static Path bundledNode(Path root) {
			boolean windows = System.getProperty("os.name", "").toLowerCase(java.util.Locale.ROOT).contains("win");
			return windows
					? root.resolve("runtime/toolchains/node/node.exe")
					: root.resolve("runtime/toolchains/node/bin/node");
		}
	}
}
