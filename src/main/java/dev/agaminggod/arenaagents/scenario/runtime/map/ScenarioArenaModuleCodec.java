package dev.agaminggod.arenaagents.scenario.runtime.map;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonToken;
import java.io.IOException;
import java.io.StringReader;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Pattern;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.Property;

/** Strict schema-1 decoder for deterministic block-only arena module resources. */
public final class ScenarioArenaModuleCodec {
	private static final int MAXIMUM_PLACEMENTS = 400_000;
	private static final Pattern ID_PATTERN = Pattern.compile("[a-z0-9][a-z0-9-]{0,63}");
	private static final Pattern SHA256_PATTERN = Pattern.compile("[0-9a-f]{64}");
	private static final Set<String> ROOT_KEYS = Set.of(
			"schemaVersion", "id", "version", "sourceKey", "difficulty", "bounds",
			"allowedTransforms", "containerPolicy", "spectatorPolicy", "palette",
			"placements", "anchors", "geometrySha256");
	private static final Set<String> CONTROL_BLOCKS = Set.of(
			"minecraft:command_block", "minecraft:repeating_command_block", "minecraft:chain_command_block",
			"minecraft:structure_block", "minecraft:jigsaw");
	private static final Set<String> CONTAINER_BLOCKS = Set.of(
			"minecraft:barrel", "minecraft:chest", "minecraft:trapped_chest", "minecraft:dispenser",
			"minecraft:dropper", "minecraft:hopper", "minecraft:furnace", "minecraft:blast_furnace",
			"minecraft:smoker", "minecraft:brewing_stand", "minecraft:chiseled_bookshelf",
			"minecraft:decorated_pot");

	private ScenarioArenaModuleCodec() {
	}

	public static ScenarioArenaModule decode(String encoded) {
		if (encoded == null) throw invalid("module JSON must not be null");
		JsonObject root = object(parseStrict(encoded), "module");
		exactKeys(root, ROOT_KEYS, "module");
		if (integer(root.get("schemaVersion"), "schemaVersion") != 1) {
			throw invalid("schemaVersion must be exactly integer 1");
		}
		String id = identifier(root.get("id"), "id");
		int version = positive(integer(root.get("version"), "version"), "version");
		String sourceKey = identifier(root.get("sourceKey"), "sourceKey");
		int difficulty = integer(root.get("difficulty"), "difficulty");
		if (difficulty < 1 || difficulty > 5) throw invalid("difficulty must be 1..5");
		ScenarioArenaModule.Bounds bounds = bounds(root.get("bounds"));
		List<ScenarioArenaModule.AllowedTransform> transformOrder = transforms(root.get("allowedTransforms"));
		Set<ScenarioArenaModule.AllowedTransform> transforms = new LinkedHashSet<>(transformOrder);
		ScenarioArenaModule.ContainerPolicy containerPolicy = ScenarioArenaModule.ContainerPolicy.fromWireName(
				string(root.get("containerPolicy"), "containerPolicy"));
		ScenarioArenaModule.SpectatorPolicy spectatorPolicy = ScenarioArenaModule.SpectatorPolicy.fromWireName(
				string(root.get("spectatorPolicy"), "spectatorPolicy"));
		List<BlockState> palette = palette(root.get("palette"));
		List<ScenarioArenaModule.Placement> placements = placements(
				root.get("placements"), palette, bounds, containerPolicy);
		List<ScenarioArenaAnchor> anchors = anchors(root.get("anchors"), bounds);
		String geometrySha256 = string(root.get("geometrySha256"), "geometrySha256");
		if (!SHA256_PATTERN.matcher(geometrySha256).matches()) {
			throw invalid("geometrySha256 must be 64 lowercase hexadecimal characters");
		}
		String actualHash = ScenarioModuleHasher.sha256(placements);
		if (!geometrySha256.equals(actualHash)) {
			throw invalid("geometrySha256 mismatch: expected " + actualHash + ", got " + geometrySha256);
		}
		return new ScenarioArenaModule(
				id, version, sourceKey, difficulty, bounds, transforms, containerPolicy, spectatorPolicy,
				placements, anchors, geometrySha256);
	}

