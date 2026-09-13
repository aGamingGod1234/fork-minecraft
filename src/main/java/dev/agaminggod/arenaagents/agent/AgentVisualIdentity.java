package dev.agaminggod.arenaagents.agent;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;

/** Strict project-owned mapping from provider/model identity to visual and transport identity. */
public final class AgentVisualIdentity {
	public static final int INDIVIDUAL_VARIANT_COUNT = 4;

	private static final String MANIFEST_RESOURCE =
			"assets/arenaagents/identity/agent_visual_manifest.json";
	private static final Set<String> REQUIRED_PROVIDERS = Set.of("codex", "gemini", "kimi", "cursor");
	private static final Set<String> REQUIRED_BRANDS = Set.of("openai", "claude", "deepseek", "gemini", "kimi");
	private static final Pattern KEY = Pattern.compile("[a-z][a-z0-9_]*");
	private static final Pattern TRANSPORT_CODE = Pattern.compile("[a-z][a-z0-9]{1,6}");
	private static final Pattern TEXTURE_PATH = Pattern.compile(
			"arenaagents:textures/entity/[a-z0-9_./-]+\\.png");

	private static final Manifest MANIFEST = loadManifest();

	private AgentVisualIdentity() {
	}

	public static Resolved resolve(String provider, String exactModelSlug, int variant) {
		ProviderIdentity providerIdentity = requireProvider(provider);
		String modelSlug = requireText(exactModelSlug, "model slug").toLowerCase(Locale.ROOT);
		requireVariant(variant);
		FamilyIdentity family = providerIdentity.models().getOrDefault(modelSlug, providerIdentity.fallback());
		return resolved(providerIdentity, family, variant);
	}

	/** Resolves a manifest family key without silently accepting malformed nonblank family data. */
	public static Resolved resolveFamily(String provider, String family, int variant) {
		ProviderIdentity providerIdentity = requireProvider(provider);
		String familyKey = requireText(family, "model family").toLowerCase(Locale.ROOT);
		requireVariant(variant);
		FamilyIdentity familyIdentity = providerIdentity.families().get(familyKey);
		if (familyIdentity == null) {
			throw new IllegalArgumentException(
					"Unsupported model family for provider " + providerIdentity.key() + ": " + familyKey);
		}
		return resolved(providerIdentity, familyIdentity, variant);
	}

	/** Explicit compatibility path for historical transport names that did not encode a family. */
	public static Resolved resolveProviderFallback(String provider, int variant) {
		ProviderIdentity providerIdentity = requireProvider(provider);
		requireVariant(variant);
		return resolved(providerIdentity, providerIdentity.fallback(), variant);
	}

	public static int normalizedVariant(int variant) {
		return Math.floorMod(variant, INDIVIDUAL_VARIANT_COUNT);
	}

	/** Returns the bundled company-brand skin path for a cinematic or custom summon. */
	public static String brandTexturePath(String brand, int variant) {
		String brandKey = requireText(brand, "brand").toLowerCase(Locale.ROOT);
		requireVariant(variant);
		List<String> variants = MANIFEST.brandSkinPaths().get(brandKey);
		if (variants == null) throw new IllegalArgumentException("Unsupported brand skin: " + brandKey);
		return variants.get(variant);
	}

	/** Selects the company-brand texture used by every client-side agent render. */
	public static String renderTexturePath(Resolved identity) {
		Objects.requireNonNull(identity, "identity must not be null");
		String brand = switch (identity.providerKey()) {
			case "codex" -> "openai";
			case "gemini" -> identity.modelFamilyKey().equals("claude") ? "claude" : "gemini";
			case "kimi" -> "kimi";
			default -> null;
		};
		return brand == null ? identity.texturePath() : brandTexturePath(brand, identity.individualVariant());
	}

	private static Resolved resolved(
			ProviderIdentity providerIdentity,
			FamilyIdentity family,
			int variant
	) {
		VariantIdentity visualVariant = family.variants().get(variant);
		return new Resolved(
				providerIdentity.key(),
				providerIdentity.chassis(),
				family.key(),
				variant,
				visualVariant.texturePath(),
				family.shortLabel(),
				providerIdentity.glyph(),
				visualVariant.transportCode()
		);
	}

	private static ProviderIdentity requireProvider(String provider) {
		String providerKey = requireText(provider, "provider").toLowerCase(Locale.ROOT);
		ProviderIdentity providerIdentity = MANIFEST.providers().get(providerKey);
		if (providerIdentity == null) throw new IllegalArgumentException("Unsupported provider: " + providerKey);
		return providerIdentity;
	}

	private static void requireVariant(int variant) {
		if (variant < 0 || variant >= INDIVIDUAL_VARIANT_COUNT) {
			throw new IllegalArgumentException("individual variant must be between 0 and 3");
		}
	}

