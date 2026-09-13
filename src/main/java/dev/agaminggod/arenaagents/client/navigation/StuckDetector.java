package dev.agaminggod.arenaagents.client.navigation;

public final class StuckDetector {
	public static final double MIN_PROGRESS_DISTANCE = 0.1D;
	public static final long NO_PROGRESS_WINDOW_MS = 2_500L;
	public static final int MAX_RECOVERY_ATTEMPTS = 3;

	private static final double MIN_PROGRESS_DISTANCE_SQUARED =
			MIN_PROGRESS_DISTANCE * MIN_PROGRESS_DISTANCE;

	private double anchorX;
	private double anchorY;
	private double anchorZ;
	private long windowStartedAtMs;
	private int recoveryAttempts;
	private boolean initialized;

	public void reset(double x, double y, double z, long nowMs) {
		setAnchor(x, y, z, nowMs);
		recoveryAttempts = 0;
		initialized = true;
	}

	public void recoveryStarted(double x, double y, double z, long nowMs) {
		requireInitialized();
		setAnchor(x, y, z, nowMs);
	}

	public Outcome observe(double x, double y, double z, long nowMs) {
		requireFinite(x, "x");
		requireFinite(y, "y");
		requireFinite(z, "z");
		if (!initialized) {
			reset(x, y, z, nowMs);
			return Outcome.MONITORING;
		}

		double xDifference = x - anchorX;
		double yDifference = y - anchorY;
		double zDifference = z - anchorZ;
		double displacementSquared = xDifference * xDifference
				+ yDifference * yDifference
				+ zDifference * zDifference;
		if (displacementSquared >= MIN_PROGRESS_DISTANCE_SQUARED) {
			reset(x, y, z, nowMs);
			return Outcome.MONITORING;
		}
		if (nonNegativeElapsed(windowStartedAtMs, nowMs) < NO_PROGRESS_WINDOW_MS) {
			return Outcome.MONITORING;
		}
		if (recoveryAttempts >= MAX_RECOVERY_ATTEMPTS) {
			return Outcome.FAILED;
		}
		recoveryAttempts++;
		windowStartedAtMs = nowMs;
		return Outcome.RECOVER;
	}

	public int recoveryAttempts() {
		return recoveryAttempts;
	}

	private void setAnchor(double x, double y, double z, long nowMs) {
		requireFinite(x, "x");
		requireFinite(y, "y");
		requireFinite(z, "z");
		anchorX = x;
		anchorY = y;
		anchorZ = z;
		windowStartedAtMs = nowMs;
	}

	private void requireInitialized() {
		if (!initialized) {
			throw new IllegalStateException("stuck detector is not initialized");
		}
	}

	private static long nonNegativeElapsed(long startedAtMs, long nowMs) {
		if (nowMs <= startedAtMs) {
			return 0L;
		}
		try {
			return Math.subtractExact(nowMs, startedAtMs);
		} catch (ArithmeticException exception) {
			return Long.MAX_VALUE;
		}
	}

	private static void requireFinite(double value, String field) {
		if (!Double.isFinite(value)) {
			throw new IllegalArgumentException(field + " must be finite");
		}
	}

	public enum Outcome {
		MONITORING,
		RECOVER,
		FAILED
	}
}
