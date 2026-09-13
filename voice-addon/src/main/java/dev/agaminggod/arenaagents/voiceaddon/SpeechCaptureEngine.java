package dev.agaminggod.arenaagents.voiceaddon;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Executor;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.function.LongSupplier;

final class SpeechCaptureEngine implements AutoCloseable {
	private static final long STT_UNAVAILABLE_RETRY_BACKOFF_NANOS = TimeUnit.SECONDS.toNanos(5L);
	private static final long STT_CAPACITY_RETRY_BACKOFF_NANOS = TimeUnit.MILLISECONDS.toNanos(250L);
	private static final long STT_RATE_LIMITED_RETRY_BACKOFF_NANOS = TimeUnit.SECONDS.toNanos(1L);
	private static final long INITIAL_DECODER_RETRY_NANOS = TimeUnit.SECONDS.toNanos(1L);
	private static final long MAX_DECODER_RETRY_NANOS = TimeUnit.SECONDS.toNanos(30L);
	private static final long DEFAULT_TRANSCRIPTION_DEADLINE_MILLISECONDS = 120_000L;
	private final Transcriber transcriber;
	private final ScheduledExecutorService scheduler;
	private final long minimumSilenceMilliseconds;
	private final long maximumSilenceMilliseconds;
	private final int adaptiveAfterSamples;
	private final int maxSamples;
	private final Consumer<InputLatency> latencyObserver;
	private final Consumer<InputActivity> activityObserver;
	private final LongSupplier monotonicNanos;
	private final long transcriptionDeadlineMilliseconds;
	private final Map<UUID, Utterance> utterances = new LinkedHashMap<>();
	private final Map<UUID, Long> sequences = new LinkedHashMap<>();
	private final Map<UUID, PlayerGeneration> playerGenerations = new LinkedHashMap<>();
	private final Map<UUID, TranscriptQueue> transcriptQueues = new LinkedHashMap<>();
	private final Map<UUID, DecoderRetry> decoderRetries = new LinkedHashMap<>();
	private final Map<UUID, Long> playerSttRetryAfterNanos = new LinkedHashMap<>();
	private final Map<CompletableFuture<SpeechWorkerClient.Transcript>, CompletedUtterance> transcriptions =
			new LinkedHashMap<>();
	private final Map<CompletableFuture<SpeechWorkerClient.Transcript>, CompletableFuture<SpeechWorkerClient.Transcript>>
			transcriptionSources = new LinkedHashMap<>();
	private final Map<CompletableFuture<SpeechWorkerClient.Transcript>, ScheduledFuture<?>> transcriptionDeadlines =
			new LinkedHashMap<>();
	private final java.util.Set<UUID> activeTranscriptionPlayers = new java.util.LinkedHashSet<>();
	private final Map<UUID, CompletedUtterance> pendingTranscriptions = new LinkedHashMap<>();
	private boolean closed;
	private long sttRetryAfterNanos;

	SpeechCaptureEngine(
			Transcriber transcriber,
			ScheduledExecutorService scheduler,
			long silenceMilliseconds,
			int maxSamples
	) {
		this(transcriber, scheduler, silenceMilliseconds, silenceMilliseconds, Integer.MAX_VALUE,
				maxSamples, ignored -> { }, ignored -> { }, System::nanoTime);
	}

	SpeechCaptureEngine(
			Transcriber transcriber,
			ScheduledExecutorService scheduler,
			long silenceMilliseconds,
			int maxSamples,
			Consumer<InputLatency> latencyObserver
	) {
		this(transcriber, scheduler, silenceMilliseconds, silenceMilliseconds, Integer.MAX_VALUE,
				maxSamples, latencyObserver, ignored -> { }, System::nanoTime);
	}

	SpeechCaptureEngine(
			Transcriber transcriber,
			ScheduledExecutorService scheduler,
			long silenceMilliseconds,
			int maxSamples,
			Consumer<InputLatency> latencyObserver,
			LongSupplier monotonicNanos
	) {
		this(transcriber, scheduler, silenceMilliseconds, silenceMilliseconds, Integer.MAX_VALUE,
				maxSamples, latencyObserver, ignored -> { }, monotonicNanos);
	}

