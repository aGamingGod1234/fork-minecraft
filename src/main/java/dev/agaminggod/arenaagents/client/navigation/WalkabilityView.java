package dev.agaminggod.arenaagents.client.navigation;

import java.util.Objects;

@FunctionalInterface
public interface WalkabilityView {
	Cell cellAt(GridPosition position);

	default TraversalType traversalAt(GridPosition position) {
		if (isStandable(position)) return TraversalType.WALK;
		if (cellAt(position) == Cell.WATER && isBodyClear(position.above())) return TraversalType.SWIM;
		if (cellAt(position) == Cell.CLIMBABLE && isBodyClear(position.above())) return TraversalType.CLIMB;
		return null;
	}

	default boolean isBodyClear(GridPosition position) {
		Cell cell = cellAt(position);
		return cell == Cell.CLEAR || cell == Cell.CLIMBABLE;
	}

	default boolean isTraversable(GridPosition position) {
		return traversalAt(position) != null;
	}

	default boolean isStandable(GridPosition feetPosition) {
		Objects.requireNonNull(feetPosition, "feetPosition must not be null");
		return isBodyClear(feetPosition)
				&& isBodyClear(feetPosition.above())
				&& cellAt(feetPosition.below()) == Cell.SAFE_SUPPORT;
	}

	enum Cell {
		UNLOADED,
		CLEAR,
		WATER,
		CLIMBABLE,
		SAFE_SUPPORT,
		BLOCKED,
		HAZARD
	}
}
