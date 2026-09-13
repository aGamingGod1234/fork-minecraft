package dev.agaminggod.arenaagents.scenario.runtime;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import java.util.Objects;
import java.util.Optional;
import java.util.List;
import dev.agaminggod.arenaagents.server.ChunkedSavedPayload;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.datafix.DataFixTypes;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.world.level.saveddata.SavedDataType;

public final class ScenarioSavedData extends SavedData {
	private static final String PAYLOAD_FIELD = "payload";
	private static final String PAYLOAD_CHUNKS_FIELD = "payload_chunks";
	private static final Codec<ScenarioSavedData> CODEC = RecordCodecBuilder.create(instance -> instance.group(
			ChunkedSavedPayload.legacyCodec().optionalFieldOf(PAYLOAD_FIELD, "").forGetter(data -> ""),
			ChunkedSavedPayload.chunksCodec().optionalFieldOf(PAYLOAD_CHUNKS_FIELD, List.of()).forGetter(data -> ChunkedSavedPayload.split(data.encodePayload()))
	).apply(instance, ScenarioSavedData::decodePayload));
	public static final SavedDataType<ScenarioSavedData> TYPE = new SavedDataType<>(
			Identifier.fromNamespaceAndPath("arenaagents", "scenario_run"),
			ScenarioSavedData::new,
			CODEC,
			DataFixTypes.SAVED_DATA_COMMAND_STORAGE
	);

	private ScenarioRunSnapshot snapshot;

	public ScenarioSavedData() {
	}

	private ScenarioSavedData(ScenarioRunSnapshot snapshot) {
		this.snapshot = Objects.requireNonNull(snapshot, "snapshot must not be null");
	}

	public static ScenarioSavedData get(MinecraftServer server) {
		Objects.requireNonNull(server, "server must not be null");
		return server.overworld().getDataStorage().computeIfAbsent(TYPE);
	}

	public Optional<ScenarioRunSnapshot> snapshot() {
		return Optional.ofNullable(snapshot);
	}

	public void setSnapshot(ScenarioRunSnapshot value) {
		ScenarioRunSnapshot revised = Objects.requireNonNull(value, "snapshot must not be null");
		if (revised.equals(snapshot)) return;
		snapshot = revised;
		setDirty();
	}

	public void clear() {
		if (snapshot == null) return;
		snapshot = null;
		setDirty();
	}

	public static void saveNow(MinecraftServer server) {
		Objects.requireNonNull(server, "server must not be null");
		server.overworld().getDataStorage().saveAndJoin();
	}

	private String encodePayload() {
		return snapshot == null ? "" : snapshot.toJson();
	}

	private static ScenarioSavedData decodePayload(String legacyPayload, List<String> chunks) {
		String payload = ChunkedSavedPayload.join(legacyPayload, chunks);
		if (payload == null || payload.isBlank()) return new ScenarioSavedData();
		return new ScenarioSavedData(ScenarioRunSnapshot.fromJson(payload));
	}
}
