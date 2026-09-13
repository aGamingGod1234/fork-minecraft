package dev.agaminggod.arenaagents.client.navigation;

@FunctionalInterface
public interface PathPlanner {
	PathPlan findPath(WalkabilityView view, GridPosition start, GridPosition destination);
}
