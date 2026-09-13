package dev.agaminggod.arenaagents.voiceaddon;

import java.io.File;
import java.lang.reflect.InvocationTargetException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/** Runs legacy provider startup without making the modern core configuration class loadable. */
final class LegacyVoiceProviderClasspathVerification {
	private static final String CONFIGURATION_CLASS =
			"dev.agaminggod.arenaagents.server.voice.VoiceSubsystemConfiguration";
	private static final String VERBOSE_REPORTER_CLASS =
			"dev.agaminggod.arenaagents.server.voice.VoiceInputVerboseReporter";

	private LegacyVoiceProviderClasspathVerification() {
	}

	static int verify() throws Exception {
		String legacyCoreClasses = System.getProperty("arenaagents.legacyCoreClasses");
		if (legacyCoreClasses == null || legacyCoreClasses.isBlank()) {
			throw new AssertionError("Legacy core compatibility classes were not configured");
		}
		List<URL> urls = new ArrayList<>();
		urls.add(Path.of(legacyCoreClasses).toUri().toURL());
		for (String entry : System.getProperty("java.class.path").split(File.pathSeparator)) {
			urls.add(Path.of(entry).toUri().toURL());
		}
		try (MissingConfigurationClassLoader loader = new MissingConfigurationClassLoader(urls)) {
			try {
				Class.forName(CONFIGURATION_CLASS, false, loader);
				throw new AssertionError("Modern voice configuration leaked into the legacy classpath");
			} catch (ClassNotFoundException expected) {
				// The class must stay absent for the startup assertion below to mean anything.
			}
			Class<?> scenario = Class.forName(
					"dev.agaminggod.arenaagents.voiceaddon.LegacyVoiceProviderClasspathScenario",
					true,
					loader
			);
			try {
				return (int) scenario.getMethod("verify").invoke(null);
			} catch (InvocationTargetException exception) {
				Throwable failure = exception.getCause();
				if (failure instanceof Exception checked) throw checked;
				if (failure instanceof Error error) throw error;
				throw new AssertionError("Legacy provider startup failed", failure);
			}
		}
	}

	private static final class MissingConfigurationClassLoader extends URLClassLoader {
		private MissingConfigurationClassLoader(List<URL> urls) {
			super(urls.toArray(URL[]::new), ClassLoader.getPlatformClassLoader());
		}

		@Override
		protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
			synchronized (getClassLoadingLock(name)) {
				if (name.equals(CONFIGURATION_CLASS) || name.equals(VERBOSE_REPORTER_CLASS)) {
					throw new ClassNotFoundException(name);
				}
				Class<?> loaded = findLoadedClass(name);
				if (loaded == null) {
					loaded = name.startsWith("dev.agaminggod.arenaagents.")
							? findClass(name)
							: super.loadClass(name, false);
				}
				if (resolve) resolveClass(loaded);
				return loaded;
			}
		}
	}
}
