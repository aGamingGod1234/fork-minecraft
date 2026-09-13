package dev.agaminggod.arenaagents.server.conversation;

import java.util.List;
import java.util.Objects;

public record DeliveryReceipt(List<String> deliveredIds, List<String> mirroredOperatorIds) {
	public DeliveryReceipt {
		deliveredIds = List.copyOf(Objects.requireNonNull(deliveredIds, "deliveredIds must not be null"));
		mirroredOperatorIds = List.copyOf(Objects.requireNonNull(mirroredOperatorIds, "mirroredOperatorIds must not be null"));
	}
}
