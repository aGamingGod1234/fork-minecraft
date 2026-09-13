package dev.agaminggod.arenaagents.server;

import com.mojang.brigadier.StringReader;
import com.mojang.brigadier.arguments.StringArgumentType;
import dev.agaminggod.arenaagents.client.gui.SkitDirectorScreen;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/** Headless checks for director commands, timeline execution and placement. */
public final class SkitModeVerification {
	private SkitModeVerification() {
	}

	public static int verify() {
		SkitPlacement pose = new SkitPlacement("minecraft:overworld", 1.5D, 70.0D, -2.0D, 180.0F, -20.0F);
		SkitScript script = new SkitScript("takeoff", "ChatGPT", java.util.List.of())
				.append(new SkitStep(0, pose))
				.append(new SkitStep(20, new SkitPlacement("minecraft:overworld", 4.5D, 75.0D, -2.0D, 180.0F, -10.0F)));
		assertEquals(2, script.steps().size(), "timeline append preserves order");
		assertThrows(() -> new SkitPlacement("minecraft:overworld", 0, 0, 0, 0, 91), "pitch is bounded");
		assertThrows(() -> new SkitStep(-1, pose), "negative delays are rejected");
		assertThrows(() -> new SkitScript("bad name", "agent", java.util.List.of()), "script names are command-safe");
		assertEquals(2, new SkitStep(0, pose, java.util.List.of(SkitAction.move(20), SkitAction.swing())).actions().size(),
				"action steps preserve order");
		assertThrows(() -> SkitAction.move(0), "move requires a duration");
		assertThrows(() -> SkitAction.equip("diamond_sword"), "equip requires a namespaced item id");
		return 7 + verifyNamesAndPlacement() + verifyTimeline() + verifyPreflight() + verifyCleanup() + verifyGuiCommands();
	}

	private static int verifyNamesAndPlacement() {
		for (String selector : List.of("GPT 5.6-Sol", "演员 Lucas", "Alex \"The Builder\"", "@e")) {
			assertEquals(selector, new SkitScript("intro", selector, List.of()).agentSelector(), "actor selectors preserve display names");
		}
		assertThrows(() -> new SkitScript("intro", "Alex\nstop", List.of()), "selectors reject control characters");
		assertThrows(() -> new SkitScript("intro", "Alex", Arrays.asList((SkitStep) null)), "null steps are validation errors");
		SkitPlacement origin = new SkitPlacement("minecraft:overworld", 0, 64, 0, 0, 0);
		assertThrows(() -> new SkitStep(0, origin, Arrays.asList((SkitAction) null)), "null actions are validation errors");
		assertThrows(() -> new SkitPlacement("bad dimension", 0, 64, 0, 0, 0), "dimension identifiers are validated");
		SkitPlacement south = SkitPlacement.relativeTo(origin, 2, 3, 4);
		assertEquals(-2, south.x(), "right when facing south is west");
		assertEquals(67, south.y(), "relative up retains world height");
		assertEquals(4, south.z(), "forward when facing south is south");
		SkitPlacement west = SkitPlacement.relativeTo(new SkitPlacement("minecraft:overworld", 0, 64, 0, 90, 0), 2, 0, 4);
		assertEquals(-4, west.x(), "forward when facing west is west");
		assertEquals(-2, west.z(), "right when facing west is north");
		return 13;
	}

