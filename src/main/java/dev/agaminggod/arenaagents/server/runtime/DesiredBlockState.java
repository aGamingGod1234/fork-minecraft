package dev.agaminggod.arenaagents.server.runtime;

import dev.agaminggod.arenaagents.agent.AgentDomainException;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import java.util.regex.Pattern;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.Property;

/** Immutable model-authored block id and requested stable state properties. */
public record DesiredBlockState(String blockId, Map<String, String> properties) {
	private static final Pattern BLOCK_STATE_PREFIX = Pattern.compile("^[^\\[\\]]+$");

	public DesiredBlockState {
		blockId = Objects.requireNonNull(blockId, "blockId must not be null");
		properties = Collections.unmodifiableMap(new LinkedHashMap<>(
				Objects.requireNonNull(properties, "properties must not be null")));
	}

	public static DesiredBlockState parse(String desiredState, String fallbackBlockId) {
		String fallback = canonicalBlockId(fallbackBlockId, "fallback block id");
		if (desiredState == null) return new DesiredBlockState(fallback, Map.of());
		String text = desiredState.trim();
		if (text.isEmpty()) throw invalid("desired state must not be blank");

		int open = text.indexOf('[');
		String blockText = open < 0 ? text : text.substring(0, open);
		if (!BLOCK_STATE_PREFIX.matcher(blockText).matches()) {
			throw invalid("desired state has an invalid block id: " + blockText);
		}
		String blockId = canonicalBlockId(blockText, "desired block id");
		if (!fallback.equals(blockId)) {
			throw invalid("desired block id " + blockId + " does not match item block " + fallback);
		}

		Map<String, String> properties = new LinkedHashMap<>();
		if (open >= 0) {
			if (!text.endsWith("]") || text.indexOf('[', open + 1) >= 0) {
				throw invalid("desired state has malformed property brackets");
			}
			String propertyText = text.substring(open + 1, text.length() - 1);
			if (propertyText.isBlank()) throw invalid("desired state property list must not be empty");
			Block block = BuiltInRegistries.BLOCK.getValue(Identifier.parse(blockId));
			for (String entry : propertyText.split(",", -1)) {
				int equals = entry.indexOf('=');
				if (equals <= 0 || equals == entry.length() - 1 || entry.indexOf('=', equals + 1) >= 0) {
					throw invalid("desired state property must use name=value: " + entry);
				}
				String name = entry.substring(0, equals).trim();
				String valueName = entry.substring(equals + 1).trim();
				if (name.isEmpty() || valueName.isEmpty() || properties.putIfAbsent(name, valueName) != null) {
					throw invalid("duplicate or blank desired state property: " + name);
				}
				Property<?> property = block.getStateDefinition().getProperty(name);
				if (property == null) throw invalid("unknown property " + name + " for " + blockId);
				if (property.getValue(valueName).isEmpty()) {
					throw invalid("invalid value " + valueName + " for property " + name);
				}
			}
		}
		return new DesiredBlockState(blockId, properties);
	}

	public String expectedBlockId() {
		return blockId;
	}

	public boolean matches(BlockState actual) {
		Objects.requireNonNull(actual, "actual state must not be null");
		String actualBlockId = BuiltInRegistries.BLOCK.getKey(actual.getBlock()).toString();
		if (!blockId.equals(actualBlockId)) return false;
		Map<String, String> actualProperties = stableProperties(actual);
		for (Map.Entry<String, String> entry : properties.entrySet()) {
			if (isWorldDerivedProperty(entry.getKey())) continue;
			if (!entry.getValue().equals(actualProperties.get(entry.getKey()))) return false;
		}
		return true;
	}

	static Map<String, String> stableProperties(BlockState state) {
		Map<String, String> result = new TreeMap<>();
		state.getValues()
				.filter(entry -> !isWorldDerivedProperty(entry.property().getName()))
				.forEach(entry -> result.put(entry.property().getName(), entry.valueName()));
		return Collections.unmodifiableMap(result);
	}

	static boolean isWorldDerivedProperty(String name) {
		return switch (name) {
			case "distance", "north", "east", "south", "west", "up" -> true;
			default -> false;
		};
	}

	private static String canonicalBlockId(String blockId, String label) {
		if (blockId == null || blockId.isBlank()) throw invalid(label + " must not be blank");
		Identifier identifier = Identifier.tryParse(blockId.trim());
		if (identifier == null || !BuiltInRegistries.BLOCK.containsKey(identifier)) {
			throw invalid("unknown " + label + ": " + blockId);
		}
		return identifier.toString();
	}

	private static AgentDomainException invalid(String message) {
		return new AgentDomainException("INVALID_DESIRED_STATE", message);
	}
}
