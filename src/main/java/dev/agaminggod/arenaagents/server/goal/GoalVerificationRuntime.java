package dev.agaminggod.arenaagents.server.goal;

import dev.agaminggod.arenaagents.agent.AgentConstants;
import dev.agaminggod.arenaagents.agent.AgentDomainException;
import dev.agaminggod.arenaagents.agent.AgentGoal;
import dev.agaminggod.arenaagents.agent.AgentId;
import dev.agaminggod.arenaagents.agent.AgentLifecycleState;
import dev.agaminggod.arenaagents.agent.AgentRecord;
import dev.agaminggod.arenaagents.agent.AgentRegistry;
import dev.agaminggod.arenaagents.agent.AgentTransition;
import dev.agaminggod.arenaagents.agent.AgentValidators;
import dev.agaminggod.arenaagents.agent.goal.GoalPredicate;
import dev.agaminggod.arenaagents.agent.goal.GoalSpec;
import dev.agaminggod.arenaagents.agent.goal.GoalStatus;
import dev.agaminggod.arenaagents.server.runtime.GoalCompletionVerifier;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.LongSupplier;

/** Main-thread service that proactively completes goals only from server-observed facts. */
public final class GoalVerificationRuntime {
	public static final long MAX_RETRY_DELAY_TICKS = 20L;
	private final AgentRegistry registry;
	private final Function<AgentId, Optional<GoalCompletionVerifier.FactSource>> facts;
	private final LongSupplier serverTick;
	private final LongSupplier epochMillis;
	private final GoalCompletionVerifier verifier;
	private final AgentKillLedger killLedger;
	private final SurvivalProgressLedger survivalProgress;
	private final OperatorConfirmationLedger operatorConfirmations;
	private final Consumer<GoalSpec> queuedGoalValidator;
	private final Map<AgentId, VerificationFault> faults = new LinkedHashMap<>();

	public GoalVerificationRuntime(
			AgentRegistry registry,
			Function<AgentId, Optional<GoalCompletionVerifier.FactSource>> facts,
			LongSupplier serverTick,
			LongSupplier epochMillis
	) {
		this(registry, facts, serverTick, epochMillis, new AgentKillLedger(), new SurvivalProgressLedger());
	}

	public GoalVerificationRuntime(
			AgentRegistry registry,
			Function<AgentId, Optional<GoalCompletionVerifier.FactSource>> facts,
			LongSupplier serverTick,
			LongSupplier epochMillis,
			Consumer<GoalSpec> queuedGoalValidator
	) {
		this(registry, facts, serverTick, epochMillis, new AgentKillLedger(), new SurvivalProgressLedger(),
				new OperatorConfirmationLedger(), queuedGoalValidator);
	}

	public GoalVerificationRuntime(
			AgentRegistry registry,
			Function<AgentId, Optional<GoalCompletionVerifier.FactSource>> facts,
			LongSupplier serverTick,
			LongSupplier epochMillis,
			AgentKillLedger killLedger
	) {
		this(registry, facts, serverTick, epochMillis, killLedger, new SurvivalProgressLedger());
	}

	public GoalVerificationRuntime(
			AgentRegistry registry,
			Function<AgentId, Optional<GoalCompletionVerifier.FactSource>> facts,
			LongSupplier serverTick,
			LongSupplier epochMillis,
			AgentKillLedger killLedger,
			SurvivalProgressLedger survivalProgress
	) {
		this(registry, facts, serverTick, epochMillis, killLedger, survivalProgress,
				new OperatorConfirmationLedger(), ignored -> { });
	}

	public GoalVerificationRuntime(
			AgentRegistry registry,
			Function<AgentId, Optional<GoalCompletionVerifier.FactSource>> facts,
			LongSupplier serverTick,
			LongSupplier epochMillis,
			AgentKillLedger killLedger,
			SurvivalProgressLedger survivalProgress,
			OperatorConfirmationLedger operatorConfirmations
	) {
		this(registry, facts, serverTick, epochMillis, killLedger, survivalProgress, operatorConfirmations,
				ignored -> { });
	}

