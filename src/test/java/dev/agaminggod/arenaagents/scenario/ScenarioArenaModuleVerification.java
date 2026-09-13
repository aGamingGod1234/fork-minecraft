package dev.agaminggod.arenaagents.scenario;

import dev.agaminggod.arenaagents.scenario.runtime.map.ScenarioArenaAnchor;
import dev.agaminggod.arenaagents.scenario.runtime.map.ScenarioArenaModule;
import dev.agaminggod.arenaagents.scenario.runtime.map.ScenarioArenaModuleCodec;
import dev.agaminggod.arenaagents.scenario.runtime.map.ScenarioArenaModuleLoader;
import dev.agaminggod.arenaagents.scenario.runtime.map.ScenarioArenaModuleTransform;
import dev.agaminggod.arenaagents.scenario.runtime.map.ScenarioArenaPlan;
import dev.agaminggod.arenaagents.scenario.runtime.map.ScenarioArenaComposer;
import dev.agaminggod.arenaagents.scenario.runtime.map.ScenarioArenaReachability;
import dev.agaminggod.arenaagents.scenario.runtime.map.ScenarioArenaSafetyPolicy;
import dev.agaminggod.arenaagents.scenario.runtime.map.ScenarioModuleHasher;
import dev.agaminggod.arenaagents.scenario.runtime.ScenarioArenaBlueprint;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

public final class ScenarioArenaModuleVerification {
	private static final String RESOURCE_PATH = "fixtures/tiny-owned-room-v1.json";
	private static final String RESOURCE_PREFIX = "data/arenaagents/arena_modules/";
	private static final String GEOMETRY_HASH = "22f97084ac1f7e5eda56cd47a405e29d058996d44450d8da02d55c7310c97cc3";
	private static final String FIXTURE = "{\"allowedTransforms\":[\"identity\",\"rotate_90\",\"rotate_180\",\"rotate_270\"],\"anchors\":[{\"id\":\"spawn-1\",\"position\":[0,1,0],\"type\":\"spawn\"},{\"id\":\"goal-1\",\"position\":[1,1,1],\"type\":\"goal\"}],\"bounds\":{\"max\":[1,1,1],\"min\":[0,0,0]},\"containerPolicy\":\"none\",\"difficulty\":1,\"geometrySha256\":\"" + GEOMETRY_HASH + "\",\"id\":\"tiny-owned-room\",\"palette\":[{\"id\":\"minecraft:oak_planks\",\"properties\":{}},{\"id\":\"minecraft:stone\",\"properties\":{}}],\"placements\":[{\"state\":1,\"x\":0,\"y\":0,\"z\":0},{\"state\":1,\"x\":0,\"y\":0,\"z\":1},{\"state\":1,\"x\":1,\"y\":0,\"z\":0},{\"state\":1,\"x\":1,\"y\":0,\"z\":1},{\"state\":0,\"x\":1,\"y\":1,\"z\":1}],\"schemaVersion\":1,\"sourceKey\":\"project-owned-fixtures\",\"spectatorPolicy\":\"separated\",\"version\":1}";
	private static final String STAIRS_FIXTURE = "{\"allowedTransforms\":[\"identity\"],\"anchors\":[],\"bounds\":{\"max\":[0,0,0],\"min\":[0,0,0]},\"containerPolicy\":\"none\",\"difficulty\":1,\"geometrySha256\":\"76c884eda3b74a03ff1451841f034c2e6a815d626558ff4264ab9a82d7f51cab\",\"id\":\"stairs\",\"palette\":[{\"id\":\"minecraft:oak_stairs\",\"properties\":{\"facing\":\"north\",\"half\":\"bottom\",\"shape\":\"straight\",\"waterlogged\":\"false\"}}],\"placements\":[{\"state\":0,\"x\":0,\"y\":0,\"z\":0}],\"schemaVersion\":1,\"sourceKey\":\"project-owned-fixtures\",\"spectatorPolicy\":\"none\",\"version\":1}";

	private ScenarioArenaModuleVerification() {
	}

	public static void main(String[] arguments) throws IOException {
		System.out.println("PASS: " + verify() + " arena module assertions");
	}

	public static int verify() throws IOException {
		net.minecraft.SharedConstants.tryDetectVersion();
		net.minecraft.server.Bootstrap.bootStrap();
		verifyFixtureAndHash();
		verifyStrictRootAndNumbers();
		verifyRegistryStateReconstruction();
		verifyPaletteAndPlacements();
		verifyAnchorsTransformsAndPolicies();
		verifyContainedLoader();
		verifyImmutability();
		verifyTransformsAndComposition();
		verifyCompositionConflictsAndHash();
		verifySafetyCapsAndContent();
		verifySpawnReachabilityAndSpectators();
		return 81;
	}

