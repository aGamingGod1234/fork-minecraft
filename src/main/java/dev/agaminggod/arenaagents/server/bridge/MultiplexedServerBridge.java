package dev.agaminggod.arenaagents.server.bridge;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import dev.agaminggod.arenaagents.agent.AgentConstants;
import dev.agaminggod.arenaagents.agent.AgentDomainException;
import dev.agaminggod.arenaagents.agent.AgentGoal;
import dev.agaminggod.arenaagents.agent.AgentId;
import dev.agaminggod.arenaagents.agent.AgentLifecycleState;
import dev.agaminggod.arenaagents.agent.AgentProfile;
import dev.agaminggod.arenaagents.agent.AgentRecord;
import dev.agaminggod.arenaagents.agent.AgentTransition;
import dev.agaminggod.arenaagents.agent.goal.GoalPredicate;
import dev.agaminggod.arenaagents.agent.goal.GoalEvidence;
import dev.agaminggod.arenaagents.control.AgentControlCatalog;
import dev.agaminggod.arenaagents.control.AgentControlModelOption;
import dev.agaminggod.arenaagents.protocol.ActionType;
import dev.agaminggod.arenaagents.protocol.ProtocolCodec;
import dev.agaminggod.arenaagents.protocol.ProtocolException;
import dev.agaminggod.arenaagents.protocol.ProtocolConstants;
import dev.agaminggod.arenaagents.scenario.ScenarioAgentEvent;
import dev.agaminggod.arenaagents.scenario.runtime.ScenarioRuntimeService;
import dev.agaminggod.arenaagents.server.AgentRuntimeHooks;
import dev.agaminggod.arenaagents.server.AgentSavedData;
import dev.agaminggod.arenaagents.server.AgentRuntimeRouter;
import dev.agaminggod.arenaagents.server.AgentActivityPresentation;
import dev.agaminggod.arenaagents.server.AgentChatReporter;
import dev.agaminggod.arenaagents.server.AgentVerboseChat;
import dev.agaminggod.arenaagents.server.AgentVerboseState;
import dev.agaminggod.arenaagents.server.CodexAgentManager;
import dev.agaminggod.arenaagents.server.conversation.ConversationAudience;
import dev.agaminggod.arenaagents.server.conversation.ConversationEvent;
import dev.agaminggod.arenaagents.server.conversation.ConversationKind;
import dev.agaminggod.arenaagents.server.conversation.DeliveryReceipt;
import dev.agaminggod.arenaagents.server.conversation.PendingConversationWake;
import dev.agaminggod.arenaagents.server.conversation.ServerAgentConversationRouter;
import dev.agaminggod.arenaagents.server.goal.PendingGoalDraft;
import dev.agaminggod.arenaagents.server.goal.GoalPredicateWorldValidator;
import dev.agaminggod.arenaagents.server.goal.GoalInventoryCapacity;
import dev.agaminggod.arenaagents.server.goal.GoalSpecWireCodec;
import dev.agaminggod.arenaagents.server.goal.GoalVerificationRuntime;
import dev.agaminggod.arenaagents.server.goal.GoalSubmission;
import dev.agaminggod.arenaagents.server.perception.ObservationDispatchQueue;
import dev.agaminggod.arenaagents.server.perception.AttentionFactDelta;
import dev.agaminggod.arenaagents.server.perception.ServerObservationCollector;
import dev.agaminggod.arenaagents.server.perception.ServerObservationWireBudget;
import dev.agaminggod.arenaagents.server.runtime.ServerActionExecutor;
import dev.agaminggod.arenaagents.server.runtime.ServerActionProgress;
import dev.agaminggod.arenaagents.server.runtime.ServerActionObservation;
import dev.agaminggod.arenaagents.server.runtime.ActionProvenance;
import dev.agaminggod.arenaagents.server.runtime.ServerActionRequest;
import dev.agaminggod.arenaagents.server.runtime.ServerActionResult;
import dev.agaminggod.arenaagents.server.runtime.GoalCompletionVerifier;
import dev.agaminggod.arenaagents.server.runtime.input.AgentInputRuntime;
import dev.agaminggod.arenaagents.server.runtime.input.LeasedServerInputController;
import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.MessageDigest;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.LongSupplier;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;

public final class MultiplexedServerBridge implements AgentRuntimeHooks, AutoCloseable {
	public static final String LOOPBACK_HOST = "127.0.0.1";
	public static final int DEFAULT_PORT = 25_570;
	public static final int CONNECTION_QUEUE_CAP = 256;
	public static final int AGENT_QUEUE_CAP = 32;
	/** Default idle heartbeat spacing; urgent and explicitly requested observations are unaffected. */
	public static final int DEFAULT_HEARTBEAT_MIN_INTERVAL_TICKS = 10;
	private static final int OBSERVATIONS_PER_TICK = AgentConstants.DEFAULT_AGENT_LIMIT;
	private static final int HANDSHAKE_TIMEOUT_MS = 5_000;
	private static final int PREAUTH_SESSION_CAP = 8;
	private static final int PREAUTH_ACCEPT_RATE_CAP = 32;
	private static final long PREAUTH_ACCEPT_RATE_WINDOW_NANOS = TimeUnit.SECONDS.toNanos(1L);
	private static final int AUTHENTICATION_NONCE_BYTES = 32;
	private static final int AUTHENTICATION_TOKEN_LENGTH = 43;
	private static final String AUTHENTICATION_CONTEXT = "arena-agents-v2";
	private static final String ACTION_RESULT_REPLAY_CONTEXT = "arena-agents-v2-action-result-replay";
	static final long HANDSHAKE_RETRY_WAIT_MS = 25L;
	private static final int MIN_SECRET_LENGTH = 32;
	private static final int MAX_SECRET_LENGTH = 512;
	private static final int MAX_TRACKED_IDS = 4_096;
	private static final int MAX_ACTIVATED_GOAL_SPEC_REQUESTS = AgentConstants.DEFAULT_AGENT_LIMIT * 2;
	private static final int SERVER_TASK_BULK_CAP = 4_096;
	private static final int SERVER_TASK_URGENT_RESERVE = 512;
	private static final int SERVER_TASK_CONTROL_RESERVE = 512;
	private static final int SERVER_TASKS_PER_TICK = 256;
	private static final int OBSERVATION_HISTORY_CAPACITY = 4_096;
	private static final int MAX_TARGET_IDS_PER_OBSERVATION = 64;
	private static final int MAX_TARGET_IDS_WITH_INSPECTIONS = 320;
	private static final int MAX_CONVERSATION_SOURCES_PER_AGENT = 16;
	private static final long CATALOG_DISCOVERY_RETRY_BASE_NANOS = 50_000_000L;
	private static final int CATALOG_DISCOVERY_MAX_BACKOFF_SHIFT = 6;
	private static final String MAX_OBSERVATION_MESSAGE_ID = "m".repeat(128);
	private static final String HEARTBEAT_MIN_INTERVAL_TICKS_PROPERTY =
			"arenaagents.observationHeartbeatMinIntervalTicks";
	private static final String COORDINATOR_OFFLINE_MESSAGE =
			"AI agent coordinator is offline; check logs/arena-agents-coordinator-error.log for the startup cause";
	private static final Logger LOGGER = LoggerFactory.getLogger(MultiplexedServerBridge.class);
	private static final Set<String> INBOUND_TYPES = Set.of(
			"auth_challenge", "hello", "catalog_snapshot", "coordinator_status", "agent_ready", "planning_state", "goal_completed", "conversation_wake_ack", "goal_spec_proposal", "request_observation", "inspection_request", "action_command", "action_cancel", "action_result_ack", "agent_error", "verbose_event", "heartbeat", "fork_batch"
	);

	private final CodexAgentManager manager;
	private final AgentRuntimeRouter router;
	private final ServerActionExecutor actionExecutor;
	private final ServerAgentConversationRouter conversationRouter;
	private final ServerObservationCollector observations;
	private final GoalVerificationRuntime goalVerificationRuntime;
	private final BridgeEnvelopeCodec codec = new BridgeEnvelopeCodec();
	private static final GoalSpecWireCodec GOAL_SPEC_WIRE_CODEC = new GoalSpecWireCodec();
	private final String serverInstanceId = UUID.randomUUID().toString();
	private final ObservationPublication observationPublication = new ObservationPublication(
			AgentConstants.DEFAULT_AGENT_LIMIT,
			OBSERVATIONS_PER_TICK,
			configuredHeartbeatMinimumIntervalTicks(),
			(agentId, payload) -> ServerObservationWireBudget.fit(payload, candidate ->
					codec.encodedLineBytesForPayload(
							2, serverInstanceId, agentId.toString(), "observation",
							MAX_OBSERVATION_MESSAGE_ID, candidate
					) <= BridgeEnvelopeCodec.MAX_LINE_BYTES)
	);
	private final String secret;
	private final int port;
	private final ServerSocketFactory serverSockets;
	private final LongSupplier nanoTime;
	private final AgentVerboseState verboseState;
	private final BoundedServerTaskQueue serverTasks = new BoundedServerTaskQueue(
			SERVER_TASK_BULK_CAP + SERVER_TASK_URGENT_RESERVE + SERVER_TASK_CONTROL_RESERVE,
			SERVER_TASK_URGENT_RESERVE,
			SERVER_TASK_CONTROL_RESERVE
	);
	private final AtomicBoolean running = new AtomicBoolean();
	private final AtomicBoolean coordinatorDisconnectPending = new AtomicBoolean();
	private final AtomicBoolean activeDisconnectPending = new AtomicBoolean();
	private final AtomicLong messageIds = new AtomicLong();
	private final AtomicLong registryPublicationRevision = new AtomicLong();
	private final AtomicLong sessionGenerations = new AtomicLong();
	private final SecureRandom authenticationRandom = new SecureRandom();
	private final ProgramActionLedger programActions = new ProgramActionLedger();
	private final TerminalResultLedger terminalResults = new TerminalResultLedger();
	private final DurableActionJournal actionJournal;
	private final Object publicationLock = new Object();
	private final Object verboseControlLock = new Object();
	private final Set<AgentId> protocolKnownAgentIds = new HashSet<>();
	private final Set<AgentId> coordinatorReadyAgentIds = new HashSet<>();
	private final LinkedHashSet<Session> preauthSessions = new LinkedHashSet<>();
	private final ArrayDeque<Long> preauthAccepts = new ArrayDeque<>();
	private final Map<AgentId, RecoveryObservationIdentity> recoveryObservationIdentities = new HashMap<>();
	private final Map<UUID, ActivatedGoalSpecRequest> activatedGoalSpecRequests = new LinkedHashMap<>();
	private volatile Runnable handshakeSnapshotHook = () -> { };
	private volatile java.util.function.Consumer<AutoCloseable> handshakeCommittedHook = ignored -> { };
	private boolean disconnectInProgress;
	private long coordinatorLifecycleGeneration;
	private long verboseControlRevision;
	private long publishedVerboseControlRevision;
	private volatile Session session;
	private volatile ServerSocket serverSocket;
	private volatile Set<String> catalogProfiles = Set.of();
	private volatile List<AgentControlModelOption> catalogModels = AgentControlCatalog.fallbackOptions();
	private boolean actionJournalHydrated;
	private volatile boolean catalogLoaded;
	private boolean catalogDiscoveryPending;
	private int catalogDiscoveryAttempts;
	private long catalogDiscoveryRetryAtNanos;
	private long catalogDiscoveryGeneration;
	private String catalogDiscoveryFailureCode;
	private volatile long admissionTicks;
	private volatile long publicationTicks;

	public MultiplexedServerBridge(CodexAgentManager manager) {
		this(manager, configuredPort(), configuredSecretPath(), new AgentVerboseState());
	}

	public MultiplexedServerBridge(CodexAgentManager manager, AgentVerboseState verboseState) {
		this(manager, configuredPort(), configuredSecretPath(), verboseState);
	}

	public MultiplexedServerBridge(
			CodexAgentManager manager,
			AgentVerboseState verboseState,
			GoalVerificationRuntime goalVerificationRuntime
	) {
		this(manager, configuredPort(), configuredSecretPath(), verboseState, goalVerificationRuntime);
	}

	public MultiplexedServerBridge(CodexAgentManager manager, Path secretPath) {
		this(manager, DEFAULT_PORT, secretPath, new AgentVerboseState());
	}

	public MultiplexedServerBridge(CodexAgentManager manager, Path secretPath, AgentVerboseState verboseState) {
		this(manager, DEFAULT_PORT, secretPath, verboseState);
	}

	public MultiplexedServerBridge(CodexAgentManager manager, int port, Path secretPath) {
		this(manager, port, secretPath, new AgentVerboseState());
	}

	public MultiplexedServerBridge(
			CodexAgentManager manager,
			int port,
			Path secretPath,
			AgentVerboseState verboseState
	) {
		this(manager, port, secretPath, verboseState, defaultGoalVerificationRuntime(manager));
	}

	public MultiplexedServerBridge(
			CodexAgentManager manager,
			Path secretPath,
			AgentVerboseState verboseState,
			GoalVerificationRuntime goalVerificationRuntime
	) {
		this(manager, DEFAULT_PORT, secretPath, verboseState, goalVerificationRuntime);
	}

	public MultiplexedServerBridge(
			CodexAgentManager manager,
			int port,
			Path secretPath,
			AgentVerboseState verboseState,
			GoalVerificationRuntime goalVerificationRuntime
	) {
		this(manager, port, secretPath, verboseState, goalVerificationRuntime, ServerSocket::new, System::nanoTime);
	}

	MultiplexedServerBridge(
			CodexAgentManager manager,
			int port,
			Path secretPath,
			ServerSocketFactory serverSockets,
			LongSupplier nanoTime
	) {
		this(
				manager, port, secretPath, new AgentVerboseState(), defaultGoalVerificationRuntime(manager),
				serverSockets, nanoTime
		);
	}

	MultiplexedServerBridge(
			CodexAgentManager manager,
			int port,
			Path secretPath,
			ServerSocketFactory serverSockets,
			LongSupplier nanoTime,
			DurableActionJournal actionJournal
	) {
		this(
				manager, port, readSecret(secretPath), new AgentVerboseState(), defaultGoalVerificationRuntime(manager),
				serverSockets, nanoTime, actionJournal
		);
	}

	private MultiplexedServerBridge(
			CodexAgentManager manager,
			int port,
			Path secretPath,
			AgentVerboseState verboseState,
			GoalVerificationRuntime goalVerificationRuntime,
			ServerSocketFactory serverSockets
	) {
		this(manager, port, secretPath, verboseState, goalVerificationRuntime, serverSockets, System::nanoTime);
	}

	private MultiplexedServerBridge(
			CodexAgentManager manager,
			int port,
			Path secretPath,
			AgentVerboseState verboseState,
			GoalVerificationRuntime goalVerificationRuntime,
			ServerSocketFactory serverSockets,
			LongSupplier nanoTime
	) {
		this(
				manager, port, readSecret(secretPath), verboseState, goalVerificationRuntime,
				serverSockets, nanoTime
		);
	}

	private MultiplexedServerBridge(
			CodexAgentManager manager,
			int port,
			String preparedSecret,
			AgentVerboseState verboseState,
			GoalVerificationRuntime goalVerificationRuntime,
			ServerSocketFactory serverSockets,
			LongSupplier nanoTime
	) {
		this(manager, port, preparedSecret, verboseState, goalVerificationRuntime, serverSockets, nanoTime, defaultActionJournal(manager));
	}

	private MultiplexedServerBridge(
			CodexAgentManager manager,
			int port,
			String preparedSecret,
			AgentVerboseState verboseState,
			GoalVerificationRuntime goalVerificationRuntime,
			ServerSocketFactory serverSockets,
			LongSupplier nanoTime,
			DurableActionJournal actionJournal
	) {
		this.manager = Objects.requireNonNull(manager, "manager must not be null");
		this.actionJournal = Objects.requireNonNull(actionJournal, "actionJournal must not be null");
		this.router = new AgentRuntimeRouter(manager);
		if (port < 0 || port > 65_535) throw new IllegalArgumentException("bridge port must be between 0 and 65535");
		this.port = port;
		this.serverSockets = Objects.requireNonNull(serverSockets, "server socket factory must not be null");
		this.nanoTime = Objects.requireNonNull(nanoTime, "nanoTime must not be null");
		this.secret = validatePreparedSecret(preparedSecret);
		this.verboseState = Objects.requireNonNull(verboseState, "verboseState must not be null");
		this.goalVerificationRuntime = Objects.requireNonNull(goalVerificationRuntime, "goalVerificationRuntime must not be null");
		this.conversationRouter = new ServerAgentConversationRouter(
				manager, this::publishConversationEvent, this::publishGoalSpecRequest);
		this.actionExecutor = new ServerActionExecutor(
				manager, this::sendActionResult, this::sendActionProgress,
				dev.agaminggod.arenaagents.server.runtime.ServerProtectionPolicy.TRUSTED_LOCAL_OPERATOR,
				this::sendRespawnResultBeforeControl,
				conversationRouter
		);
		this.observations = new ServerObservationCollector(manager, actionExecutor);
	}

	public static MultiplexedServerBridge withPreparedSecret(CodexAgentManager manager, int port, String secret) {
		return withPreparedSecret(manager, port, secret, new AgentVerboseState(), defaultGoalVerificationRuntime(manager));
	}

	public static MultiplexedServerBridge withPreparedSecret(
			CodexAgentManager manager,
			int port,
			String secret,
			AgentVerboseState verboseState
	) {
		return withPreparedSecret(manager, port, secret, verboseState, defaultGoalVerificationRuntime(manager));
	}

	public static MultiplexedServerBridge withPreparedSecret(
			CodexAgentManager manager,
			int port,
			String secret,
			AgentVerboseState verboseState,
			GoalVerificationRuntime goalVerificationRuntime
	) {
		return new MultiplexedServerBridge(
				manager, port, secret, verboseState, goalVerificationRuntime, ServerSocket::new, System::nanoTime
		);
	}

	public synchronized void start() {
		start(serverSockets);
	}

	synchronized void start(ServerSocketFactory socketFactory) {
		Objects.requireNonNull(socketFactory, "server socket factory must not be null");
		hydrateActionJournal();
		if (!running.compareAndSet(false, true)) {
			return;
		}
		ServerSocket socket = null;
		try {
			socket = socketFactory.open();
			socket.setReuseAddress(true);
			socket.bind(new InetSocketAddress(InetAddress.getByName(LOOPBACK_HOST), port), 1);
			serverSocket = socket;
			manager.setRuntimeHooks(this);
			Thread.ofPlatform().daemon().name("arenaagents-v2-accept").start(this::acceptLoop);
		} catch (IOException exception) {
			running.set(false);
			if (socket != null) {
				try {
					socket.close();
				} catch (IOException closeException) {
					exception.addSuppressed(closeException);
				}
			}
			throw new BridgeProtocolException("BRIDGE_BIND_FAILED", "Could not bind " + LOOPBACK_HOST + ":" + port, exception);
		}
	}

	@FunctionalInterface
	interface ServerSocketFactory {
		ServerSocket open() throws IOException;
	}

	/**
	 * Admits coordinator work and applies action input at the start of the Minecraft tick.
	 * Every callback in this method must run on the server thread.
	 */
	public void startTick() {
		admissionTicks++;
		drainServerTasks();
		actionExecutor.tick();
	}

	/** Publishes terminal results and post-physics observations at the end of the tick. */
	public void endTick() {
		publicationTicks++;
		publishPendingDisconnects();
		publishPendingVerboseControl();
		publishCatalogDiscoveryRetry();
		replayPendingTerminalResults();
		List<AgentRecord> visibleRecords = manager.coordinatorVisibleRecords();
		List<AgentId> observationAgents = registeredObservationIds(visibleRecords);
		Set<AgentId> observationAgentSet = Set.copyOf(observationAgents);
		for (AgentId agentId : observations.changedActiveAgents(visibleRecords)) {
			if (!observationAgentSet.contains(agentId)) continue;
			observationPublication.markAttention(agentId);
			queueUrgentObservation(agentId);
		}
		observationPublication.scheduleIdleHeartbeat(observationAgents);
		observationPublication.drain(this::sendObservation);
	}

	/** Compatibility entry point for verification and embedders that do not expose tick phases. */
	public void tick() {
		startTick();
		endTick();
	}

	private void drainServerTasks() {
		serverTasks.drain(SERVER_TASKS_PER_TICK, task -> {
			try {
				task.run();
			} catch (RuntimeException exception) {
				LOGGER.error("Codex bridge server task failed", exception);
			}
		});
	}

	static List<AgentId> registeredObservationIds(List<AgentRecord> records) {
		Objects.requireNonNull(records, "records must not be null");
		// The product protocol is hard-capped at 16 agents even if restored data is malformed.
		return records.stream()
				.map(AgentRecord::agentId)
				.limit(AgentConstants.DEFAULT_AGENT_LIMIT)
				.toList();
	}

	public boolean authenticated() {
		Session active = session;
		return active != null && active.open.get() && active.authenticated.get();
	}

	TerminalResultLedger terminalResultsForVerification() {
		return terminalResults;
	}

	/** Low-cost counters for confirming queue pressure and tick-phase admission in production. */
	public BridgePerformanceSnapshot performanceSnapshot() {
		BoundedServerTaskQueue.QueueMetrics queue = serverTasks.metrics();
		return new BridgePerformanceSnapshot(
				admissionTicks, publicationTicks,
				queue.offeredUrgent(), queue.offeredControl(), queue.offeredBulk(),
				queue.rejected(), queue.drained(),
				queue.pendingUrgent(), queue.pendingControl(), queue.pendingBulk()
		);
	}

	public record BridgePerformanceSnapshot(
			long admissionTicks,
			long publicationTicks,
			long offeredUrgent,
			long offeredControl,
			long offeredBulk,
			long rejectedInbound,
			long drainedInbound,
			int pendingUrgent,
			int pendingControl,
			int pendingBulk
	) { }

	public void setVerbose(boolean enabled) {
		synchronized (verboseControlLock) {
			verboseState.setEnabled(enabled);
			verboseControlRevision += 1L;
		}
		publishPendingVerboseControl();
	}

	private void publishPendingVerboseControl() {
		VerboseControlSnapshot control = verboseControlSnapshot();
		if (control.revision() <= publishedVerboseControlRevision()) return;
		Session active = session;
		if (active == null || !active.authenticated.get()) return;
		try {
			active.enqueue(verboseControlEnvelope(control.enabled()));
			markVerboseControlPublished(control);
		} catch (BridgeProtocolException exception) {
			LOGGER.debug("Verbose control will retry on the next server tick: {}", exception.getMessage());
		}
	}

	private VerboseControlSnapshot verboseControlSnapshot() {
		synchronized (verboseControlLock) {
			return new VerboseControlSnapshot(verboseControlRevision, verboseState.enabled());
		}
	}

	private long publishedVerboseControlRevision() {
		synchronized (verboseControlLock) {
			return publishedVerboseControlRevision;
		}
	}

