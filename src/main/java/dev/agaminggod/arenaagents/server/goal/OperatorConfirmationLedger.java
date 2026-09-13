package dev.agaminggod.arenaagents.server.goal;

import dev.agaminggod.arenaagents.agent.AgentConstants;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/** Bounded durable confirmation state keyed by immutable goal identity. */
public final class OperatorConfirmationLedger {
	static final int SCHEMA_VERSION = 1;
	static final int MAX_ENTRIES = AgentConstants.MAX_CONFIGURED_AGENTS;
	private final Set<UUID> confirmedGoals = new HashSet<>();
	private final Runnable mutationListener;

	public OperatorConfirmationLedger() {
		this(emptySnapshot(), () -> { });
	}

	public OperatorConfirmationLedger(Snapshot snapshot, Runnable mutationListener) {
		this.mutationListener = Objects.requireNonNull(mutationListener, "mutationListener must not be null");
		for (UUID goalId : Objects.requireNonNull(snapshot, "snapshot must not be null").goalIds()) {
			if (!confirmedGoals.add(goalId)) {
				throw new IllegalArgumentException("Operator confirmations contain duplicate goal IDs");
			}
		}
	}

	public synchronized void confirm(UUID goalId) {
		UUID checked = Objects.requireNonNull(goalId, "goalId must not be null");
		if (confirmedGoals.contains(checked)) return;
		if (confirmedGoals.size() >= MAX_ENTRIES) {
			throw new IllegalArgumentException("Operator confirmation count exceeds the bounded limit");
		}
		confirmedGoals.add(checked);
		mutationListener.run();
	}

	public synchronized boolean isConfirmed(UUID goalId) {
		return confirmedGoals.contains(Objects.requireNonNull(goalId, "goalId must not be null"));
	}

	public synchronized void retainGoals(Set<UUID> goalIds) {
		Set<UUID> retained = Set.copyOf(Objects.requireNonNull(goalIds, "goalIds must not be null"));
		if (confirmedGoals.removeIf(goalId -> !retained.contains(goalId))) mutationListener.run();
	}

	public synchronized Snapshot snapshot() {
		return new Snapshot(SCHEMA_VERSION, confirmedGoals.stream().sorted().toList());
	}

	public static Snapshot emptySnapshot() {
		return new Snapshot(SCHEMA_VERSION, List.of());
	}

	public record Snapshot(int schemaVersion, List<UUID> goalIds) {
		public Snapshot {
			if (schemaVersion != SCHEMA_VERSION) {
				throw new IllegalArgumentException("Unsupported operator confirmation schema: " + schemaVersion);
			}
			goalIds = List.copyOf(Objects.requireNonNull(goalIds, "goalIds must not be null"));
			if (goalIds.size() > MAX_ENTRIES || new HashSet<>(goalIds).size() != goalIds.size()) {
				throw new IllegalArgumentException("Operator confirmations must be bounded and unique");
			}
			goalIds.forEach(goalId -> Objects.requireNonNull(goalId, "goalId must not be null"));
		}
	}
}
