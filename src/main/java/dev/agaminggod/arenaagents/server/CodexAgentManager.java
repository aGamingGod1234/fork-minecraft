package dev.agaminggod.arenaagents.server;

import dev.agaminggod.arenaagents.agent.AgentDomainException;
import dev.agaminggod.arenaagents.agent.AgentDeathSnapshot;
import dev.agaminggod.arenaagents.agent.AgentGameMode;
import dev.agaminggod.arenaagents.agent.AgentEntityLocation;
import dev.agaminggod.arenaagents.agent.AgentEntityRecoveryTarget;
import dev.agaminggod.arenaagents.agent.AgentId;
import dev.agaminggod.arenaagents.agent.AgentIdentity;
import dev.agaminggod.arenaagents.agent.AgentProfile;
import dev.agaminggod.arenaagents.agent.AgentRecord;
import dev.agaminggod.arenaagents.agent.AgentRegistry;
import dev.agaminggod.arenaagents.agent.AgentTransition;
import dev.agaminggod.arenaagents.mixin.CachedUserNameToIdResolverAccessor;
import dev.agaminggod.arenaagents.agent.goal.GoalPredicate;
import dev.agaminggod.arenaagents.agent.goal.GoalSpec;
import dev.agaminggod.arenaagents.server.goal.PendingGoalDraft;
import dev.agaminggod.arenaagents.server.goal.GoalDraftChoice;
import dev.agaminggod.arenaagents.server.goal.GoalDraftResolution;
import dev.agaminggod.arenaagents.server.goal.GoalCompilation;
import dev.agaminggod.arenaagents.server.goal.GoalCompiler;
import dev.agaminggod.arenaagents.server.goal.GoalInventoryCapacity;
import dev.agaminggod.arenaagents.server.goal.GoalPredicateWorldValidator;
import dev.agaminggod.arenaagents.server.goal.GoalSpecRequestSink;
import dev.agaminggod.arenaagents.server.goal.GoalSubmission;
import dev.agaminggod.arenaagents.server.goal.GoalSubmissionFlow;
import dev.agaminggod.arenaagents.server.goal.DraftIntent;
import dev.agaminggod.arenaagents.agent.CodexAgentEntities;
import dev.agaminggod.arenaagents.agent.CodexAgentEntity;
import dev.agaminggod.arenaagents.server.group.AgentGroup;
import dev.agaminggod.arenaagents.server.group.AgentGroupRegistry;
import dev.agaminggod.arenaagents.server.group.AgentGroupSavedData;
import dev.agaminggod.arenaagents.server.group.AgentGroupSpawnCoordinator;
import dev.agaminggod.arenaagents.server.conversation.ConversationEvent;
import dev.agaminggod.arenaagents.server.conversation.PendingConversationWake;
import dev.agaminggod.arenaagents.server.runtime.input.AgentInputRuntime;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.WeakHashMap;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BiConsumer;
import java.util.function.Predicate;
import java.util.function.Supplier;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.Registry;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.players.NameAndId;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.server.level.TicketType;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.FallingBlock;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.scores.PlayerTeam;
import net.minecraft.world.waypoints.WaypointStyleAsset;
import net.minecraft.world.waypoints.WaypointStyleAssets;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class CodexAgentManager {
	private static final Logger LOGGER = LoggerFactory.getLogger(CodexAgentManager.class);
	private static final Map<MinecraftServer, CodexAgentManager> INSTANCES = new WeakHashMap<>();
	private static final int AGENT_TICKET_RADIUS = 2;
	private static final long PLAYER_SPAWN_TIMEOUT_MS = 10_000L;
	private static final int MAX_PLAYER_NAME_ALLOCATION_ATTEMPTS = 64;
	private static final long VANILLA_DEATH_REMOVAL_GRACE_MS = 1_000L;
	private static final long RECOVERY_RETRY_DELAY_MS = 30_000L;
	private static final long CANCELLED_SPAWN_RETENTION_MS = 120_000L;
	private static final long LOCATION_PERSIST_INTERVAL_MS = 1_000L;
	private static final String LEGACY_HIDDEN_AGENT_TEAM = "arenaagents_hidden";
	private static final TicketType AGENT_TICKET_TYPE = new TicketType(
			TicketType.NO_TIMEOUT,
			TicketType.FLAG_LOADING | TicketType.FLAG_SIMULATION | TicketType.FLAG_KEEP_DIMENSION_ACTIVE
	);

	private final MinecraftServer server;
	private final AgentSavedData savedData;
	private final GoalCompiler goalCompiler = new GoalCompiler();
	private final AgentGroupSavedData groupSavedData;
	private final Map<AgentId, AgentChunkTicket> chunkTickets = new LinkedHashMap<>();
	private final Map<AgentChunkTicket, Integer> chunkTicketReferences = new LinkedHashMap<>();
	private final Map<AgentId, Long> pendingPlayerSpawns = new LinkedHashMap<>();
	private final Map<AgentId, VanillaRespawnAttempt> pendingVerifiedRespawns = new LinkedHashMap<>();
	private final Map<AgentId, Long> lastLocationPersistenceEpochMs = new LinkedHashMap<>();
	private Map<AgentId, OfflineAgentPlayers.LegacyBodyMigration> pendingLegacyMigrations = new LinkedHashMap<>();
	private Set<AgentId> pendingLegacyCanonicalRemovals = new LinkedHashSet<>();
	private Map<AgentId, ServerPlayer> retainedDeadPlayers = new LinkedHashMap<>();
	private final Set<AgentId> pendingAgentRegistrations = ConcurrentHashMap.newKeySet();
	private final Set<AgentId> pendingEntityRecoveries = new LinkedHashSet<>();
	private final PendingSpawnCancellationLedger cancelledPlayerSpawns =
			new PendingSpawnCancellationLedger(CANCELLED_SPAWN_RETENTION_MS);
	private final Set<AgentId> seenPlayers = new LinkedHashSet<>();
	private final AtomicBoolean releaseStarted = new AtomicBoolean();
	private AgentRuntimeHooks runtimeHooks = AgentRuntimeHooks.NO_OP;

	private CodexAgentManager(MinecraftServer server) {
		this.server = Objects.requireNonNull(server, "server must not be null");
		this.savedData = AgentSavedData.get(server);
		this.groupSavedData = AgentGroupSavedData.get(server);
		this.savedData.setRuntimeHooks(new ForwardingRuntimeHooks());
		prunePersistentMembership();
	}

	public static synchronized CodexAgentManager get(MinecraftServer server) {
		return INSTANCES.computeIfAbsent(
				Objects.requireNonNull(server, "server must not be null"),
				CodexAgentManager::new
		);
	}

	public static synchronized void release(MinecraftServer server) {
		CodexAgentManager manager = INSTANCES.remove(server);
		if (manager != null) {
			try {
				manager.releaseOwnedState();
			} finally {
				OfflineAgentPlayers.clearIdentityCache();
			}
		}
	}

	private synchronized Map<AgentId, ServerPlayer> retainedDeadPlayers() {
		if (retainedDeadPlayers == null) retainedDeadPlayers = new LinkedHashMap<>();
		return retainedDeadPlayers;
	}

	private synchronized Map<AgentId, OfflineAgentPlayers.LegacyBodyMigration> pendingLegacyMigrations() {
		if (pendingLegacyMigrations == null) pendingLegacyMigrations = new LinkedHashMap<>();
		return pendingLegacyMigrations;
	}

	private synchronized Set<AgentId> pendingLegacyCanonicalRemovals() {
		if (pendingLegacyCanonicalRemovals == null) pendingLegacyCanonicalRemovals = new LinkedHashSet<>();
		return pendingLegacyCanonicalRemovals;
	}

	private void releaseOwnedState() {
		releaseOnce(
			releaseStarted,
				runtimeHooks::onServerStopping,
				this::persistLiveAgentLocations,
				this::releasePendingVerifiedRespawns,
				() -> retainedDeadPlayers().clear(),
				this::releaseChunkTickets,
				() -> savedData.setRuntimeHooks(AgentRuntimeHooks.NO_OP),
				() -> AgentInputRuntime.release(server)
		);
	}

	static void releaseOnce(AtomicBoolean releaseStarted, Runnable... cleanupSteps) {
		Objects.requireNonNull(releaseStarted, "release state must not be null");
		if (!releaseStarted.compareAndSet(false, true)) return;
		runCleanupSteps(cleanupSteps);
	}

	private static void runCleanupSteps(Runnable... cleanupSteps) {
		Objects.requireNonNull(cleanupSteps, "cleanup steps must not be null");
		RuntimeException primaryFailure = null;
		for (Runnable cleanup : cleanupSteps) {
			try {
				Objects.requireNonNull(cleanup, "cleanup step must not be null").run();
			} catch (RuntimeException failure) {
				if (primaryFailure == null) primaryFailure = failure;
				else if (primaryFailure != failure) primaryFailure.addSuppressed(failure);
			}
		}
		if (primaryFailure != null) throw primaryFailure;
	}

	public void setRuntimeHooks(AgentRuntimeHooks runtimeHooks) {
		this.runtimeHooks = Objects.requireNonNull(runtimeHooks, "runtimeHooks must not be null");
	}

	public AgentRecord summon(
			ServerLevel level,
			Vec3 position,
			String model,
			String reasoning,
			Optional<String> userName
	) {
		return summon(level, position, "codex", model, reasoning, userName);
	}

	public AgentRecord summon(
			ServerLevel level,
			Vec3 position,
			String provider,
			String model,
			String reasoning,
			Optional<String> userName
	) {
		return summon(level, position, provider, model, reasoning, userName, AgentGameMode.SURVIVAL);
	}

	public AgentRecord summon(
			ServerLevel level,
			Vec3 position,
			String provider,
			String model,
			String reasoning,
			Optional<String> userName,
			AgentGameMode gameMode
	) {
		return summon(level, position, provider, model, reasoning, "priority", userName, gameMode);
	}

	public AgentRecord summon(
			ServerLevel level,
			Vec3 position,
			String provider,
			String model,
			String reasoning,
			String serviceTier,
			Optional<String> userName,
			AgentGameMode gameMode
	) {
		Objects.requireNonNull(level, "level must not be null");
		Objects.requireNonNull(position, "position must not be null");
		long now = System.currentTimeMillis();
		AgentRegistry registry = savedData.registry();
		List<String> livePlayerNames = server.getPlayerList().getPlayers().stream()
				.map(player -> player.getGameProfile().name())
				.toList();
		AgentRecord created;
		synchronized (registry) {
			created = allocatePlayerName(livePlayerNames, unavailableNames ->
					runtimeHooks.withinPublicationBoundary(() -> {
					AgentRecord record = registry.create(
							provider, model, reasoning, serviceTier, userName, gameMode, now, unavailableNames);
					pendingAgentRegistrations.add(record.agentId());
					return record;
				}), this::isPersistedPlayerNameReserved,
				rejected -> runtimeHooks.withinPublicationBoundary(() -> {
					registry.remove(rejected.agentId());
					pendingAgentRegistrations.remove(rejected.agentId());
					OfflineAgentPlayers.invalidateIdentity(rejected.agentId());
					return null;
				}));
		}
		AgentRecord finalCreated = created;
		try {
			runtimeHooks.validateProfile(finalCreated.profile());
			OfflineAgentPlayers.spawn(
					server,
					finalCreated.agentId(),
					finalCreated.profile(),
					position,
					0.0F,
					0.0F,
					level.dimension(),
					gameMode
			);
			pendingPlayerSpawns.put(finalCreated.agentId(), now + PLAYER_SPAWN_TIMEOUT_MS);
			return finalCreated;
		} catch (RuntimeException exception) {
			releaseChunkTicket(finalCreated.agentId());
			pendingEntityRecoveries.remove(finalCreated.agentId());
			OfflineAgentPlayers.find(server, finalCreated.agentId(), finalCreated.profile()).ifPresent(OfflineAgentPlayers::remove);
			if (pendingPlayerSpawns.remove(finalCreated.agentId()) != null) {
				cancelledPlayerSpawns.record(finalCreated.agentId(), finalCreated.profile(), System.currentTimeMillis());
			}
			synchronized (registry) {
				runtimeHooks.withinPublicationBoundary(() -> {
					try {
						registry.remove(finalCreated.agentId());
					} catch (AgentDomainException ignored) {
						// The record may already have been removed by a failing integration hook.
					} finally {
						pendingAgentRegistrations.remove(finalCreated.agentId());
						OfflineAgentPlayers.invalidateIdentity(finalCreated.agentId());
					}
					return null;
				});
			}
			throw exception;
		}
	}

	private boolean isPersistedPlayerNameReserved(String name) {
		return isPersistedPlayerNameReserved(name, server.getWorldPath(net.minecraft.world.level.storage.LevelResource.PLAYER_DATA_DIR),
				server.services().nameToIdCache(), identity -> server.getPlayerList().loadPlayerData(identity).isPresent());
	}

	static boolean isPersistedPlayerNameReserved(String name, java.nio.file.Path playerDataDirectory,
			net.minecraft.server.players.UserNameToIdResolver cache, Predicate<NameAndId> hasPlayerData) {
		UUID offlineUuid = AgentIdentity.offlinePlayerUuid(name);
		if (java.nio.file.Files.exists(playerDataDirectory.resolve(offlineUuid + ".dat"))
				|| java.nio.file.Files.exists(playerDataDirectory.resolve(offlineUuid + ".dat_old"))
				|| hasPlayerData.test(new NameAndId(offlineUuid, name))) return true;
		if (!(cache instanceof CachedUserNameToIdResolverAccessor cached)) {
			throw new AgentDomainException("PLAYER_NAME_CACHE_UNAVAILABLE", "Player names cannot be checked without a cached-only resolver");
		}
		return cached.arenaagents$cachedProfilesByName().containsKey(name.toLowerCase(java.util.Locale.ROOT));
	}

	/** Keeps name collision retries local and rolls back every rejected registration. */
	static AgentRecord allocatePlayerName(List<String> livePlayerNames,
			java.util.function.Function<List<String>, AgentRecord> create,
			Predicate<String> reserved, java.util.function.Consumer<AgentRecord> reject) {
		List<String> unavailable = new java.util.ArrayList<>(livePlayerNames);
		for (int attempt = 0; attempt < MAX_PLAYER_NAME_ALLOCATION_ATTEMPTS; attempt++) {
			AgentRecord candidate = create.apply(List.copyOf(unavailable));
			boolean accepted = false;
			try {
				String name = AgentIdentity.playerName(candidate.agentId(), candidate.profile());
				if (!reserved.test(name)) {
					accepted = true;
					return candidate;
				}
				unavailable.add(name);
			} finally {
				if (!accepted) reject.accept(candidate);
			}
		}
		throw new AgentDomainException("AGENT_NAME_ALLOCATION_EXHAUSTED",
				"Could not allocate an unused player name after 64 candidates; choose another agent name");
	}

	public AgentTransition start(String selector, String prompt, ServerLevel sourceLevel) {
		AgentRecord record = resolve(selector);
		SkitModeRuntime.requireNormalControlAllowed(server, record.agentId());
		return savedData.registry().start(record.agentId(), compileGoal(prompt, sourceLevel), System.currentTimeMillis());
	}

	public AgentTransition startSubjective(String selector, String prompt) {
		AgentRecord record = resolve(selector);
		SkitModeRuntime.requireNormalControlAllowed(server, record.agentId());
		return savedData.registry().start(record.agentId(), prompt, System.currentTimeMillis());
	}

	public AgentTransition startAtomically(
			AgentId agentId,
			String prompt,
			ServerLevel sourceLevel,
			BiConsumer<AgentTransition, Runnable> publicationBarrier
	) {
		SkitModeRuntime.requireNormalControlAllowed(server, Objects.requireNonNull(agentId, "agentId must not be null"));
		return savedData.registry().startAtomically(
				agentId,
				compileGoal(prompt, sourceLevel),
				System.currentTimeMillis(),
				Objects.requireNonNull(publicationBarrier, "publicationBarrier must not be null")
		);
	}

	public AgentTransition startConversationWakeAtomically(
			ConversationEvent event,
			GoalSpec spec,
			BiConsumer<PendingConversationWake, Runnable> publicationBarrier
	) {
		Objects.requireNonNull(event, "event must not be null");
		Objects.requireNonNull(publicationBarrier, "publicationBarrier must not be null");
		SkitModeRuntime.requireNormalControlAllowed(server, event.agentId());
		PendingConversationWake[] staged = { null };
		try {
			return savedData.registry().startAtomically(
					event.agentId(), spec, System.currentTimeMillis(),
					(transition, commit) -> {
						PendingConversationWake wake = PendingConversationWake.create(event, transition);
						savedData.stageConversationWake(wake);
						staged[0] = wake;
						publicationBarrier.accept(wake, commit);
					}
			);
		} catch (RuntimeException exception) {
			if (staged[0] != null) savedData.rollbackConversationWake(staged[0].transactionId());
			throw exception;
		}
	}

	public List<PendingConversationWake> pendingConversationWakes() {
		return savedData.conversationWakes();
	}

	public boolean acknowledgeConversationWake(UUID transactionId, AgentId agentId, long goalRevision) {
		return savedData.acknowledgeConversationWake(transactionId, agentId, goalRevision);
	}

	public Optional<PendingConversationWake> pendingConversationWake(AgentId agentId) {
		return savedData.conversationWake(agentId);
	}

	public void stageGoalDraft(PendingGoalDraft draft) {
		savedData.stageGoalDraft(draft);
	}

	public List<PendingGoalDraft> goalDrafts() {
		return savedData.goalDrafts();
	}

	public Optional<PendingGoalDraft> goalDraft(UUID draftId) {
		return savedData.goalDraft(draftId);
	}

	public PendingGoalDraft updateGoalDraftProposal(
			UUID draftId,
			AgentId agentId,
			dev.agaminggod.arenaagents.agent.goal.GoalPredicate predicate
	) {
		return savedData.updateGoalDraftProposal(draftId, agentId, predicate);
	}

	public boolean removeGoalDraft(UUID draftId) {
		return savedData.removeGoalDraft(draftId);
	}

	public Optional<GoalDraftResult> resolveGoalDraft(
			UUID draftId,
			UUID actorId,
			boolean operator,
			GoalDraftChoice choice
	) {
		Objects.requireNonNull(draftId, "draftId must not be null");
		PendingGoalDraft draft = savedData.goalDraft(draftId).orElse(null);
		if (draft == null) return Optional.empty();
		GoalDraftResolution.Operation operation = GoalDraftResolution.authorize(draft, actorId, operator, choice);
		if (operation == GoalDraftResolution.Operation.CANCEL) {
			savedData.removeGoalDraft(draftId);
			return Optional.of(new GoalDraftResult(operation, draft.agentId(), Optional.empty()));
		}
		AgentRecord record = savedData.registry().require(draft.agentId());
		SkitModeRuntime.requireNormalControlAllowed(server, draft.agentId());
		if (!draft.matches(record)) throw new AgentDomainException("STALE_GOAL_DRAFT", "Goal draft no longer matches the target goal revision");
		GoalPredicate predicate = draft.proposedPredicate().orElseThrow();
		validateGoalDraftTranslation(draft, predicate);
		if (GoalPredicateWorldValidator.requiresLiveLevel(predicate)) {
			GoalPredicateWorldValidator.validate(
					GoalPredicateWorldValidator.requireLevel(server, draft.dimensionId()), predicate);
		}
		GoalSpec spec = GoalSpec.create(
				draft.originalRequest(), predicate, draft.createdAtTick());
		validateGoalForActivation(spec);
		long now = System.currentTimeMillis();
		AgentTransition transition = switch (operation) {
			case START -> savedData.registry().start(draft.agentId(), spec, now);
			case REPLACE -> savedData.registry().replace(draft.agentId(), spec, now);
			case QUEUE -> savedData.registry().queue(draft.agentId(), spec, now);
			case CANCEL -> throw new AssertionError("cancel handled above");
		};
		savedData.removeGoalDraft(draftId);
		return Optional.of(new GoalDraftResult(operation, draft.agentId(), Optional.of(transition)));
	}

	public Optional<GoalDraftResult> activateTranslatedManagerDraft(PendingGoalDraft draft) {
		Objects.requireNonNull(draft, "draft must not be null");
		if (draft.intent() != DraftIntent.TRANSLATE_START && draft.intent() != DraftIntent.TRANSLATE_QUEUE) {
			return Optional.empty();
		}
		return resolveGoalDraft(
				draft.draftId(), draft.requestingPlayerId(), false, GoalDraftChoice.CONFIRM);
	}

	public void validateGoalDraftTranslation(PendingGoalDraft draft, GoalPredicate predicate) {
		Objects.requireNonNull(draft, "draft must not be null");
		Objects.requireNonNull(predicate, "predicate must not be null");
		draft.translationConstraint().validate(predicate);
		RegistryAccess registries = server == null ? RegistryAccess.EMPTY : server.registryAccess();
		GoalCompiler compiler = goalCompiler == null ? new GoalCompiler() : goalCompiler;
		compiler.translationConstraintFor(draft.originalRequest(), registries).validate(predicate);
	}

	public GoalPredicate normalizeGoalDraftTranslation(PendingGoalDraft draft, GoalPredicate predicate) {
		Objects.requireNonNull(draft, "draft must not be null");
		Objects.requireNonNull(predicate, "predicate must not be null");
		GoalCompiler compiler = goalCompiler == null ? new GoalCompiler() : goalCompiler;
		return compiler.normalizeTranslatedPredicate(draft.originalRequest(), predicate);
	}

	static void validateGoalDraftPredicate(
			GoalPredicate predicate,
			RegistryAccess registries,
			Predicate<String> advancementExists
	) {
		Objects.requireNonNull(registries, "registries must not be null");
		Registry<Item> items = registries.lookup(Registries.ITEM).orElse(BuiltInRegistries.ITEM);
		Registry<EntityType<?>> entities = registries.lookup(Registries.ENTITY_TYPE)
				.orElse(BuiltInRegistries.ENTITY_TYPE);
		validateLiveGoalIdentifiers(
				predicate,
				id -> contains(items, id),
				id -> contains(entities, id),
				advancementExists
		);
		GoalInventoryCapacity.validateTranslated(predicate, registries);
	}

	public void validateGoalForActivation(GoalSpec spec) {
		Objects.requireNonNull(spec, "spec must not be null");
		GoalPredicate predicate = spec.completion();
		if (GoalPredicateWorldValidator.requiresLiveLevel(predicate)) {
			GoalPredicateWorldValidator.validate(server, predicate);
		}
		RegistryAccess registries = server == null ? RegistryAccess.EMPTY : server.registryAccess();
		validateGoalDraftPredicate(
				predicate,
				registries,
				id -> {
					if (server == null) return false;
					Identifier identifier = Identifier.tryParse(id);
					return identifier != null && server.getAdvancements().get(identifier) != null;
				}
		);
	}

	static void validateLiveGoalIdentifiers(
			GoalPredicate predicate,
			Predicate<String> itemExists,
			Predicate<String> entityExists,
			Predicate<String> advancementExists
	) {
		Objects.requireNonNull(predicate, "predicate must not be null");
		Objects.requireNonNull(itemExists, "itemExists must not be null");
		Objects.requireNonNull(entityExists, "entityExists must not be null");
		Objects.requireNonNull(advancementExists, "advancementExists must not be null");
		switch (predicate) {
			case GoalPredicate.InventoryContains value -> requireLiveIdentifier(
					itemExists.test(value.itemId()), "item", value.itemId());
			case GoalPredicate.InventoryContainsAny value -> value.itemIds().forEach(
					itemId -> requireLiveIdentifier(itemExists.test(itemId), "item", itemId));
			case GoalPredicate.EntityKilledByAgent value -> requireLiveIdentifier(
					entityExists.test(value.entityType()), "entity type", value.entityType());
			case GoalPredicate.AdvancementGranted value -> {
				requireLiveIdentifier(
						advancementExists.test(value.advancementId()), "advancement", value.advancementId());
			}
			case GoalPredicate.BlockMatches value -> GoalPredicateWorldValidator.validateBlockProperties(
					value.blockId(), value.properties());
			case GoalPredicate.AllOf value -> value.predicates().forEach(
					child -> validateLiveGoalIdentifiers(child, itemExists, entityExists, advancementExists));
			case GoalPredicate.AnyOf value -> value.predicates().forEach(
					child -> validateLiveGoalIdentifiers(child, itemExists, entityExists, advancementExists));
			default -> { }
		}
	}

	private static boolean contains(Registry<?> registry, String value) {
		Identifier identifier = Identifier.tryParse(value);
		return identifier != null && registry.containsKey(identifier);
	}

	private static void requireLiveIdentifier(boolean exists, String type, String value) {
		if (!exists) {
			throw new AgentDomainException(
					"UNKNOWN_GOAL_IDENTIFIER",
					type + " does not exist on this server: " + value
			);
		}
	}

	public record GoalDraftResult(
			GoalDraftResolution.Operation operation,
			AgentId agentId,
			Optional<AgentTransition> transition
	) {
		public GoalDraftResult {
			Objects.requireNonNull(operation, "operation must not be null");
			Objects.requireNonNull(agentId, "agentId must not be null");
			transition = Objects.requireNonNull(transition, "transition must not be null");
		}
	}

	public AgentTransition rearmConversationWake(PendingConversationWake wake) {
		Objects.requireNonNull(wake, "wake must not be null");
		return savedData.registry().rearmConversationWake(
				wake.event().agentId(), wake.goalRevision(), wake.goal().goalId(), System.currentTimeMillis()
		);
	}

	public AgentTransition stop(String selector) {
		AgentRecord record = resolve(selector);
		return savedData.registry().stop(record.agentId(), System.currentTimeMillis());
	}

	public AgentTransition resume(String selector) {
		AgentRecord record = resolve(selector);
		SkitModeRuntime.requireNormalControlAllowed(server, record.agentId());
		return savedData.registry().resume(record.agentId(), System.currentTimeMillis());
	}

	public AgentTransition queue(String selector, String prompt, ServerLevel sourceLevel) {
		AgentRecord record = resolve(selector);
		SkitModeRuntime.requireNormalControlAllowed(server, record.agentId());
		return savedData.registry().queue(record.agentId(), compileGoal(prompt, sourceLevel), System.currentTimeMillis());
	}

	public GoalSubmission submitGoal(
			String selector,
			String prompt,
			ServerLevel sourceLevel,
			Optional<UUID> requestingPlayerId,
			GoalSubmission.Operation operation,
			GoalSpecRequestSink requestSink
	) {
		AgentRecord record = resolve(selector);
		SkitModeRuntime.requireNormalControlAllowed(server, record.agentId());
		Objects.requireNonNull(requestingPlayerId, "requestingPlayerId must not be null");
		Objects.requireNonNull(operation, "operation must not be null");
		Objects.requireNonNull(requestSink, "requestSink must not be null");
		GoalCompilation compilation = compileGoalResult(prompt, sourceLevel);
		return GoalSubmissionFlow.route(
				compilation,
				spec -> switch (operation) {
					case START -> savedData.registry().start(record.agentId(), spec, System.currentTimeMillis());
					case QUEUE -> savedData.registry().queue(record.agentId(), spec, System.currentTimeMillis());
				},
				() -> translatedSubmissionDraft(record, prompt, sourceLevel, requestingPlayerId, operation),
				this::stageGoalDraft,
				requestSink
		);
	}

	private PendingGoalDraft translatedSubmissionDraft(
			AgentRecord record,
			String prompt,
			ServerLevel sourceLevel,
			Optional<UUID> requestingPlayerId,
			GoalSubmission.Operation operation
	) {
		UUID requester = PendingGoalDraft.requesterId(requestingPlayerId);
		List<String> candidateIds = goalCompiler.candidateIdsFor(prompt, server.registryAccess(), liveAdvancementTitles());
		var constraint = goalCompiler.translationConstraintFor(prompt, server.registryAccess());
		constraint.requireCatalog(candidateIds);
		return new PendingGoalDraft(
				UUID.randomUUID(), record.agentId(), requester, prompt,
				sourceLevel.dimension().identifier().toString(),
				candidateIds, constraint, Optional.empty(),
				operation == GoalSubmission.Operation.START ? DraftIntent.TRANSLATE_START : DraftIntent.TRANSLATE_QUEUE,
				server.getTickCount(), record.goalRevision(), PendingGoalDraft.expectedGoalIdFor(record)
		);
	}

	private Map<String, String> liveAdvancementTitles() {
		LinkedHashMap<String, String> titles = new LinkedHashMap<>();
		for (var advancement : server.getAdvancements().getAllAdvancements()) {
			titles.put(
					advancement.id().toString(),
					advancement.value().display().map(display -> display.getTitle().getString()).orElse("")
			);
		}
		return Map.copyOf(titles);
	}

	private GoalSpec compileGoal(String prompt, ServerLevel sourceLevel) {
		GoalCompilation compilation = compileGoalResult(prompt, sourceLevel);
		return compilation.acceptedSpec().orElseThrow(() -> new AgentDomainException(
				"GOAL_REQUIRES_CLARIFICATION",
				compilation.playerMessage()
		));
	}

	private GoalCompilation compileGoalResult(String prompt, ServerLevel sourceLevel) {
		return goalCompiler.compile(
				prompt,
				server.registryAccess(),
				server.getTickCount(),
				id -> server.getAdvancements().get(net.minecraft.resources.Identifier.parse(id)) != null,
				Objects.requireNonNull(sourceLevel, "sourceLevel must not be null")
		);
	}

	public AgentTransition steer(String selector, String prompt) {
		AgentRecord record = resolve(selector);
		return savedData.registry().steer(record.agentId(), prompt, System.currentTimeMillis());
	}

	private VanillaRespawnAttempt beginVanillaRespawn(AgentId agentId) {
		AgentRecord record = savedData.registry().require(Objects.requireNonNull(agentId, "agentId must not be null"));
		if (record.state() != dev.agaminggod.arenaagents.agent.AgentLifecycleState.DEAD) {
			throw new AgentDomainException("AGENT_NOT_DEAD", "Only a dead Codex agent can be respawned");
		}
		runtimeHooks.validateProfile(record.profile());
		try {
			AgentDeathSnapshot death = record.deathSnapshot().orElseThrow(
					() -> new AgentDomainException("DEATH_SNAPSHOT_MISSING", "Dead agent has no persisted vanilla respawn facts")
			);
			OfflineAgentPlayers.VanillaRespawnTarget target = OfflineAgentPlayers.resolveVanillaRespawn(server, death);
			long now = System.currentTimeMillis();
			VanillaRespawnAttempt attempt = new VanillaRespawnAttempt(
					record, target, now + VANILLA_DEATH_REMOVAL_GRACE_MS, now + PLAYER_SPAWN_TIMEOUT_MS);
			Optional<ServerPlayer> existing = findAgentPlayer(record.agentId());
			if (existing.isPresent()) {
				ServerPlayer player = existing.orElseThrow();
				AgentInputRuntime.clear(server, record.agentId());
				AgentRespawnSpawnPolicy.ExistingPlayerAction action = AgentRespawnSpawnPolicy.existingPlayerAction(
						retainedDeadPlayers().get(record.agentId()) == player,
						player.isAlive()
				);
				switch (action) {
					case RESPAWN_CONNECTED_PLAYER -> {
						ServerPlayer replacement = OfflineAgentPlayers.respawnConnected(player);
						retainedDeadPlayers().remove(record.agentId(), player);
						attempt.connectedRespawn = true;
						attempt.spawnRequested = true;
						attempt.deadlineEpochMs = now + PLAYER_SPAWN_TIMEOUT_MS;
						if (!OfflineAgentPlayers.isManagedFakePlayer(
								replacement, record.agentId(), record.profile())) {
							throw new AgentDomainException("PLAYER_SPAWN_FAILED", "Vanilla respawn changed the managed player identity");
						}
					}
					case REMOVE_STALE_PLAYER -> {
						attempt.removalRequested = true;
						OfflineAgentPlayers.remove(player);
					}
					case WAIT_FOR_NATURAL_REMOVAL -> { }
				}
				pendingPlayerSpawns.put(record.agentId(), attempt.deadlineEpochMs());
			} else {
				retainedDeadPlayers().remove(record.agentId());
				requestVanillaRespawnPlayer(attempt, now);
			}
			return attempt;
		} catch (RuntimeException exception) {
			rollbackVanillaRespawn(record);
			throw exception;
		}
	}

	public boolean verifyVanillaRespawn(VanillaRespawnAttempt attempt, long nowEpochMs) {
		Objects.requireNonNull(attempt, "attempt must not be null");
		if (!savedData.registry().require(attempt.deadRecord().agentId()).equals(attempt.deadRecord())) {
			throw new AgentDomainException("STALE_RESPAWN_ATTEMPT", "Dead lifecycle changed during respawn");
		}
		Optional<ServerPlayer> found = findAgentPlayer(attempt.deadRecord().agentId());
		if (!attempt.connectedRespawn) {
			RespawnRemovalDecision removal = respawnRemovalDecision(
					attempt.spawnRequested,
					attempt.removalRequested,
					found.isPresent(),
					nowEpochMs,
					attempt.removalGraceDeadlineEpochMs,
					attempt.deadlineEpochMs
			);
			attempt.deadlineEpochMs = removal.deadlineEpochMs();
			if (removal.requestRemoval()) {
				attempt.removalRequested = true;
				OfflineAgentPlayers.remove(found.orElseThrow());
				return false;
			}
		}
		AgentRespawnSpawnPolicy.Decision decision = AgentRespawnSpawnPolicy.decide(
				attempt.spawnRequested, found.isPresent(), nowEpochMs, attempt.deadlineEpochMs()
		);
		switch (decision) {
			case WAIT_FOR_REMOVAL, WAIT_FOR_SPAWN -> { return false; }
			case REQUEST_SPAWN -> {
				requestVanillaRespawnPlayer(attempt, nowEpochMs);
				return false;
			}
			case TIMED_OUT -> throw new AgentDomainException(
					attempt.spawnRequested ? "PLAYER_SPAWN_TIMEOUT" : "PLAYER_REMOVAL_TIMEOUT",
					attempt.spawnRequested
							? "Respawned player did not appear before the deadline"
							: "Dead player did not leave before the respawn deadline"
			);
			case VERIFY_PLAYER -> { }
		}
		ServerPlayer player = found.orElseThrow();
		if (!player.isAlive()) throw new AgentDomainException("PLAYER_SPAWN_FAILED", "Respawned player is not alive");
		if (attempt.connectedRespawn) {
			attempt.verifiedPlayer = player;
			return true;
		}
		Vec3 finalPosition = attempt.target().finalPosition(player);
		if (player.level() != attempt.target().level() || player.position().distanceToSqr(finalPosition) > 1.0E-8D
				|| Math.abs(player.getYRot() - attempt.target().yaw()) > 0.001F || Math.abs(player.getXRot() - attempt.target().pitch()) > 0.001F) {
			boolean moved = player.teleportTo(
					attempt.target().level(), finalPosition.x, finalPosition.y, finalPosition.z,
					Set.of(), attempt.target().yaw(), attempt.target().pitch(), true
			);
			if (!moved) throw new AgentDomainException("PLAYER_SPAWN_VERIFY_FAILED", "Respawned player could not reach the vanilla target");
		}
		if (player.level() != attempt.target().level() || player.position().distanceToSqr(finalPosition) > 1.0E-8D
				|| player.gameMode.getGameModeForPlayer() != attempt.target().gameMode()) {
			throw new AgentDomainException("PLAYER_SPAWN_VERIFY_FAILED", "Respawned player failed physical verification");
		}
		attempt.verifiedPlayer = player;
		return true;
	}

	static RespawnRemovalDecision respawnRemovalDecision(
			boolean spawnRequested,
			boolean removalRequested,
			boolean playerPresent,
			long nowEpochMs,
			long removalGraceDeadlineEpochMs,
			long deadlineEpochMs
	) {
		return new RespawnRemovalDecision(
				!spawnRequested && !removalRequested && playerPresent
						&& nowEpochMs >= removalGraceDeadlineEpochMs,
				deadlineEpochMs
		);
	}

	static record RespawnRemovalDecision(boolean requestRemoval, long deadlineEpochMs) { }

	private void requestVanillaRespawnPlayer(VanillaRespawnAttempt attempt, long nowEpochMs) {
		AgentRecord record = attempt.deadRecord();
		OfflineAgentPlayers.spawn(
				server,
				record.agentId(),
				record.profile(),
				attempt.target().position(),
				attempt.target().yaw(),
				attempt.target().pitch(),
				attempt.target().level().dimension(),
				attempt.target().gameMode()
		);
		attempt.spawnRequested = true;
		attempt.deadlineEpochMs = nowEpochMs + PLAYER_SPAWN_TIMEOUT_MS;
		pendingPlayerSpawns.put(record.agentId(), attempt.deadlineEpochMs);
	}

	public AgentTransition commitVanillaRespawn(
			VanillaRespawnAttempt attempt,
			BiConsumer<AgentTransition, Runnable> publicationBarrier
	) {
		Objects.requireNonNull(publicationBarrier, "publicationBarrier must not be null");
		ServerPlayer player = Objects.requireNonNull(attempt.verifiedPlayer, "respawn must be physically verified before commit");
		Runnable rollbackWorld = () -> { };
		try {
			if (!attempt.connectedRespawn) rollbackWorld = attempt.target().commitWorldEffects();
			AgentEntityLocation location = entityLocation(player);
			AgentTransition transition = savedData.registry().respawnAtomically(
					attempt.deadRecord().agentId(), player.getUUID(), location, System.currentTimeMillis(),
					(prepared, commit) -> {
						restoreChunkTicket(prepared.after());
						try {
							publicationBarrier.accept(prepared, commit);
						} catch (RuntimeException exception) {
							releaseChunkTicket(prepared.after().agentId());
							throw exception;
						}
					}
			);
			pendingPlayerSpawns.remove(attempt.deadRecord().agentId());
			pendingEntityRecoveries.remove(attempt.deadRecord().agentId());
			return transition;
		} catch (RuntimeException exception) {
			rollbackWorld.run();
			if (!attempt.connectedRespawn) rollbackVanillaRespawn(attempt.deadRecord());
			throw exception;
		}
	}

	public void rollbackVanillaRespawn(VanillaRespawnAttempt attempt) {
		if (attempt != null) rollbackVanillaRespawn(attempt.deadRecord());
	}

	private void rollbackVanillaRespawn(AgentRecord deadRecord) {
		releaseChunkTicket(deadRecord.agentId());
		pendingPlayerSpawns.remove(deadRecord.agentId());
		retainedDeadPlayers().remove(deadRecord.agentId());
		AgentInputRuntime.clear(server, deadRecord.agentId());
		OfflineAgentPlayers.find(server, deadRecord.agentId(), deadRecord.profile()).ifPresent(OfflineAgentPlayers::remove);
	}

	public static final class VanillaRespawnAttempt {
		private final AgentRecord deadRecord;
		private final OfflineAgentPlayers.VanillaRespawnTarget target;
		private final long removalGraceDeadlineEpochMs;
		private long deadlineEpochMs;
		private boolean spawnRequested;
		private boolean removalRequested;
		private boolean connectedRespawn;
		private long nextCommitAttemptEpochMs;
		private ServerPlayer verifiedPlayer;

		private VanillaRespawnAttempt(
				AgentRecord deadRecord,
				OfflineAgentPlayers.VanillaRespawnTarget target,
				long removalGraceDeadlineEpochMs,
				long deadlineEpochMs
		) {
			this.deadRecord = deadRecord;
			this.target = target;
			this.removalGraceDeadlineEpochMs = removalGraceDeadlineEpochMs;
			this.deadlineEpochMs = deadlineEpochMs;
		}

		public AgentRecord deadRecord() { return deadRecord; }
		public OfflineAgentPlayers.VanillaRespawnTarget target() { return target; }
		public long deadlineEpochMs() { return deadlineEpochMs; }
	}

	public void reconcileDeaths() {
		long now = System.currentTimeMillis();
		RecoveryAttemptGate recoveryAttempts = new RecoveryAttemptGate();
		tickVerifiedRespawns(now);
		for (PendingSpawnCancellationLedger.Cancellation cancellation : cancelledPlayerSpawns.active(now)) {
			try {
				OfflineAgentPlayers.find(server, cancellation.agentId(), cancellation.profile())
						.ifPresent(OfflineAgentPlayers::remove);
			} catch (RuntimeException exception) {
				LOGGER.warn("Could not remove a cancelled player spawn for agent {}", cancellation.agentId(), exception);
			}
		}
		for (AgentRecord record : records()) {
			if (record.state() == dev.agaminggod.arenaagents.agent.AgentLifecycleState.DEAD) {
				ServerPlayer retained = retainedDeadPlayers().get(record.agentId());
				if (retained != null && findAgentPlayer(record.agentId()).filter(player -> player == retained).isEmpty()) {
					retainedDeadPlayers().remove(record.agentId(), retained);
				}
				pendingEntityRecoveries.remove(record.agentId());
				if (record.respawnPolicy() == dev.agaminggod.arenaagents.agent.RespawnPolicy.RESPAWN_AUTOMATICALLY
						&& !pendingVerifiedRespawns.containsKey(record.agentId())
						&& pendingPlayerSpawns.getOrDefault(record.agentId(), 0L) <= now) {
					try {
						requestVerifiedRespawn(record.agentId());
					} catch (RuntimeException exception) {
						scheduleRecoveryRetry(record.agentId(), now);
						LOGGER.warn("Could not begin automatic respawn for agent {}", record.agentId(), exception);
					}
				}
				continue;
			}
			retainedDeadPlayers().remove(record.agentId());
			if (pendingLegacyCanonicalRemovals().contains(record.agentId())) {
				Optional<ServerPlayer> rejectedCanonical = findAgentPlayer(record.agentId());
				if (rejectedCanonical.isPresent()) {
					try {
						OfflineAgentPlayers.remove(rejectedCanonical.orElseThrow());
					} catch (RuntimeException exception) {
						LOGGER.warn("Could not finish rolling back canonical migration player for agent {}",
								record.agentId(), exception);
					}
					scheduleRecoveryRetry(record.agentId(), now);
					continue;
				}
				pendingLegacyCanonicalRemovals().remove(record.agentId());
			}
			OfflineAgentPlayers.LegacyBodyMigration legacyMigration = pendingLegacyMigrations().get(record.agentId());
			if (legacyMigration == null
					&& pendingPlayerSpawns.getOrDefault(record.agentId(), 0L) > now
					&& findAgentPlayer(record.agentId()).isEmpty()) {
				continue;
			}
			if (legacyMigration == null) {
				try {
					legacyMigration = OfflineAgentPlayers.stageConnectedLegacyMigration(
							server, record.agentId(), record.profile()).orElse(null);
					if (legacyMigration != null) pendingLegacyMigrations().put(record.agentId(), legacyMigration);
				} catch (RuntimeException exception) {
					scheduleRecoveryRetry(record.agentId(), now);
					LOGGER.warn("Could not stage legacy player migration for agent {}", record.agentId(), exception);
					continue;
				}
			}
			Optional<ServerPlayer> player = findAgentPlayer(record.agentId());
			if (player.isPresent() && legacyMigration != null) {
				try {
					OfflineAgentPlayers.completeConnectedLegacyMigration(legacyMigration, player.orElseThrow());
					pendingLegacyMigrations().remove(record.agentId(), legacyMigration);
				} catch (RuntimeException exception) {
					pendingLegacyCanonicalRemovals().add(record.agentId());
					try {
						OfflineAgentPlayers.remove(player.orElseThrow());
					} catch (RuntimeException cleanupFailure) {
						exception.addSuppressed(cleanupFailure);
					}
					pendingPlayerSpawns.remove(record.agentId());
					scheduleRecoveryRetry(record.agentId(), now);
					LOGGER.warn("Could not commit legacy player migration for agent {}", record.agentId(), exception);
					continue;
				}
			}
			if (player.isPresent() && player.get().isAlive()) {
				pendingPlayerSpawns.remove(record.agentId());
				seenPlayers.add(record.agentId());
				AgentRecord attached = record;
				if (record.entityUuid().filter(player.get().getUUID()::equals).isEmpty()
						|| record.entityLocation().isEmpty()) {
					attached = savedData.registry().attachEntity(
							record.agentId(),
							player.get().getUUID(),
							entityLocation(player.get()),
							now
					);
					lastLocationPersistenceEpochMs.put(record.agentId(), now);
				}
				applyWorldIdentity(player.get(), attached);
				trackChunkTicket(record.agentId(), player.get(), now);
				publishPendingRegistration(attached);
				if (pendingEntityRecoveries.remove(record.agentId())
						&& attached.state() == dev.agaminggod.arenaagents.agent.AgentLifecycleState.DISCONNECTED) {
					savedData.registry().resume(record.agentId(), now);
				}
			} else if (player.isPresent()) {
				AgentInputRuntime.clear(server, record.agentId());
				savedData.registry().die(record.agentId(), deathSnapshot(player.get(), now), now);
			} else {
				long deadline = pendingPlayerSpawns.getOrDefault(record.agentId(), 0L);
				if (deadline > now) continue;
				AgentRecord recoveryTarget = record;
				AgentRecord recoveryRecord = record;
				if (record.entityUuid().isPresent()) {
					releaseChunkTicket(record.agentId());
					AgentInputRuntime.clear(server, record.agentId());
					recoveryRecord = savedData.registry().detachEntity(record.agentId(), now);
				}
				if (seenPlayers.contains(record.agentId()) && recoveryRecord.state().isActive()) {
					AgentTransition disconnected = savedData.registry().disconnect(record.agentId(), now);
					pendingEntityRecoveries.add(record.agentId());
					recoveryRecord = disconnected.after();
				}
				if (!recoveryAttempts.tryClaim()) continue;
				recoverOfflinePlayer(recoveryTarget, now);
			}
		}
	}

	static final class RecoveryAttemptGate {
		private boolean claimed;

		boolean tryClaim() {
			if (claimed) return false;
			claimed = true;
			return true;
		}
	}

	private void prunePersistentMembership() {
		Set<AgentId> currentIds = new LinkedHashSet<>();
		Set<String> currentPlayerNames = new LinkedHashSet<>();
		for (AgentRecord record : records()) {
			currentIds.add(record.agentId());
			currentPlayerNames.add(OfflineAgentPlayers.playerName(record.agentId(), record.profile()));
		}
		groupSavedData.registry().retainMembers(currentIds);
		PlayerTeam team = server.getScoreboard().getPlayerTeam(LEGACY_HIDDEN_AGENT_TEAM);
		if (team == null) return;
		for (String trackedName : staleHiddenTeamMembers(team.getPlayers(), currentPlayerNames)) {
			server.getScoreboard().removePlayerFromTeam(trackedName, team);
		}
		if (team.getPlayers().isEmpty()) server.getScoreboard().removePlayerTeam(team);
	}

	static List<String> staleHiddenTeamMembers(Iterable<String> trackedNames, Set<String> currentPlayerNames) {
		Objects.requireNonNull(trackedNames, "trackedNames must not be null");
		Set<String> checkedCurrentNames = Set.copyOf(Objects.requireNonNull(
				currentPlayerNames, "currentPlayerNames must not be null"
		));
		List<String> stale = new java.util.ArrayList<>();
		for (String trackedName : trackedNames) {
			if (!checkedCurrentNames.contains(trackedName)) stale.add(trackedName);
		}
		return List.copyOf(stale);
	}

	public AgentGroup saveGroup(String name, List<AgentId> memberIds) {
		List<AgentId> checkedIds = List.copyOf(Objects.requireNonNull(memberIds, "memberIds must not be null"));
		for (AgentId memberId : checkedIds) savedData.registry().require(memberId);
		return groupSavedData.registry().save(name, checkedIds);
	}

	public AgentGroup deleteGroup(String name) {
		return groupSavedData.registry().delete(name);
	}

	public List<AgentGroup> groups() {
		return groupSavedData.registry().groups();
	}

	public AgentGroupSpawnCoordinator.Result spawnGroup(String name) {
		AgentGroup group = groupSavedData.registry().require(name);
		return AgentGroupSpawnCoordinator.spawn(group, this::ensureGroupMemberPresent);
	}

	public AgentRecord requestRespawn(String selector) {
		AgentRecord record = resolve(selector);
		requestVerifiedRespawn(record.agentId());
		return record;
	}

	public VanillaRespawnAttempt requestVerifiedRespawn(AgentId agentId) {
		VanillaRespawnAttempt pending = pendingVerifiedRespawns.get(agentId);
		if (pending != null) return pending;
		long now = System.currentTimeMillis();
		if (pendingPlayerSpawns.getOrDefault(agentId, 0L) > now) {
			throw new AgentDomainException("RESPAWN_ALREADY_PENDING", "The agent player is already respawning");
		}
		return singleFlight(pendingVerifiedRespawns, agentId, () -> beginVanillaRespawn(agentId));
	}

	public boolean cancelVerifiedRespawn(VanillaRespawnAttempt attempt) {
		Objects.requireNonNull(attempt, "attempt must not be null");
		if (!abortPendingVerifiedRespawn(pendingVerifiedRespawns, attempt)) return false;
		rollbackVanillaRespawn(attempt);
		return true;
	}

	static boolean abortPendingVerifiedRespawn(
			Map<AgentId, VanillaRespawnAttempt> pending,
			VanillaRespawnAttempt attempt
	) {
		Objects.requireNonNull(pending, "pending must not be null");
		Objects.requireNonNull(attempt, "attempt must not be null");
		return pending.remove(attempt.deadRecord().agentId(), attempt);
	}

	static <T> T singleFlight(Map<AgentId, T> inFlight, AgentId agentId, Supplier<T> start) {
		Objects.requireNonNull(inFlight, "inFlight must not be null");
		Objects.requireNonNull(agentId, "agentId must not be null");
		Objects.requireNonNull(start, "start must not be null");
		T existing = inFlight.get(agentId);
		if (existing != null) return existing;
		T started = Objects.requireNonNull(start.get(), "start must return an attempt");
		inFlight.put(agentId, started);
		return started;
	}

	public VerifiedRespawnState verifiedRespawnState(VanillaRespawnAttempt attempt) {
		Objects.requireNonNull(attempt, "attempt must not be null");
		AgentId agentId = attempt.deadRecord().agentId();
		AgentRecord current = savedData.registry().require(agentId);
		if (!current.equals(attempt.deadRecord())) {
			return current.state() != dev.agaminggod.arenaagents.agent.AgentLifecycleState.DEAD
					&& findAgentPlayer(agentId).filter(ServerPlayer::isAlive).isPresent()
					? VerifiedRespawnState.SUCCEEDED : VerifiedRespawnState.FAILED;
		}
		return pendingVerifiedRespawns.get(agentId) == attempt
				? VerifiedRespawnState.PENDING : VerifiedRespawnState.FAILED;
	}

	public enum VerifiedRespawnState { PENDING, SUCCEEDED, FAILED }

	private AgentGroupSpawnCoordinator.MemberStatus ensureGroupMemberPresent(AgentId agentId) {
		AgentRecord record;
		try {
			record = savedData.registry().require(agentId);
		} catch (AgentDomainException exception) {
			if ("AGENT_NOT_FOUND".equals(exception.code())) return AgentGroupSpawnCoordinator.MemberStatus.MISSING;
			throw exception;
		}
		Optional<ServerPlayer> player = OfflineAgentPlayers.find(server, agentId, record.profile());
		if (player.filter(ServerPlayer::isAlive).isPresent()) {
			return AgentGroupSpawnCoordinator.MemberStatus.PRESENT;
		}
		if (record.state() == dev.agaminggod.arenaagents.agent.AgentLifecycleState.DEAD) {
			requestVerifiedRespawn(agentId);
			return AgentGroupSpawnCoordinator.MemberStatus.RESTORING;
		}
		recoverOfflinePlayer(record, System.currentTimeMillis());
		return AgentGroupSpawnCoordinator.MemberStatus.RESTORING;
	}

	private void tickVerifiedRespawns(long nowEpochMs) {
		for (Map.Entry<AgentId, VanillaRespawnAttempt> entry : List.copyOf(pendingVerifiedRespawns.entrySet())) {
			AgentId agentId = entry.getKey();
			VanillaRespawnAttempt attempt = entry.getValue();
			if (nowEpochMs < attempt.nextCommitAttemptEpochMs) continue;
			try {
				if (!verifyVanillaRespawn(attempt, nowEpochMs)) continue;
				commitVanillaRespawn(attempt, (transition, commit) -> {
					commit.run();
					runtimeHooks.onTransition(transition);
				});
				pendingVerifiedRespawns.remove(agentId, attempt);
			} catch (RuntimeException exception) {
				boolean replacementAlive = findAgentPlayer(agentId).filter(ServerPlayer::isAlive).isPresent();
				boolean deadRecordCurrent = savedData.registry().require(agentId).equals(attempt.deadRecord());
				if (shouldRetryConnectedRespawn(attempt.connectedRespawn, replacementAlive, deadRecordCurrent)) {
					attempt.nextCommitAttemptEpochMs = nowEpochMs + 1_000L;
					LOGGER.warn("Could not publish connected respawn for agent {}; retrying in place", agentId, exception);
					continue;
				}
				pendingVerifiedRespawns.remove(agentId, attempt);
				rollbackVanillaRespawn(attempt);
				scheduleRecoveryRetry(agentId, nowEpochMs);
				LOGGER.warn("Could not complete verified respawn for agent {}", agentId, exception);
			}
		}
	}

	static boolean shouldRetryConnectedRespawn(
			boolean connectedRespawn,
			boolean replacementAlive,
			boolean deadRecordCurrent
	) {
		return connectedRespawn && replacementAlive && deadRecordCurrent;
	}

	private void releasePendingVerifiedRespawns() {
		Runnable[] cleanupSteps = List.copyOf(pendingVerifiedRespawns.values()).stream()
				.map(attempt -> (Runnable) () -> rollbackVanillaRespawn(attempt))
				.toArray(Runnable[]::new);
		pendingVerifiedRespawns.clear();
		runCleanupSteps(cleanupSteps);
	}

	/** Keeps a managed dead fake player connected so vanilla can replace it without leave/join broadcasts. */
	public boolean retainConnectedDeath(ServerPlayer player) {
		Objects.requireNonNull(player, "player must not be null");
		Optional<AgentRecord> matchedRecord = records().stream()
				.filter(record -> record.state() == dev.agaminggod.arenaagents.agent.AgentLifecycleState.DEAD)
				.filter(record -> record.respawnPolicy() != dev.agaminggod.arenaagents.agent.RespawnPolicy.REMOVE_ON_DEATH)
				.filter(record -> OfflineAgentPlayers.isManagedFakePlayer(player, record.agentId(), record.profile()))
				.findFirst();
		if (matchedRecord.isEmpty()) return false;
		AgentId agentId = matchedRecord.orElseThrow().agentId();
		OfflineAgentPlayers.retainConnectedDeath(player);
		retainedDeadPlayers().put(agentId, player);
		return true;
	}

	public boolean captureDeath(ServerPlayer player, DamageSource source) {
		Objects.requireNonNull(player, "player must not be null");
		Objects.requireNonNull(source, "source must not be null");
		Optional<AgentRecord> matchedRecord = records().stream()
				.filter(record -> OfflineAgentPlayers.isManagedFakePlayer(player, record.agentId(), record.profile()))
				.findFirst();
		if (matchedRecord.isEmpty()) return false;
		long now = System.currentTimeMillis();
		String cause;
		try {
			cause = source.getLocalizedDeathMessage(player).getString();
		} catch (RuntimeException ignored) {
			cause = "Agent died";
		}
		AgentInputRuntime.clear(server, matchedRecord.orElseThrow().agentId());
		return AgentDeathCapture.record(
				savedData.registry(), player.getUUID(), deathSnapshot(player, cause, now), now
		);
	}

	private static AgentDeathSnapshot deathSnapshot(ServerPlayer player, long now) {
		String cause;
		try {
			cause = player.getCombatTracker().getDeathMessage().getString();
		} catch (RuntimeException ignored) {
			cause = "Agent died";
		}
		return deathSnapshot(player, cause, now);
	}

	private static AgentDeathSnapshot deathSnapshot(ServerPlayer player, String cause, long now) {
		ServerPlayer.RespawnConfig config = player.getRespawnConfig();
		Optional<String> respawnDimension = Optional.empty();
		Optional<Double> respawnX = Optional.empty();
		Optional<Double> respawnY = Optional.empty();
		Optional<Double> respawnZ = Optional.empty();
		Optional<Float> respawnYaw = Optional.empty();
		Optional<Float> respawnPitch = Optional.empty();
		Optional<Boolean> respawnForced = Optional.empty();
		if (config != null) {
			var data = config.respawnData();
			respawnDimension = Optional.of(data.dimension().identifier().toString());
			respawnX = Optional.of((double) data.pos().getX());
			respawnY = Optional.of((double) data.pos().getY());
			respawnZ = Optional.of((double) data.pos().getZ());
			respawnYaw = Optional.of(data.yaw());
			respawnPitch = Optional.of(data.pitch());
			respawnForced = Optional.of(config.forced());
		}
		return new AgentDeathSnapshot(
				cause, player.level().dimension().identifier().toString(), player.getX(), player.getY(), player.getZ(),
				respawnDimension, respawnX, respawnY, respawnZ, respawnYaw, respawnPitch, respawnForced,
				player.gameMode.getGameModeForPlayer().getName(), now
		);
	}


	private void applyWorldIdentity(ServerPlayer player, AgentRecord record) {
		PlayerTeam hidden = server.getScoreboard().getPlayerTeam(LEGACY_HIDDEN_AGENT_TEAM);
		if (hidden != null && server.getScoreboard().getPlayersTeam(player.getScoreboardName()) == hidden) {
			server.getScoreboard().removePlayerFromTeam(player.getScoreboardName(), hidden);
		}
		player.setCustomName(Component.literal(AgentIdentity.displayNameTag(record.profile())));
		player.setCustomNameVisible(true);
		String stylePath = "agent/" + record.profile().visualIdentity().transportCode();
		ResourceKey<WaypointStyleAsset> style = ResourceKey.create(
				WaypointStyleAssets.ROOT_ID,
				Identifier.fromNamespaceAndPath("arenaagents", stylePath)
		);
		player.waypointIcon().style = style;
	}

	private void removeHiddenWorldName(String playerName) {
		PlayerTeam team = server.getScoreboard().getPlayerTeam(LEGACY_HIDDEN_AGENT_TEAM);
		if (team != null && team.getPlayers().contains(playerName)) {
			server.getScoreboard().removePlayerFromTeam(playerName, team);
			if (team.getPlayers().isEmpty()) server.getScoreboard().removePlayerTeam(team);
		}
	}

	private boolean recoverOfflinePlayer(AgentRecord record, long now) {
		Optional<RecoverySpawn> recovery;
		try {
			Optional<CodexAgentEntity> legacy = findAgentEntity(record.agentId());
			if (legacy.isPresent() && legacy.get().level() instanceof ServerLevel legacyLevel) {
				CodexAgentEntity legacyEntity = legacy.get();
				Vec3 legacyPosition = legacyEntity.position();
				AgentRecoverySpawnPolicy.ChunkPosition chunk =
						AgentRecoverySpawnPolicy.chunkContaining(legacyPosition.x, legacyPosition.z);
				AgentEntityLocation legacyLocation = AgentEntityLocation.exact(
						legacyLevel.dimension().identifier().toString(),
						chunk.x(),
						chunk.z(),
						legacyPosition.x,
						legacyPosition.y,
						legacyPosition.z,
						legacyEntity.getYRot(),
						legacyEntity.getXRot()
				);
				legacy.get().discard();
				recovery = findRecoverySpawn(legacyLevel, legacyLocation);
			} else if (record.entityLocation().isPresent()) {
				AgentEntityLocation location = record.entityLocation().orElseThrow();
				recovery = findLevel(location.dimension())
						.flatMap(level -> findRecoverySpawn(level, location));
			} else {
				recovery = Optional.empty();
			}
			if (recovery.isEmpty()) recovery = findOverworldSpawnRecovery();
		} catch (RuntimeException exception) {
			LOGGER.warn("Could not search for a safe recovery position for agent {}", record.agentId().value(), exception);
			scheduleRecoveryRetry(record.agentId(), now);
			return false;
		}
		if (recovery.isEmpty()) {
			LOGGER.warn("No dry supported recovery position is available for agent {}", record.agentId().value());
			scheduleRecoveryRetry(record.agentId(), now);
			return false;
		}
		ServerLevel level = recovery.orElseThrow().level();
		Vec3 position = recovery.orElseThrow().position();
		try {
			OfflineAgentPlayers.spawn(
					server,
					record.agentId(),
					record.profile(),
					position,
					recovery.orElseThrow().yaw(),
					recovery.orElseThrow().pitch(),
					level.dimension(),
					record.profile().gameMode()
			);
			pendingPlayerSpawns.put(record.agentId(), now + PLAYER_SPAWN_TIMEOUT_MS);
			return true;
		} catch (RuntimeException exception) {
			LOGGER.warn("Could not request a recovery player spawn for agent {}", record.agentId().value(), exception);
			scheduleRecoveryRetry(record.agentId(), now);
			return false;
		}
	}

	private void scheduleRecoveryRetry(AgentId agentId, long now) {
		pendingPlayerSpawns.put(agentId, now + RECOVERY_RETRY_DELAY_MS);
	}

	private Optional<RecoverySpawn> findOverworldSpawnRecovery() {
		ServerLevel overworld = server.overworld();
		BlockPos spawn = overworld.getRespawnData().pos();
		return findRecoverySpawn(
				overworld,
				spawn.getX() >> 4,
				spawn.getZ() >> 4,
				java.util.OptionalInt.of(spawn.getY())
		);
	}

	private Optional<RecoverySpawn> findRecoverySpawn(
			ServerLevel level,
			int chunkX,
			int chunkZ,
			java.util.OptionalInt preferredY
	) {
		int centerX = (chunkX << 4) + 8;
		int centerZ = (chunkZ << 4) + 8;
		return findRecoverySpawn(level, chunkX, chunkZ, centerX, centerZ, preferredY, 0.0F, 0.0F);
	}

	private Optional<RecoverySpawn> findRecoverySpawn(ServerLevel level, AgentEntityLocation location) {
		if (location.hasExactPosition()) level.getChunk(location.chunkX(), location.chunkZ());
		Optional<ExactRecoveryCoordinates> exact = exactRecoveryCoordinates(
				location,
				(x, y, z) -> recoveryColumnAt(level, x, y, z).safe()
		);
		if (exact.isPresent()) {
			ExactRecoveryCoordinates coordinates = exact.orElseThrow();
			return Optional.of(new RecoverySpawn(
					level,
					new Vec3(coordinates.x(), coordinates.y(), coordinates.z()),
					coordinates.yaw(),
					coordinates.pitch()
			));
		}
		float yaw = (float) location.yaw().orElse(0.0D);
		float pitch = (float) location.pitch().orElse(0.0D);
		if (location.hasExactPosition()) {
			return findRecoverySpawn(
					level,
					location.chunkX(),
					location.chunkZ(),
					(int) Math.floor(location.exactX().orElseThrow()),
					(int) Math.floor(location.exactZ().orElseThrow()),
					location.blockY(),
					yaw,
					pitch
			);
		}
		return findRecoverySpawn(
				level,
				location.chunkX(),
				location.chunkZ(),
				(location.chunkX() << 4) + 8,
				(location.chunkZ() << 4) + 8,
				location.blockY(),
				yaw,
				pitch
		);
	}

	static Optional<ExactRecoveryCoordinates> exactRecoveryCoordinates(
			AgentEntityLocation location,
			RecoveryColumnSafety safety
	) {
		Objects.requireNonNull(location, "location must not be null");
		Objects.requireNonNull(safety, "safety must not be null");
		if (!location.hasExactPosition()) return Optional.empty();
		double x = location.exactX().orElseThrow();
		double y = location.exactY().orElseThrow();
		double z = location.exactZ().orElseThrow();
		if (!safety.safe((int) Math.floor(x), (int) Math.floor(y), (int) Math.floor(z))) {
			return Optional.empty();
		}
		return Optional.of(new ExactRecoveryCoordinates(
				x,
				y,
				z,
				(float) location.yaw().orElse(0.0D),
				(float) location.pitch().orElse(0.0D)
		));
	}

	private Optional<RecoverySpawn> findRecoverySpawn(
			ServerLevel level,
			int chunkX,
			int chunkZ,
			int preferredX,
			int preferredZ,
			java.util.OptionalInt preferredY,
			float yaw,
			float pitch
	) {
		level.getChunk(chunkX, chunkZ);
		int centerX = Math.max(chunkX << 4, Math.min(((chunkX + 1) << 4) - 1, preferredX));
		int centerZ = Math.max(chunkZ << 4, Math.min(((chunkZ + 1) << 4) - 1, preferredZ));
		Optional<RecoverySpawn> local = findRecoverySpawnInBounds(
				level,
				centerX,
				centerZ,
				chunkX << 4,
				((chunkX + 1) << 4) - 1,
				chunkZ << 4,
				((chunkZ + 1) << 4) - 1,
				preferredY,
				yaw,
				pitch
		);
		if (local.isPresent()) return local;

		int chunkRadius = 1;
		for (int x = chunkX - chunkRadius; x <= chunkX + chunkRadius; x++) {
			for (int z = chunkZ - chunkRadius; z <= chunkZ + chunkRadius; z++) {
				if (x != chunkX || z != chunkZ) level.getChunk(x, z);
			}
		}
		int minX = (chunkX - chunkRadius) << 4;
		int maxX = ((chunkX + chunkRadius + 1) << 4) - 1;
		int minZ = (chunkZ - chunkRadius) << 4;
		int maxZ = ((chunkZ + chunkRadius + 1) << 4) - 1;
		return findRecoverySpawnInBounds(level, centerX, centerZ, minX, maxX, minZ, maxZ, preferredY, yaw, pitch);
	}

	private Optional<RecoverySpawn> findRecoverySpawnInBounds(
			ServerLevel level,
			int centerX,
			int centerZ,
			int minX,
			int maxX,
			int minZ,
			int maxZ,
			java.util.OptionalInt preferredY,
			float yaw,
			float pitch
	) {
		return AgentRecoverySpawnPolicy.selectNearestDryPosition(
				centerX, centerZ, minX, maxX, minZ, maxZ,
				(x, z) -> recoveryColumn(level, x, z, preferredY)
		).map(selected -> new RecoverySpawn(
				level,
				Vec3.atBottomCenterOf(new BlockPos(selected.x(), selected.y(), selected.z())),
				yaw,
				pitch
		));
	}

	private AgentRecoverySpawnPolicy.Column recoveryColumn(
			ServerLevel level,
			int x,
			int z,
			java.util.OptionalInt preferredY
	) {
		if (preferredY.isEmpty()) {
			BlockPos surface = level.getHeightmapPos(Heightmap.Types.WORLD_SURFACE, new BlockPos(x, 0, z));
			return recoveryColumnAt(level, x, surface.getY(), z);
		}
		java.util.OptionalInt selected = AgentRecoverySpawnPolicy.selectNearestSafeY(
				preferredY.getAsInt(),
				level.getMinY() + 1,
				level.getMaxY() - 2,
				y -> recoveryColumnAt(level, x, y, z).safe()
		);
		if (selected.isEmpty()) {
			return new AgentRecoverySpawnPolicy.Column(
					preferredY.getAsInt(), false, false, false, false, false, false, false);
		}
		return recoveryColumnAt(level, x, selected.getAsInt(), z);
	}

	private AgentRecoverySpawnPolicy.Column recoveryColumnAt(ServerLevel level, int x, int y, int z) {
		BlockPos feet = new BlockPos(x, y, z);
		BlockPos floor = feet.below();
		BlockPos head = feet.above();
		var floorState = level.getBlockState(floor);
		BlockPos floorSupport = floor.below();
		boolean safeFloor = floorState.isFaceSturdy(level, floor, Direction.UP)
				&& !floorState.is(Blocks.CACTUS)
				&& !floorState.is(Blocks.MAGMA_BLOCK)
				&& !floorState.is(Blocks.CAMPFIRE)
				&& !floorState.is(Blocks.SOUL_CAMPFIRE)
				&& !floorState.is(Blocks.POWDER_SNOW);
		boolean stableFloor = !(floorState.getBlock() instanceof FallingBlock)
				|| level.getBlockState(floorSupport).isFaceSturdy(level, floorSupport, Direction.UP);
		return new AgentRecoverySpawnPolicy.Column(
				feet.getY(),
				safeFloor,
				stableFloor,
				level.getFluidState(floor).isEmpty(),
				level.getBlockState(feet).getCollisionShape(level, feet).isEmpty(),
				level.getFluidState(feet).isEmpty(),
				level.getBlockState(head).getCollisionShape(level, head).isEmpty(),
				level.getFluidState(head).isEmpty()
		);
	}

	private record RecoverySpawn(ServerLevel level, Vec3 position, float yaw, float pitch) {
	}

	record ExactRecoveryCoordinates(double x, double y, double z, float yaw, float pitch) {
	}

	@FunctionalInterface
	interface RecoveryColumnSafety {
		boolean safe(int x, int y, int z);
	}

	public void maintainChunkTickets() {
		long now = System.currentTimeMillis();
		for (AgentRecord record : records()) {
			if (record.state() == dev.agaminggod.arenaagents.agent.AgentLifecycleState.DEAD) {
				releaseChunkTicket(record.agentId());
				continue;
			}
			restoreChunkTicket(record);
			if (record.entityUuid().isPresent()) {
				findAgentPlayer(record.agentId()).ifPresent(entity -> trackChunkTicket(record.agentId(), entity, now));
			}
		}
	}

	public AgentRecord remove(String selector) {
		AgentRecord record = resolve(selector);
		Optional<ServerPlayer> player = findAgentPlayer(record.agentId());
		OfflineAgentPlayers.LegacyBodyMigration pendingLegacy = pendingLegacyMigrations().get(record.agentId());
		Optional<ServerPlayer> legacyPlayer = pendingLegacy == null
				? OfflineAgentPlayers.findLegacyPlayer(server, record.agentId(), record.profile())
				: Optional.of(pendingLegacy.legacyPlayer());
		retainedDeadPlayers().remove(record.agentId());
		SkitModeRuntime.stop(server, record.agentId());
		AgentInputRuntime.clear(server, record.agentId());
		long terminalRevision = record.goalRevision() == Long.MAX_VALUE
				? Long.MAX_VALUE
				: record.goalRevision() + 1L;
		AgentRecord removed = runtimeHooks.withinPublicationBoundary(() -> deleteAfterRequiredCleanup(
				() -> {
					player.ifPresent(OfflineAgentPlayers::remove);
					legacyPlayer.filter(legacy -> player.filter(legacy::equals).isEmpty())
							.ifPresent(OfflineAgentPlayers::remove);
				},
				() -> AgentRemovalCoordinator.removeRegistryFirst(
						savedData.registry(),
						record.agentId(),
						() -> {
							VanillaRespawnAttempt pendingRespawn = pendingVerifiedRespawns.remove(record.agentId());
							runCleanupSteps(
									() -> {
										if (pendingRespawn != null) rollbackVanillaRespawn(pendingRespawn);
									},
									() -> releaseChunkTicket(record.agentId()),
									() -> {
										if (pendingPlayerSpawns.remove(record.agentId()) != null) {
											cancelledPlayerSpawns.record(
													record.agentId(), record.profile(), System.currentTimeMillis());
										}
									},
									() -> pendingAgentRegistrations.remove(record.agentId()),
									() -> pendingEntityRecoveries.remove(record.agentId()),
									() -> pendingLegacyMigrations().remove(record.agentId()),
									() -> pendingLegacyCanonicalRemovals().remove(record.agentId()),
									() -> retainedDeadPlayers().remove(record.agentId()),
									() -> seenPlayers.remove(record.agentId()),
							() -> removeHiddenWorldName(OfflineAgentPlayers.playerName(
											record.agentId(), record.profile())),
									() -> groupSavedData.registry().removeMember(record.agentId())
							);
						},
						() -> runtimeHooks.onRemoved(record.agentId(), terminalRevision),
						failure -> LOGGER.warn("Post-delete cleanup failed for agent {}", record.agentId(), failure)
				)
		));
		runCleanupSteps(
				() -> lastLocationPersistenceEpochMs.remove(record.agentId()),
				() -> savedData.clearConversationWake(record.agentId()),
				() -> savedData.clearGoalDrafts(record.agentId()),
				() -> OfflineAgentPlayers.invalidateIdentity(record.agentId())
		);
		return removed;
	}

	static <T> T deleteAfterRequiredCleanup(Runnable requiredCleanup, Supplier<T> durableDelete) {
		Objects.requireNonNull(requiredCleanup, "required cleanup must not be null").run();
		return Objects.requireNonNull(durableDelete, "durable delete must not be null").get();
	}

	public boolean toggleAutomaticProgress(String selector) {
		AgentRecord record = resolve(selector);
		boolean enabled = !record.automaticProgress();
		savedData.registry().setAutomaticProgress(record.agentId(), enabled, System.currentTimeMillis());
		return enabled;
	}

	public boolean automaticProgress(AgentId agentId) {
		return savedData.registry().require(agentId).automaticProgress();
	}

	public String displayName(AgentRecord target) {
		Objects.requireNonNull(target, "target must not be null");
		return AgentIdentity.displayName(target.agentId(), target.profile());
	}

	public AgentRecord resolve(String selector) {
		return savedData.registry().resolve(selector);
	}

	public List<AgentRecord> records() {
		return savedData.registry().records();
	}

	/** Records already published, or restored through the coordinator handshake, and safe to reference on the wire. */
	public List<AgentRecord> coordinatorVisibleRecords() {
		AgentRegistry registry = savedData.registry();
		synchronized (registry) {
			Set<AgentId> pending = Set.copyOf(pendingAgentRegistrations);
			List<AgentRecord> records = registry.records();
			if (pending.isEmpty()) return records;
			return records.stream().filter(record -> !pending.contains(record.agentId())).toList();
		}
	}

	public boolean isCoordinatorVisible(AgentId agentId) {
		Objects.requireNonNull(agentId, "agentId must not be null");
		return !pendingAgentRegistrations.contains(agentId) && savedData.registry().contains(agentId);
	}

	private void publishPendingRegistration(AgentRecord record) {
		try {
			runtimeHooks.withinPublicationBoundary(() -> {
				if (pendingAgentRegistrations.contains(record.agentId()) && runtimeHooks.onCreated(record)) {
					pendingAgentRegistrations.remove(record.agentId());
				}
				return null;
			});
		} catch (RuntimeException exception) {
			LOGGER.warn("Could not publish verified agent registration for {}", record.agentId(), exception);
		}
	}

	public List<String> selectors() {
		return savedData.registry().selectors();
	}

	public AgentRegistry registry() {
		return savedData.registry();
	}

	public MinecraftServer server() {
		return server;
	}

	public Optional<CodexAgentEntity> findAgentEntity(AgentId agentId) {
		AgentRecord record = savedData.registry().require(agentId);
		return findEntity(record)
				.filter(CodexAgentEntity.class::isInstance)
				.map(CodexAgentEntity.class::cast)
				.or(() -> findLoadedAgentEntity(agentId));
	}

	public Optional<ServerPlayer> findAgentPlayer(AgentId agentId) {
		AgentRecord record = savedData.registry().require(agentId);
		return OfflineAgentPlayers.find(server, agentId, record.profile());
	}

	private Optional<CodexAgentEntity> findLoadedAgentEntity(AgentId agentId) {
		for (ServerLevel level : server.getAllLevels()) {
			for (Entity entity : level.getAllEntities()) {
				if (entity instanceof CodexAgentEntity agentEntity
						&& agentEntity.getAgentId().filter(agentId::equals).isPresent()) {
					return Optional.of(agentEntity);
				}
			}
		}
		return Optional.empty();
	}

	private Optional<Entity> findEntity(AgentRecord record) {
		Optional<AgentEntityRecoveryTarget> recoveryTarget = AgentEntityRecoveryTarget.from(record);
		if (recoveryTarget.isPresent()) {
			AgentEntityRecoveryTarget target = recoveryTarget.orElseThrow();
			Optional<ServerLevel> level = findLevel(target.location().dimension());
			if (level.isPresent()) {
				Entity entity = level.orElseThrow().getEntity(target.entityUuid());
				if (entity != null) {
					return Optional.of(entity);
				}
			}
		}
		return record.entityUuid().flatMap(this::findEntity);
	}

	private Optional<Entity> findEntity(UUID entityUuid) {
		for (ServerLevel level : server.getAllLevels()) {
			for (Entity entity : level.getAllEntities()) {
				if (entity.getUUID().equals(entityUuid)) {
					return Optional.of(entity);
				}
			}
		}
		return Optional.empty();
	}

	private void restoreChunkTicket(AgentRecord record) {
		AgentEntityRecoveryTarget.from(record).ifPresent(target -> findLevel(target.location().dimension()).ifPresent(level -> {
			AgentChunkTicket current = chunkTickets.get(record.agentId());
			AgentChunkTicket recovered = new AgentChunkTicket(
					level,
					new ChunkPos(target.location().chunkX(), target.location().chunkZ())
			);
			if (recovered.equals(current)) {
				return;
			}
			retainChunkTicket(recovered);
			if (current != null) {
				releaseChunkTicket(current);
			}
			chunkTickets.put(record.agentId(), recovered);
		}));
	}

	private Optional<ServerLevel> findLevel(String dimension) {
		for (ServerLevel level : server.getAllLevels()) {
			if (level.dimension().identifier().toString().equals(dimension)) {
				return Optional.of(level);
			}
		}
		return Optional.empty();
	}

	private static AgentEntityLocation entityLocation(Entity entity) {
		if (!(entity.level() instanceof ServerLevel level)) {
			throw new AgentDomainException("AGENT_LEVEL_INVALID", "Codex agent is not in a server level");
		}
		ChunkPos position = entity.chunkPosition();
		return AgentEntityLocation.exact(
				level.dimension().identifier().toString(),
				position.x(),
				position.z(),
				entity.getX(),
				entity.getY(),
				entity.getZ(),
				entity.getYRot(),
				entity.getXRot()
		);
	}

	private void trackChunkTicket(AgentId agentId, Entity entity, long nowEpochMs) {
		if (!(entity.level() instanceof ServerLevel level)) {
			throw new AgentDomainException("AGENT_LEVEL_INVALID", "Codex agent is not in a server level");
		}
		ChunkPos position = entity.chunkPosition();
		AgentChunkTicket current = chunkTickets.get(agentId);
		boolean chunkChanged = current == null || current.level() != level || !current.position().equals(position);
		if (current != null && current.level() == level && current.position().equals(position)) {
			persistEntityLocation(agentId, entity, nowEpochMs, false);
			return;
		}
		AgentChunkTicket next = new AgentChunkTicket(level, position);
		retainChunkTicket(next);
		if (current != null) {
			releaseChunkTicket(current);
		}
		chunkTickets.put(agentId, next);
		persistEntityLocation(agentId, entity, nowEpochMs, chunkChanged);
	}

	private void persistEntityLocation(AgentId agentId, Entity entity, long nowEpochMs, boolean force) {
		long lastPersisted = lastLocationPersistenceEpochMs.getOrDefault(agentId, Long.MIN_VALUE);
		if (!shouldPersistEntityLocation(lastPersisted, nowEpochMs, force)) return;
		savedData.registry().updateEntityLocation(agentId, entityLocation(entity), nowEpochMs);
		lastLocationPersistenceEpochMs.put(agentId, nowEpochMs);
	}

	static boolean shouldPersistEntityLocation(long lastPersistedEpochMs, long nowEpochMs, boolean force) {
		return force || lastPersistedEpochMs == Long.MIN_VALUE
				|| nowEpochMs < lastPersistedEpochMs
				|| nowEpochMs - lastPersistedEpochMs >= LOCATION_PERSIST_INTERVAL_MS;
	}

	private void persistLiveAgentLocations() {
		long now = System.currentTimeMillis();
		for (AgentRecord record : records()) {
			findAgentPlayer(record.agentId()).ifPresent(player ->
					persistEntityLocation(record.agentId(), player, now, true));
		}
	}

	private void releaseChunkTicket(AgentId agentId) {
		AgentChunkTicket removed = chunkTickets.remove(agentId);
		if (removed == null) {
			return;
		}
		releaseChunkTicket(removed);
	}

	private void retainChunkTicket(AgentChunkTicket ticket) {
		int references = chunkTicketReferences.getOrDefault(ticket, 0);
		if (references == 0) {
			ticket.level().getChunkSource().addTicketWithRadius(
					AGENT_TICKET_TYPE,
					ticket.position(),
					AGENT_TICKET_RADIUS
			);
		}
		chunkTicketReferences.put(ticket, references + 1);
	}

	private void releaseChunkTicket(AgentChunkTicket ticket) {
		Integer references = chunkTicketReferences.get(ticket);
		if (references == null) {
			return;
		}
		if (references > 1) {
			chunkTicketReferences.put(ticket, references - 1);
			return;
		}
		chunkTicketReferences.remove(ticket);
		ticket.level().getChunkSource().removeTicketWithRadius(
				AGENT_TICKET_TYPE,
				ticket.position(),
				AGENT_TICKET_RADIUS
		);
	}

	private void releaseChunkTickets() {
		Runnable[] cleanupSteps = List.copyOf(chunkTickets.keySet()).stream()
				.map(agentId -> (Runnable) () -> releaseChunkTicket(agentId))
				.toArray(Runnable[]::new);
		runCleanupSteps(cleanupSteps);
	}

	private record AgentChunkTicket(ServerLevel level, ChunkPos position) {
		private AgentChunkTicket {
			Objects.requireNonNull(level, "level must not be null");
			Objects.requireNonNull(position, "position must not be null");
		}
	}

	private final class ForwardingRuntimeHooks implements AgentRuntimeHooks {
		@Override
		public void onTransition(AgentTransition transition) {
			if (transition.cancelAction()) {
				findAgentPlayer(transition.after().agentId()).ifPresent(OfflineAgentPlayers::stop);
			}
			if (transition.after().state() == dev.agaminggod.arenaagents.agent.AgentLifecycleState.PLANNING
					&& transition.before().state() != transition.after().state()) {
				AgentChatReporter.planning(CodexAgentManager.this, transition.after());
			}
			runtimeHooks.onTransition(transition);
		}
	}
}