	private void markVerboseControlPublished(VerboseControlSnapshot control) {
		synchronized (verboseControlLock) {
			if (verboseControlRevision == control.revision() && verboseState.enabled() == control.enabled()) {
				publishedVerboseControlRevision = Math.max(publishedVerboseControlRevision, control.revision());
			}
		}
	}


	public String authenticatedLaunchId() {
		Session active = session;
		return active == null || !active.open.get() || !active.authenticated.get()
				? null
				: active.authenticatedLaunchId;
	}

	public long authenticatedSessionGeneration() {
		Session active = session;
		return active == null || !active.open.get() || !active.authenticated.get()
				? 0L
				: active.authenticatedSessionGeneration;
	}

	ObservationPublication observationPublicationForVerification() {
		return observationPublication;
	}

	boolean coordinatorReadyForVerification(AgentId agentId) {
		return coordinatorReadyAgentIds.contains(agentId);
	}

	/** Server ticks serialize input leases with observation publication at this boundary. */
	static ObservationPublication.Result publishObservationWithInputGuard(
			ObservationPublication publication,
			Optional<LeasedServerInputController> inputController,
			AgentId agentId,
			Object sourceSession,
			JsonObject observation,
			ObservationPublication.Writer writer,
			boolean allowUnchanged) {
		return publishObservationWithInputGuard(publication, inputController, agentId, sourceSession,
				observation, writer, allowUnchanged, publication.fitter);
	}

	private static ObservationPublication.Result publishObservationWithInputGuard(
			ObservationPublication publication, Optional<LeasedServerInputController> inputController,
			AgentId agentId, Object sourceSession, JsonObject observation,
			ObservationPublication.Writer writer, boolean allowUnchanged,
			java.util.function.BiFunction<AgentId, JsonObject, ServerObservationWireBudget.Fitted> fitter) {
		Objects.requireNonNull(publication, "publication must not be null");
		Objects.requireNonNull(inputController, "input controller must not be null");
		long revision = inputController.map(LeasedServerInputController::mutationRevision).orElse(-1L);
		try {
			return publication.publish(agentId, sourceSession, observation, writer, allowUnchanged, fitter);
		} finally {
			if (inputController.isPresent() && inputController.get().mutationRevision() != revision) {
				throw new BridgeProtocolException(
						"INPUT_MUTATED_DURING_OBSERVATION", "Observation publication changed input state");
			}
		}
	}

	int boundPortForVerification() {
		ServerSocket active = serverSocket;
		if (active == null) throw new IllegalStateException("bridge is not started");
		return active.getLocalPort();
	}

	public List<AgentControlModelOption> catalogModels() {
		return catalogModels;
	}

	public GoalSubmission submitGoal(
			String selector,
			String prompt,
			net.minecraft.server.level.ServerLevel sourceLevel,
			Optional<UUID> requestingPlayerId,
			GoalSubmission.Operation operation
	) {
		if (!authenticated()) {
			throw new AgentDomainException("COORDINATOR_DISCONNECTED", COORDINATOR_OFFLINE_MESSAGE);
		}
		return manager.submitGoal(selector, prompt, sourceLevel, requestingPlayerId, operation, this::publishGoalSpecRequest);
	}

	public DeliveryReceipt sendPlayerDirectMessage(ServerPlayer source, AgentId recipientAgentId, String text) {
		if (!authenticated()) {
			throw new AgentDomainException("COORDINATOR_DISCONNECTED", COORDINATOR_OFFLINE_MESSAGE);
		}
		return conversationRouter.deliverPlayerMessage(source, recipientAgentId, text);
	}

	public DeliveryReceipt sendNativePlayerDirectMessage(ServerPlayer source, AgentId recipientAgentId, String text) {
		if (!authenticated()) {
			throw new AgentDomainException("COORDINATOR_DISCONNECTED", COORDINATOR_OFFLINE_MESSAGE);
		}
		return conversationRouter.deliverPlayerMessageFromNativeWhisper(source, recipientAgentId, text);
	}

	public DeliveryReceipt sendPlayerProximitySpeech(ServerPlayer source, String text, boolean whispering) {
		if (!authenticated()) {
			throw new AgentDomainException("COORDINATOR_DISCONNECTED", COORDINATOR_OFFLINE_MESSAGE);
		}
		return conversationRouter.deliverPlayerProximitySpeech(source, text, whispering ? 16.0D : 48.0D);
	}

	public ServerAgentConversationRouter.ProximitySpeechAudience capturePlayerProximitySpeechAudience(
			ServerPlayer source,
			boolean whispering
	) {
		if (!authenticated()) {
			throw new AgentDomainException("COORDINATOR_DISCONNECTED", COORDINATOR_OFFLINE_MESSAGE);
		}
		return conversationRouter.capturePlayerProximitySpeechAudience(source, whispering ? 16.0D : 48.0D);
	}

	public DeliveryReceipt sendPlayerProximitySpeech(
			ServerAgentConversationRouter.ProximitySpeechAudience audience,
			String text
	) {
		if (!authenticated()) {
			throw new AgentDomainException("COORDINATOR_DISCONNECTED", COORDINATOR_OFFLINE_MESSAGE);
		}
		return conversationRouter.deliverPlayerProximitySpeech(audience, text);
	}

	@Override
	public void validateProfile(AgentProfile profile) {
		if (!authenticated()) {
			throw new AgentDomainException("COORDINATOR_DISCONNECTED", COORDINATOR_OFFLINE_MESSAGE);
		}
		if (!catalogLoaded) {
			throw new AgentDomainException("MODEL_CATALOG_UNAVAILABLE", "AI provider model catalog has not loaded yet");
		}
		Set<String> catalog = catalogProfiles;
		String requestedProfile = profile.provider() + "\u0000" + profile.model() + "\u0000" + profile.reasoning();
		if (!catalog.contains(requestedProfile)) {
			long providerProfileCount = catalog.stream()
					.filter(candidate -> candidate.startsWith(profile.provider() + "\u0000"))
					.count();
			throw new AgentDomainException(
					"UNSUPPORTED_MODEL_PROFILE",
					"Coordinator catalog rejected " + profile.provider() + "/" + profile.model()
							+ "/" + profile.reasoning() + " (provider profiles: " + providerProfileCount + ")"
			);
		}
	}

	@Override
	public boolean onCreated(AgentRecord record) {
		registryPublicationRevision.incrementAndGet();
		actionJournal.retainGoal(record.agentId(), logicalGoalId(record));
		programActions.beginGoal(record.agentId(), record.goalRevision());
		terminalResults.beginGoal(record.agentId(), record.goalRevision(), logicalGoalId(record));
		synchronized (publicationLock) {
			if (protocolKnownAgentIds.contains(record.agentId())) return true;
			Session active = session;
			if (active == null || !active.open.get() || !active.authenticated.get()) return false;
			active.enqueue(new BridgeEnvelope(
					2, serverInstanceId, record.agentId().toString(), "agent_registered",
					"server-" + messageIds.incrementAndGet(), registeredPayload(record)
			));
			protocolKnownAgentIds.add(record.agentId());
			return true;
		}
	}

	@Override
	public void onTransition(AgentTransition transition) {
		registryPublicationRevision.incrementAndGet();
		actionExecutor.actionSuccessLedger().retainRevision(
				transition.after().agentId(), transition.after().goalRevision()
		);
		if (transition.after().state() == dev.agaminggod.arenaagents.agent.AgentLifecycleState.COMPLETED
				|| transition.after().state() == dev.agaminggod.arenaagents.agent.AgentLifecycleState.ERROR
				|| transition.after().state() == dev.agaminggod.arenaagents.agent.AgentLifecycleState.IDLE) {
			actionExecutor.actionSuccessLedger().clear(
					transition.after().agentId(), transition.after().goalRevision()
			);
		}
		programActions.beginGoal(transition.after().agentId(), transition.after().goalRevision());
		terminalResults.beginGoal(transition.after().agentId(), transition.after().goalRevision(), logicalGoalId(transition.after()));
		withinPublicationBoundary(() -> {
			ScenarioRuntimeService.onAgentState(
					manager.server(),
					transition.after().agentId().toString(),
					publicState(transition.after().state())
			);
			if (transition.cancelAction()) {
				actionExecutor.cancel(transition.after().agentId(), "Lifecycle changed to " + transition.after().state());
			}
			if (!authenticated()) {
				if (transition.after().state().isActive()) {
					requestActiveDisconnect();
				}
				return null;
			}
			if (!protocolKnownAgentIds.contains(transition.after().agentId())) return null;
			observationPublication.markAttention(transition.after().agentId());
			queueUrgentObservation(transition.after().agentId());
			String operation = operation(transition);
			if (operation == null) return null;
			send("goal_control", transition.after().agentId().toString(), goalControlPayload(transition, operation));
			return null;
		});
		actionJournal.retainGoal(transition.after().agentId(), logicalGoalId(transition.after()));
	}

	void setHandshakeSnapshotHookForVerification(Runnable hook) {
		handshakeSnapshotHook = Objects.requireNonNull(hook, "handshake snapshot hook must not be null");
	}

	void setHandshakeCommittedHookForVerification(java.util.function.Consumer<AutoCloseable> hook) {
		handshakeCommittedHook = Objects.requireNonNull(hook, "handshake committed hook must not be null");
	}

	boolean coordinatorDisconnectPendingForVerification() {
		return coordinatorDisconnectPending.get();
	}

	@Override
	public void onRemoved(AgentId agentId, long terminalRevision) {
		registryPublicationRevision.incrementAndGet();
		actionExecutor.cancel(agentId, "Agent removed");
		actionExecutor.actionSuccessLedger().clear(agentId);
		conversationRouter.removeAgent(agentId);
		dev.agaminggod.arenaagents.server.voice.VoiceSubsystemRuntime.removeAgent(manager.server(), agentId);
		programActions.remove(agentId);
		terminalResults.remove(agentId);
		actionJournal.remove(agentId);
		observationPublication.remove(agentId);
		activatedGoalSpecRequests.entrySet().removeIf(entry -> entry.getValue().agentId().equals(agentId));
		synchronized (publicationLock) {
			coordinatorReadyAgentIds.remove(agentId);
			recoveryObservationIdentities.remove(agentId);
			if (!protocolKnownAgentIds.contains(agentId)) return;
			Session active = session;
			if (active == null || !active.open.get() || !active.authenticated.get()) {
				if (active != null) active.close();
				return;
			}
			JsonObject payload = new JsonObject();
			payload.addProperty("goalRevision", terminalRevision);
			try {
				active.enqueue(new BridgeEnvelope(
						2, serverInstanceId, agentId.toString(), "agent_removed",
						"server-" + messageIds.incrementAndGet(), payload
				));
				protocolKnownAgentIds.remove(agentId);
			} catch (RuntimeException exception) {
				active.close();
				throw exception;
			}
		}
	}

	@Override
	public <T> T withinPublicationBoundary(java.util.function.Supplier<T> publication) {
		bumpRegistryPublicationRevision();
		try {
			synchronized (publicationLock) {
				return AgentRuntimeHooks.super.withinPublicationBoundary(publication);
			}
		} finally {
			bumpRegistryPublicationRevision();
		}
	}

	@Override
	public void onServerStopping() {
		JsonObject payload = new JsonObject();
		payload.addProperty("reason", "server_stopping");
		send("shutdown", "server", payload);
		close();
	}

	@Override
	public synchronized void close() {
		try {
			closeTransport();
		} finally {
			actionJournal.close();
		}
	}

	private void closeTransport() {
		List<Session> candidates;
		synchronized (publicationLock) {
			running.set(false);
			verboseState.clearActivity();
			CoordinatorStatusStore.clear(manager.server());
			Session active = session;
			if (active != null) {
				active.close();
			}
			candidates = List.copyOf(preauthSessions);
		}
		for (Session candidate : candidates) candidate.close();
		try {
			if (serverSocket != null) {
				serverSocket.close();
			}
		} catch (IOException ignored) {
		}
	}

	/** Rebinding runs on the server thread, so finish the old session's state transition before replacement. */
	public synchronized void closeAndDrainDisconnect() {
		try {
			closeTransport();
			publishPendingDisconnects();
		} finally {
			actionJournal.close();
		}
	}

	private void acceptLoop() {
		while (running.get()) {
			try {
				Socket socket = serverSocket.accept();
				boolean admitted = false;
				try {
					if (!socket.getInetAddress().isLoopbackAddress()) continue;
					Session candidate = new Session(socket);
					synchronized (publicationLock) {
						if (!running.get() || !allowPreauthAcceptLocked()) continue;
						if (preauthSessions.size() >= PREAUTH_SESSION_CAP) continue;
						preauthSessions.add(candidate);
						candidate.start();
						admitted = true;
					}
				} finally {
					if (!admitted) socket.close();
				}
			} catch (IOException exception) {
				if (running.get()) {
					LOGGER.error("Codex bridge accept failed", exception);
				}
			}
		}
	}

	private boolean allowPreauthAcceptLocked() {
		long now = nanoTime.getAsLong();
		long cutoff = now - PREAUTH_ACCEPT_RATE_WINDOW_NANOS;
		while (!preauthAccepts.isEmpty() && preauthAccepts.getFirst() <= cutoff) preauthAccepts.removeFirst();
		if (preauthAccepts.size() >= PREAUTH_ACCEPT_RATE_CAP) return false;
		preauthAccepts.addLast(now);
		return true;
	}

	static void onSessionAccepted(ObservationPublication publication, Object session) {
		publication.activate(session);
	}

	static void onSessionClosed(ObservationPublication publication, Object session) {
		publication.deactivate(session);
	}

	private void accept(BridgeEnvelope envelope, Session source) {
		if (!INBOUND_TYPES.contains(envelope.type())) {
			throw new BridgeProtocolException("UNKNOWN_MESSAGE_TYPE", "Unsupported coordinator message: " + envelope.type());
		}
		if (!source.authenticated.get()) {
			if (source.clientNonce == null) acceptAuthChallenge(envelope, source);
			else acceptHello(envelope, source);
			return;
		}
		if (!serverInstanceId.equals(envelope.serverInstanceId())) {
			throw new BridgeProtocolException("SERVER_INSTANCE_MISMATCH", "Authenticated session changed serverInstanceId");
		}
		if (!serverTasks.offer(inboundLane(envelope.type()), () -> {
			synchronized (publicationLock) {
				if (session != source || !source.open.get() || !source.authenticated.get()) return;
				routeAuthenticated(envelope);
			}
		})) {
			throw new BridgeProtocolException("SERVER_TASK_QUEUE_FULL", "Coordinator exceeded the bounded server task queue");
		}
	}

	static BoundedServerTaskQueue.Lane inboundLane(String type) {
		return switch (Objects.requireNonNull(type, "type must not be null")) {
			// Lifecycle state, action admission, and cancellation share one FIFO. In particular,
			// an action_cancel can never overtake its preceding action_command.
			case "agent_ready", "planning_state", "goal_completed", "action_command", "action_cancel",
					"action_result_ack" -> BoundedServerTaskQueue.Lane.URGENT;
			case "coordinator_status", "conversation_wake_ack", "goal_spec_proposal",
					"request_observation", "inspection_request", "heartbeat" -> BoundedServerTaskQueue.Lane.CONTROL;
			default -> BoundedServerTaskQueue.Lane.BULK;
		};
	}

	private void acceptAuthChallenge(BridgeEnvelope envelope, Session source) {
		if (!"auth_challenge".equals(envelope.type()) || !"server".equals(envelope.agentId())) {
			throw new BridgeProtocolException("HANDSHAKE_REQUIRED", "auth_challenge must be the first coordinator message");
		}
		String clientNonce = requiredAuthenticationToken(envelope.payload(), "clientNonce");
		String serverNonce = newAuthenticationNonce();
		JsonObject payload = new JsonObject();
		payload.addProperty("replyTo", envelope.messageId());
		payload.addProperty("clientNonce", clientNonce);
		payload.addProperty("serverNonce", serverNonce);
		payload.addProperty("proof", authenticationProof("server", clientNonce, serverNonce, serverInstanceId, null));
		BridgeEnvelope response = new BridgeEnvelope(
				2, serverInstanceId, "server", "auth_response", "server-" + messageIds.incrementAndGet(), payload
		);
		source.beginAuthentication(clientNonce, serverNonce, response);
	}

	private void acceptHello(BridgeEnvelope envelope, Session source) {
		if (!"hello".equals(envelope.type()) || !"server".equals(envelope.agentId())) {
			throw new BridgeProtocolException("HANDSHAKE_REQUIRED", "hello must be the first coordinator message");
		}
		if (!serverInstanceId.equals(envelope.serverInstanceId())) {
			throw new BridgeProtocolException("SERVER_INSTANCE_MISMATCH", "hello changed the authenticated server instance");
		}
		String replyTo = requiredString(envelope.payload(), "replyTo");
		String clientNonce = requiredAuthenticationToken(envelope.payload(), "clientNonce");
		String serverNonce = requiredAuthenticationToken(envelope.payload(), "serverNonce");
		String suppliedLaunchId = optionalLaunchId(envelope.payload());
		String suppliedProof = requiredAuthenticationToken(envelope.payload(), "proof");
		if (!replyTo.equals(source.authResponseMessageId)
				|| !clientNonce.equals(source.clientNonce)
				|| !serverNonce.equals(source.serverNonce)) {
			throw new BridgeProtocolException("HANDSHAKE_MISMATCH", "hello does not match the active server authentication response");
		}
		String expectedProof = authenticationProof("coordinator", clientNonce, serverNonce, serverInstanceId, suppliedLaunchId);
		if (!MessageDigest.isEqual(expectedProof.getBytes(StandardCharsets.UTF_8), suppliedProof.getBytes(StandardCharsets.UTF_8))) {
			throw new BridgeProtocolException("AUTHENTICATION_FAILED", "Coordinator did not prove possession of the bridge secret");
		}
		while (true) {
			ensureHandshakeTimeRemaining(source);
			awaitDisconnectPublication(source);
			ensureHandshakeTimeRemaining(source);
			HandshakeSnapshot snapshot = awaitHandshakeSnapshot(source);
			long snapshotRevision = snapshot.registryRevision();
			List<AgentRecord> visibleRecords = snapshot.visibleRecords();
			JsonObject payload = new JsonObject();
			payload.addProperty("replyTo", envelope.messageId());
			payload.addProperty("authenticated", true);
			if (suppliedLaunchId != null) payload.addProperty("launchId", suppliedLaunchId);
			JsonArray registry = new JsonArray();
			Set<AgentId> handshakeKnownAgentIds = new HashSet<>();
			for (AgentRecord record : visibleRecords) {
				registry.add(registeredPayload(record));
				handshakeKnownAgentIds.add(record.agentId());
			}
			payload.add("registry", registry);
			ArrayList<BridgeEnvelope> handshake = new ArrayList<>();
			handshake.add(new BridgeEnvelope(
					2, serverInstanceId, "server", "hello_ack", "server-" + messageIds.incrementAndGet(), payload
			));
			VerboseControlSnapshot verboseControl = verboseControlSnapshot();
			handshake.add(verboseControlEnvelope(verboseControl.enabled()));
			for (PendingConversationWake wake : snapshot.pendingWakes()) {
				handshake.add(conversationWakeEnvelope(wake));
			}
			handshakeSnapshotHook.run();
			ensureHandshakeTimeRemaining(source);
			synchronized (publicationLock) {
				if (coordinatorDisconnectPending.get()
						|| disconnectInProgress
						|| snapshotRevision != registryPublicationRevision.get()) {
					awaitHandshakeRetryLocked(source);
					continue;
				}
				if (!source.open.get()) {
					throw new BridgeProtocolException("COORDINATOR_DISCONNECTED", "Bridge session closed during authentication");
				}
				if (session != null && session != source) {
					throw new BridgeProtocolException("SESSION_ACTIVE", "An authenticated coordinator session is already active");
				}
				preauthSessions.remove(source);
				session = source;
				onSessionAccepted(observationPublication, source);
				resetObservationPublication();
				verboseState.clearActivity();
				catalogProfiles = Set.of();
				catalogModels = AgentControlCatalog.fallbackOptions();
				catalogLoaded = false;
				catalogDiscoveryPending = false;
				catalogDiscoveryAttempts = 0;
				catalogDiscoveryRetryAtNanos = 0L;
				catalogDiscoveryGeneration = 0L;
				catalogDiscoveryFailureCode = null;
				protocolKnownAgentIds.clear();
				protocolKnownAgentIds.addAll(handshakeKnownAgentIds);
				coordinatorReadyAgentIds.clear();
				for (PendingGoalDraft draft : snapshot.goalSpecRequests()) {
					if (handshakeKnownAgentIds.contains(draft.agentId())) {
						handshake.add(goalSpecRequestEnvelope(draft));
					}
				}
				for (AgentRecord record : visibleRecords) terminalResults.beginGoal(record.agentId(), record.goalRevision(), logicalGoalId(record));
				List<ServerActionResult> terminalReplay = terminalResults.pending();
				int replayCapacity = Math.max(0, CONNECTION_QUEUE_CAP - handshake.size());
				Map<String, Integer> handshakeQueuedByAgent = new HashMap<>();
				for (BridgeEnvelope queued : handshake) handshakeQueuedByAgent.merge(queued.agentId(), 1, Integer::sum);
				List<ServerActionResult> claimedReplay = new ArrayList<>();
				for (ServerActionResult result : terminalReplay) {
					if (claimedReplay.size() >= replayCapacity) break;
					if (!handshakeKnownAgentIds.contains(result.agentId())) continue;
					if (handshakeQueuedByAgent.getOrDefault(result.agentId().toString(), 0) >= AGENT_QUEUE_CAP) continue;
					if (!terminalResults.claim(result, source)) continue;
					claimedReplay.add(result);
					handshake.add(actionResultEnvelope(result, source));
					handshakeQueuedByAgent.merge(result.agentId().toString(), 1, Integer::sum);
				}
				try {
					for (AgentRecord record : visibleRecords) programActions.beginGoal(record.agentId(), record.goalRevision());
					source.completeHandshake(handshake, suppliedLaunchId, sessionGenerations.incrementAndGet());
					handshakeCommittedHook.accept(source);
					coordinatorLifecycleGeneration++;
					markVerboseControlPublished(verboseControl);
				} catch (RuntimeException exception) {
					for (ServerActionResult result : claimedReplay) terminalResults.release(result, source);
					protocolKnownAgentIds.clear();
					coordinatorReadyAgentIds.clear();
					if (!source.authenticated.get() && session == source) {
						onSessionClosed(observationPublication, source);
						session = null;
					}
					throw exception;
				}
				return;
			}
		}
	}

	private BridgeEnvelope verboseControlEnvelope(boolean enabled) {
		JsonObject payload = new JsonObject();
		payload.addProperty("enabled", enabled);
		return new BridgeEnvelope(
				2, serverInstanceId, "server", "verbose_control",
				"server-" + messageIds.incrementAndGet(), payload
		);
	}

