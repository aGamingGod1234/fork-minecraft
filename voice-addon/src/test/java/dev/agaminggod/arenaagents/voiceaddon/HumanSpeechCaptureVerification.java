package dev.agaminggod.arenaagents.voiceaddon;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicReference;

/** Verifies the production input adapter from speech phases through delivery and verbose reporting. */
final class HumanSpeechCaptureVerification {
	private static final UUID PLAYER = UUID.fromString("20000000-0000-4000-8000-000000000010");

	private HumanSpeechCaptureVerification() {
	}

	static int verify() {
		int assertions = verifySpeechTimeAudienceSurvivesTranscription();
		assertions += verifyNoRecipientIsVisible();
		assertions += verifyAudienceIsCapturedBeforeReporting();
		assertions += verifyDeliveryFailureIsVisible();
		assertions += verifyCanceledContextCannotDeliverIntoReusedSequence();
		return assertions;
	}

	private static int verifySpeechTimeAudienceSurvivesTranscription() {
		AtomicReference<List<String>> currentAudience = new AtomicReference<>(List.of("agent-at-speech-time"));
		List<List<String>> deliveredAudience = new ArrayList<>();
		List<String> deliveredTranscript = new ArrayList<>();
		List<String> verbose = new ArrayList<>();
		var adapter = adapter(currentAudience, deliveredAudience, deliveredTranscript, verbose);
		CompletableFuture<SpeechWorkerClient.Transcript> pending = new CompletableFuture<>();
		SpeechCaptureEngine engine = new SpeechCaptureEngine(
				(playerId, sequence, whispering, samples) -> pending,
				Executors.newSingleThreadScheduledExecutor(),
				5_000L, 5_000L, 1, 1, ignored -> { }, adapter::report, System::nanoTime
		);
		engine.accept(
				PLAYER, false, new byte[] { 1 }, HumanSpeechCaptureVerification::decoder,
				Runnable::run,
				(playerId, sequence) -> adapter.begin("server", Runnable::run, playerId, sequence, false)
		);
		currentAudience.set(List.of("agent-after-speech"));
		pending.complete(new SpeechWorkerClient.Transcript("heard clearly", 0.95D));

		assertEquals(List.of(List.of("agent-at-speech-time")), deliveredAudience,
				"delivery keeps the audience captured when speech began");
		assertEquals(List.of("heard clearly"), deliveredTranscript,
				"recognized text reaches delivery exactly once");
		assertEquals(List.of(
				"agent-at-speech-time|Receiving nearby speech.",
				"agent-at-speech-time|Processing nearby speech.",
				"agent-at-speech-time|Nearby speech was recognized.",
				"agent-at-speech-time|Nearby speech was delivered for processing."
		), verbose, "the adapter emits bounded truthful milestones against the same audience");
		engine.close();
		return 3;
	}

	private static int verifyNoRecipientIsVisible() {
		AtomicReference<List<String>> currentAudience = new AtomicReference<>(List.of());
		List<List<String>> deliveredAudience = new ArrayList<>();
		List<String> deliveredTranscript = new ArrayList<>();
		List<String> verbose = new ArrayList<>();
		var adapter = adapter(currentAudience, deliveredAudience, deliveredTranscript, verbose);
		SpeechCaptureEngine engine = new SpeechCaptureEngine(
				(playerId, sequence, whispering, samples) -> CompletableFuture.completedFuture(
						new SpeechWorkerClient.Transcript("anyone there", 0.9D)
				),
				Executors.newSingleThreadScheduledExecutor(),
				5_000L, 5_000L, 1, 1, ignored -> { }, adapter::report, System::nanoTime
		);
		engine.accept(
				PLAYER, false, new byte[] { 1 }, HumanSpeechCaptureVerification::decoder,
				Runnable::run,
				(playerId, sequence) -> adapter.begin("server", Runnable::run, playerId, sequence, false)
		);
		assertEquals(List.of(
				"SYSTEM|Voice audio was received, but no agent was in range.",
				"SYSTEM|Processing nearby speech.",
				"SYSTEM|Nearby speech was recognized.",
				"SYSTEM|Speech was recognized, but no agent received it."
		), verbose, "an empty audience is explicit instead of looking like a frozen microphone");
		engine.close();
		return 1;
	}

	private static int verifyAudienceIsCapturedBeforeReporting() {
		AtomicReference<List<String>> currentAudience = new AtomicReference<>(List.of());
		List<List<String>> deliveredAudience = new ArrayList<>();
		List<String> deliveredTranscript = new ArrayList<>();
		List<String> verbose = new ArrayList<>();
		var adapter = adapter(currentAudience, deliveredAudience, deliveredTranscript, verbose);
		List<Runnable> queued = new ArrayList<>();
		adapter.begin("server", queued::add, PLAYER, 1L, false);
		assertEquals(0, queued.size(), "audience capture completes before asynchronous reporting is queued");
		adapter.report(new SpeechCaptureEngine.InputActivity(
				PLAYER, 1L, SpeechCaptureEngine.InputActivity.Phase.RECEIVED));
		assertEquals(1, queued.size(), "the received milestone is queued after the audience snapshot is ready");
		queued.remove(0).run();
		assertEquals(List.of("SYSTEM|Voice audio was received, but no agent was in range."), verbose,
				"an empty captured audience is reported as out of range instead of not ready");
		return 2;
	}

