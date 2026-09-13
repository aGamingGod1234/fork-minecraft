package dev.agaminggod.arenaagents.server.runtime.input;

import dev.agaminggod.arenaagents.agent.AgentId;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import net.minecraft.world.InteractionHand;

public final class InputStateVerification {
	private static final AgentId AGENT = new AgentId(UUID.fromString("00000000-0000-0000-0000-000000000070"));

	private InputStateVerification() {
	}

	public static int verify() {
		int assertions = 0;
		assertions += verifyCompleteInputState();
		assertions += verifyLeasePreemptionAndRestoration();
		assertions += verifyNavigationReplacementDoesNotRestoreReleasedInput();
		assertions += verifyOwnedReleasePreservesSystemLease();
		assertions += verifyClearReleasesEveryPressedInput();
		assertions += verifyFailedApplyRemainsRetryable();
		assertions += verifyPartialApplyCleanup();
		assertions += verifyPartialTransitionsRestoreLeaseOwner();
		assertions += verifyFailedReleaseRemainsRetryable();
		assertions += verifyFailedPreemptingReleaseRemainsRetryable();
		assertions += verifyLeaseDeadman();
		assertions += verifyFailedDeadmanRemainsRetryable();
		assertions += verifyDeadmanFailureDoesNotBlockOtherAgents();
		assertions += verifyTickFailureDoesNotBlockOtherAgents();
		assertions += verifyRuntimeAllowsFailedCleanupRetry();
		assertions += verifyExactHandUseDriver();
		assertions += verifyBoundedMotor();
		assertions += ControlSequenceVerification.verify();
		assertions += verifyMotorWorldHeading();
		return assertions;
	}

	private static int verifyRuntimeAllowsFailedCleanupRetry() {
		FailingSink sink = new FailingSink();
		LeasedServerInputController controller = new LeasedServerInputController(sink);
		controller.apply(controller.acquire(AGENT, InputOwner.INTERACTION, 300), state(0.0F, true, true));
		for (long tick = 1; tick < LeasedServerInputController.LEASE_TIMEOUT_TICKS; tick++) {
			AgentInputRuntime.tickController(controller);
		}
		sink.failNextClear();
		int followingTicks = 0;
		for (int tick = 0; tick < 2; tick++) {
			AgentInputRuntime.tickController(controller);
			followingTicks++;
			if (tick == 0) {
				assertTrue(controller.currentState(AGENT).isPresent(), "failed runtime cleanup retains its lease for retry");
			}
		}
		assertEquals(2, followingTicks, "input failures allow subsequent runtime work and the next server tick");
		assertEquals(2, sink.clearAttempts, "the runtime retries physical cleanup on the next tick");
		assertTrue(controller.currentState(AGENT).isEmpty(), "the runtime retry releases expired inputs");
		return 4;
	}

	private static int verifyDeadmanFailureDoesNotBlockOtherAgents() {
		FailingSink sink = new FailingSink();
		LeasedServerInputController controller = new LeasedServerInputController(sink);
		AgentId other = AgentId.random();
		controller.apply(controller.acquire(AGENT, InputOwner.INTERACTION, 300), state(0.0F, true, true));
		controller.apply(controller.acquire(other, InputOwner.INTERACTION, 300), state(0.0F, true, true));
		for (long tick = 1; tick < LeasedServerInputController.LEASE_TIMEOUT_TICKS; tick++) controller.tick();
		sink.failNextClear();
		assertThrows(controller::tick, "deadman failure remains visible to the caller");
		assertTrue(controller.currentState(AGENT).isPresent(), "failed cleanup keeps its lease for retry");
		assertTrue(controller.currentState(other).isEmpty(), "one failed cleanup must not leave another agent attacking");
		assertEquals(2, sink.clearAttempts, "all expired agents receive a cleanup attempt");
		controller.tick();
		assertTrue(controller.currentState(AGENT).isEmpty(), "failed agent is cleaned on the next tick");
		return 5;
	}

	private static int verifyTickFailureDoesNotBlockOtherAgents() {
		AgentId other = AgentId.random();
		List<AgentId> ticked = new ArrayList<>();
		InputStateSink sink = new InputStateSink() {
			@Override
			public void apply(AgentId agentId, AgentInputState previous, AgentInputState state) { }

			@Override
			public void clear(AgentId agentId, AgentInputState previous) { }

			@Override
			public void tick(AgentId agentId, AgentInputState state) {
				ticked.add(agentId);
				if (agentId.equals(AGENT)) throw new IllegalStateException("unavailable player");
			}
		};
		LeasedServerInputController controller = new LeasedServerInputController(sink);
		controller.apply(controller.acquire(AGENT, InputOwner.INTERACTION, 300), state(0.0F, false, true));
		controller.apply(controller.acquire(other, InputOwner.INTERACTION, 300), state(0.0F, false, true));
		assertThrows(controller::tick, "physical tick failure remains visible to the caller");
		assertEquals(List.of(AGENT, other), ticked, "one broken player must not starve another player's held use");
		return 2;
	}

