package dev.agaminggod.arenaagents.server.bridge;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.mojang.serialization.Codec;
import dev.agaminggod.arenaagents.agent.AgentConstants;
import dev.agaminggod.arenaagents.agent.AgentDeathSnapshot;
import dev.agaminggod.arenaagents.agent.AgentId;
import dev.agaminggod.arenaagents.agent.AgentLifecycleState;
import dev.agaminggod.arenaagents.agent.AgentProfile;
import dev.agaminggod.arenaagents.agent.AgentRecord;
import dev.agaminggod.arenaagents.agent.AgentTransition;
import dev.agaminggod.arenaagents.agent.goal.GoalEvidence;
import dev.agaminggod.arenaagents.agent.goal.GoalPredicate;
import dev.agaminggod.arenaagents.agent.goal.GoalSpec;
import dev.agaminggod.arenaagents.server.AgentRuntimeHooks;
import dev.agaminggod.arenaagents.server.AgentSavedData;
import dev.agaminggod.arenaagents.server.AgentVerboseState;
import dev.agaminggod.arenaagents.server.CodexAgentManager;
import dev.agaminggod.arenaagents.server.conversation.ConversationAudience;
import dev.agaminggod.arenaagents.server.conversation.ConversationEvent;
import dev.agaminggod.arenaagents.server.conversation.ConversationKind;
import dev.agaminggod.arenaagents.server.conversation.PendingConversationWakeCodec;
import dev.agaminggod.arenaagents.server.goal.DraftIntent;
import dev.agaminggod.arenaagents.server.goal.GoalCompiler;
import dev.agaminggod.arenaagents.server.goal.GoalDraftChoice;
import dev.agaminggod.arenaagents.server.goal.GoalSpecWireCodec;
import dev.agaminggod.arenaagents.server.goal.PendingGoalDraft;
import dev.agaminggod.arenaagents.server.perception.ObservationDispatchQueue;
import dev.agaminggod.arenaagents.server.perception.ServerObservationWireBudget;
import dev.agaminggod.arenaagents.server.runtime.ActionProvenance;
import dev.agaminggod.arenaagents.server.runtime.GoalCompletionVerifier;
import dev.agaminggod.arenaagents.server.runtime.ServerActionProgress;
import dev.agaminggod.arenaagents.server.runtime.ServerActionObservation;
import dev.agaminggod.arenaagents.server.runtime.ServerActionRequest;
import dev.agaminggod.arenaagents.server.runtime.ServerActionResult;
import dev.agaminggod.arenaagents.server.runtime.ServerActionState;
import dev.agaminggod.arenaagents.server.runtime.ServerActionExecutor;
import dev.agaminggod.arenaagents.protocol.ActionType;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.lang.reflect.Field;
import java.net.ServerSocket;
import java.lang.reflect.Method;
import java.net.InetAddress;
import java.net.Proxy;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.AtomicInteger;
import net.minecraft.nbt.NbtOps;
import net.minecraft.nbt.Tag;
import net.minecraft.SystemReport;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.permissions.LevelBasedPermissionSet;
import net.minecraft.server.permissions.PermissionSet;
import net.minecraft.server.players.PlayerList;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.debugchart.SampleLogger;

public final class MultiplexedServerBridgeVerification {
	private static final int BACKPRESSURE_HANDSHAKE_TIMEOUT_MS = 10_000;

	private MultiplexedServerBridgeVerification() {
	}

	public static void main(String[] args) {
		net.minecraft.SharedConstants.tryDetectVersion();
		net.minecraft.server.Bootstrap.bootStrap();
		if (args.length == 1 && "preauth-overflow".equals(args[0])) {
			verifyPreauthOverflowPreservesIncumbentHandshake();
			System.out.println("MultiplexedServerBridgeVerification preauth-overflow assertions=14");
			return;
		}
		System.out.println("MultiplexedServerBridgeVerification assertions=" + verify());
	}

	public static int verify() {
		verifyPendingRegistrationBoundary();
		verifyRemovalBackpressureForcesReconciliation();
		verifyHandshakeWaitsForPendingMarker();
		verifyReplacementHandshakeSupersedesPendingDisconnect();
		verifyAuthenticatedReconnectRecovery();
		verifyTerminalReplaySurvivesDisconnectRevision();
		verifyAcceptedActionRecoversWithoutReplay();
		verifyRebindPersistsCancellationBeforeClosingJournal();
		verifyObsoletePlannerReadinessIsIgnored();
		verifyAgentErrorRevisionGate();
		verifyRespawnContinuationPayload();
		verifyVerboseControlRetriesAfterBackpressure();
		verifyVerboseTelemetryCannotSuppressProgress();
		assertEquals(true, MultiplexedServerBridge.HANDSHAKE_RETRY_WAIT_MS > 0L,
				"hello retries wait instead of spinning when the registry snapshot moves");
		List<AgentRecord> registered = new ArrayList<>();
		for (int index = 0; index <= AgentConstants.DEFAULT_AGENT_LIMIT; index++) {
			registered.add(AgentRecord.create(
					AgentId.parse(String.format("00000000-0000-0000-0000-%012d", index + 1)),
					new AgentProfile("codex", "gpt-5.6-sol", "high", Optional.empty(), index),
					1_000L + index
			));
		}
		List<AgentId> candidates = MultiplexedServerBridge.registeredObservationIds(registered);
		assertEquals(AgentConstants.DEFAULT_AGENT_LIMIT, candidates.size(),
				"publication remains capped at sixteen despite a malformed seventeen-agent registry");
		ObservationDispatchQueue<AgentId> publicationQueue = new ObservationDispatchQueue<>(
				AgentConstants.DEFAULT_AGENT_LIMIT,
				AgentConstants.DEFAULT_AGENT_LIMIT
		);
		candidates.forEach(publicationQueue::offer);
		List<AgentId> published = new ArrayList<>();
		publicationQueue.drain(published::add);
		assertEquals(candidates, published, "all sixteen registered agents publish within one server tick");
		assertEquals(0, publicationQueue.pendingCount(), "one-tick publication drains the bounded queue");
		AgentId idleAgent = registered.getFirst().agentId();
		assertTrue(idleAgent.equals(published.getFirst()),
				"an idle registered agent without an active action is sampled and published");

		List<String> events = new ArrayList<>();
		assertThrows(IllegalStateException.class, () -> MultiplexedServerBridge.publishRespawnScenarioEvents(
				() -> { throw new IllegalStateException("publication failed"); },
				() -> events.add("action"),
				() -> events.add("state")
		), "failed respawn publication emits no scenario success or state");
		assertTrue(events.isEmpty(), "failed respawn publication leaves scenario records untouched");
		MultiplexedServerBridge.publishRespawnScenarioEvents(
				() -> events.add("publication"),
				() -> events.add("action"),
				() -> events.add("state")
		);
		assertEquals(List.of("publication", "action", "state"), events,
				"respawn scenario success and PAUSED/IDLE state follow committed paired publication");
		List<String> committed = new ArrayList<>();
		MultiplexedServerBridge.publishRespawnScenarioEvents(
				() -> committed.add("paired-messages-and-commit"),
				() -> { committed.add("action-attempted"); throw new IllegalStateException("telemetry unavailable"); },
				() -> committed.add("state-after-telemetry-failure")
		);
		assertEquals(List.of("paired-messages-and-commit", "action-attempted", "state-after-telemetry-failure"), committed,
				"scenario callback failure cannot escape or roll back committed respawn publication");
		List<String> durableRespawn = new ArrayList<>();
		MultiplexedServerBridge.commitRespawnTerminal(
				() -> durableRespawn.add("commit"),
				() -> durableRespawn.add("terminal")
		);
		assertEquals(List.of("commit", "terminal"), durableRespawn,
				"respawn success becomes durable only after the rollback-capable lifecycle commit");
		AtomicBoolean terminalPersisted = new AtomicBoolean();
		assertThrows(IllegalStateException.class, () -> MultiplexedServerBridge.commitRespawnTerminal(
				() -> { throw new IllegalStateException("commit rejected"); },
				() -> terminalPersisted.set(true)
		), "failed respawn commit does not persist terminal success");
		assertTrue(!terminalPersisted.get(), "failed respawn commit leaves the accepted journal phase intact");
		verifyDeathFacts();
		verifyTraceWireValidation();
		verifyIdleDirectReplyRevisionPolicy(registered.getFirst());
		verifyExactTargetObservationLedger(registered.getFirst().agentId());
		verifyConversationAttention(registered.getFirst().agentId());
		verifyObservationCadence(candidates);
		verifyObservationPublicationLifecycle(registered.getFirst().agentId());
		verifyInspectionQueryValidation();
		verifyInspectionPublication(registered.getFirst().agentId());
		verifyInspectionWireBudget(registered.getFirst().agentId());
		verifyEmptyCatalogRequestsLiveDiscovery();
		verifyRealBridgeSessionLifecycle();
		verifyPreauthOverflowPreservesIncumbentHandshake();
		verifyAtomicConversationWakePublication();
		verifyGoalSpecProposalLifecycle();
		verifyStaleGoalDraftIsPrunedBeforeHandshake();
		verifyGoalDraftCreatedDuringHandshakeIsReplayed();
		verifyCompletionResultFacts();
		verifyReplacementOperation();
		verifyFailedBindClosesEverySocket();
		verifyShutdownRejectsAcceptedSocketBeforePublication();
		verifyLaunchIdentityAndReconnectGeneration();
		verifyReplacementHandshakeDrainsPreviousDisconnect();
		verifyHandshakeSnapshotAvoidsRegistryLockInversion();
		verifyHandshakeResnapshotsLifecycleRaces();
		verifyHandshakeSnapshotDeadline();
		verifyImmediateHandshakeClosePreservesDisconnect();
		verifyPendingRegistrationMarkerIsFenced();
		verifyAtomicPublicationRacesSessionClose();
		return 294;
	}

	@SuppressWarnings("unchecked")
	private static void verifyRebindPersistsCancellationBeforeClosingJournal() {
		MultiplexedServerBridge bridge = null;
		Path secretFile = null;
		Path journalFile = null;
		try {
			secretFile = Files.createTempFile("arena-agents-rebind-secret-", ".txt");
			Files.writeString(secretFile, "0123456789abcdef0123456789abcdef");
			journalFile = Files.createTempFile("arena-agents-rebind-journal-", ".json");
			Files.delete(journalFile);
			CodexAgentManager manager = uninitializedManager();
			AgentRecord created = manager.registry().create("gpt-5.6-sol", "high", Optional.of("Rebind"), 4_000L);
			AgentRecord started = manager.registry().start(created.agentId(), "wait", 4_001L).after();
			JsonObject arguments = new JsonObject();
			arguments.addProperty("durationMs", 1000L);
			ServerActionRequest request = new ServerActionRequest(
					started.agentId(), started.goalRevision(), "wait-before-rebind", ActionType.WAIT, arguments,
					new ActionProvenance("codex", "gpt-5.6-sol", "high", "priority", "rebind", 1L, "wait", 1L)
			);
			DurableActionJournal journal = DurableActionJournal.open(journalFile);
			journal.accept(request, started.currentGoal().orElseThrow().goalId());
			bridge = new MultiplexedServerBridge(manager, 0, secretFile, ServerSocket::new, System::nanoTime, journal);
			ServerActionExecutor executor = (ServerActionExecutor) readPrivateField(bridge, "actionExecutor");
			Class<?> actionClass = Class.forName(ServerActionExecutor.class.getName() + "$ActiveAction");
			Method waitFor = actionClass.getDeclaredMethod("waitFor", ServerActionRequest.class, ServerPlayer.class, long.class);
			waitFor.setAccessible(true);
			((Map<AgentId, Object>) readPrivateField(executor, "active")).put(
					started.agentId(), waitFor.invoke(null, request, null, 1000L));
			((AgentSavedData) readPrivateField(manager, "savedData")).setRuntimeHooks(new AgentRuntimeHooks() {
				@Override public void onTransition(AgentTransition transition) {
					if (transition.cancelAction()) executor.cancel(transition.after().agentId(), "Bridge rebind");
				}
			});
			((AtomicBoolean) readPrivateField(bridge, "activeDisconnectPending")).set(true);
			bridge.closeAndDrainDisconnect();
			try (DurableActionJournal reopened = DurableActionJournal.open(journalFile)) {
				DurableActionJournal.Entry entry = reopened.snapshot().getFirst();
				assertEquals(DurableActionJournal.Phase.TERMINAL, entry.phase(), "rebind persists cancellation before closing the journal");
				assertEquals("ACTION_CANCELLED", entry.result().reasonCode(), "rebind retains the definite cancellation outcome");
			}
			assertTrue(((Map<?, ?>) readPrivateField(executor, "active")).isEmpty(), "rebind finishes the cancelled action");
		} catch (ReflectiveOperationException | IOException exception) {
			throw new AssertionError("rebind journal verification failed", exception);
		} finally {
			if (bridge != null) bridge.close();
			deleteIfExists(secretFile);
			deleteIfExists(journalFile);
		}
	}

	private static void verifyAcceptedActionRecoversWithoutReplay() {
		MultiplexedServerBridge bridge = null;
		Path secretFile = null;
		Path journalFile = null;
		try {
			secretFile = Files.createTempFile("arena-agents-action-recovery-secret-", ".txt");
			Files.writeString(secretFile, "0123456789abcdef0123456789abcdef");
			journalFile = Files.createTempFile("arena-agents-action-recovery-", ".json");
			Files.delete(journalFile);
			CodexAgentManager manager = uninitializedManager();
			AgentRecord created = manager.registry().create("gpt-5.6-sol", "high", Optional.of("JournalRecovery"), 4_000L);
			AgentRecord started = manager.registry().start(created.agentId(), "recover the accepted action", 4_001L).after();
			JsonObject arguments = new JsonObject();
			arguments.addProperty("durationMs", 25L);
			String traceId = "trace-action-recovery";
			ServerActionRequest request = new ServerActionRequest(
					started.agentId(), started.goalRevision(), "accepted-before-crash", ActionType.WAIT, arguments,
					new ActionProvenance(
							"codex", "gpt-5.6-sol", "high", "priority", "recovery-program", 1L,
							"recovery-step", 1L, traceId, null
					), traceId
			);
			DurableActionJournal journal = DurableActionJournal.open(journalFile);
			journal.accept(request, started.currentGoal().orElseThrow().goalId());
			bridge = new MultiplexedServerBridge(manager, 0, secretFile, ServerSocket::new, System::nanoTime, journal);
			bridge.hydrateActionJournalForVerification();

			journal.close();
			DurableActionJournal recoveredJournal = DurableActionJournal.open(journalFile);
			DurableActionJournal.Entry recovered = recoveredJournal.snapshot().getFirst();
			assertEquals(DurableActionJournal.Phase.TERMINAL, recovered.phase(),
					"accepted-only crash recovery is durably terminalized");
			assertEquals("RECOVERY_UNCERTAIN", recovered.result().reasonCode(),
					"accepted-only crash recovery reports an explicit uncertain outcome");
			assertEquals(1, bridge.terminalResultsForVerification().pendingCount(),
					"uncertain recovery result is hydrated for coordinator replay");
			ProgramActionLedger actions = (ProgramActionLedger) readPrivateField(bridge, "programActions");
			assertThrowsDomain(() -> actions.accept(request), "ACTION_REPLAY");
			recoveredJournal.close();
		} catch (ReflectiveOperationException | IOException exception) {
			throw new AssertionError("accepted action crash recovery verification failed", exception);
		} finally {
			if (bridge != null) bridge.close();
			deleteIfExists(secretFile);
			deleteIfExists(journalFile);
		}
	}

	private static void verifyIdleDirectReplyRevisionPolicy(AgentRecord idle) {
		ActionProvenance provenance = new ActionProvenance(
				idle.profile().provider(), idle.profile().model(), idle.profile().reasoning(),
				idle.profile().serviceTier(), "conversation-only", 1L, "reply", 0L
		);
		JsonObject direct = new JsonObject();
		direct.addProperty("message", "Hello");
		direct.addProperty("audience", "direct");
		direct.addProperty("recipientId", "10000000-0000-4000-8000-000000000001");
		ServerActionRequest directReply = new ServerActionRequest(
				idle.agentId(), idle.goalRevision(), "idle-direct", ActionType.CHAT, direct, provenance
		);
		assertTrue(MultiplexedServerBridge.acceptsActionRevision(idle, directReply),
				"an idle conversation may issue only its same-revision private reply");
		assertTrue(MultiplexedServerBridge.isDetachedConversationReply(idle, directReply),
				"an idle private reply bypasses goal-action lifecycle transitions");
		JsonObject proximityArguments = direct.deepCopy();
		proximityArguments.addProperty("audience", "proximity");
		proximityArguments.remove("recipientId");
		ServerActionRequest proximityReply = new ServerActionRequest(
				idle.agentId(), idle.goalRevision(), "idle-proximity", ActionType.CHAT, proximityArguments, provenance
		);
		assertTrue(MultiplexedServerBridge.acceptsActionRevision(idle, proximityReply),
				"an idle proximity conversation may reply on the same channel");
		assertTrue(MultiplexedServerBridge.isDetachedConversationReply(idle, proximityReply),
				"an idle proximity reply bypasses goal-action lifecycle transitions");
		assertTrue(!MultiplexedServerBridge.acceptsActionRevision(idle, new ServerActionRequest(
				idle.agentId(), idle.goalRevision() + 1L, "idle-proximity-stale", ActionType.CHAT,
				proximityArguments, provenance
		)), "a proximity reply cannot cross a lifecycle revision");

		JsonObject publicArguments = direct.deepCopy();
		publicArguments.addProperty("audience", "public");
		ServerActionRequest publicReply = new ServerActionRequest(
				idle.agentId(), idle.goalRevision(), "idle-public", ActionType.CHAT, publicArguments, provenance
		);
		assertTrue(!MultiplexedServerBridge.acceptsActionRevision(idle, publicReply),
				"idle conversation cannot publish chat");
		assertTrue(!MultiplexedServerBridge.isDetachedConversationReply(idle, publicReply),
				"public chat cannot enter the detached reply executor");
		assertTrue(!MultiplexedServerBridge.acceptsActionRevision(idle, new ServerActionRequest(
				idle.agentId(), idle.goalRevision(), "idle-wait", ActionType.WAIT, new JsonObject(), provenance
		)), "idle conversation cannot execute physical actions");
		assertTrue(!MultiplexedServerBridge.acceptsActionRevision(idle, new ServerActionRequest(
				idle.agentId(), idle.goalRevision() + 1L, "idle-stale", ActionType.CHAT, direct, provenance
		)), "idle conversation cannot cross a lifecycle revision");
	}

	/**
	 * A terminal result may be queued when the coordinator disappears. The disconnect tick
	 * advances the lifecycle revision, but it must not fence a result belonging to the same
	 * logical goal before a replacement handshake can replay and acknowledge it.
	 */
	private static void verifyTerminalReplaySurvivesDisconnectRevision() {
		MultiplexedServerBridge bridge = null;
		Path secretFile = null;
		try {
			String secret = "0123456789abcdef0123456789abcdef";
			secretFile = Files.createTempFile("arena-agents-terminal-replay-reconnect-secret-", ".txt");
			Files.writeString(secretFile, secret);
			CodexAgentManager manager = uninitializedManager();
			AgentRecord active = manager.registry().create("gpt-5.6-sol", "high", Optional.of("Replay"), 2_500L);
			manager.registry().start(active.agentId(), "finish the queued action", 2_501L);
			AgentRecord started = manager.registry().require(active.agentId());
			ServerActionResult result = new ServerActionResult(
					started.agentId(), started.goalRevision(), "terminal-after-close", ActionType.WAIT,
					"trace-terminal-after-close", ServerActionState.SUCCEEDED, "DONE", "done", 1L,
					1_750_000_000_001L, true, true
			);

			bridge = new MultiplexedServerBridge(manager, 0, secretFile);
			bridge.start();
			TerminalResultLedger ledger = bridge.terminalResultsForVerification();
			BridgeEnvelopeCodec codec = new BridgeEnvelopeCodec();
			try (Socket first = new Socket(MultiplexedServerBridge.LOOPBACK_HOST, bridge.boundPortForVerification());
				 BufferedReader firstReader = new BufferedReader(new InputStreamReader(first.getInputStream(), StandardCharsets.UTF_8))) {
				first.setSoTimeout(2_000);
				authenticate(first, firstReader, codec, secret, "hello-terminal-replay-first");
				assertTrue(ledger.retain(result), "terminal result is retained before the coordinator closes");
				assertTrue(ledger.claim(result, session(bridge)), "first session owns the initial result delivery");
				bridge.tick();
				assertNoActionResult(
						first, firstReader, codec, result.actionId(),
						"claimed terminal result is not replayed twice on the first session"
				);
			}

			MultiplexedServerBridge activeBridge = bridge;
			awaitCondition(() -> !activeBridge.observationPublicationForVerification().hasActiveSession(),
					"closed coordinator session releases terminal result delivery ownership");
			// Drive the same registry transition the bridge's disconnect tick would perform. The
			// lifecycle-only fixture has no Minecraft server for the chat reporter, so publishing
			// it directly keeps this race check focused on bridge fencing and replay.
			manager.registry().disconnect(active.agentId(), 2_502L);
			bridge.tick();
			AgentRecord disconnected = manager.registry().require(active.agentId());
			assertEquals(AgentLifecycleState.DISCONNECTED, disconnected.state(),
					"disconnect reconciliation advances the lifecycle after the close");
			assertEquals(2L, disconnected.goalRevision(), "disconnect reconciliation advances the goal revision");
			assertTrue(disconnected.currentGoal().isPresent(), "disconnect reconciliation retains the logical goal");

			try (Socket replacement = new Socket(MultiplexedServerBridge.LOOPBACK_HOST, bridge.boundPortForVerification());
				 BufferedReader replacementReader = new BufferedReader(new InputStreamReader(replacement.getInputStream(), StandardCharsets.UTF_8))) {
				replacement.setSoTimeout(2_000);
				AuthenticationExchange replacementExchange = beginAuthentication(
						replacement, replacementReader, codec, secret, "hello-terminal-replay-replacement"
				);
				writeAuthenticatedHello(
						replacement, codec, secret, null, "hello-terminal-replay-replacement", replacementExchange
				);
				BridgeEnvelope replacementAck = codec.decode(replacementReader.readLine());
				assertEquals("hello_ack", replacementAck.type(), "replacement authenticates before terminal replay");
				assertEquals("verbose_control", codec.decode(replacementReader.readLine()).type(),
						"replacement consumes verbose control before terminal replay");
				BridgeEnvelope replay = codec.decode(replacementReader.readLine());
				assertEquals("action_result", replay.type(),
						"replacement handshake replays a terminal result after the disconnect revision");
				assertEquals("terminal-after-close", replay.payload().get("actionId").getAsString(),
						"replacement handshake replays the original action exactly once");
				assertEquals(MultiplexedServerBridge.actionResultReplayProof(
						secret, replacementExchange.clientNonce(), replacementExchange.serverNonce(),
						replacementAck.serverInstanceId(), result.agentId().toString(), result.goalRevision(), result.actionId()
				), replay.payload().get("replayProof").getAsString(),
						"retained result provenance is bound to the replacement authenticated session");
				assertEquals(2L, replacementAck.payload().getAsJsonArray("registry").get(0)
						.getAsJsonObject().get("goalRevision").getAsLong(),
						"replacement handshake reports the reconciled lifecycle revision");

				JsonObject acknowledgement = new JsonObject();
				acknowledgement.addProperty("goalRevision", result.goalRevision());
				acknowledgement.addProperty("actionId", result.actionId());
				writeEnvelope(replacement, codec, new BridgeEnvelope(
						2, replacementAck.serverInstanceId(), result.agentId().toString(), "action_result_ack",
						"ack-terminal-after-close", acknowledgement
				));
				awaitCondition(() -> {
					activeBridge.tick();
					return ledger.pendingCount() == 0;
				}, "replacement acknowledgement removes the replayed result");
				bridge.tick();
				while (replacementReader.ready()) {
					assertTrue(!"action_result".equals(codec.decode(replacementReader.readLine()).type()),
							"acknowledged terminal result is not replayed again");
				}
			}
		} catch (Exception exception) {
			throw new AssertionError("terminal replay disconnect revision verification failed", exception);
		} finally {
			if (bridge != null) bridge.close();
			if (secretFile != null) {
				try {
					Files.deleteIfExists(secretFile);
				} catch (java.io.IOException exception) {
					throw new AssertionError("could not remove terminal replay bridge secret", exception);
				}
			}
		}
	}

