package dev.agaminggod.arenaagents.client.navigation;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;

public final class LocalPathfinderVerification {
	private static final int TEST_NODE_LIMIT = 128;
	private static final long TEST_TIME_BUDGET_NANOS = 1_000L;

	private LocalPathfinderVerification() {
	}

	public static int verify() {
		int assertions = 0;
		assertions += verifyFlatPath();
		assertions += verifyOneBlockJump();
		assertions += verifySingleBlockGapJump();
		assertions += verifySafeThreeBlockDrop();
		assertions += verifyWallHasNoPath();
		assertions += verifyUnsafeDropHasNoPath();
		assertions += verifyLimitsAreExplicit();
		assertions += verifyTieBreakIsDeterministic();
		assertions += verifyStartEqualsGoal();
		assertions += verifyPathIsImmutableAndAcyclic();
		assertions += verifySharedBudgetIsAggregate();
		assertions += verifySharedBudgetReportsPerRequestDeltas();
		assertions += verifyOpenSetExhaustionAtNodeBoundIsNoPath();
		assertions += verifyRetainedSearchContinuesAcrossBudgets();
		assertions += verifyThreeDimensionalFrontier();
		assertions += verifyDetourCanStartAwayFromDestination();
		assertions += verifySurfaceSwimmingAndBreathingClearance();
		assertions += verifyClimbingToGroundedExit();
		return assertions;
	}

	private static int verifyRetainedSearchContinuesAcrossBudgets() {
		TestWorld world = new TestWorld();
		for (int x = 0; x <= 24; x++) world.standable(position(x, 64, 0));
		GridPosition destination = position(24, 64, 0);
		LocalPathfinder.Search search = new LocalPathfinder().beginSearch(
				position(0, 64, 0), Set.of(destination), destination, 32, Set.of());
		PathPlan plan = null;
		int expanded = 0;
		int slices = 0;
		while (!search.finished() && slices++ < 20) {
			plan = search.advance(world, new LocalPathfinder.SearchBudget(2, TEST_TIME_BUDGET_NANOS, () -> 0L));
			expanded += plan.expandedNodes();
		}
		assertTrue(search.finished(), "small tick budgets eventually finish the retained route");
		assertEquals(PathOutcome.FOUND, plan.outcome(), "retained search finds the exact destination");
		assertEquals(destination, plan.nodes().getLast().position(), "resumption preserves endpoint authority");
		assertEquals(24, expanded, "resumption expands each corridor node once instead of restarting");
		assertTrue(slices > 1 && search.retainedNodes() <= LocalPathfinder.MAX_RETAINED_NODES, "search remains sliced and memory bounded");
		return 5;
	}

	private static int verifyThreeDimensionalFrontier() {
		TestWorld world = new TestWorld();
		for (int x = 0; x <= 8; x++) {
			GridPosition feet = position(x, 64 + Math.min(x, 4), 0);
			world.standable(feet);
			world.cell(feet.above(2), WalkabilityView.Cell.CLEAR);
		}
		GridPosition focus = position(100, 64, 0);
		LocalPathfinder.Search search = new LocalPathfinder().beginSearch(position(0, 64, 0), Set.of(), focus, 8, Set.of());
		PathPlan plan = search.advance(world, new LocalPathfinder.SearchBudget(128, TEST_TIME_BUDGET_NANOS, () -> 0L));
		assertEquals(PathOutcome.FOUND, plan.outcome(), "a reachable elevated boundary replaces the unsupported straight-line midpoint");
		assertEquals(position(8, 68, 0), plan.nodes().getLast().position(), "the local frontier retains actual terrain height");
		assertTrue(plan.nodes().stream().anyMatch(node -> node.traversal() == TraversalType.JUMP_UP), "the frontier has an executable route through the rise");
		LocalPathfinder.Search visited = new LocalPathfinder().beginSearch(position(0, 64, 0), Set.of(), focus, 8, Set.of(position(8, 68, 0)));
		assertEquals(PathOutcome.NO_PATH, visited.advance(world,
				new LocalPathfinder.SearchBudget(128, TEST_TIME_BUDGET_NANOS, () -> 0L)).outcome(), "an exhausted boundary does not repeat forever");
		return 4;
	}

