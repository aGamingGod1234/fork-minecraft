package dev.agaminggod.arenaagents.server;

import dev.agaminggod.arenaagents.server.bridge.MultiplexedServerBridge;
import java.io.IOException;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

/** Verifies fail-closed runtime ticks and manual-secret voice lifecycle transitions. */
public final class CodexAgentServerRuntimeVoiceStartVerification {
	private CodexAgentServerRuntimeVoiceStartVerification() {
	}

	public static int verify() throws Exception {
		String oldAutoStart = System.getProperty("arenaagents.coordinatorAutoStart");
		String oldBridgeSecret = System.getProperty("arenaagents.bridgeSecretFile");
		String oldVoiceSecret = System.getProperty("arenaagents.voiceSecretFile");
		String oldBridgePort = System.getProperty("arenaagents.bridgePort");
		Path defaultBridgeSecret = Path.of("runtime", "bridge-secret.txt");
		Path defaultVoiceSecret = Path.of("runtime", "voice-secret.txt");
		boolean hadDefaultBridgeSecret = Files.exists(defaultBridgeSecret);
		boolean hadDefaultVoiceSecret = Files.exists(defaultVoiceSecret);
		byte[] previousBridgeSecret = hadDefaultBridgeSecret ? Files.readAllBytes(defaultBridgeSecret) : null;
		byte[] previousVoiceSecret = hadDefaultVoiceSecret ? Files.readAllBytes(defaultVoiceSecret) : null;
		CoordinatorProcessSupervisor supervisor = null;
		CodexAgentServerRuntime.BridgeSlot slot = null;
		try {
			Files.createDirectories(defaultBridgeSecret.getParent());
			Files.writeString(defaultBridgeSecret, "b".repeat(32), StandardCharsets.UTF_8);
			Files.writeString(defaultVoiceSecret, "d".repeat(32), StandardCharsets.UTF_8);
			System.setProperty("arenaagents.coordinatorAutoStart", "false");
			System.clearProperty("arenaagents.bridgeSecretFile");
			System.clearProperty("arenaagents.voiceSecretFile");
			System.setProperty("arenaagents.bridgePort", Integer.toString(unusedLoopbackPort()));

			supervisor = new CoordinatorProcessSupervisor(
					Path.of("build", "default-secret-voice-game"), Map.of(), () -> 0L,
					null, null, () -> "00000000-0000-0000-0000-000000000901"
			);
			assertFalse(supervisor.configured(), "disabled autostart skips coordinator dependency preparation");
			assertTrue(CodexAgentServerRuntime.voiceConfigurationPrepared(supervisor),
					"readable default voice secret prepares voice without an explicit secret property");

			slot = new CodexAgentServerRuntime.BridgeSlot(System::currentTimeMillis);
			CodexAgentServerRuntime.reconcileBridgeConfiguration(slot, uninitializedManager(), supervisor);
			assertTrue(slot.bridge() != null, "the Java bridge starts from its separate default runtime secret");
			MultiplexedServerBridge adoptionListener = slot.bridge();
			AtomicInteger boundRuntimeTicks = new AtomicInteger();
			assertFalse(CodexAgentServerRuntime.runRestoredStateTick(false, boundRuntimeTicks::incrementAndGet),
					"failed persisted-state restoration fences the bound runtime tick");
			assertEquals(0, boundRuntimeTicks.get(),
					"failed restoration does not drain commands or mutate stale-bound agents");
			assertEquals(adoptionListener, slot.bridge(),
					"failed restoration leaves the bridge listener available for coordinator adoption");
			assertTrue(CodexAgentServerRuntime.runRestoredStateTick(true, boundRuntimeTicks::incrementAndGet),
					"a later successful restoration resumes the bound runtime tick");
			assertEquals(1, boundRuntimeTicks.get(), "successful restoration resumes bound work exactly once");

			AtomicInteger voiceStarts = new AtomicInteger();
			AtomicInteger voiceCloses = new AtomicInteger();
			CodexAgentServerRuntime.VoiceInitializationGate gate =
					new CodexAgentServerRuntime.VoiceInitializationGate(
							voiceStarts::incrementAndGet, voiceCloses::incrementAndGet
					);
			assertTrue(gate.reconcile(CodexAgentServerRuntime.voiceConfigurationPrepared(supervisor),
					CodexAgentServerRuntime.voiceConfigurationRevision(supervisor)),
					"prepared default runtime secret starts the voice subsystem");
			assertEquals(1, voiceStarts.get(), "voice starts once for the prepared default runtime secret");

			Files.writeString(defaultVoiceSecret, "invalid", StandardCharsets.UTF_8);
			boolean invalidPrepared = CodexAgentServerRuntime.voiceConfigurationPrepared(supervisor);
			assertFalse(invalidPrepared, "invalid default runtime secret keeps voice fail-closed");
			assertTrue(gate.reconcile(invalidPrepared, CodexAgentServerRuntime.voiceConfigurationRevision(supervisor)),
					"invalidating the effective manual secret fences the active voice subsystem");
			assertEquals(1, voiceCloses.get(), "invalid secret transition closes the active voice subsystem once");

			Files.writeString(defaultVoiceSecret, "r".repeat(32), StandardCharsets.UTF_8);
			assertTrue(gate.reconcile(CodexAgentServerRuntime.voiceConfigurationPrepared(supervisor),
					CodexAgentServerRuntime.voiceConfigurationRevision(supervisor)),
					"repairing the effective manual secret restarts voice without restarting Minecraft");
			assertEquals(2, voiceStarts.get(), "valid secret recovery starts one replacement voice subsystem");

			Files.delete(defaultVoiceSecret);
			boolean missingPrepared = CodexAgentServerRuntime.voiceConfigurationPrepared(supervisor);
			assertFalse(missingPrepared, "missing default runtime secret keeps voice fail-closed");
			assertTrue(gate.reconcile(missingPrepared, CodexAgentServerRuntime.voiceConfigurationRevision(supervisor)),
					"removing the effective manual secret fences the recovered voice subsystem");
			assertEquals(2, voiceCloses.get(), "missing secret transition closes the recovered voice subsystem once");

			Files.writeString(defaultVoiceSecret, "s".repeat(32), StandardCharsets.UTF_8);
			assertTrue(gate.reconcile(CodexAgentServerRuntime.voiceConfigurationPrepared(supervisor),
					CodexAgentServerRuntime.voiceConfigurationRevision(supervisor)),
					"recreating the missing manual secret recovers voice on a later tick");
			assertEquals(3, voiceStarts.get(), "missing secret recovery starts one replacement voice subsystem");
			return 19;
		} finally {
			if (slot != null) slot.close();
			if (supervisor != null) supervisor.close();
			restoreProperty("arenaagents.coordinatorAutoStart", oldAutoStart);
			restoreProperty("arenaagents.bridgeSecretFile", oldBridgeSecret);
			restoreProperty("arenaagents.voiceSecretFile", oldVoiceSecret);
			restoreProperty("arenaagents.bridgePort", oldBridgePort);
			if (hadDefaultBridgeSecret) Files.write(defaultBridgeSecret, previousBridgeSecret);
			else Files.deleteIfExists(defaultBridgeSecret);
			if (hadDefaultVoiceSecret) Files.write(defaultVoiceSecret, previousVoiceSecret);
			else Files.deleteIfExists(defaultVoiceSecret);
		}
	}

