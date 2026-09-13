package dev.agaminggod.arenaagents.server.perception;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.function.IntFunction;

/** A bounded page reports its omissions, so missing entries never mean observed absence. */
final class ObservationPage {
	static final int MAX_ENTRIES = 32;
	static final int MAX_BYTES = 12_000;

	private ObservationPage() { }

	static JsonObject collect(int total, int offset, int limit, IntFunction<JsonObject> read, String source) {
		return collect(total, offset, limit, read, source, MAX_BYTES);
	}

	static JsonObject collect(int total, int offset, int limit, IntFunction<JsonObject> read, String source, int maximumBytes) {
		JsonArray entries = new JsonArray();
		int bytes = 2;
		boolean byteLimited = false;
		for (int index = Math.min(offset, total); index < total && entries.size() < limit; index++) {
			JsonObject entry = read.apply(index);
			int size = entry.toString().getBytes(StandardCharsets.UTF_8).length + (entries.isEmpty() ? 0 : 1);
			if (size + bytes > maximumBytes) { byteLimited = true; break; }
			entries.add(entry);
			bytes += size;
		}
		JsonObject result = new JsonObject();
		result.add("entries", entries);
		JsonObject coverage = new JsonObject();
		coverage.addProperty("offset", offset);
		coverage.addProperty("limit", limit);
		coverage.addProperty("total", total);
		coverage.addProperty("returned", entries.size());
		coverage.addProperty("hasMore", offset + entries.size() < total);
		coverage.addProperty("nextOffset", Math.min(total, offset + entries.size()));
		coverage.addProperty("complete", offset == 0 && entries.size() == total);
		coverage.addProperty("byteLimited", byteLimited);
		coverage.addProperty("source", source);
		result.add("coverage", coverage);
		return result;
	}

	static JsonObject coverage(JsonObject observation) {
		JsonObject coverage = new JsonObject();
		coverage.addProperty("mode", "sampled_visible");
		coverage.addProperty("complete", false);
		coverage.addProperty("blocksRadius", ServerObservationCollector.BLOCK_RADIUS);
		coverage.addProperty("landmarkDistanceLimit", ServerObservationCollector.LANDMARK_SIGHT_DISTANCE);
		coverage.addProperty("entitiesDistanceLimit", ServerObservationCollector.ENTITY_SIGHT_DISTANCE);
		JsonObject sections = new JsonObject();
		for (String field : List.of("entities", "blocks", "landmarks", "nearbyContainers")) {
			JsonElement value = observation.get(field);
			JsonObject section = new JsonObject();
			section.addProperty("returned", value != null && value.isJsonArray() ? value.getAsJsonArray().size() : 0);
			section.addProperty("complete", false);
			sections.add(field, section);
		}
		coverage.add("sections", sections);
		return coverage;
	}
}
