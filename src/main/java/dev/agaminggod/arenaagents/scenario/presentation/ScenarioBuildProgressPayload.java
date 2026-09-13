package dev.agaminggod.arenaagents.scenario.presentation;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;
import java.nio.charset.StandardCharsets;
import java.util.Objects;
import java.util.Set;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

/** Strict JSON envelope for server-to-client arena construction progress. */
public record ScenarioBuildProgressPayload(String encodedProgress) implements CustomPacketPayload {
	public static final int MAX_ENCODED_BYTES = 8_192;
	private static final int SCHEMA_VERSION = 2;
	private static final Set<String> KEYS = Set.of(
			"schemaVersion", "buildId", "scenarioTitle", "phase", "revision",
			"completed", "total", "changedBlocks", "originX", "originY", "originZ", "status", "detail",
			"errorCode", "confirmationToken"
	);
	public static final Type<ScenarioBuildProgressPayload> TYPE = new Type<>(
			Identifier.fromNamespaceAndPath("arenaagents", "scenario_build_progress")
	);
	public static final StreamCodec<RegistryFriendlyByteBuf, ScenarioBuildProgressPayload> CODEC = StreamCodec.composite(
			ByteBufCodecs.stringUtf8(MAX_ENCODED_BYTES),
			ScenarioBuildProgressPayload::encodedProgress,
			ScenarioBuildProgressPayload::new
	);

	public ScenarioBuildProgressPayload {
		encodedProgress = Objects.requireNonNull(encodedProgress, "encodedProgress must not be null");
		if (encodedProgress.isBlank()) throw new IllegalArgumentException("encodedProgress must not be blank");
		if (encodedProgress.getBytes(StandardCharsets.UTF_8).length > MAX_ENCODED_BYTES) {
			throw new IllegalArgumentException("build progress payload exceeds " + MAX_ENCODED_BYTES + " UTF-8 bytes");
		}
		parseProgress(encodedProgress);
	}

	public static ScenarioBuildProgressPayload fromProgress(ScenarioBuildProgress progress) {
		Objects.requireNonNull(progress, "progress must not be null");
		JsonObject root = new JsonObject();
		root.addProperty("schemaVersion", SCHEMA_VERSION);
		root.addProperty("buildId", progress.buildId());
		root.addProperty("scenarioTitle", progress.scenarioTitle());
		root.addProperty("phase", progress.phase());
		root.addProperty("revision", progress.revision());
		root.addProperty("completed", progress.completed());
		root.addProperty("total", progress.total());
		root.addProperty("changedBlocks", progress.changedBlocks());
		root.addProperty("originX", progress.originX());
		root.addProperty("originY", progress.originY());
		root.addProperty("originZ", progress.originZ());
		root.addProperty("status", progress.status().wireName());
		root.addProperty("detail", progress.detail());
		root.addProperty("errorCode", progress.errorCode());
		root.addProperty("confirmationToken", progress.confirmationToken());
		return new ScenarioBuildProgressPayload(root.toString());
	}

	public ScenarioBuildProgress progress() {
		JsonObject root = parseProgress(encodedProgress);
		return new ScenarioBuildProgress(
				text(root, "buildId", ScenarioBuildProgress.MAX_BUILD_ID_LENGTH),
				text(root, "scenarioTitle", ScenarioBuildProgress.MAX_TITLE_LENGTH),
				text(root, "phase", ScenarioBuildProgress.MAX_PHASE_LENGTH),
				exactLong(root, "revision"),
				exactInt(root, "completed"),
				exactInt(root, "total"),
				exactInt(root, "changedBlocks"),
				exactInt(root, "originX"),
				exactInt(root, "originY"),
				exactInt(root, "originZ"),
				ScenarioBuildProgress.Status.fromWireName(text(root, "status", 32)),
				text(root, "detail", ScenarioBuildProgress.MAX_DETAIL_LENGTH),
				optionalText(root, "errorCode", ScenarioBuildProgress.MAX_ERROR_CODE_LENGTH),
				optionalText(root, "confirmationToken", ScenarioBuildProgress.MAX_CONFIRMATION_TOKEN_LENGTH)
		);
	}

	@Override
	public Type<ScenarioBuildProgressPayload> type() {
		return TYPE;
	}

	private static JsonObject parseProgress(String encoded) {
		try {
			JsonElement value = JsonParser.parseString(encoded);
			if (!value.isJsonObject()) throw new IllegalArgumentException("build progress must be a JSON object");
			JsonObject root = value.getAsJsonObject();
			requireExactKeys(root);
			if (exactInt(root, "schemaVersion") != SCHEMA_VERSION) {
				throw new IllegalArgumentException("unsupported build progress schema");
			}
			return root;
		} catch (JsonParseException exception) {
			throw new IllegalArgumentException("build progress is invalid JSON", exception);
		}
	}

	private static void requireExactKeys(JsonObject root) {
		if (!root.keySet().equals(KEYS)) throw new IllegalArgumentException("build progress fields are invalid");
	}

	private static String text(JsonObject root, String field, int maximum) {
		if (!root.has(field) || !root.get(field).isJsonPrimitive() || !root.get(field).getAsJsonPrimitive().isString()) {
			throw new IllegalArgumentException(field + " must be a string");
		}
		String value = root.get(field).getAsString();
		if (value.isBlank() || value.length() > maximum || value.indexOf('\n') >= 0 || value.indexOf('\r') >= 0) {
			throw new IllegalArgumentException(field + " is invalid");
		}
		return value;
	}

	private static String optionalText(JsonObject root, String field, int maximum) {
		if (!root.has(field) || !root.get(field).isJsonPrimitive()
				|| !root.get(field).getAsJsonPrimitive().isString()) {
			throw new IllegalArgumentException(field + " must be a string");
		}
		String value = root.get(field).getAsString();
		if (value.length() > maximum || value.indexOf('\n') >= 0 || value.indexOf('\r') >= 0) {
			throw new IllegalArgumentException(field + " is invalid");
		}
		return value;
	}

	private static long exactLong(JsonObject root, String field) {
		if (!root.has(field) || !root.get(field).isJsonPrimitive() || !root.get(field).getAsJsonPrimitive().isNumber()) {
			throw new IllegalArgumentException(field + " must be a number");
		}
		try {
			long value = root.get(field).getAsLong();
			if (!root.get(field).getAsString().equals(Long.toString(value))) {
				throw new IllegalArgumentException(field + " must be an exact integer");
			}
			return value;
		} catch (NumberFormatException exception) {
			throw new IllegalArgumentException(field + " must be an exact integer", exception);
		}
	}

	private static int exactInt(JsonObject root, String field) {
		long value = exactLong(root, field);
		if (value < Integer.MIN_VALUE || value > Integer.MAX_VALUE) throw new IllegalArgumentException(field + " is out of range");
		return (int) value;
	}
}