	private static int verifyMotorWorldHeading() {
		for (float targetYaw : new float[] {-90.0F, 90.0F}) {
			AgentInputStates.MotorStep step = AgentInputStates.stepMotor(
					AgentInputStates.MotorState.initial(0.0F, 0.0F),
					new AgentInputStates.MotorTarget(targetYaw, 0.0F, true, false, false), 0L);
			net.minecraft.world.phys.Vec3 movement = new net.minecraft.world.phys.Vec3(step.strafe(), 0.0D, step.forward())
					.yRot((float) -Math.toRadians(step.yaw()));
			assertTrue(targetYaw < 0.0F ? movement.x > 0.0D : movement.x < 0.0D,
					"a turn toward " + (targetYaw < 0.0F ? "east" : "west") + " must move toward that waypoint");
		}
		return 2;
	}

	private static int verifyCompleteInputState() {
		AgentInputState state = new AgentInputState(
				-1.0F, 0.75F, true, true, true, true, true,
				135.0F, -25.0F, 7, InteractionHand.OFF_HAND
		);
		assertEquals(-1.0F, state.forward(), "reverse movement");
		assertEquals(0.75F, state.strafe(), "strafe movement");
		assertEquals(true, state.jump(), "jump pressed");
		assertEquals(true, state.sneak(), "sneak pressed");
		assertEquals(true, state.sprint(), "sprint pressed");
		assertEquals(true, state.attack(), "attack pressed");
		assertEquals(true, state.use(), "use pressed");
		assertEquals(135.0F, state.yaw(), "look yaw");
		assertEquals(-25.0F, state.pitch(), "look pitch");
		assertEquals(7, state.selectedSlot(), "selected slot");
		assertEquals(InteractionHand.OFF_HAND, state.hand(), "interaction hand");
		return 11;
	}

	private static int verifyLeasePreemptionAndRestoration() {
		RecordingSink sink = new RecordingSink();
		LeasedServerInputController controller = new LeasedServerInputController(sink);
		InputLease navigation = controller.acquire(AGENT, InputOwner.NAVIGATION, 100);
		AgentInputState walking = state(1.0F, false, false);
		controller.apply(navigation, walking);
		InputLease combat = controller.acquire(AGENT, InputOwner.COMBAT, 200);
		AgentInputState attacking = state(0.0F, true, false);
		controller.apply(combat, attacking);
		controller.apply(navigation, state(1.0F, false, true));
		assertEquals(attacking, controller.currentState(AGENT).orElseThrow(), "higher-priority combat owns inputs");
		controller.release(combat);
		assertEquals(state(1.0F, false, true), controller.currentState(AGENT).orElseThrow(), "navigation state restores after combat");
		assertEquals(List.of(walking, attacking, state(1.0F, false, true)), sink.applied, "only winning states reach the sink");
		return 3;
	}

	private static int verifyNavigationReplacementDoesNotRestoreReleasedInput() {
		RecordingSink sink = new RecordingSink();
		LeasedServerInputController controller = new LeasedServerInputController(sink);
		InputLease previous = controller.acquire(AGENT, InputOwner.NAVIGATION, 100);
		AgentInputState previousState = state(1.0F, false, false);
		controller.apply(previous, previousState);

		controller.release(previous);
		InputLease replacement = controller.acquire(AGENT, InputOwner.NAVIGATION, 100);
		AgentInputState replacementState = state(0.0F, false, true);
		controller.apply(replacement, replacementState);
		controller.release(replacement);

		assertEquals(true, controller.currentState(AGENT).isEmpty(),
				"completed navigation replacement leaves no input lease active");
		assertEquals(List.of(previousState, replacementState), sink.applied,
				"replacement never restores movement from the released navigation lease");
		assertEquals(List.of(AGENT, AGENT), sink.cleared,
				"replanning and completion both release physical input");
		return 3;
	}

	private static int verifyClearReleasesEveryPressedInput() {
		RecordingSink sink = new RecordingSink();
		LeasedServerInputController controller = new LeasedServerInputController(sink);
		InputLease lease = controller.acquire(AGENT, InputOwner.INTERACTION, 300);
		controller.apply(lease, new AgentInputState(
				1.0F, -1.0F, true, true, true, true, true,
				0.0F, 0.0F, 0, InteractionHand.MAIN_HAND
		));
		controller.clear(AGENT);
		assertEquals(true, controller.currentState(AGENT).isEmpty(), "clear removes current state");
		assertEquals(List.of(AGENT), sink.cleared, "clear reaches the physical sink once");
		assertThrows(() -> controller.apply(lease, state(1.0F, true, true)), "cleared lease cannot affect a respawned player");
		return 3;
	}

