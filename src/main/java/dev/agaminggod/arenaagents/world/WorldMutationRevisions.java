package dev.agaminggod.arenaagents.world;

import java.util.LinkedHashMap;

/** Bounded revisions for observed 512-block regions; unrelated world activity stays cheap. */
public final class WorldMutationRevisions {
	private static final int REGION_SHIFT = 9;
	private static final int CAPACITY = 256;
	private final LinkedHashMap<Long, Long> regions = new LinkedHashMap<>(16, 0.75F, true);
	private long sequence;

	public synchronized long revision(int centerX, int centerZ, int radius) {
		if (radius < 0 || radius >= 1 << REGION_SHIFT) throw new IllegalArgumentException("invalid observation radius");
		int minimumX = (int) (((long) centerX - radius) >> REGION_SHIFT);
		int maximumX = (int) (((long) centerX + radius) >> REGION_SHIFT);
		int minimumZ = (int) (((long) centerZ - radius) >> REGION_SHIFT);
		int maximumZ = (int) (((long) centerZ + radius) >> REGION_SHIFT);
		long revision = 0L;
		for (int x = minimumX; x <= maximumX; x++) {
			for (int z = minimumZ; z <= maximumZ; z++) {
				long key = key(x, z);
				Long stamp = regions.get(key);
				if (stamp == null) {
					// Recreated regions must never revive a cache key from before eviction.
					stamp = ++sequence;
					regions.put(key, stamp);
					if (regions.size() > CAPACITY) regions.pollFirstEntry();
				}
				revision = Math.max(revision, stamp);
			}
		}
		return revision;
	}

	public synchronized void recordMutation(int blockX, int blockZ) {
		if (regions.isEmpty()) return;
		long key = key(blockX >> REGION_SHIFT, blockZ >> REGION_SHIFT);
		if (regions.get(key) != null) regions.put(key, ++sequence);
	}

	synchronized int retainedRegions() {
		return regions.size();
	}

	private static long key(int regionX, int regionZ) {
		return ((long) regionX << 32) | (regionZ & 0xffffffffL);
	}
}
