package dev.agaminggod.arenaagents.scenario.runtime;

import dev.agaminggod.arenaagents.scenario.ScenarioCategory;
import dev.agaminggod.arenaagents.scenario.ScenarioPreset;
import dev.agaminggod.arenaagents.scenario.ScenarioSpawnLayout;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Deterministic, self-contained arena geometry. Changes are applied incrementally by the runtime.
 */
public final class ScenarioArenaBlueprint {
	private final BlockPos origin;
	private final List<Placement> placements;
	private final SiteBounds siteBounds;

	private ScenarioArenaBlueprint(BlockPos origin, List<Placement> placements) {
		this.origin = origin.immutable();
		this.placements = List.copyOf(placements);
		this.siteBounds = SiteBounds.from(origin, placements);
	}

	public static ScenarioArenaBlueprint create(ScenarioPreset preset, BlockPos origin) {
		return create(preset, origin, ScenarioPreset.MAXIMUM_AGENTS);
	}

	public static ScenarioArenaBlueprint create(ScenarioPreset preset, BlockPos origin, int participantCount) {
		Objects.requireNonNull(preset, "preset must not be null");
		preset.validateAgentCount(participantCount);
		ScenarioArenaProfile profile = ScenarioArenaProfile.create(preset.category(), participantCount);
		Builder builder = new Builder(origin);
		switch (preset.category()) {
			case SURVIVAL -> survival(builder);
			case BUILDING -> building(builder, profile);
			case PVP -> pvp(builder, profile);
			case PARKOUR -> parkour(builder, profile);
		}
		builder.spawnStations(preset.category(), profile.spawnSlots());
		builder.spectatorDeck();
		return new ScenarioArenaBlueprint(origin, builder.placements);
	}

	/** Creates a deterministic blueprint from already validated, absolute block placements. */
	public static ScenarioArenaBlueprint fromPlacements(BlockPos origin, List<Placement> placements) {
		Objects.requireNonNull(origin, "origin must not be null");
		Objects.requireNonNull(placements, "placements must not be null");
		if (placements.stream().anyMatch(Objects::isNull)) {
			throw new IllegalArgumentException("placements must not contain null");
		}
		return new ScenarioArenaBlueprint(origin, placements);
	}

	public BlockPos origin() {
		return origin;
	}

	public List<Placement> placements() {
		return placements;
	}

	/** Full footprint. Everything above clearFloorY, including the old surface, is removed before building. */
	public SiteBounds siteBounds() {
		return siteBounds;
	}

	public BlockPos operatorSpawn() {
		return operatorSpawn(origin);
	}

	public static BlockPos operatorSpawn(BlockPos origin) {
		return Objects.requireNonNull(origin, "origin must not be null").offset(0, 15, 70);
	}

