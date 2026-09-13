package dev.agaminggod.arenaagents.scenario;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.HexFormat;
import net.minecraft.SharedConstants;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.IntTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.server.Bootstrap;
import net.minecraft.util.datafix.DataFixers;
import net.minecraft.util.datafix.DataFixTypes;

/** Offline-only bridge to Minecraft's official structure-template DataFixerUpper. */
public final class MapStructureDataFixer {
	private static final int TARGET_DATA_VERSION = 4790;
	private static final long MAXIMUM_NBT_BYTES = 64L * 1024L * 1024L;

	private MapStructureDataFixer() {
	}

	public static void main(String[] args) throws Exception {
		SharedConstants.tryDetectVersion();
		Bootstrap.bootStrap();
		if (args.length == 1 && "--self-test".equals(args[0])) {
			selfTest();
			return;
		}
		if (args.length != 2) {
			throw new IllegalArgumentException("usage: MapStructureDataFixer <input.nbt> <output.nbt> | --self-test");
		}
		Path repository = Path.of(System.getProperty("user.dir", ".")).toAbsolutePath().normalize();
		Path researchRoot = repository.resolve("runtime/map-research").normalize();
		Path input = contained(Path.of(args[0]), researchRoot, true);
		Path output = contained(Path.of(args[1]), researchRoot, false);
		if (input.equals(output)) throw new IllegalArgumentException("input and output must be different files");
		Result result = upgrade(input, output);
		System.out.printf(
				"{\"sourceDataVersion\":%d,\"targetDataVersion\":%d,\"sourceSha256\":\"%s\",\"outputSha256\":\"%s\"}%n",
				result.sourceDataVersion(), result.targetDataVersion(), result.sourceSha256(), result.outputSha256());
	}

