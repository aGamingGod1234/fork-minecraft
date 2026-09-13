package dev.agaminggod.arenaagents.server.bridge;

import com.google.gson.JsonObject;
import dev.agaminggod.arenaagents.protocol.ActionType;
import dev.agaminggod.arenaagents.protocol.ProtocolConstants;
import dev.agaminggod.arenaagents.server.runtime.ServerActionRequest;
import java.nio.charset.StandardCharsets;

public final class BridgeEnvelopeCodecVerification {
	private BridgeEnvelopeCodecVerification() {
	}

	public static int verify() {
		BridgeEnvelopeCodec codec = new BridgeEnvelopeCodec();
		JsonObject payload = new JsonObject();
		payload.addProperty("goalRevision", 7L);
		BridgeEnvelope source = new BridgeEnvelope(2, "server-instance", "agent-id", "observation", "message-1", payload);
		payload.addProperty("goalRevision", 99L);
		JsonObject publicPayload = source.payload();
		publicPayload.addProperty("goalRevision", 100L);
		assertEquals(7L, source.payload().get("goalRevision").getAsLong(), "public payload mutations cannot change an envelope");
		BridgeEnvelopeCodec.EncodedFrame firstFrame = codec.encodeFrame(source);
		BridgeEnvelopeCodec.EncodedFrame secondFrame = codec.encodeFrame(source);
		assertEquals(true, firstFrame == secondFrame, "an immutable envelope caches one encoded frame");
		assertEquals(codec.encode(source).getBytes(StandardCharsets.UTF_8).length, firstFrame.byteLength(), "encoded frame reports exact wire bytes");
		assertEquals(codec.encodedLineBytes(source), codec.encodedLineBytesForPayload(
				source.protocolVersion(), source.serverInstanceId(), source.agentId(), source.type(), source.messageId(),
				source.payload()), "transient payload sizing matches the envelope encoder");
		JsonObject escapedPayload = new JsonObject();
		escapedPayload.addProperty("text", "quotes \\\" slash \\\\ newline \\n unicode 世界 🌍");
		BridgeEnvelope escaped = new BridgeEnvelope(2, "server-\\\"instance", "agent-\\\\id", "observation", "message-\\\"1", escapedPayload);
		assertEquals(codec.encodedLineBytes(escaped), codec.encodedLineBytesForPayload(
				escaped.protocolVersion(), escaped.serverInstanceId(), escaped.agentId(), escaped.type(), escaped.messageId(),
				escaped.payload()), "transient sizing preserves escaping and multibyte UTF-8");
		byte[] detachedBytes = firstFrame.bytes();
		detachedBytes[0] = (byte) 'x';
		assertEquals((byte) '{', firstFrame.bytes()[0], "public frame bytes are detached");
		BridgeEnvelope decoded = codec.decode(codec.encode(source).trim());
		assertEquals(source.protocolVersion(), decoded.protocolVersion(), "protocol version");
		assertEquals(source.serverInstanceId(), decoded.serverInstanceId(), "server instance");
		assertEquals(source.agentId(), decoded.agentId(), "agent ID");
		assertEquals(source.type(), decoded.type(), "message type");
		assertEquals(7L, decoded.payload().get("goalRevision").getAsLong(), "payload revision");
		expectFailure(() -> codec.decode("{}"), "MISSING_FIELD");
		expectFailure(() -> codec.decode("x".repeat(BridgeEnvelopeCodec.MAX_LINE_BYTES + 1)), "LINE_TOO_LARGE");
		expectFailure(() -> codec.decode("{\"protocolVersion\":2,\"protocolVersion\":2}"), "DUPLICATE_FIELD");
		expectFailure(() -> codec.decode("{\"protocolVersion\":2,\"serverInstanceId\":\"server-instance\",\"agentId\":\"agent\",\"type\":\"observation\",\"messageId\":\"m\",\"payload\":{\"goalRevision\":1,\"goalRevision\":2}}"), "DUPLICATE_FIELD");
		expectFailure(() -> codec.decode("{\"protocolVersion\":\"2\",\"serverInstanceId\":\"server-instance\",\"agentId\":\"agent\",\"type\":\"observation\",\"messageId\":\"m\",\"payload\":{}}"), "INVALID_FIELD");
		JsonObject boundedPayload = new JsonObject();
		boundedPayload.addProperty("text", "");
		BridgeEnvelope bounded = new BridgeEnvelope(2, "server-instance", "agent", "observation", "utf8-bound", boundedPayload);
		int fixedJsonBytes = codec.encodeFrame(bounded).byteLength() - 1;
		boundedPayload.addProperty("text", "a".repeat(BridgeEnvelopeCodec.MAX_LINE_BYTES - fixedJsonBytes));
		BridgeEnvelope exactLimit = new BridgeEnvelope(2, "server-instance", "agent", "observation", "utf8-bound", boundedPayload);
		assertEquals(BridgeEnvelopeCodec.MAX_LINE_BYTES + 1, codec.encodeFrame(exactLimit).byteLength(), "line limit excludes the newline delimiter");
		assertEquals(BridgeEnvelopeCodec.MAX_LINE_BYTES, codec.encodedLineBytes(exactLimit), "line sizing excludes only the frame delimiter");
		boundedPayload.addProperty("text", boundedPayload.get("text").getAsString() + "a");
		expectFailure(() -> codec.encode(new BridgeEnvelope(2, "server-instance", "agent", "observation", "utf8-bound", boundedPayload)), "LINE_TOO_LARGE");
		JsonObject waitArguments = new JsonObject();
		waitArguments.addProperty("durationMs", 25L);
		ServerActionRequest primitive = MultiplexedServerBridge.decodeActionRequest(new BridgeEnvelope(
				2, "server-instance", "00000000-0000-0000-0000-000000000001", "action_command", "message-2",
				actionPayload("wait-1", ActionType.WAIT.wireName(), waitArguments)
		));
		assertEquals(ActionType.WAIT, primitive.type(), "primitive action decodes through the bridge");
		assertEquals("program-1-1", primitive.provenance().programId(), "bridge attaches immutable provenance");
		JsonObject missingProvenance = actionPayload("wait-2", ActionType.WAIT.wireName(), waitArguments);
		missingProvenance.remove("provenance");
		expectFailure(() -> MultiplexedServerBridge.decodeActionRequest(new BridgeEnvelope(
				2, "server-instance", "00000000-0000-0000-0000-000000000001", "action_command", "message-3", missingProvenance
		)), "MISSING_FIELD");
		JsonObject fightArguments = new JsonObject();
		fightArguments.addProperty("targetSelector", "nearest_hostile");
		fightArguments.addProperty("desiredRange", 2.5D);
		fightArguments.addProperty("timeoutMs", 5_000L);
		JsonObject fightPayload = actionPayload("fight-1", ActionType.FIGHT_TARGET.wireName(), fightArguments);
		expectFailure(() -> MultiplexedServerBridge.decodeActionRequest(new BridgeEnvelope(
				2, "server-instance", "00000000-0000-0000-0000-000000000001", "action_command", "message-2", fightPayload
		)), "UNSUPPORTED_ARENA_SCRIPT_ACTION");
		JsonObject aliasPayload = actionPayload("wait-3", ActionType.WAIT.wireName(), waitArguments);
		aliasPayload.addProperty("commandId", "wait-3");
		expectFailure(() -> MultiplexedServerBridge.decodeActionRequest(new BridgeEnvelope(
				2, "server-instance", "00000000-0000-0000-0000-000000000001", "action_command", "message-4", aliasPayload
		)), "INVALID_FIELD");
		JsonObject coercionPayload = actionPayload("wait-4", ActionType.WAIT.wireName(), waitArguments);
		coercionPayload.addProperty("goalRevision", "1");
		expectFailure(() -> MultiplexedServerBridge.decodeActionRequest(new BridgeEnvelope(
				2, "server-instance", "00000000-0000-0000-0000-000000000001", "action_command", "message-5", coercionPayload
		)), "INVALID_FIELD");
		JsonObject fractionalProvenance = actionPayload("wait-5", ActionType.WAIT.wireName(), waitArguments);
		fractionalProvenance.getAsJsonObject("provenance").addProperty("programVersion", 1.5D);
		expectFailure(() -> MultiplexedServerBridge.decodeActionRequest(new BridgeEnvelope(
				2, "server-instance", "00000000-0000-0000-0000-000000000001", "action_command", "message-6", fractionalProvenance
		)), "INVALID_PROVENANCE");
		assertEquals(ProtocolConstants.MAX_RESULT_MESSAGE_LENGTH,
				MultiplexedServerBridge.boundedRejectionMessage("x".repeat(ProtocolConstants.MAX_RESULT_MESSAGE_LENGTH + 64)).length(),
				"rejection messages are bounded before result encoding");
		String emojiBounded = MultiplexedServerBridge.boundedRejectionMessage("x".repeat(ProtocolConstants.MAX_RESULT_MESSAGE_LENGTH - 1) + "😀".repeat(2));
		assertEquals(ProtocolConstants.MAX_RESULT_MESSAGE_LENGTH - 1, emojiBounded.length(), "emoji rejection clamp does not split a surrogate pair");
		assertEquals(false, Character.isHighSurrogate(emojiBounded.charAt(emojiBounded.length() - 1)), "emoji rejection clamp leaves valid UTF-16");
		assertEquals("Action rejected", MultiplexedServerBridge.boundedRejectionMessage(" \t"), "blank rejection has stable fallback");
		return 27;
	}

