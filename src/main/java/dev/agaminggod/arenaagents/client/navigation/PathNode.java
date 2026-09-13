package dev.agaminggod.arenaagents.client.navigation;

import java.util.Objects;

public record PathNode(GridPosition position, TraversalType traversal) {
	public PathNode {
		position = Objects.requireNonNull(position, "position must not be null");
		traversal = Objects.requireNonNull(traversal, "traversal must not be null");
	}
}
