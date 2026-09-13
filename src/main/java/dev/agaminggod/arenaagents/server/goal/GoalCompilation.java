package dev.agaminggod.arenaagents.server.goal;

import dev.agaminggod.arenaagents.agent.goal.GoalSpec;
import java.util.Objects;
import java.util.Optional;

public sealed interface GoalCompilation permits GoalCompilation.Accepted, GoalCompilation.NeedsTranslation, GoalCompilation.Rejected {
	enum Kind { ACCEPTED, NEEDS_TRANSLATION, REJECTED }

	Kind kind();

	Optional<GoalSpec> acceptedSpec();

	String playerMessage();

	static GoalCompilation accepted(GoalSpec spec, String message) {
		return new Accepted(spec, message);
	}

	static GoalCompilation needsTranslation(String message) {
		return new NeedsTranslation(message);
	}

	static GoalCompilation rejected(String message) {
		return new Rejected(message);
	}

	record Accepted(GoalSpec spec, String playerMessage) implements GoalCompilation {
		public Accepted {
			Objects.requireNonNull(spec, "spec must not be null");
			playerMessage = checkedMessage(playerMessage);
		}

		@Override
		public Kind kind() {
			return Kind.ACCEPTED;
		}

		@Override
		public Optional<GoalSpec> acceptedSpec() {
			return Optional.of(spec);
		}
	}

	record NeedsTranslation(String playerMessage) implements GoalCompilation {
		public NeedsTranslation {
			playerMessage = checkedMessage(playerMessage);
		}

		@Override
		public Kind kind() {
			return Kind.NEEDS_TRANSLATION;
		}

		@Override
		public Optional<GoalSpec> acceptedSpec() {
			return Optional.empty();
		}
	}

	record Rejected(String playerMessage) implements GoalCompilation {
		public Rejected {
			playerMessage = checkedMessage(playerMessage);
		}

		@Override
		public Kind kind() {
			return Kind.REJECTED;
		}

		@Override
		public Optional<GoalSpec> acceptedSpec() {
			return Optional.empty();
		}
	}

	private static String checkedMessage(String message) {
		String checked = Objects.requireNonNull(message, "playerMessage must not be null").strip();
		if (checked.isEmpty() || checked.length() > 512) throw new IllegalArgumentException("playerMessage has an invalid length");
		return checked;
	}
}
