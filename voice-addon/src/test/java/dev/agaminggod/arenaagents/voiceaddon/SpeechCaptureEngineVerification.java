package dev.agaminggod.arenaagents.voiceaddon;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.UUID;
import java.util.concurrent.AbstractExecutorService;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Delayed;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

final class SpeechCaptureEngineVerification {
	private static final UUID PLAYER = UUID.fromString("20000000-0000-4000-8000-000000000001");
	private static final UUID OTHER_PLAYER = UUID.fromString("20000000-0000-4000-8000-000000000002");

	private SpeechCaptureEngineVerification() {
	}

	static int verify() throws Exception {
		int assertions = 0;
		assertions += verifyProductionSpeechEndpointFlushesWithinBudget();
		assertions += verifyAdaptiveEndpointShortensEstablishedUtterances();
		assertions += verifyInputLatencyReportsOneCompletedUtterance();
		assertions += verifySuccessfulInputActivityLifecycle();
		assertions += verifyBlankAndFailedInputActivityLifecycles();
		assertions += verifyDecodedSilenceDoesNotStartSpeech();
		assertions += verifyMidUtteranceQuietFramesAreRetained();
		assertions += verifySparseFrameNoiseDoesNotStartSpeech();
		assertions += verifyOpusSilenceArtifactDoesNotStartSpeech();
		assertions += verifyHungTranscriptionTimesOutAndReleasesPlayer();
		assertions += verifyThrowingInputObserverCannotInterruptDelivery();
		assertions += verifySilenceFlushesOneOrderedUtterance();
		assertions += verifyDeliveryContextIsCapturedAtUtteranceStart();
		assertions += verifyCanceledRunningSilenceTimerCannotFinishNewerAudio();
		assertions += verifyWhisperChangeSplitsAndSequencesUtterances();
		assertions += verifyWhisperChangeFlushesBeforeReplacementFailure();
		assertions += verifyMaximumDurationBoundsDecodedSamples();
		assertions += verifyMalformedPacketDoesNotWedgeLaterSpeech();
		assertions += verifyDecoderBackoffIsPerPlayer();
		assertions += verifyDecoderCloseFailureDoesNotWedgeLaterSpeech();
		assertions += verifyTranscriptsDeliverInUtteranceOrder();
		assertions += verifyFailedEarlierTranscriptReleasesCompletedSuccessor();
		assertions += verifyPendingUtteranceCoalescesToLatest();
		assertions += verifyUnavailableSttRecoversAfterBackoff();
		assertions += verifyCapacitySttRecoversAfterBackoff();
		assertions += verifyRateLimitedSttRecoversAfterBackoff();
		assertions += verifyCapacityBackoffIsolatesOtherPlayers();
		assertions += verifyRateLimitBackoffIsGlobal();
		assertions += verifyConcurrentRateLimitsKeepLongestBackoff();
		assertions += verifyCloseCancelsPendingTranscription();
		assertions += verifyConsentRevocationCancelsOnlyOwnedSpeech();
		assertions += verifyDisconnectGenerationStateIsBounded();
		assertions += verifyCloseDiscardsPartialSpeechAndClosesDecoder();
		assertions += verifyCloseContinuesAfterDecoderCloseFailure();
		return assertions;
	}

	private static int verifySuccessfulInputActivityLifecycle() {
		List<SpeechCaptureEngine.InputActivity> activity = new ArrayList<>();
		SpeechCaptureEngine engine = new SpeechCaptureEngine(
				(playerId, sequence, whispering, samples) -> CompletableFuture.completedFuture(
						new SpeechWorkerClient.Transcript("hello", 0.9D)
				),
				scheduler(), 5_000L, 5_000L, 1, 1, ignored -> { }, activity::add, System::nanoTime
		);
		engine.accept(PLAYER, false, new byte[] { 1 }, RecordingDecoder::new, Runnable::run,
				(playerId, text, whispering) -> { });
		assertEquals(
				List.of(
						SpeechCaptureEngine.InputActivity.Phase.RECEIVED,
						SpeechCaptureEngine.InputActivity.Phase.PROCESSING,
						SpeechCaptureEngine.InputActivity.Phase.RECOGNIZED
				),
				activity.stream().map(SpeechCaptureEngine.InputActivity::phase).toList(),
				"one successful utterance reports truthful input phases once"
		);
		assertEquals(List.of(1L, 1L, 1L),
				activity.stream().map(SpeechCaptureEngine.InputActivity::utteranceSequence).toList(),
				"input phases retain the utterance identity");
		engine.close();
		return 2;
	}

	private static int verifyBlankAndFailedInputActivityLifecycles() {
		List<SpeechCaptureEngine.InputActivity> blankActivity = new ArrayList<>();
		SpeechCaptureEngine blank = new SpeechCaptureEngine(
				(playerId, sequence, whispering, samples) -> CompletableFuture.completedFuture(
						new SpeechWorkerClient.Transcript(" ", 0.1D)
				),
				scheduler(), 5_000L, 5_000L, 1, 1, ignored -> { }, blankActivity::add, System::nanoTime
		);
		blank.accept(PLAYER, false, new byte[] { 1 }, RecordingDecoder::new, Runnable::run,
				(playerId, text, whispering) -> { });
		assertEquals(
				List.of(
						SpeechCaptureEngine.InputActivity.Phase.RECEIVED,
						SpeechCaptureEngine.InputActivity.Phase.PROCESSING,
						SpeechCaptureEngine.InputActivity.Phase.NO_SPEECH
				),
				blankActivity.stream().map(SpeechCaptureEngine.InputActivity::phase).toList(),
				"blank recognition reports no speech instead of a false delivery"
		);
		blank.close();

		List<SpeechCaptureEngine.InputActivity> failedActivity = new ArrayList<>();
		SpeechCaptureEngine failed = new SpeechCaptureEngine(
				(playerId, sequence, whispering, samples) -> CompletableFuture.failedFuture(
						new VoiceWorkerClient.VoiceWorkerException("STT_UNAVAILABLE", "private provider detail")
				),
				scheduler(), 5_000L, 5_000L, 1, 1, ignored -> { }, failedActivity::add, System::nanoTime
		);
		failed.accept(PLAYER, false, new byte[] { 1 }, RecordingDecoder::new, Runnable::run,
				(playerId, text, whispering) -> { });
		assertEquals(
				List.of(
						SpeechCaptureEngine.InputActivity.Phase.RECEIVED,
						SpeechCaptureEngine.InputActivity.Phase.PROCESSING,
						SpeechCaptureEngine.InputActivity.Phase.FAILED
				),
				failedActivity.stream().map(SpeechCaptureEngine.InputActivity::phase).toList(),
				"failed recognition reports one safe terminal input phase"
		);
		failed.close();
		return 2;
	}

