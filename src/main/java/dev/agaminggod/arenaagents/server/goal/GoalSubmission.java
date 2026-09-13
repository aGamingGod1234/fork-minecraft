package dev.agaminggod.arenaagents.server.goal;

import dev.agaminggod.arenaagents.agent.AgentTransition;
import java.util.Objects;
import java.util.Optional;

public record GoalSubmission(
		Optional<AgentTransition> transition,
		Optional<PendingGoalDraft> pendingDraft
) {
	public GoalSubmission {
		transition = Objects.requireNonNull(transition, "transition must not be null");
		pendingDraft = Objects.requireNonNull(pendingDraft, "pendingDraft must not be null");
		if (transition.isPresent() == pendingDraft.isPresent()) {
			throw new IllegalArgumentException("A goal submission must be either activated or pending translation");
		}
	}

	public static GoalSubmission activated(AgentTransition transition) {
		return new GoalSubmission(Optional.of(Objects.requireNonNull(transition, "transition must not be null")), Optional.empty());
	}

	public static GoalSubmission pending(PendingGoalDraft draft) {
		return new GoalSubmission(Optional.empty(), Optional.of(Objects.requireNonNull(draft, "draft must not be null")));
	}

	public enum Operation {
		START,
		QUEUE
	}
}
