package dev.agaminggod.arenaagents.scenario.presentation;

import java.util.Objects;

/** Chooses the least intrusive operator surface for scenario lifecycle notices. */
public final class ScenarioOperatorMessagePolicy {
	private ScenarioOperatorMessagePolicy() {
	}

	public static Surface surface(Event event) {
		return switch (Objects.requireNonNull(event, "event must not be null")) {
			case PREPARING, READY, STARTED, FINISHED -> Surface.ACTION_BAR;
			case FAILURE -> Surface.FIELD_CONSOLE;
		};
	}

	public enum Event {
		PREPARING,
		READY,
		STARTED,
		FINISHED,
		FAILURE
	}

	public enum Surface {
		ACTION_BAR,
		FIELD_CONSOLE
	}
}
