package dev.agaminggod.arenaagents.server.perception;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.function.Predicate;
import java.util.function.Supplier;
import java.util.function.UnaryOperator;

public final class ObservationSectionCache<K, V> {
	private final int capacity;
	private final long maximumAgeTicks;
	private final UnaryOperator<V> copier;
	private final LinkedHashMap<K, Entry<V>> entries = new LinkedHashMap<>(16, 0.75F, true);

	public ObservationSectionCache(int capacity, long maximumAgeTicks, UnaryOperator<V> copier) {
		if (capacity <= 0 || maximumAgeTicks < 0L) throw new IllegalArgumentException("invalid observation cache bounds");
		this.capacity = capacity;
		this.maximumAgeTicks = maximumAgeTicks;
		this.copier = Objects.requireNonNull(copier, "copier must not be null");
	}

	public synchronized V getOrCompute(K key, long tick, Supplier<V> loader) {
		Objects.requireNonNull(key, "key must not be null");
		Objects.requireNonNull(loader, "loader must not be null");
		if (tick < 0L) throw new IllegalArgumentException("tick must be non-negative");
		Entry<V> existing = entries.get(key);
		if (existing != null && tick >= existing.tick() && tick - existing.tick() <= maximumAgeTicks) {
			return copy(existing.value());
		}
		V loaded = Objects.requireNonNull(loader.get(), "loader must not return null");
		entries.put(key, new Entry<>(tick, copy(loaded)));
		trim();
		return copy(loaded);
	}

	public synchronized void invalidate(K key) {
		entries.remove(Objects.requireNonNull(key, "key must not be null"));
	}

	public synchronized int invalidateMatching(Predicate<K> predicate) {
		Objects.requireNonNull(predicate, "predicate must not be null");
		int before = entries.size();
		entries.keySet().removeIf(predicate);
		return before - entries.size();
	}

	public synchronized void clear() {
		entries.clear();
	}

	private V copy(V value) {
		return Objects.requireNonNull(copier.apply(value), "copier must not return null");
	}

	private void trim() {
		while (entries.size() > capacity) {
			Map.Entry<K, Entry<V>> eldest = entries.entrySet().iterator().next();
			entries.remove(eldest.getKey());
		}
	}

	private record Entry<V>(long tick, V value) {
	}
}
