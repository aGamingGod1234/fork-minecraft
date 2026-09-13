package dev.agaminggod.arenaagents.client.gui;

import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.network.chat.FontDescription;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;

/** Text primitives for the product UI that intentionally avoid Minecraft's default shadow. */
public final class ConsoleText {
	private static final FontDescription CONSOLE_FONT = new FontDescription.Resource(ConsoleFont.ID);

	private ConsoleText() {
	}

	public static MutableComponent component(String message) {
		return component(Component.literal(message));
	}

	public static MutableComponent component(Component message) {
		return message.copy().withStyle(style -> style.withFont(CONSOLE_FONT).withoutShadow());
	}

	public static int width(Font font, String message) {
		return font.width(component(message));
	}

	public static int width(Font font, Component message) {
		return font.width(component(message));
	}

	public static void text(
			GuiGraphicsExtractor graphics,
			Font font,
			Component message,
			int x,
			int y,
			int color
	) {
		graphics.text(font, component(message), x, y, color, false);
	}

	public static void text(
			GuiGraphicsExtractor graphics,
			Font font,
			String message,
			int x,
			int y,
			int color
	) {
		text(graphics, font, Component.literal(message), x, y, color);
	}

	public static void centered(
			GuiGraphicsExtractor graphics,
			Font font,
			Component message,
			int centerX,
			int y,
			int color
	) {
		Component styled = component(message);
		graphics.text(font, styled, centerX - font.width(styled) / 2, y, color, false);
	}

	public static void centered(
			GuiGraphicsExtractor graphics,
			Font font,
			String message,
			int centerX,
			int y,
			int color
	) {
		centered(graphics, font, Component.literal(message), centerX, y, color);
	}
}
