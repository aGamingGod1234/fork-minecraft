package dev.agaminggod.arenaagents.scenario.runtime;

import dev.agaminggod.arenaagents.scenario.ScenarioCategory;
import dev.agaminggod.arenaagents.scenario.ScenarioPreset;
import dev.agaminggod.arenaagents.scenario.ScenarioSpawnLayout;
import java.util.List;
import java.util.Objects;

/** Roster-sized authored geometry shared by blueprints and spawn allocation. */
public record ScenarioArenaProfile(
		ScenarioCategory category,
		int participantCount,
		List<ScenarioSpawnLayout.Slot> spawnSlots
) {
	public ScenarioArenaProfile {
		category = Objects.requireNonNull(category, "category must not be null");
		if (participantCount < 1 || participantCount > ScenarioPreset.MAXIMUM_AGENTS) {
			throw new IllegalArgumentException("participant count must be in [1, 16]");
		}
		spawnSlots = List.copyOf(Objects.requireNonNull(spawnSlots, "spawnSlots must not be null"));
		if (spawnSlots.size() != participantCount) {
			throw new IllegalArgumentException("spawn slot count must match participants");
		}
	}

	public static ScenarioArenaProfile create(ScenarioCategory category, int participantCount) {
		return new ScenarioArenaProfile(
				category,
				participantCount,
				ScenarioSpawnLayout.slots(category, participantCount)
		);
	}

	public int minimumX() {
		return spawnSlots.stream().mapToInt(ScenarioSpawnLayout.Slot::x).min().orElse(0);
	}

	public int maximumX() {
		return spawnSlots.stream().mapToInt(ScenarioSpawnLayout.Slot::x).max().orElse(0);
	}

	public int parkourHalfWidth() {
		int edge = Math.max(Math.abs(minimumX() - 2), Math.abs(maximumX() + 4));
		return Math.max(10, edge + 3);
	}
}
