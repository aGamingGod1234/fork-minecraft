package dev.agaminggod.arenaagents.agent;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

public record AgentRecord(
		int schemaVersion, AgentId agentId, Optional<UUID> entityUuid, Optional<AgentEntityLocation> entityLocation,
		AgentProfile profile, AgentLifecycleState state, boolean resumeAfterRespawn,
		Optional<AgentGoal> currentGoal, long goalRevision,
		List<AgentGoal> queuedGoals, String lastSummary, String inventorySnapshot, boolean automaticProgress,
		RespawnPolicy respawnPolicy, Optional<AgentDeathSnapshot> deathSnapshot,
		long createdAtEpochMs, long updatedAtEpochMs, String lastError
) {
	public AgentRecord {
		if (schemaVersion != AgentConstants.SCHEMA_VERSION) throw new AgentDomainException("UNSUPPORTED_SCHEMA", "Unsupported agent schema version: " + schemaVersion);
		Objects.requireNonNull(agentId, "agentId must not be null");
		entityUuid = Objects.requireNonNull(entityUuid, "entityUuid must not be null");
		entityLocation = Objects.requireNonNull(entityLocation, "entityLocation must not be null");
		if (entityUuid.isEmpty() && entityLocation.isPresent()) throw new AgentDomainException("INVALID_ENTITY_LOCATION", "Entity location requires an entity UUID");
		Objects.requireNonNull(profile, "profile must not be null");
		Objects.requireNonNull(state, "state must not be null");
		if (state != AgentLifecycleState.DEAD && resumeAfterRespawn) throw new AgentDomainException("INVALID_AGENT_STATE", "Only a dead agent may retain respawn continuation intent");
		currentGoal = Objects.requireNonNull(currentGoal, "currentGoal must not be null");
		if (goalRevision < 0L) throw new AgentDomainException("INVALID_REVISION", "goalRevision must not be negative");
		queuedGoals = List.copyOf(Objects.requireNonNull(queuedGoals, "queuedGoals must not be null"));
		lastSummary = AgentValidators.boundedText(lastSummary, "lastSummary", AgentConstants.MAX_SUMMARY_LENGTH);
		inventorySnapshot = AgentValidators.boundedText(inventorySnapshot, "inventorySnapshot", AgentConstants.MAX_INVENTORY_SNAPSHOT_LENGTH);
		Objects.requireNonNull(respawnPolicy, "respawnPolicy must not be null");
		deathSnapshot = Objects.requireNonNull(deathSnapshot, "deathSnapshot must not be null");
		if (state != AgentLifecycleState.DEAD && deathSnapshot.isPresent()) throw new AgentDomainException("INVALID_DEATH_SNAPSHOT", "Only a dead agent may retain a death snapshot");
		if (createdAtEpochMs <= 0L || updatedAtEpochMs < createdAtEpochMs) throw new AgentDomainException("INVALID_AGENT_TIME", "Agent timestamps are invalid");
		lastError = AgentValidators.boundedText(lastError, "lastError", AgentConstants.MAX_ERROR_LENGTH);
		validateStateGoalInvariant(state, currentGoal);
	}

	public AgentRecord(
			int schemaVersion, AgentId agentId, Optional<UUID> entityUuid, Optional<AgentEntityLocation> entityLocation,
			AgentProfile profile, AgentLifecycleState state, Optional<AgentGoal> currentGoal, long goalRevision,
			List<AgentGoal> queuedGoals, String lastSummary, String inventorySnapshot, boolean automaticProgress,
			RespawnPolicy respawnPolicy, Optional<AgentDeathSnapshot> deathSnapshot,
			long createdAtEpochMs, long updatedAtEpochMs, String lastError
	) {
		this(schemaVersion, agentId, entityUuid, entityLocation, profile, state, false, currentGoal,
				goalRevision, queuedGoals, lastSummary, inventorySnapshot, automaticProgress, respawnPolicy,
				deathSnapshot, createdAtEpochMs, updatedAtEpochMs, lastError);
	}

	public static AgentRecord create(AgentId id, AgentProfile profile, long nowEpochMs) {
		return new AgentRecord(AgentConstants.SCHEMA_VERSION, id, Optional.empty(), Optional.empty(), profile,
				AgentLifecycleState.IDLE, false, Optional.empty(), 0L, List.of(), "", "", true, RespawnPolicy.PAUSE_UNTIL_RESPAWN,
				Optional.empty(), nowEpochMs, nowEpochMs, "");
	}

	public AgentRecord withEntityUuid(Optional<UUID> revisedEntityUuid, long nowEpochMs) {
		Optional<UUID> uuid = Objects.requireNonNull(revisedEntityUuid, "revisedEntityUuid must not be null");
		return copy(state, currentGoal, goalRevision, queuedGoals, uuid, uuid.isPresent() ? entityLocation : Optional.empty(), lastSummary, inventorySnapshot, automaticProgress, respawnPolicy, deathSnapshot, nowEpochMs, lastError);
	}

	public AgentRecord withEntity(Optional<UUID> revisedEntityUuid, Optional<AgentEntityLocation> revisedEntityLocation, long nowEpochMs) {
		return copy(state, currentGoal, goalRevision, queuedGoals, revisedEntityUuid, revisedEntityLocation, lastSummary, inventorySnapshot, automaticProgress, respawnPolicy, deathSnapshot, nowEpochMs, lastError);
	}

	public AgentRecord withEntityLocation(AgentEntityLocation revisedEntityLocation, long nowEpochMs) {
		return withEntity(entityUuid, Optional.of(Objects.requireNonNull(revisedEntityLocation, "revisedEntityLocation must not be null")), nowEpochMs);
	}

	public AgentRecord withLifecycle(AgentLifecycleState revisedState, Optional<AgentGoal> revisedGoal, long revisedRevision, List<AgentGoal> revisedQueue, long nowEpochMs, String revisedError) {
		Optional<AgentDeathSnapshot> snapshot = revisedState == AgentLifecycleState.DEAD ? deathSnapshot : Optional.empty();
		boolean continueAfterRespawn = revisedState == AgentLifecycleState.DEAD
				&& (state == AgentLifecycleState.DEAD
						? resumeAfterRespawn
						: state.isActive() || state == AgentLifecycleState.DISCONNECTED);
		return copy(revisedState, continueAfterRespawn, revisedGoal, revisedRevision, revisedQueue, entityUuid, entityLocation, lastSummary, inventorySnapshot, automaticProgress, respawnPolicy, snapshot, nowEpochMs, revisedError);
	}

	public AgentRecord withDeathSnapshot(AgentDeathSnapshot snapshot, long nowEpochMs) {
		return copy(AgentLifecycleState.DEAD, currentGoal, goalRevision, queuedGoals, entityUuid, entityLocation, lastSummary, inventorySnapshot, automaticProgress, respawnPolicy, Optional.of(Objects.requireNonNull(snapshot, "snapshot must not be null")), nowEpochMs, lastError);
	}

	public AgentRecord withRecovery(String revisedSummary, String revisedInventorySnapshot, long nowEpochMs) {
		return copy(state, currentGoal, goalRevision, queuedGoals, entityUuid, entityLocation, revisedSummary, revisedInventorySnapshot, automaticProgress, respawnPolicy, deathSnapshot, nowEpochMs, lastError);
	}

	public AgentRecord withRespawnPolicy(RespawnPolicy revisedPolicy, long nowEpochMs) {
		return copy(state, currentGoal, goalRevision, queuedGoals, entityUuid, entityLocation, lastSummary, inventorySnapshot, automaticProgress, revisedPolicy, deathSnapshot, nowEpochMs, lastError);
	}

	public AgentRecord withAutomaticProgress(boolean revisedAutomaticProgress, long nowEpochMs) {
		return copy(state, currentGoal, goalRevision, queuedGoals, entityUuid, entityLocation, lastSummary, inventorySnapshot, revisedAutomaticProgress, respawnPolicy, deathSnapshot, nowEpochMs, lastError);
	}

	public boolean acceptsRevision(long proposedRevision) { return proposedRevision == goalRevision && state.isActive(); }

	private AgentRecord copy(AgentLifecycleState revisedState, Optional<AgentGoal> revisedGoal, long revisedRevision, List<AgentGoal> revisedQueue, Optional<UUID> revisedEntityUuid, Optional<AgentEntityLocation> revisedEntityLocation, String revisedSummary, String revisedInventorySnapshot, boolean revisedAutomaticProgress, RespawnPolicy revisedRespawnPolicy, Optional<AgentDeathSnapshot> revisedDeathSnapshot, long nowEpochMs, String revisedError) {
		return copy(revisedState, resumeAfterRespawn, revisedGoal, revisedRevision, revisedQueue, revisedEntityUuid, revisedEntityLocation, revisedSummary, revisedInventorySnapshot, revisedAutomaticProgress, revisedRespawnPolicy, revisedDeathSnapshot, nowEpochMs, revisedError);
	}

	private AgentRecord copy(AgentLifecycleState revisedState, boolean revisedResumeAfterRespawn, Optional<AgentGoal> revisedGoal, long revisedRevision, List<AgentGoal> revisedQueue, Optional<UUID> revisedEntityUuid, Optional<AgentEntityLocation> revisedEntityLocation, String revisedSummary, String revisedInventorySnapshot, boolean revisedAutomaticProgress, RespawnPolicy revisedRespawnPolicy, Optional<AgentDeathSnapshot> revisedDeathSnapshot, long nowEpochMs, String revisedError) {
		return new AgentRecord(schemaVersion, agentId, revisedEntityUuid, revisedEntityLocation, profile, revisedState, revisedResumeAfterRespawn, revisedGoal, revisedRevision, revisedQueue, revisedSummary, revisedInventorySnapshot, revisedAutomaticProgress, revisedRespawnPolicy, revisedDeathSnapshot, createdAtEpochMs, nowEpochMs, revisedError);
	}

	private static void validateStateGoalInvariant(AgentLifecycleState state, Optional<AgentGoal> currentGoal) {
		if (state == AgentLifecycleState.IDLE && currentGoal.isPresent()) throw new AgentDomainException("INVALID_AGENT_STATE", "IDLE agents cannot have a current goal");
		if ((state.isActive() || state == AgentLifecycleState.PAUSED || state == AgentLifecycleState.DISCONNECTED) && currentGoal.isEmpty()) throw new AgentDomainException("INVALID_AGENT_STATE", state + " agents require a current goal");
		if (state == AgentLifecycleState.COMPLETED
				&& currentGoal.filter(goal -> goal.status() == dev.agaminggod.arenaagents.agent.goal.GoalStatus.SATISFIED).isEmpty()) {
			throw new AgentDomainException("GOAL_NOT_SATISFIED", "COMPLETED agents require a factually satisfied goal");
		}
	}
}
