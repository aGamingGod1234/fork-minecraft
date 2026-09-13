package dev.agaminggod.arenaagents.server.runtime.input;

import carpet.helpers.EntityPlayerActionPack;
import carpet.script.utils.Tracer;
import dev.agaminggod.arenaagents.agent.AgentId;
import dev.agaminggod.arenaagents.server.CodexAgentManager;
import dev.agaminggod.arenaagents.server.OfflineAgentPlayers;
import java.util.Objects;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Input;
import net.minecraft.world.entity.decoration.ItemFrame;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

public final class CarpetInputStateSink implements InputStateSink {
	private final CodexAgentManager manager;
	private final ExactHandUseDriver useDriver = new ExactHandUseDriver();

	public CarpetInputStateSink(CodexAgentManager manager) {
		this.manager = Objects.requireNonNull(manager, "manager must not be null");
	}

	@Override
	public void apply(AgentId agentId, AgentInputState previous, AgentInputState state) {
		ServerPlayer player = manager.findAgentPlayer(agentId).orElse(null);
		if (player == null) {
			CarpetActionArbitration.unbind(agentId);
			ModelPlayerInputBridge.unbind(agentId);
			useDriver.discard(agentId);
			return;
		}
		ModelPlayerInputBridge.bind(player, agentId, state);
		EntityPlayerActionPack actions = OfflineAgentPlayers.actions(player);
		boolean resetActions = previous != null && (
				(previous.jump() && !state.jump())
						|| (previous.attack() && !state.attack())
		);
		if (resetActions) actions.stopAll();
		MinecraftPlayerUseAccess useAccess = new MinecraftPlayerUseAccess(player);
		if (previous != null && previous.use() && (!state.use() || previous.hand() != state.hand())) {
			useDriver.stop(agentId, useAccess);
		}
		actions.look(state.yaw(), state.pitch())
				.setForward(state.forward())
				.setStrafing(state.strafe())
				.setSneaking(state.sneak())
				.setSprinting(state.sprint());
		actions.setSlot(state.selectedSlot() + 1);
		player.setLastClientInput(new Input(state.forward() > 0, state.forward() < 0,
				state.strafe() > 0, state.strafe() < 0, state.jump(), state.sneak(), state.sprint()));
		if (state.jump() && (resetActions || previous == null || !previous.jump())) {
			actions.start(EntityPlayerActionPack.ActionType.JUMP, EntityPlayerActionPack.Action.continuous());
		}
		if (state.attack() && (resetActions || previous == null || !previous.attack())) {
			actions.start(EntityPlayerActionPack.ActionType.ATTACK, EntityPlayerActionPack.Action.continuous());
		}
		if (state.use()) useDriver.start(agentId, state.hand(), useAccess);
		if (state.use() && state.attack()) {
			CarpetActionArbitration.bind(player, agentId, state.hand(), useDriver);
		} else {
			CarpetActionArbitration.unbind(agentId);
		}
	}

	@Override
	public void tick(AgentId agentId, AgentInputState state) {
		if (!state.use()) {
			CarpetActionArbitration.unbind(agentId);
			return;
		}
		ServerPlayer player = manager.findAgentPlayer(agentId).orElse(null);
		if (player == null) {
			CarpetActionArbitration.unbind(agentId);
			useDriver.discard(agentId);
			return;
		}
		if (state.attack()) {
			CarpetActionArbitration.bind(player, agentId, state.hand(), useDriver);
			return;
		}
		CarpetActionArbitration.unbind(agentId);
		useDriver.tick(
				agentId,
				state.hand(),
				new MinecraftPlayerUseAccess(player),
				player.level().getServer().getTickCount()
		);
	}

	@Override
	public long acceptedUses(AgentId agentId) { return useDriver.acceptedUses(agentId); }

