package dev.agaminggod.arenaagents.client.mixin;

import java.util.Map;
import net.minecraft.client.gui.font.FontManager;
import net.minecraft.client.gui.font.FontSet;
import net.minecraft.resources.Identifier;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(FontManager.class)
public interface FontManagerAccessor {
	@Accessor("fontSets")
	Map<Identifier, FontSet> arenaagents$fontSets();
}
