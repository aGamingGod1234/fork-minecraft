package dev.agaminggod.arenaagents.scenario;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

public final class ScenarioSession {
	private final ScenarioSessionConfig config;
	private final List<ScenarioSpawn> spawns;
	private final ArrayList<ScenarioEvent> evidence = new ArrayList<>();
	private final ArrayList<ScenarioScoreChange> scoreAudit = new ArrayList<>();
	private final LinkedHashMap<String, Double> scores = new LinkedHashMap<>();
	private ScenarioSessionState state = ScenarioSessionState.PREPARING;
	private String completionReason;
	private long lastElapsedTick = -1L;

	public ScenarioSession(ScenarioSessionConfig config) {
		this(config, new ScenarioSpawnAllocator());
	}

	public ScenarioSession(ScenarioSessionConfig config, ScenarioSpawnAllocator spawnAllocator) {
		this.config = Objects.requireNonNull(config, "config must not be null");
		this.spawns = Objects.requireNonNull(spawnAllocator, "spawnAllocator must not be null").allocate(config);
		initializeScores();
	}

	public ScenarioSessionConfig config() {
		return config;
	}

	public synchronized ScenarioSessionState state() {
		return state;
	}

	public List<ScenarioSpawn> spawns() {
		return spawns;
	}

	public synchronized List<ScenarioEvent> evidence() {
		return List.copyOf(evidence);
	}

	public synchronized List<ScenarioScoreChange> scoreAudit() {
		return List.copyOf(scoreAudit);
	}

	public synchronized Map<String, Double> scores() {
		return Map.copyOf(scores);
	}

	public synchronized Optional<String> completionReason() {
		return Optional.ofNullable(completionReason);
	}

	public synchronized long lastElapsedTick() {
		return lastElapsedTick;
	}

	public static ScenarioSession restore(
			ScenarioSessionConfig config,
			ScenarioSessionState state,
			Map<String, Double> restoredScores,
			Optional<String> completionReason,
			long lastElapsedTick
	) {
		ScenarioSession restored = new ScenarioSession(config);
		Objects.requireNonNull(state, "state must not be null");
		Map<String, Double> copiedScores = Map.copyOf(Objects.requireNonNull(restoredScores, "scores must not be null"));
		if (!copiedScores.keySet().equals(restored.scores.keySet())) {
			throw ScenarioValidators.failure("INVALID_SCENARIO_SNAPSHOT", "snapshot scores must match the participant roster");
		}
		for (double score : copiedScores.values()) {
			if (!Double.isFinite(score)) {
				throw ScenarioValidators.failure("INVALID_SCENARIO_SNAPSHOT", "snapshot scores must be finite");
			}
		}
		if (lastElapsedTick < -1L) {
			throw ScenarioValidators.failure("INVALID_SCENARIO_SNAPSHOT", "lastElapsedTick must be at least -1");
		}
		Optional<String> normalizedReason = Objects.requireNonNull(completionReason, "completionReason must not be null")
				.map(reason -> ScenarioValidators.text(reason, "completion reason", 320));
		if (state.terminal() != normalizedReason.isPresent()) {
			throw ScenarioValidators.failure(
					"INVALID_SCENARIO_SNAPSHOT",
					"terminal snapshot state and completion reason must agree"
			);
		}
		restored.state = state;
		restored.scores.clear();
		restored.scores.putAll(copiedScores);
		restored.completionReason = normalizedReason.orElse(null);
		restored.lastElapsedTick = lastElapsedTick;
		return restored;
	}

	public synchronized Optional<ScenarioPhase> phaseAt(long elapsedTick) {
		return config.preset().phaseAt(elapsedTick);
	}

	public synchronized void markReady(long elapsedTick) {
		transition(ScenarioSessionState.READY, elapsedTick, "Arena prepared and roster validated");
	}

	public synchronized void beginCountdown(long elapsedTick) {
		transition(ScenarioSessionState.COUNTDOWN, elapsedTick, "Synchronized countdown started");
	}

