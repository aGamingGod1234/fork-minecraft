package dev.agaminggod.arenaagents.client.camera;

import com.google.gson.JsonObject;
import dev.agaminggod.arenaagents.client.mixin.CameraEyeHeightAccessor;
import java.io.IOException;
import java.io.Writer;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.minecraft.client.Camera;
import net.minecraft.client.CameraType;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Options;
import net.minecraft.client.gui.Gui;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.Marker;
import net.minecraft.world.flag.FeatureFlagSet;
import net.minecraft.world.flag.FeatureFlags;
import net.minecraft.world.phys.Vec3;

/** Focused checks for path sampling, take safety, persistence and camera lifecycle. */
public final class CameraPathVerification {
	private CameraPathVerification() {
	}

	public static int verify() {
		CameraPath path = new CameraPath("intro", java.util.List.of(
				new CameraKeyframe(0, 0.0D, 64.0D, 0.0D, 350.0F, 0.0F),
				new CameraKeyframe(20, 10.0D, 66.0D, 0.0D, 10.0F, 20.0F),
				new CameraKeyframe(40, 20.0D, 64.0D, 4.0D, 30.0F, 0.0F)
		));
		CameraPose middle = path.sample(10.0D);
		assertTrue(middle.x() > 0.0D && middle.x() < 10.0D, "position interpolation stays in the segment");
		assertTrue(Math.abs(middle.yaw()) > 350.0F || Math.abs(middle.yaw()) < 20.0F, "yaw takes the short turn across zero");
		assertEquals(20.0D, path.sample(100.0D).x(), "samples after the end hold the final frame");
		CameraPath replaced = path.append(new CameraKeyframe(40, 99.0D, 64.0D, 0.0D, 0.0F, 0.0F));
		assertEquals(99.0D, replaced.sample(40.0D).x(), "same-tick frame replaces the old frame");
		assertThrows(() -> new CameraPath("bad name", java.util.List.of(new CameraKeyframe(0, 0.0D, 0.0D, 0.0D, 0.0F, 0.0F))), "path names are command-safe");
		assertThrows(() -> new CameraPath("duplicate", java.util.List.of(
				new CameraKeyframe(0, 0.0D, 0.0D, 0.0D, 0.0F, 0.0F),
				new CameraKeyframe(0, 1.0D, 0.0D, 0.0D, 0.0F, 0.0F))), "duplicate frame times are rejected");
		try {
			return 6 + verifyDelayedStart() + verifyUnboundedYaw() + verifyReplacement()
					+ verifyRestart() + verifySaveFailure() + verifyDeleteAndClearFailure()
					+ verifyMalformedStorage() + verifyRecordingLevelChange() + verifyRecordingClockCorrection()
					+ verifyPlaybackLevelChange() + verifyPlaybackRespawn() + verifyWriterFailure() + verifyAnchorAndPerspective() + verifyRenderedEyeHeight();
		} catch (Exception exception) {
			throw new AssertionError("camera verification failed", exception);
		}
	}

	private static int verifyDelayedStart() {
		CameraPath path = new CameraPath("delayed", List.of(
				new CameraKeyframe(20, 10, 64, 30, 45, 10),
				new CameraKeyframe(40, 20, 70, 40, 90, 20)));
		for (double tick : new double[] {-10, 0, 1, 10, 19.5, 20}) {
			assertTrue(path.sample(tick).equals(new CameraPose(10, 64, 30, 45, 10)), "hold the first pose at tick " + tick);
		}
		return 6;
	}

	private static int verifyUnboundedYaw() {
		float[][] turns = {{1080, 0, 1080}, {1080, 10, 1085}, {0, -1080, 0},
				{-1080, 0, -1080}, {0, 1080, 0}, {350, 10, 360}, {10, 350, 0}, {-710, 710, -720}};
		for (float[] turn : turns) {
			CameraPath path = new CameraPath("turn", List.of(
					new CameraKeyframe(0, 0, 64, 0, turn[0], 0),
					new CameraKeyframe(20, 0, 64, 0, turn[1], 0)));
			assertEquals(turn[2], path.sample(10).yaw(), "shortest midpoint from " + turn[0] + " to " + turn[1]);
		}
		return turns.length;
	}

