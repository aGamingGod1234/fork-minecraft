package dev.agaminggod.arenaagents.scenario;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.agaminggod.arenaagents.agent.AgentLifecycleState;
import dev.agaminggod.arenaagents.agent.AgentRecord;
import dev.agaminggod.arenaagents.agent.AgentRegistry;
import dev.agaminggod.arenaagents.scenario.result.ScenarioPublicEvent;
import dev.agaminggod.arenaagents.scenario.runtime.ScenarioArenaBlueprint;
import dev.agaminggod.arenaagents.scenario.runtime.ScenarioArenaResetJob;
import dev.agaminggod.arenaagents.scenario.runtime.ScenarioRecoveryDecision;
import dev.agaminggod.arenaagents.scenario.runtime.ScenarioRecoveryGate;
import dev.agaminggod.arenaagents.scenario.runtime.ScenarioRunSnapshot;
import dev.agaminggod.arenaagents.scenario.runtime.ScenarioRuntimeClock;
import dev.agaminggod.arenaagents.scenario.runtime.ScenarioRuntimeSafetyVerification;
import dev.agaminggod.arenaagents.scenario.runtime.ScenarioOwnedAgentIds;
import dev.agaminggod.arenaagents.scenario.runtime.ScenarioPreparationJournal;
import dev.agaminggod.arenaagents.server.AgentRemovalCoordinator;
import dev.agaminggod.arenaagents.server.bridge.CoordinatorStatusSnapshot;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;

public final class ScenarioRecoveryVerification {
	private static final UUID SESSION_ID = UUID.fromString("11111111-2222-3333-4444-555555555555");
	private static final UUID OPERATOR_ID = UUID.fromString("aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee");

	private ScenarioRecoveryVerification() {
	}