	private static int verifyOwnedReleasePreservesSystemLease() {
		RecordingSink sink = new RecordingSink();
		LeasedServerInputController controller = new LeasedServerInputController(sink);
		InputLease navigation = controller.acquire(AGENT, InputOwner.NAVIGATION, 100);
		AgentInputState walking = state(1.0F, false, false);
		controller.apply(navigation, walking);
		InputLease safety = controller.acquire(AGENT, InputOwner.SYSTEM, 1_000);
		AgentInputState escaping = state(1.0F, false, true);
		controller.apply(safety, escaping);

		controller.release(navigation);
		assertEquals(escaping, controller.currentState(AGENT).orElseThrow(),
				"action-owned release preserves the system lease");
		assertEquals(List.of(walking, escaping), sink.applied,
				"releasing a preempted action lease does not disturb physical system input");
		controller.release(safety);
		assertEquals(List.of(AGENT), sink.cleared, "system input clears only when its own lease releases");
		return 3;
	}

	private static int verifyPartialApplyCleanup() {
		for (boolean expire : new boolean[] {false, true}) {
			PartialSink sink = new PartialSink();
			LeasedServerInputController controller = new LeasedServerInputController(sink);
			InputLease lease = controller.acquire(AGENT, InputOwner.INTERACTION, 300);
			if (expire) {
				for (long tick = 1; tick < LeasedServerInputController.LEASE_TIMEOUT_TICKS; tick++) controller.tick();
			}
			sink.failApply = true;
			assertThrows(() -> controller.apply(lease, state(1.0F, true, true)), "partial apply reports failure");
			assertTrue(sink.physical != null, "the failing sink already changed physical inputs");
			assertTrue(controller.currentState(AGENT).isEmpty(), "partial application does not become logical input");
			if (expire) controller.tick();
			else controller.release(lease);
			assertTrue(sink.physical == null, "release and expiry clear partial physical inputs");
			assertEquals(1, sink.clears, "partial first application retains one physical cleanup obligation");
		}
		return 10;
	}

	private static int verifyPartialTransitionsRestoreLeaseOwner() {
		PartialSink sink = new PartialSink();
		LeasedServerInputController controller = new LeasedServerInputController(sink);
		InputLease navigation = controller.acquire(AGENT, InputOwner.NAVIGATION, 100);
		AgentInputState walking = state(1.0F, false, false);
		AgentInputState attacking = state(0.0F, true, true);
		controller.apply(navigation, walking);
		InputLease failedCombat = controller.acquire(AGENT, InputOwner.COMBAT, 200);
		sink.failApply = true;
		assertThrows(() -> controller.apply(failedCombat, attacking), "partial preemption reports failure");
		assertEquals(walking, controller.currentState(AGENT).orElseThrow(), "failed preemption preserves the logical winner");
		controller.release(failedCombat);
		assertEquals(walking, sink.physical, "releasing failed preemption restores the lower-priority physical input");
		InputLease combat = controller.acquire(AGENT, InputOwner.COMBAT, 200);
		controller.apply(combat, attacking);
		InputLease activeCombat = combat;
		sink.failApply = true;
		assertThrows(() -> controller.release(activeCombat), "partial restoration reports failure");
		assertEquals(attacking, controller.currentState(AGENT).orElseThrow(), "failed release preserves the owning lease");
		controller.tick();
		assertEquals(attacking, sink.physical, "the next tick restores the authoritative owner after partial restoration");
		controller.release(combat);
		assertEquals(walking, sink.physical, "the retained lower-priority lease restores after successful release");
		controller.release(navigation);
		assertTrue(sink.physical == null, "final release clears restored inputs");
		return 8;
	}

	private static final class PartialSink implements InputStateSink {
		private AgentInputState physical;
		private boolean failApply;
		private int clears;

		@Override
		public void apply(AgentId agentId, AgentInputState previous, AgentInputState state) {
			physical = state;
			if (failApply) {
				failApply = false;
				throw new IllegalStateException("apply failed after physical mutation");
			}
		}

		@Override
		public void clear(AgentId agentId, AgentInputState previous) {
			physical = null;
			clears++;
		}
	}

	private static int verifyFailedApplyRemainsRetryable() {
		FailingSink sink = new FailingSink();
		LeasedServerInputController controller = new LeasedServerInputController(sink);
		InputLease lease = controller.acquire(AGENT, InputOwner.INTERACTION, 300);
		AgentInputState requested = state(1.0F, true, false);
		long revisionAfterAcquire = controller.mutationRevision();

		sink.failNextApply();
		assertThrows(() -> controller.apply(lease, requested), "failed physical apply is reported");
		assertEquals(true, controller.currentState(AGENT).isEmpty(),
				"failed physical apply does not become the current state");
		assertEquals(revisionAfterAcquire, controller.mutationRevision(),
				"failed physical apply does not advance the mutation revision");

		controller.apply(lease, requested);
		assertEquals(2, sink.applyAttempts, "identical apply retries the physical transition");
		assertEquals(requested, controller.currentState(AGENT).orElseThrow(),
				"successful retry becomes the current state");
		assertEquals(revisionAfterAcquire + 1L, controller.mutationRevision(),
				"successful apply advances the mutation revision once");
		return 6;
	}

