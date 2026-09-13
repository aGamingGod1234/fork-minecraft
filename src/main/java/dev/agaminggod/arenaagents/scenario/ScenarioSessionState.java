package dev.agaminggod.arenaagents.scenario;

public enum ScenarioSessionState {
	PREPARING,
	READY,
	COUNTDOWN,
	RUNNING,
	PAUSED,
	PAUSED_RECOVERY,
	FINISHED,
	FAILED;

	public boolean terminal() {
		return this == FINISHED || this == FAILED;
	}
}