	private static void verifyTransformsAndComposition() {
		ScenarioArenaModule module = module(
				"turn-room", 1, new ScenarioArenaModule.Bounds(new BlockPos(0, 0, 0), new BlockPos(2, 2, 1)),
				List.of(
						new ScenarioArenaModule.Placement(new BlockPos(0, 0, 0), Blocks.STONE.defaultBlockState()),
						new ScenarioArenaModule.Placement(new BlockPos(2, 1, 1), Blocks.OAK_STAIRS.defaultBlockState())
				),
				List.of(new ScenarioArenaAnchor("spawn-1", ScenarioArenaAnchor.Type.SPAWN, new BlockPos(0, 1, 0))),
				Set.of(ScenarioArenaModule.AllowedTransform.ROTATE_90),
				ScenarioArenaModule.ContainerPolicy.NONE, ScenarioArenaModule.SpectatorPolicy.NONE
		);
		assertEquals(new BlockPos(0, 1, 2), ScenarioArenaModuleTransform.transformPosition(
				new BlockPos(2, 1, 1), module.bounds(), ScenarioArenaModule.AllowedTransform.ROTATE_90),
				"rotate 90 transforms a non-square local bound clockwise");
		assertEquals(Rotation.CLOCKWISE_90.rotate(net.minecraft.core.Direction.NORTH),
				ScenarioArenaModuleTransform.transformState(Blocks.OAK_STAIRS.defaultBlockState(),
						ScenarioArenaModule.AllowedTransform.ROTATE_90)
						.getValue(net.minecraft.world.level.block.state.properties.BlockStateProperties.HORIZONTAL_FACING),
				"rotate 90 transforms directional block states");

		ScenarioArenaPlan plan = new ScenarioArenaPlan("transform-plan", List.of(
				new ScenarioArenaPlan.ModulePlacement("stage-1", module,
						ScenarioArenaModule.AllowedTransform.ROTATE_90, new BlockPos(4, 0, 6))
		), Set.of());
		ScenarioArenaBlueprint blueprint = ScenarioArenaComposer.compose(plan, new BlockPos(10, 64, 20));
		assertEquals(List.of(new BlockPos(14, 65, 28), new BlockPos(15, 64, 26)),
				blueprint.placements().stream().map(ScenarioArenaBlueprint.Placement::position).toList(),
				"composer emits placements in stable numeric coordinate order");
		assertEquals(new BlockPos(15, 65, 26), ScenarioArenaComposer.anchors(blueprint).getFirst().position(),
				"composer transforms anchors with geometry");
		assertEquals(blueprint.placements(), ScenarioArenaComposer.compose(plan, new BlockPos(10, 64, 20)).placements(),
				"fixed plan and origin compose identically");
	}

	private static void verifyCompositionConflictsAndHash() {
		ScenarioArenaModule first = singleBlockModule("first", 1, Blocks.STONE.defaultBlockState());
		ScenarioArenaModule identical = singleBlockModule("identical", 1, Blocks.STONE.defaultBlockState());
		ScenarioArenaPlan allowed = plan("overlap", first, identical, new BlockPos(0, 0, 0));
		assertEquals(1, ScenarioArenaComposer.compose(allowed, BlockPos.ZERO).placements().size(),
				"identical overlaps are deduplicated");

		ScenarioArenaModule conflicting = singleBlockModule("conflict", 1, Blocks.DIRT.defaultBlockState());
		assertThrows(IllegalArgumentException.class,
				() -> ScenarioArenaComposer.compose(plan("overlap", first, conflicting, BlockPos.ZERO), BlockPos.ZERO),
				"different states at one coordinate are rejected");

		ScenarioArenaBlueprint versionOne = ScenarioArenaComposer.compose(
				new ScenarioArenaPlan("hash-plan", List.of(new ScenarioArenaPlan.ModulePlacement(
						"stage", first, ScenarioArenaModule.AllowedTransform.IDENTITY, BlockPos.ZERO)), Set.of()), BlockPos.ZERO);
		ScenarioArenaModule versionTwoModule = singleBlockModule("first", 2, Blocks.STONE.defaultBlockState());
		ScenarioArenaBlueprint versionTwo = ScenarioArenaComposer.compose(
				new ScenarioArenaPlan("hash-plan", List.of(new ScenarioArenaPlan.ModulePlacement(
						"stage", versionTwoModule, ScenarioArenaModule.AllowedTransform.IDENTITY, BlockPos.ZERO)), Set.of()), BlockPos.ZERO);
		assertTrue(!ScenarioArenaComposer.compositionHash(versionOne)
				.equals(ScenarioArenaComposer.compositionHash(versionTwo)), "module version participates in composition hash");
		ScenarioArenaModule otherSource = new ScenarioArenaModule(
				first.id(), first.version(), "another-source", first.difficulty(), first.bounds(), first.allowedTransforms(),
				first.containerPolicy(), first.spectatorPolicy(), first.placements(), first.anchors(), first.geometrySha256());
		ScenarioArenaBlueprint sourceChanged = ScenarioArenaComposer.compose(
				new ScenarioArenaPlan("hash-plan", List.of(new ScenarioArenaPlan.ModulePlacement(
						"stage", otherSource, ScenarioArenaModule.AllowedTransform.IDENTITY, BlockPos.ZERO)), Set.of()), BlockPos.ZERO);
		assertTrue(!ScenarioArenaComposer.compositionHash(versionOne)
				.equals(ScenarioArenaComposer.compositionHash(sourceChanged)), "module source participates in composition hash");
	}

