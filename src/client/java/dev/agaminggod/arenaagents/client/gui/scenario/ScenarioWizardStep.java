package dev.agaminggod.arenaagents.client.gui.scenario;

public enum ScenarioWizardStep {
	ARENA("Preset"),
	ROSTER("Roster"),
	REVIEW("Launch");

	private final String displayName;

	ScenarioWizardStep(String displayName) {
		this.displayName = displayName;
	}

	public String displayName() {
		return displayName;
	}
}
