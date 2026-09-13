package dev.agaminggod.arenaagents.agent;

import java.util.Locale;
import java.util.Set;

public final class AgentValidators {
	private static final String ERROR_EMPTY = "EMPTY_VALUE";
	private static final String ERROR_TOO_LONG = "VALUE_TOO_LONG";
	private static final String ERROR_CONTROL_CHARACTER = "CONTROL_CHARACTER";
	private static final Set<String> PROVIDERS = Set.of("codex", "gemini", "kimi", "cursor");
	private static final Set<String> SERVICE_TIERS = Set.of("priority", "fast");

	private AgentValidators() {
	}

	public static String normalizePrompt(String value) {
		return normalizeWhitespace(value, "prompt", AgentConstants.MAX_PROMPT_LENGTH);
	}

	public static String requireModel(String value) {
		return requireSingleLine(value, "model", AgentConstants.MAX_MODEL_LENGTH);
	}

	public static String requireProvider(String value) {
		String provider = requireSingleLine(value, "provider", AgentConstants.MAX_MODEL_LENGTH).toLowerCase(Locale.ROOT);
		if (!PROVIDERS.contains(provider)) {
			throw failure("INVALID_PROVIDER", "provider must be codex, gemini, kimi, or cursor");
		}
		return provider;
	}

	public static String requireReasoning(String value) {
		return requireSingleLine(value, "reasoning", AgentConstants.MAX_REASONING_LENGTH).toLowerCase(Locale.ROOT);
	}

	public static String requireServiceTier(String value) {
		String tier = requireSingleLine(value, "speed mode", 24).toLowerCase(Locale.ROOT);
		if (!SERVICE_TIERS.contains(tier)) {
			throw failure("INVALID_SERVICE_TIER", "speed mode must be priority or fast");
		}
		return tier;
	}

	public static String requireUserName(String value) {
		return requireSingleLine(value, "name", AgentConstants.MAX_USER_NAME_LENGTH);
	}

	public static String boundedText(String value, String field, int maximumLength) {
		if (value == null || value.isEmpty()) {
			return "";
		}
		if (value.length() <= maximumLength) {
			return value;
		}
		return value.substring(0, maximumLength);
	}

	static String requireNonBlank(String value, String field, int maximumLength) {
		if (value == null || value.isBlank()) {
			throw failure(ERROR_EMPTY, field + " must not be blank");
		}
		if (value.length() > maximumLength) {
			throw failure(ERROR_TOO_LONG, field + " must not exceed " + maximumLength + " characters");
		}
		return value;
	}

	private static String requireSingleLine(String value, String field, int maximumLength) {
		String checked = requireNonBlank(value, field, maximumLength).trim();
		for (int index = 0; index < checked.length(); index++) {
			char character = checked.charAt(index);
			if (Character.isISOControl(character)) {
				throw failure(ERROR_CONTROL_CHARACTER, field + " must not contain control characters");
			}
		}
		return checked;
	}

	private static String normalizeWhitespace(String value, String field, int maximumLength) {
		requireNonBlank(value, field, maximumLength);
		StringBuilder normalized = new StringBuilder(value.length());
		boolean separatorPending = false;
		for (int offset = 0; offset < value.length();) {
			int codePoint = value.codePointAt(offset);
			offset += Character.charCount(codePoint);
			if (Character.isWhitespace(codePoint)) {
				separatorPending = normalized.length() > 0;
				continue;
			}
			if (Character.isISOControl(codePoint)) {
				throw failure(ERROR_CONTROL_CHARACTER, field + " must not contain control characters");
			}
			if (separatorPending) {
				normalized.append(' ');
				separatorPending = false;
			}
			normalized.appendCodePoint(codePoint);
		}
		if (normalized.isEmpty()) {
			throw failure(ERROR_EMPTY, field + " must contain non-whitespace text");
		}
		return normalized.toString();
	}

	private static AgentDomainException failure(String code, String message) {
		return new AgentDomainException(code, message);
	}
}
