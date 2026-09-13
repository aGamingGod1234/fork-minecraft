package dev.agaminggod.arenaagents.server;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.agaminggod.arenaagents.server.bridge.BridgeEnvelope;
import dev.agaminggod.arenaagents.server.bridge.BridgeEnvelopeCodec;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/** Starts one staged coordinator with an isolated provider PATH and validates its first catalog. */
public final class CoordinatorStartupSmokeVerification {
	private static final long STARTUP_TIMEOUT_MS = 15_000L;

	private CoordinatorStartupSmokeVerification() {
	}

	public static void main(String[] arguments) throws Exception {
		if (arguments.length != 0) throw new IllegalArgumentException("Coordinator startup smoke takes no arguments");
		verify();
	}

	public static int verify() throws Exception {
		Path sourceCoordinator = Path.of("coordinator").toAbsolutePath().normalize();
		if (!Files.isRegularFile(sourceCoordinator.resolve("src/dynamic-main.mjs"))) {
			throw new AssertionError("startup smoke requires the coordinator source package");
		}
		Path packageRoot = Files.createTempDirectory("arena-startup-smoke");
		String oldPackageRoot = System.getProperty("arenaagents.packageRoot");
		String oldNodePath = System.getProperty(NodeRuntimeLocator.PROPERTY);
		String oldBridgeSecret = System.getProperty("arenaagents.bridgeSecretFile");
		String oldVoiceSecret = System.getProperty("arenaagents.voiceSecretFile");
		String oldVoiceUrl = System.getProperty("arenaagents.voiceUrl");
		String oldVoiceRequestTimeout = System.getProperty("arenaagents.voiceRequestTimeoutMs");
		CoordinatorProcessSupervisor supervisor = null;
		try {
			int trustBoundaryAssertions = verifyPathRejectedBeforeSecretRead(
					packageRoot.resolve("path-rejection"), oldPackageRoot, oldNodePath
			);
			stageCoordinator(sourceCoordinator, packageRoot);
			System.setProperty("arenaagents.packageRoot", packageRoot.toString());
			assertEquals(2, CoordinatorProcessSupervisor.ownershipRoots(packageRoot.resolve("game")).size(),
					"startup checks default and configured ownership roots");
			assertEquals(packageRoot.toAbsolutePath().normalize(),
					CoordinatorProcessSupervisor.ownershipRoots(packageRoot.resolve("game")).getFirst(),
					"custom package ownership is reaped before launch");
			int credentialAssertions = verifyOptionalVoiceCredential(packageRoot.resolve("credential-test"));
			Path node = stageBundledNode(packageRoot);
			FakeCodexEnvironment fakeCodex = stageFakeCodex(packageRoot, node);
			Path secret = packageRoot.resolve("runtime/bridge-secret.txt");
			Files.createDirectories(secret.getParent());
			Files.writeString(secret, "s".repeat(32), StandardCharsets.UTF_8);
			Files.writeString(
					packageRoot.resolve("runtime/fish-api-key.txt"),
					"test-fish-api-key",
					StandardCharsets.UTF_8
			);
			System.clearProperty(NodeRuntimeLocator.PROPERTY);
			System.clearProperty("arenaagents.bridgeSecretFile");
			System.clearProperty("arenaagents.voiceSecretFile");
			System.clearProperty("arenaagents.voiceUrl");
			System.clearProperty("arenaagents.voiceRequestTimeoutMs");

			try (ServerSocket bridge = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
				int voicePort = unusedLoopbackPort();
				writeSmokeConfig(packageRoot, bridge.getLocalPort(), voicePort);
				Map<String, String> isolatedEnvironment = new HashMap<>();
				isolatedEnvironment.put("PATH", fakeCodex.path());
				isolatedEnvironment.put("APPDATA", fakeCodex.appData().toString());
				isolatedEnvironment.put("FISH_AUDIO_API_KEY", "");
				isolatedEnvironment.put("FISH_API_KEY", "");
				supervisor = new CoordinatorProcessSupervisor(packageRoot.resolve("game"), isolatedEnvironment);
				long configurationDeadline = System.currentTimeMillis() + STARTUP_TIMEOUT_MS;
				while (!supervisor.configured() && System.currentTimeMillis() < configurationDeadline) {
					supervisor.tick(false);
					Thread.sleep(10L);
				}
				assertTrue(supervisor.configured(), "staged package is configured");
				assertEquals(bridge.getLocalPort(), supervisor.bridgePort(),
					"production supervisor publishes the nondefault coordinator bridge port");
				assertEquals("http://127.0.0.1:" + voicePort + "/v1/tts", System.getProperty("arenaagents.voiceUrl"),
					"coordinator shares the existing optional voice endpoint before startup");
				assertEquals("91234", System.getProperty("arenaagents.voiceRequestTimeoutMs"),
					"coordinator local inference deadline is shared with the addon before voice startup");
				assertEquals(node.toAbsolutePath().normalize(), NodeRuntimeLocator.locate(packageRoot).executable(),
						"bundled runtime is selected before the empty PATH");

				long deadline = System.currentTimeMillis() + STARTUP_TIMEOUT_MS;
				while (System.currentTimeMillis() < deadline && supervisor.failureCode() == null) {
					supervisor.tick(false);
					if (bridge.getSoTimeout() == 0) bridge.setSoTimeout(250);
					try (Socket socket = bridge.accept()) {
						socket.setSoTimeout(15_000);
						if (completeHandshakeAndCatalog(socket)) {
							assertTrue(awaitLoopbackListener(voicePort, 5_000L),
								"runtime Fish credential starts the loopback voice worker");
							return 12 + credentialAssertions + trustBoundaryAssertions;
						}
					} catch (java.net.SocketTimeoutException ignored) {
						// The supervisor's startup grace is intentionally polled without shell state.
					}
					Thread.sleep(100L);
				}
				Path errorLog = packageRoot.resolve("game/logs/arena-agents-coordinator-error.log");
				String errorDetail = Files.isRegularFile(errorLog)
						? Files.readString(errorLog, StandardCharsets.UTF_8)
						: "<missing coordinator error log>";
				throw new AssertionError("staged coordinator did not reach the catalog-ready boundary: "
						+ supervisor.failureCode() + "\n" + errorDetail);
			}
		} finally {
			AssertionError ownershipFailure = null;
			try {
				if (supervisor != null) {
					supervisor.close();
					try {
						long ownershipDeadline = System.currentTimeMillis() + 5_000L;
						while (Files.exists(CoordinatorProcessOwnership.ownershipFile(packageRoot))
								&& System.currentTimeMillis() < ownershipDeadline) {
							Thread.sleep(25L);
						}
						assertTrue(!Files.exists(CoordinatorProcessOwnership.ownershipFile(packageRoot)),
							"coordinator close clears the ownership record");
					} catch (AssertionError failure) {
						ownershipFailure = failure;
					} catch (InterruptedException interrupted) {
						Thread.currentThread().interrupt();
						ownershipFailure = new AssertionError("ownership cleanup wait was interrupted", interrupted);
					}
				}
			} finally {
				restoreProperty("arenaagents.packageRoot", oldPackageRoot);
				restoreProperty(NodeRuntimeLocator.PROPERTY, oldNodePath);
				restoreProperty("arenaagents.bridgeSecretFile", oldBridgeSecret);
				restoreProperty("arenaagents.voiceSecretFile", oldVoiceSecret);
				restoreProperty("arenaagents.voiceUrl", oldVoiceUrl);
				restoreProperty("arenaagents.voiceRequestTimeoutMs", oldVoiceRequestTimeout);
				deleteTree(packageRoot);
			}
			if (ownershipFailure != null) throw ownershipFailure;
		}
	}

