package dev.agaminggod.arenaagents.client.gui;

import dev.agaminggod.arenaagents.client.mixin.FontManagerAccessor;
import dev.agaminggod.arenaagents.client.mixin.MinecraftFontManagerAccessor;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.font.FontManager;
import net.minecraft.client.gui.font.FontSet;

/** Installs the Arena Agents typeface only for Arena Agents screens and widgets. */
public final class ConsoleFont {
	public static final net.minecraft.resources.Identifier ID =
			net.minecraft.resources.Identifier.fromNamespaceAndPath("arenaagents", "console");
	private static FontSet cachedSet;
	private static Font cachedFont;

	private ConsoleFont() {
	}

	public static synchronized Font create(Minecraft minecraft) {
		FontManager manager = ((MinecraftFontManagerAccessor) minecraft).arenaagents$fontManager();
		FontSet selected = ((FontManagerAccessor) manager).arenaagents$fontSets().get(ID);
		if (selected == null) return minecraft.font;
		if (selected == cachedSet && cachedFont != null) return cachedFont;
		cachedSet = selected;
		cachedFont = new Font(new Font.Provider() {
			@Override
			public net.minecraft.client.gui.GlyphSource glyphs(net.minecraft.network.chat.FontDescription description) {
				return selected.source(false);
			}

			@Override
			public net.minecraft.client.gui.font.glyphs.EffectGlyph effect() {
				return selected.whiteGlyph();
			}
		});
		return cachedFont;
	}
}
