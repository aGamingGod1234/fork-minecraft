package dev.agaminggod.arenaagents.voiceaddon;

import com.google.gson.JsonObject;
import dev.agaminggod.arenaagents.server.voice.VoiceRequest;
import dev.agaminggod.arenaagents.server.voice.VoiceSubsystemConfiguration;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicReference;

final class VoiceWorkerClient {
	private static final int MAX_SAMPLES = 48_000 * 20;
	private static final Set<String> TTS_WORKER_ERROR_CODES = Set.of(
			"TTS_AUDIO_TOO_LONG",
			"TTS_CAPACITY",
			"TTS_MALFORMED_AUDIO",
			"TTS_PROVIDER_ERROR",
			"TTS_RATE_LIMITED",
			"TTS_TIMEOUT",
			"TTS_UNAVAILABLE"
	);
	private final HttpClient client;
	private final URI endpoint;
	private final String secret;
	private final Duration requestTimeout;

	VoiceWorkerClient(VoiceSubsystemConfiguration configuration) {
		this(
				HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build(),
				URI.create(configuration.endpoint()),
				configuration.secret(),
				Duration.ofMillis(configuration.requestTimeoutMs())
		);
	}

	VoiceWorkerClient(HttpClient client, URI endpoint, String secret) {
		this(client, endpoint, secret, Duration.ofMillis(VoiceSubsystemConfiguration.DEFAULT_REQUEST_TIMEOUT_MS));
	}

	VoiceWorkerClient(HttpClient client, URI endpoint, String secret, Duration requestTimeout) {
		this.client = java.util.Objects.requireNonNull(client, "client must not be null");
		this.endpoint = java.util.Objects.requireNonNull(endpoint, "endpoint must not be null");
		if (secret == null || secret.length() < 16) throw new IllegalArgumentException("secret is too short");
		this.secret = secret;
		this.requestTimeout = java.util.Objects.requireNonNull(requestTimeout, "requestTimeout must not be null");
	}

	CompletableFuture<short[]> synthesize(VoiceRequest request) {
		JsonObject payload = new JsonObject();
		payload.addProperty("agentId", request.agentId().toString());
		payload.addProperty("text", request.text());
		payload.addProperty("profileId", request.profileId());
		payload.addProperty("speed", request.speed());
		payload.addProperty("tone", request.tone());
		payload.addProperty("radius", request.radius());
		payload.addProperty("conversationSequence", request.conversationSequence());
		String contentType = "application/json";
		byte[] requestBody = payload.toString().getBytes(StandardCharsets.UTF_8);
		HttpRequest.Builder requestBuilder = HttpRequest.newBuilder(endpoint)
				.timeout(requestTimeout)
				.header("Content-Type", contentType);
		String requestNonce = VoiceHttpAuthentication.authenticate(
				requestBuilder, secret, "POST", endpoint, contentType, Map.of(), requestBody
		);
		HttpRequest httpRequest = requestBuilder
				.POST(HttpRequest.BodyPublishers.ofByteArray(requestBody))
				.build();
		CompletableFuture<HttpResponse<InputStream>> exchange = client.sendAsync(
				httpRequest, HttpResponse.BodyHandlers.ofInputStream()
		);
		AtomicReference<CompletableFuture<byte[]>> bodyRead = new AtomicReference<>();
		CompletableFuture<short[]> result = exchange.thenCompose(response -> {
					int maximumBytes = response.statusCode() == 200 ? MAX_SAMPLES * 2 : 8 * 1024;
					CompletableFuture<byte[]> reading = VoiceHttpAuthentication.readAuthenticatedBody(
							response, secret, requestNonce, maximumBytes, requestTimeout, () -> exchange.cancel(true)
					);
					bodyRead.set(reading);
					return reading.thenApply(bytes -> decodeResponse(response, bytes));
				});
		result.whenComplete((samples, failure) -> {
			if (result.isCancelled()) {
				CompletableFuture<byte[]> reading = bodyRead.get();
				if (reading != null) reading.cancel(true);
				exchange.cancel(true);
			}
		});
		return result;
	}

	private static short[] decodeResponse(HttpResponse<?> response, byte[] bytes) {
					if (response.statusCode() != 200) {
						throw workerHttpFailure(response, bytes);
					}
					String contentType = response.headers().firstValue("Content-Type").orElse("")
							.toLowerCase(Locale.ROOT).split(";", 2)[0].strip();
					String sampleRate = response.headers().firstValue("X-Audio-Sample-Rate").orElse("");
					String channels = response.headers().firstValue("X-Audio-Channels").orElse("");
					if (!contentType.equals("audio/l16") || !sampleRate.equals("48000") || !channels.equals("1")) {
						throw new VoiceWorkerException(
								"VOICE_WORKER_AUDIO", "Voice worker returned invalid 48 kHz mono PCM metadata"
						);
					}
					if (bytes.length == 0 || bytes.length % 2 != 0 || bytes.length > MAX_SAMPLES * 2) {
						throw new VoiceWorkerException("VOICE_WORKER_AUDIO", "Voice worker returned invalid 48 kHz mono PCM");
					}
					ByteBuffer buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);
					short[] samples = new short[bytes.length / 2];
					buffer.asShortBuffer().get(samples);
					return samples;
	}

	private static VoiceWorkerException workerHttpFailure(HttpResponse<?> response, byte[] body) {
		String code = "VOICE_WORKER_HTTP";
		String contentType = response.headers().firstValue("Content-Type").orElse("")
				.toLowerCase(Locale.ROOT).split(";", 2)[0].strip();
		if (contentType.equals("application/json") && body.length <= 1_024) {
			try {
				JsonObject payload = com.google.gson.JsonParser.parseString(
						new String(body, StandardCharsets.UTF_8)
				).getAsJsonObject();
				if (payload.has("code") && payload.get("code").isJsonPrimitive()) {
					String candidate = payload.get("code").getAsString();
					if (TTS_WORKER_ERROR_CODES.contains(candidate)) code = candidate;
				}
			} catch (RuntimeException ignored) {
				// Invalid error bodies remain a generic bounded HTTP failure.
			}
		}
		return new VoiceWorkerException(code, "Voice worker returned HTTP " + response.statusCode());
	}

	static final class VoiceWorkerException extends RuntimeException {
		private final String code;
		private final long retryAfterNanos;

		VoiceWorkerException(String code, String message) {
			this(code, message, null, 0L);
		}

		VoiceWorkerException(String code, String message, Throwable cause) {
			this(code, message, cause, 0L);
		}

		VoiceWorkerException(String code, String message, long retryAfterNanos) {
			this(code, message, null, retryAfterNanos);
		}

		private VoiceWorkerException(String code, String message, Throwable cause, long retryAfterNanos) {
			super(message, cause);
			this.code = code;
			this.retryAfterNanos = Math.max(0L, retryAfterNanos);
		}

		String code() {
			return code;
		}

		long retryAfterNanos() {
			return retryAfterNanos;
		}
	}
}
