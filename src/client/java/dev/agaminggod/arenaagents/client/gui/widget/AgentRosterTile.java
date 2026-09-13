package dev.agaminggod.arenaagents.client.gui.widget;

import dev.agaminggod.arenaagents.agent.AgentVisualIdentity;
import dev.agaminggod.arenaagents.client.gui.AgentRosterGrid;
import dev.agaminggod.arenaagents.client.gui.AgentRosterGridLayout.Bounds;
import dev.agaminggod.arenaagents.client.gui.ConsoleFocusTarget;
import dev.agaminggod.arenaagents.client.gui.ConsoleText;
import dev.agaminggod.arenaagents.client.gui.ConsoleTheme;
import dev.agaminggod.arenaagents.control.AgentRosterEntry;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.ToIntFunction;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.PlayerFaceExtractor;
import net.minecraft.client.gui.narration.NarratedElementType;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import org.lwjgl.glfw.GLFW;

/** One concise agent portrait that emits semantic roster intents without owning roster state. */
public final class AgentRosterTile extends AbstractWidget implements ConsoleFocusTarget {
	private static final String ELLIPSIS = "…";
	private static final int FACE_MAXIMUM = 32;
	private static final int TEXT_GAP = 7;
	private static final int MINIMUM_TILE_SIZE = 24;
	private static final int CHECK_WIDTH = 7;
	private static final int CHECK_HEIGHT = 6;

	private final Font font;
	private final AgentRosterEntry entry;
	private final Identifier texture;
	private final int position;
	private final int total;
	private final boolean selected;
	private final AgentRosterGrid.Mode mode;
	private final BooleanSupplier contextFocused;
	private final Consumer<Intent> onIntent;

	public AgentRosterTile(
			Font font,
			AgentRosterEntry entry,
			AgentVisualIdentity.Resolved visual,
			int x,
			int y,
			int width,
			int height,
			int position,
			int total,
			boolean selected,
			AgentRosterGrid.Mode mode,
			BooleanSupplier contextFocused,
			Consumer<Intent> onIntent
	) {
		super(x, y, width, height, Component.literal(narrationText(entry, position, total, selected)));
		this.font = Objects.requireNonNull(font, "font must not be null");
		this.entry = Objects.requireNonNull(entry, "entry must not be null");
		AgentVisualIdentity.Resolved checkedVisual = Objects.requireNonNull(
				visual, "visual identity must not be null");
		this.texture = Identifier.parse(AgentVisualIdentity.renderTexturePath(checkedVisual));
		if (position < 1 || total < position) {
			throw new IllegalArgumentException("Roster tile position must be within the filtered total");
		}
		this.position = position;
		this.total = total;
		this.selected = selected;
		this.mode = Objects.requireNonNull(mode, "mode must not be null");
		this.contextFocused = Objects.requireNonNull(contextFocused, "context focus must not be null");
		this.onIntent = Objects.requireNonNull(onIntent, "intent consumer must not be null");
	}

	@Override
	protected void extractWidgetRenderState(
			GuiGraphicsExtractor graphics,
			int mouseX,
			int mouseY,
			float partialTick
	) {
		ContentMetrics metrics = contentMetrics(getX(), getY(), getWidth(), getHeight(), selected);
		int surface = surfaceColor(selected, entry.selectable(), isHovered());
		graphics.fill(getX(), getY(), getRight(), getBottom(), surface);
		graphics.outline(
				getX(), getY(), getWidth(), getHeight(),
				focusVisible(isFocused(), contextFocused.getAsBoolean())
						? ConsoleTheme.ROSTER_FOCUS : ConsoleTheme.BORDER
		);

		Bounds portrait = metrics.portraitBounds();
		PlayerFaceExtractor.extractRenderState(
				graphics,
				texture,
				portrait.left(),
				portrait.top(),
				portrait.width(),
				true,
				false,
				0xFFFFFFFF
		);

		if (metrics.showPrimaryText()) {
			Bounds primary = metrics.primaryTextBounds();
			String fittedName = ellipsize(
					entry.name(), primary.width(), value -> ConsoleText.width(font, value));
			ConsoleText.text(graphics, font, fittedName, primary.left(), primary.top(), ConsoleTheme.TEXT);
		}
		if (metrics.showSecondaryText()) {
			Bounds secondary = metrics.secondaryTextBounds();
			String fittedModel = ellipsize(
					entry.modelLabel(), secondary.width(), value -> ConsoleText.width(font, value));
			ConsoleText.text(graphics, font, fittedModel, secondary.left(), secondary.top(), ConsoleTheme.MUTED);
			drawStateCue(graphics, metrics.stateCueBounds(), entry.state());
		}
		if (metrics.showCheck()) {
			drawSelectionCheck(graphics, metrics.checkBounds());
		}
	}

