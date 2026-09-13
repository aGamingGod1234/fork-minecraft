package dev.agaminggod.arenaagents.server;

import dev.agaminggod.arenaagents.agent.AgentDomainException;
import dev.agaminggod.arenaagents.agent.goal.GoalPredicate;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.component.DataComponentMap;
import net.minecraft.core.component.DataComponents;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;

public final class GoalDraftAdvancementRevalidationVerification {
	private GoalDraftAdvancementRevalidationVerification() {
	}

	public static int verify() {
		net.minecraft.SharedConstants.tryDetectVersion();
		net.minecraft.server.Bootstrap.bootStrap();
		bindItemStackSize(Items.APPLE, 64);
		bindItemStackSize(Items.DIRT, 64);
		int assertions = 0;
		assertions += verifyRecursiveLiveIdentifiers();
		assertions += verifyLiveAlternativeItemIdentifiers();
		assertions += verifyRestartCapacityRevalidation();
		return assertions;
	}

	private static int verifyRecursiveLiveIdentifiers() {
		Set<String> liveItems = new LinkedHashSet<>(List.of("example:tool"));
		Set<String> liveEntities = new LinkedHashSet<>(List.of("example:boss"));
		Set<String> liveAdvancements = new LinkedHashSet<>(List.of(
				"minecraft:story/root",
				"example:quest/finish"
		));
		GoalPredicate nested = new GoalPredicate.AllOf(List.of(
				new GoalPredicate.InventoryContains("example:tool", 1),
				new GoalPredicate.AdvancementGranted("minecraft:story/root"),
				new GoalPredicate.AnyOf(List.of(
						new GoalPredicate.EntityKilledByAgent("example:boss", true),
						new GoalPredicate.AdvancementGranted("example:quest/finish")
				))
		));

		CodexAgentManager.validateLiveGoalIdentifiers(
				nested, liveItems::contains, liveEntities::contains, liveAdvancements::contains);
		pass("valid nested draft identifiers survive confirmation revalidation");

		liveItems.clear();
		expectCode(
				"UNKNOWN_GOAL_IDENTIFIER",
				() -> CodexAgentManager.validateLiveGoalIdentifiers(
						nested, liveItems::contains, liveEntities::contains, liveAdvancements::contains),
				"restart removal rejects a stale item nested under all-of"
		);
		liveItems.add("example:tool");

		liveEntities.clear();
		expectCode(
				"UNKNOWN_GOAL_IDENTIFIER",
				() -> CodexAgentManager.validateLiveGoalIdentifiers(
						nested, liveItems::contains, liveEntities::contains, liveAdvancements::contains),
				"restart removal rejects a stale entity nested under any-of"
		);
		liveEntities.add("example:boss");

		liveAdvancements.remove("example:quest/finish");
		expectCode(
				"UNKNOWN_GOAL_IDENTIFIER",
				() -> CodexAgentManager.validateLiveGoalIdentifiers(
						nested, liveItems::contains, liveEntities::contains, liveAdvancements::contains),
				"reload removal rejects a stale advancement nested under any-of"
		);

		liveAdvancements.clear();
		expectCode(
				"UNKNOWN_GOAL_IDENTIFIER",
				() -> CodexAgentManager.validateLiveGoalIdentifiers(
						nested, liveItems::contains, liveEntities::contains, liveAdvancements::contains),
				"restart removal rejects every stale advancement leaf before activation"
		);

		GoalPredicate nonAdvancement = new GoalPredicate.AllOf(List.of(
				new GoalPredicate.InventoryContains("minecraft:iron_pickaxe", 1),
				new GoalPredicate.SurviveDuration(20)
		));
		CodexAgentManager.validateLiveGoalIdentifiers(
				nonAdvancement, ignored -> true, ignored -> false, ignored -> false);
		pass("non-advancement predicates remain valid when no advancements are registered");
		return 6;
	}

	private static int verifyLiveAlternativeItemIdentifiers() {
		Set<String> liveItems = new LinkedHashSet<>(List.of("example:oak_log", "example:birch_log"));
		Set<String> checkedItems = new LinkedHashSet<>();
		GoalPredicate alternatives = new GoalPredicate.InventoryContainsAny(List.copyOf(liveItems), 12);
		CodexAgentManager.validateLiveGoalIdentifiers(alternatives, itemId -> {
			checkedItems.add(itemId);
			return liveItems.contains(itemId);
		}, ignored -> false, ignored -> false);
		pass("a mixed-item inventory goal accepts live alternative items");
		if (!checkedItems.equals(liveItems)) throw new AssertionError("every alternative item must be revalidated");
		pass("a mixed-item inventory goal checks every listed item against the live registry");

		liveItems.remove("example:birch_log");
		expectCode("UNKNOWN_GOAL_IDENTIFIER", () -> CodexAgentManager.validateLiveGoalIdentifiers(
				new GoalPredicate.AnyOf(List.of(new GoalPredicate.SurviveDuration(20), alternatives)),
				liveItems::contains, ignored -> false, ignored -> false),
				"a removed alternative item is rejected even when another branch or item remains valid");
		return 3;
	}

	private static int verifyRestartCapacityRevalidation() {
		GoalPredicate predicate = new GoalPredicate.AllOf(List.of(
				new GoalPredicate.InventoryContains("minecraft:apple", 38),
				new GoalPredicate.InventoryContains("minecraft:dirt", 64)
		));
		CodexAgentManager.validateGoalDraftPredicate(predicate, RegistryAccess.EMPTY, ignored -> true);
		pass("staged compound fits the original shared inventory capacity");

		bindItemStackSize(Items.APPLE, 1);
		try {
			expectCode(
					"INVALID_GOAL_PREDICATE",
					() -> CodexAgentManager.validateGoalDraftPredicate(
							predicate, RegistryAccess.EMPTY, ignored -> true),
					"confirmation rejects a compound after a restart lowers its live stack limit"
			);
		} finally {
			bindItemStackSize(Items.APPLE, 64);
		}
		return 2;
	}

	private static void bindItemStackSize(Item item, int maxStackSize) {
		item.builtInRegistryHolder().bindComponents(
				DataComponentMap.builder().set(DataComponents.MAX_STACK_SIZE, maxStackSize).build()
		);
	}

	private static void expectCode(String code, Runnable operation, String label) {
		try {
			operation.run();
			throw new AssertionError(label + ": expected " + code);
		} catch (AgentDomainException exception) {
			if (!code.equals(exception.code())) {
				throw new AssertionError(label + ": expected=" + code + ", actual=" + exception.code());
			}
			pass(label);
		}
	}

	private static void pass(String label) {
		System.out.println("PASS: " + label);
	}
}
