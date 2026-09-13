package dev.agaminggod.arenaagents.server;

import dev.agaminggod.arenaagents.agent.AgentId;
import dev.agaminggod.arenaagents.protocol.ActionType;
import dev.agaminggod.arenaagents.server.runtime.ServerActionResult;
import dev.agaminggod.arenaagents.server.runtime.ServerActionState;

public final class AgentActivityPresentationVerification {
	private AgentActivityPresentationVerification() {
	}

	public static int verify() {
		assertEquals("Mining a block", AgentActivityPresentation.action(ActionType.BREAK_BLOCK),
				"actions use plain-language activity copy");
		assertTrue(AgentActivityPresentation.result(result(ServerActionState.SUCCEEDED, "DONE", "Block broken")).isEmpty(),
				"routine successes do not flood chat");
		String failure = AgentActivityPresentation.result(result(
				ServerActionState.FAILED, "CRAFT_REMAINDER_UNSAFE", "Recipe layout was incompatible")).orElseThrow();
		assertTrue(failure.startsWith("Crafting an item needs attention"),
				"failures identify the real activity without lifecycle jargon");
		assertTrue(!failure.contains("Failed") && !failure.contains("Succeeded") && !failure.contains("Planning"),
				"player activity copy omits internal lifecycle terms");
		assertTrue(!AgentActivityPresentation.shouldAnnounceAction(ActionType.NAVIGATE_TO),
				"routine navigation is visible in-world instead of repeated in chat");
		assertTrue(!AgentActivityPresentation.shouldAnnounceFailure("PATH_BLOCKED"),
				"retryable path failures remain in the console until the task actually stops");
		assertTrue(!AgentActivityPresentation.shouldAnnounceFailure("TARGET_NOT_FOUND"),
				"stale target selectors are routine replanning evidence rather than red errors");
		assertTrue(!AgentActivityPresentation.shouldAnnounceFailure("TARGET_TOO_FAR"),
				"out-of-range targets are routine pursuit evidence rather than red errors");
		assertTrue(!AgentActivityPresentation.shouldShowInChat("ACTION_CANCELLED", true),
				"routine cancellation stays in structured diagnostics rather than red chat");
		assertTrue(!AgentActivityPresentation.shouldShowInChat("PLACEMENT_NOT_CONFIRMED", true),
				"recoverable placement failures stay in structured diagnostics rather than red chat");
		assertTrue(AgentActivityPresentation.shouldShowInChat("COORDINATOR_UNAVAILABLE", false),
				"system failures remain visible in chat");
		assertTrue(AgentActivityPresentation.shouldAnnounceAction(ActionType.BREAK_BLOCK),
				"concrete world-changing actions remain visible in chat");
		assertTrue(AgentActivityPresentation.result(result(
				ServerActionState.CANCELLED, "ACTION_CANCELLED", "Cancelled by explicit model decision")).isEmpty(),
				"intentional model cancellation is not rendered as a red chat error");
		for (String reason : new String[] {
				"ITEM_PICKUP_TIMED_OUT", "RANGED_USE_TIMED_OUT", "RANGED_USE_NOT_STARTED",
				"RANGED_RELEASE_NOT_OBSERVED", "BUILD_SEQUENCE_TIMEOUT", "TARGET_OCCUPIED" }) {
			assertTrue(!AgentActivityPresentation.shouldShowInChat(reason, true),
					reason + " remains structured recoverable evidence rather than red chat");
		}
		assertTrue(AgentActivityPresentation.shouldShowInChat("PLACEMENT_CONTRACT_BROKEN", false),
				"contract failures remain visible");
		return 20;
	}

	private static ServerActionResult result(ServerActionState state, String reason, String message) {
		return new ServerActionResult(
				AgentId.parse("01234567-89ab-cdef-0123-456789abcdef"), 1L, "action-1",
				ActionType.CRAFT_INVENTORY, state, reason, message, 1L, 1L);
	}

	private static void assertEquals(Object expected, Object actual, String label) {
		if (!expected.equals(actual)) throw new AssertionError(label + ": expected=" + expected + ", actual=" + actual);
	}

	private static void assertTrue(boolean value, String label) {
		if (!value) throw new AssertionError(label);
	}
}
