package dev.agaminggod.arenaagents.client.navigation;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.PriorityQueue;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.function.LongSupplier;

public final class LocalPathfinder implements PathPlanner {
	public static final int MAX_EXPANDED_NODES = 8_192;
	public static final long MAX_PLANNING_TIME_NANOS = TimeUnit.MILLISECONDS.toNanos(40L);
	public static final int MAX_DROP_BLOCKS = 3;
	public static final int MAX_RETAINED_NODES = 32_768;
	public static final int MAX_EXPANDED_NODES_PER_SLICE = 512;

	private static final int WALK_COST = 10;
	private static final int JUMP_UP_COST = 14;
	private static final int JUMP_GAP_COST = 18;
	private static final int DROP_BASE_COST = 11;
	private static final int DROP_PER_BLOCK_COST = 1;
	private static final int HEIGHT_HEURISTIC_COST = 1;
	private static final int[][] CARDINAL_OFFSETS = {
		{1, 0},
		{0, 1},
		{-1, 0},
		{0, -1}
	};
	private static final Comparator<SearchNode> OPEN_ORDER = Comparator
			.comparingLong(SearchNode::estimatedTotalCost)
			.thenComparingLong(SearchNode::heuristicCost)
			.thenComparingLong(SearchNode::pathCost)
			.thenComparingLong(SearchNode::sequence)
			.thenComparingInt(node -> node.position().x())
			.thenComparingInt(node -> node.position().y())
			.thenComparingInt(node -> node.position().z());

	@Override
	public PathPlan findPath(
			WalkabilityView view,
			GridPosition start,
			GridPosition destination
	) {
		return findPath(
				view,
				start,
				destination,
				MAX_EXPANDED_NODES,
				MAX_PLANNING_TIME_NANOS,
				System::nanoTime
		);
	}

	public PathPlan findPath(
			WalkabilityView view,
			GridPosition start,
			GridPosition destination,
			int maximumExpandedNodes,
			long timeBudgetNanos,
			LongSupplier monotonicClock
	) {
		if (view == null || start == null || destination == null || monotonicClock == null
				|| maximumExpandedNodes <= 0 || timeBudgetNanos <= 0L) {
			return PathPlan.failed(PathOutcome.INVALID, 0);
		}
		return findPath(
				view,
				start,
				destination,
				new SearchBudget(maximumExpandedNodes, timeBudgetNanos, monotonicClock)
		);
	}

	/**
	 * Plans against a caller-owned budget. Reusing the budget across requests makes
	 * its expansion and deadline limits aggregate instead of multiplying per path.
	 */
	public PathPlan findPath(
			WalkabilityView view,
			GridPosition start,
			GridPosition destination,
			SearchBudget budget
	) {
		if (view == null || start == null || destination == null || budget == null) {
			return PathPlan.failed(PathOutcome.INVALID, 0);
		}
		if (!view.isTraversable(start) || !view.isTraversable(destination)) {
			return PathPlan.failed(PathOutcome.INVALID, 0);
		}
		if (start.equals(destination)) return new PathPlan(List.of(new PathNode(start, TraversalType.START)), PathOutcome.FOUND, 0);
		return new Search(start, Set.of(destination), destination, 0, Set.of()).advance(view, budget);
	}

	/** Retains one bounded search. The caller must discard it when its terrain snapshot changes. */
	public Search beginSearch(GridPosition start, Set<GridPosition> goals, GridPosition destination,
			int radius, Set<GridPosition> previousFrontiers) {
		if (radius < 1 || radius > 64) throw new IllegalArgumentException("local search radius must be 1..64");
		return new Search(start, goals, destination, radius, previousFrontiers);
	}

	public static final class Search {
		private final GridPosition start;
		private final Set<GridPosition> goals;
		private final GridPosition destination;
		private final int radius;
		private final Set<GridPosition> previousFrontiers;
		private final PriorityQueue<SearchNode> open = new PriorityQueue<>(OPEN_ORDER);
		private final Map<GridPosition, Long> bestCosts = new HashMap<>();
		private final Map<GridPosition, ParentEdge> parents = new HashMap<>();
		private final Set<GridPosition> closed = new HashSet<>();
		private SearchNode frontier;
		private long nextSequence;
		private boolean finished;

