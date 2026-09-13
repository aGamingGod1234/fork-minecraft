package dev.agaminggod.arenaagents.server.bridge;

import dev.agaminggod.arenaagents.agent.AgentDomainException;
import dev.agaminggod.arenaagents.agent.AgentId;
import dev.agaminggod.arenaagents.server.runtime.ActionProvenance;
import dev.agaminggod.arenaagents.server.runtime.ServerActionRequest;
import dev.agaminggod.arenaagents.server.runtime.ServerActionResult;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;

/** Bounded action diagnostics backed by a full replay fence for each active goal. */
final class ProgramActionLedger {
	private static final int MAX_TRACKED_ACTIONS_PER_AGENT = 4_096;
	private final Map<AgentId, LinkedHashMap<String, ActionProvenance>> accepted = new HashMap<>();
	private final Map<AgentId, LinkedHashMap<String, ActionProvenance>> terminal = new HashMap<>();
	private final Map<AgentId, LinkedHashMap<ActionProvenance, String>> actionIdsByProgramStep = new HashMap<>();
	private final Map<AgentId, GoalExecutionFence> executionFences = new HashMap<>();

	synchronized void beginGoal(AgentId agentId, long goalRevision) {
		GoalExecutionFence current = executionFences.get(agentId);
		if (current != null && current.goalRevision() == goalRevision) return;
		executionFences.put(agentId, new GoalExecutionFence(goalRevision, new HashMap<>(), new HashMap<>()));
		accepted.remove(agentId);
		terminal.remove(agentId);
		actionIdsByProgramStep.remove(agentId);
	}

	synchronized void accept(ServerActionRequest request) {
		beginGoal(request.agentId(), request.goalRevision());
		GoalExecutionFence fence = executionFences.get(request.agentId());
		ActionProvenance prior = fence.provenanceByActionId().get(request.actionId());
		if (prior != null) {
			if (!prior.equals(request.provenance())) {
				throw new AgentDomainException("ACTION_PROVENANCE_MISMATCH", "Action ID is already bound to a different program step");
			}
			throw new AgentDomainException("ACTION_REPLAY", "Action ID has already been accepted");
		}
		String priorActionId = fence.actionIdByProgramStep().get(request.provenance());
		if (priorActionId != null) throw new AgentDomainException("ACTION_REPLAY", "Program step is already bound to action ID " + priorActionId);
		fence.provenanceByActionId().put(request.actionId(), request.provenance());
		fence.actionIdByProgramStep().put(request.provenance(), request.actionId());
		LinkedHashMap<ActionProvenance, String> actionIds = actionIdsByProgramStep.computeIfAbsent(request.agentId(), ignored -> new LinkedHashMap<>());
		actionIds.put(request.provenance(), request.actionId());
		trim(actionIds);
		LinkedHashMap<String, ActionProvenance> entries = accepted.computeIfAbsent(request.agentId(), ignored -> new LinkedHashMap<>());
		entries.put(request.actionId(), request.provenance());
		trim(entries);
	}

	synchronized void terminal(ServerActionResult result) {
		GoalExecutionFence fence = executionFences.get(result.agentId());
		if (fence == null || fence.goalRevision() != result.goalRevision()) return;
		LinkedHashMap<String, ActionProvenance> entries = accepted.get(result.agentId());
		if (entries == null) return;
		ActionProvenance provenance = entries.remove(result.actionId());
		if (provenance == null) return;
		LinkedHashMap<String, ActionProvenance> completed = terminal.computeIfAbsent(result.agentId(), ignored -> new LinkedHashMap<>());
		completed.put(result.actionId(), provenance);
		trim(completed);
	}

	synchronized void rollback(ServerActionRequest request) {
		GoalExecutionFence fence = executionFences.get(request.agentId());
		if (fence == null || fence.goalRevision() != request.goalRevision()) return;
		LinkedHashMap<String, ActionProvenance> entries = accepted.get(request.agentId());
		if (entries == null || !request.provenance().equals(entries.get(request.actionId()))) return;
		entries.remove(request.actionId());
		LinkedHashMap<ActionProvenance, String> actionIds = actionIdsByProgramStep.get(request.agentId());
		if (actionIds != null) actionIds.remove(request.provenance(), request.actionId());
		fence.provenanceByActionId().remove(request.actionId(), request.provenance());
		fence.actionIdByProgramStep().remove(request.provenance(), request.actionId());
	}

	synchronized void remove(AgentId agentId) {
		accepted.remove(agentId);
		terminal.remove(agentId);
		actionIdsByProgramStep.remove(agentId);
		executionFences.remove(agentId);
	}

	private static void trim(LinkedHashMap<?, ?> entries) {
		while (entries.size() > MAX_TRACKED_ACTIONS_PER_AGENT) entries.remove(entries.keySet().iterator().next());
	}

	private record GoalExecutionFence(
			long goalRevision,
			Map<String, ActionProvenance> provenanceByActionId,
			Map<ActionProvenance, String> actionIdByProgramStep
	) { }
}
