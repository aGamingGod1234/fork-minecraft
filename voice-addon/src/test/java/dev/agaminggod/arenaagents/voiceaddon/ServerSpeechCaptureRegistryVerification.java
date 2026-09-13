package dev.agaminggod.arenaagents.voiceaddon;

import de.maxhenkel.voicechat.api.events.MicrophonePacketEvent;
import dev.agaminggod.arenaagents.server.voice.VoiceSubsystemConfiguration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static java.util.concurrent.TimeUnit.SECONDS;

/** Per-server ownership verification for the real addon speech-capture registry. */
public final class ServerSpeechCaptureRegistryVerification {
	private ServerSpeechCaptureRegistryVerification() {
	}

	public static void main(String[] arguments) throws Exception {
		int assertions = verify();
		System.out.println("PASS: " + assertions + " per-server speech-capture assertions");
	}

	static int verify() throws Exception {
		Object serverA = new Object();
		Object serverB = new Object();
		Object voicechatA = new Object();
		Object voicechatB = new Object();
		VoiceSubsystemConfiguration configurationA = new VoiceSubsystemConfiguration(
				"http://127.0.0.1:18001/v1/tts", "a".repeat(32)
		);
		VoiceSubsystemConfiguration configurationB = new VoiceSubsystemConfiguration(
				"http://127.0.0.1:18002/v1/tts", "b".repeat(32)
		);
		VoiceSubsystemConfiguration restartedConfigurationA = new VoiceSubsystemConfiguration(
				"http://127.0.0.1:18003/v1/tts", "c".repeat(32)
		);
		List<RecordingCapture> captures = new CopyOnWriteArrayList<>();
		ServerSpeechCaptureRegistry<Object, Object> registry = new ServerSpeechCaptureRegistry<>(configuration -> {
			RecordingCapture capture = new RecordingCapture(configuration);
			captures.add(capture);
			return capture;
		});

		registry.configure(serverA, voicechatA, configurationA);
		registry.configure(serverB, voicechatB, configurationB);
		acceptConcurrently(registry, serverA, voicechatA, serverB, voicechatB);

		List<RecordingCapture> capturesA = capturesFor(captures, configurationA);
		List<RecordingCapture> capturesB = capturesFor(captures, configurationB);
		assertEquals(1, capturesA.size(), "concurrent first packets coalesce one capture for server A");
		assertEquals(1, capturesB.size(), "concurrent first packets coalesce one capture for server B");
		RecordingCapture captureA = capturesA.getFirst();
		RecordingCapture captureB = capturesB.getFirst();
		assertTrue(captureA != captureB, "two servers own distinct speech clients");
		assertEquals(configurationA, captureA.configuration, "server A uses its endpoint and secret");
		assertEquals(configurationB, captureB.configuration, "server B uses its endpoint and secret");
		assertEquals(8, captureA.accepts, "all server A packets use its capture");
		assertEquals(8, captureB.accepts, "all server B packets use its capture");

		registry.clear(serverA);
		assertEquals(1, captureA.closes, "clearing server A closes its capture once");
		assertEquals(0, captureB.closes, "clearing server A leaves server B active");
		registry.accept(serverB, voicechatB, null);
		assertEquals(9, captureB.accepts, "server B still accepts packets after server A closes");

		Object restartedVoicechatA = new Object();
		registry.configure(serverA, restartedVoicechatA, restartedConfigurationA);
		registry.accept(serverA, restartedVoicechatA, null);
		List<RecordingCapture> restartedCapturesA = capturesFor(captures, restartedConfigurationA);
		assertEquals(1, restartedCapturesA.size(), "restarted server A constructs one new capture");
		RecordingCapture restartedCaptureA = restartedCapturesA.getFirst();
		assertTrue(restartedCaptureA != captureA, "restarted server A cannot reuse its prior worker");
		assertEquals(restartedConfigurationA, restartedCaptureA.configuration,
				"restarted server A uses its new endpoint and secret");

		registry.clearOwner(restartedVoicechatA);
		assertEquals(1, restartedCaptureA.closes, "server-stop ownership closes restarted server A once");
		assertEquals(0, captureB.closes, "stopping server A still leaves server B active");
		registry.clearOwner(voicechatB);
		assertEquals(1, captureB.closes, "server B closes its remaining capture once");

		List<RecordingCapture> cleanupCaptures = new ArrayList<>();
		ServerSpeechCaptureRegistry<Object, Object> cleanupRegistry = new ServerSpeechCaptureRegistry<>(configuration -> {
			RecordingCapture capture = new RecordingCapture(configuration);
			cleanupCaptures.add(capture);
			return capture;
		});
		Object cleanupServer = new Object();
		Object cleanupOwner = new Object();
		cleanupRegistry.configure(cleanupServer, cleanupOwner, configurationA);
		cleanupRegistry.accept(cleanupServer, cleanupOwner, null);
		cleanupCaptures.getFirst().closeFailure = new IllegalStateException("stale capture cleanup failed");
		cleanupRegistry.configure(cleanupServer, cleanupOwner, configurationB);
		cleanupRegistry.accept(cleanupServer, cleanupOwner, null);
		assertEquals(1, cleanupCaptures.getFirst().closes,
				"failed displaced capture cleanup is attempted exactly once");
		assertEquals(1, cleanupCaptures.getLast().accepts,
				"failed displaced cleanup cannot block the replacement capture");

		long[] now = { 0L };
		int[] factoryCalls = { 0 };
		List<String> diagnostics = new ArrayList<>();
		List<RecordingCapture> liveFailureCaptures = new ArrayList<>();
		ServerSpeechCaptureRegistry<Object, Object> liveFailureRegistry = new ServerSpeechCaptureRegistry<>(configuration -> {
			factoryCalls[0]++;
			if (factoryCalls[0] == 1) throw new IllegalStateException("speech capture factory failed");
			RecordingCapture capture = new RecordingCapture(configuration);
			liveFailureCaptures.add(capture);
			return capture;
		}, () -> now[0], diagnostics::add);
		liveFailureRegistry.configure(cleanupServer, cleanupOwner, configurationA);
		for (int packet = 0; packet < 20; packet++) {
			liveFailureRegistry.accept(cleanupServer, cleanupOwner, null);
		}
		assertEquals(1, factoryCalls[0], "persistent factory failure is attempted once during cooldown");
		assertEquals(List.of("CAPTURE_FACTORY_FAILED"), diagnostics,
				"persistent factory failure emits one bounded transition diagnostic");

		now[0] = SECONDS.toNanos(60L);
		liveFailureRegistry.accept(cleanupServer, cleanupOwner, null);
		assertEquals(2, factoryCalls[0], "capture retries once after the capped deadline");
		assertEquals(1, liveFailureCaptures.getFirst().accepts,
				"the recovered capture receives the retrying packet");
		assertEquals(List.of("CAPTURE_FACTORY_FAILED", "CAPTURE_RECOVERED"), diagnostics,
				"recovery emits one transition diagnostic");

		RecordingCapture recovered = liveFailureCaptures.getFirst();
		liveFailureRegistry.clear(cleanupServer);
		now[0] = SECONDS.toNanos(120L);
		liveFailureRegistry.accept(cleanupServer, cleanupOwner, null);
		assertEquals(1, recovered.closes, "cleared capture closes once");
		assertEquals(2, factoryCalls[0], "elapsed stale cooldown cannot resurrect a cleared entry");

		int callsBeforeReconfigure = factoryCalls[0];
		liveFailureRegistry.configure(cleanupServer, cleanupOwner, configurationA);
		liveFailureRegistry.accept(cleanupServer, cleanupOwner, null);
		liveFailureRegistry.configure(cleanupServer, cleanupOwner, configurationB);
		liveFailureRegistry.accept(cleanupServer, cleanupOwner, null);
		assertEquals(callsBeforeReconfigure + 2, factoryCalls[0],
				"reconfigure resets recovery state for the exact new generation");
		assertEquals(configurationB, liveFailureCaptures.getLast().configuration,
				"reconfigured recovery cannot reuse the stale endpoint or secret");

		long[] captureNow = { 0L };
		int[] captureFactoryCalls = { 0 };
		List<String> captureDiagnostics = new ArrayList<>();
		List<RecordingCapture> failedCaptures = new ArrayList<>();
		ServerSpeechCaptureRegistry<Object, Object> captureFailureRegistry = new ServerSpeechCaptureRegistry<>(
				configuration -> {
					captureFactoryCalls[0]++;
					RecordingCapture capture = new RecordingCapture(configuration);
					if (captureFactoryCalls[0] == 1) {
						capture.acceptFailure = new IllegalStateException("persistent capture failure");
					}
					failedCaptures.add(capture);
					return capture;
				},
				() -> captureNow[0],
				captureDiagnostics::add
		);
		captureFailureRegistry.configure(cleanupServer, cleanupOwner, configurationA);
		for (int packet = 0; packet < 20; packet++) {
			captureFailureRegistry.accept(cleanupServer, cleanupOwner, null);
		}
		assertEquals(1, captureFactoryCalls[0], "persistent live failure does not reconstruct per packet");
		assertEquals(1, failedCaptures.getFirst().closes, "failed live capture closes once");
		assertEquals(List.of("CAPTURE_FAILED"), captureDiagnostics,
				"persistent live failure emits one transition diagnostic");
		captureNow[0] = SECONDS.toNanos(60L);
		captureFailureRegistry.accept(cleanupServer, cleanupOwner, null);
		assertEquals(2, captureFactoryCalls[0], "live capture retries once after its deadline");
		assertEquals(List.of("CAPTURE_FAILED", "CAPTURE_RECOVERED"), captureDiagnostics,
				"live capture recovery emits one transition diagnostic");

		long[] reconfigureNow = { 0L };
		int[] reconfigureCalls = { 0 };
		List<RecordingCapture> reconfiguredCaptures = new ArrayList<>();
		ServerSpeechCaptureRegistry<Object, Object> reconfigureRegistry = new ServerSpeechCaptureRegistry<>(
				configuration -> {
					reconfigureCalls[0]++;
					if (configuration.equals(configurationA)) {
						throw new IllegalStateException("old generation failed");
					}
					RecordingCapture capture = new RecordingCapture(configuration);
					reconfiguredCaptures.add(capture);
					return capture;
				},
				() -> reconfigureNow[0],
				ignored -> { }
		);
		reconfigureRegistry.configure(cleanupServer, cleanupOwner, configurationA);
		reconfigureRegistry.accept(cleanupServer, cleanupOwner, null);
		reconfigureRegistry.configure(cleanupServer, cleanupOwner, configurationB);
		reconfigureRegistry.accept(cleanupServer, cleanupOwner, null);
		assertEquals(2, reconfigureCalls[0], "reconfigure retries immediately with a fresh generation");
		assertEquals(configurationB, reconfiguredCaptures.getFirst().configuration,
				"fresh generation retains its exact endpoint and secret");
		reconfigureNow[0] = SECONDS.toNanos(60L);
		reconfigureRegistry.accept(cleanupServer, cleanupOwner, null);
		assertEquals(2, reconfigureCalls[0], "old generation deadline cannot resurrect stale capture");
		assertEquals(2, reconfiguredCaptures.getFirst().accepts,
				"packets after stale deadline remain on the current generation");
		return 38;
	}

