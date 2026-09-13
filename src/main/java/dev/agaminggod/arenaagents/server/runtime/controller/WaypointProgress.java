package dev.agaminggod.arenaagents.server.runtime.controller;

import dev.agaminggod.arenaagents.server.runtime.ElapsedTimeAccumulator;

/**
 * Tracks material path progress without depending on Minecraft runtime types.
 */
public final class WaypointProgress {
	private static final double MATERIAL_PROGRESS = 0.1D;

	private double segmentStartDistance;
	private final long stallTimeoutMs;
	private final int maximumReplans;
	private double bestRemainingDistance;
	private ElapsedTimeAccumulator timeWithoutProgress;
	private int replans;

	public WaypointProgress(
			double initialDistance,
			long startedAtEpochMs,
			long stallTimeoutMs,
			int maximumReplans
	) {
		if (!Double.isFinite(initialDistance) || initialDistance < 0.0D) {
			throw new IllegalArgumentException("initialDistance must be finite and non-negative");
		}
		if (stallTimeoutMs <= 0L || maximumReplans < 0) {
			throw new IllegalArgumentException("stall timeout must be positive and replans non-negative");
		}
		this.segmentStartDistance = Math.max(MATERIAL_PROGRESS, initialDistance);
		this.bestRemainingDistance = initialDistance;
		this.timeWithoutProgress = new ElapsedTimeAccumulator(startedAtEpochMs);
		this.stallTimeoutMs = stallTimeoutMs;
		this.maximumReplans = maximumReplans;
	}

	public Update observe(double activeDistance, boolean waypointReached, long nowEpochMs) {
		if (!Double.isFinite(activeDistance) || activeDistance < 0.0D) {
			throw new IllegalArgumentException("activeDistance must be finite and non-negative");
		}
		if (activeDistance + MATERIAL_PROGRESS < bestRemainingDistance) {
			bestRemainingDistance = activeDistance;
			timeWithoutProgress = new ElapsedTimeAccumulator(nowEpochMs);
		}
		if (waypointReached) {
			timeWithoutProgress = new ElapsedTimeAccumulator(nowEpochMs);
		}
		long idleMs = timeWithoutProgress.advance(nowEpochMs);
		Decision decision = idleMs < stallTimeoutMs
				? Decision.CONTINUE
				: replans >= maximumReplans ? Decision.FAIL : Decision.REPLAN;
		double completed = 1.0D - (activeDistance / segmentStartDistance);
		double bounded = Math.max(0.0D, Math.min(1.0D, completed));
		return new Update(decision, waypointReached, bounded);
	}

	/** Starts a fresh stall window for the next active waypoint without consuming a replan. */
	public void waypointAdvanced(double activeDistance, long nowEpochMs) {
		if (!Double.isFinite(activeDistance) || activeDistance < 0.0D) {
			throw new IllegalArgumentException("activeDistance must be finite and non-negative");
		}
		segmentStartDistance = Math.max(MATERIAL_PROGRESS, activeDistance);
		bestRemainingDistance = activeDistance;
		timeWithoutProgress = new ElapsedTimeAccumulator(nowEpochMs);
	}

	public void replanned(double activeDistance, long nowEpochMs) {
		if (!Double.isFinite(activeDistance) || activeDistance < 0.0D) {
			throw new IllegalArgumentException("activeDistance must be finite and non-negative");
		}
		replans++;
		segmentStartDistance = Math.max(MATERIAL_PROGRESS, activeDistance);
		bestRemainingDistance = activeDistance;
		timeWithoutProgress = new ElapsedTimeAccumulator(nowEpochMs);
	}

	public enum Decision {
		CONTINUE,
		REPLAN,
		FAIL
	}

	public record Update(Decision decision, boolean advanceWaypoint, double progress) {
	}
}
