package dev.agaminggod.arenaagents.server;

import dev.agaminggod.arenaagents.agent.AgentEntityLocation;
import dev.agaminggod.arenaagents.agent.AgentId;
import dev.agaminggod.arenaagents.agent.AgentProfile;
import dev.agaminggod.arenaagents.agent.AgentRecord;
import java.util.LinkedHashMap;
import java.util.Optional;

public final class AgentRespawnSpawnPolicyVerification {
	private AgentRespawnSpawnPolicyVerification() {
	}

	public static void main(String[] args) {
		net.minecraft.SharedConstants.tryDetectVersion();
		net.minecraft.server.Bootstrap.bootStrap();
		System.out.println("PASS: " + verify() + " respawn and lifecycle assertions");
	}

	public static int verify() {
		long deadline = 2_000L;
		assertEquals(
				AgentRespawnSpawnPolicy.Decision.WAIT_FOR_REMOVAL,
				AgentRespawnSpawnPolicy.decide(false, true, 1_999L, deadline),
				"old fake player must leave before a replacement is requested"
		);
		assertEquals(
				AgentRespawnSpawnPolicy.Decision.TIMED_OUT,
				AgentRespawnSpawnPolicy.decide(false, true, deadline, deadline),
				"stuck fake-player removal times out"
		);
		assertEquals(
				AgentRespawnSpawnPolicy.Decision.REQUEST_SPAWN,
				AgentRespawnSpawnPolicy.decide(false, false, 1_000L, deadline),
				"replacement is requested only after absence is confirmed"
		);
		assertEquals(
				AgentRespawnSpawnPolicy.Decision.WAIT_FOR_SPAWN,
				AgentRespawnSpawnPolicy.decide(true, false, 1_999L, deadline),
				"accepted Carpet spawn remains pending until the player appears"
		);
		assertEquals(
				AgentRespawnSpawnPolicy.Decision.VERIFY_PLAYER,
				AgentRespawnSpawnPolicy.decide(true, true, 1_500L, deadline),
				"physical player presence advances to verification"
		);
		assertEquals(
				AgentRespawnSpawnPolicy.Decision.TIMED_OUT,
				AgentRespawnSpawnPolicy.decide(true, false, deadline, deadline),
				"accepted spawn without a physical player times out"
		);
		assertEquals(
				AgentRespawnSpawnPolicy.ExistingPlayerAction.RESPAWN_CONNECTED_PLAYER,
				AgentRespawnSpawnPolicy.existingPlayerAction(true, false),
				"a retained dead Carpet player respawns through its existing connection"
		);
		assertEquals(
				AgentRespawnSpawnPolicy.ExistingPlayerAction.RESPAWN_CONNECTED_PLAYER,
				AgentRespawnSpawnPolicy.existingPlayerAction(true, true),
				"Carpet's temporary post-death health reset cannot turn a retained death into a second disconnect"
		);
		assertEquals(
				AgentRespawnSpawnPolicy.ExistingPlayerAction.WAIT_FOR_NATURAL_REMOVAL,
				AgentRespawnSpawnPolicy.existingPlayerAction(false, false),
				"an unretained dead Carpet player may finish its stock disconnect"
		);
		assertEquals(
				AgentRespawnSpawnPolicy.ExistingPlayerAction.REMOVE_STALE_PLAYER,
				AgentRespawnSpawnPolicy.existingPlayerAction(false, true),
				"an unrelated stale live player must be removed before replacement"
		);
		CodexAgentManager.RespawnRemovalDecision beforeGrace = CodexAgentManager.respawnRemovalDecision(
				false, false, true, 999L, 1_000L, deadline);
		assertFalse(
				beforeGrace.requestRemoval(),
				"respawn waits for the fixed removal grace deadline"
		);
		CodexAgentManager.RespawnRemovalDecision firstRemoval = CodexAgentManager.respawnRemovalDecision(
				false, false, true, 1_000L, 1_000L, deadline);
		assertTrue(
				firstRemoval.requestRemoval(),
				"respawn requests stale-player removal once after grace"
		);
		assertEquals(deadline, firstRemoval.deadlineEpochMs(), "stale-player removal retains the original deadline");
		CodexAgentManager.RespawnRemovalDecision repeatedRemoval = CodexAgentManager.respawnRemovalDecision(
				false, true, true, 3_000L, 1_000L, firstRemoval.deadlineEpochMs());
		assertFalse(
				repeatedRemoval.requestRemoval(),
				"respawn does not request removal again while waiting for the fixed deadline"
		);
		assertEquals(deadline, repeatedRemoval.deadlineEpochMs(), "waiting does not extend the removal deadline");
		assertEquals(
				AgentRespawnSpawnPolicy.Decision.TIMED_OUT,
				AgentRespawnSpawnPolicy.decide(false, true, 2_000L, deadline),
				"one removal request cannot extend the original removal deadline"
		);

		java.util.concurrent.atomic.AtomicBoolean released = new java.util.concurrent.atomic.AtomicBoolean();
		java.util.concurrent.atomic.AtomicInteger cleanupCalls = new java.util.concurrent.atomic.AtomicInteger();
		IllegalStateException primary = new IllegalStateException("runtime hook failed");
		IllegalArgumentException secondary = new IllegalArgumentException("ticket cleanup failed");
		RuntimeException observed = expectRuntimeFailure(() -> CodexAgentManager.releaseOnce(
				released,
				() -> {
					cleanupCalls.incrementAndGet();
					throw primary;
				},
				cleanupCalls::incrementAndGet,
				() -> {
					cleanupCalls.incrementAndGet();
					throw secondary;
				}
		));
		assertSame(primary, observed, "shutdown reports the first cleanup failure");
		assertEquals(3, cleanupCalls.get(), "shutdown attempts every owned cleanup after a failure");
		assertEquals(1, observed.getSuppressed().length, "shutdown retains later cleanup failures");
		assertSame(secondary, observed.getSuppressed()[0], "shutdown suppresses the later failure on the primary");
		CodexAgentManager.releaseOnce(released, cleanupCalls::incrementAndGet);
		assertEquals(3, cleanupCalls.get(), "repeated shutdown is idempotent after a failed first release");

		java.util.concurrent.atomic.AtomicInteger physicalCleanupCalls = new java.util.concurrent.atomic.AtomicInteger();
		java.util.concurrent.atomic.AtomicInteger durableDeleteCalls = new java.util.concurrent.atomic.AtomicInteger();
		IllegalStateException cleanupFailure = new IllegalStateException("player removal failed");
		assertSame(cleanupFailure, expectRuntimeFailure(() -> CodexAgentManager.deleteAfterRequiredCleanup(
				() -> {
					physicalCleanupCalls.incrementAndGet();
					throw cleanupFailure;
				},
				() -> {
					durableDeleteCalls.incrementAndGet();
					return "removed";
				}
		)), "failed physical cleanup is reported");
		assertEquals(0, durableDeleteCalls.get(), "failed player cleanup leaves the registry record retryable");
		assertEquals("removed", CodexAgentManager.deleteAfterRequiredCleanup(
				physicalCleanupCalls::incrementAndGet,
				() -> {
					durableDeleteCalls.incrementAndGet();
					return "removed";
				}
		), "successful retry reaches durable deletion");
		assertEquals(2, physicalCleanupCalls.get(), "retry performs physical cleanup again");
		assertEquals(1, durableDeleteCalls.get(), "durable deletion happens once after cleanup succeeds");

		java.util.Set<String> currentNames = java.util.Set.of("c00_11111111", "k20_22222222");
		assertEquals(
				java.util.List.of("c01_DEADBEEF", "legacy_33333333"),
				CodexAgentManager.staleHiddenTeamMembers(
						java.util.List.of("c00_11111111", "c01_DEADBEEF", "k20_22222222", "legacy_33333333"),
						currentNames
				),
				"startup pruning selects only hidden-team identities absent from the current registry"
		);
		assertEquals(java.util.List.of(), CodexAgentManager.staleHiddenTeamMembers(currentNames, currentNames),
				"repeated hidden-team cleanup is idempotent");
		assertEquals(java.util.List.of("c00_11111111"), CodexAgentManager.staleHiddenTeamMembers(
				java.util.List.of("c00_11111111"), java.util.Set.of()),
				"removing the final registry record marks its hidden-team membership stale");
		assertTrue(CodexAgentManager.shouldPersistEntityLocation(Long.MIN_VALUE, 10_000L, false),
				"the first exact location is persisted immediately");
		assertFalse(CodexAgentManager.shouldPersistEntityLocation(10_000L, 10_999L, false),
				"the hot tick loop does not dirty saved data before the one-second bound");
		assertTrue(CodexAgentManager.shouldPersistEntityLocation(10_000L, 11_000L, false),
				"exact location persistence runs at the one-second bound");
		assertTrue(CodexAgentManager.shouldPersistEntityLocation(10_999L, 10_999L, true),
				"shutdown forces the final exact position and view write");

		AgentEntityLocation exactLocation = AgentEntityLocation.exact(
				"minecraft:overworld", -2, 2, -16.25D, 70.75D, 32.5D, 120.0F, -15.0F
		);
		java.util.concurrent.atomic.AtomicInteger safetyChecks = new java.util.concurrent.atomic.AtomicInteger();
		CodexAgentManager.ExactRecoveryCoordinates exact = CodexAgentManager.exactRecoveryCoordinates(
				exactLocation,
				(x, y, z) -> {
					safetyChecks.incrementAndGet();
					assertEquals(-17, x, "exact recovery checks the saved feet X block");
					assertEquals(70, y, "exact recovery checks the saved feet Y block");
					assertEquals(32, z, "exact recovery checks the saved feet Z block");
					return true;
				}
		).orElseThrow();
		assertEquals(1, safetyChecks.get(), "exact recovery checks its feet, head, and floor column once");
		assertEquals(-16.25D, exact.x(), "safe recovery preserves exact X");
		assertEquals(70.75D, exact.y(), "safe recovery preserves exact Y");
		assertEquals(32.5D, exact.z(), "safe recovery preserves exact Z");
		assertEquals(120.0F, exact.yaw(), "safe recovery preserves yaw");
		assertEquals(-15.0F, exact.pitch(), "safe recovery preserves pitch");
		assertTrue(CodexAgentManager.exactRecoveryCoordinates(exactLocation, (x, y, z) -> false).isEmpty(),
				"an unsafe exact column yields to the bounded nearby fallback");
		assertTrue(CodexAgentManager.exactRecoveryCoordinates(
				new AgentEntityLocation("minecraft:overworld", 0, 0),
				(x, y, z) -> {
					throw new AssertionError("coarse snapshots must skip exact recovery");
				}
		).isEmpty(), "coarse snapshots continue directly to bounded recovery");

		var inFlight = new java.util.LinkedHashMap<dev.agaminggod.arenaagents.agent.AgentId, Object>();
		var agentId = dev.agaminggod.arenaagents.agent.AgentId.random();
		var starts = new java.util.concurrent.atomic.AtomicInteger();
		Object first = CodexAgentManager.singleFlight(inFlight, agentId, () -> {
			starts.incrementAndGet();
			return new Object();
		});
		Object second = CodexAgentManager.singleFlight(inFlight, agentId, () -> {
			starts.incrementAndGet();
			return new Object();
		});
		assertSame(first, second, "automatic and coordinator respawn requests share one attempt");
		assertEquals(1, starts.get(), "only one physical respawn attempt can start per agent");
		assertEquals(1, inFlight.size(), "single-flight ownership keeps one pending respawn entry");

		AgentId abortId = AgentId.random();
		AgentRecord deadRecord = AgentRecord.create(
				abortId, new AgentProfile("codex", "gpt-5.6-sol", "high", Optional.empty(), 0), 1_000L);
		CodexAgentManager.VanillaRespawnAttempt attempt = vanillaRespawnAttempt(deadRecord);
		var pending = new LinkedHashMap<AgentId, CodexAgentManager.VanillaRespawnAttempt>();
		pending.put(abortId, attempt);
		assertTrue(CodexAgentManager.abortPendingVerifiedRespawn(pending, attempt),
				"cancellation removes the manager-owned pending respawn");
		assertTrue(pending.isEmpty(), "a cancelled respawn cannot remain eligible to commit");
		assertFalse(CodexAgentManager.abortPendingVerifiedRespawn(pending, attempt),
				"a second cancel cannot abort a respawn that already left the map");
		CodexAgentManager.VanillaRespawnAttempt replacement = vanillaRespawnAttempt(deadRecord);
		pending.put(abortId, replacement);
		assertFalse(CodexAgentManager.abortPendingVerifiedRespawn(pending, attempt),
				"cancellation cannot abort a replacement attempt");
		assertSame(replacement, pending.get(abortId), "a newer pending respawn stays in flight");

		assertTrue(CodexAgentManager.shouldRetryConnectedRespawn(true, true, true),
				"publication failure keeps a healthy connected replacement for retry");
		assertFalse(CodexAgentManager.shouldRetryConnectedRespawn(true, false, true),
				"a missing connected replacement falls back to recovery");
		assertFalse(CodexAgentManager.shouldRetryConnectedRespawn(false, true, true),
				"legacy createFake failures retain their existing rollback semantics");
		assertFalse(CodexAgentManager.shouldRetryConnectedRespawn(true, true, false),
				"a superseded lifecycle cannot replay an old connected respawn commit");
		return 56 + AgentSummonNameVerification.verify();
	}

