package dev.agaminggod.arenaagents.agent;

import java.util.Locale;

public enum AgentGameMode {
	SURVIVAL,
	CREATIVE,
	ADVENTURE;

	public String wireName() {
		return name().toLowerCase(Locale.ROOT);
	}

	public String displayName() {
		String value = wireName();
		return value.substring(0, 1).toUpperCase(Locale.ROOT) + value.substring(1);
	}

	public static AgentGameMode parse(String value) {
		try {
			return valueOf(value.toUpperCase(Locale.ROOT));
		} catch (IllegalArgumentException | NullPointerException exception) {
			throw new AgentDomainException("INVALID_GAME_MODE", "Unknown agent game mode: " + value);
		}
	}
}
