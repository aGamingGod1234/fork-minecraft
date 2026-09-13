package dev.agaminggod.arenaagents.server.perception;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.WeakHashMap;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientboundBossEventPacket;
import net.minecraft.network.protocol.game.ClientboundSetActionBarTextPacket;
import net.minecraft.network.protocol.game.ClientboundSetSubtitleTextPacket;
import net.minecraft.network.protocol.game.ClientboundSetTitleTextPacket;
import net.minecraft.network.protocol.game.ClientboundSoundEntityPacket;
import net.minecraft.network.protocol.game.ClientboundSoundPacket;
import net.minecraft.network.protocol.game.ClientboundSystemChatPacket;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.BossEvent;

/** Observes only packets already addressed to this player, never global world events. */
public final class PlayerObservationEvents {
	private static final Map<ServerPlayer, Stream> PLAYERS = new WeakHashMap<>();
	private static final String[] DIRECTIONS = { "front", "front_right", "right", "back_right", "back", "back_left", "left", "front_left" };

	private PlayerObservationEvents() { }

	public static void capture(ServerPlayer player, Packet<?> packet) {
		if (!(packet instanceof ClientboundSoundPacket || packet instanceof ClientboundSoundEntityPacket
				|| packet instanceof ClientboundBossEventPacket || packet instanceof ClientboundSystemChatPacket
				|| packet instanceof ClientboundSetActionBarTextPacket || packet instanceof ClientboundSetTitleTextPacket
				|| packet instanceof ClientboundSetSubtitleTextPacket)) return;
		var server = player.level().getServer();
		if (!server.isSameThread()) { server.execute(() -> capture(player, packet)); return; }
		Stream stream = stream(player);
		long tick = player.level().getGameTime();
		String dimension = player.level().dimension().identifier().toString();
		if (packet instanceof ClientboundSoundPacket sound) {
			sound(player, stream, sound.getSound().value().location().toString(), sound.getX(), sound.getY(), sound.getZ(),
					sound.getSound().value().getRange(sound.getVolume()), sound.getVolume(), tick, dimension);
		} else if (packet instanceof ClientboundSoundEntityPacket sound) {
			var entity = player.level().getEntity(sound.getId());
			if (entity != null) sound(player, stream, sound.getSound().value().location().toString(), entity.getX(), entity.getY(), entity.getZ(),
					sound.getSound().value().getRange(sound.getVolume()), sound.getVolume(), tick, dimension);
		} else if (packet instanceof ClientboundBossEventPacket boss) {
			boss.dispatch(new ClientboundBossEventPacket.Handler() {
				@Override public void add(UUID id, Component name, float progress, BossEvent.BossBarColor color,
						BossEvent.BossBarOverlay overlay, boolean darken, boolean music, boolean fog) {
					stream.boss(id.toString(), ObservationDetails.bounded(name.getString(), 256), progress, false, tick, dimension);
				}
				@Override public void remove(UUID id) { stream.boss(id.toString(), null, null, true, tick, dimension); }
				@Override public void updateProgress(UUID id, float progress) { stream.boss(id.toString(), null, progress, false, tick, dimension); }
				@Override public void updateName(UUID id, Component name) { stream.boss(id.toString(), ObservationDetails.bounded(name.getString(), 256), null, false, tick, dimension); }
			});
		} else if (packet instanceof ClientboundSystemChatPacket message) {
			stream.text(message.overlay() ? "action_bar" : "system_message", message.content().getString(), tick, dimension);
		} else if (packet instanceof ClientboundSetActionBarTextPacket message) {
			stream.text("action_bar", message.text().getString(), tick, dimension);
		} else if (packet instanceof ClientboundSetTitleTextPacket message) {
			stream.text("title", message.text().getString(), tick, dimension);
		} else if (packet instanceof ClientboundSetSubtitleTextPacket message) {
			stream.text("subtitle", message.text().getString(), tick, dimension);
		}
	}

	private static void sound(ServerPlayer player, Stream stream, String id, double x, double y, double z,
			double range, float volume, long tick, String dimension) {
		double dx = x - player.getX();
		double dy = y - player.getEyeY();
		double dz = z - player.getZ();
		double distance = Math.sqrt(dx * dx + dy * dy + dz * dz);
		if (!(volume > 0) || distance > range) return;
		JsonObject event = soundEvent(id, dx, dy, dz, player.getYRot());
		stream.record(event, tick, dimension);
	}

	static JsonObject soundEvent(String id, double dx, double dy, double dz, float yaw) {
		JsonObject event = new JsonObject();
		event.addProperty("type", "sound");
		event.addProperty("soundId", ObservationDetails.bounded(id, 256));
		double bearing = Math.toDegrees(Math.atan2(-dx, dz)) - yaw;
		int sector = Math.floorMod((int) Math.round(bearing / 45.0D), DIRECTIONS.length);
		event.addProperty("direction", DIRECTIONS[sector]);
		double distance = Math.sqrt(dx * dx + dy * dy + dz * dz);
		event.addProperty("range", distance < 8 ? "near" : distance < 24 ? "medium" : "far");
		event.addProperty("elevation", dy > 3 ? "above" : dy < -3 ? "below" : "level");
		return event;
	}

