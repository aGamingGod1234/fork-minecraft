package dev.agaminggod.arenaagents.client;

import dev.agaminggod.arenaagents.client.presentation.ArenaSpectatorState;
import dev.agaminggod.arenaagents.client.presentation.ArenaSpectatorHud;
import dev.agaminggod.arenaagents.client.presentation.ArenaHudPresentation;
import dev.agaminggod.arenaagents.client.presentation.ScenarioResultsScreen;
import dev.agaminggod.arenaagents.scenario.presentation.ArenaSpectatorSnapshot;
import dev.agaminggod.arenaagents.scenario.presentation.ArenaSpectatorSnapshotPayload;
import dev.agaminggod.arenaagents.scenario.presentation.DirectorRecommendation;
import dev.agaminggod.arenaagents.scenario.result.ScenarioPublicEvent;
import java.util.List;
import java.util.Optional;

public final class ArenaSpectatorStateVerification {
	private ArenaSpectatorStateVerification() {
	}

	public static int verify() {
		ArenaSpectatorState state = new ArenaSpectatorState();
		assertTrue(state.cameraDisabled(), "camera assistant defaults off");
		assertFalse(state.resultsAvailable(), "results are unavailable before a terminal snapshot");

		ArenaSpectatorSnapshot first = snapshot(5L, "run-a", 100L, false, "");
		assertTrue(state.accept(ArenaSpectatorSnapshotPayload.full(first)), "first full snapshot is accepted");
		assertFalse(state.accept(ArenaSpectatorSnapshotPayload.full(first)), "duplicate revision is ignored");
		assertEquals(5L, state.snapshot().orElseThrow().revision(), "accepted revision is retained");
		assertEquals(2, state.visibleFeed(140L).size(), "feed entries remain visible through their TTL");
		assertEquals(1, state.visibleFeed(141L).size(), "feed entries expire deterministically after their TTL");

		ArenaSpectatorSnapshot next = snapshot(6L, "run-a", 104L, false, "");
		assertTrue(state.accept(ArenaSpectatorSnapshotPayload.delta(first, next)),
				"delta on the current base revision is accepted");
		assertFalse(state.accept(ArenaSpectatorSnapshotPayload.delta(first, snapshot(7L, "run-a", 108L, false, ""))),
				"delta on a stale base revision is ignored");

		assertFalse(state.enableCamera(false, 104L), "camera assistant cannot enable outside spectator mode");
		assertTrue(state.enableCamera(true, 104L), "spectator can opt in with a current recommendation");
		assertFalse(state.cameraDisabled(), "successful opt-in enables the camera assistant");
		state.tickCamera(true, true, true, false, 160L);
		assertTrue(state.cameraDisabled(), "expired recommendation disables the camera assistant");

		for (ArenaSpectatorState.ManualOverride override : ArenaSpectatorState.ManualOverride.values()) {
			assertTrue(state.enableCamera(true, 104L), "camera can be re-enabled before " + override);
			state.onManualInput(override);
			assertTrue(state.cameraDisabled(), override + " immediately disables the camera assistant");
		}

		ArenaSpectatorSnapshot terminal = snapshot(
				8L,
				"run-a",
				200L,
				true,
				"0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef"
		);
		assertTrue(state.accept(ArenaSpectatorSnapshotPayload.full(terminal)), "newer terminal snapshot is accepted");
		assertTrue(state.resultsAvailable(), "terminal results remain available");
		assertEquals(0xFF42D39B, ArenaSpectatorHud.providerColor("codex"), "Codex has a stable HUD color");
		assertEquals(0xFF8E86FF, ArenaSpectatorHud.providerColor("gemini"), "Gemini has a stable HUD color");
		assertEquals(0xFF8E86FF, ArenaSpectatorHud.providerColor("antigravity"),
				"Antigravity shares the Gemini provider-family color");
		assertEquals(0xFFFFB45E, ArenaSpectatorHud.providerColor("kimi"), "Kimi has a stable HUD color");
		assertTrue(!ArenaHudPresentation.worldOverlayEnabled(),
				"agent state and standings never cover the live world view");
		ArenaHudPresentation.CompactRoster crowdedRoster = ArenaHudPresentation.compactRoster(8);
		assertEquals(4, crowdedRoster.visibleCount(), "compact HUD stays glanceable without covering the play area");
		assertEquals(4, crowdedRoster.overflowCount(), "compact HUD routes the remaining agents to the live-arena page");
		ArenaHudPresentation.CompactRoster shortRoster = ArenaHudPresentation.compactRoster(3);
		assertEquals(3, shortRoster.visibleCount(), "compact HUD does not invent empty agent rows");
		assertEquals(0, shortRoster.overflowCount(), "short rosters have no overflow notice");
		assertTrue(ArenaHudPresentation.compactHeight(8) <= 128,
				"the maximum roster HUD leaves most of the world view unobstructed");
		assertTrue(ArenaHudPresentation.compactHeight(1) < ArenaHudPresentation.compactHeight(8),
				"the HUD only occupies vertical space for visible agents");
		assertEquals(0xFF66D9A3, ArenaHudPresentation.healthColor(72), "healthy agents use the stable mint health color");
		assertEquals(0xFFF2BD58, ArenaHudPresentation.healthColor(35), "wounded agents use the amber warning color");
		assertEquals(0xFFFF737A, ArenaHudPresentation.healthColor(20), "critical agents use the coral fault color");
		assertEquals("01:05 / 02:00", ArenaHudPresentation.timeLabel(1_300L, 2_400L, false),
				"match time is readable without exposing implementation ticks");
		assertEquals("FINAL  02:00", ArenaHudPresentation.timeLabel(2_400L, 2_400L, true),
				"terminal match time has an unmistakable final state");
		assertEquals("FINAL  01:05 / 02:00", ArenaHudPresentation.timeLabel(1_300L, 2_400L, true),
				"terminal match time reports actual elapsed time instead of pretending the full duration ran");
		assertEquals("Waiting for model", ArenaHudPresentation.statusLabel("waiting_for_model"),
				"machine status identifiers become human-readable labels");
		assertEquals("Codex", ArenaHudPresentation.providerLabel("CODEX"),
				"provider identity is exposed in text instead of color alone");
		assertEquals(
				"#1 Agent A [codex] 4 | HP 85% | completed",
				ArenaSpectatorHud.standingLabel(terminal.standings().getFirst()),
				"HUD line exposes rank and a non-color provider/status badge"
		);
		List<String> resultLines = ScenarioResultsScreen.resultLines(terminal);
		assertEquals("Winner: Agent A", resultLines.getFirst(), "results lead with the winner");
		assertTrue(resultLines.indexOf("#1 Agent A [codex] 4 | HP 85% | completed")
				< resultLines.indexOf("Run: run-a"), "standings appear before technical run metadata");
		assertTrue(resultLines.contains("Map: last-valley @ 1.0.0"), "result lines include map id and version");
		assertTrue(resultLines.contains("Seeds: world=123 event=456"), "result lines include both seeds");
		assertTrue(resultLines.contains("Result SHA-256: " + terminal.resultHash()),
				"result lines include the canonical hash");
		assertEquals("Decisive event: " + terminal.feed().getLast().message(),
				resultLines.stream().filter(line -> line.startsWith("Decisive event: ")).findFirst().orElseThrow(),
				"results lead with the newest decisive event");
		ArenaSpectatorSnapshot failed = snapshot(
				9L, "run-failed", 220L, true,
				"abcdef0123456789abcdef0123456789abcdef0123456789abcdef0123456789", "Failed");
		List<String> failedLines = ScenarioResultsScreen.resultLines(failed);
		assertEquals("MATCH FAILED", ScenarioResultsScreen.headline(failed),
				"failed results use an explicit failure headline");
		assertEquals("Match failed", failedLines.getFirst(), "failed results do not announce a winner");
		assertTrue(failedLines.stream().noneMatch(line -> line.startsWith("Winner:")),
				"failed result hierarchy never crowns the top standing");
		assertTrue(failedLines.indexOf("Outcome: Failed after 220 ticks") < failedLines.indexOf("Run: run-failed"),
				"failed outcome appears before technical run metadata");
		state.tickCamera(true, true, true, false, 10_000L);
		assertTrue(state.resultsAvailable(), "time does not expire deterministic terminal results");
		state.dismissResults();
		assertFalse(state.resultsAvailable(), "explicit dismissal hides terminal results");

		ArenaSpectatorSnapshot newRun = snapshot(1L, "run-b", 0L, false, "");
		assertTrue(state.accept(ArenaSpectatorSnapshotPayload.full(newRun)),
				"a new run accepts its independently reset revision");
		assertFalse(state.resultsAvailable(), "starting another run clears dismissed terminal state");
		assertTrue(state.enableCamera(true, 0L), "new run recommendation can opt in");
		state.clear();
		assertTrue(state.snapshot().isEmpty(), "server tombstone clears an expired spectator snapshot");
		assertTrue(state.cameraDisabled(), "server tombstone clears camera opt-in");
		assertTrue(state.accept(ArenaSpectatorSnapshotPayload.full(newRun)),
				"a full snapshot can start cleanly after a server tombstone");
		state.clearOnDisconnect();
		assertTrue(state.snapshot().isEmpty(), "disconnect clears spectator snapshots");
		assertTrue(state.cameraDisabled(), "disconnect clears camera opt-in");
		return 56;
	}

