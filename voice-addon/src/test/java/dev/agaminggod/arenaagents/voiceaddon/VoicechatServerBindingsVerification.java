package dev.agaminggod.arenaagents.voiceaddon;

import dev.agaminggod.arenaagents.server.voice.VoiceSubsystemConfiguration;
import java.util.ArrayList;
import java.util.List;

/** Verifies idempotent Simple Voice Chat lifecycle ownership and stale-handle fencing. */
final class VoicechatServerBindingsVerification {
	private VoicechatServerBindingsVerification() {
	}

	static int verify() {
		List<RecordingCapture> captures = new ArrayList<>();
		VoicechatServerBindings<Object, Object> bindings = new VoicechatServerBindings<>(configuration -> {
			RecordingCapture capture = new RecordingCapture(configuration);
			captures.add(capture);
			return capture;
		});
		Object server = new Object();
		Object apiA = new Object();
		Object apiB = new Object();
		VoiceSubsystemConfiguration first = configuration(18_201, 'a');
		VoiceSubsystemConfiguration second = configuration(18_202, 'b');
		VoiceSubsystemConfiguration restarted = configuration(18_203, 'c');

		bindings.started(apiA);
		bindings.started(apiA);
		VoicechatServerBindings.Binding<Object> original = bindings.configure(server, first);
		assertSame(server, bindings.configuredServer(apiA),
				"voice callback resolves its Minecraft server without reading a Minecraft player");
		bindings.accept(server, apiA, null);
		java.util.UUID speakingPlayer = java.util.UUID.randomUUID();
		bindings.cancel(server, speakingPlayer);
		assertEquals(speakingPlayer, captures.getFirst().cancelledPlayer,
				"consent revocation reaches the configured server capture");
		assertSame(apiA, original.owner(), "duplicate same-API start is one registration");
		bindings.started(apiA);
		VoicechatServerBindings.Binding<Object> reconfigured = bindings.configure(server, second);
		bindings.accept(server, apiA, null);
		assertSame(apiA, reconfigured.owner(), "duplicate claimed start keeps the server owner");
		assertEquals(false, original.active(), "reconfigure fences the displaced binding");
		original.close();
		assertEquals(true, reconfigured.active(), "stale close cannot clear a reconfigured binding");

		bindings.stopped(apiA);
		assertEquals(false, reconfigured.active(), "stop fences the live registration");
		bindings.started(apiB);
		assertSame(server, bindings.configuredServer(apiB),
				"restarted voice API resolves the existing Minecraft server before handoff");
		assertEquals(true, reconfigured.active(),
				"the current binding adopts a newer voice-chat generation without reconfiguring Minecraft");
		assertSame(apiB, reconfigured.owner(),
				"the current binding exposes the restarted voice-chat API");
		bindings.accept(server, apiB, null);
		VoicechatServerBindings.Binding<Object> replacement = bindings.configure(server, restarted);
		bindings.accept(server, apiB, null);
		assertSame(apiB, replacement.owner(), "same Minecraft server adopts its restarted API");
		assertEquals(1, captures.get(1).closes, "restart closes the displaced registration capture once");
		assertEquals(1, captures.get(2).closes,
				"explicit reconfiguration closes the dynamically rebound capture once");
		reconfigured.close();
		assertEquals(true, replacement.active(), "pre-restart close cannot clear the replacement");
		replacement.close();
		replacement.close();
		assertEquals(1, captures.get(2).closes, "replacement close is idempotent");
		bindings.stopped(apiB);
		bindings.started(apiB);
		VoicechatServerBindings.Binding<Object> sameApiRestart = bindings.configure(server, first);
		assertSame(apiB, sameApiRestart.owner(), "stop and restart with the same API is recoverable");
		sameApiRestart.close();

		VoicechatServerBindings<Object, Object> multiServer = new VoicechatServerBindings<>(configuration ->
				new RecordingCapture(configuration));
		Object serverA = new Object();
		Object serverB = new Object();
		Object serverC = new Object();
		Object apiC = new Object();
		multiServer.started(apiA);
		multiServer.configure(serverA, first);
		multiServer.started(apiB);
		multiServer.configure(serverB, second);
		multiServer.started(apiA);
		multiServer.started(apiC);
		VoicechatServerBindings.Binding<Object> configuredC = multiServer.configure(serverC, restarted);
		assertSame(apiC, configuredC.owner(),
				"duplicate start for a non-current live API cannot displace the next server registration");

		VoicechatServerBindings<Object, Object> pending = new VoicechatServerBindings<>(configuration ->
				new RecordingCapture(configuration));
		Object pendingServer = new Object();
		pending.started(apiA);
		pending.started(apiB);
		VoicechatServerBindings.Binding<Object> pendingB = pending.configure(pendingServer, first);
		pending.started(apiB);
		VoicechatServerBindings.Binding<Object> repeatedB = pending.configure(pendingServer, first);
		pending.stopped(apiA);
		pendingB.close();
		assertSame(apiB, repeatedB.owner(), "the newest pending API owns the configured server");
		assertEquals(true, repeatedB.active(),
				"stale stop and close from a displaced registration cannot clear the current API");
		return 19;
	}

	private static VoiceSubsystemConfiguration configuration(int port, char secret) {
		return new VoiceSubsystemConfiguration(
				"http://127.0.0.1:" + port + "/v1/tts",
				String.valueOf(secret).repeat(32)
		);
	}

	private static void assertEquals(Object expected, Object actual, String label) {
		if (!java.util.Objects.equals(expected, actual)) {
			throw new AssertionError(label + ": expected <" + expected + "> but was <" + actual + ">");
		}
	}

	private static void assertSame(Object expected, Object actual, String label) {
		if (expected != actual) throw new AssertionError(label);
	}

	private static final class RecordingCapture implements ServerSpeechCaptureRegistry.Capture {
		private final VoiceSubsystemConfiguration configuration;
		private int closes;
		private java.util.UUID cancelledPlayer;

		private RecordingCapture(VoiceSubsystemConfiguration configuration) {
			this.configuration = configuration;
		}

		@Override
		public void accept(MicrophonePacketSnapshot packet) {
		}

		@Override
		public void cancel(java.util.UUID playerId) {
			cancelledPlayer = playerId;
		}

		@Override
		public void close() {
			closes++;
		}
	}
}
