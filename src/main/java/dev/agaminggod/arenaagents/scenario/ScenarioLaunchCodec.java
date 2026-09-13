package dev.agaminggod.arenaagents.scenario;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.agaminggod.arenaagents.agent.AgentGameMode;

import java.util.ArrayList;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

public final class ScenarioLaunchCodec {
	private static final int MAX_PAYLOAD_CHARS = 32_768;

	private ScenarioLaunchCodec() {
	}

	public static String encode(ScenarioLaunchRequest request) {
		Objects.requireNonNull(request, "request must not be null");
		JsonObject root = new JsonObject();
		root.addProperty("scenarioId", request.scenarioId());
		root.addProperty("mapVersion", request.mapVersion());
		root.addProperty("deterministicEvents", request.deterministicEvents());
		root.addProperty("placementMode", request.placementMode().wireName());
		root.addProperty("confirmationToken", request.confirmationToken());
		JsonArray roster = new JsonArray();
		for (ScenarioAgentSpec agent : request.roster()) {
			JsonObject value = new JsonObject();
			value.addProperty("slot", agent.slot());
			value.addProperty("displayName", agent.displayName());
			value.addProperty("provider", agent.provider());
			value.addProperty("model", agent.model());
			value.addProperty("reasoning", agent.reasoning());
			value.addProperty("serviceTier", agent.serviceTier());
			agent.team().ifPresent(team -> value.addProperty("team", team));
			value.addProperty("gameMode", agent.gameMode().name());
			roster.add(value);
		}
		root.add("roster", roster);
		String encoded = root.toString();
		if (encoded.length() > MAX_PAYLOAD_CHARS) {
			throw new IllegalArgumentException("scenario launch request is too large");
		}
		return encoded;
	}

	public static ScenarioLaunchRequest decode(String encoded) {
		if (encoded == null || encoded.isBlank() || encoded.length() > MAX_PAYLOAD_CHARS) {
			throw new IllegalArgumentException("scenario launch request is empty or too large");
		}
		JsonObject root = JsonParser.parseString(encoded).getAsJsonObject();
		requireKeys(root, Set.of(
				"scenarioId", "mapVersion", "deterministicEvents", "placementMode", "confirmationToken", "roster"));
		ArrayList<ScenarioAgentSpec> roster = new ArrayList<>();
		for (var element : root.getAsJsonArray("roster")) {
			JsonObject value = element.getAsJsonObject();
			Set<String> allowed = Set.of(
					"slot", "displayName", "provider", "model", "reasoning", "serviceTier", "team", "gameMode"
			);
			if (!allowed.containsAll(value.keySet())
					|| !value.keySet().containsAll(Set.of(
							"slot", "displayName", "provider", "model", "reasoning", "serviceTier", "gameMode"
					))) {
				throw new IllegalArgumentException("scenario roster entry contains missing or unknown fields");
			}
			roster.add(new ScenarioAgentSpec(
					value.get("slot").getAsInt(),
					value.get("displayName").getAsString(),
					value.get("provider").getAsString(),
					value.get("model").getAsString(),
					value.get("reasoning").getAsString(),
					value.get("serviceTier").getAsString(),
					value.has("team") ? Optional.of(value.get("team").getAsString()) : Optional.empty(),
					AgentGameMode.parse(value.get("gameMode").getAsString())
			));
		}
		ScenarioLaunchRequest request = new ScenarioLaunchRequest(
				root.get("scenarioId").getAsString(),
				root.get("mapVersion").getAsString(),
				root.get("deterministicEvents").getAsBoolean(),
				ScenarioPlacementMode.parse(root.get("placementMode").getAsString()),
				roster,
				root.get("confirmationToken").getAsString()
		);
		return new ScenarioLaunchRequest(
				request.scenarioId(),
				request.mapVersion(),
				request.deterministicEvents(),
				request.placementMode(),
				request.roster(),
				request.confirmationToken()
		);
	}

	private static void requireKeys(JsonObject object, Set<String> expected) {
		if (!object.keySet().equals(expected)) {
			throw new IllegalArgumentException("scenario launch request contains missing or unknown fields");
		}
	}
}
