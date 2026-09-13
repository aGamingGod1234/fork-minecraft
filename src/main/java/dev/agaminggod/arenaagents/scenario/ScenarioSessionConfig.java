package dev.agaminggod.arenaagents.scenario;

import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

public record ScenarioSessionConfig(
		UUID sessionId,
		ScenarioPreset preset,
		long worldSeed,
		long eventSeed,
		long durationTicks,
		boolean deterministicEvents,
		List<ScenarioParticipant> participants,
		long createdAtEpochMs
) {
	public static final long MAX_DURATION_TICKS = 1_728_000L;

	public ScenarioSessionConfig {
		Objects.requireNonNull(sessionId, "sessionId must not be null");
		preset = Objects.requireNonNull(preset, "preset must not be null");
		if (durationTicks <= 0L || durationTicks > MAX_DURATION_TICKS) {
			throw ScenarioValidators.failure("INVALID_DURATION", "durationTicks must be between 1 and " + MAX_DURATION_TICKS);
		}
		participants = List.copyOf(Objects.requireNonNull(participants, "participants must not be null"));
		preset.validateAgentCount(participants.size());
		HashSet<String> participantIds = new HashSet<>();
		for (ScenarioParticipant participant : participants) {
			Objects.requireNonNull(participant, "participants must not contain null");
			if (!participantIds.add(participant.id())) {
				throw ScenarioValidators.failure(
						"DUPLICATE_PARTICIPANT_ID",
						"duplicate participant id " + participant.id()
				);
			}
		}
		if (createdAtEpochMs <= 0L) {
			throw ScenarioValidators.failure("INVALID_TIMESTAMP", "createdAtEpochMs must be positive");
		}
	}

	public ScenarioParticipant requireParticipant(String participantId) {
		String normalizedId = ScenarioValidators.id(participantId, "participant id");
		return participants.stream()
				.filter(participant -> participant.id().equals(normalizedId))
				.findFirst()
				.orElseThrow(() -> ScenarioValidators.failure(
						"UNKNOWN_PARTICIPANT",
						"unknown scenario participant " + normalizedId
				));
	}
}