	public synchronized void start(long elapsedTick) {
		transition(ScenarioSessionState.RUNNING, elapsedTick, "Scenario running");
	}

	public synchronized void pause(long elapsedTick) {
		transition(ScenarioSessionState.PAUSED, elapsedTick, "Scenario paused");
	}

	public synchronized void pauseForRecovery(long elapsedTick) {
		if (state == ScenarioSessionState.PAUSED_RECOVERY) return;
		transition(ScenarioSessionState.PAUSED_RECOVERY, elapsedTick, "Scenario paused for recovery reconciliation");
	}

	public synchronized void resume(long elapsedTick) {
		transition(ScenarioSessionState.RUNNING, elapsedTick, "Scenario resumed");
	}

	public synchronized boolean resumeRecovery(long elapsedTick) {
		if (state == ScenarioSessionState.RUNNING) return false;
		if (state != ScenarioSessionState.PAUSED_RECOVERY) {
			throw ScenarioValidators.failure("INVALID_SESSION_TRANSITION", "scenario is not paused for recovery");
		}
		transition(ScenarioSessionState.RUNNING, elapsedTick, "Scenario recovery reconciled");
		return true;
	}

	public synchronized void finish(long elapsedTick, String reason) {
		if (state == ScenarioSessionState.FINISHED || state == ScenarioSessionState.FAILED) {
			return;
		}
		String normalizedReason = ScenarioValidators.text(reason, "completion reason", 320);
		requireValidTick(elapsedTick);
		transition(ScenarioSessionState.FINISHED, elapsedTick, normalizedReason);
		completionReason = normalizedReason;
	}

	public synchronized void fail(long elapsedTick, String reason) {
		if (state == ScenarioSessionState.FAILED || state == ScenarioSessionState.FINISHED) {
			return;
		}
		String normalizedReason = ScenarioValidators.text(reason, "failure reason", 320);
		requireValidTick(elapsedTick);
		transition(ScenarioSessionState.FAILED, elapsedTick, normalizedReason);
		completionReason = normalizedReason;
	}

	public synchronized void stop(long elapsedTick, String reason) {
		if (state.terminal()) {
			return;
		}
		finish(elapsedTick, reason);
	}

	public synchronized void reset(long elapsedTick) {
		if (state == ScenarioSessionState.PREPARING) {
			return;
		}
		requireValidTick(elapsedTick);
		evidence.clear();
		scoreAudit.clear();
		scores.clear();
		initializeScores();
		state = ScenarioSessionState.PREPARING;
		completionReason = null;
		lastElapsedTick = -1L;
		appendEvidence(
				elapsedTick,
				ScenarioEventType.SESSION_STATE,
				Optional.empty(),
				Optional.empty(),
				"session-reset",
				0.0D,
				"Session reset to pristine preparation state",
				Map.of("state", state.name())
		);
	}

	public synchronized ScenarioEvent record(
			long elapsedTick,
			ScenarioEventType type,
			Optional<String> participantId,
			Optional<String> targetId,
			String metric,
			double value,
			String description,
			Map<String, String> attributes
	) {
		requireActiveForEvidence();
		participantId.ifPresent(config::requireParticipant);
		return appendEvidence(
				elapsedTick,
				type,
				participantId,
				targetId,
				metric,
				value,
				description,
				attributes
		);
	}

	public synchronized ScenarioScoreChange award(
			long elapsedTick,
			String participantId,
			String ruleId,
			double points,
			String reason,
			Map<String, String> attributes
	) {
		requireActiveForEvidence();
		ScenarioParticipant participant = config.requireParticipant(participantId);
		ScenarioScoreRule rule = config.preset().requireScoreRule(ruleId);
		if (!Double.isFinite(points)) {
			throw ScenarioValidators.failure("INVALID_SCORE", "score points must be finite");
		}
		String normalizedReason = ScenarioValidators.text(reason, "score reason", 320);
		ScenarioEvent event = appendEvidence(
				elapsedTick,
				ScenarioEventType.SCORE_CHANGED,
				Optional.of(participant.id()),
				Optional.empty(),
				rule.evidenceMetric(),
				points,
				normalizedReason,
				attributes
		);
		scores.compute(participant.id(), (ignored, score) -> score + points);
		ScenarioScoreChange change = new ScenarioScoreChange(
				participant.id(),
				rule.id(),
				points,
				normalizedReason,
				event.sequence()
		);
		scoreAudit.add(change);
		return change;
	}