	private static void verifySafetyCapsAndContent() {
		ScenarioArenaBlueprint exact = ScenarioArenaBlueprint.fromPlacements(BlockPos.ZERO, List.of(
				new ScenarioArenaBlueprint.Placement(BlockPos.ZERO, Blocks.STONE.defaultBlockState()),
				new ScenarioArenaBlueprint.Placement(new BlockPos(191, 63, 191), Blocks.STONE.defaultBlockState())
		));
		ScenarioArenaSafetyPolicy.Report exactReport = ScenarioArenaSafetyPolicy.validate(exact, 1);
		assertTrue(!exactReport.hasCode("FOOTPRINT_CAP"), "192 by 64 by 192 is inside the exact footprint cap");
		assertTrue(!exactReport.hasCode("CLEAR_VOLUME_CAP"), "2359296 cells are inside the exact clear-volume cap");
		ScenarioArenaBlueprint oversized = ScenarioArenaBlueprint.fromPlacements(BlockPos.ZERO, List.of(
				new ScenarioArenaBlueprint.Placement(BlockPos.ZERO, Blocks.STONE.defaultBlockState()),
				new ScenarioArenaBlueprint.Placement(new BlockPos(192, 63, 191), Blocks.STONE.defaultBlockState())
		));
		ScenarioArenaSafetyPolicy.Report oversizedReport = ScenarioArenaSafetyPolicy.validate(oversized, 1);
		assertTrue(oversizedReport.hasCode("FOOTPRINT_CAP"), "193-block X span exceeds the exact footprint cap");
		assertTrue(oversizedReport.hasCode("CLEAR_VOLUME_CAP"), "clear volume above 2359296 is rejected");
		ScenarioArenaSafetyPolicy.Report extremeReport = ScenarioArenaSafetyPolicy.validate(
				ScenarioArenaBlueprint.fromPlacements(BlockPos.ZERO, List.of(
						new ScenarioArenaBlueprint.Placement(
								new BlockPos(Integer.MIN_VALUE, 0, Integer.MIN_VALUE),
								Blocks.STONE.defaultBlockState()),
						new ScenarioArenaBlueprint.Placement(
								new BlockPos(Integer.MAX_VALUE, 0, Integer.MAX_VALUE),
								Blocks.STONE.defaultBlockState())
				)), 1);
		assertTrue(extremeReport.hasCode("CLEAR_VOLUME_CAP"),
				"extreme coordinates return a bounded cap finding instead of overflowing");
		assertTrue(ScenarioArenaSafetyPolicy.validate(ScenarioArenaBlueprint.fromPlacements(BlockPos.ZERO, List.of(
				new ScenarioArenaBlueprint.Placement(BlockPos.ZERO, Blocks.STONE.defaultBlockState()),
				new ScenarioArenaBlueprint.Placement(new BlockPos(0, 64, 0), Blocks.STONE.defaultBlockState())
		)), 1).hasCode("FOOTPRINT_CAP"), "65-block Y span exceeds the exact footprint cap");
		assertTrue(ScenarioArenaSafetyPolicy.validate(ScenarioArenaBlueprint.fromPlacements(BlockPos.ZERO, List.of(
				new ScenarioArenaBlueprint.Placement(BlockPos.ZERO, Blocks.STONE.defaultBlockState()),
				new ScenarioArenaBlueprint.Placement(new BlockPos(0, 0, 192), Blocks.STONE.defaultBlockState())
		)), 1).hasCode("FOOTPRINT_CAP"), "193-block Z span exceeds the exact footprint cap");

		ScenarioArenaBlueprint tooManyPlacements = ScenarioArenaBlueprint.fromPlacements(BlockPos.ZERO,
				Collections.nCopies(400_001,
						new ScenarioArenaBlueprint.Placement(BlockPos.ZERO, Blocks.STONE.defaultBlockState())));
		assertTrue(ScenarioArenaSafetyPolicy.validate(tooManyPlacements, 1).hasCode("PLACEMENT_CAP"),
				"400001 authored placements are rejected");
		assertTrue(ScenarioArenaSafetyPolicy.validate(blueprintOf(65, Blocks.CHEST), 1).hasCode("CONTAINER_CAP"),
				"65 containers exceed the exact cap");
		assertTrue(ScenarioArenaSafetyPolicy.validate(blueprintOf(32_769, Blocks.WATER), 1).hasCode("FLUID_CAP"),
				"32769 fluid placements exceed the exact cap");
		assertTrue(ScenarioArenaSafetyPolicy.validate(chunkSpanningBlueprint(197), 1).hasCode("LOADED_CHUNK_CAP"),
				"197 authored chunks exceed the exact cap");
		assertTrue(ScenarioArenaSafetyPolicy.validate(
				ScenarioArenaComposer.compose(sparseBoundsPlan(192), BlockPos.ZERO), 1).hasCode("FOOTPRINT_CAP"),
				"declared transformed bounds cap sparse composed modules");
		assertTrue(ScenarioArenaSafetyPolicy.validate(blueprintOf(1, Blocks.COMMAND_BLOCK), 1).hasCode("PROHIBITED_BLOCK"),
				"runtime-prohibited control blocks are reported");
		assertTrue(ScenarioArenaSafetyPolicy.validate(blueprintOf(300, Blocks.COMMAND_BLOCK), 1).findings().size()
				<= ScenarioArenaSafetyPolicy.MAXIMUM_FINDINGS, "validation findings are bounded");
	}

