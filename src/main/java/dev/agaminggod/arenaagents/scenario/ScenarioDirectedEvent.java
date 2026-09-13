package dev.agaminggod.arenaagents.scenario;

public record ScenarioDirectedEvent(
		int sequence,
		String id,
		String phaseId,
		long elapsedTick,
		String description
) {
	public ScenarioDirectedEvent {
		if (sequence < 0) {
			throw ScenarioValidators.failure("INVALID_EVENT", "directed event sequence must not be negative");
		}
		id = ScenarioValidators.id(id, "directed event id");
		phaseId = ScenarioValidators.id(phaseId, "directed event phase id");
		if (elapsedTick < 0L) {
			throw ScenarioValidators.failure("INVALID_EVENT", "directed event tick must not be negative");
		}
		description = ScenarioValidators.text(description, "directed event description", 320);
	}
}
