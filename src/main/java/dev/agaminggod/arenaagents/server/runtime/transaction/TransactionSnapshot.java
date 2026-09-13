package dev.agaminggod.arenaagents.server.runtime.transaction;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Predicate;
import java.util.function.Supplier;

public record TransactionSnapshot(List<SlotState> slots) {
	public TransactionSnapshot {
		Objects.requireNonNull(slots, "slots must not be null");
		HashSet<Integer> indexes = new HashSet<>();
		ArrayList<SlotState> copy = new ArrayList<>(slots.size());
		for (SlotState slot : slots) {
			Objects.requireNonNull(slot, "slot must not be null");
			if (!indexes.add(slot.index())) throw new IllegalArgumentException("duplicate slot " + slot.index());
			copy.add(slot);
		}
		copy.sort(Comparator.comparingInt(SlotState::index));
		slots = List.copyOf(copy);
	}

	public int totalCount(String itemId) {
		return slots.stream().filter(slot -> slot.itemId().equals(itemId)).mapToInt(SlotState::count).sum();
	}

	/**
	 * Expands recipe output aligned to a trimmed CraftingInput back onto the menu's full grid.
	 * Newer Minecraft versions crop empty rows/columns when constructing CraftingInput, so
	 * getRemainingItems(input) legitimately returns the cropped input size.
	 */
	public static <T> List<T> expandCraftingRemainders(
			List<T> remainders,
			int fullGridSize,
			int fullGridWidth,
			int trimmedWidth,
			int leftOffset,
			int topOffset,
			T emptyValue
	) {
		Objects.requireNonNull(remainders, "remainders must not be null");
		if (fullGridSize <= 0 || fullGridWidth <= 0 || fullGridSize % fullGridWidth != 0) {
			throw new IllegalArgumentException("full crafting grid dimensions are invalid");
		}
		if (remainders.size() == fullGridSize) return List.copyOf(remainders);
		if (remainders.isEmpty() || trimmedWidth <= 0 || remainders.size() % trimmedWidth != 0
				|| leftOffset < 0 || topOffset < 0) {
			throw new IllegalArgumentException("trimmed crafting remainder layout is invalid");
		}
		int trimmedHeight = remainders.size() / trimmedWidth;
		int fullGridHeight = fullGridSize / fullGridWidth;
		if (leftOffset + trimmedWidth > fullGridWidth || topOffset + trimmedHeight > fullGridHeight) {
			throw new IllegalArgumentException("trimmed crafting remainder layout exceeds the full grid");
		}
		ArrayList<T> expanded = new ArrayList<>(java.util.Collections.nCopies(fullGridSize, emptyValue));
		for (int row = 0; row < trimmedHeight; row++) {
			for (int column = 0; column < trimmedWidth; column++) {
				expanded.set((row + topOffset) * fullGridWidth + column + leftOffset,
						remainders.get(row * trimmedWidth + column));
			}
		}
		return List.copyOf(expanded);
	}

	public TransactionPostcondition.Verdict verifyExactTransfer(
			TransactionSnapshot after,
			int sourceIndex,
			int destinationIndex,
			String expectedItemId,
			int count
	) {
		Objects.requireNonNull(after, "after must not be null");
		if (sourceIndex == destinationIndex || count <= 0) {
			return failed("INVALID_TRANSFER", "Source, destination, and count are invalid");
		}
		Map<Integer, SlotState> beforeByIndex = byIndex();
		Map<Integer, SlotState> afterByIndex = after.byIndex();
		if (!beforeByIndex.keySet().equals(afterByIndex.keySet())) {
			return failed("TRANSACTION_CONFLICT", "The menu slot set changed during the transaction");
		}
		SlotState sourceBefore = beforeByIndex.get(sourceIndex);
		SlotState sourceAfter = afterByIndex.get(sourceIndex);
		SlotState destinationBefore = beforeByIndex.get(destinationIndex);
		SlotState destinationAfter = afterByIndex.get(destinationIndex);
		if (sourceBefore == null || destinationBefore == null) {
			return failed("INVALID_SLOT", "Source or destination slot is not present");
		}
		if (!sourceBefore.itemId().equals(expectedItemId) || sourceBefore.count() < count) {
			return failed("SOURCE_MISMATCH", "Source item identity or count did not match");
		}
		if (!destinationBefore.empty()
				&& (!destinationBefore.itemId().equals(expectedItemId)
				|| !destinationBefore.fingerprint().equals(sourceBefore.fingerprint()))) {
			return failed("DESTINATION_MISMATCH", "Destination cannot merge the requested stack");
		}
		if (!matchesRemainder(sourceBefore, sourceAfter, sourceBefore.count() - count)
				|| !matchesRemainder(sourceBefore, destinationAfter, destinationBefore.count() + count)) {
			return failed("TRANSACTION_CONFLICT", "Requested debit and credit were not both observed");
		}
		for (int index : beforeByIndex.keySet()) {
			if (index != sourceIndex && index != destinationIndex
					&& !beforeByIndex.get(index).equals(afterByIndex.get(index))) {
				return failed("TRANSACTION_CONFLICT", "Unexpected slot " + index + " changed");
			}
		}
		if (totalCount(expectedItemId) != after.totalCount(expectedItemId)) {
			return failed("CONSERVATION_FAILED", "Transferred item count was not conserved");
		}
		return new TransactionPostcondition.Verdict.Succeeded("Exact debit and credit confirmed");
	}

