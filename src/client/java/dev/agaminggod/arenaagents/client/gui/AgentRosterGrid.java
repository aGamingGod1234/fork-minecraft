package dev.agaminggod.arenaagents.client.gui;

import dev.agaminggod.arenaagents.agent.AgentVisualIdentity;
import dev.agaminggod.arenaagents.client.gui.AgentRosterGridLayout.Bounds;
import dev.agaminggod.arenaagents.client.gui.widget.AgentRosterTile;
import dev.agaminggod.arenaagents.client.gui.widget.AgentRosterTile.Intent;
import dev.agaminggod.arenaagents.control.AgentRosterEntry;
import dev.agaminggod.arenaagents.control.AgentRosterPage;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.function.ToIntFunction;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;
import org.lwjgl.glfw.GLFW;

/** Builds one immutable visible roster page and delegates all mutations to its owner. */
public final class AgentRosterGrid {
	private static final int PAGER_CONTROL_SIZE = 24;
	private static final int PAGER_GAP = PAGER_CONTROL_SIZE - AgentRosterGridLayout.PAGER_HEIGHT;

	private final Font font;
	private final AgentRosterPage page;
	private final AgentRosterGridLayout layout;
	private final Set<String> selectedIds;
	private final Actions actions;
	private final List<AbstractWidget> widgets;

	public AgentRosterGrid(
			Font font,
			AgentRosterPage page,
			AgentRosterGridLayout layout,
			Set<String> selectedIds,
			Mode mode,
			Predicate<String> contextFocusedById,
			Function<String, AgentVisualIdentity.Resolved> visualById,
			Actions actions
	) {
		this.font = Objects.requireNonNull(font, "font must not be null");
		this.page = Objects.requireNonNull(page, "page must not be null");
		this.layout = Objects.requireNonNull(layout, "layout must not be null");
		validatePageLayout(page, layout);
		this.selectedIds = immutableSelectedSnapshot(selectedIds);
		Objects.requireNonNull(mode, "mode must not be null");
		Objects.requireNonNull(contextFocusedById, "context focus lookup must not be null");
		Objects.requireNonNull(visualById, "visual lookup must not be null");
		this.actions = Objects.requireNonNull(actions, "actions must not be null");

		if (page.entries().size() > layout.pageSize()) {
			throw new IllegalArgumentException("Visible roster page exceeds layout capacity");
		}
		ArrayList<AbstractWidget> built = new ArrayList<>();
		for (int index = 0; index < page.entries().size(); index++) {
			AgentRosterEntry entry = page.entries().get(index);
			Bounds bounds = layout.tileBounds(index);
			AgentVisualIdentity.Resolved visual = Objects.requireNonNull(
					visualById.apply(entry.id()), "Missing visual identity for roster entry " + entry.id());
			built.add(new AgentRosterTile(
					font,
					entry,
					visual,
					bounds.left(),
					bounds.top(),
					bounds.width(),
					bounds.height(),
					page.firstIndex() + index,
					page.totalFiltered(),
					this.selectedIds.contains(entry.id()),
					mode,
					() -> contextFocusedById.test(entry.id()),
					this::handleIntent
			));
		}
		if (hasPager(layout, page)) {
			PagerMetrics previous = pagerMetrics(layout, false);
			PagerMetrics next = pagerMetrics(layout, true);
			built.add(new PagerWidget(font, previous, false, page.page() > 1, () -> actions.changePage(-1)));
			built.add(new PagerWidget(
					font, next, true, page.page() < page.pageCount(), () -> actions.changePage(1)));
		}
		widgets = immutableSnapshot(built);
	}

	public List<AbstractWidget> widgets() {
		return widgets;
	}

	public Set<String> selectedIds() {
		return selectedIds;
	}

	public void extractRenderState(GuiGraphicsExtractor graphics) {
		Objects.requireNonNull(graphics, "graphics must not be null");
		if (!hasPager(layout, page)) return;
		Bounds bounds = pagerLabelBounds(layout);
		String label = pagerDisplayLabel(page, layout, value -> ConsoleText.width(font, value));
		if (label.isEmpty()) return;
		ConsoleText.centered(
				graphics,
				font,
				label,
				bounds.left() + bounds.width() / 2,
				bounds.top() + (bounds.height() - 9) / 2,
				ConsoleTheme.MUTED
		);
	}

	public boolean mouseScrolled(
			double mouseX,
			double mouseY,
			double horizontalDelta,
			double verticalDelta
	) {
		int delta = wheelPageDelta(layout, page, mouseX, mouseY, horizontalDelta, verticalDelta);
		if (delta == 0) return false;
		actions.changePage(delta);
		return true;
	}

	private void handleIntent(Intent intent) {
		switch (intent.type()) {
			case FOCUS -> actions.focus(intent.agentId());
			case OPEN -> actions.open(intent.agentId());
			case TOGGLE -> actions.toggle(intent.agentId());
			case SELECT_RANGE -> actions.selectRangeTo(intent.agentId());
			case SELECT_ALL -> actions.selectAll();
			case MOVE_LEFT -> move(intent.agentId(), Direction.LEFT);
			case MOVE_RIGHT -> move(intent.agentId(), Direction.RIGHT);
			case MOVE_UP -> move(intent.agentId(), Direction.UP);
			case MOVE_DOWN -> move(intent.agentId(), Direction.DOWN);
		}
	}

