package dev.agaminggod.arenaagents.server.runtime;

import carpet.helpers.EntityPlayerActionPack;
import carpet.script.utils.Tracer;
import com.google.gson.JsonObject;
import dev.agaminggod.arenaagents.agent.AgentDomainException;
import dev.agaminggod.arenaagents.agent.AgentId;
import dev.agaminggod.arenaagents.agent.AgentTransition;
import dev.agaminggod.arenaagents.protocol.ActionType;
import dev.agaminggod.arenaagents.server.AgentChatReporter;
import dev.agaminggod.arenaagents.server.AgentRuntimeRouter;
import dev.agaminggod.arenaagents.server.CodexAgentManager;
import dev.agaminggod.arenaagents.server.OfflineAgentPlayers;
import dev.agaminggod.arenaagents.server.perception.ObservationVisibility;
import dev.agaminggod.arenaagents.server.conversation.ConversationAudience;
import dev.agaminggod.arenaagents.server.conversation.ServerAgentConversationRouter;
import dev.agaminggod.arenaagents.server.runtime.controller.ServerController;
import dev.agaminggod.arenaagents.server.runtime.controller.ServerNavigationController;
import dev.agaminggod.arenaagents.server.runtime.controller.ServerItemPickupController;
import dev.agaminggod.arenaagents.server.runtime.controller.ServerPathPlanner;
import dev.agaminggod.arenaagents.server.runtime.transaction.ServerTransactionAdapter;
import dev.agaminggod.arenaagents.server.runtime.input.AgentInputRuntime;
import dev.agaminggod.arenaagents.server.runtime.input.AgentInputState;
import dev.agaminggod.arenaagents.server.runtime.input.AgentInputStates;
import dev.agaminggod.arenaagents.server.runtime.input.ControlSequence;
import dev.agaminggod.arenaagents.server.runtime.input.InputLease;
import dev.agaminggod.arenaagents.server.runtime.input.InputOwner;
import dev.agaminggod.arenaagents.server.runtime.input.LeasedServerInputController;
import dev.agaminggod.arenaagents.mixin.EntityPlayerActionPackAccessor;
import dev.agaminggod.arenaagents.mixin.ServerPlayerGameModeBreakAccessor;
import dev.agaminggod.arenaagents.server.voice.VoiceReceipt;
import dev.agaminggod.arenaagents.server.voice.VoiceRequest;
import dev.agaminggod.arenaagents.server.voice.VoiceSubsystemRuntime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.VoxelShape;

public final class ServerActionExecutor {
	private static final Set<ActionType> ARENA_SCRIPT_PRIMITIVES = Set.of(
			ActionType.MOVE_TO, ActionType.NAVIGATE_TO, ActionType.CONTROL, ActionType.CONTROL_SEQUENCE, ActionType.LOOK_AT, ActionType.ATTACK,
			ActionType.SELECT_ITEM, ActionType.USE_ITEM, ActionType.BREAK_BLOCK, ActionType.PLACE_BLOCK,
			ActionType.CHAT, ActionType.WAIT, ActionType.SET_DOOR, ActionType.PICK_UP_ITEM, ActionType.DROP_ITEM,
			ActionType.TRANSFER_CONTAINER, ActionType.CRAFT_INVENTORY, ActionType.CRAFT_TABLE,
			ActionType.FURNACE_TRANSACTION, ActionType.EQUIP_ITEM, ActionType.SELECT_TOOL,
			ActionType.BLOCK_WITH_SHIELD, ActionType.USE_RANGED, ActionType.RESPAWN
			, ActionType.INTERACT_BLOCK, ActionType.INTERACT_ENTITY, ActionType.DISMOUNT,
			ActionType.START_FALL_FLYING, ActionType.MENU_TRANSFER, ActionType.MENU_BUTTON,
			ActionType.ANVIL_RENAME, ActionType.MENU_CLICK, ActionType.MENU_CLOSE,
			ActionType.WAKE_UP, ActionType.SET_FLIGHT, ActionType.WRITE_SIGN, ActionType.EDIT_BOOK, ActionType.BEACON_EFFECTS
	);
	private static final Direction[] HORIZONTAL_PLACEMENT_DIRECTIONS = {
			Direction.NORTH, Direction.EAST, Direction.SOUTH, Direction.WEST
	};
	private static final long DEFAULT_TIMEOUT_MS = 60_000L;
	private static final long MOVEMENT_STALL_TIMEOUT_MS = 4_000L;
	private static final long PLACE_TIMEOUT_MS = 5_000L;
	private static final double PROGRESS_EMISSION_DELTA = 0.05D;
	private static final long PROGRESS_HEARTBEAT_MS = 1_000L;
	static final int MAX_CLEANUP_ATTEMPTS = 8;

	private final CodexAgentManager manager;
	private final AgentRuntimeRouter router;
	private final Consumer<ServerActionResult> resultSink;
	private final RespawnResultSink respawnResultSink;
	private final Consumer<ServerActionProgress> progressSink;
	private final ServerProtectionPolicy protection;
	private final ResourceLeaseManager resourceLeases;
	private final AdvancedInteractionService advancedInteractions;
	private final ServerAgentConversationRouter conversationRouter;
	private final ActionSuccessLedger actionSuccessLedger;
	private final Map<AgentId, ActiveAction> active = new LinkedHashMap<>();
	private final Map<AgentId, ActiveAction> quarantinedActions = new LinkedHashMap<>();
	private final Map<AgentId, CleanupRetry<ServerActionResult>> pendingCompletions = new LinkedHashMap<>();
	private final Map<AgentId, TerminalPublication<ServerActionResult>> pendingPublications = new LinkedHashMap<>();
	private final Map<AgentId, PendingRespawn> pendingRespawns = new LinkedHashMap<>();
	private final Map<AgentId, String> quarantinedAgents = new LinkedHashMap<>();
	private final Map<AgentId, ServerActionResult> lastResults = new LinkedHashMap<>();
	private long coordinatorGeneration;
	private long pathfindingRoundRobinCursor;

	public ServerActionExecutor(CodexAgentManager manager, Consumer<ServerActionResult> resultSink) {
		this(manager, resultSink, progress -> { }, ServerProtectionPolicy.TRUSTED_LOCAL_OPERATOR);
	}

	public ServerActionExecutor(
			CodexAgentManager manager,
			Consumer<ServerActionResult> resultSink,
			Consumer<ServerActionProgress> progressSink
	) {
		this(manager, resultSink, progressSink, ServerProtectionPolicy.TRUSTED_LOCAL_OPERATOR);
	}

	public ServerActionExecutor(
			CodexAgentManager manager,
			Consumer<ServerActionResult> resultSink,
			ServerProtectionPolicy protection
	) {
		this(manager, resultSink, progress -> { }, protection);
	}

	public ServerActionExecutor(
			CodexAgentManager manager,
			Consumer<ServerActionResult> resultSink,
			Consumer<ServerActionProgress> progressSink,
			ServerProtectionPolicy protection
	) {
		this(manager, resultSink, progressSink, protection, (result, transition, commit) -> {
			resultSink.accept(result);
			commit.run();
		});
	}

	public ServerActionExecutor(
			CodexAgentManager manager,
			Consumer<ServerActionResult> resultSink,
			Consumer<ServerActionProgress> progressSink,
			ServerProtectionPolicy protection,
			RespawnResultSink respawnResultSink
	) {
		this(
				manager,
				resultSink,
				progressSink,
				protection,
				respawnResultSink,
				new ServerAgentConversationRouter(manager, (event, wakeGoal) -> { })
		);
	}

	public ServerActionExecutor(
			CodexAgentManager manager,
			Consumer<ServerActionResult> resultSink,
			Consumer<ServerActionProgress> progressSink,
			ServerProtectionPolicy protection,
			RespawnResultSink respawnResultSink,
			ServerAgentConversationRouter conversationRouter
	) {
		this.manager = Objects.requireNonNull(manager, "manager must not be null");
		this.router = new AgentRuntimeRouter(manager);
		this.resultSink = Objects.requireNonNull(resultSink, "resultSink must not be null");
		this.respawnResultSink = Objects.requireNonNull(respawnResultSink, "respawnResultSink must not be null");
		this.progressSink = Objects.requireNonNull(progressSink, "progressSink must not be null");
		this.protection = Objects.requireNonNull(protection, "protection must not be null");
		this.resourceLeases = new ResourceLeaseManager();
		this.advancedInteractions = new AdvancedInteractionService(protection, resourceLeases);
		this.conversationRouter = Objects.requireNonNull(conversationRouter, "conversationRouter must not be null");
		this.actionSuccessLedger = new ActionSuccessLedger();
	}

	public ActionSuccessLedger actionSuccessLedger() { return actionSuccessLedger; }

	/** Legacy scenario/operator path. Model-authored commands must use submitProgramPrimitive. */
	synchronized void submitLegacy(ServerActionRequest request) {
		Objects.requireNonNull(request, "request must not be null");
		if (request.type() == ActionType.RESPAWN) {
			submitVanillaRespawn(request);
			return;
		}
		String quarantineReason = quarantinedAgents.get(request.agentId());
		if (quarantineReason != null) {
			throw new AgentDomainException("ACTION_RUNTIME_QUARANTINED", quarantineReason);
		}
		if (actionInFlight(request.agentId())) {
			throw new AgentDomainException("ACTION_ALREADY_ACTIVE", "Agent already has an active action");
		}
		if (request.type() == ActionType.COMPLETE_GOAL) {
			emit(request, ServerActionState.FAILED, "SERVER_VERIFICATION_REQUIRED",
					"Goal completion must use the server-owned verification request", 0L, false, false);
			return;
		}

		router.actionAccepted(request.agentId(), request.goalRevision());
		ServerPlayer player = null;
		try {
			player = manager.findAgentPlayer(request.agentId()).orElseThrow(
					() -> new AgentDomainException("AGENT_PLAYER_MISSING", "Agent player is not loaded")
			);
			// Mining validation is deliberately completed before the activity reporter runs.
			// A rejected target must never be announced as an action that has started.
			ActiveAction preparedMining = request.type() == ActionType.BREAK_BLOCK
					? createAction(request, player) : null;
			AgentChatReporter.acting(manager, manager.registry().require(request.agentId()), request);
			active.put(request.agentId(), preparedMining == null ? createAction(request, player) : preparedMining);
		} catch (RuntimeException exception) {
			try {
				router.actionFinished(request.agentId(), request.goalRevision());
			} catch (AgentDomainException stale) { }
			String reason = exception instanceof AgentDomainException domain ? domain.code() : "ACTION_REJECTED";
			ServerActionObservation rejectionObservation = request.type() == ActionType.BREAK_BLOCK && player != null
					? rejectedBreakObservation(player, request.arguments(), System.currentTimeMillis()) : null;
			emit(request, ServerActionState.FAILED, reason, safeMessage(exception), 0L, false, false,
					rejectionObservation);
		}
	}

	private void submitVanillaRespawn(ServerActionRequest request) {
		CodexAgentManager.VanillaRespawnAttempt attempt = null;
		try {
			if (actionInFlight(request.agentId())) {
				throw new AgentDomainException("ACTION_ALREADY_ACTIVE", "Agent already has an active action");
			}
			attempt = manager.requestVerifiedRespawn(request.agentId());
			AgentChatReporter.acting(manager, attempt.deadRecord(), request);
			pendingRespawns.put(request.agentId(), new PendingRespawn(
					request, attempt, new ElapsedTimeAccumulator(System.currentTimeMillis()), coordinatorGeneration
			));
		} catch (RuntimeException exception) {
			String reason = exception instanceof AgentDomainException domain ? domain.code() : "RESPAWN_REJECTED";
			emit(request, ServerActionState.FAILED, reason, safeMessage(exception), 0L, false, false);
		}
	}

	/** Executes only a model-authored physical primitive received through the coordinator bridge. */
	public synchronized void submitProgramPrimitive(ServerActionRequest request) {
		Objects.requireNonNull(request, "request must not be null");
		if (request.traceId() == null || request.provenance().traceId() == null) {
			throw new AgentDomainException("MISSING_TRACE_ID", "Model-authored actions require a non-null trace ID");
		}
		requireArenaScriptPrimitive(request.type());
		submitLegacy(request);
	}

