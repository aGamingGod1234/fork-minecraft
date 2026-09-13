package dev.agaminggod.arenaagents.server;

import dev.agaminggod.arenaagents.agent.AgentId;
import dev.agaminggod.arenaagents.agent.AgentProfile;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

final class PendingSpawnCancellationLedger {
	private final long retentionMs;
	private final Map<AgentId, RetainedCancellation> cancellations = new LinkedHashMap<>();

	PendingSpawnCancellationLedger(long retentionMs) {
		if (retentionMs <= 0L) {
			throw new IllegalArgumentException("retentionMs must be positive");
		}
		this.retentionMs = retentionMs;
	}

	void record(AgentId agentId, AgentProfile profile, long nowEpochMs) {
		Cancellation cancellation = new Cancellation(
				Objects.requireNonNull(agentId, "agentId must not be null"),
				Objects.requireNonNull(profile, "profile must not be null")
		);
		cancellations.put(agentId, new RetainedCancellation(
				cancellation,
				Math.addExact(nowEpochMs, retentionMs)
		));
	}

	List<Cancellation> active(long nowEpochMs) {
		cancellations.entrySet().removeIf(entry -> entry.getValue().expiresAtEpochMs() <= nowEpochMs);
		return cancellations.values().stream().map(RetainedCancellation::cancellation).toList();
	}

	record Cancellation(AgentId agentId, AgentProfile profile) { }

	private record RetainedCancellation(Cancellation cancellation, long expiresAtEpochMs) { }
}
