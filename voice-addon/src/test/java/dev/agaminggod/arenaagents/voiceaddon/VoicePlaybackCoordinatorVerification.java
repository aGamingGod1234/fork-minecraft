package dev.agaminggod.arenaagents.voiceaddon;

import dev.agaminggod.arenaagents.agent.AgentId;
import dev.agaminggod.arenaagents.server.voice.VoiceReceipt;
import dev.agaminggod.arenaagents.server.voice.VoiceRequest;
import java.net.ConnectException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Queue;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;

final class VoicePlaybackCoordinatorVerification {
	private static final AgentId AGENT = AgentId.parse("00000000-0000-4000-8000-000000000001");
	private static final UUID ENTITY = UUID.fromString("10000000-0000-4000-8000-000000000001");

	private VoicePlaybackCoordinatorVerification() {
	}

	static int verify() {
		int assertions = 0;
		assertions += verifyUnavailableSpeechDegradesWithoutSynthesis();
		assertions += verifyOutputLatencyReportsFirstPlayback();
		assertions += verifySuccessfulPlaybackUsesRegisteredEntityAndRadius();
		assertions += verifyReplacementStopsThePreviousUtterance();
		assertions += verifyStopFailureCannotBlockReplacement();
		assertions += verifyStopReplacementAndCloseCancelPendingSynthesis();
		assertions += verifyFailuresCompleteAndDoNotWedgeLaterSpeech();
		assertions += verifyAsynchronousStreamFailureRequestsTextFallback();
		assertions += verifyUnavailableWorkerReportsSafeDiagnostic();
		assertions += verifyDiagnosticsReportOnlyStateTransitions();
		assertions += verifyStartFailureWinsOverSynchronousStopCallback();
		assertions += verifyUnregisterAndCloseStopPlayback();
		return assertions;
	}

	private static int verifyOutputLatencyReportsFirstPlayback() {
		RecordingSynthesizer synthesizer = new RecordingSynthesizer();
		RecordingTransport transport = new RecordingTransport();
		List<VoicePlaybackCoordinator.OutputLatency> latencies = new ArrayList<>();
		VoicePlaybackCoordinator coordinator = new VoicePlaybackCoordinator(
				synthesizer, Runnable::run, transport, latencies::add
		);
		coordinator.registerAgent(AGENT, ENTITY);
		coordinator.speak(request(30L));
		synthesizer.completeNext(new short[] { 1, 2 });
		assertEquals(1, latencies.size(), "one output latency sample");
		VoicePlaybackCoordinator.OutputLatency latency = latencies.getFirst();
		assertEquals(AGENT, latency.agentId(), "output latency agent identity");
		assertEquals(30L, latency.conversationSequence(), "output latency conversation sequence");
		assertEquals(true, latency.synthesisMilliseconds() >= 0L,
				"output synthesis latency is non-negative");
		assertEquals(true, latency.firstPlaybackMilliseconds() >= latency.synthesisMilliseconds(),
				"first playback latency includes synthesis");
		coordinator.close();
		return 5;
	}

	private static int verifyUnavailableSpeechDegradesWithoutSynthesis() {
		RecordingSynthesizer synthesizer = new RecordingSynthesizer();
		RecordingTransport transport = new RecordingTransport();
		VoicePlaybackCoordinator coordinator = new VoicePlaybackCoordinator(synthesizer, Runnable::run, transport);
		VoiceReceipt receipt = coordinator.speak(request(1L)).toCompletableFuture().join();
		assertEquals(VoiceReceipt.Status.DEGRADED_TO_TEXT, receipt.status(), "unregistered agent degrades");
		assertEquals(0, synthesizer.requests.size(), "unregistered agent skips synthesis");
		coordinator.registerAgent(AGENT, ENTITY);
		transport.available = false;
		receipt = coordinator.speak(request(2L)).toCompletableFuture().join();
		assertEquals(VoiceReceipt.Status.DEGRADED_TO_TEXT, receipt.status(), "unavailable transport degrades");
		assertEquals(0, synthesizer.requests.size(), "unavailable transport skips synthesis");
		coordinator.close();
		return 4;
	}

