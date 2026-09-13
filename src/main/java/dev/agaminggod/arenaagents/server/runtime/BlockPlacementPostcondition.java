package dev.agaminggod.arenaagents.server.runtime;

import java.util.Objects;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.level.block.state.BlockState;

public final class BlockPlacementPostcondition {
	private BlockPlacementPostcondition() {
	}

	public static Decision evaluate(
			String initialBlockId,
			String currentBlockId,
			String expectedBlockId,
			boolean placementOwned,
			boolean timedOut
	) {
		Objects.requireNonNull(initialBlockId, "initialBlockId must not be null");
		Objects.requireNonNull(currentBlockId, "currentBlockId must not be null");
		Objects.requireNonNull(expectedBlockId, "expectedBlockId must not be null");
		if (initialBlockId.equals(expectedBlockId)) {
			return currentBlockId.equals(expectedBlockId) ? Decision.ALREADY_SATISFIED : Decision.CONFLICT;
		}
		if (currentBlockId.equals(expectedBlockId)) {
			return placementOwned ? Decision.SUCCEEDED : Decision.CONFLICT;
		}
		if (!currentBlockId.equals(initialBlockId)) return Decision.CONFLICT;
		return timedOut ? Decision.TIMED_OUT : Decision.WAITING;
	}

	/** Preserves the original block-id-only contract for callers without ownership tracking. */
	public static Decision evaluate(
			String initialBlockId,
			String currentBlockId,
			String expectedBlockId,
			boolean timedOut
	) {
		Objects.requireNonNull(initialBlockId, "initialBlockId must not be null");
		Objects.requireNonNull(currentBlockId, "currentBlockId must not be null");
		Objects.requireNonNull(expectedBlockId, "expectedBlockId must not be null");
		if (initialBlockId.equals(expectedBlockId)) return Decision.CONFLICT;
		if (currentBlockId.equals(expectedBlockId)) return Decision.SUCCEEDED;
		if (!currentBlockId.equals(initialBlockId)) return Decision.CONFLICT;
		return timedOut ? Decision.TIMED_OUT : Decision.WAITING;
	}

	public static Decision evaluate(
			String initialBlockId,
			BlockState currentState,
			DesiredBlockState expectedState,
			boolean placementOwned,
			boolean timedOut
	) {
		return evaluate(initialBlockId, currentState, expectedState, placementOwned, timedOut, -1);
	}

	public static Decision evaluate(
			String initialBlockId,
			BlockState currentState,
			DesiredBlockState expectedState,
			boolean placementOwned,
			boolean timedOut,
			int attempts
	) {
		Objects.requireNonNull(initialBlockId, "initialBlockId must not be null");
		Objects.requireNonNull(currentState, "currentState must not be null");
		Objects.requireNonNull(expectedState, "expectedState must not be null");
		String currentBlockId = BuiltInRegistries.BLOCK.getKey(currentState.getBlock()).toString();
		String expectedBlockId = expectedState.blockId();
		if (initialBlockId.equals(expectedBlockId)) {
			return expectedState.matches(currentState) ? Decision.ALREADY_SATISFIED : Decision.CONFLICT;
		}
		if (!currentBlockId.equals(expectedBlockId)) {
			if (!currentBlockId.equals(initialBlockId)) return Decision.CONFLICT;
			return exhaustedOrTimedOut(attempts, timedOut);
		}
		if (!expectedState.matches(currentState)) return Decision.CONFLICT;
		return placementOwned ? Decision.SUCCEEDED : Decision.CONFLICT;
	}

	private static Decision exhaustedOrTimedOut(int attempts, boolean timedOut) {
		if (BlockPlacementAttemptPolicy.isExhausted(attempts)) return Decision.EXHAUSTED;
		return timedOut ? Decision.TIMED_OUT : Decision.WAITING;
	}

	public enum Decision {
		WAITING,
		SUCCEEDED,
		ALREADY_SATISFIED,
		CONFLICT,
		TIMED_OUT,
		EXHAUSTED
	}
}
