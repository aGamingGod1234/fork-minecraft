package dev.agaminggod.arenaagents.server;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.AclEntry;
import java.nio.file.attribute.AclEntryFlag;
import java.nio.file.attribute.AclEntryPermission;
import java.nio.file.attribute.AclEntryType;
import java.nio.file.attribute.AclFileAttributeView;
import java.nio.file.attribute.PosixFileAttributeView;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.UserPrincipal;
import java.security.MessageDigest;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.HexFormat;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

public final class BundledCoordinatorInstallerVerification {
	private BundledCoordinatorInstallerVerification() {
	}

	public static void main(String[] arguments) throws Exception {
		System.out.println("PASS: " + verify() + " coordinator generation assertions");
	}

	public static int verify() throws Exception {
		int assertions = 0;
		assertions += verifyPrivateAclUsesInteractiveUser();
		assertions += verifyWindowsRuntimeAclReachesStateFile();
		assertions += verifyConfiguredSecretPathUsesPreparedRuntime();
		assertions += verifyVerifiedGenerationCanRollbackCandidate();
		assertions += verifyRejectedGenerationMarkerSafety();
		assertions += verifyInterruptedRollbackRetainsRejectedGeneration();
		assertions += verifyCorruptedActiveGenerationRollsBackToVerifiedRuntime();
		assertions += verifySupervisorAutomaticallyRestoresCorruptedRuntime();
		assertions += verifySupervisorRejectsCorruptionWithoutValidLastKnownGood();
		assertions += verifySoleCandidateIsRetainedForRetry();
		assertions += verifyStaleCoordinatorIsReplacedWithoutTouchingRuntimeState();
		assertions += verifyIncompleteBundleLeavesExistingCoordinatorIntact();
		assertions += verifyFreshInstallCreatesSecretAndPreservesProviderConfig();
		assertions += verifyInterruptedSwapRecoversPreviousCoordinator();
		assertions += verifyTransientDirectoryLockIsRetried();
		return assertions;
	}

	private static int verifyWindowsRuntimeAclReachesStateFile() throws Exception {
		Path packageRoot = Files.createTempDirectory("arena-coordinator-windows-acl");
		try {
			Path runtime = packageRoot.resolve("runtime");
			if (Files.getFileAttributeView(runtime, AclFileAttributeView.class) == null) return 0;
			byte[] main = "acl main".getBytes(StandardCharsets.UTF_8);
			byte[] config = "{}".getBytes(StandardCharsets.UTF_8);
			String manifest = manifest(entry("src/dynamic-main.mjs", main), entry("config/dynamic-agents.json", config));
			BundledCoordinatorInstaller.install(packageRoot, resource(resources(manifest, main, config)));

			UserPrincipal currentUser = packageRoot.getFileSystem().getUserPrincipalLookupService()
					.lookupPrincipalByName(System.getProperty("user.name"));
			assertPrivateWindowsAcl(
					runtime,
					currentUser,
					Set.of(AclEntryFlag.DIRECTORY_INHERIT, AclEntryFlag.FILE_INHERIT),
					"runtime directory ACL is inherited by new coordinator state"
			);
			assertPrivateWindowsAcl(
					runtime.resolve("coordinator-generation.properties"),
					currentUser,
					Set.of(),
					"coordinator journal is readable and writable by the interactive user"
			);
			return 10;
		} finally {
			deleteTree(packageRoot);
		}
	}

	private static void assertPrivateWindowsAcl(
			Path target,
			UserPrincipal currentUser,
			Set<AclEntryFlag> expectedFlags,
			String label
	) throws IOException {
		AclFileAttributeView acl = Files.getFileAttributeView(target, AclFileAttributeView.class);
		assertEquals(currentUser, acl.getOwner(), label + " owner");
		List<AclEntry> entries = acl.getAcl();
		assertEquals(1, entries.size(), label + " entry count");
		AclEntry entry = entries.getFirst();
		assertEquals(AclEntryType.ALLOW, entry.type(), label + " entry type");
		assertEquals(currentUser, entry.principal(), label + " principal");
		assertEquals(expectedFlags, entry.flags(), label + " inheritance flags");
		assertTrue(entry.permissions().containsAll(EnumSet.allOf(AclEntryPermission.class)), label + " permissions");
	}

	private static int verifyPrivateAclUsesInteractiveUser() {
		UserPrincipal currentUser = () -> "DESKTOP\\User";
		UserPrincipal inheritedOwner = () -> "BUILTIN\\Administrators";
		assertEquals(
				currentUser,
				BundledCoordinatorInstaller.privateAclPrincipal(currentUser, inheritedOwner),
				"private Windows ACL grants the interactive user instead of an inherited administrator owner"
		);
		return 1;
	}

