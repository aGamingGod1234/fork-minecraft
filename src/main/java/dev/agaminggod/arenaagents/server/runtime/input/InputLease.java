package dev.agaminggod.arenaagents.server.runtime.input;

import dev.agaminggod.arenaagents.agent.AgentId;
import java.util.Objects;

public record InputLease(AgentId agentId, InputOwner owner, int priority, long sequence) {
	public InputLease {
		Objects.requireNonNull(agentId, "agentId must not be null");
		Objects.requireNonNull(owner, "owner must not be null");
		if (priority < 0) throw new IllegalArgumentException("priority must not be negative");
		if (sequence < 1L) throw new IllegalArgumentException("sequence must be positive");
	}
}
