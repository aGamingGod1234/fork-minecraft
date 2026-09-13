package dev.agaminggod.arenaagents.server.runtime;

import dev.agaminggod.arenaagents.agent.AgentId;
import dev.agaminggod.arenaagents.protocol.ActionType;

public final class ActionSuccessLedgerVerification {
	private ActionSuccessLedgerVerification() { }

	public static void main(String[] arguments) {
		System.out.println("PASS: " + verify() + " action success ledger assertions");
	}

	public static int verify() {
		AgentId agent = AgentId.parse("00000000-0000-0000-0000-000000000001");
		ActionSuccessLedger ledger = new ActionSuccessLedger();
		ledger.record(result(agent, 4L, ActionType.CRAFT_INVENTORY, ServerActionState.SUCCEEDED));
		ledger.record(result(agent, 4L, ActionType.CRAFT_INVENTORY, ServerActionState.FAILED));
		ledger.record(result(agent, 4L, ActionType.COMPLETE_GOAL, ServerActionState.SUCCEEDED));
		assertEquals(1, ledger.count(agent, 4L, "craft_inventory"), "only successful physical actions count");
		assertEquals(0, ledger.count(agent, 4L, "complete_goal"), "completion cannot count itself");
		assertEquals(0, ledger.count(agent, 3L, "craft_inventory"), "counts are revision scoped");
		ledger.record(result(agent, 5L, ActionType.MOVE_TO, ServerActionState.SUCCEEDED));
		ledger.retainRevision(agent, 5L);
		assertEquals(0, ledger.count(agent, 4L, "craft_inventory"), "goal transition evicts historical revisions");
		assertEquals(1, ledger.count(agent, 5L, "move_to"), "goal transition retains the active revision");
		ledger.clear(agent, 4L);
		assertEquals(0, ledger.count(agent, 4L, "craft_inventory"), "clearing a revision is idempotent");
		ledger.clear(agent);
		assertEquals(0, ledger.count(agent, 5L, "move_to"), "agent removal evicts every retained revision");
		return 7;
	}

	private static ServerActionResult result(AgentId agent, long revision, ActionType type, ServerActionState state) {
		return new ServerActionResult(agent, revision, "action-" + type.wireName(), type, "trace-ledger", state, state == ServerActionState.SUCCEEDED ? "DONE" : "FAILED", "test", 1L, 1L);
	}

	private static void assertEquals(Object expected, Object actual, String label) {
		if (!expected.equals(actual)) throw new AssertionError(label + ": expected <" + expected + "> but was <" + actual + ">");
	}
}
