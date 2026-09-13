package dev.agaminggod.arenaagents.server.runtime;

import com.google.gson.JsonObject;
import dev.agaminggod.arenaagents.agent.AgentDomainException;
import dev.agaminggod.arenaagents.agent.AgentGameMode;
import dev.agaminggod.arenaagents.agent.AgentRecord;
import dev.agaminggod.arenaagents.server.AgentSavedData;
import dev.agaminggod.arenaagents.server.CodexAgentManager;
import dev.agaminggod.arenaagents.server.runtime.controller.ServerController;
import dev.agaminggod.arenaagents.server.runtime.controller.ServerItemPickupController;
import dev.agaminggod.arenaagents.server.runtime.controller.ServerNavigationController;
import dev.agaminggod.arenaagents.server.runtime.input.AgentInputState;
import dev.agaminggod.arenaagents.server.runtime.input.InputLease;
import dev.agaminggod.arenaagents.server.runtime.input.InputOwner;
import dev.agaminggod.arenaagents.server.runtime.input.InputStateSink;
import dev.agaminggod.arenaagents.server.runtime.input.LeasedServerInputController;

import dev.agaminggod.arenaagents.agent.AgentId;
import dev.agaminggod.arenaagents.protocol.ActionType;
import dev.agaminggod.arenaagents.server.perception.ServerObservationCollector;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import dev.agaminggod.arenaagents.server.runtime.transaction.ServerTransactionAdapter;
import java.util.concurrent.atomic.AtomicInteger;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.level.EmptyBlockGetter;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;

public final class ServerActionExecutorVerification {
	private ServerActionExecutorVerification() {
	}

	public static void main(String[] args) {
		net.minecraft.server.Bootstrap.bootStrap();
		System.out.println("Server action executor verification passed: " + verify() + " assertions");
	}