	private static int verifySupervisorAutomaticallyRestoresCorruptedRuntime() throws Exception {
		Path root = Files.createTempDirectory("arena-supervisor-corrupt-lkg");
		String oldPackageRoot = System.getProperty("arenaagents.packageRoot");
		String oldNodePath = System.getProperty(NodeRuntimeLocator.PROPERTY);
		CoordinatorProcessSupervisor supervisor = null;
		Process staleCoordinator = null;
		try {
			RuntimeFixture fixture = stageRuntimeFixture(root.resolve("package"), true);
			String failedGeneration = BundledCoordinatorInstaller.validate(fixture.packageRoot()).generationId();
			staleCoordinator = startOwnedSleeper(fixture.activeMain());
			CoordinatorProcessOwnership.record(
					fixture.packageRoot(), staleCoordinator, fixture.activeMain(), failedGeneration,
					"00000000-0000-0000-0000-000000000898"
			);
			invalidateOwnershipOwner(fixture.packageRoot());
			Files.delete(fixture.activeMain());
			Path game = blockBundledRefresh(root.resolve("game"));
			System.setProperty("arenaagents.packageRoot", fixture.packageRoot().toString());
			System.setProperty(NodeRuntimeLocator.PROPERTY, findHostNode().toString());
			FakeClock clock = new FakeClock();
			FakeLauncher launcher = new FakeLauncher();
			AtomicInteger launchIds = new AtomicInteger();
			supervisor = new CoordinatorProcessSupervisor(
					game,
					Map.of(),
					clock,
					null,
					launcher,
					() -> "00000000-0000-0000-0000-%012d".formatted(launchIds.incrementAndGet()),
					Runnable::run,
					CoordinatorProcessOwnership::reapOrphaned,
					task -> { }
			);

			assertTrue(staleCoordinator.waitFor(5L, TimeUnit.SECONDS),
					"production startup reaps the exact stale coordinator before touching corrupt active bytes");
			assertFalse(Files.exists(CoordinatorProcessOwnership.ownershipFile(fixture.packageRoot())),
					"production startup clears the stale ownership record before restoring the runtime");
			assertTrue(supervisor.configured(),
					"supervisor restores a verified LKG when bundled refresh cannot repair the corrupt active runtime");
			assertEquals(fixture.verifiedGeneration(), supervisor.runtimeGenerationId(),
					"supervisor publishes the exact restored verified generation");
			assertEquals("verified main", Files.readString(fixture.activeMain()),
					"supervisor restores verified runtime bytes before launch");
			assertFalse(Files.exists(fixture.packageRoot().resolve("coordinator.last-known-good")),
					"automatic restoration consumes the retained generation once");

			clock.advance(3_000L);
			supervisor.tick(false, null, 0L);
			assertEquals(1, launcher.launches.size(), "automatic restoration starts one coordinator generation");
			assertEquals(fixture.verifiedGeneration(), launcher.launches.getFirst().generationId(),
					"automatic restoration launches only the verified generation");
			String launchId = supervisor.snapshot().launchId();
			supervisor.tick(true, launchId, 1L);
			assertEquals(CoordinatorRecoveryState.HEALTHY, supervisor.snapshot().state(),
					"restored verified runtime authenticates and returns automatically to healthy");
			assertEquals(1L, launcher.children.stream().filter(FakeChild::isAlive).count(),
					"automatic restoration owns exactly one live generation");
			return 10;
		} finally {
			if (supervisor != null) supervisor.close();
			if (staleCoordinator != null && staleCoordinator.isAlive()) {
				CoordinatorProcessOwnership.terminateTree(staleCoordinator.toHandle());
			}
			restoreProperty("arenaagents.packageRoot", oldPackageRoot);
			restoreProperty(NodeRuntimeLocator.PROPERTY, oldNodePath);
			deleteTree(root);
		}
	}

	private static Path findHostNode() throws IOException {
		String path = System.getenv("PATH");
		boolean windows = System.getProperty("os.name", "").toLowerCase(java.util.Locale.ROOT).contains("win");
		String executableName = windows ? "node.exe" : "node";
		if (path != null) {
			for (String entry : path.split(java.util.regex.Pattern.quote(java.io.File.pathSeparator), -1)) {
				if (entry.isBlank()) continue;
				Path candidate = Path.of(entry).resolve(executableName).toAbsolutePath().normalize();
				if (Files.isRegularFile(candidate) && Files.isExecutable(candidate)) return candidate;
			}
		}
		throw new IOException("Node 22+ is required for the supervisor recovery verification fixture");
	}

	private static Process startOwnedSleeper(Path main) throws Exception {
		String executable = Path.of(
				System.getProperty("java.home"),
				"bin",
				System.getProperty("os.name", "").toLowerCase(java.util.Locale.ROOT).contains("win")
						? "java.exe" : "java"
		).toString();
		Process process = new ProcessBuilder(
				executable,
				"-cp",
				CoordinatorProcessOwnershipVerification.fixtureClassPath(),
				CoordinatorProcessFixture.class.getName(),
				main.toString()
		).start();
		Thread.sleep(100L);
		assertTrue(process.isAlive(), "stale coordinator fixture starts before runtime corruption");
		return process;
	}

	private static void invalidateOwnershipOwner(Path runtimeRoot) throws IOException {
		Path ownership = CoordinatorProcessOwnership.ownershipFile(runtimeRoot);
		Properties values = new Properties();
		try (var reader = Files.newBufferedReader(ownership)) {
			values.load(reader);
		}
		values.setProperty("ownerPid", Long.toString(Long.MAX_VALUE));
		try (var writer = Files.newBufferedWriter(ownership)) {
			values.store(writer, "stale Minecraft owner fixture");
		}
	}

	private static int verifySupervisorRejectsCorruptionWithoutValidLastKnownGood() throws Exception {
		int assertions = 0;
		for (boolean retainThenCorruptLkg : List.of(false, true)) {
			Path root = Files.createTempDirectory("arena-supervisor-invalid-lkg");
			String oldPackageRoot = System.getProperty("arenaagents.packageRoot");
			CoordinatorProcessSupervisor supervisor = null;
			try {
				RuntimeFixture fixture = stageRuntimeFixture(root.resolve("package"), retainThenCorruptLkg);
				Files.writeString(fixture.activeMain(), "corrupted active main", StandardCharsets.UTF_8);
				if (retainThenCorruptLkg) {
					Files.writeString(
							fixture.packageRoot().resolve("coordinator.last-known-good/src/dynamic-main.mjs"),
							"corrupted retained main",
							StandardCharsets.UTF_8
					);
				}
				Path game = blockBundledRefresh(root.resolve("game"));
				System.setProperty("arenaagents.packageRoot", fixture.packageRoot().toString());
				FakeClock clock = new FakeClock();
				FakeLauncher launcher = new FakeLauncher();
				supervisor = new CoordinatorProcessSupervisor(
						game,
						Map.of(),
						clock,
						null,
						launcher,
						() -> "00000000-0000-0000-0000-000000000901",
						Runnable::run,
						runtimeRoot -> 0,
						task -> { }
				);

				assertEquals(CoordinatorRecoveryState.BLOCKED_RETRYABLE, supervisor.snapshot().state(),
						"corrupt runtime without a valid verified LKG remains safely retryable");
				assertFalse(supervisor.configured(),
						"corrupt runtime without a valid verified LKG is never launchable");
				clock.advance(60_000L);
				supervisor.tick(false, null, 0L);
				assertTrue(launcher.launches.isEmpty(),
						"blocked corruption never launches an unverified or empty fallback");
				assertTrue(supervisor.snapshot().nextRetryEpochMs() > clock.now,
						"blocked corruption retains a bounded automatic revalidation deadline");
				assertions += 4;
			} finally {
				if (supervisor != null) supervisor.close();
				restoreProperty("arenaagents.packageRoot", oldPackageRoot);
				deleteTree(root);
			}
		}
		return assertions;
	}

