package dev.agaminggod.arenaagents.server;

import dev.agaminggod.arenaagents.agent.AgentConstants;
import dev.agaminggod.arenaagents.agent.AgentDomainException;
import dev.agaminggod.arenaagents.agent.AgentGameMode;
import dev.agaminggod.arenaagents.agent.AgentLifecycleState;
import dev.agaminggod.arenaagents.agent.AgentRecord;
import dev.agaminggod.arenaagents.agent.AgentRegistry;
import dev.agaminggod.arenaagents.agent.goal.GoalEvidence;
import dev.agaminggod.arenaagents.agent.goal.GoalPredicate;
import dev.agaminggod.arenaagents.agent.goal.GoalSpec;
import dev.agaminggod.arenaagents.agent.goal.GoalStatus;
import dev.agaminggod.arenaagents.server.goal.GoalInventoryCapacity;
import dev.agaminggod.arenaagents.server.goal.GoalVerificationRuntime;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.component.DataComponentMap;
import net.minecraft.core.component.DataComponents;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;

public final class QueuedGoalRevalidationVerification {
	private QueuedGoalRevalidationVerification() {
	}

	public static int verify() {
		net.minecraft.SharedConstants.tryDetectVersion();
		net.minecraft.server.Bootstrap.bootStrap();
		bindItemStackSize(Items.APPLE, 64);
		bindItemStackSize(Items.DIRT, 64);
		bindItemStackSize(Items.STICK, 64);
		bindItemStackSize(Items.DIAMOND_PICKAXE, 1);
		try {
			int assertions = verifyRestartDropsInvalidHeadsBeforeValidPromotion();
			assertions += verifyAllInvalidQueueSettlesTerminal();
			assertions += verifyUnchangedQueuePromotesNormally();
			assertions += verifySpatialActivationValidation();
			return assertions;
		} finally {
			bindItemStackSize(Items.APPLE, 64);
			bindItemStackSize(Items.DIRT, 64);
		}
	}

