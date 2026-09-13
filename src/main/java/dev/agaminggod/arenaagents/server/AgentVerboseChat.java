package dev.agaminggod.arenaagents.server;

import com.google.gson.JsonElement;
import com.google.gson.JsonParser;
import dev.agaminggod.arenaagents.agent.AgentRecord;
import java.util.ArrayDeque;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class AgentVerboseChat {
	public static final int MAX_MESSAGE_LENGTH = 256;
	private static final Logger LOGGER = LoggerFactory.getLogger(AgentVerboseChat.class);
	private static final Set<String> STAGES = Set.of(
			"conversation", "lifecycle", "planner", "provider", "output", "decision",
			"agent_message", "action", "progress", "result", "retry", "error", "voice"
	);
	private static final Set<String> CURATED_STAGES = Set.of(
			"planner", "output", "agent_message", "decision", "action", "progress", "result", "retry", "error", "voice"
	);
	private static final Pattern UUID_PATTERN = Pattern.compile(
			"(?i)\\b[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}\\b"
	);
	private static final List<Pattern> TECHNICAL_ID_PATTERNS = List.of(
			Pattern.compile("(?i)\\b(?:action|call|trace)[ _-]?id\\b\\s*[:=]"),
			Pattern.compile("(?i)\\baction-progress-\\d+\\b"),
			Pattern.compile("(?i)\\bcall_[a-z0-9]{3,}\\b"),
			Pattern.compile("(?i)\\b[a-z0-9._-]+:\\d+:\\d+:\\d+:program-\\d+-\\d+:\\d+:\\d+:(?:arena-state|step)-\\d+\\b"),
			Pattern.compile("(?i)\\bprogram-\\d+-\\d+:\\d+:\\d+:(?:arena-state|step)-\\d+\\b"),
			Pattern.compile("(?i)\\btrace-[a-z0-9._:-]+-\\d+-\\d+-[a-z][a-z0-9_-]*\\b")
	);
	private static final List<String> CREDENTIAL_MARKERS = List.of(
			"api key", "api_key", "apikey", "authorization", "bearer ", "credential",
			"password", "secret", "token", "x-api-key", "sk-", "aiza"
	);
	private static final List<String> WORLD_DUMP_MARKERS = List.of(
			"world dump", "observation dump", "block dump", "entity dump", "chunk dump",
			"inventory dump", "raw observation"
	);
	private static final List<String> TECHNICAL_MARKERS = List.of(
			"actionid", "action_id", "callid", "call_id", "tool call", "tool_call",
			"traceid", "trace_id", "program step", "diagnostic"
	);

	private AgentVerboseChat() {
	}

	public static boolean allowedStage(String stage) {
		return stage != null && STAGES.contains(stage);
	}

	public static boolean curatedStage(String stage) {
		return stage != null && CURATED_STAGES.contains(stage);
	}

	public static String sanitizeMessage(String message) {
		String compact = compact(Objects.requireNonNull(message, "message must not be null"));
		String lower = compact.toLowerCase(Locale.ROOT);
		if (CREDENTIAL_MARKERS.stream().anyMatch(lower::contains)) return "Sensitive details redacted.";
		if (lower.contains("prompt:") || lower.contains("system prompt") || lower.contains("private instruction")) {
			return "Private prompt details redacted.";
		}
		if (WORLD_DUMP_MARKERS.stream().anyMatch(lower::contains)) {
			return "World details redacted.";
		}
		if (lower.contains("native:") || containsJsonContainer(compact)
				|| TECHNICAL_MARKERS.stream().anyMatch(lower::contains) || UUID_PATTERN.matcher(compact).find()
				|| TECHNICAL_ID_PATTERNS.stream().anyMatch(pattern -> pattern.matcher(compact).find())) {
			return "Technical details hidden.";
		}
		if (compact.isBlank()) return "No details.";
		return bound(compact);
	}

	public static String sanitizeMessage(AgentRecord record, String message) {
		String safe = sanitizeMessage(message);
		if (safe.endsWith("redacted.")) return safe;
		String lower = compact(message).toLowerCase(Locale.ROOT);
		boolean exposesCurrentGoal = record.currentGoal()
				.map(goal -> compact(goal.prompt()).toLowerCase(Locale.ROOT))
				.filter(prompt -> prompt.length() >= 8)
				.map(prompt -> lower.contains(prompt) || lower.length() >= 16 && prompt.contains(lower))
				.orElse(false);
		if (exposesCurrentGoal) return "Private prompt details redacted.";
		return safe;
	}

	public static void report(
			CodexAgentManager manager,
			AgentVerboseState state,
			AgentRecord record,
			String stage,
			String message
	) {
		Objects.requireNonNull(manager, "manager must not be null");
		Objects.requireNonNull(state, "state must not be null");
		Objects.requireNonNull(record, "record must not be null");
		if (!state.enabled()) return;
		if (!allowedStage(stage)) {
			LOGGER.warn("Skipped unsupported verbose stage {}", stage);
			return;
		}
		if (!curatedStage(stage)) return;
		try {
			MinecraftServer server = manager.server();
			if (server == null) return;
			Component line = line(
					manager.displayName(record), record.profile().provider(), stage, sanitizeMessage(record, message)
			);
			for (ServerPlayer player : server.getPlayerList().getPlayers()) {
				try {
					if (GoalControl.mayControl(player.createCommandSourceStack())) player.sendSystemMessage(line);
				} catch (RuntimeException exception) {
					LOGGER.warn("Could not send verbose event to operator {}", player.getGameProfile().name(), exception);
				}
			}
		} catch (RuntimeException exception) {
			LOGGER.warn("Could not publish verbose {} event for {}", stage, record.agentId(), exception);
		}
	}

	public static void report(CodexAgentManager manager, AgentRecord record, String stage, String message) {
		try {
			MinecraftServer server = manager.server();
			if (!AgentVerboseState.enabled(server)) return;
			report(manager, AgentVerboseState.forServer(server), record, stage, message);
		} catch (RuntimeException exception) {
			LOGGER.warn("Could not publish verbose {} event for {}", stage, record.agentId(), exception);
		}
	}

	public static void reportSystem(MinecraftServer server, String stage, String message) {
		try {
			if (!AgentVerboseState.enabled(server) || !curatedStage(stage)) return;
			Component line = line("Voice", "voice", stage, sanitizeMessage(message));
			for (ServerPlayer player : server.getPlayerList().getPlayers()) {
				if (GoalControl.mayControl(player.createCommandSourceStack())) player.sendSystemMessage(line);
			}
		} catch (RuntimeException exception) {
			LOGGER.warn("Could not publish system verbose {} event", stage, exception);
		}
	}

	static Component line(String agentName, String provider, String stage, String message) {
		MutableComponent prefix = Component.literal("[" + agentName + "]")
				.withStyle(familyColor(provider));
		MutableComponent body = Component.literal(" " + stageLabel(stage) + ": " + message)
				.withStyle("error".equals(stage) ? ChatFormatting.RED : ChatFormatting.GRAY);
		return prefix.append(body);
	}

	private static String stageLabel(String stage) {
		return switch (stage) {
			case "planner", "output", "agent_message" -> "Thinking";
			case "decision" -> "Plan";
			case "action" -> "Action";
			case "progress" -> "Progress";
			case "result" -> "Result";
			case "retry" -> "Problem";
			case "error" -> "Error";
			case "voice" -> "Voice";
			default -> throw new IllegalArgumentException("Unsupported verbose stage: " + stage);
		};
	}

	private static ChatFormatting familyColor(String provider) {
		return switch (provider.toLowerCase(Locale.ROOT)) {
			case "codex" -> ChatFormatting.AQUA;
			case "gemini", "antigravity" -> ChatFormatting.LIGHT_PURPLE;
			case "kimi" -> ChatFormatting.GOLD;
			default -> ChatFormatting.WHITE;
		};
	}

	private static String compact(String value) {
		StringBuilder result = new StringBuilder(value.length());
		boolean separatorPending = false;
		for (int offset = 0; offset < value.length();) {
			int codePoint = value.codePointAt(offset);
			offset += Character.charCount(codePoint);
			if (Character.isWhitespace(codePoint) || Character.isISOControl(codePoint)) {
				separatorPending = result.length() > 0;
				continue;
			}
			if (separatorPending) result.append(' ');
			separatorPending = false;
			result.appendCodePoint(codePoint);
		}
		return result.toString();
	}

	private static boolean containsJsonContainer(String value) {
		for (int start = 0; start < value.length(); start++) {
			char opening = value.charAt(start);
			if (opening != '[' && opening != '{') continue;
			int end = matchingJsonContainerEnd(value, start);
			if (end < 0) continue;
			try {
				JsonElement parsed = JsonParser.parseString(value.substring(start, end + 1));
				if (parsed.isJsonArray() || parsed.isJsonObject()) return true;
			} catch (RuntimeException ignored) {
				// Bracketed natural-language prose is not a technical JSON record.
			}
		}
		return false;
	}

	private static int matchingJsonContainerEnd(String value, int start) {
		ArrayDeque<Character> closings = new ArrayDeque<>();
		closings.push(value.charAt(start) == '[' ? ']' : '}');
		boolean quoted = false;
		boolean escaped = false;
		for (int index = start + 1; index < value.length(); index++) {
			char current = value.charAt(index);
			if (quoted) {
				if (escaped) {
					escaped = false;
				} else if (current == '\\') {
					escaped = true;
				} else if (current == '"') {
					quoted = false;
				}
				continue;
			}
			if (current == '"') {
				quoted = true;
			} else if (current == '[') {
				closings.push(']');
			} else if (current == '{') {
				closings.push('}');
			} else if (current == ']' || current == '}') {
				if (closings.isEmpty() || closings.pop() != current) return -1;
				if (closings.isEmpty()) return index;
			}
		}
		return -1;
	}

	private static String bound(String value) {
		if (value.length() <= MAX_MESSAGE_LENGTH) return value;
		int end = MAX_MESSAGE_LENGTH - 3;
		if (Character.isHighSurrogate(value.charAt(end - 1)) && Character.isLowSurrogate(value.charAt(end))) end--;
		return value.substring(0, end) + "...";
	}
}
