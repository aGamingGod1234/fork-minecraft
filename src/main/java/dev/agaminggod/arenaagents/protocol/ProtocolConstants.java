package dev.agaminggod.arenaagents.protocol;

public final class ProtocolConstants {
	public static final String FIELD_PROTOCOL_VERSION = "protocolVersion";
	public static final int PROTOCOL_VERSION = 1;
	public static final int MAX_LINE_BYTES = 65_536;
	public static final int MAX_COMMAND_ID_LENGTH = 128;
	public static final int MAX_CHAT_LENGTH = 512;
	public static final int MAX_VOICE_TEXT_LENGTH = 280;
	public static final int MAX_SUMMARY_LENGTH = 2_048;
	public static final int MAX_IDENTIFIER_LENGTH = 256;
	public static final int MAX_TARGET_SELECTOR_LENGTH = 256;
	public static final int MAX_REASON_CODE_LENGTH = 128;
	public static final int MAX_RESULT_MESSAGE_LENGTH = 2_048;
	public static final long MIN_DURATION_MS = 1L;
	public static final long MAX_DURATION_MS = 600_000L;
	public static final double MIN_MOVEMENT_TOLERANCE = 0.01D;
	public static final double MAX_MOVEMENT_TOLERANCE = 16.0D;

	public static final String ERROR_ENCODING_FAILED = "ENCODING_FAILED";
	public static final String ERROR_INVALID_FIELD = "INVALID_FIELD";
	public static final String ERROR_LINE_TOO_LARGE = "LINE_TOO_LARGE";
	public static final String ERROR_MALFORMED_JSON = "MALFORMED_JSON";
	public static final String ERROR_MISSING_FIELD = "MISSING_FIELD";
	public static final String ERROR_OUT_OF_RANGE = "OUT_OF_RANGE";
	public static final String ERROR_UNKNOWN_ACTION = "UNKNOWN_ACTION";
	public static final String ERROR_UNKNOWN_FIELD = "UNKNOWN_FIELD";
	public static final String ERROR_UNSUPPORTED_VERSION = "UNSUPPORTED_VERSION";

	private ProtocolConstants() {
	}
}
