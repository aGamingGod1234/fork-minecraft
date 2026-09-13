package dev.agaminggod.arenaagents.scenario;

import java.util.Map;
import java.util.Objects;
import java.util.Optional;

public record ScenarioEvent(
		int sequence,
		long elapsedTick,
		ScenarioEventType type,
		Optional<String> participantId,
		Optional<String> targetId,
		String metric,
		double value,
		String description,
		Map<String, String> attributes
) {
	public ScenarioEvent {
		if (sequence < 0) {
			throw ScenarioValidators.failure("INVALID_EVENT", "event sequence must not be negative");
		}
		if (elapsedTick < 0L) {
			throw ScenarioValidators.failure("INVALID_EVENT", "event elapsedTick must not be negative");
		}
		type = Objects.requireNonNull(type, "type must not be null");
		participantId = ScenarioValidators.optionalText(participantId, "event participant id", 64)
				.map(valueId -> ScenarioValidators.id(valueId, "event participant id"));
		targetId = ScenarioValidators.optionalText(targetId, "event target id", 128);
		metric = ScenarioValidators.id(metric, "event metric");
		if (!Double.isFinite(value)) {
			throw ScenarioValidators.failure("INVALID_EVENT", "event value must be finite");
		}
		description = ScenarioValidators.text(description, "event description", 320);
		attributes = ScenarioValidators.attributes(attributes);
	}
}