	private static int verifyDetourCanStartAwayFromDestination() {
		TestWorld world = new TestWorld();
		for (int z = 0; z <= 8; z++) {
			world.standable(position(0, 64, z));
			world.standable(position(8, 64, z));
		}
		for (int x = 0; x <= 8; x++) world.standable(position(x, 64, 8));
		GridPosition destination = position(8, 64, 0);
		LocalPathfinder.Search search = new LocalPathfinder().beginSearch(position(0, 64, 0), Set.of(destination), destination, 8, Set.of());
		PathPlan plan = search.advance(world, new LocalPathfinder.SearchBudget(128, TEST_TIME_BUDGET_NANOS, () -> 0L));
		assertEquals(PathOutcome.FOUND, plan.outcome(), "a U-shaped detour reaches the selected goal");
		assertEquals(position(0, 64, 1), plan.nodes().get(1).position(), "the first detour step may increase distance to the goal");
		assertEquals(destination, plan.nodes().getLast().position(), "a reachable goal takes precedence over partial frontiers");
		return 3;
	}

	private static int verifySurfaceSwimmingAndBreathingClearance() {
		TestWorld world = new TestWorld().standable(position(0, 64, 0)).standable(position(9, 64, 0));
		for (int x = 1; x <= 8; x++) {
			world.cell(position(x, 64, 0), WalkabilityView.Cell.WATER);
			world.cell(position(x, 65, 0), WalkabilityView.Cell.CLEAR);
		}
		PathPlan plan = find(world, position(0, 64, 0), position(9, 64, 0));
		assertEquals(PathOutcome.FOUND, plan.outcome(), "open surface water can connect supported banks beyond four blocks");
		assertEquals(8L, plan.nodes().stream().filter(node -> node.traversal() == TraversalType.SWIM).count(), "water transitions are explicit");
		world.cell(position(4, 65, 0), WalkabilityView.Cell.WATER);
		assertEquals(PathOutcome.NO_PATH, find(world, position(0, 64, 0), position(9, 64, 0)).outcome(), "surface navigation never silently commits to a submerged passage");
		return 3;
	}

	private static int verifyClimbingToGroundedExit() {
		TestWorld world = new TestWorld().standable(position(0, 64, 0)).standable(position(1, 69, 0));
		for (int y = 64; y <= 69; y++) world.cell(position(0, y, 0), WalkabilityView.Cell.CLIMBABLE);
		world.cell(position(0, 70, 0), WalkabilityView.Cell.CLEAR);
		world.cell(position(0, 71, 0), WalkabilityView.Cell.CLEAR);
		PathPlan plan = find(world, position(0, 64, 0), position(1, 69, 0));
		assertEquals(PathOutcome.FOUND, plan.outcome(), "a climbable column connects to a supported exit");
		assertTrue(plan.nodes().stream().anyMatch(node -> node.traversal() == TraversalType.CLIMB), "vertical climb steps are explicit");
		assertEquals(position(1, 69, 0), plan.nodes().getLast().position(), "climbing preserves the grounded endpoint");
		return 3;
	}

	private static int verifyFlatPath() {
		TestWorld world = new TestWorld();
		for (int x = 0; x <= 4; x++) {
			world.standable(position(x, 64, 0));
		}

		PathPlan plan = find(world, position(0, 64, 0), position(4, 64, 0));
		assertEquals(PathOutcome.FOUND, plan.outcome(), "flat path outcome");
		assertEquals(5, plan.nodes().size(), "flat path includes start and destination");
		assertEquals(position(0, 64, 0), plan.nodes().getFirst().position(), "flat path start");
		assertEquals(position(4, 64, 0), plan.nodes().getLast().position(), "flat path destination");
		assertEquals(
				List.of(
						TraversalType.START,
						TraversalType.WALK,
						TraversalType.WALK,
						TraversalType.WALK,
						TraversalType.WALK
				),
				plan.nodes().stream().map(PathNode::traversal).toList(),
				"flat path traversal types"
		);
		return 5;
	}

