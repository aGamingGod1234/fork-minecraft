package dev.agaminggod.arenaagents.server.conversation;

import dev.agaminggod.arenaagents.agent.AgentDomainException;
import java.util.Locale;

public enum ConversationAudience {
	PUBLIC,
	DIRECT,
	PROXIMITY;

	public String wireName() {
		return name().toLowerCase(Locale.ROOT);
	}

	public static ConversationAudience parse(String value) {
		if (value == null || value.isBlank()) return PUBLIC;
		try {
			return valueOf(value.trim().toUpperCase(Locale.ROOT));
		} catch (IllegalArgumentException exception) {
			throw new AgentDomainException("INVALID_CONVERSATION_AUDIENCE", "Unknown conversation audience: " + value);
		}
	}
}
