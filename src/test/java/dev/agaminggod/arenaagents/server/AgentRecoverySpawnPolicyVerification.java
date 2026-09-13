package dev.agaminggod.arenaagents.server;

import java.util.Optional;
import java.util.OptionalInt;
import java.util.concurrent.atomic.AtomicInteger;

public final class AgentRecoverySpawnPolicyVerification {
	private AgentRecoverySpawnPolicyVerification() {
	}

	public static void main(String[] args) {
		System.out.println("PASS: " + verify() + " recovery policy assertions");
	}

	public static int verify() {
		int assertions = 0;
		AgentRecoverySpawnPolicy.Column unsafeWater = new AgentRecoverySpawnPolicy.Column(
				64, false, false, false, true, false, true, false);
		AgentRecoverySpawnPolicy.Column safeLand = new AgentRecoverySpawnPolicy.Column(
				67, true, true, true, true, true, true, true);
		AgentRecoverySpawnPolicy.Column unsupportedSand = new AgentRecoverySpawnPolicy.Column(
				63, true, false, true, true, true, true, true);
		AtomicInteger sampled = new AtomicInteger();
		Optional<AgentRecoverySpawnPolicy.Position> selected = AgentRecoverySpawnPolicy.selectNearestDryPosition(
				8, 8, 0, 15, 0, 15,
				(x, z) -> {
					sampled.incrementAndGet();
					return x == 9 && z == 8 ? safeLand : unsafeWater;
				}
		);
		assertEquals(Optional.of(new AgentRecoverySpawnPolicy.Position(9, 67, 8)), selected,
				"submerged chunk center is rejected for the nearest dry supported column");
		assertions++;
		assertEquals(5, sampled.get(), "nearest-first recovery stops sampling after the first safe distance tier");
		assertions++;

		Optional<AgentRecoverySpawnPolicy.Position> shiftingFloor = AgentRecoverySpawnPolicy.selectNearestDryPosition(
				8, 8, 0, 15, 0, 15, (x, z) -> x == 8 && z == 8 ? unsupportedSand : unsafeWater);
		assertEquals(Optional.empty(), shiftingFloor,
				"a gravity-affected floor without stable support is rejected before it can fall into water");
		assertions++;

		Optional<AgentRecoverySpawnPolicy.Position> none = AgentRecoverySpawnPolicy.selectNearestDryPosition(
				8, 8, 0, 15, 0, 15, (x, z) -> unsafeWater);
		assertEquals(Optional.empty(), none, "a chunk without dry supported body space has no recovery position");
		assertions++;

		assertEquals(
				new AgentRecoverySpawnPolicy.ChunkPosition(-1, 1),
				AgentRecoverySpawnPolicy.chunkContaining(-0.1D, 16.0D),
				"legacy entity coordinates use floor-based chunk selection before the safety search"
		);
		assertions++;

		expectIllegalArgument(
				() -> AgentRecoverySpawnPolicy.selectNearestDryPosition(
						0, 0, Integer.MIN_VALUE, Integer.MAX_VALUE, 0, 0, (x, z) -> safeLand),
				"corrupted recovery bounds are rejected before an overflowing scan"
		);
		assertions++;

		assertEquals(OptionalInt.of(61), AgentRecoverySpawnPolicy.selectNearestSafeY(
				64, -64, 319, y -> y == 61 || y == 128),
				"recovery selects playable body space near the persisted height instead of the Nether roof");
		assertions++;
		assertEquals(OptionalInt.empty(), AgentRecoverySpawnPolicy.selectNearestSafeY(
				64, -64, 319, y -> y == 128),
				"recovery rejects safe-looking terrain outside the bounded persisted-height search");
		assertions++;

		CodexAgentManager.RecoveryAttemptGate attemptGate = new CodexAgentManager.RecoveryAttemptGate();
		assertEquals(true, attemptGate.tryClaim(), "the first missing agent can claim this tick's recovery attempt");
		assertions++;
		assertEquals(false, attemptGate.tryClaim(),
				"a failed first recovery still consumes the tick budget and prevents recovery fan-out");
		assertions++;
		return assertions;
	}

	private static void expectIllegalArgument(Runnable action, String message) {
		try {
			action.run();
			throw new AssertionError(message + ": expected IllegalArgumentException");
		} catch (IllegalArgumentException expected) {
			// Expected.
		}
	}

	private static void assertEquals(Object expected, Object actual, String message) {
		if (!expected.equals(actual)) throw new AssertionError(message + ": expected=" + expected + ", actual=" + actual);
	}
}