	public static int verify() {
		int assertions = ScenarioRuntimeSafetyVerification.verify();
		net.minecraft.SharedConstants.tryDetectVersion();
		net.minecraft.server.Bootstrap.bootStrap();
		ScenarioSession session = runningSession();
		ScenarioRuntimeClock clock = new ScenarioRuntimeClock(session);
		clock.tick();
		clock.tick();
		session.pauseForRecovery(2L);
		ScenarioRuntimeClock.Snapshot clockSnapshot = clock.snapshot();
		assertEquals(2L, clock.tick().elapsedTick(), "recovery pause reports the frozen elapsed tick");
		assertEquals(clockSnapshot, clock.snapshot(), "recovery pause freezes all clock progress");
		assertTrue(session.resumeRecovery(2L), "recovery resumes once");
		assertFalse(session.resumeRecovery(2L), "duplicate recovery completion is idempotent");
		assertEquals(2L, clock.tick().elapsedTick(), "first resumed tick continues at exact saved position");

		ScenarioRunSnapshot snapshot = new ScenarioRunSnapshot(
				ScenarioRunSnapshot.CURRENT_VERSION,
				SESSION_ID,
				"thinking-tower",
				ScenarioPresets.require("thinking-tower").mapVersion(),
				51L,
				52L,
				ScenarioPresets.require("thinking-tower").defaultDurationTicks(),
				true,
				List.of(
						new ScenarioRunSnapshot.Participant("agent-a", "Codex Sol High", Optional.of("amber")),
						new ScenarioRunSnapshot.Participant("agent-b", "Gemini Pro High", Optional.of("blue"))
				),
				1_750_000_000_000L,
				"minecraft:overworld",
				OPERATOR_ID,
				List.of("agent-b", "agent-a"),
				Map.of("agent-b", 20, "agent-a", 10),
				new ScenarioRunSnapshot.Origin(10, 64, -10),
				ScenarioSessionState.PAUSED_RECOVERY,
				Optional.empty(),
				2L,
				Map.of("agent-b", 1.0D, "agent-a", 2.0D),
				dev.agaminggod.arenaagents.scenario.runtime.ScenarioResetReceipt.verified(
						"blueprint-sha", "blueprint-sha", 2, 2, 2
				),
				clockSnapshot,
				List.of(new ScenarioPublicEvent(
						1L, "agent-a", "Codex Sol High", "action_completed", "harvest", 0.0D,
						"acting"
				))
		);
		String encoded = snapshot.toJson();
		assertEquals(snapshot, ScenarioRunSnapshot.fromJson(encoded), "strict snapshot JSON round trip");
		assertEquals(Map.of("agent-b", 20, "agent-a", 10),
				ScenarioRunSnapshot.fromJson(encoded).parkourCheckpoints(),
				"snapshot JSON preserves per-agent parkour checkpoints");
		assertEquals(List.of("agent-b", "agent-a"),
				ScenarioOwnedAgentIds.forCleanup(List.of(), snapshot.boundAgentIds()),
				"terminal recovery cleans agents from the saved roster when memory is empty");
		assertEquals(List.of("agent-current", "agent-b", "agent-a"),
				ScenarioOwnedAgentIds.forCleanup(List.of("agent-current", "agent-b"), snapshot.boundAgentIds()),
				"terminal cleanup merges partial memory and saved bindings without duplicates");
		JsonObject legacyJson = JsonParser.parseString(encoded).getAsJsonObject();
		legacyJson.addProperty("version", 1);
		legacyJson.remove("parkourCheckpoints");
		ScenarioRunSnapshot migratedLegacy = ScenarioRunSnapshot.fromJson(legacyJson.toString());
		assertEquals(ScenarioRunSnapshot.CURRENT_VERSION, migratedLegacy.version(),
				"version one snapshots migrate to the current schema");
		assertEquals(Map.of(), migratedLegacy.parkourCheckpoints(),
				"legacy snapshots safely resume parkour participants at checkpoint zero");
		expectFailure(() -> ScenarioRunSnapshot.fromJson(encoded.replace(
				"\"version\":" + ScenarioRunSnapshot.CURRENT_VERSION, "\"version\":99")),
				"UNSUPPORTED_SCENARIO_SNAPSHOT");
		expectFailure(() -> ScenarioRunSnapshot.fromJson(encoded.substring(0, encoded.length() - 1) + ",\"extra\":true}"),
				"INVALID_SCENARIO_SNAPSHOT");
		expectFailure(() -> ScenarioRunSnapshot.fromJson(encoded.replace(
				"\"state\":\"acting\"", "\"state\":\"acting\",\"message\":\"raw provider prose\""
		)), "INVALID_SCENARIO_SNAPSHOT");

		var pausedStatus = new ScenarioRecoveryGate.AgentStatus("agent-a", AgentLifecycleState.PAUSED, true);
		var disconnectedGate = ScenarioRecoveryGate.evaluate(false, List.of(pausedStatus));
		assertFalse(disconnectedGate.ready(), "recovery stays frozen without an authenticated coordinator");
		assertEquals(List.of(), disconnectedGate.resumeAgentIds(), "disconnected recovery does not resume agents");
		var pausedGate = ScenarioRecoveryGate.evaluate(true, List.of(pausedStatus));
		assertFalse(pausedGate.ready(), "paused agents must resume before scenario time advances");
		assertEquals(List.of("agent-a"), pausedGate.resumeAgentIds(), "authenticated recovery identifies paused agent to resume");
		assertTrue(ScenarioRecoveryGate.evaluate(true, List.of(
				new ScenarioRecoveryGate.AgentStatus("agent-a", AgentLifecycleState.STARTING, true)
		)).ready(), "recovery becomes ready only after the agent is active");
		assertEquals(List.of("agent-a"), ScenarioRecoveryGate.deadAgentIds(List.of(
				new ScenarioRecoveryGate.AgentStatus("agent-b", AgentLifecycleState.PAUSED, true),
				new ScenarioRecoveryGate.AgentStatus("agent-a", AgentLifecycleState.DEAD, false)
		)), "recovery identifies dead contestants that must be recreated at their checkpoint");
		long statusNow = 50_000L;
		List<String> recoveredAgentIds = snapshot.boundAgentIds();
		assertFalse(ScenarioRecoveryGate.coordinatorReady(Optional.empty(), recoveredAgentIds, statusNow, 2_500L),
				"recovery rejects a missing coordinator status");
		assertFalse(ScenarioRecoveryGate.coordinatorReady(
				Optional.of(recoveryStatus(true, List.of("agent-a", "agent-b"), statusNow - 2_501L)),
				recoveredAgentIds, statusNow, 2_500L
		), "recovery rejects a stale coordinator status");
		assertFalse(ScenarioRecoveryGate.coordinatorReady(
				Optional.of(recoveryStatus(false, List.of("agent-a", "agent-b"), statusNow)),
				recoveredAgentIds, statusNow, 2_500L
		), "recovery rejects an unreconciled coordinator status");
		assertFalse(ScenarioRecoveryGate.coordinatorReady(
				Optional.of(recoveryStatus(true, List.of("agent-a"), statusNow)),
				recoveredAgentIds, statusNow, 2_500L
		), "recovery rejects a reconciled status missing a bound agent");
		assertTrue(ScenarioRecoveryGate.coordinatorReady(
				Optional.of(recoveryStatus(true, List.of("agent-a", "agent-b"), statusNow)),
				recoveredAgentIds, statusNow, 2_500L
		), "recovery accepts only a fresh reconciled status containing every bound agent");

		ScenarioRecoveryDecision missing = ScenarioRecoveryDecision.evaluate(snapshot, Set.of("minecraft:the_nether"));
		assertFalse(missing.recoverable(), "missing dimension fails closed");
		assertEquals(List.of("agent-a", "agent-b"), missing.boundAgentsToRemove(), "failed recovery removes every bound agent");
		assertTrue(ScenarioRecoveryDecision.evaluate(snapshot, Set.of("minecraft:overworld")).recoverable(),
				"exact saved dimension is recoverable");

		List<ScenarioArenaBlueprint.Placement> duplicates = List.of(
				new ScenarioArenaBlueprint.Placement(new BlockPos(2, 64, 1), Blocks.STONE.defaultBlockState()),
				new ScenarioArenaBlueprint.Placement(new BlockPos(1, 64, 1), Blocks.DIRT.defaultBlockState()),
				new ScenarioArenaBlueprint.Placement(new BlockPos(2, 64, 1), Blocks.GOLD_BLOCK.defaultBlockState())
		);
		List<ScenarioArenaBlueprint.Placement> canonical = ScenarioArenaResetJob.canonicalize(duplicates);
		assertEquals(2, canonical.size(), "duplicate positions canonicalize to one placement");
		assertEquals(Blocks.GOLD_BLOCK, canonical.get(1).state().getBlock(), "duplicate placements are last-write-wins");
		assertEquals(ScenarioArenaResetJob.hash(canonical), ScenarioArenaResetJob.hash(List.of(
				canonical.get(1), canonical.get(0)
		)), "reset hash is independent of input ordering");
		assertEquals(2, ScenarioArenaResetJob.batchEnd(0, 2, 2_048), "reset batch stays within available work");
		assertEquals(2_048, ScenarioArenaResetJob.batchEnd(0, 5_000, 2_048), "reset batch is capped at 2048 entries");
		assertEquals(256, ScenarioArenaResetJob.maximumWorkForPhase(ScenarioArenaResetJob.Phase.APPLY),
				"world application is paced so construction remains visible");
		assertEquals(2_048, ScenarioArenaResetJob.maximumWorkForPhase(ScenarioArenaResetJob.Phase.CLEAR),
				"site clearing removes multiple height levels per tick");
		assertEquals(2_048, ScenarioArenaResetJob.maximumWorkForPhase(ScenarioArenaResetJob.Phase.VERIFY),
				"nonvisual verification keeps the full bounded throughput");
		assertEquals(256, ScenarioArenaResetJob.maximumWorkForPhase(ScenarioArenaResetJob.Phase.REPAIR),
				"corrective world mutation is paced like initial application");
		assertEquals(ScenarioArenaResetJob.VerificationDecision.COMPLETE,
				ScenarioArenaResetJob.decideAfterVerification(0, 0),
				"an exact verification completes without a correction pass");
		assertEquals(ScenarioArenaResetJob.VerificationDecision.REPAIR,
				ScenarioArenaResetJob.decideAfterVerification(1, 0),
				"the first mismatch enters corrective repair");
		assertEquals(ScenarioArenaResetJob.VerificationDecision.REPAIR,
				ScenarioArenaResetJob.decideAfterVerification(1, 2),
				"the third and final correction pass remains available");
		assertEquals(ScenarioArenaResetJob.VerificationDecision.FAIL,
				ScenarioArenaResetJob.decideAfterVerification(1, 3),
				"a mismatch remaining after three corrections fails closed");
		List<net.minecraft.world.level.ChunkPos> managedChunks = ScenarioArenaResetJob.managedChunks(List.of(
				new ScenarioArenaBlueprint.Placement(new BlockPos(33, 64, -1), Blocks.STONE.defaultBlockState()),
				new ScenarioArenaBlueprint.Placement(new BlockPos(1, 65, 1), Blocks.DIRT.defaultBlockState()),
				new ScenarioArenaBlueprint.Placement(new BlockPos(2, 66, 2), Blocks.GOLD_BLOCK.defaultBlockState())
		));
		assertEquals(2, managedChunks.size(), "managed chunks are deduplicated before loading");
		assertEquals(new net.minecraft.world.level.ChunkPos(0, 0), managedChunks.getFirst(),
				"managed chunks use deterministic coordinate order");
		assertEquals(new net.minecraft.world.level.ChunkPos(2, -1), managedChunks.getLast(),
				"managed chunk plan preserves every occupied chunk");
		assertEquals("loading chunks", ScenarioArenaResetJob.Phase.LOAD_CHUNKS.displayName(),
				"reset exposes a human-readable chunk preparation phase");
		var verifiedReset = ScenarioArenaResetJob.verifyCanonical(canonical, List.of(canonical.get(1), canonical.get(0)));
		assertTrue(verifiedReset.verified(), "exhaustive reset verification accepts identical managed state");
		BlockState expectedLeaves = Blocks.OAK_LEAVES.defaultBlockState()
				.setValue(BlockStateProperties.PERSISTENT, true)
				.setValue(BlockStateProperties.DISTANCE, 7);
		BlockState worldNormalizedLeaves = expectedLeaves.setValue(BlockStateProperties.DISTANCE, 2);
		var normalizedReset = ScenarioArenaResetJob.verifyCanonical(
				List.of(new ScenarioArenaBlueprint.Placement(new BlockPos(3, 70, 3), expectedLeaves)),
				List.of(new ScenarioArenaBlueprint.Placement(new BlockPos(3, 70, 3), worldNormalizedLeaves))
		);
		assertTrue(normalizedReset.verified(),
				"verification accepts the world-computed leaf distance while preserving authored leaf properties");
		BlockState authoredFence = Blocks.OAK_FENCE.defaultBlockState();
		BlockState connectedFence = authoredFence.setValue(BlockStateProperties.EAST, true)
				.setValue(BlockStateProperties.WEST, true);
		assertTrue(ScenarioArenaResetJob.verifyCanonical(
				List.of(new ScenarioArenaBlueprint.Placement(new BlockPos(4, 70, 3), authoredFence)),
				List.of(new ScenarioArenaBlueprint.Placement(new BlockPos(4, 70, 3), connectedFence))
		).verified(), "verification accepts neighbor-computed fence connections");
		var mismatchedReset = ScenarioArenaResetJob.verifyCanonical(canonical, List.of(
				canonical.get(0),
				new ScenarioArenaBlueprint.Placement(canonical.get(1).position(), Blocks.IRON_BLOCK.defaultBlockState())
		));
		assertFalse(mismatchedReset.verified(), "exhaustive reset verification rejects one mismatched state");
		assertEquals(2, mismatchedReset.verifiedPlacements(), "verification inspects the full managed volume after a mismatch");

		BlockPos arenaOrigin = new BlockPos(100, 70, -40);
		List<ScenarioArenaBlueprint.Placement> survival = ScenarioArenaResetJob.canonicalize(
				ScenarioArenaBlueprint.create(ScenarioPresets.require("last-valley"), arenaOrigin).placements()
		);
		Map<Long, ScenarioArenaBlueprint.Placement> survivalByPosition = survival.stream().collect(
				java.util.stream.Collectors.toMap(placement -> placement.position().asLong(), placement -> placement)
		);
		BlockPos unobstructedRiver = arenaOrigin.offset(10, 0, 0);
		assertEquals(Blocks.WATER, survivalByPosition.get(unobstructedRiver.asLong()).state().getBlock(),
				"survival river is placed inside the ground-level trench");
		assertTrue(!survivalByPosition.containsKey(unobstructedRiver.above().asLong()),
				"survival river relies on whole-site clearing instead of a raised authored air sheet");
		assertTrue(survival.stream()
				.filter(placement -> placement.state().is(Blocks.OAK_LEAVES))
				.allMatch(placement -> placement.state().getValue(BlockStateProperties.PERSISTENT)),
				"arena leaves remain stable during a long reset and verification pass");
		List<ScenarioArenaBlueprint.Placement> pvp = ScenarioArenaResetJob.canonicalize(
				ScenarioArenaBlueprint.create(ScenarioPresets.require("citadel-collapse"), arenaOrigin).placements()
		);
		Map<Long, ScenarioArenaBlueprint.Placement> pvpByPosition = pvp.stream().collect(
				java.util.stream.Collectors.toMap(placement -> placement.position().asLong(), placement -> placement)
		);
		BlockPos river = arenaOrigin.offset(19, 0, 0);
		assertEquals(Blocks.WATER, pvpByPosition.get(river.asLong()).state().getBlock(),
				"PvP river is contained in the ground-level terrain channel");
		assertTrue(!pvpByPosition.containsKey(river.above().asLong()),
				"PvP river relies on whole-site clearing instead of a raised authored air sheet");
		int pvpRadius = 82;
		for (int coordinate = -pvpRadius; coordinate <= pvpRadius; coordinate++) {
			assertEquals(Blocks.BEDROCK, pvpByPosition.get(arenaOrigin.offset(coordinate, -7, -pvpRadius).asLong()).state().getBlock(),
					"north lower boundary is bedrock");
			assertEquals(Blocks.BEDROCK, pvpByPosition.get(arenaOrigin.offset(coordinate, -7, pvpRadius).asLong()).state().getBlock(),
					"south lower boundary is bedrock");
			assertEquals(Blocks.BARRIER, pvpByPosition.get(arenaOrigin.offset(coordinate, 20, -pvpRadius).asLong()).state().getBlock(),
					"north upper boundary is an invisible barrier");
			assertEquals(Blocks.BARRIER, pvpByPosition.get(arenaOrigin.offset(coordinate, 20, pvpRadius).asLong()).state().getBlock(),
					"south upper boundary is an invisible barrier");
		}
		assertEquals(Blocks.BEDROCK, pvpByPosition.get(arenaOrigin.offset(0, -8, 0).asLong()).state().getBlock(),
				"the PvP arena has an unbreakable floor beneath its underground spaces");
		for (int[] house : new int[][]{{-27, -18}, {28, -17}, {-25, 21}, {26, 20}, {-8, 35}, {10, -36}}) {
			assertEquals(Blocks.COBBLED_DEEPSLATE,
					pvpByPosition.get(arenaOrigin.offset(house[0], 5, house[1]).asLong()).state().getBlock(),
					"each cobbled-deepslate house has a roof");
			assertEquals(Blocks.OAK_DOOR,
					pvpByPosition.get(arenaOrigin.offset(house[0], 2, house[1] + 3).asLong()).state().getBlock(),
					"each house has a usable doorway");
			assertEquals(Blocks.CHEST,
					pvpByPosition.get(arenaOrigin.offset(house[0], 2, house[1]).asLong()).state().getBlock(),
					"each house contains a loot chest");
		}
		for (int[] dungeon : new int[][]{{-42, -35}, {43, -31}, {-39, 40}, {41, 38}, {0, -49}, {0, 49}}) {
			assertEquals(Blocks.CHEST,
					pvpByPosition.get(arenaOrigin.offset(dungeon[0], -5, dungeon[1]).asLong()).state().getBlock(),
					"each underground dungeon contains a loot chest");
			assertEquals(Blocks.COBBLESTONE,
					pvpByPosition.get(arenaOrigin.offset(dungeon[0], -6, dungeon[1]).asLong()).state().getBlock(),
					"each dungeon has a cobblestone floor");
		}
		assertEquals(30L, pvp.stream().filter(placement -> placement.state().is(Blocks.COBBLESTONE_STAIRS)).count(),
				"six underground dungeons each expose a five-step entrance");

		List<ScenarioArenaBlueprint.Placement> ordered = ScenarioArenaResetJob.applicationOrder(List.of(
				new ScenarioArenaBlueprint.Placement(new BlockPos(0, 0, 0), Blocks.STONE.defaultBlockState()),
				new ScenarioArenaBlueprint.Placement(new BlockPos(0, -1, 0), Blocks.DIRT.defaultBlockState()),
				new ScenarioArenaBlueprint.Placement(new BlockPos(0, 1, 0), Blocks.AIR.defaultBlockState()),
				new ScenarioArenaBlueprint.Placement(new BlockPos(0, 3, 0), Blocks.AIR.defaultBlockState()),
				new ScenarioArenaBlueprint.Placement(new BlockPos(1, 0, 0), Blocks.LAVA.defaultBlockState()),
				new ScenarioArenaBlueprint.Placement(new BlockPos(2, 0, 0), Blocks.WATER.defaultBlockState())
		));
		assertTrue(ordered.get(0).state().isAir() && ordered.get(1).state().isAir(),
				"reset clears managed air before building geometry");
		assertEquals(3, ordered.get(0).position().getY(), "air clearing proceeds from the top down");
		assertEquals(-1, ordered.get(2).position().getY(), "solid geometry builds from the bottom up");
		assertTrue(!ordered.get(ordered.size() - 2).state().getFluidState().isEmpty()
					&& !ordered.getLast().state().getFluidState().isEmpty(),
				"fluids are placed only after containment geometry is complete");
		var footprint = ScenarioArenaBlueprint.create(
				ScenarioPresets.require("last-valley"), new BlockPos(100, 70, -40), 2).siteBounds();
		assertTrue(footprint.minimumX() <= 36 && footprint.maximumX() >= 36,
				"site bounds include the full authored arena width");
		assertEquals(68, footprint.clearFloorY(),
				"site preparation removes the old surface down through the arena foundation depth");

		AgentRegistry registry = AgentRegistry.createDefault(() -> { }, transition -> { });
		AgentRecord removable = registry.create("codex", "gpt-5.6-sol", "high", Optional.of("Removal Test"), 1L);
		AtomicInteger removalFailures = new AtomicInteger();
		AgentRemovalCoordinator.removeRegistryFirst(
				registry,
				removable.agentId(),
				() -> { throw new IllegalStateException("cleanup failure"); },
				() -> { throw new IllegalStateException("hook failure"); },
				failure -> removalFailures.incrementAndGet()
		);
		assertEquals(List.of(), registry.records(), "registry deletion survives cleanup and hook failures");
		assertEquals(2, removalFailures.get(), "post-delete failures are reported without restoring the agent");

		java.nio.file.Path journalRoot = null;
		try {
			journalRoot = java.nio.file.Files.createTempDirectory("scenario-preparation-");
			ScenarioLaunchRequest request = new ScenarioLaunchRequest(
					"last-valley", ScenarioPresets.require("last-valley").mapVersion(), true,
					ScenarioPlacementMode.IN_FRONT_OF_PLAYER,
					List.of(
							new ScenarioAgentSpec(1, "One", "codex", "gpt-5.6-sol", "high", "priority",
									Optional.empty(), dev.agaminggod.arenaagents.agent.AgentGameMode.SURVIVAL),
							new ScenarioAgentSpec(2, "Two", "codex", "gpt-5.6-sol", "high", "priority",
									Optional.empty(), dev.agaminggod.arenaagents.agent.AgentGameMode.SURVIVAL)));
			ScenarioPreparationJournal journal = new ScenarioPreparationJournal(journalRoot);
			ScenarioPreparationJournal.Snapshot owner = new ScenarioPreparationJournal.Snapshot(
					SESSION_ID, OPERATOR_ID, "minecraft:overworld", new BlockPos(10, 70, 20), request,
					101L, 202L, 1_000L, List.of(), false).withAgentId("agent-a").cancelling();
			journal.write(owner);
			assertEquals(Optional.of(owner), journal.load(),
					"preparation journal survives restart with exact owner and cleanup IDs");
			journal.write(owner);
			assertEquals(Optional.of(owner), journal.load(), "preparation journal rewrite is idempotent");
			journal.clear();
			assertEquals(Optional.empty(), journal.load(), "completed preparation removes its durable owner record");
		} catch (java.io.IOException exception) {
			throw new AssertionError("preparation journal verification failed", exception);
		} finally {
			if (journalRoot != null) {
				try (var paths = java.nio.file.Files.walk(journalRoot)) {
					for (var path : paths.sorted(java.util.Comparator.reverseOrder()).toList()) {
						java.nio.file.Files.deleteIfExists(path);
					}
				} catch (java.io.IOException exception) {
					throw new AssertionError("could not remove preparation journal fixture", exception);
				}
			}
		}
		return 60 + assertions;
	}

