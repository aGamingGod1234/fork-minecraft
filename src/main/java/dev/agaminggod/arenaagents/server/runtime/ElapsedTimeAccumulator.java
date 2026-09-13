package dev.agaminggod.arenaagents.server.runtime;

/** Tracks elapsed duration across wall-clock rollback and saturates timestamp overflow. */
public final class ElapsedTimeAccumulator {
	private long previousTimeMs;
	private long elapsedMs;

	public ElapsedTimeAccumulator(long initialTimeMs) {
		this.previousTimeMs = initialTimeMs;
	}

	public long advance(long currentTimeMs) {
		long delta = positiveDifference(currentTimeMs, previousTimeMs);
		previousTimeMs = currentTimeMs;
		elapsedMs = saturatingAdd(elapsedMs, delta);
		return elapsedMs;
	}

	public long elapsedMs() {
		return elapsedMs;
	}

	static long positiveDifference(long current, long previous) {
		if (current <= previous) return 0L;
		try {
			return Math.subtractExact(current, previous);
		} catch (ArithmeticException overflow) {
			return Long.MAX_VALUE;
		}
	}

	private static long saturatingAdd(long left, long right) {
		return left > Long.MAX_VALUE - right ? Long.MAX_VALUE : left + right;
	}
}