	public static int verify() {
		ServerTransactionAdapter.TerminalGate gate = new ServerTransactionAdapter.TerminalGate();
		ServerTransactionAdapter.TickResult success = ServerTransactionAdapter.TickResult.succeeded(
				"TRANSFER_CONFIRMED", "Transfer confirmed");
		ServerTransactionAdapter.TickResult conflict = ServerTransactionAdapter.TickResult.failed(
				"TRANSACTION_CONFLICT", "Unexpected slot changed");
		assertEquals(success, gate.finish(success), "first terminal result is retained");
		assertEquals(success, gate.finish(conflict), "later terminal result cannot replace the first");

		AtomicInteger cleanupCalls = new AtomicInteger();
		gate.cleanupOnce(cleanupCalls::incrementAndGet);
		gate.cleanupOnce(cleanupCalls::incrementAndGet);
		assertEquals(1, cleanupCalls.get(), "menu, carried stack, use state, and lease cleanup runs once");
		ServerTransactionAdapter.TerminalGate retryableCleanup = new ServerTransactionAdapter.TerminalGate();
		AtomicInteger cleanupAttempts = new AtomicInteger();
		assertThrows(IllegalStateException.class, () -> retryableCleanup.cleanupOnce(() -> {
			cleanupAttempts.incrementAndGet();
			throw new IllegalStateException("first cleanup failed");
		}), "failed cleanup remains retryable");
		assertFalse(retryableCleanup.cleanupComplete(), "failed cleanup is not reported as clean");
		retryableCleanup.cleanupOnce(cleanupAttempts::incrementAndGet);
		assertEquals(2, cleanupAttempts.get(), "cleanup retries after a failed attempt");
		assertTrue(retryableCleanup.cleanupComplete(), "successful retry marks cleanup complete");

		AtomicInteger bestEffortSteps = new AtomicInteger();
		assertThrows(IllegalStateException.class, () -> ServerTransactionAdapter.runBestEffort(
				() -> { bestEffortSteps.incrementAndGet(); throw new IllegalStateException("menu close failed"); },
				bestEffortSteps::incrementAndGet,
				bestEffortSteps::incrementAndGet
		), "best-effort cleanup reports its first failure");
		assertEquals(3, bestEffortSteps.get(), "controller stop and player stop run after an earlier cleanup failure");

		ServerActionExecutor.CleanupRetry<String> retainedCleanup = new ServerActionExecutor.CleanupRetry<>();
		retainedCleanup.retain("terminal-result");
		assertThrows(IllegalStateException.class, () -> retainedCleanup.complete(() -> {
			throw new IllegalStateException("teardown failed");
		}), "failed executor teardown keeps its terminal result pending");
		assertTrue(retainedCleanup.hasPending(), "failed executor teardown retains the cleanup handle");
		retainedCleanup.retain("later-result");
		assertEquals("terminal-result", retainedCleanup.complete(() -> { }),
				"executor finish publishes only the first retained result after cleanup succeeds");
		assertFalse(retainedCleanup.hasPending(), "successful retry releases the cleanup handle");

		ServerActionExecutor.CleanupRetry<String> permanentCleanup = new ServerActionExecutor.CleanupRetry<>();
		permanentCleanup.retain("terminal-result");
		for (int attempt = 1; attempt <= ServerActionExecutor.MAX_CLEANUP_ATTEMPTS; attempt++) {
			assertThrows(IllegalStateException.class, () -> permanentCleanup.complete(() -> {
				throw new IllegalStateException("input sink unavailable");
			}), "permanent input-sink cleanup failure remains contained");
			assertEquals(attempt, permanentCleanup.failureCount(), "cleanup failure attempts are counted deterministically");
		}
		assertTrue(permanentCleanup.exhausted(ServerActionExecutor.MAX_CLEANUP_ATTEMPTS),
				"permanent cleanup failure reaches the quarantine boundary");
		assertTrue(permanentCleanup.hasPending(), "quarantine preserves the original terminal result for diagnosis");

		ServerActionExecutor.TerminalPublication<String> publication =
				new ServerActionExecutor.TerminalPublication<>("terminal-result");
		AtomicInteger publicationPreparations = new AtomicInteger();
		AtomicInteger publicationAttempts = new AtomicInteger();
		assertThrows(IllegalStateException.class, () -> publication.publish(
				publicationPreparations::incrementAndGet,
				result -> {
					publicationAttempts.incrementAndGet();
					throw new IllegalStateException("first publication failed");
				}
		), "terminal sink failure remains retryable");
		assertTrue(publication.hasPending(), "terminal result remains pending after sink failure");
		assertEquals(1, publicationPreparations.get(), "terminal result is prepared once before delivery");
		assertTrue(publication.publish(
				publicationPreparations::incrementAndGet,
				result -> publicationAttempts.incrementAndGet()
		), "terminal result publishes after the sink recovers");
		assertEquals(1, publicationPreparations.get(), "terminal retry does not repeat local completion effects");
		assertEquals(2, publicationAttempts.get(), "terminal retry reaches the sink again");
		assertFalse(publication.hasPending(), "successful terminal publication closes the retry state");
		assertTrue(publication.publish(
				publicationPreparations::incrementAndGet,
				result -> publicationAttempts.incrementAndGet()
		), "completed terminal publication is idempotent");
		assertEquals(2, publicationAttempts.get(), "completed terminal publication does not call the sink twice");

		ElapsedTimeAccumulator elapsed = new ElapsedTimeAccumulator(1_000L);
		assertEquals(100L, elapsed.advance(1_100L), "elapsed time advances with the wall clock");
		assertEquals(100L, elapsed.advance(900L), "clock rollback does not subtract elapsed duration");
		assertEquals(150L, elapsed.advance(950L), "elapsed time resumes from the corrected clock value");
		ElapsedTimeAccumulator overflowElapsed = new ElapsedTimeAccumulator(Long.MIN_VALUE);
		assertEquals(Long.MAX_VALUE, overflowElapsed.advance(Long.MAX_VALUE),
				"timestamp subtraction overflow saturates instead of becoming zero");

		ResourceLeaseManager leases = new ResourceLeaseManager();
		AgentId owner = AgentId.random();
		assertTrue(leases.acquire("container:minecraft:overworld:42", owner, 1_000L, 5_000L),
				"transaction lease is acquired");
		assertTrue(leases.isHeldBy("container:minecraft:overworld:42", owner, 1_001L),
				"active transaction owns its lease");
		leases.releaseAll(owner);
		assertFalse(leases.isHeldBy("container:minecraft:overworld:42", owner, 1_002L),
				"cancellation releases every transaction lease");

		for (String reason : new String[] {"ACTION_CANCELLED", "ACTION_TIMED_OUT", "THREAT_DETECTED"}) {
			ServerTransactionAdapter.TerminalGate interrupted = new ServerTransactionAdapter.TerminalGate();
			AtomicInteger interruptedCleanup = new AtomicInteger();
			interrupted.finish(ServerTransactionAdapter.TickResult.failed(reason, reason));
			interrupted.cleanupOnce(interruptedCleanup::incrementAndGet);
			interrupted.cleanupOnce(interruptedCleanup::incrementAndGet);
			assertEquals(1, interruptedCleanup.get(), reason + " cleanup runs once");
		}
		assertEquals(
				java.util.List.of("transfer_container"),
				ServerObservationCollector.transactionCapabilities("minecraft:chest"),
				"chest observation exposes only the supported transfer adapter"
		);
		assertEquals(
				java.util.List.of("furnace_transaction"),
				ServerObservationCollector.transactionCapabilities("minecraft:blast_furnace"),
				"furnace variants expose only the supported furnace adapter"
		);
		assertEquals(
				java.util.List.of(),
				ServerObservationCollector.transactionCapabilities("modded:machine"),
				"modded menu capabilities fail closed"
		);
		AgentId progressAgent = AgentId.random();
		ActionProvenance provenance = new ActionProvenance(
				"codex", "gpt-5.6-sol", "high", "priority", "program-7-1", 1L, "step-80-126", 4L
		);
		assertEquals("program-7-1", provenance.programId(), "provenance retains program identity");
		ActionProvenance watcherProvenance = new ActionProvenance(
				"codex", "gpt-5.6-sol", "high", "priority", "program-7-1", 1L, "step-80-126", 4L, "trace-watcher-1", "watcher-0"
		);
		assertEquals("watcher-0", watcherProvenance.watcherId(), "watcher provenance retains its authorizing identity");
		assertThrows(IllegalArgumentException.class, () -> new ActionProvenance(
				"codex", "gpt-5.6-sol", "high", "priority", "program-7-1", 1L, "step-80-126", 4L, null, "watcher-0"
		), "watcher provenance requires a trace identity");
		assertThrows(IllegalArgumentException.class, () -> new ActionProvenance(
				"codex", "gpt-5.6-sol", "high", "priority", "program-7-1", 1L, "step-80-126", 4L, "trace-watcher-1", "x".repeat(129)
		), "watcher provenance remains bounded");
		assertThrows(IllegalArgumentException.class, () -> new ActionProvenance(
				"codex", "gpt-5.6-sol", "high", "priority", "program-7-1", 1L, "\u00a0", 4L
		), "provenance rejects non-breaking blank source steps");
		assertThrows(IllegalArgumentException.class, () -> new ActionProvenance(
				"codex", "gpt-5.6-sol", "high", "priority", "program-7-1", 1L, "step-80-126", -1L
		), "provenance rejects negative event sequences");
		for (ActionType type : ActionType.values()) {
			boolean expectedPrimitive = switch (type) {
				case CONTROL, MOVE_TO, NAVIGATE_TO, LOOK_AT, ATTACK, SELECT_ITEM, USE_ITEM, BREAK_BLOCK, PLACE_BLOCK, PICK_UP_ITEM,
						CHAT, WAIT, SET_DOOR, DROP_ITEM, TRANSFER_CONTAINER, CRAFT_INVENTORY, CRAFT_TABLE,
						FURNACE_TRANSACTION, EQUIP_ITEM, SELECT_TOOL, BLOCK_WITH_SHIELD, USE_RANGED,
						INTERACT_BLOCK, INTERACT_ENTITY, DISMOUNT, START_FALL_FLYING -> true;
				case MENU_TRANSFER, MENU_BUTTON, ANVIL_RENAME, MENU_CLICK, MENU_CLOSE,
						CONTROL_SEQUENCE, WAKE_UP, SET_FLIGHT, WRITE_SIGN, EDIT_BOOK, BEACON_EFFECTS -> true;
				case RESPAWN -> true;
				default -> false;
			};
			assertEquals(expectedPrimitive, ServerActionExecutor.isArenaScriptPrimitive(type),
					"server primitive parity for " + type.wireName());
		}
		for (ActionType retired : List.of(
				ActionType.BUILD_SEQUENCE,
				ActionType.FIGHT_TARGET,
				ActionType.FLEE_FROM,
				ActionType.FOLLOW_ENTITY
		)) {
			assertThrows(AgentDomainException.class, () -> ServerActionExecutor.requireArenaScriptPrimitive(retired),
					"program primitive entry point rejects retired controller action " + retired.wireName());
		}
		JsonObject mainHand = new JsonObject();
		mainHand.addProperty("hand", "main");
		assertEquals(net.minecraft.world.InteractionHand.MAIN_HAND, ServerActionExecutor.hand(mainHand),
				"raw control maps the main-hand wire value");
		JsonObject offHand = new JsonObject();
		offHand.addProperty("hand", "off");
		assertEquals(net.minecraft.world.InteractionHand.OFF_HAND, ServerActionExecutor.hand(offHand),
				"raw control maps the offhand wire value");
		JsonObject invalidHand = new JsonObject();
		invalidHand.addProperty("hand", "left");
		assertThrows(AgentDomainException.class, () -> ServerActionExecutor.hand(invalidHand),
				"raw control rejects an unknown hand value");
		ServerActionProgress progress = new ServerActionProgress(
				progressAgent, 7L, "action-7", ActionType.NAVIGATE_TO, 0.5D, 250L, 1_750_000_000_250L
		);
		assertEquals(progressAgent, progress.agentId(), "progress retains agent identity");
		assertEquals(0.5D, progress.progress(), "progress retains bounded fraction");
		ServerActionObservation observation = new ServerActionObservation(
				42L, 1_750_000_000_250L,
				new ServerActionObservation.Position(1.25D, 64.0D, -2.5D),
				new ServerActionObservation.Position(0.0D, 0.0D, 0.1D),
				90.0D, 12.0D,
				new ServerActionObservation.Collision(false, true, false),
				new ServerActionObservation.RayTarget("block", new ServerActionObservation.Position(2.0D, 64.0D, -2.0D), "minecraft:oak_log", "west", 2.75D),
				new ServerActionObservation.Reach(2.75D, 4.5D, true),
				new ServerActionObservation.Target("block", new ServerActionObservation.Position(2.0D, 64.0D, -2.0D), "minecraft:oak_log", "minecraft:oak_log", "minecraft:oak_log", "minecraft:oak_log", false, null, null, null),
				new ServerActionObservation.Progress(0.25D, "block_damage", true)
		);
		ServerActionProgress observedProgress = new ServerActionProgress(
				progressAgent, 7L, "action-observed", ActionType.BREAK_BLOCK, "trace-observed", 0.25D, 250L, 1_750_000_000_250L, observation
		);
		assertEquals(42L, observation.worldTick(), "action observation retains the server world tick without claiming a coordinator sequence");
		assertEquals(observation, observedProgress.actionObservation(), "progress retains authoritative action observation");
		assertThrows(IllegalArgumentException.class, () -> new ServerActionObservation.Progress(0.25D, "timer", true),
				"action observations reject executor timer progress bases");
		ServerActionRequest cancellationTarget = new ServerActionRequest(
				progressAgent, 7L, "action-7", ActionType.WAIT, new JsonObject(), provenance);
		assertThrows(NullPointerException.class, () -> new ServerActionRequest(
				progressAgent, 7L, "action-7", ActionType.WAIT, new JsonObject(), null
		), "requests reject absent provenance");
		assertTrue(ServerActionExecutor.matchesCancellation(cancellationTarget, 7L, "action-7"),
				"cancellation matches the exact revision and action identity");
		assertFalse(ServerActionExecutor.matchesCancellation(cancellationTarget, 8L, "action-7"),
				"cancellation rejects a newer goal revision");
		assertFalse(ServerActionExecutor.matchesCancellation(cancellationTarget, 7L, "action-8"),
				"cancellation rejects a different action identity");
		assertDoesNotThrow(() -> cleanupWaitAction(cancellationTarget),
				"action cleanup touches only resources owned by the action");
		assertDoesNotThrow(() -> new ServerNavigationController(
				Vec3.ZERO, 1.0D, false, 0L, 1_000L).cancel(null),
				"navigation without an acquired lease does not clear unrelated player input");
		assertDoesNotThrow(() -> uninitializedItemPickupController().cancel(null),
				"item pickup without acquired navigation does not clear unrelated player input");
		assertTrue(ServerActionExecutor.isCurrentCoordinatorGeneration(4L, 4L),
				"respawn completion remains valid only for its coordinator generation");
		assertFalse(ServerActionExecutor.isCurrentCoordinatorGeneration(4L, 5L),
				"respawn completion from a disconnected coordinator generation is ignored");
		verifyDisconnectedRespawnFinishesOnce();
		verifyDisconnectedControlNeutralizesLease();
		assertThrows(IllegalArgumentException.class, () -> new ServerActionProgress(
				progressAgent, 7L, "action-7", ActionType.NAVIGATE_TO, 1.1D, 250L, 1_750_000_000_250L
		), "progress rejects fractions above one");
		AtomicInteger progressAttempts = new AtomicInteger();
		ServerActionExecutor.publishProgressBestEffort(item -> {
			progressAttempts.incrementAndGet();
			throw new IllegalStateException("bridge backpressure");
		}, progress);
		assertEquals(1, progressAttempts.get(), "progress telemetry failure is isolated from the server tick");
		assertEquals(ServerController.TickResult.failed(
				"CONTROLLER_NO_RESULT", "Navigation controller returned no result", 0.4D),
				ServerActionExecutor.requireControllerResult(null, 0.4D, "Navigation"),
				"missing controller results become bounded failures instead of null dereferences");
		assertEquals(
				new Vec3(10.5D, 65.999D, -3.5D),
				ServerActionExecutor.placementLookTarget(new BlockPos(10, 65, -4), Direction.UP),
				"placement aims at the requested support face instead of its center"
		);
		assertEquals(
				new Vec3(10.999D, 65.5D, -3.5D),
				ServerActionExecutor.placementLookTarget(new BlockPos(10, 65, -4), Direction.EAST),
				"horizontal placement aims at the requested support face"
		);
		assertTrue(ServerActionExecutor.isValidPlacementHit(
				new BlockPos(10, 65, -4), new Vec3(10.999D, 65.5D, -3.5D)
		), "placement accepts a hit location on the support block face");
		assertFalse(ServerActionExecutor.isValidPlacementHit(
				new BlockPos(10, 65, -4), new Vec3(12.0D, 65.5D, -3.5D)
		), "placement rejects a forged hit location outside the support block");
		assertThrows(
				AgentDomainException.class,
				() -> ServerActionExecutor.requirePlacementProtection(
						ServerProtectionPolicy.DENY_ALL, null, null, BlockPos.ZERO
				),
				"placement honors the configured protection policy"
		);
		assertEquals("28c7b487-49f8-4eb1-bf03-848553cce39a",
				EntityTargetSelector.normalize("uuid:28c7b487-49f8-4eb1-bf03-848553cce39a"),
				"observation-style UUID selectors are accepted");
		assertEquals("SolEmer", EntityTargetSelector.normalize("name=SolEmer"),
				"observation-style name selectors are accepted");
		assertEquals("Lucas", EntityTargetSelector.normalize("player:Lucas"),
				"explicit player selectors retain their existing behavior");
		assertEquals("nearest_hostile", EntityTargetSelector.normalize("nearest_hostile"),
				"symbolic proximity selectors remain unchanged");
		assertEquals("TARGET_OCCUPIED",
				ServerActionExecutor.failureReason(new dev.agaminggod.arenaagents.agent.AgentDomainException(
						"TARGET_OCCUPIED", "changed world")),
				"runtime revalidation preserves a precise recoverable domain reason");
		assertEquals("ACTION_EXCEPTION", ServerActionExecutor.failureReason(new IllegalStateException("broken")),
				"unexpected runtime exceptions remain isolated");
		verifySetupFailureDoesNotClaimPhysicalExecution();
		verifySetupFailurePublicationRetries();
		verifyPermanentCleanupQuarantines();
		assertEquals(0, ServerActionExecutor.roundRobinStart(0L, 16),
				"round-robin starts with the first active agent");
		assertEquals(1, ServerActionExecutor.roundRobinStart(1L, 16),
				"round-robin advances one active agent per tick");
		assertEquals(0, ServerActionExecutor.roundRobinStart(16L, 16),
				"round-robin wraps after all sixteen active agents");
		boolean[] admitted = new boolean[16];
		for (long cursor = 0L; cursor < admitted.length; cursor++) {
			int start = ServerActionExecutor.roundRobinStart(cursor, admitted.length);
			assertFalse(admitted[start], "round-robin does not admit an agent twice before the full turn");
			admitted[start] = true;
		}
		verifyModelOnlyControlBoundary();
		return 112 + dev.agaminggod.arenaagents.protocol.PlayerActionSchemaVerification.verify()
				+ dev.agaminggod.arenaagents.server.perception.PlayerKnowledgeInspectionVerification.verify()
				+ verifyInteractionOutlineHit();
	}