	private void routeAuthenticated(BridgeEnvelope envelope) {
		switch (envelope.type()) {
			case "fork_batch" -> send("fork_receipt", "server", dev.fork.integration.ForkEntrypoint.accept(manager.server(), envelope.payload()));
			case "catalog_snapshot" -> acceptCatalog(envelope.payload());
			case "coordinator_status" -> acceptCoordinatorStatus(envelope.payload());
			case "agent_ready", "planning_state" -> plannerReady(envelope);
			case "goal_completed" -> acceptGoalCompleted(envelope);
			case "conversation_wake_ack" -> acceptConversationWakeAck(envelope);
			case "goal_spec_proposal" -> acceptGoalSpecProposal(envelope);
			case "request_observation" -> acceptObservationRequest(envelope);
			case "inspection_request" -> acceptInspectionRequest(envelope);
			case "action_command" -> acceptAction(envelope);
			case "action_cancel" -> acceptActionCancel(envelope);
			case "action_result_ack" -> acceptActionResultAck(envelope);
			case "agent_error" -> acceptAgentError(envelope);
			case "verbose_event" -> acceptVerboseEvent(envelope);
			case "heartbeat" -> send("heartbeat", "server", new JsonObject());
			default -> throw new BridgeProtocolException("UNKNOWN_MESSAGE_TYPE", envelope.type());
		}
	}

	private void acceptActionResultAck(BridgeEnvelope envelope) {
		AgentId agentId = AgentId.parse(envelope.agentId());
		JsonObject payload = envelope.payload();
		requireKeys(payload, Set.of("goalRevision", "actionId"), "action_result_ack");
		long goalRevision = requiredLong(payload, "goalRevision");
		String actionId = requiredString(payload, "actionId");
		actionJournal.acknowledge(agentId, goalRevision, actionId);
		terminalResults.acknowledge(agentId, goalRevision, actionId);
	}

	private void acceptGoalSpecProposal(BridgeEnvelope envelope) {
		AgentId agentId = AgentId.parse(envelope.agentId());
		JsonObject payload = envelope.payload();
		requireKeys(payload, Set.of("requestId", "summary", "predicate"), "goal_spec_proposal");
		String requestIdValue = requiredString(payload, "requestId");
		UUID requestId;
		try {
			requestId = UUID.fromString(requestIdValue);
		} catch (IllegalArgumentException exception) {
			sendGoalSpecResult(agentId, requestIdValue, "rejected", "INVALID_GOAL_SPEC_REQUEST_ID");
			return;
		}
		String summary = requiredString(payload, "summary").strip();
		if (summary.isEmpty() || summary.length() > 512) {
			sendGoalSpecResult(agentId, requestIdValue, "rejected", "INVALID_GOAL_SPEC_SUMMARY");
			return;
		}
		try {
			JsonElement predicateElement = payload.get("predicate");
			if (predicateElement == null || !predicateElement.isJsonObject()) {
				throw new BridgeProtocolException("INVALID_GOAL_PREDICATE", "goal_spec_proposal.predicate must be an object");
			}
			GoalPredicate decodedPredicate = GOAL_SPEC_WIRE_CODEC.decodePredicate(predicateElement.getAsJsonObject());
			ActivatedGoalSpecRequest activated = activatedGoalSpecRequests.get(requestId);
			if (activated != null) {
				if (!activated.agentId().equals(agentId)) {
					throw new AgentDomainException("GOAL_DRAFT_AGENT_MISMATCH", "Goal draft belongs to another agent");
				}
				if (!activated.predicate().equals(decodedPredicate)) {
					throw new AgentDomainException("GOAL_DRAFT_PROPOSAL_CONFLICT", "Goal draft already activated with a different proposal");
				}
				sendGoalSpecResult(agentId, requestIdValue, "accepted", "PROPOSAL_ALREADY_ACTIVATED");
				return;
			}
			PendingGoalDraft draft = manager.goalDraft(requestId)
					.orElseThrow(() -> new AgentDomainException("UNKNOWN_GOAL_DRAFT", "Goal draft does not exist"));
			if (!draft.agentId().equals(agentId)) {
				throw new AgentDomainException("GOAL_DRAFT_AGENT_MISMATCH", "Goal draft belongs to another agent");
			}
			if (!draft.matches(manager.registry().require(agentId))) {
				throw new AgentDomainException("STALE_GOAL_DRAFT", "Goal draft no longer matches the target goal revision");
			}
			GoalPredicate predicate = GoalPredicateWorldValidator.bindToDimension(
					manager.normalizeGoalDraftTranslation(draft, decodedPredicate), draft.dimensionId());
			GoalPredicateWorldValidator.validateTranslatedProposal(predicate);
			manager.validateGoalDraftTranslation(draft, predicate);
			validateProposalIdentifiers(predicate, Set.copyOf(draft.candidateIds()));
			GoalInventoryCapacity.validateTranslated(
					predicate,
					manager.server() == null ? net.minecraft.core.RegistryAccess.EMPTY : manager.server().registryAccess()
			);
			if (GoalPredicateWorldValidator.requiresLiveLevel(predicate)) {
				GoalPredicateWorldValidator.validate(
						GoalPredicateWorldValidator.requireLevel(manager.server(), draft.dimensionId()), predicate);
			}
			boolean duplicate = draft.proposedPredicate().isPresent();
			PendingGoalDraft updated = manager.updateGoalDraftProposal(requestId, agentId, predicate);
			ServerPlayer player = manager.server() == null ? null : manager.server().getPlayerList().getPlayer(updated.requestingPlayerId());
			Optional<CodexAgentManager.GoalDraftResult> activatedResult = manager.activateTranslatedManagerDraft(updated);
			if (activatedResult.isPresent()) {
				recordActivatedGoalSpecRequest(requestId, agentId, decodedPredicate);
				if (player != null) {
					String operation = activatedResult.orElseThrow().operation() == dev.agaminggod.arenaagents.server.goal.GoalDraftResolution.Operation.QUEUE
							? "queued" : "started";
					player.sendSystemMessage(net.minecraft.network.chat.Component.literal(
							"Validated goal " + operation + ": " + summary));
				}
				sendGoalSpecResult(agentId, requestIdValue, "accepted", "PROPOSAL_ACTIVATED");
				return;
			}
			if (player != null && !duplicate) {
				player.sendSystemMessage(net.minecraft.network.chat.Component.literal(
						goalProposalMessage(updated, summary)));
			}
			sendGoalSpecResult(agentId, requestIdValue, "accepted", duplicate ? "PROPOSAL_ALREADY_STAGED" : "PROPOSAL_STAGED");
		} catch (AgentDomainException | BridgeProtocolException exception) {
			String code = exception instanceof AgentDomainException domain ? domain.code() : ((BridgeProtocolException) exception).code();
			sendGoalSpecResult(agentId, requestIdValue, "rejected", code);
		}
	}

	static String goalProposalMessage(PendingGoalDraft draft, String summary) {
		Objects.requireNonNull(draft, "draft must not be null");
		String cleanSummary = Objects.requireNonNull(summary, "summary must not be null")
				.replaceAll("\\s+", " ").strip();
		if (cleanSummary.isEmpty()) throw new IllegalArgumentException("summary must not be blank");
		char lastCharacter = cleanSummary.charAt(cleanSummary.length() - 1);
		String sentence = ".!?".indexOf(lastCharacter) >= 0 ? cleanSummary : cleanSummary + ".";
		return "Proposed goal for \"" + draft.originalRequest() + "\": " + sentence
				+ " Confirm or cancel draft " + draft.draftId() + ".";
	}

	private void recordActivatedGoalSpecRequest(UUID requestId, AgentId agentId, GoalPredicate predicate) {
		activatedGoalSpecRequests.put(requestId, new ActivatedGoalSpecRequest(agentId, predicate));
		while (activatedGoalSpecRequests.size() > MAX_ACTIVATED_GOAL_SPEC_REQUESTS) {
			activatedGoalSpecRequests.remove(activatedGoalSpecRequests.keySet().iterator().next());
		}
	}

	private record ActivatedGoalSpecRequest(AgentId agentId, GoalPredicate predicate) {
		private ActivatedGoalSpecRequest {
			Objects.requireNonNull(agentId, "agentId must not be null");
			Objects.requireNonNull(predicate, "predicate must not be null");
		}
	}

	private void validateProposalIdentifiers(GoalPredicate predicate, Set<String> candidates) {
		switch (predicate) {
			case GoalPredicate.InventoryContains value -> validateIdentifier(value.itemId(), candidates,
					id -> BuiltInRegistries.ITEM.containsKey(id), "item");
			case GoalPredicate.InventoryContainsAny value -> value.itemIds().forEach(
					itemId -> validateIdentifier(itemId, candidates, id -> BuiltInRegistries.ITEM.containsKey(id), "item"));
			case GoalPredicate.AdvancementGranted value -> validateIdentifier(value.advancementId(), candidates,
					id -> manager.server().getAdvancements().get(id) != null, "advancement");
			case GoalPredicate.EntityKilledByAgent value -> validateIdentifier(value.entityType(), candidates,
					id -> BuiltInRegistries.ENTITY_TYPE.containsKey(id), "entity type");
			case GoalPredicate.BlockMatches value -> validateIdentifier(value.blockId(), candidates,
					id -> BuiltInRegistries.BLOCK.containsKey(id), "block");
			case GoalPredicate.AllOf value -> value.predicates().forEach(child -> validateProposalIdentifiers(child, candidates));
			case GoalPredicate.AnyOf value -> value.predicates().forEach(child -> validateProposalIdentifiers(child, candidates));
			default -> { }
		}
	}

	private static void validateIdentifier(
			String value, Set<String> candidates, java.util.function.Predicate<Identifier> exists, String type
	) {
		if (!candidates.contains(value)) throw new AgentDomainException("GOAL_IDENTIFIER_NOT_CANDIDATE", type + " was not offered by the server");
		Identifier id = Identifier.tryParse(value);
		if (id == null || !exists.test(id)) throw new AgentDomainException("UNKNOWN_GOAL_IDENTIFIER", type + " does not exist on this server");
	}

	private void sendGoalSpecResult(AgentId agentId, String requestId, String status, String reasonCode) {
		JsonObject result = new JsonObject();
		result.addProperty("requestId", requestId);
		result.addProperty("status", status);
		result.addProperty("reasonCode", reasonCode);
		send("goal_spec_result", agentId.toString(), result);
	}

	private void acceptObservationRequest(BridgeEnvelope envelope) {
		AgentId id = AgentId.parse(envelope.agentId());
		AgentRecord record = manager.registry().require(id);
		JsonObject payload = envelope.payload();
		requireKeys(payload, Set.of("goalRevision"), "request_observation");
		if (requiredLong(payload, "goalRevision") != record.goalRevision()) {
			throw new AgentDomainException("STALE_REVISION", "Coordinator observation request revision is stale");
		}
		observations.invalidate(id);
		observationPublication.markAttention(id);
		queueUrgentObservation(id);
	}

	private void acceptCoordinatorStatus(JsonObject payload) {
		CoordinatorStatusStore.update(manager.server(), decodeCoordinatorStatus(payload, System.currentTimeMillis()));
	}

	private void acceptInspectionRequest(BridgeEnvelope envelope) {
		AgentId id = AgentId.parse(envelope.agentId());
		JsonObject payload = envelope.payload();
		requireKeys(payload, Set.of("goalRevision", "requestId", "query"), "inspection_request");
		AgentRecord record = manager.registry().require(id);
		long revision = requiredLong(payload, "goalRevision");
		String requestId = requiredString(payload, "requestId");
		if (requestId.length() > 128) throw new BridgeProtocolException("INVALID_FIELD", "Invalid inspection request id");
		Session source = session;
		if (source == null) return;
		JsonObject reply = new JsonObject();
		reply.addProperty("goalRevision", revision);
		reply.addProperty("requestId", requestId);
		try {
			if (revision != record.goalRevision()) throw new AgentDomainException("STALE_REVISION", "Inspection goal revision is stale");
			if (!payload.get("query").isJsonObject()) throw new AgentDomainException("INVALID_INSPECTION", "query must be an object");
			JsonObject query = validateInspectionQuery(payload.getAsJsonObject("query"));
			String section = requiredString(query, "section");
			if ("observation".equals(section)) {
				observations.invalidate(id);
				JsonObject observation = observations.collect(id);
				ObservationPublication.Result published = publishObservationWithInputGuard(
						observationPublication, AgentInputRuntime.existingController(manager.server()), id, source, observation,
						(agent, fresh) -> {
							return sendInspectionObservation(source, agent, fresh, inspectionObservationReply(reply, fresh));
						}, true, (agent, fresh) -> fitInspectionObservation(codec, serverInstanceId, agent, reply, fresh));
				if (published != ObservationPublication.Result.COMMITTED) throw new AgentDomainException("INSPECTION_UNAVAILABLE", "Fresh observation could not be delivered");
				return;
			} else {
				ServerPlayer player = manager.findAgentPlayer(id).orElseThrow(() -> new AgentDomainException("PLAYER_UNAVAILABLE", "No player to inspect"));
				JsonObject inspection = observations.collectInspection(player, query);
				ObservationPublication.Result published = observationPublication.deliverInspection(id, source, inspection,
						(agent, result) -> {
							reply.add("result", result);
							return sendInspectionEnvelope(source, agent, reply);
						});
				if (published != ObservationPublication.Result.COMMITTED) throw new AgentDomainException("INSPECTION_UNAVAILABLE", "Inspection session changed before delivery");
				return;
			}
		} catch (IllegalArgumentException | BridgeProtocolException exception) {
			JsonObject error = new JsonObject();
			error.addProperty("code", exception instanceof AgentDomainException domain ? domain.code()
					: exception instanceof BridgeProtocolException protocol ? protocol.code() : "INVALID_INSPECTION");
			error.addProperty("message", exception.getMessage() == null ? "Inspection unavailable" : exception.getMessage());
			reply.remove("result");
			reply.add("error", error);
		}
		sendInspectionEnvelope(source, id, reply);
	}

	static JsonObject validateInspectionQuery(JsonObject query) {
		Set<String> sections = Set.of("observation", "inventory", "menu", "entities", "blocks", "item", "block", "events", "landmarks", "nearby_containers", "recipes", "mechanics");
		JsonElement sectionValue = query.get("section");
		if (sectionValue == null || !sectionValue.isJsonPrimitive() || !sectionValue.getAsJsonPrimitive().isString()
				|| !sections.contains(sectionValue.getAsString())) throw new AgentDomainException("INVALID_INSPECTION", "Unknown inspection section");
		String section = sectionValue.getAsString();
		Set<String> allowed = new HashSet<>(Set.of("section", "offset", "limit"));
		if ("item".equals(section)) allowed.add("slot");
		if ("block".equals(section)) allowed.addAll(Set.of("x", "y", "z"));
		if ("events".equals(section)) allowed.add("afterSequence");
		if ("recipes".equals(section)) allowed.add("recipeId");
		if (!allowed.containsAll(query.keySet())) throw new AgentDomainException("INVALID_INSPECTION", "Unexpected field for inspection section");
		JsonObject result = query.deepCopy();
		if (!result.has("offset")) result.addProperty("offset", 0);
		if (!result.has("limit")) result.addProperty("limit", 16);
		inspectionInteger(result, "offset", 0, 4096);
		inspectionInteger(result, "limit", 1, 32);
		if ("item".equals(section)) inspectionInteger(result, "slot", 0, 255);
		if ("block".equals(section)) {
			inspectionInteger(result, "x", -30_000_000, 30_000_000);
			inspectionInteger(result, "y", -2048, 2048);
			inspectionInteger(result, "z", -30_000_000, 30_000_000);
		}
		if (result.has("afterSequence")) inspectionInteger(result, "afterSequence", -1, ActionProvenance.MAX_SAFE_INTEGER);
		if (result.has("recipeId")) {
			JsonElement recipe = result.get("recipeId");
			if (!recipe.isJsonPrimitive() || !recipe.getAsJsonPrimitive().isString() || recipe.getAsString().length() > 256
					|| !recipe.getAsString().matches("[a-z0-9_.-]+:[a-z0-9_./-]+")) {
				throw new AgentDomainException("INVALID_INSPECTION", "recipeId must be a namespaced recipe id");
			}
		}
		return result;
	}

	private static long inspectionInteger(JsonObject query, String field, long minimum, long maximum) {
		JsonElement value = query.get(field);
		if (value == null || !value.isJsonPrimitive() || !value.getAsJsonPrimitive().isNumber()) {
			throw new AgentDomainException("INVALID_INSPECTION", field + " must be a JSON integer");
		}
		try {
			long number = value.getAsBigDecimal().longValueExact();
			if (number < minimum || number > maximum) throw new ArithmeticException();
			return number;
		} catch (ArithmeticException | NumberFormatException exception) {
			throw new AgentDomainException("INVALID_INSPECTION", field + " is outside the inspection bounds");
		}
	}

	static JsonObject inspectionObservationReply(JsonObject correlation, JsonObject observation) {
		JsonObject reply = correlation.deepCopy();
		JsonObject result = new JsonObject();
		result.add("observation", observation.deepCopy());
		result.add("eventSequence", observation.get("eventSequence"));
		reply.add("result", result);
		return reply;
	}

	static ServerObservationWireBudget.Fitted fitInspectionObservation(
			BridgeEnvelopeCodec codec, String serverId, AgentId agent, JsonObject correlation, JsonObject observation) {
		return ServerObservationWireBudget.fit(observation, candidate ->
				codec.encodedLineBytesForPayload(2, serverId, agent.toString(), "observation", MAX_OBSERVATION_MESSAGE_ID, candidate)
						<= BridgeEnvelopeCodec.MAX_LINE_BYTES
				&& codec.encodedLineBytesForPayload(2, serverId, agent.toString(), "inspection_result", MAX_OBSERVATION_MESSAGE_ID,
						inspectionObservationReply(correlation, candidate)) <= BridgeEnvelopeCodec.MAX_LINE_BYTES);
	}

	static CoordinatorStatusSnapshot decodeCoordinatorStatus(JsonObject payload, long receivedAtEpochMs) {
		try {
		Set<String> legacyKeys = Set.of("reconciled", "profiles", "supportedProfileCount", "rosterReadyCount", "rosterCount", "scheduler", "circuits");
		Set<String> latencyKeys = Set.of("reconciled", "profiles", "supportedProfileCount", "rosterReadyCount", "rosterCount", "scheduler", "circuits", "latencies");
		Set<String> recoveryKeys = Set.of("reconciled", "profiles", "supportedProfileCount", "rosterReadyCount", "rosterCount", "scheduler", "circuits", "bridgeSessionEpoch", "runtimeGeneration", "components");
		Set<String> recoveryLatencyKeys = Set.of("reconciled", "profiles", "supportedProfileCount", "rosterReadyCount", "rosterCount", "scheduler", "circuits", "latencies", "bridgeSessionEpoch", "runtimeGeneration", "components");
		boolean extendedRecovery = payload.keySet().equals(recoveryKeys) || payload.keySet().equals(recoveryLatencyKeys);
		if (!payload.keySet().equals(legacyKeys) && !payload.keySet().equals(latencyKeys) && !extendedRecovery) {
			throw new BridgeProtocolException("INVALID_FIELD", "coordinator_status");
		}
		JsonArray profileValues = requiredArray(payload, "profiles", CoordinatorStatusSnapshot.MAX_PROFILES);
		ArrayList<CoordinatorStatusSnapshot.SupportedProfile> profiles = new ArrayList<>();
		for (var element : profileValues) {
			if (!element.isJsonObject()) throw new BridgeProtocolException("INVALID_COORDINATOR_STATUS", "profile must be an object");
			JsonObject profile = element.getAsJsonObject();
			Set<String> legacyProfileKeys = Set.of("agentId", "provider", "model", "reasoningEffort");
			Set<String> exactProfileKeys = Set.of("agentId", "provider", "model", "reasoningEffort", "serviceTier");
			if (!profile.keySet().equals(legacyProfileKeys) && !profile.keySet().equals(exactProfileKeys)) throw new BridgeProtocolException("INVALID_FIELD", "profile");
			profiles.add(new CoordinatorStatusSnapshot.SupportedProfile(
					requiredStatusString(profile, "agentId"), requiredStatusString(profile, "provider"),
					requiredStatusString(profile, "model"), requiredStatusString(profile, "reasoningEffort"),
					profile.has("serviceTier") ? requiredStatusString(profile, "serviceTier") : "priority"
			));
		}
		JsonObject scheduler = requiredObject(payload, "scheduler");
		Set<String> schedulerRequiredKeys = Set.of("active", "pending", "maxConcurrent", "maxPending", "warning");
		Set<String> schedulerAllowedKeys = Set.of(
				"active", "pending", "maxConcurrent", "maxPending", "warning", "mode", "configuredTarget", "target",
				"minConcurrency", "maxConcurrency", "urgentReserve", "ordinaryActiveLimit", "activeOrdinary", "activeUrgent",
				"pendingOrdinary", "pendingUrgent", "growthCount", "backoffCount", "lastChangeReason", "healthyCompletions",
				"ordinaryReservationRejections", "urgentReservationRejections"
		);
		if (!scheduler.keySet().containsAll(schedulerRequiredKeys) || !schedulerAllowedKeys.containsAll(scheduler.keySet())) {
			throw new BridgeProtocolException("INVALID_FIELD", "scheduler");
		}
		CoordinatorStatusSnapshot.SchedulerStatus schedulerStatus = new CoordinatorStatusSnapshot.SchedulerStatus(
				requiredInt(scheduler, "active"), requiredInt(scheduler, "pending"),
				requiredInt(scheduler, "maxConcurrent"), requiredInt(scheduler, "maxPending"), requiredBoolean(scheduler, "warning"),
				scheduler.has("mode") && "adaptive".equals(requiredString(scheduler, "mode")) && scheduler.has("maxConcurrency")
						? requiredInt(scheduler, "maxConcurrency")
						: requiredInt(scheduler, "maxConcurrent")
		);
		JsonArray circuitValues = requiredArray(payload, "circuits", CoordinatorStatusSnapshot.MAX_CIRCUITS);
		ArrayList<CoordinatorStatusSnapshot.CircuitHealth> circuits = new ArrayList<>();
		for (var element : circuitValues) {
			if (!element.isJsonObject()) throw new BridgeProtocolException("INVALID_COORDINATOR_STATUS", "circuit must be an object");
			JsonObject circuit = element.getAsJsonObject();
			requireKeys(circuit, Set.of("provider", "model", "operation", "count", "p50Ms", "p95Ms", "failureRate", "circuit"), "circuit");
			circuits.add(new CoordinatorStatusSnapshot.CircuitHealth(
					requiredStatusString(circuit, "provider"), requiredStatusString(circuit, "model"), requiredStatusString(circuit, "operation"),
					requiredInt(circuit, "count"), requiredInt(circuit, "p50Ms"), requiredInt(circuit, "p95Ms"),
					requiredDouble(circuit, "failureRate"), requiredStatusString(circuit, "circuit")
			));
		}
		JsonArray latencyValues = payload.has("latencies")
				? requiredArray(payload, "latencies", CoordinatorStatusSnapshot.MAX_LATENCIES)
				: new JsonArray();
		ArrayList<CoordinatorStatusSnapshot.LatencyHealth> latencies = new ArrayList<>();
		for (var element : latencyValues) {
			if (!element.isJsonObject()) throw new BridgeProtocolException("INVALID_COORDINATOR_STATUS", "latency must be an object");
			JsonObject latency = element.getAsJsonObject();
			requireKeys(latency, Set.of("operation", "count", "p50Ms", "p95Ms"), "latency");
			latencies.add(new CoordinatorStatusSnapshot.LatencyHealth(
					requiredStatusString(latency, "operation"), requiredInt(latency, "count"),
					requiredNonNegativeDouble(latency, "p50Ms"), requiredNonNegativeDouble(latency, "p95Ms")
			));
		}
		long bridgeSessionEpoch = 0L;
		String runtimeGeneration = null;
		ArrayList<CoordinatorStatusSnapshot.ComponentRecovery> components = new ArrayList<>();
		if (extendedRecovery) {
			bridgeSessionEpoch = requiredLong(payload, "bridgeSessionEpoch");
			runtimeGeneration = requiredNullableStatusString(payload, "runtimeGeneration");
			JsonArray componentValues = requiredArray(payload, "components", CoordinatorStatusSnapshot.MAX_COMPONENTS);
			for (var element : componentValues) {
				if (!element.isJsonObject()) throw new BridgeProtocolException("INVALID_COORDINATOR_STATUS", "component must be an object");
				JsonObject component = element.getAsJsonObject();
				requireKeys(component, Set.of(
						"component", "state", "fallbackMode", "boundary", "failureCode", "consecutiveFailureCount",
						"nextProbeAtEpochMs", "generation", "lastRecoveryAtEpochMs"
				), "component");
				components.add(new CoordinatorStatusSnapshot.ComponentRecovery(
						requiredStatusString(component, "component"), requiredStatusString(component, "state"),
						requiredNullableStatusString(component, "fallbackMode"), requiredNullableStatusString(component, "boundary"),
						requiredNullableStatusString(component, "failureCode"), requiredInt(component, "consecutiveFailureCount"),
						requiredNullableStatusLong(component, "nextProbeAtEpochMs"), requiredLong(component, "generation"),
						requiredNullableStatusLong(component, "lastRecoveryAtEpochMs")
				));
			}
		}
			return new CoordinatorStatusSnapshot(
					requiredBoolean(payload, "reconciled"), profiles, requiredInt(payload, "supportedProfileCount"),
					requiredInt(payload, "rosterReadyCount"), requiredInt(payload, "rosterCount"), schedulerStatus, circuits, latencies,
					bridgeSessionEpoch, runtimeGeneration, components,
					receivedAtEpochMs
			);
		} catch (BridgeProtocolException exception) {
			throw exception;
		} catch (IllegalArgumentException exception) {
			throw new BridgeProtocolException("INVALID_COORDINATOR_STATUS", exception.getMessage(), exception);
		}
	}

