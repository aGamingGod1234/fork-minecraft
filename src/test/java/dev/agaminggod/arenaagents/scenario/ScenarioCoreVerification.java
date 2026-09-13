package dev.agaminggod.arenaagents.scenario;

import dev.agaminggod.arenaagents.agent.AgentGameMode;
import dev.agaminggod.arenaagents.agent.AgentLifecycleState;
import dev.agaminggod.arenaagents.scenario.runtime.ScenarioLoadoutPlan;
import dev.agaminggod.arenaagents.scenario.runtime.ScenarioLoadoutService;
import dev.agaminggod.arenaagents.scenario.runtime.ScenarioLootManifest;
import dev.agaminggod.arenaagents.scenario.runtime.ScenarioEventMarker;
import dev.agaminggod.arenaagents.scenario.runtime.ScenarioParkourCourse;
import dev.agaminggod.arenaagents.scenario.runtime.ScenarioParkourRecovery;
import dev.agaminggod.arenaagents.scenario.runtime.ScenarioParkourRunState;
import dev.agaminggod.arenaagents.scenario.runtime.ScenarioParticipantPolicy;
import dev.agaminggod.arenaagents.scenario.runtime.ScenarioPlacementBatchPolicy;
import dev.agaminggod.arenaagents.scenario.runtime.ScenarioRosterReadinessBarrier;
import dev.agaminggod.arenaagents.scenario.runtime.ScenarioRosterActivator;
import dev.agaminggod.arenaagents.scenario.runtime.ScenarioRuntimeClock;
import dev.agaminggod.arenaagents.client.navigation.GridPosition;
import dev.agaminggod.arenaagents.client.navigation.LocalPathfinder;
import dev.agaminggod.arenaagents.client.navigation.PathOutcome;
import dev.agaminggod.arenaagents.client.navigation.WalkabilityView;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.Optional;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Blocks;

public final class ScenarioCoreVerification {
	private static final UUID SESSION_ID = UUID.fromString("11111111-2222-3333-4444-555555555555");
	private static final long CREATED_AT_EPOCH_MS = 1_750_000_000_000L;

	private ScenarioCoreVerification() {
	}

	public static void main(String[] args) {
		int assertions = verify();
		System.out.printf("PASS: %d scenario core assertions%n", assertions);
	}

	public static int verify() {
		int assertions = 0;
		assertions += verifyBuiltInPresets();
		assertions += verifySessionConfigValidation();
		assertions += verifyDeterministicSpawnAllocation();
		assertions += verifyAdaptiveSpawnLayouts();
		assertions += verifyRepresentativeArenaScaling();
		assertions += verifyAuthoredSpawnStations();
		assertions += verifyBuildingPlotSpawnAlignment();
		assertions += verifyScenarioCompletionPolicy();
		assertions += verifyControllerReachableParkourCourse();
		assertions += verifyParkourCheckpointsAndRecovery();
		assertions += verifyParkourParticipantRuntime();
		assertions += verifyStandardizedLoadouts();
		assertions += verifyPvpLootManifests();
		assertions += verifyLoadoutApplication();
		assertions += verifyRosterReadinessBarrier();
		assertions += verifyRosterActivationOrder();
		assertions += verifyPlacementBatchPolicy();
		assertions += verifyDeterministicDirectorEvents();
		assertions += verifySafeEventMarkers();
		assertions += verifyRuntimeClockDispatch();
		assertions += verifyLegacyRuntimeClockBoundaryRestore();
		assertions += verifyPhases();
		assertions += verifyLifecycle();
		assertions += verifyScoringAndEvidence();
		assertions += verifyReset();
		return assertions;
	}

	private static int verifyRepresentativeArenaScaling() {
		net.minecraft.SharedConstants.tryDetectVersion();
		net.minecraft.server.Bootstrap.bootStrap();
		BlockPos origin = new BlockPos(100, 70, -40);
		int assertions = 0;
		for (ScenarioPreset preset : ScenarioPresets.all()) {
			for (int count : new int[]{2, 8, 16}) {
				var blueprint = dev.agaminggod.arenaagents.scenario.runtime.ScenarioArenaBlueprint.create(
						preset, origin, count);
				Map<Long, dev.agaminggod.arenaagents.scenario.runtime.ScenarioArenaBlueprint.Placement> byPosition =
						dev.agaminggod.arenaagents.scenario.runtime.ScenarioArenaResetJob.canonicalize(
								blueprint.placements()).stream().collect(java.util.stream.Collectors.toMap(
								placement -> placement.position().asLong(), placement -> placement));
				List<ScenarioSpawnLayout.Slot> slots = ScenarioSpawnLayout.slots(preset.category(), count);
				assertEquals(count, slots.size(), preset.id() + " representative roster size " + count);
				assertTrue(slots.stream().allMatch(slot -> byPosition.containsKey(
						origin.offset(slot.x(), slot.floorY(), slot.z()).asLong())),
						preset.id() + " authors every representative spawn " + count);
				assertTrue(blueprint.siteBounds().columnCount() > 0,
						preset.id() + " has a nonempty managed footprint " + count);
				assertions += 3;
			}
		}
		return assertions;
	}

	private static int verifyRosterReadinessBarrier() {
		ScenarioRosterReadinessBarrier barrier = new ScenarioRosterReadinessBarrier(
				List.of("agent-a", "agent-b", "agent-c"),
				240L
		);
		ScenarioRosterReadinessBarrier.Assessment partial = barrier.assess(
				0L,
				List.of("agent-a", "agent-c")
		);
		assertEquals(
				ScenarioRosterReadinessBarrier.Status.WAITING,
				partial.status(),
				"partial fake-player roster remains behind readiness barrier"
		);
		assertEquals(List.of("agent-b"), partial.missingIds(), "readiness barrier reports the missing contestant");
		assertEquals(
				ScenarioRosterReadinessBarrier.Status.READY,
				barrier.assess(239L, List.of("agent-c", "agent-b", "agent-a")).status(),
				"full fake-player roster becomes ready before timeout"
		);
		ScenarioRosterReadinessBarrier.Assessment timedOut = barrier.assess(240L, List.of("agent-a"));
		assertEquals(
				ScenarioRosterReadinessBarrier.Status.TIMED_OUT,
				timedOut.status(),
				"incomplete fake-player roster times out at the bounded deadline"
		);
		assertEquals(
				List.of("agent-b", "agent-c"),
				timedOut.missingIds(),
				"timed-out readiness barrier preserves deterministic missing order"
		);
		return 5;
	}

	private static int verifyRosterActivationOrder() {
		ArrayList<String> events = new ArrayList<>();
		ScenarioRosterActivator activator = new ScenarioRosterActivator();
		activator.protect(
				List.of("agent-a", "agent-b", "agent-c"),
				agent -> events.add("protect-" + agent)
		);
		activator.activate(
				List.of("agent-a", "agent-b", "agent-c"),
				agent -> events.add("release-" + agent),
				agent -> events.add("load-" + agent),
				agent -> events.add("start-" + agent)
		);
		assertEquals(
				List.of(
						"protect-agent-a",
						"protect-agent-b",
						"protect-agent-c",
						"release-agent-a",
						"release-agent-b",
						"release-agent-c",
						"load-agent-a",
						"load-agent-b",
						"load-agent-c",
						"start-agent-a",
						"start-agent-b",
						"start-agent-c"
				),
				events,
				"every contestant loadout completes before any goal starts"
		);
		return 1;
	}

	private static int verifyPlacementBatchPolicy() {
		ScenarioPlacementBatchPolicy policy = new ScenarioPlacementBatchPolicy(3, 4L);
		assertEquals(
				new ScenarioPlacementBatchPolicy.Window(2, 5),
				policy.window(2, 10),
				"placement batch caps inspections while work remains"
		);
		assertEquals(
				new ScenarioPlacementBatchPolicy.Window(9, 10),
				policy.window(9, 10),
				"placement batch preserves final partial progress"
		);
		assertEquals(
				new ScenarioPlacementBatchPolicy.Window(Integer.MAX_VALUE - 1, Integer.MAX_VALUE),
				policy.window(Integer.MAX_VALUE - 1, Integer.MAX_VALUE),
				"placement batch preserves progress without integer overflow"
		);
		assertTrue(policy.shouldContinue(0, 9L), "placement batch always permits one progress step");
		assertTrue(policy.shouldContinue(1, 3L), "placement batch continues inside its time budget");
		assertTrue(!policy.shouldContinue(1, 4L), "placement batch yields at its time budget");
		return 6;
	}

