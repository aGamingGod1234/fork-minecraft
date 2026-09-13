package dev.agaminggod.arenaagents.server.bridge;

import java.util.ArrayList;
import java.util.List;

public final class BoundedServerTaskQueueVerification {
	private BoundedServerTaskQueueVerification() {
	}

	public static int verify() {
		BoundedServerTaskQueue queue = new BoundedServerTaskQueue(3, 0, 0);
		assertTrue(queue.offer(() -> { }), "first server task is accepted");
		assertTrue(queue.offer(() -> { }), "second server task is accepted");
		assertTrue(queue.offer(() -> { }), "third server task is accepted");
		assertFalse(queue.offer(() -> { }), "server work fails closed once its capacity is full");
		List<Integer> executed = new ArrayList<>();
		queue = new BoundedServerTaskQueue(3, 0, 0);
		queue.offer(() -> executed.add(1));
		queue.offer(() -> executed.add(2));
		queue.offer(() -> executed.add(3));
		assertEquals(2, queue.drain(2, Runnable::run), "tick drain reports its bounded work count");
		assertEquals(List.of(1, 2), executed, "tick drain retains FIFO order");
		assertEquals(1, queue.pendingCount(), "excess work is deferred to a future tick");
		assertEquals(1, queue.drain(2, Runnable::run), "next tick drains the deferred task");
		assertEquals(List.of(1, 2, 3), executed, "deferred work remains ordered");

		queue = new BoundedServerTaskQueue(8, 2, 2);
		for (int index = 0; index < 4; index++) {
			assertTrue(queue.offer(BoundedServerTaskQueue.Lane.BULK, () -> executed.add(10)),
					"bulk work is accepted inside its unreserved capacity");
		}
		assertFalse(queue.offer(BoundedServerTaskQueue.Lane.BULK, () -> { }),
				"bulk work cannot consume control and urgent reserves");
		assertTrue(queue.offer(BoundedServerTaskQueue.Lane.CONTROL, () -> executed.add(20)),
				"control work uses its reserved capacity");
		assertTrue(queue.offer(BoundedServerTaskQueue.Lane.CONTROL, () -> executed.add(21)),
				"second control task uses its reserved capacity");
		assertFalse(queue.offer(BoundedServerTaskQueue.Lane.CONTROL, () -> { }),
				"control work cannot consume the urgent reserve");
		assertTrue(queue.offer(BoundedServerTaskQueue.Lane.URGENT, () -> executed.add(30)),
				"urgent action work remains admissible under bulk pressure");
		assertTrue(queue.offer(BoundedServerTaskQueue.Lane.URGENT, () -> executed.add(31)),
				"urgent cancellation work remains admissible under bulk pressure");
		assertFalse(queue.offer(BoundedServerTaskQueue.Lane.URGENT, () -> { }),
				"the aggregate queue remains bounded");
		executed.clear();
		assertEquals(8, queue.drain(8, Runnable::run), "all admitted lane work drains");
		assertEquals(List.of(30, 31, 20, 21, 10, 10, 10, 10), executed,
				"urgent FIFO drains before control and bulk without reordering command and cancel");
		BoundedServerTaskQueue.QueueMetrics metrics = queue.metrics();
		assertEquals(2L, metrics.offeredUrgent(), "urgent admissions are counted");
		assertEquals(3L, metrics.rejected(), "lane backpressure rejections are counted");
		assertEquals(8L, metrics.drained(), "drained work is counted");
		assertEquals(BoundedServerTaskQueue.Lane.URGENT, MultiplexedServerBridge.inboundLane("action_command"),
				"action commands use the urgent FIFO");
		assertEquals(BoundedServerTaskQueue.Lane.URGENT, MultiplexedServerBridge.inboundLane("action_cancel"),
				"action cancellations share the command FIFO");
		assertEquals(BoundedServerTaskQueue.Lane.CONTROL, MultiplexedServerBridge.inboundLane("heartbeat"),
				"control traffic is isolated from bulk traffic");
		assertEquals(BoundedServerTaskQueue.Lane.BULK, MultiplexedServerBridge.inboundLane("verbose_event"),
				"verbose telemetry cannot consume reserved action capacity");

		queue = new BoundedServerTaskQueue(8, 2, 2);
		assertTrue(queue.offer(BoundedServerTaskQueue.Lane.URGENT, () -> { }),
				"first early urgent task uses urgent capacity");
		assertTrue(queue.offer(BoundedServerTaskQueue.Lane.URGENT, () -> { }),
				"second early urgent task uses urgent capacity");
		for (int index = 0; index < 4; index++) {
			assertTrue(queue.offer(BoundedServerTaskQueue.Lane.BULK, () -> { }),
					"early urgent work does not consume bulk capacity");
		}
		assertFalse(queue.offer(BoundedServerTaskQueue.Lane.BULK, () -> { }),
				"bulk remains bounded after reverse-order admission");
		assertTrue(queue.offer(BoundedServerTaskQueue.Lane.CONTROL, () -> { }),
				"control reserve remains available after urgent and bulk admission");
		assertTrue(queue.offer(BoundedServerTaskQueue.Lane.CONTROL, () -> { }),
				"second control reserve remains available after urgent and bulk admission");
		assertEquals(8, queue.pendingCount(), "reverse-order admission fills the queue without wasting capacity");
		return 40;
	}

	private static void assertTrue(boolean value, String label) {
		if (!value) throw new AssertionError(label);
	}

	private static void assertFalse(boolean value, String label) {
		if (value) throw new AssertionError(label);
	}

	private static void assertEquals(Object expected, Object actual, String label) {
		if (!expected.equals(actual)) throw new AssertionError(label + ": expected <" + expected + "> but was <" + actual + ">");
	}
}
