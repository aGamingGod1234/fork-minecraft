package dev.agaminggod.arenaagents.server.runtime.controller;

import java.util.ArrayList;
import java.util.List;
import net.minecraft.world.level.Level;

public final class ItemPickupProgressVerification {
	private ItemPickupProgressVerification() {
	}

	public static void main(String[] arguments) {
		System.out.println("PASS: " + verify() + " item pickup assertions");
	}

	public static int verify() {
		assertTrue(ServerItemPickupController.remainsInDimension(Level.OVERWORLD, Level.OVERWORLD),
				"item pickup remains valid in its starting dimension");
		assertTrue(!ServerItemPickupController.remainsInDimension(Level.OVERWORLD, Level.NETHER),
				"item pickup rejects an item coordinate after the player changes dimension");
		assertEquals(7_500L, ServerItemPickupController.remainingNavigationTimeout(10_000L, 2_500L),
				"item pickup passes its remaining action timeout to navigation");
		assertEquals(1L, ServerItemPickupController.remainingNavigationTimeout(10_000L, Long.MAX_VALUE),
				"item pickup bounds remaining navigation timeout after elapsed-time saturation");
		assertEquals(ItemPickupProgress.Decision.RUNNING,
				ItemPickupProgress.evaluate(2, 2, true, false), "approaching a live item keeps moving");
		assertEquals(ItemPickupProgress.Decision.SUCCEEDED,
				ItemPickupProgress.evaluate(2, 3, false, false), "only an observed inventory increase succeeds");
		assertEquals(ItemPickupProgress.Decision.ITEM_UNAVAILABLE,
				ItemPickupProgress.evaluate(2, 2, false, false), "a vanished item never becomes synthetic loot");
		assertEquals(ItemPickupProgress.Decision.TIMED_OUT,
				ItemPickupProgress.evaluate(2, 2, true, true), "a live unreachable item times out");
		List<String> lifecycle = new ArrayList<>();
		Object previous = new Object();
		Object replacement = new Object();
		assertEquals(replacement, ServerItemPickupController.replaceNavigation(
				previous,
				ignored -> lifecycle.add("released"),
				() -> {
					lifecycle.add("created");
					return replacement;
				}
		), "moving-item replanning installs the replacement controller");
		assertEquals(List.of("released", "created"), lifecycle,
				"moving-item replanning releases the old navigation lease before replacement");
		return 10;
	}

	private static void assertTrue(boolean condition, String message) {
		if (!condition) throw new AssertionError(message);
	}

	private static void assertEquals(Object expected, Object actual, String message) {
		if (!expected.equals(actual)) throw new AssertionError(message + " expected=" + expected + " actual=" + actual);
	}
}
