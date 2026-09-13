package dev.agaminggod.arenaagents.server.voice;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.Objects;

/** Worker-validated voice transport configuration passed without global properties or file reads. */
public record VoiceSubsystemConfiguration(String endpoint, String secret, int requestTimeoutMs) {
	public static final int DEFAULT_REQUEST_TIMEOUT_MS = 120_000;
	public static final int MAX_REQUEST_TIMEOUT_MS = 600_000;

	public VoiceSubsystemConfiguration(String endpoint, String secret) {
		this(endpoint, secret, DEFAULT_REQUEST_TIMEOUT_MS);
	}

	public VoiceSubsystemConfiguration {
		endpoint = Objects.requireNonNull(endpoint, "voice endpoint must not be null").strip();
		if (endpoint.isEmpty() || endpoint.length() > 2_048) {
			throw new IllegalArgumentException("voice endpoint must be nonblank and bounded");
		}
		validateEndpoint(endpoint);
		secret = Objects.requireNonNull(secret, "voice secret must not be null").strip();
		if (secret.length() < 16 || secret.length() > 512) {
			throw new IllegalArgumentException("voice secret is invalid");
		}
		if (requestTimeoutMs < 1 || requestTimeoutMs > MAX_REQUEST_TIMEOUT_MS) {
			throw new IllegalArgumentException("voice request timeout is invalid");
		}
	}

	private static void validateEndpoint(String endpoint) {
		try {
			URI uri = new URI(endpoint);
			String scheme = uri.getScheme();
			boolean supportedScheme = scheme != null
					&& (scheme.equalsIgnoreCase("http") || scheme.equalsIgnoreCase("https"));
			if (!supportedScheme
					|| uri.isOpaque()
					|| !"127.0.0.1".equals(uri.getHost())
					|| uri.getPort() < 1
					|| uri.getPort() > 65_535
					|| uri.getRawUserInfo() != null
					|| !"/v1/tts".equals(uri.getRawPath())
					|| uri.getRawQuery() != null
					|| uri.getRawFragment() != null) {
				throw new IllegalArgumentException("voice endpoint must be a loopback worker /v1/tts URI");
			}
		} catch (URISyntaxException exception) {
			throw new IllegalArgumentException("voice endpoint must be a valid URI", exception);
		}
	}
}
