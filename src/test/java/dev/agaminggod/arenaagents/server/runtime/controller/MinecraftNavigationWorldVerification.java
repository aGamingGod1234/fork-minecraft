package dev.agaminggod.arenaagents.server.runtime.controller;

import dev.agaminggod.arenaagents.client.navigation.WalkabilityView;
import dev.agaminggod.arenaagents.world.WorldMutationRevisions;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.EmptyBlockGetter;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.SlabType;

public final class MinecraftNavigationWorldVerification {
	private MinecraftNavigationWorldVerification() {
	}

	public static int verify() {
		assertEquals(WalkabilityView.Cell.SAFE_SUPPORT, classify(Blocks.STONE.defaultBlockState(), false),
				"full blocks remain safe support");
		assertEquals(WalkabilityView.Cell.SAFE_SUPPORT, classify(Blocks.STONE_SLAB.defaultBlockState(), false),
				"slabs are real walkable support");
		assertEquals(WalkabilityView.Cell.SAFE_SUPPORT, classify(Blocks.OAK_STAIRS.defaultBlockState(), false),
				"stairs are real walkable support");
		assertEquals(WalkabilityView.Cell.SAFE_SUPPORT, classify(Blocks.FARMLAND.defaultBlockState(), false),
				"farmland is real walkable support");
		assertEquals(WalkabilityView.Cell.SAFE_SUPPORT, classify(Blocks.DIRT_PATH.defaultBlockState(), false),
				"dirt paths are real walkable support");
		assertEquals(WalkabilityView.Cell.SAFE_SUPPORT, classify(Blocks.WHITE_CARPET.defaultBlockState(), false),
				"carpet support comes from actual collision instead of a block-name allowlist");
		assertEquals(WalkabilityView.Cell.SAFE_SUPPORT, classify(Blocks.SOUL_SAND.defaultBlockState(), false),
				"soul sand supports the player at its actual collision height");

		BlockState closedDoor = Blocks.OAK_DOOR.defaultBlockState().setValue(BlockStateProperties.OPEN, false);
		BlockState openDoor = closedDoor.setValue(BlockStateProperties.OPEN, true);
		assertEquals(WalkabilityView.Cell.BLOCKED, classify(closedDoor, false),
				"closed doors remain collision barriers");
		assertEquals(WalkabilityView.Cell.CLEAR, classify(openDoor, false),
				"open doors are clear navigation space");

		assertEquals(WalkabilityView.Cell.CLEAR, classify(Blocks.WATER.defaultBlockState(), true),
				"bounded one-block-deep water is crossable");
		assertEquals(WalkabilityView.Cell.WATER, classify(Blocks.WATER.defaultBlockState(), false),
				"open water is represented separately so breathing clearance can govern traversal");
		assertEquals(WalkabilityView.Cell.HAZARD, classify(Blocks.LAVA.defaultBlockState(), true),
				"lava never inherits shallow-water traversal");
		assertEquals(WalkabilityView.Cell.HAZARD, classify(Blocks.MAGMA_BLOCK.defaultBlockState(), false),
				"damaging support remains hazardous");
		assertEquals(WalkabilityView.Cell.BLOCKED, classify(Blocks.OAK_FENCE.defaultBlockState(), false),
				"other partial collision such as fences remains blocked");

		assertEquals(0.5D, collisionHeight(Blocks.STONE_SLAB.defaultBlockState(), 0.5D, 0.5D),
				"bottom slabs expose their half-block standing height");
		double lowStairHeight = Double.POSITIVE_INFINITY;
		double highStairHeight = Double.NEGATIVE_INFINITY;
		for (double x : new double[]{0.25D, 0.75D}) {
			for (double z : new double[]{0.25D, 0.75D}) {
				double height = collisionHeight(Blocks.OAK_STAIRS.defaultBlockState(), x, z);
				lowStairHeight = Math.min(lowStairHeight, height);
				highStairHeight = Math.max(highStairHeight, height);
			}
		}
		assertEquals(0.5D, lowStairHeight, "bottom stairs expose their lower collision surface");
		assertEquals(1.0D, highStairHeight, "bottom stairs retain their upper collision surface");
		assertTrue(Double.isNaN(collisionHeight(Blocks.AIR.defaultBlockState(), 0.5D, 0.5D)),
				"empty space never invents support across a hole or cliff");
		assertTrue(Double.isNaN(collisionHeight(Blocks.STONE.defaultBlockState(), 1.0D, 0.5D)),
				"support from the adjacent block cannot complete a waypoint early");
		assertTrue(MinecraftNavigationWorld.crouchClearance(Blocks.STONE_SLAB.defaultBlockState()
				.setValue(BlockStateProperties.SLAB_TYPE, SlabType.TOP)
				.getCollisionShape(EmptyBlockGetter.INSTANCE, BlockPos.ZERO)), "a top slab leaves real clearance for a crouching player");
		assertTrue(!MinecraftNavigationWorld.crouchClearance(Blocks.STONE_SLAB.defaultBlockState()
				.getCollisionShape(EmptyBlockGetter.INSTANCE, BlockPos.ZERO)), "a bottom slab does not invent a crouch passage through solid collision");
		return 21 + verifySampledRevisions();
	}