	private static CoordinatorStatusSnapshot recoveryStatus(
			boolean reconciled,
			List<String> agentIds,
			long receivedAtEpochMs
	) {
		List<CoordinatorStatusSnapshot.SupportedProfile> profiles = agentIds.stream()
				.map(agentId -> new CoordinatorStatusSnapshot.SupportedProfile(
						agentId, "codex", "gpt-5.6-sol", "high"
				))
				.toList();
		return new CoordinatorStatusSnapshot(
				reconciled,
				profiles,
				profiles.size(),
				profiles.size(),
				profiles.size(),
				new CoordinatorStatusSnapshot.SchedulerStatus(0, 0, 4, 12, false),
				List.of(new CoordinatorStatusSnapshot.CircuitHealth(
						"codex", "gpt-5.6-sol", "decide", 0, 0, 0, 0.0D, "closed"
				)),
				receivedAtEpochMs
		);
	}

	private static ScenarioSession runningSession() {
		ScenarioPreset preset = ScenarioPresets.require("last-valley");
		ScenarioSession session = new ScenarioSession(new ScenarioSessionConfig(
				SESSION_ID,
				preset,
				51L,
				52L,
				preset.defaultDurationTicks(),
				true,
				List.of(
						new ScenarioParticipant("agent-a", "Codex Sol High", Optional.of("amber")),
						new ScenarioParticipant("agent-b", "Gemini Pro High", Optional.of("blue"))
				),
				1_750_000_000_000L
		));
		session.markReady(0L);
		session.beginCountdown(0L);
		session.start(0L);
		return session;
	}

	private static void expectFailure(Runnable operation, String code) {
		try {
			operation.run();
			throw new AssertionError("expected failure " + code);
		} catch (IllegalArgumentException expected) {
			if (!expected.getMessage().contains(code)) {
				throw new AssertionError("expected failure " + code + " but was " + expected.getMessage());
			}
		}
	}

	private static void assertTrue(boolean condition, String label) {
		if (!condition) throw new AssertionError(label);
	}

	private static void assertFalse(boolean condition, String label) {
		assertTrue(!condition, label);
	}

	private static void assertEquals(Object expected, Object actual, String label) {
		if (!expected.equals(actual)) {
			throw new AssertionError(label + ": expected <" + expected + "> but was <" + actual + ">");
		}
	}
}