	SpeechCaptureEngine(
			Transcriber transcriber,
			ScheduledExecutorService scheduler,
			long minimumSilenceMilliseconds,
			long maximumSilenceMilliseconds,
			int adaptiveAfterSamples,
			int maxSamples,
			Consumer<InputLatency> latencyObserver
	) {
		this(transcriber, scheduler, minimumSilenceMilliseconds, maximumSilenceMilliseconds,
				adaptiveAfterSamples, maxSamples, latencyObserver, ignored -> { }, System::nanoTime);
	}

	SpeechCaptureEngine(
			Transcriber transcriber,
			ScheduledExecutorService scheduler,
			long minimumSilenceMilliseconds,
			long maximumSilenceMilliseconds,
			int adaptiveAfterSamples,
			int maxSamples,
			Consumer<InputLatency> latencyObserver,
			LongSupplier monotonicNanos
	) {
		this(transcriber, scheduler, minimumSilenceMilliseconds, maximumSilenceMilliseconds,
				adaptiveAfterSamples, maxSamples, latencyObserver, ignored -> { }, monotonicNanos,
				DEFAULT_TRANSCRIPTION_DEADLINE_MILLISECONDS);
	}

	SpeechCaptureEngine(
			Transcriber transcriber,
			ScheduledExecutorService scheduler,
			long minimumSilenceMilliseconds,
			long maximumSilenceMilliseconds,
			int adaptiveAfterSamples,
			int maxSamples,
			Consumer<InputLatency> latencyObserver,
			Consumer<InputActivity> activityObserver,
			LongSupplier monotonicNanos,
			long transcriptionDeadlineMilliseconds
	) {
		this.transcriber = Objects.requireNonNull(transcriber, "transcriber must not be null");
		this.scheduler = Objects.requireNonNull(scheduler, "scheduler must not be null");
		if (minimumSilenceMilliseconds < 1L) {
			throw new IllegalArgumentException("minimumSilenceMilliseconds must be positive");
		}
		if (maximumSilenceMilliseconds < minimumSilenceMilliseconds) {
			throw new IllegalArgumentException("maximumSilenceMilliseconds must not be less than its minimum");
		}
		if (adaptiveAfterSamples < 1) throw new IllegalArgumentException("adaptiveAfterSamples must be positive");
		if (maxSamples < 1) throw new IllegalArgumentException("maxSamples must be positive");
		if (transcriptionDeadlineMilliseconds < 1L) {
			throw new IllegalArgumentException("transcriptionDeadlineMilliseconds must be positive");
		}
		this.minimumSilenceMilliseconds = minimumSilenceMilliseconds;
		this.maximumSilenceMilliseconds = maximumSilenceMilliseconds;
		this.adaptiveAfterSamples = adaptiveAfterSamples;
		this.maxSamples = maxSamples;
		this.latencyObserver = Objects.requireNonNull(latencyObserver, "latencyObserver must not be null");
		this.activityObserver = Objects.requireNonNull(activityObserver, "activityObserver must not be null");
		this.monotonicNanos = Objects.requireNonNull(monotonicNanos, "monotonicNanos must not be null");
		this.transcriptionDeadlineMilliseconds = transcriptionDeadlineMilliseconds;
	}

	SpeechCaptureEngine(
			Transcriber transcriber,
			ScheduledExecutorService scheduler,
			long minimumSilenceMilliseconds,
			long maximumSilenceMilliseconds,
			int adaptiveAfterSamples,
			int maxSamples,
			Consumer<InputLatency> latencyObserver,
			Consumer<InputActivity> activityObserver,
			LongSupplier monotonicNanos
	) {
		this(transcriber, scheduler, minimumSilenceMilliseconds, maximumSilenceMilliseconds,
				adaptiveAfterSamples, maxSamples, latencyObserver, activityObserver, monotonicNanos,
				DEFAULT_TRANSCRIPTION_DEADLINE_MILLISECONDS);
	}