		private Search(GridPosition start, Set<GridPosition> goals, GridPosition destination,
				int radius, Set<GridPosition> previousFrontiers) {
			this.start = Objects.requireNonNull(start);
			this.goals = Set.copyOf(goals);
			this.destination = Objects.requireNonNull(destination);
			this.radius = radius;
			this.previousFrontiers = Set.copyOf(previousFrontiers);
			open.add(new SearchNode(start, 0L, heuristic(start, destination), nextSequence++));
			bestCosts.put(start, 0L);
		}

		public boolean finished() { return finished; }
		public int retainedNodes() { return bestCosts.size(); }

		public PathPlan advance(WalkabilityView view, SearchBudget budget) {
			if (finished) throw new IllegalStateException("search has already finished");
			int expandedAtStart = budget.expandedNodes();
			if (!view.isTraversable(start)) return finish(PathPlan.failed(PathOutcome.INVALID, 0));
			while (!open.isEmpty()) {
				if (budget.timeExceeded()) return PathPlan.failed(budget.exhaustionOutcome(), budget.expandedNodes() - expandedAtStart);
				if (radius > 0 && budget.expandedNodes() - expandedAtStart >= MAX_EXPANDED_NODES_PER_SLICE) {
					return PathPlan.failed(PathOutcome.NODE_LIMIT, budget.expandedNodes() - expandedAtStart);
				}
				SearchNode current = open.peek();
				Long currentBestCost = bestCosts.get(current.position());
				if (currentBestCost == null || current.pathCost() != currentBestCost || closed.contains(current.position())) {
					open.remove();
					continue;
				}
				if (goals.contains(current.position())) {
					return finish(reconstruct(start, current.position(), parents, budget.expandedNodes() - expandedAtStart));
				}
				if (!budget.tryExpand()) return PathPlan.failed(budget.exhaustionOutcome(), budget.expandedNodes() - expandedAtStart);
				open.remove();
				closed.add(current.position());
				if (radius > 0 && isFrontier(view, current.position())
						&& (frontier == null || current.heuristicCost() < frontier.heuristicCost()
						|| (current.heuristicCost() == frontier.heuristicCost() && current.pathCost() < frontier.pathCost()))) {
					frontier = current;
				}
				for (Neighbor neighbor : neighbors(view, current.position())) {
					if (closed.contains(neighbor.position()) || (radius > 0 && distanceFromStart(neighbor.position()) > radius)) continue;
					long candidateCost = saturatedAdd(current.pathCost(), neighbor.cost());
					long knownCost = bestCosts.getOrDefault(neighbor.position(), Long.MAX_VALUE);
					if (candidateCost >= knownCost) continue;
					if (!bestCosts.containsKey(neighbor.position()) && bestCosts.size() >= MAX_RETAINED_NODES) {
						return finish(frontierPlan(PathOutcome.NODE_LIMIT, budget.expandedNodes() - expandedAtStart));
					}
					bestCosts.put(neighbor.position(), candidateCost);
					parents.put(neighbor.position(), new ParentEdge(current.position(), neighbor.traversal()));
					open.add(new SearchNode(neighbor.position(), candidateCost,
							heuristic(neighbor.position(), destination), nextSequence++));
				}
			}
			return finish(frontierPlan(PathOutcome.NO_PATH, budget.expandedNodes() - expandedAtStart));
		}

		private int distanceFromStart(GridPosition position) {
			return (int) Math.min(Integer.MAX_VALUE, Math.max(absoluteDifference(start.x(), position.x()),
					Math.max(absoluteDifference(start.y(), position.y()), absoluteDifference(start.z(), position.z()))));
		}

		private boolean isFrontier(WalkabilityView view, GridPosition position) {
			int distance = distanceFromStart(position);
			if (distance < Math.min(8, radius) || previousFrontiers.contains(position)) return false;
			if (distance == radius) return true;
			for (int[] offset : CARDINAL_OFFSETS) {
				if (view.cellAt(position.offset(offset[0], 0, offset[1])) == WalkabilityView.Cell.UNLOADED) return true;
			}
			return false;
		}

