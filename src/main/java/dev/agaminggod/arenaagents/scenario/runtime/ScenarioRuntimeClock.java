package dev.agaminggod.arenaagents.scenario.runtime;

import dev.agaminggod.arenaagents.scenario.ScenarioDirectedEvent;
import dev.agaminggod.arenaagents.scenario.ScenarioEventDirector;
import dev.agaminggod.arenaagents.scenario.ScenarioEventType;
import dev.agaminggod.arenaagents.scenario.ScenarioPhase;
import dev.agaminggod.arenaagents.scenario.ScenarioSession;
import dev.agaminggod.arenaagents.scenario.ScenarioSessionState;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

public final class ScenarioRuntimeClock {
	private final ScenarioSession session;
	private final List<ScenarioDirectedEvent> schedule;
	private long elapsedTick;
	private int nextEventIndex;
	private String activePhaseId;
	private boolean finished;

	public ScenarioRuntimeClock(ScenarioSession session) {
		this.session = Objects.requireNonNull(session, "session must not be null");
		if (session.state() != ScenarioSessionState.RUNNING
				&& session.state() != ScenarioSessionState.PAUSED
				&& session.state() != ScenarioSessionState.PAUSED_RECOVERY) {
			throw new IllegalArgumentException("scenario runtime clock requires an active or recovery-paused session");
		}
		this.schedule = session.config().deterministicEvents()
				? new ScenarioEventDirector().schedule(session.config())
				: List.of();
	}

	private ScenarioRuntimeClock(ScenarioSession session, Snapshot snapshot) {
		this(session);
		Objects.requireNonNull(snapshot, "snapshot must not be null");
		if (snapshot.nextEventIndex() > schedule.size()) {
			throw new IllegalArgumentException("INVALID_SCENARIO_SNAPSHOT: clock event index exceeds schedule");
		}
		long durationTicks = session.config().durationTicks();
		this.elapsedTick = Math.min(snapshot.elapsedTick(), durationTicks);
		this.nextEventIndex = snapshot.nextEventIndex();
		this.activePhaseId = snapshot.activePhaseId().orElse(null);
		this.finished = snapshot.finished();
		if (finished && !session.state().terminal()) {
			throw new IllegalArgumentException("INVALID_SCENARIO_SNAPSHOT: active session cannot have a finished clock");
		}
		if (!finished && snapshot.elapsedTick() >= durationTicks) {
			long finishTick = Math.max(session.lastElapsedTick(), Math.max(0L, durationTicks - 1L));
			session.finish(finishTick, "Configured scenario duration elapsed");
			finished = true;
		}
	}

	public static ScenarioRuntimeClock restore(ScenarioSession session, Snapshot snapshot) {
		return new ScenarioRuntimeClock(session, snapshot);
	}

	public Snapshot snapshot() {
		return new Snapshot(1, elapsedTick, nextEventIndex, Optional.ofNullable(activePhaseId), finished);
	}

	public Update tick() {
		if (finished) {
			return new Update(elapsedTick, Optional.empty(), List.of(), false);
		}
		if (session.state() == ScenarioSessionState.PAUSED
				|| session.state() == ScenarioSessionState.PAUSED_RECOVERY) {
			return new Update(elapsedTick, Optional.empty(), List.of(), false);
		}
		if (session.state() != ScenarioSessionState.RUNNING) {
			boolean finishedNow = session.state().terminal();
			finished = finishedNow;
			return new Update(elapsedTick, Optional.empty(), List.of(), finishedNow);
		}
		long currentTick = elapsedTick;

		Optional<ScenarioPhase> enteredPhase = session.phaseAt(currentTick)
				.filter(phase -> !phase.id().equals(activePhaseId));
		enteredPhase.ifPresent(phase -> {
			activePhaseId = phase.id();
			session.record(
					currentTick,
					ScenarioEventType.PHASE_CHANGED,
					Optional.empty(),
					Optional.empty(),
					"phase",
					0.0D,
					phase.title() + " | " + phase.description(),
					Map.of("phaseId", phase.id())
			);
		});

		ArrayList<ScenarioDirectedEvent> due = new ArrayList<>();
		while (nextEventIndex < schedule.size()
				&& schedule.get(nextEventIndex).elapsedTick() <= currentTick) {
			ScenarioDirectedEvent event = schedule.get(nextEventIndex++);
			due.add(event);
			session.record(
					currentTick,
					ScenarioEventType.DIRECTOR_EVENT,
					Optional.empty(),
					Optional.empty(),
					"director-event",
					event.sequence(),
					event.description(),
					Map.of("eventId", event.id(), "phaseId", event.phaseId())
			);
		}
		elapsedTick++;
		boolean finishedNow = elapsedTick >= session.config().durationTicks();
		if (finishedNow) {
			session.finish(currentTick, "Configured scenario duration elapsed");
			finished = true;
		}
		return new Update(currentTick, enteredPhase, due, finishedNow);
	}

	public record Snapshot(
			int version,
			long elapsedTick,
			int nextEventIndex,
			Optional<String> activePhaseId,
			boolean finished
	) {
		public Snapshot {
			if (version != 1) throw new IllegalArgumentException("UNSUPPORTED_SCENARIO_SNAPSHOT: clock version " + version);
			if (elapsedTick < 0L || nextEventIndex < 0) {
				throw new IllegalArgumentException("INVALID_SCENARIO_SNAPSHOT: clock progress is invalid");
			}
			activePhaseId = Objects.requireNonNull(activePhaseId, "activePhaseId must not be null")
					.map(value -> {
						String normalized = value.strip();
						if (normalized.isEmpty() || normalized.length() > 64) {
							throw new IllegalArgumentException("INVALID_SCENARIO_SNAPSHOT: activePhaseId is invalid");
						}
						return normalized;
					});
		}
	}

	public record Update(
			long elapsedTick,
			Optional<ScenarioPhase> enteredPhase,
			List<ScenarioDirectedEvent> directedEvents,
			boolean finishedNow
	) {
		public Update {
			enteredPhase = Objects.requireNonNull(enteredPhase, "enteredPhase must not be null");
			directedEvents = List.copyOf(Objects.requireNonNull(directedEvents, "directedEvents must not be null"));
		}
	}
}