	public RollbackPlan rollbackPlan(TransactionSnapshot current, int sourceIndex, int destinationIndex) {
		Objects.requireNonNull(current, "current must not be null");
		Map<Integer, SlotState> beforeByIndex = byIndex();
		Map<Integer, SlotState> currentByIndex = current.byIndex();
		if (!beforeByIndex.keySet().equals(currentByIndex.keySet())) {
			throw new IllegalStateException("slot set changed before rollback");
		}
		for (int index : beforeByIndex.keySet()) {
			if (index != sourceIndex && index != destinationIndex
					&& !beforeByIndex.get(index).equals(currentByIndex.get(index))) {
				throw new IllegalStateException("unrelated slot changed before rollback");
			}
		}
		SlotState sourceBefore = require(beforeByIndex, sourceIndex);
		SlotState sourceCurrent = require(currentByIndex, sourceIndex);
		SlotState destinationBefore = require(beforeByIndex, destinationIndex);
		SlotState destinationCurrent = require(currentByIndex, destinationIndex);
		int sourceCredit = sourceBefore.count() - sourceCurrent.count();
		int destinationDebit = destinationCurrent.count() - destinationBefore.count();
		if (sourceCredit < 0 || destinationDebit < 0 || destinationDebit > sourceCredit
				|| !compatible(sourceBefore, sourceCurrent) || !compatible(sourceBefore, destinationCurrent)) {
			throw new IllegalStateException("transaction state cannot be rolled back safely");
		}
		return new RollbackPlan(sourceIndex, destinationIndex, sourceCredit, destinationDebit, this);
	}

	private Map<Integer, SlotState> byIndex() {
		HashMap<Integer, SlotState> result = new HashMap<>();
		for (SlotState slot : slots) result.put(slot.index(), slot);
		return result;
	}

	private static SlotState require(Map<Integer, SlotState> slots, int index) {
		SlotState value = slots.get(index);
		if (value == null) throw new IllegalStateException("slot " + index + " is absent");
		return value;
	}

	private static boolean matchesRemainder(SlotState original, SlotState observed, int expectedCount) {
		if (expectedCount == 0) return observed.empty();
		return observed.count() == expectedCount && compatible(original, observed);
	}

	private static boolean compatible(SlotState expected, SlotState observed) {
		return observed.empty() || (expected.itemId().equals(observed.itemId())
				&& expected.fingerprint().equals(observed.fingerprint()));
	}

	private static TransactionPostcondition.Verdict.Failed failed(String reason, String message) {
		return new TransactionPostcondition.Verdict.Failed(reason, message);
	}

	public record SlotState(int index, String itemId, int count, String fingerprint) {
		public SlotState {
			if (index < 0) throw new IllegalArgumentException("slot index must not be negative");
			itemId = Objects.requireNonNull(itemId, "itemId must not be null");
			fingerprint = Objects.requireNonNull(fingerprint, "fingerprint must not be null");
			if (count < 0) throw new IllegalArgumentException("count must not be negative");
			if (count == 0) {
				itemId = "";
				fingerprint = "";
			} else if (itemId.isBlank()) {
				throw new IllegalArgumentException("non-empty slot needs an itemId");
			}
		}