	private static void verifySpawnReachabilityAndSpectators() {
		ScenarioArenaBlueprint safe = ScenarioArenaComposer.compose(safetyPlan(false, false, false, 20), BlockPos.ZERO);
		assertTrue(ScenarioArenaSafetyPolicy.validate(safe, 1).valid(),
				"clear spawn, reachable goal and separated spectator are valid");
		assertTrue(ScenarioArenaReachability.canReachRequiredObjectives(safe),
				"required goal is reachable from a spawn");

		ScenarioArenaBlueprint blockedFeet = ScenarioArenaComposer.compose(safetyPlan(true, false, false, 20), BlockPos.ZERO);
		assertTrue(ScenarioArenaSafetyPolicy.validate(blockedFeet, 1).hasCode("SPAWN_FEET_BLOCKED"),
				"spawn feet must be clear");
		ScenarioArenaBlueprint blockedHead = ScenarioArenaComposer.compose(safetyPlan(false, true, false, 20), BlockPos.ZERO);
		assertTrue(ScenarioArenaSafetyPolicy.validate(blockedHead, 1).hasCode("SPAWN_HEAD_BLOCKED"),
				"spawn head must be clear");
		ScenarioArenaBlueprint missingFloor = ScenarioArenaComposer.compose(safetyPlan(false, false, true, 20), BlockPos.ZERO);
		assertTrue(ScenarioArenaSafetyPolicy.validate(missingFloor, 1).hasCode("SPAWN_FLOOR_MISSING"),
				"spawn requires a floor");
		ScenarioArenaBlueprint unreachable = ScenarioArenaComposer.compose(unreachablePlan(), BlockPos.ZERO);
		assertTrue(ScenarioArenaSafetyPolicy.validate(unreachable, 1).hasCode("OBJECTIVE_UNREACHABLE"),
				"a sealed required objective is rejected");
		ScenarioArenaBlueprint closeSpectator = ScenarioArenaComposer.compose(safetyPlan(false, false, false, 11), BlockPos.ZERO);
		assertTrue(ScenarioArenaSafetyPolicy.validate(closeSpectator, 1).hasCode("SPECTATOR_SEPARATION"),
				"spectator anchors must remain at least 12 blocks from play anchors");
		assertTrue(!ScenarioArenaSafetyPolicy.validate(
				ScenarioArenaComposer.compose(extremeSpectatorPlan(), BlockPos.ZERO), 1)
				.hasCode("SPECTATOR_SEPARATION"),
				"extreme coordinate separation cannot overflow into a false proximity finding");
		assertThrows(IllegalArgumentException.class, () -> ScenarioArenaSafetyPolicy.validate(safe, 17),
				"participant count is bounded to the supported roster");
	}

	private static void verifyFixtureAndHash() throws IOException {
		ScenarioArenaModule module = ScenarioArenaModuleLoader.load(RESOURCE_PATH);
		assertEquals("tiny-owned-room", module.id(), "fixture id");
		assertEquals(1, module.version(), "fixture version");
		assertEquals("project-owned-fixtures", module.sourceKey(), "fixture source");
		assertEquals(1, module.difficulty(), "fixture difficulty");
		assertEquals(new BlockPos(0, 0, 0), module.bounds().minimum(), "fixture minimum");
		assertEquals(new BlockPos(1, 1, 1), module.bounds().maximum(), "fixture maximum");
		assertEquals(5, module.placements().size(), "fixture placement count");
		assertEquals(Blocks.STONE, module.placements().getFirst().state().getBlock(), "first decoded block");
		assertEquals(Blocks.OAK_PLANKS, module.placements().getLast().state().getBlock(), "last decoded block");
		assertEquals(List.of("spawn-1", "goal-1"), module.anchors().stream().map(ScenarioArenaAnchor::id).toList(),
				"fixture anchor order");
		assertEquals(GEOMETRY_HASH, module.geometrySha256(), "fixture geometry hash");
		assertEquals(GEOMETRY_HASH, ScenarioModuleHasher.sha256(module.placements()), "Java hash matches Python");
		List<ScenarioArenaModule.Placement> shuffled = new ArrayList<>(module.placements());
		Collections.reverse(shuffled);
		assertEquals(GEOMETRY_HASH, ScenarioModuleHasher.sha256(shuffled), "hash sorts placements numerically");
		assertEquals("minecraft:stone", ScenarioModuleHasher.canonicalState(Blocks.STONE.defaultBlockState()),
				"canonical state without properties");
		try (InputStream stream = ScenarioArenaModuleVerification.class.getClassLoader()
				.getResourceAsStream(RESOURCE_PREFIX + RESOURCE_PATH)) {
			assertTrue(stream != null, "fixture resource exists");
			assertEquals(FIXTURE + "\n",
					new String(stream.readAllBytes(), StandardCharsets.UTF_8).replace("\r\n", "\n"),
					"resource bytes exactly match Task 2 output");
		}
	}

