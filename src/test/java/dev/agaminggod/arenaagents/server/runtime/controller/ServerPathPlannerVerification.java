package dev.agaminggod.arenaagents.server.runtime.controller;

import dev.agaminggod.arenaagents.client.navigation.GridPosition;
import dev.agaminggod.arenaagents.client.navigation.LocalPathfinder;
import dev.agaminggod.arenaagents.client.navigation.PathOutcome;
import dev.agaminggod.arenaagents.client.navigation.PathPlan;
import dev.agaminggod.arenaagents.client.navigation.WalkabilityView;

import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;

public final class ServerPathPlannerVerification {
	private ServerPathPlannerVerification() {
	}

	public static int verify() {
		ServerPathPlanner planner = new ServerPathPlanner();
		Set<GridPosition> blocked = new HashSet<>();
		blocked.add(new GridPosition(1, 64, 0));

		WalkabilityView view = position -> {
			if (blocked.contains(position)) {
				return WalkabilityView.Cell.BLOCKED;
			}
			if (position.y() == 63) {
				return WalkabilityView.Cell.SAFE_SUPPORT;
			}
			return position.y() >= 64 && position.y() <= 65
					? WalkabilityView.Cell.CLEAR
					: WalkabilityView.Cell.BLOCKED;
		};

		PathPlan first = planner.findPath(
				view,
				new GridPosition(0, 64, 0),
				new GridPosition(2, 64, 0)
		);
		PathPlan second = planner.findPath(
				view,
				new GridPosition(0, 64, 0),
				new GridPosition(2, 64, 0)
		);

		assertEquals(PathOutcome.FOUND, first.outcome(), "server planner finds a bounded route");
		assertEquals(first.nodes(), second.nodes(), "server planning is deterministic");
		assertTrue(first.nodes().stream().noneMatch(node -> blocked.contains(node.position())),
				"server plan does not cross blocked cells");
		return 3 + verifyElevationAndHoleSafety() + verifyDeferredPlanningIsRetryable() + verifySharedElapsedBudget()
				+ verifyRoundRobinAdmissionEventuallyServesAll() + verifyContentionDeferralWindow()
				+ verifyTickScopeRestoresPreviousBudget() + verifyRetainedSearchUsesSharedTickBudget();
	}

	private static int verifyRetainedSearchUsesSharedTickBudget() {
		ServerPathPlanner planner = new ServerPathPlanner();
		WalkabilityView view = position -> position.y() == 63 ? WalkabilityView.Cell.SAFE_SUPPORT
				: position.y() >= 64 && position.y() <= 65 ? WalkabilityView.Cell.CLEAR : WalkabilityView.Cell.BLOCKED;
		GridPosition firstGoal = new GridPosition(4, 64, 0);
		GridPosition secondGoal = new GridPosition(4, 64, 1);
		LocalPathfinder.Search[] searches = {
				planner.beginSearch(new GridPosition(0, 64, 0), Set.of(firstGoal), firstGoal, 8, Set.of()),
				planner.beginSearch(new GridPosition(0, 64, 1), Set.of(secondGoal), secondGoal, 8, Set.of())
		};
		int expanded = 0;
		for (int tick = 0; tick < 8; tick++) {
			try (ServerPathPlanner.TickScope scope = ServerPathPlanner.beginServerTick(2, LocalPathfinder.MAX_PLANNING_TIME_NANOS, () -> 0L)) {
				for (int offset = 0; offset < searches.length; offset++) {
					LocalPathfinder.Search search = searches[(tick + offset) % searches.length];
					if (search.finished()) continue;
					ServerPathPlanner.PlanningResult result = planner.resume(search, view);
					if (!result.deferred()) assertEquals(PathOutcome.FOUND, result.plan().outcome(), "retained controller search finishes its selected route");
				}
				assertTrue(scope.budget().expandedNodes() <= 2, "resumed searches share one tick expansion limit");
				expanded += scope.budget().expandedNodes();
			}
		}
		assertTrue(searches[0].finished() && searches[1].finished(), "both retained requests make progress under rotating admission");
		assertEquals(8, expanded, "retained requests do not repay previous expansion work");
		return 12;
	}