	private void acceptAgentError(BridgeEnvelope envelope) {
		AgentId agentId = AgentId.parse(envelope.agentId());
		long goalRevision = requiredLong(envelope.payload(), "goalRevision");
		if (!router.isCurrentActiveRevision(agentId, goalRevision)) {
			return;
		}
		String code = requiredString(envelope.payload(), "code");
		String message = requiredString(envelope.payload(), "message");
		AgentTransition transition = router.plannerFailed(agentId, goalRevision, message);
		reportRawAgentError(verboseState,
				() -> AgentChatReporter.failed(manager, transition.after(), code, message));
	}

	static void reportRawAgentError(AgentVerboseState verboseState, Runnable reporter) {
		if (verboseState.standardActivityEnabled()) reporter.run();
	}

	private void acceptVerboseEvent(BridgeEnvelope envelope) {
		AgentId agentId = AgentId.parse(envelope.agentId());
		VerboseEvent event = decodeVerboseEvent(envelope.payload());
		AgentRecord record = manager.registry().require(agentId);
		if (event.goalRevision() != record.goalRevision()) return;
		AgentVerboseChat.report(manager, verboseState, record, event.stage(), event.message());
	}

	static VerboseEvent decodeVerboseEvent(JsonObject payload) {
		requireKeys(payload, Set.of("goalRevision", "stage", "message"), "verbose_event");
		long goalRevision = requiredLong(payload, "goalRevision");
		String stage = requiredString(payload, "stage");
		if (!AgentVerboseChat.allowedStage(stage)) {
			throw new BridgeProtocolException("INVALID_VERBOSE_EVENT", "verbose_event.stage is not supported");
		}
		String message = requiredString(payload, "message");
		if (message.length() > AgentVerboseChat.MAX_MESSAGE_LENGTH
				|| message.codePoints().anyMatch(codePoint -> codePoint < 0x20 || codePoint == 0x7f)) {
			throw new BridgeProtocolException(
					"INVALID_VERBOSE_EVENT", "verbose_event.message must be plain text with at most 256 characters"
			);
		}
		return new VerboseEvent(goalRevision, stage, message);
	}

	static record VerboseEvent(long goalRevision, String stage, String message) { }

	private void acceptGoalCompleted(BridgeEnvelope envelope) {
		AgentId agentId = AgentId.parse(envelope.agentId());
		JsonObject payload = envelope.payload();
		requireKeys(payload, Set.of("goalRevision", "goalFingerprint", "traceId", "profile"), "goal_completed");
		long goalRevision = requiredLong(payload, "goalRevision");
		AgentRecord record = manager.registry().require(agentId);
		String traceId = requiredTraceId(payload, "traceId");
		String goalFingerprint = requiredString(payload, "goalFingerprint");
		if (!goalFingerprint.matches("[0-9a-f]{64}")) {
			throw new BridgeProtocolException("INVALID_GOAL_FINGERPRINT", "goalFingerprint must be lowercase SHA-256");
		}
		JsonObject profile = requiredObject(payload, "profile");
		requireKeys(profile, Set.of("provider", "model", "reasoningEffort", "serviceTier"), "goal_completed.profile");
		if (!record.profile().provider().equals(requiredString(profile, "provider"))
				|| !record.profile().model().equals(requiredString(profile, "model"))
				|| !record.profile().reasoning().equals(requiredString(profile, "reasoningEffort"))
				|| !record.profile().serviceTier().equals(requiredString(profile, "serviceTier"))) {
			throw new AgentDomainException("STALE_PROVENANCE", "Completion profile does not match the selected model profile");
		}
		GoalCompletionVerifier.VerificationResult verification = goalVerificationRuntime.evaluateRequest(
				agentId, goalRevision, goalFingerprint);
		VerboseEvent feedback = completionVerboseEvent(goalRevision, verification);
		boolean changed = verboseState.goalVerificationChanged(
				agentId, goalRevision, verification.verified(), verification.facts());
		if (!verification.verified() && changed) {
			AgentChatReporter.goalNotComplete(manager, record, verification.facts());
			AgentVerboseChat.report(manager, verboseState, record, feedback.stage(), feedback.message());
		}
		JsonObject result = completionResultPayload(goalRevision, traceId, goalFingerprint, verification);
		send("goal_completion_result", agentId.toString(), result);
		if (verification.verified()) {
			goalVerificationRuntime.acceptVerified(agentId, goalRevision, goalFingerprint, verification);
			AgentRecord completed = manager.registry().require(agentId);
			if (changed) {
				AgentChatReporter.goalVerified(manager, completed, verification.facts());
				AgentVerboseChat.report(manager, verboseState, completed, feedback.stage(), feedback.message());
			}
		}
	}

	static VerboseEvent completionVerboseEvent(
			long goalRevision,
			GoalCompletionVerifier.VerificationResult verification
	) {
		Objects.requireNonNull(verification, "verification must not be null");
		GoalEvidence.Fact fact = verification.facts().stream().filter(item -> !item.satisfied()).findFirst()
				.orElse(verification.facts().isEmpty() ? null : verification.facts().getFirst());
		if (verification.verified()) {
			return new VerboseEvent(goalRevision, "result",
					fact == null ? "Goal verified." : "Goal verified: " + fact.expectedValue() + ".");
		}
		return new VerboseEvent(goalRevision, "retry", fact == null
				? "Goal not complete. Continuing."
				: "Goal not complete: expected " + fact.expectedValue() + ", observed " + fact.observedValue() + ". Continuing.");
	}

	static JsonObject completionResultPayload(long goalRevision, String traceId, String goalFingerprint, GoalCompletionVerifier.VerificationResult verification) {
		JsonObject result = verification.toJson();
		result.addProperty("goalRevision", goalRevision);
		result.addProperty("traceId", traceId);
		result.addProperty("goalFingerprint", goalFingerprint);
		return result;
	}

	private void acceptConversationWakeAck(BridgeEnvelope envelope) {
		AgentId agentId = AgentId.parse(envelope.agentId());
		JsonObject payload = envelope.payload();
		requireKeys(payload, Set.of("transactionId", "goalRevision"), "conversation_wake_ack");
		UUID transactionId;
		try {
			transactionId = UUID.fromString(requiredString(payload, "transactionId"));
		} catch (IllegalArgumentException exception) {
			throw new BridgeProtocolException("INVALID_FIELD", "conversation_wake_ack.transactionId", exception);
		}
		manager.acknowledgeConversationWake(transactionId, agentId, requiredLong(payload, "goalRevision"));
	}

	private void requestActiveDisconnect() {
		activeDisconnectPending.set(true);
	}

	private void publishPendingDisconnects() {
		boolean coordinatorDisconnect;
		boolean activeDisconnect;
		synchronized (publicationLock) {
			coordinatorDisconnect = coordinatorDisconnectPending.getAndSet(false);
			activeDisconnect = activeDisconnectPending.getAndSet(false) && !authenticated();
			if (!coordinatorDisconnect && !activeDisconnect) return;
			disconnectInProgress = true;
		}
		try {
			if (coordinatorDisconnect) actionExecutor.coordinatorDisconnected();
			disconnectActiveAgents();
		} finally {
			synchronized (publicationLock) {
				disconnectInProgress = false;
				publicationLock.notifyAll();
			}
		}
	}

	private void awaitDisconnectPublication(Session source) {
		synchronized (publicationLock) {
			while (disconnectInProgress) {
				long remainingNanos = source.handshakeRemainingNanos();
				if (remainingNanos <= 0L) throw handshakeTimeout();
				try {
					publicationLock.wait(Math.max(1L, Math.min(HANDSHAKE_RETRY_WAIT_MS,
							TimeUnit.NANOSECONDS.toMillis(remainingNanos))));
				} catch (InterruptedException exception) {
					Thread.currentThread().interrupt();
					throw new BridgeProtocolException(
							"COORDINATOR_DISCONNECTED", "Bridge authentication was interrupted", exception
					);
				}
			}
		}
	}

	void hydrateActionJournalForVerification() {
		hydrateActionJournal();
	}

	private synchronized void hydrateActionJournal() {
		if (actionJournalHydrated) return;
		actionJournal.terminalizeAccepted(MultiplexedServerBridge::recoveryUncertainResult);
		List<AgentRecord> records = manager.registry().records();
		Map<AgentId, AgentRecord> current = new HashMap<>();
		for (AgentRecord record : records) {
			current.put(record.agentId(), record);
			actionJournal.retainGoal(record.agentId(), logicalGoalId(record));
		}
		for (DurableActionJournal.Entry entry : actionJournal.snapshot()) {
			AgentRecord record = current.get(entry.request().agentId());
			if (record == null) {
				actionJournal.remove(entry.request().agentId());
				continue;
			}
		}
		for (AgentRecord record : records) {
			programActions.beginGoal(record.agentId(), record.goalRevision());
		}
		for (DurableActionJournal.Entry entry : actionJournal.snapshot()) {
			AgentRecord record = current.get(entry.request().agentId());
			if (record == null) continue;
			ServerActionResult result = entry.result();
			if (entry.request().goalRevision() == record.goalRevision()) {
				programActions.accept(entry.request());
				programActions.terminal(result);
			}
			if (entry.phase() == DurableActionJournal.Phase.TERMINAL) {
				terminalResults.beginGoal(entry.request().agentId(), entry.request().goalRevision(), entry.logicalGoalId());
				terminalResults.retain(result);
			}
		}
		for (AgentRecord record : records) {
			terminalResults.beginGoal(record.agentId(), record.goalRevision(), logicalGoalId(record));
		}
		actionJournalHydrated = true;
	}

	static ServerActionResult recoveryUncertainResult(ServerActionRequest request) {
		return new ServerActionResult(
				request.agentId(), request.goalRevision(), request.actionId(), request.type(), request.traceId(),
				dev.agaminggod.arenaagents.server.runtime.ServerActionState.FAILED,
				"RECOVERY_UNCERTAIN",
				"Server restarted after accepting the action; its physical outcome is uncertain and it will not be replayed",
				0L, Math.max(1L, System.currentTimeMillis()), true, true
		);
	}

	private HandshakeSnapshot awaitHandshakeSnapshot(Session source) {
		if (manager.server() == null || !manager.server().isRunning()) {
			return captureHandshakeSnapshot(false);
		}
		CompletableFuture<HandshakeSnapshot> snapshot = new CompletableFuture<>();
		if (!serverTasks.offer(BoundedServerTaskQueue.Lane.CONTROL, () -> {
			try {
				snapshot.complete(captureHandshakeSnapshot(true));
			} catch (RuntimeException exception) {
				snapshot.completeExceptionally(exception);
			}
		})) {
			throw new BridgeProtocolException(
					"SERVER_TASK_QUEUE_FULL", "Could not capture the coordinator handshake on the server thread");
		}
		long remainingNanos = source.handshakeRemainingNanos();
		if (remainingNanos <= 0L) throw handshakeTimeout();
		try {
			return snapshot.get(remainingNanos, TimeUnit.NANOSECONDS);
		} catch (TimeoutException exception) {
			throw handshakeTimeout();
		} catch (InterruptedException exception) {
			Thread.currentThread().interrupt();
			throw new BridgeProtocolException(
					"COORDINATOR_DISCONNECTED", "Bridge authentication was interrupted", exception);
		} catch (ExecutionException exception) {
			Throwable cause = exception.getCause();
			if (cause instanceof RuntimeException runtime) throw runtime;
			throw new BridgeProtocolException(
					"HANDSHAKE_SNAPSHOT_FAILED", "Could not capture coordinator state", cause);
		}
	}

	private HandshakeSnapshot captureHandshakeSnapshot(boolean mayPruneStaleDrafts) {
		long revision = registryPublicationRevision.get();
		List<AgentRecord> visibleRecords = manager.coordinatorVisibleRecords();
		List<PendingConversationWake> pendingWakes = manager.pendingConversationWakes();
		Map<AgentId, AgentRecord> recordsById = new HashMap<>();
		for (AgentRecord record : manager.records()) recordsById.put(record.agentId(), record);
		ArrayList<PendingGoalDraft> goalSpecRequests = new ArrayList<>();
		ArrayList<UUID> staleDraftIds = new ArrayList<>();
		for (PendingGoalDraft draft : manager.goalDrafts()) {
			AgentRecord record = recordsById.get(draft.agentId());
			if (record == null || !draft.matches(record)) {
				staleDraftIds.add(draft.draftId());
			} else if (draft.proposedPredicate().isEmpty()) {
				goalSpecRequests.add(draft);
			}
		}
		if (!staleDraftIds.isEmpty()) {
			if (mayPruneStaleDrafts) {
				staleDraftIds.forEach(manager::removeGoalDraft);
			} else if (!serverTasks.offer(BoundedServerTaskQueue.Lane.CONTROL,
					() -> staleDraftIds.forEach(manager::removeGoalDraft))) {
				LOGGER.debug("Stale goal draft cleanup deferred because the server task queue is full");
			}
		}
		return new HandshakeSnapshot(revision, visibleRecords, pendingWakes, goalSpecRequests);
	}

	private static void ensureHandshakeTimeRemaining(Session source) {
		if (source.handshakeRemainingNanos() <= 0L) throw handshakeTimeout();
	}

	private static BridgeProtocolException handshakeTimeout() {
		return new BridgeProtocolException(
				"AUTHENTICATION_TIMEOUT", "Coordinator handshake deadline expired while publishing the registry snapshot"
		);
	}

	private record HandshakeSnapshot(
			long registryRevision,
			List<AgentRecord> visibleRecords,
			List<PendingConversationWake> pendingWakes,
			List<PendingGoalDraft> goalSpecRequests
	) {
		private HandshakeSnapshot {
			visibleRecords = List.copyOf(visibleRecords);
			pendingWakes = List.copyOf(pendingWakes);
			goalSpecRequests = List.copyOf(goalSpecRequests);
		}
	}

	private void bumpRegistryPublicationRevision() {
		registryPublicationRevision.incrementAndGet();
		synchronized (publicationLock) {
			publicationLock.notifyAll();
		}
	}

	private void awaitHandshakeRetryLocked(Session source) {
		long remainingNanos = source.handshakeRemainingNanos();
		if (remainingNanos <= 0L) throw handshakeTimeout();
		try {
			publicationLock.wait(Math.max(1L, Math.min(HANDSHAKE_RETRY_WAIT_MS,
					TimeUnit.NANOSECONDS.toMillis(remainingNanos))));
		} catch (InterruptedException exception) {
			Thread.currentThread().interrupt();
			throw new BridgeProtocolException(
					"COORDINATOR_DISCONNECTED", "Bridge authentication was interrupted", exception
			);
		}
	}

	private void disconnectActiveAgents() {
		long now = System.currentTimeMillis();
		for (AgentRecord record : manager.records()) {
			if (!record.state().isActive()) continue;
			Optional<PendingConversationWake> wake = manager.pendingConversationWake(record.agentId());
			if (wake.isPresent() && wake.orElseThrow().matches(record)) {
				manager.rearmConversationWake(wake.orElseThrow());
				continue;
			}
			AgentTransition transition = manager.registry().disconnect(record.agentId(), now);
			AgentChatReporter.disconnected(manager, transition.after());
		}
	}

	static List<AgentControlModelOption> decodeCatalog(JsonObject payload) {
		try {
			requireKeys(payload, Set.of("refreshedAtEpochMs", "models"), "catalog_snapshot");
			requiredLong(payload, "refreshedAtEpochMs");
			JsonArray models = requiredArray(payload, "models", AgentControlModelOption.MAX_OPTIONS);
			ArrayList<AgentControlModelOption> decoded = new ArrayList<>(models.size());
			for (var element : models) {
				if (!element.isJsonObject()) {
					throw new BridgeProtocolException("INVALID_MODEL_CATALOG", "model must be an object");
				}
				JsonObject model = element.getAsJsonObject();
				requireKeys(model, Set.of("provider", "id", "model", "displayName", "reasoningEfforts", "serviceTiers"),
						"catalog model");
				decoded.add(new AgentControlModelOption(
						requiredStatusString(model, "provider"),
						requiredStatusString(model, "id"),
						requiredStatusString(model, "displayName"),
						stringList(model, "reasoningEfforts", 12),
						stringList(model, "serviceTiers", 8)
				));
			}
			return List.copyOf(decoded);
		} catch (BridgeProtocolException exception) {
			throw exception;
		} catch (RuntimeException exception) {
			throw new BridgeProtocolException("INVALID_MODEL_CATALOG", exception.getMessage(), exception);
		}
	}

	private void acceptCatalog(JsonObject payload) {
		List<AgentControlModelOption> decoded = decodeCatalog(payload);
		if (decoded.isEmpty()) {
			synchronized (publicationLock) {
				catalogProfiles = Set.of();
				catalogModels = AgentControlCatalog.fallbackOptions();
				catalogLoaded = false;
				catalogDiscoveryPending = false;
				if (catalogDiscoveryAttempts == 0) requestCatalogDiscovery();
				else catalogDiscoveryRetryAtNanos = catalogRetryDeadline(catalogDiscoveryAttempts);
			}
			return;
		}
		JsonArray models = payload.getAsJsonArray("models");
		HashSet<String> profiles = new HashSet<>();
		for (var element : models) {
			JsonObject model = element.getAsJsonObject();
			String provider = requiredStatusString(model, "provider");
			String id = requiredStatusString(model, "id");
			String wireModel = requiredStatusString(model, "model");
			JsonArray efforts = model.getAsJsonArray("reasoningEfforts");
			for (var effort : efforts) {
				String normalizedEffort = effort.getAsString().toLowerCase();
				profiles.add(provider + "\u0000" + id + "\u0000" + normalizedEffort);
				profiles.add(provider + "\u0000" + wireModel + "\u0000" + normalizedEffort);
			}
		}
		catalogProfiles = Set.copyOf(profiles);
		catalogModels = decoded;
		catalogLoaded = true;
		catalogDiscoveryPending = false;
		catalogDiscoveryAttempts = 0;
		catalogDiscoveryRetryAtNanos = 0L;
		catalogDiscoveryGeneration = 0L;
		catalogDiscoveryFailureCode = null;
	}

	private void publishCatalogDiscoveryRetry() {
		synchronized (publicationLock) {
			if (catalogLoaded || catalogDiscoveryAttempts == 0) return;
			if (catalogDiscoveryGeneration != coordinatorLifecycleGeneration || session == null || !session.authenticated.get()) return;
			if (!catalogRetryDue(nanoTime.getAsLong(), catalogDiscoveryRetryAtNanos)) return;
			catalogDiscoveryPending = false;
			requestCatalogDiscovery();
		}
	}

	private void requestCatalogDiscovery() {
		if (catalogLoaded || catalogDiscoveryPending) return;
		if (catalogDiscoveryAttempts < Integer.MAX_VALUE) catalogDiscoveryAttempts += 1;
		catalogDiscoveryGeneration = coordinatorLifecycleGeneration;
		catalogDiscoveryRetryAtNanos = catalogRetryDeadline(catalogDiscoveryAttempts);
		try {
			send("catalog_request", "server", new JsonObject());
			catalogDiscoveryPending = true;
			if (catalogDiscoveryFailureCode != null) {
				LOGGER.debug("Catalog discovery publication recovered after {}", catalogDiscoveryFailureCode);
				catalogDiscoveryFailureCode = null;
			}
		} catch (RuntimeException exception) {
			catalogDiscoveryPending = false;
			String failureCode = catalogPublicationFailureCode(exception);
			if (!failureCode.equals(catalogDiscoveryFailureCode)) {
				LOGGER.debug("Catalog discovery publication unavailable ({}); retry remains scheduled", failureCode);
				catalogDiscoveryFailureCode = failureCode;
			}
		}
	}

	static long catalogRetryDelayNanos(int completedAttempts) {
		return CATALOG_DISCOVERY_RETRY_BASE_NANOS
				<< Math.min(CATALOG_DISCOVERY_MAX_BACKOFF_SHIFT, Math.max(0, completedAttempts - 1));
	}

	private long catalogRetryDeadline(int completedAttempts) {
		return nanoTime.getAsLong() + catalogRetryDelayNanos(completedAttempts);
	}

	static boolean catalogRetryDue(long now, long deadline) {
		return now - deadline >= 0L;
	}

