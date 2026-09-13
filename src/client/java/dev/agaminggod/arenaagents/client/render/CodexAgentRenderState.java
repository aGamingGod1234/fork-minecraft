package dev.agaminggod.arenaagents.client.render;

import dev.agaminggod.arenaagents.agent.AgentVisualIdentity;
import net.minecraft.client.renderer.entity.state.HumanoidRenderState;

public final class CodexAgentRenderState extends HumanoidRenderState {
	private String provider = "codex";
	private String model = "unconfigured";
	private int skinVariant;

	public String provider() {
		return provider;
	}

	void setProvider(String provider) {
		this.provider = provider;
	}

	public String model() {
		return model;
	}

	void setModel(String model) {
		this.model = model;
	}

	public int skinVariant() {
		return skinVariant;
	}

	void setSkinVariant(int skinVariant) {
		this.skinVariant = AgentVisualIdentity.normalizedVariant(skinVariant);
	}
}
