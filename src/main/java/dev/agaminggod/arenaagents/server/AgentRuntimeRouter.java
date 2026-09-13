package dev.agaminggod.arenaagents.server;

import dev.agaminggod.arenaagents.agent.AgentDomainException;
import dev.agaminggod.arenaagents.agent.AgentId;
import dev.agaminggod.arenaagents.agent.AgentLifecycleState;
import dev.agaminggod.arenaagents.agent.AgentRecord;
import dev.agaminggod.arenaagents.agent.AgentRegistry;
import dev.agaminggod.arenaagents.agent.AgentTransition;
import dev.agaminggod.arenaagents.agent.goal.GoalEvidence;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Revision-gated ingress used by the future multiplexed loopback bridge.
 * Networking, authentication and JSONL framing remain outside this class.
 */
public final class AgentRuntimeRouter {
	private final AgentRegistry registry;

	public AgentRuntimeRouter(CodexAgentManager manager) {
		this.registry = Objects.requireNonNull(manager, "manager must not be null").registry();
	}

	public AgentTransition plannerStarted(AgentId agentId) {
		return registry.beginPlanning(agentId, System.currentTimeMillis());
	}

	public AgentTransition actionAccepted(AgentId agentId, long goalRevision) {
		return registry.beginAction(agentId, goalRevision, System.currentTimeMillis());
	}

	public AgentTransition actionFinished(AgentId agentId, long goalRevision) {
		return registry.actionFinished(agentId, goalRevision, System.currentTimeMillis());
	}

	public AgentTransition goalCompleted(AgentId agentId, long goalRevision) {
		return registry.completeGoal(agentId, goalRevision, System.currentTimeMillis());
	}

	public AgentTransition goalSatisfied(AgentId agentId, long goalRevision, GoalEvidence evidence) {
		return registry.satisfyGoal(agentId, goalRevision, evidence, System.currentTimeMillis());
	}

	public AgentRecord coordinatorCompleted(AgentId agentId, long goalRevision) {
		return registry.coordinatorCompleted(agentId, goalRevision, System.currentTimeMillis());
	}

	public AgentTransition plannerFailed(AgentId agentId, long goalRevision, String error) {
		requireCurrentRevision(agentId, goalRevision);
		return registry.fail(agentId, error, System.currentTimeMillis());
	}

	public boolean isCurrentActiveRevision(AgentId agentId, long goalRevision) {
		return registry.isCurrentActiveRevision(agentId, goalRevision);
	}

	public List<AgentTransition> coordinatorDisconnected() {
		long now = System.currentTimeMillis();
		ArrayList<AgentTransition> transitions = new ArrayList<>();
		for (AgentRecord record : registry.records()) {
			if (record.state().isActive()) {
				transitions.add(registry.disconnect(record.agentId(), now));
			}
		}
		return List.copyOf(transitions);
	}

	private void requireCurrentRevision(AgentId agentId, long goalRevision) {
		AgentRecord record = registry.require(agentId);
		if (!record.acceptsRevision(goalRevision)) {
			throw new AgentDomainException(
					"STALE_REVISION",
					"Rejected stale runtime message for " + agentId.shortValue()
			);
		}
		if (record.state() == AgentLifecycleState.PAUSED) {
			throw new AgentDomainException("AGENT_PAUSED", "Rejected runtime message for paused agent");
		}
	}
}
