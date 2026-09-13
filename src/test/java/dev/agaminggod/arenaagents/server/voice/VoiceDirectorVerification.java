package dev.agaminggod.arenaagents.server.voice;

/** Focused checks for bounded voice profiles and deterministic cue timelines. */
public final class VoiceDirectorVerification {
	private VoiceDirectorVerification() {
	}

	public static int verify() {
		VoiceProfile profile = new VoiceProfile("voice.claude.v1", "dramatic", 1.25D, 64);
		assertEquals("voice.claude.v1", profile.profileId(), "profile id is retained");
		assertEquals("dramatic", profile.tone(), "tone is retained");
		VoiceCue inherited = new VoiceCue(20, "I have a plan.");
		VoiceCue override = new VoiceCue(40, "Now!", "voice.claude.v1", "excited", 1.5D, 32);
		VoiceScript script = new VoiceScript("intro", "Claude", java.util.List.of())
				.append(inherited).append(override);
		assertEquals(2, script.cues().size(), "cues append in order");
		assertThrows(() -> new VoiceProfile("voice", "neutral", 2.1D, 48), "profile speed is bounded");
		assertThrows(() -> new VoiceCue(0, "line", "", "", 0.2D, 0), "cue speed is bounded");
		assertThrows(() -> new VoiceCue(0, "line", "", "", -1D, 129), "cue radius is bounded");
		return 7;
	}

	private static void assertThrows(Runnable action, String label) {
		try {
			action.run();
			throw new AssertionError(label + ": expected IllegalArgumentException");
		} catch (IllegalArgumentException expected) {
		}
	}

	private static void assertEquals(Object expected, Object actual, String label) {
		if (!java.util.Objects.equals(expected, actual)) throw new AssertionError(label + ": expected " + expected + ", got " + actual);
	}
}