	private static boolean completeHandshakeAndCatalog(Socket socket) throws Exception {
		BridgeEnvelopeCodec codec = new BridgeEnvelopeCodec();
		try (BufferedReader reader = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
			 BufferedWriter writer = new BufferedWriter(new OutputStreamWriter(socket.getOutputStream(), StandardCharsets.UTF_8))) {
			String secret = "s".repeat(32);
			JsonObject challenge = JsonParser.parseString(reader.readLine()).getAsJsonObject();
			assertEquals("auth_challenge", challenge.get("type").getAsString(),
					"coordinator starts without releasing a secret-derived proof");
			String clientNonce = challenge.getAsJsonObject("payload").get("clientNonce").getAsString();
			String serverNonce = Base64.getUrlEncoder().withoutPadding().encodeToString(
					MessageDigest.getInstance("SHA-256").digest("startup-smoke-server".getBytes(StandardCharsets.UTF_8))
			);
			JsonObject authPayload = new JsonObject();
			authPayload.addProperty("replyTo", challenge.get("messageId").getAsString());
			authPayload.addProperty("clientNonce", clientNonce);
			authPayload.addProperty("serverNonce", serverNonce);
			authPayload.addProperty("proof", authenticationProof(
					secret, "server", clientNonce, serverNonce, "startup-smoke-server", null
			));
			writer.write(codec.encode(new BridgeEnvelope(
					2, "startup-smoke-server", "server", "auth_response", "startup-smoke-auth", authPayload
			)));
			writer.flush();
			String helloLine = reader.readLine();
			JsonObject hello = JsonParser.parseString(helloLine).getAsJsonObject();
			assertEquals("hello", hello.get("type").getAsString(), "coordinator authenticates after the server proof");
			String launchId = hello.getAsJsonObject("payload").get("launchId").getAsString();
			java.util.UUID.fromString(launchId);
			assertEquals(
					authenticationProof(secret, "coordinator", clientNonce, serverNonce, "startup-smoke-server", launchId),
					hello.getAsJsonObject("payload").get("proof").getAsString(),
					"coordinator proof is bound to both nonces and the staged launch identity"
			);
			JsonObject payload = new JsonObject();
			payload.addProperty("replyTo", hello.get("messageId").getAsString());
			payload.addProperty("authenticated", true);
			payload.add("registry", new JsonArray());
			payload.addProperty("launchId", launchId);
			writer.write(codec.encode(new BridgeEnvelope(
					2,
					"startup-smoke-server",
					"server",
					"hello_ack",
					"startup-smoke-ack",
					payload
			)));
			writer.flush();
			boolean discoveryRequested = false;
			boolean stagedModelReady = false;
			long deadline = System.currentTimeMillis() + STARTUP_TIMEOUT_MS;
			while (System.currentTimeMillis() < deadline) {
				String line = reader.readLine();
				if (line == null) break;
				JsonObject message = JsonParser.parseString(line).getAsJsonObject();
				if (!"catalog_snapshot".equals(message.get("type").getAsString())) continue;
				JsonArray models = message.getAsJsonObject("payload").getAsJsonArray("models");
				if (models.toString().contains("gpt-5.6-luna")) {
					stagedModelReady = true;
					break;
				}
				if (discoveryRequested) continue;
				discoveryRequested = true;
				writer.write(codec.encode(new BridgeEnvelope(
						2,
						"startup-smoke-server",
						"server",
						"catalog_request",
						"startup-smoke-catalog-request",
						new JsonObject()
				)));
				writer.flush();
			}
			assertTrue(stagedModelReady, "catalog-ready boundary contains the staged Codex model");
			return true;
		}
	}

