package dev.agaminggod.arenaagents.server.conversation;

import dev.agaminggod.arenaagents.agent.goal.GoalSpec;
import java.util.Optional;

@FunctionalInterface
public interface ConversationEventSink {
	void publish(ConversationEvent event, Optional<GoalSpec> wakeGoal);
}
