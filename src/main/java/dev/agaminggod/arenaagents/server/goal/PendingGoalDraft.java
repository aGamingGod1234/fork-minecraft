package dev.agaminggod.arenaagents.server.goal;

import dev.agaminggod.arenaagents.agent.AgentId;
import dev.agaminggod.arenaagents.agent.AgentRecord;
import dev.agaminggod.arenaagents.agent.AgentValidators;
import dev.agaminggod.arenaagents.agent.goal.GoalPredicate;
import dev.agaminggod.arenaagents.agent.goal.GoalStatus;
import java.util.Objects;
import java.util.Optional;
import java.util.List;
import java.util.HashSet;
import java.util.UUID;

public record PendingGoalDraft(
		UUID draftId,
		AgentId agentId,
		UUID requestingPlayerId,
		String originalRequest,
		String dimensionId,
		List<String> candidateIds,
		GoalTranslationConstraint translationConstraint,
		Optional<GoalPredicate> proposedPredicate,
		DraftIntent intent,
		long createdAtTick,
		long expectedGoalRevision,
		Optional<UUID> expectedGoalId
) {
	/** Stable principal for authorized server-console and RCON goal submissions. */
	public static final UUID SYSTEM_REQUESTER_ID = new UUID(0L, 0L);

	public PendingGoalDraft {
		Objects.requireNonNull(draftId, "draftId must not be null");
		Objects.requireNonNull(agentId, "agentId must not be null");
		Objects.requireNonNull(requestingPlayerId, "requestingPlayerId must not be null");
		originalRequest = AgentValidators.normalizePrompt(originalRequest);
		dimensionId = Objects.requireNonNull(dimensionId, "dimensionId must not be null").strip();
		if (!dimensionId.matches("[a-z0-9_.-]+:[a-z0-9_./-]+")) {
			throw new IllegalArgumentException("dimensionId must be a namespaced identifier");
		}
		candidateIds = List.copyOf(Objects.requireNonNull(candidateIds, "candidateIds must not be null"));
		if (candidateIds.size() > 64 || new HashSet<>(candidateIds).size() != candidateIds.size()) {
			throw new IllegalArgumentException("candidateIds must contain at most 64 unique identifiers");
		}
		for (String candidateId : candidateIds) {
			if (candidateId == null || !candidateId.matches("[a-z0-9_.-]+:[a-z0-9_./-]+")) {
				throw new IllegalArgumentException("candidateIds must contain namespaced identifiers");
			}
		}
		Objects.requireNonNull(translationConstraint, "translationConstraint must not be null");
		proposedPredicate = Objects.requireNonNull(proposedPredicate, "proposedPredicate must not be null");
		Objects.requireNonNull(intent, "intent must not be null");
		if (createdAtTick < 0L) throw new IllegalArgumentException("createdAtTick must be nonnegative");
		if (expectedGoalRevision < 0L) throw new IllegalArgumentException("expectedGoalRevision must be nonnegative");
		expectedGoalId = Objects.requireNonNull(expectedGoalId, "expectedGoalId must not be null");
	}

	public PendingGoalDraft(
			UUID draftId, AgentId agentId, UUID requestingPlayerId, String originalRequest,
			String dimensionId, List<String> candidateIds, Optional<GoalPredicate> proposedPredicate,
			DraftIntent intent, long createdAtTick, long expectedGoalRevision, Optional<UUID> expectedGoalId
	) {
		this(draftId, agentId, requestingPlayerId, originalRequest, dimensionId, candidateIds,
				GoalTranslationConstraint.none(), proposedPredicate, intent,
				createdAtTick, expectedGoalRevision, expectedGoalId);
	}

	public PendingGoalDraft(
			UUID draftId, AgentId agentId, UUID requestingPlayerId, String originalRequest,
			List<String> candidateIds, Optional<GoalPredicate> proposedPredicate, DraftIntent intent,
			long createdAtTick, long expectedGoalRevision, Optional<UUID> expectedGoalId
	) {
		this(draftId, agentId, requestingPlayerId, originalRequest, GoalPredicate.DEFAULT_DIMENSION,
				candidateIds, GoalTranslationConstraint.none(), proposedPredicate, intent,
				createdAtTick, expectedGoalRevision, expectedGoalId);
	}

	public PendingGoalDraft(
			UUID draftId, AgentId agentId, UUID requestingPlayerId, String originalRequest,
			Optional<GoalPredicate> proposedPredicate, DraftIntent intent, long createdAtTick,
			long expectedGoalRevision, Optional<UUID> expectedGoalId
	) {
		this(draftId, agentId, requestingPlayerId, originalRequest, GoalPredicate.DEFAULT_DIMENSION,
				List.of(), GoalTranslationConstraint.none(), proposedPredicate, intent,
				createdAtTick, expectedGoalRevision, expectedGoalId);
	}

	public PendingGoalDraft withProposedPredicate(GoalPredicate predicate) {
		return new PendingGoalDraft(draftId, agentId, requestingPlayerId, originalRequest, dimensionId, candidateIds,
				translationConstraint, Optional.of(Objects.requireNonNull(predicate, "predicate must not be null")), intent,
				createdAtTick, expectedGoalRevision, expectedGoalId);
	}

	public static UUID requesterId(Optional<UUID> requestingPlayerId) {
		return Objects.requireNonNull(requestingPlayerId, "requestingPlayerId must not be null")
				.orElse(SYSTEM_REQUESTER_ID);
	}

	public boolean matches(AgentRecord record) {
		Objects.requireNonNull(record, "record must not be null");
		return record.agentId().equals(agentId)
				&& record.goalRevision() == expectedGoalRevision
				&& expectedGoalIdFor(record).equals(expectedGoalId);
	}

	public static Optional<UUID> expectedGoalIdFor(AgentRecord record) {
		Objects.requireNonNull(record, "record must not be null");
		return record.currentGoal()
				.filter(goal -> goal.status() != GoalStatus.SATISFIED && goal.status() != GoalStatus.CANCELLED)
				.map(dev.agaminggod.arenaagents.agent.AgentGoal::goalId);
	}
}