	private static int verifyPathRejectedBeforeSecretRead(
			Path root,
			String oldPackageRoot,
			String oldNodePath
	) throws Exception {
		Path maliciousDirectory = root.resolve("malicious-path");
		Path maliciousNode = maliciousDirectory.resolve(isWindows() ? "node.exe" : "node");
		Files.createDirectories(maliciousDirectory);
		stageHostNode(maliciousNode);
		Path invalidSecret = root.resolve("runtime/bridge-secret.txt");
		Files.createDirectories(invalidSecret);
		Map<String, String> maliciousPath = new HashMap<>();
		maliciousPath.put("PATH", maliciousDirectory.toString());
		CoordinatorProcessSupervisor blocked = null;
		try {
			System.setProperty("arenaagents.packageRoot", root.toString());
			System.clearProperty(NodeRuntimeLocator.PROPERTY);
			blocked = new CoordinatorProcessSupervisor(root.resolve("game"), maliciousPath);
			long deadline = System.currentTimeMillis() + STARTUP_TIMEOUT_MS;
			while (blocked.failureCode() == null && System.currentTimeMillis() < deadline) {
				blocked.tick(false);
				Thread.sleep(10L);
			}
			assertEquals("NODE_RUNTIME_NOT_FOUND", blocked.failureCode(),
					"malicious PATH cannot satisfy coordinator Node trust");
			assertTrue(Files.isDirectory(invalidSecret),
					"Node trust fails before the bridge secret target is examined");
			assertTrue(!Files.exists(root.resolve("coordinator")),
					"Node trust fails before coordinator installation reads or creates secrets");
			return 3;
		} finally {
			if (blocked != null) blocked.close();
			restoreProperty("arenaagents.packageRoot", oldPackageRoot);
			restoreProperty(NodeRuntimeLocator.PROPERTY, oldNodePath);
			deleteTree(root);
		}
	}

