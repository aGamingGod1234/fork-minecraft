package dev.agaminggod.arenaagents.server;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.brigadier.exceptions.DynamicCommandExceptionType;
import dev.agaminggod.arenaagents.server.GoalControl.Delivery;
import dev.agaminggod.arenaagents.server.GoalControl.DeliveryFailure;
import dev.agaminggod.arenaagents.server.GoalControl.GoalStatus;
import java.util.Collection;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.EntityArgument;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.MinecraftServer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class ArenaAgentCommands {
	private static final String ARGUMENT_GOAL = "goal";
	private static final String ARGUMENT_PLAYERS = "players";
	private static final DynamicCommandExceptionType INVALID_GOAL = new DynamicCommandExceptionType(
			message -> Component.literal(String.valueOf(message))
	);
	private static final Map<MinecraftServer, GoalControl> GOAL_CONTROLS = new IdentityHashMap<>();
	private static final Logger LOGGER = LoggerFactory.getLogger(ArenaAgentCommands.class);

	private ArenaAgentCommands() {
	}

	public static void register() {
		CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) -> register(dispatcher));
		ServerLifecycleEvents.SERVER_STOPPING.register(ArenaAgentCommands::clearGoalControl);
	}

	private static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
		dispatcher.register(
				Commands.literal("arenaagent")
						.requires(GoalControl::mayControl)
						.then(Commands.literal("goal")
								.then(Commands.argument(ARGUMENT_PLAYERS, EntityArgument.players())
										.then(Commands.argument(ARGUMENT_GOAL, StringArgumentType.greedyString())
												.executes(ArenaAgentCommands::setGoal))))
						.then(Commands.literal("stop")
								.then(Commands.argument(ARGUMENT_PLAYERS, EntityArgument.players())
										.executes(ArenaAgentCommands::stop)))
						.then(Commands.literal("status")
								.then(Commands.argument(ARGUMENT_PLAYERS, EntityArgument.players())
										.executes(ArenaAgentCommands::status)))
		);
	}

	private static int setGoal(CommandContext<CommandSourceStack> context) throws CommandSyntaxException {
		Collection<ServerPlayer> players = EntityArgument.getPlayers(context, ARGUMENT_PLAYERS);
		String normalizedGoal;
		try {
			normalizedGoal = GoalControl.normalize(StringArgumentType.getString(context, ARGUMENT_GOAL));
		} catch (IllegalArgumentException exception) {
			throw INVALID_GOAL.create(exception.getMessage());
		}

		Delivery<ServerPlayer> delivery = deliver(players, GoalPayload.set(normalizedGoal));
		for (ServerPlayer player : delivery.delivered()) {
			goalControl(context.getSource().getServer()).rememberGoal(player.getUUID(), normalizedGoal);
		}
		reportDelivery(context.getSource(), "goal", delivery);
		return delivery.delivered().size();
	}

	private static int stop(CommandContext<CommandSourceStack> context) throws CommandSyntaxException {
		Collection<ServerPlayer> players = EntityArgument.getPlayers(context, ARGUMENT_PLAYERS);
		Delivery<ServerPlayer> delivery = deliver(players, GoalPayload.stop());
		for (ServerPlayer player : delivery.delivered()) {
			goalControl(context.getSource().getServer()).rememberStop(player.getUUID());
		}
		reportDelivery(context.getSource(), "stop", delivery);
		return delivery.delivered().size();
	}

	private static int status(CommandContext<CommandSourceStack> context) throws CommandSyntaxException {
		Collection<ServerPlayer> players = EntityArgument.getPlayers(context, ARGUMENT_PLAYERS);
		CommandSourceStack source = context.getSource();
		for (ServerPlayer player : players) {
			GoalStatus status = goalControl(source.getServer()).status(player.getUUID());
			source.sendSuccess(
					() -> Component.literal(formatStatus(player, status)),
					false
			);
		}
		return players.size();
	}

	private static Delivery<ServerPlayer> deliver(
			Collection<ServerPlayer> players,
			GoalPayload payload
	) {
		return GoalControl.sendToSelected(
				players,
				player -> ServerPlayNetworking.canSend(player, GoalPayload.TYPE),
				ServerPlayNetworking::send,
				payload
		);
	}

	private static void reportDelivery(
			CommandSourceStack source,
			String operation,
			Delivery<ServerPlayer> delivery
	) {
		if (!delivery.delivered().isEmpty()) {
			String names = playerNames(delivery.delivered());
			source.sendSuccess(
					() -> Component.literal(
							"Arena agent " + operation + " delivered to " + delivery.delivered().size() + " player(s): " + names
					),
					false
			);
		}
		if (!delivery.unsupported().isEmpty()) {
			source.sendFailure(Component.literal(
					"Arena Agents mod channel unavailable for: " + playerNames(delivery.unsupported())
		));
		}
		for (DeliveryFailure<ServerPlayer> failure : delivery.failed()) {
			String playerName = playerName(failure.target());
			LOGGER.warn("Could not deliver Arena Agents {} payload to {}", operation, playerName, failure.cause());
			source.sendFailure(Component.literal("Arena agent " + operation + " delivery failed for: " + playerName));
		}
	}

	private static String formatStatus(ServerPlayer player, GoalStatus status) {
		String prefix = "Arena agent " + playerName(player) + ": ";
		return switch (status.status()) {
			case ACTIVE -> prefix + "active goal | " + status.goal();
			case STOPPED -> prefix + "stopped";
			case UNKNOWN -> prefix + "no goal has been delivered this server session";
		};
	}

	private static String playerNames(List<ServerPlayer> players) {
		return players.stream().map(ArenaAgentCommands::playerName).reduce((left, right) -> left + ", " + right).orElse("");
	}

	private static String playerName(ServerPlayer player) {
		return player.getGameProfile().name();
	}

	static synchronized GoalControl goalControl(MinecraftServer server) {
		return GOAL_CONTROLS.computeIfAbsent(java.util.Objects.requireNonNull(server), ignored -> new GoalControl());
	}

	static synchronized void clearGoalControl(MinecraftServer server) {
		GOAL_CONTROLS.remove(java.util.Objects.requireNonNull(server));
	}
}