	private static int verifyInteractionOutlineHit() {
		BlockPos position = new BlockPos(2, 64, 0);
		Vec3 eye = new Vec3(0.5D, 65.62D, 0.5D);
		VoxelShape chest = Blocks.CHEST.defaultBlockState().getShape(EmptyBlockGetter.INSTANCE, position);
		Vec3 unitFace = new Vec3(2.0D, 64.5D, 0.5D);
		assertTrue(chest.clip(eye, unitFace.add(unitFace.subtract(eye).normalize().scale(0.001D)), position) == null,
				"the unit-cube face ray stops before an inset chest outline");
		Vec3 target = ServerActionExecutor.blockInteractionHitLocation(position, Direction.WEST, new JsonObject(), chest);
		assertEquals(new Vec3(2.0625D, 64.4375D, 0.5D), target, "default chest aim uses its actual west outline face");
		BlockHitResult hit = chest.clip(eye, target.add(target.subtract(eye).normalize().scale(0.001D)), position);
		assertTrue(hit != null, "the corrected ray enters the chest outline");
		assertEquals(Direction.WEST, hit.getDirection(), "the corrected ray hits the requested face");
		assertEquals(position, hit.getBlockPos(), "the corrected ray retains the requested block identity");
		assertEquals(unitFace, ServerActionExecutor.blockInteractionHitLocation(position, Direction.WEST,
				new JsonObject(), Shapes.block()), "full-cube default face remains unchanged");
		VoxelShape slab = Blocks.OAK_SLAB.defaultBlockState().getShape(EmptyBlockGetter.INSTANCE, position);
		Vec3 slabTarget = ServerActionExecutor.blockInteractionHitLocation(position, Direction.UP, new JsonObject(), slab);
		assertEquals(new Vec3(2.5D, 64.5D, 0.5D), slabTarget, "bottom slab aim uses its actual top surface");
		assertEquals(Direction.UP, slab.clip(new Vec3(2.5D, 66.0D, 0.5D), slabTarget.add(0, -0.001D, 0), position).getDirection(),
				"a short ray reaches the actual slab top");
		JsonObject explicit = new JsonObject();
		explicit.addProperty("hitX", 0.0D);
		explicit.addProperty("hitY", 0.5D);
		explicit.addProperty("hitZ", 0.5D);
		Vec3 supplied = ServerActionExecutor.blockInteractionHitLocation(position, Direction.WEST, explicit, chest);
		assertEquals(unitFace, supplied, "explicit hit offsets are never moved onto the outline");
		assertTrue(chest.clip(eye, supplied.add(supplied.subtract(eye).normalize().scale(0.001D)), position) == null,
				"an explicit ray that stops outside the outline remains invalid");
		assertThrows(AgentDomainException.class, () -> ServerActionExecutor.blockInteractionHitLocation(
				position, Direction.WEST, new JsonObject(), Shapes.empty()), "an absent outline cannot invent a default interaction surface");
		assertEquals(unitFace, ServerActionExecutor.blockInteractionHitLocation(position, Direction.WEST, explicit, Shapes.empty()),
				"explicit geometry remains authored even when the later vanilla trace cannot hit");
		return 12;
	}

