package dev.agaminggod.arenaagents.server.bridge;

import com.google.gson.JsonObject;
import dev.agaminggod.arenaagents.server.AgentSavedData;
import dev.agaminggod.arenaagents.server.AgentVerboseChat;
import dev.agaminggod.arenaagents.server.AgentVerboseState;
import dev.agaminggod.arenaagents.server.CodexAgentManager;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.lang.reflect.Field;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.Base64;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

public final class AgentVerboseVerification {
	private AgentVerboseVerification() {
	}

	public static void main(String[] args) {
		net.minecraft.SharedConstants.tryDetectVersion();
		net.minecraft.server.Bootstrap.bootStrap();
		int assertions = verify();
		System.out.println("Agent verbose verification passed: " + assertions + " assertions");
	}

	public static int verify() {
		verifyDefaultControlAfterAuthentication();
		verifyToggleSurvivesReconnect();
		verifyStrictVerboseEventIsAccepted();
		verifyStrictSchemaAndRedaction();
		verifyRawAgentErrorChatPolicy();
		return 38;
	}

	private static void verifyRawAgentErrorChatPolicy() {
		AgentVerboseState state = new AgentVerboseState();
		AtomicInteger rawReports = new AtomicInteger();
		reportRawAgentError(state, rawReports::incrementAndGet);
		assertEquals(1, rawReports.get(), "verbose off retains concise agent_error chat");

		state.setEnabled(true);
		reportRawAgentError(state, rawReports::incrementAndGet);
		assertEquals(1, rawReports.get(), "verbose on suppresses the duplicate raw agent_error message");

		state.setEnabled(false);
		reportRawAgentError(state, rawReports::incrementAndGet);
		assertEquals(2, rawReports.get(), "turning verbose off restores concise agent_error chat");
	}

	private static void reportRawAgentError(AgentVerboseState state, Runnable reporter) {
		try {
			var method = MultiplexedServerBridge.class.getDeclaredMethod(
					"reportRawAgentError", AgentVerboseState.class, Runnable.class);
			method.setAccessible(true);
			method.invoke(null, state, reporter);
		} catch (ReflectiveOperationException exception) {
			throw new AssertionError("missing raw agent_error reporting boundary", exception);
		}
	}

	private static void verifyStrictSchemaAndRedaction() {
		JsonObject valid = verbosePayload("planner", "Planning next action");
		MultiplexedServerBridge.VerboseEvent decoded = MultiplexedServerBridge.decodeVerboseEvent(valid);
		assertEquals(7L, decoded.goalRevision(), "verbose event retains its guarded revision");
		assertEquals("planner", decoded.stage(), "verbose event retains an approved stage");
		assertEquals("Planning next action", decoded.message(), "verbose event retains bounded plain text");
		for (String stage : List.of(
				"conversation", "lifecycle", "planner", "provider", "output", "decision",
				"action", "progress", "result", "retry", "error"
		)) {
			assertEquals(stage, MultiplexedServerBridge.decodeVerboseEvent(verbosePayload(stage, "message")).stage(),
					"wire contract accepts the approved " + stage + " stage");
		}

		JsonObject extra = valid.deepCopy();
		extra.addProperty("prompt", "private task");
		assertThrows(() -> MultiplexedServerBridge.decodeVerboseEvent(extra),
				"verbose events reject extra fields that could smuggle prompt data");
		assertThrows(() -> MultiplexedServerBridge.decodeVerboseEvent(verbosePayload("unknown", "message")),
				"verbose events reject unknown stages");
		assertThrows(() -> MultiplexedServerBridge.decodeVerboseEvent(verbosePayload("output", "x".repeat(257))),
				"verbose events reject messages beyond 256 characters");

		assertEquals("Sensitive details redacted.",
				AgentVerboseChat.sanitizeMessage("Authorization: Bearer sk-private-token"),
				"credential-shaped text is never sent to Minecraft chat");
		assertEquals("Private prompt details redacted.",
				AgentVerboseChat.sanitizeMessage("System prompt: private task text"),
				"private prompt text is never sent to Minecraft chat");
		assertEquals("World details redacted.",
				AgentVerboseChat.sanitizeMessage("Raw observation {\"blocks\":[1,2,3]}"),
				"raw world dumps are never sent to Minecraft chat");
		assertEquals("Technical details hidden.",
				AgentVerboseChat.sanitizeMessage("{\"directive\":\"replace\",\"summary\":\"Gather wood\"}"),
				"raw planner JSON never reaches Minecraft chat");
		CodexAgentManager manager = uninitializedManager();
		var record = manager.registry().create("gpt-5.6-luna", "high", Optional.of("RedactionAgent"), 1_000L);
		record = manager.registry().start(record.agentId(), "Find the hidden diamond cache", 1_001L).after();
		assertEquals("Private prompt details redacted.",
				AgentVerboseChat.sanitizeMessage(record, "Find the hidden diamond cache"),
				"unlabelled current prompt text is never sent to Minecraft chat");
	}

