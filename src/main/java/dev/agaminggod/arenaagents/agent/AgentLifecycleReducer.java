package dev.agaminggod.arenaagents.agent;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import dev.agaminggod.arenaagents.agent.goal.GoalSpec;
import dev.agaminggod.arenaagents.agent.goal.GoalEvidence;
import dev.agaminggod.arenaagents.agent.goal.GoalStatus;

public final class AgentLifecycleReducer {
	private AgentLifecycleReducer() {
	}

	public static AgentTransition start(AgentRecord current, String prompt, long nowEpochMs) {
		return start(current, AgentGoal.create(prompt, nowEpochMs), nowEpochMs);
	}

	public static AgentTransition start(AgentRecord current, GoalSpec spec, long nowEpochMs) {
		Objects.requireNonNull(spec, "spec must not be null");
		return start(current, AgentGoal.create(spec.originalRequest(), spec, nowEpochMs), nowEpochMs);
	}

	private static AgentTransition start(AgentRecord current, AgentGoal goal, long nowEpochMs) {
		requireState(current, "start", AgentLifecycleState.IDLE, AgentLifecycleState.PAUSED,
				AgentLifecycleState.COMPLETED, AgentLifecycleState.ERROR, AgentLifecycleState.DISCONNECTED);
		AgentRecord revised = current.withLifecycle(
				AgentLifecycleState.STARTING,
				Optional.of(goal),
				nextRevision(current),
				current.queuedGoals(),
				nowEpochMs,
				""
		);
		return transition(current, revised, current.state().isActive(), current.state().isActive());
	}

	public static AgentTransition queue(AgentRecord current, String prompt, int queueLimit, long nowEpochMs) {
		return queue(current, AgentGoal.create(prompt, nowEpochMs), queueLimit, nowEpochMs);
	}

	public static AgentTransition replace(AgentRecord current, GoalSpec spec, long nowEpochMs) {
		Objects.requireNonNull(spec, "spec must not be null");
		if (current.currentGoal().isEmpty()) {
			throw new AgentDomainException("NO_CURRENT_GOAL", "Agent has no current goal to replace");
		}
		requireState(current, "replace", AgentLifecycleState.STARTING, AgentLifecycleState.PLANNING,
				AgentLifecycleState.ACTING, AgentLifecycleState.PAUSED, AgentLifecycleState.COMPLETED,
				AgentLifecycleState.ERROR, AgentLifecycleState.DISCONNECTED);
		AgentRecord revised = current.withLifecycle(
				AgentLifecycleState.STARTING,
				Optional.of(AgentGoal.create(spec.originalRequest(), spec, nowEpochMs)),
				nextRevision(current),
				current.queuedGoals(),
				nowEpochMs,
				""
		);
		return transition(current, revised, true, true);
	}

	public static AgentTransition queue(AgentRecord current, GoalSpec spec, int queueLimit, long nowEpochMs) {
		Objects.requireNonNull(spec, "spec must not be null");
		return queue(current, AgentGoal.create(spec.originalRequest(), spec, nowEpochMs), queueLimit, nowEpochMs);
	}

	private static AgentTransition queue(AgentRecord current, AgentGoal goal, int queueLimit, long nowEpochMs) {
		if (queueLimit <= 0) {
			throw new IllegalArgumentException("queueLimit must be positive");
		}
		if (current.queuedGoals().size() >= queueLimit) {
			throw new AgentDomainException("QUEUE_FULL", "Agent queue limit reached: " + queueLimit);
		}
		ArrayList<AgentGoal> queue = new ArrayList<>(current.queuedGoals());
		queue.add(goal);
		AgentRecord revised = current.withLifecycle(
				current.state(),
				current.currentGoal(),
				current.goalRevision(),
				queue,
				nowEpochMs,
				current.lastError()
		);
		return transition(current, revised, false, false);
	}

	public static AgentTransition stop(AgentRecord current, long nowEpochMs) {
		requireUnfinishedGoal(current, "stop");
		AgentRecord revised = current.withLifecycle(
				AgentLifecycleState.PAUSED,
				current.currentGoal(),
				nextRevision(current),
				current.queuedGoals(),
				nowEpochMs,
				current.lastError()
		);
		return transition(current, revised, true, true);
	}

