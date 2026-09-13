package dev.agaminggod.arenaagents.server.runtime;

import dev.agaminggod.arenaagents.agent.AgentId;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

public final class ResourceLeaseManager {
	private static final int MAX_LEASES = 256;
	private static final long MAX_LEASE_MS = 30_000L;
	private final Map<String, Lease> leases = new HashMap<>();

	public synchronized boolean acquire(String resourceKey, AgentId owner, long nowEpochMs, long requestedMs) {
		cleanup(nowEpochMs);
		String key = requireKey(resourceKey);
		Lease current = leases.get(key);
		if (current != null && !current.owner().equals(owner)) return false;
		if (current == null && leases.size() >= MAX_LEASES) return false;
		leases.put(key, new Lease(
				Objects.requireNonNull(owner),
				new ElapsedTimeAccumulator(nowEpochMs),
				Math.clamp(requestedMs, 1L, MAX_LEASE_MS)));
		return true;
	}

	public synchronized void release(String resourceKey, AgentId owner) {
		leases.computeIfPresent(requireKey(resourceKey), (key, lease) -> lease.owner().equals(owner) ? null : lease);
	}

	public synchronized void releaseAll(AgentId owner) {
		leases.entrySet().removeIf(entry -> entry.getValue().owner().equals(owner));
	}

	public synchronized void cleanup(long nowEpochMs) {
		leases.entrySet().removeIf(entry -> entry.getValue().expired(nowEpochMs));
	}

	public synchronized boolean isHeldBy(String resourceKey, AgentId owner, long nowEpochMs) {
		cleanup(nowEpochMs);
		Lease lease = leases.get(requireKey(resourceKey));
		return lease != null && lease.owner().equals(Objects.requireNonNull(owner));
	}

	private static String requireKey(String value) {
		if (value == null || value.isBlank() || value.length() > 256) throw new IllegalArgumentException("resourceKey is invalid");
		return value;
	}

	private record Lease(AgentId owner, ElapsedTimeAccumulator elapsed, long durationMs) {
		private boolean expired(long nowEpochMs) {
			return elapsed.advance(nowEpochMs) >= durationMs;
		}
	}
}
