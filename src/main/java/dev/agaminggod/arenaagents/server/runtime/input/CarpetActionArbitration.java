package dev.agaminggod.arenaagents.server.runtime.input;

import dev.agaminggod.arenaagents.agent.AgentId;
import java.util.Map;
import java.util.Objects;
import java.util.WeakHashMap;
import java.util.function.Supplier;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;

/** Bridges exact-hand use into Carpet's USE-before-ATTACK action-pack slot. */
public final class CarpetActionArbitration {
	private static final Map<ServerPlayer, Binding> BINDINGS = new WeakHashMap<>();

	private CarpetActionArbitration() {
	}

	static synchronized void bind(
			ServerPlayer player,
			AgentId agentId,
			InteractionHand hand,
			ExactHandUseDriver driver
	) {
		Objects.requireNonNull(player, "player must not be null");
		Objects.requireNonNull(agentId, "agentId must not be null");
		Objects.requireNonNull(hand, "hand must not be null");
		Objects.requireNonNull(driver, "driver must not be null");
		BINDINGS.entrySet().removeIf(entry -> entry.getValue().agentId().equals(agentId));
		BINDINGS.put(player, new Binding(agentId, hand, driver));
	}

	static synchronized void unbind(AgentId agentId) {
		Objects.requireNonNull(agentId, "agentId must not be null");
		BINDINGS.entrySet().removeIf(entry -> entry.getValue().agentId().equals(agentId));
	}

	public static Boolean arbitrate(ServerPlayer player, Supplier<Boolean> attack) {
		Objects.requireNonNull(player, "player must not be null");
		Objects.requireNonNull(attack, "attack must not be null");
		Binding binding;
		synchronized (CarpetActionArbitration.class) {
			binding = BINDINGS.get(player);
		}
		if (binding == null) return attack.get();
		return binding.driver().arbitrate(
				binding.agentId(),
				binding.hand(),
				new CarpetInputStateSink.MinecraftPlayerUseAccess(player),
				attack,
				player.level().getServer().getTickCount()
		);
	}

	private record Binding(AgentId agentId, InteractionHand hand, ExactHandUseDriver driver) {
	}
}
