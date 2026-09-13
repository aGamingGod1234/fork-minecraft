package dev.agaminggod.arenaagents.client.navigation;

import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

public record PathPlan(List<PathNode> nodes, PathOutcome outcome, int expandedNodes) {
	public PathPlan {
		nodes = List.copyOf(Objects.requireNonNull(nodes, "nodes must not be null"));
		outcome = Objects.requireNonNull(outcome, "outcome must not be null");
		if (expandedNodes < 0) {
			throw new IllegalArgumentException("expandedNodes must not be negative");
		}
		if (outcome == PathOutcome.FOUND) {
			validateFoundPath(nodes);
		} else if (!nodes.isEmpty()) {
			throw new IllegalArgumentException("non-found path plans must not contain nodes");
		}
	}

	public static PathPlan failed(PathOutcome outcome, int expandedNodes) {
		if (outcome == PathOutcome.FOUND) {
			throw new IllegalArgumentException("failed plan outcome must not be FOUND");
		}
		return new PathPlan(List.of(), outcome, expandedNodes);
	}

	private static void validateFoundPath(List<PathNode> nodes) {
		if (nodes.isEmpty()) {
			throw new IllegalArgumentException("found path plans must contain at least one node");
		}
		if (nodes.getFirst().traversal() != TraversalType.START) {
			throw new IllegalArgumentException("the first path node must use START traversal");
		}
		Set<GridPosition> positions = new HashSet<>(nodes.size());
		for (int index = 0; index < nodes.size(); index++) {
			PathNode node = nodes.get(index);
			if (index > 0 && node.traversal() == TraversalType.START) {
				throw new IllegalArgumentException("only the first path node may use START traversal");
			}
			if (!positions.add(node.position())) {
				throw new IllegalArgumentException("path must not contain a cycle");
			}
		}
	}
}
