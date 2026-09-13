package dev.agaminggod.arenaagents.server.goal;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;
import dev.agaminggod.arenaagents.agent.AgentDomainException;
import dev.agaminggod.arenaagents.agent.AgentId;
import dev.agaminggod.arenaagents.agent.goal.GoalPredicate;
import dev.agaminggod.arenaagents.agent.goal.GoalSpecCodec;
import java.util.Optional;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;

public final class PendingGoalDraftCodec {
	private static final Gson GSON = new GsonBuilder().disableHtmlEscaping().serializeNulls().create();
	private static final Set<String> LEGACY_FIELDS = Set.of(
			"draft_id", "agent_id", "requesting_player_id", "original_request", "candidate_ids", "proposed_predicate", "intent", "created_at_tick",
			"expected_goal_revision", "expected_goal_id"
	);
	private static final Set<String> DIMENSION_FIELDS = Set.of(
			"draft_id", "agent_id", "requesting_player_id", "original_request", "dimension_id", "candidate_ids", "proposed_predicate", "intent",
			"created_at_tick", "expected_goal_revision", "expected_goal_id"
	);
	private static final Set<String> FIELDS = Set.of(
			"draft_id", "agent_id", "requesting_player_id", "original_request", "dimension_id", "candidate_ids", "translation_constraint",
			"proposed_predicate", "intent", "created_at_tick", "expected_goal_revision", "expected_goal_id"
	);
	private final GoalSpecCodec goalCodec = new GoalSpecCodec();

	public String encode(PendingGoalDraft draft) {
		JsonObject json = new JsonObject();
		json.addProperty("draft_id", draft.draftId().toString());
		json.addProperty("agent_id", draft.agentId().toString());
		json.addProperty("requesting_player_id", draft.requestingPlayerId().toString());
		json.addProperty("original_request", draft.originalRequest());
		json.addProperty("dimension_id", draft.dimensionId());
		json.add("candidate_ids", GSON.toJsonTree(draft.candidateIds()));
		json.add("translation_constraint", encodeConstraint(draft.translationConstraint()));
		draft.proposedPredicate().ifPresentOrElse(
				predicate -> json.add("proposed_predicate", goalCodec.encodePredicateObject(predicate)),
				() -> json.add("proposed_predicate", null)
		);
		json.addProperty("intent", draft.intent().name());
		json.addProperty("created_at_tick", draft.createdAtTick());
		json.addProperty("expected_goal_revision", draft.expectedGoalRevision());
		draft.expectedGoalId().ifPresentOrElse(
				goalId -> json.addProperty("expected_goal_id", goalId.toString()),
				() -> json.add("expected_goal_id", null)
		);
		return GSON.toJson(json);
	}

	public PendingGoalDraft decode(String encoded) {
		try {
			JsonObject json = object(JsonParser.parseString(encoded), "draft");
			boolean legacyDimension = json.keySet().equals(LEGACY_FIELDS);
			boolean legacyConstraint = legacyDimension || json.keySet().equals(DIMENSION_FIELDS);
			if (!legacyConstraint && !json.keySet().equals(FIELDS)) throw failure("UNKNOWN_GOAL_DRAFT_FIELD", "Draft fields differ from the closed schema");
			JsonElement proposed = field(json, "proposed_predicate");
			Optional<GoalPredicate> predicate = proposed.isJsonNull()
					? Optional.empty()
					: Optional.of(goalCodec.decodePredicateObject(object(proposed, "proposed_predicate")));
			JsonElement expectedGoalId = field(json, "expected_goal_id");
			return new PendingGoalDraft(
					uuid(string(json, "draft_id"), "draft_id"),
					AgentId.parse(string(json, "agent_id")),
					uuid(string(json, "requesting_player_id"), "requesting_player_id"),
					string(json, "original_request"),
					legacyDimension ? GoalPredicate.DEFAULT_DIMENSION : string(json, "dimension_id"),
					strings(json, "candidate_ids"),
					legacyConstraint ? GoalTranslationConstraint.none() : decodeConstraint(
							object(field(json, "translation_constraint"), "translation_constraint")),
					predicate,
					enumeration(DraftIntent.class, string(json, "intent"), "intent"),
					exactLong(json, "created_at_tick"),
					exactLong(json, "expected_goal_revision"),
					expectedGoalId.isJsonNull()
						? Optional.empty()
						: Optional.of(uuid(string(json, "expected_goal_id"), "expected_goal_id"))
			);
		} catch (AgentDomainException exception) {
			throw exception;
		} catch (JsonParseException | IllegalStateException | IllegalArgumentException exception) {
			throw failure("INVALID_GOAL_DRAFT", "Invalid persisted goal draft: " + exception.getMessage());
		}
	}

