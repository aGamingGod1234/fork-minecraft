package dev.agaminggod.arenaagents.client.camera;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonParseException;
import com.google.gson.JsonIOException;
import com.mojang.brigadier.arguments.BoolArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import dev.agaminggod.arenaagents.client.mixin.CameraEyeHeightAccessor;
import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.command.v2.ClientCommands;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.minecraft.client.CameraType;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.Marker;
import net.minecraft.world.entity.player.ChatVisiblity;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Local cinematic camera director. It never changes server-side agent state. */
public final class CameraDirectorClient {
	private static final Logger LOGGER = LoggerFactory.getLogger(CameraDirectorClient.class);
	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
	private static final int MAX_PATHS = 64;
	private static final int MIN_PLAYBACK_TICKS = 1;
	private static final Map<String, CameraPath> PATHS = new LinkedHashMap<>();
	private static Recording recording;
	private static Playback playback;
	private static Marker cameraAnchor;
	private static Entity previousCamera;
	private static CameraType previousCameraType;
	private static Boolean previousHideGui;
	private static ChatVisiblity previousChatVisibility;
	private static boolean registered;

	public static List<String> presetNames() { return List.copyOf(PATHS.keySet()); }
	/** Render guards use playback state, never the user's identity or saved preferences. */
	public static boolean cleanPlaybackActive() { return playback != null; }

	private CameraDirectorClient() {
	}

