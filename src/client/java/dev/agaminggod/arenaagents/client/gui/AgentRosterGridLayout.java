package dev.agaminggod.arenaagents.client.gui;

import java.util.Objects;

/** Pure responsive geometry for the shared agent roster grid. */
public record AgentRosterGridLayout(
		Bounds gridBounds,
		int columns,
		int rows,
		int tileWidth,
		int tileHeight,
		int pageSize,
		Bounds pagerBounds,
		boolean hasPager
) {
	public static final int TILE_GAP = 6;
	public static final int PAGER_HEIGHT = 18;
	private static final int PAGER_GAP = 6;
	private static final int FOUR_COLUMN_THRESHOLD = 420;
	private static final int MAXIMUM_ROWS = 4;
	private static final int PREFERRED_TILE_HEIGHT = 48;
	private static final int MINIMUM_TARGET_SIZE = 24;
	private static final int MAXIMUM_TILE_HEIGHT = 64;
	private static final int SINGLE_TILE_MAXIMUM_WIDTH = 240;
	private static final int SINGLE_TILE_MAXIMUM_HEIGHT = 72;

	public AgentRosterGridLayout {
		Objects.requireNonNull(gridBounds, "gridBounds");
		Objects.requireNonNull(pagerBounds, "pagerBounds");
		if (columns < 0 || rows < 0 || tileWidth < 0 || tileHeight < 0 || pageSize < 0) {
			throw new IllegalArgumentException("Roster grid dimensions cannot be negative");
		}
		if (pageSize == 0) {
			if (columns != 0 || rows != 0 || tileWidth != 0 || tileHeight != 0
					|| !gridBounds.isPoint() || hasPager || !pagerBounds.isPoint()) {
				throw new IllegalArgumentException("Empty roster geometry must be non-interactive");
			}
		} else {
			if ((columns != 1 && columns != 2 && columns != 4) || rows == 0 || rows > MAXIMUM_ROWS
					|| tileWidth < MINIMUM_TARGET_SIZE || tileHeight < MINIMUM_TARGET_SIZE
					|| pageSize > columns * rows || rows != divideRoundUp(pageSize, columns)
					|| gridBounds.isEmpty()) {
				throw new IllegalArgumentException("Roster grid geometry is inconsistent");
			}
			boolean singleEntry = columns == 1;
			if (singleEntry
					? rows != 1 || pageSize != 1 || hasPager
							|| tileWidth > SINGLE_TILE_MAXIMUM_WIDTH || tileHeight > SINGLE_TILE_MAXIMUM_HEIGHT
					: pageSize < 2 || tileHeight > MAXIMUM_TILE_HEIGHT) {
				throw new IllegalArgumentException("Roster tile shape is inconsistent with its entry mode");
			}
			long expectedWidth = (long) columns * tileWidth + (long) (columns - 1) * TILE_GAP;
			long expectedHeight = (long) rows * tileHeight + (long) (rows - 1) * TILE_GAP;
			if (gridBounds.width() != expectedWidth || gridBounds.height() != expectedHeight) {
				throw new IllegalArgumentException("Roster tiles must consume their active grid bounds exactly");
			}
			if (hasPager ? pagerBounds.isEmpty() : !pagerBounds.isPoint()) {
				throw new IllegalArgumentException("Pager signal and bounds must agree");
			}
			if (hasPager && pagerBounds.height() != PAGER_HEIGHT) {
				throw new IllegalArgumentException("Pager must use the fixed height");
			}
			if (hasPager && (pageSize != columns * rows
					|| pagerBounds.left() != gridBounds.left() || pagerBounds.right() != gridBounds.right()
					|| (long) pagerBounds.top() - gridBounds.bottom() < PAGER_GAP)) {
				throw new IllegalArgumentException("Pager must align below a full roster page");
			}
		}
	}

	public static AgentRosterGridLayout calculate(
			int left,
			int top,
			int right,
			int bottom,
			int totalEntries
	) {
		if (totalEntries < 0) {
			throw new IllegalArgumentException("Roster entry count cannot be negative");
		}
		long measuredWidth = (long) right - left;
		long measuredHeight = (long) bottom - top;
		if (measuredWidth < MINIMUM_TARGET_SIZE || measuredHeight < MINIMUM_TARGET_SIZE
				|| measuredWidth > Integer.MAX_VALUE || measuredHeight > Integer.MAX_VALUE) {
			throw new IllegalArgumentException("Roster region is inverted or too small for an interactive target");
		}
		int regionWidth = (int) measuredWidth;
		int regionHeight = (int) measuredHeight;

		if (totalEntries == 0) {
			Bounds empty = new Bounds(left, top, left, top);
			return new AgentRosterGridLayout(empty, 0, 0, 0, 0, 0, empty, false);
		}
		if (totalEntries == 1) {
			return singleEntry(left, top, right, bottom, regionWidth, regionHeight);
		}

		int columns = regionWidth >= FOUR_COLUMN_THRESHOLD ? 4 : 2;
		int tileWidth = (regionWidth - (columns - 1) * TILE_GAP) / columns;
		if (tileWidth < MINIMUM_TARGET_SIZE) {
			throw new IllegalArgumentException("Roster region is too narrow for the required columns");
		}

		int rowsWithoutPager = rowCapacity(regionHeight);
		boolean hasPager = totalEntries > columns * rowsWithoutPager;
		int slotBottom = bottom;
		Bounds pagerBounds = new Bounds(left, top, left, top);
		if (hasPager) {
			int pagerTop = bottom - PAGER_HEIGHT;
			slotBottom = pagerTop - PAGER_GAP;
			if (slotBottom - top < MINIMUM_TARGET_SIZE) {
				throw new IllegalArgumentException("Roster region is too short for both tiles and pager");
			}
		}

		int slotHeight = slotBottom - top;
		int rows = hasPager ? rowCapacity(slotHeight) : divideRoundUp(totalEntries, columns);
		if (rows == 0 || rows > MAXIMUM_ROWS) {
			throw new IllegalArgumentException("Roster region cannot fit a visible row");
		}
		int uncappedTileHeight = (slotHeight - (rows - 1) * TILE_GAP) / rows;
		int tileHeight = Math.min(MAXIMUM_TILE_HEIGHT, uncappedTileHeight);
		if (tileHeight < MINIMUM_TARGET_SIZE) {
			throw new IllegalArgumentException("Roster region is too short for an interactive tile");
		}

		int usedWidth = columns * tileWidth + (columns - 1) * TILE_GAP;
		int usedHeight = rows * tileHeight + (rows - 1) * TILE_GAP;
		int gridLeft = left + (regionWidth - usedWidth) / 2;
		int gridTop = top + (slotHeight - usedHeight) / 2;
		Bounds gridBounds = new Bounds(gridLeft, gridTop, gridLeft + usedWidth, gridTop + usedHeight);
		if (hasPager) {
			pagerBounds = new Bounds(gridLeft, bottom - PAGER_HEIGHT, gridLeft + usedWidth, bottom);
		}
		int pageSize = hasPager ? columns * rows : totalEntries;
		return new AgentRosterGridLayout(
				gridBounds, columns, rows, tileWidth, tileHeight, pageSize, pagerBounds, hasPager
		);
	}

	private static AgentRosterGridLayout singleEntry(
			int left,
			int top,
			int right,
			int bottom,
			int regionWidth,
			int regionHeight
	) {
		int tileWidth = Math.min(SINGLE_TILE_MAXIMUM_WIDTH, regionWidth);
		int tileHeight = Math.min(SINGLE_TILE_MAXIMUM_HEIGHT, regionHeight);
		int tileLeft = left + (regionWidth - tileWidth) / 2;
		int tileTop = top + (regionHeight - tileHeight) / 2;
		Bounds gridBounds = new Bounds(tileLeft, tileTop, tileLeft + tileWidth, tileTop + tileHeight);
		Bounds emptyPager = new Bounds(right, bottom, right, bottom);
		return new AgentRosterGridLayout(gridBounds, 1, 1, tileWidth, tileHeight, 1, emptyPager, false);
	}

	private static int rowCapacity(int height) {
		if (height < MINIMUM_TARGET_SIZE) {
			return 0;
		}
		if (height < PREFERRED_TILE_HEIGHT) {
			return 1;
		}
		return Math.min(
				MAXIMUM_ROWS,
				(int) (((long) height + TILE_GAP) / (PREFERRED_TILE_HEIGHT + TILE_GAP))
		);
	}

	private static int divideRoundUp(int value, int divisor) {
		return (value - 1) / divisor + 1;
	}

	public boolean contains(double x, double y) {
		return gridBounds.contains(x, y) || hasPager && pagerBounds.contains(x, y);
	}

	public Bounds tileBounds(int indexOnPage) {
		if (indexOnPage < 0 || indexOnPage >= pageSize) {
			throw new IllegalArgumentException("Tile index is outside the current page capacity");
		}
		int column = indexOnPage % columns;
		int row = indexOnPage / columns;
		int left = gridBounds.left + column * (tileWidth + TILE_GAP);
		int top = gridBounds.top + row * (tileHeight + TILE_GAP);
		return new Bounds(left, top, left + tileWidth, top + tileHeight);
	}

	/** Integer rectangle with inclusive left/top and exclusive right/bottom edges. */
	public record Bounds(int left, int top, int right, int bottom) {
		public Bounds {
			long measuredWidth = (long) right - left;
			long measuredHeight = (long) bottom - top;
			if (measuredWidth < 0 || measuredHeight < 0
					|| measuredWidth > Integer.MAX_VALUE || measuredHeight > Integer.MAX_VALUE) {
				throw new IllegalArgumentException("Bounds cannot be inverted or exceed integer dimensions");
			}
		}

		public int width() {
			return right - left;
		}

		public int height() {
			return bottom - top;
		}

		public boolean isEmpty() {
			return right == left || bottom == top;
		}

		private boolean isPoint() {
			return right == left && bottom == top;
		}

		public boolean contains(double x, double y) {
			return x >= left && x < right && y >= top && y < bottom;
		}
	}
}
