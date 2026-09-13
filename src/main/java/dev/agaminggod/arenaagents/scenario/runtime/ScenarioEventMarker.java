package dev.agaminggod.arenaagents.scenario.runtime;

import dev.agaminggod.arenaagents.scenario.ScenarioDirectedEvent;

import java.util.Objects;

public record ScenarioEventMarker(int x, int y, int z) {
	public static ScenarioEventMarker forEvent(ScenarioDirectedEvent event) {
		Objects.requireNonNull(event, "event must not be null");
		int slot = Math.floorMod(event.sequence(), 25);
		return new ScenarioEventMarker(
				-6 + (slot % 5) * 3,
				14,
				68 + slot / 5
		);
	}
}
