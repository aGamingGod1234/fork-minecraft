package dev.agaminggod.arenaagents.server;

import dev.agaminggod.arenaagents.agent.AgentDeathSnapshot;
import dev.agaminggod.arenaagents.agent.AgentLifecycleState;
import dev.agaminggod.arenaagents.agent.AgentRecord;
import dev.agaminggod.arenaagents.agent.AgentRegistry;
import java.util.Objects;
import java.util.UUID;

public final class AgentDeathCapture {
	private AgentDeathCapture() {
	}

	public static boolean record(
			AgentRegistry registry,
			UUID playerUuid,
			AgentDeathSnapshot snapshot,
			long nowEpochMs
	) {
		Objects.requireNonNull(registry, "registry must not be null");
		Objects.requireNonNull(playerUuid, "playerUuid must not be null");
		Objects.requireNonNull(snapshot, "snapshot must not be null");
		for (AgentRecord record : registry.records()) {
			if (!OfflineAgentPlayers.offlineUuid(record.agentId(), record.profile()).equals(playerUuid)) continue;
			if (record.state() != AgentLifecycleState.DEAD) {
				registry.die(record.agentId(), snapshot, nowEpochMs);
			}
			return true;
		}
		return false;
	}

	public static boolean allowVanillaDeath(boolean recoveredByScenario, Runnable recordDeath) {
		Objects.requireNonNull(recordDeath, "recordDeath must not be null");
		if (recoveredByScenario) return false;
		recordDeath.run();
		return true;
	}
}