	private static void acceptConcurrently(
			ServerSpeechCaptureRegistry<Object, Object> registry,
			Object serverA,
			Object voicechatA,
			Object serverB,
			Object voicechatB
	) throws Exception {
		int calls = 16;
		ExecutorService executor = Executors.newFixedThreadPool(calls);
		CountDownLatch ready = new CountDownLatch(calls);
		CountDownLatch start = new CountDownLatch(1);
		List<Future<?>> futures = new ArrayList<>(calls);
		try {
			for (int index = 0; index < calls; index++) {
				Object server = index % 2 == 0 ? serverA : serverB;
				Object voicechat = index % 2 == 0 ? voicechatA : voicechatB;
				futures.add(executor.submit(() -> {
					ready.countDown();
					await(start, "concurrent speech-capture start");
					registry.accept(server, voicechat, null);
				}));
			}
			await(ready, "concurrent speech-capture workers ready");
			start.countDown();
			for (Future<?> future : futures) future.get(5L, TimeUnit.SECONDS);
		} finally {
			start.countDown();
			executor.shutdownNow();
		}
	}

	private static List<RecordingCapture> capturesFor(
			List<RecordingCapture> captures,
			VoiceSubsystemConfiguration configuration
	) {
		return captures.stream().filter(capture -> capture.configuration.equals(configuration)).toList();
	}

