package dev.agaminggod.arenaagents.server.voice;

import dev.agaminggod.arenaagents.agent.AgentDomainException;
import dev.agaminggod.arenaagents.agent.AgentId;
import dev.agaminggod.arenaagents.server.CodexAgentManager;
import dev.agaminggod.arenaagents.server.SkitModeRuntime;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import net.minecraft.server.MinecraftServer;

/** Server-side director for deterministic, world-persisted skit speech cues. */
public final class VoiceDirector {
	private static final Map<MinecraftServer, Map<AgentId, Playback>> PLAYBACK = new ConcurrentHashMap<>();

	private VoiceDirector() {
	}

	public static VoiceProfile profile(MinecraftServer server, AgentId agentId) {
		return VoiceDirectorSavedData.get(server).profile(agentId);
	}

	public static VoiceProfile setProfile(MinecraftServer server, AgentId agentId, VoiceProfile profile) {
		VoiceDirectorSavedData data = VoiceDirectorSavedData.get(server);
		data.putProfile(agentId, profile);
		return profile;
	}

	public static VoiceScript createScript(MinecraftServer server, String name, String agentSelector) {
		SkitModeRuntime.requireEnabled(server);
		VoiceScript script = new VoiceScript(name, agentSelector, java.util.List.of());
		VoiceDirectorSavedData.get(server).putScript(script);
		return script;
	}

	public static VoiceScript addCue(MinecraftServer server, String name, VoiceCue cue) {
		SkitModeRuntime.requireEnabled(server);
		VoiceDirectorSavedData data = VoiceDirectorSavedData.get(server);
		VoiceScript current = Optional.ofNullable(data.script(name))
				.orElseThrow(() -> new AgentDomainException("VOICE_SCRIPT_NOT_FOUND", "No voice script named " + name));
		VoiceScript updated = current.append(cue);
		data.putScript(updated);
		return updated;
	}

	public static VoiceScript play(CodexAgentManager manager, String name, String selectorOverride) {
		SkitModeRuntime.requireEnabled(manager.server());
		VoiceDirectorSavedData data = VoiceDirectorSavedData.get(manager.server());
		VoiceScript script = Optional.ofNullable(data.script(name))
				.orElseThrow(() -> new AgentDomainException("VOICE_SCRIPT_NOT_FOUND", "No voice script named " + name));
		if (script.cues().isEmpty()) throw new AgentDomainException("VOICE_SCRIPT_EMPTY", "Voice script has no cues");
		String selector = selectorOverride == null || selectorOverride.isBlank() ? script.agentSelector() : selectorOverride;
		AgentId agentId = manager.resolve(selector).agentId();
		PLAYBACK.computeIfAbsent(manager.server(), ignored -> new ConcurrentHashMap<>())
				.put(agentId, new Playback(script.cues(), 0,
					manager.server().getTickCount() + script.cues().getFirst().delayTicks()));
		return script;
	}

	/** Schedules one line immediately, using the persisted profile for the agent. */
	public static void say(MinecraftServer server, AgentId agentId, String text) {
		VoiceProfile profile = profile(server, agentId);
		speak(server, agentId, text, profile);
	}

	public static void stop(MinecraftServer server, AgentId agentId) {
		Optional.ofNullable(PLAYBACK.get(server)).ifPresent(runs -> runs.remove(agentId));
		VoiceSubsystemRuntime.stopSpeaking(server, agentId);
	}

	public static void stopAll(MinecraftServer server) {
		Map<AgentId, Playback> runs = PLAYBACK.remove(server);
		if (runs != null) runs.keySet().forEach(agentId -> VoiceSubsystemRuntime.stopSpeaking(server, agentId));
	}

	public static void tick(MinecraftServer server) {
		if (!SkitModeRuntime.enabled(server)) {
			stopAll(server);
			return;
		}
		Map<AgentId, Playback> runs = PLAYBACK.get(server);
		if (runs == null || runs.isEmpty()) return;
		long tick = server.getTickCount();
		for (var entry : runs.entrySet()) {
			Playback playback = entry.getValue();
			if (tick < playback.nextTick()) continue;
			if (playback.index() >= playback.cues().size()) {
				runs.remove(entry.getKey(), playback);
				continue;
			}
			VoiceCue cue = playback.cues().get(playback.index());
			VoiceProfile profile = profile(server, entry.getKey());
			if (!cue.profileId().isEmpty() || !cue.tone().isEmpty() || cue.speed() >= 0.0D || cue.radius() > 0) {
				profile = new VoiceProfile(
						cue.profileId().isEmpty() ? profile.profileId() : cue.profileId(),
						cue.tone().isEmpty() ? profile.tone() : cue.tone(),
						cue.speed() < 0.0D ? profile.speed() : cue.speed(),
						cue.radius() == 0 ? profile.radius() : cue.radius());
			}
			speak(server, entry.getKey(), cue.text(), profile);
			int nextIndex = playback.index() + 1;
			if (nextIndex >= playback.cues().size()) runs.remove(entry.getKey(), playback);
			else runs.replace(entry.getKey(), playback,
					new Playback(playback.cues(), nextIndex, tick + playback.cues().get(nextIndex).delayTicks()));
		}
	}

	public static void release(MinecraftServer server) {
		stopAll(server);
	}

	private static void speak(MinecraftServer server, AgentId agentId, String text, VoiceProfile profile) {
		long sequence = VoiceSubsystemRuntime.nextConversationSequence(server, agentId);
		VoiceSubsystemRuntime.speak(server, new VoiceRequest(
				agentId, text, profile.profileId(), profile.radius(), sequence, profile.speed(), profile.tone()));
	}

	private record Playback(java.util.List<VoiceCue> cues, int index, long nextTick) {
		private Playback { cues = java.util.List.copyOf(cues); }
	}
}