		private PathPlan frontierPlan(PathOutcome failure, int expanded) {
			return frontier == null ? PathPlan.failed(failure, expanded) : reconstruct(start, frontier.position(), parents, expanded);
		}

		private PathPlan finish(PathPlan result) {
			finished = true;
			return result;
		}
	}

	private static List<Neighbor> neighbors(WalkabilityView view, GridPosition current) {
		List<Neighbor> neighbors = new ArrayList<>(CARDINAL_OFFSETS.length);
		if (view.cellAt(current) == WalkabilityView.Cell.CLIMBABLE) {
			for (int dy : new int[]{1, -1}) {
				GridPosition vertical = current.offset(0, dy, 0);
				if (view.isTraversable(vertical)) neighbors.add(new Neighbor(vertical, TraversalType.CLIMB, 16));
			}
		}
		for (int[] offset : CARDINAL_OFFSETS) {
			GridPosition sameLevel;
			try {
				sameLevel = current.offset(offset[0], 0, offset[1]);
			} catch (ArithmeticException exception) {
				continue;
			}
			Neighbor neighbor = resolveNeighbor(view, current, sameLevel);
			if (neighbor != null) {
				neighbors.add(neighbor);
			}
		}
		return neighbors;
	}

	private static Neighbor resolveNeighbor(
			WalkabilityView view,
			GridPosition current,
			GridPosition sameLevel
	) {
		TraversalType traversal = view.traversalAt(sameLevel);
		if (traversal != null) {
			return new Neighbor(sameLevel, traversal, traversal == TraversalType.WALK ? WALK_COST : 16);
		}

		GridPosition jumpDestination;
		try {
			jumpDestination = sameLevel.above();
		} catch (ArithmeticException exception) {
			return null;
		}
		if (isStandable(view, jumpDestination) && isClear(view, current.above(2))) {
			return new Neighbor(jumpDestination, TraversalType.JUMP_UP, JUMP_UP_COST);
		}

		GridPosition gapLanding;
		try {
			int dx = sameLevel.x() - current.x();
			int dz = sameLevel.z() - current.z();
			gapLanding = sameLevel.offset(dx, 0, dz);
		} catch (ArithmeticException exception) {
			return null;
		}
		if (isClear(view, sameLevel)
				&& isClear(view, sameLevel.above())
				&& isClear(view, current.above(2))
				&& isStandable(view, gapLanding)) {
			return new Neighbor(gapLanding, TraversalType.JUMP_GAP, JUMP_GAP_COST);
		}

		for (int drop = 1; drop <= MAX_DROP_BLOCKS; drop++) {
			GridPosition landing;
			try {
				landing = sameLevel.below(drop);
			} catch (ArithmeticException exception) {
				return null;
			}
			TraversalType landingTraversal = view.traversalAt(landing);
			if (landingTraversal == null) {
				continue;
			}
			if (isClearDropShaft(view, landing, current.y())) {
				return new Neighbor(
						landing,
						landingTraversal == TraversalType.SWIM || landingTraversal == TraversalType.CLIMB
								? landingTraversal : TraversalType.DROP_DOWN,
						DROP_BASE_COST + DROP_PER_BLOCK_COST * drop
				);
			}
		}
		return null;
	}

	private static boolean isClearDropShaft(
			WalkabilityView view,
			GridPosition landing,
			int sourceFeetY
	) {
		for (int y = landing.y(); y <= sourceFeetY + 1; y++) {
			if (!isClear(view, new GridPosition(landing.x(), y, landing.z()))) {
				return false;
			}
		}
		return true;
	}

	private static boolean isStandable(WalkabilityView view, GridPosition position) {
		try {
			return view.isStandable(position);
		} catch (ArithmeticException exception) {
			return false;
		}
	}

	private static boolean isClear(WalkabilityView view, GridPosition position) {
		return view.isBodyClear(position) || view.cellAt(position) == WalkabilityView.Cell.WATER;
	}

