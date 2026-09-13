package dev.agaminggod.arenaagents.server.perception;

import com.google.gson.JsonObject;

public final class PlayerObservationEventsVerification {
	private PlayerObservationEventsVerification() { }

	public static int verify() {
		JsonObject sound = PlayerObservationEvents.soundEvent("minecraft:entity.zombie.ambient", 0, 0, 4, 0);
		check(sound.get("direction").getAsString().equals("front"), "sound bearing is relative to the listener");
		check(sound.get("range").getAsString().equals("near"), "sound range is approximate");
		check(!sound.has("x") && !sound.has("z") && !sound.has("entityId"), "sound exposes no hidden coordinate or entity ID");
		JsonObject left = PlayerObservationEvents.soundEvent("sound", 10, 8, 0, 0);
		check(left.get("direction").getAsString().equals("left") && left.get("elevation").getAsString().equals("above"), "bearing and elevation survive without vision");
		PlayerObservationEvents.Stream first = new PlayerObservationEvents.Stream();
		PlayerObservationEvents.Stream other = new PlayerObservationEvents.Stream();
		first.record(sound, 1, "minecraft:overworld");
		first.record(sound, 1, "minecraft:overworld");
		check(first.snapshot().get("latestSequence").getAsLong() == 1, "same-tick duplicate packets coalesce");
		sound.addProperty("soundId", "changed");
		check(first.snapshot().getAsJsonArray("events").get(0).getAsJsonObject().get("soundId").getAsString().equals("minecraft:entity.zombie.ambient"), "event queue owns its facts");
		check(other.snapshot().getAsJsonArray("events").isEmpty(), "another player's stream cannot see these events");
		for (int index = 2; index <= 180; index++) first.text("system_message", "message " + index, index, "minecraft:overworld");
		JsonObject page = first.page(0, 0, 32);
		check(page.getAsJsonObject("coverage").get("total").getAsInt() == 128, "retention is bounded");
		check(page.getAsJsonObject("coverage").get("gap").getAsBoolean(), "expired history is reported as a gap");
		check(page.getAsJsonObject("coverage").get("earliestSequence").getAsLong() == 53, "oldest retained sequence remains explicit");
		check(first.snapshot().getAsJsonArray("events").size() == 8, "heartbeat carries only a small recent window");
		other.boss("bar", "Visible bar", 0.5F, false, 1, "minecraft:overworld");
		other.boss("bar", null, 0.501F, false, 2, "minecraft:overworld");
		check(other.snapshot().get("latestSequence").getAsLong() == 1, "sub-percent boss changes do not flood attention");
		other.boss("bar", null, 0.4F, false, 3, "minecraft:overworld");
		check(other.snapshot().getAsJsonArray("bossBars").get(0).getAsJsonObject().get("name").getAsString().equals("Visible bar"), "partial bar updates preserve its observed name");
		other.boss("bar", null, null, true, 4, "minecraft:overworld");
		check(other.snapshot().getAsJsonArray("bossBars").isEmpty(), "remove packet clears the visible bar");
		JsonObject before = new JsonObject();
		JsonObject after = new JsonObject();
		before.add("perception", new PlayerObservationEvents.Stream().snapshot());
		after.add("perception", other.snapshot());
		check(AttentionSignalPolicy.changedFacts(before, after).contains("perception"), "new entitled events request model attention");
		return 15;
	}

	private static void check(boolean condition, String message) {
		if (!condition) throw new AssertionError(message);
	}
}
