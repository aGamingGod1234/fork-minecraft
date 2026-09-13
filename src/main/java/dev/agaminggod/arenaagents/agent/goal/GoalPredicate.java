package dev.agaminggod.arenaagents.agent.goal;

import dev.agaminggod.arenaagents.agent.AgentDomainException;
import dev.agaminggod.arenaagents.protocol.ProtocolConstants;
import java.util.List;
import java.util.Map;
import java.util.Objects;

public sealed interface GoalPredicate permits
		GoalPredicate.InventoryContains,
		GoalPredicate.InventoryContainsAny,
		GoalPredicate.PositionWithin,
		GoalPredicate.AdvancementGranted,
		GoalPredicate.EntityKilledByAgent,
		GoalPredicate.BlockMatches,
		GoalPredicate.SurviveDuration,
		GoalPredicate.OperatorConfirmed,
		GoalPredicate.AllOf,
		GoalPredicate.AnyOf {
	String DEFAULT_DIMENSION = "minecraft:overworld";
	int MAX_INVENTORY_ITEM_IDS = 64;

	record InventoryContains(String itemId, int count) implements GoalPredicate {
		public InventoryContains {
			itemId = identifier(itemId, "itemId");
			if (count <= 0) throw invalid("Inventory count must be positive");
		}
	}

	record InventoryContainsAny(List<String> itemIds, int count) implements GoalPredicate {
		public InventoryContainsAny {
			Objects.requireNonNull(itemIds, "itemIds must not be null");
			if (itemIds.isEmpty() || itemIds.size() > MAX_INVENTORY_ITEM_IDS) {
				throw invalid("Inventory item IDs must contain between 1 and " + MAX_INVENTORY_ITEM_IDS + " entries");
			}
			itemIds = itemIds.stream().map(value -> identifier(value, "itemIds entry")).toList();
			if (new java.util.HashSet<>(itemIds).size() != itemIds.size()) throw invalid("Inventory item IDs must be unique");
			if (count <= 0) throw invalid("Inventory count must be positive");
		}
	}

	record PositionWithin(String dimensionId, double x, double y, double z, double radius, int stableTicks) implements GoalPredicate {
		public PositionWithin(double x, double y, double z, double radius, int stableTicks) {
			this(DEFAULT_DIMENSION, x, y, z, radius, stableTicks);
		}

		public PositionWithin {
			dimensionId = identifier(dimensionId, "dimensionId");
			if (!MinecraftCoordinateBounds.isReachable(x, y, z)
					|| !Double.isFinite(radius) || radius < ProtocolConstants.MIN_MOVEMENT_TOLERANCE) {
				throw invalid("Position values must be reachable Minecraft coordinates and radius must be at least "
						+ ProtocolConstants.MIN_MOVEMENT_TOLERANCE);
			}
			if (stableTicks <= 0) throw invalid("Stable ticks must be positive");
		}
	}

	record AdvancementGranted(String advancementId) implements GoalPredicate {
		public AdvancementGranted {
			advancementId = identifier(advancementId, "advancementId");
		}
	}

	record EntityKilledByAgent(String entityType, boolean afterGoalStart) implements GoalPredicate {
		public EntityKilledByAgent {
			entityType = identifier(entityType, "entityType");
		}
	}

	record BlockMatches(String dimensionId, int x, int y, int z, String blockId, Map<String, String> properties) implements GoalPredicate {
		public BlockMatches(int x, int y, int z, String blockId, Map<String, String> properties) {
			this(DEFAULT_DIMENSION, x, y, z, blockId, properties);
		}

		public BlockMatches {
			dimensionId = identifier(dimensionId, "dimensionId");
			blockId = identifier(blockId, "blockId");
			if (!MinecraftCoordinateBounds.isReachable(x, y, z)) {
				throw invalid("Block coordinates are outside Minecraft's reachable world bounds");
			}
			properties = Map.copyOf(Objects.requireNonNull(properties, "properties must not be null"));
			if (properties.size() > 16) throw invalid("Block property limit exceeded");
			for (Map.Entry<String, String> entry : properties.entrySet()) {
				text(entry.getKey(), "property name", 64);
				text(entry.getValue(), "property value", 128);
			}
		}
	}

	record SurviveDuration(long ticks) implements GoalPredicate {
		public SurviveDuration {
			if (ticks <= 0L) throw invalid("Survival duration must be positive");
		}
	}

	record OperatorConfirmed() implements GoalPredicate {
	}

	record AllOf(List<GoalPredicate> predicates) implements GoalPredicate {
		public AllOf {
			predicates = predicateList(predicates);
		}
	}

	record AnyOf(List<GoalPredicate> predicates) implements GoalPredicate {
		public AnyOf {
			predicates = predicateList(predicates);
		}
	}

	private static List<GoalPredicate> predicateList(List<GoalPredicate> predicates) {
		List<GoalPredicate> copy = List.copyOf(Objects.requireNonNull(predicates, "predicates must not be null"));
		if (copy.isEmpty()) throw invalid("Compound predicates must not be empty");
		copy.forEach(value -> Objects.requireNonNull(value, "predicate must not be null"));
		return copy;
	}

	private static String identifier(String value, String field) {
		String checked = text(value, field, 256);
		if (!checked.matches("[a-z0-9_.-]+:[a-z0-9_./-]+")) {
			throw invalid(field + " must be a namespaced identifier");
		}
		return checked;
	}

	private static String text(String value, String field, int maximumLength) {
		String checked = Objects.requireNonNull(value, field + " must not be null").strip();
		if (checked.isEmpty() || checked.length() > maximumLength) {
			throw invalid(field + " has an invalid length");
		}
		return checked;
	}

	private static AgentDomainException invalid(String message) {
		return new AgentDomainException("INVALID_GOAL_PREDICATE", message);
	}
}
