package dev.agaminggod.arenaagents.server;

/** Current coordinator availability state owned by the Minecraft server process. */
public enum CoordinatorRecoveryState {
	STARTING,
	AUTHENTICATING,
	HEALTHY,
	DEGRADED,
	BACKOFF,
	BLOCKED_RETRYABLE,
	STOPPED
}
