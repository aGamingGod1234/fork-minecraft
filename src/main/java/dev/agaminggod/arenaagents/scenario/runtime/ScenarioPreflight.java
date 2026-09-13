package dev.agaminggod.arenaagents.scenario.runtime;

import dev.agaminggod.arenaagents.server.bridge.CoordinatorStatusSnapshot;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

public final class ScenarioPreflight {
	public static final long STATUS_MAXIMUM_AGE_MS = 2_500L;
	public static final long FIRST_WAVE_DEADLINE_MS = 75_000L;

	private ScenarioPreflight() {
	}

	public static Verdict assess(Input input) {
		Objects.requireNonNull(input, "input must not be null");
		if (input.nowEpochMs() - input.firstWaveStartedAtEpochMs() > FIRST_WAVE_DEADLINE_MS) return Verdict.failed("FIRST_WAVE_DEADLINE");
		CoordinatorStatusSnapshot status = input.status().orElse(null);
		if (status == null || !status.fresh(input.nowEpochMs(), STATUS_MAXIMUM_AGE_MS)) return Verdict.waiting("COORDINATOR_STATUS_STALE");
		if (!status.reconciled()) return Verdict.waiting("COORDINATOR_NOT_RECONCILED");
		if (!input.expectedDimension().equals(input.actualDimension())) return Verdict.failed("DIMENSION_MISMATCH");
		if (!input.resetVerified()) return Verdict.failed("RESET_NOT_VERIFIED");
		if (!input.expectedResetHash().equals(input.verifiedResetHash())) return Verdict.failed("RESET_HASH_MISMATCH");
		for (RequiredProfile profile : input.requiredProfiles()) {
			if (!status.supports(
					profile.agentId(), profile.provider(), profile.model(),
					profile.reasoningEffort(), profile.serviceTier())) {
				return Verdict.waiting("REQUIRED_PROFILE_NOT_READY");
			}
		}
		int rosterSize = input.requiredProfiles().size();
		if (status.rosterReadyCount() < rosterSize) return Verdict.waiting("ROSTER_NOT_READY");
		if (status.scheduler().availableCapacity() < rosterSize) return Verdict.waiting("SCHEDULER_HEADROOM");
		Set<String> requiredHealth = new HashSet<>();
		for (RequiredProfile profile : input.requiredProfiles()) {
			for (String operation : List.of("create_agent", "decide")) {
				String identity = profile.provider() + "\u0000" + profile.model() + "\u0000" + operation;
				if (!requiredHealth.add(identity)) continue;
				CoordinatorStatusSnapshot.CircuitHealth health = status.circuits().stream()
						.filter(candidate -> candidate.provider().equals(profile.provider())
								&& candidate.model().equals(profile.model()) && candidate.operation().equals(operation))
						.findFirst().orElse(null);
				if (health == null) return Verdict.waiting("PROVIDER_HEALTH_UNKNOWN");
				if (!health.circuit().equals("closed")) return Verdict.waiting("PROVIDER_CIRCUIT_" + health.circuit().toUpperCase(java.util.Locale.ROOT));
			}
		}
		return Verdict.ready();
	}

	public record Input(
			Optional<CoordinatorStatusSnapshot> status,
			List<RequiredProfile> requiredProfiles,
			String expectedDimension,
			String actualDimension,
			String expectedResetHash,
			String verifiedResetHash,
			boolean resetVerified,
			long firstWaveStartedAtEpochMs,
			long nowEpochMs
	) {
		public Input {
			status = Objects.requireNonNull(status, "status must not be null");
			requiredProfiles = List.copyOf(Objects.requireNonNull(requiredProfiles, "requiredProfiles must not be null"));
			expectedDimension = nonblank(expectedDimension, "expectedDimension");
			actualDimension = nonblank(actualDimension, "actualDimension");
			expectedResetHash = nonblank(expectedResetHash, "expectedResetHash");
			verifiedResetHash = nonblank(verifiedResetHash, "verifiedResetHash");
			if (requiredProfiles.isEmpty() || requiredProfiles.size() > CoordinatorStatusSnapshot.MAX_PROFILES) throw new IllegalArgumentException("requiredProfiles must contain 1-16 entries");
			if (firstWaveStartedAtEpochMs < 0L || nowEpochMs < firstWaveStartedAtEpochMs) throw new IllegalArgumentException("invalid preflight clock");
		}
	}

	public record RequiredProfile(
			String agentId,
			String provider,
			String model,
			String reasoningEffort,
			String serviceTier
	) {
		public RequiredProfile(String agentId, String provider, String model, String reasoningEffort) {
			this(agentId, provider, model, reasoningEffort, "priority");
		}

		public RequiredProfile {
			agentId = nonblank(agentId, "agentId");
			provider = nonblank(provider, "provider");
			model = nonblank(model, "model");
			reasoningEffort = nonblank(reasoningEffort, "reasoningEffort");
			serviceTier = nonblank(serviceTier, "serviceTier");
		}
	}

	public record Verdict(Status status, String code) {
		public Verdict {
			Objects.requireNonNull(status, "status must not be null");
			code = nonblank(code, "code");
		}

		public static Verdict ready() { return new Verdict(Status.READY, "READY"); }
		public static Verdict waiting(String code) { return new Verdict(Status.WAITING, code); }
		public static Verdict failed(String code) { return new Verdict(Status.FAILED, code); }
	}

	public enum Status { READY, WAITING, FAILED }

	private static String nonblank(String value, String field) {
		Objects.requireNonNull(value, field + " must not be null");
		if (value.isBlank()) throw new IllegalArgumentException(field + " must be nonblank");
		return value;
	}
}
