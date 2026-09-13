package dev.agaminggod.arenaagents.server.perception;

import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.Objects;
import java.util.function.Consumer;

public final class ObservationDispatchQueue<T> {
	private final int capacity;
	private final int maximumPerDrain;
	private final LinkedHashSet<T> pending = new LinkedHashSet<>();

	public ObservationDispatchQueue(int capacity, int maximumPerDrain) {
		if (capacity <= 0 || maximumPerDrain <= 0 || maximumPerDrain > capacity) {
			throw new IllegalArgumentException("invalid observation dispatch bounds");
		}
		this.capacity = capacity;
		this.maximumPerDrain = maximumPerDrain;
	}

	public synchronized boolean offer(T identity) {
		Objects.requireNonNull(identity, "identity must not be null");
		if (pending.contains(identity)) return false;
		if (pending.size() >= capacity) throw new IllegalStateException("observation dispatch capacity is full");
		pending.add(identity);
		return true;
	}

	/** Promotes urgent work to the front while retaining one pending entry per identity. */
	public synchronized boolean offerFirst(T identity) {
		Objects.requireNonNull(identity, "identity must not be null");
		boolean present = pending.remove(identity);
		if (!present && pending.size() >= capacity) {
			throw new IllegalStateException("observation dispatch capacity is full");
		}
		pending.addFirst(identity);
		return true;
	}

	public synchronized boolean remove(T identity) {
		return pending.remove(Objects.requireNonNull(identity, "identity must not be null"));
	}

	public synchronized boolean contains(T identity) {
		return pending.contains(Objects.requireNonNull(identity, "identity must not be null"));
	}

	public synchronized void clear() {
		pending.clear();
	}

	/** Removes and returns the oldest pending identity, or {@code null} when empty. */
	public synchronized T poll() {
		Iterator<T> iterator = pending.iterator();
		if (!iterator.hasNext()) return null;
		T identity = iterator.next();
		iterator.remove();
		return identity;
	}

	public void drain(Consumer<T> consumer) {
		Objects.requireNonNull(consumer, "consumer must not be null");
		for (int emitted = 0; emitted < maximumPerDrain; emitted++) {
			T identity = poll();
			if (identity == null) return;
			consumer.accept(identity);
		}
	}

	public synchronized int pendingCount() {
		return pending.size();
	}
}
