package dev.agaminggod.arenaagents.server.voice;

import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.WeakHashMap;
import net.minecraft.server.MinecraftServer;

public final class VoiceConsentRegistry {
	private static final Map<MinecraftServer, Set<UUID>> ENABLED = new WeakHashMap<>();

	private VoiceConsentRegistry() {
	}

	public static synchronized void grant(MinecraftServer server, UUID playerId) {
		ENABLED.computeIfAbsent(server, ignored -> new LinkedHashSet<>()).add(playerId);
	}

	public static void playerConnected(MinecraftServer server, UUID playerId) {
		grant(server, playerId);
	}

	public static void revoke(MinecraftServer server, UUID playerId) {
		synchronized (VoiceConsentRegistry.class) {
			Set<UUID> players = ENABLED.get(server);
			if (players != null) {
				players.remove(playerId);
				if (players.isEmpty()) ENABLED.remove(server);
			}
		}
		VoiceSubsystemRuntime.cancelHumanSpeech(server, playerId);
	}

	public static void playerDisconnected(MinecraftServer server, UUID playerId) {
		revoke(server, playerId);
	}

	public static synchronized boolean granted(MinecraftServer server, UUID playerId) {
		return ENABLED.getOrDefault(server, Set.of()).contains(playerId);
	}

	public static synchronized boolean captureWhileGranted(
			MinecraftServer server,
			UUID playerId,
			Runnable capture
	) {
		java.util.Objects.requireNonNull(capture, "capture must not be null");
		if (!ENABLED.getOrDefault(server, Set.of()).contains(playerId)) return false;
		capture.run();
		return true;
	}

	public static synchronized void clear(MinecraftServer server) {
		ENABLED.remove(server);
	}
}