	private static RuntimeFixture stageRuntimeFixture(Path packageRoot, boolean withLastKnownGood) throws Exception {
		byte[] verifiedMain = "verified main".getBytes(StandardCharsets.UTF_8);
		byte[] config = "{}".getBytes(StandardCharsets.UTF_8);
		String verifiedManifest = manifest(
				entry("src/dynamic-main.mjs", verifiedMain),
				entry("config/dynamic-agents.json", config)
		);
		String verifiedGeneration = sha256(verifiedManifest.getBytes(StandardCharsets.UTF_8));
		BundledCoordinatorInstaller.install(
				packageRoot,
				resource(resources(verifiedManifest, verifiedMain, config))
		);
		if (!withLastKnownGood) {
			return new RuntimeFixture(
					packageRoot,
					packageRoot.resolve("coordinator/src/dynamic-main.mjs"),
					verifiedGeneration
			);
		}
		BundledCoordinatorInstaller.promote(packageRoot, verifiedGeneration);
		byte[] candidateMain = "candidate main".getBytes(StandardCharsets.UTF_8);
		String candidateManifest = manifest(
				entry("src/dynamic-main.mjs", candidateMain),
				entry("config/dynamic-agents.json", config)
		);
		BundledCoordinatorInstaller.install(
				packageRoot,
				resource(resources(candidateManifest, candidateMain, config))
		);
		return new RuntimeFixture(
				packageRoot,
				packageRoot.resolve("coordinator/src/dynamic-main.mjs"),
				verifiedGeneration
		);
	}

	private static Path blockBundledRefresh(Path game) throws IOException {
		Files.createDirectories(game);
		Files.writeString(game.resolve("arena-agents-runtime"), "bundled refresh blocked", StandardCharsets.UTF_8);
		return game;
	}

	private record RuntimeFixture(Path packageRoot, Path activeMain, String verifiedGeneration) {
	}

	private static final class FakeClock implements java.util.function.LongSupplier {
		private long now = 100_000L;

		@Override
		public long getAsLong() {
			return now;
		}

		private void advance(long millis) {
			now += millis;
		}
	}

	private static final class FakeLauncher implements CoordinatorProcessSupervisor.ProcessLauncher {
		private final List<CoordinatorProcessSupervisor.LaunchRequest> launches = new ArrayList<>();
		private final List<FakeChild> children = new ArrayList<>();

		@Override
		public CoordinatorProcessSupervisor.ChildProcess launch(CoordinatorProcessSupervisor.LaunchRequest request) {
			launches.add(request);
			FakeChild child = new FakeChild();
			children.add(child);
			return child;
		}
	}

	private static final class FakeChild implements CoordinatorProcessSupervisor.ChildProcess {
		private boolean alive = true;

		@Override
		public boolean isAlive() {
			return alive;
		}

		@Override
		public long pid() {
			return 90_901L;
		}

		@Override
		public void terminate() {
			alive = false;
		}
	}

	private static int verifyCorruptedActiveGenerationRollsBackToVerifiedRuntime() throws Exception {
		Path packageRoot = Files.createTempDirectory("arena-coordinator-corrupt-active");
		try {
			byte[] verifiedMain = "verified main".getBytes(StandardCharsets.UTF_8);
			byte[] config = "{}".getBytes(StandardCharsets.UTF_8);
			String verifiedManifest = manifest(
					entry("src/dynamic-main.mjs", verifiedMain),
					entry("config/dynamic-agents.json", config)
			);
			String verifiedGeneration = sha256(verifiedManifest.getBytes(StandardCharsets.UTF_8));
			BundledCoordinatorInstaller.install(
					packageRoot,
					resource(resources(verifiedManifest, verifiedMain, config))
			);
			assertTrue(BundledCoordinatorInstaller.promote(packageRoot, verifiedGeneration),
					"corruption fixture promotes its verified rollback target");

			byte[] candidateMain = "candidate main".getBytes(StandardCharsets.UTF_8);
			String candidateManifest = manifest(
					entry("src/dynamic-main.mjs", candidateMain),
					entry("config/dynamic-agents.json", config)
			);
			String candidateGeneration = sha256(candidateManifest.getBytes(StandardCharsets.UTF_8));
			BundledCoordinatorInstaller.install(
					packageRoot,
					resource(resources(candidateManifest, candidateMain, config))
			);
			assertEquals("verified main",
					Files.readString(packageRoot.resolve("coordinator.last-known-good/src/dynamic-main.mjs")),
					"candidate activation retains the exact verified runtime before corruption");

			Files.writeString(
					packageRoot.resolve("coordinator/src/dynamic-main.mjs"),
					"corrupted active main",
					StandardCharsets.UTF_8
			);
			boolean corruptionDetected = false;
			try {
				BundledCoordinatorInstaller.validate(packageRoot);
			} catch (IOException expected) {
				corruptionDetected = true;
			}
			assertTrue(corruptionDetected, "active runtime corruption is detected before launch");
			assertTrue(BundledCoordinatorInstaller.rollback(packageRoot, candidateGeneration),
					"corrupted active candidate rolls back to the verified runtime");
			assertEquals("verified main", Files.readString(packageRoot.resolve("coordinator/src/dynamic-main.mjs")),
					"rollback replaces corrupted candidate bytes with verified bytes");
			BundledCoordinatorInstaller.RuntimePackage recovered = BundledCoordinatorInstaller.validate(packageRoot);
			assertEquals(verifiedGeneration, recovered.generationId(),
					"recovered runtime publishes the exact verified generation");
			assertFalse(recovered.candidate(), "recovered runtime is immediately verified");
			assertFalse(Files.exists(packageRoot.resolve("coordinator.last-known-good")),
					"recovery consumes the retained copy instead of leaking generations");
			assertFalse(BundledCoordinatorInstaller.rollback(packageRoot, candidateGeneration),
					"repeating corruption rollback is idempotent");
			return 9;
		} finally {
			deleteTree(packageRoot);
		}
	}