		public boolean empty() {
			return count == 0;
		}
	}

	public record TransferAccounting(int requestedCount, int sourceDebit, int destinationCredit, int escrowCount) {
		public TransferAccounting {
			if (requestedCount <= 0 || sourceDebit < 0 || destinationCredit < 0 || escrowCount < 0) {
				throw new IllegalArgumentException("transfer accounting counts are invalid");
			}
		}

		public TransactionPostcondition.Verdict verifyOwnership() {
			if (sourceDebit != requestedCount || destinationCredit + escrowCount != sourceDebit) {
				return failed("OWNERSHIP_LOST", "Transferred stacks are not fully owned by destination or escrow");
			}
			return new TransactionPostcondition.Verdict.Succeeded("Transfer ownership is conserved");
		}

		public int rollbackSourceCredit(int recoveredDestinationCount) {
			if (recoveredDestinationCount < 0 || recoveredDestinationCount > destinationCredit) {
				throw new IllegalArgumentException("recovered destination count is invalid");
			}
			return escrowCount + recoveredDestinationCount;
		}
	}

	public record OwnedStack(String itemId, int count, String fingerprint) {
		public OwnedStack {
			if (itemId == null || itemId.isBlank()) throw new IllegalArgumentException("itemId must not be blank");
			if (count <= 0) throw new IllegalArgumentException("owned stack count must be positive");
			fingerprint = Objects.requireNonNull(fingerprint, "fingerprint must not be null");
		}
	}

	public record CraftingAccounting(Map<ItemIdentity, Integer> expectedAfter) {
		public CraftingAccounting {
			expectedAfter = Map.copyOf(expectedAfter);
		}

		public static CraftingAccounting plan(
				List<OwnedStack> ownedBefore,
				List<OwnedStack> consumed,
				List<OwnedStack> remainders,
				OwnedStack output
		) {
			HashMap<ItemIdentity, Integer> expected = aggregateOwned(ownedBefore);
			apply(expected, consumed, -1);
			apply(expected, remainders, 1);
			apply(expected, List.of(output), 1);
			if (expected.values().stream().anyMatch(count -> count < 0)) {
				throw new IllegalArgumentException("craft consumes more ownership than is present");
			}
			expected.entrySet().removeIf(entry -> entry.getValue() == 0);
			return new CraftingAccounting(expected);
		}

		public TransactionPostcondition.Verdict verify(List<OwnedStack> observedAfter) {
			if (!expectedAfter.equals(aggregateOwned(observedAfter))) {
				return failed("CRAFT_ACCOUNTING_FAILED", "Craft ingredients, remainders, and output were not exactly conserved");
			}
			return new TransactionPostcondition.Verdict.Succeeded("Craft ownership accounting is exact");
		}

		private static void apply(Map<ItemIdentity, Integer> target, List<OwnedStack> stacks, int sign) {
			for (OwnedStack stack : stacks) {
				ItemIdentity key = new ItemIdentity(stack.itemId(), stack.fingerprint());
				target.merge(key, sign * stack.count(), Integer::sum);
			}
		}
	}

	public static final class CraftPlacementGuard {
		private final Map<ItemIdentity, Integer> ownedBeforePlacement;
		private boolean placementAttempted;

		public CraftPlacementGuard(List<OwnedStack> ownedBeforePlacement) {
			this.ownedBeforePlacement = Map.copyOf(aggregateOwned(ownedBeforePlacement));
		}

		public void markPlacementAttempted() {
			placementAttempted = true;
		}

		public TransactionPostcondition.Verdict verifyPlacement(List<OwnedStack> observedAfterPlacement) {
			if (!placementAttempted) {
				return failed("CRAFT_PLACEMENT_NOT_ATTEMPTED", "Recipe placement was not marked as attempted");
			}
			if (!ownedBeforePlacement.equals(aggregateOwned(observedAfterPlacement))) {
				return failed("CRAFT_PLACEMENT_ACCOUNTING_FAILED",
						"Recipe placement changed total inventory or crafting-grid ownership");
			}
			return new TransactionPostcondition.Verdict.Succeeded("Recipe placement ownership is conserved");
		}

		public boolean mayReportCleanFailure(List<OwnedStack> observedCurrentOwnership) {
			return placementAttempted && ownedBeforePlacement.equals(aggregateOwned(observedCurrentOwnership));
		}
	}

