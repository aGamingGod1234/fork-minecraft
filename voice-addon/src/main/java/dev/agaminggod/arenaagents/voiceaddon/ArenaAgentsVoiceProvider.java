package dev.agaminggod.arenaagents.voiceaddon;

import de.maxhenkel.voicechat.api.VoicechatPlugin;
import de.maxhenkel.voicechat.api.VoicechatServerApi;
import de.maxhenkel.voicechat.api.events.EventRegistration;
import de.maxhenkel.voicechat.api.events.MicrophonePacketEvent;
import de.maxhenkel.voicechat.api.events.VoiceDistanceEvent;
import de.maxhenkel.voicechat.api.events.VoicechatServerStartedEvent;
import de.maxhenkel.voicechat.api.events.VoicechatServerStoppedEvent;
import dev.agaminggod.arenaagents.agent.AgentId;
import dev.agaminggod.arenaagents.server.CodexAgentServerRuntime;
import dev.agaminggod.arenaagents.server.voice.VoiceReceipt;
import dev.agaminggod.arenaagents.server.voice.VoiceRequest;
import dev.agaminggod.arenaagents.server.voice.VoiceSubsystem;
import dev.agaminggod.arenaagents.server.voice.VoiceSubsystemConfiguration;
import dev.agaminggod.arenaagents.server.voice.VoiceSubsystemProvider;
import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CompletionStage;
import net.minecraft.server.MinecraftServer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class ArenaAgentsVoiceProvider implements VoiceSubsystemProvider, VoicechatPlugin {
	private static final Logger LOGGER = LoggerFactory.getLogger(ArenaAgentsVoiceProvider.class);
	private static final String CONFIGURATION_CLASS =
			"dev.agaminggod.arenaagents.server.voice.VoiceSubsystemConfiguration";
	private static final int DEFAULT_REQUEST_TIMEOUT_MS = 120_000;
	private static final int MAX_REQUEST_TIMEOUT_MS = 600_000;
	private static volatile VoicechatServerApi legacyServerApi;
	private static volatile MinecraftServer legacyMinecraftServer;
	private static volatile HumanSpeechCapture legacySpeechCapture;

	@Override
	public String getPluginId() {
		return "arenaagents_voice";
	}

	@Override
	public void registerEvents(EventRegistration registration) {
		if (modernConfigurationAvailable()) {
			modernVoicechatPlugin().registerEvents(registration);
			return;
		}
		registration.registerEvent(VoicechatServerStartedEvent.class,
				event -> legacyServerApi = event.getVoicechat());
		registration.registerEvent(MicrophonePacketEvent.class, this::onLegacyMicrophonePacket);
		registration.registerEvent(VoiceDistanceEvent.class, SyntheticPlayerVoiceTransport::applyPlaybackDistance);
		registration.registerEvent(VoicechatServerStoppedEvent.class, event -> {
			if (legacyServerApi == event.getVoicechat()) legacyServerApi = null;
			HumanSpeechCapture capture = legacySpeechCapture;
			legacySpeechCapture = null;
			if (capture != null) capture.close();
		});
	}

	@Override
	public VoiceSubsystem create(MinecraftServer server) {
		LegacyConfiguration configuration = legacyConfiguration();
		legacyMinecraftServer = Objects.requireNonNull(server, "server must not be null");
		return new LegacyVoiceSubsystem(server, legacyWorker(configuration));
	}

	@Override
	public VoiceSubsystem create(MinecraftServer server, VoiceSubsystemConfiguration configuration) {
		ArenaAgentsVoiceChatPlugin.ConfiguredServer configuredServer =
				ArenaAgentsVoiceChatPlugin.configure(server, configuration);
		try {
			return new SimpleVoiceChatSubsystem(
					server, new VoiceWorkerClient(configuration), configuredServer
			);
		} catch (RuntimeException failure) {
			configuredServer.close();
			throw failure;
		}
	}

	private static boolean modernConfigurationAvailable() {
		try {
			Class.forName(CONFIGURATION_CLASS, false, ArenaAgentsVoiceProvider.class.getClassLoader());
			return true;
		} catch (ClassNotFoundException | LinkageError unavailable) {
			return false;
		}
	}

	private static VoicechatPlugin modernVoicechatPlugin() {
		try {
			return (VoicechatPlugin) Class.forName(
					"dev.agaminggod.arenaagents.voiceaddon.ArenaAgentsVoiceChatPlugin",
					true,
					ArenaAgentsVoiceProvider.class.getClassLoader()
			).getConstructor().newInstance();
		} catch (ReflectiveOperationException exception) {
			throw new IllegalStateException("Modern voice-chat plugin is unavailable", exception);
		}
	}

	private void onLegacyMicrophonePacket(MicrophonePacketEvent event) {
		try {
			MicrophonePacketSnapshot captured = MicrophonePacketSnapshot.capture(event);
			if (captured == null) return;
			MinecraftServer server = legacyMinecraftServer;
			if (server == null) return;
			MicrophonePacketSnapshot packet = captured.forServer(server);
			server.execute(() -> acceptLegacyPacketOnServer(server, packet));
		} catch (RuntimeException exception) {
			LOGGER.error("Arena Agents proximity speech capture could not start", exception);
		}
	}

	private void acceptLegacyPacketOnServer(MinecraftServer server, MicrophonePacketSnapshot packet) {
		try {
			if (dev.agaminggod.arenaagents.server.voice.VoiceSubsystemRuntime.isAgentPlayer(server, packet.playerId())) return;
			HumanSpeechCapture capture = legacySpeechCapture;
			if (capture == null) {
				synchronized (ArenaAgentsVoiceProvider.class) {
					capture = legacySpeechCapture;
					if (capture == null) {
						LegacyConfiguration configuration = legacyConfiguration();
						URI speechEndpoint = legacySpeechEndpoint(configuration.endpoint());
						capture = new HumanSpeechCapture(new SpeechWorkerClient(
								defaultHttpClient(), speechEndpoint, configuration.secret(),
								configuration.requestTimeout()
						));
						legacySpeechCapture = capture;
					}
				}
			}
			capture.accept(packet);
		} catch (RuntimeException exception) {
			LOGGER.error("Arena Agents proximity speech capture could not start", exception);
		}
	}

	private static VoiceWorkerClient legacyWorker(LegacyConfiguration configuration) {
		return new VoiceWorkerClient(
				defaultHttpClient(), configuration.endpoint(), configuration.secret(),
				configuration.requestTimeout()
		);
	}

	private static HttpClient defaultHttpClient() {
		return HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();
	}

	static URI legacySpeechEndpoint(URI voiceEndpoint) {
		return validateEndpoint(System.getProperty(
				"arenaagents.sttUrl",
				voiceEndpoint.toString().replace("/v1/tts", "/v1/stt")
		), "/v1/stt");
	}

	private static LegacyConfiguration legacyConfiguration() {
		URI endpoint = validateEndpoint(System.getProperty(
				"arenaagents.voiceUrl", "http://127.0.0.1:8766/v1/tts"
		), "/v1/tts");
		int requestTimeoutMs = parseRequestTimeout(System.getProperty(
				"arenaagents.voiceRequestTimeoutMs", Integer.toString(DEFAULT_REQUEST_TIMEOUT_MS)
		));
		String secretPath = System.getProperty(
				"arenaagents.voiceSecretFile",
				"runtime/voice-secret.txt"
		);
		try {
			String secret = Files.readString(Path.of(secretPath), StandardCharsets.UTF_8).trim();
			if (secret.length() < 16 || secret.length() > 512) {
				throw new IllegalArgumentException("voice secret is invalid");
			}
			return new LegacyConfiguration(endpoint, secret, Duration.ofMillis(requestTimeoutMs));
		} catch (IOException exception) {
			throw new IllegalStateException("Voice worker secret is unavailable", exception);
		}
	}

	private static URI validateEndpoint(String endpoint, String requiredPath) {
		String normalized = Objects.requireNonNull(endpoint, "voice endpoint must not be null").strip();
		if (normalized.isEmpty() || normalized.length() > 2_048) {
			throw new IllegalArgumentException("voice endpoint must be nonblank and bounded");
		}
		try {
			URI uri = new URI(normalized);
			String scheme = uri.getScheme();
			boolean supportedScheme = scheme != null
					&& (scheme.equalsIgnoreCase("http") || scheme.equalsIgnoreCase("https"));
			if (!supportedScheme
					|| uri.isOpaque()
					|| !"127.0.0.1".equals(uri.getHost())
					|| uri.getPort() < 1
					|| uri.getPort() > 65_535
					|| uri.getRawUserInfo() != null
					|| !requiredPath.equals(uri.getRawPath())
					|| uri.getRawQuery() != null
					|| uri.getRawFragment() != null) {
				throw new IllegalArgumentException(
						"voice endpoint must be a loopback worker " + requiredPath + " URI"
				);
			}
			return uri;
		} catch (URISyntaxException exception) {
			throw new IllegalArgumentException("voice endpoint must be a valid URI", exception);
		}
	}

	private static int parseRequestTimeout(String value) {
		try {
			int timeout = Integer.parseInt(value);
			if (timeout < 1 || timeout > MAX_REQUEST_TIMEOUT_MS) {
				throw new IllegalArgumentException("voice request timeout is invalid");
			}
			return timeout;
		} catch (NumberFormatException exception) {
			throw new IllegalArgumentException("voice request timeout must be an integer", exception);
		}
	}

	private record LegacyConfiguration(URI endpoint, String secret, Duration requestTimeout) {
	}

	private static final class LegacyVoiceSubsystem implements VoiceSubsystem {
		private final MinecraftServer server;
		private final SyntheticPlayerVoiceTransport transport;
		private final VoicePlaybackCoordinator playback;

		private LegacyVoiceSubsystem(MinecraftServer server, VoiceWorkerClient worker) {
			this.server = Objects.requireNonNull(server, "server must not be null");
			this.transport = new SyntheticPlayerVoiceTransport(() -> legacyServerApi);
			this.playback = new VoicePlaybackCoordinator(
					worker::synthesize,
					server::execute,
					transport,
					latency -> LOGGER.info(
							"Voice output latency agent={} sequence={} synthesisMs={} firstPlaybackMs={}",
							latency.agentId(), latency.conversationSequence(), latency.synthesisMilliseconds(),
							latency.firstPlaybackMilliseconds()
					)
			);
		}

		@Override
		public boolean available() {
			return playback.available();
		}

		@Override
		public void registerAgent(AgentId agentId, UUID entityId) {
			playback.registerAgent(agentId, entityId);
			transport.registerAgent(agentId, entityId);
		}

		@Override
		public void refreshAgent(AgentId agentId, UUID entityId) {
			transport.registerAgent(agentId, entityId);
		}

		@Override
		public void unregisterAgent(AgentId agentId) {
			playback.unregisterAgent(agentId);
			transport.unregisterAgent(agentId);
		}

		@Override
		public CompletionStage<VoiceReceipt> speak(VoiceRequest request) {
			return playback.speak(request);
		}

		@Override
		public void stop(AgentId agentId) {
			playback.stop(agentId);
		}

		@Override
		public void cancelHumanSpeech(UUID playerId) {
			HumanSpeechCapture capture = legacySpeechCapture;
			if (capture != null) capture.cancel(playerId);
		}

		@Override
		public void close() {
			try {
				playback.close();
			} finally {
				try {
					transport.close();
				} finally {
					if (legacyMinecraftServer == server) legacyMinecraftServer = null;
				}
			}
		}
	}
}
