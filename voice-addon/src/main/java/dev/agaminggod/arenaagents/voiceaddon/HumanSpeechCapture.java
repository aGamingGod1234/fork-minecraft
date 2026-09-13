package dev.agaminggod.arenaagents.voiceaddon;

import de.maxhenkel.voicechat.api.VoicechatServerApi;
import de.maxhenkel.voicechat.api.opus.OpusDecoder;
import dev.agaminggod.arenaagents.server.CodexAgentServerRuntime;
import dev.agaminggod.arenaagents.server.conversation.ServerAgentConversationRouter.ProximitySpeechAudience;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.Executor;
import net.minecraft.server.MinecraftServer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

final class HumanSpeechCapture implements ServerSpeechCaptureRegistry.Capture {
	private static final Logger LOGGER = LoggerFactory.getLogger(HumanSpeechCapture.class);
	private static final int MAX_SAMPLES = 48_000 * 20;
	private static final int ADAPTIVE_ENDPOINT_AFTER_SAMPLES = 48_000 / 2;
	private static final long MIN_SILENCE_MILLISECONDS = 160L;
	private static final long SILENCE_MILLISECONDS = 300L;
	private static final ConsentCapture CONSENT_CAPTURE = resolveConsentCapture();
	private static final VoiceInputReporter INPUT_REPORTER = resolveInputReporter();
	private final InputActivityAdapter<MinecraftServer, ProximitySpeechAudience> inputActivity;
	private final SpeechCaptureEngine engine;

	HumanSpeechCapture(SpeechWorkerClient worker) {
		this.inputActivity = new InputActivityAdapter<>(
				new InputActivityAdapter.Gateway<>() {
					@Override
					public Optional<ProximitySpeechAudience> capture(
							MinecraftServer server, UUID playerId, boolean whispering
					) {
						return CodexAgentServerRuntime.captureHumanSpeechAudience(server, playerId, whispering);
					}

					@Override
					public int deliver(MinecraftServer server, ProximitySpeechAudience audience, String transcript) {
						return CodexAgentServerRuntime.deliverHumanSpeech(server, audience, transcript)
								.deliveredIds().size();
					}
				},
				new InputActivityAdapter.Reporter<>() {
					@Override public boolean hasRecipients(ProximitySpeechAudience audience) {
						return !audience.recipientAgentIds().isEmpty();
					}
					@Override public void reportAudience(MinecraftServer server, ProximitySpeechAudience audience, String message) {
						INPUT_REPORTER.reportAudience(server, audience, message);
					}
					@Override public void reportSystem(MinecraftServer server, String message) {
						INPUT_REPORTER.reportSystem(server, message);
					}
				}
		);
		this.engine = new SpeechCaptureEngine(
				worker::transcribe,
				java.util.concurrent.Executors.newSingleThreadScheduledExecutor(
						runnable -> Thread.ofPlatform().daemon().name("arenaagents-stt").unstarted(runnable)
				),
				MIN_SILENCE_MILLISECONDS,
				SILENCE_MILLISECONDS,
				ADAPTIVE_ENDPOINT_AFTER_SAMPLES,
				MAX_SAMPLES,
				latency -> LOGGER.info(
						"Voice input latency player={} sequence={} endpointMs={} transcriptionMs={} totalMs={}",
						latency.playerId(), latency.utteranceSequence(), latency.endpointMilliseconds(),
						latency.transcriptionMilliseconds(), latency.totalMilliseconds()
				),
				inputActivity::report,
				System::nanoTime
		);
	}

	@Override
	public void accept(MicrophonePacketSnapshot packet) {
		if (!(packet.minecraftServer() instanceof MinecraftServer server)) return;
		captureWhileGranted(
				server, packet.playerId(), () -> {
					byte[] opus = packet.opus();
					if (opus.length == 0 || opus.length > 8_192) return;
					VoicechatServerApi api = packet.voicechat();
					boolean whispering = packet.whispering();
					engine.accept(
							packet.playerId(),
							whispering,
							opus,
							() -> decoder(api.createDecoder()),
							server::execute,
							(capturedPlayerId, utteranceSequence) -> inputActivity.begin(
									server, server::execute, capturedPlayerId, utteranceSequence, whispering
							)
					);
				}
		);
	}