	private static int verifyReplacement() throws Exception {
		try (Fixture fixture = new Fixture()) {
			CameraPath original = savedPath("intro");
			fixture.paths.put("intro", original);
			fixture.client.level = null;
			CameraDirectorClient.startRecordingFromGui("intro", true);
			assertTrue(fixture.paths.get("intro") == original, "invalid replacement preserves the saved path");
			fixture.client.level = fixture.level;
			CameraDirectorClient.startRecordingFromGui(" intro ", false);
			assertTrue(state("recording") == null, "normalized names cannot bypass duplicate validation");
			for (int i = 1; i < 64; i++) fixture.paths.put("path" + i, savedPath("path" + i));
			CameraDirectorClient.startRecordingFromGui(" intro ", true);
			assertTrue(state("recording") != null, "an existing path can be replaced at library capacity");
			assertTrue(fixture.paths.get("intro") == original, "replacement keeps the saved path until save succeeds");
			CameraDirectorClient.stopRecordingFromGui();
			assertTrue(fixture.paths.size() == 64 && fixture.paths.get("intro") != original, "successful replacement commits under the normalized name");
			assertTrue(Files.isRegularFile(fixture.storage()), "successful replacement reaches disk");
		}
		return 6;
	}

	private static int verifyRestart() throws Exception {
		try (Fixture fixture = new Fixture()) {
			CameraDirectorClient.startRecordingFromGui("first", false);
			Object take = state("recording");
			CameraDirectorClient.startRecordingFromGui("second", false);
			assertTrue(state("recording") == take, "GUI restart preserves the unsaved take");
			assertEquals(0, fixture.command("startRecording", "third"), "command restart is rejected");
			assertTrue(state("recording") == take, "command restart preserves the unsaved take");
		}
		return 3;
	}

	private static int verifySaveFailure() throws Exception {
		try (Fixture fixture = new Fixture()) {
			CameraDirectorClient.startRecordingFromGui("intro", false);
			CameraDirectorClient.stopRecordingFromGui();
			CameraPath original = fixture.paths.get("intro");
			String stored = Files.readString(fixture.storage());
			CameraDirectorClient.startRecordingFromGui("intro", true);
			Object take = state("recording");
			Path blocked = fixture.storage().resolveSibling("camera-paths.json.tmp");
			Files.createDirectory(blocked);
			CameraDirectorClient.stopRecordingFromGui();
			assertTrue(state("recording") == take, "failed GUI save retains the unsaved take for retry");
			assertTrue(fixture.paths.get("intro") == original, "failed replacement keeps the old in-memory path");
			assertTrue(Files.readString(fixture.storage()).equals(stored), "failed replacement keeps the old file");
			assertTrue(!fixture.gui.message.startsWith("Saved camera path"), "failed GUI save never reports success");
			assertEquals(0, fixture.command("stopRecording"), "failed command save returns failure");
			assertTrue(state("recording") == take && fixture.paths.get("intro") == original, "failed command save preserves both takes");
			Files.delete(blocked);
			assertEquals(1, fixture.command("stopRecording"), "the retained recording can be saved after IO recovers");
			CameraPath saved = fixture.paths.get("intro");
			assertTrue(saved != original && state("recording") == null, "successful retry commits and ends recording");
			fixture.paths.clear();
			invoke("load", new Class<?>[] {Minecraft.class}, fixture.client);
			assertTrue(saved.equals(fixture.paths.get("intro")), "committed recording survives a disk round trip");
		}
		return 9;
	}

	private static int verifyDeleteAndClearFailure() throws Exception {
		try (Fixture fixture = new Fixture()) {
			CameraPath original = savedPath("intro");
			fixture.paths.put("intro", original);
			Files.writeString(fixture.directory.resolve("config"), "blocks directory creation");
			assertEquals(0, fixture.command("delete", "intro"), "failed delete returns failure");
			assertTrue(fixture.paths.get("intro") == original, "failed delete preserves the library");
			assertEquals(0, fixture.command("clear"), "failed clear returns failure");
			assertTrue(fixture.paths.get("intro") == original, "failed clear preserves the library");
		}
		return 4;
	}