	private static ScenarioArenaModule.Bounds bounds(JsonElement element) {
		JsonObject bounds = object(element, "bounds");
		exactKeys(bounds, Set.of("min", "max"), "bounds");
		BlockPos minimum = position(bounds.get("min"), "bounds.min");
		BlockPos maximum = position(bounds.get("max"), "bounds.max");
		if (!minimum.equals(BlockPos.ZERO)) throw invalid("module bounds must begin at [0,0,0]");
		ScenarioArenaModule.Bounds result = new ScenarioArenaModule.Bounds(minimum, maximum);
		long xSpan = (long) maximum.getX() - minimum.getX() + 1L;
		long ySpan = (long) maximum.getY() - minimum.getY() + 1L;
		long zSpan = (long) maximum.getZ() - minimum.getZ() + 1L;
		if (xSpan > 192L || ySpan > 64L || zSpan > 192L) throw invalid("module bounds exceed 192x64x192");
		return result;
	}

	private static List<ScenarioArenaModule.AllowedTransform> transforms(JsonElement element) {
		JsonArray array = array(element, "allowedTransforms");
		if (array.isEmpty()) throw invalid("allowedTransforms must not be empty");
		List<ScenarioArenaModule.AllowedTransform> values = new ArrayList<>();
		Set<ScenarioArenaModule.AllowedTransform> unique = new HashSet<>();
		for (JsonElement entry : array) {
			ScenarioArenaModule.AllowedTransform value = ScenarioArenaModule.AllowedTransform.fromWireName(
					string(entry, "allowedTransforms entry"));
			if (!unique.add(value)) throw invalid("allowedTransforms contains a duplicate: " + value.wireName());
			values.add(value);
		}
		List<ScenarioArenaModule.AllowedTransform> canonical = values.stream()
				.sorted(Comparator.comparingInt(Enum::ordinal)).toList();
		if (!values.equals(canonical)) throw invalid("allowedTransforms is not in canonical order");
		return values;
	}

	private static List<BlockState> palette(JsonElement element) {
		JsonArray array = array(element, "palette");
		List<BlockState> states = new ArrayList<>();
		String previous = null;
		for (int index = 0; index < array.size(); index++) {
			JsonObject entry = object(array.get(index), "palette[" + index + "]");
			exactKeys(entry, Set.of("id", "properties"), "palette[" + index + "]");
			String blockId = string(entry.get("id"), "palette block id");
			Identifier identifier = Identifier.tryParse(blockId);
			if (identifier == null || !"minecraft".equals(identifier.getNamespace())
					|| !BuiltInRegistries.BLOCK.containsKey(identifier)) {
				throw invalid("unknown or non-Minecraft block id: " + blockId);
			}
			if (CONTROL_BLOCKS.contains(blockId)) throw invalid("forbidden control block: " + blockId);
			Block block = BuiltInRegistries.BLOCK.getValue(identifier);
			JsonObject properties = object(entry.get("properties"), "palette properties");
			List<String> propertyNames = new ArrayList<>(properties.keySet());
			List<String> sortedNames = propertyNames.stream().sorted().toList();
			if (!propertyNames.equals(sortedNames)) throw invalid("palette properties are not sorted by name");
			Set<String> requiredProperties = new TreeSet<>();
			block.getStateDefinition().getProperties().forEach(property -> requiredProperties.add(property.getName()));
			if (!properties.keySet().equals(requiredProperties)) {
				throw invalid("palette properties are not the exact complete set for " + blockId);
			}
			BlockState state = block.defaultBlockState();
			for (String propertyName : propertyNames) {
				Property<?> property = block.getStateDefinition().getProperty(propertyName);
				state = setProperty(state, property, string(properties.get(propertyName), "property " + propertyName));
			}
			String canonical = ScenarioModuleHasher.canonicalState(state);
			if (previous != null && previous.compareTo(canonical) >= 0) {
				throw invalid("palette must be sorted and unique");
			}
			previous = canonical;
			states.add(state);
		}
		return List.copyOf(states);
	}

	private static <T extends Comparable<T>> BlockState setProperty(
			BlockState state,
			Property<T> property,
			String valueName
	) {
		if (property == null) throw invalid("unknown block-state property");
		T value = property.getValue(valueName)
				.orElseThrow(() -> invalid("invalid value " + valueName + " for property " + property.getName()));
		return state.setValue(property, value);
	}

