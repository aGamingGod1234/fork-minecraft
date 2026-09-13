package dev.agaminggod.arenaagents.server;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

/** Verifies ownership and refresh of the voice URL published by coordinator configuration. */
public final class CoordinatorVoiceEndpointRefreshVerification {
	private CoordinatorVoiceEndpointRefreshVerification() {
	}

	public static int verify() throws Exception {
		String oldAutoStart = System.getProperty("arenaagents.coordinatorAutoStart");
		String oldVoiceUrl = System.getProperty("arenaagents.voiceUrl");
		String oldBridgeSecret = System.getProperty("arenaagents.bridgeSecretFile");
		String oldVoiceSecret = System.getProperty("arenaagents.voiceSecretFile");
		Path config = Files.createTempFile("arena-supervisor-voice-refresh-", ".json");
		Path manualSecret = Files.createTempFile("arena-manual-voice-secret-", ".txt");
		AtomicInteger voiceStarts = new AtomicInteger();
		AtomicInteger voiceCloses = new AtomicInteger();
		CoordinatorProcessSupervisor managed = null;
		CoordinatorProcessSupervisor overridden = null;
		CoordinatorProcessSupervisor manual = null;
		try {
			System.setProperty("arenaagents.coordinatorAutoStart", "true");
			System.clearProperty("arenaagents.voiceUrl");
			Files.writeString(config, "{\"voice\":{\"port\":18766}}", StandardCharsets.UTF_8);
			MutableResolver managedResolver = new MutableResolver(config, "managed-initial");
			String managedLaunchId = "00000000-0000-0000-0000-000000000801";
			managed = supervisor(managedResolver, managedLaunchId);

			assertEquals("http://127.0.0.1:18766/v1/tts", System.getProperty("arenaagents.voiceUrl"),
					"initial dependency preparation publishes the config-derived voice endpoint");
			long initialVoiceRevision = managed.voiceConfigurationRevision();
			long initialBridgeRevision = managed.bridgeRevision();
			assertTrue(initialVoiceRevision > 0L,
					"initial managed endpoint publication advances the voice configuration revision");

			Files.writeString(config, "{\"voice\":{\"port\":18767}}", StandardCharsets.UTF_8);
			managedResolver.fingerprint = "managed-config-only-change";
			managed.publishDependencyFingerprintChange();
			managed.tick(true, managedLaunchId, 1L);
			assertEquals("http://127.0.0.1:18767/v1/tts", System.getProperty("arenaagents.voiceUrl"),
					"config-only dependency changes replace the supervisor-managed voice endpoint");
			assertTrue(managed.voiceConfigurationRevision() > initialVoiceRevision,
					"config-only endpoint changes advance the voice configuration revision");
			assertEquals(initialBridgeRevision, managed.bridgeRevision(),
					"voice-only changes do not restart the authenticated bridge listener");

			long refreshedRevision = managed.voiceConfigurationRevision();
			managed.publishDependencyFingerprintChange();
			managed.tick(true, managedLaunchId, 1L);
			managed.tick(true, managedLaunchId, 1L);
			assertEquals(refreshedRevision, managed.voiceConfigurationRevision(),
					"an unchanged resolved endpoint does not recreate the voice client");

			CodexAgentServerRuntime.VoiceInitializationGate voiceGate =
					new CodexAgentServerRuntime.VoiceInitializationGate(
							voiceStarts::incrementAndGet, voiceCloses::incrementAndGet
					);
			long initialRuntimeRevision = CodexAgentServerRuntime.voiceConfigurationRevision(managed);
			assertTrue(voiceGate.reconcile(true, initialRuntimeRevision),
					"prepared voice configuration creates the initial worker client");
			long endpointRevisionBeforeSecretRotation = managed.voiceConfigurationRevision();
			long bridgeRevisionBeforeSecretRotation = managed.bridgeRevision();
			managedResolver.rotateSecret("managed-secret-only-change", "w".repeat(32));
			managed.publishDependencyFingerprintChange();
			managed.tick(true, managedLaunchId, 1L);
			managed.tick(true, managedLaunchId, 1L);
			assertEquals(endpointRevisionBeforeSecretRotation, managed.voiceConfigurationRevision(),
					"secret-only rotation preserves the endpoint-only revision");
			assertTrue(managed.bridgeRevision() > bridgeRevisionBeforeSecretRotation,
					"same-path shared-secret rotation advances the bridge content revision");
			long rotatedRuntimeRevision = CodexAgentServerRuntime.voiceConfigurationRevision(managed);
			assertFalse(initialRuntimeRevision == rotatedRuntimeRevision,
					"same-path shared-secret rotation advances the voice initialization gate revision");
			assertTrue(voiceGate.reconcile(true, rotatedRuntimeRevision),
					"same-path shared-secret rotation recreates the worker client");
			assertEquals(2, voiceStarts.get(), "secret rotation starts one replacement worker client");
			assertEquals(1, voiceCloses.get(), "secret rotation closes the stale worker client once");
			managed.close();
			managed = null;
			assertEquals(null, System.getProperty("arenaagents.voiceUrl"),
					"supervisor shutdown releases its managed endpoint before a same-JVM restart");

			String explicitOverride = "http://127.0.0.1:19999/v1/tts";
			System.setProperty("arenaagents.voiceUrl", explicitOverride);
			MutableResolver overrideResolver = new MutableResolver(config, "override-initial");
			String overrideLaunchId = "00000000-0000-0000-0000-000000000802";
			overridden = supervisor(overrideResolver, overrideLaunchId);
			assertEquals(explicitOverride, System.getProperty("arenaagents.voiceUrl"),
					"initial preparation leaves an explicit user voice endpoint untouched");
			long overrideRevision = overridden.voiceConfigurationRevision();

			Files.writeString(config, "{\"voice\":{\"port\":18768}}", StandardCharsets.UTF_8);
			overrideResolver.fingerprint = "explicit-override-config-change";
			overridden.publishDependencyFingerprintChange();
			overridden.tick(true, overrideLaunchId, 1L);
			assertEquals(explicitOverride, System.getProperty("arenaagents.voiceUrl"),
					"config changes never replace an explicit user voice endpoint override");
			assertEquals(overrideRevision, overridden.voiceConfigurationRevision(),
					"ignored config endpoints do not advance the explicit override revision");
			overridden.close();
			overridden = null;
			assertEquals(explicitOverride, System.getProperty("arenaagents.voiceUrl"),
					"supervisor shutdown leaves an explicit user voice endpoint untouched");

			System.setProperty("arenaagents.coordinatorAutoStart", "false");
			System.clearProperty("arenaagents.bridgeSecretFile");
			System.setProperty("arenaagents.voiceSecretFile", manualSecret.toString());
			String initialTail = "TAIL_SECRET_MARKER_A";
			String rotatedTail = "TAIL_SECRET_MARKER_B";
			String initialManualSecret = "m".repeat(512 - initialTail.length()) + initialTail;
			String rotatedManualSecret = "m".repeat(512 - rotatedTail.length()) + rotatedTail;
			Files.writeString(manualSecret, initialManualSecret, StandardCharsets.UTF_8);
			FileTime originalTimestamp = Files.getLastModifiedTime(manualSecret);
			manual = supervisor(new MutableResolver(config, "unused-manual"),
					"00000000-0000-0000-0000-000000000803");
			assertEquals(CoordinatorRecoveryState.STOPPED, manual.snapshot().state(),
					"manual coordinator mode leaves child-process supervision stopped");
			long initialManualRevision = CodexAgentServerRuntime.voiceConfigurationRevision(manual);
			CodexAgentServerRuntime.VoiceInitializationGate manualGate =
					new CodexAgentServerRuntime.VoiceInitializationGate(
							voiceStarts::incrementAndGet, voiceCloses::incrementAndGet
					);
			assertTrue(manualGate.reconcile(true, initialManualRevision),
					"manual dedicated voice secret creates the initial worker client");
			String initialFingerprint = CodexAgentServerRuntime.explicitSecretContentFingerprint(manualSecret);
			assertEquals(
					HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
							.digest(initialManualSecret.getBytes(StandardCharsets.UTF_8))),
					initialFingerprint,
					"manual secret fingerprint covers the complete accepted credential"
			);
			assertFalse(initialFingerprint.contains(initialTail),
					"manual secret fingerprint never exposes plaintext tail content");
			Files.writeString(manualSecret, rotatedManualSecret, StandardCharsets.UTF_8);
			Files.setLastModifiedTime(manualSecret, originalTimestamp);
			assertEquals(512L, Files.size(manualSecret),
					"tail-only rotation preserves the maximum accepted secret file size");
			long rotatedManualRevision = CodexAgentServerRuntime.voiceConfigurationRevision(manual);
			assertFalse(initialManualRevision == rotatedManualRevision,
					"same-size same-timestamp tail rotation advances the voice gate revision");
			assertTrue(manualGate.reconcile(true, rotatedManualRevision),
					"manual secret rotation recreates the worker client without restarting Minecraft");
			assertEquals(4, voiceStarts.get(), "manual secret rotation starts one replacement worker client");
			assertEquals(2, voiceCloses.get(), "manual secret rotation closes the stale worker client once");
			return 27;
		} finally {
			if (managed != null) managed.close();
			if (overridden != null) overridden.close();
			if (manual != null) manual.close();
			restoreProperty("arenaagents.coordinatorAutoStart", oldAutoStart);
			restoreProperty("arenaagents.voiceUrl", oldVoiceUrl);
			restoreProperty("arenaagents.bridgeSecretFile", oldBridgeSecret);
			restoreProperty("arenaagents.voiceSecretFile", oldVoiceSecret);
			Files.deleteIfExists(config);
			Files.deleteIfExists(manualSecret);
		}
	}

	private static CoordinatorProcessSupervisor supervisor(MutableResolver resolver, String launchId) {
		return new CoordinatorProcessSupervisor(
				Path.of("build", "voice-endpoint-refresh-game"), Map.of(), () -> 100_000L, resolver,
				request -> new OwnedTestChild(),
				() -> launchId, Runnable::run, runtimeRoot -> 0, task -> { }
		);
	}

	private static final class OwnedTestChild implements CoordinatorProcessSupervisor.ChildProcess {
		private boolean alive = true;

		@Override
		public boolean isAlive() { return alive; }

		@Override
		public long pid() { return 10_000L; }

		@Override
		public void terminate() { alive = false; }
	}

	private static final class MutableResolver implements CoordinatorProcessSupervisor.DependencyResolver {
		private CoordinatorProcessSupervisor.PreparedRuntime runtime;
		private String fingerprint;

		private MutableResolver(Path config, String fingerprint) {
			this.runtime = new CoordinatorProcessSupervisor.PreparedRuntime(
					Path.of("build", "voice-endpoint-runtime"),
					Path.of("build", "voice-endpoint-runtime", "coordinator"),
					Path.of("build", "voice-endpoint-runtime", "coordinator", "src", "dynamic-main.mjs"),
					config,
					Path.of("build", "voice-endpoint-runtime", "runtime", "bridge-secret.txt"),
					Path.of("build", "voice-endpoint-runtime", "runtime", "toolchains", "node", "node.exe"),
					"v".repeat(32)
			);
			this.fingerprint = fingerprint;
		}

		@Override
		public String fingerprint() {
			return fingerprint;
		}

		@Override
		public CoordinatorProcessSupervisor.DependencyResolution resolve() {
			return CoordinatorProcessSupervisor.DependencyResolution.ready(runtime);
		}

		private void rotateSecret(String fingerprint, String secret) {
			CoordinatorProcessSupervisor.PreparedRuntime current = runtime;
			runtime = new CoordinatorProcessSupervisor.PreparedRuntime(
					current.root(), current.coordinatorRoot(), current.main(), current.config(), current.secret(),
					current.nodeExecutable(), secret, current.bridgePort(), current.generationId(),
					current.candidate(), current.lastKnownGoodAvailable()
			);
			this.fingerprint = fingerprint;
		}
	}

	private static void restoreProperty(String name, String value) {
		if (value == null) System.clearProperty(name);
		else System.setProperty(name, value);
	}

	private static void assertEquals(Object expected, Object actual, String label) {
		if (!java.util.Objects.equals(expected, actual)) {
			throw new AssertionError(label + ": expected <" + expected + "> but was <" + actual + ">");
		}
	}

	private static void assertTrue(boolean condition, String label) {
		if (!condition) throw new AssertionError(label);
	}

	private static void assertFalse(boolean condition, String label) {
		if (condition) throw new AssertionError(label);
	}
}