	private static int verifyMalformedStorage() throws Exception {
		String[] malformed = {"[]", "null", "{\"paths\":{}}", "{\"paths\":[[]]}",
				"{\"paths\":[{\"name\":\"intro\",\"keyframes\":{}}]}",
				"{\"paths\":[{\"name\":\"intro\",\"keyframes\":[{}]}]}",
				"{\"paths\":[{\"name\":{},\"keyframes\":[]}]}", "{broken"};
		try (Fixture fixture = new Fixture()) {
			Files.createDirectories(fixture.storage().getParent());
			for (String json : malformed) {
				fixture.paths.put("stale", savedPath("stale"));
				Files.writeString(fixture.storage(), json);
				invoke("load", new Class<?>[] {Minecraft.class}, fixture.client);
				assertTrue(fixture.paths.isEmpty(), "malformed storage leaves an empty library: " + json);
			}
		}
		return malformed.length;
	}

	private static int verifyRecordingLevelChange() throws Exception {
		try (Fixture fixture = new Fixture()) {
			CameraDirectorClient.startRecordingFromGui("intro", false);
			fixture.changeLevel();
			CameraDirectorClient.recordKeyframeFromGui();
			assertTrue(state("recording") == null, "GUI capture cancels a take from a different level before the next tick");
			CameraDirectorClient.startRecordingFromGui("command", false);
			fixture.changeLevel();
			assertEquals(0, fixture.command("recordKeyframe"), "command capture rejects a different level");
			assertTrue(state("recording") == null, "command capture ends the invalid take");
			CameraDirectorClient.startRecordingFromGui("tick", false);
			fixture.changeLevel();
			invoke("tick", new Class<?>[] {Minecraft.class}, fixture.client);
			assertTrue(state("recording") == null, "client tick cancels recording on level identity change");
		}
		return 4;
	}

	private static int verifyRecordingClockCorrection() throws Exception {
		for (boolean command : new boolean[] {false, true}) {
			try (Fixture fixture = new Fixture()) {
				CameraDirectorClient.startRecordingFromGui("clock", false);
				fixture.level.gameTime = 120;
				CameraDirectorClient.recordKeyframeFromGui();
				fixture.level.gameTime = 119;
				if (command) assertEquals(0, fixture.command("recordKeyframe"), "command rejects a backwards clock capture");
				else {
					CameraDirectorClient.recordKeyframeFromGui();
					assertTrue(fixture.gui.message.contains("clock moved backwards"), "GUI explains why the backwards clock capture was rejected");
				}
				fixture.level.gameTime = 120;
				CameraDirectorClient.recordKeyframeFromGui();
				fixture.level.gameTime = 140;
				CameraDirectorClient.recordKeyframeFromGui();
				assertEquals(1, fixture.command("stopRecording"), "clock correction leaves the recording saveable");
				CameraPath saved = fixture.paths.get("clock");
				assertTrue(saved.keyframes().stream().map(CameraKeyframe::tick).toList().equals(List.of(0, 20, 40)), "clock correction cannot add out-of-order or duplicate keyframes");
				assertTrue(state("recording") == null, "saving after clock recovery ends the take");
			}
		}
		return 8;
	}

	private static int verifyPlaybackLevelChange() throws Exception {
		try (Fixture fixture = new Fixture()) {
			fixture.paths.put("intro", savedPath("intro"));
			fixture.client.options.setCameraType(CameraType.THIRD_PERSON_BACK);
			CameraDirectorClient.playFromGui("intro", true);
			Marker anchor = (Marker) state("cameraAnchor");
			fixture.changeLevel();
			invoke("tick", new Class<?>[] {Minecraft.class}, fixture.client);
			assertTrue(state("playback") == null && state("cameraAnchor") == null, "playback stops on level identity change even with the same clock");
			assertTrue(anchor.isRemoved(), "level change discards the old camera anchor");
			assertTrue(fixture.client.camera == fixture.client.player, "level change restores the new level's player");
			assertTrue(fixture.client.options.getCameraType() == CameraType.THIRD_PERSON_BACK, "level change restores perspective");
			CameraDirectorClient.playFromGui("intro", true);
			fixture.client.level = null;
			fixture.client.player = null;
			invoke("tick", new Class<?>[] {Minecraft.class}, fixture.client);
			assertTrue(state("playback") == null && state("cameraAnchor") == null, "missing world cleans up playback");
			assertTrue(fixture.client.camera == null, "missing world cannot retain an old camera entity");
		}
		return 6;
	}