	private static void verifyModelOnlyControlBoundary() {
		try (var compiledRuntime = ServerActionExecutorVerification.class.getResourceAsStream(
				"/dev/agaminggod/arenaagents/server/CodexAgentServerRuntime.class")) {
			assertTrue(compiledRuntime != null, "production runtime bytecode is present for the registration check");
			String references = new String(compiledRuntime.readAllBytes(), java.nio.charset.StandardCharsets.ISO_8859_1);
			assertFalse(references.contains("GoalSafetyController"), "compiled production runtime cannot register the removed autonomous safety controller");
		} catch (java.io.IOException exception) {
			throw new AssertionError("could not inspect production runtime registration", exception);
		}
		AgentId agent = AgentId.random();
		JsonObject arguments = com.google.gson.JsonParser.parseString("{\"frames\":[{\"ticks\":5}],\"maxTicks\":5}").getAsJsonObject();
		ActionProvenance provenance = new ActionProvenance("codex", "model", "high", "priority", "authored-control", 1L, "step-1", 1L);
		ServerActionRequest unsigned = new ServerActionRequest(agent, 1L, "control-untraced", ActionType.CONTROL_SEQUENCE, arguments, provenance);
		ServerActionExecutor executor = new ServerActionExecutor(uninitializedManager(), ignored -> { });
		assertThrows(AgentDomainException.class, () -> executor.submitProgramPrimitive(unsigned),
				"conditional input programs cannot bypass the model trace requirement");
		assertThrows(NullPointerException.class, () -> new ServerActionRequest(agent, 1L, "control-no-author", ActionType.CONTROL_SEQUENCE, arguments, null),
				"conditional input programs require author provenance before execution");
		arguments.getAsJsonArray("frames").get(0).getAsJsonObject().addProperty("ticks", 200);
		assertEquals(5, unsigned.arguments().getAsJsonArray("frames").get(0).getAsJsonObject().get("ticks").getAsInt(),
				"a submitted model program owns a detached copy of every input frame");
	}

