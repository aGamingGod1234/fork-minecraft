package dev.agaminggod.arenaagents.scenario.runtime;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;
import dev.agaminggod.arenaagents.scenario.ScenarioParticipant;
import dev.agaminggod.arenaagents.scenario.ScenarioPreset;
import dev.agaminggod.arenaagents.scenario.ScenarioPresets;
import dev.agaminggod.arenaagents.scenario.ScenarioSession;
import dev.agaminggod.arenaagents.scenario.ScenarioSessionConfig;
import dev.agaminggod.arenaagents.scenario.ScenarioSessionState;
import dev.agaminggod.arenaagents.scenario.result.ScenarioPublicEvent;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;

public record ScenarioRunSnapshot(
		int version,
		UUID sessionId,
		String scenarioId,
		String mapVersion,
		long worldSeed,
		long eventSeed,
		long durationTicks,
		boolean deterministicEvents,
		List<Participant> participants,
		long createdAtEpochMs,
		String dimensionId,
		UUID operatorId,
		List<String> boundAgentIds,
		Map<String, Integer> parkourCheckpoints,
		Origin origin,
		ScenarioSessionState state,
		Optional<String> completionReason,
		long lastElapsedTick,
		Map<String, Double> scores,
		ScenarioResetReceipt reset,
		ScenarioRuntimeClock.Snapshot clock,
		List<ScenarioPublicEvent> publicEvents
) {
	public static final int CURRENT_VERSION = 2;
	private static final int LEGACY_VERSION = 1;
	private static final Set<String> ROOT_KEYS_V1 = Set.of(
			"version", "sessionId", "scenarioId", "mapVersion", "worldSeed", "eventSeed",
			"durationTicks", "deterministicEvents", "participants", "createdAtEpochMs",
			"dimensionId", "operatorId", "boundAgentIds", "origin", "state",
			"completionReason", "lastElapsedTick", "scores", "reset", "clock", "publicEvents"
	);
	private static final Set<String> ROOT_KEYS = Set.of(
			"version", "sessionId", "scenarioId", "mapVersion", "worldSeed", "eventSeed",
			"durationTicks", "deterministicEvents", "participants", "createdAtEpochMs",
			"dimensionId", "operatorId", "boundAgentIds", "parkourCheckpoints", "origin", "state",
			"completionReason", "lastElapsedTick", "scores", "reset", "clock", "publicEvents"
	);

	public ScenarioRunSnapshot {
		if (version != CURRENT_VERSION) {
			throw failure("UNSUPPORTED_SCENARIO_SNAPSHOT", "unsupported snapshot version " + version);
		}
		Objects.requireNonNull(sessionId, "sessionId must not be null");
		scenarioId = id(scenarioId, "scenarioId");
		mapVersion = text(mapVersion, "mapVersion", 64);
		if (durationTicks <= 0L || durationTicks > ScenarioSessionConfig.MAX_DURATION_TICKS) {
			throw failure("INVALID_SCENARIO_SNAPSHOT", "durationTicks is invalid");
		}
		participants = List.copyOf(Objects.requireNonNull(participants, "participants must not be null"));
		if (participants.isEmpty() || participants.size() > 64) {
			throw failure("INVALID_SCENARIO_SNAPSHOT", "participant count is invalid");
		}
		HashSet<String> participantIds = new HashSet<>();
		for (Participant participant : participants) {
			if (!participantIds.add(participant.id())) {
				throw failure("INVALID_SCENARIO_SNAPSHOT", "duplicate participant " + participant.id());
			}
		}
		if (createdAtEpochMs <= 0L) throw failure("INVALID_SCENARIO_SNAPSHOT", "createdAtEpochMs is invalid");
		dimensionId = resourceId(dimensionId, "dimensionId");
		Objects.requireNonNull(operatorId, "operatorId must not be null");
		boundAgentIds = Objects.requireNonNull(boundAgentIds, "boundAgentIds must not be null").stream()
				.map(value -> text(value, "boundAgentId", 96))
				.toList();
		if (new HashSet<>(boundAgentIds).size() != boundAgentIds.size()) {
			throw failure("INVALID_SCENARIO_SNAPSHOT", "bound agent ids must be unique");
		}
		if (boundAgentIds.size() > 64) throw failure("INVALID_SCENARIO_SNAPSHOT", "too many bound agents");
		if (boundAgentIds.size() != participants.size()) {
			throw failure("INVALID_SCENARIO_SNAPSHOT", "bound agents must exactly match participants");
		}
		TreeMap<String, Integer> orderedCheckpoints = new TreeMap<>();
		for (Map.Entry<String, Integer> entry : Objects.requireNonNull(
				parkourCheckpoints, "parkourCheckpoints must not be null").entrySet()) {
			String agentId = text(entry.getKey(), "parkour checkpoint agentId", 96);
			int checkpoint = Objects.requireNonNull(entry.getValue(), "parkour checkpoint must not be null");
			if (!boundAgentIds.contains(agentId)) {
				throw failure("INVALID_SCENARIO_SNAPSHOT", "parkour checkpoint agent is not bound");
			}
			if (checkpoint < 0 || checkpoint >= ScenarioParkourCourse.PLATFORM_COUNT) {
				throw failure("INVALID_SCENARIO_SNAPSHOT", "parkour checkpoint is invalid");
			}
			orderedCheckpoints.put(agentId, checkpoint);
		}
		parkourCheckpoints = Map.copyOf(orderedCheckpoints);
		origin = Objects.requireNonNull(origin, "origin must not be null");
		state = Objects.requireNonNull(state, "state must not be null");
		completionReason = Objects.requireNonNull(completionReason, "completionReason must not be null")
				.map(value -> text(value, "completionReason", 320));
		if (state.terminal() != completionReason.isPresent()) {
			throw failure("INVALID_SCENARIO_SNAPSHOT", "terminal state and completionReason must agree");
		}
		if (lastElapsedTick < -1L) throw failure("INVALID_SCENARIO_SNAPSHOT", "lastElapsedTick is invalid");
		TreeMap<String, Double> orderedScores = new TreeMap<>();
		for (Map.Entry<String, Double> entry : Objects.requireNonNull(scores, "scores must not be null").entrySet()) {
			String participantId = id(entry.getKey(), "score participantId");
			double score = Objects.requireNonNull(entry.getValue(), "score must not be null");
			if (!Double.isFinite(score)) throw failure("INVALID_SCENARIO_SNAPSHOT", "score must be finite");
			orderedScores.put(participantId, score == 0.0D ? 0.0D : score);
		}
		if (!orderedScores.keySet().equals(participantIds)) {
			throw failure("INVALID_SCENARIO_SNAPSHOT", "scores must exactly match participants");
		}
		scores = Map.copyOf(orderedScores);
		reset = Objects.requireNonNull(reset, "reset must not be null");
		clock = Objects.requireNonNull(clock, "clock must not be null");
		if (clock.elapsedTick() > durationTicks + 1L) {
			throw failure("INVALID_SCENARIO_SNAPSHOT", "clock exceeds scenario duration");
		}
		publicEvents = Objects.requireNonNull(publicEvents, "publicEvents must not be null").stream()
				.sorted(ScenarioPublicEvent.CANONICAL_ORDER)
				.limit(4_096L)
				.toList();
	}

	public static ScenarioRunSnapshot capture(
			ScenarioSession session,
			ScenarioRuntimeClock clock,
			String dimensionId,
			UUID operatorId,
			List<String> boundAgentIds,
			Map<String, Integer> parkourCheckpoints,
			Origin origin,
			ScenarioResetReceipt reset,
			List<ScenarioPublicEvent> publicEvents
	) {
		Objects.requireNonNull(session, "session must not be null");
		ScenarioSessionConfig config = session.config();
		ScenarioSessionState persistedState = session.state() == ScenarioSessionState.RUNNING
				? ScenarioSessionState.PAUSED_RECOVERY : session.state();
		return new ScenarioRunSnapshot(
				CURRENT_VERSION,
				config.sessionId(),
				config.preset().id(),
				config.preset().mapVersion(),
				config.worldSeed(),
				config.eventSeed(),
				config.durationTicks(),
				config.deterministicEvents(),
				config.participants().stream()
						.map(value -> new Participant(value.id(), value.modelLabel(), value.team()))
						.toList(),
				config.createdAtEpochMs(),
				dimensionId,
				operatorId,
				boundAgentIds,
				parkourCheckpoints,
				origin,
				persistedState,
				session.completionReason(),
				Math.max(session.lastElapsedTick(), clock.snapshot().elapsedTick()),
				session.scores(),
				reset,
				clock.snapshot(),
				publicEvents
		);
	}

	public ScenarioSession restoreSession() {
		ScenarioPreset preset = ScenarioPresets.require(scenarioId);
		if (!preset.mapVersion().equals(mapVersion)) {
			throw failure("UNSUPPORTED_SCENARIO_VERSION", "saved map version is not installed");
		}
		ScenarioSessionConfig config = new ScenarioSessionConfig(
				sessionId,
				preset,
				worldSeed,
				eventSeed,
				durationTicks,
				deterministicEvents,
				participants.stream()
						.map(value -> new ScenarioParticipant(value.id(), value.displayName(), value.team()))
						.toList(),
				createdAtEpochMs
		);
		ScenarioSessionState restoredState = state == ScenarioSessionState.RUNNING
				? ScenarioSessionState.PAUSED_RECOVERY : state;
		return ScenarioSession.restore(
				config,
				restoredState,
				scores,
				completionReason,
				Math.min(lastElapsedTick, durationTicks)
		);
	}

	public ScenarioRunSnapshot failed(String reason) {
		String normalized = text(reason, "failure reason", 320);
		return new ScenarioRunSnapshot(
				version, sessionId, scenarioId, mapVersion, worldSeed, eventSeed, durationTicks,
				deterministicEvents, participants, createdAtEpochMs, dimensionId, operatorId,
				boundAgentIds, parkourCheckpoints, origin, ScenarioSessionState.FAILED, Optional.of(normalized),
				lastElapsedTick, scores, reset, clock, publicEvents
		);
	}

	public String toJson() {
		JsonObject root = new JsonObject();
		root.addProperty("version", version);
		root.addProperty("sessionId", sessionId.toString());
		root.addProperty("scenarioId", scenarioId);
		root.addProperty("mapVersion", mapVersion);
		root.addProperty("worldSeed", worldSeed);
		root.addProperty("eventSeed", eventSeed);
		root.addProperty("durationTicks", durationTicks);
		root.addProperty("deterministicEvents", deterministicEvents);
		JsonArray participantArray = new JsonArray();
		for (Participant participant : participants) participantArray.add(participant.toJson());
		root.add("participants", participantArray);
		root.addProperty("createdAtEpochMs", createdAtEpochMs);
		root.addProperty("dimensionId", dimensionId);
		root.addProperty("operatorId", operatorId.toString());
		JsonArray agentArray = new JsonArray();
		for (String agentId : boundAgentIds) agentArray.add(agentId);
		root.add("boundAgentIds", agentArray);
		JsonObject checkpointObject = new JsonObject();
		new TreeMap<>(parkourCheckpoints).forEach(checkpointObject::addProperty);
		root.add("parkourCheckpoints", checkpointObject);
		root.add("origin", origin.toJson());
		root.addProperty("state", state.name());
		if (completionReason.isPresent()) root.addProperty("completionReason", completionReason.orElseThrow());
		else root.add("completionReason", null);
		root.addProperty("lastElapsedTick", lastElapsedTick);
		JsonObject scoreObject = new JsonObject();
		new TreeMap<>(scores).forEach((key, value) -> scoreObject.addProperty(key, normalized(value)));
		root.add("scores", scoreObject);
		root.add("reset", JsonParser.parseString(reset.canonicalJson()));
		root.add("clock", clockToJson(clock));
		JsonArray eventArray = new JsonArray();
		for (ScenarioPublicEvent event : publicEvents) {
			eventArray.add(JsonParser.parseString(event.canonicalJson()));
		}
		root.add("publicEvents", eventArray);
		return root.toString();
	}

	public static ScenarioRunSnapshot fromJson(String json) {
		try {
			JsonElement parsed = JsonParser.parseString(Objects.requireNonNull(json, "json must not be null"));
			if (!parsed.isJsonObject()) throw failure("INVALID_SCENARIO_SNAPSHOT", "root must be an object");
			JsonObject root = parsed.getAsJsonObject();
			int version = exactInt(root, "version");
			if (version != LEGACY_VERSION && version != CURRENT_VERSION) {
				throw failure("UNSUPPORTED_SCENARIO_SNAPSHOT", "unsupported snapshot version " + version);
			}
			requireExactKeys(root, version == LEGACY_VERSION ? ROOT_KEYS_V1 : ROOT_KEYS, "root");
			List<Participant> participants = new ArrayList<>();
			for (JsonElement item : array(root, "participants")) participants.add(Participant.fromJson(item));
			List<String> boundAgents = new ArrayList<>();
			for (JsonElement item : array(root, "boundAgentIds")) boundAgents.add(string(item, "boundAgentId"));
			TreeMap<String, Integer> checkpoints = new TreeMap<>();
			if (version == CURRENT_VERSION) {
				JsonObject checkpointObject = object(root, "parkourCheckpoints");
				for (Map.Entry<String, JsonElement> entry : checkpointObject.entrySet()) {
					checkpoints.put(entry.getKey(), exactInt(checkpointObject, entry.getKey()));
				}
			}
			TreeMap<String, Double> scores = new TreeMap<>();
			JsonObject scoreObject = object(root, "scores");
			for (Map.Entry<String, JsonElement> entry : scoreObject.entrySet()) {
				scores.put(entry.getKey(), finiteDouble(entry.getValue(), "score"));
			}
			List<ScenarioPublicEvent> events = new ArrayList<>();
			for (JsonElement item : array(root, "publicEvents")) events.add(publicEvent(item));
			JsonElement reason = root.get("completionReason");
			Optional<String> completionReason = reason.isJsonNull()
					? Optional.empty() : Optional.of(string(reason, "completionReason"));
			return new ScenarioRunSnapshot(
					CURRENT_VERSION,
					UUID.fromString(string(root.get("sessionId"), "sessionId")),
					string(root.get("scenarioId"), "scenarioId"),
					string(root.get("mapVersion"), "mapVersion"),
					exactLong(root, "worldSeed"),
					exactLong(root, "eventSeed"),
					exactLong(root, "durationTicks"),
					bool(root, "deterministicEvents"),
					participants,
					exactLong(root, "createdAtEpochMs"),
					string(root.get("dimensionId"), "dimensionId"),
					UUID.fromString(string(root.get("operatorId"), "operatorId")),
					boundAgents,
					checkpoints,
					Origin.fromJson(root.get("origin")),
					ScenarioSessionState.valueOf(string(root.get("state"), "state")),
					completionReason,
					exactLong(root, "lastElapsedTick"),
					scores,
					resetFromJson(root.get("reset")),
					clockFromJson(root.get("clock")),
					events
			);
		} catch (IllegalArgumentException exception) {
			if (exception.getMessage() != null && (exception.getMessage().startsWith("INVALID_SCENARIO_SNAPSHOT")
					|| exception.getMessage().startsWith("UNSUPPORTED_SCENARIO_SNAPSHOT"))) throw exception;
			throw failure("INVALID_SCENARIO_SNAPSHOT", safeMessage(exception));
		} catch (JsonParseException | IllegalStateException exception) {
			throw failure("INVALID_SCENARIO_SNAPSHOT", safeMessage(exception));
		}
	}

	private static ScenarioResetReceipt resetFromJson(JsonElement value) {
		JsonObject object = requireObject(value, "reset");
		requireExactKeys(object, Set.of(
				"blueprintSha256", "managedVolumeSha256", "plannedPlacements",
				"appliedPlacements", "verifiedPlacements", "verified"
		), "reset");
		return new ScenarioResetReceipt(
				string(object.get("blueprintSha256"), "blueprintSha256"),
				string(object.get("managedVolumeSha256"), "managedVolumeSha256"),
				exactInt(object, "plannedPlacements"),
				exactInt(object, "appliedPlacements"),
				exactInt(object, "verifiedPlacements"),
				bool(object, "verified")
		);
	}

	private static ScenarioPublicEvent publicEvent(JsonElement value) {
		JsonObject object = requireObject(value, "publicEvent");
		requireExactKeys(object, Set.of(
				"elapsedTick", "participantId", "participantDisplayName", "category",
				"actionFamily", "scoreDelta", "state"
		), "publicEvent");
		return new ScenarioPublicEvent(
				exactLong(object, "elapsedTick"),
				string(object.get("participantId"), "participantId"),
				string(object.get("participantDisplayName"), "participantDisplayName"),
				string(object.get("category"), "category"),
				string(object.get("actionFamily"), "actionFamily"),
				finiteDouble(object.get("scoreDelta"), "scoreDelta"),
				string(object.get("state"), "state")
		);
	}

	private static JsonObject clockToJson(ScenarioRuntimeClock.Snapshot value) {
		JsonObject object = new JsonObject();
		object.addProperty("version", value.version());
		object.addProperty("elapsedTick", value.elapsedTick());
		object.addProperty("nextEventIndex", value.nextEventIndex());
		if (value.activePhaseId().isPresent()) object.addProperty("activePhaseId", value.activePhaseId().orElseThrow());
		else object.add("activePhaseId", null);
		object.addProperty("finished", value.finished());
		return object;
	}

	private static ScenarioRuntimeClock.Snapshot clockFromJson(JsonElement value) {
		JsonObject object = requireObject(value, "clock");
		requireExactKeys(object, Set.of("version", "elapsedTick", "nextEventIndex", "activePhaseId", "finished"), "clock");
		JsonElement phase = object.get("activePhaseId");
		return new ScenarioRuntimeClock.Snapshot(
				exactInt(object, "version"),
				exactLong(object, "elapsedTick"),
				exactInt(object, "nextEventIndex"),
				phase.isJsonNull() ? Optional.empty() : Optional.of(string(phase, "activePhaseId")),
				bool(object, "finished")
		);
	}

	private static void requireExactKeys(JsonObject object, Set<String> expected, String field) {
		if (!object.keySet().equals(expected)) {
			throw failure("INVALID_SCENARIO_SNAPSHOT", field + " fields differ from schema");
		}
	}

	private static JsonObject object(JsonObject parent, String name) {
		return requireObject(parent.get(name), name);
	}

	private static JsonObject requireObject(JsonElement value, String field) {
		if (value == null || !value.isJsonObject()) throw failure("INVALID_SCENARIO_SNAPSHOT", field + " must be an object");
		return value.getAsJsonObject();
	}

	private static JsonArray array(JsonObject parent, String name) {
		JsonElement value = parent.get(name);
		if (value == null || !value.isJsonArray()) throw failure("INVALID_SCENARIO_SNAPSHOT", name + " must be an array");
		return value.getAsJsonArray();
	}

	private static String string(JsonElement value, String field) {
		if (value == null || !value.isJsonPrimitive() || !value.getAsJsonPrimitive().isString()) {
			throw failure("INVALID_SCENARIO_SNAPSHOT", field + " must be a string");
		}
		return value.getAsString();
	}

	private static boolean bool(JsonObject object, String field) {
		JsonElement value = object.get(field);
		if (value == null || !value.isJsonPrimitive() || !value.getAsJsonPrimitive().isBoolean()) {
			throw failure("INVALID_SCENARIO_SNAPSHOT", field + " must be a boolean");
		}
		return value.getAsBoolean();
	}

	private static int exactInt(JsonObject object, String field) {
		long value = exactLong(object, field);
		if (value < Integer.MIN_VALUE || value > Integer.MAX_VALUE) throw failure("INVALID_SCENARIO_SNAPSHOT", field + " exceeds int range");
		return (int) value;
	}

	private static long exactLong(JsonObject object, String field) {
		JsonElement value = object.get(field);
		if (value == null || !value.isJsonPrimitive() || !value.getAsJsonPrimitive().isNumber()) {
			throw failure("INVALID_SCENARIO_SNAPSHOT", field + " must be an integer");
		}
		try {
			return value.getAsBigDecimal().longValueExact();
		} catch (ArithmeticException exception) {
			throw failure("INVALID_SCENARIO_SNAPSHOT", field + " must be an exact integer");
		}
	}

	private static double finiteDouble(JsonElement value, String field) {
		if (value == null || !value.isJsonPrimitive() || !value.getAsJsonPrimitive().isNumber()) {
			throw failure("INVALID_SCENARIO_SNAPSHOT", field + " must be numeric");
		}
		double result = value.getAsDouble();
		if (!Double.isFinite(result)) throw failure("INVALID_SCENARIO_SNAPSHOT", field + " must be finite");
		return result;
	}

	private static BigDecimal normalized(double value) {
		return value == 0.0D ? BigDecimal.ZERO : BigDecimal.valueOf(value).stripTrailingZeros();
	}

	private static String id(String value, String field) {
		String normalized = text(value, field, 64).toLowerCase(java.util.Locale.ROOT);
		if (!normalized.matches("[a-z0-9][a-z0-9_-]{0,63}")) throw failure("INVALID_SCENARIO_SNAPSHOT", field + " is invalid");
		return normalized;
	}

	private static String resourceId(String value, String field) {
		String normalized = text(value, field, 128).toLowerCase(java.util.Locale.ROOT);
		if (!normalized.matches("[a-z0-9_.-]+:[a-z0-9_./-]+")) throw failure("INVALID_SCENARIO_SNAPSHOT", field + " is invalid");
		return normalized;
	}

	private static String text(String value, String field, int maximum) {
		Objects.requireNonNull(value, field + " must not be null");
		String normalized = value.strip().replaceAll("\\s+", " ");
		if (normalized.isEmpty() || normalized.length() > maximum) throw failure("INVALID_SCENARIO_SNAPSHOT", field + " is blank or too long");
		return normalized;
	}

	private static IllegalArgumentException failure(String code, String message) {
		return new IllegalArgumentException(code + ": " + message);
	}

	private static String safeMessage(Throwable throwable) {
		String message = throwable.getMessage();
		return message == null || message.isBlank() ? throwable.getClass().getSimpleName() : message;
	}

	public record Participant(String id, String displayName, Optional<String> team) {
		public Participant {
			id = ScenarioRunSnapshot.id(id, "participant id");
			displayName = text(displayName, "participant displayName", 64);
			team = Objects.requireNonNull(team, "team must not be null").map(value -> ScenarioRunSnapshot.id(value, "team"));
		}

		private JsonObject toJson() {
			JsonObject object = new JsonObject();
			object.addProperty("id", id);
			object.addProperty("displayName", displayName);
			if (team.isPresent()) object.addProperty("team", team.orElseThrow());
			else object.add("team", null);
			return object;
		}

		private static Participant fromJson(JsonElement value) {
			JsonObject object = requireObject(value, "participant");
			requireExactKeys(object, Set.of("id", "displayName", "team"), "participant");
			JsonElement team = object.get("team");
			return new Participant(
					string(object.get("id"), "participant.id"),
					string(object.get("displayName"), "participant.displayName"),
					team.isJsonNull() ? Optional.empty() : Optional.of(string(team, "participant.team"))
			);
		}
	}

	public record Origin(int x, int y, int z) {
		private JsonObject toJson() {
			JsonObject object = new JsonObject();
			object.addProperty("x", x);
			object.addProperty("y", y);
			object.addProperty("z", z);
			return object;
		}

		private static Origin fromJson(JsonElement value) {
			JsonObject object = requireObject(value, "origin");
			requireExactKeys(object, Set.of("x", "y", "z"), "origin");
			return new Origin(exactInt(object, "x"), exactInt(object, "y"), exactInt(object, "z"));
		}
	}
}
