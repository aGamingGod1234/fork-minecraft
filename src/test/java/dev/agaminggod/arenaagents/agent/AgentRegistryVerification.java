package dev.agaminggod.arenaagents.agent;

import dev.agaminggod.arenaagents.agent.goal.GoalEvidence;
import dev.agaminggod.arenaagents.agent.goal.GoalStatus;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.OptionalDouble;
import java.util.OptionalInt;
import java.util.Set;
import java.util.UUID;

public final class AgentRegistryVerification {
	private static final long START_TIME = 1_000L;

	private AgentRegistryVerification() {
	}

	public static int verify() {
		int assertions = 0;
		assertions += verifyLifecycleAndRevisions();
		assertions += verifyAtomicStartPublication();
		assertions += verifyPendingConversationWakeRecovery();
		assertions += verifyCoordinatorRecoveryRearm();
		assertions += verifyCoordinatorCompletion();
		assertions += verifyCoordinatorCompletionPromotesQueue();
		assertions += verifyTerminalGoalControls();
		assertions += verifyQueueAndSteeringBounds();
		assertions += verifyIdentityResolution();
		assertions += verifyPersistenceRecovery();
		assertions += verifyProviderPersistenceAndMigration();
		assertions += verifyEntityLocationPersistenceAndMigration();
		assertions += verifyAutomaticProgressPersistence();
		assertions += verifyEntityRecoveryTarget();
		assertions += verifyDeathSnapshotPersistenceAndRespawn();
		return assertions;
	}

	private static int verifyAtomicStartPublication() {
		ArrayList<AgentTransition> dispatched = new ArrayList<>();
		AgentRegistry registry = new AgentRegistry(2, 1, () -> { }, dispatched::add);
		AgentRecord created = registry.create("gpt-5.6-sol", "high", Optional.of("WakeTarget"), START_TIME);

		try {
			registry.startAtomically(created.agentId(), "Respond to the player", START_TIME + 1L, (transition, commit) -> {
				throw new AgentDomainException("PUBLICATION_FAILED", "paired publication failed before commit");
			});
			throw new AssertionError("Expected atomic start publication failure");
		} catch (AgentDomainException exception) {
			assertEquals("PUBLICATION_FAILED", exception.code(), "pre-commit start publication failure code");
		}
		assertEquals(created, registry.require(created.agentId()), "pre-commit publication failure retains the exact idle record");

		try {
			registry.startAtomically(created.agentId(), "Respond to the player", START_TIME + 2L, (transition, commit) -> {
				commit.run();
				throw new AgentDomainException("PUBLICATION_FAILED", "paired publication failed after commit");
			});
			throw new AssertionError("Expected post-commit atomic start publication failure");
		} catch (AgentDomainException exception) {
			assertEquals("PUBLICATION_FAILED", exception.code(), "post-commit start publication failure code");
		}
		assertEquals(created, registry.require(created.agentId()), "post-commit publication failure restores the exact idle record");

		ArrayList<String> barrierOrder = new ArrayList<>();
		AgentTransition started = registry.startAtomically(
				created.agentId(), "Respond to the player", START_TIME + 3L,
				(transition, commit) -> {
					barrierOrder.add(transition.before().state().name());
					commit.run();
					barrierOrder.add(registry.require(created.agentId()).state().name());
				}
		);
		assertEquals(List.of("IDLE", "STARTING"), barrierOrder, "atomic start exposes a prepared transition and explicit commit point");
		assertEquals(AgentLifecycleState.STARTING, started.after().state(), "successful paired publication commits starting state");
		assertEquals(1L, started.after().goalRevision(), "successful paired publication advances the goal revision once");
		assertEquals(0, dispatched.size(), "atomic publisher owns the transition and avoids a duplicate runtime-hook publication");
		return 8;
	}

	private static int verifyPendingConversationWakeRecovery() {
		AgentRegistry source = new AgentRegistry(2, 1, () -> { }, transition -> { });
		AgentRecord created = source.create("gpt-5.6-sol", "high", Optional.of("DurableWake"), START_TIME);
		AgentRecord active = source.start(created.agentId(), "Respond to the player", START_TIME + 1L).after();
		AgentRegistry recovered = AgentRegistry.restore(
				source.snapshot(), () -> { }, transition -> { }, START_TIME + 2L, Set.of(created.agentId())
		);
		AgentRecord rearmed = recovered.require(created.agentId());
		assertEquals(AgentLifecycleState.STARTING, rearmed.state(), "durable conversation wake restores as starting");
		assertEquals(active.goalRevision(), rearmed.goalRevision(), "durable conversation wake recovery preserves its revision");
		assertEquals(active.currentGoal().orElseThrow().goalId(), rearmed.currentGoal().orElseThrow().goalId(),
				"durable conversation wake recovery preserves its exact goal identity");
		assertEquals("", rearmed.lastError(), "durable conversation wake recovery does not report a false reload pause");
		return 4;
	}

