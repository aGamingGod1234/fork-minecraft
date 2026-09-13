package dev.agaminggod.arenaagents.server.runtime;

import dev.agaminggod.arenaagents.agent.AgentId;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

/** Server-owned count of successful physical actions, scoped to an agent goal revision. */
public final class ActionSuccessLedger {
	private final Map<AgentId, Map<Long, Map<String, Integer>>> counts = new HashMap<>();

	public synchronized void record(ServerActionResult result) {
		Objects.requireNonNull(result, "result must not be null");
		if (result.state() != ServerActionState.SUCCEEDED || result.actionType() == dev.agaminggod.arenaagents.protocol.ActionType.COMPLETE_GOAL) return;
		counts.computeIfAbsent(result.agentId(), ignored -> new HashMap<>())
				.computeIfAbsent(result.goalRevision(), ignored -> new HashMap<>())
				.merge(result.actionType().wireName(), 1, Integer::sum);
	}

	public synchronized int count(AgentId agentId, long goalRevision, String actionType) {
		return counts.getOrDefault(agentId, Map.of())
				.getOrDefault(goalRevision, Map.of())
				.getOrDefault(actionType, 0);
	}

	public synchronized void clear(AgentId agentId, long goalRevision) {
		Map<Long, Map<String, Integer>> revisions = counts.get(agentId);
		if (revisions == null) return;
		revisions.remove(goalRevision);
		if (revisions.isEmpty()) counts.remove(agentId);
	}

	public synchronized void retainRevision(AgentId agentId, long goalRevision) {
		Map<Long, Map<String, Integer>> revisions = counts.get(
				Objects.requireNonNull(agentId, "agentId must not be null")
		);
		if (revisions == null) return;
		revisions.keySet().removeIf(revision -> revision != goalRevision);
		if (revisions.isEmpty()) counts.remove(agentId);
	}

	public synchronized void clear(AgentId agentId) {
		counts.remove(Objects.requireNonNull(agentId, "agentId must not be null"));
	}
}
