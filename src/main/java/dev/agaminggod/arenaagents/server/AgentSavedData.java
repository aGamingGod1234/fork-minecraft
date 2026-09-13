package dev.agaminggod.arenaagents.server;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import dev.agaminggod.arenaagents.agent.AgentConstants;
import dev.agaminggod.arenaagents.agent.AgentDomainException;
import dev.agaminggod.arenaagents.agent.AgentId;
import dev.agaminggod.arenaagents.agent.AgentLifecycleState;
import dev.agaminggod.arenaagents.agent.AgentRecord;
import dev.agaminggod.arenaagents.agent.AgentRegistry;
import dev.agaminggod.arenaagents.agent.AgentRegistrySnapshotCodec;
import dev.agaminggod.arenaagents.agent.AgentTransition;
import dev.agaminggod.arenaagents.server.conversation.PendingConversationWake;
import dev.agaminggod.arenaagents.server.conversation.PendingConversationWakeCodec;
import dev.agaminggod.arenaagents.server.goal.PendingGoalDraft;
import dev.agaminggod.arenaagents.server.goal.PendingGoalDraftCodec;
import dev.agaminggod.arenaagents.server.goal.AgentKillLedger;
import dev.agaminggod.arenaagents.server.goal.AgentKillLedgerCodec;
import dev.agaminggod.arenaagents.server.goal.OperatorConfirmationLedger;
import dev.agaminggod.arenaagents.server.goal.OperatorConfirmationLedgerCodec;
import dev.agaminggod.arenaagents.server.goal.SurvivalProgressLedger;
import dev.agaminggod.arenaagents.server.goal.SurvivalProgressLedgerCodec;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.datafix.DataFixTypes;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.world.level.saveddata.SavedDataType;
import net.minecraft.world.level.storage.LevelResource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class AgentSavedData extends SavedData {
	private static final Logger LOGGER = LoggerFactory.getLogger(AgentSavedData.class);
	private static final String PAYLOAD_FIELD = "payload";
	private static final String PAYLOAD_CHUNKS_FIELD = "payload_chunks";
	private static final String CONVERSATION_WAKES_FIELD = "conversation_wakes";
	private static final String GOAL_DRAFTS_FIELD = "goal_drafts";
	private static final String KILL_LEDGER_CHUNKS_FIELD = "kill_ledger_chunks";
	private static final String SURVIVAL_PROGRESS_CHUNKS_FIELD = "survival_progress_chunks";
	private static final String OPERATOR_CONFIRMATION_CHUNKS_FIELD = "operator_confirmation_chunks";
	private static final AgentRegistrySnapshotCodec SNAPSHOT_CODEC = new AgentRegistrySnapshotCodec();
	private static final PendingConversationWakeCodec WAKE_CODEC = new PendingConversationWakeCodec();
	private static final PendingGoalDraftCodec DRAFT_CODEC = new PendingGoalDraftCodec();
	private static final AgentKillLedgerCodec KILL_LEDGER_CODEC = new AgentKillLedgerCodec();
	private static final SurvivalProgressLedgerCodec SURVIVAL_PROGRESS_CODEC = new SurvivalProgressLedgerCodec();
	private static final OperatorConfirmationLedgerCodec OPERATOR_CONFIRMATION_CODEC = new OperatorConfirmationLedgerCodec();
	private static final Codec<List<String>> WAKE_LIST_CODEC = ChunkedSavedPayload.boundedStringListCodec(
			AgentConstants.MAX_CONFIGURED_AGENTS, 256 * 1_024, 16 * 1_024 * 1_024,
			"Persisted conversation wakes"
	);
	private static final Codec<List<String>> DRAFT_LIST_CODEC = ChunkedSavedPayload.boundedStringListCodec(
			AgentConstants.MAX_CONFIGURED_AGENTS * 2, 256 * 1_024, 16 * 1_024 * 1_024,
			"Persisted goal drafts"
	);
	private static final Codec<AgentSavedData> CODEC = RecordCodecBuilder.create(instance -> instance.group(
			ChunkedSavedPayload.legacyCodec().optionalFieldOf(PAYLOAD_FIELD, "").forGetter(data -> ""),
			ChunkedSavedPayload.chunksCodec().optionalFieldOf(PAYLOAD_CHUNKS_FIELD, List.of()).forGetter(data -> ChunkedSavedPayload.split(data.encodePayload())),
			WAKE_LIST_CODEC.optionalFieldOf(CONVERSATION_WAKES_FIELD, List.of()).forGetter(AgentSavedData::encodeConversationWakes),
			DRAFT_LIST_CODEC.optionalFieldOf(GOAL_DRAFTS_FIELD, List.of()).forGetter(AgentSavedData::encodeGoalDrafts),
			ChunkedSavedPayload.chunksCodec().optionalFieldOf(KILL_LEDGER_CHUNKS_FIELD, List.of()).forGetter(AgentSavedData::encodeKillLedger),
			ChunkedSavedPayload.chunksCodec().optionalFieldOf(SURVIVAL_PROGRESS_CHUNKS_FIELD, List.of()).forGetter(AgentSavedData::encodeSurvivalProgress),
			ChunkedSavedPayload.chunksCodec().optionalFieldOf(OPERATOR_CONFIRMATION_CHUNKS_FIELD, List.of()).forGetter(AgentSavedData::encodeOperatorConfirmations)
	).apply(instance, AgentSavedData::decodePayload));
	public static final SavedDataType<AgentSavedData> TYPE = new SavedDataType<>(
			Identifier.fromNamespaceAndPath("arenaagents", "codex_agents"),
			AgentSavedData::new,
			CODEC,
			DataFixTypes.SAVED_DATA_COMMAND_STORAGE
	);

	private final AgentRegistry registry;
	private final Map<AgentId, PendingConversationWake> conversationWakes = new LinkedHashMap<>();
	private final Map<UUID, PendingGoalDraft> goalDrafts = new LinkedHashMap<>();
	private final AgentKillLedger killLedger;
	private final SurvivalProgressLedger survivalProgress;
	private final OperatorConfirmationLedger operatorConfirmations;
	private AgentRuntimeHooks runtimeHooks = AgentRuntimeHooks.NO_OP;

	public AgentSavedData() {
		this(new AgentRegistry.Snapshot(
				AgentConstants.SCHEMA_VERSION,
				AgentConstants.DEFAULT_AGENT_LIMIT,
				AgentConstants.DEFAULT_QUEUE_LIMIT,
				List.of()
		));
	}

	private AgentSavedData(AgentRegistry.Snapshot snapshot) {
		this(snapshot, List.of(), List.of(), AgentKillLedger.emptySnapshot(), SurvivalProgressLedger.emptySnapshot(),
				OperatorConfirmationLedger.emptySnapshot());
	}

	private AgentSavedData(
			AgentRegistry.Snapshot snapshot,
			List<PendingConversationWake> persistedWakes,
			List<PendingGoalDraft> persistedDrafts,
			AgentKillLedger.Snapshot persistedKillLedger,
			SurvivalProgressLedger.Snapshot persistedSurvivalProgress,
			OperatorConfirmationLedger.Snapshot persistedOperatorConfirmations
	) {
		this.killLedger = new AgentKillLedger(persistedKillLedger, this::setDirty);
		this.survivalProgress = new SurvivalProgressLedger(persistedSurvivalProgress, this::setDirty);
		this.operatorConfirmations = new OperatorConfirmationLedger(persistedOperatorConfirmations, this::setDirty);
		if (persistedWakes.size() > snapshot.maxAgents()) {
			throw new AgentDomainException("INVALID_PERSISTED_CONVERSATION_WAKE", "Persisted conversation wake count exceeds the agent limit");
		}
		Map<AgentId, AgentRecord> records = new LinkedHashMap<>();
		for (AgentRecord record : snapshot.records()) records.put(record.agentId(), record);
		if (persistedDrafts.size() > snapshot.maxAgents() * 2) {
			throw new AgentDomainException("INVALID_PERSISTED_GOAL_DRAFT", "Persisted goal draft count exceeds the bounded limit");
		}
		boolean discardedDraft = false;
		for (PendingGoalDraft draft : persistedDrafts) {
			AgentRecord record = records.get(draft.agentId());
			if (record == null || !draft.matches(record) || goalDrafts.containsKey(draft.draftId())) {
				discardedDraft = true;
				continue;
			}
			goalDrafts.put(draft.draftId(), draft);
		}
		boolean discardedWake = false;
		for (PendingConversationWake wake : persistedWakes) {
			AgentRecord record = records.get(wake.event().agentId());
			if (record == null || !wake.matches(record) || !record.state().isReloadUncertain()
					|| conversationWakes.containsKey(record.agentId())
					|| conversationWakes.values().stream().anyMatch(existing -> existing.transactionId().equals(wake.transactionId()))) {
				discardedWake = true;
				continue;
			}
			conversationWakes.put(record.agentId(), wake);
		}
		boolean recoveredUncertainState = snapshot.records().stream()
				.anyMatch(record -> record.state().isReloadUncertain());
		this.registry = AgentRegistry.restore(
				snapshot,
				this::setDirty,
				this::dispatchTransition,
				System.currentTimeMillis(),
				conversationWakes.keySet()
		);
		if (recoveredUncertainState || discardedWake || discardedDraft) {
			setDirty();
		}
	}

	public static AgentSavedData get(MinecraftServer server) {
		Objects.requireNonNull(server, "server must not be null");
		return server.overworld().getDataStorage().computeIfAbsent(TYPE);
	}

	/** Sidecar path used for synchronous action write-ahead durability. */
	public static Path actionJournalPath(MinecraftServer server) {
		Objects.requireNonNull(server, "server must not be null");
		return server.getWorldPath(LevelResource.ROOT).resolve("data").resolve("arenaagents-action-journal.json");
	}

	public AgentRegistry registry() {
		return registry;
	}

	public AgentKillLedger killLedger() {
		return killLedger;
	}

	public SurvivalProgressLedger survivalProgress() {
		return survivalProgress;
	}

	public OperatorConfirmationLedger operatorConfirmations() {
		return operatorConfirmations;
	}

	public void setRuntimeHooks(AgentRuntimeHooks runtimeHooks) {
		this.runtimeHooks = Objects.requireNonNull(runtimeHooks, "runtimeHooks must not be null");
	}

	private String encodePayload() {
		return SNAPSHOT_CODEC.encode(registry.snapshot());
	}

	private synchronized List<String> encodeConversationWakes() {
		return conversationWakes.values().stream().map(WAKE_CODEC::encode).toList();
	}

	private synchronized List<String> encodeGoalDrafts() {
		return goalDrafts.values().stream().map(DRAFT_CODEC::encode).toList();
	}

	private List<String> encodeKillLedger() {
		return ChunkedSavedPayload.split(KILL_LEDGER_CODEC.encode(killLedger.snapshot()));
	}

	private List<String> encodeSurvivalProgress() {
		return ChunkedSavedPayload.split(SURVIVAL_PROGRESS_CODEC.encode(survivalProgress.snapshot()));
	}

	private List<String> encodeOperatorConfirmations() {
		return ChunkedSavedPayload.split(OPERATOR_CONFIRMATION_CODEC.encode(operatorConfirmations.snapshot()));
	}

	private static AgentSavedData decodePayload(
			String legacyPayload,
			List<String> chunks,
			List<String> encodedWakes,
			List<String> encodedDrafts,
			List<String> killLedgerChunks,
			List<String> survivalProgressChunks,
			List<String> operatorConfirmationChunks
	) {
		return new AgentSavedData(
				SNAPSHOT_CODEC.decode(ChunkedSavedPayload.join(legacyPayload, chunks)),
				encodedWakes.stream().map(WAKE_CODEC::decode).toList(),
				encodedDrafts.stream().map(DRAFT_CODEC::decode).toList(),
				killLedgerChunks.isEmpty()
						? AgentKillLedger.emptySnapshot()
						: KILL_LEDGER_CODEC.decode(ChunkedSavedPayload.join("", killLedgerChunks)),
				survivalProgressChunks.isEmpty()
						? SurvivalProgressLedger.emptySnapshot()
						: SURVIVAL_PROGRESS_CODEC.decode(ChunkedSavedPayload.join("", survivalProgressChunks)),
				operatorConfirmationChunks.isEmpty()
						? OperatorConfirmationLedger.emptySnapshot()
						: OPERATOR_CONFIRMATION_CODEC.decode(ChunkedSavedPayload.join("", operatorConfirmationChunks))
		);
	}

	public synchronized void stageGoalDraft(PendingGoalDraft draft) {
		Objects.requireNonNull(draft, "draft must not be null");
		AgentRecord record = registry.records().stream()
				.filter(candidate -> candidate.agentId().equals(draft.agentId()))
				.findFirst()
				.orElse(null);
		if (record == null) {
			throw new AgentDomainException("UNKNOWN_AGENT", "Goal draft target does not exist");
		}
		if (!draft.matches(record)) {
			throw new AgentDomainException("STALE_GOAL_DRAFT", "Goal draft no longer matches the target goal revision");
		}
		if (goalDrafts.containsKey(draft.draftId())) {
			throw new AgentDomainException("DUPLICATE_GOAL_DRAFT", "Goal draft identity already exists");
		}
		goalDrafts.entrySet().removeIf(entry -> entry.getValue().agentId().equals(draft.agentId())
				&& entry.getValue().requestingPlayerId().equals(draft.requestingPlayerId()));
		pruneStaleGoalDrafts();
		if (goalDrafts.size() >= registry.maxAgents() * 2) {
			throw new AgentDomainException("GOAL_DRAFT_LIMIT_REACHED", "Too many pending goal clarifications");
		}
		goalDrafts.put(draft.draftId(), draft);
		setDirty();
	}

	private void pruneStaleGoalDrafts() {
		Map<AgentId, AgentRecord> currentRecords = new LinkedHashMap<>();
		for (AgentRecord record : registry.records()) currentRecords.put(record.agentId(), record);
		boolean removed = goalDrafts.entrySet().removeIf(entry -> {
			PendingGoalDraft pending = entry.getValue();
			AgentRecord current = currentRecords.get(pending.agentId());
			return current == null || !pending.matches(current);
		});
		if (removed) setDirty();
	}

	public synchronized List<PendingGoalDraft> goalDrafts() {
		return List.copyOf(goalDrafts.values());
	}

	public synchronized Optional<PendingGoalDraft> goalDraft(UUID draftId) {
		return Optional.ofNullable(goalDrafts.get(Objects.requireNonNull(draftId, "draftId must not be null")));
	}

	public synchronized PendingGoalDraft updateGoalDraftProposal(
			UUID draftId,
			AgentId agentId,
			dev.agaminggod.arenaagents.agent.goal.GoalPredicate predicate
	) {
		Objects.requireNonNull(draftId, "draftId must not be null");
		Objects.requireNonNull(agentId, "agentId must not be null");
		Objects.requireNonNull(predicate, "predicate must not be null");
		PendingGoalDraft draft = goalDrafts.get(draftId);
		if (draft == null) throw new AgentDomainException("UNKNOWN_GOAL_DRAFT", "Goal draft does not exist");
		if (!draft.agentId().equals(agentId)) throw new AgentDomainException("GOAL_DRAFT_AGENT_MISMATCH", "Goal draft belongs to another agent");
		if (!draft.intent().acceptsCoordinatorProposal()) {
			throw new AgentDomainException("GOAL_DRAFT_INTENT_MISMATCH", "Only translation drafts accept coordinator proposals");
		}
		AgentRecord record = registry.records().stream()
				.filter(candidate -> candidate.agentId().equals(agentId)).findFirst()
				.orElseThrow(() -> new AgentDomainException("UNKNOWN_AGENT", "Goal draft target does not exist"));
		if (!draft.matches(record)) throw new AgentDomainException("STALE_GOAL_DRAFT", "Goal draft no longer matches the target goal revision");
		if (draft.proposedPredicate().isPresent()) {
			if (draft.proposedPredicate().orElseThrow().equals(predicate)) return draft;
			throw new AgentDomainException("GOAL_DRAFT_PROPOSAL_CONFLICT", "Goal draft already has a different proposal");
		}
		PendingGoalDraft updated = draft.withProposedPredicate(predicate);
		goalDrafts.put(draftId, updated);
		setDirty();
		return updated;
	}

	public synchronized boolean removeGoalDraft(UUID draftId) {
		boolean removed = goalDrafts.remove(Objects.requireNonNull(draftId, "draftId must not be null")) != null;
		if (removed) setDirty();
		return removed;
	}

	public synchronized void clearGoalDrafts(AgentId agentId) {
		Objects.requireNonNull(agentId, "agentId must not be null");
		if (goalDrafts.entrySet().removeIf(entry -> entry.getValue().agentId().equals(agentId))) setDirty();
	}

	public synchronized void stageConversationWake(PendingConversationWake wake) {
		Objects.requireNonNull(wake, "wake must not be null");
		PendingConversationWake existing = conversationWakes.get(wake.event().agentId());
		if (existing != null) {
			throw new AgentDomainException("CONVERSATION_WAKE_PENDING", "Agent already has a pending conversation wake");
		}
		if (conversationWakes.values().stream().anyMatch(value -> value.transactionId().equals(wake.transactionId()))) {
			throw new AgentDomainException("DUPLICATE_CONVERSATION_WAKE", "Conversation wake transaction already exists");
		}
		conversationWakes.put(wake.event().agentId(), wake);
		setDirty();
	}

	public synchronized void rollbackConversationWake(UUID transactionId) {
		Objects.requireNonNull(transactionId, "transactionId must not be null");
		AgentId target = conversationWakes.entrySet().stream()
				.filter(entry -> entry.getValue().transactionId().equals(transactionId))
				.map(Map.Entry::getKey)
				.findFirst()
				.orElse(null);
		if (target != null) {
			conversationWakes.remove(target);
			setDirty();
		}
	}

	public synchronized boolean acknowledgeConversationWake(UUID transactionId, AgentId agentId, long goalRevision) {
		PendingConversationWake wake = conversationWakes.get(Objects.requireNonNull(agentId, "agentId must not be null"));
		if (wake == null) return false;
		if (!wake.transactionId().equals(Objects.requireNonNull(transactionId, "transactionId must not be null"))
				|| wake.goalRevision() != goalRevision) {
			throw new AgentDomainException("CONVERSATION_WAKE_MISMATCH", "Conversation wake acknowledgement does not match the durable transaction");
		}
		if (!wake.acknowledged()) {
			conversationWakes.put(agentId, wake.acknowledge());
			setDirty();
		}
		return true;
	}

	public synchronized Optional<PendingConversationWake> conversationWake(AgentId agentId) {
		return Optional.ofNullable(conversationWakes.get(Objects.requireNonNull(agentId, "agentId must not be null")));
	}

	public synchronized List<PendingConversationWake> conversationWakes() {
		return List.copyOf(conversationWakes.values());
	}

	public synchronized void clearConversationWake(AgentId agentId) {
		if (conversationWakes.remove(Objects.requireNonNull(agentId, "agentId must not be null")) != null) setDirty();
	}

	private void dispatchTransition(AgentTransition transition) {
		clearSupersededConversationWake(transition.after());
		if (transition.before().state() != AgentLifecycleState.DEAD
				&& transition.after().state() == AgentLifecycleState.DEAD) {
			survivalProgress.resetAgent(transition.after().agentId());
		}
		try {
			runtimeHooks.onTransition(transition);
		} catch (RuntimeException exception) {
			LOGGER.error("Codex agent runtime transition hook failed for {}", transition.after().agentId(), exception);
		}
	}

	private synchronized void clearSupersededConversationWake(AgentRecord record) {
		PendingConversationWake wake = conversationWakes.get(record.agentId());
		if (wake != null && (!wake.matches(record) || !record.state().isActive())) {
			conversationWakes.remove(record.agentId());
			setDirty();
		}
	}
}