	private static int verifyCoordinatorRecoveryRearm() {
		AgentRegistry registry = new AgentRegistry(2, 1, () -> { }, transition -> { });
		AgentRecord created = registry.create(
				"kimi", "kimi-code/k3", "max", "priority", Optional.of("Recovery"), AgentGameMode.CREATIVE, START_TIME
		);
		AgentRecord active = registry.start(created.agentId(), "Keep the exact profile", START_TIME + 1L).after();
		AgentRecord disconnected = registry.disconnect(created.agentId(), START_TIME + 2L).after();

		AgentRecord rearmed = registry.rearmAfterCoordinatorRecovery(
				created.agentId().value(), disconnected.goalRevision(), START_TIME + 3L
		);
		assertEquals(AgentLifecycleState.STARTING, rearmed.state(), "coordinator recovery re-arms disconnected work");
		assertEquals(disconnected.goalRevision(), rearmed.goalRevision(), "coordinator recovery preserves the goal revision");
		assertEquals(active.currentGoal(), rearmed.currentGoal(), "coordinator recovery preserves the exact unfinished goal");
		assertEquals(created.profile(), rearmed.profile(), "coordinator recovery preserves the exact selected profile");
		assertEquals(rearmed, registry.rearmAfterCoordinatorRecovery(
				created.agentId().value(), disconnected.goalRevision(), START_TIME + 4L
		), "duplicate coordinator recovery is idempotent");
		expectFailure(
				() -> registry.rearmAfterCoordinatorRecovery(created.agentId().value(), disconnected.goalRevision() - 1L, START_TIME + 5L),
				"STALE_REVISION"
		);
		return 6;
	}

	private static int verifyLifecycleAndRevisions() {
		ArrayList<AgentTransition> transitions = new ArrayList<>();
		AgentRegistry registry = new AgentRegistry(4, 2, () -> { }, transitions::add);
		assertEquals(4, registry.availableCapacity(), "empty registry exposes all configured capacity");
		registry.requireCapacity(4);
		AgentRecord created = registry.create("gpt-5.6-sol", "HIGH", Optional.of("Builder"), START_TIME);
		assertEquals(3, registry.availableCapacity(), "creating an agent consumes one capacity slot");
		assertTrue(registry.contains(created.agentId()), "registry membership lookup finds the current agent");
		assertTrue(!registry.contains(AgentId.random()), "registry membership lookup rejects an unknown agent");
		expectFailure(() -> registry.requireCapacity(4), "AGENT_LIMIT_REACHED");
		assertEquals(AgentLifecycleState.IDLE, created.state(), "new agent is idle");
		assertEquals(RespawnPolicy.PAUSE_UNTIL_RESPAWN, created.respawnPolicy(),
				"new agents wait for an explicit respawn decision");
		assertEquals(RespawnPolicy.RESPAWN_AUTOMATICALLY,
				registry.setRespawnPolicy(created.agentId(), RespawnPolicy.RESPAWN_AUTOMATICALLY, START_TIME + 1L).respawnPolicy(),
				"automatic respawn requires an explicit policy change");

		AgentTransition started = registry.start(created.agentId(), "Build a shelter", START_TIME + 1L);
		assertEquals(AgentLifecycleState.STARTING, started.after().state(), "start state");
		assertEquals(1L, started.after().goalRevision(), "start revision");
		assertTrue(registry.isCurrentActiveRevision(created.agentId(), 1L), "current active revision accepted");
		assertTrue(!registry.isCurrentActiveRevision(created.agentId(), 0L), "stale active revision rejected");
		AgentRecord direct = registry.create("gpt-5.6-sol", "high", Optional.of("Direct"), START_TIME + 1L);
		registry.start(direct.agentId(), "Move now", START_TIME + 2L);
		assertEquals(AgentLifecycleState.ACTING,
				registry.beginAction(direct.agentId(), 1L, START_TIME + 3L).after().state(),
				"a valid action command may atomically acknowledge a delayed planning-state message");

		AgentTransition planning = registry.beginPlanning(created.agentId(), START_TIME + 2L);
		assertEquals(AgentLifecycleState.PLANNING, planning.after().state(), "planning state");
		expectFailure(
				() -> registry.beginAction(created.agentId(), 0L, START_TIME + 3L),
				"STALE_REVISION"
		);
		AgentTransition acting = registry.beginAction(created.agentId(), 1L, START_TIME + 3L);
		assertEquals(AgentLifecycleState.ACTING, acting.after().state(), "acting state");

		AgentTransition stopped = registry.stop(created.agentId(), START_TIME + 4L);
		assertTrue(stopped.cancelAction(), "stop cancels active action");
		assertTrue(stopped.interruptPlanner(), "stop interrupts planner");
		assertEquals(2L, stopped.after().goalRevision(), "stop revision");
		assertEquals(AgentLifecycleState.PAUSED, stopped.after().state(), "stop state");
		assertTrue(!registry.isCurrentActiveRevision(created.agentId(), 2L), "paused current revision rejected");
		assertTrue(!registry.isCurrentActiveRevision(AgentId.random(), 2L), "unknown agent revision rejected");

		AgentTransition stoppedAgain = registry.stop(created.agentId(), START_TIME + 5L);
		assertEquals(3L, stoppedAgain.after().goalRevision(), "repeated stop invalidates late work");
		AgentTransition resumed = registry.resume(created.agentId(), START_TIME + 6L);
		assertEquals(4L, resumed.after().goalRevision(), "resume revision");
		assertEquals(AgentLifecycleState.STARTING, resumed.after().state(), "resume state");
		AgentTransition disconnected = registry.disconnect(created.agentId(), START_TIME + 7L);
		assertEquals(AgentLifecycleState.DISCONNECTED, disconnected.after().state(), "disconnect state");
		AgentTransition resumedAfterDisconnect = registry.resume(created.agentId(), START_TIME + 8L);
		assertEquals(AgentLifecycleState.STARTING, resumedAfterDisconnect.after().state(), "resume after coordinator reconnect");
		return 26;
	}

