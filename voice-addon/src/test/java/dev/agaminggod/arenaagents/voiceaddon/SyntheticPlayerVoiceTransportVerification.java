package dev.agaminggod.arenaagents.voiceaddon;

import de.maxhenkel.voicechat.api.VoicechatConnection;
import de.maxhenkel.voicechat.api.VoicechatServerApi;
import de.maxhenkel.voicechat.api.audiosender.AudioSender;
import de.maxhenkel.voicechat.api.events.VoiceDistanceEvent;
import de.maxhenkel.voicechat.api.opus.OpusEncoder;
import de.maxhenkel.voicechat.api.ServerPlayer;
import dev.agaminggod.arenaagents.agent.AgentId;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

final class SyntheticPlayerVoiceTransportVerification {
	private static final AgentId AGENT = AgentId.parse("00000000-0000-4000-8000-000000000101");
	private static final UUID FIRST_PLAYER = UUID.fromString("10000000-0000-4000-8000-000000000101");
	private static final UUID RESPAWNED_PLAYER = UUID.fromString("10000000-0000-4000-8000-000000000102");
	private static final UUID ORDINARY_PLAYER = UUID.fromString("10000000-0000-4000-8000-000000000103");

	private SyntheticPlayerVoiceTransportVerification() {
	}

	static int verify() throws Exception {
		int assertions = 0;
		assertions += verifyConnectedSpeakingAndSilentLifecycle();
		assertions += verifyRespawnRebindsExactlyOneSender();
		assertions += verifyVoiceServerRestartRebindsTheSamePlayer();
		assertions += verifyRejectedPacketFailsWithoutAFalseCompletion();
		assertions += verifyLaterPacketFailureIsReportedAsFailure();
		assertions += verifyCancellationStopsTheStreamWithoutCallbacks();
		assertions += verifyDelayedConnectionIsRetried();
		assertions += verifyRealVoiceClientIsNeverModified();
		return assertions;
	}

	private static int verifyConnectedSpeakingAndSilentLifecycle() throws Exception {
		RecordingApi api = new RecordingApi();
		RecordingConnection agent = api.connection(FIRST_PLAYER, false);
		RecordingConnection ordinary = api.connection(ORDINARY_PLAYER, true);
		SyntheticPlayerVoiceTransport transport = new SyntheticPlayerVoiceTransport(() -> api.proxy());
		transport.registerAgent(AGENT, FIRST_PLAYER);
		transport.registerAgent(AGENT, FIRST_PLAYER);

		assertEquals(true, agent.connected, "managed fake player is presented as voice connected");
		assertEquals(false, agent.disabled, "managed fake player is not presented as voice disabled");
		assertEquals(1, api.registeredSenders, "reconciliation keeps one sender per managed player");
		assertEquals(0, ordinary.connectionWrites, "ordinary players are not modified");

		CountDownLatch stopped = new CountDownLatch(1);
		VoicePlaybackCoordinator.Playback playback = transport.create(
				AGENT, FIRST_PLAYER, 48, new short[1_920], stopped::countDown,
				() -> { throw new AssertionError("complete stream must not report failure"); }
		);
		assertEquals(48, SyntheticPlayerVoiceTransport.playbackDistance(FIRST_PLAYER),
				"create records the requested proximity radius");
		RecordingDistanceEvent distanceEvent = new RecordingDistanceEvent(FIRST_PLAYER, 16.0F);
		SyntheticPlayerVoiceTransport.applyPlaybackDistance(distanceEvent.event());
		assertEquals(48.0F, distanceEvent.distance,
				"VoiceDistanceEvent uses the requested proximity radius instead of the server-wide default");
		playback.start();
		assertEquals(true, stopped.await(2, TimeUnit.SECONDS), "speech stream reaches its natural stop callback");
		RecordingSender sender = api.onlySender();
		assertEquals(2, sender.frames.size(), "48 kHz speech is sent as two paced 20 ms microphone packets");
		assertEquals(960, sender.encodedSampleCounts.get(0), "first Opus frame has the required sample count");
		assertEquals(960, sender.encodedSampleCounts.get(1), "second Opus frame has the required sample count");
		assertEquals(true, sender.resetCalls >= 2, "sender marks both stream start and stream end");
		assertEquals(true, sender.encoderClosed, "per-utterance Opus encoder is closed");
		assertEquals(true, agent.connected, "silent managed player remains voice connected");
		assertEquals(1, api.registeredSenders, "silent managed player retains its sender for later speech");
		assertEquals(null, SyntheticPlayerVoiceTransport.playbackDistance(FIRST_PLAYER),
				"completed playback clears the requested proximity radius");

		transport.unregisterAgent(AGENT);
		assertEquals(false, agent.connected, "removed agent is no longer presented as voice connected");
		assertEquals(0, api.registeredSenders, "removed agent releases its audio sender");
		assertEquals(0, ordinary.connectionWrites, "cleanup still does not touch ordinary players");
		transport.close();
		return 17;
	}

