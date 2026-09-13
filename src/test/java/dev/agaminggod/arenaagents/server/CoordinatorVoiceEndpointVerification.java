package dev.agaminggod.arenaagents.server;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Optional;

public final class CoordinatorVoiceEndpointVerification {
	private CoordinatorVoiceEndpointVerification() {
	}

	public static int verify() throws Exception {
		Path config = Files.createTempFile("arena-voice-endpoint", ".json");
		try {
			Files.writeString(config, "{\"voice\":{\"port\":9123,\"localSpeechTimeoutMs\":91234}}", StandardCharsets.UTF_8);
			assertEquals(
					Optional.of("http://127.0.0.1:9123/v1/tts"),
					CoordinatorVoiceEndpoint.resolve(config, Map.of(), Map.of()),
					"configured voice port"
			);
			assertEquals(
					Optional.of("http://127.0.0.1:9234/v1/tts"),
					CoordinatorVoiceEndpoint.resolve(
							config,
							Map.of("ARENA_AGENT_VOICE_PORT", "9000"),
							Map.of("ARENA_AGENT_VOICE_PORT", "9234")
					),
					"launch environment override"
			);
			expectIllegalArgument(
					() -> CoordinatorVoiceEndpoint.resolve(
							config, Map.of(), Map.of("ARENA_AGENT_VOICE_PORT", "0")),
					"ephemeral voice port is rejected because the addon cannot discover it"
			);
			assertEquals(91_234, CoordinatorVoiceEndpoint.requestTimeoutMs(config),
					"configured local inference timeout reaches the voice clients");
			Files.writeString(config, "{\"voice\":{\"localSpeechTimeoutMs\":600001}}", StandardCharsets.UTF_8);
			expectIllegalArgument(() -> CoordinatorVoiceEndpoint.requestTimeoutMs(config),
					"unsupported voice request timeout is rejected before startup");
			return 5;
		} finally {
			Files.deleteIfExists(config);
		}
	}

	private static void expectIllegalArgument(CheckedAction action, String label) throws Exception {
		try {
			action.run();
			throw new AssertionError(label + ": expected IllegalArgumentException");
		} catch (IllegalArgumentException expected) {
			// Expected.
		}
	}

	@FunctionalInterface
	private interface CheckedAction {
		void run() throws Exception;
	}

	private static void assertEquals(Object expected, Object actual, String label) {
		if (!java.util.Objects.equals(expected, actual)) {
			throw new AssertionError(label + ": expected=" + expected + ", actual=" + actual);
		}
	}
}