	private static int verifyVerifiedGenerationCanRollbackCandidate() throws Exception {
		Path packageRoot = Files.createTempDirectory("arena-coordinator-generations");
		try {
			byte[] mainA = "main A".getBytes(StandardCharsets.UTF_8);
			byte[] configDefault = "{\"model\":\"default\"}".getBytes(StandardCharsets.UTF_8);
			String manifestA = manifest(entry("src/dynamic-main.mjs", mainA), entry("config/dynamic-agents.json", configDefault));
			String generationA = sha256(manifestA.getBytes(StandardCharsets.UTF_8));
			assertTrue(BundledCoordinatorInstaller.install(packageRoot, resource(resources(manifestA, mainA, configDefault))),
					"generation A activates as the first candidate");
			BundledCoordinatorInstaller.RuntimePackage candidateA = BundledCoordinatorInstaller.validate(packageRoot);
			assertEquals(generationA, candidateA.generationId(), "generation ID is the bundle manifest digest");
			assertTrue(candidateA.candidate(), "the first generation remains a candidate until stability promotion");
			assertFalse(candidateA.lastKnownGoodAvailable(), "a fresh candidate has no rollback target");
			assertTrue(BundledCoordinatorInstaller.promote(packageRoot, generationA),
					"stable generation A becomes verified");

			Path config = packageRoot.resolve("runtime/dynamic-agents.json");
			Path secret = packageRoot.resolve("runtime/bridge-secret.txt");
			Files.writeString(config, "{\"model\":\"custom\"}", StandardCharsets.UTF_8);
			String configHash = sha256(Files.readAllBytes(config));
			String secretHash = sha256(Files.readAllBytes(secret));
			byte[] mainB = "main B".getBytes(StandardCharsets.UTF_8);
			byte[] changedDefault = "{\"model\":\"new-default\"}".getBytes(StandardCharsets.UTF_8);
			String manifestB = manifest(entry("src/dynamic-main.mjs", mainB), entry("config/dynamic-agents.json", changedDefault));
			String generationB = sha256(manifestB.getBytes(StandardCharsets.UTF_8));
			assertTrue(BundledCoordinatorInstaller.install(packageRoot, resource(resources(manifestB, mainB, changedDefault))),
					"generation B activates after full staging validation");
			assertEquals("main B", Files.readString(packageRoot.resolve("coordinator/src/dynamic-main.mjs")),
					"generation B owns the active path");
			assertEquals("main A", Files.readString(packageRoot.resolve("coordinator.last-known-good/src/dynamic-main.mjs")),
					"verified generation A is retained as the only rollback target");
			BundledCoordinatorInstaller.RuntimePackage candidateB = BundledCoordinatorInstaller.validate(packageRoot);
			assertEquals(generationB, candidateB.generationId(), "prepared runtime identifies active generation B");
			assertTrue(candidateB.candidate(), "generation B is not verified before supervisor stability");
			assertTrue(candidateB.lastKnownGoodAvailable(), "generation B exposes its verified rollback target");
			assertEquals(configHash, sha256(Files.readAllBytes(config)), "candidate activation preserves canonical config bytes");
			assertEquals(secretHash, sha256(Files.readAllBytes(secret)), "candidate activation preserves bridge-secret bytes");

			assertTrue(BundledCoordinatorInstaller.rollback(packageRoot, generationB),
					"a failed generation B rolls back to verified generation A");
			assertEquals("main A", Files.readString(packageRoot.resolve("coordinator/src/dynamic-main.mjs")),
					"rollback restores generation A to the active path");
			assertFalse(Files.exists(packageRoot.resolve("coordinator.last-known-good")),
					"rollback consumes the retained copy instead of accumulating generations");
			BundledCoordinatorInstaller.RuntimePackage rolledBack = BundledCoordinatorInstaller.validate(packageRoot);
			assertEquals(generationA, rolledBack.generationId(), "rollback republishes generation A state");
			assertFalse(rolledBack.candidate(), "rolled-back verified generation A is not a candidate");
			assertFalse(BundledCoordinatorInstaller.rollback(packageRoot, generationB),
					"repeating a completed rollback is idempotent");
			assertEquals(configHash, sha256(Files.readAllBytes(config)), "rollback preserves canonical config bytes");
			assertEquals(secretHash, sha256(Files.readAllBytes(secret)), "rollback preserves bridge-secret bytes");

			assertFalse(BundledCoordinatorInstaller.install(packageRoot, resource(resources(manifestB, mainB, changedDefault))),
					"a rejected generation is quarantined after rollback");
			assertEquals("main A", Files.readString(packageRoot.resolve("coordinator/src/dynamic-main.mjs")),
					"dependency refresh does not immediately reinstall identical rejected bytes");
			assertFalse(BundledCoordinatorInstaller.install(packageRoot, resource(resources(manifestB, mainB, changedDefault))),
					"the rejected-generation quarantine survives a fresh installer invocation");
			assertEquals(configHash, sha256(Files.readAllBytes(config)), "quarantine preserves canonical config bytes");
			assertEquals(secretHash, sha256(Files.readAllBytes(secret)), "quarantine preserves bridge-secret bytes");
			Properties rolledBackState = loadProperties(packageRoot.resolve("runtime/coordinator-generation.properties"));
			assertEquals(generationB, rolledBackState.getProperty("rejectedGeneration"),
					"rollback persists only the rejected generation digest");

			byte[] mainC = "main C".getBytes(StandardCharsets.UTF_8);
			String manifestC = manifest(entry("src/dynamic-main.mjs", mainC), entry("config/dynamic-agents.json", changedDefault));
			String generationC = sha256(manifestC.getBytes(StandardCharsets.UTF_8));
			assertTrue(BundledCoordinatorInstaller.install(packageRoot, resource(resources(manifestC, mainC, changedDefault))),
					"a genuinely new bundled generation can replace the quarantined one");
			assertEquals("main C", Files.readString(packageRoot.resolve("coordinator/src/dynamic-main.mjs")),
					"the new generation owns the active path");
			Properties activatedState = loadProperties(packageRoot.resolve("runtime/coordinator-generation.properties"));
			assertEquals("", activatedState.getProperty("rejectedGeneration", ""),
					"successful activation of a new generation clears the quarantine");
			assertTrue(BundledCoordinatorInstaller.promote(packageRoot, generationC),
					"stable generation C becomes verified");
			assertFalse(BundledCoordinatorInstaller.validate(packageRoot).candidate(),
					"promoted generation C is verified");
			assertEquals("main A", Files.readString(packageRoot.resolve("coordinator.last-known-good/src/dynamic-main.mjs")),
					"promotion retains one prior verified generation for the next rollback");
			return 32;
		} finally {
			deleteTree(packageRoot);
		}
	}