	public static AgentTransition resume(AgentRecord current, long nowEpochMs) {
		requireState(current, "resume", AgentLifecycleState.PAUSED, AgentLifecycleState.DISCONNECTED);
		requireUnfinishedGoal(current, "resume");
		AgentRecord revised = current.withLifecycle(
				AgentLifecycleState.STARTING,
				current.currentGoal(),
				nextRevision(current),
				current.queuedGoals(),
				nowEpochMs,
				""
		);
		return transition(current, revised, false, false);
	}

	public static AgentTransition steer(AgentRecord current, String instruction, long nowEpochMs) {
		AgentGoal currentGoal = requireUnfinishedGoal(current, "steer");
		AgentGoal steered = currentGoal.steer(instruction, nowEpochMs);
		AgentRecord revised = current.withLifecycle(
				AgentLifecycleState.STARTING,
				Optional.of(steered),
				nextRevision(current),
				current.queuedGoals(),
				nowEpochMs,
				""
		);
		return transition(current, revised, true, true);
	}

	public static AgentTransition beginPlanning(AgentRecord current, long nowEpochMs) {
		requireState(current, "plan", AgentLifecycleState.STARTING);
		return transition(current, current.withLifecycle(
				AgentLifecycleState.PLANNING,
				current.currentGoal(),
				current.goalRevision(),
				current.queuedGoals(),
				nowEpochMs,
				""
		), false, false);
	}

	public static AgentTransition beginAction(AgentRecord current, long revision, long nowEpochMs) {
		requireState(current, "act", AgentLifecycleState.STARTING, AgentLifecycleState.PLANNING);
		requireRevision(current, revision);
		return transition(current, current.withLifecycle(
				AgentLifecycleState.ACTING,
				current.currentGoal(),
				current.goalRevision(),
				current.queuedGoals(),
				nowEpochMs,
				""
		), false, false);
	}

	public static AgentTransition actionFinished(AgentRecord current, long revision, long nowEpochMs) {
		requireState(current, "finish action", AgentLifecycleState.ACTING);
		requireRevision(current, revision);
		return transition(current, current.withLifecycle(
				AgentLifecycleState.STARTING,
				current.currentGoal(),
				current.goalRevision(),
				current.queuedGoals(),
				nowEpochMs,
				""
		), false, false);
	}

	public static AgentTransition completeGoal(AgentRecord current, long revision, long nowEpochMs) {
		requireState(
				current,
				"complete goal",
				AgentLifecycleState.STARTING,
				AgentLifecycleState.PLANNING,
				AgentLifecycleState.ACTING
		);
		requireRevision(current, revision);
		if (current.currentGoal().isEmpty()) {
			throw new AgentDomainException("NO_CURRENT_GOAL", "Agent has no current goal to complete");
		}
		List<AgentGoal> queue = current.queuedGoals();
		if (queue.isEmpty()) {
			AgentRecord completed = current.withLifecycle(
					AgentLifecycleState.IDLE,
					Optional.empty(),
					nextRevision(current),
					List.of(),
					nowEpochMs,
					""
			);
			return transition(current, completed, true, true);
		}
		AgentGoal promoted = queue.getFirst().activatedAt(nowEpochMs);
		AgentRecord promotedRecord = current.withLifecycle(
				AgentLifecycleState.STARTING,
				Optional.of(promoted),
				nextRevision(current),
				queue.subList(1, queue.size()),
				nowEpochMs,
				""
		);
		return transition(current, promotedRecord, true, true);
	}

	public static AgentTransition satisfyGoal(
			AgentRecord current,
			long revision,
			GoalEvidence evidence,
			long nowEpochMs
	) {
		requireState(current, "satisfy goal", AgentLifecycleState.STARTING, AgentLifecycleState.PLANNING, AgentLifecycleState.ACTING);
		requireRevision(current, revision);
		AgentGoal goal = current.currentGoal().orElseThrow(
				() -> new AgentDomainException("NO_CURRENT_GOAL", "Agent has no current goal to satisfy")
		);
		if (goal.status() != GoalStatus.ACTIVE && goal.status() != GoalStatus.RECOVERING) {
			throw new AgentDomainException("GOAL_NOT_ACTIVE", "Only active or recovering goals may become satisfied");
		}
		AgentGoal satisfied = goal.withStatus(GoalStatus.SATISFIED, Optional.of(
				Objects.requireNonNull(evidence, "evidence must not be null")), nowEpochMs);
		AgentRecord completed = current.withLifecycle(
				AgentLifecycleState.COMPLETED,
				Optional.of(satisfied),
				nextRevision(current),
				current.queuedGoals(),
				nowEpochMs,
				""
		);
		return transition(current, completed, true, true);
	}

