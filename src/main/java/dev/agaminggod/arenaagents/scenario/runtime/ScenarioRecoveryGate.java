package dev.agaminggod.arenaagents.scenario.runtime;

import dev.agaminggod.arenaagents.agent.AgentLifecycleState;
import dev.agaminggod.arenaagents.server.bridge.CoordinatorStatusSnapshot;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/** Pure coordinator/player/lifecycle gate used before a recovered scenario clock may resume. */
public final class ScenarioRecoveryGate {
	private ScenarioRecoveryGate() {
	}

	public static boolean coordinatorReady(
			Optional<CoordinatorStatusSnapshot> status,
			List<String> boundAgentIds,
			long nowEpochMs,
			long maximumAgeMs
	) {
		Objects.requireNonNull(status, "status must not be null");
		List<String> bound = List.copyOf(Objects.requireNonNull(boundAgentIds, "boundAgentIds must not be null"));
		if (bound.isEmpty() || new HashSet<>(bound).size() != bound.size()) {
			throw new IllegalArgumentException("boundAgentIds must be nonempty and unique");
		}
		if (nowEpochMs < 0L || maximumAgeMs < 0L) throw new IllegalArgumentException("coordinator clock is invalid");
		CoordinatorStatusSnapshot snapshot = status.orElse(null);
		if (snapshot == null || !snapshot.reconciled() || !snapshot.fresh(nowEpochMs, maximumAgeMs)) return false;
		HashSet<String> supportedAgentIds = new HashSet<>();
		for (CoordinatorStatusSnapshot.SupportedProfile profile : snapshot.profiles()) {
			supportedAgentIds.add(profile.agentId());
		}
		return supportedAgentIds.containsAll(bound);
	}

	public static Decision evaluate(boolean coordinatorReady, List<AgentStatus> agents) {
		List<AgentStatus> copied = List.copyOf(Objects.requireNonNull(agents, "agents must not be null"));
		if (copied.isEmpty()) throw new IllegalArgumentException("recovery roster must not be empty");
		HashSet<String> ids = new HashSet<>();
		for (AgentStatus agent : copied) {
			if (!ids.add(agent.agentId())) throw new IllegalArgumentException("duplicate recovery agent " + agent.agentId());
		}
		if (!coordinatorReady) {
			return new Decision(false, List.of(), "COORDINATOR_NOT_RECONCILED");
		}
		List<String> resume = copied.stream()
				.filter(AgentStatus::playerReady)
				.filter(agent -> agent.state() == AgentLifecycleState.PAUSED)
				.map(AgentStatus::agentId)
				.sorted()
				.toList();
		boolean ready = resume.isEmpty() && copied.stream()
				.allMatch(agent -> agent.playerReady() && agent.state().isActive());
		return new Decision(ready, resume, ready ? "" : "AGENTS_NOT_ACTIVE");
	}

	public static List<String> deadAgentIds(List<AgentStatus> agents) {
		List<AgentStatus> copied = List.copyOf(Objects.requireNonNull(agents, "agents must not be null"));
		return copied.stream()
				.filter(agent -> agent.state() == AgentLifecycleState.DEAD)
				.map(AgentStatus::agentId)
				.sorted()
				.toList();
	}

	public record AgentStatus(String agentId, AgentLifecycleState state, boolean playerReady) {
		public AgentStatus {
			Objects.requireNonNull(agentId, "agentId must not be null");
			agentId = agentId.strip();
			if (agentId.isEmpty() || agentId.length() > 96) throw new IllegalArgumentException("agentId is invalid");
			state = Objects.requireNonNull(state, "state must not be null");
		}
	}

	public record Decision(boolean ready, List<String> resumeAgentIds, String reason) {
		public Decision {
			resumeAgentIds = List.copyOf(Objects.requireNonNull(resumeAgentIds, "resumeAgentIds must not be null"));
			reason = Objects.requireNonNull(reason, "reason must not be null");
			if (ready && (!resumeAgentIds.isEmpty() || !reason.isEmpty())) {
				throw new IllegalArgumentException("ready recovery decision must be clear");
			}
		}
	}
}
