package dev.agaminggod.arenaagents.server.bridge;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonToken;
import java.io.IOException;
import java.io.StringReader;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Objects;
import java.util.Set;

public final class BridgeEnvelopeCodec {
	public static final int MAX_LINE_BYTES = 65_536;
	private static final Set<String> FIELDS = Set.of(
			"protocolVersion", "serverInstanceId", "agentId", "type", "messageId", "payload"
	);
	private static final Gson GSON = new GsonBuilder().serializeNulls().create();

	public BridgeEnvelope decode(String line) {
		if (line == null || line.getBytes(StandardCharsets.UTF_8).length > MAX_LINE_BYTES) {
			throw new BridgeProtocolException("LINE_TOO_LARGE", "Protocol line exceeds " + MAX_LINE_BYTES + " bytes");
		}
		JsonElement parsed;
		try {
			parsed = decodeUniqueJson(line);
		} catch (BridgeProtocolException exception) {
			throw exception;
		} catch (RuntimeException | IOException exception) {
			throw new BridgeProtocolException("MALFORMED_JSON", "Protocol line is not valid JSON", exception);
		}
		if (!parsed.isJsonObject()) {
			throw new BridgeProtocolException("INVALID_ENVELOPE", "Protocol envelope must be an object");
		}
		JsonObject object = parsed.getAsJsonObject();
		for (String field : FIELDS) {
			if (!object.has(field)) {
				throw new BridgeProtocolException("MISSING_FIELD", "Missing envelope field: " + field);
			}
		}
		for (String field : object.keySet()) {
			if (!FIELDS.contains(field)) {
				throw new BridgeProtocolException("INVALID_FIELD", "Unknown envelope field: " + field);
			}
		}
		if (!object.get("payload").isJsonObject()) {
			throw new BridgeProtocolException("INVALID_FIELD", "payload must be an object");
		}
		return BridgeEnvelope.fromDecoded(
				requiredInteger(object, "protocolVersion"),
				requiredString(object, "serverInstanceId"),
				requiredString(object, "agentId"),
				requiredString(object, "type"),
				requiredString(object, "messageId"),
				object.getAsJsonObject("payload")
		);
	}

	private static JsonElement decodeUniqueJson(String line) throws IOException {
		try (JsonReader reader = new JsonReader(new StringReader(line))) {
			reader.setLenient(false);
			JsonElement value = readElement(reader);
			if (reader.peek() != JsonToken.END_DOCUMENT) throw new BridgeProtocolException("MALFORMED_JSON", "Protocol line has trailing content");
			return value;
		}
	}

	private static JsonElement readElement(JsonReader reader) throws IOException {
		return switch (reader.peek()) {
			case BEGIN_OBJECT -> readObject(reader);
			case BEGIN_ARRAY -> readArray(reader);
			case STRING -> new JsonPrimitive(reader.nextString());
			case NUMBER -> new JsonPrimitive(new BigDecimal(reader.nextString()));
			case BOOLEAN -> new JsonPrimitive(reader.nextBoolean());
			case NULL -> {
				reader.nextNull();
				yield JsonNull.INSTANCE;
			}
			default -> throw new BridgeProtocolException("MALFORMED_JSON", "Protocol line has an invalid JSON token");
		};
	}

	private static JsonObject readObject(JsonReader reader) throws IOException {
		JsonObject object = new JsonObject();
		Set<String> names = new HashSet<>();
		reader.beginObject();
		while (reader.hasNext()) {
			String name = reader.nextName();
			if (!names.add(name)) throw new BridgeProtocolException("DUPLICATE_FIELD", "Duplicate JSON field: " + name);
			object.add(name, readElement(reader));
		}
		reader.endObject();
		return object;
	}

	private static JsonArray readArray(JsonReader reader) throws IOException {
		JsonArray array = new JsonArray();
		reader.beginArray();
		while (reader.hasNext()) array.add(readElement(reader));
		reader.endArray();
		return array;
	}

	private static String requiredString(JsonObject object, String field) {
		if (!object.get(field).isJsonPrimitive() || !object.get(field).getAsJsonPrimitive().isString()) {
			throw new BridgeProtocolException("INVALID_FIELD", field + " must be a JSON string");
		}
		return object.get(field).getAsString();
	}

