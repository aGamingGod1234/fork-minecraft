package dev.agaminggod.arenaagents.server.runtime.input;

import carpet.patches.EntityPlayerMPFake;
import dev.agaminggod.arenaagents.agent.AgentId;
import java.lang.ref.WeakReference;
import java.util.Map;
import java.util.WeakHashMap;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.PlayerRideableJumping;
import net.minecraft.world.entity.vehicle.boat.AbstractBoat;

/** Runs the client-only input mechanics for a currently leased fake player's authored frame. */
public final class ModelPlayerInputBridge {
	private static final Map<ServerPlayer, Binding> BINDINGS = new WeakHashMap<>();
	private ModelPlayerInputBridge() { }

	static synchronized void bind(ServerPlayer player, AgentId agentId, AgentInputState state) {
		if (!(player instanceof EntityPlayerMPFake)) return;
		BINDINGS.entrySet().removeIf(entry -> {
			if (entry.getKey() == player || !entry.getValue().agentId.equals(agentId)) return false;
			entry.getValue().clear();
			return true;
		});
		Binding binding = BINDINGS.get(player);
		if (binding == null || !binding.agentId.equals(agentId)) {
			if (binding != null) binding.clear();
			binding = new Binding(agentId);
			BINDINGS.put(player, binding);
		}
		binding.state = state;
	}

	static synchronized void unbind(AgentId agentId) {
		BINDINGS.entrySet().removeIf(entry -> {
			if (!entry.getValue().agentId.equals(agentId)) return false;
			entry.getValue().clear();
			return true;
		});
	}

	public static synchronized void tickPlayer(ServerPlayer player) {
		Binding binding = BINDINGS.get(player);
		if (binding == null) return;
		if (!player.isAlive()) { unbind(binding.agentId); return; }
		int tick = player.level().getServer().getTickCount();
		if (binding.lastTick == tick) return;
		binding.lastTick = tick;
		Entity vehicle = player.getControlledVehicle();
		if (binding.vehicle.get() != vehicle) {
			binding.clearVehicle();
			binding.vehicle = new WeakReference<>(vehicle);
		}
		AgentInputState state = binding.state;
		if (vehicle instanceof PlayerRideableJumping jumping) {
			Integer charge = binding.jump.tick(state.jump(), jumping.canJump() && jumping.getJumpCooldown() == 0);
			if (charge != null) {
				jumping.onPlayerJump(charge);
				if (charge > 0) jumping.handleStartJump(charge);
			}
		} else binding.jump.reset();
		if (player.getAbilities().flying && !player.isPassenger()) {
			int vertical = (state.jump() ? 1 : 0) - (state.sneak() ? 1 : 0);
			if (vertical != 0) player.setDeltaMovement(player.getDeltaMovement().add(
					0.0D, vertical * player.getAbilities().getFlyingSpeed() * 3.0F, 0.0D));
		}
	}

	public static synchronized boolean updateBoatInputs(AbstractBoat boat) {
		if (boat.level().isClientSide() || !(boat.getControllingPassenger() instanceof ServerPlayer player)) return false;
		Binding binding = BINDINGS.get(player);
		if (binding == null || !player.isAlive() || player.getControlledVehicle() != boat) return false;
		AgentInputState state = binding.state;
		boat.setInput(state.strafe() > 0, state.strafe() < 0, state.forward() > 0, state.forward() < 0);
		binding.boat = new WeakReference<>(boat);
		return true;
	}

	private static final class Binding {
		private final AgentId agentId;
		private final RidingJumpInput jump = new RidingJumpInput();
		private AgentInputState state;
		private int lastTick = Integer.MIN_VALUE;
		private WeakReference<Entity> vehicle = new WeakReference<>(null);
		private WeakReference<AbstractBoat> boat = new WeakReference<>(null);
		private Binding(AgentId agentId) { this.agentId = agentId; }
		private void clearVehicle() {
			AbstractBoat previous = boat.get();
			if (previous != null && (previous.getControllingPassenger() == null
					|| previous.getControllingPassenger() instanceof ServerPlayer rider && BINDINGS.get(rider) == this)) {
				previous.setInput(false, false, false, false);
				previous.setPaddleState(false, false);
			}
			boat.clear();
			jump.reset();
		}
		private void clear() { clearVehicle(); vehicle.clear(); }
	}
}