	private static int verifyElevationAndHoleSafety() {
		ServerPathPlanner planner = new ServerPathPlanner();
		GridPosition hole = new GridPosition(1, 64, 0);
		GridPosition lava = new GridPosition(1, 64, 1);
		WalkabilityView detour = position -> {
			if (position.equals(lava)) return WalkabilityView.Cell.HAZARD;
			if (position.y() == 63 && position.x() == hole.x() && position.z() == hole.z()) return WalkabilityView.Cell.CLEAR;
			if (position.y() == 63) return WalkabilityView.Cell.SAFE_SUPPORT;
			return position.y() == 64 || position.y() == 65
					? WalkabilityView.Cell.CLEAR
					: WalkabilityView.Cell.BLOCKED;
		};
		PathPlan aroundHole = planner.findPath(detour, new GridPosition(0, 64, 0), new GridPosition(2, 64, 0));
		assertEquals(PathOutcome.FOUND, aroundHole.outcome(), "planner deterministically routes around a hole and lava");
		assertTrue(aroundHole.nodes().stream().noneMatch(node -> node.position().equals(hole) || node.position().equals(lava)),
				"hole and lava cells never enter the route");

		WalkabilityView elevation = position -> {
			if (position.equals(new GridPosition(1, 64, 0))) return WalkabilityView.Cell.SAFE_SUPPORT;
			if (position.y() == 63) return WalkabilityView.Cell.SAFE_SUPPORT;
			return position.y() >= 64 && position.y() <= 66
					? WalkabilityView.Cell.CLEAR
					: WalkabilityView.Cell.BLOCKED;
		};
		PathPlan stepUp = planner.findPath(elevation, new GridPosition(0, 64, 0), new GridPosition(1, 65, 0));
		assertEquals(PathOutcome.FOUND, stepUp.outcome(), "one-block elevation has a bounded route");
		assertEquals(dev.agaminggod.arenaagents.client.navigation.TraversalType.JUMP_UP,
				stepUp.nodes().get(stepUp.nodes().size() - 1).traversal(),
				"one-block elevation requires the safe jump-up traversal");
		return 5;
	}

	private static int verifyDeferredPlanningIsRetryable() {
		ServerPathPlanner planner = new ServerPathPlanner();
		WalkabilityView view = position -> position.y() == 63
				? WalkabilityView.Cell.SAFE_SUPPORT
				: position.y() >= 64 && position.y() <= 65
						? WalkabilityView.Cell.CLEAR
						: WalkabilityView.Cell.BLOCKED;
		ServerPathPlanner.TickBudget budget = new ServerPathPlanner.TickBudget(
				1,
				LocalPathfinder.MAX_PLANNING_TIME_NANOS,
				() -> 0L
		);
		ServerPathPlanner.PlanningResult first = planner.planPath(
				view,
				new GridPosition(0, 64, 0),
				new GridPosition(1, 64, 0),
				budget
		);
		ServerPathPlanner.PlanningResult deferred = planner.planPath(
				view,
				new GridPosition(0, 64, 0),
				new GridPosition(1, 64, 0),
				budget
		);

		assertTrue(!first.deferred(), "first planner request is admitted");
		assertTrue(deferred.deferred(), "aggregate exhaustion defers the next planner request");
		assertEquals(PathOutcome.NODE_LIMIT, deferred.plan().outcome(),
				"deferred planning retains the bounded search diagnostic without becoming a terminal action failure");

		ServerPathPlanner.TickBudget retryBudget = new ServerPathPlanner.TickBudget(
				LocalPathfinder.MAX_EXPANDED_NODES,
				LocalPathfinder.MAX_PLANNING_TIME_NANOS,
				() -> 0L
		);
		ServerPathPlanner.PlanningResult retry = planner.planPath(
				view,
				new GridPosition(0, 64, 0),
				new GridPosition(1, 64, 0),
				retryBudget
		);
		assertTrue(!retry.deferred(), "a later tick retries deferred planning");
		assertEquals(PathOutcome.FOUND, retry.plan().outcome(), "a later tick eventually serves the request");
		return 4;
	}

