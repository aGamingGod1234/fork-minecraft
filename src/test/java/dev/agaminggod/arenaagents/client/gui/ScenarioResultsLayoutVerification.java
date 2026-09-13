package dev.agaminggod.arenaagents.client.gui;

public final class ScenarioResultsLayoutVerification {
	private ScenarioResultsLayoutVerification() {
	}

	public static int verify() {
		ScenarioResultsLayout wide = ScenarioResultsLayout.calculate(960, 540, 8);
		assertTrue(wide.columns() == 2, "wide results use two readable columns");
		assertTrue(wide.visibleStandings() == 8, "wide results show all eight standings");
		assertTrue(wide.detailsTop() < wide.footerY(), "run record stays above the footer");

		ScenarioResultsLayout compact = ScenarioResultsLayout.calculate(320, 240, 8);
		assertTrue(compact.columns() == 1, "compact results use one column");
		assertTrue(compact.visibleStandings() >= 1 && compact.visibleStandings() < 8,
				"compact results scroll instead of overflowing");
		assertTrue(compact.footerY() + 24 <= compact.panelBottom(), "compact result action stays on screen");
		assertTrue(compact.cardWidth() >= 260, "compact standing cards remain readable");
		assertTrue(compact.detailsTop() + 48 <= compact.footerY(),
				"compact run record reserves four readable lines above the footer");
		assertTrue(compact.detailsTop() - 11 >= compact.gridBottom() + 4,
				"scroll range label stays clear of the last standing card");
		return 9;
	}

	private static void assertTrue(boolean condition, String label) {
		if (!condition) throw new AssertionError(label);
	}
}
