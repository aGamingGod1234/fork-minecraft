package dev.agaminggod.arenaagents.control;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.nio.charset.StandardCharsets;
import java.util.Objects;

public final class AgentControlSnapshotCodec {
	private static final Gson GSON = new Gson();
	public static final int MAX_ENCODED_BYTES = 32_767;
	@Deprecated(forRemoval = false)
	public static final int MAX_ENCODED_LENGTH = MAX_ENCODED_BYTES;

	private AgentControlSnapshotCodec() {
	}

	public static String encode(AgentControlSnapshot snapshot) {
		String encoded = GSON.toJson(Objects.requireNonNull(snapshot, "snapshot must not be null"));
		if (utf8Bytes(encoded) > MAX_ENCODED_BYTES) {
			throw new IllegalArgumentException("Control snapshot exceeds the wire limit");
		}
		return encoded;
	}

	public static AgentControlSnapshot decode(String encoded) {
		String checked = Objects.requireNonNull(encoded, "encoded must not be null");
		if (utf8Bytes(checked) > MAX_ENCODED_BYTES) {
			throw new IllegalArgumentException("Control snapshot exceeds the wire limit");
		}
		try {
			JsonObject object = JsonParser.parseString(checked).getAsJsonObject();
			if (schemaVersion(object) != AgentControlSnapshot.SCHEMA_VERSION) {
				throw new IllegalArgumentException("Unsupported control snapshot schema");
			}
			AgentControlSnapshot snapshot = GSON.fromJson(object, AgentControlSnapshot.class);
			if (snapshot == null) {
				throw new IllegalArgumentException("Control snapshot must be a JSON object");
			}
			return new AgentControlSnapshot(
					snapshot.schemaVersion(),
					snapshot.canControl(),
					snapshot.automationAvailable(),
					snapshot.automationStatus(),
					snapshot.generatedAtEpochMs(),
					snapshot.agents(),
					snapshot.groups(),
					snapshot.catalog()
			);
		} catch (RuntimeException exception) {
			throw new IllegalArgumentException("Invalid control snapshot", exception);
		}
	}

	private static int utf8Bytes(String value) {
		return value.getBytes(StandardCharsets.UTF_8).length;
	}

	private static int schemaVersion(JsonObject object) {
		JsonElement value = object.get("schemaVersion");
		if (value == null || !value.isJsonPrimitive() || !value.getAsJsonPrimitive().isNumber()) {
			throw new IllegalArgumentException("Control snapshot schema must be an integer");
		}
		try {
			return value.getAsBigDecimal().intValueExact();
		} catch (ArithmeticException | NumberFormatException exception) {
			throw new IllegalArgumentException("Control snapshot schema must be an integer", exception);
		}
	}
}