	private static int verifyCoordinatorCompletion() {
		ArrayList<AgentTransition> transitions = new ArrayList<>();
		AgentRegistry registry = new AgentRegistry(2, 1, () -> { }, transitions::add);
		AgentRecord created = registry.create("gpt-5.6-sol", "high", Optional.of("Coordinator"), START_TIME);
		registry.start(created.agentId(), "Finish this task", START_TIME + 1L);
		registry.beginPlanning(created.agentId(), START_TIME + 2L);
		expectFailure(() -> registry.coordinatorCompleted(created.agentId(), 1L, START_TIME + 3L), "GOAL_NOT_SATISFIED");
		GoalEvidence evidence = new GoalEvidence(START_TIME + 3L, "operator_confirmed", List.of());
		AgentRecord completed = registry.satisfyGoal(created.agentId(), 1L, evidence, START_TIME + 3L).after();
		assertEquals(AgentLifecycleState.COMPLETED, completed.state(), "factual satisfaction completes the goal");
		assertEquals(GoalStatus.SATISFIED, completed.currentGoal().orElseThrow().status(), "completed goal is satisfied");
		assertEquals(completed, registry.coordinatorCompleted(created.agentId(), 2L, START_TIME + 4L),
				"repeated coordinator completion is idempotent only after satisfaction");
		expectFailure(() -> registry.coordinatorCompleted(created.agentId(), 0L, START_TIME + 5L), "STALE_REVISION");
		return 5;
	}

	private static int verifyCoordinatorCompletionPromotesQueue() {
		ArrayList<AgentTransition> transitions = new ArrayList<>();
		AgentRegistry registry = new AgentRegistry(2, 2, () -> { }, transitions::add);
		AgentRecord created = registry.create("gpt-5.6-sol", "high", Optional.of("QueuedCoordinator"), START_TIME);
		registry.start(created.agentId(), "Finish this task", START_TIME + 1L);
		registry.queue(created.agentId(), "Start the queued task", START_TIME + 2L);
		expectFailure(() -> registry.coordinatorCompleted(created.agentId(), 1L, START_TIME + 3L), "GOAL_NOT_SATISFIED");
		AgentRecord retained = registry.require(created.agentId());
		assertEquals(AgentLifecycleState.STARTING, retained.state(), "unverified completion keeps the goal active");
		assertEquals(1, retained.queuedGoals().size(), "unverified completion keeps queued work intact");
		return 3;
	}

	private static int verifyTerminalGoalControls() {
		AgentRegistry registry = new AgentRegistry(2, 1, () -> { }, transition -> { });
		AgentRecord created = registry.create("gpt-5.6-sol", "high", Optional.of("Completed"), START_TIME);
		AgentRecord active = registry.start(created.agentId(), "Get an iron pickaxe", START_TIME + 1L).after();
		GoalEvidence evidence = new GoalEvidence(2L, "inventory_contains", List.of(
				new GoalEvidence.Fact("inventory_contains", true, "minecraft:iron_pickaxe x1", "minecraft:iron_pickaxe x1")
		));
		AgentRecord completed = registry.satisfyGoal(
				created.agentId(), active.goalRevision(), evidence, START_TIME + 2L).after();

		expectFailure(() -> registry.stop(created.agentId(), START_TIME + 3L), "TERMINAL_GOAL");
		assertEquals(completed, registry.require(created.agentId()), "rejected stop preserves the completed record exactly");
		expectFailure(() -> registry.steer(created.agentId(), "Continue", START_TIME + 4L), "TERMINAL_GOAL");
		assertEquals(completed, registry.require(created.agentId()), "rejected steering cannot resurrect satisfied work");
		expectFailure(
				() -> completed.currentGoal().orElseThrow().steer("Continue", START_TIME + 4L),
				"TERMINAL_GOAL"
		);
		AgentTransition disconnected = registry.disconnect(created.agentId(), START_TIME + 5L);
		assertEquals(completed, disconnected.after(), "coordinator disconnect leaves completed work immutable");
		assertEquals(completed, registry.require(created.agentId()), "disconnect cannot create a resumable terminal record");
		return 7;
	}

