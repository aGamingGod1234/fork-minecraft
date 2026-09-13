package dev.agaminggod.arenaagents.scenario.presentation;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

/** Server tombstone for an expired terminal match view. */
public record ArenaSpectatorClearPayload() implements CustomPacketPayload {
	public static final ArenaSpectatorClearPayload INSTANCE = new ArenaSpectatorClearPayload();
	public static final Type<ArenaSpectatorClearPayload> TYPE = new Type<>(
			Identifier.fromNamespaceAndPath("arenaagents", "arena_spectator_clear"));
	public static final StreamCodec<RegistryFriendlyByteBuf, ArenaSpectatorClearPayload> CODEC =
			StreamCodec.unit(INSTANCE);

	@Override
	public Type<ArenaSpectatorClearPayload> type() { return TYPE; }
}
