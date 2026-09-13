package dev.agaminggod.arenaagents.server.voice;

import java.util.Objects;

public record VoiceReceipt(Status status, String message) {
	public VoiceReceipt {
		Objects.requireNonNull(status, "status must not be null");
		message = Objects.requireNonNull(message, "message must not be null");
	}

	public enum Status {
		ACCEPTED,
		PLAYED,
		DEGRADED_TO_TEXT,
		CANCELLED,
		FAILED
	}

	public static VoiceReceipt accepted() {
		return new VoiceReceipt(Status.ACCEPTED, "Speech accepted");
	}

	public static VoiceReceipt degraded(String message) {
		return new VoiceReceipt(Status.DEGRADED_TO_TEXT, message);
	}

	public static VoiceReceipt cancelled() {
		return new VoiceReceipt(Status.CANCELLED, "Speech cancelled");
	}

	public boolean requiresTextFallback() {
		return status == Status.DEGRADED_TO_TEXT || status == Status.FAILED;
	}
}