	public GoalVerificationRuntime(
			AgentRegistry registry,
			Function<AgentId, Optional<GoalCompletionVerifier.FactSource>> facts,
			LongSupplier serverTick,
			LongSupplier epochMillis,
			AgentKillLedger killLedger,
			SurvivalProgressLedger survivalProgress,
			OperatorConfirmationLedger operatorConfirmations,
			Consumer<GoalSpec> queuedGoalValidator
	) {
		this.registry = Objects.requireNonNull(registry, "registry must not be null");
		this.facts = Objects.requireNonNull(facts, "facts must not be null");
		this.serverTick = Objects.requireNonNull(serverTick, "serverTick must not be null");
		this.epochMillis = Objects.requireNonNull(epochMillis, "epochMillis must not be null");
		this.killLedger = Objects.requireNonNull(killLedger, "killLedger must not be null");
		this.survivalProgress = Objects.requireNonNull(survivalProgress, "survivalProgress must not be null");
		this.operatorConfirmations = Objects.requireNonNull(
				operatorConfirmations, "operatorConfirmations must not be null");
		this.queuedGoalValidator = Objects.requireNonNull(
				queuedGoalValidator, "queuedGoalValidator must not be null");
		this.verifier = new GoalCompletionVerifier(this.survivalProgress);
		synchronizeProgress();
	}

	public List<AgentTransition> tick() {
		List<AgentRecord> snapshots = synchronizeProgress();
		long tick = serverTick.getAsLong();
		long now = epochMillis.getAsLong();
		if (tick < 0L) throw new IllegalStateException("Server tick must be nonnegative");
		ArrayList<AgentTransition> transitions = new ArrayList<>();
		for (AgentRecord snapshot : snapshots) {
			AgentRecord record = registry.require(snapshot.agentId());
			if (readyToPromote(record)) {
				promoteFirstValidQueuedGoal(record, now, transitions);
				continue;
			}
			if (!record.state().isActive() || record.currentGoal().isEmpty()) continue;
			if (record.state() == dev.agaminggod.arenaagents.agent.AgentLifecycleState.ACTING) continue;
			GoalStatus status = record.currentGoal().orElseThrow().status();
			if (status != GoalStatus.ACTIVE && status != GoalStatus.RECOVERING) continue;
			GoalCompletionVerifier.VerificationResult result = verifySafely(record, tick);
			if (!result.verified()) continue;
			transitions.add(registry.satisfyGoal(record.agentId(), record.goalRevision(), result.evidence(tick), now));
		}
		List<AgentRecord> currentRecords = synchronizeProgress();
		Set<UUID> currentGoals = currentRecords.stream().flatMap(record -> record.currentGoal().stream())
				.map(AgentGoal::goalId).collect(java.util.stream.Collectors.toUnmodifiableSet());
		verifier.retainGoals(currentGoals);
		Map<AgentId, Long> currentGoalRevisions = new HashMap<>();
		for (AgentRecord record : currentRecords) {
			if (record.currentGoal().isPresent()) currentGoalRevisions.put(record.agentId(), record.goalRevision());
		}
		faults.entrySet().removeIf(entry -> !Objects.equals(
				currentGoalRevisions.get(entry.getKey()), entry.getValue().goalRevision()));
		return List.copyOf(transitions);
	}

	private void promoteFirstValidQueuedGoal(
			AgentRecord record,
			long now,
			List<AgentTransition> transitions
	) {
		while (readyToPromote(record)) {
			AgentGoal queued = record.queuedGoals().getFirst();
			try {
				queuedGoalValidator.accept(queued.spec());
			} catch (AgentDomainException exception) {
				AgentTransition rejected = registry.rejectQueuedGoal(
						record.agentId(), queued.goalId(), rejectionReason(record.lastError(), queued, exception), now);
				transitions.add(rejected);
				record = rejected.after();
				continue;
			}
			transitions.add(registry.promoteSatisfied(record.agentId(), now));
			return;
		}
	}

	private static String rejectionReason(
			String previous,
			AgentGoal queued,
			AgentDomainException exception
	) {
		String detail = exception.getMessage();
		if (detail == null || detail.isBlank()) detail = exception.code();
		String notice = "Skipped queued goal '" + queued.prompt() + "' [" + exception.code() + "]: " + detail;
		String combined = previous.isBlank() ? notice : previous + " | " + notice;
		return AgentValidators.boundedText(combined, "error", AgentConstants.MAX_ERROR_LENGTH);
	}

	public GoalCompletionVerifier.VerificationResult evaluate(AgentId agentId) {
		synchronizeProgress();
		AgentRecord record = registry.require(agentId);
		long tick = serverTick.getAsLong();
		return verifySafely(record, tick);
	}

