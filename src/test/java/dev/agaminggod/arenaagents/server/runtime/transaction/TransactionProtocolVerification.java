package dev.agaminggod.arenaagents.server.runtime.transaction;

import com.google.gson.JsonObject;
import dev.agaminggod.arenaagents.agent.AgentId;
import dev.agaminggod.arenaagents.protocol.ActionType;
import dev.agaminggod.arenaagents.protocol.ProtocolCodec;
import dev.agaminggod.arenaagents.protocol.ProtocolException;
import dev.agaminggod.arenaagents.server.bridge.BridgeProtocolException;
import dev.agaminggod.arenaagents.server.bridge.BridgeEnvelope;
import dev.agaminggod.arenaagents.server.bridge.MultiplexedServerBridge;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.Map;

public final class TransactionProtocolVerification {
	private TransactionProtocolVerification() {
	}

	public static void main(String[] args) throws Exception {
		System.out.println("PASS: " + verify() + " transaction protocol assertions");
	}

	public static int verify() throws Exception {
		verifyStrictActionSchemas();
		verifyLiveBridgeRejectsUnknownArgumentBeforeSubmit();
		verifyImmutablePostconditionVerdicts();
		verifyUseConfirmationTransitions();
		return 22;
	}

	private static void verifyStrictActionSchemas() {
		Map<String, JsonObject> valid = Map.of(
				"transfer_container", json(
						"x", 1, "y", 64, "z", -2,
						"sourceKind", "player", "sourceSlot", 0,
						"destinationKind", "container", "destinationSlot", 4,
						"count", 3, "expectedItemId", "minecraft:oak_log", "timeoutMs", 5_000
				),
				"craft_inventory", json("recipeId", "minecraft:oak_planks", "count", 4, "timeoutMs", 5_000),
				"craft_table", json(
						"recipeId", "minecraft:crafting_table", "x", 2, "y", 64, "z", 3,
						"count", 1, "timeoutMs", 5_000
				),
				"furnace_transaction", json(
						"x", 2, "y", 64, "z", 3, "operation", "insert_input", "inventorySlot", 5,
						"count", 1, "expectedItemId", "minecraft:raw_iron", "timeoutMs", 5_000
				),
				"equip_item", json("sourceSlot", 5, "targetSlot", "chest", "expectedItemId", "minecraft:iron_chestplate"),
				"select_tool", json(
						"sourceSlot", 5, "hotbarSlot", 1, "expectedItemId", "minecraft:iron_pickaxe",
						"minRemainingDurability", 32
				),
				"block_with_shield", json("durationMs", 750),
				"use_ranged", json("targetId", "00000000-0000-0000-0000-000000000001", "drawDurationMs", 1_000, "timeoutMs", 5_000)
		);

		for (Map.Entry<String, JsonObject> entry : valid.entrySet()) {
			ActionType type = ActionType.fromWireName(entry.getKey()).orElseThrow();
			assertEquals(entry.getValue(), ProtocolCodec.validateActionArguments(type, entry.getValue()), entry.getKey() + " validates");
		}

		JsonObject zeroTransfer = valid.get("transfer_container").deepCopy();
		zeroTransfer.addProperty("count", 0);
		verifyRejects("transfer_container", zeroTransfer, "OUT_OF_RANGE");

		JsonObject invalidEquipmentTarget = valid.get("equip_item").deepCopy();
		invalidEquipmentTarget.addProperty("targetSlot", "mainhand");
		verifyRejects("equip_item", invalidEquipmentTarget, "INVALID_FIELD");

		JsonObject invalidKind = valid.get("transfer_container").deepCopy();
		invalidKind.addProperty("sourceKind", "ender_chest");
		verifyRejects("transfer_container", invalidKind, "INVALID_FIELD");

		JsonObject invalidFurnaceOperation = valid.get("furnace_transaction").deepCopy();
		invalidFurnaceOperation.addProperty("operation", "smelt");
		verifyRejects("furnace_transaction", invalidFurnaceOperation, "INVALID_FIELD");

		JsonObject unknown = valid.get("transfer_container").deepCopy();
		unknown.addProperty("extra", true);
		verifyRejects("transfer_container", unknown, "UNKNOWN_FIELD");

		for (String whitespace : new String[] {" \t\r\n", "\u00a0"}) {
			JsonObject blankItem = valid.get("transfer_container").deepCopy();
			blankItem.addProperty("expectedItemId", whitespace);
			verifyRejects("transfer_container", blankItem, "INVALID_FIELD");

			JsonObject blankRecipe = valid.get("craft_inventory").deepCopy();
			blankRecipe.addProperty("recipeId", whitespace);
			verifyRejects("craft_inventory", blankRecipe, "INVALID_FIELD");

			JsonObject blankTarget = valid.get("use_ranged").deepCopy();
			blankTarget.addProperty("targetId", whitespace);
			verifyRejects("use_ranged", blankTarget, "INVALID_FIELD");
		}
		JsonObject selector = valid.get("use_ranged").deepCopy();
		selector.addProperty("targetSelector", "nearest_hostile");
		verifyRejects("use_ranged", selector, "UNKNOWN_FIELD");
		JsonObject nonUuid = valid.get("use_ranged").deepCopy();
		nonUuid.addProperty("targetId", "nearest_hostile");
		verifyRejects("use_ranged", nonUuid, "INVALID_FIELD");
	}