	static Result upgrade(Path input, Path output) throws IOException {
		long sourceLength = Files.size(input);
		if (sourceLength < 1 || sourceLength > MAXIMUM_NBT_BYTES) {
			throw new IllegalArgumentException("structure input must be between 1 byte and 64 MiB");
		}
		byte[] sourceBytes = Files.readAllBytes(input);
		String sourceHash = sha256(sourceBytes);
		CompoundTag source = NbtIo.readCompressed(input, NbtAccounter.create(MAXIMUM_NBT_BYTES));
		int sourceDataVersion = NbtUtils.getDataVersion(source, -1);
		if (sourceDataVersion < 1 || sourceDataVersion >= TARGET_DATA_VERSION) {
			throw new IllegalArgumentException("structure DataVersion must be older than 4790 and explicitly present");
		}
		requireStructureShape(source);

		CompoundTag upgraded = DataFixTypes.STRUCTURE.update(
				DataFixers.getDataFixer(), source.copy(), sourceDataVersion, TARGET_DATA_VERSION);
		NbtUtils.addDataVersion(upgraded, TARGET_DATA_VERSION);
		requireStructureShape(upgraded);
		if (NbtUtils.getDataVersion(upgraded, -1) != TARGET_DATA_VERSION) {
			throw new IllegalStateException("Minecraft DFU did not produce DataVersion 4790");
		}

		Files.createDirectories(output.toAbsolutePath().normalize().getParent());
		Path temporary = Files.createTempFile(output.getParent(), "." + output.getFileName(), ".tmp");
		try {
			NbtIo.writeCompressed(upgraded, temporary);
			if (Files.size(temporary) > MAXIMUM_NBT_BYTES) throw new IllegalStateException("upgraded structure exceeds 64 MiB");
			try {
				Files.move(temporary, output, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
			} catch (java.nio.file.AtomicMoveNotSupportedException ignored) {
				Files.move(temporary, output, StandardCopyOption.REPLACE_EXISTING);
			}
		} finally {
			Files.deleteIfExists(temporary);
		}
		if (!sourceHash.equals(sha256(Files.readAllBytes(input)))) {
			throw new IllegalStateException("source structure changed during upgrade");
		}
		return new Result(sourceDataVersion, TARGET_DATA_VERSION, sourceHash, sha256(Files.readAllBytes(output)));
	}

	private static void requireStructureShape(CompoundTag tag) {
		if (tag.getList("size").isEmpty() || tag.getList("palette").isEmpty()
				|| tag.getList("blocks").isEmpty() || tag.getList("entities").isEmpty()) {
			throw new IllegalArgumentException("NBT is not a vanilla structure template");
		}
	}

	private static Path contained(Path requested, Path root, boolean mustExist) throws IOException {
		Path path = requested.toAbsolutePath().normalize();
		if (!path.startsWith(root)) throw new IllegalArgumentException("path must stay under ignored runtime/map-research");
		Path current = path;
		while (current != null && current.startsWith(root)) {
			if (Files.isSymbolicLink(current)) throw new IllegalArgumentException("path contains a symbolic link");
			if (current.equals(root)) break;
			current = current.getParent();
		}
		if (mustExist && !Files.isRegularFile(path)) throw new IllegalArgumentException("input must be a regular file");
		return path;
	}

	private static String sha256(byte[] value) {
		try {
			return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value));
		} catch (NoSuchAlgorithmException impossible) {
			throw new IllegalStateException(impossible);
		}
	}

	private static void selfTest() throws Exception {
		Path temporaryRoot = Files.createTempDirectory("map-structure-dfu-");
		try {
			Path input = temporaryRoot.resolve("source.nbt");
			Path first = temporaryRoot.resolve("first.nbt");
			Path second = temporaryRoot.resolve("second.nbt");
			NbtIo.writeCompressed(minimalStructure(3953), input);
			byte[] original = Files.readAllBytes(input);
			Result firstResult = upgrade(input, first);
			Result secondResult = upgrade(input, second);
			if (firstResult.sourceDataVersion() != 3953 || firstResult.targetDataVersion() != TARGET_DATA_VERSION) {
				throw new AssertionError("unexpected DataVersion upgrade result");
			}
			if (!Arrays.equals(original, Files.readAllBytes(input))) throw new AssertionError("upgrade mutated source");
			if (!Arrays.equals(Files.readAllBytes(first), Files.readAllBytes(second))) {
				throw new AssertionError("same structure upgrade is not byte-identical");
			}
			CompoundTag verified = NbtIo.readCompressed(first, NbtAccounter.create(MAXIMUM_NBT_BYTES));
			if (NbtUtils.getDataVersion(verified, -1) != TARGET_DATA_VERSION) {
				throw new AssertionError("upgraded fixture is not DataVersion 4790");
			}
			System.out.println("PASS: Minecraft STRUCTURE DFU upgrades 3953 to 4790 deterministically");
		} finally {
			try (var files = Files.walk(temporaryRoot)) {
				files.sorted(java.util.Comparator.reverseOrder()).forEach(path -> {
					try {
						Files.deleteIfExists(path);
					} catch (IOException error) {
						throw new RuntimeException(error);
					}
				});
			}
		}
	}

	private static CompoundTag minimalStructure(int dataVersion) {
		CompoundTag structure = new CompoundTag();
		structure.putInt("DataVersion", dataVersion);
		ListTag size = new ListTag();
		size.add(IntTag.valueOf(1));
		size.add(IntTag.valueOf(1));
		size.add(IntTag.valueOf(1));
		structure.put("size", size);

		CompoundTag paletteEntry = new CompoundTag();
		paletteEntry.putString("Name", "minecraft:stone");
		ListTag palette = new ListTag();
		palette.add(paletteEntry);
		structure.put("palette", palette);

		CompoundTag block = new CompoundTag();
		ListTag position = new ListTag();
		position.add(IntTag.valueOf(0));
		position.add(IntTag.valueOf(0));
		position.add(IntTag.valueOf(0));
		block.put("pos", position);
		block.putInt("state", 0);
		ListTag blocks = new ListTag();
		blocks.add(block);
		structure.put("blocks", blocks);
		structure.put("entities", new ListTag());
		return structure;
	}

	record Result(
			int sourceDataVersion,
			int targetDataVersion,
			String sourceSha256,
			String outputSha256
	) {
	}
}
