package dev.agaminggod.arenaagents.voiceaddon;

import de.maxhenkel.voicechat.api.VoicechatServerApi;
import de.maxhenkel.voicechat.api.events.Event;
import de.maxhenkel.voicechat.api.events.EventRegistration;
import de.maxhenkel.voicechat.api.events.MicrophonePacketEvent;
import de.maxhenkel.voicechat.api.events.VoiceDistanceEvent;
import de.maxhenkel.voicechat.api.events.VoicechatServerStartedEvent;
import de.maxhenkel.voicechat.api.events.VoicechatServerStoppedEvent;
import dev.agaminggod.arenaagents.server.voice.VoiceSubsystemConfiguration;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/** Production plugin wiring verification for server/API ownership. */
final class ArenaAgentsVoiceChatPluginVerification {
	private ArenaAgentsVoiceChatPluginVerification() {
	}

	static int verify() throws Exception {
		Object serverA = new Object();
		Object serverB = new Object();
		Object serverC = new Object();
		VoicechatServerApi apiA = voicechatApi("A");
		VoicechatServerApi apiB = voicechatApi("B");
		VoicechatServerApi apiC = voicechatApi("C");
		VoiceSubsystemConfiguration configurationA = configuration(18_101, 'a');
		VoiceSubsystemConfiguration configurationB = configuration(18_102, 'b');
		VoiceSubsystemConfiguration reconfiguredA = configuration(18_103, 'c');
		VoiceSubsystemConfiguration reopenedA = configuration(18_104, 'd');
		VoiceSubsystemConfiguration configurationC = configuration(18_105, 'e');
		VoiceSubsystemConfiguration restartConfiguration = configuration(18_106, 'f');
		List<RecordingCapture> captures = new CopyOnWriteArrayList<>();
		Map<MicrophonePacketEvent, Object> packetServers = Collections.synchronizedMap(new IdentityHashMap<>());
		ArenaAgentsVoiceChatPlugin plugin = new ArenaAgentsVoiceChatPlugin(configuration -> {
			RecordingCapture capture = new RecordingCapture(configuration);
			captures.add(capture);
			return capture;
		}, packetServers::get);
		RecordingEventRegistration events = new RecordingEventRegistration();
		plugin.registerEvents(events);
		events.fire(VoiceDistanceEvent.class, distanceEvent());

		events.fire(VoicechatServerStartedEvent.class, serverEvent(VoicechatServerStartedEvent.class, apiA));
		ArenaAgentsVoiceChatPlugin.ConfiguredServer configuredA = plugin.configureServer(serverA, configurationA);
		events.fire(VoicechatServerStartedEvent.class, serverEvent(VoicechatServerStartedEvent.class, apiB));
		ArenaAgentsVoiceChatPlugin.ConfiguredServer configuredB = plugin.configureServer(serverB, configurationB);
		assertSame(apiA, configuredA.voicechat(), "playback for server A retains API A");
		assertSame(apiB, configuredB.voicechat(), "playback for server B retains API B");

		dispatchFirstPacketsConcurrently(events, packetServers, serverA, apiA, serverB, apiB);
		RecordingCapture captureA = onlyCapture(captures, configurationA);
		RecordingCapture captureB = onlyCapture(captures, configurationB);
		assertEquals("http://127.0.0.1:18101/v1/tts", captureA.configuration.endpoint(),
				"server A packet uses endpoint A");
		assertEquals("a".repeat(32), captureA.configuration.secret(), "server A packet uses secret A");
		assertEquals("http://127.0.0.1:18102/v1/tts", captureB.configuration.endpoint(),
				"server B packet uses endpoint B");
		assertEquals("b".repeat(32), captureB.configuration.secret(), "server B packet uses secret B");
		assertEquals(8, captureA.accepts, "concurrent first packets coalesce for server A");
		assertEquals(8, captureB.accepts, "concurrent first packets coalesce for server B");

		ArenaAgentsVoiceChatPlugin.ConfiguredServer secondA = plugin.configureServer(serverA, reconfiguredA);
		RecordingCapture secondCaptureA = dispatchAndCapture(
				events, packetServers, captures, serverA, apiA, reconfiguredA
		);
		assertSame(apiA, secondA.voicechat(), "reconfiguring A cannot adopt the newer API B");
		assertEquals(1, captureA.closes, "reconfiguring A closes its prior capture");
		assertEquals(0, captureB.closes, "reconfiguring A leaves B open");
		configuredA.close();
		assertEquals(0, secondCaptureA.closes, "closing stale A configuration cannot close replacement A");
		secondA.close();
		assertEquals(1, secondCaptureA.closes, "closing current A closes replacement A once");
		assertEquals(0, captureB.closes, "closing current A leaves B open");
		dispatch(events, packetServers, serverB, apiB);
		assertEquals(9, captureB.accepts, "B still accepts packets after A closes");

		ArenaAgentsVoiceChatPlugin.ConfiguredServer thirdA = plugin.configureServer(serverA, reopenedA);
		RecordingCapture thirdCaptureA = dispatchAndCapture(events, packetServers, captures, serverA, apiA, reopenedA);
		events.fire(VoicechatServerStoppedEvent.class, serverEvent(VoicechatServerStoppedEvent.class, apiB));
		configuredB.close();
		assertEquals(1, captureB.closes, "stopping and closing B closes B once");
		assertEquals(0, thirdCaptureA.closes, "stopping and closing B cannot close A");
		dispatch(events, packetServers, serverA, apiA);
		assertEquals(2, thirdCaptureA.accepts, "A still accepts packets after B stops");

		events.fire(VoicechatServerStartedEvent.class, serverEvent(VoicechatServerStartedEvent.class, apiC));
		events.fire(VoicechatServerStoppedEvent.class, serverEvent(VoicechatServerStoppedEvent.class, apiA));
		assertThrows(
				IllegalStateException.class,
				() -> plugin.configureServer(serverA, configurationC),
				"stale server A configuration is rejected instead of adopting API C"
		);
		ArenaAgentsVoiceChatPlugin.ConfiguredServer configuredC = plugin.configureServer(serverC, configurationC);
		assertSame(apiC, configuredC.voicechat(), "rejected stale A did not claim API C");
		assertEquals(1, thirdCaptureA.closes, "stopping API A closes its capture");
		thirdA.close();
		configuredC.close();

		List<RecordingCapture> ambiguousCaptures = new ArrayList<>();
		ArenaAgentsVoiceChatPlugin ambiguousPlugin = new ArenaAgentsVoiceChatPlugin(configuration -> {
			RecordingCapture capture = new RecordingCapture(configuration);
			ambiguousCaptures.add(capture);
			return capture;
		}, event -> null);
		RecordingEventRegistration ambiguousEvents = new RecordingEventRegistration();
		ambiguousPlugin.registerEvents(ambiguousEvents);
		ambiguousEvents.fire(VoicechatServerStartedEvent.class,
				serverEvent(VoicechatServerStartedEvent.class, apiA));
		ambiguousEvents.fire(VoicechatServerStartedEvent.class,
				serverEvent(VoicechatServerStartedEvent.class, apiB));
		ArenaAgentsVoiceChatPlugin.ConfiguredServer newestPending =
				ambiguousPlugin.configureServer(serverA, configurationA);
		assertSame(apiB, newestPending.voicechat(), "the newest unclaimed API generation is configured");
		assertEquals(0, ambiguousCaptures.size(), "configuration remains lazy until a microphone packet");
		newestPending.close();

		List<RecordingCapture> restartCaptures = new ArrayList<>();
		ArenaAgentsVoiceChatPlugin restartPlugin = new ArenaAgentsVoiceChatPlugin(configuration -> {
			RecordingCapture capture = new RecordingCapture(configuration);
			restartCaptures.add(capture);
			return capture;
		}, packetServers::get);
		RecordingEventRegistration restartEvents = new RecordingEventRegistration();
		restartPlugin.registerEvents(restartEvents);
		Object restartServer = new Object();
		VoicechatServerApi beforeRestart = voicechatApi("before-restart");
		VoicechatServerApi afterRestart = voicechatApi("after-restart");
		restartEvents.fire(VoicechatServerStartedEvent.class,
				serverEvent(VoicechatServerStartedEvent.class, beforeRestart));
		ArenaAgentsVoiceChatPlugin.ConfiguredServer configuredBeforeRestart =
				restartPlugin.configureServer(restartServer, restartConfiguration);
		restartEvents.fire(VoicechatServerStoppedEvent.class,
				serverEvent(VoicechatServerStoppedEvent.class, beforeRestart));
		assertEquals(false, configuredBeforeRestart.active(),
				"a stopped voice-chat generation is unavailable");
		restartEvents.fire(VoicechatServerStartedEvent.class,
				serverEvent(VoicechatServerStartedEvent.class, afterRestart));
		dispatch(restartEvents, packetServers, restartServer, afterRestart);
		assertEquals(true, configuredBeforeRestart.active(),
				"the existing configured server recovers after a voice-chat-only restart");
		assertSame(afterRestart, configuredBeforeRestart.voicechat(),
				"playback switches to the restarted voice-chat API without restarting Minecraft");
		RecordingCapture restartedCapture = onlyCapture(restartCaptures, restartConfiguration);
		assertEquals(1, restartedCapture.accepts,
				"microphone capture switches to the restarted API generation");
		configuredBeforeRestart.close();
		assertEquals(1, restartedCapture.closes,
				"closing the live configured server still closes its capture once");
		return 32;
	}

