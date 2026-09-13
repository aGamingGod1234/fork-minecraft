package dev.agaminggod.arenaagents.control;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

public final class AgentRosterViewStateVerification {
	private AgentRosterViewStateVerification() {
	}

	public static int verify() {
		int assertions = 0;
		assertions += verifyEntryAndFilterNormalization();
		assertions += verifyFocusAndGridMovement();
		assertions += verifyRangeAndFilteredSelection();
		assertions += verifyPagingAtRosterBounds();
		assertions += verifyReconciliationAndSnapshots();
		assertions += verifyInvalidAuthoritativeRostersAreAtomic();
		assertions += verifyUnavailableSelectionReconciliation();
		return assertions;
	}

	private static int verifyEntryAndFilterNormalization() {
		AgentRosterEntry entry = new AgentRosterEntry(
				" agent-1 ", " Rook ", " Codex ", " Sol ", " Working ", true, null
		);

		assertEquals("agent-1", entry.id(), "entry IDs are canonicalized at the boundary");
		assertEquals("Rook", entry.name(), "entry names are trimmed without losing their display case");
		assertEquals("", entry.unavailableReason(), "missing unavailable reason becomes blank");
		assertTrue(AgentRosterFilter.all().matches(entry), "all filter includes every valid entry");
		assertTrue(new AgentRosterFilter("ook", "", "").matches(entry), "query searches display name");
		assertTrue(new AgentRosterFilter("CODEX", "", "").matches(entry), "query searches provider case-insensitively");
		assertTrue(new AgentRosterFilter("sol", "", "").matches(entry), "query searches model label");
		assertTrue(new AgentRosterFilter("", "codex", "working").matches(entry),
				"provider and state use exact canonical case-insensitive filters");
		assertTrue(!new AgentRosterFilter("", "code", "").matches(entry),
				"provider filter does not accept partial matches");
		expectFailure(
				() -> new AgentRosterEntry(" ", "Rook", "codex", "Sol", "Idle", true, ""),
				"blank roster ID"
		);
		return 10;
	}

	private static int verifyFocusAndGridMovement() {
		AgentRosterViewState state = new AgentRosterViewState();
		state.reconcile(entries(8, Set.of("agent-6")));

		assertEquals("agent-1", state.focusedId(), "first reconciled entry receives focus");
		state.focus("agent-2");
		state.toggle("agent-4");
		assertEquals("agent-2", state.focusedId(), "toggle does not move focus");
		assertEquals(List.of("agent-4"), List.copyOf(state.selectedIds()), "selection is explicit and ordered");
		state.focus("agent-4");
		state.moveFocus(4, 0, 1);
		assertEquals("agent-4", state.focusedId(), "four-column movement clamps at the row edge");
		state.moveFocus(4, 1, 0);
		assertEquals("agent-8", state.focusedId(), "four-column movement advances by one row");
		state.moveFocus(4, 0, -1);
		assertEquals("agent-7", state.focusedId(), "four-column movement moves within a row");
		state.focus("agent-2");
		state.moveFocus(2, 1, 0);
		assertEquals("agent-4", state.focusedId(), "two-column movement advances by one row");
		state.moveFocus(2, -1, 0);
		assertEquals("agent-2", state.focusedId(), "two-column movement returns to the prior row");
		state.focus("agent-6");
		assertEquals("agent-6", state.focusedId(), "focus may inspect an unavailable entry");
		state.toggle("agent-6");
		assertEquals(List.of("agent-4"), List.copyOf(state.selectedIds()), "unavailable entry cannot be toggled on");
		expectFailure(() -> state.moveFocus(0, 1, 0), "non-positive grid columns");
		return 11;
	}

	private static int verifyRangeAndFilteredSelection() {
		AgentRosterViewState state = new AgentRosterViewState();
		state.reconcile(entries(8, Set.of("agent-3")));
		state.toggle("agent-2");
		state.selectRangeTo("agent-5");

		assertEquals(List.of("agent-2", "agent-4", "agent-5"), List.copyOf(state.selectedIds()),
				"range is additive and skips unavailable entries");
		state.selectRangeTo("agent-7");
		assertEquals(List.of("agent-2", "agent-4", "agent-5", "agent-6", "agent-7"),
				List.copyOf(state.selectedIds()), "range endpoint becomes the next anchor");
		state.setFilter(new AgentRosterFilter("", "gemini", ""));
		AgentRosterPage filtered = state.page(8);
		assertEquals(List.of("agent-2", "agent-4", "agent-6", "agent-8"),
				filtered.entries().stream().map(AgentRosterEntry::id).toList(), "provider filter preserves row-major order");
		assertEquals(2, filtered.hiddenSelectedCount(), "page reports valid selections hidden by the filter");
		state.selectAll();
		assertEquals(List.of("agent-2", "agent-4", "agent-5", "agent-6", "agent-7", "agent-8"),
				List.copyOf(state.selectedIds()), "select all adds only the filtered selectable result set");
		state.clearSelection();
		state.selectRangeTo("agent-4");
		assertEquals(List.of("agent-4"), List.copyOf(state.selectedIds()),
				"clearing selection also resets the previous range anchor");
		return 6;
	}

