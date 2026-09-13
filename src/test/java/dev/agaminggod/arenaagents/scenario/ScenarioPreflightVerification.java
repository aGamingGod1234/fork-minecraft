package dev.agaminggod.arenaagents.scenario;

import dev.agaminggod.arenaagents.scenario.runtime.ScenarioPreflight;
import dev.agaminggod.arenaagents.scenario.runtime.ScenarioArenaBlueprint;
import dev.agaminggod.arenaagents.scenario.runtime.ScenarioSitePreflight;
import dev.agaminggod.arenaagents.server.bridge.CoordinatorStatusSnapshot;
import java.util.List;
import java.util.Optional;

public final class ScenarioPreflightVerification {
	private ScenarioPreflightVerification() {
	}

	public static int verify() {
		long now = 100_000L;
		ScenarioPreflight.RequiredProfile required = new ScenarioPreflight.RequiredProfile("agent-a", "codex", "gpt-5.6-sol", "high");
		CoordinatorStatusSnapshot healthy = status(now, required, true, 1, 1, 0, 0, "closed");
		ScenarioPreflight.Input ready = input(now, Optional.of(healthy), List.of(required), "hash-a", "hash-a");
		assertEquals(ScenarioPreflight.Status.READY, ScenarioPreflight.assess(ready).status(), "complete fresh preflight is ready");

		CoordinatorStatusSnapshot stale = status(now - ScenarioPreflight.STATUS_MAXIMUM_AGE_MS - 1L, required, true, 1, 1, 0, 0, "closed");
		assertEquals("COORDINATOR_STATUS_STALE", ScenarioPreflight.assess(input(now, Optional.of(stale), List.of(required), "hash-a", "hash-a")).code(), "stale status waits");
		assertEquals("COORDINATOR_NOT_RECONCILED", ScenarioPreflight.assess(input(now, Optional.of(status(now, required, false, 1, 1, 0, 0, "closed")), List.of(required), "hash-a", "hash-a")).code(), "unreconciled status waits");
		assertEquals("RESET_HASH_MISMATCH", ScenarioPreflight.assess(input(now, Optional.of(healthy), List.of(required), "hash-a", "hash-b")).code(), "reset mismatch fails");
		ScenarioPreflight.Input unverifiedReset = new ScenarioPreflight.Input(Optional.of(healthy), List.of(required), "minecraft:overworld", "minecraft:overworld", "hash-a", "hash-a", false, now - 1_000L, now);
		assertEquals("RESET_NOT_VERIFIED", ScenarioPreflight.assess(unverifiedReset).code(), "unverified reset fails");
		ScenarioPreflight.Input wrongDimension = new ScenarioPreflight.Input(Optional.of(healthy), List.of(required), "minecraft:overworld", "minecraft:the_nether", "hash-a", "hash-a", true, now - 1_000L, now);
		assertEquals("DIMENSION_MISMATCH", ScenarioPreflight.assess(wrongDimension).code(), "dimension mismatch fails");
		ScenarioPreflight.RequiredProfile other = new ScenarioPreflight.RequiredProfile("agent-b", "gemini", "gemini-3.1-pro", "high");
		assertEquals("REQUIRED_PROFILE_NOT_READY", ScenarioPreflight.assess(input(now, Optional.of(healthy), List.of(other), "hash-a", "hash-a")).code(), "missing coordinator-ready profile waits");
		ScenarioPreflight.RequiredProfile wrongTier = new ScenarioPreflight.RequiredProfile(
				"agent-a", "codex", "gpt-5.6-sol", "high", "flex");
		assertEquals("REQUIRED_PROFILE_NOT_READY",
				ScenarioPreflight.assess(input(now, Optional.of(healthy), List.of(wrongTier), "hash-a", "hash-a")).code(),
				"preflight does not approve a different service tier");
		assertEquals("ROSTER_NOT_READY", ScenarioPreflight.assess(input(now, Optional.of(status(now, required, true, 0, 1, 0, 0, "closed")), List.of(required), "hash-a", "hash-a")).code(), "matching profile without ready roster waits");
		assertEquals("SCHEDULER_HEADROOM", ScenarioPreflight.assess(input(now, Optional.of(status(now, required, true, 1, 1, 4, 12, "closed")), List.of(required), "hash-a", "hash-a")).code(), "full scheduler waits");
		assertEquals("PROVIDER_CIRCUIT_OPEN", ScenarioPreflight.assess(input(now, Optional.of(status(now, required, true, 1, 1, 0, 0, "open")), List.of(required), "hash-a", "hash-a")).code(), "open provider circuit waits");
		ScenarioPreflight.Input startupStillPending = new ScenarioPreflight.Input(Optional.empty(), List.of(required), "minecraft:overworld", "minecraft:overworld", "hash-a", "hash-a", true, now - 60_001L, now);
		assertEquals("COORDINATOR_STATUS_STALE", ScenarioPreflight.assess(startupStillPending).code(), "full concurrent provider startup window remains available");
		ScenarioPreflight.Input expiredWave = new ScenarioPreflight.Input(Optional.of(healthy), List.of(required), "minecraft:overworld", "minecraft:overworld", "hash-a", "hash-a", true, now - ScenarioPreflight.FIRST_WAVE_DEADLINE_MS - 1L, now);
		assertEquals("FIRST_WAVE_DEADLINE", ScenarioPreflight.assess(expiredWave).code(), "first-wave deadline fails closed");
		ScenarioArenaBlueprint.SiteBounds bounds = new ScenarioArenaBlueprint.SiteBounds(-64, 64, 70, -64, 64);
		ScenarioSitePreflight.Verdict site = ScenarioSitePreflight.assess(new ScenarioSitePreflight.Input(
				bounds, -64, 320, 92, true, 3));
		assertEquals(true, site.allowedWithConfirmation(), "valid destructive site requires explicit confirmation");
		assertEquals(3, site.occupantCount(), "site verdict carries the exact occupant count");
		assertEquals("SITE_OUTSIDE_WORLD_BORDER", ScenarioSitePreflight.assess(new ScenarioSitePreflight.Input(
				bounds, -64, 320, 92, false, 0)).code(), "world-border violation fails before mutation");
		assertEquals("SITE_HEIGHT_OUT_OF_RANGE", ScenarioSitePreflight.assess(new ScenarioSitePreflight.Input(
				bounds, 80, 320, 92, true, 0)).code(), "clear floor below build height fails before mutation");
		return 17;
	}

