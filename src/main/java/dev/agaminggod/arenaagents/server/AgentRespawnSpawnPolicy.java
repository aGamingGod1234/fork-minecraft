package dev.agaminggod.arenaagents.server;

public final class AgentRespawnSpawnPolicy {
	private AgentRespawnSpawnPolicy() {
	}

	public enum Decision {
		WAIT_FOR_REMOVAL,
		REQUEST_SPAWN,
		WAIT_FOR_SPAWN,
		VERIFY_PLAYER,
		TIMED_OUT
	}

	public enum ExistingPlayerAction {
		RESPAWN_CONNECTED_PLAYER,
		WAIT_FOR_NATURAL_REMOVAL,
		REMOVE_STALE_PLAYER
	}

	public static ExistingPlayerAction existingPlayerAction(boolean retainedAfterDeath, boolean playerAlive) {
		if (retainedAfterDeath) return ExistingPlayerAction.RESPAWN_CONNECTED_PLAYER;
		return playerAlive
				? ExistingPlayerAction.REMOVE_STALE_PLAYER
				: ExistingPlayerAction.WAIT_FOR_NATURAL_REMOVAL;
	}

	public static Decision decide(
			boolean spawnRequested,
			boolean playerPresent,
			long nowEpochMs,
			long deadlineEpochMs
	) {
		if (!spawnRequested) {
			if (!playerPresent) return Decision.REQUEST_SPAWN;
			return nowEpochMs >= deadlineEpochMs ? Decision.TIMED_OUT : Decision.WAIT_FOR_REMOVAL;
		}
		if (playerPresent) return Decision.VERIFY_PLAYER;
		return nowEpochMs >= deadlineEpochMs ? Decision.TIMED_OUT : Decision.WAIT_FOR_SPAWN;
	}
}
