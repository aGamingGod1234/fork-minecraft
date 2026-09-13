package dev.agaminggod.arenaagents.agent;

import java.util.Locale;
import java.util.Objects;
import java.util.UUID;

public record AgentId(UUID value) implements Comparable<AgentId> {
	public AgentId {
		Objects.requireNonNull(value, "value must not be null");
	}

	public static AgentId random() {
		return new AgentId(UUID.randomUUID());
	}

	public static AgentId parse(String value) {
		try {
			return new AgentId(UUID.fromString(Objects.requireNonNull(value, "value must not be null")));
		} catch (IllegalArgumentException exception) {
			throw new AgentDomainException("INVALID_AGENT_ID", "Invalid agent ID: " + value);
		}
	}

	public String shortValue() {
		return compactValue().substring(0, AgentConstants.SHORT_ID_LENGTH);
	}

	public String compactValue() {
		return value.toString().replace("-", "").toLowerCase(Locale.ROOT);
	}

	public boolean startsWith(String prefix) {
		if (prefix == null) {
			return false;
		}
		String compactPrefix = prefix.replace("-", "").toLowerCase(Locale.ROOT);
		return compactPrefix.length() >= AgentConstants.SHORT_ID_LENGTH && compactValue().startsWith(compactPrefix);
	}

	@Override
	public int compareTo(AgentId other) {
		return value.compareTo(other.value);
	}

	@Override
	public String toString() {
		return value.toString();
	}
}