	public static int surfaceColor(boolean selected, boolean selectable, boolean hovered) {
		if (!selectable) return ConsoleTheme.ROSTER_UNAVAILABLE_SURFACE;
		if (selected) return ConsoleTheme.ROSTER_SELECTED_SURFACE;
		return hovered ? ConsoleTheme.SURFACE_HOVER : ConsoleTheme.SURFACE;
	}

	private static void drawStateCue(GuiGraphicsExtractor graphics, Bounds bounds, String state) {
		int color = stateColor(state);
		switch (stateCue(state)) {
			case BAR -> graphics.fill(
					bounds.left(), bounds.top() + 2, bounds.right(), bounds.bottom() - 1, color);
			case SQUARE -> graphics.outline(
					bounds.left(), bounds.top(), bounds.width(), bounds.height(), color);
			case PAUSE -> {
				graphics.fill(bounds.left(), bounds.top(), bounds.left() + 2, bounds.bottom(), color);
				graphics.fill(bounds.right() - 2, bounds.top(), bounds.right(), bounds.bottom(), color);
			}
			case CROSS -> {
				graphics.fill(bounds.left(), bounds.top(), bounds.left() + 2, bounds.top() + 2, color);
				graphics.fill(bounds.right() - 2, bounds.top(), bounds.right(), bounds.top() + 2, color);
				graphics.fill(bounds.left() + 2, bounds.top() + 2, bounds.right() - 2, bounds.bottom(), color);
			}
		}
	}

	private static void drawSelectionCheck(GuiGraphicsExtractor graphics, Bounds bounds) {
		graphics.fill(bounds.left(), bounds.top() + 3, bounds.left() + 2, bounds.top() + 5, ConsoleTheme.ACCENT);
		graphics.fill(bounds.left() + 2, bounds.top() + 4, bounds.left() + 4, bounds.bottom(), ConsoleTheme.ACCENT);
		graphics.fill(bounds.left() + 4, bounds.top() + 1, bounds.right(), bounds.top() + 5, ConsoleTheme.ACCENT);
	}

	private static StateCue stateCue(String state) {
		String value = state == null ? "" : state.toLowerCase(java.util.Locale.ROOT);
		if (value.contains("work") || value.contains("running")) return StateCue.BAR;
		if (value.contains("idle") || value.contains("ready")) return StateCue.SQUARE;
		if (value.contains("block") || value.contains("wait")) return StateCue.PAUSE;
		return StateCue.CROSS;
	}

	private static int stateColor(String state) {
		return switch (stateCue(state)) {
			case BAR -> ConsoleTheme.SUCCESS;
			case SQUARE -> ConsoleTheme.MUTED;
			case PAUSE -> ConsoleTheme.ACCENT;
			case CROSS -> ConsoleTheme.ERROR;
		};
	}

	@Override
	public void onClick(MouseButtonEvent event, boolean doubled) {
		if (!active) return;
		for (IntentType type : clickIntentTypes(mode, event.hasShiftDown(), doubled)) {
			emit(type);
		}
	}

	@Override
	public boolean keyPressed(KeyEvent event) {
		if (!active) return super.keyPressed(event);
		Optional<IntentType> intent = keyIntent(event.key(), event.isSelectAll(), mode);
		if (intent.isEmpty()) return super.keyPressed(event);
		emit(intent.orElseThrow());
		return true;
	}

	@Override
	public void setFocused(boolean focused) {
		boolean wasFocused = isFocused();
		super.setFocused(focused);
		if (shouldEmitFocus(wasFocused, focused, contextFocused.getAsBoolean())) {
			onIntent.accept(new Intent(IntentType.FOCUS, entry.id()));
		}
	}