	private static int verifySuccessfulPlaybackUsesRegisteredEntityAndRadius() {
		RecordingSynthesizer synthesizer = new RecordingSynthesizer();
		RecordingTransport transport = new RecordingTransport();
		VoicePlaybackCoordinator coordinator = new VoicePlaybackCoordinator(synthesizer, Runnable::run, transport);
		coordinator.registerAgent(AGENT, ENTITY);
		VoiceRequest request = request(3L);
		CompletableFuture<VoiceReceipt> receipt = coordinator.speak(request).toCompletableFuture();
		assertEquals(false, receipt.isDone(), "receipt waits for synthesis");
		short[] samples = new short[] { -32_768, -1, 0, 1, 32_767 };
		synthesizer.completeNext(samples);
		assertEquals(1, transport.created.size(), "one playback created");
		CreatedPlayback created = transport.created.getFirst();
		assertEquals(AGENT, created.agentId, "playback agent identity");
		assertEquals(ENTITY, created.entityId, "playback entity identity");
		assertEquals(48, created.radius, "playback radius");
		assertEquals(true, Arrays.equals(samples, created.samples), "playback PCM samples");
		assertEquals(true, created.playback.started, "playback starts");
		assertEquals(false, receipt.isDone(), "receipt waits for playback stop");
		created.playback.finish();
		assertEquals(VoiceReceipt.Status.PLAYED, receipt.join().status(), "completed playback receipt");
		coordinator.close();
		return 9;
	}

	private static int verifyReplacementStopsThePreviousUtterance() {
		RecordingSynthesizer synthesizer = new RecordingSynthesizer();
		RecordingTransport transport = new RecordingTransport();
		VoicePlaybackCoordinator coordinator = new VoicePlaybackCoordinator(synthesizer, Runnable::run, transport);
		coordinator.registerAgent(AGENT, ENTITY);
		CompletableFuture<VoiceReceipt> first = coordinator.speak(request(4L)).toCompletableFuture();
		synthesizer.completeNext(new short[] { 1 });
		RecordingPlayback firstPlayback = transport.created.getFirst().playback;
		CompletableFuture<VoiceReceipt> second = coordinator.speak(request(5L)).toCompletableFuture();
		assertEquals(true, firstPlayback.stopped, "replacement stops active playback");
		VoiceReceipt replaced = first.join();
		assertEquals(VoiceReceipt.Status.CANCELLED, replaced.status(),
				"intentional replacement does not request text fallback for old speech");
		assertEquals(false, replaced.requiresTextFallback(),
				"executor fallback policy ignores intentionally replaced speech");
		synthesizer.completeNext(new short[] { 2 });
		RecordingPlayback secondPlayback = transport.created.get(1).playback;
		secondPlayback.finish();
		assertEquals(VoiceReceipt.Status.PLAYED, second.join().status(), "replacement playback completes");
		coordinator.close();
		return 4;
	}

	private static int verifyStopFailureCannotBlockReplacement() {
		RecordingSynthesizer synthesizer = new RecordingSynthesizer();
		RecordingTransport transport = new RecordingTransport();
		transport.stopFailure = new IllegalStateException("audio player stop failed");
		VoicePlaybackCoordinator coordinator = new VoicePlaybackCoordinator(synthesizer, Runnable::run, transport);
		coordinator.registerAgent(AGENT, ENTITY);
		CompletableFuture<VoiceReceipt> first = coordinator.speak(request(50L)).toCompletableFuture();
		synthesizer.completeNext(new short[] { 1 });
		CompletableFuture<VoiceReceipt> replacement = coordinator.speak(request(51L)).toCompletableFuture();
		assertEquals(VoiceReceipt.Status.CANCELLED, first.join().status(),
				"failed playback cleanup still settles the displaced receipt");
		transport.stopFailure = null;
		synthesizer.completeNext(new short[] { 2 });
		transport.created.getLast().playback.finish();
		assertEquals(VoiceReceipt.Status.PLAYED, replacement.join().status(),
				"replacement proceeds after failed playback cleanup");
		coordinator.close();
		return 2;
	}

