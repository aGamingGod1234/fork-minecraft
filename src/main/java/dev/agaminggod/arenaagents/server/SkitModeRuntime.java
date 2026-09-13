package dev.agaminggod.arenaagents.server;

import carpet.helpers.EntityPlayerActionPack;
import dev.agaminggod.arenaagents.agent.AgentDomainException;
import dev.agaminggod.arenaagents.agent.AgentId;
import dev.agaminggod.arenaagents.agent.AgentRecord;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Predicate;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Relative;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Server-authoritative placement and timeline playback. It never participates in normal agent control. */
public final class SkitModeRuntime {
	private static final Logger LOGGER = LoggerFactory.getLogger(SkitModeRuntime.class);
	private static final Map<MinecraftServer, Map<AgentId, Run>> PLAYBACK = new ConcurrentHashMap<>();

	private SkitModeRuntime() {
	}

	public static boolean enabled(MinecraftServer server) {
		return SkitModeSavedData.get(server).enabled();
	}

	public static boolean setEnabled(MinecraftServer server, boolean enabled) {
		SkitModeSavedData data = SkitModeSavedData.get(server);
		data.setEnabled(enabled);
		if (!enabled) release(server);
		return data.enabled();
	}

	public static void requireEnabled(MinecraftServer server) {
		if (!enabled(server)) throw new AgentDomainException("SKIT_MODE_DISABLED", "Enable skit mode with /codex skit on first");
	}

	public static SkitPlacement place(CodexAgentManager manager, String selector, ServerLevel level, double x, double y, double z, float yaw, float pitch) {
		Objects.requireNonNull(manager, "manager must not be null");
		Objects.requireNonNull(level, "level must not be null");
		requireEnabled(manager.server());
		AgentRecord record = manager.resolve(selector);
		SkitPlacement placement = new SkitPlacement(level.dimension().identifier().toString(), x, y, z, yaw, pitch);
		manager.findAgentPlayer(record.agentId()).ifPresentOrElse(
				player -> {
					stop(manager.server(), record.agentId());
					teleport(player, level, placement);
				},
				() -> { throw new AgentDomainException("AGENT_NOT_PRESENT", "Agent has not joined the world yet"); }
		);
		SkitModeSavedData.get(manager.server()).putPlacement(record.agentId().toString(), placement);
		return placement;
	}

	/** Persists and applies a placement captured from the controlling player. */
	public static SkitPlacement placeFromPlayer(CodexAgentManager manager, String selector, ServerPlayer player) {
		SkitPlacement placement = SkitPlacement.fromPlayer(player);
		return place(manager, selector, (ServerLevel) player.level(), placement.x(), placement.y(), placement.z(), placement.yaw(), placement.pitch());
	}

	/** Persists and applies a placement offset from the controlling player's view. */
	public static SkitPlacement placeRelative(CodexAgentManager manager, String selector, ServerPlayer player,
			double right, double up, double forward) {
		SkitPlacement placement = SkitPlacement.relativeTo(player, right, up, forward);
		return place(manager, selector, (ServerLevel) player.level(), placement.x(), placement.y(), placement.z(), placement.yaw(), placement.pitch());
	}

	/** Persists and applies a placement at the player while looking at a target point. */
	public static SkitPlacement placeLookingAt(CodexAgentManager manager, String selector, ServerPlayer player, net.minecraft.world.phys.Vec3 target) {
		SkitPlacement placement = SkitPlacement.lookingAt(player, target);
		return place(manager, selector, (ServerLevel) player.level(), placement.x(), placement.y(), placement.z(), placement.yaw(), placement.pitch());
	}

	public static Optional<SkitPlacement> savedPlacement(CodexAgentManager manager, String selector) {
		AgentRecord record = manager.resolve(selector);
		return Optional.ofNullable(SkitModeSavedData.get(manager.server()).placement(record.agentId().toString()));
	}

	public static SkitScript createScript(MinecraftServer server, String name, String agentSelector) {
		requireEnabled(server);
		SkitScript script = new SkitScript(name, agentSelector, List.of());
		SkitModeSavedData.get(server).putScript(script);
		return script;
	}

	public static SkitScript addStep(MinecraftServer server, String name, SkitStep step) {
		requireEnabled(server);
		SkitModeSavedData data = SkitModeSavedData.get(server);
		SkitScript current = Optional.ofNullable(data.script(name))
				.orElseThrow(() -> new AgentDomainException("SKIT_SCRIPT_NOT_FOUND", "No skit script named " + name));
		SkitScript updated = current.append(step);
		data.putScript(updated);
		return updated;
	}

