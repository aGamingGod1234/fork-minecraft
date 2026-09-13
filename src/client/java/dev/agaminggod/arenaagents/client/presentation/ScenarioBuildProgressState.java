package dev.agaminggod.arenaagents.client.presentation;

import dev.agaminggod.arenaagents.scenario.presentation.ScenarioBuildProgress;
import dev.agaminggod.arenaagents.scenario.presentation.ScenarioBuildProgressPayload;
import java.util.Objects;
import java.util.Optional;

/** Client-side monotonic state for arena construction progress. */
public final class ScenarioBuildProgressState {
	private ScenarioBuildProgress progress;

	public Optional<ScenarioBuildProgress> progress() {
		return Optional.ofNullable(progress);
	}

	/** Active and failed construction takes precedence over the generic pre-match spectator snapshot. */
	public boolean shouldDisplayBeforeMatch(boolean spectatorSnapshotAvailable) {
		return progress != null && (!spectatorSnapshotAvailable
				|| progress.status() != ScenarioBuildProgress.Status.READY);
	}

	public boolean accept(ScenarioBuildProgressPayload payload) {
		Objects.requireNonNull(payload, "payload must not be null");
		ScenarioBuildProgress next;
		try {
			next = payload.progress();
		} catch (IllegalArgumentException invalidPayload) {
			return false;
		}
		if (progress != null) {
			if (progress.buildId().equals(next.buildId())) {
				if (next.revision() <= progress.revision()) return false;
			} else if (next.revision() <= 0L) {
				return false;
			}
		}
		progress = next;
		return true;
	}

	public void clear() {
		progress = null;
	}
}
