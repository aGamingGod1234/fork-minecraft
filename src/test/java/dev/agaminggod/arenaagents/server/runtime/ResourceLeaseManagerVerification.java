package dev.agaminggod.arenaagents.server.runtime;

import dev.agaminggod.arenaagents.agent.AgentId;

public final class ResourceLeaseManagerVerification {
	private ResourceLeaseManagerVerification() {
	}

	public static void main(String[] args) {
		System.out.println("PASS: " + verify() + " resource lease assertions");
	}

	public static int verify() {
		ResourceLeaseManager leases = new ResourceLeaseManager();
		AgentId first = AgentId.random();
		AgentId second = AgentId.random();

		assertTrue(leases.acquire("block:minecraft:overworld:1", first, 1_000L, 5_000L),
				"first owner acquires a block target");
		assertFalse(leases.acquire("block:minecraft:overworld:1", second, 1_001L, 5_000L),
				"second owner cannot share the same block target");
		assertTrue(leases.acquire("block:minecraft:overworld:2", second, 1_001L, 5_000L),
				"different block targets remain independent");
		leases.release("block:minecraft:overworld:1", first);
		assertTrue(leases.acquire("block:minecraft:overworld:1", second, 1_002L, 5_000L),
				"released targets are immediately reusable");
		assertTrue(leases.acquire("block:minecraft:the_nether:1", first, 1_002L, 5_000L),
				"the same coordinate in another dimension is independent");
		assertTrue(leases.acquire("block:minecraft:overworld:1", first, 6_002L, 5_000L),
				"expired targets are reusable by another owner");

		ResourceLeaseManager rollbackLeases = new ResourceLeaseManager();
		assertTrue(rollbackLeases.acquire("resource", first, 1_000L, 300L), "rollback lease is acquired");
		assertTrue(rollbackLeases.isHeldBy("resource", first, 1_200L), "lease time accumulates before rollback");
		assertTrue(rollbackLeases.isHeldBy("resource", first, 900L), "clock rollback does not expire a lease early");
		assertFalse(rollbackLeases.isHeldBy("resource", first, 1_000L), "lease expires after accumulated active time");

		ResourceLeaseManager overflowLeases = new ResourceLeaseManager();
		assertTrue(overflowLeases.acquire("overflow", first, Long.MIN_VALUE, 1L), "overflow lease is acquired");
		assertFalse(overflowLeases.isHeldBy("overflow", first, Long.MAX_VALUE), "overflowing timestamp distance expires safely");
		return 12;
	}

	private static void assertTrue(boolean condition, String label) {
		if (!condition) throw new AssertionError(label);
	}

	private static void assertFalse(boolean condition, String label) {
		if (condition) throw new AssertionError(label);
	}
}