	private static int verifyDecodedSilenceDoesNotStartSpeech() {
		RecordingTranscriber transcriber = new RecordingTranscriber();
		List<SpeechCaptureEngine.InputActivity> activity = new ArrayList<>();
		int[] deliveryContexts = { 0 };
		SpeechCaptureEngine engine = new SpeechCaptureEngine(
				transcriber, scheduler(), 5_000L, 5_000L, 1, 960, ignored -> { }, activity::add,
				System::nanoTime
		);
		PcmDecoder encodedSilence = new PcmDecoder(new short[960]);
		engine.accept(PLAYER, false, new byte[] { 1 }, () -> encodedSilence, Runnable::run,
				(playerId, sequence) -> {
					deliveryContexts[0]++;
					return (ignoredPlayer, ignoredText, ignoredWhispering) -> { };
				});
		assertEquals(0, deliveryContexts[0], "PCM silence creates no delivery context");
		assertEquals(0, transcriber.captured.size(), "PCM silence never reaches STT");
		assertEquals(List.of(), activity, "PCM silence reports no speech activity");
		assertEquals(true, encodedSilence.closed, "PCM silence closes its transient decoder");

		PcmDecoder codecSilenceArtifact = new PcmDecoder(repeatedSamples(960, 2));
		engine.accept(PLAYER, false, new byte[] { 2 }, () -> codecSilenceArtifact, Runnable::run,
				(playerId, sequence) -> {
					deliveryContexts[0]++;
					return (ignoredPlayer, ignoredText, ignoredWhispering) -> { };
				});
		assertEquals(0, deliveryContexts[0], "codec silence artifact creates no delivery context");
		assertEquals(0, transcriber.captured.size(), "codec silence artifact never reaches STT");
		assertEquals(true, codecSilenceArtifact.closed, "codec silence artifact closes its transient decoder");

		PcmDecoder speech = new PcmDecoder(repeatedSamples(960, 1_000));
		engine.accept(PLAYER, false, new byte[] { 3 }, () -> speech, Runnable::run,
				(playerId, sequence) -> {
					deliveryContexts[0]++;
					return (ignoredPlayer, ignoredText, ignoredWhispering) -> { };
				});
		assertEquals(1, deliveryContexts[0], "speech creates exactly one delivery context after silence");
		assertEquals(1, transcriber.captured.size(), "speech after silence reaches STT once");
		assertEquals(1L, transcriber.captured.getFirst().sequence,
				"silence does not consume an utterance sequence");
		assertEquals(List.of(
				SpeechCaptureEngine.InputActivity.Phase.RECEIVED,
				SpeechCaptureEngine.InputActivity.Phase.PROCESSING,
				SpeechCaptureEngine.InputActivity.Phase.RECOGNIZED
		), activity.stream().map(SpeechCaptureEngine.InputActivity::phase).toList(),
				"speech after silence starts a truthful lifecycle");
		assertEquals(true, speech.closed, "speech decoder closes after reaching its sample cap");
		engine.close();
		return 10;
	}

	private static int verifyMidUtteranceQuietFramesAreRetained() {
		RecordingTranscriber transcriber = new RecordingTranscriber();
		ManualScheduledExecutor scheduler = new ManualScheduledExecutor();
		short[] speech = repeatedSamples(960, 1_000);
		short[] quiet = repeatedSamples(960, 2);
		SequenceDecoder decoder = new SequenceDecoder(speech, quiet);
		SpeechCaptureEngine engine = new SpeechCaptureEngine(
				transcriber, scheduler, 20L, 20L, 1, 4_096, ignored -> { }
		);
		engine.accept(PLAYER, false, new byte[] { 1 }, () -> decoder, Runnable::run,
				(playerId, text, whispering) -> { });
		engine.accept(PLAYER, false, new byte[] { 2 }, () -> {
			throw new AssertionError("mid-utterance packets must keep the started decoder");
		}, Runnable::run, (playerId, text, whispering) -> { });
		assertEquals(0, transcriber.captured.size(), "quiet frames keep the utterance open until endpointing");
		assertEquals(true, scheduler.tasks.get(0).isCancelled(), "a later quiet frame refreshes endpointing");
		scheduler.runEvenIfCancelled(1);
		assertEquals(1, transcriber.captured.size(), "endpointing transcribes the complete utterance once");
		short[] captured = transcriber.captured.getFirst().samples;
		assertEquals(1_920, captured.length, "mid-utterance quiet frames remain in the captured PCM");
		assertEquals(true, Arrays.equals(speech, Arrays.copyOfRange(captured, 0, 960)),
				"the utterance still starts with the speech frame");
		assertEquals(true, Arrays.equals(quiet, Arrays.copyOfRange(captured, 960, 1_920)),
				"internal quiet frames are not dropped before STT");
		engine.close();
		return 6;
	}

	private static int verifyHungTranscriptionTimesOutAndReleasesPlayer() {
		ManualScheduledExecutor scheduler = new ManualScheduledExecutor();
		List<NonCancellableFuture<SpeechWorkerClient.Transcript>> requests = new ArrayList<>();
		List<SpeechCaptureEngine.InputActivity> activity = new ArrayList<>();
		SpeechCaptureEngine engine = new SpeechCaptureEngine(
				(playerId, sequence, whispering, samples) -> {
					NonCancellableFuture<SpeechWorkerClient.Transcript> request = new NonCancellableFuture<>();
					requests.add(request);
					return request;
				},
				scheduler, 5_000L, 5_000L, 1, 1, ignored -> { }, activity::add, System::nanoTime, 10L
		);
		engine.accept(PLAYER, false, new byte[] { 1 }, RecordingDecoder::new, Runnable::run,
				(playerId, text, whispering) -> { });
		scheduler.runEvenIfCancelled(0);
		assertEquals(1, requests.size(), "one speech request is submitted");
		engine.accept(PLAYER, true, new byte[] { 2 }, RecordingDecoder::new, Runnable::run,
				(playerId, text, whispering) -> { });
		assertEquals(1, requests.size(), "a successor waits behind the active speech request");

		/* Task 1 is the deadline scheduled by the first completed utterance. */
		scheduler.runEvenIfCancelled(1);
		assertEquals(true, requests.getFirst().cancellationAttempted,
				"the transcription deadline cancels the underlying request");
		assertEquals(2, requests.size(), "the timeout releases the player for its pending successor");
		assertEquals(List.of(
				SpeechCaptureEngine.InputActivity.Phase.RECEIVED,
				SpeechCaptureEngine.InputActivity.Phase.PROCESSING,
				SpeechCaptureEngine.InputActivity.Phase.RECEIVED,
				SpeechCaptureEngine.InputActivity.Phase.FAILED,
				SpeechCaptureEngine.InputActivity.Phase.PROCESSING
		), activity.stream().map(SpeechCaptureEngine.InputActivity::phase).toList(),
				"a hung request reaches a bounded terminal phase and unblocks later speech");
		engine.close();
		return 5;
	}

	private static int verifySparseFrameNoiseDoesNotStartSpeech() {
		RecordingTranscriber transcriber = new RecordingTranscriber();
		List<SpeechCaptureEngine.InputActivity> activity = new ArrayList<>();
		short[] sparseSamples = new short[960];
		sparseSamples[480] = Short.MAX_VALUE;
		PcmDecoder sparseNoise = new PcmDecoder(sparseSamples);
		SpeechCaptureEngine engine = new SpeechCaptureEngine(
				transcriber, scheduler(), 5_000L, 5_000L, 1, 960, ignored -> { }, activity::add,
				System::nanoTime
		);
		int[] deliveryContexts = { 0 };
		engine.accept(PLAYER, false, new byte[] { 1 }, () -> sparseNoise, Runnable::run,
				(playerId, sequence) -> {
					deliveryContexts[0]++;
					return (ignoredPlayer, ignoredText, ignoredWhispering) -> { };
				});
		assertEquals(false, DecodedPcmSpeechDetector.hasSpeech(sparseSamples),
				"a single full-scale sample is not a speech frame");
		assertEquals(true, DecodedPcmSpeechDetector.hasSpeech(repeatedSamples(960, 110)),
				"a sustained quiet frame remains eligible for speech recognition");
		assertEquals(0, deliveryContexts[0], "sparse frame noise creates no delivery context");
		assertEquals(0, transcriber.captured.size(), "sparse frame noise never reaches STT");
		assertEquals(List.of(), activity, "sparse frame noise reports no speech activity");
		assertEquals(true, sparseNoise.closed, "sparse frame noise closes its transient decoder");
		engine.close();
		return 4;
	}

	private static int verifyOpusSilenceArtifactDoesNotStartSpeech() {
		RecordingTranscriber transcriber = new RecordingTranscriber();
		List<SpeechCaptureEngine.InputActivity> activity = new ArrayList<>();
		/* Actual Opus encoding of a 960-sample zero frame decodes to this tiny codec artifact. */
		short[] decodedSilence = repeatedSamples(960, 2);
		PcmDecoder frameDecoder = new PcmDecoder(decodedSilence);
		SpeechCaptureEngine engine = new SpeechCaptureEngine(
				transcriber, scheduler(), 5_000L, 5_000L, 1, decodedSilence.length, ignored -> { }, activity::add,
				System::nanoTime
		);
		engine.accept(PLAYER, false, new byte[] { 1 }, () -> frameDecoder, Runnable::run,
				(playerId, sequence) -> (ignoredPlayer, ignoredText, ignoredWhispering) -> { });
		assertEquals(false, DecodedPcmSpeechDetector.hasSpeech(decodedSilence),
				"actual Opus-encoded silence is rejected at the decoded PCM boundary");
		assertEquals(0, transcriber.captured.size(), "actual Opus silence never reaches STT");
		assertEquals(List.of(), activity, "actual Opus silence reports no speech activity");
		assertEquals(true, frameDecoder.closed, "actual Opus silence closes its transient decoder");
		engine.close();
		return 4;
	}

