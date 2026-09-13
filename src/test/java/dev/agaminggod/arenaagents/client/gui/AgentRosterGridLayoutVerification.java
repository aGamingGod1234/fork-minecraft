package dev.agaminggod.arenaagents.client.gui;

public final class AgentRosterGridLayoutVerification {
	private AgentRosterGridLayoutVerification() {
	}

	public static int verify() {
		int assertions = 0;
		assertions += verifyEmptyAndSingleEntryGeometry();
		assertions += verifyColumnThresholdAndExactGaps();
		assertions += verifyRowsAndPagerReservation();
		assertions += verifyTargetScreenRegions();
		assertions += verifyContainmentAndValidation();
		assertions += verifyOverflowSafetyAndConstructionInvariants();
		return assertions;
	}

	private static int verifyEmptyAndSingleEntryGeometry() {
		AgentRosterGridLayout empty = AgentRosterGridLayout.calculate(10, 20, 310, 220, 0);
		assertEquals(0, empty.columns(), "empty roster has no columns");
		assertEquals(0, empty.rows(), "empty roster has no rows");
		assertEquals(0, empty.pageSize(), "empty roster has no page capacity");
		assertTrue(!empty.hasPager(), "empty roster does not reserve a pager");
		assertTrue(empty.gridBounds().isEmpty(), "empty roster publishes stable empty grid bounds");
		assertTrue(!empty.contains(10, 20), "empty roster has no interactive region");

		AgentRosterGridLayout single = AgentRosterGridLayout.calculate(0, 0, 500, 200, 1);
		assertEquals(1, single.columns(), "single roster uses one column");
		assertEquals(1, single.rows(), "single roster uses one row");
		assertEquals(1, single.pageSize(), "single roster exposes its only entry");
		assertEquals(240, single.tileWidth(), "single tile width is enlarged but concise");
		assertEquals(72, single.tileHeight(), "single tile height remains a concise enlarged target");
		assertEquals(new AgentRosterGridLayout.Bounds(130, 64, 370, 136), single.gridBounds(),
				"single tile is centered in the roster region");
		assertEquals(single.gridBounds(), single.tileBounds(0), "single tile consumes its active grid bounds");
		return 13;
	}

	private static int verifyColumnThresholdAndExactGaps() {
		AgentRosterGridLayout below = AgentRosterGridLayout.calculate(0, 0, 419, 180, 2);
		assertEquals(2, below.columns(), "419 pixel region uses two columns");
		assertEquals(206, below.tileWidth(), "two-column width is uniform at the threshold edge");
		assertEquals(418, below.gridBounds().width(),
				"integer-pixel two-column grid consumes its centered usable width exactly");
		assertEquals(64, below.tileHeight(), "multi-entry tile height stays below the card-like cap");
		assertEquals(58, below.gridBounds().top(), "capped two-column grid is vertically centered");
		assertEquals(6, below.tileBounds(1).left() - below.tileBounds(0).right(),
				"horizontal tile gap is exactly six pixels");

		AgentRosterGridLayout at = AgentRosterGridLayout.calculate(0, 0, 420, 48, 4);
		assertEquals(4, at.columns(), "420 pixel region uses four columns");
		assertEquals(100, at.tileWidth(), "four-column width is uniform at the threshold");
		assertEquals(418, at.gridBounds().width(),
				"integer-pixel four-column grid consumes its centered usable width exactly");
		assertEquals(6, at.tileBounds(1).left() - at.tileBounds(0).right(),
				"four-column horizontal gap is exactly six pixels");
		return 10;
	}

