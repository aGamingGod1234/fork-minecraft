package dev.agaminggod.arenaagents.scenario.result;

import dev.agaminggod.arenaagents.scenario.ScenarioAgentEvent;
import java.util.Objects;

/** Produces bounded spectator text exclusively from typed, non-secret fields. */
public final class ScenarioPublicFormatter {
	public ScenarioPublicEvent format(ScenarioAgentEvent event) {
		Objects.requireNonNull(event, "event must not be null");
		return new ScenarioPublicEvent(
				event.elapsedTick(),
				event.participantId(),
				event.participantDisplayName(),
				event.kind().wireName(),
				event.actionFamily().wireName(),
				event.scoreDelta(),
				event.state().wireName()
		);
	}
}
