package dev.agaminggod.arenaagents.client.gui.scenario;

import dev.agaminggod.arenaagents.agent.AgentGameMode;
import dev.agaminggod.arenaagents.scenario.ScenarioPlacementMode;
import java.util.List;
import java.util.Objects;

public record ScenarioLaunchPlan(
		String scenarioId,
		String scenarioTitle,
		String mapVersion,
		boolean deterministicEvents,
		ScenarioPlacementMode placementMode,
		List<Agent> roster,
		String confirmationToken
) {
	public ScenarioLaunchPlan(
			String scenarioId, String scenarioTitle, String mapVersion,
			boolean deterministicEvents, ScenarioPlacementMode placementMode, List<Agent> roster
	) {
		this(scenarioId, scenarioTitle, mapVersion, deterministicEvents, placementMode, roster, "");
	}

	public ScenarioLaunchPlan {
		scenarioId = Objects.requireNonNull(scenarioId, "scenarioId must not be null");
		scenarioTitle = Objects.requireNonNull(scenarioTitle, "scenarioTitle must not be null");
		mapVersion = Objects.requireNonNull(mapVersion, "mapVersion must not be null");
		placementMode = Objects.requireNonNull(placementMode, "placementMode must not be null");
		roster = List.copyOf(roster);
		confirmationToken = Objects.requireNonNull(confirmationToken, "confirmationToken must not be null");
		if (confirmationToken.length() > 80) throw new IllegalArgumentException("confirmationToken is too long");
	}

	public ScenarioLaunchPlan withConfirmationToken(String token) {
		return new ScenarioLaunchPlan(
				scenarioId, scenarioTitle, mapVersion, deterministicEvents, placementMode, roster, token);
	}

	public record Agent(
			int slot,
			String displayName,
			String provider,
			String model,
			String reasoning,
			String serviceTier,
			String team,
			AgentGameMode gameMode
	) {
		public Agent {
			if (slot < 1) {
				throw new IllegalArgumentException("slot must be positive");
			}
			displayName = Objects.requireNonNull(displayName, "displayName must not be null");
			provider = Objects.requireNonNull(provider, "provider must not be null");
			model = Objects.requireNonNull(model, "model must not be null");
			reasoning = Objects.requireNonNull(reasoning, "reasoning must not be null");
			serviceTier = Objects.requireNonNull(serviceTier, "serviceTier must not be null");
			team = Objects.requireNonNull(team, "team must not be null");
			gameMode = Objects.requireNonNull(gameMode, "gameMode must not be null");
		}
	}
}
