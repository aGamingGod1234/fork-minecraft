package dev.agaminggod.arenaagents.scenario.runtime;

import dev.agaminggod.arenaagents.agent.AgentGameMode;
import dev.agaminggod.arenaagents.scenario.ScenarioAgentSpec;
import dev.agaminggod.arenaagents.scenario.ScenarioLaunchRequest;
import dev.agaminggod.arenaagents.scenario.ScenarioPlacementMode;
import dev.agaminggod.arenaagents.scenario.ScenarioPresets;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.AABB;

public final class ScenarioRuntimeSafetyVerification {
	private ScenarioRuntimeSafetyVerification() {
	}

	public static int verify() {
		UUID preparation = UUID.fromString("11111111-2222-3333-4444-555555555555");
		UUID staleRun = UUID.fromString("aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee");
		assertTrue(ScenarioRuntimeService.preparationOwnsRecovery(Optional.of(preparation), Optional.empty()),
				"a preparation journal owns recovery when no run snapshot is durable");
		assertTrue(ScenarioRuntimeService.preparationOwnsRecovery(
				Optional.of(preparation), Optional.of(staleRun)),
				"a newer preparation journal supersedes a different stale run snapshot");
		assertFalse(ScenarioRuntimeService.preparationOwnsRecovery(
				Optional.of(preparation), Optional.of(preparation)),
				"the active snapshot owns recovery after the same-session durable handoff");
		assertFalse(ScenarioRuntimeService.preparationOwnsRecovery(Optional.empty(), Optional.of(staleRun)),
				"a saved run remains authoritative without a preparation journal");

		assertEquals(List.of(), ScenarioRuntimeService.activationCleanupIds(true, List.of("agent-a")),
				"successful partial activation cleanup releases the retry ledger");
		assertEquals(List.of("agent-a", "agent-b"),
				ScenarioRuntimeService.activationCleanupIds(false, List.of("agent-a", "agent-b")),
				"failed partial activation cleanup retains every owned agent id");
		ScenarioLaunchRequest request = new ScenarioLaunchRequest(
				"last-valley", ScenarioPresets.require("last-valley").mapVersion(), true,
				ScenarioPlacementMode.IN_FRONT_OF_PLAYER,
				List.of(
						new ScenarioAgentSpec(1, "One", "codex", "gpt-5.6-sol", "high", "priority",
								Optional.empty(), AgentGameMode.SURVIVAL),
						new ScenarioAgentSpec(2, "Two", "codex", "gpt-5.6-sol", "high", "priority",
								Optional.empty(), AgentGameMode.SURVIVAL)));
		ScenarioPreparationJournal.Snapshot retryOwner = new ScenarioPreparationJournal.Snapshot(
				preparation, staleRun, "minecraft:overworld", new BlockPos(0, 70, 0), request,
				1L, 2L, 3L, List.of("agent-a", "agent-b"), false);
		ScenarioPreparationJournal.Snapshot clearedRetry =
				ScenarioRuntimeService.preparationWithoutAgents(retryOwner);
		assertEquals(List.of(), clearedRetry.agentIds(), "a cleaned activation retry starts with no stale agent ids");
		assertEquals(retryOwner.sessionId(), clearedRetry.sessionId(),
				"clearing retry agent ids preserves preparation ownership");

		ScenarioArenaBlueprint.SiteBounds bounds = new ScenarioArenaBlueprint.SiteBounds(-10, 10, 63, -8, 8);
		List<BlockPos> columns = ScenarioRuntimeService.evacuationCandidateColumns(bounds, 512);
		assertEquals(4_096, columns.size(), "maximum occupant evacuation has a bounded candidate budget");
		assertEquals(columns.size(), new HashSet<>(columns).size(), "evacuation columns are distinct");
		assertTrue(columns.stream().allMatch(position -> position.getX() < bounds.minimumX()
				|| position.getX() > bounds.maximumX()
				|| position.getZ() < bounds.minimumZ()
				|| position.getZ() > bounds.maximumZ()),
				"every evacuation column is outside the destructive site");
		List<BlockPos> wideFootprint = ScenarioRuntimeService.evacuationFootprint(
				new AABB(-0.5D, 70.0D, -0.5D, 1.5D, 72.0D, 1.5D), 69);
		assertEquals(9, wideFootprint.size(), "wide occupants require support beneath their full footprint");
		assertTrue(wideFootprint.stream().allMatch(position -> position.getY() == 69),
				"wide occupant support checks stay on the destination floor");
		return 13;
	}

	private static void assertTrue(boolean condition, String label) {
		if (!condition) throw new AssertionError(label);
	}

	private static void assertFalse(boolean condition, String label) {
		assertTrue(!condition, label);
	}

	private static void assertEquals(Object expected, Object actual, String label) {
		if (!expected.equals(actual)) {
			throw new AssertionError(label + ": expected <" + expected + "> but was <" + actual + ">");
		}
	}
}