	/** Delivers a validated direct or proximity reply without inventing an active goal for an idle agent. */
	public synchronized void submitConversationReply(ServerActionRequest request) {
		Objects.requireNonNull(request, "request must not be null");
		if (request.traceId() == null || request.provenance().traceId() == null) {
			throw new AgentDomainException("MISSING_TRACE_ID", "Model-authored actions require a non-null trace ID");
		}
		ConversationAudience audience = ConversationAudience.parse(nullableString(request.arguments(), "audience"));
		if (request.type() != ActionType.CHAT
				|| (audience != ConversationAudience.DIRECT && audience != ConversationAudience.PROXIMITY)) {
			throw new AgentDomainException(
					"INVALID_CONVERSATION_REPLY",
					"Detached conversation replies must use direct or proximity chat"
			);
		}
		if (actionInFlight(request.agentId())) {
			throw new AgentDomainException("ACTION_ALREADY_ACTIVE", "Agent already has an active action");
		}
		long startedAt = System.currentTimeMillis();
		try {
			manager.findAgentPlayer(request.agentId()).orElseThrow(
					() -> new AgentDomainException("AGENT_PLAYER_MISSING", "Agent player is not loaded")
			);
			AgentChatReporter.acting(manager, manager.registry().require(request.agentId()), request);
			sendConversation(request, request.arguments());
			emit(request, ServerActionState.SUCCEEDED, "ACTION_COMPLETED", "Action completed",
					System.currentTimeMillis() - startedAt, true, false);
		} catch (RuntimeException exception) {
			String reason = exception instanceof AgentDomainException domain ? domain.code() : "ACTION_REJECTED";
			emit(request, ServerActionState.FAILED, reason, safeMessage(exception),
					System.currentTimeMillis() - startedAt, true, false);
		}
	}

	public static boolean isArenaScriptPrimitive(ActionType type) {
		return ARENA_SCRIPT_PRIMITIVES.contains(Objects.requireNonNull(type, "type must not be null"));
	}

	public static void requireArenaScriptPrimitive(ActionType type) {
		if (!isArenaScriptPrimitive(type)) {
			throw new AgentDomainException("UNSUPPORTED_ARENA_SCRIPT_ACTION", "ArenaScript cannot invoke " + type.wireName());
		}
	}

	public synchronized void tick() {
		long now = System.currentTimeMillis();
		retryPendingPublications();
		List<ActiveAction> actions = new ArrayList<>(active.values());
		try (ServerPathPlanner.TickScope ignored = ServerPathPlanner.beginServerTick()) {
			for (PendingRespawn pending : new ArrayList<>(pendingRespawns.values())) tickRespawn(pending, now);
			for (ActiveAction action : new ArrayList<>(quarantinedActions.values())) {
				CleanupRetry<ServerActionResult> pending = pendingCompletions.get(action.request().agentId());
				if (pending != null) finish(action, pending.pending());
			}
			if (actions.isEmpty()) return;
			int start = roundRobinStart(pathfindingRoundRobinCursor, actions.size());
			pathfindingRoundRobinCursor++;
			for (int offset = 0; offset < actions.size(); offset++) {
				ActiveAction action = actions.get((start + offset) % actions.size());
				if (pendingPublications.containsKey(action.request().agentId())) continue;
				CleanupRetry<ServerActionResult> pending = pendingCompletions.get(action.request().agentId());
				if (pending != null) {
					finish(action, pending.pending());
					continue;
				}
				ServerActionResult result;
				try {
					result = action.tick(now);
				} catch (RuntimeException exception) {
					result = action.result(ServerActionState.FAILED, failureReason(exception), safeMessage(exception), now);
				}
				if (result != null) {
					finish(action, result);
				} else {
					ServerActionProgress progress = action.progress(now);
					if (progress != null) publishProgressBestEffort(progressSink, progress);
				}
			}
		}
	}

	/** Fences coordinator-owned physical work when the authenticated session disappears. */
	public synchronized void coordinatorDisconnected() {
		coordinatorGeneration++;
		for (ActiveAction action : new ArrayList<>(active.values())) {
			if (!action.isControl()) continue;
			try {
				finishDisconnectedControl(
						active,
						action.request().agentId(),
						action,
						() -> {
							ServerTransactionAdapter.runBestEffort(
									() -> pendingCompletions.remove(action.request().agentId()),
									action::neutralizeDisconnectedControl,
									() -> releaseResourceLease(action)
							);
						},
						() -> {
							try {
								router.actionFinished(action.request().agentId(), action.request().goalRevision());
							} catch (AgentDomainException stale) { }
						}
				);
			} catch (RuntimeException ignored) {
				// Every neutralization step is best effort and the action has already been fenced from later ticks.
			}
		}
		for (PendingRespawn pending : new ArrayList<>(pendingRespawns.values())) {
			finishDisconnectedRespawn(
					pendingRespawns,
					pending.request().agentId(),
					pending,
					() -> AgentChatReporter.respawnDisconnected(manager, pending.attempt().deadRecord())
			);
		}
	}

	static boolean finishDisconnectedControl(
			Map<AgentId, ?> activeActions,
			AgentId agentId,
			Object action,
			Runnable neutralize,
			Runnable lifecycleFinish
	) {
		if (!activeActions.remove(agentId, action)) return false;
		try {
			neutralize.run();
		} finally {
			lifecycleFinish.run();
		}
		return true;
	}

	static boolean finishDisconnectedRespawn(
			Map<AgentId, ?> pendingRespawns,
			AgentId agentId,
			Object pending,
			Runnable terminalReport
	) {
		if (!pendingRespawns.remove(agentId, pending)) return false;
		terminalReport.run();
		return true;
	}

	private void tickRespawn(PendingRespawn pending, long now) {
		try {
			if (!isCurrentCoordinatorGeneration(pending.coordinatorGeneration(), coordinatorGeneration)) {
				pendingRespawns.remove(pending.request().agentId(), pending);
				return;
			}
			CodexAgentManager.VerifiedRespawnState state = manager.verifiedRespawnState(pending.attempt());
			if (state == CodexAgentManager.VerifiedRespawnState.PENDING) return;
			pendingRespawns.remove(pending.request().agentId(), pending);
			if (state == CodexAgentManager.VerifiedRespawnState.SUCCEEDED) {
				quarantinedAgents.remove(pending.request().agentId());
				emit(pending.request(), ServerActionState.SUCCEEDED, "VANILLA_RESPAWNED",
						"Respawned at the vanilla target", pending.elapsedTime().advance(now), true, true);
			} else {
				emit(pending.request(), ServerActionState.FAILED, "RESPAWN_REJECTED",
						"The server-owned respawn did not complete", pending.elapsedTime().advance(now), false, false);
			}
		} catch (RuntimeException exception) {
			pendingRespawns.remove(pending.request().agentId(), pending);
			if (!isCurrentCoordinatorGeneration(pending.coordinatorGeneration(), coordinatorGeneration)
					|| isCoordinatorDisconnected(exception)) return;
			String reason = exception instanceof AgentDomainException domain ? domain.code() : "RESPAWN_REJECTED";
			emit(pending.request(), ServerActionState.FAILED, reason, safeMessage(exception), pending.elapsedTime().advance(now), false, false);
		}
	}

	private static boolean isCoordinatorDisconnected(RuntimeException exception) {
		return exception instanceof dev.agaminggod.arenaagents.server.bridge.BridgeProtocolException bridge
				&& "COORDINATOR_DISCONNECTED".equals(bridge.code());
	}

	static boolean isCurrentCoordinatorGeneration(long actionGeneration, long currentGeneration) {
		return actionGeneration == currentGeneration;
	}

	static int roundRobinStart(long cursor, int activeActionCount) {
		if (activeActionCount <= 0) return 0;
		return (int) Math.floorMod(cursor, activeActionCount);
	}

	static void publishProgressBestEffort(
			Consumer<ServerActionProgress> progressSink,
			ServerActionProgress progress
	) {
		try {
			progressSink.accept(progress);
		} catch (RuntimeException ignored) {
			// Progress is advisory telemetry; bridge backpressure must never abort the server tick.
		}
	}

	public synchronized boolean cancel(AgentId agentId, String reason) {
		PendingRespawn respawn = pendingRespawns.remove(agentId);
		if (respawn != null) {
			long elapsedMs = respawn.elapsedTime().advance(System.currentTimeMillis());
			if (manager.cancelVerifiedRespawn(respawn.attempt())) {
				emit(respawn.request(), ServerActionState.CANCELLED, "ACTION_CANCELLED",
						reason == null ? "Action cancelled" : reason, elapsedMs, false, false);
			} else {
				CodexAgentManager.VerifiedRespawnState state = manager.verifiedRespawnState(respawn.attempt());
				if (state == CodexAgentManager.VerifiedRespawnState.SUCCEEDED) {
					quarantinedAgents.remove(agentId);
					emit(respawn.request(), ServerActionState.SUCCEEDED, "VANILLA_RESPAWNED",
							"Respawned at the vanilla target", elapsedMs, true, true);
				} else {
					emit(respawn.request(), ServerActionState.FAILED, "RESPAWN_REJECTED",
							"The server-owned respawn did not complete", elapsedMs, false, false);
				}
			}
			return true;
		}
		ActiveAction action = active.get(agentId);
		if (action == null) action = quarantinedActions.get(agentId);
		if (pendingPublications.containsKey(agentId)) return true;
		if (action == null) return false;
		String cancellationReason = reason == null ? "Action cancelled" : reason;
		CleanupRetry<ServerActionResult> pending = pendingCompletions.get(agentId);
		if (pending != null) {
			finish(action, pending.pending());
			return true;
		}
		ServerActionResult result = action.result(
				ServerActionState.CANCELLED, "ACTION_CANCELLED", cancellationReason, System.currentTimeMillis());
		try {
			action.cancel(cancellationReason);
		} catch (RuntimeException teardownFailure) {
			CleanupRetry<ServerActionResult> retry = new CleanupRetry<>();
			retry.retain(result);
			retry.recordFailure();
			pendingCompletions.put(agentId, retry);
			return true;
		}
		complete(action, result);
		return true;
	}

	public synchronized boolean cancel(AgentId agentId, long goalRevision, String actionId, String reason) {
		Objects.requireNonNull(agentId, "agentId must not be null");
		Objects.requireNonNull(actionId, "actionId must not be null");
		ActiveAction action = active.get(agentId);
		if (action == null) action = quarantinedActions.get(agentId);
		PendingRespawn respawn = pendingRespawns.get(agentId);
		if (action == null && respawn == null) return false;
		if (respawn != null) {
			if (!matchesCancellation(respawn.request(), goalRevision, actionId)) throw new AgentDomainException("STALE_ACTION", "Cancellation does not match the active action");
			return cancel(agentId, reason);
		}
		if (!matchesCancellation(action.request(), goalRevision, actionId)) {
			throw new AgentDomainException("STALE_ACTION", "Cancellation does not match the active action");
		}
		return cancel(agentId, reason);
	}

	static boolean matchesCancellation(ServerActionRequest request, long goalRevision, String actionId) {
		Objects.requireNonNull(request, "request must not be null");
		return request.goalRevision() == goalRevision && request.actionId().equals(actionId);
	}

	private void complete(ActiveAction action, ServerActionResult result) {
		TerminalPublication<ServerActionResult> publication = retainPublication(result, true);
		attemptPublication(action, publication);
	}

	private boolean actionInFlight(AgentId agentId) {
		return active.containsKey(agentId)
				|| quarantinedActions.containsKey(agentId)
				|| pendingCompletions.containsKey(agentId)
				|| pendingPublications.containsKey(agentId)
				|| pendingRespawns.containsKey(agentId);
	}

	public synchronized ServerActionResult lastResult(AgentId agentId) {
		return lastResults.get(agentId);
	}