	private static String authenticationProof(
			String secret,
			String role,
			String clientNonce,
			String serverNonce,
			String serverInstanceId,
			String launchId
	) throws Exception {
		String context = String.join("\0", "arena-agents-v2", role, clientNonce, serverNonce, serverInstanceId)
				+ ("coordinator".equals(role) ? "\0" + (launchId == null ? "" : launchId) : "");
		Mac mac = Mac.getInstance("HmacSHA256");
		mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
		return Base64.getUrlEncoder().withoutPadding().encodeToString(
				mac.doFinal(context.getBytes(StandardCharsets.UTF_8))
		);
	}

	private static void stageCoordinator(Path source, Path root) throws IOException {
		String manifest = coordinatorManifest(source);
		BundledCoordinatorInstaller.install(root, resourcePath -> {
			String prefix = "arena-agents/coordinator/";
			if (!resourcePath.startsWith(prefix)) throw new IOException("Unexpected coordinator resource: " + resourcePath);
			String relative = resourcePath.substring(prefix.length());
			if (relative.equals("coordinator-manifest.txt")) {
				return new ByteArrayInputStream(manifest.getBytes(StandardCharsets.UTF_8));
			}
			Path file = source.resolve(relative).normalize();
			if (!file.startsWith(source) || !Files.isRegularFile(file)) {
				throw new IOException("Missing coordinator fixture resource: " + relative);
			}
			return Files.newInputStream(file);
		});
	}

	private static String coordinatorManifest(Path source) throws IOException {
		List<Path> files = new ArrayList<>();
		for (String fixed : List.of("package.json", "package-lock.json")) {
			Path file = source.resolve(fixed);
			if (Files.isRegularFile(file)) files.add(file);
		}
		for (String directory : List.of("config", "src", "node_modules/acorn")) {
			try (var paths = Files.walk(source.resolve(directory))) {
				paths.filter(Files::isRegularFile).forEach(files::add);
			}
		}
		files.sort(Comparator.comparing(file -> source.relativize(file).toString().replace('\\', '/')));
		StringBuilder manifest = new StringBuilder();
		for (Path file : files) {
			manifest.append(sha256(Files.readAllBytes(file))).append(' ')
					.append(source.relativize(file).toString().replace('\\', '/')).append('\n');
		}
		return manifest.toString();
	}

