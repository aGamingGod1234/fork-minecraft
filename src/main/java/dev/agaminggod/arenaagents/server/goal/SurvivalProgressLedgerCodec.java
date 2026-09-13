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
import java.util.ArrayList;
import java.util.Set;
import java.util.UUID;

/** Closed, versioned codec for survival progress stored in Minecraft SavedData. */
public final class SurvivalProgressLedgerCodec {
	private static final Gson GSON = new GsonBuilder().disableHtmlEscaping().create();
	private static final Set<String> ROOT_FIELDS = Set.of("schema_version", "entries");
	private static final Set<String> ENTRY_FIELDS = Set.of(
			"goal_id", "agent_id", "predicate_path", "required_ticks", "observed_ticks");

	public String encode(SurvivalProgressLedger.Snapshot snapshot) {
		JsonObject root = new JsonObject();
		root.addProperty("schema_version", snapshot.schemaVersion());
		JsonArray entries = new JsonArray();
		for (SurvivalProgressLedger.ProgressEntry entry : snapshot.entries()) {
			JsonObject encoded = new JsonObject();
			encoded.addProperty("goal_id", entry.goalId().toString());
			encoded.addProperty("agent_id", entry.agentId().toString());
			encoded.addProperty("predicate_path", entry.predicatePath());
			encoded.addProperty("required_ticks", entry.requiredTicks());
			encoded.addProperty("observed_ticks", entry.observedTicks());
			entries.add(encoded);
		}
		root.add("entries", entries);
		return GSON.toJson(root);
	}

	public SurvivalProgressLedger.Snapshot decode(String encoded) {
		try {
			JsonObject root = object(JsonParser.parseString(encoded), "survival progress");
			exactFields(root, ROOT_FIELDS, "survival progress");
			int version = exactInt(root, "schema_version");
			if (version != SurvivalProgressLedger.SCHEMA_VERSION) {
				throw failure("unsupported survival progress schema: " + version);
			}
			JsonElement entryValue = field(root, "entries");
			if (!entryValue.isJsonArray()) throw failure("entries must be an array");
			JsonArray entries = entryValue.getAsJsonArray();
			if (entries.size() > SurvivalProgressLedger.MAX_ENTRIES) {
				throw failure("entry count exceeds the bounded limit");
			}
			ArrayList<SurvivalProgressLedger.ProgressEntry> decoded = new ArrayList<>(entries.size());
			for (JsonElement value : entries) {
				JsonObject entry = object(value, "survival progress entry");
				exactFields(entry, ENTRY_FIELDS, "survival progress entry");
				decoded.add(new SurvivalProgressLedger.ProgressEntry(
						UUID.fromString(string(entry, "goal_id")),
						AgentId.parse(string(entry, "agent_id")),
						string(entry, "predicate_path"),
						exactLong(entry, "required_ticks"),
						exactLong(entry, "observed_ticks")
				));
			}
			return new SurvivalProgressLedger.Snapshot(SurvivalProgressLedger.SCHEMA_VERSION, decoded);
		} catch (AgentDomainException exception) {
			throw exception;
		} catch (JsonParseException | IllegalStateException | IllegalArgumentException exception) {
			throw failure("invalid persisted survival progress: " + exception.getMessage());
		}
	}

	private static void exactFields(JsonObject object, Set<String> fields, String label) {
		if (!object.keySet().equals(fields)) throw failure(label + " fields differ from the closed schema");
	}

	private static JsonElement field(JsonObject object, String name) {
		if (!object.has(name)) throw failure("missing survival progress field: " + name);
		return object.get(name);
	}

	private static JsonObject object(JsonElement value, String label) {
		if (value == null || !value.isJsonObject()) throw failure(label + " must be an object");
		return value.getAsJsonObject();
	}

	private static String string(JsonObject object, String name) {
		JsonElement value = field(object, name);
		if (!value.isJsonPrimitive() || !value.getAsJsonPrimitive().isString()) {
			throw failure(name + " must be a string");
		}
		return value.getAsString();
	}

	private static int exactInt(JsonObject object, String name) {
		long value = exactLong(object, name);
		if (value < Integer.MIN_VALUE || value > Integer.MAX_VALUE) throw failure(name + " must be an integer");
		return (int) value;
	}

	private static long exactLong(JsonObject object, String name) {
		JsonElement value = field(object, name);
		if (!value.isJsonPrimitive() || !value.getAsJsonPrimitive().isNumber()) {
			throw failure(name + " must be an integer");
		}
		try {
			return value.getAsBigDecimal().longValueExact();
		} catch (ArithmeticException | NumberFormatException | UnsupportedOperationException exception) {
			throw failure(name + " must be an integer");
		}
	}

	private static AgentDomainException failure(String message) {
		return new AgentDomainException("INVALID_PERSISTED_SURVIVAL_PROGRESS", message);
	}
}
