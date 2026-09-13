package dev.agaminggod.arenaagents.agent;

import dev.agaminggod.arenaagents.agent.goal.GoalPredicate;
import dev.agaminggod.arenaagents.agent.goal.GoalEvidence;
import dev.agaminggod.arenaagents.agent.goal.GoalSpec;
import dev.agaminggod.arenaagents.agent.goal.GoalSpecCodec;
import dev.agaminggod.arenaagents.agent.goal.GoalStatus;
import dev.agaminggod.arenaagents.server.goal.GoalSpecWireCodec;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.util.List;
import java.util.Map;
import java.util.Optional;

public final class GoalSpecVerification {
	private GoalSpecVerification() {
	}

	public static int verify() {
		int assertions = 0;
		assertions += verifyCanonicalRoundTrip();
		assertions += verifyInventoryCategory();
		assertions += verifyClosedSchema();
		assertions += verifyComplexityBounds();
		assertions += verifyAgentGoalPersistence();
		assertions += verifyLegacyGoalMigration();
		assertions += verifyPromptCanonicalization();
		assertions += verifyTerminalStatusAuthority();
		assertions += verifyPersistedEnvelopeIsClosed();
		return assertions;
	}

	private static int verifyCanonicalRoundTrip() {
		GoalSpecCodec codec = new GoalSpecCodec();
		GoalSpec spec = GoalSpec.create(
				"Get an iron pickaxe",
				new GoalPredicate.AllOf(List.of(
						new GoalPredicate.InventoryContains("minecraft:iron_pickaxe", 1),
						new GoalPredicate.BlockMatches(12, 64, -8, "minecraft:crafting_table", Map.of("facing", "north"))
				)),
				1_200L
		);
		String encoded = codec.encode(spec);
		GoalSpec decoded = codec.decode(encoded);
		assertEquals(spec, decoded, "goal spec round-trip");
		assertEquals(encoded, codec.encode(decoded), "goal spec canonical bytes");
		assertEquals(64, spec.fingerprint().length(), "goal spec SHA-256 fingerprint length");
		return 3;
	}