	private static void survival(Builder b) {
		b.fill(-64, 64, -1, -1, -64, 64, Blocks.COARSE_DIRT);
		b.fill(-64, 64, 0, 0, -64, 64, Blocks.MOSS_BLOCK);
		b.patch(-38, -34, 18, 12, 0, Blocks.PODZOL);
		b.patch(35, 30, 20, 13, 0, Blocks.ROOTED_DIRT);
		b.patch(-34, 31, 13, 19, 0, Blocks.COARSE_DIRT);
		b.patch(38, -28, 15, 17, 0, Blocks.MUD);
		b.patch(0, 43, 24, 9, 0, Blocks.PACKED_MUD);
		b.annulus(0, 0, 51, 57, 0, Blocks.COARSE_DIRT);
		b.annulus(0, 0, 51, 57, 1, Blocks.AIR);
		b.wall(-64, 64, -64, 64, 1, 5, Blocks.MOSSY_COBBLESTONE);
		b.fill(-64, 64, 0, 0, -4, 4, Blocks.GRAVEL);
		// Keep the river in a trench. A raised water sheet flows into managed AIR
		// positions and can never pass exhaustive verification.
		b.fill(-64, 64, 0, 0, -3, 3, Blocks.WATER);
		for (int bridgeX : new int[]{-38, 0, 38}) {
			b.fill(bridgeX - 3, bridgeX + 3, 1, 1, -5, 5, Blocks.OAK_PLANKS);
			b.lineX(bridgeX - 3, bridgeX + 3, 2, -5, Blocks.OAK_FENCE);
			b.lineX(bridgeX - 3, bridgeX + 3, 2, 5, Blocks.OAK_FENCE);
		}
		for (int x = -52; x <= 52; x += 13) {
			for (int z = -52; z <= 52; z += 17) {
				if (Math.abs(z) < 9 || Math.abs(x) < 10 && Math.abs(z) < 18) continue;
				b.tree(x + Math.floorMod(x + z, 4), z + Math.floorMod(x - z, 3));
			}
		}
		b.ruin(-28, 24, 12, 9);
		b.ruin(25, -30, 10, 12);
		b.fill(-6, 6, 1, 1, 18, 30, Blocks.COBBLESTONE);
		b.fill(-4, 4, 2, 2, 20, 28, Blocks.MOSS_BLOCK);
		b.ring(0, 24, 7, 1, Blocks.COBBLESTONE);
		b.set(0, 1, 24, Blocks.CAMPFIRE);
		for (int i = -2; i <= 2; i++) {
			b.set(47 + i, 1, 42, i % 2 == 0 ? Blocks.IRON_ORE : Blocks.COAL_ORE);
			b.set(-48 + i, 1, -42, i % 2 == 0 ? Blocks.COPPER_ORE : Blocks.COAL_ORE);
		}
		b.fill(-10, 10, 1, 1, -58, -54, Blocks.HAY_BLOCK);
	}

	private static void building(Builder b, ScenarioArenaProfile profile) {
		b.fill(-48, 48, -1, -1, -48, 48, Blocks.STONE);
		b.fill(-48, 48, 0, 0, -48, 48, Blocks.SMOOTH_STONE);
		b.patch(-28, -28, 13, 13, 0, Blocks.CALCITE);
		b.patch(28, 28, 13, 13, 0, Blocks.TUFF_BRICKS);
		b.patch(-28, 28, 13, 13, 0, Blocks.CUT_COPPER);
		b.patch(28, -28, 13, 13, 0, Blocks.POLISHED_ANDESITE);
		b.wall(-48, 48, -48, 48, 1, 4, Blocks.QUARTZ_BRICKS);
		for (ScenarioSpawnLayout.Slot slot : profile.spawnSlots()) {
			b.plot(slot.x(), slot.z(), 18);
		}
		b.fill(-8, 8, 1, 1, -8, 8, Blocks.POLISHED_BLACKSTONE);
		b.ring(0, 0, 9, 2, Blocks.GOLD_BLOCK);
		b.fill(-3, 3, 2, 2, -3, 3, Blocks.QUARTZ_BLOCK);
		b.pillar(-45, -45, 10, Blocks.SEA_LANTERN);
		b.pillar(45, -45, 10, Blocks.SEA_LANTERN);
		b.pillar(-45, 45, 10, Blocks.SEA_LANTERN);
		b.pillar(45, 45, 10, Blocks.SEA_LANTERN);
	}

