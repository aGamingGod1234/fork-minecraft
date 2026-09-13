package dev.agaminggod.arenaagents.client.gui.scenario;

import dev.agaminggod.arenaagents.client.gui.AgentControlLayout;

/** Arena wizard geometry built on the same responsive field-console shell as agent control. */
public record ScenarioSetupLayout(
		int panelLeft,
		int panelTop,
		int panelRight,
		int panelBottom,
		int navigationTop,
		int contentLeft,
		int contentRight,
		int stepRailBottom,
		int contentTop,
		int contentBottom,
		int statusTop,
		int statusBottom,
		int footerY,
		boolean sideNavigation,
		boolean wideRoster
) {
	public static final int ROW_HEIGHT = AgentControlLayout.CONTROL_HEIGHT;
	private static final int STEP_RAIL_HEIGHT = 20;
	private static final int COMPACT_STEP_RAIL_HEIGHT = 14;
	private static final int GAP = 5;
	private static final int WIDE_REGION_GAP = 12;
	private static final int COMPACT_ROSTER_MAXIMUM_WIDTH = 414;

	public static ScenarioSetupLayout calculate(int screenWidth, int screenHeight) {
		AgentControlLayout shell = AgentControlLayout.calculate(screenWidth, screenHeight);
		boolean compact = !shell.sideNavigation();
		int stepRailTop = compact ? shell.navigationBottom() + 3 : shell.contentTop();
		int stepRailBottom = stepRailTop + (compact ? COMPACT_STEP_RAIL_HEIGHT : STEP_RAIL_HEIGHT);
		int contentTop = stepRailBottom + (compact ? 2 : 7);
		int statusBottom = shell.footerY() - 3;
		int statusTop = compact ? statusBottom : statusBottom - 10;
		int contentBottom = compact ? shell.footerY() - 3 : statusTop - 2;
		return new ScenarioSetupLayout(
				shell.panelLeft(), shell.panelTop(), shell.panelRight(), shell.panelBottom(),
				shell.navigationTop(), shell.contentLeft(), shell.contentRight(), stepRailBottom,
				contentTop, contentBottom, statusTop, statusBottom, shell.footerY(),
				shell.sideNavigation(), shell.contentWidth() >= 560
		);
	}

	public int panelWidth() {
		return panelRight - panelLeft;
	}

	public int contentWidth() {
		return contentRight - contentLeft;
	}

	public int contentHeight() {
		return contentBottom - contentTop;
	}

	/** Compact screens replace the large 2x2 scenario cards with one concise selector row. */
	public boolean compactArenaPicker() {
		return !sideNavigation || contentHeight() < 120;
	}

	public int footerButtonWidth() {
		return Math.min(112, Math.max(72, (contentWidth() - 10) / 3));
	}

	public boolean compactRoster() {
		return !wideRoster;
	}

	public boolean showsRoster(boolean compactEditorOpen) {
		return !compactRoster() || !compactEditorOpen;
	}

	public boolean showsEditor(boolean compactEditorOpen) {
		return !compactRoster() || compactEditorOpen;
	}

	public Bounds rosterHeaderBounds() {
		Horizontal roster = rosterHorizontal(false);
		return new Bounds(roster.left(), contentTop, roster.right(), contentTop + ROW_HEIGHT);
	}

	public Bounds filterBounds(boolean review) {
		Bounds anchor = review ? reviewControlBounds() : rosterHeaderBounds();
		Horizontal horizontal = rosterHorizontal(review);
		int top = anchor.bottom() + GAP;
		return new Bounds(horizontal.left(), top, horizontal.right(), top + ROW_HEIGHT);
	}

	public Bounds rosterBounds(boolean filtersVisible, boolean review) {
		Bounds anchor = filtersVisible ? filterBounds(review)
				: review ? reviewControlBounds() : rosterHeaderBounds();
		Horizontal horizontal = rosterHorizontal(review);
		return new Bounds(horizontal.left(), anchor.bottom() + GAP,
				horizontal.right(), contentBottom);
	}

	public Bounds editorBounds() {
		if (compactRoster()) return compactEditorBounds();
		Horizontal roster = rosterHorizontal(false);
		return new Bounds(roster.right() + WIDE_REGION_GAP, contentTop, contentRight, contentBottom);
	}

	public Bounds compactEditorBounds() {
		return new Bounds(contentLeft, contentTop, contentRight, contentBottom);
	}

	public Bounds reviewControlBounds() {
		Horizontal horizontal = rosterHorizontal(true);
		return new Bounds(horizontal.left(), contentTop, horizontal.right(), contentTop + ROW_HEIGHT);
	}

	public Bounds footerBounds() {
		return new Bounds(contentLeft, footerY, contentRight, footerY + ROW_HEIGHT);
	}

	public boolean rosterPointerContains(boolean filtersVisible, boolean review, double x, double y) {
		return rosterBounds(filtersVisible, review).contains(x, y);
	}

	private Horizontal rosterHorizontal(boolean review) {
		if (!wideRoster) {
			int width = Math.min(COMPACT_ROSTER_MAXIMUM_WIDTH, contentWidth());
			int left = contentLeft + (contentWidth() - width) / 2;
			return new Horizontal(left, left + width);
		}
		if (review) return new Horizontal(contentLeft, contentRight);
		int rosterWidth = (contentWidth() - WIDE_REGION_GAP) * 3 / 5;
		return new Horizontal(contentLeft, contentLeft + rosterWidth);
	}

	private record Horizontal(int left, int right) {
	}

	public record Bounds(int left, int top, int right, int bottom) {
		public Bounds {
			if (right < left || bottom < top) throw new IllegalArgumentException("Bounds cannot be inverted");
		}

		public int width() {
			return right - left;
		}

		public int height() {
			return bottom - top;
		}

		public boolean overlaps(Bounds other) {
			return left < other.right && right > other.left && top < other.bottom && bottom > other.top;
		}

		public boolean contains(double x, double y) {
			return x >= left && x < right && y >= top && y < bottom;
		}
	}
}
