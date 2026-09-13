package dev.agaminggod.arenaagents.client.gui.widget;

import dev.agaminggod.arenaagents.client.gui.ConsoleTheme;
import dev.agaminggod.arenaagents.client.gui.ConsoleFocusTarget;
import dev.agaminggod.arenaagents.client.gui.ConsoleText;
import java.util.List;
import java.util.Objects;
import java.util.function.Consumer;
import java.util.function.Function;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.narration.NarratedElementType;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;
import org.lwjgl.glfw.GLFW;

/** Compact, keyboard-accessible value selector for the Arena Agents command console. */
public final class ConsoleCycleButton<T> extends AbstractWidget implements ConsoleFocusTarget {
	private static final int LABEL = 0xFF9DA9B6;
	private final Font font;
	private final Component label;
	private final List<T> values;
	private final Function<T, Component> formatter;
	private final Consumer<T> onValueChanged;
	private int index;

	public ConsoleCycleButton(
			Font font,
			int x,
			int y,
			int width,
			int height,
			Component label,
			List<T> values,
			T initialValue,
			Function<T, Component> formatter,
			Consumer<T> onValueChanged
	) {
		super(x, y, width, height, Component.empty());
		this.font = Objects.requireNonNull(font, "font must not be null");
		this.label = Objects.requireNonNull(label, "label must not be null");
		this.values = List.copyOf(Objects.requireNonNull(values, "values must not be null"));
		if (this.values.isEmpty()) throw new IllegalArgumentException("values must not be empty");
		this.index = this.values.indexOf(Objects.requireNonNull(initialValue, "initialValue must not be null"));
		if (index < 0) throw new IllegalArgumentException("initialValue must be present in values");
		this.formatter = Objects.requireNonNull(formatter, "formatter must not be null");
		this.onValueChanged = Objects.requireNonNull(onValueChanged, "onValueChanged must not be null");
		updateMessage();
	}

	@Override
	protected void extractWidgetRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
		Presentation style = presentation(active, isFocused(), isHovered());
		graphics.fill(getX(), getY(), getRight(), getBottom(), style.border());
		graphics.fill(getX() + style.inset(), getY() + style.inset(),
				getRight() - style.inset(), getBottom() - style.inset(), style.fill());
		int textY = getY() + (getHeight() - 9) / 2;
		ConsoleText.text(graphics, font, "<", getX() + 7, textY, style.arrow());
		ConsoleText.text(graphics, font, label, getX() + 19, textY, LABEL);
		Component value = fittedValue();
		int rightReserve = 20;
		int valueX = Math.max(getX() + 19 + ConsoleText.width(font, label) + 8,
				getRight() - rightReserve - ConsoleText.width(font, value));
		ConsoleText.text(graphics, font, value, valueX, textY, style.text());
		ConsoleText.text(graphics, font, ">", getRight() - 8, textY, style.arrow());
	}

	public static Presentation presentation(boolean active, boolean focused, boolean hovered) {
		int border = !active ? ConsoleTheme.BORDER
				: focused ? ConsoleTheme.FOCUS
				: hovered ? ConsoleTheme.ROSTER_FOCUS : ConsoleTheme.BORDER;
		int fill = !active ? ConsoleTheme.TRACK
				: hovered || focused ? ConsoleTheme.SURFACE_HOVER : ConsoleTheme.SURFACE;
		int text = active ? ConsoleTheme.TEXT : ConsoleTheme.MUTED;
		int arrow = active && (hovered || focused) ? ConsoleTheme.TEXT : ConsoleTheme.MUTED;
		return new Presentation(border, fill, text, arrow, 1);
	}

	public static String cycleNarrationKey() {
		return "screen.arenaagents.control.cycle_hint";
	}

	private Component fittedValue() {
		String full = formatter.apply(values.get(index)).getString();
		int available = Math.max(12, getWidth() - ConsoleText.width(font, label) - 48);
		if (ConsoleText.width(font, full) <= available) return Component.literal(full);
		String suffix = "...";
		return Component.literal(fit(full, Math.max(1, available - ConsoleText.width(font, suffix))) + suffix);
	}

	private String fit(String value, int available) {
		int end = value.length();
		while (end > 0 && ConsoleText.width(font, value.substring(0, end)) > available) end--;
		return value.substring(0, end);
	}

	@Override
	public void onClick(MouseButtonEvent event, boolean doubled) {
		if (active) cycle(clickDirection(event.x(), getX(), getWidth()));
	}

	/** Left-arrow hit target is deliberately wider than the glyph; the rest advances. */
	public static int clickDirection(double mouseX, int x, int width) {
		if (width <= 0) throw new IllegalArgumentException("width must be positive");
		return mouseX < x + Math.min(24, width / 3) ? -1 : 1;
	}

	@Override
	public boolean keyPressed(KeyEvent event) {
		if (!active) return super.keyPressed(event);
		if (event.key() == GLFW.GLFW_KEY_LEFT) {
			cycle(-1);
			return true;
		}
		if (event.key() == GLFW.GLFW_KEY_RIGHT || event.key() == GLFW.GLFW_KEY_ENTER
				|| event.key() == GLFW.GLFW_KEY_SPACE) {
			cycle(1);
			return true;
		}
		return super.keyPressed(event);
	}

	@Override
	protected void updateWidgetNarration(NarrationElementOutput output) {
		defaultButtonNarrationText(output);
		output.add(NarratedElementType.HINT, Component.translatable(cycleNarrationKey()));
	}

	private void cycle(int direction) {
		index = Math.floorMod(index + direction, values.size());
		updateMessage();
		onValueChanged.accept(values.get(index));
	}

	private void updateMessage() {
		setMessage(Component.literal(label.getString() + ": " + formatter.apply(values.get(index)).getString()));
	}

	@Override
	public String consoleFocusIdentity() {
		return "cycle:" + label.getString();
	}

	public record Presentation(int border, int fill, int text, int arrow, int inset) {
	}
}
