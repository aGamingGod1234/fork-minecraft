package dev.agaminggod.arenaagents.server.perception;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import net.minecraft.core.Holder;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.component.DataComponentMap;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.tags.TagKey;
import net.minecraft.util.context.ContextMap;
import net.minecraft.world.flag.FeatureFlags;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStackTemplate;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.crafting.display.FurnaceRecipeDisplay;
import net.minecraft.world.item.crafting.display.ShapedCraftingRecipeDisplay;
import net.minecraft.world.item.crafting.display.ShapelessCraftingRecipeDisplay;
import net.minecraft.world.item.crafting.display.SlotDisplay;
import net.minecraft.world.item.crafting.display.SlotDisplayContext;
import net.minecraft.world.item.crafting.display.SmithingRecipeDisplay;
import net.minecraft.world.level.block.entity.FuelValues;

public final class PlayerKnowledgeInspectionVerification {
	private PlayerKnowledgeInspectionVerification() { }

	public static int verify() {
		net.minecraft.SharedConstants.tryDetectVersion();
		net.minecraft.server.Bootstrap.bootStrap();
		try (ComponentBindings ignored = new ComponentBindings(
				Items.OAK_PLANKS, Items.STICK, Items.CRAFTING_TABLE, Items.MILK_BUCKET,
				Items.BUCKET, Items.BIRCH_PLANKS, Items.IRON_ORE, Items.IRON_INGOT,
				Items.FURNACE, Items.COAL, Items.NETHERITE_UPGRADE_SMITHING_TEMPLATE,
				Items.DIAMOND_CHESTPLATE, Items.NETHERITE_INGOT, Items.NETHERITE_CHESTPLATE,
				Items.SMITHING_TABLE, Items.POTION)) {
			ContextMap context = new ContextMap.Builder().create(SlotDisplayContext.CONTEXT);
			return verifyShaped(context) + verifyShapeless(context) + verifyFurnace()
					+ verifySmithing(context) + verifyBoundsAndUnresolved(context);
		}
	}

	public static void main(String[] args) {
		System.out.println("PASS: " + verify() + " player knowledge inspection assertions");
	}

	private static int verifyShaped(ContextMap context) {
		List<SlotDisplay> ingredients = List.of(
				item(Items.OAK_PLANKS), SlotDisplay.Empty.INSTANCE,
				item(Items.OAK_PLANKS), SlotDisplay.Empty.INSTANCE);
		ItemStackTemplate resultTemplate = new ItemStackTemplate(Items.STICK, 4);
		var display = new ShapedCraftingRecipeDisplay(2, 2, ingredients,
				new SlotDisplay.ItemStackSlotDisplay(resultTemplate), item(Items.CRAFTING_TABLE));
		JsonObject result = PlayerKnowledgeInspection.display(display, context);
		JsonArray slots = result.getAsJsonArray("ingredients");
		assertEquals("shaped_row_major", result.get("layout").getAsString(), "shaped layout names its order");
		assertEquals(2, result.get("width").getAsInt(), "shaped width");
		assertEquals(2, result.get("height").getAsInt(), "shaped height");
		assertEquals(4, slots.size(), "empty cells remain in the shaped grid");
		assertEquals("minecraft:oak_planks", firstItem(slots.get(0).getAsJsonObject()), "first grid cell");
		assertEquals(true, slots.get(1).getAsJsonObject().get("empty").getAsBoolean(), "second grid cell is empty");
		assertEquals(2, slots.get(2).getAsJsonObject().get("index").getAsInt(), "second row keeps its index");
		assertEquals(true, slots.get(3).getAsJsonObject().get("empty").getAsBoolean(), "last grid cell is empty");
		assertEquals(4, result.getAsJsonObject("result").getAsJsonArray("alternatives")
				.get(0).getAsJsonObject().get("count").getAsInt(), "recipe output quantity");
		assertEquals(ingredients, display.ingredients(), "inspection preserves ingredient definitions");
		assertEquals(4, resultTemplate.count(), "inspection preserves result quantity");
		return 11;
	}

