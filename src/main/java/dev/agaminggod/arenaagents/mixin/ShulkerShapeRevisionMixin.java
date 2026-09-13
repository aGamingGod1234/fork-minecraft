package dev.agaminggod.arenaagents.mixin;

import dev.agaminggod.arenaagents.world.WorldMutationRevisionAccess;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.entity.ShulkerBoxBlockEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Shulker animation changes the visual shape without replacing its block state. */
@Mixin(ShulkerBoxBlockEntity.class)
abstract class ShulkerShapeRevisionMixin {
	@Shadow private float progress;
	@Shadow private float progressOld;

	@Inject(method = "updateAnimation", at = @At("RETURN"))
	private void arenaagents$recordShapeMutation(Level level, BlockPos position, BlockState state, CallbackInfo callback) {
		if (progress != progressOld) ((WorldMutationRevisionAccess) level).arenaagents$recordWorldMutation(position);
	}
}
