package dev.agaminggod.arenaagents.client.gui;

import java.util.concurrent.atomic.AtomicInteger;

public final class AgentControlSubmissionVerification {
	private AgentControlSubmissionVerification() {
	}

	public static int verify() {
		AgentControlScreen.SingleSubmissionGate gate = new AgentControlScreen.SingleSubmissionGate();
		AtomicInteger commands = new AtomicInteger();
		AtomicInteger closes = new AtomicInteger();
		for (int attempt = 1; attempt <= 5; attempt++) {
			AgentControlScreen.dispatchSummonOnce(
					gate,
					() -> {
						commands.incrementAndGet();
						return true;
					},
					closes::incrementAndGet
			);
		}
		assertEquals(1, commands.get(), "five Create invocations dispatch one summon command");
		assertEquals(1, closes.get(), "accepted summon closes the UI exactly once");
		assertTrue(gate.claimed(), "accepted summon keeps duplicate submission fenced");

		AgentControlScreen.SingleSubmissionGate retryGate = new AgentControlScreen.SingleSubmissionGate();
		AtomicInteger retryCommands = new AtomicInteger();
		AtomicInteger retryCloses = new AtomicInteger();
		assertTrue(!AgentControlScreen.dispatchSummonOnce(
				retryGate,
				() -> {
					retryCommands.incrementAndGet();
					return false;
				},
				retryCloses::incrementAndGet
		), "failed transport is not accepted");
		assertTrue(AgentControlScreen.dispatchSummonOnce(
				retryGate,
				() -> {
					retryCommands.incrementAndGet();
					return true;
				},
				retryCloses::incrementAndGet
		), "failed transport releases the gate for one real retry");
		assertEquals(2, retryCommands.get(), "retry dispatches only after the failed transport");
		assertEquals(1, retryCloses.get(), "only an accepted retry closes the UI");
		return 7;
	}

	private static void assertTrue(boolean condition, String label) {
		if (!condition) throw new AssertionError(label);
	}

	private static void assertEquals(int expected, int actual, String label) {
		if (expected != actual) throw new AssertionError(label + ": expected=" + expected + ", actual=" + actual);
	}
}