	void accept(
			UUID playerId,
			boolean whispering,
			byte[] opus,
			DecoderFactory decoderFactory,
			Executor deliveryExecutor,
			TranscriptDelivery delivery
	) {
		Objects.requireNonNull(delivery, "delivery must not be null");
		accept(playerId, whispering, opus, decoderFactory, deliveryExecutor,
				(ignoredPlayerId, ignoredSequence) -> delivery);
	}

	void accept(
			UUID playerId,
			boolean whispering,
			byte[] opus,
			DecoderFactory decoderFactory,
			Executor deliveryExecutor,
			TranscriptDeliveryFactory deliveryFactory
	) {
		Objects.requireNonNull(playerId, "playerId must not be null");
		Objects.requireNonNull(opus, "opus must not be null");
		Objects.requireNonNull(decoderFactory, "decoderFactory must not be null");
		Objects.requireNonNull(deliveryExecutor, "deliveryExecutor must not be null");
		Objects.requireNonNull(deliveryFactory, "deliveryFactory must not be null");
		List<CompletedUtterance> completed = new ArrayList<>(2);
		try {
			synchronized (this) {
				long now = monotonicNanos.getAsLong();
				if (closed || now < sttRetryAfterNanos) return;
				Long playerRetryAfter = playerSttRetryAfterNanos.get(playerId);
				if (playerRetryAfter != null) {
					if (now < playerRetryAfter) return;
					playerSttRetryAfterNanos.remove(playerId);
				}
				DecoderRetry decoderRetry = decoderRetries.get(playerId);
				if (decoderRetry != null && now < decoderRetry.retryAfterNanos) return;
				Utterance utterance = utterances.get(playerId);
				if (utterance != null && utterance.whispering != whispering) {
					completed.add(finishLocked(playerId, utterance));
					utterance = null;
				}

				Decoder decoder = null;
				if (utterance == null) {
					try {
						decoder = Objects.requireNonNull(decoderFactory.create(), "decoderFactory returned null");
					} catch (RuntimeException ignored) {
						recordDecoderFailureLocked(playerId, now);
						return;
					}
				}
				Decoder activeDecoder = utterance == null ? decoder : utterance.decoder;
				short[] decoded;
				try {
					decoded = Objects.requireNonNull(activeDecoder.decode(opus), "decoder returned null");
				} catch (RuntimeException ignored) {
					if (utterance == null) closeDecoder(decoder);
					else completed.add(discardLocked(playerId, utterance));
					recordDecoderFailureLocked(playerId, now);
					return;
				}
				decoderRetries.remove(playerId);
				if (utterance == null && !DecodedPcmSpeechDetector.hasSpeech(decoded)) {
					closeDecoder(decoder);
					return;
				}

				if (utterance == null) {
					long sequence = sequences.getOrDefault(playerId, 0L) + 1L;
					TranscriptDelivery delivery;
					try {
						delivery = Objects.requireNonNull(
								deliveryFactory.create(playerId, sequence), "deliveryFactory returned null"
						);
					} catch (RuntimeException ignored) {
						closeDecoder(decoder);
						return;
					}
					sequences.put(playerId, sequence);
					utterance = new Utterance(
							playerId, decoder, whispering, sequence,
							playerGenerations.computeIfAbsent(playerId, ignored -> new PlayerGeneration()),
							deliveryExecutor, delivery, maxSamples
					);
					utterances.put(playerId, utterance);
				}
				utterance.append(decoded);
				if (utterance.length > 0 && !utterance.receivedReported) {
					utterance.receivedReported = true;
					reportActivity(utterance, InputActivity.Phase.RECEIVED);
				}
				utterance.lastPacketNanos = System.nanoTime();
				if (utterance.timeout != null) utterance.timeout.cancel(false);
				Utterance current = utterance;
				long timeoutEpoch = ++utterance.timeoutEpoch;
				utterance.timeout = scheduler.schedule(
						() -> finishIfCurrent(playerId, current, timeoutEpoch),
						endpointDelayMilliseconds(utterance.length),
						TimeUnit.MILLISECONDS
				);
				if (utterance.length >= maxSamples) completed.add(finishLocked(playerId, utterance));
			}
		} finally {
			for (CompletedUtterance utterance : completed) transcribe(utterance);
		}
	}

