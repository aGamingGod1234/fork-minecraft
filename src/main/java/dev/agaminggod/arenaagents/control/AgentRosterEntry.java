package dev.agaminggod.arenaagents.control;

public record AgentRosterEntry(
		String id,
		String name,
		String provider,
		String modelLabel,
		String state,
		boolean selectable,
		String unavailableReason
) {
	public AgentRosterEntry {
		id = requiredId(id);
		name = normalized(name);
		provider = normalized(provider);
		modelLabel = normalized(modelLabel);
		state = normalized(state);
		unavailableReason = normalized(unavailableReason);
	}

	private static String requiredId(String value) {
		String normalized = normalized(value);
		if (normalized.isEmpty()) {
			throw new IllegalArgumentException("Agent roster ID must not be blank");
		}
		return normalized;
	}

	private static String normalized(String value) {
		return value == null ? "" : value.trim();
	}
}
