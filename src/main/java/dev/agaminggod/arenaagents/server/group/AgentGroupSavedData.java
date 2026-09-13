package dev.agaminggod.arenaagents.server.group;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import dev.agaminggod.arenaagents.server.ChunkedSavedPayload;
import java.util.List;
import java.util.Objects;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.datafix.DataFixTypes;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.world.level.saveddata.SavedDataType;

public final class AgentGroupSavedData extends SavedData {
	private static final String PAYLOAD_FIELD = "payload";
	private static final String PAYLOAD_CHUNKS_FIELD = "payload_chunks";
	private static final Codec<AgentGroupSavedData> CODEC = RecordCodecBuilder.create(instance -> instance.group(
			ChunkedSavedPayload.legacyCodec().optionalFieldOf(PAYLOAD_FIELD, "").forGetter(data -> ""),
			ChunkedSavedPayload.chunksCodec().optionalFieldOf(PAYLOAD_CHUNKS_FIELD, List.of())
					.forGetter(data -> ChunkedSavedPayload.split(data.encodePayload()))
	).apply(instance, AgentGroupSavedData::decodePayload));
	public static final SavedDataType<AgentGroupSavedData> TYPE = new SavedDataType<>(
			Identifier.fromNamespaceAndPath("arenaagents", "agent_groups"),
			AgentGroupSavedData::new,
			CODEC,
			DataFixTypes.SAVED_DATA_COMMAND_STORAGE
	);

	private final AgentGroupRegistry registry;

	public AgentGroupSavedData() {
		this(new AgentGroupRegistry.Snapshot(AgentGroupRegistry.SCHEMA_VERSION, List.of()));
	}

	private AgentGroupSavedData(AgentGroupRegistry.Snapshot snapshot) {
		registry = AgentGroupRegistry.restore(snapshot, this::setDirty);
	}

	public static AgentGroupSavedData get(MinecraftServer server) {
		Objects.requireNonNull(server, "server must not be null");
		return server.overworld().getDataStorage().computeIfAbsent(TYPE);
	}

	public AgentGroupRegistry registry() {
		return registry;
	}

	private String encodePayload() {
		return AgentGroupSnapshotCodec.encode(registry.snapshot());
	}

	private static AgentGroupSavedData decodePayload(String legacyPayload, List<String> chunks) {
		String joined = ChunkedSavedPayload.join(legacyPayload, chunks);
		if (joined.isBlank()) return new AgentGroupSavedData();
		return new AgentGroupSavedData(AgentGroupSnapshotCodec.decode(joined));
	}
}
