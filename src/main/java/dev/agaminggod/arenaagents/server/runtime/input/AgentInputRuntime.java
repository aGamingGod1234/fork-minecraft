package dev.agaminggod.arenaagents.server.runtime.input;

import dev.agaminggod.arenaagents.agent.AgentDomainException;
import dev.agaminggod.arenaagents.agent.AgentId;
import dev.agaminggod.arenaagents.server.CodexAgentManager;
import java.util.Map;
import java.util.Optional;
import java.util.WeakHashMap;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class AgentInputRuntime {
	private static final Logger LOGGER = LoggerFactory.getLogger(AgentInputRuntime.class);
	private static final Map<MinecraftServer, LeasedServerInputController> CONTROLLERS = new WeakHashMap<>();

	private AgentInputRuntime() {
	}

	public static synchronized LeasedServerInputController controller(MinecraftServer server) {
		CodexAgentManager manager = CodexAgentManager.get(server);
		return CONTROLLERS.computeIfAbsent(server, ignored -> new LeasedServerInputController(new CarpetInputStateSink(manager)));
	}

	public static synchronized Optional<LeasedServerInputController> existingController(MinecraftServer server) {
		return Optional.ofNullable(CONTROLLERS.get(
				java.util.Objects.requireNonNull(server, "server must not be null")
		));
	}

	public static LeasedServerInputController controller(ServerPlayer player) {
		return controller(player.level().getServer());
	}

	public static AgentId requireAgentId(ServerPlayer player) {
		return findAgentId(player).orElseThrow(
				() -> new AgentDomainException("AGENT_PLAYER_MISSING", "Player is not backed by an arena agent")
		);
	}

	public static Optional<AgentId> findAgentId(ServerPlayer player) {
		return CodexAgentManager.get(player.level().getServer()).records().stream()
				.filter(record -> record.entityUuid().filter(player.getUUID()::equals).isPresent())
				.map(record -> record.agentId())
				.findFirst();
	}

	public static void clear(ServerPlayer player) {
		findAgentId(player).ifPresent(agentId -> clear(player.level().getServer(), agentId));
	}

	public static synchronized void clear(MinecraftServer server, AgentId agentId) {
		LeasedServerInputController controller = CONTROLLERS.get(
				java.util.Objects.requireNonNull(server, "server must not be null")
		);
		if (controller != null) controller.clear(java.util.Objects.requireNonNull(agentId, "agentId must not be null"));
	}

	public static synchronized void tick(MinecraftServer server) {
		LeasedServerInputController controller = CONTROLLERS.get(
				java.util.Objects.requireNonNull(server, "server must not be null")
		);
		if (controller != null) tickController(controller);
	}

	static void tickController(LeasedServerInputController controller) {
		try {
			controller.tick();
		} catch (RuntimeException failure) {
			LOGGER.error("Agent input tick failed; retained inputs will retry on the next server tick", failure);
		}
	}

	public static synchronized void release(MinecraftServer server) {
		CONTROLLERS.remove(server);
	}
}
