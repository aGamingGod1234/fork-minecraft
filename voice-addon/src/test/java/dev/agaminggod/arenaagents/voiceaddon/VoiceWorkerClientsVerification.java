package dev.agaminggod.arenaagents.voiceaddon;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import dev.agaminggod.arenaagents.agent.AgentId;
import dev.agaminggod.arenaagents.server.voice.VoiceRequest;
import dev.agaminggod.arenaagents.server.voice.VoiceSubsystemConfiguration;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

final class VoiceWorkerClientsVerification {
	private static final String SECRET = "voice-addon-verification-secret";
	private static final AgentId AGENT = AgentId.parse("00000000-0000-4000-8000-000000000001");
	private static final UUID PLAYER = UUID.fromString("20000000-0000-4000-8000-000000000001");

	private VoiceWorkerClientsVerification() {
	}

	static int verify() throws Exception {
		int assertions = 0;
		assertions += verifyTtsClientSendsContractAndDecodesPcm();
		assertions += verifyPreparedSecretDoesNotReadGlobalSecretPath();
		assertions += verifyTtsClientRejectsMalformedAudioAndHttpFailure();
		assertions += verifyTtsCancellationStopsTheHttpExchange();
		assertions += verifySttCancellationStopsTheHttpExchange();
		assertions += verifySttClientSendsPcmMetadataAndBoundsTranscript();
		assertions += verifySttClientRejectsMalformedInputAndResponse();
		assertions += verifySttBackoffCodesPreserved();
		assertions += verifyClientsRejectWrongResponseMediaTypes();
		assertions += verifyConfiguredDeadlineReachesBothHttpClients();
		assertions += verifyClientsRejectUnsignedAndOversizedResponses();
		assertions += verifyStalledResponseBodyHonorsDeadline();
		return assertions;
	}

	private static int verifyStalledResponseBodyHonorsDeadline() {
		StalledInputStream stalled = new StalledInputStream();
		ImmediateResponseHttpClient http = new ImmediateResponseHttpClient(stalled);
		VoiceWorkerClient client = new VoiceWorkerClient(
				http, URI.create("http://127.0.0.1:8766/v1/tts"), SECRET, java.time.Duration.ofMillis(25)
		);
		long startedNanos = System.nanoTime();
		assertWorkerFailure("VOICE_WORKER_TIMEOUT", () -> client.synthesize(request()).join(),
				"stalled streamed response body");
		assertEquals(true, java.util.concurrent.TimeUnit.NANOSECONDS.toMillis(
				System.nanoTime() - startedNanos
		) < 1_000L, "stalled response completion remains hard-bounded");
		assertEquals(true, stalled.awaitClosed(), "response deadline closes a stalled body stream");
		return 3;
	}

	private static int verifyConfiguredDeadlineReachesBothHttpClients() {
		VoiceSubsystemConfiguration configuration = new VoiceSubsystemConfiguration(
				"http://127.0.0.1:8766/v1/tts", SECRET, 91_234
		);
		PendingHttpClient ttsHttp = new PendingHttpClient();
		CompletableFuture<short[]> synthesis = new VoiceWorkerClient(
				ttsHttp,
				URI.create(configuration.endpoint()),
				configuration.secret(),
				java.time.Duration.ofMillis(configuration.requestTimeoutMs())
		).synthesize(request());
		assertEquals(91_234L, ttsHttp.request.get().timeout().orElseThrow().toMillis(),
				"configured TTS HTTP deadline");
		synthesis.cancel(true);

		PendingHttpClient sttHttp = new PendingHttpClient();
		CompletableFuture<SpeechWorkerClient.Transcript> transcription = new SpeechWorkerClient(
				sttHttp,
				URI.create("http://127.0.0.1:8766/v1/stt"),
				configuration.secret(),
				java.time.Duration.ofMillis(configuration.requestTimeoutMs())
		).transcribe(PLAYER, 1L, false, new short[] { 1 });
		assertEquals(91_234L, sttHttp.request.get().timeout().orElseThrow().toMillis(),
				"configured STT HTTP deadline");
		transcription.cancel(true);
		return 2;
	}

