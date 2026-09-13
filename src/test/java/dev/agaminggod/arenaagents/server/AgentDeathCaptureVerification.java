package dev.agaminggod.arenaagents.server;

import dev.agaminggod.arenaagents.agent.AgentDeathSnapshot;
import dev.agaminggod.arenaagents.agent.AgentLifecycleState;
import dev.agaminggod.arenaagents.agent.AgentRecord;
import dev.agaminggod.arenaagents.agent.AgentRegistry;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

public final class AgentDeathCaptureVerification {
	private AgentDeathCaptureVerification() {
	}

	public static int verify() {
		int assertions = 0;
		AgentRegistry registry = AgentRegistry.createDefault(() -> { }, transition -> { });
		AgentRecord created = registry.create("gpt-5.6-sol", "medium", Optional.of("DeathTest"), 1_000L);
		AgentDeathSnapshot snapshot = new AgentDeathSnapshot(
				"DeathTest was slain by Zombie", "minecraft:overworld", 2.5D, 69.0D, 1.5D,
				Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(), 2_000L
		);

		boolean captured = AgentDeathCapture.record(
				registry,
				OfflineAgentPlayers.offlineUuid(created.agentId(), created.profile()),
				snapshot,
				2_000L
		);
		assertTrue(captured, "a lethal event for the real offline player is captured before disconnect");
		assertions++;
		AgentRecord dead = registry.require(created.agentId());
		assertEquals(AgentLifecycleState.DEAD, dead.state(), "captured lethal events persist DEAD instead of DISCONNECTED");
		assertions++;
		assertEquals(Optional.of(snapshot), dead.deathSnapshot(), "the model receives the exact pre-disconnect death facts");
		assertions++;
		long deadRevision = dead.goalRevision();
		assertEquals(true, AgentDeathCapture.record(
				registry, OfflineAgentPlayers.offlineUuid(created.agentId(), created.profile()), snapshot, 2_001L),
				"duplicate lethal callbacks still identify the same agent");
		assertions++;
		assertEquals(deadRevision, registry.require(created.agentId()).goalRevision(),
				"duplicate lethal callbacks cannot publish a second death revision");
		assertions++;
		assertEquals(false, AgentDeathCapture.record(registry, java.util.UUID.randomUUID(), snapshot, 2_001L),
				"ordinary players are not converted into agents");
		assertions++;

		AtomicInteger recorded = new AtomicInteger();
		assertEquals(false, AgentDeathCapture.allowVanillaDeath(true, recorded::incrementAndGet),
				"scenario recovery may prevent vanilla death without publishing false death facts");
		assertions++;
		assertEquals(0, recorded.get(), "prevented deaths never notify the model");
		assertions++;
		assertEquals(true, AgentDeathCapture.allowVanillaDeath(false, recorded::incrementAndGet),
				"unhandled lethal damage continues through the vanilla death screen");
		assertions++;
		assertEquals(1, recorded.get(), "vanilla death is recorded exactly once before the player disconnects");
		return assertions + 1;
	}

	private static void assertTrue(boolean value, String message) {
		if (!value) throw new AssertionError(message);
	}

	private static void assertEquals(Object expected, Object actual, String message) {
		if (!java.util.Objects.equals(expected, actual)) {
			throw new AssertionError(message + ": expected=" + expected + ", actual=" + actual);
		}
	}
}