	private static int verifyDeliveryFailureIsVisible() {
		AtomicReference<List<String>> currentAudience = new AtomicReference<>(List.of("agent"));
		List<String> verbose = new ArrayList<>();
		var adapter = adapter(currentAudience, new ArrayList<>(), new ArrayList<>(), verbose, true);
		SpeechCaptureEngine engine = new SpeechCaptureEngine(
				(playerId, sequence, whispering, samples) -> CompletableFuture.completedFuture(
						new SpeechWorkerClient.Transcript("heard", 0.9D)
				),
				Executors.newSingleThreadScheduledExecutor(),
				5_000L, 5_000L, 1, 1, ignored -> { }, adapter::report, System::nanoTime
		);
		engine.accept(
				PLAYER, false, new byte[] { 1 }, HumanSpeechCaptureVerification::decoder,
				Runnable::run,
				(playerId, sequence) -> adapter.begin("server", Runnable::run, playerId, sequence, false)
		);
		assertEquals("SYSTEM|Speech was recognized, but delivery was interrupted. Listening will continue.",
				verbose.getLast(), "delivery failures terminate with a safe visible status");
		engine.close();
		return 1;
	}

	private static int verifyCanceledContextCannotDeliverIntoReusedSequence() {
		AtomicReference<List<String>> currentAudience = new AtomicReference<>(List.of("old-agent"));
		List<List<String>> deliveredAudience = new ArrayList<>();
		var adapter = adapter(currentAudience, deliveredAudience, new ArrayList<>(), new ArrayList<>());
		SpeechCaptureEngine.TranscriptDelivery stale = adapter.begin("server", Runnable::run, PLAYER, 1L, false);
		adapter.cancel(PLAYER);
		currentAudience.set(List.of("new-agent"));
		adapter.begin("server", Runnable::run, PLAYER, 1L, false);
		stale.deliver(PLAYER, "must not cross sessions", false);
		assertEquals(List.of(), deliveredAudience,
				"canceled speech cannot deliver through a context key reused by a later session");
		adapter.clear();
		return 1;
	}

	private static HumanSpeechCapture.InputActivityAdapter<String, List<String>> adapter(
			AtomicReference<List<String>> currentAudience,
			List<List<String>> deliveredAudience,
			List<String> deliveredTranscript,
			List<String> verbose
	) {
		return adapter(currentAudience, deliveredAudience, deliveredTranscript, verbose, false);
	}

	private static HumanSpeechCapture.InputActivityAdapter<String, List<String>> adapter(
			AtomicReference<List<String>> currentAudience,
			List<List<String>> deliveredAudience,
			List<String> deliveredTranscript,
			List<String> verbose,
			boolean failDelivery
	) {
		return new HumanSpeechCapture.InputActivityAdapter<>(
				new HumanSpeechCapture.InputActivityAdapter.Gateway<>() {
					@Override public Optional<List<String>> capture(String server, UUID playerId, boolean whispering) {
						return Optional.of(List.copyOf(currentAudience.get()));
					}
					@Override public int deliver(String server, List<String> audience, String transcript) {
						if (failDelivery) throw new IllegalStateException("private provider failure");
						deliveredAudience.add(audience);
						deliveredTranscript.add(transcript);
						return audience.size();
					}
				},
				new HumanSpeechCapture.InputActivityAdapter.Reporter<>() {
					@Override public boolean hasRecipients(List<String> audience) { return !audience.isEmpty(); }
					@Override public void reportAudience(String server, List<String> audience, String message) {
						verbose.add(String.join(",", audience) + "|" + message);
					}
					@Override public void reportSystem(String server, String message) {
						verbose.add("SYSTEM|" + message);
					}
				}
		);
	}

	private static SpeechCaptureEngine.Decoder decoder() {
		return new SpeechCaptureEngine.Decoder() {
			@Override public short[] decode(byte[] opus) {
				short[] samples = new short[opus.length];
				for (int index = 0; index < opus.length; index++) samples[index] = opus[index];
				return samples;
			}
			@Override public void close() { }
		};
	}

	private static void assertEquals(Object expected, Object actual, String label) {
		if (!java.util.Objects.equals(expected, actual)) {
			throw new AssertionError(label + ": expected <" + expected + "> but was <" + actual + ">");
		}
	}
}
