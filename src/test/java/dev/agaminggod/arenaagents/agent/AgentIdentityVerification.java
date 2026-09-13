package dev.agaminggod.arenaagents.agent;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;

public final class AgentIdentityVerification {
	private AgentIdentityVerification() {
	}

	public static int verify() {
		AgentId id = new AgentId(UUID.fromString("193a9add-1234-5678-9abc-123456789abc"));
		for (String brand : List.of("openai", "claude", "deepseek", "gemini", "kimi")) {
			for (int variant = 0; variant < AgentVisualIdentity.INDIVIDUAL_VARIANT_COUNT; variant++) {
				String texturePath = AgentVisualIdentity.brandTexturePath(brand, variant);
				assertEquals("arenaagents:textures/entity/brand_" + brand + "_agent_" + variant + ".png",
						texturePath, "brand skin path is stable for " + brand);
				assertRgbaSkin(readTexture(texturePath), texturePath, 512);
			}
		}
		expectIllegalArgument(() -> AgentVisualIdentity.brandTexturePath("unknown", 0),
				"unknown company brand skin is rejected");
		expectIllegalArgument(() -> AgentVisualIdentity.brandTexturePath("openai", 4),
				"company brand skin variant stays within the four persisted variants");
		AgentProfile sol = new AgentProfile("codex", "gpt-5.6-sol", "high", "fast", Optional.empty(), 2,
				AgentGameMode.SURVIVAL);
		assertEquals("GPT_5_6_Sol", AgentIdentity.displayName(id, sol),
				"unnamed agents use a readable Minecraft-safe public name");
		AgentId samePrefixId = new AgentId(UUID.fromString("193a9add-2222-2222-9abc-123456789abc"));
		assertEquals(AgentIdentity.displayName(id, sol), AgentIdentity.displayName(samePrefixId, sol),
				"the registry, not a UUID hash, owns same-model collision suffixes");
		assertEquals("GPT_5_6_Sol", AgentIdentity.playerName(id, sol),
				"fake-player username is the same public name shown everywhere else");
		assertEquals("GPT 5.6-Sol", AgentIdentity.displayNameTag(sol),
				"generated model names use readable punctuation in the visible name tag");
		assertEquals(List.of("Sol2_C7CA442D", "c02_193A9ADD"), AgentIdentity.legacyPlayerNames(id, sol),
				"both previously deployed technical handles remain discoverable for state migration");
		assertTrue(AgentIdentity.playerName(id, sol).length() <= 16,
				"fake-player username stays within Minecraft's limit");
		assertTrue(AgentIdentity.playerName(id, sol).matches("[A-Za-z0-9_]+"),
				"fake-player username uses Minecraft-safe characters");
		String currentTechnicalName = "c02_193A9ADD";
		UUID currentOfflineUuid = AgentIdentity.offlinePlayerUuid(currentTechnicalName);
		assertEquals(UUID.fromString("6eb46a0e-9bd1-33e1-8fa7-d1280f08577c"), currentOfflineUuid,
				"current transport name derives the exact Minecraft offline UUID");
		assertTrue(!UUID.fromString("aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee").equals(currentOfflineUuid),
				"current transport-looking name with a genuine player UUID fails provenance");
		String legacyTechnicalName = "SolEmer_193A9ADD";
		UUID legacyOfflineUuid = AgentIdentity.offlinePlayerUuid(legacyTechnicalName);
		assertEquals(UUID.fromString("c0bbfc41-28e0-331c-9c45-53e6a002905e"), legacyOfflineUuid,
				"legacy transport name derives the exact Minecraft offline UUID");
		assertTrue(!UUID.fromString("11111111-2222-3333-8444-555555555555").equals(legacyOfflineUuid),
				"legacy transport-looking name with a genuine player UUID fails provenance");
		assertEquals(new AgentIdentity.SkinIdentity("codex", "sol", 2),
				AgentIdentity.skinForPlayerName("c02_193A9ADD").orElseThrow(),
				"manifest player names expose the exact family and variant before the first client snapshot");
		assertEquals(new AgentIdentity.SkinIdentity("codex", "sol", 2),
				AgentIdentity.skinForPlayerName("Sol2_193A9ADD").orElseThrow(),
				"recognizable player names retain the exact family and variant before the first client snapshot");
		AgentIdentity.SkinIdentity currentTransport = AgentIdentity.skinForPlayerName("r22_193A9ADD").orElseThrow();
		AgentVisualIdentity.Resolved currentTransportResolved = AgentVisualIdentity.resolveFamily(
				currentTransport.provider(), currentTransport.modelFamily(), currentTransport.variant());
		assertEquals("grok_46", currentTransportResolved.modelFamilyKey(),
				"current transport identity retains its exact manifest family");
		assertEquals("arenaagents:textures/entity/cursor_grok_46_agent_2.png",
				currentTransportResolved.texturePath(),
				"current transport identity resolves the exact family and individual texture");
		assertEquals(new AgentIdentity.SkinIdentity("codex", 2),
				AgentIdentity.skinForPlayerName("SolEmer_193A9ADD").orElseThrow(),
				"legacy player names retain pre-snapshot skin fallback");
		AgentIdentity.SkinIdentity legacyTransport = AgentIdentity.skinForPlayerName("SolEmer_193A9ADD").orElseThrow();
		assertEquals("", legacyTransport.modelFamily(), "historical player names carry an explicit blank family");
		AgentVisualIdentity.Resolved legacyFallback = AgentVisualIdentity.resolveProviderFallback(
				legacyTransport.provider(), legacyTransport.variant());
		assertEquals("spark", legacyFallback.modelFamilyKey(),
				"historical blank-family Codex names use the documented provider fallback");
		assertEquals("arenaagents:textures/entity/codex_spark_agent_2.png", legacyFallback.texturePath(),
				"historical blank-family fallback keeps its individual variant");
		expectIllegalArgument(
				() -> AgentVisualIdentity.resolveFamily("cursor", "not_a_family", 2),
				"malformed nonblank family is rejected instead of becoming a provider fallback"
		);
		assertEquals(new AgentIdentity.SkinIdentity("kimi", 2),
				AgentIdentity.skinForPlayerName("K3Orch_193A9ADD").orElseThrow(),
				"known digit-bearing legacy K3 names retain pre-snapshot skin fallback");
		assertTrue(AgentIdentity.skinForPlayerName("a1ice_12345678").isEmpty(),
				"ordinary digit-bearing names with a legacy token suffix are rejected");
		assertTrue(AgentIdentity.skinForPlayerName("ordinary_player").isEmpty(),
				"ordinary player names are not mistaken for arena identities");
		AgentProfile kimiLong = new AgentProfile(
				"kimi", "kimi-code/k3-256k", "max", "priority", Optional.empty(), 1, AgentGameMode.SURVIVAL);
		assertEquals("Kimi_K3_256K", AgentIdentity.playerName(id, kimiLong),
				"digit-bearing model families remain readable and Minecraft-safe");
		AgentProfile legacyVariant = new AgentProfile(
				"codex", "gpt-5.6-sol", "high", "priority", Optional.empty(), 6, AgentGameMode.SURVIVAL);
		assertEquals("GPT_5_6_Sol", AgentIdentity.playerName(id, legacyVariant),
				"legacy persisted variants normalize through the canonical manifest count");
		assertEquals(2, AgentVisualIdentity.normalizedVariant(6),
				"legacy entity variants normalize through the manifest-owned variant count");
		assertEquals(3, AgentVisualIdentity.normalizedVariant(-1),
				"negative legacy entity variants normalize through the manifest-owned variant count");
		AgentProfile named = new AgentProfile("codex", "gpt-5.6-sol", "low", "priority", Optional.of("Rook"), 0,
				AgentGameMode.SURVIVAL);
		assertEquals("Rook", AgentIdentity.displayName(id, named), "explicit names remain authoritative");
		assertEquals("Rook", AgentIdentity.displayNameTag(named), "explicit names remain authoritative in visible tags");
		assertEquals("Rook", AgentIdentity.playerName(id, named), "explicit names are exact /msg targets");
		assertEquals("Rook0_C7CA442D", AgentIdentity.legacyReadablePlayerName(id, named),
				"the immediately preceding readable/hash handle remains deterministic after canonicalization");
		assertEquals(Optional.of("Rook"), AgentIdentity.worldTag(named), "explicit world tag has no provider decoration");
		assertEquals(Optional.of("GPT_5_6_Sol"), AgentIdentity.worldTag(sol),
				"unnamed agents expose the same public name in the world");
		assertEquals("Builder_One", AgentIdentity.canonicalPublicName(" Builder One "),
				"spaces become Minecraft-safe separators");
		assertEquals("Agent_42", AgentIdentity.canonicalPublicName("42"),
				"public names always begin with a letter");
		assertEquals("GPT_5_6_Sol2", AgentIdentity.allocatePublicName(
				"GPT 5.6 Sol", List.of("gpt_5_6_sol")),
				"the smallest case-insensitive numeric suffix resolves a collision");
		assertEquals("GPT_5_6_Sol3", AgentIdentity.allocatePublicName(
				"GPT_5_6_Sol2", List.of("GPT_5_6_Sol", "GPT_5_6_Sol2")),
				"already allocated scenario suffixes advance instead of nesting suffixes");
		assertEquals("ExactlyFifteenC2", AgentIdentity.allocatePublicName(
				"ExactlyFifteenChars", List.of("ExactlyFifteenCh")),
				"collision suffixes retain the sixteen-character Minecraft bound");

		assertEquals("GPT 5.6 Sol WM", AgentModelNames.displayName("codex", "gpt-5.6-sol-wm"),
				"Codex display name is canonical");
		assertEquals("Sol WM", AgentModelNames.shortLabel("codex", "gpt-5.6-sol-wm"),
				"Codex short label is canonical");
		assertEquals("Gemini 3.1 Pro", AgentModelNames.displayName("gemini", "gemini-3.1-pro"),
				"Gemini display name is canonical");
		assertEquals("K2.7 Coding Highspeed",
				AgentModelNames.displayName("kimi", "kimi-code/kimi-for-coding-highspeed"),
				"Kimi display name is canonical");
		assertEquals("Composer 2.5", AgentModelNames.displayName("cursor", "composer-2.5"),
				"Cursor display name is canonical");

		List<ModelCase> models = List.of(
				new ModelCase("codex", "gpt-5.6-sol", "codex", "sol"),
				new ModelCase("codex", "gpt-5.6-terra", "codex", "terra"),
				new ModelCase("codex", "gpt-5.6-luna", "codex", "luna"),
				new ModelCase("codex", "gpt-5.3-codex-spark", "codex", "spark"),
				new ModelCase("gemini", "gemini-3.1-pro", "gemini", "pro"),
				new ModelCase("gemini", "gemini-3.6-flash", "gemini", "flash"),
				new ModelCase("gemini", "claude-sonnet-4-6", "gemini", "claude"),
				new ModelCase("gemini", "gpt-oss-120b", "gemini", "oss"),
				new ModelCase("kimi", "kimi-code/k3", "kimi", "k3"),
				new ModelCase("kimi", "kimi-code/k3-256k", "kimi", "k3_long"),
				new ModelCase("kimi", "kimi-code/kimi-for-coding", "kimi", "coding"),
				new ModelCase("kimi", "kimi-code/kimi-for-coding-highspeed", "kimi", "coding_fast"),
				new ModelCase("cursor", "composer-2.5", "cursor", "composer"),
				new ModelCase("cursor", "grok-4.5", "cursor", "grok_45"),
				new ModelCase("cursor", "grok-4.6", "cursor", "grok_46"),
				new ModelCase("cursor", "cursor-next", "cursor", "cursor_next")
		);
		Set<String> transportCodes = new HashSet<>();
		Set<String> texturePaths = new HashSet<>();
		Map<String, Set<String>> textureBytesByProvider = new HashMap<>();
		for (ModelCase model : models) {
			for (int variant = 0; variant < AgentVisualIdentity.INDIVIDUAL_VARIANT_COUNT; variant++) {
				AgentVisualIdentity.Resolved resolved = AgentVisualIdentity.resolve(model.provider(), model.slug(), variant);
				assertEquals(model.provider(), resolved.providerKey(), "provider identity remains distinct");
				assertEquals(model.chassis(), resolved.providerChassis(), "provider chassis remains distinct");
				assertEquals(model.family(), resolved.modelFamilyKey(), "model resolves to its named family slot");
				assertEquals(variant, resolved.individualVariant(), "all four individual variants resolve");
				String expectedBrand = model.provider().equals("codex") ? "openai"
						: model.provider().equals("kimi") ? "kimi"
						: model.provider().equals("gemini") && model.family().equals("claude") ? "claude"
						: model.provider().equals("gemini") ? "gemini" : null;
				if (expectedBrand != null) {
					assertEquals(AgentVisualIdentity.brandTexturePath(expectedBrand, variant),
							AgentVisualIdentity.renderTexturePath(resolved),
							"provider render selects the persistent " + expectedBrand + " brand skin");
				}
				assertTrue(resolved.texturePath().startsWith(
						"arenaagents:textures/entity/" + model.provider() + "_"),
						"texture stays in the provider's project namespace");
				assertTrue(transportCodes.add(resolved.transportCode()), "transport codes are globally unique");
				assertEquals(resolved,
						AgentVisualIdentity.resolveTransportCode(resolved.transportCode()).orElseThrow(),
						"transport identity round-trips");
				String recognizableCode = AgentIdentity.recognizablePlayerCode(
						resolved.shortModelLabel(), resolved.individualVariant());
				assertEquals(resolved,
						AgentVisualIdentity.resolveRecognizablePlayerCode(recognizableCode).orElseThrow(),
						"recognizable player prefix round-trips without losing its skin");
				assertTrue(texturePaths.add(resolved.texturePath()), "manifest texture paths are globally unique");
				byte[] textureBytes = readTexture(resolved.texturePath());
				assertRgbaSkin(textureBytes, resolved.texturePath(), model.provider().equals("cursor") ? 64 : 512);
				String locatorIcon = "assets/arenaagents/textures/gui/sprites/hud/locator_bar_dot/agent/"
						+ resolved.transportCode() + ".png";
				byte[] locatorBytes = readResource(locatorIcon);
				assertEquals(16, readBigEndianInt(locatorBytes, 16), "locator face sprite has 16px width");
				assertEquals(16, readBigEndianInt(locatorBytes, 20), "locator face sprite has 16px height");
				String locatorStyle = "assets/arenaagents/waypoint_style/agent/"
						+ resolved.transportCode() + ".json";
				assertTrue(new String(readResource(locatorStyle), StandardCharsets.UTF_8)
						.contains("arenaagents:agent/" + resolved.transportCode()),
						"locator style points at the matching face sprite");
				assertTrue(textureBytesByProvider
						.computeIfAbsent(resolved.providerKey(), ignored -> new HashSet<>())
						.add(Base64.getEncoder().encodeToString(textureBytes)),
						"provider textures are byte-distinct");
			}
		}
		assertEquals(64, texturePaths.size(), "manifest resolves exactly 64 agent texture artifacts");

		AgentVisualIdentity.Resolved kimiK3 = AgentVisualIdentity.resolve("kimi", "kimi-code/k3", 0);
		AgentVisualIdentity.Resolved kimiK3256 = AgentVisualIdentity.resolve("kimi", "kimi-code/k3-256k", 0);
		assertEquals("K3", kimiK3.shortModelLabel(), "Kimi digit-bearing K3 label is preserved");
		assertEquals("K3 256K", kimiK3256.shortModelLabel(), "Kimi digit-bearing K3 256K label is preserved");
		assertTrue(!kimiK3.transportCode().equals(kimiK3256.transportCode()),
				"Kimi digit-bearing families keep distinct transport identities");

		AgentVisualIdentity.Resolved cursor = AgentVisualIdentity.resolve("cursor", "composer-1.5", 2);
		assertEquals("cursor", cursor.providerKey(), "Cursor keeps its provider identity");
		assertEquals("cursor", cursor.providerChassis(), "Cursor keeps its distinct chassis");
		assertTrue(cursor.texturePath().contains("cursor_"), "Cursor never uses Codex art");
		assertEquals(cursor, AgentVisualIdentity.resolveTransportCode(cursor.transportCode()).orElseThrow(),
				"Cursor transport identity round-trips");

		AgentVisualIdentity.Resolved unknown = AgentVisualIdentity.resolve("codex", "future-research-model-9", 3);
		assertEquals("spark", unknown.modelFamilyKey(), "unknown model uses the declared provider fallback family");
		assertEquals(unknown, AgentVisualIdentity.resolve("codex", "future-research-model-9", 3),
				"unknown model fallback is deterministic");
		assertTrue(AgentVisualIdentity.resolveTransportCode("not-a-transport-code").isEmpty(),
				"unknown transport identity is rejected");

		expectInvalidManifest(manifestWith(root -> root.addProperty("schemaVersion", 1.5D)),
				"schemaVersion must be an integer", "fractional manifest schema rejected");
		expectInvalidManifest(manifestWith(root -> root.addProperty("schemaVersion", 2_147_483_648L)),
				"schemaVersion must be an integer", "out-of-range manifest schema rejected");
		expectInvalidManifest(manifestWith(root -> root.addProperty("schemaVersion", 2)),
				"schemaVersion must be 1", "wrong manifest schema rejected");
		expectInvalidManifest(manifestWith(root -> root.addProperty("unexpected", true)),
				"unknown keys [unexpected]", "unknown manifest key rejected");
		expectInvalidManifest(manifestWith(root -> {
			JsonObject cursorProvider = root.getAsJsonArray("providers").get(3).getAsJsonObject();
			cursorProvider.addProperty("key", "codex");
			cursorProvider.getAsJsonArray("families").forEach(family ->
					family.getAsJsonObject().getAsJsonArray("variants").forEach(variant -> {
						JsonObject value = variant.getAsJsonObject();
						value.addProperty("texturePath", value.get("texturePath").getAsString()
								.replace("cursor_", "codex_"));
					}));
		}), "duplicate provider: codex", "duplicate manifest provider rejected");
		expectInvalidManifest(manifestWith(root -> root.getAsJsonArray("providers").get(0).getAsJsonObject()
				.getAsJsonArray("families").get(1).getAsJsonObject().addProperty("key", "sol")),
				"duplicate family sol", "duplicate manifest family rejected");
		expectInvalidManifest(manifestWith(root -> {
			var variants = root.getAsJsonArray("providers").get(0).getAsJsonObject()
					.getAsJsonArray("families").get(0).getAsJsonObject().getAsJsonArray("variants");
			variants.get(1).getAsJsonObject().addProperty("transportCode",
					variants.get(0).getAsJsonObject().get("transportCode").getAsString());
		}), "duplicate transport code: c00", "duplicate manifest transport code rejected");
		expectInvalidManifest(manifestWith(root -> root.getAsJsonArray("providers").get(0).getAsJsonObject()
				.getAsJsonArray("families").get(0).getAsJsonObject().getAsJsonArray("variants").remove(3)),
				"must declare exactly four variants", "invalid manifest variant count rejected");
		expectInvalidManifest(manifestWith(root -> root.getAsJsonArray("providers").get(0).getAsJsonObject()
				.getAsJsonArray("families").get(0).getAsJsonObject().getAsJsonArray("variants").get(0)
				.getAsJsonObject().addProperty("texturePath", "minecraft:textures/entity/stolen.png")),
				"project-owned codex entity texture", "non-project manifest texture rejected");
		return 1150;
	}

