package dev.agaminggod.arenaagents.scenario;

import java.util.Optional;

public record ScenarioParticipant(
		String id,
		String modelLabel,
		Optional<String> team
) {
	public ScenarioParticipant {
		id = ScenarioValidators.id(id, "participant id");
		modelLabel = ScenarioValidators.text(modelLabel, "participant model label", 128);
		team = ScenarioValidators.optionalText(team, "participant team", 48);
	}
}