	private static void verifyStrictRootAndNumbers() {
		reject(FIXTURE.replaceFirst("\\}$", ",\"unexpected\":true}"), "unknown root field");
		reject(FIXTURE.replace("\"difficulty\":1,", ""), "missing root field");
		reject(FIXTURE.replace("\"version\":1", "\"version\":1,\"version\":1"), "duplicate JSON field");
		reject(FIXTURE.replace("\"schemaVersion\":1", "\"schemaVersion\":1.0"), "decimal schema version");
		reject(FIXTURE.replace("\"version\":1", "\"version\":true"), "boolean version");
		reject(FIXTURE.replace("\"difficulty\":1", "\"difficulty\":6"), "difficulty range");
		reject(FIXTURE.replace("\"max\":[1,1,1]", "\"max\":[192,1,1]"), "bounds cap");
		reject(FIXTURE.replace("\"min\":[0,0,0]", "\"min\":[1,0,0]"), "bounds must start at zero");
		reject(FIXTURE.replace("\"max\":[1,1,1]", "\"max\":[-1,1,1]"), "inverted bounds");
		reject(FIXTURE.replace("\"id\":\"tiny-owned-room\"", "\"id\":\"Bad ID\""), "module id format");
		reject(FIXTURE.replace("\"sourceKey\":\"project-owned-fixtures\"", "\"sourceKey\":\"../source\""),
				"source key format");
	}

	private static void verifyRegistryStateReconstruction() {
		ScenarioArenaModule stairs = ScenarioArenaModuleCodec.decode(STAIRS_FIXTURE);
		assertEquals(
				"minecraft:oak_stairs[facing=north,half=bottom,shape=straight,waterlogged=false]",
				ScenarioModuleHasher.canonicalState(stairs.placements().getFirst().state()),
				"complete authored properties reconstruct the exact runtime state");
		reject(FIXTURE.replace("minecraft:oak_planks", "minecraft:not_real"), "unknown block id");
		reject(FIXTURE.replace("minecraft:oak_planks", "arenaagents:stone"), "non-Minecraft block id");
		reject(FIXTURE.replace("{\"id\":\"minecraft:oak_planks\",\"properties\":{}}",
				"{\"id\":\"minecraft:oak_stairs\",\"properties\":{\"facing\":\"north\"}}"),
				"missing state properties");
		reject(FIXTURE.replace("{\"id\":\"minecraft:stone\",\"properties\":{}}",
				"{\"id\":\"minecraft:stone\",\"properties\":{\"extra\":\"true\"}}"),
				"extra state property");
		reject(FIXTURE.replace("{\"id\":\"minecraft:oak_planks\",\"properties\":{}}",
				"{\"id\":\"minecraft:oak_stairs\",\"properties\":{\"facing\":\"up\",\"half\":\"bottom\",\"shape\":\"straight\",\"waterlogged\":\"false\"}}"),
				"invalid state property value");
		for (String forbidden : List.of("command_block", "repeating_command_block", "chain_command_block",
				"structure_block", "jigsaw")) {
			reject(FIXTURE.replace("minecraft:oak_planks", "minecraft:" + forbidden), "forbidden control block " + forbidden);
		}
	}

	private static void verifyPaletteAndPlacements() {
		String first = "{\"id\":\"minecraft:oak_planks\",\"properties\":{}}";
		String second = "{\"id\":\"minecraft:stone\",\"properties\":{}}";
		reject(FIXTURE.replace(first + "," + second, second + "," + first), "unsorted palette");
		reject(FIXTURE.replace(first, second), "duplicate palette");
		reject(FIXTURE.replace("{\"state\":1,\"x\":0,\"y\":0,\"z\":0},{\"state\":1,\"x\":0,\"y\":0,\"z\":1}",
				"{\"state\":1,\"x\":0,\"y\":0,\"z\":1},{\"state\":1,\"x\":0,\"y\":0,\"z\":0}"),
				"unsorted placements");
		reject(FIXTURE.replaceFirst("\"state\":1", "\"state\":2"), "bad palette index");
		reject(FIXTURE.replace("{\"state\":1,\"x\":0,\"y\":0,\"z\":1}",
				"{\"state\":1,\"x\":0,\"y\":0,\"z\":0}"), "duplicate placement");
		reject(FIXTURE.replaceFirst("\"x\":0,\"y\":0,\"z\":0", "\"x\":2,\"y\":0,\"z\":0"),
				"out-of-bounds placement");
		reject(FIXTURE.replaceFirst("\"state\":1", "\"state\":1,\"command\":\"say no\""),
				"unrepresentable placement content");
		reject(FIXTURE.replace(GEOMETRY_HASH, "bad"), "malformed hash");
		reject(FIXTURE.replace(GEOMETRY_HASH, "0".repeat(64)), "mismatched hash");
	}