	/** Returns the active request for one agent without materializing the full active set. */
	public synchronized ServerActionRequest activeRequest(AgentId agentId) {
		Objects.requireNonNull(agentId, "agentId must not be null");
		ActiveAction action = active.get(agentId);
		return action == null ? null : action.request();
	}

	private ActiveAction createAction(ServerActionRequest request, ServerPlayer player) {
		JsonObject arguments = request.arguments();
		return switch (request.type()) {
			case CONTROL -> ActiveAction.control(
					request,
					player,
					new AgentInputState(
							(float) number(arguments, "forward"),
							(float) number(arguments, "strafe"),
							bool(arguments, "jump"),
							bool(arguments, "sneak"),
							bool(arguments, "sprint"),
							bool(arguments, "attack"),
							bool(arguments, "use"),
							(float) number(arguments, "yaw"),
							(float) number(arguments, "pitch"),
							integer(arguments, "selectedSlot"),
							hand(arguments)
					),
					integer(arguments, "ticks")
			);
			case CONTROL_SEQUENCE -> ActiveAction.controlSequence(request, player, ControlSequence.parse(arguments));
			case MOVE_TO, NAVIGATE_TO -> ActiveAction.controller(
					request,
					player,
					new ServerNavigationController(
							new Vec3(number(arguments, "x"), number(arguments, "y"), number(arguments, "z")),
							number(arguments, "tolerance"),
							bool(arguments, "sprint"),
							System.currentTimeMillis(),
							arguments.has("timeoutMs") ? integer(arguments, "timeoutMs") : DEFAULT_TIMEOUT_MS
					)
				);
			case LOOK_AT -> ActiveAction.immediate(request, player, () ->
					OfflineAgentPlayers.actions(player).lookAt(new Vec3(
							number(arguments, "x"),
							number(arguments, "y"),
							number(arguments, "z")
					)));
			case ATTACK -> ActiveAction.immediate(request, player,
					() -> attack(player, string(arguments, "targetId")));
			case SELECT_ITEM -> ActiveAction.immediate(request, player,
					() -> selectItem(player, string(arguments, "itemId")));
			case USE_ITEM -> ActiveAction.use(request, player, integer(arguments, "durationMs"),
					nullableString(arguments, "hand") != null ? hand(arguments) : InteractionHand.MAIN_HAND,
					nullableString(arguments, "expectedItemId"));
			case INTERACT_BLOCK -> ActiveAction.immediate(request, player, () -> interactBlock(
					player,
					blockPosition(arguments),
					Direction.byName(string(arguments, "face")),
					hand(arguments),
					string(arguments, "expectedItemId"), arguments
			));
			case INTERACT_ENTITY -> ActiveAction.immediate(request, player, () -> interactEntity(
					player,
					string(arguments, "targetId"),
					hand(arguments),
					string(arguments, "expectedItemId"), arguments
			));
			case DISMOUNT -> ActiveAction.immediate(request, player, () -> dismount(player));
			case START_FALL_FLYING -> ActiveAction.immediate(request, player, () -> startFallFlying(player));
			case WAKE_UP -> ActiveAction.immediate(request, player, () -> {
				if (!player.isSleeping()) throw new AgentDomainException("NOT_SLEEPING", "Player is not sleeping");
				player.stopSleepInBed(false, true);
				if (player.isSleeping()) throw new AgentDomainException("WAKE_NOT_CONFIRMED", "Vanilla wake-up was not observed");
			});
			case SET_FLIGHT -> ActiveAction.immediate(request, player, () -> {
				boolean enabled = bool(arguments, "enabled");
				if (enabled && !player.getAbilities().mayfly) throw new AgentDomainException("FLIGHT_NOT_ALLOWED", "Current player abilities do not permit flight");
				player.getAbilities().flying = enabled;
				player.onUpdateAbilities();
			});
			case WRITE_SIGN -> ActiveAction.transaction(request, player, PlayerTextInteraction.writeSign(player, arguments, protection));
			case EDIT_BOOK -> ActiveAction.transaction(request, player, PlayerTextInteraction.editBook(player, arguments));
			case BREAK_BLOCK -> {
				BlockPos position = blockPosition(arguments);
				String expectedBlockId = requiredExpectedBlockId(arguments);
				String initialBlockId = validateBreakTarget(player, position, expectedBlockId);
				yield ActiveAction.breakBlock(
						request, player, position, integer(arguments, "timeoutMs"), initialBlockId, expectedBlockId
				);
			}
			case PLACE_BLOCK -> {
				BlockPos position = blockPosition(arguments);
				String itemId = string(arguments, "itemId");
				String initialBlockId = blockId(player.level().getBlockState(position));
				String expectedBlockId = expectedBlockId(itemId);
				DesiredBlockState desiredBlockState = DesiredBlockState.parse(
						arguments.has("desiredState") && !arguments.get("desiredState").isJsonNull()
								? arguments.get("desiredState").getAsString() : null,
						expectedBlockId
				);
				Direction requestedFace = Direction.byName(string(arguments, "face"));
				if (!initialBlockId.equals(expectedBlockId)) {
					validatePlaceBlock(protection, player, position, requestedFace, itemId);
				}
				int initialItemCount = inventoryItemCount(player, itemId);
				String resourceKey = blockResourceKey(player, position);
				if (!resourceLeases.acquire(
						resourceKey,
						request.agentId(),
						System.currentTimeMillis(),
						PLACE_TIMEOUT_MS
				)) {
					throw new AgentDomainException("RESOURCE_BUSY", "Another agent is already placing at this target");
				}
				try {
					ActiveAction action = ActiveAction.placeBlock(
							request,
							player,
							() -> placeBlock(protection, player, position, requestedFace, itemId, desiredBlockState),
							position,
							initialBlockId,
							expectedBlockId,
							desiredBlockState,
							requestedFace,
							itemId,
							initialItemCount
					);
					action.resourceLeaseKey = resourceKey;
					yield action;
				} catch (RuntimeException exception) {
					resourceLeases.release(resourceKey, request.agentId());
					throw exception;
				}
			}
			case CHAT -> ActiveAction.immediate(request, player, () -> sendConversation(request, arguments));
			case WAIT -> ActiveAction.waitFor(request, player, integer(arguments, "durationMs"));
			case SET_DOOR -> ActiveAction.immediate(request, player, () -> requireResult(
					advancedInteractions.setDoor(
							request.agentId(),
							player,
							blockPosition(arguments),
							bool(arguments, "open")
					)
			));
			case PICK_UP_ITEM -> {
				ItemEntity item = findItem(player, string(arguments, "targetSelector"));
				requireResult(advancedInteractions.validatePickUp(player, item));
				yield ActiveAction.controller(request, player,
						new ServerItemPickupController(player, item, System.currentTimeMillis()));
			}
			case DROP_ITEM -> ActiveAction.immediate(request, player, () -> requireResult(
					advancedInteractions.drop(player, integer(arguments, "slot"), integer(arguments, "count"))
			));
			case TRANSFER_CONTAINER, CRAFT_INVENTORY, CRAFT_TABLE, FURNACE_TRANSACTION,
					EQUIP_ITEM, SELECT_TOOL, BLOCK_WITH_SHIELD, USE_RANGED,
					MENU_TRANSFER, MENU_BUTTON, ANVIL_RENAME, MENU_CLICK, MENU_CLOSE, BEACON_EFFECTS -> ActiveAction.transaction(
					request,
					player,
					advancedInteractions.begin(player, request, arguments)
			);
			case RESPAWN, COMPLETE_GOAL -> throw new IllegalStateException("respawn and complete_goal are handled before action creation");
			default -> throw new AgentDomainException(
					"UNSUPPORTED_ACTION",
					"Action type is not supported by the server executor: " + request.type().wireName()
			);
		};
	}

	private void finish(ActiveAction action, ServerActionResult result) {
		AgentId agentId = action.request().agentId();
		CleanupRetry<ServerActionResult> retry = pendingCompletions.computeIfAbsent(agentId, ignored -> new CleanupRetry<>());
		retry.retain(result);
		ServerActionResult completed;
		try {
			completed = retry.complete(action::cleanup);
		} catch (RuntimeException teardownFailure) {
			if (retry.exhausted(MAX_CLEANUP_ATTEMPTS) && !quarantinedAgents.containsKey(agentId)) {
				quarantine(action, teardownFailure);
			}
			return;
		}
		if (quarantinedAgents.containsKey(agentId)) {
			recoverQuarantined(action);
			return;
		}
		complete(action, completed);
	}

	private void quarantine(ActiveAction action, RuntimeException teardownFailure) {
		AgentId agentId = action.request().agentId();
		String message = "Action cleanup failed after " + MAX_CLEANUP_ATTEMPTS
				+ " attempts; physical actions are quarantined while cleanup retries automatically: "
				+ safeMessage(teardownFailure);
		quarantinedAgents.put(agentId, message);
		active.remove(agentId, action);
		quarantinedActions.put(agentId, action);
		publish(action.result(ServerActionState.FAILED, "ACTION_CLEANUP_FAILED", message, System.currentTimeMillis()));
	}

	private void recoverQuarantined(ActiveAction action) {
		AgentId agentId = action.request().agentId();
		releaseResourceLease(action);
		try {
			router.actionFinished(agentId, action.request().goalRevision());
		} catch (AgentDomainException stale) { }
		quarantinedActions.remove(agentId, action);
		pendingCompletions.remove(agentId);
		quarantinedAgents.remove(agentId);
	}

	private TerminalPublication<ServerActionResult> retainPublication(
			ServerActionResult result,
			boolean completesAction
	) {
		AgentId agentId = result.agentId();
		TerminalPublication<ServerActionResult> existing = pendingPublications.get(agentId);
		if (existing != null) return existing;
		TerminalPublication<ServerActionResult> publication = new TerminalPublication<>(result, completesAction);
		pendingPublications.put(agentId, publication);
		return publication;
	}

	private void retryPendingPublications() {
		for (var entry : new ArrayList<>(pendingPublications.entrySet())) {
			TerminalPublication<ServerActionResult> publication = entry.getValue();
			attemptPublication(publication.completesAction() ? active.get(entry.getKey()) : null, publication);
		}
	}

	private void attemptPublication(
			ActiveAction action,
			TerminalPublication<ServerActionResult> publication
	) {
		try {
			boolean delivered = publication.publish(
					() -> preparePublication(action, publication.value()),
					resultSink
			);
			if (!delivered) return;
		} catch (RuntimeException publicationFailure) {
			return;
		}
		ServerActionResult result = publication.value();
		if (action != null) {
			active.remove(result.agentId(), action);
			pendingCompletions.remove(result.agentId());
		}
		pendingPublications.remove(result.agentId(), publication);
	}

	private void preparePublication(ActiveAction action, ServerActionResult result) {
		if (action != null) {
			releaseResourceLease(action);
			try {
				router.actionFinished(result.agentId(), result.goalRevision());
			} catch (AgentDomainException stale) { }
		}
		lastResults.put(result.agentId(), result);
		actionSuccessLedger.record(result);
		try {
			AgentChatReporter.result(manager, manager.registry().require(result.agentId()), result);
		} catch (RuntimeException ignored) {
			// Terminal delivery must survive presentation or agent-removal races.
		}
	}

	static final class CleanupRetry<T> {
		private T pending;
		private int failureCount;

		synchronized void retain(T candidate) {
			Objects.requireNonNull(candidate, "candidate must not be null");
			if (pending == null) pending = candidate;
		}

		synchronized boolean hasPending() {
			return pending != null;
		}

		synchronized T pending() {
			if (pending == null) throw new IllegalStateException("no cleanup result is pending");
			return pending;
		}

		synchronized T complete(Runnable cleanup) {
			Objects.requireNonNull(cleanup, "cleanup must not be null");
			T retained = pending();
			try {
				cleanup.run();
			} catch (RuntimeException failure) {
				failureCount++;
				throw failure;
			}
			pending = null;
			return retained;
		}

		synchronized int failureCount() {
			return failureCount;
		}

		synchronized void recordFailure() {
			failureCount++;
		}

