package dev.agaminggod.arenaagents.scenario;

import dev.agaminggod.arenaagents.scenario.presentation.ArenaSpectatorSnapshot;
import dev.agaminggod.arenaagents.scenario.presentation.ArenaSpectatorSnapshotPayload;
import dev.agaminggod.arenaagents.scenario.presentation.DirectorRecommendation;
import dev.agaminggod.arenaagents.scenario.result.ScenarioPublicEvent;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

public final class ArenaSpectatorSnapshotVerification {
	private ArenaSpectatorSnapshotVerification() {
	}

	public static int verify() {
		List<ArenaSpectatorSnapshot.Standing> standings = new ArrayList<>();
		for (int index = 0; index < 16; index++) {
			standings.add(ArenaSpectatorSnapshot.Standing.candidate(
					"agent-" + index,
					"Agent " + index,
					index % 3 == 0 ? "codex" : index % 3 == 1 ? "gemini" : "kimi",
					index == 14 || index == 15 ? 99.0D : index,
					Math.max(0, 100 - index * 9),
					index == 15 ? "eliminated" : "acting"
			));
		}
		List<ScenarioPublicEvent> feed = new ArrayList<>();
		for (int index = 0; index < 9; index++) {
			feed.add(new ScenarioPublicEvent(
					100L + index,
					"agent-" + index,
					"Agent " + index,
					index % 2 == 0 ? "action_completed" : "state_changed",
					index % 2 == 0 ? "movement" : "other",
					0.0D,
					index % 2 == 0 ? "acting" : "recovering"
			));
		}
		DirectorRecommendation overview = new DirectorRecommendation(
				"anchor", "overview", "Arena overview", 12.5D, 98.0D, 70.5D, 140L, 10);
		DirectorRecommendation participant = new DirectorRecommendation(
				"participant", "agent-9", "Follow Agent 9", 4.5D, 82.0D, 2.5D, 140L, 20);
		assertEquals(Optional.of(participant),
				DirectorRecommendation.select(120L, List.of(overview, participant)),
				"recommendations rank participant and fixed-anchor candidates deterministically");
		assertEquals(Optional.of(overview.withExpiry(160L)),
				DirectorRecommendation.select(141L, List.of(overview.withExpiry(160L), participant)),
				"expired recommendation candidates are ignored");

		ArenaSpectatorSnapshot first = snapshot(41L, 120L, false, standings, feed, Optional.of(participant), "");
		assertEquals(16, first.standings().size(), "snapshot carries the complete sixteen-agent arena roster");
		assertEquals(List.of("agent-14", "agent-15"),
				first.standings().subList(0, 2).stream().map(ArenaSpectatorSnapshot.Standing::participantId).toList(),
				"score ties use stable participant id ordering");
		assertEquals(java.util.stream.IntStream.rangeClosed(1, 16).boxed().toList(),
				first.standings().stream().map(ArenaSpectatorSnapshot.Standing::rank).toList(),
				"standings include explicit deterministic rank numbers");
		assertEquals(6, first.feed().size(), "snapshot retains at most six public feed entries");
		assertEquals(103L, first.feed().getFirst().elapsedTick(), "feed keeps the six newest entries in display order");

		ArenaSpectatorSnapshotPayload full = ArenaSpectatorSnapshotPayload.full(first);
		String encoded = full.encodedUpdate();
		assertTrue(encoded.getBytes(StandardCharsets.UTF_8).length <= ArenaSpectatorSnapshotPayload.MAX_ENCODED_BYTES,
				"full spectator payload fits the 8192-byte wire budget");
		for (String forbidden : List.of("prompt", "observation", "goal", "arguments", "token", "filePath", "providerError")) {
			assertFalse(encoded.contains(forbidden), "spectator payload excludes private field " + forbidden);
		}
		assertEquals(first, full.applyTo(Optional.empty()), "full payload round-trips the public snapshot");

		ArenaSpectatorSnapshot second = snapshot(
				42L, 124L, false, standings, feed,
				Optional.of(participant.withExpiry(164L)), "");
		ArenaSpectatorSnapshotPayload delta = ArenaSpectatorSnapshotPayload.delta(first, second);
		assertEquals(ArenaSpectatorSnapshotPayload.Kind.DELTA, delta.kind(), "changed state uses a delta payload");
		assertEquals(41L, delta.baseRevision(), "delta names the exact base revision");
		assertEquals(second, delta.applyTo(Optional.of(first)), "delta reconstructs the next snapshot exactly");
		assertThrows(IllegalArgumentException.class, () -> delta.applyTo(Optional.empty()),
				"delta without its exact base is rejected");
		assertThrows(IllegalArgumentException.class, () -> new ArenaSpectatorSnapshotPayload(
				"{\"schemaVersion\":1,\"kind\":\"full\",\"revision\":1,\"snapshot\":{},\"prompt\":\"secret\"}"
		), "payload codec rejects forbidden unknown fields instead of ignoring them");
		assertThrows(IllegalArgumentException.class, () -> new ArenaSpectatorSnapshotPayload(
				"x".repeat(ArenaSpectatorSnapshotPayload.MAX_ENCODED_BYTES + 1)
		), "payload hard rejects bytes above the wire budget");

		ArenaSpectatorSnapshot.PublicationCadence cadence = new ArenaSpectatorSnapshot.PublicationCadence(4);
		assertTrue(cadence.due(0L), "first spectator publication is immediately due");
		cadence.markPublished(0L);
		assertFalse(cadence.due(1L), "publication is suppressed one tick after send");
		assertFalse(cadence.due(3L), "publication is suppressed before the fourth tick");
		assertTrue(cadence.due(4L), "publication is due exactly four ticks later");

		ArenaSpectatorSnapshot.PublicView publicView = new ArenaSpectatorSnapshot.PublicView(
				"run-0002",
				"citadel-collapse",
				"Citadel Collapse",
				"Collapse",
				8_000L,
				24_000L,
				false,
				"1.0.0",
				777L,
				888L,
				"",
				12.0D,
				80.0D,
				30.0D,
				List.of(
						new ArenaSpectatorSnapshot.ParticipantView(
								"agent-a", "Agent A", "codex", 4.0D, 90, "acting", 4.0D, 81.0D, 5.0D),
						new ArenaSpectatorSnapshot.ParticipantView(
								"agent-b", "Agent B", "gemini", 6.0D, 20, "recovering", 8.0D, 81.0D, 9.0D)
				),
				feed
		);
		ArenaSpectatorSnapshot adapted = ArenaSpectatorSnapshot.fromPublicView(50L, publicView);
		assertEquals("agent-b", adapted.standings().getFirst().participantId(),
				"production public view ranks standings by score");
		assertEquals(20, adapted.standings().getFirst().healthPercent(),
				"production public view carries bounded health badges");
		assertEquals("gemini", adapted.standings().getFirst().providerFamily(),
				"production public view carries provider family only");
		assertEquals("agent-b", adapted.recommendation().orElseThrow().targetId(),
				"director favors the deterministic high-interest participant focus");
		assertEquals(8_040L, adapted.recommendation().orElseThrow().expiresAtTick(),
				"production recommendations have a bounded forty-tick lifetime");
		assertFalse(ArenaSpectatorSnapshotPayload.full(adapted).encodedUpdate().contains("prompt"),
				"production public-view adapter cannot introduce prompt data");
		assertEquals(Optional.of(publicView),
				ArenaSpectatorSnapshot.retainPublication(Optional.empty(), Optional.of(publicView)),
				"an observed live view becomes the publication candidate");
		assertTrue(ArenaSpectatorSnapshot.retainPublication(Optional.of(publicView), Optional.empty()).isEmpty(),
				"a vanished non-terminal view is not replayed");
		ArenaSpectatorSnapshot.PublicView terminalView = new ArenaSpectatorSnapshot.PublicView(
				publicView.runId(), publicView.scenarioId(), publicView.scenarioTitle(), "Finished",
				publicView.durationTicks(), publicView.durationTicks(), true, publicView.mapVersion(),
				publicView.worldSeed(), publicView.eventSeed(),
				"0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef",
				publicView.originX(), publicView.originY(), publicView.originZ(),
				publicView.participants(), publicView.feed()
		);
		assertEquals(Optional.of(terminalView),
				ArenaSpectatorSnapshot.retainPublication(Optional.of(terminalView), Optional.empty()),
				"a terminal view is retained until the cadence can publish it");
		return 39;
	}

	private static ArenaSpectatorSnapshot snapshot(
			long revision,
			long elapsedTick,
			boolean terminal,
			List<ArenaSpectatorSnapshot.Standing> standings,
			List<ScenarioPublicEvent> feed,
			Optional<DirectorRecommendation> recommendation,
			String resultHash
	) {
		return new ArenaSpectatorSnapshot(
				revision,
				"run-0001",
				"citadel-collapse",
				"Citadel Collapse",
				terminal ? "Finished" : "Open Conflict",
				elapsedTick,
				24_000L,
				terminal,
				"1.0.0",
				99L,
				101L,
				resultHash,
				standings,
				feed,
				recommendation
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

	private static <T extends Throwable> void assertThrows(Class<T> type, Runnable action, String label) {
		try {
			action.run();
		} catch (Throwable throwable) {
			if (type.isInstance(throwable)) return;
			throw new AssertionError(label + ": expected " + type.getSimpleName() + " but got " + throwable, throwable);
		}
		throw new AssertionError(label + ": expected " + type.getSimpleName());
	}
}
