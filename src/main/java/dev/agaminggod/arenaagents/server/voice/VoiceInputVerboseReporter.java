package dev.agaminggod.arenaagents.server.voice;

import dev.agaminggod.arenaagents.agent.AgentId;
import dev.agaminggod.arenaagents.agent.AgentRecord;
import dev.agaminggod.arenaagents.server.AgentVerboseChat;
import dev.agaminggod.arenaagents.server.CodexAgentManager;
import dev.agaminggod.arenaagents.server.conversation.ServerAgentConversationRouter.ProximitySpeechAudience;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import net.minecraft.server.MinecraftServer;

/** Publishes bounded, operator-only voice input milestones without transcript content. */
public final class VoiceInputVerboseReporter {
	private VoiceInputVerboseReporter() {
	}

	public static void reportAudience(
			MinecraftServer server,
			ProximitySpeechAudience audience,
			String message
	) {
		Objects.requireNonNull(server, "server must not be null");
		Objects.requireNonNull(audience, "audience must not be null");
		Objects.requireNonNull(message, "message must not be null");
		CodexAgentManager manager = CodexAgentManager.get(server);
		Map<AgentId, AgentRecord> records = new LinkedHashMap<>();
		for (AgentRecord record : manager.records()) records.put(record.agentId(), record);
		for (AgentId agentId : audience.recipientAgentIds()) {
			AgentRecord record = records.get(agentId);
			if (record != null) AgentVerboseChat.report(manager, record, "voice", message);
		}
	}

	public static void reportSystem(MinecraftServer server, String message) {
		AgentVerboseChat.reportSystem(
				Objects.requireNonNull(server, "server must not be null"),
				"voice",
				Objects.requireNonNull(message, "message must not be null")
		);
	}
}
