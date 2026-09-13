package dev.agaminggod.arenaagents.server.runtime.input;

/** Tracks the vanilla riding-jump charge; only an explicit input release emits a jump. */
public final class RidingJumpInput {
	private boolean previousJump;
	private int jumpTicks;
	private float jumpScale;

	public Integer tick(boolean jump, boolean available) {
		if (!available) {
			reset();
			return null;
		}
		if (jumpTicks < 0 && ++jumpTicks == 0) jumpScale = 0.0F;
		Integer charge = null;
		if (previousJump && !jump) {
			charge = (int) Math.floor(jumpScale * 100.0F);
			jumpTicks = -10;
		} else if (!previousJump && jump) {
			jumpTicks = 0;
			jumpScale = 0.0F;
		} else if (previousJump) {
			jumpTicks++;
			jumpScale = jumpTicks < 10 ? jumpTicks * 0.1F : 0.8F + 2.0F / (jumpTicks - 9) * 0.1F;
		}
		previousJump = jump;
		return charge;
	}

	/** Discards pending charge when input is cancelled, the mount changes, or jumping is unavailable. */
	public void reset() {
		previousJump = false;
		jumpTicks = 0;
		jumpScale = 0.0F;
	}
}
