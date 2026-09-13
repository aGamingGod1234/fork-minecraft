package dev.agaminggod.arenaagents.client.control;

import dev.agaminggod.arenaagents.agent.AgentIdentity;
import dev.agaminggod.arenaagents.control.AgentControlAgent;
import dev.agaminggod.arenaagents.control.AgentControlCatalog;
import dev.agaminggod.arenaagents.control.AgentControlModelOption;
import dev.agaminggod.arenaagents.control.AgentControlSnapshot;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;

public final class AgentClientPresentationVerification {
	private AgentClientPresentationVerification() {
	}

	public static int verify() {
		SnapshotRefreshPolicy.Tick disconnected = SnapshotRefreshPolicy.advance(0, false, 20);
		assertFalse(disconnected.requestSnapshot(), "disconnected clients do not send snapshot requests");
		assertEquals(0, disconnected.nextCountdown(), "disconnect resets the background refresh timer");

		SnapshotRefreshPolicy.Tick due = SnapshotRefreshPolicy.advance(0, true, 20);
		assertTrue(due.requestSnapshot(), "connected clients refresh snapshots even with the console closed");
		assertEquals(19, due.nextCountdown(), "a refresh starts an exact twenty-tick interval");
		SnapshotRefreshPolicy.Tick waiting = SnapshotRefreshPolicy.advance(7, true, 20);
		assertFalse(waiting.requestSnapshot(), "background polling waits until the interval expires");
		assertEquals(6, waiting.nextCountdown(), "background polling advances once per client tick");
		SnapshotRefreshPolicy.Tick hidden = SnapshotRefreshPolicy.advance(0, true, false, 20, 100);
		assertTrue(hidden.requestSnapshot(), "hidden controls retain a slow roster heartbeat");
		assertEquals(99, hidden.nextCountdown(), "hidden controls avoid polling a full snapshot every second");
		SnapshotRefreshPolicy.Tick shown = SnapshotRefreshPolicy.advance(99, true, true, 20, 100);
		assertFalse(shown.requestSnapshot(), "showing controls advances the next request without a duplicate tick send");
		assertEquals(0, shown.nextCountdown(), "showing controls makes the next tick immediately due");

		AgentControlClient.SnapshotAcknowledgements acknowledgements =
				new AgentControlClient.SnapshotAcknowledgements();
		acknowledgements.recordRequest();
		long mutationId = acknowledgements.beginMutation();
		acknowledgements.recordRequest();
		assertEquals(0L, acknowledgements.acceptSnapshot(),
				"a refresh already in flight cannot acknowledge a later mutation");
		assertEquals(mutationId, acknowledgements.acceptSnapshot(),
				"the first request sent after the mutation carries its acknowledgement");
		long disconnectedMutation = acknowledgements.beginMutation();
		acknowledgements.recordRequest();
		acknowledgements.clear();
		assertEquals(0L, acknowledgements.acceptSnapshot(),
				"disconnect clears queued mutation acknowledgements");
		assertTrue(disconnectedMutation > mutationId, "mutation receipts increase monotonically");

		AgentControlAgent agent = new AgentControlAgent(
				"12345678-1234-1234-1234-123456789abc", "12345678", "Builder", "codex",
				"gpt-5.6-sol", "high", "SolCyan_12345678", 0, "IDLE", "", 0, "", "", true, true
		);
		AgentControlSnapshot snapshot = new AgentControlSnapshot(true, 1L, List.of(agent));
		assertTrue(SnapshotRevisionPolicy.isNewer(null, snapshot),
				"the first control snapshot is accepted");
		assertFalse(SnapshotRevisionPolicy.isNewer(snapshot,
				new AgentControlSnapshot(false, 1L, List.of(agent))),
				"an equal revision cannot replace current permissions or presentation state");
		AgentControlSnapshot readOnly = new AgentControlSnapshot(false, 2L, List.of(agent));
		assertFalse(SnapshotRevisionPolicy.isNewer(readOnly, snapshot),
				"an older revision is ignored");
		assertTrue(SnapshotRevisionPolicy.isNewer(snapshot, readOnly),
				"a newer revision can publish a read-only snapshot");
		assertEquals(agent, AgentPlayerIdentity.find(snapshot, "SolCyan_12345678").orElseThrow(),
				"exact player profile names resolve the custom agent identity");
		assertEquals(agent, AgentPlayerIdentity.find(snapshot, "solcyan_12345678").orElseThrow(),
				"profile-name casing differences do not break custom skins or label suppression");
		assertTrue(AgentPlayerIdentity.find(snapshot, "DifferentPlayer").isEmpty(),
				"ordinary players never inherit agent presentation");
		assertTrue(AgentPlayerIdentity.find(snapshot, "  ").isEmpty(),
				"blank profile names never match an agent");

		String patternHumanName = agent.playerName();
		UUID offlineHumanUuid = UUID.nameUUIDFromBytes(
				("OfflinePlayer:" + patternHumanName).getBytes(StandardCharsets.UTF_8)
		);
		assertEquals(AgentIdentity.offlinePlayerUuid(patternHumanName), offlineHumanUuid,
				"an offline human with an agent-pattern name has the same deterministic UUID as the old fallback");
		AgentControlSnapshot loadingSnapshot = new AgentControlSnapshot(true, 2L, List.of());
		assertTrue(AgentPlayerIdentity.find(loadingSnapshot, patternHumanName).isEmpty(),
				"name pattern and matching offline UUID do not grant agent presentation before snapshot membership");
		assertEquals(agent, AgentPlayerIdentity.find(snapshot, patternHumanName).orElseThrow(),
				"authoritative snapshot membership enables agent presentation after synchronization");

		try {
			AgentControlCatalog.installRuntimeCatalog(List.of(new AgentControlModelOption(
					"codex", "gpt-future", "GPT Future", List.of("medium"), List.of("priority")
			)));
			AgentControlClient.Preferences remembered = new AgentControlClient.Preferences(
					"codex", "gpt-future", "medium", "priority");
			AgentControlCatalog.resetRuntimeCatalog();
			assertEquals(remembered, AgentControlClient.Preferences.reconcile(remembered, false),
					"temporary fallback catalog preserves a runtime-only preference");

			AgentControlCatalog.installRuntimeCatalog(List.of(new AgentControlModelOption(
					"codex", "gpt-next", "GPT Next", List.of("high"), List.of("priority")
			)));
			AgentControlClient.Preferences repaired = AgentControlClient.Preferences.reconcile(remembered, true);
			assertEquals("codex", repaired.provider(), "catalog change replaces an unavailable preferred provider");
			assertEquals("gpt-next", repaired.model(), "authoritative catalog chooses the available model");
			assertEquals("high", repaired.reasoning(), "authoritative catalog chooses available reasoning");
			assertEquals("priority", repaired.serviceTier(), "available service tier survives catalog reconciliation");
		} finally {
			AgentControlCatalog.resetRuntimeCatalog();
		}
		return 26;
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
