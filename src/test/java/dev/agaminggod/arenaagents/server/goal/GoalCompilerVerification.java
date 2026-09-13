package dev.agaminggod.arenaagents.server.goal;

import com.google.gson.JsonParser;
import dev.agaminggod.arenaagents.agent.AgentId;
import dev.agaminggod.arenaagents.agent.AgentLifecycleReducer;
import dev.agaminggod.arenaagents.agent.AgentProfile;
import dev.agaminggod.arenaagents.agent.AgentRecord;
import dev.agaminggod.arenaagents.agent.goal.GoalEvidence;
import dev.agaminggod.arenaagents.agent.goal.GoalPredicate;
import dev.agaminggod.arenaagents.agent.goal.GoalSpec;
import java.util.LinkedHashMap;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.component.DataComponentMap;
import net.minecraft.core.component.DataComponents;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;

public final class GoalCompilerVerification {
	private GoalCompilerVerification() {
	}

	public static int verify() {
		net.minecraft.SharedConstants.tryDetectVersion();
		net.minecraft.server.Bootstrap.bootStrap();
		bindItemStackSize(Items.APPLE, 64);
		bindItemStackSize(Items.COBBLESTONE, 64);
		bindItemStackSize(Items.DIRT, 64);
		bindItemStackSize(Items.DIAMOND_PICKAXE, 1);
		bindItemStackSize(Items.DIAMOND_SWORD, 1);
		bindItemStackSize(Items.IRON_AXE, 1);
		bindItemStackSize(Items.IRON_PICKAXE, 1);
		int assertions = 0;
		assertions += verifyExactItemAndAmbiguity();
		assertions += verifyInventoryCapacity();
		assertions += verifyExactPositionEntityAndAdvancement();
		assertions += verifyManagerSubmissionFlow();
		assertions += verifyCompoundItemsAndKills();
		assertions += verifyKillCountTranslationBounds();
		assertions += verifyTranslatedKillConstraints();
		assertions += verifyTranslatedItemConstraints();
		assertions += verifyRegistryCategoryTranslation();
		assertions += verifyInventoryGroupConstraints();
		assertions += verifyExplicitAlternativeCandidates();
		assertions += verifyDraftRoundTrip();
		assertions += verifyWorldValidation();
		assertions += verifyWireBoundaryInvariants();
		assertions += verifyDraftRevisionBinding();
		assertions += verifyDraftAuthorizationAndChoices();
		assertions += verifySpecAwareLifecycleStart();
		assertions += verifyRequesterPrincipal();
		return assertions;
	}

	private static int verifyRequesterPrincipal() {
		UUID playerId = UUID.randomUUID();
		assertEquals(playerId, PendingGoalDraft.requesterId(Optional.of(playerId)),
				"in-game semantic goals retain the requesting player principal");
		assertEquals(PendingGoalDraft.SYSTEM_REQUESTER_ID, PendingGoalDraft.requesterId(Optional.empty()),
				"authorized console semantic goals use the explicit system principal");
		return 2;
	}

	private static int verifyExactItemAndAmbiguity() {
		GoalCompiler compiler = new GoalCompiler();
		GoalCompilation exact = compiler.compile("Hey, get an iron pickaxe", RegistryAccess.EMPTY, 1_200L);
		assertEquals(GoalCompilation.Kind.ACCEPTED, exact.kind(), "exact item request is accepted");
		assertEquals(
				new GoalPredicate.InventoryContains("minecraft:iron_pickaxe", 1),
				exact.acceptedSpec().orElseThrow().completion(),
				"exact item request freezes the iron pickaxe predicate"
		);
		assertEquals(
				GoalCompilation.Kind.NEEDS_TRANSLATION,
				compiler.compile("Get a good pickaxe", RegistryAccess.EMPTY, 1_200L).kind(),
				"subjective item request requires translation"
		);
		assertEquals(
				List.of(
						"minecraft:copper_pickaxe", "minecraft:diamond_pickaxe", "minecraft:golden_pickaxe", "minecraft:iron_pickaxe",
						"minecraft:netherite_pickaxe", "minecraft:stone_pickaxe", "minecraft:wooden_pickaxe"
				),
				compiler.candidateIdsFor("Get a good pickaxe", RegistryAccess.EMPTY),
				"subjective item request keeps only bounded registered pickaxe candidates"
		);
		assertEquals(false, GoalCompiler.looksLikeGoalRequest("Hi, can you hear me?"), "casual speech remains conversation");
		assertEquals(true, GoalCompiler.looksLikeGoalRequest("Can you get an iron pickaxe?"), "actionable speech is a goal request");
		assertEquals(false, GoalCompiler.looksLikeGoalRequest("come here"), "come here is live steering, not a new goal");
		assertEquals(false, GoalCompiler.looksLikeGoalRequest("Follow me"), "follow me is live steering, not a new goal");
		assertEquals(true, GoalCompiler.isLiveSteeringRequest("Can you come here?"), "polite come-here stays steering");
		assertEquals(false, GoalCompiler.consumePlayerSpeechAsGoal(true, "come here", false), "busy agents still hear come here");
		assertEquals(false, GoalCompiler.consumePlayerSpeechAsGoal(true, "get an iron pickaxe", false), "busy agents keep working unless replace/queue was chosen");
		assertEquals(true, GoalCompiler.consumePlayerSpeechAsGoal(false, "get an iron pickaxe", false), "idle agents still compile exact goal speech");
		assertEquals(
				List.of("minecraft:iron_axe", "minecraft:iron_hoe", "minecraft:iron_pickaxe", "minecraft:iron_shovel", "minecraft:iron_sword"),
				compiler.candidateIdsFor("Get iron tools", RegistryAccess.EMPTY),
				"tool-set clarification keeps the registered material tool family"
		);
		GoalCompilation craft = compiler.compile("Craft an iron pickaxe", RegistryAccess.EMPTY, 1_200L);
		assertEquals(GoalCompilation.Kind.NEEDS_TRANSLATION, craft.kind(),
				"craft wording cannot be reduced to already-held inventory");
		assertEquals(Optional.empty(), craft.acceptedSpec(),
				"craft wording has no false possession completion predicate");
		GoalCompilation make = compiler.compile("Make an iron pickaxe", RegistryAccess.EMPTY, 1_200L);
		assertEquals(GoalCompilation.Kind.NEEDS_TRANSLATION, make.kind(),
				"make wording cannot be reduced to already-held inventory");
		return 17;
	}

	private static int verifyInventoryCapacity() {
		GoalCompiler compiler = new GoalCompiler();
		GoalCompilation fullCobblestoneInventory = compiler.compile(
				"Get 2368 cobblestone", RegistryAccess.EMPTY, 1_200L
		);
		assertEquals(GoalCompilation.Kind.ACCEPTED, fullCobblestoneInventory.kind(),
				"general inventory and offhand stacks are representable");
		assertEquals(
				new GoalPredicate.InventoryContains("minecraft:cobblestone", 2_368),
				fullCobblestoneInventory.acceptedSpec().orElseThrow().completion(),
				"the stackable-item capacity boundary preserves the exact requested count"
		);
		assertEquals(
				GoalCompilation.Kind.REJECTED,
				compiler.compile("Get 2369 cobblestone", RegistryAccess.EMPTY, 1_200L).kind(),
				"one item above the stackable-item capacity is rejected"
		);
		assertEquals(
				GoalCompilation.Kind.REJECTED,
				compiler.compile("Get 1000000 cobblestone", RegistryAccess.EMPTY, 1_200L).kind(),
				"an unrepresentable million-item inventory goal is rejected"
		);
		assertEquals(
				GoalCompilation.Kind.ACCEPTED,
				compiler.compile("Get 37 iron pickaxes", RegistryAccess.EMPTY, 1_200L).kind(),
				"unstackable tools can fill general inventory and offhand slots"
		);
		assertEquals(
				GoalCompilation.Kind.REJECTED,
				compiler.compile("Get 38 iron pickaxes", RegistryAccess.EMPTY, 1_200L).kind(),
				"unstackable items use their resolved one-item stack limit"
		);
		assertEquals(
				GoalCompilation.Kind.REJECTED,
				compiler.compile("Get 2369 cobblestone and kill a zombie", RegistryAccess.EMPTY, 1_200L).kind(),
				"compound inventory predicates enforce the same carrying capacity"
		);
		assertEquals(
				GoalCompilation.Kind.REJECTED,
				compiler.compile("Get 2304 cobblestone and 2304 dirt", RegistryAccess.EMPTY, 1_200L).kind(),
				"distinct stackable items share the same inventory and offhand slots"
		);
		assertEquals(
				GoalCompilation.Kind.ACCEPTED,
				compiler.compile("Get 2304 cobblestone and 64 dirt", RegistryAccess.EMPTY, 1_200L).kind(),
				"mixed stackable items may exactly fill the shared thirty-seven-slot budget"
		);
		assertEquals(
				GoalCompilation.Kind.REJECTED,
				compiler.compile("Get 2304 cobblestone and 65 dirt", RegistryAccess.EMPTY, 1_200L).kind(),
				"a partial extra stack cannot exceed the shared slot budget"
		);
		assertEquals(
				GoalCompilation.Kind.ACCEPTED,
				compiler.compile("Get 36 iron pickaxes and 64 cobblestone", RegistryAccess.EMPTY, 1_200L).kind(),
				"unstackable and stackable requirements share inventory and offhand capacity"
		);
		assertEquals(
				GoalCompilation.Kind.REJECTED,
				compiler.compile("Get 36 iron pickaxes and 65 cobblestone", RegistryAccess.EMPTY, 1_200L).kind(),
				"mixed stack limits count the partially occupied stack as another slot"
		);
		assertEquals(
				GoalCompilation.Kind.ACCEPTED,
				compiler.compile("Get 1152 cobblestone and 1152 cobblestone and 64 dirt", RegistryAccess.EMPTY, 1_200L).kind(),
				"summed duplicate requirements participate in the shared slot calculation"
		);

		GoalPredicate translatedFeasible = new GoalPredicate.AllOf(List.of(
				new GoalPredicate.InventoryContains("minecraft:cobblestone", 2_304),
				new GoalPredicate.InventoryContains("minecraft:dirt", 64)
		));
		assertSucceeds(
				() -> GoalInventoryCapacity.validateTranslated(translatedFeasible, RegistryAccess.EMPTY),
				"translated compounds use the same exact shared-slot boundary"
		);
		expectCode("INVALID_GOAL_PREDICATE", () -> GoalInventoryCapacity.validateTranslated(
				new GoalPredicate.AllOf(List.of(
						new GoalPredicate.InventoryContains("minecraft:cobblestone", 2_304),
						new GoalPredicate.InventoryContains("minecraft:dirt", 65)
				)),
				RegistryAccess.EMPTY
		), "translated compounds reject the same impossible shared-slot demand");
		assertSucceeds(() -> GoalInventoryCapacity.validateTranslated(new GoalPredicate.AllOf(List.of(
					new GoalPredicate.AnyOf(List.of(
							new GoalPredicate.InventoryContains("minecraft:cobblestone", 2_304),
							new GoalPredicate.InventoryContains("minecraft:dirt", 1)
					)),
					new GoalPredicate.InventoryContains("minecraft:apple", 128)
			)), RegistryAccess.EMPTY), "a feasible any-of branch keeps a translated compound satisfiable");
		return 16;
	}