	private static int verifyStopReplacementAndCloseCancelPendingSynthesis() {
		RecordingSynthesizer synthesizer = new RecordingSynthesizer();
		RecordingTransport transport = new RecordingTransport();
		VoicePlaybackCoordinator coordinator = new VoicePlaybackCoordinator(synthesizer, Runnable::run, transport);
		coordinator.registerAgent(AGENT, ENTITY);

		CompletableFuture<VoiceReceipt> replacedReceipt = coordinator.speak(request(20L)).toCompletableFuture();
		CompletableFuture<short[]> replacedSynthesis = synthesizer.pending.remove();
		CompletableFuture<VoiceReceipt> replacementReceipt = coordinator.speak(request(21L)).toCompletableFuture();
		assertEquals(true, replacedSynthesis.isCancelled(), "replacement cancels pending synthesis");
		assertEquals(VoiceReceipt.Status.CANCELLED, replacedReceipt.join().status(), "replacement completes replaced receipt");

		CompletableFuture<short[]> replacementSynthesis = synthesizer.pending.remove();
		coordinator.stop(AGENT);
		assertEquals(true, replacementSynthesis.isCancelled(), "stop cancels pending synthesis");
		assertEquals(VoiceReceipt.Status.CANCELLED, replacementReceipt.join().status(), "stop completes pending receipt");

		CompletableFuture<VoiceReceipt> closeReceipt = coordinator.speak(request(22L)).toCompletableFuture();
		CompletableFuture<short[]> closeSynthesis = synthesizer.pending.remove();
		coordinator.close();
		assertEquals(true, closeSynthesis.isCancelled(), "close cancels pending synthesis");
		assertEquals(VoiceReceipt.Status.CANCELLED, closeReceipt.join().status(), "close completes pending receipt");
		return 6;
	}

	private static int verifyUnavailableWorkerReportsSafeDiagnostic() {
		RecordingSynthesizer synthesizer = new RecordingSynthesizer();
		RecordingTransport transport = new RecordingTransport();
		VoicePlaybackCoordinator coordinator = new VoicePlaybackCoordinator(synthesizer, Runnable::run, transport);
		coordinator.registerAgent(AGENT, ENTITY);
		CompletableFuture<VoiceReceipt> receipt = coordinator.speak(request(9L)).toCompletableFuture();
		synthesizer.failNext(new CompletionException(new ConnectException(
				"Connection refused; Authorization=Bearer must-not-reach-logs"
		)));
		assertEquals(
				"[VOICE_WORKER_UNAVAILABLE] Voice worker is not reachable",
				receipt.join().message(),
				"unavailable TTS worker reports a stable safe diagnostic"
		);
		coordinator.close();
		return 1;
	}

	private static int verifyDiagnosticsReportOnlyStateTransitions() {
		RecordingSynthesizer synthesizer = new RecordingSynthesizer();
		RecordingTransport transport = new RecordingTransport();
		transport.available = false;
		List<VoicePlaybackCoordinator.DiagnosticTransition> transitions = new ArrayList<>();
		VoicePlaybackCoordinator coordinator = new VoicePlaybackCoordinator(
				synthesizer, Runnable::run, transport, ignored -> { }, transitions::add
		);
		coordinator.registerAgent(AGENT, ENTITY);
		coordinator.speak(request(40L)).toCompletableFuture().join();
		coordinator.speak(request(41L)).toCompletableFuture().join();
		assertEquals(1, transitions.size(), "repeated identical voice fallback emits one transition");
		assertEquals("VOICE_TRANSPORT_UNAVAILABLE", transitions.getFirst().code(),
				"fallback transition exposes only its bounded code");

		transport.available = true;
		CompletableFuture<VoiceReceipt> recovered = coordinator.speak(request(42L)).toCompletableFuture();
		synthesizer.completeNext(new short[] { 1 });
		transport.created.getFirst().playback.finish();
		assertEquals(VoiceReceipt.Status.PLAYED, recovered.join().status(), "voice recovers after the fallback");
		assertEquals(true, transitions.getLast().recovered(), "successful playback emits one recovery transition");

		transport.available = false;
		coordinator.speak(request(43L)).toCompletableFuture().join();
		assertEquals(3, transitions.size(), "a new fallback after recovery emits one new transition");
		coordinator.close();
		return 5;
	}