	private static int verifyRejectedGenerationMarkerSafety() throws Exception {
		Path packageRoot = Files.createTempDirectory("arena-coordinator-rejected-marker");
		try {
			byte[] main = "valid generation".getBytes(StandardCharsets.UTF_8);
			byte[] config = "{}".getBytes(StandardCharsets.UTF_8);
			String manifest = manifest(entry("src/dynamic-main.mjs", main), entry("config/dynamic-agents.json", config));
			BundledCoordinatorInstaller.install(packageRoot, resource(resources(manifest, main, config)));
			Path stateFile = packageRoot.resolve("runtime/coordinator-generation.properties");
			Properties state = loadProperties(stateFile);
			state.setProperty("rejectedGeneration", "../outside");
			storeProperties(stateFile, state);
			assertThrowsStateFailure(packageRoot, "an unsafe rejected-generation marker is refused");
			assertEquals("valid generation", Files.readString(packageRoot.resolve("coordinator/src/dynamic-main.mjs")),
					"invalid marker parsing cannot mutate the active generation");

			state.remove("rejectedGeneration");
			storeProperties(stateFile, state);
			Files.writeString(stateFile, "x".repeat(8_192), StandardCharsets.UTF_8);
			assertThrowsStateFailure(packageRoot, "an oversized persisted generation marker journal is refused");
			assertEquals("valid generation", Files.readString(packageRoot.resolve("coordinator/src/dynamic-main.mjs")),
					"oversized state parsing cannot mutate the active generation");
			assertTrue(Files.size(stateFile) > 4_096L, "the oversized-state fixture exceeds the parser bound");
			return 5;
		} finally {
			deleteTree(packageRoot);
		}
	}

	private static int verifyInterruptedRollbackRetainsRejectedGeneration() throws Exception {
		Path packageRoot = Files.createTempDirectory("arena-coordinator-rejected-recovery");
		try {
			byte[] config = "{}".getBytes(StandardCharsets.UTF_8);
			byte[] mainA = "recovery A".getBytes(StandardCharsets.UTF_8);
			String manifestA = manifest(entry("src/dynamic-main.mjs", mainA), entry("config/dynamic-agents.json", config));
			String generationA = sha256(manifestA.getBytes(StandardCharsets.UTF_8));
			assertTrue(BundledCoordinatorInstaller.install(packageRoot, resource(resources(manifestA, mainA, config))),
					"recovery fixture installs generation A");
			assertTrue(BundledCoordinatorInstaller.promote(packageRoot, generationA),
					"recovery fixture verifies generation A");

			byte[] mainB = "recovery B".getBytes(StandardCharsets.UTF_8);
			String manifestB = manifest(entry("src/dynamic-main.mjs", mainB), entry("config/dynamic-agents.json", config));
			String generationB = sha256(manifestB.getBytes(StandardCharsets.UTF_8));
			assertTrue(BundledCoordinatorInstaller.install(packageRoot, resource(resources(manifestB, mainB, config))),
					"recovery fixture activates generation B");

			Path stateFile = packageRoot.resolve("runtime/coordinator-generation.properties");
			Properties interrupted = loadProperties(stateFile);
			interrupted.setProperty("phase", "rollback");
			interrupted.setProperty("verifiedGeneration", generationA);
			interrupted.setProperty("candidateGeneration", generationB);
			interrupted.setProperty("lastKnownGoodGeneration", generationA);
			interrupted.setProperty("stagingDirectory", "coordinator.staging-rollback-" + generationB);
			interrupted.setProperty("previousActiveGeneration", generationB);
			interrupted.setProperty("rejectedGeneration", generationB);
			storeProperties(stateFile, interrupted);

			BundledCoordinatorInstaller.RuntimePackage recovered = BundledCoordinatorInstaller.validate(packageRoot);
			assertEquals(generationA, recovered.generationId(), "restart completes the interrupted rollback to generation A");
			assertFalse(recovered.candidate(), "the recovered verified runtime is not a candidate");
			assertEquals(generationB, loadProperties(stateFile).getProperty("rejectedGeneration"),
					"rollback recovery preserves the rejected-generation quarantine");
			assertFalse(BundledCoordinatorInstaller.install(packageRoot, resource(resources(manifestB, mainB, config))),
					"dependency refresh after recovery still refuses identical rejected bytes");
			assertEquals("recovery A", Files.readString(packageRoot.resolve("coordinator/src/dynamic-main.mjs")),
					"recovery leaves the verified generation active");
			return 8;
		} finally {
			deleteTree(packageRoot);
		}
	}

