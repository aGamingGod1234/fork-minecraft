package dev.agaminggod.arenaagents.scenario.runtime;

import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;

public final class ScenarioRosterReadinessBarrier {
	private final List<String> expectedIds;
	private final long timeoutTicks;

	public ScenarioRosterReadinessBarrier(List<String> expectedIds, long timeoutTicks) {
		this.expectedIds = List.copyOf(Objects.requireNonNull(expectedIds, "expectedIds must not be null"));
		if (this.expectedIds.isEmpty() || new LinkedHashSet<>(this.expectedIds).size() != this.expectedIds.size()) {
			throw new IllegalArgumentException("expectedIds must be non-empty and unique");
		}
		if (timeoutTicks <= 0L) {
			throw new IllegalArgumentException("timeoutTicks must be positive");
		}
		this.timeoutTicks = timeoutTicks;
	}

	public Assessment assess(long elapsedTicks, Collection<String> readyIds) {
		if (elapsedTicks < 0L) {
			throw new IllegalArgumentException("elapsedTicks must not be negative");
		}
		LinkedHashSet<String> ready = new LinkedHashSet<>(
				Objects.requireNonNull(readyIds, "readyIds must not be null")
		);
		List<String> missing = expectedIds.stream().filter(id -> !ready.contains(id)).toList();
		Status status = missing.isEmpty()
				? Status.READY
				: elapsedTicks >= timeoutTicks ? Status.TIMED_OUT : Status.WAITING;
		return new Assessment(status, missing);
	}

	public enum Status {
		WAITING,
		READY,
		TIMED_OUT
	}

	public record Assessment(Status status, List<String> missingIds) {
		public Assessment {
			status = Objects.requireNonNull(status, "status must not be null");
			missingIds = List.copyOf(Objects.requireNonNull(missingIds, "missingIds must not be null"));
		}
	}
}
