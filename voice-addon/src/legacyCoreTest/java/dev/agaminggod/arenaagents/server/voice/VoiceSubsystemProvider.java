package dev.agaminggod.arenaagents.server.voice;

import net.minecraft.server.MinecraftServer;

/** The one-method provider API shipped by the pre-configuration 0.1.0 core. */
@FunctionalInterface
public interface VoiceSubsystemProvider {
	VoiceSubsystem create(MinecraftServer server);
}