	private static int verifySoleCandidateIsRetainedForRetry() throws Exception {
		Path packageRoot = Files.createTempDirectory("arena-coordinator-sole-candidate");
		try {
			byte[] main = "only runnable candidate".getBytes(StandardCharsets.UTF_8);
			byte[] config = "{}".getBytes(StandardCharsets.UTF_8);
			String manifest = manifest(entry("src/dynamic-main.mjs", main), entry("config/dynamic-agents.json", config));
			String generation = sha256(manifest.getBytes(StandardCharsets.UTF_8));
			BundledCoordinatorInstaller.install(packageRoot, resource(resources(manifest, main, config)));
			assertFalse(BundledCoordinatorInstaller.rollback(packageRoot, generation),
					"a candidate without a verified rollback target is retained");
			assertEquals("only runnable candidate", Files.readString(packageRoot.resolve("coordinator/src/dynamic-main.mjs")),
					"failed sole candidate remains runnable for retry");
			assertTrue(BundledCoordinatorInstaller.validate(packageRoot).candidate(),
					"sole failed generation remains a candidate until it becomes stable");
			return 3;
		} finally {
			deleteTree(packageRoot);
		}
	}

	private static int verifyTransientDirectoryLockIsRetried() throws Exception {
		Path root = Files.createTempDirectory("arena-coordinator-retry");
		try {
			Path source = root.resolve("source");
			Path target = root.resolve("target");
			Files.createDirectories(source);
			int[] attempts = {0};
			BundledCoordinatorInstaller.moveDirectoryWithRetry(source, target, (from, to) -> {
				attempts[0] += 1;
				if (attempts[0] < 3) {
					throw new java.nio.file.FileSystemException(from.toString(), to.toString(), "temporarily busy");
				}
				Files.move(from, to);
			});
			assertEquals(3, attempts[0], "runtime swap retries a transient Windows directory lock");
			assertTrue(Files.isDirectory(target), "runtime swap completes after the lock is released");
			return 2;
		} finally {
			deleteTree(root);
		}
	}

	private static int verifyConfiguredSecretPathUsesPreparedRuntime() {
		Path preparedSecret = Path.of("prepared-runtime", "runtime", "bridge-secret.txt")
				.toAbsolutePath().normalize();
		Path preparedVoiceSecret = Path.of("prepared-runtime", "runtime", "voice-secret.txt")
				.toAbsolutePath().normalize();
		String oldBridgeSecret = System.getProperty("arenaagents.bridgeSecretFile");
		String oldVoiceSecret = System.getProperty("arenaagents.voiceSecretFile");
		try {
			System.setProperty("arenaagents.bridgeSecretFile", "development-runtime/runtime/bridge-secret.txt");
			CoordinatorProcessSupervisor.configureSharedSecretPaths(preparedSecret, preparedVoiceSecret);
			assertEquals(preparedSecret.toString(), System.getProperty("arenaagents.bridgeSecretFile"),
					"prepared runtime overrides a stale development bridge secret path");
			assertEquals(preparedVoiceSecret.toString(), System.getProperty("arenaagents.voiceSecretFile"),
					"prepared runtime configures its distinct voice secret path");
			return 2;
		} finally {
			restoreProperty("arenaagents.bridgeSecretFile", oldBridgeSecret);
			restoreProperty("arenaagents.voiceSecretFile", oldVoiceSecret);
		}
	}

	private static int verifyStaleCoordinatorIsReplacedWithoutTouchingRuntimeState() throws Exception {
		Path packageRoot = Files.createTempDirectory("arena-coordinator-install");
		try {
			Path coordinator = packageRoot.resolve("coordinator");
			Files.createDirectories(coordinator.resolve("src"));
			Files.writeString(coordinator.resolve("src/dynamic-main.mjs"), "old main", StandardCharsets.UTF_8);
			Files.writeString(coordinator.resolve("stale-file.mjs"), "stale", StandardCharsets.UTF_8);
			byte[] legacyConfig = "{\"legacy\":\"preserve exactly\"}".getBytes(StandardCharsets.UTF_8);
			Files.createDirectories(coordinator.resolve("config"));
			Files.write(coordinator.resolve("config/dynamic-agents.json"), legacyConfig);
			Files.createDirectories(packageRoot.resolve("runtime"));
			Path secret = packageRoot.resolve("runtime/bridge-secret.txt");
			byte[] existingSecret = new byte[64];
			for (int index = 0; index < existingSecret.length; index++) existingSecret[index] = (byte) ('a' + index % 6);
			Files.write(secret, existingSecret);
			String secretFingerprint = sha256(existingSecret);

			String manifest = """
					c3030194b6df5f53b10753e8a19de3af866b5e95c6454d0888be297c91277667 src/dynamic-main.mjs
					fdd8894ff997b79b72253382c82f641840e76b7d5f14fd0ad775cd8cf5d7bae5 package.json
					""";
			Map<String, byte[]> resources = Map.of(
					"arena-agents/coordinator/coordinator-manifest.txt", manifest.getBytes(StandardCharsets.UTF_8),
					"arena-agents/coordinator/src/dynamic-main.mjs", "new main".getBytes(StandardCharsets.UTF_8),
					"arena-agents/coordinator/package.json", "{\"name\":\"arena\"}".getBytes(StandardCharsets.UTF_8)
			);

			boolean installed = BundledCoordinatorInstaller.install(packageRoot, resource(resources));
			assertTrue(installed, "stale coordinator triggers a bundled install");
			assertEquals("new main", Files.readString(coordinator.resolve("src/dynamic-main.mjs")),
					"bundled coordinator replaces stale source");
			assertTrue(Files.isRegularFile(coordinator.resolve("package.json")),
					"bundled coordinator installs every manifest entry");
			assertFalse(Files.exists(coordinator.resolve("stale-file.mjs")),
					"directory swap removes files absent from the new bundle");
			assertEquals(secretFingerprint, sha256(Files.readAllBytes(secret)),
					"coordinator refresh preserves runtime state outside its directory");
			assertEquals(sha256(legacyConfig), sha256(Files.readAllBytes(packageRoot.resolve("runtime/dynamic-agents.json"))),
					"legacy mutable config migrates once without changing its bytes");
			assertFalse(BundledCoordinatorInstaller.install(packageRoot, resource(resources)),
					"matching content manifest skips a redundant install");
			return 7;
		} finally {
			deleteTree(packageRoot);
		}
	}

