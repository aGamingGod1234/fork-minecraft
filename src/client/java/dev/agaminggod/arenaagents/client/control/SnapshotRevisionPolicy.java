package dev.agaminggod.arenaagents.client.control;

import dev.agaminggod.arenaagents.control.AgentControlSnapshot;
import java.util.Objects;

/** Pure ordering rule for control snapshots received from the server. */
public final class SnapshotRevisionPolicy {
	private SnapshotRevisionPolicy() {
	}

	public static boolean isNewer(AgentControlSnapshot current, AgentControlSnapshot candidate) {
		Objects.requireNonNull(candidate, "candidate snapshot must not be null");
		return current == null || candidate.generatedAtEpochMs() > current.generatedAtEpochMs();
	}
}