	private static int verifyPlaybackRespawn() throws Exception {
		try (Fixture fixture = new Fixture()) {
			fixture.paths.put("intro", savedPath("intro"));
			fixture.client.options.setCameraType(CameraType.THIRD_PERSON_BACK);
			CameraDirectorClient.playFromGui("intro", true);
			Marker anchor = (Marker) state("cameraAnchor");
			LocalPlayer replacement = allocate(LocalPlayer.class);
			setField(replacement, Entity.class, "level", fixture.level);
			fixture.client.player = replacement;
			fixture.client.setCameraEntity(replacement);
			invoke("tick", new Class<?>[] {Minecraft.class}, fixture.client);
			assertTrue(state("playback") == null && state("cameraAnchor") == null, "same-level respawn stops playback");
			assertTrue(anchor.isRemoved(), "same-level respawn discards the old camera anchor");
			assertTrue(fixture.client.camera == replacement, "same-level respawn cannot restore the old player camera");
			assertTrue(fixture.client.options.getCameraType() == CameraType.THIRD_PERSON_BACK, "same-level respawn restores perspective");
		}
		return 4;
	}

	private static int verifyWriterFailure() throws Exception {
		IOException failure = new IOException("simulated disk full");
		Writer writer = new Writer() {
			@Override public void write(char[] buffer, int offset, int length) throws IOException { throw failure; }
			@Override public void flush() { }
			@Override public void close() { }
		};
		try {
			invoke("writeJson", new Class<?>[] {JsonObject.class, Writer.class}, new JsonObject(), writer);
			throw new AssertionError("serialization must report the writer failure");
		} catch (IOException expected) {
			assertTrue(expected == failure, "serialization exposes checked IO failures to the save retry handlers");
		}
		return 1;
	}

	private static int verifyAnchorAndPerspective() throws Exception {
		try (Fixture fixture = new Fixture()) {
			fixture.paths.put("intro", savedPath("intro"));
			Marker original = new Marker(EntityType.MARKER, fixture.level);
			fixture.client.camera = original;
			fixture.client.options.setCameraType(CameraType.THIRD_PERSON_FRONT);
			assertEquals(1, fixture.command("play", "intro", true), "command playback starts");
			Marker anchor = (Marker) state("cameraAnchor");
			assertTrue(fixture.client.initialPoseReady, "new anchor has its current and previous pose before camera exposure");
			assertEquals(anchor.getX(), anchor.xo, "first frame cannot interpolate from the origin");
			assertTrue(fixture.client.options.getCameraType() == CameraType.FIRST_PERSON, "playback normalizes third-person perspective");
			fixture.client.options.setCameraType(CameraType.THIRD_PERSON_BACK);
			fixture.level.gameTime += 10;
			invoke("tick", new Class<?>[] {Minecraft.class}, fixture.client);
			assertTrue(fixture.client.options.getCameraType() == CameraType.FIRST_PERSON, "F5 during playback is normalized");
			assertEquals(100, anchor.xo, "later samples retain the previous sampled pose");
			CameraDirectorClient.stopPlaybackFromGui();
			assertTrue(fixture.client.camera == original, "manual stop restores the original valid camera");
			assertTrue(fixture.client.options.getCameraType() == CameraType.THIRD_PERSON_FRONT, "manual stop restores the original F5 perspective");
			CameraDirectorClient.playFromGui("intro", false);
			fixture.level.gameTime += 20;
			invoke("tick", new Class<?>[] {Minecraft.class}, fixture.client);
			assertTrue(state("playback") == null && fixture.client.camera == original, "natural completion restores camera ownership");
			assertTrue(fixture.client.options.getCameraType() == CameraType.THIRD_PERSON_FRONT, "natural completion restores perspective");
		}
		return 10;
	}