	private static int verifyRowsAndPagerReservation() {
		AgentRosterGridLayout eight = AgentRosterGridLayout.calculate(0, 0, 420, 102, 8);
		assertEquals(2, eight.rows(), "eight entries use two rows when two readable rows fit");
		assertEquals(8, eight.pageSize(), "eight entries fit without paging");
		assertTrue(!eight.hasPager(), "pager is not reserved when every entry fits");
		assertEquals(48, eight.tileHeight(), "two full rows retain the preferred height");
		assertEquals(6, eight.tileBounds(4).top() - eight.tileBounds(0).bottom(),
				"vertical tile gap is exactly six pixels");

		AgentRosterGridLayout sixteen = AgentRosterGridLayout.calculate(0, 0, 420, 210, 16);
		assertEquals(4, sixteen.rows(), "sixteen entries use at most four rows");
		assertEquals(16, sixteen.pageSize(), "sixteen entries fit in a four by four grid");
		assertTrue(!sixteen.hasPager(), "full four by four grid does not reserve a pager");
		assertEquals(48, sixteen.tileHeight(), "four rows retain the preferred height");

		AgentRosterGridLayout paged = AgentRosterGridLayout.calculate(0, 0, 420, 102, 9);
		assertTrue(paged.hasPager(), "pager is reserved when one more entry cannot fit");
		assertEquals(18, paged.pagerBounds().height(), "pager height is exactly eighteen pixels");
		assertEquals(7, paged.gridBounds().top(),
				"capped grid is centered in the region above the pager reserve");
		assertEquals(7, paged.pagerBounds().top() - 6 - paged.gridBounds().bottom(),
				"capped grid leaves balanced space before the six-pixel pager reserve");
		assertEquals(1, paged.rows(), "row capacity is recalculated after pager reservation");
		assertEquals(4, paged.pageSize(), "recalculated pager page exposes one readable row");

		AgentRosterGridLayout compact = AgentRosterGridLayout.calculate(0, 0, 419, 120, 8);
		assertTrue(compact.hasPager(), "compact overflowing roster reserves its pager");
		assertEquals(1, compact.rows(), "compact roster drops a row before shrinking below 48 pixels");
		assertEquals(64, compact.tileHeight(), "remaining compact row respects the concise tile-height cap");
		assertEquals(16, compact.gridBounds().top(),
				"compact active grid is vertically centered above its bottom-aligned pager");
		assertEquals(16, compact.pagerBounds().top() - 6 - compact.gridBounds().bottom(),
				"compact active grid keeps balanced breathing room above the pager reserve");
		assertEquals(2, compact.pageSize(), "compact page size follows the reduced readable row count");

		AgentRosterGridLayout many = AgentRosterGridLayout.calculate(0, 0, 420, 300, 80);
		assertEquals(4, many.rows(), "overflowing tall roster never exceeds four visible rows");
		assertEquals(16, many.pageSize(), "four-column roster page remains bounded to four rows");
		return 23;
	}

	private static int verifyTargetScreenRegions() {
		AgentControlLayout wideShell = AgentControlLayout.calculate(960, 540);
		AgentRosterGridLayout wide = fromCanvas(wideShell, 16);
		assertEquals(4, wide.columns(), "960 by 540 canvas uses four roster columns");
		assertEquals(4, wide.rows(), "960 by 540 canvas fits the full sixteen-entry roster");
		assertTrue(!wide.hasPager(), "960 by 540 canvas needs no pager for sixteen entries");
		assertTrue(wide.tileWidth() >= 24 && wide.tileHeight() >= 48,
				"960 by 540 tiles meet the readable target contract");

		AgentControlLayout mediumShell = AgentControlLayout.calculate(700, 360);
		AgentRosterGridLayout medium = fromCanvas(mediumShell, 16);
		assertEquals(4, medium.columns(), "700 by 360 canvas uses four roster columns");
		assertEquals(4, medium.rows(), "700 by 360 canvas fits four readable roster rows");
		assertTrue(!medium.hasPager(), "700 by 360 canvas needs no pager for sixteen entries");
		assertTrue(medium.tileWidth() >= 24 && medium.tileHeight() >= 48,
				"700 by 360 tiles meet the readable target contract");

		AgentControlLayout compactShell = AgentControlLayout.calculate(320, 240);
		AgentRosterGridLayout compact = fromCanvas(compactShell, 16);
		assertEquals(2, compact.columns(), "320 by 240 canvas switches to two roster columns");
		assertEquals(1, compact.rows(), "320 by 240 canvas favors one readable row with paging");
		assertTrue(compact.hasPager(), "320 by 240 canvas pages the sixteen-entry roster");
		assertTrue(compact.tileWidth() >= 24 && compact.tileHeight() >= 48,
				"320 by 240 tiles meet the readable target contract");
		return 12;
	}

