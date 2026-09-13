package dev.agaminggod.arenaagents.scenario;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.SplittableRandom;

public final class ScenarioEventDirector {
	public List<ScenarioDirectedEvent> schedule(ScenarioSessionConfig config) {
		ArrayList<String> descriptions = new ArrayList<>(config.preset().dynamicEvents());
		SplittableRandom random = new SplittableRandom(
				mix(config.eventSeed() ^ config.preset().id().hashCode())
		);
		for (int index = descriptions.size() - 1; index > 0; index--) {
			int swapIndex = random.nextInt(index + 1);
			String value = descriptions.get(index);
			descriptions.set(index, descriptions.get(swapIndex));
			descriptions.set(swapIndex, value);
		}

		List<ScenarioPhase> phases = config.preset().phases();
		ArrayList<DraftEvent> drafts = new ArrayList<>(descriptions.size());
		for (int index = 0; index < descriptions.size(); index++) {
			ScenarioPhase phase = phases.get(index % phases.size());
			long margin = Math.max(1L, phase.durationTicks() / 5L);
			long usableTicks = Math.max(1L, phase.durationTicks() - (margin * 2L));
			long elapsedTick = phase.startTick() + margin + random.nextLong(usableTicks);
			drafts.add(new DraftEvent(phase.id(), elapsedTick, descriptions.get(index), index));
		}
		drafts.sort(Comparator.comparingLong(DraftEvent::elapsedTick).thenComparingInt(DraftEvent::tieBreaker));

		ArrayList<ScenarioDirectedEvent> schedule = new ArrayList<>(drafts.size());
		for (int sequence = 0; sequence < drafts.size(); sequence++) {
			DraftEvent draft = drafts.get(sequence);
			schedule.add(new ScenarioDirectedEvent(
					sequence,
					"director-" + sequence,
					draft.phaseId(),
					draft.elapsedTick(),
					draft.description()
			));
		}
		return List.copyOf(schedule);
	}

	private static long mix(long value) {
		long mixed = value;
		mixed = (mixed ^ (mixed >>> 30)) * 0xbf58476d1ce4e5b9L;
		mixed = (mixed ^ (mixed >>> 27)) * 0x94d049bb133111ebL;
		return mixed ^ (mixed >>> 31);
	}

	private record DraftEvent(String phaseId, long elapsedTick, String description, int tieBreaker) {
	}
}
