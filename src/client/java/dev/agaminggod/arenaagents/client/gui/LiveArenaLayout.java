package dev.agaminggod.arenaagents.client.gui;

/** Responsive, scroll-safe geometry for the detailed live-agent dashboard. */
public record LiveArenaLayout(
		int columns,
		int cardWidth,
		int cardHeight,
		int gridTopOffset,
		int visibleCount,
		int maximumScroll
) {
	public static final int COLUMN_GAP = 8;
	public static final int ROW_GAP = 7;
	private static final int GRID_TOP_OFFSET = 44;

	public static LiveArenaLayout calculate(int contentWidth, int contentHeight, int participantCount) {
		if (contentWidth < 200 || contentHeight < 80) {
			throw new IllegalArgumentException("live arena content region is too small");
		}
		if (participantCount < 0) throw new IllegalArgumentException("participantCount must not be negative");
		int columns = contentWidth >= 560 ? 2 : 1;
		int cardWidth = (contentWidth - COLUMN_GAP * (columns - 1)) / columns;
		int cardHeight = contentHeight < 160 ? 42 : 50;
		int rows = Math.max(1, (contentHeight - GRID_TOP_OFFSET) / (cardHeight + ROW_GAP));
		int visible = Math.min(participantCount, rows * columns);
		return new LiveArenaLayout(columns, cardWidth, cardHeight, GRID_TOP_OFFSET,
				visible, Math.max(0, participantCount - visible));
	}
}
