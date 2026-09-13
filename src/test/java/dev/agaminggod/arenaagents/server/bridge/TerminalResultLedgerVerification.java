package dev.agaminggod.arenaagents.server.bridge;

import dev.agaminggod.arenaagents.agent.AgentId;
import dev.agaminggod.arenaagents.protocol.ActionType;
import dev.agaminggod.arenaagents.server.runtime.ServerActionResult;
import dev.agaminggod.arenaagents.server.runtime.ServerActionState;

import java.util.List;
import java.util.UUID;

/** Deterministic checks for terminal result fencing, coalescing, replay, and acknowledgement. */
public final class TerminalResultLedgerVerification {
	private TerminalResultLedgerVerification() { }

	public static int verify() {
		TerminalResultLedger ledger = new TerminalResultLedger();
		AgentId agent = AgentId.random();
		UUID logicalGoalId = UUID.fromString("00000000-0000-0000-0000-000000000007");
		UUID replacementGoalId = UUID.fromString("00000000-0000-0000-0000-000000000008");
		ServerActionResult result = result(agent, 7L, "action-1");
		Object firstSession = new Object();
		Object replacementSession = new Object();

		ledger.beginGoal(agent, 7L, logicalGoalId);
		assertTrue(ledger.retain(result), "current terminal result is retained");
		assertEquals(List.of(result), ledger.pending(), "retained result is visible for replay");
		assertTrue(ledger.claim(result, firstSession), "first session claims the result once");
		assertFalse(ledger.claim(result, firstSession), "same session cannot enqueue a duplicate result");
		ledger.sessionClosed(firstSession);
		ledger.beginGoal(agent, 8L, logicalGoalId);
		assertEquals(List.of(result), ledger.pending(), "disconnect revision preserves the same logical goal result");
		assertTrue(ledger.claim(result, replacementSession), "reconnect can claim a result fenced by the old session");
		assertFalse(ledger.claim(result, replacementSession), "replacement handshake replays the result only once");
		assertFalse(ledger.acknowledge(agent, 7L, "unknown"), "unknown acknowledgement does not remove retained work");
		assertTrue(ledger.acknowledge(agent, 7L, "action-1"), "matching acknowledgement removes retained work");
		assertTrue(ledger.acknowledge(agent, 7L, "action-1"), "duplicate acknowledgement is idempotently recognized");

		ServerActionResult stale = result(agent, 7L, "old-action");
		ledger.beginGoal(agent, 8L, logicalGoalId);
		assertTrue(ledger.retain(stale), "a late terminal result remains replayable after the live revision advances");
		assertEquals(1, ledger.pendingCount(), "late terminal work remains globally retained");
		ledger.retain(result(agent, 8L, "action-2"));
		ledger.beginGoal(agent, 9L, replacementGoalId);
		assertEquals(2, ledger.pendingCount(), "new logical goal preserves unacknowledged terminal results");
		ledger.retain(result(agent, 9L, "action-3"));
		ledger.beginGoal(agent, 10L, null);
		assertEquals(3, ledger.pendingCount(), "a no-goal transition preserves unacknowledged terminal work");

		ledger.beginGoal(agent, 11L, replacementGoalId);
		AgentId otherAgent = AgentId.random();
		ledger.beginGoal(otherAgent, 11L, replacementGoalId);
		for (int index = 0; index <= 4_096; index++) {
			AgentId target = index % 2 == 0 ? agent : otherAgent;
			assertTrue(ledger.retain(result(target, 11L, "bounded-" + index)),
					"global pending FIFO accepts bounded terminal result " + index);
		}
		assertEquals(4_096, ledger.pendingCount(), "terminal payload retention uses one global 4,096-result cap");
		ServerActionResult evicted = result(agent, 11L, "bounded-0");
		ledger.beginGoal(agent, 12L, UUID.fromString("00000000-0000-0000-0000-000000000009"));
		assertTrue(ledger.retain(evicted), "an unacknowledged retired identity can restore its matching payload");
		assertEquals(4_096, ledger.pendingCount(), "restoring a retired payload preserves the global cap");
		assertTrue(ledger.acknowledge(agent, 11L, "bounded-0"), "restored payload can be acknowledged");
		assertFalse(ledger.retain(evicted), "an acknowledged retired result is not redelivered");
		expectFailure(() -> ledger.retain(new ServerActionResult(
				agent, 11L, "bounded-0", ActionType.WAIT, "trace-bounded-0",
				ServerActionState.FAILED, "CHANGED", "changed", 1L, 1_750_000_000_001L, true, true
		)), "changed payload cannot reuse a retired terminal identity");
		ledger.remove(agent);
		ledger.remove(otherAgent);
		assertEquals(0, ledger.pendingCount(), "agent removal clears retained terminal results");
		return 4_121;
	}

	private static ServerActionResult result(AgentId agent, long goalRevision, String actionId) {
		return new ServerActionResult(
				agent, goalRevision, actionId, ActionType.WAIT, "trace-" + actionId,
				ServerActionState.SUCCEEDED, "DONE", "done", 1L, 1_750_000_000_001L, true, true
		);
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

	private static void expectFailure(Runnable operation, String label) {
		try {
			operation.run();
			throw new AssertionError(label);
		} catch (IllegalStateException expected) {
			// Expected identity mismatch.
		}
	}
}