	private static int verifyRoundRobinAdmissionEventuallyServesAll() {
		ServerPathPlanner planner = new ServerPathPlanner();
		WalkabilityView view = position -> position.y() == 63
				? WalkabilityView.Cell.SAFE_SUPPORT
				: position.y() >= 64 && position.y() <= 65
						? WalkabilityView.Cell.CLEAR
						: WalkabilityView.Cell.BLOCKED;
		int agentCount = 16;
		Set<Integer> served = new HashSet<>();
		for (int tick = 0; tick < agentCount; tick++) {
			ServerPathPlanner.TickBudget budget = new ServerPathPlanner.TickBudget(
					2,
					LocalPathfinder.MAX_PLANNING_TIME_NANOS,
					() -> 0L
			);
			int start = tick % agentCount;
			for (int offset = 0; offset < agentCount; offset++) {
				int agent = (start + offset) % agentCount;
				ServerPathPlanner.PlanningResult result = planner.planPath(
						view,
						new GridPosition(0, 64, agent),
						new GridPosition(1, 64, agent),
						budget
				);
				if (!result.deferred() && result.plan().outcome() == PathOutcome.FOUND) served.add(agent);
			}
			assertTrue(budget.expandedNodes() <= 2,
					"one server tick never exceeds its aggregate expansion budget");
		}
		assertEquals(agentCount, served.size(), "sixteen retrying agents eventually receive service");
		return 1;
	}

	private static int verifySharedElapsedBudget() {
		ServerPathPlanner planner = new ServerPathPlanner();
		WalkabilityView view = position -> position.y() == 63
				? WalkabilityView.Cell.SAFE_SUPPORT
				: position.y() >= 64 && position.y() <= 65
						? WalkabilityView.Cell.CLEAR
						: WalkabilityView.Cell.BLOCKED;
		AtomicLong clock = new AtomicLong();
		ServerPathPlanner.TickBudget budget = new ServerPathPlanner.TickBudget(
				LocalPathfinder.MAX_EXPANDED_NODES,
				40L,
				() -> clock.getAndAdd(41L)
		);
		ServerPathPlanner.PlanningResult first = planner.planPath(
				view,
				new GridPosition(0, 64, 0),
				new GridPosition(1, 64, 0),
				budget
		);
		ServerPathPlanner.PlanningResult second = planner.planPath(
				view,
				new GridPosition(0, 64, 1),
				new GridPosition(1, 64, 1),
				budget
		);
		assertTrue(first.deferred(), "elapsed tick budget defers the first request at its deadline");
		assertEquals(PathOutcome.TIME_LIMIT, first.plan().outcome(), "elapsed exhaustion reports time limit");
		assertTrue(second.deferred(), "elapsed tick budget defers every later request in the same tick");
		assertEquals(0, budget.expandedNodes(), "elapsed exhaustion performs no expansion");
		return 4;
	}

	private static int verifyContentionDeferralWindow() {
		ServerPathPlanner planner = new ServerPathPlanner();
		WalkabilityView view = position -> position.y() == 63
				? WalkabilityView.Cell.SAFE_SUPPORT
				: position.y() >= 64 && position.y() <= 65
						? WalkabilityView.Cell.CLEAR
						: WalkabilityView.Cell.BLOCKED;
		for (int tick = 0; tick < 6; tick++) {
			ServerPathPlanner.TickBudget budget = new ServerPathPlanner.TickBudget(
					1,
					LocalPathfinder.MAX_PLANNING_TIME_NANOS,
					() -> 0L
			);
			ServerPathPlanner.PlanningResult admitted = planner.planPath(
					view,
					new GridPosition(0, 64, tick),
					new GridPosition(1, 64, tick),
					budget
			);
			ServerPathPlanner.PlanningResult deferred = planner.planPath(
					view,
					new GridPosition(0, 64, tick + 100),
					new GridPosition(1, 64, tick + 100),
					budget
			);
			assertEquals(PathOutcome.FOUND, admitted.plan().outcome(), "contention admits the first agent");
			assertTrue(deferred.deferred(), "contention defers the second agent without terminal failure");
		}
		return 12;
	}

	private static int verifyTickScopeRestoresPreviousBudget() {
		assertTrue(ServerPathPlanner.currentBudget() == null, "planner scope starts clear");
		ServerPathPlanner.TickScope scope = ServerPathPlanner.beginServerTick(
				1,
				LocalPathfinder.MAX_PLANNING_TIME_NANOS,
				() -> 0L
		);
		try {
			assertTrue(ServerPathPlanner.currentBudget() == scope.budget(),
					"nested navigation sees the current server tick budget");
		} finally {
			scope.close();
		}
		assertTrue(ServerPathPlanner.currentBudget() == null, "planner scope clears after the server tick");
		return 2;
	}

	private static void assertEquals(Object expected, Object actual, String message) {
		if (!expected.equals(actual)) {
			throw new AssertionError(message + " (expected=" + expected + ", actual=" + actual + ")");
		}
	}

	private static void assertTrue(boolean condition, String message) {
		if (!condition) {
			throw new AssertionError(message);
		}
	}
}
