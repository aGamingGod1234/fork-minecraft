package dev.agaminggod.arenaagents.server.perception;

import com.google.gson.JsonObject;
import java.util.concurrent.atomic.AtomicBoolean;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

public final class ServerObservationRayTargetVerification {
	private ServerObservationRayTargetVerification() {
	}

	public static int verify() {
		AtomicBoolean blockLookupCalled = new AtomicBoolean();
		BlockHitResult miss = BlockHitResult.miss(Vec3.ZERO, Direction.NORTH, BlockPos.ZERO);

		JsonObject rayTarget = ServerObservationCollector.rayTarget(miss, position -> {
			blockLookupCalled.set(true);
			return "minecraft:stone";
		});

		assertEquals("{\"type\":\"miss\"}", rayTarget.toString(), "miss ray target contains only its type");
		assertFalse(blockLookupCalled.get(), "miss ray target does not resolve a block");
		return 2;
	}

	private static void assertEquals(Object expected, Object actual, String label) {
		if (!expected.equals(actual)) {
			throw new AssertionError(label + ": expected <" + expected + "> but was <" + actual + ">");
		}
	}

	private static void assertFalse(boolean condition, String label) {
		if (condition) throw new AssertionError(label);
	}
}