	private static int verifyControllerReachableParkourCourse() {
		ScenarioParkourCourse course = ScenarioParkourCourse.create();
		assertEquals(16, course.lanes().size(), "parkour course exposes sixteen independent lanes");
		assertEquals(
				new ScenarioParkourCourse.Platform(
						-61, 1, -40, ScenarioParkourCourse.Stage.EASY, true, 3),
				course.lanes().getFirst().platforms().getFirst(),
				"first parkour lane starts at the authored west edge"
		);
		assertEquals(
				new ScenarioParkourCourse.Platform(
						59, 14, 10, ScenarioParkourCourse.Stage.EXTREME, true, 3),
				course.lanes().getLast().platforms().getLast(),
				"last parkour lane reaches the common finish row"
		);
		assertTrue(
				course.lanes().stream().allMatch(ScenarioParkourCourse.Lane::transitionsReachable),
				"every parkour transition stays inside the controller reach envelope"
		);
		for (ScenarioParkourCourse.Lane lane : course.lanes()) {
			String traversalFailure = controllerTraversalFailure(lane);
			assertTrue(traversalFailure == null,
					"the real navigation pathfinder can traverse authored parkour lane " + lane.index()
							+ (traversalFailure == null ? "" : ": " + traversalFailure));
		}
		assertTrue(
				course.lanes().stream()
						.flatMap(lane -> lane.platforms().stream())
						.allMatch(platform -> platform.x() >= -62 && platform.x() + platform.width() - 1 <= 63
								&& platform.y() >= 1 && platform.y() <= 18
								&& platform.z() >= -40 && platform.z() <= 19),
				"parkour course stays inside the authored arena bounds"
		);
		for (int index = 1; index < course.lanes().size(); index++) {
			ScenarioParkourCourse.Platform previous = course.lanes().get(index - 1).platforms().getFirst();
			ScenarioParkourCourse.Platform current = course.lanes().get(index).platforms().getFirst();
			assertTrue(current.x() - (previous.x() + previous.width() - 1) >= 6,
					"adjacent parkour lanes remain beyond a normal cross-lane jump");
		}

		int assertions = 36;
		for (int count = 1; count <= 16; count++) {
			List<ScenarioSpawn> allocated = new ScenarioSpawnAllocator().allocate(
					config(ScenarioPresets.require("thinking-tower"), 8_000L + count, 11L, participants(count))
			);
			assertEquals(
					count,
					new HashSet<>(allocated.stream().map(spawn -> spawn.x() + ":" + spawn.z()).toList()).size(),
					"parkour lane starts are unique " + count
			);
			assertTrue(
					allocated.stream().allMatch(spawn -> spawn.x() >= -60.0D && spawn.x() <= 60.0D
							&& spawn.z() == -40.0D),
					"parkour contestants spawn on authored lane starts " + count
			);
			assertions += 2;
		}
		return assertions;
	}

	private static String controllerTraversalFailure(ScenarioParkourCourse.Lane lane) {
		Set<GridPosition> supports = new HashSet<>();
		Set<GridPosition> arena = new HashSet<>();
		for (ScenarioParkourCourse.Platform platform : lane.platforms()) {
			int zRadius = platform.checkpoint() ? 1 : 0;
			for (int x = platform.x(); x < platform.x() + platform.width(); x++) {
				for (int z = platform.z() - zRadius; z <= platform.z() + zRadius; z++) {
					supports.add(new GridPosition(x, platform.y(), z));
				}
			}
		}
		for (int x = lane.platforms().stream().mapToInt(ScenarioParkourCourse.Platform::x).min().orElseThrow() - 3;
				x <= lane.platforms().stream().mapToInt(p -> p.x() + p.width()).max().orElseThrow() + 3; x++) {
			for (int y = 0; y <= 20; y++) {
				for (int z = ScenarioParkourCourse.START_Z - 3; z <= 24; z++) arena.add(new GridPosition(x, y, z));
			}
		}
		WalkabilityView view = position -> !arena.contains(position) ? WalkabilityView.Cell.UNLOADED
				: supports.contains(position) ? WalkabilityView.Cell.SAFE_SUPPORT
				: position.y() == 0 ? WalkabilityView.Cell.HAZARD : WalkabilityView.Cell.CLEAR;
		LocalPathfinder pathfinder = new LocalPathfinder();
		ScenarioParkourCourse.Platform laneStart = lane.platforms().getFirst();
		ScenarioParkourCourse.Platform laneFinish = lane.platforms().getLast();
		var completePlan = pathfinder.findPath(
				view,
				new GridPosition((int) Math.floor(laneStart.centerX()), laneStart.y() + 1, laneStart.z()),
				new GridPosition((int) Math.floor(laneFinish.centerX()), laneFinish.y() + 1, laneFinish.z()),
				LocalPathfinder.MAX_EXPANDED_NODES,
				Long.MAX_VALUE,
				() -> 0L
		);
		if (completePlan.outcome() != PathOutcome.FOUND) {
			return "complete route returned " + completePlan.outcome() + " after "
					+ completePlan.expandedNodes() + " nodes";
		}
		for (int index = 1; index < lane.platforms().size(); index++) {
			ScenarioParkourCourse.Platform start = lane.platforms().get(index - 1);
			ScenarioParkourCourse.Platform finish = lane.platforms().get(index);
			var plan = pathfinder.findPath(
					view,
					new GridPosition((int) Math.floor(start.centerX()), start.y() + 1, start.z()),
					new GridPosition((int) Math.floor(finish.centerX()), finish.y() + 1, finish.z()),
					LocalPathfinder.MAX_EXPANDED_NODES,
					Long.MAX_VALUE,
					() -> 0L
			);
			if (plan.outcome() != PathOutcome.FOUND) {
				GridPosition startFeet = new GridPosition(
						(int) Math.floor(start.centerX()), start.y() + 1, start.z());
				GridPosition finishFeet = new GridPosition(
						(int) Math.floor(finish.centerX()), finish.y() + 1, finish.z());
				return "transition " + (index - 1) + " -> " + index + " returned " + plan.outcome()
						+ " after " + plan.expandedNodes() + " nodes; start=" + startFeet + "/"
						+ view.cellAt(startFeet) + "/" + view.cellAt(startFeet.below()) + ", finish="
						+ finishFeet + "/" + view.cellAt(finishFeet) + "/" + view.cellAt(finishFeet.below());
			}
		}
		return null;
	}

