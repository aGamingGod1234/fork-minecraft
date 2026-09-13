package dev.agaminggod.arenaagents.server;

import java.io.IOException;
import java.io.InputStream;
import java.io.Writer;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.AclEntry;
import java.nio.file.attribute.AclEntryFlag;
import java.nio.file.attribute.AclEntryPermission;
import java.nio.file.attribute.AclEntryType;
import java.nio.file.attribute.AclFileAttributeView;
import java.nio.file.attribute.FileAttribute;
import java.nio.file.attribute.PosixFileAttributeView;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.nio.file.attribute.UserPrincipal;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.Properties;
import java.util.Set;
import java.util.UUID;

final class BundledCoordinatorInstaller {
	private static final String RESOURCE_ROOT = "arena-agents/coordinator/";
	private static final String MANIFEST_NAME = "coordinator-manifest.txt";
	private static final String INSTALLED_MANIFEST_NAME = ".arena-agents-bundle-manifest";
	private static final String BUNDLED_CONFIG_PATH = "config/dynamic-agents.json";
	private static final String CONFIG_PATH = "runtime/dynamic-agents.json";
	private static final String SECRET_PATH = "runtime/bridge-secret.txt";
	private static final String VOICE_SECRET_PATH = "runtime/voice-secret.txt";
	private static final String STATE_PATH = "runtime/coordinator-generation.properties";
	private static final String ACTIVE_NAME = "coordinator";
	private static final String LAST_KNOWN_GOOD_NAME = "coordinator.last-known-good";
	private static final String STAGING_PREFIX = "coordinator.staging-";
	private static final int MAX_STAGING_CLEANUP = 8;
	private static final int MAX_STATE_BYTES = 4_096;
	private static final int SWAP_ATTEMPTS = 21;
	private static final long SWAP_RETRY_DELAY_MS = 50L;
	private static final Set<PosixFilePermission> OWNER_ONLY_DIRECTORY = Set.of(
			PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE, PosixFilePermission.OWNER_EXECUTE
	);
	private static final Set<PosixFilePermission> OWNER_ONLY_FILE = Set.of(
			PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE
	);
	private static final FileAttribute<Set<PosixFilePermission>> OWNER_ONLY_FILE_ATTRIBUTE =
			PosixFilePermissions.asFileAttribute(OWNER_ONLY_FILE);

	private BundledCoordinatorInstaller() {
	}

	static boolean installBundled(Path packageRoot) throws IOException {
		ClassLoader classLoader = BundledCoordinatorInstaller.class.getClassLoader();
		if (classLoader.getResource(RESOURCE_ROOT + MANIFEST_NAME) == null) return false;
		return install(packageRoot, path -> {
			InputStream stream = classLoader.getResourceAsStream(path);
			if (stream == null) throw new IOException("Bundled coordinator resource is missing: " + path);
			return stream;
		});
	}

	/** Installs the embedded coordinator when present, then returns one validated runtime context. */
	static RuntimePackage prepare(Path packageRoot) throws IOException {
		installBundled(packageRoot);
		return validate(packageRoot);
	}

	static RuntimePackage validate(Path packageRoot) throws IOException {
		Path root = normalizedRoot(packageRoot);
		ensureSafeMutationTargets(root);
		recoverInterruptedSwap(root, BundledCoordinatorInstaller::atomicMove);
		return validatedPackage(root);
	}

	static boolean install(Path packageRoot, ResourceSource resources) throws IOException {
		return install(packageRoot, resources, BundledCoordinatorInstaller::atomicMove);
	}

	static boolean install(Path packageRoot, ResourceSource resources, MoveOperation moveOperation) throws IOException {
		Path root = normalizedRoot(packageRoot);
		Objects.requireNonNull(resources, "coordinator resources must not be null");
		Objects.requireNonNull(moveOperation, "coordinator move operation must not be null");
		ensureSafeMutationTargets(root);
		Files.createDirectories(root);
		recoverInterruptedSwap(root, moveOperation);

		String manifest;
		try (InputStream input = resources.open(RESOURCE_ROOT + MANIFEST_NAME)) {
			if (input == null) throw new IOException("Bundled coordinator manifest is missing");
			manifest = new String(input.readAllBytes(), StandardCharsets.UTF_8);
		}
		List<Entry> entries = parseManifest(manifest);
		String generationId = sha256(manifest.getBytes(StandardCharsets.UTF_8));
		Path active = root.resolve(ACTIVE_NAME);
		GenerationState state = readState(root);
		if (state.phase().equals(Phase.READY.value)
				&& generationId.equals(state.rejectedGeneration())
				&& state.activeGeneration().equals(state.verifiedGeneration())
				&& !generationId.equals(state.activeGeneration())
				&& generationMatches(active, state.activeGeneration())) {
			ensureExternalConfig(root, active, entries, resources);
			ensureRuntimeSecrets(root);
			cleanupStaging(root, null);
			return false;
		}
		if (generationId.equals(generationOf(active)) && installedFilesMatch(active, entries)) {
			ensureExternalConfig(root, active, entries, resources);
			ensureRuntimeSecrets(root);
			GenerationState reconciled = reconcileReadyState(root, state, generationId);
			if (!reconciled.equals(state)) writeState(root, reconciled);
			cleanupStaging(root, null);
			return false;
		}

		Path staging = root.resolve(STAGING_PREFIX + generationId);
		if (Files.exists(staging, LinkOption.NOFOLLOW_LINKS)) deleteTree(root, staging);
		extract(staging, entries, resources);
		Files.writeString(staging.resolve(INSTALLED_MANIFEST_NAME), manifest, StandardCharsets.UTF_8);
		validateGeneration(staging, generationId);
		ensureExternalConfig(root, active, entries, resources);
		ensureRuntimeSecrets(root);

		String previousActive = generationOf(active);
		String verified = state.phase().equals(Phase.READY.value) ? state.verifiedGeneration() : "";
		boolean retainActive = !verified.isBlank() && verified.equals(previousActive);
		String retainedGeneration = retainActive ? verified : validGeneration(root.resolve(LAST_KNOWN_GOOD_NAME));
		if (!retainActive && retainedGeneration.isBlank()) retainedGeneration = "";
		GenerationState journal = new GenerationState(
				Phase.ACTIVATE.value,
				generationId,
				verified,
				generationId,
				retainedGeneration,
				staging.getFileName().toString(),
				previousActive,
				state.rejectedGeneration()
		);
		writeState(root, journal);
		recoverInterruptedSwap(root, moveOperation);
		cleanupStaging(root, null);
		return true;
	}

