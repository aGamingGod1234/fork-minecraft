package dev.agaminggod.arenaagents.server.conversation;

import java.util.Objects;

public record ConversationParticipant(
		String id,
		boolean operator,
		boolean online,
		String dimensionId,
		double distanceSquared
) {
	public ConversationParticipant {
		id = Objects.requireNonNull(id, "id must not be null").trim();
		if (id.isEmpty()) throw new IllegalArgumentException("id must not be blank");
		dimensionId = Objects.requireNonNull(dimensionId, "dimensionId must not be null").trim();
		if (dimensionId.isEmpty()) throw new IllegalArgumentException("dimensionId must not be blank");
		if (!Double.isFinite(distanceSquared) || distanceSquared < 0.0D) {
			throw new IllegalArgumentException("distanceSquared must be finite and nonnegative");
		}
	}
}
