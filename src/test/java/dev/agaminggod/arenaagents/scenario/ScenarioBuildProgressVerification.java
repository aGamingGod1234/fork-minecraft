package dev.agaminggod.arenaagents.scenario;

import dev.agaminggod.arenaagents.client.presentation.ScenarioBuildProgressState;
import dev.agaminggod.arenaagents.scenario.presentation.ScenarioBuildProgress;
import dev.agaminggod.arenaagents.scenario.presentation.ScenarioBuildProgressPayload;
import dev.agaminggod.arenaagents.scenario.presentation.ScenarioOperatorMessagePolicy;

public final class ScenarioBuildProgressVerification {
	private ScenarioBuildProgressVerification() {
	}

	public static int verify() {
		ScenarioBuildProgress initial = new ScenarioBuildProgress(
				"build-1", "The Last Valley", "canonicalizing", 1L,
				8, 32, 0, 120, 78, -40,
				ScenarioBuildProgress.Status.BUILDING, "Preparing the arena blueprint"
		);
		ScenarioBuildProgressPayload payload = ScenarioBuildProgressPayload.fromProgress(initial);
		assertEquals(initial, payload.progress(), "progress payload round trips its state");
		assertTrue(payload.encodedProgress().getBytes(java.nio.charset.StandardCharsets.UTF_8).length
				<= ScenarioBuildProgressPayload.MAX_ENCODED_BYTES, "progress payload is bounded");

		ScenarioBuildProgressState state = new ScenarioBuildProgressState();
		assertTrue(state.accept(payload), "first build progress is accepted");
		assertFalse(state.accept(payload), "duplicate build progress is ignored");
		ScenarioBuildProgress changed = new ScenarioBuildProgress(
				"build-1", "The Last Valley", "applying_blocks", 2L,
				16, 32, 11, 120, 78, -40,
				ScenarioBuildProgress.Status.BUILDING, "Applying the arena blueprint"
		);
		assertTrue(state.accept(ScenarioBuildProgressPayload.fromProgress(changed)),
				"newer build progress is accepted");
		assertEquals(11, state.progress().orElseThrow().changedBlocks(),
				"client state retains actual changed-block count");
		assertTrue(state.shouldDisplayBeforeMatch(true),
				"live construction outranks the generic pre-match spectator snapshot");
		assertEquals("Processing blueprint blocks", changed.humanPhase(),
				"build phase describes work without claiming that blocks already changed");
		ScenarioBuildProgress repairing = new ScenarioBuildProgress(
				"build-1", "The Last Valley", "repair", 3L,
				2, 5, 13, 120, 78, -40,
				ScenarioBuildProgress.Status.BUILDING, "Corrected 2 of 5 mismatched arena blocks"
		);
		assertEquals("Repairing arena", repairing.humanPhase(),
				"corrective verification has a distinct operator-visible phase");
		ScenarioBuildProgress stale = new ScenarioBuildProgress(
				"build-1", "The Last Valley", "canonicalizing", 1L,
				32, 32, 0, 120, 78, -40,
				ScenarioBuildProgress.Status.BUILDING, "stale"
		);
		assertFalse(state.accept(ScenarioBuildProgressPayload.fromProgress(stale)),
				"stale progress is ignored");

		ScenarioBuildProgress ready = new ScenarioBuildProgress(
				"build-1", "The Last Valley", "complete", 3L,
				32, 32, 32, 120, 78, -40,
				ScenarioBuildProgress.Status.READY, "Arena ready at 120, 78, -40"
		);
		assertTrue(state.accept(ScenarioBuildProgressPayload.fromProgress(ready)),
				"terminal ready progress is accepted");
		assertTrue(state.progress().orElseThrow().terminal(), "ready progress is terminal");
		assertFalse(state.shouldDisplayBeforeMatch(true),
				"ready construction yields to the activation or match dashboard");
		assertTrue(state.shouldDisplayBeforeMatch(false),
				"ready construction remains visible until any activation snapshot arrives");
		assertEquals("120, 78, -40", state.progress().orElseThrow().originLabel(),
				"origin label is human-readable");
		ScenarioBuildProgress rejected = ScenarioBuildProgress.rejected(
				"rejected-1", "The Last Valley", 4, 70, 9, "Automation is offline");
		assertTrue(rejected.terminal() && rejected.status() == ScenarioBuildProgress.Status.FAILED,
				"server-side launch rejection becomes a terminal dashboard state");
		assertEquals(0, rejected.percent(), "rejected builds do not pretend that construction completed");
		ScenarioBuildProgress activationFailure = ScenarioBuildProgress.rejected(
				"build-1", "The Last Valley", 4L, 120, 78, -40,
				"Agent coordinator could not start contestants");
		assertEquals(4L, activationFailure.revision(),
				"activation failures supersede the ready dashboard revision");
		assertEquals(ScenarioOperatorMessagePolicy.Surface.ACTION_BAR,
				ScenarioOperatorMessagePolicy.surface(ScenarioOperatorMessagePolicy.Event.PREPARING),
				"preparation notices stay above the hotbar");
		assertEquals(ScenarioOperatorMessagePolicy.Surface.ACTION_BAR,
				ScenarioOperatorMessagePolicy.surface(ScenarioOperatorMessagePolicy.Event.STARTED),
				"start notices stay above the hotbar");
		assertEquals(ScenarioOperatorMessagePolicy.Surface.ACTION_BAR,
				ScenarioOperatorMessagePolicy.surface(ScenarioOperatorMessagePolicy.Event.FINISHED),
				"finish notices stay above the hotbar");
		assertEquals(ScenarioOperatorMessagePolicy.Surface.FIELD_CONSOLE,
				ScenarioOperatorMessagePolicy.surface(ScenarioOperatorMessagePolicy.Event.FAILURE),
				"failures remain durable in the field console instead of chat");

		ScenarioBuildProgress completedTick = ScenarioBuildProgress.fromResetTick(
				"build-2", "The Thinking Tower",
				new dev.agaminggod.arenaagents.scenario.runtime.ScenarioArenaResetJob.Tick(
						dev.agaminggod.arenaagents.scenario.runtime.ScenarioArenaResetJob.Phase.COMPLETE,
						1, 48, 48, 37, java.util.Optional.empty(), ""),
				10, 72, -90, 4L, "Arena ready"
		);
		assertEquals(ScenarioBuildProgress.Status.READY, completedTick.status(),
				"a completed reset tick becomes ready instead of invalid building progress");
		assertEquals("complete", completedTick.phase(), "a completed reset tick keeps its terminal phase");
		ScenarioBuildProgress failedTick = ScenarioBuildProgress.fromResetTick(
				"build-3", "The Thinking Tower",
				new dev.agaminggod.arenaagents.scenario.runtime.ScenarioArenaResetJob.Tick(
						dev.agaminggod.arenaagents.scenario.runtime.ScenarioArenaResetJob.Phase.FAILED,
						0, 32, 48, 21, java.util.Optional.empty(), "RESET_VERIFICATION_MISMATCH"),
				10, 72, -90, 5L, "Arena verification did not match"
		);
		assertEquals(ScenarioBuildProgress.Status.FAILED, failedTick.status(),
				"a failed reset tick becomes failed instead of invalid building progress");
		assertEquals(21, failedTick.changedBlocks(), "terminal reset mapping preserves actual world changes");
		assertEquals("RESET_VERIFICATION_MISMATCH", failedTick.errorCode(),
				"terminal reset errors retain a stable machine-readable code");
		ScenarioBuildProgress confirmation = ScenarioBuildProgress.confirmationRequired(
				"build-4", "The Last Valley", 1L, 0, 70, 0,
				"Confirm overwrite", "token-1");
		assertEquals(ScenarioBuildProgress.Status.CONFIRMATION_REQUIRED, confirmation.status(),
				"destructive preflight is distinct from a failed build");
		assertFalse(confirmation.terminal(), "confirmation remains actionable");
		ScenarioBuildProgress cancelled = ScenarioBuildProgress.cancelled(
				"build-4", "The Last Valley", 2L, 12, 4, 0, 70, 0, "Cancelled safely");
		assertTrue(cancelled.terminal(), "cancelled build is terminal");
		assertTrue(dev.agaminggod.arenaagents.scenario.presentation.ScenarioPresentationExpiry.expired(
				10L, 10L + dev.agaminggod.arenaagents.scenario.presentation.ScenarioPresentationExpiry.BUILD_TERMINAL_TTL_TICKS,
				dev.agaminggod.arenaagents.scenario.presentation.ScenarioPresentationExpiry.BUILD_TERMINAL_TTL_TICKS),
				"terminal build publication expires at its bounded retention window");

		assertThrows(IllegalArgumentException.class, () -> new ScenarioBuildProgress(
				"", "title", "phase", 1L, 0, 0, 0, 0, 0, 0,
				ScenarioBuildProgress.Status.BUILDING, "detail"), "build id is required");
		assertThrows(IllegalArgumentException.class, () -> new ScenarioBuildProgress(
				"build-1", "title", "phase", 0L, 0, 0, 0, 0, 0, 0,
				ScenarioBuildProgress.Status.BUILDING, "detail"), "revision is positive");
		assertThrows(IllegalArgumentException.class, () -> new ScenarioBuildProgress(
				"build-1", "title", "phase", 1L, 9, 8, 0, 0, 0, 0,
				ScenarioBuildProgress.Status.BUILDING, "detail"), "completed cannot exceed total");
		assertThrows(IllegalArgumentException.class, () -> new ScenarioBuildProgressPayload(
				"{\"schemaVersion\":999}"), "unsupported payload schema is rejected");
		return 37;
	}

	private static void assertTrue(boolean condition, String label) {
		if (!condition) throw new AssertionError(label);
	}

	private static void assertFalse(boolean condition, String label) {
		assertTrue(!condition, label);
	}

	private static void assertEquals(Object expected, Object actual, String label) {
		if (!expected.equals(actual)) throw new AssertionError(label + ": expected <" + expected + "> but was <" + actual + ">");
	}

	private static void assertThrows(Class<? extends Throwable> type, Runnable action, String label) {
		try {
			action.run();
		} catch (Throwable thrown) {
			if (type.isInstance(thrown)) return;
			throw new AssertionError(label + ": unexpected exception " + thrown, thrown);
		}
		throw new AssertionError(label + ": expected " + type.getSimpleName());
	}
}