	private static int verifyOneBlockJump() {
		TestWorld world = new TestWorld()
				.standable(position(0, 64, 0))
				.standable(position(1, 65, 0));
		world.cell(position(0, 66, 0), WalkabilityView.Cell.CLEAR);

		PathPlan plan = find(world, position(0, 64, 0), position(1, 65, 0));
		assertEquals(PathOutcome.FOUND, plan.outcome(), "one-block jump outcome");
		assertEquals(2, plan.nodes().size(), "one-block jump node count");
		assertEquals(TraversalType.JUMP_UP, plan.nodes().getLast().traversal(), "one-block jump traversal");
		return 3;
	}

	private static int verifySingleBlockGapJump() {
		TestWorld world = new TestWorld()
				.standable(position(0, 64, 0))
				.standable(position(2, 64, 0));
		world.cell(position(1, 64, 0), WalkabilityView.Cell.CLEAR);
		world.cell(position(1, 65, 0), WalkabilityView.Cell.CLEAR);
		world.cell(position(0, 66, 0), WalkabilityView.Cell.CLEAR);

		PathPlan plan = find(world, position(0, 64, 0), position(2, 64, 0));
		assertEquals(PathOutcome.FOUND, plan.outcome(), "single-block gap jump outcome");
		assertEquals(2, plan.nodes().size(), "single-block gap jump node count");
		assertEquals("JUMP_GAP", plan.nodes().getLast().traversal().name(), "single-block gap traversal");
		return 3;
	}

	private static int verifySafeThreeBlockDrop() {
		GridPosition start = position(0, 68, 0);
		GridPosition destination = position(1, 65, 0);
		TestWorld world = new TestWorld().standable(start).standable(destination);
		for (int y = 67; y <= 69; y++) {
			world.cell(position(1, y, 0), WalkabilityView.Cell.CLEAR);
		}

		PathPlan plan = find(world, start, destination);
		assertEquals(PathOutcome.FOUND, plan.outcome(), "safe three-block drop outcome");
		assertEquals(TraversalType.DROP_DOWN, plan.nodes().getLast().traversal(), "safe drop traversal");
		return 2;
	}

	private static int verifyWallHasNoPath() {
		TestWorld world = new TestWorld()
				.standable(position(0, 64, 0))
				.standable(position(2, 64, 0));
		world.cell(position(1, 64, 0), WalkabilityView.Cell.BLOCKED);
		world.cell(position(1, 65, 0), WalkabilityView.Cell.BLOCKED);

		PathPlan plan = find(world, position(0, 64, 0), position(2, 64, 0));
		assertEquals(PathOutcome.NO_PATH, plan.outcome(), "two-block wall has no path");
		assertEquals(List.of(), plan.nodes(), "no-path plan has no nodes");
		return 2;
	}

	private static int verifyUnsafeDropHasNoPath() {
		TestWorld world = new TestWorld()
				.standable(position(0, 68, 0))
				.standable(position(1, 64, 0));

		PathPlan plan = find(world, position(0, 68, 0), position(1, 64, 0));
		assertEquals(PathOutcome.NO_PATH, plan.outcome(), "drop beyond three blocks is rejected");

		TestWorld hazardousLanding = new TestWorld()
				.standable(position(0, 65, 0))
				.standable(position(1, 64, 0));
		hazardousLanding.cell(position(1, 63, 0), WalkabilityView.Cell.HAZARD);
		PathPlan hazardousPlan = find(
				hazardousLanding,
				position(0, 65, 0),
				position(1, 64, 0)
		);
		assertEquals(PathOutcome.INVALID, hazardousPlan.outcome(), "hazardous destination is invalid");
		return 2;
	}