	private static void verifyAnchorsTransformsAndPolicies() {
		reject(FIXTURE.replace("\"id\":\"goal-1\"", "\"id\":\"spawn-1\""), "duplicate anchor id");
		reject(FIXTURE.replace("\"position\":[1,1,1],\"type\":\"goal\"",
				"\"position\":[0,1,0],\"type\":\"goal\""), "duplicate anchor position");
		reject(FIXTURE.replace("\"position\":[1,1,1],\"type\":\"goal\"",
				"\"position\":[2,1,1],\"type\":\"goal\""), "out-of-bounds anchor");
		reject(FIXTURE.replace("\"type\":\"goal\"", "\"type\":\"unknown\""), "unknown anchor type");
		reject(FIXTURE.replace("\"rotate_90\"", "\"warp\""), "unknown transform");
		reject(FIXTURE.replace("\"identity\",\"rotate_90\"", "\"identity\",\"identity\",\"rotate_90\""),
				"duplicate transform");
		reject(FIXTURE.replace("\"identity\",\"rotate_90\"", "\"rotate_90\",\"identity\""),
				"noncanonical transform order");
		reject(FIXTURE.replace("\"containerPolicy\":\"none\"", "\"containerPolicy\":\"all\""),
				"invalid container policy");
		reject(FIXTURE.replace("\"spectatorPolicy\":\"separated\"", "\"spectatorPolicy\":\"nearby\""),
				"invalid spectator policy");
	}

	private static void verifyContainedLoader() {
		for (String path : List.of("../tiny-owned-room-v1.json", "fixtures\\tiny-owned-room-v1.json",
				"/fixtures/tiny-owned-room-v1.json", "fixtures/missing-v1.json")) {
			assertThrows(IllegalArgumentException.class, () -> ScenarioArenaModuleLoader.load(path),
					"loader rejects uncontained or missing path " + path);
		}
		ClassLoader original = Thread.currentThread().getContextClassLoader();
		try {
			Thread.currentThread().setContextClassLoader(resourceLoader(
					RESOURCE_PREFIX + "fixtures/mismatch-v2.json", FIXTURE.getBytes(StandardCharsets.UTF_8)));
			assertThrows(IllegalArgumentException.class,
					() -> ScenarioArenaModuleLoader.load("fixtures/mismatch-v2.json"),
					"filename version must match module version");
			Thread.currentThread().setContextClassLoader(resourceLoader(
					RESOURCE_PREFIX + "fixtures/oversized-v1.json", new byte[8 * 1024 * 1024 + 1]));
			assertThrows(IllegalArgumentException.class,
					() -> ScenarioArenaModuleLoader.load("fixtures/oversized-v1.json"),
					"loader rejects oversized resource");
		} finally {
			Thread.currentThread().setContextClassLoader(original);
		}
	}

	private static void verifyImmutability() {
		ScenarioArenaModule module = ScenarioArenaModuleCodec.decode(FIXTURE);
		assertThrows(UnsupportedOperationException.class,
				() -> module.placements().add(module.placements().getFirst()), "placements are immutable");
		assertThrows(UnsupportedOperationException.class,
				() -> module.anchors().add(module.anchors().getFirst()), "anchors are immutable");
		assertThrows(UnsupportedOperationException.class,
				() -> module.allowedTransforms().add(ScenarioArenaModule.AllowedTransform.MIRROR_X),
				"transforms are immutable");
		assertThrows(IllegalArgumentException.class,
				() -> ScenarioModuleHasher.sha256(List.of(
						module.placements().getFirst(), module.placements().getFirst())),
				"hasher rejects duplicate coordinates");
	}