	private void transition(ScenarioSessionState target, long elapsedTick, String description) {
		if (!transitionAllowed(state, target)) {
			throw ScenarioValidators.failure(
					"INVALID_SESSION_TRANSITION",
					"cannot move scenario session from " + state + " to " + target
			);
		}
		requireValidTick(elapsedTick);
		ScenarioSessionState previous = state;
		state = target;
		appendEvidence(
				elapsedTick,
				ScenarioEventType.SESSION_STATE,
				Optional.empty(),
				Optional.empty(),
				"session-state",
				0.0D,
				description,
				Map.of("from", previous.name(), "to", target.name())
		);
	}

	private static boolean transitionAllowed(ScenarioSessionState from, ScenarioSessionState to) {
		return switch (from) {
			case PREPARING -> to == ScenarioSessionState.READY
					|| to == ScenarioSessionState.FINISHED
					|| to == ScenarioSessionState.FAILED;
			case READY -> to == ScenarioSessionState.COUNTDOWN
					|| to == ScenarioSessionState.FINISHED
					|| to == ScenarioSessionState.FAILED;
			case COUNTDOWN -> to == ScenarioSessionState.RUNNING
					|| to == ScenarioSessionState.FINISHED
					|| to == ScenarioSessionState.FAILED;
			case RUNNING -> to == ScenarioSessionState.PAUSED
					|| to == ScenarioSessionState.PAUSED_RECOVERY
					|| to == ScenarioSessionState.FINISHED
					|| to == ScenarioSessionState.FAILED;
			case PAUSED -> to == ScenarioSessionState.RUNNING
					|| to == ScenarioSessionState.PAUSED_RECOVERY
					|| to == ScenarioSessionState.FINISHED
					|| to == ScenarioSessionState.FAILED;
			case PAUSED_RECOVERY -> to == ScenarioSessionState.RUNNING
					|| to == ScenarioSessionState.FINISHED
					|| to == ScenarioSessionState.FAILED;
			case FINISHED, FAILED -> false;
		};
	}

	private ScenarioEvent appendEvidence(
			long elapsedTick,
			ScenarioEventType type,
			Optional<String> participantId,
			Optional<String> targetId,
			String metric,
			double value,
			String description,
			Map<String, String> attributes
	) {
		requireValidTick(elapsedTick);
		ScenarioEvent event = new ScenarioEvent(
				evidence.size(),
				elapsedTick,
				type,
				participantId,
				targetId,
				metric,
				value,
				description,
				attributes
		);
		evidence.add(event);
		lastElapsedTick = elapsedTick;
		return event;
	}

	private void requireValidTick(long elapsedTick) {
		if (elapsedTick < 0L) {
			throw ScenarioValidators.failure("INVALID_EVENT", "scenario evidence tick must not be negative");
		}
		if (elapsedTick < lastElapsedTick) {
			throw ScenarioValidators.failure("STALE_EVENT_TICK", "scenario evidence ticks must not move backwards");
		}
	}

	private void requireActiveForEvidence() {
		if (state != ScenarioSessionState.RUNNING && state != ScenarioSessionState.PAUSED) {
			throw ScenarioValidators.failure(
					"SESSION_NOT_ACTIVE",
					"scenario evidence can only be recorded while running or paused"
			);
		}
	}

	private void initializeScores() {
		for (ScenarioParticipant participant : config.participants()) {
			scores.put(participant.id(), 0.0D);
		}
	}
}
