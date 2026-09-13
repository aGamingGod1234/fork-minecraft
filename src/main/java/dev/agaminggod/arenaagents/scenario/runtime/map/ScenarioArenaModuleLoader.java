package dev.agaminggod.arenaagents.scenario.runtime.map;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Contained classpath loader for verified arena module resources. */
public final class ScenarioArenaModuleLoader {
	private static final String RESOURCE_PREFIX = "data/arenaagents/arena_modules/";
	private static final int MAXIMUM_RESOURCE_BYTES = 8 * 1024 * 1024;
	private static final Pattern RESOURCE_PATH = Pattern.compile(
			"(?:[a-z0-9][a-z0-9._-]*/)*[a-z0-9][a-z0-9._-]*-v[1-9][0-9]*\\.json");
	private static final Pattern VERSIONED_FILENAME = Pattern.compile("([a-z0-9][a-z0-9-]*)-v([1-9][0-9]*)\\.json");

	private ScenarioArenaModuleLoader() {
	}

	public static ScenarioArenaModule load(String resourcePath) {
		if (resourcePath == null || !RESOURCE_PATH.matcher(resourcePath).matches()
				|| resourcePath.contains("..") || resourcePath.contains("\\") || resourcePath.startsWith("/")) {
			throw new IllegalArgumentException("arena module resource path is invalid or uncontained: " + resourcePath);
		}
		String classpathResource = RESOURCE_PREFIX + resourcePath;
		ClassLoader loader = Thread.currentThread().getContextClassLoader();
		if (loader == null) loader = ScenarioArenaModuleLoader.class.getClassLoader();
		byte[] encoded;
		try (InputStream stream = loader.getResourceAsStream(classpathResource)) {
			if (stream == null) throw new IllegalArgumentException("arena module resource is missing: " + resourcePath);
			encoded = readBounded(stream);
		} catch (IOException error) {
			throw new IllegalArgumentException("unable to read arena module resource: " + resourcePath, error);
		}
		ScenarioArenaModule module = ScenarioArenaModuleCodec.decode(new String(encoded, StandardCharsets.UTF_8));
		String filename = resourcePath.substring(resourcePath.lastIndexOf('/') + 1);
		Matcher matcher = VERSIONED_FILENAME.matcher(filename);
		if (!matcher.matches() || !module.id().equals(matcher.group(1))
				|| module.version() != Integer.parseInt(matcher.group(2))) {
			throw new IllegalArgumentException("arena module filename does not match module id/version: " + resourcePath);
		}
		return module;
	}

	private static byte[] readBounded(InputStream stream) throws IOException {
		ByteArrayOutputStream output = new ByteArrayOutputStream();
		byte[] buffer = new byte[16 * 1024];
		int total = 0;
		int read;
		while ((read = stream.read(buffer)) != -1) {
			total += read;
			if (total > MAXIMUM_RESOURCE_BYTES) {
				throw new IllegalArgumentException("arena module resource exceeds 8 MiB");
			}
			output.write(buffer, 0, read);
		}
		return output.toByteArray();
	}
}