	public static Optional<Resolved> resolveTransportCode(String code) {
		if (code == null || code.isBlank()) return Optional.empty();
		TransportIdentity identity = MANIFEST.transportCodes().get(code.toLowerCase(Locale.ROOT));
		if (identity == null) return Optional.empty();
		ProviderIdentity provider = identity.provider();
		FamilyIdentity family = identity.family();
		VariantIdentity variant = family.variants().get(identity.variant());
		return Optional.of(new Resolved(
				provider.key(),
				provider.chassis(),
				family.key(),
				identity.variant(),
				variant.texturePath(),
				family.shortLabel(),
				provider.glyph(),
				variant.transportCode()
		));
	}

	/** Resolves the readable model-plus-variant prefix used by Minecraft player names. */
	public static Optional<Resolved> resolveRecognizablePlayerCode(String code) {
		if (code == null || code.isBlank()) return Optional.empty();
		String checked = code.toLowerCase(Locale.ROOT);
		Resolved match = null;
		for (TransportIdentity transport : MANIFEST.transportCodes().values()) {
			Resolved candidate = resolved(transport.provider(), transport.family(), transport.variant());
			String candidateCode = AgentIdentity.recognizablePlayerCode(
					candidate.shortModelLabel(), candidate.individualVariant()).toLowerCase(Locale.ROOT);
			if (!candidateCode.equals(checked)) continue;
			if (match != null && !match.equals(candidate)) return Optional.empty();
			match = candidate;
		}
		return Optional.ofNullable(match);
	}

	static void validateManifest(String encoded) {
		parseManifest(requireObject(JsonParser.parseString(requireText(encoded, "manifest")), "manifest"));
	}

	private static Manifest loadManifest() {
		try (InputStream stream = AgentVisualIdentity.class.getClassLoader().getResourceAsStream(MANIFEST_RESOURCE)) {
			if (stream == null) throw invalid("missing classpath resource " + MANIFEST_RESOURCE);
			JsonElement parsed = JsonParser.parseReader(new InputStreamReader(stream, StandardCharsets.UTF_8));
			return parseManifest(requireObject(parsed, "manifest"));
		} catch (IOException exception) {
			throw new ExceptionInInitializerError(exception);
		} catch (RuntimeException exception) {
			throw new ExceptionInInitializerError(exception);
		}
	}

	private static Manifest parseManifest(JsonObject root) {
		requireKeys(root, Set.of("schemaVersion", "brandSkins", "providers"), "manifest");
		if (requiredInt(root, "schemaVersion", "manifest") != 1) {
			throw invalid("manifest schemaVersion must be 1");
		}
		JsonArray providers = requiredArray(root, "providers", "manifest");
		if (providers.size() != REQUIRED_PROVIDERS.size()) {
			throw invalid("manifest must declare exactly four providers");
		}

		Map<String, ProviderIdentity> byProvider = new LinkedHashMap<>();
		Map<String, TransportIdentity> byTransportCode = new HashMap<>();
		Set<String> texturePaths = new HashSet<>();
		for (JsonElement providerElement : providers) {
			ProviderIdentity provider = parseProvider(
					requireObject(providerElement, "provider"), byTransportCode, texturePaths);
			if (byProvider.putIfAbsent(provider.key(), provider) != null) {
				throw invalid("duplicate provider: " + provider.key());
			}
		}
		if (!byProvider.keySet().equals(REQUIRED_PROVIDERS)) {
			throw invalid("manifest providers must be exactly " + REQUIRED_PROVIDERS);
		}
		Map<String, List<String>> brandSkinPaths = parseBrandSkins(root.getAsJsonArray("brandSkins"));
		return new Manifest(Map.copyOf(byProvider), Map.copyOf(byTransportCode), brandSkinPaths);
	}

