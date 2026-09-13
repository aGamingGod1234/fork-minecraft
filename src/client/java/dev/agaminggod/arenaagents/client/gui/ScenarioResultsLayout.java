package dev.agaminggod.arenaagents.client.gui;

/** Responsive and scroll-safe geometry for terminal arena results. */
public record ScenarioResultsLayout(
		int panelLeft,
		int panelTop,
		int panelRight,
		int panelBottom,
		int gridLeft,
		int gridTop,
		int cardWidth,
		int columns,
		int visibleStandings,
		int detailsTop,
		int footerY
) {
	public static final int CARD_HEIGHT = 38;
	public static final int CARD_GAP = 6;
	private static final int OUTER_MARGIN = 8;
	private static final int MAXIMUM_WIDTH = 720;

	public static ScenarioResultsLayout calculate(int screenWidth, int screenHeight, int standings) {
		if (screenWidth < 320 || screenHeight < 240) {
			throw new IllegalArgumentException("Arena results require at least a 320x240 GUI");
		}
		if (standings < 0) throw new IllegalArgumentException("standings must not be negative");
		int panelWidth = Math.min(MAXIMUM_WIDTH, screenWidth - OUTER_MARGIN * 2);
		int left = (screenWidth - panelWidth) / 2;
		int top = OUTER_MARGIN;
		int right = left + panelWidth;
		int bottom = screenHeight - OUTER_MARGIN;
		int footerY = bottom - AgentControlLayout.CONTROL_HEIGHT - 7;
		int gridLeft = left + 14;
		int columns = panelWidth >= 600 ? 2 : 1;
		int gridWidth = panelWidth - 28;
		int cardWidth = (gridWidth - CARD_GAP * (columns - 1)) / columns;
		int gridTop = top + 54;
		int availableRows = Math.max(1, (footerY - gridTop - 70) / (CARD_HEIGHT + CARD_GAP));
		int visible = Math.min(standings, availableRows * columns);
		int usedRows = (visible + columns - 1) / columns;
		int overflowLabelHeight = standings > visible ? 14 : 0;
		int detailsTop = Math.min(footerY - 52,
				gridTop + usedRows * (CARD_HEIGHT + CARD_GAP) + overflowLabelHeight + 2);
		return new ScenarioResultsLayout(left, top, right, bottom, gridLeft, gridTop, cardWidth,
				columns, visible, detailsTop, footerY);
	}

	public int panelWidth() {
		return panelRight - panelLeft;
	}

	public int gridBottom() {
		if (visibleStandings == 0) return gridTop;
		int rows = (visibleStandings + columns - 1) / columns;
		return gridTop + (rows - 1) * (CARD_HEIGHT + CARD_GAP) + CARD_HEIGHT;
	}
}