	private static byte[] readTexture(String texturePath) {
		String[] location = texturePath.split(":", 2);
		String resourcePath = "assets/" + location[0] + "/" + location[1];
		return readResource(resourcePath);
	}

	private static byte[] readResource(String resourcePath) {
		try (var stream = AgentIdentityVerification.class.getClassLoader().getResourceAsStream(resourcePath)) {
			if (stream == null) throw new AssertionError("resource is missing: " + resourcePath);
			return stream.readAllBytes();
		} catch (IOException exception) {
			throw new AssertionError("resource could not be read: " + resourcePath, exception);
		}
	}

	private static void assertRgbaSkin(byte[] bytes, String texturePath, int expectedSize) {
		assertTrue(bytes.length >= 29, texturePath + " contains a complete PNG header");
		assertTrue(bytes[0] == (byte) 137 && bytes[1] == 80 && bytes[2] == 78 && bytes[3] == 71
				&& bytes[4] == 13 && bytes[5] == 10 && bytes[6] == 26 && bytes[7] == 10,
				texturePath + " has a PNG signature");
		assertEquals(13, readBigEndianInt(bytes, 8), texturePath + " has a complete IHDR payload");
		assertEquals("IHDR", new String(bytes, 12, 4, StandardCharsets.US_ASCII),
				texturePath + " begins with IHDR");
		assertEquals(expectedSize, readBigEndianInt(bytes, 16), texturePath + " has expected width");
		assertEquals(expectedSize, readBigEndianInt(bytes, 20), texturePath + " has expected height");
		assertEquals(8, Byte.toUnsignedInt(bytes[24]), texturePath + " uses 8-bit channels");
		assertEquals(6, Byte.toUnsignedInt(bytes[25]), texturePath + " uses RGBA color");
	}