	@Override
	public void clear(AgentId agentId, AgentInputState previous) {
		CarpetActionArbitration.unbind(agentId);
		ModelPlayerInputBridge.unbind(agentId);
		ServerPlayer player = manager.findAgentPlayer(agentId).orElse(null);
		if (player == null) {
			useDriver.discard(agentId);
		} else {
			OfflineAgentPlayers.actions(player).stopAll();
			player.setLastClientInput(Input.EMPTY);
			useDriver.stop(agentId, new MinecraftPlayerUseAccess(player));
			useDriver.discard(agentId);
		}
	}

	static final class MinecraftPlayerUseAccess implements ExactHandUseDriver.PlayerUseAccess {
		private final ServerPlayer player;
		private HitResult target;

		MinecraftPlayerUseAccess(ServerPlayer player) {
			this.player = player;
		}

		@Override
		public boolean isUsingItem() {
			return player.isUsingItem();
		}

		@Override
		public InteractionHand usedHand() {
			return player.getUsedItemHand();
		}

		@Override
		public void releaseUsingItem() {
			player.releaseUsingItem();
		}

		@Override
		public ExactHandUseDriver.TargetKind target() {
			double reach = Math.max(player.blockInteractionRange(), player.entityInteractionRange());
			target = Tracer.rayTrace(player, 1.0F, reach, false);
			return switch (target.getType()) {
				case BLOCK -> ExactHandUseDriver.TargetKind.BLOCK;
				case ENTITY -> ExactHandUseDriver.TargetKind.ENTITY;
				case MISS -> ExactHandUseDriver.TargetKind.MISS;
			};
		}

		@Override
		public ExactHandUseDriver.TargetAttempt useBlock(InteractionHand hand) {
			BlockHitResult hit = (BlockHitResult) target;
			player.resetLastActionTime();
			ServerLevel level = player.level();
			BlockPos position = hit.getBlockPos();
			Direction direction = hit.getDirection();
			if (!player.isWithinBlockInteractionRange(position, 0.0D)
					|| position.getY() >= level.getMaxY() - (direction == Direction.UP ? 1 : 0)
					|| !level.mayInteract(player, position)) {
				return ExactHandUseDriver.TargetAttempt.pass();
			}
			InteractionResult result = player.gameMode.useItemOn(
					player, level, player.getItemInHand(hand), hand, hit
			);
			if (result instanceof InteractionResult.Success success) {
				return ExactHandUseDriver.TargetAttempt.consumed(
						success.swingSource() == InteractionResult.SwingSource.SERVER
				);
			}
			return ExactHandUseDriver.TargetAttempt.pass();
		}

		@Override
		public ExactHandUseDriver.TargetAttempt useEntity(InteractionHand hand) {
			EntityHitResult hit = (EntityHitResult) target;
			player.resetLastActionTime();
			Entity target = hit.getEntity();
			if (!player.isWithinEntityInteractionRange(target, 0.0D)) return ExactHandUseDriver.TargetAttempt.pass();
			ItemStack held = player.getItemInHand(hand);
			boolean itemWasEmpty = held.isEmpty();
			boolean emptyItemFrame = target instanceof ItemFrame frame && frame.getItem().isEmpty();
			Vec3 relativeHit = hit.getLocation().subtract(target.getX(), target.getY(), target.getZ());
			if (target.interact(player, hand, relativeHit).consumesAction()) {
				return ExactHandUseDriver.TargetAttempt.consumed(false);
			}
			if (player.interactOn(target, hand, relativeHit).consumesAction()
					&& (!itemWasEmpty || !emptyItemFrame)) {
				return ExactHandUseDriver.TargetAttempt.consumed(false);
			}
			return ExactHandUseDriver.TargetAttempt.pass();
		}

		@Override
		public boolean useItem(InteractionHand hand) {
			return player.gameMode.useItem(
					player, player.level(), player.getItemInHand(hand), hand
			).consumesAction();
		}

		@Override
		public void swing(InteractionHand hand) {
			player.swing(hand);
		}
	}
}
