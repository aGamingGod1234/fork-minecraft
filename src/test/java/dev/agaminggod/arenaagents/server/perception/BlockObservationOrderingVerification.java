package dev.agaminggod.arenaagents.server.perception;

import java.util.ArrayList;
import java.util.List;

public final class BlockObservationOrderingVerification {
	private BlockObservationOrderingVerification() {
	}

	public static int verify() {
		ArrayList<BlockObservationOrdering.Candidate> candidates = new ArrayList<>();
		for (int x = -4; x <= 4; x++) {
			candidates.add(candidate(x, -1, 0, "minecraft:grass_block"));
		}
		candidates.add(candidate(2, 0, 0, "minecraft:crafting_table"));
		candidates.add(candidate(4, 1, 0, "minecraft:oak_log"));
		candidates.add(candidate(-5, 0, 0, "minecraft:coal_ore"));

		List<BlockObservationOrdering.Candidate> selected =
				BlockObservationOrdering.select(candidates, 6, 2);

		assertEquals(5, selected.size(), "selection respects total and per-type limits");
		assertEquals(
				2L,
				selected.stream().filter(candidate -> candidate.blockId().equals("minecraft:grass_block")).count(),
				"repetitive terrain cannot consume the complete observation"
		);
		assertTrue(
				selected.stream().anyMatch(candidate -> candidate.blockId().equals("minecraft:oak_log")),
				"nearby tree remains observable after terrain de-duplication"
		);
		assertTrue(
				selected.stream().anyMatch(candidate -> candidate.blockId().equals("minecraft:coal_ore")),
				"nearby ore remains observable after terrain de-duplication"
		);
		assertEquals(
				candidate(0, -1, 0, "minecraft:grass_block"),
				selected.getFirst(),
				"selection is nearest-first"
		);
		assertEquals(
				selected,
				BlockObservationOrdering.select(candidates.reversed(), 6, 2),
				"selection is deterministic regardless of scan order"
		);
		return 6;
	}

	private static BlockObservationOrdering.Candidate candidate(int x, int y, int z, String blockId) {
		return new BlockObservationOrdering.Candidate(x, y, z, blockId);
	}

	private static void assertEquals(Object expected, Object actual, String label) {
		if (!expected.equals(actual)) {
			throw new AssertionError(label + ": expected <" + expected + "> but was <" + actual + ">");
		}
	}

	private static void assertTrue(boolean condition, String label) {
		if (!condition) {
			throw new AssertionError(label);
		}
	}
}
