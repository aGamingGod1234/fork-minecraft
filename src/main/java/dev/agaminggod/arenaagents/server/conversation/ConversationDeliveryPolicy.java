package dev.agaminggod.arenaagents.server.conversation;

import dev.agaminggod.arenaagents.agent.AgentDomainException;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;

public final class ConversationDeliveryPolicy {
	private ConversationDeliveryPolicy() {
	}

	public static DeliveryReceipt plan(
			ConversationEvent event,
			Collection<ConversationParticipant> participants,
			double proximityRange
	) {
		Objects.requireNonNull(event, "event must not be null");
		Objects.requireNonNull(participants, "participants must not be null");
		if (!Double.isFinite(proximityRange) || proximityRange <= 0.0D) {
			throw new IllegalArgumentException("proximityRange must be finite and positive");
		}

		LinkedHashMap<String, ConversationParticipant> byId = new LinkedHashMap<>();
		for (ConversationParticipant participant : participants) {
			ConversationParticipant value = Objects.requireNonNull(participant, "participant must not be null");
			byId.putIfAbsent(value.id(), value);
		}
		LinkedHashSet<String> delivered = new LinkedHashSet<>();
		LinkedHashSet<String> mirrored = new LinkedHashSet<>();
		switch (event.audience()) {
			case PUBLIC -> byId.values().stream()
					.filter(ConversationParticipant::online)
					.forEach(participant -> delivered.add(participant.id()));
			case DIRECT -> {
				ConversationParticipant recipient = byId.get(event.recipientId());
				if (recipient == null || !recipient.online()) {
					throw new AgentDomainException("RECIPIENT_OFFLINE", "Direct-message recipient is not online");
				}
				delivered.add(recipient.id());
				for (ConversationParticipant participant : byId.values()) {
					if (!participant.online() || !participant.operator()) continue;
					delivered.add(participant.id());
					if (!participant.id().equals(recipient.id())) mirrored.add(participant.id());
				}
			}
			case PROXIMITY -> {
				double rangeSquared = proximityRange * proximityRange;
				byId.values().stream()
						.filter(ConversationParticipant::online)
						.filter(participant -> participant.dimensionId().equals(event.dimensionId()))
						.filter(participant -> participant.distanceSquared() <= rangeSquared)
						.forEach(participant -> delivered.add(participant.id()));
			}
		}
		return new DeliveryReceipt(List.copyOf(delivered), List.copyOf(mirrored));
	}
}
