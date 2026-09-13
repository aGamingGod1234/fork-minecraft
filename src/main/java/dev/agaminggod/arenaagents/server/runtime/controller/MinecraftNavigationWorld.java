package dev.agaminggod.arenaagents.server.runtime.controller;

import dev.agaminggod.arenaagents.client.navigation.GridPosition;
import dev.agaminggod.arenaagents.client.navigation.WalkabilityView;
import dev.agaminggod.arenaagents.client.navigation.TraversalType;
import dev.agaminggod.arenaagents.world.WorldMutationRevisionAccess;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.FluidTags;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.shapes.VoxelShape;

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Read-only, chunk-safe terrain view used by the bounded local planner.
 */
public final class MinecraftNavigationWorld implements WalkabilityView {
	private static final double[] FOOTPRINT_SAMPLES = {0.2D, 0.5D, 0.8D};
	private static final Direction[] HORIZONTAL_DIRECTIONS = {Direction.WEST, Direction.EAST, Direction.NORTH, Direction.SOUTH};

	private final ServerLevel level;
	private final Map<GridPosition, Cell> cells = new HashMap<>();
	private final Map<GridPosition, Boolean> shallowWater = new HashMap<>();
	private final Map<Long, Boolean> sampledChunks = new HashMap<>();
	private final SampledRevisions sampledRevisions;
	private long cacheHits;
	private long cacheMisses;

	public MinecraftNavigationWorld(ServerLevel level) {
		this.level = Objects.requireNonNull(level, "level must not be null");
		this.sampledRevisions = new SampledRevisions((center, radius) ->
				level instanceof WorldMutationRevisionAccess access
						? access.arenaagents$worldMutationRevision(center, radius) : level.getGameTime());
	}

	/** Dynamic collision changes invalidate even when the sampled block states are identical. */
	boolean isCurrent() {
		for (Map.Entry<Long, Boolean> entry : sampledChunks.entrySet()) {
			long key = entry.getKey();
			if (level.hasChunk((int) (key >> 32), (int) key) != entry.getValue()) return false;
		}
		return sampledRevisions.isCurrent();
	}

	private BlockState stateAt(BlockPos position) {
		hasChunk(position.getX(), position.getZ());
		return level.getBlockState(position);
	}

	private boolean hasChunk(int x, int z) {
		long key = ((long) (x >> 4) << 32) | ((z >> 4) & 0xffffffffL);
		return sampledChunks.computeIfAbsent(key, ignored -> {
			sampledRevisions.sample(x, z);
			return level.hasChunk(x >> 4, z >> 4);
		});
	}

	@FunctionalInterface
	interface RevisionReader {
		long read(BlockPos center, int radius);
	}

	static final class SampledRevisions {
		private final RevisionReader reader;
		private final Map<Long, Long> revisions = new HashMap<>();

		SampledRevisions(RevisionReader reader) {
			this.reader = Objects.requireNonNull(reader);
		}

		void sample(int x, int z) {
			long key = ((long) (x >> 4) << 32) | ((z >> 4) & 0xffffffffL);
			revisions.computeIfAbsent(key, ignored -> read(key));
		}

		boolean isCurrent() {
			for (Map.Entry<Long, Long> entry : revisions.entrySet()) {
				if (read(entry.getKey()) != entry.getValue()) return false;
			}
			return true;
		}

		private long read(long key) {
			BlockPos center = new BlockPos(((int) (key >> 32) << 4) + 8, 0, ((int) key << 4) + 8);
			// Include neighboring blocks whose moving collision can enter a sampled chunk.
			return reader.read(center, 9);
		}
	}