	private static int verifyRespawnRebindsExactlyOneSender() {
		RecordingApi api = new RecordingApi();
		RecordingConnection first = api.connection(FIRST_PLAYER, false);
		RecordingConnection respawned = api.connection(RESPAWNED_PLAYER, false);
		SyntheticPlayerVoiceTransport transport = new SyntheticPlayerVoiceTransport(() -> api.proxy());
		transport.registerAgent(AGENT, FIRST_PLAYER);
		transport.registerAgent(AGENT, RESPAWNED_PLAYER);
		assertEquals(false, first.connected, "old fake player connection is cleared during identity replacement");
		assertEquals(true, respawned.connected, "replacement fake player is connected automatically");
		assertEquals(1, api.registeredSenders, "respawn replacement leaves exactly one registered sender");
		assertEquals(2, api.senderRegistrations, "respawn creates one new sender after releasing the old one");
		assertEquals(1, api.senderUnregistrations, "respawn releases the old sender once");
		transport.close();
		assertEquals(false, respawned.connected, "transport shutdown clears replacement connection state");
		assertEquals(0, api.registeredSenders, "transport shutdown releases every sender");
		return 7;
	}

	private static int verifyRejectedPacketFailsWithoutAFalseCompletion() {
		RecordingApi api = new RecordingApi();
		api.connection(FIRST_PLAYER, false);
		SyntheticPlayerVoiceTransport transport = new SyntheticPlayerVoiceTransport(() -> api.proxy());
		transport.registerAgent(AGENT, FIRST_PLAYER);
		RecordingSender sender = api.onlySender();
		sender.acceptPackets = false;
		AtomicInteger stopped = new AtomicInteger();
		VoicePlaybackCoordinator.Playback playback = transport.create(
				AGENT, FIRST_PLAYER, 48, new short[960], stopped::incrementAndGet, stopped::incrementAndGet
		);
		assertThrows(VoicePlaybackCoordinator.UnavailableException.class, playback::start,
				"a rejected first microphone packet fails playback synchronously");
		playback.stop();
		assertEquals(0, stopped.get(), "rejected playback does not report a false successful completion");
		assertEquals(true, sender.encoderClosed, "rejected playback closes its Opus encoder");
		transport.close();
		return 3;
	}

	private static int verifyLaterPacketFailureIsReportedAsFailure() throws Exception {
		RecordingApi api = new RecordingApi();
		api.connection(FIRST_PLAYER, false);
		SyntheticPlayerVoiceTransport transport = new SyntheticPlayerVoiceTransport(() -> api.proxy());
		transport.registerAgent(AGENT, FIRST_PLAYER);
		api.onlySender().acceptedPacketLimit = 1;
		AtomicInteger stopped = new AtomicInteger();
		CountDownLatch failed = new CountDownLatch(1);
		VoicePlaybackCoordinator.Playback playback = transport.create(
				AGENT, FIRST_PLAYER, 48, new short[1_920], stopped::incrementAndGet, failed::countDown
		);
		playback.start();
		assertEquals(true, failed.await(2, TimeUnit.SECONDS), "later packet rejection reports stream failure");
		assertEquals(0, stopped.get(), "truncated speech is never reported as played");
		assertEquals(true, api.onlySender().encoderClosed, "failed stream closes its Opus encoder");
		transport.close();
		return 3;
	}

	private static int verifyCancellationStopsTheStreamWithoutCallbacks() throws Exception {
		RecordingApi api = new RecordingApi();
		api.connection(FIRST_PLAYER, false);
		SyntheticPlayerVoiceTransport transport = new SyntheticPlayerVoiceTransport(() -> api.proxy());
		transport.registerAgent(AGENT, FIRST_PLAYER);
		AtomicInteger callbacks = new AtomicInteger();
		VoicePlaybackCoordinator.Playback playback = transport.create(
				AGENT, FIRST_PLAYER, 48, new short[96_000], callbacks::incrementAndGet, callbacks::incrementAndGet
		);
		playback.start();
		playback.stop();
		long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
		while (!api.onlySender().encoderClosed && System.nanoTime() < deadline) Thread.sleep(5L);
		int framesAfterStop = api.onlySender().frames.size();
		Thread.sleep(60L);
		assertEquals(true, api.onlySender().encoderClosed, "cancelled stream closes its Opus encoder promptly");
		assertEquals(framesAfterStop, api.onlySender().frames.size(), "cancelled stream sends no later packets");
		assertEquals(0, callbacks.get(), "cancellation reports neither played nor failed");
		transport.close();
		return 3;
	}

