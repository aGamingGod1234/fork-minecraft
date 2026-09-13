package dev.agaminggod.arenaagents.server.runtime;

import java.util.Locale;
import java.util.Objects;

public final class EntityTargetSelector {
	private static final String[] LABEL_PREFIXES = {"player:", "uuid:", "name=", "name:"};

	private EntityTargetSelector() {
	}

	public static String normalize(String selector) {
		String normalized = Objects.requireNonNull(selector, "selector must not be null").trim();
		String lower = normalized.toLowerCase(Locale.ROOT);
		for (String prefix : LABEL_PREFIXES) {
			if (!lower.startsWith(prefix)) continue;
			normalized = normalized.substring(prefix.length()).trim();
			break;
		}
		if (normalized.isEmpty()) throw new IllegalArgumentException("selector must not be blank");
		return normalized;
	}
}
