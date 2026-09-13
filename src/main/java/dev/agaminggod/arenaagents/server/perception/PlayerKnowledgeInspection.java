package dev.agaminggod.arenaagents.server.perception;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import dev.agaminggod.arenaagents.agent.AgentDomainException;
import java.nio.charset.StandardCharsets;
import java.util.Comparator;
import java.util.List;
import net.minecraft.SharedConstants;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.context.ContextMap;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.RecipeHolder;
import net.minecraft.world.item.crafting.display.DisplayContentsFactory;
import net.minecraft.world.item.crafting.display.FurnaceRecipeDisplay;
import net.minecraft.world.item.crafting.display.RecipeDisplay;
import net.minecraft.world.item.crafting.display.ShapedCraftingRecipeDisplay;
import net.minecraft.world.item.crafting.display.ShapelessCraftingRecipeDisplay;
import net.minecraft.world.item.crafting.display.SlotDisplay;
import net.minecraft.world.item.crafting.display.SlotDisplayContext;
import net.minecraft.world.item.crafting.display.SmithingRecipeDisplay;
import net.minecraft.world.item.crafting.display.StonecutterRecipeDisplay;

/** Installed recipe displays and the player's current mechanics, without exploration data. */
public final class PlayerKnowledgeInspection {
	private static final int MAX_INGREDIENT_SLOTS = 9;
	private static final int MAX_ALTERNATIVES = 16;
	private static final int MAX_ALTERNATIVE_BYTES = 600;
	private static final DisplayContentsFactory.ForStacks<ItemStack> STACKS = stack -> stack;

	private PlayerKnowledgeInspection() { }

	public static JsonObject recipes(ServerPlayer player, JsonObject query, int offset, int limit) {
		var manager = player.level().getServer().getRecipeManager();
		ContextMap context = SlotDisplayContext.fromLevel(player.level());
		JsonObject page;
		if (query.has("recipeId")) {
			Identifier id = Identifier.tryParse(query.get("recipeId").getAsString());
			if (id == null) throw new AgentDomainException("INVALID_INSPECTION", "Invalid recipeId");
			RecipeHolder<?> holder = manager.byKey(ResourceKey.create(Registries.RECIPE, id))
					.orElseThrow(() -> new AgentDomainException("RECIPE_NOT_FOUND", "Recipe is not installed: " + id));
			List<RecipeDisplay> displays = holder.value().display();
			page = ObservationPage.collect(displays.size(), offset, limit, index -> {
				JsonObject display = display(displays.get(index), context);
				display.addProperty("displayIndex", index);
				display.addProperty("enabled", displays.get(index).isEnabled(player.level().enabledFeatures()));
				return display;
			}, "installed_recipe_displays");
			page.add("recipe", summary(holder, player, context));
			page.addProperty("fixedDisplayAvailable", !displays.isEmpty());
		} else {
			List<RecipeHolder<?>> recipes = manager.getRecipes().stream()
					.sorted(Comparator.comparing(holder -> holder.id().identifier().toString())).toList();
			page = ObservationPage.collect(recipes.size(), offset, limit,
					index -> summary(recipes.get(index), player, context), "installed_recipe_registry");
		}
		page.addProperty("knowledgeScope", "installed_recipes_including_not_yet_unlocked");
		page.addProperty("craftabilityChecked", false);
		page.addProperty("stackComponentsAndRemaindersIncluded", false);
		page.addProperty("displayItemsAreExamples", true);
		return page;
	}

	private static JsonObject summary(RecipeHolder<?> holder, ServerPlayer player, ContextMap context) {
		JsonObject result = new JsonObject();
		result.addProperty("recipeId", boundedId(holder.id().identifier().toString()));
		result.addProperty("recipeIdTruncated", holder.id().identifier().toString().length() > 256);
		result.addProperty("type", boundedId(BuiltInRegistries.RECIPE_TYPE.getKey(holder.value().getType()).toString()));
		result.addProperty("recipebookKnown", player.getRecipeBook().contains(holder.id()));
		result.addProperty("special", holder.value().isSpecial());
		List<RecipeDisplay> displays = holder.value().display();
		result.addProperty("displayCount", displays.size());
		if (!displays.isEmpty()) {
			result.add("firstDisplayResult", slot(displays.getFirst().result(), context));
			result.addProperty("firstDisplayEnabled", displays.getFirst().isEnabled(player.level().enabledFeatures()));
		}
		return result;
	}

