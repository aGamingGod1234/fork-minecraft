package dev.agaminggod.arenaagents.server.runtime;

final class ActionProgressEmissionPolicy {
	private final double materialDelta;
	private final long heartbeatMs;
	private boolean emitted;
	private double lastProgress;
	private ElapsedTimeAccumulator timeSinceEmission;

	ActionProgressEmissionPolicy(double materialDelta, long heartbeatMs) {
		if (!Double.isFinite(materialDelta) || materialDelta <= 0.0D || materialDelta > 1.0D || heartbeatMs <= 0L) {
			throw new IllegalArgumentException("invalid action progress emission policy");
		}
		this.materialDelta = materialDelta;
		this.heartbeatMs = heartbeatMs;
	}

	boolean shouldEmit(double progress, long nowEpochMs) {
		if (!Double.isFinite(progress) || progress < 0.0D || progress > 1.0D || nowEpochMs < 0L) {
			throw new IllegalArgumentException("invalid action progress sample");
		}
		long elapsedSinceEmission = emitted ? timeSinceEmission.advance(nowEpochMs) : 0L;
		boolean shouldEmit = !emitted
				|| progress >= lastProgress + materialDelta
				|| elapsedSinceEmission >= heartbeatMs;
		if (shouldEmit) {
			emitted = true;
			lastProgress = Math.max(lastProgress, progress);
			timeSinceEmission = new ElapsedTimeAccumulator(nowEpochMs);
		}
		return shouldEmit;
	}
}