	private static CodexAgentManager.VanillaRespawnAttempt vanillaRespawnAttempt(AgentRecord deadRecord) {
		try {
			var constructor = CodexAgentManager.VanillaRespawnAttempt.class.getDeclaredConstructor(
					AgentRecord.class, OfflineAgentPlayers.VanillaRespawnTarget.class, long.class, long.class);
			constructor.setAccessible(true);
			return constructor.newInstance(deadRecord, null, 0L, 0L);
		} catch (ReflectiveOperationException exception) {
			throw new AssertionError("missing vanilla respawn attempt constructor", exception);
		}
	}

	private static RuntimeException expectRuntimeFailure(Runnable operation) {
		try {
			operation.run();
		} catch (RuntimeException failure) {
			return failure;
		}
		throw new AssertionError("expected cleanup failure");
	}

	private static void assertEquals(Object expected, Object actual, String label) {
		if (!expected.equals(actual)) {
			throw new AssertionError(label + ": expected <" + expected + "> but was <" + actual + ">");
		}
	}

	private static void assertTrue(boolean condition, String label) {
		if (!condition) throw new AssertionError(label);
	}

	private static void assertFalse(boolean condition, String label) {
		assertTrue(!condition, label);
	}

	private static void assertSame(Object expected, Object actual, String label) {
		if (expected != actual) throw new AssertionError(label + ": expected same instance");
	}
}
