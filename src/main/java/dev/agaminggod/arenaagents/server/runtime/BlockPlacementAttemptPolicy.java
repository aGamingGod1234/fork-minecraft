package dev.agaminggod.arenaagents.server.runtime;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Predicate;
import net.minecraft.core.Direction;

public final class BlockPlacementAttemptPolicy {
	private static final List<Direction> MODEL_FACE_ORDER = List.of(
			Direction.DOWN,
			Direction.UP,
			Direction.NORTH,
			Direction.SOUTH,
			Direction.WEST,
			Direction.EAST
	);
	static final long RETRY_INTERVAL_MS = 250L;
	static final int MAX_ATTEMPTS = 8;

	private BlockPlacementAttemptPolicy() {
	}

	static boolean shouldAttempt(long elapsedMs, int attempts) {
		return attempts >= 0
				&& attempts < MAX_ATTEMPTS
				&& Math.max(0L, elapsedMs) >= attempts * RETRY_INTERVAL_MS;
	}

	static boolean isExhausted(int attempts) {
		return attempts >= MAX_ATTEMPTS;
	}

	static Direction chooseFace(Direction requested, Predicate<Direction> usable) {
		return requested != null && usable.test(requested) ? requested : null;
	}

	public static List<Direction> supportedFaces(Predicate<Direction> usable) {
		ArrayList<Direction> supported = new ArrayList<>(MODEL_FACE_ORDER.size());
		for (Direction face : MODEL_FACE_ORDER) if (usable.test(face)) supported.add(face);
		return List.copyOf(supported);
	}
}