	private static int verifyExactPositionEntityAndAdvancement() {
		GoalCompiler compiler = new GoalCompiler();
		GoalPredicate.PositionWithin position = (GoalPredicate.PositionWithin) compiler.compile(
				"Go to 12 64 -8", RegistryAccess.EMPTY, 1_200L
		).acceptedSpec().orElseThrow().completion();
		assertEquals(new GoalPredicate.PositionWithin(12.0D, 64.0D, -8.0D, 1.0D, 20), position, "exact coordinates");
		GoalPredicate.PositionWithin labelledPosition = (GoalPredicate.PositionWithin) compiler.compile(
				"Move to coordinates x=-22, y=99, z=12 and stop there", RegistryAccess.EMPTY, 1_200L
		).acceptedSpec().orElseThrow().completion();
		assertEquals(new GoalPredicate.PositionWithin(-22.0D, 99.0D, 12.0D, 1.0D, 20), labelledPosition,
				"labelled coordinates from the task UI compile to a positional verifier");
		assertEquals(
				new GoalPredicate.EntityKilledByAgent("minecraft:ender_dragon", true),
				compiler.compile("Kill the ender dragon", RegistryAccess.EMPTY, 1_200L).acceptedSpec().orElseThrow().completion(),
				"exact entity kill"
		);
		assertEquals(
				new GoalPredicate.EntityKilledByAgent("minecraft:ender_dragon", true),
				compiler.compile("Beat the game", RegistryAccess.EMPTY, 1_200L).acceptedSpec().orElseThrow().completion(),
				"beating the game freezes an agent-attributed dragon kill"
		);
		assertEquals(
				GoalCompilation.Kind.NEEDS_TRANSLATION,
				compiler.compile("Go beat the game and kill the Ender Dragon", RegistryAccess.EMPTY, 1_200L).kind(),
				"a natural game-completion request enters semantic goal translation"
		);
		assertEquals(true,
				GoalCompiler.isDeterministicTranslation("Go and beat the game and kill the Ender Dragon"),
				"the unambiguous dragon completion phrase can start without asking the player to restate it");
		assertEquals(true,
				GoalCompiler.isDeterministicTranslation("Kill the Ender Dragon and beat the game"),
				"the same unambiguous dragon completion remains deterministic when spoken in the other order");
		assertEquals(false,
				GoalCompiler.isDeterministicTranslation("Beat the game"),
				"the direct beat-the-game command remains an already compiled goal");
		assertEquals(false,
				GoalCompiler.isDeterministicTranslation("Go beat the game and kill a zombie"),
				"an unrelated compound phrase still requires semantic translation and validation");
		assertEquals(
				List.of("minecraft:ender_dragon"),
				compiler.candidateIdsFor("Go beat the game and kill the Ender Dragon", RegistryAccess.EMPTY),
				"the coordinator receives the bounded terminal-result candidate"
		);
		assertEquals(
				List.of("minecraft:ender_dragon"),
				compiler.candidateIdsFor("Go and beat the game and kill the enemy dragon", RegistryAccess.EMPTY),
				"a common spoken enemy-dragon transcription keeps the same candidate"
		);
		GoalTranslationConstraint dragonConstraint = compiler.translationConstraintFor(
				"Go beat the game and kill the Ender Dragon", RegistryAccess.EMPTY);
		expectCode("GOAL_TRANSLATION_CONSTRAINT_MISMATCH",
				() -> dragonConstraint.validate(new GoalPredicate.OperatorConfirmed()),
				"operator confirmation cannot replace the requested terminal result");
		expectCode("GOAL_TRANSLATION_CONSTRAINT_MISMATCH",
				() -> dragonConstraint.validate(new GoalPredicate.EntityKilledByAgent("minecraft:zombie", true)),
				"a translated goal cannot substitute a different enemy");
		assertSucceeds(
				() -> dragonConstraint.validate(new GoalPredicate.EntityKilledByAgent("minecraft:ender_dragon", true)),
				"the server constraint accepts the inferred Ender Dragon terminal result");
		assertEquals(
				new GoalPredicate.EntityKilledByAgent("minecraft:ender_dragon", true),
				compiler.normalizeTranslatedPredicate(
						"Go beat the game and kill the Ender Dragon",
						new GoalPredicate.AllOf(List.of(
								new GoalPredicate.OperatorConfirmed(),
								new GoalPredicate.EntityKilledByAgent("minecraft:ender_dragon", true)
						))
				),
				"beat-the-game translation removes redundant manual confirmation from the objective dragon result"
		);
		assertEquals(
				GoalCompilation.Kind.NEEDS_TRANSLATION,
				compiler.compile("Go beat the game and kill a zombie", RegistryAccess.EMPTY, 1_200L).kind(),
				"a separate trailing result is not discarded as redundant dragon wording"
		);
		assertEquals(
				new GoalPredicate.AdvancementGranted("minecraft:story/mine_stone"),
				compiler.compile("Complete advancement minecraft:story/mine_stone", RegistryAccess.EMPTY, 1_200L,
						id -> id.equals("minecraft:story/mine_stone"))
						.acceptedSpec().orElseThrow().completion(),
				"exact advancement ID"
		);
		assertEquals(
				GoalCompilation.Kind.REJECTED,
				compiler.compile("Complete advancement minecraft:story/not_real", RegistryAccess.EMPTY, 1_200L, id -> false).kind(),
				"an exact nonexistent advancement ID is rejected instead of translated"
		);
		assertEquals(
				GoalCompilation.Kind.NEEDS_TRANSLATION,
				compiler.compile("Earn the Stone Age advancement", RegistryAccess.EMPTY, 1_200L, id -> false).kind(),
				"a natural advancement title may still be resolved through translation"
		);
		GoalPredicate.PositionWithin netherPosition = (GoalPredicate.PositionWithin) compiler.compile(
				"Go to 12 64 -8", RegistryAccess.EMPTY, 1_200L, "minecraft:the_nether"
		).acceptedSpec().orElseThrow().completion();
		assertEquals("minecraft:the_nether", netherPosition.dimensionId(), "coordinate goal binds its source dimension");
		assertEquals(GoalCompilation.Kind.REJECTED,
				compiler.compile("Go to 30000000 64 0", RegistryAccess.EMPTY, 1_200L).kind(),
				"out-of-world horizontal coordinates are rejected before a goal is accepted");
		assertEquals(
				List.of("minecraft:oak_planks"),
				compiler.candidateIdsFor("Place oak planks", RegistryAccess.EMPTY),
				"plural multiword block names resolve to the exact registered block ID"
		);
		assertEquals(
				List.of("minecraft:oak_planks"),
				compiler.candidateIdsFor("Place oak plank", RegistryAccess.EMPTY),
				"singular and plural block wording resolve to the same registered block ID"
		);
		assertEquals(
				List.of("minecraft:oak_planks"),
				compiler.candidateIdsFor("Build with oak_planks", RegistryAccess.EMPTY),
				"registry path spelling is normalized as a multiword block name"
		);
		assertEquals(
				List.of("minecraft:crafting_table"),
				compiler.candidateIdsFor("place a crafting table at 10 64 10", RegistryAccess.EMPTY),
				"exact coordinates are removed before matching a block translation candidate"
		);
		assertEquals(
				List.of("minecraft:crafting_table"),
				compiler.candidateIdsFor("Place a crafting table at coordinates x=-10, y=64, z=10", RegistryAccess.EMPTY),
				"labelled coordinate suffixes preserve the exact block candidate"
		);
		assertTrue(
				!compiler.candidateIdsFor("Place oak planks", RegistryAccess.EMPTY).contains("minecraft:oak_button"),
				"phrase matching does not expand a block request to unrelated same-material blocks"
		);
		GoalCompilation breakBlock = compiler.compile(
				"Break stone at 10 64 -10", RegistryAccess.EMPTY, 1_200L, "minecraft:the_nether");
		assertEquals(GoalCompilation.Kind.ACCEPTED, breakBlock.kind(),
				"an exact coordinate-bearing destructive request compiles without translation");
		assertEquals(
				new GoalPredicate.BlockMatches("minecraft:the_nether", 10, 64, -10, "minecraft:air", Map.of()),
				breakBlock.acceptedSpec().orElseThrow().completion(),
				"destructive block completion verifies the achievable post-break state"
		);
		assertEquals(
				List.of("minecraft:air"),
				compiler.candidateIdsFor("Destroy stone at coordinates x=10, y=64, z=-10", RegistryAccess.EMPTY),
				"destructive coordinate translation exposes only the post-break state"
		);
		GoalCompilation genericBreak = compiler.compile(
				"Break the block at 10 64 -10", RegistryAccess.EMPTY, 1_200L, "minecraft:the_nether");
		assertEquals(GoalCompilation.Kind.ACCEPTED, genericBreak.kind(),
				"a generic block target still compiles when destructive coordinates determine the postcondition");
		assertEquals(
				new GoalPredicate.BlockMatches("minecraft:the_nether", 10, 64, -10, "minecraft:air", Map.of()),
				genericBreak.acceptedSpec().orElseThrow().completion(),
				"a generic destructive request freezes air at the requested coordinates"
		);
		assertEquals(
				List.of("minecraft:air"),
				compiler.candidateIdsFor("Break the block at 10 64 -10", RegistryAccess.EMPTY),
				"generic destructive coordinate translation exposes the safe post-break candidate"
		);
		assertEquals(
				List.of(),
				compiler.candidateIdsFor("Break the block", RegistryAccess.EMPTY),
				"a generic destructive request without coordinates does not invent a completion ID"
		);
		assertEquals(
				List.of(),
				compiler.candidateIdsFor("Place the block at 10 64 -10", RegistryAccess.EMPTY),
				"generic placement coordinates do not reuse the destructive air candidate"
		);
		assertEquals(
				List.of("minecraft:stone"),
				compiler.candidateIdsFor("Place stone at 10 64 -10", RegistryAccess.EMPTY),
				"placement translation still exposes the requested placed block"
		);
		Map<String, String> liveAdvancements = Map.of(
				"minecraft:story/mine_stone", "Stone Age",
				"minecraft:story/upgrade_tools", "Getting an Upgrade"
		);
		assertEquals(
				List.of("minecraft:story/mine_stone"),
				compiler.candidateIdsFor("Earn the Stone Age advancement", RegistryAccess.EMPTY, liveAdvancements),
				"natural advancement titles resolve to matching live server IDs"
		);
		assertEquals(
				List.of("minecraft:story/upgrade_tools"),
				compiler.candidateIdsFor("Complete the advancement Getting an Upgrade", RegistryAccess.EMPTY, liveAdvancements),
				"prefix-style natural advancement requests resolve from the same live catalog"
		);
		assertEquals(
				List.of(),
				compiler.candidateIdsFor("Earn the Stone Age advancement", RegistryAccess.EMPTY, Map.of()),
				"advancement translation never invents IDs absent from the live server"
		);
		LinkedHashMap<String, String> manyLiveAdvancements = new LinkedHashMap<>();
		for (int index = 0; index < 80; index++) {
			manyLiveAdvancements.put("example:story/stone_age_" + index, "Stone Age challenge " + index);
		}
		assertEquals(
				64,
				compiler.candidateIdsFor("Earn the Stone Age advancement", RegistryAccess.EMPTY, manyLiveAdvancements).size(),
				"natural advancement candidates remain bounded"
		);
		return 35;
	}

