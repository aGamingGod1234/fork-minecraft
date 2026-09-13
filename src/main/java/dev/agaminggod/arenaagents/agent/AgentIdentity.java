package dev.agaminggod.arenaagents.agent;

import java.nio.charset.StandardCharsets;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Deterministic, human-readable identity shared by fake players, UI, chat, and skins. */
public final class AgentIdentity {
	private static final int PLAYER_NAME_LIMIT = 16;
	private static final Pattern PUBLIC_NAME = Pattern.compile("^[A-Za-z][A-Za-z0-9_]{0,15}$");
	private static final Pattern LEGACY_PLAYER_NAME = Pattern.compile("^([A-Za-z][A-Za-z0-9]{1,6})_[0-9A-Fa-f]{8}$");
	private static final String[] CODEX_LEGACY_MODELS = {"sol", "ter", "lun", "gpt"};
	private static final String[] CODEX_LEGACY_VARIANTS = {"cyan", "viol", "emer", "ambe"};
	private static final String[] GEMINI_LEGACY_MODELS = {"gem"};
	private static final String[] GEMINI_LEGACY_VARIANTS = {"azur", "crim", "sola", "verd"};
	private static final String[] KIMI_K3_LEGACY_MODELS = {"k3"};
	private static final String[] KIMI_K3_LEGACY_VARIANTS = {"moon", "ice", "orch", "sunr"};
	private static final String[] KIMI_LEGACY_MODELS = {"kimi"};
	private static final String[] KIMI_LEGACY_VARIANTS = {"moo", "ice", "orc", "sun"};

	private AgentIdentity() {
	}

	/** Compatibility formatter for call sites that do not yet carry the stable agent ID. */
	public static String displayName(AgentProfile profile) {
		Objects.requireNonNull(profile, "profile must not be null");
		return profile.userName().map(AgentIdentity::canonicalPublicName)
				.orElseGet(() -> defaultPublicName(profile.provider(), profile.model()));
	}

	public static String displayName(AgentId id, AgentProfile profile) {
		Objects.requireNonNull(id, "id must not be null");
		Objects.requireNonNull(profile, "profile must not be null");
		return playerName(id, profile);
	}

	/** Returns the readable tag shown above a spawned player, without changing its safe technical name. */
	public static String displayNameTag(AgentProfile profile) {
		Objects.requireNonNull(profile, "profile must not be null");
		String technicalName = displayName(profile);
		String defaultTechnicalName = defaultPublicName(profile.provider(), profile.model());
		return technicalName.equals(defaultTechnicalName)
				? AgentModelNames.tagName(profile.provider(), profile.model())
				: technicalName;
	}

	static String canonicalIdentityKey(String value) {
		return Objects.requireNonNull(value, "identity value must not be null").toLowerCase(Locale.ROOT);
	}

	public static boolean sameIdentity(String first, String second) {
		return canonicalIdentityKey(first).equals(canonicalIdentityKey(second));
	}

	public static Optional<String> worldTag(AgentProfile profile) {
		Objects.requireNonNull(profile, "profile must not be null");
		return Optional.of(displayName(profile));
	}

	public static String defaultPublicName(String provider, String model) {
		return canonicalPublicName(AgentModelNames.displayName(provider, model));
	}

	/** Converts a requested label into the exact name Minecraft, chat, and the UI can share. */
	public static String canonicalPublicName(String requested) {
		String source = Objects.requireNonNull(requested, "requested name must not be null").strip();
		StringBuilder safe = new StringBuilder(Math.min(source.length(), PLAYER_NAME_LIMIT));
		boolean separatorPending = false;
		for (int offset = 0; offset < source.length();) {
			int codePoint = source.codePointAt(offset);
			offset += Character.charCount(codePoint);
			boolean asciiLetter = codePoint >= 'A' && codePoint <= 'Z' || codePoint >= 'a' && codePoint <= 'z';
			boolean asciiDigit = codePoint >= '0' && codePoint <= '9';
			if (!asciiLetter && !asciiDigit) {
				separatorPending = safe.length() > 0;
				continue;
			}
			if (separatorPending && safe.charAt(safe.length() - 1) != '_') safe.append('_');
			separatorPending = false;
			safe.appendCodePoint(codePoint);
		}
		while (!safe.isEmpty() && safe.charAt(safe.length() - 1) == '_') safe.setLength(safe.length() - 1);
		if (safe.isEmpty()) safe.append("Agent");
		if (!isAsciiLetter(safe.charAt(0))) safe.insert(0, "Agent_");
		if (safe.length() > PLAYER_NAME_LIMIT) safe.setLength(PLAYER_NAME_LIMIT);
		return safe.toString();
	}