	private static void pvp(Builder b, ScenarioArenaProfile profile) {
		int radius = Math.max(78, 50 + profile.participantCount() * 2);
		b.fill(-radius, radius, -8, -8, -radius, radius, Blocks.BEDROCK);
		b.fill(-radius, radius, -7, -2, -radius, radius, Blocks.DEEPSLATE);
		b.fill(-radius, radius, -1, -1, -radius, radius, Blocks.DIRT);
		b.fill(-radius, radius, 0, 0, -radius, radius, Blocks.GRASS_BLOCK);
		b.wall(-radius, radius, -radius, radius, -7, 3, Blocks.BEDROCK);
		b.wall(-radius, radius, -radius, radius, 4, 20, Blocks.BARRIER);

		// Survival-Games terrain grammar: a readable cornucopia, four resource-rich
		// biomes, outer ruins, cover, and multiple routes instead of a flat combat box.
		b.patch(-radius / 2, -radius / 2, 22, 19, 0, Blocks.PODZOL);
		b.patch(radius / 2, -radius / 2, 20, 17, 0, Blocks.SAND);
		b.patch(-radius / 2, radius / 2, 20, 18, 0, Blocks.MOSS_BLOCK);
		b.patch(radius / 2, radius / 2, 19, 19, 0, Blocks.COARSE_DIRT);
		b.patch(-radius / 2, -radius / 2, 8, 7, 0, Blocks.MOSSY_STONE_BRICKS);
		b.patch(radius / 2, radius / 2, 7, 8, 0, Blocks.CRACKED_STONE_BRICKS);
		b.patch(-radius / 2, radius / 2, 5, 6, 0, Blocks.POLISHED_BASALT);
		b.fill(-radius + 5, radius - 5, 0, 0, -3, 3, Blocks.WATER);
		for (int bridgeX : new int[]{-36, 0, 36}) {
			b.fill(bridgeX - 3, bridgeX + 3, 1, 1, -4, 4, Blocks.POLISHED_ANDESITE);
		}

		// Cornucopia: open center with sixteen visible loot points and defensible tiers.
		b.annulus(0, 0, 10, 15, 1, Blocks.SMOOTH_STONE);
		b.ring(0, 0, 15, 1, Blocks.POLISHED_BLACKSTONE_BRICKS);
		b.fill(-7, 7, 1, 1, -7, 7, Blocks.CUT_COPPER);
		b.patch(0, 0, 6, 6, 1, Blocks.GOLD_BLOCK);
		b.fill(-5, 5, 2, 2, -2, 2, Blocks.QUARTZ_BRICKS);
		b.fill(-2, 2, 2, 2, -5, 5, Blocks.QUARTZ_BRICKS);
		for (int index = 0; index < 16; index++) {
			double angle = Math.toRadians(index * 22.5D);
			int x = (int) Math.round(Math.cos(angle) * 9.0D);
			int z = (int) Math.round(Math.sin(angle) * 9.0D);
			b.set(x, 2, z, Blocks.CHEST);
		}
		b.set(0, 2, 0, Blocks.BARREL);

		// Outer loot circuits: underground dungeons, watchtowers, woodland cover and mining outcrops.
		for (int[] dungeon : new int[][]{{-42, -35}, {43, -31}, {-39, 40}, {41, 38}, {0, -49}, {0, 49}}) {
			b.dungeon(dungeon[0], dungeon[1]);
		}
		for (int[] tower : new int[][]{{-radius + 9, -radius + 9}, {radius - 9, -radius + 9},
				{-radius + 9, radius - 9}, {radius - 9, radius - 9}}) {
			b.tower(tower[0], tower[1], 7, 14);
			b.set(tower[0], 8, tower[1], Blocks.BARREL);
		}
		for (int x = -radius + 12; x <= radius - 12; x += 13) {
			for (int z = -radius + 12; z <= radius - 12; z += 17) {
				if (x * x + z * z < 24 * 24 || Math.abs(z) < 7) continue;
				if (Math.floorMod(x * 3 + z, 5) <= 2) b.tree(x, z);
			}
		}
		for (int[] cache : new int[][]{{-27, -18}, {28, -17}, {-25, 21}, {26, 20}, {-8, 35}, {10, -36}}) {
			b.house(cache[0], cache[1]);
		}
		b.ring(0, 0, radius - 5, 1, Blocks.RED_NETHER_BRICKS);
	}

