package dev.agaminggod.arenaagents.agent;

import java.util.Objects;

public record AgentTransition(
		AgentRecord before,
		AgentRecord after,
		boolean cancelAction,
		boolean interruptPlanner
) {
	public AgentTransition {
		Objects.requireNonNull(before, "before must not be null");
		Objects.requireNonNull(after, "after must not be null");
		if (!before.agentId().equals(after.agentId())) {
			throw new IllegalArgumentException("A transition cannot change the logical agent ID");
		}
	}
}
