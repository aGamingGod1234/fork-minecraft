package dev.agaminggod.arenaagents.client.mixin;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.font.FontManager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(Minecraft.class)
public interface MinecraftFontManagerAccessor {
	@Accessor("fontManager")
	FontManager arenaagents$fontManager();
}
