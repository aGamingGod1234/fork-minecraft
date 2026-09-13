package dev.agaminggod.arenaagents.scenario.result;

import com.google.gson.JsonObject;
import dev.agaminggod.arenaagents.scenario.ScenarioAgentEvent;
import java.math.BigDecimal;
import java.util.Comparator;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;

public record ScenarioPublicEvent(
		long elapsedTick,
		String participantId,
		String participantDisplayName,
		String category,
		String actionFamily,
		double scoreDelta,
		String state
) {
	private static final Pattern ID = Pattern.compile("[a-z0-9][a-z0-9_-]{0,63}");
	private static final Set<String> CATEGORIES = java.util.Arrays.stream(ScenarioAgentEvent.Kind.values())
			.map(ScenarioAgentEvent.Kind::wireName).collect(java.util.stream.Collectors.toUnmodifiableSet());
	private static final Set<String> ACTION_FAMILIES = java.util.Arrays.stream(ScenarioAgentEvent.ActionFamily.values())
			.map(ScenarioAgentEvent.ActionFamily::wireName).collect(java.util.stream.Collectors.toUnmodifiableSet());
	private static final Set<String> STATES = java.util.Arrays.stream(ScenarioAgentEvent.PublicState.values())
			.map(ScenarioAgentEvent.PublicState::wireName).collect(java.util.stream.Collectors.toUnmodifiableSet());
	public static final Comparator<ScenarioPublicEvent> CANONICAL_ORDER = Comparator
			.comparingLong(ScenarioPublicEvent::elapsedTick)
			.thenComparing(ScenarioPublicEvent::participantId)
			.thenComparing(ScenarioPublicEvent::participantDisplayName)
			.thenComparing(ScenarioPublicEvent::category)
			.thenComparing(ScenarioPublicEvent::actionFamily)
			.thenComparing(ScenarioPublicEvent::normalizedScoreDelta)
			.thenComparing(ScenarioPublicEvent::state);

	public ScenarioPublicEvent {
		if (elapsedTick < 0L) throw new IllegalArgumentException("elapsedTick must not be negative");
		participantId = id(participantId, "participantId");
		participantDisplayName = text(participantDisplayName, "participantDisplayName", 64);
		category = enumId(category, "category", CATEGORIES);
		actionFamily = enumId(actionFamily, "actionFamily", ACTION_FAMILIES);
		if (!Double.isFinite(scoreDelta) || Math.abs(scoreDelta) > 1_000_000.0D) {
			throw new IllegalArgumentException("scoreDelta must be finite and bounded");
		}
		state = enumId(state, "state", STATES);
	}

	/** Fixed-template display text derived solely from validated typed fields. */
	public String message() {
		String verb = ScenarioAgentEvent.ActionFamily.valueOf(actionFamily.toUpperCase(java.util.Locale.ROOT)).displayVerb();
		return switch (ScenarioAgentEvent.Kind.valueOf(category.toUpperCase(java.util.Locale.ROOT))) {
			case ACTION_STARTED -> participantDisplayName + " started " + verb + ".";
			case ACTION_COMPLETED -> participantDisplayName + " completed " + verb + ".";
			case ACTION_FAILED -> participantDisplayName + " could not complete " + verb + ".";
			case STATE_CHANGED -> participantDisplayName + " is now " + state + ".";
			case SCORE_CHANGED -> participantDisplayName + " scored " + signed(scoreDelta) + " points.";
			case ELIMINATED -> participantDisplayName + " was eliminated.";
		};
	}

	public String canonicalJson() {
		JsonObject value = new JsonObject();
		value.addProperty("elapsedTick", elapsedTick);
		value.addProperty("participantId", participantId);
		value.addProperty("participantDisplayName", participantDisplayName);
		value.addProperty("category", category);
		value.addProperty("actionFamily", actionFamily);
		value.addProperty("scoreDelta", normalizedDouble(scoreDelta));
		value.addProperty("state", state);
		return value.toString();
	}

	private BigDecimal normalizedScoreDelta() {
		return normalizedDouble(scoreDelta);
	}

	private static java.math.BigDecimal normalizedDouble(double value) {
		if (value == 0.0D) return java.math.BigDecimal.ZERO;
		return java.math.BigDecimal.valueOf(value).stripTrailingZeros();
	}

	private static String id(String value, String field) {
		Objects.requireNonNull(value, field + " must not be null");
		String normalized = value.strip().toLowerCase(java.util.Locale.ROOT);
		if (!ID.matcher(normalized).matches()) throw new IllegalArgumentException(field + " is invalid");
		return normalized;
	}

	private static String enumId(String value, String field, Set<String> allowed) {
		String normalized = id(value, field);
		if (!allowed.contains(normalized)) throw new IllegalArgumentException(field + " is unsupported");
		return normalized;
	}

	private static String signed(double value) {
		String normalized = normalizedDouble(value).toPlainString();
		return value > 0.0D ? "+" + normalized : normalized;
	}

	private static String text(String value, String field, int maximum) {
		Objects.requireNonNull(value, field + " must not be null");
		String normalized = value.strip().replaceAll("\\s+", " ");
		if (normalized.isEmpty() || normalized.length() > maximum) {
			throw new IllegalArgumentException(field + " is blank or too long");
		}
		return normalized;
	}
}
