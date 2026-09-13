package dev.agaminggod.arenaagents.server.runtime;

public enum ServerActionState {
	RUNNING,
	SUCCEEDED,
	FAILED,
	CANCELLED,
	TIMED_OUT;

	public boolean terminal() {
		return this != RUNNING;
	}
}
