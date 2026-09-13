package dev.agaminggod.arenaagents.client.presentation;

import dev.agaminggod.arenaagents.scenario.presentation.ArenaSpectatorSnapshot;
import dev.agaminggod.arenaagents.scenario.presentation.DirectorRecommendation;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.util.Mth;

public final class SpectatorCameraAssistant {
	private static final float MANUAL_LOOK_EPSILON = 0.05F;
	private static final float SMOOTHING = 0.18F;
	private LocalPlayer trackedPlayer;
	private boolean expectedRotationAvailable;
	private float expectedYaw;
	private float expectedPitch;

	public void tick(Minecraft client, ArenaSpectatorState state) {
		ArenaSpectatorSnapshot snapshot = state.snapshot().orElse(null);
		long currentTick = snapshot == null ? 0L : snapshot.elapsedTick();
		LocalPlayer player = client.player;
		state.tickCamera(
				client.getConnection() != null,
				player != null && player.isAlive(),
				player != null && player.isSpectator(),
				client.screen != null,
				currentTick
		);
		if (state.cameraDisabled()) {
			resetTracking();
			return;
		}
		if (player == null || (trackedPlayer != null && trackedPlayer != player)) {
			state.onManualInput(ArenaSpectatorState.ManualOverride.LIFE_TRANSITION);
			resetTracking();
			return;
		}
		trackedPlayer = player;
		if (client.getCameraEntity() != player) {
			state.onManualInput(ArenaSpectatorState.ManualOverride.MOVEMENT);
			resetTracking();
			return;
		}
		if (movementInput(client)) {
			state.onManualInput(ArenaSpectatorState.ManualOverride.MOVEMENT);
			resetTracking();
			return;
		}
		if (jumpSneakSprintInput(client)) {
			state.onManualInput(ArenaSpectatorState.ManualOverride.JUMP_SNEAK_SPRINT);
			resetTracking();
			return;
		}
		if (actionInput(client)) {
			state.onManualInput(ArenaSpectatorState.ManualOverride.ATTACK_USE_PICK);
			resetTracking();
			return;
		}
		if (expectedRotationAvailable && (
				Math.abs(Mth.wrapDegrees(player.getYRot() - expectedYaw)) > MANUAL_LOOK_EPSILON
						|| Math.abs(player.getXRot() - expectedPitch) > MANUAL_LOOK_EPSILON)) {
			state.onManualInput(ArenaSpectatorState.ManualOverride.MOUSE_LOOK);
			resetTracking();
			return;
		}
		DirectorRecommendation target = state.cameraTarget(currentTick).orElse(null);
		if (target == null) {
			state.disableCamera();
			resetTracking();
			return;
		}
		adjustView(player, target);
	}

	public void resetTracking() {
		trackedPlayer = null;
		expectedRotationAvailable = false;
	}

	private void adjustView(LocalPlayer player, DirectorRecommendation target) {
		double dx = target.x() - player.getX();
		double dy = target.y() - player.getEyeY();
		double dz = target.z() - player.getZ();
		double horizontal = Math.sqrt((dx * dx) + (dz * dz));
		float targetYaw = (float) Math.toDegrees(Math.atan2(dz, dx)) - 90.0F;
		float targetPitch = (float) -Math.toDegrees(Math.atan2(dy, horizontal));
		float yaw = player.getYRot() + Mth.wrapDegrees(targetYaw - player.getYRot()) * SMOOTHING;
		float pitch = Mth.clamp(player.getXRot() + (targetPitch - player.getXRot()) * SMOOTHING, -90.0F, 90.0F);
		player.setYRot(yaw);
		player.setXRot(pitch);
		expectedYaw = yaw;
		expectedPitch = pitch;
		expectedRotationAvailable = true;
	}

	private static boolean movementInput(Minecraft client) {
		return client.options.keyUp.isDown()
				|| client.options.keyDown.isDown()
				|| client.options.keyLeft.isDown()
				|| client.options.keyRight.isDown();
	}

	private static boolean jumpSneakSprintInput(Minecraft client) {
		return client.options.keyJump.isDown()
				|| client.options.keyShift.isDown()
				|| client.options.keySprint.isDown();
	}

	private static boolean actionInput(Minecraft client) {
		return client.options.keyAttack.isDown()
				|| client.options.keyUse.isDown()
				|| client.options.keyPickItem.isDown();
	}
}