	private static int verifyFailuresCompleteAndDoNotWedgeLaterSpeech() {
		RecordingSynthesizer synthesizer = new RecordingSynthesizer();
		RecordingTransport transport = new RecordingTransport();
		VoicePlaybackCoordinator coordinator = new VoicePlaybackCoordinator(synthesizer, Runnable::run, transport);
		coordinator.registerAgent(AGENT, ENTITY);
		CompletableFuture<VoiceReceipt> providerFailure = coordinator.speak(request(6L)).toCompletableFuture();
		synthesizer.failNext(new IllegalStateException("provider offline"));
		assertEquals(VoiceReceipt.Status.FAILED, providerFailure.join().status(), "provider failure receipt");
		assertEquals(true, providerFailure.join().requiresTextFallback(), "provider failure requests text fallback");
		transport.createFailure = new IllegalStateException("channel unavailable");
		CompletableFuture<VoiceReceipt> transportFailure = coordinator.speak(request(7L)).toCompletableFuture();
		synthesizer.completeNext(new short[] { 3 });
		assertEquals(VoiceReceipt.Status.FAILED, transportFailure.join().status(), "transport failure receipt");
		assertEquals(true, transportFailure.join().requiresTextFallback(), "transport failure requests text fallback");
		transport.createFailure = null;
		CompletableFuture<VoiceReceipt> recovered = coordinator.speak(request(8L)).toCompletableFuture();
		synthesizer.completeNext(new short[] { 4 });
		transport.created.getLast().playback.finish();
		assertEquals(VoiceReceipt.Status.PLAYED, recovered.join().status(), "speech recovers after failures");
		coordinator.close();
		return 5;
	}

	private static int verifyStartFailureWinsOverSynchronousStopCallback() {
		RecordingSynthesizer synthesizer = new RecordingSynthesizer();
		RecordingTransport transport = new RecordingTransport();
		transport.startFailure = new IllegalStateException("audio player failed to start");
		transport.stopCompletes = true;
		VoicePlaybackCoordinator coordinator = new VoicePlaybackCoordinator(synthesizer, Runnable::run, transport);
		coordinator.registerAgent(AGENT, ENTITY);
		CompletableFuture<VoiceReceipt> receipt = coordinator.speak(request(9L)).toCompletableFuture();
		synthesizer.completeNext(new short[] { 5 });
		assertEquals(VoiceReceipt.Status.FAILED, receipt.join().status(), "start failure wins over stop callback");
		coordinator.close();
		return 1;
	}

	private static int verifyAsynchronousStreamFailureRequestsTextFallback() {
		RecordingSynthesizer synthesizer = new RecordingSynthesizer();
		RecordingTransport transport = new RecordingTransport();
		VoicePlaybackCoordinator coordinator = new VoicePlaybackCoordinator(synthesizer, Runnable::run, transport);
		coordinator.registerAgent(AGENT, ENTITY);
		CompletableFuture<VoiceReceipt> receipt = coordinator.speak(request(52L)).toCompletableFuture();
		synthesizer.completeNext(new short[] { 1, 2 });
		transport.created.getFirst().playback.fail();
		assertEquals(VoiceReceipt.Status.DEGRADED_TO_TEXT, receipt.join().status(),
				"asynchronous stream failure degrades to text");
		assertEquals(true, receipt.join().requiresTextFallback(),
				"asynchronous stream failure requests text fallback");
		coordinator.close();
		return 2;
	}