	/** Appends one action step while retaining the same explicit endpoint model as pose steps. */
	public static SkitScript addAction(MinecraftServer server, String name, SkitPlacement endpoint, SkitAction action) {
		Objects.requireNonNull(endpoint, "endpoint must not be null");
		Objects.requireNonNull(action, "action must not be null");
		return addStep(server, name, new SkitStep(0, endpoint, List.of(action)));
	}

	public static SkitScript play(CodexAgentManager manager, String name, String selectorOverride) {
		requireEnabled(manager.server());
		SkitModeSavedData data = SkitModeSavedData.get(manager.server());
		SkitScript script = Optional.ofNullable(data.script(name))
				.orElseThrow(() -> new AgentDomainException("SKIT_SCRIPT_NOT_FOUND", "No skit script named " + name));
		if (script.steps().isEmpty()) throw new AgentDomainException("SKIT_SCRIPT_EMPTY", "Skit script has no steps");
		String selector = selectorOverride == null || selectorOverride.isBlank() ? script.agentSelector() : selectorOverride;
		AgentRecord record = manager.resolve(selector);
		if (record.state() != dev.agaminggod.arenaagents.agent.AgentLifecycleState.IDLE
				|| !record.currentGoal().isEmpty() || !record.queuedGoals().isEmpty()) {
			throw new AgentDomainException("SKIT_AGENT_BUSY", "Skit playback requires an idle agent with no queued goals");
		}
		AgentId agentId = record.agentId();
		ServerPlayer actor = manager.findAgentPlayer(agentId).filter(player -> player.isAlive() && !player.isRemoved())
				.orElseThrow(() -> new AgentDomainException("AGENT_NOT_PRESENT", "Agent has not joined the world yet"));
		validateTimeline(script, actor.level().dimension().identifier().toString(),
				dimension -> findLevel(manager.server(), dimension).isPresent(),
				item -> BuiltInRegistries.ITEM.containsKey(Identifier.parse(item)));
		stop(manager.server(), agentId);
		PLAYBACK.computeIfAbsent(manager.server(), ignored -> new ConcurrentHashMap<>())
				.put(agentId, new Run(Playback.waiting(script.steps(), manager.server().getTickCount()), actor, (ServerLevel) actor.level()));
		return script;
	}

	/** Prevents normal goal execution from racing a server-authoritative skit timeline. */
	public static void requireNormalControlAllowed(MinecraftServer server, AgentId agentId) {
		Objects.requireNonNull(agentId, "agentId must not be null");
		if (server == null) return;
		if (Optional.ofNullable(PLAYBACK.get(server)).map(runs -> runs.containsKey(agentId)).orElse(false)) {
			throw new AgentDomainException("SKIT_AGENT_RESERVED", "Agent is reserved by active skit playback");
		}
	}

	public static boolean deleteScript(MinecraftServer server, String name) {
		requireEnabled(server);
		SkitModeSavedData data = SkitModeSavedData.get(server);
		if (!data.removeScript(name)) {
			throw new AgentDomainException("SKIT_SCRIPT_NOT_FOUND", "No skit script named " + name);
		}
		return true;
	}

	public static void stop(MinecraftServer server, String selector) {
		stop(server, CodexAgentManager.get(server).resolve(selector).agentId());
	}

	static void stop(MinecraftServer server, AgentId agentId) {
		if (server == null) return;
		Map<AgentId, Run> runs = PLAYBACK.get(server);
		if (runs != null) cleanup(runs.remove(agentId));
	}

	public static void tick(MinecraftServer server) {
		if (!enabled(server)) { release(server); return; }
		Map<AgentId, Run> runs = PLAYBACK.get(server);
		if (runs == null || runs.isEmpty()) return;
		long tick = server.getTickCount();
		CodexAgentManager manager = CodexAgentManager.get(server);
		for (var entry : runs.entrySet()) {
			Run run = entry.getValue();
			try {
				ServerPlayer actor = run.actor();
				if (!actor.isAlive() || actor.isRemoved() || actor.level() != run.level()
						|| manager.findAgentPlayer(entry.getKey()).orElse(null) != actor) {
					if (runs.remove(entry.getKey(), run)) cleanup(run);
					continue;
				}
				Playback playback = run.playback();
				if (tick < playback.nextTick()) continue;
				Playback updated = advancePlayback(server, actor, playback, tick);
				if (updated == null) {
					if (runs.remove(entry.getKey(), run)) cleanup(run);
				} else {
					runs.replace(entry.getKey(), run, new Run(updated, actor, (ServerLevel) actor.level()));
				}
			} catch (RuntimeException exception) {
				if (runs.remove(entry.getKey(), run)) cleanup(run);
				LOGGER.warn("Stopped skit playback for {} after an action failed", entry.getKey(), exception);
			}
		}
	}