	private static int verifyQueueAndSteeringBounds() {
		AgentRegistry registry = new AgentRegistry(2, 1, () -> { }, transition -> { });
		AgentRecord created = registry.create("gpt-5.6-sol", "xhigh", Optional.empty(), START_TIME);
		registry.start(created.agentId(), "Gather wood", START_TIME + 1L);
		AgentTransition queued = registry.queue(created.agentId(), "Build tools", START_TIME + 2L);
		assertEquals(1, queued.after().queuedGoals().size(), "queue append");
		expectFailure(() -> registry.queue(created.agentId(), "Mine stone", START_TIME + 3L), "QUEUE_FULL");

		AgentTransition steered = registry.steer(created.agentId(), "Avoid the ravine", START_TIME + 4L);
		assertEquals(2L, steered.after().goalRevision(), "steer revision");
		assertEquals(1, steered.after().currentGoal().orElseThrow().steeringInstructions().size(), "steer history");
		assertTrue(steered.cancelAction(), "steer cancels active action");

		AgentTransition completed = registry.completeGoal(
				created.agentId(),
				steered.after().goalRevision(),
				START_TIME + 5L
		);
		assertEquals(AgentLifecycleState.STARTING, completed.after().state(), "queued goal promotion state");
		assertEquals("Build tools", completed.after().currentGoal().orElseThrow().prompt(), "promoted goal");
		assertEquals(0, completed.after().queuedGoals().size(), "queue emptied by promotion");
		return 9;
	}

	private static int verifyIdentityResolution() {
		AgentRegistry registry = AgentRegistry.createDefault(() -> { }, transition -> { });
		AgentRecord named = registry.create("gpt-5.5", "high", Optional.of("Scout"), START_TIME);
		assertEquals(named.agentId(), registry.resolve("scout").agentId(), "case-insensitive name resolution");
		assertEquals(named.agentId(), registry.resolve(named.agentId().shortValue()).agentId(), "short ID resolution");
		AgentRecord suffixed = registry.create(
				"gpt-5.6-sol", "high", Optional.of("SCOUT"), START_TIME + 1L);
		assertEquals("SCOUT2", suffixed.profile().userName().orElseThrow(),
				"duplicate public names receive the smallest numeric suffix automatically");
		assertEquals(suffixed.agentId(), registry.resolve("scout2").agentId(),
				"the allocated public name is a command selector");
		AgentRecord automatic = registry.create("gpt-5.6-sol", "high", Optional.empty(), START_TIME + 2L);
		AgentRecord automatic2 = registry.create("gpt-5.6-sol", "high", Optional.empty(), START_TIME + 3L);
		assertEquals("GPT_5_6_Sol", automatic.profile().userName().orElseThrow(),
				"blank direct summons materialize a stable model-derived public name");
		assertEquals("GPT_5_6_Sol2", automatic2.profile().userName().orElseThrow(),
				"same-model direct summons receive a stable collision suffix");
		AgentRegistry liveNameRegistry = AgentRegistry.createDefault(() -> { }, transition -> { });
		AgentRecord liveNameCollision = liveNameRegistry.create(
				"codex", "gpt-5.6-sol", "high", "priority", Optional.empty(), AgentGameMode.SURVIVAL,
				START_TIME + 4L, List.of("HumanPlayer", "gpt_5_6_sol"));
		assertEquals("GPT_5_6_Sol2", liveNameCollision.profile().userName().orElseThrow(),
				"live GameProfile names share the same case-insensitive allocation namespace");
		return 8;
	}

	private static int verifyPersistenceRecovery() {
		AgentRegistry registry = AgentRegistry.createDefault(() -> { }, transition -> { });
		AgentRecord created = registry.create("gpt-5.6-sol", "high", Optional.of("Miner"), START_TIME);
		registry.start(created.agentId(), "Mine iron", START_TIME + 1L);
		registry.beginPlanning(created.agentId(), START_TIME + 2L);
		AgentRecord paused = registry.create("gpt-5.6-sol", "high", Optional.of("Paused Miner"), START_TIME + 2L);
		registry.start(paused.agentId(), "Wait for the operator", START_TIME + 3L);
		paused = registry.stop(paused.agentId(), START_TIME + 4L).after();

		AgentRegistrySnapshotCodec codec = new AgentRegistrySnapshotCodec();
		String encoded = codec.encode(registry.snapshot());
		AgentRegistry.Snapshot decoded = codec.decode(encoded);
		AgentRegistry recovered = AgentRegistry.restore(decoded, () -> { }, transition -> { }, START_TIME + 5L);
		AgentRecord restored = recovered.require(created.agentId());
		assertEquals(AgentLifecycleState.STARTING, restored.state(), "active reload re-arms unfinished work");
		assertEquals(1L, restored.goalRevision(), "reload preserves the active goal revision");
		assertEquals("Mine iron", restored.currentGoal().orElseThrow().prompt(), "reload goal");
		assertEquals(registry.require(created.agentId()).profile(), restored.profile(), "reload preserves the exact selected profile");
		AgentRecord restoredPaused = recovered.require(paused.agentId());
		assertEquals(AgentLifecycleState.PAUSED, restoredPaused.state(), "explicit pause survives reload");
		assertEquals(paused.goalRevision(), restoredPaused.goalRevision(), "explicit pause revision survives reload");
		assertEquals(paused.currentGoal().orElseThrow().prompt(), restoredPaused.currentGoal().orElseThrow().prompt(),
				"explicit pause goal survives reload");

		String legacyFalseCompletion = encoded.replace(
				"\"state\":\"PLANNING\"", "\"state\":\"COMPLETED\""
		);
		AgentRecord migratedCompletion = codec.decode(legacyFalseCompletion).records().getFirst();
		assertEquals(AgentLifecycleState.PAUSED, migratedCompletion.state(),
				"legacy completed state with unfinished work migrates to a safe pause");
		assertEquals(GoalStatus.ACTIVE, migratedCompletion.currentGoal().orElseThrow().status(),
				"legacy false-completion migration preserves the unfinished goal");
		return 9;
	}