	private static int verifyThrowingInputObserverCannotInterruptDelivery() {
		int[] deliveries = { 0 };
		SpeechCaptureEngine engine = new SpeechCaptureEngine(
				(playerId, sequence, whispering, samples) -> CompletableFuture.completedFuture(
						new SpeechWorkerClient.Transcript("still delivered", 0.9D)
				),
				scheduler(), 5_000L, 5_000L, 1, 1, ignored -> { }, ignored -> {
					throw new IllegalStateException("observer failure");
				}, System::nanoTime
		);
		engine.accept(PLAYER, false, new byte[] { 1 }, RecordingDecoder::new, Runnable::run,
				(playerId, text, whispering) -> deliveries[0]++);
		assertEquals(1, deliveries[0], "operator diagnostics cannot interrupt transcript delivery");
		engine.close();
		return 1;
	}

	private static int verifyWhisperChangeFlushesBeforeReplacementFailure() {
		RecordingTranscriber transcriber = new RecordingTranscriber();
		SpeechCaptureEngine engine = new SpeechCaptureEngine(transcriber, scheduler(), 5_000L, 32);
		List<Delivered> delivered = new ArrayList<>();
		SpeechCaptureEngine.TranscriptDelivery delivery = (playerId, text, whispering) ->
				delivered.add(new Delivered(playerId, text, whispering));
		engine.accept(PLAYER, false, new byte[] { 4 }, RecordingDecoder::new, Runnable::run, delivery);
		engine.accept(PLAYER, true, new byte[] { 5 }, () -> {
			throw new IllegalStateException("decoder unavailable");
		}, Runnable::run, delivery);

		assertEquals(1, transcriber.captured.size(),
				"a replacement decoder failure cannot discard the completed prior utterance");
		assertEquals(List.of(new Delivered(PLAYER, "heard 1", false)), delivered,
				"the prior utterance is delivered before replacement capture retries");
		engine.close();
		return 2;
	}

	private static int verifyDeliveryContextIsCapturedAtUtteranceStart() throws Exception {
		RecordingTranscriber transcriber = new RecordingTranscriber();
		SpeechCaptureEngine engine = new SpeechCaptureEngine(transcriber, scheduler(), 5_000L, 2);
		List<String> listeners = new ArrayList<>(List.of("near-at-speech-time"));
		List<List<String>> deliveredTo = new ArrayList<>();
		int[] contextCaptures = { 0 };
		SpeechCaptureEngine.TranscriptDeliveryFactory deliveryFactory = (capturedPlayerId, utteranceSequence) -> {
			contextCaptures[0]++;
			List<String> capturedListeners = List.copyOf(listeners);
			return (playerId, transcript, whispering) -> deliveredTo.add(capturedListeners);
		};

		engine.accept(PLAYER, false, new byte[] { 1 }, RecordingDecoder::new, Runnable::run, deliveryFactory);
		listeners.clear();
		listeners.add("near-after-transcription");
		engine.accept(PLAYER, false, new byte[] { 2 }, RecordingDecoder::new, Runnable::run, deliveryFactory);

		assertEquals(1, contextCaptures[0], "delivery context is captured once for the utterance");
		assertEquals(List.of(List.of("near-at-speech-time")), deliveredTo,
				"listener changes after speech starts cannot reroute its transcript");
		assertEquals(1, transcriber.captured.size(), "the context spans the complete multi-packet utterance");
		engine.close();
		return 3;
	}

	private static int verifyCanceledRunningSilenceTimerCannotFinishNewerAudio() {
		RecordingTranscriber transcriber = new RecordingTranscriber();
		ManualScheduledExecutor scheduler = new ManualScheduledExecutor();
		SpeechCaptureEngine engine = new SpeechCaptureEngine(transcriber, scheduler, 20L, 32);
		RecordingDecoder first = new RecordingDecoder();
		RecordingDecoder afterClose = new RecordingDecoder();
		Queue<RecordingDecoder> decoders = new ArrayDeque<>(List.of(first, afterClose));

		engine.accept(PLAYER, false, new byte[] { 1 }, decoders::remove, Runnable::run,
				(playerId, text, whispering) -> { });
		engine.accept(PLAYER, false, new byte[] { 2 }, decoders::remove, Runnable::run,
				(playerId, text, whispering) -> { });
		assertEquals(true, scheduler.tasks.get(0).isCancelled(), "new packet cancels the prior silence timer");
		scheduler.runEvenIfCancelled(0);
		assertEquals(0, transcriber.captured.size(), "canceled running timer cannot finish newer audio");
		assertEquals(false, first.closed, "canceled running timer cannot close the current decoder");

		scheduler.runEvenIfCancelled(1);
		assertEquals(1, transcriber.captured.size(), "current silence timer finishes exactly once");
		assertEquals(true, Arrays.equals(new short[] { 1, 2 }, transcriber.captured.getFirst().samples),
				"current timer preserves every packet in its epoch");

		engine.accept(PLAYER, false, new byte[] { 3 }, decoders::remove, Runnable::run,
				(playerId, text, whispering) -> { });
		engine.close();
		scheduler.runEvenIfCancelled(2);
		assertEquals(1, transcriber.captured.size(), "closed generation fences a captured timer");
		return 6;
	}

	private static int verifyInputLatencyReportsOneCompletedUtterance() {
		RecordingTranscriber transcriber = new RecordingTranscriber();
		List<SpeechCaptureEngine.InputLatency> latencies = new ArrayList<>();
		SpeechCaptureEngine engine = new SpeechCaptureEngine(
				transcriber, scheduler(), 5_000L, 1, latencies::add
		);
		engine.accept(
				PLAYER, true, new byte[] { 7 }, RecordingDecoder::new, Runnable::run,
				(playerId, text, whispering) -> { }
		);
		assertEquals(1, latencies.size(), "one input latency sample");
		SpeechCaptureEngine.InputLatency latency = latencies.getFirst();
		assertEquals(PLAYER, latency.playerId(), "input latency player identity");
		assertEquals(1L, latency.utteranceSequence(), "input latency utterance sequence");
		assertEquals(true, latency.endpointMilliseconds() >= 0L, "input endpoint latency is non-negative");
		assertEquals(true, latency.transcriptionMilliseconds() >= 0L,
				"input transcription latency is non-negative");
		assertEquals(true, latency.totalMilliseconds() >= latency.transcriptionMilliseconds(),
				"input total latency includes transcription");
		engine.close();
		return 6;
	}

	private static int verifyProductionSpeechEndpointFlushesWithinBudget() throws Exception {
		var field = HumanSpeechCapture.class.getDeclaredField("SILENCE_MILLISECONDS");
		field.setAccessible(true);
		long productionSilenceMilliseconds = field.getLong(null);
		RecordingTranscriber transcriber = new RecordingTranscriber();
		SpeechCaptureEngine engine = new SpeechCaptureEngine(
				transcriber, scheduler(), productionSilenceMilliseconds, 32
		);
		CountDownLatch latch = new CountDownLatch(1);
		engine.accept(
				PLAYER, false, new byte[] { 1 }, RecordingDecoder::new, Runnable::run,
				(playerId, text, whispering) -> latch.countDown()
		);
		assertEquals(true, latch.await(550, TimeUnit.MILLISECONDS),
				"production speech endpoint flushes within the conversational latency budget");
		engine.close();
		return 1;
	}

