package dev.agaminggod.arenaagents.server.conversation;

import dev.agaminggod.arenaagents.agent.AgentLifecycleState;
import java.util.Objects;

public final class ConversationWakePolicy {
	private ConversationWakePolicy() {
	}

	public static boolean mayInstallNewGoalFromSpeech(AgentLifecycleState state) {
		Objects.requireNonNull(state, "state must not be null");
		return state == AgentLifecycleState.IDLE || state == AgentLifecycleState.COMPLETED;
	}

	public static boolean shouldStartGoal(AgentLifecycleState state, ConversationKind kind) {
		Objects.requireNonNull(state, "state must not be null");
		Objects.requireNonNull(kind, "kind must not be null");
		if (state != AgentLifecycleState.IDLE
				&& state != AgentLifecycleState.COMPLETED
				&& state != AgentLifecycleState.PAUSED) {
			return false;
		}
		return switch (kind) {
			case PLAYER_MESSAGE, PROXIMITY_SPEECH -> true;
			case AGENT_MESSAGE, PLAYER_STEER -> false;
		};
	}
}
