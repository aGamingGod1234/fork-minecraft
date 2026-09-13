package dev.agaminggod.arenaagents.scenario;

import dev.agaminggod.arenaagents.scenario.runtime.ScenarioParkourCourse;
import java.util.ArrayList;
import java.util.List;

/** Roster-sized authored spawn stations shared by arena geometry and allocation. */
public final class ScenarioSpawnLayout {
	private static final int SLOT_COUNT = 16;

	private ScenarioSpawnLayout() {
	}

	public static List<Slot> slots(ScenarioCategory category) {
		return slots(category, SLOT_COUNT);
	}

	public static List<Slot> slots(ScenarioCategory category, int participantCount) {
		if (participantCount < 1 || participantCount > SLOT_COUNT) {
			throw new IllegalArgumentException("participant count must be in [1, 16]");
		}
		return switch (category) {
			case SURVIVAL -> radial(participantCount, scaledRadius(participantCount, 24, 54));
			case PVP -> radial(participantCount, scaledRadius(participantCount, 20, 32));
			case BUILDING -> buildingPlots(participantCount);
			case PARKOUR -> parkourStarts(participantCount);
		};
	}

	private static int scaledRadius(int count, int minimum, int maximum) {
		if (count == 1) return minimum;
		return minimum + (int) Math.round((maximum - minimum) * (count - 1) / 15.0D);
	}

	private static List<Slot> radial(int count, int radius) {
		ArrayList<Slot> slots = new ArrayList<>(count);
		for (int index = 0; index < count; index++) {
			double angle = -90.0D + 360.0D * index / count;
			double radians = Math.toRadians(angle);
			slots.add(new Slot(
					index,
					(int) Math.round(Math.cos(radians) * radius),
					0,
					(int) Math.round(Math.sin(radians) * radius),
					(float) normalizeYaw(angle + 180.0D)
			));
		}
		return List.copyOf(slots);
	}

	private static List<Slot> buildingPlots(int count) {
		if (count == 1) {
			return List.of(new Slot(0, 24, 0, 0, 180.0F));
		}
		ArrayList<Slot> slots = new ArrayList<>(count);
		int columns = (int) Math.ceil(Math.sqrt(count));
		int rows = (count + columns - 1) / columns;
		int index = 0;
		for (int row = 0; row < rows; row++) {
			int remaining = count - index;
			int rowColumns = Math.min(columns, remaining);
			int z = centeredCoordinate(row, rows, 24);
			for (int column = 0; column < rowColumns; column++) {
				int x = centeredCoordinate(column, rowColumns, 24);
				double angle = Math.toDegrees(Math.atan2(z, x));
				slots.add(new Slot(index++, x, 0, z, (float) normalizeYaw(angle + 180.0D)));
			}
		}
		return List.copyOf(slots);
	}

	private static int centeredCoordinate(int index, int count, int spacing) {
		return (int) Math.round((index - (count - 1) / 2.0D) * spacing);
	}

	private static List<Slot> parkourStarts(int count) {
		return ScenarioParkourCourse.create(count).lanes().stream()
				.map(lane -> {
					ScenarioParkourCourse.Platform start = lane.platforms().getFirst();
					return new Slot(lane.index(), (int) Math.floor(start.centerX()), start.y(), start.z(), 0.0F);
				})
				.toList();
	}

	private static double normalizeYaw(double angle) {
		double normalized = angle % 360.0D;
		return normalized > 180.0D ? normalized - 360.0D : normalized;
	}

	public record Slot(int index, int x, int floorY, int z, float yaw) {
		public Slot {
			if (index < 0 || index >= SLOT_COUNT) throw new IllegalArgumentException("spawn slot index is out of range");
		}

		public double spawnY() {
			return floorY + 1.0D;
		}
	}
}
