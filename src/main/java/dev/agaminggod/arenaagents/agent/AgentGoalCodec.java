package dev.agaminggod.arenaagents.agent;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import dev.agaminggod.arenaagents.agent.goal.GoalEvidence;
import dev.agaminggod.arenaagents.agent.goal.GoalPredicate;
import dev.agaminggod.arenaagents.agent.goal.GoalSpec;
import dev.agaminggod.arenaagents.agent.goal.GoalSpecCodec;
import dev.agaminggod.arenaagents.agent.goal.GoalStatus;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

public final class AgentGoalCodec {
	private final GoalSpecCodec specCodec = new GoalSpecCodec();

	public JsonObject encode(AgentGoal goal) {
		JsonObject json = new JsonObject();
		json.addProperty("goal_id", goal.goalId().toString());
		json.addProperty("prompt", goal.prompt());
		JsonArray steering = new JsonArray();
		goal.steeringInstructions().forEach(steering::add);
		json.add("steering", steering);
		json.add("spec", specCodec.encodeObject(goal.spec()));
		json.addProperty("status", goal.status().name());
		goal.evidence().ifPresentOrElse(value -> json.add("evidence", encodeEvidence(value)), () -> json.add("evidence", null));
		json.addProperty("created_at_epoch_ms", goal.createdAtEpochMs());
		json.addProperty("updated_at_epoch_ms", goal.updatedAtEpochMs());
		return json;
	}

	public AgentGoal decode(JsonObject json, GoalStatus legacyStatus) {
		Set<String> legacyKeys = Set.of("goal_id", "prompt", "steering", "created_at_epoch_ms", "updated_at_epoch_ms");
		Set<String> currentKeys = Set.of("goal_id", "prompt", "steering", "spec", "status", "evidence", "created_at_epoch_ms", "updated_at_epoch_ms");
		if (!json.keySet().equals(legacyKeys) && !json.keySet().equals(currentKeys)) {
			throw failure("UNKNOWN_GOAL_FIELD", "Persisted goal fields differ from the closed schema");
		}
		ArrayList<String> steering = new ArrayList<>();
		JsonElement steeringElement = field(json, "steering");
		if (!steeringElement.isJsonArray()) throw failure("steering must be an array");
		for (JsonElement element : steeringElement.getAsJsonArray()) {
			if (!element.isJsonPrimitive() || !element.getAsJsonPrimitive().isString()) throw failure("steering entries must be strings");
			steering.add(element.getAsString());
		}
		String prompt = string(json, "prompt");
		GoalSpec spec = json.has("spec") && !json.get("spec").isJsonNull()
				? specCodec.decodeObject(object(json.get("spec"), "spec"))
				: GoalSpec.create(prompt, new GoalPredicate.OperatorConfirmed(), 0L);
		GoalStatus status = json.has("status") && !json.get("status").isJsonNull()
				? enumeration(GoalStatus.class, string(json, "status"), "status")
				: legacyStatus;
		if (status == GoalStatus.AWAITING_CLARIFICATION
				&& spec.completion() instanceof GoalPredicate.OperatorConfirmed) {
			status = GoalStatus.ACTIVE;
		}
		Optional<GoalEvidence> evidence = !json.has("evidence") || json.get("evidence").isJsonNull()
				? Optional.empty()
				: Optional.of(decodeEvidence(object(json.get("evidence"), "evidence")));
		return new AgentGoal(
				uuid(string(json, "goal_id"), "goal_id"), prompt, steering, spec, status, evidence,
				exactLong(json, "created_at_epoch_ms"), exactLong(json, "updated_at_epoch_ms")
		);
	}

	private static JsonObject encodeEvidence(GoalEvidence evidence) {
		JsonObject json = new JsonObject();
		json.addProperty("verified_at_tick", evidence.verifiedAtTick());
		json.addProperty("reason_code", evidence.reasonCode());
		JsonArray facts = new JsonArray();
		for (GoalEvidence.Fact fact : evidence.facts()) {
			JsonObject value = new JsonObject();
			value.addProperty("type", fact.type());
			value.addProperty("satisfied", fact.satisfied());
			value.addProperty("expected_value", fact.expectedValue());
			value.addProperty("observed_value", fact.observedValue());
			facts.add(value);
		}
		json.add("facts", facts);
		return json;
	}

	private static GoalEvidence decodeEvidence(JsonObject json) {
		requireExactKeys(json, Set.of("verified_at_tick", "reason_code", "facts"));
		JsonElement factsElement = field(json, "facts");
		if (!factsElement.isJsonArray()) throw failure("evidence facts must be an array");
		ArrayList<GoalEvidence.Fact> facts = new ArrayList<>();
		for (JsonElement element : factsElement.getAsJsonArray()) {
			JsonObject fact = object(element, "evidence fact");
			requireExactKeys(fact, Set.of("type", "satisfied", "expected_value", "observed_value"));
			facts.add(new GoalEvidence.Fact(
					string(fact, "type"), bool(fact, "satisfied"),
					string(fact, "expected_value"), string(fact, "observed_value")
			));
		}
		return new GoalEvidence(exactLong(json, "verified_at_tick"), string(json, "reason_code"), facts);
	}

	private static void requireExactKeys(JsonObject object, Set<String> expected) {
		if (!object.keySet().equals(expected)) {
			throw failure("UNKNOWN_GOAL_FIELD", "Persisted goal evidence fields differ from the closed schema");
		}
	}

	private static JsonElement field(JsonObject object, String field) {
		if (!object.has(field)) throw failure("Missing persisted goal field: " + field);
		return object.get(field);
	}

	private static JsonObject object(JsonElement element, String field) {
		if (element == null || !element.isJsonObject()) throw failure(field + " must be an object");
		return element.getAsJsonObject();
	}

	private static String string(JsonObject object, String field) {
		JsonElement value = field(object, field);
		if (!value.isJsonPrimitive() || !value.getAsJsonPrimitive().isString()) throw failure(field + " must be a string");
		return value.getAsString();
	}

	private static boolean bool(JsonObject object, String field) {
		JsonElement value = field(object, field);
		if (!value.isJsonPrimitive() || !value.getAsJsonPrimitive().isBoolean()) throw failure(field + " must be a boolean");
		return value.getAsBoolean();
	}

	private static long exactLong(JsonObject object, String field) {
		JsonElement value = field(object, field);
		if (!value.isJsonPrimitive() || !value.getAsJsonPrimitive().isNumber()) throw failure(field + " must be an integer");
		try {
			return value.getAsBigDecimal().longValueExact();
		} catch (ArithmeticException | NumberFormatException exception) {
			throw failure(field + " must be an integer");
		}
	}

	private static UUID uuid(String value, String field) {
		try {
			return UUID.fromString(value);
		} catch (IllegalArgumentException exception) {
			throw failure(field + " must be a UUID");
		}
	}

	private static <E extends Enum<E>> E enumeration(Class<E> type, String value, String field) {
		try {
			return Enum.valueOf(type, value);
		} catch (IllegalArgumentException exception) {
			throw failure("Unknown " + field + ": " + value);
		}
	}

	private static AgentDomainException failure(String message) {
		return failure("INVALID_PERSISTED_GOAL", message);
	}

	private static AgentDomainException failure(String code, String message) {
		return new AgentDomainException(code, message);
	}
}
