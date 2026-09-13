package dev.agaminggod.arenaagents.server;

import com.mojang.serialization.Codec;
import com.mojang.serialization.DataResult;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/** Keeps SavedData strings below Java's modified-UTF record limit while preserving legacy saves. */
public final class ChunkedSavedPayload {
	private static final int CHUNK_CHARACTERS = 20_000;
	private static final int MAX_CHUNKS = 1_024;
	private static final int MAX_CHUNK_UTF8_BYTES = 60_000;
	private static final int MAX_PAYLOAD_UTF8_BYTES = 16 * 1_024 * 1_024;
	private static final Codec<String> LEGACY_CODEC = boundedStringCodec(
			MAX_PAYLOAD_UTF8_BYTES, "SavedData legacy payload"
	);
	private static final Codec<List<String>> CHUNKS_CODEC = boundedStringListCodec(
			MAX_CHUNKS, MAX_CHUNK_UTF8_BYTES, MAX_PAYLOAD_UTF8_BYTES, "SavedData payload chunks"
	);

	private ChunkedSavedPayload() {
	}

	public static List<String> split(String payload) {
		if (payload == null || payload.isEmpty()) return List.of();
		requireUtf8BytesAtMost(payload, MAX_PAYLOAD_UTF8_BYTES, "SavedData payload");
		ArrayList<String> chunks = new ArrayList<>((payload.length() + CHUNK_CHARACTERS - 1) / CHUNK_CHARACTERS);
		for (int start = 0; start < payload.length(); start += CHUNK_CHARACTERS) {
			chunks.add(payload.substring(start, Math.min(payload.length(), start + CHUNK_CHARACTERS)));
		}
		if (chunks.size() > MAX_CHUNKS) throw invalid("SavedData payload has too many chunks");
		return List.copyOf(chunks);
	}

	public static String join(String legacyPayload, List<String> chunks) {
		if (chunks != null && !chunks.isEmpty()) {
			validateStrings(chunks, MAX_CHUNKS, MAX_CHUNK_UTF8_BYTES, MAX_PAYLOAD_UTF8_BYTES,
					"SavedData payload chunks");
			StringBuilder joined = new StringBuilder();
			for (String chunk : chunks) joined.append(chunk);
			return joined.toString();
		}
		String legacy = legacyPayload == null ? "" : legacyPayload;
		requireUtf8BytesAtMost(legacy, MAX_PAYLOAD_UTF8_BYTES, "SavedData legacy payload");
		return legacy;
	}

	public static Codec<String> legacyCodec() {
		return LEGACY_CODEC;
	}

	public static Codec<List<String>> chunksCodec() {
		return CHUNKS_CODEC;
	}

	public static Codec<List<String>> boundedStringListCodec(
			int maxEntries,
			int maxEntryUtf8Bytes,
			int maxAggregateUtf8Bytes,
			String label
	) {
		if (maxEntries < 0 || maxEntryUtf8Bytes < 0 || maxAggregateUtf8Bytes < 0) {
			throw new IllegalArgumentException("SavedData codec limits must be non-negative");
		}
		Codec<String> entryCodec = boundedStringCodec(maxEntryUtf8Bytes, label + " entry");
		return entryCodec.listOf(0, maxEntries).validate(values -> validationResult(
				values, maxEntries, maxEntryUtf8Bytes, maxAggregateUtf8Bytes, label
		));
	}

	private static Codec<String> boundedStringCodec(int maxUtf8Bytes, String label) {
		return Codec.STRING.validate(value -> {
			try {
				requireUtf8BytesAtMost(value, maxUtf8Bytes, label);
				return DataResult.success(value);
			} catch (IllegalArgumentException exception) {
				return DataResult.error(exception::getMessage);
			}
		});
	}

	private static DataResult<List<String>> validationResult(
			List<String> values,
			int maxEntries,
			int maxEntryUtf8Bytes,
			int maxAggregateUtf8Bytes,
			String label
	) {
		try {
			validateStrings(values, maxEntries, maxEntryUtf8Bytes, maxAggregateUtf8Bytes, label);
			return DataResult.success(values);
		} catch (IllegalArgumentException exception) {
			return DataResult.error(exception::getMessage);
		}
	}

	private static void validateStrings(
			List<String> values,
			int maxEntries,
			int maxEntryUtf8Bytes,
			int maxAggregateUtf8Bytes,
			String label
	) {
		if (values.size() > maxEntries) throw invalid(label + " exceed the entry limit");
		long aggregateBytes = 0L;
		for (String value : values) {
			if (value == null) throw invalid(label + " contain a null entry");
			int bytes = utf8Bytes(value, maxEntryUtf8Bytes, label + " entry");
			aggregateBytes += bytes;
			if (aggregateBytes > maxAggregateUtf8Bytes) {
				throw invalid(label + " exceed the aggregate byte limit");
			}
		}
	}

	private static void requireUtf8BytesAtMost(String value, int maximum, String label) {
		utf8Bytes(value, maximum, label);
	}

	private static int utf8Bytes(String value, int maximum, String label) {
		if (value.length() > maximum) throw invalid(label + " exceeds the byte limit");
		int bytes = value.getBytes(StandardCharsets.UTF_8).length;
		if (bytes > maximum) throw invalid(label + " exceeds the byte limit");
		return bytes;
	}

	private static IllegalArgumentException invalid(String message) {
		return new IllegalArgumentException(message);
	}
}