	public static AgentTransition promoteSatisfied(AgentRecord current, long nowEpochMs) {
		requireSatisfiedPromotionSource(current);
		if (current.queuedGoals().isEmpty()) {
			throw new AgentDomainException("NO_QUEUED_GOAL", "Agent has no queued goal to promote");
		}
		AgentRecord promoted = current.withLifecycle(
				AgentLifecycleState.STARTING,
				Optional.of(current.queuedGoals().getFirst().activatedAt(nowEpochMs)),
				nextRevision(current),
				current.queuedGoals().subList(1, current.queuedGoals().size()),
				nowEpochMs,
				current.lastError()
		);
		return transition(current, promoted, false, false);
	}

	public static AgentTransition rejectQueuedGoal(
			AgentRecord current,
			UUID expectedGoalId,
			String reason,
			long nowEpochMs
	) {
		requireSatisfiedPromotionSource(current);
		if (current.queuedGoals().isEmpty()) {
			throw new AgentDomainException("NO_QUEUED_GOAL", "Agent has no queued goal to reject");
		}
		AgentGoal queued = current.queuedGoals().getFirst();
		if (!queued.goalId().equals(Objects.requireNonNull(expectedGoalId, "expectedGoalId must not be null"))) {
			throw new AgentDomainException("STALE_QUEUED_GOAL", "Queued goal head changed before rejection");
		}
		AgentRecord rejected = current.withLifecycle(
				AgentLifecycleState.COMPLETED,
				current.currentGoal(),
				current.goalRevision(),
				current.queuedGoals().subList(1, current.queuedGoals().size()),
				nowEpochMs,
				AgentValidators.boundedText(reason, "error", AgentConstants.MAX_ERROR_LENGTH)
		);
		return transition(current, rejected, false, false);
	}

	public static AgentTransition fail(AgentRecord current, String message, long nowEpochMs) {
		AgentRecord failed = current.withLifecycle(
				AgentLifecycleState.ERROR,
				current.currentGoal(),
				nextRevision(current),
				current.queuedGoals(),
				nowEpochMs,
				AgentValidators.boundedText(message, "error", AgentConstants.MAX_ERROR_LENGTH)
		);
		return transition(current, failed, true, true);
	}

	public static AgentTransition disconnect(AgentRecord current, long nowEpochMs) {
		if (current.currentGoal().isEmpty()) {
			return transition(current, current, false, false);
		}
		if (isTerminal(current.currentGoal().orElseThrow())) {
			return transition(current, current, false, false);
		}
		AgentRecord disconnected = current.withLifecycle(
				AgentLifecycleState.DISCONNECTED,
				current.currentGoal(),
				nextRevision(current),
				current.queuedGoals(),
				nowEpochMs,
				"Coordinator disconnected"
		);
		return transition(current, disconnected, true, true);
	}

	public static AgentTransition rearmAfterCoordinatorRecovery(
			AgentRecord current,
			long expectedGoalRevision,
			long nowEpochMs
	) {
		requireRevision(current, expectedGoalRevision);
		requireUnfinishedGoal(current, "re-arm after coordinator recovery");
		if (current.state() == AgentLifecycleState.STARTING) {
			return transition(current, current, false, false);
		}
		requireState(current, "re-arm after coordinator recovery", AgentLifecycleState.PLANNING,
				AgentLifecycleState.ACTING, AgentLifecycleState.DISCONNECTED);
		AgentRecord rearmed = current.withLifecycle(
				AgentLifecycleState.STARTING,
				current.currentGoal(),
				current.goalRevision(),
				current.queuedGoals(),
				nowEpochMs,
				""
		);
		return transition(current, rearmed, false, false);
	}

