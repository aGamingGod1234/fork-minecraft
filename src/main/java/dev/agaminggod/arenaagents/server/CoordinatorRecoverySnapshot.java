package dev.agaminggod.arenaagents.server;

import java.util.Objects;

/** Bounded diagnostics for one coordinator recovery state-machine generation. */
public record CoordinatorRecoverySnapshot(
		CoordinatorRecoveryState state,
		long generation,
		int consecutiveFailures,
		long lastStableEpochMs,
		long nextRetryEpochMs,
		String failureCode,
		String failureMessage,
		String failingBoundary,
		String launchId,
		long processId,
		long processStartedEpochMs,
		long authenticationDeadlineEpochMs,
		long reconnectDeadlineEpochMs
) {
	public CoordinatorRecoverySnapshot {
		state = Objects.requireNonNull(state, "state must not be null");
		if (generation < 0L || consecutiveFailures < 0 || lastStableEpochMs < 0L || nextRetryEpochMs < 0L
				|| processId < -1L || processStartedEpochMs < 0L || authenticationDeadlineEpochMs < 0L
				|| reconnectDeadlineEpochMs < 0L) {
			throw new IllegalArgumentException("coordinator recovery counters and deadlines must be nonnegative");
		}
		failureCode = bounded(failureCode, 96);
		failureMessage = bounded(failureMessage, 512);
		failingBoundary = bounded(failingBoundary, 96);
		launchId = bounded(launchId, 64);
	}

	private static String bounded(String value, int maximum) {
		if (value == null) return null;
		String normalized = value.strip();
		if (normalized.isEmpty()) return null;
		return normalized.length() <= maximum ? normalized : normalized.substring(0, maximum);
	}
}
