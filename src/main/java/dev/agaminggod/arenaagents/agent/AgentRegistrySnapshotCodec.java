package dev.agaminggod.arenaagents.agent;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.OptionalDouble;
import java.util.OptionalInt;
import java.util.UUID;

public final class AgentRegistrySnapshotCodec {
	private static final Gson GSON = new GsonBuilder().disableHtmlEscaping().serializeNulls().create();
	private static final AgentGoalCodec GOAL_CODEC = new AgentGoalCodec();

	public String encode(AgentRegistry.Snapshot snapshot) {
		JsonObject root = new JsonObject();
		root.addProperty("schema_version", snapshot.schemaVersion());
		root.addProperty("max_agents", snapshot.maxAgents());
		root.addProperty("queue_limit", snapshot.queueLimit());
		JsonArray agents = new JsonArray();
		for (AgentRecord record : snapshot.records()) {
			agents.add(encodeRecord(record));
		}
		root.add("agents", agents);
		return GSON.toJson(root);
	}

	public AgentRegistry.Snapshot decode(String encoded) {
		try {
			JsonObject root = requireObject(JsonParser.parseString(encoded), "root");
			int schemaVersion = requireInt(root, "schema_version");
			int maxAgents = requireInt(root, "max_agents");
			int queueLimit = requireInt(root, "queue_limit");
			JsonArray agents = requireArray(root, "agents");
			ArrayList<AgentRecord> records = new ArrayList<>(agents.size());
			for (JsonElement element : agents) {
				AgentRecord record = decodeRecord(requireObject(element, "agent"));
				if (record.queuedGoals().size() > queueLimit) {
					throw failure("PERSISTED_QUEUE_TOO_LARGE", "Persisted queue exceeds configured limit");
				}
				records.add(record);
			}
			return new AgentRegistry.Snapshot(schemaVersion, maxAgents, queueLimit, records);
		} catch (AgentDomainException exception) {
			throw exception;
		} catch (JsonParseException | IllegalStateException | NumberFormatException exception) {
			throw failure("INVALID_PERSISTED_DATA", "Invalid persisted agent data: " + exception.getMessage());
		}
	}

	private static JsonObject encodeRecord(AgentRecord record) {
		JsonObject json = new JsonObject();
		json.addProperty("schema_version", record.schemaVersion());
		json.addProperty("agent_id", record.agentId().toString());
		record.entityUuid().ifPresentOrElse(
				value -> json.addProperty("entity_uuid", value.toString()),
				() -> json.add("entity_uuid", null)
		);
		record.entityLocation().ifPresentOrElse(
				location -> json.add("entity_location", encodeEntityLocation(location)),
				() -> json.add("entity_location", null)
		);
		json.add("profile", encodeProfile(record.profile()));
		json.addProperty("state", record.state().name());
		json.addProperty("resume_after_respawn", record.resumeAfterRespawn());
		record.currentGoal().ifPresentOrElse(
				goal -> json.add("current_goal", encodeGoal(goal)),
				() -> json.add("current_goal", null)
		);
		json.addProperty("goal_revision", record.goalRevision());
		JsonArray queue = new JsonArray();
		for (AgentGoal goal : record.queuedGoals()) {
			queue.add(encodeGoal(goal));
		}
		json.add("queue", queue);
		json.addProperty("last_summary", record.lastSummary());
		json.addProperty("inventory_snapshot", record.inventorySnapshot());
		json.addProperty("automatic_progress", record.automaticProgress());
		json.addProperty("respawn_policy", record.respawnPolicy().name());
		record.deathSnapshot().ifPresentOrElse(
				death -> json.add("death_snapshot", encodeDeathSnapshot(death)),
				() -> json.add("death_snapshot", null)
		);
		json.addProperty("created_at_epoch_ms", record.createdAtEpochMs());
		json.addProperty("updated_at_epoch_ms", record.updatedAtEpochMs());
		json.addProperty("last_error", record.lastError());
		return json;
	}

