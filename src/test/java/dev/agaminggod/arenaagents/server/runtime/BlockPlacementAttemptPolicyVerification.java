package dev.agaminggod.arenaagents.server.runtime;

import java.util.Set;
import net.minecraft.core.Direction;

public final class BlockPlacementAttemptPolicyVerification {
	private BlockPlacementAttemptPolicyVerification() {
	}

	public static int verify() {
		assertTrue(BlockPlacementAttemptPolicy.shouldAttempt(0L, 0), "first click is immediate");
		assertFalse(BlockPlacementAttemptPolicy.shouldAttempt(249L, 1), "retry waits for its interval");
		assertTrue(BlockPlacementAttemptPolicy.shouldAttempt(250L, 1), "retry starts at its interval");
		assertFalse(BlockPlacementAttemptPolicy.shouldAttempt(10_000L, 8), "retry count is bounded");
		assertFalse(BlockPlacementAttemptPolicy.isExhausted(7), "seventh attempt still permits one final click");
		assertTrue(BlockPlacementAttemptPolicy.isExhausted(8), "eighth attempt exhausts the click budget");
		assertEquals(Direction.NORTH, BlockPlacementAttemptPolicy.chooseFace(
				Direction.NORTH, Set.of(Direction.NORTH, Direction.UP)::contains),
				"requested usable face is retained");
		assertEquals(null, BlockPlacementAttemptPolicy.chooseFace(
				Direction.NORTH, Set.of(Direction.UP, Direction.SOUTH)::contains),
				"unusable requested face is rejected instead of substituted");
		assertEquals(null, BlockPlacementAttemptPolicy.chooseFace(Direction.NORTH, direction -> false),
				"missing support is reported explicitly");
		assertEquals(
				java.util.List.of(Direction.UP, Direction.NORTH),
				BlockPlacementAttemptPolicy.supportedFaces(Set.of(Direction.NORTH, Direction.UP)::contains),
				"supported placement faces use a stable model-facing order"
		);
		assertEquals(java.util.List.of(), BlockPlacementAttemptPolicy.supportedFaces(direction -> false),
				"unsupported blocks expose no placeable faces");
		return 11;
	}

	private static void assertEquals(Object expected, Object actual, String label) {
		if (!java.util.Objects.equals(expected, actual)) {
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