	private static int verifyProviderPersistenceAndMigration() {
		AgentRegistrySnapshotCodec codec = new AgentRegistrySnapshotCodec();
		AgentProfile kimi = new AgentProfile("kimi", "kimi-code/k3", "max", Optional.empty(), 2);
		AgentProfile fastCodex = new AgentProfile(
				"codex", "gpt-5.6-luna", "xhigh", "fast", Optional.of("Fast"), 3, AgentGameMode.SURVIVAL
		);
		AgentRecord record = AgentRecord.create(AgentId.random(), kimi, START_TIME);
		AgentRecord fastRecord = AgentRecord.create(AgentId.random(), fastCodex, START_TIME + 1L);
		AgentRegistry.Snapshot snapshot = new AgentRegistry.Snapshot(
				AgentConstants.SCHEMA_VERSION,
				AgentConstants.DEFAULT_AGENT_LIMIT,
				AgentConstants.DEFAULT_QUEUE_LIMIT,
				List.of(record, fastRecord)
		);
		String encoded = codec.encode(snapshot);
		AgentRegistry.Snapshot roundTrip = codec.decode(encoded);
		AgentProfile decoded = roundTrip.records().getFirst().profile();
		assertEquals("kimi", decoded.provider(), "provider round-trip");
		assertEquals("Kimi K3 Max | Orchid", decoded.nameTag(), "provider and skin aware name tag");
		assertEquals("fast", roundTrip.records().get(1).profile().serviceTier(), "fast service tier round-trip");

		String legacyProvider = encoded.replace("\"provider\":\"kimi\",", "");
		assertEquals("codex", codec.decode(legacyProvider).records().getFirst().profile().provider(), "legacy provider migration");
		String legacyTier = encoded.replace(",\"service_tier\":\"fast\"", "");
		assertEquals("priority", codec.decode(legacyTier).records().get(1).profile().serviceTier(),
				"legacy service tier migration defaults to priority");

		AgentProfile legacyNamed = new AgentProfile(
				"codex", "gpt-5.6-sol", "high", "priority", Optional.of("GPT-5.6-Sol"), 0,
				AgentGameMode.SURVIVAL);
		AgentProfile collidingLegacyNamed = new AgentProfile(
				"codex", "gpt-5.6-sol", "high", "priority", Optional.of("GPT 5.6 Sol"), 1,
				AgentGameMode.SURVIVAL);
		AgentRecord legacyNamedRecord = AgentRecord.create(AgentId.random(), legacyNamed, START_TIME + 2L);
		AgentRecord collidingLegacyRecord = AgentRecord.create(
				AgentId.random(), collidingLegacyNamed, START_TIME + 3L);
		int[] migrationChanges = {0};
		AgentRegistry migratedNames = AgentRegistry.restore(new AgentRegistry.Snapshot(
				AgentConstants.SCHEMA_VERSION, AgentConstants.DEFAULT_AGENT_LIMIT,
				AgentConstants.DEFAULT_QUEUE_LIMIT, List.of(legacyNamedRecord, collidingLegacyRecord)
		), () -> migrationChanges[0]++, transition -> { }, START_TIME + 4L);
		assertEquals("GPT_5_6_Sol",
				migratedNames.require(legacyNamedRecord.agentId()).profile().userName().orElseThrow(),
				"restore canonicalizes a legacy decorated public name deterministically");
		assertEquals("GPT_5_6_Sol2",
				migratedNames.require(collidingLegacyRecord.agentId()).profile().userName().orElseThrow(),
				"restore resolves canonicalization collisions in persisted record order");
		assertEquals(legacyNamedRecord.agentId(), migratedNames.resolve("GPT-5.6-Sol").agentId(),
				"the legacy selector remains available during the migration session");
		assertEquals(1, migrationChanges[0], "public-name migration dirties the restored snapshot exactly once");
		return 9;
	}

