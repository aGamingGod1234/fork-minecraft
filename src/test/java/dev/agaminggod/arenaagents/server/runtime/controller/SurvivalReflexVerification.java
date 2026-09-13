package dev.agaminggod.arenaagents.server.runtime.controller;

public final class SurvivalReflexVerification {
	private SurvivalReflexVerification() {
	}

	public static int verify() {
		assertTrue(classIsAbsent("dev.agaminggod.arenaagents.server.runtime.controller.DamageReplanSignal"),
				"health changes must not expose a server-side replan or stop classifier");
		return 1;
	}

	private static void assertEquals(Object expected, Object actual, String message) {
		if (!expected.equals(actual)) {
			throw new AssertionError(message + " (expected=" + expected + ", actual=" + actual + ")");
		}
	}

	private static boolean classIsAbsent(String name) {
		try {
			Class.forName(name);
			return false;
		} catch (ClassNotFoundException expected) {
			return true;
		}
	}

	private static void assertTrue(boolean value, String message) {
		if (!value) throw new AssertionError(message);
	}
}
