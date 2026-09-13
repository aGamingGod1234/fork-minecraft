package dev.agaminggod.arenaagents;

import dev.agaminggod.arenaagents.agent.CodexAgentEntities;
import dev.agaminggod.arenaagents.server.AgentControlSync;
import dev.agaminggod.arenaagents.server.AgentModelArgumentType;
import dev.agaminggod.arenaagents.server.ArenaAgentCommands;
import dev.agaminggod.arenaagents.server.CodexAgentCommands;
import dev.agaminggod.arenaagents.server.CodexAgentServerRuntime;
import dev.agaminggod.arenaagents.server.GoalPayload;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;

public final class ArenaAgents implements ModInitializer {
	@Override
	public void onInitialize() {
		CodexAgentEntities.register();
		AgentModelArgumentType.register();
		PayloadTypeRegistry.clientboundPlay().register(GoalPayload.TYPE, GoalPayload.CODEC);
		AgentControlSync.register();
		ArenaAgentCommands.register();
		CodexAgentCommands.register();
		CodexAgentServerRuntime.register();
	}
}
