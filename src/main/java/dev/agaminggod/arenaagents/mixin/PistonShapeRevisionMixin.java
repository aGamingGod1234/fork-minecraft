package dev.agaminggod.arenaagents.mixin;

import dev.agaminggod.arenaagents.world.WorldMutationRevisionAccess;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.piston.PistonMovingBlockEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Piston progress changes the visual shape between block replacements. */
@Mixin(PistonMovingBlockEntity.class)
abstract class PistonShapeRevisionMixin {
	@Inject(method = "tick", at = @At("RETURN"))
	private static void arenaagents$recordShapeMutation(
			Level level, BlockPos position, BlockState state, PistonMovingBlockEntity piston, CallbackInfo callback
	) {
		if (piston.getProgress(1.0F) != piston.getProgress(0.0F)) {
			((WorldMutationRevisionAccess) level).arenaagents$recordWorldMutation(position);
		}
	}
}