	private static int verifyEntityLocationPersistenceAndMigration() {
		AgentRegistrySnapshotCodec codec = new AgentRegistrySnapshotCodec();
		AgentRecord record = AgentRecord.create(
				AgentId.random(),
				new AgentProfile("codex", "gpt-5.6-sol", "high", Optional.empty(), 0),
				START_TIME
		).withEntity(
				Optional.of(UUID.fromString("01234567-89ab-cdef-0123-456789abcdef")),
				Optional.of(AgentEntityLocation.exact(
						"minecraft:the_nether", 12, -8, 200.25D, 71.125D, -120.75D, 137.5F, -22.25F)),
				START_TIME + 1L
		);
		AgentRegistry.Snapshot snapshot = new AgentRegistry.Snapshot(
				AgentConstants.SCHEMA_VERSION,
				AgentConstants.DEFAULT_AGENT_LIMIT,
				AgentConstants.DEFAULT_QUEUE_LIMIT,
				List.of(record)
		);

		String encoded = codec.encode(snapshot);
		AgentRecord decoded = codec.decode(encoded).records().getFirst();
		assertEquals(record.entityLocation(), decoded.entityLocation(), "entity location round-trip");
		assertTrue(encoded.contains("\"block_y\":71"), "entity recovery height is persisted");
		assertTrue(encoded.contains("\"exact_x\":200.25"), "exact recovery X is persisted");
		assertTrue(encoded.contains("\"exact_y\":71.125"), "exact recovery Y is persisted");
		assertTrue(encoded.contains("\"exact_z\":-120.75"), "exact recovery Z is persisted");
		assertTrue(encoded.contains("\"yaw\":137.5"), "recovery yaw is persisted");
		assertTrue(encoded.contains("\"pitch\":-22.25"), "recovery pitch is persisted");

		String coarse = encoded
				.replace(",\"exact_x\":200.25", "")
				.replace(",\"exact_y\":71.125", "")
				.replace(",\"exact_z\":-120.75", "")
				.replace(",\"yaw\":137.5", "")
				.replace(",\"pitch\":-22.25", "");
		AgentEntityLocation migratedCoarse = codec.decode(coarse).records().getFirst().entityLocation().orElseThrow();
		assertEquals(new AgentEntityLocation("minecraft:the_nether", 12, -8, OptionalInt.of(71)), migratedCoarse,
				"coarse entity location snapshots remain loadable");

		String legacy = coarse.replace(
				",\"entity_location\":{\"dimension\":\"minecraft:the_nether\",\"chunk_x\":12,\"chunk_z\":-8,\"block_y\":71}",
				""
		);
		AgentRecord migrated = codec.decode(legacy).records().getFirst();
		assertEquals(Optional.empty(), migrated.entityLocation(), "legacy entity location migration");
		assertEquals(record.entityUuid(), migrated.entityUuid(), "legacy entity UUID preserved");

		AgentRegistry registry = AgentRegistry.restore(snapshot, () -> { }, transition -> { }, START_TIME + 2L);
		AgentRecord detached = registry.detachEntity(record.agentId(), START_TIME + 3L);
		assertEquals(Optional.empty(), detached.entityUuid(), "missing physical player clears stale entity UUID");
		assertEquals(Optional.empty(), detached.entityLocation(), "missing physical player clears stale entity location");
		expectFailure(() -> new AgentEntityLocation(
				"minecraft:overworld", 0, 0, OptionalInt.of(64),
				OptionalDouble.of(Double.NaN), OptionalDouble.of(64.0D), OptionalDouble.of(0.0D),
				OptionalDouble.empty(), OptionalDouble.empty()), "INVALID_ENTITY_LOCATION");
		return 13;
	}

	private static int verifyAutomaticProgressPersistence() {
		AgentRegistrySnapshotCodec codec = new AgentRegistrySnapshotCodec();
		AgentRegistry registry = AgentRegistry.createDefault(() -> { }, transition -> { });
		AgentRecord record = registry.create("gpt-5.6-sol", "high", Optional.empty(), START_TIME);
		assertTrue(record.automaticProgress(), "automatic progress defaults on");
		registry.setAutomaticProgress(record.agentId(), false, START_TIME + 1L);

		String encoded = codec.encode(registry.snapshot());
		assertTrue(encoded.contains("\"automatic_progress\":false"), "automatic progress is persisted");
		assertTrue(!codec.decode(encoded).records().getFirst().automaticProgress(), "disabled automatic progress round-trip");

		String legacy = encoded.replace(",\"automatic_progress\":false", "");
		assertTrue(codec.decode(legacy).records().getFirst().automaticProgress(), "legacy automatic progress defaults on");
		return 4;
	}

	private static int verifyEntityRecoveryTarget() {
		AgentRecord legacy = AgentRecord.create(
				AgentId.random(),
				new AgentProfile("codex", "gpt-5.6-sol", "high", Optional.empty(), 0),
				START_TIME
		).withEntityUuid(
				Optional.of(UUID.fromString("aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee")),
				START_TIME + 1L
		);
		assertEquals(Optional.empty(), AgentEntityRecoveryTarget.from(legacy), "legacy record has no recovery target");

		AgentEntityLocation location = new AgentEntityLocation("minecraft:the_end", -3, 7);
		AgentRecord located = legacy.withEntityLocation(location, START_TIME + 2L);
		AgentEntityRecoveryTarget target = AgentEntityRecoveryTarget.from(located).orElseThrow();
		assertEquals(legacy.entityUuid().orElseThrow(), target.entityUuid(), "recovery target entity UUID");
		assertEquals(location, target.location(), "recovery target location");
		return 3;
	}