	private static JsonObject encodeConstraint(GoalTranslationConstraint constraint) {
		JsonObject json = new JsonObject();
		JsonArray clauses = new JsonArray();
		for (GoalTranslationConstraint.KillClause clause : constraint.killClauses()) {
			JsonObject clauseJson = new JsonObject();
			JsonArray alternatives = new JsonArray();
			for (GoalTranslationConstraint.KillAlternative alternative : clause.alternatives()) {
				JsonObject alternativeJson = new JsonObject();
				alternativeJson.add("entity_types", GSON.toJsonTree(alternative.entityTypes()));
				alternativeJson.addProperty("count", alternative.count());
				alternatives.add(alternativeJson);
			}
			clauseJson.add("alternatives", alternatives);
			clauses.add(clauseJson);
		}
		json.add("kill_clauses", clauses);
		JsonArray itemClauses = new JsonArray();
		for (GoalTranslationConstraint.ItemClause clause : constraint.itemClauses()) {
			JsonObject clauseJson = new JsonObject();
			JsonArray alternatives = new JsonArray();
			for (GoalTranslationConstraint.ItemAlternative alternative : clause.alternatives()) {
				JsonObject alternativeJson = new JsonObject();
				alternativeJson.add("item_ids", GSON.toJsonTree(alternative.itemIds()));
				alternativeJson.addProperty("count", alternative.count());
				alternatives.add(alternativeJson);
			}
			clauseJson.add("alternatives", alternatives);
			itemClauses.add(clauseJson);
		}
		json.add("item_clauses", itemClauses);
		return json;
	}

	private static GoalTranslationConstraint decodeConstraint(JsonObject json) {
		boolean legacyItemConstraints = json.keySet().equals(Set.of("kill_clauses"));
		if (!legacyItemConstraints && !json.keySet().equals(Set.of("kill_clauses", "item_clauses"))) {
			throw failure("UNKNOWN_GOAL_DRAFT_FIELD", "Translation constraint fields differ from the closed schema");
		}
		JsonElement clausesValue = field(json, "kill_clauses");
		if (!clausesValue.isJsonArray()) throw failure("INVALID_GOAL_DRAFT", "kill_clauses must be an array");
		ArrayList<GoalTranslationConstraint.KillClause> clauses = new ArrayList<>();
		for (JsonElement clauseValue : clausesValue.getAsJsonArray()) {
			JsonObject clause = object(clauseValue, "kill_clause");
			if (!clause.keySet().equals(Set.of("alternatives"))) {
				throw failure("UNKNOWN_GOAL_DRAFT_FIELD", "Kill clause fields differ from the closed schema");
			}
			JsonElement alternativesValue = field(clause, "alternatives");
			if (!alternativesValue.isJsonArray()) throw failure("INVALID_GOAL_DRAFT", "alternatives must be an array");
			ArrayList<GoalTranslationConstraint.KillAlternative> alternatives = new ArrayList<>();
			for (JsonElement alternativeValue : alternativesValue.getAsJsonArray()) {
				JsonObject alternative = object(alternativeValue, "kill_alternative");
				if (!alternative.keySet().equals(Set.of("entity_types", "count"))) {
					throw failure("UNKNOWN_GOAL_DRAFT_FIELD", "Kill alternative fields differ from the closed schema");
				}
				long count = exactLong(alternative, "count");
				if (count < Integer.MIN_VALUE || count > Integer.MAX_VALUE) {
					throw failure("INVALID_GOAL_DRAFT", "kill alternative count is out of range");
				}
				alternatives.add(new GoalTranslationConstraint.KillAlternative(
						strings(alternative, "entity_types"), (int) count));
			}
			clauses.add(new GoalTranslationConstraint.KillClause(alternatives));
		}
		ArrayList<GoalTranslationConstraint.ItemClause> itemClauses = legacyItemConstraints
				? new ArrayList<>()
				: decodeItemClauses(json);
		return clauses.isEmpty() && itemClauses.isEmpty()
				? GoalTranslationConstraint.none()
				: new GoalTranslationConstraint(clauses, itemClauses);
	}

