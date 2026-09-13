package dev.agaminggod.arenaagents.server.runtime.transaction;

import java.util.List;

public final class TransactionPostconditionVerification {
	private TransactionPostconditionVerification() {
	}

	public static int verify() {
		TransactionSnapshot before = snapshot(
				slot(0, "minecraft:oak_log", 10, "plain"),
				slot(1, "minecraft:oak_log", 3, "plain"),
				slot(2, "minecraft:cobblestone", 8, "plain")
		);
		TransactionSnapshot after = snapshot(
				slot(0, "minecraft:oak_log", 6, "plain"),
				slot(1, "minecraft:oak_log", 7, "plain"),
				slot(2, "minecraft:cobblestone", 8, "plain")
		);
		assertSucceeded(before.verifyExactTransfer(after, 0, 1, "minecraft:oak_log", 4),
				"exact debit and credit succeeds");
		assertEquals(13, after.totalCount("minecraft:oak_log"), "transferred item count is conserved");

		TransactionSnapshot unrelatedChanged = snapshot(
				slot(0, "minecraft:oak_log", 6, "plain"),
				slot(1, "minecraft:oak_log", 7, "plain"),
				slot(2, "minecraft:cobblestone", 7, "plain")
		);
		assertFailed(before.verifyExactTransfer(unrelatedChanged, 0, 1, "minecraft:oak_log", 4),
				"TRANSACTION_CONFLICT", "unrelated slot changes conflict");

		TransactionSnapshot wrongCredit = snapshot(
				slot(0, "minecraft:oak_log", 6, "plain"),
				slot(1, "minecraft:oak_log", 6, "plain"),
				slot(2, "minecraft:cobblestone", 8, "plain")
		);
		assertFailed(before.verifyExactTransfer(wrongCredit, 0, 1, "minecraft:oak_log", 4),
				"TRANSACTION_CONFLICT", "partial destination credit conflicts");

		TransactionSnapshot rejectedDestination = snapshot(
				slot(0, "minecraft:oak_log", 6, "plain"),
				slot(1, "minecraft:oak_log", 3, "plain"),
				slot(2, "minecraft:cobblestone", 8, "plain")
		);
		TransactionSnapshot.RollbackPlan rollback = before.rollbackPlan(rejectedDestination, 0, 1);
		assertEquals(4, rollback.sourceCredit(), "rollback restores the complete source debit");
		assertEquals(0, rollback.destinationDebit(), "rejected destination has no debit to undo");
		assertEquals(before, rollback.apply(rejectedDestination), "rollback plan restores the exact source snapshot");

		TransactionSnapshot equipped = snapshot(
				slot(9, "", 0, ""),
				slot(6, "minecraft:iron_chestplate", 1, "undamaged")
		);
		TransactionSnapshot unequipped = snapshot(
				slot(9, "minecraft:iron_chestplate", 1, "undamaged"),
				slot(6, "", 0, "")
		);
		assertSucceeded(unequipped.verifyExactTransfer(
				equipped, 9, 6, "minecraft:iron_chestplate", 1), "equipment uses exact slot semantics");

		TransactionSnapshot.TransferAccounting partialInsert = new TransactionSnapshot.TransferAccounting(4, 4, 2, 2);
		assertSucceeded(partialInsert.verifyOwnership(), "accepted and escrowed transfer stacks remain owned");
		assertEquals(4, partialInsert.rollbackSourceCredit(2), "rollback combines recovered destination and escrow stacks");
		assertEquals(2, partialInsert.rollbackSourceCredit(0), "unrecovered destination credit remains owned in destination");
		assertFailed(
				new TransactionSnapshot.TransferAccounting(4, 4, 2, 1).verifyOwnership(),
				"OWNERSHIP_LOST",
				"missing transfer escrow is detected before rollback"
		);

		TransactionSnapshot.CraftingAccounting craft = TransactionSnapshot.CraftingAccounting.plan(
				List.of(
						owned("minecraft:iron_ingot", 3, "plain"),
						owned("minecraft:milk_bucket", 1, "milk")
				),
				List.of(
						owned("minecraft:iron_ingot", 3, "plain"),
						owned("minecraft:milk_bucket", 1, "milk")
				),
				List.of(owned("minecraft:bucket", 1, "plain")),
				owned("minecraft:cauldron", 1, "plain")
		);
		assertSucceeded(craft.verify(List.of(
				owned("minecraft:bucket", 1, "plain"),
				owned("minecraft:cauldron", 1, "plain")
		)), "craft accounting includes exact ingredients, remainder, and output");
		assertFailed(craft.verify(List.of(owned("minecraft:cauldron", 1, "plain"))),
				"CRAFT_ACCOUNTING_FAILED", "craft cannot succeed when a remainder is missing");
		assertFailed(craft.verify(List.of(
				owned("minecraft:bucket", 1, "plain"),
				owned("minecraft:cauldron", 1, "plain"),
				owned("minecraft:diamond", 1, "plain")
		)), "CRAFT_ACCOUNTING_FAILED", "craft cannot succeed with an unexplained ownership change");

		TransactionSnapshot.CraftPlacementGuard placementGuard =
				new TransactionSnapshot.CraftPlacementGuard(List.of(
						owned("minecraft:oak_log", 2, "plain"),
						owned("minecraft:stick", 1, "plain")
				));
		placementGuard.markPlacementAttempted();
		assertSucceeded(placementGuard.verifyPlacement(List.of(
				owned("minecraft:stick", 1, "plain"),
				owned("minecraft:oak_log", 2, "plain")
		)), "recipe placement conserves full inventory and grid ownership");
		assertTrue(placementGuard.mayReportCleanFailure(List.of(
				owned("minecraft:oak_log", 2, "plain"),
				owned("minecraft:stick", 1, "plain")
		)), "clean failure is allowed only after exact pre-placement ownership restoration");
		assertFalse(placementGuard.mayReportCleanFailure(List.of(
				owned("minecraft:oak_log", 1, "plain"),
				owned("minecraft:stick", 1, "plain")
		)), "missing placement ownership requires rollback failure");

		TransactionSnapshot thrownInsert = snapshot(
				slot(0, "minecraft:oak_log", 6, "plain"),
				slot(1, "minecraft:oak_log", 5, "plain"),
				slot(2, "minecraft:cobblestone", 8, "plain")
		);
		TransactionSnapshot.TransferRecoveryState insertRecovery =
				TransactionSnapshot.TransferRecoveryState.observe(before, thrownInsert, 0, 1, 2);
		assertEquals(4, insertRecovery.sourceDebit(), "thrown insert recovery observes the source debit");
		assertEquals(2, insertRecovery.destinationCredit(), "thrown insert recovery observes destination credit");
		assertEquals(2, insertRecovery.destinationRecoveryCount(), "thrown insert recovery requests exact destination escrow");
		assertTrue(insertRecovery.ownershipAccounted(), "destination plus reachable escrow accounts for thrown insert ownership");

		TransactionSnapshot thrownTake = snapshot(
				slot(0, "minecraft:oak_log", 6, "plain"),
				slot(1, "minecraft:oak_log", 3, "plain"),
				slot(2, "minecraft:cobblestone", 8, "plain")
		);
		TransactionSnapshot.TransferRecoveryState lostTake =
				TransactionSnapshot.TransferRecoveryState.observe(before, thrownTake, 0, 1, 0);
		assertFalse(lostTake.ownershipAccounted(), "thrown safeTake without reachable escrow cannot claim ownership");
		assertEquals(0, lostTake.destinationRecoveryCount(), "thrown safeTake does not invent destination escrow");

		TransactionSnapshot.TransferMutationEscrow<String> mutationEscrow =
				new TransactionSnapshot.TransferMutationEscrow<>(value -> value != null && !value.isBlank());
		mutationEscrow.retain("safeTake-result");
		assertThrows(IllegalStateException.class, () -> mutationEscrow.attempt(() -> {
			throw new IllegalStateException("safeInsert threw after debit");
		}), "throwing slot mutation leaves prior reachable escrow retained");
		assertEquals(List.of("safeTake-result"), mutationEscrow.reachable(),
				"mutation exception cannot discard previously reachable escrow");

		TransactionSnapshot.CraftPlacementMode survivalPlacement =
				TransactionSnapshot.CraftPlacementMode.oneCraft(false);
		assertFalse(survivalPlacement.useMaxItems(), "one-craft placement never requests maximum craft count");
		assertFalse(survivalPlacement.allowDroppingItemsToClear(),
				"survival placement cannot drop inventory items while clearing the grid");
		TransactionSnapshot.CraftPlacementMode creativePlacement =
				TransactionSnapshot.CraftPlacementMode.oneCraft(true);
		assertFalse(creativePlacement.useMaxItems(), "creative one-craft mode does not swap into max-item placement");
		assertTrue(creativePlacement.allowDroppingItemsToClear(),
				"creative status occupies handlePlacement's second boolean");

		assertEquals(List.of("a", "b"), TransactionSnapshot.expandCraftingRemainders(
				List.of("a", "b"), 2, 2, 2, 0, 0, "empty"),
				"already full-grid remainder layouts are preserved");
		assertEquals(List.of("empty", "empty", "empty", "empty", "a", "b", "empty", "empty", "empty"),
				TransactionSnapshot.expandCraftingRemainders(List.of("a", "b"), 9, 3, 2, 1, 1, "empty"),
				"trimmed recipe remainders are restored to their full crafting-grid coordinates");
		assertEquals(List.of("empty", "empty", "empty", "empty", "a", "empty", "empty", "b", "empty"),
				TransactionSnapshot.expandCraftingRemainders(List.of("a", "b"), 9, 3, 1, 1, 1, "empty"),
				"vertical trimmed recipes preserve their one-column remainder layout");
		assertThrows(IllegalArgumentException.class,
				() -> TransactionSnapshot.expandCraftingRemainders(List.of("a", "b"), 4, 2, 2, 2, 0, "empty"),
				"out-of-bounds remainder positioning is rejected");
		return 37;
	}

