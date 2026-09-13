package dev.agaminggod.arenaagents.voiceaddon;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.agaminggod.arenaagents.server.voice.VoiceSubsystemConfiguration;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.time.Duration;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

final class SpeechWorkerClient {
	private static final int MAX_SAMPLES = 48_000 * 20;
	private static final Set<String> STT_WORKER_ERROR_CODES = Set.of(
			"STT_CAPACITY",
			"STT_RATE_LIMITED",
			"STT_TIMEOUT",
			"STT_UNAVAILABLE"
	);
	private final HttpClient client;
	private final URI endpoint;
	private final String secret;
	private final Duration requestTimeout;

	SpeechWorkerClient(VoiceSubsystemConfiguration configuration) {
		this(
				defaultClient(),
				speechEndpoint(configuration.endpoint()),
				configuration.secret(),
				Duration.ofMillis(configuration.requestTimeoutMs())
		);
	}

	SpeechWorkerClient(HttpClient client, URI endpoint, String secret) {
		this(client, endpoint, secret, Duration.ofMillis(VoiceSubsystemConfiguration.DEFAULT_REQUEST_TIMEOUT_MS));
	}

	SpeechWorkerClient(HttpClient client, URI endpoint, String secret, Duration requestTimeout) {
		this.client = java.util.Objects.requireNonNull(client, "client must not be null");
		this.endpoint = java.util.Objects.requireNonNull(endpoint, "endpoint must not be null");
		if (secret == null || secret.length() < 16) throw new IllegalArgumentException("secret is too short");
		this.secret = secret;
		this.requestTimeout = java.util.Objects.requireNonNull(requestTimeout, "requestTimeout must not be null");
	}

	CompletableFuture<Transcript> transcribe(
			UUID playerId,
			long utteranceSequence,
			boolean whispering,
			short[] samples
	) {
		if (playerId == null || utteranceSequence < 1L || samples == null
				|| samples.length == 0 || samples.length > MAX_SAMPLES) {
			return CompletableFuture.failedFuture(new VoiceWorkerClient.VoiceWorkerException(
					"STT_WORKER_AUDIO", "Speech input must be at most 20 seconds of 48 kHz mono PCM"
			));
		}
		ByteBuffer pcm = ByteBuffer.allocate(samples.length * 2).order(ByteOrder.LITTLE_ENDIAN);
		pcm.asShortBuffer().put(samples);
		byte[] requestBody = pcm.array();
		String contentType = "audio/l16;rate=48000;channels=1";
		Map<String, String> identityHeaders = Map.of(
				"x-player-id", playerId.toString(),
				"x-utterance-sequence", Long.toString(utteranceSequence),
				"x-whispering", Boolean.toString(whispering)
		);
		HttpRequest.Builder requestBuilder = HttpRequest.newBuilder(endpoint)
				.timeout(requestTimeout)
				.header("Content-Type", contentType)
				.header("X-Player-Id", playerId.toString())
				.header("X-Utterance-Sequence", Long.toString(utteranceSequence))
				.header("X-Whispering", Boolean.toString(whispering));
		String requestNonce = VoiceHttpAuthentication.authenticate(
				requestBuilder, secret, "POST", endpoint, contentType, identityHeaders, requestBody
		);
		HttpRequest request = requestBuilder
				.POST(HttpRequest.BodyPublishers.ofByteArray(requestBody))
				.build();
		CompletableFuture<HttpResponse<InputStream>> exchange = client.sendAsync(
				request, HttpResponse.BodyHandlers.ofInputStream()
		);
		AtomicReference<CompletableFuture<byte[]>> bodyRead = new AtomicReference<>();
		CompletableFuture<Transcript> result = exchange.thenCompose(response -> {
					CompletableFuture<byte[]> reading = VoiceHttpAuthentication.readAuthenticatedBody(
							response, secret, requestNonce, 8 * 1024, requestTimeout, () -> exchange.cancel(true)
					);
					bodyRead.set(reading);
					return reading.thenApply(responseBytes -> decodeResponse(response, responseBytes));
				});
		result.whenComplete((transcript, failure) -> {
			if (result.isCancelled()) {
				CompletableFuture<byte[]> reading = bodyRead.get();
				if (reading != null) reading.cancel(true);
				exchange.cancel(true);
			}
		});
		return result;
	}