	private static int verifyInventoryCategory() {
		GoalSpecCodec codec = new GoalSpecCodec();
		GoalSpecWireCodec wire = new GoalSpecWireCodec();
		java.util.ArrayList<String> source = new java.util.ArrayList<>(List.of("minecraft:oak_log", "minecraft:birch_log"));
		GoalPredicate.InventoryContainsAny category = new GoalPredicate.InventoryContainsAny(source, 3);
		source.clear();
		assertEquals(List.of("minecraft:oak_log", "minecraft:birch_log"), category.itemIds(), "category owns the ordered item IDs");
		GoalSpec spec = GoalSpec.create("Collect any logs", category, 1200L);
		assertEquals("f83b10c17263df853e9ff267009f9944df8f660818b7037396c607eea8581c90", spec.fingerprint(), "category fingerprint matches coordinator fixture");
		assertEquals(spec, codec.decode(codec.encode(spec)), "inventory category persisted round-trip");
		assertEquals("{\"type\":\"inventory_contains_any\",\"item_ids\":[\"minecraft:oak_log\",\"minecraft:birch_log\"],\"count\":3}",
				codec.encodePredicateObject(category).toString(), "persisted category uses canonical snake-case keys");
		assertEquals(category, wire.decodePredicate(wire.encodePredicate(category)), "inventory category wire round-trip");
		assertEquals(category.itemIds(), wire.identifiers(category), "all accepted category identifiers cross validation boundary");
		expectFailure(() -> new GoalPredicate.InventoryContainsAny(List.of(), 1), "INVALID_GOAL_PREDICATE");
		expectFailure(() -> new GoalPredicate.InventoryContainsAny(List.of("minecraft:oak_log", "minecraft:oak_log"), 1), "INVALID_GOAL_PREDICATE");
		expectFailure(() -> new GoalPredicate.InventoryContainsAny(List.of("oak_log"), 1), "INVALID_GOAL_PREDICATE");
		expectFailure(() -> new GoalPredicate.InventoryContainsAny(category.itemIds(), 0), "INVALID_GOAL_PREDICATE");
		List<String> identifiers = java.util.stream.IntStream.range(0, 65).mapToObj(index -> "minecraft:test_" + index).toList();
		expectFailure(() -> new GoalPredicate.InventoryContainsAny(identifiers, 1), "INVALID_GOAL_PREDICATE");
		GoalPredicate bounded = new GoalPredicate.AllOf(List.of(
				new GoalPredicate.InventoryContainsAny(identifiers.subList(0, 32), 1),
				new GoalPredicate.InventoryContainsAny(identifiers.subList(32, 64), 1),
				new GoalPredicate.InventoryContains("minecraft:stone", 1)));
		assertEquals(bounded, codec.decode(codec.encode(GoalSpec.create("Bounded categories", bounded, 1))).completion(), "64 grouped references fit alongside exact inventory leaves");
		assertEquals(bounded, wire.decodePredicate(wire.encodePredicate(bounded)), "64 grouped references fit the wire budget");
		GoalPredicate excess = new GoalPredicate.AllOf(List.of(
				new GoalPredicate.InventoryContainsAny(identifiers.subList(0, 32), 1),
				new GoalPredicate.InventoryContainsAny(identifiers.subList(32, 65), 1)));
		expectFailure(() -> GoalSpec.create("Too many category references", excess, 1), "GOAL_PREDICATE_LIMIT_EXCEEDED");
		expectFailure(() -> wire.decodePredicate(wire.encodePredicate(excess)), "GOAL_PREDICATE_LIMIT_EXCEEDED");
		JsonObject wrongType = wire.encodePredicate(category);
		wrongType.addProperty("itemIds", "minecraft:oak_log");
		expectFailure(() -> wire.decodePredicate(wrongType), "INVALID_GOAL_PREDICATE");
		JsonObject wrongElement = codec.encodePredicateObject(category);
		wrongElement.getAsJsonArray("item_ids").set(0, new com.google.gson.JsonPrimitive(4));
		expectFailure(() -> codec.decodePredicateObject(wrongElement), "INVALID_GOAL_SPEC");
		JsonObject unknown = wire.encodePredicate(category);
		unknown.addProperty("extra", true);
		expectFailure(() -> wire.decodePredicate(unknown), "INVALID_GOAL_PREDICATE");
		return 18;
	}

	private static int verifyClosedSchema() {
		GoalSpecCodec codec = new GoalSpecCodec();
		GoalSpec spec = GoalSpec.create(
				"Get an iron pickaxe",
				new GoalPredicate.InventoryContains("minecraft:iron_pickaxe", 1),
				1_200L
		);
		String encoded = codec.encode(spec);
		expectFailure(() -> codec.decode(encoded.replace("inventory_contains", "invented_rule")), "UNKNOWN_GOAL_PREDICATE");
		expectFailure(() -> codec.decode(encoded.replace("\"count\":1", "\"count\":1,\"extra\":true")), "UNKNOWN_GOAL_FIELD");
		expectFailure(() -> codec.decode(encoded.replace(spec.fingerprint(), "0".repeat(64))), "GOAL_FINGERPRINT_MISMATCH");
		return 3;
	}

	private static int verifyComplexityBounds() {
		GoalPredicate predicate = new GoalPredicate.OperatorConfirmed();
		for (int index = 0; index < 5; index++) {
			predicate = new GoalPredicate.AllOf(List.of(predicate));
		}
		GoalPredicate overDepth = predicate;
		expectFailure(() -> GoalSpec.create("Nested goal", overDepth, 1L), "GOAL_PREDICATE_DEPTH_EXCEEDED");

		List<GoalPredicate> leaves = java.util.stream.IntStream.range(0, 17)
				.mapToObj(index -> (GoalPredicate) new GoalPredicate.InventoryContains("minecraft:stone", 1))
				.toList();
		expectFailure(() -> GoalSpec.create("Large goal", new GoalPredicate.AllOf(leaves), 1L), "GOAL_PREDICATE_LIMIT_EXCEEDED");
		return 2;
	}

