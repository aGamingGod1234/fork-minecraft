package dev.agaminggod.arenaagents.server;

import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/** Focused, dependency-free checks for trusted Node runtime selection. */
public final class NodeRuntimeLocatorVerification {
	private NodeRuntimeLocatorVerification() {
	}

	public static int verify() throws Exception {
		Class<?> locator = Class.forName("dev.agaminggod.arenaagents.server.NodeRuntimeLocator");
		Class<?> probeType = Class.forName(locator.getName() + "$Probe");
		Method locate = locator.getDeclaredMethod("locate", Path.class, String.class, probeType);
		locate.setAccessible(true);

		Path root = Files.createTempDirectory("arena-node-locator");
		try {
			Path bundled = bundledExecutable(root);
			Object probe = Proxy.newProxyInstance(
					probeType.getClassLoader(),
					new Class<?>[]{probeType},
					(proxy, method, args) -> "v22.14.0"
			);
			Object result = locate.invoke(null, root, "", probe);
			assertEquals(bundled.toAbsolutePath().normalize(), property(result, "executable"),
					"bundled executable is selected without an explicit override");
			assertEquals("BUNDLED_PROFILE", property(result, "source").toString(),
					"bundled source is reported");

			Path explicit = root.resolve("explicit-node.exe");
			writeExecutableFixture(explicit);
			result = locate.invoke(null, root, explicit.toString(), probe);
			assertEquals(explicit.toAbsolutePath().normalize(), property(result, "executable"),
					"explicit executable wins over bundled runtime");

			try {
				locate.invoke(null, root, "node.exe", probe);
				throw new AssertionError("relative explicit executable must fail closed");
			} catch (InvocationTargetException expected) {
				assertEquals("NODE_RUNTIME_EXPLICIT_INVALID", failureCode(expected.getCause()),
						"relative explicit executable has an actionable code");
			}

			try {
				locate.invoke(null, root, root.resolve("missing-node.exe").toString(), probe);
				throw new AssertionError("invalid explicit executable must fail closed");
			} catch (InvocationTargetException expected) {
				assertEquals("NODE_RUNTIME_EXPLICIT_INVALID", failureCode(expected.getCause()),
						"invalid explicit executable has an actionable code");
			}

			verifyMaliciousPathRejected(root);

			Path invalidBundledRoot = Files.createTempDirectory(root, "invalid-bundled-");
			Path invalidBundled = bundledExecutable(invalidBundledRoot);
			Object failingBundledProbe = Proxy.newProxyInstance(
					probeType.getClassLoader(),
					new Class<?>[]{probeType},
					(proxy, method, args) -> {
						if (Path.of(args[0].toString()).equals(invalidBundled)) throw new IOException("probe failed");
						return "v22.14.0";
					}
			);
			try {
				locate.invoke(null, invalidBundledRoot, "", failingBundledProbe);
				throw new AssertionError("invalid bundled runtime must not fall through to PATH");
			} catch (InvocationTargetException expected) {
				assertEquals("NODE_RUNTIME_BUNDLED_INVALID", failureCode(expected.getCause()),
						"invalid bundled runtime has an actionable code");
			}

			Object oldVersionProbe = Proxy.newProxyInstance(
					probeType.getClassLoader(),
					new Class<?>[]{probeType},
					(proxy, method, args) -> "v20.11.1"
			);
			try {
				locate.invoke(null, root, explicit.toString(), oldVersionProbe);
				throw new AssertionError("unsupported Node version must fail");
			} catch (InvocationTargetException expected) {
				assertEquals("NODE_RUNTIME_VERSION_UNSUPPORTED", failureCode(expected.getCause()),
						"unsupported Node version has a stable code");
			}
			return 8;
		} finally {
			deleteTree(root);
		}
	}