	private static int verifyIncompleteBundleLeavesExistingCoordinatorIntact() throws Exception {
		Path packageRoot = Files.createTempDirectory("arena-coordinator-install-failure");
		try {
			Path existingMain = packageRoot.resolve("coordinator/src/dynamic-main.mjs");
			Files.createDirectories(existingMain.getParent());
			Files.writeString(existingMain, "working old main", StandardCharsets.UTF_8);
			String manifest = """
					c3030194b6df5f53b10753e8a19de3af866b5e95c6454d0888be297c91277667 src/dynamic-main.mjs
					fdd8894ff997b79b72253382c82f641840e76b7d5f14fd0ad775cd8cf5d7bae5 package.json
					""";
			Map<String, byte[]> incomplete = Map.of(
					"arena-agents/coordinator/coordinator-manifest.txt", manifest.getBytes(StandardCharsets.UTF_8),
					"arena-agents/coordinator/src/dynamic-main.mjs", "new main".getBytes(StandardCharsets.UTF_8)
			);
			try {
				BundledCoordinatorInstaller.install(packageRoot, resource(incomplete));
				throw new AssertionError("incomplete coordinator bundle must fail installation");
			} catch (IOException expected) {
				assertEquals("working old main", Files.readString(existingMain),
						"failed extraction preserves the complete existing coordinator");
			}
			return 1;
		} finally {
			deleteTree(packageRoot);
		}
	}

	private static int verifyFreshInstallCreatesSecretAndPreservesProviderConfig() throws Exception {
		Path packageRoot = Files.createTempDirectory("arena-coordinator-install-fresh");
		try {
			byte[] initialMain = "main v1".getBytes(StandardCharsets.UTF_8);
			byte[] initialConfig = "{\"codex\":{\"model\":\"user-model\"}}".getBytes(StandardCharsets.UTF_8);
			String initialManifest = manifest(
					entry("src/dynamic-main.mjs", initialMain),
					entry("config/dynamic-agents.json", initialConfig));
			Map<String, byte[]> initialResources = resources(initialManifest, initialMain, initialConfig);

			assertTrue(BundledCoordinatorInstaller.install(packageRoot, resource(initialResources)),
					"fresh JAR install extracts the bundled coordinator");
			Path secret = packageRoot.resolve("runtime/bridge-secret.txt");
			Path voiceSecret = packageRoot.resolve("runtime/voice-secret.txt");
			assertTrue(Files.isRegularFile(secret), "fresh JAR install creates the shared bridge secret");
			assertTrue(Files.isRegularFile(voiceSecret), "fresh JAR install creates the voice-only secret");
			assertOwnerOnlyIfSupported(packageRoot.resolve("runtime"), true, "runtime directory is owner-only");
			assertOwnerOnlyIfSupported(secret, false, "bridge secret is owner-only");
			assertOwnerOnlyIfSupported(voiceSecret, false, "voice secret is owner-only");
			BundledCoordinatorInstaller.RuntimePackage prepared = BundledCoordinatorInstaller.prepare(packageRoot);
			assertEquals(packageRoot.toAbsolutePath().normalize(), prepared.root(), "preparation returns normalized package root");
			assertEquals(packageRoot.resolve("coordinator").toAbsolutePath().normalize(), prepared.coordinatorRoot(),
					"preparation returns coordinator root");
			assertEquals(packageRoot.resolve("coordinator/src/dynamic-main.mjs").toAbsolutePath().normalize(), prepared.main(),
					"preparation returns coordinator entrypoint");
			assertEquals(packageRoot.resolve("runtime/dynamic-agents.json").toAbsolutePath().normalize(), prepared.config(),
					"preparation returns canonical external config");
			assertEquals(secret.toAbsolutePath().normalize(), prepared.secret(), "preparation returns the canonical secret path");
			assertEquals(voiceSecret.toAbsolutePath().normalize(), prepared.voiceSecret(),
					"preparation returns the canonical voice secret path");
			assertFalse(Files.readString(secret).equals(Files.readString(voiceSecret)),
					"bridge and voice channels receive distinct credentials");
			assertFalse(Files.exists(packageRoot.resolve("coordinator/runtime/bridge-secret.txt")),
					"preparation never copies the shared secret into the coordinator tree");
			String secretFingerprint = sha256(Files.readAllBytes(secret));
			String voiceSecretFingerprint = sha256(Files.readAllBytes(voiceSecret));
			assertTrue(Files.readString(secret, StandardCharsets.UTF_8).trim().length() >= 32,
					"fresh shared bridge secret is bounded and usable");
			assertFalse(Files.exists(packageRoot.resolve("coordinator/config/dynamic-agents.json")),
					"mutable config is not copied into a runtime generation");
			Path config = packageRoot.resolve("runtime/dynamic-agents.json");
			Files.writeString(config, "{\"codex\":{\"model\":\"my-custom-model\"}}", StandardCharsets.UTF_8);

			byte[] upgradedMain = "main v2".getBytes(StandardCharsets.UTF_8);
			byte[] upgradedConfig = "{\"codex\":{\"model\":\"new-default\"}}".getBytes(StandardCharsets.UTF_8);
			String upgradedManifest = manifest(
					entry("src/dynamic-main.mjs", upgradedMain),
					entry("config/dynamic-agents.json", upgradedConfig));
			Map<String, byte[]> upgradedResources = resources(upgradedManifest, upgradedMain, upgradedConfig);
			assertTrue(BundledCoordinatorInstaller.install(packageRoot, resource(upgradedResources)),
					"changed bundled code triggers a coordinator upgrade");
			assertEquals("{\"codex\":{\"model\":\"my-custom-model\"}}",
					Files.readString(config, StandardCharsets.UTF_8),
					"user provider config survives a bundled coordinator upgrade");
			assertEquals(secretFingerprint, sha256(Files.readAllBytes(secret)),
					"coordinator upgrade keeps the one shared bridge secret");
			assertEquals(voiceSecretFingerprint, sha256(Files.readAllBytes(voiceSecret)),
					"coordinator upgrade keeps the voice-only secret");
			assertFalse(BundledCoordinatorInstaller.install(packageRoot, resource(upgradedResources)),
					"matching coordinator upgrade is idempotent after preserving custom config");
			return 19;
		} finally {
			deleteTree(packageRoot);
		}
	}

