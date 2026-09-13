package dev.agaminggod.arenaagents.scenario.runtime;

import java.util.List;
import java.util.Objects;
import java.util.function.Consumer;

public final class ScenarioRosterActivator {
	public <T> void protect(List<T> contestants, Consumer<T> applyProtection) {
		List<T> ordered = List.copyOf(Objects.requireNonNull(contestants, "contestants must not be null"));
		ordered.forEach(Objects.requireNonNull(applyProtection, "applyProtection must not be null"));
	}

	public <T> void activate(
			List<T> contestants,
			Consumer<T> releaseProtection,
			Consumer<T> applyLoadout,
			Consumer<T> startGoal
	) {
		List<T> ordered = List.copyOf(Objects.requireNonNull(contestants, "contestants must not be null"));
		Consumer<T> release = Objects.requireNonNull(releaseProtection, "releaseProtection must not be null");
		Consumer<T> loadout = Objects.requireNonNull(applyLoadout, "applyLoadout must not be null");
		Consumer<T> start = Objects.requireNonNull(startGoal, "startGoal must not be null");
		ordered.forEach(release);
		ordered.forEach(loadout);
		ordered.forEach(start);
	}
}