	private static Map<String, List<String>> parseBrandSkins(JsonArray brands) {
		if (brands.size() != REQUIRED_BRANDS.size()) {
			throw invalid("brandSkins must declare exactly " + REQUIRED_BRANDS.size() + " brands");
		}
		Map<String, List<String>> pathsByBrand = new LinkedHashMap<>();
		Set<String> texturePaths = new HashSet<>();
		for (JsonElement brandElement : brands) {
			JsonObject brand = requireObject(brandElement, "brand skin");
			requireKeys(brand, Set.of("key", "label", "variants"), "brand skin");
			String key = requiredKey(brand, "key", "brand skin");
			if (!REQUIRED_BRANDS.contains(key)) throw invalid("unsupported brand skin: " + key);
			if (pathsByBrand.containsKey(key)) throw invalid("duplicate brand skin: " + key);
			requiredString(brand, "label", "brand skin " + key);
			JsonArray variants = requiredArray(brand, "variants", "brand skin " + key);
			if (variants.size() != INDIVIDUAL_VARIANT_COUNT) {
				throw invalid("brand skin " + key + " must declare exactly four variants");
			}
			List<String> paths = new ArrayList<>(INDIVIDUAL_VARIANT_COUNT);
			for (JsonElement variantElement : variants) {
				JsonObject variant = requireObject(variantElement, "brand skin variant");
				requireKeys(variant, Set.of("texturePath"), "brand skin variant");
				String texturePath = requiredString(variant, "texturePath", "brand skin variant");
				if (!TEXTURE_PATH.matcher(texturePath).matches()
						|| !texturePath.startsWith("arenaagents:textures/entity/brand_")) {
					throw invalid("brand skin texture must be project-owned: " + texturePath);
				}
				if (!texturePaths.add(texturePath)) throw invalid("duplicate texture path: " + texturePath);
				paths.add(texturePath);
			}
			pathsByBrand.put(key, List.copyOf(paths));
		}
		if (!pathsByBrand.keySet().equals(REQUIRED_BRANDS)) {
			throw invalid("brand skins must be exactly " + REQUIRED_BRANDS);
		}
		return Map.copyOf(pathsByBrand);
	}

	private static ProviderIdentity parseProvider(
			JsonObject object,
			Map<String, TransportIdentity> byTransportCode,
			Set<String> texturePaths
	) {
		requireKeys(object, Set.of("key", "chassis", "glyph", "fallbackFamily", "families"), "provider");
		String key = requiredKey(object, "key", "provider");
		String chassis = requiredKey(object, "chassis", "provider " + key);
		String glyph = requiredString(object, "glyph", "provider " + key);
		if (glyph.codePointCount(0, glyph.length()) != 1) {
			throw invalid("provider " + key + " glyph must be one code point");
		}
		String fallbackFamily = requiredKey(object, "fallbackFamily", "provider " + key);
		JsonArray families = requiredArray(object, "families", "provider " + key);
		if (families.size() != 4) throw invalid("provider " + key + " must declare exactly four families");

		Map<String, FamilyIdentity> byFamily = new LinkedHashMap<>();
		Map<String, FamilyIdentity> byModel = new HashMap<>();
		List<PendingTransportIdentity> transportIdentities = new ArrayList<>();
		for (JsonElement familyElement : families) {
			FamilyIdentity family = parseFamily(key, requireObject(familyElement, "family"),
					transportIdentities, texturePaths);
			if (byFamily.putIfAbsent(family.key(), family) != null) {
				throw invalid("duplicate family " + family.key() + " for provider " + key);
			}
			for (String model : family.models()) {
				if (byModel.putIfAbsent(model, family) != null) {
					throw invalid("duplicate model " + model + " for provider " + key);
				}
			}
		}
		FamilyIdentity fallback = byFamily.get(fallbackFamily);
		if (fallback == null) throw invalid("provider " + key + " has an unknown fallback family");
		ProviderIdentity provider = new ProviderIdentity(
				key, chassis, glyph, Map.copyOf(byFamily), Map.copyOf(byModel), fallback);
		for (PendingTransportIdentity pending : transportIdentities) {
			TransportIdentity identity = new TransportIdentity(provider, pending.family(), pending.variant());
			if (byTransportCode.putIfAbsent(pending.code(), identity) != null) {
				throw invalid("duplicate transport code: " + pending.code());
			}
		}
		return provider;
	}

