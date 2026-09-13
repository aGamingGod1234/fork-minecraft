package dev.agaminggod.arenaagents.server.runtime;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;

public interface ServerProtectionPolicy {
	ServerProtectionPolicy DENY_ALL = new ServerProtectionPolicy() { };
	ServerProtectionPolicy TRUSTED_LOCAL_OPERATOR = new ServerProtectionPolicy() {
		@Override public boolean mayModifyBlock(ServerPlayer agent, ServerLevel level, BlockPos position) { return true; }
		@Override public boolean mayTakeEntity(ServerPlayer agent, Entity entity) { return true; }
		@Override public boolean mayDropItem(ServerPlayer agent) { return true; }
		@Override public boolean mayUseContainer(ServerPlayer agent, ServerLevel level, BlockPos position) { return true; }
		@Override public boolean mayUseRangedWeapon(ServerPlayer agent, Entity target) { return true; }
		@Override public boolean mayInteractWithBlock(ServerPlayer agent, ServerLevel level, BlockPos position) { return true; }
		@Override public boolean mayInteractWithEntity(ServerPlayer agent, Entity entity) { return true; }
	};

	default boolean mayModifyBlock(ServerPlayer agent, ServerLevel level, BlockPos position) { return false; }
	default boolean mayTakeEntity(ServerPlayer agent, Entity entity) { return false; }
	default boolean mayDropItem(ServerPlayer agent) { return false; }
	default boolean mayUseContainer(ServerPlayer agent, ServerLevel level, BlockPos position) { return false; }
	default boolean mayUseRangedWeapon(ServerPlayer agent, Entity target) { return false; }
	default boolean mayInteractWithBlock(ServerPlayer agent, ServerLevel level, BlockPos position) { return false; }
	default boolean mayInteractWithEntity(ServerPlayer agent, Entity entity) { return false; }
}