		synchronized boolean exhausted(int maximumAttempts) {
			if (maximumAttempts <= 0) throw new IllegalArgumentException("maximumAttempts must be positive");
			return failureCount >= maximumAttempts;
		}
	}

	static final class TerminalPublication<T> {
		private enum Phase { NEW, PREPARING, READY, DELIVERING, DELIVERED }

		private final T value;
		private final boolean completesAction;
		private Phase phase = Phase.NEW;

		TerminalPublication(T value) {
			this(value, false);
		}

		TerminalPublication(T value, boolean completesAction) {
			this.value = Objects.requireNonNull(value, "value must not be null");
			this.completesAction = completesAction;
		}

		synchronized T value() {
			return value;
		}

		synchronized boolean hasPending() {
			return phase != Phase.DELIVERED;
		}

		boolean completesAction() {
			return completesAction;
		}

		synchronized boolean publish(Runnable preparation, Consumer<T> sink) {
			Objects.requireNonNull(preparation, "preparation must not be null");
			Objects.requireNonNull(sink, "sink must not be null");
			if (phase == Phase.DELIVERED) return true;
			if (phase == Phase.PREPARING || phase == Phase.DELIVERING) return false;
			if (phase == Phase.NEW) {
				phase = Phase.PREPARING;
				try {
					preparation.run();
					phase = Phase.READY;
				} catch (RuntimeException failure) {
					phase = Phase.NEW;
					throw failure;
				}
			}
			phase = Phase.DELIVERING;
			try {
				sink.accept(value);
				phase = Phase.DELIVERED;
				return true;
			} catch (RuntimeException failure) {
				phase = Phase.READY;
				throw failure;
			}
		}
	}

	private void releaseResourceLease(ActiveAction action) {
		if (action.resourceLeaseKey != null) {
			resourceLeases.release(action.resourceLeaseKey, action.request().agentId());
		}
	}

	private void emit(
			ServerActionRequest request,
			ServerActionState state,
			String reasonCode,
			String message,
			long elapsedMs,
			boolean executionStarted,
			boolean physicalAttempted
	) {
		emit(request, state, reasonCode, message, elapsedMs, executionStarted, physicalAttempted, null);
	}

	private void emit(
			ServerActionRequest request,
			ServerActionState state,
			String reasonCode,
			String message,
			long elapsedMs,
			boolean executionStarted,
			boolean physicalAttempted,
			ServerActionObservation actionObservation
	) {
		publish(new ServerActionResult(
				request.agentId(),
				request.goalRevision(),
				request.actionId(),
				request.type(),
				request.traceId(),
				state,
				reasonCode,
				message == null ? "" : message,
				elapsedMs,
				System.currentTimeMillis(),
				executionStarted,
				physicalAttempted,
				actionObservation
		));
	}

	private void publish(ServerActionResult result) {
		attemptPublication(null, retainPublication(result, false));
	}

	private void publishRespawn(ServerActionResult result, AgentTransition transition, Runnable commit) {
		respawnResultSink.publish(result, transition, commit);
		lastResults.put(result.agentId(), result);
		try {
			AgentChatReporter.result(manager, manager.registry().require(result.agentId()), result);
		} catch (AgentDomainException ignored) { }
	}

	private static ServerActionResult result(
			ServerActionRequest request,
			ServerActionState state,
			String reasonCode,
			String message,
			long elapsedMs,
			boolean executionStarted,
			boolean physicalAttempted
	) {
		return new ServerActionResult(request.agentId(), request.goalRevision(), request.actionId(), request.type(), request.traceId(), state,
				reasonCode, message == null ? "" : message, Math.max(0L, elapsedMs), System.currentTimeMillis(), executionStarted, physicalAttempted);
	}

	@FunctionalInterface
	public interface RespawnResultSink {
		void publish(ServerActionResult result, AgentTransition transition, Runnable commit);
	}

	private record PendingRespawn(
			ServerActionRequest request,
			CodexAgentManager.VanillaRespawnAttempt attempt,
			ElapsedTimeAccumulator elapsedTime,
			long coordinatorGeneration
	) { }

	private void attack(ServerPlayer player, String targetId) {
		Entity target = resolveExactObservedTarget(player, targetId);
		if (target instanceof ServerPlayer targetPlayer
				&& (targetPlayer.isCreative() || targetPlayer.isSpectator())) {
			throw new AgentDomainException("TARGET_INVULNERABLE", "Creative and spectator players cannot be valid combat targets");
		}
		if (!protection.mayInteractWithEntity(player, target)) {
			throw new AgentDomainException("PROTECTION_DENIED", "Attack was denied");
		}
		if (!player.isWithinAttackRange(player.getMainHandItem(), target.getBoundingBox(), 0.0D)) {
			throw new AgentDomainException("TARGET_TOO_FAR", "Attack target is out of reach");
		}
		player.lookAt(net.minecraft.commands.arguments.EntityAnchorArgument.Anchor.EYES, target.getEyePosition());
		HitResult hit = Tracer.rayTrace(player, 1.0F,
				Math.max(player.entityInteractionRange(), player.getEyePosition().distanceTo(target.getEyePosition()) + 0.1D), false);
		if (!(hit instanceof EntityHitResult entityHit) || entityHit.getEntity() != target) {
			throw new AgentDomainException("TARGET_OBSTRUCTED", "Attack target is not under the requested aim ray");
		}
		player.attack(target);
		player.swing(net.minecraft.world.InteractionHand.MAIN_HAND);
	}

	/** Resolves only the exact UUID supplied from the agent's retained observation. */
	static Entity resolveExactObservedTarget(ServerPlayer player, String targetId) {
		final UUID uuid;
		try {
			uuid = UUID.fromString(targetId);
		} catch (IllegalArgumentException exception) {
			throw new AgentDomainException("TARGET_NOT_FOUND", "Target id is not a UUID");
		}
		Entity target = player.level().getEntity(uuid);
		if (target == null || target.level() != player.level() || !target.isAlive() || target == player) {
			throw new AgentDomainException("TARGET_UNAVAILABLE", "Observed target is no longer available");
		}
		if (!ObservationVisibility.canSeeEntity(player, target)) {
			throw new AgentDomainException("TARGET_NOT_VISIBLE", "Observed target is no longer visible");
		}
		return target;
	}

	private static void selectItem(ServerPlayer player, String itemId) {
		Identifier identifier = Identifier.tryParse(itemId);
		if (identifier == null || !BuiltInRegistries.ITEM.containsKey(identifier)) {
			throw new AgentDomainException("UNKNOWN_ITEM", "Unknown item: " + itemId);
		}
		int found = -1;
		for (int slot = 0; slot < player.getInventory().getContainerSize(); slot++) {
			ItemStack stack = player.getInventory().getItem(slot);
			if (!stack.isEmpty() && BuiltInRegistries.ITEM.getKey(stack.getItem()).equals(identifier)) {
				if (found >= 0) throw new AgentDomainException("AMBIGUOUS_ITEM", "Several slots match this item; choose an exact slot with control, select_tool, or menu_click");
				found = slot;
			}
		}
		if (found < 0 && player.isCreative()) {
			found = player.getInventory().getSelectedSlot();
			if (!player.getInventory().getItem(found).isEmpty()) {
				throw new AgentDomainException("HOTBAR_SLOT_OCCUPIED", "Select an empty hotbar slot before taking a creative item");
			}
			player.getInventory().setItem(found, new ItemStack(BuiltInRegistries.ITEM.getValue(identifier)));
		}
		if (found < 0) throw new AgentDomainException("ITEM_NOT_FOUND", "Agent does not have " + itemId);
		if (found > 8) {
			throw new AgentDomainException("ITEM_NOT_IN_HOTBAR", "Move the exact inventory slot into a chosen hotbar slot using select_tool or menu_click");
		}
		OfflineAgentPlayers.actions(player).setSlot(found + 1);
	}

	private static void validatePlaceBlock(
			ServerProtectionPolicy protection,
			ServerPlayer player,
			BlockPos position,
			Direction requestedFace,
			String itemId
	) {
		requirePlacementProtection(protection, player, player.level(), position);
		String currentBlockId = blockId(player.level().getBlockState(position));
		if (!player.level().getBlockState(position).canBeReplaced()) {
			throw new AgentDomainException("TARGET_OCCUPIED", "Placement target contains " + currentBlockId);
		}
		if (!BuiltInRegistries.ITEM.getKey(player.getMainHandItem().getItem()).toString().equals(itemId)) selectItem(player, itemId);
		if (!(player.getMainHandItem().getItem() instanceof BlockItem)) {
			throw new AgentDomainException("ITEM_NOT_PLACEABLE", itemId + " is not a block item");
		}
		if (placementFace(player, position, requestedFace) == null) {
			throw new AgentDomainException("NO_PLACEMENT_SUPPORT", "No adjacent solid face can support this placement");
		}
		BlockHitResult hit = placementHit(player, position, requestedFace);
		if (hit == null) {
			throw new AgentDomainException("NO_PLACEMENT_SUPPORT", "No adjacent solid face can support this placement");
		}
		requirePlacementGates(protection, player, position, hit);
	}

	static void requirePlacementProtection(
			ServerProtectionPolicy policy,
			ServerPlayer player,
			ServerLevel level,
			BlockPos position
	) {
		if (policy == null || !policy.mayModifyBlock(player, level, position)) {
			throw new AgentDomainException("PROTECTION_DENIED", "Block placement denied");
		}
	}

	private static void placeBlock(
			ServerProtectionPolicy protection,
			ServerPlayer player,
			BlockPos position,
			Direction requestedFace,
			String itemId,
			DesiredBlockState desiredBlockState
	) {
		validatePlaceBlock(protection, player, position, requestedFace, itemId);
		BlockHitResult hit = placementHit(player, position, requestedFace);
		if (hit == null) {
			throw new AgentDomainException("NO_PLACEMENT_SUPPORT", "No adjacent solid face can support this placement");
		}
		ItemStack stack = player.getMainHandItem();
		orientPlayerForDesiredState(player, (BlockItem) stack.getItem(), stack, hit, desiredBlockState);
		InteractionResult result = player.gameMode.useItemOn(
				player,
				player.level(),
				stack,
				InteractionHand.MAIN_HAND,
				hit
		);
		if (result.consumesAction()) player.swing(InteractionHand.MAIN_HAND);
	}

	private static BlockHitResult placementHit(ServerPlayer player, BlockPos position, Direction requestedFace) {
		Direction face = placementFace(player, position, requestedFace);
		if (face == null) return null;
		BlockPos support = position.relative(face.getOpposite());
		return new BlockHitResult(
				placementLookTarget(support, face),
				face,
				support,
				false
		);
	}

	private static void requirePlacementGates(
			ServerProtectionPolicy protection,
			ServerPlayer player,
			BlockPos target,
			BlockHitResult hit
	) {
		ServerLevel level = player.level();
		BlockPos support = hit.getBlockPos();
		requirePlacementProtection(protection, player, level, target);
		requirePlacementProtection(protection, player, level, support);
		if (level.getServer().isUnderSpawnProtection(level, target, player)
				|| level.getServer().isUnderSpawnProtection(level, support, player)
				|| !level.mayInteract(player, target)
				|| !level.mayInteract(player, support)) {
			throw new AgentDomainException("PROTECTION_DENIED", "Vanilla placement protection denied the target or support block");
		}
		if (!player.isWithinBlockInteractionRange(support, 0.0D)
				|| !isValidPlacementHit(support, hit.getLocation())) {
			throw new AgentDomainException("TARGET_TOO_FAR", "Placement hit is out of reach");
		}
	}

	static boolean isValidPlacementHit(BlockPos support, Vec3 hitLocation) {
		Vec3 offset = hitLocation.subtract(Vec3.atCenterOf(support));
		return Math.abs(offset.x()) < 1.0000001D
				&& Math.abs(offset.y()) < 1.0000001D
				&& Math.abs(offset.z()) < 1.0000001D;
	}