	private static int verifyAgentGoalPersistence() {
		GoalSpec spec = GoalSpec.create(
				"Get an iron pickaxe",
				new GoalPredicate.InventoryContains("minecraft:iron_pickaxe", 1),
				1_200L
		);
		GoalEvidence evidence = new GoalEvidence(
				1_260L,
				"PREDICATE_SATISFIED",
				List.of(new GoalEvidence.Fact("inventory_contains", true, "minecraft:iron_pickaxe x1", "minecraft:iron_pickaxe x1"))
		);
		AgentGoal goal = AgentGoal.create("Get an iron pickaxe", spec, 10_000L)
				.withStatus(GoalStatus.SATISFIED, Optional.of(evidence), 10_100L);
		AgentRecord record = AgentRecord.create(
				AgentId.random(),
				new AgentProfile("codex", "gpt-5.6-sol", "high", Optional.empty(), 0),
				9_000L
		).withLifecycle(AgentLifecycleState.COMPLETED, Optional.of(goal), 1L, List.of(), 10_100L, "");
		AgentRegistry.Snapshot snapshot = new AgentRegistry.Snapshot(
				AgentConstants.SCHEMA_VERSION,
				AgentConstants.DEFAULT_AGENT_LIMIT,
				AgentConstants.DEFAULT_QUEUE_LIMIT,
				List.of(record)
		);
		AgentGoal decoded = new AgentRegistrySnapshotCodec().decode(
				new AgentRegistrySnapshotCodec().encode(snapshot)
		).records().getFirst().currentGoal().orElseThrow();
		assertEquals(goal, decoded, "authoritative goal snapshot round-trip");
		assertEquals(Optional.of(evidence), decoded.evidence(), "goal evidence snapshot round-trip");
		return 2;
	}

	private static int verifyLegacyGoalMigration() {
		AgentRegistry registry = AgentRegistry.createDefault(() -> { }, transition -> { });
		AgentRecord record = registry.create("gpt-5.6-sol", "high", Optional.of("Legacy"), 10_000L);
		record = registry.start(record.agentId(), "Mine iron", 10_001L).after();
		registry.queue(record.agentId(), "Bring coal", 10_002L);
		AgentRegistrySnapshotCodec codec = new AgentRegistrySnapshotCodec();
		JsonObject legacyRoot = JsonParser.parseString(codec.encode(registry.snapshot())).getAsJsonObject();
		JsonObject legacyRecord = legacyRoot.getAsJsonArray("agents").get(0).getAsJsonObject();
		stripAuthoritativeFields(legacyRecord.getAsJsonObject("current_goal"));
		stripAuthoritativeFields(legacyRecord.getAsJsonArray("queue").get(0).getAsJsonObject());
		AgentRecord migratedRecord = codec.decode(legacyRoot.toString()).records().getFirst();
		AgentGoal migrated = migratedRecord.currentGoal().orElseThrow();
		assertEquals(GoalStatus.ACTIVE, migrated.status(), "legacy active goal remains explicitly confirmable");
		assertEquals(new GoalPredicate.OperatorConfirmed(), migrated.spec().completion(), "legacy goal cannot auto-satisfy");
		assertEquals(GoalStatus.ACTIVE, migratedRecord.queuedGoals().getFirst().status(),
				"legacy queued goal is confirmable after promotion");
		assertEquals(GoalStatus.ACTIVE, AgentGoal.create("Subjective scenario goal", 10_003L).status(),
				"the compatibility string path creates a resolvable confirmation goal");
		JsonObject previouslyMigrated = JsonParser.parseString(codec.encode(registry.snapshot())).getAsJsonObject();
		previouslyMigrated.getAsJsonArray("agents").get(0).getAsJsonObject()
				.getAsJsonObject("current_goal").addProperty("status", GoalStatus.AWAITING_CLARIFICATION.name());
		assertEquals(GoalStatus.ACTIVE,
				codec.decode(previouslyMigrated.toString()).records().getFirst().currentGoal().orElseThrow().status(),
				"a previously migrated confirmation placeholder is repaired on its next reload");
		return 5;
	}

