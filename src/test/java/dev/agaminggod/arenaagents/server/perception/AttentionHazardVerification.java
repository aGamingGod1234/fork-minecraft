package dev.agaminggod.arenaagents.server.perception;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

/** Verifies that urgent hazards stay factual attention, not body-authored tactics. */
public final class AttentionHazardVerification {
	private AttentionHazardVerification() {
	}

	public static int verify() {
		JsonObject before = observation("mine");
		JsonObject after = before.deepCopy();
		JsonObject player = after.getAsJsonObject("player");
		player.addProperty("health", 18.0D);
		player.addProperty("onFire", true);
		player.addProperty("air", 40);
		player.addProperty("suffocating", true);
		player.addProperty("fallDistance", 8.0D);
		JsonObject attacker = new JsonObject();
		attacker.addProperty("uuid", "00000000-0000-0000-0000-000000000001");
		attacker.addProperty("type", "minecraft:zombie");
		player.add("lastAttacker", attacker);

		AttentionFactDelta active = AttentionFactDelta.between(before, after, 7L, 123L);
		assertTrue(active.attention(), "hazard changes gain urgent attention during a brain-authored action");
		for (String fact : new String[] {
				"player.health", "player.onFire", "player.air", "player.suffocating",
				"player.fallDistance", "player.lastAttacker"
		}) {
			assertTrue(active.changedFacts().contains(fact), fact + " remains a factual attention delta");
		}
		assertFalse(active.changedFacts().stream().anyMatch(AttentionHazardVerification::isTacticalLabel),
				"hazard attention does not invent a tactical label");

		JsonObject lavaBefore = observation("idle");
		JsonObject lavaAfter = lavaBefore.deepCopy();
		lavaAfter.getAsJsonArray("blocks").add(block("minecraft:lava"));
		AttentionFactDelta lava = AttentionFactDelta.between(lavaBefore, lavaAfter, 8L, 124L);
		assertTrue(lava.attention(), "observed lava remains a factual attention delta");
		assertTrue(lava.changedFacts().contains("blocks.0,64,0"), "lava is delivered as an observed block fact");
		assertFalse(lava.changedFacts().stream().anyMatch(AttentionHazardVerification::isTacticalLabel),
				"lava attention does not choose flee, jump, or another movement");
		return 11;
	}

	private static JsonObject observation(String actionType) {
		JsonObject value = new JsonObject();
		JsonObject player = new JsonObject();
		player.addProperty("health", 20.0D);
		player.addProperty("onFire", false);
		player.addProperty("air", 300);
		player.addProperty("maxAir", 300);
		player.addProperty("suffocating", false);
		player.addProperty("fallDistance", 0.0D);
		value.add("player", player);
		value.add("blocks", new JsonArray());
		JsonObject currentAction = new JsonObject();
		currentAction.addProperty("active", !"idle".equals(actionType));
		currentAction.addProperty("actionType", actionType);
		value.add("currentAction", currentAction);
		return value;
	}

	private static JsonObject block(String blockId) {
		JsonObject value = new JsonObject();
		value.addProperty("x", 0);
		value.addProperty("y", 64);
		value.addProperty("z", 0);
		value.addProperty("blockId", blockId);
		return value;
	}

	private static boolean isTacticalLabel(String value) {
		String lower = value.toLowerCase(java.util.Locale.ROOT);
		return switch (lower) {
			case "danger", "flee", "escape", "jump", "sprint", "attack", "move", "tactic" -> true;
			default -> false;
		};
	}

	private static void assertTrue(boolean value, String label) {
		if (!value) throw new AssertionError(label);
	}

	private static void assertFalse(boolean value, String label) {
		if (value) throw new AssertionError(label);
	}
}
