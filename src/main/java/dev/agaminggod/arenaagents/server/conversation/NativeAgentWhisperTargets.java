package dev.agaminggod.arenaagents.server.conversation;

import dev.agaminggod.arenaagents.agent.AgentDomainException;
import dev.agaminggod.arenaagents.agent.AgentId;
import dev.agaminggod.arenaagents.agent.AgentRecord;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

public final class NativeAgentWhisperTargets {
	private final Map<UUID, AgentId> agentIdsByEntity;

	private NativeAgentWhisperTargets(Map<UUID, AgentId> agentIdsByEntity) {
		this.agentIdsByEntity = Map.copyOf(agentIdsByEntity);
	}

	public static NativeAgentWhisperTargets fromRecords(List<AgentRecord> records) {
		LinkedHashMap<UUID, AgentId> index = new LinkedHashMap<>();
		for (AgentRecord record : List.copyOf(Objects.requireNonNull(records, "records must not be null"))) {
			Objects.requireNonNull(record, "records must not contain null").entityUuid().ifPresent(entityId -> {
				AgentId duplicate = index.putIfAbsent(entityId, record.agentId());
				if (duplicate != null && !duplicate.equals(record.agentId())) {
					throw new AgentDomainException("DUPLICATE_AGENT_ENTITY", "Two agents cannot share one player entity");
				}
			});
		}
		return new NativeAgentWhisperTargets(index);
	}

	public Optional<AgentId> resolve(UUID entityId) {
		return Optional.ofNullable(agentIdsByEntity.get(Objects.requireNonNull(entityId, "entityId must not be null")));
	}

	public int size() {
		return agentIdsByEntity.size();
	}
}