	private long endpointDelayMilliseconds(int samples) {
		return samples >= adaptiveAfterSamples ? minimumSilenceMilliseconds : maximumSilenceMilliseconds;
	}

	private void recordDecoderFailureLocked(UUID playerId, long now) {
		DecoderRetry previous = decoderRetries.get(playerId);
		int failures = Math.min(previous == null ? 1 : previous.failures + 1, 31);
		long multiplier = 1L << Math.min(failures - 1, 5);
		long delay = Math.min(MAX_DECODER_RETRY_NANOS, INITIAL_DECODER_RETRY_NANOS * multiplier);
		long retryAfter = now > Long.MAX_VALUE - delay ? Long.MAX_VALUE : now + delay;
		decoderRetries.put(playerId, new DecoderRetry(failures, retryAfter));
	}

	private void finishIfCurrent(UUID playerId, Utterance expected, long timeoutEpoch) {
		CompletedUtterance completed;
		synchronized (this) {
			if (closed || utterances.get(playerId) != expected || expected.timeoutEpoch != timeoutEpoch) return;
			completed = finishLocked(playerId, expected);
		}
		transcribe(completed);
	}

	private CompletedUtterance finishLocked(UUID playerId, Utterance utterance) {
		utterances.remove(playerId, utterance);
		utterance.timeoutEpoch++;
		if (utterance.timeout != null) utterance.timeout.cancel(false);
		boolean decoderClosed = closeDecoder(utterance.decoder);
		return new CompletedUtterance(
				playerId,
				utterance.sequence,
				utterance.whispering,
				utterance.playerGeneration,
				decoderClosed ? Arrays.copyOf(utterance.samples, utterance.length) : new short[0],
				utterance.deliveryExecutor,
				utterance.delivery,
				utterance.lastPacketNanos,
				System.nanoTime()
		);
	}

	private CompletedUtterance discardLocked(UUID playerId, Utterance utterance) {
		utterances.remove(playerId, utterance);
		utterance.timeoutEpoch++;
		if (utterance.timeout != null) utterance.timeout.cancel(false);
		closeDecoder(utterance.decoder);
		return new CompletedUtterance(
				playerId,
				utterance.sequence,
				utterance.whispering,
				utterance.playerGeneration,
				new short[0],
				utterance.deliveryExecutor,
				utterance.delivery,
				utterance.lastPacketNanos,
				System.nanoTime()
		);
	}

	private static boolean closeDecoder(Decoder decoder) {
		try {
			decoder.close();
			return true;
		} catch (RuntimeException ignored) {
			return false;
		}
	}