	private void move(String currentId, Direction direction) {
		String target = directionalTarget(page.entries(), currentId, layout.columns(), direction);
		if (!target.isEmpty() && !target.equals(currentId)) actions.focus(target);
	}

	public static String directionalTarget(
			List<AgentRosterEntry> visibleEntries,
			String currentId,
			int columns,
			Direction direction
	) {
		List<AgentRosterEntry> entries = List.copyOf(
				Objects.requireNonNull(visibleEntries, "visible entries must not be null"));
		Objects.requireNonNull(direction, "direction must not be null");
		if (columns < 1) throw new IllegalArgumentException("Roster columns must be positive");
		if (entries.isEmpty()) return "";
		int current = indexOf(entries, currentId);
		if (current < 0) return entries.getFirst().id();
		int row = current / columns;
		int column = current % columns;
		int lastRow = (entries.size() - 1) / columns;
		int target = switch (direction) {
			case LEFT -> Math.max(row * columns, current - 1);
			case RIGHT -> Math.min(Math.min(entries.size() - 1, (row + 1) * columns - 1), current + 1);
			case UP -> Math.min(Math.max(0, row - 1) * columns + column, entries.size() - 1);
			case DOWN -> Math.min(Math.min(lastRow, row + 1) * columns + column, entries.size() - 1);
		};
		return entries.get(target).id();
	}

	private static int indexOf(List<AgentRosterEntry> entries, String id) {
		if (id == null || id.isEmpty()) return -1;
		for (int index = 0; index < entries.size(); index++) {
			if (entries.get(index).id().equals(id)) return index;
		}
		return -1;
	}

	public static int wheelPageDelta(
			AgentRosterGridLayout layout,
			AgentRosterPage page,
			double mouseX,
			double mouseY,
			double horizontalDelta,
			double verticalDelta
	) {
		Objects.requireNonNull(layout, "layout must not be null");
		Objects.requireNonNull(page, "page must not be null");
		if (!hasPager(layout, page) || !Double.isFinite(verticalDelta) || verticalDelta == 0.0D
				|| !layout.contains(mouseX, mouseY)) {
			return 0;
		}
		int requested = verticalDelta < 0.0D ? 1 : -1;
		int targetPage = page.page() + requested;
		return targetPage >= 1 && targetPage <= page.pageCount() ? requested : 0;
	}

	public static boolean hasPager(AgentRosterGridLayout layout, AgentRosterPage page) {
		Objects.requireNonNull(layout, "layout must not be null");
		Objects.requireNonNull(page, "page must not be null");
		return layout.hasPager() && page.pageCount() > 1;
	}

	public static String pagerLabel(AgentRosterPage page) {
		Objects.requireNonNull(page, "page must not be null");
		return page.firstIndex() + "–" + page.lastIndex() + " of " + page.totalFiltered();
	}

	public static Bounds pagerLabelBounds(AgentRosterGridLayout layout) {
		Objects.requireNonNull(layout, "layout must not be null");
		if (!layout.hasPager()) throw new IllegalArgumentException("Roster layout has no pager");
		Bounds previous = pagerMetrics(layout, false).interactionBounds();
		Bounds next = pagerMetrics(layout, true).interactionBounds();
		return new Bounds(previous.right(), layout.pagerBounds().top(), next.left(), layout.pagerBounds().bottom());
	}

	public static String pagerDisplayLabel(
			AgentRosterPage page,
			AgentRosterGridLayout layout,
			ToIntFunction<String> measure
	) {
		Objects.requireNonNull(page, "page must not be null");
		Objects.requireNonNull(measure, "measure must not be null");
		int available = pagerLabelBounds(layout).width();
		String full = pagerLabel(page);
		if (measure.applyAsInt(full) <= available) return full;
		String shortLabel = page.page() + "/" + page.pageCount();
		return measure.applyAsInt(shortLabel) <= available ? shortLabel : "";
	}

