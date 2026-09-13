package dev.agaminggod.arenaagents.server.voice;

import net.minecraft.server.MinecraftServer;

/** Fabric entrypoint implemented by optional voice transport addons. */
@FunctionalInterface
public interface VoiceSubsystemProvider {
	/** Original provider contract retained so addons compiled against it remain loadable. */
	VoiceSubsystem create(MinecraftServer server);

	/** Configuration-aware startup used by current cores and providers. */
	default VoiceSubsystem create(MinecraftServer server, VoiceSubsystemConfiguration configuration) {
		return create(server);
	}
}
