package dev.agaminggod.arenaagents.agent;

import java.util.Objects;

public final class AgentDomainException extends IllegalArgumentException {
	private final String code;

	public AgentDomainException(String code, String message) {
		super(Objects.requireNonNull(message, "message must not be null"));
		if (code == null || code.isBlank() || code.length() > 64) {
			throw new IllegalArgumentException("code must contain at most 64 characters");
		}
		this.code = code;
	}

	public String code() {
		return code;
	}
}