	private static int verifyParkourCheckpointsAndRecovery() {
		ScenarioParkourCourse.Lane lane = ScenarioParkourCourse.create(1).lanes().getFirst();
		assertEquals(
				List.of(
						ScenarioParkourCourse.Stage.EASY,
						ScenarioParkourCourse.Stage.MEDIUM,
						ScenarioParkourCourse.Stage.HARD,
						ScenarioParkourCourse.Stage.EXTREME
				),
				lane.platforms().stream().map(ScenarioParkourCourse.Platform::stage).distinct().toList(),
				"parkour lane progresses through four ordered difficulty stages"
		);
		assertEquals(List.of(0, 8, 16, 24, 32), lane.checkpointIndices(),
				"parkour checkpoints mark the start and every difficulty boundary");

		List<String> stageSignatures = ScenarioParkourCourse.Stage.values().length == 4
				? java.util.Arrays.stream(ScenarioParkourCourse.Stage.values())
						.map(stage -> lane.transitionSignature(stage)).toList()
				: List.of();
		assertEquals(4, new HashSet<>(stageSignatures).size(),
				"each parkour difficulty uses a distinct movement grammar");
		assertTrue(lane.platforms().stream().anyMatch(platform -> platform.width() == 1),
				"later stages include precision one-block landings");
		assertTrue(lane.platforms().stream().map(ScenarioParkourCourse.Platform::width).distinct().count() == 3,
				"the route varies landing widths instead of repeating one staircase shape");
		assertTrue(lane.platforms().stream().map(ScenarioParkourCourse.Platform::y).distinct().count() >= 6,
				"the route has meaningful vertical composition");
		assertTrue(lane.platforms().stream().map(ScenarioParkourCourse.Platform::x).distinct().count() >= 2,
				"the route requires lateral movement instead of following one straight axis");
		assertTrue(lane.transitionsReachable(),
				"every progressive parkour transition stays inside the controller reach envelope");

		ScenarioParkourRecovery progress = new ScenarioParkourRecovery(lane);
		ScenarioParkourCourse.Platform checkpoint20 = lane.platforms().get(16);
		ScenarioParkourRecovery.Decision advanced = progress.evaluate(
				0, checkpoint20.x() + 0.5D, checkpoint20.y() + 1.0D, checkpoint20.z() + 0.5D);
		assertEquals(16, advanced.checkpointIndex(), "standing on a later checkpoint advances progress");
		assertTrue(!advanced.recover(), "standing on a checkpoint does not trigger recovery");
		ScenarioParkourCourse.Platform checkpoint10 = lane.platforms().get(8);
		ScenarioParkourRecovery.Decision backwards = progress.evaluate(
				16, checkpoint10.x() + 0.5D, checkpoint10.y() + 1.0D, checkpoint10.z() + 0.5D);
		assertEquals(16, backwards.checkpointIndex(), "checkpoint progress never moves backwards");
		ScenarioParkourRecovery.Decision fallen = progress.evaluate(16, 0.0D, 1.0D, 0.0D);
		assertTrue(!fallen.recover(), "touching lava does not teleport a still-living contestant");
		assertTrue(!progress.evaluate(16, 0.0D, 1.8D, 0.0D).recover(),
				"falling contestants are allowed to die in lava before checkpoint respawn");
		assertEquals(
				new ScenarioParkourRecovery.Target(
						checkpoint20.centerX(), checkpoint20.y() + 1.0D, checkpoint20.z() + 0.5D),
				fallen.target(), "recovery returns to the center of the latest checkpoint"
		);
		ScenarioParkourRecovery.Decision crossedLane = progress.evaluate(
				16, checkpoint20.centerX() + ScenarioParkourCourse.LANE_PITCH,
				checkpoint20.standingY(), checkpoint20.centerZ());
		assertTrue(!crossedLane.recover(), "crossing a lane never causes an invisible forced teleport");
		assertEquals(fallen.target(), crossedLane.target(),
				"lane crossing returns to the participant's own checkpoint");

		BlockPos origin = new BlockPos(0, 70, 0);
		var blueprint = dev.agaminggod.arenaagents.scenario.runtime.ScenarioArenaBlueprint.create(
				ScenarioPresets.require("thinking-tower"), origin, 2);
		Map<Long, dev.agaminggod.arenaagents.scenario.runtime.ScenarioArenaBlueprint.Placement> byPosition =
				dev.agaminggod.arenaagents.scenario.runtime.ScenarioArenaResetJob.canonicalize(blueprint.placements())
						.stream().collect(java.util.stream.Collectors.toMap(
								placement -> placement.position().asLong(), placement -> placement));
		BlockPos beacon = byPosition.values().stream()
				.filter(placement -> placement.state().getBlock() == Blocks.BEACON)
				.map(dev.agaminggod.arenaagents.scenario.runtime.ScenarioArenaBlueprint.Placement::position)
				.findFirst().orElseThrow();
		for (int x = -1; x <= 1; x++) {
			for (int z = -1; z <= 1; z++) {
				assertEquals(Blocks.EMERALD_BLOCK, byPosition.get(beacon.offset(x, -1, z).asLong()).state().getBlock(),
						"finish beacon uses a 3 by 3 mineral base");
			}
		}
		assertTrue(!byPosition.containsKey(beacon.offset(-2, -1, 0).asLong())
				|| byPosition.get(beacon.offset(-2, -1, 0).asLong()).state().getBlock() != Blocks.EMERALD_BLOCK,
				"finish beacon base does not extend to five blocks");
		assertEquals(Blocks.LAVA, byPosition.get(origin.offset(0, 0, 0).asLong()).state().getBlock(),
				"parkour failure floor is lava rather than a decorative solid floor");
		assertTrue(byPosition.values().stream().filter(placement -> placement.state().getBlock() == Blocks.LAVA).count()
				>= 100, "the visible parkour floor is substantially lava-backed");
		for (int x = -10; x <= 10; x++) {
			assertEquals(Blocks.POLISHED_BLACKSTONE_BRICKS,
					byPosition.get(origin.offset(x, 0, -43).asLong()).state().getBlock(),
					"parkour lava has a sealed north curb at fluid level");
			assertEquals(Blocks.POLISHED_BLACKSTONE_BRICKS,
					byPosition.get(origin.offset(x, 0, 31).asLong()).state().getBlock(),
					"parkour lava has a sealed south curb at fluid level");
		}
		assertTrue(byPosition.values().stream().noneMatch(placement ->
				placement.state().getBlock() == Blocks.BIRCH_PLANKS),
				"parkour platforms contain no flammable wooden surfaces");
		for (ScenarioParkourCourse.Lane authoredLane : ScenarioParkourCourse.create(2).lanes()) {
			ScenarioParkourCourse.Platform start = authoredLane.platforms().getFirst();
			ScenarioParkourCourse.Platform next = authoredLane.platforms().get(1);
			assertEquals(start.z() + 3, next.z(),
					"first jump clears the spawning checkpoint with one navigable gap");
		}
		var pvpBlueprint = dev.agaminggod.arenaagents.scenario.runtime.ScenarioArenaBlueprint.create(
				ScenarioPresets.require("citadel-collapse"), origin, 16);
		Map<Long, dev.agaminggod.arenaagents.scenario.runtime.ScenarioArenaBlueprint.Placement> pvpByPosition =
				dev.agaminggod.arenaagents.scenario.runtime.ScenarioArenaResetJob.canonicalize(pvpBlueprint.placements())
						.stream().collect(java.util.stream.Collectors.toMap(
								placement -> placement.position().asLong(), placement -> placement));
		long lootContainers = pvpByPosition.values().stream().filter(placement ->
				placement.state().getBlock() == Blocks.CHEST || placement.state().getBlock() == Blocks.BARREL).count();
		assertTrue(lootContainers >= 30, "survival-games arena exposes abundant center and outer loot locations");
		assertTrue(pvpBlueprint.siteBounds().maximumX() - pvpBlueprint.siteBounds().minimumX() >= 160,
				"sixteen-player survival-games arena scales beyond the old small combat box");
		assertTrue(pvpByPosition.values().stream().anyMatch(placement -> placement.state().getBlock() == Blocks.WATER),
				"survival-games arena contains navigable terrain rather than only stone and lava");
		return 34;
	}

	private static int verifyParkourParticipantRuntime() {
		ScenarioParkourCourse course = ScenarioParkourCourse.create(2);
		ScenarioParkourRunState state = new ScenarioParkourRunState(
				course, Map.of("agent-kimi", 1, "agent-sol", 0));
		ScenarioParkourCourse.Platform kimiCheckpoint = course.lanes().get(1).platforms().get(16);
		ScenarioParkourRecovery.Decision kimi = state.evaluate(
				"agent-kimi", kimiCheckpoint.centerX(), kimiCheckpoint.standingY(), kimiCheckpoint.centerZ());
		assertEquals(16, kimi.checkpointIndex(), "runtime advances the checkpoint for the allocated lane");
		assertEquals(0, state.checkpointIndex("agent-sol"),
				"one participant's checkpoint never advances another participant");
		ScenarioParkourRecovery.Decision recovered = state.evaluate("agent-kimi", 0.0D, 1.0D, 0.0D);
		assertTrue(!recovered.recover(), "runtime waits for genuine death before respawn");
		assertEquals(kimi.target(), recovered.target(), "runtime remembers the latest checkpoint target");
		ScenarioParkourRecovery.Decision wrongLane = state.evaluate(
				"agent-kimi",
				course.lanes().getFirst().platforms().get(16).centerX(),
				kimiCheckpoint.standingY(), kimiCheckpoint.centerZ());
		assertTrue(!wrongLane.recover(), "runtime does not secretly teleport a live wrong-lane participant");
		assertEquals(kimi.target(), wrongLane.target(), "wrong-lane recovery preserves independent progress");
		assertEquals(Map.of("agent-kimi", 16, "agent-sol", 0), state.checkpoints(),
				"runtime exposes an immutable checkpoint snapshot keyed by agent");
		ScenarioParkourRunState restored = new ScenarioParkourRunState(
				course, Map.of("agent-kimi", 1, "agent-sol", 0), state.checkpoints());
		assertEquals(16, restored.checkpointIndex("agent-kimi"),
				"runtime restores each participant's persisted checkpoint");
		assertEquals(kimi.target(), restored.evaluate("agent-kimi", 0.0D, 1.0D, 0.0D).target(),
				"restored checkpoint selects the same recovery target");
		assertEquals(AgentGameMode.ADVENTURE,
				ScenarioParticipantPolicy.effectiveGameMode(ScenarioCategory.PARKOUR, AgentGameMode.SURVIVAL),
				"parkour forces Adventure mode even when an agent requested Survival");
		assertEquals(AgentGameMode.CREATIVE,
				ScenarioParticipantPolicy.effectiveGameMode(ScenarioCategory.BUILDING, AgentGameMode.CREATIVE),
				"non-parkour scenarios preserve the configured game mode");
		return 11;
	}