	private static int verifyRestartDropsInvalidHeadsBeforeValidPromotion() {
		long now = 90_000L;
		AgentRegistry registry = new AgentRegistry(16, 8, () -> { }, ignored -> { });
		AgentRecord idle = registry.create(
				"codex", "gpt-5.6-sol", "high", "priority", Optional.empty(), AgentGameMode.SURVIVAL, now);
		GoalSpec current = GoalSpec.create("Finish current work", new GoalPredicate.OperatorConfirmed(), 900L);
		registry.start(idle.agentId(), current, now + 1L);

		Set<String> liveItems = new LinkedHashSet<>(List.of(
				"minecraft:apple", "minecraft:dirt", "minecraft:stick", "minecraft:diamond_pickaxe"));
		Set<String> liveEntities = new LinkedHashSet<>(List.of("minecraft:zombie"));
		Set<String> liveAdvancements = new LinkedHashSet<>(List.of("minecraft:story/root"));
		List<GoalSpec> queued = List.of(
				GoalSpec.create("Nested item", new GoalPredicate.AllOf(List.of(
						new GoalPredicate.SurviveDuration(1L),
						new GoalPredicate.AnyOf(List.of(
								new GoalPredicate.InventoryContains("minecraft:stick", 1))))), 901L),
				GoalSpec.create("Nested entity", new GoalPredicate.AnyOf(List.of(
						new GoalPredicate.AllOf(List.of(
								new GoalPredicate.EntityKilledByAgent("minecraft:zombie", true))))), 902L),
				GoalSpec.create("Nested advancement", new GoalPredicate.AllOf(List.of(
						new GoalPredicate.AnyOf(List.of(
								new GoalPredicate.AdvancementGranted("minecraft:story/root"))))), 903L),
				GoalSpec.create("Changed stack capacity", new GoalPredicate.AllOf(List.of(
						new GoalPredicate.InventoryContains("minecraft:apple", 38),
						new GoalPredicate.InventoryContains("minecraft:dirt", 64))), 904L),
				GoalSpec.create("Valid queued work",
						new GoalPredicate.InventoryContains("minecraft:diamond_pickaxe", 1), 905L)
		);
		for (GoalSpec spec : queued) {
			validate(spec, liveItems, liveEntities, liveAdvancements);
			registry.queue(idle.agentId(), spec, now + 2L);
		}
		registry.satisfyGoal(
				idle.agentId(), 1L, new GoalEvidence(906L, "operator_confirmed", List.of()), now + 3L);

		AgentRegistry restored = AgentRegistry.restore(
				registry.snapshot(), () -> { }, ignored -> { }, now + 4L);
		liveItems.remove("minecraft:stick");
		liveEntities.remove("minecraft:zombie");
		liveAdvancements.remove("minecraft:story/root");
		bindItemStackSize(Items.APPLE, 1);
		GoalVerificationRuntime runtime = new GoalVerificationRuntime(
				restored, ignored -> Optional.empty(), () -> 907L, () -> now + 5L,
				spec -> validate(spec, liveItems, liveEntities, liveAdvancements));

		assertEquals(5, runtime.tick().size(),
				"one tick reports four rejected heads and one valid promotion");
		AgentRecord promoted = restored.require(idle.agentId());
		assertEquals("Valid queued work", promoted.currentGoal().orElseThrow().prompt(),
				"restart revalidation skips every invalid queued head before promotion");
		assertEquals(AgentLifecycleState.STARTING, promoted.state(),
				"the first still-valid queued goal starts without stalling");
		assertEquals(0, promoted.queuedGoals().size(), "invalid heads and the promoted valid goal leave the queue");
		assertTrue(!promoted.lastError().isBlank(), "skipped queued work leaves a user-visible reason");
		assertTrue(promoted.lastError().length() <= AgentConstants.MAX_ERROR_LENGTH,
				"the queued rejection reason remains bounded");
		assertTrue(promoted.lastError().contains("UNKNOWN_GOAL_IDENTIFIER"),
				"identifier rejection remains visible after the valid promotion");
		assertTrue(promoted.lastError().contains("INVALID_GOAL_PREDICATE"),
				"changed aggregate capacity remains visible after the valid promotion");
		assertEquals(3L, promoted.goalRevision(),
				"discarding queued heads does not manufacture current-goal revisions");
		return 9;
	}

	private static int verifyAllInvalidQueueSettlesTerminal() {
		long now = 91_000L;
		AgentRegistry registry = new AgentRegistry(16, 8, () -> { }, ignored -> { });
		AgentRecord idle = registry.create(
				"codex", "gpt-5.6-sol", "high", "priority", Optional.empty(), AgentGameMode.SURVIVAL, now);
		GoalSpec current = GoalSpec.create("Completed work", new GoalPredicate.OperatorConfirmed(), 910L);
		GoalSpec stale = GoalSpec.create(
				"Removed item", new GoalPredicate.InventoryContains("minecraft:dirt", 1), 911L);
		registry.start(idle.agentId(), current, now + 1L);
		registry.queue(idle.agentId(), stale, now + 2L);
		registry.satisfyGoal(
				idle.agentId(), 1L, new GoalEvidence(912L, "operator_confirmed", List.of()), now + 3L);
		Set<String> noItems = Set.of();
		GoalVerificationRuntime runtime = new GoalVerificationRuntime(
				registry, ignored -> Optional.empty(), () -> 913L, () -> now + 4L,
				spec -> validate(spec, noItems, Set.of(), Set.of()));

		assertEquals(1, runtime.tick().size(), "an all-invalid queue emits one deterministic rejection");
		AgentRecord settled = registry.require(idle.agentId());
		assertEquals(AgentLifecycleState.COMPLETED, settled.state(),
				"an exhausted invalid queue stays terminal instead of reviving work");
		assertEquals(GoalStatus.SATISFIED, settled.currentGoal().orElseThrow().status(),
				"the accepted terminal goal remains satisfied");
		assertEquals("Completed work", settled.currentGoal().orElseThrow().prompt(),
				"queue rejection cannot replace terminal history");
		assertEquals(0, settled.queuedGoals().size(), "the invalid terminal queue head is removed");
		assertEquals(0, runtime.tick().size(), "an exhausted invalid queue does not retry or stall each tick");
		return 6;
	}