	static float directionalPlacementYaw(Direction direction) {
		if (direction == null || !direction.getAxis().isHorizontal()) {
			throw new IllegalArgumentException("direction must be horizontal");
		}
		return direction.toYRot();
	}

	private static void orientPlayerForDesiredState(
			ServerPlayer player,
			BlockItem blockItem,
			ItemStack stack,
			BlockHitResult hit,
			DesiredBlockState desiredBlockState
	) {
		String requestedFacing = desiredBlockState.properties().get("facing");
		Direction requestedDirection = requestedFacing == null ? null : Direction.byName(requestedFacing);
		float originalYaw = player.getYRot();
		float originalHeadYaw = player.getYHeadRot();
		if (requestedDirection == null || !requestedDirection.getAxis().isHorizontal()) {
			BlockState predicted = predictedPlacementState(player, blockItem, stack, hit);
			if (predicted == null || !desiredBlockState.matches(predicted)) {
				throw new AgentDomainException(
						"PLACEMENT_STATE_MISMATCH",
						"Requested block state cannot be produced by vanilla placement context"
				);
			}
			return;
		}
		for (Direction direction : HORIZONTAL_PLACEMENT_DIRECTIONS) {
			float yaw = directionalPlacementYaw(direction);
			player.setYRot(yaw);
			player.setYHeadRot(yaw);
			BlockState predicted = predictedPlacementState(player, blockItem, stack, hit);
			if (predicted != null && desiredBlockState.matches(predicted)) return;
		}
		player.setYRot(originalYaw);
		player.setYHeadRot(originalHeadYaw);
		throw new AgentDomainException(
				"PLACEMENT_STATE_MISMATCH",
				"Requested directional state cannot be produced by vanilla placement context"
		);
	}

	private static BlockState predictedPlacementState(
			ServerPlayer player,
			BlockItem blockItem,
			ItemStack stack,
			BlockHitResult hit
	) {
		BlockPlaceContext context = blockItem.updatePlacementContext(
				new BlockPlaceContext(player, InteractionHand.MAIN_HAND, stack, hit)
		);
		return context == null ? null : blockItem.getBlock().getStateForPlacement(context);
	}

	private static Direction placementFace(ServerPlayer player, BlockPos position, Direction requestedFace) {
		return BlockPlacementAttemptPolicy.chooseFace(requestedFace, candidate -> {
			BlockPos support = position.relative(candidate.getOpposite());
			return player.level().getBlockState(support).isFaceSturdy(player.level(), support, candidate);
		});
	}

	private static int inventoryItemCount(ServerPlayer player, String itemId) {
		Identifier identifier = Identifier.tryParse(itemId);
		if (identifier == null || !BuiltInRegistries.ITEM.containsKey(identifier)) return 0;
		int count = 0;
		for (int slot = 0; slot < player.getInventory().getContainerSize(); slot++) {
			ItemStack stack = player.getInventory().getItem(slot);
			if (!stack.isEmpty() && BuiltInRegistries.ITEM.getKey(stack.getItem()).equals(identifier)) {
				count += stack.getCount();
			}
		}
		return count;
	}

	static Vec3 placementLookTarget(BlockPos support, Direction face) {
		Vec3 center = Vec3.atCenterOf(support);
		return center.add(
				face.getStepX() * 0.499D,
				face.getStepY() * 0.499D,
				face.getStepZ() * 0.499D
		);
	}

	private static String expectedBlockId(String itemId) {
		Identifier identifier = Identifier.tryParse(itemId);
		if (identifier == null || !BuiltInRegistries.ITEM.containsKey(identifier)) {
			throw new AgentDomainException("UNKNOWN_ITEM", "Unknown item: " + itemId);
		}
		if (!(BuiltInRegistries.ITEM.getValue(identifier) instanceof BlockItem blockItem)) {
			throw new AgentDomainException("ITEM_NOT_PLACEABLE", itemId + " is not a block item");
		}
		return BuiltInRegistries.BLOCK.getKey(blockItem.getBlock()).toString();
	}

	private static String blockId(net.minecraft.world.level.block.state.BlockState state) {
		return BuiltInRegistries.BLOCK.getKey(state.getBlock()).toString();
	}

	private static String requiredExpectedBlockId(JsonObject arguments) {
		if (!arguments.has("expectedBlockId") || arguments.get("expectedBlockId").isJsonNull()) {
			throw new AgentDomainException(
					"MISSING_EXPECTED_BLOCK_ID",
					"Mining requires the exact non-air blockId from the latest observation"
			);
		}
		String expected = arguments.get("expectedBlockId").getAsString();
		if (expected.isBlank()) {
			throw new AgentDomainException(
					"MISSING_EXPECTED_BLOCK_ID",
					"Mining requires the exact non-air blockId from the latest observation"
			);
		}
		return expected;
	}

	/**
	 * Admission gate for a break request. Carpet's ray trace and vanilla's
	 * interaction-range check are the source of truth; coordinates alone are
	 * never sufficient to start a mining action.
	 */
	private static String validateBreakTarget(ServerPlayer player, BlockPos position, String expectedBlockId) {
		BlockState state = player.level().getBlockState(position);
		if (state.isAir()) {
			throw new AgentDomainException("TARGET_AIR", "Cannot mine air at " + position);
		}
		String currentBlockId = blockId(state);
		if (!expectedBlockId.equals(currentBlockId)) {
			throw new AgentDomainException(
					"TARGET_CHANGED",
					"Expected " + expectedBlockId + " at " + position + " but found " + currentBlockId
			);
		}
		if (!player.isWithinBlockInteractionRange(position, 0.0D)) {
			throw new AgentDomainException("TARGET_TOO_FAR", "Block is out of reach");
		}
		if (player.blockActionRestricted(player.level(), position, player.gameMode.getGameModeForPlayer())) {
			throw new AgentDomainException("GAME_MODE_RESTRICTED", "The current game mode cannot break this block");
		}
		HitResult hit = breakRayTarget(player);
		if (!(hit instanceof BlockHitResult blockHit) || !position.equals(blockHit.getBlockPos())) {
			throw new AgentDomainException("TARGET_NOT_VISIBLE", "The requested block is not the exact block under the crosshair");
		}
		return currentBlockId;
	}

	private static HitResult breakRayTarget(ServerPlayer player) {
		double reach = player.blockInteractionRange();
		return Tracer.rayTrace(player, 1.0F, reach, false);
	}

	private static ServerActionObservation rejectedBreakObservation(
			ServerPlayer player,
			JsonObject arguments,
			long now
	) {
		try {
			BlockPos target = blockPosition(arguments);
			BlockState currentState = player.level().getBlockState(target);
			String currentBlockId = blockId(currentState);
			String expectedBlockId = arguments.has("expectedBlockId")
					&& !arguments.get("expectedBlockId").isJsonNull()
					&& !arguments.get("expectedBlockId").getAsString().isBlank()
					? arguments.get("expectedBlockId").getAsString() : null;
			HitResult hit = breakRayTarget(player);
			Vec3 eye = player.getEyePosition();
			ServerActionObservation.RayTarget lookedAt;
			if (hit instanceof BlockHitResult blockHit) {
				BlockPos hitPosition = blockHit.getBlockPos();
				lookedAt = new ServerActionObservation.RayTarget(
						"block",
						new ServerActionObservation.Position(hitPosition.getX(), hitPosition.getY(), hitPosition.getZ()),
						blockId(player.level().getBlockState(hitPosition)),
						blockHit.getDirection().getSerializedName(),
						eye.distanceTo(hit.getLocation())
				);
			} else {
				lookedAt = new ServerActionObservation.RayTarget(
						hit.getType().name().toLowerCase(java.util.Locale.ROOT),
						null, null, null, eye.distanceTo(hit.getLocation())
				);
			}
			Vec3 velocity = player.getDeltaMovement();
			double maxReach = player.blockInteractionRange();
			double distance = Math.sqrt(player.distanceToSqr(Vec3.atCenterOf(target)));
			return new ServerActionObservation(
					Math.max(0L, player.level().getGameTime()),
					now,
					new ServerActionObservation.Position(player.getX(), player.getY(), player.getZ()),
					new ServerActionObservation.Position(velocity.x(), velocity.y(), velocity.z()),
					player.getYRot(),
					player.getXRot(),
					new ServerActionObservation.Collision(
							player.horizontalCollision, player.verticalCollision, player.isInWall()),
					lookedAt,
					new ServerActionObservation.Reach(
							distance, maxReach, player.isWithinBlockInteractionRange(target, 1.0D)),
					new ServerActionObservation.Target(
							"block",
							new ServerActionObservation.Position(target.getX(), target.getY(), target.getZ()),
							expectedBlockId,
							currentBlockId,
							currentBlockId,
							currentBlockId,
							false,
							Math.max(0.0D, distance - maxReach),
							null,
							null
					),
					new ServerActionObservation.Progress(0.0D, "none", true)
			);
		} catch (RuntimeException ignored) {
			return null;
		}
	}

	private static String blockResourceKey(ServerPlayer player, BlockPos position) {
		return "block:" + player.level().dimension().identifier() + ":" + position.asLong();
	}

	private static ItemEntity findItem(ServerPlayer player, String selector) {
		if (!"nearest_item".equals(EntityTargetSelector.normalize(selector))) {
			try {
				Entity entity = player.level().getEntity(UUID.fromString(selector));
				if (entity instanceof ItemEntity item && item.isAlive()) return item;
			} catch (IllegalArgumentException exception) {
				throw new AgentDomainException("ITEM_NOT_FOUND", "Invalid item target selector: " + selector);
			}
			throw new AgentDomainException("ITEM_NOT_FOUND", "The selected item entity is unavailable");
		}
		return player.level().getEntitiesOfClass(
						ItemEntity.class,
						player.getBoundingBox().inflate(64.0D),
						Entity::isAlive
				).stream()
				.filter(item -> ObservationVisibility.canSeeEntity(player, item))
				.min(Comparator.comparingDouble(player::distanceToSqr))
				.orElseThrow(() -> new AgentDomainException("ITEM_NOT_FOUND", "No item entity is nearby"));
	}

	private static void requireResult(AdvancedInteractionService.Result result) {
		if (!result.succeeded()) throw new AgentDomainException(result.reasonCode(), result.message());
	}

	private static BlockPos blockPosition(JsonObject arguments) {
		return BlockPos.containing(number(arguments, "x"), number(arguments, "y"), number(arguments, "z"));
	}

	private static String string(JsonObject object, String field) {
		return object.get(field).getAsString();
	}

	private void sendConversation(ServerActionRequest request, JsonObject arguments) {
		ConversationAudience audience = ConversationAudience.parse(nullableString(arguments, "audience"));
		String recipientId = nullableString(arguments, "recipientId");
		String message = string(arguments, "message");
		if (audience != ConversationAudience.PROXIMITY || !VoiceSubsystemRuntime.available(manager.server())) {
			if (audience == ConversationAudience.PROXIMITY) {
				VoiceSubsystemRuntime.reportAvailabilityFallback(manager.server(), request.agentId());
			}
			conversationRouter.deliverAgentMessage(request.agentId(), audience, recipientId, message);
			return;
		}
		conversationRouter.deliverAgentMessageToAgents(request.agentId(), audience, recipientId, message);
		long sequence = VoiceSubsystemRuntime.nextConversationSequence(manager.server(), request.agentId());
		VoiceRequest voiceRequest = new VoiceRequest(
				request.agentId(),
				message,
				"voice.auto.v1",
				(int) ServerAgentConversationRouter.DEFAULT_PROXIMITY_RANGE,
				sequence
		);
		VoiceSubsystemRuntime.speak(manager.server(), voiceRequest).whenComplete((receipt, failure) -> {
			boolean fallback = failure != null || receipt == null || receipt.requiresTextFallback();
			if (!fallback) return;
			manager.server().execute(() -> {
				try {
					conversationRouter.deliverAgentMessageToPlayers(
							request.agentId(), ConversationAudience.PROXIMITY, recipientId, message
					);
				} catch (RuntimeException ignored) {
					// The speaker may have despawned before an asynchronous provider failure.
				}
			});
		});
	}

