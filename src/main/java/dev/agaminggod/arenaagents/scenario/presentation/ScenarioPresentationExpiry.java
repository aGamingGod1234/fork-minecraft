package dev.agaminggod.arenaagents.scenario.presentation;

/** Bounded retention policy for completed build and match publications. */
public final class ScenarioPresentationExpiry {
	public static final long BUILD_TERMINAL_TTL_TICKS = 20L * 60L;
	public static final long SPECTATOR_TERMINAL_TTL_TICKS = 20L * 60L * 5L;

	private ScenarioPresentationExpiry() {
	}

	public static boolean expired(long firstTerminalTick, long currentTick, long ttlTicks) {
		if (firstTerminalTick < 0L || currentTick < firstTerminalTick || ttlTicks < 1L) {
			throw new IllegalArgumentException("publication expiry clock is invalid");
		}
		return currentTick - firstTerminalTick >= ttlTicks;
	}
}