	private static List<ScenarioArenaModule.Placement> placements(
			JsonElement element,
			List<BlockState> palette,
			ScenarioArenaModule.Bounds bounds,
			ScenarioArenaModule.ContainerPolicy containerPolicy
	) {
		JsonArray array = array(element, "placements");
		if (array.size() > MAXIMUM_PLACEMENTS) throw invalid("placement count exceeds 400000");
		if (!array.isEmpty() && palette.isEmpty()) throw invalid("palette must not be empty when placements exist");
		List<ScenarioArenaModule.Placement> placements = new ArrayList<>();
		Set<BlockPos> coordinates = new HashSet<>();
		BlockPos previous = null;
		for (int index = 0; index < array.size(); index++) {
			JsonObject entry = object(array.get(index), "placements[" + index + "]");
			exactKeys(entry, Set.of("x", "y", "z", "state"), "placements[" + index + "]");
			BlockPos position = new BlockPos(
					integer(entry.get("x"), "placement x"),
					integer(entry.get("y"), "placement y"),
					integer(entry.get("z"), "placement z"));
			if (!bounds.contains(position)) throw invalid("placement is outside module bounds: " + position);
			if (!coordinates.add(position)) throw invalid("duplicate placement coordinate: " + position);
			if (previous != null && compare(previous, position) >= 0) {
				throw invalid("placements are not in numeric x/y/z order");
			}
			previous = position;
			int stateIndex = integer(entry.get("state"), "placement state");
			if (stateIndex < 0 || stateIndex >= palette.size()) throw invalid("placement palette index is invalid");
			BlockState state = palette.get(stateIndex);
			String blockId = BuiltInRegistries.BLOCK.getKey(state.getBlock()).toString();
			if (state.isAir()) throw invalid("air states must not be emitted as placements");
			if (containerPolicy == ScenarioArenaModule.ContainerPolicy.NONE && CONTAINER_BLOCKS.contains(blockId)) {
				throw invalid("containerPolicy none rejects " + blockId);
			}
			placements.add(new ScenarioArenaModule.Placement(position, state));
		}
		return List.copyOf(placements);
	}

	private static List<ScenarioArenaAnchor> anchors(JsonElement element, ScenarioArenaModule.Bounds bounds) {
		JsonArray array = array(element, "anchors");
		List<ScenarioArenaAnchor> anchors = new ArrayList<>();
		Set<String> ids = new HashSet<>();
		Set<BlockPos> positions = new HashSet<>();
		ScenarioArenaAnchor previous = null;
		for (int index = 0; index < array.size(); index++) {
			JsonObject entry = object(array.get(index), "anchors[" + index + "]");
			exactKeys(entry, Set.of("id", "type", "position"), "anchors[" + index + "]");
			ScenarioArenaAnchor anchor = new ScenarioArenaAnchor(
					identifier(entry.get("id"), "anchor id"),
					ScenarioArenaAnchor.Type.fromWireName(string(entry.get("type"), "anchor type")),
					position(entry.get("position"), "anchor position"));
			if (!bounds.contains(anchor.position())) throw invalid("anchor is outside module bounds: " + anchor.id());
			if (!ids.add(anchor.id())) throw invalid("duplicate anchor id: " + anchor.id());
			if (!positions.add(anchor.position())) throw invalid("duplicate anchor position: " + anchor.position());
			if (previous != null && compare(previous, anchor) >= 0) {
				throw invalid("anchors are not in canonical semantic order");
			}
			previous = anchor;
			anchors.add(anchor);
		}
		return List.copyOf(anchors);
	}

	private static int compare(BlockPos first, BlockPos second) {
		int x = Integer.compare(first.getX(), second.getX());
		if (x != 0) return x;
		int y = Integer.compare(first.getY(), second.getY());
		return y != 0 ? y : Integer.compare(first.getZ(), second.getZ());
	}

	private static int compare(ScenarioArenaAnchor first, ScenarioArenaAnchor second) {
		int type = Integer.compare(first.type().ordinal(), second.type().ordinal());
		return type != 0 ? type : first.id().compareTo(second.id());
	}

