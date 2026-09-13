package dev.agaminggod.arenaagents.scenario.runtime;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Random;
import net.minecraft.core.BlockPos;

/** Deterministic PvP container contents, independent from runtime write order. */
public record ScenarioLootManifest(Tier tier, List<Entry> entries) {
	public ScenarioLootManifest {
		Objects.requireNonNull(tier, "tier must not be null");
		entries = List.copyOf(Objects.requireNonNull(entries, "entries must not be null"));
		if (entries.isEmpty()) throw new IllegalArgumentException("loot manifest must not be empty");
		HashSet<Integer> slots = new HashSet<>();
		for (Entry entry : entries) {
			if (!slots.add(entry.slot())) throw new IllegalArgumentException("loot slots must be unique");
		}
	}

	public static ScenarioLootManifest forContainer(BlockPos origin, BlockPos position, long worldSeed) {
		Objects.requireNonNull(origin, "origin must not be null");
		Objects.requireNonNull(position, "position must not be null");
		int relativeX = position.getX() - origin.getX();
		int relativeY = position.getY() - origin.getY();
		int relativeZ = position.getZ() - origin.getZ();
		Tier tier = relativeY < 0 ? Tier.DUNGEON
				: (long) relativeX * relativeX + (long) relativeZ * relativeZ <= 15L * 15L
				? Tier.CENTER : Tier.HOUSE;
		Random random = new Random(worldSeed ^ position.asLong() ^ ((long) tier.ordinal() << 56));
		ArrayList<Integer> slots = new ArrayList<>(27);
		for (int slot = 0; slot < 27; slot++) slots.add(slot);
		Collections.shuffle(slots, random);
		ArrayList<ItemSpec> items = switch (tier) {
			case HOUSE -> houseItems(random);
			case DUNGEON -> dungeonItems(random);
			case CENTER -> centerItems(random);
		};
		ArrayList<Entry> entries = new ArrayList<>(items.size());
		for (int index = 0; index < items.size(); index++) {
			ItemSpec item = items.get(index);
			entries.add(new Entry(slots.get(index), item.itemId(), item.count()));
		}
		entries.sort(java.util.Comparator.comparingInt(Entry::slot));
		return new ScenarioLootManifest(tier, entries);
	}

	private static ArrayList<ItemSpec> houseItems(Random random) {
		ArrayList<ItemSpec> result = new ArrayList<>();
		result.add(item(random.nextBoolean() ? "minecraft:bread" : "minecraft:cooked_beef", 4 + random.nextInt(5)));
		result.add(item(switch (random.nextInt(3)) {
			case 0 -> "minecraft:stone_sword";
			case 1 -> "minecraft:stone_axe";
			default -> "minecraft:shield";
		}, 1));
		result.add(item(random.nextBoolean() ? "minecraft:leather_chestplate" : "minecraft:chainmail_boots", 1));
		result.add(item(random.nextBoolean() ? "minecraft:torch" : "minecraft:arrow", 8 + random.nextInt(9)));
		return result;
	}

	private static ArrayList<ItemSpec> dungeonItems(Random random) {
		ArrayList<ItemSpec> result = new ArrayList<>();
		result.add(item(switch (random.nextInt(4)) {
			case 0 -> "minecraft:iron_sword";
			case 1 -> "minecraft:iron_axe";
			case 2 -> "minecraft:bow";
			default -> "minecraft:crossbow";
		}, 1));
		result.add(item("minecraft:arrow", 12 + random.nextInt(13)));
		result.add(item(random.nextBoolean() ? "minecraft:cooked_beef" : "minecraft:bread", 4 + random.nextInt(5)));
		result.add(item(random.nextBoolean() ? "minecraft:chainmail_chestplate" : "minecraft:iron_boots", 1));
		return result;
	}

	private static ArrayList<ItemSpec> centerItems(Random random) {
		ArrayList<ItemSpec> result = new ArrayList<>();
		result.add(item(switch (random.nextInt(4)) {
			case 0 -> "minecraft:iron_chestplate";
			case 1 -> "minecraft:golden_apple";
			case 2 -> "minecraft:ender_pearl";
			default -> "minecraft:diamond_sword";
		}, 1));
		result.add(item(random.nextBoolean() ? "minecraft:iron_sword" : "minecraft:crossbow", 1));
		result.add(item("minecraft:arrow", 16 + random.nextInt(17)));
		result.add(item("minecraft:cooked_beef", 5 + random.nextInt(5)));
		result.add(item(random.nextBoolean() ? "minecraft:shield" : "minecraft:iron_helmet", 1));
		return result;
	}

	private static ItemSpec item(String itemId, int count) {
		return new ItemSpec(itemId, count);
	}

	public enum Tier {
		HOUSE,
		DUNGEON,
		CENTER
	}

	public record Entry(int slot, String itemId, int count) {
		public Entry {
			itemId = Objects.requireNonNull(itemId, "itemId must not be null").trim();
			if (slot < 0 || slot >= 27 || itemId.isEmpty() || count < 1 || count > 64) {
				throw new IllegalArgumentException("invalid loot entry");
			}
		}
	}

	private record ItemSpec(String itemId, int count) {
	}
}