	private static int verifyRenderedEyeHeight() throws Exception {
		try (Fixture fixture = new Fixture()) {
			fixture.paths.put("intro", savedPath("intro"));
			setField(fixture.client.player, Entity.class, "eyeHeight", 1.62F);
			fixture.renderCamera.arenaagents$setEyeHeight(1.62F);
			fixture.renderCamera.arenaagents$setEyeHeightOld(1.62F);
			CameraDirectorClient.playFromGui("intro", false);
			Method align = Camera.class.getDeclaredMethod("alignWithEntity", float.class);
			align.setAccessible(true);
			for (float partialTick : new float[] {0.0F, 0.5F, 1.0F}) {
				align.invoke(fixture.renderCamera, partialTick);
				assertEquals(64, fixture.renderCamera.position().y, "rendered first pose must not inherit the player's eye height at partial tick " + partialTick);
			}
			CameraDirectorClient.stopPlaybackFromGui();
			for (String name : new String[] {"eyeHeight", "eyeHeightOld"}) {
				Field field = Camera.class.getDeclaredField(name);
				field.setAccessible(true);
				assertEquals(1.62F, field.getFloat(fixture.renderCamera), "restoring the player immediately restores " + name);
			}
		}
		return 5;
	}

	private static CameraPath savedPath(String name) {
		return new CameraPath(name, List.of(new CameraKeyframe(0, 100, 64, 200, 45, 10),
				new CameraKeyframe(20, 110, 65, 210, 90, 20)));
	}

	private static Object state(String name) throws ReflectiveOperationException {
		Field field = CameraDirectorClient.class.getDeclaredField(name);
		field.setAccessible(true);
		return field.get(null);
	}

	private static Object invoke(String name, Class<?>[] parameters, Object... arguments) throws Exception {
		Method method = CameraDirectorClient.class.getDeclaredMethod(name, parameters);
		method.setAccessible(true);
		try {
			return method.invoke(null, arguments);
		} catch (InvocationTargetException exception) {
			if (exception.getCause() instanceof Exception cause) throw cause;
			throw exception;
		}
	}

	private static void setField(Object target, Class<?> owner, String name, Object value) throws ReflectiveOperationException {
		Field field = owner.getDeclaredField(name);
		field.setAccessible(true);
		field.set(target, value);
	}

	private static <T> T allocate(Class<T> type) throws ReflectiveOperationException {
		Field field = sun.misc.Unsafe.class.getDeclaredField("theUnsafe");
		field.setAccessible(true);
		return type.cast(((sun.misc.Unsafe) field.get(null)).allocateInstance(type));
	}

	/** Constructor-free fixtures: no game loop, renderer, connection, chunks or world are started. */
	private static final class Fixture implements AutoCloseable {
		private final Path directory = Files.createTempDirectory("camera-verification-");
		private final Map<Field, Object> previousState = new LinkedHashMap<>();
		private final Map<String, CameraPath> paths;
		private final Map<String, CameraPath> previousPaths;
		private final Minecraft previousClient;
		private final HeadlessClient client;
		private final HeadlessGui gui;
		private final HeadlessCamera renderCamera;
		private HeadlessLevel level;

		@SuppressWarnings("unchecked")
		private Fixture() throws Exception {
			net.minecraft.SharedConstants.tryDetectVersion();
			net.minecraft.server.Bootstrap.bootStrap();
			previousClient = Minecraft.getInstance();
			paths = (Map<String, CameraPath>) state("PATHS");
			previousPaths = new LinkedHashMap<>(paths);
			for (Field field : CameraDirectorClient.class.getDeclaredFields()) {
				if (java.lang.reflect.Modifier.isStatic(field.getModifiers()) && !java.lang.reflect.Modifier.isFinal(field.getModifiers())) {
					field.setAccessible(true);
					previousState.put(field, field.get(null));
					if (!field.getType().isPrimitive()) field.set(null, null);
				}
			}
			paths.clear();
			client = allocate(HeadlessClient.class);
			gui = allocate(HeadlessGui.class);
			setField(client, Minecraft.class, "gameDirectory", directory.toFile());
			setField(client, Minecraft.class, "options", allocate(Options.class));
			setField(client, Minecraft.class, "gui", gui);
			setField(null, Minecraft.class, "instance", client);
			renderCamera = new HeadlessCamera();
			GameRenderer renderer = allocate(GameRenderer.class);
			setField(renderer, GameRenderer.class, "mainCamera", renderCamera);
			setField(client, Minecraft.class, "gameRenderer", renderer);
			client.options.setCameraType(CameraType.FIRST_PERSON);
			changeLevel();
			client.camera = client.player;
		}

