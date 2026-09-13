package dev.agaminggod.arenaagents.agent.goal;

import dev.agaminggod.arenaagents.agent.AgentConstants;
import dev.agaminggod.arenaagents.agent.AgentDomainException;
import dev.agaminggod.arenaagents.agent.AgentValidators;
import java.util.Locale;
import java.util.Objects;

public record GoalSpec(String originalRequest, GoalPredicate completion, long createdAtTick, String fingerprint) {
	public GoalSpec {
		originalRequest = AgentValidators.normalizePrompt(originalRequest);
		if (originalRequest.isEmpty() || originalRequest.length() > AgentConstants.MAX_PROMPT_LENGTH) {
			throw new AgentDomainException("INVALID_GOAL_SPEC", "Original request has an invalid length");
		}
		completion = Objects.requireNonNull(completion, "completion must not be null");
		if (createdAtTick < 0L) throw new AgentDomainException("INVALID_GOAL_SPEC", "Creation tick must be nonnegative");
		GoalSpecCodec.validatePredicate(completion);
		fingerprint = Objects.requireNonNull(fingerprint, "fingerprint must not be null").toLowerCase(Locale.ROOT);
		if (!fingerprint.matches("[0-9a-f]{64}")) {
			throw new AgentDomainException("INVALID_GOAL_FINGERPRINT", "Goal fingerprint must be lowercase SHA-256");
		}
		String expected = GoalSpecCodec.fingerprint(originalRequest, completion, createdAtTick);
		if (!fingerprint.equals(expected)) {
			throw new AgentDomainException("GOAL_FINGERPRINT_MISMATCH", "Goal fingerprint does not match its immutable fields");
		}
	}

	public static GoalSpec create(String request, GoalPredicate completion, long createdAtTick) {
		String checkedRequest = AgentValidators.normalizePrompt(request);
		return new GoalSpec(
				checkedRequest,
				completion,
				createdAtTick,
				GoalSpecCodec.fingerprint(checkedRequest, completion, createdAtTick)
		);
	}
}