	private void interactBlock(
			ServerPlayer player,
			BlockPos position,
			Direction face,
			InteractionHand hand,
			String expectedItemId,
			JsonObject arguments
	) {
		ServerLevel level = player.level();
		if (face == null) throw new AgentDomainException("INVALID_FACE", "Interaction face is invalid");
		if (!level.hasChunkAt(position)) throw new AgentDomainException("TARGET_NOT_LOADED", "Target chunk is not loaded");
		if (!player.isWithinBlockInteractionRange(position, 0.0D)) {
			throw new AgentDomainException("TARGET_TOO_FAR", "Block interaction target is out of reach");
		}
		if (!ObservationVisibility.canSeeBlock(level, player, position)) {
			throw new AgentDomainException("TARGET_NOT_VISIBLE", "Block interaction target is no longer visible");
		}
		if (!protection.mayInteractWithBlock(player, level, position)) {
			throw new AgentDomainException("PROTECTION_DENIED", "Block interaction was denied");
		}
		ItemStack held = player.getItemInHand(hand);
		requireHeldItem(held, expectedItemId);
		Vec3 hitLocation = blockInteractionHitLocation(position, face, arguments,
				level.getBlockState(position).getShape(level, position, CollisionContext.of(player)));
		Vec3 eye = player.getEyePosition();
		BlockHitResult hit = level.clip(new net.minecraft.world.level.ClipContext(eye,
				hitLocation.add(hitLocation.subtract(eye).normalize().scale(0.001D)),
				net.minecraft.world.level.ClipContext.Block.OUTLINE, net.minecraft.world.level.ClipContext.Fluid.NONE, player));
		if (hit.getType() != HitResult.Type.BLOCK || !hit.getBlockPos().equals(position) || hit.getDirection() != face) {
			throw new AgentDomainException("TARGET_OBSTRUCTED", "Requested block face is not reachable along the supplied hit ray");
		}
		player.lookAt(net.minecraft.commands.arguments.EntityAnchorArgument.Anchor.EYES, hitLocation);
		InteractionResult result = player.gameMode.useItemOn(
				player, level, held, hand, hit
		);
		if (!result.consumesAction()) {
			throw new AgentDomainException("INTERACTION_REJECTED", "Vanilla block interaction was not accepted");
		}
		player.swing(hand);
	}

	static Vec3 blockInteractionHitLocation(BlockPos position, Direction face, JsonObject arguments, VoxelShape outline) {
		if (arguments.has("hitX") && !arguments.get("hitX").isJsonNull()) {
			return Vec3.atLowerCornerOf(position).add(number(arguments, "hitX"), number(arguments, "hitY"), number(arguments, "hitZ"));
		}
		if (outline.isEmpty()) throw new AgentDomainException("TARGET_OBSTRUCTED", "The requested block has no interaction outline");
		var bounds = outline.bounds();
		return Vec3.atLowerCornerOf(position).add(bounds.getCenter()).add(
				face.getStepX() * bounds.getXsize() * 0.5D,
				face.getStepY() * bounds.getYsize() * 0.5D,
				face.getStepZ() * bounds.getZsize() * 0.5D);
	}

	private void interactEntity(
			ServerPlayer player,
			String targetId,
			InteractionHand hand,
			String expectedItemId,
			JsonObject arguments
	) {
		Entity target = resolveExactObservedTarget(player, targetId);
		if (!player.isWithinEntityInteractionRange(target, 0.0D)) {
			throw new AgentDomainException("TARGET_TOO_FAR", "Entity interaction target is out of reach");
		}
		if (!protection.mayInteractWithEntity(player, target)) {
			throw new AgentDomainException("PROTECTION_DENIED", "Entity interaction was denied");
		}
		requireHeldItem(player.getItemInHand(hand), expectedItemId);
		Vec3 requestedHit = arguments.has("hitX") && !arguments.get("hitX").isJsonNull() ? target.position().add(
				number(arguments, "hitX"), number(arguments, "hitY"), number(arguments, "hitZ")
		) : target.getBoundingBox().getCenter();
		if (!target.getBoundingBox().inflate(0.001D).contains(requestedHit)) {
			throw new AgentDomainException("INVALID_HIT", "Entity hit must be inside the observed entity bounds");
		}
		player.lookAt(net.minecraft.commands.arguments.EntityAnchorArgument.Anchor.EYES, requestedHit);
		HitResult traced = Tracer.rayTrace(player, 1.0F, player.entityInteractionRange(), false);
		if (!(traced instanceof EntityHitResult entityHit) || entityHit.getEntity() != target) {
			throw new AgentDomainException("TARGET_OBSTRUCTED", "Entity is not reachable along the supplied hit ray");
		}
		Vec3 relativeHit = traced.getLocation().subtract(target.position());
		InteractionResult result = target.interact(player, hand, relativeHit);
		if (!result.consumesAction()) result = player.interactOn(target, hand, relativeHit);
		if (!result.consumesAction()) {
			throw new AgentDomainException("INTERACTION_REJECTED", "Vanilla entity interaction was not accepted");
		}
		player.swing(hand);
	}

	private static void requireHeldItem(ItemStack held, String expectedItemId) {
		String actualItemId = held.isEmpty() ? "minecraft:air" : BuiltInRegistries.ITEM.getKey(held.getItem()).toString();
		if (!actualItemId.equals(expectedItemId)) {
			throw new AgentDomainException(
					"HELD_ITEM_MISMATCH",
					"Expected " + expectedItemId + " in the selected hand but observed " + actualItemId
			);
		}
	}

	static InteractionHand hand(JsonObject arguments) {
		return switch (string(arguments, "hand")) {
			case "main" -> InteractionHand.MAIN_HAND;
			case "off" -> InteractionHand.OFF_HAND;
			default -> throw new AgentDomainException("INVALID_HAND", "Hand must be main or off");
		};
	}

	private static void dismount(ServerPlayer player) {
		if (!player.isPassenger()) throw new AgentDomainException("NOT_RIDING", "Agent is not riding an entity");
		player.stopRiding();
		if (player.isPassenger()) throw new AgentDomainException("DISMOUNT_NOT_CONFIRMED", "Vanilla dismount was not observed");
	}

	private static void startFallFlying(ServerPlayer player) {
		if (!player.tryToStartFallFlying() || !player.isFallFlying()) {
			throw new AgentDomainException("ELYTRA_START_REJECTED", "Vanilla elytra flight preconditions were not met");
		}
	}

	private static String nullableString(JsonObject object, String field) {
		return object.has(field) && !object.get(field).isJsonNull() ? object.get(field).getAsString() : null;
	}

	private static int integer(JsonObject object, String field) {
		return object.get(field).getAsInt();
	}

	private static double number(JsonObject object, String field) {
		return object.get(field).getAsDouble();
	}

	private static boolean bool(JsonObject object, String field) {
		return object.get(field).getAsBoolean();
	}

	private static String safeMessage(Throwable throwable) {
		String message = throwable.getMessage();
		return message == null || message.isBlank() ? throwable.getClass().getSimpleName() : message;
	}

	static String failureReason(Throwable throwable) {
		return throwable instanceof AgentDomainException domain ? domain.code() : "ACTION_EXCEPTION";
	}

	private static final class ActiveAction {
		private enum Mode { IMMEDIATE, CONTROL, CONTROL_SEQUENCE, MOVE, USE, BREAK, PLACE, WAIT, CONTROLLER, TRANSACTION }

		private final ServerActionRequest request;
		private final ServerPlayer player;
		private final Mode mode;
		private final ElapsedTimeAccumulator elapsedTime;
		private final net.minecraft.resources.ResourceKey<net.minecraft.world.level.Level> startingDimension;
		private final long timeoutMs;
		private final Runnable immediate;
		private final Vec3 destination;
		private final double tolerance;
		private final boolean sprint;
		private final BlockPos block;
		private final ActionProgressTracker progress;
		private final ActionProgressEmissionPolicy progressEmission =
				new ActionProgressEmissionPolicy(PROGRESS_EMISSION_DELTA, PROGRESS_HEARTBEAT_MS);
		private ServerController controller;
		private ServerTransactionAdapter.ActiveTransaction transaction;
		private String initialBlockId;
		private String expectedBlockId;
		private DesiredBlockState desiredBlockState;
		private Direction placementRequestedFace;
		private String placementItemId;
		private int initialPlacementItemCount;
		private String resourceLeaseKey;
		private int placementAttempts;
		private boolean breakInputIssued;
		private boolean breakObservedInCarpet;
		private final long breakStartGameTime;
		private int breakTicks;
		private ServerActionObservation lastObservation;
		private boolean executionStarted;
		private boolean physicalAttempted;
		private double lastProgress;
		private InputLease inputLease;
		private AgentInputState controlState;
		private int controlDurationTicks;
		private int controlElapsedTicks;
		private ControlSequence controlSequence;
		private InteractionHand useHand;
		private int useDurationTicks;
		private int useElapsedTicks;
		private long useAcceptedBaseline;
		private boolean useStartObserved;

		private ActiveAction(
				ServerActionRequest request,
				ServerPlayer player,
				Mode mode,
				long timeoutMs,
				Runnable immediate,
				Vec3 destination,
				double tolerance,
				boolean sprint,
				BlockPos block
		) {
			this.request = request;
			this.player = player;
			this.mode = mode;
			this.startingDimension = player == null ? null : player.level().dimension();
			long startedAt = System.currentTimeMillis();
			this.elapsedTime = new ElapsedTimeAccumulator(startedAt);
			this.timeoutMs = Math.max(1L, timeoutMs);
			this.immediate = immediate;
			this.destination = destination;
			this.tolerance = tolerance;
			this.sprint = sprint;
			this.block = block;
			this.breakStartGameTime = player == null ? -1L : player.level().getGameTime();
			this.progress = mode == Mode.MOVE
					? new ActionProgressTracker(player.position().distanceTo(destination), startedAt, MOVEMENT_STALL_TIMEOUT_MS)
					: null;
		}

		static ActiveAction immediate(ServerActionRequest request, ServerPlayer player, Runnable operation) {
			return new ActiveAction(request, player, Mode.IMMEDIATE, DEFAULT_TIMEOUT_MS, operation, null, 0.0D, false, null);
		}

		static ActiveAction control(
				ServerActionRequest request,
				ServerPlayer player,
				AgentInputState state,
				int durationTicks
		) {
			ActiveAction action = new ActiveAction(
					request, player, Mode.CONTROL, DEFAULT_TIMEOUT_MS, null, null, 0.0D, false, null
			);
			action.controlState = Objects.requireNonNull(state, "control state must not be null");
			if (durationTicks < 1 || durationTicks > 200) {
				throw new IllegalArgumentException("control duration must be 1..200 ticks");
			}
			action.controlDurationTicks = durationTicks;
			return action;
		}

		static ActiveAction move(
				ServerActionRequest request,
				ServerPlayer player,
				Vec3 destination,
				double tolerance,
				boolean sprint,
				long timeoutMs
		) {
			return new ActiveAction(request, player, Mode.MOVE, timeoutMs, null, destination, tolerance, sprint, null);
		}

		static ActiveAction controlSequence(ServerActionRequest request, ServerPlayer player, ControlSequence sequence) {
			ActiveAction action = new ActiveAction(request, player, Mode.CONTROL_SEQUENCE,
					DEFAULT_TIMEOUT_MS, null, null, 0.0D, false, null);
			action.controlSequence = Objects.requireNonNull(sequence, "sequence must not be null");
			return action;
		}

		static ActiveAction use(ServerActionRequest request, ServerPlayer player, long durationMs,
				InteractionHand hand, String expectedItemId) {
			if (expectedItemId != null) requireHeldItem(player.getItemInHand(hand), expectedItemId);
			ActiveAction action = new ActiveAction(request, player, Mode.USE, durationMs, null, null, 0.0D, false, null);
			action.useHand = hand;
			action.useDurationTicks = Math.toIntExact(Math.max(1L, (durationMs + 49L) / 50L));
			return action;
		}

