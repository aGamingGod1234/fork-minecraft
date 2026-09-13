package dev.agaminggod.arenaagents.control;

import java.util.Objects;
import java.util.Optional;

public final class AgentControlSnapshotStore {
	private AgentControlSnapshot current;

	public synchronized boolean accept(AgentControlSnapshot snapshot) {
		AgentControlSnapshot checked = Objects.requireNonNull(snapshot, "snapshot must not be null");
		if (current != null && checked.generatedAtEpochMs() < current.generatedAtEpochMs()) {
			return false;
		}
		current = checked;
		return true;
	}

	public synchronized Optional<AgentControlSnapshot> current() {
		return Optional.ofNullable(current);
	}

	public synchronized void clear() {
		current = null;
	}
}
