package dev.agaminggod.arenaagents.server.runtime.menu;

import dev.agaminggod.arenaagents.agent.AgentDomainException;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/** Vanilla menu inputs are available independently of specialized convenience adapters. */
public final class MenuCapabilityRegistry {
	private static final Set<String> VANILLA_MENUS = Set.of(
			"inventory", "generic_9x1", "generic_9x2", "generic_9x3", "generic_9x4", "generic_9x5", "generic_9x6",
			"generic_3x3", "crafter_3x3", "anvil", "beacon", "blast_furnace", "brewing_stand", "crafting",
			"enchantment", "furnace", "grindstone", "hopper", "lectern", "loom", "merchant", "shulker_box",
			"smithing", "smoker", "cartography_table", "stonecutter", "horse", "nautilus"
	);
	private static final List<String> GENERAL_CAPABILITIES = List.of("menu_click", "menu_close");
	private static final Map<String, List<String>> CAPABILITIES = Map.of(
			"minecraft:beacon", List.of("beacon_effects"),
			"minecraft:crafter_3x3", List.of("toggle_empty_slot"),
			"minecraft:lectern", List.of("select_page", "take_book"),
			"minecraft:merchant", List.of("select_trade", "transfer_exact"),
			"minecraft:enchantment", List.of("select_enchantment", "transfer_exact"),
			"minecraft:anvil", List.of("set_anvil_name", "transfer_exact", "take_result"),
			"minecraft:smithing", List.of("transfer_exact", "take_result"),
			"minecraft:brewing_stand", List.of("transfer_exact", "take_result"),
			"minecraft:loom", List.of("select_pattern", "transfer_exact", "take_result"),
			"minecraft:stonecutter", List.of("select_recipe", "transfer_exact", "take_result")
	);

	private MenuCapabilityRegistry() {
	}

	public static Optional<List<String>> capabilities(String menuId) {
		Objects.requireNonNull(menuId, "menuId must not be null");
		if (!menuId.startsWith("minecraft:") || !VANILLA_MENUS.contains(menuId.substring(10))) return Optional.empty();
		List<String> specialized = CAPABILITIES.get(menuId);
		if (specialized == null) return Optional.of(GENERAL_CAPABILITIES);
		return Optional.of(java.util.stream.Stream.concat(
				GENERAL_CAPABILITIES.stream(), specialized.stream()).toList());
	}

	public static List<String> requireSupported(String menuId) {
		return capabilities(menuId).orElseThrow(() -> new AgentDomainException(
				"UNSUPPORTED_MENU",
				"Unsupported or modded menu: " + menuId
		));
	}
}
