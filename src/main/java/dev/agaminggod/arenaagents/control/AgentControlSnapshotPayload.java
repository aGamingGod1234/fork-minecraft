package dev.agaminggod.arenaagents.control;

import java.util.Objects;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

public final class AgentControlSnapshotPayload implements CustomPacketPayload {
	public static final Type<AgentControlSnapshotPayload> TYPE = new Type<>(
			Identifier.fromNamespaceAndPath("arenaagents", "agent_control_snapshot")
	);
	public static final StreamCodec<RegistryFriendlyByteBuf, AgentControlSnapshotPayload> CODEC = StreamCodec.composite(
			ByteBufCodecs.stringUtf8(AgentControlSnapshotCodec.MAX_ENCODED_BYTES),
			AgentControlSnapshotPayload::encodedSnapshot,
			AgentControlSnapshotPayload::new
	);

	private final String encodedSnapshot;
	private final AgentControlSnapshot snapshot;

	public AgentControlSnapshotPayload(String encodedSnapshot) {
		this.encodedSnapshot = Objects.requireNonNull(encodedSnapshot, "encodedSnapshot must not be null");
		this.snapshot = AgentControlSnapshotCodec.decode(encodedSnapshot);
	}

	private AgentControlSnapshotPayload(String encodedSnapshot, AgentControlSnapshot snapshot) {
		this.encodedSnapshot = encodedSnapshot;
		this.snapshot = snapshot;
	}

	public static AgentControlSnapshotPayload fromSnapshot(AgentControlSnapshot snapshot) {
		AgentControlSnapshot checked = Objects.requireNonNull(snapshot, "snapshot must not be null");
		return new AgentControlSnapshotPayload(AgentControlSnapshotCodec.encode(checked), checked);
	}

	public String encodedSnapshot() {
		return encodedSnapshot;
	}

	public AgentControlSnapshot snapshot() {
		return snapshot;
	}

	@Override
	public boolean equals(Object other) {
		return this == other || other instanceof AgentControlSnapshotPayload payload
				&& encodedSnapshot.equals(payload.encodedSnapshot);
	}

	@Override
	public int hashCode() {
		return encodedSnapshot.hashCode();
	}

	@Override
	public String toString() {
		return "AgentControlSnapshotPayload[encodedSnapshot=" + encodedSnapshot + "]";
	}

	@Override
	public Type<AgentControlSnapshotPayload> type() {
		return TYPE;
	}
}