	private static int verifyContainmentAndValidation() {
		AgentRosterGridLayout layout = AgentRosterGridLayout.calculate(10, 20, 430, 122, 9);
		AgentRosterGridLayout.Bounds first = layout.tileBounds(0);
		assertTrue(layout.contains(first.left(), first.top()), "grid contains its inclusive top-left edge");
		assertTrue(!layout.contains(layout.gridBounds().right(), first.top()),
				"grid excludes its right edge");
		assertTrue(layout.contains(layout.pagerBounds().left(), layout.pagerBounds().top()),
				"pager contains its inclusive top-left edge");
		assertTrue(!layout.contains(layout.gridBounds().left(), layout.gridBounds().bottom()),
				"gap above pager is not interactive");
		assertTrue(!layout.contains(layout.gridBounds().left() - 0.01, first.top()),
				"point immediately left of the active union is excluded");
		assertTrue(!layout.contains(layout.pagerBounds().right(), layout.pagerBounds().top()),
				"point immediately right of the active union is excluded");

		expectFailure(() -> layout.tileBounds(-1), "negative tile index");
		expectFailure(() -> layout.tileBounds(layout.pageSize()), "tile index at page size");
		expectFailure(() -> AgentRosterGridLayout.calculate(0, 0, 24, 24, -1), "negative entry count");
		expectFailure(() -> AgentRosterGridLayout.calculate(10, 0, 10, 24, 0), "zero-width region");
		expectFailure(() -> AgentRosterGridLayout.calculate(11, 0, 10, 24, 0), "inverted horizontal region");
		expectFailure(() -> AgentRosterGridLayout.calculate(0, 10, 24, 10, 0), "zero-height region");
		expectFailure(() -> AgentRosterGridLayout.calculate(0, 11, 24, 10, 0), "inverted vertical region");
		expectFailure(() -> AgentRosterGridLayout.calculate(0, 0, 23, 24, 1), "single target below width floor");
		expectFailure(() -> AgentRosterGridLayout.calculate(0, 0, 53, 48, 2), "two targets below width floor");
		expectFailure(() -> AgentRosterGridLayout.calculate(0, 0, 54, 47, 3),
				"overflowing roster without grid and pager target height");
		return 16;
	}