	private static void verifyDisconnectedControlNeutralizesLease() {
		AgentId agentId = AgentId.random();
		List<AgentId> cleared = new ArrayList<>();
		InputStateSink sink = new InputStateSink() {
			@Override
			public void apply(AgentId ignored, AgentInputState previous, AgentInputState state) {
			}

			@Override
			public void clear(AgentId clearedAgent, AgentInputState previous) {
				cleared.add(clearedAgent);
			}
		};
		LeasedServerInputController controller = new LeasedServerInputController(sink);
		InputLease lease = controller.acquire(agentId, InputOwner.DIRECT_CONTROL, 250);
		controller.apply(lease, new AgentInputState(
				1.0F, -1.0F, true, true, true, true, true,
				90.0F, 15.0F, 4, InteractionHand.OFF_HAND
		));
		Object action = new Object();
		Map<AgentId, Object> active = new LinkedHashMap<>();
		active.put(agentId, action);
		AtomicInteger lifecycleFinishes = new AtomicInteger();
		assertTrue(ServerActionExecutor.finishDisconnectedControl(
				active, agentId, action, () -> controller.clear(agentId), lifecycleFinishes::incrementAndGet
		), "coordinator disconnect fences the active control action");
		assertTrue(active.isEmpty(), "disconnected control cannot tick again");
		assertTrue(controller.currentState(agentId).isEmpty(), "disconnect removes the active control lease immediately");
		assertEquals(List.of(agentId), cleared, "disconnect neutralizes the physical input sink once");
		assertThrows(IllegalStateException.class, () -> controller.apply(lease, new AgentInputState(
				1.0F, 0.0F, false, false, true, false, false,
				0.0F, 0.0F, 0, InteractionHand.MAIN_HAND
		)), "disconnected control lease cannot renew");
		assertEquals(1, lifecycleFinishes.get(), "disconnect finishes the control lifecycle once");
		assertFalse(ServerActionExecutor.finishDisconnectedControl(
				active, agentId, action, () -> controller.clear(agentId), lifecycleFinishes::incrementAndGet
		), "duplicate disconnect cannot neutralize the same control twice");
	}

