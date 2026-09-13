package dev.agaminggod.arenaagents.server;

import java.util.Objects;
import java.util.List;

/** One absolute pose in a replayable skit timeline. */
public record SkitStep(int delayTicks, SkitPlacement placement, List<SkitAction> actions) {
	public static final int MAX_DELAY_TICKS = 20 * 60 * 60;

	public SkitStep(int delayTicks, SkitPlacement placement) {
		this(delayTicks, placement, List.of());
	}

	public SkitStep {
		if (delayTicks < 0 || delayTicks > MAX_DELAY_TICKS) {
			throw new IllegalArgumentException("delayTicks must be between 0 and " + MAX_DELAY_TICKS);
		}
		Objects.requireNonNull(placement, "placement must not be null");
		Objects.requireNonNull(actions, "actions must not be null");
		if (actions.size() > 64) throw new IllegalArgumentException("A skit step may contain at most 64 actions");
		if (actions.stream().anyMatch(Objects::isNull)) throw new IllegalArgumentException("actions must not contain null");
		actions = List.copyOf(actions);
	}
}
