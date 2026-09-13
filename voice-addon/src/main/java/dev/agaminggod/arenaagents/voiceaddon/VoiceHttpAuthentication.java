package dev.agaminggod.arenaagents.voiceaddon;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Instant;
import java.time.Duration;
import java.util.Base64;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

final class VoiceHttpAuthentication {
	private static final String VERSION = "arena-voice-v1";
	private static final SecureRandom RANDOM = new SecureRandom();

	private VoiceHttpAuthentication() {
	}

	static String authenticate(
			HttpRequest.Builder request,
			String secret,
			String method,
			URI endpoint,
			String contentType,
			Map<String, String> identityHeaders,
			byte[] body
	) {
		byte[] nonceBytes = new byte[24];
		RANDOM.nextBytes(nonceBytes);
		String nonce = Base64.getUrlEncoder().withoutPadding().encodeToString(nonceBytes);
		long timestamp = Instant.now().toEpochMilli();
		String signature = requestSignature(
				secret, method, endpoint.getRawPath(), timestamp, nonce,
				canonicalContentType(contentType), canonicalIdentity(identityHeaders), sha256Hex(body)
		);
		request.header("X-Voice-Nonce", nonce)
				.header("X-Voice-Timestamp", Long.toString(timestamp))
				.header("X-Voice-Signature", signature);
		return nonce;
	}

	static CompletableFuture<byte[]> readAuthenticatedBody(
			HttpResponse<InputStream> response,
			String secret,
			String requestNonce,
			int maximumBytes,
			Duration deadline,
			Runnable abortExchange
	) {
		if (maximumBytes < 1) throw new IllegalArgumentException("maximumBytes must be positive");
		Objects.requireNonNull(deadline, "deadline must not be null");
		Objects.requireNonNull(abortExchange, "abortExchange must not be null");
		if (deadline.isZero() || deadline.isNegative()) throw new IllegalArgumentException("deadline must be positive");
		String contentLength = response.headers().firstValue("Content-Length").orElse(null);
		if (contentLength != null) {
			try {
				long declared = Long.parseLong(contentLength);
				if (declared < 0L || declared > maximumBytes) throw oversizedResponse();
			} catch (NumberFormatException exception) {
				throw new VoiceWorkerClient.VoiceWorkerException(
						"VOICE_WORKER_RESPONSE", "Voice worker returned an invalid Content-Length", exception
				);
			}
		}
		InputStream input = response.body();
		CompletableFuture<byte[]> reading = CompletableFuture.supplyAsync(() -> readBounded(input, maximumBytes),
				command -> Thread.startVirtualThread(command));
		reading.orTimeout(deadline.toMillis(), TimeUnit.MILLISECONDS);
		CompletableFuture<byte[]> authenticated = reading.handle((body, failure) -> {
			if (failure != null) {
				abortAndClose(abortExchange, input);
				Throwable cause = unwrap(failure);
				if (cause instanceof TimeoutException) {
					throw new CompletionException(new VoiceWorkerClient.VoiceWorkerException(
							"VOICE_WORKER_TIMEOUT", "Voice worker response body timed out", cause
					));
				}
				if (cause instanceof VoiceWorkerClient.VoiceWorkerException workerFailure) {
					throw new CompletionException(workerFailure);
				}
				throw new CompletionException(new VoiceWorkerClient.VoiceWorkerException(
						"VOICE_WORKER_RESPONSE", "Voice worker response could not be read", cause
				));
			}
			String contentType = response.headers().firstValue("Content-Type").orElse("");
			String actual = response.headers().firstValue("X-Voice-Response-Signature").orElse("");
			String expected = responseSignature(
					secret, requestNonce, response.statusCode(), contentType, body
			);
			if (!constantTimeEquals(actual, expected)) {
				throw new CompletionException(new VoiceWorkerClient.VoiceWorkerException(
						"VOICE_WORKER_AUTHENTICATION", "Voice worker response authentication failed"
				));
			}
			return body;
		});
		authenticated.whenComplete((body, failure) -> {
			if (authenticated.isCancelled()) {
				reading.cancel(true);
				abortAndClose(abortExchange, input);
			}
		});
		return authenticated;
	}

