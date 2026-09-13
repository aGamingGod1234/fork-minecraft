package dev.agaminggod.arenaagents.server.runtime;

import dev.agaminggod.arenaagents.agent.AgentId;
import dev.agaminggod.arenaagents.protocol.ActionType;
import java.util.Objects;

public record ServerActionResult(
		AgentId agentId,
		long goalRevision,
	String actionId,
	ActionType actionType,
	String traceId,
	ServerActionState state,
		String reasonCode,
		String message,
		long elapsedMs,
	long observedAtEpochMs,
	boolean executionStarted,
	boolean physicalAttempted,
	ServerActionObservation actionObservation
) {
	public ServerActionResult {
		Objects.requireNonNull(agentId, "agentId must not be null");
		Objects.requireNonNull(actionId, "actionId must not be null");
		Objects.requireNonNull(actionType, "actionType must not be null");
		if (traceId != null && (traceId.isBlank() || traceId.getBytes(java.nio.charset.StandardCharsets.UTF_8).length > 128
				|| traceId.codePoints().anyMatch(codePoint -> codePoint < 0x20 || codePoint == 0x7f))) {
			throw new IllegalArgumentException("traceId must be nonblank and at most 128 UTF-8 bytes");
		}
		Objects.requireNonNull(state, "state must not be null");
		if (!state.terminal()) {
			throw new IllegalArgumentException("Result state must be terminal");
		}
		if (physicalAttempted && !executionStarted) {
			throw new IllegalArgumentException("physicalAttempted requires executionStarted");
		}
		reasonCode = bounded(reasonCode, 128);
		message = bounded(message, 2_048);
		if (elapsedMs < 0L || observedAtEpochMs <= 0L) {
			throw new IllegalArgumentException("Result timestamps are invalid");
		}
	}

	public ServerActionResult(
			AgentId agentId, long goalRevision, String actionId, ActionType actionType,
			ServerActionState state, String reasonCode, String message, long elapsedMs, long observedAtEpochMs
	) {
		this(agentId, goalRevision, actionId, actionType, null, state, reasonCode, message, elapsedMs, observedAtEpochMs, false, false, null);
	}

	public ServerActionResult(
			AgentId agentId, long goalRevision, String actionId, ActionType actionType,
			String traceId, ServerActionState state, String reasonCode, String message, long elapsedMs, long observedAtEpochMs
	) {
		this(agentId, goalRevision, actionId, actionType, traceId, state, reasonCode, message, elapsedMs, observedAtEpochMs, false, false, null);
	}

	public ServerActionResult(
			AgentId agentId, long goalRevision, String actionId, ActionType actionType,
			String traceId, ServerActionState state, String reasonCode, String message,
			long elapsedMs, long observedAtEpochMs, boolean executionStarted, boolean physicalAttempted
	) {
		this(agentId, goalRevision, actionId, actionType, traceId, state, reasonCode, message,
				elapsedMs, observedAtEpochMs, executionStarted, physicalAttempted, null);
	}

	private static String bounded(String value, int maximum) {
		String safe = value == null ? "" : value;
		return safe.length() <= maximum ? safe : safe.substring(0, maximum);
	}
}
