package dev.agaminggod.arenaagents.server.runtime.controller;

/** Pure postcondition policy: pickup succeeds only after the player's inventory really changes. */
public final class ItemPickupProgress {
	private ItemPickupProgress() {
	}

	public static Decision evaluate(int initialCount, int currentCount, boolean itemAlive, boolean timedOut) {
		if (initialCount < 0 || currentCount < 0) throw new IllegalArgumentException("item counts must not be negative");
		if (currentCount > initialCount) return Decision.SUCCEEDED;
		if (!itemAlive) return Decision.ITEM_UNAVAILABLE;
		return timedOut ? Decision.TIMED_OUT : Decision.RUNNING;
	}

	public enum Decision {
		RUNNING,
		SUCCEEDED,
		ITEM_UNAVAILABLE,
		TIMED_OUT
	}
}