	private static int verifyTimeline() {
		SkitPlacement origin = new SkitPlacement("minecraft:overworld", 0, 64, 0, 350, 0);
		SkitPlacement endpoint = new SkitPlacement("minecraft:overworld", 8, 66, 4, 10, 20);
		FakePerformer actor = new FakePerformer(origin);
		List<SkitStep> steps = List.of(new SkitStep(2, origin, List.of(SkitAction.waitTicks(4))),
				new SkitStep(0, endpoint, List.of(SkitAction.waitTicks(4), SkitAction.move(4), SkitAction.swing())));
		// Keep the second step's initial WAIT at the origin, then aim MOVE at an explicit endpoint.
		List<SkitStep> chained = List.of(new SkitStep(2, endpoint, List.of(SkitAction.move(4), SkitAction.move(4), SkitAction.swing())));
		SkitModeRuntime.Playback run = SkitModeRuntime.Playback.waiting(chained, 10);
		run = SkitModeRuntime.advancePlayback(run, 11, actor);
		assertEquals(0, actor.starts.size(), "delayed scripts do not start early");
		run = SkitModeRuntime.advancePlayback(run, 15, actor);
		assertEquals(15, run.actionStartTick(), "late dispatch starts its clock when the action actually runs");
		assertEquals(origin, actor.position(), "movement begins at the actor's actual pose");
		for (long tick = 16; tick <= 18; tick++) run = SkitModeRuntime.advancePlayback(run, tick, actor);
		assertEquals(6, actor.position().x(), "movement has not reached the endpoint before its duration");
		run = SkitModeRuntime.advancePlayback(run, 19, actor);
		assertEquals(endpoint, actor.position(), "the first move reaches its exact endpoint");
		assertEquals(List.of("MOVE@0", "MOVE@0"), actor.starts, "the next action starts exactly once on the boundary");
		assertEquals(19, run.actionStartTick(), "chained actions do not overlap or repeat their initial tick");
		assertEquals(1, actor.stops.size(), "only the completed move has released controls");
		// Simulate a new physical origin before another chained MOVE to expose truncated interpolation.
		FakePerformer second = new FakePerformer(origin);
		SkitModeRuntime.Playback moveAfterWait = SkitModeRuntime.Playback.waiting(
				List.of(new SkitStep(0, endpoint, List.of(SkitAction.waitTicks(2), SkitAction.move(4)))), 0);
		moveAfterWait = SkitModeRuntime.advancePlayback(moveAfterWait, 0, second);
		second.pose = origin;
		for (long tick = 1; tick <= 5; tick++) moveAfterWait = SkitModeRuntime.advancePlayback(moveAfterWait, tick, second);
		assertEquals(6, second.position().x(), "chained move has 75 percent progress one tick before completion");
		assertTrue(moveAfterWait != null, "chained move is still reserved before its endpoint");
		moveAfterWait = SkitModeRuntime.advancePlayback(moveAfterWait, 6, second);
		assertEquals(endpoint, second.position(), "chained move reaches 100 percent before releasing the actor");
		assertTrue(moveAfterWait == null, "finished script releases its timeline");
		for (long tick = 20; tick <= 23; tick++) run = SkitModeRuntime.advancePlayback(run, tick, actor);
		assertEquals(List.of("MOVE@0", "MOVE@0", "SWING@0"), actor.starts, "one-shot effects fire only once");
		assertTrue(run != null, "one-tick effects remain active until the next server tick");
		run = SkitModeRuntime.advancePlayback(run, 24, actor);
		assertTrue(run == null, "one-tick effect finishes without an extra idle tick");
		assertEquals(3, actor.stops.size(), "each action releases exactly once");
		FakePerformer jumper = new FakePerformer(origin);
		SkitModeRuntime.Playback jump = SkitModeRuntime.Playback.waiting(List.of(new SkitStep(0, origin, List.of(SkitAction.jump()))), 30);
		jump = SkitModeRuntime.advancePlayback(jump, 30, jumper);
		assertEquals(0, jumper.stops.size(), "jump is held until a player tick can consume it");
		jump = SkitModeRuntime.advancePlayback(jump, 31, jumper);
		assertTrue(jump == null && jumper.stops.size() == 1, "jump releases after one full server tick");
		FakePerformer delayed = new FakePerformer(origin);
		SkitModeRuntime.Playback delays = SkitModeRuntime.Playback.waiting(steps, 0);
		delays = SkitModeRuntime.advancePlayback(delays, 2, delayed);
		delays = SkitModeRuntime.advancePlayback(delays, 6, delayed);
		assertEquals(1, delays.index(), "zero-delay steps advance on the completion boundary");
		assertEquals(6, delays.actionStartTick(), "next step does not lose a server tick");
		assertThrows(() -> SkitModeRuntime.interpolate(origin, new SkitPlacement("minecraft:the_nether", 0, 64, 0, 0, 0), .5F),
				"cross-dimension movement cannot blend unrelated coordinates");
		assertThrows(() -> SkitModeRuntime.interpolate(origin, endpoint, Float.NaN), "invalid movement progress is rejected");
		assertEquals(360, SkitModeRuntime.interpolate(origin, endpoint, .5F).yaw(), "actor rotation takes the shortest turn");
		return 23;
	}

	private static int verifyPreflight() {
		SkitPlacement overworld = new SkitPlacement("minecraft:overworld", 0, 64, 0, 0, 0);
		SkitPlacement nether = new SkitPlacement("minecraft:the_nether", 0, 64, 0, 0, 0);
		assertThrows(() -> SkitModeRuntime.validateTimeline(new SkitScript("intro", "Alex", List.of(new SkitStep(0, nether))),
				"minecraft:overworld", dimension -> dimension.equals("minecraft:overworld"), item -> true), "missing dimensions fail before reservation");
		assertThrows(() -> SkitModeRuntime.validateTimeline(new SkitScript("intro", "Alex", List.of(new SkitStep(0, overworld, List.of(SkitAction.equip("minecraft:not_an_item"))))),
				"minecraft:overworld", dimension -> true, item -> false), "unknown items fail before any timeline effect");
		assertThrows(() -> SkitModeRuntime.validateTimeline(new SkitScript("intro", "Alex", List.of(new SkitStep(0, nether, List.of(SkitAction.move(20))))),
				"minecraft:overworld", dimension -> true, item -> true), "movement across dimensions requires an explicit placement");
		SkitModeRuntime.validateTimeline(new SkitScript("intro", "Alex", List.of(new SkitStep(0, nether), new SkitStep(0, nether, List.of(SkitAction.move(20))))),
				"minecraft:overworld", dimension -> true, item -> true);
		return 4;
	}