	private static int verifyCompoundItemsAndKills() {
		GoalCompiler compiler = new GoalCompiler();
		GoalCompilation mixed = compiler.compile("Get 2 apples and kill a zombie", RegistryAccess.EMPTY, 1_200L);
		assertEquals(GoalCompilation.Kind.ACCEPTED, mixed.kind(), "mixed factual item and kill request is accepted");
		assertEquals(
				new GoalPredicate.AllOf(List.of(
						new GoalPredicate.InventoryContains("minecraft:apple", 2),
						new GoalPredicate.EntityKilledByAgent("minecraft:zombie", true)
				)),
				mixed.acceptedSpec().orElseThrow().completion(),
				"mixed request freezes one bounded predicate per factual result"
		);
		GoalCompilation twoKills = compiler.compile("Kill a zombie and a skeleton", RegistryAccess.EMPTY, 1_200L);
		assertEquals(
				new GoalPredicate.AllOf(List.of(
						new GoalPredicate.EntityKilledByAgent("minecraft:zombie", true),
						new GoalPredicate.EntityKilledByAgent("minecraft:skeleton", true)
				)),
				twoKills.acceptedSpec().orElseThrow().completion(),
				"a carried kill verb compiles each entity into its own verifier"
		);
		assertEquals(
				List.of(
						"minecraft:copper_pickaxe", "minecraft:diamond_pickaxe", "minecraft:golden_pickaxe", "minecraft:iron_pickaxe",
						"minecraft:netherite_pickaxe", "minecraft:stone_pickaxe", "minecraft:wooden_pickaxe", "minecraft:zombie"
				),
				compiler.candidateIdsFor("Get a good pickaxe and kill a zombie", RegistryAccess.EMPTY),
				"compound translation candidates include registered items and entities"
		);
		assertEquals(
				List.of("minecraft:zombie"),
				compiler.candidateIdsFor("Kill 3 zombies", RegistryAccess.EMPTY),
				"a kill count is removed before collecting translation candidates"
		);
		assertEquals(
				List.of("minecraft:apple", "minecraft:enchanted_golden_apple", "minecraft:golden_apple", "minecraft:zombie"),
				compiler.candidateIdsFor("Get an apple and kill 3 zombies", RegistryAccess.EMPTY),
				"compound translation strips kill counts without changing item candidates"
		);
		assertEquals(
				List.of("minecraft:air", "minecraft:apple", "minecraft:enchanted_golden_apple", "minecraft:golden_apple"),
				compiler.candidateIdsFor("Get an apple and break the block at 10 64 -10", RegistryAccess.EMPTY),
				"compound translation retains the safe post-break candidate for a generic block target"
		);
		String everyPredicateKind = "Place a crafting table at 10 64 10 and get an iron pickaxe"
				+ " and go to 12 64 12 and complete the Stone Age advancement"
				+ " and kill a zombie and survive for 20 ticks and explore somewhere nice";
		assertEquals(
				List.of(
						"minecraft:crafting_table", "minecraft:iron_pickaxe",
						"minecraft:story/mine_stone", "minecraft:zombie"
				),
				compiler.candidateIdsFor(
						everyPredicateKind,
						RegistryAccess.EMPTY,
						Map.of("minecraft:story/mine_stone", "Stone Age")
				),
				"compound translation retains identifiers while non-identifier predicate kinds add no candidates"
		);
		assertEquals(
				GoalCompilation.Kind.NEEDS_TRANSLATION,
				compiler.compile(everyPredicateKind, RegistryAccess.EMPTY, 1_200L).kind(),
				"mixed-schema compound requests reach translation instead of being misparsed as one block"
		);
		GoalCompilation overBound = compiler.compile("Get apple" + " and apple".repeat(16), RegistryAccess.EMPTY, 1_200L);
		assertEquals(GoalCompilation.Kind.REJECTED, overBound.kind(), "compound predicates reject more than sixteen factual leaves");
		GoalCompilation carriedCraft = compiler.compile("Craft an iron pickaxe and a shield", RegistryAccess.EMPTY, 1_200L);
		assertEquals(GoalCompilation.Kind.NEEDS_TRANSLATION, carriedCraft.kind(),
				"compound craft wording cannot be reduced to possession predicates");
		assertEquals(Optional.empty(), carriedCraft.acceptedSpec(),
				"compound craft wording has no false possession completion predicate");
		assertEquals(
				GoalCompilation.Kind.NEEDS_TRANSLATION,
				compiler.compile("Get an iron pickaxe and make a shield", RegistryAccess.EMPTY, 1_200L).kind(),
				"an explicit make clause keeps the entire compound goal verifiable"
		);
		GoalCompilation repeatedItem = compiler.compile(
				"Get 2 diamond swords and 3 diamond swords", RegistryAccess.EMPTY, 1_200L);
		assertEquals(GoalCompilation.Kind.ACCEPTED, repeatedItem.kind(),
				"repeated inventory requirements remain directly compilable");
		assertEquals(
				new GoalPredicate.AllOf(List.of(
						new GoalPredicate.InventoryContains("minecraft:diamond_sword", 5)
				)),
				repeatedItem.acceptedSpec().orElseThrow().completion(),
				"repeated inventory requirements sum into one exact minimum count"
		);
		assertEquals(
				GoalCompilation.Kind.REJECTED,
				compiler.compile("Get 20 diamond swords and 18 diamond swords", RegistryAccess.EMPTY, 1_200L).kind(),
				"summed duplicate requirements are revalidated against inventory capacity"
		);
		return 16;
	}

