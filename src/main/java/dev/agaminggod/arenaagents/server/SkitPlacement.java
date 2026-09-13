package dev.agaminggod.arenaagents.server;

import java.util.Objects;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;

/** A persisted camera-ready location for one skit agent. */
public record SkitPlacement(String dimension, double x, double y, double z, float yaw, float pitch) {
	private static final double MAX_HORIZONTAL_COORDINATE = 30_000_000.0D;
	private static final double MIN_Y = -2_048.0D;
	private static final double MAX_Y = 4_096.0D;
	public SkitPlacement {
		dimension = Objects.requireNonNull(dimension, "dimension must not be null").strip();
		if (!dimension.matches("[a-z0-9_.-]+:[a-z0-9_/.-]+")) {
			throw new IllegalArgumentException("dimension must be a namespaced dimension identifier");
		}
		finite(x, "x");
		finite(y, "y");
		finite(z, "z");
		finite(yaw, "yaw");
		finite(pitch, "pitch");
		if (Math.abs(x) > MAX_HORIZONTAL_COORDINATE || Math.abs(z) > MAX_HORIZONTAL_COORDINATE) {
			throw new IllegalArgumentException("x and z must be within Minecraft's world boundary");
		}
		if (y < MIN_Y || y > MAX_Y) throw new IllegalArgumentException("y is outside the supported world height range");
		if (pitch < -90.0F || pitch > 90.0F) throw new IllegalArgumentException("pitch must be between -90 and 90");
	}

	/** Captures the exact location and view of a player for the intuitive "here" command. */
	public static SkitPlacement fromPlayer(ServerPlayer player) {
		Objects.requireNonNull(player, "player must not be null");
		return new SkitPlacement(player.level().dimension().identifier().toString(), player.getX(), player.getY(), player.getZ(), player.getYRot(), player.getXRot());
	}

	/** Places an actor relative to the player's view: right, up, then forward blocks. */
	public static SkitPlacement relativeTo(ServerPlayer player, double right, double up, double forward) {
		return relativeTo(fromPlayer(player), right, up, forward);
	}

	static SkitPlacement relativeTo(SkitPlacement base, double right, double up, double forward) {
		double yawRadians = Math.toRadians(base.yaw());
		return new SkitPlacement(base.dimension(),
				base.x() - right * Math.cos(yawRadians) - forward * Math.sin(yawRadians),
				base.y() + up,
				base.z() - right * Math.sin(yawRadians) + forward * Math.cos(yawRadians),
				base.yaw(), base.pitch());
	}

	/** Keeps an actor at the current player location while aiming it at a world-space target. */
	public static SkitPlacement lookingAt(ServerPlayer player, Vec3 target) {
		Objects.requireNonNull(target, "target must not be null");
		SkitPlacement base = fromPlayer(player);
		Vec3 delta = target.subtract(player.getEyePosition());
		double horizontal = Math.sqrt(delta.x * delta.x + delta.z * delta.z);
		float yaw = Mth.wrapDegrees((float) Math.toDegrees(Math.atan2(-delta.x, delta.z)));
		float pitch = Mth.clamp((float) -Math.toDegrees(Math.atan2(delta.y, horizontal)), -90.0F, 90.0F);
		return new SkitPlacement(base.dimension(), base.x(), base.y(), base.z(), yaw, pitch);
	}

	private static void finite(double value, String field) {
		if (!Double.isFinite(value)) throw new IllegalArgumentException(field + " must be finite");
	}

	private static void finite(float value, String field) {
		if (!Float.isFinite(value)) throw new IllegalArgumentException(field + " must be finite");
	}
}
