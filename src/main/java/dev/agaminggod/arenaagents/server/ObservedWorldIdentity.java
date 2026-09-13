package dev.agaminggod.arenaagents.server;

import com.mojang.serialization.Codec;
import java.util.UUID;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.datafix.DataFixTypes;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.world.level.saveddata.SavedDataType;

/** Stable opaque identity for remembered observations, independent of seed or filesystem paths. */
public final class ObservedWorldIdentity extends SavedData {
	private static final SavedDataType<ObservedWorldIdentity> TYPE = new SavedDataType<>(
		Identifier.fromNamespaceAndPath("arenaagents", "observed_world_identity"),
		() -> { var identity = new ObservedWorldIdentity(UUID.randomUUID().toString()); identity.setDirty(); return identity; },
		Codec.STRING.xmap(ObservedWorldIdentity::new, value -> value.id), DataFixTypes.SAVED_DATA_COMMAND_STORAGE);
	private final String id;
	private ObservedWorldIdentity(String id) { this.id = UUID.fromString(id).toString(); }
	public static String get(MinecraftServer server) { return server.overworld().getDataStorage().computeIfAbsent(TYPE).id; }
}
