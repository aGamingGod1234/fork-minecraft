package dev.agaminggod.arenaagents.scenario.runtime.map;

import dev.agaminggod.arenaagents.scenario.runtime.ScenarioArenaBlueprint;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.WeakHashMap;
import net.minecraft.core.BlockPos;

/** Pure deterministic composition of validated arena modules into a runtime blueprint. */
public final class ScenarioArenaComposer {
	private static final Comparator<BlockPos> POSITION_ORDER = Comparator
			.comparingInt((BlockPos position) -> position.getX())
			.thenComparingInt(BlockPos::getY)
			.thenComparingInt(BlockPos::getZ);
	private static final Map<ScenarioArenaBlueprint, CompositionMetadata> METADATA =
			Collections.synchronizedMap(new WeakHashMap<>());

	private ScenarioArenaComposer() {
	}

	public static ScenarioArenaBlueprint compose(ScenarioArenaPlan plan, BlockPos origin) {
		Objects.requireNonNull(plan, "plan must not be null");
		Objects.requireNonNull(origin, "origin must not be null");
		TreeMap<BlockPos, ScenarioArenaBlueprint.Placement> placements = new TreeMap<>(POSITION_ORDER);
		ArrayList<ScenarioArenaAnchor> anchors = new ArrayList<>();
		HashSet<String> anchorIds = new HashSet<>();
		boolean spectatorSeparation = false;
		BlockPos minimum = null;
		BlockPos maximum = null;

		for (ScenarioArenaPlan.ModulePlacement entry : plan.modules()) {
			ScenarioArenaModule module = entry.module();
			spectatorSeparation |= module.spectatorPolicy() == ScenarioArenaModule.SpectatorPolicy.SEPARATED;
			for (BlockPos corner : transformedCorners(module.bounds(), entry.transform())) {
				BlockPos absolute = add(origin, entry.offset(), corner);
				minimum = minimum == null ? absolute : new BlockPos(
						Math.min(minimum.getX(), absolute.getX()), Math.min(minimum.getY(), absolute.getY()),
						Math.min(minimum.getZ(), absolute.getZ()));
				maximum = maximum == null ? absolute : new BlockPos(
						Math.max(maximum.getX(), absolute.getX()), Math.max(maximum.getY(), absolute.getY()),
						Math.max(maximum.getZ(), absolute.getZ()));
			}
			for (ScenarioArenaModule.Placement local : module.placements()) {
				BlockPos transformed = ScenarioArenaModuleTransform.transformPosition(
						local.position(), module.bounds(), entry.transform());
				BlockPos absolute = add(origin, entry.offset(), transformed);
				var state = ScenarioArenaModuleTransform.transformState(local.state(), entry.transform());
				ScenarioArenaBlueprint.Placement previous = placements.putIfAbsent(
						absolute, new ScenarioArenaBlueprint.Placement(absolute, state));
				if (previous != null && !previous.state().equals(state)) {
					throw new IllegalArgumentException("conflicting module overlap at " + absolute);
				}
			}
			for (ScenarioArenaAnchor local : module.anchors()) {
				BlockPos transformed = ScenarioArenaModuleTransform.transformPosition(
						local.position(), module.bounds(), entry.transform());
				String id = entry.instanceId() + "-" + local.id();
				if (!anchorIds.add(id)) throw new IllegalArgumentException("duplicate composed anchor id: " + id);
				anchors.add(new ScenarioArenaAnchor(id, local.type(), add(origin, entry.offset(), transformed)));
			}
		}

		Set<String> availableObjectives = new HashSet<>();
		Map<BlockPos, Integer> connectionCounts = new TreeMap<>(POSITION_ORDER);
		for (ScenarioArenaAnchor anchor : anchors) {
			if (anchor.type() == ScenarioArenaAnchor.Type.GOAL
					|| anchor.type() == ScenarioArenaAnchor.Type.CHECKPOINT) {
				availableObjectives.add(anchor.id());
			}
			if (anchor.type() == ScenarioArenaAnchor.Type.CONNECTION) {
				connectionCounts.merge(anchor.position(), 1, Integer::sum);
			}
		}
		if (!availableObjectives.containsAll(plan.requiredObjectiveAnchorIds())) {
			TreeSet<String> missing = new TreeSet<>(plan.requiredObjectiveAnchorIds());
			missing.removeAll(availableObjectives);
			throw new IllegalArgumentException("required objective anchors are missing: " + missing);
		}
		for (Map.Entry<BlockPos, Integer> connection : connectionCounts.entrySet()) {
			if (connection.getValue() != 2) {
				throw new IllegalArgumentException("connection anchor must join exactly two modules at "
						+ connection.getKey());
			}
		}

		List<ScenarioArenaBlueprint.Placement> orderedPlacements = List.copyOf(placements.values());
		ScenarioArenaBlueprint blueprint = ScenarioArenaBlueprint.fromPlacements(origin, orderedPlacements);
		CompositionMetadata metadata = new CompositionMetadata(
				List.copyOf(anchors), Set.copyOf(plan.requiredObjectiveAnchorIds()), spectatorSeparation,
				new CompositionBounds(minimum, maximum), compositionHash(plan, origin, orderedPlacements));
		METADATA.put(blueprint, metadata);
		return blueprint;
	}

