package dev.agaminggod.arenaagents.agent.goal;

/**
 * Coordinate limits accepted by Minecraft's dimension format and world border.
 *
 * <p>Individual dimensions can use a smaller build-height range. The live
 * verifier checks that narrower range through its fact source; these bounds
 * prevent a goal from ever naming a coordinate that no Minecraft dimension
 * can represent.</p>
 */
public final class MinecraftCoordinateBounds {
	public static final int MIN_HORIZONTAL = -30_000_000;
	public static final int MAX_HORIZONTAL_EXCLUSIVE = 30_000_000;
	public static final int MIN_BUILD_HEIGHT = -2_032;
	public static final int MAX_BUILD_HEIGHT_EXCLUSIVE = 2_032;

	private MinecraftCoordinateBounds() {
	}

	public static boolean isReachable(double x, double y, double z) {
		return Double.isFinite(x) && Double.isFinite(y) && Double.isFinite(z)
				&& x >= MIN_HORIZONTAL && x < MAX_HORIZONTAL_EXCLUSIVE
				&& z >= MIN_HORIZONTAL && z < MAX_HORIZONTAL_EXCLUSIVE
				&& y >= MIN_BUILD_HEIGHT && y < MAX_BUILD_HEIGHT_EXCLUSIVE;
	}

	public static boolean isReachable(int x, int y, int z) {
		return x >= MIN_HORIZONTAL && x < MAX_HORIZONTAL_EXCLUSIVE
				&& z >= MIN_HORIZONTAL && z < MAX_HORIZONTAL_EXCLUSIVE
				&& y >= MIN_BUILD_HEIGHT && y < MAX_BUILD_HEIGHT_EXCLUSIVE;
	}
}
