package dev.agaminggod.arenaagents.scenario.runtime;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/** Deterministic parallel parkour lanes with four progressively harder movement grammars. */
public record ScenarioParkourCourse(List<Lane> lanes) {
	public static final int MAXIMUM_LANES = 16;
	/** Leaves five blocks of lava between three-wide lanes, beyond a normal player jump. */
	public static final int LANE_PITCH = 8;
	public static final int START_Z = -40;
	public static final int TRANSITIONS_PER_STAGE = 8;
	public static final int PLATFORM_COUNT = 33;

	/*
	 * Movement motifs adapted from the checkpoint-to-checkpoint pacing of New Year
	 * Parkour (BlastersTNT, DreamLiner, Menae, CC 4.0). The original authored map
	 * uses snow banks, ice, trees, caves, slime and precision sections; these compact
	 * motifs retain that authored rhythm while remaining reachable by the controller.
	 */
	private static final List<List<Step>> STAGE_STEPS = List.of(
			List.of(
					step(0, 0, 3, 3), step(0, 0, 2, 3), step(0, 1, 1, 3), step(0, 0, 2, 2),
					step(0, 1, 1, 3), step(0, 0, 2, 2), step(0, 0, 2, 1), step(0, 0, 2, 3)
			),
			List.of(
					step(0, 1, 1, 2), step(0, 0, 2, 2), step(0, 1, 1, 1), step(0, 0, 2, 2),
					step(1, 0, 1, 1), step(0, 1, 1, 2), step(0, 0, 2, 1), step(0, 0, 2, 3)
			),
			List.of(
					step(0, 1, 1, 1), step(0, 0, 2, 1), step(0, 1, 1, 1), step(0, 0, 2, 1),
					step(0, 1, 1, 1), step(0, 0, 2, 1), step(0, 1, 1, 1), step(0, 0, 2, 3)
			),
			List.of(
					step(0, 1, 1, 1), step(0, 0, 2, 1), step(0, 1, 1, 1), step(0, 0, 1, 1),
					step(0, 1, 1, 1), step(0, 0, 2, 1), step(0, 1, 1, 1), step(-1, 0, 2, 3)
			)
	);

	public ScenarioParkourCourse {
		lanes = List.copyOf(Objects.requireNonNull(lanes, "lanes must not be null"));
		if (lanes.isEmpty() || lanes.size() > MAXIMUM_LANES) {
			throw new IllegalArgumentException("parkour course must have between one and sixteen lanes");
		}
	}

	public static ScenarioParkourCourse create() {
		return create(MAXIMUM_LANES);
	}

	public static ScenarioParkourCourse create(int laneCount) {
		if (laneCount < 1 || laneCount > MAXIMUM_LANES) {
			throw new IllegalArgumentException("parkour lane count must be in [1, 16]");
		}
		ArrayList<Lane> lanes = new ArrayList<>(laneCount);
		int firstX = -((laneCount - 1) * LANE_PITCH / 2) - 1;
		for (int laneIndex = 0; laneIndex < laneCount; laneIndex++) {
			int laneX = firstX + laneIndex * LANE_PITCH;
			int x = laneX;
			int y = 1;
			int z = START_Z;
			ArrayList<Platform> platforms = new ArrayList<>(PLATFORM_COUNT);
			platforms.add(new Platform(x, y, z, Stage.EASY, true, 3));
			for (int transition = 1; transition < PLATFORM_COUNT; transition++) {
				int stageIndex = (transition - 1) / TRANSITIONS_PER_STAGE;
				int withinStage = (transition - 1) % TRANSITIONS_PER_STAGE;
				Stage stage = Stage.values()[stageIndex];
				Step step = STAGE_STEPS.get(stageIndex).get(withinStage);
				x += step.dx();
				y += step.dy();
				z += step.dz();
				boolean checkpoint = transition % TRANSITIONS_PER_STAGE == 0;
				platforms.add(new Platform(
						x, y, z, stage, checkpoint, checkpoint ? 3 : step.width()
				));
			}
			lanes.add(new Lane(laneIndex, platforms));
		}
		return new ScenarioParkourCourse(lanes);
	}