	static JsonObject display(RecipeDisplay display, ContextMap context) {
		JsonObject result = new JsonObject();
		result.addProperty("displayType", boundedId(BuiltInRegistries.RECIPE_DISPLAY.getKey(display.type()).toString()));
		result.add("result", slot(display.result(), context));
		result.add("craftingStation", slot(display.craftingStation(), context));
		List<SlotDisplay> ingredients;
		if (display instanceof ShapedCraftingRecipeDisplay shaped) {
			result.addProperty("layout", "shaped_row_major");
			result.addProperty("width", shaped.width());
			result.addProperty("height", shaped.height());
			ingredients = shaped.ingredients();
		} else if (display instanceof ShapelessCraftingRecipeDisplay shapeless) {
			result.addProperty("layout", "shapeless");
			ingredients = shapeless.ingredients();
		} else if (display instanceof FurnaceRecipeDisplay furnace) {
			result.addProperty("layout", "cooking");
			result.addProperty("durationTicks", furnace.duration());
			result.addProperty("experience", furnace.experience());
			result.add("fuel", slot(furnace.fuel(), context));
			ingredients = List.of(furnace.ingredient());
		} else if (display instanceof StonecutterRecipeDisplay stonecutter) {
			result.addProperty("layout", "stonecutting");
			ingredients = List.of(stonecutter.input());
		} else if (display instanceof SmithingRecipeDisplay smithing) {
			result.addProperty("layout", "smithing_template_base_addition");
			ingredients = List.of(smithing.template(), smithing.base(), smithing.addition());
		} else {
			result.addProperty("layout", "unsupported_display");
			result.addProperty("ingredientsIncluded", false);
			return result;
		}
		JsonArray slots = new JsonArray();
		for (int index = 0; index < Math.min(ingredients.size(), MAX_INGREDIENT_SLOTS); index++) {
			JsonObject ingredient = slot(ingredients.get(index), context);
			ingredient.addProperty("index", index);
			slots.add(ingredient);
		}
		result.add("ingredients", slots);
		result.addProperty("ingredientSlotsTotal", ingredients.size());
		result.addProperty("ingredientSlotsTruncated", ingredients.size() > slots.size());
		result.addProperty("ingredientsIncluded", true);
		return result;
	}

	static JsonObject slot(SlotDisplay display, ContextMap context) {
		JsonObject result = new JsonObject();
		result.addProperty("displayType", boundedId(BuiltInRegistries.SLOT_DISPLAY.getKey(display.type()).toString()));
		result.addProperty("empty", display instanceof SlotDisplay.Empty);
		if (display instanceof SlotDisplay.TagSlotDisplay tag) {
			result.addProperty("tag", boundedId(tag.tag().location().toString()));
			result.addProperty("tagTruncated", tag.tag().location().toString().length() > 256);
		}
		JsonArray alternatives = new JsonArray();
		if (!boundedResolution(display, 0, new int[] {64})) {
			result.add("alternatives", alternatives);
			result.addProperty("alternativesTruncated", true);
			result.addProperty("resolution", "dynamic_display_not_expanded");
			return result;
		}
		boolean truncated = false;
		int bytes = 2;
		try (var values = display.resolve(context, STACKS)) {
			var iterator = values.limit(MAX_ALTERNATIVES + 1L).iterator();
			while (iterator.hasNext()) {
				ItemStack stack = iterator.next();
				JsonObject item = new JsonObject();
				item.addProperty("itemId", boundedId(BuiltInRegistries.ITEM.getKey(stack.getItem()).toString()));
				item.addProperty("itemIdTruncated", BuiltInRegistries.ITEM.getKey(stack.getItem()).toString().length() > 256);
				item.addProperty("count", stack.getCount());
				int size = item.toString().getBytes(StandardCharsets.UTF_8).length + 1;
				if (alternatives.size() >= MAX_ALTERNATIVES || bytes + size > MAX_ALTERNATIVE_BYTES) {
					truncated = true;
					break;
				}
				alternatives.add(item);
				bytes += size;
			}
		}
		result.add("alternatives", alternatives);
		result.addProperty("alternativesTruncated", truncated);
		result.addProperty("resolution", alternatives.isEmpty() && !(display instanceof SlotDisplay.Empty) ? "no_display_examples" : "display_examples");
		return result;
	}

