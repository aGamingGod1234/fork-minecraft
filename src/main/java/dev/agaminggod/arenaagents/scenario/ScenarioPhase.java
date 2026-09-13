package dev.agaminggod.arenaagents.scenario;

public record ScenarioPhase(
		String id,
		String title,
		long startTick,
		long durationTicks,
		String description
) {
	public ScenarioPhase {
		id = ScenarioValidators.id(id, "phase id");
		title = ScenarioValidators.text(title, "phase title", 80);
		description = ScenarioValidators.text(description, "phase description", 320);
		if (startTick < 0L) {
			throw ScenarioValidators.failure("INVALID_PHASE", "phase startTick must not be negative");
		}
		if (durationTicks <= 0L) {
			throw ScenarioValidators.failure("INVALID_PHASE", "phase durationTicks must be positive");
		}
	}

	public long endTickExclusive() {
		return Math.addExact(startTick, durationTicks);
	}

	public boolean contains(long elapsedTick) {
		return elapsedTick >= startTick && elapsedTick < endTickExclusive();
	}
}