	public static List<ScenarioArenaAnchor> anchors(ScenarioArenaBlueprint blueprint) {
		return requireMetadata(blueprint).anchors();
	}

	public static String compositionHash(ScenarioArenaBlueprint blueprint) {
		return requireMetadata(blueprint).compositionHash();
	}

	static CompositionMetadata metadata(ScenarioArenaBlueprint blueprint) {
		return METADATA.get(Objects.requireNonNull(blueprint, "blueprint must not be null"));
	}

	private static CompositionMetadata requireMetadata(ScenarioArenaBlueprint blueprint) {
		CompositionMetadata metadata = metadata(blueprint);
		if (metadata == null) throw new IllegalArgumentException("blueprint was not produced by the module composer");
		return metadata;
	}

	private static BlockPos add(BlockPos origin, BlockPos offset, BlockPos local) {
		return new BlockPos(
				Math.addExact(Math.addExact(origin.getX(), offset.getX()), local.getX()),
				Math.addExact(Math.addExact(origin.getY(), offset.getY()), local.getY()),
				Math.addExact(Math.addExact(origin.getZ(), offset.getZ()), local.getZ()));
	}

	private static List<BlockPos> transformedCorners(
			ScenarioArenaModule.Bounds bounds,
			ScenarioArenaModule.AllowedTransform transform
	) {
		ArrayList<BlockPos> corners = new ArrayList<>(8);
		for (int x : new int[]{bounds.minimum().getX(), bounds.maximum().getX()}) {
			for (int y : new int[]{bounds.minimum().getY(), bounds.maximum().getY()}) {
				for (int z : new int[]{bounds.minimum().getZ(), bounds.maximum().getZ()}) {
					corners.add(ScenarioArenaModuleTransform.transformPosition(new BlockPos(x, y, z), bounds, transform));
				}
			}
		}
		return corners;
	}

	private static String compositionHash(
			ScenarioArenaPlan plan,
			BlockPos origin,
			List<ScenarioArenaBlueprint.Placement> placements
	) {
		MessageDigest digest = digest();
		update(digest, "plan=" + plan.id() + "\n");
		update(digest, "origin=" + origin.getX() + "," + origin.getY() + "," + origin.getZ() + "\n");
		for (int index = 0; index < plan.modules().size(); index++) {
			ScenarioArenaPlan.ModulePlacement entry = plan.modules().get(index);
			ScenarioArenaModule module = entry.module();
			update(digest, index + "=" + entry.instanceId() + "," + module.id() + "," + module.version()
					+ "," + module.sourceKey() + "," + module.geometrySha256() + "," + entry.transform().wireName()
					+ "," + entry.offset().getX() + "," + entry.offset().getY() + "," + entry.offset().getZ() + "\n");
		}
		for (ScenarioArenaBlueprint.Placement placement : placements) {
			BlockPos position = placement.position();
			update(digest, position.getX() + "," + position.getY() + "," + position.getZ() + "="
					+ ScenarioModuleHasher.canonicalState(placement.state()) + "\n");
		}
		return java.util.HexFormat.of().formatHex(digest.digest());
	}

	private static void update(MessageDigest digest, String value) {
		digest.update(value.getBytes(StandardCharsets.UTF_8));
	}

	private static MessageDigest digest() {
		try {
			return MessageDigest.getInstance("SHA-256");
		} catch (NoSuchAlgorithmException error) {
			throw new IllegalStateException("SHA-256 is unavailable", error);
		}
	}

	record CompositionMetadata(
			List<ScenarioArenaAnchor> anchors,
			Set<String> requiredObjectiveAnchorIds,
			boolean spectatorSeparation,
			CompositionBounds bounds,
			String compositionHash
	) {
	}

	record CompositionBounds(BlockPos minimum, BlockPos maximum) {
	}
}
