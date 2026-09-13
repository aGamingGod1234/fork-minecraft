package dev.agaminggod.arenaagents.client.control;

/** Pure timing policy for the lightweight control snapshot heartbeat. */
public final class SnapshotRefreshPolicy {
	private SnapshotRefreshPolicy() {
	}

	public static Tick advance(int countdown, boolean connected, int intervalTicks) {
		return advance(countdown, connected, true, intervalTicks, intervalTicks);
	}

	public static Tick advance(
			int countdown,
			boolean connected,
			boolean controlsVisible,
			int visibleIntervalTicks,
			int hiddenIntervalTicks
	) {
		if (countdown < 0) throw new IllegalArgumentException("countdown must not be negative");
		if (visibleIntervalTicks <= 0) throw new IllegalArgumentException("visibleIntervalTicks must be positive");
		if (hiddenIntervalTicks < visibleIntervalTicks) {
			throw new IllegalArgumentException("hiddenIntervalTicks must be at least visibleIntervalTicks");
		}
		if (!connected) return new Tick(0, false);
		if (countdown == 0) {
			int interval = controlsVisible ? visibleIntervalTicks : hiddenIntervalTicks;
			return new Tick(interval - 1, true);
		}
		if (controlsVisible && countdown >= visibleIntervalTicks) return new Tick(0, false);
		return new Tick(countdown - 1, false);
	}

	public record Tick(int nextCountdown, boolean requestSnapshot) {
		public Tick {
			if (nextCountdown < 0) throw new IllegalArgumentException("nextCountdown must not be negative");
		}
	}
}
