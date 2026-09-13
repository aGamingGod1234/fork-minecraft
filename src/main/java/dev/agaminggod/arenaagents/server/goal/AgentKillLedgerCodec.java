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
import java.util.List;
import java.util.Set;
import java.util.UUID;

/** Closed, versioned codec for the bounded kill ledger stored in Minecraft SavedData. */
public final class AgentKillLedgerCodec {
	private static final Gson GSON = new GsonBuilder().disableHtmlEscaping().create();
	private static final Set<String> ROOT_FIELDS_V1 = Set.of("schema_version", "events");
	private static final Set<String> ROOT_FIELDS_V2 = Set.of("schema_version", "events", "progress");
	private static final Set<String> ROOT_FIELDS_V3 = Set.of(
			"schema_version", "last_sequence", "events", "progress");
	private static final Set<String> EVENT_FIELDS_V1_V2 = Set.of(
			"agent_id", "entity_type", "occurred_at_epoch_ms");
	private static final Set<String> EVENT_FIELDS_V3 = Set.of(
			"agent_id", "entity_type", "occurred_at_epoch_ms", "sequence");
	private static final Set<String> PROGRESS_FIELDS_V2 = Set.of(
			"goal_id", "agent_id", "entity_type", "after_exclusive", "required_count", "evicted_count");
	private static final Set<String> PROGRESS_FIELDS_V3 = Set.of(
			"goal_id", "agent_id", "entity_type", "after_goal_start", "after_sequence_exclusive",
			"required_count", "evicted_count");

	public String encode(AgentKillLedger.Snapshot snapshot) {
		JsonObject root = new JsonObject();
		if (snapshot.legacyTimestampProgressMigration()) {
			root.addProperty("schema_version", 1);
			JsonArray events = new JsonArray();
			for (AgentKillLedger.KillEvent event : snapshot.events()) {
				JsonObject encoded = new JsonObject();
				encoded.addProperty("agent_id", event.agentId().toString());
				encoded.addProperty("entity_type", event.entityType());
				encoded.addProperty("occurred_at_epoch_ms", event.occurredAtEpochMs());
				events.add(encoded);
			}
			root.add("events", events);
			return GSON.toJson(root);
		}
		root.addProperty("schema_version", snapshot.schemaVersion());
		root.addProperty("last_sequence", snapshot.lastSequence());
		JsonArray events = new JsonArray();
		for (AgentKillLedger.KillEvent event : snapshot.events()) {
			JsonObject encoded = new JsonObject();
			encoded.addProperty("agent_id", event.agentId().toString());
			encoded.addProperty("entity_type", event.entityType());
			encoded.addProperty("occurred_at_epoch_ms", event.occurredAtEpochMs());
			encoded.addProperty("sequence", event.sequence());
			events.add(encoded);
		}
		root.add("events", events);
		JsonArray progress = new JsonArray();
		for (AgentKillLedger.ProgressEvent event : snapshot.progress()) {
			JsonObject encoded = new JsonObject();
			encoded.addProperty("goal_id", event.goalId().toString());
			encoded.addProperty("agent_id", event.agentId().toString());
			encoded.addProperty("entity_type", event.entityType());
			encoded.addProperty("after_goal_start", event.afterGoalStart());
			encoded.addProperty("after_sequence_exclusive", event.afterSequenceExclusive());
			encoded.addProperty("required_count", event.requiredCount());
			encoded.addProperty("evicted_count", event.evictedCount());
			progress.add(encoded);
		}
		root.add("progress", progress);
		return GSON.toJson(root);
	}

