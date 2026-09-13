package dev.agaminggod.arenaagents.scenario.runtime.map;

import dev.agaminggod.arenaagents.scenario.runtime.ScenarioArenaBlueprint;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.level.block.state.BlockState;

/** Exact preflight budgets and semantic safety checks for composed arenas. */
public final class ScenarioArenaSafetyPolicy {
	public static final int MAXIMUM_FINDINGS = 256;
	private static final int MAXIMUM_X_SPAN = 192;
	private static final int MAXIMUM_Y_SPAN = 64;
	private static final int MAXIMUM_Z_SPAN = 192;
	private static final int MAXIMUM_PLACEMENTS = 400_000;
	private static final long MAXIMUM_CLEAR_CELLS = 2_359_296L;
	private static final int MAXIMUM_LOADED_CHUNKS = 196;
	private static final int MAXIMUM_FLUIDS = 32_768;
	private static final int MAXIMUM_CONTAINERS = 64;
	private static final long MINIMUM_SPECTATOR_DISTANCE_SQUARED = 12L * 12L;
	private static final Set<String> PROHIBITED_BLOCKS = Set.of(
			"minecraft:command_block", "minecraft:repeating_command_block", "minecraft:chain_command_block",
			"minecraft:structure_block", "minecraft:jigsaw");
	private static final Set<String> CONTAINER_BLOCKS = Set.of(
			"minecraft:barrel", "minecraft:chest", "minecraft:trapped_chest", "minecraft:dispenser",
			"minecraft:dropper", "minecraft:hopper", "minecraft:furnace", "minecraft:blast_furnace",
			"minecraft:smoker", "minecraft:brewing_stand", "minecraft:chiseled_bookshelf",
			"minecraft:decorated_pot");

	private ScenarioArenaSafetyPolicy() {
	}

	public static Report validate(ScenarioArenaBlueprint blueprint, int participantCount) {
		Objects.requireNonNull(blueprint, "blueprint must not be null");
		if (participantCount < 1 || participantCount > 16) {
			throw new IllegalArgumentException("participantCount must be 1..16");
		}
		Findings findings = new Findings();
		List<ScenarioArenaBlueprint.Placement> placements = blueprint.placements();
		ScenarioArenaComposer.CompositionMetadata metadata = ScenarioArenaComposer.metadata(blueprint);
		if (placements.size() > MAXIMUM_PLACEMENTS) {
			findings.add("PLACEMENT_CAP", "placement count " + placements.size() + " exceeds " + MAXIMUM_PLACEMENTS);
		}
		Bounds bounds = Bounds.of(blueprint.origin(), placements, metadata);
		if (bounds.xSpan() > MAXIMUM_X_SPAN || bounds.ySpan() > MAXIMUM_Y_SPAN
				|| bounds.zSpan() > MAXIMUM_Z_SPAN) {
			findings.add("FOOTPRINT_CAP", "arena span " + bounds.xSpan() + "x" + bounds.ySpan() + "x"
					+ bounds.zSpan() + " exceeds 192x64x192");
		}
		if (bounds.volume() > MAXIMUM_CLEAR_CELLS) {
			findings.add("CLEAR_VOLUME_CAP", "clear volume " + bounds.volume() + " exceeds " + MAXIMUM_CLEAR_CELLS);
		}

		HashSet<Long> chunks = new HashSet<>();
		HashMap<Long, BlockState> states = new HashMap<>();
		int fluids = 0;
		int containers = 0;
		for (ScenarioArenaBlueprint.Placement placement : placements) {
			BlockPos position = placement.position();
			chunks.add(chunkKey(position.getX() >> 4, position.getZ() >> 4));
			states.put(position.asLong(), placement.state());
			if (!placement.state().getFluidState().isEmpty()) fluids++;
			String blockId = BuiltInRegistries.BLOCK.getKey(placement.state().getBlock()).toString();
			if (CONTAINER_BLOCKS.contains(blockId)) containers++;
			if (PROHIBITED_BLOCKS.contains(blockId)) {
				findings.addOnce("PROHIBITED_BLOCK", "prohibited runtime block " + blockId + " is present");
			}
		}
		if (chunks.size() > MAXIMUM_LOADED_CHUNKS) {
			findings.add("LOADED_CHUNK_CAP", "loaded chunk count " + chunks.size() + " exceeds " + MAXIMUM_LOADED_CHUNKS);
		}
		if (fluids > MAXIMUM_FLUIDS) {
			findings.add("FLUID_CAP", "fluid count " + fluids + " exceeds " + MAXIMUM_FLUIDS);
		}
		if (containers > MAXIMUM_CONTAINERS) {
			findings.add("CONTAINER_CAP", "container count " + containers + " exceeds " + MAXIMUM_CONTAINERS);
		}

		if (metadata != null) validateSemantics(blueprint, participantCount, states, metadata, findings);
		return new Report(findings.copy());
	}

