package dev.agaminggod.arenaagents.mixin;

import dev.agaminggod.arenaagents.world.WorldMutationRevisionAccess;
import dev.agaminggod.arenaagents.world.WorldMutationRevisions;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;

/** Invalidates cached perception samples when a level successfully changes a block. */
@Mixin(Level.class)
abstract class LevelMutationRevisionMixin implements WorldMutationRevisionAccess {
	@Unique
	private final WorldMutationRevisions arenaagents$worldMutationRevisions = new WorldMutationRevisions();

	@Override
	public void arenaagents$recordWorldMutation(BlockPos position) {
		arenaagents$worldMutationRevisions.recordMutation(position.getX(), position.getZ());
	}

	@Override
	public long arenaagents$worldMutationRevision(BlockPos center, int radius) {
		return arenaagents$worldMutationRevisions.revision(center.getX(), center.getZ(), radius);
	}
}
