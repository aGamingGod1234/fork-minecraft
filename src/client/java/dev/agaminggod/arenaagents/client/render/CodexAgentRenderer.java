package dev.agaminggod.arenaagents.client.render;

import dev.agaminggod.arenaagents.agent.AgentVisualIdentity;
import dev.agaminggod.arenaagents.agent.CodexAgentEntity;
import net.minecraft.client.model.HumanoidModel;
import net.minecraft.client.model.geom.ModelLayers;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.client.renderer.entity.HumanoidMobRenderer;
import net.minecraft.resources.Identifier;

public final class CodexAgentRenderer extends HumanoidMobRenderer<
		CodexAgentEntity,
		CodexAgentRenderState,
		HumanoidModel<CodexAgentRenderState>
> {
	private static final float SHADOW_RADIUS = 0.5F;

	public CodexAgentRenderer(EntityRendererProvider.Context context) {
		super(context, new HumanoidModel<>(context.bakeLayer(ModelLayers.ZOMBIE)), SHADOW_RADIUS);
	}

	@Override
	public CodexAgentRenderState createRenderState() {
		return new CodexAgentRenderState();
	}

	@Override
	public void extractRenderState(CodexAgentEntity entity, CodexAgentRenderState state, float partialTick) {
		super.extractRenderState(entity, state, partialTick);
		state.setProvider(entity.getProvider());
		state.setModel(entity.getModelName());
		state.setSkinVariant(entity.getSkinVariant());
	}

	@Override
	public Identifier getTextureLocation(CodexAgentRenderState state) {
		return textureFor(AgentVisualIdentity.resolve(state.provider(), state.model(), state.skinVariant()));
	}

	public static Identifier textureFor(AgentVisualIdentity.Resolved identity) {
		return Identifier.parse(AgentVisualIdentity.renderTexturePath(identity));
	}
}
