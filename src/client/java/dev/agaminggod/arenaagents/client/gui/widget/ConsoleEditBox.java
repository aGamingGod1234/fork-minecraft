package dev.agaminggod.arenaagents.client.gui.widget;

import dev.agaminggod.arenaagents.client.gui.ConsoleFocusTarget;
import dev.agaminggod.arenaagents.client.gui.ConsoleTheme;
import dev.agaminggod.arenaagents.client.gui.ConsoleText;
import java.util.Objects;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.network.chat.Component;

/** Single-line editor drawn as part of the Field Console instead of a vanilla text box. */
public final class ConsoleEditBox extends EditBox implements ConsoleFocusTarget {
	private static final int HORIZONTAL_PADDING = 6;
	private final Font font;
	private final int outerX;
	private final int outerY;
	private final int outerWidth;
	private final int outerHeight;
	private final Component placeholder;
	private final String focusIdentity;

	public ConsoleEditBox(
			Font font,
			int x,
			int y,
			int width,
			int height,
			Component narration,
			Component placeholder,
			String focusIdentity
	) {
		super(
				Objects.requireNonNull(font, "font must not be null"),
				x + HORIZONTAL_PADDING,
				y + Math.max(0, (height - 8) / 2),
				Math.max(8, width - HORIZONTAL_PADDING * 2),
				8,
				Objects.requireNonNull(narration, "narration must not be null")
		);
		if (width < 24 || height < 16) throw new IllegalArgumentException("console edit box is too small");
		this.font = font;
		this.outerX = x;
		this.outerY = y;
		this.outerWidth = width;
		this.outerHeight = height;
		this.placeholder = Objects.requireNonNull(placeholder, "placeholder must not be null");
		this.focusIdentity = Objects.requireNonNull(focusIdentity, "focusIdentity must not be null");
		setBordered(false);
		setTextShadow(false);
		setTextColor(ConsoleTheme.TEXT);
		setTextColorUneditable(ConsoleTheme.MUTED);
	}

	@Override
	public void extractWidgetRenderState(
			GuiGraphicsExtractor graphics,
			int mouseX,
			int mouseY,
			float partialTick
	) {
		int outline = isFocused() ? ConsoleTheme.FOCUS
				: isHovered() ? ConsoleTheme.ACCENT : ConsoleTheme.BORDER;
		graphics.fill(outerX, outerY, outerX + outerWidth, outerY + outerHeight, outline);
		int inset = isFocused() ? 2 : 1;
		graphics.fill(outerX + inset, outerY + inset,
				outerX + outerWidth - inset, outerY + outerHeight - inset, ConsoleTheme.TRACK);
		if (getValue().isEmpty() && !isFocused()) {
			ConsoleText.text(graphics, font, placeholder, outerX + HORIZONTAL_PADDING,
					outerY + (outerHeight - 8) / 2, ConsoleTheme.MUTED);
		}
		super.extractWidgetRenderState(graphics, mouseX, mouseY, partialTick);
	}

	@Override
	public boolean isMouseOver(double mouseX, double mouseY) {
		return visible && mouseX >= outerX && mouseX < outerX + outerWidth
				&& mouseY >= outerY && mouseY < outerY + outerHeight;
	}

	@Override
	public String consoleFocusIdentity() {
		return "input:" + focusIdentity;
	}
}
