package dev.agaminggod.arenaagents.server.runtime.menu;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import java.nio.charset.StandardCharsets;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.AbstractFurnaceMenu;
import net.minecraft.world.inventory.AnvilMenu;
import net.minecraft.world.inventory.BeaconMenu;
import net.minecraft.world.inventory.BrewingStandMenu;
import net.minecraft.world.inventory.CrafterMenu;
import net.minecraft.world.inventory.EnchantmentMenu;
import net.minecraft.world.inventory.HorseInventoryMenu;
import net.minecraft.world.inventory.InventoryMenu;
import net.minecraft.world.inventory.LecternMenu;
import net.minecraft.world.inventory.LoomMenu;
import net.minecraft.world.inventory.MerchantMenu;
import net.minecraft.world.inventory.NautilusInventoryMenu;
import net.minecraft.world.inventory.StonecutterMenu;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.item.crafting.display.SlotDisplayContext;
import net.minecraft.world.level.block.entity.BeaconBlockEntity;

/** Information shown by vanilla menus, excluding hidden recipes, enchantment rolls and seeds. */
public final class MenuInspection {
	private static final int MAX_OPTION_BYTES = 6_000;
	private MenuInspection() {
	}

	public static String menuId(AbstractContainerMenu menu) {
		if (menu instanceof InventoryMenu) return "minecraft:inventory";
		if (menu instanceof HorseInventoryMenu) return "minecraft:horse";
		if (menu instanceof NautilusInventoryMenu) return "minecraft:nautilus";
		try {
			var id = BuiltInRegistries.MENU.getKey(menu.getType());
			return id == null ? "minecraft:unknown" : id.toString();
		} catch (UnsupportedOperationException noType) {
			return "minecraft:unknown";
		}
	}

	public static JsonObject details(ServerPlayer player) {
		return details(player, 0, 32);
	}

	public static JsonObject details(ServerPlayer player, int offset, int limit) {
		int start = Math.max(0, offset);
		int pageSize = Math.max(1, Math.min(32, limit));
		AbstractContainerMenu menu = player.containerMenu;
		JsonObject details = new JsonObject();
		if (menu instanceof MerchantMenu merchant) {
			JsonArray options = new JsonArray();
			var offers = merchant.getOffers();
			for (int index = start; index < offers.size() && index - start < pageSize; index++) {
				var offer = offers.get(index);
				JsonObject option = new JsonObject();
				option.addProperty("buttonId", index);
				option.add("costA", item(player, offer.getCostA()));
				option.add("costB", item(player, offer.getCostB()));
				option.add("result", item(player, offer.getResult()));
				option.addProperty("outOfStock", offer.isOutOfStock());
				if (!appendOption(options, option)) break;
			}
			details.addProperty("traderLevel", merchant.getTraderLevel());
			details.addProperty("traderXp", merchant.getTraderXp());
			details.addProperty("canRestock", merchant.canRestock());
			page(details, options, offers.size(), start);
		} else if (menu instanceof EnchantmentMenu enchantment) {
			JsonArray options = new JsonArray();
			for (int index = 0; index < enchantment.costs.length; index++) {
				JsonObject option = new JsonObject();
				option.addProperty("buttonId", index);
				option.addProperty("requiredLevel", enchantment.costs[index]);
				option.addProperty("levelCost", index + 1);
				option.addProperty("materialCost", index + 1);
				option.addProperty("clueLevel", enchantment.levelClue[index]);
				player.registryAccess().lookupOrThrow(Registries.ENCHANTMENT).get(enchantment.enchantClue[index])
						.flatMap(holder -> holder.unwrapKey())
						.ifPresent(key -> option.addProperty("clueEnchantmentId", key.identifier().toString()));
				option.addProperty("enabled", enchantment.costs[index] > 0 && (player.isCreative()
						|| player.experienceLevel >= enchantment.costs[index] && enchantment.getGoldCount() >= index + 1));
				if (!appendOption(options, option)) break;
			}
			details.addProperty("materialCount", enchantment.getGoldCount());
			page(details, options, options.size(), 0);
		} else if (menu instanceof AnvilMenu anvil) {
			details.addProperty("levelCost", anvil.getCost());
			details.addProperty("canTakeResult", anvil.getSlot(AnvilMenu.RESULT_SLOT).mayPickup(player)
					&& anvil.getSlot(AnvilMenu.RESULT_SLOT).hasItem());
		} else if (menu instanceof AbstractFurnaceMenu furnace) {
			details.addProperty("lit", furnace.isLit());
			details.addProperty("cookingProgress", furnace.getBurnProgress());
			details.addProperty("fuelProgress", furnace.getLitProgress());
		} else if (menu instanceof BrewingStandMenu brewing) {
			details.addProperty("fuel", brewing.getFuel());
			details.addProperty("brewingTicks", brewing.getBrewingTicks());
		} else if (menu instanceof LoomMenu loom) {
			JsonArray options = new JsonArray();
			var patterns = loom.getSelectablePatterns();
			for (int index = start; index < patterns.size() && index - start < pageSize; index++) {
				JsonObject option = new JsonObject();
				option.addProperty("buttonId", index);
				patterns.get(index).unwrapKey().ifPresent(key -> option.addProperty("patternId", key.identifier().toString()));
				if (!appendOption(options, option)) break;
			}
			details.addProperty("selectedOption", loom.getSelectedBannerPatternIndex());
			page(details, options, patterns.size(), start);
		} else if (menu instanceof StonecutterMenu stonecutter) {
			JsonArray options = new JsonArray();
			var entries = stonecutter.getVisibleRecipes().entries();
			var context = SlotDisplayContext.fromLevel(player.level());
			for (int index = start; index < entries.size() && index - start < pageSize; index++) {
				JsonObject option = new JsonObject();
				option.addProperty("buttonId", index);
				var recipe = entries.get(index).recipe();
				recipe.recipe().ifPresent(holder -> option.addProperty("recipeId", holder.id().identifier().toString()));
				option.add("result", item(player, recipe.optionDisplay().resolveForFirstStack(context)));
				if (!appendOption(options, option)) break;
			}
			details.addProperty("selectedOption", stonecutter.getSelectedRecipeIndex());
			page(details, options, entries.size(), start);
		} else if (menu instanceof BeaconMenu beacon) {
			details.addProperty("levels", beacon.getLevels());
			details.addProperty("hasPayment", beacon.hasPayment());
			details.addProperty("primaryEffect", "none");
			details.addProperty("secondaryEffect", "none");
			if (beacon.getPrimaryEffect() != null) beacon.getPrimaryEffect().unwrapKey()
					.ifPresent(key -> details.addProperty("primaryEffect", key.identifier().toString()));
			if (beacon.getSecondaryEffect() != null) beacon.getSecondaryEffect().unwrapKey()
					.ifPresent(key -> details.addProperty("secondaryEffect", key.identifier().toString()));
			JsonArray primaryOptions = new JsonArray();
			JsonArray secondaryOptions = new JsonArray();
			for (int tier = 0; tier < BeaconBlockEntity.BEACON_EFFECTS.size(); tier++) {
				for (var effect : BeaconBlockEntity.BEACON_EFFECTS.get(tier)) {
					JsonObject option = new JsonObject();
					effect.unwrapKey().ifPresent(key -> option.addProperty("effectId", key.identifier().toString()));
					option.addProperty("requiredLevel", tier + 1);
					option.addProperty("enabled", beacon.getLevels() >= tier + 1);
					(tier < 3 ? primaryOptions : secondaryOptions).add(option);
				}
			}
			details.add("primaryOptions", primaryOptions);
			details.add("secondaryOptions", secondaryOptions);
			details.addProperty("secondaryMayMatchPrimary", beacon.getLevels() >= 4);
			details.addProperty("secondaryMayBeNone", true);
		} else if (menu instanceof CrafterMenu crafter) {
			JsonArray options = new JsonArray();
			for (int index = 0; index < 9; index++) {
				JsonObject option = new JsonObject();
				option.addProperty("buttonId", index);
				option.addProperty("slot", index);
				option.addProperty("disabled", crafter.isSlotDisabled(index));
				option.addProperty("canToggle", !crafter.getSlot(index).hasItem());
				options.add(option);
			}
			details.addProperty("powered", crafter.isPowered());
			page(details, options, options.size(), 0);
		} else if (menu instanceof LecternMenu lectern) {
			details.addProperty("page", lectern.getPage());
			details.addProperty("previousPageButton", LecternMenu.BUTTON_PREV_PAGE);
			details.addProperty("nextPageButton", LecternMenu.BUTTON_NEXT_PAGE);
			details.addProperty("takeBookButton", LecternMenu.BUTTON_TAKE_BOOK);
		}
		return details;
	}