	public GoalCompletionVerifier.VerificationResult evaluateRequest(
			AgentId agentId, long requestedRevision, String goalFingerprint
	) {
		AgentRecord record = registry.require(agentId);
		AgentGoal goal = record.currentGoal().orElseThrow(
				() -> new AgentDomainException("NO_CURRENT_GOAL", "Agent has no current goal to verify"));
		if (!goal.spec().fingerprint().equals(Objects.requireNonNull(goalFingerprint, "goalFingerprint must not be null"))) {
			throw new AgentDomainException("STALE_GOAL_FINGERPRINT", "Completion request does not match the immutable current goal");
		}
		if (record.goalRevision() == requestedRevision) return evaluate(agentId);
		if (record.goalRevision() > 0L && record.goalRevision() - 1L == requestedRevision && goal.status() == GoalStatus.SATISFIED) {
			var evidence = goal.evidence().orElseThrow();
			return new GoalCompletionVerifier.VerificationResult(
					true, requestedRevision, evidence.reasonCode(), evidence.facts());
		}
		throw new AgentDomainException("STALE_REVISION", "Completion request revision is stale");
	}

	public Optional<AgentTransition> acceptVerified(
			AgentId agentId,
			long requestedRevision,
			String goalFingerprint,
			GoalCompletionVerifier.VerificationResult result
	) {
		Objects.requireNonNull(result, "result must not be null");
		if (!result.verified()) throw new IllegalArgumentException("Only verified evidence may be accepted");
		AgentRecord record = registry.require(agentId);
		AgentGoal goal = record.currentGoal().orElseThrow(
				() -> new AgentDomainException("NO_CURRENT_GOAL", "Agent has no current goal to satisfy"));
		if (!goal.spec().fingerprint().equals(goalFingerprint)) {
			throw new AgentDomainException("STALE_GOAL_FINGERPRINT", "Verified evidence targets a stale goal");
		}
		if (record.goalRevision() > 0L && record.goalRevision() - 1L == requestedRevision && goal.status() == GoalStatus.SATISFIED) {
			return Optional.empty();
		}
		if (record.goalRevision() != requestedRevision) {
			throw new AgentDomainException("STALE_REVISION", "Verified evidence targets a stale goal revision");
		}
		return Optional.of(registry.satisfyGoal(
				agentId, requestedRevision, result.evidence(serverTick.getAsLong()), epochMillis.getAsLong()));
	}

	public void recordKill(AgentId agentId, String entityType) {
		synchronizeProgress();
		killLedger.record(agentId, entityType, epochMillis.getAsLong());
	}

	public void confirm(AgentId agentId, UUID goalId) {
		AgentRecord record = registry.require(agentId);
		UUID currentGoalId = record.currentGoal().map(AgentGoal::goalId)
				.orElseThrow(() -> new AgentDomainException("NO_CURRENT_GOAL", "Agent has no current goal to confirm"));
		if (!currentGoalId.equals(Objects.requireNonNull(goalId, "goalId must not be null"))) {
			throw new AgentDomainException("STALE_GOAL_CONFIRMATION", "Operator confirmation targets a stale goal");
		}
		operatorConfirmations.confirm(goalId);
	}

	public AgentKillLedger killLedger() {
		return killLedger;
	}

	public SurvivalProgressLedger survivalProgress() {
		return survivalProgress;
	}

	public OperatorConfirmationLedger operatorConfirmations() {
		return operatorConfirmations;
	}

	public List<VerificationFault> faults() {
		return List.copyOf(faults.values());
	}

	private List<AgentRecord> synchronizeProgress() {
		ArrayList<AgentKillLedger.KillProgressRequirement> requirements = new ArrayList<>();
		ArrayList<SurvivalProgressLedger.Requirement> survivalRequirements = new ArrayList<>();
		Set<UUID> activeGoalIds = new java.util.HashSet<>();
		List<AgentRecord> records = registry.records();
		for (AgentRecord record : records) {
			AgentGoal goal = record.currentGoal().orElse(null);
			if (goal == null || goal.status() != GoalStatus.ACTIVE && goal.status() != GoalStatus.RECOVERING) continue;
			activeGoalIds.add(goal.goalId());
			Map<KillRequirementKey, Integer> requiredByEntity = new HashMap<>();
			collectKillRequirements(goal.spec().completion(), requiredByEntity);
			for (Map.Entry<KillRequirementKey, Integer> entry : requiredByEntity.entrySet()) {
				requirements.add(new AgentKillLedger.KillProgressRequirement(
						goal.goalId(), record.agentId(), entry.getKey().entityType(),
						entry.getKey().afterGoalStart(), goal.createdAtEpochMs(), entry.getValue()));
			}
			collectSurvivalRequirements(
					goal.goalId(), record.agentId(), goal.spec().completion(), "root", survivalRequirements);
		}
		killLedger.synchronizeProgress(requirements);
		survivalProgress.synchronizeProgress(survivalRequirements);
		operatorConfirmations.retainGoals(activeGoalIds);
		return records;
	}

