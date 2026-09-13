package dev.agaminggod.arenaagents.server.runtime.menu;

import dev.agaminggod.arenaagents.agent.AgentDomainException;
import java.util.List;

public final class MenuCapabilityRegistryVerification {
	private MenuCapabilityRegistryVerification() {
	}

	public static int verify() {
		int assertions = 0;
		for (String menuId : List.of(
				"minecraft:inventory", "minecraft:hopper", "minecraft:shulker_box", "minecraft:generic_9x6",
				"minecraft:grindstone", "minecraft:cartography_table", "minecraft:beacon", "minecraft:horse",
				"minecraft:merchant",
				"minecraft:enchantment",
				"minecraft:anvil",
				"minecraft:smithing",
				"minecraft:brewing_stand",
				"minecraft:loom",
				"minecraft:stonecutter"
		)) {
			if (!MenuCapabilityRegistry.requireSupported(menuId).containsAll(List.of("menu_click", "menu_close"))) {
				throw new AssertionError("Expected capabilities for " + menuId);
			}
			assertions++;
		}
		try {
			MenuCapabilityRegistry.requireSupported("example:modded_menu");
			throw new AssertionError("Unknown menus must fail closed");
		} catch (AgentDomainException exception) {
			if (!"UNSUPPORTED_MENU".equals(exception.code())) throw new AssertionError(exception);
			assertions++;
		}
		return assertions;
	}
}
