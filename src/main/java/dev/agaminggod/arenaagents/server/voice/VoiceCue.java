package dev.agaminggod.arenaagents.server.voice;

import java.util.Objects;

/** One delayed line in a skit voice timeline. Empty overrides inherit the agent profile. */
public record VoiceCue(int delayTicks, String text, String profileId, String tone, double speed, int radius) {
	public static final int MAX_DELAY_TICKS = 20 * 60 * 60;

	public VoiceCue(int delayTicks, String text) {
		this(delayTicks, text, "", "", -1.0D, 0);
	}

	public VoiceCue {
		if (delayTicks < 0 || delayTicks > MAX_DELAY_TICKS) {
			throw new IllegalArgumentException("delayTicks must be between 0 and " + MAX_DELAY_TICKS);
		}
		text = Objects.requireNonNull(text, "text must not be null").strip();
		if (text.isEmpty() || text.codePointCount(0, text.length()) > VoiceRequest.MAX_TEXT_CODE_POINTS) {
			throw new IllegalArgumentException("text must contain 1 to 280 Unicode code points");
		}
		profileId = requireOptionalToken(profileId, "profileId", 128);
		tone = requireOptionalToken(tone, "tone", 32);
		if (!Double.isFinite(speed) || (speed != -1.0D && (speed < 0.5D || speed > 2.0D))) {
			throw new IllegalArgumentException("speed must be -1 or finite and between 0.5 and 2.0");
		}
		if (radius < 0 || radius > VoiceRequest.MAX_RADIUS) {
			throw new IllegalArgumentException("radius must be 0 or between 1 and 128");
		}
	}

	private static String requireOptionalToken(String value, String field, int maxLength) {
		Objects.requireNonNull(value, field + " must not be null");
		String checked = value.strip();
		if (checked.length() > maxLength || (!checked.isEmpty() && !checked.matches("[A-Za-z0-9_.-]+"))) {
			throw new IllegalArgumentException(field + " must be empty or a bounded command-safe token");
		}
		return checked;
	}
}