	private static int verifyAdaptiveEndpointShortensEstablishedUtterances() {
		RecordingTranscriber transcriber = new RecordingTranscriber();
		ManualScheduledExecutor scheduler = new ManualScheduledExecutor();
		SpeechCaptureEngine engine = new SpeechCaptureEngine(
				transcriber, scheduler, 100L, 300L, 4, 32, ignored -> { }
		);
		engine.accept(PLAYER, false, new byte[] { 1, 2 }, RecordingDecoder::new, Runnable::run,
				(playerId, text, whispering) -> { });
		engine.accept(PLAYER, false, new byte[] { 3, 4 }, RecordingDecoder::new, Runnable::run,
				(playerId, text, whispering) -> { });
		assertEquals(300L, scheduler.tasks.get(0).delayMilliseconds,
				"short speech keeps the pause-preserving endpoint");
		assertEquals(100L, scheduler.tasks.get(1).delayMilliseconds,
				"established speech uses the low-latency endpoint");
		assertEquals(true, scheduler.tasks.get(0).isCancelled(),
				"the adaptive timer still fences the older endpoint epoch");
		engine.close();
		return 3;
	}

	private static int verifySilenceFlushesOneOrderedUtterance() throws Exception {
		RecordingTranscriber transcriber = new RecordingTranscriber();
		ManualScheduledExecutor scheduler = new ManualScheduledExecutor();
		SpeechCaptureEngine engine = new SpeechCaptureEngine(transcriber, scheduler, 20L, 32);
		List<Delivered> delivered = new ArrayList<>();
		CountDownLatch latch = new CountDownLatch(1);
		SpeechCaptureEngine.TranscriptDelivery delivery = (playerId, text, whispering) -> {
			delivered.add(new Delivered(playerId, text, whispering));
			latch.countDown();
		};
		Queue<RecordingDecoder> decoders = new ArrayDeque<>();
		engine.accept(PLAYER, false, new byte[] { 1, 2 }, () -> decoder(decoders), Runnable::run, delivery);
		engine.accept(PLAYER, false, new byte[] { 3 }, () -> decoder(decoders), Runnable::run, delivery);
		scheduler.runEvenIfCancelled(1);
		assertEquals(true, latch.await(2, TimeUnit.SECONDS), "silence flush completed");
		Captured captured = transcriber.captured.getFirst();
		assertEquals(1L, captured.sequence, "first utterance sequence");
		assertEquals(false, captured.whispering, "normal speech state");
		assertEquals(true, Arrays.equals(new short[] { 1, 2, 3 }, captured.samples), "ordered decoded samples");
		assertEquals(List.of(new Delivered(PLAYER, "heard 3", false)), delivered, "transcript delivered once");
		assertEquals(true, decoders.remove().closed, "decoder closes after silence");
		engine.close();
		return 6;
	}

	private static int verifyWhisperChangeSplitsAndSequencesUtterances() throws Exception {
		RecordingTranscriber transcriber = new RecordingTranscriber();
		SpeechCaptureEngine engine = new SpeechCaptureEngine(transcriber, scheduler(), 20L, 32);
		CountDownLatch latch = new CountDownLatch(2);
		List<Delivered> delivered = new ArrayList<>();
		SpeechCaptureEngine.TranscriptDelivery delivery = (playerId, text, whispering) -> {
			delivered.add(new Delivered(playerId, text, whispering));
			latch.countDown();
		};
		Queue<RecordingDecoder> decoders = new ArrayDeque<>();
		engine.accept(PLAYER, false, new byte[] { 4 }, () -> decoder(decoders), Runnable::run, delivery);
		engine.accept(PLAYER, true, new byte[] { 5, 6 }, () -> decoder(decoders), Runnable::run, delivery);
		assertEquals(true, latch.await(2, TimeUnit.SECONDS), "whisper split delivered both utterances");
		assertEquals(2, transcriber.captured.size(), "whisper split transcription count");
		assertEquals(1L, transcriber.captured.get(0).sequence, "normal utterance sequence");
		assertEquals(2L, transcriber.captured.get(1).sequence, "whisper utterance sequence");
		assertEquals(false, transcriber.captured.get(0).whispering, "first utterance normal");
		assertEquals(true, transcriber.captured.get(1).whispering, "second utterance whispering");
		assertEquals(true, decoders.remove().closed, "normal decoder closes on whisper change");
		assertEquals(true, decoders.remove().closed, "whisper decoder closes on silence");
		engine.close();
		return 8;
	}

	private static int verifyMaximumDurationBoundsDecodedSamples() throws Exception {
		RecordingTranscriber transcriber = new RecordingTranscriber();
		SpeechCaptureEngine engine = new SpeechCaptureEngine(transcriber, scheduler(), 5_000L, 3);
		CountDownLatch latch = new CountDownLatch(1);
		Queue<RecordingDecoder> decoders = new ArrayDeque<>();
		engine.accept(
				PLAYER,
				false,
				new byte[] { 7, 8, 9, 10 },
				() -> decoder(decoders),
				Runnable::run,
				(playerId, text, whispering) -> latch.countDown()
		);
		assertEquals(true, latch.await(2, TimeUnit.SECONDS), "sample cap flush completed");
		assertEquals(true, Arrays.equals(new short[] { 7, 8, 9 }, transcriber.captured.getFirst().samples),
				"sample cap truncates decoded frame");
		assertEquals(true, decoders.remove().closed, "decoder closes at sample cap");
		engine.close();
		return 3;
	}

	private static int verifyMalformedPacketDoesNotWedgeLaterSpeech() throws Exception {
		RecordingTranscriber transcriber = new RecordingTranscriber();
		long[] now = { 0L };
		SpeechCaptureEngine engine = new SpeechCaptureEngine(
				transcriber, scheduler(), 20L, 32, ignored -> { }, () -> now[0]
		);
		RecordingDecoder broken = new RecordingDecoder();
		broken.decodeFailure = new IllegalArgumentException("bad Opus frame");
		RecordingDecoder recovered = new RecordingDecoder();
		Queue<RecordingDecoder> decoders = new ArrayDeque<>(List.of(broken, recovered));
		int[] decoderCreations = { 0 };
		SpeechCaptureEngine.DecoderFactory decoderFactory = () -> {
			decoderCreations[0]++;
			return decoders.remove();
		};
		engine.accept(
				PLAYER, false, new byte[] { 1 }, decoderFactory, Runnable::run,
				(playerId, text, whispering) -> { }
		);
		CountDownLatch latch = new CountDownLatch(1);
		engine.accept(
				PLAYER, false, new byte[] { 2 }, decoderFactory, Runnable::run,
				(playerId, text, whispering) -> latch.countDown()
		);
		assertEquals(1, decoderCreations[0], "decoder failure enters a packet-safe cooldown");
		now[0] = TimeUnit.SECONDS.toNanos(60L);
		engine.accept(
				PLAYER, false, new byte[] { 12 }, decoderFactory, Runnable::run,
				(playerId, text, whispering) -> latch.countDown()
		);
		assertEquals(true, broken.closed, "malformed packet closes broken decoder");
		assertEquals(true, latch.await(2, TimeUnit.SECONDS), "speech recovers after malformed packet");
		assertEquals(2, decoderCreations[0], "decoder is reconstructed once after the retry deadline");
		assertEquals(true, Arrays.equals(new short[] { 12 }, transcriber.captured.getFirst().samples),
				"recovered utterance excludes malformed packet");
		engine.close();
		return 5;
	}