	private static JsonObject actionPayload(String actionId, String type, JsonObject arguments) {
		String traceId = "trace-" + actionId;
		JsonObject provenance = new JsonObject();
		provenance.addProperty("provider", "codex");
		provenance.addProperty("model", "gpt-5.6-sol");
		provenance.addProperty("reasoningEffort", "high");
		provenance.addProperty("serviceTier", "priority");
		provenance.addProperty("programId", "program-1-1");
		provenance.addProperty("programVersion", 1L);
		provenance.addProperty("sourceStepId", "step-80-126");
		provenance.addProperty("eventSequence", 4L);
		provenance.addProperty("traceId", traceId);
		JsonObject payload = new JsonObject();
		payload.addProperty("traceId", traceId);
		payload.addProperty("goalRevision", 1L);
		payload.addProperty("actionId", actionId);
		payload.addProperty("actionType", type);
		payload.add("arguments", arguments);
		payload.add("provenance", provenance);
		return payload;
	}

	private static void expectFailure(Runnable operation, String code) {
		try {
			operation.run();
			throw new AssertionError("Expected failure " + code);
		} catch (BridgeProtocolException exception) {
			assertEquals(code, exception.code(), "failure code");
		}
	}

	private static void assertEquals(Object expected, Object actual, String label) {
		if (!expected.equals(actual)) {
			throw new AssertionError(label + ": expected <" + expected + "> but was <" + actual + ">");
		}
	}
}
