package dev.agaminggod.arenaagents.voiceaddon;

import de.maxhenkel.voicechat.api.VoicechatServerApi;
import de.maxhenkel.voicechat.api.events.Event;
import de.maxhenkel.voicechat.api.events.EventRegistration;
import de.maxhenkel.voicechat.api.events.VoicechatServerStartedEvent;
import dev.agaminggod.arenaagents.server.voice.VoiceSubsystem;
import dev.agaminggod.arenaagents.server.voice.VoiceSubsystemConfiguration;
import dev.agaminggod.arenaagents.server.voice.VoiceSubsystemProvider;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.dedicated.DedicatedServer;

/** Verifies both provider startup contracts across core upgrade directions. */
final class ArenaAgentsVoiceProviderVerification {
	private ArenaAgentsVoiceProviderVerification() {
	}

	static int verify() throws Exception {
		Path secretFile = Files.createTempFile("arena-agents-voice-provider", ".secret");
		Files.writeString(secretFile, "legacy-provider-secret-0123456789\n", StandardCharsets.UTF_8);
		String previousUrl = System.getProperty("arenaagents.voiceUrl");
		String previousSecretFile = System.getProperty("arenaagents.voiceSecretFile");
		String previousTimeout = System.getProperty("arenaagents.voiceRequestTimeoutMs");
		MinecraftServer server = testServer();
		try {
			System.setProperty("arenaagents.voiceUrl", "http://127.0.0.1:18765/v1/tts");
			System.setProperty("arenaagents.voiceSecretFile", secretFile.toString());
			System.setProperty("arenaagents.voiceRequestTimeoutMs", "12345");
			RecordingEventRegistration events = new RecordingEventRegistration();
			ArenaAgentsVoiceProvider addonEntrypoint = new ArenaAgentsVoiceProvider();
			addonEntrypoint.registerEvents(events);
			events.fire(VoicechatServerStartedEvent.class, startedEvent());

			VoiceSubsystemProvider provider = addonEntrypoint;
			VoiceSubsystem legacy = provider.create(server);
			assertTrue(legacy != null, "legacy one-argument provider startup returns a subsystem");
			legacy.close();

			VoiceSubsystemConfiguration explicit = new VoiceSubsystemConfiguration(
					"http://127.0.0.1:18766/v1/tts", "explicit-provider-secret-0123456789", 23456
			);
			VoiceSubsystem configured = provider.create(server, explicit);
			assertTrue(configured != null, "configuration-aware provider startup returns a subsystem");
			configured.close();
			return 2;
		} finally {
			restoreProperty("arenaagents.voiceUrl", previousUrl);
			restoreProperty("arenaagents.voiceSecretFile", previousSecretFile);
			restoreProperty("arenaagents.voiceRequestTimeoutMs", previousTimeout);
			Files.deleteIfExists(secretFile);
		}
	}

	private static MinecraftServer testServer() throws Exception {
		Class<?> unsafeType = Class.forName("sun.misc.Unsafe");
		var field = unsafeType.getDeclaredField("theUnsafe");
		field.setAccessible(true);
		Object unsafe = field.get(null);
		return (MinecraftServer) unsafeType.getMethod("allocateInstance", Class.class)
				.invoke(unsafe, DedicatedServer.class);
	}

	private static VoicechatServerStartedEvent startedEvent() {
		VoicechatServerApi api = proxy(VoicechatServerApi.class, (proxy, method, arguments) ->
				defaultValue(method.getReturnType()));
		return eventProxy(VoicechatServerStartedEvent.class, (proxy, method, arguments) ->
				method.getName().equals("getVoicechat") ? api : defaultValue(method.getReturnType()));
	}

	private static <T> T proxy(Class<T> type, InvocationHandler handler) {
		return type.cast(Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[] { type }, handler));
	}

	private static <T extends Event> T eventProxy(Class<T> type, InvocationHandler handler) {
		return proxy(type, handler);
	}

	private static Object defaultValue(Class<?> type) {
		if (!type.isPrimitive()) return null;
		if (type == boolean.class) return false;
		if (type == char.class) return '\0';
		return 0;
	}

	private static void restoreProperty(String key, String value) {
		if (value == null) System.clearProperty(key);
		else System.setProperty(key, value);
	}

	private static void assertTrue(boolean value, String label) {
		if (!value) throw new AssertionError(label);
	}

	private static final class RecordingEventRegistration implements EventRegistration {
		private final Map<Class<?>, Consumer<?>> handlers = new ConcurrentHashMap<>();

		@Override
		public <T extends Event> void registerEvent(Class<T> eventType, Consumer<T> handler, int priority) {
			handlers.put(eventType, handler);
		}

		private <T extends Event> void fire(Class<T> eventType, T event) {
			@SuppressWarnings("unchecked")
			Consumer<T> handler = (Consumer<T>) handlers.get(eventType);
			if (handler == null) throw new AssertionError("No handler registered for " + eventType.getSimpleName());
			handler.accept(event);
		}
	}
}