	private static int verifyOverflowSafetyAndConstructionInvariants() {
		AgentRosterGridLayout extreme = AgentRosterGridLayout.calculate(
				Integer.MIN_VALUE, Integer.MIN_VALUE, -1, -1, 16
		);
		assertEquals(4, extreme.rows(), "accepted extreme dimensions retain four readable rows");
		assertTrue(!extreme.hasPager(), "accepted extreme height does not overflow into a false pager");
		assertEquals(64, extreme.tileHeight(), "accepted extreme height still respects the concise tile cap");
		assertTrue(extreme.gridBounds().width() > 0, "accepted extreme width retains positive bounds");

		expectFailure(
				() -> new AgentRosterGridLayout.Bounds(Integer.MIN_VALUE, 0, Integer.MAX_VALUE, 1),
				"bounds with overflowing width"
		);
		expectFailure(
				() -> new AgentRosterGridLayout.Bounds(0, Integer.MIN_VALUE, 1, Integer.MAX_VALUE),
				"bounds with overflowing height"
		);

		AgentRosterGridLayout.Bounds empty = new AgentRosterGridLayout.Bounds(0, 0, 0, 0);
		AgentRosterGridLayout.Bounds emptyLine = new AgentRosterGridLayout.Bounds(0, 0, 10, 0);
		expectFailure(
				() -> new AgentRosterGridLayout(emptyLine, 0, 0, 0, 0, 0, empty, false),
				"empty roster with non-point grid bounds"
		);
		AgentRosterGridLayout validSingle = new AgentRosterGridLayout(
				new AgentRosterGridLayout.Bounds(0, 0, 240, 72), 1, 1, 240, 72, 1, empty, false
		);
		assertEquals(72, validSingle.tileHeight(), "single enlarged tile may use its distinct 72 pixel cap");
		expectFailure(
				() -> new AgentRosterGridLayout(
						validSingle.gridBounds(), 1, 1, 240, 72, 1, emptyLine, false
				),
				"inactive pager with non-point bounds"
		);
		expectFailure(
				() -> new AgentRosterGridLayout(
						new AgentRosterGridLayout.Bounds(0, 0, 240, 73), 1, 1, 240, 73, 1, empty, false
				),
				"single tile above its height cap"
		);
		expectFailure(
				() -> new AgentRosterGridLayout(
						new AgentRosterGridLayout.Bounds(0, 0, 241, 72), 1, 1, 241, 72, 1, empty, false
				),
				"single tile above its width cap"
		);
		expectFailure(
				() -> new AgentRosterGridLayout(
						new AgentRosterGridLayout.Bounds(0, 0, 54, 65), 2, 1, 24, 65, 2, empty, false
				),
				"multi-entry tile above its height cap"
		);

		AgentRosterGridLayout paged = AgentRosterGridLayout.calculate(0, 0, 420, 102, 9);
		expectFailure(
				() -> new AgentRosterGridLayout(
						paged.gridBounds(), paged.columns(), paged.rows(), paged.tileWidth(), paged.tileHeight(),
						paged.pageSize(),
						new AgentRosterGridLayout.Bounds(
								paged.pagerBounds().left() + 1, paged.pagerBounds().top(),
								paged.pagerBounds().right() + 1, paged.pagerBounds().bottom()
						),
						true
				),
				"pager shifted away from grid alignment"
		);
		expectFailure(
				() -> new AgentRosterGridLayout(
						paged.gridBounds(), paged.columns(), paged.rows(), paged.tileWidth(), paged.tileHeight(),
						paged.pageSize(),
						new AgentRosterGridLayout.Bounds(
								paged.gridBounds().left(), paged.gridBounds().bottom() + 5,
								paged.gridBounds().right(), paged.gridBounds().bottom() + 23
						),
						true
				),
				"pager below the reserved six pixel separation"
		);
		expectFailure(
				() -> new AgentRosterGridLayout(
						paged.gridBounds(), paged.columns(), paged.rows(), paged.tileWidth(), paged.tileHeight(),
						paged.pageSize(),
						new AgentRosterGridLayout.Bounds(
								paged.gridBounds().left(), paged.gridBounds().bottom() - 1,
								paged.gridBounds().right(), paged.gridBounds().bottom() + 17
						),
						true
				),
				"overlapping pager"
		);
		expectFailure(
				() -> new AgentRosterGridLayout(
						paged.gridBounds(), paged.columns(), paged.rows(), paged.tileWidth(), paged.tileHeight(),
						1, paged.pagerBounds(), true
				),
				"paged layout without a full page capacity"
		);

		AgentRosterGridLayout full = AgentRosterGridLayout.calculate(0, 0, 420, 210, 16);
		expectFailure(
				() -> new AgentRosterGridLayout(
						full.gridBounds(), full.columns(), full.rows(), full.tileWidth(), full.tileHeight(),
						4, full.pagerBounds(), false
				),
				"unpaged layout with excess empty rows"
		);
		expectFailure(
				() -> new AgentRosterGridLayout(
						new AgentRosterGridLayout.Bounds(0, 0, 84, 24), 3, 1, 24, 24, 3, empty, false
				),
				"unsupported three-column layout"
		);
		return 18;
	}

	private static AgentRosterGridLayout fromCanvas(AgentControlLayout shell, int totalEntries) {
		return AgentRosterGridLayout.calculate(
				shell.canvasLeft(), shell.contentTop(), shell.canvasRight(), shell.contentBottom(), totalEntries
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