	private static int verifyPreparedSecretDoesNotReadGlobalSecretPath() throws Exception {
		String oldVoiceSecret = System.getProperty("arenaagents.voiceSecretFile");
		String oldBridgeSecret = System.getProperty("arenaagents.bridgeSecretFile");
		try (WorkerServer server = new WorkerServer(exchange -> {
			assertEquals(null, exchange.getRequestHeaders().getFirst("Authorization"),
					"prepared in-memory TTS does not disclose its secret");
			assertRequestAuthentication(exchange, exchange.getRequestBody().readAllBytes());
			respondPcm(exchange, pcm(1, 2));
		})) {
			System.setProperty("arenaagents.voiceSecretFile", "missing/unreadable/voice-secret.txt");
			System.setProperty("arenaagents.bridgeSecretFile", "missing/unreadable/bridge-secret.txt");
			VoiceWorkerClient client = new VoiceWorkerClient(new VoiceSubsystemConfiguration(
					server.uri("/v1/tts").toString(), SECRET
			));
			short[] samples = client.synthesize(request()).join();
			server.assertHealthy();
			assertEquals(true, Arrays.equals(new short[] {1, 2}, samples),
					"voice client starts from worker-prevalidated memory after secret paths become unreadable");
			return 2;
		} finally {
			restoreProperty("arenaagents.voiceSecretFile", oldVoiceSecret);
			restoreProperty("arenaagents.bridgeSecretFile", oldBridgeSecret);
		}
	}

	private static int verifyTtsClientSendsContractAndDecodesPcm() throws Exception {
		try (WorkerServer server = new WorkerServer(exchange -> {
			assertEquals(null, exchange.getRequestHeaders().getFirst("Authorization"), "TTS omits bearer secret");
			assertEquals("application/json", exchange.getRequestHeaders().getFirst("Content-Type"), "TTS content type");
			byte[] requestBody = exchange.getRequestBody().readAllBytes();
			assertRequestAuthentication(exchange, requestBody);
			JsonObject payload = JsonParser.parseString(new String(requestBody, StandardCharsets.UTF_8)).getAsJsonObject();
			assertEquals(AGENT.toString(), payload.get("agentId").getAsString(), "TTS agent id");
			assertEquals("Testing voice.", payload.get("text").getAsString(), "TTS text");
			assertEquals("voice.auto.v1", payload.get("profileId").getAsString(), "TTS profile id");
			assertEquals(48, payload.get("radius").getAsInt(), "TTS radius");
			assertEquals(12L, payload.get("conversationSequence").getAsLong(), "TTS sequence");
			respondPcm(exchange, pcm(-32_768, -1, 0, 1, 32_767));
		})) {
			VoiceWorkerClient client = new VoiceWorkerClient(HttpClient.newHttpClient(), server.uri("/v1/tts"), SECRET);
			short[] actual = client.synthesize(new VoiceRequest(
					AGENT, "Testing voice.", "voice.auto.v1", 48, 12L
			)).join();
			server.assertHealthy();
			assertEquals(true, Arrays.equals(new short[] { -32_768, -1, 0, 1, 32_767 }, actual), "TTS PCM decode");
		}
		return 8;
	}

	private static int verifyTtsClientRejectsMalformedAudioAndHttpFailure() throws Exception {
		try (WorkerServer malformed = new WorkerServer(exchange -> respondPcm(exchange, new byte[] { 1, 2, 3 }))) {
			VoiceWorkerClient client = new VoiceWorkerClient(HttpClient.newHttpClient(), malformed.uri("/v1/tts"), SECRET);
			assertWorkerFailure("VOICE_WORKER_AUDIO", () -> client.synthesize(request()).join(), "odd PCM response");
			malformed.assertHealthy();
		}
		try (WorkerServer unavailable = new WorkerServer(exchange -> respond(
				exchange, 503, "offline".getBytes(StandardCharsets.UTF_8), "text/plain"
		))) {
			VoiceWorkerClient client = new VoiceWorkerClient(HttpClient.newHttpClient(), unavailable.uri("/v1/tts"), SECRET);
			assertWorkerFailure("VOICE_WORKER_HTTP", () -> client.synthesize(request()).join(), "TTS HTTP failure");
			unavailable.assertHealthy();
		}
		try (WorkerServer providerUnavailable = new WorkerServer(exchange -> respond(
				exchange,
				502,
				"{\"code\":\"TTS_UNAVAILABLE\",\"message\":\"secret must-not-reach-logs\"}"
						.getBytes(StandardCharsets.UTF_8),
				"application/json"
		))) {
			VoiceWorkerClient client = new VoiceWorkerClient(
					HttpClient.newHttpClient(), providerUnavailable.uri("/v1/tts"), SECRET
			);
			assertWorkerFailure("TTS_UNAVAILABLE", () -> client.synthesize(request()).join(),
					"TTS provider error code");
			providerUnavailable.assertHealthy();
		}
		return 3;
	}

