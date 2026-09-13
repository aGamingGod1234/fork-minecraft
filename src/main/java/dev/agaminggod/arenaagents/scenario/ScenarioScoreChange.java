package dev.agaminggod.arenaagents.scenario;

public record ScenarioScoreChange(
		String participantId,
		String ruleId,
		double points,
		String reason,
		int evidenceSequence
) {
	public ScenarioScoreChange {
		participantId = ScenarioValidators.id(participantId, "participant id");
		ruleId = ScenarioValidators.id(ruleId, "score rule id");
		if (!Double.isFinite(points)) {
			throw ScenarioValidators.failure("INVALID_SCORE", "score points must be finite");
		}
		reason = ScenarioValidators.text(reason, "score reason", 320);
		if (evidenceSequence < 0) {
			throw ScenarioValidators.failure("INVALID_SCORE", "evidenceSequence must not be negative");
		}
	}
}
