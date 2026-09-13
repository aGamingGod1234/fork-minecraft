package dev.agaminggod.arenaagents.agent;

import dev.agaminggod.arenaagents.agent.goal.GoalEvidence;
import dev.agaminggod.arenaagents.agent.goal.GoalPredicate;
import dev.agaminggod.arenaagents.agent.goal.GoalSpec;
import dev.agaminggod.arenaagents.agent.goal.GoalStatus;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

public record AgentGoal(
		UUID goalId,
		String prompt,
		List<String> steeringInstructions,
		GoalSpec spec,
		GoalStatus status,
		Optional<GoalEvidence> evidence,
		long createdAtEpochMs,
		long updatedAtEpochMs
) {
	public AgentGoal {
		Objects.requireNonNull(goalId, "goalId must not be null");
		prompt = AgentValidators.normalizePrompt(prompt);
		List<String> suppliedInstructions = List.copyOf(Objects.requireNonNull(
				steeringInstructions,
				"steeringInstructions must not be null"
		));
		if (suppliedInstructions.size() > AgentConstants.MAX_STEERING_INSTRUCTIONS) {
			throw new AgentDomainException("STEERING_LIMIT_REACHED", "Too many steering instructions");
		}
		steeringInstructions = suppliedInstructions.stream().map(AgentValidators::normalizePrompt).toList();
		spec = Objects.requireNonNull(spec, "spec must not be null");
		if (!prompt.equals(spec.originalRequest())) {
			throw new AgentDomainException("INVALID_GOAL_SPEC", "Goal prompt must match the immutable original request");
		}
		status = Objects.requireNonNull(status, "status must not be null");
		evidence = Objects.requireNonNull(evidence, "evidence must not be null");
		if (evidence.isPresent() && status != GoalStatus.SATISFIED) {
			throw new AgentDomainException("INVALID_GOAL_EVIDENCE", "Only a satisfied goal may retain completion evidence");
		}
		if (createdAtEpochMs <= 0L || updatedAtEpochMs < createdAtEpochMs) {
			throw new AgentDomainException("INVALID_GOAL_TIME", "Goal timestamps are invalid");
		}
	}

	public AgentGoal(UUID goalId, String prompt, List<String> steeringInstructions, long createdAtEpochMs, long updatedAtEpochMs) {
		this(
				goalId,
				AgentValidators.normalizePrompt(prompt),
				steeringInstructions,
				legacySpec(prompt),
				GoalStatus.ACTIVE,
				Optional.empty(),
				createdAtEpochMs,
				updatedAtEpochMs
		);
	}

	public static AgentGoal create(String prompt, long nowEpochMs) {
		String checked = AgentValidators.normalizePrompt(prompt);
		return new AgentGoal(UUID.randomUUID(), checked, List.of(), legacySpec(checked),
				GoalStatus.ACTIVE, Optional.empty(), nowEpochMs, nowEpochMs);
	}

	public static AgentGoal create(String prompt, GoalSpec spec, long nowEpochMs) {
		return new AgentGoal(UUID.randomUUID(), AgentValidators.normalizePrompt(prompt), List.of(), spec,
				GoalStatus.ACTIVE, Optional.empty(), nowEpochMs, nowEpochMs);
	}

	public AgentGoal steer(String instruction, long nowEpochMs) {
		if (status == GoalStatus.SATISFIED || status == GoalStatus.CANCELLED) {
			throw new AgentDomainException("TERMINAL_GOAL", "A terminal goal cannot be steered back into active work");
		}
		if (nowEpochMs < updatedAtEpochMs) {
			throw new AgentDomainException("INVALID_GOAL_TIME", "Steering timestamp precedes the goal update");
		}
		ArrayList<String> revised = new ArrayList<>(steeringInstructions);
		if (revised.size() >= AgentConstants.MAX_STEERING_INSTRUCTIONS) {
			throw new AgentDomainException("STEERING_LIMIT_REACHED", "Steering instruction limit reached");
		}
		revised.add(AgentValidators.normalizePrompt(instruction));
		return new AgentGoal(goalId, prompt, revised, spec, status, evidence, createdAtEpochMs, nowEpochMs);
	}

	public AgentGoal withStatus(GoalStatus revisedStatus, Optional<GoalEvidence> revisedEvidence, long nowEpochMs) {
		if (nowEpochMs < updatedAtEpochMs) {
			throw new AgentDomainException("INVALID_GOAL_TIME", "Status timestamp precedes the goal update");
		}
		Objects.requireNonNull(revisedStatus, "revisedStatus must not be null");
		Objects.requireNonNull(revisedEvidence, "revisedEvidence must not be null");
		if ((status == GoalStatus.SATISFIED || status == GoalStatus.CANCELLED) && revisedStatus != status) {
			throw new AgentDomainException("TERMINAL_GOAL", "A terminal goal cannot return to active work");
		}
		if (revisedStatus == GoalStatus.SATISFIED && revisedEvidence.isEmpty()) {
			throw new AgentDomainException("INVALID_GOAL_EVIDENCE", "Newly satisfied goals require factual evidence");
		}
		return new AgentGoal(goalId, prompt, steeringInstructions, spec, revisedStatus, revisedEvidence, createdAtEpochMs, nowEpochMs);
	}

	/** Resets the factual start boundary when a queued goal becomes active. */
	public AgentGoal activatedAt(long nowEpochMs) {
		if (nowEpochMs <= 0L || nowEpochMs < updatedAtEpochMs) {
			throw new AgentDomainException("INVALID_GOAL_TIME", "Activation timestamp is invalid");
		}
		return new AgentGoal(goalId, prompt, steeringInstructions, spec, status, evidence, nowEpochMs, nowEpochMs);
	}

	private static GoalSpec legacySpec(String prompt) {
		String checked = AgentValidators.normalizePrompt(prompt);
		return GoalSpec.create(checked, new GoalPredicate.OperatorConfirmed(), 0L);
	}
}
