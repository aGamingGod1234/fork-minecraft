package dev.agaminggod.arenaagents.server.goal;

import dev.agaminggod.arenaagents.agent.goal.GoalPredicate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.core.Holder;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.component.DataComponentMap;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.equipment.Equippable;

public final class GoalInventoryCapacityVerification {
	private GoalInventoryCapacityVerification() {
	}

	public static int verify() {
		net.minecraft.SharedConstants.tryDetectVersion();
		net.minecraft.server.Bootstrap.bootStrap();
		Map<Item, DataComponentMap> originals = new HashMap<>();
		try {
			for (Item item : List.of(Items.APPLE, Items.DIRT, Items.COBBLESTONE)) bind(originals, item, 64, null);
			for (Item item : List.of(Items.IRON_PICKAXE, Items.DIAMOND_PICKAXE, Items.IRON_AXE, Items.DIAMOND_SWORD)) bind(originals, item, 1, null);
			bind(originals, Items.IRON_HELMET, 1, EquipmentSlot.HEAD);
			bind(originals, Items.DIAMOND_HELMET, 1, EquipmentSlot.HEAD);
			bind(originals, Items.IRON_BOOTS, 1, EquipmentSlot.FEET);
			int assertions = 0;
			assertions += assertCapacity(false, category(37, "iron_pickaxe"), "one-member categories preserve unstackable capacity");
			assertions += assertCapacity(true, category(38, "iron_pickaxe"), "one-member categories reject capacity overflow");
			assertions += assertCapacity(false, category(2_368, "iron_pickaxe", "apple"), "a category may use its stackable member for the summed count");
			assertions += assertCapacity(true, category(2_369, "iron_pickaxe", "apple"), "mixed stack sizes cannot exceed the best available carrying capacity");
			assertions += assertCapacity(true, category(Integer.MAX_VALUE, "apple", "dirt"), "category capacity arithmetic cannot overflow");
			assertions += assertCapacity(false, category(39, "iron_helmet", "iron_boots"), "mixed category members can use distinct equipment slots");
			assertions += assertCapacity(true, category(39, "iron_helmet", "diamond_helmet"), "alternative helmets share a single equipment slot");
			assertions += assertCapacity(false, new GoalPredicate.AllOf(List.of(
					category(2_368, "apple", "dirt"), category(2_368, "apple", "cobblestone"))),
					"overlapping categories can count the same factual inventory");
			List<GoalPredicate> chain = List.of(category(1_024, "apple", "dirt"),
					category(1_024, "iron_pickaxe", "cobblestone"), category(1_024, "dirt", "cobblestone"));
			for (int first = 0; first < chain.size(); first++) {
				for (int second = 0; second < chain.size(); second++) {
					if (first == second) continue;
					assertions += assertCapacity(false, new GoalPredicate.AllOf(List.of(chain.get(first), chain.get(second), chain.get(3 - first - second))),
							"transitively overlapping categories remain possible in insertion order " + first + "," + second);
				}
			}
			assertions += assertCapacity(true, new GoalPredicate.AllOf(List.of(
					category(20, "iron_pickaxe", "diamond_pickaxe"), category(20, "iron_axe", "diamond_sword"))),
					"disjoint categories share the finite general inventory");
			assertions += assertCapacity(true, new GoalPredicate.AllOf(List.of(
					new GoalPredicate.InventoryContains("minecraft:iron_pickaxe", 37), category(128, "apple", "dirt"))),
					"fixed items and disjoint categories share general slots");
			assertions += assertCapacity(false, new GoalPredicate.AnyOf(List.of(
					category(Integer.MAX_VALUE, "apple", "dirt"), category(1, "iron_pickaxe", "diamond_pickaxe"))),
					"one feasible any-of category keeps the predicate possible");
			assertions += assertCapacity(true, new GoalPredicate.AllOf(List.of(
					new GoalPredicate.InventoryContains("minecraft:iron_pickaxe", 37),
					new GoalPredicate.InventoryContains("minecraft:apple", 1))),
					"fixed-item capacity remains exact after adding categories");
			List<String> broadIds = BuiltInRegistries.ITEM.keySet().stream()
					.filter(id -> !id.equals(Identifier.parse("minecraft:air"))).map(Identifier::toString).sorted().limit(64).toList();
			for (String itemId : broadIds) bind(originals, BuiltInRegistries.ITEM.getValue(Identifier.parse(itemId)), 64, null);
			List<GoalPredicate> categories = new ArrayList<>();
			for (int index = 0; index < 16; index++) categories.add(new GoalPredicate.InventoryContainsAny(broadIds, 64));
			assertions += assertCapacity(false, new GoalPredicate.AllOf(categories),
					"sixteen 64-member categories remain compact instead of expanding item assignments");
			return assertions;
		} finally {
			restore(originals);
		}
	}

	private static GoalPredicate.InventoryContainsAny category(int count, String... itemIds) {
		return new GoalPredicate.InventoryContainsAny(java.util.Arrays.stream(itemIds).map(itemId -> "minecraft:" + itemId).toList(), count);
	}

	private static int assertCapacity(boolean exceeds, GoalPredicate predicate, String label) {
		boolean actual = GoalInventoryCapacity.exceeds(predicate, RegistryAccess.EMPTY);
		if (actual != exceeds) throw new AssertionError(label + ": expected exceeds=" + exceeds + ", actual=" + actual);
		System.out.println("PASS: " + label);
		return 1;
	}

	private static void bind(Map<Item, DataComponentMap> originals, Item item, int maxStackSize, EquipmentSlot equipmentSlot) {
		Holder.Reference<Item> holder = item.builtInRegistryHolder();
		if (!originals.containsKey(item)) originals.put(item, holder.areComponentsBound() ? holder.components() : null);
		DataComponentMap.Builder components = DataComponentMap.builder().set(DataComponents.MAX_STACK_SIZE, maxStackSize);
		if (equipmentSlot != null) components.set(DataComponents.EQUIPPABLE, Equippable.builder(equipmentSlot).build());
		holder.bindComponents(components.build());
	}

	private static void restore(Map<Item, DataComponentMap> originals) {
		try {
			var components = Holder.Reference.class.getDeclaredField("components");
			components.setAccessible(true);
			for (var entry : originals.entrySet()) {
				Holder.Reference<Item> holder = entry.getKey().builtInRegistryHolder();
				if (entry.getValue() == null) components.set(holder, null);
				else holder.bindComponents(entry.getValue());
			}
		} catch (ReflectiveOperationException exception) {
			throw new AssertionError("Could not restore bootstrap item components", exception);
		}
	}
}