	private static ArrayList<GoalTranslationConstraint.ItemClause> decodeItemClauses(JsonObject json) {
		JsonElement clausesValue = field(json, "item_clauses");
		if (!clausesValue.isJsonArray()) throw failure("INVALID_GOAL_DRAFT", "item_clauses must be an array");
		ArrayList<GoalTranslationConstraint.ItemClause> clauses = new ArrayList<>();
		for (JsonElement clauseValue : clausesValue.getAsJsonArray()) {
			JsonObject clause = object(clauseValue, "item_clause");
			if (!clause.keySet().equals(Set.of("alternatives"))) {
				throw failure("UNKNOWN_GOAL_DRAFT_FIELD", "Item clause fields differ from the closed schema");
			}
			JsonElement alternativesValue = field(clause, "alternatives");
			if (!alternativesValue.isJsonArray()) throw failure("INVALID_GOAL_DRAFT", "alternatives must be an array");
			ArrayList<GoalTranslationConstraint.ItemAlternative> alternatives = new ArrayList<>();
			for (JsonElement alternativeValue : alternativesValue.getAsJsonArray()) {
				JsonObject alternative = object(alternativeValue, "item_alternative");
				if (!alternative.keySet().equals(Set.of("item_ids", "count"))) {
					throw failure("UNKNOWN_GOAL_DRAFT_FIELD", "Item alternative fields differ from the closed schema");
				}
				long count = exactLong(alternative, "count");
				if (count < Integer.MIN_VALUE || count > Integer.MAX_VALUE) {
					throw failure("INVALID_GOAL_DRAFT", "item alternative count is out of range");
				}
				alternatives.add(new GoalTranslationConstraint.ItemAlternative(
						strings(alternative, "item_ids"), (int) count));
			}
			clauses.add(new GoalTranslationConstraint.ItemClause(alternatives));
		}
		return clauses;
	}

	private static List<String> strings(JsonObject object, String field) {
		JsonElement value = field(object, field);
		if (!value.isJsonArray()) throw failure("INVALID_GOAL_DRAFT", field + " must be an array");
		ArrayList<String> result = new ArrayList<>();
		for (JsonElement element : value.getAsJsonArray()) {
			if (!element.isJsonPrimitive() || !element.getAsJsonPrimitive().isString()) {
				throw failure("INVALID_GOAL_DRAFT", field + " must contain strings");
			}
			result.add(element.getAsString());
		}
		return List.copyOf(result);
	}

	private static JsonElement field(JsonObject object, String field) {
		if (!object.has(field)) throw failure("INVALID_GOAL_DRAFT", "Missing draft field: " + field);
		return object.get(field);
	}

	private static JsonObject object(JsonElement value, String field) {
		if (value == null || !value.isJsonObject()) throw failure("INVALID_GOAL_DRAFT", field + " must be an object");
		return value.getAsJsonObject();
	}

	private static String string(JsonObject object, String field) {
		JsonElement value = field(object, field);
		if (!value.isJsonPrimitive() || !value.getAsJsonPrimitive().isString()) throw failure("INVALID_GOAL_DRAFT", field + " must be a string");
		return value.getAsString();
	}

	private static long exactLong(JsonObject object, String field) {
		JsonElement value = field(object, field);
		if (!value.isJsonPrimitive() || !value.getAsJsonPrimitive().isNumber()) throw failure("INVALID_GOAL_DRAFT", field + " must be an integer");
		try {
			return value.getAsBigDecimal().longValueExact();
		} catch (ArithmeticException | NumberFormatException exception) {
			throw failure("INVALID_GOAL_DRAFT", field + " must be an integer");
		}
	}

	private static UUID uuid(String value, String field) {
		try {
			return UUID.fromString(value);
		} catch (IllegalArgumentException exception) {
			throw failure("INVALID_GOAL_DRAFT", field + " must be a UUID");
		}
	}

	private static <E extends Enum<E>> E enumeration(Class<E> type, String value, String field) {
		try {
			return Enum.valueOf(type, value);
		} catch (IllegalArgumentException exception) {
			throw failure("INVALID_GOAL_DRAFT", "Unknown " + field + ": " + value);
		}
	}

	private static AgentDomainException failure(String code, String message) {
		return new AgentDomainException(code, message);
	}
}