	private static String catalogPublicationFailureCode(RuntimeException exception) {
		String code = exception instanceof BridgeProtocolException protocol ? protocol.code() : exception.getClass().getSimpleName();
		if (code == null || code.isBlank()) return "RUNTIME_FAILURE";
		return code.length() <= 64 ? code : code.substring(0, 64);
	}

	private void plannerReady(BridgeEnvelope envelope) {
		AgentId id = AgentId.parse(envelope.agentId());
		long revision = requiredLong(envelope.payload(), "goalRevision");
		AgentRecord record;
		try {
			record = manager.registry().require(id);
		} catch (AgentDomainException exception) {
			if ("AGENT_NOT_FOUND".equals(exception.code())) return;
			throw exception;
		}
		if (revision != record.goalRevision()) {
			if (revision < record.goalRevision()) return;
			throw new AgentDomainException("STALE_REVISION", "Coordinator planning revision is stale");
		}
		boolean agentReady = "agent_ready".equals(envelope.type());
		if (agentReady) coordinatorReadyAgentIds.add(id);
		boolean recoveryReady = agentReady
				&& envelope.payload().has("reconciled")
				&& requiredBoolean(envelope.payload(), "reconciled");
		if (recoveryReady && record.state() == AgentLifecycleState.DISCONNECTED
				&& record.currentGoal().isPresent()) {
			manager.registry().rearmAfterCoordinatorRecovery(id.value(), revision, System.currentTimeMillis());
		}
		if (!recoveryReady && record.state() == AgentLifecycleState.STARTING) {
			router.plannerStarted(id);
		}
		if (agentReady && shouldRequestReadyObservation(id, revision, recoveryReady)) {
			observationPublication.markAttention(id);
			queueUrgentObservation(id);
		}
	}

	private boolean shouldRequestReadyObservation(AgentId agentId, long goalRevision, boolean recoveryReady) {
		if (!recoveryReady) return true;
		RecoveryObservationIdentity identity = new RecoveryObservationIdentity(
				coordinatorLifecycleGeneration, goalRevision
		);
		return !identity.equals(recoveryObservationIdentities.put(agentId, identity));
	}

	private void acceptAction(BridgeEnvelope envelope) {
		boolean durableAccepted = false;
		try {
			if (dev.fork.integration.ForkEntrypoint.active(manager.server())) throw new AgentDomainException("FORK_ACTION_FENCED", "FORK permits only complete scored batches; ordinary Arena actions are disabled");
			ServerActionRequest request = decodeActionRequest(envelope);
			AgentRecord record = validateActionProvenance(request);
			if (isDetachedConversationReply(record, request)) {
				actionExecutor.submitConversationReply(request);
			} else {
				actionJournal.accept(request, logicalGoalId(record));
				durableAccepted = true;
				try {
					programActions.accept(request);
					actionExecutor.submitProgramPrimitive(request);
				} catch (RuntimeException exception) {
					if (actionJournal.rollbackAccepted(request)) {
						durableAccepted = false;
						programActions.rollback(request);
					}
					throw exception;
				}
			}
		} catch (BridgeProtocolException | AgentDomainException exception) {
			if (durableAccepted) throw exception;
			if (sendRejectedAction(envelope, exception)) return;
			throw exception;
		}
	}

	private void acceptActionCancel(BridgeEnvelope envelope) {
		AgentId agentId = AgentId.parse(envelope.agentId());
		JsonObject payload = envelope.payload();
		requireKeys(payload, Set.of("goalRevision", "actionId"), "action_cancel");
		long goalRevision = requiredLong(payload, "goalRevision");
		String actionId = requiredString(payload, "actionId");
		if (!actionExecutor.cancel(agentId, goalRevision, actionId, "Cancelled by explicit model decision")) {
			throw new AgentDomainException("ACTION_NOT_ACTIVE", "The referenced action is no longer active");
		}
	}

	static ServerActionRequest decodeActionRequest(BridgeEnvelope envelope) {
		JsonObject payload = envelope.payload();
		requireActionCommandKeys(payload);
		AgentId agentId = AgentId.parse(envelope.agentId());
		JsonObject arguments = requiredObject(payload, "arguments");
		String type = requiredString(payload, "actionType");
		ActionType actionType = ActionType.fromWireName(type).orElseThrow(
				() -> new BridgeProtocolException("UNKNOWN_ACTION", "Unknown action type '" + type + "'")
		);
		if (!ServerActionExecutor.isArenaScriptPrimitive(actionType)) {
			throw new BridgeProtocolException("UNSUPPORTED_ARENA_SCRIPT_ACTION", "ArenaScript cannot invoke '" + type + "'");
		}
		try {
			JsonObject validatedArguments = ProtocolCodec.validateActionArguments(actionType, arguments);
			String traceId = requiredTraceId(payload, "traceId");
			ActionProvenance provenance = decodeActionProvenance(payload);
			if (provenance.traceId() == null || !provenance.traceId().equals(traceId)) {
				throw new BridgeProtocolException("INVALID_TRACE_ID", "provenance.traceId must match traceId");
			}
			return new ServerActionRequest(agentId, requiredLong(payload, "goalRevision"), requiredString(payload, "actionId"), actionType, validatedArguments, provenance, traceId);
		} catch (ProtocolException exception) {
			throw new BridgeProtocolException(exception.code(), exception.getMessage(), exception);
		} catch (IllegalArgumentException exception) {
			throw new BridgeProtocolException("INVALID_ACTION_REQUEST", exception.getMessage(), exception);
		}
	}

	private static void requireActionCommandKeys(JsonObject payload) {
		Set<String> expected = Set.of("traceId", "goalRevision", "actionId", "actionType", "arguments", "provenance");
		for (String field : Set.of("goalRevision", "actionId", "actionType", "arguments", "provenance")) if (!payload.has(field)) throw new BridgeProtocolException("MISSING_FIELD", field);
		for (String field : payload.keySet()) if (!expected.contains(field)) throw new BridgeProtocolException("INVALID_FIELD", "action_command");
	}

	private boolean sendRejectedAction(BridgeEnvelope envelope, RuntimeException exception) {
		JsonObject payload = envelope.payload();
		RejectionIdentity identity = rejectionIdentity(envelope);
		if (identity == null) return false;
		JsonObject result = new JsonObject();
		result.addProperty("goalRevision", identity.goalRevision());
		result.addProperty("actionId", identity.actionId());
		result.addProperty("commandId", identity.actionId());
		result.addProperty("actionType", identity.actionType());
		result.addProperty("traceId", identity.traceId());
		result.addProperty("state", "FAILED");
		result.addProperty("reasonCode", exception instanceof BridgeProtocolException protocol ? protocol.code() : ((AgentDomainException) exception).code());
		result.addProperty("message", boundedRejectionMessage(exception.getMessage()));
		result.addProperty("elapsedMs", 0L);
		result.addProperty("observedAtEpochMs", System.currentTimeMillis());
		result.addProperty("executionStarted", false);
		result.addProperty("physicalAttempted", false);
		send("action_result", identity.agentId(), result);
		reportVerbose(AgentId.parse(identity.agentId()), "error",
				"Action rejected: " + boundedRejectionMessage(exception.getMessage()));
		return true;
	}

	private static RejectionIdentity rejectionIdentity(BridgeEnvelope envelope) {
		JsonObject payload = envelope.payload();
		try {
			AgentId.parse(envelope.agentId());
			return new RejectionIdentity(envelope.agentId(), requiredLong(payload, "goalRevision"), requiredString(payload, "actionId"), requiredString(payload, "actionType"), requiredTraceId(payload, "traceId"));
		} catch (RuntimeException exception) {
			return null;
		}
	}

	private record RejectionIdentity(String agentId, long goalRevision, String actionId, String actionType, String traceId) { }
	private record RecoveryObservationIdentity(long coordinatorLifecycleGeneration, long goalRevision) { }

	static String boundedRejectionMessage(String message) {
		String fallback = "Action rejected";
		if (message == null || message.isBlank()) return fallback;
		int end = Math.min(message.length(), ProtocolConstants.MAX_RESULT_MESSAGE_LENGTH);
		if (end < message.length() && end > 0 && Character.isHighSurrogate(message.charAt(end - 1))
				&& Character.isLowSurrogate(message.charAt(end))) end -= 1;
		String bounded = message.substring(0, end);
		return bounded.isBlank() ? fallback : bounded;
	}

	private AgentRecord validateActionProvenance(ServerActionRequest request) {
		AgentRecord record = manager.registry().require(request.agentId());
		boolean respawn = request.type() == ActionType.RESPAWN;
		if ((respawn && (record.state() != AgentLifecycleState.DEAD || request.goalRevision() != record.goalRevision()))
				|| (!respawn && !acceptsActionRevision(record, request))) {
			throw new AgentDomainException("STALE_REVISION", "Coordinator action revision is stale");
		}
		ActionProvenance provenance = request.provenance();
		AgentProfile profile = record.profile();
		if (!profile.provider().equals(provenance.provider()) || !profile.model().equals(provenance.model())
				|| !profile.reasoning().equals(provenance.reasoningEffort()) || !profile.serviceTier().equals(provenance.serviceTier())) {
			throw new AgentDomainException("STALE_PROVENANCE", "Action provenance does not match the selected model profile");
		}
		if (request.type() == ActionType.ATTACK || request.type() == ActionType.USE_RANGED
				|| request.type() == ActionType.INTERACT_ENTITY) {
		observationPublication.requireObservedTarget(
					request.agentId(),
					provenance.eventSequence(),
					request.arguments().get("targetId").getAsString()
			);
		}
		if (request.type() == ActionType.CHAT
				&& ConversationAudience.parse(nullableString(request.arguments(), "audience")) == ConversationAudience.DIRECT) {
			observationPublication.requireDirectMessageRecipient(
					request.agentId(),
					provenance.eventSequence(),
					requiredString(request.arguments(), "recipientId")
			);
		}
		return record;
	}

	static boolean acceptsActionRevision(AgentRecord record, ServerActionRequest request) {
		if (record.acceptsRevision(request.goalRevision())) return true;
		return isDetachedConversationReply(record, request);
	}

	static boolean isDetachedConversationReply(AgentRecord record, ServerActionRequest request) {
		if (request.goalRevision() != record.goalRevision() || request.type() != ActionType.CHAT) return false;
		if (record.state() != AgentLifecycleState.IDLE
				&& record.state() != AgentLifecycleState.PAUSED
				&& record.state() != AgentLifecycleState.COMPLETED) return false;
		ConversationAudience audience = ConversationAudience.parse(nullableString(request.arguments(), "audience"));
		return audience == ConversationAudience.DIRECT || audience == ConversationAudience.PROXIMITY;
	}

	private static ActionProvenance decodeActionProvenance(JsonObject payload) {
		JsonObject provenance = requiredObject(payload, "provenance");
		Set<String> expected = Set.of(
				"provider", "model", "reasoningEffort", "serviceTier", "programId", "programVersion", "sourceStepId", "eventSequence", "traceId", "watcherId"
		);
		for (String field : provenance.keySet()) if (!expected.contains(field)) throw new BridgeProtocolException("INVALID_FIELD", "provenance");
		for (String field : Set.of("provider", "model", "reasoningEffort", "serviceTier", "programId", "programVersion", "sourceStepId", "eventSequence")) {
			if (!provenance.has(field)) throw new BridgeProtocolException("MISSING_FIELD", "provenance." + field);
		}
		if (provenance.has("watcherId") && !provenance.has("traceId")) {
			throw new BridgeProtocolException("MISSING_FIELD", "provenance.traceId");
		}
		try {
			return new ActionProvenance(
					requiredProvenanceString(provenance, "provider"),
					requiredProvenanceString(provenance, "model"),
					requiredProvenanceString(provenance, "reasoningEffort"),
					requiredProvenanceString(provenance, "serviceTier"),
					requiredProvenanceString(provenance, "programId"),
					requiredSafeLong(provenance, "programVersion"),
					requiredProvenanceString(provenance, "sourceStepId"),
					requiredSafeLong(provenance, "eventSequence"),
					provenance.has("traceId") ? requiredTraceId(provenance, "traceId") : null,
					provenance.has("watcherId") ? requiredProvenanceString(provenance, "watcherId") : null
			);
		} catch (IllegalArgumentException exception) {
			throw new BridgeProtocolException("INVALID_PROVENANCE", exception.getMessage(), exception);
		}
	}

	private void sendActionResult(ServerActionResult result) {
		actionJournal.terminalIfAccepted(result);
		programActions.terminal(result);
		observations.invalidate(result.agentId());
		ScenarioRuntimeService.onAgentAction(
				manager.server(),
				result.agentId().toString(),
				result.actionType().wireName(),
				result.state() == dev.agaminggod.arenaagents.server.runtime.ServerActionState.SUCCEEDED
						&& !"TARGET_ALREADY_SATISFIED".equals(result.reasonCode())
		);
		synchronized (publicationLock) {
			terminalResults.retain(result);
		}
		reportVerbose(result.agentId(), AgentActivityPresentation.verboseResultStage(result), verboseResult(result));
		verboseState.finishAction(result);
		observationPublication.markAttention(result.agentId());
		queueUrgentObservation(result.agentId());
	}

	void publishConversationEvent(ConversationEvent event, Optional<dev.agaminggod.arenaagents.agent.goal.GoalSpec> wakeGoal) {
		Objects.requireNonNull(event, "event must not be null");
		Optional<dev.agaminggod.arenaagents.agent.goal.GoalSpec> checkedWakeGoal = Objects.requireNonNull(wakeGoal, "wakeGoal must not be null");
		try {
			if (checkedWakeGoal.isEmpty()) {
				synchronized (publicationLock) {
					Session active = requireConversationSession(event.agentId());
					active.enqueue(new BridgeEnvelope(
							2, serverInstanceId, event.agentId().toString(), "conversation_event",
							"server-" + messageIds.incrementAndGet(), conversationEventPayload(event)
					));
				}
			} else {
				AgentTransition transition = manager.startConversationWakeAtomically(
						event, checkedWakeGoal.orElseThrow(),
						(wake, commit) -> {
							synchronized (publicationLock) {
								Session active = requireConversationSession(event.agentId());
								active.enqueueAtomically(conversationWakeEnvelope(wake), commit);
							}
						}
				);
				publishConversationWakeScenarioState(transition);
			}
		} catch (BridgeProtocolException exception) {
			throw new AgentDomainException(exception.code(), "Agent conversation delivery failed: " + exception.getMessage());
		}
		conversationPublished(event);
	}

	void publishGoalSpecRequest(PendingGoalDraft draft) {
		Objects.requireNonNull(draft, "draft must not be null");
		// A draft staged after handshake capture must either invalidate that snapshot or use the live session.
		bumpRegistryPublicationRevision();
		synchronized (publicationLock) {
			Session active = session;
			if (active == null || !active.open.get() || !active.authenticated.get()
					|| !protocolKnownAgentIds.contains(draft.agentId())) return;
			active.enqueue(goalSpecRequestEnvelope(draft));
		}
	}

	private BridgeEnvelope goalSpecRequestEnvelope(PendingGoalDraft draft) {
		JsonObject payload = new JsonObject();
		payload.addProperty("requestId", draft.draftId().toString());
		payload.addProperty("originalRequest", draft.originalRequest());
		JsonArray candidates = new JsonArray();
		draft.candidateIds().forEach(candidates::add);
		payload.add("candidateIds", candidates);
		return new BridgeEnvelope(
				2, serverInstanceId, draft.agentId().toString(), "goal_spec_request",
				"server-" + messageIds.incrementAndGet(), payload
		);
	}

	private record VerboseControlSnapshot(long revision, boolean enabled) {
	}

	private Session requireConversationSession(AgentId agentId) {
		Session active = session;
		if (active == null || !active.open.get() || !active.authenticated.get()) {
			throw new AgentDomainException("COORDINATOR_DISCONNECTED", COORDINATOR_OFFLINE_MESSAGE);
		}
		if (!protocolKnownAgentIds.contains(agentId)) {
			throw new AgentDomainException(
					"AGENT_NOT_READY", "AI agent is still registering with the coordinator; retry shortly"
			);
		}
		if (!coordinatorReadyAgentIds.contains(agentId)) {
			throw new AgentDomainException(
					"AGENT_NOT_READY", "AI agent is waiting for coordinator readiness; retry shortly"
			);
		}
		return active;
	}

	private static JsonObject conversationEventPayload(ConversationEvent event) {
		JsonObject payload = new JsonObject();
		payload.addProperty("sequence", event.sequence());
		payload.addProperty("kind", event.kind().wireName());
		payload.addProperty("sourceId", event.sourceId());
		payload.addProperty("recipientId", event.recipientId());
		payload.addProperty("scope", event.audience().wireName());
		payload.addProperty("text", event.text());
		payload.addProperty("goalRevision", event.goalRevision());
		payload.addProperty("observedAtEpochMs", event.observedAtEpochMs());
		return payload;
	}

	private BridgeEnvelope conversationWakeEnvelope(PendingConversationWake wake) {
		return new BridgeEnvelope(
				2, serverInstanceId, wake.event().agentId().toString(), "conversation_wake",
				"server-" + messageIds.incrementAndGet(), conversationWakePayload(wake)
		);
	}

	private static JsonObject conversationWakePayload(PendingConversationWake wake) {
		JsonObject payload = new JsonObject();
		payload.addProperty("transactionId", wake.transactionId().toString());
		payload.add("event", conversationEventPayload(wake.event()));
		JsonObject control = new JsonObject();
		control.addProperty("operation", "start");
		control.addProperty("goalRevision", wake.goalRevision());
		control.addProperty("updatedAtEpochMs", wake.updatedAtEpochMs());
		control.addProperty("goal", plannerGoal(wake.goal()));
		control.add("goalSpec", GOAL_SPEC_WIRE_CODEC.encodeSpec(wake.goal().spec()));
		payload.add("control", control);
		return payload;
	}

	private void conversationPublished(ConversationEvent event) {
		if (event.kind() == ConversationKind.PLAYER_MESSAGE) {
			observationPublication.retainConversationSource(event.agentId(), event.sourceId());
		}
		observationPublication.markAttention(event.agentId());
		queueUrgentObservation(event.agentId());
	}

	private void publishConversationWakeScenarioState(AgentTransition transition) {
		try {
			ScenarioRuntimeService.onAgentState(
					manager.server(), transition.after().agentId().toString(), publicState(transition.after().state())
			);
		} catch (RuntimeException exception) {
			LOGGER.warn("Conversation wake scenario telemetry failed after committed publication", exception);
		}
	}

	private void sendRespawnResultBeforeControl(ServerActionResult result, AgentTransition transition, Runnable commit) {
		synchronized (publicationLock) {
			Session active = session;
			if (active == null || !active.open.get() || !active.authenticated.get()) {
				throw new BridgeProtocolException("COORDINATOR_DISCONNECTED", "Respawn result has no authenticated coordinator");
			}
			BridgeEnvelope resultEnvelope = new BridgeEnvelope(2, serverInstanceId, result.agentId().toString(), "action_result",
					"server-" + messageIds.incrementAndGet(), actionResultPayload(result, active));
			BridgeEnvelope controlEnvelope = new BridgeEnvelope(2, serverInstanceId, transition.after().agentId().toString(), "goal_control",
					"server-" + messageIds.incrementAndGet(), goalControlPayload(transition, "respawn"));
			terminalResults.retain(result);
			try {
				if (!terminalResults.claim(result, active)) throw new BridgeProtocolException("ACTION_RESULT_REPLAY_CONFLICT", "Respawn result is already queued");
				publishRespawnScenarioEvents(
						() -> active.enqueuePair(resultEnvelope, controlEnvelope,
								() -> commitRespawnTerminal(commit, () -> actionJournal.terminal(result))),
						() -> ScenarioRuntimeService.onAgentAction(manager.server(), result.agentId().toString(), result.actionType().wireName(), true),
						() -> ScenarioRuntimeService.onAgentState(manager.server(), transition.after().agentId().toString(), publicState(transition.after().state()))
				);
			} catch (RuntimeException exception) {
				terminalResults.discard(result, active);
				throw exception;
			}
		}
		programActions.terminal(result);
		observations.invalidate(result.agentId());
		reportVerbose(result.agentId(), AgentActivityPresentation.verboseResultStage(result), verboseResult(result));
		verboseState.finishAction(result);
		observationPublication.markAttention(result.agentId());
		queueUrgentObservation(result.agentId());
	}

	static void publishRespawnScenarioEvents(Runnable publication, Runnable actionEvent, Runnable stateEvent) {
		publication.run();
		try {
			actionEvent.run();
		} catch (RuntimeException exception) {
			LOGGER.warn("Respawn action scenario telemetry failed after committed publication", exception);
		}
		try {
			stateEvent.run();
		} catch (RuntimeException exception) {
			LOGGER.warn("Respawn state scenario telemetry failed after committed publication", exception);
		}
	}

	private JsonObject actionResultPayload(ServerActionResult result, Session target) {
		JsonObject payload = new JsonObject();
		payload.addProperty("goalRevision", result.goalRevision());
		payload.addProperty("actionId", result.actionId());
		payload.addProperty("commandId", result.actionId());
		payload.addProperty("actionType", result.actionType().wireName());
		if (result.traceId() != null) payload.addProperty("traceId", result.traceId());
		payload.addProperty("state", result.state().name());
		payload.addProperty("reasonCode", result.reasonCode());
		payload.addProperty("message", result.message());
		payload.addProperty("elapsedMs", result.elapsedMs());
		payload.addProperty("observedAtEpochMs", result.observedAtEpochMs());
		payload.addProperty("executionStarted", result.executionStarted());
		payload.addProperty("physicalAttempted", result.physicalAttempted());
		if (result.actionObservation() != null) payload.add("actionObservation", actionObservationPayload(result.actionObservation()));
		payload.addProperty("replayProof", actionResultReplayProof(
				secret, target.clientNonce, target.serverNonce, serverInstanceId,
				result.agentId().toString(), result.goalRevision(), result.actionId()
		));
		return payload;
	}

	private BridgeEnvelope actionResultEnvelope(ServerActionResult result, Session target) {
		return new BridgeEnvelope(2, serverInstanceId, result.agentId().toString(), "action_result",
				"server-" + messageIds.incrementAndGet(), actionResultPayload(result, target));
	}

	private static String verboseResult(ServerActionResult result) {
		return AgentVerboseChat.sanitizeMessage(AgentActivityPresentation.verboseResult(result));
	}

