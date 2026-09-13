package dev.agaminggod.arenaagents.scenario;

public record ScenarioScoreRule(
		String id,
		String title,
		String description,
		double weight,
		String evidenceMetric
) {
	public ScenarioScoreRule {
		id = ScenarioValidators.id(id, "score rule id");
		title = ScenarioValidators.text(title, "score rule title", 80);
		description = ScenarioValidators.text(description, "score rule description", 320);
		evidenceMetric = ScenarioValidators.id(evidenceMetric, "evidence metric");
		if (!Double.isFinite(weight) || weight <= 0.0D || weight > 100.0D) {
			throw ScenarioValidators.failure("INVALID_SCORE_WEIGHT", "score rule weight must be finite and between 0 and 100");
		}
	}
}
