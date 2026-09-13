package dev.agaminggod.arenaagents.server.voice;

import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.WeakHashMap;
import net.minecraft.server.MinecraftServer;

/** The consent registry shipped by the pre-configuration 0.1.0 core. */
public final class VoiceConsentRegistry {
	private static final Map<MinecraftServer, Set<UUID>> DISABLED = new WeakHashMap<>();

	private VoiceConsentRegistry() {
	}

	public static synchronized void grant(MinecraftServer server, UUID playerId) {
		Set<UUID> players = DISABLED.get(server);
		if (players == null) return;
		players.remove(playerId);
		if (players.isEmpty()) DISABLED.remove(server);
	}

	public static synchronized void revoke(MinecraftServer server, UUID playerId) {
		DISABLED.computeIfAbsent(server, ignored -> new LinkedHashSet<>()).add(playerId);
	}

	public static synchronized boolean granted(MinecraftServer server, UUID playerId) {
		return !DISABLED.getOrDefault(server, Set.of()).contains(playerId);
	}

	public static synchronized void clearPlayer(MinecraftServer server, UUID playerId) {
		grant(server, playerId);
	}

	public static synchronized void clear(MinecraftServer server) {
		DISABLED.remove(server);
	}
}
