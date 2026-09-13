package dev.agaminggod.arenaagents.client.gui;

public final class LiveArenaLayoutVerification {
	private LiveArenaLayoutVerification() {
	}

	public static int verify() {
		AgentControlLayout wideShell = AgentControlLayout.calculate(960, 540);
		LiveArenaLayout wide = LiveArenaLayout.calculate(
				wideShell.contentWidth(), wideShell.contentHeight(), 8);
		assertTrue(wide.columns() == 2, "wide live arena uses two readable columns");
		assertTrue(wide.visibleCount() == 8, "wide live arena shows the full default roster");
		assertTrue(wide.maximumScroll() == 0, "wide default roster does not pretend to scroll");

		AgentControlLayout compactShell = AgentControlLayout.calculate(320, 240);
		LiveArenaLayout compact = LiveArenaLayout.calculate(
				compactShell.contentWidth(), compactShell.contentHeight(), 8);
		assertTrue(compact.columns() == 1, "compact live arena uses one column");
		assertTrue(compact.cardHeight() >= 42, "compact stat cards retain a readable health row");
		assertTrue(compact.visibleCount() >= 1 && compact.visibleCount() < 8,
				"compact live arena scrolls instead of drawing off-screen");
		assertTrue(compact.maximumScroll() == 8 - compact.visibleCount(),
				"compact scroll range reaches every agent");

		LiveFeedViewport recent = LiveFeedViewport.calculate(12, 60, 0);
		assertTrue(recent.visibleRows() == 3, "activity feed derives visible rows from available height");
		assertTrue(recent.maximumScroll() == 9, "long activity feed exposes every older event");
		assertTrue(recent.sourceIndex(12, 0) == 11, "activity feed starts with the newest event");
		LiveFeedViewport older = LiveFeedViewport.calculate(12, 60, recent.pageDown());
		assertTrue(older.sourceIndex(12, 0) == 8, "Page Down reaches older activity");
		assertTrue(older.pageUp() == 0, "Page Up returns to recent activity");

		ConsoleMutationState initial = ConsoleMutationState.initial(10L);
		assertTrue(initial.accepts(11L), "newer control snapshots advance console state");
		assertTrue(!initial.accepts(10L), "equal control snapshots cannot rebuild the console");
		ConsoleMutationState pending = initial.beginMutation(5L);
		assertTrue(pending.mutationPending(), "one submitted command disables further mutations");
		assertTrue(pending.beginMutation(6L).equals(pending), "duplicate submission does not replace the pending mutation");
		assertTrue(pending.accept(10L, 5L).equals(pending), "stale snapshots do not clear a pending mutation");
		ConsoleMutationState unrelated = pending.accept(11L, 0L);
		assertTrue(unrelated.mutationPending(), "an unrelated newer snapshot does not acknowledge the mutation");
		assertTrue(unrelated.acceptedRevision() == 11L, "unrelated snapshots still advance revision gating");
		assertTrue(unrelated.accept(12L, 4L).mutationPending(), "an older mutation receipt cannot clear newer work");
		assertTrue(!unrelated.accept(13L, 5L).mutationPending(), "the matching requested snapshot clears pending work");
		return 21;
	}

	private static void assertTrue(boolean condition, String label) {
		if (!condition) throw new AssertionError(label);
	}
}