	public static boolean shouldEmitFocus(
			boolean wasFocused,
			boolean focused,
			boolean contextAlreadyFocused
	) {
		return focused && !wasFocused && !contextAlreadyFocused;
	}

	public static boolean focusVisible(boolean keyboardFocused, boolean contextFocused) {
		return keyboardFocused || contextFocused;
	}

	public static ContentMetrics contentMetrics(int x, int y, int width, int height, boolean selected) {
		if (width < MINIMUM_TILE_SIZE || height < MINIMUM_TILE_SIZE) {
			throw new IllegalArgumentException("Roster tile must meet the 24-pixel interaction minimum");
		}
		int padding = Math.min(6, Math.max(4, Math.min(width, height) / 8));
		int portraitSize = Math.min(FACE_MAXIMUM, Math.min(width - padding * 2, height - padding * 2));
		int portraitX = x + padding;
		int portraitY = y + (height - portraitSize) / 2;
		Bounds portrait = new Bounds(
				portraitX, portraitY, portraitX + portraitSize, portraitY + portraitSize);

		Bounds hidden = new Bounds(x, y, x, y);
		Bounds check = hidden;
		if (selected) {
			int checkLeft = x + width - padding - CHECK_WIDTH;
			check = new Bounds(checkLeft, y + padding, checkLeft + CHECK_WIDTH, y + padding + CHECK_HEIGHT);
		}

		int textLeft = portrait.right() + TEXT_GAP;
		int textRight = selected ? check.left() - 3 : x + width - padding;
		int textWidth = Math.max(0, textRight - textLeft);
		int primaryY = y + Math.max(padding, (height - 22) / 2);
		boolean showPrimary = textWidth >= 12 && primaryY + 9 <= y + height - padding;
		Bounds primary = showPrimary
				? new Bounds(textLeft, primaryY, textRight, primaryY + 9) : hidden;

		int stateWidth = 6;
		int stateHeight = 5;
		int secondaryY = primaryY + 12;
		int secondaryLeft = textLeft + stateWidth + 4;
		boolean showSecondary = showPrimary && textRight - secondaryLeft >= 12
				&& secondaryY + 9 <= y + height - padding;
		Bounds secondary = showSecondary
				? new Bounds(secondaryLeft, secondaryY, textRight, secondaryY + 9) : hidden;
		Bounds state = showSecondary
				? new Bounds(textLeft, secondaryY + 1, textLeft + stateWidth, secondaryY + 1 + stateHeight)
				: hidden;

		ArrayList<Bounds> painted = new ArrayList<>();
		painted.add(portrait);
		if (showPrimary) painted.add(primary);
		if (showSecondary) {
			painted.add(secondary);
			painted.add(state);
		}
		if (selected) painted.add(check);
		return new ContentMetrics(portrait, primary, secondary, state, check, List.copyOf(painted));
	}

	private void emit(IntentType type) {
		if (type == IntentType.FOCUS && contextFocused.getAsBoolean()) return;
		onIntent.accept(new Intent(type, entry.id()));
	}

	@Override
	protected void updateWidgetNarration(NarrationElementOutput output) {
		output.add(NarratedElementType.TITLE, narrationText(entry, position, total, selected));
	}

	@Override
	public String consoleFocusIdentity() {
		return focusIdentity(entry.id());
	}

	public static String focusIdentity(String exactId) {
		return "agent-tile:" + Objects.requireNonNull(exactId, "agent ID must not be null");
	}

	public static String narrationText(
			AgentRosterEntry entry,
			int position,
			int total,
			boolean selected
	) {
		Objects.requireNonNull(entry, "entry must not be null");
		if (position < 1 || total < position) {
			throw new IllegalArgumentException("Roster narration position must be within the filtered total");
		}
		StringBuilder narration = new StringBuilder()
				.append("Agent ").append(position).append(" of ").append(total).append(". ")
				.append(entry.name()).append(". ")
				.append(entry.modelLabel()).append(". ")
				.append(entry.state()).append(". ")
				.append(selected ? "Selected." : "Not selected.");
		if (!entry.selectable() && !entry.unavailableReason().isEmpty()) {
			narration.append(" Unavailable: ").append(entry.unavailableReason()).append('.');
		}
		return narration.toString();
	}