	private static int verifyPagingAtRosterBounds() {
		AgentRosterViewState state = new AgentRosterViewState();
		state.reconcile(entries(16, Set.of()));

		AgentRosterPage all = state.page(16);
		assertEquals(List.of(1, 16, 16, 1, 1),
				List.of(all.firstIndex(), all.lastIndex(), all.totalFiltered(), all.page(), all.pageCount()),
				"sixteen entries fit one full page with user-facing indexes");
		state.setPage(2, 8);
		AgentRosterPage second = state.page(8);
		assertEquals(List.of("agent-9", "agent-10", "agent-11", "agent-12", "agent-13", "agent-14", "agent-15", "agent-16"),
				second.entries().stream().map(AgentRosterEntry::id).toList(), "eight-entry page exposes the second half");
		assertEquals(List.of(9, 16, 2, 2),
				List.of(second.firstIndex(), second.lastIndex(), second.page(), second.pageCount()),
				"second page has inclusive filtered indexes");
		state.nextPage(8);
		assertEquals(2, state.page(8).page(), "next page clamps at the final page");
		state.previousPage(8);
		assertEquals(1, state.page(8).page(), "previous page moves within bounds");
		state.setPage(99, 1);
		AgentRosterPage last = state.page(1);
		assertEquals(List.of("agent-16"), last.entries().stream().map(AgentRosterEntry::id).toList(),
				"one-entry paging clamps an oversized page request");
		assertEquals(16, last.page(), "one-entry paging reaches page sixteen");
		state.setFilter(new AgentRosterFilter("does-not-exist", "", ""));
		AgentRosterPage empty = state.page(1);
		assertEquals(List.of(0, 0, 0, 1, 1),
				List.of(empty.firstIndex(), empty.lastIndex(), empty.totalFiltered(), empty.page(), empty.pageCount()),
				"empty filters retain one bounded page and zero result indexes");
		expectUnsupported(() -> second.entries().clear(), "page entries are immutable");
		expectFailure(() -> state.page(0), "non-positive page size");
		return 10;
	}

	private static int verifyReconciliationAndSnapshots() {
		AgentRosterViewState state = new AgentRosterViewState();
		ArrayList<AgentRosterEntry> mutableRoster = new ArrayList<>(entries(4, Set.of()));
		state.reconcile(mutableRoster);
		state.focus("agent-1");
		state.toggle("agent-1");
		state.toggle("agent-2");
		state.setFilter(new AgentRosterFilter("", "", "working"));
		mutableRoster.clear();
		assertEquals(2, state.page(8).totalFiltered(), "caller mutation cannot change reconciled roster state");

		state.reconcile(List.of(
				entry(2, true),
				entry(3, true),
				entry(4, true)
		));
		assertEquals("agent-2", state.focusedId(), "removed focus falls back to first current filtered entry");
		assertEquals(List.of("agent-2"), List.copyOf(state.selectedIds()),
				"reconcile removes selected IDs absent from the authoritative roster");
		Set<String> selectedSnapshot = state.selectedIds();
		expectUnsupported(() -> selectedSnapshot.add("agent-3"), "selected IDs are immutable");
		state.reconcile(List.of(entry(1, false), entry(3, false)));
		assertEquals("agent-1", state.focusedId(),
				"focus falls back to the authoritative roster when the filter has no results");
		state.reconcile(List.of());
		assertEquals("", state.focusedId(), "empty authoritative roster clears focus");
		assertEquals(Set.of(), state.selectedIds(), "empty authoritative roster clears selection");
		return 7;
	}

