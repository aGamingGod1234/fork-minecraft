package dev.agaminggod.arenaagents.server.voice;

import dev.agaminggod.arenaagents.agent.AgentId;
import java.util.UUID;
import java.util.concurrent.CompletionStage;

/** The subsystem API shipped by the pre-configuration 0.1.0 core. */
public interface VoiceSubsystem extends AutoCloseable {
	boolean available();

	void registerAgent(AgentId agentId, UUID entityId);

	void unregisterAgent(AgentId agentId);

	CompletionStage<VoiceReceipt> speak(VoiceRequest request);

	void stop(AgentId agentId);

	@Override
	void close();
}