	private static int verifyManagerSubmissionFlow() {
		GoalCompiler compiler = new GoalCompiler();
		String request = "Go beat the game and kill the Ender Dragon";
		GoalCompilation compilation = compiler.compile(request, RegistryAccess.EMPTY, 1_200L);
		AgentRecord idle = AgentRecord.create(
				AgentId.random(),
				new AgentProfile("codex", "gpt-5.6-sol", "high", Optional.empty(), 0),
				10_000L
		);
		PendingGoalDraft draft = new PendingGoalDraft(
				UUID.randomUUID(), idle.agentId(), UUID.randomUUID(), request,
				List.of("minecraft:ender_dragon"), Optional.empty(), DraftIntent.TRANSLATE_START,
				1_200L, idle.goalRevision(), Optional.empty()
		);
		ArrayList<String> events = new ArrayList<>();
		GoalSubmission submission = GoalSubmissionFlow.route(
				compilation,
				spec -> { throw new AssertionError("translation must not activate a goal"); },
				() -> draft,
				staged -> events.add("staged:" + staged.draftId()),
				published -> events.add("published:" + published.draftId())
		);
		assertEquals(Optional.empty(), submission.transition(),
				"Manager translation does not activate before a proposal is confirmed");
		assertEquals(Optional.of(draft), submission.pendingDraft(),
				"Manager translation returns the durable pending draft");
		assertEquals(List.of("staged:" + draft.draftId(), "published:" + draft.draftId()), events,
				"Manager translation persists the draft before publishing goal_spec_request");
		expectCode("GOAL_DRAFT_NOT_READY",
				() -> GoalDraftResolution.authorize(draft, draft.requestingPlayerId(), false, GoalDraftChoice.CONFIRM),
				"an unverified coordinator draft cannot activate");
		PendingGoalDraft validated = draft.withProposedPredicate(
				new GoalPredicate.EntityKilledByAgent("minecraft:ender_dragon", true));
		assertEquals(GoalDraftResolution.Operation.START,
				GoalDraftResolution.authorize(validated, draft.requestingPlayerId(), false, GoalDraftChoice.CONFIRM),
				"the requested start activates only after a validated proposal is confirmed");
		PendingGoalDraft queued = new PendingGoalDraft(
				UUID.randomUUID(), idle.agentId(), draft.requestingPlayerId(), request,
				List.of("minecraft:ender_dragon"), validated.proposedPredicate(), DraftIntent.TRANSLATE_QUEUE,
				1_200L, idle.goalRevision(), Optional.empty()
		);
		assertEquals(GoalDraftResolution.Operation.QUEUE,
				GoalDraftResolution.authorize(queued, queued.requestingPlayerId(), false, GoalDraftChoice.CONFIRM),
				"a translated Manager queue request preserves its requested operation");
		return 6;
	}

	private static int verifyExplicitAlternativeCandidates() {
		GoalCompiler compiler = new GoalCompiler();
		assertEquals(
				List.of("minecraft:diamond_pickaxe", "minecraft:iron_pickaxe"),
				compiler.candidateIdsFor("Get an iron or diamond pickaxe", RegistryAccess.EMPTY),
				"shared item nouns publish each explicit alternative"
		);
		assertEquals(
				List.of("minecraft:skeleton", "minecraft:wither_skeleton", "minecraft:zombie"),
				compiler.candidateIdsFor("Kill a zombie or a skeleton", RegistryAccess.EMPTY),
				"kill alternatives publish each bounded related entity"
		);
		assertEquals(
				List.of("minecraft:skeleton", "minecraft:wither_skeleton", "minecraft:zombie"),
				compiler.candidateIdsFor("Kill 3 zombies or 2 skeletons", RegistryAccess.EMPTY),
				"each counted kill alternative publishes its matching any-of candidates"
		);
		assertEquals(
				List.of("minecraft:iron_pickaxe"),
				compiler.candidateIdsFor("Get an iron or iron pickaxe", RegistryAccess.EMPTY),
				"duplicate alternatives publish one candidate identifier"
		);
		assertTrue(
				compiler.candidateIdsFor("Get a sword or diamond pickaxe", RegistryAccess.EMPTY)
						.containsAll(List.of("minecraft:iron_sword", "minecraft:diamond_pickaxe")),
				"a standalone item alternative is not forced to share the final noun"
		);
		assertEquals(
				List.of(
						"minecraft:diamond_pickaxe", "minecraft:iron_pickaxe", "minecraft:skeleton",
						"minecraft:wither_skeleton", "minecraft:zombie"
				),
				compiler.candidateIdsFor(
						"Get an iron or diamond pickaxe and kill a zombie or skeleton", RegistryAccess.EMPTY
				),
				"compound clauses union item and kill alternatives"
		);
		assertEquals(
				List.of("minecraft:compass", "minecraft:recovery_compass"),
				compiler.candidateIdsFor("Get a compass to find iron or diamond", RegistryAccess.EMPTY),
				"purpose qualifiers do not invent item alternatives from unrelated nouns"
		);
		assertEquals(
				List.of("minecraft:diamond"),
				compiler.candidateIdsFor("Get diamond" + " or diamond".repeat(15), RegistryAccess.EMPTY),
				"sixteen explicit alternatives stay within the predicate leaf bound"
		);
		assertEquals(
				List.of(),
				compiler.candidateIdsFor("Get diamond" + " or diamond".repeat(16), RegistryAccess.EMPTY),
				"more than sixteen alternatives are not expanded into an invalid predicate"
		);
		assertEquals(
				64,
				compiler.candidateIdsFor("Get stairs or slab", RegistryAccess.EMPTY).size(),
				"alternative candidate unions retain the draft schema limit"
		);
		return 10;
	}

	private static int verifyKillCountTranslationBounds() {
		GoalCompiler compiler = new GoalCompiler();
		assertEquals(GoalCompilation.Kind.REJECTED,
				compiler.compile("Kill 0 zombies", RegistryAccess.EMPTY, 1_200L).kind(),
				"zero kills are rejected before translation");
		assertEquals(List.of(), compiler.candidateIdsFor("Kill 0 zombies", RegistryAccess.EMPTY),
				"zero kills publish no entity candidates");
		assertEquals(GoalCompilation.Kind.REJECTED,
				compiler.compile("Kill -1 zombies", RegistryAccess.EMPTY, 1_200L).kind(),
				"negative kills are rejected before translation");
		assertEquals(List.of(), compiler.candidateIdsFor("Kill -1 zombies", RegistryAccess.EMPTY),
				"negative kills publish no entity candidates");
		assertEquals(List.of("minecraft:zombie"),
				compiler.candidateIdsFor("Kill 16 zombies", RegistryAccess.EMPTY),
				"the exact sixteen-leaf boundary remains translatable");
		assertEquals(List.of(), compiler.candidateIdsFor("Kill 17 zombies", RegistryAccess.EMPTY),
				"a count above the predicate leaf limit publishes no candidates");
		assertEquals(GoalCompilation.Kind.REJECTED,
				compiler.compile("Kill 17 zombies", RegistryAccess.EMPTY, 1_200L).kind(),
				"a count above the predicate leaf limit is rejected");
		assertEquals(
				List.of("minecraft:skeleton", "minecraft:wither_skeleton", "minecraft:zombie"),
				compiler.candidateIdsFor("Kill 8 zombies or 8 skeletons", RegistryAccess.EMPTY),
				"counted alternatives may consume exactly sixteen leaves");
		assertEquals(List.of(),
				compiler.candidateIdsFor("Kill 8 zombies or 9 skeletons", RegistryAccess.EMPTY),
				"counted alternatives cannot exceed sixteen leaves in total");
		assertEquals(
				List.of("minecraft:apple", "minecraft:enchanted_golden_apple", "minecraft:golden_apple", "minecraft:zombie"),
				compiler.candidateIdsFor("Get an apple and kill 15 zombies", RegistryAccess.EMPTY),
				"a compound request may consume exactly sixteen leaves");
		assertEquals(List.of(),
				compiler.candidateIdsFor("Get an apple and kill 16 zombies", RegistryAccess.EMPTY),
				"compound non-kill leaves count against the translation limit");
		assertEquals(GoalCompilation.Kind.REJECTED,
				compiler.compile("Get an apple and kill 16 zombies", RegistryAccess.EMPTY, 1_200L).kind(),
				"an over-budget compound count is rejected before translation");
		assertEquals(
				List.of("minecraft:diamond_pickaxe", "minecraft:iron_pickaxe", "minecraft:zombie"),
				compiler.candidateIdsFor(
						"Get an iron or diamond pickaxe and kill 14 zombies", RegistryAccess.EMPTY),
				"item alternatives and counted kills may consume exactly sixteen leaves");
		assertEquals(List.of(),
				compiler.candidateIdsFor(
						"Get an iron or diamond pickaxe and kill 15 zombies", RegistryAccess.EMPTY),
				"item alternatives count separately from kill leaves in a compound translation");
		assertEquals(List.of(),
				compiler.candidateIdsFor("Kill 8 zombies and 9 skeletons", RegistryAccess.EMPTY),
				"separate counted kill clauses cannot exceed the shared leaf limit");
		return 15;
	}

