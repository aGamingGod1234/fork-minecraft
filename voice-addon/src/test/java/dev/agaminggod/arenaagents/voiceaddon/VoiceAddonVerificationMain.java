package dev.agaminggod.arenaagents.voiceaddon;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;

public final class VoiceAddonVerificationMain {
	private VoiceAddonVerificationMain() {
	}

	public static void main(String[] arguments) throws Exception {
		int assertions = 0;
		assertions += verifyFabricMetadataLoadsOnIntegratedAndDedicatedServers();
		assertions += LegacyVoiceProviderClasspathVerification.verify();
		assertions += ArenaAgentsVoiceProviderVerification.verify();
		assertions += ArenaAgentsVoiceChatPluginVerification.verify();
		assertions += VoicechatServerBindingsVerification.verify();
		assertions += ServerSpeechCaptureRegistryVerification.verify();
		assertions += VoicePlaybackCoordinatorVerification.verify();
		assertions += SyntheticPlayerVoiceTransportVerification.verify();
		assertions += SpeechCaptureEngineVerification.verify();
		assertions += HumanSpeechCaptureVerification.verify();
		assertions += VoiceWorkerClientsVerification.verify();
		assertions += NodeVoiceWorkerIntegrationVerification.verify();
		System.out.println("PASS: " + assertions + " voice-addon assertions");
	}

	private static int verifyFabricMetadataLoadsOnIntegratedAndDedicatedServers() {
		try (InputStream stream = VoiceAddonVerificationMain.class.getClassLoader()
				.getResourceAsStream("fabric.mod.json")) {
			if (stream == null) throw new AssertionError("Voice addon metadata is missing");
			JsonObject metadata = JsonParser.parseReader(new InputStreamReader(stream, StandardCharsets.UTF_8))
					.getAsJsonObject();
			if (!"*".equals(metadata.get("environment").getAsString())) {
				throw new AssertionError("Voice addon must load in an integrated server client JVM");
			}
			if (!">=0.1.0".equals(metadata.getAsJsonObject("depends")
					.get("arenaagents").getAsString())) {
				throw new AssertionError("Voice addon must retain the compatible arenaagents dependency range");
			}
			String voicechatEntrypoint = metadata.getAsJsonObject("entrypoints")
					.getAsJsonArray("voicechat").get(0).getAsString();
			if (!ArenaAgentsVoiceProvider.class.getName().equals(voicechatEntrypoint)) {
				throw new AssertionError("Voice addon entrypoints must share the compatibility provider");
			}
			return 3;
		} catch (java.io.IOException exception) {
			throw new AssertionError("Could not read voice addon metadata", exception);
		}
	}
}