	private static void parkour(Builder b, ScenarioArenaProfile profile) {
		int halfWidth = profile.parkourHalfWidth();
		b.fill(-halfWidth, halfWidth, -2, -1, -43, 31, Blocks.DEEPSLATE);
		b.fill(-halfWidth, halfWidth, 0, 0, -43, 31, Blocks.LAVA);
		// Seal the hazard at fluid level. Without this curb, an underwater build lets
		// surrounding water touch the lava before the glass wall begins at y=1.
		b.lineX(-halfWidth, halfWidth, 0, -43, Blocks.POLISHED_BLACKSTONE_BRICKS);
		b.lineX(-halfWidth, halfWidth, 0, 31, Blocks.POLISHED_BLACKSTONE_BRICKS);
		b.lineZ(-42, 30, 0, -halfWidth, Blocks.POLISHED_BLACKSTONE_BRICKS);
		b.lineZ(-42, 30, 0, halfWidth, Blocks.POLISHED_BLACKSTONE_BRICKS);
		b.wall(-halfWidth, halfWidth, -43, 31, 1, 4, Blocks.TINTED_GLASS);
		ScenarioParkourCourse course = ScenarioParkourCourse.create(profile.participantCount());
		for (ScenarioParkourCourse.Lane lane : course.lanes()) {
			for (int index = 0; index < lane.platforms().size(); index++) {
				ScenarioParkourCourse.Platform platform = lane.platforms().get(index);
				if (platform.checkpoint()) {
					int centerX = platform.x() + 1;
					b.ring(centerX, platform.z(), 1, platform.y(), Blocks.POLISHED_BLACKSTONE_BRICKS);
					b.set(centerX, platform.y(), platform.z(),
							index == 0 ? Blocks.SEA_LANTERN : Blocks.YELLOW_GLAZED_TERRACOTTA);
					b.pillar(centerX, platform.z(), 1, platform.y() - 1, Blocks.SEA_LANTERN);
				} else {
					Block block = parkourPlatformBlock(platform.stage(), index, lane.index());
					b.fill(platform.x(), platform.x() + platform.width() - 1,
							platform.y(), platform.y(), platform.z(), platform.z(), block);
				}
			}
		}
		ScenarioParkourCourse.Platform finish = course.lanes().getFirst().platforms().getLast();
		b.fill(profile.minimumX() - 2, profile.maximumX() + 4, finish.y(), finish.y(),
				finish.z(), finish.z() + 4, Blocks.GOLD_BLOCK);
		b.fill(-1, 1, finish.y() + 1, finish.y() + 1,
				finish.z() + 2, finish.z() + 4, Blocks.EMERALD_BLOCK);
		b.set(0, finish.y() + 2, finish.z() + 3, Blocks.BEACON);
	}

	private static Block parkourPlatformBlock(ScenarioParkourCourse.Stage stage, int index, int laneIndex) {
		int variant = Math.floorMod(index + laneIndex, 3);
		return switch (stage) {
			case EASY -> switch (variant) {
				case 0 -> Blocks.SMOOTH_QUARTZ;
				case 1 -> Blocks.QUARTZ_BRICKS;
				default -> Blocks.CALCITE;
			};
			case MEDIUM -> switch (variant) {
				case 0 -> Blocks.PRISMARINE_BRICKS;
				case 1 -> Blocks.DARK_PRISMARINE;
				default -> Blocks.CYAN_CONCRETE;
			};
			case HARD -> switch (variant) {
				case 0 -> Blocks.PURPUR_BLOCK;
				case 1 -> Blocks.END_STONE_BRICKS;
				default -> Blocks.AMETHYST_BLOCK;
			};
			case EXTREME -> switch (variant) {
				case 0 -> Blocks.RED_NETHER_BRICKS;
				case 1 -> Blocks.POLISHED_BLACKSTONE;
				default -> Blocks.MAGENTA_CONCRETE;
			};
		};
	}

	public record Placement(BlockPos position, BlockState state) {
		public Placement {
			position = position.immutable();
			Objects.requireNonNull(state, "state must not be null");
		}
	}

	public record SiteBounds(int minimumX, int maximumX, int clearFloorY, int minimumZ, int maximumZ) {
		public SiteBounds {
			if (minimumX > maximumX || minimumZ > maximumZ) {
				throw new IllegalArgumentException("site bounds are inverted");
			}
		}

