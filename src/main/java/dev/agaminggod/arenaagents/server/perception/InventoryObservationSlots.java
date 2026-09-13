package dev.agaminggod.arenaagents.server.perception;

final class InventoryObservationSlots {
	private InventoryObservationSlots() {
	}

	static boolean shouldEmitNumeric(int slot, int selectedMainHandSlot, boolean mappedEquipmentSlot) {
		return slot != selectedMainHandSlot && !mappedEquipmentSlot;
	}
}