	private static int verifyDecoderCloseFailureDoesNotWedgeLaterSpeech() throws Exception {
		RecordingTranscriber transcriber = new RecordingTranscriber();
		SpeechCaptureEngine engine = new SpeechCaptureEngine(transcriber, scheduler(), 20L, 32);
		RecordingDecoder broken = new RecordingDecoder();
		broken.closeFailure = new IllegalStateException("decoder close failed");
		broken.closeAttempted = new CountDownLatch(1);
		RecordingDecoder recovered = new RecordingDecoder();
		Queue<RecordingDecoder> decoders = new ArrayDeque<>(List.of(broken, recovered));
		List<Delivered> delivered = new ArrayList<>();
		CountDownLatch deliveredLatch = new CountDownLatch(1);

		engine.accept(
				PLAYER, false, new byte[] { 1 }, decoders::remove, Runnable::run,
				(playerId, text, whispering) -> delivered.add(new Delivered(playerId, text, whispering))
		);
		assertEquals(true, broken.closeAttempted.await(2, TimeUnit.SECONDS),
				"silence flush attempts the failing decoder close");
		engine.accept(
				PLAYER, true, new byte[] { 2 }, decoders::remove, Runnable::run,
				(playerId, text, whispering) -> {
					delivered.add(new Delivered(playerId, text, whispering));
					deliveredLatch.countDown();
				}
		);

		assertEquals(true, broken.closed, "failed decoder close is attempted once");
		assertEquals(true, deliveredLatch.await(2, TimeUnit.SECONDS),
				"later transcript is not wedged behind the failed silence flush");
		assertEquals(1, transcriber.captured.size(), "failed utterance is skipped before transcription");
		assertEquals(2L, transcriber.captured.getFirst().sequence,
				"later utterance keeps its monotonic sequence");
		assertEquals(List.of(new Delivered(PLAYER, "heard 1", true)), delivered,
				"later transcript delivers after the skipped close failure");
		engine.close();
		return 6;
	}

	private static int verifyDecoderBackoffIsPerPlayer() throws Exception {
		UUID otherPlayer = UUID.fromString("20000000-0000-4000-8000-000000000002");
		RecordingTranscriber transcriber = new RecordingTranscriber();
		long[] now = { 0L };
		SpeechCaptureEngine engine = new SpeechCaptureEngine(
				transcriber, scheduler(), 20L, 1, ignored -> { }, () -> now[0]
		);
		RecordingDecoder broken = new RecordingDecoder();
		broken.decodeFailure = new IllegalArgumentException("bad Opus frame");
		RecordingDecoder other = new RecordingDecoder();
		RecordingDecoder recovered = new RecordingDecoder();
		java.util.Queue<RecordingDecoder> decoders = new java.util.ArrayDeque<>(
				List.of(broken, other, recovered)
		);
		int[] decoderCreations = { 0 };
		SpeechCaptureEngine.DecoderFactory decoderFactory = () -> {
			decoderCreations[0]++;
			return decoders.remove();
		};

		engine.accept(PLAYER, false, new byte[] { 1 }, decoderFactory, Runnable::run,
				(playerId, text, whispering) -> { });
		engine.accept(PLAYER, false, new byte[] { 2 }, decoderFactory, Runnable::run,
				(playerId, text, whispering) -> { });
		assertEquals(1, decoderCreations[0], "one player's malformed packet enters only that player's cooldown");

		engine.accept(otherPlayer, false, new byte[] { 3 }, decoderFactory, Runnable::run,
				(playerId, text, whispering) -> { });
		assertEquals(2, decoderCreations[0], "another player's packet is not blocked by the first player's cooldown");

		now[0] = TimeUnit.SECONDS.toNanos(1L);
		engine.accept(PLAYER, false, new byte[] { 4 }, decoderFactory, Runnable::run,
				(playerId, text, whispering) -> { });
		assertEquals(3, decoderCreations[0], "the malformed player's decoder retries after its own deadline");
		assertEquals(true, broken.closed, "the malformed player's decoder is closed");
		assertEquals(true, other.closed, "the other player's decoder remains independently active");
		engine.close();
		return 5;
	}

	private static int verifyTranscriptsDeliverInUtteranceOrder() {
		ControlledTranscriber transcriber = new ControlledTranscriber();
		SpeechCaptureEngine engine = new SpeechCaptureEngine(transcriber, scheduler(), 5_000L, 1);
		List<Delivered> delivered = new ArrayList<>();
		SpeechCaptureEngine.TranscriptDelivery delivery = (playerId, text, whispering) ->
				delivered.add(new Delivered(playerId, text, whispering));
		engine.accept(PLAYER, false, new byte[] { 1 }, RecordingDecoder::new, Runnable::run, delivery);
		engine.accept(PLAYER, true, new byte[] { 2 }, RecordingDecoder::new, Runnable::run, delivery);

		assertEquals(false, transcriber.pending.containsKey(2L), "successor waits behind active STT");
		transcriber.complete(1L, "first");
		assertEquals(true, transcriber.pending.containsKey(2L), "successor starts after active STT completes");
		transcriber.complete(2L, "second");
		assertEquals(
				List.of(new Delivered(PLAYER, "first", false), new Delivered(PLAYER, "second", true)),
				delivered,
				"bounded successor delivers in utterance order"
		);
		engine.close();
		return 3;
	}

	private static int verifyFailedEarlierTranscriptReleasesCompletedSuccessor() {
		ControlledTranscriber transcriber = new ControlledTranscriber();
		SpeechCaptureEngine engine = new SpeechCaptureEngine(transcriber, scheduler(), 5_000L, 1);
		List<Delivered> delivered = new ArrayList<>();
		SpeechCaptureEngine.TranscriptDelivery delivery = (playerId, text, whispering) ->
				delivered.add(new Delivered(playerId, text, whispering));
		engine.accept(PLAYER, false, new byte[] { 1 }, RecordingDecoder::new, Runnable::run, delivery);
		engine.accept(PLAYER, true, new byte[] { 2 }, RecordingDecoder::new, Runnable::run, delivery);

		assertEquals(1, transcriber.pending.size(), "per-player STT admission remains bounded to one request");
		transcriber.fail(1L, new VoiceWorkerClient.VoiceWorkerException("STT_PROVIDER_ERROR", "temporary failure"));
		assertEquals(true, transcriber.pending.containsKey(2L), "successor starts after an ordinary STT failure");
		transcriber.complete(2L, "second");
		assertEquals(List.of(new Delivered(PLAYER, "second", true)), delivered,
				"failed earlier STT advances ordering before the successor delivers");
		engine.close();
		return 3;
	}

	private static int verifyPendingUtteranceCoalescesToLatest() {
		ControlledTranscriber transcriber = new ControlledTranscriber();
		SpeechCaptureEngine engine = new SpeechCaptureEngine(transcriber, scheduler(), 5_000L, 1);
		List<Delivered> delivered = new ArrayList<>();
		SpeechCaptureEngine.TranscriptDelivery delivery = (playerId, text, whispering) ->
				delivered.add(new Delivered(playerId, text, whispering));
		engine.accept(PLAYER, false, new byte[] { 1 }, RecordingDecoder::new, Runnable::run, delivery);
		engine.accept(PLAYER, true, new byte[] { 2 }, RecordingDecoder::new, Runnable::run, delivery);
		engine.accept(PLAYER, false, new byte[] { 3 }, RecordingDecoder::new, Runnable::run, delivery);

		assertEquals(1, transcriber.pending.size(), "only the active STT reaches the worker");
		transcriber.complete(1L, "first");
		assertEquals(false, transcriber.pending.containsKey(2L), "superseded pending speech never reaches STT");
		assertEquals(true, transcriber.pending.containsKey(3L), "latest bounded pending speech reaches STT");
		transcriber.complete(3L, "third");
		assertEquals(
				List.of(new Delivered(PLAYER, "first", false), new Delivered(PLAYER, "third", false)),
				delivered,
				"coalescing retains one latest utterance without blocking transcript order"
		);
		engine.close();
		return 4;
	}

	private static int verifyUnavailableSttRecoversAfterBackoff() {
		return verifySttRecoversAfterBackoff(
				"STT_UNAVAILABLE", "unavailable", TimeUnit.SECONDS.toNanos(5L)
		);
	}

	private static int verifyCapacitySttRecoversAfterBackoff() {
		return verifySttRecoversAfterBackoff(
				"STT_CAPACITY", "capacity-limited", TimeUnit.MILLISECONDS.toNanos(250L)
		);
	}

	private static int verifyRateLimitedSttRecoversAfterBackoff() {
		return verifySttRecoversAfterBackoff(
				"STT_RATE_LIMITED", "rate-limited", TimeUnit.SECONDS.toNanos(1L)
		);
	}

	private static int verifyCapacityBackoffIsolatesOtherPlayers() {
		return verifyPlayerBackoffIsolatesOtherPlayers("STT_CAPACITY", "capacity");
	}

