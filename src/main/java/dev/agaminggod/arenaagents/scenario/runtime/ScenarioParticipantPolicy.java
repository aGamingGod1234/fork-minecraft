package dev.agaminggod.arenaagents.scenario.runtime;

import dev.agaminggod.arenaagents.agent.AgentGameMode;
import dev.agaminggod.arenaagents.scenario.ScenarioCategory;
import java.util.Objects;

/** Scenario-specific participant rules that override unsafe per-agent configuration. */
public final class ScenarioParticipantPolicy {
	private ScenarioParticipantPolicy() {
	}

	public static AgentGameMode effectiveGameMode(ScenarioCategory category, AgentGameMode requested) {
		Objects.requireNonNull(category, "category must not be null");
		Objects.requireNonNull(requested, "requested game mode must not be null");
		return category == ScenarioCategory.PARKOUR ? AgentGameMode.ADVENTURE : requested;
	}
}
