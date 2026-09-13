package dev.agaminggod.arenaagents.scenario;

import java.util.Locale;
import java.util.Objects;

/**
 * Deliberately narrow event ingress for the spectator feed. It cannot carry prompts,
 * observations, provider output, action arguments, or arbitrary metadata.
 */
public record ScenarioAgentEvent(
		long elapsedTick,
		String participantId,
		String participantDisplayName,
		Kind kind,
		ActionFamily actionFamily,
		double scoreDelta,
		PublicState state
) {
	public ScenarioAgentEvent {
		if (elapsedTick < 0L) throw new IllegalArgumentException("elapsedTick must not be negative");
		participantId = ScenarioValidators.id(participantId, "participant id");
		participantDisplayName = ScenarioValidators.text(participantDisplayName, "participant display name", 64);
		kind = Objects.requireNonNull(kind, "kind must not be null");
		actionFamily = Objects.requireNonNull(actionFamily, "actionFamily must not be null");
		if (!Double.isFinite(scoreDelta) || Math.abs(scoreDelta) > 1_000_000.0D) {
			throw new IllegalArgumentException("scoreDelta must be finite and bounded");
		}
		state = Objects.requireNonNull(state, "state must not be null");
	}

	public enum Kind {
		ACTION_STARTED,
		ACTION_COMPLETED,
		ACTION_FAILED,
		STATE_CHANGED,
		SCORE_CHANGED,
		ELIMINATED;

		public String wireName() {
			return name().toLowerCase(Locale.ROOT);
		}
	}

	public enum ActionFamily {
		MOVEMENT("moving"),
		HARVEST("harvesting"),
		BUILD("building"),
		CRAFT("crafting"),
		COMBAT("combat"),
		SURVIVAL("survival"),
		COMMUNICATION("communicating"),
		OTHER("acting");

		private final String displayVerb;

		ActionFamily(String displayVerb) {
			this.displayVerb = displayVerb;
		}

		public String wireName() {
			return name().toLowerCase(Locale.ROOT);
		}

		public String displayVerb() {
			return displayVerb;
		}
	}

	public enum PublicState {
		IDLE,
		THINKING,
		ACTING,
		RECOVERING,
		DONE,
		FAILED,
		DEAD;

		public String wireName() {
			return name().toLowerCase(Locale.ROOT);
		}
	}
}
