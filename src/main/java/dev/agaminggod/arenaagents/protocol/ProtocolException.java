package dev.agaminggod.arenaagents.protocol;

import java.io.Serial;
import java.util.Objects;

public final class ProtocolException extends RuntimeException {
	@Serial
	private static final long serialVersionUID = 1L;

	private final String code;

	public ProtocolException(String code, String message) {
		this(code, message, null);
	}

	public ProtocolException(String code, String message, Throwable cause) {
		super(Objects.requireNonNull(message, "message must not be null"), cause);
		if (code == null || code.isBlank()) {
			throw new IllegalArgumentException("code must not be blank");
		}
		this.code = code;
	}

	public String code() {
		return code;
	}
}