	private static int verifyFailedReleaseRemainsRetryable() {
		FailingSink sink = new FailingSink();
		LeasedServerInputController controller = new LeasedServerInputController(sink);
		InputLease lease = controller.acquire(AGENT, InputOwner.INTERACTION, 300);
		AgentInputState applied = state(0.0F, false, true);
		controller.apply(lease, applied);
		long revisionBeforeRelease = controller.mutationRevision();

		sink.failNextClear();
		assertThrows(() -> controller.release(lease), "failed physical release is reported");
		assertEquals(applied, controller.currentState(AGENT).orElseThrow(),
				"failed physical release keeps the successful current state");
		assertEquals(revisionBeforeRelease, controller.mutationRevision(),
				"failed physical release does not advance the mutation revision");

		controller.release(lease);
		assertEquals(2, sink.clearAttempts, "release retries the physical transition with the same lease");
		assertEquals(true, controller.currentState(AGENT).isEmpty(),
				"successful release removes the current state");
		assertEquals(revisionBeforeRelease + 1L, controller.mutationRevision(),
				"successful release advances the mutation revision once");
		return 6;
	}

	private static int verifyFailedPreemptingReleaseRemainsRetryable() {
		FailingSink sink = new FailingSink();
		LeasedServerInputController controller = new LeasedServerInputController(sink);
		InputLease navigation = controller.acquire(AGENT, InputOwner.NAVIGATION, 100);
		AgentInputState walking = state(1.0F, false, false);
		controller.apply(navigation, walking);
		InputLease combat = controller.acquire(AGENT, InputOwner.COMBAT, 200);
		AgentInputState attacking = state(0.0F, true, false);
		controller.apply(combat, attacking);
		long revisionBeforeRelease = controller.mutationRevision();

		sink.failNextApply();
		assertThrows(() -> controller.release(combat), "failed restoration apply is reported");
		assertEquals(attacking, controller.currentState(AGENT).orElseThrow(),
				"failed restoration keeps the preempting lease authoritative");
		assertEquals(revisionBeforeRelease, controller.mutationRevision(),
				"failed restoration does not advance the mutation revision");

		controller.release(combat);
		assertEquals(4, sink.applyAttempts, "release retries restoring the lower-priority physical state");
		assertEquals(walking, controller.currentState(AGENT).orElseThrow(),
				"successful retry restores the lower-priority lease");
		assertEquals(revisionBeforeRelease + 1L, controller.mutationRevision(),
				"successful preempting release advances the mutation revision once");
		return 6;
	}

	private static int verifyLeaseDeadman() {
		RecordingSink sink = new RecordingSink();
		LeasedServerInputController controller = new LeasedServerInputController(sink);
		InputLease lease = controller.acquire(AGENT, InputOwner.INTERACTION, 300);
		controller.apply(lease, state(1.0F, true, true));
		for (long tick = 1; tick < LeasedServerInputController.LEASE_TIMEOUT_TICKS; tick++) controller.tick();
		assertEquals(true, controller.currentState(AGENT).isPresent(), "lease remains active inside its renewal window");
		assertEquals(39, sink.ticked.size(), "held input reaches the physical tick sink throughout its lease");
		assertEquals(state(1.0F, true, true), sink.ticked.getLast(),
				"physical tick sink receives the winning held state");
		controller.tick();
		assertEquals(true, controller.currentState(AGENT).isEmpty(), "silent lease expires at the deadman deadline");
		assertEquals(List.of(AGENT), sink.cleared, "deadman neutralizes held physical input once");
		assertThrows(() -> controller.apply(lease, state(1.0F, true, true)), "expired lease cannot resume input");

		InputLease renewed = controller.acquire(AGENT, InputOwner.NAVIGATION, 100);
		for (int cycle = 0; cycle < 3; cycle++) {
			controller.apply(renewed, state(1.0F, false, false));
			for (long tick = 1; tick < LeasedServerInputController.LEASE_TIMEOUT_TICKS; tick++) controller.tick();
		}
		assertEquals(true, controller.currentState(AGENT).isPresent(), "regular input application renews the lease");
		return 7;
	}

	private static int verifyFailedDeadmanRemainsRetryable() {
		FailingSink sink = new FailingSink();
		LeasedServerInputController controller = new LeasedServerInputController(sink);
		InputLease lease = controller.acquire(AGENT, InputOwner.INTERACTION, 300);
		AgentInputState applied = state(1.0F, true, true);
		controller.apply(lease, applied);
		for (long tick = 1; tick < LeasedServerInputController.LEASE_TIMEOUT_TICKS; tick++) controller.tick();
		long revisionBeforeExpiration = controller.mutationRevision();

		sink.failNextClear();
		assertThrows(controller::tick, "failed deadman neutralization is reported");
		assertEquals(applied, controller.currentState(AGENT).orElseThrow(),
				"failed deadman neutralization keeps the lease retryable");
		assertEquals(revisionBeforeExpiration, controller.mutationRevision(),
				"failed deadman neutralization does not advance the mutation revision");

		controller.tick();
		assertEquals(2, sink.clearAttempts, "the next server tick retries deadman neutralization");
		assertEquals(true, controller.currentState(AGENT).isEmpty(),
				"successful deadman retry removes the expired lease");
		assertEquals(revisionBeforeExpiration + 1L, controller.mutationRevision(),
				"successful deadman retry advances the mutation revision once");
		return 6;
	}

