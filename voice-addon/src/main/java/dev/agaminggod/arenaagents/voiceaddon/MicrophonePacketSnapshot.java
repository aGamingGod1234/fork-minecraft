package dev.agaminggod.arenaagents.voiceaddon;

import de.maxhenkel.voicechat.api.VoicechatServerApi;
import de.maxhenkel.voicechat.api.events.MicrophonePacketEvent;
import java.util.Objects;
import java.util.UUID;

record MicrophonePacketSnapshot(
		Object minecraftServer,
		UUID playerId,
		VoicechatServerApi voicechat,
		boolean whispering,
		byte[] opus
) {
	MicrophonePacketSnapshot {
		playerId = Objects.requireNonNull(playerId, "playerId must not be null");
		voicechat = Objects.requireNonNull(voicechat, "voicechat must not be null");
		opus = Objects.requireNonNull(opus, "opus must not be null").clone();
	}

	static MicrophonePacketSnapshot capture(MicrophonePacketEvent event) {
		Objects.requireNonNull(event, "event must not be null");
		if (event.getSenderConnection() == null || event.getSenderConnection().getPlayer() == null
				|| event.getPacket() == null || event.getVoicechat() == null) return null;
		return new MicrophonePacketSnapshot(
				null,
				event.getSenderConnection().getPlayer().getUuid(),
				event.getVoicechat(),
				event.getPacket().isWhispering(),
				event.getPacket().getOpusEncodedData()
		);
	}

	MicrophonePacketSnapshot forServer(Object server) {
		return new MicrophonePacketSnapshot(Objects.requireNonNull(server), playerId, voicechat, whispering, opus);
	}

	@Override
	public byte[] opus() {
		return opus.clone();
	}
}