	private static ArenaSpectatorSnapshot snapshot(
			long revision,
			String runId,
			long elapsedTick,
			boolean terminal,
			String resultHash
	) {
		return snapshot(revision, runId, elapsedTick, terminal, resultHash, terminal ? "Finished" : "Dawn");
	}

	private static ArenaSpectatorSnapshot snapshot(
			long revision,
			String runId,
			long elapsedTick,
			boolean terminal,
			String resultHash,
			String phaseTitle
	) {
		DirectorRecommendation recommendation = new DirectorRecommendation(
				"participant", "agent-a", "Follow Agent A", 10.0D, 80.0D, 10.0D, elapsedTick + 40L, 20);
		return new ArenaSpectatorSnapshot(
				revision,
				runId,
				"last-valley",
				"The Last Valley",
				phaseTitle,
				elapsedTick,
				36_000L,
				terminal,
				"1.0.0",
				123L,
				456L,
				resultHash,
				List.of(ArenaSpectatorSnapshot.Standing.candidate(
						"agent-a", "Agent A", "codex", 4.0D, 85, terminal ? "completed" : "acting")),
				List.of(
						new ScenarioPublicEvent(
								elapsedTick, "agent-a", "Agent A", "action_completed", "movement", 0.0D, "acting"),
						new ScenarioPublicEvent(
								Math.max(0L, elapsedTick - 60L), "agent-a", "Agent A", "state_changed", "other", 0.0D, "recovering")
				),
				terminal ? Optional.empty() : Optional.of(recommendation)
		);
	}

	private static void assertTrue(boolean condition, String label) {
		if (!condition) throw new AssertionError(label);
	}

	private static void assertFalse(boolean condition, String label) {
		assertTrue(!condition, label);
	}

	private static void assertEquals(Object expected, Object actual, String label) {
		if (!expected.equals(actual)) {
			throw new AssertionError(label + ": expected <" + expected + "> but was <" + actual + ">");
		}
	}
}
