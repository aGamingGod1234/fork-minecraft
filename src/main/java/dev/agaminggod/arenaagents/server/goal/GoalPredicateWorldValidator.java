package dev.agaminggod.arenaagents.server.goal;

import dev.agaminggod.arenaagents.agent.AgentDomainException;
import dev.agaminggod.arenaagents.agent.goal.GoalPredicate;
import java.util.Map;
import java.util.Objects;
import java.util.function.IntPredicate;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.properties.Property;

/** Binds translated spatial predicates to their request world and validates live-world constraints. */
public final class GoalPredicateWorldValidator {
	private GoalPredicateWorldValidator() {
	}

	public static GoalPredicate bindToDimension(GoalPredicate predicate, String dimensionId) {
		Objects.requireNonNull(predicate, "predicate must not be null");
		Objects.requireNonNull(dimensionId, "dimensionId must not be null");
		return switch (predicate) {
			case GoalPredicate.PositionWithin value -> new GoalPredicate.PositionWithin(
					dimensionId, value.x(), value.y(), value.z(), value.radius(), value.stableTicks());
			case GoalPredicate.BlockMatches value -> new GoalPredicate.BlockMatches(
					dimensionId, value.x(), value.y(), value.z(), value.blockId(), value.properties());
			case GoalPredicate.AllOf value -> new GoalPredicate.AllOf(
					value.predicates().stream().map(child -> bindToDimension(child, dimensionId)).toList());
			case GoalPredicate.AnyOf value -> new GoalPredicate.AnyOf(
					value.predicates().stream().map(child -> bindToDimension(child, dimensionId)).toList());
			default -> predicate;
		};
	}

	public static boolean requiresLiveLevel(GoalPredicate predicate) {
		return switch (Objects.requireNonNull(predicate, "predicate must not be null")) {
			case GoalPredicate.PositionWithin ignored -> true;
			case GoalPredicate.BlockMatches ignored -> true;
			case GoalPredicate.AllOf value -> value.predicates().stream().anyMatch(GoalPredicateWorldValidator::requiresLiveLevel);
			case GoalPredicate.AnyOf value -> value.predicates().stream().anyMatch(GoalPredicateWorldValidator::requiresLiveLevel);
			default -> false;
		};
	}

	/** Provider-translated imperative kills can never claim events retained from before activation. */
	public static void validateTranslatedProposal(GoalPredicate predicate) {
		Objects.requireNonNull(predicate, "predicate must not be null");
		switch (predicate) {
			case GoalPredicate.EntityKilledByAgent value -> {
				if (!value.afterGoalStart()) {
					throw invalid("INVALID_GOAL_PREDICATE",
							"Translated kill goals must require attribution after goal activation");
				}
			}
			case GoalPredicate.AllOf value -> value.predicates().forEach(
					GoalPredicateWorldValidator::validateTranslatedProposal);
			case GoalPredicate.AnyOf value -> value.predicates().forEach(
					GoalPredicateWorldValidator::validateTranslatedProposal);
			default -> { }
		}
	}

	public static ServerLevel requireLevel(MinecraftServer server, String dimensionId) {
		Objects.requireNonNull(server, "server must not be null");
		Objects.requireNonNull(dimensionId, "dimensionId must not be null");
		for (ServerLevel level : server.getAllLevels()) {
			if (dimensionId.equals(level.dimension().identifier().toString())) return level;
		}
		throw invalid("GOAL_DIMENSION_UNAVAILABLE", "The goal's selected dimension is not available: " + dimensionId);
	}

	public static void validate(ServerLevel level, GoalPredicate predicate) {
		Objects.requireNonNull(level, "level must not be null");
		validate(
				level.dimension().identifier().toString(),
				y -> !level.isOutsideBuildHeight(y),
				predicate
		);
		validateLiveBounds(level, predicate);
	}