	public static synchronized void register() {
		if (registered) return;
		load(Minecraft.getInstance());
		ClientCommandRegistrationCallback.EVENT.register((dispatcher, ignored) -> dispatcher.register(commands()));
		ClientTickEvents.END_CLIENT_TICK.register(CameraDirectorClient::tick);
		ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> {
			stopPlayback(client);
			recording = null;
		});
		registered = true;
	}

	/** GUI-safe camera controls used by the in-game Skit Director screen. */
	public static void startRecordingFromGui(String name, boolean replace) {
		Minecraft client = Minecraft.getInstance();
		try {
			name = beginRecording(client, name, replace);
			guiFeedback("Recording camera path '" + name + "'. Capture keyframes as you move, then save recording.", false);
		} catch (IllegalArgumentException exception) {
			guiFeedback(exception.getMessage(), true);
		}
	}

	public static void recordKeyframeFromGui() {
		Minecraft client = Minecraft.getInstance();
		if (recording == null) { guiFeedback("No camera path is recording.", true); return; }
		if (!recordingInCurrentLevel(client)) {
			recording = null;
			guiFeedback("Recording cancelled because you left its world.", true);
			return;
		}
		long elapsed = Math.max(0L, client.level.getGameTime() - recording.startedAt());
		if (elapsed > CameraPath.MAX_DURATION_TICKS) { guiFeedback("This camera path has reached its one-hour limit.", true); return; }
		if (elapsed < recording.frames().getLast().tick()) { guiFeedback("The world clock moved backwards. Wait before capturing another keyframe.", true); return; }
		CameraKeyframe frame = new CameraKeyframe((int) elapsed, client.player.getX(), client.player.getEyeY(), client.player.getZ(), client.player.getYRot(), client.player.getXRot());
		if (recording.frames().size() >= CameraPath.MAX_KEYFRAMES && recording.frames().stream().noneMatch(existing -> existing.tick() == frame.tick())) {
			guiFeedback("This camera path has reached its keyframe limit.", true);
			return;
		}
		if (!recording.frames().isEmpty() && recording.frames().getLast().tick() == frame.tick()) recording.frames().set(recording.frames().size() - 1, frame);
		else recording.frames().add(frame);
		guiFeedback("Captured keyframe at " + frame.tick() + " ticks.", false);
	}

	public static void stopRecordingFromGui() {
		Minecraft client = Minecraft.getInstance();
		if (recording == null) { guiFeedback("No camera path is recording.", true); return; }
		try {
			CameraPath saved = finishRecording(client);
			guiFeedback("Saved camera path '" + saved.name() + "' (" + saved.keyframes().size() + " keyframes).", false);
		} catch (IllegalArgumentException exception) {
			guiFeedback(exception.getMessage(), true);
		} catch (IOException exception) {
			guiFeedback("Could not save camera path. Recording kept; try saving again.", true);
		}
	}

	public static void playFromGui(String name, boolean loop) {
		CameraPath path = PATHS.get(name == null ? "" : name.strip());
		Minecraft client = Minecraft.getInstance();
		if (path == null) { guiFeedback("No camera path named '" + name + "'.", true); return; }
		if (client.level == null || client.player == null) { guiFeedback("You must be in a world to play a camera path.", true); return; }
		if (path.durationTicks() < MIN_PLAYBACK_TICKS) { guiFeedback("Add a second keyframe so the camera has a duration to play.", true); return; }
		stopPlayback(client);
		previousCamera = client.getCameraEntity();
		previousCameraType = client.options.getCameraType();
		captureAndHidePresentation(client);
		playback = new Playback(new CameraReel(List.of(path)), client.level, client.player, new PresentationClock(System.nanoTime(), client.isPaused()), loop);
		apply(client, path.sample(0.0D));
	}

	public static void stopPlaybackFromGui() {
		if (playback == null) { guiFeedback("No camera path is playing.", true); return; }
		stopPlayback(Minecraft.getInstance());
		guiFeedback("Camera path stopped; camera returned to the player.", false);
	}

	private static void guiFeedback(String message, boolean error) {
		if(cleanPlaybackActive()) return;
		Minecraft client = Minecraft.getInstance();
		if (client.gui != null) client.gui.setOverlayMessage(Component.literal(message), true);
	}

	private static com.mojang.brigadier.builder.LiteralArgumentBuilder<FabricClientCommandSource> commands() {
		var path = ClientCommands.literal("path");
		path.then(ClientCommands.literal("start")
				.then(com.mojang.brigadier.builder.RequiredArgumentBuilder.<FabricClientCommandSource, String>argument("name", StringArgumentType.word())
						.executes(context -> startRecording(context.getSource(), StringArgumentType.getString(context, "name")))));
		var keyframe = ClientCommands.literal("keyframe").executes(context -> recordKeyframe(context.getSource()));
		path.then(keyframe);
		path.then(ClientCommands.literal("frame").executes(context -> recordKeyframe(context.getSource())));
		path.then(ClientCommands.literal("stop").executes(context -> stopRecording(context.getSource())));
		var play = ClientCommands.literal("play").then(
				com.mojang.brigadier.builder.RequiredArgumentBuilder.<FabricClientCommandSource, String>argument("name", StringArgumentType.word())
						.suggests((context, builder) -> SharedSuggestionProvider.suggest(PATHS.keySet(), builder))
						.executes(context -> play(context.getSource(), StringArgumentType.getString(context, "name"), false))
						.then(ClientCommands.argument("loop", BoolArgumentType.bool())
								.executes(context -> play(context.getSource(), StringArgumentType.getString(context, "name"), BoolArgumentType.getBool(context, "loop")))));
		path.then(play);
		path.then(ClientCommands.literal("stop-playback").executes(context -> stopPlaybackCommand(context.getSource())));
		path.then(ClientCommands.literal("list").executes(context -> list(context.getSource())));
		path.then(ClientCommands.literal("info")
				.then(ClientCommands.argument("name", StringArgumentType.word())
						.suggests((context, builder) -> SharedSuggestionProvider.suggest(PATHS.keySet(), builder))
						.executes(context -> info(context.getSource(), StringArgumentType.getString(context, "name")))));
		path.then(ClientCommands.literal("delete")
				.then(ClientCommands.argument("name", StringArgumentType.word())
						.suggests((context, builder) -> SharedSuggestionProvider.suggest(PATHS.keySet(), builder))
						.executes(context -> delete(context.getSource(), StringArgumentType.getString(context, "name")))));
		path.then(ClientCommands.literal("clear").executes(context -> clear(context.getSource())));
		var reel = ClientCommands.literal("reel");
        reel.then(ClientCommands.literal("play").then(ClientCommands.literal("fork_showcase").executes(context -> playShowcase(context.getSource()))));
        reel.then(ClientCommands.literal("stop").executes(context -> stopPlaybackCommand(context.getSource())));
        return ClientCommands.literal("camera").then(path).then(reel);
	}

	private static int startRecording(FabricClientCommandSource source, String name) {
		try {
			name = beginRecording(source.getClient(), name, false);
			source.sendFeedback(Component.literal("Recording camera path '" + name + "'. Move the player/camera, then use /camera path keyframe. Stop saves it."));
			return 1;
		} catch (IllegalArgumentException exception) {
			return error(source, exception.getMessage());
		}
	}

	private static int recordKeyframe(FabricClientCommandSource source) {
		if (recording == null) return error(source, "No camera path is recording. Start one with /camera path start <name>.");
		Minecraft client = source.getClient();
		if (!recordingInCurrentLevel(client)) {
			recording = null;
			return error(source, "Recording cancelled because you left its world.");
		}
		long elapsed = Math.max(0L, client.level.getGameTime() - recording.startedAt());
		if (elapsed > CameraPath.MAX_DURATION_TICKS) return error(source, "This camera path has reached its one-hour limit.");
		if (elapsed < recording.frames().getLast().tick()) return error(source, "The world clock moved backwards. Wait before capturing another keyframe.");
		CameraKeyframe frame = new CameraKeyframe((int) elapsed, client.player.getX(), client.player.getEyeY(), client.player.getZ(), client.player.getYRot(), client.player.getXRot());
		if (recording.frames().size() >= CameraPath.MAX_KEYFRAMES && recording.frames().stream().noneMatch(existing -> existing.tick() == frame.tick())) {
			return error(source, "This camera path has reached its keyframe limit (" + CameraPath.MAX_KEYFRAMES + ").");
		}
		if (!recording.frames().isEmpty() && recording.frames().getLast().tick() == frame.tick()) recording.frames().set(recording.frames().size() - 1, frame);
		else recording.frames().add(frame);
		source.sendFeedback(Component.literal("Captured camera keyframe at " + frame.tick() + " ticks."));
		return 1;
	}

	private static int stopRecording(FabricClientCommandSource source) {
		if (recording == null) return error(source, "No camera path is recording.");
		try {
			CameraPath saved = finishRecording(source.getClient());
			source.sendFeedback(Component.literal("Saved camera path '" + saved.name() + "' (" + saved.keyframes().size() + " keyframes, " + saved.durationTicks() + " ticks)."));
			return 1;
		} catch (IllegalArgumentException exception) {
			return error(source, exception.getMessage());
		} catch (IOException exception) {
			return error(source, "Could not save camera path. Recording kept; try saving again.");
		}
	}

	private static String beginRecording(Minecraft client, String name, boolean replace) {
		name = validateName(name);
		if (recording != null) throw new IllegalArgumentException("A camera path is already recording. Save it before starting another.");
		boolean exists = PATHS.containsKey(name);
		if (exists && !replace) throw new IllegalArgumentException("A camera path named " + name + " already exists.");
		if (!exists && PATHS.size() >= MAX_PATHS) throw new IllegalArgumentException("Camera path limit reached (" + MAX_PATHS + ").");
		if (client.level == null || client.player == null) throw new IllegalArgumentException("You must be in a world to record a camera path.");
		CameraKeyframe first = new CameraKeyframe(0, client.player.getX(), client.player.getEyeY(), client.player.getZ(), client.player.getYRot(), client.player.getXRot());
		stopPlayback(client);
		recording = new Recording(name, client.level, client.level.getGameTime(), new ArrayList<>(List.of(first)));
		return name;
	}

	private static boolean recordingInCurrentLevel(Minecraft client) {
		return recording != null && client.level != null && client.player != null && recording.level() == client.level;
	}

	private static CameraPath finishRecording(Minecraft client) throws IOException {
		if (!recordingInCurrentLevel(client)) {
			recording = null;
			throw new IllegalArgumentException("Recording cancelled because you left its world.");
		}
		CameraPath saved = new CameraPath(recording.name(), recording.frames());
		Map<String, CameraPath> next = new LinkedHashMap<>(PATHS);
		next.put(saved.name(), saved);
		save(client, next);
		recording = null;
		return saved;
	}

	private static int play(FabricClientCommandSource source, String name, boolean loop) {
		CameraPath path = PATHS.get(name);
		if (path == null) return error(source, "No camera path named '" + name + "'. Use /camera path list.");
		Minecraft client = source.getClient();
		if (client.level == null || client.player == null) return error(source, "You must be in a world to play a camera path.");
		if (path.durationTicks() < MIN_PLAYBACK_TICKS) return error(source, "Add a second keyframe so the camera has a duration to play.");
		stopPlayback(client);
		previousCamera = client.getCameraEntity();
		previousCameraType = client.options.getCameraType();
		captureAndHidePresentation(client);
		playback = new Playback(new CameraReel(List.of(path)), client.level, client.player, new PresentationClock(System.nanoTime(), client.isPaused()), loop);
		apply(client, path.sample(0.0D));
		return 1;
	}

	    public static int playShowcase(FabricClientCommandSource source) {
        Minecraft client = source.getClient();
        if (client.level == null || client.player == null) return error(source, "You must be in a world to play the showcase.");
        var paths = new ArrayList<CameraPath>();
        for (String name : List.of("fork_intro", "fork_city_flythrough", "fork_clinic", "fork_workshop", "fork_courier", "fork_overview")) {
            CameraPath path = PATHS.get(name);
            if (path == null || path.durationTicks() < MIN_PLAYBACK_TICKS)
                return error(source, "Showcase needs a valid camera preset: " + name);
            paths.add(path);
        }
        CameraReel reel = new CameraReel(paths); // Validate every segment before disturbing the current camera.
        stopPlayback(client);
        previousCamera = client.getCameraEntity();
        previousCameraType = client.options.getCameraType();
        captureAndHidePresentation(client);
        playback = new Playback(reel, client.level, client.player, new PresentationClock(System.nanoTime(), client.isPaused()), false);
        apply(client, reel.sample(0));
        return 1;
    }
    public static PresentationClock playTimedTake(List<String> names,List<Integer> durations) {
        var client=Minecraft.getInstance();
        if(client.level==null||client.player==null)throw new IllegalStateException("Join the Singapore world first");
        var paths=names.stream().map(n->{var p=PATHS.get(n);if(p==null)throw new IllegalArgumentException("Missing camera preset: "+n);return p;}).toList();
        var reel=new CameraReel(paths,durations);
        stopPlayback(client);previousCamera=client.getCameraEntity();previousCameraType=client.options.getCameraType();captureAndHidePresentation(client);
        var clock=new PresentationClock(System.nanoTime(),client.isPaused());
        playback=new Playback(reel,client.level,client.player,clock,false);apply(client,reel.sample(0));return clock;
    }
    /** Hard cuts between immutable authored paths, with one elapsed clock for the whole reel. */
    public static final class CameraReel {
        private final List<CameraPath> paths;
        private final int durationTicks;
        private final List<Integer> durations;
        public CameraReel(List<CameraPath> paths) { this(paths, paths.stream().map(CameraPath::durationTicks).toList()); }
        public CameraReel(List<CameraPath> paths,List<Integer> durations) {
            this.durations=List.copyOf(durations);
            if(paths.size()!=durations.size()||durations.stream().anyMatch(d->d<1))throw new IllegalArgumentException("Invalid timed reel");
            this.paths = List.copyOf(paths);
            if (this.paths.isEmpty()) throw new IllegalArgumentException("A reel needs camera paths");
            int duration = 0;
            for (int i=0;i<this.paths.size();i++) {
                CameraPath path=this.paths.get(i);
                if (path.durationTicks() < MIN_PLAYBACK_TICKS) throw new IllegalArgumentException("Every reel path needs a positive duration");
                duration = Math.addExact(duration, this.durations.get(i));
            }
            durationTicks = duration;
        }
        public int durationTicks() { return durationTicks; }
        public boolean complete(double elapsed) { return elapsed >= durationTicks; }
        public CameraPose sample(double elapsed) {
            double local = Math.max(0, elapsed);
            for (int i = 0; i < paths.size(); i++) {
                CameraPath path = paths.get(i);
                int slot = durations.get(i);
                if (local < slot || i == paths.size() - 1) return path.sample(Math.min(local,slot)*path.durationTicks()/slot);
                local -= slot;
            }
            throw new IllegalStateException("Empty camera reel");
        }
    }    private static int stopPlaybackCommand(FabricClientCommandSource source) {
		if (playback == null) return error(source, "No camera path is playing.");
		stopPlayback(source.getClient());
		source.sendFeedback(Component.literal("Camera path stopped; camera returned to the player."));
		return 1;
	}

	private static int list(FabricClientCommandSource source) {
		if (PATHS.isEmpty()) return error(source, "No saved camera paths. Start with /camera path start <name>.");
		PATHS.values().forEach(path -> source.sendFeedback(Component.literal(path.name() + " - " + path.keyframes().size() + " keyframes, " + path.durationTicks() + " ticks (" + (path.durationTicks() / 20.0D) + "s)")));
		return PATHS.size();
	}

	private static int info(FabricClientCommandSource source, String name) {
		CameraPath path = PATHS.get(name);
		if (path == null) return error(source, "No camera path named '" + name + "'.");
		source.sendFeedback(Component.literal("Camera path '" + name + "': " + path.keyframes().size() + " keyframes, " + path.durationTicks() + " ticks. Playback uses smooth position interpolation and shortest-turn rotation."));
		return 1;
	}

	private static int delete(FabricClientCommandSource source, String name) {
		Map<String, CameraPath> next = new LinkedHashMap<>(PATHS);
		if (next.remove(name) == null) return error(source, "No camera path named '" + name + "'.");
		try {
			save(source.getClient(), next);
			source.sendFeedback(Component.literal("Deleted camera path '" + name + "'."));
			return 1;
		} catch (IOException exception) {
			return error(source, "Could not delete camera path. The saved library was kept.");
		}
	}

	private static int clear(FabricClientCommandSource source) {
		if (PATHS.isEmpty()) return error(source, "There are no saved camera paths.");
		int count = PATHS.size();
		try {
			save(source.getClient(), Map.of());
			source.sendFeedback(Component.literal("Deleted " + count + " camera paths."));
			return count;
		} catch (IOException exception) {
			return error(source, "Could not clear camera paths. The saved library was kept.");
		}
	}

	private static void tick(Minecraft client) {
		if (recording != null && !recordingInCurrentLevel(client)) recording = null;
		validatePlayback(client);
	}

	private static boolean validatePlayback(Minecraft client) {
		if (playback == null) return false;
		if (client.level == null || client.player == null || playback.level() != client.level || playback.player() != client.player) {
			stopPlayback(client);
			return false;
		}
		client.options.hideGui = true;
		client.options.chatVisibility().set(ChatVisiblity.HIDDEN);
		return true;
	}

	/** Called before Camera.update aligns the view and builds its culling matrices. */
	public static void updatePresentationFrame() {
		Minecraft client = Minecraft.getInstance();
		if (!validatePlayback(client)) return;
		double elapsed = playback.clock().advance(System.nanoTime(), client.isPaused());
		int duration = playback.reel().durationTicks();
		if (elapsed >= duration) {
			if (!playback.loop()) {
				stopPlayback(client);
				return;
			}
			elapsed %= duration;
		}
		apply(client, playback.reel().sample(elapsed));
	}

	/** Monotonic presentation time in authored 20 Hz ticks; independent of simulation/daylight. */
	public static final class PresentationClock {
		private long previousNanos;
		private long elapsedNanos;
		private boolean previouslyPaused;
		public PresentationClock(long now, boolean paused) { previousNanos = now; previouslyPaused = paused; }
		public double advance(long now, boolean paused) {
			long delta = now - previousNanos;
			if (!paused && !previouslyPaused && delta > 0) elapsedNanos += delta;
			previousNanos = now;
			previouslyPaused = paused;
			return elapsedNanos / 50_000_000.0D;
		}
	}

	private static void apply(Minecraft client, CameraPose pose) {
		if (client.level == null) return;
		boolean created = cameraAnchor == null || cameraAnchor.level() != client.level || cameraAnchor.isRemoved();
		if (created) {
			if (cameraAnchor != null) cameraAnchor.remove(Entity.RemovalReason.DISCARDED);
			cameraAnchor = EntityType.MARKER.create(client.level, EntitySpawnReason.COMMAND);
			if (cameraAnchor == null) return;
		}
		cameraAnchor.setPos(pose.x(), pose.y(), pose.z());
		cameraAnchor.setYRot(pose.yaw());
		cameraAnchor.setXRot(pose.pitch());
		// The pose is already sampled for this frame: suppress a second entity interpolation.
		cameraAnchor.setOldPosAndRot();
		client.options.setCameraType(CameraType.FIRST_PERSON);
		if (created) {
			client.level.addFreshEntity(cameraAnchor);
			setCamera(client, cameraAnchor);
		}
	}

	private static void setCamera(Minecraft client, Entity entity) {
		client.setCameraEntity(entity);
		CameraEyeHeightAccessor camera = (CameraEyeHeightAccessor) client.gameRenderer.getMainCamera();
		float height = entity == null ? 0.0F : entity.getEyeHeight();
		camera.arenaagents$setEyeHeight(height);
		camera.arenaagents$setEyeHeightOld(height);
	}

	private static void stopPlayback(Minecraft client) {
		if (playback == null && cameraAnchor == null && previousCamera == null && previousCameraType == null && previousHideGui == null) return;
		playback = null;
		// Restore before touching the camera entity, including disconnect/world-loss paths.
		if(previousHideGui != null) { client.options.hideGui=previousHideGui; previousHideGui=null; }
		if(previousChatVisibility != null) { client.options.chatVisibility().set(previousChatVisibility); previousChatVisibility=null; }
		if (cameraAnchor != null) {
			cameraAnchor.remove(Entity.RemovalReason.DISCARDED);
			cameraAnchor = null;
		}
		Entity restore = previousCamera;
		previousCamera = null;
		if (restore == null || restore.isRemoved() || restore.level() != client.level
				|| (restore instanceof LocalPlayer && restore != client.player)) restore = client.player;
		if (client.level == null || (restore != null && (restore.isRemoved() || restore.level() != client.level))) restore = null;
		setCamera(client, restore);
		if (previousCameraType != null) {
			client.options.setCameraType(previousCameraType);
			previousCameraType = null;
		}
	}

	private static void captureAndHidePresentation(Minecraft client) {
		previousHideGui=client.options.hideGui;
		previousChatVisibility=client.options.chatVisibility().get();
		client.options.hideGui=true;
		client.options.chatVisibility().set(ChatVisiblity.HIDDEN);
	}

	private static void load(Minecraft client) {
		PATHS.clear();
		Path file = storageFile(client);
		if (!Files.isRegularFile(file)) return;
		try (Reader reader = Files.newBufferedReader(file)) {
			JsonObject root = JsonParser.parseReader(reader).getAsJsonObject();
			JsonArray paths = root.getAsJsonArray("paths");
			if (paths == null) return;
			for (JsonElement pathElement : paths) {
				if (PATHS.size() >= MAX_PATHS) break;
				JsonObject pathObject = pathElement.getAsJsonObject();
				ArrayList<CameraKeyframe> frames = new ArrayList<>();
				for (JsonElement frameElement : pathObject.getAsJsonArray("keyframes")) {
					JsonObject frame = frameElement.getAsJsonObject();
					frames.add(new CameraKeyframe(frame.get("tick").getAsInt(), frame.get("x").getAsDouble(), frame.get("y").getAsDouble(), frame.get("z").getAsDouble(), frame.get("yaw").getAsFloat(), frame.get("pitch").getAsFloat()));
				}
				CameraPath path = new CameraPath(pathObject.get("name").getAsString(), frames);
				PATHS.put(path.name(), path);
			}
		} catch (IOException | JsonParseException | IllegalArgumentException | NullPointerException
				| IllegalStateException | ClassCastException | UnsupportedOperationException exception) {
			LOGGER.warn("Could not load Arena Agents camera paths; starting with an empty library", exception);
			PATHS.clear();
		}
	}

	private static void save(Minecraft client, Map<String, CameraPath> library) throws IOException {
		Path file = storageFile(client);
		Path temporary = file.resolveSibling(file.getFileName() + ".tmp");
		try {
			Files.createDirectories(file.getParent());
			JsonObject root = new JsonObject();
			JsonArray paths = new JsonArray();
			for (CameraPath path : library.values()) {
				JsonObject pathObject = new JsonObject();
				pathObject.addProperty("name", path.name());
				JsonArray frames = new JsonArray();
				for (CameraKeyframe frame : path.keyframes()) {
					JsonObject frameObject = new JsonObject();
					frameObject.addProperty("tick", frame.tick());
					frameObject.addProperty("x", frame.x());
					frameObject.addProperty("y", frame.y());
					frameObject.addProperty("z", frame.z());
					frameObject.addProperty("yaw", frame.yaw());
					frameObject.addProperty("pitch", frame.pitch());
					frames.add(frameObject);
				}
				pathObject.add("keyframes", frames);
				paths.add(pathObject);
			}
			root.add("paths", paths);
			try (Writer writer = Files.newBufferedWriter(temporary)) {
				writeJson(root, writer);
			}
			try {
				Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
			} catch (AtomicMoveNotSupportedException exception) {
				Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING);
			}
			PATHS.clear();
			PATHS.putAll(library);
		} catch (IOException exception) {
			LOGGER.warn("Could not save Arena Agents camera paths", exception);
			throw exception;
		}
	}

	private static void writeJson(JsonObject root, Writer writer) throws IOException {
		try {
			GSON.toJson(root, writer);
		} catch (JsonIOException exception) {
			if (exception.getCause() instanceof IOException cause) throw cause;
			throw exception;
		}
	}

	private static Path storageFile(Minecraft client) {
		return client.gameDirectory.toPath().resolve("config").resolve("arenaagents").resolve("camera-paths.json");
	}

	private static String validateName(String name) {
		if (name == null) throw new IllegalArgumentException("A camera path needs a name.");
		return new CameraPath(name, List.of(new CameraKeyframe(0, 0.0D, 0.0D, 0.0D, 0.0F, 0.0F))).name();
	}

	private static int error(FabricClientCommandSource source, String message) {
		source.sendError(Component.literal(message == null || message.isBlank() ? "Camera command failed." : message));
		return 0;
	}

	private record Recording(String name, ClientLevel level, long startedAt, ArrayList<CameraKeyframe> frames) {
	}

	private record Playback(CameraReel reel, ClientLevel level, LocalPlayer player, PresentationClock clock, boolean loop) {
	}
}
