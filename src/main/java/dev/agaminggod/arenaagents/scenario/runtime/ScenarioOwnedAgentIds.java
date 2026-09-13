package dev.agaminggod.arenaagents.scenario.runtime;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;

/** Resolves all run-owned agents, including bindings restored only from durable state. */
public final class ScenarioOwnedAgentIds {
	private ScenarioOwnedAgentIds() {
	}

	public static List<String> forCleanup(List<String> liveAgentIds, List<String> savedAgentIds) {
		LinkedHashSet<String> result = new LinkedHashSet<>();
		result.addAll(Objects.requireNonNull(liveAgentIds, "liveAgentIds must not be null"));
		result.addAll(Objects.requireNonNull(savedAgentIds, "savedAgentIds must not be null"));
		if (result.stream().anyMatch(value -> value == null || value.isBlank())) {
			throw new IllegalArgumentException("cleanup agent ids must be nonblank");
		}
		return List.copyOf(result);
	}
}