		private static SiteBounds from(BlockPos origin, List<Placement> placements) {
			if (placements.isEmpty()) {
				return new SiteBounds(origin.getX(), origin.getX(), origin.getY(), origin.getZ(), origin.getZ());
			}
			int lowestAuthoredY = placements.stream()
					.mapToInt(placement -> placement.position().getY()).min().orElse(origin.getY());
			return new SiteBounds(
					placements.stream().mapToInt(placement -> placement.position().getX()).min().orElseThrow(),
					placements.stream().mapToInt(placement -> placement.position().getX()).max().orElseThrow(),
					Math.subtractExact(lowestAuthoredY, 1),
					placements.stream().mapToInt(placement -> placement.position().getZ()).min().orElseThrow(),
					placements.stream().mapToInt(placement -> placement.position().getZ()).max().orElseThrow()
			);
		}

		public int columnCount() {
			return Math.multiplyExact(maximumX - minimumX + 1, maximumZ - minimumZ + 1);
		}
	}

	private static final class Builder {
		private final BlockPos origin;
		private final ArrayList<Placement> placements = new ArrayList<>();

		private Builder(BlockPos origin) {
			this.origin = origin.immutable();
		}

		private void set(int x, int y, int z, Block block) {
			placements.add(new Placement(origin.offset(x, y, z), block.defaultBlockState()));
		}

		private void set(int x, int y, int z, BlockState state) {
			placements.add(new Placement(origin.offset(x, y, z), state));
		}

		private void fill(int minX, int maxX, int minY, int maxY, int minZ, int maxZ, Block block) {
			for (int y = minY; y <= maxY; y++) {
				for (int x = minX; x <= maxX; x++) {
					for (int z = minZ; z <= maxZ; z++) set(x, y, z, block);
				}
			}
		}

		private void wall(int minX, int maxX, int minZ, int maxZ, int minY, int maxY, Block block) {
			for (int y = minY; y <= maxY; y++) {
				lineX(minX, maxX, y, minZ, block);
				lineX(minX, maxX, y, maxZ, block);
				lineZ(minZ, maxZ, y, minX, block);
				lineZ(minZ, maxZ, y, maxX, block);
			}
		}

		private void lineX(int minX, int maxX, int y, int z, Block block) {
			for (int x = minX; x <= maxX; x++) set(x, y, z, block);
		}

		private void lineZ(int minZ, int maxZ, int y, int x, Block block) {
			for (int z = minZ; z <= maxZ; z++) set(x, y, z, block);
		}

		private void pillar(int x, int z, int height, Block block) {
			pillar(x, z, 1, height, block);
		}

		private void pillar(int x, int z, int top, int bottom, Block block) {
			int min = Math.min(top, bottom);
			int max = Math.max(top, bottom);
			for (int y = min; y <= max; y++) set(x, y, z, block);
		}

		private void ring(int centerX, int centerZ, int radius, int y, Block block) {
			for (int x = -radius; x <= radius; x++) {
				set(centerX + x, y, centerZ - radius, block);
				set(centerX + x, y, centerZ + radius, block);
			}
			for (int z = -radius + 1; z < radius; z++) {
				set(centerX - radius, y, centerZ + z, block);
				set(centerX + radius, y, centerZ + z, block);
			}
		}

		private void annulus(
				int centerX,
				int centerZ,
				int innerRadius,
				int outerRadius,
				int y,
				Block block
		) {
			int innerSquared = innerRadius * innerRadius;
			int outerSquared = outerRadius * outerRadius;
			for (int x = -outerRadius; x <= outerRadius; x++) {
				for (int z = -outerRadius; z <= outerRadius; z++) {
					int distanceSquared = x * x + z * z;
					if (distanceSquared >= innerSquared && distanceSquared <= outerSquared) {
						set(centerX + x, y, centerZ + z, block);
					}
				}
			}
		}

		private void patch(int centerX, int centerZ, int radiusX, int radiusZ, int y, Block block) {
			long radiusSquared = (long) radiusX * radiusX * radiusZ * radiusZ;
			for (int x = -radiusX; x <= radiusX; x++) {
				for (int z = -radiusZ; z <= radiusZ; z++) {
					long scaled = (long) x * x * radiusZ * radiusZ + (long) z * z * radiusX * radiusX;
					if (scaled <= radiusSquared) set(centerX + x, y, centerZ + z, block);
				}
			}
		}