	private static int verifyCleanup() {
		List<String> cleanup = new ArrayList<>();
		SkitModeRuntime.cleanup(() -> cleanup.add("input"), () -> cleanup.add("use"));
		assertEquals(List.of("input", "use"), cleanup, "cancellation clears input and held item use");
		cleanup.clear();
		SkitModeRuntime.cleanup(() -> { throw new IllegalStateException("expected test cleanup failure"); }, () -> cleanup.add("use"));
		assertEquals(List.of("use"), cleanup, "item use is released even when input cleanup fails");
		return 2;
	}

	private static int verifyGuiCommands() {
		assertEquals("codex skit script stop Alex", gui("scriptCommand", new Class<?>[]{String.class, String.class, String.class}, "stop", "intro", "Alex"),
				"Stop actor sends only the selector");
		assertEquals("codex skit script play intro Alex", gui("scriptCommand", new Class<?>[]{String.class, String.class, String.class}, "play", "intro", "Alex"),
				"Play script honors the GUI actor override");
		assertEquals("codex skit script play intro", gui("scriptCommand", new Class<?>[]{String.class, String.class, String.class}, "play", "intro", ""),
				"empty playback override preserves the script actor");
		String text = "He said \"Go!\" C:\\casts, café.";
		for (String prefix : List.of("codex skit voice say Alex", "codex skit voice script add dialogue 0")) {
			String command = (String) gui("lineCommand", new Class<?>[]{String.class, String.class}, prefix, text);
			try {
				StringReader reader = new StringReader(command);
				reader.setCursor(prefix.length() + 1);
				assertEquals(text, StringArgumentType.greedyString().parse(reader), "raw speech reaches Brigadier without added quoting or escaping");
			} catch (com.mojang.brigadier.exceptions.CommandSyntaxException exception) {
				throw new AssertionError("GUI speech must parse", exception);
			}
		}
		assertEquals(4, gui("maxScrollRows", new Class<?>[]{int.class, int.class}, 220, 7), "all voice rows remain reachable on a 240-pixel-high GUI");
		assertEquals(0, gui("maxScrollRows", new Class<?>[]{int.class, int.class}, 390, 7), "full-height director needs no scrolling");
		Object models = gui("models", new Class<?>[]{String.class}, "claude");
		assertTrue(models instanceof List<?> values && values.stream().allMatch(value -> value.toString().startsWith("claude-")),
				"Claude selection never sends a Gemini model with Claude reasoning");
		return 8;
	}

	private static Object gui(String name, Class<?>[] parameters, Object... arguments) {
		try {
			Method method = SkitDirectorScreen.class.getDeclaredMethod(name, parameters);
			method.setAccessible(true);
			return method.invoke(null, arguments);
		} catch (ReflectiveOperationException exception) {
			throw new AssertionError("Director command verification failed", exception);
		}
	}

	private static final class FakePerformer implements SkitModeRuntime.Performer {
		private SkitPlacement pose;
		private final List<String> starts = new ArrayList<>();
		private final List<SkitAction.Type> stops = new ArrayList<>();
		private FakePerformer(SkitPlacement pose) { this.pose = pose; }
		@Override public SkitPlacement position() { return pose; }
		@Override public void place(SkitPlacement placement) { pose = placement; }
		@Override public void perform(SkitStep step, SkitAction action, SkitPlacement origin, long elapsed, boolean firstTick) {
			if (firstTick) starts.add(action.type() + "@" + elapsed);
			if (action.type() == SkitAction.Type.MOVE) {
				pose = SkitModeRuntime.interpolate(origin, step.placement(), Math.min(1.0F, (float) elapsed / action.durationTicks()));
			}
		}
		@Override public void stop(SkitAction action) { stops.add(action.type()); }
	}

	private static void assertThrows(Runnable action, String label) {
		try {
			action.run();
			throw new AssertionError(label + ": expected IllegalArgumentException");
		} catch (IllegalArgumentException expected) {
		}
	}

	private static void assertEquals(double expected, double actual, String label) {
		if (!Double.isFinite(actual) || Math.abs(expected - actual) > 0.000001D) throw new AssertionError(label + ": expected " + expected + ", got " + actual);
	}

	private static void assertEquals(Object expected, Object actual, String label) {
		if (!java.util.Objects.equals(expected, actual)) throw new AssertionError(label + ": expected " + expected + ", got " + actual);
	}

	private static void assertTrue(boolean condition, String label) {
		if (!condition) throw new AssertionError(label);
	}
}