	private static void stripAuthoritativeFields(JsonObject goal) {
		goal.remove("spec");
		goal.remove("status");
		goal.remove("evidence");
	}

	private static int verifyPromptCanonicalization() {
		GoalSpec spec = GoalSpec.create(
				"  Get   an\tiron pickaxe  ",
				new GoalPredicate.InventoryContains("minecraft:iron_pickaxe", 1),
				1L
		);
		AgentGoal goal = AgentGoal.create("  Get   an\tiron pickaxe  ", spec, 10_000L);
		assertEquals("Get an iron pickaxe", spec.originalRequest(), "goal request uses prompt canonicalization");
		assertEquals(spec.originalRequest(), goal.prompt(), "goal prompt and spec request remain identical");
		return 2;
	}

	private static int verifyTerminalStatusAuthority() {
		GoalSpec spec = GoalSpec.create(
				"Get an iron pickaxe",
				new GoalPredicate.InventoryContains("minecraft:iron_pickaxe", 1),
				1L
		);
		AgentGoal active = AgentGoal.create("Get an iron pickaxe", spec, 10_000L);
		expectFailure(() -> active.withStatus(GoalStatus.SATISFIED, Optional.empty(), 10_001L), "INVALID_GOAL_EVIDENCE");
		GoalEvidence evidence = new GoalEvidence(
				2L,
				"PREDICATE_SATISFIED",
				List.of(new GoalEvidence.Fact("inventory_contains", true, "iron x1", "iron x1"))
		);
		AgentGoal satisfied = active.withStatus(GoalStatus.SATISFIED, Optional.of(evidence), 10_001L);
		expectFailure(() -> satisfied.withStatus(GoalStatus.ACTIVE, Optional.empty(), 10_002L), "TERMINAL_GOAL");
		return 2;
	}

	private static int verifyPersistedEnvelopeIsClosed() {
		AgentRegistry registry = AgentRegistry.createDefault(() -> { }, transition -> { });
		AgentRecord record = registry.create("gpt-5.6-sol", "high", Optional.of("Strict"), 10_000L);
		registry.start(record.agentId(), "Mine iron", 10_001L);
		AgentRegistrySnapshotCodec codec = new AgentRegistrySnapshotCodec();
		JsonObject root = JsonParser.parseString(codec.encode(registry.snapshot())).getAsJsonObject();
		root.getAsJsonArray("agents").get(0).getAsJsonObject().getAsJsonObject("current_goal").addProperty("invented", true);
		expectFailure(() -> codec.decode(root.toString()), "UNKNOWN_GOAL_FIELD");

		GoalSpec spec = GoalSpec.create("Mine iron", new GoalPredicate.InventoryContains("minecraft:raw_iron", 1), 1L);
		String malformedNumber = new GoalSpecCodec().encode(spec).replace("\"count\":1", "\"count\":1e999999");
		expectFailure(() -> new GoalSpecCodec().decode(malformedNumber), "INVALID_GOAL_SPEC");
		return 2;
	}

	private static void expectFailure(Runnable action, String expectedCode) {
		try {
			action.run();
			throw new AssertionError("Expected failure " + expectedCode);
		} catch (AgentDomainException exception) {
			assertEquals(expectedCode, exception.code(), "goal spec failure code");
		}
	}

	private static void assertEquals(Object expected, Object actual, String label) {
		if (!java.util.Objects.equals(expected, actual)) {
			throw new AssertionError(label + ": expected=" + expected + ", actual=" + actual);
		}
		System.out.println("PASS: " + label);
	}
}