	@Override
	public TraversalType traversalAt(GridPosition position) {
		TraversalType normal = WalkabilityView.super.traversalAt(position);
		if (normal != null) return normal;
		if (cellAt(position) != Cell.CLEAR || cellAt(position.below()) != Cell.SAFE_SUPPORT
				|| cellAt(position.above()) == Cell.UNLOADED || cellAt(position.above()) == Cell.HAZARD
				|| cellAt(position.above()) == Cell.WATER) return null;
		BlockPos head = new BlockPos(position.x(), position.y() + 1, position.z());
		return crouchClearance(stateAt(head).getCollisionShape(level, head)) ? TraversalType.CROUCH : null;
	}

	static boolean crouchClearance(VoxelShape headCollision) {
		for (AABB box : headCollision.toAabbs()) {
			if (box.maxX > 0.2D && box.minX < 0.8D && box.maxZ > 0.2D && box.minZ < 0.8D
					&& box.maxY > 0.0D && box.minY < 0.5D) return false;
		}
		return true;
	}

	Direction climbDirection(GridPosition position) {
		BlockPos block = new BlockPos(position.x(), position.y(), position.z());
		if (cellAt(position) != Cell.CLIMBABLE) return null;
		BlockState state = stateAt(block);
		if (state.hasProperty(BlockStateProperties.HORIZONTAL_FACING)) {
			return state.getValue(BlockStateProperties.HORIZONTAL_FACING).getOpposite();
		}
		for (Direction direction : HORIZONTAL_DIRECTIONS) {
			GridPosition adjacent = position.offset(direction.getStepX(), 0, direction.getStepZ());
			if (cellAt(adjacent) == Cell.SAFE_SUPPORT) return direction;
		}
		return null;
	}

	@Override
	public Cell cellAt(GridPosition position) {
		Objects.requireNonNull(position, "position must not be null");
		Cell cached = cells.get(position);
		if (cached != null) {
			cacheHits++;
			return cached;
		}
		cacheMisses++;
		if (level.isOutsideBuildHeight(position.y())
				|| !hasChunk(position.x(), position.z())) {
			cells.put(position, Cell.UNLOADED);
			shallowWater.put(position, false);
			return Cell.UNLOADED;
		}
		BlockPos blockPosition = new BlockPos(position.x(), position.y(), position.z());
		BlockState state = stateAt(blockPosition);
		VoxelShape collision = state.getCollisionShape(level, blockPosition);
		boolean standingWater = state.is(Blocks.WATER) && isOneBlockDeepWater(blockPosition);
		Cell cell = classifyCell(state, collision, standingWater);
		cells.put(position, cell);
		shallowWater.put(position, standingWater);
		return cell;
	}

	boolean isShallowWater(GridPosition position) {
		Objects.requireNonNull(position, "position must not be null");
		cellAt(position);
		return shallowWater.getOrDefault(position, false);
	}

	CacheMetrics cacheMetrics() {
		return new CacheMetrics(cacheHits, cacheMisses, cells.size());
	}

	record CacheMetrics(long hits, long misses, int cachedCells) { }

	double supportHeight(GridPosition feetPosition, double worldX, double worldZ) {
		Objects.requireNonNull(feetPosition, "feetPosition must not be null");
		BlockPos supportPosition = new BlockPos(
				feetPosition.x(),
				feetPosition.y() - 1,
				feetPosition.z()
		);
		if (level.isOutsideBuildHeight(supportPosition.getY())
				|| !hasChunk(supportPosition.getX(), supportPosition.getZ())) {
			return Double.NaN;
		}
		double localX = worldX - supportPosition.getX();
		double localZ = worldZ - supportPosition.getZ();
		BlockState support = stateAt(supportPosition);
		double collisionHeight = collisionHeightAt(
				support.getCollisionShape(level, supportPosition),
				localX,
				localZ
		);
		return Double.isFinite(collisionHeight)
				? supportPosition.getY() + collisionHeight
				: Double.NaN;
	}