		static ActiveAction breakBlock(
				ServerActionRequest request,
				ServerPlayer player,
				BlockPos block,
				long timeoutMs,
				String initialBlockId,
				String expectedBlockId
		) {
			ActiveAction action = new ActiveAction(request, player, Mode.BREAK, timeoutMs, null, null, 0.0D, false, block);
			action.initialBlockId = Objects.requireNonNull(initialBlockId, "initialBlockId must not be null");
			action.expectedBlockId = Objects.requireNonNull(expectedBlockId, "expectedBlockId must not be null");
			return action;
		}

		static ActiveAction placeBlock(
				ServerActionRequest request,
				ServerPlayer player,
				Runnable operation,
				BlockPos block,
				String initialBlockId,
				String expectedBlockId,
				DesiredBlockState desiredBlockState,
				Direction placementRequestedFace,
				String itemId,
				int initialItemCount
		) {
			ActiveAction action = new ActiveAction(
					request,
					player,
					Mode.PLACE,
					PLACE_TIMEOUT_MS,
					operation,
					null,
					0.0D,
					false,
					block
			);
			action.initialBlockId = Objects.requireNonNull(initialBlockId, "initialBlockId must not be null");
			action.expectedBlockId = Objects.requireNonNull(expectedBlockId, "expectedBlockId must not be null");
			action.desiredBlockState = Objects.requireNonNull(desiredBlockState, "desiredBlockState must not be null");
			action.placementRequestedFace = placementRequestedFace;
			action.placementItemId = Objects.requireNonNull(itemId, "itemId must not be null");
			action.initialPlacementItemCount = Math.max(0, initialItemCount);
			return action;
		}

		static ActiveAction waitFor(ServerActionRequest request, ServerPlayer player, long durationMs) {
			return new ActiveAction(request, player, Mode.WAIT, durationMs, null, null, 0.0D, false, null);
		}

		static ActiveAction controller(
				ServerActionRequest request,
				ServerPlayer player,
				ServerController controller
		) {
			ActiveAction action = new ActiveAction(
					request,
					player,
					Mode.CONTROLLER,
					DEFAULT_TIMEOUT_MS,
					null,
					null,
					0.0D,
					false,
					null
			);
			action.controller = Objects.requireNonNull(controller, "controller must not be null");
			return action;
		}

		static ActiveAction transaction(
				ServerActionRequest request,
				ServerPlayer player,
				ServerTransactionAdapter.ActiveTransaction transaction
		) {
			ActiveAction action = new ActiveAction(
					request,
					player,
					Mode.TRANSACTION,
					DEFAULT_TIMEOUT_MS,
					null,
					null,
					0.0D,
					false,
					null
			);
			action.transaction = Objects.requireNonNull(transaction, "transaction must not be null");
			return action;
		}

		ServerActionRequest request() {
			return request;
		}

		ServerActionResult tick(long now) {
			if (startingDimension != null && !startingDimension.equals(player.level().dimension())) {
				return result(ServerActionState.CANCELLED, "DIMENSION_TRANSITION",
						"Player changed dimension from " + startingDimension.identifier() + " to " + player.level().dimension().identifier()
								+ "; this action stopped and its original outcome is unconfirmed", now);
			}
			if (!player.isAlive()) return result(ServerActionState.FAILED, "AGENT_DEAD", "Agent player died", now);
			long elapsed = elapsedTime.advance(now);
			if (!executionStarted) {
				executionStarted = true;
				switch (mode) {
					case IMMEDIATE -> {
						physicalAttempted = true;
						immediate.run();
					}
					case CONTROL -> {
						physicalAttempted = true;
						applyControlInput();
						return null;
					}
					case CONTROL_SEQUENCE -> { }
					case MOVE -> {
						physicalAttempted = true;
						applyLookingInput(InputOwner.NAVIGATION, 100, destination, 1.0F, true, false, false);
					}
					case USE -> {
						physicalAttempted = true;
						useAcceptedBaseline = AgentInputRuntime.controller(player).acceptedUses(request.agentId());
						applyUseInput();
						return null;
					}
					case BREAK -> {
						validateBreakTarget(player, block, expectedBlockId);
						physicalAttempted = true;
						applyLookingInput(InputOwner.INTERACTION, 300, Vec3.atCenterOf(block), 0.0F, false, true, false);
						breakInputIssued = true;
					}
					case PLACE -> {
					}
					case WAIT -> {
					}
					case CONTROLLER -> {
					}
					case TRANSACTION -> {
					}
				}
				if (mode == Mode.IMMEDIATE) {
					if (request.type() == ActionType.ATTACK) return result(ServerActionState.SUCCEEDED,
							"ATTACK_APPLIED", "Vanilla attack applied to the requested target; damage or death is not confirmed", now);
					return result(ServerActionState.SUCCEEDED, "ACTION_COMPLETED", "Action completed", now);
				}
			}

			if (mode == Mode.CONTROL_SEQUENCE) return tickControlSequence(now);
			if (mode == Mode.USE) {
				useElapsedTicks++;
				useStartObserved |= player.isUsingItem() && player.getUsedItemHand() == useHand;
				if (useElapsedTicks >= useDurationTicks) {
					long accepted = Math.max(0L, AgentInputRuntime.controller(player).acceptedUses(request.agentId()) - useAcceptedBaseline);
					releaseInput();
					return accepted > 0
							? result(ServerActionState.SUCCEEDED, "USE_INPUT_CONFIRMED",
									"Held use for " + useElapsedTicks + " server ticks; accepted interactions=" + accepted
											+ ", using state observed=" + useStartObserved + ", input released=true; downstream effects are unconfirmed", now)
							: result(ServerActionState.FAILED, "USE_NOT_ACCEPTED", "Held use input was released without an accepted vanilla interaction", now);
				}
				applyUseInput();
				lastProgress = (double) useElapsedTicks / useDurationTicks;
				return null;
			}
			if (mode == Mode.CONTROL) {
				physicalAttempted = true;
				applyControlInput();
				controlElapsedTicks++;
				lastProgress = (double) controlElapsedTicks / controlDurationTicks;
				if (controlElapsedTicks >= controlDurationTicks) {
					return result(ServerActionState.SUCCEEDED, "CONTROL_SEGMENT_COMPLETED", "Control segment completed", now);
				}
				return null;
			} else if (mode == Mode.MOVE) {
				double distance = player.position().distanceTo(destination);
				lastProgress = progress.progress(distance);
				physicalAttempted = true;
				applyLookingInput(InputOwner.NAVIGATION, 100, destination, 1.0F, true, false, false);
				if (distance <= tolerance) {
					return result(ServerActionState.SUCCEEDED, "DESTINATION_REACHED", "Destination reached", now);
				}
				if (progress.stalled(distance, now)) {
					return result(
							ServerActionState.FAILED,
							"PATH_BLOCKED",
							"Agent made no progress for 4 seconds; replanning around the obstacle",
							now
					);
				}
			} else if (mode == Mode.BREAK) {
				physicalAttempted = true;
				// Renew the deadman lease without restarting Carpet's continuous attack.
				// Slow blocks such as logs take longer than the 40-tick lease window.
				applyLookingInput(InputOwner.INTERACTION, 300, Vec3.atCenterOf(block), 0.0F, false, true, false);
				breakTicks++;
				BlockState currentState = player.level().getBlockState(block);
				String currentBlockId = blockId(currentState);
				HitResult hit = breakRayTarget(player);
				boolean exactRayTarget = hit instanceof BlockHitResult blockHit && block.equals(blockHit.getBlockPos());
				if (!currentState.isAir() && !expectedBlockId.equals(currentBlockId)) {
					lastObservation = breakObservation(now, hit, currentBlockId, 0.0D, false);
					return result(ServerActionState.FAILED, "TARGET_CHANGED", "The observed block changed before it was broken", now);
				}
				if (currentState.isAir()) {
					ServerPlayerGameModeBreakAccessor gameMode = (ServerPlayerGameModeBreakAccessor) (Object) player.gameMode;
					boolean ownedTransition = breakInputIssued
							&& block.equals(gameMode.arenaagents$getLastDestroyedBlock())
							&& gameMode.arenaagents$getLastDestroyedGameTime() >= breakStartGameTime;
					lastObservation = breakObservation(now, hit, currentBlockId, 1.0D, ownedTransition);
					return ownedTransition
							? result(ServerActionState.SUCCEEDED, "BLOCK_BROKEN", "Block broken", now)
							: result(ServerActionState.FAILED, "TARGET_CHANGED", "The target disappeared before this action started mining", now);
				}
				if (!exactRayTarget) {
					lastObservation = breakObservation(now, hit, currentBlockId, lastProgress, false);
					return result(ServerActionState.FAILED, "TARGET_NOT_VISIBLE", "The target is no longer under the crosshair", now);
				}
				EntityPlayerActionPack actionPack = OfflineAgentPlayers.actions(player);
				EntityPlayerActionPackAccessor accessor = (EntityPlayerActionPackAccessor) (Object) actionPack;
				BlockPos carpetTarget = accessor.arenaagents$getCurrentBlock();
				if (block.equals(carpetTarget)) {
					breakObservedInCarpet = true;
					lastProgress = Math.max(0.0D, Math.min(1.0D, accessor.arenaagents$getCurrentBlockDamage()));
				} else {
					lastProgress = 0.0D;
					if (breakTicks > 1 && carpetTarget != null) {
						lastObservation = breakObservation(now, hit, currentBlockId, 0.0D, false);
						return result(ServerActionState.FAILED, "TARGET_NOT_STARTED", "Carpet is mining a different block", now);
					}
				}
				lastObservation = breakObservation(now, hit, currentBlockId, lastProgress, breakObservedInCarpet);
			} else if (mode == Mode.PLACE) {
				boolean placementOwned = player.isCreative()
						? placementAttempts > 0
						: inventoryItemCount(player, placementItemId) == initialPlacementItemCount - 1;
				BlockPlacementPostcondition.Decision decision = BlockPlacementPostcondition.evaluate(
						initialBlockId,
						player.level().getBlockState(block),
						desiredBlockState,
										placementOwned,
										elapsed >= timeoutMs,
										placementAttempts
								);
				if (decision == BlockPlacementPostcondition.Decision.ALREADY_SATISFIED) {
					return result(ServerActionState.SUCCEEDED, "TARGET_ALREADY_SATISFIED", "Requested block was already present", now);
				}
				if (decision == BlockPlacementPostcondition.Decision.SUCCEEDED) {
					return result(ServerActionState.SUCCEEDED, "BLOCK_PLACED", "Block placement confirmed", now);
				}
				if (decision == BlockPlacementPostcondition.Decision.CONFLICT) {
					return result(ServerActionState.FAILED, "PLACEMENT_CONFLICT", placementFailureMessage(
							"A different block or state occupied the target"), now);
				}
				if (decision == BlockPlacementPostcondition.Decision.TIMED_OUT
						|| decision == BlockPlacementPostcondition.Decision.EXHAUSTED) {
					return result(ServerActionState.TIMED_OUT, "PLACEMENT_NOT_CONFIRMED", placementFailureMessage(
							"Block placement was not confirmed"), now);
				}
				if (BlockPlacementAttemptPolicy.shouldAttempt(elapsed, placementAttempts)) {
					physicalAttempted = true;
					immediate.run();
					placementAttempts += 1;
				}
			} else if (mode == Mode.WAIT && elapsed >= timeoutMs) {
				return result(ServerActionState.SUCCEEDED, "ACTION_COMPLETED", "Action completed", now);
			}
			if (mode == Mode.CONTROLLER) {
				physicalAttempted = true;
				ServerController.TickResult controllerResult = requireControllerResult(
						controller.tick(player, now), lastProgress, controller.getClass().getSimpleName());
				lastProgress = controllerResult.progress();
				if (controller instanceof ServerNavigationController navigation) {
					lastObservation = navigationObservation(navigation.authoritativeState(player, now), now);
				}
				return switch (controllerResult.state()) {
					case RUNNING -> null;
					case SUCCEEDED -> result(
							ServerActionState.SUCCEEDED,
							controllerResult.reasonCode(),
							controllerResult.message(),
							now
					);
					case FAILED -> result(
							ServerActionState.FAILED,
							controllerResult.reasonCode(),
							controllerResult.message(),
							now
					);
				};
			}
			if (mode == Mode.TRANSACTION) {
				physicalAttempted = true;
				ServerTransactionAdapter.TickResult transactionResult = transaction.tick(now);
				lastProgress = timedProgress(elapsed);
				return switch (transactionResult.state()) {
					case RUNNING -> null;
					case SUCCEEDED -> result(ServerActionState.SUCCEEDED, transactionResult.reasonCode(), transactionResult.message(), now);
					case FAILED -> result(ServerActionState.FAILED, transactionResult.reasonCode(), transactionResult.message(), now);
					case CANCELLED -> result(ServerActionState.CANCELLED, transactionResult.reasonCode(), transactionResult.message(), now);
					case TIMED_OUT -> result(ServerActionState.TIMED_OUT, transactionResult.reasonCode(), transactionResult.message(), now);
				};
			}

			if (elapsed >= timeoutMs) {
				return result(ServerActionState.TIMED_OUT, "ACTION_TIMED_OUT", "Action timed out", now);
			}
			if (mode != Mode.MOVE && mode != Mode.CONTROLLER && mode != Mode.TRANSACTION && mode != Mode.BREAK) {
				lastProgress = timedProgress(elapsed);
			}
			return null;
		}