	private static int verifySampledRevisions() {
		WorldMutationRevisions revisions = new WorldMutationRevisions();
		int[] reads = {0};
		MinecraftNavigationWorld.SampledRevisions snapshot = new MinecraftNavigationWorld.SampledRevisions((center, radius) -> {
			reads[0]++;
			assertEquals(9, radius, "a sampled chunk uses bounded neighboring collision scope");
			return revisions.revision(center.getX(), center.getZ(), radius);
		});
		assertTrue(snapshot.isCurrent(), "an empty terrain snapshot has no stale samples");
		assertEquals(0, reads[0], "an empty snapshot never requests a global revision");
		snapshot.sample(510, 250);
		snapshot.sample(511, 255);
		assertEquals(1, reads[0], "all sampled cells in one chunk share one revision scope");
		assertTrue(snapshot.isCurrent(), "unchanged terrain retains an incremental search");
		revisions.revision(10_000, 10_000, 0);
		revisions.recordMutation(10_000, 10_000);
		assertTrue(snapshot.isCurrent(), "mutations in another tracked region retain an incremental search");
		revisions.recordMutation(512, 250);
		assertTrue(!snapshot.isCurrent(), "neighboring collision changes cross a positive region boundary");
		assertTrue(!snapshot.isCurrent(), "checking an invalid snapshot cannot silently rebase its cached cells");
		snapshot.sample(600, 250);
		assertTrue(!snapshot.isCurrent(), "later samples cannot erase an earlier stale collision scope");

		MinecraftNavigationWorld.SampledRevisions negative = snapshot(revisions);
		negative.sample(-512, -512);
		assertTrue(negative.isCurrent(), "negative chunk coordinates begin current");
		revisions.recordMutation(-513, -513);
		assertTrue(!negative.isCurrent(), "neighboring collision changes cross negative region boundaries");

		MinecraftNavigationWorld.SampledRevisions dynamic = snapshot(revisions);
		dynamic.sample(128, 128);
		BlockState unchanged = Blocks.SHULKER_BOX.defaultBlockState();
		revisions.recordMutation(128, 128);
		assertTrue(unchanged == Blocks.SHULKER_BOX.defaultBlockState(), "dynamic collision need not replace a block state");
		assertTrue(!dynamic.isCurrent(), "a dynamic collision notification invalidates without a block-state comparison");

		MinecraftNavigationWorld.SampledRevisions evicted = snapshot(revisions);
		evicted.sample(0, 0);
		for (int index = 1; index <= 300; index++) revisions.revision(index * 2_048, 0, 0);
		assertTrue(!evicted.isCurrent(), "eviction of regional history cannot revive old navigation cells");

		long[] tick = {1};
		MinecraftNavigationWorld.SampledRevisions fallback = new MinecraftNavigationWorld.SampledRevisions((center, radius) -> tick[0]);
		fallback.sample(0, 0);
		assertTrue(fallback.isCurrent(), "the clock fallback can reuse cells within a tick");
		tick[0]++;
		assertTrue(!fallback.isCurrent(), "the clock fallback expires cells when mutation tracking is absent");
		return 15 + reads[0];
	}

	private static MinecraftNavigationWorld.SampledRevisions snapshot(WorldMutationRevisions revisions) {
		return new MinecraftNavigationWorld.SampledRevisions((center, radius) -> revisions.revision(center.getX(), center.getZ(), radius));
	}

	private static WalkabilityView.Cell classify(BlockState state, boolean boundedShallowWater) {
		return MinecraftNavigationWorld.classifyCell(
				state,
				state.getCollisionShape(EmptyBlockGetter.INSTANCE, BlockPos.ZERO),
				boundedShallowWater
		);
	}

	private static double collisionHeight(BlockState state, double localX, double localZ) {
		return MinecraftNavigationWorld.collisionHeightAt(
				state.getCollisionShape(EmptyBlockGetter.INSTANCE, BlockPos.ZERO),
				localX,
				localZ
		);
	}

	private static void assertEquals(Object expected, Object actual, String label) {
		if (!expected.equals(actual)) {
			throw new AssertionError(label + ": expected <" + expected + "> but was <" + actual + ">");
		}
	}

	private static void assertTrue(boolean condition, String label) {
		if (!condition) throw new AssertionError(label);
	}
}
