package dev.agaminggod.arenaagents.server.voice;

import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BiFunction;
import net.minecraft.server.MinecraftServer;

/** Verifies retryable voice provider startup with direct worker configuration. */
public final class VoiceSubsystemRuntimeVerification {
	private VoiceSubsystemRuntimeVerification() {
	}

	public static void main(String[] arguments) {
		System.out.println("PASS: " + verify() + " voice runtime assertions");
	}

	public static int verify() {
		MinecraftServer server = uninitializedServer();
		VoiceSubsystemConfiguration configuration = new VoiceSubsystemConfiguration(
				"http://127.0.0.1:18771/v1/tts", "m".repeat(32), 45_678
		);
		VoiceSubsystemConfiguration replacementConfiguration = new VoiceSubsystemConfiguration(
				"http://127.0.0.1:18772/v1/tts", "n".repeat(32)
		);
		AtomicReference<VoiceSubsystemConfiguration> received = new AtomicReference<>();
		AtomicInteger closes = new AtomicInteger();
		AtomicInteger constructions = new AtomicInteger();
		boolean failed = VoiceSubsystemRuntime.start(server, configuration, List.of(configuredProvider((actualServer, actualConfiguration) -> {
			received.set(actualConfiguration);
			throw new IllegalStateException("voice transport is starting");
		})));
		assertFalse(failed, "a provider construction failure remains retryable instead of caching NoVoice");
		assertEquals(configuration, received.get(), "provider receives the exact in-memory endpoint and secret");
		assertEquals(45_678, received.get().requestTimeoutMs(),
				"configuration-aware provider receives the configured request timeout");

		RecordingVoiceSubsystem first = new RecordingVoiceSubsystem(closes);
		boolean promoted = VoiceSubsystemRuntime.start(server, configuration, List.of(configuredProvider((actualServer, actualConfiguration) -> {
			constructions.incrementAndGet();
			return first;
		})));
		assertTrue(promoted, "a later provider construction succeeds without restarting Minecraft");
		assertTrue(VoiceSubsystemRuntime.available(server), "successful retry promotes the real voice subsystem");
		assertTrue(VoiceSubsystemRuntime.start(server, configuration, List.of(configuredProvider((actualServer, actualConfiguration) -> {
			throw new AssertionError("identical configuration must not reconstruct voice");
		}))), "identical voice start is idempotent");
		assertEquals(1, constructions.get(), "identical voice start preserves one runtime");

		RecordingVoiceSubsystem reconfigured = new RecordingVoiceSubsystem(closes);
		assertTrue(VoiceSubsystemRuntime.start(server, replacementConfiguration,
				List.of(configuredProvider((actualServer, actualConfiguration) -> {
					assertEquals(replacementConfiguration, actualConfiguration,
							"reconfigure retains exact endpoint and secret ownership");
					constructions.incrementAndGet();
					return reconfigured;
				}))), "configuration change replaces the live runtime");
		assertEquals(1, closes.get(), "reconfigure closes the displaced runtime once");

		reconfigured.available = false;
		RecordingVoiceSubsystem recovered = new RecordingVoiceSubsystem(closes);
		assertTrue(VoiceSubsystemRuntime.start(server, replacementConfiguration,
				List.of(configuredProvider((actualServer, actualConfiguration) -> {
					constructions.incrementAndGet();
					return recovered;
				}))), "a failed live runtime is reconstructed without restarting Minecraft");
		assertEquals(2, closes.get(), "live failure closes the failed generation once");
		assertTrue(VoiceSubsystemRuntime.available(server), "live failure recovery promotes the replacement");
		java.util.UUID humanPlayer = java.util.UUID.randomUUID();
		VoiceConsentRegistry.grant(server, humanPlayer);
		VoiceConsentRegistry.revoke(server, humanPlayer);
		assertEquals(humanPlayer, recovered.cancelledHumanSpeech,
				"consent revocation cancels the live subsystem's buffered and in-flight speech");
		dev.agaminggod.arenaagents.agent.AgentId agentId = dev.agaminggod.arenaagents.agent.AgentId.parse(
				"00000000-0000-4000-8000-000000000777"
		);
		assertTrue(VoiceSubsystemRuntime.availabilityFallbackTransition(
				server, agentId, "VOICE_TRANSPORT_UNAVAILABLE"
		), "first availability fallback is a diagnostic transition");
		assertFalse(VoiceSubsystemRuntime.availabilityFallbackTransition(
				server, agentId, "VOICE_TRANSPORT_UNAVAILABLE"
		), "identical availability fallback is deduplicated");
		assertEquals("VOICE_TRANSPORT_UNAVAILABLE",
				VoiceSubsystemRuntime.clearAvailabilityFallback(server, agentId),
				"successful availability clears the prior fallback once");
		assertEquals(null, VoiceSubsystemRuntime.clearAvailabilityFallback(server, agentId),
				"repeated recovery emits no duplicate transition");
		assertTrue(VoiceSubsystemRuntime.availabilityFallbackTransition(
				server, agentId, "VOICE_TRANSPORT_UNAVAILABLE"
		), "a later fallback is visible after recovery");
		assertEquals(1L, VoiceSubsystemRuntime.nextConversationSequence(server, agentId),
				"first voice conversation uses the first per-agent sequence");
		assertEquals(2L, VoiceSubsystemRuntime.nextConversationSequence(server, agentId),
				"voice conversations advance the per-agent sequence");
		VoiceSubsystemRuntime.removeAgent(server, agentId);
		assertEquals(1L, VoiceSubsystemRuntime.nextConversationSequence(server, agentId),
				"agent removal evicts voice sequence and diagnostic state");
		VoiceRequest request = new VoiceRequest(agentId, "Recovered voice.", "voice.auto.v1", 48, 1L);
		assertEquals(VoiceReceipt.Status.ACCEPTED,
				VoiceSubsystemRuntime.speak(server, request).toCompletableFuture().join().status(),
				"healthy voice remains usable after recovery");
		assertEquals(null, VoiceSubsystemRuntime.clearAvailabilityFallback(server, agentId),
				"successful speech clears the availability fallback transition");
		recovered.speakFailure = new IllegalStateException("secret must-not-reach-control-work");
		assertEquals(VoiceReceipt.Status.DEGRADED_TO_TEXT,
				VoiceSubsystemRuntime.speak(server, request).toCompletableFuture().join().status(),
				"synchronous voice failure degrades without rejecting control work");
		VoiceSubsystemRuntime.close(server);
		VoiceSubsystemRuntime.close(server);
		assertEquals(3, closes.get(), "current voice runtime closes exactly once");

		MinecraftServer startupRaceServer = uninitializedServer();
		AtomicInteger startupAttempts = new AtomicInteger();
		RecordingVoiceSubsystem startupRecovered = new RecordingVoiceSubsystem(new AtomicInteger());
		VoiceSubsystemProvider startupRaceProvider = configuredProvider((actualServer, actualConfiguration) -> {
			if (startupAttempts.getAndIncrement() == 0) {
				throw new IllegalStateException("Simple Voice Chat has not registered yet");
			}
			return startupRecovered;
		});
		assertFalse(VoiceSubsystemRuntime.start(startupRaceServer, configuration, List.of(startupRaceProvider)),
				"a provider waiting for Simple Voice Chat remains retryable");
		assertFalse(VoiceSubsystemRuntime.available(startupRaceServer),
				"a startup registration race does not install a permanent no-voice runtime");
		assertTrue(VoiceSubsystemRuntime.retryPending(startupRaceServer, Long.MAX_VALUE),
				"the pending voice provider retries after Simple Voice Chat registration");
		assertTrue(VoiceSubsystemRuntime.available(startupRaceServer),
				"the retry promotes the real voice subsystem without restarting Minecraft");
		VoiceSubsystemRuntime.close(startupRaceServer);
		return 30 + verifyLegacyProviderCompatibility(configuration)
				+ verifyLinkageFailureFallback(configuration)
				+ verifyOptionalConfigurationFailure() + verifyEndpointValidation()
				+ verifyConsentCancellationLockOrder();
	}