	private static void validateSemantics(
			ScenarioArenaBlueprint blueprint,
			int participantCount,
			Map<Long, BlockState> states,
			ScenarioArenaComposer.CompositionMetadata metadata,
			Findings findings
	) {
		List<ScenarioArenaAnchor> spawns = metadata.anchors().stream()
				.filter(anchor -> anchor.type() == ScenarioArenaAnchor.Type.SPAWN).toList();
		if (spawns.size() < participantCount) {
			findings.add("SPAWN_COUNT", "only " + spawns.size() + " spawn anchors for " + participantCount + " participants");
		}
		for (ScenarioArenaAnchor spawn : spawns) {
			BlockPos feet = spawn.position();
			if (!isPassable(states.get(feet.asLong()))) {
				findings.addOnce("SPAWN_FEET_BLOCKED", "spawn feet are blocked at " + feet);
			}
			if (!isPassable(states.get(feet.above().asLong()))) {
				findings.addOnce("SPAWN_HEAD_BLOCKED", "spawn head is blocked at " + feet.above());
			}
			if (!isFloor(states.get(feet.below().asLong()))) {
				findings.addOnce("SPAWN_FLOOR_MISSING", "spawn floor is missing at " + feet.below());
			}
		}
		for (String objective : ScenarioArenaReachability.unreachableRequiredObjectives(blueprint)) {
			findings.add("OBJECTIVE_UNREACHABLE", "required objective is unreachable: " + objective);
		}
		if (metadata.spectatorSeparation()) {
			List<ScenarioArenaAnchor> cameras = metadata.anchors().stream()
					.filter(anchor -> anchor.type() == ScenarioArenaAnchor.Type.CAMERA).toList();
			List<ScenarioArenaAnchor> playAnchors = metadata.anchors().stream()
					.filter(anchor -> anchor.type() != ScenarioArenaAnchor.Type.CAMERA
							&& anchor.type() != ScenarioArenaAnchor.Type.CONNECTION).toList();
			for (ScenarioArenaAnchor camera : cameras) {
				for (ScenarioArenaAnchor play : playAnchors) {
					if (distanceSquared(camera.position(), play.position()) < MINIMUM_SPECTATOR_DISTANCE_SQUARED) {
						findings.addOnce("SPECTATOR_SEPARATION",
								"spectator anchor " + camera.id() + " is under 12 blocks from play");
					}
				}
			}
		}
	}

	private static boolean isPassable(BlockState state) {
		return state == null || state.isAir();
	}

	private static boolean isFloor(BlockState state) {
		return state != null && !state.isAir() && state.getFluidState().isEmpty();
	}

	private static long distanceSquared(BlockPos first, BlockPos second) {
		long x = (long) first.getX() - second.getX();
		long y = (long) first.getY() - second.getY();
		long z = (long) first.getZ() - second.getZ();
		if (Math.abs(x) >= 12L || Math.abs(y) >= 12L || Math.abs(z) >= 12L) {
			return MINIMUM_SPECTATOR_DISTANCE_SQUARED;
		}
		return x * x + y * y + z * z;
	}

	private static long chunkKey(int x, int z) {
		return (long) x << 32 ^ z & 0xffffffffL;
	}

	public record Finding(String code, String detail) {
		public Finding {
			Objects.requireNonNull(code, "code must not be null");
			Objects.requireNonNull(detail, "detail must not be null");
		}
	}

	public record Report(List<Finding> findings) {
		public Report {
			findings = List.copyOf(Objects.requireNonNull(findings, "findings must not be null"));
		}

		public boolean valid() {
			return findings.isEmpty();
		}

		public boolean hasCode(String code) {
			return findings.stream().anyMatch(finding -> finding.code().equals(code));
		}
	}

	private static final class Findings {
		private final ArrayList<Finding> values = new ArrayList<>();
		private final HashSet<String> uniqueCodes = new HashSet<>();

		private void add(String code, String detail) {
			if (values.size() < MAXIMUM_FINDINGS) values.add(new Finding(code, detail));
		}

		private void addOnce(String code, String detail) {
			if (uniqueCodes.add(code)) add(code, detail);
		}

		private List<Finding> copy() {
			return List.copyOf(values);
		}
	}

	private record Bounds(int minX, int maxX, int minY, int maxY, int minZ, int maxZ) {
		private static Bounds of(
				BlockPos origin,
				List<ScenarioArenaBlueprint.Placement> placements,
				ScenarioArenaComposer.CompositionMetadata metadata
		) {
			if (metadata != null) {
				BlockPos minimum = metadata.bounds().minimum();
				BlockPos maximum = metadata.bounds().maximum();
				return new Bounds(minimum.getX(), maximum.getX(), minimum.getY(), maximum.getY(),
						minimum.getZ(), maximum.getZ());
			}
			if (placements.isEmpty()) {
				return new Bounds(origin.getX(), origin.getX(), origin.getY(), origin.getY(), origin.getZ(), origin.getZ());
			}
			int minX = Integer.MAX_VALUE;
			int maxX = Integer.MIN_VALUE;
			int minY = Integer.MAX_VALUE;
			int maxY = Integer.MIN_VALUE;
			int minZ = Integer.MAX_VALUE;
			int maxZ = Integer.MIN_VALUE;
			for (ScenarioArenaBlueprint.Placement placement : placements) {
				BlockPos position = placement.position();
				minX = Math.min(minX, position.getX());
				maxX = Math.max(maxX, position.getX());
				minY = Math.min(minY, position.getY());
				maxY = Math.max(maxY, position.getY());
				minZ = Math.min(minZ, position.getZ());
				maxZ = Math.max(maxZ, position.getZ());
			}
			return new Bounds(minX, maxX, minY, maxY, minZ, maxZ);
		}

		private long xSpan() { return (long) maxX - minX + 1L; }
		private long ySpan() { return (long) maxY - minY + 1L; }
		private long zSpan() { return (long) maxZ - minZ + 1L; }
		private long volume() {
			long x = xSpan();
			long y = ySpan();
			long z = zSpan();
			if (x > Long.MAX_VALUE / y) return Long.MAX_VALUE;
			long xy = x * y;
			return xy > Long.MAX_VALUE / z ? Long.MAX_VALUE : xy * z;
		}
	}
}
