package dev.agaminggod.arenaagents.scenario.runtime.map;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.level.block.state.BlockState;

/** Canonical geometry hashing shared by module decoding and composition. */
public final class ScenarioModuleHasher {
	private static final Comparator<ScenarioArenaModule.Placement> PLACEMENT_ORDER = Comparator
			.comparingInt((ScenarioArenaModule.Placement placement) -> placement.position().getX())
			.thenComparingInt(placement -> placement.position().getY())
			.thenComparingInt(placement -> placement.position().getZ());

	private ScenarioModuleHasher() {
	}

	public static String sha256(List<ScenarioArenaModule.Placement> placements) {
		if (placements == null) throw new IllegalArgumentException("placements must not be null");
		List<ScenarioArenaModule.Placement> ordered = new ArrayList<>(placements);
		if (ordered.stream().anyMatch(java.util.Objects::isNull)) {
			throw new IllegalArgumentException("placements must not contain null");
		}
		ordered.sort(PLACEMENT_ORDER);
		Set<BlockPos> coordinates = new HashSet<>();
		MessageDigest digest = digest();
		for (ScenarioArenaModule.Placement placement : ordered) {
			if (!coordinates.add(placement.position())) {
				throw new IllegalArgumentException("duplicate placement coordinate: " + placement.position());
			}
			BlockPos position = placement.position();
			String line = position.getX() + "," + position.getY() + "," + position.getZ()
					+ "=" + canonicalState(placement.state()) + "\n";
			digest.update(line.getBytes(StandardCharsets.UTF_8));
		}
		return java.util.HexFormat.of().formatHex(digest.digest());
	}

	public static String canonicalState(BlockState state) {
		if (state == null) throw new IllegalArgumentException("state must not be null");
		StringBuilder value = new StringBuilder(BuiltInRegistries.BLOCK.getKey(state.getBlock()).toString());
		var properties = state.getValues()
				.sorted(Comparator.comparing(entry -> entry.property().getName()))
				.toList();
		if (!properties.isEmpty()) {
			value.append('[');
			for (int index = 0; index < properties.size(); index++) {
				if (index > 0) value.append(',');
				var entry = properties.get(index);
				value.append(entry.property().getName()).append('=').append(entry.valueName());
			}
			value.append(']');
		}
		return value.toString();
	}

	private static MessageDigest digest() {
		try {
			return MessageDigest.getInstance("SHA-256");
		} catch (NoSuchAlgorithmException error) {
			throw new IllegalStateException("SHA-256 is unavailable", error);
		}
	}
}
