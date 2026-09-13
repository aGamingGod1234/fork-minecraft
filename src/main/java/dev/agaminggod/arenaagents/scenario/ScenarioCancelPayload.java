package dev.agaminggod.arenaagents.scenario;

import java.util.Objects;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

/** Operator request to stop one exact preparation without affecting a later build. */
public record ScenarioCancelPayload(String buildId) implements CustomPacketPayload {
	public static final Type<ScenarioCancelPayload> TYPE = new Type<>(
			Identifier.fromNamespaceAndPath("arenaagents", "scenario_cancel"));
	public static final StreamCodec<RegistryFriendlyByteBuf, ScenarioCancelPayload> CODEC = StreamCodec.composite(
			ByteBufCodecs.stringUtf8(80), ScenarioCancelPayload::buildId, ScenarioCancelPayload::new);

	public ScenarioCancelPayload {
		buildId = Objects.requireNonNull(buildId, "buildId must not be null").trim();
		if (buildId.isEmpty() || buildId.length() > 80) throw new IllegalArgumentException("buildId is invalid");
	}

	@Override
	public Type<ScenarioCancelPayload> type() {
		return TYPE;
	}
}
