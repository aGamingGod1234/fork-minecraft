package dev.agaminggod.arenaagents.server;

import dev.agaminggod.arenaagents.agent.AgentId;
import dev.agaminggod.arenaagents.agent.AgentRecord;
import dev.agaminggod.arenaagents.agent.AgentRegistry;
import java.util.Objects;
import java.util.function.Consumer;

/** Deletes durable registry ownership before invoking fallible runtime cleanup and hooks. */
public final class AgentRemovalCoordinator {
	private AgentRemovalCoordinator() {
	}

	public static AgentRecord removeRegistryFirst(
			AgentRegistry registry,
			AgentId agentId,
			Runnable cleanup,
			Runnable removalHook,
			Consumer<RuntimeException> failureSink
	) {
		Objects.requireNonNull(registry, "registry must not be null");
		AgentRecord removed = registry.remove(Objects.requireNonNull(agentId, "agentId must not be null"));
		runBestEffort(cleanup, failureSink);
		runBestEffort(removalHook, failureSink);
		return removed;
	}

	private static void runBestEffort(Runnable operation, Consumer<RuntimeException> failureSink) {
		Objects.requireNonNull(operation, "operation must not be null");
		Consumer<RuntimeException> failures = Objects.requireNonNull(failureSink, "failureSink must not be null");
		try {
			operation.run();
		} catch (RuntimeException exception) {
			failures.accept(exception);
		}
	}
}