	private static int verifyRateLimitBackoffIsGlobal() {
		Map<UUID, CompletableFuture<SpeechWorkerClient.Transcript>> pending = new java.util.LinkedHashMap<>();
		long[] now = { 0L };
		SpeechCaptureEngine engine = new SpeechCaptureEngine((playerId, sequence, whispering, samples) -> {
			CompletableFuture<SpeechWorkerClient.Transcript> future = new CompletableFuture<>();
			pending.put(playerId, future);
			return future;
		}, scheduler(), 5_000L, 2, ignored -> { }, () -> now[0]);
		RecordingDecoder otherDecoder = new RecordingDecoder();
		List<Delivered> delivered = new ArrayList<>();
		engine.accept(OTHER_PLAYER, false, new byte[] { 1 }, () -> otherDecoder, Runnable::run,
				(playerId, text, whispering) -> delivered.add(new Delivered(playerId, text, whispering)));
		engine.accept(PLAYER, false, new byte[] { 1, 2 }, RecordingDecoder::new, Runnable::run,
				(playerId, text, whispering) -> delivered.add(new Delivered(playerId, text, whispering)));
		pending.get(PLAYER).completeExceptionally(new VoiceWorkerClient.VoiceWorkerException(
				"STT_RATE_LIMITED", "Speech provider rate limit", TimeUnit.SECONDS.toNanos(12L)));

		assertEquals(true, otherDecoder.closed,
				"a provider-global rate limit discards speech captured against the throttled API key");
		engine.accept(OTHER_PLAYER, false, new byte[] { 2 }, () -> {
			throw new AssertionError("provider Retry-After must block every player before decoding");
		}, Runnable::run, (playerId, text, whispering) -> { });
		now[0] = TimeUnit.SECONDS.toNanos(12L);
		engine.accept(OTHER_PLAYER, false, new byte[] { 3, 4 }, RecordingDecoder::new, Runnable::run,
				(playerId, text, whispering) -> delivered.add(new Delivered(playerId, text, whispering)));
		pending.get(OTHER_PLAYER).complete(new SpeechWorkerClient.Transcript("other player heard", 0.9));
		assertEquals(List.of(new Delivered(OTHER_PLAYER, "other player heard", false)), delivered,
				"all players recover when the provider Retry-After deadline expires");
		assertEquals(2, pending.size(), "the global backoff prevents an extra provider request before recovery");
		engine.close();
		return 3;
	}

	private static int verifyConcurrentRateLimitsKeepLongestBackoff() {
		Map<UUID, CompletableFuture<SpeechWorkerClient.Transcript>> pending = new java.util.LinkedHashMap<>();
		int[] transcriptions = { 0 };
		long[] now = { 0L };
		List<Delivered> delivered = new ArrayList<>();
		SpeechCaptureEngine engine = new SpeechCaptureEngine((playerId, sequence, whispering, samples) -> {
			transcriptions[0]++;
			CompletableFuture<SpeechWorkerClient.Transcript> future = new CompletableFuture<>();
			pending.put(playerId, future);
			return future;
		}, scheduler(), 5_000L, 1, ignored -> { }, () -> now[0]);
		engine.accept(PLAYER, false, new byte[] { 1 }, RecordingDecoder::new, Runnable::run,
				(playerId, text, whispering) -> delivered.add(new Delivered(playerId, text, whispering)));
		engine.accept(OTHER_PLAYER, false, new byte[] { 2 }, RecordingDecoder::new, Runnable::run,
				(playerId, text, whispering) -> delivered.add(new Delivered(playerId, text, whispering)));

		pending.get(PLAYER).completeExceptionally(new VoiceWorkerClient.VoiceWorkerException(
				"STT_RATE_LIMITED", "long provider rate limit", TimeUnit.SECONDS.toNanos(300L)));
		pending.get(OTHER_PLAYER).completeExceptionally(new VoiceWorkerClient.VoiceWorkerException(
				"STT_RATE_LIMITED", "short provider rate limit", TimeUnit.SECONDS.toNanos(1L)));
		now[0] = TimeUnit.SECONDS.toNanos(1L);
		engine.accept(PLAYER, false, new byte[] { 3 }, () -> {
			throw new AssertionError("a later shorter rate limit must not shorten the provider deadline");
		}, Runnable::run, (playerId, text, whispering) -> { });
		assertEquals(2, transcriptions[0], "the longest concurrent provider rate limit remains authoritative");

		now[0] = TimeUnit.SECONDS.toNanos(300L);
		engine.accept(PLAYER, false, new byte[] { 4 }, RecordingDecoder::new, Runnable::run,
				(playerId, text, whispering) -> delivered.add(new Delivered(playerId, text, whispering)));
		pending.get(PLAYER).complete(new SpeechWorkerClient.Transcript("recovered", 0.9));
		assertEquals(3, transcriptions[0], "speech retries when the longest provider deadline expires");
		assertEquals(List.of(new Delivered(PLAYER, "recovered", false)), delivered,
				"speech recovers after the retained provider deadline");
		engine.close();
		return 3;
	}

	private static int verifyPlayerBackoffIsolatesOtherPlayers(String failureCode, String label) {
		Map<UUID, CompletableFuture<SpeechWorkerClient.Transcript>> pending = new java.util.LinkedHashMap<>();
		long[] now = { 0L };
		SpeechCaptureEngine engine = new SpeechCaptureEngine((playerId, sequence, whispering, samples) -> {
			CompletableFuture<SpeechWorkerClient.Transcript> future = new CompletableFuture<>();
			pending.put(playerId, future);
			return future;
		}, scheduler(), 5_000L, 2, ignored -> { }, () -> now[0]);
		RecordingDecoder otherDecoder = new RecordingDecoder();
		List<Delivered> delivered = new ArrayList<>();
		engine.accept(OTHER_PLAYER, false, new byte[] { 1 }, () -> otherDecoder, Runnable::run,
				(playerId, text, whispering) -> delivered.add(new Delivered(playerId, text, whispering)));
		engine.accept(PLAYER, false, new byte[] { 1, 2 }, RecordingDecoder::new, Runnable::run,
				(playerId, text, whispering) -> delivered.add(new Delivered(playerId, text, whispering)));
		pending.get(PLAYER).completeExceptionally(new VoiceWorkerClient.VoiceWorkerException(
				failureCode, "Speech recognition hit a " + label));

		assertEquals(false, otherDecoder.closed,
				"one player's STT " + label + " response cannot close another player's active decoder");
		engine.accept(OTHER_PLAYER, false, new byte[] { 2 }, () -> {
			throw new AssertionError("the unaffected player's decoder must remain active");
		}, Runnable::run, (playerId, text, whispering) -> delivered.add(new Delivered(playerId, text, whispering)));
		pending.get(OTHER_PLAYER).complete(new SpeechWorkerClient.Transcript("other player heard", 0.9));
		assertEquals(List.of(new Delivered(OTHER_PLAYER, "other player heard", false)), delivered,
				"the unaffected player completes speech during another player's " + label + " backoff");
		engine.close();
		return 2;
	}

	private static int verifySttRecoversAfterBackoff(String failureCode, String label, long retryBackoffNanos) {
		int[] transcriptions = { 0 };
		int[] decoders = { 0 };
		long[] now = { 0L };
		List<Delivered> delivered = new ArrayList<>();
		SpeechCaptureEngine engine = new SpeechCaptureEngine((playerId, sequence, whispering, samples) -> {
			transcriptions[0]++;
			return transcriptions[0] == 1
					? CompletableFuture.failedFuture(new VoiceWorkerClient.VoiceWorkerException(
							failureCode, "Speech recognition is " + label))
					: CompletableFuture.completedFuture(new SpeechWorkerClient.Transcript("recovered", 0.9));
		}, scheduler(), 5_000L, 1, ignored -> { }, () -> now[0]);
		SpeechCaptureEngine.DecoderFactory decoderFactory = () -> {
			decoders[0]++;
			return new RecordingDecoder();
		};
		engine.accept(PLAYER, false, new byte[] { 1 }, decoderFactory, Runnable::run,
				(playerId, text, whispering) -> delivered.add(new Delivered(playerId, text, whispering)));
		engine.accept(PLAYER, false, new byte[] { 2 }, () -> {
			throw new AssertionError("capture must respect the bounded STT backoff");
		}, Runnable::run, (playerId, text, whispering) -> { });
		now[0] = retryBackoffNanos;
		engine.accept(PLAYER, false, new byte[] { 3 }, decoderFactory, Runnable::run,
				(playerId, text, whispering) -> delivered.add(new Delivered(playerId, text, whispering)));
		assertEquals(2, transcriptions[0], label + " STT capture probes again after its bounded backoff");
		assertEquals(2, decoders[0], label + " backoff drops packets without decoding and recovery creates one decoder");
		assertEquals(List.of(new Delivered(PLAYER, "recovered", false)), delivered,
				"the recovered transcript remains sequenced after the " + label + " utterance");
		engine.close();
		return 3;
	}