	private static ScenarioArenaPlan safetyPlan(
			boolean blockSpawnFeet,
			boolean blockSpawnHead,
			boolean omitSpawnFloor,
			int cameraX
	) {
		ArrayList<ScenarioArenaModule.Placement> placements = new ArrayList<>();
		for (int x = 0; x <= cameraX; x++) {
			if (!omitSpawnFloor || x != 0) {
				placements.add(new ScenarioArenaModule.Placement(new BlockPos(x, 0, 0), Blocks.STONE.defaultBlockState()));
			}
		}
		if (blockSpawnFeet) {
			placements.add(new ScenarioArenaModule.Placement(new BlockPos(0, 1, 0), Blocks.STONE.defaultBlockState()));
		}
		if (blockSpawnHead) {
			placements.add(new ScenarioArenaModule.Placement(new BlockPos(0, 2, 0), Blocks.STONE.defaultBlockState()));
		}
		placements.sort(java.util.Comparator
				.comparingInt((ScenarioArenaModule.Placement placement) -> placement.position().getX())
				.thenComparingInt(placement -> placement.position().getY())
				.thenComparingInt(placement -> placement.position().getZ()));
		ScenarioArenaModule module = module(
				"safe-room", 1,
				new ScenarioArenaModule.Bounds(BlockPos.ZERO, new BlockPos(cameraX, 2, 0)), placements,
				List.of(
						new ScenarioArenaAnchor("spawn-1", ScenarioArenaAnchor.Type.SPAWN, new BlockPos(0, 1, 0)),
						new ScenarioArenaAnchor("goal-1", ScenarioArenaAnchor.Type.GOAL, new BlockPos(3, 1, 0)),
						new ScenarioArenaAnchor("camera-1", ScenarioArenaAnchor.Type.CAMERA, new BlockPos(cameraX, 1, 0))
				),
				Set.of(ScenarioArenaModule.AllowedTransform.IDENTITY),
				ScenarioArenaModule.ContainerPolicy.NONE, ScenarioArenaModule.SpectatorPolicy.SEPARATED
		);
		return new ScenarioArenaPlan("safety-plan", List.of(new ScenarioArenaPlan.ModulePlacement(
				"stage", module, ScenarioArenaModule.AllowedTransform.IDENTITY, BlockPos.ZERO)), Set.of("stage-goal-1"));
	}

	private static ScenarioArenaPlan unreachablePlan() {
		ArrayList<ScenarioArenaModule.Placement> placements = new ArrayList<>();
		for (int x = 0; x <= 3; x++) {
			placements.add(new ScenarioArenaModule.Placement(new BlockPos(x, 0, 0), Blocks.STONE.defaultBlockState()));
		}
		placements.add(new ScenarioArenaModule.Placement(new BlockPos(1, 1, 0), Blocks.STONE.defaultBlockState()));
		placements.add(new ScenarioArenaModule.Placement(new BlockPos(1, 2, 0), Blocks.STONE.defaultBlockState()));
		placements.sort(java.util.Comparator
				.comparingInt((ScenarioArenaModule.Placement placement) -> placement.position().getX())
				.thenComparingInt(placement -> placement.position().getY())
				.thenComparingInt(placement -> placement.position().getZ()));
		ScenarioArenaModule module = module(
				"sealed-room", 1, new ScenarioArenaModule.Bounds(BlockPos.ZERO, new BlockPos(3, 2, 0)), placements,
				List.of(
						new ScenarioArenaAnchor("spawn-1", ScenarioArenaAnchor.Type.SPAWN, new BlockPos(0, 1, 0)),
						new ScenarioArenaAnchor("goal-1", ScenarioArenaAnchor.Type.GOAL, new BlockPos(3, 1, 0))
				), Set.of(ScenarioArenaModule.AllowedTransform.IDENTITY),
				ScenarioArenaModule.ContainerPolicy.NONE, ScenarioArenaModule.SpectatorPolicy.NONE
		);
		return new ScenarioArenaPlan("sealed-plan", List.of(new ScenarioArenaPlan.ModulePlacement(
				"stage", module, ScenarioArenaModule.AllowedTransform.IDENTITY, BlockPos.ZERO)), Set.of("stage-goal-1"));
	}

	private static ScenarioArenaPlan extremeSpectatorPlan() {
		ScenarioArenaModule spawn = module(
				"spawn-room", 1, new ScenarioArenaModule.Bounds(BlockPos.ZERO, new BlockPos(0, 1, 0)),
				List.of(new ScenarioArenaModule.Placement(BlockPos.ZERO, Blocks.STONE.defaultBlockState())),
				List.of(new ScenarioArenaAnchor("spawn-1", ScenarioArenaAnchor.Type.SPAWN, new BlockPos(0, 1, 0))),
				Set.of(ScenarioArenaModule.AllowedTransform.IDENTITY),
				ScenarioArenaModule.ContainerPolicy.NONE, ScenarioArenaModule.SpectatorPolicy.NONE);
		ScenarioArenaModule camera = module(
				"camera-room", 1, new ScenarioArenaModule.Bounds(BlockPos.ZERO, new BlockPos(0, 1, 0)),
				List.of(new ScenarioArenaModule.Placement(BlockPos.ZERO, Blocks.STONE.defaultBlockState())),
				List.of(new ScenarioArenaAnchor("camera-1", ScenarioArenaAnchor.Type.CAMERA, new BlockPos(0, 1, 0))),
				Set.of(ScenarioArenaModule.AllowedTransform.IDENTITY),
				ScenarioArenaModule.ContainerPolicy.NONE, ScenarioArenaModule.SpectatorPolicy.SEPARATED);
		return new ScenarioArenaPlan("extreme-spectator", List.of(
				new ScenarioArenaPlan.ModulePlacement("spawn", spawn,
						ScenarioArenaModule.AllowedTransform.IDENTITY, new BlockPos(Integer.MIN_VALUE, 0, 0)),
				new ScenarioArenaPlan.ModulePlacement("camera", camera,
						ScenarioArenaModule.AllowedTransform.IDENTITY, new BlockPos(Integer.MAX_VALUE, 0, 0))
		), Set.of());
	}

