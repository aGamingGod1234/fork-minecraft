package dev.agaminggod.arenaagents.server.conversation;

public interface AgentConversationRouter {
	DeliveryReceipt deliver(ConversationEvent event);
}
