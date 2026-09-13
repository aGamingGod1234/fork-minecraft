package dev.agaminggod.arenaagents.scenario.runtime;

import java.util.Objects;

/** Pure checkpoint advancement and fall-recovery policy for one parkour lane. */
public final class ScenarioParkourRecovery {
	/** Recovery begins only after the contestant has actually died in the lava. */
	public static final double RECOVERY_PLANE_Y = -64.0D;
	private static final double CHECKPOINT_HORIZONTAL_RADIUS = 1.75D;
	private static final double CHECKPOINT_VERTICAL_RADIUS = 1.25D;

	private final ScenarioParkourCourse.Lane lane;

	public ScenarioParkourRecovery(ScenarioParkourCourse.Lane lane) {
		this.lane = Objects.requireNonNull(lane, "lane must not be null");
	}

	public Decision evaluate(int currentCheckpointIndex, double x, double y, double z) {
		if (!Double.isFinite(x) || !Double.isFinite(y) || !Double.isFinite(z)) {
			throw new IllegalArgumentException("parkour position must be finite");
		}
		if (!lane.checkpointIndices().contains(currentCheckpointIndex)) {
			throw new IllegalArgumentException("current checkpoint is not part of the lane");
		}
		int checkpointIndex = currentCheckpointIndex;
		for (int candidate : lane.checkpointIndices()) {
			if (candidate <= checkpointIndex) continue;
			ScenarioParkourCourse.Platform checkpoint = lane.platforms().get(candidate);
			if (Math.abs(x - checkpoint.centerX()) <= CHECKPOINT_HORIZONTAL_RADIUS
					&& Math.abs(y - checkpoint.standingY()) <= CHECKPOINT_VERTICAL_RADIUS
					&& Math.abs(z - checkpoint.centerZ()) <= CHECKPOINT_HORIZONTAL_RADIUS) {
				checkpointIndex = candidate;
			}
		}
		Target target = target(checkpointIndex);
		return new Decision(checkpointIndex, false, target);
	}

	public Target target(int checkpointIndex) {
		if (!lane.checkpointIndices().contains(checkpointIndex)) {
			throw new IllegalArgumentException("checkpoint is not part of the lane");
		}
		ScenarioParkourCourse.Platform checkpoint = lane.platforms().get(checkpointIndex);
		return new Target(checkpoint.centerX(), checkpoint.standingY(), checkpoint.centerZ());
	}

	public record Decision(int checkpointIndex, boolean recover, Target target) {
		public Decision {
			if (checkpointIndex < 0) throw new IllegalArgumentException("checkpoint index must not be negative");
			target = Objects.requireNonNull(target, "target must not be null");
		}
	}

	public record Target(double x, double y, double z) {
		public Target {
			if (!Double.isFinite(x) || !Double.isFinite(y) || !Double.isFinite(z)) {
				throw new IllegalArgumentException("recovery target must be finite");
			}
		}
	}
}
