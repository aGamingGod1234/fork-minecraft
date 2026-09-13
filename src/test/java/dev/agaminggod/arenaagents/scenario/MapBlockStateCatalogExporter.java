package dev.agaminggod.arenaagents.scenario;

import java.io.IOException;
import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Comparator;
import java.util.TreeSet;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.level.block.state.BlockState;

/** Emits the exact complete vanilla block-state combinations for Minecraft 26.1.2. */
public final class MapBlockStateCatalogExporter {
	private static final int DATA_VERSION = 4790;
	private static final String MINECRAFT_VERSION = "26.1.2";

	private MapBlockStateCatalogExporter() {
	}

	public static void main(String[] args) throws IOException {
		if (args.length == 2 && "--owned-fixture".equals(args[0])) {
			writeAtomically(Path.of(args[1]).toAbsolutePath().normalize(), encodeOwnedFixture());
			return;
		}
		if (args.length < 1 || args.length > 2 || args.length == 2 && !"--check".equals(args[1])) {
			throw new IllegalArgumentException("usage: MapBlockStateCatalogExporter <output> [--check] | --owned-fixture <output>");
		}
		net.minecraft.SharedConstants.tryDetectVersion();
		net.minecraft.server.Bootstrap.bootStrap();
		Path output = Path.of(args[0]).toAbsolutePath().normalize();
		byte[] encoded = encodeCatalog();
		if (args.length == 2) {
			if (!Files.isRegularFile(output) || !java.util.Arrays.equals(encoded, normalizedCatalogBytes(output))) {
				throw new IllegalStateException("pinned block-state catalog differs from the resolved Minecraft 26.1.2 registry");
			}
			return;
		}
		writeAtomically(output, encoded);
	}

	private static byte[] normalizedCatalogBytes(Path output) throws IOException {
		return Files.readString(output, StandardCharsets.UTF_8)
				.replace("\r\n", "\n")
				.getBytes(StandardCharsets.UTF_8);
	}

	private static void writeAtomically(Path output, byte[] encoded) throws IOException {
		Files.createDirectories(output.getParent());
		Path temporary = Files.createTempFile(output.getParent(), "." + output.getFileName(), ".tmp");
		try {
			Files.write(temporary, encoded);
			try {
				Files.move(temporary, output, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
			} catch (java.nio.file.AtomicMoveNotSupportedException ignored) {
				Files.move(temporary, output, StandardCopyOption.REPLACE_EXISTING);
			}
		} finally {
			Files.deleteIfExists(temporary);
		}
	}

	private static byte[] encodeOwnedFixture() throws IOException {
		ByteArrayOutputStream bytes = new ByteArrayOutputStream();
		try (DataOutputStream output = new DataOutputStream(bytes)) {
			output.writeByte(10);
			output.writeUTF("structure");
			output.writeByte(3);
			output.writeUTF("DataVersion");
			output.writeInt(DATA_VERSION);
			output.writeByte(9);
			output.writeUTF("size");
			output.writeByte(3);
			output.writeInt(3);
			output.writeInt(2);
			output.writeInt(2);
			output.writeInt(2);
			output.writeByte(9);
			output.writeUTF("palette");
			output.writeByte(10);
			output.writeInt(2);
			writePaletteEntry(output, "minecraft:stone");
			writePaletteEntry(output, "minecraft:oak_planks");
			output.writeByte(9);
			output.writeUTF("blocks");
			output.writeByte(10);
			output.writeInt(5);
			writeBlock(output, 0, 0, 0, 0);
			writeBlock(output, 0, 0, 1, 0);
			writeBlock(output, 1, 0, 0, 0);
			writeBlock(output, 1, 0, 1, 0);
			writeBlock(output, 1, 1, 1, 1);
			output.writeByte(9);
			output.writeUTF("entities");
			output.writeByte(10);
			output.writeInt(0);
			output.writeByte(0);
		}
		return bytes.toByteArray();
	}

	private static void writePaletteEntry(DataOutputStream output, String blockId) throws IOException {
		output.writeByte(8);
		output.writeUTF("Name");
		output.writeUTF(blockId);
		output.writeByte(0);
	}

	private static void writeBlock(DataOutputStream output, int x, int y, int z, int state) throws IOException {
		output.writeByte(9);
		output.writeUTF("pos");
		output.writeByte(3);
		output.writeInt(3);
		output.writeInt(x);
		output.writeInt(y);
		output.writeInt(z);
		output.writeByte(3);
		output.writeUTF("state");
		output.writeInt(state);
		output.writeByte(0);
	}

	private static byte[] encodeCatalog() {
		TreeSet<String> states = new TreeSet<>();
		BuiltInRegistries.BLOCK.stream().forEach(block -> {
			String blockId = BuiltInRegistries.BLOCK.getKey(block).toString();
			if (!blockId.startsWith("minecraft:")) return;
			block.getStateDefinition().getPossibleStates().forEach(state -> states.add(canonicalState(blockId, state)));
		});
		StringBuilder json = new StringBuilder(states.size() * 64);
		json.append("{\"dataVersion\":").append(DATA_VERSION)
				.append(",\"minecraftVersion\":\"").append(MINECRAFT_VERSION)
				.append("\",\"schemaVersion\":1,\"states\":[");
		boolean first = true;
		for (String state : states) {
			if (!first) json.append(',');
			first = false;
			json.append('"').append(state).append('"');
		}
		json.append("]}\n");
		return json.toString().getBytes(StandardCharsets.UTF_8);
	}

	private static String canonicalState(String blockId, BlockState state) {
		StringBuilder value = new StringBuilder(blockId);
		var properties = state.getValues().sorted(Comparator.comparing(entry -> entry.property().getName())).toList();
		if (!properties.isEmpty()) {
			value.append('[');
			for (int index = 0; index < properties.size(); index++) {
				if (index > 0) value.append(',');
				var entry = properties.get(index);
				value.append(entry.property().getName()).append('=').append(entry.valueName());
			}
			value.append(']');
		}
		return value.toString();
	}
}
