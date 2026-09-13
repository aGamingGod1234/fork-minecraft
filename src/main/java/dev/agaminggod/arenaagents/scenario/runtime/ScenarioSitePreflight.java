package dev.agaminggod.arenaagents.scenario.runtime;

import java.util.Objects;

/** Pure destructive-site gate used before a reset can own or mutate a world area. */
public final class ScenarioSitePreflight {
	private ScenarioSitePreflight() {
	}

	public static Verdict assess(Input input) {
		Objects.requireNonNull(input, "input must not be null");
		ScenarioArenaBlueprint.SiteBounds bounds = input.bounds();
		if (bounds.clearFloorY() < input.minimumBuildY()
				|| input.maximumAuthoredY() >= input.maximumBuildY()) {
			return Verdict.blocked("SITE_HEIGHT_OUT_OF_RANGE");
		}
		if (!input.withinWorldBorder()) return Verdict.blocked("SITE_OUTSIDE_WORLD_BORDER");
		long destructiveCells = Math.multiplyExact(
				(long) bounds.columnCount(),
				Math.max(0L, (long) input.maximumBuildY() - bounds.clearFloorY() - 1L));
		if (destructiveCells > ScenarioBuildProgressLimit.MAXIMUM_DESTRUCTIVE_CELLS) {
			return Verdict.blocked("SITE_DESTRUCTION_LIMIT");
		}
		return new Verdict(true, "CONFIRMATION_REQUIRED", destructiveCells, input.occupantCount());
	}

	public record Input(
			ScenarioArenaBlueprint.SiteBounds bounds,
			int minimumBuildY,
			int maximumBuildY,
			int maximumAuthoredY,
			boolean withinWorldBorder,
			int occupantCount
	) {
		public Input {
			bounds = Objects.requireNonNull(bounds, "bounds must not be null");
			if (minimumBuildY >= maximumBuildY || occupantCount < 0) {
				throw new IllegalArgumentException("site preflight input is invalid");
			}
		}
	}

	public record Verdict(boolean allowedWithConfirmation, String code, long destructiveCells, int occupantCount) {
		public Verdict {
			code = Objects.requireNonNull(code, "code must not be null");
			if (code.isBlank() || destructiveCells < 0L || occupantCount < 0) {
				throw new IllegalArgumentException("site preflight verdict is invalid");
			}
		}

		private static Verdict blocked(String code) { return new Verdict(false, code, 0L, 0); }
	}

	private static final class ScenarioBuildProgressLimit {
		private static final long MAXIMUM_DESTRUCTIVE_CELLS = 16_000_000L;
	}
}
