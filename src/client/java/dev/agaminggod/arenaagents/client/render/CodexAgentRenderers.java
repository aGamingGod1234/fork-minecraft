package dev.agaminggod.arenaagents.client.render;

import dev.agaminggod.arenaagents.agent.CodexAgentEntities;
import net.fabricmc.fabric.api.client.rendering.v1.EntityRendererRegistry;

public final class CodexAgentRenderers {
	private CodexAgentRenderers() {
	}

	public static void register() {
		EntityRendererRegistry.register(CodexAgentEntities.CODEX_AGENT, CodexAgentRenderer::new);
	}
}
