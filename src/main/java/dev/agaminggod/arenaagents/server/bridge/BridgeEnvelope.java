package dev.agaminggod.arenaagents.server.bridge;

import com.google.gson.JsonObject;
import java.util.Objects;

public final class BridgeEnvelope {
	private final int protocolVersion;
	private final String serverInstanceId;
	private final String agentId;
	private final String type;
	private final String messageId;
	private final JsonObject payload;
	private volatile BridgeEnvelopeCodec.EncodedFrame encodedFrame;

	public BridgeEnvelope(
			int protocolVersion,
			String serverInstanceId,
			String agentId,
			String type,
			String messageId,
			JsonObject payload
	) {
		this(protocolVersion, serverInstanceId, agentId, type, messageId, payload, false);
	}

	private BridgeEnvelope(
			int protocolVersion,
			String serverInstanceId,
			String agentId,
			String type,
			String messageId,
			JsonObject payload,
			boolean trustedPayload
	) {
		if (protocolVersion != 2) {
			throw new BridgeProtocolException("UNSUPPORTED_VERSION", "Expected protocol version 2");
		}
		this.protocolVersion = protocolVersion;
		this.serverInstanceId = identifier(serverInstanceId, "serverInstanceId");
		this.agentId = identifier(agentId, "agentId");
		this.type = identifier(type, "type");
		this.messageId = text(messageId, "messageId", 128);
		JsonObject checkedPayload = Objects.requireNonNull(payload, "payload must not be null");
		this.payload = trustedPayload ? checkedPayload : checkedPayload.deepCopy();
	}

	static BridgeEnvelope fromDecoded(
			int protocolVersion,
			String serverInstanceId,
			String agentId,
			String type,
			String messageId,
			JsonObject payload
	) {
		return new BridgeEnvelope(protocolVersion, serverInstanceId, agentId, type, messageId, payload, true);
	}

	public int protocolVersion() { return protocolVersion; }
	public String serverInstanceId() { return serverInstanceId; }
	public String agentId() { return agentId; }
	public String type() { return type; }
	public String messageId() { return messageId; }

	public JsonObject payload() {
		return payload.deepCopy();
	}

	JsonObject payloadView() { return payload; }
	BridgeEnvelopeCodec.EncodedFrame encodedFrame() { return encodedFrame; }
	void cacheEncodedFrame(BridgeEnvelopeCodec.EncodedFrame frame) {
		if (encodedFrame == null) encodedFrame = Objects.requireNonNull(frame, "frame must not be null");
	}

	@Override
	public boolean equals(Object other) {
		if (this == other) return true;
		if (!(other instanceof BridgeEnvelope envelope)) return false;
		return protocolVersion == envelope.protocolVersion
				&& serverInstanceId.equals(envelope.serverInstanceId)
				&& agentId.equals(envelope.agentId)
				&& type.equals(envelope.type)
				&& messageId.equals(envelope.messageId)
				&& payload.equals(envelope.payload);
	}

	@Override
	public int hashCode() {
		return Objects.hash(protocolVersion, serverInstanceId, agentId, type, messageId, payload);
	}

	@Override
	public String toString() {
		return "BridgeEnvelope[protocolVersion=" + protocolVersion
				+ ", serverInstanceId=" + serverInstanceId
				+ ", agentId=" + agentId
				+ ", type=" + type
				+ ", messageId=" + messageId
				+ ", payload=" + payload + "]";
	}

	private static String identifier(String value, String field) {
		return text(value, field, 256);
	}

	private static String text(String value, String field, int maximum) {
		if (value == null || value.isBlank() || value.length() > maximum) {
			throw new BridgeProtocolException("INVALID_FIELD", field + " must contain at most " + maximum + " characters");
		}
		return value;
	}
}