	private static long heuristic(GridPosition position, GridPosition destination) {
		long horizontalDistance = absoluteDifference(position.x(), destination.x())
				+ absoluteDifference(position.z(), destination.z());
		long heightDistance = absoluteDifference(position.y(), destination.y());
		return saturatedAdd(
				saturatedMultiply(horizontalDistance, WALK_COST),
				saturatedMultiply(heightDistance, HEIGHT_HEURISTIC_COST)
		);
	}

	private static PathPlan reconstruct(
			GridPosition start,
			GridPosition destination,
			Map<GridPosition, ParentEdge> parents,
			int expandedNodes
	) {
		LinkedList<PathNode> nodes = new LinkedList<>();
		Set<GridPosition> visited = new HashSet<>();
		GridPosition current = destination;
		while (!current.equals(start)) {
			if (!visited.add(current)) {
				return PathPlan.failed(PathOutcome.INVALID, expandedNodes);
			}
			ParentEdge edge = parents.get(current);
			if (edge == null) {
				return PathPlan.failed(PathOutcome.INVALID, expandedNodes);
			}
			nodes.addFirst(new PathNode(current, edge.traversal()));
			current = edge.parent();
		}
		nodes.addFirst(new PathNode(start, TraversalType.START));
		return new PathPlan(nodes, PathOutcome.FOUND, expandedNodes);
	}

	private static long absoluteDifference(int first, int second) {
		return Math.abs((long) first - second);
	}

	private static long saturatedAdd(long first, long second) {
		try {
			return Math.addExact(first, second);
		} catch (ArithmeticException exception) {
			return Long.MAX_VALUE;
		}
	}

	private static long saturatedMultiply(long value, long multiplier) {
		try {
			return Math.multiplyExact(value, multiplier);
		} catch (ArithmeticException exception) {
			return Long.MAX_VALUE;
		}
	}

	private record SearchNode(
			GridPosition position,
			long pathCost,
			long heuristicCost,
			long sequence
	) {
		private SearchNode {
			Objects.requireNonNull(position, "position must not be null");
		}

		private long estimatedTotalCost() {
			return saturatedAdd(pathCost, heuristicCost);
		}
	}

	private record ParentEdge(GridPosition parent, TraversalType traversal) {
	}

	private record Neighbor(GridPosition position, TraversalType traversal, int cost) {
	}

	/** Mutable limits shared by all path requests admitted during one server tick. */
	public static final class SearchBudget {
		private final int maximumExpandedNodes;
		private final long timeBudgetNanos;
		private final LongSupplier monotonicClock;
		private final long startedAtNanos;
		private int expandedNodes;
		private boolean exhausted;
		private PathOutcome exhaustionOutcome;

		public SearchBudget(int maximumExpandedNodes, long timeBudgetNanos, LongSupplier monotonicClock) {
			if (maximumExpandedNodes <= 0 || timeBudgetNanos <= 0L || monotonicClock == null) {
				throw new IllegalArgumentException("path search budget must be positive");
			}
			this.maximumExpandedNodes = maximumExpandedNodes;
			this.timeBudgetNanos = timeBudgetNanos;
			this.monotonicClock = monotonicClock;
			this.startedAtNanos = monotonicClock.getAsLong();
		}

		public int maximumExpandedNodes() {
			return maximumExpandedNodes;
		}

		public long timeBudgetNanos() {
			return timeBudgetNanos;
		}

		public int expandedNodes() {
			return expandedNodes;
		}

		public boolean exhausted() {
			return exhausted;
		}

		public PathOutcome exhaustionOutcome() {
			if (!exhausted || exhaustionOutcome == null) {
				throw new IllegalStateException("path search budget is not exhausted");
			}
			return exhaustionOutcome;
		}

		private boolean timeExceeded() {
			if (exhausted) return exhaustionOutcome == PathOutcome.TIME_LIMIT;
			if (monotonicClock.getAsLong() - startedAtNanos < timeBudgetNanos) return false;
			exhausted = true;
			exhaustionOutcome = PathOutcome.TIME_LIMIT;
			return true;
		}

		private boolean tryExpand() {
			if (timeExceeded()) return false;
			if (expandedNodes >= maximumExpandedNodes) {
				exhausted = true;
				exhaustionOutcome = PathOutcome.NODE_LIMIT;
				return false;
			}
			expandedNodes++;
			return true;
		}
	}
}
