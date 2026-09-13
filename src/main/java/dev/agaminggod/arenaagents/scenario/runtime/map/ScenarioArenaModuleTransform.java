package dev.agaminggod.arenaagents.scenario.runtime.map;

import java.util.Objects;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Mirror;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.block.state.BlockState;

/** Applies one declared module transform to positions and directional block states. */
public final class ScenarioArenaModuleTransform {
	private ScenarioArenaModuleTransform() {
	}

	public static BlockPos transformPosition(
			BlockPos position,
			ScenarioArenaModule.Bounds bounds,
			ScenarioArenaModule.AllowedTransform transform
	) {
		Objects.requireNonNull(position, "position must not be null");
		Objects.requireNonNull(bounds, "bounds must not be null");
		Objects.requireNonNull(transform, "transform must not be null");
		if (!bounds.contains(position)) throw new IllegalArgumentException("position is outside module bounds");
		int x = Math.subtractExact(position.getX(), bounds.minimum().getX());
		int y = Math.subtractExact(position.getY(), bounds.minimum().getY());
		int z = Math.subtractExact(position.getZ(), bounds.minimum().getZ());
		int maximumX = Math.subtractExact(bounds.maximum().getX(), bounds.minimum().getX());
		int maximumZ = Math.subtractExact(bounds.maximum().getZ(), bounds.minimum().getZ());
		return switch (transform) {
			case IDENTITY -> new BlockPos(x, y, z);
			case ROTATE_90 -> new BlockPos(Math.subtractExact(maximumZ, z), y, x);
			case ROTATE_180 -> new BlockPos(Math.subtractExact(maximumX, x), y,
					Math.subtractExact(maximumZ, z));
			case ROTATE_270 -> new BlockPos(z, y, Math.subtractExact(maximumX, x));
			case MIRROR_X -> new BlockPos(Math.subtractExact(maximumX, x), y, z);
			case MIRROR_Z -> new BlockPos(x, y, Math.subtractExact(maximumZ, z));
		};
	}

	public static BlockState transformState(
			BlockState state,
			ScenarioArenaModule.AllowedTransform transform
	) {
		Objects.requireNonNull(state, "state must not be null");
		Objects.requireNonNull(transform, "transform must not be null");
		return switch (transform) {
			case IDENTITY -> state;
			case ROTATE_90 -> state.rotate(Rotation.CLOCKWISE_90);
			case ROTATE_180 -> state.rotate(Rotation.CLOCKWISE_180);
			case ROTATE_270 -> state.rotate(Rotation.COUNTERCLOCKWISE_90);
			case MIRROR_X -> state.mirror(Mirror.FRONT_BACK);
			case MIRROR_Z -> state.mirror(Mirror.LEFT_RIGHT);
		};
	}
}