	private static int verifyInterruptedSwapRecoversPreviousCoordinator() throws Exception {
		Path packageRoot = Files.createTempDirectory("arena-coordinator-install-recovery");
		try {
			byte[] oldMain = "working old main".getBytes(StandardCharsets.UTF_8);
			byte[] oldConfig = "{\"codex\":{\"model\":\"crash-safe-custom\"}}".getBytes(StandardCharsets.UTF_8);
			String oldManifest = manifest(entry("src/dynamic-main.mjs", oldMain), entry("config/dynamic-agents.json", oldConfig));
			String oldGeneration = sha256(oldManifest.getBytes(StandardCharsets.UTF_8));
			BundledCoordinatorInstaller.install(packageRoot, resource(resources(oldManifest, oldMain, oldConfig)));
			BundledCoordinatorInstaller.promote(packageRoot, oldGeneration);

			byte[] newMain = "new main".getBytes(StandardCharsets.UTF_8);
			byte[] newConfig = "new default".getBytes(StandardCharsets.UTF_8);
			String manifest = manifest(entry("src/dynamic-main.mjs", newMain), entry("config/dynamic-agents.json", newConfig));
			Map<String, byte[]> resources = resources(manifest, newMain, newConfig);
			int[] moves = {0};
			try {
				BundledCoordinatorInstaller.install(packageRoot, resource(resources), (source, target) -> {
					moves[0] += 1;
					if (target.getFileName().toString().equals("coordinator")) {
						throw new IOException("injected interruption before candidate activation");
					}
					Files.move(source, target);
				});
				throw new AssertionError("interrupted swap must fail");
			} catch (IOException expected) {
				assertFalse(Files.exists(packageRoot.resolve("coordinator")),
						"interruption after retaining the verified runtime leaves no ambiguous active directory");
				assertEquals("working old main", Files.readString(packageRoot.resolve("coordinator.last-known-good/src/dynamic-main.mjs")),
						"interrupted swap retains the exact verified generation");
			}

			assertFalse(BundledCoordinatorInstaller.install(packageRoot, resource(resources)),
					"a repeated install deterministically completes the journaled candidate activation");
			assertEquals("new main", Files.readString(packageRoot.resolve("coordinator/src/dynamic-main.mjs")),
					"recovered swap installs the new coordinator code");
			assertEquals("{\"codex\":{\"model\":\"crash-safe-custom\"}}",
					Files.readString(packageRoot.resolve("runtime/dynamic-agents.json")),
					"recovered swap retains the user provider config");
			assertEquals(0L, Files.list(packageRoot)
					.filter(path -> path.getFileName().toString().startsWith("coordinator.staging-"))
					.count(), "completed recovery cleans disposable staging directories");
			return 6;
		} finally {
			deleteTree(packageRoot);
		}
	}

	private static void assertThrowsStateFailure(Path packageRoot, String label) {
		try {
			BundledCoordinatorInstaller.validate(packageRoot);
			throw new AssertionError(label);
		} catch (IOException | IllegalArgumentException expected) {
			// Expected validation boundary.
		}
	}

	private static Properties loadProperties(Path path) throws IOException {
		Properties properties = new Properties();
		try (var reader = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
			properties.load(reader);
		}
		return properties;
	}

	private static void storeProperties(Path path, Properties properties) throws IOException {
		try (var writer = Files.newBufferedWriter(path, StandardCharsets.UTF_8)) {
			properties.store(writer, null);
		}
	}

	private static String manifest(EntryData... entries) {
		return java.util.Arrays.stream(entries)
				.sorted(java.util.Comparator.comparing(EntryData::path))
				.map(entry -> entry.hash() + " " + entry.path() + "\n")
				.collect(java.util.stream.Collectors.joining());
	}

	private static EntryData entry(String path, byte[] content) {
		return new EntryData(path, sha256(content));
	}

	private static EntryData entry(String path, String content) {
		return entry(path, content.getBytes(StandardCharsets.UTF_8));
	}

	private static Map<String, byte[]> resources(String manifest, byte[] main, byte[] config) {
		return Map.of(
				"arena-agents/coordinator/coordinator-manifest.txt", manifest.getBytes(StandardCharsets.UTF_8),
				"arena-agents/coordinator/src/dynamic-main.mjs", main,
				"arena-agents/coordinator/config/dynamic-agents.json", config);
	}

	private static String sha256(byte[] content) {
		try {
			return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content));
		} catch (java.security.NoSuchAlgorithmException exception) {
			throw new AssertionError(exception);
		}
	}

	private static BundledCoordinatorInstaller.ResourceSource resource(Map<String, byte[]> resources) {
		return path -> {
			byte[] value = resources.get(path);
			if (value == null) throw new IOException("missing test resource " + path);
			return new ByteArrayInputStream(value);
		};
	}

	private record EntryData(String path, String hash) {
	}

	private static void deleteTree(Path root) throws IOException {
		if (!Files.exists(root)) return;
		try (var paths = Files.walk(root)) {
			for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(path);
		}
	}

	private static void assertEquals(Object expected, Object actual, String label) {
		if (!java.util.Objects.equals(expected, actual)) {
			throw new AssertionError(label + ": expected=" + expected + ", actual=" + actual);
		}
	}

	private static void assertTrue(boolean condition, String label) {
		if (!condition) throw new AssertionError(label);
	}

	private static void assertFalse(boolean condition, String label) {
		assertTrue(!condition, label);
	}

	private static void assertOwnerOnlyIfSupported(Path target, boolean directory, String label) throws IOException {
		if (Files.getFileAttributeView(target, PosixFileAttributeView.class) == null) return;
		var permissions = Files.getPosixFilePermissions(target);
		Set<PosixFilePermission> expected = directory
				? Set.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE, PosixFilePermission.OWNER_EXECUTE)
				: Set.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE);
		assertEquals(expected, permissions, label);
	}

	private static void restoreProperty(String name, String value) {
		if (value == null) System.clearProperty(name);
		else System.setProperty(name, value);
	}
}
