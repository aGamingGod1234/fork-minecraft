package dev.agaminggod.arenaagents.server.goal;

@FunctionalInterface
public interface GoalSpecRequestSink {
	void publish(PendingGoalDraft draft);
}