	private static void collectSurvivalRequirements(
			UUID goalId,
			AgentId agentId,
			GoalPredicate predicate,
			String path,
			List<SurvivalProgressLedger.Requirement> requirements
	) {
		if (predicate instanceof GoalPredicate.SurviveDuration survive) {
			requirements.add(new SurvivalProgressLedger.Requirement(goalId, agentId, path, survive.ticks()));
			return;
		}
		List<GoalPredicate> children;
		if (predicate instanceof GoalPredicate.AllOf all) children = all.predicates();
		else if (predicate instanceof GoalPredicate.AnyOf any) children = any.predicates();
		else return;
		for (int index = 0; index < children.size(); index++) {
			collectSurvivalRequirements(goalId, agentId, children.get(index), path + "." + index, requirements);
		}
	}

	private static void collectKillRequirements(
			GoalPredicate predicate, Map<KillRequirementKey, Integer> requiredByEntity
	) {
		if (predicate instanceof GoalPredicate.EntityKilledByAgent killed) {
			requiredByEntity.merge(
					new KillRequirementKey(killed.entityType(), killed.afterGoalStart()), 1, Math::addExact);
			return;
		}
		if (predicate instanceof GoalPredicate.AllOf all) {
			for (GoalPredicate child : all.predicates()) {
				collectKillRequirements(child, requiredByEntity);
			}
			return;
		}
		if (predicate instanceof GoalPredicate.AnyOf any) {
			for (GoalPredicate child : any.predicates()) {
				collectKillRequirements(child, requiredByEntity);
			}
		}
	}

	private record KillRequirementKey(String entityType, boolean afterGoalStart) { }

	private GoalCompletionVerifier.VerificationResult verifySafely(AgentRecord record, long tick) {
		VerificationFault existing = faults.get(record.agentId());
		if (existing != null && existing.goalRevision() == record.goalRevision() && tick < existing.retryAtTick()) {
			return failed(record.goalRevision(), "VERIFIER_RETRY_PENDING");
		}
		try {
			GoalCompletionVerifier.VerificationResult result = verifier.verify(
					record,
					facts.apply(record.agentId()).orElse(null),
					killLedger,
					tick,
					record.currentGoal().map(AgentGoal::goalId).filter(operatorConfirmations::isConfirmed).isPresent()
			);
			faults.remove(record.agentId());
			return result;
		} catch (RuntimeException exception) {
			int failures = existing != null && existing.goalRevision() == record.goalRevision()
					? existing.consecutiveFailures() + 1 : 1;
			long delay = Math.min(MAX_RETRY_DELAY_TICKS, 1L << Math.min(failures - 1, 30));
			String exceptionType = exception.getClass().getSimpleName();
			if (exceptionType.isBlank()) exceptionType = exception.getClass().getName();
			faults.put(record.agentId(), new VerificationFault(
					record.agentId(), record.goalRevision(), exceptionType, failures, tick + delay));
			return failed(record.goalRevision(), "VERIFIER_ERROR");
		}
	}

	private static GoalCompletionVerifier.VerificationResult failed(long revision, String reasonCode) {
		return new GoalCompletionVerifier.VerificationResult(false, revision, reasonCode, List.of());
	}

	public record VerificationFault(
			AgentId agentId,
			long goalRevision,
			String exceptionType,
			int consecutiveFailures,
			long retryAtTick
	) {
		public VerificationFault {
			Objects.requireNonNull(agentId, "agentId must not be null");
			Objects.requireNonNull(exceptionType, "exceptionType must not be null");
			if (goalRevision < 0L || consecutiveFailures <= 0 || retryAtTick < 0L) {
				throw new IllegalArgumentException("Verification fault fields are invalid");
			}
		}
	}

	private static boolean readyToPromote(AgentRecord record) {
		return record.state() == AgentLifecycleState.COMPLETED
				&& record.currentGoal().map(goal -> goal.status() == GoalStatus.SATISFIED).orElse(false)
				&& !record.queuedGoals().isEmpty();
	}
}