	private static int verifyTtsCancellationStopsTheHttpExchange() {
		PendingHttpClient http = new PendingHttpClient();
		VoiceWorkerClient client = new VoiceWorkerClient(http, URI.create("http://127.0.0.1:8766/v1/tts"), SECRET);
		CompletableFuture<short[]> synthesis = client.synthesize(request());
		assertEquals(true, synthesis.cancel(true), "TTS request cancellation is accepted");
		assertEquals(true, http.response.isCancelled(), "TTS cancellation reaches the HTTP exchange");
		return 2;
	}

	private static int verifySttCancellationStopsTheHttpExchange() {
		PendingHttpClient http = new PendingHttpClient();
		SpeechWorkerClient client = new SpeechWorkerClient(
				http, URI.create("http://127.0.0.1:8766/v1/stt"), SECRET
		);
		CompletableFuture<SpeechWorkerClient.Transcript> transcription = client.transcribe(
				PLAYER, 18L, false, new short[] { 1, 2 }
		);
		assertEquals(true, transcription.cancel(true), "STT request cancellation is accepted");
		assertEquals(true, http.response.isCancelled(), "STT cancellation reaches the HTTP exchange");
		return 2;
	}

	private static int verifyClientsRejectWrongResponseMediaTypes() throws Exception {
		try (WorkerServer wrongTtsType = new WorkerServer(exchange -> respond(
				exchange, 200, pcm(1, 2), "text/plain"
		))) {
			VoiceWorkerClient client = new VoiceWorkerClient(
					HttpClient.newHttpClient(), wrongTtsType.uri("/v1/tts"), SECRET
			);
			assertWorkerFailure("VOICE_WORKER_AUDIO", () -> client.synthesize(request()).join(),
					"TTS response media type");
			wrongTtsType.assertHealthy();
		}
		try (WorkerServer wrongSttType = new WorkerServer(exchange -> respond(
				exchange, 200, "{\"transcript\":\"hello\",\"confidence\":0.8}".getBytes(StandardCharsets.UTF_8),
				"text/plain"
		))) {
			SpeechWorkerClient client = new SpeechWorkerClient(
					HttpClient.newHttpClient(), wrongSttType.uri("/v1/stt"), SECRET
			);
			assertWorkerFailure("STT_WORKER_RESPONSE", () -> client.transcribe(
					PLAYER, 1L, false, new short[] { 1 }
			).join(), "STT response media type");
			wrongSttType.assertHealthy();
		}
		return 2;
	}

	private static int verifySttClientSendsPcmMetadataAndBoundsTranscript() throws Exception {
		String transcript = "a".repeat(513);
		try (WorkerServer server = new WorkerServer(exchange -> {
			assertEquals(null, exchange.getRequestHeaders().getFirst("Authorization"), "STT omits bearer secret");
			assertEquals("audio/l16;rate=48000;channels=1", exchange.getRequestHeaders().getFirst("Content-Type"),
					"STT content type");
			assertEquals(PLAYER.toString(), exchange.getRequestHeaders().getFirst("X-Player-Id"), "STT player id");
			assertEquals("17", exchange.getRequestHeaders().getFirst("X-Utterance-Sequence"), "STT sequence");
			assertEquals("true", exchange.getRequestHeaders().getFirst("X-Whispering"), "STT whisper state");
			byte[] requestBody = exchange.getRequestBody().readAllBytes();
			assertRequestAuthentication(exchange, requestBody);
			assertEquals(true, Arrays.equals(pcm(-32_768, 0, 32_767), requestBody),
					"STT PCM encoding");
			respond(exchange, 200, ("{\"transcript\":\"" + transcript + "\",\"confidence\":0.75}")
					.getBytes(StandardCharsets.UTF_8), "application/json");
		})) {
			SpeechWorkerClient client = new SpeechWorkerClient(HttpClient.newHttpClient(), server.uri("/v1/stt"), SECRET);
			SpeechWorkerClient.Transcript actual = client.transcribe(
					PLAYER, 17L, true, new short[] { -32_768, 0, 32_767 }
			).join();
			server.assertHealthy();
			assertEquals(512, actual.text().codePointCount(0, actual.text().length()), "STT transcript bound");
			assertEquals(0.75D, actual.confidence(), "STT confidence");
		}
		return 8;
	}

