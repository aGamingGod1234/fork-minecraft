package dev.agaminggod.arenaagents.client.mixin;

import dev.agaminggod.arenaagents.client.control.AgentControlClient;
import dev.agaminggod.arenaagents.client.render.CodexAgentRenderer;
import dev.agaminggod.arenaagents.agent.AgentIdentity;
import dev.agaminggod.arenaagents.agent.AgentVisualIdentity;
import dev.agaminggod.arenaagents.control.AgentControlAgent;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.core.ClientAsset;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.player.PlayerModelType;
import net.minecraft.world.entity.player.PlayerSkin;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(AbstractClientPlayer.class)
abstract class AbstractClientPlayerMixin {
	@Inject(method = "getSkin", at = @At("HEAD"), cancellable = true)
	private void arenaagents$agentSkin(CallbackInfoReturnable<PlayerSkin> callback) {
		AbstractClientPlayer player = (AbstractClientPlayer) (Object) this;
		String profileName = player.getGameProfile().name();
		AgentControlAgent agent = AgentControlClient.agentForPlayer(profileName).orElse(null);
		AgentVisualIdentity.Resolved identity;
		if (agent != null) {
			// The UUID check prevents a similarly named human player from inheriting
			// an agent's skin when the roster snapshot is already available.
			if (!player.getUUID().equals(AgentIdentity.offlinePlayerUuid(agent.playerName()))) return;
			identity = AgentVisualIdentity.resolve(agent.provider(), agent.model(), agent.skinVariant());
		} else {
			// A fake player can render before the first roster snapshot reaches the
			// client. The technical transport/recognizable name still carries enough
			// signed identity to select its persisted skin during that gap.
			AgentIdentity.SkinIdentity fallback = AgentIdentity.skinForPlayerName(profileName).orElse(null);
			if (fallback == null || !player.getUUID().equals(AgentIdentity.offlinePlayerUuid(profileName))) return;
			identity = fallback.modelFamily().isBlank()
					? AgentVisualIdentity.resolveProviderFallback(fallback.provider(), fallback.variant())
					: AgentVisualIdentity.resolveFamily(fallback.provider(), fallback.modelFamily(), fallback.variant());
		}
		Identifier texture = CodexAgentRenderer.textureFor(identity);
		ClientAsset.Texture body = new ClientAsset.ResourceTexture(texture, texture);
		callback.setReturnValue(new PlayerSkin(body, null, null, PlayerModelType.WIDE, false));
	}

}
