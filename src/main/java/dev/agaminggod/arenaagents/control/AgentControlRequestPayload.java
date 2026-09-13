package dev.agaminggod.arenaagents.control;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

public record AgentControlRequestPayload() implements CustomPacketPayload {
	public static final AgentControlRequestPayload INSTANCE = new AgentControlRequestPayload();
	public static final Type<AgentControlRequestPayload> TYPE = new Type<>(
			Identifier.fromNamespaceAndPath("arenaagents", "agent_control_request")
	);
	public static final StreamCodec<RegistryFriendlyByteBuf, AgentControlRequestPayload> CODEC = StreamCodec.unit(INSTANCE);

	@Override
	public Type<AgentControlRequestPayload> type() {
		return TYPE;
	}
}