	private static int verifyConsentCancellationLockOrder() {
		MinecraftServer server = uninitializedServer();
		VoiceSubsystemConfiguration configuration = new VoiceSubsystemConfiguration(
				"http://127.0.0.1:18774/v1/tts", "l".repeat(32)
		);
		UUID playerId = UUID.randomUUID();
		Object captureMonitor = new Object();
		CountDownLatch captureMonitorHeld = new CountDownLatch(1);
		CountDownLatch continueCapture = new CountDownLatch(1);
		CountDownLatch cancellationStarted = new CountDownLatch(1);
		AtomicBoolean captureRan = new AtomicBoolean();
		AtomicReference<Boolean> captureAccepted = new AtomicReference<>();
		CancellationLockVoiceSubsystem subsystem = new CancellationLockVoiceSubsystem(
				captureMonitor, cancellationStarted
		);
		assertTrue(VoiceSubsystemRuntime.start(server, configuration,
				List.of(configuredProvider((actualServer, actualConfiguration) -> subsystem))),
				"lock-order verification installs its voice subsystem");
		VoiceConsentRegistry.grant(server, playerId);

		Thread microphone = Thread.ofPlatform().daemon().name("voice-consent-lock-test-microphone").unstarted(() -> {
			synchronized (captureMonitor) {
				captureMonitorHeld.countDown();
				await(continueCapture, "microphone callback was not released");
				captureAccepted.set(VoiceConsentRegistry.captureWhileGranted(
						server, playerId, () -> captureRan.set(true)
				));
			}
		});
		Thread revoke = Thread.ofPlatform().daemon().name("voice-consent-lock-test-revoke").unstarted(
				() -> VoiceConsentRegistry.revoke(server, playerId)
		);
		microphone.start();
		await(captureMonitorHeld, "microphone callback did not acquire the capture monitor");
		revoke.start();
		await(cancellationStarted, "consent revocation did not reach capture cancellation");
		continueCapture.countDown();
		join(microphone, "microphone callback deadlocked with consent revocation");
		join(revoke, "consent revocation deadlocked with the microphone callback");

		assertEquals(false, captureAccepted.get(),
				"a callback racing after revocation cannot accept captured speech");
		assertFalse(captureRan.get(), "revoked capture work remains fenced during the lock-order race");
		assertEquals(1, subsystem.cancellations.get(),
				"revocation cancels the exact live capture generation once");
		VoiceSubsystemRuntime.close(server);
		return 6;
	}

