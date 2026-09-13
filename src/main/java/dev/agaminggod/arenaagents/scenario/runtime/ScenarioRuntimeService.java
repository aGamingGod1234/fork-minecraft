package dev.agaminggod.arenaagents.scenario.runtime;

import dev.agaminggod.arenaagents.agent.AgentDomainException;
import dev.agaminggod.arenaagents.agent.AgentIdentity;
import dev.agaminggod.arenaagents.agent.AgentLifecycleState;
import dev.agaminggod.arenaagents.agent.AgentRecord;
import dev.agaminggod.arenaagents.scenario.ScenarioAgentEvent;
import dev.agaminggod.arenaagents.scenario.ScenarioAgentSpec;
import dev.agaminggod.arenaagents.scenario.ScenarioCompletionPolicy;
import dev.agaminggod.arenaagents.scenario.ScenarioLaunchRequest;
import dev.agaminggod.arenaagents.scenario.ScenarioParticipant;
import dev.agaminggod.arenaagents.scenario.ScenarioPlacementMode;
import dev.agaminggod.arenaagents.scenario.ScenarioPreset;
import dev.agaminggod.arenaagents.scenario.ScenarioPresets;
import dev.agaminggod.arenaagents.scenario.ScenarioSession;
import dev.agaminggod.arenaagents.scenario.ScenarioSessionConfig;
import dev.agaminggod.arenaagents.scenario.ScenarioSpawn;
import dev.agaminggod.arenaagents.scenario.ScenarioSpawnAllocator;
import dev.agaminggod.arenaagents.scenario.presentation.ArenaSpectatorSnapshot;
import dev.agaminggod.arenaagents.scenario.presentation.ScenarioBuildProgress;
import dev.agaminggod.arenaagents.scenario.presentation.ScenarioOperatorMessagePolicy;
import dev.agaminggod.arenaagents.scenario.result.MatchResultV1;
import dev.agaminggod.arenaagents.scenario.result.MatchResultWriter;
import dev.agaminggod.arenaagents.scenario.result.ScenarioPublicEvent;
import dev.agaminggod.arenaagents.scenario.result.ScenarioPublicFormatter;
import dev.agaminggod.arenaagents.scenario.result.ScenarioResultPersistence;
import dev.agaminggod.arenaagents.server.CodexAgentManager;
import dev.agaminggod.arenaagents.server.CodexAgentServerRuntime;
import dev.agaminggod.arenaagents.server.GoalControl;
import dev.agaminggod.arenaagents.server.OfflineAgentPlayers;
import dev.agaminggod.arenaagents.server.bridge.CoordinatorStatusStore;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.game.ClientboundSetActionBarTextPacket;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.FallingBlock;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.storage.LevelData;
import net.minecraft.world.level.GameType;
import net.minecraft.world.Container;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.WeakHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class ScenarioRuntimeService {
	private static final Logger LOGGER = LoggerFactory.getLogger(ScenarioRuntimeService.class);
	private static final long ROSTER_READY_TIMEOUT_TICKS = 240L;
	private static final int PUBLIC_EVENT_LIMIT = 4_096;
	private static final long CONFIRMATION_TTL_TICKS = 20L * 60L;
	private static final long TERMINAL_BUILD_TTL_TICKS = 20L * 60L;
	private static final int MAX_EVACUATION_OCCUPANTS = 512;
	private static final int MAX_EVACUATION_COLUMNS = 4_096;
	private static final Map<MinecraftServer, RuntimeState> STATES = new WeakHashMap<>();

	private ScenarioRuntimeService() {
	}

	public static synchronized void launch(ServerPlayer operator, ScenarioLaunchRequest request) {
		if (!GoalControl.mayControl(operator.createCommandSourceStack())) {
			throw new IllegalStateException("You do not have permission to launch Arena Agents scenarios");
		}
		MinecraftServer server = operator.level().getServer();
		RuntimeState state = STATES.computeIfAbsent(server, ignored -> new RuntimeState());
		if (state.build != null || state.pendingActivation != null || state.activation != null
				|| state.preparationSnapshot != null) {
			throw new IllegalStateException("An arena is already being prepared");
		}
		if (state.activeRun != null || state.pendingResult != null) {
			throw new IllegalStateException("A scenario is already running");
		}
		CodexAgentManager manager = CodexAgentManager.get(server);
		manager.registry().requireCapacity(request.roster().size());
		request = reserveScenarioPublicNames(request, manager, server);
		ScenarioPreset preset = ScenarioPresets.require(request.scenarioId());
		ServerLevel level = operator.level();
		BlockPos origin = arenaOrigin(level, preset, operator, request.placementMode());
		long now = System.currentTimeMillis();
		long worldSeed = scenarioSeed(level, preset);
		List<ScenarioParticipant> participants = request.roster().stream()
				.sorted(Comparator.comparingInt(ScenarioAgentSpec::slot))
				.map(agent -> new ScenarioParticipant(
						"slot-" + agent.slot(),
						agent.displayName(),
						agent.team()
				))
				.toList();
		ScenarioSessionConfig config = new ScenarioSessionConfig(
				UUID.randomUUID(),
				preset,
				worldSeed,
				worldSeed ^ 0x6A09E667F3BCC909L,
				preset.defaultDurationTicks(),
				request.deterministicEvents(),
				participants,
				now
		);
		ScenarioArenaBlueprint blueprint = ScenarioArenaBlueprint.create(preset, origin, participants.size());
		SiteInspection inspection = inspectSite(level, blueprint);
		if (!inspection.verdict.allowedWithConfirmation()) {
			throw new IllegalStateException(inspection.verdict.code());
		}
		String launchIntent = launchIntent(request);
		if (request.confirmationToken().isEmpty()) {
			String token = UUID.randomUUID().toString();
			state.pendingConfirmation = new PendingSiteConfirmation(
					token, operator.getUUID(), launchIntent, origin, inspection.occupantIds,
					state.runtimeTick + CONFIRMATION_TTL_TICKS);
			state.buildProgress = ScenarioBuildProgress.confirmationRequired(
					config.sessionId().toString(), preset.title(), state.nextBuildRevision(),
					origin.getX(), origin.getY(), origin.getZ(), confirmationDetail(blueprint, inspection), token);
			return;
		}
		PendingSiteConfirmation confirmation = state.pendingConfirmation;
		if (confirmation == null || state.runtimeTick > confirmation.expiresAtTick
				|| !confirmation.token.equals(request.confirmationToken())
				|| !confirmation.operatorId.equals(operator.getUUID())
				|| !confirmation.launchIntent.equals(launchIntent)
				|| !confirmation.origin.equals(origin)
				|| !confirmation.occupantIds.equals(inspection.occupantIds)) {
			throw new IllegalStateException("SITE_CONFIRMATION_EXPIRED");
		}
		state.pendingConfirmation = null;
		CodexAgentManager.get(server).registry().requireCapacity(request.roster().size());
		ScenarioPreparationJournal journal = new ScenarioPreparationJournal(server.getServerDirectory());
		ScenarioPreparationJournal.Snapshot preparation = new ScenarioPreparationJournal.Snapshot(
				config.sessionId(), operator.getUUID(), level.dimension().identifier().toString(), origin,
				new ScenarioLaunchRequest(request.scenarioId(), request.mapVersion(), request.deterministicEvents(),
						request.placementMode(), request.roster()),
				config.worldSeed(), config.eventSeed(), config.createdAtEpochMs(), List.of(), false);
		try {
			journal.write(preparation);
		} catch (java.io.IOException exception) {
			throw new IllegalStateException("PREPARATION_JOURNAL_UNAVAILABLE", exception);
		}
		state.preparationJournal = journal;
		state.preparationSnapshot = preparation;
		ScenarioSavedData.get(server).clear();
		ScenarioSavedData.saveNow(server);
		evacuateSite(level, blueprint.siteBounds());
		removeContestants(manager, state.agents);
		state.agents = List.of();
		state.participantByAgent.clear();
		state.publicEvents.clear();
		state.build = new BuildJob(
				operator.getUUID(),
				level,
				request,
				config,
				new ScenarioSession(config),
				blueprint,
				new ScenarioArenaResetJob(blueprint),
				null,
				-1,
				false
		);
		state.buildProgress = new ScenarioBuildProgress(
				config.sessionId().toString(), preset.title(), "canonicalizing", state.nextBuildRevision(),
				0, state.build.reset().totalPlacements(), 0,
				origin.getX(), origin.getY(), origin.getZ(), ScenarioBuildProgress.Status.BUILDING,
				"Preparing the arena blueprint at " + coordinates(origin)
		);
		publishOperatorNotice(operator, ScenarioOperatorMessagePolicy.Event.PREPARING,
				"Preparing " + preset.title() + " at " + coordinates(origin) + ". "
						+ blueprint.placements().size() + " blueprint blocks will be checked; "
						+ "construction is happening at this location."
		);
	}

	private static ScenarioLaunchRequest reserveScenarioPublicNames(
			ScenarioLaunchRequest request,
			CodexAgentManager manager,
			MinecraftServer server
	) {
		ArrayList<String> unavailableNames = new ArrayList<>();
		manager.records().stream().map(manager::displayName).forEach(unavailableNames::add);
		server.getPlayerList().getPlayers().stream()
				.map(player -> player.getGameProfile().name())
				.forEach(unavailableNames::add);
		ArrayList<ScenarioAgentSpec> roster = new ArrayList<>(request.roster().size());
		for (ScenarioAgentSpec agent : request.roster()) {
			String publicName = AgentIdentity.allocatePublicName(agent.displayName(), unavailableNames);
			unavailableNames.add(publicName);
			roster.add(new ScenarioAgentSpec(
					agent.slot(), publicName, agent.provider(), agent.model(), agent.reasoning(),
					agent.serviceTier(), agent.team(), agent.gameMode()));
		}
		return new ScenarioLaunchRequest(
				request.scenarioId(), request.mapVersion(), request.deterministicEvents(),
				request.placementMode(), roster, request.confirmationToken());
	}

	public static synchronized void tick(MinecraftServer server) {
		RuntimeState state = STATES.get(server);
		if (state == null) return;
		state.runtimeTick++;
		if (state.pendingConfirmation != null
				&& state.runtimeTick > state.pendingConfirmation.expiresAtTick) {
			state.pendingConfirmation = null;
			state.buildProgress = null;
		}
		if (state.buildProgress != null && state.buildProgress.terminal()
				&& state.buildProgressTerminalTick > 0L
				&& state.runtimeTick - state.buildProgressTerminalTick >= TERMINAL_BUILD_TTL_TICKS) {
			state.buildProgress = null;
			state.buildProgressTerminalTick = 0L;
		}
		if (!state.cancelCleanupIds.isEmpty()) {
			if (removeBoundAgentsStrict(CodexAgentManager.get(server), state.cancelCleanupIds)) {
				state.cancelCleanupIds = List.of();
				clearPreparationJournal(state);
			}
			return;
		}
		if (state.pendingResult != null) {
			tickPendingResult(state, server);
			return;
		}
		if (state.recovery != null) {
			boolean coordinatorReady = ScenarioRecoveryGate.coordinatorReady(
					CoordinatorStatusStore.latest(server),
					state.recovery.snapshot.boundAgentIds(),
					System.currentTimeMillis(),
					ScenarioPreflight.STATUS_MAXIMUM_AGE_MS
			);
			tickRecovery(state, coordinatorReady);
			return;
		}
		if (state.build != null) {
			tickBuild(state);
			return;
		}
		if (state.pendingActivation != null) {
			tickPendingActivation(state);
			return;
		}
		if (state.activation != null) {
			tickActivation(state);
			return;
		}
		tickActive(state);
	}

	/** Read-only, public-field projection for the spectator presentation publisher. */
	public static synchronized Optional<ArenaSpectatorSnapshot.PublicView> spectatorView(MinecraftServer server) {
		Objects.requireNonNull(server, "server must not be null");
		RuntimeState state = STATES.get(server);
		if (state == null) return Optional.empty();
		if (state.pendingResult != null) {
			ScenarioRunSnapshot snapshot = state.pendingResult.snapshot;
			return Optional.of(publicViewFromSnapshot(server, state, snapshot, true));
		}
		if (state.activeRun != null) {
			ActiveRun run = state.activeRun;
			ScenarioSessionConfig config = run.session.config();
			long elapsedTick = Math.max(0L, run.clock.snapshot().elapsedTick());
			String phaseTitle = config.preset().phaseAt(Math.min(elapsedTick, config.durationTicks() - 1L))
					.map(dev.agaminggod.arenaagents.scenario.ScenarioPhase::title)
					.orElse(run.session.state().name().toLowerCase(java.util.Locale.ROOT));
			return Optional.of(new ArenaSpectatorSnapshot.PublicView(
					config.sessionId().toString(),
					config.preset().id(),
					config.preset().title(),
					phaseTitle,
					elapsedTick,
					config.durationTicks(),
					false,
					config.preset().mapVersion(),
					config.worldSeed(),
					config.eventSeed(),
					"",
					run.origin.getX(), run.origin.getY(), run.origin.getZ(),
					publicParticipants(
							server,
							config.participants(),
							run.session.scores(),
							state.participantByAgent,
							run.origin,
							Map.of()
					),
					state.publicEvents
			));
		}
		if (state.recovery != null) {
			return Optional.of(publicViewFromSnapshot(server, state, state.recovery.snapshot, false));
		}
		if (state.activation != null) {
			ActivationJob activation = state.activation;
			Map<String, String> providers = new LinkedHashMap<>();
			for (PendingContestant contestant : activation.contestants) {
				providers.put("slot-" + contestant.spec.slot(), contestant.spec.provider());
			}
			return Optional.of(publicViewFromConfig(
					server, activation.config, activation.origin, "Waiting for contestants",
					activation.session.scores(), state, providers
			));
		}
		if (state.pendingActivation != null) {
			BuildJob build = state.pendingActivation;
			Map<String, String> providers = new LinkedHashMap<>();
			for (ScenarioAgentSpec spec : build.request.roster()) providers.put("slot-" + spec.slot(), spec.provider());
			return Optional.of(publicViewFromConfig(
					server, build.config, build.blueprint.origin(), "Waiting for coordinator",
					build.session.scores(), state, providers
			));
		}
		if (state.build != null) {
			BuildJob build = state.build;
			Map<String, String> providers = new LinkedHashMap<>();
			for (ScenarioAgentSpec spec : build.request.roster()) providers.put("slot-" + spec.slot(), spec.provider());
			return Optional.of(publicViewFromConfig(
					server, build.config, build.blueprint.origin(), buildProgressTitle(build),
					build.session.scores(), state, providers
			));
		}
		return Optional.empty();
	}

	/** Read-only build status projection for the operator command center and HUD. */
	public static synchronized Optional<ScenarioBuildProgress> buildProgress(MinecraftServer server) {
		Objects.requireNonNull(server, "server must not be null");
		RuntimeState state = STATES.get(server);
		return state == null ? Optional.empty() : Optional.ofNullable(state.buildProgress);
	}

	private static ArenaSpectatorSnapshot.PublicView publicViewFromConfig(
			MinecraftServer server,
			ScenarioSessionConfig config,
			BlockPos origin,
			String phaseTitle,
			Map<String, Double> scores,
			RuntimeState state,
			Map<String, String> providerFallbacks
	) {
		return new ArenaSpectatorSnapshot.PublicView(
				config.sessionId().toString(),
				config.preset().id(),
				config.preset().title(),
				phaseTitle,
				0L,
				config.durationTicks(),
				false,
				config.preset().mapVersion(),
				config.worldSeed(),
				config.eventSeed(),
				"",
				origin.getX(), origin.getY(), origin.getZ(),
				publicParticipants(
						server, config.participants(), scores, state.participantByAgent, origin, providerFallbacks),
				state.publicEvents
		);
	}

	private static ArenaSpectatorSnapshot.PublicView publicViewFromSnapshot(
			MinecraftServer server,
			RuntimeState state,
			ScenarioRunSnapshot snapshot,
			boolean terminal
	) {
		ScenarioPreset preset = ScenarioPresets.require(snapshot.scenarioId());
		BlockPos origin = new BlockPos(snapshot.origin().x(), snapshot.origin().y(), snapshot.origin().z());
		LinkedHashMap<String, String> bindings = new LinkedHashMap<>();
		for (int index = 0; index < snapshot.participants().size(); index++) {
			bindings.put(snapshot.boundAgentIds().get(index), snapshot.participants().get(index).id());
		}
		List<ScenarioParticipant> participants = snapshot.participants().stream()
				.map(value -> new ScenarioParticipant(value.id(), value.displayName(), value.team()))
				.toList();
		long elapsedTick = Math.max(0L, snapshot.clock().elapsedTick());
		String phaseTitle = terminal
				? (snapshot.state() == dev.agaminggod.arenaagents.scenario.ScenarioSessionState.FINISHED
						? "Finished" : "Failed")
				: "Recovery paused";
		return new ArenaSpectatorSnapshot.PublicView(
				snapshot.sessionId().toString(),
				snapshot.scenarioId(),
				preset.title(),
				phaseTitle,
				elapsedTick,
				snapshot.durationTicks(),
				terminal,
				snapshot.mapVersion(),
				snapshot.worldSeed(),
				snapshot.eventSeed(),
				terminal ? resultFromSnapshot(snapshot).canonicalSha256() : "",
				origin.getX(), origin.getY(), origin.getZ(),
				publicParticipants(server, participants, snapshot.scores(), bindings, origin, Map.of()),
				snapshot.publicEvents()
		);
	}

	private static List<ArenaSpectatorSnapshot.ParticipantView> publicParticipants(
			MinecraftServer server,
			List<ScenarioParticipant> participants,
			Map<String, Double> scores,
			Map<String, String> participantByAgent,
			BlockPos origin,
			Map<String, String> providerFallbacks
	) {
		CodexAgentManager manager = CodexAgentManager.get(server);
		Map<String, AgentRecord> recordsById = manager.records().stream().collect(java.util.stream.Collectors.toMap(
				record -> record.agentId().toString(),
				record -> record,
				(first, ignored) -> first,
				LinkedHashMap::new
		));
		LinkedHashMap<String, AgentRecord> recordsByParticipant = new LinkedHashMap<>();
		for (Map.Entry<String, String> binding : participantByAgent.entrySet()) {
			AgentRecord record = recordsById.get(binding.getKey());
			if (record != null) recordsByParticipant.put(binding.getValue(), record);
		}
		ArrayList<ArenaSpectatorSnapshot.ParticipantView> result = new ArrayList<>();
		for (ScenarioParticipant participant : participants) {
			AgentRecord record = recordsByParticipant.get(participant.id());
			ServerPlayer player = null;
			if (record != null) {
				try {
					player = manager.findAgentPlayer(record.agentId()).orElse(null);
				} catch (RuntimeException ignored) {
					// A removed contestant remains a public unavailable row until the run snapshot advances.
				}
			}
			String provider = record == null
					? providerFallbacks.getOrDefault(participant.id(), "unknown")
					: record.profile().provider();
			String status = record == null
					? "preparing" : record.state().name().toLowerCase(java.util.Locale.ROOT);
			int healthPercent = player == null
					? (record != null && record.state() == AgentLifecycleState.DEAD ? 0 : 100)
					: Math.clamp(Math.round(100.0F * player.getHealth() / Math.max(1.0F, player.getMaxHealth())), 0, 100);
			double x = player == null ? origin.getX() : player.getX();
			double y = player == null ? origin.getY() : player.getY();
			double z = player == null ? origin.getZ() : player.getZ();
			result.add(new ArenaSpectatorSnapshot.ParticipantView(
					participant.id(), participant.modelLabel(), provider,
					scores.getOrDefault(participant.id(), 0.0D), healthPercent, status, x, y, z
			));
		}
		return List.copyOf(result);
	}

	/**
	 * Runs before manager reconciliation. False means unsafe stale bindings remain and the
	 * caller must skip reconciliation/action work for this tick.
	 */
	public static synchronized boolean restorePersistedState(MinecraftServer server) {
		RuntimeState state = STATES.computeIfAbsent(server, ignored -> new RuntimeState());
		if (state.cleanup != null) return retryFailedRecoveryCleanup(state, server);
		if (state.restoreAttempted) return true;
		state.restoreAttempted = true;
		ScenarioPreparationJournal preparationJournal = new ScenarioPreparationJournal(server.getServerDirectory());
		Optional<ScenarioPreparationJournal.Snapshot> prepared;
		try {
			prepared = preparationJournal.load();
		} catch (java.io.IOException exception) {
			LOGGER.error("Scenario preparation journal cannot be read; recovery is blocked", exception);
			state.restoreAttempted = false;
			return false;
		}
		Optional<ScenarioRunSnapshot> saved = ScenarioSavedData.get(server).snapshot();
		if (preparationOwnsRecovery(
				prepared.map(ScenarioPreparationJournal.Snapshot::sessionId),
				saved.map(ScenarioRunSnapshot::sessionId))) {
			ScenarioPreparationJournal.Snapshot owner = prepared.orElseThrow();
			if (saved.isPresent()) {
				LOGGER.warn(
						"Scenario preparation {} supersedes stale saved run {}",
						owner.sessionId(), saved.orElseThrow().sessionId());
				ScenarioSavedData.get(server).clear();
				ScenarioSavedData.saveNow(server);
			}
			ServerLevel level = null;
			for (ServerLevel candidate : server.getAllLevels()) {
				if (candidate.dimension().identifier().toString().equals(owner.dimensionId())) level = candidate;
			}
			CodexAgentManager manager = CodexAgentManager.get(server);
			List<String> preparationAgentIds = preparationOwnedAgentIds(manager, owner);
			if (level == null || !removeBoundAgentsStrict(manager, preparationAgentIds)) {
				state.restoreAttempted = false;
				return false;
			}
			ScenarioLaunchRequest request = owner.request();
			ScenarioPreset preset = ScenarioPresets.require(request.scenarioId());
			List<ScenarioParticipant> participants = request.roster().stream()
					.sorted(Comparator.comparingInt(ScenarioAgentSpec::slot))
					.map(agent -> new ScenarioParticipant("slot-" + agent.slot(), agent.displayName(), agent.team()))
					.toList();
			ScenarioSessionConfig config = new ScenarioSessionConfig(
					owner.sessionId(), preset, owner.worldSeed(), owner.eventSeed(), preset.defaultDurationTicks(),
					request.deterministicEvents(), participants, owner.createdAtEpochMs());
			ScenarioArenaBlueprint blueprint = ScenarioArenaBlueprint.create(preset, owner.origin(), participants.size());
			state.preparationJournal = preparationJournal;
			state.preparationSnapshot = owner.cancelling();
			try {
				preparationJournal.write(state.preparationSnapshot);
			} catch (java.io.IOException exception) {
				state.restoreAttempted = false;
				return false;
			}
			state.build = new BuildJob(
					owner.operatorId(), level, request, config, new ScenarioSession(config), blueprint,
					new ScenarioArenaResetJob(blueprint), null, -1, true);
			state.buildProgress = new ScenarioBuildProgress(
					owner.sessionId().toString(), preset.title(), "canonicalizing", state.nextBuildRevision(),
					0, state.build.reset.totalPlacements(), 0, owner.origin().getX(), owner.origin().getY(),
					owner.origin().getZ(), ScenarioBuildProgress.Status.BUILDING,
					"Recovering an interrupted preparation to a verified safe state", "", "");
			return true;
		}
		if (prepared.isPresent() && saved.isPresent()
				&& prepared.orElseThrow().sessionId().equals(saved.orElseThrow().sessionId())) {
			try {
				preparationJournal.clear();
			} catch (java.io.IOException exception) {
				LOGGER.warn("Could not clear superseded scenario preparation journal", exception);
			}
		}
		if (saved.isEmpty()) return true;
		ScenarioRunSnapshot snapshot = saved.orElseThrow();
		if (snapshot.state().terminal()) {
			beginResultPersistence(state, server, snapshot);
			return true;
		}
		Set<String> dimensions = new java.util.HashSet<>();
		ServerLevel savedLevel = null;
		for (ServerLevel level : server.getAllLevels()) {
			String dimensionId = level.dimension().identifier().toString();
			dimensions.add(dimensionId);
			if (dimensionId.equals(snapshot.dimensionId())) savedLevel = level;
		}
		ScenarioRecoveryDecision decision = ScenarioRecoveryDecision.evaluate(snapshot, dimensions);
		if (!decision.recoverable() || savedLevel == null) {
			state.cleanup = new CleanupJob(
					snapshot.failed(decision.failureReason()),
					decision.boundAgentsToRemove(),
					0
			);
			LOGGER.error("Scenario {} recovery failed closed: {}", snapshot.sessionId(), decision.failureReason());
			return retryFailedRecoveryCleanup(state, server);
		}
		ScenarioSession session = snapshot.restoreSession();
		ScenarioRuntimeClock clock = ScenarioRuntimeClock.restore(session, snapshot.clock());
		state.publicEvents.clear();
		state.publicEvents.addAll(snapshot.publicEvents());
		if (session.state().terminal()) {
			ScenarioRunSnapshot migrated = ScenarioRunSnapshot.capture(
					session,
					clock,
					snapshot.dimensionId(),
					snapshot.operatorId(),
					snapshot.boundAgentIds(),
					snapshot.parkourCheckpoints(),
					snapshot.origin(),
					snapshot.reset(),
					snapshot.publicEvents()
			);
			state.cleanup = new CleanupJob(migrated, snapshot.boundAgentIds(), 0);
			return retryFailedRecoveryCleanup(state, server);
		}
		state.recovery = new RecoveryJob(snapshot, session, clock, savedLevel, 0L);
		return true;
	}

	static boolean preparationOwnsRecovery(Optional<UUID> preparedSession, Optional<UUID> savedSession) {
		Objects.requireNonNull(preparedSession, "preparedSession must not be null");
		Objects.requireNonNull(savedSession, "savedSession must not be null");
		return preparedSession.isPresent()
				&& (savedSession.isEmpty() || !preparedSession.orElseThrow().equals(savedSession.orElseThrow()));
	}

	private static void tickRecovery(RuntimeState state, boolean coordinatorReady) {
		RecoveryJob recovery = state.recovery;
		if (!coordinatorReady) {
			// Coordinator startup/reconnect time must never consume the player-roster timeout.
			return;
		}
		CodexAgentManager manager = CodexAgentManager.get(recovery.level.getServer());
		ArrayList<AgentRecord> records = new ArrayList<>();
		ArrayList<ScenarioRecoveryGate.AgentStatus> statuses = new ArrayList<>();
		for (String agentId : recovery.snapshot.boundAgentIds()) {
			Optional<AgentRecord> record = manager.records().stream()
					.filter(candidate -> candidate.agentId().toString().equals(agentId))
					.findFirst();
			if (record.isEmpty()) break;
			AgentRecord found = record.orElseThrow();
			records.add(found);
			statuses.add(new ScenarioRecoveryGate.AgentStatus(
					agentId,
					found.state(),
					manager.findAgentPlayer(found.agentId()).isPresent()
			));
		}
		if (statuses.size() == recovery.snapshot.boundAgentIds().size()) {
			ScenarioRecoveryGate.Decision gate = ScenarioRecoveryGate.evaluate(coordinatorReady, statuses);
			for (String agentId : gate.resumeAgentIds()) {
				try {
					manager.resume(agentId);
				} catch (RuntimeException exception) {
					LOGGER.warn("Recovered scenario agent {} could not resume yet", agentId, exception);
				}
			}
			if (!gate.resumeAgentIds().isEmpty()) {
				records.clear();
				statuses.clear();
				for (String agentId : recovery.snapshot.boundAgentIds()) {
					AgentRecord refreshed = manager.records().stream()
							.filter(candidate -> candidate.agentId().toString().equals(agentId))
							.findFirst().orElse(null);
					if (refreshed == null) break;
					records.add(refreshed);
					statuses.add(new ScenarioRecoveryGate.AgentStatus(
							agentId, refreshed.state(), manager.findAgentPlayer(refreshed.agentId()).isPresent()
					));
				}
				if (statuses.size() == recovery.snapshot.boundAgentIds().size()) {
					gate = ScenarioRecoveryGate.evaluate(coordinatorReady, statuses);
				}
			}
			if (gate.ready()) {
				recovery.session.resumeRecovery(Math.max(
						recovery.session.lastElapsedTick(), recovery.clock.snapshot().elapsedTick()
				));
				state.agents = List.copyOf(records);
				state.participantByAgent.clear();
				for (int index = 0; index < recovery.snapshot.boundAgentIds().size(); index++) {
					state.participantByAgent.put(
							recovery.snapshot.boundAgentIds().get(index),
							recovery.snapshot.participants().get(index).id()
					);
				}
				ScenarioParkourRunState parkour = restoreParkourRunState(
						recovery.session.config(), recovery.snapshot);
				state.activeRun = new ActiveRun(
						recovery.session,
						recovery.clock,
						recovery.snapshot.operatorId(),
						recovery.level,
						new BlockPos(recovery.snapshot.origin().x(), recovery.snapshot.origin().y(), recovery.snapshot.origin().z()),
						recovery.snapshot.reset(),
						parkour
				);
				state.recovery = null;
				persistActiveRun(state);
				return;
			}
		}
		if (recovery.elapsedTicks >= ROSTER_READY_TIMEOUT_TICKS) {
			String reason = "RECOVERY_ROSTER_TIMEOUT";
			state.cleanup = new CleanupJob(
					recovery.snapshot.failed(reason),
					recovery.snapshot.boundAgentIds(),
					0
			);
			state.recovery = null;
			state.agents = List.of();
			state.participantByAgent.clear();
			return;
		}
		state.recovery = recovery.nextTick();
	}

	private static ScenarioParkourRunState restoreParkourRunState(
			ScenarioSessionConfig config,
			ScenarioRunSnapshot snapshot
	) {
		if (config.preset().category() != dev.agaminggod.arenaagents.scenario.ScenarioCategory.PARKOUR) {
			return null;
		}
		Map<String, Integer> laneByParticipant = new LinkedHashMap<>();
		for (ScenarioSpawn spawn : new ScenarioSpawnAllocator().allocate(config)) {
			laneByParticipant.put(spawn.participantId(), spawn.slotIndex());
		}
		LinkedHashMap<String, Integer> laneByAgent = new LinkedHashMap<>();
		for (int index = 0; index < snapshot.boundAgentIds().size(); index++) {
			String participantId = snapshot.participants().get(index).id();
			Integer lane = laneByParticipant.get(participantId);
			if (lane == null) throw new IllegalStateException("parkour lane is unavailable for " + participantId);
			laneByAgent.put(snapshot.boundAgentIds().get(index), lane);
		}
		return new ScenarioParkourRunState(
				ScenarioParkourCourse.create(config.participants().size()), laneByAgent,
				snapshot.parkourCheckpoints());
	}

	private static void tickBuild(RuntimeState state) {
		if (state.buildFailed) return;
		BuildJob build = state.build;
		try {
			evacuateSite(build.level, build.blueprint.siteBounds());
			ScenarioArenaResetJob.Tick progress = build.reset.tick(build.level);
			boolean phaseChanged = progress.phase() != build.reportedPhase;
			int percent = progress.total() == 0
					? 100 : (int) (100L * progress.completed() / progress.total());
			boolean terminal = progress.phase() == ScenarioArenaResetJob.Phase.COMPLETE
					|| progress.phase() == ScenarioArenaResetJob.Phase.FAILED;
			boolean advanced = progress.worked() > 0 || progress.completed() != build.reportedCompleted;
			if (!terminal && (phaseChanged || advanced)) {
				state.buildProgress = ScenarioBuildProgress.fromResetTick(
						build.config.sessionId().toString(), build.config.preset().title(), progress,
						build.blueprint.origin().getX(), build.blueprint.origin().getY(), build.blueprint.origin().getZ(),
						state.nextBuildRevision(), buildProgressDetail(progress));
				sendActionBar(build.level, build.operatorId, Component.literal(
						"Arena at " + coordinates(build.blueprint.origin()) + ": "
								+ progress.phase().displayName() + " " + progress.completed() + " / "
								+ progress.total() + " (" + Math.min(100, percent) + "%)"
				));
				build = build.withReportedProgress(progress.phase(), progress.completed());
			}
			state.build = build;
			if (progress.phase() == ScenarioArenaResetJob.Phase.FAILED) {
				String reason = progress.failureReason().isBlank() ? "RESET_VERIFICATION_FAILED" : progress.failureReason();
				state.buildProgress = ScenarioBuildProgress.fromResetTick(
						build.config.sessionId().toString(), build.config.preset().title(), progress,
						build.blueprint.origin().getX(), build.blueprint.origin().getY(), build.blueprint.origin().getZ(),
						state.nextBuildRevision(), resetFailureMessage(reason));
				LOGGER.error(
						"Arena reset failed at {}: reason={}, mismatches={}, samples={}",
						coordinates(build.blueprint.origin()), reason,
						build.reset.mismatchCount(), build.reset.mismatchSamples()
				);
				build.session.fail(0L, "Arena preparation failed: " + reason);
				publishOperatorNotice(build.level, build.operatorId, ScenarioOperatorMessagePolicy.Event.FAILURE,
						"Arena launch failed: " + resetFailureMessage(reason));
				state.build = build;
				state.buildFailed = true;
			} else if (progress.phase() == ScenarioArenaResetJob.Phase.COMPLETE) {
				if (build.cancellationRequested) {
					state.buildProgress = ScenarioBuildProgress.cancelled(
							build.config.sessionId().toString(), build.config.preset().title(),
							state.nextBuildRevision(), progress.total(), progress.changedBlocks(),
							build.blueprint.origin().getX(), build.blueprint.origin().getY(),
							build.blueprint.origin().getZ(),
							"Arena launch cancelled after the site reached a verified safe state");
					state.buildProgressTerminalTick = state.runtimeTick;
					state.build = null;
					state.buildFailed = false;
					clearPreparationJournal(state);
					return;
				}
				populateArenaContainers(build);
				state.buildProgress = ScenarioBuildProgress.fromResetTick(
						build.config.sessionId().toString(), build.config.preset().title(), progress,
						build.blueprint.origin().getX(), build.blueprint.origin().getY(),
						build.blueprint.origin().getZ(), state.nextBuildRevision(),
						"Arena ready at " + coordinates(build.blueprint.origin()) + ". Waiting to start agents.");
				state.build = null;
				if (CodexAgentServerRuntime.automationAvailable(build.level.getServer())) {
					beginActivationOrWait(state, build);
				} else {
					waitForCoordinator(state, build);
				}
			}
		} catch (RuntimeException exception) {
			int total = Math.max(0, build.reset.totalPlacements());
			int completed = Math.clamp(build.reset.completedWork(), 0, total);
			ScenarioArenaResetJob.Tick failedTick = new ScenarioArenaResetJob.Tick(
					ScenarioArenaResetJob.Phase.FAILED, 0, completed, total,
					Math.clamp(build.reset.changedBlocks(), 0, ScenarioBuildProgress.MAX_TOTAL_WORK),
					build.reset.receipt(), safeMessage(exception));
			state.buildProgress = ScenarioBuildProgress.failed(
					build.config.sessionId().toString(), build.config.preset().title(), failedTick,
					build.blueprint.origin().getX(), build.blueprint.origin().getY(), build.blueprint.origin().getZ(),
					state.nextBuildRevision(), safeMessage(exception));
			build.session.fail(0L, "Arena preparation failed: " + safeMessage(exception));
			publishOperatorNotice(build.level, build.operatorId, ScenarioOperatorMessagePolicy.Event.FAILURE,
					"Arena launch failed: " + safeMessage(exception));
			state.build = build;
			state.buildFailed = true;
		}
	}

	private static void tickPendingActivation(RuntimeState state) {
		BuildJob build = state.pendingActivation;
		if (!CodexAgentServerRuntime.automationAvailable(build.level.getServer())) {
			if (state.runtimeTick % 40L == 0L) {
				sendActionBar(build.level, build.operatorId, Component.literal(
						"Arena ready | connecting agent coordinator..."
				));
			}
			return;
		}
		state.pendingActivation = null;
		beginActivationOrWait(state, build);
	}

	private static void beginActivationOrWait(RuntimeState state, BuildJob build) {
		try {
			beginActivation(state, build);
		} catch (RuntimeException exception) {
			if (!state.cancelCleanupIds.isEmpty()) {
				build.session.fail(0L, "Contestant activation failed: " + safeMessage(exception));
				state.buildProgress = activationFailureProgress(state, build.config, build.blueprint.origin(),
						"Agents could not start; cleanup is retrying: " + safeMessage(exception));
				state.buildProgressTerminalTick = state.runtimeTick;
				publishOperatorNotice(build.level, build.operatorId, ScenarioOperatorMessagePolicy.Event.FAILURE,
						"Arena is ready, but partial contestant cleanup must finish before another launch.");
				return;
			}
			if (ScenarioActivationFailurePolicy.retryWhenCoordinatorReturns(exception)) {
				waitForCoordinator(state, build);
				return;
			}
			build.session.fail(0L, "Contestant activation failed: " + safeMessage(exception));
			state.buildProgress = activationFailureProgress(state, build.config, build.blueprint.origin(),
					"Agents could not start: " + safeMessage(exception));
			publishOperatorNotice(build.level, build.operatorId, ScenarioOperatorMessagePolicy.Event.FAILURE,
					"Arena is ready, but agents could not start: " + safeMessage(exception));
			state.buildProgressTerminalTick = state.runtimeTick;
			clearPreparationJournal(state);
		}
	}

	private static void waitForCoordinator(RuntimeState state, BuildJob build) {
		state.pendingActivation = build;
		sendActionBar(build.level, build.operatorId, Component.literal(
				"Arena ready | waiting for agent coordinator"
		));
	}

	private static String buildProgressDetail(ScenarioArenaResetJob.Tick progress) {
		return switch (progress.phase()) {
			case CANONICALIZE -> "Preparing the arena blueprint";
			case LOAD_CHUNKS -> "Loading " + progress.completed() + " of " + progress.total() + " arena chunks";
			case CLEAR -> "Cleared " + progress.completed() + " of " + progress.total()
					+ " vertical cells; " + progress.changedBlocks() + " blocks removed";
			case APPLY -> "Processed " + progress.completed() + " of " + progress.total()
					+ " blueprint blocks; " + progress.changedBlocks() + " world changes made";
			case VERIFY -> "Checking " + progress.completed() + " of " + progress.total() + " arena blocks";
			case REPAIR -> "Corrected " + progress.completed() + " of " + progress.total()
					+ " mismatched arena blocks";
			case COMPLETE -> "Arena verified";
			case FAILED -> progress.failureReason().isBlank() ? "Arena build failed" : progress.failureReason();
		};
	}

	private static String buildProgressTitle(BuildJob build) {
		String label = "Build " + coordinates(build.blueprint.origin()) + " | " + build.reset.progressLabel();
		return label.length() <= 80 ? label : build.reset.progressLabel();
	}

	private static String coordinates(BlockPos position) {
		return position.getX() + ", " + position.getY() + ", " + position.getZ();
	}

	private static String resetFailureMessage(String reason) {
		return switch (reason) {
			case "UNLOADED_MANAGED_CHUNK" -> "a managed arena chunk could not be loaded; retry the launch";
			case "RESET_VERIFICATION_MISMATCH", "RESET_VERIFICATION_FAILED",
					"RESET_VERIFICATION_DID_NOT_CONVERGE" ->
					"the arena remained unstable after three automatic correction passes";
			default -> reason;
		};
	}

	private static void tickActivation(RuntimeState state) {
		ActivationJob activation = state.activation;
		CodexAgentManager manager = CodexAgentManager.get(activation.level.getServer());
		LinkedHashMap<String, ServerPlayer> readyPlayers = new LinkedHashMap<>();
		for (PendingContestant contestant : activation.contestants) {
			manager.findAgentPlayer(contestant.record.agentId()).ifPresent(player ->
					readyPlayers.put(contestant.record.agentId().toString(), player)
			);
		}
		ScenarioRosterActivator activator = new ScenarioRosterActivator();
		activator.protect(readyPlayers.values().stream().toList(), player -> player.setInvulnerable(true));
		ScenarioPreflight.Verdict preflight = ScenarioPreflight.assess(new ScenarioPreflight.Input(
				CoordinatorStatusStore.latest(activation.level.getServer()),
				activation.contestants.stream().map(contestant -> new ScenarioPreflight.RequiredProfile(
						contestant.record.agentId().toString(), contestant.spec.provider(),
						contestant.spec.model(), contestant.spec.reasoning(), contestant.spec.serviceTier()
				)).toList(),
				activation.expectedDimension,
				activation.level.dimension().identifier().toString(),
				activation.resetReceipt.blueprintSha256(),
				activation.resetReceipt.managedVolumeSha256(),
				activation.resetReceipt.verified(),
				activation.firstWaveStartedAtEpochMs,
				System.currentTimeMillis()
		));
		if (preflight.status() == ScenarioPreflight.Status.FAILED) {
			failActivation(state, activation, manager, "preflight " + preflight.code());
			return;
		}
		if (preflight.status() == ScenarioPreflight.Status.WAITING) {
			state.activation = activation.nextTick();
			return;
		}
		ScenarioRosterReadinessBarrier.Assessment assessment = activation.barrier.assess(
				activation.elapsedTicks,
				readyPlayers.keySet()
		);
		if (assessment.status() == ScenarioRosterReadinessBarrier.Status.WAITING) {
			state.activation = activation.nextTick();
			return;
		}
		if (assessment.status() == ScenarioRosterReadinessBarrier.Status.TIMED_OUT) {
			failActivation(
					state,
					activation,
					manager,
					assessment.missingIds().size() + " of " + activation.contestants.size()
							+ " offline contestants did not become ready"
			);
			return;
		}

		try {
			List<ReadyContestant> ready = activation.contestants.stream()
					.map(contestant -> new ReadyContestant(
							contestant,
							readyPlayers.get(contestant.record.agentId().toString())
					))
					.toList();
			ScenarioLoadoutService loadouts = new ScenarioLoadoutService();
			activator.activate(
					ready,
					contestant -> contestant.player.setInvulnerable(false),
					contestant -> loadouts.apply(
							contestant.player,
							activation.config.preset().category(),
							ScenarioParticipantPolicy.effectiveGameMode(
									activation.config.preset().category(), contestant.pending.spec.gameMode())
					),
					contestant -> manager.startSubjective(
							contestant.pending.record.agentId().toString(),
							contestantPrompt(
									activation.config.preset(),
									contestant.pending.spec,
									activation.config,
									contestant.pending.laneIndex,
									activation.origin
							)
					)
			);
			activation.session.start(0L);
			state.agents = activation.contestants.stream().map(PendingContestant::record).toList();
			state.participantByAgent.clear();
			for (PendingContestant contestant : activation.contestants) {
				state.participantByAgent.put(
						contestant.record.agentId().toString(),
						"slot-" + contestant.spec.slot()
				);
			}
			ScenarioParkourRunState parkour = null;
			if (activation.config.preset().category() == dev.agaminggod.arenaagents.scenario.ScenarioCategory.PARKOUR) {
				LinkedHashMap<String, Integer> laneByAgent = new LinkedHashMap<>();
				for (PendingContestant contestant : activation.contestants) {
					laneByAgent.put(contestant.record.agentId().toString(), contestant.laneIndex);
				}
				parkour = new ScenarioParkourRunState(
						ScenarioParkourCourse.create(activation.config.participants().size()), laneByAgent);
			}
			state.activeRun = new ActiveRun(
					activation.session,
					new ScenarioRuntimeClock(activation.session),
					activation.operatorId,
					activation.level,
					activation.origin,
					activation.resetReceipt,
					parkour
			);
			state.activation = null;
			persistActiveRun(state);
			ScenarioSavedData.saveNow(activation.level.getServer());
			clearPreparationJournal(state);
			try {
				ServerPlayer operator = liveOperator(activation.level, activation.operatorId);
				if (operator != null) {
					BlockPos operatorSpawn = ScenarioArenaBlueprint.operatorSpawn(activation.origin);
					operator.teleportTo(operatorSpawn.getX() + 0.5D, operatorSpawn.getY(), operatorSpawn.getZ() + 0.5D);
				}
			} catch (RuntimeException exception) {
				LOGGER.warn("Scenario {} started but the operator could not be moved to the arena",
						activation.config.sessionId(), exception);
			}
			try {
				publishOperatorNotice(activation.level, activation.operatorId, ScenarioOperatorMessagePolicy.Event.STARTED,
						"GO | " + activation.config.preset().title() + " launched with " + ready.size()
								+ " independently controlled offline players.");
			} catch (RuntimeException exception) {
				LOGGER.warn("Scenario {} started but its operator notice could not be delivered",
						activation.config.sessionId(), exception);
			}
		} catch (RuntimeException exception) {
			failActivation(state, activation, manager, safeMessage(exception));
		}
	}

	private static void tickActive(RuntimeState state) {
		ActiveRun run = state.activeRun;
		if (run == null) return;
		tickParkourParticipants(state, run);
		ScenarioRuntimeClock.Update update = run.clock.tick();
		if (!run.session.state().terminal()) {
			completionReason(state, run).ifPresent(reason -> run.session.finish(update.elapsedTick(), reason));
		}
		if (run.clock.snapshot().elapsedTick() % 20L == 0L) persistActiveRun(state);
		if (!run.session.state().terminal()) return;
		CodexAgentManager manager = CodexAgentManager.get(run.level.getServer());
		for (AgentRecord agent : state.agents) {
			try {
				manager.stop(agent.agentId().toString());
			} catch (RuntimeException ignored) {
				// A contestant may already have completed, failed, or died.
			}
		}
		publishOperatorNotice(
				run.level.getServer().getPlayerList().getPlayer(run.operatorId),
				ScenarioOperatorMessagePolicy.Event.FINISHED,
				"FINISHED | " + run.session.config().preset().title() + ": "
						+ run.session.completionReason().orElse("Scenario complete")
		);
		ScenarioRunSnapshot terminalSnapshot = persistActiveRun(state);
		beginResultPersistence(state, run.level.getServer(), terminalSnapshot);
	}

	/**
	 * Vanilla death is never intercepted by a scenario. A selected model may later
	 * choose the coordinate-free vanilla respawn primitive for its dead agent.
	 */
	public static synchronized boolean recoverParkourDeath(ServerPlayer player) {
		Objects.requireNonNull(player, "player must not be null");
		return false;
	}

	private static void tickParkourParticipants(RuntimeState state, ActiveRun run) {
		if (run.parkour == null) return;
		CodexAgentManager manager = CodexAgentManager.get(run.level.getServer());
		for (AgentRecord agent : state.agents) {
			ServerPlayer player = manager.findAgentPlayer(agent.agentId()).orElse(null);
			if (player == null || !player.isAlive()) continue;
			player.setGameMode(GameType.ADVENTURE);
			ScenarioParkourRecovery.Decision decision = run.parkour.evaluate(
					agent.agentId().toString(),
					player.getX() - run.origin.getX(),
					player.getY() - run.origin.getY(),
					player.getZ() - run.origin.getZ()
			);
			ScenarioParkourRecovery.Target target = decision.target();
			BlockPos respawn = BlockPos.containing(
					run.origin.getX() + target.x(),
					run.origin.getY() + target.y(),
					run.origin.getZ() + target.z()
			);
			player.setRespawnPosition(new ServerPlayer.RespawnConfig(
					LevelData.RespawnData.of(run.level.dimension(), respawn, player.getYRot(), player.getXRot()),
					true
			), false);
		}
	}

	private static Optional<String> completionReason(RuntimeState state, ActiveRun run) {
		CodexAgentManager manager = CodexAgentManager.get(run.level.getServer());
		Map<String, AgentRecord> currentRecords = new LinkedHashMap<>();
		for (AgentRecord record : manager.records()) {
			currentRecords.put(record.agentId().toString(), record);
		}
		ArrayList<ScenarioCompletionPolicy.ParticipantState> participants = new ArrayList<>();
		for (AgentRecord boundAgent : state.agents) {
			String agentId = boundAgent.agentId().toString();
			String participantId = state.participantByAgent.get(agentId);
			if (participantId == null) continue;
			AgentRecord current = currentRecords.get(agentId);
			ScenarioParticipant participant = run.session.config().requireParticipant(participantId);
			boolean alive = current != null
					&& manager.findAgentPlayer(boundAgent.agentId()).map(ServerPlayer::isAlive).orElse(false);
			participants.add(new ScenarioCompletionPolicy.ParticipantState(
				agentId, participant.team(),
					current == null ? AgentLifecycleState.DISCONNECTED : current.state(), alive
			));
		}
		if (participants.size() != run.session.config().participants().size()) return Optional.empty();
		return ScenarioCompletionPolicy.finishReason(
				run.session.config().preset().category(), participants,
				run.parkour != null && run.parkour.allFinished()
		);
	}

	private static void publishOperatorNotice(
			ServerPlayer operator,
			ScenarioOperatorMessagePolicy.Event event,
			String message
	) {
		if (operator == null || ScenarioOperatorMessagePolicy.surface(event)
				!= ScenarioOperatorMessagePolicy.Surface.ACTION_BAR) return;
		operator.connection.send(new ClientboundSetActionBarTextPacket(Component.literal(message)));
	}

	private static void publishOperatorNotice(
			ServerLevel level,
			UUID operatorId,
			ScenarioOperatorMessagePolicy.Event event,
			String message
	) {
		publishOperatorNotice(liveOperator(level, operatorId), event, message);
	}

	private static void sendActionBar(ServerLevel level, UUID operatorId, Component message) {
		ServerPlayer operator = liveOperator(level, operatorId);
		if (operator != null) operator.connection.send(new ClientboundSetActionBarTextPacket(message));
	}

	private static ServerPlayer liveOperator(ServerLevel level, UUID operatorId) {
		return level.getServer().getPlayerList().getPlayer(operatorId);
	}

	private static void clearPreparationJournal(RuntimeState state) {
		if (state.preparationJournal != null) {
			try {
				state.preparationJournal.clear();
			} catch (java.io.IOException exception) {
				LOGGER.error("Could not clear completed scenario preparation journal", exception);
				return;
			}
		}
		state.preparationJournal = null;
		state.preparationSnapshot = null;
	}

	private static ScenarioRunSnapshot persistActiveRun(RuntimeState state) {
		ActiveRun run = state.activeRun;
		if (run == null) throw new IllegalStateException("active scenario is unavailable");
		ScenarioRunSnapshot snapshot = ScenarioRunSnapshot.capture(
				run.session,
				run.clock,
				run.level.dimension().identifier().toString(),
				run.operatorId,
				state.agents.stream().map(agent -> agent.agentId().toString()).toList(),
				run.parkour == null ? Map.of() : run.parkour.checkpoints(),
				new ScenarioRunSnapshot.Origin(run.origin.getX(), run.origin.getY(), run.origin.getZ()),
				run.resetReceipt,
				state.publicEvents
		);
		ScenarioSavedData.get(run.level.getServer()).setSnapshot(snapshot);
		return snapshot;
	}

	private static MatchResultV1 resultFromSnapshot(ScenarioRunSnapshot snapshot) {
		String outcome = snapshot.state() == dev.agaminggod.arenaagents.scenario.ScenarioSessionState.FINISHED
				? "completed" : "failed";
		List<MatchResultV1.Standing> standings = snapshot.participants().stream()
				.map(participant -> new MatchResultV1.Standing(
						participant.id(),
						participant.displayName(),
						snapshot.scores().getOrDefault(participant.id(), 0.0D),
						outcome
				))
				.toList();
		MatchResultV1 result = new MatchResultV1(
				snapshot.sessionId().toString(),
				snapshot.scenarioId(),
				snapshot.mapVersion(),
				snapshot.worldSeed(),
				snapshot.eventSeed(),
				standings,
				snapshot.publicEvents(),
				snapshot.reset(),
				""
		);
		return result;
	}

	private static void beginResultPersistence(
			RuntimeState state,
			MinecraftServer server,
			ScenarioRunSnapshot snapshot
	) {
		if (!snapshot.state().terminal()) {
			throw new IllegalArgumentException("only terminal scenarios can produce match results");
		}
		if (state.pendingResult != null) return;
		MatchResultV1 result = resultFromSnapshot(snapshot);
		MatchResultWriter writer = new MatchResultWriter(
				server.getServerDirectory().resolve("runtime").resolve("match-results")
		);
		state.pendingResult = new PendingResult(
				snapshot,
				new ScenarioResultPersistence(
						result,
						value -> writer.writeAsync(value, java.util.concurrent.ForkJoinPool.commonPool())
				)
		);
	}

	private static void tickPendingResult(RuntimeState state, MinecraftServer server) {
		PendingResult pending = state.pendingResult;
		if (pending == null) return;
		ScenarioResultPersistence.Status status = pending.persistence.poll(state.runtimeTick);
		if (status.durable()) {
			// Scenario contestants are run-owned. Leaving their durable registry rows
			// behind makes the next run collide with the same roster display names.
			List<String> boundAgentIds = ScenarioOwnedAgentIds.forCleanup(state.agents.stream()
					.map(agent -> agent.agentId().toString())
					.toList(), pending.snapshot.boundAgentIds());
			if (!removeBoundAgentsStrict(CodexAgentManager.get(server), boundAgentIds)) return;
			ScenarioSavedData.get(server).clear();
			state.pendingResult = null;
			state.activeRun = null;
			state.agents = List.of();
			state.participantByAgent.clear();
			return;
		}
		if (!status.lastFailure().isBlank() && status.attempts() > pending.loggedAttempts) {
			pending.loggedAttempts = status.attempts();
			LOGGER.error(
					"Canonical match result {} is not durable yet; retry {} scheduled: {}",
					pending.snapshot.sessionId(), status.attempts(), status.lastFailure()
			);
		}
	}

	public static synchronized void onAgentEvent(MinecraftServer server, ScenarioAgentEvent event) {
		Objects.requireNonNull(server, "server must not be null");
		Objects.requireNonNull(event, "event must not be null");
		RuntimeState state = STATES.get(server);
		if (state == null || state.activeRun == null
				|| state.activeRun.session.state() != dev.agaminggod.arenaagents.scenario.ScenarioSessionState.RUNNING) {
			return;
		}
		boolean participantExists = state.activeRun.session.config().participants().stream()
				.anyMatch(participant -> participant.id().equals(event.participantId()));
		if (!participantExists) return;
		ScenarioPublicEvent publicEvent = new ScenarioPublicFormatter().format(event);
		if (state.publicEvents.size() == PUBLIC_EVENT_LIMIT) state.publicEvents.removeFirst();
		state.publicEvents.add(publicEvent);
	}

	public static synchronized void onAgentAction(
			MinecraftServer server,
			String agentId,
			String actionWireName,
			boolean succeeded
	) {
		RuntimeState state = STATES.get(server);
		if (state == null || state.activeRun == null) return;
		String participantId = state.participantByAgent.get(agentId);
		if (participantId == null) return;
		ScenarioParticipant participant = state.activeRun.session.config().requireParticipant(participantId);
		onAgentEvent(server, new ScenarioAgentEvent(
				state.activeRun.clock.snapshot().elapsedTick(),
				participant.id(),
				participant.modelLabel(),
				succeeded ? ScenarioAgentEvent.Kind.ACTION_COMPLETED : ScenarioAgentEvent.Kind.ACTION_FAILED,
				actionFamily(actionWireName),
				0.0D,
				succeeded ? ScenarioAgentEvent.PublicState.ACTING : ScenarioAgentEvent.PublicState.RECOVERING
		));
	}

	public static synchronized void onAgentState(
			MinecraftServer server,
			String agentId,
			ScenarioAgentEvent.PublicState publicState
	) {
		RuntimeState state = STATES.get(server);
		if (state == null || state.activeRun == null) return;
		String participantId = state.participantByAgent.get(agentId);
		if (participantId == null) return;
		ScenarioParticipant participant = state.activeRun.session.config().requireParticipant(participantId);
		onAgentEvent(server, new ScenarioAgentEvent(
				state.activeRun.clock.snapshot().elapsedTick(),
				participant.id(),
				participant.modelLabel(),
				ScenarioAgentEvent.Kind.STATE_CHANGED,
				ScenarioAgentEvent.ActionFamily.OTHER,
				0.0D,
				publicState
		));
	}

	private static ScenarioAgentEvent.ActionFamily actionFamily(String wireName) {
		if (wireName == null) return ScenarioAgentEvent.ActionFamily.OTHER;
		return switch (wireName) {
			case "control", "move_to", "navigate_to", "follow_entity", "look_at" -> ScenarioAgentEvent.ActionFamily.MOVEMENT;
			case "break_block", "pick_up_item" -> ScenarioAgentEvent.ActionFamily.HARVEST;
			case "place_block" -> ScenarioAgentEvent.ActionFamily.BUILD;
			case "craft_inventory", "craft_table", "furnace_transaction", "transfer_container",
					"equip_item", "select_tool", "select_item" -> ScenarioAgentEvent.ActionFamily.CRAFT;
			case "attack", "fight_target", "block_with_shield", "use_ranged" -> ScenarioAgentEvent.ActionFamily.COMBAT;
			case "flee_from", "use_item" -> ScenarioAgentEvent.ActionFamily.SURVIVAL;
			case "chat" -> ScenarioAgentEvent.ActionFamily.COMMUNICATION;
			default -> ScenarioAgentEvent.ActionFamily.OTHER;
		};
	}

	public static synchronized void release(MinecraftServer server) {
		RuntimeState state = STATES.remove(server);
		if (state != null && state.build != null) state.build.reset.close(state.build.level);
		if (state != null && state.activeRun != null && state.pendingResult == null) persistActiveRun(state);
	}

	private static void beginActivation(RuntimeState state, BuildJob build) {
		CodexAgentManager manager = CodexAgentManager.get(build.level.getServer());
		Map<String, ScenarioAgentSpec> specs = new LinkedHashMap<>();
		for (ScenarioAgentSpec spec : build.request.roster()) {
			specs.put("slot-" + spec.slot(), spec);
		}
		List<ScenarioSpawn> spawns = new ScenarioSpawnAllocator().allocate(build.config);
		ArrayList<PendingContestant> contestants = new ArrayList<>();
		try {
			for (ScenarioSpawn spawn : spawns) {
				ScenarioAgentSpec spec = specs.get(spawn.participantId());
				Vec3 position = new Vec3(
						build.blueprint.origin().getX() + spawn.x() + 0.5D,
						build.blueprint.origin().getY() + spawn.y(),
						build.blueprint.origin().getZ() + spawn.z() + 0.5D
				);
				var effectiveGameMode = ScenarioParticipantPolicy.effectiveGameMode(
						build.config.preset().category(), spec.gameMode());
				AgentRecord record = manager.summon(
						build.level,
						position,
						spec.provider(),
						spec.model(),
						spec.reasoning(),
						spec.serviceTier(),
						Optional.of(spec.displayName()),
						effectiveGameMode
				);
				contestants.add(new PendingContestant(record, spec, spawn.slotIndex()));
				if (state.preparationJournal == null || state.preparationSnapshot == null) {
					throw new IllegalStateException("PREPARATION_JOURNAL_MISSING");
				}
				state.preparationSnapshot = state.preparationSnapshot.withAgentId(record.agentId().toString());
				try {
					state.preparationJournal.write(state.preparationSnapshot);
				} catch (java.io.IOException exception) {
					throw new IllegalStateException("PREPARATION_JOURNAL_UNAVAILABLE", exception);
				}
			}
		} catch (RuntimeException exception) {
			List<String> cleanupIds = state.preparationSnapshot == null
					? contestants.stream().map(contestant -> contestant.record.agentId().toString()).toList()
					: preparationOwnedAgentIds(manager, state.preparationSnapshot);
			boolean cleanupSucceeded = removeBoundAgentsStrict(manager, cleanupIds);
			state.cancelCleanupIds = activationCleanupIds(cleanupSucceeded, cleanupIds);
			if (cleanupSucceeded && ScenarioActivationFailurePolicy.retryWhenCoordinatorReturns(exception)
					&& state.preparationJournal != null && state.preparationSnapshot != null) {
				state.preparationSnapshot = preparationWithoutAgents(state.preparationSnapshot);
				try {
					state.preparationJournal.write(state.preparationSnapshot);
				} catch (java.io.IOException journalFailure) {
					throw new IllegalStateException("PREPARATION_JOURNAL_UNAVAILABLE", journalFailure);
				}
			}
			throw exception;
		}
		build.session.markReady(0L);
		build.session.beginCountdown(0L);
		List<PendingContestant> pending = List.copyOf(contestants);
		state.activation = new ActivationJob(
				build.session,
				build.operatorId,
				build.level,
				build.config,
				build.blueprint.origin(),
				build.reset.receipt().orElseThrow(),
				pending,
				new ScenarioRosterReadinessBarrier(
						pending.stream().map(contestant -> contestant.record.agentId().toString()).toList(),
						ROSTER_READY_TIMEOUT_TICKS
				),
				build.level.dimension().identifier().toString(),
				System.currentTimeMillis(),
				0L
		);
		publishOperatorNotice(build.level, build.operatorId, ScenarioOperatorMessagePolicy.Event.READY,
				"Arena ready | waiting for " + pending.size() + " offline contestants.");
	}

	private static void failActivation(
			RuntimeState state,
			ActivationJob activation,
			CodexAgentManager manager,
			String reason
	) {
		activation.session.fail(0L, "Contestant activation failed: " + reason);
		state.buildProgress = activationFailureProgress(
				state, activation.config, activation.origin, "Agents could not start: " + reason);
		publishOperatorNotice(activation.level, activation.operatorId, ScenarioOperatorMessagePolicy.Event.FAILURE,
				"Arena launch failed: " + reason);
		removeContestants(manager, activation.contestants.stream().map(PendingContestant::record).toList());
		state.cancelCleanupIds = activation.contestants.stream()
				.map(contestant -> contestant.record.agentId().toString()).toList();
		state.activation = null;
		state.activeRun = null;
		state.agents = List.of();
	}

	private static ScenarioBuildProgress activationFailureProgress(
			RuntimeState state,
			ScenarioSessionConfig config,
			BlockPos origin,
			String detail
	) {
		return ScenarioBuildProgress.rejected(
				config.sessionId().toString(), config.preset().title(), state.nextBuildRevision(),
				origin.getX(), origin.getY(), origin.getZ(), detail);
	}

	private static void removeContestants(CodexAgentManager manager, List<AgentRecord> contestants) {
		for (AgentRecord contestant : contestants.reversed()) {
			try {
				manager.remove(contestant.agentId().toString());
			} catch (RuntimeException ignored) {
				// A contestant may still be spawning or may already have been removed manually.
			}
		}
	}

	private static boolean removeBoundAgentsStrict(CodexAgentManager manager, List<String> agentIds) {
		boolean clean = true;
		for (String agentId : agentIds.reversed()) {
			try {
				manager.remove(agentId);
			} catch (AgentDomainException exception) {
				if (agentRegistryContains(manager, agentId)) {
					clean = false;
					LOGGER.error("Failed to delete stale scenario agent {}: {}", agentId, exception.code(), exception);
				} else if (!"AGENT_NOT_FOUND".equals(exception.code())) {
					LOGGER.warn(
							"Scenario agent {} was deleted from the registry despite cleanup error {}",
							agentId, exception.code(), exception
					);
				}
			} catch (RuntimeException exception) {
				if (agentRegistryContains(manager, agentId)) {
					clean = false;
					LOGGER.error("Failed to delete stale scenario agent {}", agentId, exception);
				} else {
					LOGGER.warn("Scenario agent {} registry deletion completed before cleanup failed", agentId, exception);
				}
			}
		}
		for (String agentId : agentIds) {
			if (agentRegistryContains(manager, agentId)) clean = false;
		}
		return clean;
	}

	private static boolean agentRegistryContains(CodexAgentManager manager, String agentId) {
		return manager.records().stream().anyMatch(record -> record.agentId().toString().equals(agentId));
	}

	static List<String> activationCleanupIds(boolean cleanupSucceeded, List<String> ownedAgentIds) {
		Objects.requireNonNull(ownedAgentIds, "ownedAgentIds must not be null");
		return cleanupSucceeded ? List.of() : List.copyOf(ownedAgentIds);
	}

	static ScenarioPreparationJournal.Snapshot preparationWithoutAgents(
			ScenarioPreparationJournal.Snapshot snapshot
	) {
		Objects.requireNonNull(snapshot, "snapshot must not be null");
		return new ScenarioPreparationJournal.Snapshot(
				snapshot.sessionId(), snapshot.operatorId(), snapshot.dimensionId(), snapshot.origin(), snapshot.request(),
				snapshot.worldSeed(), snapshot.eventSeed(), snapshot.createdAtEpochMs(), List.of(),
				snapshot.cancellationRequested());
	}

	private static boolean retryFailedRecoveryCleanup(RuntimeState state, MinecraftServer server) {
		CleanupJob cleanup = state.cleanup;
		if (cleanup == null) return true;
		if (!removeBoundAgentsStrict(CodexAgentManager.get(server), cleanup.agentIds)) {
			state.cleanup = cleanup.nextAttempt();
			return false;
		}
		ScenarioSavedData.get(server).setSnapshot(cleanup.failedSnapshot);
		state.cleanup = null;
		beginResultPersistence(state, server, cleanup.failedSnapshot);
		return true;
	}

	private static String contestantPrompt(
			ScenarioPreset preset,
			ScenarioAgentSpec spec,
			ScenarioSessionConfig config,
			int laneIndex,
			BlockPos origin
	) {
		String arenaSpecific = "";
		if (preset.category() == dev.agaminggod.arenaagents.scenario.ScenarioCategory.PARKOUR) {
			ScenarioParkourCourse.Lane lane = ScenarioParkourCourse.create(config.participants().size())
					.lanes().get(laneIndex);
			ScenarioParkourCourse.Platform start = lane.platforms().getFirst();
			ScenarioParkourCourse.Platform finish = lane.platforms().getLast();
			arenaSpecific = "Your dedicated lane is " + lane.index() + " at world x approximately "
					+ (origin.getX() + start.centerX()) + ". Stay in that lane; do not jump to another contestant's course. "
					+ "Advance toward increasing world z from " + (origin.getZ() + start.centerZ()) + " to "
					+ (origin.getZ() + finish.centerZ())
					+ ". Glowing platforms are checkpoints. Death remains a normal vanilla death; choose respawn only when appropriate.";
		}
		return """
				You are contestant %s in the Minecraft AI Arena scenario "%s".
				Primary objective: %s
				Map landmarks: %s
				Dynamic pressures: %s
				Your model's decisions are the point of the comparison. Act immediately, visibly, and autonomously.
				Use navigation/combat/flee/follow controller actions for sustained behavior. Preserve yourself in Survival,
				but do not invent an objective beyond this brief. Other contestants are independently controlled.
				Session seed: %d. Team: %s.
				%s
				""".formatted(
				spec.displayName(),
				preset.title(),
				preset.objective(),
				String.join(", ", preset.landmarks()),
				String.join(", ", preset.dynamicEvents()),
				config.worldSeed(),
				spec.team().orElse("solo"),
				arenaSpecific
		).trim();
	}

	private static void populateArenaContainers(BuildJob build) {
		if (build.config.preset().category() != dev.agaminggod.arenaagents.scenario.ScenarioCategory.PVP) return;
		for (ScenarioArenaBlueprint.Placement placement :
				ScenarioArenaResetJob.canonicalize(build.blueprint.placements())) {
			if (placement.state().getBlock() != Blocks.CHEST && placement.state().getBlock() != Blocks.BARREL) continue;
			if (!(build.level.getBlockEntity(placement.position()) instanceof Container container)) {
				throw new IllegalStateException("MISSING_LOOT_CONTAINER_AT_" + placement.position().toShortString());
			}
			ScenarioLootManifest manifest = ScenarioLootManifest.forContainer(
					build.blueprint.origin(), placement.position(), build.config.worldSeed());
			Map<Integer, ItemStack> expected = new java.util.HashMap<>();
			for (ScenarioLootManifest.Entry entry : manifest.entries()) {
				if (entry.slot() >= container.getContainerSize()) {
					throw new IllegalStateException("LOOT_SLOT_OUT_OF_RANGE_AT_" + placement.position().toShortString());
				}
				expected.put(entry.slot(), lootStack(entry));
			}
			for (int slot = 0; slot < container.getContainerSize(); slot++) {
				ItemStack wanted = expected.getOrDefault(slot, ItemStack.EMPTY);
				if (!sameStack(container.getItem(slot), wanted)) container.setItem(slot, wanted.copy());
			}
			container.setChanged();
			for (int slot = 0; slot < container.getContainerSize(); slot++) {
				ItemStack wanted = expected.getOrDefault(slot, ItemStack.EMPTY);
				if (!sameStack(container.getItem(slot), wanted)) {
					throw new IllegalStateException("LOOT_VERIFICATION_FAILED_AT_"
							+ placement.position().toShortString() + "_SLOT_" + slot);
				}
			}
		}
	}

	private static ItemStack lootStack(ScenarioLootManifest.Entry entry) {
		Identifier identifier = Identifier.tryParse(entry.itemId());
		if (identifier == null || !BuiltInRegistries.ITEM.containsKey(identifier)) {
			throw new IllegalStateException("UNKNOWN_LOOT_ITEM_" + entry.itemId());
		}
		return new ItemStack(BuiltInRegistries.ITEM.getValue(identifier), entry.count());
	}

	private static boolean sameStack(ItemStack actual, ItemStack expected) {
		if (actual.isEmpty() || expected.isEmpty()) return actual.isEmpty() && expected.isEmpty();
		return actual.getItem() == expected.getItem() && actual.getCount() == expected.getCount();
	}

	private static List<String> preparationOwnedAgentIds(
			CodexAgentManager manager,
			ScenarioPreparationJournal.Snapshot owner
	) {
		java.util.LinkedHashSet<String> ids = new java.util.LinkedHashSet<>(owner.agentIds());
		for (AgentRecord record : manager.records()) {
			if (record.createdAtEpochMs() < owner.createdAtEpochMs()) continue;
			boolean matches = owner.request().roster().stream().anyMatch(spec ->
					record.profile().userName().orElse("").equals(spec.displayName())
							&& record.profile().provider().equals(spec.provider())
							&& record.profile().model().equals(spec.model())
							&& record.profile().reasoning().equals(spec.reasoning())
							&& record.profile().serviceTier().equals(spec.serviceTier()));
			if (matches) ids.add(record.agentId().toString());
		}
		return List.copyOf(ids);
	}

	public static synchronized void cancel(ServerPlayer operator, String buildId) {
		Objects.requireNonNull(operator, "operator must not be null");
		String expectedBuildId = Objects.requireNonNull(buildId, "buildId must not be null").trim();
		if (!GoalControl.mayControl(operator.createCommandSourceStack())) {
			throw new IllegalStateException("You do not have permission to cancel Arena Agents scenarios");
		}
		RuntimeState state = STATES.get(operator.level().getServer());
		if (state == null || state.buildProgress == null
				|| !state.buildProgress.buildId().equals(expectedBuildId)) {
			throw new IllegalStateException("This arena preparation is no longer active");
		}
		if (state.activeRun != null || state.pendingResult != null || state.recovery != null) {
			throw new IllegalStateException("A started scenario cannot be cancelled as preparation");
		}
		ScenarioBuildProgress current = state.buildProgress;
		if (state.pendingConfirmation != null) {
			state.pendingConfirmation = null;
			state.buildProgress = ScenarioBuildProgress.cancelled(
					current.buildId(), current.scenarioTitle(), state.nextBuildRevision(), 0, 0,
					current.originX(), current.originY(), current.originZ(), "Arena launch cancelled before world changes");
			state.buildProgressTerminalTick = state.runtimeTick;
			clearPreparationJournal(state);
			return;
		}
		if (state.build != null) {
			ScenarioArenaResetJob.Phase phase = state.build.reset.phase();
			if (!ScenarioCancellationPolicy.requiresSafeReset(phase, state.buildFailed)) {
				state.build.reset.close(state.build.level);
				state.buildProgress = ScenarioBuildProgress.cancelled(
						current.buildId(), current.scenarioTitle(), state.nextBuildRevision(),
						current.total(), current.changedBlocks(), current.originX(), current.originY(), current.originZ(),
						"Arena launch cancelled before world changes");
				state.build = null;
				state.buildProgressTerminalTick = state.runtimeTick;
				clearPreparationJournal(state);
			} else if (state.buildFailed || phase == ScenarioArenaResetJob.Phase.FAILED) {
				state.build = state.build.restartForCancellation();
				state.buildFailed = false;
				state.buildProgress = new ScenarioBuildProgress(
						current.buildId(), current.scenarioTitle(), "canonicalizing", state.nextBuildRevision(),
						0, state.build.reset.totalPlacements(), current.changedBlocks(), current.originX(), current.originY(),
						current.originZ(), ScenarioBuildProgress.Status.BUILDING,
						"Restoring the interrupted site to a verified arena before release", "", "");
			} else {
				state.build = state.build.cancelAfterSafeReset();
				state.buildProgress = new ScenarioBuildProgress(
						current.buildId(), current.scenarioTitle(), current.phase(), state.nextBuildRevision(),
						current.completed(), current.total(), current.changedBlocks(), current.originX(), current.originY(),
						current.originZ(), ScenarioBuildProgress.Status.BUILDING,
						"Cancellation requested; finishing verification so the site is not left partial", "", "");
			}
			return;
		}
		if (state.pendingActivation != null) {
			state.pendingActivation = null;
			state.buildProgress = ScenarioBuildProgress.cancelled(
					current.buildId(), current.scenarioTitle(), state.nextBuildRevision(), current.total(),
					current.changedBlocks(), current.originX(), current.originY(), current.originZ(),
					"Arena launch cancelled before contestants started");
			state.buildProgressTerminalTick = state.runtimeTick;
			clearPreparationJournal(state);
			return;
		}
		if (state.activation != null) {
			state.cancelCleanupIds = state.activation.contestants.stream()
					.map(contestant -> contestant.record.agentId().toString()).toList();
			state.activation = null;
			state.buildProgress = ScenarioBuildProgress.cancelled(
					current.buildId(), current.scenarioTitle(), state.nextBuildRevision(), current.total(),
					current.changedBlocks(), current.originX(), current.originY(), current.originZ(),
					"Arena launch cancelled; removing summoned contestants");
			state.buildProgressTerminalTick = state.runtimeTick;
			return;
		}
		throw new IllegalStateException("This arena preparation is no longer active");
	}

	private static SiteInspection inspectSite(ServerLevel level, ScenarioArenaBlueprint blueprint) {
		ScenarioArenaBlueprint.SiteBounds bounds = blueprint.siteBounds();
		AABB volume = siteVolume(level, bounds);
		List<Entity> occupantEntities = level.getEntities((Entity) null, volume, Entity::isAlive).stream()
				.sorted(Comparator.comparing(entity -> entity.getUUID().toString()))
				.toList();
		Set<UUID> occupants = occupantEntities.stream().map(Entity::getUUID)
				.collect(java.util.stream.Collectors.toUnmodifiableSet());
		boolean border = List.of(
				new BlockPos(bounds.minimumX(), bounds.clearFloorY(), bounds.minimumZ()),
				new BlockPos(bounds.minimumX(), bounds.clearFloorY(), bounds.maximumZ()),
				new BlockPos(bounds.maximumX(), bounds.clearFloorY(), bounds.minimumZ()),
				new BlockPos(bounds.maximumX(), bounds.clearFloorY(), bounds.maximumZ()))
				.stream().allMatch(level.getWorldBorder()::isWithinBounds);
		int maximumAuthoredY = blueprint.placements().stream()
				.mapToInt(placement -> placement.position().getY()).max().orElse(bounds.clearFloorY());
		ScenarioSitePreflight.Verdict verdict = ScenarioSitePreflight.assess(new ScenarioSitePreflight.Input(
				bounds, level.getMinY(), level.getMaxY(), maximumAuthoredY, border, occupants.size()));
		if (occupants.size() > MAX_EVACUATION_OCCUPANTS) {
			verdict = new ScenarioSitePreflight.Verdict(false, "SITE_OCCUPANT_LIMIT", 0L, occupants.size());
		} else if (verdict.allowedWithConfirmation() && !canEvacuate(level, bounds, occupantEntities)) {
			verdict = new ScenarioSitePreflight.Verdict(false, "NO_SAFE_EVACUATION_SITE", 0L, occupants.size());
		}
		return new SiteInspection(verdict, occupants);
	}

	private static AABB siteVolume(ServerLevel level, ScenarioArenaBlueprint.SiteBounds bounds) {
		return new AABB(
				bounds.minimumX(), bounds.clearFloorY() + 1.0D, bounds.minimumZ(),
				bounds.maximumX() + 1.0D, level.getMaxY(), bounds.maximumZ() + 1.0D);
	}

	private static void evacuateSite(ServerLevel level, ScenarioArenaBlueprint.SiteBounds bounds) {
		List<Entity> occupants = level.getEntities((Entity) null, siteVolume(level, bounds), Entity::isAlive).stream()
				.sorted(Comparator.comparing(entity -> entity.getUUID().toString()))
				.toList();
		if (occupants.size() > MAX_EVACUATION_OCCUPANTS) throw new IllegalStateException("SITE_OCCUPANT_LIMIT");
		List<EvacuationAssignment> assignments = evacuationPlan(level, bounds, occupants);
		for (Entity occupant : occupants) occupant.stopRiding();
		for (EvacuationAssignment assignment : assignments) {
			BlockPos target = assignment.target;
			assignment.entity.teleportTo(target.getX() + 0.5D, target.getY(), target.getZ() + 0.5D);
		}
		if (!level.getEntities((Entity) null, siteVolume(level, bounds), Entity::isAlive).isEmpty()) {
			throw new IllegalStateException("SITE_EVACUATION_INCOMPLETE");
		}
	}

	private static boolean canEvacuate(
			ServerLevel level,
			ScenarioArenaBlueprint.SiteBounds bounds,
			List<Entity> occupants
	) {
		try {
			evacuationPlan(level, bounds, occupants);
			return true;
		} catch (IllegalStateException unavailable) {
			return false;
		}
	}

	private static List<EvacuationAssignment> evacuationPlan(
			ServerLevel level,
			ScenarioArenaBlueprint.SiteBounds bounds,
			List<Entity> occupants
	) {
		if (occupants.isEmpty()) return List.of();
		ArrayList<EvacuationAssignment> result = new ArrayList<>(occupants.size());
		ArrayList<AABB> reserved = new ArrayList<>(occupants.size());
		AABB destructiveVolume = siteVolume(level, bounds);
		ArrayList<BlockPos> targets = new ArrayList<>();
		for (BlockPos column : evacuationCandidateColumns(bounds, occupants.size())) {
			int y = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, column.getX(), column.getZ());
			BlockPos target = new BlockPos(
					column.getX(), Math.clamp(y, level.getMinY() + 1, level.getMaxY() - 2), column.getZ());
			if (safeEvacuationColumn(level, target)) targets.add(target);
		}
		if (targets.size() < occupants.size()) throw new IllegalStateException("NO_SAFE_EVACUATION_SITE");
		for (Entity entity : occupants) {
			EvacuationAssignment selected = null;
			for (BlockPos target : targets) {
				AABB targetBounds = entity.getBoundingBox().move(
						target.getX() + 0.5D - entity.getX(),
						target.getY() - entity.getY(),
						target.getZ() + 0.5D - entity.getZ());
				if (!evacuationFootprint(targetBounds, target.getY() - 1).stream()
						.allMatch(floor -> safeEvacuationFloor(level, floor))
						|| targetBounds.intersects(destructiveVolume)
						|| !level.getWorldBorder().isWithinBounds(targetBounds)
						|| !level.noCollision(entity, targetBounds)
						|| !level.getEntities(entity, targetBounds.inflate(0.25D), Entity::isAlive).isEmpty()
						|| reserved.stream().anyMatch(existing -> existing.intersects(targetBounds.inflate(0.25D)))) {
					continue;
				}
				selected = new EvacuationAssignment(entity, target, targetBounds);
				break;
			}
			if (selected == null) throw new IllegalStateException("NO_SAFE_EVACUATION_SITE");
			result.add(selected);
			reserved.add(selected.bounds);
		}
		return List.copyOf(result);
	}

	static List<BlockPos> evacuationCandidateColumns(ScenarioArenaBlueprint.SiteBounds bounds, int occupantCount) {
		Objects.requireNonNull(bounds, "bounds must not be null");
		if (occupantCount < 0 || occupantCount > MAX_EVACUATION_OCCUPANTS) {
			throw new IllegalArgumentException("occupantCount is out of range");
		}
		int targetCount = Math.min(MAX_EVACUATION_COLUMNS, Math.max(64, occupantCount * 8));
		LinkedHashSet<BlockPos> columns = new LinkedHashSet<>(targetCount);
		for (int radius = 6; columns.size() < targetCount; radius += 3) {
			int minimumX = bounds.minimumX() - radius;
			int maximumX = bounds.maximumX() + radius;
			int minimumZ = bounds.minimumZ() - radius;
			int maximumZ = bounds.maximumZ() + radius;
			for (int x = minimumX; x <= maximumX && columns.size() < targetCount; x += 3) {
				columns.add(new BlockPos(x, 0, minimumZ));
				if (columns.size() < targetCount) columns.add(new BlockPos(x, 0, maximumZ));
			}
			for (int z = minimumZ + 3; z < maximumZ && columns.size() < targetCount; z += 3) {
				columns.add(new BlockPos(minimumX, 0, z));
				if (columns.size() < targetCount) columns.add(new BlockPos(maximumX, 0, z));
			}
		}
		return List.copyOf(columns);
	}

	static List<BlockPos> evacuationFootprint(AABB bounds, int floorY) {
		Objects.requireNonNull(bounds, "bounds must not be null");
		int minimumX = (int) Math.floor(bounds.minX + 1.0E-7D);
		int maximumX = (int) Math.floor(bounds.maxX - 1.0E-7D);
		int minimumZ = (int) Math.floor(bounds.minZ + 1.0E-7D);
		int maximumZ = (int) Math.floor(bounds.maxZ - 1.0E-7D);
		ArrayList<BlockPos> result = new ArrayList<>((maximumX - minimumX + 1) * (maximumZ - minimumZ + 1));
		for (int x = minimumX; x <= maximumX; x++) {
			for (int z = minimumZ; z <= maximumZ; z++) result.add(new BlockPos(x, floorY, z));
		}
		return List.copyOf(result);
	}

	private static boolean safeEvacuationColumn(ServerLevel level, BlockPos feet) {
		BlockPos floor = feet.below();
		BlockPos head = feet.above();
		return safeEvacuationFloor(level, floor)
				&& level.getBlockState(feet).getCollisionShape(level, feet).isEmpty()
				&& level.getFluidState(feet).isEmpty()
				&& level.getBlockState(head).getCollisionShape(level, head).isEmpty()
				&& level.getFluidState(head).isEmpty();
	}

	private static boolean safeEvacuationFloor(ServerLevel level, BlockPos floor) {
		var floorState = level.getBlockState(floor);
		boolean safeFloor = floorState.isFaceSturdy(level, floor, Direction.UP)
				&& !floorState.is(Blocks.CACTUS)
				&& !floorState.is(Blocks.MAGMA_BLOCK)
				&& !floorState.is(Blocks.CAMPFIRE)
				&& !floorState.is(Blocks.SOUL_CAMPFIRE)
				&& !floorState.is(Blocks.POWDER_SNOW);
		boolean stableFloor = !(floorState.getBlock() instanceof FallingBlock)
				|| level.getBlockState(floor.below()).isFaceSturdy(level, floor.below(), Direction.UP);
		return safeFloor && stableFloor && level.getFluidState(floor).isEmpty();
	}

	private static String confirmationDetail(ScenarioArenaBlueprint blueprint, SiteInspection inspection) {
		ScenarioArenaBlueprint.SiteBounds bounds = blueprint.siteBounds();
		return "Confirm overwrite of X " + bounds.minimumX() + " to " + bounds.maximumX()
				+ ", Z " + bounds.minimumZ() + " to " + bounds.maximumZ()
				+ ". Up to " + inspection.verdict.destructiveCells() + " cells are managed; "
				+ inspection.verdict.occupantCount() + " occupants will be moved to safety.";
	}

	private static String launchIntent(ScenarioLaunchRequest request) {
		return dev.agaminggod.arenaagents.scenario.ScenarioLaunchCodec.encode(new ScenarioLaunchRequest(
				request.scenarioId(), request.mapVersion(), request.deterministicEvents(),
				request.placementMode(), request.roster(), ""));
	}

	static BlockPos arenaOrigin(
			ServerLevel level,
			ScenarioPreset preset,
			ServerPlayer operator,
			ScenarioPlacementMode placementMode
	) {
		if (placementMode != ScenarioPlacementMode.FIXED_LANE) {
			int distance = placementMode == ScenarioPlacementMode.IN_FRONT_OF_PLAYER ? 80 : 0;
			int x = operator.getBlockX() + operator.getDirection().getStepX() * distance;
			int z = operator.getBlockZ() + operator.getDirection().getStepZ() * distance;
			int surface = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z);
			return new BlockPos(x, surface - 1, z);
		}
		int lane = switch (preset.category()) {
			case SURVIVAL -> 0;
			case BUILDING -> 1;
			case PVP -> 2;
			case PARKOUR -> 3;
		};
		int x = 192 + lane * 160;
		int z = 320;
		int surface = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z);
		return new BlockPos(x, Math.max(80, surface + 3), z);
	}

	private static long scenarioSeed(ServerLevel level, ScenarioPreset preset) {
		return level.getSeed() ^ ((long) preset.id().hashCode() << 32) ^ preset.mapVersion().hashCode();
	}

	private static String safeMessage(Throwable throwable) {
		String message = throwable.getMessage();
		return message == null || message.isBlank() ? throwable.getClass().getSimpleName() : message;
	}

	private static final class RuntimeState {
		private PendingSiteConfirmation pendingConfirmation;
		private ScenarioPreparationJournal preparationJournal;
		private ScenarioPreparationJournal.Snapshot preparationSnapshot;
		private BuildJob build;
		private BuildJob pendingActivation;
		private ScenarioBuildProgress buildProgress;
		private long buildProgressRevision;
		private long buildProgressTerminalTick;
		private ActivationJob activation;
		private RecoveryJob recovery;
		private CleanupJob cleanup;
		private ActiveRun activeRun;
		private PendingResult pendingResult;
		private List<AgentRecord> agents = List.of();
		private final ArrayList<ScenarioPublicEvent> publicEvents = new ArrayList<>();
		private final LinkedHashMap<String, String> participantByAgent = new LinkedHashMap<>();
		private long runtimeTick;
		private boolean restoreAttempted;
		private boolean buildFailed;
		private List<String> cancelCleanupIds = List.of();

		private long nextBuildRevision() {
			return ++buildProgressRevision;
		}
	}

	private record CleanupJob(
			ScenarioRunSnapshot failedSnapshot,
			List<String> agentIds,
			int attempts
	) {
		private CleanupJob {
			Objects.requireNonNull(failedSnapshot, "failedSnapshot must not be null");
			agentIds = List.copyOf(Objects.requireNonNull(agentIds, "agentIds must not be null"));
			if (attempts < 0) throw new IllegalArgumentException("attempts must not be negative");
		}

		private CleanupJob nextAttempt() {
			return new CleanupJob(failedSnapshot, agentIds, attempts + 1);
		}
	}

	private static final class PendingResult {
		private final ScenarioRunSnapshot snapshot;
		private final ScenarioResultPersistence persistence;
		private int loggedAttempts;

		private PendingResult(ScenarioRunSnapshot snapshot, ScenarioResultPersistence persistence) {
			this.snapshot = Objects.requireNonNull(snapshot, "snapshot must not be null");
			this.persistence = Objects.requireNonNull(persistence, "persistence must not be null");
		}
	}

	private record RecoveryJob(
			ScenarioRunSnapshot snapshot,
			ScenarioSession session,
			ScenarioRuntimeClock clock,
			ServerLevel level,
			long elapsedTicks
	) {
		private RecoveryJob nextTick() {
			return new RecoveryJob(snapshot, session, clock, level, elapsedTicks + 1L);
		}
	}

	private record PendingContestant(AgentRecord record, ScenarioAgentSpec spec, int laneIndex) {
	}

	private record ReadyContestant(PendingContestant pending, ServerPlayer player) {
	}

	private record ActivationJob(
			ScenarioSession session,
			UUID operatorId,
			ServerLevel level,
			ScenarioSessionConfig config,
			BlockPos origin,
			ScenarioResetReceipt resetReceipt,
			List<PendingContestant> contestants,
			ScenarioRosterReadinessBarrier barrier,
			String expectedDimension,
			long firstWaveStartedAtEpochMs,
			long elapsedTicks
	) {
		private ActivationJob nextTick() {
			return new ActivationJob(
					session,
					operatorId,
					level,
					config,
					origin,
					resetReceipt,
					contestants,
					barrier,
					expectedDimension,
					firstWaveStartedAtEpochMs,
					elapsedTicks + 1L
			);
		}
	}

	private record ActiveRun(
			ScenarioSession session,
			ScenarioRuntimeClock clock,
			UUID operatorId,
			ServerLevel level,
			BlockPos origin,
			ScenarioResetReceipt resetReceipt,
			ScenarioParkourRunState parkour
	) {
	}

	private record BuildJob(
			UUID operatorId,
			ServerLevel level,
			ScenarioLaunchRequest request,
			ScenarioSessionConfig config,
			ScenarioSession session,
			ScenarioArenaBlueprint blueprint,
			ScenarioArenaResetJob reset,
			ScenarioArenaResetJob.Phase reportedPhase,
			int reportedCompleted,
			boolean cancellationRequested
	) {
		private BuildJob withReportedProgress(ScenarioArenaResetJob.Phase phase, int value) {
			return new BuildJob(operatorId, level, request, config, session, blueprint, reset, phase, value,
					cancellationRequested);
		}

		private BuildJob cancelAfterSafeReset() {
			return new BuildJob(operatorId, level, request, config, session, blueprint, reset,
					reportedPhase, reportedCompleted, true);
		}

		private BuildJob restartForCancellation() {
			return new BuildJob(operatorId, level, request, config, session, blueprint,
					new ScenarioArenaResetJob(blueprint), null, -1, true);
		}
	}

	private record PendingSiteConfirmation(
			String token,
			UUID operatorId,
			String launchIntent,
			BlockPos origin,
			Set<UUID> occupantIds,
			long expiresAtTick
	) {
		private PendingSiteConfirmation {
			token = Objects.requireNonNull(token, "token must not be null");
			operatorId = Objects.requireNonNull(operatorId, "operatorId must not be null");
			launchIntent = Objects.requireNonNull(launchIntent, "launchIntent must not be null");
			origin = Objects.requireNonNull(origin, "origin must not be null").immutable();
			occupantIds = Set.copyOf(Objects.requireNonNull(occupantIds, "occupantIds must not be null"));
			if (expiresAtTick < 1L) throw new IllegalArgumentException("confirmation expiry is invalid");
		}
	}

	private record SiteInspection(ScenarioSitePreflight.Verdict verdict, Set<UUID> occupantIds) {
		private SiteInspection {
			verdict = Objects.requireNonNull(verdict, "verdict must not be null");
			occupantIds = Set.copyOf(Objects.requireNonNull(occupantIds, "occupantIds must not be null"));
		}
	}

	private record EvacuationAssignment(Entity entity, BlockPos target, AABB bounds) {
		private EvacuationAssignment {
			entity = Objects.requireNonNull(entity, "entity must not be null");
			target = Objects.requireNonNull(target, "target must not be null").immutable();
			bounds = Objects.requireNonNull(bounds, "bounds must not be null");
		}
	}
}