	static boolean promote(Path packageRoot, String generationId) throws IOException {
		Path root = normalizedRoot(packageRoot);
		ensureSafeMutationTargets(root);
		recoverInterruptedSwap(root, BundledCoordinatorInstaller::atomicMove);
		GenerationState state = readState(root);
		if (!state.phase().equals(Phase.READY.value)) throw new IOException("Coordinator generation journal is not ready");
		String activeGeneration = validateGeneration(root.resolve(ACTIVE_NAME), generationId);
		if (!activeGeneration.equals(state.activeGeneration())) {
			throw new IOException("Coordinator generation state does not match the active runtime");
		}
		if (state.candidateGeneration().isBlank() && generationId.equals(state.verifiedGeneration())) return false;
		if (!generationId.equals(state.candidateGeneration())) return false;
		writeState(root, new GenerationState(
				Phase.READY.value, generationId, generationId, "",
				state.lastKnownGoodGeneration(), "", "", state.rejectedGeneration()
		));
		return true;
	}

	static boolean rollback(Path packageRoot, String failedGenerationId) throws IOException {
		Path root = normalizedRoot(packageRoot);
		ensureSafeMutationTargets(root);
		recoverInterruptedSwap(root, BundledCoordinatorInstaller::atomicMove);
		GenerationState state = readState(root);
		if (!state.phase().equals(Phase.READY.value)
				|| !failedGenerationId.equals(state.activeGeneration())
				|| !failedGenerationId.equals(state.candidateGeneration())) return false;
		String retained = state.lastKnownGoodGeneration();
		if (retained.isBlank()) return false;
		validateGeneration(root.resolve(LAST_KNOWN_GOOD_NAME), retained);
		Path holder = root.resolve(STAGING_PREFIX + "rollback-" + failedGenerationId);
		if (Files.exists(holder, LinkOption.NOFOLLOW_LINKS)) deleteTree(root, holder);
		writeState(root, new GenerationState(
				Phase.ROLLBACK.value, failedGenerationId, retained, failedGenerationId, retained,
				holder.getFileName().toString(), failedGenerationId, failedGenerationId
		));
		recoverInterruptedSwap(root, BundledCoordinatorInstaller::atomicMove);
		cleanupStaging(root, null);
		return true;
	}

	/** Restores the verified retained generation only when the active runtime is no longer runnable. */
	static boolean restoreLastKnownGoodIfActiveInvalid(Path packageRoot) throws IOException {
		Path root = normalizedRoot(packageRoot);
		ensureSafeMutationTargets(root);
		recoverInterruptedSwap(root, BundledCoordinatorInstaller::atomicMove);
		GenerationState state = readState(root);
		if (!state.phase().equals(Phase.READY.value)) return false;
		if (generationMatches(root.resolve(ACTIVE_NAME), state.activeGeneration())) return false;
		String retained = state.lastKnownGoodGeneration();
		if (retained.isBlank()) return false;
		validateGeneration(root.resolve(LAST_KNOWN_GOOD_NAME), retained);

		String failedGeneration = state.activeGeneration();
		if (failedGeneration.isBlank()) return false;
		Path holder = root.resolve(STAGING_PREFIX + "rollback-" + failedGeneration);
		if (Files.exists(holder, LinkOption.NOFOLLOW_LINKS)) deleteTree(root, holder);
		writeState(root, new GenerationState(
				Phase.ROLLBACK.value, failedGeneration, retained, failedGeneration, retained,
				holder.getFileName().toString(), failedGeneration, state.rejectedGeneration()
		));
		recoverInterruptedSwap(root, BundledCoordinatorInstaller::atomicMove);
		cleanupStaging(root, null);
		return true;
	}

	private static GenerationState reconcileReadyState(Path root, GenerationState state, String generationId)
			throws IOException {
		if (state.phase().equals(Phase.READY.value) && generationId.equals(state.activeGeneration())) return state;
		String retained = validGeneration(root.resolve(LAST_KNOWN_GOOD_NAME));
		return new GenerationState(Phase.READY.value, generationId, "", generationId, retained, "", "", "");
	}