	private static boolean boundedResolution(SlotDisplay display, int depth, int[] remaining) {
		if (depth > 8 || --remaining[0] < 0) return false;
		if (display instanceof SlotDisplay.Composite composite) {
			if (composite.contents().size() > remaining[0]) return false;
			for (SlotDisplay child : composite.contents()) if (!boundedResolution(child, depth + 1, remaining)) return false;
			return true;
		}
		if (display instanceof SlotDisplay.WithRemainder remainder) return boundedResolution(remainder.input(), depth + 1, remaining);
		return display instanceof SlotDisplay.Empty || display instanceof SlotDisplay.ItemSlotDisplay
				|| display instanceof SlotDisplay.ItemStackSlotDisplay || display instanceof SlotDisplay.TagSlotDisplay
				|| display instanceof SlotDisplay.AnyFuel;
	}

	public static JsonObject mechanics(ServerPlayer player) {
		JsonObject result = new JsonObject();
		result.addProperty("source", "installed_version_and_current_player_attributes");
		result.addProperty("minecraftVersion", SharedConstants.getCurrentVersion().name());
		result.addProperty("protocolVersion", SharedConstants.getProtocolVersion());
		result.addProperty("nominalTicksPerSecond", SharedConstants.TICKS_PER_SECOND);
		result.addProperty("configuredTicksPerSecond", player.level().getServer().tickRateManager().tickrate());
		result.addProperty("blockInteractionRange", player.blockInteractionRange());
		result.addProperty("entityInteractionRange", player.entityInteractionRange());
		result.addProperty("eyeHeight", player.getEyeHeight());
		result.addProperty("boundingBoxWidth", player.getBbWidth());
		result.addProperty("boundingBoxHeight", player.getBbHeight());
		JsonArray attributes = new JsonArray();
		for (var type : List.of(Attributes.MOVEMENT_SPEED, Attributes.SNEAKING_SPEED, Attributes.FLYING_SPEED,
				Attributes.JUMP_STRENGTH, Attributes.GRAVITY, Attributes.STEP_HEIGHT, Attributes.SAFE_FALL_DISTANCE,
				Attributes.FALL_DAMAGE_MULTIPLIER, Attributes.WATER_MOVEMENT_EFFICIENCY, Attributes.MOVEMENT_EFFICIENCY,
				Attributes.ATTACK_DAMAGE, Attributes.ATTACK_SPEED, Attributes.BLOCK_BREAK_SPEED,
				Attributes.MINING_EFFICIENCY, Attributes.SUBMERGED_MINING_SPEED, Attributes.OXYGEN_BONUS)) {
			var attribute = player.getAttribute(type);
			if (attribute == null) continue;
			JsonObject fact = new JsonObject();
			fact.addProperty("id", type.unwrapKey().orElseThrow().identifier().toString());
			fact.addProperty("baseValue", attribute.getBaseValue());
			fact.addProperty("currentValue", attribute.getValue());
			attributes.add(fact);
		}
		result.add("attributes", attributes);
		JsonObject abilities = new JsonObject();
		abilities.addProperty("mayFly", player.getAbilities().mayfly);
		abilities.addProperty("flying", player.getAbilities().flying);
		abilities.addProperty("mayBuild", player.getAbilities().mayBuild);
		abilities.addProperty("instantBuild", player.getAbilities().instabuild);
		abilities.addProperty("walkingSpeed", player.getAbilities().getWalkingSpeed());
		abilities.addProperty("flyingSpeed", player.getAbilities().getFlyingSpeed());
		result.add("abilities", abilities);
		JsonObject input = new JsonObject();
		input.addProperty("forward", "-1 backward, 0 neutral, 1 forward relative to yaw");
		input.addProperty("strafe", "-1 right, 0 neutral, 1 left relative to yaw");
		input.addProperty("yaw", "degrees: 0 south, 90 west, 180 north, -90 east");
		input.addProperty("pitch", "degrees: -90 up, 0 level, 90 down");
		input.addProperty("duration", "control and control_sequence frames count server ticks");
		input.addProperty("physics", "vanilla movement uses current attributes, pose, vehicle, effects and collisions");
		result.add("input", input);
		return result;
	}

	private static String boundedId(String value) {
		return value.length() <= 256 ? value : value.substring(0, 256);
	}
}