	/** Allocates the shortest stable suffix without leaking a UUID into the public name. */
	public static String allocatePublicName(String requested, Collection<String> unavailableNames) {
		String base = canonicalPublicName(requested);
		Set<String> unavailable = new HashSet<>();
		for (String name : Objects.requireNonNull(unavailableNames, "unavailable names must not be null")) {
			if (name != null && !name.isBlank()) unavailable.add(name.toLowerCase(Locale.ROOT));
		}
		if (!unavailable.contains(base.toLowerCase(Locale.ROOT))) return base;
		String suffixStem = allocatedSuffixStem(base, unavailable);
		if (suffixStem != null) base = suffixStem;
		for (int number = 2; number < Integer.MAX_VALUE; number++) {
			String suffix = Integer.toString(number);
			int prefixLength = PLAYER_NAME_LIMIT - suffix.length();
			if (prefixLength < 1) throw new IllegalStateException("Agent public-name space is exhausted");
			String candidate = base.substring(0, Math.min(base.length(), prefixLength)) + suffix;
			if (!unavailable.contains(candidate.toLowerCase(Locale.ROOT))) return candidate;
		}
		throw new IllegalStateException("Agent public-name space is exhausted");
	}

	private static String allocatedSuffixStem(String value, Set<String> unavailable) {
		int suffixStart = value.length();
		while (suffixStart > 0 && Character.isDigit(value.charAt(suffixStart - 1))) suffixStart--;
		if (suffixStart == 0 || suffixStart == value.length()) return null;
		String stem = value.substring(0, suffixStart);
		return unavailable.contains(stem.toLowerCase(Locale.ROOT)) ? stem : null;
	}

	public static boolean isPublicName(String value) {
		return value != null && PUBLIC_NAME.matcher(value).matches();
	}

	public static String defaultDisplayName(AgentProfile profile) {
		Objects.requireNonNull(profile, "profile must not be null");
		return AgentModelNames.displayName(profile.provider(), profile.model()) + " " + title(profile.reasoning())
				+ " | " + skinName(profile.provider(), profile.skinVariant());
	}

	public static String skinName(String provider, int variant) {
		String[] names = switch (normalizedProvider(provider)) {
			case "gemini" -> new String[]{"Azure", "Crimson", "Solar", "Verdant"};
			case "kimi" -> new String[]{"Moon", "Ice", "Orchid", "Sunrise"};
			default -> new String[]{"Cyan", "Violet", "Emerald", "Amber"};
		};
		return names[Math.floorMod(variant, names.length)];
	}

	public static String playerName(AgentId id, AgentProfile profile) {
		Objects.requireNonNull(id, "id must not be null");
		Objects.requireNonNull(profile, "profile must not be null");
		return profile.userName().map(AgentIdentity::canonicalPublicName)
				.orElseGet(() -> defaultPublicName(profile.provider(), profile.model()));
	}

	/** Returns Minecraft's deterministic offline UUID for the exact technical player name. */
	public static UUID offlinePlayerUuid(String technicalName) {
		String checkedName = Objects.requireNonNull(technicalName, "technicalName must not be null");
		return UUID.nameUUIDFromBytes(("OfflinePlayer:" + checkedName).getBytes(StandardCharsets.UTF_8));
	}

