package dev.agaminggod.arenaagents.server.goal;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import dev.agaminggod.arenaagents.agent.AgentDomainException;
import dev.agaminggod.arenaagents.agent.goal.GoalPredicate;
import dev.agaminggod.arenaagents.agent.goal.GoalSpec;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Strict camelCase bridge codec kept separate from the snake_case persisted codec. */
public final class GoalSpecWireCodec {
	private static final int MAX_DEPTH = 4;
	private static final int MAX_LEAVES = 16;

	public GoalPredicate decodePredicate(JsonObject json) {
		Counter leaves = new Counter();
		GoalPredicate result = decode(json, 0, leaves);
		if (leaves.value > MAX_LEAVES) throw invalid("Goal predicate has too many leaves");
		return result;
	}

	public JsonObject encodePredicate(GoalPredicate predicate) {
		JsonObject json = new JsonObject();
		switch (predicate) {
			case GoalPredicate.InventoryContains value -> {
				json.addProperty("type", "inventory_contains"); json.addProperty("itemId", value.itemId()); json.addProperty("count", value.count());
			}
			case GoalPredicate.InventoryContainsAny value -> {
				json.addProperty("type", "inventory_contains_any");
				JsonArray items = new JsonArray(); value.itemIds().forEach(items::add);
				json.add("itemIds", items); json.addProperty("count", value.count());
			}
			case GoalPredicate.PositionWithin value -> {
				json.addProperty("type", "position_within"); json.addProperty("dimensionId", value.dimensionId()); json.addProperty("x", value.x()); json.addProperty("y", value.y());
				json.addProperty("z", value.z()); json.addProperty("radius", value.radius()); json.addProperty("stableTicks", value.stableTicks());
			}
			case GoalPredicate.AdvancementGranted value -> {
				json.addProperty("type", "advancement_granted"); json.addProperty("advancementId", value.advancementId());
			}
			case GoalPredicate.EntityKilledByAgent value -> {
				json.addProperty("type", "entity_killed_by_agent"); json.addProperty("entityType", value.entityType());
				json.addProperty("afterGoalStart", value.afterGoalStart());
			}
			case GoalPredicate.BlockMatches value -> {
				json.addProperty("type", "block_matches"); json.addProperty("dimensionId", value.dimensionId()); json.addProperty("x", value.x()); json.addProperty("y", value.y());
				json.addProperty("z", value.z()); json.addProperty("blockId", value.blockId());
				JsonObject properties = new JsonObject(); value.properties().forEach(properties::addProperty); json.add("properties", properties);
			}
			case GoalPredicate.SurviveDuration value -> { json.addProperty("type", "survive_duration"); json.addProperty("ticks", value.ticks()); }
			case GoalPredicate.OperatorConfirmed ignored -> json.addProperty("type", "operator_confirmed");
			case GoalPredicate.AllOf value -> compound(json, "all_of", value.predicates());
			case GoalPredicate.AnyOf value -> compound(json, "any_of", value.predicates());
		}
		return json;
	}

	public JsonObject encodeSpec(GoalSpec spec) {
		JsonObject json = new JsonObject();
		json.addProperty("originalRequest", spec.originalRequest());
		json.add("predicate", encodePredicate(spec.completion()));
		json.addProperty("createdAtTick", spec.createdAtTick());
		json.addProperty("fingerprint", spec.fingerprint());
		return json;
	}

	public List<String> identifiers(GoalPredicate predicate) {
		ArrayList<String> result = new ArrayList<>();
		collectIdentifiers(predicate, result);
		return List.copyOf(result);
	}

	private GoalPredicate decode(JsonObject json, int depth, Counter leaves) {
		if (depth > MAX_DEPTH) throw invalid("Goal predicate nesting is too deep");
		String type = string(json, "type");
		return switch (type) {
			case "inventory_contains" -> { exact(json, Set.of("type", "itemId", "count")); leaves.add(); yield new GoalPredicate.InventoryContains(string(json, "itemId"), integer(json, "count")); }
			case "inventory_contains_any" -> {
				exact(json, Set.of("type", "itemIds", "count")); leaves.add();
				JsonElement items = json.get("itemIds");
				if (items == null || !items.isJsonArray() || items.getAsJsonArray().isEmpty()
						|| items.getAsJsonArray().size() > GoalPredicate.MAX_INVENTORY_ITEM_IDS) throw invalid("itemIds must contain between 1 and 64 entries");
				leaves.addItemReferences(items.getAsJsonArray().size());
				ArrayList<String> identifiers = new ArrayList<>(items.getAsJsonArray().size());
				for (JsonElement item : items.getAsJsonArray()) {
					if (!item.isJsonPrimitive() || !item.getAsJsonPrimitive().isString()) throw invalid("itemIds entries must be strings");
					identifiers.add(item.getAsString());
				}
				yield new GoalPredicate.InventoryContainsAny(identifiers, integer(json, "count"));
			}
			case "position_within" -> { boolean legacy = !json.has("dimensionId"); exact(json, legacy ? Set.of("type", "x", "y", "z", "radius", "stableTicks") : Set.of("type", "dimensionId", "x", "y", "z", "radius", "stableTicks")); leaves.add(); yield new GoalPredicate.PositionWithin(legacy ? GoalPredicate.DEFAULT_DIMENSION : string(json, "dimensionId"), number(json, "x"), number(json, "y"), number(json, "z"), number(json, "radius"), integer(json, "stableTicks")); }
			case "advancement_granted" -> { exact(json, Set.of("type", "advancementId")); leaves.add(); yield new GoalPredicate.AdvancementGranted(string(json, "advancementId")); }
			case "entity_killed_by_agent" -> { exact(json, Set.of("type", "entityType", "afterGoalStart")); leaves.add(); yield new GoalPredicate.EntityKilledByAgent(string(json, "entityType"), bool(json, "afterGoalStart")); }
			case "block_matches" -> { boolean legacy = !json.has("dimensionId"); exact(json, legacy ? Set.of("type", "x", "y", "z", "blockId", "properties") : Set.of("type", "dimensionId", "x", "y", "z", "blockId", "properties")); leaves.add(); yield new GoalPredicate.BlockMatches(legacy ? GoalPredicate.DEFAULT_DIMENSION : string(json, "dimensionId"), integer(json, "x"), integer(json, "y"), integer(json, "z"), string(json, "blockId"), properties(json, "properties")); }
			case "survive_duration" -> { exact(json, Set.of("type", "ticks")); leaves.add(); yield new GoalPredicate.SurviveDuration(longInteger(json, "ticks")); }
			case "operator_confirmed" -> { exact(json, Set.of("type")); leaves.add(); yield new GoalPredicate.OperatorConfirmed(); }
			case "all_of" -> new GoalPredicate.AllOf(decodeChildren(json, depth, leaves));
			case "any_of" -> new GoalPredicate.AnyOf(decodeChildren(json, depth, leaves));
			default -> throw invalid("Unknown goal predicate type: " + type);
		};
	}

