package dev.agaminggod.arenaagents.agent.goal;

import dev.agaminggod.arenaagents.agent.AgentDomainException;
import java.util.List;
import java.util.Objects;

public record GoalEvidence(long verifiedAtTick, String reasonCode, List<Fact> facts) {
	public GoalEvidence {
		if (verifiedAtTick < 0L) throw invalid("Verification tick must be nonnegative");
		reasonCode = text(reasonCode, "reasonCode", 128);
		facts = List.copyOf(Objects.requireNonNull(facts, "facts must not be null"));
		if (facts.size() > GoalSpecCodec.MAX_LEAF_PREDICATES) throw invalid("Evidence fact limit exceeded");
		facts.forEach(fact -> Objects.requireNonNull(fact, "fact must not be null"));
	}

	public record Fact(String type, boolean satisfied, String expectedValue, String observedValue) {
		public Fact {
			type = text(type, "type", 128);
			expectedValue = text(expectedValue, "expectedValue", 512);
			observedValue = text(observedValue, "observedValue", 512);
		}
	}

	private static String text(String value, String field, int maximumLength) {
		String checked = Objects.requireNonNull(value, field + " must not be null").strip();
		if (checked.isEmpty() || checked.length() > maximumLength) throw invalid(field + " has an invalid length");
		return checked;
	}

	private static AgentDomainException invalid(String message) {
		return new AgentDomainException("INVALID_GOAL_EVIDENCE", message);
	}
}
