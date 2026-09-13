package dev.agaminggod.arenaagents.server;

import dev.agaminggod.arenaagents.agent.AgentId;
import dev.agaminggod.arenaagents.agent.AgentProfile;
import dev.agaminggod.arenaagents.agent.AgentRecord;
import dev.agaminggod.arenaagents.agent.AgentTransition;
import java.util.Objects;
import java.util.function.Supplier;

public interface AgentRuntimeHooks {
	AgentRuntimeHooks NO_OP = new AgentRuntimeHooks() {
	};

	default void validateProfile(AgentProfile profile) {
	}

	/** Returns true only when the agent is now safe to reference through the coordinator protocol. */
	default boolean onCreated(AgentRecord record) {
		return true;
	}

	default void onTransition(AgentTransition transition) {
	}

	default void onRemoved(AgentId agentId, long terminalRevision) {
	}

	default void onServerStopping() {
	}

	/** Serializes a local registry publication with a coordinator registry snapshot. */
	default <T> T withinPublicationBoundary(Supplier<T> publication) {
		return Objects.requireNonNull(publication, "publication must not be null").get();
	}
}