	private static int verifyExactHandUseDriver() {
		ExactHandUseDriver driver = new ExactHandUseDriver();
		RecordingUseAccess block = RecordingUseAccess.target(ExactHandUseDriver.TargetKind.BLOCK, true, true);
		driver.start(AGENT, InteractionHand.MAIN_HAND, block);
		driver.tick(AGENT, InteractionHand.MAIN_HAND, block, 1L);
		assertEquals(List.of(InteractionHand.MAIN_HAND), block.targetHands,
				"block use receives only the requested main hand");
		assertEquals(List.of(), block.itemHands, "consumed block use does not fall through to item use");
		assertEquals(List.of(InteractionHand.MAIN_HAND), block.swingHands,
				"server-authoritative block success swings the requested hand");
		for (int tick = 0; tick < ExactHandUseDriver.REPEAT_COOLDOWN_TICKS; tick++) {
			driver.tick(AGENT, InteractionHand.MAIN_HAND, block, tick + 2L);
		}
		assertEquals(1, block.targetHands.size(), "block use waits for Carpet's pinned repeat cooldown");
		driver.tick(AGENT, InteractionHand.MAIN_HAND, block, 5L);
		assertEquals(2, block.targetHands.size(), "held block use repeats after the pinned cooldown");
		driver.stop(AGENT, block);

		RecordingUseAccess entity = RecordingUseAccess.target(ExactHandUseDriver.TargetKind.ENTITY, true, false);
		driver.start(AGENT, InteractionHand.OFF_HAND, entity);
		driver.tick(AGENT, InteractionHand.OFF_HAND, entity, 10L);
		assertEquals(List.of(InteractionHand.OFF_HAND), entity.targetHands,
				"entity use receives only the requested offhand");
		assertEquals(List.of(), entity.itemHands, "consumed entity use does not fall through to item use");
		driver.stop(AGENT, entity);

		RecordingUseAccess heldItem = RecordingUseAccess.item();
		driver.start(AGENT, InteractionHand.OFF_HAND, heldItem);
		driver.tick(AGENT, InteractionHand.OFF_HAND, heldItem, 20L);
		assertEquals(List.of(), heldItem.targetHands,
				"missed target does not fabricate a target interaction");
		assertEquals(List.of(InteractionHand.OFF_HAND), heldItem.itemHands,
				"item use starts with the requested offhand");
		assertEquals(InteractionHand.OFF_HAND, heldItem.usedHand(), "held item reports the requested active hand");
		for (int tick = 0; tick <= ExactHandUseDriver.REPEAT_COOLDOWN_TICKS; tick++) {
			driver.tick(AGENT, InteractionHand.OFF_HAND, heldItem, tick + 21L);
		}
		assertEquals(1, heldItem.itemHands.size(), "active held item is not restarted while its key remains down");
		driver.stop(AGENT, heldItem);
		assertEquals(1, heldItem.releaseCalls, "releasing use releases the active item once");
		assertEquals(false, heldItem.isUsingItem(), "releasing use clears the physical using state");

		RecordingUseAccess switchHands = RecordingUseAccess.item();
		switchHands.usingItem = true;
		switchHands.usedHand = InteractionHand.OFF_HAND;
		driver.start(AGENT, InteractionHand.MAIN_HAND, switchHands);
		assertEquals(1, switchHands.releaseCalls, "starting main-hand use releases an existing offhand use");
		driver.tick(AGENT, InteractionHand.MAIN_HAND, switchHands, 30L);
		assertEquals(List.of(InteractionHand.MAIN_HAND), switchHands.itemHands,
				"main-hand selection cannot fall back to the offhand");
		assertEquals(InteractionHand.MAIN_HAND, switchHands.usedHand(), "main hand becomes the observed active hand");
		driver.start(AGENT, InteractionHand.OFF_HAND, switchHands);
		assertEquals(2, switchHands.releaseCalls, "switching hands releases the previous held use");
		driver.tick(AGENT, InteractionHand.OFF_HAND, switchHands, 31L);
		assertEquals(List.of(InteractionHand.MAIN_HAND, InteractionHand.OFF_HAND), switchHands.itemHands,
				"hand switch starts the newly requested hand");
		assertEquals(InteractionHand.OFF_HAND, switchHands.usedHand(), "offhand becomes the observed active hand");
		driver.stop(AGENT, switchHands);

		int[] blockAttacks = {0};
		RecordingUseAccess combinedBlock = RecordingUseAccess.target(
				ExactHandUseDriver.TargetKind.BLOCK, true, false
		);
		Boolean blockResult = driver.arbitrate(
				AGENT, InteractionHand.OFF_HAND, combinedBlock, () -> {
					blockAttacks[0]++;
					return true;
				}, 40L
		);
		assertTrue(blockResult == null, "consumed exact-hand block use skips Carpet attack");
		assertEquals(0, blockAttacks[0], "consumed exact-hand block use never calls the attack action");
		driver.stop(AGENT, combinedBlock);

		int[] entityAttacks = {0};
		RecordingUseAccess combinedEntity = RecordingUseAccess.target(
				ExactHandUseDriver.TargetKind.ENTITY, true, false
		);
		Boolean entityResult = driver.arbitrate(
				AGENT, InteractionHand.OFF_HAND, combinedEntity, () -> {
					entityAttacks[0]++;
					return true;
				}, 50L
		);
		assertTrue(entityResult == null, "consumed exact-hand entity use skips Carpet attack");
		assertEquals(0, entityAttacks[0], "consumed exact-hand entity use never calls the attack action");
		driver.stop(AGENT, combinedEntity);

		RecordingUseAccess shield = RecordingUseAccess.item();
		int[] shieldAttacks = {0};
		Boolean raisedShield = driver.arbitrate(AGENT, InteractionHand.OFF_HAND, shield, () -> {
			shieldAttacks[0]++;
			return true;
		}, 60L);
		assertTrue(raisedShield == null, "raising an offhand shield suppresses the combined attack");
		assertEquals(0, shieldAttacks[0], "offhand shield prevents physical attack execution");
		Boolean heldShield = driver.arbitrate(AGENT, InteractionHand.OFF_HAND, shield, () -> {
			shieldAttacks[0]++;
			return true;
		}, 61L);
		assertTrue(heldShield == null, "held offhand shield keeps the combined attack suppressed");
		assertEquals(0, shieldAttacks[0], "held offhand shield cannot attack during use cooldown");
		driver.stop(AGENT, shield);
		RecordingUseAccess releasedShield = RecordingUseAccess.target(
				ExactHandUseDriver.TargetKind.BLOCK, false, false
		);
		Boolean resumedAttack = driver.arbitrate(AGENT, InteractionHand.OFF_HAND, releasedShield, () -> {
			shieldAttacks[0]++;
			return false;
		}, 62L);
		assertEquals(Boolean.FALSE, resumedAttack, "failed use returns the retained continuous attack result");
		assertEquals(1, shieldAttacks[0], "the same scheduled attack resumes after held use stops consuming");
		driver.stop(AGENT, releasedShield);

		RecordingUseAccess retry = RecordingUseAccess.targetAfterFailures(
				ExactHandUseDriver.TargetKind.BLOCK, 1, false
		);
		Boolean retryResult = driver.arbitrate(AGENT, InteractionHand.MAIN_HAND, retry, () -> {
			retry.events.add("attack");
			return true;
		}, 70L);
		assertEquals(Boolean.TRUE, retryResult, "failed use preserves Carpet's successful attack result");
		assertEquals(2, retry.targetHands.size(), "successful attack retries exact-hand use before arbitration returns");
		assertEquals(
				List.of("block:MAIN_HAND", "item:MAIN_HAND", "attack", "block:MAIN_HAND"),
				retry.events,
				"combined arbitration performs use, attack, and retry in one synchronous update"
		);
		assertEquals(1L, retry.events.stream().filter("attack"::equals).count(),
				"same-update exact-hand retry executes only one physical attack");
		driver.stop(AGENT, retry);

		RecordingUseAccess failedAttack = RecordingUseAccess.targetAfterFailures(
				ExactHandUseDriver.TargetKind.BLOCK, 2, false
		);
		Boolean failedAttackResult = driver.arbitrate(
				AGENT, InteractionHand.MAIN_HAND, failedAttack, () -> false, 80L
		);
		assertEquals(Boolean.FALSE, failedAttackResult, "failed Carpet attack result is preserved");
		assertEquals(1, failedAttack.targetHands.size(), "failed attack does not trigger the post-attack use retry");
		driver.stop(AGENT, failedAttack);

		RecordingUseAccess transition = RecordingUseAccess.target(
				ExactHandUseDriver.TargetKind.BLOCK, true, false
		);
		driver.arbitrate(AGENT, InteractionHand.MAIN_HAND, transition, () -> true, 90L);
		driver.arbitrate(AGENT, InteractionHand.MAIN_HAND, transition, () -> true, 91L);
		driver.arbitrate(AGENT, InteractionHand.MAIN_HAND, transition, () -> true, 92L);
		driver.arbitrate(AGENT, InteractionHand.MAIN_HAND, transition, () -> true, 93L);
		assertEquals(1, transition.targetHands.size(), "combined use consumes its final cooldown tick once");
		driver.tick(AGENT, InteractionHand.MAIN_HAND, transition, 93L);
		assertEquals(1, transition.targetHands.size(),
				"same-tick combined-to-use-only transition cannot execute use twice");
		driver.tick(AGENT, InteractionHand.MAIN_HAND, transition, 94L);
		assertEquals(2, transition.targetHands.size(), "held use resumes on the following server tick");
		driver.stop(AGENT, transition);

		RecordingUseAccess cleared = RecordingUseAccess.target(
				ExactHandUseDriver.TargetKind.BLOCK, true, false
		);
		driver.tick(AGENT, InteractionHand.MAIN_HAND, cleared, 100L);
		driver.stop(AGENT, cleared);
		driver.discard(AGENT);
		RecordingUseAccess restartedAfterClear = RecordingUseAccess.target(
				ExactHandUseDriver.TargetKind.BLOCK, true, false
		);
		driver.tick(AGENT, InteractionHand.MAIN_HAND, restartedAfterClear, 100L);
		assertEquals(1L, driver.acceptedUses(AGENT), "use receipt counts an accepted vanilla operation");
		assertEquals(1, restartedAfterClear.targetHands.size(),
				"full clear discards the historical execution stamp before controller reuse");
		driver.stop(AGENT, restartedAfterClear);
		driver.discard(AGENT);
		assertEquals(0L, driver.acceptedUses(AGENT), "cleanup clears accepted-use evidence before the next lease");
		return 41;
	}

