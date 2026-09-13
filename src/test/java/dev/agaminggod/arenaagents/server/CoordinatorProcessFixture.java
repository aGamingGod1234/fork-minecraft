package dev.agaminggod.arenaagents.server;

import java.nio.file.Path;
import java.util.Locale;
import java.util.concurrent.TimeUnit;

final class CoordinatorProcessFixture {
	private CoordinatorProcessFixture() {
	}

	public static void main(String[] arguments) throws Exception {
		Thread.sleep(TimeUnit.MINUTES.toMillis(5));
	}
}

final class CoordinatorProcessTreeFixture {
	private CoordinatorProcessTreeFixture() {
	}

	public static void main(String[] arguments) throws Exception {
		String java = Path.of(
				System.getProperty("java.home"),
				"bin",
				System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win")
						? "java.exe" : "java"
		).toString();
		new ProcessBuilder(
				java,
				"-cp",
				System.getProperty("java.class.path"),
				CoordinatorProcessFixture.class.getName(),
				arguments[0]
		).start();
		Thread.sleep(TimeUnit.MINUTES.toMillis(5));
	}
}