	private List<GoalPredicate> decodeChildren(JsonObject json, int depth, Counter leaves) {
		exact(json, Set.of("type", "predicates"));
		JsonElement element = json.get("predicates");
		if (element == null || !element.isJsonArray() || element.getAsJsonArray().isEmpty()) throw invalid("predicates must be a nonempty array");
		ArrayList<GoalPredicate> children = new ArrayList<>();
		for (JsonElement child : element.getAsJsonArray()) {
			if (!child.isJsonObject()) throw invalid("predicate child must be an object");
			children.add(decode(child.getAsJsonObject(), depth + 1, leaves));
		}
		return List.copyOf(children);
	}

	private void compound(JsonObject json, String type, List<GoalPredicate> predicates) {
		json.addProperty("type", type); JsonArray values = new JsonArray(); predicates.forEach(value -> values.add(encodePredicate(value))); json.add("predicates", values);
	}

	private static void collectIdentifiers(GoalPredicate predicate, List<String> target) {
		switch (predicate) {
			case GoalPredicate.InventoryContains value -> target.add(value.itemId());
			case GoalPredicate.InventoryContainsAny value -> target.addAll(value.itemIds());
			case GoalPredicate.AdvancementGranted value -> target.add(value.advancementId());
			case GoalPredicate.EntityKilledByAgent value -> target.add(value.entityType());
			case GoalPredicate.BlockMatches value -> target.add(value.blockId());
			case GoalPredicate.AllOf value -> value.predicates().forEach(child -> collectIdentifiers(child, target));
			case GoalPredicate.AnyOf value -> value.predicates().forEach(child -> collectIdentifiers(child, target));
			default -> { }
		}
	}

	private static Map<String, String> properties(JsonObject json, String field) {
		JsonElement element = json.get(field); if (element == null || !element.isJsonObject()) throw invalid(field + " must be an object");
		Map<String, String> values = new LinkedHashMap<>();
		for (var entry : element.getAsJsonObject().entrySet()) {
			if (!entry.getValue().isJsonPrimitive() || !entry.getValue().getAsJsonPrimitive().isString()) throw invalid(field + " values must be strings");
			values.put(entry.getKey(), entry.getValue().getAsString());
		}
		return Map.copyOf(values);
	}

	private static String string(JsonObject json, String field) { JsonElement value = json.get(field); if (value == null || !value.isJsonPrimitive() || !value.getAsJsonPrimitive().isString()) throw invalid(field + " must be a string"); return value.getAsString(); }
	private static boolean bool(JsonObject json, String field) { JsonElement value = json.get(field); if (value == null || !value.isJsonPrimitive() || !value.getAsJsonPrimitive().isBoolean()) throw invalid(field + " must be a boolean"); return value.getAsBoolean(); }
	private static double number(JsonObject json, String field) { JsonElement value = json.get(field); if (value == null || !value.isJsonPrimitive() || !value.getAsJsonPrimitive().isNumber()) throw invalid(field + " must be a number"); return value.getAsDouble(); }
	private static int integer(JsonObject json, String field) { long value = longInteger(json, field); if (value < Integer.MIN_VALUE || value > Integer.MAX_VALUE) throw invalid(field + " is out of range"); return (int) value; }
	private static long longInteger(JsonObject json, String field) { JsonElement value = json.get(field); if (value == null || !value.isJsonPrimitive() || !value.getAsJsonPrimitive().isNumber()) throw invalid(field + " must be an integer"); try { return value.getAsBigDecimal().longValueExact(); } catch (ArithmeticException exception) { throw invalid(field + " must be an integer"); } }
	private static void exact(JsonObject json, Set<String> fields) { if (!json.keySet().equals(fields)) throw invalid("Predicate fields differ from the closed schema"); }
	private static AgentDomainException invalid(String message) { return new AgentDomainException("INVALID_GOAL_PREDICATE", message); }
	private static final class Counter {
		int value;
		int itemReferences;
		void add() { if (++value > MAX_LEAVES) throw invalid("Goal predicate has too many leaves"); }
		void addItemReferences(int count) {
			itemReferences += count;
			if (itemReferences > GoalPredicate.MAX_INVENTORY_ITEM_IDS) {
				throw new AgentDomainException("GOAL_PREDICATE_LIMIT_EXCEEDED", "Goal predicate contains too many grouped inventory item IDs");
			}
		}
	}
}
