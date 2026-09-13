package dev.agaminggod.arenaagents.server.goal;

import dev.agaminggod.arenaagents.agent.AgentDomainException;
import java.util.Objects;
import java.util.UUID;

public final class GoalDraftResolution {
	private GoalDraftResolution() {
	}

	public static Operation authorize(
			PendingGoalDraft draft,
			UUID actorId,
			boolean operator,
			GoalDraftChoice choice
	) {
		Objects.requireNonNull(draft, "draft must not be null");
		Objects.requireNonNull(actorId, "actorId must not be null");
		Objects.requireNonNull(choice, "choice must not be null");
		if (!operator && !draft.requestingPlayerId().equals(actorId)) {
			throw new AgentDomainException("GOAL_DRAFT_FORBIDDEN", "Only the requesting player or an operator can resolve this goal draft");
		}
		if (choice == GoalDraftChoice.CANCEL) return Operation.CANCEL;
		if (draft.proposedPredicate().isEmpty()) {
			throw new AgentDomainException("GOAL_DRAFT_NOT_READY", "The goal draft does not have a validated completion rule yet");
		}
		if (draft.intent() == DraftIntent.TRANSLATE_START) {
			if (choice != GoalDraftChoice.CONFIRM) {
				throw new AgentDomainException("GOAL_DRAFT_CHOICE_INVALID", "Confirm or cancel this requested start");
			}
			return Operation.START;
		}
		if (draft.intent() == DraftIntent.TRANSLATE_QUEUE) {
			if (choice != GoalDraftChoice.CONFIRM) {
				throw new AgentDomainException("GOAL_DRAFT_CHOICE_INVALID", "Confirm or cancel this requested queue addition");
			}
			return Operation.QUEUE;
		}
		boolean replacesExistingGoal = draft.expectedGoalId().isPresent();
		return switch (choice) {
			case CONFIRM -> {
				if (replacesExistingGoal) {
					throw new AgentDomainException("GOAL_DRAFT_CHOICE_REQUIRED", "Choose replace, queue, or cancel for a draft created while a goal is active");
				}
				yield Operation.START;
			}
			case REPLACE -> {
				if (!replacesExistingGoal) {
					throw new AgentDomainException("GOAL_DRAFT_CHOICE_INVALID", "Use confirm or cancel when no goal is active");
				}
				yield Operation.REPLACE;
			}
			case QUEUE -> {
				if (!replacesExistingGoal) {
					throw new AgentDomainException("GOAL_DRAFT_CHOICE_INVALID", "Use confirm or cancel when no goal is active");
				}
				yield Operation.QUEUE;
			}
			case CANCEL -> throw new AssertionError("cancel handled above");
		};
	}

	public enum Operation {
		START,
		REPLACE,
		QUEUE,
		CANCEL
	}
}
