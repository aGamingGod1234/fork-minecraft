package dev.agaminggod.arenaagents.mixin;

import carpet.patches.EntityPlayerMPFake;
import com.mojang.authlib.GameProfile;
import dev.agaminggod.arenaagents.server.CodexAgentManager;
import dev.agaminggod.arenaagents.server.OfflineAgentProfileLookup;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import net.minecraft.server.MinecraftServer;
import net.minecraft.network.chat.Component;
import net.minecraft.server.players.OldUsersConverter;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(value = EntityPlayerMPFake.class, remap = false)
abstract class EntityPlayerMPFakeMixin {
	@Redirect(
			method = "createFake",
			at = @At(
					value = "INVOKE",
					target = "Lnet/minecraft/server/players/OldUsersConverter;convertMobOwnerIfNecessary(Lnet/minecraft/server/MinecraftServer;Ljava/lang/String;)Ljava/util/UUID;",
					remap = true
			),
			remap = false
	)
	private static UUID arenaagents$keepRequestedOfflineIdentity(MinecraftServer server, String name) {
		return OfflineAgentProfileLookup.requestedUuid(name)
				.orElseGet(() -> OldUsersConverter.convertMobOwnerIfNecessary(server, name));
	}

	@Inject(method = "fetchGameProfile", at = @At("HEAD"), cancellable = true)
	private static void arenaagents$skipRemoteProfileLookup(
			MinecraftServer server,
			UUID uuid,
			CallbackInfoReturnable<CompletableFuture<GameProfile>> callback
	) {
		if (OfflineAgentProfileLookup.shouldBypassRemoteLookup(uuid)) {
			callback.setReturnValue(CompletableFuture.completedFuture(new GameProfile(uuid, "")));
		}
	}

	@Redirect(
			method = "die",
			at = @At(
					value = "INVOKE",
					target = "Lcarpet/patches/EntityPlayerMPFake;kill(Lnet/minecraft/network/chat/Component;)V",
					remap = false
			),
			remap = false
	)
	private void arenaagents$retainManagedDeath(EntityPlayerMPFake player, Component reason) {
		MinecraftServer server = player.level().getServer();
		if (server != null && CodexAgentManager.get(server).retainConnectedDeath(player)) return;
		player.kill(reason);
	}
}