	private static int verifyLimitsAreExplicit() {
		TestWorld world = new TestWorld();
		for (int x = 0; x <= 8; x++) {
			world.standable(position(x, 64, 0));
		}

		LocalPathfinder pathfinder = new LocalPathfinder();
		AtomicLong steadyClock = new AtomicLong();
		PathPlan nodeLimited = pathfinder.findPath(
				world,
				position(0, 64, 0),
				position(8, 64, 0),
				2,
				TEST_TIME_BUDGET_NANOS,
				steadyClock::get
		);
		assertEquals(PathOutcome.NODE_LIMIT, nodeLimited.outcome(), "node limit outcome");
		assertEquals(2, nodeLimited.expandedNodes(), "node limit expanded count");

		AtomicLong advancingClock = new AtomicLong();
		PathPlan timeLimited = pathfinder.findPath(
				world,
				position(0, 64, 0),
				position(8, 64, 0),
				TEST_NODE_LIMIT,
				40L,
				() -> advancingClock.getAndAdd(41L)
		);
		assertEquals(PathOutcome.TIME_LIMIT, timeLimited.outcome(), "deadline outcome");
		assertEquals(0, timeLimited.expandedNodes(), "deadline stops before expansion");
		return 4;
	}

	private static int verifyTieBreakIsDeterministic() {
		TestWorld world = new TestWorld();
		for (int x = 0; x <= 2; x++) {
			world.standable(position(x, 64, -1));
			world.standable(position(x, 64, 1));
		}
		world.standable(position(0, 64, 0));
		world.standable(position(2, 64, 0));

		PathPlan first = find(world, position(0, 64, 0), position(2, 64, 0));
		PathPlan second = find(world, position(0, 64, 0), position(2, 64, 0));
		assertEquals(PathOutcome.FOUND, first.outcome(), "tie-break path outcome");
		assertEquals(first, second, "tie-break path is repeatable");
		assertEquals(position(0, 64, 1), first.nodes().get(1).position(), "fixed neighbor-order tie break");
		return 3;
	}

	private static int verifyStartEqualsGoal() {
		GridPosition start = position(3, 70, -2);
		PathPlan plan = find(new TestWorld().standable(start), start, start);
		assertEquals(PathOutcome.FOUND, plan.outcome(), "start-equals-goal outcome");
		assertEquals(List.of(new PathNode(start, TraversalType.START)), plan.nodes(), "start-equals-goal path");
		assertEquals(0, plan.expandedNodes(), "start-equals-goal expands no nodes");
		return 3;
	}

	private static int verifyPathIsImmutableAndAcyclic() {
		TestWorld world = new TestWorld();
		for (int x = 0; x <= 4; x++) {
			world.standable(position(x, 64, 0));
		}
		PathPlan plan = find(world, position(0, 64, 0), position(4, 64, 0));
		Set<GridPosition> unique = new HashSet<>(plan.nodes().stream().map(PathNode::position).toList());
		assertEquals(plan.nodes().size(), unique.size(), "reconstructed path has no cycle");
		expectThrows(
				UnsupportedOperationException.class,
				() -> plan.nodes().add(new PathNode(position(5, 64, 0), TraversalType.WALK)),
				"path nodes are immutable"
		);
		return 2;
	}

	private static int verifySharedBudgetIsAggregate() {
		TestWorld world = new TestWorld();
		world.standable(position(0, 64, 0)).standable(position(1, 64, 0));
		LocalPathfinder.SearchBudget budget = new LocalPathfinder.SearchBudget(
				1,
				TEST_TIME_BUDGET_NANOS,
				() -> 0L
		);
		LocalPathfinder pathfinder = new LocalPathfinder();

		PathPlan first = pathfinder.findPath(
				world,
				position(0, 64, 0),
				position(1, 64, 0),
				budget
		);
		PathPlan second = pathfinder.findPath(
				world,
				position(0, 64, 0),
				position(1, 64, 0),
				budget
		);

		assertEquals(PathOutcome.FOUND, first.outcome(), "shared budget first request is served");
		assertEquals(PathOutcome.NODE_LIMIT, second.outcome(), "shared budget defers the next request");
		assertEquals(1, first.expandedNodes(), "first request reports its own expansion count");
		assertEquals(0, second.expandedNodes(), "deferred request reports no local expansions");
		assertEquals(1, budget.expandedNodes(), "shared budget counts expansions once across requests");
		assertTrue(budget.exhausted(), "shared budget reports aggregate exhaustion");
		return 6;
	}