	private static void recoverInterruptedSwap(Path root, MoveOperation moveOperation) throws IOException {
		GenerationState state = readState(root);
		if (state.phase().equals(Phase.READY.value)) return;
		if (state.phase().equals(Phase.ACTIVATE.value)) {
			recoverActivation(root, state, moveOperation);
			return;
		}
		if (state.phase().equals(Phase.ROLLBACK.value)) {
			recoverRollback(root, state, moveOperation);
			return;
		}
		throw new IOException("Unknown coordinator generation journal phase: " + state.phase());
	}

	private static void recoverActivation(Path root, GenerationState state, MoveOperation moveOperation)
			throws IOException {
		Path active = root.resolve(ACTIVE_NAME);
		Path lkg = root.resolve(LAST_KNOWN_GOOD_NAME);
		Path staging = journalStaging(root, state);
		if (generationMatches(active, state.activeGeneration())) {
			finishActivation(root, state);
			if (Files.exists(staging, LinkOption.NOFOLLOW_LINKS)) deleteTree(root, staging);
			return;
		}
		if (!generationMatches(staging, state.activeGeneration())) {
			restoreRunnableAfterLostActivation(root, state, active, lkg, staging, moveOperation);
			return;
		}

		boolean retainPrevious = !state.verifiedGeneration().isBlank()
				&& state.verifiedGeneration().equals(state.previousActiveGeneration());
		if (Files.exists(active, LinkOption.NOFOLLOW_LINKS)) {
			if (retainPrevious) {
				if (Files.exists(lkg, LinkOption.NOFOLLOW_LINKS)) deleteTree(root, lkg);
				moveDirectoryWithRetry(active, lkg, moveOperation);
			} else {
				deleteTree(root, active);
			}
		}
		moveDirectoryWithRetry(staging, active, moveOperation);
		validateGeneration(active, state.activeGeneration());
		finishActivation(root, state);
	}

	private static void finishActivation(Path root, GenerationState state) throws IOException {
		String retained = validGeneration(root.resolve(LAST_KNOWN_GOOD_NAME));
		if (!state.lastKnownGoodGeneration().isBlank()
				&& !state.lastKnownGoodGeneration().equals(retained)) {
			throw new IOException("Last-known-good coordinator generation failed validation after activation");
		}
		writeState(root, new GenerationState(
				Phase.READY.value, state.activeGeneration(), state.verifiedGeneration(), state.candidateGeneration(),
				retained, "", "", state.activeGeneration().equals(state.rejectedGeneration())
						? state.rejectedGeneration() : ""
		));
	}

	private static void restoreRunnableAfterLostActivation(
			Path root,
			GenerationState state,
			Path active,
			Path lkg,
			Path staging,
			MoveOperation moveOperation
	) throws IOException {
		if (generationMatches(active, state.previousActiveGeneration())) {
			writeState(root, readyForRestored(
					state.previousActiveGeneration(), state.verifiedGeneration(), validGeneration(lkg),
					state.rejectedGeneration()
			));
			return;
		}
		if (generationMatches(lkg, state.verifiedGeneration())) {
			if (Files.exists(active, LinkOption.NOFOLLOW_LINKS)) deleteTree(root, active);
			moveDirectoryWithRetry(lkg, active, moveOperation);
			writeState(root, readyForRestored(
					state.verifiedGeneration(), state.verifiedGeneration(), "", state.rejectedGeneration()
			));
			return;
		}
		if (Files.exists(staging, LinkOption.NOFOLLOW_LINKS)) deleteTree(root, staging);
		throw new IOException("Interrupted coordinator activation has no validated runnable generation");
	}

	private static GenerationState readyForRestored(
			String active,
			String verified,
			String retained,
			String rejected
	) {
		String candidate = active.equals(verified) ? "" : active;
		return new GenerationState(Phase.READY.value, active, verified, candidate, retained, "", "", rejected);
	}

	private static void recoverRollback(Path root, GenerationState state, MoveOperation moveOperation)
			throws IOException {
		Path active = root.resolve(ACTIVE_NAME);
		Path lkg = root.resolve(LAST_KNOWN_GOOD_NAME);
		Path holder = journalStaging(root, state);
		if (generationMatches(active, state.verifiedGeneration())) {
			finishRollback(root, state, holder);
			return;
		}
		if (generationMatches(lkg, state.verifiedGeneration())) {
			if (Files.exists(active, LinkOption.NOFOLLOW_LINKS)) {
				if (!Files.exists(holder, LinkOption.NOFOLLOW_LINKS)) {
					moveDirectoryWithRetry(active, holder, moveOperation);
				} else {
					deleteTree(root, active);
				}
			}
			moveDirectoryWithRetry(lkg, active, moveOperation);
			validateGeneration(active, state.verifiedGeneration());
			finishRollback(root, state, holder);
			return;
		}
		if (!Files.exists(active, LinkOption.NOFOLLOW_LINKS) && generationMatches(holder, state.candidateGeneration())) {
			moveDirectoryWithRetry(holder, active, moveOperation);
			writeState(root, new GenerationState(
					Phase.READY.value, state.candidateGeneration(), "", state.candidateGeneration(), "", "", "",
					state.rejectedGeneration()
			));
			return;
		}
		throw new IOException("Interrupted coordinator rollback has no validated runnable generation");
	}

	private static void finishRollback(Path root, GenerationState state, Path holder) throws IOException {
		writeState(root, new GenerationState(
				Phase.READY.value, state.verifiedGeneration(), state.verifiedGeneration(), "", "", "", "",
				state.rejectedGeneration()
		));
		if (Files.exists(holder, LinkOption.NOFOLLOW_LINKS)) deleteTree(root, holder);
	}