	private static int verifyCloseDiscardsPartialSpeechAndClosesDecoder() {
		RecordingTranscriber transcriber = new RecordingTranscriber();
		SpeechCaptureEngine engine = new SpeechCaptureEngine(transcriber, scheduler(), 5_000L, 32);
		Queue<RecordingDecoder> decoders = new ArrayDeque<>();
		engine.accept(
				PLAYER,
				false,
				new byte[] { 11 },
				() -> decoder(decoders),
				Runnable::run,
				(playerId, text, whispering) -> { throw new AssertionError("closed partial speech must not deliver"); }
		);
		RecordingDecoder decoder = decoders.remove();
		engine.close();
		assertEquals(true, decoder.closed, "close closes active decoder");
		assertEquals(0, transcriber.captured.size(), "close discards partial utterance");
		return 2;
	}

	private static int verifyCloseContinuesAfterDecoderCloseFailure() {
		ScheduledExecutorService scheduler = scheduler();
		SpeechCaptureEngine engine = new SpeechCaptureEngine(
				new RecordingTranscriber(), scheduler, 5_000L, 32
		);
		RecordingDecoder broken = new RecordingDecoder();
		broken.closeFailure = new IllegalStateException("first decoder close failed");
		RecordingDecoder later = new RecordingDecoder();
		engine.accept(PLAYER, false, new byte[] { 1 }, () -> broken, Runnable::run,
				(playerId, text, whispering) -> { });
		engine.accept(UUID.randomUUID(), false, new byte[] { 2 }, () -> later, Runnable::run,
				(playerId, text, whispering) -> { });

		long startedNanos = System.nanoTime();
		engine.close();
		long elapsedMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedNanos);

