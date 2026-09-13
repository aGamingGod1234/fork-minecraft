package dev.agaminggod.arenaagents.server.runtime.controller;

import com.google.gson.JsonObject;
import dev.agaminggod.arenaagents.agent.AgentDomainException;
import dev.agaminggod.arenaagents.server.perception.ObservationVisibility;
import dev.agaminggod.arenaagents.server.runtime.ElapsedTimeAccumulator;
import dev.agaminggod.arenaagents.server.runtime.ServerProtectionPolicy;
import dev.agaminggod.arenaagents.server.runtime.transaction.ServerTransactionAdapter;
import dev.agaminggod.arenaagents.server.runtime.transaction.UseConfirmation;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import net.minecraft.commands.arguments.EntityAnchorArgument;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.projectile.arrow.AbstractArrow;
import net.minecraft.world.item.BowItem;
import net.minecraft.world.item.ItemStack;

public final class ServerRangedUseController implements ServerTransactionAdapter.ActiveTransaction {
	private static final double PROJECTILE_OBSERVATION_RADIUS = 128.0D;
	private final ServerPlayer player;
	private final Entity target;
	private final InteractionHand hand;
	private final ItemStack bow;
	private final ElapsedTimeAccumulator elapsedTime;
	private final long drawDurationMs;
	private final long timeoutMs;
	private final int entityIdBaseline;
	private final ServerTransactionAdapter.TerminalGate terminal = new ServerTransactionAdapter.TerminalGate();
	private final ServerTransactionAdapter.ConfirmedUseTimer useTimer = new ServerTransactionAdapter.ConfirmedUseTimer();
	private UseConfirmation useConfirmation = UseConfirmation.initial();
	private boolean started;
	private boolean released;

	public ServerRangedUseController(
			ServerPlayer player,
			JsonObject arguments,
			ServerProtectionPolicy protection
	) {
		this.player = Objects.requireNonNull(player, "player must not be null");
		Objects.requireNonNull(arguments, "arguments must not be null");
		this.target = resolveExactObservedTarget(player, arguments.get("targetId").getAsString());
		boolean protectionAllowed = Objects.requireNonNull(protection, "protection must not be null")
				.mayUseRangedWeapon(player, target);
		TargetFacts targetFacts = TargetFacts.from(target, protectionAllowed);
		if (!targetFacts.eligible()) {
			throw new AgentDomainException("RANGED_TARGET_DENIED", "Ranged target is protected, invulnerable, or not projectile-hittable");
		}
		this.hand = bowHand(player);
		this.bow = player.getItemInHand(hand);
		if (!(bow.getItem() instanceof BowItem)) {
			throw new AgentDomainException("BOW_NOT_EQUIPPED", "A bow must be held in either hand");
		}
		if (!canStartBowUse(!player.getProjectile(bow).isEmpty(), player.hasInfiniteMaterials())) {
			throw new AgentDomainException("PROJECTILE_REQUIRED", "Vanilla bow use requires an owned arrow");
		}
		this.elapsedTime = new ElapsedTimeAccumulator(System.currentTimeMillis());
		this.drawDurationMs = arguments.get("drawDurationMs").getAsLong();
		this.timeoutMs = arguments.get("timeoutMs").getAsLong();
		this.entityIdBaseline = globalEntityIdBaseline(player.level());
	}

	@Override
	public ServerTransactionAdapter.TickResult tick(long nowEpochMs) {
		ServerTransactionAdapter.TickResult existing = terminal.terminalResult();
		if (existing != null) return existing;
		long elapsed = elapsedTime.advance(nowEpochMs);
		if (elapsed >= timeoutMs) {
			return finish(ServerTransactionAdapter.TickResult.timedOut(
					"RANGED_USE_TIMED_OUT", "No newly spawned owned arrow was observed before timeout"));
		}
		if (!released && (!target.isAlive() || target.level() != player.level()
				|| !ObservationVisibility.canSeeEntity(player, target))) {
			return finish(ServerTransactionAdapter.TickResult.failed("TARGET_UNAVAILABLE", "Ranged target is no longer alive"));
		}
		if (!started) {
			player.lookAt(EntityAnchorArgument.Anchor.EYES, target, EntityAnchorArgument.Anchor.EYES);
			InteractionResult result = player.gameMode.useItem(player, player.level(), bow, hand);
			if (!result.consumesAction() || !player.isUsingItem() || player.getUsedItemHand() != hand) {
				return finish(ServerTransactionAdapter.TickResult.failed(
						"RANGED_USE_NOT_STARTED", "Vanilla bow use did not enter the observed using state"));
			}
			started = true;
			useConfirmation = useConfirmation.observeUsing(true);
			useTimer.observeStarted(true, nowEpochMs);
		}
		if (!released && useTimer.durationElapsed(nowEpochMs, drawDurationMs)) {
			player.releaseUsingItem();
			released = true;
			useConfirmation = useConfirmation.observeUsing(player.isUsingItem());
			if (!useConfirmation.confirmed()) {
				return finish(ServerTransactionAdapter.TickResult.failed(
						"RANGED_RELEASE_NOT_OBSERVED", "Bow release was not observed after use started"));
			}
		}
		if (released && confirmOwnedProjectile(entityIdBaseline, liveOwnedArrows(player), player.getUUID())) {
			return finish(ServerTransactionAdapter.TickResult.succeeded(
					"OWNED_PROJECTILE_CONFIRMED", "A new live arrow owned by the agent was observed"));
		}
		return ServerTransactionAdapter.TickResult.running();
	}

