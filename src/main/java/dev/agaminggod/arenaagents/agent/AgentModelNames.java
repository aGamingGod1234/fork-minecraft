package dev.agaminggod.arenaagents.agent;

import java.util.Locale;
import java.util.Map;
import java.util.Objects;

/** Canonical operator-facing names for every model in the bundled provider catalog. */
public final class AgentModelNames {
	private static final Map<ModelKey, Names> NAMES = Map.ofEntries(
			entry("codex", "gpt-5.6-sol", "GPT 5.6 Sol", "Sol"),
			entry("codex", "gpt-5.6-sol-wm", "GPT 5.6 Sol WM", "Sol WM"),
			entry("codex", "gpt-5.6-terra", "GPT 5.6 Terra", "Terra"),
			entry("codex", "gpt-5.6-luna", "GPT 5.6 Luna", "Luna"),
			entry("codex", "gpt-5.5", "GPT 5.5", "GPT 5.5"),
			entry("codex", "gpt-5.4", "GPT 5.4", "GPT 5.4"),
			entry("codex", "gpt-5.4-mini", "GPT 5.4 Mini", "GPT 5.4 Mini"),
			entry("codex", "gpt-5.3-codex-spark", "GPT 5.3 Codex Spark", "Spark"),
			entry("codex", "codex-auto-review", "Codex Auto Review", "Auto Review"),
			entry("gemini", "gemini-3.1-pro", "Gemini 3.1 Pro", "3.1 Pro"),
			entry("gemini", "gemini-3.6-flash", "Gemini 3.6 Flash", "3.6 Flash"),
			entry("gemini", "gemini-3.5-flash", "Gemini 3.5 Flash", "3.5 Flash"),
			entry("gemini", "claude-sonnet-4-6", "Claude Sonnet 4.6", "Sonnet 4.6"),
			entry("gemini", "claude-opus-4-6", "Claude Opus 4.6", "Opus 4.6"),
			entry("gemini", "gpt-oss-120b", "GPT OSS 120B", "GPT OSS 120B"),
			entry("kimi", "kimi-code/k3", "Kimi K3", "K3"),
			entry("kimi", "kimi-code/k3-256k", "Kimi K3 256K", "K3 256K"),
			entry("kimi", "kimi-code/kimi-for-coding", "K2.7 Coding", "K2.7 Coding"),
			entry("kimi", "kimi-code/kimi-for-coding-highspeed", "K2.7 Coding Highspeed", "K2.7 Fast"),
			entry("cursor", "composer-2.5", "Composer 2.5", "Composer 2.5"),
			entry("cursor", "grok-4.5", "Grok 4.5", "Grok 4.5"),
			entry("cursor", "grok-4.6", "Grok 4.6", "Grok 4.6")
	);

	private AgentModelNames() {
	}

	public static String displayName(String provider, String slug) {
		ModelKey key = key(provider, slug);
		Names names = NAMES.get(key);
		return names == null ? readable(key.slug()) : names.displayName();
	}

	public static String shortLabel(String provider, String slug) {
		ModelKey key = key(provider, slug);
		Names names = NAMES.get(key);
		return names == null ? readable(key.slug()) : names.shortLabel();
	}

	/** Model label formatting used by the in-world tag, where the family separator is useful at a glance. */
	public static String tagName(String provider, String slug) {
		ModelKey key = key(provider, slug);
		if (key.provider().equals("codex")) {
			return switch (key.slug()) {
				case "gpt-5.6-sol" -> "GPT 5.6-Sol";
				case "gpt-5.6-sol-wm" -> "GPT 5.6-Sol WM";
				case "gpt-5.6-terra" -> "GPT 5.6-Terra";
				case "gpt-5.6-luna" -> "GPT 5.6-Luna";
				default -> displayName(key.provider(), key.slug());
			};
		}
		return displayName(key.provider(), key.slug());
	}

	private static Map.Entry<ModelKey, Names> entry(
			String provider,
			String slug,
			String displayName,
			String shortLabel
	) {
		return Map.entry(new ModelKey(provider, slug), new Names(displayName, shortLabel));
	}

	private static ModelKey key(String provider, String slug) {
		String checkedProvider = requireText(provider, "provider").toLowerCase(Locale.ROOT);
		String checkedSlug = requireText(slug, "model slug").toLowerCase(Locale.ROOT);
		return new ModelKey(checkedProvider, checkedSlug);
	}

	private static String readable(String slug) {
		String value = slug.startsWith("kimi-code/") ? slug.substring("kimi-code/".length()) : slug;
		StringBuilder result = new StringBuilder();
		for (String part : value.replace('_', '-').split("-")) {
			if (part.isBlank()) continue;
			if (!result.isEmpty()) result.append(' ');
			result.append(readablePart(part));
		}
		return result.isEmpty() ? slug : result.toString();
	}

	private static String readablePart(String part) {
		String lower = part.toLowerCase(Locale.ROOT);
		if (lower.equals("gpt") || lower.equals("oss") || lower.equals("wm")) {
			return lower.toUpperCase(Locale.ROOT);
		}
		if (lower.matches("k\\d+(?:\\.\\d+)?") || lower.matches("\\d+[bk]")) {
			return lower.toUpperCase(Locale.ROOT);
		}
		if (lower.matches("\\d+(?:\\.\\d+)?")) return lower;
		return Character.toUpperCase(lower.charAt(0)) + lower.substring(1);
	}

	private static String requireText(String value, String label) {
		String checked = Objects.requireNonNull(value, label + " must not be null").trim();
		if (checked.isEmpty()) throw new IllegalArgumentException(label + " must not be blank");
		return checked;
	}

	private record ModelKey(String provider, String slug) {
	}

	private record Names(String displayName, String shortLabel) {
	}
}
