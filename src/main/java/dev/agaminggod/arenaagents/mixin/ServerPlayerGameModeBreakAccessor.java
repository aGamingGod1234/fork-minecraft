package dev.agaminggod.arenaagents.mixin;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerPlayerGameMode;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** Reads the last block destruction committed by this player's vanilla game mode. */
@Mixin(ServerPlayerGameMode.class)
public interface ServerPlayerGameModeBreakAccessor {
	@Accessor("arenaagents$lastDestroyedBlock")
	BlockPos arenaagents$getLastDestroyedBlock();

	@Accessor("arenaagents$lastDestroyedGameTime")
	long arenaagents$getLastDestroyedGameTime();
}