	private static CodexAgentManager uninitializedManager() {
		try {
			Field field = sun.misc.Unsafe.class.getDeclaredField("theUnsafe");
			field.setAccessible(true);
			sun.misc.Unsafe unsafe = (sun.misc.Unsafe) field.get(null);
			CodexAgentManager manager = (CodexAgentManager) unsafe.allocateInstance(CodexAgentManager.class);
			Field savedData = CodexAgentManager.class.getDeclaredField("savedData");
			unsafe.putObject(manager, unsafe.objectFieldOffset(savedData), new AgentSavedData());
			Field pendingRegistrations = CodexAgentManager.class.getDeclaredField("pendingAgentRegistrations");
			unsafe.putObject(manager, unsafe.objectFieldOffset(pendingRegistrations), new java.util.LinkedHashSet<>());
			return manager;
		} catch (ReflectiveOperationException exception) {
			throw new AssertionError("could not allocate lifecycle-only manager", exception);
		}
	}

	private static int unusedLoopbackPort() throws IOException {
		try (var socket = new java.net.ServerSocket(0, 1, java.net.InetAddress.getLoopbackAddress())) {
			return socket.getLocalPort();
		}
	}

	private static void restoreProperty(String name, String value) {
		if (value == null) System.clearProperty(name);
		else System.setProperty(name, value);
	}

	private static void assertTrue(boolean condition, String label) {
		if (!condition) throw new AssertionError(label);
	}

	private static void assertFalse(boolean condition, String label) {
		if (condition) throw new AssertionError(label);
	}

	private static void assertEquals(Object expected, Object actual, String label) {
		if (!java.util.Objects.equals(expected, actual)) {
			throw new AssertionError(label + ": expected <" + expected + "> but was <" + actual + ">");
		}
	}
}
