package dev.agaminggod.arenaagents.server;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.function.IntPredicate;

final class AgentRecoverySpawnPolicy {
	private static final int MAX_SEARCH_SPAN = 48;
	private static final int MAX_VERTICAL_DISTANCE = 32;

	private AgentRecoverySpawnPolicy() {
	}

	static ChunkPosition chunkContaining(double x, double z) {
		if (!Double.isFinite(x) || !Double.isFinite(z)) {
			throw new IllegalArgumentException("recovery coordinates must be finite");
		}
		long blockX = (long) Math.floor(x);
		long blockZ = (long) Math.floor(z);
		if (blockX < Integer.MIN_VALUE || blockX > Integer.MAX_VALUE
				|| blockZ < Integer.MIN_VALUE || blockZ > Integer.MAX_VALUE) {
			throw new IllegalArgumentException("recovery coordinates exceed supported bounds");
		}
		return new ChunkPosition(((int) blockX) >> 4, ((int) blockZ) >> 4);
	}

	static Optional<Position> selectNearestDryPosition(
			int centerX,
			int centerZ,
			int minX,
			int maxX,
			int minZ,
			int maxZ,
			ColumnLookup columns
	) {
		Objects.requireNonNull(columns, "columns must not be null");
		if (minX > maxX || minZ > maxZ) throw new IllegalArgumentException("search bounds must not be inverted");
		long width = (long) maxX - minX + 1L;
		long depth = (long) maxZ - minZ + 1L;
		if (width > MAX_SEARCH_SPAN || depth > MAX_SEARCH_SPAN) {
			throw new IllegalArgumentException("recovery search bounds exceed the supported span");
		}
		ArrayList<Position> candidates = new ArrayList<>((int) (width * depth));
		for (int x = minX; x <= maxX; x++) {
			for (int z = minZ; z <= maxZ; z++) {
				candidates.add(new Position(x, 0, z));
			}
		}
		candidates.sort(Comparator
				.comparingLong((Position position) -> squaredDistance(position.x(), position.z(), centerX, centerZ))
				.thenComparingInt(Position::x)
				.thenComparingInt(Position::z));
		for (Position candidate : candidates) {
			Column column = Objects.requireNonNull(columns.sample(candidate.x(), candidate.z()), "column must not be null");
			if (column.safe()) return Optional.of(new Position(candidate.x(), column.feetY(), candidate.z()));
		}
		return Optional.empty();
	}

	private static long squaredDistance(int x, int z, int centerX, int centerZ) {
		long dx = (long) x - centerX;
		long dz = (long) z - centerZ;
		return dx * dx + dz * dz;
	}

	static OptionalInt selectNearestSafeY(int preferredY, int minY, int maxY, IntPredicate safe) {
		Objects.requireNonNull(safe, "safe must not be null");
		if (minY > maxY) throw new IllegalArgumentException("vertical search bounds must not be inverted");
		int center = Math.max(minY, Math.min(maxY, preferredY));
		for (int distance = 0; distance <= MAX_VERTICAL_DISTANCE; distance++) {
			int lower = center - distance;
			if (lower >= minY && safe.test(lower)) return OptionalInt.of(lower);
			int upper = center + distance;
			if (distance > 0 && upper <= maxY && safe.test(upper)) return OptionalInt.of(upper);
		}
		return OptionalInt.empty();
	}

	record Position(int x, int y, int z) {
	}

	record ChunkPosition(int x, int z) {
	}

	record Column(
			int feetY,
			boolean floorSturdy,
			boolean floorStable,
			boolean floorDry,
			boolean feetClear,
			boolean feetDry,
			boolean headClear,
			boolean headDry
	) {
		boolean safe() {
			return floorSturdy && floorStable && floorDry && feetClear && feetDry && headClear && headDry;
		}
	}

	@FunctionalInterface
	interface ColumnLookup {
		Column sample(int x, int z);
	}
}