	public AgentKillLedger.Snapshot decode(String encoded) {
		try {
			JsonObject root = object(JsonParser.parseString(encoded), "kill ledger");
			int version = exactInt(root, "schema_version");
			if (version == 1) exactFields(root, ROOT_FIELDS_V1, "kill ledger");
			else if (version == 2) exactFields(root, ROOT_FIELDS_V2, "kill ledger");
			else if (version == AgentKillLedger.SCHEMA_VERSION) exactFields(root, ROOT_FIELDS_V3, "kill ledger");
			else throw failure("unsupported kill ledger schema: " + version);

			JsonElement eventValue = field(root, "events");
			if (!eventValue.isJsonArray()) throw failure("events must be an array");
			JsonArray events = eventValue.getAsJsonArray();
			if (events.size() > AgentKillLedger.MAX_EVENTS) throw failure("event count exceeds the bounded limit");
			ArrayList<AgentKillLedger.KillEvent> decoded = new ArrayList<>(events.size());
			long migratedSequence = 0L;
			for (JsonElement value : events) {
				JsonObject event = object(value, "kill event");
				exactFields(event, version >= 3 ? EVENT_FIELDS_V3 : EVENT_FIELDS_V1_V2, "kill event");
				long sequence = version >= 3 ? exactLong(event, "sequence") : ++migratedSequence;
				decoded.add(new AgentKillLedger.KillEvent(
						AgentId.parse(string(event, "agent_id")),
						string(event, "entity_type"),
						exactLong(event, "occurred_at_epoch_ms"),
						sequence
				));
			}
			long lastSequence = version >= 3 ? exactLong(root, "last_sequence") : migratedSequence;

			ArrayList<AgentKillLedger.ProgressEvent> decodedProgress = new ArrayList<>();
			if (version >= 2) {
				JsonElement progressValue = field(root, "progress");
				if (!progressValue.isJsonArray()) throw failure("progress must be an array");
				JsonArray progress = progressValue.getAsJsonArray();
				if (progress.size() > AgentKillLedger.MAX_PROGRESS_ENTRIES) {
					throw failure("progress count exceeds the bounded limit");
				}
				for (JsonElement value : progress) {
					JsonObject entry = object(value, "kill progress");
					if (version >= 3) {
						exactFields(entry, PROGRESS_FIELDS_V3, "kill progress");
						decodedProgress.add(new AgentKillLedger.ProgressEvent(
								UUID.fromString(string(entry, "goal_id")),
								AgentId.parse(string(entry, "agent_id")),
								string(entry, "entity_type"),
								exactBoolean(entry, "after_goal_start"),
								exactLong(entry, "after_sequence_exclusive"),
								exactInt(entry, "required_count"),
								exactInt(entry, "evicted_count")
						));
					} else {
						exactFields(entry, PROGRESS_FIELDS_V2, "kill progress");
						long afterExclusive = exactLong(entry, "after_exclusive");
						boolean afterGoalStart = afterExclusive != Long.MIN_VALUE;
						AgentId agentId = AgentId.parse(string(entry, "agent_id"));
						String entityType = string(entry, "entity_type");
						int requiredCount = exactInt(entry, "required_count");
						int evictedCount = exactInt(entry, "evicted_count");
						long boundary = afterGoalStart ? lastSequence : 0L;
						int migratedCount = afterGoalStart
								? migratedCount(decoded, agentId, entityType, afterExclusive, requiredCount, evictedCount)
								: evictedCount;
						decodedProgress.add(new AgentKillLedger.ProgressEvent(
								UUID.fromString(string(entry, "goal_id")),
								agentId,
								entityType,
								afterGoalStart,
								boundary,
								requiredCount,
								migratedCount
						));
					}
				}
			}
			return new AgentKillLedger.Snapshot(
					AgentKillLedger.SCHEMA_VERSION, lastSequence, decoded, decodedProgress, version == 1);
		} catch (AgentDomainException exception) {
			throw exception;
		} catch (JsonParseException | IllegalStateException | IllegalArgumentException exception) {
			throw failure("invalid persisted kill ledger: " + exception.getMessage());
		}
	}

	private static int migratedCount(
			List<AgentKillLedger.KillEvent> events,
			AgentId agentId,
			String entityType,
			long afterExclusive,
			int requiredCount,
			int evictedCount
	) {
		int count = evictedCount;
		for (AgentKillLedger.KillEvent event : events) {
			if (count >= requiredCount) break;
			if (event.agentId().equals(agentId)
					&& event.entityType().equals(entityType)
					&& event.occurredAtEpochMs() > afterExclusive) {
				count++;
			}
		}
		return count;
	}

	private static void exactFields(JsonObject object, Set<String> fields, String label) {
		if (!object.keySet().equals(fields)) throw failure(label + " fields differ from the closed schema");
	}

	private static JsonElement field(JsonObject object, String name) {
		if (!object.has(name)) throw failure("missing kill ledger field: " + name);
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

	private static boolean exactBoolean(JsonObject object, String name) {
		JsonElement value = field(object, name);
		if (!value.isJsonPrimitive() || !value.getAsJsonPrimitive().isBoolean()) {
			throw failure(name + " must be a boolean");
		}
		return value.getAsBoolean();
	}

	private static int exactInt(JsonObject object, String name) {
		JsonElement value = field(object, name);
		if (!value.isJsonPrimitive() || !value.getAsJsonPrimitive().isNumber()) {
			throw failure(name + " must be an integer");
		}
		try {
			return value.getAsBigDecimal().intValueExact();
		} catch (ArithmeticException | NumberFormatException | UnsupportedOperationException exception) {
			throw failure(name + " must be an integer");
		}
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
		return new AgentDomainException("INVALID_PERSISTED_KILL_LEDGER", message);
	}
}