	public static AgentTransition die(AgentRecord current, AgentDeathSnapshot deathSnapshot, long nowEpochMs) {
		Objects.requireNonNull(deathSnapshot, "deathSnapshot must not be null");
		AgentRecord dead = current.withLifecycle(
				AgentLifecycleState.DEAD,
				current.currentGoal(),
				current.goalRevision(),
				current.queuedGoals(),
				nowEpochMs,
				current.lastError()
		).withDeathSnapshot(deathSnapshot, nowEpochMs).withEntityUuid(Optional.empty(), nowEpochMs);
		return transition(current, dead, true, true);
	}

	public static AgentTransition respawn(AgentRecord current, UUID entityUuid, long nowEpochMs) {
		requireState(current, "respawn", AgentLifecycleState.DEAD);
		AgentLifecycleState nextState;
		if (current.currentGoal().isEmpty()) {
			nextState = AgentLifecycleState.IDLE;
		} else if (current.currentGoal().orElseThrow().status() == GoalStatus.SATISFIED) {
			nextState = AgentLifecycleState.COMPLETED;
		} else {
			nextState = current.resumeAfterRespawn() ? AgentLifecycleState.STARTING : AgentLifecycleState.PAUSED;
		}
		AgentRecord respawned = current.withLifecycle(
				nextState,
				current.currentGoal(),
				current.goalRevision(),
				current.queuedGoals(),
				nowEpochMs,
				""
		).withEntityUuid(Optional.of(Objects.requireNonNull(entityUuid, "entityUuid must not be null")), nowEpochMs);
		return transition(current, respawned, true, true);
	}

	public static AgentRecord recoverAfterReload(AgentRecord current, long nowEpochMs) {
		if (!current.state().isReloadUncertain()) {
			return current;
		}
		return rearmAfterCoordinatorRecovery(current, current.goalRevision(), nowEpochMs).after();
	}

	private static void requireRevision(AgentRecord current, long revision) {
		if (revision != current.goalRevision()) {
			throw new AgentDomainException(
					"STALE_REVISION",
					"Expected goal revision " + current.goalRevision() + " but received " + revision
			);
		}
	}

	private static AgentGoal requireUnfinishedGoal(AgentRecord current, String operation) {
		AgentGoal goal = current.currentGoal().orElseThrow(
				() -> new AgentDomainException("NO_CURRENT_GOAL", "Agent has no current goal to " + operation)
		);
		if (isTerminal(goal)) {
			throw new AgentDomainException("TERMINAL_GOAL", "Cannot " + operation + " a terminal goal");
		}
		return goal;
	}

	private static boolean isTerminal(AgentGoal goal) {
		return goal.status() == GoalStatus.SATISFIED || goal.status() == GoalStatus.CANCELLED;
	}

	private static void requireSatisfiedPromotionSource(AgentRecord current) {
		requireState(current, "promote satisfied goal", AgentLifecycleState.COMPLETED);
		AgentGoal completed = current.currentGoal().orElseThrow(
				() -> new AgentDomainException("NO_CURRENT_GOAL", "Completed agent has no satisfied goal")
		);
		if (completed.status() != GoalStatus.SATISFIED) {
			throw new AgentDomainException(
					"GOAL_NOT_SATISFIED", "Queued work can be promoted only after factual satisfaction");
		}
	}

	private static long nextRevision(AgentRecord current) {
		if (current.goalRevision() == Long.MAX_VALUE) {
			throw new AgentDomainException("REVISION_EXHAUSTED", "Agent goal revision is exhausted");
		}
		return current.goalRevision() + 1L;
	}

	private static void requireState(
			AgentRecord current,
			String operation,
			AgentLifecycleState... allowedStates
	) {
		for (AgentLifecycleState state : allowedStates) {
			if (current.state() == state) {
				return;
			}
		}
		throw new AgentDomainException(
				"INVALID_TRANSITION",
				"Cannot " + operation + " agent while it is " + current.state()
		);
	}

	private static AgentTransition transition(
			AgentRecord before,
			AgentRecord after,
			boolean cancelAction,
			boolean interruptPlanner
	) {
		return new AgentTransition(before, after, cancelAction, interruptPlanner);
	}
}
