package dev.agaminggod.arenaagents.scenario.runtime.map;

import dev.agaminggod.arenaagents.scenario.runtime.ScenarioArenaBlueprint;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;

/** Bounded voxel walkability checks over authored blueprint data only. */
public final class ScenarioArenaReachability {
	private static final int MAXIMUM_VISITED_VOXELS = 2_359_296;
	private static final int[][] HORIZONTAL_DIRECTIONS = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}};

	private ScenarioArenaReachability() {
	}

	public static boolean canReachRequiredObjectives(ScenarioArenaBlueprint blueprint) {
		return unreachableRequiredObjectives(blueprint).isEmpty();
	}

	public static List<String> unreachableRequiredObjectives(ScenarioArenaBlueprint blueprint) {
		Objects.requireNonNull(blueprint, "blueprint must not be null");
		ScenarioArenaComposer.CompositionMetadata metadata = ScenarioArenaComposer.metadata(blueprint);
		if (metadata == null) throw new IllegalArgumentException("blueprint was not produced by the module composer");
		if (metadata.requiredObjectiveAnchorIds().isEmpty()) return List.of();

		Map<Long, BlockState> states = states(blueprint);
		List<BlockPos> spawns = metadata.anchors().stream()
				.filter(anchor -> anchor.type() == ScenarioArenaAnchor.Type.SPAWN)
				.map(ScenarioArenaAnchor::position)
				.filter(position -> isStandable(position, states))
				.toList();
		Map<String, BlockPos> objectives = new HashMap<>();
		for (ScenarioArenaAnchor anchor : metadata.anchors()) {
			if (metadata.requiredObjectiveAnchorIds().contains(anchor.id())) {
				objectives.put(anchor.id(), anchor.position());
			}
		}
		if (spawns.isEmpty()) return objectives.keySet().stream().sorted().toList();

		Bounds bounds = Bounds.of(blueprint, metadata.anchors());
		Set<BlockPos> reached = flood(spawns, states, bounds);
		ArrayList<String> unreachable = new ArrayList<>();
		objectives.entrySet().stream().sorted(Map.Entry.comparingByKey()).forEach(entry -> {
			if (!reachesObjective(reached, entry.getValue())) unreachable.add(entry.getKey());
		});
		return List.copyOf(unreachable);
	}

	static boolean isStandable(BlockPos feet, Map<Long, BlockState> states) {
		return isPassable(feet, states)
				&& isPassable(feet.above(), states)
				&& isFloor(states.get(feet.below().asLong()));
	}

	private static Set<BlockPos> flood(List<BlockPos> starts, Map<Long, BlockState> states, Bounds bounds) {
		ArrayDeque<BlockPos> queue = new ArrayDeque<>();
		HashSet<BlockPos> reached = new HashSet<>();
		for (BlockPos start : starts) {
			BlockPos immutable = start.immutable();
			if (bounds.contains(immutable) && reached.add(immutable)) queue.add(immutable);
		}
		while (!queue.isEmpty() && reached.size() < MAXIMUM_VISITED_VOXELS) {
			BlockPos current = queue.removeFirst();
			for (int[] direction : HORIZONTAL_DIRECTIONS) {
				for (int deltaY : new int[]{0, 1, -1}) {
					BlockPos candidate = current.offset(direction[0], deltaY, direction[1]);
					if (!bounds.contains(candidate) || reached.contains(candidate)
							|| !isStandable(candidate, states)) continue;
					reached.add(candidate.immutable());
					queue.addLast(candidate.immutable());
					break;
				}
			}
		}
		return reached;
	}

	private static boolean reachesObjective(Set<BlockPos> reached, BlockPos objective) {
		if (reached.contains(objective)) return true;
		for (int[] direction : HORIZONTAL_DIRECTIONS) {
			if (reached.contains(objective.offset(direction[0], 0, direction[1]))) return true;
		}
		return reached.contains(objective.above()) || reached.contains(objective.below());
	}

	private static boolean isPassable(BlockPos position, Map<Long, BlockState> states) {
		BlockState state = states.get(position.asLong());
		return state == null || state.isAir();
	}

	private static boolean isFloor(BlockState state) {
		return state != null && !state.isAir() && state.getFluidState().isEmpty();
	}

	private static Map<Long, BlockState> states(ScenarioArenaBlueprint blueprint) {
		HashMap<Long, BlockState> states = new HashMap<>();
		for (ScenarioArenaBlueprint.Placement placement : blueprint.placements()) {
			states.put(placement.position().asLong(), placement.state());
		}
		return states;
	}

	private record Bounds(int minX, int maxX, int minY, int maxY, int minZ, int maxZ) {
		private static Bounds of(ScenarioArenaBlueprint blueprint, List<ScenarioArenaAnchor> anchors) {
			ArrayList<BlockPos> positions = new ArrayList<>();
			blueprint.placements().forEach(placement -> positions.add(placement.position()));
			anchors.forEach(anchor -> positions.add(anchor.position()));
			if (positions.isEmpty()) return new Bounds(0, 0, 0, 0, 0, 0);
			int minX = positions.stream().min(Comparator.comparingInt((BlockPos position) -> position.getX())).orElseThrow().getX();
			int maxX = positions.stream().max(Comparator.comparingInt((BlockPos position) -> position.getX())).orElseThrow().getX();
			int minY = positions.stream().min(Comparator.comparingInt((BlockPos position) -> position.getY())).orElseThrow().getY() - 1;
			int maxY = positions.stream().max(Comparator.comparingInt((BlockPos position) -> position.getY())).orElseThrow().getY() + 1;
			int minZ = positions.stream().min(Comparator.comparingInt((BlockPos position) -> position.getZ())).orElseThrow().getZ();
			int maxZ = positions.stream().max(Comparator.comparingInt((BlockPos position) -> position.getZ())).orElseThrow().getZ();
			return new Bounds(minX, maxX, minY, maxY, minZ, maxZ);
		}

		private boolean contains(BlockPos position) {
			return position.getX() >= minX && position.getX() <= maxX
					&& position.getY() >= minY && position.getY() <= maxY
					&& position.getZ() >= minZ && position.getZ() <= maxZ;
		}
	}
}
