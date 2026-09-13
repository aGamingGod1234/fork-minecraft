package dev.agaminggod.arenaagents.server.runtime.controller;

import dev.agaminggod.arenaagents.client.navigation.GridPosition;
import dev.agaminggod.arenaagents.client.navigation.PathNode;
import dev.agaminggod.arenaagents.client.navigation.PathOutcome;
import dev.agaminggod.arenaagents.client.navigation.TraversalType;
import dev.agaminggod.arenaagents.server.runtime.ElapsedTimeAccumulator;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.level.Level;

public final class NavigationProgressVerification {
	private NavigationProgressVerification() {
	}

	public static int verify() {
		ElapsedTimeAccumulator elapsed = new ElapsedTimeAccumulator(1_000L);
		assertEquals(500L, elapsed.advance(1_500L), "navigation elapsed time advances normally");
		assertEquals(500L, elapsed.advance(900L), "navigation elapsed time survives wall-clock rollback");
		ElapsedTimeAccumulator overflowElapsed = new ElapsedTimeAccumulator(Long.MIN_VALUE);
		assertEquals(Long.MAX_VALUE, overflowElapsed.advance(Long.MAX_VALUE),
				"navigation elapsed time saturates timestamp overflow");
		assertTrue(ServerNavigationController.remainsInDimension(Level.OVERWORLD, Level.OVERWORLD),
				"navigation remains valid in its starting dimension");
		assertTrue(!ServerNavigationController.remainsInDimension(Level.OVERWORLD, Level.NETHER),
				"navigation rejects the same coordinates after a dimension change");
		new ServerNavigationController(new net.minecraft.world.phys.Vec3(1.0D, 64.0D, 1.0D), 0.01D, false, 0L, 1_000L);
		WaypointProgress progress = new WaypointProgress(10.0D, 1_000L, 4_000L, 3);

		WaypointProgress.Update advanced = progress.observe(8.0D, true, 1_100L);
		assertTrue(advanced.advanceWaypoint(), "waypoint tolerance advances the plan");
		assertEquals(WaypointProgress.Decision.CONTINUE, advanced.decision(), "advance keeps navigation running");
		assertBounded(advanced.progress());

		WaypointProgress.Update stalled = progress.observe(8.0D, false, 5_200L);
		assertEquals(WaypointProgress.Decision.REPLAN, stalled.decision(), "four seconds without progress replans");
		progress.replanned(8.0D, 5_200L);
		progress.observe(8.0D, false, 9_300L);
		progress.replanned(8.0D, 9_300L);
		progress.observe(8.0D, false, 13_400L);
		progress.replanned(8.0D, 13_400L);
		WaypointProgress.Update exhausted = progress.observe(8.0D, false, 17_500L);
		assertEquals(WaypointProgress.Decision.FAIL, exhausted.decision(), "three replans exhaust recovery");
		assertBounded(exhausted.progress());

		WaypointProgress detour = new WaypointProgress(10.0D, 1_000L, 4_000L, 3);
		WaypointProgress.Update waypointReached = detour.observe(10.5D, true, 5_200L);
		assertEquals(WaypointProgress.Decision.CONTINUE, waypointReached.decision(),
				"reaching a detour waypoint resets the stall clock even when final distance increases");
		assertEquals(WaypointProgress.Decision.CONTINUE, detour.observe(10.5D, false, 9_199L).decision(),
				"detour progress retains the full stall recovery window");
		assertEquals(WaypointProgress.Decision.REPLAN, detour.observe(10.5D, false, 9_200L).decision(),
				"a reached detour replans only after its new stall deadline");

		WaypointProgress nextWaypoint = new WaypointProgress(2.0D, 1_000L, 4_000L, 3);
		nextWaypoint.observe(1.0D, true, 1_100L);
		nextWaypoint.waypointAdvanced(20.0D, 1_100L);
		assertEquals(WaypointProgress.Decision.CONTINUE, nextWaypoint.observe(20.0D, false, 5_099L).decision(),
				"a new waypoint gets its own progress baseline after a detour");
		assertEquals(WaypointProgress.Decision.REPLAN, nextWaypoint.observe(20.0D, false, 5_100L).decision(),
				"the new waypoint stall deadline is measured from its activation");

		WaypointProgress rollbackProgress = new WaypointProgress(10.0D, 1_000L, 300L, 1);
		assertEquals(WaypointProgress.Decision.CONTINUE, rollbackProgress.observe(10.0D, false, 1_200L).decision(),
				"waypoint stall time accumulates before rollback");
		assertEquals(WaypointProgress.Decision.CONTINUE, rollbackProgress.observe(10.0D, false, 900L).decision(),
				"clock rollback does not manufacture a waypoint stall");
		assertEquals(WaypointProgress.Decision.REPLAN, rollbackProgress.observe(10.0D, false, 1_000L).decision(),
				"waypoint stall time resumes from the corrected clock");
		WaypointProgress overflowProgress = new WaypointProgress(10.0D, Long.MIN_VALUE, 1L, 1);
		assertEquals(WaypointProgress.Decision.REPLAN,
				overflowProgress.observe(10.0D, false, Long.MAX_VALUE).decision(),
				"overflowing waypoint time saturates as elapsed");

		assertTrue(ServerNavigationController.satisfiesDestinationTolerance(1.0D, 1.0D),
				"the requested tolerance includes its exact boundary");
		assertTrue(!ServerNavigationController.satisfiesDestinationTolerance(1.01D, 1.0D),
				"path exhaustion outside the requested tolerance must replan");
		Vec3 progressStart = new Vec3(0.0D, 64.0D, 0.0D);
		Vec3 progressEndpoint = new Vec3(10.0D, 64.0D, 0.0D);
		assertEquals(0.4D, ServerNavigationController.progressFromActualDistance(
				progressStart, progressEndpoint, new Vec3(4.0D, 64.0D, 0.0D), 0.0D),
				"navigation progress is derived from actual endpoint distance");
		assertEquals(0.25D, ServerNavigationController.progressFromActualDistance(
				progressStart, progressEndpoint, new Vec3(2.5D, 64.0D, 0.0D), 0.4D),
				"backtracking is reflected instead of freezing a stale best progress value");
		ServerNavigationController intermediateNavigation = new ServerNavigationController(
				new Vec3(2.5D, 65.0D, 2.5D), 0.2D, false, 0L, 1_000L);
		PathNode jumpWaypoint = new PathNode(new GridPosition(2, 65, 2), TraversalType.JUMP_UP);
		assertTrue(!intermediateNavigation.reachedTarget(new Vec3(2.5D, 64.0D, 2.5D), jumpWaypoint, false),
				"an intermediate jump cannot complete while the player is one block below");
		assertTrue(intermediateNavigation.reachedTarget(
				new Vec3(2.5D, 64.5D, 2.5D), jumpWaypoint, false, 64.5D),
				"a waypoint above bottom-slab support completes at the collision surface");
		assertTrue(intermediateNavigation.reachedTarget(new Vec3(2.5D, 64.8D, 2.5D), jumpWaypoint, false),
				"an intermediate jump completes only after the player reaches the landing height");
		PathNode dropWaypoint = new PathNode(new GridPosition(2, 63, 2), TraversalType.DROP_DOWN);
		assertTrue(!intermediateNavigation.reachedTarget(new Vec3(2.5D, 64.0D, 2.5D), dropWaypoint, false),
				"an intermediate drop cannot complete before the lower landing");
		assertTrue(intermediateNavigation.reachedTarget(new Vec3(2.5D, 63.0D, 2.5D), dropWaypoint, false),
				"an intermediate drop completes at its landing height");
		assertTrue(ServerNavigationController.shouldRetryPlanning(
				PathOutcome.NODE_LIMIT,
				true, 1_000L, 10_000L),
				"scheduler-contended planning remains retryable beyond three ticks");
		assertTrue(!ServerNavigationController.shouldRetryPlanning(
				PathOutcome.NODE_LIMIT,
				true, 10_000L, 10_000L),
				"bounded planning retry stops at the navigation deadline");
		assertTrue(!ServerNavigationController.shouldRetryPlanning(
				PathOutcome.NO_PATH,
				false, 1_000L, 10_000L),
				"a genuine no-path result remains terminal");

		Vec3 exactDestination = new Vec3(5.1D, 64.1D, 7.9D);
		ServerNavigationController exactNavigation = new ServerNavigationController(
				exactDestination, 0.2D, false, 0L, 1_000L);
		PathNode finalNode = new PathNode(new GridPosition(5, 64, 7), TraversalType.WALK);
		Vec3 finalTarget = exactNavigation.targetFor(finalNode, true);
		assertEquals(5.1D, finalTarget.x, "the final waypoint retains the requested x coordinate");
		assertEquals(64.1D, finalTarget.y, "the final waypoint retains the requested y coordinate on full support");
		assertEquals(7.9D, finalTarget.z, "the final waypoint retains the requested z coordinate");
		assertTrue(!exactNavigation.reachedTarget(new Vec3(5.5D, 64.0D, 7.5D), finalNode, true),
				"the block center does not finish a precise destination outside tolerance");
		assertTrue(exactNavigation.reachedTarget(new Vec3(5.2D, 64.0D, 7.9D), finalNode, true),
				"the exact final target finishes inside the requested tolerance");
		assertTrue(exactNavigation.reachedTarget(
				new Vec3(5.1D, 63.5D, 7.9D), finalNode, true, 63.5D),
				"an exact destination on partial support uses the collision-surface height");
		Vec3 adjustedTarget = exactNavigation.targetFor(
				new PathNode(new GridPosition(4, 64, 7), TraversalType.WALK), true);
		assertEquals(4.5D, adjustedTarget.x, "an adjusted safe goal retains its block-center x coordinate");
		assertEquals(7.5D, adjustedTarget.z, "an adjusted safe goal retains its block-center z coordinate");
		GridPosition adjusted = new GridPosition(4, 64, 7);
		assertTrue(!ServerNavigationController.candidateSatisfiesTolerance(adjusted, exactDestination, 0.2D),
				"an adjusted endpoint outside the requested tolerance is never selected");
		assertTrue(ServerNavigationController.candidateSatisfiesTolerance(
				new GridPosition(5, 64, 7), exactDestination, 0.6D),
				"a safe endpoint inside the requested tolerance remains eligible");

		assertTrue(ServerNavigationController.hasAirReserve(true, 61), "surface traversal retains a breathing reserve");
		assertTrue(!ServerNavigationController.hasAirReserve(true, 60), "the exact air reserve ends water traversal");
		assertTrue(ServerNavigationController.hasAirReserve(false, 0), "air reserve does not prevent grounded movement");
		return 44;
	}

	private static void assertBounded(double value) {
		if (value < 0.0D || value > 1.0D || !Double.isFinite(value)) {
			throw new AssertionError("progress must remain in [0, 1]: " + value);
		}
	}

	private static void assertEquals(Object expected, Object actual, String message) {
		if (!expected.equals(actual)) {
			throw new AssertionError(message + " (expected=" + expected + ", actual=" + actual + ")");
		}
	}

	private static void assertTrue(boolean condition, String message) {
		if (!condition) {
			throw new AssertionError(message);
		}
	}
}
