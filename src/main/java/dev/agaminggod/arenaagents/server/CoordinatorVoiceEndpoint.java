package dev.agaminggod.arenaagents.server;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

final class CoordinatorVoiceEndpoint {
	private static final int DEFAULT_PORT = 8_766;
	private static final int DEFAULT_REQUEST_TIMEOUT_MS = 120_000;
	private static final int MAX_REQUEST_TIMEOUT_MS = 600_000;
	private static final String PORT_ENVIRONMENT_VARIABLE = "ARENA_AGENT_VOICE_PORT";

	private CoordinatorVoiceEndpoint() {
	}

	static Optional<String> resolve(
			Path configPath,
			Map<String, String> inheritedEnvironment,
			Map<String, String> launchOverrides
	) throws IOException {
		Objects.requireNonNull(configPath, "configPath must not be null");
		Objects.requireNonNull(inheritedEnvironment, "inheritedEnvironment must not be null");
		Objects.requireNonNull(launchOverrides, "launchOverrides must not be null");
		String configuredEnvironment = firstNonblank(
				valueIgnoreCase(launchOverrides, PORT_ENVIRONMENT_VARIABLE),
				valueIgnoreCase(inheritedEnvironment, PORT_ENVIRONMENT_VARIABLE)
		);
		int port = configuredEnvironment == null
				? configuredPort(configPath)
				: parsePort(configuredEnvironment);
		return Optional.of("http://127.0.0.1:" + port + "/v1/tts");
	}

	static int requestTimeoutMs(Path configPath) throws IOException {
		Objects.requireNonNull(configPath, "configPath must not be null");
		JsonObject root = readConfig(configPath);
		if (!root.has("voice") || root.get("voice").isJsonNull()) return DEFAULT_REQUEST_TIMEOUT_MS;
		JsonObject voice = root.getAsJsonObject("voice");
		if (!voice.has("localSpeechTimeoutMs") || voice.get("localSpeechTimeoutMs").isJsonNull()) {
			return DEFAULT_REQUEST_TIMEOUT_MS;
		}
		try {
			var value = voice.get("localSpeechTimeoutMs");
			if (!value.isJsonPrimitive() || !value.getAsJsonPrimitive().isNumber()) {
				throw new IllegalArgumentException("voice.localSpeechTimeoutMs must be numeric");
			}
			double numeric = value.getAsDouble();
			if (!Double.isFinite(numeric) || numeric != Math.rint(numeric)
					|| numeric < 1 || numeric > MAX_REQUEST_TIMEOUT_MS) {
				throw new IllegalArgumentException("voice.localSpeechTimeoutMs is outside the supported range");
			}
			return (int) numeric;
		} catch (RuntimeException exception) {
			throw new IllegalArgumentException(
					"voice.localSpeechTimeoutMs must be an integer between 1 and 600000",
					exception
			);
		}
	}

	private static int configuredPort(Path configPath) throws IOException {
		JsonObject root = readConfig(configPath);
		if (!root.has("voice") || root.get("voice").isJsonNull()) return DEFAULT_PORT;
		JsonObject voice = root.getAsJsonObject("voice");
		if (!voice.has("port") || voice.get("port").isJsonNull()) return DEFAULT_PORT;
		try {
			var value = voice.get("port");
			if (!value.isJsonPrimitive() || !value.getAsJsonPrimitive().isNumber()) {
				throw new IllegalArgumentException("voice.port must be numeric");
			}
			double numeric = value.getAsDouble();
			if (!Double.isFinite(numeric) || numeric != Math.rint(numeric)) {
				throw new IllegalArgumentException("voice.port must be integral");
			}
			return validPort((int) numeric);
		} catch (RuntimeException exception) {
			throw new IllegalArgumentException("voice.port must be an integer between 0 and 65535", exception);
		}
	}

	private static JsonObject readConfig(Path configPath) throws IOException {
		return JsonParser.parseString(Files.readString(configPath, StandardCharsets.UTF_8)).getAsJsonObject();
	}

	private static int parsePort(String value) {
		try {
			return validPort(Integer.parseInt(value));
		} catch (NumberFormatException exception) {
			throw new IllegalArgumentException("ARENA_AGENT_VOICE_PORT must be an integer between 0 and 65535", exception);
		}
	}

	private static int validPort(int port) {
		if (port < 1 || port > 65_535) throw new IllegalArgumentException("voice port is outside the supported range");
		return port;
	}

	private static String valueIgnoreCase(Map<String, String> values, String key) {
		for (Map.Entry<String, String> entry : values.entrySet()) {
			if (entry.getKey().equalsIgnoreCase(key)) return entry.getValue();
		}
		return null;
	}

	private static String firstNonblank(String first, String second) {
		if (first != null && !first.isBlank()) return first;
		return second == null || second.isBlank() ? null : second;
	}
}
