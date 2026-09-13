package dev.agaminggod.arenaagents.server.runtime;

public final class ActionProgressTrackerVerification {
	private ActionProgressTrackerVerification() {
	}

	public static int verify() {
		ActionProgressTracker tracker = new ActionProgressTracker(10.0D, 1_000L, 3_000L);
		assertEquals(false, tracker.stalled(9.95D, 3_000L), "minor jitter is not progress");
		assertEquals(false, tracker.stalled(9.0D, 3_500L), "material progress resets the stall clock");
		assertEquals(false, tracker.stalled(8.98D, 6_499L), "movement remains active before the stall deadline");
		assertEquals(true, tracker.stalled(8.98D, 6_500L), "movement fails at the stall deadline");
		assertEquals(0.5D, tracker.progress(5.0D), "distance progress is normalized");

		ActionProgressEmissionPolicy policy = new ActionProgressEmissionPolicy(0.05D, 1_000L);
		assertEquals(true, policy.shouldEmit(0.0D, 1_000L), "first progress emits");
		assertEquals(false, policy.shouldEmit(0.01D, 1_100L), "jitter is suppressed");
		assertEquals(true, policy.shouldEmit(0.06D, 1_200L), "material progress emits");
		assertEquals(true, policy.shouldEmit(0.06D, 2_200L), "heartbeat emits without progress");

		ActionProgressTracker rollbackTracker = new ActionProgressTracker(10.0D, 1_000L, 300L);
		assertEquals(false, rollbackTracker.stalled(10.0D, 1_200L), "stall time accumulates before a clock rollback");
		assertEquals(false, rollbackTracker.stalled(10.0D, 900L), "clock rollback does not manufacture a timeout");
		assertEquals(true, rollbackTracker.stalled(10.0D, 1_000L), "stall time resumes from the corrected wall clock");

		ActionProgressTracker overflowTracker = new ActionProgressTracker(10.0D, Long.MIN_VALUE, 1L);
		assertEquals(true, overflowTracker.stalled(10.0D, Long.MAX_VALUE), "overflowing timestamp distance saturates as elapsed");

		ActionProgressEmissionPolicy rollbackPolicy = new ActionProgressEmissionPolicy(0.05D, 300L);
		assertEquals(true, rollbackPolicy.shouldEmit(0.0D, 1_000L), "rollback policy emits its first sample");
		assertEquals(false, rollbackPolicy.shouldEmit(0.0D, 1_200L), "heartbeat time accumulates before rollback");
		assertEquals(false, rollbackPolicy.shouldEmit(0.0D, 900L), "progress emission tolerates a corrected wall clock");
		assertEquals(true, rollbackPolicy.shouldEmit(0.0D, 1_000L), "heartbeat resumes after rollback");
		return 17;
	}

	private static void assertEquals(Object expected, Object actual, String label) {
		if (!expected.equals(actual)) {
			throw new AssertionError(label + ": expected <" + expected + "> but was <" + actual + ">");
		}
	}
}