	private static int verifyClientsRejectUnsignedAndOversizedResponses() throws Exception {
		try (WorkerServer unsigned = new WorkerServer(exchange -> respondUnsigned(
				exchange, 200, pcm(1, 2), "audio/l16", java.util.Map.of(
						"X-Audio-Sample-Rate", "48000", "X-Audio-Channels", "1"
				)
		))) {
			VoiceWorkerClient client = new VoiceWorkerClient(HttpClient.newHttpClient(), unsigned.uri("/v1/tts"), SECRET);
			assertWorkerFailure("VOICE_WORKER_AUTHENTICATION", () -> client.synthesize(request()).join(),
					"unsigned TTS response");
			unsigned.assertHealthy();
		}
		try (WorkerServer oversized = new WorkerServer(exchange -> {
			exchange.getResponseHeaders().set("Content-Type", "application/json");
			exchange.sendResponseHeaders(200, 0);
			exchange.getResponseBody().write(new byte[9_000]);
			exchange.close();
		})) {
			SpeechWorkerClient client = new SpeechWorkerClient(
					HttpClient.newHttpClient(), oversized.uri("/v1/stt"), SECRET
			);
			assertWorkerFailure("VOICE_WORKER_RESPONSE", () -> client.transcribe(
					PLAYER, 1L, false, new short[] { 1 }
			).join(), "oversized declared STT response");
			oversized.assertHealthy();
		}
		return 2;
	}

	private static int verifySttClientRejectsMalformedInputAndResponse() throws Exception {
		try (WorkerServer unused = new WorkerServer(exchange -> respond(
				exchange, 500, new byte[0], "application/json"
		))) {
			SpeechWorkerClient client = new SpeechWorkerClient(HttpClient.newHttpClient(), unused.uri("/v1/stt"), SECRET);
			assertWorkerFailure("STT_WORKER_AUDIO", () -> client.transcribe(PLAYER, 1L, false, new short[0]).join(),
					"empty STT samples");
		}
		try (WorkerServer malformed = new WorkerServer(exchange -> respond(
				exchange, 200, "not-json".getBytes(StandardCharsets.UTF_8), "application/json"
		))) {
			SpeechWorkerClient client = new SpeechWorkerClient(HttpClient.newHttpClient(), malformed.uri("/v1/stt"), SECRET);
			assertWorkerFailure("STT_WORKER_RESPONSE", () -> client.transcribe(
					PLAYER, 1L, false, new short[] { 1 }
			).join(), "malformed STT response");
			malformed.assertHealthy();
		}
		return 2;
	}

