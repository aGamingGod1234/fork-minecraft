package dev.agaminggod.arenaagents.server.goal;

import dev.agaminggod.arenaagents.agent.AgentConstants;
import dev.agaminggod.arenaagents.agent.AgentId;
import dev.agaminggod.arenaagents.agent.goal.GoalSpecCodec;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/** Bounded durable progress for active survive-duration predicate leaves. */
public final class SurvivalProgressLedger {
	static final int SCHEMA_VERSION = 1;
	static final int MAX_ENTRIES = AgentConstants.MAX_CONFIGURED_AGENTS * GoalSpecCodec.MAX_LEAF_PREDICATES;
	private final Map<Key, Progress> progress = new HashMap<>();
	private final Map<Key, Long> lastObservedTick = new HashMap<>();
	private final Runnable mutationListener;

	public SurvivalProgressLedger() {
		this(emptySnapshot(), () -> { });
	}

	public SurvivalProgressLedger(Snapshot snapshot, Runnable mutationListener) {
		this.mutationListener = Objects.requireNonNull(mutationListener, "mutationListener must not be null");
		for (ProgressEntry entry : Objects.requireNonNull(snapshot, "snapshot must not be null").entries()) {
			Key key = new Key(entry.goalId(), entry.agentId(), entry.predicatePath());
			if (progress.putIfAbsent(key, new Progress(entry.requiredTicks(), entry.observedTicks())) != null) {
				throw new IllegalArgumentException("Survival progress contains duplicate predicate entries");
			}
		}
	}

	public synchronized long observe(Requirement requirement, boolean alive, long serverTick) {
		Objects.requireNonNull(requirement, "requirement must not be null");
		if (serverTick < 0L) throw new IllegalArgumentException("serverTick must be nonnegative");
		Key key = new Key(requirement.goalId(), requirement.agentId(), requirement.predicatePath());
		Progress previous = progress.get(key);
		if (previous == null || previous.requiredTicks() != requirement.requiredTicks()) {
			if (progress.size() >= MAX_ENTRIES && previous == null) {
				throw new IllegalArgumentException("Active survival progress exceeds the bounded limit");
			}
			previous = new Progress(requirement.requiredTicks(), 0L);
		}
		long observed = previous.observedTicks();
		Long lastTick = lastObservedTick.get(key);
		if (!alive) {
			observed = 0L;
		} else if ((lastTick == null || lastTick.longValue() != serverTick)
				&& observed < requirement.requiredTicks()) {
			observed++;
		}
		Progress next = new Progress(requirement.requiredTicks(), observed);
		lastObservedTick.put(key, serverTick);
		if (!next.equals(progress.put(key, next))) mutationListener.run();
		return observed;
	}

	public synchronized void synchronizeProgress(List<Requirement> requirements) {
		Objects.requireNonNull(requirements, "requirements must not be null");
		Map<Key, Long> desired = new HashMap<>();
		for (Requirement requirement : requirements) {
			Key key = new Key(requirement.goalId(), requirement.agentId(), requirement.predicatePath());
			Long duplicate = desired.putIfAbsent(key, requirement.requiredTicks());
			if (duplicate != null && duplicate.longValue() != requirement.requiredTicks()) {
				throw new IllegalArgumentException("Survival predicate identity has conflicting durations");
			}
		}
		if (desired.size() > MAX_ENTRIES) {
			throw new IllegalArgumentException("Active survival progress exceeds the bounded limit");
		}
		Map<Key, Progress> next = new HashMap<>();
		for (Map.Entry<Key, Long> entry : desired.entrySet()) {
			Progress previous = progress.get(entry.getKey());
			long observed = previous == null || previous.requiredTicks() != entry.getValue()
					? 0L : Math.min(previous.observedTicks(), entry.getValue());
			next.put(entry.getKey(), new Progress(entry.getValue(), observed));
		}
		if (!next.equals(progress)) {
			progress.clear();
			progress.putAll(next);
			lastObservedTick.keySet().retainAll(next.keySet());
			mutationListener.run();
		}
	}

	public synchronized void resetAgent(AgentId agentId) {
		AgentId resetAgent = Objects.requireNonNull(agentId, "agentId must not be null");
		boolean changed = false;
		for (Map.Entry<Key, Progress> entry : progress.entrySet()) {
			if (!entry.getKey().agentId().equals(resetAgent) || entry.getValue().observedTicks() == 0L) continue;
			entry.setValue(new Progress(entry.getValue().requiredTicks(), 0L));
			changed = true;
		}
		lastObservedTick.keySet().removeIf(key -> key.agentId().equals(resetAgent));
		if (changed) mutationListener.run();
	}

	public synchronized void retainGoals(Set<UUID> goalIds) {
		Set<UUID> retained = Set.copyOf(
				Objects.requireNonNull(goalIds, "goalIds must not be null"));
		if (progress.keySet().removeIf(key -> !retained.contains(key.goalId()))) {
			lastObservedTick.keySet().retainAll(progress.keySet());
			mutationListener.run();
		}
	}

	public synchronized Snapshot snapshot() {
		List<ProgressEntry> entries = progress.entrySet().stream()
				.sorted(Comparator.comparing((Map.Entry<Key, Progress> entry) -> entry.getKey().goalId().toString())
						.thenComparing(entry -> entry.getKey().agentId().toString())
						.thenComparing(entry -> entry.getKey().predicatePath()))
				.map(entry -> new ProgressEntry(
						entry.getKey().goalId(), entry.getKey().agentId(), entry.getKey().predicatePath(),
						entry.getValue().requiredTicks(), entry.getValue().observedTicks()))
				.toList();
		return new Snapshot(SCHEMA_VERSION, entries);
	}

	public static Snapshot emptySnapshot() {
		return new Snapshot(SCHEMA_VERSION, List.of());
	}

	private record Key(UUID goalId, AgentId agentId, String predicatePath) { }
	private record Progress(long requiredTicks, long observedTicks) { }

	public record Requirement(UUID goalId, AgentId agentId, String predicatePath, long requiredTicks) {
		public Requirement {
			Objects.requireNonNull(goalId, "goalId must not be null");
			Objects.requireNonNull(agentId, "agentId must not be null");
			predicatePath = validPath(predicatePath);
			if (requiredTicks <= 0L) throw new IllegalArgumentException("requiredTicks must be positive");
		}
	}

	public record ProgressEntry(
			UUID goalId, AgentId agentId, String predicatePath, long requiredTicks, long observedTicks
	) {
		public ProgressEntry {
			new Requirement(goalId, agentId, predicatePath, requiredTicks);
			if (observedTicks < 0L || observedTicks > requiredTicks) {
				throw new IllegalArgumentException("observedTicks must be between zero and requiredTicks");
			}
		}
	}

	public record Snapshot(int schemaVersion, List<ProgressEntry> entries) {
		public Snapshot {
			if (schemaVersion != SCHEMA_VERSION) {
				throw new IllegalArgumentException("Unsupported survival progress schema: " + schemaVersion);
			}
			entries = List.copyOf(Objects.requireNonNull(entries, "entries must not be null"));
			if (entries.size() > MAX_ENTRIES) {
				throw new IllegalArgumentException("Survival progress exceeds the bounded entry limit");
			}
		}
	}

	private static String validPath(String value) {
		String path = Objects.requireNonNull(value, "predicatePath must not be null");
		if (!path.matches("root(?:\\.[0-9]{1,2}){0,4}")) {
			throw new IllegalArgumentException("predicatePath is invalid");
		}
		return path;
	}
}
