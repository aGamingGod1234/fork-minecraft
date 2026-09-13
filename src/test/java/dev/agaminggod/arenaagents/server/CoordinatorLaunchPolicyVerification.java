package dev.agaminggod.arenaagents.server;

public final class CoordinatorLaunchPolicyVerification {
	private CoordinatorLaunchPolicyVerification() {
	}

	public static int verify() {
		long createdAt = 10_000L;
		assertTrue(CoordinatorLaunchPolicy.shouldStart(false, false, createdAt, createdAt), "owned coordinator starts without an artificial delay");
		assertFalse(CoordinatorLaunchPolicy.shouldStart(true, false, createdAt, createdAt + 5_000L), "authenticated coordinator is reused");
		assertFalse(CoordinatorLaunchPolicy.shouldStart(false, true, createdAt, createdAt + 5_000L), "live owned coordinator is retained");
		assertTrue(CoordinatorLaunchPolicy.shouldStart(false, false, createdAt, createdAt + CoordinatorLaunchPolicy.STARTUP_GRACE_MS), "missing coordinator starts immediately");
		CoordinatorLaunchPolicy.RestartBudget budget = new CoordinatorLaunchPolicy.RestartBudget();
		long[] expectedDelays = {1_000L, 2_000L, 5_000L, 15_000L, 30_000L, 30_000L, 30_000L, 30_000L};
		for (int crash = 0; crash < expectedDelays.length; crash++) {
			assertTrue(budget.recordUnexpectedExit(), "coordinator crash " + (crash + 1) + " remains restartable");
			assertEquals(expectedDelays[crash], budget.nextDelayMs(),
					"coordinator crash " + (crash + 1) + " uses the capped retry delay");
		}
		budget.resetAfterStability();
		assertTrue(budget.recordUnexpectedExit(), "authenticated stability resets crash-loop history");
		assertEquals(1_000L, budget.nextDelayMs(), "stable reset uses the first delay");
		return 24;
	}

	private static void assertEquals(long expected, long actual, String label) {
		if (expected != actual) throw new AssertionError(label + ": expected=" + expected + ", actual=" + actual);
	}

	private static void assertTrue(boolean condition, String label) {
		if (!condition) throw new AssertionError(label);
	}

	private static void assertFalse(boolean condition, String label) {
		assertTrue(!condition, label);
	}
}
