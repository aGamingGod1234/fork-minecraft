package dev.agaminggod.arenaagents.server.voice;

import java.util.Locale;
import java.util.Objects;

/** Persistent voice settings for one skit agent. */
public record VoiceProfile(String profileId, String tone, double speed, int radius) {
	public static final String DEFAULT_PROFILE_ID = "voice.auto.v1";
	public static final String DEFAULT_TONE = "neutral";
	public static final double DEFAULT_SPEED = 1.0D;
	public static final int DEFAULT_RADIUS = 48;

	public VoiceProfile(String profileId) {
		this(profileId, DEFAULT_TONE, DEFAULT_SPEED, DEFAULT_RADIUS);
	}

	public static VoiceProfile defaults() {
		return new VoiceProfile(DEFAULT_PROFILE_ID, DEFAULT_TONE, DEFAULT_SPEED, DEFAULT_RADIUS);
	}

	public VoiceProfile {
		profileId = requireToken(profileId, "profileId", 128);
		tone = requireToken(tone, "tone", 32).toLowerCase(Locale.ROOT);
		if (!Double.isFinite(speed) || speed < 0.5D || speed > 2.0D) {
			throw new IllegalArgumentException("speed must be finite and between 0.5 and 2.0");
		}
		if (radius < 1 || radius > 128) throw new IllegalArgumentException("radius must be between 1 and 128");
	}

	private static String requireToken(String value, String field, int maxLength) {
		Objects.requireNonNull(value, field + " must not be null");
		String checked = value.strip();
		if (checked.isEmpty() || checked.length() > maxLength || !checked.matches("[A-Za-z0-9_.-]+")) {
			throw new IllegalArgumentException(field + " must be a bounded command-safe token");
		}
		return checked;
	}
}