	private static void page(JsonObject details, JsonArray options, int total, int offset) {
		details.add("options", options);
		details.addProperty("optionCount", total);
		details.addProperty("optionOffset", offset);
		details.addProperty("optionReturned", options.size());
		details.addProperty("hasMoreOptions", offset + options.size() < total);
		if (offset + options.size() < total) details.addProperty("nextOptionOffset", offset + options.size());
	}

	private static boolean appendOption(JsonArray options, JsonObject option) {
		if (!options.isEmpty() && options.toString().getBytes(StandardCharsets.UTF_8).length
				+ option.toString().getBytes(StandardCharsets.UTF_8).length + 1 > MAX_OPTION_BYTES) return false;
		options.add(option);
		return true;
	}

	private static JsonObject item(ServerPlayer player, ItemStack stack) {
		JsonObject item = new JsonObject();
		item.addProperty("itemId", stack.isEmpty() ? "minecraft:air" : BuiltInRegistries.ITEM.getKey(stack.getItem()).toString());
		item.addProperty("count", stack.getCount());
		item.addProperty("fingerprint", MenuStackIdentity.fingerprint(stack, player.registryAccess()));
		if (!stack.isEmpty()) {
			JsonArray tooltip = new JsonArray();
			var lines = stack.getTooltipLines(Item.TooltipContext.of(player.level()), player, TooltipFlag.NORMAL);
			boolean truncated = lines.size() > 8;
			int remainingBytes = 1_200;
			for (int index = 0; index < lines.size() && index < 8; index++) {
				String line = lines.get(index).getString();
				if (remainingBytes < 4) { truncated = true; break; }
				String bounded = boundedText(line, Math.min(remainingBytes, 200));
				truncated |= !bounded.equals(line);
				tooltip.add(bounded);
				remainingBytes -= new JsonPrimitive(bounded).toString().getBytes(StandardCharsets.UTF_8).length + 1;
			}
			item.add("tooltip", tooltip);
			item.addProperty("tooltipTruncated", truncated);
		}
		return item;
	}

	private static String boundedText(String text, int maxJsonBytes) {
		String bounded = text.substring(0, text.offsetByCodePoints(0, Math.min(160, text.codePointCount(0, text.length()))));
		while (!bounded.isEmpty() && new JsonPrimitive(bounded).toString().getBytes(StandardCharsets.UTF_8).length > maxJsonBytes) {
			bounded = bounded.substring(0, bounded.offsetByCodePoints(bounded.length(), -1));
		}
		return bounded;
	}
}