		assertEquals(true, broken.closed, "close attempts the failing decoder");
		assertEquals(true, later.closed, "close continues to later decoders");
		assertEquals(true, scheduler.isShutdown(), "close still shuts down the scheduler");
		assertEquals(true, elapsedMillis < 1_000L, "close remains bounded after cleanup failure");
		return 4;
	}

	private static int verifyCloseCancelsPendingTranscription() {
		ControlledTranscriber transcriber = new ControlledTranscriber();
		List<Delivered> delivered = new ArrayList<>();
		SpeechCaptureEngine engine = new SpeechCaptureEngine(transcriber, scheduler(), 5_000L, 1);
		engine.accept(
				PLAYER, false, new byte[] { 1 }, RecordingDecoder::new, Runnable::run,
				(playerId, text, whispering) -> delivered.add(new Delivered(playerId, text, whispering))
		);
		engine.accept(
				PLAYER, true, new byte[] { 2 }, RecordingDecoder::new, Runnable::run,
				(playerId, text, whispering) -> delivered.add(new Delivered(playerId, text, whispering))
		);
		CompletableFuture<SpeechWorkerClient.Transcript> firstPending = transcriber.pending.get(1L);
		engine.close();
		assertEquals(true, firstPending.isCancelled(), "close cancels the first pending STT request");
		assertEquals(false, transcriber.pending.containsKey(2L), "bounded pending replacement is discarded on close");
		assertEquals(List.of(), delivered, "a late cancelled transcript cannot deliver after close");
		return 3;
	}

	private static int verifyConsentRevocationCancelsOnlyOwnedSpeech() {
		ManualScheduledExecutor partialScheduler = new ManualScheduledExecutor();
		RecordingTranscriber partialTranscriber = new RecordingTranscriber();
		SpeechCaptureEngine partialEngine = new SpeechCaptureEngine(partialTranscriber, partialScheduler, 20L, 32);
		RecordingDecoder partialDecoder = new RecordingDecoder();
		partialEngine.accept(PLAYER, false, new byte[] { 1 }, () -> partialDecoder, Runnable::run,
				(playerId, text, whispering) -> { throw new AssertionError("revoked speech must not deliver"); });
		partialEngine.cancel(PLAYER);
		partialScheduler.runEvenIfCancelled(0);
		assertEquals(true, partialDecoder.closed, "revocation closes the player's buffered decoder");
		assertEquals(0, partialTranscriber.captured.size(), "revocation discards audio before STT submission");
		partialEngine.close();

		UUID otherPlayer = UUID.fromString("20000000-0000-4000-8000-000000000002");
		java.util.Map<UUID, CompletableFuture<SpeechWorkerClient.Transcript>> pending =
				new java.util.LinkedHashMap<>();
		SpeechCaptureEngine pendingEngine = new SpeechCaptureEngine((playerId, sequence, whispering, samples) -> {
			CompletableFuture<SpeechWorkerClient.Transcript> future = new CompletableFuture<>();
			pending.put(playerId, future);
			return future;
		}, scheduler(), 5_000L, 1);
		List<Delivered> delivered = new ArrayList<>();
		SpeechCaptureEngine.TranscriptDelivery delivery = (playerId, text, whispering) ->
				delivered.add(new Delivered(playerId, text, whispering));
		pendingEngine.accept(PLAYER, false, new byte[] { 2 }, RecordingDecoder::new, Runnable::run, delivery);
		CompletableFuture<SpeechWorkerClient.Transcript> revoked = pending.get(PLAYER);
		pendingEngine.accept(otherPlayer, false, new byte[] { 3 }, RecordingDecoder::new, Runnable::run, delivery);
		CompletableFuture<SpeechWorkerClient.Transcript> unrelated = pending.get(otherPlayer);
		pendingEngine.cancel(PLAYER);
		assertEquals(true, revoked.isCancelled(), "revocation cancels the player's owned STT request");
		assertEquals(false, unrelated.isCancelled(), "revocation leaves another player's STT request active");
		unrelated.complete(new SpeechWorkerClient.Transcript("other", 0.9));
		assertEquals(List.of(new Delivered(otherPlayer, "other", false)), delivered,
				"another player's transcript still delivers");

		pendingEngine.accept(PLAYER, false, new byte[] { 4 }, RecordingDecoder::new, Runnable::run, delivery);
		CompletableFuture<SpeechWorkerClient.Transcript> regranted = pending.get(PLAYER);
		regranted.complete(new SpeechWorkerClient.Transcript("new", 0.9));
		assertEquals(List.of(
				new Delivered(otherPlayer, "other", false),
				new Delivered(PLAYER, "new", false)
		), delivered, "a later consent generation starts a fresh ordered transcript stream");
		pendingEngine.close();
		return 6;
	}

	private static int verifyDisconnectGenerationStateIsBounded() throws Exception {
		java.util.Map<UUID, NonCancellableFuture<SpeechWorkerClient.Transcript>> pending =
				new java.util.LinkedHashMap<>();
		List<Delivered> delivered = new ArrayList<>();
		SpeechCaptureEngine engine = new SpeechCaptureEngine((playerId, sequence, whispering, samples) -> {
			NonCancellableFuture<SpeechWorkerClient.Transcript> future = new NonCancellableFuture<>();
			pending.put(playerId, future);
			return future;
		}, scheduler(), 5_000L, 1);

		for (int index = 0; index < 256; index++) engine.cancel(UUID.randomUUID());
		assertEquals(0, trackedPlayerGenerationCount(engine),
				"disconnects without captured speech retain no player generation state");

		engine.accept(PLAYER, false, new byte[] { 1 }, RecordingDecoder::new, Runnable::run,
				(playerId, text, whispering) -> delivered.add(new Delivered(playerId, text, whispering)));
		NonCancellableFuture<SpeechWorkerClient.Transcript> revoked = pending.get(PLAYER);
		assertEquals(1, trackedPlayerGenerationCount(engine),
				"captured speech owns one player generation");
		engine.cancel(PLAYER);
		assertEquals(true, revoked.cancellationAttempted,
				"revocation attempts to cancel its owned transcription");
		assertEquals(0, trackedPlayerGenerationCount(engine),
				"revocation releases the disconnected player's generation state");

		engine.accept(PLAYER, false, new byte[] { 2 }, RecordingDecoder::new, Runnable::run,
				(playerId, text, whispering) -> delivered.add(new Delivered(playerId, text, whispering)));
		NonCancellableFuture<SpeechWorkerClient.Transcript> regranted = pending.get(PLAYER);
		revoked.complete(new SpeechWorkerClient.Transcript("late", 0.9));
		assertEquals(List.of(), delivered,
				"late work from a revoked generation stays fenced after consent is granted again");
		regranted.complete(new SpeechWorkerClient.Transcript("current", 0.9));
		assertEquals(List.of(new Delivered(PLAYER, "current", false)), delivered,
				"the replacement generation can deliver while revoked work remains fenced");
		engine.cancel(PLAYER);
		assertEquals(0, trackedPlayerGenerationCount(engine),
				"settled replacement work leaves no generation state after disconnect");
		engine.close();
		return 7;
	}

	private static int trackedPlayerGenerationCount(SpeechCaptureEngine engine) throws Exception {
		var field = SpeechCaptureEngine.class.getDeclaredField("playerGenerations");
		field.setAccessible(true);
		return ((java.util.Map<?, ?>) field.get(engine)).size();
	}

	private static ScheduledExecutorService scheduler() {
		return Executors.newSingleThreadScheduledExecutor(
				runnable -> Thread.ofPlatform().daemon().name("voice-capture-verification").unstarted(runnable)
		);
	}

	private static RecordingDecoder decoder(Queue<RecordingDecoder> decoders) {
		RecordingDecoder decoder = new RecordingDecoder();
		decoders.add(decoder);
		return decoder;
	}

	private static short[] repeatedSamples(int length, int value) {
		short[] samples = new short[length];
		Arrays.fill(samples, (short) value);
		return samples;
	}

	private static void assertEquals(Object expected, Object actual, String message) {
		if (!java.util.Objects.equals(expected, actual)) {
			throw new AssertionError(message + ": expected=" + expected + ", actual=" + actual);
		}
	}

	private static final class RecordingTranscriber implements SpeechCaptureEngine.Transcriber {
		private final List<Captured> captured = new ArrayList<>();

		@Override
		public CompletableFuture<SpeechWorkerClient.Transcript> transcribe(
				UUID playerId,
				long utteranceSequence,
				boolean whispering,
				short[] samples
		) {
			captured.add(new Captured(playerId, utteranceSequence, whispering, samples.clone()));
			return CompletableFuture.completedFuture(new SpeechWorkerClient.Transcript("heard " + samples.length, 0.9));
		}
	}

	private static final class ControlledTranscriber implements SpeechCaptureEngine.Transcriber {
		private final java.util.Map<Long, CompletableFuture<SpeechWorkerClient.Transcript>> pending =
				new java.util.LinkedHashMap<>();

		@Override
		public CompletableFuture<SpeechWorkerClient.Transcript> transcribe(
				UUID playerId,
				long utteranceSequence,
				boolean whispering,
				short[] samples
		) {
			CompletableFuture<SpeechWorkerClient.Transcript> future = new CompletableFuture<>();
			pending.put(utteranceSequence, future);
			return future;
		}

		private void complete(long sequence, String text) {
			pending.get(sequence).complete(new SpeechWorkerClient.Transcript(text, 0.9));
		}

		private void fail(long sequence, Throwable failure) {
			pending.get(sequence).completeExceptionally(failure);
		}
	}

	private static final class NonCancellableFuture<T> extends CompletableFuture<T> {
		private boolean cancellationAttempted;

		@Override
		public boolean cancel(boolean mayInterruptIfRunning) {
			cancellationAttempted = true;
			return false;
		}
	}

	private static final class RecordingDecoder implements SpeechCaptureEngine.Decoder {
		private boolean closed;
		private RuntimeException decodeFailure;
		private RuntimeException closeFailure;
		private CountDownLatch closeAttempted;

		@Override
		public short[] decode(byte[] opus) {
			if (decodeFailure != null) throw decodeFailure;
			short[] decoded = new short[opus.length];
			for (int index = 0; index < opus.length; index++) decoded[index] = opus[index];
			return decoded;
		}

		@Override
		public void close() {
			closed = true;
			if (closeAttempted != null) closeAttempted.countDown();
			if (closeFailure != null) throw closeFailure;
		}
	}

	private static final class SequenceDecoder implements SpeechCaptureEngine.Decoder {
		private final Queue<short[]> frames;

		private SequenceDecoder(short[]... frames) {
			this.frames = new ArrayDeque<>(List.of(frames));
		}

		@Override
		public short[] decode(byte[] opus) {
			return frames.remove().clone();
		}

		@Override
		public void close() {
		}
	}

	private static final class PcmDecoder implements SpeechCaptureEngine.Decoder {
		private final short[] decoded;
		private boolean closed;

		private PcmDecoder(short[] decoded) {
			this.decoded = decoded.clone();
		}

		@Override
		public short[] decode(byte[] opus) {
			return decoded.clone();
		}

		@Override
		public void close() {
			closed = true;
		}
	}

	private static final class ManualScheduledExecutor extends AbstractExecutorService
			implements ScheduledExecutorService {
		private final List<ManualFuture> tasks = new ArrayList<>();
		private boolean shutdown;

		private void runEvenIfCancelled(int index) {
			tasks.get(index).runEvenIfCancelled();
		}

		@Override
		public ScheduledFuture<?> schedule(Runnable command, long delay, TimeUnit unit) {
			ManualFuture future = new ManualFuture(command, unit.toMillis(delay));
			tasks.add(future);
			return future;
		}

		@Override
		public <V> ScheduledFuture<V> schedule(Callable<V> callable, long delay, TimeUnit unit) {
			throw new UnsupportedOperationException();
		}

		@Override
		public ScheduledFuture<?> scheduleAtFixedRate(
				Runnable command, long initialDelay, long period, TimeUnit unit
		) {
			throw new UnsupportedOperationException();
		}

		@Override
		public ScheduledFuture<?> scheduleWithFixedDelay(
				Runnable command, long initialDelay, long delay, TimeUnit unit
		) {
			throw new UnsupportedOperationException();
		}

		@Override public void shutdown() { shutdown = true; }
		@Override public List<Runnable> shutdownNow() { shutdown = true; return List.of(); }
		@Override public boolean isShutdown() { return shutdown; }
		@Override public boolean isTerminated() { return shutdown; }
		@Override public boolean awaitTermination(long timeout, TimeUnit unit) { return shutdown; }
		@Override public void execute(Runnable command) { command.run(); }
	}

	private static final class ManualFuture implements ScheduledFuture<Object> {
		private final Runnable command;
		private final long delayMilliseconds;
		private boolean cancelled;
		private boolean done;

		private ManualFuture(Runnable command, long delayMilliseconds) {
			this.command = command;
			this.delayMilliseconds = delayMilliseconds;
		}

		private void runEvenIfCancelled() {
			command.run();
			done = true;
		}

		@Override public long getDelay(TimeUnit unit) { return 0L; }
		@Override public int compareTo(Delayed other) { return 0; }
		@Override public boolean cancel(boolean mayInterruptIfRunning) { cancelled = true; return true; }
		@Override public boolean isCancelled() { return cancelled; }
		@Override public boolean isDone() { return done; }
		@Override public Object get() { return null; }
		@Override public Object get(long timeout, TimeUnit unit) { return null; }
	}

	private record Captured(UUID playerId, long sequence, boolean whispering, short[] samples) {
	}

	private record Delivered(UUID playerId, String text, boolean whispering) {
	}
}
