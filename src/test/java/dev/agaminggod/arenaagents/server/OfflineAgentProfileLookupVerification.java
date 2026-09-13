package dev.agaminggod.arenaagents.server;

import java.util.UUID;

public final class OfflineAgentProfileLookupVerification {
	private OfflineAgentProfileLookupVerification() {
	}

	public static int verify() {
		UUID agent = UUID.fromString("193a9add-1234-5678-9abc-123456789abc");
		UUID ordinary = UUID.fromString("293a9add-1234-5678-9abc-123456789abc");
		OfflineAgentProfileLookup.begin("DeathSmoke", agent);
		assertTrue(OfflineAgentProfileLookup.requestedUuid("DeathSmoke").orElseThrow().equals(agent),
				"exact pending name keeps the Arena offline UUID before Carpet resolves a real account");
		assertTrue(OfflineAgentProfileLookup.requestedUuid("Deathsmoke").isEmpty(),
				"profile interception never guesses across different requested name casing");
		assertTrue(OfflineAgentProfileLookup.shouldBypassRemoteLookup(agent),
				"pending offline agent bypasses Mojang profile lookup");
		assertTrue(!OfflineAgentProfileLookup.shouldBypassRemoteLookup(ordinary),
				"ordinary profiles still use the vanilla resolver");
		OfflineAgentProfileLookup.end("DeathSmoke", ordinary);
		assertTrue(OfflineAgentProfileLookup.shouldBypassRemoteLookup(agent),
				"a mismatched cleanup cannot release another spawn's profile scope");
		OfflineAgentProfileLookup.end("DeathSmoke", agent);
		assertTrue(OfflineAgentProfileLookup.requestedUuid("DeathSmoke").isEmpty(),
				"requested-name interception ends immediately after Carpet schedules the fake player");
		assertTrue(!OfflineAgentProfileLookup.shouldBypassRemoteLookup(agent),
				"bypass scope ends immediately after Carpet schedules the fake player");
		return 7;
	}

	private static void assertTrue(boolean value, String label) {
		if (!value) throw new AssertionError(label);
	}
}
