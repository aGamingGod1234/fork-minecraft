package dev.agaminggod.arenaagents.client.navigation;

public record GridPosition(int x, int y, int z) {
	public GridPosition above() {
		return above(1);
	}

	public GridPosition above(int distance) {
		return offset(0, distance, 0);
	}

	public GridPosition below() {
		return below(1);
	}

	public GridPosition below(int distance) {
		return offset(0, -distance, 0);
	}

	public GridPosition offset(int xOffset, int yOffset, int zOffset) {
		return new GridPosition(
				Math.addExact(x, xOffset),
				Math.addExact(y, yOffset),
				Math.addExact(z, zOffset)
		);
	}
}
