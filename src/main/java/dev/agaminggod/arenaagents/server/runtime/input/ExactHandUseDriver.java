package dev.agaminggod.arenaagents.server.runtime.input;

import dev.agaminggod.arenaagents.agent.AgentId;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.function.Supplier;
import net.minecraft.world.InteractionHand;

/** Runs exact-hand use with Carpet's combined use-before-attack arbitration. */
final class ExactHandUseDriver {
	static final int REPEAT_COOLDOWN_TICKS = 3;

	private final Map<AgentId, UseState> states = new LinkedHashMap<>();
	private final Map<AgentId, Long> lastExecutionTicks = new LinkedHashMap<>();
	private final Map<AgentId, Long> acceptedUses = new LinkedHashMap<>();

	long acceptedUses(AgentId agentId) { return acceptedUses.getOrDefault(agentId, 0L); }

	void start(AgentId agentId, InteractionHand hand, PlayerUseAccess player) {
		Objects.requireNonNull(agentId, "agentId must not be null");
		Objects.requireNonNull(hand, "hand must not be null");
		Objects.requireNonNull(player, "player must not be null");
		UseState current = states.get(agentId);
		if (current != null && current.hand == hand) return;
		if (player.isUsingItem()) player.releaseUsingItem();
		states.put(agentId, new UseState(hand));
	}

	void tick(AgentId agentId, InteractionHand hand, PlayerUseAccess player, long serverTick) {
		if (!claimExecution(agentId, serverTick)) return;
		executeUse(agentId, hand, player);
	}

	Boolean arbitrate(
			AgentId agentId,
			InteractionHand hand,
			PlayerUseAccess player,
			Supplier<Boolean> attack,
			long serverTick
	) {
		Objects.requireNonNull(attack, "attack must not be null");
		if (!claimExecution(agentId, serverTick)) return null;
		if (executeUse(agentId, hand, player)) return null;
		Boolean attackResult = attack.get();
		if (Boolean.TRUE.equals(attackResult)) executeUse(agentId, hand, player);
		return attackResult;
	}

	private boolean claimExecution(AgentId agentId, long serverTick) {
		Objects.requireNonNull(agentId, "agentId must not be null");
		Long previousTick = lastExecutionTicks.put(agentId, serverTick);
		return previousTick == null || previousTick.longValue() != serverTick;
	}

	private boolean executeUse(AgentId agentId, InteractionHand hand, PlayerUseAccess player) {
		Objects.requireNonNull(player, "player must not be null");
		start(agentId, hand, player);
		UseState state = states.get(agentId);
		if (state.cooldownTicks > 0) {
			state.cooldownTicks--;
			return true;
		}
		if (player.isUsingItem()) {
			if (player.usedHand() == hand) return true;
			player.releaseUsingItem();
		}
		TargetAttempt target = switch (Objects.requireNonNull(player.target(), "target must not be null")) {
			case BLOCK -> player.useBlock(hand);
			case ENTITY -> player.useEntity(hand);
			case MISS -> TargetAttempt.pass();
		};
		Objects.requireNonNull(target, "target attempt must not be null");
		if (target.consumed()) {
			acceptedUses.merge(agentId, 1L, Long::sum);
			if (target.swing()) player.swing(hand);
			state.cooldownTicks = REPEAT_COOLDOWN_TICKS;
			return true;
		}
		if (!player.useItem(hand)) return false;
		acceptedUses.merge(agentId, 1L, Long::sum);
		state.cooldownTicks = REPEAT_COOLDOWN_TICKS;
		return true;
	}

	void stop(AgentId agentId, PlayerUseAccess player) {
		Objects.requireNonNull(agentId, "agentId must not be null");
		Objects.requireNonNull(player, "player must not be null");
		if (states.remove(agentId) != null && player.isUsingItem()) player.releaseUsingItem();
	}

	void discard(AgentId agentId) {
		Objects.requireNonNull(agentId, "agentId must not be null");
		states.remove(agentId);
		lastExecutionTicks.remove(agentId);
		acceptedUses.remove(agentId);
	}

	interface PlayerUseAccess {
		boolean isUsingItem();

		InteractionHand usedHand();

		void releaseUsingItem();

		TargetKind target();

		TargetAttempt useBlock(InteractionHand hand);

		TargetAttempt useEntity(InteractionHand hand);

		boolean useItem(InteractionHand hand);

		void swing(InteractionHand hand);
	}

	enum TargetKind {
		MISS,
		BLOCK,
		ENTITY
	}

	record TargetAttempt(boolean consumed, boolean swing) {
		static TargetAttempt pass() {
			return new TargetAttempt(false, false);
		}

		static TargetAttempt consumed(boolean swing) {
			return new TargetAttempt(true, swing);
		}
	}

	private static final class UseState {
		private final InteractionHand hand;
		private int cooldownTicks;

		private UseState(InteractionHand hand) {
			this.hand = hand;
		}
	}
}
