package dev.agaminggod.arenaagents.client.gui;

/** Scroll geometry for the newest-first activity feed in the live arena page. */
public record LiveFeedViewport(int visibleRows, int scroll, int maximumScroll) {
	private static final int ROW_HEIGHT = 20;

	public static LiveFeedViewport calculate(int eventCount, int availableHeight, int requestedScroll) {
		if (eventCount < 0) throw new IllegalArgumentException("eventCount must not be negative");
		int visibleRows = Math.max(1, availableHeight / ROW_HEIGHT);
		int maximumScroll = Math.max(0, eventCount - visibleRows);
		return new LiveFeedViewport(visibleRows, Math.clamp(requestedScroll, 0, maximumScroll), maximumScroll);
	}

	public int sourceIndex(int eventCount, int visibleIndex) {
		if (visibleIndex < 0 || visibleIndex >= visibleRows) {
			throw new IllegalArgumentException("visibleIndex is outside the viewport");
		}
		int sourceIndex = eventCount - 1 - scroll - visibleIndex;
		if (sourceIndex < 0 || sourceIndex >= eventCount) {
			throw new IllegalArgumentException("visibleIndex has no event");
		}
		return sourceIndex;
	}

	public int scrollBy(int rows) {
		return Math.clamp(scroll + rows, 0, maximumScroll);
	}

	public int pageUp() {
		return scrollBy(-visibleRows);
	}

	public int pageDown() {
		return scrollBy(visibleRows);
	}
}