	private synchronized void transcribe(CompletedUtterance utterance) {
		if (closed || !ownsPlayerGeneration(utterance)) return;
		if (utterance.samples.length == 0) {
			completeTranscription(utterance, null, new RuntimeException("Speech audio could not be decoded"));
			return;
		}
		long now = monotonicNanos.getAsLong();
		if (now < sttRetryAfterNanos) {
			completeTranscription(utterance, null, new RuntimeException("STT retry backoff is active"));
			return;
		}
		Long playerRetryAfter = playerSttRetryAfterNanos.get(utterance.playerId);
		if (playerRetryAfter != null) {
			if (now < playerRetryAfter) {
				completeTranscription(utterance, null, new RuntimeException("Player STT retry backoff is active"));
				return;
			}
			playerSttRetryAfterNanos.remove(utterance.playerId);
		}
		if (activeTranscriptionPlayers.contains(utterance.playerId)) {
			CompletedUtterance replaced = pendingTranscriptions.put(utterance.playerId, utterance);
			if (replaced != null) {
				completeTranscription(replaced, null, new RuntimeException("Utterance was coalesced"));
			}
			return;
		}
		activeTranscriptionPlayers.add(utterance.playerId);
		reportActivity(utterance, InputActivity.Phase.PROCESSING);
		long transcriptionStartedNanos = System.nanoTime();
		CompletableFuture<SpeechWorkerClient.Transcript> source;
		try {
			source = Objects.requireNonNull(Objects.requireNonNull(transcriber.transcribe(
					utterance.playerId,
					utterance.sequence,
					utterance.whispering,
					utterance.samples
				), "transcriber returned null").toCompletableFuture(), "transcriber returned a null future");
		} catch (RuntimeException failure) {
			reportLatency(utterance, transcriptionStartedNanos, System.nanoTime());
			completeTranscription(utterance, null, failure);
			finishActiveAndSubmitPending(utterance.playerId);
			return;
		}
		if (closed || !ownsPlayerGeneration(utterance)) {
			activeTranscriptionPlayers.remove(utterance.playerId);
			source.cancel(true);
			return;
		}
		CompletableFuture<SpeechWorkerClient.Transcript> bounded = new CompletableFuture<>();
		final CompletableFuture<SpeechWorkerClient.Transcript> sourceFuture = source;
		ScheduledFuture<?> deadline;
		try {
			deadline = scheduler.schedule(
					() -> timeoutTranscription(bounded, sourceFuture),
					transcriptionDeadlineMilliseconds,
					TimeUnit.MILLISECONDS
			);
		} catch (RuntimeException failure) {
			sourceFuture.cancel(true);
			reportLatency(utterance, transcriptionStartedNanos, System.nanoTime());
			completeTranscription(utterance, null, failure);
			finishActiveAndSubmitPending(utterance.playerId);
			return;
		}
		transcriptions.put(bounded, utterance);
		transcriptionSources.put(bounded, sourceFuture);
		transcriptionDeadlines.put(bounded, deadline);
		sourceFuture.whenComplete((transcript, failure) -> {
			if (failure == null) bounded.complete(transcript);
			else bounded.completeExceptionally(failure);
		});
		bounded.whenComplete((transcript, failure) -> {
			ScheduledFuture<?> timeout;
			synchronized (this) {
				transcriptions.remove(bounded);
				transcriptionSources.remove(bounded);
				timeout = transcriptionDeadlines.remove(bounded);
			}
			if (timeout != null) timeout.cancel(false);
			reportLatency(utterance, transcriptionStartedNanos, System.nanoTime());
			completeTranscription(utterance, transcript, failure);
			finishActiveAndSubmitPending(utterance.playerId);
		});
	}

	private void timeoutTranscription(
			CompletableFuture<SpeechWorkerClient.Transcript> bounded,
			CompletableFuture<SpeechWorkerClient.Transcript> source
	) {
		if (bounded.completeExceptionally(new VoiceWorkerClient.VoiceWorkerException(
				"STT_TIMEOUT", "Speech transcription exceeded its deadline"
		))) {
			source.cancel(true);
		}
	}

	private synchronized void finishActiveAndSubmitPending(UUID playerId) {
		activeTranscriptionPlayers.remove(playerId);
		CompletedUtterance pending = pendingTranscriptions.remove(playerId);
		if (pending != null) transcribe(pending);
	}

	private void reportLatency(CompletedUtterance utterance, long transcriptionStartedNanos, long completedNanos) {
		long endpointNanos = Math.max(0L, utterance.endpointCompletedNanos - utterance.lastPacketNanos);
		long transcriptionNanos = Math.max(0L, completedNanos - transcriptionStartedNanos);
		long totalNanos = Math.max(0L, completedNanos - utterance.lastPacketNanos);
		try {
			latencyObserver.accept(new InputLatency(
					utterance.playerId,
					utterance.sequence,
					TimeUnit.NANOSECONDS.toMillis(endpointNanos),
					TimeUnit.NANOSECONDS.toMillis(transcriptionNanos),
					TimeUnit.NANOSECONDS.toMillis(totalNanos)
			));
		} catch (RuntimeException ignored) {
			// Timing diagnostics must never interrupt speech delivery.
		}
	}

