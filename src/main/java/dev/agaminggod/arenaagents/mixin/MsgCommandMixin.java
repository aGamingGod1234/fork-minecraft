package dev.agaminggod.arenaagents.mixin;

import dev.agaminggod.arenaagents.agent.AgentDomainException;
import dev.agaminggod.arenaagents.server.CodexAgentManager;
import dev.agaminggod.arenaagents.server.CodexAgentServerRuntime;
import dev.agaminggod.arenaagents.server.conversation.MinecraftWhisperDelivery;
import dev.agaminggod.arenaagents.server.conversation.NativeAgentWhisperTargets;
import java.util.Collection;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.network.chat.ChatType;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.OutgoingChatMessage;
import net.minecraft.network.chat.PlayerChatMessage;
import net.minecraft.server.commands.MsgCommand;
import net.minecraft.server.level.ServerPlayer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

@Mixin(MsgCommand.class)
public abstract class MsgCommandMixin {
	@Redirect(
			method = "sendMessage",
			at = @At(
					value = "INVOKE",
					target = "Lnet/minecraft/server/level/ServerPlayer;sendChatMessage(Lnet/minecraft/network/chat/OutgoingChatMessage;ZLnet/minecraft/network/chat/ChatType$Bound;)V"
			)
	)
	private static void arenaagents$routeAgentWhisper(
			ServerPlayer recipient,
			OutgoingChatMessage outgoing,
			boolean filtered,
			ChatType.Bound bound,
			CommandSourceStack source,
			Collection<ServerPlayer> recipients,
			PlayerChatMessage message
	) {
		if (!(source.getEntity() instanceof ServerPlayer sender)) {
			recipient.sendChatMessage(outgoing, filtered, bound);
			return;
		}
		CodexAgentManager manager = CodexAgentManager.get(source.getServer());
		var agentId = NativeAgentWhisperTargets.fromRecords(manager.records()).resolve(recipient.getUUID());
		if (agentId.isEmpty()) {
			recipient.sendChatMessage(outgoing, filtered, bound);
			return;
		}
		try {
			CodexAgentServerRuntime.sendNativeDirectMessage(
					source.getServer(), sender, agentId.orElseThrow(),
					MinecraftWhisperDelivery.nativeText(message, filtered)
			);
		} catch (AgentDomainException exception) {
			source.sendFailure(Component.literal("Agent DM failed: " + exception.getMessage()));
		}
	}
}