	private static int requiredInteger(JsonObject object, String field) {
		if (!object.get(field).isJsonPrimitive() || !object.get(field).getAsJsonPrimitive().isNumber()) {
			throw new BridgeProtocolException("INVALID_FIELD", field + " must be a JSON number");
		}
		try {
			return object.get(field).getAsBigDecimal().intValueExact();
		} catch (ArithmeticException exception) {
			throw new BridgeProtocolException("INVALID_FIELD", field + " must be an integer", exception);
		}
	}

	public String encode(BridgeEnvelope envelope) {
		return encodeFrame(envelope).utf8();
	}

	/** Returns the exact UTF-8 wire size, including the newline frame delimiter. */
	public int encodedBytes(BridgeEnvelope envelope) {
		return encodedFrameUnchecked(envelope).byteLength();
	}

	/** Returns the exact UTF-8 JSON line size, excluding the newline frame delimiter. */
	public int encodedLineBytes(BridgeEnvelope envelope) {
		return encodedFrameUnchecked(envelope).byteLength() - 1;
	}

	/**
	 * Returns an exact UTF-8 JSON line size for a transient payload probe without allocating an
	 * envelope, defensive payload copy, or disposable newline frame. The metadata values are owned
	 * by the caller for the duration of this call; callers that need envelope validation should use
	 * {@link #encodedLineBytes(BridgeEnvelope)} instead.
	 */
	int encodedLineBytesForPayload(
			int protocolVersion,
			String serverInstanceId,
			String agentId,
			String type,
			String messageId,
			JsonObject payload
	) {
		Objects.requireNonNull(serverInstanceId, "serverInstanceId must not be null");
		Objects.requireNonNull(agentId, "agentId must not be null");
		Objects.requireNonNull(type, "type must not be null");
		Objects.requireNonNull(messageId, "messageId must not be null");
		Objects.requireNonNull(payload, "payload must not be null");
		return serialize(protocolVersion, serverInstanceId, agentId, type, messageId, payload)
				.getBytes(StandardCharsets.UTF_8).length;
	}

	public EncodedFrame encodeFrame(BridgeEnvelope envelope) {
		EncodedFrame encoded = encodedFrameUnchecked(envelope);
		if (encoded.byteLength() - 1 > MAX_LINE_BYTES) {
			throw new BridgeProtocolException("LINE_TOO_LARGE", "Encoded protocol line exceeds " + MAX_LINE_BYTES + " UTF-8 bytes");
		}
		return encoded;
	}

	private EncodedFrame encodedFrameUnchecked(BridgeEnvelope envelope) {
		BridgeEnvelope checked = java.util.Objects.requireNonNull(envelope, "envelope must not be null");
		EncodedFrame cached = checked.encodedFrame();
		if (cached != null) return cached;
		byte[] jsonBytes = serialize(checked).getBytes(StandardCharsets.UTF_8);
		byte[] wireBytes = Arrays.copyOf(jsonBytes, jsonBytes.length + 1);
		wireBytes[jsonBytes.length] = (byte) '\n';
		EncodedFrame encoded = new EncodedFrame(wireBytes);
		checked.cacheEncodedFrame(encoded);
		return checked.encodedFrame();
	}

	private static String serialize(BridgeEnvelope envelope) {
		return serialize(
				envelope.protocolVersion(),
				envelope.serverInstanceId(),
				envelope.agentId(),
				envelope.type(),
				envelope.messageId(),
				envelope.payloadView()
		);
	}

	private static String serialize(
			int protocolVersion,
			String serverInstanceId,
			String agentId,
			String type,
			String messageId,
			JsonObject payload
	) {
		JsonObject object = new JsonObject();
		object.addProperty("protocolVersion", protocolVersion);
		object.addProperty("serverInstanceId", serverInstanceId);
		object.addProperty("agentId", agentId);
		object.addProperty("type", type);
		object.addProperty("messageId", messageId);
		object.add("payload", payload);
		return GSON.toJson(object);
	}

	public static final class EncodedFrame {
		private final byte[] bytes;
		private volatile String utf8;

		private EncodedFrame(byte[] bytes) {
			this.bytes = bytes;
		}

		public int byteLength() { return bytes.length; }
		public byte[] bytes() { return bytes.clone(); }
		byte[] bytesView() { return bytes; }
		public String utf8() {
			String value = utf8;
			if (value != null) return value;
			value = new String(bytes, StandardCharsets.UTF_8);
			utf8 = value;
			return value;
		}
	}
}
