package dev.agaminggod.arenaagents.client.gui.widget;

import dev.agaminggod.arenaagents.client.gui.ConsoleTheme;
import dev.agaminggod.arenaagents.client.gui.ConsoleFocusTarget;
import dev.agaminggod.arenaagents.client.gui.ConsoleText;
import java.util.Objects;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.narration.NarratedElementType;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;
import org.lwjgl.glfw.GLFW;

/** Flat command-console control used instead of Minecraft's generic stone button. */
public final class ConsoleButton extends AbstractWidget implements ConsoleFocusTarget {
	public enum Tone {
		SECONDARY,
		PRIMARY,
		DANGER
	}
	private static final int DANGER_FILL = 0xFF44242B;
	private static final int DANGER_HOVER_FILL = 0xFF573038;
	private final Font font;
	private final Runnable onPress;
	private final int accent;
	private final boolean selected;
	private final Tone tone;

	public ConsoleButton(
			Font font,
			int x,
			int y,
			int width,
			int height,
			Component message,
			boolean selected,
			int accent,
			Runnable onPress
	) {
		this(font, x, y, width, height, message, selected, accent, Tone.SECONDARY, onPress);
	}

	public ConsoleButton(
			Font font,
			int x,
			int y,
			int width,
			int height,
			Component message,
			boolean selected,
			int accent,
			Tone tone,
			Runnable onPress
	) {
		super(x, y, width, height, Objects.requireNonNull(message, "message must not be null"));
		this.font = Objects.requireNonNull(font, "font must not be null");
		this.onPress = Objects.requireNonNull(onPress, "onPress must not be null");
		this.selected = selected;
		this.accent = accent;
		this.tone = Objects.requireNonNull(tone, "tone must not be null");
	}

	@Override
	protected void extractWidgetRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
		Presentation style = presentation(tone, active, selected, isFocused(), isHovered(), accent);
		graphics.fill(getX(), getY(), getRight(), getBottom(), style.border());
		graphics.fill(getX() + style.inset(), getY() + style.inset(),
				getRight() - style.inset(), getBottom() - style.inset(), style.fill());
		ConsoleText.centered(graphics, font, fittedMessage(), getX() + getWidth() / 2,
				getY() + (getHeight() - 9) / 2, style.text());
	}

	public static Presentation presentation(
			Tone tone,
			boolean active,
			boolean selected,
			boolean focused,
			boolean hovered,
			int accent
	) {
		Objects.requireNonNull(tone, "tone must not be null");
		int border = !active ? ConsoleTheme.BORDER
				: focused ? ConsoleTheme.FOCUS
				: tone == Tone.DANGER ? ConsoleTheme.ERROR
				: tone == Tone.PRIMARY || selected ? ConsoleTheme.ACCENT : ConsoleTheme.BORDER;
		int fill = !active ? ConsoleTheme.TRACK
				: tone == Tone.DANGER ? (hovered || focused ? DANGER_HOVER_FILL : DANGER_FILL)
				: tone == Tone.PRIMARY || selected ? ConsoleTheme.ROSTER_SELECTED_SURFACE
				: hovered || focused ? ConsoleTheme.SURFACE_HOVER : ConsoleTheme.SURFACE;
		int text = !active ? ConsoleTheme.MUTED
				: tone == Tone.PRIMARY ? ConsoleTheme.ACCENT : ConsoleTheme.TEXT;
		return new Presentation(border, fill, text, 1);
	}

	public static String selectionNarrationKey(boolean selected) {
		return selected ? "screen.arenaagents.control.selected" : "";
	}

	private Component fittedMessage() {
		String value = getMessage().getString();
		int available = Math.max(8, getWidth() - 10);
		if (ConsoleText.width(font, value) <= available) return getMessage();
		String suffix = "...";
		int end = value.length();
		int target = Math.max(1, available - ConsoleText.width(font, suffix));
		while (end > 0 && ConsoleText.width(font, value.substring(0, end)) > target) end--;
		return Component.literal(value.substring(0, end) + suffix);
	}

	@Override
	public void onClick(MouseButtonEvent event, boolean doubled) {
		if (active) onPress.run();
	}

	@Override
	public boolean keyPressed(KeyEvent event) {
		if (active && (event.key() == GLFW.GLFW_KEY_ENTER || event.key() == GLFW.GLFW_KEY_SPACE)) {
			onPress.run();
			return true;
		}
		return super.keyPressed(event);
	}

	@Override
	protected void updateWidgetNarration(NarrationElementOutput output) {
		defaultButtonNarrationText(output);
		String hint = selectionNarrationKey(selected);
		if (!hint.isEmpty()) output.add(NarratedElementType.HINT, Component.translatable(hint));
	}

	@Override
	public String consoleFocusIdentity() {
		return "button:" + getMessage().getString();
	}

	public record Presentation(int border, int fill, int text, int inset) {
	}
}
