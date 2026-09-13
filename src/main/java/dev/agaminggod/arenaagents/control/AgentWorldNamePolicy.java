package dev.agaminggod.arenaagents.control;

import java.util.Objects;
import java.util.Optional;

/** Pure friendly-name policy for snapshot-authoritative agent world tags. */
public final class AgentWorldNamePolicy {
	private AgentWorldNamePolicy() {
	}

	public static Optional<String> tag(AgentControlAgent agent) {
		Objects.requireNonNull(agent, "agent must not be null");
		String displayName = agent.displayName();
		return displayName.isBlank() ? Optional.empty() : Optional.of(displayName);
	}
}