	private static void verifyReplacementOperation() {
		long now = 50_000L;
		dev.agaminggod.arenaagents.agent.AgentRegistry registry =
				dev.agaminggod.arenaagents.agent.AgentRegistry.createDefault(() -> { }, ignored -> { });
		AgentRecord idle = registry.create("codex", "gpt-5.6-sol", "high", "priority", Optional.of("ReplaceWire"),
				dev.agaminggod.arenaagents.agent.AgentGameMode.SURVIVAL, now);
		GoalSpec first = GoalSpec.create("Get stone", new GoalPredicate.InventoryContains("minecraft:stone", 1), 1L);
		GoalSpec replacement = GoalSpec.create("Get dirt", new GoalPredicate.InventoryContains("minecraft:dirt", 1), 2L);
		registry.start(idle.agentId(), first, now + 1L);
		AgentTransition replaced = registry.replace(idle.agentId(), replacement, now + 2L);
		assertEquals("replace", invokeGoalOperation(replaced), "replacement retains a distinct bridge operation");
		JsonObject payload = invokeGoalControlPayload(replaced, invokeGoalOperation(replaced));
		assertEquals("Get dirt", payload.get("goal").getAsString(), "replacement serializes the new goal");
		assertEquals(replacement.fingerprint(), payload.getAsJsonObject("goalSpec").get("fingerprint").getAsString(),
				"replacement serializes the immutable new goal spec");

		registry.queue(idle.agentId(), first, now + 3L);
		GoalEvidence evidence = new GoalEvidence(3L, "COMPLETION_VERIFIED",
				List.of(new GoalEvidence.Fact("inventory_contains", true, "minecraft:dirt x1", "minecraft:dirt x1")));
		registry.satisfyGoal(idle.agentId(), registry.require(idle.agentId()).goalRevision(), evidence, now + 4L);
		AgentTransition rejected = registry.rejectQueuedGoal(
				idle.agentId(), registry.require(idle.agentId()).queuedGoals().getFirst().goalId(),
				"Removed block", now + 5L);
		assertEquals("dequeue", invokeGoalOperation(rejected),
				"queued rejection publishes a distinct bridge operation before later promotion");
		JsonObject rejectedPayload = invokeGoalControlPayload(rejected, invokeGoalOperation(rejected));
		assertEquals("Get stone", rejectedPayload.get("goal").getAsString(),
				"queued rejection fences the exact removed head");
		assertEquals(first.fingerprint(), rejectedPayload.getAsJsonObject("goalSpec").get("fingerprint").getAsString(),
				"queued rejection serializes the immutable removed goal spec");

		registry.queue(idle.agentId(), first, now + 6L);
		AgentTransition promoted = registry.promoteSatisfied(idle.agentId(), now + 7L);
		assertEquals("start", invokeGoalOperation(promoted), "queued promotion remains a start operation");
	}
	private static void verifyEmptyCatalogRequestsLiveDiscovery() {
		MultiplexedServerBridge bridge = null;
		Path secretFile = null;
		try {
			assertEquals(50_000_000L, MultiplexedServerBridge.catalogRetryDelayNanos(1),
					"catalog retry starts at the bounded base interval");
			assertEquals(3_200_000_000L, MultiplexedServerBridge.catalogRetryDelayNanos(7),
					"catalog retry reaches its capped interval");
			assertEquals(3_200_000_000L, MultiplexedServerBridge.catalogRetryDelayNanos(10_000),
					"catalog retry remains capped across indefinite attempts");
			AtomicLong nanoTime = new AtomicLong(Long.MAX_VALUE - 25_000_000L);
			String secret = "0123456789abcdef0123456789abcdef";
			secretFile = Files.createTempFile("arena-agents-catalog-secret-", ".txt");
			Files.writeString(secretFile, secret);
			bridge = new MultiplexedServerBridge(uninitializedManager(), 0, secretFile, ServerSocket::new, nanoTime::get);
			bridge.start();
			BridgeEnvelopeCodec codec = new BridgeEnvelopeCodec();
			try (Socket socket = new Socket(MultiplexedServerBridge.LOOPBACK_HOST, bridge.boundPortForVerification());
				 BufferedReader reader = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8))) {
				socket.setSoTimeout(2_000);
				BridgeEnvelope acknowledgement = authenticate(
						socket, reader, codec, secret, "hello-empty-catalog"
				);
				assertEquals("hello_ack", acknowledgement.type(), "empty-catalog fixture authenticates the bridge");
				nanoTime.addAndGet(1_000_000L);
				for (int tick = 0; tick < 8; tick++) bridge.tick();
				assertTrue(!reader.ready(), "catalog discovery does not start eagerly before an empty snapshot");

				JsonObject catalog = new JsonObject();
				catalog.addProperty("refreshedAtEpochMs", 0L);
				catalog.add("models", new JsonArray());
				writeEnvelope(socket, codec, new BridgeEnvelope(
						2, acknowledgement.serverInstanceId(), "server", "catalog_snapshot", "empty-catalog", catalog
				));

				BridgeEnvelope request = null;
				long deadline = System.currentTimeMillis() + 2_000L;
				while (request == null && System.currentTimeMillis() < deadline) {
					bridge.tick();
					if (reader.ready()) request = codec.decode(reader.readLine());
					else Thread.sleep(10L);
				}
				assertTrue(request != null && "catalog_request".equals(request.type()),
						"an empty bootstrap catalog requests live provider discovery asynchronously");
				assertTrue(!bridge.catalogModels().isEmpty(),
						"fallback model choices remain visible while live discovery retries");
				nanoTime.addAndGet(49_000_000L);
				for (int tick = 0; tick < 8; tick++) bridge.tick();
				assertTrue(!reader.ready(), "catalog retry remains pending immediately before a wrapping deadline");
				nanoTime.addAndGet(1_000_000L);
				BridgeEnvelope wrappedRetry = null;
				deadline = System.currentTimeMillis() + 2_000L;
				while (wrappedRetry == null && System.currentTimeMillis() < deadline) {
					bridge.tick();
					if (reader.ready()) wrappedRetry = codec.decode(reader.readLine());
					else Thread.sleep(1L);
				}
				assertTrue(wrappedRetry != null && "catalog_request".equals(wrappedRetry.type()),
						"catalog retry fires when nanoTime crosses Long.MAX_VALUE into Long.MIN_VALUE");

				for (int attempt = 3; attempt <= 6; attempt++) {
					writeEnvelope(socket, codec, new BridgeEnvelope(
							2, acknowledgement.serverInstanceId(), "server", "catalog_snapshot", "empty-catalog-" + attempt, catalog
					));
					awaitServerTaskCount(bridge, 1,
							"empty catalog attempt " + attempt + " reaches the server-task handoff");
					bridge.tick();
					assertTrue(!catalogDiscoveryPending(bridge),
							"empty catalog attempt " + attempt + " is applied before retry time advances");
					nanoTime.addAndGet(60_000_000_000L);
					if (attempt == 4) {
						setQueuedCount(bridge, "server", MultiplexedServerBridge.AGENT_QUEUE_CAP);
						assertDoesNotThrow(bridge::tick,
								"catalog retry backpressure remains best effort outside Minecraft tick control");
						setQueuedCount(bridge, "server", 0);
						long noSpinDeadline = System.nanoTime() + 100_000_000L;
						while (System.nanoTime() < noSpinDeadline) {
							bridge.tick();
							Thread.sleep(1L);
						}
						assertTrue(!reader.ready(), "failed catalog publication retains one future retry instead of spinning");
						nanoTime.addAndGet(60_000_000_000L);
					}
					BridgeEnvelope retry = null;
					deadline = System.currentTimeMillis() + 2_000L;
					while (retry == null && System.currentTimeMillis() < deadline) {
						bridge.tick();
						if (reader.ready()) retry = codec.decode(reader.readLine());
						else Thread.sleep(1L);
					}
					assertTrue(retry != null && "catalog_request".equals(retry.type()),
							"empty catalog discovery keeps probing after attempt " + attempt);
					for (int tick = 0; tick < 8; tick++) bridge.tick();
					assertTrue(!reader.ready(), "one exact catalog request owns each retry deadline");
				}

				JsonObject liveModel = new JsonObject();
				liveModel.addProperty("provider", "codex");
				liveModel.addProperty("id", "codex:review-recovered");
				liveModel.addProperty("model", "gpt-5.6-sol");
				liveModel.addProperty("displayName", "Recovered Sol");
				JsonArray efforts = new JsonArray();
				efforts.add("high");
				liveModel.add("reasoningEfforts", efforts);
				JsonArray tiers = new JsonArray();
				tiers.add("priority");
				liveModel.add("serviceTiers", tiers);
				JsonObject recoveredCatalog = new JsonObject();
				recoveredCatalog.addProperty("refreshedAtEpochMs", 1L);
				JsonArray liveModels = new JsonArray();
				liveModels.add(liveModel);
				recoveredCatalog.add("models", liveModels);
				writeEnvelope(socket, codec, new BridgeEnvelope(
						2, acknowledgement.serverInstanceId(), "server", "catalog_snapshot", "recovered-catalog", recoveredCatalog
				));
				deadline = System.currentTimeMillis() + 2_000L;
				while (bridge.catalogModels().stream().noneMatch(model -> "codex:review-recovered".equals(model.model()))
						&& System.currentTimeMillis() < deadline) {
					bridge.tick();
					Thread.sleep(1L);
				}
				assertTrue(bridge.catalogModels().stream().anyMatch(model -> "codex:review-recovered".equals(model.model())),
						"eventual provider recovery promotes the live model catalog automatically");
				nanoTime.addAndGet(60_000_000_000L);
				for (int tick = 0; tick < 8; tick++) bridge.tick();
				assertTrue(!reader.ready(), "live catalog promotion cancels the fallback retry timer");
			}
		} catch (Exception exception) {
			throw new AssertionError("empty catalog discovery verification failed", exception);
		} finally {
			if (bridge != null) bridge.close();
			if (secretFile != null) {
				try {
					Files.deleteIfExists(secretFile);
				} catch (java.io.IOException exception) {
					throw new AssertionError("could not remove empty catalog bridge secret", exception);
				}
			}
		}
	}

	private static void verifyAlternativeItemProposalIdentifiers(MultiplexedServerBridge bridge) {
		Set<String> candidates = Set.of("minecraft:oak_log", "minecraft:birch_log");
		GoalPredicate alternatives = new GoalPredicate.InventoryContainsAny(List.copyOf(candidates), 12);
		assertDoesNotThrow(() -> validateProposalIdentifiers(bridge, alternatives, candidates),
				"mixed-item proposals accept every offered live item");
		assertDoesNotThrow(() -> validateProposalIdentifiers(bridge,
				new GoalPredicate.AllOf(List.of(alternatives, new GoalPredicate.SurviveDuration(20))), candidates),
				"nested mixed-item proposals retain identifier validation");
		assertThrowsCode(() -> validateProposalIdentifiers(bridge,
				new GoalPredicate.InventoryContainsAny(List.of("minecraft:oak_log", "minecraft:birch_log"), 12),
				Set.of("minecraft:oak_log")), "GOAL_IDENTIFIER_NOT_CANDIDATE");
		assertThrowsCode(() -> validateProposalIdentifiers(bridge,
				new GoalPredicate.AnyOf(List.of(new GoalPredicate.SurviveDuration(20),
						new GoalPredicate.InventoryContainsAny(List.of("minecraft:oak_log", "example:missing_log"), 12))),
				Set.of("minecraft:oak_log", "example:missing_log")), "UNKNOWN_GOAL_IDENTIFIER");
	}

	private static void validateProposalIdentifiers(
			MultiplexedServerBridge bridge, GoalPredicate predicate, Set<String> candidates
	) {
		try {
			Method method = MultiplexedServerBridge.class.getDeclaredMethod(
					"validateProposalIdentifiers", GoalPredicate.class, Set.class);
			method.setAccessible(true);
			method.invoke(bridge, predicate, candidates);
		} catch (java.lang.reflect.InvocationTargetException exception) {
			if (exception.getCause() instanceof RuntimeException cause) throw cause;
			throw new AssertionError("proposal identifier validation failed", exception.getCause());
		} catch (ReflectiveOperationException exception) {
			throw new AssertionError("could not invoke proposal identifier validation", exception);
		}
	}

	private static void verifyGoalSpecProposalLifecycle() {
		MultiplexedServerBridge bridge = null;
		Path secretFile = null;
		try {
			String secret = "0123456789abcdef0123456789abcdef";
			secretFile = Files.createTempFile("arena-agents-goal-spec-secret-", ".txt");
			Files.writeString(secretFile, secret);
			CodexAgentManager manager = uninitializedManager();
			AgentRecord idle = manager.registry().create("gpt-5.6-sol", "high", Optional.of("Translator"), 1_000L);
			UUID requestId = UUID.fromString("00000000-0000-0000-0000-000000000301");
			PendingGoalDraft draft = new PendingGoalDraft(
					requestId, idle.agentId(), UUID.fromString("00000000-0000-0000-0000-000000000302"),
					"Get a good pickaxe", List.of("minecraft:diamond_pickaxe", "minecraft:iron_pickaxe"),
					Optional.empty(), DraftIntent.CONFIRM_TRANSLATION, 1_001L, idle.goalRevision(), Optional.empty()
			);
			assertEquals(
					"Proposed goal for \"Get a good pickaxe\": Goal set: obtain minecraft:iron_pickaxe. Confirm or cancel draft "
							+ requestId + ".",
					MultiplexedServerBridge.goalProposalMessage(draft, "Goal set:\n obtain minecraft:iron_pickaxe"),
					"player goal proposal is a readable sentence without raw predicate JSON"
			);
			manager.stageGoalDraft(draft);
			bridge = new MultiplexedServerBridge(manager, 0, secretFile);
			verifyAlternativeItemProposalIdentifiers(bridge);
			bridge.start();
			BridgeEnvelopeCodec codec = new BridgeEnvelopeCodec();
			try (Socket socket = new Socket(MultiplexedServerBridge.LOOPBACK_HOST, bridge.boundPortForVerification());
				 BufferedReader reader = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8))) {
				socket.setSoTimeout(2_000);
				BridgeEnvelope hello = authenticate(socket, reader, codec, secret, "hello-goal-spec");
				BridgeEnvelope replay = codec.decode(reader.readLine());
				assertEquals("goal_spec_request", replay.type(), "pending goal translation is replayed during authentication");
				assertEquals(requestId.toString(), replay.payload().get("requestId").getAsString(), "goal translation replay retains draft identity");
				assertEquals(2, replay.payload().getAsJsonArray("candidateIds").size(), "goal translation replay retains bounded candidate IDs");

				JsonObject malformedId = goalSpecProposal(requestId, "minecraft:iron_pickaxe");
				malformedId.addProperty("requestId", "not-a-uuid");
				writeEnvelope(socket, codec, new BridgeEnvelope(2, hello.serverInstanceId(), idle.agentId().toString(),
						"goal_spec_proposal", "proposal-invalid-id", malformedId));
				BridgeEnvelope malformedRejected = pollBridgeResponseOfType(
						bridge, socket, reader, codec, "goal_spec_result", null);
				assertEquals("rejected", malformedRejected.payload().get("status").getAsString(),
						"malformed proposal identity receives an explicit rejection");
				assertEquals("INVALID_GOAL_SPEC_REQUEST_ID", malformedRejected.payload().get("reasonCode").getAsString(),
						"malformed proposal identity reports its stable reason code");

				writeEnvelope(socket, codec, new BridgeEnvelope(2, hello.serverInstanceId(), idle.agentId().toString(),
						"goal_spec_proposal", "proposal-million", goalSpecProposal(
								requestId, new GoalPredicate.InventoryContains("minecraft:iron_pickaxe", 1_000_000))));
				BridgeEnvelope millionRejected = pollBridgeResponseOfType(
						bridge, socket, reader, codec, "goal_spec_result", null);
				assertEquals("rejected", millionRejected.payload().get("status").getAsString(),
						"translated million-item inventory goal is rejected");
				assertEquals("INVALID_GOAL_PREDICATE", millionRejected.payload().get("reasonCode").getAsString(),
						"translated inventory overflow reports a correctable predicate error");
				assertTrue(manager.goalDraft(requestId).orElseThrow().proposedPredicate().isEmpty(),
						"an impossible translated count is never staged");

				GoalPredicate compoundOverflow = new GoalPredicate.AllOf(List.of(
						new GoalPredicate.InventoryContains("minecraft:iron_pickaxe", 20),
						new GoalPredicate.InventoryContains("minecraft:iron_pickaxe", 18)
				));
				writeEnvelope(socket, codec, new BridgeEnvelope(2, hello.serverInstanceId(), idle.agentId().toString(),
						"goal_spec_proposal", "proposal-compound-overflow", goalSpecProposal(requestId, compoundOverflow)));
				BridgeEnvelope compoundRejected = pollBridgeResponseOfType(
						bridge, socket, reader, codec, "goal_spec_result", null);
				assertEquals("rejected", compoundRejected.payload().get("status").getAsString(),
						"translated duplicate inventory requirements are summed before validation");
				assertEquals("INVALID_GOAL_PREDICATE", compoundRejected.payload().get("reasonCode").getAsString(),
						"compound translated capacity overflow reports a correctable predicate error");
				assertTrue(manager.goalDraft(requestId).orElseThrow().proposedPredicate().isEmpty(),
						"an impossible compound inventory predicate is never staged");

				JsonObject proposal = goalSpecProposal(requestId, "minecraft:iron_pickaxe");
				writeEnvelope(socket, codec, new BridgeEnvelope(2, hello.serverInstanceId(), idle.agentId().toString(),
						"goal_spec_proposal", "proposal-1", proposal));
				BridgeEnvelope accepted = pollBridgeResponseOfType(
						bridge, socket, reader, codec, "goal_spec_result", null);
				assertEquals("goal_spec_result", accepted.type(), "valid proposal receives an explicit acknowledgement");
				assertEquals("accepted", accepted.payload().get("status").getAsString(), "valid proposal is staged");
				assertEquals(new GoalPredicate.InventoryContains("minecraft:iron_pickaxe", 1),
						manager.goalDraft(requestId).orElseThrow().proposedPredicate().orElseThrow(),
						"proposal atomically updates only the matching draft");
				assertEquals(AgentLifecycleState.IDLE, manager.registry().require(idle.agentId()).state(),
						"proposal cannot start or replace the agent goal");

				writeEnvelope(socket, codec, new BridgeEnvelope(2, hello.serverInstanceId(), idle.agentId().toString(),
						"goal_spec_proposal", "proposal-2", proposal));
				assertEquals("accepted", pollBridgeResponseOfType(
						bridge, socket, reader, codec, "goal_spec_result", null).payload().get("status").getAsString(),
						"identical proposal replay is idempotent");

				writeEnvelope(socket, codec, new BridgeEnvelope(2, hello.serverInstanceId(), idle.agentId().toString(),
						"goal_spec_proposal", "proposal-3", goalSpecProposal(requestId, "minecraft:diamond_pickaxe")));
				BridgeEnvelope rejected = pollBridgeResponseOfType(
						bridge, socket, reader, codec, "goal_spec_result", null);
				assertEquals("rejected", rejected.payload().get("status").getAsString(), "changed proposal replay is rejected");
				assertEquals("GOAL_DRAFT_PROPOSAL_CONFLICT", rejected.payload().get("reasonCode").getAsString(),
						"changed proposal replay reports the stable conflict code");

				UUID nonTranslationId = UUID.fromString("00000000-0000-0000-0000-000000000303");
				manager.stageGoalDraft(new PendingGoalDraft(
						nonTranslationId, idle.agentId(), UUID.fromString("00000000-0000-0000-0000-000000000304"),
						"Get an iron pickaxe", List.of("minecraft:iron_pickaxe"), Optional.empty(),
						DraftIntent.START, 1_002L, idle.goalRevision(), Optional.empty()
				));
				writeEnvelope(socket, codec, new BridgeEnvelope(2, hello.serverInstanceId(), idle.agentId().toString(),
						"goal_spec_proposal", "proposal-wrong-intent", goalSpecProposal(nonTranslationId, "minecraft:iron_pickaxe")));
				BridgeEnvelope intentRejected = pollBridgeResponseOfType(
						bridge, socket, reader, codec, "goal_spec_result", null);
				assertEquals("GOAL_DRAFT_INTENT_MISMATCH", intentRejected.payload().get("reasonCode").getAsString(),
						"coordinator proposals cannot populate non-translation drafts");

				UUID countedKillId = UUID.fromString("00000000-0000-0000-0000-000000000305");
				UUID countedKillRequester = UUID.fromString("00000000-0000-0000-0000-000000000306");
				var countedKillConstraint = new GoalCompiler().translationConstraintFor(
						"Kill 3 good zombies", net.minecraft.core.RegistryAccess.EMPTY);
				manager.stageGoalDraft(new PendingGoalDraft(
						countedKillId, idle.agentId(), countedKillRequester, "Kill 3 good zombies",
						dev.agaminggod.arenaagents.agent.goal.GoalPredicate.DEFAULT_DIMENSION,
						List.of("minecraft:zombie"), countedKillConstraint, Optional.empty(),
						DraftIntent.CONFIRM_TRANSLATION, 1_003L, idle.goalRevision(), Optional.empty()
				));
				writeEnvelope(socket, codec, new BridgeEnvelope(2, hello.serverInstanceId(), idle.agentId().toString(),
						"goal_spec_proposal", "proposal-kill-operator-bypass",
						goalSpecProposal(countedKillId, new GoalPredicate.OperatorConfirmed())));
				BridgeEnvelope operatorBypass = pollBridgeResponseOfType(
						bridge, socket, reader, codec, "goal_spec_result", null);
				assertEquals("GOAL_TRANSLATION_CONSTRAINT_MISMATCH",
						operatorBypass.payload().get("reasonCode").getAsString(),
						"operator confirmation cannot bypass a server-authored kill count");
				writeEnvelope(socket, codec, new BridgeEnvelope(2, hello.serverInstanceId(), idle.agentId().toString(),
						"goal_spec_proposal", "proposal-kill-undercount",
						goalSpecProposal(countedKillId,
								new GoalPredicate.EntityKilledByAgent("minecraft:zombie", true))));
				BridgeEnvelope undercounted = pollBridgeResponseOfType(
						bridge, socket, reader, codec, "goal_spec_result", null);
				assertEquals("GOAL_TRANSLATION_CONSTRAINT_MISMATCH",
						undercounted.payload().get("reasonCode").getAsString(),
						"one translated kill cannot satisfy a server-authored count of three");

				UUID confirmationBypassId = UUID.fromString("00000000-0000-0000-0000-000000000307");
				manager.stageGoalDraft(new PendingGoalDraft(
						confirmationBypassId, idle.agentId(), countedKillRequester, "Kill 3 good zombies",
						dev.agaminggod.arenaagents.agent.goal.GoalPredicate.DEFAULT_DIMENSION,
						List.of("minecraft:zombie"), countedKillConstraint,
						Optional.of(new GoalPredicate.OperatorConfirmed()), DraftIntent.CONFIRM_TRANSLATION,
						1_004L, idle.goalRevision(), Optional.empty()
				));
				assertThrowsDomain(
						() -> manager.resolveGoalDraft(
								confirmationBypassId, countedKillRequester, false, GoalDraftChoice.CONFIRM),
						"GOAL_TRANSLATION_CONSTRAINT_MISMATCH");

				UUID countedItemId = UUID.fromString("00000000-0000-0000-0000-000000000308");
				UUID countedItemRequester = UUID.fromString("00000000-0000-0000-0000-000000000309");
				var countedItemConstraint = new GoalCompiler().translationConstraintFor(
						"Get 3 good iron pickaxes", net.minecraft.core.RegistryAccess.EMPTY);
				manager.stageGoalDraft(new PendingGoalDraft(
						countedItemId, idle.agentId(), countedItemRequester, "Get 3 good iron pickaxes",
						dev.agaminggod.arenaagents.agent.goal.GoalPredicate.DEFAULT_DIMENSION,
						List.of("minecraft:iron_pickaxe"), countedItemConstraint, Optional.empty(),
						DraftIntent.CONFIRM_TRANSLATION, 1_005L, idle.goalRevision(), Optional.empty()
				));
				writeEnvelope(socket, codec, new BridgeEnvelope(2, hello.serverInstanceId(), idle.agentId().toString(),
						"goal_spec_proposal", "proposal-item-undercount",
						goalSpecProposal(countedItemId,
								new GoalPredicate.InventoryContains("minecraft:iron_pickaxe", 1))));
				BridgeEnvelope itemUndercount = pollBridgeResponseOfType(
						bridge, socket, reader, codec, "goal_spec_result", null);
				assertEquals("GOAL_TRANSLATION_CONSTRAINT_MISMATCH",
						itemUndercount.payload().get("reasonCode").getAsString(),
						"one translated item cannot satisfy a server-authored count of three");
				writeEnvelope(socket, codec, new BridgeEnvelope(2, hello.serverInstanceId(), idle.agentId().toString(),
						"goal_spec_proposal", "proposal-item-operator-bypass",
						goalSpecProposal(countedItemId, new GoalPredicate.OperatorConfirmed())));
				BridgeEnvelope itemOperatorBypass = pollBridgeResponseOfType(
						bridge, socket, reader, codec, "goal_spec_result", null);
				assertEquals("GOAL_TRANSLATION_CONSTRAINT_MISMATCH",
						itemOperatorBypass.payload().get("reasonCode").getAsString(),
						"operator confirmation cannot bypass a server-authored item count");
				writeEnvelope(socket, codec, new BridgeEnvelope(2, hello.serverInstanceId(), idle.agentId().toString(),
						"goal_spec_proposal", "proposal-item-anyof-bypass",
						goalSpecProposal(countedItemId, new GoalPredicate.AnyOf(List.of(
								new GoalPredicate.InventoryContains("minecraft:iron_pickaxe", 3),
								new GoalPredicate.OperatorConfirmed())))));
				BridgeEnvelope itemAnyOfBypass = pollBridgeResponseOfType(
						bridge, socket, reader, codec, "goal_spec_result", null);
				assertEquals("GOAL_TRANSLATION_CONSTRAINT_MISMATCH",
						itemAnyOfBypass.payload().get("reasonCode").getAsString(),
						"an operator-confirmed any-of branch cannot bypass a server-authored item count");
				writeEnvelope(socket, codec, new BridgeEnvelope(2, hello.serverInstanceId(), idle.agentId().toString(),
						"goal_spec_proposal", "proposal-item-valid",
						goalSpecProposal(countedItemId,
								new GoalPredicate.InventoryContains("minecraft:iron_pickaxe", 3))));
				assertEquals("accepted", pollBridgeResponseOfType(
						bridge, socket, reader, codec, "goal_spec_result", null)
						.payload().get("status").getAsString(),
						"the bridge accepts a translated item predicate that preserves the requested count");

				UUID legacyItemBypassId = UUID.fromString("00000000-0000-0000-0000-000000000310");
				manager.stageGoalDraft(new PendingGoalDraft(
						legacyItemBypassId, idle.agentId(), countedItemRequester, "Get 3 good iron pickaxes",
						dev.agaminggod.arenaagents.agent.goal.GoalPredicate.DEFAULT_DIMENSION,
						List.of("minecraft:iron_pickaxe"), dev.agaminggod.arenaagents.server.goal.GoalTranslationConstraint.none(),
						Optional.of(new GoalPredicate.InventoryContains("minecraft:iron_pickaxe", 1)),
						DraftIntent.CONFIRM_TRANSLATION, 1_006L, idle.goalRevision(), Optional.empty()
				));
				assertThrowsDomain(
						() -> manager.resolveGoalDraft(
								legacyItemBypassId, countedItemRequester, false, GoalDraftChoice.CONFIRM),
						"GOAL_TRANSLATION_CONSTRAINT_MISMATCH");

				String reportedRequest = "Go beat the game and kill the Ender Dragon";
				GoalPredicate dragonKill = new GoalPredicate.EntityKilledByAgent("minecraft:ender_dragon", true);
				GoalPredicate redundantDragonConfirmation = new GoalPredicate.AllOf(List.of(
						new GoalPredicate.OperatorConfirmed(), dragonKill));
				var dragonConstraint = new GoalCompiler().translationConstraintFor(
						reportedRequest, net.minecraft.core.RegistryAccess.EMPTY);
				UUID invalidManagerId = UUID.fromString("00000000-0000-0000-0000-000000000311");
				manager.stageGoalDraft(managerTranslationDraft(
						invalidManagerId, idle.agentId(),
						UUID.fromString("00000000-0000-0000-0000-000000000312"),
						reportedRequest, dragonConstraint, DraftIntent.TRANSLATE_START,
						manager.registry().require(idle.agentId()), 1_007L));
				writeEnvelope(socket, codec, new BridgeEnvelope(2, hello.serverInstanceId(), idle.agentId().toString(),
						"goal_spec_proposal", "proposal-manager-bypass",
						goalSpecProposal(invalidManagerId, new GoalPredicate.OperatorConfirmed())));
				BridgeEnvelope managerBypass = pollBridgeResponseOfType(
						bridge, socket, reader, codec, "goal_spec_result", null);
				assertEquals("GOAL_TRANSLATION_CONSTRAINT_MISMATCH",
						managerBypass.payload().get("reasonCode").getAsString(),
						"a Manager request cannot auto-activate an operator-confirmed placeholder");
				assertEquals(AgentLifecycleState.IDLE, manager.registry().require(idle.agentId()).state(),
						"an invalid Manager translation activates nothing");

				UUID managerStartId = UUID.fromString("00000000-0000-0000-0000-000000000313");
				manager.stageGoalDraft(managerTranslationDraft(
						managerStartId, idle.agentId(),
						UUID.fromString("00000000-0000-0000-0000-000000000314"),
						reportedRequest, dragonConstraint, DraftIntent.TRANSLATE_START,
						manager.registry().require(idle.agentId()), 1_008L));
				writeEnvelope(socket, codec, new BridgeEnvelope(2, hello.serverInstanceId(), idle.agentId().toString(),
						"goal_spec_proposal", "proposal-manager-start",
						goalSpecProposal(managerStartId, redundantDragonConfirmation)));
				BridgeEnvelope managerStarted = pollBridgeResponseOfType(
						bridge, socket, reader, codec, "goal_spec_result", null);
				assertEquals("accepted", managerStarted.payload().get("status").getAsString(),
						"a verified Manager translation is accepted");
				assertEquals("PROPOSAL_ACTIVATED", managerStarted.payload().get("reasonCode").getAsString(),
						"a verified Manager start activates without a second confirmation");
				AgentRecord started = manager.registry().require(idle.agentId());
				assertEquals(AgentLifecycleState.STARTING, started.state(),
						"the verified Manager proposal starts the agent");
				assertEquals(dragonKill, started.currentGoal().orElseThrow().spec().completion(),
						"the bridge removes redundant manual confirmation from the objective terminal result");
				assertTrue(manager.goalDraft(managerStartId).isEmpty(),
						"activation consumes the durable Manager draft");

				writeEnvelope(socket, codec, new BridgeEnvelope(2, hello.serverInstanceId(), idle.agentId().toString(),
						"goal_spec_proposal", "proposal-manager-start-replay",
						goalSpecProposal(managerStartId, redundantDragonConfirmation)));
				BridgeEnvelope startReplay = pollBridgeResponseOfType(
						bridge, socket, reader, codec, "goal_spec_result", null);
				assertEquals("PROPOSAL_ALREADY_ACTIVATED", startReplay.payload().get("reasonCode").getAsString(),
						"an identical Manager start replay is acknowledged idempotently");
				assertEquals(started.goalRevision(), manager.registry().require(idle.agentId()).goalRevision(),
						"an identical start replay cannot activate a second goal revision");
				writeEnvelope(socket, codec, new BridgeEnvelope(2, hello.serverInstanceId(), idle.agentId().toString(),
						"goal_spec_proposal", "proposal-manager-start-conflict", goalSpecProposal(
								managerStartId, new GoalPredicate.EntityKilledByAgent("minecraft:zombie", true))));
				BridgeEnvelope startConflict = pollBridgeResponseOfType(
						bridge, socket, reader, codec, "goal_spec_result", null);
				assertEquals("GOAL_DRAFT_PROPOSAL_CONFLICT",
						startConflict.payload().get("reasonCode").getAsString(),
						"a changed replay cannot replace an already activated Manager proposal");
				assertEquals(started.goalRevision(), manager.registry().require(idle.agentId()).goalRevision(),
						"a changed replay cannot activate a second goal revision");

				UUID managerQueueId = UUID.fromString("00000000-0000-0000-0000-000000000315");
				manager.stageGoalDraft(managerTranslationDraft(
						managerQueueId, idle.agentId(),
						UUID.fromString("00000000-0000-4000-8000-000000000316"),
						reportedRequest, dragonConstraint, DraftIntent.TRANSLATE_QUEUE,
						manager.registry().require(idle.agentId()), 1_009L));
				writeEnvelope(socket, codec, new BridgeEnvelope(2, hello.serverInstanceId(), idle.agentId().toString(),
						"goal_spec_proposal", "proposal-manager-queue", goalSpecProposal(managerQueueId, dragonKill)));
				BridgeEnvelope managerQueued = pollBridgeResponseOfType(
						bridge, socket, reader, codec, "goal_spec_result", null);
				assertEquals("PROPOSAL_ACTIVATED", managerQueued.payload().get("reasonCode").getAsString(),
						"a verified Manager queue proposal is applied automatically");
				assertEquals(1, manager.registry().require(idle.agentId()).queuedGoals().size(),
						"the verified queue proposal appends one goal");
				writeEnvelope(socket, codec, new BridgeEnvelope(2, hello.serverInstanceId(), idle.agentId().toString(),
						"goal_spec_proposal", "proposal-manager-queue-replay", goalSpecProposal(managerQueueId, dragonKill)));
				BridgeEnvelope queueReplay = pollBridgeResponseOfType(
						bridge, socket, reader, codec, "goal_spec_result", null);
				assertEquals("PROPOSAL_ALREADY_ACTIVATED", queueReplay.payload().get("reasonCode").getAsString(),
						"an identical queue replay is acknowledged idempotently");
				assertEquals(1, manager.registry().require(idle.agentId()).queuedGoals().size(),
						"an identical queue replay cannot append the goal twice");

				UUID staleManagerId = UUID.fromString("00000000-0000-0000-0000-000000000317");
				manager.stageGoalDraft(managerTranslationDraft(
						staleManagerId, idle.agentId(),
						UUID.fromString("00000000-0000-4000-8000-000000000318"),
						reportedRequest, dragonConstraint, DraftIntent.TRANSLATE_QUEUE,
						manager.registry().require(idle.agentId()), 1_010L));
				manager.registry().stop(idle.agentId(), 1_011L);
				writeEnvelope(socket, codec, new BridgeEnvelope(2, hello.serverInstanceId(), idle.agentId().toString(),
						"goal_spec_proposal", "proposal-manager-stale", goalSpecProposal(staleManagerId, dragonKill)));
				BridgeEnvelope staleManager = pollBridgeResponseOfType(
						bridge, socket, reader, codec, "goal_spec_result", null);
				assertEquals("STALE_GOAL_DRAFT", staleManager.payload().get("reasonCode").getAsString(),
						"a proposal fenced to an old Manager goal revision is rejected");
				assertEquals(1, manager.registry().require(idle.agentId()).queuedGoals().size(),
						"a stale Manager proposal activates nothing");
			}
		} catch (Exception exception) {
			throw new AssertionError("goal specification proposal lifecycle failed", exception);
		} finally {
			if (bridge != null) bridge.close();
			if (secretFile != null) try { Files.deleteIfExists(secretFile); } catch (java.io.IOException exception) {
				throw new AssertionError("could not remove goal spec bridge secret", exception);
			}
		}
	}

	private static PendingGoalDraft managerTranslationDraft(
			UUID requestId,
			dev.agaminggod.arenaagents.agent.AgentId agentId,
			UUID requesterId,
			String request,
			dev.agaminggod.arenaagents.server.goal.GoalTranslationConstraint constraint,
			DraftIntent intent,
			AgentRecord record,
			long createdAtTick
	) {
		return new PendingGoalDraft(
				requestId, agentId, requesterId, request,
				dev.agaminggod.arenaagents.agent.goal.GoalPredicate.DEFAULT_DIMENSION,
				List.of("minecraft:ender_dragon"), constraint, Optional.empty(), intent,
				createdAtTick, record.goalRevision(), PendingGoalDraft.expectedGoalIdFor(record)
		);
	}

	private static JsonObject goalSpecProposal(UUID requestId, String itemId) {
		return goalSpecProposal(requestId, new GoalPredicate.InventoryContains(itemId, 1));
	}

	private static JsonObject goalSpecProposal(UUID requestId, GoalPredicate predicate) {
		JsonObject payload = new JsonObject();
		payload.addProperty("requestId", requestId.toString());
		payload.addProperty("summary", "Obtain the selected pickaxe.");
		payload.add("predicate", new GoalSpecWireCodec().encodePredicate(predicate));
		return payload;
	}

	private static void verifyStaleGoalDraftIsPrunedBeforeHandshake() {
		MultiplexedServerBridge bridge = null;
		Path secretFile = null;
		try {
			String secret = "0123456789abcdef0123456789abcdef";
			secretFile = Files.createTempFile("arena-agents-stale-goal-draft-secret-", ".txt");
			Files.writeString(secretFile, secret);
			CodexAgentManager manager = uninitializedManager();
			AgentRecord idle = manager.registry().create("gpt-5.6-sol", "high", Optional.of("StaleDraft"), 1_000L);
			UUID draftId = UUID.fromString("00000000-0000-0000-0000-000000000311");
			manager.stageGoalDraft(new PendingGoalDraft(
					draftId, idle.agentId(), UUID.fromString("00000000-0000-0000-0000-000000000312"),
					"Obtain an iron pickaxe", List.of("minecraft:iron_pickaxe"), Optional.empty(),
					DraftIntent.CONFIRM_TRANSLATION, 1_001L, idle.goalRevision(), Optional.empty()
			));
			manager.registry().start(idle.agentId(), "a replacement goal", 1_002L);
			bridge = new MultiplexedServerBridge(manager, 0, secretFile);
			bridge.start();
			BridgeEnvelopeCodec codec = new BridgeEnvelopeCodec();
			try (Socket socket = new Socket(MultiplexedServerBridge.LOOPBACK_HOST, bridge.boundPortForVerification());
				 BufferedReader reader = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8))) {
				socket.setSoTimeout(2_000);
				authenticate(socket, reader, codec, secret, "hello-stale-goal-draft");
				Thread.sleep(50L);
				assertTrue(!reader.ready(), "stale goal draft is not replayed during authentication");
			}
			bridge.tick();
			assertTrue(manager.goalDraft(draftId).isEmpty(), "stale goal draft cleanup runs on the server tick");
		} catch (Exception exception) {
			throw new AssertionError("stale goal draft handshake verification failed", exception);
		} finally {
			if (bridge != null) bridge.close();
			if (secretFile != null) try { Files.deleteIfExists(secretFile); } catch (java.io.IOException exception) {
				throw new AssertionError("could not remove stale goal draft secret", exception);
			}
		}
	}

	private static void verifyGoalDraftCreatedDuringHandshakeIsReplayed() {
		MultiplexedServerBridge bridge = null;
		Path secretFile = null;
		CountDownLatch releaseSnapshot = new CountDownLatch(1);
		try {
			String secret = "0123456789abcdef0123456789abcdef";
			secretFile = Files.createTempFile("arena-agents-goal-draft-handshake-", ".txt");
			Files.writeString(secretFile, secret);
			CodexAgentManager manager = uninitializedManager();
			AgentRecord idle = manager.registry().create("gpt-5.6-sol", "high", Optional.of("DraftRace"), 1_100L);
			bridge = new MultiplexedServerBridge(manager, 0, secretFile);
			CountDownLatch snapshotCaptured = new CountDownLatch(1);
			bridge.setHandshakeSnapshotHookForVerification(() -> {
				snapshotCaptured.countDown();
				awaitLatch(releaseSnapshot, "goal draft handshake snapshot released");
			});
			bridge.start();
			BridgeEnvelopeCodec codec = new BridgeEnvelopeCodec();
			try (Socket socket = new Socket(MultiplexedServerBridge.LOOPBACK_HOST, bridge.boundPortForVerification());
				 BufferedReader reader = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8))) {
				socket.setSoTimeout(2_000);
				String messageId = "hello-goal-draft-handshake";
				AuthenticationExchange exchange = beginAuthentication(socket, reader, codec, secret, messageId);
				writeAuthenticatedHello(socket, codec, secret, null, messageId, exchange);
				awaitLatch(snapshotCaptured, "handshake captures state before the goal draft is staged");
				PendingGoalDraft draft = new PendingGoalDraft(
						UUID.fromString("00000000-0000-0000-0000-000000000321"), idle.agentId(),
						UUID.fromString("00000000-0000-0000-0000-000000000322"),
						"Obtain an iron pickaxe", List.of("minecraft:iron_pickaxe"), Optional.empty(),
						DraftIntent.CONFIRM_TRANSLATION, 1_101L, idle.goalRevision(), Optional.empty()
				);
				manager.stageGoalDraft(draft);
				bridge.publishGoalSpecRequest(draft);
				releaseSnapshot.countDown();
				assertEquals("hello_ack", codec.decode(reader.readLine()).type(),
						"handshake retries after the staged goal draft invalidates its snapshot");
				assertEquals("verbose_control", codec.decode(reader.readLine()).type(),
						"verbose control remains ordered before goal draft replay");
				BridgeEnvelope replay = codec.decode(reader.readLine());
				assertEquals("goal_spec_request", replay.type(),
						"goal draft staged during authentication is included by the retried handshake");
				assertEquals(draft.draftId().toString(), replay.payload().get("requestId").getAsString(),
						"retried handshake preserves the staged draft identity");
			}
		} catch (Exception exception) {
			throw new AssertionError("goal draft handshake race verification failed", exception);
		} finally {
			releaseSnapshot.countDown();
			if (bridge != null) bridge.close();
			deleteIfExists(secretFile);
		}
	}

	private static void verifyObsoletePlannerReadinessIsIgnored() {
		MultiplexedServerBridge bridge = null;
		Path secretFile = null;
		try {
			secretFile = Files.createTempFile("arena-agents-obsolete-ready-secret-", ".txt");
			Files.writeString(secretFile, "0123456789abcdef0123456789abcdef");
			CodexAgentManager manager = uninitializedManager();
			AgentRecord created = manager.registry().create("gpt-5.6-sol", "high", Optional.of("LateReady"), 1_700L);
			AgentRecord started = manager.registry().start(created.agentId(), "keep working", 1_701L).after();
			bridge = new MultiplexedServerBridge(manager, 0, secretFile);
			JsonObject stalePayload = new JsonObject();
			stalePayload.addProperty("goalRevision", started.goalRevision() - 1L);
			invokePlannerReady(bridge, new BridgeEnvelope(
					2, "coordinator", started.agentId().toString(), "agent_ready", "late-stale-ready", stalePayload
			));
			assertEquals(AgentLifecycleState.STARTING, manager.registry().require(started.agentId()).state(),
					"an obsolete readiness frame cannot mutate the current lifecycle");

			AgentRecord disconnected = manager.registry().disconnect(started.agentId(), 1_702L).after();
			JsonObject recoveredPayload = new JsonObject();
			recoveredPayload.addProperty("goalRevision", disconnected.goalRevision());
			recoveredPayload.addProperty("reconciled", true);
			BridgeEnvelope recoveredReady = new BridgeEnvelope(
					2, "coordinator", disconnected.agentId().toString(), "agent_ready", "ready-recovered", recoveredPayload
			);
			invokePlannerReady(bridge, recoveredReady);
			AgentRecord recovered = manager.registry().require(disconnected.agentId());
			assertEquals(disconnected.goalRevision(), recovered.goalRevision(),
					"coordinator readiness preserves the authoritative goal revision");
			assertEquals(disconnected.profile(), recovered.profile(),
					"coordinator readiness preserves the exact selected profile");
			assertEquals(1, bridge.observationPublicationForVerification().pendingCount(),
					"coordinator readiness requests one fresh Minecraft observation");
			invokePlannerReady(bridge, recoveredReady);
			assertEquals(1, bridge.observationPublicationForVerification().pendingCount(),
					"duplicate readiness coalesces to one fresh observation request");
			assertEquals(AgentLifecycleState.STARTING, manager.registry().require(disconnected.agentId()).state(),
					"duplicate recovery readiness leaves the same re-armed lifecycle state");
			List<AgentId> recoveryObservations = new ArrayList<>();
			bridge.observationPublicationForVerification().drain(recoveryObservations::add);
			assertEquals(List.of(disconnected.agentId()), recoveryObservations,
					"the first recovery readiness drains one observation request");
			assertEquals(0, bridge.observationPublicationForVerification().pendingCount(),
					"the first recovery observation is fully drained");
			JsonObject planningPayload = new JsonObject();
			planningPayload.addProperty("goalRevision", disconnected.goalRevision());
			invokePlannerReady(bridge, new BridgeEnvelope(
					2, "coordinator", disconnected.agentId().toString(), "planning_state", "planning-recovered", planningPayload
			));
			assertEquals(AgentLifecycleState.PLANNING, manager.registry().require(disconnected.agentId()).state(),
					"the first recovery observation progresses into planning");
			invokePlannerReady(bridge, recoveredReady);
			assertEquals(0, bridge.observationPublicationForVerification().pendingCount(),
					"late duplicate readiness cannot queue a second recovery observation");
			assertEquals(AgentLifecycleState.PLANNING, manager.registry().require(disconnected.agentId()).state(),
					"late duplicate readiness cannot restart the progressed recovery lifecycle");

			manager.registry().remove(started.agentId());
			JsonObject removedPayload = new JsonObject();
			removedPayload.addProperty("goalRevision", started.goalRevision());
			invokePlannerReady(bridge, new BridgeEnvelope(
					2, "coordinator", started.agentId().toString(), "agent_ready", "late-removed-ready", removedPayload
			));
			assertEquals(0, manager.registry().records().size(),
					"readiness for a removed agent is an idempotent no-op");
		} catch (java.io.IOException exception) {
			throw new AssertionError("could not prepare obsolete readiness verification", exception);
		} finally {
			if (bridge != null) bridge.close();
			if (secretFile != null) {
				try {
					Files.deleteIfExists(secretFile);
				} catch (java.io.IOException exception) {
					throw new AssertionError("could not remove obsolete readiness secret", exception);
				}
			}
		}
	}

	private static void verifyHandshakeWaitsForPendingMarker() {
		MultiplexedServerBridge bridge = null;
		Path secretFile = null;
		Thread creator = null;
		CountDownLatch releaseCreation = new CountDownLatch(1);
		AtomicReference<AgentRecord> created = new AtomicReference<>();
		try {
			String secret = "0123456789abcdef0123456789abcdef";
			secretFile = Files.createTempFile("arena-agents-atomic-pending-secret-", ".txt");
			Files.writeString(secretFile, secret);
			CodexAgentManager manager = uninitializedManager();
			Set<AgentId> pending = pendingRegistrations(manager);
			bridge = new MultiplexedServerBridge(manager, 0, secretFile);
			manager.setRuntimeHooks(bridge);
			bridge.start();
			MultiplexedServerBridge activeBridge = bridge;
			BridgeEnvelopeCodec codec = new BridgeEnvelopeCodec();
			try (Socket socket = new Socket(MultiplexedServerBridge.LOOPBACK_HOST, bridge.boundPortForVerification());
				 BufferedReader reader = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8))) {
				socket.setSoTimeout(2_000);
				String messageId = "hello-during-pending-marker";
				AuthenticationExchange exchange = beginAuthentication(socket, reader, codec, secret, messageId);
				CountDownLatch recordVisible = new CountDownLatch(1);
				creator = Thread.ofPlatform().start(() -> activeBridge.withinPublicationBoundary(() -> {
					AgentRecord record = manager.registry().create(
							"gpt-5.6-sol", "high", Optional.of("AtomicSpawn"), 1_250L
					);
					created.set(record);
					recordVisible.countDown();
					awaitLatch(releaseCreation, "pending marker release");
					pending.add(record.agentId());
					return null;
				}));
				awaitLatch(recordVisible, "logical record creation");
				writeAuthenticatedHello(socket, codec, secret, null, messageId, exchange);
				Thread.sleep(50L);
				assertTrue(!activeBridge.authenticated(),
						"handshake cannot authenticate before the pending marker is installed");
				releaseCreation.countDown();
				BridgeEnvelope acknowledgement = codec.decode(reader.readLine());
				assertEquals(0, acknowledgement.payload().getAsJsonArray("registry").size(),
						"handshake excludes the atomically marked pending spawn");
				assertEquals("verbose_control", codec.decode(reader.readLine()).type(),
						"atomic pending fixture consumes verbose control");
			}
			creator.join(2_000L);
			assertTrue(!creator.isAlive(), "pending creation boundary finishes after the handshake snapshot is released");
			assertTrue(pending.contains(created.get().agentId()), "pending marker survives the serialized creation boundary");
		} catch (Exception exception) {
			throw new AssertionError("atomic pending creation verification failed", exception);
		} finally {
			releaseCreation.countDown();
			if (creator != null) {
				try {
					creator.join(2_000L);
				} catch (InterruptedException exception) {
					Thread.currentThread().interrupt();
				}
			}
			if (bridge != null) bridge.close();
			if (secretFile != null) {
				try {
					Files.deleteIfExists(secretFile);
				} catch (java.io.IOException exception) {
					throw new AssertionError("could not remove atomic-pending secret", exception);
				}
			}
		}
	}

	private static void verifyVerboseControlRetriesAfterBackpressure() {
		MultiplexedServerBridge bridge = null;
		Path secretFile = null;
		try {
			String secret = "0123456789abcdef0123456789abcdef";
			secretFile = Files.createTempFile("arena-agents-verbose-retry-secret-", ".txt");
			Files.writeString(secretFile, secret);
			AgentVerboseState verboseState = new AgentVerboseState();
			verboseState.setEnabled(true);
			bridge = new MultiplexedServerBridge(uninitializedManager(), 0, secretFile, verboseState);
			bridge.start();
			BridgeEnvelopeCodec codec = new BridgeEnvelopeCodec();
			try (Socket socket = new Socket(MultiplexedServerBridge.LOOPBACK_HOST, bridge.boundPortForVerification());
				 BufferedReader reader = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8))) {
				socket.setSoTimeout(2_000);
				authenticate(socket, reader, codec, secret, "hello-verbose-retry");
				setQueuedCount(bridge, "server", MultiplexedServerBridge.AGENT_QUEUE_CAP);
				bridge.setVerbose(false);
				setQueuedCount(bridge, "server", 0);
				bridge.tick();
				BridgeEnvelope retried = codec.decode(reader.readLine());
				assertEquals("verbose_control", retried.type(), "a backpressured verbose change is retried");
				assertEquals(false, retried.payload().get("enabled").getAsBoolean(),
						"the retry publishes the latest server verbose setting");
			}
		} catch (Exception exception) {
			throw new AssertionError("verbose control retry verification failed", exception);
		} finally {
			if (bridge != null) bridge.close();
			if (secretFile != null) {
				try {
					Files.deleteIfExists(secretFile);
				} catch (java.io.IOException exception) {
					throw new AssertionError("could not remove verbose retry secret", exception);
				}
			}
		}
	}

	private static void verifyVerboseTelemetryCannotSuppressProgress() {
		MultiplexedServerBridge bridge = null;
		Path secretFile = null;
		try {
			String secret = "0123456789abcdef0123456789abcdef";
			secretFile = Files.createTempFile("arena-agents-observational-verbose-secret-", ".txt");
			Files.writeString(secretFile, secret);
			CodexAgentManager manager = uninitializedManager();
			AgentVerboseState verboseState = new AgentVerboseState();
			verboseState.setEnabled(true);
			bridge = new MultiplexedServerBridge(manager, 0, secretFile, verboseState);
			bridge.start();
			BridgeEnvelopeCodec codec = new BridgeEnvelopeCodec();
			try (Socket socket = new Socket(MultiplexedServerBridge.LOOPBACK_HOST, bridge.boundPortForVerification());
				 BufferedReader reader = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8))) {
				socket.setSoTimeout(2_000);
				BridgeEnvelope verboseHandshake = authenticate(
						socket, reader, codec, secret, "hello-observational-verbose"
				);
				assertEquals("hello_ack", verboseHandshake.type(),
						"observational verbose fixture authenticates the bridge");
				AgentId removedAgent = AgentId.parse("00000000-0000-0000-0000-000000000401");
				invokeActionProgress(bridge, new ServerActionProgress(
						removedAgent, 1L, "action-progress-1", ActionType.WAIT, "trace-progress-1",
						0.5D, 25L, 4_000L
				));
				assertEquals("action_progress", codec.decode(reader.readLine()).type(),
						"missing verbose record cannot suppress the authoritative progress frame");
			}
		} catch (Exception exception) {
			throw new AssertionError("observational verbose progress verification failed", exception);
		} finally {
			if (bridge != null) bridge.close();
			if (secretFile != null) {
				try {
					Files.deleteIfExists(secretFile);
				} catch (java.io.IOException exception) {
					throw new AssertionError("could not remove observational verbose secret", exception);
				}
			}
		}
	}

	private static void verifyRespawnContinuationPayload() {
		ServerActionResult respawnResult = new ServerActionResult(
				AgentId.parse("00000000-0000-0000-0000-000000000300"), 3L, "respawn-result",
				ActionType.RESPAWN, ServerActionState.SUCCEEDED, "VANILLA_RESPAWNED",
				"Respawned", 42L, 3_000L
		);
		assertEquals("Respawned.", invokeVerboseResult(respawnResult),
				"respawn verbose result uses readable player-facing copy");
		ServerActionResult technicalResult = new ServerActionResult(
				respawnResult.agentId(), 3L, "respawn-result-technical", ActionType.RESPAWN,
				ServerActionState.FAILED, "RESPAWN_REJECTED",
				"Result payload {\"action\":\"respawn-result-technical\"}", 42L, 3_001L
		);
		assertEquals("Technical details hidden.", invokeVerboseResult(technicalResult),
				"typed action result messages pass through the technical-output defense");
		CodexAgentManager activeManager = uninitializedManager();
		AgentRecord active = activeManager.registry().create("gpt-5.6-sol", "high", Optional.of("ActiveDeath"), 3_000L);
		activeManager.registry().start(active.agentId(), "continue after respawn", 3_001L);
		activeManager.registry().die(active.agentId(), deathSnapshot(3_002L), 3_002L);
		AgentTransition activeRespawn = activeManager.registry().respawn(
				active.agentId(), UUID.fromString("00000000-0000-0000-0000-000000000301"), 3_003L
		);
		JsonObject activePayload = invokeGoalControlPayload(activeRespawn, "respawn");
		assertTrue(activePayload.has("resumeGoal") && activePayload.get("resumeGoal").getAsBoolean(),
				"active-at-death respawn tells the coordinator to continue the unfinished goal");

		CodexAgentManager pausedManager = uninitializedManager();
		AgentRecord paused = pausedManager.registry().create("gpt-5.6-sol", "high", Optional.of("PausedDeath"), 3_010L);
		pausedManager.registry().start(paused.agentId(), "remain paused after respawn", 3_011L);
		pausedManager.registry().stop(paused.agentId(), 3_012L);
		pausedManager.registry().die(paused.agentId(), deathSnapshot(3_013L), 3_013L);
		AgentTransition pausedRespawn = pausedManager.registry().respawn(
				paused.agentId(), UUID.fromString("00000000-0000-0000-0000-000000000302"), 3_014L
		);
		assertTrue(!invokeGoalControlPayload(pausedRespawn, "respawn").has("resumeGoal"),
				"explicitly paused-at-death respawn omits automatic goal continuation");
	}

	private static AgentDeathSnapshot deathSnapshot(long diedAtEpochMs) {
		return new AgentDeathSnapshot(
				"verification", "minecraft:overworld", 0.0D, 64.0D, 0.0D,
				Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(),
				Optional.empty(), Optional.empty(), Optional.empty(), "survival", diedAtEpochMs
		);
	}

	private static void verifyPendingRegistrationBoundary() {
		MultiplexedServerBridge bridge = null;
		Path secretFile = null;
		try {
			String secret = "0123456789abcdef0123456789abcdef";
			secretFile = Files.createTempFile("arena-agents-pending-registration-secret-", ".txt");
			Files.writeString(secretFile, secret);
			CodexAgentManager manager = uninitializedManager();
			AgentRecord registered = manager.registry().create("gpt-5.6-sol", "high", Optional.of("Luna"), 1_000L);
			AgentRecord pending = manager.registry().create("gpt-5.6-sol", "high", Optional.of("Sol"), 1_001L);
			Set<AgentId> pendingRegistrations = pendingRegistrations(manager);
			pendingRegistrations.add(pending.agentId());
			assertEquals(List.of(registered), manager.coordinatorVisibleRecords(),
					"a logical record remains coordinator-invisible until its verified registration is published");

			bridge = new MultiplexedServerBridge(manager, 0, secretFile);
			manager.setRuntimeHooks(bridge);
			invokePendingRegistrationPublication(manager, pending);
			assertTrue(pendingRegistrations.contains(pending.agentId()),
					"an unauthenticated publication attempt retains the pending marker for retry");
			bridge.start();
			MultiplexedServerBridge activeBridge = bridge;
			BridgeEnvelopeCodec codec = new BridgeEnvelopeCodec();
			try (Socket socket = new Socket(MultiplexedServerBridge.LOOPBACK_HOST, bridge.boundPortForVerification());
				 BufferedReader reader = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8))) {
				socket.setSoTimeout(2_000);
				BridgeEnvelope helloAck = authenticate(
						socket, reader, codec, secret, "hello-pending-registration"
				);
				assertEquals("hello_ack", helloAck.type(), "pending-registration fixture authenticates the bridge");
				JsonArray registry = helloAck.payload().getAsJsonArray("registry");
				assertEquals(1, registry.size(), "handshake excludes the unregistered logical record");
				assertEquals(registered.agentId().toString(), registry.get(0).getAsJsonObject().get("agentId").getAsString(),
						"handshake retains the previously registered agent");
				invokeUrgentObservation(bridge, pending.agentId());
				assertEquals(0, bridge.observationPublicationForVerification().pendingCount(),
						"pending registration cannot enter the urgent observation queue");
				MultiplexedServerBridge.ObservationPublication publication =
						bridge.observationPublicationForVerification();
				publication.scheduleIdleHeartbeat(
						MultiplexedServerBridge.registeredObservationIds(manager.coordinatorVisibleRecords())
				);
				AtomicReference<AgentId> heartbeatAgent = new AtomicReference<>();
				publication.drain(agentId -> {
					heartbeatAgent.set(agentId);
					assertTrue(publication.takeHeartbeat(agentId),
							"registered agent remains eligible for heartbeat publication");
				});
				assertEquals(registered.agentId(), heartbeatAgent.get(),
						"heartbeat scheduling selects the registered agent");
				assertTrue(!publication.takeHeartbeat(pending.agentId()),
						"pending registration cannot enter the heartbeat queue");
				AgentRecord beforeConversation = manager.registry().require(pending.agentId());
				ConversationEvent pendingMessage = new ConversationEvent(
						pending.agentId(), "00000000-0000-0000-0000-000000000098", pending.agentId().toString(),
						ConversationAudience.DIRECT, ConversationKind.PLAYER_MESSAGE, "Can you respond while joining?",
						0L, 1_002L, 1L, "minecraft:overworld"
				);
				assertThrowsCode(
						() -> activeBridge.publishConversationEvent(pendingMessage, Optional.of(testGoal("Respond after registration."))),
						"AGENT_NOT_READY"
				);
				assertEquals(beforeConversation, manager.registry().require(pending.agentId()),
						"pending online direct message cannot commit STARTING before registration");
				assertTrue(manager.pendingConversationWakes().isEmpty(),
						"rejected pending direct message leaves no durable conversation wake");
				assertThrowsCode(
						() -> activeBridge.publishConversationEvent(pendingMessage, Optional.empty()),
						"AGENT_NOT_READY"
				);
				writeEnvelope(socket, codec, new BridgeEnvelope(
						2, helloAck.serverInstanceId(), "server", "heartbeat",
						"heartbeat-after-pending-conversation", new JsonObject()
				));
				assertEquals("heartbeat", pollBridgeResponseOfType(
						bridge, socket, reader, codec, "heartbeat", pending.agentId()).type(),
						"pending direct message emits neither conversation_event nor conversation_wake");

				AgentTransition pendingStart = manager.registry().start(
						pending.agentId(), "must register before lifecycle", 1_003L
				);
				bridge.onTransition(pendingStart);
				writeEnvelope(socket, codec, new BridgeEnvelope(
						2, helloAck.serverInstanceId(), "server", "heartbeat",
						"heartbeat-after-pending-transition", new JsonObject()
				));
				assertEquals("heartbeat", pollBridgeResponseOfType(
						bridge, socket, reader, codec, "heartbeat", pending.agentId()).type(),
						"a pending agent cannot publish lifecycle frames before agent_registered");

				bridge.onRemoved(pending.agentId(), pendingStart.after().goalRevision() + 1L);
				writeEnvelope(socket, codec, new BridgeEnvelope(
						2, helloAck.serverInstanceId(), "server", "heartbeat",
						"heartbeat-after-pending-removal", new JsonObject()
				));
				assertEquals("heartbeat", pollBridgeResponseOfType(
						bridge, socket, reader, codec, "heartbeat", pending.agentId()).type(),
						"removing a never-registered pending agent emits no unknown agent_removed frame");

				invokePendingRegistrationPublication(manager, pendingStart.after());
				BridgeEnvelope registration = pollBridgeResponseOfType(
						bridge, socket, reader, codec, "agent_registered", pending.agentId());
				assertEquals("agent_registered", registration.type(),
						"verified registration is the first frame published for the new agent");
				assertEquals(pending.agentId().toString(), registration.agentId(),
						"verified registration carries the new stable agent ID");
				assertTrue(!pendingRegistrations.contains(pending.agentId()),
						"manager clears pending registration only after the hook publishes it");
				ConversationEvent awaitingCoordinatorReady = new ConversationEvent(
						pending.agentId(), "00000000-0000-0000-0000-000000000099", pending.agentId().toString(),
						ConversationAudience.DIRECT, ConversationKind.PLAYER_MESSAGE, "Do not lose this while registering.",
						pendingStart.after().goalRevision(), 1_004L, 2L, "minecraft:overworld"
				);
				assertThrowsCode(
						() -> activeBridge.publishConversationEvent(awaitingCoordinatorReady, Optional.empty()),
						"AGENT_NOT_READY"
				);
				writeEnvelope(socket, codec, new BridgeEnvelope(
						2, helloAck.serverInstanceId(), "server", "heartbeat",
						"heartbeat-before-agent-ready", new JsonObject()
				));
				assertEquals("heartbeat", pollBridgeResponseOfType(
						bridge, socket, reader, codec, "heartbeat", null).type(),
						"queued registration does not accept conversation frames before coordinator readiness");

				JsonObject ready = new JsonObject();
				ready.addProperty("goalRevision", pendingStart.after().goalRevision());
				writeEnvelope(socket, codec, new BridgeEnvelope(
						2, helloAck.serverInstanceId(), pending.agentId().toString(), "agent_ready",
						"pending-registration-ready", ready
				));
				awaitCondition(() -> {
					activeBridge.tick();
					return manager.registry().require(pending.agentId()).state() == AgentLifecycleState.PLANNING;
				}, "coordinator readiness completes the pending registration boundary");
				activeBridge.publishConversationEvent(awaitingCoordinatorReady, Optional.empty());
				assertEquals("conversation_event", pollBridgeResponseOfType(
						bridge, socket, reader, codec, "conversation_event", null).type(),
						"conversation publication begins after coordinator readiness is acknowledged");
				bridge.onTransition(pendingStart);
				BridgeEnvelope lifecycle = pollBridgeResponseOfType(
						bridge, socket, reader, codec, "goal_control", null);
				assertEquals("goal_control", lifecycle.type(),
						"lifecycle publication follows the successful agent_registered frame");
				assertEquals(List.of(registered, manager.registry().require(pending.agentId())), manager.coordinatorVisibleRecords(),
						"successful registration makes the new agent observation-eligible");
				invokeUrgentObservation(bridge, pending.agentId());
				assertEquals(1, bridge.observationPublicationForVerification().pendingCount(),
						"the first observation may queue only after registration publication succeeds");
			}
		} catch (Exception exception) {
			throw new AssertionError("pending registration boundary verification failed", exception);
		} finally {
			if (bridge != null) bridge.close();
			if (secretFile != null) {
				try {
					Files.deleteIfExists(secretFile);
				} catch (java.io.IOException exception) {
					throw new AssertionError("could not remove pending-registration bridge secret", exception);
				}
			}
		}
	}

	private static void verifyRemovalBackpressureForcesReconciliation() {
		MultiplexedServerBridge bridge = null;
		Path secretFile = null;
		try {
			String secret = "0123456789abcdef0123456789abcdef";
			secretFile = Files.createTempFile("arena-agents-removal-backpressure-secret-", ".txt");
			Files.writeString(secretFile, secret);
			CodexAgentManager manager = uninitializedManager();
			AgentRecord removed = manager.registry().create("gpt-5.6-sol", "high", Optional.of("Removed"), 1_100L);
			bridge = new MultiplexedServerBridge(manager, 0, secretFile);
			bridge.start();
			MultiplexedServerBridge activeBridge = bridge;
			BridgeEnvelopeCodec codec = new BridgeEnvelopeCodec();
			try (Socket socket = new Socket(MultiplexedServerBridge.LOOPBACK_HOST, bridge.boundPortForVerification());
				 BufferedReader reader = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8))) {
				socket.setSoTimeout(BACKPRESSURE_HANDSHAKE_TIMEOUT_MS);
				authenticate(socket, reader, codec, secret, "hello-before-removal-backpressure");
				manager.registry().remove(removed.agentId());
				saturateAgentQueue(bridge, removed.agentId());
				assertThrowsBridgeCode(
						() -> activeBridge.onRemoved(removed.agentId(), 1L), "AGENT_BACKPRESSURE",
						"failed agent_removed enqueue retains reconciliation responsibility"
				);
				awaitCondition(() -> !activeBridge.observationPublicationForVerification().hasActiveSession(),
						"agent_removed backpressure closes the stale coordinator session");
			}
			awaitCondition(activeBridge::coordinatorDisconnectPendingForVerification,
					"agent_removed backpressure schedules coordinator reconciliation");
			bridge.tick();

			try (Socket replacement = new Socket(MultiplexedServerBridge.LOOPBACK_HOST, bridge.boundPortForVerification());
				 BufferedReader reader = new BufferedReader(new InputStreamReader(replacement.getInputStream(), StandardCharsets.UTF_8))) {
				replacement.setSoTimeout(BACKPRESSURE_HANDSHAKE_TIMEOUT_MS);
				BridgeEnvelope acknowledgement = authenticate(
						replacement, reader, codec, secret, "hello-after-removal-backpressure"
				);
				assertEquals(0, acknowledgement.payload().getAsJsonArray("registry").size(),
						"replacement handshake reconciles the removed agent out of coordinator state");
			}
		} catch (Exception exception) {
			throw new AssertionError("agent removal backpressure verification failed", exception);
		} finally {
			if (bridge != null) bridge.close();
			if (secretFile != null) {
				try {
					Files.deleteIfExists(secretFile);
				} catch (java.io.IOException exception) {
					throw new AssertionError("could not remove removal-backpressure secret", exception);
				}
			}
		}
	}

	private static void verifyReplacementHandshakeSupersedesPendingDisconnect() {
		MultiplexedServerBridge bridge = null;
		Path secretFile = null;
		try {
			String secret = "0123456789abcdef0123456789abcdef";
			secretFile = Files.createTempFile("arena-agents-replacement-handshake-secret-", ".txt");
			Files.writeString(secretFile, secret);
			CodexAgentManager manager = uninitializedManager();
			AgentRecord active = manager.registry().create("gpt-5.6-sol", "high", Optional.of("Replacement"), 1_500L);
			AgentTransition started = manager.registry().start(active.agentId(), "survive fast reconnect", 1_501L);
			bridge = new MultiplexedServerBridge(manager, 0, secretFile);
			savedData(manager).setRuntimeHooks(bridge);
			bridge.start();
			BridgeEnvelopeCodec codec = new BridgeEnvelopeCodec();
			try (Socket first = new Socket(MultiplexedServerBridge.LOOPBACK_HOST, bridge.boundPortForVerification());
				 BufferedReader firstReader = new BufferedReader(new InputStreamReader(first.getInputStream(), StandardCharsets.UTF_8))) {
				first.setSoTimeout(2_000);
				BridgeEnvelope firstAck = authenticate(first, firstReader, codec, secret, "hello-before-replacement");
				assertEquals(started.after().agentId().toString(),
						firstAck.payload().getAsJsonArray("registry").get(0).getAsJsonObject().get("agentId").getAsString(),
						"first session knows the active agent");
				JsonObject emptyCatalog = new JsonObject();
				emptyCatalog.addProperty("refreshedAtEpochMs", 0L);
				emptyCatalog.add("models", new JsonArray());
				writeEnvelope(first, codec, new BridgeEnvelope(
						2, firstAck.serverInstanceId(), "server", "catalog_snapshot", "old-session-empty-catalog", emptyCatalog
				));
				assertEquals("catalog_request", pollBridgeResponseOfType(
						bridge, first, firstReader, codec, "catalog_request", null).type(),
						"old session owns its catalog discovery request");
			}
			MultiplexedServerBridge activeBridge = bridge;
			awaitCondition(() -> !activeBridge.observationPublicationForVerification().hasActiveSession(),
					"old authenticated session closes before its disconnect tick");

			try (Socket replacement = new Socket(MultiplexedServerBridge.LOOPBACK_HOST, bridge.boundPortForVerification());
				 BufferedReader replacementReader = new BufferedReader(new InputStreamReader(replacement.getInputStream(), StandardCharsets.UTF_8))) {
				replacement.setSoTimeout(2_000);
				BridgeEnvelope replacementAck = authenticateWithTicks(
						bridge, replacement, replacementReader, codec, secret, "hello-fast-replacement"
				);
				AgentRecord reconciled = manager.registry().require(active.agentId());
				assertEquals(reconciled.goalRevision(),
						replacementAck.payload().getAsJsonArray("registry").get(0).getAsJsonObject().get("goalRevision").getAsLong(),
						"replacement handshake snapshots the reconciled authoritative revision");
				assertEquals(AgentLifecycleState.DISCONNECTED, reconciled.state(),
						"replacement authentication waits for old-session disconnect reconciliation");
				long staleRetryDeadline = System.nanoTime() + 150_000_000L;
				while (System.nanoTime() < staleRetryDeadline) {
					bridge.tick();
					Thread.sleep(1L);
				}
				while (replacement.getInputStream().available() > 0) {
					BridgeEnvelope response = codec.decode(replacementReader.readLine());
					assertTrue(!"catalog_request".equals(response.type()),
							"replacement session never receives the closed session catalog retry");
				}

				AgentTransition resumed = manager.registry().resume(active.agentId(), 1_502L);
				bridge.onTransition(resumed);
				assertEquals("goal_control", pollBridgeResponseOfType(
						bridge, replacement, replacementReader, codec, "goal_control", null).type(),
						"replacement session receives the resumed authoritative goal");
				JsonObject ready = new JsonObject();
				ready.addProperty("goalRevision", resumed.after().goalRevision());
				writeEnvelope(replacement, codec, new BridgeEnvelope(
						2, replacementAck.serverInstanceId(), active.agentId().toString(), "agent_ready",
						"ready-after-fast-replacement", ready
				));
				awaitCondition(() -> {
					activeBridge.tick();
					return manager.registry().require(active.agentId()).state() == AgentLifecycleState.PLANNING;
				}, "replacement agent_ready for the resumed authoritative revision remains current");
			}
		} catch (Exception exception) {
			throw new AssertionError("replacement handshake disconnect ordering verification failed", exception);
		} finally {
			if (bridge != null) bridge.close();
			if (secretFile != null) {
				try {
					Files.deleteIfExists(secretFile);
				} catch (java.io.IOException exception) {
					throw new AssertionError("could not remove replacement-handshake secret", exception);
				}
			}
		}
	}

	private static void verifyAuthenticatedReconnectRecovery() {
		MultiplexedServerBridge bridge = null;
		Path secretFile = null;
		try {
			String secret = "0123456789abcdef0123456789abcdef";
			secretFile = Files.createTempFile("arena-agents-reconnect-secret-", ".txt");
			Files.writeString(secretFile, secret);
			CodexAgentManager manager = uninitializedManager();
			AgentRecord disconnected = manager.registry().create("gpt-5.6-sol", "high", Optional.of("Recovering"), 2_000L);
			manager.registry().start(disconnected.agentId(), "finish the interrupted task", 2_001L);
			disconnected = manager.registry().disconnect(disconnected.agentId(), 2_002L).after();
			AgentId disconnectedId = disconnected.agentId();
			long disconnectedRevision = disconnected.goalRevision();
			AgentRecord paused = manager.registry().create("gpt-5.6-sol", "high", Optional.of("Paused"), 2_003L);
			manager.registry().start(paused.agentId(), "stay explicitly paused", 2_004L);
			paused = manager.registry().stop(paused.agentId(), 2_005L).after();
			long pausedRevision = paused.goalRevision();

			bridge = new MultiplexedServerBridge(manager, 0, secretFile);
			ProgramActionLedger programActions = programActions(bridge);
			JsonObject replayArguments = new JsonObject();
			replayArguments.addProperty("durationMs", 25L);
			ServerActionRequest replayProtected = new ServerActionRequest(
					disconnected.agentId(), disconnectedRevision, "action-before-reconnect", ActionType.WAIT, replayArguments,
					new ActionProvenance("codex", "gpt-5.6-sol", "high", "priority", "program-before-reconnect", 1L, "step-1", 1L)
			);
			programActions.accept(replayProtected);
			savedData(manager).setRuntimeHooks(bridge);
			bridge.start();
			BridgeEnvelopeCodec codec = new BridgeEnvelopeCodec();
			try (Socket socket = new Socket(MultiplexedServerBridge.LOOPBACK_HOST, bridge.boundPortForVerification());
				 BufferedReader reader = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8))) {
				socket.setSoTimeout(2_000);
				BridgeEnvelope helloAck = authenticate(
						socket, reader, codec, secret, "hello-reconnect"
				);
				assertEquals("hello_ack", helloAck.type(), "reconnect fixture authenticates the bridge");
				assertThrowsCode(() -> programActions.accept(replayProtected), "ACTION_REPLAY");

				JsonObject reconnectReady = new JsonObject();
				reconnectReady.addProperty("goalRevision", disconnectedRevision);
				reconnectReady.addProperty("reconciled", true);
				writeEnvelope(socket, codec, new BridgeEnvelope(
						2, helloAck.serverInstanceId(), disconnected.agentId().toString(), "agent_ready",
						"ready-reconnected", reconnectReady
				));
				writeEnvelope(socket, codec, new BridgeEnvelope(
						2, helloAck.serverInstanceId(), "server", "heartbeat", "heartbeat-after-recovery", new JsonObject()
				));
				BridgeEnvelope heartbeat = pollBridgeResponseOfType(
						bridge, socket, reader, codec, "heartbeat", null);
				assertEquals("heartbeat", heartbeat.type(),
						"infrastructure recovery emits no player resume command");
				assertEquals(AgentLifecycleState.STARTING, manager.registry().require(disconnected.agentId()).state(),
						"disconnected goal re-enters STARTING after authenticated reconciliation");
				assertEquals(disconnectedRevision, manager.registry().require(disconnected.agentId()).goalRevision(),
						"authenticated recovery preserves the authoritative goal revision");
				assertEquals(disconnected.profile(), manager.registry().require(disconnected.agentId()).profile(),
						"authenticated recovery preserves the exact selected profile");
				assertEquals("finish the interrupted task",
						manager.registry().require(disconnected.agentId()).currentGoal().orElseThrow().prompt(),
						"automatic reconnect recovery preserves the unfinished goal");

				JsonObject resumedReady = new JsonObject();
				resumedReady.addProperty("goalRevision", disconnectedRevision);
				writeEnvelope(socket, codec, new BridgeEnvelope(
						2, helloAck.serverInstanceId(), disconnected.agentId().toString(), "agent_ready",
						"ready-resumed", resumedReady
				));
				MultiplexedServerBridge activeBridge = bridge;
				awaitCondition(() -> {
					activeBridge.tick();
					return manager.registry().require(disconnectedId).state() == AgentLifecycleState.PLANNING;
				}, "fresh coordinator readiness advances the recovered goal to planning");

				JsonObject pausedReady = new JsonObject();
				pausedReady.addProperty("goalRevision", pausedRevision);
				pausedReady.addProperty("reconciled", true);
				writeEnvelope(socket, codec, new BridgeEnvelope(
						2, helloAck.serverInstanceId(), paused.agentId().toString(), "agent_ready",
						"ready-explicitly-paused", pausedReady
				));
				writeEnvelope(socket, codec, new BridgeEnvelope(
						2, helloAck.serverInstanceId(), "server", "heartbeat", "heartbeat-after-paused-ready", new JsonObject()
				));
				assertEquals("heartbeat", pollBridgeResponseOfType(
						bridge, socket, reader, codec, "heartbeat", null).type(),
						"explicitly paused reconciliation emits no automatic resume control");
				assertEquals(AgentLifecycleState.PAUSED, manager.registry().require(paused.agentId()).state(),
						"explicitly paused agent remains paused after authenticated reconciliation");
			}
		} catch (Exception exception) {
			throw new AssertionError("authenticated reconnect recovery verification failed", exception);
		} finally {
			if (bridge != null) bridge.close();
			if (secretFile != null) {
				try {
					Files.deleteIfExists(secretFile);
				} catch (java.io.IOException exception) {
					throw new AssertionError("could not remove reconnect bridge secret", exception);
				}
			}
		}
	}

	private static void verifyAgentErrorRevisionGate() {
		MultiplexedServerBridge bridge = null;
		Path secretFile = null;
		try {
			String secret = "0123456789abcdef0123456789abcdef";
			secretFile = Files.createTempFile("arena-agents-error-revision-secret-", ".txt");
			Files.writeString(secretFile, secret);
			CodexAgentManager manager = uninitializedManager();
			AgentVerboseState verboseState = new AgentVerboseState();
			verboseState.setEnabled(true);
			AgentRecord active = manager.registry().create(
					"gpt-5.6-sol", "high", Optional.of("Error gate"), 2_100L
			);
			AgentTransition started = manager.registry().start(
					active.agentId(), "fail the interrupted task", 2_101L
			);
			long currentRevision = started.after().goalRevision();

			bridge = new MultiplexedServerBridge(manager, 0, secretFile, verboseState);
			savedData(manager).setRuntimeHooks(bridge);
			bridge.start();
			BridgeEnvelopeCodec codec = new BridgeEnvelopeCodec();
			try (Socket socket = new Socket(MultiplexedServerBridge.LOOPBACK_HOST, bridge.boundPortForVerification());
				 BufferedReader reader = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8))) {
				socket.setSoTimeout(2_000);
				BridgeEnvelope helloAck = authenticate(socket, reader, codec, secret, "hello-error-revision");

				JsonObject staleError = new JsonObject();
				staleError.addProperty("goalRevision", currentRevision - 1L);
				staleError.addProperty("code", "STALE_PLANNER_FAILED");
				staleError.addProperty("message", "Stale planner failure must be ignored");
				writeEnvelope(socket, codec, new BridgeEnvelope(
						2, helloAck.serverInstanceId(), active.agentId().toString(), "agent_error",
						"agent-error-stale", staleError
				));
				writeEnvelope(socket, codec, new BridgeEnvelope(
						2, helloAck.serverInstanceId(), "server", "heartbeat",
						"heartbeat-after-stale-agent-error", new JsonObject()
				));
				assertEquals("heartbeat", pollBridgeResponseOfType(
						bridge, socket, reader, codec, "heartbeat", null).type(),
						"stale agent_error leaves the authenticated bridge usable");

				AgentRecord afterStale = manager.registry().require(active.agentId());
				assertEquals(AgentLifecycleState.STARTING, afterStale.state(),
						"stale agent_error leaves the active Java lifecycle unchanged");
				assertEquals(currentRevision, afterStale.goalRevision(),
						"stale agent_error cannot manufacture a revision");
				assertEquals("", afterStale.lastError(),
						"stale agent_error cannot write a terminal planner message");

				JsonObject currentError = new JsonObject();
				currentError.addProperty("goalRevision", currentRevision);
				currentError.addProperty("code", "PLANNER_FAILED");
				currentError.addProperty("message", "Planner could not continue");
				writeEnvelope(socket, codec, new BridgeEnvelope(
						2, helloAck.serverInstanceId(), active.agentId().toString(), "agent_error",
						"agent-error-current", currentError
				));

				BridgeEnvelope failure = pollBridgeResponseOfType(
						bridge, socket, reader, codec, "goal_control", null);
				assertEquals("goal_control", failure.type(),
						"current agent_error publishes a lifecycle failure control");
				assertEquals("fail", failure.payload().get("operation").getAsString(),
						"current agent_error publishes the fail operation");
				assertEquals(currentRevision + 1L, failure.payload().get("goalRevision").getAsLong(),
						"current agent_error advances the authoritative revision exactly once");

				AgentRecord failed = manager.registry().require(active.agentId());
				assertEquals(AgentLifecycleState.ERROR, failed.state(),
						"current agent_error aligns the Java lifecycle with ERROR");
				assertEquals(currentRevision + 1L, failed.goalRevision(),
						"current agent_error stores the single terminal revision increment");
				assertEquals("Planner could not continue", failed.lastError(),
						"current agent_error stores the terminal planner message");
			}
		} catch (Exception exception) {
			throw new AssertionError("agent_error revision gate verification failed", exception);
		} finally {
			if (bridge != null) bridge.close();
			if (secretFile != null) {
				try {
					Files.deleteIfExists(secretFile);
				} catch (java.io.IOException exception) {
					throw new AssertionError("could not remove agent_error revision gate secret", exception);
				}
			}
		}
	}

	private static void verifyShutdownRejectsAcceptedSocketBeforePublication() {
		MultiplexedServerBridge bridge = null;
		ShutdownRaceServerSocket listener = null;
		try {
			long baselineBridgeThreads = bridgeThreadCount();
			bridge = MultiplexedServerBridge.withPreparedSecret(
					uninitializedManager(), 0, "0123456789abcdef0123456789abcdef");
			listener = new ShutdownRaceServerSocket();
			MultiplexedServerBridge activeBridge = bridge;
			ShutdownRaceServerSocket activeListener = listener;
			bridge.start(() -> activeListener);
			activeListener.releaseAccept();
			awaitLatch(activeListener.acceptedSocket.addressLookupStarted,
					"accept returns before bridge shutdown");
			Thread closer = Thread.ofPlatform().daemon().start(activeBridge::close);
			closer.join(2_000L);
			assertTrue(!closer.isAlive(), "bridge close completes after the publication boundary opens");
			assertTrue(activeListener.isClosed(), "bridge close releases the obsolete listener");
			activeListener.acceptedSocket.releaseAddressLookup();
			awaitCondition(activeListener.acceptedSocket.closed::get,
					"socket accepted by the obsolete listener closes instead of becoming a session");
			assertTrue(readPrivateField(bridge, "session") == null,
					"shutdown leaves no obsolete session published");
			assertTrue(!bridge.observationPublicationForVerification().hasActiveSession(),
					"shutdown leaves no obsolete observation publisher active");
			BoundedServerTaskQueue tasks = (BoundedServerTaskQueue) readPrivateField(bridge, "serverTasks");
			assertEquals(0, tasks.pendingCount(), "shutdown queues no inbound work from the rejected socket");
			awaitCondition(() -> bridgeThreadCount() <= baselineBridgeThreads,
					"shutdown leaves no accept, reader, or writer thread behind");
		} catch (Exception exception) {
			throw new AssertionError("accepted socket shutdown race verification failed", exception);
		} finally {
			if (bridge != null) bridge.close();
			if (listener != null) {
				listener.acceptedSocket.releaseAddressLookup();
				listener.acceptedSocket.close();
				listener.close();
			}
		}
	}

	private static void verifyFailedBindClosesEverySocket() {
		MultiplexedServerBridge bridge = null;
		try (ServerSocket conflict = new ServerSocket(0, 1, InetAddress.getByName(MultiplexedServerBridge.LOOPBACK_HOST))) {
			int port = conflict.getLocalPort();
			bridge = MultiplexedServerBridge.withPreparedSecret(
					uninitializedManager(), port, "0123456789abcdef0123456789abcdef");
			List<ServerSocket> candidates = new ArrayList<>();
			MultiplexedServerBridge.ServerSocketFactory socketFactory = () -> {
				ServerSocket candidate = new ServerSocket();
				candidates.add(candidate);
				return candidate;
			};

			MultiplexedServerBridge retryingBridge = bridge;
			for (int attempt = 0; attempt < 4; attempt++) {
				assertThrows(BridgeProtocolException.class, () -> retryingBridge.start(socketFactory),
						"occupied bridge port rejects retry " + attempt);
			}
			assertEquals(4, candidates.size(), "each occupied-port retry creates one candidate socket");
			assertTrue(candidates.stream().allMatch(ServerSocket::isClosed),
					"every candidate socket closes when bind fails before bridge ownership transfer");
		} catch (IOException exception) {
			throw new AssertionError("failed-bind socket cleanup verification failed", exception);
		} finally {
			if (bridge != null) bridge.close();
		}
	}

	private static void verifyLaunchIdentityAndReconnectGeneration() {
		MultiplexedServerBridge bridge = null;
		Path secretFile = null;
		try {
			String secret = "0123456789abcdef0123456789abcdef";
			String launchId = "00000000-0000-0000-0000-000000000881";
			secretFile = Files.createTempFile("arena-agents-launch-identity-", ".txt");
			Files.writeString(secretFile, secret);
			bridge = new MultiplexedServerBridge(uninitializedManager(), 0, secretFile);
			bridge.start();
			BridgeEnvelopeCodec codec = new BridgeEnvelopeCodec();
			long firstGeneration;
			try (Socket socket = new Socket(MultiplexedServerBridge.LOOPBACK_HOST, bridge.boundPortForVerification());
				 BufferedReader reader = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8))) {
				socket.setSoTimeout(2_000);
				BridgeEnvelope acknowledgement = authenticate(socket, reader, codec, secret, launchId, "hello-launch-1");
				assertEquals(launchId, acknowledgement.payload().get("launchId").getAsString(),
						"hello acknowledgement echoes the owned launch identity");
				assertEquals(launchId, bridge.authenticatedLaunchId(),
						"bridge publishes the exact authenticated launch identity");
				firstGeneration = bridge.authenticatedSessionGeneration();
				assertTrue(firstGeneration > 0L, "first authenticated socket receives a positive session generation");
			}
			MultiplexedServerBridge activeBridge = bridge;
			awaitCondition(() -> !activeBridge.authenticated(), "closed authenticated socket releases bridge authentication");
			awaitCondition(activeBridge::coordinatorDisconnectPendingForVerification,
					"closed authenticated socket schedules coordinator cleanup");
			bridge.tick();
			try (Socket socket = new Socket(MultiplexedServerBridge.LOOPBACK_HOST, bridge.boundPortForVerification());
				 BufferedReader reader = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8))) {
				socket.setSoTimeout(2_000);
				authenticate(socket, reader, codec, secret, launchId, "hello-launch-2");
				assertTrue(bridge.authenticatedSessionGeneration() > firstGeneration,
						"replacement authentication advances the bridge session generation");
			}
		} catch (Exception exception) {
			throw new AssertionError("launch identity bridge verification failed", exception);
		} finally {
			if (bridge != null) bridge.close();
			deleteIfExists(secretFile);
		}
	}

	private static void verifyReplacementHandshakeDrainsPreviousDisconnect() {
		MultiplexedServerBridge bridge = null;
		Path secretFile = null;
		try {
			String secret = "0123456789abcdef0123456789abcdef";
			secretFile = Files.createTempFile("arena-agents-replacement-disconnect-", ".txt");
			Files.writeString(secretFile, secret);
			CodexAgentManager manager = uninitializedManager();
			AgentRecord record = manager.registry().create("gpt-5.6-sol", "high", Optional.of("Disconnect"), 1_000L);
			manager.registry().setAutomaticProgress(record.agentId(), false, 1_001L);
			manager.registry().start(record.agentId(), "remain owned until cleanup", 1_002L);
			bridge = new MultiplexedServerBridge(manager, 0, secretFile);
			bridge.start();
			BridgeEnvelopeCodec codec = new BridgeEnvelopeCodec();
			try (Socket socket = new Socket(MultiplexedServerBridge.LOOPBACK_HOST, bridge.boundPortForVerification());
				 BufferedReader reader = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8))) {
				socket.setSoTimeout(2_000);
				authenticate(socket, reader, codec, secret, null, "hello-before-replacement");
			}

			MultiplexedServerBridge activeBridge = bridge;
			awaitCondition(activeBridge::coordinatorDisconnectPendingForVerification,
					"previous authenticated session schedules coordinator cleanup");
			assertEquals(AgentLifecycleState.STARTING, manager.registry().require(record.agentId()).state(),
					"pending cleanup leaves the previous lifecycle active");
			assertEquals(0L, coordinatorGeneration(bridge),
					"pending cleanup has not fenced coordinator-owned actions early");

			CountDownLatch replacementSnapshot = new CountDownLatch(1);
			CountDownLatch replacementCommitted = new CountDownLatch(1);
			AtomicBoolean cleanupVisibleAtCommit = new AtomicBoolean();
			AtomicInteger commitCount = new AtomicInteger();
			bridge.setHandshakeSnapshotHookForVerification(replacementSnapshot::countDown);
			bridge.setHandshakeCommittedHookForVerification(ignored -> {
				commitCount.incrementAndGet();
				cleanupVisibleAtCommit.set(
						manager.registry().require(record.agentId()).state() == AgentLifecycleState.DISCONNECTED
								&& coordinatorGeneration(activeBridge) == 1L
				);
				replacementCommitted.countDown();
			});

			try (Socket socket = new Socket(MultiplexedServerBridge.LOOPBACK_HOST, bridge.boundPortForVerification());
				 BufferedReader reader = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8))) {
				socket.setSoTimeout(2_000);
				writeHello(socket, reader, codec, secret, null, "hello-replacement-disconnect-race");
				awaitLatch(replacementSnapshot, "replacement handshake captures its registry snapshot");
				assertTrue(!replacementCommitted.await(100L, java.util.concurrent.TimeUnit.MILLISECONDS),
						"replacement handshake waits for the previous disconnect cleanup");
				assertTrue(activeBridge.coordinatorDisconnectPendingForVerification(),
						"replacement handshake preserves the previous disconnect marker");

				bridge.tick();
				awaitLatch(replacementCommitted, "replacement handshake commits after disconnect cleanup");
				assertEquals("hello_ack", codec.decode(reader.readLine()).type(),
						"replacement handshake acknowledges after cleanup");
				assertTrue(cleanupVisibleAtCommit.get(),
						"lifecycle and action cleanup complete before replacement commit");
				assertEquals(1, commitCount.get(), "replacement handshake commits once");
				assertEquals(AgentLifecycleState.DISCONNECTED, manager.registry().require(record.agentId()).state(),
						"previous active lifecycle disconnects exactly once");
				assertEquals(1L, coordinatorGeneration(bridge),
						"previous coordinator action ownership is fenced exactly once");

				bridge.tick();
				assertEquals(1, commitCount.get(), "later ticks do not recommit the replacement handshake");
				assertEquals(1L, coordinatorGeneration(bridge),
						"later ticks do not repeat previous coordinator cleanup");
			}
		} catch (Exception exception) {
			throw new AssertionError("replacement handshake disconnect cleanup verification failed", exception);
		} finally {
			if (bridge != null) bridge.close();
			deleteIfExists(secretFile);
		}
	}

	private static void verifyHandshakeSnapshotAvoidsRegistryLockInversion() {
		MultiplexedServerBridge bridge = null;
		Path secretFile = null;
		CountDownLatch releaseSnapshot = new CountDownLatch(1);
		try {
			String secret = "0123456789abcdef0123456789abcdef";
			secretFile = Files.createTempFile("arena-agents-handshake-lock-order-", ".txt");
			Files.writeString(secretFile, secret);
			CodexAgentManager manager = uninitializedManager();
			manager.registry().create("gpt-5.6-sol", "high", Optional.of("LockOrder"), 1_000L);
			bridge = new MultiplexedServerBridge(manager, 0, secretFile);
			CountDownLatch snapshotCaptured = new CountDownLatch(1);
			bridge.setHandshakeSnapshotHookForVerification(() -> {
				snapshotCaptured.countDown();
				awaitLatch(releaseSnapshot, "lock-order handshake snapshot released");
			});
			bridge.start();
			BridgeEnvelopeCodec codec = new BridgeEnvelopeCodec();
			try (Socket socket = new Socket(MultiplexedServerBridge.LOOPBACK_HOST, bridge.boundPortForVerification());
				 BufferedReader reader = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8))) {
				socket.setSoTimeout(2_000);
				writeHello(socket, reader, codec, secret, null, "hello-lock-order");
				awaitLatch(snapshotCaptured, "handshake captures registry state before publication");
				synchronized (manager.registry()) {
					releaseSnapshot.countDown();
					assertEquals("hello_ack", codec.decode(reader.readLine()).type(),
							"handshake publishes without reacquiring the registry under publicationLock");
				}
				assertTrue(bridge.authenticated(), "lock-order handshake commits while the registry monitor is held");
			}
		} catch (Exception exception) {
			throw new AssertionError("handshake registry lock-order verification failed", exception);
		} finally {
			releaseSnapshot.countDown();
			if (bridge != null) bridge.close();
			deleteIfExists(secretFile);
		}
	}

	private static void verifyHandshakeResnapshotsLifecycleRaces() {
		verifyRemovalDuringHandshakeResnapshots();
		verifyTransitionDuringHandshakePublishesOnce();
	}

	private static void verifyHandshakeSnapshotDeadline() {
		MultiplexedServerBridge bridge = null;
		Path secretFile = null;
		try {
			String secret = "0123456789abcdef0123456789abcdef";
			secretFile = Files.createTempFile("arena-agents-handshake-deadline-", ".txt");
			Files.writeString(secretFile, secret);
			bridge = new MultiplexedServerBridge(uninitializedManager(), 0, secretFile);
			MultiplexedServerBridge activeBridge = bridge;
			AtomicBoolean churn = new AtomicBoolean(true);
			java.util.concurrent.atomic.AtomicInteger snapshots = new java.util.concurrent.atomic.AtomicInteger();
			bridge.setHandshakeSnapshotHookForVerification(() -> {
				if (!churn.get()) return;
				snapshots.incrementAndGet();
				activeBridge.withinPublicationBoundary(() -> null);
			});
			bridge.start();
			BridgeEnvelopeCodec codec = new BridgeEnvelopeCodec();
			try (Socket socket = new Socket(MultiplexedServerBridge.LOOPBACK_HOST, bridge.boundPortForVerification());
				 BufferedReader reader = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8))) {
				socket.setSoTimeout(8_000);
				writeHello(socket, reader, codec, secret, null, "hello-deadline-churn");
				assertTrue(reader.readLine() == null, "snapshot churn closes the session at the handshake deadline");
				assertTrue(snapshots.get() > 1, "snapshot churn retried before the handshake deadline");
			}
			awaitCondition(() -> !activeBridge.authenticated(), "expired handshake releases the bridge session");
		} catch (Exception exception) {
			throw new AssertionError("handshake snapshot deadline verification failed", exception);
		} finally {
			if (bridge != null) bridge.close();
			deleteIfExists(secretFile);
		}
	}

	private static void verifyRemovalDuringHandshakeResnapshots() {
		MultiplexedServerBridge bridge = null;
		Path secretFile = null;
		CountDownLatch releaseSnapshot = new CountDownLatch(1);
		try {
			String secret = "0123456789abcdef0123456789abcdef";
			secretFile = Files.createTempFile("arena-agents-removal-handshake-", ".txt");
			Files.writeString(secretFile, secret);
			CodexAgentManager manager = uninitializedManager();
			AgentRecord record = manager.registry().create("gpt-5.6-sol", "high", Optional.of("Removed"), 1_000L);
			bridge = new MultiplexedServerBridge(manager, 0, secretFile);
			AtomicBoolean firstSnapshot = new AtomicBoolean(true);
			CountDownLatch snapshotTaken = new CountDownLatch(1);
			bridge.setHandshakeSnapshotHookForVerification(() -> {
				if (!firstSnapshot.compareAndSet(true, false)) return;
				snapshotTaken.countDown();
				awaitLatch(releaseSnapshot, "removal handshake snapshot released");
			});
			bridge.start();
			BridgeEnvelopeCodec codec = new BridgeEnvelopeCodec();
			try (Socket socket = new Socket(MultiplexedServerBridge.LOOPBACK_HOST, bridge.boundPortForVerification());
				 BufferedReader reader = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8))) {
				socket.setSoTimeout(2_000);
				writeHello(socket, reader, codec, secret, null, "hello-removal-race");
				awaitLatch(snapshotTaken, "handshake captured the pre-removal registry");
				MultiplexedServerBridge activeBridge = bridge;
				AtomicBoolean managerRemovalBoundaryUsed = new AtomicBoolean();
				manager.setRuntimeHooks(new AgentRuntimeHooks() {
					@Override
					public <T> T withinPublicationBoundary(java.util.function.Supplier<T> publication) {
						managerRemovalBoundaryUsed.set(true);
						return activeBridge.withinPublicationBoundary(publication);
					}

					@Override
					public void onRemoved(AgentId agentId, long terminalRevision) {
						activeBridge.onRemoved(agentId, terminalRevision);
					}
				});
				manager.remove(record.agentId().toString());
				assertTrue(managerRemovalBoundaryUsed.get(),
						"manager removal enters the bridge publication boundary before onRemoved");
				releaseSnapshot.countDown();
				BridgeEnvelope acknowledgement = codec.decode(reader.readLine());
				assertEquals(0, acknowledgement.payload().getAsJsonArray("registry").size(),
						"removal during authentication retries the snapshot without closing the candidate session");
				assertTrue(bridge.authenticated(), "removal race leaves the resnapshotted session authenticated");
			}
		} catch (Exception exception) {
			throw new AssertionError("removal handshake race verification failed", exception);
		} finally {
			releaseSnapshot.countDown();
			if (bridge != null) bridge.close();
			deleteIfExists(secretFile);
		}
	}

	private static void verifyTransitionDuringHandshakePublishesOnce() {
		MultiplexedServerBridge bridge = null;
		Path secretFile = null;
		CountDownLatch releaseSnapshot = new CountDownLatch(1);
		try {
			String secret = "0123456789abcdef0123456789abcdef";
			secretFile = Files.createTempFile("arena-agents-transition-handshake-", ".txt");
			Files.writeString(secretFile, secret);
			CodexAgentManager manager = uninitializedManager();
			AgentRecord record = manager.registry().create("gpt-5.6-sol", "high", Optional.of("Starting"), 1_000L);
			bridge = new MultiplexedServerBridge(manager, 0, secretFile);
			AtomicBoolean firstSnapshot = new AtomicBoolean(true);
			CountDownLatch snapshotTaken = new CountDownLatch(1);
			bridge.setHandshakeSnapshotHookForVerification(() -> {
				if (!firstSnapshot.compareAndSet(true, false)) return;
				snapshotTaken.countDown();
				awaitLatch(releaseSnapshot, "transition handshake snapshot released");
			});
			bridge.start();
			BridgeEnvelopeCodec codec = new BridgeEnvelopeCodec();
			try (Socket socket = new Socket(MultiplexedServerBridge.LOOPBACK_HOST, bridge.boundPortForVerification());
				 BufferedReader reader = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8))) {
				socket.setSoTimeout(2_000);
				writeHello(socket, reader, codec, secret, null, "hello-transition-race");
				awaitLatch(snapshotTaken, "handshake captured the pre-transition registry");
				AgentTransition transition = manager.registry().start(record.agentId(), "publish exactly once", 1_001L);
				bridge.onTransition(transition);
				releaseSnapshot.countDown();
				BridgeEnvelope acknowledgement = codec.decode(reader.readLine());
				JsonObject published = acknowledgement.payload().getAsJsonArray("registry").get(0).getAsJsonObject();
				assertEquals(AgentLifecycleState.STARTING.name(), published.get("state").getAsString(),
						"transition during authentication is folded into the retried handshake snapshot");
				assertEquals("verbose_control", codec.decode(reader.readLine()).type(),
						"transition fixture consumes the handshake verbose-control frame");
				socket.setSoTimeout(150);
				try {
					reader.readLine();
					throw new AssertionError("transition race published a duplicate lifecycle frame");
				} catch (SocketTimeoutException expected) {
					assertTrue(true, "transition race emits no duplicate queue or start frame after hello_ack");
				}
			}
		} catch (Exception exception) {
			throw new AssertionError("transition handshake race verification failed", exception);
		} finally {
			releaseSnapshot.countDown();
			if (bridge != null) bridge.close();
			deleteIfExists(secretFile);
		}
	}

	private static void verifyImmediateHandshakeClosePreservesDisconnect() {
		MultiplexedServerBridge bridge = null;
		Path secretFile = null;
		try {
			String secret = "0123456789abcdef0123456789abcdef";
			secretFile = Files.createTempFile("arena-agents-immediate-close-", ".txt");
			Files.writeString(secretFile, secret);
			bridge = new MultiplexedServerBridge(uninitializedManager(), 0, secretFile);
			bridge.setHandshakeCommittedHookForVerification(session -> {
				try {
					session.close();
				} catch (Exception exception) {
					throw new AssertionError(exception);
				}
			});
			bridge.start();
			BridgeEnvelopeCodec codec = new BridgeEnvelopeCodec();
			try (Socket socket = new Socket(MultiplexedServerBridge.LOOPBACK_HOST, bridge.boundPortForVerification());
				 BufferedReader reader = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8))) {
				writeHello(socket, reader, codec, secret, null, "hello-immediate-close");
				MultiplexedServerBridge activeBridge = bridge;
				awaitCondition(activeBridge::coordinatorDisconnectPendingForVerification,
						"session close immediately after authentication preserves the pending disconnect");
			}
		} catch (Exception exception) {
			throw new AssertionError("immediate handshake close verification failed", exception);
		} finally {
			if (bridge != null) bridge.close();
			deleteIfExists(secretFile);
		}
	}

	private static void verifyPendingRegistrationMarkerIsFenced() {
		try {
			CodexAgentManager manager = uninitializedManager();
			AgentRecord record = manager.registry().create("gpt-5.6-sol", "high", Optional.of("Pending"), 1_000L);
			Set<AgentId> pending = pendingRegistrations(manager);
			pending.add(record.agentId());
			assertTrue(MultiplexedServerBridge.registeredObservationIds(manager.coordinatorVisibleRecords()).isEmpty(),
					"pending registrations stay out of observation scheduling");
			AtomicBoolean inBoundary = new AtomicBoolean();
			AtomicBoolean removedInsideBoundary = new AtomicBoolean();
			manager.setRuntimeHooks(new AgentRuntimeHooks() {
				@Override
				public boolean onCreated(AgentRecord created) {
					assertTrue(inBoundary.get(), "registration hook runs inside the publication boundary");
					return true;
				}

				@Override
				public <T> T withinPublicationBoundary(java.util.function.Supplier<T> publication) {
					inBoundary.set(true);
					try {
						T result = publication.get();
						removedInsideBoundary.set(!pending.contains(record.agentId()));
						return result;
					} finally {
						inBoundary.set(false);
					}
				}
			});
			Method publish = CodexAgentManager.class.getDeclaredMethod("publishPendingRegistration", AgentRecord.class);
			publish.setAccessible(true);
			publish.invoke(manager, record);
			assertTrue(removedInsideBoundary.get(),
					"pending registration marker clears before the publication boundary opens");
			assertEquals(List.of(record), manager.coordinatorVisibleRecords(),
					"published registration becomes observation-eligible after the fenced commit");
		} catch (ReflectiveOperationException exception) {
			throw new AssertionError("pending registration fence verification failed", exception);
		}
	}

	private static void verifyCompletionResultFacts() {
		GoalCompletionVerifier.VerificationResult verification = new GoalCompletionVerifier.VerificationResult(
				false,
				7L,
				"PREDICATE_FAILED",
				List.of(
						new GoalEvidence.Fact("inventory_contains", false, "minecraft:iron_pickaxe x1", "minecraft:iron_pickaxe x0"),
						new GoalEvidence.Fact("position_within", true, "12.0,64.0,12.0 radius=2.0", "12.0,64.0,13.25 stableTicks=1")
				)
		);
		String goalFingerprint = "a".repeat(64);
		JsonObject payload = MultiplexedServerBridge.completionResultPayload(7L, "trace-java-1", goalFingerprint, verification);
		assertEquals(2, payload.getAsJsonArray("facts").size(), "completion result retains every verifier fact");
		assertEquals(goalFingerprint, payload.get("goalFingerprint").getAsString(), "completion result retains the immutable goal fingerprint");
		assertEquals("minecraft:iron_pickaxe x1", payload.getAsJsonArray("facts").get(0).getAsJsonObject().get("expectedValue").getAsString(), "completion result retains expected value");
		assertEquals("minecraft:iron_pickaxe x0", payload.getAsJsonArray("facts").get(0).getAsJsonObject().get("observedValue").getAsString(), "completion result retains observed value");
		MultiplexedServerBridge.VerboseEvent rejected = invokeCompletionVerboseEvent(7L, verification);
		assertEquals(7L, rejected.goalRevision(), "rejected completion feedback retains the guarded revision");
		assertEquals("retry", rejected.stage(), "rejected completion feedback uses the Problem stage exactly once");
		assertEquals("Goal not complete: expected minecraft:iron_pickaxe x1, observed minecraft:iron_pickaxe x0. Continuing.", rejected.message(),
				"rejected completion feedback explains that work will continue");
		GoalCompletionVerifier.VerificationResult verified = new GoalCompletionVerifier.VerificationResult(
				true, 7L, "VERIFIED", List.of()
		);
		MultiplexedServerBridge.VerboseEvent completed = invokeCompletionVerboseEvent(7L, verified);
		assertEquals(7L, completed.goalRevision(), "successful completion feedback retains the guarded revision");
		assertEquals("result", completed.stage(), "successful completion feedback uses Result instead of Lifecycle");
		assertEquals("Goal verified.", completed.message(), "successful completion feedback is concise");
	}

	private static void verifyTraceWireValidation() {
		AgentId agent = AgentId.parse("00000000-0000-0000-0000-000000000001");
		String traceId = "trace-java-1";
		ActionProvenance provenance = new ActionProvenance(
				"codex", "gpt-5.6-sol", "high", "priority", "program-1-1", 1L, "step-1", 1L
		);
		ActionProvenance tracedProvenance = new ActionProvenance(
				"codex", "gpt-5.6-sol", "high", "priority", "program-1-1", 1L, "step-1", 1L, traceId
		);
		JsonObject payload = new JsonObject();
		payload.addProperty("traceId", traceId);
		payload.addProperty("goalRevision", 1L);
		payload.addProperty("actionId", "action-1");
		payload.addProperty("actionType", "wait");
		JsonObject arguments = new JsonObject();
		arguments.addProperty("durationMs", 1L);
		payload.add("arguments", arguments);
		JsonObject wireProvenance = new JsonObject();
		wireProvenance.addProperty("provider", "codex");
		wireProvenance.addProperty("model", "gpt-5.6-sol");
		wireProvenance.addProperty("reasoningEffort", "high");
		wireProvenance.addProperty("serviceTier", "priority");
		wireProvenance.addProperty("programId", "program-1-1");
		wireProvenance.addProperty("programVersion", 1L);
		wireProvenance.addProperty("sourceStepId", "step-1");
		wireProvenance.addProperty("eventSequence", 1L);
		wireProvenance.addProperty("traceId", traceId);
		wireProvenance.addProperty("watcherId", "watcher-0");
		payload.add("provenance", wireProvenance);
		ServerActionRequest request = MultiplexedServerBridge.decodeActionRequest(
				new BridgeEnvelope(2, "server-instance", agent.toString(), "action_command", "message-1", payload)
		);
		assertEquals(traceId, request.traceId(), "action request retains the trace ID");
		assertEquals("watcher-0", request.provenance().watcherId(), "action request retains watcher provenance");
		ServerActionProgress progress = new ServerActionProgress(agent, 1L, "action-1", ActionType.WAIT, traceId, 0.5D, 1L, 2L);
		ServerActionResult result = new ServerActionResult(agent, 1L, "action-1", ActionType.WAIT, traceId, ServerActionState.SUCCEEDED, "DONE", "", 2L, 3L);
		assertEquals(traceId, progress.traceId(), "first progress retains the action trace ID");
		assertEquals(traceId, result.traceId(), "terminal result retains the action trace ID");
		ServerActionObservation evidence = new ServerActionObservation(
				42L, 1_750_000_000_250L,
				new ServerActionObservation.Position(1.0D, 64.0D, 2.0D),
				null, 0.0D, 0.0D, null,
				new ServerActionObservation.RayTarget("block", new ServerActionObservation.Position(2.0D, 64.0D, 2.0D), "minecraft:oak_log", "north", 3.0D),
				new ServerActionObservation.Reach(3.0D, 4.5D, true),
				new ServerActionObservation.Target("block", new ServerActionObservation.Position(2.0D, 64.0D, 2.0D), "minecraft:oak_log", "minecraft:oak_log", "minecraft:oak_log", "minecraft:oak_log", false, null, null, null),
				new ServerActionObservation.Progress(0.25D, "block_damage", true)
		);
		JsonObject evidenceWire = invokeActionObservationPayload(evidence);
		assertEquals(42L, evidenceWire.get("worldTick").getAsLong(), "action evidence preserves the server world tick");
		assertEquals("minecraft:oak_log", evidenceWire.getAsJsonObject("lookedAt").get("id").getAsString(), "action evidence preserves the server ray target");
		assertEquals("block_damage", evidenceWire.getAsJsonObject("progress").get("basis").getAsString(), "action evidence preserves the factual progress basis");
		assertThrows(IllegalArgumentException.class, () -> new ServerActionRequest(agent, 1L, "action-1", ActionType.WAIT, arguments, tracedProvenance, "trace-other"),
				"direct request construction rejects mismatched top-level and provenance traces");
		ServerActionRequest legacy = new ServerActionRequest(agent, 1L, "action-legacy", ActionType.WAIT, arguments, provenance);
		assertThrowsCode(() -> new ServerActionExecutor(uninitializedManager(), ignored -> { }).submitProgramPrimitive(legacy), "MISSING_TRACE_ID");
		JsonObject mismatched = payload.deepCopy();
		mismatched.getAsJsonObject("provenance").addProperty("traceId", "trace-other");
		assertThrows(BridgeProtocolException.class, () -> MultiplexedServerBridge.decodeActionRequest(
				new BridgeEnvelope(2, "server-instance", agent.toString(), "action_command", "message-mismatch", mismatched)),
				"mismatched wire trace IDs fail closed");
		assertThrows(IllegalArgumentException.class, () -> new ServerActionRequest(agent, 1L, "action-1", ActionType.WAIT, arguments, provenance, ""), "blank trace ID is typed validation");
		assertThrows(IllegalArgumentException.class, () -> new ServerActionRequest(agent, 1L, "action-1", ActionType.WAIT, arguments, provenance, "🙂".repeat(40)), "overlong UTF-8 trace ID is typed validation");
	}

	private static void verifyAtomicConversationWakePublication() {
		MultiplexedServerBridge bridge = null;
		Path secretFile = null;
		try {
			String secret = "0123456789abcdef0123456789abcdef";
			secretFile = Files.createTempFile("arena-agents-conversation-secret-", ".txt");
			Files.writeString(secretFile, secret);
			CodexAgentManager manager = uninitializedManager();
			AgentRecord idle = manager.registry().create("gpt-5.6-sol", "high", Optional.of("WakeTarget"), 1_000L);
			ConversationEvent event = new ConversationEvent(
					idle.agentId(), "00000000-0000-0000-0000-000000000099", idle.agentId().toString(),
					ConversationAudience.DIRECT, ConversationKind.PLAYER_MESSAGE, "Can you respond?",
					0L, 1_001L, 1L, "minecraft:overworld"
			);
			bridge = new MultiplexedServerBridge(manager, 0, secretFile);
			MultiplexedServerBridge activeBridge = bridge;
			dev.agaminggod.arenaagents.agent.AgentDomainException disconnected = assertThrowsDomain(
					() -> activeBridge.publishConversationEvent(event, Optional.of(testGoal("Respond to the player message."))),
					"COORDINATOR_DISCONNECTED"
			);
			assertEquals(
					"AI agent coordinator is offline; check logs/arena-agents-coordinator-error.log for the startup cause",
					disconnected.getMessage(),
					"disconnected delivery reports the actual coordinator state instead of guessing authentication"
			);
			assertEquals(idle, manager.registry().require(idle.agentId()),
					"disconnected conversation publication leaves the exact idle record");

			bridge.start();
			BridgeEnvelopeCodec codec = new BridgeEnvelopeCodec();
			String transactionId;
			try (Socket socket = new Socket(MultiplexedServerBridge.LOOPBACK_HOST, bridge.boundPortForVerification());
				 BufferedReader reader = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8))) {
				socket.setSoTimeout(2_000);
				BridgeEnvelope helloAck = authenticate(socket, reader, codec, secret, null, "hello-atomic-wake");
				JsonObject ready = new JsonObject();
				ready.addProperty("goalRevision", idle.goalRevision());
				writeEnvelope(socket, codec, new BridgeEnvelope(
						2, helloAck.serverInstanceId(), idle.agentId().toString(), "agent_ready", "ready-before-atomic-wake", ready
				));
				awaitCondition(() -> {
					activeBridge.tick();
					return activeBridge.coordinatorReadyForVerification(idle.agentId());
				}, "conversation fixture acknowledges coordinator readiness before publishing a wake");

				bridge.publishConversationEvent(event, Optional.of(testGoal("Respond to the player message.")));
				BridgeEnvelope wake = pollBridgeResponseOfType(
						bridge, socket, reader, codec, "conversation_wake", null);
				assertEquals("conversation_wake", wake.type(), "conversation and lifecycle start cross the wire as one transaction");
				transactionId = wake.payload().get("transactionId").getAsString();
				assertEquals(
						manager.pendingConversationWakes().getFirst(),
						new PendingConversationWakeCodec().decode(
								new PendingConversationWakeCodec().encode(manager.pendingConversationWakes().getFirst())
						),
						"durable conversation wake round-trips without losing transaction identity or message context"
				);
				PendingGoalDraft draft = new PendingGoalDraft(
						UUID.fromString("00000000-0000-0000-0000-000000000201"),
						idle.agentId(),
						UUID.fromString("00000000-0000-0000-0000-000000000099"),
						"Get a good pickaxe",
						Optional.of(new GoalPredicate.InventoryContains("minecraft:iron_pickaxe", 1)),
						DraftIntent.REPLACE_OR_QUEUE,
						1_002L,
						1L,
						manager.registry().require(idle.agentId()).currentGoal().map(dev.agaminggod.arenaagents.agent.AgentGoal::goalId)
				);
				manager.stageGoalDraft(draft);
				assertThrowsCode(() -> manager.stageGoalDraft(draft), "DUPLICATE_GOAL_DRAFT");
				AgentSavedData restored = roundTripSavedData(savedData(manager));
				assertEquals(manager.pendingConversationWakes(), restored.conversationWakes(),
						"Minecraft SavedData round-trip retains the durable wake outbox");
				assertEquals(List.of(draft), restored.goalDrafts(),
						"Minecraft SavedData round-trip retains the clarification draft");
				assertEquals(manager.registry().require(idle.agentId()).currentGoal(),
						restored.registry().require(idle.agentId()).currentGoal(),
						"clarification draft persistence leaves the active goal unchanged");
				assertEquals(AgentLifecycleState.STARTING, restored.registry().require(idle.agentId()).state(),
						"Minecraft SavedData restore re-arms the pending wake at the same lifecycle boundary");
				assertEquals(1L, restored.registry().require(idle.agentId()).goalRevision(),
						"Minecraft SavedData restore preserves the pending wake revision");
				JsonObject conversation = wake.payload().getAsJsonObject("event");
				JsonObject control = wake.payload().getAsJsonObject("control");
				assertEquals(0L, conversation.get("goalRevision").getAsLong(), "conversation event retains the prior goal revision");
				assertEquals("start", control.get("operation").getAsString(), "conversation wake publishes a start operation");
				assertEquals(1L, control.get("goalRevision").getAsLong(), "conversation wake control advances the goal revision once");
				assertEquals("Respond to the player message.", control.get("goal").getAsString(), "conversation wake carries the generated response goal");
				assertEquals(AgentLifecycleState.STARTING, manager.registry().require(idle.agentId()).state(),
						"durable publication commits STARTING only after the transaction is staged");
			}
			MultiplexedServerBridge activeBridgeAfterDisconnect = bridge;
			awaitCondition(() -> !activeBridgeAfterDisconnect.observationPublicationForVerification().hasActiveSession(),
					"unacknowledged wake fixture observes its failed connection");
			bridge.tick();
			assertEquals(AgentLifecycleState.STARTING, manager.registry().require(idle.agentId()).state(),
					"an unacknowledged wake remains armed across coordinator disconnect");
			assertEquals(1L, manager.registry().require(idle.agentId()).goalRevision(),
					"disconnect recovery does not manufacture another goal revision");

			try (Socket socket = new Socket(MultiplexedServerBridge.LOOPBACK_HOST, bridge.boundPortForVerification());
				 BufferedReader reader = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8))) {
				socket.setSoTimeout(2_000);
				BridgeEnvelope helloAck = authenticate(socket, reader, codec, secret, null, "hello-replay-wake");
				BridgeEnvelope replay = codec.decode(reader.readLine());
				assertEquals("conversation_wake", replay.type(), "unacknowledged conversation wake is replayed after reconnect");
				assertEquals(transactionId, replay.payload().get("transactionId").getAsString(),
						"replay retains the stable transaction identity");
				assertEquals(1L, replay.payload().getAsJsonObject("control").get("goalRevision").getAsLong(),
						"replay retains the original goal revision");
				JsonObject acknowledgement = new JsonObject();
				acknowledgement.addProperty("transactionId", transactionId);
				acknowledgement.addProperty("goalRevision", 1L);
				socket.getOutputStream().write(codec.encode(new BridgeEnvelope(
						2, helloAck.serverInstanceId(), idle.agentId().toString(), "conversation_wake_ack",
						"ack-replayed-wake", acknowledgement
				)).getBytes(StandardCharsets.UTF_8));
				socket.getOutputStream().flush();
				MultiplexedServerBridge activeBridgeAfterAck = bridge;
				awaitCondition(() -> {
					activeBridgeAfterAck.tick();
					return manager.pendingConversationWakes().getFirst().acknowledged();
				}, "matching coordinator acknowledgement is persisted");
				assertEquals(1, manager.pendingConversationWakes().size(),
						"acknowledgement retains replay context until the wake goal is terminal");
				manager.registry().satisfyGoal(
						idle.agentId(),
						1L,
						new GoalEvidence(System.currentTimeMillis(), "operator_confirmed", List.of()),
						System.currentTimeMillis()
				);
				assertTrue(manager.pendingConversationWakes().isEmpty(),
						"terminal wake goal clears its durable replay context");
			}
		} catch (Exception exception) {
			throw new AssertionError("atomic conversation wake publication failed", exception);
		} finally {
			if (bridge != null) bridge.close();
			if (secretFile != null) {
				try {
					Files.deleteIfExists(secretFile);
				} catch (java.io.IOException exception) {
					throw new AssertionError("could not remove temporary conversation bridge secret", exception);
				}
			}
		}
	}

	private static void verifyAtomicPublicationRacesSessionClose() {
		MultiplexedServerBridge bridge = null;
		Path secretFile = null;
		try {
			String secret = "0123456789abcdef0123456789abcdef";
			secretFile = Files.createTempFile("arena-agents-publication-close-race-", ".txt");
			Files.writeString(secretFile, secret);
			bridge = new MultiplexedServerBridge(uninitializedManager(), 0, secretFile);
			MultiplexedServerBridge activeBridge = bridge;
			bridge.start();
			BridgeEnvelopeCodec codec = new BridgeEnvelopeCodec();
			try (Socket socket = new Socket(MultiplexedServerBridge.LOOPBACK_HOST, bridge.boundPortForVerification());
				 BufferedReader reader = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8))) {
				socket.setSoTimeout(2_000);
				authenticate(socket, reader, codec, secret, null, "hello-publication-close-race");
				Object session = readPrivateField(bridge, "session");
				Object publicationLock = readPrivateField(bridge, "publicationLock");
				Method enqueuePair = session.getClass().getDeclaredMethod(
						"enqueuePair", BridgeEnvelope.class, BridgeEnvelope.class, Runnable.class);
				enqueuePair.setAccessible(true);
				BridgeEnvelope pairFirst = new BridgeEnvelope(
						2, "server-instance", "server", "heartbeat", "paired-rollback-first", new JsonObject());
				BridgeEnvelope pairSecond = new BridgeEnvelope(
						2, "server-instance", "server", "heartbeat", "paired-rollback-second", new JsonObject());
				try {
					enqueuePair.invoke(session, pairFirst, pairSecond,
							(Runnable) () -> { throw new IllegalStateException("paired callback failed"); });
					throw new AssertionError("failed paired callback was accepted");
				} catch (java.lang.reflect.InvocationTargetException exception) {
					assertTrue(exception.getCause() instanceof IllegalStateException,
							"paired callback failure is returned to the transaction owner");
				}
				@SuppressWarnings("unchecked")
				java.util.concurrent.ArrayBlockingQueue<BridgeEnvelope> outbound =
						(java.util.concurrent.ArrayBlockingQueue<BridgeEnvelope>) readPrivateField(session, "outbound");
				assertTrue(outbound.stream().noneMatch(envelope -> envelope.messageId().startsWith("paired-rollback-")),
						"failed paired callback publishes neither envelope");
				Method enqueueAtomically = session.getClass().getDeclaredMethod("enqueueAtomically", BridgeEnvelope.class, Runnable.class);
				enqueueAtomically.setAccessible(true);
				BridgeEnvelope envelope = new BridgeEnvelope(2, "server-instance", "server", "heartbeat", "publication-close-race", new JsonObject());
				CountDownLatch callbackEntered = new CountDownLatch(1);
				java.util.concurrent.atomic.AtomicReference<Throwable> enqueueFailure = new java.util.concurrent.atomic.AtomicReference<>();
				Thread enqueuer;
				Thread closer;
				synchronized (publicationLock) {
					enqueuer = Thread.ofPlatform().daemon().start(() -> {
						try {
							enqueueAtomically.invoke(session, envelope, (Runnable) () -> {
								callbackEntered.countDown();
								activeBridge.withinPublicationBoundary(() -> null);
							});
						} catch (java.lang.reflect.InvocationTargetException exception) {
							enqueueFailure.set(exception.getCause());
						} catch (ReflectiveOperationException exception) {
							enqueueFailure.set(exception);
						}
					});
					closer = Thread.ofPlatform().daemon().start(() -> sessionClose(session, enqueueFailure));
					assertTrue(!callbackEntered.await(100L, java.util.concurrent.TimeUnit.MILLISECONDS),
							"atomic enqueue acquires publicationLock before entering its transition callback");
				}
				enqueuer.join(2_000L);
				closer.join(2_000L);
				assertTrue(!enqueuer.isAlive() && !closer.isAlive(),
						"atomic publication and session close complete without lock-order deadlock");
				Throwable failure = enqueueFailure.get();
				if (failure != null && !(failure instanceof BridgeProtocolException)) {
					throw new AssertionError("atomic publication failed with an unexpected exception", failure);
				}
			} finally {
				if (bridge != null) bridge.close();
			}
		} catch (Exception exception) {
			throw new AssertionError("atomic publication/session close race verification failed", exception);
		} finally {
			deleteIfExists(secretFile);
		}
	}

	private static void sessionClose(Object session, java.util.concurrent.atomic.AtomicReference<Throwable> failure) {
		try {
			Method close = session.getClass().getDeclaredMethod("close");
			close.setAccessible(true);
			close.invoke(session);
		} catch (java.lang.reflect.InvocationTargetException exception) {
			failure.compareAndSet(null, exception.getCause());
		} catch (ReflectiveOperationException exception) {
			failure.compareAndSet(null, exception);
		}
	}

	private static Object readPrivateField(Object owner, String fieldName) throws ReflectiveOperationException {
		Field field = owner.getClass().getDeclaredField(fieldName);
		field.setAccessible(true);
		return field.get(owner);
	}

	private static long coordinatorGeneration(MultiplexedServerBridge bridge) {
		try {
			Object actionExecutor = readPrivateField(bridge, "actionExecutor");
			Field field = actionExecutor.getClass().getDeclaredField("coordinatorGeneration");
			field.setAccessible(true);
			return field.getLong(actionExecutor);
		} catch (ReflectiveOperationException exception) {
			throw new AssertionError("could not read coordinator action generation", exception);
		}
	}

	private static void verifyConversationAttention(AgentId agentId) {
		MultiplexedServerBridge.PublishedObservationState state =
				new MultiplexedServerBridge.PublishedObservationState(AgentConstants.DEFAULT_AGENT_LIMIT);
		JsonObject observation = new JsonObject();
		observation.addProperty("observedAtEpochMs", 1_000L);
		assertTrue(state.markAttention(agentId), "conversation attention is retained for publication");
		var forced = state.delta(agentId, observation, 1L, 1_000L);
		assertTrue(forced.attention() && forced.changedFacts().isEmpty(),
				"conversation triggers attention without inventing factual changes");
		observation.addProperty("eventSequence", 1L);
		state.commit(agentId, observation);
		assertTrue(!state.delta(agentId, observation, 2L, 1_001L).attention(),
				"committed conversation attention is exact once");
	}

	private static void verifyDeathFacts() {
		AgentDeathSnapshot death = new AgentDeathSnapshot(
				"fell from a high place", "minecraft:the_nether", 12.5D, 64.0D, -3.5D,
				Optional.of("minecraft:overworld"), Optional.of(100.5D), Optional.of(70.0D), Optional.of(-20.5D),
				Optional.of(37.5F), Optional.of(-12.25F), Optional.of(true), "spectator", 2_000L
		);
		JsonObject facts = MultiplexedServerBridge.deathFacts(death);
		assertEquals("minecraft:overworld", facts.get("respawnDimensionId").getAsString(), "death facts expose respawn dimension");
		assertEquals(100.5D, facts.get("respawnX").getAsDouble(), "death facts expose respawn x");
		assertEquals(70.0D, facts.get("respawnY").getAsDouble(), "death facts expose respawn y");
		assertEquals(-20.5D, facts.get("respawnZ").getAsDouble(), "death facts expose respawn z");
		assertEquals(37.5F, facts.get("respawnYaw").getAsFloat(), "death facts expose respawn yaw");
		assertEquals(-12.25F, facts.get("respawnPitch").getAsFloat(), "death facts expose respawn pitch");
		assertEquals(true, facts.get("respawnForced").getAsBoolean(), "death facts expose forced respawn flag");
		assertEquals("spectator", facts.get("gameMode").getAsString(), "death facts expose game mode");
		AgentDeathSnapshot noConfiguredRespawn = new AgentDeathSnapshot(
				"fell from a high place", "minecraft:overworld", 12.5D, 64.0D, -3.5D,
				Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(),
				Optional.empty(), Optional.empty(), Optional.empty(), "survival", 2_001L
		);
		JsonObject envelopePayload = new JsonObject();
		envelopePayload.add("death", MultiplexedServerBridge.deathFacts(noConfiguredRespawn));
		BridgeEnvelope roundTrip = new BridgeEnvelopeCodec().decode(new BridgeEnvelopeCodec().encode(
				new BridgeEnvelope(2, "server-instance", "server", "hello_ack", "death-null-check", envelopePayload)
		));
		JsonObject encodedDeath = roundTrip.payload().getAsJsonObject("death");
		for (String field : List.of("respawnDimensionId", "respawnX", "respawnY", "respawnZ", "respawnYaw", "respawnPitch", "respawnForced")) {
			assertTrue(encodedDeath.has(field) && encodedDeath.get(field).isJsonNull(),
					"encoded death facts retain explicit null " + field);
		}
	}

	private static void verifyRealBridgeSessionLifecycle() {
		MultiplexedServerBridge bridge = null;
		Path secretFile = null;
		try {
			String secret = "0123456789abcdef0123456789abcdef";
			secretFile = Files.createTempFile("arena-agents-bridge-secret-", ".txt");
			Files.writeString(secretFile, secret);
			bridge = new MultiplexedServerBridge(uninitializedManager(), 0, secretFile);
			bridge.start();
			MultiplexedServerBridge activeBridge = bridge;
			assertTrue(!activeBridge.observationPublicationForVerification().hasActiveSession(),
					"bridge starts without an accepted session");
			try (Socket unauthenticated = new Socket(
					MultiplexedServerBridge.LOOPBACK_HOST, activeBridge.boundPortForVerification())) {
				awaitPreauthSessionCount(activeBridge, 1,
						"silent candidate is admitted before the authenticated connection starts");
				assertTrue(!activeBridge.observationPublicationForVerification().hasActiveSession(),
						"an unauthenticated socket cannot claim the primary publication session");
				try (Socket authenticated = new Socket(
						MultiplexedServerBridge.LOOPBACK_HOST, activeBridge.boundPortForVerification());
					 BufferedReader reader = new BufferedReader(new InputStreamReader(
							 authenticated.getInputStream(), StandardCharsets.UTF_8))) {
					authenticate(authenticated, reader, new BridgeEnvelopeCodec(), secret, null,
							"hello-after-silent-candidate");
					awaitCondition(activeBridge.observationPublicationForVerification()::hasActiveSession,
							"a valid coordinator authenticates while a silent candidate remains connected");
				}
			}
			awaitCondition(() -> !activeBridge.observationPublicationForVerification().hasActiveSession(),
					"session close deactivates publication and clears lifecycle ownership");
		} catch (Exception exception) {
			throw new AssertionError("real bridge session lifecycle failed", exception);
		} finally {
			if (bridge != null) bridge.close();
			if (secretFile != null) {
				try {
					Files.deleteIfExists(secretFile);
				} catch (java.io.IOException exception) {
					throw new AssertionError("could not remove temporary bridge secret", exception);
				}
			}
		}
	}

	private static void verifyPreauthOverflowPreservesIncumbentHandshake() {
		MultiplexedServerBridge bridge = null;
		Path secretFile = null;
		List<Socket> fillers = new ArrayList<>();
		try {
			String secret = "0123456789abcdef0123456789abcdef";
			secretFile = Files.createTempFile("arena-agents-preauth-overflow-secret-", ".txt");
			Files.writeString(secretFile, secret);
			bridge = new MultiplexedServerBridge(uninitializedManager(), 0, secretFile);
			bridge.start();
			MultiplexedServerBridge activeBridge = bridge;
			BridgeEnvelopeCodec codec = new BridgeEnvelopeCodec();
			try (Socket incumbent = new Socket(
					MultiplexedServerBridge.LOOPBACK_HOST, bridge.boundPortForVerification());
				 BufferedReader reader = new BufferedReader(new InputStreamReader(
						 incumbent.getInputStream(), StandardCharsets.UTF_8))) {
				incumbent.setSoTimeout(2_000);
				String messageId = "hello-preauth-incumbent";
				AuthenticationExchange exchange = beginAuthentication(
						incumbent, reader, codec, secret, messageId
				);
				for (int index = 1; index < 8; index++) {
					fillers.add(new Socket(
							MultiplexedServerBridge.LOOPBACK_HOST, bridge.boundPortForVerification()
					));
					awaitPreauthSessionCount(activeBridge, index + 1,
							"pre-authentication filler " + index + " is admitted before the next connection");
				}

				try (Socket overflow = new Socket(
						MultiplexedServerBridge.LOOPBACK_HOST, bridge.boundPortForVerification())) {
					overflow.setSoTimeout(2_000);
					assertEquals(-1, overflow.getInputStream().read(),
							"overflow arrival is closed instead of evicting an incumbent handshake");
				}
				assertEquals(8, preauthSessionCount(activeBridge),
						"overflow rejection leaves every incumbent pre-authentication session registered");

				writeAuthenticatedHello(incumbent, codec, secret, null, messageId, exchange);
				assertEquals("hello_ack", codec.decode(reader.readLine()).type(),
						"oldest incumbent completes authentication after overflow rejection");
				assertEquals("verbose_control", codec.decode(reader.readLine()).type(),
						"incumbent handshake replay remains intact after pool saturation");
				awaitCondition(activeBridge.observationPublicationForVerification()::hasActiveSession,
						"incumbent becomes the active coordinator after saturation");
			}
		} catch (Exception exception) {
			throw new AssertionError("pre-authentication overflow verification failed", exception);
		} finally {
			for (Socket filler : fillers) {
				try {
					filler.close();
				} catch (IOException ignored) {
				}
			}
			if (bridge != null) bridge.close();
			deleteIfExists(secretFile);
		}
	}

	@SuppressWarnings("unchecked")
	private static int preauthSessionCount(MultiplexedServerBridge bridge) {
		try {
			Object publicationLock = readPrivateField(bridge, "publicationLock");
			synchronized (publicationLock) {
				return ((Set<Object>) readPrivateField(bridge, "preauthSessions")).size();
			}
		} catch (ReflectiveOperationException exception) {
			throw new AssertionError("could not inspect bounded pre-authentication sessions", exception);
		}
	}

	private static CodexAgentManager uninitializedManager() {
		try {
			Field field = sun.misc.Unsafe.class.getDeclaredField("theUnsafe");
			field.setAccessible(true);
			sun.misc.Unsafe unsafe = (sun.misc.Unsafe) field.get(null);
			CodexAgentManager manager = (CodexAgentManager) unsafe.allocateInstance(CodexAgentManager.class);
			TestMinecraftServer server = (TestMinecraftServer) unsafe.allocateInstance(TestMinecraftServer.class);
			server.playerList = (EmptyPlayerList) unsafe.allocateInstance(EmptyPlayerList.class);
			putObject(unsafe, manager, "server", server);
			Field savedData = CodexAgentManager.class.getDeclaredField("savedData");
			unsafe.putObject(manager, unsafe.objectFieldOffset(savedData), new AgentSavedData());
			putObject(unsafe, manager, "chunkTickets", new java.util.LinkedHashMap<>());
			putObject(unsafe, manager, "chunkTicketReferences", new java.util.LinkedHashMap<>());
			putObject(unsafe, manager, "pendingPlayerSpawns", new java.util.LinkedHashMap<>());
			putObject(unsafe, manager, "pendingVerifiedRespawns", new java.util.LinkedHashMap<>());
			putObject(unsafe, manager, "pendingAgentRegistrations", java.util.concurrent.ConcurrentHashMap.newKeySet());
			putObject(unsafe, manager, "pendingEntityRecoveries", new java.util.LinkedHashSet<>());
			putObject(unsafe, manager, "seenPlayers", new java.util.LinkedHashSet<>());
			putObject(unsafe, manager, "lastLocationPersistenceEpochMs", new java.util.LinkedHashMap<>());
			return manager;
		} catch (ReflectiveOperationException exception) {
			throw new AssertionError("could not allocate lifecycle-only manager", exception);
		}
	}

	@SuppressWarnings("unchecked")
	private static Set<AgentId> pendingRegistrations(CodexAgentManager manager) {
		try {
			Field field = CodexAgentManager.class.getDeclaredField("pendingAgentRegistrations");
			field.setAccessible(true);
			Set<AgentId> registrations = (Set<AgentId>) field.get(manager);
			return registrations;
		} catch (ReflectiveOperationException exception) {
			throw new AssertionError("could not access pending agent registrations", exception);
		}
	}

	private static void invokeUrgentObservation(MultiplexedServerBridge bridge, AgentId agentId) {
		try {
			var method = MultiplexedServerBridge.class.getDeclaredMethod("queueUrgentObservation", AgentId.class);
			method.setAccessible(true);
			method.invoke(bridge, agentId);
		} catch (ReflectiveOperationException exception) {
			throw new AssertionError("could not invoke urgent observation boundary", exception);
		}
	}

	private static void invokePlannerReady(MultiplexedServerBridge bridge, BridgeEnvelope envelope) {
		try {
			var method = MultiplexedServerBridge.class.getDeclaredMethod("plannerReady", BridgeEnvelope.class);
			method.setAccessible(true);
			method.invoke(bridge, envelope);
		} catch (java.lang.reflect.InvocationTargetException exception) {
			throw new AssertionError("obsolete planner readiness was not ignored", exception.getCause());
		} catch (ReflectiveOperationException exception) {
			throw new AssertionError("could not invoke planner readiness", exception);
		}
	}

	private static void invokePendingRegistrationPublication(CodexAgentManager manager, AgentRecord record) {
		try {
			var method = CodexAgentManager.class.getDeclaredMethod("publishPendingRegistration", AgentRecord.class);
			method.setAccessible(true);
			method.invoke(manager, record);
		} catch (ReflectiveOperationException exception) {
			throw new AssertionError("could not invoke pending registration publication", exception);
		}
	}

	private static JsonObject invokeGoalControlPayload(AgentTransition transition, String operation) {
		try {
			var method = MultiplexedServerBridge.class.getDeclaredMethod("goalControlPayload", AgentTransition.class, String.class);
			method.setAccessible(true);
			return (JsonObject) method.invoke(null, transition, operation);
		} catch (ReflectiveOperationException exception) {
			throw new AssertionError("could not serialize goal control payload", exception);
		}
	}

	private static String invokeGoalOperation(AgentTransition transition) {
		try {
			var method = MultiplexedServerBridge.class.getDeclaredMethod("operation", AgentTransition.class);
			method.setAccessible(true);
			return (String) method.invoke(null, transition);
		} catch (ReflectiveOperationException exception) {
			throw new AssertionError("could not classify goal control operation", exception);
		}
	}

	private static String invokeVerboseResult(ServerActionResult result) {
		try {
			var method = MultiplexedServerBridge.class.getDeclaredMethod("verboseResult", ServerActionResult.class);
			method.setAccessible(true);
			return (String) method.invoke(null, result);
		} catch (ReflectiveOperationException exception) {
			throw new AssertionError("could not format verbose action result", exception);
		}
	}

	private static MultiplexedServerBridge.VerboseEvent invokeCompletionVerboseEvent(
			long goalRevision,
			GoalCompletionVerifier.VerificationResult verification
	) {
		try {
			var method = MultiplexedServerBridge.class.getDeclaredMethod(
					"completionVerboseEvent", long.class, GoalCompletionVerifier.VerificationResult.class);
			method.setAccessible(true);
			return (MultiplexedServerBridge.VerboseEvent) method.invoke(null, goalRevision, verification);
		} catch (ReflectiveOperationException exception) {
			throw new AssertionError("could not format goal completion verbose feedback", exception);
		}
	}

	private static void invokeActionProgress(MultiplexedServerBridge bridge, ServerActionProgress progress) {
		try {
			var method = MultiplexedServerBridge.class.getDeclaredMethod("sendActionProgress", ServerActionProgress.class);
			method.setAccessible(true);
			method.invoke(bridge, progress);
		} catch (ReflectiveOperationException exception) {
			throw new AssertionError("could not publish action progress", exception);
		}
	}

	private static JsonObject invokeActionObservationPayload(ServerActionObservation observation) {
		try {
			var method = MultiplexedServerBridge.class.getDeclaredMethod("actionObservationPayload", ServerActionObservation.class);
			method.setAccessible(true);
			return (JsonObject) method.invoke(null, observation);
		} catch (ReflectiveOperationException exception) {
			throw new AssertionError("could not serialize action observation", exception);
		}
	}

	private static void saturateAgentQueue(MultiplexedServerBridge bridge, AgentId agentId) {
		setQueuedCount(bridge, agentId.toString(), MultiplexedServerBridge.AGENT_QUEUE_CAP);
	}

	@SuppressWarnings("unchecked")
	private static void setQueuedCount(MultiplexedServerBridge bridge, String agentId, int count) {
		try {
			Field sessionField = MultiplexedServerBridge.class.getDeclaredField("session");
			sessionField.setAccessible(true);
			Object session = sessionField.get(bridge);
			Field queuedField = session.getClass().getDeclaredField("queuedByAgent");
			queuedField.setAccessible(true);
			synchronized (session) {
				Map<String, Integer> queued = (Map<String, Integer>) queuedField.get(session);
				if (count == 0) queued.remove(agentId);
				else queued.put(agentId, count);
			}
		} catch (ReflectiveOperationException exception) {
			throw new AssertionError("could not set the agent publication queue count", exception);
		}
	}

	private static void putObject(sun.misc.Unsafe unsafe, CodexAgentManager manager, String fieldName, Object value)
			throws ReflectiveOperationException {
		Field field = CodexAgentManager.class.getDeclaredField(fieldName);
		unsafe.putObject(manager, unsafe.objectFieldOffset(field), value);
	}

	private static final class TestMinecraftServer extends MinecraftServer {
		private EmptyPlayerList playerList;

		private TestMinecraftServer() {
			super(null, null, null, null, java.util.Optional.empty(), Proxy.NO_PROXY, null, null, null, false);
		}

		@Override protected boolean initServer() { return true; }
		@Override public LevelBasedPermissionSet operatorUserPermissions() { return null; }
		@Override public PermissionSet getFunctionCompilationPermissions() { return null; }
		@Override public boolean shouldRconBroadcast() { return false; }
		@Override protected SampleLogger getTickTimeLogger() { return null; }
		@Override public boolean isTickTimeLoggingEnabled() { return false; }
		@Override public SystemReport fillServerSystemReport(SystemReport report) { return report; }
		@Override public boolean isDedicatedServer() { return false; }
		@Override public int getRateLimitPacketsPerSecond() { return 0; }
		@Override public boolean useNativeTransport() { return false; }
		@Override public boolean isPublished() { return false; }
		@Override public boolean shouldInformAdmins() { return false; }
		@Override public boolean isSingleplayerOwner(net.minecraft.server.players.NameAndId profile) { return false; }
		@Override public int getMaxPlayers() { return 0; }
		@Override public PlayerList getPlayerList() { return playerList; }
		@Override public net.minecraft.core.RegistryAccess.Frozen registryAccess() {
			return net.minecraft.core.RegistryAccess.EMPTY;
		}
	}

	private static final class EmptyPlayerList extends PlayerList {
		private EmptyPlayerList() {
			super(null, null, null, null);
		}

		@Override public ServerPlayer getPlayer(UUID uuid) { return null; }
		@Override public ServerPlayer getPlayerByName(String name) { return null; }
		@Override public void broadcastSystemMessage(net.minecraft.network.chat.Component message, boolean overlay) { }
	}

	private static final class ShutdownRaceServerSocket extends ServerSocket {
		private final CountDownLatch releaseAccept = new CountDownLatch(1);
		private final ShutdownRaceSocket acceptedSocket = new ShutdownRaceSocket();
		private final AtomicBoolean closed = new AtomicBoolean();

		private ShutdownRaceServerSocket() throws IOException {
			super();
		}

		@Override
		public void bind(java.net.SocketAddress endpoint, int backlog) {
		}

		@Override
		public Socket accept() throws IOException {
			try {
				releaseAccept.await();
			} catch (InterruptedException exception) {
				Thread.currentThread().interrupt();
				throw new IOException("accept interrupted", exception);
			}
			return acceptedSocket;
		}

		@Override
		public synchronized void close() {
			closed.set(true);
			releaseAccept.countDown();
		}

		@Override
		public boolean isClosed() {
			return closed.get();
		}

		@Override
		public int getLocalPort() {
			return 25_570;
		}

		private void releaseAccept() {
			releaseAccept.countDown();
		}
	}

	private static void awaitPreauthSessionCount(
			MultiplexedServerBridge bridge,
			int expected,
			String label
	) {
		long deadline = System.nanoTime() + 2_000_000_000L;
		while (preauthSessionCount(bridge) != expected && System.nanoTime() < deadline) {
			try {
				Thread.sleep(1L);
			} catch (InterruptedException exception) {
				Thread.currentThread().interrupt();
				throw new AssertionError(label + " was interrupted", exception);
			}
		}
		assertEquals(expected, preauthSessionCount(bridge), label);
	}

	private static boolean catalogDiscoveryPending(MultiplexedServerBridge bridge) {
		try {
			Object publicationLock = readPrivateField(bridge, "publicationLock");
			synchronized (publicationLock) {
				return (boolean) readPrivateField(bridge, "catalogDiscoveryPending");
			}
		} catch (ReflectiveOperationException exception) {
			throw new AssertionError("could not inspect catalog discovery state", exception);
		}
	}

	private static void awaitServerTaskCount(
			MultiplexedServerBridge bridge,
			int expected,
			String label
	) {
		long deadline = System.nanoTime() + 2_000_000_000L;
		while (serverTaskCount(bridge) != expected && System.nanoTime() < deadline) {
			try {
				Thread.sleep(1L);
			} catch (InterruptedException exception) {
				Thread.currentThread().interrupt();
				throw new AssertionError(label + " was interrupted", exception);
			}
		}
		assertEquals(expected, serverTaskCount(bridge), label);
	}

	private static int serverTaskCount(MultiplexedServerBridge bridge) {
		try {
			return ((BoundedServerTaskQueue) readPrivateField(bridge, "serverTasks")).pendingCount();
		} catch (ReflectiveOperationException exception) {
			throw new AssertionError("could not inspect inbound server tasks", exception);
		}
	}

	private static final class ShutdownRaceSocket extends Socket {
		private final CountDownLatch addressLookupStarted = new CountDownLatch(1);
		private final CountDownLatch allowAddressLookup = new CountDownLatch(1);
		private final CountDownLatch closedLatch = new CountDownLatch(1);
		private final AtomicBoolean closed = new AtomicBoolean();
		private final java.io.InputStream input = new java.io.InputStream() {
			@Override
			public int read() throws IOException {
				try {
					closedLatch.await();
					return -1;
				} catch (InterruptedException exception) {
					Thread.currentThread().interrupt();
					throw new IOException("socket read interrupted", exception);
				}
			}
		};

		@Override
		public InetAddress getInetAddress() {
			addressLookupStarted.countDown();
			try {
				allowAddressLookup.await();
			} catch (InterruptedException exception) {
				Thread.currentThread().interrupt();
			}
			return InetAddress.getLoopbackAddress();
		}

		private void releaseAddressLookup() {
			allowAddressLookup.countDown();
		}

		@Override
		public void setTcpNoDelay(boolean on) {
		}

		@Override
		public void setSoTimeout(int timeout) {
		}

		@Override
		public java.io.InputStream getInputStream() {
			return input;
		}

		@Override
		public java.io.OutputStream getOutputStream() {
			return java.io.OutputStream.nullOutputStream();
		}

		@Override
		public synchronized void close() {
			if (closed.compareAndSet(false, true)) closedLatch.countDown();
		}

		@Override
		public boolean isClosed() {
			return closed.get();
		}
	}

	@SuppressWarnings("unchecked")
	private static BridgeEnvelope authenticate(
			Socket socket,
			BufferedReader reader,
			BridgeEnvelopeCodec codec,
			String secret,
			String launchId,
			String messageId
	) throws Exception {
		writeHello(socket, reader, codec, secret, launchId, messageId);
		BridgeEnvelope acknowledgement = codec.decode(reader.readLine());
		assertEquals("hello_ack", acknowledgement.type(), "launch fixture authenticates the session");
		assertEquals("verbose_control", codec.decode(reader.readLine()).type(),
				"launch fixture consumes verbose control");
		return acknowledgement;
	}

	private static void writeHello(
			Socket socket,
			BufferedReader reader,
			BridgeEnvelopeCodec codec,
			String secret,
			String launchId,
			String messageId
	) throws Exception {
		AuthenticationExchange exchange = beginAuthentication(socket, reader, codec, secret, messageId);
		writeAuthenticatedHello(socket, codec, secret, launchId, messageId, exchange);
	}

	private static AuthenticationExchange beginAuthentication(
			Socket socket,
			BufferedReader reader,
			BridgeEnvelopeCodec codec,
			String secret,
			String messageId
	) throws Exception {
		String clientNonce = Base64.getUrlEncoder().withoutPadding().encodeToString(
				MessageDigest.getInstance("SHA-256").digest(messageId.getBytes(StandardCharsets.UTF_8))
		);
		JsonObject challenge = new JsonObject();
		challenge.addProperty("clientNonce", clientNonce);
		writeEnvelope(socket, codec, new BridgeEnvelope(
				2, "pending", "server", "auth_challenge", messageId + "-challenge", challenge
		));
		BridgeEnvelope response = codec.decode(reader.readLine());
		assertEquals("auth_response", response.type(), "server proves its identity before coordinator authentication");
		String serverNonce = response.payload().get("serverNonce").getAsString();
		assertEquals(
				MultiplexedServerBridge.authenticationProof(secret, "server", clientNonce, serverNonce, response.serverInstanceId(), null),
				response.payload().get("proof").getAsString(),
				"server response is bound to the fresh coordinator challenge"
		);
		return new AuthenticationExchange(clientNonce, response.payload().get("serverNonce").getAsString(), response);
	}

	private static void writeAuthenticatedHello(
			Socket socket,
			BridgeEnvelopeCodec codec,
			String secret,
			String launchId,
			String messageId,
			AuthenticationExchange exchange
	) throws Exception {
		String clientNonce = exchange.clientNonce();
		String serverNonce = exchange.serverNonce();
		BridgeEnvelope response = exchange.response();
		JsonObject payload = new JsonObject();
		payload.addProperty("replyTo", response.messageId());
		payload.addProperty("clientNonce", clientNonce);
		payload.addProperty("serverNonce", serverNonce);
		payload.addProperty("proof", MultiplexedServerBridge.authenticationProof(
				secret, "coordinator", clientNonce, serverNonce, response.serverInstanceId(), launchId
		));
		if (launchId != null) payload.addProperty("launchId", launchId);
		socket.getOutputStream().write(codec.encode(new BridgeEnvelope(
				2, response.serverInstanceId(), "server", "hello", messageId, payload
		)).getBytes(StandardCharsets.UTF_8));
		socket.getOutputStream().flush();
	}

	private record AuthenticationExchange(String clientNonce, String serverNonce, BridgeEnvelope response) {
	}

	private static void awaitLatch(CountDownLatch latch, String label) {
		try {
			assertTrue(latch.await(2L, java.util.concurrent.TimeUnit.SECONDS), label);
		} catch (InterruptedException exception) {
			Thread.currentThread().interrupt();
			throw new AssertionError(label + " was interrupted", exception);
		}
	}

	private static void deleteIfExists(Path path) {
		if (path == null) return;
		try {
			Files.deleteIfExists(path);
		} catch (java.io.IOException exception) {
			throw new AssertionError("could not remove temporary bridge secret", exception);
		}
	}

	private static void awaitCondition(java.util.function.BooleanSupplier condition, String label) {
		long deadline = System.nanoTime() + 2_000_000_000L;
		while (!condition.getAsBoolean() && System.nanoTime() < deadline) {
			Thread.onSpinWait();
		}
		assertTrue(condition.getAsBoolean(), label);
	}

	private static long bridgeThreadCount() {
		return Thread.getAllStackTraces().keySet().stream()
				.filter(Thread::isAlive)
				.filter(thread -> thread.getName().startsWith("arenaagents-v2-"))
				.count();
	}

	private static BridgeEnvelope pollBridgeResponse(
			MultiplexedServerBridge bridge,
			Socket socket,
			BufferedReader reader,
			BridgeEnvelopeCodec codec
	) throws Exception {
		long deadline = System.nanoTime() + 2_000_000_000L;
		while (System.nanoTime() < deadline) {
			bridge.tick();
			if (socket.getInputStream().available() > 0) return codec.decode(reader.readLine());
			Thread.sleep(5L);
		}
		throw new AssertionError("bridge did not publish a response");
	}

	private static BridgeEnvelope pollBridgeResponseOfType(
			MultiplexedServerBridge bridge,
			Socket socket,
			BufferedReader reader,
			BridgeEnvelopeCodec codec,
			String expectedType,
			AgentId excludedAgent
	) throws Exception {
		long deadline = System.nanoTime() + 2_000_000_000L;
		while (System.nanoTime() < deadline) {
			BridgeEnvelope response = pollBridgeResponse(bridge, socket, reader, codec);
			if (expectedType.equals(response.type())) return response;
			if ((excludedAgent != null && excludedAgent.toString().equals(response.agentId()))
					|| "conversation_event".equals(response.type())
					|| "conversation_wake".equals(response.type())) {
				throw new AssertionError("pending agent leaked a bridge publication: " + response.type());
			}
			if (!"observation".equals(response.type()) && !"catalog_discovery_request".equals(response.type())) {
				throw new AssertionError("unexpected bridge response before " + expectedType + ": " + response.type());
			}
		}
		throw new AssertionError("bridge did not publish " + expectedType + " within the bounded response window");
	}

	private static BridgeEnvelope authenticate(
			Socket socket,
			BufferedReader reader,
			BridgeEnvelopeCodec codec,
			String secret,
			String messageId
	) throws Exception {
		writeHello(socket, reader, codec, secret, null, messageId);
		BridgeEnvelope acknowledgement = codec.decode(reader.readLine());
		assertEquals("hello_ack", acknowledgement.type(), "replacement fixture authenticates the session");
		assertEquals("verbose_control", codec.decode(reader.readLine()).type(),
				"replacement fixture consumes verbose control");
		return acknowledgement;
	}

	private static BridgeEnvelope authenticateWithTicks(
			MultiplexedServerBridge bridge,
			Socket socket,
			BufferedReader reader,
			BridgeEnvelopeCodec codec,
			String secret,
			String messageId
	) throws Exception {
		writeHello(socket, reader, codec, secret, null, messageId);
		BridgeEnvelope acknowledgement = pollBridgeResponseOfType(
				bridge, socket, reader, codec, "hello_ack", null);
		assertEquals("verbose_control", pollBridgeResponseOfType(
				bridge, socket, reader, codec, "verbose_control", null).type(),
				"replacement fixture consumes verbose control");
		return acknowledgement;
	}

	private static void writeEnvelope(Socket socket, BridgeEnvelopeCodec codec, BridgeEnvelope envelope) throws Exception {
		socket.getOutputStream().write(codec.encode(envelope).getBytes(StandardCharsets.UTF_8));
		socket.getOutputStream().flush();
	}

	private static void assertNoActionResult(
			Socket socket,
			BufferedReader reader,
			BridgeEnvelopeCodec codec,
			String actionId,
			String label
	) throws Exception {
		int previousTimeout = socket.getSoTimeout();
		long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(200L);
		try {
			while (System.nanoTime() < deadline) {
				long remainingNanos = deadline - System.nanoTime();
				socket.setSoTimeout((int) Math.max(1L, TimeUnit.NANOSECONDS.toMillis(remainingNanos)));
				String line;
				try {
					line = reader.readLine();
				} catch (SocketTimeoutException expected) {
					return;
				}
				if (line == null) return;
				BridgeEnvelope envelope = codec.decode(line);
				if ("action_result".equals(envelope.type())
						&& actionId.equals(envelope.payload().get("actionId").getAsString())) {
					throw new AssertionError(label);
				}
			}
		} finally {
			socket.setSoTimeout(previousTimeout);
		}
	}

	private static void verifyObservationPublicationLifecycle(AgentId agent) {
		MultiplexedServerBridge.ObservationPublication publication = new MultiplexedServerBridge.ObservationPublication(16, 16);
		Object oldSession = new Object();
		Object newSession = new Object();
		MultiplexedServerBridge.onSessionAccepted(publication, oldSession);
		JsonObject oldObservation = observation("00000000-0000-0000-0000-000000000002", 1_000L);
		assertEquals(MultiplexedServerBridge.ObservationPublication.Result.COMMITTED,
				publication.publish(agent, oldSession, oldObservation, (ignoredAgent, ignoredPayload) -> true),
				"old session writer commits its observation before disconnect");
		publication.offer(agent);
		assertEquals(1, publication.retainedCount(), "old session baseline is retained before reset");
		assertEquals(1, publication.pendingCount(), "old session queue is retained before reset");

		MultiplexedServerBridge.onSessionClosed(publication, oldSession);
		MultiplexedServerBridge.onSessionAccepted(publication, newSession);
		AtomicBoolean staleWriterCalled = new AtomicBoolean();
		JsonObject staleObservation = observation("00000000-0000-0000-0000-000000000003", 1_001L);
		assertEquals(MultiplexedServerBridge.ObservationPublication.Result.STALE_SESSION,
				publication.publish(agent, oldSession, staleObservation, (ignoredAgent, ignoredPayload) -> {
					staleWriterCalled.set(true);
					return true;
				}),
				"old session observation cannot publish after reset activates a new session");
		assertTrue(!staleWriterCalled.get(), "new session writer never receives stale old-session observation");
		assertEquals(0, publication.retainedCount(), "reset clears old delivered baseline before new session");
		assertEquals(0, publication.pendingCount(), "reset clears old queued observation before new session");

		List<JsonObject> freshDeliveries = new ArrayList<>();
		JsonObject freshObservation = observation("00000000-0000-0000-0000-000000000003", 1_002L);
		assertEquals(MultiplexedServerBridge.ObservationPublication.Result.COMMITTED,
				publication.publish(agent, newSession, freshObservation, (ignoredAgent, payload) -> {
					freshDeliveries.add(payload.deepCopy());
					return true;
				}),
				"new session writer commits a fresh observation");
		assertEquals(1, freshDeliveries.size(), "new session receives exactly its fresh observation");
		assertTrue(!freshDeliveries.getFirst().get("attention").getAsBoolean(),
				"fresh observation starts from an empty reset baseline");
		assertEquals(1, publication.retainedCount(), "new session baseline is retained after commit");
	}

	private static void verifyObservationCadence(List<AgentId> agents) {
		MultiplexedServerBridge.ObservationPublication publication =
				new MultiplexedServerBridge.ObservationPublication(16, 16, 1);
		List<AgentId> heartbeats = new ArrayList<>();
		for (int tick = 0; tick < agents.size(); tick++) {
			publication.scheduleIdleHeartbeat(agents);
			List<AgentId> emitted = new ArrayList<>();
			publication.drain(emitted::add);
			assertEquals(1, emitted.size(), "idle heartbeat is bounded to one agent per tick");
			heartbeats.addAll(emitted);
		}
		assertEquals(agents, heartbeats, "idle heartbeat rotates through all registered agents");

		Object session = new Object();
		MultiplexedServerBridge.onSessionAccepted(publication, session);
		AgentId agent = agents.getFirst();
		JsonObject first = observation("00000000-0000-0000-0000-000000000002", 1_000L);
		assertEquals(MultiplexedServerBridge.ObservationPublication.Result.COMMITTED,
				publication.publish(agent, session, first, (ignoredAgent, ignoredPayload) -> true, false),
				"the first urgent observation establishes a delivered baseline");
		AtomicInteger fitCalls = new AtomicInteger();
		MultiplexedServerBridge.ObservationPublication singleFitPublication =
				new MultiplexedServerBridge.ObservationPublication(16, 16, (ignoredAgent, payload) -> {
					fitCalls.incrementAndGet();
					return new ServerObservationWireBudget.Fitted(payload, List.of());
				});
		singleFitPublication.activate(session);
		JsonObject singleFitObservation = observation("00000000-0000-0000-0000-000000000002", 1_000L);
		assertEquals(MultiplexedServerBridge.ObservationPublication.Result.COMMITTED,
				singleFitPublication.publish(agent, session, singleFitObservation,
						(ignoredAgent, ignoredPayload) -> true, false),
				"an under-budget observation publishes through the single-fit path");
		assertEquals(1, fitCalls.get(), "an under-budget observation is fitted once");
		assertTrue(!singleFitObservation.has("eventSequence"), "single-fit publication does not mutate the collector snapshot");
		JsonObject unchanged = first.deepCopy();
		unchanged.addProperty("observedAtEpochMs", 1_001L);
		assertEquals(MultiplexedServerBridge.ObservationPublication.Result.SUPPRESSED,
				publication.publish(agent, session, unchanged, (ignoredAgent, ignoredPayload) -> true, false),
				"unchanged event-driven observations are suppressed");
		assertTrue(publication.markAttention(agent), "urgent attention can bypass unchanged suppression");
		assertEquals(MultiplexedServerBridge.ObservationPublication.Result.COMMITTED,
				publication.publish(agent, session, unchanged, (ignoredAgent, ignoredPayload) -> true, false),
				"urgent attention publishes even when facts are unchanged");
		JsonObject heartbeat = unchanged.deepCopy();
		heartbeat.addProperty("observedAtEpochMs", 1_002L);
		assertEquals(MultiplexedServerBridge.ObservationPublication.Result.COMMITTED,
				publication.publish(agent, session, heartbeat, (ignoredAgent, ignoredPayload) -> true, true),
				"a scheduled heartbeat publishes unchanged facts");
		publication.scheduleIdleHeartbeat(agents);
		assertTrue(publication.pendingCount() > 0, "heartbeat remains queued before a session reset");
		MultiplexedServerBridge.onSessionClosed(publication, session);
		assertEquals(0, publication.pendingCount(), "session cleanup clears pending idle heartbeats");
		verifyDueHeartbeatCadence(agents.subList(0, Math.min(2, agents.size())));
	}

	private static void verifyDueHeartbeatCadence(List<AgentId> agents) {
		MultiplexedServerBridge.ObservationPublication publication =
				new MultiplexedServerBridge.ObservationPublication(16, 16, 3);
		Object session = new Object();
		MultiplexedServerBridge.onSessionAccepted(publication, session);
		List<AgentId> emitted = new ArrayList<>();
		for (int tick = 0; tick < 8; tick++) {
			final long observedAt = 2_000L + tick;
			publication.scheduleIdleHeartbeat(agents);
			publication.drain(agentId -> {
				emitted.add(agentId);
				assertTrue(publication.takeHeartbeat(agentId), "scheduled work is marked as a heartbeat");
				assertEquals(MultiplexedServerBridge.ObservationPublication.Result.COMMITTED,
						publication.publish(agentId, session, observation("00000000-0000-0000-0000-000000000002", observedAt),
								(ignoredAgent, ignoredPayload) -> true, true),
						"a due heartbeat commits and advances its next due tick");
			});
		}
		assertEquals(List.of(agents.get(0), agents.get(1), agents.get(0), agents.get(1), agents.get(0), agents.get(1)),
				emitted, "minimum interval spaces each agent while retaining fair rotation");

		MultiplexedServerBridge.ObservationPublication retryPublication =
				new MultiplexedServerBridge.ObservationPublication(16, 16, 3);
		MultiplexedServerBridge.onSessionAccepted(retryPublication, session);
		retryPublication.scheduleIdleHeartbeat(List.of(agents.get(0)));
		List<AgentId> retryDrain = new ArrayList<>();
		retryPublication.drain(agentId -> {
			retryDrain.add(agentId);
			assertTrue(retryPublication.takeHeartbeat(agentId), "failed heartbeat is identified for retry");
			assertEquals(MultiplexedServerBridge.ObservationPublication.Result.DELIVERY_RETRY,
					retryPublication.publish(agentId, session, observation("00000000-0000-0000-0000-000000000002", 3_000L),
								(ignoredAgent, ignoredPayload) -> false, true),
						"a failed heartbeat remains undelivered");
		});
		assertTrue(retryPublication.offerHeartbeat(agents.get(0)), "failed heartbeat can be requeued");
		retryPublication.drain(agentId -> {
			assertTrue(retryPublication.takeHeartbeat(agentId), "requeued heartbeat retains its bypass marker");
			assertEquals(MultiplexedServerBridge.ObservationPublication.Result.COMMITTED,
					retryPublication.publish(agentId, session, observation("00000000-0000-0000-0000-000000000002", 3_001L),
								(ignoredAgent, ignoredPayload) -> true, true),
						"a requeued heartbeat commits successfully");
		});
		assertEquals(List.of(agents.get(0)), retryDrain, "only the failed agent is retried");

		MultiplexedServerBridge.ObservationPublication cleanupPublication =
				new MultiplexedServerBridge.ObservationPublication(16, 16, 3);
		Object oldSession = new Object();
		Object newSession = new Object();
		MultiplexedServerBridge.onSessionAccepted(cleanupPublication, oldSession);
		cleanupPublication.scheduleIdleHeartbeat(List.of(agents.get(0)));
		MultiplexedServerBridge.onSessionAccepted(cleanupPublication, newSession);
		assertEquals(0, cleanupPublication.pendingCount(), "session replacement clears queued heartbeat state");
		cleanupPublication.scheduleIdleHeartbeat(List.of(agents.get(0)));
		List<AgentId> freshSession = new ArrayList<>();
		cleanupPublication.drain(agentId -> {
			freshSession.add(agentId);
			assertTrue(cleanupPublication.takeHeartbeat(agentId), "fresh session starts with a due heartbeat");
		});
		assertEquals(List.of(agents.get(0)), freshSession, "session replacement does not delay first observation");
	}

	private static void verifyInspectionQueryValidation() {
		JsonObject query = new JsonObject();
		query.addProperty("section", "observation");
		JsonObject normalized = MultiplexedServerBridge.validateInspectionQuery(query);
		assertEquals(0, normalized.get("offset").getAsInt(), "inspection defaults its offset");
		assertEquals(16, normalized.get("limit").getAsInt(), "inspection defaults its page size");
		assertTrue(!query.has("offset"), "inspection validation does not mutate its input");
		for (String section : List.of("observation", "inventory", "menu", "entities", "blocks", "events", "landmarks", "nearby_containers")) {
			query.addProperty("section", section);
			MultiplexedServerBridge.validateInspectionQuery(query);
		}
		for (String field : List.of("offset", "limit")) {
			JsonObject bad = query.deepCopy();
			bad.addProperty(field, "1");
			assertThrowsCode(() -> MultiplexedServerBridge.validateInspectionQuery(bad), "INVALID_INSPECTION");
			bad.addProperty(field, 1.5);
			assertThrowsCode(() -> MultiplexedServerBridge.validateInspectionQuery(bad), "INVALID_INSPECTION");
			bad.add(field, com.google.gson.JsonNull.INSTANCE);
			assertThrowsCode(() -> MultiplexedServerBridge.validateInspectionQuery(bad), "INVALID_INSPECTION");
		}
		query.addProperty("section", "observation");
		query.addProperty("slot", 0);
		assertThrowsCode(() -> MultiplexedServerBridge.validateInspectionQuery(query), "INVALID_INSPECTION");
		query.remove("slot");
		query.addProperty("offset", 4097);
		assertThrowsCode(() -> MultiplexedServerBridge.validateInspectionQuery(query), "INVALID_INSPECTION");
		query.remove("offset");
		query.addProperty("section", "item");
		assertThrowsCode(() -> MultiplexedServerBridge.validateInspectionQuery(query), "INVALID_INSPECTION");
		query.addProperty("slot", 40);
		MultiplexedServerBridge.validateInspectionQuery(query);
		query.remove("slot");
		query.addProperty("section", "block");
		query.addProperty("x", -30_000_000);
		query.addProperty("y", -64);
		assertThrowsCode(() -> MultiplexedServerBridge.validateInspectionQuery(query), "INVALID_INSPECTION");
		query.addProperty("z", 30_000_000);
		MultiplexedServerBridge.validateInspectionQuery(query);
		query.addProperty("y", 2049);
		assertThrowsCode(() -> MultiplexedServerBridge.validateInspectionQuery(query), "INVALID_INSPECTION");
		JsonObject events = new JsonObject();
		events.addProperty("section", "events");
		events.addProperty("afterSequence", -1);
		MultiplexedServerBridge.validateInspectionQuery(events);
		events.addProperty("afterSequence", 9_007_199_254_740_992L);
		assertThrowsCode(() -> MultiplexedServerBridge.validateInspectionQuery(events), "INVALID_INSPECTION");
		JsonObject recipes = new JsonObject();
		recipes.addProperty("section", "recipes");
		recipes.addProperty("recipeId", "minecraft:oak_planks");
		MultiplexedServerBridge.validateInspectionQuery(recipes);
		recipes.addProperty("recipeId", "../invalid");
		assertThrowsCode(() -> MultiplexedServerBridge.validateInspectionQuery(recipes), "INVALID_INSPECTION");
		recipes.addProperty("recipeId", "minecraft:oak_planks");
		recipes.addProperty("section", "mechanics");
		assertThrowsCode(() -> MultiplexedServerBridge.validateInspectionQuery(recipes), "INVALID_INSPECTION");
	}

	private static void verifyInspectionPublication(AgentId agent) {
		MultiplexedServerBridge.ObservationPublication publication = new MultiplexedServerBridge.ObservationPublication(16, 16);
		Object session = new Object();
		publication.activate(session);
		JsonObject page = inspectionPage(65, 32);
		assertThrowsCode(() -> publication.deliverInspection(agent, session, page, (id, value) -> true), "STALE_FACTS");
		JsonObject baseline = observation(inspectionId(1), 1000);
		baseline.add("entities", inspectionPage(1, 64).get("entries"));
		JsonObject world = new JsonObject();
		world.addProperty("worldId", "test-world");
		world.addProperty("dimension", "minecraft:overworld");
		baseline.add("world", world);
		publication.publish(agent, session, baseline, (id, value) -> true);
		publication.markAttention(agent);
		List<JsonObject> deliveries = new ArrayList<>();
		assertEquals(MultiplexedServerBridge.ObservationPublication.Result.COMMITTED,
				publication.deliverInspection(agent, session, page, (id, value) -> { deliveries.add(value.deepCopy()); return true; }),
				"focused entity page publishes successfully");
		long sequence = deliveries.getFirst().get("eventSequence").getAsLong();
		assertEquals(1L, sequence, "focused query retains the existing causal observation sequence");
		assertTrue(!page.has("eventSequence"), "inspection publication does not mutate collector output");
		for (int index = 1; index <= 96; index++) publication.requireObservedTarget(agent, sequence, inspectionId(index));
		assertThrowsCode(() -> publication.requireObservedTarget(agent, sequence, inspectionId(97)), "TARGET_NOT_OBSERVED");
		assertEquals(1, publication.retainedCount(), "inspection preserves exactly one full baseline");
		assertEquals(MultiplexedServerBridge.ObservationPublication.Result.DELIVERY_RETRY,
				publication.deliverInspection(agent, session, inspectionPage(97, 1), (id, value) -> false),
				"failed write does not commit focused target authority");
		assertThrowsCode(() -> publication.requireObservedTarget(agent, sequence, inspectionId(97)), "TARGET_NOT_OBSERVED");
		assertThrows(BridgeProtocolException.class, () -> publication.deliverInspection(agent, session, inspectionPage(98, 1),
				(id, value) -> { throw new BridgeProtocolException("INSPECTION_TOO_LARGE", "oversize"); }), "oversized inspection fails before target commit");
		assertThrowsCode(() -> publication.requireObservedTarget(agent, sequence, inspectionId(98)), "TARGET_NOT_OBSERVED");
		JsonObject otherSection = inspectionPage(99, 1);
		otherSection.addProperty("section", "inventory");
		publication.deliverInspection(agent, session, otherSection, (id, value) -> true);
		assertThrowsCode(() -> publication.requireObservedTarget(agent, sequence, inspectionId(99)), "TARGET_NOT_OBSERVED");
		JsonObject hidden = inspectionPage(100, 1);
		hidden.add("unreturned", inspectionPage(101, 1).get("entries"));
		publication.deliverInspection(agent, session, hidden, (id, value) -> true);
		publication.requireObservedTarget(agent, sequence, inspectionId(100));
		assertThrowsCode(() -> publication.requireObservedTarget(agent, sequence, inspectionId(101)), "TARGET_NOT_OBSERVED");
		JsonObject wrongWorld = inspectionPage(102, 1);
		wrongWorld.addProperty("dimension", "minecraft:the_nether");
		assertThrowsCode(() -> publication.deliverInspection(agent, session, wrongWorld, (id, value) -> true), "STALE_FACTS");
		for (int start = 101; start < 320; start += 32) {
			publication.deliverInspection(agent, session, inspectionPage(start, Math.min(32, 320 - start)), (id, value) -> true);
		}
		AtomicBoolean overLimitWritten = new AtomicBoolean();
		assertThrowsCode(() -> publication.deliverInspection(agent, session, inspectionPage(321, 5), (id, value) -> {
			overLimitWritten.set(true); return true;
		}), "INSPECTION_AUTHORITY_LIMIT");
		assertTrue(!overLimitWritten.get(), "authority capacity cannot silently discard delivered target references");
		assertEquals(MultiplexedServerBridge.ObservationPublication.Result.COMMITTED,
				publication.publish(agent, session, baseline, (id, value) -> true), "inspection preserves pending attention and full baseline delta");
		publication.requireObservedTarget(agent, sequence, inspectionId(100));
		assertThrowsCode(() -> publication.requireObservedTarget(agent, 2L, inspectionId(100)), "TARGET_NOT_OBSERVED");
		Object replacement = new Object();
		publication.activate(replacement);
		AtomicBoolean staleWritten = new AtomicBoolean();
		assertEquals(MultiplexedServerBridge.ObservationPublication.Result.STALE_SESSION,
				publication.deliverInspection(agent, session, page, (id, value) -> { staleWritten.set(true); return true; }),
				"old session inspection cannot authorize targets in replacement session");
		assertTrue(!staleWritten.get(), "stale session inspection is not written");
		assertThrowsCode(() -> publication.requireObservedTarget(agent, sequence, inspectionId(100)), "TARGET_NOT_OBSERVED");
	}

	private static String inspectionId(int value) { return new UUID(0L, value).toString(); }

	private static JsonObject inspectionPage(int first, int count) {
		JsonObject result = new JsonObject();
		result.addProperty("section", "entities");
		result.addProperty("worldId", "test-world");
		result.addProperty("dimension", "minecraft:overworld");
		JsonArray entries = new JsonArray();
		for (int index = first; index < first + count; index++) entries.add(entity(inspectionId(index)));
		result.add("entries", entries);
		return result;
	}

	private static void verifyInspectionWireBudget(AgentId agent) {
		BridgeEnvelopeCodec codec = new BridgeEnvelopeCodec();
		String serverId = UUID.randomUUID().toString();
		JsonObject correlation = new JsonObject();
		correlation.addProperty("goalRevision", 9_007_199_254_740_991L);
		correlation.addProperty("requestId", "r".repeat(128));
		JsonObject source = observation(inspectionId(1), 1000);
		source.addProperty("eventSequence", 9_007_199_254_740_991L);
		source.addProperty("protected", "p".repeat(55_000));
		JsonArray entities = new JsonArray();
		for (int index = 0; index < 32; index++) {
			JsonObject entity = entity(inspectionId(index + 1));
			entity.addProperty("name", "🧭".repeat(120));
			entities.add(entity);
		}
		source.add("entities", entities);
		var fitted = MultiplexedServerBridge.fitInspectionObservation(codec, serverId, agent, correlation, source);
		assertTrue(!fitted.reductions().isEmpty(), "oversized observation is fitted for its correlated envelope");
		JsonObject reply = MultiplexedServerBridge.inspectionObservationReply(correlation, fitted.observation());
		assertTrue(codec.encodedLineBytes(new BridgeEnvelope(2, serverId, agent.toString(), "observation", "m".repeat(128), fitted.observation()))
				<= BridgeEnvelopeCodec.MAX_LINE_BYTES, "normal observation remains within the full wire budget");
		assertTrue(codec.encodedLineBytes(new BridgeEnvelope(2, serverId, agent.toString(), "inspection_result", "m".repeat(128), reply))
				<= BridgeEnvelopeCodec.MAX_LINE_BYTES, "correlated observation including request id and nesting remains within the full wire budget");
		assertEquals(32, source.getAsJsonArray("entities").size(), "wire fitting preserves collector source");
		JsonObject boundary = observation(inspectionId(1), 1000);
		boundary.addProperty("eventSequence", 1L);
		boundary.getAsJsonArray("entities").get(0).getAsJsonObject().addProperty("name", "x".repeat(1024));
		boundary.addProperty("protected", "");
		int overhead = codec.encodedLineBytes(new BridgeEnvelope(2, serverId, agent.toString(), "observation", "m".repeat(128), boundary));
		boundary.addProperty("protected", "p".repeat(BridgeEnvelopeCodec.MAX_LINE_BYTES - overhead));
		assertEquals(BridgeEnvelopeCodec.MAX_LINE_BYTES,
				codec.encodedLineBytes(new BridgeEnvelope(2, serverId, agent.toString(), "observation", "m".repeat(128), boundary)),
				"boundary fixture fits the original observation envelope exactly");
		assertTrue(codec.encodedLineBytes(new BridgeEnvelope(2, serverId, agent.toString(), "inspection_result", "m".repeat(128),
				MultiplexedServerBridge.inspectionObservationReply(correlation, boundary))) > BridgeEnvelopeCodec.MAX_LINE_BYTES,
				"reusing an observation-fitted payload overflows its correlated inspection envelope");
		var boundaryFit = MultiplexedServerBridge.fitInspectionObservation(codec, serverId, agent, correlation, boundary);
		assertTrue(codec.encodedLineBytes(new BridgeEnvelope(2, serverId, agent.toString(), "inspection_result", "m".repeat(128),
				MultiplexedServerBridge.inspectionObservationReply(correlation, boundaryFit.observation()))) <= BridgeEnvelopeCodec.MAX_LINE_BYTES,
				"inspection fitting fixes the exact double-envelope boundary failure");
		source.addProperty("protected", "p".repeat(66_000));
		assertThrows(BridgeProtocolException.class, () -> MultiplexedServerBridge.fitInspectionObservation(codec, serverId, agent, correlation, source),
				"oversized protected facts fail before any inspection result is published");
	}

	private static JsonObject observation(String targetId, long observedAtEpochMs) {
		JsonObject observation = new JsonObject();
		observation.addProperty("observedAtEpochMs", observedAtEpochMs);
		JsonArray entities = new JsonArray();
		entities.add(entity(targetId));
		observation.add("entities", entities);
		return observation;
	}

	private static void verifyExactTargetObservationLedger(AgentId agent) {
		MultiplexedServerBridge.PublishedObservationState state = new MultiplexedServerBridge.PublishedObservationState(16);
		JsonObject observation = new JsonObject();
		observation.addProperty("eventSequence", 1L);
		JsonArray entities = new JsonArray();
		entities.add(entity("00000000-0000-0000-0000-000000000002"));
		entities.add(entity("00000000-0000-0000-0000-000000000001"));
		observation.add("entities", entities);
		state.commit(agent, observation);
		state.requireObservedTarget(agent, 1L, "00000000-0000-0000-0000-000000000001");
		assertThrowsCode(() -> state.requireObservedTarget(agent, 1L, "00000000-0000-0000-0000-000000000003"), "TARGET_NOT_OBSERVED");
		state.retainConversationSource(agent, "00000000-0000-0000-0000-000000000004");
		state.requireDirectMessageRecipient(agent, 1L, "00000000-0000-0000-0000-000000000004");
		assertThrowsCode(() -> state.requireDirectMessageRecipient(agent, 1L, "00000000-0000-0000-0000-000000000003"), "TARGET_NOT_OBSERVED");
		for (long sequence = 2; sequence <= 2_401; sequence++) {
			JsonObject next = new JsonObject();
			next.addProperty("eventSequence", sequence);
			next.add("entities", new JsonArray());
			state.commit(agent, next);
		}
		state.requireObservedTarget(agent, 1L, "00000000-0000-0000-0000-000000000001");
		for (long sequence = 2_402; sequence <= 4_097; sequence++) {
			JsonObject next = new JsonObject();
			next.addProperty("eventSequence", sequence);
			next.add("entities", new JsonArray());
			state.commit(agent, next);
		}
		assertThrowsCode(() -> state.requireObservedTarget(agent, 1L, "00000000-0000-0000-0000-000000000001"), "STALE_FACTS");
		state.remove(agent);
		assertThrowsCode(() -> state.requireObservedTarget(agent, 4_097L, "00000000-0000-0000-0000-000000000001"), "TARGET_NOT_OBSERVED");
		assertThrowsCode(() -> state.requireDirectMessageRecipient(agent, 4_097L, "00000000-0000-0000-0000-000000000004"), "TARGET_NOT_OBSERVED");
	}

	private static AgentSavedData savedData(CodexAgentManager manager) {
		try {
			Field field = CodexAgentManager.class.getDeclaredField("savedData");
			field.setAccessible(true);
			return (AgentSavedData) field.get(manager);
		} catch (ReflectiveOperationException exception) {
			throw new AssertionError("could not read manager SavedData", exception);
		}
	}

	private static ProgramActionLedger programActions(MultiplexedServerBridge bridge) {
		try {
			Field field = MultiplexedServerBridge.class.getDeclaredField("programActions");
			field.setAccessible(true);
			return (ProgramActionLedger) field.get(bridge);
		} catch (ReflectiveOperationException exception) {
			throw new AssertionError("could not read bridge program action ledger", exception);
		}
	}

	private static Object session(MultiplexedServerBridge bridge) {
		try {
			Field field = MultiplexedServerBridge.class.getDeclaredField("session");
			field.setAccessible(true);
			Object current = field.get(bridge);
			if (current == null) throw new AssertionError("bridge has no active session");
			return current;
		} catch (ReflectiveOperationException exception) {
			throw new AssertionError("could not read bridge session", exception);
		}
	}

	private static GoalSpec testGoal(String request) {
		return GoalSpec.create(request, new GoalPredicate.OperatorConfirmed(), 0L);
	}

	@SuppressWarnings("unchecked")
	private static AgentSavedData roundTripSavedData(AgentSavedData data) {
		try {
			Field field = AgentSavedData.class.getDeclaredField("CODEC");
			field.setAccessible(true);
			Codec<AgentSavedData> codec = (Codec<AgentSavedData>) field.get(null);
			Tag encoded = codec.encodeStart(NbtOps.INSTANCE, data).getOrThrow();
			return codec.parse(NbtOps.INSTANCE, encoded).getOrThrow();
		} catch (ReflectiveOperationException exception) {
			throw new AssertionError("could not round-trip Minecraft SavedData", exception);
		}
	}

	private static JsonObject entity(String uuid) {
		JsonObject entity = new JsonObject();
		entity.addProperty("uuid", uuid);
		return entity;
	}

	private static void assertThrowsCode(Runnable action, String code) {
		assertThrowsDomain(action, code);
	}

	private static dev.agaminggod.arenaagents.agent.AgentDomainException assertThrowsDomain(
			Runnable action,
			String code) {
		try {
			action.run();
		} catch (dev.agaminggod.arenaagents.agent.AgentDomainException exception) {
			assertEquals(code, exception.code(), "target observation rejection code");
			return exception;
		}
		throw new AssertionError("expected " + code);
	}

	private static void assertThrowsBridgeCode(Runnable action, String code, String label) {
		try {
			action.run();
		} catch (BridgeProtocolException exception) {
			assertEquals(code, exception.code(), label);
			return;
		}
		throw new AssertionError(label + " did not throw " + code);
	}

	private static void assertTrue(boolean value, String label) {
		if (!value) throw new AssertionError(label);
	}

	private static void assertEquals(Object expected, Object actual, String label) {
		if (!expected.equals(actual)) throw new AssertionError(label + ": expected <" + expected + "> but was <" + actual + ">");
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

	private static void assertDoesNotThrow(Runnable action, String label) {
		try {
			action.run();
		} catch (Throwable throwable) {
			throw new AssertionError(label + ": " + throwable.getClass().getSimpleName(), throwable);
		}
	}
}
