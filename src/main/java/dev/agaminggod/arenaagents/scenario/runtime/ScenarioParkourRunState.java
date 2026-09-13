package dev.agaminggod.arenaagents.scenario.runtime;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/** Independent checkpoint state for every active parkour participant. */
public final class ScenarioParkourRunState {
	private final Map<String, ScenarioParkourRecovery> recoveryByAgent;
	private final Map<String, Integer> checkpointByAgent;
	private final Map<String, Integer> finishCheckpointByAgent;

	public ScenarioParkourRunState(ScenarioParkourCourse course, Map<String, Integer> laneByAgent) {
		this(course, laneByAgent, Map.of());
	}

	public ScenarioParkourRunState(
			ScenarioParkourCourse course,
			Map<String, Integer> laneByAgent,
			Map<String, Integer> restoredCheckpoints
	) {
		Objects.requireNonNull(course, "course must not be null");
		Map<String, Integer> lanes = Map.copyOf(Objects.requireNonNull(laneByAgent, "laneByAgent must not be null"));
		Map<String, Integer> restored = Map.copyOf(Objects.requireNonNull(
				restoredCheckpoints, "restoredCheckpoints must not be null"));
		if (!lanes.keySet().containsAll(restored.keySet())) {
			throw new IllegalArgumentException("restored checkpoint has no parkour participant binding");
		}
		LinkedHashMap<String, ScenarioParkourRecovery> recoveries = new LinkedHashMap<>();
		LinkedHashMap<String, Integer> checkpoints = new LinkedHashMap<>();
		LinkedHashMap<String, Integer> finishes = new LinkedHashMap<>();
		for (Map.Entry<String, Integer> entry : lanes.entrySet()) {
			String agentId = Objects.requireNonNull(entry.getKey(), "agent id must not be null");
			int laneIndex = Objects.requireNonNull(entry.getValue(), "lane index must not be null");
			if (agentId.isBlank() || laneIndex < 0 || laneIndex >= course.lanes().size()) {
				throw new IllegalArgumentException("parkour participant binding is invalid");
			}
			ScenarioParkourCourse.Lane lane = course.lanes().get(laneIndex);
			int checkpoint = restored.getOrDefault(agentId, 0);
			if (!lane.checkpointIndices().contains(checkpoint)) {
				throw new IllegalArgumentException("restored parkour checkpoint is invalid");
			}
			recoveries.put(agentId, new ScenarioParkourRecovery(lane));
			checkpoints.put(agentId, checkpoint);
			finishes.put(agentId, lane.platforms().size() - 1);
		}
		if (recoveries.isEmpty()) throw new IllegalArgumentException("parkour run must have participants");
		recoveryByAgent = Map.copyOf(recoveries);
		checkpointByAgent = checkpoints;
		finishCheckpointByAgent = Map.copyOf(finishes);
	}

	public synchronized ScenarioParkourRecovery.Decision evaluate(
			String agentId,
			double x,
			double relativeY,
			double z
	) {
		ScenarioParkourRecovery recovery = requireRecovery(agentId);
		int current = checkpointByAgent.get(agentId);
		ScenarioParkourRecovery.Decision decision = recovery.evaluate(current, x, relativeY, z);
		checkpointByAgent.put(agentId, decision.checkpointIndex());
		return decision;
	}

	public synchronized int checkpointIndex(String agentId) {
		requireRecovery(agentId);
		return checkpointByAgent.get(agentId);
	}

	public synchronized Map<String, Integer> checkpoints() {
		return Map.copyOf(checkpointByAgent);
	}

	public synchronized boolean allFinished() {
		return checkpointByAgent.entrySet().stream()
				.allMatch(entry -> entry.getValue() >= finishCheckpointByAgent.get(entry.getKey()));
	}

	public synchronized ScenarioParkourRecovery.Target recoveryTarget(String agentId) {
		return requireRecovery(agentId).target(checkpointByAgent.get(agentId));
	}

	private ScenarioParkourRecovery requireRecovery(String agentId) {
		ScenarioParkourRecovery recovery = recoveryByAgent.get(agentId);
		if (recovery == null) throw new IllegalArgumentException("unknown parkour participant " + agentId);
		return recovery;
	}
}