	private static RecordingCapture dispatchAndCapture(
			RecordingEventRegistration events,
			Map<MicrophonePacketEvent, Object> packetServers,
			List<RecordingCapture> captures,
			Object server,
			VoicechatServerApi api,
			VoiceSubsystemConfiguration configuration
	) {
		dispatch(events, packetServers, server, api);
		return onlyCapture(captures, configuration);
	}

	private static void dispatchFirstPacketsConcurrently(
			RecordingEventRegistration events,
			Map<MicrophonePacketEvent, Object> packetServers,
			Object serverA,
			VoicechatServerApi apiA,
			Object serverB,
			VoicechatServerApi apiB
	) throws Exception {
		int calls = 16;
		ExecutorService executor = Executors.newFixedThreadPool(calls);
		CountDownLatch ready = new CountDownLatch(calls);
		CountDownLatch start = new CountDownLatch(1);
		List<Future<?>> futures = new ArrayList<>(calls);
		try {
			for (int index = 0; index < calls; index++) {
				Object server = index % 2 == 0 ? serverA : serverB;
				VoicechatServerApi api = index % 2 == 0 ? apiA : apiB;
				futures.add(executor.submit(() -> {
					ready.countDown();
					await(start, "plugin packet workers start");
					dispatch(events, packetServers, server, api);
				}));
			}
			await(ready, "plugin packet workers ready");
			start.countDown();
			for (Future<?> future : futures) future.get(5L, TimeUnit.SECONDS);
		} finally {
			start.countDown();
			executor.shutdownNow();
		}
	}