	public record CraftPlacementMode(boolean useMaxItems, boolean allowDroppingItemsToClear) {
		public static CraftPlacementMode oneCraft(boolean creativePlayer) {
			return new CraftPlacementMode(false, creativePlayer);
		}
	}

	public record TransferRecoveryState(
			int sourceDebit,
			int destinationCredit,
			int reachableEscrowCount,
			int destinationRecoveryCount,
			boolean ownershipAccounted
	) {
		public static TransferRecoveryState observe(
				TransactionSnapshot before,
				TransactionSnapshot current,
				int sourceIndex,
				int destinationIndex,
				int reachableEscrowCount
		) {
			Objects.requireNonNull(before, "before must not be null");
			Objects.requireNonNull(current, "current must not be null");
			if (reachableEscrowCount < 0) throw new IllegalArgumentException("escrow count must not be negative");
			SlotState sourceBefore = require(before.byIndex(), sourceIndex);
			SlotState sourceCurrent = require(current.byIndex(), sourceIndex);
			SlotState destinationBefore = require(before.byIndex(), destinationIndex);
			SlotState destinationCurrent = require(current.byIndex(), destinationIndex);
			int sourceDebit = sourceBefore.count() - sourceCurrent.count();
			int destinationCredit = destinationCurrent.count() - destinationBefore.count();
			boolean compatibleState = compatible(sourceBefore, sourceCurrent)
					&& compatible(sourceBefore, destinationCurrent)
					&& (destinationBefore.empty() || compatible(sourceBefore, destinationBefore));
			boolean accounted = compatibleState
					&& sourceDebit >= 0
					&& destinationCredit >= 0
					&& destinationCredit <= sourceDebit
					&& destinationCredit + reachableEscrowCount == sourceDebit;
			int recoveryCount = destinationCredit > 0 && destinationCredit <= sourceDebit
					? destinationCredit : 0;
			return new TransferRecoveryState(
					sourceDebit, destinationCredit, reachableEscrowCount, recoveryCount, accounted);
		}
	}

	public static final class TransferMutationEscrow<T> {
		private final Predicate<T> reachablePredicate;
		private final List<T> reachable = new ArrayList<>();

		public TransferMutationEscrow(Predicate<T> reachablePredicate) {
			this.reachablePredicate = Objects.requireNonNull(
					reachablePredicate, "reachable predicate must not be null");
		}

		public T attempt(Supplier<T> mutation) {
			T result = Objects.requireNonNull(mutation, "mutation must not be null").get();
			retain(result);
			return result;
		}

		public void retain(T value) {
			if (!reachablePredicate.test(value)) return;
			for (T existing : reachable) {
				if (existing == value) return;
			}
			reachable.add(value);
		}

		public List<T> reachable() {
			return List.copyOf(reachable);
		}

		public void clear() {
			reachable.clear();
		}
	}

	private static HashMap<ItemIdentity, Integer> aggregateOwned(List<OwnedStack> stacks) {
		Objects.requireNonNull(stacks, "stacks must not be null");
		HashMap<ItemIdentity, Integer> result = new HashMap<>();
		for (OwnedStack stack : stacks) {
			Objects.requireNonNull(stack, "owned stack must not be null");
			ItemIdentity key = new ItemIdentity(stack.itemId(), stack.fingerprint());
			result.merge(key, stack.count(), Integer::sum);
		}
		return result;
	}

	public record ItemIdentity(String itemId, String fingerprint) {
		public ItemIdentity {
			Objects.requireNonNull(itemId, "itemId must not be null");
			Objects.requireNonNull(fingerprint, "fingerprint must not be null");
		}
	}

	public record RollbackPlan(
			int sourceIndex,
			int destinationIndex,
			int sourceCredit,
			int destinationDebit,
			TransactionSnapshot target
	) {
		public TransactionSnapshot apply(TransactionSnapshot current) {
			Map<Integer, SlotState> targetByIndex = target.byIndex();
			ArrayList<SlotState> restored = new ArrayList<>(current.slots());
			for (int index = 0; index < restored.size(); index++) {
				int slotIndex = restored.get(index).index();
				if (slotIndex == sourceIndex || slotIndex == destinationIndex) {
					restored.set(index, targetByIndex.get(slotIndex));
				}
			}
			return new TransactionSnapshot(restored);
		}
	}
}
