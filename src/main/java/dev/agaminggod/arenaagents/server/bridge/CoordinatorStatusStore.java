package dev.agaminggod.arenaagents.server.bridge;

import java.util.Map;
import java.util.Optional;
import java.util.WeakHashMap;
import net.minecraft.server.MinecraftServer;

public final class CoordinatorStatusStore {
	private static final Map<MinecraftServer, CoordinatorStatusSnapshot> STATUS = new WeakHashMap<>();

	private CoordinatorStatusStore() {
	}

	public static synchronized void update(MinecraftServer server, CoordinatorStatusSnapshot snapshot) {
		STATUS.put(server, snapshot);
	}

	public static synchronized Optional<CoordinatorStatusSnapshot> latest(MinecraftServer server) {
		return Optional.ofNullable(STATUS.get(server));
	}

	public static synchronized void clear(MinecraftServer server) {
		STATUS.remove(server);
	}
}
