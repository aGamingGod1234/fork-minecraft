package dev.agaminggod.arenaagents.mixin;

import carpet.helpers.EntityPlayerActionPack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

@Mixin(value = EntityPlayerActionPack.Action.class, remap = false)
public interface EntityPlayerActionInvoker {
	@Invoker("tick")
	Boolean arenaagents$invokeTick(
			EntityPlayerActionPack actionPack,
			EntityPlayerActionPack.ActionType actionType
	);
}
