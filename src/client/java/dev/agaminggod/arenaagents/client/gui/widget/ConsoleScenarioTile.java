package dev.agaminggod.arenaagents.client.gui.widget;

import dev.agaminggod.arenaagents.client.gui.ConsoleTheme;
import dev.agaminggod.arenaagents.client.gui.ConsoleFocusTarget;
import dev.agaminggod.arenaagents.client.gui.ConsoleText;
import java.util.Objects;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;
import org.lwjgl.glfw.GLFW;

/** A map-table scenario tile, visually distinct from generic Minecraft buttons. */
public final class ConsoleScenarioTile extends AbstractWidget implements ConsoleFocusTarget {
	private static final int BORDER = 0xFF46515F;
	private static final int FILL = 0xFF222B36;
	private static final int HOVER = 0xFF2C3845;
	private static final int SELECTED = 0xFF35465A;
	private static final int TEXT = 0xFFF2F5F8;
	private static final int MUTED = 0xFFAEB8C4;
	private final Font font;
	private final Component key;
	private final Component title;
	private final Component meta;
	private final boolean selected;
	private final int accent;
	private final Runnable onPress;

	public ConsoleScenarioTile(
			Font font, int x, int y, int width, int height,
			Component key, Component title, Component meta, boolean selected, int accent, Runnable onPress
	) {
		super(x, y, width, height, Component.literal((selected ? "Selected: " : "") + title.getString()
				+ " | shortcut " + key.getString() + " | " + meta.getString()));
		this.font = Objects.requireNonNull(font, "font must not be null");
		this.key = Objects.requireNonNull(key, "key must not be null");
		this.title = Objects.requireNonNull(title, "title must not be null");
		this.meta = Objects.requireNonNull(meta, "meta must not be null");
		this.selected = selected;
		this.accent = accent;
		this.onPress = Objects.requireNonNull(onPress, "onPress must not be null");
	}

	@Override
	protected void extractWidgetRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
		graphics.fill(getX(), getY(), getRight(), getBottom(), isFocused() ? ConsoleTheme.FOCUS : selected ? accent : BORDER);
		int inset = isFocused() || selected ? 2 : 1;
		graphics.fill(getX() + inset, getY() + inset, getRight() - inset, getBottom() - inset,
				selected ? SELECTED : isHoveredOrFocused() ? HOVER : FILL);
		int keySize = Math.min(30, getHeight() - 12);
		int keyX = getX() + 8;
		int keyY = getY() + (getHeight() - keySize) / 2;
		graphics.fill(keyX, keyY, keyX + keySize, keyY + keySize, accent);
		graphics.fill(keyX + 2, keyY + 2, keyX + keySize - 2, keyY + keySize - 2, 0xFF202833);
		ConsoleText.centered(graphics, font, key, keyX + keySize / 2, keyY + (keySize - 9) / 2, accent);
		int textX = keyX + keySize + 10;
		ConsoleText.text(graphics, font, title, textX, getY() + 10, TEXT);
		ConsoleText.text(graphics, font, meta, textX, getY() + 25, MUTED);
		if (selected) ConsoleText.text(graphics, font, "SELECTED", getRight() - 58, getY() + 10, accent);
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
	}

	@Override
	public String consoleFocusIdentity() {
		return "scenario:" + key.getString();
	}
}