	private static int verifySttBackoffCodesPreserved() throws Exception {
		try (WorkerServer unavailable = new WorkerServer(exchange -> respond(
				exchange,
				503,
				"{\"code\":\"STT_UNAVAILABLE\",\"message\":\"Speech recognition is not configured\"}"
						.getBytes(StandardCharsets.UTF_8),
				"application/json"
		))) {
			SpeechWorkerClient client = new SpeechWorkerClient(
					HttpClient.newHttpClient(), unavailable.uri("/v1/stt"), SECRET
			);
			assertWorkerFailure("STT_UNAVAILABLE", () -> client.transcribe(
					PLAYER, 1L, false, new short[] { 1 }
			).join(), "STT unavailable response");
			unavailable.assertHealthy();
		}
		try (WorkerServer saturated = new WorkerServer(exchange -> respond(
				exchange,
				429,
				"{\"code\":\"STT_CAPACITY\",\"message\":\"Speech recognition is busy\"}"
						.getBytes(StandardCharsets.UTF_8),
				"application/json"
		))) {
			SpeechWorkerClient client = new SpeechWorkerClient(
					HttpClient.newHttpClient(), saturated.uri("/v1/stt"), SECRET
			);
			assertWorkerFailure("STT_CAPACITY", () -> client.transcribe(
					PLAYER, 1L, false, new short[] { 1 }
			).join(), "STT capacity response");
			saturated.assertHealthy();
		}
		try (WorkerServer rateLimited = new WorkerServer(exchange -> {
			exchange.getResponseHeaders().set("Retry-After", "12");
			respond(
					exchange,
					429,
					"{\"code\":\"STT_RATE_LIMITED\",\"message\":\"Speech provider rate limit reached\"}"
							.getBytes(StandardCharsets.UTF_8),
					"application/json"
			);
		})) {
			SpeechWorkerClient client = new SpeechWorkerClient(
					HttpClient.newHttpClient(), rateLimited.uri("/v1/stt"), SECRET
			);
			VoiceWorkerClient.VoiceWorkerException failure = assertWorkerFailure("STT_RATE_LIMITED", () -> client.transcribe(
					PLAYER, 1L, false, new short[] { 1 }
			).join(), "STT provider rate-limit response");
			assertEquals(TimeUnit.SECONDS.toNanos(12L), failure.retryAfterNanos(),
					"STT provider Retry-After response");
			rateLimited.assertHealthy();
		}
		return 4;
	}

	private static VoiceRequest request() {
		return new VoiceRequest(AGENT, "Testing voice.", "voice.auto.v1", 48, 1L);
	}

	private static byte[] pcm(int... samples) {
		ByteBuffer buffer = ByteBuffer.allocate(samples.length * 2).order(ByteOrder.LITTLE_ENDIAN);
		for (int sample : samples) buffer.putShort((short) sample);
		return buffer.array();
	}

	private static void respond(HttpExchange exchange, int status, byte[] body, String contentType) throws IOException {
		exchange.getResponseHeaders().set("Content-Type", contentType);
		String nonce = exchange.getRequestHeaders().getFirst("X-Voice-Nonce");
		exchange.getResponseHeaders().set("X-Voice-Response-Signature",
				VoiceHttpAuthentication.responseSignature(SECRET, nonce, status, contentType, body));
		exchange.sendResponseHeaders(status, body.length);
		exchange.getResponseBody().write(body);
		exchange.close();
	}

	private static void respondUnsigned(
			HttpExchange exchange,
			int status,
			byte[] body,
			String contentType,
			java.util.Map<String, String> headers
	) throws IOException {
		exchange.getResponseHeaders().set("Content-Type", contentType);
		headers.forEach((name, value) -> exchange.getResponseHeaders().set(name, value));
		exchange.sendResponseHeaders(status, body.length);
		exchange.getResponseBody().write(body);
		exchange.close();
	}

	private static void assertRequestAuthentication(HttpExchange exchange, byte[] body) {
		String nonce = exchange.getRequestHeaders().getFirst("X-Voice-Nonce");
		String timestamp = exchange.getRequestHeaders().getFirst("X-Voice-Timestamp");
		String actual = exchange.getRequestHeaders().getFirst("X-Voice-Signature");
		assertEquals(true, nonce != null, "voice request nonce");
		assertEquals(true, timestamp != null, "voice request timestamp");
		String contentType = exchange.getRequestHeaders().getFirst("Content-Type");
		java.util.Map<String, String> identity = new java.util.LinkedHashMap<>();
		identity.put("x-player-id", exchange.getRequestHeaders().getFirst("X-Player-Id"));
		identity.put("x-utterance-sequence", exchange.getRequestHeaders().getFirst("X-Utterance-Sequence"));
		identity.put("x-whispering", exchange.getRequestHeaders().getFirst("X-Whispering"));
		String expected = VoiceHttpAuthentication.requestSignature(
				SECRET,
				exchange.getRequestMethod(),
				exchange.getRequestURI().getRawPath(),
				Long.parseLong(timestamp),
				nonce,
				VoiceHttpAuthentication.canonicalContentType(contentType),
				VoiceHttpAuthentication.canonicalIdentity(identity),
				VoiceHttpAuthentication.sha256Hex(body)
		);
		assertEquals(expected, actual, "voice request HMAC binds metadata and body");
	}

