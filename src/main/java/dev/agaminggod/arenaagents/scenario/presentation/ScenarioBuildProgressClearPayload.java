package dev.agaminggod.arenaagents.scenario.presentation;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

/** Server tombstone for an expired build dashboard. */
public record ScenarioBuildProgressClearPayload() implements CustomPacketPayload {
	public static final ScenarioBuildProgressClearPayload INSTANCE = new ScenarioBuildProgressClearPayload();
	public static final Type<ScenarioBuildProgressClearPayload> TYPE = new Type<>(
			Identifier.fromNamespaceAndPath("arenaagents", "scenario_build_progress_clear"));
	public static final StreamCodec<RegistryFriendlyByteBuf, ScenarioBuildProgressClearPayload> CODEC =
			StreamCodec.unit(INSTANCE);

	@Override
	public Type<ScenarioBuildProgressClearPayload> type() { return TYPE; }
}