	private static void verifyDisconnectedRespawnFinishesOnce() {
		AgentId agentId = AgentId.random();
		Object pending = new Object();
		Map<AgentId, Object> pendingRespawns = new LinkedHashMap<>();
		pendingRespawns.put(agentId, pending);
		AtomicInteger terminalReports = new AtomicInteger();
		assertTrue(finishDisconnectedRespawn(
				pendingRespawns, agentId, pending, terminalReports::incrementAndGet),
				"the accepted pending respawn is finished at disconnect");
		assertFalse(finishDisconnectedRespawn(
				pendingRespawns, agentId, pending, terminalReports::incrementAndGet),
				"a second disconnect cannot finish the same respawn twice");
		assertTrue(pendingRespawns.isEmpty(), "disconnect only detaches the coordinator observer");
		assertEquals(1, terminalReports.get(), "pending respawn terminal reporting runs exactly once");
	}

	private static boolean finishDisconnectedRespawn(
			Map<AgentId, ?> pendingRespawns,
			AgentId agentId,
			Object pending,
			Runnable terminalReport
	) {
		try {
			var method = ServerActionExecutor.class.getDeclaredMethod(
					"finishDisconnectedRespawn", Map.class, AgentId.class, Object.class, Runnable.class);
			method.setAccessible(true);
			return (boolean) method.invoke(null, pendingRespawns, agentId, pending, terminalReport);
		} catch (ReflectiveOperationException exception) {
			throw new AssertionError("missing pending-respawn disconnect boundary", exception);
		}
	}

