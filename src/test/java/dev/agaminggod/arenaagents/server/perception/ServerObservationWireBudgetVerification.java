package dev.agaminggod.arenaagents.server.perception;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import dev.agaminggod.arenaagents.server.bridge.BridgeEnvelope;
import dev.agaminggod.arenaagents.server.bridge.BridgeEnvelopeCodec;
import dev.agaminggod.arenaagents.server.bridge.BridgeProtocolException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

public final class ServerObservationWireBudgetVerification {
	private static final String MAX_MESSAGE_ID = "m".repeat(128);

	private ServerObservationWireBudgetVerification() {
	}

	public static int verify() {
		BridgeEnvelopeCodec codec = new BridgeEnvelopeCodec();
		JsonObject utf8 = new JsonObject();
		utf8.addProperty("status", "ready-\u4e16\u754c");
		BridgeEnvelope utf8Envelope = envelope(utf8);
		assertEquals(codec.encode(utf8Envelope).getBytes(StandardCharsets.UTF_8).length,
				codec.encodedBytes(utf8Envelope), "encoded byte count includes UTF-8 and newline");

		JsonObject source = observation();
		AtomicInteger stagedFitChecks = new AtomicInteger();
		ServerObservationWireBudget.Fitted reduced = ServerObservationWireBudget.fit(source, candidate -> {
			stagedFitChecks.incrementAndGet();
			return !hasCandidateTags(candidate)
						&& candidate.getAsJsonArray("blocks").size() == 1
						&& candidate.getAsJsonArray("nearbyContainers").size() == 1
						&& candidate.getAsJsonArray("entities").size() == 1
						&& candidate.getAsJsonObject("player").getAsJsonArray("effects").size() == 1;
		});
		JsonObject fitted = reduced.observation();
		assertEquals(List.of("candidateTags", "blocks", "nearbyContainers", "entities", "player.effects"),
				reduced.reductions(), "deterministic reduction order");
		assertTrue(hasCandidateTags(source), "fitting does not mutate the source observation");
		assertTrue(!hasCandidateTags(fitted), "candidate tags are discarded first");
		assertEquals("nearest", fitted.getAsJsonArray("entities").get(0).getAsJsonObject().get("name").getAsString(),
				"nearest entity survives tail fitting");
		assertEquals("minecraft:diamond_pickaxe", fitted.getAsJsonObject("inventory").get("selectedItem").getAsString(),
				"selected item is preserved");
		JsonObject fittedItem = fitted.getAsJsonObject("inventory").getAsJsonArray("items").get(0).getAsJsonObject();
		assertEquals("minecraft:oak_log", fittedItem.get("itemId").getAsString(), "inventory item identity is preserved");
		assertEquals(12, fittedItem.get("count").getAsInt(), "inventory item count is preserved");
		assertEquals(12, fitted.getAsJsonObject("inventory").getAsJsonObject("tagCounts").get("#minecraft:logs").getAsInt(),
				"aggregate inventory tag counts are preserved");
		assertEquals(17.0D, fitted.getAsJsonObject("player").get("health").getAsDouble(), "player vitals are preserved");
		assertTrue(fitted.getAsJsonObject("player").has("lastAttacker"), "threat facts are preserved");
		assertTrue(fitted.getAsJsonObject("currentAction").get("active").getAsBoolean(), "current action is preserved");
		assertTrue(fitted.getAsJsonObject("lastResult").get("present").getAsBoolean(), "last result is preserved");
		assertThrows(UnsupportedOperationException.class, () -> reduced.reductions().add("mutation"),
				"reduction report is immutable");
		fitted.remove("player");
		assertTrue(reduced.observation().has("player"), "fitted observation accessor is detached");
		assertEquals(6, stagedFitChecks.get(),
				"each staged reduction performs one complete-envelope check without duplicate fitting");

		AtomicReference<JsonObject> exposedCandidate = new AtomicReference<>();
		ServerObservationWireBudget.Fitted isolated = ServerObservationWireBudget.fit(source, candidate -> {
			exposedCandidate.set(candidate);
			return true;
		});
		exposedCandidate.get().remove("player");
		assertTrue(isolated.observation().has("player"),
				"a fitting predicate cannot retain a mutable alias to the fitted observation");

		JsonObject oversized = observation();
		for (int index = 0; index < 64; index++) {
			JsonObject entity = candidate("entity-" + index, "x".repeat(2_000));
			entity.addProperty("uuid", String.format("00000000-0000-0000-0000-%012d", index + 1));
			oversized.getAsJsonArray("entities").add(entity);
		}
		ServerObservationWireBudget.Fitted wireFitted = ServerObservationWireBudget.fit(oversized,
				candidate -> codec.encodedLineBytes(envelope(candidate)) <= BridgeEnvelopeCodec.MAX_LINE_BYTES);
		assertTrue(codec.encodedLineBytes(envelope(wireFitted.observation())) <= BridgeEnvelopeCodec.MAX_LINE_BYTES,
				"worst-case complete envelope with maximum message ID fits the wire limit");
		assertEquals("nearest", wireFitted.observation().getAsJsonArray("entities").get(0).getAsJsonObject()
				.get("name").getAsString(), "wire fitting retains the nearest original entity");

		JsonObject singleton = new JsonObject();
		JsonArray singletonBlocks = new JsonArray();
		singletonBlocks.add(new JsonObject());
		singleton.add("blocks", singletonBlocks);
		ServerObservationWireBudget.Fitted emptyOptional = ServerObservationWireBudget.fit(singleton,
				candidate -> candidate.getAsJsonArray("blocks").isEmpty());
		assertEquals(0, emptyOptional.observation().getAsJsonArray("blocks").size(),
				"a lone optional candidate can be removed when protected facts require the space");
		assertEquals(List.of("blocks"), emptyOptional.reductions(),
				"dropping a lone optional candidate is reported once");

		JsonObject impossible = observation();
		impossible.getAsJsonObject("lastResult").addProperty("message", "x".repeat(BridgeEnvelopeCodec.MAX_LINE_BYTES));
		assertThrowsCode(() -> ServerObservationWireBudget.fit(impossible,
				candidate -> codec.encodedLineBytes(envelope(candidate)) <= BridgeEnvelopeCodec.MAX_LINE_BYTES),
				"OBSERVATION_TOO_LARGE", "protected essentials fail closed when they cannot fit");
		return 21;
	}