		private void spawnStations(ScenarioCategory category, List<ScenarioSpawnLayout.Slot> slots) {
			if (category == ScenarioCategory.PARKOUR) return;
			for (ScenarioSpawnLayout.Slot slot : slots) {
				Block pad = switch (category) {
					case SURVIVAL -> (slot.index() & 1) == 0 ? Blocks.SPRUCE_PLANKS : Blocks.MUD_BRICKS;
					case BUILDING -> (slot.index() & 1) == 0 ? Blocks.CUT_COPPER : Blocks.QUARTZ_BRICKS;
					case PVP -> (slot.index() & 1) == 0 ? Blocks.RED_CONCRETE : Blocks.BLUE_CONCRETE;
					case PARKOUR -> (slot.index() & 1) == 0 ? Blocks.CYAN_CONCRETE : Blocks.MAGENTA_CONCRETE;
				};
				int radius = category == ScenarioCategory.PARKOUR ? 1 : 2;
				fill(slot.x() - radius, slot.x() + radius, slot.floorY(), slot.floorY(),
						slot.z() - radius, slot.z() + radius, pad);
				ring(slot.x(), slot.z(), radius, slot.floorY(), Blocks.POLISHED_BLACKSTONE_BRICKS);
				set(slot.x(), slot.floorY(), slot.z(), Blocks.SEA_LANTERN);
			}
		}

		private void tree(int x, int z) {
			for (int y = 1; y <= 5; y++) set(x, y, z, Blocks.OAK_LOG);
			for (int y = 4; y <= 7; y++) {
				int radius = y == 7 ? 1 : 2;
				for (int dx = -radius; dx <= radius; dx++) {
					for (int dz = -radius; dz <= radius; dz++) {
						if (Math.abs(dx) + Math.abs(dz) <= radius + 1) {
							set(x + dx, y, z + dz, Blocks.OAK_LEAVES.defaultBlockState()
									.setValue(BlockStateProperties.PERSISTENT, true));
						}
					}
				}
			}
		}

		private void ruin(int centerX, int centerZ, int width, int depth) {
			fill(centerX - width / 2, centerX + width / 2, 1, 1, centerZ - depth / 2, centerZ + depth / 2,
					Blocks.COBBLESTONE);
			for (int y = 2; y <= 6; y++) {
				lineX(centerX - width / 2, centerX + width / 2, y, centerZ - depth / 2, Blocks.STONE_BRICKS);
				lineZ(centerZ - depth / 2, centerZ + depth / 2, y, centerX - width / 2, Blocks.MOSSY_STONE_BRICKS);
			}
		}

		private void house(int centerX, int centerZ) {
			fill(centerX - 3, centerX + 3, 1, 1, centerZ - 3, centerZ + 3, Blocks.SPRUCE_PLANKS);
			for (int y = 2; y <= 4; y++) ring(centerX, centerZ, 3, y, Blocks.COBBLED_DEEPSLATE);
			fill(centerX - 3, centerX + 3, 5, 5, centerZ - 3, centerZ + 3, Blocks.COBBLED_DEEPSLATE);
			set(centerX - 3, 3, centerZ, Blocks.GLASS_PANE);
			set(centerX + 3, 3, centerZ, Blocks.GLASS_PANE);
			set(centerX, 3, centerZ - 3, Blocks.GLASS_PANE);
			BlockState lowerDoor = Blocks.OAK_DOOR.defaultBlockState()
					.setValue(BlockStateProperties.HORIZONTAL_FACING, Direction.NORTH)
					.setValue(BlockStateProperties.DOUBLE_BLOCK_HALF, DoubleBlockHalf.LOWER);
			BlockState upperDoor = lowerDoor.setValue(BlockStateProperties.DOUBLE_BLOCK_HALF, DoubleBlockHalf.UPPER);
			set(centerX, 2, centerZ + 3, lowerDoor);
			set(centerX, 3, centerZ + 3, upperDoor);
			set(centerX, 2, centerZ, Blocks.CHEST);
		}