	private static int verifyShapeless(ContextMap context) {
		SlotDisplay planks = new SlotDisplay.Composite(List.of(item(Items.OAK_PLANKS), item(Items.BIRCH_PLANKS)));
		SlotDisplay milk = new SlotDisplay.WithRemainder(item(Items.MILK_BUCKET), item(Items.BUCKET));
		List<SlotDisplay> ingredients = new ArrayList<>(List.of(planks, planks, milk));
		List<SlotDisplay> original = List.copyOf(ingredients);
		var display = new ShapelessCraftingRecipeDisplay(ingredients, item(Items.STICK), item(Items.CRAFTING_TABLE));
		JsonObject result = PlayerKnowledgeInspection.display(display, context);
		JsonArray slots = result.getAsJsonArray("ingredients");
		assertEquals("shapeless", result.get("layout").getAsString(), "shapeless layout");
		assertEquals(3, slots.size(), "repeated ingredients remain separate requirements");
		assertEquals(2, slots.get(0).getAsJsonObject().getAsJsonArray("alternatives").size(), "one slot contains alternatives");
		assertEquals(2, slots.get(1).getAsJsonObject().getAsJsonArray("alternatives").size(), "duplicate requirement retains alternatives");
		assertEquals("minecraft:milk_bucket", firstItem(slots.get(2).getAsJsonObject()), "remainder wrapper retains its input");
		assertEquals(original, ingredients, "inspection leaves mutable source ingredients unchanged");
		assertEquals(3, result.get("ingredientSlotsTotal").getAsInt(), "required slot count");
		assertEquals(false, result.get("ingredientSlotsTruncated").getAsBoolean(), "small recipe is complete");
		return 8;
	}

	private static int verifyFurnace() {
		var registries = RegistryAccess.fromRegistryOfRegistries(BuiltInRegistries.REGISTRY);
		FuelValues fuel = new FuelValues.Builder(registries, FeatureFlags.VANILLA_SET).add(Items.COAL, 1600).build();
		ContextMap context = new ContextMap.Builder()
				.withParameter(SlotDisplayContext.REGISTRIES, registries)
				.withParameter(SlotDisplayContext.FUEL_VALUES, fuel)
				.create(SlotDisplayContext.CONTEXT);
		var display = new FurnaceRecipeDisplay(item(Items.IRON_ORE), SlotDisplay.AnyFuel.INSTANCE,
				item(Items.IRON_INGOT), item(Items.FURNACE), 200, 0.7f);
		JsonObject result = PlayerKnowledgeInspection.display(display, context);
		assertEquals("cooking", result.get("layout").getAsString(), "furnace layout");
		assertEquals(200, result.get("durationTicks").getAsInt(), "cooking duration remains server ticks");
		assertEquals(0.7f, result.get("experience").getAsFloat(), "cooking experience");
		assertEquals("minecraft:iron_ore", firstItem(result.getAsJsonArray("ingredients").get(0).getAsJsonObject()), "cooking input");
		assertEquals("minecraft:coal", firstItem(result.getAsJsonObject("fuel")), "fuel resolves using supplied fuel values");
		assertEquals("minecraft:iron_ingot", firstItem(result.getAsJsonObject("result")), "cooking result");
		assertEquals("minecraft:furnace", firstItem(result.getAsJsonObject("craftingStation")), "cooking station");
		return 7;
	}

	private static int verifySmithing(ContextMap context) {
		var display = new SmithingRecipeDisplay(item(Items.NETHERITE_UPGRADE_SMITHING_TEMPLATE),
				item(Items.DIAMOND_CHESTPLATE), item(Items.NETHERITE_INGOT),
				item(Items.NETHERITE_CHESTPLATE), item(Items.SMITHING_TABLE));
		JsonObject result = PlayerKnowledgeInspection.display(display, context);
		JsonArray slots = result.getAsJsonArray("ingredients");
		assertEquals("smithing_template_base_addition", result.get("layout").getAsString(), "smithing layout names ingredient roles");
		assertEquals(3, slots.size(), "smithing has three ingredient roles");
		assertEquals("minecraft:netherite_upgrade_smithing_template", firstItem(slots.get(0).getAsJsonObject()), "template is first");
		assertEquals("minecraft:diamond_chestplate", firstItem(slots.get(1).getAsJsonObject()), "base is second");
		assertEquals("minecraft:netherite_ingot", firstItem(slots.get(2).getAsJsonObject()), "addition is third");
		assertEquals("minecraft:netherite_chestplate", firstItem(result.getAsJsonObject("result")), "smithing result");
		assertEquals("minecraft:smithing_table", firstItem(result.getAsJsonObject("craftingStation")), "smithing station");
		return 7;
	}

