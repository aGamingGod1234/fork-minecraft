package dev.agaminggod.arenaagents.server;

import dev.agaminggod.arenaagents.agent.AgentDomainException;
import dev.agaminggod.arenaagents.agent.AgentLifecycleState;
import dev.agaminggod.arenaagents.control.AgentControlCatalog;
import dev.agaminggod.arenaagents.control.AgentControlModelOption;
import dev.agaminggod.arenaagents.server.bridge.MultiplexedServerBridge;
import dev.agaminggod.arenaagents.server.bridge.BridgeProtocolException;
import dev.agaminggod.arenaagents.server.bridge.CoordinatorStatusSnapshot;
import dev.agaminggod.arenaagents.server.bridge.CoordinatorStatusStore;
import dev.agaminggod.arenaagents.agent.AgentId;
import dev.agaminggod.arenaagents.server.conversation.DeliveryReceipt;
import dev.agaminggod.arenaagents.server.conversation.ServerAgentConversationRouter;
import dev.agaminggod.arenaagents.server.voice.VoiceSubsystemRuntime;
import dev.agaminggod.arenaagents.server.voice.VoiceConsentRegistry;
import dev.agaminggod.arenaagents.server.voice.VoiceDirector;
import dev.agaminggod.arenaagents.server.goal.GoalVerificationRuntime;
import dev.agaminggod.arenaagents.server.goal.GoalSubmission;
import dev.agaminggod.arenaagents.server.perception.ServerObservationCollector;
import dev.agaminggod.arenaagents.server.runtime.input.AgentInputRuntime;
import dev.agaminggod.arenaagents.scenario.runtime.ScenarioRuntimeService;
import java.util.Map;
import java.util.HashMap;
import java.util.List;
import java.util.Optional;
import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.LongSupplier;
import java.util.function.Supplier;
import java.util.function.Function;
import java.util.UUID;
import net.fabricmc.fabric.api.entity.event.v1.ServerLivingEntityEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerChunkEvents;
import dev.agaminggod.arenaagents.world.WorldMutationRevisionAccess;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.core.registries.BuiltInRegistries;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class CodexAgentServerRuntime {
	private static final Logger LOGGER = LoggerFactory.getLogger(CodexAgentServerRuntime.class);
	private static final Map<MinecraftServer, BridgeSlot> BRIDGE_SLOTS = new ConcurrentHashMap<>();
	private static final Map<MinecraftServer, CoordinatorProcessSupervisor> COORDINATORS = new ConcurrentHashMap<>();
	private static final Map<MinecraftServer, VoiceInitializationGate> VOICE_GATES = new ConcurrentHashMap<>();
	private static final Map<MinecraftServer, Map<String, Long>> PLANNING_UPDATES = new ConcurrentHashMap<>();
	private static final Map<MinecraftServer, GoalVerificationRuntime> GOAL_VERIFIERS = new ConcurrentHashMap<>();
	private static final java.util.Set<MinecraftServer> RESTORED_SERVERS = ConcurrentHashMap.newKeySet();
	private static final long PLANNING_UPDATE_INTERVAL_MS = 30_000L;
	private static final long COORDINATOR_STATUS_MAXIMUM_AGE_MS = 2_500L;
	private static final int MIN_EXPLICIT_SECRET_CHARACTERS = 32;
	private static final int MAX_EXPLICIT_SECRET_CHARACTERS = 512;
	private static boolean registered;

	private CodexAgentServerRuntime() {
	}

	public static void confirmCurrentGoal(MinecraftServer server, AgentId agentId) {
		GoalVerificationRuntime runtime = GOAL_VERIFIERS.get(server);
		if (runtime == null) {
			throw new dev.agaminggod.arenaagents.agent.AgentDomainException(
					"GOAL_VERIFIER_UNAVAILABLE", "Goal verification is not running"
			);
		}
		var goal = CodexAgentManager.get(server).registry().require(agentId).currentGoal()
				.orElseThrow(() -> new dev.agaminggod.arenaagents.agent.AgentDomainException(
						"NO_CURRENT_GOAL", "Agent has no current goal to confirm"
				));
		if (!containsOperatorConfirmation(goal.spec().completion())) {
			throw new dev.agaminggod.arenaagents.agent.AgentDomainException(
					"FACTUAL_GOAL_NOT_CONFIRMABLE", "Minecraft verifies this goal from in-game facts"
			);
		}
		runtime.confirm(agentId, goal.goalId());
	}

	private static boolean containsOperatorConfirmation(dev.agaminggod.arenaagents.agent.goal.GoalPredicate predicate) {
		if (predicate instanceof dev.agaminggod.arenaagents.agent.goal.GoalPredicate.OperatorConfirmed) return true;
		if (predicate instanceof dev.agaminggod.arenaagents.agent.goal.GoalPredicate.AllOf all) {
			return all.predicates().stream().anyMatch(CodexAgentServerRuntime::containsOperatorConfirmation);
		}
		if (predicate instanceof dev.agaminggod.arenaagents.agent.goal.GoalPredicate.AnyOf any) {
			return any.predicates().stream().anyMatch(CodexAgentServerRuntime::containsOperatorConfirmation);
		}
		return false;
	}

	public static synchronized void register() {
		if (registered) {
			return;
		}
		ServerLifecycleEvents.SERVER_STARTED.register(CodexAgentServerRuntime::start);
		ServerChunkEvents.CHUNK_LOAD.register((level, chunk, newChunk) ->
				((WorldMutationRevisionAccess) level).arenaagents$recordWorldMutation(chunk.getPos().getWorldPosition()));
		ServerChunkEvents.CHUNK_UNLOAD.register((level, chunk) ->
				((WorldMutationRevisionAccess) level).arenaagents$recordWorldMutation(chunk.getPos().getWorldPosition()));
		ServerTickEvents.START_SERVER_TICK.register(CodexAgentServerRuntime::startTick);
		ServerTickEvents.END_SERVER_TICK.register(CodexAgentServerRuntime::endTick);
		ServerLifecycleEvents.SERVER_STOPPING.register(CodexAgentServerRuntime::stop);
		ServerLifecycleEvents.END_DATA_PACK_RELOAD.register((server, resourceManager, success) ->
				ServerObservationCollector.clearTagCache());
		ServerPlayConnectionEvents.JOIN.register((handler, sender, server) ->
				VoiceConsentRegistry.playerConnected(server, handler.getPlayer().getUUID()));
		ServerPlayConnectionEvents.DISCONNECT.register((handler, server) ->
				VoiceConsentRegistry.playerDisconnected(server, handler.getPlayer().getUUID()));
		ServerLivingEntityEvents.ALLOW_DEATH.register((entity, source, damageAmount) -> {
			if (!(entity instanceof net.minecraft.server.level.ServerPlayer player)) return true;
			return AgentDeathCapture.allowVanillaDeath(
					ScenarioRuntimeService.recoverParkourDeath(player),
					() -> CodexAgentManager.get(player.level().getServer()).captureDeath(player, source)
			);
		});
		ServerLivingEntityEvents.AFTER_DEATH.register(CodexAgentServerRuntime::recordAttributedKill);
		registered = true;
	}

	private static void start(MinecraftServer server) {
		ServerObservationCollector.clearTagCache();
		CodexAgentManager manager = CodexAgentManager.get(server);
		CoordinatorProcessSupervisor supervisor = COORDINATORS.get(server);
		if (supervisor == null) {
			CoordinatorProcessSupervisor candidate = new CoordinatorProcessSupervisor();
			CoordinatorProcessSupervisor previous = COORDINATORS.putIfAbsent(server, candidate);
			if (previous == null) supervisor = candidate;
			else {
				candidate.close();
				supervisor = previous;
			}
		}
		GoalVerificationRuntime goalVerifier = GOAL_VERIFIERS.computeIfAbsent(server, ignored -> new GoalVerificationRuntime(
				manager.registry(),
				agentId -> manager.findAgentPlayer(agentId).map(dev.agaminggod.arenaagents.server.runtime.GoalCompletionVerifier::minecraftFacts),
				server::getTickCount,
				System::currentTimeMillis,
				AgentSavedData.get(server).killLedger(),
				AgentSavedData.get(server).survivalProgress(),
				AgentSavedData.get(server).operatorConfirmations(),
				manager::validateGoalForActivation
		));
		try {
			tryStartBridge(server, manager, supervisor);
		} catch (RuntimeException exception) {
			LOGGER.error(
					"Codex agent bridge is unavailable; summoned agents will remain locally controllable but autonomous planning is disabled",
					exception
			);
		}
	}

	private static void tryStartBridge(
			MinecraftServer server,
			CodexAgentManager manager,
			CoordinatorProcessSupervisor supervisor
	) {
		BridgeSlot slot = BRIDGE_SLOTS.computeIfAbsent(server, ignored -> new BridgeSlot(System::currentTimeMillis));
		reconcileBridgeConfiguration(slot, manager, supervisor);
	}

	static void reconcileBridgeConfiguration(
			BridgeSlot slot,
			CodexAgentManager manager,
			CoordinatorProcessSupervisor supervisor
	) {
		java.util.Objects.requireNonNull(slot, "bridge slot must not be null");
		java.util.Objects.requireNonNull(manager, "manager must not be null");
		String preparedSecret = supervisor == null ? null : supervisor.bridgeSecret();
		if (preparedSecret != null) {
			int port = supervisor.bridgePort();
			slot.reconcile(supervisor.bridgeRevision(), () -> {
				GoalVerificationRuntime goalVerifier = goalVerification(manager);
				return goalVerifier == null
						? MultiplexedServerBridge.withPreparedSecret(manager, port, preparedSecret, verboseState(manager))
						: MultiplexedServerBridge.withPreparedSecret(
								manager, port, preparedSecret, verboseState(manager), goalVerifier);
			});
			return;
		}
		if (supervisor != null && supervisor.snapshot().state() == CoordinatorRecoveryState.STOPPED) {
			slot.reconcile(explicitBridgeRevision(), () -> {
				GoalVerificationRuntime goalVerifier = goalVerification(manager);
				return goalVerifier == null
						? new MultiplexedServerBridge(manager, verboseState(manager))
						: new MultiplexedServerBridge(manager, verboseState(manager), goalVerifier);
			});
		}
	}

	private static AgentVerboseState verboseState(CodexAgentManager manager) {
		MinecraftServer server = manager.server();
		return server == null ? new AgentVerboseState() : AgentVerboseState.forServer(server);
	}

	private static GoalVerificationRuntime goalVerification(CodexAgentManager manager) {
		MinecraftServer server = manager.server();
		return server == null ? null : GOAL_VERIFIERS.get(server);
	}

	private static long explicitBridgeRevision() {
		String configuredPath = System.getProperty("arenaagents.bridgeSecretFile");
		if (configuredPath == null || configuredPath.isBlank()) configuredPath = System.getenv("ARENA_AGENT_BRIDGE_SECRET_FILE");
		String port = System.getProperty("arenaagents.bridgePort", Integer.toString(MultiplexedServerBridge.DEFAULT_PORT));
		return java.util.Objects.hash(port, explicitSecretFileRevision(configuredPath, Path.of("runtime", "bridge-secret.txt")));
	}

	private static long explicitSecretFileRevision(String configuredPath, Path fallbackPath) {
		try {
			Path path = configuredPath == null || configuredPath.isBlank()
					? fallbackPath
					: Path.of(configuredPath);
			Path normalized = path.toAbsolutePath().normalize();
			return java.util.Objects.hash(
					normalized, Files.size(normalized), Files.getLastModifiedTime(normalized).toMillis(),
					explicitSecretContentFingerprint(normalized)
			);
		} catch (java.io.IOException | RuntimeException unavailable) {
			return java.util.Objects.hash(
					configuredPath == null || configuredPath.isBlank() ? fallbackPath.toString() : configuredPath,
					unavailable.getClass().getName()
			);
		}
	}

	static String explicitSecretContentFingerprint(Path path) throws IOException {
		String secret = readAcceptedExplicitSecret(path);
		try {
			byte[] digest = MessageDigest.getInstance("SHA-256")
					.digest(secret.getBytes(StandardCharsets.UTF_8));
			return HexFormat.of().formatHex(digest);
		} catch (NoSuchAlgorithmException unavailable) {
			throw new IllegalStateException("SHA-256 is unavailable", unavailable);
		}
	}

	private static String readAcceptedExplicitSecret(Path path) throws IOException {
		StringBuilder content = new StringBuilder(MAX_EXPLICIT_SECRET_CHARACTERS);
		StringBuilder pendingWhitespace = new StringBuilder(MAX_EXPLICIT_SECRET_CHARACTERS + 1);
		boolean pendingWhitespaceOverflow = false;
		try (Reader reader = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
			char[] buffer = new char[256];
			for (int read; (read = reader.read(buffer)) >= 0; ) {
				for (int index = 0; index < read; index++) {
					char character = buffer[index];
					if (content.isEmpty() && character <= ' ') continue;
					if (character <= ' ') {
						if (pendingWhitespace.length() <= MAX_EXPLICIT_SECRET_CHARACTERS) {
							pendingWhitespace.append(character);
						} else {
							pendingWhitespaceOverflow = true;
						}
						continue;
					}
					if (pendingWhitespaceOverflow
							|| content.length() + pendingWhitespace.length() + 1 > MAX_EXPLICIT_SECRET_CHARACTERS) {
						throw new IOException("Bridge secret exceeds the accepted character limit");
					}
					content.append(pendingWhitespace).append(character);
					pendingWhitespace.setLength(0);
				}
			}
		}
		if (content.length() < MIN_EXPLICIT_SECRET_CHARACTERS) {
			throw new IOException("Bridge secret is shorter than the accepted character limit");
		}
		return content.toString();
	}

	static void reconcilePreparedBridge(
			BridgeSlot slot,
			long revision,
			String preparedSecret,
			Function<String, MultiplexedServerBridge> factory
	) {
		java.util.Objects.requireNonNull(slot, "bridge slot must not be null");
		java.util.Objects.requireNonNull(factory, "bridge factory must not be null");
		if (preparedSecret != null) slot.reconcile(revision, () -> factory.apply(preparedSecret));
	}

	private static void startTick(MinecraftServer server) {
		if (!RESTORED_SERVERS.contains(server)) return;
		MultiplexedServerBridge bridge = bridge(server);
		if (bridge != null) bridge.startTick();
	}

	private static void endTick(MinecraftServer server) {
		CodexAgentManager manager = CodexAgentManager.get(server);
		CoordinatorProcessSupervisor supervisor = COORDINATORS.get(server);
		MultiplexedServerBridge bridge = bridge(server);
		if (supervisor != null) {
			tryStartBridge(server, manager, supervisor);
			bridge = bridge(server);
			boolean coordinatorReady = bridge != null && bridge.authenticated()
					&& CoordinatorStatusStore.latest(server)
							.map(status -> coordinatorStatusReady(status, System.currentTimeMillis()))
							.orElse(false);
			supervisor.tickWithBridgeListener(
					bridge != null && bridge.authenticated(),
					bridge == null ? null : bridge.authenticatedLaunchId(),
					bridge == null ? 0L : bridge.authenticatedSessionGeneration(),
					coordinatorReady,
					bridge != null
			);
			tryStartBridge(server, manager, supervisor);
			bridge = bridge(server);
			reconcileVoice(server, supervisor);
		}
		MultiplexedServerBridge activeBridge = bridge;
		boolean restored = ScenarioRuntimeService.restorePersistedState(server);
		if (restored) RESTORED_SERVERS.add(server);
		runRestoredStateTick(restored, () -> {
			manager.reconcileDeaths();
			manager.maintainChunkTickets();
			VoiceSubsystemRuntime.tick(server);
			VoiceDirector.tick(server);
			maintainPlanningProgress(manager);
			if (activeBridge != null) activeBridge.endTick();
			verifyGoals(server, manager);
			AgentInputRuntime.tick(server);
			SkitModeRuntime.tick(server);
			ScenarioRuntimeService.tick(server);
		});
	}

	static boolean runRestoredStateTick(boolean restored, Runnable activeRuntimeTick) {
		java.util.Objects.requireNonNull(activeRuntimeTick, "active runtime tick must not be null");
		if (!restored) return false;
		activeRuntimeTick.run();
		return true;
	}

	private static void maintainPlanningProgress(CodexAgentManager manager) {
		long now = System.currentTimeMillis();
		Map<String, Long> lastUpdates = PLANNING_UPDATES.computeIfAbsent(manager.server(), ignored -> new HashMap<>());
		java.util.Set<String> planning = new java.util.HashSet<>();
		for (var record : manager.records()) {
			if (record.state() != AgentLifecycleState.PLANNING) continue;
			String agentId = record.agentId().toString();
			planning.add(agentId);
			long last = lastUpdates.getOrDefault(agentId, record.updatedAtEpochMs());
			if (now - last >= PLANNING_UPDATE_INTERVAL_MS) {
				AgentChatReporter.stillPlanning(manager, record);
				lastUpdates.put(agentId, now);
			}
		}
		lastUpdates.keySet().retainAll(planning);
	}

	public static boolean automationAvailable(MinecraftServer server) {
		MultiplexedServerBridge bridge = bridge(server);
		return bridge != null && bridge.authenticated();
	}

	public static void setVerbose(MinecraftServer server, boolean enabled) {
		AgentVerboseState state = AgentVerboseState.forServer(server);
		state.setEnabled(enabled);
		MultiplexedServerBridge bridge = bridge(server);
		if (bridge != null) bridge.setVerbose(enabled);
	}

	public static String automationStatus(MinecraftServer server) {
		CoordinatorProcessSupervisor supervisor = COORDINATORS.get(server);
		if (supervisor != null && supervisor.failureCode() != null) {
			return startupFailureStatus(supervisor.failureCode());
		}
		MultiplexedServerBridge bridge = bridge(server);
		if (bridge == null) {
			return "Automation is offline. Restart Minecraft after checking the bridge setup.";
		}
		return bridge.authenticated() ? "Automation ready" : "Waiting for the agent coordinator...";
	}

	private static String startupFailureStatus(String code) {
		if (code.startsWith("NODE_RUNTIME")) {
			return "Automation is offline. Node.js 22+ was not found; set -Darenaagents.nodePath to an absolute executable or install the bundled profile runtime.";
		}
		if ("BRIDGE_SECRET_PATH_CONFLICT".equals(code)) {
			return "Automation is offline. Bridge and voice secret paths must point to the prepared runtime secret.";
		}
		if ("COORDINATOR_RESTART_EXHAUSTED".equals(code)) {
			return "Automation is offline. The coordinator stopped repeatedly; check the coordinator error log and restart Minecraft.";
		}
		return "Automation is offline. Restart Minecraft after checking the coordinator setup.";
	}

	public static List<AgentControlModelOption> modelCatalog(MinecraftServer server) {
		MultiplexedServerBridge bridge = bridge(server);
		return bridge == null ? AgentControlCatalog.fallbackOptions() : bridge.catalogModels();
	}

	public static void requireAutomation(MinecraftServer server) {
		if (!automationAvailable(server)) {
			throw new AgentDomainException("AUTOMATION_UNAVAILABLE", automationStatus(server));
		}
	}

	private static void verifyGoals(MinecraftServer server, CodexAgentManager manager) {
		GoalVerificationRuntime goalVerifier = GOAL_VERIFIERS.get(server);
		if (goalVerifier == null) return;
		for (var transition : goalVerifier.tick()) {
			transition.after().currentGoal().flatMap(dev.agaminggod.arenaagents.agent.AgentGoal::evidence)
					.ifPresent(evidence -> reportProactiveGoalVerification(manager, transition.after(), evidence));
		}
	}

	public static GoalSubmission submitGoal(
			MinecraftServer server,
			String selector,
			String prompt,
			ServerLevel sourceLevel,
			Optional<UUID> requestingPlayerId,
			GoalSubmission.Operation operation
	) {
		requireAutomation(server);
		MultiplexedServerBridge bridge = bridge(server);
		if (bridge == null) throw new AgentDomainException("AUTOMATION_UNAVAILABLE", automationStatus(server));
		return bridge.submitGoal(selector, prompt, sourceLevel, requestingPlayerId, operation);
	}

	public static DeliveryReceipt sendDirectMessage(
			MinecraftServer server,
			ServerPlayer source,
			AgentId recipientAgentId,
			String text
	) {
		requireAutomation(server);
		MultiplexedServerBridge bridge = bridge(server);
		if (bridge == null) throw new AgentDomainException("AUTOMATION_UNAVAILABLE", automationStatus(server));
		return bridge.sendPlayerDirectMessage(source, recipientAgentId, text);
	}

	public static DeliveryReceipt sendNativeDirectMessage(
			MinecraftServer server,
			ServerPlayer source,
			AgentId recipientAgentId,
			String text
	) {
		requireAutomation(server);
		MultiplexedServerBridge bridge = bridge(server);
		if (bridge == null) throw new AgentDomainException("AUTOMATION_UNAVAILABLE", automationStatus(server));
		return bridge.sendNativePlayerDirectMessage(source, recipientAgentId, text);
	}

	public static DeliveryReceipt deliverHumanSpeech(
			MinecraftServer server,
			UUID sourcePlayerId,
			String transcript,
			boolean whispering
	) {
		return captureHumanSpeechAudience(server, sourcePlayerId, whispering)
				.map(audience -> deliverHumanSpeech(server, audience, transcript))
				.orElseGet(() -> new DeliveryReceipt(List.of(), List.of()));
	}

	public static Optional<ServerAgentConversationRouter.ProximitySpeechAudience> captureHumanSpeechAudience(
			MinecraftServer server,
			UUID sourcePlayerId,
			boolean whispering
	) {
		if (!VoiceConsentRegistry.granted(server, sourcePlayerId)) {
			return Optional.empty();
		}
		MultiplexedServerBridge bridge = bridge(server);
		if (bridge == null || !bridge.authenticated()) return Optional.empty();
		ServerPlayer source = server.getPlayerList().getPlayer(sourcePlayerId);
		if (source == null) return Optional.empty();
		for (var record : CodexAgentManager.get(server).records()) {
			if (record.entityUuid().filter(sourcePlayerId::equals).isPresent()) {
				return Optional.empty();
			}
		}
		try {
			return Optional.of(bridge.capturePlayerProximitySpeechAudience(source, whispering));
		} catch (AgentDomainException exception) {
			if (exception.code().equals("COORDINATOR_DISCONNECTED")) return Optional.empty();
			throw exception;
		}
	}

	public static DeliveryReceipt deliverHumanSpeech(
			MinecraftServer server,
			ServerAgentConversationRouter.ProximitySpeechAudience audience,
			String transcript
	) {
		if (!VoiceConsentRegistry.granted(server, audience.sourcePlayerId())) {
			return new DeliveryReceipt(List.of(), List.of());
		}
		MultiplexedServerBridge bridge = bridge(server);
		if (bridge == null || !bridge.authenticated()) return new DeliveryReceipt(List.of(), List.of());
		try {
			return bridge.sendPlayerProximitySpeech(audience, transcript);
		} catch (AgentDomainException exception) {
			if (exception.code().equals("COORDINATOR_DISCONNECTED")) {
				return new DeliveryReceipt(List.of(), List.of());
			}
			throw exception;
		}
	}

	public static boolean hasVoiceConsent(MinecraftServer server, UUID playerId) {
		return VoiceConsentRegistry.granted(server, playerId);
	}

	private static void reportProactiveGoalVerification(
			CodexAgentManager manager,
			dev.agaminggod.arenaagents.agent.AgentRecord record,
			dev.agaminggod.arenaagents.agent.goal.GoalEvidence evidence
	) {
		AgentVerboseState verbose = AgentVerboseState.forServer(manager.server());
		if (!verbose.goalVerificationChanged(record.agentId(), record.goalRevision(), true, evidence.facts())) return;
		AgentChatReporter.goalVerified(manager, record, evidence.facts());
		String detail = evidence.facts().isEmpty()
				? "Goal verified."
				: "Goal verified: " + evidence.facts().getFirst().expectedValue() + ".";
		AgentVerboseChat.report(manager, verbose, record, "result", detail);
	}

	private static void recordAttributedKill(net.minecraft.world.entity.LivingEntity entity, net.minecraft.world.damagesource.DamageSource source) {
		if (!(source.getEntity() instanceof ServerPlayer responsible)) return;
		MinecraftServer server = responsible.level().getServer();
		GoalVerificationRuntime runtime = GOAL_VERIFIERS.get(server);
		if (runtime == null) return;
		for (var record : CodexAgentManager.get(server).records()) {
			if (record.entityUuid().filter(responsible.getUUID()::equals).isEmpty()) continue;
			runtime.recordKill(record.agentId(), BuiltInRegistries.ENTITY_TYPE.getKey(entity.getType()).toString());
			return;
		}
	}

	private static void stop(MinecraftServer server) {
		ServerObservationCollector.clearTagCache();
		PLANNING_UPDATES.remove(server);
		GOAL_VERIFIERS.remove(server);
		VOICE_GATES.remove(server);
		RESTORED_SERVERS.remove(server);
		CoordinatorProcessSupervisor supervisor = COORDINATORS.remove(server);
		BridgeSlot bridgeSlot = BRIDGE_SLOTS.remove(server);
		try {
			VoiceDirector.release(server);
			VoiceSubsystemRuntime.close(server);
			VoiceConsentRegistry.clear(server);
			CodexAgentManager.release(server);
		} finally {
			ScenarioRuntimeService.release(server);
			SkitModeRuntime.release(server);
			if (bridgeSlot != null) bridgeSlot.close();
			if (supervisor != null) supervisor.close();
			AgentVerboseState.release(server);
		}
	}

	/** Keeps voice fenced unless the effective secret and configuration are prepared. */
	private static void reconcileVoice(MinecraftServer server, CoordinatorProcessSupervisor supervisor) {
		if (supervisor == null) return;
		boolean prepared = voiceConfigurationPrepared(supervisor);
		VoiceInitializationGate gate = VOICE_GATES.get(server);
		if (gate == null) {
			if (!prepared) return;
			gate = VOICE_GATES.computeIfAbsent(server, ignored ->
					new VoiceInitializationGate(
							() -> VoiceSubsystemRuntime.start(server),
							() -> VoiceSubsystemRuntime.close(server)
					)
			);
		}
		try {
			gate.reconcile(prepared, prepared ? voiceConfigurationRevision(supervisor) : 0L);
		} catch (RuntimeException failure) {
			LOGGER.warn("Voice subsystem reconciliation will retry after coordinator paths are prepared", failure);
		}
	}

	static boolean coordinatorStatusReady(CoordinatorStatusSnapshot status, long nowEpochMs) {
		return status != null && status.reconciled()
				&& status.fresh(nowEpochMs, COORDINATOR_STATUS_MAXIMUM_AGE_MS);
	}

	static boolean voiceConfigurationPrepared(CoordinatorProcessSupervisor supervisor) {
		if (supervisor == null) return false;
		if (supervisor.configured()) return true;
		return supervisor.snapshot().state() == CoordinatorRecoveryState.STOPPED
				&& acceptedSecretFile(configuredVoiceSecretFile(), Path.of("runtime", "voice-secret.txt"));
	}

	static long voiceConfigurationRevision(CoordinatorProcessSupervisor supervisor) {
		long explicitSecretRevision = supervisor.snapshot().state() == CoordinatorRecoveryState.STOPPED
				? explicitSecretFileRevision(configuredVoiceSecretFile(), Path.of("runtime", "voice-secret.txt"))
				: 0L;
		return java.util.Objects.hash(
				supervisor.voiceConfigurationRevision(), supervisor.sharedSecretRevision(), supervisor.secretPath(),
				configuredVoiceSecretFile(), explicitSecretRevision, System.getProperty("arenaagents.voiceUrl")
		);
	}

	private static String configuredVoiceSecretFile() {
		return System.getProperty("arenaagents.voiceSecretFile", "runtime/voice-secret.txt");
	}

	private static boolean acceptedSecretFile(String configuredPath, Path fallbackPath) {
		try {
			Path path = configuredPath == null ? fallbackPath : Path.of(configuredPath);
			readAcceptedExplicitSecret(path);
			return true;
		} catch (IOException | RuntimeException unavailable) {
			return false;
		}
	}

	/** Small lifecycle seam that keeps unprepared startup from permanently selecting NoVoice. */
	static final class VoiceInitializationGate {
		private static final long UNINITIALIZED = Long.MIN_VALUE;
		private final Runnable starter;
		private final Runnable closer;
		private long activeRevision = UNINITIALIZED;

		VoiceInitializationGate(Runnable starter, Runnable closer) {
			this.starter = java.util.Objects.requireNonNull(starter, "voice starter must not be null");
			this.closer = java.util.Objects.requireNonNull(closer, "voice closer must not be null");
		}

		synchronized boolean reconcile(boolean prepared, long desiredRevision) {
			if (!prepared) {
				if (activeRevision == UNINITIALIZED) return false;
				activeRevision = UNINITIALIZED;
				closer.run();
				return true;
			}
			if (activeRevision == desiredRevision) return false;
			if (activeRevision != UNINITIALIZED) {
				activeRevision = UNINITIALIZED;
				closer.run();
			}
			starter.run();
			activeRevision = desiredRevision;
			return true;
		}
	}

	private static MultiplexedServerBridge bridge(MinecraftServer server) {
		BridgeSlot slot = BRIDGE_SLOTS.get(server);
		return slot == null ? null : slot.bridge();
	}

	static final class BridgeSlot implements AutoCloseable {
		private final BridgeRetry retry;
		private MultiplexedServerBridge bridge;
		private long activeRevision = Long.MIN_VALUE;
		private long attemptedRevision = Long.MIN_VALUE;
		private boolean closed;

		BridgeSlot(LongSupplier clock) {
			retry = new BridgeRetry(clock);
		}

		synchronized void reconcile(long desiredRevision, Supplier<MultiplexedServerBridge> factory) {
			java.util.Objects.requireNonNull(factory, "bridge factory must not be null");
			if (closed || bridge != null && activeRevision == desiredRevision) return;
			if (bridge != null) {
				bridge.closeAndDrainDisconnect();
				bridge = null;
				activeRevision = Long.MIN_VALUE;
			}
			if (attemptedRevision == desiredRevision && !retry.canAttempt()) return;
			attemptedRevision = desiredRevision;
			MultiplexedServerBridge candidate = null;
			try {
				candidate = java.util.Objects.requireNonNull(factory.get(), "bridge factory returned no bridge");
				candidate.start();
				bridge = candidate;
				activeRevision = desiredRevision;
				retry.recordSuccess();
			} catch (RuntimeException exception) {
				if (candidate != null) candidate.close();
				String code = exception instanceof BridgeProtocolException protocol
						? protocol.code()
						: "JAVA_BRIDGE_START_FAILED";
				retry.recordFailure(code, exception.getMessage());
			}
		}

		synchronized MultiplexedServerBridge bridge() {
			return bridge;
		}

		BridgeRetry retry() {
			return retry;
		}

		@Override
		public synchronized void close() {
			if (closed) return;
			closed = true;
			if (bridge != null) bridge.close();
			bridge = null;
		}
	}

	static final class BridgeRetry {
		private final LongSupplier clock;
		private final CoordinatorLaunchPolicy.RestartBudget budget = new CoordinatorLaunchPolicy.RestartBudget();
		private long nextRetryEpochMs;
		private String failureCode;
		private String failureMessage;

		BridgeRetry(LongSupplier clock) {
			this.clock = java.util.Objects.requireNonNull(clock, "clock must not be null");
		}

		synchronized boolean canAttempt() {
			return clock.getAsLong() >= nextRetryEpochMs;
		}

		synchronized void recordSuccess() {
			budget.resetAfterStability();
			nextRetryEpochMs = 0L;
			failureCode = null;
			failureMessage = null;
		}

		synchronized void recordFailure(String code, String message) {
			budget.recordUnexpectedExit();
			nextRetryEpochMs = clock.getAsLong() + budget.nextDelayMs();
			failureCode = code == null || code.isBlank() ? "JAVA_BRIDGE_START_FAILED" : code;
			failureMessage = message;
		}

		synchronized String failureCode() { return failureCode; }
		synchronized String failureMessage() { return failureMessage; }
		synchronized long nextRetryEpochMs() { return nextRetryEpochMs; }
	}
}
