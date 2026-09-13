package dev.agaminggod.arenaagents.server.voice;

import dev.agaminggod.arenaagents.agent.AgentId;
import java.util.Objects;

public record VoiceRequest(
		AgentId agentId,
		String text,
		String profileId,
		int radius,
		long conversationSequence,
		double speed,
		String tone
) {
	public static final int MAX_TEXT_CODE_POINTS = 280;
	public static final int MAX_RADIUS = 128;
	public static final double DEFAULT_SPEED = 1.0D;
	public static final String DEFAULT_TONE = "neutral";

	/** Backwards-compatible request shape used by normal agent conversation. */
	public VoiceRequest(AgentId agentId, String text, String profileId, int radius, long conversationSequence) {
		this(agentId, text, profileId, radius, conversationSequence, DEFAULT_SPEED, DEFAULT_TONE);
	}

	public VoiceRequest {
		Objects.requireNonNull(agentId, "agentId must not be null");
		text = requireText(text, "text");
		profileId = requireText(profileId, "profileId");
		if (text.codePointCount(0, text.length()) > MAX_TEXT_CODE_POINTS) {
			throw new IllegalArgumentException("Voice text must be at most 280 Unicode code points");
		}
		if (radius < 1 || radius > MAX_RADIUS) throw new IllegalArgumentException("Voice radius must be between 1 and 128");
		if (conversationSequence < 0L) throw new IllegalArgumentException("Conversation sequence must not be negative");
		if (!Double.isFinite(speed) || speed < 0.5D || speed > 2.0D) {
			throw new IllegalArgumentException("Voice speed must be finite and between 0.5 and 2.0");
		}
		tone = requireToken(tone, "tone");
	}

	private static String requireText(String value, String name) {
		if (value == null || value.isBlank()) throw new IllegalArgumentException(name + " must not be blank");
		return value;
	}

	private static String requireToken(String value, String name) {
		String checked = requireText(value, name).strip();
		if (checked.length() > 32 || !checked.matches("[A-Za-z0-9_.-]+")) {
			throw new IllegalArgumentException(name + " must be a bounded command-safe token");
		}
		return checked;
	}
}