	private static int readBigEndianInt(byte[] bytes, int offset) {
		return (Byte.toUnsignedInt(bytes[offset]) << 24)
				| (Byte.toUnsignedInt(bytes[offset + 1]) << 16)
				| (Byte.toUnsignedInt(bytes[offset + 2]) << 8)
				| Byte.toUnsignedInt(bytes[offset + 3]);
	}

	private static String manifestWith(Consumer<JsonObject> mutation) {
		try (var stream = AgentIdentityVerification.class.getClassLoader().getResourceAsStream(
				"assets/arenaagents/identity/agent_visual_manifest.json")) {
			if (stream == null) throw new AssertionError("agent visual manifest fixture is missing");
			JsonObject root = JsonParser.parseString(new String(stream.readAllBytes(), StandardCharsets.UTF_8))
					.getAsJsonObject();
			mutation.accept(root);
			return root.toString();
		} catch (IOException exception) {
			throw new AssertionError("agent visual manifest fixture could not be read", exception);
		}
	}

	private static void expectInvalidManifest(String manifest, String expectedMessagePart, String label) {
		String firstMessage = invalidManifestMessage(manifest, label);
		String secondMessage = invalidManifestMessage(manifest, label);
		assertTrue(firstMessage.contains(expectedMessagePart), label + " reports its cause");
		assertEquals(firstMessage, secondMessage, label + " is stable");
	}

	private static String invalidManifestMessage(String manifest, String label) {
		try {
			AgentVisualIdentity.validateManifest(manifest);
		} catch (IllegalStateException exception) {
			return exception.getMessage();
		} catch (RuntimeException exception) {
			throw new AssertionError(label + " threw " + exception.getClass().getSimpleName(), exception);
		}
		throw new AssertionError(label + ": expected invalid manifest rejection");
	}

	private static void expectIllegalArgument(Runnable operation, String label) {
		try {
			operation.run();
			throw new AssertionError(label + ": expected rejection");
		} catch (IllegalArgumentException expected) {
			// Expected.
		}
	}

	private record ModelCase(String provider, String slug, String chassis, String family) {
	}

	private static void assertTrue(boolean value, String label) {
		if (!value) throw new AssertionError(label + ": expected true");
	}

	private static void assertEquals(Object expected, Object actual, String label) {
		if (!expected.equals(actual)) throw new AssertionError(label + ": expected=" + expected + ", actual=" + actual);
	}
}
