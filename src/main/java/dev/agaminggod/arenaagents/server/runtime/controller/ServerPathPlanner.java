package dev.agaminggod.arenaagents.server.runtime.controller;

import dev.agaminggod.arenaagents.client.navigation.GridPosition;
import dev.agaminggod.arenaagents.client.navigation.LocalPathfinder;
import dev.agaminggod.arenaagents.client.navigation.PathOutcome;
import dev.agaminggod.arenaagents.client.navigation.PathPlan;
import dev.agaminggod.arenaagents.client.navigation.WalkabilityView;

import java.util.Objects;
import java.util.Set;
import java.util.function.LongSupplier;

/**
 * Common-server boundary for the deterministic, bounded local pathfinder.
 */
public final class ServerPathPlanner {
	public static final int MAX_EXPANDED_NODES_PER_TICK = LocalPathfinder.MAX_EXPANDED_NODES;
	public static final long MAX_PLANNING_TIME_NANOS_PER_TICK = LocalPathfinder.MAX_PLANNING_TIME_NANOS;

	private static final ThreadLocal<TickBudget> CURRENT_BUDGET = new ThreadLocal<>();
	private final LocalPathfinder pathfinder;

	public ServerPathPlanner() {
		this(new LocalPathfinder());
	}

	ServerPathPlanner(LocalPathfinder pathfinder) {
		this.pathfinder = Objects.requireNonNull(pathfinder, "pathfinder must not be null");
	}

	public PathPlan findPath(
			WalkabilityView view,
			GridPosition start,
			GridPosition destination
	) {
		return planPath(view, start, destination).plan();
	}

	/** Plans with the aggregate budget active for the current server tick, if any. */
	public PlanningResult planPath(
			WalkabilityView view,
			GridPosition start,
			GridPosition destination
	) {
		return planPath(view, start, destination, CURRENT_BUDGET.get());
	}

	/** Plans against an explicitly shared budget, useful for nested controller tests. */
	public PlanningResult planPath(
			WalkabilityView view,
			GridPosition start,
			GridPosition destination,
			TickBudget budget
	) {
		if (budget == null) {
			return new PlanningResult(pathfinder.findPath(view, start, destination), false);
		}
		return budget.plan(pathfinder, view, start, destination);
	}

	public static TickScope beginServerTick() {
		return beginServerTick(
				MAX_EXPANDED_NODES_PER_TICK,
				MAX_PLANNING_TIME_NANOS_PER_TICK,
				System::nanoTime
		);
	}

	public static TickScope beginServerTick(
			int maximumExpandedNodes,
			long timeBudgetNanos,
			LongSupplier monotonicClock
	) {
		TickBudget budget = new TickBudget(maximumExpandedNodes, timeBudgetNanos, monotonicClock);
		TickBudget previous = CURRENT_BUDGET.get();
		CURRENT_BUDGET.set(budget);
		return new TickScope(budget, previous);
	}

	public static TickBudget currentBudget() {
		return CURRENT_BUDGET.get();
	}

	public LocalPathfinder.Search beginSearch(GridPosition start, Set<GridPosition> goals,
			GridPosition destination, int radius, Set<GridPosition> previousFrontiers) {
		return pathfinder.beginSearch(start, goals, destination, radius, previousFrontiers);
	}

	public PlanningResult resume(LocalPathfinder.Search search, WalkabilityView view) {
		TickBudget tick = CURRENT_BUDGET.get();
		LocalPathfinder.SearchBudget budget = tick == null
				? new LocalPathfinder.SearchBudget(MAX_EXPANDED_NODES_PER_TICK, MAX_PLANNING_TIME_NANOS_PER_TICK, System::nanoTime)
				: tick.searchBudget;
		PathPlan result = search.advance(view, budget);
		return new PlanningResult(result, !search.finished());
	}

	public record PlanningResult(PathPlan plan, boolean deferred) {
		public PlanningResult {
			Objects.requireNonNull(plan, "plan must not be null");
		}

		public static PlanningResult deferred(PathPlan plan) {
			return new PlanningResult(plan, true);
		}
	}

	/** One mutable search budget shared by every path request in a server tick. */
	public static final class TickBudget {
		private final LocalPathfinder.SearchBudget searchBudget;

		public TickBudget(int maximumExpandedNodes, long timeBudgetNanos, LongSupplier monotonicClock) {
			this.searchBudget = new LocalPathfinder.SearchBudget(
					maximumExpandedNodes,
					timeBudgetNanos,
					monotonicClock
			);
		}

		public TickBudget(int maximumExpandedNodes, long timeBudgetNanos) {
			this(maximumExpandedNodes, timeBudgetNanos, System::nanoTime);
		}

		public int expandedNodes() {
			return searchBudget.expandedNodes();
		}

		public int maximumExpandedNodes() {
			return searchBudget.maximumExpandedNodes();
		}

		public long timeBudgetNanos() {
			return searchBudget.timeBudgetNanos();
		}

		public boolean exhausted() {
			return searchBudget.exhausted();
		}

		private PlanningResult plan(
				LocalPathfinder pathfinder,
				WalkabilityView view,
				GridPosition start,
				GridPosition destination
		) {
			PathPlan plan = pathfinder.findPath(view, start, destination, searchBudget);
			if (plan.outcome() == PathOutcome.INVALID
					|| (plan.outcome() == PathOutcome.FOUND
					&& plan.expandedNodes() == 0)) {
				return new PlanningResult(plan, false);
			}
			return searchBudget.exhausted() ? PlanningResult.deferred(plan) : new PlanningResult(plan, false);
		}
	}

	/** Installs a budget for nested navigation and restores the previous scope on close. */
	public static final class TickScope implements AutoCloseable {
		private final TickBudget budget;
		private final TickBudget previous;
		private boolean closed;

		private TickScope(TickBudget budget, TickBudget previous) {
			this.budget = budget;
			this.previous = previous;
		}

		public TickBudget budget() {
			return budget;
		}

		@Override
		public void close() {
			if (closed) return;
			closed = true;
			if (previous == null) CURRENT_BUDGET.remove();
			else CURRENT_BUDGET.set(previous);
		}
	}
}
