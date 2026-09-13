package dev.agaminggod.arenaagents.server.voice;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/** Bounded, world-persisted speech timeline for a skit agent. */
public record VoiceScript(String name, String agentSelector, List<VoiceCue> cues) {
	public static final int MAX_CUES = 512;

	public VoiceScript {
		name = requireName(name, "name");
		agentSelector = requireName(agentSelector, "agentSelector");
		Objects.requireNonNull(cues, "cues must not be null");
		if (cues.size() > MAX_CUES) throw new IllegalArgumentException("A voice script may contain at most " + MAX_CUES + " cues");
		if (cues.stream().anyMatch(Objects::isNull)) throw new IllegalArgumentException("cues must not contain null");
		cues = List.copyOf(cues);
	}

	public VoiceScript append(VoiceCue cue) {
		Objects.requireNonNull(cue, "cue must not be null");
		if (cues.size() >= MAX_CUES) throw new IllegalArgumentException("A voice script may contain at most " + MAX_CUES + " cues");
		ArrayList<VoiceCue> next = new ArrayList<>(cues);
		next.add(cue);
		return new VoiceScript(name, agentSelector, next);
	}

	private static String requireName(String value, String field) {
		Objects.requireNonNull(value, field + " must not be null");
		String checked = value.strip();
		if (checked.isEmpty() || checked.length() > 64 || !checked.matches("[A-Za-z0-9_.-]+")) {
			throw new IllegalArgumentException(field + " must be 1-64 letters, numbers, '.', '_' or '-'");
		}
		return checked;
	}
}
