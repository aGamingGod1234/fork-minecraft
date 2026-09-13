package dev.agaminggod.arenaagents.scenario.runtime;

import dev.agaminggod.arenaagents.agent.AgentGameMode;
import dev.agaminggod.arenaagents.scenario.ScenarioCategory;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.ItemStack;

import java.util.Objects;

public final class ScenarioLoadoutService {
	public void apply(ScenarioLoadoutPlan plan, Target target) {
		Objects.requireNonNull(plan, "plan must not be null");
		Objects.requireNonNull(target, "target must not be null");
		target.reset();
		for (ScenarioLoadoutPlan.Entry entry : plan.entries()) {
			if (entry.armorSlot() == ScenarioLoadoutPlan.ArmorSlot.NONE) {
				target.putInventory(entry.inventorySlot(), entry.itemId(), entry.count());
			} else {
				target.equip(entry.armorSlot(), entry.itemId(), entry.count());
			}
		}
	}

	public void apply(ServerPlayer player, ScenarioCategory category, AgentGameMode gameMode) {
		apply(ScenarioLoadoutPlan.forContestant(category, gameMode), new PlayerTarget(player));
	}

	public void applyThenStart(ScenarioLoadoutPlan plan, Target target, Runnable start) {
		apply(plan, target);
		Objects.requireNonNull(start, "start must not be null").run();
	}

	public void applyThenStart(
			ServerPlayer player,
			ScenarioCategory category,
			AgentGameMode gameMode,
			Runnable start
	) {
		applyThenStart(
				ScenarioLoadoutPlan.forContestant(category, gameMode),
				new PlayerTarget(player),
				start
		);
	}

	public interface Target {
		void reset();

		void putInventory(int slot, String itemId, int count);

		void equip(ScenarioLoadoutPlan.ArmorSlot slot, String itemId, int count);
	}

	private static final class PlayerTarget implements Target {
		private final ServerPlayer player;

		private PlayerTarget(ServerPlayer player) {
			this.player = Objects.requireNonNull(player, "player must not be null");
		}

		@Override
		public void reset() {
			player.getInventory().clearContent();
			player.setItemSlot(EquipmentSlot.HEAD, ItemStack.EMPTY);
			player.setItemSlot(EquipmentSlot.CHEST, ItemStack.EMPTY);
			player.setItemSlot(EquipmentSlot.LEGS, ItemStack.EMPTY);
			player.setItemSlot(EquipmentSlot.FEET, ItemStack.EMPTY);
			player.setItemSlot(EquipmentSlot.OFFHAND, ItemStack.EMPTY);
		}

		@Override
		public void putInventory(int slot, String itemId, int count) {
			player.getInventory().setItem(slot, stack(itemId, count));
		}

		@Override
		public void equip(ScenarioLoadoutPlan.ArmorSlot slot, String itemId, int count) {
			player.setItemSlot(switch (slot) {
				case HEAD -> EquipmentSlot.HEAD;
				case CHEST -> EquipmentSlot.CHEST;
				case LEGS -> EquipmentSlot.LEGS;
				case FEET -> EquipmentSlot.FEET;
				case NONE -> throw new IllegalArgumentException("NONE is not an equipment slot");
			}, stack(itemId, count));
		}

		private static ItemStack stack(String itemId, int count) {
			Identifier identifier = Identifier.tryParse(itemId);
			if (identifier == null || !BuiltInRegistries.ITEM.containsKey(identifier)) {
				throw new IllegalStateException("Unknown scenario loadout item " + itemId);
			}
			return new ItemStack(BuiltInRegistries.ITEM.getValue(identifier), count);
		}
	}
}