	private static void respondPcm(HttpExchange exchange, byte[] body) throws IOException {
		exchange.getResponseHeaders().set("X-Audio-Sample-Rate", "48000");
		exchange.getResponseHeaders().set("X-Audio-Channels", "1");
		respond(exchange, 200, body, "audio/L16");
	}

	private static VoiceWorkerClient.VoiceWorkerException assertWorkerFailure(
			String expectedCode, Runnable action, String message
	) {
		try {
			action.run();
			throw new AssertionError(message + ": expected failure " + expectedCode);
		} catch (CompletionException exception) {
			Throwable cause = exception.getCause();
			if (!(cause instanceof VoiceWorkerClient.VoiceWorkerException workerFailure)) {
				throw new AssertionError(message + ": unexpected failure " + cause, cause);
			}
			assertEquals(expectedCode, workerFailure.code(), message);
			return workerFailure;
		}
	}

	private static void assertEquals(Object expected, Object actual, String message) {
		if (!Objects.equals(expected, actual)) {
			throw new AssertionError(message + ": expected=" + expected + ", actual=" + actual);
		}
	}

	private static void restoreProperty(String name, String value) {
		if (value == null) System.clearProperty(name);
		else System.setProperty(name, value);
	}

	@FunctionalInterface
	private interface Handler {
		void handle(HttpExchange exchange) throws Exception;
	}

	private static final class WorkerServer implements AutoCloseable {
		private final HttpServer server;
		private final AtomicReference<Throwable> failure = new AtomicReference<>();

		private WorkerServer(Handler handler) throws IOException {
			server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
			server.createContext("/", exchange -> {
				try {
					handler.handle(exchange);
				} catch (Throwable throwable) {
					failure.compareAndSet(null, throwable);
					if (exchange.getResponseCode() < 0) respond(
							exchange, 500, "fixture failure".getBytes(StandardCharsets.UTF_8), "text/plain"
					);
				}
			});
			server.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
			server.start();
		}

		private URI uri(String path) {
			return URI.create("http://127.0.0.1:" + server.getAddress().getPort() + path);
		}

		private void assertHealthy() {
			Throwable throwable = failure.get();
			if (throwable != null) throw new AssertionError("worker fixture failed", throwable);
		}

		@Override
		public void close() {
			server.stop(0);
		}
	}

	private static final class PendingHttpClient extends HttpClient {
		private final CompletableFuture<java.net.http.HttpResponse<InputStream>> response = new CompletableFuture<>();
		private final AtomicReference<java.net.http.HttpRequest> request = new AtomicReference<>();

		@Override public java.util.Optional<java.net.CookieHandler> cookieHandler() { return java.util.Optional.empty(); }
		@Override public java.util.Optional<java.time.Duration> connectTimeout() { return java.util.Optional.empty(); }
		@Override public Redirect followRedirects() { return Redirect.NEVER; }
		@Override public java.util.Optional<java.net.ProxySelector> proxy() { return java.util.Optional.empty(); }
		@Override public javax.net.ssl.SSLContext sslContext() { return null; }
		@Override public javax.net.ssl.SSLParameters sslParameters() { return new javax.net.ssl.SSLParameters(); }
		@Override public java.util.Optional<java.net.Authenticator> authenticator() { return java.util.Optional.empty(); }
		@Override public Version version() { return Version.HTTP_1_1; }
		@Override public java.util.Optional<java.util.concurrent.Executor> executor() { return java.util.Optional.empty(); }
		@Override public <T> java.net.http.HttpResponse<T> send(
				java.net.http.HttpRequest request, java.net.http.HttpResponse.BodyHandler<T> handler
		) { throw new UnsupportedOperationException(); }
		@Override @SuppressWarnings("unchecked") public <T> CompletableFuture<java.net.http.HttpResponse<T>> sendAsync(
				java.net.http.HttpRequest request, java.net.http.HttpResponse.BodyHandler<T> handler
		) {
			this.request.set(request);
			return (CompletableFuture<java.net.http.HttpResponse<T>>) (CompletableFuture<?>) response;
		}
		@Override public <T> CompletableFuture<java.net.http.HttpResponse<T>> sendAsync(
				java.net.http.HttpRequest request,
				java.net.http.HttpResponse.BodyHandler<T> handler,
				java.net.http.HttpResponse.PushPromiseHandler<T> pushPromiseHandler
		) { return sendAsync(request, handler); }
	}

