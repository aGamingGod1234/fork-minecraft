package dev.agaminggod.arenaagents.server.perception;

import com.google.gson.JsonArray;
import net.minecraft.world.item.Items;

import java.lang.reflect.Field;
import java.util.Map;

/** Verifies that tag caching preserves values while isolating each JSON consumer. */
public final class ServerObservationTagCacheVerification {
	private ServerObservationTagCacheVerification() {
	}

	public static int verify() {
		net.minecraft.SharedConstants.tryDetectVersion();
		net.minecraft.server.Bootstrap.bootStrap();
		ServerObservationCollector.clearTagCache();
		var holder = Items.DIAMOND.builtInRegistryHolder();

		JsonArray first = ServerObservationCollector.tags(holder);
		assertEquals(1, cacheSize(), "holder is cached after first use");
		JsonArray expected = new JsonArray();
		holder.tags().map(tag -> "#" + tag.location().toString()).sorted()
				.limit(ServerObservationCollector.MAX_OBSERVATION_TAGS).forEach(expected::add);
		assertEquals(expected.toString(), first.toString(), "tag values preserve ordering and limit");

		first.add("#test:caller_mutation");
		JsonArray second = ServerObservationCollector.tags(holder);
		assertEquals(expected.toString(), second.toString(), "each consumer receives a defensive JsonArray");
		assertTrue(first != second, "each tags call returns a fresh JsonArray");

		ServerObservationCollector.clearTagCache();
		assertEquals(0, cacheSize(), "cache invalidation releases retained holders");
		JsonArray afterReload = ServerObservationCollector.tags(holder);
		assertEquals(expected.toString(), afterReload.toString(), "cache invalidation preserves recomputed values");

		assertEquals("Zombie", ServerObservationCollector.boundedEntityName("Zombie"),
				"short entity names remain unchanged");
		assertEquals(256, ServerObservationCollector.boundedEntityName("x".repeat(257)).length(),
				"entity names are bounded to the coordinator text limit");
		String emoji = "\uD83D\uDE00";
		assertEquals(emoji.repeat(256), ServerObservationCollector.boundedEntityName(emoji.repeat(257)),
				"entity name truncation preserves complete Unicode code points");
		return 9;
	}

	public static void main(String[] args) {
		System.out.println("PASS: " + verify() + " server observation tag cache assertions");
	}

	private static void assertEquals(Object expected, Object actual, String label) {
		if (!expected.equals(actual)) {
			throw new AssertionError(label + ": expected <" + expected + "> but was <" + actual + ">");
		}
	}

	private static void assertTrue(boolean condition, String label) {
		if (!condition) throw new AssertionError(label);
	}

	private static int cacheSize() {
		try {
			Field field = ServerObservationCollector.class.getDeclaredField("TAG_VALUES");
			field.setAccessible(true);
			Map<?, ?> values = (Map<?, ?>) field.get(null);
			synchronized (values) {
				return values.size();
			}
		} catch (ReflectiveOperationException exception) {
			throw new AssertionError("could not inspect tag cache", exception);
		}
	}
}