	private static int verifyDelayedConnectionIsRetried() {
		RecordingApi api = new RecordingApi();
		SyntheticPlayerVoiceTransport transport = new SyntheticPlayerVoiceTransport(() -> api.proxy());
		transport.registerAgent(AGENT, FIRST_PLAYER);
		assertEquals(0, api.senderRegistrations, "missing initial voice connection does not create a broken sender");
		RecordingConnection connected = api.connection(FIRST_PLAYER, false);
		transport.registerAgent(AGENT, FIRST_PLAYER);
		assertEquals(true, connected.connected, "unchanged agent identity reconnects when voice registration appears");
		assertEquals(1, api.senderRegistrations, "delayed voice registration creates exactly one sender");
		transport.close();
		return 3;
	}

	private static int verifyVoiceServerRestartRebindsTheSamePlayer() {
		RecordingApi beforeRestart = new RecordingApi();
		RecordingConnection oldConnection = beforeRestart.connection(FIRST_PLAYER, false);
		RecordingApi afterRestart = new RecordingApi();
		RecordingConnection newConnection = afterRestart.connection(FIRST_PLAYER, false);
		AtomicReference<VoicechatServerApi> currentApi = new AtomicReference<>(beforeRestart.proxy());
		SyntheticPlayerVoiceTransport transport = new SyntheticPlayerVoiceTransport(currentApi::get);
		transport.registerAgent(AGENT, FIRST_PLAYER);
		currentApi.set(afterRestart.proxy());
		transport.registerAgent(AGENT, FIRST_PLAYER);
		assertEquals(false, oldConnection.connected, "voice-server restart clears the old connection override");
		assertEquals(0, beforeRestart.registeredSenders, "voice-server restart releases the old API sender");
		assertEquals(true, newConnection.connected, "voice-server restart reconnects the same fake player");
		assertEquals(1, afterRestart.registeredSenders, "voice-server restart registers one sender on the new API");
		transport.close();
		return 4;
	}

	private static int verifyRealVoiceClientIsNeverModified() {
		RecordingApi api = new RecordingApi();
		RecordingConnection installed = api.connection(FIRST_PLAYER, true);
		SyntheticPlayerVoiceTransport transport = new SyntheticPlayerVoiceTransport(() -> api.proxy());
		transport.registerAgent(AGENT, FIRST_PLAYER);
		assertEquals(0, installed.connectionWrites, "a player with a real voice client is never impersonated");
		assertEquals(0, api.senderRegistrations, "a real voice client never receives a synthetic sender");
		assertThrows(VoicePlaybackCoordinator.UnavailableException.class,
				() -> transport.create(AGENT, FIRST_PLAYER, 48, new short[960], () -> { }, () -> { }),
				"speech rejects rather than overriding a real voice client");
		transport.close();
		return 3;
	}

	private static final class RecordingApi implements InvocationHandler {
		private final Map<UUID, RecordingConnection> connections = new LinkedHashMap<>();
		private final Map<AudioSender, RecordingSender> senders = new IdentityHashMap<>();
		private final VoicechatServerApi proxy = SyntheticPlayerVoiceTransportVerification.proxy(
				VoicechatServerApi.class, this
		);
		private int registeredSenders;
		private int senderRegistrations;
		private int senderUnregistrations;

		private VoicechatServerApi proxy() {
			return proxy;
		}

		private RecordingConnection connection(UUID playerId, boolean installed) {
			RecordingConnection connection = new RecordingConnection(installed);
			connections.put(playerId, connection);
			return connection;
		}

		private RecordingSender onlySender() {
			return senders.values().stream().reduce((first, second) -> second).orElseThrow();
		}

		@Override
		public Object invoke(Object ignored, java.lang.reflect.Method method, Object[] arguments) {
			return switch (method.getName()) {
				case "getConnectionOf" -> {
					RecordingConnection connection = connections.get((UUID) arguments[0]);
					yield connection == null ? null : connection.proxy;
				}
				case "createAudioSender" -> {
					RecordingSender sender = new RecordingSender();
					senders.put(sender.proxy, sender);
					yield sender.proxy;
				}
				case "registerAudioSender" -> {
					RecordingSender sender = senders.get((AudioSender) arguments[0]);
					sender.registered = true;
					registeredSenders++;
					senderRegistrations++;
					yield true;
				}
				case "unregisterAudioSender" -> {
					RecordingSender sender = senders.get((AudioSender) arguments[0]);
					if (sender.registered) {
						sender.registered = false;
						registeredSenders--;
						senderUnregistrations++;
						yield true;
					}
					yield false;
				}
				case "createEncoder" -> {
					RecordingSender sender = onlySender();
					yield sender.encoder;
				}
				case "toString" -> "recording-voicechat-api";
				default -> defaultValue(method.getReturnType());
			};
		}
	}

