package dev.agaminggod.arenaagents.mixin;

import dev.agaminggod.arenaagents.world.WorldMutationRevisionAccess;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Observe the actual write, including callers that bypass Level.setBlock. */
@Mixin(LevelChunk.class)
abstract class LevelChunkMutationRevisionMixin {
	@Shadow @Final private Level level;

	@Inject(method = "setBlockState", at = @At("RETURN"))
	private void arenaagents$recordBlockMutation(
			BlockPos position, BlockState state, int flags,
			CallbackInfoReturnable<BlockState> callback
	) {
		if (callback.getReturnValue() != null) {
			((WorldMutationRevisionAccess) level).arenaagents$recordWorldMutation(position);
		}
	}
}