	private static void await(CountDownLatch latch, String message) {
		try {
			if (!latch.await(2L, TimeUnit.SECONDS)) throw new AssertionError(message);
		} catch (InterruptedException exception) {
			Thread.currentThread().interrupt();
			throw new AssertionError(message, exception);
		}
	}

	private static void join(Thread thread, String message) {
		try {
			thread.join(2_000L);
		} catch (InterruptedException exception) {
			Thread.currentThread().interrupt();
			throw new AssertionError(message, exception);
		}
		if (thread.isAlive()) throw new AssertionError(message);
	}

	private static int verifyLegacyProviderCompatibility(VoiceSubsystemConfiguration configuration) {
		MinecraftServer server = uninitializedServer();
		AtomicInteger starts = new AtomicInteger();
		VoiceSubsystemProvider legacyProvider = new LegacyVoiceProvider(starts);
		try {
			assertEquals(VoiceSubsystemProvider.class, legacyProvider.getClass().getMethod(
					"create", MinecraftServer.class, VoiceSubsystemConfiguration.class
			).getDeclaringClass(), "old provider resolves the configuration overload to the core default");
		} catch (NoSuchMethodException exception) {
			throw new AssertionError("configuration-aware provider overload is missing", exception);
		}
		try {
			assertTrue(VoiceSubsystem.class.getMethod("cancelHumanSpeech", UUID.class).isDefault(),
					"human speech cancellation remains a binary-compatible interface default");
		} catch (NoSuchMethodException exception) {
			throw new AssertionError("human speech cancellation method is missing", exception);
		}
		assertTrue(VoiceSubsystemRuntime.start(server, configuration, List.of(legacyProvider)),
				"a provider with only the original one-argument method starts on the current core");
		assertEquals(1, starts.get(), "current core invokes the old provider exactly once");
		assertTrue(VoiceSubsystemRuntime.available(server), "old provider remains usable after startup");
		UUID playerId = UUID.randomUUID();
		VoiceConsentRegistry.grant(server, playerId);
		VoiceConsentRegistry.revoke(server, playerId);
		assertTrue(VoiceSubsystemRuntime.available(server),
				"disconnect and voice-off cancellation remain safe for an old subsystem implementation");
		VoiceSubsystemRuntime.close(server);
		return 6;
	}