	private static int verifyStandardizedLoadouts() {
		ScenarioLoadoutPlan survival = ScenarioLoadoutPlan.forContestant(
				ScenarioCategory.SURVIVAL,
				AgentGameMode.SURVIVAL
		);
		assertTrue(survival.hasItem("minecraft:iron_pickaxe"), "survival loadout includes a pickaxe");
		assertTrue(survival.hasItem("minecraft:iron_axe"), "survival loadout includes an axe");
		assertTrue(survival.hasItem("minecraft:bread"), "survival loadout includes food");
		assertTrue(survival.hasItem("minecraft:torch"), "survival loadout includes torches");

		ScenarioLoadoutPlan building = ScenarioLoadoutPlan.forContestant(
				ScenarioCategory.BUILDING,
				AgentGameMode.SURVIVAL
		);
		assertTrue(building.hasItem("minecraft:stone_bricks"), "building palette includes structure blocks");
		assertTrue(building.hasItem("minecraft:glass"), "building palette includes glass");
		assertTrue(building.hasItem("minecraft:sea_lantern"), "building palette includes lighting");
		assertEquals(
				List.of(),
				ScenarioLoadoutPlan.forContestant(ScenarioCategory.BUILDING, AgentGameMode.CREATIVE).entries(),
				"creative builders do not receive a redundant palette"
		);

		ScenarioLoadoutPlan pvp = ScenarioLoadoutPlan.forContestant(ScenarioCategory.PVP, AgentGameMode.SURVIVAL);
		assertEquals(List.of(), pvp.entries(), "PvP contestants start completely empty and earn every item");

		ScenarioLoadoutPlan parkour = ScenarioLoadoutPlan.forContestant(
				ScenarioCategory.PARKOUR,
				AgentGameMode.ADVENTURE
		);
		assertTrue(parkour.hasItem("minecraft:cooked_beef"), "parkour loadout includes food");
		assertTrue(
				parkour.entries().stream().anyMatch(entry -> entry.armorSlot() == ScenarioLoadoutPlan.ArmorSlot.FEET),
				"parkour loadout equips safe footwear"
		);
		assertEquals(
				survival.entries().size(),
				new HashSet<>(survival.entries().stream().map(ScenarioLoadoutPlan.Entry::inventorySlot).toList()).size(),
				"survival loadout uses unique inventory slots"
		);
		return 12;
	}

	private static int verifyPvpLootManifests() {
		BlockPos origin = new BlockPos(100, 70, -40);
		long seed = 917_221L;
		ScenarioLootManifest house = ScenarioLootManifest.forContainer(
				origin, origin.offset(-27, 2, -18), seed);
		ScenarioLootManifest dungeon = ScenarioLootManifest.forContainer(
				origin, origin.offset(-42, -5, -35), seed);
		ScenarioLootManifest center = ScenarioLootManifest.forContainer(
				origin, origin.offset(9, 2, 0), seed);
		assertEquals(house, ScenarioLootManifest.forContainer(origin, origin.offset(-27, 2, -18), seed),
				"the same world seed and container position produce identical loot");
		assertEquals(ScenarioLootManifest.Tier.HOUSE, house.tier(), "house containers use the house tier");
		assertEquals(ScenarioLootManifest.Tier.DUNGEON, dungeon.tier(), "underground containers use the dungeon tier");
		assertEquals(ScenarioLootManifest.Tier.CENTER, center.tier(), "cornucopia containers use the center tier");
		assertTrue(!house.entries().isEmpty() && !dungeon.entries().isEmpty() && !center.entries().isEmpty(),
				"every authored loot tier is nonempty");
		assertTrue(house.entries().stream().anyMatch(entry -> Set.of(
				"minecraft:bread", "minecraft:cooked_beef").contains(entry.itemId())),
				"house loot always contains food");
		assertTrue(house.entries().stream().anyMatch(entry -> Set.of(
				"minecraft:stone_sword", "minecraft:stone_axe", "minecraft:shield").contains(entry.itemId())),
				"house loot always contains basic equipment");
		assertTrue(dungeon.entries().stream().anyMatch(entry -> Set.of(
				"minecraft:iron_sword", "minecraft:iron_axe", "minecraft:bow", "minecraft:crossbow").contains(entry.itemId())),
				"dungeon loot always contains an iron or ranged item");
		assertTrue(center.entries().stream().anyMatch(entry -> Set.of(
				"minecraft:iron_chestplate", "minecraft:golden_apple", "minecraft:ender_pearl", "minecraft:diamond_sword").contains(entry.itemId())),
				"center loot always contains a high-tier item");
		assertEquals((long) house.entries().size(), house.entries().stream().map(ScenarioLootManifest.Entry::slot).distinct().count(),
				"manifest slots are unique");
		var blueprint = dev.agaminggod.arenaagents.scenario.runtime.ScenarioArenaBlueprint.create(
				ScenarioPresets.require("citadel-collapse"), origin, 16);
		List<dev.agaminggod.arenaagents.scenario.runtime.ScenarioArenaBlueprint.Placement> containers =
				dev.agaminggod.arenaagents.scenario.runtime.ScenarioArenaResetJob.canonicalize(blueprint.placements())
						.stream().filter(placement -> placement.state().is(Blocks.CHEST)
								|| placement.state().is(Blocks.BARREL)).toList();
		assertEquals(33, containers.size(), "the PvP blueprint authors exactly 33 loot containers");
		Map<ScenarioLootManifest.Tier, Long> tierCounts = containers.stream()
				.map(placement -> ScenarioLootManifest.forContainer(origin, placement.position(), seed))
				.peek(manifest -> assertTrue(!manifest.entries().isEmpty(), "every authored container has useful loot"))
				.collect(java.util.stream.Collectors.groupingBy(
						ScenarioLootManifest::tier, java.util.stream.Collectors.counting()));
		assertEquals(Map.of(
				ScenarioLootManifest.Tier.CENTER, 17L,
				ScenarioLootManifest.Tier.DUNGEON, 6L,
				ScenarioLootManifest.Tier.HOUSE, 10L), tierCounts,
				"all center, dungeon, house, and tower containers receive the intended loot tier");
		return 12;
	}

	private static int verifyLoadoutApplication() {
		RecordingLoadoutTarget target = new RecordingLoadoutTarget();
		new ScenarioLoadoutService().apply(
				ScenarioLoadoutPlan.forContestant(ScenarioCategory.PVP, AgentGameMode.SURVIVAL),
				target
		);
		assertEquals(1, target.resetCount, "loadout application resets stale inventory");
		assertEquals(
				List.of(),
				target.inventory,
				"PvP activation clears stale inventory without granting starter items"
		);
		assertEquals(
				List.of(),
				target.armor,
				"PvP armor entries are equipped rather than left in inventory"
		);
		RecordingLoadoutTarget orderedTarget = new RecordingLoadoutTarget();
		new ScenarioLoadoutService().applyThenStart(
				ScenarioLoadoutPlan.forContestant(ScenarioCategory.SURVIVAL, AgentGameMode.SURVIVAL),
				orderedTarget,
				() -> orderedTarget.events.add("start")
		);
		assertEquals("start", orderedTarget.events.getLast(), "contestant starts only after its loadout is applied");
		return 4;
	}

	private static final class RecordingLoadoutTarget implements ScenarioLoadoutService.Target {
		private int resetCount;
		private final ArrayList<String> inventory = new ArrayList<>();
		private final ArrayList<String> armor = new ArrayList<>();
		private final ArrayList<String> events = new ArrayList<>();

		@Override
		public void reset() {
			resetCount++;
			inventory.clear();
			armor.clear();
			events.add("reset");
		}

		@Override
		public void putInventory(int slot, String itemId, int count) {
			inventory.add(slot + "=" + itemId + "x" + count);
			events.add("inventory");
		}

