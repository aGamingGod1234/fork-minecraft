package dev.agaminggod.arenaagents.mixin;

import carpet.helpers.EntityPlayerActionPack;
import dev.agaminggod.arenaagents.server.runtime.input.CarpetActionArbitration;
import dev.agaminggod.arenaagents.server.runtime.input.ModelPlayerInputBridge;
import java.util.function.Supplier;
import net.minecraft.server.level.ServerPlayer;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(value = EntityPlayerActionPack.class, remap = false)
abstract class EntityPlayerActionPackMixin {
	@Shadow
	@Final
	private ServerPlayer player;

	@Inject(method = "onUpdate", at = @At("TAIL"))
	private void arenaagents$applyAuthoredVehicleInputs(CallbackInfo callback) {
		ModelPlayerInputBridge.tickPlayer(player);
	}

	@Redirect(
			method = "onUpdate",
			at = @At(
					value = "INVOKE",
					target = "Lcarpet/helpers/EntityPlayerActionPack$Action;tick(Lcarpet/helpers/EntityPlayerActionPack;Lcarpet/helpers/EntityPlayerActionPack$ActionType;)Ljava/lang/Boolean;"
			)
	)
	private Boolean arenaagents$arbitrateExactHandUse(
			EntityPlayerActionPack.Action action,
			EntityPlayerActionPack actionPack,
			EntityPlayerActionPack.ActionType actionType
	) {
		Supplier<Boolean> original = () -> ((EntityPlayerActionInvoker) (Object) action)
				.arenaagents$invokeTick(actionPack, actionType);
		if (actionType != EntityPlayerActionPack.ActionType.ATTACK) return original.get();
		// Null is Carpet's skipped-Action.tick result; onUpdate retains continuous actions until Action.done is true.
		return CarpetActionArbitration.arbitrate(player, original);
	}
}
