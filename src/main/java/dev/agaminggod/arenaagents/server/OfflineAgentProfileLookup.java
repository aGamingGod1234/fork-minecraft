package dev.agaminggod.arenaagents.server;

import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/** Scopes the remote-profile bypass to Arena Agents currently entering Carpet's fake-player factory. */
public final class OfflineAgentProfileLookup {
	private static final Map<String, UUID> PENDING = new ConcurrentHashMap<>();

	private OfflineAgentProfileLookup() {
	}

	public static void begin(String name, UUID uuid) {
		String requiredName = java.util.Objects.requireNonNull(name, "name must not be null");
		UUID requiredUuid = java.util.Objects.requireNonNull(uuid, "uuid must not be null");
		UUID existing = PENDING.putIfAbsent(requiredName, requiredUuid);
		if (existing != null && !existing.equals(requiredUuid)) {
			throw new IllegalStateException("A different Arena agent spawn already owns this player name");
		}
	}

	public static void end(String name, UUID uuid) {
		PENDING.remove(
				java.util.Objects.requireNonNull(name, "name must not be null"),
				java.util.Objects.requireNonNull(uuid, "uuid must not be null")
		);
	}

	public static Optional<UUID> requestedUuid(String name) {
		return Optional.ofNullable(PENDING.get(java.util.Objects.requireNonNull(name, "name must not be null")));
	}

	public static boolean shouldBypassRemoteLookup(UUID uuid) {
		return PENDING.containsValue(java.util.Objects.requireNonNull(uuid, "uuid must not be null"));
	}
}
