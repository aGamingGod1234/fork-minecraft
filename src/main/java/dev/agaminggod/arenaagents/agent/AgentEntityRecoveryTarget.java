package dev.agaminggod.arenaagents.agent;

import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

public record AgentEntityRecoveryTarget(UUID entityUuid, AgentEntityLocation location) {
	public AgentEntityRecoveryTarget {
		Objects.requireNonNull(entityUuid, "entityUuid must not be null");
		Objects.requireNonNull(location, "location must not be null");
	}

	public static Optional<AgentEntityRecoveryTarget> from(AgentRecord record) {
		Objects.requireNonNull(record, "record must not be null");
		if (record.entityUuid().isEmpty() || record.entityLocation().isEmpty()) {
			return Optional.empty();
		}
		return Optional.of(new AgentEntityRecoveryTarget(
				record.entityUuid().orElseThrow(),
				record.entityLocation().orElseThrow()
		));
	}
}
