package dev.agaminggod.arenaagents.client.presentation;

import dev.agaminggod.arenaagents.scenario.presentation.ArenaSpectatorSnapshot;
import dev.agaminggod.arenaagents.scenario.presentation.ArenaSpectatorSnapshotPayload;
import dev.agaminggod.arenaagents.scenario.presentation.DirectorRecommendation;
import dev.agaminggod.arenaagents.scenario.result.ScenarioPublicEvent;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

public final class ArenaSpectatorState {
	public static final long FEED_TTL_TICKS = 100L;

	private ArenaSpectatorSnapshot snapshot;
	private boolean cameraEnabled;
	private String dismissedResultRunId = "";

	public Optional<ArenaSpectatorSnapshot> snapshot() {
		return Optional.ofNullable(snapshot);
	}

	public boolean accept(ArenaSpectatorSnapshotPayload payload) {
		Objects.requireNonNull(payload, "payload must not be null");
		try {
			if (payload.kind() == ArenaSpectatorSnapshotPayload.Kind.DELTA && snapshot == null) return false;
			ArenaSpectatorSnapshot next = payload.applyTo(Optional.ofNullable(snapshot));
			boolean newRun = snapshot == null || !snapshot.runId().equals(next.runId());
			if (!newRun && next.revision() <= snapshot.revision()) return false;
			if (newRun && payload.kind() != ArenaSpectatorSnapshotPayload.Kind.FULL) return false;
			if (newRun) {
				dismissedResultRunId = "";
				cameraEnabled = false;
			}
			snapshot = next;
			if (cameraEnabled && snapshot.recommendation().isEmpty()) cameraEnabled = false;
			return true;
		} catch (IllegalArgumentException invalidUpdate) {
			return false;
		}
	}

	public List<ScenarioPublicEvent> visibleFeed(long currentTick) {
		if (currentTick < 0L) throw new IllegalArgumentException("currentTick must not be negative");
		if (snapshot == null) return List.of();
		return snapshot.feed().stream()
				.filter(entry -> entry.elapsedTick() <= currentTick)
				.filter(entry -> currentTick - entry.elapsedTick() <= FEED_TTL_TICKS)
				.toList();
	}

	public boolean resultsAvailable() {
		return snapshot != null
				&& snapshot.terminal()
				&& !snapshot.runId().equals(dismissedResultRunId);
	}

	public void dismissResults() {
		if (snapshot != null && snapshot.terminal()) dismissedResultRunId = snapshot.runId();
	}

	public boolean enableCamera(boolean spectator, long currentTick) {
		if (currentTick < 0L) throw new IllegalArgumentException("currentTick must not be negative");
		if (!spectator || snapshot == null) return false;
		Optional<DirectorRecommendation> recommendation = snapshot.recommendation()
				.filter(value -> value.currentAt(currentTick));
		if (recommendation.isEmpty()) return false;
		cameraEnabled = true;
		return true;
	}

	public void disableCamera() {
		cameraEnabled = false;
	}

	public boolean cameraDisabled() {
		return !cameraEnabled;
	}

	public Optional<DirectorRecommendation> cameraTarget(long currentTick) {
		if (!cameraEnabled || snapshot == null) return Optional.empty();
		return snapshot.recommendation().filter(value -> value.currentAt(currentTick));
	}

	public void onManualInput(ManualOverride override) {
		Objects.requireNonNull(override, "override must not be null");
		cameraEnabled = false;
	}

	public void tickCamera(
			boolean connected,
			boolean alive,
			boolean spectator,
			boolean screenOpen,
			long currentTick
	) {
		if (currentTick < 0L) throw new IllegalArgumentException("currentTick must not be negative");
		if (!cameraEnabled) return;
		if (!connected) onManualInput(ManualOverride.DISCONNECT);
		else if (!alive) onManualInput(ManualOverride.LIFE_TRANSITION);
		else if (!spectator) onManualInput(ManualOverride.LEFT_SPECTATOR);
		else if (screenOpen) onManualInput(ManualOverride.SCREEN_OPENED);
		else if (cameraTarget(currentTick).isEmpty()) disableCamera();
	}

	public void clearOnDisconnect() {
		clear();
		dismissedResultRunId = "";
	}

	public void clear() {
		snapshot = null;
		cameraEnabled = false;
	}

	public enum ManualOverride {
		MOVEMENT,
		JUMP_SNEAK_SPRINT,
		MOUSE_LOOK,
		ATTACK_USE_PICK,
		SCREEN_OPENED,
		DISCONNECT,
		LIFE_TRANSITION,
		LEFT_SPECTATOR
	}
}