	public static void release(MinecraftServer server) {
		Map<AgentId, Run> runs = PLAYBACK.remove(server);
		if (runs != null) runs.values().forEach(SkitModeRuntime::cleanup);
	}

	private static void cleanup(Run run) {
		if (run == null) return;
		cleanup(() -> OfflineAgentPlayers.actions(run.actor()).stopAll(), run.actor()::stopUsingItem);
	}

	static void cleanup(Runnable releaseInputs, Runnable stopUsingItem) {
		try {
			releaseInputs.run();
		} catch (RuntimeException exception) {
			LOGGER.warn("Could not clear skit actor input", exception);
		}
		try {
			stopUsingItem.run();
		} catch (RuntimeException exception) {
			LOGGER.warn("Could not stop skit actor item use", exception);
		}
	}

	static void validateTimeline(SkitScript script, String initialDimension, Predicate<String> dimensionExists,
			Predicate<String> itemExists) {
		String dimension = initialDimension;
		for (SkitStep step : script.steps()) {
			String target = step.placement().dimension();
			if (!dimensionExists.test(target)) {
				throw new AgentDomainException("SKIT_DIMENSION_NOT_FOUND", "Unknown dimension: " + target);
			}
			if (!step.actions().isEmpty() && step.actions().getFirst().type() == SkitAction.Type.MOVE
					&& !dimension.equals(target)) {
				throw new AgentDomainException("SKIT_MOVE_DIMENSION", "Place the actor in the target dimension before moving it");
			}
			for (SkitAction action : step.actions()) {
				if (action.type() == SkitAction.Type.EQUIP && !itemExists.test(action.itemId())) {
					throw new AgentDomainException("SKIT_ITEM_NOT_FOUND", "Unknown item: " + action.itemId());
				}
			}
			dimension = target;
		}
	}

	private static void teleport(ServerPlayer player, ServerLevel level, SkitPlacement placement) {
		player.teleportTo(level, placement.x(), placement.y(), placement.z(), Set.<Relative>of(), placement.yaw(), placement.pitch(), false);
		player.setYHeadRot(placement.yaw());
	}

	private static Playback advancePlayback(MinecraftServer server, ServerPlayer actor, Playback playback, long tick) {
		return advancePlayback(playback, tick, new Performer() {
			@Override public SkitPlacement position() { return currentPlacement(actor); }
			@Override public void place(SkitPlacement placement) {
				ServerLevel level = findLevel(server, placement.dimension())
						.orElseThrow(() -> new AgentDomainException("SKIT_DIMENSION_NOT_FOUND", "Skit dimension is no longer available"));
				teleport(actor, level, placement);
			}
			@Override public void perform(SkitStep step, SkitAction action, SkitPlacement origin, long elapsed, boolean firstTick) {
				applyAction(actor, step, action, origin, elapsed, firstTick);
			}
			@Override public void stop(SkitAction action) { stopAction(actor, action); }
		});
	}

	static Playback advancePlayback(Playback playback, long tick, Performer actor) {
		while (playback != null && tick >= playback.nextTick()) {
			SkitStep step = playback.steps().get(playback.index());
			SkitAction action = step.actions().isEmpty() ? null : step.actions().get(playback.actionIndex());
			boolean firstTick = !playback.started();
			if (firstTick) {
				if (playback.actionIndex() == 0 && (action == null || action.type() != SkitAction.Type.MOVE)) {
					actor.place(step.placement());
				}
				playback = playback.begin(actor.position(), tick);
			}
			if (action == null) {
				playback = finishStep(playback, tick);
				continue;
			}
			long elapsed = tick - playback.actionStartTick();
			actor.perform(step, action, playback.actionOrigin(), elapsed, firstTick);
			if (!playback.actionComplete(tick)) return playback;
			actor.stop(action);
			int nextAction = playback.actionIndex() + 1;
			playback = nextAction < step.actions().size()
					? playback.nextAction(nextAction, tick) : finishStep(playback, tick);
		}
		return playback;
	}

	static Playback finishStep(Playback playback, long tick) {
		int next = playback.index() + 1;
		if (next >= playback.steps().size()) return null;
		SkitStep nextStep = playback.steps().get(next);
		return new Playback(playback.steps(), next, tick + nextStep.delayTicks(), 0, 0, 0, false, null);
	}