		@Override
		public void equip(ScenarioLoadoutPlan.ArmorSlot slot, String itemId, int count) {
			armor.add(slot + "=" + itemId + "x" + count);
			events.add("armor");
		}
	}

	private static int verifyBuiltInPresets() {
		List<ScenarioPreset> presets = ScenarioPresets.all();
		assertEquals(4, presets.size(), "built-in preset count");
		assertEquals(
				List.of(ScenarioCategory.SURVIVAL, ScenarioCategory.BUILDING, ScenarioCategory.PVP, ScenarioCategory.PARKOUR),
				presets.stream().map(ScenarioPreset::category).toList(),
				"preset category order"
		);
		assertEquals("The Last Valley", ScenarioPresets.require("last-valley").title(), "survival title");
		assertEquals("The Impossible Brief", ScenarioPresets.require("impossible-brief").title(), "building title");
		assertEquals("Citadel Collapse", ScenarioPresets.require("citadel-collapse").title(), "PvP title");
		assertEquals("The Thinking Tower", ScenarioPresets.require("thinking-tower").title(), "parkour title");
		assertEquals(1, ScenarioPresets.require("last-valley").minimumAgents(), "survival minimum");
		assertEquals(2, ScenarioPresets.require("citadel-collapse").minimumAgents(), "PvP minimum");
		assertEquals(16, ScenarioPresets.require("thinking-tower").maximumAgents(), "parkour maximum");
		for (ScenarioPreset preset : presets) {
			long scheduledTicks = preset.phases().stream().mapToLong(ScenarioPhase::durationTicks).sum();
			double totalWeight = preset.scoreRules().stream().mapToDouble(ScenarioScoreRule::weight).sum();
			assertEquals(preset.defaultDurationTicks(), scheduledTicks, preset.id() + " phase duration");
			assertDoubleEquals(100.0D, totalWeight, preset.id() + " score weight");
			assertTrue(!preset.landmarks().isEmpty(), preset.id() + " landmarks");
			assertTrue(!preset.presentationTags().isEmpty(), preset.id() + " presentation tags");
		}
		expectUnsupported(() -> presets.add(ScenarioPresets.require("last-valley")), "preset registry is immutable");
		expectUnsupported(
				() -> ScenarioPresets.require("last-valley").landmarks().add("unfair shortcut"),
				"preset metadata is immutable"
		);
		return 22;
	}

	private static int verifySessionConfigValidation() {
		ScenarioSessionConfig valid = config(
				ScenarioPresets.require("last-valley"),
				99L,
				123L,
				participants(2)
		);
		assertEquals(2, valid.participants().size(), "valid participant count");
		assertTrue(valid.deterministicEvents(), "deterministic events");
		expectFailure(
				() -> config(ScenarioPresets.require("citadel-collapse"), 1L, 2L, participants(1)),
				"AGENT_COUNT_OUT_OF_RANGE"
		);
		expectFailure(
				() -> new ScenarioSessionConfig(
						SESSION_ID,
						ScenarioPresets.require("last-valley"),
						1L,
						2L,
						ScenarioPresets.require("last-valley").defaultDurationTicks(),
						true,
						List.of(
								new ScenarioParticipant("agent-a", "First", Optional.empty()),
								new ScenarioParticipant("AGENT-A", "Duplicate", Optional.empty())
						),
						CREATED_AT_EPOCH_MS
				),
				"DUPLICATE_PARTICIPANT_ID"
		);
		expectFailure(
				() -> new ScenarioSessionConfig(
						SESSION_ID,
						ScenarioPresets.require("last-valley"),
						1L,
						2L,
						0L,
						true,
						participants(1),
						CREATED_AT_EPOCH_MS
				),
				"INVALID_DURATION"
		);
		return 5;
	}

	private static int verifyDeterministicSpawnAllocation() {
		ScenarioSpawnAllocator allocator = new ScenarioSpawnAllocator();
		ScenarioSessionConfig first = config(ScenarioPresets.require("last-valley"), 91L, 7L, participants(8));
		ScenarioSessionConfig same = config(ScenarioPresets.require("last-valley"), 91L, 7L, participants(8));
		ScenarioSessionConfig changed = config(ScenarioPresets.require("last-valley"), 92L, 7L, participants(8));
		List<ScenarioSpawn> firstAllocation = allocator.allocate(first);
		assertEquals(firstAllocation, allocator.allocate(same), "same seed allocation");
		assertTrue(!firstAllocation.equals(allocator.allocate(changed)), "different seed allocation");
		assertEquals(8, new HashSet<>(firstAllocation.stream().map(ScenarioSpawn::slotIndex).toList()).size(), "unique slots");
		assertEquals(
				List.of("agent-0", "agent-1", "agent-2", "agent-3", "agent-4", "agent-5", "agent-6", "agent-7"),
				firstAllocation.stream().map(ScenarioSpawn::participantId).sorted().toList(),
				"all participants allocated"
		);
		ScenarioSessionConfig twelve = config(ScenarioPresets.require("thinking-tower"), 193L, 7L, participants(12));
		assertEquals(
				twelve.participants().stream().map(ScenarioParticipant::id).toList(),
				allocator.allocate(twelve).stream().map(ScenarioSpawn::participantId).toList(),
				"double-digit rosters preserve the durable participant binding order"
		);
		for (int count = 1; count <= 16; count++) {
			List<ScenarioSpawn> allocated = allocator.allocate(
					config(ScenarioPresets.require("thinking-tower"), 1_000L + count, 8L, participants(count))
			);
			assertEquals(count, allocated.size(), "allocation size " + count);
			assertEquals(count, new HashSet<>(allocated.stream().map(ScenarioSpawn::slotIndex).toList()).size(), "slot count " + count);
			assertTrue(
					allocated.stream().allMatch(spawn -> spawn.lane().startsWith("lane-")),
					"parkour lanes " + count
			);
		}
		return 53;
	}

	private static int verifyAdaptiveSpawnLayouts() {
		net.minecraft.SharedConstants.tryDetectVersion();
		net.minecraft.server.Bootstrap.bootStrap();
		int assertions = 0;
		for (ScenarioCategory category : ScenarioCategory.values()) {
			int minimum = category == ScenarioCategory.PVP ? 2 : 1;
			for (int count = minimum; count <= 16; count++) {
				List<ScenarioSpawnLayout.Slot> slots = ScenarioSpawnLayout.slots(category, count);
				assertEquals(count, slots.size(), category + " authors only the requested stations " + count);
				assertEquals(count, new HashSet<>(slots.stream()
						.map(slot -> slot.x() + ":" + slot.z()).toList()).size(),
						category + " stations remain unique " + count);
				assertions += 2;
			}
		}

		List<ScenarioSpawnLayout.Slot> pair = ScenarioSpawnLayout.slots(ScenarioCategory.PARKOUR, 2);
		assertEquals(0, pair.getFirst().x() + pair.getLast().x(),
				"two parkour lanes are centered around the arena axis");
		assertEquals(pair.getFirst().z(), pair.getLast().z(),
				"two parkour starts share one aligned start line");

		BlockPos origin = new BlockPos(100, 70, -40);
		ScenarioPreset parkour = ScenarioPresets.require("thinking-tower");
		var blueprint = dev.agaminggod.arenaagents.scenario.runtime.ScenarioArenaBlueprint.create(
				parkour, origin, 2);
		Map<Long, dev.agaminggod.arenaagents.scenario.runtime.ScenarioArenaBlueprint.Placement> byPosition =
				dev.agaminggod.arenaagents.scenario.runtime.ScenarioArenaResetJob.canonicalize(blueprint.placements())
						.stream().collect(java.util.stream.Collectors.toMap(
								placement -> placement.position().asLong(), placement -> placement));
		for (ScenarioSpawnLayout.Slot slot : pair) {
			BlockPos marker = origin.offset(slot.x(), slot.floorY(), slot.z());
			assertEquals(Blocks.SEA_LANTERN, byPosition.get(marker.asLong()).state().getBlock(),
					"active parkour lane has an aligned start marker " + slot.index());
			assertions++;
		}
		Set<String> activeCoordinates = pair.stream().map(slot -> slot.x() + ":" + slot.z())
				.collect(java.util.stream.Collectors.toSet());
		for (ScenarioSpawnLayout.Slot inactive : ScenarioSpawnLayout.slots(ScenarioCategory.PARKOUR, 16)) {
			if (activeCoordinates.contains(inactive.x() + ":" + inactive.z())) continue;
			BlockPos marker = origin.offset(inactive.x(), inactive.floorY(), inactive.z());
			var placement = byPosition.get(marker.asLong());
			assertTrue(placement == null || placement.state().getBlock() != Blocks.SEA_LANTERN,
					"inactive parkour lane is not authored " + inactive.index());
			assertions++;
		}
		return assertions + 2;
	}

