package dev.agaminggod.arenaagents.scenario;

import java.util.Optional;

public record ScenarioSpawn(
		String participantId,
		int slotIndex,
		String lane,
		double x,
		double y,
		double z,
		float yaw,
		Optional<String> team
) {
	public ScenarioSpawn {
		participantId = ScenarioValidators.id(participantId, "participant id");
		lane = ScenarioValidators.id(lane, "spawn lane");
		if (slotIndex < 0 || slotIndex >= ScenarioPreset.MAXIMUM_AGENTS) {
			throw ScenarioValidators.failure("INVALID_SPAWN", "slotIndex is outside supported bounds");
		}
		if (!Double.isFinite(x) || !Double.isFinite(y) || !Double.isFinite(z) || !Float.isFinite(yaw)) {
			throw ScenarioValidators.failure("INVALID_SPAWN", "spawn coordinates must be finite");
		}
		team = ScenarioValidators.optionalText(team, "spawn team", 48);
	}
}