	private static Transcript decodeResponse(HttpResponse<?> response, byte[] responseBytes) {
					if (response.statusCode() != 200) {
						throw workerHttpFailure(response, responseBytes);
					}
					try {
						String contentType = response.headers().firstValue("Content-Type").orElse("")
								.toLowerCase(Locale.ROOT).split(";", 2)[0].strip();
						if (!contentType.equals("application/json")) {
							throw new VoiceWorkerClient.VoiceWorkerException(
									"STT_WORKER_RESPONSE", "Speech worker returned a non-JSON transcript"
							);
						}
						JsonObject payload = JsonParser.parseString(
								new String(responseBytes, java.nio.charset.StandardCharsets.UTF_8)
						).getAsJsonObject();
						if (!payload.has("transcript") || !payload.get("transcript").isJsonPrimitive()
								|| !payload.has("confidence") || !payload.get("confidence").isJsonPrimitive()) {
							throw new IllegalArgumentException("missing transcript fields");
						}
						String transcript = payload.get("transcript").getAsString().strip();
						if (transcript.codePointCount(0, transcript.length()) > 512) {
							transcript = transcript.substring(0, transcript.offsetByCodePoints(0, 512));
						}
						double confidence = payload.get("confidence").getAsDouble();
						if (!Double.isFinite(confidence)) throw new IllegalArgumentException("confidence is not finite");
						return new Transcript(transcript, Math.max(0.0D, Math.min(1.0D, confidence)));
					} catch (RuntimeException exception) {
						if (exception instanceof VoiceWorkerClient.VoiceWorkerException workerException) {
							throw workerException;
						}
						throw new VoiceWorkerClient.VoiceWorkerException(
								"STT_WORKER_RESPONSE", "Speech worker returned an invalid transcript", exception
						);
					}
	}

	private static VoiceWorkerClient.VoiceWorkerException workerHttpFailure(HttpResponse<?> response, byte[] body) {
		String code = "STT_WORKER_HTTP";
		String contentType = response.headers().firstValue("Content-Type").orElse("")
				.toLowerCase(Locale.ROOT).split(";", 2)[0].strip();
		if (contentType.equals("application/json")) {
			try {
				JsonObject payload = JsonParser.parseString(
						new String(body, java.nio.charset.StandardCharsets.UTF_8)
				).getAsJsonObject();
				if (payload.has("code") && payload.get("code").isJsonPrimitive()) {
					String candidate = payload.get("code").getAsString();
					if (STT_WORKER_ERROR_CODES.contains(candidate)) code = candidate;
				}
			} catch (RuntimeException ignored) {
				// Invalid error bodies remain a generic bounded HTTP failure.
			}
		}
		long retryAfterNanos = "STT_RATE_LIMITED".equals(code)
				? retryAfterNanos(response.headers().firstValue("Retry-After").orElse("")) : 0L;
		return new VoiceWorkerClient.VoiceWorkerException(
				code, "Speech worker returned HTTP " + response.statusCode(), retryAfterNanos
		);
	}

	private static long retryAfterNanos(String value) {
		try {
			long seconds = Long.parseLong(value.strip());
			return TimeUnit.SECONDS.toNanos(Math.max(0L, Math.min(seconds, 300L)));
		} catch (NumberFormatException ignored) {
			return 0L;
		}
	}

	private static HttpClient defaultClient() {
		return HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();
	}

	private static URI speechEndpoint(String voiceEndpoint) {
		return URI.create(voiceEndpoint.replace("/v1/tts", "/v1/stt"));
	}

	record Transcript(String text, double confidence) {
	}
}