	private static int verifyAuthoredSpawnStations() {
		net.minecraft.SharedConstants.tryDetectVersion();
		net.minecraft.server.Bootstrap.bootStrap();
		BlockPos origin = new BlockPos(100, 70, -40);
		int assertions = 0;
		for (ScenarioPreset preset : ScenarioPresets.all()) {
			List<ScenarioSpawnLayout.Slot> slots = ScenarioSpawnLayout.slots(preset.category());
			assertEquals(16, slots.size(), preset.id() + " exposes sixteen authored spawn stations");
			assertEquals(16, new HashSet<>(slots.stream().map(slot -> slot.x() + ":" + slot.z()).toList()).size(),
					preset.id() + " spawn stations are unique");
			var blueprint = dev.agaminggod.arenaagents.scenario.runtime.ScenarioArenaBlueprint.create(preset, origin);
			Map<Long, dev.agaminggod.arenaagents.scenario.runtime.ScenarioArenaBlueprint.Placement> byPosition =
					dev.agaminggod.arenaagents.scenario.runtime.ScenarioArenaResetJob.canonicalize(blueprint.placements())
							.stream().collect(java.util.stream.Collectors.toMap(
									placement -> placement.position().asLong(), placement -> placement));
			Set<net.minecraft.world.level.block.Block> palette = byPosition.values().stream()
					.map(placement -> placement.state().getBlock())
					.filter(block -> block != Blocks.AIR)
					.collect(java.util.stream.Collectors.toSet());
			assertTrue(palette.size() >= 10, preset.id() + " uses a varied, textured material palette");
			List<net.minecraft.world.level.block.Block> signatureColors = switch (preset.category()) {
				case SURVIVAL -> List.of(Blocks.MOSS_BLOCK, Blocks.PODZOL, Blocks.WATER, Blocks.OAK_PLANKS);
				case BUILDING -> List.of(Blocks.CALCITE, Blocks.TUFF_BRICKS, Blocks.CUT_COPPER, Blocks.GOLD_BLOCK);
				case PVP -> List.of(Blocks.CRACKED_STONE_BRICKS, Blocks.MOSSY_STONE_BRICKS,
						Blocks.POLISHED_BASALT, Blocks.RED_CONCRETE, Blocks.BLUE_CONCRETE);
				case PARKOUR -> List.of(Blocks.LAVA, Blocks.SMOOTH_QUARTZ,
						Blocks.PRISMARINE_BRICKS, Blocks.PURPUR_BLOCK, Blocks.RED_NETHER_BRICKS,
						Blocks.GOLD_BLOCK, Blocks.EMERALD_BLOCK);
			};
			assertTrue(palette.containsAll(signatureColors), preset.id() + " retains its authored color identity");
			for (ScenarioSpawnLayout.Slot slot : slots) {
				BlockPos station = origin.offset(slot.x(), slot.floorY(), slot.z());
				assertEquals(Blocks.SEA_LANTERN, byPosition.get(station.asLong()).state().getBlock(),
						preset.id() + " spawn " + slot.index() + " has a visible center marker");
			}
			BlockPos operatorSpawn = blueprint.operatorSpawn();
			assertEquals(Blocks.SEA_LANTERN, byPosition.get(operatorSpawn.below().asLong()).state().getBlock(),
					preset.id() + " operator spawn stands on the observation deck marker");
			assertTrue(!byPosition.containsKey(operatorSpawn.asLong()),
					preset.id() + " operator feet space is provided by whole-site clearing");
			assertions += 22;
		}
		return assertions;
	}

	private static int verifyBuildingPlotSpawnAlignment() {
		ScenarioSpawnAllocator allocator = new ScenarioSpawnAllocator();
		int assertions = 0;
		for (int count = 1; count <= 16; count++) {
			List<ScenarioSpawn> allocated = allocator.allocate(
					config(ScenarioPresets.require("impossible-brief"), 4_000L + count, 9L, participants(count))
			);
			assertEquals(
					count,
					new HashSet<>(allocated.stream().map(spawn -> spawn.x() + ":" + spawn.z()).toList()).size(),
					"building plot positions are unique " + count
			);
			assertTrue(
					allocated.stream().allMatch(spawn -> Math.abs(spawn.x()) <= 36.0D
							&& Math.abs(spawn.z()) <= 36.0D),
					"building contestants stay inside the centered plot envelope " + count
			);
			assertions += 2;
		}
		assertEquals(List.of("24.0:0.0"), buildingCoordinates(allocator, 1),
				"one building plot reserves the central pavilion");
		assertEquals(List.of("-12.0:0.0", "12.0:0.0"), buildingCoordinates(allocator, 2),
				"two building plots are equally spaced around center");
		assertEquals(List.of("-12.0:-12.0", "-12.0:12.0", "12.0:-12.0", "12.0:12.0"),
				buildingCoordinates(allocator, 4), "four building plots form a centered square");
		assertEquals(16, buildingCoordinates(allocator, 16).size(),
				"sixteen building plots retain full capacity");
		BlockPos origin = new BlockPos(0, 70, 0);
		var blueprint = dev.agaminggod.arenaagents.scenario.runtime.ScenarioArenaBlueprint.create(
				ScenarioPresets.require("impossible-brief"), origin, 1);
		Map<Long, dev.agaminggod.arenaagents.scenario.runtime.ScenarioArenaBlueprint.Placement> placements =
				dev.agaminggod.arenaagents.scenario.runtime.ScenarioArenaResetJob.canonicalize(blueprint.placements()).stream()
						.collect(java.util.stream.Collectors.toMap(
								placement -> placement.position().asLong(), placement -> placement));
		ScenarioSpawn singleSpawn = allocator.allocate(config(
				ScenarioPresets.require("impossible-brief"), 9_999L, 19L, participants(1))).getFirst();
		assertTrue(isAirOrUnspecified(placements, origin.offset(
				(int) singleSpawn.x(), (int) singleSpawn.y(), (int) singleSpawn.z())),
				"single building contestant has clear feet space");
		assertTrue(isAirOrUnspecified(placements, origin.offset(
				(int) singleSpawn.x(), (int) singleSpawn.y() + 1, (int) singleSpawn.z())),
				"single building contestant has clear head space");
		return assertions + 6;
	}

	private static int verifyScenarioCompletionPolicy() {
		List<ScenarioCompletionPolicy.ParticipantState> allCompleted = List.of(
				participantState("agent-a", Optional.empty(), AgentLifecycleState.COMPLETED, true),
				participantState("agent-b", Optional.empty(), AgentLifecycleState.COMPLETED, true)
		);
		List<ScenarioCompletionPolicy.ParticipantState> onePlanning = List.of(
				participantState("agent-a", Optional.empty(), AgentLifecycleState.COMPLETED, true),
				participantState("agent-b", Optional.empty(), AgentLifecycleState.PLANNING, true)
		);
		assertTrue(ScenarioCompletionPolicy.finishReason(ScenarioCategory.SURVIVAL, allCompleted, false).isPresent(),
				"survival ends only after every chosen model completes");
		assertEquals(Optional.empty(), ScenarioCompletionPolicy.finishReason(ScenarioCategory.BUILDING, onePlanning, false),
				"building remains under the selected models' control while any model is active");
		assertTrue(ScenarioCompletionPolicy.finishReason(ScenarioCategory.BUILDING, allCompleted, false).isPresent(),
				"building ends after every chosen model explicitly completes");
		List<ScenarioCompletionPolicy.ParticipantState> twoPvpSurvivors = List.of(
				participantState("agent-a", Optional.empty(), AgentLifecycleState.ACTING, true),
				participantState("agent-b", Optional.empty(), AgentLifecycleState.ACTING, true)
		);
		assertEquals(Optional.empty(), ScenarioCompletionPolicy.finishReason(ScenarioCategory.PVP, twoPvpSurvivors, false),
				"pvp continues while multiple individual contestants remain alive");
		assertTrue(ScenarioCompletionPolicy.finishReason(ScenarioCategory.PVP, List.of(
				participantState("agent-a", Optional.of("red"), AgentLifecycleState.ACTING, true),
				participantState("agent-b", Optional.of("red"), AgentLifecycleState.ACTING, true),
				participantState("agent-c", Optional.of("blue"), AgentLifecycleState.DEAD, false)
		), false).isPresent(), "pvp finishes when one team remains alive");
		assertEquals(Optional.empty(), ScenarioCompletionPolicy.finishReason(ScenarioCategory.PARKOUR, allCompleted, false),
				"parkour does not trust model completion in place of the course finish");
		assertTrue(ScenarioCompletionPolicy.finishReason(ScenarioCategory.PARKOUR, onePlanning, true).isPresent(),
				"parkour ends when every contestant reaches the final checkpoint");
		ScenarioParkourCourse course = ScenarioParkourCourse.create(2);
		assertTrue(!new ScenarioParkourRunState(course, Map.of("agent-a", 0, "agent-b", 1)).allFinished(),
				"parkour is unfinished before every final checkpoint");
		assertTrue(new ScenarioParkourRunState(course, Map.of("agent-a", 0, "agent-b", 1), Map.of(
				"agent-a", ScenarioParkourCourse.PLATFORM_COUNT - 1,
				"agent-b", ScenarioParkourCourse.PLATFORM_COUNT - 1
		)).allFinished(), "parkour completion is derived from verified final checkpoints");
		return 9;
	}