	private static int verifyBoundsAndUnresolved(ContextMap context) {
		List<SlotDisplay> many = new ArrayList<>();
		for (int index = 0; index < 32; index++) many.add(item(Items.OAK_PLANKS));
		JsonObject bounded = PlayerKnowledgeInspection.slot(new SlotDisplay.Composite(many), context);
		JsonArray alternatives = bounded.getAsJsonArray("alternatives");
		assertTrue(!alternatives.isEmpty() && alternatives.size() <= 16, "alternatives obey the count limit");
		assertTrue(alternatives.toString().getBytes(StandardCharsets.UTF_8).length <= 600, "alternatives obey the byte limit");
		assertEquals(true, bounded.get("alternativesTruncated").getAsBoolean(), "omitted alternatives are explicit");
		JsonObject largeRecipe = PlayerKnowledgeInspection.display(new ShapelessCraftingRecipeDisplay(
				many, item(Items.STICK), item(Items.CRAFTING_TABLE)), context);
		assertEquals(9, largeRecipe.getAsJsonArray("ingredients").size(), "ingredient output is bounded");
		assertEquals(32, largeRecipe.get("ingredientSlotsTotal").getAsInt(), "uncut ingredient count is reported");
		assertEquals(true, largeRecipe.get("ingredientSlotsTruncated").getAsBoolean(), "omitted ingredient slots are explicit");
		JsonObject empty = PlayerKnowledgeInspection.slot(SlotDisplay.Empty.INSTANCE, context);
		assertEquals(true, empty.get("empty").getAsBoolean(), "empty grid cells are explicit");
		assertEquals(0, empty.getAsJsonArray("alternatives").size(), "empty cells have no alternatives");
		var tag = new SlotDisplay.TagSlotDisplay(TagKey.create(Registries.ITEM, Identifier.parse("test:unresolved")));
		JsonObject unresolved = PlayerKnowledgeInspection.slot(tag, context);
		assertEquals(false, unresolved.get("empty").getAsBoolean(), "unresolved tag is not an empty requirement");
		assertEquals("no_display_examples", unresolved.get("resolution").getAsString(), "missing tag context is reported as unresolved");
		assertEquals("test:unresolved", unresolved.get("tag").getAsString(), "unresolved tag identity remains available");
		JsonObject fuel = PlayerKnowledgeInspection.slot(SlotDisplay.AnyFuel.INSTANCE, context);
		assertEquals("no_display_examples", fuel.get("resolution").getAsString(), "missing fuel context is not impossibility");
		JsonObject dynamic = PlayerKnowledgeInspection.slot(new SlotDisplay.WithAnyPotion(item(Items.POTION)), context);
		assertEquals("dynamic_display_not_expanded", dynamic.get("resolution").getAsString(), "dynamic output is not claimed exhaustive");
		assertEquals(0, dynamic.getAsJsonArray("alternatives").size(), "dynamic display expansion is skipped");
		return 14;
	}

	private static SlotDisplay item(Item item) {
		return new SlotDisplay.ItemSlotDisplay(item);
	}

	private static String firstItem(JsonObject slot) {
		return slot.getAsJsonArray("alternatives").get(0).getAsJsonObject().get("itemId").getAsString();
	}

	private static void assertEquals(Object expected, Object actual, String label) {
		if (!Objects.equals(expected, actual)) throw new AssertionError(label + ": expected <" + expected + "> but was <" + actual + ">");
	}

	private static void assertTrue(boolean condition, String label) {
		if (!condition) throw new AssertionError(label);
	}

	private static final class ComponentBindings implements AutoCloseable {
		private final List<Holder.Reference<Item>> temporary = new ArrayList<>();

		private ComponentBindings(Item... items) {
			for (Item item : items) {
				Holder.Reference<Item> holder = item.builtInRegistryHolder();
				if (holder.areComponentsBound()) continue;
				temporary.add(holder);
				holder.bindComponents(DataComponentMap.builder().set(DataComponents.MAX_STACK_SIZE, 64).build());
			}
		}

		@Override
		public void close() {
			try {
				Field components = Holder.Reference.class.getDeclaredField("components");
				components.setAccessible(true);
				for (Holder.Reference<Item> holder : temporary) components.set(holder, null);
			} catch (ReflectiveOperationException exception) {
				throw new AssertionError("could not restore temporary item components", exception);
			}
		}
	}
}