	private static byte[] readBounded(InputStream input, int maximumBytes) {
		try (input; ByteArrayOutputStream output = new ByteArrayOutputStream(
				Math.min(maximumBytes, 8 * 1024)
		)) {
			byte[] buffer = new byte[8 * 1024];
			int total = 0;
			while (true) {
				int count = input.read(buffer);
				if (count < 0) break;
				if (count == 0) continue;
				if (count > maximumBytes - total) throw oversizedResponse();
				output.write(buffer, 0, count);
				total += count;
			}
			return output.toByteArray();
		} catch (IOException exception) {
			throw new VoiceWorkerClient.VoiceWorkerException(
					"VOICE_WORKER_RESPONSE", "Voice worker response could not be read", exception
			);
		}
	}

	static String responseSignature(
			String secret,
			String requestNonce,
			int status,
			String contentType,
			byte[] body
	) {
		String digest = java.util.HexFormat.of().formatHex(sha256(body));
		return hmac(secret, VERSION + "\nresponse\n" + requestNonce + "\n" + status + "\n"
				+ contentType + "\n" + digest);
	}

	static String requestSignature(
			String secret,
			String method,
			String path,
			long timestamp,
			String nonce,
			String contentType,
			String identity,
			String bodyDigest
	) {
		return hmac(secret, VERSION + "\nrequest\n" + method + "\n" + path + "\n" + timestamp + "\n"
				+ nonce + "\n" + contentType + "\n" + identity + "\n" + bodyDigest);
	}

	static String canonicalContentType(String value) {
		return Objects.requireNonNull(value, "content type must not be null")
				.strip().toLowerCase(java.util.Locale.ROOT).replaceAll("\\s+", "");
	}

	static String canonicalIdentity(Map<String, String> headers) {
		Map<String, String> values = Objects.requireNonNull(headers, "identity headers must not be null");
		return "player-id=" + canonicalValue(values.get("x-player-id"), true)
				+ "\nutterance-sequence=" + canonicalValue(values.get("x-utterance-sequence"), false)
				+ "\nwhispering=" + canonicalValue(values.get("x-whispering"), true);
	}

	static String sha256Hex(byte[] value) {
		return java.util.HexFormat.of().formatHex(sha256(Objects.requireNonNull(value, "body must not be null")));
	}

	private static String canonicalValue(String value, boolean lowercase) {
		if (value == null) return "";
		String normalized = value.strip();
		return lowercase ? normalized.toLowerCase(java.util.Locale.ROOT) : normalized;
	}

	private static Throwable unwrap(Throwable failure) {
		Throwable current = failure;
		while ((current instanceof CompletionException || current instanceof java.util.concurrent.ExecutionException)
				&& current.getCause() != null) current = current.getCause();
		return current;
	}

	private static void closeQuietly(InputStream input) {
		try {
			input.close();
		} catch (IOException ignored) {
		}
	}

	private static void abortAndClose(Runnable abortExchange, InputStream input) {
		try {
			abortExchange.run();
		} catch (RuntimeException ignored) {
		}
		Thread.startVirtualThread(() -> closeQuietly(input));
	}

	private static String hmac(String secret, String value) {
		try {
			Mac mac = Mac.getInstance("HmacSHA256");
			mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
			return Base64.getUrlEncoder().withoutPadding().encodeToString(
					mac.doFinal(value.getBytes(StandardCharsets.UTF_8))
			);
		} catch (GeneralSecurityException exception) {
			throw new IllegalStateException("HMAC-SHA256 is unavailable", exception);
		}
	}

	private static byte[] sha256(byte[] value) {
		try {
			return MessageDigest.getInstance("SHA-256").digest(value);
		} catch (GeneralSecurityException exception) {
			throw new IllegalStateException("SHA-256 is unavailable", exception);
		}
	}

	private static boolean constantTimeEquals(String actual, String expected) {
		return MessageDigest.isEqual(
				actual.getBytes(StandardCharsets.US_ASCII), expected.getBytes(StandardCharsets.US_ASCII)
		);
	}

	private static VoiceWorkerClient.VoiceWorkerException oversizedResponse() {
		return new VoiceWorkerClient.VoiceWorkerException(
				"VOICE_WORKER_RESPONSE", "Voice worker response exceeded its byte limit"
		);
	}
}