	private static JsonObject verbosePayload(String stage, String message) {
		JsonObject payload = new JsonObject();
		payload.addProperty("goalRevision", 7L);
		payload.addProperty("stage", stage);
		payload.addProperty("message", message);
		return payload;
	}

	private static void verifyStrictVerboseEventIsAccepted() {
		MultiplexedServerBridge bridge = null;
		Path secretFile = null;
		try {
			String secret = "fedcba9876543210fedcba9876543210";
			secretFile = Files.createTempFile("arena-agents-verbose-event-", ".txt");
			Files.writeString(secretFile, secret);
			CodexAgentManager manager = uninitializedManager();
			var record = manager.registry().create("gpt-5.6-luna", "high", Optional.of("VerboseAgent"), 1_000L);
			manager.registry().start(record.agentId(), "private task text", 1_001L);
			bridge = new MultiplexedServerBridge(manager, 0, secretFile);
			bridge.start();
			BridgeEnvelopeCodec codec = new BridgeEnvelopeCodec();
			try (Socket socket = new Socket(MultiplexedServerBridge.LOOPBACK_HOST, bridge.boundPortForVerification());
				 BufferedReader reader = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8))) {
				socket.setSoTimeout(1_000);
				sendHello(socket, reader, codec, secret, "hello-verbose-event");
				BridgeEnvelope helloAck = codec.decode(reader.readLine());
				assertEquals("hello_ack", helloAck.type(), "event fixture authenticates");
				assertEquals("verbose_control", codec.decode(reader.readLine()).type(), "event fixture receives control");

				JsonObject event = new JsonObject();
				event.addProperty("goalRevision", 1L);
				event.addProperty("stage", "planner");
				event.addProperty("message", "Planning next action");
				socket.getOutputStream().write(codec.encode(new BridgeEnvelope(
						2, helloAck.serverInstanceId(), record.agentId().toString(), "verbose_event", "verbose-event-1", event
				)).getBytes(StandardCharsets.UTF_8));
				socket.getOutputStream().flush();

				JsonObject heartbeat = new JsonObject();
				socket.getOutputStream().write(codec.encode(new BridgeEnvelope(
						2, helloAck.serverInstanceId(), "server", "heartbeat", "verbose-heartbeat-1", heartbeat
				)).getBytes(StandardCharsets.UTF_8));
				socket.getOutputStream().flush();
				BridgeEnvelope response = pollBridgeResponse(bridge, socket, reader, codec);
				assertEquals("heartbeat", response.type(), "strict verbose events preserve the authenticated session");
			}
		} catch (AssertionError error) {
			throw error;
		} catch (Exception exception) {
			throw new AssertionError("verbose event verification failed", exception);
		} finally {
			if (bridge != null) bridge.close();
			if (secretFile != null) {
				try {
					Files.deleteIfExists(secretFile);
				} catch (java.io.IOException exception) {
					throw new AssertionError("could not remove temporary event secret", exception);
				}
			}
		}
	}

	private static void verifyToggleSurvivesReconnect() {
		MultiplexedServerBridge bridge = null;
		Path secretFile = null;
		try {
			String secret = "abcdef0123456789abcdef0123456789";
			secretFile = Files.createTempFile("arena-agents-verbose-reconnect-", ".txt");
			Files.writeString(secretFile, secret);
			AgentVerboseState state = new AgentVerboseState();
			assertEquals(false, state.enabled(), "verbose state defaults off");
			bridge = new MultiplexedServerBridge(uninitializedManager(), 0, secretFile, state);
			bridge.start();
			BridgeEnvelopeCodec codec = new BridgeEnvelopeCodec();
			try (Socket first = new Socket(MultiplexedServerBridge.LOOPBACK_HOST, bridge.boundPortForVerification());
				 BufferedReader reader = new BufferedReader(new InputStreamReader(first.getInputStream(), StandardCharsets.UTF_8))) {
				first.setSoTimeout(1_000);
				sendHello(first, reader, codec, secret, "hello-verbose-first");
				assertEquals("hello_ack", codec.decode(reader.readLine()).type(), "first session authenticates");
				assertEquals(false, codec.decode(reader.readLine()).payload().get("enabled").getAsBoolean(),
						"first session receives disabled control");

				bridge.setVerbose(true);
				assertEquals(true, state.enabled(), "bridge toggle updates the server session state");
				BridgeEnvelope enabled = codec.decode(reader.readLine());
				assertEquals(true, enabled.payload().get("enabled").getAsBoolean(),
						"active coordinator receives the enabled control");
			}
			MultiplexedServerBridge activeBridge = bridge;
			awaitCondition(() -> !activeBridge.authenticated(), "first verbose session closes");
			awaitCondition(activeBridge::coordinatorDisconnectPendingForVerification,
					"first verbose session schedules coordinator cleanup");
			bridge.tick();
			try (Socket second = new Socket(MultiplexedServerBridge.LOOPBACK_HOST, bridge.boundPortForVerification());
				 BufferedReader reader = new BufferedReader(new InputStreamReader(second.getInputStream(), StandardCharsets.UTF_8))) {
				second.setSoTimeout(1_000);
				sendHello(second, reader, codec, secret, "hello-verbose-second");
				assertEquals("hello_ack", codec.decode(reader.readLine()).type(), "replacement session authenticates");
				assertEquals(true, codec.decode(reader.readLine()).payload().get("enabled").getAsBoolean(),
						"replacement coordinator inherits the enabled server setting");
			}
		} catch (AssertionError error) {
			throw error;
		} catch (Exception exception) {
			throw new AssertionError("verbose reconnect verification failed", exception);
		} finally {
			if (bridge != null) bridge.close();
			if (secretFile != null) {
				try {
					Files.deleteIfExists(secretFile);
				} catch (java.io.IOException exception) {
					throw new AssertionError("could not remove temporary reconnect secret", exception);
				}
			}
		}
	}

	private static void verifyDefaultControlAfterAuthentication() {
		MultiplexedServerBridge bridge = null;
		Path secretFile = null;
		try {
			String secret = "0123456789abcdef0123456789abcdef";
			secretFile = Files.createTempFile("arena-agents-verbose-secret-", ".txt");
			Files.writeString(secretFile, secret);
			bridge = new MultiplexedServerBridge(uninitializedManager(), 0, secretFile);
			bridge.start();
			BridgeEnvelopeCodec codec = new BridgeEnvelopeCodec();
			try (Socket socket = new Socket(MultiplexedServerBridge.LOOPBACK_HOST, bridge.boundPortForVerification());
				 BufferedReader reader = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8))) {
				socket.setSoTimeout(750);
				sendHello(socket, reader, codec, secret, "hello-verbose-default");

				assertEquals("hello_ack", codec.decode(reader.readLine()).type(),
						"authentication acknowledges the coordinator first");
				BridgeEnvelope control;
				try {
					control = codec.decode(reader.readLine());
				} catch (SocketTimeoutException exception) {
					throw new AssertionError("authentication must publish the server session verbose setting", exception);
				}
				assertEquals("verbose_control", control.type(),
						"authentication publishes verbose control before ordinary traffic");
				assertEquals(false, control.payload().get("enabled").getAsBoolean(),
						"a new server session starts with verbose output disabled");
			}
		} catch (AssertionError error) {
			throw error;
		} catch (Exception exception) {
			throw new AssertionError("verbose bridge verification failed", exception);
		} finally {
			if (bridge != null) bridge.close();
			if (secretFile != null) {
				try {
					Files.deleteIfExists(secretFile);
				} catch (java.io.IOException exception) {
					throw new AssertionError("could not remove temporary bridge secret", exception);
				}
			}
		}
	}

	private static CodexAgentManager uninitializedManager() {
		try {
			Field field = sun.misc.Unsafe.class.getDeclaredField("theUnsafe");
			field.setAccessible(true);
			sun.misc.Unsafe unsafe = (sun.misc.Unsafe) field.get(null);
			CodexAgentManager manager = (CodexAgentManager) unsafe.allocateInstance(CodexAgentManager.class);
			Field savedData = CodexAgentManager.class.getDeclaredField("savedData");
			unsafe.putObject(manager, unsafe.objectFieldOffset(savedData), new AgentSavedData());
			Field pendingRegistrations = CodexAgentManager.class.getDeclaredField("pendingAgentRegistrations");
			unsafe.putObject(manager, unsafe.objectFieldOffset(pendingRegistrations), ConcurrentHashMap.newKeySet());
			return manager;
		} catch (ReflectiveOperationException exception) {
			throw new AssertionError("could not allocate verbose-only manager", exception);
		}
	}

	private static void sendHello(Socket socket, BufferedReader reader, BridgeEnvelopeCodec codec, String secret, String messageId)
			throws Exception {
		String clientNonce = Base64.getUrlEncoder().withoutPadding().encodeToString(
				MessageDigest.getInstance("SHA-256").digest(messageId.getBytes(StandardCharsets.UTF_8))
		);
		JsonObject challenge = new JsonObject();
		challenge.addProperty("clientNonce", clientNonce);
		socket.getOutputStream().write(codec.encode(new BridgeEnvelope(
				2, "pending", "server", "auth_challenge", messageId + "-challenge", challenge
		)).getBytes(StandardCharsets.UTF_8));
		socket.getOutputStream().flush();
		BridgeEnvelope response = codec.decode(reader.readLine());
		String serverNonce = response.payload().get("serverNonce").getAsString();
		JsonObject hello = new JsonObject();
		hello.addProperty("replyTo", response.messageId());
		hello.addProperty("clientNonce", clientNonce);
		hello.addProperty("serverNonce", serverNonce);
		hello.addProperty("proof", MultiplexedServerBridge.authenticationProof(
				secret, "coordinator", clientNonce, serverNonce, response.serverInstanceId(), null
		));
		socket.getOutputStream().write(codec.encode(new BridgeEnvelope(
				2, response.serverInstanceId(), "server", "hello", messageId, hello
		)).getBytes(StandardCharsets.UTF_8));
		socket.getOutputStream().flush();
	}

	private static void awaitCondition(java.util.function.BooleanSupplier condition, String label) {
		long deadline = System.nanoTime() + 2_000_000_000L;
		while (!condition.getAsBoolean() && System.nanoTime() < deadline) Thread.onSpinWait();
		if (!condition.getAsBoolean()) throw new AssertionError(label);
	}

	private static BridgeEnvelope pollBridgeResponse(
			MultiplexedServerBridge bridge,
			Socket socket,
			BufferedReader reader,
			BridgeEnvelopeCodec codec
	) throws Exception {
		long deadline = System.nanoTime() + 2_000_000_000L;
		while (System.nanoTime() < deadline) {
			bridge.tick();
			if (socket.getInputStream().available() > 0) return codec.decode(reader.readLine());
			Thread.sleep(5L);
		}
		throw new AssertionError("bridge did not publish a response");
	}

	private static void assertEquals(Object expected, Object actual, String label) {
		if (!java.util.Objects.equals(expected, actual)) {
			throw new AssertionError(label + ": expected=" + expected + ", actual=" + actual);
		}
	}

	private static void assertThrows(Runnable action, String label) {
		try {
			action.run();
		} catch (BridgeProtocolException exception) {
			return;
		}
		throw new AssertionError(label);
	}
}
