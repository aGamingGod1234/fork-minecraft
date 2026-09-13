package dev.agaminggod.arenaagents.scenario.runtime;

import dev.agaminggod.arenaagents.agent.AgentGameMode;
import dev.agaminggod.arenaagents.scenario.ScenarioCategory;

import java.util.HashSet;
import java.util.List;
import java.util.Objects;

public record ScenarioLoadoutPlan(List<Entry> entries) {
	public ScenarioLoadoutPlan {
		entries = List.copyOf(Objects.requireNonNull(entries, "entries must not be null"));
		HashSet<Integer> inventorySlots = new HashSet<>();
		for (Entry entry : entries) {
			if (!inventorySlots.add(entry.inventorySlot())) {
				throw new IllegalArgumentException("loadout inventory slots must be unique");
			}
		}
	}

	public static ScenarioLoadoutPlan forContestant(ScenarioCategory category, AgentGameMode gameMode) {
		Objects.requireNonNull(category, "category must not be null");
		Objects.requireNonNull(gameMode, "gameMode must not be null");
		return switch (category) {
			case SURVIVAL -> new ScenarioLoadoutPlan(List.of(
					item("minecraft:iron_pickaxe", 1, 0),
					item("minecraft:iron_axe", 1, 1),
					item("minecraft:bread", 16, 2),
					item("minecraft:torch", 32, 3)
			));
			case BUILDING -> gameMode == AgentGameMode.CREATIVE
					? new ScenarioLoadoutPlan(List.of())
					: new ScenarioLoadoutPlan(List.of(
							item("minecraft:stone_bricks", 64, 0),
							item("minecraft:oak_planks", 64, 1),
							item("minecraft:glass", 64, 2),
							item("minecraft:sea_lantern", 64, 3),
							item("minecraft:white_concrete", 64, 4),
							item("minecraft:scaffolding", 64, 5)
					));
			case PVP -> new ScenarioLoadoutPlan(List.of());
			case PARKOUR -> new ScenarioLoadoutPlan(List.of(
					item("minecraft:cooked_beef", 16, 0),
					armor("minecraft:leather_boots", 1, ArmorSlot.FEET)
			));
		};
	}

	public boolean hasItem(String itemId) {
		return entries.stream().anyMatch(entry -> entry.itemId().equals(itemId));
	}

	private static Entry item(String itemId, int count, int inventorySlot) {
		return new Entry(itemId, count, inventorySlot, ArmorSlot.NONE);
	}

	private static Entry armor(String itemId, int inventorySlot, ArmorSlot armorSlot) {
		return new Entry(itemId, 1, inventorySlot, armorSlot);
	}

	public record Entry(String itemId, int count, int inventorySlot, ArmorSlot armorSlot) {
		public Entry {
			itemId = Objects.requireNonNull(itemId, "itemId must not be null").trim();
			if (itemId.isEmpty() || count < 1 || count > 64 || inventorySlot < 0 || inventorySlot > 35) {
				throw new IllegalArgumentException("invalid loadout entry");
			}
			Objects.requireNonNull(armorSlot, "armorSlot must not be null");
		}
	}

	public enum ArmorSlot {
		NONE,
		HEAD,
		CHEST,
		LEGS,
		FEET
	}
}