	public static Optional<SkinIdentity> skinForPlayerName(String playerName) {
		Matcher matcher = LEGACY_PLAYER_NAME.matcher(Objects.requireNonNullElse(playerName, ""));
		if (!matcher.matches()) return Optional.empty();
		String identity = matcher.group(1).toLowerCase(Locale.ROOT);
		Optional<AgentVisualIdentity.Resolved> resolved = AgentVisualIdentity.resolveTransportCode(identity);
		if (resolved.isEmpty()) resolved = AgentVisualIdentity.resolveRecognizablePlayerCode(identity);
		if (resolved.isPresent()) {
			AgentVisualIdentity.Resolved value = resolved.orElseThrow();
			return Optional.of(new SkinIdentity(
					value.providerKey(), value.modelFamilyKey(), value.individualVariant()));
		}
		return legacySkin(identity, "codex", CODEX_LEGACY_MODELS, CODEX_LEGACY_VARIANTS)
				.or(() -> legacySkin(identity, "gemini", GEMINI_LEGACY_MODELS, GEMINI_LEGACY_VARIANTS))
				.or(() -> legacySkin(identity, "kimi", KIMI_K3_LEGACY_MODELS, KIMI_K3_LEGACY_VARIANTS))
				.or(() -> legacySkin(identity, "kimi", KIMI_LEGACY_MODELS, KIMI_LEGACY_VARIANTS));
	}

	static String recognizablePlayerCode(String label, int variant) {
		String safe = Objects.requireNonNull(label, "label must not be null").replaceAll("[^A-Za-z0-9]", "");
		if (safe.isEmpty() || !Character.isLetter(safe.charAt(0))) safe = "Agent" + safe;
		return safe.substring(0, Math.min(6, safe.length())) + AgentVisualIdentity.normalizedVariant(variant);
	}

	/** The immediately preceding readable/hash transport name, retained only for one-time body migration. */
	public static String legacyReadablePlayerName(AgentId id, AgentProfile profile) {
		Objects.requireNonNull(id, "id must not be null");
		Objects.requireNonNull(profile, "profile must not be null");
		AgentVisualIdentity.Resolved visual = profile.visualIdentity();
		String identity = profile.userName()
				.map(name -> recognizablePlayerCode(name, visual.individualVariant()))
				.orElseGet(() -> recognizablePlayerCode(visual.shortModelLabel(), visual.individualVariant()));
		return identity + "_" + String.format(Locale.ROOT, "%08X", id.value().hashCode());
	}

	/** All deployed technical handles that may still own an agent's connected body or playerdata. */
	public static List<String> legacyPlayerNames(AgentId id, AgentProfile profile) {
		Objects.requireNonNull(id, "id must not be null");
		Objects.requireNonNull(profile, "profile must not be null");
		return List.of(
				legacyReadablePlayerName(id, profile),
				profile.visualIdentity().transportCode() + "_" + id.shortValue().toUpperCase(Locale.ROOT)
		).stream().distinct().toList();
	}

	private static Optional<SkinIdentity> legacySkin(
			String identity,
			String provider,
			String[] modelTokens,
			String[] variantTokens
	) {
		for (String modelToken : modelTokens) {
			if (!identity.startsWith(modelToken)) continue;
			String variantToken = identity.substring(modelToken.length());
			for (int variant = 0; variant < variantTokens.length; variant++) {
				if (variantToken.equals(variantTokens[variant])) {
					return Optional.of(new SkinIdentity(provider, variant));
				}
			}
		}
		return Optional.empty();
	}

	public record SkinIdentity(String provider, String modelFamily, int variant) {
		public SkinIdentity(String provider, int variant) {
			this(provider, "", variant);
		}

		public SkinIdentity {
			provider = normalizedProvider(provider);
			modelFamily = Objects.requireNonNull(modelFamily, "modelFamily must not be null")
					.toLowerCase(Locale.ROOT);
			if (variant < 0 || variant >= AgentVisualIdentity.INDIVIDUAL_VARIANT_COUNT) {
				throw new IllegalArgumentException("skin variant is out of range");
			}
		}
	}

	private static String normalizedProvider(String provider) {
		return Objects.requireNonNull(provider, "provider must not be null").toLowerCase(Locale.ROOT);
	}

	private static boolean isAsciiLetter(char value) {
		return value >= 'A' && value <= 'Z' || value >= 'a' && value <= 'z';
	}

	private static String title(String value) {
		if (value == null || value.isBlank()) return "";
		String lower = value.toLowerCase(Locale.ROOT);
		return lower.substring(0, 1).toUpperCase(Locale.ROOT) + lower.substring(1);
	}
}
