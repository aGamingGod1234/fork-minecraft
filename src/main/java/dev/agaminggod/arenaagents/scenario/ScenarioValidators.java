package dev.agaminggod.arenaagents.scenario;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.regex.Pattern;

final class ScenarioValidators {
	private static final Pattern ID_PATTERN = Pattern.compile("[a-z0-9][a-z0-9_-]{0,63}");

	private ScenarioValidators() {
	}

	static String id(String value, String field) {
		String normalized = text(value, field, 64).toLowerCase(Locale.ROOT).replace(' ', '-');
		if (!ID_PATTERN.matcher(normalized).matches()) {
			throw failure("INVALID_ID", field + " must use lowercase letters, numbers, underscores, or hyphens");
		}
		return normalized;
	}

	static String text(String value, String field, int maximumLength) {
		Objects.requireNonNull(value, field + " must not be null");
		String normalized = value.strip().replaceAll("\\s+", " ");
		if (normalized.isEmpty()) {
			throw failure("EMPTY_VALUE", field + " must not be blank");
		}
		if (normalized.length() > maximumLength) {
			throw failure("VALUE_TOO_LONG", field + " exceeds " + maximumLength + " characters");
		}
		return normalized;
	}

	static Optional<String> optionalText(Optional<String> value, String field, int maximumLength) {
		Objects.requireNonNull(value, field + " must not be null");
		return value.map(item -> text(item, field, maximumLength));
	}

	static List<String> textList(List<String> values, String field, int maximumEntries, int maximumLength) {
		List<String> copied = List.copyOf(Objects.requireNonNull(values, field + " must not be null"));
		if (copied.isEmpty()) {
			throw failure("EMPTY_COLLECTION", field + " must not be empty");
		}
		if (copied.size() > maximumEntries) {
			throw failure("COLLECTION_LIMIT", field + " exceeds " + maximumEntries + " entries");
		}
		ArrayList<String> normalized = new ArrayList<>(copied.size());
		for (String value : copied) {
			normalized.add(text(value, field + " entry", maximumLength));
		}
		return List.copyOf(normalized);
	}

	static Map<String, String> attributes(Map<String, String> values) {
		Map<String, String> copied = Map.copyOf(Objects.requireNonNull(values, "attributes must not be null"));
		if (copied.size() > 32) {
			throw failure("COLLECTION_LIMIT", "attributes exceeds 32 entries");
		}
		for (Map.Entry<String, String> entry : copied.entrySet()) {
			text(entry.getKey(), "attribute key", 64);
			text(entry.getValue(), "attribute value", 256);
		}
		return copied;
	}

	static ScenarioValidationException failure(String code, String message) {
		return new ScenarioValidationException(code, message);
	}
}
