package dev.agaminggod.arenaagents.client.gui.scenario;

import dev.agaminggod.arenaagents.client.gui.AgentRosterGridLayout;

public final class ScenarioSetupLayoutVerification {
	private ScenarioSetupLayoutVerification() {
	}

	public static int verify() {
		int assertions = 0;
		for (int[] size : new int[][] {{960, 540}, {700, 360}, {320, 240}}) {
			ScenarioSetupLayout layout = ScenarioSetupLayout.calculate(size[0], size[1]);
			assertions += verifyNonOverlap(layout, size[0], size[1]);
			for (int count : new int[] {1, 8, 16}) {
				assertGrid(layout.rosterBounds(count >= 9, false), count,
						"browse " + size[0] + "x" + size[1] + " count " + count);
				assertGrid(layout.rosterBounds(count >= 9, true), count,
						"review " + size[0] + "x" + size[1] + " count " + count);
				assertions += 4;
			}
		}

		ScenarioSetupLayout medium = ScenarioSetupLayout.calculate(700, 360);
		ScenarioSetupLayout compact = ScenarioSetupLayout.calculate(320, 240);
		assertTrue(medium.compactRoster(), "700x360 separates lineup browse from editor");
		assertTrue(compact.compactRoster(), "320x240 separates lineup browse from editor");
		assertTrue(compact.showsRoster(false) && !compact.showsEditor(false),
				"compact browse shows only the roster");
		assertTrue(!compact.showsRoster(true) && compact.showsEditor(true),
				"compact editor hides the roster");
		AgentRosterGridLayout filtered = grid(compact.rosterBounds(true, false), 16);
		assertEquals(2, filtered.columns(), "compact filtered sixteen-agent lineup remains two-column");
		assertTrue(filtered.tileWidth() >= 24 && filtered.tileHeight() >= 24,
				"compact filtered tiles retain 24-pixel targets");
		assertTrue(filtered.hasPager() && filtered.pagerBounds().height() == 18,
				"compact filtered lineup retains valid pager geometry");
		assertTrue(compact.rosterPointerContains(true, true,
				compact.rosterBounds(true, true).left() + 1, compact.rosterBounds(true, true).top() + 1),
				"pointer containment includes the review roster");
		assertTrue(!compact.rosterPointerContains(true, false,
				compact.compactEditorBounds().left() + 1, compact.compactEditorBounds().top() + 1),
				"pointer containment excludes the compact inspector");
		assertTrue(!compact.rosterPointerContains(true, false,
				compact.contentLeft() + 1, compact.footerY() + 1),
				"pointer containment excludes the footer");
		return assertions + 9;
	}

	private static int verifyNonOverlap(ScenarioSetupLayout layout, int width, int height) {
		assertTrue(layout.panelLeft() >= 0 && layout.panelRight() <= width,
				"panel stays inside " + width + "x" + height);
		assertTrue(layout.panelTop() >= 0 && layout.panelBottom() <= height,
				"panel height stays inside " + width + "x" + height);
		assertTrue(!layout.rosterHeaderBounds().overlaps(layout.rosterBounds(false, false)),
				"roster header does not overlap browse grid");
		assertTrue(!layout.filterBounds(false).overlaps(layout.rosterBounds(true, false)),
				"filters do not overlap browse grid");
		assertTrue(!layout.rosterBounds(false, false).overlaps(layout.footerBounds()),
				"browse grid does not overlap footer");
		assertTrue(!layout.rosterBounds(true, true).overlaps(layout.footerBounds()),
				"review grid does not overlap footer");
		assertTrue(!layout.reviewControlBounds().overlaps(layout.rosterBounds(false, true)),
				"review controls do not overlap review grid");
		ScenarioSetupLayout.Bounds status = new ScenarioSetupLayout.Bounds(
				layout.contentLeft(), layout.statusTop(), layout.contentRight(), layout.statusBottom());
		assertTrue(!layout.rosterBounds(true, true).overlaps(status),
				"review grid does not overlap status geometry");
		assertTrue(!layout.rosterPointerContains(true, false,
				layout.editorBounds().left() + 1, layout.editorBounds().top() + 1),
				"roster pointer containment excludes the inspector");
		assertTrue(!layout.rosterPointerContains(true, false, layout.contentLeft() + 1, layout.footerY() + 1),
				"roster pointer containment excludes the footer");
		if (!layout.compactRoster()) {
			assertTrue(!layout.rosterBounds(true, false).overlaps(layout.editorBounds()),
					"wide roster grid does not overlap inspector");
		}
		return 11;
	}

	private static void assertGrid(ScenarioSetupLayout.Bounds bounds, int count, String label) {
		AgentRosterGridLayout layout = grid(bounds, count);
		assertTrue(layout.pageSize() >= 1, label + " has visible capacity");
		assertTrue(layout.columns() == 1 || layout.columns() == 2 || layout.columns() == 4,
				label + " has supported columns");
		assertTrue(layout.tileWidth() >= 24, label + " tile width is interactive");
		assertTrue(layout.tileHeight() >= 24, label + " tile height is interactive");
	}

	private static AgentRosterGridLayout grid(ScenarioSetupLayout.Bounds bounds, int count) {
		return AgentRosterGridLayout.calculate(bounds.left(), bounds.top(), bounds.right(), bounds.bottom(), count);
	}

	private static void assertTrue(boolean condition, String label) {
		if (!condition) throw new AssertionError(label);
	}

	private static void assertEquals(Object expected, Object actual, String label) {
		if (!java.util.Objects.equals(expected, actual)) {
			throw new AssertionError(label + ": expected=" + expected + ", actual=" + actual);
		}
	}
}
