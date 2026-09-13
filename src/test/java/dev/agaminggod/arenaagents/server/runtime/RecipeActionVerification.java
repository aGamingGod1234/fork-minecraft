package dev.agaminggod.arenaagents.server.runtime;

import dev.agaminggod.arenaagents.agent.AgentDomainException;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.stats.ServerRecipeBook;
import net.minecraft.world.item.crafting.Recipe;

public final class RecipeActionVerification {
	private RecipeActionVerification() {
	}

	public static int verify() {
		assertEquals("minecraft:stick", AdvancedInteractionService.canonicalRecipeId("minecraft:sticks"),
				"singularizes the vanilla stick recipe alias");
		assertEquals("minecraft:oak_planks", AdvancedInteractionService.canonicalRecipeId("minecraft:oak_planks"),
				"preserves canonical recipe IDs");

		ServerRecipeBook recipeBook = new ServerRecipeBook((key, displays) -> {
		});
		ResourceKey<Recipe<?>> recipeKey = ResourceKey.create(
				Registries.RECIPE,
				Identifier.parse("minecraft:wooden_pickaxe")
		);
		assertFalse(recipeBook.contains(recipeKey), "recipe starts locked");
		assertTrue(AdvancedInteractionService.recipeAllowed(recipeBook, recipeKey, false),
				"ordinary crafting permits a known grid without granting a recipe unlock");
		assertFalse(recipeBook.contains(recipeKey), "checking craft access leaves the recipe locked");
		assertFalse(AdvancedInteractionService.recipeAllowed(recipeBook, recipeKey, true),
				"limited crafting rejects a locked recipe");
		recipeBook.add(recipeKey);
		assertTrue(AdvancedInteractionService.recipeAllowed(recipeBook, recipeKey, true),
				"limited crafting accepts an already unlocked recipe");

		Map<String, Set<String>> loadedPlankRecipes = Map.of(
				"minecraft:oak_planks", Set.of("minecraft:oak_log"),
				"minecraft:birch_planks", Set.of("minecraft:birch_log")
		);
		assertEquals("minecraft:oak_planks", AdvancedInteractionService.resolveGenericPlankRecipeId(
				"minecraft:planks", Map.of("minecraft:oak_planks", Set.of("minecraft:oak_log")),
				List.of("minecraft:oak_log")),
				"resolves generic planks from one observed log species");
		expectFailure("RECIPE_AMBIGUOUS", () -> AdvancedInteractionService.resolveGenericPlankRecipeId(
				"minecraft:planks", loadedPlankRecipes,
				List.of("minecraft:oak_log", "minecraft:birch_log")),
				"rejects generic planks when observed logs identify multiple species");
		expectFailure("RECIPE_NOT_FOUND", () -> AdvancedInteractionService.resolveGenericPlankRecipeId(
				"minecraft:planks", loadedPlankRecipes,
				List.of("minecraft:spruce_log")),
				"rejects generic planks when no loaded recipe matches observed ingredients");
		assertTrue(AdvancedInteractionService.craftOutputSatisfiesRequest(4, 1),
				"one vanilla plank craft may exceed the requested minimum");
		assertTrue(AdvancedInteractionService.craftOutputSatisfiesRequest(4, 4),
				"an exact vanilla craft output satisfies the request");
		assertFalse(AdvancedInteractionService.craftOutputSatisfiesRequest(4, 5),
				"one craft cannot claim an output larger than it produced");
		assertEquals("RECIPE_INPUTS_UNAVAILABLE", AdvancedInteractionService.craftPlacementFailureReason(
				net.minecraft.world.inventory.RecipeBookMenu.PostPlaceAction.PLACE_GHOST_RECIPE),
				"vanilla ghost placement reports missing inputs instead of generic placement rejection");
		assertEquals("RECIPE_PLACEMENT_REJECTED", AdvancedInteractionService.craftPlacementFailureReason(
				net.minecraft.world.inventory.RecipeBookMenu.PostPlaceAction.NOTHING),
				"successful placement has no missing-input diagnostic");
		return 13;
	}

	private static void expectFailure(String expectedCode, Runnable action, String label) {
		try {
			action.run();
			throw new AssertionError(label + ": expected AgentDomainException " + expectedCode);
		} catch (AgentDomainException exception) {
			assertEquals(expectedCode, exception.code(), label + " reason code");
		}
	}

	private static void assertEquals(Object expected, Object actual, String label) {
		if (!Objects.equals(expected, actual)) {
			throw new AssertionError(label + ": expected <" + expected + "> but was <" + actual + ">");
		}
	}

	private static void assertTrue(boolean value, String label) {
		if (!value) throw new AssertionError(label);
	}

	private static void assertFalse(boolean value, String label) {
		if (value) throw new AssertionError(label);
	}
}
