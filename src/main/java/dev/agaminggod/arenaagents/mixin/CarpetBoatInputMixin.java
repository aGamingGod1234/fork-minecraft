package dev.agaminggod.arenaagents.mixin;

import dev.agaminggod.arenaagents.server.runtime.input.ModelPlayerInputBridge;
import net.minecraft.world.entity.vehicle.boat.AbstractBoat;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(AbstractBoat.class)
abstract class CarpetBoatInputMixin {
	@Shadow private void controlBoat() { throw new AssertionError(); }

	@Inject(method = "tick", at = @At(value = "INVOKE",
			target = "Lnet/minecraft/world/entity/vehicle/boat/AbstractBoat;floatBoat()V", shift = At.Shift.AFTER))
	private void arenaagents$applyAuthoredPaddles(CallbackInfo callback) {
		if (ModelPlayerInputBridge.updateBoatInputs((AbstractBoat) (Object) this)) controlBoat();
	}
}