	private static void await(CountDownLatch latch, String label) {
		try {
			if (!latch.await(5L, TimeUnit.SECONDS)) throw new AssertionError(label + " timed out");
		} catch (InterruptedException exception) {
			Thread.currentThread().interrupt();
			throw new AssertionError(label + " was interrupted", exception);
		}
	}

	private static void assertEquals(Object expected, Object actual, String label) {
		if (!java.util.Objects.equals(expected, actual)) {
			throw new AssertionError(label + ": expected <" + expected + "> but was <" + actual + ">");
		}
	}

	private static void assertTrue(boolean condition, String label) {
		if (!condition) throw new AssertionError(label);
	}

	private static final class RecordingCapture implements ServerSpeechCaptureRegistry.Capture {
		private final VoiceSubsystemConfiguration configuration;
		private int accepts;
		private int closes;
		private RuntimeException closeFailure;
		private RuntimeException acceptFailure;

		private RecordingCapture(VoiceSubsystemConfiguration configuration) {
			this.configuration = configuration;
		}

		@Override
		public synchronized void accept(MicrophonePacketSnapshot packet) {
			if (acceptFailure != null) throw acceptFailure;
			accepts++;
		}

		@Override
		public synchronized void close() {
			closes++;
			if (closeFailure != null) throw closeFailure;
		}

		@Override
		public void cancel(java.util.UUID playerId) {
		}
	}
}