	private static JsonObject goalControlPayload(AgentTransition transition, String operation) {
		JsonObject payload = new JsonObject();
		payload.addProperty("operation", operation);
		payload.addProperty("goalRevision", transition.after().goalRevision());
		payload.addProperty("updatedAtEpochMs", transition.after().updatedAtEpochMs());
		if ("queue".equals(operation)) {
			List<AgentGoal> queue = transition.after().queuedGoals();
			AgentGoal goal = queue.get(queue.size() - 1);
			payload.addProperty("goal", goal.prompt());
			payload.add("goalSpec", GOAL_SPEC_WIRE_CODEC.encodeSpec(goal.spec()));
		} else if ("dequeue".equals(operation)) {
			AgentGoal goal = transition.before().queuedGoals().getFirst();
			payload.addProperty("goal", goal.prompt());
			payload.add("goalSpec", GOAL_SPEC_WIRE_CODEC.encodeSpec(goal.spec()));
		} else if ("start".equals(operation) || "replace".equals(operation) || "steer".equals(operation)) {
			transition.after().currentGoal().ifPresent(goal -> {
				payload.addProperty("goal", plannerGoal(goal));
				payload.add("goalSpec", GOAL_SPEC_WIRE_CODEC.encodeSpec(goal.spec()));
			});
		}
		if ("respawn".equals(operation) && transition.after().state() == AgentLifecycleState.STARTING) {
			payload.addProperty("resumeGoal", true);
		}
		if ("dead".equals(operation)) transition.after().deathSnapshot().ifPresent(death -> payload.add("death", deathFacts(death)));
		return payload;
	}

	static JsonObject deathFacts(dev.agaminggod.arenaagents.agent.AgentDeathSnapshot death) {
		JsonObject facts = new JsonObject();
		facts.addProperty("cause", death.cause());
		facts.addProperty("dimensionId", death.dimensionId());
		facts.addProperty("x", death.x());
		facts.addProperty("y", death.y());
		facts.addProperty("z", death.z());
		death.respawnDimensionId().ifPresentOrElse(
				value -> facts.addProperty("respawnDimensionId", value),
				() -> facts.add("respawnDimensionId", JsonNull.INSTANCE)
		);
		death.respawnX().ifPresentOrElse(
				value -> facts.addProperty("respawnX", value),
				() -> facts.add("respawnX", JsonNull.INSTANCE)
		);
		death.respawnY().ifPresentOrElse(
				value -> facts.addProperty("respawnY", value),
				() -> facts.add("respawnY", JsonNull.INSTANCE)
		);
		death.respawnZ().ifPresentOrElse(
				value -> facts.addProperty("respawnZ", value),
				() -> facts.add("respawnZ", JsonNull.INSTANCE)
		);
		death.respawnYaw().ifPresentOrElse(
				value -> facts.addProperty("respawnYaw", value),
				() -> facts.add("respawnYaw", JsonNull.INSTANCE)
		);
		death.respawnPitch().ifPresentOrElse(
				value -> facts.addProperty("respawnPitch", value),
				() -> facts.add("respawnPitch", JsonNull.INSTANCE)
		);
		death.respawnForced().ifPresentOrElse(
				value -> facts.addProperty("respawnForced", value),
				() -> facts.add("respawnForced", JsonNull.INSTANCE)
		);
		facts.addProperty("gameMode", death.gameMode());
		facts.addProperty("diedAtEpochMs", death.diedAtEpochMs());
		return facts;
	}

	private void sendActionProgress(ServerActionProgress progress) {
		JsonObject payload = new JsonObject();
		payload.addProperty("goalRevision", progress.goalRevision());
		payload.addProperty("actionId", progress.actionId());
		payload.addProperty("commandId", progress.actionId());
		payload.addProperty("actionType", progress.actionType().wireName());
		if (progress.traceId() != null) payload.addProperty("traceId", progress.traceId());
		payload.addProperty("state", "RUNNING");
		payload.addProperty("progress", progress.progress());
		payload.addProperty("elapsedMs", progress.elapsedMs());
		payload.addProperty("observedAtEpochMs", progress.observedAtEpochMs());
		if (progress.actionObservation() != null) payload.add("actionObservation", actionObservationPayload(progress.actionObservation()));
		send("action_progress", progress.agentId().toString(), payload);
		if (progress.actionObservation() == null || progress.actionObservation().progress() == null
				|| progress.actionObservation().progress().verified()) {
			verboseState.progressMilestone(progress).ifPresent(milestone -> {
				String message = progress.actionObservation() == null
						? AgentActivityPresentation.progress(progress.actionType(), milestone)
						: actionObservationProgressMessage(progress.actionObservation(), milestone);
				reportVerbose(progress.agentId(), "progress", message);
			});
		}
		queueObservation(progress.agentId());
	}

	private static JsonObject actionObservationPayload(ServerActionObservation observation) {
		JsonObject payload = new JsonObject();
		if (observation.worldTick() != null) payload.addProperty("worldTick", observation.worldTick());
		payload.addProperty("observedAtEpochMs", observation.observedAtEpochMs());
		addPosition(payload, "position", observation.position());
		addPosition(payload, "velocity", observation.velocity());
		payload.addProperty("yaw", observation.yaw());
		payload.addProperty("pitch", observation.pitch());
		if (observation.collision() != null) {
			JsonObject collision = new JsonObject();
			collision.addProperty("horizontal", observation.collision().horizontal());
			collision.addProperty("vertical", observation.collision().vertical());
			collision.addProperty("inWall", observation.collision().inWall());
			payload.add("collision", collision);
		}
		if (observation.lookedAt() != null) {
			ServerActionObservation.RayTarget lookedAt = observation.lookedAt();
			JsonObject look = new JsonObject();
			look.addProperty("type", lookedAt.type());
			addPosition(look, "position", lookedAt.position());
			if (lookedAt.id() != null) look.addProperty("id", lookedAt.id());
			if (lookedAt.face() != null) look.addProperty("face", lookedAt.face());
			look.addProperty("hitDistance", lookedAt.hitDistance());
			payload.add("lookedAt", look);
		}
		if (observation.reach() != null) {
			ServerActionObservation.Reach reach = observation.reach();
			JsonObject value = new JsonObject();
			value.addProperty("distance", reach.distance());
			value.addProperty("max", reach.max());
			value.addProperty("within", reach.within());
			payload.add("reach", value);
		}
		if (observation.target() != null) {
			ServerActionObservation.Target target = observation.target();
			JsonObject value = new JsonObject();
			value.addProperty("kind", target.kind());
			addPosition(value, "position", target.position());
			if (target.expectedId() != null) value.addProperty("expectedId", target.expectedId());
			if (target.currentId() != null) value.addProperty("currentId", target.currentId());
			if (target.beforeId() != null) value.addProperty("beforeId", target.beforeId());
			if (target.afterId() != null) value.addProperty("afterId", target.afterId());
			if (target.worldChanged() != null) value.addProperty("worldChanged", target.worldChanged());
			if (target.distanceRemaining() != null) value.addProperty("distanceRemaining", target.distanceRemaining());
			if (target.tolerance() != null) value.addProperty("tolerance", target.tolerance());
			if (target.standable() != null) value.addProperty("standable", target.standable());
			payload.add("target", value);
		}
		if (observation.progress() != null) {
			ServerActionObservation.Progress progress = observation.progress();
			JsonObject value = new JsonObject();
			value.addProperty("value", progress.value());
			value.addProperty("basis", progress.basis());
			value.addProperty("verified", progress.verified());
			payload.add("progress", value);
		}
		return payload;
	}

	private static void addPosition(JsonObject parent, String name, ServerActionObservation.Position position) {
		if (position == null) return;
		JsonObject value = new JsonObject();
		value.addProperty("x", position.x());
		value.addProperty("y", position.y());
		value.addProperty("z", position.z());
		parent.add(name, value);
	}

	private static String actionObservationProgressMessage(ServerActionObservation observation, int milestone) {
		StringBuilder message = new StringBuilder("Verified ").append(milestone).append("% progress");
		if (observation.progress() != null) message.append(" (basis=").append(observation.progress().basis()).append(')');
		if (observation.lookedAt() != null && observation.lookedAt().id() != null) {
			message.append("; looking at ").append(observation.lookedAt().id());
		}
		if (observation.reach() != null) {
			message.append("; reach=").append(formatDecimal(observation.reach().distance()))
					.append('/').append(formatDecimal(observation.reach().max()));
		}
		if (observation.target() != null) {
			ServerActionObservation.Target target = observation.target();
			if (target.kind().equals("block") && target.position() != null) {
				message.append("; target=").append(formatPosition(target.position()));
			}
			if (target.distanceRemaining() != null) {
				message.append("; remaining=").append(formatDecimal(target.distanceRemaining()));
			}
			if (target.tolerance() != null) message.append("; tolerance=").append(formatDecimal(target.tolerance()));
		}
		if (observation.collision() != null && (observation.collision().horizontal() || observation.collision().inWall())) {
			message.append("; collision=true");
		}
		return message.toString();
	}

	private static String formatPosition(ServerActionObservation.Position position) {
		return '(' + formatDecimal(position.x()) + ',' + formatDecimal(position.y()) + ',' + formatDecimal(position.z()) + ')';
	}

	private static String formatDecimal(double value) {
		return String.format(java.util.Locale.ROOT, "%.2f", value);
	}

	private void reportVerbose(AgentId agentId, String stage, String message) {
		try {
			AgentRecord record = manager.registry().require(agentId);
			AgentVerboseChat.report(manager, verboseState, record, stage, message);
		} catch (RuntimeException exception) {
			LOGGER.debug("Verbose {} event was unavailable for {}: {}", stage, agentId, exception.getMessage());
		}
	}

	private void queueObservation(AgentId agentId) {
		if (!manager.isCoordinatorVisible(agentId)) return;
		if (!observationPublication.offer(agentId)) {
			LOGGER.debug("Observation request coalesced or deferred for {}", agentId);
		}
	}

	private void queueUrgentObservation(AgentId agentId) {
		if (!manager.isCoordinatorVisible(agentId)) return;
		if (!observationPublication.offerUrgent(agentId)) {
			LOGGER.debug("Urgent observation request deferred for {}", agentId);
		}
	}

	private void sendObservation(AgentId agentId) {
		if (!manager.isCoordinatorVisible(agentId)) return;
		if (manager.server() == null) return;
		Session source = session;
		if (source == null || !source.authenticated.get()) {
			return;
		}
		boolean heartbeat = observationPublication.takeHeartbeat(agentId);
		final JsonObject observation;
		try {
			observation = observations.collect(agentId);
			ObservationPublication.Result result = publishObservationWithInputGuard(
					observationPublication,
					AgentInputRuntime.existingController(manager.server()),
					agentId,
					source,
					observation,
					(ignoredAgent, payload) -> sendObservationEnvelope(source, ignoredAgent, payload),
					heartbeat
			);
			if (result == ObservationPublication.Result.DELIVERY_RETRY) retryObservation(agentId, heartbeat);
			return;
		} catch (AgentDomainException exception) {
			LOGGER.debug("Dropping observation for removed agent {}: {}", agentId, exception.code());
		} catch (BridgeProtocolException exception) {
			if (isTransientObservationDelivery(exception)) retryObservation(agentId, heartbeat);
			else LOGGER.warn("Dropping observation delivery for {}: {}", agentId, exception.getMessage());
		} catch (RuntimeException exception) {
			LOGGER.warn("Could not collect observation for {}: {}", agentId, exception.getMessage());
		}
	}

	private void retryObservation(AgentId agentId) {
		retryObservation(agentId, false);
	}

	private void retryObservation(AgentId agentId, boolean heartbeat) {
		try {
			manager.registry().require(agentId);
		} catch (AgentDomainException exception) {
			return;
		}
		if (!observationPublication.markDirty(agentId)) return;
		try {
			if (heartbeat) observationPublication.offerHeartbeat(agentId);
			else observationPublication.offer(agentId);
		} catch (RuntimeException exception) {
			LOGGER.debug("Could not retain observation retry for {}: {}", agentId, exception.getMessage());
		}
	}

	private void resetObservationPublication() {
		observationPublication.reset();
	}

	private static boolean isTransientObservationDelivery(BridgeProtocolException exception) {
		return "AGENT_BACKPRESSURE".equals(exception.code()) || "CONNECTION_BACKPRESSURE".equals(exception.code());
	}

	private static ScenarioAgentEvent.PublicState publicState(AgentLifecycleState state) {
		return switch (state) {
			case IDLE -> ScenarioAgentEvent.PublicState.IDLE;
			case STARTING, PLANNING -> ScenarioAgentEvent.PublicState.THINKING;
			case ACTING -> ScenarioAgentEvent.PublicState.ACTING;
			case PAUSED, DISCONNECTED -> ScenarioAgentEvent.PublicState.RECOVERING;
			case COMPLETED -> ScenarioAgentEvent.PublicState.DONE;
			case ERROR -> ScenarioAgentEvent.PublicState.FAILED;
			case DEAD -> ScenarioAgentEvent.PublicState.DEAD;
		};
	}

	/** Replays retained terminal results without ever re-entering physical action execution. */
	private void replayPendingTerminalResults() {
		synchronized (publicationLock) {
			Session active = session;
			if (active == null || !active.open.get() || !active.authenticated.get()) return;
			for (ServerActionResult result : terminalResults.pending()) {
				if (!enqueueTerminalResult(active, result)) break;
			}
		}
	}

	private boolean enqueueTerminalResult(Session target, ServerActionResult result) {
		if (!terminalResults.claim(result, target)) return true;
		try {
			target.enqueue(actionResultEnvelope(result, target));
			return true;
		} catch (RuntimeException exception) {
			terminalResults.release(result, target);
			LOGGER.debug("Terminal action result will retry after coordinator reconnect: {}", exception.getMessage());
			return false;
		}
	}

	public boolean sendFork(String type, JsonObject payload) {
		if (!Set.of("fork_request", "fork_cancel").contains(type)) throw new IllegalArgumentException("Invalid FORK outbound type");
		if (!authenticated()) return false;
		send(type, "server", payload); return true;
	}

	private void send(String type, String agentId, JsonObject payload) {
		Session active = session;
		if (active == null || !active.authenticated.get()) {
			return;
		}
		active.enqueue(new BridgeEnvelope(2, serverInstanceId, agentId, type,
				"server-" + messageIds.incrementAndGet(), payload));
	}

	private boolean sendObservationEnvelope(Session source, AgentId agentId, JsonObject payload) {
		if (session != source || !source.open.get() || !source.authenticated.get()) return false;
		BridgeEnvelope envelope = new BridgeEnvelope(2, serverInstanceId, agentId.toString(), "observation",
				"server-" + messageIds.incrementAndGet(), payload);
		if (codec.encodedLineBytes(envelope) > BridgeEnvelopeCodec.MAX_LINE_BYTES) {
			throw new BridgeProtocolException("LINE_TOO_LARGE", "Fitted observation exceeds the actual wire envelope");
		}
		source.enqueue(envelope);
		return true;
	}

	private boolean sendInspectionEnvelope(Session source, AgentId agentId, JsonObject reply) {
		if (session != source || !source.open.get() || !source.authenticated.get()) return false;
		BridgeEnvelope envelope = new BridgeEnvelope(2, serverInstanceId, agentId.toString(), "inspection_result",
				"server-" + messageIds.incrementAndGet(), reply);
		if (codec.encodedLineBytes(envelope) > BridgeEnvelopeCodec.MAX_LINE_BYTES) {
			throw new BridgeProtocolException("INSPECTION_TOO_LARGE", "Inspection reply exceeds the complete bridge envelope limit");
		}
		source.enqueue(envelope);
		return true;
	}

	private boolean sendInspectionObservation(Session source, AgentId agentId, JsonObject observation, JsonObject reply) {
		if (session != source || !source.open.get() || !source.authenticated.get()) return false;
		BridgeEnvelope first = new BridgeEnvelope(2, serverInstanceId, agentId.toString(), "observation",
				"server-" + messageIds.incrementAndGet(), observation);
		BridgeEnvelope second = new BridgeEnvelope(2, serverInstanceId, agentId.toString(), "inspection_result",
				"server-" + messageIds.incrementAndGet(), reply);
		if (codec.encodedLineBytes(first) > BridgeEnvelopeCodec.MAX_LINE_BYTES
				|| codec.encodedLineBytes(second) > BridgeEnvelopeCodec.MAX_LINE_BYTES) {
			throw new BridgeProtocolException("INSPECTION_TOO_LARGE", "Fresh observation reply exceeds the complete bridge envelope limit");
		}
		source.enqueuePair(first, second, () -> {});
		return true;
	}

	private static JsonObject registeredPayload(AgentRecord record) {
		JsonObject payload = new JsonObject();
		payload.addProperty("schemaVersion", record.schemaVersion());
		payload.addProperty("agentId", record.agentId().toString());
		record.entityUuid().ifPresent(value -> payload.addProperty("entityUuid", value.toString()));
		record.profile().userName().ifPresent(value -> payload.addProperty("name", value));
		payload.addProperty("provider", record.profile().provider());
		payload.addProperty("model", record.profile().model());
		payload.addProperty("reasoningEffort", record.profile().reasoning());
		payload.addProperty("serviceTier", record.profile().serviceTier());
		payload.addProperty("gameMode", record.profile().gameMode().wireName());
		payload.addProperty("skinVariant", "variant-" + record.profile().skinVariant());
		payload.addProperty("state", record.state().name());
		record.currentGoal().ifPresent(goal -> payload.addProperty("currentGoal", plannerGoal(goal)));
		record.currentGoal().ifPresent(goal -> payload.add("currentGoalSpec", GOAL_SPEC_WIRE_CODEC.encodeSpec(goal.spec())));
		payload.addProperty("goalRevision", record.goalRevision());
		JsonArray queue = new JsonArray();
		JsonArray queueGoalSpecs = new JsonArray();
		for (AgentGoal goal : record.queuedGoals()) {
			queue.add(goal.prompt());
			queueGoalSpecs.add(GOAL_SPEC_WIRE_CODEC.encodeSpec(goal.spec()));
		}
		payload.add("queue", queue);
		payload.add("queueGoalSpecs", queueGoalSpecs);
		if (!record.lastSummary().isBlank()) {
			payload.addProperty("lastSummary", record.lastSummary());
		}
		record.deathSnapshot().ifPresent(death -> payload.add("death", deathFacts(death)));
		payload.addProperty("createdAtEpochMs", record.createdAtEpochMs());
		payload.addProperty("updatedAtEpochMs", record.updatedAtEpochMs());
		if (!record.lastError().isBlank()) {
			JsonObject error = new JsonObject();
			error.addProperty("code", "AGENT_ERROR");
			error.addProperty("message", record.lastError());
			payload.add("lastError", error);
		}
		return payload;
	}

	private static String operation(AgentTransition transition) {
		if (transition.after().queuedGoals().size() > transition.before().queuedGoals().size()) return "queue";
		if (transition.after().queuedGoals().size() + 1 == transition.before().queuedGoals().size()
				&& transition.after().goalRevision() == transition.before().goalRevision()) return "dequeue";
		if (transition.before().state() == AgentLifecycleState.DEAD
				&& transition.after().state() != AgentLifecycleState.DEAD) return "respawn";
		if (transition.after().state() == AgentLifecycleState.DEAD
				&& transition.before().state() != AgentLifecycleState.DEAD) return "dead";
		if (transition.after().goalRevision() <= transition.before().goalRevision()) return null;
		if (transition.before().currentGoal().isPresent()
				&& transition.after().state() == AgentLifecycleState.IDLE
				&& transition.after().currentGoal().isEmpty()) return "complete";
		if (transition.after().state() == AgentLifecycleState.PAUSED) return "stop";
		if (transition.after().state() == AgentLifecycleState.COMPLETED) return "complete";
		if (transition.after().state() == AgentLifecycleState.ERROR) return "fail";
		if (transition.after().state() == AgentLifecycleState.DISCONNECTED) return "disconnect";
		if ((transition.before().state() == AgentLifecycleState.PAUSED
				|| transition.before().state() == AgentLifecycleState.DISCONNECTED)
				&& transition.after().state() == AgentLifecycleState.STARTING) return "resume";
		if (transition.after().state() != AgentLifecycleState.STARTING) return null;
		if (transition.before().currentGoal().isPresent() && transition.after().currentGoal().isPresent()
				&& transition.before().currentGoal().get().goalId().equals(transition.after().currentGoal().get().goalId())) {
			return "steer";
		}
		if (transition.before().currentGoal().isPresent()
				&& transition.after().currentGoal().isPresent()
				&& transition.cancelAction()
				&& transition.interruptPlanner()) {
			return "replace";
		}
		return "start";
	}

	private static UUID logicalGoalId(AgentRecord record) {
		return record.currentGoal().map(AgentGoal::goalId).orElse(null);
	}

	private static String plannerGoal(AgentGoal goal) {
		if (goal.steeringInstructions().isEmpty()) {
			return goal.prompt();
		}
		String steering = "\n\nSteering instruction: "
				+ goal.steeringInstructions().get(goal.steeringInstructions().size() - 1);
		if (steering.length() >= AgentConstants.MAX_PROMPT_LENGTH) {
			return steering.substring(steering.length() - AgentConstants.MAX_PROMPT_LENGTH);
		}
		int promptLimit = AgentConstants.MAX_PROMPT_LENGTH - steering.length();
		String prompt = goal.prompt().length() <= promptLimit ? goal.prompt() : goal.prompt().substring(0, promptLimit);
		return prompt + steering;
	}

	private static GoalVerificationRuntime defaultGoalVerificationRuntime(CodexAgentManager manager) {
		Objects.requireNonNull(manager, "manager must not be null");
		return new GoalVerificationRuntime(
				manager.registry(),
				agentId -> manager.findAgentPlayer(agentId).map(GoalCompletionVerifier::minecraftFacts),
				() -> manager.server() == null ? 0L : manager.server().getTickCount(),
				System::currentTimeMillis,
				manager::validateGoalForActivation
		);
	}

	static void commitRespawnTerminal(Runnable commit, Runnable persistTerminal) {
		Objects.requireNonNull(commit, "commit must not be null");
		Objects.requireNonNull(persistTerminal, "persistTerminal must not be null");
		commit.run();
		persistTerminal.run();
	}

	private static DurableActionJournal defaultActionJournal(CodexAgentManager manager) {
		Objects.requireNonNull(manager, "manager must not be null");
		if (manager.server() == null || !manager.server().isRunning()) return DurableActionJournal.inMemory();
		return DurableActionJournal.open(AgentSavedData.actionJournalPath(manager.server()));
	}

	private static Path configuredSecretPath() {
		String configured = System.getProperty("arenaagents.bridgeSecretFile");
		if (configured == null || configured.isBlank()) configured = System.getenv("ARENA_AGENT_BRIDGE_SECRET_FILE");
		return configured == null || configured.isBlank() ? Paths.get("runtime", "bridge-secret.txt") : Paths.get(configured);
	}

	static int configuredPort() {
		String configured = System.getProperty("arenaagents.bridgePort");
		if (configured == null || configured.isBlank()) return DEFAULT_PORT;
		final int parsed;
		try {
			parsed = Integer.parseInt(configured);
		} catch (NumberFormatException exception) {
			throw new IllegalArgumentException("arenaagents.bridgePort must be an integer from 1 to 65535", exception);
		}
		if (parsed < 1 || parsed > 65_535) {
			throw new IllegalArgumentException("arenaagents.bridgePort must be an integer from 1 to 65535");
		}
		return parsed;
	}

