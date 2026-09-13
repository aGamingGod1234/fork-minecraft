package dev.agaminggod.arenaagents.agent;

public final class AgentConstants {
	public static final int SCHEMA_VERSION = 1;
	public static final int DEFAULT_AGENT_LIMIT = 16;
	public static final int DEFAULT_QUEUE_LIMIT = 32;
	public static final int DEFAULT_SKIN_VARIANT_COUNT = 4;
	public static final int MAX_CONFIGURED_AGENTS = 1_024;
	public static final int MAX_CONFIGURED_QUEUE_LIMIT = 256;
	public static final int MAX_STEERING_INSTRUCTIONS = 64;
	public static final int MAX_PROMPT_LENGTH = 4_096;
	public static final int MAX_MODEL_LENGTH = 128;
	public static final int MAX_REASONING_LENGTH = 64;
	public static final int MAX_USER_NAME_LENGTH = 32;
	public static final int MAX_SUMMARY_LENGTH = 2_048;
	public static final int MAX_ERROR_LENGTH = 1_024;
	public static final int MAX_INVENTORY_SNAPSHOT_LENGTH = 16_384;
	public static final int SHORT_ID_LENGTH = 8;

	private AgentConstants() {
	}
}
