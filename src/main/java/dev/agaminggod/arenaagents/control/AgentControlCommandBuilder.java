package dev.agaminggod.arenaagents.control;

import com.mojang.brigadier.arguments.StringArgumentType;
import dev.agaminggod.arenaagents.agent.AgentConstants;
import dev.agaminggod.arenaagents.agent.AgentGameMode;
import dev.agaminggod.arenaagents.agent.AgentId;
import java.util.Locale;
import java.util.List;
import java.util.LinkedHashSet;
import java.util.Objects;
import java.util.Set;

public final class AgentControlCommandBuilder {
	private static final Set<String> AGENT_OPERATIONS = Set.of("stop", "resume", "respawn", "remove", "status", "auto");
	private static final Set<String> PROMPT_OPERATIONS = Set.of("start", "queue", "steer");

	private AgentControlCommandBuilder() {
	}

	public static String summon(String provider, String model, String reasoning, String optionalName) {
		return summon(provider, model, reasoning, optionalName, AgentGameMode.SURVIVAL);
	}

	public static String summon(
			String provider,
			String model,
			String reasoning,
			String optionalName,
			AgentGameMode gameMode
	) {
		return summon(provider, model, reasoning,
				AgentControlCatalog.defaultServiceTier(provider, model), optionalName, gameMode);
	}

	public static String summon(
			String provider,
			String model,
			String reasoning,
			String serviceTier,
			String optionalName,
			AgentGameMode gameMode
	) {
		String checkedProvider = AgentControlCatalog.requireProvider(provider);
		String checkedModel = requireToken(model, "model", AgentConstants.MAX_MODEL_LENGTH);
		String checkedReasoning = requireToken(reasoning, "reasoning", AgentConstants.MAX_REASONING_LENGTH);
		String checkedTier = requireToken(serviceTier, "speed mode", 24);
		if (!AgentControlCatalog.serviceTiers(checkedProvider, checkedModel).contains(checkedTier)) {
			throw new IllegalArgumentException("speed mode is unavailable for this provider/model");
		}
		String normalizedName = normalizeOptional(optionalName);
		if (normalizedName.length() > AgentConstants.MAX_USER_NAME_LENGTH) {
			throw new IllegalArgumentException("name exceeds the supported length");
		}
		String encodedName = normalizedName.isEmpty() ? "\"\"" : StringArgumentType.escapeIfRequired(normalizedName);
		return "codex summon-configured "
				+ checkedProvider + " "
				+ StringArgumentType.escapeIfRequired(checkedModel) + " "
				+ StringArgumentType.escapeIfRequired(checkedReasoning) + " "
				+ StringArgumentType.escapeIfRequired(checkedTier) + " "
				+ Objects.requireNonNull(gameMode, "gameMode must not be null").wireName() + " "
				+ encodedName;
	}
	public static String agent(String operation, String agentId) {
		String checkedOperation = requireOperation(operation, AGENT_OPERATIONS);
		return "codex " + checkedOperation + " " + AgentId.parse(agentId);
	}

	public static String prompt(String operation, String agentId, String prompt) {
		String checkedOperation = requireOperation(operation, PROMPT_OPERATIONS);
		return "codex " + checkedOperation + " " + AgentId.parse(agentId) + " " + normalizePrompt(prompt);
	}

	public static String saveGroup(String name, List<String> agentIds) {
		String checkedName = StringArgumentType.escapeIfRequired(AgentControlGroup.normalizeName(name));
		List<String> checkedIds = List.copyOf(Objects.requireNonNull(agentIds, "agentIds must not be null"));
		if (checkedIds.isEmpty() || checkedIds.size() > AgentControlGroup.MAX_MEMBERS) {
			throw new IllegalArgumentException("A saved group must contain 1 to 16 agents");
		}
		List<String> canonicalIds = checkedIds.stream().map(value -> AgentId.parse(value).toString()).toList();
		if (new LinkedHashSet<>(canonicalIds).size() != canonicalIds.size()) {
			throw new IllegalArgumentException("A saved group cannot contain the same agent twice");
		}
		return "codex group save " + checkedName + " " + String.join(" ", canonicalIds);
	}

	public static String spawnGroup(String name) {
		return groupOperation("spawn", name);
	}

	public static String deleteGroup(String name) {
		return groupOperation("delete", name);
	}

	private static String groupOperation(String operation, String name) {
		return "codex group " + operation + " "
				+ StringArgumentType.escapeIfRequired(AgentControlGroup.normalizeName(name));
	}

	private static String requireOperation(String operation, Set<String> allowed) {
		String checked = Objects.requireNonNull(operation, "operation must not be null").toLowerCase(Locale.ROOT);
		if (!allowed.contains(checked)) {
			throw new IllegalArgumentException("Unsupported control operation: " + operation);
		}
		return checked;
	}

	private static String requireToken(String value, String field, int maximumLength) {
		String checked = Objects.requireNonNull(value, field + " must not be null").strip();
		if (checked.isEmpty() || checked.length() > maximumLength || checked.codePoints().anyMatch(Character::isISOControl)) {
			throw new IllegalArgumentException(field + " is invalid");
		}
		return checked;
	}

	private static String normalizeOptional(String value) {
		if (value == null) {
			return "";
		}
		return value.strip().replaceAll("\\s+", " ");
	}

	private static String normalizePrompt(String value) {
		String normalized = Objects.requireNonNull(value, "prompt must not be null").strip().replaceAll("\\s+", " ");
		if (normalized.isEmpty()) {
			throw new IllegalArgumentException("prompt must not be blank");
		}
		if (normalized.length() > AgentConstants.MAX_PROMPT_LENGTH) {
			throw new IllegalArgumentException("prompt exceeds the supported length");
		}
		return normalized;
	}
}
