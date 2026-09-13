package dev.agaminggod.arenaagents.server.bridge;

public final class BridgeProtocolException extends RuntimeException {
	private final String code;

	public BridgeProtocolException(String code, String message) {
		super(message);
		this.code = code;
	}

	public BridgeProtocolException(String code, String message, Throwable cause) {
		super(message, cause);
		this.code = code;
	}

	public String code() {
		return code;
	}
}