	private static BridgeEnvelope envelope(JsonObject payload) {
		return new BridgeEnvelope(2, "server-instance", "00000000-0000-0000-0000-000000000001",
				"observation", MAX_MESSAGE_ID, payload);
	}

	private static JsonObject observation() {
		JsonObject observation = new JsonObject();
		observation.addProperty("goalRevision", 1L);
		observation.addProperty("observedAtEpochMs", 1L);
		observation.addProperty("ready", true);
		observation.addProperty("status", "ACTING");
		JsonObject player = new JsonObject();
		player.addProperty("health", 17.0D);
		player.addProperty("maxHealth", 20.0D);
		JsonObject attacker = new JsonObject();
		attacker.addProperty("uuid", "00000000-0000-0000-0000-000000000099");
		player.add("lastAttacker", attacker);
		JsonArray effects = new JsonArray();
		effects.add(candidate("effect-nearest", "first"));
		effects.add(candidate("effect-tail", "second"));
		player.add("effects", effects);
		observation.add("player", player);
		JsonObject inventory = new JsonObject();
		JsonArray items = new JsonArray();
		JsonObject item = candidate("inventory", "item");
		item.addProperty("itemId", "minecraft:oak_log");
		item.addProperty("count", 12);
		items.add(item);
		inventory.add("items", items);
		JsonObject tagCounts = new JsonObject();
		tagCounts.addProperty("#minecraft:logs", 12);
		inventory.add("tagCounts", tagCounts);
		inventory.addProperty("selectedItem", "minecraft:diamond_pickaxe");
		observation.add("inventory", inventory);
		JsonArray entities = new JsonArray();
		entities.add(candidate("nearest", "entity-nearest"));
		entities.add(candidate("entity-tail", "entity-tail"));
		observation.add("entities", entities);
		observation.add("blocks", candidates("block-nearest", "block-tail"));
		observation.add("nearbyContainers", candidates("container-nearest", "container-tail"));
		JsonObject currentAction = new JsonObject();
		currentAction.addProperty("active", true);
		currentAction.addProperty("actionId", "action-1");
		observation.add("currentAction", currentAction);
		JsonObject lastResult = new JsonObject();
		lastResult.addProperty("present", true);
		lastResult.addProperty("actionId", "action-0");
		lastResult.addProperty("state", "SUCCEEDED");
		observation.add("lastResult", lastResult);
		return observation;
	}

	private static JsonArray candidates(String first, String second) {
		JsonArray values = new JsonArray();
		values.add(candidate(first, first));
		values.add(candidate(second, second));
		return values;
	}

	private static JsonObject candidate(String name, String value) {
		JsonObject candidate = new JsonObject();
		candidate.addProperty("name", name);
		candidate.addProperty("value", value);
		JsonArray tags = new JsonArray();
		tags.add("#minecraft:very_long_candidate_tag");
		candidate.add("tags", tags);
		return candidate;
	}

	private static boolean hasCandidateTags(JsonObject observation) {
		ArrayList<JsonArray> arrays = new ArrayList<>();
		for (String field : List.of("blocks", "nearbyContainers", "entities")) {
			if (observation.has(field) && observation.get(field).isJsonArray()) arrays.add(observation.getAsJsonArray(field));
		}
		if (observation.has("inventory") && observation.get("inventory").isJsonObject()) {
			JsonObject inventory = observation.getAsJsonObject("inventory");
			if (inventory.has("items") && inventory.get("items").isJsonArray()) arrays.add(inventory.getAsJsonArray("items"));
		}
		return arrays.stream().flatMap(array -> array.asList().stream())
				.anyMatch(value -> value.isJsonObject() && value.getAsJsonObject().has("tags"));
	}

	private static void assertThrowsCode(Runnable action, String code, String label) {
		try {
			action.run();
		} catch (BridgeProtocolException exception) {
			assertEquals(code, exception.code(), label);
			return;
		}
		throw new AssertionError(label + " did not throw " + code);
	}

	private static void assertThrows(Class<? extends Throwable> type, Runnable action, String label) {
		try {
			action.run();
		} catch (Throwable throwable) {
			if (type.isInstance(throwable)) return;
			throw new AssertionError(label + " threw " + throwable.getClass().getSimpleName(), throwable);
		}
		throw new AssertionError(label + " did not throw " + type.getSimpleName());
	}

	private static void assertTrue(boolean condition, String label) {
		if (!condition) throw new AssertionError(label);
	}

	private static void assertEquals(Object expected, Object actual, String label) {
		if (!expected.equals(actual)) throw new AssertionError(label + ": expected <" + expected + "> but was <" + actual + ">");
	}
}
