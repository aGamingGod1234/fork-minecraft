package dev.agaminggod.arenaagents.control;

import dev.agaminggod.arenaagents.agent.AgentConstants;
import dev.agaminggod.arenaagents.agent.AgentId;
import dev.agaminggod.arenaagents.agent.AgentVisualIdentity;
import java.util.Objects;

public record AgentControlAgent(
		String agentId,
		String shortId,
		String displayName,
		String friendlyName,
		String provider,
		String model,
		String reasoning,
		String playerName,
		int skinVariant,
		String state,
		String currentGoal,
		int queuedGoalCount,
		String lastSummary,
		String lastError,
		boolean automaticProgress,
		boolean entityPresent
) {
	public static final int MAX_DISPLAY_NAME_LENGTH = 160;
	public static final int MAX_STATE_LENGTH = 32;
	public static final int MAX_CURRENT_GOAL_LENGTH = 512;
	public static final int MAX_LAST_SUMMARY_LENGTH = 512;
	public static final int MAX_LAST_ERROR_LENGTH = 256;

	public AgentControlAgent {
		AgentId parsedId = AgentId.parse(agentId);
		agentId = parsedId.toString();
		shortId = requireBounded(shortId, "shortId", AgentConstants.SHORT_ID_LENGTH);
		if (!shortId.equals(parsedId.shortValue())) {
			throw new IllegalArgumentException("shortId does not match agentId");
		}
		displayName = requireBounded(displayName, "displayName", MAX_DISPLAY_NAME_LENGTH);
		friendlyName = requireBounded(friendlyName, "friendlyName", AgentConstants.MAX_USER_NAME_LENGTH);
		provider = requireBounded(provider, "provider", AgentConstants.MAX_REASONING_LENGTH);
		model = requireBounded(model, "model", AgentConstants.MAX_MODEL_LENGTH);
		reasoning = requireBounded(reasoning, "reasoning", AgentConstants.MAX_REASONING_LENGTH);
		playerName = requireBounded(playerName, "playerName", 16);
		if (playerName.isBlank()) throw new IllegalArgumentException("playerName must not be blank");
		if (skinVariant < 0 || skinVariant >= AgentVisualIdentity.INDIVIDUAL_VARIANT_COUNT) {
			throw new IllegalArgumentException("skinVariant is outside the supported range");
		}
		state = requireBounded(state, "state", MAX_STATE_LENGTH);
		currentGoal = requireBounded(currentGoal, "currentGoal", MAX_CURRENT_GOAL_LENGTH);
		lastSummary = requireBounded(lastSummary, "lastSummary", MAX_LAST_SUMMARY_LENGTH);
		lastError = requireBounded(lastError, "lastError", MAX_LAST_ERROR_LENGTH);
		if (queuedGoalCount < 0 || queuedGoalCount > AgentConstants.DEFAULT_QUEUE_LIMIT) {
			throw new IllegalArgumentException("queuedGoalCount is outside the supported range");
		}
	}

	public AgentControlAgent(
			String agentId,
			String shortId,
			String displayName,
			String provider,
			String model,
			String reasoning,
			String playerName,
			int skinVariant,
			String state,
			String currentGoal,
			int queuedGoalCount,
			String lastSummary,
			String lastError,
			boolean automaticProgress,
			boolean entityPresent
	) {
		this(
				agentId, shortId, displayName, "", provider, model, reasoning, playerName, skinVariant, state,
				currentGoal, queuedGoalCount, lastSummary, lastError, automaticProgress, entityPresent
		);
	}

	static String truncate(String value, int maximumLength) {
		String checked = Objects.requireNonNull(value, "value must not be null");
		if (checked.length() <= maximumLength) {
			return checked;
		}
		return checked.substring(0, maximumLength);
	}

	private static String requireBounded(String value, String field, int maximumLength) {
		String checked = Objects.requireNonNull(value, field + " must not be null");
		if (checked.length() > maximumLength) {
			throw new IllegalArgumentException(field + " exceeds " + maximumLength + " characters");
		}
		return checked;
	}
}