	static double collisionHeightAt(VoxelShape collision, double localX, double localZ) {
		Objects.requireNonNull(collision, "collision must not be null");
		if (!Double.isFinite(localX) || !Double.isFinite(localZ)
				|| localX < 0.0D || localX >= 1.0D
				|| localZ < 0.0D || localZ >= 1.0D) {
			return Double.NaN;
		}
		double height = Double.NaN;
		for (AABB box : collision.toAabbs()) {
			if (localX < box.minX || localX > box.maxX
					|| localZ < box.minZ || localZ > box.maxZ) continue;
			height = Double.isNaN(height) ? box.maxY : Math.max(height, box.maxY);
		}
		return height;
	}

	static Cell classifyCell(BlockState state, VoxelShape collision, boolean standingWater) {
		Objects.requireNonNull(state, "state must not be null");
		Objects.requireNonNull(collision, "collision must not be null");
		if (isIntrinsicHazard(state)) return Cell.HAZARD;
		if (!state.getFluidState().isEmpty()) {
			if ((!state.is(Blocks.WATER) && !state.getFluidState().is(FluidTags.WATER)) || !collision.isEmpty()) return Cell.HAZARD;
			return standingWater ? Cell.CLEAR : Cell.WATER;
		}
		if (state.is(BlockTags.CLIMBABLE)) return Cell.CLIMBABLE;
		if (isOpenDoor(state)) return Cell.CLEAR;
		if (collision.isEmpty()) return Cell.CLEAR;
		if (Block.isShapeFullBlock(collision) || supportsPlayerFootprint(collision)) return Cell.SAFE_SUPPORT;
		return Cell.BLOCKED;
	}

	private boolean isOneBlockDeepWater(BlockPos position) {
		if (level.isOutsideBuildHeight(position.getY()) || level.isOutsideBuildHeight(position.getY() - 1)
				|| !hasChunk(position.getX(), position.getZ())) return false;
		BlockState water = stateAt(position);
		if (!water.getFluidState().is(FluidTags.WATER)
				|| !water.getCollisionShape(level, position).isEmpty()) return false;
		BlockPos abovePosition = position.above();
		BlockState above = stateAt(abovePosition);
		if (!above.getFluidState().isEmpty() || !above.getCollisionShape(level, abovePosition).isEmpty()) return false;
		BlockPos supportPosition = position.below();
		BlockState support = stateAt(supportPosition);
		return classifyCell(support, support.getCollisionShape(level, supportPosition), false) == Cell.SAFE_SUPPORT;
	}

	private static boolean supportsPlayerFootprint(VoxelShape collision) {
		java.util.List<AABB> boxes = collision.toAabbs();
		for (double x : FOOTPRINT_SAMPLES) {
			for (double z : FOOTPRINT_SAMPLES) {
				double height = 0.0D;
				for (AABB box : boxes) {
					if (x >= box.minX && x <= box.maxX && z >= box.minZ && z <= box.maxZ) height = Math.max(height, box.maxY);
				}
				if (height <= 0.0D || height > 1.0D) return false;
			}
		}
		return true;
	}

	private static boolean isOpenDoor(BlockState state) {
		String path = BuiltInRegistries.BLOCK.getKey(state.getBlock()).getPath();
		return path.endsWith("_door")
				&& !path.endsWith("_trapdoor")
				&& state.hasProperty(BlockStateProperties.OPEN)
				&& state.getValue(BlockStateProperties.OPEN);
	}

	private static boolean isIntrinsicHazard(BlockState state) {
		return state.is(Blocks.FIRE)
				|| state.is(Blocks.SOUL_FIRE)
				|| state.is(Blocks.CACTUS)
				|| state.is(Blocks.MAGMA_BLOCK)
				|| state.is(Blocks.CAMPFIRE)
				|| state.is(Blocks.SOUL_CAMPFIRE)
				|| state.is(Blocks.SWEET_BERRY_BUSH)
				|| state.is(Blocks.WITHER_ROSE)
				|| state.is(Blocks.POWDER_SNOW);
	}
}
