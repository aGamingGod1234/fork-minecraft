package dev.agaminggod.arenaagents.server;

import dev.agaminggod.arenaagents.agent.AgentId;
import dev.agaminggod.arenaagents.agent.AgentGameMode;
import dev.agaminggod.arenaagents.agent.AgentProfile;
import java.util.List;
import java.util.Optional;

public final class PendingSpawnCancellationLedgerVerification {
	private PendingSpawnCancellationLedgerVerification() {
	}

	public static void main(String[] args) {
		System.out.println("PASS: " + verify() + " pending spawn cancellation assertions");
	}

	public static int verify() {
		AgentId first = AgentId.random();
		AgentId second = AgentId.random();
		AgentProfile firstProfile = new AgentProfile(
				"codex", "gpt-5.6-luna", "high", "priority", Optional.of("First"), 0, AgentGameMode.SURVIVAL);
		AgentProfile secondProfile = new AgentProfile(
				"codex", "gpt-5.6-luna", "high", "priority", Optional.of("Second"), 1, AgentGameMode.SURVIVAL);
		PendingSpawnCancellationLedger ledger = new PendingSpawnCancellationLedger(30_000L);

		ledger.record(first, firstProfile, 1_000L);
		assertEquals(
				List.of(new PendingSpawnCancellationLedger.Cancellation(first, firstProfile)),
				ledger.active(30_999L),
				"cancellation retains the removed profile needed to find a delayed player spawn"
		);
		assertEquals(List.of(), ledger.active(31_000L), "cancellation expires at its deadline");

		ledger.record(first, firstProfile, 40_000L);
		ledger.record(second, secondProfile, 40_001L);
		ledger.record(first, firstProfile, 50_000L);
		assertEquals(
				List.of(
						new PendingSpawnCancellationLedger.Cancellation(first, firstProfile),
						new PendingSpawnCancellationLedger.Cancellation(second, secondProfile)
				),
				ledger.active(60_000L),
				"re-recording extends without duplicating an agent"
		);
		assertEquals(
				List.of(new PendingSpawnCancellationLedger.Cancellation(first, firstProfile)),
				ledger.active(70_001L),
				"independent expirations are pruned deterministically"
		);
		return 4;
	}

	private static void assertEquals(Object expected, Object actual, String label) {
		if (!expected.equals(actual)) {
			throw new AssertionError(label + ": expected <" + expected + "> but was <" + actual + ">");
		}
	}
}
