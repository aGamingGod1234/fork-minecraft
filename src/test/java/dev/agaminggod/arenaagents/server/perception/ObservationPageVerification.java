package dev.agaminggod.arenaagents.server.perception;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicInteger;

public final class ObservationPageVerification {
	private ObservationPageVerification() { }

	public static int verify() {
		AtomicInteger reads = new AtomicInteger();
		JsonObject page = ObservationPage.collect(90, 64, 16, index -> {
			reads.incrementAndGet();
			JsonObject entry = new JsonObject();
			entry.addProperty("slot", index);
			return entry;
		}, "open_menu");
		check(reads.get() == 16, "page reads only its requested slots");
		check(page.getAsJsonArray("entries").get(0).getAsJsonObject().get("slot").getAsInt() == 64, "slots beyond 63 are addressable");
		check(page.getAsJsonObject("coverage").get("hasMore").getAsBoolean(), "partial page signals more slots");
		check(page.getAsJsonObject("coverage").get("nextOffset").getAsInt() == 80, "cursor advances by delivered entries");
		JsonObject last = ObservationPage.collect(90, 80, 16, index -> new JsonObject(), "open_menu");
		check(!last.getAsJsonObject("coverage").get("hasMore").getAsBoolean(), "last page signals exhaustion");
		check(!last.getAsJsonObject("coverage").get("complete").getAsBoolean(), "last page is not the full menu");
		JsonObject large = ObservationPage.collect(20, 0, 32, index -> {
			JsonObject entry = new JsonObject();
			entry.addProperty("text", "x".repeat(2_000));
			return entry;
		}, "book");
		check(large.getAsJsonObject("coverage").get("byteLimited").getAsBoolean(), "byte limit is explicit");
		check(large.getAsJsonObject("coverage").get("nextOffset").getAsInt() == large.getAsJsonArray("entries").size(), "byte limit does not skip undelivered entries");
		check(large.getAsJsonArray("entries").toString().getBytes(StandardCharsets.UTF_8).length <= ObservationPage.MAX_BYTES, "entry payload stays within budget");
		JsonObject original = new JsonObject();
		JsonArray blocks = new JsonArray();
		for (int index = 0; index < 8; index++) {
			JsonObject block = new JsonObject();
			block.addProperty("x", index);
			block.addProperty("padding", "x".repeat(100));
			blocks.add(block);
		}
		original.add("blocks", blocks);
		original.add("coverage", ObservationPage.coverage(original));
		JsonObject reduced = ServerObservationWireBudget.fit(original, value -> value.toString().length() <= 650).observation();
		JsonObject section = reduced.getAsJsonObject("coverage").getAsJsonObject("sections").getAsJsonObject("blocks");
		check(section.get("returned").getAsInt() == reduced.getAsJsonArray("blocks").size(), "wire coverage matches delivered candidates");
		check(section.get("returned").getAsInt() + section.get("omittedByWire").getAsInt() == 8, "wire omissions retain original count");
		JsonObject twice = ServerObservationWireBudget.fit(reduced, value -> value.toString().length() <= 510).observation();
		JsonObject twiceSection = twice.getAsJsonObject("coverage").getAsJsonObject("sections").getAsJsonObject("blocks");
		check(twiceSection.get("returned").getAsInt() + twiceSection.get("omittedByWire").getAsInt() == 8, "repeated envelope fitting preserves omission total");
		return 12;
	}

	private static void check(boolean condition, String message) {
		if (!condition) throw new AssertionError(message);
	}
}
