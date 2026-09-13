package dev.agaminggod.arenaagents.server.conversation;

import java.util.Locale;

public enum ConversationKind {
	AGENT_MESSAGE,
	PLAYER_MESSAGE,
	PLAYER_STEER,
	PROXIMITY_SPEECH;

	public String wireName() {
		return name().toLowerCase(Locale.ROOT);
	}
}