	private static void verifySetupFailureDoesNotClaimPhysicalExecution() {
		CodexAgentManager manager = uninitializedManager();
		AgentRecord agent = manager.registry().create(
				"codex", "gpt-5.6-sol", "high", Optional.of("SetupFailureTarget"), AgentGameMode.SURVIVAL, 1_000L
		);
		manager.startSubjective(agent.agentId().toString(), "Set up the executor test");
		manager.registry().setAutomaticProgress(agent.agentId(), false, 1_001L);
		ActionProvenance provenance = new ActionProvenance(
				"codex", "gpt-5.6-sol", "high", "priority", "program-setup", 1L, "step-setup", 1L, "trace-setup"
		);
		JsonObject arguments = new JsonObject();
		arguments.addProperty("durationMs", 1L);
		ServerActionRequest request = new ServerActionRequest(
				agent.agentId(), agent.goalRevision() + 1L, "action-setup", ActionType.WAIT, arguments, provenance, "trace-setup"
		);
		List<ServerActionResult> results = new ArrayList<>();
		new ServerActionExecutor(manager, results::add).submitProgramPrimitive(request);
		assertEquals(1, results.size(), "missing player setup failure emits one terminal result");
		ServerActionResult result = results.getFirst();
		assertFalse(result.executionStarted(), "setup failure does not claim execution started");
		assertFalse(result.physicalAttempted(), "setup failure does not claim a physical attempt");
	}

	private static void verifySetupFailurePublicationRetries() {
		CodexAgentManager manager = uninitializedManager();
		AgentRecord agent = manager.registry().create(
				"codex", "gpt-5.6-sol", "high", Optional.of("PublicationRetryTarget"), AgentGameMode.SURVIVAL, 2_000L
		);
		manager.startSubjective(agent.agentId().toString(), "Retry a terminal result");
		manager.registry().setAutomaticProgress(agent.agentId(), false, 2_001L);
		ActionProvenance provenance = new ActionProvenance(
				"codex", "gpt-5.6-sol", "high", "priority", "program-publication", 1L,
				"step-publication", 1L, "trace-publication"
		);
		JsonObject arguments = new JsonObject();
		arguments.addProperty("durationMs", 1L);
		ServerActionRequest request = new ServerActionRequest(
				agent.agentId(), agent.goalRevision() + 1L, "action-publication", ActionType.WAIT,
				arguments, provenance, "trace-publication"
		);
		AtomicInteger attempts = new AtomicInteger();
		List<ServerActionResult> delivered = new ArrayList<>();
		ServerActionExecutor executor = new ServerActionExecutor(manager, result -> {
			if (attempts.incrementAndGet() == 1) throw new IllegalStateException("coordinator unavailable");
			delivered.add(result);
		});

		executor.submitProgramPrimitive(request);
		assertEquals(1, attempts.get(), "setup terminal result makes its first publication attempt");
		assertEquals("ACTION_REJECTED", executor.lastResult(agent.agentId()).reasonCode(),
				"failed publication retains the exact terminal result");
		assertThrows(AgentDomainException.class, () -> executor.submitProgramPrimitive(request),
				"pending terminal publication fences physical resubmission");

		executor.tick();
		assertEquals(2, attempts.get(), "executor tick retries the terminal sink");
		assertEquals(1, delivered.size(), "recovered terminal sink receives one result");
		executor.tick();
		assertEquals(2, attempts.get(), "delivered terminal result is not published again");
	}

	@SuppressWarnings("unchecked")
	private static void verifyPermanentCleanupQuarantines() {
		CodexAgentManager manager = uninitializedManager();
		AgentRecord agent = manager.registry().create(
				"codex", "gpt-5.6-sol", "high", Optional.of("CleanupQuarantineTarget"), AgentGameMode.SURVIVAL, 3_000L
		);
		manager.startSubjective(agent.agentId().toString(), "Exercise cleanup quarantine");
		manager.registry().setAutomaticProgress(agent.agentId(), false, 3_001L);
		ActionProvenance provenance = new ActionProvenance(
				"codex", "gpt-5.6-sol", "high", "priority", "program-cleanup", 1L,
				"step-cleanup", 1L, "trace-cleanup"
		);
		ServerActionRequest request = new ServerActionRequest(
				agent.agentId(), agent.goalRevision() + 1L, "action-cleanup", ActionType.SELECT_TOOL,
				new JsonObject(), provenance, "trace-cleanup"
		);
		AtomicInteger cleanupAttempts = new AtomicInteger();
		ServerTransactionAdapter.ActiveTransaction transaction = new ServerTransactionAdapter.ActiveTransaction() {
			@Override
			public ServerTransactionAdapter.TickResult tick(long nowEpochMs) {
				return ServerTransactionAdapter.TickResult.succeeded("TOOL_SELECTED", "selected");
			}

			@Override
			public void cancel(String reason) {
			}

			@Override
			public void cleanup() {
				if (cleanupAttempts.incrementAndGet() <= ServerActionExecutor.MAX_CLEANUP_ATTEMPTS) {
					throw new IllegalStateException("input sink unavailable");
				}
			}
		};
		List<ServerActionResult> results = new ArrayList<>();
		ServerActionExecutor executor = new ServerActionExecutor(manager, results::add);
		try {
			Class<?> actionClass = Class.forName(ServerActionExecutor.class.getName() + "$ActiveAction");
			var factory = actionClass.getDeclaredMethod(
					"transaction", ServerActionRequest.class, net.minecraft.server.level.ServerPlayer.class,
					ServerTransactionAdapter.ActiveTransaction.class);
			factory.setAccessible(true);
			Object action = factory.invoke(null, request, null, transaction);
			Field activeField = ServerActionExecutor.class.getDeclaredField("active");
			activeField.setAccessible(true);
			((Map<AgentId, Object>) activeField.get(executor)).put(agent.agentId(), action);
			assertEquals(request, executor.activeRequest(agent.agentId()),
					"direct active-action lookup returns the exact request without materializing the active set");
			assertTrue(executor.activeRequest(AgentId.random()) == null,
					"direct active-action lookup is empty for an unrelated agent");
			var resultMethod = actionClass.getDeclaredMethod(
					"result", ServerActionState.class, String.class, String.class, long.class);
			resultMethod.setAccessible(true);
			ServerActionResult original = (ServerActionResult) resultMethod.invoke(
					action, ServerActionState.SUCCEEDED, "TOOL_SELECTED", "selected", 3_100L);
			var finish = ServerActionExecutor.class.getDeclaredMethod("finish", actionClass, ServerActionResult.class);
			finish.setAccessible(true);
			for (int attempt = 0; attempt < ServerActionExecutor.MAX_CLEANUP_ATTEMPTS; attempt++) {
				finish.invoke(executor, action, original);
			}
			assertEquals(ServerActionExecutor.MAX_CLEANUP_ATTEMPTS, cleanupAttempts.get(),
					"input cleanup reaches the bounded quarantine threshold");
			assertEquals(1, results.size(), "cleanup quarantine publishes one terminal failure");
			assertEquals("ACTION_CLEANUP_FAILED", results.getFirst().reasonCode(),
					"cleanup quarantine replaces a misleading success result");
			assertTrue(executor.activeRequest(request.agentId()) == null,
					"terminally failed cleanup is no longer advertised as a running action");
			assertThrows(AgentDomainException.class, () -> executor.submitProgramPrimitive(request),
					"quarantine fences another physical action while cleanup is unsafe");

		} catch (ReflectiveOperationException exception) {
			throw new AssertionError("could not exercise permanent cleanup quarantine", exception);
		}
		executor.tick();
		assertEquals(ServerActionExecutor.MAX_CLEANUP_ATTEMPTS + 1, cleanupAttempts.get(),
				"quarantined cleanup retries the retained physical release until it recovers");
		assertTrue(executor.activeRequest(request.agentId()) == null, "quarantined action leaves the active execution set");
		assertDoesNotThrow(() -> executor.submitProgramPrimitive(request),
				"successful retained cleanup automatically clears the action quarantine");
	}