	private static TransactionSnapshot.OwnedStack owned(String itemId, int count, String fingerprint) {
		return new TransactionSnapshot.OwnedStack(itemId, count, fingerprint);
	}

	private static TransactionSnapshot snapshot(TransactionSnapshot.SlotState... slots) {
		return new TransactionSnapshot(List.of(slots));
	}

	private static TransactionSnapshot.SlotState slot(int index, String itemId, int count, String fingerprint) {
		return new TransactionSnapshot.SlotState(index, itemId, count, fingerprint);
	}

	private static void assertSucceeded(TransactionPostcondition.Verdict verdict, String label) {
		if (!(verdict instanceof TransactionPostcondition.Verdict.Succeeded)) {
			throw new AssertionError(label + ": expected success but got " + verdict);
		}
	}

	private static void assertFailed(TransactionPostcondition.Verdict verdict, String reason, String label) {
		if (!(verdict instanceof TransactionPostcondition.Verdict.Failed failed)
				|| !reason.equals(failed.reasonCode())) {
			throw new AssertionError(label + ": expected " + reason + " but got " + verdict);
		}
	}

	private static void assertEquals(Object expected, Object actual, String label) {
		if (!expected.equals(actual)) {
			throw new AssertionError(label + ": expected <" + expected + "> but was <" + actual + ">");
		}
	}

	private static void assertTrue(boolean value, String label) {
		if (!value) throw new AssertionError(label);
	}

	private static void assertFalse(boolean value, String label) {
		if (value) throw new AssertionError(label);
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
}