	private static int verifyBoundedMotor() {
		AgentInputStates.MotorState initial = AgentInputStates.MotorState.initial(170.0F, 20.0F);
		AgentInputStates.MotorStep turn = AgentInputStates.stepMotor(
				initial,
				new AgentInputStates.MotorTarget(-170.0F, -20.0F, true, false, true),
				0L
		);
		assertEquals(AgentInputStates.MAX_YAW_STEP_DEGREES,
				Math.abs(AgentInputStates.shortestAngleDelta(initial.yaw(), turn.state().yaw())),
				"yaw takes the shortest bounded step across wrap");
		assertEquals(AgentInputStates.MAX_PITCH_STEP_DEGREES,
				Math.abs(initial.pitch() - turn.state().pitch()),
				"pitch takes a bounded step");
		assertTrue(turn.forward() >= 0.0F, "target-relative motor does not reverse unnecessarily");
		assertTrue(Math.abs(turn.strafe()) > 0.0F, "target-relative motor supplies useful strafing");

		AgentInputStates.MotorState accelerating = AgentInputStates.MotorState.initial(0.0F, 0.0F);
		AgentInputStates.MotorStep first = AgentInputStates.stepMotor(
				accelerating,
				new AgentInputStates.MotorTarget(0.0F, 0.0F, true, false, true),
				0L
		);
		assertEquals(AgentInputStates.MOVE_ACCELERATION, first.forward(), "motor accelerates by a bounded amount");
		AgentInputStates.MotorStep braking = AgentInputStates.stepMotor(
				first.state(),
				new AgentInputStates.MotorTarget(0.0F, 0.0F, false, false, false),
				1L
		);
		assertEquals(0.0F, braking.forward(), "motor brakes to zero without overshoot");

		AgentInputStates.MotorStep jumped = AgentInputStates.stepMotor(
				initial,
				new AgentInputStates.MotorTarget(-170.0F, -20.0F, true, true, true),
				3L
		);
		assertEquals(true, jumped.jump(), "jump request produces an edge pulse");
		AgentInputStates.MotorStep held = AgentInputStates.stepMotor(
				jumped.state(),
				new AgentInputStates.MotorTarget(-170.0F, -20.0F, true, true, true),
				4L
		);
		assertEquals(true, held.jump(), "held jump keeps Carpet's continuous jump action active across waypoints");
		AgentInputStates.MotorStep released = AgentInputStates.stepMotor(
				held.state(),
				new AgentInputStates.MotorTarget(-170.0F, -20.0F, true, false, true),
				5L
		);
		AgentInputStates.MotorStep repulsed = AgentInputStates.stepMotor(
				released.state(),
				new AgentInputStates.MotorTarget(-170.0F, -20.0F, true, true, true),
				6L
		);
		assertEquals(true, repulsed.jump(), "a released jump request can pulse again");
		AgentInputStates.MotorState facingEast = new AgentInputStates.MotorState(-90.0F, 0.0F, 0.0F, -1.0F, false);
		AgentInputStates.MotorStep turningSouth = AgentInputStates.stepMotor(
				facingEast,
				new AgentInputStates.MotorTarget(0.0F, 0.0F, true, false, false),
				7L
		);
		float remainingYaw = AgentInputStates.shortestAngleDelta(turningSouth.state().yaw(), 0.0F);
		assertEquals(-(float) Math.sin(Math.toRadians(remainingYaw)), turningSouth.strafe(),
				"movement is relative to the yaw applied this tick instead of the stale previous yaw");
		return 14;
	}

