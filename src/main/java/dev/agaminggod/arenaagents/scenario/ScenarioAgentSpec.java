package dev.agaminggod.arenaagents.scenario;

import dev.agaminggod.arenaagents.agent.AgentGameMode;

import java.util.Objects;
import java.util.Optional;

public record ScenarioAgentSpec(
		int slot,
		String displayName,
		String provider,
		String model,
		String reasoning,
		String serviceTier,
		Optional<String> team,
		AgentGameMode gameMode
) {
	public ScenarioAgentSpec {
		if (slot < 1 || slot > ScenarioPreset.MAXIMUM_AGENTS) {
			throw new IllegalArgumentException("agent slot must be in [1, 16]");
		}
		displayName = required(displayName, "displayName", 64);
		provider = required(provider, "provider", 24);
		model = required(model, "model", 128);
		reasoning = required(reasoning, "reasoning", 32);
		serviceTier = required(serviceTier, "serviceTier", 24);
		team = Objects.requireNonNull(team, "team must not be null")
				.map(value -> required(value, "team", 48));
		Objects.requireNonNull(gameMode, "gameMode must not be null");
	}

	private static String required(String value, String field, int maximumLength) {
		String normalized = Objects.requireNonNull(value, field + " must not be null").trim();
		if (normalized.isEmpty() || normalized.length() > maximumLength) {
			throw new IllegalArgumentException(field + " must contain 1-" + maximumLength + " characters");
		}
		return normalized;
	}
}