	public static String ellipsize(String value, int availableWidth, ToIntFunction<String> measure) {
		Objects.requireNonNull(value, "value must not be null");
		Objects.requireNonNull(measure, "measure must not be null");
		if (availableWidth <= 0) return "";
		if (measure.applyAsInt(value) <= availableWidth) return value;
		int suffixWidth = measure.applyAsInt(ELLIPSIS);
		if (suffixWidth > availableWidth) return "";
		int end = value.length();
		while (end > 0 && measure.applyAsInt(value.substring(0, end)) + suffixWidth > availableWidth) {
			end = value.offsetByCodePoints(0, value.codePointCount(0, end) - 1);
		}
		return value.substring(0, end) + ELLIPSIS;
	}

	public static List<IntentType> clickIntentTypes(
			AgentRosterGrid.Mode mode,
			boolean shiftDown,
			boolean doubled
	) {
		Objects.requireNonNull(mode, "mode must not be null");
		if (doubled) return List.of(IntentType.FOCUS, IntentType.OPEN);
		if (mode == AgentRosterGrid.Mode.FOCUS_ONLY) return List.of(IntentType.FOCUS);
		return List.of(IntentType.FOCUS, shiftDown ? IntentType.SELECT_RANGE : IntentType.TOGGLE);
	}

	public static Optional<IntentType> keyIntent(
			int key,
			boolean platformSelectAll,
			AgentRosterGrid.Mode mode
	) {
		Objects.requireNonNull(mode, "mode must not be null");
		if (platformSelectAll) {
			return mode == AgentRosterGrid.Mode.MULTI_SELECT
					? Optional.of(IntentType.SELECT_ALL) : Optional.empty();
		}
		return switch (key) {
			case GLFW.GLFW_KEY_ENTER, GLFW.GLFW_KEY_KP_ENTER -> Optional.of(IntentType.OPEN);
			case GLFW.GLFW_KEY_SPACE -> mode == AgentRosterGrid.Mode.MULTI_SELECT
					? Optional.of(IntentType.TOGGLE) : Optional.empty();
			case GLFW.GLFW_KEY_LEFT -> Optional.of(IntentType.MOVE_LEFT);
			case GLFW.GLFW_KEY_RIGHT -> Optional.of(IntentType.MOVE_RIGHT);
			case GLFW.GLFW_KEY_UP -> Optional.of(IntentType.MOVE_UP);
			case GLFW.GLFW_KEY_DOWN -> Optional.of(IntentType.MOVE_DOWN);
			default -> Optional.empty();
		};
	}

	public enum IntentType {
		FOCUS,
		OPEN,
		TOGGLE,
		SELECT_RANGE,
		SELECT_ALL,
		MOVE_LEFT,
		MOVE_RIGHT,
		MOVE_UP,
		MOVE_DOWN
	}

	public record Intent(IntentType type, String agentId) {
		public Intent {
			Objects.requireNonNull(type, "intent type must not be null");
			Objects.requireNonNull(agentId, "agent ID must not be null");
		}
	}

	public record ContentMetrics(
			Bounds portraitBounds,
			Bounds primaryTextBounds,
			Bounds secondaryTextBounds,
			Bounds stateCueBounds,
			Bounds checkBounds,
			List<Bounds> paintedBounds
	) {
		public ContentMetrics {
			Objects.requireNonNull(portraitBounds, "portrait bounds must not be null");
			Objects.requireNonNull(primaryTextBounds, "primary text bounds must not be null");
			Objects.requireNonNull(secondaryTextBounds, "secondary text bounds must not be null");
			Objects.requireNonNull(stateCueBounds, "state cue bounds must not be null");
			Objects.requireNonNull(checkBounds, "check bounds must not be null");
			paintedBounds = List.copyOf(Objects.requireNonNull(paintedBounds, "painted bounds must not be null"));
		}

		public boolean showPrimaryText() {
			return !primaryTextBounds.isEmpty();
		}

		public boolean showSecondaryText() {
			return !secondaryTextBounds.isEmpty();
		}

		public boolean showStateCue() {
			return !stateCueBounds.isEmpty();
		}

		public boolean showCheck() {
			return !checkBounds.isEmpty();
		}
	}

	private enum StateCue {
		BAR,
		SQUARE,
		PAUSE,
		CROSS
	}
}