	private void completeTranscription(
			CompletedUtterance utterance,
			SpeechWorkerClient.Transcript transcript,
			Throwable failure
	) {
		Map<UUID, List<TranscriptOutcome>> readyByPlayer = new LinkedHashMap<>();
		InputActivity.Phase terminalPhase = failure != null
				? InputActivity.Phase.FAILED
				: transcript == null || transcript.text().isBlank()
						? InputActivity.Phase.NO_SPEECH : InputActivity.Phase.RECOGNIZED;
		synchronized (this) {
			if (closed || !ownsPlayerGeneration(utterance)) return;
			String backoffCode = sttBackoffCode(failure);
			long retryBackoffNanos = sttRetryBackoffNanos(backoffCode, failure);
			if ("STT_UNAVAILABLE".equals(backoffCode) || "STT_RATE_LIMITED".equals(backoffCode)) {
				long now = monotonicNanos.getAsLong();
				long retryAfterNanos = now > Long.MAX_VALUE - retryBackoffNanos
						? Long.MAX_VALUE : now + retryBackoffNanos;
				sttRetryAfterNanos = Math.max(sttRetryAfterNanos, retryAfterNanos);
				playerSttRetryAfterNanos.clear();
				recordOutcomeLocked(utterance, null, readyByPlayer);
				for (Utterance active : utterances.values()) {
					if (active.timeout != null) active.timeout.cancel(false);
					try {
						active.decoder.close();
					} catch (RuntimeException ignored) {
					}
					recordOutcomeLocked(new CompletedUtterance(
							active.playerId, active.sequence, active.whispering, active.playerGeneration, new short[0],
							active.deliveryExecutor, active.delivery, active.lastPacketNanos, monotonicNanos.getAsLong()
					), null, readyByPlayer);
				}
				utterances.clear();
			} else if (retryBackoffNanos > 0L) {
				long now = monotonicNanos.getAsLong();
				long retryAfter = now > Long.MAX_VALUE - retryBackoffNanos
						? Long.MAX_VALUE : now + retryBackoffNanos;
				playerSttRetryAfterNanos.put(utterance.playerId, retryAfter);
				recordOutcomeLocked(utterance, null, readyByPlayer);
				Utterance active = utterances.get(utterance.playerId);
				if (active != null) recordOutcomeLocked(discardLocked(active.playerId, active), null, readyByPlayer);
			} else {
				recordOutcomeLocked(utterance, failure == null ? transcript : null, readyByPlayer);
			}
		}
		reportActivity(utterance, terminalPhase);
		for (List<TranscriptOutcome> ready : readyByPlayer.values()) {
			try {
				ready.getFirst().utterance.deliveryExecutor.execute(() -> {
					for (TranscriptOutcome outcome : ready) {
						outcome.utterance.delivery.deliver(
								outcome.utterance.playerId,
								outcome.transcript.text(),
								outcome.utterance.whispering
						);
					}
				});
			} catch (RuntimeException ignored) {
				// The Minecraft server may be stopping while transcription completes.
			}
		}
	}

	private void reportActivity(CompletedUtterance utterance, InputActivity.Phase phase) {
		reportActivity(utterance.playerId, utterance.sequence, phase);
	}

	private void reportActivity(Utterance utterance, InputActivity.Phase phase) {
		reportActivity(utterance.playerId, utterance.sequence, phase);
	}

	private void reportActivity(UUID playerId, long sequence, InputActivity.Phase phase) {
		try {
			activityObserver.accept(new InputActivity(playerId, sequence, phase));
		} catch (RuntimeException ignored) {
			// Operator diagnostics cannot interrupt microphone capture or transcription.
		}
	}

	private void recordOutcomeLocked(
			CompletedUtterance utterance,
			SpeechWorkerClient.Transcript transcript,
			Map<UUID, List<TranscriptOutcome>> readyByPlayer
	) {
		TranscriptQueue queue = transcriptQueues.computeIfAbsent(utterance.playerId, ignored -> new TranscriptQueue());
		queue.completed.put(utterance.sequence, new TranscriptOutcome(utterance, transcript));
		while (true) {
			TranscriptOutcome outcome = queue.completed.remove(queue.nextSequence);
			if (outcome == null) break;
			queue.nextSequence++;
			if (outcome.transcript != null && !outcome.transcript.text().isBlank()) {
				readyByPlayer.computeIfAbsent(utterance.playerId, ignored -> new ArrayList<>()).add(outcome);
			}
		}
	}

