package dev.agaminggod.arenaagents.client.presentation;

import java.util.Locale;
import java.util.Objects;

/** Pure presentation rules shared by the compact HUD and the full live-arena console. */
public final class ArenaHudPresentation {
	public static final int MAX_COMPACT_ROWS = 4;
	public static final int COMPACT_HEADER_HEIGHT = 27;
	public static final int COMPACT_ROW_HEIGHT = 22;
	public static final int COMPACT_OVERFLOW_HEIGHT = 11;
	public static final int COMPACT_BOTTOM_PADDING = 2;
	public static final int HEALTHY = 0xFF66D9A3;
	public static final int WOUNDED = 0xFFF2BD58;
	public static final int CRITICAL = 0xFFFF737A;

	private ArenaHudPresentation() {
	}

	/** The live world is the spectator surface; detailed agent state belongs in the G console. */
	public static boolean worldOverlayEnabled() {
		return false;
	}

	public static CompactRoster compactRoster(int participantCount) {
		if (participantCount < 0) throw new IllegalArgumentException("participantCount must not be negative");
		int visible = Math.min(MAX_COMPACT_ROWS, participantCount);
		return new CompactRoster(visible, participantCount - visible);
	}

	public static int compactHeight(int participantCount) {
		CompactRoster roster = compactRoster(participantCount);
		return COMPACT_HEADER_HEIGHT + roster.visibleCount() * COMPACT_ROW_HEIGHT
				+ (roster.overflowCount() > 0 ? COMPACT_OVERFLOW_HEIGHT : 0) + COMPACT_BOTTOM_PADDING;
	}

	public static int healthColor(int healthPercent) {
		if (healthPercent < 0 || healthPercent > 100) {
			throw new IllegalArgumentException("healthPercent must be between 0 and 100");
		}
		if (healthPercent <= 20) return CRITICAL;
		if (healthPercent <= 50) return WOUNDED;
		return HEALTHY;
	}

	public static String timeLabel(long elapsedTicks, long durationTicks, boolean terminal) {
		if (elapsedTicks < 0L || durationTicks <= 0L) throw new IllegalArgumentException("invalid arena time");
		long elapsedSeconds = Math.min(elapsedTicks, durationTicks) / 20L;
		long durationSeconds = durationTicks / 20L;
		if (terminal) {
			return "FINAL  " + clock(elapsedSeconds)
					+ (elapsedSeconds == durationSeconds ? "" : " / " + clock(durationSeconds));
		}
		return clock(elapsedSeconds) + " / " + clock(durationSeconds);
	}

	public static String statusLabel(String status) {
		String normalized = Objects.requireNonNull(status, "status must not be null")
				.strip().toLowerCase(Locale.ROOT).replace('_', ' ');
		if (normalized.isEmpty()) return "Unknown";
		return Character.toUpperCase(normalized.charAt(0)) + normalized.substring(1);
	}

	public static String providerLabel(String provider) {
		String normalized = Objects.requireNonNull(provider, "provider must not be null").strip();
		if (normalized.isEmpty()) return "Unknown provider";
		if (normalized.equalsIgnoreCase("antigravity")) return "Gemini";
		return Character.toUpperCase(normalized.charAt(0)) + normalized.substring(1).toLowerCase(Locale.ROOT);
	}

	private static String clock(long seconds) {
		return String.format(Locale.ROOT, "%02d:%02d", seconds / 60L, seconds % 60L);
	}

	public record CompactRoster(int visibleCount, int overflowCount) {
		public CompactRoster {
			if (visibleCount < 0 || overflowCount < 0) throw new IllegalArgumentException("roster counts must not be negative");
		}
	}
}
