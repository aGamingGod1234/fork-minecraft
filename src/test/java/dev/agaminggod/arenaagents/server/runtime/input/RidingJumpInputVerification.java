package dev.agaminggod.arenaagents.server.runtime.input;

import java.util.Objects;

public final class RidingJumpInputVerification {
	private RidingJumpInputVerification() {
	}

	public static void main(String[] args) {
		System.out.println("RidingJumpInputVerification: " + verify() + " assertions passed");
	}

	public static int verify() {
		int assertions = 0;
		RidingJumpInput input = new RidingJumpInput();
		assertEquals(null, input.tick(false, true), "neutral input cannot jump");
		assertEquals(null, input.tick(true, true), "initial press only starts charging");
		assertEquals(0, input.tick(false, true), "immediate explicit release emits zero charge");
		assertEquals(null, input.tick(false, true), "a release is emitted only once");
		assertions += 4;

		int[][] samples = {{1, 10}, {5, 50}, {9, 90}, {10, 100}, {11, 90}, {12, 86}, {15, 83}, {30, 80}};
		for (int[] sample : samples) {
			input.reset();
			assertions += hold(input, sample[0]);
			assertEquals(sample[1], input.tick(false, true), "vanilla charge after " + sample[0] + " held ticks");
			assertEquals(null, input.tick(false, true), "released charge cannot repeat");
			assertions += 2;
		}

		input.reset();
		assertions += hold(input, 10);
		input.reset();
		assertEquals(null, input.tick(false, true), "cancellation discards charge without release");
		assertions += hold(input, 1);
		assertEquals(10, input.tick(false, true), "charging starts fresh after reset");
		assertions += 2;

		assertions += hold(input, 10);
		assertEquals(null, input.tick(false, false), "unavailable explicit release discards charge");
		assertEquals(null, input.tick(false, true), "availability returning cannot discharge old charge");
		assertions += 2;

		assertions += hold(input, 10);
		assertEquals(null, input.tick(true, false), "losing availability while held discards charge");
		assertEquals(null, input.tick(true, false), "unavailable held input cannot accumulate charge");
		assertEquals(null, input.tick(true, true), "availability returning starts a fresh press");
		assertEquals(0, input.tick(false, true), "unavailable time is excluded from charge");
		assertions += 4;

		for (int tick = 0; tick < 10; tick++) {
			assertEquals(null, input.tick(false, true), "post-release ticks cannot emit a jump");
			assertions++;
		}
		assertions += hold(input, 1);
		assertEquals(10, input.tick(false, true), "a later press starts fresh after cooldown");
		return assertions + 1;
	}

	private static int hold(RidingJumpInput input, int heldTicks) {
		assertEquals(null, input.tick(true, true), "press cannot emit a jump");
		for (int tick = 0; tick < heldTicks; tick++) {
			assertEquals(null, input.tick(true, true), "holding cannot emit a jump");
		}
		return heldTicks + 1;
	}

	private static void assertEquals(Object expected, Object actual, String message) {
		if (!Objects.equals(expected, actual)) {
			throw new AssertionError(message + ": expected " + expected + ", got " + actual);
		}
	}
}