	private static AgentInputState state(float forward, boolean attack, boolean use) {
		return new AgentInputState(
				forward, 0.0F, false, false, forward > 0.0F, attack, use,
				0.0F, 0.0F, 0, InteractionHand.MAIN_HAND
		);
	}

	private static final class RecordingSink implements InputStateSink {
		private final List<AgentInputState> applied = new ArrayList<>();
		private final List<AgentInputState> ticked = new ArrayList<>();
		private final List<AgentId> cleared = new ArrayList<>();

		@Override
		public void apply(AgentId agentId, AgentInputState previous, AgentInputState state) {
			applied.add(state);
		}

		@Override
		public void tick(AgentId agentId, AgentInputState state) {
			ticked.add(state);
		}

		@Override
		public void clear(AgentId agentId, AgentInputState previous) {
			cleared.add(agentId);
		}
	}

	private static final class FailingSink implements InputStateSink {
		private int applyAttempts;
		private int clearAttempts;
		private boolean failApply;
		private boolean failClear;

		private void failNextApply() {
			failApply = true;
		}

		private void failNextClear() {
			failClear = true;
		}

		@Override
		public void apply(AgentId agentId, AgentInputState previous, AgentInputState state) {
			applyAttempts++;
			if (failApply) {
				failApply = false;
				throw new IllegalStateException("physical apply failed");
			}
		}