	private static void verifyLiveBridgeRejectsUnknownArgumentBeforeSubmit() throws Exception {
		JsonObject arguments = json("durationMs", 25, "extra", true);
		JsonObject payload = json(
				"goalRevision", 7,
				"actionId", "action-1",
				"actionType", "wait"
		);
		payload.add("arguments", arguments);
		payload.add("provenance", provenance());
		BridgeEnvelope envelope = new BridgeEnvelope(
				2,
				"server-instance",
				AgentId.random().toString(),
				"action_command",
				"message-1",
				payload
		);
		Method decoder = MultiplexedServerBridge.class.getDeclaredMethod("decodeActionRequest", BridgeEnvelope.class);
		decoder.setAccessible(true);
		try {
			decoder.invoke(null, envelope);
		} catch (InvocationTargetException exception) {
			Throwable cause = exception.getCause();
			assertTrue(cause instanceof BridgeProtocolException, "live bridge wraps shared protocol rejection into a correlated bridge failure");
			assertEquals("UNKNOWN_FIELD", ((BridgeProtocolException) cause).code(), "live bridge rejects unknown argument");
			return;
		}
		throw new AssertionError("live bridge accepted an unknown argument");
	}

	private static JsonObject provenance() {
		return json(
				"provider", "codex", "model", "gpt-5.6-sol", "reasoningEffort", "high", "serviceTier", "priority",
				"programId", "program-7-1", "programVersion", 1, "sourceStepId", "step-1-1", "eventSequence", 1
		);
	}

	private static void verifyImmutablePostconditionVerdicts() {
		TransactionPostcondition.Verdict succeeded = new TransactionPostcondition.Verdict.Succeeded("Transferred 3 items.");
		TransactionPostcondition.Verdict failed = new TransactionPostcondition.Verdict.Failed("CONFLICT", "Inventory changed.");
		assertEquals("Transferred 3 items.", ((TransactionPostcondition.Verdict.Succeeded) succeeded).message(), "success verdict message");
		assertEquals("CONFLICT", ((TransactionPostcondition.Verdict.Failed) failed).reasonCode(), "failure verdict reason");
		assertEquals("Inventory changed.", ((TransactionPostcondition.Verdict.Failed) failed).message(), "failure verdict message");
	}

	private static void verifyUseConfirmationTransitions() {
		UseConfirmation initial = UseConfirmation.initial();
		assertTrue(!initial.confirmed(), "use is initially unconfirmed");
		UseConfirmation notStarted = initial.observeUsing(false);
		assertEquals(initial, notStarted, "release before start is ignored");
		UseConfirmation started = initial.observeUsing(true);
		assertTrue(started.observedStart(), "use start is observed");
		UseConfirmation confirmed = started.observeUsing(false);
		assertTrue(confirmed.confirmed(), "release after start confirms use");
		assertEquals(confirmed, confirmed.observeUsing(true), "confirmed use remains terminal");
	}

	private static void verifyRejects(String wireName, JsonObject arguments, String expectedCode) {
		try {
			ProtocolCodec.validateActionArguments(ActionType.fromWireName(wireName).orElseThrow(), arguments);
		} catch (ProtocolException exception) {
			assertEquals(expectedCode, exception.code(), wireName + " rejection code");
			return;
		}
		throw new AssertionError(wireName + " was accepted");
	}

	private static JsonObject json(Object... fields) {
		JsonObject object = new JsonObject();
		for (int index = 0; index < fields.length; index += 2) {
			String name = (String) fields[index];
			Object value = fields[index + 1];
			if (value instanceof String text) object.addProperty(name, text);
			else if (value instanceof Number number) object.addProperty(name, number);
			else if (value instanceof Boolean bool) object.addProperty(name, bool);
			else throw new IllegalArgumentException("Unsupported fixture value: " + value);
		}
		return object;
	}

	private static void assertEquals(Object expected, Object actual, String label) {
		if (!expected.equals(actual)) throw new AssertionError(label + ": expected <" + expected + "> but was <" + actual + ">");
	}

	private static void assertTrue(boolean condition, String label) {
		if (!condition) throw new AssertionError(label);
	}

	private static void assertThrows(Class<? extends Throwable> expected, ThrowingRunnable action, String label) {
		try {
			action.run();
		} catch (Throwable throwable) {
			if (expected.isInstance(throwable)) return;
			throw new AssertionError(label + " threw " + throwable.getClass().getSimpleName(), throwable);
		}
		throw new AssertionError(label + " did not throw " + expected.getSimpleName());
	}

	@FunctionalInterface
	private interface ThrowingRunnable {
		void run() throws Exception;
	}
}
