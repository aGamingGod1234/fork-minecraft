package dev.agaminggod.arenaagents.server.perception;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Predicate;
import java.util.HashMap;
import java.util.Map;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Blocks;

public final class BlockObservationLazyVisibilityVerification {
	private static final String[] BLOCK_IDS = {
			"minecraft:stone",
			"minecraft:grass_block",
			"minecraft:oak_log",
			"minecraft:coal_ore",
			"minecraft:crafting_table",
			"minecraft:water"
	};

	private BlockObservationLazyVisibilityVerification() {
	}

	public static int verify() {
		int assertions = 0;

		for (long seed = 1L; seed <= 64L; seed++) {
			List<BlockObservationOrdering.Candidate> candidates = candidates(seed);
			Set<BlockObservationOrdering.Candidate> occluded = occluded(candidates, seed);
			Predicate<BlockObservationOrdering.Candidate> visible = candidate -> !occluded.contains(candidate);

			ArrayList<BlockObservationOrdering.Candidate> prefiltered = new ArrayList<>();
			for (BlockObservationOrdering.Candidate candidate : candidates) {
				if (visible.test(candidate)) prefiltered.add(candidate);
			}

			List<BlockObservationOrdering.Candidate> eager = BlockObservationOrdering.select(
					prefiltered, ServerObservationCollector.MAX_BLOCKS,
					ServerObservationCollector.MAX_BLOCKS_PER_TYPE);
			List<BlockObservationOrdering.Candidate> lazy = BlockObservationOrdering.select(
					candidates, ServerObservationCollector.MAX_BLOCKS,
					ServerObservationCollector.MAX_BLOCKS_PER_TYPE, visible);

			assertEquals(eager, lazy, "lazy visibility selection is output-identical (seed " + seed + ")");
			assertEquals(
					lazy,
					BlockObservationOrdering.select(
							candidates.reversed(), ServerObservationCollector.MAX_BLOCKS,
							ServerObservationCollector.MAX_BLOCKS_PER_TYPE, visible),
					"lazy visibility selection is independent of scan order (seed " + seed + ")"
			);
			assertEquals(
					lazy,
					BlockObservationOrdering.selectOrdered(
							BlockObservationOrdering.ordered(candidates),
							ServerObservationCollector.MAX_BLOCKS,
							ServerObservationCollector.MAX_BLOCKS_PER_TYPE,
							visible
					),
					"cached ordered candidates preserve exact selection (seed " + seed + ")"
			);
			assertions += 3;
		}

		List<BlockObservationOrdering.Candidate> saturated = new ArrayList<>();
		for (int x = 1; x <= 40; x++) {
			saturated.add(new BlockObservationOrdering.Candidate(x, 0, 0, "minecraft:stone"));
		}
		saturated.add(new BlockObservationOrdering.Candidate(60, 0, 0, "minecraft:oak_log"));

		AtomicInteger eagerChecks = new AtomicInteger();
		BlockObservationOrdering.select(visibleAll(saturated, eagerChecks), 8, 2);
		AtomicInteger lazyChecks = new AtomicInteger();
		List<BlockObservationOrdering.Candidate> lazy = BlockObservationOrdering.select(
				saturated, 8, 2, candidate -> {
					lazyChecks.incrementAndGet();
					return true;
				});

		assertEquals(3, lazy.size(), "per-type exhaustion does not shorten the global selection");
		assertTrue(
				lazy.getLast().blockId().equals("minecraft:oak_log"),
				"the scan continues past an exhausted per-type quota until the global quota is full"
		);
		assertTrue(
				lazyChecks.get() < eagerChecks.get(),
				"lazy selection tests fewer candidates than pre-filtering: "
						+ lazyChecks.get() + " vs " + eagerChecks.get()
		);
		assertions += 3;

		AtomicInteger boundedChecks = new AtomicInteger();
		BlockObservationOrdering.select(saturated, 2, 8, candidate -> {
			boundedChecks.incrementAndGet();
			return true;
		});
		assertEquals(2, boundedChecks.get(), "a satisfied global quota stops visibility testing immediately");
		assertions += 1;

		ArrayList<SightCandidate> crowded = new ArrayList<>();
		for (int index = 511; index >= 0; index--) crowded.add(new SightCandidate(index, true));
		AtomicInteger lineOfSightChecks = new AtomicInteger();
		List<SightCandidate> visibleEntities = ServerObservationCollector.selectNearestVisible(
				crowded,
				Comparator.comparingInt(SightCandidate::distance),
				ServerObservationCollector.MAX_ENTITIES,
				candidate -> {
					lineOfSightChecks.incrementAndGet();
					return candidate.visible();
				}
		);
		assertEquals(
				java.util.stream.IntStream.range(0, ServerObservationCollector.MAX_ENTITIES)
						.mapToObj(index -> new SightCandidate(index, true)).toList(),
				visibleEntities,
				"nearest-first entity selection preserves the former visible ordering"
		);
		assertEquals(
				ServerObservationCollector.MAX_ENTITIES,
				lineOfSightChecks.get(),
				"crowded observations stop line-of-sight work at the entity limit"
		);
		assertEquals(513, BlockObservationOrdering.ordered(crowdedBlocks()).size(),
				"the raw spatial cache retains candidates beyond the former four-times quota");

		AtomicInteger uniformOccludedChecks = new AtomicInteger();
		BlockObservationOrdering.selectOrderedWithVisibilityBudget(
				BlockObservationOrdering.ordered(crowdedBlocks()),
				ServerObservationCollector.MAX_BLOCKS,
				ServerObservationCollector.MAX_BLOCKS_PER_TYPE,
				ServerObservationCollector.MAX_BLOCK_VISIBILITY_CHECKS,
				ServerObservationCollector.MAX_BLOCK_VISIBILITY_CHECKS_PER_TYPE,
				candidate -> {
					uniformOccludedChecks.incrementAndGet();
					return false;
				});
		assertEquals(ServerObservationCollector.MAX_BLOCK_VISIBILITY_CHECKS_PER_TYPE,
				uniformOccludedChecks.get(),
				"uniform occlusion cannot exceed the per-type raycast budget");

		ArrayList<BlockObservationOrdering.Candidate> diverseOccluded = new ArrayList<>();
		for (int index = 0; index < 600; index++) {
			diverseOccluded.add(new BlockObservationOrdering.Candidate(
					index, 0, 0, "fixture:block_" + index));
		}
		AtomicInteger diverseOccludedChecks = new AtomicInteger();
		BlockObservationOrdering.selectOrderedWithVisibilityBudget(
				BlockObservationOrdering.ordered(diverseOccluded),
				ServerObservationCollector.MAX_BLOCKS,
				ServerObservationCollector.MAX_BLOCKS_PER_TYPE,
				ServerObservationCollector.MAX_BLOCK_VISIBILITY_CHECKS,
				ServerObservationCollector.MAX_BLOCK_VISIBILITY_CHECKS_PER_TYPE,
				candidate -> {
					diverseOccludedChecks.incrementAndGet();
					return false;
				});
		assertEquals(ServerObservationCollector.MAX_BLOCK_VISIBILITY_CHECKS,
				diverseOccludedChecks.get(),
				"diverse occlusion cannot exceed the global raycast budget");

		ArrayList<BlockObservationOrdering.Candidate> coneFiltered = new ArrayList<>();
		for (int index = 0; index < 40; index++) {
			coneFiltered.add(new BlockObservationOrdering.Candidate(
					index + 1, 0, 0, "minecraft:stone"));
		}
		AtomicInteger raycastsAfterCone = new AtomicInteger();
		List<BlockObservationOrdering.Candidate> selectedAfterCone =
				BlockObservationOrdering.selectOrderedWithVisibilityBudget(
						BlockObservationOrdering.ordered(coneFiltered),
						ServerObservationCollector.MAX_BLOCKS,
						ServerObservationCollector.MAX_BLOCKS_PER_TYPE,
						ServerObservationCollector.MAX_BLOCK_VISIBILITY_CHECKS,
						ServerObservationCollector.MAX_BLOCK_VISIBILITY_CHECKS_PER_TYPE,
						candidate -> candidate.x() > 32,
						candidate -> {
							raycastsAfterCone.incrementAndGet();
							return true;
						});
		assertEquals(8, selectedAfterCone.size(),
				"cheap cone rejects do not hide later visible blocks of the same type");
		assertEquals(8, raycastsAfterCone.get(),
				"only cone-eligible candidates consume the raycast budget");
		assertions += 7;

		Map<BlockPos, net.minecraft.world.level.block.state.BlockState> mutableStates = new HashMap<>();
		BlockPos cachedPosition = new BlockPos(1, 0, 0);
		List<BlockObservationOrdering.Candidate> cached = List.of(
				new BlockObservationOrdering.Candidate(1, 0, 0, "minecraft:stone"));
		mutableStates.put(cachedPosition, Blocks.STONE.defaultBlockState());
		assertEquals(
				List.of(new BlockObservationOrdering.Candidate(1, 0, 0, "minecraft:stone")),
				ServerObservationCollector.refreshCurrentBlockCandidates(cached, BlockPos.ZERO, mutableStates::get),
				"cached candidates initially reflect the live block state");
		mutableStates.put(cachedPosition, Blocks.DIRT.defaultBlockState());
		assertEquals(
				List.of(new BlockObservationOrdering.Candidate(1, 0, 0, "minecraft:dirt")),
				ServerObservationCollector.refreshCurrentBlockCandidates(cached, BlockPos.ZERO, mutableStates::get),
				"cached candidates refresh their block ID after an in-window mutation");
		mutableStates.put(cachedPosition, Blocks.AIR.defaultBlockState());
		assertEquals(
				List.of(),
				ServerObservationCollector.refreshCurrentBlockCandidates(cached, BlockPos.ZERO, mutableStates::get),
				"cached candidates omit a block that became air");
		assertions += 3;

		return assertions;
	}

