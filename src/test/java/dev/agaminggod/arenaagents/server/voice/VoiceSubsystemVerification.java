package dev.agaminggod.arenaagents.server.voice;

import dev.agaminggod.arenaagents.agent.AgentId;
import dev.agaminggod.arenaagents.server.CodexAgentServerRuntime;
import dev.agaminggod.arenaagents.server.conversation.ServerAgentConversationRouter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

public final class VoiceSubsystemVerification {
	private VoiceSubsystemVerification() {
	}

	public static int verify() {
		int assertions = 0;
		UUID humanPlayer = UUID.randomUUID();
		VoiceConsentRegistry.clear(null);
		if (VoiceConsentRegistry.granted(null, humanPlayer)) {
			throw new AssertionError("Human proximity transcription must default to disabled");
		}
		assertions++;
		VoiceConsentRegistry.playerConnected(null, humanPlayer);
		if (!VoiceConsentRegistry.granted(null, humanPlayer)) {
			throw new AssertionError("Joining must make agent voice transcription ready without a command");
		}
		assertions++;
		VoiceConsentRegistry.revoke(null, humanPlayer);
		if (VoiceConsentRegistry.granted(null, humanPlayer)) {
			throw new AssertionError("Explicit voice opt-out must remain effective for the connection");
		}
		assertions++;
		VoiceConsentRegistry.playerConnected(null, humanPlayer);
		VoiceConsentRegistry.playerDisconnected(null, humanPlayer);
		if (VoiceConsentRegistry.granted(null, humanPlayer)) {
			throw new AssertionError("Disconnect must revoke consent before a later connection");
		}
		assertions++;
		if (!CodexAgentServerRuntime.deliverHumanSpeech(null, humanPlayer, "must stay private", false)
				.deliveredIds().isEmpty()) {
			throw new AssertionError("Revoked consent must fence buffered or in-flight speech delivery");
		}
		assertions++;
		var capturedAudience = new ServerAgentConversationRouter.ProximitySpeechAudience(
				humanPlayer, net.minecraft.world.level.Level.OVERWORLD, List.of(firstAgentId())
		);
		if (!CodexAgentServerRuntime.deliverHumanSpeech(null, capturedAudience, "still private")
				.deliveredIds().isEmpty()) {
			throw new AssertionError("Revoked consent must fence delivery through a captured speech-time audience");
		}
		assertions++;
		if (VoiceConsentRegistry.captureWhileGranted(null, humanPlayer, () -> {
			throw new AssertionError("Revoked consent must fence a racing microphone packet");
		})) {
			throw new AssertionError("Revoked consent must reject new capture work");
		}
		assertions++;
		VoiceConsentRegistry.grant(null, humanPlayer);
		VoiceConsentRegistry.clear(null);
		if (VoiceConsentRegistry.granted(null, humanPlayer)) {
			throw new AssertionError("A server shutdown must clear prior consent");
		}
		assertions++;
		AgentId first = AgentId.parse("00000000-0000-0000-0000-000000000001");
		AgentId second = AgentId.parse("00000000-0000-0000-0000-000000000002");
		VoiceRequest request = new VoiceRequest(first, "Hello nearby.", "voice.moss.v1", 48, 1L);
		if (NoVoiceSubsystem.INSTANCE.speak(request).toCompletableFuture().join().status()
				!= VoiceReceipt.Status.DEGRADED_TO_TEXT) throw new AssertionError("No voice must degrade to text");
		assertions++;
		try {
			new VoiceRequest(first, "x".repeat(281), "voice.moss.v1", 48, 1L);
			throw new AssertionError("Overlong voice text must fail");
		} catch (IllegalArgumentException expected) {
			assertions++;
		}

		RecordingSubsystem subsystem = new RecordingSubsystem();
		VoiceRegistrationTracker tracker = new VoiceRegistrationTracker();
		UUID firstEntity = UUID.randomUUID();
		UUID respawnedEntity = UUID.randomUUID();
		Map<AgentId, UUID> live = new LinkedHashMap<>();
		live.put(first, firstEntity);
		live.put(second, UUID.randomUUID());
		tracker.reconcile(live, subsystem);
		if (subsystem.registered.size() != 2) throw new AssertionError("Live agents must register once");
		assertions++;
		if (!tracker.containsEntity(firstEntity) || tracker.containsEntity(UUID.randomUUID())) {
			throw new AssertionError("Voice input must identify only registered agent players");
		}
		assertions++;
		tracker.reconcile(live, subsystem);
		if (subsystem.registered.size() != 2) throw new AssertionError("Registration must be idempotent");
		assertions++;
		if (!subsystem.refreshed.equals(List.of(first, second))) {
			throw new AssertionError("Unchanged agents must refresh recoverable voice registration");
		}
		assertions++;
		live.put(first, respawnedEntity);
		live.remove(second);
		tracker.reconcile(live, subsystem);
		if (!subsystem.unregistered.equals(List.of(second, first))) {
			throw new AssertionError("Removal and respawn must unregister old channels");
		}
		assertions++;
		tracker.clear(subsystem);
		if (!subsystem.unregistered.equals(List.of(second, first, first))) {
			throw new AssertionError("Shutdown must unregister the remaining channel");
		}
		return assertions + 1 + VoiceSubsystemRuntimeVerification.verify();
	}

	private static AgentId firstAgentId() {
		return AgentId.parse("00000000-0000-0000-0000-000000000001");
	}

	private static final class RecordingSubsystem implements VoiceSubsystem {
		private final List<AgentId> registered = new ArrayList<>();
		private final List<AgentId> refreshed = new ArrayList<>();
		private final List<AgentId> unregistered = new ArrayList<>();

		@Override public boolean available() { return true; }
		@Override public void registerAgent(AgentId agentId, UUID entityId) { registered.add(agentId); }
		@Override public void refreshAgent(AgentId agentId, UUID entityId) { refreshed.add(agentId); }
		@Override public void unregisterAgent(AgentId agentId) { unregistered.add(agentId); }
		@Override public CompletionStage<VoiceReceipt> speak(VoiceRequest request) {
			return CompletableFuture.completedFuture(VoiceReceipt.accepted());
		}
		@Override public void stop(AgentId agentId) { }
		@Override public void cancelHumanSpeech(UUID playerId) { }
		@Override public void close() { }
	}
}
