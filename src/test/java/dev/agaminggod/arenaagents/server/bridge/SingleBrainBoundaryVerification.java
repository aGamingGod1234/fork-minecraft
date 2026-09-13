package dev.agaminggod.arenaagents.server.bridge;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import dev.agaminggod.arenaagents.agent.AgentId;
import dev.agaminggod.arenaagents.server.runtime.input.InputStateSink;
import dev.agaminggod.arenaagents.server.runtime.input.LeasedServerInputController;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/** Verifies that bridge hazard publication stays factual and never acquires body input. */
public final class SingleBrainBoundaryVerification {
	private SingleBrainBoundaryVerification() {
	}

	public static int verify() {
		AgentId agent = AgentId.parse("00000000-0000-0000-0000-000000000071");
		MultiplexedServerBridge.ObservationPublication publication =
				new MultiplexedServerBridge.ObservationPublication(16, 16);
		RecordingSink sink = new RecordingSink();
		LeasedServerInputController input = new LeasedServerInputController(sink);
		Object session = new Object();
		MultiplexedServerBridge.onSessionAccepted(publication, session);
		assertEquals(MultiplexedServerBridge.ObservationPublication.Result.COMMITTED,
				MultiplexedServerBridge.publishObservationWithInputGuard(
						publication, Optional.of(input), agent, session, activeObservation(1_000L),
						(ignoredAgent, ignoredPayload) -> true, false),
				"active action establishes a bridge observation baseline");

		long revisionBeforeHazard = input.mutationRevision();
		List<JsonObject> delivered = new ArrayList<>();
		JsonObject lavaObservation = activeObservation(1_001L);
		lavaObservation.getAsJsonArray("blocks").add(block("minecraft:lava"));
		assertEquals(MultiplexedServerBridge.ObservationPublication.Result.COMMITTED,
				MultiplexedServerBridge.publishObservationWithInputGuard(
						publication, Optional.of(input), agent, session, lavaObservation, (ignoredAgent, payload) -> {
							delivered.add(payload.deepCopy());
							return true;
						}, false),
				"active-action lava publishes through the bridge");
		JsonObject payload = delivered.getFirst();
		assertTrue(payload.get("attention").getAsBoolean(), "active-action lava is urgent factual attention");
		assertTrue(payload.getAsJsonArray("changedFacts").contains(new com.google.gson.JsonPrimitive("blocks.0,64,0")),
				"active-action lava is published as an observed block fact");
		assertEquals(revisionBeforeHazard, input.mutationRevision(), "hazard publication does not mutate input revision");
		assertTrue(input.currentState(agent).isEmpty(), "hazard publication acquires no synthetic input lease");
		assertEquals(List.of(), sink.applied, "hazard publication emits no synthetic movement input");
		assertEquals(List.of(), sink.cleared, "hazard publication clears no body input");
		return 6;
	}

	private static JsonObject activeObservation(long observedAtEpochMs) {
		JsonObject observation = new JsonObject();
		observation.addProperty("observedAtEpochMs", observedAtEpochMs);
		JsonObject player = new JsonObject();
		player.addProperty("health", 20.0D);
		player.addProperty("onFire", false);
		player.addProperty("air", 300);
		player.addProperty("maxAir", 300);
		player.addProperty("suffocating", false);
		player.addProperty("fallDistance", 0.0D);
		observation.add("player", player);
		observation.add("blocks", new JsonArray());
		JsonObject currentAction = new JsonObject();
		currentAction.addProperty("active", true);
		currentAction.addProperty("actionType", "navigate_to");
		observation.add("currentAction", currentAction);
		return observation;
	}

	private static JsonObject block(String blockId) {
		JsonObject block = new JsonObject();
		block.addProperty("x", 0);
		block.addProperty("y", 64);
		block.addProperty("z", 0);
		block.addProperty("blockId", blockId);
		return block;
	}

	private static void assertEquals(Object expected, Object actual, String label) {
		if (!expected.equals(actual)) throw new AssertionError(label + ": expected <" + expected + "> but was <" + actual + ">");
	}

	private static void assertTrue(boolean condition, String label) {
		if (!condition) throw new AssertionError(label);
	}

	private static final class RecordingSink implements InputStateSink {
		private final List<Object> applied = new ArrayList<>();
		private final List<Object> cleared = new ArrayList<>();

		@Override
		public void apply(AgentId agentId, dev.agaminggod.arenaagents.server.runtime.input.AgentInputState previous,
				dev.agaminggod.arenaagents.server.runtime.input.AgentInputState state) {
			applied.add(state);
		}

		@Override
		public void clear(AgentId agentId, dev.agaminggod.arenaagents.server.runtime.input.AgentInputState previous) {
			cleared.add(agentId);
		}
	}
}
