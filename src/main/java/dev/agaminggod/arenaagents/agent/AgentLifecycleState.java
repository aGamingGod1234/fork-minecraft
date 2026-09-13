package dev.agaminggod.arenaagents.agent;

public enum AgentLifecycleState {
	IDLE,
	STARTING,
	PLANNING,
	ACTING,
	PAUSED,
	COMPLETED,
	ERROR,
	DEAD,
	DISCONNECTED;

	public boolean isActive() {
		return this == STARTING || this == PLANNING || this == ACTING;
	}

	public boolean isReloadUncertain() {
		return isActive() || this == DISCONNECTED;
	}
}