	private static int verifyTranslatedKillConstraints() {
		GoalCompiler compiler = new GoalCompiler();
		assertEquals(GoalCompilation.Kind.REJECTED,
				compiler.compile("Kill 0 good zombies", RegistryAccess.EMPTY, 1_200L).kind(),
				"an invalid subjective kill count is rejected before translation");
		assertEquals(GoalCompilation.Kind.REJECTED,
				compiler.compile("Get a good pickaxe and kill 0 zombies", RegistryAccess.EMPTY, 1_200L).kind(),
				"an invalid compound kill count is rejected before subjective translation");

		GoalTranslationConstraint direct = compiler.translationConstraintFor(
				"Kill 3 zombies", RegistryAccess.EMPTY);
		assertSucceeds(() -> direct.validate(kills("minecraft:zombie", 3)),
				"three translated kill leaves preserve a requested count of three");
		expectCode("GOAL_TRANSLATION_CONSTRAINT_MISMATCH",
				() -> direct.validate(new GoalPredicate.EntityKilledByAgent("minecraft:zombie", true)),
				"one translated kill cannot satisfy a requested count of three");

		GoalTranslationConstraint alternatives = compiler.translationConstraintFor(
				"Kill 3 good zombies or 2 skeletons", RegistryAccess.EMPTY);
		assertSucceeds(() -> alternatives.validate(new GoalPredicate.AnyOf(List.of(
				kills("minecraft:zombie", 3),
				kills("minecraft:skeleton", 2)
		))), "each translated kill alternative preserves its own count");
		expectCode("GOAL_TRANSLATION_CONSTRAINT_MISMATCH",
				() -> alternatives.validate(new GoalPredicate.AnyOf(List.of(
						kills("minecraft:zombie", 3), new GoalPredicate.OperatorConfirmed()))),
				"an operator-confirmed alternative cannot bypass a counted kill branch");

		GoalTranslationConstraint compound = compiler.translationConstraintFor(
				"Get a good pickaxe and kill 2 good zombies and 3 skeletons", RegistryAccess.EMPTY);
		assertSucceeds(() -> compound.validate(new GoalPredicate.AllOf(List.of(
				new GoalPredicate.InventoryContains("minecraft:iron_pickaxe", 1),
				kills("minecraft:zombie", 2),
				kills("minecraft:skeleton", 3)
		))), "compound translations retain every requested kill count");
		expectCode("GOAL_TRANSLATION_CONSTRAINT_MISMATCH",
				() -> compound.validate(new GoalPredicate.AllOf(List.of(
						new GoalPredicate.InventoryContains("minecraft:iron_pickaxe", 1),
						kills("minecraft:zombie", 2),
						kills("minecraft:skeleton", 2)
				))),
				"compound translated kill counts each retain distinct evidence");

		GoalTranslationConstraint subjective = compiler.translationConstraintFor(
				"Kill 3 good zombies", RegistryAccess.EMPTY);
		assertSucceeds(() -> subjective.validate(new GoalPredicate.AllOf(List.of(
				kills("minecraft:zombie", 3), new GoalPredicate.OperatorConfirmed()))),
				"subjective confirmation may accompany the required factual kills");
		expectCode("GOAL_TRANSLATION_CONSTRAINT_MISMATCH",
				() -> subjective.validate(new GoalPredicate.OperatorConfirmed()),
				"operator confirmation alone cannot bypass a subjective counted kill request");

		GoalTranslationConstraint repeatedCompound = compiler.translationConstraintFor(
				"Kill 2 good zombies and 3 zombies", RegistryAccess.EMPTY);
		expectCode("GOAL_TRANSLATION_CONSTRAINT_MISMATCH",
				() -> repeatedCompound.validate(kills("minecraft:zombie", 3)),
				"one translated kill leaf cannot satisfy two compound kill clauses");
		assertSucceeds(() -> repeatedCompound.validate(kills("minecraft:zombie", 5)),
				"five distinct kill leaves satisfy compound counts of two and three");
		return 12;
	}

	private static int verifyTranslatedItemConstraints() {
		GoalCompiler compiler = new GoalCompiler();
		assertEquals(GoalCompilation.Kind.REJECTED,
				compiler.compile("Get 0 good iron pickaxes", RegistryAccess.EMPTY, 1_200L).kind(),
				"a zero subjective item count is rejected before translation");
		assertEquals(GoalCompilation.Kind.REJECTED,
				compiler.compile("Get -2 good iron pickaxes", RegistryAccess.EMPTY, 1_200L).kind(),
				"a negative subjective item count is rejected before translation");
		assertEquals(GoalCompilation.Kind.REJECTED,
				compiler.compile("Get 999999999999999999 good iron pickaxes", RegistryAccess.EMPTY, 1_200L).kind(),
				"an overflowing subjective item count is rejected before translation");
		assertEquals(GoalCompilation.Kind.REJECTED,
				compiler.compile("Get 3.5 good iron pickaxes", RegistryAccess.EMPTY, 1_200L).kind(),
				"a non-integral subjective item count is rejected before translation");
		assertEquals(GoalCompilation.Kind.REJECTED,
				compiler.compile("Get a good pickaxe and 0 diamonds", RegistryAccess.EMPTY, 1_200L).kind(),
				"an invalid compound item count is rejected before subjective translation");

		GoalTranslationConstraint direct = compiler.translationConstraintFor(
				"Get 3 good iron pickaxes", RegistryAccess.EMPTY);
		assertSucceeds(() -> direct.validate(new GoalPredicate.InventoryContains("minecraft:iron_pickaxe", 3)),
				"a translated item leaf preserves its requested quantity");
		expectCode("GOAL_TRANSLATION_CONSTRAINT_MISMATCH",
				() -> direct.validate(new GoalPredicate.InventoryContains("minecraft:iron_pickaxe", 1)),
				"a translated item leaf cannot lower its requested quantity");
		expectCode("GOAL_TRANSLATION_CONSTRAINT_MISMATCH",
				() -> direct.validate(new GoalPredicate.OperatorConfirmed()),
				"operator confirmation cannot replace a requested factual item quantity");
		expectCode("GOAL_TRANSLATION_CONSTRAINT_MISMATCH",
				() -> direct.validate(new GoalPredicate.AnyOf(List.of(
						new GoalPredicate.InventoryContains("minecraft:iron_pickaxe", 3),
						new GoalPredicate.OperatorConfirmed()))),
				"an operator-confirmed any-of branch cannot bypass an item quantity");

		GoalTranslationConstraint alternatives = compiler.translationConstraintFor(
				"Get 3 good iron or diamond pickaxes", RegistryAccess.EMPTY);
		assertSucceeds(() -> alternatives.validate(new GoalPredicate.AnyOf(List.of(
				new GoalPredicate.InventoryContains("minecraft:iron_pickaxe", 3),
				new GoalPredicate.InventoryContains("minecraft:diamond_pickaxe", 3)
		))), "every direct item alternative preserves the shared count");
		expectCode("GOAL_TRANSLATION_CONSTRAINT_MISMATCH", () -> alternatives.validate(
				new GoalPredicate.AnyOf(List.of(
						new GoalPredicate.InventoryContains("minecraft:iron_pickaxe", 3),
						new GoalPredicate.InventoryContains("minecraft:diamond_pickaxe", 1)
				))), "one weakened any-of branch rejects the translated alternative");

		GoalTranslationConstraint perAlternativeCounts = compiler.translationConstraintFor(
				"Get 3 good iron pickaxes or 2 diamond pickaxes", RegistryAccess.EMPTY);
		assertEquals(List.of("minecraft:diamond_pickaxe", "minecraft:iron_pickaxe"),
				compiler.candidateIdsFor("Get 3 good iron pickaxes or 2 diamond pickaxes", RegistryAccess.EMPTY),
				"per-alternative item counts are removed before publishing candidates");
		assertSucceeds(() -> perAlternativeCounts.validate(new GoalPredicate.AnyOf(List.of(
				new GoalPredicate.InventoryContains("minecraft:iron_pickaxe", 3),
				new GoalPredicate.InventoryContains("minecraft:diamond_pickaxe", 2)
		))), "explicit item alternatives may preserve different requested counts");

		GoalTranslationConstraint compound = compiler.translationConstraintFor(
				"Get 2 good pickaxes and 3 diamonds", RegistryAccess.EMPTY);
		assertSucceeds(() -> compound.validate(new GoalPredicate.AllOf(List.of(
				new GoalPredicate.InventoryContains("minecraft:iron_pickaxe", 2),
				new GoalPredicate.InventoryContains("minecraft:diamond", 3)
		))), "compound item translations retain every requested count");
		expectCode("GOAL_TRANSLATION_CONSTRAINT_MISMATCH", () -> compound.validate(
				new GoalPredicate.AnyOf(List.of(
						new GoalPredicate.InventoryContains("minecraft:iron_pickaxe", 2),
						new GoalPredicate.InventoryContains("minecraft:diamond", 3)
				))), "any-of cannot replace the requested compound conjunction");

		GoalTranslationConstraint repeated = compiler.translationConstraintFor(
				"Get 2 good iron pickaxes and 3 iron pickaxes", RegistryAccess.EMPTY);
		assertSucceeds(() -> repeated.validate(new GoalPredicate.InventoryContains("minecraft:iron_pickaxe", 5)),
				"one inventory threshold may preserve the summed repeated-item request");
		expectCode("GOAL_TRANSLATION_CONSTRAINT_MISMATCH", () -> repeated.validate(
				new GoalPredicate.AllOf(List.of(
						new GoalPredicate.InventoryContains("minecraft:iron_pickaxe", 2),
						new GoalPredicate.InventoryContains("minecraft:iron_pickaxe", 3)
				))), "duplicate inventory leaves cannot fake an additive quantity at runtime");

		GoalTranslationConstraint mixed = compiler.translationConstraintFor(
				"Get 2 good iron pickaxes and kill 3 good zombies", RegistryAccess.EMPTY);
		expectCode("GOAL_TRANSLATION_CONSTRAINT_MISMATCH", () -> mixed.validate(kills("minecraft:zombie", 3)),
				"preserving only the kill half cannot omit a compound item quantity");
		assertSucceeds(() -> mixed.validate(new GoalPredicate.AllOf(List.of(
				new GoalPredicate.InventoryContains("minecraft:iron_pickaxe", 2),
				kills("minecraft:zombie", 3)
		))), "mixed translations preserve both item and kill quantities");
		return 19;
	}

