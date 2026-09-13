package dev.agaminggod.arenaagents.client.gui;

import dev.agaminggod.arenaagents.control.AgentRosterFilter;
import java.util.Objects;

/** Responsive geometry for the field console, independent from Minecraft rendering state. */
public record AgentControlLayout(
		int panelLeft,
		int panelTop,
		int panelRight,
		int panelBottom,
		int navigationTop,
		int navigationBottom,
		int contentLeft,
		int contentTop,
		int contentRight,
		int contentBottom,
		int canvasLeft,
		int canvasRight,
		int contextLeft,
		int contextRight,
		int footerY,
		boolean sideNavigation,
		boolean splitWorkspace
) {
	public static final int CONTROL_HEIGHT = 24;
	private static final int MINIMUM_WIDTH = 320;
	private static final int MINIMUM_HEIGHT = 240;
	private static final int OUTER_MARGIN = 8;
	private static final int INNER_MARGIN = 14;
	private static final int MAXIMUM_PANEL_WIDTH = 920;
	private static final int SIDE_NAVIGATION_BREAKPOINT = 680;
	private static final int SIDE_NAVIGATION_WIDTH = 132;
	private static final int CONTEXT_RAIL_BREAKPOINT = 700;
	private static final int CONTEXT_RAIL_WIDTH = 208;
	private static final int REGION_GAP = 12;
	private static final int ROSTER_FILTER_HEIGHT = 30;
	private static final int WORKSPACE_ACTION_GAP = 6;
	private static final int GROUP_INLINE_TOOLBAR_HEIGHT = 157;
	private static final int GROUP_COLUMN_HEIGHT = 245;

	public static AgentControlLayout calculate(int screenWidth, int screenHeight) {
		if (screenWidth < MINIMUM_WIDTH || screenHeight < MINIMUM_HEIGHT) {
			throw new IllegalArgumentException("Agent control requires at least a 320x240 GUI");
		}
		int panelWidth = Math.min(MAXIMUM_PANEL_WIDTH, screenWidth - OUTER_MARGIN * 2);
		int panelLeft = (screenWidth - panelWidth) / 2;
		int verticalMargin = screenHeight <= 260 ? 4 : OUTER_MARGIN;
		int panelTop = verticalMargin;
		int panelRight = panelLeft + panelWidth;
		int panelBottom = screenHeight - verticalMargin;
		boolean sideNavigation = panelWidth >= SIDE_NAVIGATION_BREAKPOINT;
		int navigationTop = sideNavigation ? panelTop + 65 : panelTop + 45;
		int navigationBottom = sideNavigation ? panelBottom - 66 : navigationTop + CONTROL_HEIGHT;
		int contentLeft = panelLeft + INNER_MARGIN + (sideNavigation ? SIDE_NAVIGATION_WIDTH : 0);
		int contentRight = panelRight - INNER_MARGIN;
		int contentTop = sideNavigation ? panelTop + 54 : navigationBottom + 10;
		int footerY = panelBottom - CONTROL_HEIGHT - 7;
		int contentBottom = footerY - 10;
		int contentWidth = contentRight - contentLeft;
		boolean splitWorkspace = contentWidth >= CONTEXT_RAIL_BREAKPOINT;
		int contextLeft = splitWorkspace ? contentRight - CONTEXT_RAIL_WIDTH : contentRight;
		int canvasRight = splitWorkspace ? contextLeft - REGION_GAP : contentRight;
		return new AgentControlLayout(
				panelLeft, panelTop, panelRight, panelBottom,
				navigationTop, navigationBottom,
				contentLeft, contentTop, contentRight, contentBottom,
				contentLeft, canvasRight, contextLeft, contentRight,
				footerY, sideNavigation, splitWorkspace
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

	public int canvasWidth() {
		return canvasRight - canvasLeft;
	}

	public int contextWidth() {
		return splitWorkspace ? contextRight - contextLeft : 0;
	}

	public int minimumTargetHeight() {
		return CONTROL_HEIGHT;
	}

	public boolean groupToolbarInFooter() {
		return contentHeight() < GROUP_INLINE_TOOLBAR_HEIGHT;
	}

	public boolean groupColumns() {
		return (splitWorkspace || contentWidth() >= 500) && contentHeight() >= GROUP_COLUMN_HEIGHT;
	}

	public static boolean rosterFiltersVisible(int authoritativeCount) {
		if (authoritativeCount < 0) {
			throw new IllegalArgumentException("Authoritative roster count cannot be negative");
		}
		return authoritativeCount >= 9;
	}

	public static int rosterPageAfterFilterUpdate(
			AgentRosterFilter currentFilter,
			AgentRosterFilter nextFilter,
			int currentPage
	) {
		Objects.requireNonNull(currentFilter, "current filter must not be null");
		Objects.requireNonNull(nextFilter, "next filter must not be null");
		if (currentPage < 1) throw new IllegalArgumentException("Roster page must be positive");
		return currentFilter.equals(nextFilter) ? currentPage : 1;
	}

	public static String groupScopeLabel(int selectedCount, int hiddenSelectedCount) {
		if (selectedCount < 0 || hiddenSelectedCount < 0 || hiddenSelectedCount > selectedCount) {
			throw new IllegalArgumentException("Group scope counts are invalid");
		}
		String selected = selectedCount + " selected";
		return hiddenSelectedCount == 0
				? selected
				: selected + " \u00b7 " + hiddenSelectedCount + " hidden";
	}

	public Bounds groupScopeBounds() {
		if (sideNavigation) {
			return new Bounds(contentLeft, Math.max(panelTop, contentTop - 14), contentRight, contentTop);
		}
		return new Bounds(contentLeft, panelTop + 19, contentRight, Math.min(navigationTop, panelTop + 34));
	}

	public Bounds rosterBounds(boolean filtersVisible, boolean reserveActionRow) {
		int top = contentTop + (filtersVisible ? ROSTER_FILTER_HEIGHT : 0);
		int bottom = contentBottom - (reserveActionRow ? CONTROL_HEIGHT + WORKSPACE_ACTION_GAP : 0);
		return new Bounds(canvasLeft, top, canvasRight, bottom);
	}

	public Bounds composerBounds() {
		return new Bounds(contentLeft, contentTop, contentRight, workspaceActionBounds().top);
	}

	public Bounds workspaceActionBounds() {
		return new Bounds(contentLeft, contentBottom - CONTROL_HEIGHT, contentRight, contentBottom);
	}

	public record Bounds(int left, int top, int right, int bottom) {
		public Bounds {
			if (right < left || bottom < top) throw new IllegalArgumentException("Layout bounds cannot be inverted");
		}

		public int width() {
			return right - left;
		}

		public int height() {
			return bottom - top;
		}
	}
}
