package dev.agaminggod.arenaagents.mixin;

import carpet.helpers.EntityPlayerActionPack;
import net.minecraft.core.BlockPos;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** Exposes Carpet's authoritative block-break state to the server action executor. */
@Mixin(value = EntityPlayerActionPack.class, remap = false)
public interface EntityPlayerActionPackAccessor {
	@Accessor("currentBlock")
	BlockPos arenaagents$getCurrentBlock();

	@Accessor("curBlockDamageMP")
	float arenaagents$getCurrentBlockDamage();
}