	public static JsonObject snapshot(ServerPlayer player) { return stream(player).snapshot(); }
	public static JsonObject page(ServerPlayer player, long afterSequence, int offset, int limit) { return stream(player).page(afterSequence, offset, limit); }
	public static long sequence(ServerPlayer player) {
		synchronized (PLAYERS) {
			Stream current = PLAYERS.get(player);
			return current == null ? 0 : current.sequence;
		}
	}

	private static Stream stream(ServerPlayer player) {
		synchronized (PLAYERS) { return PLAYERS.computeIfAbsent(player, ignored -> new Stream()); }
	}

	static final class Stream {
		static final int MAX_EVENTS = 128;
		private final ArrayDeque<JsonObject> events = new ArrayDeque<>();
		private final LinkedHashMap<String, JsonObject> bars = new LinkedHashMap<>();
		private long sequence;
		private String lastSignature;
		private long lastTick = -1;

		void record(JsonObject event, long tick, String dimension) {
			String signature = event.toString() + dimension;
			if (tick == lastTick && signature.equals(lastSignature)) return;
			lastTick = tick;
			lastSignature = signature;
			JsonObject owned = event.deepCopy();
			owned.addProperty("sequence", ++sequence);
			owned.addProperty("gameTime", tick);
			owned.addProperty("dimension", dimension);
			owned.addProperty("observedAtEpochMs", System.currentTimeMillis());
			events.addLast(owned);
			while (events.size() > MAX_EVENTS) events.removeFirst();
		}

		void text(String kind, String value, long tick, String dimension) {
			JsonObject event = new JsonObject();
			event.addProperty("type", kind);
			event.addProperty("text", ObservationDetails.bounded(value, 512));
			event.addProperty("textTruncated", value.codePointCount(0, value.length()) > 512);
			record(event, tick, dimension);
		}

		void boss(String id, String name, Float progress, boolean remove, long tick, String dimension) {
			if (remove) {
				if (bars.remove(id) == null) return;
				JsonObject event = new JsonObject();
				event.addProperty("type", "boss_bar_removed");
				event.addProperty("barId", id);
				record(event, tick, dimension);
				return;
			}
			JsonObject before = bars.get(id);
			JsonObject bar = before == null ? new JsonObject() : before.deepCopy();
			bar.addProperty("barId", id);
			if (name != null) bar.addProperty("name", name);
			if (progress != null) bar.addProperty("progress", Math.round(Math.max(0, Math.min(1, progress)) * 100) / 100.0);
			if (bar.equals(before)) return;
			bars.put(id, bar);
			while (bars.size() > 16) bars.remove(bars.keySet().iterator().next());
			JsonObject event = bar.deepCopy();
			event.addProperty("type", "boss_bar");
			record(event, tick, dimension);
		}

		JsonObject snapshot() {
			JsonObject value = new JsonObject();
			value.addProperty("latestSequence", sequence);
			JsonArray recent = new JsonArray();
			List<JsonObject> retained = new ArrayList<>(events);
			for (int index = Math.max(0, retained.size() - 8); index < retained.size(); index++) {
				JsonObject event = retained.get(index).deepCopy();
				if (event.has("text") && event.get("text").getAsString().length() > 128) {
					event.addProperty("text", ObservationDetails.bounded(event.get("text").getAsString(), 128));
					event.addProperty("textTruncated", true);
				}
				recent.add(event);
			}
			value.add("events", recent);
			JsonArray bossBars = new JsonArray();
			bars.values().forEach(bar -> {
				JsonObject compact = bar.deepCopy();
				if (compact.has("name") && compact.get("name").getAsString().length() > 96) {
					compact.addProperty("name", ObservationDetails.bounded(compact.get("name").getAsString(), 96));
					compact.addProperty("nameTruncated", true);
				}
				bossBars.add(compact);
			});
			value.add("bossBars", bossBars);
			value.addProperty("earliestSequence", events.isEmpty() ? sequence + 1 : events.getFirst().get("sequence").getAsLong());
			return value;
		}

		JsonObject page(long afterSequence, int offset, int limit) {
			List<JsonObject> retained = events.stream().filter(event -> event.get("sequence").getAsLong() > afterSequence).toList();
			JsonObject value = ObservationPage.collect(retained.size(), offset, limit, index -> retained.get(index).deepCopy(), "packets_addressed_to_this_player");
			JsonObject coverage = value.getAsJsonObject("coverage");
			long earliest = events.isEmpty() ? sequence + 1 : events.getFirst().get("sequence").getAsLong();
			coverage.addProperty("earliestSequence", earliest);
			coverage.addProperty("latestSequence", sequence);
			coverage.addProperty("gap", afterSequence >= 0 && afterSequence < earliest - 1);
			return value;
		}
	}
}