	private static final class RecordingConnection implements InvocationHandler {
		private final boolean installed;
		private final VoicechatConnection proxy = proxy(VoicechatConnection.class, this);
		private boolean connected;
		private boolean disabled = true;
		private int connectionWrites;

		private RecordingConnection(boolean installed) {
			this.installed = installed;
		}

		@Override
		public Object invoke(Object ignored, java.lang.reflect.Method method, Object[] arguments) {
			return switch (method.getName()) {
				case "isInstalled" -> installed;
				case "isConnected" -> connected;
				case "isDisabled" -> disabled;
				case "setConnected" -> {
					connected = (boolean) arguments[0];
					connectionWrites++;
					yield null;
				}
				case "setDisabled" -> {
					disabled = (boolean) arguments[0];
					connectionWrites++;
					yield null;
				}
				case "toString" -> "recording-voicechat-connection";
				default -> defaultValue(method.getReturnType());
			};
		}
	}

	private static final class RecordingSender implements InvocationHandler {
		private boolean registered;
		private boolean acceptPackets = true;
		private int acceptedPacketLimit = Integer.MAX_VALUE;
		private boolean encoderClosed;
		private int resetCalls;
		private final AudioSender proxy = proxy(AudioSender.class, this);
		private final List<byte[]> frames = new ArrayList<>();
		private final List<Integer> encodedSampleCounts = new ArrayList<>();
		private final OpusEncoder encoder = proxy(OpusEncoder.class, (ignored, method, arguments) -> {
			return switch (method.getName()) {
					case "encode" -> {
						short[] samples = (short[]) arguments[0];
						encodedSampleCounts.add(samples.length);
						yield new byte[] { (byte) encodedSampleCounts.size() };
					}
					case "isClosed" -> encoderClosed;
					case "close" -> {
						encoderClosed = true;
						yield null;
					}
					default -> defaultValue(method.getReturnType());
				};
		});
		@Override
		public Object invoke(Object ignored, java.lang.reflect.Method method, Object[] arguments) {
			return switch (method.getName()) {
				case "canSend" -> registered;
				case "send" -> {
					if (!registered || !acceptPackets || frames.size() >= acceptedPacketLimit) yield false;
					frames.add(((byte[]) arguments[0]).clone());
					yield true;
				}
				case "reset" -> {
					resetCalls++;
					yield registered;
				}
				case "whispering", "sequenceNumber" -> proxy;
				case "isWhispering" -> false;
				case "toString" -> "recording-audio-sender";
				default -> defaultValue(method.getReturnType());
			};
		}
	}

	private static final class RecordingDistanceEvent implements InvocationHandler {
		private final VoicechatConnection connection;
		private final VoiceDistanceEvent event = SyntheticPlayerVoiceTransportVerification.proxy(
				VoiceDistanceEvent.class, this);
		private float distance;

		private RecordingDistanceEvent(UUID playerId, float distance) {
			this.distance = distance;
			ServerPlayer player = SyntheticPlayerVoiceTransportVerification.proxy(
					ServerPlayer.class, (ignored, method, arguments) -> switch (method.getName()) {
						case "getUuid" -> playerId;
						default -> defaultValue(method.getReturnType());
					});
			this.connection = SyntheticPlayerVoiceTransportVerification.proxy(
					VoicechatConnection.class, (ignored, method, arguments) -> switch (method.getName()) {
						case "getPlayer" -> player;
						default -> defaultValue(method.getReturnType());
					});
		}

		private VoiceDistanceEvent event() {
			return event;
		}

		@Override
		public Object invoke(Object ignored, java.lang.reflect.Method method, Object[] arguments) {
			return switch (method.getName()) {
				case "getSenderConnection" -> connection;
				case "getDistance" -> distance;
				case "setDistance" -> {
					distance = ((Number) arguments[0]).floatValue();
					yield null;
				}
				default -> defaultValue(method.getReturnType());
			};
		}
	}

	private static <T> T proxy(Class<T> type, InvocationHandler handler) {
		return type.cast(Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[] { type }, handler));
	}

	private static Object defaultValue(Class<?> type) {
		if (!type.isPrimitive()) return null;
		if (type == boolean.class) return false;
		if (type == char.class) return '\0';
		return 0;
	}

	private static void assertThrows(Class<? extends Throwable> type, Runnable action, String label) {
		try {
			action.run();
		} catch (Throwable failure) {
			if (type.isInstance(failure)) return;
			throw new AssertionError(label + ": wrong failure " + failure, failure);
		}
		throw new AssertionError(label + ": expected " + type.getSimpleName());
	}

	private static void assertEquals(Object expected, Object actual, String label) {
		if (!java.util.Objects.equals(expected, actual)) {
			throw new AssertionError(label + ": expected=" + expected + " actual=" + actual);
		}
	}
}
