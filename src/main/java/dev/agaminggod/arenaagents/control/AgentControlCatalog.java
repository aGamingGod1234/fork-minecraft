package dev.agaminggod.arenaagents.control;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

/** Runtime provider catalog with a deterministic offline fallback. */
public final class AgentControlCatalog {
	private static final String CODEX = "codex";
	private static final List<AgentControlModelOption> FALLBACK = buildFallbackOptions();
	private static volatile List<AgentControlModelOption> options = FALLBACK;

	private AgentControlCatalog() {
	}

	public static List<String> providers() {
		return options.stream().map(AgentControlModelOption::provider).distinct().toList();
	}

	public static List<String> models(String provider) {
		String checked = requireProvider(provider);
		return options.stream().filter(option -> option.provider().equals(checked))
				.map(AgentControlModelOption::model).toList();
	}

	public static String defaultModel(String provider) {
		String checked = requireProvider(provider);
		String preferred = checked.equals(CODEX) ? "gpt-5.6-luna" : null;
		List<String> available = models(checked);
		return preferred != null && available.contains(preferred) ? preferred : available.getFirst();
	}

	public static String defaultReasoning(String provider, String model) {
		List<String> efforts = reasoningEfforts(provider, model);
		if (CODEX.equals(provider) && "gpt-5.6-luna".equals(model) && efforts.contains("xhigh")) return "xhigh";
		return efforts.contains("high") ? "high" : efforts.getFirst();
	}

	public static String defaultServiceTier(String provider, String model) {
		List<String> tiers = serviceTiers(provider, model);
		return CODEX.equals(provider) && tiers.contains("fast") ? "fast" : "priority";
	}

	public static String displayName(String provider, String model) {
		return requireOption(provider, model).displayName();
	}

	public static List<String> reasoningEfforts(String provider, String model) {
		return requireOption(provider, model).reasoningEfforts();
	}

	public static List<String> serviceTiers(String provider, String model) {
		List<String> tiers = requireOption(provider, model).serviceTiers();
		return tiers.isEmpty() ? List.of("priority") : tiers;
	}

	public static boolean hasSpeedMode(String provider, String model) {
		return requireOption(provider, model).serviceTiers().contains("fast");
	}

	public static String requireProvider(String provider) {
		String checked = Objects.requireNonNull(provider, "provider must not be null").toLowerCase(Locale.ROOT);
		if (!providers().contains(checked)) throw new IllegalArgumentException("Unsupported provider: " + checked);
		return checked;
	}

	public static void installRuntimeCatalog(List<AgentControlModelOption> runtimeOptions) {
		List<AgentControlModelOption> checked = List.copyOf(
				Objects.requireNonNull(runtimeOptions, "runtimeOptions must not be null"));
		if (checked.isEmpty() || checked.size() > AgentControlModelOption.MAX_OPTIONS) {
			throw new IllegalArgumentException("runtime model catalog has an invalid item count");
		}
		long identities = checked.stream().map(option -> option.provider() + "\u0000" + option.model()).distinct().count();
		if (identities != checked.size()) throw new IllegalArgumentException("runtime model catalog contains duplicates");
		options = checked;
	}

	public static void resetRuntimeCatalog() {
		options = FALLBACK;
	}

	public static List<AgentControlModelOption> currentOptions() {
		return options;
	}

	public static List<AgentControlModelOption> fallbackOptions() {
		return FALLBACK;
	}

	private static AgentControlModelOption requireOption(String provider, String model) {
		String checkedProvider = requireProvider(provider);
		String checkedModel = Objects.requireNonNull(model, "model must not be null");
		return options.stream()
				.filter(option -> option.provider().equals(checkedProvider) && option.model().equals(checkedModel))
				.findFirst()
				.orElseThrow(() -> new IllegalArgumentException(
						"Unsupported model for " + checkedProvider + ": " + checkedModel));
	}

	private static List<AgentControlModelOption> buildFallbackOptions() {
		ArrayList<AgentControlModelOption> values = new ArrayList<>();
		add(values, CODEX, "gpt-5.6-luna", "GPT 5.6 Luna", List.of("low", "medium", "high", "xhigh", "max"), true);
		add(values, CODEX, "gpt-5.6-terra", "GPT 5.6 Terra", List.of("low", "medium", "high", "xhigh", "max", "ultra"), true);
		add(values, CODEX, "gpt-5.6-sol", "GPT 5.6 Sol", List.of("low", "medium", "high", "xhigh", "max", "ultra"), true);
		add(values, CODEX, "gpt-5.6-sol-wm", "GPT 5.6 Sol WM", List.of("low", "medium", "high", "xhigh", "max", "ultra"), true);
		for (String model : List.of("gpt-5.5", "gpt-5.4")) {
			add(values, CODEX, model, readable(model), List.of("low", "medium", "high", "xhigh"), true);
		}
		for (String model : List.of("gpt-5.4-mini", "gpt-5.3-codex-spark")) {
			add(values, CODEX, model, readable(model), List.of("low", "medium", "high", "xhigh"), false);
		}
		add(values, "gemini", "gemini-3.1-pro", "Gemini 3.1 Pro", List.of("high", "low"), false);
		for (String model : List.of("gemini-3.6-flash", "gemini-3.5-flash")) {
			add(values, "gemini", model, readable(model), List.of("high", "medium", "low"), false);
		}
		for (String model : List.of("claude-sonnet-4-6", "claude-opus-4-6")) {
			add(values, "gemini", model, readable(model), List.of("thinking"), false);
		}
		add(values, "gemini", "gpt-oss-120b", "GPT OSS 120B", List.of("medium"), false);
		for (String model : List.of("kimi-code/k3", "kimi-code/k3-256k")) {
			add(values, "kimi", model, readable(model), List.of("low", "high", "max"), false);
		}
		add(values, "kimi", "kimi-code/kimi-for-coding", "K2.7 Coding", List.of("high"), false);
		add(values, "kimi", "kimi-code/kimi-for-coding-highspeed", "K2.7 Coding Highspeed", List.of("high"), false);
		add(values, "cursor", "composer-2.5", "Composer 2.5", List.of("high"), true);
		add(values, "cursor", "grok-4.5", "Grok 4.5", List.of("low", "medium", "high"), true);
		add(values, "cursor", "grok-4.6", "Grok 4.6", List.of("low", "medium", "high", "xhigh"), true);
		return List.copyOf(values);
	}

	private static void add(List<AgentControlModelOption> values, String provider, String model,
	                        String displayName, List<String> efforts, boolean fast) {
		values.add(new AgentControlModelOption(provider, model, displayName, efforts,
				fast ? List.of("priority", "fast") : List.of()));
	}

	private static String readable(String model) {
		String value = model.startsWith("kimi-code/") ? model.substring("kimi-code/".length()) : model;
		StringBuilder result = new StringBuilder();
		for (String part : value.replace('_', '-').split("-")) {
			if (part.isBlank()) continue;
			if (!result.isEmpty()) result.append(' ');
			result.append(part.equalsIgnoreCase("gpt") ? "GPT"
					: Character.toUpperCase(part.charAt(0)) + part.substring(1));
		}
		return result.toString();
	}
}
