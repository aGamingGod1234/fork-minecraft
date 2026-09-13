package dev.agaminggod.arenaagents.server.conversation;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;
import dev.agaminggod.arenaagents.agent.AgentDomainException;
import dev.agaminggod.arenaagents.agent.AgentGoal;
import dev.agaminggod.arenaagents.agent.AgentGoalCodec;
import dev.agaminggod.arenaagents.agent.AgentId;
import dev.agaminggod.arenaagents.agent.goal.GoalStatus;
import java.util.ArrayList;
import java.util.UUID;

public final class PendingConversationWakeCodec {
	private static final Gson GSON = new GsonBuilder().disableHtmlEscaping().serializeNulls().create();
	private static final AgentGoalCodec GOAL_CODEC = new AgentGoalCodec();

	public String encode(PendingConversationWake wake) {
		JsonObject root = new JsonObject();
		root.addProperty("transaction_id", wake.transactionId().toString());
		root.add("event", encodeEvent(wake.event()));
		root.add("goal", encodeGoal(wake.goal()));
		root.addProperty("goal_revision", wake.goalRevision());
		root.addProperty("updated_at_epoch_ms", wake.updatedAtEpochMs());
		root.addProperty("acknowledged", wake.acknowledged());
		return GSON.toJson(root);
	}

	public PendingConversationWake decode(String encoded) {
		try {
			JsonObject root = object(JsonParser.parseString(encoded), "root");
			return new PendingConversationWake(
					uuid(string(root, "transaction_id"), "transaction_id"),
					decodeEvent(object(element(root, "event"), "event")),
					decodeGoal(object(element(root, "goal"), "goal")),
					integer(root, "goal_revision"),
					integer(root, "updated_at_epoch_ms"),
					bool(root, "acknowledged")
			);
		} catch (AgentDomainException exception) {
			throw exception;
		} catch (JsonParseException | IllegalStateException | NumberFormatException exception) {
			throw failure("Invalid persisted conversation wake: " + exception.getMessage());
		}
	}

	private static JsonObject encodeEvent(ConversationEvent event) {
		JsonObject json = new JsonObject();
		json.addProperty("agent_id", event.agentId().toString());
		json.addProperty("source_id", event.sourceId());
		json.addProperty("recipient_id", event.recipientId());
		json.addProperty("audience", event.audience().name());
		json.addProperty("kind", event.kind().name());
		json.addProperty("text", event.text());
		json.addProperty("goal_revision", event.goalRevision());
		json.addProperty("observed_at_epoch_ms", event.observedAtEpochMs());
		json.addProperty("sequence", event.sequence());
		json.addProperty("dimension_id", event.dimensionId());
		return json;
	}

	private static ConversationEvent decodeEvent(JsonObject json) {
		return new ConversationEvent(
				AgentId.parse(string(json, "agent_id")), string(json, "source_id"), string(json, "recipient_id"),
				enumeration(ConversationAudience.class, string(json, "audience"), "audience"),
				enumeration(ConversationKind.class, string(json, "kind"), "kind"), string(json, "text"),
				integer(json, "goal_revision"), integer(json, "observed_at_epoch_ms"),
				integer(json, "sequence"), string(json, "dimension_id")
		);
	}

	private static JsonObject encodeGoal(AgentGoal goal) {
		return GOAL_CODEC.encode(goal);
	}

	private static AgentGoal decodeGoal(JsonObject json) {
		return GOAL_CODEC.decode(json, GoalStatus.ACTIVE);
	}

	private static JsonElement element(JsonObject object, String field) {
		if (!object.has(field)) throw failure("Missing persisted conversation wake field: " + field);
		return object.get(field);
	}

	private static JsonObject object(JsonElement element, String field) {
		if (element == null || !element.isJsonObject()) throw failure(field + " must be an object");
		return element.getAsJsonObject();
	}

	private static String string(JsonObject object, String field) {
		JsonElement value = element(object, field);
		if (!value.isJsonPrimitive() || !value.getAsJsonPrimitive().isString()) throw failure(field + " must be a string");
		return value.getAsString();
	}

	private static long integer(JsonObject object, String field) {
		JsonElement value = element(object, field);
		if (!value.isJsonPrimitive() || !value.getAsJsonPrimitive().isNumber()) throw failure(field + " must be an integer");
		try {
			return value.getAsBigDecimal().longValueExact();
		} catch (ArithmeticException exception) {
			throw failure(field + " must be an integer");
		}
	}

	private static boolean bool(JsonObject object, String field) {
		JsonElement value = element(object, field);
		if (!value.isJsonPrimitive() || !value.getAsJsonPrimitive().isBoolean()) throw failure(field + " must be a boolean");
		return value.getAsBoolean();
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
			throw failure(field + " is invalid");
		}
	}

	private static AgentDomainException failure(String message) {
		return new AgentDomainException("INVALID_PERSISTED_CONVERSATION_WAKE", message);
	}
}