	private static int verifyRegistryCategoryTranslation() {
		GoalCompiler compiler = new GoalCompiler();
		String request = "Collect at least one log from any tree and keep it in your inventory.";
		List<String> logs = net.minecraft.core.registries.BuiltInRegistries.ITEM.keySet().stream()
				.filter(id -> id.getPath().endsWith("_log")).map(Object::toString).sorted().toList();
		assertTrue(logs.size() > 16, "registry fixture exceeds the old any-of leaf limit");
		assertEquals(logs, compiler.candidateIdsFor(request, RegistryAccess.EMPTY),
				"qualified category retrieves every registered matching item without selecting a progression strategy");
		assertEquals(GoalCompilation.Kind.NEEDS_TRANSLATION, compiler.compile(request, RegistryAccess.EMPTY, 1000).kind(),
				"registry retrieval leaves interpretation of the full request to the model");
		GoalTranslationConstraint constraint = compiler.translationConstraintFor(request, RegistryAccess.EMPTY);
		assertEquals(1, constraint.itemClauses().size(), "retaining referenced items does not invent another item requirement");
		assertEquals(logs, constraint.itemClauses().getFirst().alternatives().getFirst().itemIds(),
				"the translated identifier catalog and required category use the same registry candidates");
		assertSucceeds(() -> constraint.validate(new GoalPredicate.InventoryContainsAny(logs, 1)),
				"the complete category remains representable in one model-authored predicate");
		expectCode("GOAL_TRANSLATION_CONSTRAINT_MISMATCH", () -> constraint.validate(new GoalPredicate.OperatorConfirmed()),
				"subjective confirmation cannot replace an explicit inventory requirement");
		for (String verb : List.of("Gather", "Acquire", "Fetch", "Obtain")) {
			String qualified = verb + " at least three logs from nearby trees and retain them with you";
			assertEquals(logs, compiler.candidateIdsFor(qualified, RegistryAccess.EMPTY), "ordinary verb and plural wording uses the same catalog");
			GoalTranslationConstraint counted = compiler.translationConstraintFor(qualified, RegistryAccess.EMPTY);
			assertSucceeds(() -> counted.validate(new GoalPredicate.InventoryContainsAny(logs, 3)), "spoken minimum quantities remain enforceable");
			expectCode("GOAL_TRANSLATION_CONSTRAINT_MISMATCH", () -> counted.validate(new GoalPredicate.InventoryContainsAny(logs, 2)),
					"qualified wording cannot lower the requested count");
		}
		List<String> axes = compiler.candidateIdsFor("Acquire two iron axes from a chest", RegistryAccess.EMPTY);
		assertEquals(List.of("minecraft:iron_axe"), axes, "plural noun forms and source qualifiers work outside the original category");
		assertSucceeds(() -> compiler.translationConstraintFor("Acquire two iron axes from a chest", RegistryAccess.EMPTY)
				.validate(new GoalPredicate.InventoryContains("minecraft:iron_axe", 2)), "catalog and constraints preserve ordinary spoken quantities");
		assertEquals(List.of("minecraft:iron_pickaxe"), compiler.candidateIdsFor("Get minecraft:iron_pickaxe for later", RegistryAccess.EMPTY),
				"namespaced identifiers retain underscores during qualifier retrieval");
		assertEquals(List.of("minecraft:cookie"), compiler.candidateIdsFor("Get two cookies for later", RegistryAccess.EMPTY),
				"plural words ending in ies retain their registered singular e form");
		expectCode("GOAL_TRANSLATION_CANDIDATES_UNAVAILABLE",
				() -> compiler.translationConstraintFor("Get two nonexistent_widgets", RegistryAccess.EMPTY),
				"unmatched factual clauses fail before an impossible provider retry loop");
		expectCode("GOAL_TRANSLATION_CATALOG_TOO_BROAD",
				() -> compiler.translationConstraintFor("Get stairs or slab", RegistryAccess.EMPTY),
				"over-broad categories fail instead of silently dropping required eligible identifiers");
		return 25;
	}

	private static int verifyInventoryGroupConstraints() {
		List<String> first = List.of("minecraft:oak_log", "minecraft:birch_log");
		List<String> second = List.of("minecraft:birch_log", "minecraft:spruce_log");
		GoalTranslationConstraint category = inventoryConstraint(List.of(first), List.of(3));
		assertSucceeds(() -> category.requireCatalog(first), "the actual offered catalog contains all required category identifiers");
		expectCode("GOAL_TRANSLATION_CATALOG_MISMATCH", () -> category.requireCatalog(List.of("minecraft:oak_log", "minecraft:diamond")),
				"an unrelated offered candidate cannot conceal a required identifier omitted by a bounded mixed catalog");
		assertSucceeds(() -> category.validate(new GoalPredicate.AllOf(List.of(
				new GoalPredicate.InventoryContains("minecraft:oak_log", 1), new GoalPredicate.InventoryContains("minecraft:birch_log", 2)))),
				"disjoint exact stacks sum toward a category quantity");
		assertSucceeds(() -> category.validate(new GoalPredicate.InventoryContainsAny(first, 3)), "a matching group proves its summed minimum");
		expectCode("GOAL_TRANSLATION_CONSTRAINT_MISMATCH", () -> category.validate(
				new GoalPredicate.InventoryContainsAny(List.of("minecraft:oak_log", "minecraft:diamond"), 3)),
				"unrelated inventory variants cannot widen a requested category");
		GoalTranslationConstraint repeated = inventoryConstraint(List.of(first, first), List.of(2, 3));
		assertSucceeds(() -> repeated.validate(new GoalPredicate.InventoryContainsAny(first, 5)),
				"one group can prove repeated requested quantities by consuming its guarantee once");
		expectCode("GOAL_TRANSLATION_CONSTRAINT_MISMATCH", () -> repeated.validate(new GoalPredicate.AllOf(List.of(
				new GoalPredicate.InventoryContainsAny(first, 2), new GoalPredicate.InventoryContainsAny(first.reversed(), 3)))),
				"identical unordered evidence groups merge by maximum and cannot fake additive counts");
		GoalTranslationConstraint overlap = inventoryConstraint(List.of(first, second), List.of(2, 2));
		expectCode("GOAL_TRANSLATION_CONSTRAINT_MISMATCH", () -> overlap.validate(new GoalPredicate.AllOf(List.of(
				new GoalPredicate.InventoryContainsAny(first, 2), new GoalPredicate.InventoryContainsAny(second, 2)))),
				"overlapping group guarantees cannot count the same two birch logs twice");
		assertSucceeds(() -> overlap.validate(new GoalPredicate.AllOf(List.of(
				new GoalPredicate.InventoryContains("minecraft:oak_log", 2), new GoalPredicate.InventoryContains("minecraft:spruce_log", 2)))),
				"independent exact witnesses preserve overlapping requested categories");
		GoalTranslationConstraint scarcity = inventoryConstraint(List.of(first, List.of("minecraft:birch_log")), List.of(2, 2));
		assertSucceeds(() -> scarcity.validate(new GoalPredicate.AllOf(List.of(
				new GoalPredicate.InventoryContains("minecraft:birch_log", 2), new GoalPredicate.InventoryContains("minecraft:oak_log", 2)))),
				"category allocation preserves evidence needed by a later specific clause");
		expectCode("GOAL_TRANSLATION_CONSTRAINT_MISMATCH", () -> category.validate(new GoalPredicate.AnyOf(List.of(
				new GoalPredicate.InventoryContainsAny(first, 3), new GoalPredicate.OperatorConfirmed()))),
				"every branch must retain the objective category count");
		return 11;
	}

