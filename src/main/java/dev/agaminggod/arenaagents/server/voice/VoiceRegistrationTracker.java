package dev.agaminggod.arenaagents.server.voice;

import dev.agaminggod.arenaagents.agent.AgentId;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

final class VoiceRegistrationTracker {
	private final Map<AgentId, UUID> registered = new LinkedHashMap<>();

	void reconcile(Map<AgentId, UUID> current, VoiceSubsystem subsystem) {
		Objects.requireNonNull(current, "current must not be null");
		Objects.requireNonNull(subsystem, "subsystem must not be null");
		for (AgentId removed : registered.keySet().stream().filter(id -> !current.containsKey(id)).toList()) {
			subsystem.unregisterAgent(removed);
			registered.remove(removed);
		}
		for (Map.Entry<AgentId, UUID> entry : current.entrySet()) {
			UUID previous = registered.get(entry.getKey());
			if (entry.getValue().equals(previous)) {
				subsystem.refreshAgent(entry.getKey(), entry.getValue());
				continue;
			}
			if (previous != null) subsystem.unregisterAgent(entry.getKey());
			subsystem.registerAgent(entry.getKey(), entry.getValue());
			registered.put(entry.getKey(), entry.getValue());
		}
	}

	void clear(VoiceSubsystem subsystem) {
		for (AgentId agentId : registered.keySet().stream().toList()) subsystem.unregisterAgent(agentId);
		registered.clear();
	}

	boolean containsEntity(UUID entityId) {
		return registered.containsValue(Objects.requireNonNull(entityId, "entityId must not be null"));
	}
}