	static boolean captureWhileGranted(MinecraftServer server, UUID playerId, Runnable capture) {
		return CONSENT_CAPTURE.capture(server, playerId, capture);
	}

	private static ConsentCapture resolveConsentCapture() {
		try {
			dev.agaminggod.arenaagents.server.voice.VoiceConsentRegistry.class.getMethod(
					"captureWhileGranted", MinecraftServer.class, UUID.class, Runnable.class
			);
			return ModernConsentCapture.INSTANCE;
		} catch (NoSuchMethodException legacyCore) {
			return (server, playerId, capture) -> {
				if (!dev.agaminggod.arenaagents.server.voice.VoiceConsentRegistry.granted(server, playerId)) {
					return false;
				}
				capture.run();
				return true;
			};
		}
	}

	@Override
	public void close() {
		engine.close();
		inputActivity.clear();
	}

	@Override
	public void cancel(UUID playerId) {
		engine.cancel(playerId);
		inputActivity.cancel(playerId);
	}

	private static VoiceInputReporter resolveInputReporter() {
		try {
			Class<?> reporter = Class.forName(
					"dev.agaminggod.arenaagents.server.voice.VoiceInputVerboseReporter",
					false,
					HumanSpeechCapture.class.getClassLoader()
			);
			Method reportAudience = reporter.getMethod(
					"reportAudience", MinecraftServer.class, ProximitySpeechAudience.class, String.class
			);
			Method reportSystem = reporter.getMethod("reportSystem", MinecraftServer.class, String.class);
			return new VoiceInputReporter() {
				@Override
				public void reportAudience(
						MinecraftServer server,
						ProximitySpeechAudience audience,
						String message
				) {
					invokeReporter(reportAudience, server, audience, message);
				}

				@Override
				public void reportSystem(MinecraftServer server, String message) {
					invokeReporter(reportSystem, server, message);
				}
			};
		} catch (ClassNotFoundException | NoSuchMethodException | LinkageError legacyCore) {
			return VoiceInputReporter.NO_OP;
		}
	}

	private static void invokeReporter(Method method, Object... arguments) {
		try {
			method.invoke(null, arguments);
		} catch (IllegalAccessException | InvocationTargetException | RuntimeException ignored) {
			// Verbose reporting is optional and cannot interrupt microphone capture.
		}
	}

	static final class InputActivityAdapter<S, A> {
		private final java.util.Map<InputKey, Context<S, A>> contexts =
				new java.util.concurrent.ConcurrentHashMap<>();
		private final Gateway<S, A> gateway;
		private final Reporter<S, A> reporter;

		InputActivityAdapter(Gateway<S, A> gateway, Reporter<S, A> reporter) {
			this.gateway = java.util.Objects.requireNonNull(gateway, "gateway must not be null");
			this.reporter = java.util.Objects.requireNonNull(reporter, "reporter must not be null");
		}

		SpeechCaptureEngine.TranscriptDelivery begin(
				S server, Executor executor, UUID playerId, long utteranceSequence, boolean whispering
		) {
			InputKey key = new InputKey(playerId, utteranceSequence);
			Context<S, A> context = new Context<>(server, executor);
			contexts.put(key, context);
			try {
				// The first decoded speech packet is already handled on the Minecraft server
				// thread. Capture the audience before returning so recognition and reporting
				// cannot race the snapshot that delivery must use.
				context.audience = Optional.ofNullable(gateway.capture(server, playerId, whispering))
						.orElseGet(Optional::empty);
				context.audienceReady = true;
			} catch (RuntimeException ignored) {
				context.audience = Optional.empty();
				context.audienceReady = true;
				// A failed snapshot is represented as an empty audience. The speech
				// lifecycle remains recoverable and the next utterance can retry it.
			}
			return (ignoredPlayer, transcript, ignoredWhispering) -> deliver(key, context, transcript);
		}