	private static GoalTranslationConstraint inventoryConstraint(List<List<String>> groups, List<Integer> counts) {
		List<GoalTranslationConstraint.ItemClause> clauses = new ArrayList<>();
		for (int index = 0; index < groups.size(); index++) clauses.add(new GoalTranslationConstraint.ItemClause(List.of(
				new GoalTranslationConstraint.ItemAlternative(groups.get(index), counts.get(index)))));
		return new GoalTranslationConstraint(List.of(), clauses);
	}

	private static int verifyDraftRoundTrip() {
		GoalTranslationConstraint constraint = new GoalCompiler().translationConstraintFor(
				"Get 3 good iron pickaxes and kill 2 good zombies", RegistryAccess.EMPTY);
		GoalPredicate proposed = new GoalPredicate.AllOf(List.of(
				new GoalPredicate.InventoryContains("minecraft:iron_pickaxe", 3),
				kills("minecraft:zombie", 2)));
		PendingGoalDraft draft = new PendingGoalDraft(
				UUID.fromString("00000000-0000-0000-0000-000000000101"),
				new AgentId(UUID.fromString("00000000-0000-0000-0000-000000000102")),
				UUID.fromString("00000000-0000-0000-0000-000000000103"),
				"Get 3 good iron pickaxes and kill 2 good zombies",
				"minecraft:the_nether",
				List.of("minecraft:iron_pickaxe", "minecraft:zombie"),
				constraint,
				Optional.of(proposed),
				DraftIntent.CONFIRM_TRANSLATION,
				1_200L,
				4L,
				Optional.empty()
		);
		PendingGoalDraftCodec codec = new PendingGoalDraftCodec();
		assertEquals(draft, codec.decode(codec.encode(draft)), "pending goal draft round-trip");
		assertEquals(
				draft.withProposedPredicate(proposed),
				codec.decode(codec.encode(draft.withProposedPredicate(proposed))),
				"atomic proposal replacement retains draft identity and candidate IDs"
		);
		var priorJson = JsonParser.parseString(codec.encode(draft)).getAsJsonObject();
		priorJson.getAsJsonObject("translation_constraint").remove("item_clauses");
		assertEquals(new GoalTranslationConstraint(constraint.killClauses()),
				codec.decode(priorJson.toString()).translationConstraint(),
				"kill-only translation constraints from prior drafts remain readable");
		priorJson.remove("translation_constraint");
		assertEquals(GoalTranslationConstraint.none(), codec.decode(priorJson.toString()).translationConstraint(),
				"drafts persisted before constraints remain readable without invented requirements");
		priorJson.remove("dimension_id");
		assertEquals(GoalPredicate.DEFAULT_DIMENSION, codec.decode(priorJson.toString()).dimensionId(),
				"legacy persisted drafts retain their historical Overworld interpretation");
		return 5;
	}

	private static GoalPredicate kills(String entityType, int count) {
		return new GoalPredicate.AllOf(java.util.stream.IntStream.range(0, count)
				.mapToObj(ignored -> (GoalPredicate) new GoalPredicate.EntityKilledByAgent(entityType, true))
				.toList());
	}

	private static int verifyWorldValidation() {
		GoalPredicate translated = new GoalPredicate.AllOf(List.of(
				new GoalPredicate.PositionWithin(12, 64, -8, 1, 20),
				new GoalPredicate.BlockMatches(12, 64, -8, "minecraft:oak_stairs", Map.of("facing", "north"))
		));
		GoalPredicate bound = GoalPredicateWorldValidator.bindToDimension(translated, "minecraft:the_nether");
		GoalPredicate.AllOf compound = (GoalPredicate.AllOf) bound;
		assertEquals("minecraft:the_nether", ((GoalPredicate.PositionWithin) compound.predicates().get(0)).dimensionId(),
				"translated position binds to the requester's live dimension");
		assertEquals("minecraft:the_nether", ((GoalPredicate.BlockMatches) compound.predicates().get(1)).dimensionId(),
				"translated block binds to the requester's live dimension");
		GoalPredicateWorldValidator.validate("minecraft:the_nether", y -> y >= 0 && y < 128, bound);
		assertTrue(GoalPredicateWorldValidator.requiresLiveLevel(bound),
				"spatial compounds require a live dimension before staging or activation");
		expectCode("GOAL_COORDINATES_OUT_OF_BUILD_HEIGHT", () -> GoalPredicateWorldValidator.validate(
				"minecraft:overworld", y -> y >= -64 && y < 320,
				new GoalPredicate.PositionWithin("minecraft:overworld", 0, 1_000, 0, 1, 20)),
				"coordinates outside the selected dimension build height are rejected");
		expectCode("GOAL_DIMENSION_MISMATCH", () -> GoalPredicateWorldValidator.validate(
				"minecraft:the_nether", y -> true,
				new GoalPredicate.PositionWithin("minecraft:overworld", 0, 64, 0, 1, 20)),
				"a spatial predicate cannot escape its selected dimension");
		GoalPredicateWorldValidator.validateBlockProperties("minecraft:oak_stairs", Map.of("facing", "north"));
		expectCode("INVALID_GOAL_BLOCK_PROPERTY", () -> GoalPredicateWorldValidator.validateBlockProperties(
				"minecraft:oak_stairs", Map.of("imaginary", "north")),
				"unknown block-state properties are rejected before staging");
		expectCode("INVALID_GOAL_BLOCK_PROPERTY_VALUE", () -> GoalPredicateWorldValidator.validateBlockProperties(
				"minecraft:oak_stairs", Map.of("facing", "upwards")),
				"unknown block-state values are rejected before staging");
		return 7;
	}

	private static int verifyWireBoundaryInvariants() {
		GoalSpecWireCodec codec = new GoalSpecWireCodec();
		GoalPredicate.PositionWithin minimumPosition = new GoalPredicate.PositionWithin(12, 64, -8, 0.01, 1);
		assertEquals(minimumPosition, codec.decodePredicate(codec.encodePredicate(minimumPosition)),
				"minimum executable position radius survives the Java wire round-trip");
		GoalPredicate.EntityKilledByAgent attributedKill =
				new GoalPredicate.EntityKilledByAgent("minecraft:zombie", true);
		assertEquals(attributedKill, codec.decodePredicate(codec.encodePredicate(attributedKill)),
				"post-activation kill attribution survives the Java wire round-trip");
		expectCode("INVALID_GOAL_PREDICATE", () -> codec.decodePredicate(JsonParser.parseString("""
				{"type":"position_within","x":12,"y":64,"z":-8,"radius":0,"stableTicks":1}
				""").getAsJsonObject()), "zero-radius wire predicates fail closed");
		GoalPredicate historicalKill = codec.decodePredicate(JsonParser.parseString("""
				{"type":"entity_killed_by_agent","entityType":"minecraft:zombie","afterGoalStart":false}
				""").getAsJsonObject());
		assertEquals(new GoalPredicate.EntityKilledByAgent("minecraft:zombie", false), historicalKill,
				"general goal decoding preserves explicit historical attribution semantics");
		expectCode("INVALID_GOAL_PREDICATE",
				() -> GoalPredicateWorldValidator.validateTranslatedProposal(historicalKill),
				"pre-goal kill attribution fails closed at the translated-proposal boundary");
		return 5;
	}

