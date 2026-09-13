package dev.agaminggod.arenaagents.scenario.runtime.map;

import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;

/** Validated, immutable block-only arena module in local coordinates. */
public record ScenarioArenaModule(
		String id,
		int version,
		String sourceKey,
		int difficulty,
		Bounds bounds,
		Set<AllowedTransform> allowedTransforms,
		ContainerPolicy containerPolicy,
		SpectatorPolicy spectatorPolicy,
		List<Placement> placements,
		List<ScenarioArenaAnchor> anchors,
		String geometrySha256
) {
	private static final Pattern ID_PATTERN = Pattern.compile("[a-z0-9][a-z0-9-]{0,63}");
	private static final Pattern SHA256_PATTERN = Pattern.compile("[0-9a-f]{64}");

	public ScenarioArenaModule {
		if (id == null || !ID_PATTERN.matcher(id).matches()) throw new IllegalArgumentException("module id is invalid");
		if (version < 1) throw new IllegalArgumentException("module version must be positive");
		if (sourceKey == null || !ID_PATTERN.matcher(sourceKey).matches()) {
			throw new IllegalArgumentException("module sourceKey is invalid");
		}
		if (difficulty < 1 || difficulty > 5) throw new IllegalArgumentException("module difficulty must be 1..5");
		bounds = Objects.requireNonNull(bounds, "module bounds must not be null");
		Objects.requireNonNull(allowedTransforms, "allowedTransforms must not be null");
		if (allowedTransforms.isEmpty()) throw new IllegalArgumentException("allowedTransforms must not be empty");
		allowedTransforms = Collections.unmodifiableSet(new LinkedHashSet<>(allowedTransforms));
		containerPolicy = Objects.requireNonNull(containerPolicy, "containerPolicy must not be null");
		spectatorPolicy = Objects.requireNonNull(spectatorPolicy, "spectatorPolicy must not be null");
		placements = List.copyOf(Objects.requireNonNull(placements, "placements must not be null"));
		anchors = List.copyOf(Objects.requireNonNull(anchors, "anchors must not be null"));
		if (geometrySha256 == null || !SHA256_PATTERN.matcher(geometrySha256).matches()) {
			throw new IllegalArgumentException("geometrySha256 must be lowercase SHA-256");
		}
	}

	public enum AllowedTransform {
		IDENTITY("identity"),
		ROTATE_90("rotate_90"),
		ROTATE_180("rotate_180"),
		ROTATE_270("rotate_270"),
		MIRROR_X("mirror_x"),
		MIRROR_Z("mirror_z");

		private final String wireName;

		AllowedTransform(String wireName) {
			this.wireName = wireName;
		}

		public String wireName() {
			return wireName;
		}

		public static AllowedTransform fromWireName(String value) {
			for (AllowedTransform transform : values()) {
				if (transform.wireName.equals(value)) return transform;
			}
			throw new IllegalArgumentException("unknown allowed transform: " + value);
		}
	}

	public enum ContainerPolicy {
		NONE("none"), EMPTY("empty");

		private final String wireName;

		ContainerPolicy(String wireName) {
			this.wireName = wireName;
		}

		public String wireName() {
			return wireName;
		}

		public static ContainerPolicy fromWireName(String value) {
			for (ContainerPolicy policy : values()) if (policy.wireName.equals(value)) return policy;
			throw new IllegalArgumentException("unknown container policy: " + value);
		}
	}

	public enum SpectatorPolicy {
		NONE("none"), SEPARATED("separated");

		private final String wireName;

		SpectatorPolicy(String wireName) {
			this.wireName = wireName;
		}

		public String wireName() {
			return wireName;
		}

		public static SpectatorPolicy fromWireName(String value) {
			for (SpectatorPolicy policy : values()) if (policy.wireName.equals(value)) return policy;
			throw new IllegalArgumentException("unknown spectator policy: " + value);
		}
	}

	public record Bounds(BlockPos minimum, BlockPos maximum) {
		public Bounds {
			Objects.requireNonNull(minimum, "minimum must not be null");
			Objects.requireNonNull(maximum, "maximum must not be null");
			minimum = new BlockPos(minimum.getX(), minimum.getY(), minimum.getZ());
			maximum = new BlockPos(maximum.getX(), maximum.getY(), maximum.getZ());
			if (minimum.getX() > maximum.getX() || minimum.getY() > maximum.getY()
					|| minimum.getZ() > maximum.getZ()) {
				throw new IllegalArgumentException("module bounds are inverted");
			}
		}

		public boolean contains(BlockPos position) {
			return position.getX() >= minimum.getX() && position.getX() <= maximum.getX()
					&& position.getY() >= minimum.getY() && position.getY() <= maximum.getY()
					&& position.getZ() >= minimum.getZ() && position.getZ() <= maximum.getZ();
		}
	}

	public record Placement(BlockPos position, BlockState state) {
		public Placement {
			Objects.requireNonNull(position, "placement position must not be null");
			position = new BlockPos(position.getX(), position.getY(), position.getZ());
			state = Objects.requireNonNull(state, "placement state must not be null");
		}
	}
}
