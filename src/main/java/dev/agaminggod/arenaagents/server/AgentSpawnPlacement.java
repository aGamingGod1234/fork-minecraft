package dev.agaminggod.arenaagents.server;

import dev.agaminggod.arenaagents.agent.AgentDomainException;
import dev.agaminggod.arenaagents.agent.CodexAgentEntity;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

final class AgentSpawnPlacement {
	static final double SPAWN_DISTANCE = 2.0D;
	private static final double[] FORWARD_DISTANCES = {2.0D, 4.0D, 6.0D, 8.0D};
	private static final double[] LATERAL_OFFSETS = {0.0D, 2.0D, -2.0D, 4.0D, -4.0D};
	private static final double MIN_HORIZONTAL_LENGTH_SQUARED = 1.0E-6D;
	private static final double AGENT_WIDTH = 1.0D;
	private static final double AGENT_HEIGHT = 1.8D;
	private static final double AGENT_HALF_HEIGHT = AGENT_HEIGHT / 2.0D;
	private static final double AGENT_CLEARANCE = 0.25D;
	private static final Vec3 DEFAULT_FORWARD = new Vec3(0.0D, 0.0D, 1.0D);

	private AgentSpawnPlacement() {
	}

	static Vec3 inFrontOf(Vec3 origin, Vec3 lookDirection) {
		Objects.requireNonNull(origin, "origin must not be null");
		Objects.requireNonNull(lookDirection, "lookDirection must not be null");
		Vec3 forward = horizontalForward(lookDirection);
		return origin.add(forward.scale(SPAWN_DISTANCE));
	}

	static Vec3 availableNear(ServerLevel level, Vec3 origin, Vec3 lookDirection) {
		Objects.requireNonNull(level, "level must not be null");
		for (Vec3 candidate : candidates(origin, lookDirection)) {
			AABB bounds = AABB.ofSize(
					candidate.add(0.0D, AGENT_HALF_HEIGHT, 0.0D),
					AGENT_WIDTH,
					AGENT_HEIGHT,
					AGENT_WIDTH
			);
			if (level.noCollision(bounds)
					&& level.getEntitiesOfClass(CodexAgentEntity.class, bounds.inflate(AGENT_CLEARANCE)).isEmpty()) {
				return candidate;
			}
		}
		throw new AgentDomainException("NO_SPAWN_SPACE", "No clear agent spawn position was found near the player");
	}

	static List<Vec3> candidates(Vec3 origin, Vec3 lookDirection) {
		Objects.requireNonNull(origin, "origin must not be null");
		Vec3 forward = horizontalForward(Objects.requireNonNull(lookDirection, "lookDirection must not be null"));
		Vec3 lateral = new Vec3(-forward.z, 0.0D, forward.x);
		ArrayList<Vec3> candidates = new ArrayList<>(FORWARD_DISTANCES.length * LATERAL_OFFSETS.length);
		for (double forwardDistance : FORWARD_DISTANCES) {
			Vec3 rowOrigin = origin.add(forward.scale(forwardDistance));
			for (double lateralOffset : LATERAL_OFFSETS) {
				candidates.add(rowOrigin.add(lateral.scale(lateralOffset)));
			}
		}
		return List.copyOf(candidates);
	}

	private static Vec3 horizontalForward(Vec3 lookDirection) {
		Vec3 horizontal = new Vec3(lookDirection.x, 0.0D, lookDirection.z);
		return horizontal.lengthSqr() < MIN_HORIZONTAL_LENGTH_SQUARED
				? DEFAULT_FORWARD
				: horizontal.normalize();
	}
}
