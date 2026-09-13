package dev.agaminggod.arenaagents.scenario.runtime.map;

import java.util.Objects;
import java.util.regex.Pattern;
import net.minecraft.core.BlockPos;

/** Immutable semantic point in module-local coordinates. */
public record ScenarioArenaAnchor(String id, Type type, BlockPos position) {
	private static final Pattern ID_PATTERN = Pattern.compile("[a-z0-9][a-z0-9-]{0,63}");

	public ScenarioArenaAnchor {
		if (id == null || !ID_PATTERN.matcher(id).matches()) {
			throw new IllegalArgumentException("anchor id is invalid: " + id);
		}
		type = Objects.requireNonNull(type, "anchor type must not be null");
		Objects.requireNonNull(position, "anchor position must not be null");
		position = new BlockPos(position.getX(), position.getY(), position.getZ());
	}

	public enum Type {
		SPAWN("spawn"),
		CHECKPOINT("checkpoint"),
		GOAL("goal"),
		LOOT("loot"),
		CAMERA("camera"),
		CONNECTION("connection");

		private final String wireName;

		Type(String wireName) {
			this.wireName = wireName;
		}

		public String wireName() {
			return wireName;
		}

		public static Type fromWireName(String value) {
			for (Type type : values()) {
				if (type.wireName.equals(value)) return type;
			}
			throw new IllegalArgumentException("unknown anchor type: " + value);
		}
	}
}