	private static Path journalStaging(Path root, GenerationState state) throws IOException {
		if (state.stagingName().isBlank() || !state.stagingName().startsWith(STAGING_PREFIX)
				|| !Path.of(state.stagingName()).getFileName().toString().equals(state.stagingName())) {
			throw new IOException("Invalid coordinator generation staging journal");
		}
		Path staging = root.resolve(state.stagingName()).normalize();
		if (!staging.startsWith(root)) throw new IOException("Coordinator staging path escaped its runtime root");
		return staging;
	}

	private static List<Entry> parseManifest(String manifest) throws IOException {
		List<Entry> entries = new ArrayList<>();
		Set<Path> paths = new HashSet<>();
		for (String line : manifest.lines().toList()) {
			if (line.isBlank()) continue;
			int separator = line.indexOf(' ');
			if (separator != 64 || line.length() <= 65) throw new IOException("Invalid bundled coordinator manifest");
			String hash = line.substring(0, separator);
			if (!hash.matches("[0-9a-f]{64}")) throw new IOException("Invalid bundled coordinator hash");
			String rawPath = line.substring(separator + 1);
			if (rawPath.contains("\\")) throw new IOException("Invalid bundled coordinator path");
			Path path = Path.of(rawPath).normalize();
			if (path.isAbsolute() || path.getNameCount() == 0 || path.startsWith("..")
					|| !rawPath.equals(path.toString().replace('\\', '/')) || !paths.add(path)) {
				throw new IOException("Invalid bundled coordinator path");
			}
			entries.add(new Entry(path, hash));
		}
		if (entries.isEmpty()) throw new IOException("Bundled coordinator manifest is empty");
		return List.copyOf(entries);
	}

