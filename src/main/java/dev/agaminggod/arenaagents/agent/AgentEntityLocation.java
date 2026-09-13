package dev.agaminggod.arenaagents.agent;

import java.util.Objects;
import java.util.OptionalDouble;
import java.util.OptionalInt;

public record AgentEntityLocation(
		String dimension,
		int chunkX,
		int chunkZ,
		OptionalInt blockY,
		OptionalDouble exactX,
		OptionalDouble exactY,
		OptionalDouble exactZ,
		OptionalDouble yaw,
		OptionalDouble pitch
) {
	private static final int MAX_DIMENSION_IDENTIFIER_LENGTH = 256;

	public AgentEntityLocation {
		dimension = Objects.requireNonNull(dimension, "dimension must not be null").trim();
		blockY = Objects.requireNonNull(blockY, "blockY must not be null");
		exactX = Objects.requireNonNull(exactX, "exactX must not be null");
		exactY = Objects.requireNonNull(exactY, "exactY must not be null");
		exactZ = Objects.requireNonNull(exactZ, "exactZ must not be null");
		yaw = Objects.requireNonNull(yaw, "yaw must not be null");
		pitch = Objects.requireNonNull(pitch, "pitch must not be null");
		if (dimension.isEmpty() || dimension.length() > MAX_DIMENSION_IDENTIFIER_LENGTH) {
			throw new AgentDomainException(
					"INVALID_ENTITY_DIMENSION",
					"dimension must contain between 1 and " + MAX_DIMENSION_IDENTIFIER_LENGTH + " characters"
			);
		}
		int exactPositionParts = (exactX.isPresent() ? 1 : 0)
				+ (exactY.isPresent() ? 1 : 0)
				+ (exactZ.isPresent() ? 1 : 0);
		if (exactPositionParts != 0 && exactPositionParts != 3) {
			throw new AgentDomainException(
					"INVALID_ENTITY_POSITION",
					"exact entity position must contain X, Y, and Z together"
			);
		}
		if (yaw.isPresent() != pitch.isPresent()) {
			throw new AgentDomainException(
					"INVALID_ENTITY_VIEW",
					"entity view must contain yaw and pitch together"
			);
		}
		validateFinite(exactX, "exact X");
		validateFinite(exactY, "exact Y");
		validateFinite(exactZ, "exact Z");
		validateFinite(yaw, "yaw");
		validateFinite(pitch, "pitch");
		if (exactX.isPresent()) {
			int exactBlockX = blockCoordinate(exactX.getAsDouble(), "exact X");
			int exactBlockY = blockCoordinate(exactY.getAsDouble(), "exact Y");
			int exactBlockZ = blockCoordinate(exactZ.getAsDouble(), "exact Z");
			if ((exactBlockX >> 4) != chunkX || (exactBlockZ >> 4) != chunkZ) {
				throw new AgentDomainException(
						"INVALID_ENTITY_POSITION",
						"exact entity position must be inside its saved chunk"
				);
			}
			if (blockY.isPresent() && exactBlockY != blockY.getAsInt()) {
				throw new AgentDomainException(
						"INVALID_ENTITY_POSITION",
						"exact entity Y must match its saved block Y"
				);
			}
		}
	}

	public AgentEntityLocation(String dimension, int chunkX, int chunkZ) {
		this(dimension, chunkX, chunkZ, OptionalInt.empty());
	}

	public AgentEntityLocation(String dimension, int chunkX, int chunkZ, OptionalInt blockY) {
		this(
				dimension, chunkX, chunkZ, blockY,
				OptionalDouble.empty(), OptionalDouble.empty(), OptionalDouble.empty(),
				OptionalDouble.empty(), OptionalDouble.empty()
		);
	}

	public static AgentEntityLocation exact(
			String dimension,
			int chunkX,
			int chunkZ,
			double x,
			double y,
			double z,
			float yaw,
			float pitch
	) {
		return new AgentEntityLocation(
				dimension,
				chunkX,
				chunkZ,
				OptionalInt.of((int) Math.floor(y)),
				OptionalDouble.of(x),
				OptionalDouble.of(y),
				OptionalDouble.of(z),
				OptionalDouble.of(yaw),
				OptionalDouble.of(pitch)
		);
	}

	public boolean hasExactPosition() {
		return exactX.isPresent();
	}

	public boolean hasView() {
		return yaw.isPresent();
	}

	private static void validateFinite(OptionalDouble value, String label) {
		if (value.isPresent() && !Double.isFinite(value.getAsDouble())) {
			throw new AgentDomainException("INVALID_ENTITY_LOCATION", label + " must be finite");
		}
	}

	private static int blockCoordinate(double value, String label) {
		double floored = Math.floor(value);
		if (floored < Integer.MIN_VALUE || floored > Integer.MAX_VALUE) {
			throw new AgentDomainException("INVALID_ENTITY_LOCATION", label + " is outside the supported world range");
		}
		return (int) floored;
	}
}
