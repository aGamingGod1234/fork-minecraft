package dev.agaminggod.arenaagents.server.conversation;

import dev.agaminggod.arenaagents.agent.AgentDomainException;
import dev.agaminggod.arenaagents.agent.AgentGoal;
import dev.agaminggod.arenaagents.agent.AgentRecord;
import dev.agaminggod.arenaagents.agent.AgentTransition;
import java.util.Objects;
import java.util.UUID;

/** Durable delivery state for one conversation-triggered lifecycle start. */
public record PendingConversationWake(
		UUID transactionId,
		ConversationEvent event,
		AgentGoal goal,
		long goalRevision,
		long updatedAtEpochMs,
		boolean acknowledged
) {
	public PendingConversationWake {
		Objects.requireNonNull(transactionId, "transactionId must not be null");
		Objects.requireNonNull(event, "event must not be null");
		Objects.requireNonNull(goal, "goal must not be null");
		if (event.goalRevision() == Long.MAX_VALUE || goalRevision != event.goalRevision() + 1L) {
			throw new AgentDomainException("INVALID_CONVERSATION_WAKE", "Conversation wake revisions must be consecutive");
		}
		if (updatedAtEpochMs <= 0L) {
			throw new AgentDomainException("INVALID_CONVERSATION_WAKE", "Conversation wake time must be positive");
		}
	}

	public static PendingConversationWake create(ConversationEvent event, AgentTransition transition) {
		Objects.requireNonNull(transition, "transition must not be null");
		if (!transition.before().agentId().equals(event.agentId())
				|| transition.before().goalRevision() != event.goalRevision()) {
			throw new AgentDomainException("STALE_REVISION", "Conversation wake target changed before publication");
		}
		AgentGoal goal = transition.after().currentGoal().orElseThrow(
				() -> new AgentDomainException("INVALID_CONVERSATION_WAKE", "Conversation wake start has no goal")
		);
		return new PendingConversationWake(
				UUID.randomUUID(), event, goal, transition.after().goalRevision(),
				transition.after().updatedAtEpochMs(), false
		);
	}

	public PendingConversationWake acknowledge() {
		return acknowledged ? this : new PendingConversationWake(
				transactionId, event, goal, goalRevision, updatedAtEpochMs, true
		);
	}

	public boolean matches(AgentRecord record) {
		return record.agentId().equals(event.agentId())
				&& record.goalRevision() == goalRevision
				&& record.currentGoal().map(current -> current.goalId().equals(goal.goalId())).orElse(false);
	}
}