	private static int verifyUnavailableSelectionReconciliation() {
		AgentRosterViewState state = new AgentRosterViewState();
		state.reconcile(List.of(entry(1, false), entry(2, true)));
		state.focus("agent-2");
		state.toggle("agent-2");
		assertEquals(List.of("agent-2"), List.copyOf(state.selectedIds()),
				"available entry can enter explicit scope");

		state.reconcile(List.of(entry(1, false), unavailableEntry(2)));
		assertEquals(Set.of(), state.selectedIds(),
				"entry becoming unavailable is pruned from explicit scope");
		assertEquals("agent-2", state.focusedId(),
				"unavailable focused entry remains inspectable after reconciliation");
		assertEquals(0, state.page(8).hiddenSelectedCount(),
				"pruned unavailable entry is not retained as hidden selection");
		state.toggle("agent-2");
		assertEquals(Set.of(), state.selectedIds(),
				"unavailable entry cannot be restored to explicit scope by toggling");
		return 5;
	}

	private static int verifyInvalidAuthoritativeRostersAreAtomic() {
		AgentRosterViewState state = new AgentRosterViewState();
		state.reconcile(entries(3, Set.of()));
		state.focus("agent-2");
		state.toggle("agent-2");
		AgentRosterPage before = state.page(8);
		AgentRosterEntry duplicate = new AgentRosterEntry(
				"agent-1", "Duplicate", "kimi", "K3", "Idle", true, ""
		);

		expectFailure(() -> state.reconcile(List.of(entry(1, false), duplicate)),
				"duplicate authoritative roster ID");
		assertEquals(before, state.page(8), "duplicate rejection preserves the previous roster page");
		assertEquals("agent-2", state.focusedId(), "duplicate rejection preserves focus");
		assertEquals(List.of("agent-2"), List.copyOf(state.selectedIds()),
				"duplicate rejection preserves explicit selection");

		ArrayList<AgentRosterEntry> rosterWithNull = new ArrayList<>();
		rosterWithNull.add(entry(1, false));
		rosterWithNull.add(null);
		expectNullFailure(() -> state.reconcile(rosterWithNull), "null authoritative roster entry");
		assertEquals(before, state.page(8), "null-entry rejection preserves the previous roster page");
		assertEquals("agent-2", state.focusedId(), "null-entry rejection preserves focus");
		assertEquals(List.of("agent-2"), List.copyOf(state.selectedIds()),
				"null-entry rejection preserves explicit selection");

		expectNullFailure(() -> state.reconcile(null), "null authoritative roster");
		assertEquals(before, state.page(8), "null-list rejection preserves the previous roster page");
		assertEquals("agent-2", state.focusedId(), "null-list rejection preserves focus");
		assertEquals(List.of("agent-2"), List.copyOf(state.selectedIds()),
				"null-list rejection preserves explicit selection");
		return 12;
	}

	private static List<AgentRosterEntry> entries(int count, Set<String> unavailableIds) {
		ArrayList<AgentRosterEntry> entries = new ArrayList<>();
		for (int index = 1; index <= count; index++) {
			AgentRosterEntry entry = entry(index, index % 2 == 0);
			if (unavailableIds.contains(entry.id())) {
				entry = new AgentRosterEntry(
						entry.id(), entry.name(), entry.provider(), entry.modelLabel(), entry.state(), false, "Disconnected"
				);
			}
			entries.add(entry);
		}
		return List.copyOf(entries);
	}

	private static AgentRosterEntry entry(int index, boolean working) {
		return new AgentRosterEntry(
				"agent-" + index,
				"Agent " + index,
				index % 2 == 0 ? "gemini" : "codex",
				index % 2 == 0 ? "3.1 Pro" : "Sol",
				working ? "Working" : "Idle",
				true,
				""
		);
	}

	private static AgentRosterEntry unavailableEntry(int index) {
		AgentRosterEntry entry = entry(index, false);
		return new AgentRosterEntry(
				entry.id(), entry.name(), entry.provider(), entry.modelLabel(), entry.state(), false, "Disconnected"
		);
	}

	private static void expectFailure(Runnable operation, String label) {
		try {
			operation.run();
			throw new AssertionError(label + " should fail");
		} catch (IllegalArgumentException expected) {
			// Expected.
		}
	}

	private static void expectUnsupported(Runnable operation, String label) {
		try {
			operation.run();
			throw new AssertionError(label + " should fail");
		} catch (UnsupportedOperationException expected) {
			// Expected.
		}
	}

	private static void expectNullFailure(Runnable operation, String label) {
		try {
			operation.run();
			throw new AssertionError(label + " should fail");
		} catch (NullPointerException expected) {
			// Expected.
		}
	}

	private static void assertTrue(boolean actual, String label) {
		if (!actual) {
			throw new AssertionError(label);
		}
	}

	private static void assertEquals(Object expected, Object actual, String label) {
		if (!java.util.Objects.equals(expected, actual)) {
			throw new AssertionError(label + ": expected=" + expected + ", actual=" + actual);
		}
	}
}
