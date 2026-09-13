package dev.agaminggod.arenaagents.server.runtime;

final class ActionProgressTracker {
	private static final double MINIMUM_PROGRESS_BLOCKS = 0.1D;

	private final long stallTimeoutMs;
	private final double initialDistance;
	private double bestDistance;
	private ElapsedTimeAccumulator timeWithoutProgress;

	ActionProgressTracker(double initialDistance, long startedAtEpochMs, long stallTimeoutMs) {
		if (!Double.isFinite(initialDistance) || initialDistance < 0.0D) {
			throw new IllegalArgumentException("initialDistance must be finite and non-negative");
		}
		if (stallTimeoutMs <= 0L) {
			throw new IllegalArgumentException("stallTimeoutMs must be positive");
		}
		this.initialDistance = initialDistance;
		this.bestDistance = initialDistance;
		this.timeWithoutProgress = new ElapsedTimeAccumulator(startedAtEpochMs);
		this.stallTimeoutMs = stallTimeoutMs;
	}

	boolean stalled(double distance, long nowEpochMs) {
		if (!Double.isFinite(distance) || distance < 0.0D) {
			throw new IllegalArgumentException("distance must be finite and non-negative");
		}
		if (distance + MINIMUM_PROGRESS_BLOCKS < bestDistance) {
			bestDistance = distance;
			timeWithoutProgress = new ElapsedTimeAccumulator(nowEpochMs);
			return false;
		}
		return timeWithoutProgress.advance(nowEpochMs) >= stallTimeoutMs;
	}

	double progress(double distance) {
		if (!Double.isFinite(distance) || distance < 0.0D) {
			throw new IllegalArgumentException("distance must be finite and non-negative");
		}
		if (initialDistance == 0.0D) return distance == 0.0D ? 1.0D : 0.0D;
		return Math.max(0.0D, Math.min(1.0D, 1.0D - distance / initialDistance));
	}
}