	static int configuredHeartbeatMinimumIntervalTicks() {
		String configured = System.getProperty(HEARTBEAT_MIN_INTERVAL_TICKS_PROPERTY);
		if (configured == null || configured.isBlank()) return DEFAULT_HEARTBEAT_MIN_INTERVAL_TICKS;
		final int parsed;
		try {
			parsed = Integer.parseInt(configured);
		} catch (NumberFormatException exception) {
			throw new IllegalArgumentException(
					HEARTBEAT_MIN_INTERVAL_TICKS_PROPERTY + " must be an integer from 1 to " + Integer.MAX_VALUE,
					exception
			);
		}
		if (parsed < 1) {
			throw new IllegalArgumentException(
					HEARTBEAT_MIN_INTERVAL_TICKS_PROPERTY + " must be an integer from 1 to " + Integer.MAX_VALUE
			);
		}
		return parsed;
	}

	private static String readSecret(Path path) {
		try {
			if (!Files.isRegularFile(path)) {
				throw new BridgeProtocolException("BRIDGE_SECRET_MISSING", "Bridge secret file does not exist: " + path.toAbsolutePath());
			}
			String value = Files.readString(path, StandardCharsets.UTF_8).trim();
			if (value.length() < MIN_SECRET_LENGTH || value.length() > MAX_SECRET_LENGTH) {
				throw new BridgeProtocolException(
						"BRIDGE_SECRET_INVALID",
						"Bridge secret must contain " + MIN_SECRET_LENGTH + "-" + MAX_SECRET_LENGTH + " characters"
				);
			}
			return value;
		} catch (IOException exception) {
			throw new BridgeProtocolException("BRIDGE_SECRET_READ_FAILED", "Could not read bridge secret: " + path.toAbsolutePath(), exception);
		}
	}

	private static String requiredString(JsonObject object, String field) {
		return requiredString(object, field, 256);
	}

	private static String requiredString(JsonObject object, String field, int maximumLength) {
		if (!object.has(field)) throw new BridgeProtocolException("MISSING_FIELD", field);
		if (!object.get(field).isJsonPrimitive() || !object.get(field).getAsJsonPrimitive().isString()) {
			throw new BridgeProtocolException("INVALID_FIELD", field + " must be a JSON string");
		}
		String value = object.get(field).getAsString();
		if (value.isBlank() || value.length() > maximumLength) {
			throw new BridgeProtocolException("INVALID_FIELD", field + " must be nonblank and bounded");
		}
		return value;
	}

	private static String requiredAuthenticationToken(JsonObject object, String field) {
		String value = requiredString(object, field, AUTHENTICATION_TOKEN_LENGTH);
		if (value.length() != AUTHENTICATION_TOKEN_LENGTH || !value.matches("[A-Za-z0-9_-]+")) {
			throw new BridgeProtocolException("INVALID_AUTHENTICATION_TOKEN", field + " must be a 32-byte base64url value");
		}
		return value;
	}

	private String newAuthenticationNonce() {
		byte[] bytes = new byte[AUTHENTICATION_NONCE_BYTES];
		authenticationRandom.nextBytes(bytes);
		return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
	}

	private String authenticationProof(
			String role,
			String clientNonce,
			String serverNonce,
			String instanceId,
			String launchId
	) {
		return authenticationProof(secret, role, clientNonce, serverNonce, instanceId, launchId);
	}

	static String authenticationProof(
			String secret,
			String role,
			String clientNonce,
			String serverNonce,
			String instanceId,
			String launchId
	) {
		if (!Set.of("server", "coordinator").contains(role)) throw new IllegalArgumentException("authentication role is invalid");
		String context = String.join("\0", AUTHENTICATION_CONTEXT, role, clientNonce, serverNonce, instanceId)
				+ ("coordinator".equals(role) ? "\0" + Objects.toString(launchId, "") : "");
		try {
			Mac mac = Mac.getInstance("HmacSHA256");
			mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
			return Base64.getUrlEncoder().withoutPadding().encodeToString(mac.doFinal(context.getBytes(StandardCharsets.UTF_8)));
		} catch (GeneralSecurityException exception) {
			throw new IllegalStateException("HmacSHA256 is unavailable", exception);
		}
	}

	static String actionResultReplayProof(
			String secret,
			String clientNonce,
			String serverNonce,
			String instanceId,
			String agentId,
			long goalRevision,
			String actionId
	) {
		String context = String.join("\0", ACTION_RESULT_REPLAY_CONTEXT, clientNonce, serverNonce, instanceId,
				agentId, Long.toString(goalRevision), actionId);
		try {
			Mac mac = Mac.getInstance("HmacSHA256");
			mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
			return Base64.getUrlEncoder().withoutPadding().encodeToString(mac.doFinal(context.getBytes(StandardCharsets.UTF_8)));
		} catch (GeneralSecurityException exception) {
			throw new IllegalStateException("HmacSHA256 is unavailable", exception);
		}
	}

	private static String validatePreparedSecret(String secret) {
		String value = Objects.requireNonNull(secret, "prepared bridge secret must not be null").trim();
		if (value.length() < MIN_SECRET_LENGTH || value.length() > MAX_SECRET_LENGTH) {
			throw new BridgeProtocolException(
					"BRIDGE_SECRET_INVALID",
					"Bridge secret must contain " + MIN_SECRET_LENGTH + "-" + MAX_SECRET_LENGTH + " characters"
			);
		}
		return value;
	}

	private static String optionalLaunchId(JsonObject object) {
		if (!object.has("launchId")) return null;
		String value = requiredString(object, "launchId");
		try {
			String canonical = UUID.fromString(value).toString();
			if (value.length() != 36 || !canonical.equalsIgnoreCase(value)) throw new IllegalArgumentException();
			return canonical;
		} catch (IllegalArgumentException invalid) {
			throw new BridgeProtocolException("INVALID_LAUNCH_ID", "launchId must be a UUID", invalid);
		}
	}

	private static String requiredTraceId(JsonObject object, String field) {
		String value = requiredString(object, field);
		if (value.getBytes(StandardCharsets.UTF_8).length > 128
				|| value.codePoints().anyMatch(codePoint -> codePoint < 0x20 || codePoint == 0x7f)) {
			throw new BridgeProtocolException("INVALID_TRACE_ID", field + " must be at most 128 UTF-8 bytes");
		}
		return value;
	}

	private static String nullableString(JsonObject object, String field) {
		return object.has(field) && !object.get(field).isJsonNull() ? object.get(field).getAsString() : null;
	}

	private static String requiredStatusString(JsonObject object, String field) {
		if (!object.has(field) || !object.get(field).isJsonPrimitive() || !object.get(field).getAsJsonPrimitive().isString()) {
			throw new BridgeProtocolException("INVALID_COORDINATOR_STATUS", field + " must be a string");
		}
		String value = object.get(field).getAsString();
		if (value.isBlank() || value.length() > 256) {
			throw new BridgeProtocolException("INVALID_COORDINATOR_STATUS", field + " must be nonblank and at most 256 characters");
		}
		return value;
	}

	private static String requiredNullableStatusString(JsonObject object, String field) {
		if (!object.has(field)) throw new BridgeProtocolException("MISSING_FIELD", field);
		if (object.get(field).isJsonNull()) return null;
		return requiredStatusString(object, field);
	}

	private static Long requiredNullableStatusLong(JsonObject object, String field) {
		if (!object.has(field)) throw new BridgeProtocolException("MISSING_FIELD", field);
		return object.get(field).isJsonNull() ? null : requiredLong(object, field);
	}

	private static String requiredProvenanceString(JsonObject object, String field) {
		if (!object.has(field) || !object.get(field).isJsonPrimitive() || !object.get(field).getAsJsonPrimitive().isString()) {
			throw new BridgeProtocolException("MISSING_FIELD", "provenance." + field);
		}
		return object.get(field).getAsString();
	}

	private static JsonObject requiredObject(JsonObject object, String field) {
		if (!object.has(field) || !object.get(field).isJsonObject()) throw new BridgeProtocolException("MISSING_FIELD", field);
		return object.getAsJsonObject(field);
	}

	private static JsonArray requiredArray(JsonObject object, String field, int maximum) {
		if (!object.has(field) || !object.get(field).isJsonArray()) throw new BridgeProtocolException("MISSING_FIELD", field);
		JsonArray value = object.getAsJsonArray(field);
		if (value.size() > maximum) throw new BridgeProtocolException("INVALID_FIELD", field);
		return value;
	}

	private static List<String> stringList(JsonObject object, String field, int maximum) {
		JsonArray values = requiredArray(object, field, maximum);
		ArrayList<String> result = new ArrayList<>(values.size());
		for (var value : values) {
			if (!value.isJsonPrimitive() || !value.getAsJsonPrimitive().isString()) {
				throw new BridgeProtocolException("INVALID_MODEL_CATALOG", field + " entries must be strings");
			}
			result.add(value.getAsString());
		}
		return List.copyOf(result);
	}

	private static boolean requiredBoolean(JsonObject object, String field) {
		if (!object.has(field) || !object.get(field).isJsonPrimitive() || !object.get(field).getAsJsonPrimitive().isBoolean()) {
			throw new BridgeProtocolException("MISSING_FIELD", field);
		}
		return object.get(field).getAsBoolean();
	}

	private static int requiredInt(JsonObject object, String field) {
		if (!object.has(field) || !object.get(field).isJsonPrimitive() || !object.get(field).getAsJsonPrimitive().isNumber()) {
			throw new BridgeProtocolException("MISSING_FIELD", field);
		}
		double value = object.get(field).getAsDouble();
		if (!Double.isFinite(value) || value < 0.0 || value > Integer.MAX_VALUE || value != Math.rint(value)) {
			throw new BridgeProtocolException("INVALID_FIELD", field);
		}
		return (int) value;
	}

	private static double requiredDouble(JsonObject object, String field) {
		if (!object.has(field) || !object.get(field).isJsonPrimitive() || !object.get(field).getAsJsonPrimitive().isNumber()) {
			throw new BridgeProtocolException("MISSING_FIELD", field);
		}
		double value = object.get(field).getAsDouble();
		if (!Double.isFinite(value)) throw new BridgeProtocolException("INVALID_FIELD", field);
		return value;
	}

	private static double requiredNonNegativeDouble(JsonObject object, String field) {
		double value = requiredDouble(object, field);
		if (value < 0.0D) throw new BridgeProtocolException("INVALID_FIELD", field);
		return value;
	}

	/** Serializes observation delivery with session identity, queue, and baseline lifecycle. */
	static final class ObservationPublication {
		enum Result { COMMITTED, SUPPRESSED, STALE_SESSION, DELIVERY_RETRY }

		@FunctionalInterface
		interface Writer {
			boolean send(AgentId agentId, JsonObject payload);
		}

		private final Object lifecycleLock = new Object();
		private final ObservationDispatchQueue<AgentId> queue;
		private final PublishedObservationState published;
		private final int queueCapacity;
		private final int perTickLimit;
		private final int heartbeatMinimumIntervalTicks;
		private final java.util.function.BiFunction<AgentId, JsonObject, ServerObservationWireBudget.Fitted> fitter;
		private final LinkedHashSet<AgentId> urgent = new LinkedHashSet<>();
		private final Set<AgentId> heartbeatPending = new HashSet<>();
		private final Map<AgentId, Long> nextHeartbeatTick = new HashMap<>();
		private final AtomicLong sequences = new AtomicLong();
		private Object activeSession;
		private int heartbeatCursor;
		private long heartbeatTick;

		ObservationPublication(int queueCapacity, int perTickLimit) {
			this(queueCapacity, perTickLimit, DEFAULT_HEARTBEAT_MIN_INTERVAL_TICKS,
					(agentId, observation) -> new ServerObservationWireBudget.Fitted(observation, List.of()));
		}

		ObservationPublication(
				int queueCapacity,
				int perTickLimit,
				java.util.function.BiFunction<AgentId, JsonObject, ServerObservationWireBudget.Fitted> fitter
		) {
			this(queueCapacity, perTickLimit, DEFAULT_HEARTBEAT_MIN_INTERVAL_TICKS, fitter);
		}

		ObservationPublication(int queueCapacity, int perTickLimit, int heartbeatMinimumIntervalTicks) {
			this(queueCapacity, perTickLimit, heartbeatMinimumIntervalTicks,
					(agentId, observation) -> new ServerObservationWireBudget.Fitted(observation, List.of()));
		}

		ObservationPublication(
				int queueCapacity,
				int perTickLimit,
				int heartbeatMinimumIntervalTicks,
				java.util.function.BiFunction<AgentId, JsonObject, ServerObservationWireBudget.Fitted> fitter
		) {
			if (heartbeatMinimumIntervalTicks < 1) {
				throw new IllegalArgumentException("heartbeat minimum interval must be positive");
			}
			this.queueCapacity = queueCapacity;
			this.perTickLimit = perTickLimit;
			this.heartbeatMinimumIntervalTicks = heartbeatMinimumIntervalTicks;
			queue = new ObservationDispatchQueue<>(queueCapacity, perTickLimit);
			published = new PublishedObservationState(queueCapacity);
			this.fitter = Objects.requireNonNull(fitter, "fitter must not be null");
		}

		void activate(Object session) {
			synchronized (lifecycleLock) {
				if (activeSession != null && activeSession != session) clearLocked();
				activeSession = Objects.requireNonNull(session, "session must not be null");
			}
		}

		void deactivate(Object session) {
			synchronized (lifecycleLock) {
				if (activeSession == session) {
					activeSession = null;
					clearLocked();
				}
			}
		}

		void reset() {
			synchronized (lifecycleLock) {
				clearLocked();
			}
		}

		boolean offer(AgentId agentId) {
			Objects.requireNonNull(agentId, "agentId must not be null");
			synchronized (lifecycleLock) {
				if (urgent.contains(agentId)) return true;
				heartbeatPending.remove(agentId);
				try {
					return queue.offer(agentId);
				} catch (IllegalStateException exception) {
					return false;
				}
			}
		}

		boolean offerHeartbeat(AgentId agentId) {
			Objects.requireNonNull(agentId, "agentId must not be null");
			synchronized (lifecycleLock) {
				if (urgent.contains(agentId)) return true;
				try {
					if (queue.offer(agentId) || queue.contains(agentId)) {
						heartbeatPending.add(agentId);
						return true;
					}
				} catch (IllegalStateException ignored) {
					// Backpressure remains bounded; the rotating heartbeat will retry later.
				}
				return false;
			}
		}

		boolean offerUrgent(AgentId agentId) {
			Objects.requireNonNull(agentId, "agentId must not be null");
			synchronized (lifecycleLock) {
				if (urgent.contains(agentId)) return true;
				if (urgent.size() >= queueCapacity) return false;
				queue.remove(agentId);
				heartbeatPending.remove(agentId);
				urgent.add(agentId);
				return true;
			}
		}

		/** Queues one due heartbeat per call and rotates fairly across the supplied roster. */
		void scheduleIdleHeartbeat(List<AgentId> agents) {
			Objects.requireNonNull(agents, "agents must not be null");
			List<AgentId> roster = agents.stream()
					.map(Objects::requireNonNull)
					.distinct()
					.toList();
			synchronized (lifecycleLock) {
				if (heartbeatTick < Long.MAX_VALUE) heartbeatTick += 1L;
				nextHeartbeatTick.keySet().removeIf(agentId -> !roster.contains(agentId));
				heartbeatPending.removeIf(agentId -> !roster.contains(agentId));
				if (roster.isEmpty()) {
					heartbeatCursor = 0;
					return;
				}
				int start = Math.floorMod(heartbeatCursor, roster.size());
				for (int offset = 0; offset < roster.size(); offset++) {
					int index = (start + offset) % roster.size();
					AgentId agentId = roster.get(index);
					if (urgent.contains(agentId) || !heartbeatDue(agentId)) continue;
					try {
						boolean queued = queue.offer(agentId) || queue.contains(agentId);
						if (queued) {
							heartbeatPending.add(agentId);
							heartbeatCursor = (index + 1) % roster.size();
							return;
						}
					} catch (IllegalStateException ignored) {
						// A full coalescing queue defers this heartbeat to a later rotation.
						return;
					}
				}
				// No agent was due; keep rotating from the next roster member on the next tick.
				heartbeatCursor = (start + 1) % roster.size();
			}
		}

		private boolean heartbeatDue(AgentId agentId) {
			Long due = nextHeartbeatTick.get(agentId);
			return due == null || due <= heartbeatTick;
		}

		boolean takeHeartbeat(AgentId agentId) {
			synchronized (lifecycleLock) {
				return heartbeatPending.remove(agentId);
			}
		}

		void drain(java.util.function.Consumer<AgentId> consumer) {
			Objects.requireNonNull(consumer, "consumer must not be null");
			for (int emitted = 0; emitted < perTickLimit; emitted++) {
				AgentId agentId;
				synchronized (lifecycleLock) {
					var iterator = urgent.iterator();
					if (iterator.hasNext()) {
						agentId = iterator.next();
						iterator.remove();
					} else {
						agentId = queue.poll();
					}
					if (agentId == null) return;
				}
				consumer.accept(agentId);
			}
		}

		void remove(AgentId agentId) {
			Objects.requireNonNull(agentId, "agentId must not be null");
			synchronized (lifecycleLock) {
				queue.remove(agentId);
				urgent.remove(agentId);
				heartbeatPending.remove(agentId);
				nextHeartbeatTick.remove(agentId);
				published.remove(agentId);
			}
		}
		boolean markDirty(AgentId agentId) { return published.markDirty(agentId); }
		boolean markAttention(AgentId agentId) { return published.markAttention(agentId); }
		void requireObservedTarget(AgentId agentId, long eventSequence, String targetId) {
			published.requireObservedTarget(agentId, eventSequence, targetId);
		}
		void retainConversationSource(AgentId agentId, String sourceId) {
			published.retainConversationSource(agentId, sourceId);
		}
		void requireDirectMessageRecipient(AgentId agentId, long eventSequence, String recipientId) {
			published.requireDirectMessageRecipient(agentId, eventSequence, recipientId);
		}
		int pendingCount() {
			synchronized (lifecycleLock) {
				return queue.pendingCount() + urgent.size();
			}
		}
		int retainedCount() { return published.retainedCount(); }
		boolean hasActiveSession() {
			synchronized (lifecycleLock) {
				return activeSession != null;
			}
		}

		Result publish(AgentId agentId, Object sourceSession, JsonObject observation, Writer writer) {
			return publish(agentId, sourceSession, observation, writer, false);
		}

		Result publish(AgentId agentId, Object sourceSession, JsonObject observation, Writer writer, boolean allowUnchanged) {
			return publish(agentId, sourceSession, observation, writer, allowUnchanged, fitter);
		}

		Result publish(AgentId agentId, Object sourceSession, JsonObject observation, Writer writer, boolean allowUnchanged,
				java.util.function.BiFunction<AgentId, JsonObject, ServerObservationWireBudget.Fitted> deliveryFitter) {
			Objects.requireNonNull(agentId, "agentId must not be null");
			Objects.requireNonNull(sourceSession, "sourceSession must not be null");
			Objects.requireNonNull(observation, "observation must not be null");
			Objects.requireNonNull(writer, "writer must not be null");
			synchronized (lifecycleLock) {
				if (activeSession != sourceSession) return Result.STALE_SESSION;
				long eventSequence = sequences.incrementAndGet();
				long observedAtEpochMs = observation.get("observedAtEpochMs").getAsLong();
				AttentionFactDelta delta = published.delta(agentId, observation, eventSequence, observedAtEpochMs);
				if (!allowUnchanged && published.hasDelivered(agentId) && !delta.attention()) return Result.SUPPRESSED;
				JsonObject delivery = observation.deepCopy();
				attachDelta(delivery, delta);
				ServerObservationWireBudget.Fitted fitted = deliveryFitter.apply(agentId, delivery);
				delivery = fitted.observation();
				if (!fitted.reductions().isEmpty()) {
					delta = published.delta(agentId, delivery, eventSequence, observedAtEpochMs);
					attachDelta(delivery, delta);
					delivery = deliveryFitter.apply(agentId, delivery).observation();
					attachDelta(delivery, published.delta(agentId, delivery, eventSequence, observedAtEpochMs));
				}
				if (!writer.send(agentId, delivery)) return Result.DELIVERY_RETRY;
				published.commit(agentId, delivery);
				nextHeartbeatTick.put(agentId, nextHeartbeatDeadline());
				return Result.COMMITTED;
			}
		}

		/** Grants only the entities in a delivered focused page, without replacing the full observation baseline. */
		Result deliverInspection(AgentId agentId, Object sourceSession, JsonObject inspection, Writer writer) {
			synchronized (lifecycleLock) {
				if (activeSession != sourceSession) return Result.STALE_SESSION;
				long sequence = published.inspectionSequence(agentId, inspection);
				Set<String> targets = PublishedObservationState.inspectionTargetIds(inspection);
				published.checkInspectionTargets(agentId, sequence, targets);
				JsonObject delivery = inspection.deepCopy();
				delivery.addProperty("eventSequence", sequence);
				if (!writer.send(agentId, delivery)) return Result.DELIVERY_RETRY;
				if (activeSession != sourceSession) return Result.STALE_SESSION;
				published.retainInspectionTargets(agentId, sequence, targets);
				return Result.COMMITTED;
			}
		}

		private static void attachDelta(JsonObject observation, AttentionFactDelta delta) {
			observation.addProperty("eventSequence", delta.eventSequence());
			observation.addProperty("attention", delta.attention());
			JsonArray changedFacts = new JsonArray();
			delta.changedFacts().forEach(changedFacts::add);
			observation.add("changedFacts", changedFacts);
		}

		private void clearLocked() {
			queue.clear();
			urgent.clear();
			heartbeatPending.clear();
			nextHeartbeatTick.clear();
			heartbeatCursor = 0;
			heartbeatTick = 0L;
			published.clear();
		}

		private long nextHeartbeatDeadline() {
			long interval = heartbeatMinimumIntervalTicks;
			return heartbeatTick > Long.MAX_VALUE - interval ? Long.MAX_VALUE : heartbeatTick + interval;
		}
	}

	/** Retains only successfully delivered baselines and a bounded retry marker. */
	public static final class PublishedObservationState {
		private final int retryCapacity;
		private final Map<AgentId, JsonObject> delivered = new HashMap<>();
		private final Set<AgentId> dirty = new HashSet<>();
		private final Set<AgentId> forcedAttention = new HashSet<>();
		private final Map<AgentId, ObservationTargetHistory> targetHistory = new java.util.LinkedHashMap<>();
		private final Map<AgentId, java.util.LinkedHashSet<String>> conversationSources = new java.util.LinkedHashMap<>();

		public PublishedObservationState(int retryCapacity) {
			if (retryCapacity < 1) throw new IllegalArgumentException("retryCapacity must be positive");
			this.retryCapacity = retryCapacity;
		}