	private static void applyAction(ServerPlayer actor, SkitStep step, SkitAction action, SkitPlacement origin,
			long elapsed, boolean firstTick) {
		switch (action.type()) {
			case MOVE -> {
				float progress = Math.min(1.0F, (float) elapsed / Math.max(1, action.durationTicks()));
				SkitPlacement target = step.placement();
				SkitPlacement interpolated = interpolate(origin, target, progress);
				if (!actor.level().dimension().identifier().toString().equals(interpolated.dimension())) {
					throw new AgentDomainException("SKIT_MOVE_DIMENSION", "Actor changed dimension during movement");
				}
				teleport(actor, (ServerLevel) actor.level(), interpolated);
			}
			case WAIT -> { }
			case JUMP -> {
				if (firstTick) OfflineAgentPlayers.actions(actor).start(
						EntityPlayerActionPack.ActionType.JUMP, EntityPlayerActionPack.Action.continuous());
			}
			case EQUIP -> {
				if (!firstTick) return;
				Identifier id = Identifier.parse(action.itemId());
				if (!BuiltInRegistries.ITEM.containsKey(id)) throw new AgentDomainException("SKIT_ITEM_NOT_FOUND", "Unknown item: " + action.itemId());
				Item item = BuiltInRegistries.ITEM.getValue(id);
				actor.getInventory().setItem(actor.getInventory().getSelectedSlot(), new ItemStack(item.builtInRegistryHolder(), 1));
			}
			case SWING -> { if (firstTick) actor.swing(InteractionHand.MAIN_HAND); }
			case USE -> {
				if (firstTick) {
					var result = actor.gameMode.useItem(actor, actor.level(), actor.getMainHandItem(), InteractionHand.MAIN_HAND);
					if (result.consumesAction()) actor.swing(InteractionHand.MAIN_HAND);
				}
			}
			case EMOTE -> OfflineAgentPlayers.actions(actor).setSneaking(action.sneak());
		}
	}

	private static void stopAction(ServerPlayer actor, SkitAction action) {
		if (action.type() == SkitAction.Type.USE) actor.releaseUsingItem();
		if (action.type() == SkitAction.Type.JUMP || action.type() == SkitAction.Type.MOVE || action.type() == SkitAction.Type.EMOTE) {
			OfflineAgentPlayers.actions(actor).stopAll();
		}
	}

	private static SkitPlacement currentPlacement(ServerPlayer player) {
		return new SkitPlacement(player.level().dimension().identifier().toString(), player.getX(), player.getY(), player.getZ(), player.getYRot(), player.getXRot());
	}

	static SkitPlacement interpolate(SkitPlacement from, SkitPlacement to, float progress) {
		if (!from.dimension().equals(to.dimension())) throw new IllegalArgumentException("Cannot interpolate between dimensions");
		if (!Float.isFinite(progress) || progress < 0.0F || progress > 1.0F) throw new IllegalArgumentException("progress must be between 0 and 1");
		if (progress == 1.0F) return to;
		float yaw = from.yaw() + (float) Math.IEEEremainder((double) to.yaw() - from.yaw(), 360.0D) * progress;
		return new SkitPlacement(to.dimension(),
				from.x() + (to.x() - from.x()) * progress,
				from.y() + (to.y() - from.y()) * progress,
				from.z() + (to.z() - from.z()) * progress,
				yaw, from.pitch() + (to.pitch() - from.pitch()) * progress);
	}

	private static Optional<ServerLevel> findLevel(MinecraftServer server, String dimension) {
		for (ServerLevel level : server.getAllLevels()) {
			if (level.dimension().identifier().toString().equals(dimension)) return Optional.of(level);
		}
		return Optional.empty();
	}

	private record Run(Playback playback, ServerPlayer actor, ServerLevel level) {
	}

	interface Performer {
		SkitPlacement position();
		void place(SkitPlacement placement);
		void perform(SkitStep step, SkitAction action, SkitPlacement origin, long elapsed, boolean firstTick);
		void stop(SkitAction action);
	}

	record Playback(List<SkitStep> steps, int index, long nextTick, int actionIndex,
			long actionStartTick, long actionEndTick, boolean started, SkitPlacement actionOrigin) {
		Playback { steps = List.copyOf(steps); }

		static Playback waiting(List<SkitStep> steps, long now) {
			return new Playback(steps, 0, now + steps.getFirst().delayTicks(), 0, 0, 0, false, null);
		}

		Playback begin(SkitPlacement origin, long tick) {
			List<SkitAction> actions = steps.get(index).actions();
			int duration = actions.isEmpty() ? 1 : Math.max(1, actions.get(actionIndex).durationTicks());
			return new Playback(steps, index, tick, actionIndex, tick, tick + duration, true, origin);
		}

		boolean actionComplete(long tick) {
			return started && tick >= actionEndTick;
		}

		Playback nextAction(int nextIndex, long startTick) {
			return new Playback(steps, index, startTick, nextIndex, 0, 0, false, null);
		}
	}
}
