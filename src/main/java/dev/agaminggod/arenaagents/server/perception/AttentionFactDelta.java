package dev.agaminggod.arenaagents.server.perception;

import com.google.gson.JsonObject;
import java.util.List;
import java.util.Objects;

/** A compact, factual description of a newly observed server-side change. */
public record AttentionFactDelta(long eventSequence, boolean attention, List<String> changedFacts, long observedAtEpochMs) {
	public static final int MAX_CHANGED_FACTS = 256;
	public AttentionFactDelta {
		if (eventSequence < 1L) throw new IllegalArgumentException("eventSequence must be positive");
		if (observedAtEpochMs < 0L) throw new IllegalArgumentException("observedAtEpochMs must be non-negative");
		changedFacts = List.copyOf(Objects.requireNonNull(changedFacts, "changedFacts must not be null"));
		if (!attention && !changedFacts.isEmpty()) throw new IllegalArgumentException("non-attention observation cannot contain changed facts");
	}

	public static AttentionFactDelta between(JsonObject previous, JsonObject current, long eventSequence, long observedAtEpochMs) {
		Objects.requireNonNull(current, "current must not be null");
		List<String> facts = AttentionSignalPolicy.changedFacts(previous, current);
		return new AttentionFactDelta(eventSequence, !facts.isEmpty(), facts, observedAtEpochMs);
	}
}