	private static JsonElement parseStrict(String encoded) {
		try (JsonReader reader = new JsonReader(new StringReader(encoded))) {
			reader.setLenient(false);
			JsonElement value = readElement(reader);
			if (reader.peek() != JsonToken.END_DOCUMENT) throw invalid("trailing JSON content");
			return value;
		} catch (IOException | IllegalStateException | NumberFormatException error) {
			throw invalid("malformed strict JSON: " + error.getMessage(), error);
		}
	}

	private static JsonElement readElement(JsonReader reader) throws IOException {
		return switch (reader.peek()) {
			case BEGIN_OBJECT -> readObject(reader);
			case BEGIN_ARRAY -> readArray(reader);
			case STRING -> new JsonPrimitive(reader.nextString());
			case NUMBER -> new JsonPrimitive(new BigDecimal(reader.nextString()));
			case BOOLEAN -> new JsonPrimitive(reader.nextBoolean());
			case NULL -> {
				reader.nextNull();
				yield JsonNull.INSTANCE;
			}
			default -> throw invalid("unexpected JSON token: " + reader.peek());
		};
	}

	private static JsonObject readObject(JsonReader reader) throws IOException {
		JsonObject result = new JsonObject();
		Set<String> names = new HashSet<>();
		reader.beginObject();
		while (reader.hasNext()) {
			String name = reader.nextName();
			if (!names.add(name)) throw invalid("duplicate JSON field: " + name);
			result.add(name, readElement(reader));
		}
		reader.endObject();
		return result;
	}

	private static JsonArray readArray(JsonReader reader) throws IOException {
		JsonArray result = new JsonArray();
		reader.beginArray();
		while (reader.hasNext()) result.add(readElement(reader));
		reader.endArray();
		return result;
	}

	private static void exactKeys(JsonObject object, Set<String> expected, String label) {
		if (!object.keySet().equals(expected)) {
			Set<String> missing = new TreeSet<>(expected);
			missing.removeAll(object.keySet());
			Set<String> unknown = new TreeSet<>(object.keySet());
			unknown.removeAll(expected);
			throw invalid(label + " keys differ; missing=" + missing + ", unknown=" + unknown);
		}
	}

	private static JsonObject object(JsonElement element, String label) {
		if (element == null || !element.isJsonObject()) throw invalid(label + " must be an object");
		return element.getAsJsonObject();
	}

	private static JsonArray array(JsonElement element, String label) {
		if (element == null || !element.isJsonArray()) throw invalid(label + " must be an array");
		return element.getAsJsonArray();
	}

	private static String string(JsonElement element, String label) {
		if (element == null || !element.isJsonPrimitive() || !element.getAsJsonPrimitive().isString()) {
			throw invalid(label + " must be a string");
		}
		return element.getAsString();
	}

	private static int integer(JsonElement element, String label) {
		if (element == null || !element.isJsonPrimitive() || !element.getAsJsonPrimitive().isNumber()) {
			throw invalid(label + " must be an exact integer");
		}
		try {
			BigDecimal decimal = element.getAsBigDecimal();
			if (decimal.scale() != 0) throw new ArithmeticException("decimal notation is not an integer token");
			return decimal.toBigIntegerExact().intValueExact();
		} catch (ArithmeticException error) {
			throw invalid(label + " must be an exact 32-bit integer", error);
		}
	}

	private static int positive(int value, String label) {
		if (value < 1) throw invalid(label + " must be positive");
		return value;
	}

	private static String identifier(JsonElement element, String label) {
		String value = string(element, label);
		if (!ID_PATTERN.matcher(value).matches()) throw invalid(label + " is invalid: " + value);
		return value;
	}

	private static BlockPos position(JsonElement element, String label) {
		JsonArray values = array(element, label);
		if (values.size() != 3) throw invalid(label + " must contain exactly three integers");
		return new BlockPos(
				integer(values.get(0), label + "[0]"),
				integer(values.get(1), label + "[1]"),
				integer(values.get(2), label + "[2]"));
	}

	private static IllegalArgumentException invalid(String message) {
		return new IllegalArgumentException("invalid arena module: " + message);
	}

	private static IllegalArgumentException invalid(String message, Throwable cause) {
		return new IllegalArgumentException("invalid arena module: " + message, cause);
	}
}