		void report(SpeechCaptureEngine.InputActivity activity) {
			InputKey key = new InputKey(activity.playerId(), activity.utteranceSequence());
			Context<S, A> context = contexts.get(key);
			if (context == null) return;
			context.executor.execute(() -> {
				if (contexts.get(key) != context) return;
				Optional<A> audience = context.audience;
				if (activity.phase() == SpeechCaptureEngine.InputActivity.Phase.RECEIVED) {
					if (!context.audienceReady) {
						reporter.reportSystem(context.server,
								"Voice audio was received, but agents were not ready to listen.");
					} else if (audience.isEmpty() || !reporter.hasRecipients(audience.get())) {
						reporter.reportSystem(context.server,
								"Voice audio was received, but no agent was in range.");
					} else {
						reporter.reportAudience(context.server, audience.get(), "Receiving nearby speech.");
					}
					return;
				}
				if (audience.isPresent() && reporter.hasRecipients(audience.get())) {
					reporter.reportAudience(context.server, audience.get(), message(activity.phase()));
				} else {
					reporter.reportSystem(context.server, message(activity.phase()));
				}
				if (activity.phase() == SpeechCaptureEngine.InputActivity.Phase.NO_SPEECH
						|| activity.phase() == SpeechCaptureEngine.InputActivity.Phase.FAILED) {
					contexts.remove(key, context);
				}
			});
		}

		private void deliver(InputKey key, Context<S, A> context, String transcript) {
			if (contexts.get(key) != context) return;
			try {
				Optional<A> audience = context.audience;
				if (audience.isEmpty()) {
					reporter.reportSystem(context.server,
							"Speech was recognized, but no agent received it.");
					return;
				}
				int delivered = gateway.deliver(context.server, audience.get(), transcript);
				if (delivered < 1) {
					reporter.reportSystem(context.server, "Speech was recognized, but no agent received it.");
				} else {
					reporter.reportAudience(context.server, audience.get(),
							"Nearby speech was delivered for processing.");
				}
			} catch (RuntimeException ignored) {
				reporter.reportSystem(context.server,
						"Speech was recognized, but delivery was interrupted. Listening will continue.");
			} finally {
				contexts.remove(key, context);
			}
		}

		void cancel(UUID playerId) {
			contexts.keySet().removeIf(key -> key.playerId().equals(playerId));
		}

		void clear() {
			contexts.clear();
		}

		private static String message(SpeechCaptureEngine.InputActivity.Phase phase) {
			return switch (phase) {
				case PROCESSING -> "Processing nearby speech.";
				case RECOGNIZED -> "Nearby speech was recognized.";
				case NO_SPEECH -> "No speech was recognized. Listening will continue.";
				case FAILED -> "Speech recognition was interrupted. Listening will retry automatically.";
				case RECEIVED -> "Receiving nearby speech.";
			};
		}

		interface Gateway<S, A> {
			Optional<A> capture(S server, UUID playerId, boolean whispering);
			int deliver(S server, A audience, String transcript);
		}

		interface Reporter<S, A> {
			boolean hasRecipients(A audience);
			void reportAudience(S server, A audience, String message);
			void reportSystem(S server, String message);
		}

		private record InputKey(UUID playerId, long utteranceSequence) {
		}

		private static final class Context<S, A> {
			private final S server;
			private final Executor executor;
			private volatile Optional<A> audience = Optional.empty();
			private volatile boolean audienceReady;

			private Context(S server, Executor executor) {
				this.server = server;
				this.executor = executor;
			}
		}
	}

	private static SpeechCaptureEngine.Decoder decoder(OpusDecoder decoder) {
		if (decoder == null) throw new IllegalStateException("Simple Voice Chat did not create an Opus decoder");
		return new SpeechCaptureEngine.Decoder() {
			@Override public short[] decode(byte[] opus) { return decoder.decode(opus); }
			@Override public void close() { decoder.close(); }
		};
	}

	@FunctionalInterface
	private interface ConsentCapture {
		boolean capture(MinecraftServer server, UUID playerId, Runnable capture);
	}

	private interface VoiceInputReporter {
		VoiceInputReporter NO_OP = new VoiceInputReporter() {
			@Override public void reportAudience(MinecraftServer server, ProximitySpeechAudience audience, String message) { }
			@Override public void reportSystem(MinecraftServer server, String message) { }
		};

		void reportAudience(MinecraftServer server, ProximitySpeechAudience audience, String message);

		void reportSystem(MinecraftServer server, String message);
	}

	private static final class ModernConsentCapture implements ConsentCapture {
		private static final ModernConsentCapture INSTANCE = new ModernConsentCapture();

		@Override
		public boolean capture(MinecraftServer server, UUID playerId, Runnable capture) {
			return dev.agaminggod.arenaagents.server.voice.VoiceConsentRegistry.captureWhileGranted(
					server, playerId, capture
			);
		}
	}
}
