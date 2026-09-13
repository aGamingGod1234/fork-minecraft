package dev.agaminggod.arenaagents.server.conversation;

import dev.agaminggod.arenaagents.agent.AgentDomainException;
import dev.agaminggod.arenaagents.agent.AgentId;
import java.util.Objects;

public record ConversationEvent(
		AgentId agentId,
		String sourceId,
		String recipientId,
		ConversationAudience audience,
		ConversationKind kind,
		String text,
		long goalRevision,
		long observedAtEpochMs,
		long sequence,
		String dimensionId
) {
	public static final int MAX_TEXT_CODE_POINTS = 512;
	public static final int MAX_ID_CODE_POINTS = 128;

	public ConversationEvent {
		Objects.requireNonNull(agentId, "agentId must not be null");
		sourceId = boundedIdentifier(sourceId, "sourceId", false);
		recipientId = boundedIdentifier(recipientId, "recipientId", true);
		Objects.requireNonNull(audience, "audience must not be null");
		Objects.requireNonNull(kind, "kind must not be null");
		text = boundedText(text);
		if (audience == ConversationAudience.DIRECT && recipientId.isEmpty()) {
			throw new AgentDomainException("MISSING_RECIPIENT", "Direct conversations require a recipient");
		}
		if (goalRevision < 0L) throw new AgentDomainException("INVALID_REVISION", "goalRevision must not be negative");
		if (observedAtEpochMs <= 0L) throw new AgentDomainException("INVALID_CONVERSATION_TIME", "observedAtEpochMs must be positive");
		if (sequence < 0L) throw new AgentDomainException("INVALID_CONVERSATION_SEQUENCE", "sequence must not be negative");
		dimensionId = boundedIdentifier(dimensionId, "dimensionId", false);
	}

	private static String boundedIdentifier(String value, String field, boolean allowEmpty) {
		if (value == null) {
			if (allowEmpty) return "";
			throw new AgentDomainException("INVALID_CONVERSATION_ID", field + " must not be null");
		}
		String normalized = value.trim();
		if (!allowEmpty && normalized.isEmpty()) {
			throw new AgentDomainException("INVALID_CONVERSATION_ID", field + " must not be blank");
		}
		if (normalized.codePointCount(0, normalized.length()) > MAX_ID_CODE_POINTS) {
			throw new AgentDomainException("CONVERSATION_ID_TOO_LONG", field + " exceeds " + MAX_ID_CODE_POINTS + " code points");
		}
		return normalized;
	}

	private static String boundedText(String value) {
		if (value == null) throw new AgentDomainException("CONVERSATION_EMPTY", "Conversation text must not be null");
		String normalized = value.strip();
		if (normalized.isEmpty()) throw new AgentDomainException("CONVERSATION_EMPTY", "Conversation text must not be blank");
		int count = normalized.codePointCount(0, normalized.length());
		if (count <= MAX_TEXT_CODE_POINTS) return normalized;
		return normalized.substring(0, normalized.offsetByCodePoints(0, MAX_TEXT_CODE_POINTS));
	}
}