	private static String sha256(byte[] bytes) {
		try {
			return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
		} catch (NoSuchAlgorithmException impossible) {
			throw new IllegalStateException(impossible);
		}
	}

	private static Path stageBundledNode(Path root) throws IOException {
		Path bundled = isWindows()
				? root.resolve("runtime/toolchains/node/node.exe")
				: root.resolve("runtime/toolchains/node/bin/node");
		Files.createDirectories(bundled.getParent());
		stageHostNode(bundled);
		return bundled;
	}

	private static void stageHostNode(Path target) throws IOException {
		Path hostNode = findHostNode();
		try {
			Files.createLink(target, hostNode);
		} catch (IOException linkUnavailable) {
			// The fallback is temporary test state only; no runtime binary is stored in git or the release archive.
			Files.copy(hostNode, target, StandardCopyOption.REPLACE_EXISTING);
		}
	}

	private static FakeCodexEnvironment stageFakeCodex(Path root, Path node) throws IOException {
		Path entrypoint = root.resolve("fake-appdata/npm/node_modules/@openai/codex/bin/codex.js");
		Files.createDirectories(entrypoint.getParent());
		Files.writeString(entrypoint, """
				import readline from 'node:readline';
				const input = readline.createInterface({ input: process.stdin });
				input.on('line', (line) => {
					const request = JSON.parse(line);
					if (!Object.hasOwn(request, 'id')) return;
					const result = request.method === 'model/list'
						? { data: [{ id: 'gpt-5.6-luna', model: 'gpt-5.6-luna', displayName: 'Smoke model', supportedReasoningEfforts: ['xhigh'], serviceTiers: ['fast'] }] }
						: {};
					process.stdout.write(JSON.stringify({ id: request.id, result }) + '\\n');
				});
				""", StandardCharsets.UTF_8);
		Path appData = root.resolve("fake-appdata");
		if (isWindows()) return new FakeCodexEnvironment(appData, "");
		Path bin = root.resolve("fake-bin");
		Path launcher = bin.resolve("codex");
		Files.createDirectories(bin);
		Files.writeString(launcher, "#!/bin/sh\nexec " + shellQuote(node) + " " + shellQuote(entrypoint) + " \"$@\"\n",
				StandardCharsets.UTF_8);
		if (!launcher.toFile().setExecutable(true, true) && !Files.isExecutable(launcher)) {
			throw new IOException("could not make the fake Codex launcher executable");
		}
		return new FakeCodexEnvironment(appData, bin.toString());
	}

	private static String shellQuote(Path path) {
		return "'" + path.toString().replace("'", "'\"'\"'") + "'";
	}

	private static void writeSmokeConfig(Path root, int port, int voicePort) throws IOException {
		String config = """
				{
				  "bridge": { "host": "127.0.0.1", "port": %d, "secretEnvironmentVariable": "ARENA_AGENT_BRIDGE_SECRET", "reconnectDelayMs": 50, "maxReconnectDelayMs": 100 },
				  "codex": { "cwd": "%s", "planningTimeoutMs": 1000, "catalogTtlMs": 60000, "serviceTier": "fast", "launchProfile": { "model": "gpt-5.6-luna", "reasoningEffort": "xhigh", "serviceTier": "fast" } },
				  "voice": { "port": %d, "maxConcurrent": 1, "localSpeechTimeoutMs": 91234 },
				  "limits": { "agentCap": 1, "goalQueueCap": 1, "planningConcurrency": 1, "planningMode": "fixed", "urgentReserve": 0, "invalidDecisionRetries": 0 }
				}
				""".formatted(port, root.toString().replace("\\", "\\\\"), voicePort);
		Files.createDirectories(root.resolve("runtime"));
		Files.writeString(root.resolve("runtime/dynamic-agents.json"), config, StandardCharsets.UTF_8);
	}