	private static int verifyLinkageFailureFallback(VoiceSubsystemConfiguration configuration) {
		MinecraftServer server = uninitializedServer();
		VoiceSubsystemProvider incompatibleProvider = actualServer -> {
			throw new NoClassDefFoundError("optional voice transport dependency");
		};
		assertFalse(VoiceSubsystemRuntime.start(server, configuration, List.of(incompatibleProvider)),
				"an incompatible provider degrades to retryable text fallback instead of aborting startup");
		assertFalse(VoiceSubsystemRuntime.available(server),
				"a provider linkage failure does not install a broken voice subsystem");
		VoiceSubsystemRuntime.close(server);
		return 2;
	}

	private static VoiceSubsystemProvider configuredProvider(
			BiFunction<MinecraftServer, VoiceSubsystemConfiguration, VoiceSubsystem> factory
	) {
		return new VoiceSubsystemProvider() {
			@Override
			public VoiceSubsystem create(MinecraftServer server) {
				throw new IllegalStateException("configuration-aware test provider requires configuration");
			}

			@Override
			public VoiceSubsystem create(MinecraftServer server, VoiceSubsystemConfiguration configuration) {
				return factory.apply(server, configuration);
			}
		};
	}

	private static int verifyEndpointValidation() {
		List<String> validEndpoints = List.of(
				"http://127.0.0.1:1/v1/tts",
				"https://127.0.0.1:65535/v1/tts"
		);
		for (String endpoint : validEndpoints) {
			assertEquals(endpoint, new VoiceSubsystemConfiguration(endpoint, "s".repeat(32)).endpoint(),
					"voice accepts the coordinator's loopback URI boundaries");
		}
		assertEquals(1, new VoiceSubsystemConfiguration(
				"http://127.0.0.1:8766/v1/tts", "s".repeat(32), 1
		).requestTimeoutMs(), "voice accepts the minimum request timeout");
		assertEquals(600_000, new VoiceSubsystemConfiguration(
				"http://127.0.0.1:8766/v1/tts", "s".repeat(32), 600_000
		).requestTimeoutMs(), "voice accepts the maximum request timeout");
		for (int invalidTimeout : List.of(0, 600_001)) {
			try {
				new VoiceSubsystemConfiguration(
						"http://127.0.0.1:8766/v1/tts", "s".repeat(32), invalidTimeout
				);
				throw new AssertionError("unsafe voice request timeout must be rejected: " + invalidTimeout);
			} catch (IllegalArgumentException expected) {
				// The HTTP worker and client share this bounded deadline.
			}
		}

		List<String> invalidEndpoints = List.of(
				"http://example.com:8766/v1/tts",
				"http://localhost:8766/v1/tts",
				"http://127.0.0.2:8766/v1/tts",
				"http://127.0.0.1.example.com:8766/v1/tts",
				"http://2130706433:8766/v1/tts",
				"http://0177.0.0.1:8766/v1/tts",
				"http://[::1]:8766/v1/tts",
				"ftp://127.0.0.1:8766/v1/tts",
				"file://127.0.0.1:8766/v1/tts",
				"//127.0.0.1:8766/v1/tts",
				"http:127.0.0.1:8766/v1/tts",
				"http://127.0.0.1/v1/tts",
				"http://127.0.0.1:0/v1/tts",
				"http://127.0.0.1:65536/v1/tts",
				"http://user:secret@127.0.0.1:8766/v1/tts",
				"http://127.0.0.1:8766/v1/tts?token=secret",
				"http://127.0.0.1:8766/v1/tts#secret",
				"http://127.0.0.1:8766/",
				"http://127.0.0.1:8766/v1/stt",
				"http://127.0.0.1:8766/v1/tts/",
				"http://127.0.0.1:8766/v1%2Ftts",
				"http://127.0.0.1:8766/v1/../v1/tts",
				"http://%31%32%37.0.0.1:8766/v1/tts",
				"http://127.0.0.1:8766//v1/tts"
		);
		for (String endpoint : invalidEndpoints) {
			try {
				new VoiceSubsystemConfiguration(endpoint, "s".repeat(32));
				throw new AssertionError("unsafe voice endpoint must be rejected: " + endpoint);
			} catch (IllegalArgumentException expected) {
				// Every rejected form could redirect authenticated voice traffic outside the worker contract.
			}
		}
		return validEndpoints.size() + invalidEndpoints.size() + 4;
	}

