package dev.agaminggod.arenaagents.control;

import java.util.List;

public final class AgentControlSelectionStateVerification {
	private static final String LUNA_ID = "12345678-1234-5678-9abc-123456789abc";
	private static final String SOL_ID = "87654321-4321-8765-cba9-987654321abc";

	private AgentControlSelectionStateVerification() {
	}

	public static int verify() {
		AgentControlAgent luna = agent(LUNA_ID, "Luna", "IDLE");
		AgentControlAgent sol = agent(SOL_ID, "Sol", "STARTING");
		AgentControlSelectionState selection = new AgentControlSelectionState();

		selection.reconcile(List.of(luna, sol));
		assertEquals(LUNA_ID, selection.selectedAgentId(), "initial roster selects the first agent");
		selection.select(SOL_ID, List.of(luna, sol));

		AgentControlAgent refreshedSol = agent(SOL_ID, "Sol", "ACTING");
		AgentControlAgent disconnectedLuna = agent(LUNA_ID, "Luna", "DISCONNECTED");
		selection.reconcile(List.of(disconnectedLuna, refreshedSol));
		assertEquals(SOL_ID, selection.selectedAgentId(),
				"snapshot refresh preserves the selected stable agent ID across state changes");

		selection.reconcile(List.of(disconnectedLuna));
		assertEquals(LUNA_ID, selection.selectedAgentId(),
				"removed selected agent falls back to the first remaining roster entry");
		return 3;
	}

	public static void main(String[] args) {
		int assertions = verify();
		System.out.println("Agent control selection state verification passed: " + assertions + " assertions");
	}

	private static AgentControlAgent agent(String id, String displayName, String state) {
		return new AgentControlAgent(
				id,
				id.substring(0, 8),
				displayName,
				"codex",
				displayName.equals("Sol") ? "gpt-5.6-sol" : "gpt-5.6-luna",
				"high",
				displayName + "_" + id.substring(0, 8).toUpperCase(java.util.Locale.ROOT),
				0,
				state,
				"",
				0,
				"",
				"",
				true,
				true
		);
	}

	private static void assertEquals(Object expected, Object actual, String label) {
		if (!java.util.Objects.equals(expected, actual)) {
			throw new AssertionError(label + ": expected=" + expected + ", actual=" + actual);
		}
	}
}
