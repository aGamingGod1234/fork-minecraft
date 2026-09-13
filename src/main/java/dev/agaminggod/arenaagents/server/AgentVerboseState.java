package dev.agaminggod.arenaagents.server;

import dev.agaminggod.arenaagents.agent.AgentId;
import dev.agaminggod.arenaagents.server.runtime.ServerActionProgress;
import dev.agaminggod.arenaagents.server.runtime.ServerActionRequest;
import dev.agaminggod.arenaagents.server.runtime.ServerActionResult;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.OptionalInt;
import dev.agaminggod.arenaagents.agent.goal.GoalEvidence;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import net.minecraft.server.MinecraftServer;

public final class AgentVerboseState {
	private static final Map<MinecraftServer, AgentVerboseState> SERVER_STATES = new ConcurrentHashMap<>();

	private final AtomicBoolean enabled = new AtomicBoolean();
	private final Map<AgentId, ActionActivity> actions = new HashMap<>();
	private final Map<AgentId, GoalVerificationActivity> goalVerifications = new HashMap<>();

	static AgentVerboseState forServer(MinecraftServer server) {
		return SERVER_STATES.computeIfAbsent(
				Objects.requireNonNull(server, "server must not be null"),
				ignored -> new AgentVerboseState()
		);
	}

	static boolean enabled(MinecraftServer server) {
		if (server == null) return false;
		AgentVerboseState state = SERVER_STATES.get(server);
		return state != null && state.enabled();
	}

	static void release(MinecraftServer server) {
		if (server == null) return;
		AgentVerboseState state = SERVER_STATES.remove(server);
		if (state != null) state.setEnabled(false);
	}

	public boolean enabled() {
		return enabled.get();
	}

	public void setEnabled(boolean enabled) {
		this.enabled.set(enabled);
		if (!enabled) clearActivity();
	}

	public synchronized boolean beginAction(ServerActionRequest request) {
		Objects.requireNonNull(request, "request must not be null");
		if (!enabled()) return false;
		ActionActivity current = actions.get(request.agentId());
		if (current != null && current.actionId().equals(request.actionId())) return false;
		actions.put(request.agentId(), new ActionActivity(request.actionId(), 0));
		return true;
	}

	public synchronized OptionalInt progressMilestone(ServerActionProgress progress) {
		Objects.requireNonNull(progress, "progress must not be null");
		if (!enabled()) return OptionalInt.empty();
		ActionActivity current = actions.get(progress.agentId());
		if (current == null || !current.actionId().equals(progress.actionId())) {
			current = new ActionActivity(progress.actionId(), 0);
			actions.put(progress.agentId(), current);
		}
		int milestone = Math.min(75, ((int) Math.floor(progress.progress() * 100.0D)) / 25 * 25);
		if (milestone < 25 || milestone <= current.lastMilestone()) return OptionalInt.empty();
		actions.put(progress.agentId(), new ActionActivity(progress.actionId(), milestone));
		return OptionalInt.of(milestone);
	}

	public synchronized void finishAction(ServerActionResult result) {
		Objects.requireNonNull(result, "result must not be null");
		ActionActivity current = actions.get(result.agentId());
		if (current != null && current.actionId().equals(result.actionId())) actions.remove(result.agentId());
	}

	public synchronized void clearActivity() {
		actions.clear();
		goalVerifications.clear();
	}

	public synchronized boolean goalVerificationChanged(
			AgentId agentId,
			long goalRevision,
			boolean verified,
			java.util.List<GoalEvidence.Fact> facts
	) {
		Objects.requireNonNull(agentId, "agentId must not be null");
		String fingerprint = Boolean.toString(verified) + Objects.requireNonNull(facts, "facts must not be null").toString();
		GoalVerificationActivity next = new GoalVerificationActivity(goalRevision, fingerprint);
		if (next.equals(goalVerifications.get(agentId))) return false;
		goalVerifications.put(agentId, next);
		return true;
	}

	public boolean standardActivityEnabled() {
		return !enabled();
	}

	private record ActionActivity(String actionId, int lastMilestone) { }
	private record GoalVerificationActivity(long goalRevision, String fingerprint) { }
}