	private static int verifyOptionalConfigurationFailure() {
		String oldVoiceSecret = System.getProperty("arenaagents.voiceSecretFile");
		Path directory = null;
		MinecraftServer unreadableServer = uninitializedServer();
		MinecraftServer longSecretServer = uninitializedServer();
		try {
			directory = Files.createTempDirectory("arena-optional-voice-secret");
			System.setProperty("arenaagents.voiceSecretFile", directory.resolve("missing.txt").toString());
			assertFalse(VoiceSubsystemRuntime.start(unreadableServer),
					"an unreadable optional voice secret degrades without throwing into bridge startup");
			dev.agaminggod.arenaagents.agent.AgentId agentId = dev.agaminggod.arenaagents.agent.AgentId.parse(
					"00000000-0000-4000-8000-000000000778"
			);
			VoiceRequest request = new VoiceRequest(agentId, "Text remains available.", "voice.auto.v1", 48, 1L);
			assertEquals(VoiceReceipt.Status.DEGRADED_TO_TEXT,
					VoiceSubsystemRuntime.speak(unreadableServer, request).toCompletableFuture().join().status(),
					"unreadable optional voice configuration keeps text fallback available");
			Files.writeString(directory.resolve("missing.txt"), "r".repeat(32));
			assertTrue(VoiceSubsystemRuntime.retryLegacyPending(unreadableServer, Long.MAX_VALUE),
					"voice configuration retries automatically after its secret becomes readable");

			for (int length : List.of(257, 512)) {
				VoiceSubsystemConfiguration longSecret = new VoiceSubsystemConfiguration(
						"http://127.0.0.1:18773/v1/tts", "s".repeat(length)
				);
				assertEquals(length, longSecret.secret().length(),
						"voice accepts every tested bridge-valid shared-secret boundary");
				assertTrue(VoiceSubsystemRuntime.start(longSecretServer, longSecret, List.of()),
						"bridge-valid shared secret cannot disable optional voice startup");
				VoiceSubsystemRuntime.close(longSecretServer);
			}
			try {
				new VoiceSubsystemConfiguration("http://127.0.0.1:18773/v1/tts", "s".repeat(513));
				throw new AssertionError("voice secret above the bridge boundary must be rejected");
			} catch (IllegalArgumentException expected) {
				// Exact bridge boundary is enforced.
			}
			return 8;
		} catch (java.io.IOException exception) {
			throw new AssertionError("could not prepare optional voice configuration verification", exception);
		} finally {
			VoiceSubsystemRuntime.close(unreadableServer);
			VoiceSubsystemRuntime.close(longSecretServer);
			if (oldVoiceSecret == null) System.clearProperty("arenaagents.voiceSecretFile");
			else System.setProperty("arenaagents.voiceSecretFile", oldVoiceSecret);
			if (directory != null) {
				try {
					Files.deleteIfExists(directory.resolve("missing.txt"));
					Files.deleteIfExists(directory);
				} catch (java.io.IOException exception) {
					throw new AssertionError("could not clean optional voice configuration verification", exception);
				}
			}
		}
	}

	private static MinecraftServer uninitializedServer() {
		try {
			Field field = sun.misc.Unsafe.class.getDeclaredField("theUnsafe");
			field.setAccessible(true);
			sun.misc.Unsafe unsafe = (sun.misc.Unsafe) field.get(null);
			return (MinecraftServer) unsafe.allocateInstance(net.minecraft.server.dedicated.DedicatedServer.class);
		} catch (ReflectiveOperationException exception) {
			throw new AssertionError("could not allocate voice-runtime-only server", exception);
		}
	}

