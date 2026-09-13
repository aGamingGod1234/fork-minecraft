package dev.agaminggod.arenaagents.server.perception;

import java.util.Objects;

/** A first visible block hit by a sight ray; it is a visual cue, not an interaction target. */
public record VisibleSurfaceCandidate(int x, int y, int z, String blockId, double distanceSquared) {
	public VisibleSurfaceCandidate {
		if (Objects.requireNonNull(blockId, "blockId must not be null").isBlank()) {
			throw new IllegalArgumentException("blockId must not be blank");
		}
		if (!Double.isFinite(distanceSquared) || distanceSquared < 0.0D) {
			throw new IllegalArgumentException("distanceSquared must be finite and non-negative");
		}
	}
}
