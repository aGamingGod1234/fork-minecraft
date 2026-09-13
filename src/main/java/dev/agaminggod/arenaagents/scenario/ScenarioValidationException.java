package dev.agaminggod.arenaagents.scenario;

import java.util.Objects;

public final class ScenarioValidationException extends IllegalArgumentException {
	private final String code;

	public ScenarioValidationException(String code, String message) {
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