	public static void validatePageLayout(AgentRosterPage page, AgentRosterGridLayout layout) {
		Objects.requireNonNull(page, "page must not be null");
		Objects.requireNonNull(layout, "layout must not be null");
		if (page.totalFiltered() < 0 || page.page() < 1 || page.pageCount() < 1
				|| page.page() > page.pageCount() || page.hiddenSelectedCount() < 0) {
			throw new IllegalArgumentException("Roster page metadata cannot be negative or out of range");
		}
		int pageSize = layout.pageSize();
		if (page.totalFiltered() == 0) {
			if (pageSize != 0 || !page.entries().isEmpty() || page.firstIndex() != 0 || page.lastIndex() != 0
					|| page.page() != 1 || page.pageCount() != 1 || layout.hasPager()) {
				throw new IllegalArgumentException("Empty roster page must match empty layout geometry");
			}
			return;
		}
		if (pageSize < 1) throw new IllegalArgumentException("Non-empty roster requires visible layout capacity");
		int expectedPageCount = (int) (((long) page.totalFiltered() + pageSize - 1L) / pageSize);
		boolean expectedPager = expectedPageCount > 1;
		if (page.pageCount() != expectedPageCount || layout.hasPager() != expectedPager
				|| (!expectedPager && pageSize != page.totalFiltered())) {
			throw new IllegalArgumentException("Roster total, page count, and pager geometry disagree");
		}
		int expectedFirst = (int) ((long) (page.page() - 1) * pageSize + 1L);
		int expectedLast = (int) Math.min((long) page.page() * pageSize, page.totalFiltered());
		int expectedEntries = expectedLast - expectedFirst + 1;
		if (page.firstIndex() != expectedFirst || page.lastIndex() != expectedLast
				|| page.entries().size() != expectedEntries || page.entries().size() > pageSize) {
			throw new IllegalArgumentException("Roster page range does not match layout capacity");
		}
	}

	public static PagerMetrics pagerMetrics(AgentRosterGridLayout layout, boolean next) {
		Objects.requireNonNull(layout, "layout must not be null");
		if (!layout.hasPager()) throw new IllegalArgumentException("Roster layout has no pager");
		Bounds pager = layout.pagerBounds();
		int left = next ? pager.right() - PAGER_CONTROL_SIZE : pager.left();
		Bounds interaction = new Bounds(
				left, pager.top() - PAGER_GAP, left + PAGER_CONTROL_SIZE, pager.bottom());
		Bounds draw = new Bounds(left, pager.top(), left + PAGER_CONTROL_SIZE, pager.bottom());
		return new PagerMetrics(interaction, draw);
	}

	public static <T> List<T> immutableSnapshot(List<T> values) {
		return List.copyOf(Objects.requireNonNull(values, "values must not be null"));
	}

	public static <T> Set<T> immutableSelectedSnapshot(Set<T> values) {
		return Set.copyOf(Objects.requireNonNull(values, "values must not be null"));
	}

	public enum Mode {
		FOCUS_ONLY,
		MULTI_SELECT
	}

	public enum Direction {
		LEFT,
		RIGHT,
		UP,
		DOWN
	}

	public record PagerMetrics(Bounds interactionBounds, Bounds drawBounds) {
		public PagerMetrics {
			Objects.requireNonNull(interactionBounds, "interaction bounds must not be null");
			Objects.requireNonNull(drawBounds, "draw bounds must not be null");
		}
	}

	public interface Actions {
		void focus(String id);

		void open(String id);

		void toggle(String id);

		void selectRangeTo(String id);

		void selectAll();

		void changePage(int delta);
	}

	private static final class PagerWidget extends AbstractWidget implements ConsoleFocusTarget {
		private final Font font;
		private final PagerMetrics metrics;
		private final boolean next;
		private final Runnable onPress;

		private PagerWidget(Font font, PagerMetrics metrics, boolean next, boolean enabled, Runnable onPress) {
			super(
					metrics.interactionBounds().left(),
					metrics.interactionBounds().top(),
					metrics.interactionBounds().width(),
					metrics.interactionBounds().height(),
					Component.literal(next ? "Next roster page" : "Previous roster page")
			);
			this.font = font;
			this.metrics = metrics;
			this.next = next;
			this.onPress = onPress;
			active = enabled;
		}

		@Override
		protected void extractWidgetRenderState(
				GuiGraphicsExtractor graphics,
				int mouseX,
				int mouseY,
				float partialTick
		) {
			Bounds draw = metrics.drawBounds();
			int color = active ? isFocused() ? ConsoleTheme.ROSTER_FOCUS : ConsoleTheme.ACCENT : ConsoleTheme.BORDER;
			if (isHoveredOrFocused() && active) {
				graphics.fill(draw.left(), draw.top(), draw.right(), draw.bottom(), ConsoleTheme.SURFACE_HOVER);
			}
			ConsoleText.centered(
					graphics,
					font,
					next ? ">" : "<",
					draw.left() + draw.width() / 2,
					draw.top() + (draw.height() - 9) / 2,
					color
			);
		}

		@Override
		public void onClick(MouseButtonEvent event, boolean doubled) {
			if (active) onPress.run();
		}

		@Override
		public boolean keyPressed(KeyEvent event) {
			if (active && (event.key() == GLFW.GLFW_KEY_ENTER || event.key() == GLFW.GLFW_KEY_KP_ENTER
					|| event.key() == GLFW.GLFW_KEY_SPACE)) {
				onPress.run();
				return true;
			}
			return super.keyPressed(event);
		}

		@Override
		protected void updateWidgetNarration(NarrationElementOutput output) {
			defaultButtonNarrationText(output);
		}

		@Override
		public String consoleFocusIdentity() {
			return next ? "roster-page:next" : "roster-page:previous";
		}
	}
}