	private static final class RecordingVoiceSubsystem implements VoiceSubsystem {
		private final AtomicInteger closes;
		private boolean available = true;
		private RuntimeException speakFailure;
		private java.util.UUID cancelledHumanSpeech;

		private RecordingVoiceSubsystem(AtomicInteger closes) {
			this.closes = closes;
		}

		@Override
		public boolean available() {
			return available;
		}

		@Override
		public void registerAgent(dev.agaminggod.arenaagents.agent.AgentId agentId, java.util.UUID entityId) {
		}

		@Override
		public void unregisterAgent(dev.agaminggod.arenaagents.agent.AgentId agentId) {
		}

		@Override
		public java.util.concurrent.CompletionStage<VoiceReceipt> speak(VoiceRequest request) {
			if (speakFailure != null) throw speakFailure;
			return CompletableFuture.completedFuture(VoiceReceipt.accepted());
		}

		@Override
		public void stop(dev.agaminggod.arenaagents.agent.AgentId agentId) {
		}

		@Override
		public void cancelHumanSpeech(java.util.UUID playerId) {
			cancelledHumanSpeech = playerId;
		}

		@Override
		public void close() {
			closes.incrementAndGet();
		}
	}

	private static final class CancellationLockVoiceSubsystem implements VoiceSubsystem {
		private final Object captureMonitor;
		private final CountDownLatch cancellationStarted;
		private final AtomicInteger cancellations = new AtomicInteger();

		private CancellationLockVoiceSubsystem(Object captureMonitor, CountDownLatch cancellationStarted) {
			this.captureMonitor = captureMonitor;
			this.cancellationStarted = cancellationStarted;
		}

		@Override public boolean available() { return true; }
		@Override public void registerAgent(dev.agaminggod.arenaagents.agent.AgentId agentId, UUID entityId) { }
		@Override public void unregisterAgent(dev.agaminggod.arenaagents.agent.AgentId agentId) { }
		@Override public java.util.concurrent.CompletionStage<VoiceReceipt> speak(VoiceRequest request) {
			return CompletableFuture.completedFuture(VoiceReceipt.accepted());
		}
		@Override public void stop(dev.agaminggod.arenaagents.agent.AgentId agentId) { }
		@Override public void cancelHumanSpeech(UUID playerId) {
			cancellationStarted.countDown();
			synchronized (captureMonitor) {
				cancellations.incrementAndGet();
			}
		}
		@Override public void close() { }
	}

	private static final class LegacyVoiceProvider implements VoiceSubsystemProvider {
		private final AtomicInteger starts;

		private LegacyVoiceProvider(AtomicInteger starts) {
			this.starts = starts;
		}

		@Override
		public VoiceSubsystem create(MinecraftServer server) {
			starts.incrementAndGet();
			return new LegacyVoiceSubsystem();
		}
	}

	private static final class LegacyVoiceSubsystem implements VoiceSubsystem {
		@Override public boolean available() { return true; }
		@Override public void registerAgent(dev.agaminggod.arenaagents.agent.AgentId agentId, UUID entityId) { }
		@Override public void unregisterAgent(dev.agaminggod.arenaagents.agent.AgentId agentId) { }
		@Override public java.util.concurrent.CompletionStage<VoiceReceipt> speak(VoiceRequest request) {
			return java.util.concurrent.CompletableFuture.completedFuture(VoiceReceipt.accepted());
		}
		@Override public void stop(dev.agaminggod.arenaagents.agent.AgentId agentId) { }
		@Override public void close() { }
	}

	private static void assertTrue(boolean value, String label) {
		if (!value) throw new AssertionError(label);
	}

	private static void assertFalse(boolean value, String label) {
		if (value) throw new AssertionError(label);
	}

	private static void assertEquals(Object expected, Object actual, String label) {
		if (!java.util.Objects.equals(expected, actual)) {
			throw new AssertionError(label + ": expected=" + expected + ", actual=" + actual);
		}
	}
}