	private static AgentRecord decodeRecord(JsonObject json) {
		JsonArray queueJson = requireArray(json, "queue");
		ArrayList<AgentGoal> queue = new ArrayList<>(queueJson.size());
		AgentProfile profile = decodeProfile(requireObject(requireElement(json, "profile"), "profile"));
		AgentLifecycleState state = parseEnum(AgentLifecycleState.class, requireString(json, "state"), "state");
		Optional<AgentGoal> currentGoal = optionalGoal(json, "current_goal", state);
		state = migrateLegacyCompletedState(state, currentGoal);
		for (JsonElement element : queueJson) {
			queue.add(GOAL_CODEC.decode(requireObject(element, "queued goal"), dev.agaminggod.arenaagents.agent.goal.GoalStatus.ACTIVE));
		}
		return new AgentRecord(
				requireInt(json, "schema_version"),
				AgentId.parse(requireString(json, "agent_id")),
				optionalUuid(json, "entity_uuid"),
				optionalEntityLocation(json, "entity_location"),
				profile,
				state,
				optionalBoolean(json, "resume_after_respawn", false),
				currentGoal,
				requireLong(json, "goal_revision"),
				queue,
				requireString(json, "last_summary"),
				requireString(json, "inventory_snapshot"),
				optionalBoolean(json, "automatic_progress", true),
				parseEnum(RespawnPolicy.class, requireString(json, "respawn_policy"), "respawn_policy"),
				optionalDeathSnapshot(json, "death_snapshot", profile.gameMode().wireName()),
				requireLong(json, "created_at_epoch_ms"),
				requireLong(json, "updated_at_epoch_ms"),
				requireString(json, "last_error")
		);
	}

	private static AgentLifecycleState migrateLegacyCompletedState(
			AgentLifecycleState state,
			Optional<AgentGoal> currentGoal
	) {
		if (state != AgentLifecycleState.COMPLETED) return state;
		if (currentGoal.map(AgentGoal::status)
				.filter(status -> status == dev.agaminggod.arenaagents.agent.goal.GoalStatus.SATISFIED)
				.isPresent()) {
			return state;
		}
		return currentGoal.isPresent() ? AgentLifecycleState.PAUSED : AgentLifecycleState.IDLE;
	}

	private static JsonObject encodeDeathSnapshot(AgentDeathSnapshot death) {
		JsonObject json = new JsonObject();
		json.addProperty("cause", death.cause());
		json.addProperty("dimension_id", death.dimensionId());
		json.addProperty("x", death.x());
		json.addProperty("y", death.y());
		json.addProperty("z", death.z());
		death.respawnDimensionId().ifPresentOrElse(value -> json.addProperty("respawn_dimension_id", value), () -> json.add("respawn_dimension_id", null));
		death.respawnX().ifPresentOrElse(value -> json.addProperty("respawn_x", value), () -> json.add("respawn_x", null));
		death.respawnY().ifPresentOrElse(value -> json.addProperty("respawn_y", value), () -> json.add("respawn_y", null));
		death.respawnZ().ifPresentOrElse(value -> json.addProperty("respawn_z", value), () -> json.add("respawn_z", null));
		death.respawnYaw().ifPresentOrElse(value -> json.addProperty("respawn_yaw", value), () -> json.add("respawn_yaw", null));
		death.respawnPitch().ifPresentOrElse(value -> json.addProperty("respawn_pitch", value), () -> json.add("respawn_pitch", null));
		death.respawnForced().ifPresentOrElse(value -> json.addProperty("respawn_forced", value), () -> json.add("respawn_forced", null));
		json.addProperty("game_mode", death.gameMode());
		json.addProperty("died_at_epoch_ms", death.diedAtEpochMs());
		return json;
	}

	private static Optional<AgentDeathSnapshot> optionalDeathSnapshot(JsonObject object, String field, String fallbackGameMode) {
		if (!object.has(field) || object.get(field).isJsonNull()) return Optional.empty();
		JsonObject json = requireObject(object.get(field), field);
		return Optional.of(new AgentDeathSnapshot(
				requireString(json, "cause"), requireString(json, "dimension_id"),
				requireDouble(json, "x"), requireDouble(json, "y"), requireDouble(json, "z"),
				optionalString(json, "respawn_dimension_id"), optionalDouble(json, "respawn_x"), optionalDouble(json, "respawn_y"), optionalDouble(json, "respawn_z"),
				optionalFloat(json, "respawn_yaw", 0.0F), optionalFloat(json, "respawn_pitch", 0.0F), optionalBooleanValue(json, "respawn_forced", false),
				json.has("game_mode") ? requireString(json, "game_mode") : fallbackGameMode,
				requireLong(json, "died_at_epoch_ms")
		));
	}

