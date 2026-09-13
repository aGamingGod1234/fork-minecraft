package dev.agaminggod.arenaagents.scenario.runtime;

public final class ScenarioPlacementBatchPolicy {
	private final int maximumInspections;
	private final long timeBudgetNanos;

	public ScenarioPlacementBatchPolicy(int maximumInspections, long timeBudgetNanos) {
		if (maximumInspections < 1) {
			throw new IllegalArgumentException("maximumInspections must be positive");
		}
		if (timeBudgetNanos < 1L) {
			throw new IllegalArgumentException("timeBudgetNanos must be positive");
		}
		this.maximumInspections = maximumInspections;
		this.timeBudgetNanos = timeBudgetNanos;
	}

	public Window window(int startInclusive, int totalSize) {
		if (startInclusive < 0 || totalSize < startInclusive) {
			throw new IllegalArgumentException("placement progress must be within the total size");
		}
		int endExclusive = (int) Math.min((long) totalSize, (long) startInclusive + maximumInspections);
		return new Window(startInclusive, endExclusive);
	}

	public boolean shouldContinue(int inspected, long elapsedNanos) {
		if (inspected < 0 || elapsedNanos < 0L) {
			throw new IllegalArgumentException("batch progress and elapsed time must not be negative");
		}
		return inspected == 0 || inspected < maximumInspections && elapsedNanos < timeBudgetNanos;
	}

	public record Window(int startInclusive, int endExclusive) {
		public Window {
			if (startInclusive < 0 || endExclusive < startInclusive) {
				throw new IllegalArgumentException("invalid placement window");
			}
		}
	}
}