	private static int verifySpecAwareLifecycleStart() {
		GoalSpec spec = new GoalCompiler().compile(
				"Get an iron pickaxe", RegistryAccess.EMPTY, 1_200L
		).acceptedSpec().orElseThrow();
		AgentRecord idle = AgentRecord.create(
				AgentId.random(),
				new AgentProfile("codex", "gpt-5.6-sol", "high", Optional.empty(), 0),
				10_000L
		);
		AgentRecord started = AgentLifecycleReducer.start(idle, spec, 10_001L).after();
		assertEquals(spec, started.currentGoal().orElseThrow().spec(), "lifecycle starts the frozen compiled specification");
		assertEquals(dev.agaminggod.arenaagents.agent.goal.GoalStatus.ACTIVE,
				started.currentGoal().orElseThrow().status(), "compiled goal starts active");

		GoalSpec queuedSpec = new GoalCompiler().compile(
				"Get a diamond pickaxe", RegistryAccess.EMPTY, 1_201L
		).acceptedSpec().orElseThrow();
		AgentRecord queued = AgentLifecycleReducer.queue(started, queuedSpec, 8, 10_002L).after();
		UUID replacedGoalId = queued.currentGoal().orElseThrow().goalId();
		long replacedRevision = queued.goalRevision();
		GoalSpec replacement = new GoalCompiler().compile(
				"Get an iron axe", RegistryAccess.EMPTY, 1_202L
		).acceptedSpec().orElseThrow();
		var replaced = AgentLifecycleReducer.replace(queued, replacement, 10_003L);
		assertEquals(replacement, replaced.after().currentGoal().orElseThrow().spec(),
				"replace installs the confirmed frozen specification");
		assertEquals(false, replacedGoalId.equals(replaced.after().currentGoal().orElseThrow().goalId()),
				"replace creates a distinct goal identity");
		assertEquals(replacedRevision + 1L, replaced.after().goalRevision(),
				"replace advances the lifecycle revision exactly once");
		assertEquals(queued.queuedGoals(), replaced.after().queuedGoals(),
				"replace preserves already queued work");
		assertEquals(true, replaced.cancelAction(), "replace cancels physical work owned by the prior goal");
		return 7;
	}

	private static int verifyDraftAuthorizationAndChoices() {
		UUID requester = UUID.randomUUID();
		PendingGoalDraft idleDraft = new PendingGoalDraft(
				UUID.randomUUID(), AgentId.random(), requester, "Get an iron pickaxe",
				Optional.of(new GoalPredicate.InventoryContains("minecraft:iron_pickaxe", 1)),
				DraftIntent.CONFIRM_TRANSLATION, 100L, 0L, Optional.empty()
		);
		assertEquals(GoalDraftResolution.Operation.START,
				GoalDraftResolution.authorize(idleDraft, requester, false, GoalDraftChoice.CONFIRM),
				"requester can confirm a validated idle draft");
		expectCode("GOAL_DRAFT_FORBIDDEN",
				() -> GoalDraftResolution.authorize(idleDraft, UUID.randomUUID(), false, GoalDraftChoice.CONFIRM),
				"another player cannot resolve the draft");
		assertEquals(GoalDraftResolution.Operation.START,
				GoalDraftResolution.authorize(idleDraft, UUID.randomUUID(), true, GoalDraftChoice.CONFIRM),
				"operator can confirm another player's draft");

		PendingGoalDraft activeDraft = new PendingGoalDraft(
				UUID.randomUUID(), idleDraft.agentId(), requester, "Get a diamond pickaxe",
				Optional.of(new GoalPredicate.InventoryContains("minecraft:diamond_pickaxe", 1)),
				DraftIntent.REPLACE_OR_QUEUE, 101L, 4L, Optional.of(UUID.randomUUID())
		);
		assertEquals(GoalDraftResolution.Operation.REPLACE,
				GoalDraftResolution.authorize(activeDraft, requester, false, GoalDraftChoice.REPLACE),
				"active draft explicitly replaces only after the player's choice");
		assertEquals(GoalDraftResolution.Operation.QUEUE,
				GoalDraftResolution.authorize(activeDraft, requester, false, GoalDraftChoice.QUEUE),
				"active draft can queue without changing current work");
		assertEquals(GoalDraftResolution.Operation.CANCEL,
				GoalDraftResolution.authorize(activeDraft, requester, false, GoalDraftChoice.CANCEL),
				"cancel removes only the draft");
		expectCode("GOAL_DRAFT_CHOICE_REQUIRED",
				() -> GoalDraftResolution.authorize(activeDraft, requester, false, GoalDraftChoice.CONFIRM),
				"active draft requires an explicit replace or queue choice");
		return 7;
	}

	private static int verifyDraftRevisionBinding() {
		AgentRecord idle = AgentRecord.create(
				AgentId.random(),
				new AgentProfile("codex", "gpt-5.6-sol", "high", Optional.empty(), 0),
				10_000L
		);
		PendingGoalDraft draft = new PendingGoalDraft(
				UUID.randomUUID(), idle.agentId(), UUID.randomUUID(), "Get an iron pickaxe",
				Optional.of(new GoalPredicate.InventoryContains("minecraft:iron_pickaxe", 1)),
				DraftIntent.START, 100L, idle.goalRevision(), Optional.empty()
		);
		assertEquals(true, draft.matches(idle), "draft matches the exact idle revision it was created against");
		GoalSpec spec = new GoalCompiler().compile("Get an iron pickaxe", RegistryAccess.EMPTY, 100L)
				.acceptedSpec().orElseThrow();
		AgentRecord started = AgentLifecycleReducer.start(idle, spec, 10_001L).after();
		assertEquals(false, draft.matches(started), "draft becomes stale when the goal revision changes");

		UUID activeGoalId = started.currentGoal().orElseThrow().goalId();
		assertEquals(Optional.of(activeGoalId), PendingGoalDraft.expectedGoalIdFor(started),
				"unfinished work remains the exact expected goal for replace or queue");
		PendingGoalDraft activeDraft = new PendingGoalDraft(
				UUID.randomUUID(), started.agentId(), UUID.randomUUID(), "Get a diamond pickaxe",
				Optional.of(new GoalPredicate.InventoryContains("minecraft:diamond_pickaxe", 1)),
				DraftIntent.CONFIRM_TRANSLATION, 101L, started.goalRevision(), Optional.of(activeGoalId)
		);
		assertEquals(true, activeDraft.matches(started), "unfinished work keeps its exact replace-or-queue fence");

		GoalEvidence evidence = new GoalEvidence(
				started.goalRevision(),
				"inventory_contains",
				List.of(new GoalEvidence.Fact(
						"inventory_contains", true,
						"minecraft:iron_pickaxe x1", "minecraft:iron_pickaxe x1"))
		);
		AgentRecord completed = AgentLifecycleReducer.satisfyGoal(
				started, started.goalRevision(), evidence, 10_002L).after();
		assertEquals(Optional.empty(), PendingGoalDraft.expectedGoalIdFor(completed),
				"satisfied retained history is absent from a new draft's expected goal");

		UUID requester = UUID.randomUUID();
		PendingGoalDraft completedDraft = new PendingGoalDraft(
				UUID.randomUUID(), completed.agentId(), requester, "Get a diamond pickaxe",
				Optional.of(new GoalPredicate.InventoryContains("minecraft:diamond_pickaxe", 1)),
				DraftIntent.CONFIRM_TRANSLATION, 102L, completed.goalRevision(), Optional.empty()
		);
		assertEquals(true, completedDraft.matches(completed),
				"a draft survives proposal and confirmation while satisfied history remains attached");
		assertEquals(GoalDraftResolution.Operation.START,
				GoalDraftResolution.authorize(completedDraft, requester, false, GoalDraftChoice.CONFIRM),
				"ordinary confirm starts new work after a satisfied retained goal");
		assertEquals(false, activeDraft.matches(completed),
				"an unfinished-goal fence cannot match satisfied retained history");
		return 8;
	}

	private static void assertEquals(Object expected, Object actual, String label) {
		if (!java.util.Objects.equals(expected, actual)) {
			throw new AssertionError(label + ": expected=" + expected + ", actual=" + actual);
		}
		System.out.println("PASS: " + label);
	}

	private static void assertTrue(boolean value, String label) {
		if (!value) throw new AssertionError(label);
		System.out.println("PASS: " + label);
	}

	private static void assertSucceeds(Runnable operation, String label) {
		operation.run();
		System.out.println("PASS: " + label);
	}

	private static void expectCode(String code, Runnable operation, String label) {
		try {
			operation.run();
			throw new AssertionError(label + ": expected " + code);
		} catch (dev.agaminggod.arenaagents.agent.AgentDomainException exception) {
			assertEquals(code, exception.code(), label);
		}
	}

	private static void bindItemStackSize(Item item, int maxStackSize) {
		item.builtInRegistryHolder().bindComponents(
				DataComponentMap.builder().set(DataComponents.MAX_STACK_SIZE, maxStackSize).build()
		);
	}
}
