package dev.agaminggod.arenaagents.server.perception;

public final class InventoryObservationSlotsVerification {
	private InventoryObservationSlotsVerification() {
	}

	public static void main(String[] args) {
		System.out.println("PASS: " + verify() + " inventory observation slot assertions");
	}

	public static int verify() {
		assertFalse(
				InventoryObservationSlots.shouldEmitNumeric(4, 4, false),
				"selected main-hand slot is represented only by its named equipment entry"
		);
		for (int slot = 36; slot <= 42; slot++) {
			assertFalse(
					InventoryObservationSlots.shouldEmitNumeric(slot, 4, true),
					"inventory-mapped equipment slot " + slot + " is represented only by its named equipment entry"
			);
		}
		assertTrue(
				InventoryObservationSlots.shouldEmitNumeric(3, 4, false),
				"ordinary hotbar slot remains visible numerically"
		);
		assertTrue(
				InventoryObservationSlots.shouldEmitNumeric(35, 4, false),
				"ordinary storage slot remains visible numerically"
		);
		return 10;
	}

	private static void assertTrue(boolean condition, String label) {
		if (!condition) throw new AssertionError(label);
	}

	private static void assertFalse(boolean condition, String label) {
		assertTrue(!condition, label);
	}
}