	private static int verifyUnregisterAndCloseStopPlayback() {
		RecordingSynthesizer synthesizer = new RecordingSynthesizer();
		RecordingTransport transport = new RecordingTransport();
		VoicePlaybackCoordinator coordinator = new VoicePlaybackCoordinator(synthesizer, Runnable::run, transport);
		coordinator.registerAgent(AGENT, ENTITY);
		CompletableFuture<VoiceReceipt> unregisterReceipt = coordinator.speak(request(9L)).toCompletableFuture();
		synthesizer.completeNext(new short[] { 5 });
		RecordingPlayback unregisterPlayback = transport.created.getFirst().playback;
		coordinator.unregisterAgent(AGENT);
		assertEquals(true, unregisterPlayback.stopped, "unregister stops playback");
		assertEquals(VoiceReceipt.Status.CANCELLED, unregisterReceipt.join().status(), "unregister completes receipt");
		assertEquals(VoiceReceipt.Status.DEGRADED_TO_TEXT,
				coordinator.speak(request(10L)).toCompletableFuture().join().status(), "unregister removes entity");
		coordinator.registerAgent(AGENT, ENTITY);
		CompletableFuture<VoiceReceipt> closeReceipt = coordinator.speak(request(11L)).toCompletableFuture();
		synthesizer.completeNext(new short[] { 6 });
		RecordingPlayback closePlayback = transport.created.getLast().playback;
		coordinator.close();
		assertEquals(true, closePlayback.stopped, "close stops playback");
		assertEquals(VoiceReceipt.Status.CANCELLED, closeReceipt.join().status(), "close completes receipt");
		assertEquals(false, coordinator.available(), "closed coordinator is unavailable");
		return 6;
	}

	private static VoiceRequest request(long sequence) {
		return new VoiceRequest(AGENT, "Hello nearby.", "voice.auto.v1", 48, sequence);
	}

	private static void assertEquals(Object expected, Object actual, String message) {
		if (!java.util.Objects.equals(expected, actual)) {
			throw new AssertionError(message + ": expected=" + expected + ", actual=" + actual);
		}
	}

	private static final class RecordingSynthesizer implements VoicePlaybackCoordinator.Synthesizer {
		private final List<VoiceRequest> requests = new ArrayList<>();
		private final Queue<CompletableFuture<short[]>> pending = new ArrayDeque<>();

		@Override
		public CompletableFuture<short[]> synthesize(VoiceRequest request) {
			requests.add(request);
			CompletableFuture<short[]> result = new CompletableFuture<>();
			pending.add(result);
			return result;
		}

		private void completeNext(short[] samples) {
			pending.remove().complete(samples);
		}

		private void failNext(Throwable failure) {
			pending.remove().completeExceptionally(failure);
		}
	}

	private static final class RecordingTransport implements VoicePlaybackCoordinator.Transport {
		private final List<CreatedPlayback> created = new ArrayList<>();
		private boolean available = true;
		private RuntimeException createFailure;
		private RuntimeException startFailure;
		private RuntimeException stopFailure;
		private boolean stopCompletes;

		@Override
		public boolean available() {
			return available;
		}

		@Override
		public VoicePlaybackCoordinator.Playback create(
				AgentId agentId,
				UUID entityId,
				int radius,
				short[] samples,
				Runnable onStopped,
				Runnable onFailed
		) {
			if (createFailure != null) throw createFailure;
			RecordingPlayback playback = new RecordingPlayback(
					onStopped, onFailed, startFailure, stopFailure, stopCompletes
			);
			created.add(new CreatedPlayback(agentId, entityId, radius, samples.clone(), playback));
			return playback;
		}
	}

	private static final class RecordingPlayback implements VoicePlaybackCoordinator.Playback {
		private final Runnable onStopped;
		private final Runnable onFailed;
		private final RuntimeException startFailure;
		private final RuntimeException stopFailure;
		private final boolean stopCompletes;
		private boolean started;
		private boolean stopped;

		private RecordingPlayback(
				Runnable onStopped,
				Runnable onFailed,
				RuntimeException startFailure,
				RuntimeException stopFailure,
				boolean stopCompletes
		) {
			this.onStopped = onStopped;
			this.onFailed = onFailed;
			this.startFailure = startFailure;
			this.stopFailure = stopFailure;
			this.stopCompletes = stopCompletes;
		}

		@Override
		public void start() {
			started = true;
			if (startFailure != null) throw startFailure;
		}

		@Override
		public void stop() {
			stopped = true;
			if (stopFailure != null) throw stopFailure;
			if (stopCompletes) onStopped.run();
		}

		private void finish() {
			onStopped.run();
		}

		private void fail() {
			onFailed.run();
		}
	}

	private record CreatedPlayback(
			AgentId agentId,
			UUID entityId,
			int radius,
			short[] samples,
			RecordingPlayback playback
	) {
	}
}