	public static void main(String[] arguments) throws Exception {
		if (arguments.length == 0) {
			verify();
			return;
		}
		if (arguments.length != 2 || !"--assert-path-rejected".equals(arguments[0])) {
			throw new IllegalArgumentException("Expected --assert-path-rejected <package-root>");
		}
		System.clearProperty(NodeRuntimeLocator.PROPERTY);
		try {
			NodeRuntimeLocator.locate(Path.of(arguments[1]));
			throw new AssertionError("Node from PATH must not be selected when the bundled runtime is absent");
		} catch (NodeRuntimeLocator.NodeRuntimeFailure expected) {
			assertEquals("NODE_RUNTIME_NOT_FOUND", expected.code(), "malicious PATH is ignored");
		}
	}

	private static void verifyMaliciousPathRejected(Path root) throws Exception {
		Path maliciousDirectory = Files.createTempDirectory(root, "malicious-path-");
		Path maliciousNode = maliciousDirectory.resolve(isWindows() ? "node.exe" : "node");
		stageExecutable(findHostNode(), maliciousNode);
		Path packageRoot = Files.createTempDirectory(root, "path-package-");
		Path java = Path.of(System.getProperty("java.home"), "bin", isWindows() ? "java.exe" : "java");
		ProcessBuilder builder = new ProcessBuilder(
				java.toString(),
				"-cp",
				System.getProperty("java.class.path"),
				NodeRuntimeLocatorVerification.class.getName(),
				"--assert-path-rejected",
				packageRoot.toString()
		).redirectErrorStream(true);
		Map<String, String> environment = builder.environment();
		environment.keySet().removeIf(name -> name.equalsIgnoreCase("PATH"));
		environment.put("PATH", maliciousDirectory.toString());
		Process child = builder.start();
		if (!child.waitFor(10, TimeUnit.SECONDS)) {
			child.destroyForcibly();
			throw new AssertionError("malicious PATH rejection child timed out");
		}
		String output = new String(child.getInputStream().readAllBytes(), StandardCharsets.UTF_8).trim();
		if (child.exitValue() != 0) {
			throw new AssertionError("malicious PATH was not rejected: " + output);
		}
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
		throw new IOException("Node 22+ is required for the malicious PATH verification fixture");
	}

	private static void stageExecutable(Path source, Path target) throws IOException {
		try {
			Files.createLink(target, source);
		} catch (IOException linkUnavailable) {
			Files.copy(source, target, StandardCopyOption.COPY_ATTRIBUTES);
			target.toFile().setExecutable(true);
			if (!Files.isExecutable(target)) {
				throw new IOException("Could not make the malicious PATH fixture executable: " + target);
			}
		}
	}

	private static boolean isWindows() {
		return System.getProperty("os.name", "").toLowerCase(java.util.Locale.ROOT).contains("win");
	}

	private static Path bundledExecutable(Path root) throws Exception {
		Path path = isWindows()
				? root.resolve("runtime/toolchains/node/node.exe")
				: root.resolve("runtime/toolchains/node/bin/node");
		Files.createDirectories(path.getParent());
		writeExecutableFixture(path);
		return path;
	}

	private static void writeExecutableFixture(Path path) throws IOException {
		Files.writeString(path, "fixture");
		path.toFile().setExecutable(true);
		if (!Files.isExecutable(path)) throw new IOException("Could not make test fixture executable: " + path);
	}

	private static Object property(Object result, String name) throws Exception {
		return result.getClass().getMethod(name).invoke(result);
	}

	private static String failureCode(Throwable error) throws Exception {
		return (String) error.getClass().getMethod("code").invoke(error);
	}

	private static void deleteTree(Path root) throws Exception {
		if (!Files.exists(root)) return;
		try (var paths = Files.walk(root)) {
			for (Path path : paths.sorted(java.util.Comparator.reverseOrder()).toList()) Files.deleteIfExists(path);
		}
	}

	private static void assertEquals(Object expected, Object actual, String label) {
		if (!java.util.Objects.equals(expected, actual)) {
			throw new AssertionError(label + ": expected=" + expected + ", actual=" + actual);
		}
	}
}