	private static List<BlockObservationOrdering.Candidate> crowdedBlocks() {
		ArrayList<BlockObservationOrdering.Candidate> candidates = new ArrayList<>();
		for (int index = 0; index < 513; index++) {
			candidates.add(new BlockObservationOrdering.Candidate(index, 0, 0, "minecraft:stone"));
		}
		return candidates;
	}

	private static List<BlockObservationOrdering.Candidate> visibleAll(
			List<BlockObservationOrdering.Candidate> candidates,
			AtomicInteger checks
	) {
		ArrayList<BlockObservationOrdering.Candidate> visible = new ArrayList<>();
		for (BlockObservationOrdering.Candidate candidate : candidates) {
			checks.incrementAndGet();
			visible.add(candidate);
		}
		return visible;
	}

	private static List<BlockObservationOrdering.Candidate> candidates(long seed) {
		Random random = new Random(seed);
		ArrayList<BlockObservationOrdering.Candidate> candidates = new ArrayList<>();
		HashSet<Long> occupied = new HashSet<>();
		int target = 200 + random.nextInt(400);
		while (candidates.size() < target) {
			int x = random.nextInt(13) - 6;
			int y = random.nextInt(7) - 3;
			int z = random.nextInt(13) - 6;
			if (!occupied.add(((long) (x + 8) << 16) | ((long) (y + 8) << 8) | (z + 8))) continue;
			candidates.add(new BlockObservationOrdering.Candidate(
					x, y, z, BLOCK_IDS[random.nextInt(BLOCK_IDS.length)]));
		}
		return List.copyOf(candidates);
	}

	private static Set<BlockObservationOrdering.Candidate> occluded(
			List<BlockObservationOrdering.Candidate> candidates,
			long seed
	) {
		Random random = new Random(seed * 31L + 7L);
		HashSet<BlockObservationOrdering.Candidate> occluded = new HashSet<>();
		for (BlockObservationOrdering.Candidate candidate : candidates) {
			if (random.nextInt(3) != 0) occluded.add(candidate);
		}
		return occluded;
	}

	private static void assertEquals(Object expected, Object actual, String label) {
		if (!expected.equals(actual)) {
			throw new AssertionError(label + ": expected <" + expected + "> but was <" + actual + ">");
		}
	}

	private static void assertTrue(boolean condition, String label) {
		if (!condition) throw new AssertionError(label);
	}

	private record SightCandidate(int distance, boolean visible) {
	}
}
