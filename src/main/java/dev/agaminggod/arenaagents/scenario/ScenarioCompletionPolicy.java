package dev.agaminggod.arenaagents.scenario;

import dev.agaminggod.arenaagents.agent.AgentLifecycleState;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/** Server-verified scenario completion criteria that do not direct contestant actions. */
public final class ScenarioCompletionPolicy {
	private ScenarioCompletionPolicy() {
	}

	public static Optional<String> finishReason(
			ScenarioCategory category,
			List<ParticipantState> participants,
			boolean parkourFinished
	) {
		Objects.requireNonNull(category, "category must not be null");
		List<ParticipantState> states = List.copyOf(Objects.requireNonNull(participants, "participants must not be null"));
		if (states.isEmpty()) return Optional.empty();
		return switch (category) {
			case SURVIVAL -> allCompleted(states) ? Optional.of("All selected models completed the survival objective") : Optional.empty();
			case BUILDING -> allCompleted(states) ? Optional.of("All selected models completed the building objective") : Optional.empty();
			case PARKOUR -> parkourFinished ? Optional.of("Every participant reached the final parkour checkpoint") : Optional.empty();
			case PVP -> pvpFinishReason(states);
		};
	}

	private static boolean allCompleted(List<ParticipantState> participants) {
		return participants.stream().allMatch(participant -> participant.lifecycle() == AgentLifecycleState.COMPLETED);
	}

	private static Optional<String> pvpFinishReason(List<ParticipantState> participants) {
		Set<String> remainingSides = new HashSet<>();
		for (ParticipantState participant : participants) {
			if (!participant.alive()) continue;
			remainingSides.add(participant.team().orElse("participant:" + participant.agentId()));
		}
		if (remainingSides.isEmpty()) return Optional.of("No PvP contestant remains alive");
		if (remainingSides.size() == 1) return Optional.of("One PvP participant or team remains alive");
		return Optional.empty();
	}

	public record ParticipantState(
			String agentId,
			Optional<String> team,
			AgentLifecycleState lifecycle,
			boolean alive
	) {
		public ParticipantState {
			agentId = ScenarioValidators.id(agentId, "agent id");
			team = ScenarioValidators.optionalText(team, "participant team", 48);
			lifecycle = Objects.requireNonNull(lifecycle, "lifecycle must not be null");
		}
	}
}
