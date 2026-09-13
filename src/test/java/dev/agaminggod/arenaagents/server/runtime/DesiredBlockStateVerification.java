package dev.agaminggod.arenaagents.server.runtime;

import java.util.Map;
import net.minecraft.core.Direction;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;

public final class DesiredBlockStateVerification {
	private static final String OAK_STAIRS_STATE =
			"minecraft:oak_stairs[facing=north,half=bottom,shape=straight,waterlogged=false]";

	private DesiredBlockStateVerification() {
	}

	public static int verify() {
		net.minecraft.SharedConstants.tryDetectVersion();
		net.minecraft.server.Bootstrap.bootStrap();
		DesiredBlockState parsed = DesiredBlockState.parse(OAK_STAIRS_STATE, "minecraft:oak_stairs");
		assertEquals("minecraft:oak_stairs", parsed.blockId(), "desired state retains the block id");
		assertEquals(
				Map.of("facing", "north", "half", "bottom", "shape", "straight", "waterlogged", "false"),
				parsed.properties(),
				"desired state retains all requested properties"
		);
		assertThrows(
				() -> DesiredBlockState.parse("minecraft:oak_stairs[facing=north,facing=south]", "minecraft:oak_stairs"),
				"duplicate properties are rejected"
		);
		assertThrows(
				() -> DesiredBlockState.parse("minecraft:oak_stairs[not_a_property=true]", "minecraft:oak_stairs"),
				"unknown properties are rejected"
		);
		assertThrows(
				() -> DesiredBlockState.parse("minecraft:oak_stairs[facing=up]", "minecraft:oak_stairs"),
				"invalid property values are rejected"
		);
		assertThrows(
				() -> DesiredBlockState.parse("minecraft:stone", "minecraft:oak_stairs"),
				"desired state block id must match the item-derived block id"
		);

		BlockState correct = Blocks.OAK_STAIRS.defaultBlockState()
				.setValue(BlockStateProperties.HORIZONTAL_FACING, Direction.NORTH)
				.setValue(BlockStateProperties.HALF, net.minecraft.world.level.block.state.properties.Half.BOTTOM)
				.setValue(BlockStateProperties.STAIRS_SHAPE, net.minecraft.world.level.block.state.properties.StairsShape.STRAIGHT)
				.setValue(BlockStateProperties.WATERLOGGED, false);
		BlockState wrongFacing = correct.setValue(BlockStateProperties.HORIZONTAL_FACING, Direction.SOUTH);
		assertTrue(parsed.matches(correct), "matching stable properties are accepted");
		assertFalse(parsed.matches(wrongFacing), "wrong facing is rejected");
		assertFalse(parsed.matches(Blocks.STONE.defaultBlockState()), "wrong block id is rejected");
		assertThrows(
				() -> parsed.properties().put("facing", "south"),
				"desired property map is immutable"
		);
		return 11;
	}

	private static void assertEquals(Object expected, Object actual, String label) {
		if (!expected.equals(actual)) {
			throw new AssertionError(label + ": expected <" + expected + "> but was <" + actual + ">");
		}
	}

	private static void assertTrue(boolean value, String label) {
		if (!value) throw new AssertionError(label);
	}

	private static void assertFalse(boolean value, String label) {
		if (value) throw new AssertionError(label);
	}

	private static void assertThrows(Runnable action, String label) {
		try {
			action.run();
		} catch (RuntimeException expected) {
			return;
		}
		throw new AssertionError(label + " did not throw");
	}
}