		ServerActionProgress progress(long now) {
			double bounded = Math.max(0.0D, Math.min(lastObservation == null ? 0.99D : 1.0D, lastProgress));
			if (!progressEmission.shouldEmit(bounded, now)) return null;
			return new ServerActionProgress(
					request.agentId(),
					request.goalRevision(),
					request.actionId(),
					request.type(),
					request.traceId(),
					bounded,
					elapsedTime.advance(now),
					now,
					lastObservation
			);
		}

		private double timedProgress(long elapsed) {
			return Math.max(0.0D, Math.min(0.99D, (double) elapsed / timeoutMs));
		}

		private ServerActionObservation breakObservation(
				long now,
				HitResult hit,
				String currentBlockId,
				double progressValue,
				boolean verified
		) {
			Vec3 velocity = player.getDeltaMovement();
			Vec3 eye = player.getEyePosition();
			double maxReach = player.blockInteractionRange();
			double distance = Math.sqrt(player.distanceToSqr(Vec3.atCenterOf(block)));
			ServerActionObservation.RayTarget rayTarget;
			if (hit instanceof BlockHitResult blockHit) {
				BlockPos hitPosition = blockHit.getBlockPos();
				rayTarget = new ServerActionObservation.RayTarget(
						"block",
						new ServerActionObservation.Position(hitPosition.getX(), hitPosition.getY(), hitPosition.getZ()),
						blockId(player.level().getBlockState(hitPosition)),
						blockHit.getDirection().getSerializedName(),
						eye.distanceTo(hit.getLocation())
				);
			} else {
				rayTarget = new ServerActionObservation.RayTarget(
						hit.getType().name().toLowerCase(java.util.Locale.ROOT),
						null,
						null,
						null,
						eye.distanceTo(hit.getLocation())
				);
			}
			boolean worldChanged = !initialBlockId.equals(currentBlockId);
			String progressBasis = worldChanged ? "world_mutation" : "block_damage";
			return new ServerActionObservation(
					Math.max(0L, player.level().getGameTime()),
					now,
					new ServerActionObservation.Position(player.getX(), player.getY(), player.getZ()),
					new ServerActionObservation.Position(velocity.x(), velocity.y(), velocity.z()),
					player.getYRot(),
					player.getXRot(),
					new ServerActionObservation.Collision(player.horizontalCollision, player.verticalCollision, player.isInWall()),
					rayTarget,
					new ServerActionObservation.Reach(distance, maxReach, player.isWithinBlockInteractionRange(block, 1.0D)),
					new ServerActionObservation.Target(
							"block",
							new ServerActionObservation.Position(block.getX(), block.getY(), block.getZ()),
							expectedBlockId,
							currentBlockId,
							initialBlockId,
							currentBlockId,
							worldChanged,
							Math.max(0.0D, distance - maxReach),
							null,
							null
					),
					new ServerActionObservation.Progress(
							Math.max(0.0D, Math.min(1.0D, progressValue)), progressBasis, verified
					)
			);
		}

		private ServerActionObservation navigationObservation(
				ServerNavigationController.AuthoritativeState state,
				long now
		) {
			Vec3 velocity = player.getDeltaMovement();
			HitResult hit = breakRayTarget(player);
			Vec3 endpoint = state.resolvedEndpoint() == null ? state.requestedDestination() : state.resolvedEndpoint();
			return new ServerActionObservation(
					Math.max(0L, player.level().getGameTime()),
					now,
					new ServerActionObservation.Position(state.position().x(), state.position().y(), state.position().z()),
					new ServerActionObservation.Position(velocity.x(), velocity.y(), velocity.z()),
					player.getYRot(),
					player.getXRot(),
					new ServerActionObservation.Collision(
							player.horizontalCollision, player.verticalCollision, state.inWall()),
					rayTargetObservation(hit),
					null,
					new ServerActionObservation.Target(
							"position",
							endpoint == null ? null : new ServerActionObservation.Position(endpoint.x(), endpoint.y(), endpoint.z()),
							null,
							null,
							null,
							null,
							null,
							state.distanceToEndpoint(),
							state.tolerance(),
							state.endpointStandable()
					),
					new ServerActionObservation.Progress(state.progress(), "world_position", true)
			);
		}

		private ServerActionObservation.RayTarget rayTargetObservation(HitResult hit) {
			Vec3 eye = player.getEyePosition();
			if (hit instanceof BlockHitResult blockHit) {
				BlockPos hitPosition = blockHit.getBlockPos();
				return new ServerActionObservation.RayTarget(
						"block",
						new ServerActionObservation.Position(hitPosition.getX(), hitPosition.getY(), hitPosition.getZ()),
						blockId(player.level().getBlockState(hitPosition)),
						blockHit.getDirection().getSerializedName(),
						eye.distanceTo(hit.getLocation())
				);
			}
			return new ServerActionObservation.RayTarget(
					hit.getType().name().toLowerCase(java.util.Locale.ROOT),
					null,
					null,
					null,
					eye.distanceTo(hit.getLocation())
			);
		}

		private String placementFailureMessage(String prefix) {
			var currentState = player.level().getBlockState(block);
			String actual = blockId(currentState) + DesiredBlockState.stableProperties(currentState);
			Direction face = placementFace(player, block, placementRequestedFace);
			BlockPos support = face == null ? null : block.relative(face.getOpposite());
			double distance = Math.sqrt(player.distanceToSqr(Vec3.atCenterOf(block)));
			return prefix
					+ "; expected=" + desiredBlockState.blockId() + desiredBlockState.properties()
					+ ", actual=" + actual
					+ ", support=" + (support == null ? "none" : support + " face=" + face)
					+ ", distance=" + String.format(java.util.Locale.ROOT, "%.3f", distance)
					+ ", itemCount=" + inventoryItemCount(player, placementItemId);
		}

		void cancel(String reason) {
			ServerTransactionAdapter.runBestEffort(
					() -> { if (transaction != null) transaction.cancel(reason); },
					() -> { if (controller != null) controller.cancel(player); },
					this::releaseInput
			);
		}

		void cleanup() {
			ServerTransactionAdapter.runBestEffort(
					() -> { if (transaction != null) transaction.cleanup(); },
					() -> { if (controller != null) controller.cancel(player); },
					this::releaseInput
			);
		}

		private void applyLookingInput(
				InputOwner owner,
				int priority,
				Vec3 target,
				float forward,
				boolean jump,
				boolean attack,
				boolean use
		) {
			LeasedServerInputController input = AgentInputRuntime.controller(player);
			if (inputLease == null) inputLease = input.acquire(request.agentId(), owner, priority);
			input.apply(inputLease, AgentInputStates.lookingAt(
					player, target, forward, 0.0F, jump, false,
					sprint && player.getFoodData().getFoodLevel() > 6,
					attack, use, InteractionHand.MAIN_HAND
			));
		}

		private void applyUseInput() {
			LeasedServerInputController input = AgentInputRuntime.controller(player);
			if (inputLease == null) inputLease = input.acquire(request.agentId(), InputOwner.INTERACTION, 300);
			input.apply(inputLease, new AgentInputState(0.0F, 0.0F, false, player.isShiftKeyDown(),
					player.isSprinting(), false, true, player.getYRot(), player.getXRot(),
					player.getInventory().getSelectedSlot(), useHand));
		}

		private ServerActionResult tickControlSequence(long now) {
			ControlSequence.Step step = controlSequence.next(new ControlSequence.Facts(player.getHealth(),
					player.getFoodData().getFoodLevel(), player.getAirSupply(), player.isOnFire(), player.isInWater(),
					player.onGround(), player.horizontalCollision, player.hurtTime > 0, player.isUsingItem()));
			lastProgress = (double) step.elapsedTicks() / step.maxTicks();
			if (step.status() == ControlSequence.Status.RUNNING) {
				physicalAttempted = true;
				controlState = step.input();
				applyControlInput();
				return null;
			}
			String detail = "Model-authored control sequence stopped at frame " + step.frameIndex()
					+ " after " + step.elapsedTicks() + " server ticks; no task outcome is implied";
			return switch (step.status()) {
				case COMPLETED -> result(ServerActionState.SUCCEEDED, "CONTROL_SEQUENCE_COMPLETED", detail, now);
				case BRANCH_STOPPED -> result(ServerActionState.SUCCEEDED, "CONTROL_SEQUENCE_STOPPED", detail, now);
				case BUDGET_EXHAUSTED -> result(ServerActionState.TIMED_OUT, "CONTROL_SEQUENCE_BUDGET_EXHAUSTED", detail, now);
				case RUNNING -> throw new IllegalStateException("Running input has no terminal result");
			};
		}

		boolean isControl() {
			return mode == Mode.CONTROL || mode == Mode.CONTROL_SEQUENCE;
		}

		void neutralizeDisconnectedControl() {
			ServerTransactionAdapter.runBestEffort(
					this::releaseInput,
					() -> AgentInputRuntime.clear(player),
					() -> OfflineAgentPlayers.actions(player).stopAll(),
					player::stopUsingItem
			);
		}

		private void applyControlInput() {
			LeasedServerInputController input = AgentInputRuntime.controller(player);
			if (inputLease == null) inputLease = input.acquire(request.agentId(), InputOwner.DIRECT_CONTROL, 250);
			input.apply(inputLease, controlState);
		}

		private void releaseInput() {
			if (inputLease == null) return;
			try {
				AgentInputRuntime.controller(player).release(inputLease);
			} catch (LeasedServerInputController.StaleInputLeaseException ignored) {
				// A lifecycle clear may already have invalidated every lease.
			}
			inputLease = null;
		}

		ServerActionResult result(ServerActionState state, String reasonCode, String message, long now) {
			return new ServerActionResult(
					request.agentId(),
					request.goalRevision(),
					request.actionId(),
					request.type(),
					request.traceId(),
					state,
					reasonCode,
					message,
					elapsedTime.advance(now),
				now,
				executionStarted,
				physicalAttempted,
				lastObservation
			);
		}
	}

	static ServerController.TickResult requireControllerResult(
			ServerController.TickResult result,
			double lastProgress,
			String controllerName
	) {
		if (result != null) return result;
		String readable = controllerName == null || controllerName.isBlank()
				? "Controller" : controllerName.replace("Server", "").replace("Controller", " controller").trim();
		if (!readable.toLowerCase(java.util.Locale.ROOT).endsWith("controller")) readable += " controller";
		return ServerController.TickResult.failed(
				"CONTROLLER_NO_RESULT",
				readable + " returned no result",
				Math.max(0.0D, Math.min(1.0D, lastProgress))
		);
	}
}