	private static int verifyOptionalVoiceCredential(Path runtimeRoot) throws IOException {
		Path credential = runtimeRoot.resolve("runtime/fish-api-key.txt");
		Files.createDirectories(credential.getParent());
		Files.writeString(credential, "bad", StandardCharsets.UTF_8);
		Map<String, String> environment = new HashMap<>();
		CoordinatorProcessSupervisor.configureVoiceProviderCredential(runtimeRoot, environment);
		assertTrue(!environment.containsKey("FISH_AUDIO_API_KEY"),
				"malformed optional TTS credential is ignored");
		Files.writeString(credential, "valid-test-fish-key", StandardCharsets.UTF_8);
		CoordinatorProcessSupervisor.configureVoiceProviderCredential(runtimeRoot, environment);
		assertEquals("valid-test-fish-key", environment.get("FISH_AUDIO_API_KEY"),
				"valid optional TTS credential is injected");
		return 2;
	}

	private static int unusedLoopbackPort() throws IOException {
		try (ServerSocket socket = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
			return socket.getLocalPort();
		}
	}

	private static boolean awaitLoopbackListener(int port, long timeoutMs) throws InterruptedException {
		long deadline = System.currentTimeMillis() + timeoutMs;
		while (System.currentTimeMillis() < deadline) {
			try (Socket ignored = new Socket(InetAddress.getLoopbackAddress(), port)) {
				return true;
			} catch (IOException unavailable) {
				Thread.sleep(25L);
			}
		}
		return false;
	}

	private static Path findHostNode() throws IOException {
		String path = System.getenv("PATH");
		String executableName = isWindows() ? "node.exe" : "node";
		if (path != null) {
			for (String entry : path.split(java.util.regex.Pattern.quote(java.io.File.pathSeparator), -1)) {
				if (entry.isBlank()) continue;
				Path candidate = Path.of(entry).resolve(executableName).toAbsolutePath().normalize();
				if (Files.isRegularFile(candidate) && Files.isExecutable(candidate)) return candidate;
			}
		}
		throw new IOException("STARTUP_SMOKE_NODE_MISSING: Node 22+ is required to create the temporary bundled-runtime hardlink");
	}

	private static boolean isWindows() {
		return System.getProperty("os.name", "").toLowerCase(java.util.Locale.ROOT).contains("win");
	}

	private static void copyTree(Path source, Path target) throws IOException {
		if (!Files.isDirectory(source)) throw new IOException("Startup smoke source tree is missing: " + source);
		try (var paths = Files.walk(source)) {
			for (Path path : paths.toList()) {
				Path destination = target.resolve(source.relativize(path));
				if (Files.isDirectory(path)) Files.createDirectories(destination);
				else Files.copy(path, destination, StandardCopyOption.REPLACE_EXISTING);
			}
		}
	}

	private static void restoreProperty(String name, String value) {
		if (value == null) System.clearProperty(name);
		else System.setProperty(name, value);
	}

	private static void deleteTree(Path root) throws IOException {
		if (!Files.exists(root)) return;
		try (var paths = Files.walk(root)) {
			for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) deleteEventually(path);
		}
	}

	private static void deleteEventually(Path path) throws IOException {
		long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(2L);
		while (true) {
			try {
				Files.deleteIfExists(path);
				return;
			} catch (java.nio.file.AccessDeniedException exception) {
				if (System.nanoTime() >= deadline) throw exception;
				try {
					Thread.sleep(25L);
				} catch (InterruptedException interrupted) {
					Thread.currentThread().interrupt();
					throw new IOException("Interrupted while waiting for the fixture to release " + path, interrupted);
				}
			}
		}
	}

	private static void assertEquals(Object expected, Object actual, String label) {
		if (!java.util.Objects.equals(expected, actual)) throw new AssertionError(label + ": expected=" + expected + ", actual=" + actual);
	}

	private static void assertTrue(boolean condition, String label) {
		if (!condition) throw new AssertionError(label);
	}

	private record FakeCodexEnvironment(Path appData, String path) {
	}
}
