package dev.agaminggod.arenaagents.server.voice;

import dev.agaminggod.arenaagents.agent.AgentId;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

public final class NoVoiceSubsystem implements VoiceSubsystem {
	public static final NoVoiceSubsystem INSTANCE = new NoVoiceSubsystem();

	private NoVoiceSubsystem() {
	}

	@Override
	public boolean available() {
		return false;
	}

	@Override
	public void registerAgent(AgentId agentId, UUID entityId) {
	}

	@Override
	public void unregisterAgent(AgentId agentId) {
	}

	@Override
	public CompletionStage<VoiceReceipt> speak(VoiceRequest request) {
		return CompletableFuture.completedFuture(VoiceReceipt.degraded("Voice addon is not installed"));
	}

	@Override
	public void stop(AgentId agentId) {
	}

	@Override
	public void cancelHumanSpeech(UUID playerId) {
	}

	@Override
	public void close() {
	}
}