	private static JsonObject encodeEntityLocation(AgentEntityLocation location) {
		JsonObject json = new JsonObject();
		json.addProperty("dimension", location.dimension());
		json.addProperty("chunk_x", location.chunkX());
		json.addProperty("chunk_z", location.chunkZ());
		if (location.blockY().isPresent()) json.addProperty("block_y", location.blockY().getAsInt());
		else json.add("block_y", null);
		addOptionalDouble(json, "exact_x", location.exactX());
		addOptionalDouble(json, "exact_y", location.exactY());
		addOptionalDouble(json, "exact_z", location.exactZ());
		addOptionalDouble(json, "yaw", location.yaw());
		addOptionalDouble(json, "pitch", location.pitch());
		return json;
	}

	private static Optional<AgentEntityLocation> optionalEntityLocation(JsonObject object, String field) {
		if (!object.has(field) || object.get(field).isJsonNull()) {
			return Optional.empty();
		}
		JsonObject json = requireObject(object.get(field), field);
		return Optional.of(new AgentEntityLocation(
				requireString(json, "dimension"),
				requireInt(json, "chunk_x"),
				requireInt(json, "chunk_z"),
				optionalInt(json, "block_y"),
				optionalFiniteDouble(json, "exact_x"),
				optionalFiniteDouble(json, "exact_y"),
				optionalFiniteDouble(json, "exact_z"),
				optionalFiniteDouble(json, "yaw"),
				optionalFiniteDouble(json, "pitch")
		));
	}

	private static void addOptionalDouble(JsonObject object, String field, OptionalDouble value) {
		if (value.isPresent()) object.addProperty(field, value.getAsDouble());
		else object.add(field, null);
	}

	private static OptionalInt optionalInt(JsonObject object, String field) {
		if (!object.has(field) || object.get(field).isJsonNull()) return OptionalInt.empty();
		return OptionalInt.of(requireInt(object, field));
	}

	private static OptionalDouble optionalFiniteDouble(JsonObject object, String field) {
		if (!object.has(field) || object.get(field).isJsonNull()) return OptionalDouble.empty();
		return OptionalDouble.of(requireDouble(object, field));
	}

	private static JsonObject encodeProfile(AgentProfile profile) {
		JsonObject json = new JsonObject();
		json.addProperty("provider", profile.provider());
		json.addProperty("model", profile.model());
		json.addProperty("reasoning", profile.reasoning());
		json.addProperty("service_tier", profile.serviceTier());
		profile.userName().ifPresentOrElse(
				name -> json.addProperty("user_name", name),
				() -> json.add("user_name", null)
		);
		json.addProperty("skin_variant", profile.skinVariant());
		json.addProperty("game_mode", profile.gameMode().wireName());
		return json;
	}

	private static AgentProfile decodeProfile(JsonObject json) {
		return new AgentProfile(
				json.has("provider") ? requireString(json, "provider") : "codex",
				requireString(json, "model"),
				requireString(json, "reasoning"),
				json.has("service_tier") ? requireString(json, "service_tier") : "priority",
				optionalString(json, "user_name"),
				requireInt(json, "skin_variant"),
				json.has("game_mode")
						? AgentGameMode.parse(requireString(json, "game_mode"))
						: AgentGameMode.SURVIVAL
		);
	}

	private static JsonObject encodeGoal(AgentGoal goal) {
		return GOAL_CODEC.encode(goal);
	}

	private static Optional<UUID> optionalUuid(JsonObject object, String field) {
		return optionalString(object, field).map(value -> parseUuid(value, field));
	}

	private static Optional<AgentGoal> optionalGoal(JsonObject object, String field, AgentLifecycleState state) {
		JsonElement element = requireElement(object, field);
		if (element.isJsonNull()) {
			return Optional.empty();
		}
		dev.agaminggod.arenaagents.agent.goal.GoalStatus legacyStatus = state == AgentLifecycleState.COMPLETED
				? dev.agaminggod.arenaagents.agent.goal.GoalStatus.SATISFIED
				: dev.agaminggod.arenaagents.agent.goal.GoalStatus.ACTIVE;
		return Optional.of(GOAL_CODEC.decode(requireObject(element, field), legacyStatus));
	}

	private static Optional<String> optionalString(JsonObject object, String field) {
		JsonElement element = requireElement(object, field);
		if (element.isJsonNull()) {
			return Optional.empty();
		}
		if (!element.isJsonPrimitive() || !element.getAsJsonPrimitive().isString()) {
			throw failure("INVALID_PERSISTED_FIELD", field + " must be a string or null");
		}
		return Optional.of(element.getAsString());
	}

