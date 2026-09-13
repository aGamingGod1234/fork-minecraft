package dev.agaminggod.arenaagents.server.goal;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;
import dev.agaminggod.arenaagents.agent.AgentDomainException;
import java.util.ArrayList;
import java.util.Set;
import java.util.UUID;

/** Closed, versioned codec for operator confirmations stored in Minecraft SavedData. */
public final class OperatorConfirmationLedgerCodec {
	private static final Gson GSON = new GsonBuilder().disableHtmlEscaping().create();
	private static final Set<String> ROOT_FIELDS = Set.of("schema_version", "goal_ids");

	public String encode(OperatorConfirmationLedger.Snapshot snapshot) {
		JsonObject root = new JsonObject();
		root.addProperty("schema_version", snapshot.schemaVersion());
		JsonArray goalIds = new JsonArray();
		snapshot.goalIds().forEach(goalId -> goalIds.add(goalId.toString()));
		root.add("goal_ids", goalIds);
		return GSON.toJson(root);
	}

	public OperatorConfirmationLedger.Snapshot decode(String encoded) {
		try {
			JsonElement parsed = JsonParser.parseString(encoded);
			if (!parsed.isJsonObject()) throw failure("operator confirmations must be an object");
			JsonObject root = parsed.getAsJsonObject();
			if (!root.keySet().equals(ROOT_FIELDS)) throw failure("operator confirmation fields differ from the closed schema");
			JsonElement versionValue = root.get("schema_version");
			if (versionValue == null || !versionValue.isJsonPrimitive() || !versionValue.getAsJsonPrimitive().isNumber()
					|| versionValue.getAsBigDecimal().intValueExact() != OperatorConfirmationLedger.SCHEMA_VERSION) {
				throw failure("unsupported operator confirmation schema");
			}
			JsonElement idsValue = root.get("goal_ids");
			if (idsValue == null || !idsValue.isJsonArray()) throw failure("goal_ids must be an array");
			JsonArray ids = idsValue.getAsJsonArray();
			if (ids.size() > OperatorConfirmationLedger.MAX_ENTRIES) throw failure("goal ID count exceeds the bounded limit");
			ArrayList<UUID> decoded = new ArrayList<>(ids.size());
			for (JsonElement value : ids) {
				if (!value.isJsonPrimitive() || !value.getAsJsonPrimitive().isString()) {
					throw failure("goal IDs must be strings");
				}
				decoded.add(UUID.fromString(value.getAsString()));
			}
			return new OperatorConfirmationLedger.Snapshot(OperatorConfirmationLedger.SCHEMA_VERSION, decoded);
		} catch (AgentDomainException exception) {
			throw exception;
		} catch (JsonParseException | IllegalStateException | IllegalArgumentException | ArithmeticException exception) {
			throw failure("invalid persisted operator confirmations: " + exception.getMessage());
		}
	}

	private static AgentDomainException failure(String message) {
		return new AgentDomainException("INVALID_PERSISTED_OPERATOR_CONFIRMATIONS", message);
	}
}
