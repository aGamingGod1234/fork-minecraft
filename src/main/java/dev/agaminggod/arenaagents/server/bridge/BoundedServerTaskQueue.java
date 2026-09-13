package dev.agaminggod.arenaagents.server.bridge;

import java.util.ArrayDeque;
import java.util.Objects;
import java.util.function.Consumer;

/**
 * Bounded multi-lane handoff from bridge IO threads to the Minecraft server thread.
 * Action commands and cancellations use the same urgent FIFO, so priority never
 * allows a cancellation to overtake the command that established its identity.
 */
final class BoundedServerTaskQueue {
	enum Lane { URGENT, CONTROL, BULK }

	private final int capacity;
	private final int urgentReserve;
	private final int controlReserve;
	private final ArrayDeque<Runnable> urgent = new ArrayDeque<>();
	private final ArrayDeque<Runnable> control = new ArrayDeque<>();
	private final ArrayDeque<Runnable> bulk = new ArrayDeque<>();
	private long offeredUrgent;
	private long offeredControl;
	private long offeredBulk;
	private long rejected;
	private long drained;

	BoundedServerTaskQueue(int capacity) {
		this(capacity, defaultReserve(capacity), defaultReserve(capacity));
	}

	BoundedServerTaskQueue(int capacity, int urgentReserve, int controlReserve) {
		if (capacity <= 0) throw new IllegalArgumentException("capacity must be positive");
		if (urgentReserve < 0 || controlReserve < 0 || urgentReserve + controlReserve >= capacity) {
			throw new IllegalArgumentException("lane reserves must be non-negative and leave bulk capacity");
		}
		this.capacity = capacity;
		this.urgentReserve = urgentReserve;
		this.controlReserve = controlReserve;
	}

	private static int defaultReserve(int capacity) {
		return capacity < 3 ? 0 : Math.max(1, capacity / 8);
	}

	synchronized boolean offer(Runnable task) {
		return offer(Lane.BULK, task);
	}

	synchronized boolean offer(Lane lane, Runnable task) {
		Objects.requireNonNull(lane, "lane must not be null");
		Objects.requireNonNull(task, "task must not be null");
		int pending = pendingCountUnsafe();
		int nonUrgent = control.size() + bulk.size();
		int bulkCapacity = capacity - urgentReserve - controlReserve;
		boolean full = pending >= capacity
				|| (lane == Lane.CONTROL && nonUrgent >= capacity - urgentReserve)
				|| (lane == Lane.BULK && (bulk.size() >= bulkCapacity || nonUrgent >= capacity - urgentReserve));
		if (full) {
			rejected++;
			return false;
		}
		switch (lane) {
			case URGENT -> {
				urgent.addLast(task);
				offeredUrgent++;
			}
			case CONTROL -> {
				control.addLast(task);
				offeredControl++;
			}
			case BULK -> {
				bulk.addLast(task);
				offeredBulk++;
			}
		}
		return true;
	}

	int drain(int maximum, Consumer<Runnable> consumer) {
		if (maximum < 0) throw new IllegalArgumentException("maximum must not be negative");
		Objects.requireNonNull(consumer, "consumer must not be null");
		int count = 0;
		while (count < maximum) {
			Runnable task;
			synchronized (this) {
				task = pollNext();
				if (task == null) break;
				drained++;
			}
			consumer.accept(task);
			count++;
		}
		return count;
	}

	private Runnable pollNext() {
		Runnable task = urgent.pollFirst();
		if (task != null) return task;
		task = control.pollFirst();
		return task != null ? task : bulk.pollFirst();
	}

	synchronized int pendingCount() {
		return pendingCountUnsafe();
	}

	synchronized QueueMetrics metrics() {
		return new QueueMetrics(
			offeredUrgent, offeredControl, offeredBulk, rejected, drained,
			urgent.size(), control.size(), bulk.size()
		);
	}

	private int pendingCountUnsafe() {
		return urgent.size() + control.size() + bulk.size();
	}

	record QueueMetrics(
			long offeredUrgent,
			long offeredControl,
			long offeredBulk,
			long rejected,
			long drained,
			int pendingUrgent,
			int pendingControl,
			int pendingBulk
	) { }
}