	private static Step step(int dx, int dy, int dz, int width) {
		return new Step(dx, dy, dz, width);
	}

	public enum Stage {
		EASY("Easy"),
		MEDIUM("Medium"),
		HARD("Hard"),
		EXTREME("Extreme");

		private final String displayName;

		Stage(String displayName) {
			this.displayName = displayName;
		}

		public String displayName() {
			return displayName;
		}
	}

	public record Lane(int index, List<Platform> platforms) {
		public Lane {
			if (index < 0 || index >= MAXIMUM_LANES) {
				throw new IllegalArgumentException("parkour lane index must be in [0, 15]");
			}
			platforms = List.copyOf(Objects.requireNonNull(platforms, "platforms must not be null"));
			if (platforms.size() != PLATFORM_COUNT) {
				throw new IllegalArgumentException("parkour lane must have exactly " + PLATFORM_COUNT + " platforms");
			}
			if (!platforms.getFirst().checkpoint() || !platforms.getLast().checkpoint()) {
				throw new IllegalArgumentException("parkour lane must start and finish at checkpoints");
			}
		}

		public List<Integer> checkpointIndices() {
			ArrayList<Integer> result = new ArrayList<>();
			for (int index = 0; index < platforms.size(); index++) {
				if (platforms.get(index).checkpoint()) result.add(index);
			}
			return List.copyOf(result);
		}

		public String transitionSignature(Stage stage) {
			Objects.requireNonNull(stage, "stage must not be null");
			StringBuilder signature = new StringBuilder();
			for (int index = 1; index < platforms.size(); index++) {
				Platform current = platforms.get(index);
				if (current.stage() != stage) continue;
				Platform previous = platforms.get(index - 1);
				if (!signature.isEmpty()) signature.append('|');
				signature.append(current.x() - previous.x()).append(',')
						.append(current.y() - previous.y()).append(',')
						.append(current.z() - previous.z()).append(',')
						.append(current.width());
			}
			return signature.toString();
		}

		public boolean transitionsReachable() {
			for (int index = 1; index < platforms.size(); index++) {
				Platform previous = platforms.get(index - 1);
				Platform next = platforms.get(index);
				int dx = Math.abs(next.x() - previous.x());
				int dy = next.y() - previous.y();
				int dz = next.z() - previous.z();
				if (index == 1 && dx == 0 && dy == 0 && dz == 3) continue;
				if (dx > 1 || dy < -1 || dy > 1 || dz < 1 || dz > 2) return false;
				if (dy > 0 && dz > 1) return false;
				if (dy < 0 && dz > 1) return false;
				if (dy != 0 && dx != 0) return false;
			}
			return true;
		}
	}

	public record Platform(
			int x,
			int y,
			int z,
			Stage stage,
			boolean checkpoint,
			int width
	) {
		public Platform {
			stage = Objects.requireNonNull(stage, "stage must not be null");
			if (width < 1 || width > 3) throw new IllegalArgumentException("platform width must be in [1, 3]");
		}

		public Platform(int x, int y, int z) {
			this(x, y, z, Stage.EASY, false, 2);
		}

		public double centerX() {
			return x + width / 2.0D;
		}

		public double standingY() {
			return y + 1.0D;
		}

		public double centerZ() {
			return z + 0.5D;
		}
	}

	private record Step(int dx, int dy, int dz, int width) {
		private Step {
			if (Math.abs(dx) > 1 || dy < -1 || dy > 1 || dz < 1 || dz > 3 || width < 1 || width > 3) {
				throw new IllegalArgumentException("parkour transition is outside the authored envelope");
			}
		}
	}
}