	private static int verifyDeathSnapshotPersistenceAndRespawn() {
		AgentRecord active = AgentRecord.create(
				AgentId.random(),
				new AgentProfile("codex", "gpt-5.6-sol", "high", Optional.of("Miner"), 0),
				START_TIME
		);
		active = AgentLifecycleReducer.start(active, "Mine iron", START_TIME + 1L).after();
		AgentDeathSnapshot death = new AgentDeathSnapshot(
				"fell from a high place", "minecraft:the_nether", 12.5D, 64.0D, -3.5D,
				Optional.of("minecraft:overworld"), Optional.of(100.5D), Optional.of(70.0D), Optional.of(-20.5D),
				Optional.of(37.5F), Optional.of(-12.25F), Optional.of(true), "spectator",
				START_TIME + 2L
		);
		AgentTransition died = AgentLifecycleReducer.die(active, death, START_TIME + 3L);
		assertEquals(AgentLifecycleState.DEAD, died.after().state(), "death enters persistent dead state");
		assertEquals(active.currentGoal(), died.after().currentGoal(), "death retains current goal");
		assertEquals(active.goalRevision(), died.after().goalRevision(), "death preserves the unfinished goal revision");
		assertEquals(active.queuedGoals(), died.after().queuedGoals(), "death retains queued goals");
		assertEquals(active.profile(), died.after().profile(), "death retains selected model profile");
		assertEquals(Optional.of(death), died.after().deathSnapshot(), "death retains exact factual snapshot");
		expectFailure(() -> AgentLifecycleReducer.start(died.after(), "Restart", START_TIME + 4L), "INVALID_TRANSITION");

		AgentRegistrySnapshotCodec codec = new AgentRegistrySnapshotCodec();
		AgentRegistry.Snapshot snapshot = new AgentRegistry.Snapshot(
				AgentConstants.SCHEMA_VERSION, AgentConstants.DEFAULT_AGENT_LIMIT, AgentConstants.DEFAULT_QUEUE_LIMIT, List.of(died.after())
		);
		String encoded = codec.encode(snapshot);
		AgentRecord roundTrip = codec.decode(encoded).records().getFirst();
		assertEquals(died.after().deathSnapshot(), roundTrip.deathSnapshot(), "death snapshot persistence round-trip");
		assertEquals("spectator", roundTrip.deathSnapshot().orElseThrow().gameMode(), "actual live game mode survives restart");
		assertEquals(Optional.of(true), roundTrip.deathSnapshot().orElseThrow().respawnForced(), "forced vanilla respawn flag survives restart");
		AgentDeathSnapshot legacyShape = new AgentDeathSnapshot(
				"legacy death", "minecraft:overworld", 1.0D, 64.0D, 1.0D,
				Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(), START_TIME + 2L
		);
		assertEquals("survival", legacyShape.gameMode(), "legacy death constructor defaults to survival");
		String legacy = encoded.replaceFirst(",\\\"death_snapshot\\\":\\{[^}]*\\}", "");
		assertEquals(Optional.empty(), codec.decode(legacy).records().getFirst().deathSnapshot(), "legacy saves default death snapshot absent");

		AgentRegistry registry = AgentRegistry.restore(codec.decode(encoded), () -> { }, transition -> { }, START_TIME + 4L);
		AgentRecord exactDead = registry.require(active.agentId());
		try {
			registry.respawnAtomically(active.agentId(), UUID.randomUUID(), START_TIME + 5L, (transition, commit) -> {
				throw new AgentDomainException("RESPAWN_BARRIER_FAILED", "result/control publication failed");
			});
			throw new AssertionError("Expected transactional respawn barrier failure");
		} catch (AgentDomainException exception) {
			assertEquals("RESPAWN_BARRIER_FAILED", exception.code(), "respawn barrier failure code");
		}
		assertEquals(exactDead, registry.require(active.agentId()), "failed respawn retains the exact DEAD record and snapshot");
		try {
			registry.respawnAtomically(active.agentId(), UUID.randomUUID(), START_TIME + 5L, (transition, commit) -> {
				commit.run();
				throw new AgentDomainException("RESPAWN_PUBLICATION_FAILED", "publication failed after commit");
			});
			throw new AssertionError("Expected post-commit respawn barrier failure");
		} catch (AgentDomainException exception) {
			assertEquals("RESPAWN_PUBLICATION_FAILED", exception.code(), "post-commit barrier failure code");
		}
		assertEquals(exactDead, registry.require(active.agentId()), "post-commit barrier failure restores the exact DEAD record");
		AgentTransition respawned = registry.respawnAtomically(active.agentId(), UUID.randomUUID(), START_TIME + 6L, (transition, commit) -> commit.run());
		assertEquals(AgentLifecycleState.STARTING, respawned.after().state(), "respawn restarts a goal that death interrupted");
		assertEquals(exactDead.goalRevision(), respawned.after().goalRevision(), "respawn preserves the interrupted goal revision");
		assertTrue(registry.isCurrentActiveRevision(active.agentId(), exactDead.goalRevision()), "respawn accepts the same unfinished goal revision");
		assertEquals(Optional.empty(), respawned.after().deathSnapshot(), "only successful respawn clears death snapshot");

		AgentRecord explicitlyPaused = AgentLifecycleReducer.stop(active, START_TIME + 4L).after();
		AgentRecord pausedDead = AgentLifecycleReducer.die(explicitlyPaused, death, START_TIME + 5L).after();
		AgentTransition pausedRespawn = AgentLifecycleReducer.respawn(pausedDead, UUID.randomUUID(), START_TIME + 6L);
		assertEquals(AgentLifecycleState.PAUSED, pausedRespawn.after().state(), "respawn preserves an explicit user pause");
		assertTrue(!pausedRespawn.after().acceptsRevision(pausedRespawn.after().goalRevision()), "paused respawn does not accept coordinator work");

		AgentRecord disconnected = AgentLifecycleReducer.disconnect(active, START_TIME + 4L).after();
		AgentRecord disconnectedDead = AgentLifecycleReducer.die(disconnected, death, START_TIME + 5L).after();
		assertTrue(disconnectedDead.resumeAfterRespawn(), "death while disconnected retains automatic continuation intent");
		AgentTransition disconnectedRespawn = AgentLifecycleReducer.respawn(disconnectedDead, UUID.randomUUID(), START_TIME + 6L);
		assertEquals(AgentLifecycleState.STARTING, disconnectedRespawn.after().state(),
				"respawn restarts a goal interrupted while the coordinator was disconnected");
		assertEquals(disconnected.goalRevision(), disconnectedRespawn.after().goalRevision(),
				"disconnected respawn preserves the unfinished goal revision");

		GoalEvidence evidence = new GoalEvidence(4L, "inventory_contains", List.of(
				new GoalEvidence.Fact("inventory_contains", true, "minecraft:iron_pickaxe x1", "minecraft:iron_pickaxe x1")
		));
		AgentRecord satisfied = AgentLifecycleReducer.satisfyGoal(
				active, active.goalRevision(), evidence, START_TIME + 4L).after();
		AgentRecord satisfiedDead = AgentLifecycleReducer.die(satisfied, death, START_TIME + 5L).after();
		assertEquals(GoalStatus.SATISFIED, satisfiedDead.currentGoal().orElseThrow().status(),
				"death retains factual completion evidence");
		assertTrue(!satisfiedDead.resumeAfterRespawn(), "completed work has no continuation intent");
		AgentTransition satisfiedRespawn = AgentLifecycleReducer.respawn(
				satisfiedDead, UUID.randomUUID(), START_TIME + 6L);
		assertEquals(AgentLifecycleState.COMPLETED, satisfiedRespawn.after().state(),
				"respawn preserves the terminal lifecycle of a satisfied goal");
		assertEquals(satisfied.goalRevision(), satisfiedRespawn.after().goalRevision(),
				"completed respawn preserves the satisfied goal revision");
		assertTrue(!satisfiedRespawn.after().acceptsRevision(satisfied.goalRevision()),
				"completed respawn cannot restart terminal coordinator work");
		AgentTransition nextGoal = AgentLifecycleReducer.start(
				satisfiedRespawn.after(), "Get some wood", START_TIME + 7L);
		assertEquals(AgentLifecycleState.STARTING, nextGoal.after().state(),
				"a new goal can start normally after completed respawn");
		assertEquals("Get some wood", nextGoal.after().currentGoal().orElseThrow().prompt(),
				"new work replaces the retained completed goal");

		String legacyWithoutContinuationIntent = encoded.replaceFirst(",\\\"resume_after_respawn\\\":true", "");
		AgentRecord legacyDead = codec.decode(legacyWithoutContinuationIntent).records().getFirst();
		assertTrue(!legacyDead.resumeAfterRespawn(), "legacy dead records fail safe without continuation intent");
		assertEquals(AgentLifecycleState.PAUSED,
				AgentLifecycleReducer.respawn(legacyDead, UUID.randomUUID(), START_TIME + 7L).after().state(),
				"legacy dead records remain paused after respawn");
		return 36;
	}

	private static void expectFailure(Runnable operation, String expectedCode) {
		try {
			operation.run();
			throw new AssertionError("Expected failure " + expectedCode);
		} catch (AgentDomainException exception) {
			assertEquals(expectedCode, exception.code(), "failure code");
		}
	}

	private static void assertTrue(boolean value, String label) {
		if (!value) {
			throw new AssertionError(label + ": expected true");
		}
	}

	private static void assertEquals(Object expected, Object actual, String label) {
		if (!expected.equals(actual)) {
			throw new AssertionError(label + ": expected <" + expected + "> but was <" + actual + ">");
		}
	}
}
