package dev.agaminggod.arenaagents.scenario;

import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

public record ScenarioPreset(
		String id,
		int schemaVersion,
		String title,
		ScenarioCategory category,
		String subtitle,
		String objective,
		String mapVersion,
		int minimumAgents,
		int maximumAgents,
		long defaultDurationTicks,
		boolean supportsTeams,
		boolean playerCombat,
		String requiredGameMode,
		String accentColor,
		List<String> landmarks,
		List<String> dynamicEvents,
		List<String> presentationTags,
		List<ScenarioPhase> phases,
		List<ScenarioScoreRule> scoreRules
) {
	public static final int SUPPORTED_SCHEMA_VERSION = 1;
	public static final int MAXIMUM_AGENTS = 16;

	public ScenarioPreset {
		id = ScenarioValidators.id(id, "scenario id");
		if (schemaVersion != SUPPORTED_SCHEMA_VERSION) {
			throw ScenarioValidators.failure(
					"UNSUPPORTED_SCENARIO_VERSION",
					"unsupported scenario schema version " + schemaVersion
			);
		}
		title = ScenarioValidators.text(title, "scenario title", 96);
		category = Objects.requireNonNull(category, "category must not be null");
		subtitle = ScenarioValidators.text(subtitle, "scenario subtitle", 160);
		objective = ScenarioValidators.text(objective, "scenario objective", 480);
		mapVersion = ScenarioValidators.text(mapVersion, "map version", 32);
		requiredGameMode = ScenarioValidators.text(requiredGameMode, "required game mode", 32);
		accentColor = ScenarioValidators.text(accentColor, "accent color", 16);
		if (minimumAgents < 1 || maximumAgents > MAXIMUM_AGENTS || minimumAgents > maximumAgents) {
			throw ScenarioValidators.failure(
					"INVALID_AGENT_RANGE",
					"scenario agent range must be within 1-" + MAXIMUM_AGENTS
			);
		}
		if (defaultDurationTicks <= 0L || defaultDurationTicks > ScenarioSessionConfig.MAX_DURATION_TICKS) {
			throw ScenarioValidators.failure("INVALID_DURATION", "scenario duration is outside supported bounds");
		}
		landmarks = ScenarioValidators.textList(landmarks, "landmarks", 32, 160);
		dynamicEvents = ScenarioValidators.textList(dynamicEvents, "dynamic events", 32, 200);
		presentationTags = ScenarioValidators.textList(presentationTags, "presentation tags", 16, 48);
		phases = List.copyOf(Objects.requireNonNull(phases, "phases must not be null"));
		scoreRules = List.copyOf(Objects.requireNonNull(scoreRules, "scoreRules must not be null"));
		validatePhases(phases, defaultDurationTicks);
		validateScoreRules(scoreRules);
	}

	public void validateAgentCount(int count) {
		if (count < minimumAgents || count > maximumAgents) {
			throw ScenarioValidators.failure(
					"AGENT_COUNT_OUT_OF_RANGE",
					title + " requires " + minimumAgents + "-" + maximumAgents + " agents"
			);
		}
	}

	public Optional<ScenarioPhase> phaseAt(long elapsedTick) {
		if (elapsedTick < 0L || elapsedTick >= defaultDurationTicks) {
			return Optional.empty();
		}
		return phases.stream().filter(phase -> phase.contains(elapsedTick)).findFirst();
	}

	public ScenarioScoreRule requireScoreRule(String ruleId) {
		String normalizedId = ScenarioValidators.id(ruleId, "score rule id");
		return scoreRules.stream()
				.filter(rule -> rule.id().equals(normalizedId))
				.findFirst()
				.orElseThrow(() -> ScenarioValidators.failure(
						"UNKNOWN_SCORE_RULE",
						"unknown score rule " + normalizedId + " for " + id
				));
	}

	private static void validatePhases(List<ScenarioPhase> phases, long durationTicks) {
		if (phases.isEmpty()) {
			throw ScenarioValidators.failure("EMPTY_COLLECTION", "phases must not be empty");
		}
		Set<String> ids = new HashSet<>();
		long expectedStart = 0L;
		for (ScenarioPhase phase : phases) {
			Objects.requireNonNull(phase, "phases must not contain null");
			if (!ids.add(phase.id())) {
				throw ScenarioValidators.failure("DUPLICATE_PHASE_ID", "duplicate phase id " + phase.id());
			}
			if (phase.startTick() != expectedStart) {
				throw ScenarioValidators.failure("INVALID_PHASE_SCHEDULE", "scenario phases must be contiguous");
			}
			expectedStart = phase.endTickExclusive();
		}
		if (expectedStart != durationTicks) {
			throw ScenarioValidators.failure("INVALID_PHASE_SCHEDULE", "scenario phases must cover the full duration");
		}
	}

	private static void validateScoreRules(List<ScenarioScoreRule> scoreRules) {
		if (scoreRules.isEmpty()) {
			throw ScenarioValidators.failure("EMPTY_COLLECTION", "scoreRules must not be empty");
		}
		Set<String> ids = new HashSet<>();
		double totalWeight = 0.0D;
		for (ScenarioScoreRule rule : scoreRules) {
			Objects.requireNonNull(rule, "scoreRules must not contain null");
			if (!ids.add(rule.id())) {
				throw ScenarioValidators.failure("DUPLICATE_SCORE_RULE_ID", "duplicate score rule id " + rule.id());
			}
			totalWeight += rule.weight();
		}
		if (Math.abs(totalWeight - 100.0D) > 0.000_001D) {
			throw ScenarioValidators.failure("INVALID_SCORE_WEIGHT", "scenario score weights must total 100");
		}
	}
}
