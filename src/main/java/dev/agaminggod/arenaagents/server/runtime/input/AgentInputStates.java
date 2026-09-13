package dev.agaminggod.arenaagents.server.runtime.input;

import java.util.Objects;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.Mth;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.phys.Vec3;

public final class AgentInputStates {
	/** Maximum view rotation applied by one server tick. */
	public static final float MAX_YAW_STEP_DEGREES = 12.0F;
	public static final float MAX_PITCH_STEP_DEGREES = 8.0F;

	/** Analog motor limits. These values are deliberately independent of server TPS. */
	public static final float MOVE_ACCELERATION = 0.20F;
	public static final float MOVE_DECELERATION = 0.35F;
	private static final float MOVEMENT_EPSILON = 1.0E-4F;

	private AgentInputStates() {
	}

	public static AgentInputState lookingAt(
			ServerPlayer player,
			Vec3 target,
			float forward,
			float strafe,
			boolean jump,
			boolean sneak,
			boolean sprint,
			boolean attack,
			boolean use,
			InteractionHand hand
	) {
		Objects.requireNonNull(player, "player must not be null");
		Objects.requireNonNull(target, "target must not be null");
		Vec3 delta = target.subtract(player.getEyePosition());
		double horizontal = Math.sqrt(delta.x * delta.x + delta.z * delta.z);
		float yaw = Mth.wrapDegrees((float) Math.toDegrees(Math.atan2(-delta.x, delta.z)));
		float pitch = Mth.clamp((float) -Math.toDegrees(Math.atan2(delta.y, horizontal)), -90.0F, 90.0F);
		return new AgentInputState(
				forward, strafe, jump, sneak, sprint, attack, use,
				yaw, pitch, player.getInventory().getSelectedSlot(), hand
		);
	}

	public static AgentInputState safetyReflex(
			ServerPlayer player,
			Vec3 target,
			float forward,
			boolean jump,
			boolean sprint,
			boolean use,
			InteractionHand hand
	) {
		AgentInputState movement = lookingAt(
				player, target, forward, 0.0F, jump, false, sprint, false, use, hand);
		return new AgentInputState(
				movement.forward(), movement.strafe(), movement.jump(), movement.sneak(), movement.sprint(),
				false, movement.use(), movement.yaw(), movement.pitch(), movement.selectedSlot(), movement.hand());
	}

	/**
	 * Advances a small player-like movement motor toward a target view and movement direction.
	 * The target's jump flag is a held request. Carpet converts the first true edge into its
	 * continuous jump action, so the state must remain true until navigation releases it.
	 */
	public static MotorStep stepMotor(MotorState state, MotorTarget target, long nowEpochMs) {
		Objects.requireNonNull(state, "state must not be null");
		Objects.requireNonNull(target, "target must not be null");
		if (nowEpochMs < 0L) throw new IllegalArgumentException("nowEpochMs must not be negative");

		float yawDelta = shortestAngleDelta(state.yaw(), target.yaw());
		float pitchDelta = target.pitch() - state.pitch();
		float yaw = Mth.wrapDegrees(state.yaw() + clamp(yawDelta, -MAX_YAW_STEP_DEGREES, MAX_YAW_STEP_DEGREES));
		float pitch = Mth.clamp(
				state.pitch() + clamp(pitchDelta, -MAX_PITCH_STEP_DEGREES, MAX_PITCH_STEP_DEGREES),
				-90.0F,
				90.0F
		);

		float desiredForward = 0.0F;
		float desiredStrafe = 0.0F;
		if (target.moving()) {
			// Move mostly forward while turning, then bleed into a lateral component. This avoids
			// the stop-and-reverse oscillation that a hard yaw snap causes around corners.
			float relativeRadians = (float) Math.toRadians(shortestAngleDelta(yaw, target.yaw()));
			desiredForward = Math.max(0.0F, (float) Math.cos(relativeRadians));
			// Positive strafe is left, opposite the positive yaw turn toward the right.
			desiredStrafe = -(float) Math.sin(relativeRadians);
			if (Math.abs(desiredForward) + Math.abs(desiredStrafe) < MOVEMENT_EPSILON) {
				desiredStrafe = yawDelta < 0.0F ? 1.0F : -1.0F;
			}
		}
		float forward = approach(state.forward(), desiredForward,
				desiredForward == 0.0F ? MOVE_DECELERATION : MOVE_ACCELERATION);
		float strafe = approach(state.strafe(), desiredStrafe,
				desiredStrafe == 0.0F ? MOVE_DECELERATION : MOVE_ACCELERATION);
		boolean jump = target.jumpRequested();
		MotorState next = new MotorState(yaw, pitch, forward, strafe, target.jumpRequested());
		return new MotorStep(next, forward, strafe, jump, target.sprint());
	}

	/** Returns the shortest signed turn from {@code current} to {@code target}. */
	public static float shortestAngleDelta(float current, float target) {
		if (!Float.isFinite(current) || !Float.isFinite(target)) {
			throw new IllegalArgumentException("angles must be finite");
		}
		return Mth.wrapDegrees(target - current);
	}

	private static float approach(float current, float target, float step) {
		if (!Float.isFinite(current) || !Float.isFinite(target) || !Float.isFinite(step) || step < 0.0F) {
			throw new IllegalArgumentException("motor values must be finite and step must be non-negative");
		}
		if (Math.abs(target - current) <= step) return target;
		return current + Math.copySign(step, target - current);
	}

	private static float clamp(float value, float minimum, float maximum) {
		return Math.max(minimum, Math.min(maximum, value));
	}

	public record MotorState(
			float yaw,
			float pitch,
			float forward,
			float strafe,
			boolean jumpHeld
	) {
		public MotorState {
			if (!Float.isFinite(yaw) || !Float.isFinite(pitch)
					|| !Float.isFinite(forward) || !Float.isFinite(strafe)
					|| pitch < -90.0F || pitch > 90.0F
					|| forward < -1.0F || forward > 1.0F
					|| strafe < -1.0F || strafe > 1.0F) {
				throw new IllegalArgumentException("invalid motor state");
			}
		}

		public MotorState(float yaw, float pitch) {
			this(yaw, pitch, 0.0F, 0.0F, false);
		}

		public static MotorState initial(float yaw, float pitch) {
			return new MotorState(yaw, pitch);
		}
	}

	public record MotorTarget(
			float yaw,
			float pitch,
			boolean moving,
			boolean jumpRequested,
			boolean sprint
	) {
		public MotorTarget {
			if (!Float.isFinite(yaw) || !Float.isFinite(pitch) || pitch < -90.0F || pitch > 90.0F) {
				throw new IllegalArgumentException("invalid motor target angles");
			}
		}
	}

	public record MotorStep(MotorState state, float forward, float strafe, boolean jump, boolean sprint) {
		public MotorStep(MotorState state, float forward, float strafe, boolean jump) {
			this(state, forward, strafe, jump, false);
		}

		public MotorStep {
			Objects.requireNonNull(state, "state must not be null");
			if (!Float.isFinite(forward) || !Float.isFinite(strafe)
					|| forward < -1.0F || forward > 1.0F || strafe < -1.0F || strafe > 1.0F) {
					throw new IllegalArgumentException("invalid motor step");
			}
		}

		public float yaw() {
			return state.yaw();
		}

		public float pitch() {
			return state.pitch();
		}
	}
}