	private static boolean installedFilesMatch(Path coordinator, List<Entry> entries) throws IOException {
		for (Entry entry : entries) {
			if (entry.relativePath().equals(BUNDLED_CONFIG_PATH)) continue;
			Path file = coordinator.resolve(entry.path()).normalize();
			if (!file.startsWith(coordinator) || !Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)
					|| linked(file) || !sha256(file).equals(entry.sha256())) return false;
		}
		return true;
	}

	private static void ensureExternalConfig(
			Path root,
			Path active,
			List<Entry> entries,
			ResourceSource resources
	) throws IOException {
		Path config = root.resolve(CONFIG_PATH);
		assertContained(root, config);
		if (Files.exists(config, LinkOption.NOFOLLOW_LINKS)) {
			if (!Files.isRegularFile(config, LinkOption.NOFOLLOW_LINKS) || linked(config)) {
				throw new IOException("Coordinator config is not a regular external file");
			}
			return;
		}
		byte[] content = null;
		Path legacy = active.resolve(BUNDLED_CONFIG_PATH).normalize();
		if (legacy.startsWith(active) && Files.isRegularFile(legacy, LinkOption.NOFOLLOW_LINKS) && !linked(legacy)) {
			content = Files.readAllBytes(legacy);
		} else {
			Entry bundled = null;
			for (Entry entry : entries) {
				if (entry.relativePath().equals(BUNDLED_CONFIG_PATH)) bundled = entry;
			}
			if (bundled != null) {
				try (InputStream input = resources.open(RESOURCE_ROOT + BUNDLED_CONFIG_PATH)) {
					if (input == null) throw new IOException("Bundled coordinator config is missing");
					content = input.readAllBytes();
				}
				if (!sha256(content).equals(bundled.sha256())) throw new IOException("Bundled coordinator config hash mismatch");
			}
		}
		if (content == null) return;
		writeExternalFileOnce(root, config, content);
	}

	private static void writeExternalFileOnce(Path root, Path target, byte[] content) throws IOException {
		Files.createDirectories(target.getParent());
		Path staging = target.resolveSibling(target.getFileName() + ".staging-" + UUID.randomUUID());
		assertContained(root, staging);
		try {
			Files.write(staging, content, StandardOpenOption.CREATE_NEW);
			try {
				Files.move(staging, target, StandardCopyOption.ATOMIC_MOVE);
			} catch (AtomicMoveNotSupportedException unsupported) {
				Files.move(staging, target);
			} catch (java.nio.file.FileAlreadyExistsException concurrent) {
				// Another preparation published the canonical file first. Its bytes are authoritative.
			}
		} finally {
			Files.deleteIfExists(staging);
		}
	}

	private static void ensureRuntimeSecrets(Path root) throws IOException {
		ensureSecret(root.resolve(SECRET_PATH), "Bridge");
		ensureSecret(root.resolve(VOICE_SECRET_PATH), "Voice");
	}

	private static void ensureSecret(Path secret, String label) throws IOException {
		ensurePrivateDirectory(secret.getParent());
		if (Files.exists(secret, LinkOption.NOFOLLOW_LINKS)) {
			if (!Files.isRegularFile(secret, LinkOption.NOFOLLOW_LINKS) || linked(secret)) {
				throw new IOException(label + " secret is not a regular external file");
			}
			ensureOwnerOnly(secret, false);
			String value = Files.readString(secret, StandardCharsets.UTF_8).trim();
			if (value.length() >= 32) return;
			throw new IOException(label + " secret is invalid");
		}
		byte[] bytes = new byte[32];
		new SecureRandom().nextBytes(bytes);
		ByteBuffer value = StandardCharsets.UTF_8.encode(HexFormat.of().formatHex(bytes));
		try {
			writeNewPrivateSecret(secret, value);
		} catch (java.nio.file.FileAlreadyExistsException ignored) {
			if (!Files.isRegularFile(secret, LinkOption.NOFOLLOW_LINKS) || linked(secret)) {
				throw new IOException(label + " secret is not a regular external file", ignored);
			}
			ensureOwnerOnly(secret, false);
			String existing = Files.readString(secret, StandardCharsets.UTF_8).trim();
			if (existing.length() < 32) throw new IOException(label + " secret is invalid", ignored);
		}
	}

	private static void writeNewPrivateSecret(Path secret, ByteBuffer value) throws IOException {
		boolean posix = Files.getFileAttributeView(secret, PosixFileAttributeView.class, LinkOption.NOFOLLOW_LINKS) != null;
		try (var channel = posix
				? Files.newByteChannel(secret, Set.of(StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE), OWNER_ONLY_FILE_ATTRIBUTE)
				: Files.newByteChannel(secret, Set.of(StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE))) {
			while (value.hasRemaining()) channel.write(value);
		}
		ensureOwnerOnly(secret, false);
	}

	private static void ensurePrivateDirectory(Path directory) throws IOException {
		Files.createDirectories(directory);
		if (!Files.isDirectory(directory, LinkOption.NOFOLLOW_LINKS) || linked(directory)) {
			throw new IOException("Coordinator runtime directory is unsafe");
		}
		ensureOwnerOnly(directory, true);
	}

	/** Uses POSIX modes where available and an explicit owner-only DACL on Windows filesystems. */
	private static void ensureOwnerOnly(Path target, boolean directory) throws IOException {
		Set<PosixFilePermission> expected = directory ? OWNER_ONLY_DIRECTORY : OWNER_ONLY_FILE;
		PosixFileAttributeView posix = Files.getFileAttributeView(target, PosixFileAttributeView.class, LinkOption.NOFOLLOW_LINKS);
		if (posix != null) {
			posix.setPermissions(expected);
			if (!posix.readAttributes().permissions().equals(expected)) {
				throw new IOException("Could not enforce private coordinator runtime permissions");
			}
			return;
		}
		AclFileAttributeView acl = Files.getFileAttributeView(target, AclFileAttributeView.class, LinkOption.NOFOLLOW_LINKS);
		if (acl == null) throw new IOException("Filesystem cannot enforce private coordinator runtime permissions");
		try {
			String currentUserName = System.getProperty("user.name");
			if (currentUserName == null || currentUserName.isBlank()) {
				throw new IOException("Could not resolve the current user for private coordinator runtime permissions");
			}
			UserPrincipal currentUser = target.getFileSystem().getUserPrincipalLookupService()
					.lookupPrincipalByName(currentUserName);
			UserPrincipal owner = privateAclPrincipal(currentUser, acl.getOwner());
			Set<AclEntryFlag> flags = directory
					? EnumSet.of(AclEntryFlag.DIRECTORY_INHERIT, AclEntryFlag.FILE_INHERIT)
					: Set.of();
			AclEntry ownerOnly = AclEntry.newBuilder()
					.setType(AclEntryType.ALLOW)
					.setPrincipal(owner)
					.setPermissions(EnumSet.allOf(AclEntryPermission.class))
					.setFlags(flags)
					.build();
			if (!privateWindowsAclMatches(acl, owner, ownerOnly)) {
				acl.setOwner(owner);
				acl.setAcl(List.of(ownerOnly));
			}
			List<AclEntry> entries = acl.getAcl();
			if (!acl.getOwner().equals(owner)
					|| entries.size() != 1 || entries.getFirst().type() != AclEntryType.ALLOW
					|| !entries.getFirst().principal().equals(owner)
					|| !entries.getFirst().flags().equals(flags)
					|| !entries.getFirst().permissions().containsAll(EnumSet.allOf(AclEntryPermission.class))) {
				throw new IOException("Could not enforce private coordinator runtime permissions");
			}
		} catch (UnsupportedOperationException exception) {
			throw new IOException("Filesystem cannot enforce private coordinator runtime permissions", exception);
		}
	}

	private static boolean privateWindowsAclMatches(
			AclFileAttributeView acl,
			UserPrincipal owner,
			AclEntry expected
	) throws IOException {
		List<AclEntry> entries = acl.getAcl();
		return acl.getOwner().equals(owner) && entries.size() == 1 && entries.getFirst().equals(expected);
	}

	static UserPrincipal privateAclPrincipal(UserPrincipal currentUser, UserPrincipal filesystemOwner) {
		Objects.requireNonNull(filesystemOwner, "filesystemOwner");
		return Objects.requireNonNull(currentUser, "currentUser");
	}

	private static RuntimePackage validatedPackage(Path root) throws IOException {
		Path active = root.resolve(ACTIVE_NAME);
		String generation = validateGeneration(active, null);
		GenerationState state = readState(root);
		if (!state.phase().equals(Phase.READY.value) || !generation.equals(state.activeGeneration())) {
			throw new IOException("Coordinator generation state does not match the active runtime");
		}
		Path main = active.resolve("src/dynamic-main.mjs").normalize();
		Path config = root.resolve(CONFIG_PATH).normalize();
		Path secret = root.resolve(SECRET_PATH).normalize();
		Path voiceSecret = root.resolve(VOICE_SECRET_PATH).normalize();
		if (!Files.isRegularFile(main, LinkOption.NOFOLLOW_LINKS)
				|| !Files.isRegularFile(config, LinkOption.NOFOLLOW_LINKS)) {
			throw new IOException("Coordinator package is incomplete; install the bundled coordinator runtime");
		}
		if (!Files.isRegularFile(secret, LinkOption.NOFOLLOW_LINKS)) throw new IOException("Bridge secret file is missing");
		if (!Files.isRegularFile(voiceSecret, LinkOption.NOFOLLOW_LINKS)) throw new IOException("Voice secret file is missing");
		ensurePrivateDirectory(secret.getParent());
		if (linked(secret)) throw new IOException("Bridge secret is not a regular external file");
		if (linked(voiceSecret)) throw new IOException("Voice secret is not a regular external file");
		ensureOwnerOnly(secret, false);
		ensureOwnerOnly(voiceSecret, false);
		String value = Files.readString(secret, StandardCharsets.UTF_8).trim();
		String voiceValue = Files.readString(voiceSecret, StandardCharsets.UTF_8).trim();
		if (value.length() < 32) throw new IOException("Bridge secret is invalid");
		if (voiceValue.length() < 32) throw new IOException("Voice secret is invalid");
		if (MessageDigest.isEqual(value.getBytes(StandardCharsets.UTF_8), voiceValue.getBytes(StandardCharsets.UTF_8))) {
			throw new IOException("Bridge and voice secrets must be distinct");
		}
		boolean lkgAvailable = !state.lastKnownGoodGeneration().isBlank()
				&& generationMatches(root.resolve(LAST_KNOWN_GOOD_NAME), state.lastKnownGoodGeneration());
		return new RuntimePackage(
				root, active, main, config, secret, voiceSecret, generation,
				generation.equals(state.candidateGeneration()), lkgAvailable
		);
	}

	private static String validateGeneration(Path coordinator, String expectedGeneration) throws IOException {
		if (!Files.isDirectory(coordinator, LinkOption.NOFOLLOW_LINKS) || linked(coordinator)) {
			throw new IOException("Coordinator generation directory is missing or linked");
		}
		assertNoLinkedTree(coordinator);
		Path installedManifest = coordinator.resolve(INSTALLED_MANIFEST_NAME);
		if (!Files.isRegularFile(installedManifest, LinkOption.NOFOLLOW_LINKS)) {
			throw new IOException("Coordinator generation manifest is missing");
		}
		String manifest = Files.readString(installedManifest, StandardCharsets.UTF_8);
		String actualGeneration = sha256(manifest.getBytes(StandardCharsets.UTF_8));
		if (expectedGeneration != null && !expectedGeneration.equals(actualGeneration)) {
			throw new IOException("Coordinator generation ID does not match its manifest");
		}
		if (!installedFilesMatch(coordinator, parseManifest(manifest))) {
			throw new IOException("Coordinator generation failed manifest hash validation");
		}
		return actualGeneration;
	}

	private static String validGeneration(Path coordinator) throws IOException {
		if (!Files.exists(coordinator, LinkOption.NOFOLLOW_LINKS)) return "";
		return validateGeneration(coordinator, null);
	}

	private static boolean generationMatches(Path coordinator, String generation) throws IOException {
		if (generation == null || generation.isBlank() || !Files.exists(coordinator, LinkOption.NOFOLLOW_LINKS)) return false;
		try {
			return generation.equals(validateGeneration(coordinator, generation));
		} catch (IOException invalid) {
			return false;
		}
	}

	private static String generationOf(Path coordinator) throws IOException {
		if (!Files.exists(coordinator, LinkOption.NOFOLLOW_LINKS)) return "";
		try {
			return validateGeneration(coordinator, null);
		} catch (IOException invalid) {
			return "";
		}
	}

	private static void extract(Path staging, List<Entry> entries, ResourceSource resources) throws IOException {
		Files.createDirectories(staging);
		for (Entry entry : entries) {
			if (entry.relativePath().equals(BUNDLED_CONFIG_PATH)) continue;
			Path destination = staging.resolve(entry.path()).normalize();
			if (!destination.startsWith(staging)) throw new IOException("Bundled coordinator path escaped staging");
			Files.createDirectories(destination.getParent());
			try (InputStream input = resources.open(RESOURCE_ROOT + entry.relativePath())) {
				if (input == null) throw new IOException("Bundled coordinator resource is missing: " + entry.path());
				Files.copy(input, destination, StandardCopyOption.REPLACE_EXISTING);
			}
			if (!sha256(destination).equals(entry.sha256())) {
				throw new IOException("Bundled coordinator resource hash mismatch: " + entry.path());
			}
		}
	}

	private static GenerationState readState(Path root) throws IOException {
		Path stateFile = root.resolve(STATE_PATH);
		if (!Files.exists(stateFile, LinkOption.NOFOLLOW_LINKS)) return GenerationState.empty();
		if (!Files.isRegularFile(stateFile, LinkOption.NOFOLLOW_LINKS) || linked(stateFile)) {
			throw new IOException("Coordinator generation state is not a regular external file");
		}
		ensureOwnerOnly(stateFile, false);
		byte[] encoded;
		try (InputStream input = Files.newInputStream(stateFile)) {
			encoded = input.readNBytes(MAX_STATE_BYTES + 1);
		}
		if (encoded.length > MAX_STATE_BYTES) {
			throw new IOException("Coordinator generation state exceeds its size limit");
		}
		Properties values = new Properties();
		values.load(new java.io.StringReader(new String(encoded, StandardCharsets.UTF_8)));
		GenerationState state;
		try {
			state = new GenerationState(
					values.getProperty("phase", Phase.READY.value),
					values.getProperty("activeGeneration", ""),
					values.getProperty("verifiedGeneration", ""),
					values.getProperty("candidateGeneration", ""),
					values.getProperty("lastKnownGoodGeneration", ""),
					values.getProperty("stagingDirectory", ""),
					values.getProperty("previousActiveGeneration", ""),
					values.getProperty("rejectedGeneration", "")
			);
		} catch (IllegalArgumentException invalid) {
			throw new IOException("Coordinator generation state contains an invalid generation ID", invalid);
		}
		state.validate();
		return state;
	}

	private static void writeState(Path root, GenerationState state) throws IOException {
		state.validate();
		Path target = root.resolve(STATE_PATH);
		ensurePrivateDirectory(target.getParent());
		Path staging = target.resolveSibling(target.getFileName() + ".staging-" + UUID.randomUUID());
		Properties values = new Properties();
		values.setProperty("phase", state.phase());
		values.setProperty("activeGeneration", state.activeGeneration());
		values.setProperty("verifiedGeneration", state.verifiedGeneration());
		values.setProperty("candidateGeneration", state.candidateGeneration());
		values.setProperty("lastKnownGoodGeneration", state.lastKnownGoodGeneration());
		values.setProperty("stagingDirectory", state.stagingName());
		values.setProperty("previousActiveGeneration", state.previousActiveGeneration());
		values.setProperty("rejectedGeneration", state.rejectedGeneration());
		try {
			try (Writer writer = Files.newBufferedWriter(staging, StandardCharsets.UTF_8, StandardOpenOption.CREATE_NEW)) {
				values.store(writer, "Arena Agents coordinator generation state");
			}
			ensureOwnerOnly(staging, false);
			try {
				Files.move(staging, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
			} catch (AtomicMoveNotSupportedException unsupported) {
				Files.move(staging, target, StandardCopyOption.REPLACE_EXISTING);
			}
			ensureOwnerOnly(target, false);
		} finally {
			Files.deleteIfExists(staging);
		}
	}

	private static void cleanupStaging(Path root, Path retained) throws IOException {
		if (!Files.isDirectory(root, LinkOption.NOFOLLOW_LINKS)) return;
		int cleaned = 0;
		try (var paths = Files.list(root)) {
			for (Path path : paths
					.filter(candidate -> candidate.getFileName().toString().startsWith(STAGING_PREFIX))
					.sorted(Comparator.comparing(path -> path.getFileName().toString()))
					.toList()) {
				if (retained != null && path.equals(retained)) continue;
				if (cleaned >= MAX_STAGING_CLEANUP) break;
				deleteTree(root, path);
				cleaned++;
			}
		}
	}

	private static void ensureSafeMutationTargets(Path root) throws IOException {
		if (Files.exists(root, LinkOption.NOFOLLOW_LINKS) && linked(root)) {
			throw new IOException("Coordinator package root is linked or a reparse point");
		}
		for (String relative : List.of(
				ACTIVE_NAME, LAST_KNOWN_GOOD_NAME, "runtime", STATE_PATH, CONFIG_PATH, SECRET_PATH, VOICE_SECRET_PATH
		)) {
			Path target = root.resolve(relative).normalize();
			assertContained(root, target);
			assertNoLinkedAncestors(root, target);
		}
		if (Files.isDirectory(root, LinkOption.NOFOLLOW_LINKS)) {
			try (var paths = Files.list(root)) {
				for (Path staging : paths.filter(path -> path.getFileName().toString().startsWith(STAGING_PREFIX)).toList()) {
					assertContained(root, staging);
					assertNoLinkedTree(staging);
				}
			}
		}
	}

	private static void assertContained(Path root, Path target) throws IOException {
		if (!target.toAbsolutePath().normalize().startsWith(root)) {
			throw new IOException("Coordinator mutation target escaped its package root");
		}
	}

	private static void assertNoLinkedAncestors(Path root, Path target) throws IOException {
		Path current = root;
		if (Files.exists(current, LinkOption.NOFOLLOW_LINKS) && linked(current)) {
			throw new IOException("Coordinator mutation target contains a link or reparse point: " + current);
		}
		Path relative = root.relativize(target);
		for (Path part : relative) {
			current = current.resolve(part);
			if (Files.exists(current, LinkOption.NOFOLLOW_LINKS) && linked(current)) {
				throw new IOException("Coordinator mutation target contains a link or reparse point: " + current);
			}
		}
	}

	private static void assertNoLinkedTree(Path root) throws IOException {
		if (!Files.exists(root, LinkOption.NOFOLLOW_LINKS)) return;
		try (var paths = Files.walk(root)) {
			for (Path path : paths.toList()) {
				if (linked(path)) throw new IOException("Coordinator mutation tree contains a link or reparse point: " + path);
			}
		}
	}

	private static boolean linked(Path path) throws IOException {
		if (Files.isSymbolicLink(path)) return true;
		try {
			Object value = Files.getAttribute(path, "dos:reparsePoint", LinkOption.NOFOLLOW_LINKS);
			return Boolean.TRUE.equals(value);
		} catch (UnsupportedOperationException | IllegalArgumentException ignored) {
			return false;
		}
	}

	private static void deleteTree(Path root, Path target) throws IOException {
		assertContained(root, target);
		if (!Files.exists(target, LinkOption.NOFOLLOW_LINKS)) return;
		assertNoLinkedTree(target);
		try (var paths = Files.walk(target)) {
			for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(path);
		}
	}

	private static Path normalizedRoot(Path packageRoot) {
		return Objects.requireNonNull(packageRoot, "package root must not be null").toAbsolutePath().normalize();
	}

	private static void atomicMove(Path source, Path target) throws IOException {
		try {
			Files.move(source, target, StandardCopyOption.ATOMIC_MOVE);
		} catch (AtomicMoveNotSupportedException unsupported) {
			Files.move(source, target);
		}
	}

	private static void moveDirectoryWithRetry(Path source, Path target) throws IOException {
		moveDirectoryWithRetry(source, target, BundledCoordinatorInstaller::atomicMove);
	}

	static void moveDirectoryWithRetry(Path source, Path target, MoveOperation operation) throws IOException {
		IOException lastFailure = null;
		for (int attempt = 1; attempt <= SWAP_ATTEMPTS; attempt += 1) {
			try {
				operation.move(source, target);
				return;
			} catch (IOException busy) {
				lastFailure = busy;
				if (attempt == SWAP_ATTEMPTS) break;
				try {
					Thread.sleep(SWAP_RETRY_DELAY_MS);
				} catch (InterruptedException interrupted) {
					Thread.currentThread().interrupt();
					throw new IOException("Interrupted while waiting to replace the coordinator runtime", interrupted);
				}
			}
		}
		throw lastFailure;
	}

	private static String sha256(Path path) throws IOException {
		try (InputStream input = Files.newInputStream(path)) {
			MessageDigest digest = sha256Digest();
			byte[] buffer = new byte[16_384];
			for (int read; (read = input.read(buffer)) >= 0; ) {
				if (read > 0) digest.update(buffer, 0, read);
			}
			return HexFormat.of().formatHex(digest.digest());
		}
	}

	private static String sha256(byte[] content) {
		return HexFormat.of().formatHex(sha256Digest().digest(content));
	}

	private static MessageDigest sha256Digest() {
		try {
			return MessageDigest.getInstance("SHA-256");
		} catch (NoSuchAlgorithmException exception) {
			throw new IllegalStateException("SHA-256 is unavailable", exception);
		}
	}

	@FunctionalInterface
	interface MoveOperation {
		void move(Path source, Path target) throws IOException;
	}

	@FunctionalInterface
	interface ResourceSource {
		InputStream open(String path) throws IOException;
	}

	private record Entry(Path path, String sha256) {
		String relativePath() {
			return path.toString().replace('\\', '/');
		}
	}

	private enum Phase {
		READY("ready"),
		ACTIVATE("activate"),
		ROLLBACK("rollback");

		private final String value;

		Phase(String value) {
			this.value = value;
		}
	}

	private record GenerationState(
			String phase,
			String activeGeneration,
			String verifiedGeneration,
			String candidateGeneration,
			String lastKnownGoodGeneration,
			String stagingName,
			String previousActiveGeneration,
			String rejectedGeneration
	) {
		GenerationState {
			phase = Objects.requireNonNull(phase, "generation phase must not be null");
			activeGeneration = boundedGeneration(activeGeneration);
			verifiedGeneration = boundedGeneration(verifiedGeneration);
			candidateGeneration = boundedGeneration(candidateGeneration);
			lastKnownGoodGeneration = boundedGeneration(lastKnownGoodGeneration);
			stagingName = Objects.requireNonNull(stagingName, "staging name must not be null");
			previousActiveGeneration = boundedGeneration(previousActiveGeneration);
			rejectedGeneration = boundedGeneration(rejectedGeneration);
		}

		static GenerationState empty() {
			return new GenerationState(Phase.READY.value, "", "", "", "", "", "", "");
		}

		void validate() throws IOException {
			if (!Set.of(Phase.READY.value, Phase.ACTIVATE.value, Phase.ROLLBACK.value).contains(phase)) {
				throw new IOException("Invalid coordinator generation phase");
			}
			if (phase.equals(Phase.READY.value) && !stagingName.isBlank()) {
				throw new IOException("Ready coordinator state cannot retain a staging directory");
			}
			if (!phase.equals(Phase.READY.value) && stagingName.isBlank()) {
				throw new IOException("Coordinator swap journal requires a staging directory");
			}
		}

		private static String boundedGeneration(String value) {
			String normalized = Objects.requireNonNull(value, "generation ID must not be null");
			if (!normalized.isBlank() && !normalized.matches("[0-9a-f]{64}")) {
				throw new IllegalArgumentException("invalid coordinator generation ID");
			}
			return normalized;
		}
	}

	record RuntimePackage(
			Path root,
			Path coordinatorRoot,
			Path main,
			Path config,
			Path secret,
			Path voiceSecret,
			String generationId,
			boolean candidate,
			boolean lastKnownGoodAvailable
	) {
		RuntimePackage {
			root = root.toAbsolutePath().normalize();
			coordinatorRoot = coordinatorRoot.toAbsolutePath().normalize();
			main = main.toAbsolutePath().normalize();
			config = config.toAbsolutePath().normalize();
			secret = secret.toAbsolutePath().normalize();
			voiceSecret = voiceSecret.toAbsolutePath().normalize();
			generationId = Objects.requireNonNull(generationId, "generation ID must not be null");
		}
	}
}
