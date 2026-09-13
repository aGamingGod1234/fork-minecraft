package dev.agaminggod.arenaagents.scenario;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

public record ScenarioLaunchPayload(String encodedRequest) implements CustomPacketPayload {
	public static final Type<ScenarioLaunchPayload> TYPE = new Type<>(
			Identifier.fromNamespaceAndPath("arenaagents", "scenario_launch")
	);
	public static final StreamCodec<RegistryFriendlyByteBuf, ScenarioLaunchPayload> CODEC = StreamCodec.composite(
			ByteBufCodecs.STRING_UTF8,
			ScenarioLaunchPayload::encodedRequest,
			ScenarioLaunchPayload::new
	);

	public ScenarioLaunchPayload {
		if (encodedRequest == null || encodedRequest.isBlank()) {
			throw new IllegalArgumentException("encodedRequest must not be blank");
		}
	}

	public static ScenarioLaunchPayload fromRequest(ScenarioLaunchRequest request) {
		return new ScenarioLaunchPayload(ScenarioLaunchCodec.encode(request));
	}

	public ScenarioLaunchRequest request() {
		return ScenarioLaunchCodec.decode(encodedRequest);
	}

	@Override
	public Type<? extends CustomPacketPayload> type() {
		return TYPE;
	}
}