		private void changeLevel() throws Exception {
			level = allocate(HeadlessLevel.class);
			level.gameTime = 100;
			client.level = level;
			client.player = allocate(LocalPlayer.class);
			setField(client.player, Entity.class, "level", level);
			setField(client.player, Entity.class, "position", new Vec3(100, 64, 200));
		}

		private Path storage() {
			return directory.resolve("config/arenaagents/camera-paths.json");
		}

		private int command(String name, Object... arguments) throws Exception {
			FabricClientCommandSource source = (FabricClientCommandSource) Proxy.newProxyInstance(
					FabricClientCommandSource.class.getClassLoader(), new Class<?>[] {FabricClientCommandSource.class},
					(proxy, method, values) -> method.getName().equals("getClient") ? client : null);
			List<Class<?>> types = new ArrayList<>(List.of(FabricClientCommandSource.class));
			List<Object> values = new ArrayList<>(List.of(source));
			for (Object argument : arguments) {
				types.add(argument instanceof Boolean ? boolean.class : String.class);
				values.add(argument);
			}
			return (int) invoke(name, types.toArray(Class<?>[]::new), values.toArray());
		}

		@Override
		public void close() throws Exception {
			for (var entry : previousState.entrySet()) entry.getKey().set(null, entry.getValue());
			paths.clear();
			paths.putAll(previousPaths);
			setField(null, Minecraft.class, "instance", previousClient);
			try (var files = Files.walk(directory)) {
				for (Path file : files.sorted(java.util.Comparator.reverseOrder()).toList()) Files.deleteIfExists(file);
			}
		}
	}

	private static final class HeadlessClient extends Minecraft {
		private Entity camera;
		private boolean initialPoseReady;
		private HeadlessClient() { super(null); }
		@Override public Entity getCameraEntity() { return camera; }
		@Override public void setCameraEntity(Entity entity) {
			camera = entity;
			gameRenderer.getMainCamera().setEntity(entity);
			if (entity instanceof Marker) {
				initialPoseReady = entity.getX() == 100 && entity.xo == entity.getX()
						&& entity.yo == entity.getY() && entity.zo == entity.getZ()
						&& entity.yRotO == entity.getYRot() && entity.xRotO == entity.getXRot();
			}
		}
	}

	/** The two accessors stand in for Fabric's mixin; positioning uses the real Minecraft Camera. */
	private static final class HeadlessCamera extends Camera implements CameraEyeHeightAccessor {
		@Override public void arenaagents$setEyeHeight(float height) { setHeight("eyeHeight", height); }
		@Override public void arenaagents$setEyeHeightOld(float height) { setHeight("eyeHeightOld", height); }
		private void setHeight(String name, float height) {
			try {
				setField(this, Camera.class, name, height);
			} catch (ReflectiveOperationException exception) {
				throw new AssertionError(exception);
			}
		}
	}

	private static final class HeadlessLevel extends ClientLevel {
		private long gameTime;
		private HeadlessLevel() { super(null, null, null, null, 0, 0, null, false, 0L, 0); }
		@Override public long getGameTime() { return gameTime; }
		@Override public FeatureFlagSet enabledFeatures() { return FeatureFlags.DEFAULT_FLAGS; }
		@Override public boolean addFreshEntity(Entity entity) { return true; }
	}

	private static final class HeadlessGui extends Gui {
		private String message = "";
		private HeadlessGui() { super(null); }
		@Override public void setOverlayMessage(Component message, boolean animateColor) { this.message = message.getString(); }
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

	private static void assertTrue(boolean condition, String label) {
		if (!condition) throw new AssertionError(label);
	}
}