	@Override
	public void cancel(String reason) {
		terminal.finish(ServerTransactionAdapter.TickResult.cancelled(reason == null ? "Ranged use cancelled" : reason));
		cleanup();
	}

	@Override
	public void cleanup() {
		terminal.cleanupOnce(() -> ServerTransactionAdapter.runBestEffort(
				() -> { if (player.isUsingItem()) player.stopUsingItem(); },
				() -> {
					ItemStack carried = player.containerMenu.getCarried();
					player.containerMenu.setCarried(ItemStack.EMPTY);
					if (!carried.isEmpty()) player.getInventory().placeItemBackInInventory(carried);
				}
		));
	}

	private ServerTransactionAdapter.TickResult finish(ServerTransactionAdapter.TickResult result) {
		return terminal.finish(result);
	}

	public static boolean confirmOwnedProjectile(
			int entityIdBaseline,
			Map<Integer, UUID> liveArrowOwners,
			UUID expectedOwner
	) {
		Objects.requireNonNull(liveArrowOwners, "liveArrowOwners must not be null");
		Objects.requireNonNull(expectedOwner, "expectedOwner must not be null");
		return liveArrowOwners.entrySet().stream().anyMatch(entry ->
				entry.getKey() > entityIdBaseline && expectedOwner.equals(entry.getValue()));
	}

	public static boolean canStartBowUse(boolean projectilePresent, boolean infiniteMaterials) {
		return projectilePresent || infiniteMaterials;
	}

	public record TargetFacts(
			boolean protectionAllowed,
			boolean entityInvulnerable,
			boolean creativePlayer,
			boolean spectatorPlayer,
			boolean abilitiesInvulnerable,
			boolean projectileHittable
	) {
		public static TargetFacts from(Entity target, boolean protectionAllowed) {
			Objects.requireNonNull(target, "target must not be null");
			if (target instanceof ServerPlayer playerTarget) {
				return new TargetFacts(
						protectionAllowed,
						target.isInvulnerable(),
						playerTarget.isCreative(),
						playerTarget.isSpectator(),
						playerTarget.getAbilities().invulnerable,
						target.canBeHitByProjectile()
				);
			}
			return new TargetFacts(
					protectionAllowed, target.isInvulnerable(), false, false, false, target.canBeHitByProjectile());
		}

		public boolean eligible() {
			return protectionAllowed
					&& !entityInvulnerable
					&& !creativePlayer
					&& !spectatorPlayer
					&& !abilitiesInvulnerable
					&& projectileHittable;
		}
	}

	private static Map<Integer, UUID> liveOwnedArrows(ServerPlayer player) {
		HashMap<Integer, UUID> result = new HashMap<>();
		for (AbstractArrow arrow : player.level().getEntitiesOfClass(
				AbstractArrow.class,
				player.getBoundingBox().inflate(PROJECTILE_OBSERVATION_RADIUS),
				Entity::isAlive
		)) {
			Entity owner = arrow.getOwner();
			if (owner != null) result.put(arrow.getId(), owner.getUUID());
		}
		return result;
	}

	private static int globalEntityIdBaseline(ServerLevel level) {
		// Entity IDs are allocated monotonically in the Entity constructor. A never-spawned
		// sentinel therefore establishes a process-global boundary without loading chunks:
		// every entity that already exists, including an unloaded arrow, has a lower ID.
		return new ItemEntity(level, 0.0D, 0.0D, 0.0D, ItemStack.EMPTY).getId();
	}

	private static InteractionHand bowHand(ServerPlayer player) {
		if (player.getMainHandItem().getItem() instanceof BowItem) return InteractionHand.MAIN_HAND;
		if (player.getOffhandItem().getItem() instanceof BowItem) return InteractionHand.OFF_HAND;
		throw new AgentDomainException("BOW_NOT_EQUIPPED", "A bow must be held in either hand");
	}

	private static Entity resolveExactObservedTarget(ServerPlayer player, String targetId) {
		final UUID uuid;
		try {
			uuid = UUID.fromString(targetId);
		} catch (IllegalArgumentException exception) {
			throw new AgentDomainException("TARGET_NOT_FOUND", "Target id is not a UUID");
		}
		Entity entity = player.level().getEntity(uuid);
		if (entity == null || entity.level() != player.level()
				|| !entity.isAlive()
				|| entity == player) {
			throw new AgentDomainException("TARGET_UNAVAILABLE", "Observed ranged target is no longer available");
		}
		if (!ObservationVisibility.canSeeEntity(player, entity)) {
			throw new AgentDomainException("TARGET_NOT_VISIBLE", "Observed ranged target is no longer visible");
		}
		return entity;
	}
}