	private static void dispatch(
			RecordingEventRegistration events,
			Map<MicrophonePacketEvent, Object> packetServers,
			Object server,
			VoicechatServerApi api
	) {
		MicrophonePacketEvent event = serverEvent(MicrophonePacketEvent.class, api);
		packetServers.put(event, server);
		events.fire(MicrophonePacketEvent.class, event);
	}

	private static VoiceDistanceEvent distanceEvent() {
		return proxy(VoiceDistanceEvent.class, (proxy, method, arguments) -> defaultValue(method.getReturnType()));
	}

	private static RecordingCapture onlyCapture(
			List<RecordingCapture> captures,
			VoiceSubsystemConfiguration configuration
	) {
		List<RecordingCapture> matches = captures.stream()
				.filter(capture -> capture.configuration.equals(configuration))
				.toList();
		assertEquals(1, matches.size(), "one capture for " + configuration.endpoint());
		return matches.getFirst();
	}

	private static VoiceSubsystemConfiguration configuration(int port, char secret) {
		return new VoiceSubsystemConfiguration(
				"http://127.0.0.1:" + port + "/v1/tts",
				String.valueOf(secret).repeat(32)
		);
	}

	private static VoicechatServerApi voicechatApi(String name) {
		return proxy(VoicechatServerApi.class, (proxy, method, arguments) -> switch (method.getName()) {
			case "toString" -> "voicechat-" + name;
			case "hashCode" -> System.identityHashCode(proxy);
			case "equals" -> proxy == arguments[0];
			default -> defaultValue(method.getReturnType());
		});
	}

	private static <T extends Event> T serverEvent(Class<T> eventType, VoicechatServerApi api) {
		return proxy(eventType, (proxy, method, arguments) -> switch (method.getName()) {
			case "getVoicechat" -> api;
			case "isCancellable", "cancel", "isCancelled" -> false;
			default -> defaultValue(method.getReturnType());
		});
	}

	private static Object defaultValue(Class<?> type) {
		if (!type.isPrimitive()) return null;
		if (type == boolean.class) return false;
		if (type == char.class) return '\0';
		return 0;
	}

	private static <T> T proxy(Class<T> type, java.lang.reflect.InvocationHandler handler) {
		return type.cast(Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[] { type }, handler));
	}

	private static void await(CountDownLatch latch, String label) {
		try {
			if (!latch.await(5L, TimeUnit.SECONDS)) throw new AssertionError(label + " timed out");
		} catch (InterruptedException exception) {
			Thread.currentThread().interrupt();
			throw new AssertionError(label + " was interrupted", exception);
		}
	}

	private static void assertThrows(Class<? extends Throwable> type, Runnable action, String label) {
		try {
			action.run();
		} catch (Throwable failure) {
			if (type.isInstance(failure)) return;
			throw new AssertionError(label + ": unexpected failure " + failure, failure);
		}
		throw new AssertionError(label + ": expected " + type.getSimpleName());
	}

	private static void assertEquals(Object expected, Object actual, String label) {
		if (!java.util.Objects.equals(expected, actual)) {
			throw new AssertionError(label + ": expected <" + expected + "> but was <" + actual + ">");
		}
	}

	private static void assertSame(Object expected, Object actual, String label) {
		if (expected != actual) throw new AssertionError(label);
	}

	private static final class RecordingEventRegistration implements EventRegistration {
		private final Map<Class<?>, Consumer<?>> handlers = new java.util.concurrent.ConcurrentHashMap<>();

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

	private static final class RecordingCapture implements ServerSpeechCaptureRegistry.Capture {
		private final VoiceSubsystemConfiguration configuration;
		private int accepts;
		private int closes;

		private RecordingCapture(VoiceSubsystemConfiguration configuration) {
			this.configuration = configuration;
		}

		@Override
		public synchronized void accept(MicrophonePacketSnapshot packet) {
			accepts++;
		}

		@Override
		public synchronized void close() {
			closes++;
		}

		@Override
		public void cancel(java.util.UUID playerId) {
		}
	}
}