	private static String requireString(JsonObject object, String field) {
		JsonElement element = requireElement(object, field);
		if (!element.isJsonPrimitive() || !element.getAsJsonPrimitive().isString()) {
			throw failure("INVALID_PERSISTED_FIELD", field + " must be a string");
		}
		return element.getAsString();
	}

	private static int requireInt(JsonObject object, String field) {
		JsonElement element = requireElement(object, field);
		if (!element.isJsonPrimitive() || !element.getAsJsonPrimitive().isNumber()) {
			throw failure("INVALID_PERSISTED_FIELD", field + " must be an integer");
		}
		return element.getAsInt();
	}

	private static long requireLong(JsonObject object, String field) {
		JsonElement element = requireElement(object, field);
		if (!element.isJsonPrimitive() || !element.getAsJsonPrimitive().isNumber()) {
			throw failure("INVALID_PERSISTED_FIELD", field + " must be an integer");
		}
		return element.getAsLong();
	}

	private static double requireDouble(JsonObject object, String field) {
		JsonElement element = requireElement(object, field);
		if (!element.isJsonPrimitive() || !element.getAsJsonPrimitive().isNumber() || !Double.isFinite(element.getAsDouble())) {
			throw failure("INVALID_PERSISTED_FIELD", field + " must be a finite number");
		}
		return element.getAsDouble();
	}

	private static Optional<Double> optionalDouble(JsonObject object, String field) {
		if (!object.has(field) || object.get(field).isJsonNull()) return Optional.empty();
		return Optional.of(requireDouble(object, field));
	}

	private static Optional<Float> optionalFloat(JsonObject object, String field, float legacyFallback) {
		if (!object.has("respawn_dimension_id") || object.get("respawn_dimension_id").isJsonNull()) return Optional.empty();
		return object.has(field) && !object.get(field).isJsonNull()
				? Optional.of((float) requireDouble(object, field))
				: Optional.of(legacyFallback);
	}

	private static Optional<Boolean> optionalBooleanValue(JsonObject object, String field, boolean legacyFallback) {
		if (!object.has("respawn_dimension_id") || object.get("respawn_dimension_id").isJsonNull()) return Optional.empty();
		if (!object.has(field) || object.get(field).isJsonNull()) return Optional.of(legacyFallback);
		JsonElement element = object.get(field);
		if (!element.isJsonPrimitive() || !element.getAsJsonPrimitive().isBoolean()) {
			throw failure("INVALID_PERSISTED_FIELD", field + " must be a boolean");
		}
		return Optional.of(element.getAsBoolean());
	}

	private static JsonArray requireArray(JsonObject object, String field) {
		JsonElement element = requireElement(object, field);
		if (!element.isJsonArray()) {
			throw failure("INVALID_PERSISTED_FIELD", field + " must be an array");
		}
		return element.getAsJsonArray();
	}

	private static boolean optionalBoolean(JsonObject object, String field, boolean fallback) {
		if (!object.has(field) || object.get(field).isJsonNull()) {
			return fallback;
		}
		JsonElement element = object.get(field);
		if (!element.isJsonPrimitive() || !element.getAsJsonPrimitive().isBoolean()) {
			throw failure("INVALID_PERSISTED_FIELD", field + " must be a boolean");
		}
		return element.getAsBoolean();
	}

	private static JsonElement requireElement(JsonObject object, String field) {
		if (!object.has(field)) {
			throw failure("MISSING_PERSISTED_FIELD", "Missing persisted field: " + field);
		}
		return object.get(field);
	}

	private static JsonObject requireObject(JsonElement element, String field) {
		if (element == null || !element.isJsonObject()) {
			throw failure("INVALID_PERSISTED_FIELD", field + " must be an object");
		}
		return element.getAsJsonObject();
	}

	private static UUID parseUuid(String value, String field) {
		try {
			return UUID.fromString(value);
		} catch (IllegalArgumentException exception) {
			throw failure("INVALID_PERSISTED_FIELD", field + " must be a UUID");
		}
	}

	private static <E extends Enum<E>> E parseEnum(Class<E> type, String value, String field) {
		try {
			return Enum.valueOf(type, value);
		} catch (IllegalArgumentException exception) {
			throw failure("INVALID_PERSISTED_FIELD", "Unknown " + field + ": " + value);
		}
	}

	private static AgentDomainException failure(String code, String message) {
		return new AgentDomainException(code, message);
	}
}