	void cancel(UUID playerId) {
		Objects.requireNonNull(playerId, "playerId must not be null");
		List<CompletableFuture<SpeechWorkerClient.Transcript>> ownedTranscriptions = new ArrayList<>();
		List<CompletableFuture<SpeechWorkerClient.Transcript>> ownedSources = new ArrayList<>();
		List<ScheduledFuture<?>> ownedDeadlines = new ArrayList<>();
		synchronized (this) {
			playerGenerations.remove(playerId);
			Utterance active = utterances.remove(playerId);
			if (active != null) {
				active.timeoutEpoch++;
				if (active.timeout != null) active.timeout.cancel(false);
				closeDecoder(active.decoder);
			}
			sequences.remove(playerId);
			transcriptQueues.remove(playerId);
			decoderRetries.remove(playerId);
			playerSttRetryAfterNanos.remove(playerId);
			activeTranscriptionPlayers.remove(playerId);
			pendingTranscriptions.remove(playerId);
			for (Map.Entry<CompletableFuture<SpeechWorkerClient.Transcript>, CompletedUtterance> entry
					: transcriptions.entrySet()) {
				if (entry.getValue().playerId.equals(playerId)) {
					CompletableFuture<SpeechWorkerClient.Transcript> transcription = entry.getKey();
					ownedTranscriptions.add(transcription);
					CompletableFuture<SpeechWorkerClient.Transcript> source = transcriptionSources.remove(transcription);
					if (source != null) ownedSources.add(source);
					ScheduledFuture<?> deadline = transcriptionDeadlines.remove(transcription);
					if (deadline != null) ownedDeadlines.add(deadline);
				}
			}
			for (CompletableFuture<SpeechWorkerClient.Transcript> transcription : ownedTranscriptions) {
				transcriptions.remove(transcription);
			}
		}
		for (ScheduledFuture<?> deadline : ownedDeadlines) deadline.cancel(false);
		for (CompletableFuture<SpeechWorkerClient.Transcript> transcription : ownedTranscriptions) {
			transcription.cancel(true);
		}
		for (CompletableFuture<SpeechWorkerClient.Transcript> source : ownedSources) source.cancel(true);
	}

	private boolean ownsPlayerGeneration(CompletedUtterance utterance) {
		return playerGenerations.get(utterance.playerId) == utterance.playerGeneration;
	}

	private static String sttBackoffCode(Throwable failure) {
		Throwable current = failure;
		while (current instanceof CompletionException && current.getCause() != null) current = current.getCause();
		if (!(current instanceof VoiceWorkerClient.VoiceWorkerException workerFailure)) return "";
		return switch (workerFailure.code()) {
			case "STT_CAPACITY", "STT_RATE_LIMITED", "STT_UNAVAILABLE" -> workerFailure.code();
			default -> "";
		};
	}

	private static long sttRetryBackoffNanos(String failureCode, Throwable failure) {
		return switch (failureCode) {
			case "STT_CAPACITY" -> STT_CAPACITY_RETRY_BACKOFF_NANOS;
			case "STT_RATE_LIMITED" -> {
				VoiceWorkerClient.VoiceWorkerException workerFailure = workerFailure(failure);
				yield workerFailure != null && workerFailure.retryAfterNanos() > 0L
						? workerFailure.retryAfterNanos() : STT_RATE_LIMITED_RETRY_BACKOFF_NANOS;
			}
			case "STT_UNAVAILABLE" -> STT_UNAVAILABLE_RETRY_BACKOFF_NANOS;
			default -> 0L;
		};
	}

	private static VoiceWorkerClient.VoiceWorkerException workerFailure(Throwable failure) {
		Throwable current = failure;
		while (current instanceof CompletionException && current.getCause() != null) current = current.getCause();
		return current instanceof VoiceWorkerClient.VoiceWorkerException worker ? worker : null;
	}

	@Override
	public synchronized void close() {
		if (closed) return;
		closed = true;
		for (Utterance utterance : utterances.values()) {
			utterance.timeoutEpoch++;
			if (utterance.timeout != null) utterance.timeout.cancel(false);
			closeDecoder(utterance.decoder);
		}
		utterances.clear();
		playerGenerations.clear();
		transcriptQueues.clear();
		decoderRetries.clear();
		playerSttRetryAfterNanos.clear();
		activeTranscriptionPlayers.clear();
		pendingTranscriptions.clear();
		for (Map.Entry<CompletableFuture<SpeechWorkerClient.Transcript>, CompletableFuture<SpeechWorkerClient.Transcript>>
				entry : List.copyOf(transcriptionSources.entrySet())) {
			entry.getKey().cancel(true);
			entry.getValue().cancel(true);
		}
		transcriptions.clear();
		for (ScheduledFuture<?> deadline : transcriptionDeadlines.values()) deadline.cancel(false);
		transcriptionSources.clear();
		transcriptionDeadlines.clear();
		scheduler.shutdownNow();
	}