	private static ScenarioArenaPlan sparseBoundsPlan(int maximumX) {
		ScenarioArenaModule module = module(
				"sparse-room", 1, new ScenarioArenaModule.Bounds(BlockPos.ZERO, new BlockPos(maximumX, 0, 0)),
				List.of(new ScenarioArenaModule.Placement(BlockPos.ZERO, Blocks.STONE.defaultBlockState())), List.of(),
				Set.of(ScenarioArenaModule.AllowedTransform.IDENTITY),
				ScenarioArenaModule.ContainerPolicy.NONE, ScenarioArenaModule.SpectatorPolicy.NONE);
		return new ScenarioArenaPlan("sparse-plan", List.of(new ScenarioArenaPlan.ModulePlacement(
				"stage", module, ScenarioArenaModule.AllowedTransform.IDENTITY, BlockPos.ZERO)), Set.of());
	}

	private static ScenarioArenaPlan plan(
			String id,
			ScenarioArenaModule first,
			ScenarioArenaModule second,
			BlockPos offset
	) {
		return new ScenarioArenaPlan(id, List.of(
				new ScenarioArenaPlan.ModulePlacement("first", first,
						ScenarioArenaModule.AllowedTransform.IDENTITY, offset),
				new ScenarioArenaPlan.ModulePlacement("second", second,
						ScenarioArenaModule.AllowedTransform.IDENTITY, offset)
		), Set.of());
	}

	private static ScenarioArenaModule singleBlockModule(String id, int version, BlockState state) {
		return module(id, version,
				new ScenarioArenaModule.Bounds(BlockPos.ZERO, BlockPos.ZERO),
				List.of(new ScenarioArenaModule.Placement(BlockPos.ZERO, state)), List.of(),
				Set.of(ScenarioArenaModule.AllowedTransform.IDENTITY),
				ScenarioArenaModule.ContainerPolicy.NONE, ScenarioArenaModule.SpectatorPolicy.NONE);
	}

	private static ScenarioArenaModule module(
			String id,
			int version,
			ScenarioArenaModule.Bounds bounds,
			List<ScenarioArenaModule.Placement> placements,
			List<ScenarioArenaAnchor> anchors,
			Set<ScenarioArenaModule.AllowedTransform> transforms,
			ScenarioArenaModule.ContainerPolicy containerPolicy,
			ScenarioArenaModule.SpectatorPolicy spectatorPolicy
	) {
		return new ScenarioArenaModule(
				id, version, "project-owned-fixtures", 1, bounds, transforms, containerPolicy, spectatorPolicy,
				placements, anchors, ScenarioModuleHasher.sha256(placements));
	}

	private static ScenarioArenaBlueprint blueprintOf(int count, Block block) {
		ArrayList<ScenarioArenaBlueprint.Placement> placements = new ArrayList<>(count);
		for (int index = 0; index < count; index++) {
			int x = index % 192;
			int y = index / 192 % 64;
			int z = index / (192 * 64);
			placements.add(new ScenarioArenaBlueprint.Placement(new BlockPos(x, y, z), block.defaultBlockState()));
		}
		return ScenarioArenaBlueprint.fromPlacements(BlockPos.ZERO, placements);
	}

	private static ScenarioArenaBlueprint chunkSpanningBlueprint(int count) {
		ArrayList<ScenarioArenaBlueprint.Placement> placements = new ArrayList<>(count);
		for (int index = 0; index < count; index++) {
			placements.add(new ScenarioArenaBlueprint.Placement(
					new BlockPos(index * 16, 0, 0), Blocks.STONE.defaultBlockState()));
		}
		return ScenarioArenaBlueprint.fromPlacements(BlockPos.ZERO, placements);
	}

	private static ClassLoader resourceLoader(String resourceName, byte[] content) {
		return new ClassLoader(ScenarioArenaModuleVerification.class.getClassLoader()) {
			@Override
			public InputStream getResourceAsStream(String name) {
				if (resourceName.equals(name)) return new ByteArrayInputStream(content);
				return super.getResourceAsStream(name);
			}
		};
	}

	private static void reject(String encoded, String label) {
		assertThrows(IllegalArgumentException.class, () -> ScenarioArenaModuleCodec.decode(encoded), label);
	}

	private static void assertTrue(boolean value, String label) {
		if (!value) throw new AssertionError(label);
	}

	private static void assertEquals(Object expected, Object actual, String label) {
		if (!java.util.Objects.equals(expected, actual)) {
			throw new AssertionError(label + ": expected=" + expected + ", actual=" + actual);
		}
	}

	private static <T extends Throwable> void assertThrows(Class<T> type, ThrowingRunnable action, String label) {
		try {
			action.run();
		} catch (Throwable error) {
			if (type.isInstance(error)) return;
			throw new AssertionError(label + ": expected " + type.getSimpleName() + ", got " + error, error);
		}
		throw new AssertionError(label + ": expected " + type.getSimpleName());
	}

	@FunctionalInterface
	private interface ThrowingRunnable {
		void run() throws Exception;
	}
}