	private static int verifySharedBudgetReportsPerRequestDeltas() {
		TestWorld world = new TestWorld()
				.standable(position(0, 64, 0))
				.standable(position(1, 64, 0))
				.standable(position(0, 64, 1))
				.standable(position(1, 64, 1));
		LocalPathfinder.SearchBudget budget = new LocalPathfinder.SearchBudget(
				2,
				TEST_TIME_BUDGET_NANOS,
				() -> 0L
		);
		LocalPathfinder pathfinder = new LocalPathfinder();
		PathPlan first = pathfinder.findPath(
				world,
				position(0, 64, 0),
				position(1, 64, 0),
				budget
		);
		PathPlan second = pathfinder.findPath(
				world,
				position(0, 64, 1),
				position(1, 64, 1),
				budget
		);

		assertEquals(PathOutcome.FOUND, first.outcome(), "first shared request finds its path");
		assertEquals(PathOutcome.FOUND, second.outcome(), "second shared request finds its path");
		assertEquals(1, first.expandedNodes(), "first shared request reports a local delta");
		assertEquals(1, second.expandedNodes(), "second shared request reports a local delta");
		assertEquals(2, budget.expandedNodes(), "shared budget still reports aggregate expansions");
		return 5;
	}

	private static int verifyOpenSetExhaustionAtNodeBoundIsNoPath() {
		GridPosition start = position(0, 64, 0);
		GridPosition destination = position(4, 64, 0);
		TestWorld world = new TestWorld().standable(start).standable(destination);
		PathPlan plan = new LocalPathfinder().findPath(
				world,
				start,
				destination,
				1,
				TEST_TIME_BUDGET_NANOS,
				() -> 0L
		);
		assertEquals(PathOutcome.NO_PATH, plan.outcome(), "an empty open set at the node bound is no path");
		assertEquals(1, plan.expandedNodes(), "the isolated search reports its one expansion");
		return 2;
	}

	private static PathPlan find(TestWorld world, GridPosition start, GridPosition destination) {
		return new LocalPathfinder().findPath(
				world,
				start,
				destination,
				TEST_NODE_LIMIT,
				TEST_TIME_BUDGET_NANOS,
				() -> 0L
		);
	}

	private static GridPosition position(int x, int y, int z) {
		return new GridPosition(x, y, z);
	}

	private static void assertEquals(Object expected, Object actual, String label) {
		if (!expected.equals(actual)) {
			throw new AssertionError(label + ": expected <" + expected + "> but was <" + actual + ">");
		}
	}

	private static void assertTrue(boolean condition, String label) {
		if (!condition) {
			throw new AssertionError(label);
		}
	}

	private static <T extends Throwable> void expectThrows(
			Class<T> expectedType,
			Runnable action,
			String label
	) {
		try {
			action.run();
		} catch (Throwable throwable) {
			if (expectedType.isInstance(throwable)) {
				return;
			}
			throw new AssertionError(label + " threw " + throwable.getClass().getSimpleName(), throwable);
		}
		throw new AssertionError(label + " did not throw " + expectedType.getSimpleName());
	}

	private static final class TestWorld implements WalkabilityView {
		private final Map<GridPosition, Cell> cells = new HashMap<>();

		private TestWorld standable(GridPosition feet) {
			cell(feet.below(), Cell.SAFE_SUPPORT);
			cell(feet, Cell.CLEAR);
			cell(feet.above(), Cell.CLEAR);
			return this;
		}

		private void cell(GridPosition position, Cell cell) {
			cells.put(position, cell);
		}

		@Override
		public Cell cellAt(GridPosition position) {
			return cells.getOrDefault(position, Cell.UNLOADED);
		}
	}
}