		private void dungeon(int centerX, int centerZ) {
			fill(centerX - 3, centerX + 3, -6, -6, centerZ - 3, centerZ + 3, Blocks.COBBLESTONE);
			for (int y = -5; y <= -2; y++) ring(centerX, centerZ, 3, y, Blocks.COBBLESTONE);
			fill(centerX - 3, centerX + 3, -1, -1, centerZ - 3, centerZ + 3, Blocks.COBBLESTONE);
			set(centerX, -5, centerZ, Blocks.CHEST);
			Direction towardCenter;
			if (Math.abs(centerX) >= Math.abs(centerZ) && centerX != 0) {
				towardCenter = centerX > 0 ? Direction.WEST : Direction.EAST;
			} else {
				towardCenter = centerZ > 0 ? Direction.NORTH : Direction.SOUTH;
			}
			BlockState stairs = Blocks.COBBLESTONE_STAIRS.defaultBlockState()
					.setValue(BlockStateProperties.HORIZONTAL_FACING, towardCenter);
			for (int step = 0; step < 5; step++) {
				int distance = 5 - step;
				int x = centerX + towardCenter.getStepX() * distance;
				int z = centerZ + towardCenter.getStepZ() * distance;
				int y = -step;
				set(x, y, z, stairs);
				set(x, y + 1, z, Blocks.AIR);
				set(x, y + 2, z, Blocks.AIR);
			}
		}

		private void plot(int centerX, int centerZ, int size) {
			int half = size / 2;
			fill(centerX - half, centerX + half, 0, 0, centerZ - half, centerZ + half, Blocks.WHITE_CONCRETE);
			ring(centerX, centerZ, half + 1, 0, Blocks.LIGHT_GRAY_CONCRETE);
			set(centerX, 0, centerZ, Blocks.SEA_LANTERN);
		}

		private void tower(int centerX, int centerZ, int radius, int height) {
			for (int y = 1; y <= height; y++) ring(centerX, centerZ, radius, y, Blocks.DEEPSLATE_BRICKS);
			fill(centerX - radius + 1, centerX + radius - 1, height, height,
					centerZ - radius + 1, centerZ + radius - 1, Blocks.POLISHED_BLACKSTONE);
			for (int y = 2; y < height; y++) set(centerX, y, centerZ, Blocks.LADDER);
		}

		private void spectatorDeck() {
			fill(-3, 3, 14, 14, 31, 66, Blocks.POLISHED_BLACKSTONE_BRICKS);
			lineZ(31, 66, 14, 0, Blocks.CUT_COPPER);
			lineZ(31, 66, 15, -3, Blocks.CYAN_STAINED_GLASS);
			lineZ(31, 66, 15, 3, Blocks.CYAN_STAINED_GLASS);
			for (int z = 34; z <= 64; z += 6) {
				set(-2, 14, z, Blocks.SEA_LANTERN);
				set(2, 14, z, Blocks.SEA_LANTERN);
			}
			fill(-7, 7, 14, 14, 66, 74, Blocks.POLISHED_BLACKSTONE_BRICKS);
			fill(-5, 5, 14, 14, 67, 73, Blocks.SMOOTH_QUARTZ);
			lineX(-7, 7, 14, 66, Blocks.CUT_COPPER);
			lineX(-7, 7, 14, 74, Blocks.CUT_COPPER);
			for (int x = -7; x <= 7; x++) {
				set(x, 15, 66, Blocks.CYAN_STAINED_GLASS);
				set(x, 15, 74, Blocks.CYAN_STAINED_GLASS);
			}
			for (int z = 67; z < 74; z++) {
				set(-7, 15, z, Blocks.CYAN_STAINED_GLASS);
				set(7, 15, z, Blocks.CYAN_STAINED_GLASS);
			}
			fill(-1, 1, 14, 14, 69, 71, Blocks.SEA_LANTERN);
		}
	}
}
