package dev.agaminggod.arenaagents.scenario;

import java.util.ArrayList;
import java.util.List;
import java.util.SplittableRandom;

public final class ScenarioSpawnAllocator {
	public List<ScenarioSpawn> allocate(ScenarioSessionConfig config) {
		// The configured roster order is the participant-to-agent binding order used by
		// durable run snapshots. Preserve it exactly: lexicographic sorting turns
		// slot-1, slot-2, ... into slot-1, slot-10, ... and can restore agents into
		// another contestant's lane once a roster reaches double digits.
		List<ScenarioParticipant> participants = config.participants();
		ArrayList<ScenarioSpawnLayout.Slot> slots = new ArrayList<>(
				ScenarioSpawnLayout.slots(config.preset().category(), participants.size()));
		SplittableRandom random = new SplittableRandom(config.worldSeed());
		for (int index = slots.size() - 1; index > 0; index--) {
			int swapIndex = random.nextInt(index + 1);
			ScenarioSpawnLayout.Slot value = slots.get(index);
			slots.set(index, slots.get(swapIndex));
			slots.set(swapIndex, value);
		}
		ArrayList<ScenarioSpawn> allocations = new ArrayList<>(participants.size());
		for (int index = 0; index < participants.size(); index++) {
			ScenarioParticipant participant = participants.get(index);
			ScenarioSpawnLayout.Slot slot = slots.get(index);
			allocations.add(new ScenarioSpawn(
					participant.id(),
					slot.index(),
					lanePrefix(config.preset().category()) + slot.index(),
					slot.x(),
					slot.spawnY(),
					slot.z(),
					slot.yaw(),
					participant.team()
			));
		}
		return List.copyOf(allocations);
	}

	private static String lanePrefix(ScenarioCategory category) {
		return switch (category) {
			case SURVIVAL -> "sector-";
			case BUILDING -> "plot-";
			case PVP -> "start-";
			case PARKOUR -> "lane-";
		};
	}

}
