package dev.agaminggod.arenaagents.server.perception;

import dev.agaminggod.arenaagents.agent.AgentId;
import java.util.List;
import java.util.Objects;

/** Immutable geometry candidates captured before current facing and line-of-sight filtering. */
public record RawSpatialObservation(List<BlockObservationOrdering.Candidate> blocks,
		List<ContainerCandidate> containers) {
	public RawSpatialObservation {
		blocks = List.copyOf(Objects.requireNonNull(blocks, "blocks must not be null"));
		containers = List.copyOf(Objects.requireNonNull(containers, "containers must not be null"));
	}

	public record Key(AgentId agentId, String dimension, int x, int y, int z, long mutationRevision) {
		public Key {
			Objects.requireNonNull(agentId, "agentId must not be null");
			if (Objects.requireNonNull(dimension, "dimension must not be null").isBlank()) {
				throw new IllegalArgumentException("dimension must not be blank");
			}
		}
	}

	public record ContainerCandidate(int x, int y, int z, String blockId, List<String> capabilities,
			double distanceSquared) {
		public ContainerCandidate {
			if (Objects.requireNonNull(blockId, "blockId must not be null").isBlank()) {
				throw new IllegalArgumentException("blockId must not be blank");
			}
			capabilities = List.copyOf(Objects.requireNonNull(capabilities, "capabilities must not be null"));
			if (!Double.isFinite(distanceSquared) || distanceSquared < 0.0D) {
				throw new IllegalArgumentException("distanceSquared must be finite and non-negative");
			}
		}
	}
}