	/** Revalidates every bound spatial leaf against its currently loaded server dimension. */
	public static void validate(MinecraftServer server, GoalPredicate predicate) {
		Objects.requireNonNull(server, "server must not be null");
		Objects.requireNonNull(predicate, "predicate must not be null");
		switch (predicate) {
			case GoalPredicate.PositionWithin value -> validate(requireLevel(server, value.dimensionId()), value);
			case GoalPredicate.BlockMatches value -> validate(requireLevel(server, value.dimensionId()), value);
			case GoalPredicate.AllOf value -> value.predicates().forEach(child -> validate(server, child));
			case GoalPredicate.AnyOf value -> value.predicates().forEach(child -> validate(server, child));
			default -> { }
		}
	}

	static void validate(String dimensionId, IntPredicate validBuildHeight, GoalPredicate predicate) {
		Objects.requireNonNull(dimensionId, "dimensionId must not be null");
		Objects.requireNonNull(validBuildHeight, "validBuildHeight must not be null");
		Objects.requireNonNull(predicate, "predicate must not be null");
		switch (predicate) {
			case GoalPredicate.PositionWithin value -> {
				requireDimension(dimensionId, value.dimensionId());
				if (!validBuildHeight.test((int) Math.floor(value.y()))) {
					throw invalid("GOAL_COORDINATES_OUT_OF_BUILD_HEIGHT",
							"The requested position is outside the selected dimension's build height");
				}
			}
			case GoalPredicate.BlockMatches value -> {
				requireDimension(dimensionId, value.dimensionId());
				if (!validBuildHeight.test(value.y())) {
					throw invalid("GOAL_COORDINATES_OUT_OF_BUILD_HEIGHT",
							"The requested block is outside the selected dimension's build height");
				}
				validateBlockProperties(value.blockId(), value.properties());
			}
			case GoalPredicate.AllOf value -> value.predicates().forEach(
					child -> validate(dimensionId, validBuildHeight, child));
			case GoalPredicate.AnyOf value -> value.predicates().forEach(
					child -> validate(dimensionId, validBuildHeight, child));
			default -> { }
		}
	}

	public static void validateBlockProperties(String blockId, Map<String, String> properties) {
		Identifier identifier = Identifier.tryParse(blockId);
		if (identifier == null || !BuiltInRegistries.BLOCK.containsKey(identifier)) {
			throw invalid("UNKNOWN_GOAL_IDENTIFIER", "block does not exist on this server");
		}
		Block block = BuiltInRegistries.BLOCK.getValue(identifier);
		for (Map.Entry<String, String> entry : properties.entrySet()) {
			Property<?> property = block.getStateDefinition().getProperty(entry.getKey());
			if (property == null) {
				throw invalid("INVALID_GOAL_BLOCK_PROPERTY",
						"Unknown property " + entry.getKey() + " for " + blockId);
			}
			if (property.getValue(entry.getValue()).isEmpty()) {
				throw invalid("INVALID_GOAL_BLOCK_PROPERTY_VALUE",
						"Invalid value " + entry.getValue() + " for property " + entry.getKey());
			}
		}
	}

	private static void requireDimension(String selected, String proposed) {
		if (!selected.equals(proposed)) {
			throw invalid("GOAL_DIMENSION_MISMATCH", "Spatial goal does not match the selected dimension");
		}
	}

	private static void validateLiveBounds(ServerLevel level, GoalPredicate predicate) {
		switch (predicate) {
			case GoalPredicate.PositionWithin value -> requireWorldBounds(
					level, BlockPos.containing(value.x(), value.y(), value.z()), "position");
			case GoalPredicate.BlockMatches value -> requireWorldBounds(
					level, new BlockPos(value.x(), value.y(), value.z()), "block");
			case GoalPredicate.AllOf value -> value.predicates().forEach(child -> validateLiveBounds(level, child));
			case GoalPredicate.AnyOf value -> value.predicates().forEach(child -> validateLiveBounds(level, child));
			default -> { }
		}
	}

	private static void requireWorldBounds(ServerLevel level, BlockPos position, String label) {
		if (!level.isInWorldBounds(position)) {
			throw invalid("GOAL_COORDINATES_OUT_OF_BUILD_HEIGHT",
					"The requested " + label + " is outside the selected dimension's live world bounds");
		}
	}

	private static AgentDomainException invalid(String code, String message) {
		return new AgentDomainException(code, message);
	}
}
