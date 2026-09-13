package dev.agaminggod.arenaagents.server.runtime;

import dev.agaminggod.arenaagents.agent.AgentId;
import dev.agaminggod.arenaagents.protocol.ActionType;
import java.util.Objects;

public record ServerActionProgress(
		AgentId agentId,
		long goalRevision,
	String actionId,
	ActionType actionType,
	String traceId,
	double progress,
		long elapsedMs,
	long observedAtEpochMs,
	ServerActionObservation actionObservation
) {
	public ServerActionProgress {
		agentId = Objects.requireNonNull(agentId, "agentId must not be null");
		actionType = Objects.requireNonNull(actionType, "actionType must not be null");
		if (goalRevision < 0L) throw new IllegalArgumentException("goalRevision must be non-negative");
		if (actionId == null || actionId.isBlank() || actionId.length() > 128) {
			throw new IllegalArgumentException("actionId must be nonblank and at most 128 characters");
		}
		if (traceId != null && (traceId.isBlank() || traceId.getBytes(java.nio.charset.StandardCharsets.UTF_8).length > 128
				|| traceId.codePoints().anyMatch(codePoint -> codePoint < 0x20 || codePoint == 0x7f))) {
			throw new IllegalArgumentException("traceId must be nonblank and at most 128 UTF-8 bytes");
		}
		if (!Double.isFinite(progress) || progress < 0.0D || progress > 1.0D) {
			throw new IllegalArgumentException("progress must be in [0, 1]");
		}
		if (elapsedMs < 0L || observedAtEpochMs < 0L) {
			throw new IllegalArgumentException("progress timestamps must be non-negative");
		}
		if (actionObservation != null && actionObservation.progress() != null
				&& Double.compare(progress, actionObservation.progress().value()) != 0) {
			throw new IllegalArgumentException("progress must equal actionObservation.progress.value");
		}
	}

	public ServerActionProgress(
			AgentId agentId, long goalRevision, String actionId, ActionType actionType,
			double progress, long elapsedMs, long observedAtEpochMs
	) {
		this(agentId, goalRevision, actionId, actionType, null, progress, elapsedMs, observedAtEpochMs, null);
	}

	public ServerActionProgress(
			AgentId agentId, long goalRevision, String actionId, ActionType actionType,
			String traceId, double progress, long elapsedMs, long observedAtEpochMs
	) {
		this(agentId, goalRevision, actionId, actionType, traceId, progress, elapsedMs, observedAtEpochMs, null);
	}

}
