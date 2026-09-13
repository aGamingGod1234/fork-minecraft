package dev.agaminggod.arenaagents.scenario.runtime;

import java.util.Objects;

/** Decides whether cancellation can release ownership or must first converge the site. */
public final class ScenarioCancellationPolicy {
	private ScenarioCancellationPolicy() {
	}

	public static boolean requiresSafeReset(ScenarioArenaResetJob.Phase phase, boolean buildFailed) {
		Objects.requireNonNull(phase, "phase must not be null");
		return buildFailed || switch (phase) {
			case CANONICALIZE, LOAD_CHUNKS -> false;
			case CLEAR, APPLY, VERIFY, REPAIR, COMPLETE, FAILED -> true;
		};
	}
}
