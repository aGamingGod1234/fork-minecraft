package dev.agaminggod.arenaagents.world;

import net.minecraft.core.BlockPos;

/** Exposes regional mutation revisions maintained on each Minecraft level. */
public interface WorldMutationRevisionAccess {
	long arenaagents$worldMutationRevision(BlockPos center, int radius);

	void arenaagents$recordWorldMutation(BlockPos position);
}