	private static FamilyIdentity parseFamily(
			String provider,
			JsonObject object,
			List<PendingTransportIdentity> transportIdentities,
			Set<String> texturePaths
	) {
		requireKeys(object, Set.of("key", "models", "shortLabel", "variants"), "family");
		String key = requiredKey(object, "key", "family");
		String shortLabel = requiredString(object, "shortLabel", "family " + key);
		JsonArray models = requiredArray(object, "models", "family " + key);
		Set<String> modelSlugs = new HashSet<>();
		for (JsonElement modelElement : models) {
			if (!modelElement.isJsonPrimitive() || !modelElement.getAsJsonPrimitive().isString()) {
				throw invalid("family " + key + " model must be a string");
			}
			String model = requireText(modelElement.getAsString(), "model slug").toLowerCase(Locale.ROOT);
			if (!modelSlugs.add(model)) throw invalid("duplicate model " + model + " in family " + key);
		}

		JsonArray variants = requiredArray(object, "variants", "family " + key);
		if (variants.size() != INDIVIDUAL_VARIANT_COUNT) {
			throw invalid("family " + key + " must declare exactly four variants");
		}
		List<VariantIdentity> variantIdentities = new ArrayList<>(INDIVIDUAL_VARIANT_COUNT);
		for (JsonElement variantElement : variants) {
			JsonObject variantObject = requireObject(variantElement, "variant");
			requireKeys(variantObject, Set.of("texturePath", "transportCode"), "variant");
			String texturePath = requiredString(variantObject, "texturePath", "variant");
			if (!TEXTURE_PATH.matcher(texturePath).matches()
					|| !texturePath.startsWith("arenaagents:textures/entity/" + provider + "_")) {
				throw invalid("texture path must be a project-owned " + provider + " entity texture: " + texturePath);
			}
			if (!texturePaths.add(texturePath)) throw invalid("duplicate texture path: " + texturePath);
			String transportCode = requiredString(variantObject, "transportCode", "variant")
					.toLowerCase(Locale.ROOT);
			if (!TRANSPORT_CODE.matcher(transportCode).matches()) {
				throw invalid("invalid transport code: " + transportCode);
			}
			variantIdentities.add(new VariantIdentity(texturePath, transportCode));
		}
		FamilyIdentity family = new FamilyIdentity(
				key, Set.copyOf(modelSlugs), shortLabel, List.copyOf(variantIdentities));
		for (int variant = 0; variant < variantIdentities.size(); variant++) {
			transportIdentities.add(new PendingTransportIdentity(
					variantIdentities.get(variant).transportCode(), family, variant));
		}
		return family;
	}

	private static JsonObject requireObject(JsonElement value, String label) {
		if (value == null || !value.isJsonObject()) throw invalid(label + " must be an object");
		return value.getAsJsonObject();
	}

	private static JsonArray requiredArray(JsonObject object, String key, String label) {
		JsonElement value = object.get(key);
		if (value == null || !value.isJsonArray()) throw invalid(label + "." + key + " must be an array");
		return value.getAsJsonArray();
	}

	private static int requiredInt(JsonObject object, String key, String label) {
		JsonElement value = object.get(key);
		if (value == null || !value.isJsonPrimitive() || !value.getAsJsonPrimitive().isNumber()) {
			throw invalid(label + "." + key + " must be an integer");
		}
		try {
			return value.getAsBigDecimal().intValueExact();
		} catch (ArithmeticException | NumberFormatException exception) {
			throw invalid(label + "." + key + " must be an integer");
		}
	}

	private static String requiredKey(JsonObject object, String key, String label) {
		String value = requiredString(object, key, label);
		if (!KEY.matcher(value).matches()) throw invalid(label + "." + key + " is invalid: " + value);
		return value;
	}

	private static String requiredString(JsonObject object, String key, String label) {
		JsonElement value = object.get(key);
		if (value == null || !value.isJsonPrimitive() || !value.getAsJsonPrimitive().isString()) {
			throw invalid(label + "." + key + " must be a string");
		}
		return requireText(value.getAsString(), label + "." + key);
	}

	private static void requireKeys(JsonObject object, Set<String> allowed, String label) {
		if (!object.keySet().equals(allowed)) {
			Set<String> unknown = new HashSet<>(object.keySet());
			unknown.removeAll(allowed);
			Set<String> missing = new HashSet<>(allowed);
			missing.removeAll(object.keySet());
			throw invalid(label + " has unknown keys " + unknown + " and missing keys " + missing);
		}
	}

	private static String requireText(String value, String label) {
		String checked = Objects.requireNonNull(value, label + " must not be null").trim();
		if (checked.isEmpty()) throw invalid(label + " must not be blank");
		return checked;
	}

	private static IllegalStateException invalid(String message) {
		return new IllegalStateException("Invalid agent visual manifest: " + message);
	}

	public record Resolved(
			String providerKey,
			String providerChassis,
			String modelFamilyKey,
			int individualVariant,
			String texturePath,
			String shortModelLabel,
			String providerGlyph,
			String transportCode
	) {
	}

	private record Manifest(
		Map<String, ProviderIdentity> providers,
		Map<String, TransportIdentity> transportCodes,
		Map<String, List<String>> brandSkinPaths
	) {
	}

	private record ProviderIdentity(
			String key,
			String chassis,
			String glyph,
			Map<String, FamilyIdentity> families,
			Map<String, FamilyIdentity> models,
			FamilyIdentity fallback
	) {
	}

	private record FamilyIdentity(
			String key,
			Set<String> models,
			String shortLabel,
			List<VariantIdentity> variants
	) {
	}

	private record VariantIdentity(String texturePath, String transportCode) {
	}

	private record PendingTransportIdentity(String code, FamilyIdentity family, int variant) {
	}

	private record TransportIdentity(ProviderIdentity provider, FamilyIdentity family, int variant) {
	}
}