	private static ScenarioCompletionPolicy.ParticipantState participantState(
			String agentId,
			Optional<String> team,
			AgentLifecycleState lifecycle,
			boolean alive
	) {
		return new ScenarioCompletionPolicy.ParticipantState(agentId, team, lifecycle, alive);
	}

	private static boolean isAirOrUnspecified(
			Map<Long, dev.agaminggod.arenaagents.scenario.runtime.ScenarioArenaBlueprint.Placement> placements,
			BlockPos position
	) {
		var placement = placements.get(position.asLong());
		return placement == null || placement.state().isAir();
	}

	private static List<String> buildingCoordinates(ScenarioSpawnAllocator allocator, int count) {
		return allocator.allocate(config(
				ScenarioPresets.require("impossible-brief"), 9_000L + count, 19L, participants(count)))
				.stream().map(spawn -> spawn.x() + ":" + spawn.z()).sorted().toList();
	}

	private static int verifyDeterministicDirectorEvents() {
		ScenarioEventDirector director = new ScenarioEventDirector();
		ScenarioSessionConfig first = config(ScenarioPresets.require("last-valley"), 91L, 7L, participants(4));
		ScenarioSessionConfig same = config(ScenarioPresets.require("last-valley"), 91L, 7L, participants(4));
		ScenarioSessionConfig changed = config(ScenarioPresets.require("last-valley"), 91L, 8L, participants(4));
		List<ScenarioDirectedEvent> firstSchedule = director.schedule(first);
		assertEquals(firstSchedule, director.schedule(same), "same event seed schedule");
		assertTrue(!firstSchedule.equals(director.schedule(changed)), "different event seed schedule");
		assertEquals(first.preset().dynamicEvents().size(), firstSchedule.size(), "all director events scheduled");
		assertEquals(
				new HashSet<>(first.preset().dynamicEvents()),
				new HashSet<>(firstSchedule.stream().map(ScenarioDirectedEvent::description).toList()),
				"director event descriptions preserved"
		);
		assertTrue(
				firstSchedule.stream().allMatch(event -> event.elapsedTick() >= 0L && event.elapsedTick() < first.durationTicks()),
				"director events stay within session"
		);
		for (int index = 0; index < firstSchedule.size(); index++) {
			assertEquals(index, firstSchedule.get(index).sequence(), "director sequence " + index);
		}
		return 10;
	}

	private static int verifySafeEventMarkers() {
		List<ScenarioDirectedEvent> schedule = new ScenarioEventDirector().schedule(
				config(ScenarioPresets.require("citadel-collapse"), 71L, 72L, participants(4))
		);
		List<ScenarioEventMarker> markers = schedule.stream().map(ScenarioEventMarker::forEvent).toList();
		assertEquals(
				markers.size(),
				new HashSet<>(markers).size(),
				"directed event markers use unique spectator-deck positions"
		);
		assertTrue(
				markers.stream().allMatch(marker ->
						marker.x() >= -6 && marker.x() <= 6
								&& marker.y() == 14
								&& marker.z() >= 67 && marker.z() <= 73),
				"directed event markers stay on the safe spectator deck"
		);
		assertEquals(
				new ScenarioEventMarker(-6, 14, 68),
				ScenarioEventMarker.forEvent(new ScenarioDirectedEvent(0, "first", "scouting", 100L, "First")),
				"first directed event marker is deterministic"
		);
		return 3;
	}

	private static int verifyRuntimeClockDispatch() {
		ScenarioSession session = new ScenarioSession(
				config(ScenarioPresets.require("last-valley"), 51L, 52L, participants(2))
		);
		session.markReady(0L);
		session.beginCountdown(0L);
		session.start(0L);
		ScenarioRuntimeClock clock = new ScenarioRuntimeClock(session);
		ArrayList<String> enteredPhases = new ArrayList<>();
		ArrayList<String> directedEvents = new ArrayList<>();
		int finishSignals = 0;
		for (long tick = 0L; tick <= session.config().durationTicks() + 1L; tick++) {
			ScenarioRuntimeClock.Update update = clock.tick();
			update.enteredPhase().ifPresent(phase -> enteredPhases.add(phase.id()));
			directedEvents.addAll(update.directedEvents().stream().map(ScenarioDirectedEvent::id).toList());
			if (update.finishedNow()) finishSignals++;
		}
		assertEquals(
				session.config().preset().phases().stream().map(ScenarioPhase::id).toList(),
				enteredPhases,
				"runtime clock enters every phase exactly once"
		);
		assertEquals(
				session.config().preset().dynamicEvents().size(),
				new HashSet<>(directedEvents).size(),
				"runtime clock dispatches every directed event exactly once"
		);
		assertEquals(1, finishSignals, "configured duration ends the scenario exactly once");
		assertEquals(ScenarioSessionState.FINISHED, session.state(), "runtime clock finishes at the configured duration");
		assertEquals(session.config().durationTicks(), clock.snapshot().elapsedTick(),
				"elapsed telemetry stops at the configured duration");
		assertEquals(Optional.of("Configured scenario duration elapsed"), session.completionReason(),
				"duration completion records a stable reason");
		assertTrue(!clock.tick().finishedNow(), "terminal completion is emitted only once");
		return 7;
	}

	private static int verifyLegacyRuntimeClockBoundaryRestore() {
		ScenarioSession exactSession = runningClockSession();
		long durationTicks = exactSession.config().durationTicks();
		ScenarioRuntimeClock exactClock = ScenarioRuntimeClock.restore(
				exactSession,
				new ScenarioRuntimeClock.Snapshot(1, durationTicks, 0, Optional.empty(), false)
		);
		assertEquals(ScenarioSessionState.FINISHED, exactSession.state(),
				"legacy clock at duration finishes during restore");
		assertEquals(durationTicks, exactClock.snapshot().elapsedTick(),
				"legacy clock at duration keeps bounded elapsed telemetry");
		assertTrue(exactClock.snapshot().finished(), "legacy clock at duration migrates to a finished snapshot");
		ScenarioRuntimeClock.Update exactUpdate = exactClock.tick();
		assertEquals(durationTicks, exactUpdate.elapsedTick(), "restored duration clock does not consume another tick");
		assertTrue(exactUpdate.enteredPhase().isEmpty(), "restored duration clock enters no extra phase");
		assertEquals(List.of(), exactUpdate.directedEvents(), "restored duration clock dispatches no extra event");
		assertTrue(!exactUpdate.finishedNow(), "restored duration clock does not emit a duplicate finish edge");

		ScenarioSession overflowSession = runningClockSession();
		ScenarioRuntimeClock overflowClock = ScenarioRuntimeClock.restore(
				overflowSession,
				new ScenarioRuntimeClock.Snapshot(1, durationTicks + 1L, 0, Optional.empty(), false)
		);
		assertEquals(ScenarioSessionState.FINISHED, overflowSession.state(),
				"legacy clock beyond duration finishes during restore");
		assertEquals(durationTicks, overflowClock.snapshot().elapsedTick(),
				"legacy clock beyond duration clamps elapsed telemetry");
		assertTrue(overflowClock.snapshot().finished(), "legacy overflow clock migrates to a finished snapshot");
		ScenarioRuntimeClock.Update overflowUpdate = overflowClock.tick();
		assertEquals(durationTicks, overflowUpdate.elapsedTick(), "restored overflow clock does not consume another tick");
		assertTrue(overflowUpdate.enteredPhase().isEmpty(), "restored overflow clock enters no extra phase");
		assertEquals(List.of(), overflowUpdate.directedEvents(), "restored overflow clock dispatches no extra event");
		assertTrue(!overflowUpdate.finishedNow(), "restored overflow clock does not emit a duplicate finish edge");
		assertEquals(Optional.of("Configured scenario duration elapsed"), overflowSession.completionReason(),
				"legacy duration migration records the canonical finish reason");
		return 15;
	}