	private static ScenarioPreflight.Input input(long now, Optional<CoordinatorStatusSnapshot> status, List<ScenarioPreflight.RequiredProfile> profiles, String expectedHash, String actualHash) {
		return new ScenarioPreflight.Input(status, profiles, "minecraft:overworld", "minecraft:overworld", expectedHash, actualHash, true, now - 1_000L, now);
	}

	private static CoordinatorStatusSnapshot status(long receivedAt, ScenarioPreflight.RequiredProfile required, boolean reconciled, int ready, int total, int active, int pending, String circuit) {
		return new CoordinatorStatusSnapshot(
				reconciled,
				List.of(new CoordinatorStatusSnapshot.SupportedProfile(
						required.agentId(), required.provider(), required.model(),
						required.reasoningEffort(), required.serviceTier())),
				1,
				ready,
				total,
				new CoordinatorStatusSnapshot.SchedulerStatus(active, pending, 4, 12, false),
				List.of(
						new CoordinatorStatusSnapshot.CircuitHealth(required.provider(), required.model(), "create_agent", 0, 0, 0, 0.0, circuit),
						new CoordinatorStatusSnapshot.CircuitHealth(required.provider(), required.model(), "decide", 0, 0, 0, 0.0, circuit)
				),
				receivedAt
		);
	}

	private static void assertEquals(Object expected, Object actual, String label) {
		if (!java.util.Objects.equals(expected, actual)) throw new AssertionError(label + ": expected=" + expected + ", actual=" + actual);
	}
}
