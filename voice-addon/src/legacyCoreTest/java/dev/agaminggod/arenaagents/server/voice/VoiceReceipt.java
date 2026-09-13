package dev.agaminggod.arenaagents.server.voice;

import java.util.Objects;

/** The receipt API shipped by the pre-configuration 0.1.0 core. */
public record VoiceReceipt(Status status, String message) {
	public VoiceReceipt {
		Objects.requireNonNull(status, "status must not be null");
		message = Objects.requireNonNull(message, "message must not be null");
	}

	public enum Status {
		ACCEPTED,
		PLAYED,
		DEGRADED_TO_TEXT,
		FAILED
	}

	public static VoiceReceipt accepted() {
		return new VoiceReceipt(Status.ACCEPTED, "Speech accepted");
	}

	public static VoiceReceipt degraded(String message) {
		return new VoiceReceipt(Status.DEGRADED_TO_TEXT, message);
	}
}
