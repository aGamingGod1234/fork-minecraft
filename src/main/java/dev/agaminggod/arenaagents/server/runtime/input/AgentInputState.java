package dev.agaminggod.arenaagents.server.runtime.input;

import java.util.Objects;
import net.minecraft.world.InteractionHand;

public record AgentInputState(
		float forward,
		float strafe,
		boolean jump,
		boolean sneak,
		boolean sprint,
		boolean attack,
		boolean use,
		float yaw,
		float pitch,
		int selectedSlot,
		InteractionHand hand
) {
	public AgentInputState {
		if (!Float.isFinite(forward) || forward < -1.0F || forward > 1.0F) {
			throw new IllegalArgumentException("forward must be finite and in [-1, 1]");
		}
		if (!Float.isFinite(strafe) || strafe < -1.0F || strafe > 1.0F) {
			throw new IllegalArgumentException("strafe must be finite and in [-1, 1]");
		}
		if (!Float.isFinite(yaw) || !Float.isFinite(pitch) || pitch < -90.0F || pitch > 90.0F) {
			throw new IllegalArgumentException("look angles must be finite and pitch must be in [-90, 90]");
		}
		if (selectedSlot < 0 || selectedSlot > 8) throw new IllegalArgumentException("selectedSlot must be in [0, 8]");
		Objects.requireNonNull(hand, "hand must not be null");
	}

	public static AgentInputState idle(float yaw, float pitch, int selectedSlot) {
		return new AgentInputState(
				0.0F, 0.0F, false, false, false, false, false,
				yaw, pitch, selectedSlot, InteractionHand.MAIN_HAND
		);
	}
}