	private static final class ImmediateResponseHttpClient extends HttpClient {
		private final InputStream body;

		private ImmediateResponseHttpClient(InputStream body) {
			this.body = body;
		}

		@Override public java.util.Optional<java.net.CookieHandler> cookieHandler() { return java.util.Optional.empty(); }
		@Override public java.util.Optional<java.time.Duration> connectTimeout() { return java.util.Optional.empty(); }
		@Override public Redirect followRedirects() { return Redirect.NEVER; }
		@Override public java.util.Optional<java.net.ProxySelector> proxy() { return java.util.Optional.empty(); }
		@Override public javax.net.ssl.SSLContext sslContext() { return null; }
		@Override public javax.net.ssl.SSLParameters sslParameters() { return new javax.net.ssl.SSLParameters(); }
		@Override public java.util.Optional<java.net.Authenticator> authenticator() { return java.util.Optional.empty(); }
		@Override public Version version() { return Version.HTTP_1_1; }
		@Override public java.util.Optional<java.util.concurrent.Executor> executor() { return java.util.Optional.empty(); }
		@Override public <T> java.net.http.HttpResponse<T> send(
				java.net.http.HttpRequest request, java.net.http.HttpResponse.BodyHandler<T> handler
		) { throw new UnsupportedOperationException(); }
		@Override @SuppressWarnings("unchecked") public <T> CompletableFuture<java.net.http.HttpResponse<T>> sendAsync(
				java.net.http.HttpRequest request, java.net.http.HttpResponse.BodyHandler<T> handler
		) {
			java.net.http.HttpResponse<InputStream> response = new java.net.http.HttpResponse<>() {
				@Override public int statusCode() { return 200; }
				@Override public java.net.http.HttpRequest request() { return request; }
				@Override public java.util.Optional<java.net.http.HttpResponse<InputStream>> previousResponse() {
					return java.util.Optional.empty();
				}
				@Override public java.net.http.HttpHeaders headers() {
					return java.net.http.HttpHeaders.of(java.util.Map.of(), (name, value) -> true);
				}
				@Override public InputStream body() { return body; }
				@Override public java.util.Optional<javax.net.ssl.SSLSession> sslSession() {
					return java.util.Optional.empty();
				}
				@Override public URI uri() { return request.uri(); }
				@Override public Version version() { return Version.HTTP_1_1; }
			};
			return (CompletableFuture<java.net.http.HttpResponse<T>>) (CompletableFuture<?>)
					CompletableFuture.completedFuture(response);
		}
		@Override public <T> CompletableFuture<java.net.http.HttpResponse<T>> sendAsync(
				java.net.http.HttpRequest request,
				java.net.http.HttpResponse.BodyHandler<T> handler,
				java.net.http.HttpResponse.PushPromiseHandler<T> pushPromiseHandler
		) { return sendAsync(request, handler); }
	}

	private static final class StalledInputStream extends InputStream {
		private final CountDownLatch released = new CountDownLatch(1);
		private volatile boolean closed;

		@Override
		public int read() throws IOException {
			try {
				released.await();
			} catch (InterruptedException exception) {
				Thread.currentThread().interrupt();
				throw new IOException("interrupted", exception);
			}
			return -1;
		}

		@Override
		public void close() {
			closed = true;
			released.countDown();
		}

		private boolean awaitClosed() {
			try {
				return released.await(1, java.util.concurrent.TimeUnit.SECONDS) && closed;
			} catch (InterruptedException exception) {
				Thread.currentThread().interrupt();
				return false;
			}
		}
	}
}
