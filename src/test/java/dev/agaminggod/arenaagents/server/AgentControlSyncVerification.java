package dev.agaminggod.arenaagents.server;

import dev.agaminggod.arenaagents.scenario.presentation.ScenarioBuildProgress;
import dev.agaminggod.arenaagents.scenario.presentation.ScenarioPresentationExpiry;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

public final class AgentControlSyncVerification {
	private AgentControlSyncVerification() {
	}

	public static int verify() {
		AtomicInteger actions = new AtomicInteger();
		assertTrue(!AgentControlSync.executeAuthorizedControlAction(false, actions::incrementAndGet),
				"non-operator control request is rejected");
		assertEquals(0, actions.get(), "non-operator rejection happens before snapshot or action work");
		assertTrue(AgentControlSync.executeAuthorizedControlAction(true, actions::incrementAndGet),
				"operator control request is accepted");
		assertEquals(1, actions.get(), "operator snapshot or action work runs exactly once");

		AtomicInteger denials = new AtomicInteger();
		assertTrue(!AgentControlSync.executeAuthorizedControlAction(
				false, actions::incrementAndGet, denials::incrementAndGet),
				"denied mutation returns an explicit rejection result");
		assertEquals(1, denials.get(), "denied mutation publishes one rejection");
		assertTrue(AgentControlSync.executeAuthorizedControlAction(
				true, actions::incrementAndGet, denials::incrementAndGet),
				"authorized mutation does not use its rejection path");
		assertEquals(1, denials.get(), "authorized mutation leaves the rejection count unchanged");
		assertEquals(2, actions.get(), "authorized mutation still runs exactly once");

		AgentControlSync.SpectatorPublication publication = new AgentControlSync.SpectatorPublication();
		UUID playerId = UUID.fromString("11111111-2222-3333-4444-555555555555");
		ScenarioBuildProgress rejection = ScenarioBuildProgress.rejected(
				"rejected-a", "The Last Valley", 0, 70, 0, "Launch rejected");
		publication.rememberDirectBuild(playerId, rejection, 100L);
		assertEquals(rejection, publication.directBuild(playerId, 100L).orElseThrow(),
				"direct launch rejection is retained after publication");
		assertEquals(rejection, publication.directBuild(
				playerId, 100L + ScenarioPresentationExpiry.BUILD_TERMINAL_TTL_TICKS - 1L).orElseThrow(),
				"direct launch rejection remains visible until the terminal TTL boundary");
		assertTrue(publication.directBuild(
				playerId, 100L + ScenarioPresentationExpiry.BUILD_TERMINAL_TTL_TICKS).isEmpty(),
				"direct launch rejection expires exactly at the terminal TTL boundary");
		publication.rememberDirectBuild(playerId, rejection, 500L);
		assertTrue(publication.directBuild(
				playerId, 500L + ScenarioPresentationExpiry.BUILD_TERMINAL_TTL_TICKS - 1L).isPresent(),
				"a repeated direct rejection starts a fresh terminal TTL");
		publication.forgetBuild(playerId);
		assertTrue(publication.directBuild(playerId, 500L).isEmpty(),
				"build tombstone cleanup removes the direct rejection clock and payload");
		return 14;
	}

	private static void assertTrue(boolean value, String label) {
		if (!value) throw new AssertionError(label);
	}

	private static void assertEquals(int expected, int actual, String label) {
		if (expected != actual) {
			throw new AssertionError(label + ": expected <" + expected + "> but was <" + actual + ">");
		}
	}

	private static void assertEquals(Object expected, Object actual, String label) {
		if (!expected.equals(actual)) {
			throw new AssertionError(label + ": expected <" + expected + "> but was <" + actual + ">");
		}
	}
}