	private static CodexAgentManager uninitializedManager() {
		try {
			Field field = sun.misc.Unsafe.class.getDeclaredField("theUnsafe");
			field.setAccessible(true);
			sun.misc.Unsafe unsafe = (sun.misc.Unsafe) field.get(null);
			CodexAgentManager manager = (CodexAgentManager) unsafe.allocateInstance(CodexAgentManager.class);
			Field savedData = CodexAgentManager.class.getDeclaredField("savedData");
			unsafe.putObject(manager, unsafe.objectFieldOffset(savedData), new AgentSavedData());
			return manager;
		} catch (ReflectiveOperationException exception) {
			throw new AssertionError("could not allocate setup-failure manager", exception);
		}
	}

	private static void cleanupWaitAction(ServerActionRequest request) {
		try {
			Class<?> actionClass = Class.forName(ServerActionExecutor.class.getName() + "$ActiveAction");
			var waitFor = actionClass.getDeclaredMethod(
					"waitFor", ServerActionRequest.class, net.minecraft.server.level.ServerPlayer.class, long.class);
			waitFor.setAccessible(true);
			Object action = waitFor.invoke(null, request, null, 1L);
			var cleanup = actionClass.getDeclaredMethod("cleanup");
			cleanup.setAccessible(true);
			cleanup.invoke(action);
		} catch (ReflectiveOperationException exception) {
			throw new AssertionError("could not exercise action-owned cleanup", exception);
		}
	}

	private static ServerItemPickupController uninitializedItemPickupController() {
		try {
			Field field = sun.misc.Unsafe.class.getDeclaredField("theUnsafe");
			field.setAccessible(true);
			sun.misc.Unsafe unsafe = (sun.misc.Unsafe) field.get(null);
			return (ServerItemPickupController) unsafe.allocateInstance(ServerItemPickupController.class);
		} catch (ReflectiveOperationException exception) {
			throw new AssertionError("could not allocate item-pickup cleanup probe", exception);
		}
	}

	private static void assertEquals(Object expected, Object actual, String label) {
		if (!expected.equals(actual)) throw new AssertionError(label + ": expected <" + expected + "> but was <" + actual + ">");
	}

	private static void assertTrue(boolean value, String label) {
		if (!value) throw new AssertionError(label);
	}

	private static void assertFalse(boolean value, String label) {
		if (value) throw new AssertionError(label);
	}

	private static void assertDoesNotThrow(Runnable action, String label) {
		try {
			action.run();
		} catch (Throwable throwable) {
			throw new AssertionError(label + " threw " + throwable.getClass().getSimpleName(), throwable);
		}
	}

	private static void assertThrows(Class<? extends Throwable> type, Runnable action, String label) {
		try {
			action.run();
		} catch (Throwable throwable) {
			if (type.isInstance(throwable)) return;
			throw new AssertionError(label + " threw " + throwable.getClass().getSimpleName(), throwable);
		}
		throw new AssertionError(label + " did not throw " + type.getSimpleName());
	}
}
