package dev.agaminggod.arenaagents.server.bridge;

import java.util.List;
import java.util.Objects;

public record CoordinatorStatusSnapshot(
		boolean reconciled,
		List<SupportedProfile> profiles,
		int supportedProfileCount,
		int rosterReadyCount,
		int rosterCount,
		SchedulerStatus scheduler,
		List<CircuitHealth> circuits,
		List<LatencyHealth> latencies,
		long bridgeSessionEpoch,
		String runtimeGeneration,
		List<ComponentRecovery> components,
		long receivedAtEpochMs
) {
	public static final int MAX_PROFILES = 16;
	public static final int MAX_CIRCUITS = 32;
	public static final int MAX_LATENCIES = 16;
	public static final int MAX_COMPONENTS = 32;

	public CoordinatorStatusSnapshot(
			boolean reconciled,
			List<SupportedProfile> profiles,
			int supportedProfileCount,
			int rosterReadyCount,
			int rosterCount,
			SchedulerStatus scheduler,
			List<CircuitHealth> circuits,
			long receivedAtEpochMs
	) {
		this(reconciled, profiles, supportedProfileCount, rosterReadyCount, rosterCount, scheduler, circuits, List.of(), 0L, null, List.of(), receivedAtEpochMs);
	}

	public CoordinatorStatusSnapshot(
			boolean reconciled,
			List<SupportedProfile> profiles,
			int supportedProfileCount,
			int rosterReadyCount,
			int rosterCount,
			SchedulerStatus scheduler,
			List<CircuitHealth> circuits,
			List<LatencyHealth> latencies,
			long receivedAtEpochMs
	) {
		this(reconciled, profiles, supportedProfileCount, rosterReadyCount, rosterCount, scheduler, circuits, latencies, 0L, null, List.of(), receivedAtEpochMs);
	}

	public CoordinatorStatusSnapshot {
		profiles = List.copyOf(Objects.requireNonNull(profiles, "profiles must not be null"));
		circuits = List.copyOf(Objects.requireNonNull(circuits, "circuits must not be null"));
		latencies = List.copyOf(Objects.requireNonNull(latencies, "latencies must not be null"));
		components = List.copyOf(Objects.requireNonNull(components, "components must not be null"));
		scheduler = Objects.requireNonNull(scheduler, "scheduler must not be null");
		if (profiles.size() > MAX_PROFILES || circuits.size() > MAX_CIRCUITS || latencies.size() > MAX_LATENCIES || components.size() > MAX_COMPONENTS) throw new IllegalArgumentException("coordinator status exceeds bounded entries");
		if (supportedProfileCount != profiles.size()) throw new IllegalArgumentException("supported profile count does not match profiles");
		if (profiles.stream().map(SupportedProfile::agentId).distinct().count() != profiles.size()) throw new IllegalArgumentException("supported profile identities must be unique");
		if (circuits.stream().map(health -> health.provider() + "\u0000" + health.model() + "\u0000" + health.operation()).distinct().count() != circuits.size()) {
			throw new IllegalArgumentException("circuit identities must be unique");
		}
		if (latencies.stream().map(LatencyHealth::operation).distinct().count() != latencies.size()) {
			throw new IllegalArgumentException("latency operations must be unique");
		}
		if (components.stream().map(ComponentRecovery::component).distinct().count() != components.size()) {
			throw new IllegalArgumentException("component identities must be unique");
		}
		if (rosterReadyCount < 0 || rosterCount < 0 || rosterReadyCount > rosterCount || rosterCount > MAX_PROFILES) throw new IllegalArgumentException("invalid roster counts");
		if (bridgeSessionEpoch < 0L || receivedAtEpochMs < 0L) throw new IllegalArgumentException("status epochs must be non-negative");
		if (runtimeGeneration != null && !runtimeGeneration.matches("[0-9a-f]{64}")) throw new IllegalArgumentException("runtimeGeneration must be a lowercase SHA-256 value");
	}

	public boolean fresh(long nowEpochMs, long maximumAgeMs) {
		return nowEpochMs >= receivedAtEpochMs && maximumAgeMs >= 0L && nowEpochMs - receivedAtEpochMs <= maximumAgeMs;
	}

	public boolean supports(String agentId, String provider, String model, String reasoningEffort) {
		return profiles.stream().anyMatch(profile -> profile.agentId().equals(agentId)
				&& profile.provider().equals(provider)
				&& profile.model().equals(model)
				&& profile.reasoningEffort().equals(reasoningEffort));
	}

	public record SupportedProfile(String agentId, String provider, String model, String reasoningEffort, String serviceTier) {
		public SupportedProfile(String agentId, String provider, String model, String reasoningEffort) {
			this(agentId, provider, model, reasoningEffort, "priority");
		}

		public SupportedProfile {
			agentId = nonblank(agentId, "agentId");
			provider = nonblank(provider, "provider");
			model = nonblank(model, "model");
			reasoningEffort = nonblank(reasoningEffort, "reasoningEffort");
			serviceTier = nonblank(serviceTier, "serviceTier");
		}
	}

	public boolean supports(String agentId, String provider, String model, String reasoningEffort, String serviceTier) {
		return profiles.stream().anyMatch(profile -> profile.agentId().equals(agentId)
				&& profile.provider().equals(provider)
				&& profile.model().equals(model)
				&& profile.reasoningEffort().equals(reasoningEffort)
				&& profile.serviceTier().equals(serviceTier));
	}

	public record ComponentRecovery(
			String component,
			String state,
			String fallbackMode,
			String boundary,
			String failureCode,
			int consecutiveFailureCount,
			Long nextProbeAtEpochMs,
			long generation,
			Long lastRecoveryAtEpochMs
	) {
		public ComponentRecovery {
			component = nonblank(component, "component");
			state = nonblank(state, "state");
			if (!List.of("ready", "degraded", "backoff", "blocked_retryable", "unknown").contains(state)) throw new IllegalArgumentException("invalid component state");
			fallbackMode = nullableBounded(fallbackMode, "fallbackMode");
			boundary = nullableBounded(boundary, "boundary");
			failureCode = nullableBounded(failureCode, "failureCode");
			if (consecutiveFailureCount < 0 || generation < 0L || nextProbeAtEpochMs != null && nextProbeAtEpochMs < 0L
					|| lastRecoveryAtEpochMs != null && lastRecoveryAtEpochMs < 0L) throw new IllegalArgumentException("invalid component recovery counters");
		}
	}

	public record SchedulerStatus(int active, int pending, int maxConcurrent, int maxPending, boolean warning, int hardConcurrentLimit) {
		public SchedulerStatus(int active, int pending, int maxConcurrent, int maxPending, boolean warning) {
			this(active, pending, maxConcurrent, maxPending, warning, maxConcurrent);
		}

		public SchedulerStatus {
			long totalCapacity = (long) maxConcurrent + maxPending;
			if (active < 0 || pending < 0 || maxConcurrent < 1 || maxPending < 0
					|| hardConcurrentLimit < maxConcurrent || hardConcurrentLimit > MAX_PROFILES
					|| active > hardConcurrentLimit || pending > maxPending || (long) active + pending > totalCapacity
					|| totalCapacity > MAX_PROFILES) {
				throw new IllegalArgumentException("invalid scheduler status");
			}
		}

		public long availableCapacity() {
			return (long) maxConcurrent + maxPending - active - pending;
		}
	}

	public record CircuitHealth(String provider, String model, String operation, int count, int p50Ms, int p95Ms, double failureRate, String circuit) {
		public CircuitHealth {
			provider = nonblank(provider, "provider");
			model = nonblank(model, "model");
			operation = nonblank(operation, "operation");
			circuit = nonblank(circuit, "circuit");
			if (!List.of("closed", "open", "half_open").contains(circuit)) throw new IllegalArgumentException("invalid circuit state");
			if (count < 0 || p50Ms < 0 || p95Ms < 0 || !Double.isFinite(failureRate) || failureRate < 0.0 || failureRate > 1.0) {
				throw new IllegalArgumentException("invalid circuit health");
			}
		}
	}

	public record LatencyHealth(String operation, int count, double p50Ms, double p95Ms) {
		public LatencyHealth {
			operation = nonblank(operation, "operation");
			if (count < 0 || !Double.isFinite(p50Ms) || !Double.isFinite(p95Ms) || p50Ms < 0 || p95Ms < 0) throw new IllegalArgumentException("invalid latency health");
		}
	}

	private static String nonblank(String value, String field) {
		Objects.requireNonNull(value, field + " must not be null");
		if (value.isBlank() || value.length() > 256) throw new IllegalArgumentException(field + " must be 1-256 characters");
		return value;
	}

	private static String nullableBounded(String value, String field) {
		return value == null ? null : nonblank(value, field);
	}
}