		@Override
		public void clear(AgentId agentId, AgentInputState previous) {
			clearAttempts++;
			if (failClear) {
				failClear = false;
				throw new IllegalStateException("physical clear failed");
			}
		}
	}

	private static final class RecordingUseAccess implements ExactHandUseDriver.PlayerUseAccess {
		private final ExactHandUseDriver.TargetKind targetKind;
		private final boolean targetConsumes;
		private final boolean targetSwings;
		private final boolean itemConsumes;
		private final List<InteractionHand> targetHands = new ArrayList<>();
		private final List<InteractionHand> itemHands = new ArrayList<>();
		private final List<InteractionHand> swingHands = new ArrayList<>();
		private final List<String> events = new ArrayList<>();
		private boolean usingItem;
		private InteractionHand usedHand = InteractionHand.MAIN_HAND;
		private int releaseCalls;
		private int remainingTargetFailures;

		private RecordingUseAccess(
				ExactHandUseDriver.TargetKind targetKind,
				boolean targetConsumes,
				boolean targetSwings,
				boolean itemConsumes,
				int remainingTargetFailures
		) {
			this.targetKind = targetKind;
			this.targetConsumes = targetConsumes;
			this.targetSwings = targetSwings;
			this.itemConsumes = itemConsumes;
			this.remainingTargetFailures = remainingTargetFailures;
		}

		private static RecordingUseAccess target(
				ExactHandUseDriver.TargetKind kind,
				boolean consumes,
				boolean swings
		) {
			return new RecordingUseAccess(kind, consumes, swings, false, 0);
		}

		private static RecordingUseAccess targetAfterFailures(
				ExactHandUseDriver.TargetKind kind,
				int failures,
				boolean swings
		) {
			return new RecordingUseAccess(kind, true, swings, false, failures);
		}

		private static RecordingUseAccess item() {
			return new RecordingUseAccess(ExactHandUseDriver.TargetKind.MISS, false, false, true, 0);
		}

		@Override
		public boolean isUsingItem() {
			return usingItem;
		}

		@Override
		public InteractionHand usedHand() {
			return usedHand;
		}

		@Override
		public void releaseUsingItem() {
			releaseCalls++;
			usingItem = false;
		}

		@Override
		public ExactHandUseDriver.TargetKind target() {
			return targetKind;
		}

		@Override
		public ExactHandUseDriver.TargetAttempt useBlock(InteractionHand hand) {
			targetHands.add(hand);
			events.add("block:" + hand);
			if (remainingTargetFailures > 0) {
				remainingTargetFailures--;
				return ExactHandUseDriver.TargetAttempt.pass();
			}
			return !targetConsumes
					? ExactHandUseDriver.TargetAttempt.pass()
					: ExactHandUseDriver.TargetAttempt.consumed(targetSwings);
		}

		@Override
		public ExactHandUseDriver.TargetAttempt useEntity(InteractionHand hand) {
			targetHands.add(hand);
			events.add("entity:" + hand);
			if (remainingTargetFailures > 0) {
				remainingTargetFailures--;
				return ExactHandUseDriver.TargetAttempt.pass();
			}
			return !targetConsumes
					? ExactHandUseDriver.TargetAttempt.pass()
					: ExactHandUseDriver.TargetAttempt.consumed(targetSwings);
		}

		@Override
		public boolean useItem(InteractionHand hand) {
			itemHands.add(hand);
			events.add("item:" + hand);
			if (itemConsumes) {
				usingItem = true;
				usedHand = hand;
			}
			return itemConsumes;
		}

		@Override
		public void swing(InteractionHand hand) {
			swingHands.add(hand);
		}
	}

	private static void assertEquals(Object expected, Object actual, String label) {
		if (!expected.equals(actual)) throw new AssertionError(label + ": expected <" + expected + "> but was <" + actual + ">");
	}

	private static void assertTrue(boolean condition, String label) {
		if (!condition) throw new AssertionError(label);
	}

	private static void assertThrows(Runnable action, String label) {
		try {
			action.run();
		} catch (IllegalStateException expected) {
			return;
		}
		throw new AssertionError(label + ": expected IllegalStateException");
	}
}