	private static ScenarioSession runningClockSession() {
		ScenarioSession session = new ScenarioSession(
				config(ScenarioPresets.require("last-valley"), 151L, 152L, participants(2))
		);
		session.markReady(0L);
		session.beginCountdown(0L);
		session.start(0L);
		session.pauseForRecovery(0L);
		return session;
	}

	private static int verifyPhases() {
		ScenarioPreset survival = ScenarioPresets.require("last-valley");
		assertEquals("dawn", survival.phaseAt(0L).orElseThrow().id(), "first phase");
		assertEquals("forecast", survival.phaseAt(7_200L).orElseThrow().id(), "phase boundary");
		assertEquals("sunrise", survival.phaseAt(survival.defaultDurationTicks() - 1L).orElseThrow().id(), "last phase");
		assertEquals(Optional.empty(), survival.phaseAt(survival.defaultDurationTicks()), "after final phase");
		assertEquals(Optional.empty(), survival.phaseAt(-1L), "negative elapsed tick");
		ScenarioPreset citadel = ScenarioPresets.require("citadel-collapse");
		assertEquals("conflict", citadel.phaseAt(0L).orElseThrow().id(), "PvP conflict is open from tick zero");
		assertTrue(citadel.playerCombat(), "PvP player combat is enabled from launch");
		assertTrue(citadel.dynamicEvents().stream().noneMatch(event -> event.toLowerCase(java.util.Locale.ROOT).contains("grace")),
				"PvP metadata contains no grace-period instruction");
		return 8;
	}

	private static int verifyLifecycle() {
		ScenarioSession session = new ScenarioSession(
				config(ScenarioPresets.require("citadel-collapse"), 31L, 32L, participants(4))
		);
		assertEquals(ScenarioSessionState.PREPARING, session.state(), "initial state");
		expectFailure(() -> session.start(10L), "INVALID_SESSION_TRANSITION");
		session.markReady(20L);
		session.beginCountdown(30L);
		session.start(40L);
		session.pause(50L);
		expectFailure(() -> session.resume(49L), "STALE_EVENT_TICK");
		assertEquals(ScenarioSessionState.PAUSED, session.state(), "stale transition is atomic");
		expectFailure(() -> session.finish(49L, "stale finish"), "STALE_EVENT_TICK");
		assertEquals(Optional.empty(), session.completionReason(), "stale finish reason is atomic");
		session.resume(60L);
		session.finish(70L, "Last agent standing");
		assertEquals(ScenarioSessionState.FINISHED, session.state(), "finished state");
		int eventsAfterFinish = session.evidence().size();
		session.finish(80L, "duplicate finish");
		session.stop(90L, "duplicate stop");
		assertEquals(eventsAfterFinish, session.evidence().size(), "finish and stop idempotence");
		assertEquals("Last agent standing", session.completionReason().orElseThrow(), "completion reason retained");
		return 5;
	}

	private static int verifyScoringAndEvidence() {
		ScenarioSession session = runningSession();
		ScenarioEvent encounter = session.record(
				90L,
				ScenarioEventType.DIRECTOR_EVENT,
				Optional.of("agent-0"),
				Optional.of("minecraft:zombie"),
				"hostile-encounter",
				1.0D,
				"A zombie entered the participant's shelter",
				Map.of("light", "4")
		);
		assertEquals("minecraft:zombie", encounter.targetId().orElseThrow(), "non-agent evidence target");
		ScenarioScoreChange first = session.award(
				100L,
				"agent-0",
				"survival",
				12.5D,
				"Reached extraction alive",
				Map.of("health", "18")
		);
		session.award(110L, "agent-0", "food-security", 3.0D, "Secured renewable food", Map.of());
		assertDoubleEquals(15.5D, session.scores().get("agent-0"), "score total");
		assertEquals(2, session.scoreAudit().size(), "score audit count");
		assertEquals(first.evidenceSequence(), session.scoreAudit().getFirst().evidenceSequence(), "score evidence linkage");
		assertEquals("survival", session.scoreAudit().getFirst().ruleId(), "score rule");
		assertEquals("Reached extraction alive", session.scoreAudit().getFirst().reason(), "score reason");
		assertEquals("18", session.evidence().get(first.evidenceSequence()).attributes().get("health"), "evidence attributes");
		expectFailure(
				() -> session.award(120L, "agent-0", "not-a-rule", 1.0D, "Invalid", Map.of()),
				"UNKNOWN_SCORE_RULE"
		);
		expectFailure(
				() -> session.award(120L, "missing-agent", "survival", 1.0D, "Invalid", Map.of()),
				"UNKNOWN_PARTICIPANT"
		);
		expectUnsupported(() -> session.evidence().clear(), "evidence list is immutable");
		expectUnsupported(() -> session.scores().put("agent-0", 999.0D), "scores map is immutable");
		return 11;
	}

	private static int verifyReset() {
		ScenarioSession session = runningSession();
		session.award(100L, "agent-0", "survival", 2.0D, "Still alive", Map.of());
		session.stop(200L, "Producer stop");
		session.reset(300L);
		assertEquals(ScenarioSessionState.PREPARING, session.state(), "reset state");
		assertDoubleEquals(0.0D, session.scores().get("agent-0"), "reset score");
		assertEquals(1, session.evidence().size(), "reset evidence marker");
		int resetEvidenceCount = session.evidence().size();
		session.reset(400L);
		assertEquals(resetEvidenceCount, session.evidence().size(), "reset idempotence");
		return 4;
	}

	private static ScenarioSession runningSession() {
		ScenarioSession session = new ScenarioSession(
				config(ScenarioPresets.require("last-valley"), 51L, 52L, participants(2))
		);
		session.markReady(10L);
		session.beginCountdown(20L);
		session.start(30L);
		return session;
	}

	private static ScenarioSessionConfig config(
			ScenarioPreset preset,
			long worldSeed,
			long eventSeed,
			List<ScenarioParticipant> participants
	) {
		return new ScenarioSessionConfig(
				SESSION_ID,
				preset,
				worldSeed,
				eventSeed,
				preset.defaultDurationTicks(),
				true,
				participants,
				CREATED_AT_EPOCH_MS
		);
	}

	private static List<ScenarioParticipant> participants(int count) {
		ArrayList<ScenarioParticipant> participants = new ArrayList<>(count);
		for (int index = 0; index < count; index++) {
			participants.add(new ScenarioParticipant(
					"agent-" + index,
					"Model " + index,
					Optional.of(index % 2 == 0 ? "amber" : "blue")
			));
		}
		return List.copyOf(participants);
	}

	private static void expectFailure(Runnable operation, String expectedCode) {
		try {
			operation.run();
			throw new AssertionError("Expected scenario failure " + expectedCode);
		} catch (ScenarioValidationException exception) {
			assertEquals(expectedCode, exception.code(), "failure code");
		}
	}

	private static void expectUnsupported(Runnable operation, String label) {
		try {
			operation.run();
			throw new AssertionError(label + ": expected UnsupportedOperationException");
		} catch (UnsupportedOperationException expected) {
		}
	}

	private static void assertTrue(boolean value, String label) {
		if (!value) {
			throw new AssertionError(label + ": expected true");
		}
	}

	private static void assertDoubleEquals(double expected, double actual, String label) {
		if (Math.abs(expected - actual) > 0.000_001D) {
			throw new AssertionError(label + ": expected <" + expected + "> but was <" + actual + ">");
		}
	}

	private static void assertEquals(Object expected, Object actual, String label) {
		if (!expected.equals(actual)) {
			throw new AssertionError(label + ": expected <" + expected + "> but was <" + actual + ">");
		}
	}
}
