package dev.agaminggod.arenaagents.server.runtime;

import java.util.Objects;
import java.nio.charset.StandardCharsets;

/** Immutable identity of the model-authored ArenaScript step that issued an action. */
public record ActionProvenance(
		String provider,
		String model,
		String reasoningEffort,
		String serviceTier,
	String programId,
	long programVersion,
	String sourceStepId,
	long eventSequence,
	String traceId,
	String watcherId
) {
	public static final int MAX_TEXT_LENGTH = 256;
	public static final long MAX_SAFE_INTEGER = 9_007_199_254_740_991L;

	public ActionProvenance {
		provider = boundedNonblank(provider, "provider");
		model = boundedNonblank(model, "model");
		reasoningEffort = boundedNonblank(reasoningEffort, "reasoningEffort");
		serviceTier = boundedNonblank(serviceTier, "serviceTier");
		programId = boundedNonblank(programId, "programId");
		if (programVersion <= 0L || programVersion > MAX_SAFE_INTEGER) {
			throw new IllegalArgumentException("programVersion must be a positive safe integer");
		}
		sourceStepId = boundedNonblank(sourceStepId, "sourceStepId");
		if (eventSequence < 0L || eventSequence > MAX_SAFE_INTEGER) {
			throw new IllegalArgumentException("eventSequence must be a nonnegative safe integer");
		}
		if (traceId != null) traceId = boundedTraceId(traceId);
		if (watcherId != null) watcherId = boundedWatcherId(watcherId);
		if (watcherId != null && traceId == null) {
			throw new IllegalArgumentException("watcherId requires traceId");
		}
	}

	public ActionProvenance(
			String provider, String model, String reasoningEffort, String serviceTier,
			String programId, long programVersion, String sourceStepId, long eventSequence
	) {
		this(provider, model, reasoningEffort, serviceTier, programId, programVersion, sourceStepId, eventSequence, null, null);
	}

	public ActionProvenance(
			String provider, String model, String reasoningEffort, String serviceTier,
			String programId, long programVersion, String sourceStepId, long eventSequence,
			String traceId
	) {
		this(provider, model, reasoningEffort, serviceTier, programId, programVersion, sourceStepId, eventSequence, traceId, null);
	}

	private static String boundedNonblank(String value, String field) {
		Objects.requireNonNull(value, field + " must not be null");
		if (value.length() > MAX_TEXT_LENGTH || value.codePoints().allMatch(ActionProvenance::isProtocolWhitespace)) {
			throw new IllegalArgumentException(field + " must be nonblank and at most " + MAX_TEXT_LENGTH + " characters");
		}
		return value;
	}

	private static boolean isProtocolWhitespace(int codePoint) {
		return Character.isWhitespace(codePoint) || Character.isSpaceChar(codePoint) || codePoint == 0xfeff;
	}

	private static String boundedTraceId(String value) {
		if (value.isBlank() || StandardCharsets.UTF_8.encode(value).remaining() > 128
				|| value.codePoints().anyMatch(codePoint -> codePoint < 0x20 || codePoint == 0x7f)) {
			throw new IllegalArgumentException("traceId must be nonblank and at most 128 UTF-8 bytes");
		}
		return value;
	}

	private static String boundedWatcherId(String value) {
		if (value.isBlank() || value.length() > 128
				|| value.codePoints().anyMatch(codePoint -> codePoint < 0x20 || codePoint == 0x7f)) {
			throw new IllegalArgumentException("watcherId must be nonblank and at most 128 characters");
		}
		return value;
	}
}
