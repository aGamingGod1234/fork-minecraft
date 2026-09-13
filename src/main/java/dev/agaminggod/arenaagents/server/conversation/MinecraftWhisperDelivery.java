package dev.agaminggod.arenaagents.server.conversation;

import net.minecraft.network.chat.ChatType;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.OutgoingChatMessage;
import net.minecraft.network.chat.PlayerChatMessage;
import net.minecraft.server.level.ServerPlayer;

public final class MinecraftWhisperDelivery {
	private MinecraftWhisperDelivery() {
	}

	static void send(ServerPlayer source, String sourceName, ServerPlayer recipient, String text) {
		ChatType.Bound incoming = ChatType.bind(
				ChatType.MSG_COMMAND_INCOMING,
				source.registryAccess(),
				Component.literal(sourceName)
		);
		recipient.sendChatMessage(outgoingMessage(text), false, incoming);
	}

	/** Extracts exactly the content vanilla would deliver for the recipient's filter state. */
	public static String nativeText(PlayerChatMessage message, boolean filtered) {
		return message.filter(filtered).decoratedContent().getString();
	}

	static OutgoingChatMessage outgoingMessage(String text) {
		return OutgoingChatMessage.create(PlayerChatMessage.system(text));
	}
}