		public synchronized AttentionFactDelta delta(AgentId agentId, JsonObject current, long eventSequence, long observedAtEpochMs) {
			Objects.requireNonNull(agentId, "agentId must not be null");
			AttentionFactDelta delta = AttentionFactDelta.between(delivered.get(agentId), current, eventSequence, observedAtEpochMs);
			return forcedAttention.contains(agentId) && !delta.attention()
					? new AttentionFactDelta(delta.eventSequence(), true, delta.changedFacts(), delta.observedAtEpochMs())
					: delta;
		}

		public synchronized void commit(AgentId agentId, JsonObject deliveredObservation) {
			delivered.put(agentId, deliveredObservation.deepCopy());
			if (deliveredObservation.has("eventSequence") && deliveredObservation.get("eventSequence").isJsonPrimitive()
					&& deliveredObservation.get("eventSequence").getAsJsonPrimitive().isNumber()) {
				long eventSequence = deliveredObservation.get("eventSequence").getAsLong();
				if (!targetHistory.containsKey(agentId) && targetHistory.size() >= AgentConstants.DEFAULT_AGENT_LIMIT) {
					targetHistory.remove(targetHistory.keySet().iterator().next());
				}
				targetHistory.computeIfAbsent(agentId, ignored -> new ObservationTargetHistory())
						.retain(eventSequence, observedTargetIds(deliveredObservation));
			}
			dirty.remove(agentId);
			forcedAttention.remove(agentId);
		}

		public synchronized boolean hasDelivered(AgentId agentId) {
			return delivered.containsKey(Objects.requireNonNull(agentId, "agentId must not be null"));
		}

		private synchronized long inspectionSequence(AgentId agentId, JsonObject inspection) {
			JsonObject baseline = delivered.get(agentId);
			if (baseline == null || !baseline.has("eventSequence")) {
				throw new AgentDomainException("STALE_FACTS", "Read a full observation before focused inspection");
			}
			JsonObject world = baseline.has("world") && baseline.get("world").isJsonObject() ? baseline.getAsJsonObject("world") : null;
			if (world == null || !inspection.has("dimension") || !inspection.has("worldId")
					|| !Objects.equals(world.get("dimension"), inspection.get("dimension"))
					|| !Objects.equals(world.get("worldId"), inspection.get("worldId"))) {
				throw new AgentDomainException("STALE_FACTS", "World changed; read a full observation before focused inspection");
			}
			return baseline.get("eventSequence").getAsLong();
		}

		private synchronized void checkInspectionTargets(AgentId agentId, long sequence, Set<String> targets) {
			ObservationTargetHistory history = targetHistory.get(agentId);
			if (history == null || !history.contains(sequence)) throw new AgentDomainException("STALE_FACTS", "Inspection baseline is no longer retained");
			Set<String> combined = new HashSet<>(history.targets(sequence));
			combined.addAll(targets);
			if (combined.size() > MAX_TARGET_IDS_WITH_INSPECTIONS) {
				throw new AgentDomainException("INSPECTION_AUTHORITY_LIMIT", "Read a new full observation before inspecting more entity targets");
			}
		}

		private synchronized void retainInspectionTargets(AgentId agentId, long sequence, Set<String> targets) {
			if (targets.isEmpty()) return;
			checkInspectionTargets(agentId, sequence, targets);
			ObservationTargetHistory history = targetHistory.get(agentId);
			HashSet<String> combined = new HashSet<>(history.targets(sequence));
			combined.addAll(targets);
			history.observations.put(sequence, Set.copyOf(combined));
		}

		private static Set<String> inspectionTargetIds(JsonObject inspection) {
			if (!inspection.has("section") || !"entities".equals(inspection.get("section").getAsString())) return Set.of();
			if (!inspection.has("entries") || !inspection.get("entries").isJsonArray()
					|| inspection.getAsJsonArray("entries").size() > 32) {
				throw new AgentDomainException("INVALID_INSPECTION", "Entity inspection must be one bounded page");
			}
			HashSet<String> result = new HashSet<>();
			for (JsonElement entry : inspection.getAsJsonArray("entries")) {
				if (!entry.isJsonObject()) throw new AgentDomainException("INVALID_INSPECTION", "Invalid inspected entity");
				JsonElement uuid = entry.getAsJsonObject().get("uuid");
				if (uuid == null || !uuid.isJsonPrimitive() || !uuid.getAsJsonPrimitive().isString()) {
					throw new AgentDomainException("INVALID_INSPECTION", "Inspected entity requires a UUID");
				}
				String id = uuid.getAsString();
				try {
					if (!UUID.fromString(id).toString().equals(id)) throw new IllegalArgumentException();
				} catch (IllegalArgumentException exception) {
					throw new AgentDomainException("INVALID_INSPECTION", "Invalid inspected entity UUID");
				}
				result.add(id);
			}
			return Set.copyOf(result);
		}

		/** Requires a target id to be present in the exact bounded observation selected by provenance. */
		public synchronized void requireObservedTarget(AgentId agentId, long eventSequence, String targetId) {
			Objects.requireNonNull(agentId, "agentId must not be null");
			Objects.requireNonNull(targetId, "targetId must not be null");
			ObservationTargetHistory history = targetHistory.get(agentId);
			if (history == null || !history.contains(eventSequence)) {
				if (history != null && history.isOlderThanRetained(eventSequence)) {
					throw new AgentDomainException("STALE_FACTS", "Action facts event sequence is no longer retained");
				}
				throw new AgentDomainException("TARGET_NOT_OBSERVED", "Target was not present in the delivered observation");
			}
			if (!history.targets(eventSequence).contains(targetId)) {
				throw new AgentDomainException("TARGET_NOT_OBSERVED", "Target was not present in the delivered observation");
			}
		}

		public synchronized boolean markDirty(AgentId agentId) {
			if (dirty.contains(agentId)) return true;
			if (dirty.size() >= retryCapacity) return false;
			dirty.add(agentId);
			return true;
		}

		/** Allows direct replies to authenticated player messages even after the sender leaves view. */
		public synchronized void retainConversationSource(AgentId agentId, String sourceId) {
			Objects.requireNonNull(agentId, "agentId must not be null");
			UUID.fromString(Objects.requireNonNull(sourceId, "sourceId must not be null"));
			if (!conversationSources.containsKey(agentId)
					&& conversationSources.size() >= AgentConstants.DEFAULT_AGENT_LIMIT) {
				conversationSources.remove(conversationSources.keySet().iterator().next());
			}
			java.util.LinkedHashSet<String> sources = conversationSources.computeIfAbsent(
					agentId, ignored -> new java.util.LinkedHashSet<>()
			);
			sources.remove(sourceId);
			sources.add(sourceId);
			while (sources.size() > MAX_CONVERSATION_SOURCES_PER_AGENT) {
				sources.remove(sources.iterator().next());
			}
		}

		public synchronized void requireDirectMessageRecipient(AgentId agentId, long eventSequence, String recipientId) {
			Set<String> trustedSources = conversationSources.get(agentId);
			if (trustedSources != null && trustedSources.contains(recipientId)) return;
			requireObservedTarget(agentId, eventSequence, recipientId);
		}

		public synchronized boolean markAttention(AgentId agentId) {
			Objects.requireNonNull(agentId, "agentId must not be null");
			if (forcedAttention.contains(agentId)) return true;
			if (forcedAttention.size() >= retryCapacity) return false;
			forcedAttention.add(agentId);
			return true;
		}

		public synchronized void remove(AgentId agentId) {
			delivered.remove(agentId);
			dirty.remove(agentId);
			forcedAttention.remove(agentId);
			targetHistory.remove(agentId);
			conversationSources.remove(agentId);
		}

		public synchronized int retainedCount() {
			return delivered.size() + dirty.size();
		}

		public synchronized void clear() {
			delivered.clear();
			dirty.clear();
			forcedAttention.clear();
			targetHistory.clear();
			conversationSources.clear();
		}

		private static Set<String> observedTargetIds(JsonObject observation) {
			if (!observation.has("entities") || !observation.get("entities").isJsonArray()) return Set.of();
			HashSet<String> result = new HashSet<>();
			for (var element : observation.getAsJsonArray("entities")) {
				if (!element.isJsonObject()) continue;
				JsonObject entity = element.getAsJsonObject();
				String targetId = null;
				if (entity.has("uuid") && entity.get("uuid").isJsonPrimitive() && entity.get("uuid").getAsJsonPrimitive().isString()) {
					targetId = entity.get("uuid").getAsString();
				} else if (entity.has("stableId") && entity.get("stableId").isJsonPrimitive() && entity.get("stableId").getAsJsonPrimitive().isString()) {
					targetId = entity.get("stableId").getAsString();
				}
				if (targetId != null && result.size() < MAX_TARGET_IDS_PER_OBSERVATION) result.add(targetId);
			}
			return Set.copyOf(result);
		}

		private static final class ObservationTargetHistory {
			private final java.util.LinkedHashMap<Long, Set<String>> observations = new java.util.LinkedHashMap<>();
			private Set<String> lastTargets = Set.of();
			private boolean hasLastTargets;

			void retain(long eventSequence, Set<String> targetIds) {
				if (!hasLastTargets || !lastTargets.equals(targetIds)) {
					lastTargets = Set.copyOf(targetIds);
					hasLastTargets = true;
				}
				observations.put(eventSequence, lastTargets);
				while (observations.size() > OBSERVATION_HISTORY_CAPACITY) {
					observations.remove(observations.keySet().iterator().next());
				}
			}

			boolean contains(long eventSequence) { return observations.containsKey(eventSequence); }
			Set<String> targets(long eventSequence) { return observations.getOrDefault(eventSequence, Set.of()); }
			boolean isOlderThanRetained(long eventSequence) {
				return !observations.isEmpty() && eventSequence < observations.keySet().iterator().next();
			}
		}
	}

	private static void requireKeys(JsonObject object, Set<String> expected, String field) {
		if (!object.keySet().equals(expected)) throw new BridgeProtocolException("INVALID_FIELD", field);
	}

	private static long requiredLong(JsonObject object, String field) {
		if (!object.has(field)) throw new BridgeProtocolException("MISSING_FIELD", field);
		if (!object.get(field).isJsonPrimitive() || !object.get(field).getAsJsonPrimitive().isNumber()) {
			throw new BridgeProtocolException("INVALID_FIELD", field + " must be a JSON number");
		}
		try {
			long value = object.get(field).getAsBigDecimal().longValueExact();
			if (value < 0L || value > ActionProvenance.MAX_SAFE_INTEGER) throw new ArithmeticException();
			return value;
		} catch (ArithmeticException exception) {
			throw new BridgeProtocolException("INVALID_GOAL_REVISION", field + " must be a nonnegative safe integer", exception);
		}
	}

	private static long requiredSafeLong(JsonObject object, String field) {
		if (!object.has(field) || !object.get(field).isJsonPrimitive() || !object.get(field).getAsJsonPrimitive().isNumber()) {
			throw new BridgeProtocolException("MISSING_FIELD", "provenance." + field);
		}
		try {
			long value = object.get(field).getAsBigDecimal().longValueExact();
			if (value < 0L || value > ActionProvenance.MAX_SAFE_INTEGER) throw new ArithmeticException();
			return value;
		} catch (ArithmeticException exception) {
			throw new BridgeProtocolException("INVALID_PROVENANCE", "provenance." + field + " must be a nonnegative safe integer");
		}
	}

	private final class Session implements AutoCloseable {
		private final Socket socket;
		private final ArrayBlockingQueue<BridgeEnvelope> outbound = new ArrayBlockingQueue<>(CONNECTION_QUEUE_CAP);
		private final Map<String, Integer> queuedByAgent = new HashMap<>();
		private final Set<String> inboundIds = new java.util.LinkedHashSet<>();
		private final AtomicBoolean open = new AtomicBoolean(true);
		private final AtomicBoolean authenticated = new AtomicBoolean();
		private final long handshakeStartedNanos;
		private final long handshakeTimeoutNanos = TimeUnit.MILLISECONDS.toNanos(HANDSHAKE_TIMEOUT_MS);
		private volatile String clientNonce;
		private volatile String serverNonce;
		private volatile String authResponseMessageId;
		private volatile String authenticatedLaunchId;
		private volatile long authenticatedSessionGeneration;
		private volatile Thread readerThread;
		private volatile Thread writerThread;

		Session(Socket socket) throws IOException {
			this.socket = socket;
			handshakeStartedNanos = System.nanoTime();
			socket.setTcpNoDelay(true);
			 socket.setSoTimeout(HANDSHAKE_TIMEOUT_MS);
		}

		long handshakeRemainingNanos() {
			return handshakeTimeoutNanos - (System.nanoTime() - handshakeStartedNanos);
		}

		void start() {
			readerThread = Thread.ofPlatform().daemon().name("arenaagents-v2-reader").start(this::readLoop);
			writerThread = Thread.ofPlatform().daemon().name("arenaagents-v2-writer").start(this::writeLoop);
		}

		synchronized void beginAuthentication(String clientNonce, String serverNonce, BridgeEnvelope response) {
			if (!open.get() || authenticated.get() || this.clientNonce != null) {
				throw new BridgeProtocolException("DUPLICATE_HANDSHAKE", "Bridge authentication challenge was already processed");
			}
			if (!"auth_response".equals(response.type())) throw new IllegalArgumentException("authentication response type is invalid");
			this.clientNonce = clientNonce;
			this.serverNonce = serverNonce;
			this.authResponseMessageId = response.messageId();
			if (!outbound.offer(response)) throw new BridgeProtocolException("CONNECTION_BACKPRESSURE", "Could not publish server authentication response");
			queuedByAgent.merge(response.agentId(), 1, Integer::sum);
		}

		synchronized void completeHandshake(List<BridgeEnvelope> envelopes, String launchId, long sessionGeneration) {
			if (!open.get()) throw new BridgeProtocolException("COORDINATOR_DISCONNECTED", "Bridge session closed during authentication");
			if (authenticated.get()) throw new BridgeProtocolException("DUPLICATE_HANDSHAKE", "Bridge session is already authenticated");
			List<BridgeEnvelope> ordered = List.copyOf(Objects.requireNonNull(envelopes, "envelopes must not be null"));
			if (ordered.isEmpty() || !"hello_ack".equals(ordered.getFirst().type())) {
				throw new IllegalArgumentException("handshake must begin with hello_ack");
			}
			ordered.forEach(codec::encodeFrame);
			if (outbound.remainingCapacity() < ordered.size()) {
				throw new BridgeProtocolException("CONNECTION_BACKPRESSURE", "Outbound queue cannot publish handshake replay batch");
			}
			Map<String, Integer> additions = new HashMap<>();
			for (BridgeEnvelope envelope : ordered) {
				int added = additions.merge(envelope.agentId(), 1, Integer::sum);
				if (queuedByAgent.getOrDefault(envelope.agentId(), 0) + added > AGENT_QUEUE_CAP) {
					throw new BridgeProtocolException("AGENT_BACKPRESSURE", envelope.agentId());
				}
			}
			authenticatedLaunchId = launchId;
			authenticatedSessionGeneration = sessionGeneration;
			authenticated.set(true);
			for (BridgeEnvelope envelope : ordered) {
				if (!outbound.offer(envelope)) throw new IllegalStateException("preflighted handshake queue rejected an envelope");
				queuedByAgent.merge(envelope.agentId(), 1, Integer::sum);
			}
		}

		void enqueue(BridgeEnvelope envelope) {
			codec.encodeFrame(envelope);
			synchronized (publicationLock) {
				synchronized (this) {
					if (!open.get() || !authenticated.get()) {
						throw new BridgeProtocolException("COORDINATOR_DISCONNECTED", "Bridge session closed before publication");
					}
					int agentQueued = queuedByAgent.getOrDefault(envelope.agentId(), 0);
					if (agentQueued >= AGENT_QUEUE_CAP) throw new BridgeProtocolException("AGENT_BACKPRESSURE", envelope.agentId());
					if (!outbound.offer(envelope)) throw new BridgeProtocolException("CONNECTION_BACKPRESSURE", "Outbound queue is full");
					queuedByAgent.put(envelope.agentId(), agentQueued + 1);
				}
			}
		}

		void enqueuePair(BridgeEnvelope first, BridgeEnvelope second, Runnable beforeEnqueue) {
			codec.encodeFrame(first);
			codec.encodeFrame(second);
			synchronized (publicationLock) {
				synchronized (this) {
					if (!open.get() || !authenticated.get()) throw new BridgeProtocolException("COORDINATOR_DISCONNECTED", "Bridge session closed before paired publication");
					if (!first.agentId().equals(second.agentId())) throw new IllegalArgumentException("paired envelopes must belong to one agent");
					int agentQueued = queuedByAgent.getOrDefault(first.agentId(), 0);
					if (agentQueued > AGENT_QUEUE_CAP - 2) throw new BridgeProtocolException("AGENT_BACKPRESSURE", first.agentId());
					if (outbound.remainingCapacity() < 2) throw new BridgeProtocolException("CONNECTION_BACKPRESSURE", "Outbound queue cannot atomically publish paired messages");
					beforeEnqueue.run();
					if (!outbound.offer(first) || !outbound.offer(second)) {
						outbound.remove(first);
						outbound.remove(second);
						throw new IllegalStateException("preflighted paired publication queue rejected an envelope");
					}
					queuedByAgent.put(first.agentId(), agentQueued + 2);
				}
			}
		}

		void enqueueAtomically(BridgeEnvelope envelope, Runnable beforeEnqueue) {
			codec.encodeFrame(envelope);
			synchronized (publicationLock) {
				synchronized (this) {
					if (!open.get() || !authenticated.get()) throw new BridgeProtocolException("COORDINATOR_DISCONNECTED", "Bridge session closed before atomic publication");
					int agentQueued = queuedByAgent.getOrDefault(envelope.agentId(), 0);
					if (agentQueued >= AGENT_QUEUE_CAP) throw new BridgeProtocolException("AGENT_BACKPRESSURE", envelope.agentId());
					if (outbound.remainingCapacity() < 1) throw new BridgeProtocolException("CONNECTION_BACKPRESSURE", "Outbound queue cannot publish transaction");
					beforeEnqueue.run();
					if (!open.get() || !authenticated.get()) {
						throw new BridgeProtocolException("COORDINATOR_DISCONNECTED", "Bridge session closed during atomic publication");
					}
					if (!outbound.offer(envelope)) {
						throw new BridgeProtocolException("CONNECTION_BACKPRESSURE", "Atomic publication failed");
					}
					queuedByAgent.put(envelope.agentId(), agentQueued + 1);
				}
			}
		}

		private void readLoop() {
			try (BufferedInputStream input = new BufferedInputStream(socket.getInputStream())) {
				while (open.get()) {
					String line = authenticated.get() ? readLine(input) : readHandshakeLine(input);
					if (line == null) break;
					BridgeEnvelope envelope = codec.decode(line);
					synchronized (this) {
						if (!inboundIds.add(envelope.messageId())) throw new BridgeProtocolException("DUPLICATE_MESSAGE", envelope.messageId());
						while (inboundIds.size() > MAX_TRACKED_IDS) inboundIds.remove(inboundIds.iterator().next());
					}
					accept(envelope, this);
					if (authenticated.get()) socket.setSoTimeout(0);
				}
			} catch (SocketTimeoutException exception) {
				LOGGER.warn("Codex bridge authentication timed out");
			} catch (RuntimeException | IOException exception) {
				if (open.get()) LOGGER.warn("Codex bridge session closed: {}", exception.getMessage());
			} finally {
				close();
			}
		}

		private String readHandshakeLine(BufferedInputStream input) throws IOException {
			ByteArrayOutputStream bytes = new ByteArrayOutputStream();
			while (true) {
				long remaining = handshakeTimeoutNanos - (System.nanoTime() - handshakeStartedNanos);
				if (remaining <= 0L) throw new SocketTimeoutException("Coordinator handshake deadline expired");
				long remainingMs = Math.max(1L, Math.ceilDiv(remaining, 1_000_000L));
				socket.setSoTimeout((int) Math.min(Integer.MAX_VALUE, remainingMs));
				int value = input.read();
				if (value < 0) return bytes.size() == 0 ? null : bytes.toString(StandardCharsets.UTF_8);
				if (value == '\n') return bytes.toString(StandardCharsets.UTF_8);
				if (value != '\r') bytes.write(value);
				if (bytes.size() > BridgeEnvelopeCodec.MAX_LINE_BYTES) {
					throw new BridgeProtocolException("LINE_TOO_LARGE", "Inbound line exceeds limit");
				}
			}
		}

		private void writeLoop() {
			try (BufferedOutputStream output = new BufferedOutputStream(socket.getOutputStream())) {
				while (open.get()) {
					BridgeEnvelope envelope = outbound.take();
					BridgeEnvelopeCodec.EncodedFrame frame = codec.encodeFrame(envelope);
					output.write(frame.bytesView());
					output.flush();
					synchronized (this) {
						queuedByAgent.computeIfPresent(envelope.agentId(), (id, count) -> count <= 1 ? null : count - 1);
					}
				}
			} catch (InterruptedException exception) {
				Thread.currentThread().interrupt();
			} catch (IOException exception) {
				if (open.get()) LOGGER.warn("Codex bridge writer failed: {}", exception.getMessage());
			} finally {
				close();
			}
		}

		@Override
		public void close() {
			boolean wasAuthenticated;
			synchronized (publicationLock) {
				synchronized (this) {
					if (!open.compareAndSet(true, false)) return;
					wasAuthenticated = authenticated.get();
				}
				terminalResults.sessionClosed(this);
				preauthSessions.remove(this);
				if (session == this) {
					verboseState.clearActivity();
					MultiplexedServerBridge.onSessionClosed(observationPublication, this);
					session = null;
					protocolKnownAgentIds.clear();
					coordinatorReadyAgentIds.clear();
					catalogProfiles = Set.of();
					catalogModels = AgentControlCatalog.fallbackOptions();
					catalogLoaded = false;
					catalogDiscoveryPending = false;
					catalogDiscoveryAttempts = 0;
					catalogDiscoveryRetryAtNanos = 0L;
					catalogDiscoveryGeneration = 0L;
					catalogDiscoveryFailureCode = null;
					CoordinatorStatusStore.clear(manager.server());
				}
				if (wasAuthenticated) coordinatorDisconnectPending.set(true);
				publicationLock.notifyAll();
			}
			interruptPeer(readerThread);
			interruptPeer(writerThread);
			try { socket.close(); } catch (IOException ignored) { }
		}

		private static void interruptPeer(Thread thread) {
			if (thread != null && thread != Thread.currentThread()) thread.interrupt();
		}
	}

	private static String readLine(BufferedInputStream input) throws IOException {
		ByteArrayOutputStream bytes = new ByteArrayOutputStream();
		while (true) {
			int value = input.read();
			if (value < 0) return bytes.size() == 0 ? null : bytes.toString(StandardCharsets.UTF_8);
			if (value == '\n') return bytes.toString(StandardCharsets.UTF_8);
			if (value != '\r') bytes.write(value);
			if (bytes.size() > BridgeEnvelopeCodec.MAX_LINE_BYTES) throw new BridgeProtocolException("LINE_TOO_LARGE", "Inbound line exceeds limit");
		}
	}
}
