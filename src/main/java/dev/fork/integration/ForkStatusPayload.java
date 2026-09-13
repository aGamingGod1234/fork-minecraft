package dev.fork.integration;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

public record ForkStatusPayload(String json) implements CustomPacketPayload {
    public static final Type<ForkStatusPayload> TYPE = new Type<>(Identifier.fromNamespaceAndPath("fork", "status"));
    public static final StreamCodec<RegistryFriendlyByteBuf,ForkStatusPayload> CODEC =
        ByteBufCodecs.stringUtf8(32767).map(ForkStatusPayload::new,ForkStatusPayload::json).cast();
    @Override public Type<ForkStatusPayload> type() { return TYPE; }
}
