package dev.agaminggod.arenaagents.mixin;

import java.util.Map;
import net.minecraft.server.players.CachedUserNameToIdResolver;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(CachedUserNameToIdResolver.class)
public interface CachedUserNameToIdResolverAccessor {
	@Accessor("profilesByName")
	Map<String, ?> arenaagents$cachedProfilesByName();
}