	private static int verifyUnchangedQueuePromotesNormally() {
		long now = 92_000L;
		AgentRegistry registry = new AgentRegistry(16, 8, () -> { }, ignored -> { });
		AgentRecord idle = registry.create(
				"codex", "gpt-5.6-sol", "high", "priority", Optional.empty(), AgentGameMode.SURVIVAL, now);
		GoalSpec current = GoalSpec.create("Completed work", new GoalPredicate.OperatorConfirmed(), 920L);
		GoalSpec valid = GoalSpec.create(
				"Still valid", new GoalPredicate.InventoryContains("minecraft:diamond_pickaxe", 1), 921L);
		registry.start(idle.agentId(), current, now + 1L);
		registry.queue(idle.agentId(), valid, now + 2L);
		registry.satisfyGoal(
				idle.agentId(), 1L, new GoalEvidence(922L, "operator_confirmed", List.of()), now + 3L);
		GoalVerificationRuntime runtime = new GoalVerificationRuntime(
				registry, ignored -> Optional.empty(), () -> 923L, () -> now + 4L,
				spec -> validate(
						spec, Set.of("minecraft:diamond_pickaxe"), Set.of(), Set.of()));

		assertEquals(1, runtime.tick().size(), "an unchanged valid queue promotes in one transition");
		AgentRecord promoted = registry.require(idle.agentId());
		assertEquals("Still valid", promoted.currentGoal().orElseThrow().prompt(),
				"valid queued work remains untouched");
		assertEquals(AgentLifecycleState.STARTING, promoted.state(), "valid queued work starts normally");
		assertEquals("", promoted.lastError(), "valid promotion does not invent a rejection notice");
		return 4;
	}

	private static int verifySpatialActivationValidation() {
		expectCode("UNKNOWN_GOAL_IDENTIFIER", () -> CodexAgentManager.validateGoalDraftPredicate(
				new GoalPredicate.BlockMatches(
						"minecraft:overworld", 0, 64, 0, "missing:block", Map.of()),
				RegistryAccess.EMPTY,
				ignored -> true
		), "queued activation rejects a block removed after confirmation");
		return 1;
	}

	private static void validate(
			GoalSpec spec,
			Set<String> liveItems,
			Set<String> liveEntities,
			Set<String> liveAdvancements
	) {
		CodexAgentManager.validateLiveGoalIdentifiers(
				spec.completion(), liveItems::contains, liveEntities::contains, liveAdvancements::contains);
		GoalInventoryCapacity.validateTranslated(spec.completion(), RegistryAccess.EMPTY);
	}

	private static void bindItemStackSize(Item item, int maxStackSize) {
		item.builtInRegistryHolder().bindComponents(
				DataComponentMap.builder().set(DataComponents.MAX_STACK_SIZE, maxStackSize).build());
	}

	private static void assertEquals(Object expected, Object actual, String label) {
		if (!java.util.Objects.equals(expected, actual)) {
			throw new AssertionError(label + ": expected=" + expected + ", actual=" + actual);
		}
		System.out.println("PASS: " + label);
	}

	private static void assertTrue(boolean condition, String label) {
		if (!condition) throw new AssertionError(label);
		System.out.println("PASS: " + label);
	}

	private static void expectCode(String code, Runnable action, String label) {
		try {
			action.run();
			throw new AssertionError(label + ": expected " + code);
		} catch (AgentDomainException exception) {
			assertEquals(code, exception.code(), label);
		}
	}
}