	@FunctionalInterface
	interface Transcriber {
		CompletionStage<SpeechWorkerClient.Transcript> transcribe(
				UUID playerId,
				long utteranceSequence,
				boolean whispering,
				short[] samples
		);
	}

	@FunctionalInterface
	interface DecoderFactory {
		Decoder create();
	}

	interface Decoder {
		short[] decode(byte[] opus);

		void close();
	}

	@FunctionalInterface
	interface TranscriptDelivery {
		void deliver(UUID playerId, String transcript, boolean whispering);
	}

	@FunctionalInterface
	interface TranscriptDeliveryFactory {
		TranscriptDelivery create(UUID playerId, long utteranceSequence);
	}

	private static final class Utterance {
		private final UUID playerId;
		private final Decoder decoder;
		private final boolean whispering;
		private final long sequence;
		private final PlayerGeneration playerGeneration;
		private final Executor deliveryExecutor;
		private final TranscriptDelivery delivery;
		private final int maxSamples;
		private short[] samples;
		private int length;
		private long lastPacketNanos;
		private ScheduledFuture<?> timeout;
		private long timeoutEpoch;
		private boolean receivedReported;

		private Utterance(
				UUID playerId,
				Decoder decoder,
				boolean whispering,
				long sequence,
				PlayerGeneration playerGeneration,
				Executor deliveryExecutor,
				TranscriptDelivery delivery,
				int maxSamples
		) {
			this.playerId = playerId;
			this.decoder = decoder;
			this.whispering = whispering;
			this.sequence = sequence;
			this.playerGeneration = playerGeneration;
			this.deliveryExecutor = deliveryExecutor;
			this.delivery = delivery;
			this.maxSamples = maxSamples;
			this.samples = new short[Math.min(48_000, maxSamples)];
		}

		private void append(short[] decoded) {
			int accepted = Math.min(decoded.length, maxSamples - length);
			if (accepted <= 0) return;
			int required = length + accepted;
			if (required > samples.length) {
				samples = Arrays.copyOf(samples, Math.min(maxSamples, Math.max(required, samples.length * 2)));
			}
			System.arraycopy(decoded, 0, samples, length, accepted);
			length = required;
		}
	}

	private record CompletedUtterance(
			UUID playerId,
			long sequence,
			boolean whispering,
			PlayerGeneration playerGeneration,
			short[] samples,
			Executor deliveryExecutor,
			TranscriptDelivery delivery,
			long lastPacketNanos,
			long endpointCompletedNanos
	) {
	}

	private static final class PlayerGeneration {
	}

	record InputLatency(
			UUID playerId,
			long utteranceSequence,
			long endpointMilliseconds,
			long transcriptionMilliseconds,
			long totalMilliseconds
	) {
	}

	record InputActivity(UUID playerId, long utteranceSequence, Phase phase) {
		InputActivity {
			Objects.requireNonNull(playerId, "playerId must not be null");
			if (utteranceSequence < 1L) throw new IllegalArgumentException("utteranceSequence must be positive");
			Objects.requireNonNull(phase, "phase must not be null");
		}

		enum Phase {
			RECEIVED,
			PROCESSING,
			RECOGNIZED,
			NO_SPEECH,
			FAILED
		}
	}

	private static final class TranscriptQueue {
		private long nextSequence = 1L;
		private final Map<Long, TranscriptOutcome> completed = new LinkedHashMap<>();
	}

	private record DecoderRetry(int failures, long retryAfterNanos) {
	}

	private record TranscriptOutcome(
			CompletedUtterance utterance,
			SpeechWorkerClient.Transcript transcript
	) {
	}
}
