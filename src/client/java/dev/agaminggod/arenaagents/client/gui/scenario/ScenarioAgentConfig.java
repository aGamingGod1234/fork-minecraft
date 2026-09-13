package dev.agaminggod.arenaagents.client.gui.scenario;

import dev.agaminggod.arenaagents.agent.AgentConstants;
import dev.agaminggod.arenaagents.agent.AgentGameMode;
import dev.agaminggod.arenaagents.control.AgentControlCatalog;
import java.util.List;
import java.util.Objects;

public record ScenarioAgentConfig(
		String provider,
		String model,
		String reasoning,
		String serviceTier,
		String name,
		String team,
		AgentGameMode gameMode
) {
	public static final List<String> TEAMS = List.of("Solo", "Red", "Blue", "Gold", "Violet");

	public ScenarioAgentConfig {
		provider = AgentControlCatalog.requireProvider(provider);
		model = requireChoice(model, AgentControlCatalog.models(provider), "model");
		reasoning = requireChoice(reasoning, AgentControlCatalog.reasoningEfforts(provider, model), "reasoning");
		serviceTier = requireChoice(serviceTier, AgentControlCatalog.serviceTiers(provider, model), "speed mode");
		name = normalizeName(name);
		team = requireChoice(team, TEAMS, "team");
		gameMode = Objects.requireNonNull(gameMode, "gameMode must not be null");
	}

	public static ScenarioAgentConfig defaults(AgentGameMode gameMode) {
		String provider = AgentControlCatalog.providers().getFirst();
		String model = AgentControlCatalog.defaultModel(provider);
		return new ScenarioAgentConfig(
				provider,
				model,
				AgentControlCatalog.defaultReasoning(provider, model),
				"priority",
				"",
				"Solo",
				gameMode
		);
	}

	public static ScenarioAgentConfig configuredDefaults(
			String provider,
			String model,
			String reasoning,
			AgentGameMode gameMode
	) {
		String tier = AgentControlCatalog.serviceTiers(provider, model).contains("priority")
				? "priority" : AgentControlCatalog.serviceTiers(provider, model).getFirst();
		return new ScenarioAgentConfig(provider, model, reasoning, tier, "", "Solo", gameMode);
	}

	public ScenarioAgentConfig withProvider(String nextProvider) {
		String checkedProvider = AgentControlCatalog.requireProvider(nextProvider);
		String nextModel = AgentControlCatalog.defaultModel(checkedProvider);
		return new ScenarioAgentConfig(
				checkedProvider,
				nextModel,
				AgentControlCatalog.defaultReasoning(checkedProvider, nextModel),
				"priority",
				name,
				team,
				gameMode
		);
	}

	public ScenarioAgentConfig withModel(String nextModel) {
		String checkedModel = requireChoice(nextModel, AgentControlCatalog.models(provider), "model");
		return new ScenarioAgentConfig(
				provider,
				checkedModel,
				AgentControlCatalog.defaultReasoning(provider, checkedModel),
				AgentControlCatalog.serviceTiers(provider, checkedModel).contains(serviceTier) ? serviceTier : "priority",
				name,
				team,
				gameMode
		);
	}

	public ScenarioAgentConfig withReasoning(String nextReasoning) {
		return new ScenarioAgentConfig(provider, model, nextReasoning, serviceTier, name, team, gameMode);
	}

	public ScenarioAgentConfig withServiceTier(String nextTier) {
		return new ScenarioAgentConfig(provider, model, reasoning, nextTier, name, team, gameMode);
	}

	public ScenarioAgentConfig withName(String nextName) {
		return new ScenarioAgentConfig(provider, model, reasoning, serviceTier, nextName, team, gameMode);
	}

	public ScenarioAgentConfig withTeam(String nextTeam) {
		return new ScenarioAgentConfig(provider, model, reasoning, serviceTier, name, nextTeam, gameMode);
	}

	public ScenarioAgentConfig withGameMode(AgentGameMode nextMode) {
		return new ScenarioAgentConfig(provider, model, reasoning, serviceTier, name, team, nextMode);
	}

	private static String normalizeName(String value) {
		String normalized = Objects.requireNonNullElse(value, "").strip().replaceAll("\\s+", " ");
		if (normalized.length() > AgentConstants.MAX_USER_NAME_LENGTH
				|| normalized.codePoints().anyMatch(Character::isISOControl)) {
			throw new IllegalArgumentException("name is invalid");
		}
		return normalized;
	}

	private static String requireChoice(String value, List<String> choices, String field) {
		String checked = Objects.requireNonNull(value, field + " must not be null");
		if (!choices.contains(checked)) {
			throw new IllegalArgumentException(field + " is not supported: " + checked);
		}
		return checked;
	}
}
