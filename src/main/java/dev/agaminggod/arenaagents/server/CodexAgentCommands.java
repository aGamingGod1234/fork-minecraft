package dev.agaminggod.arenaagents.server;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.FloatArgumentType;
import com.mojang.brigadier.arguments.DoubleArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.brigadier.exceptions.DynamicCommandExceptionType;
import dev.agaminggod.arenaagents.agent.AgentDomainException;
import dev.agaminggod.arenaagents.agent.AgentGameMode;
import dev.agaminggod.arenaagents.agent.AgentId;
import dev.agaminggod.arenaagents.agent.AgentRecord;
import dev.agaminggod.arenaagents.agent.AgentTransition;
import dev.agaminggod.arenaagents.control.AgentControlCatalog;
import dev.agaminggod.arenaagents.server.group.AgentGroup;
import dev.agaminggod.arenaagents.server.group.AgentGroupSpawnCoordinator;
import dev.agaminggod.arenaagents.server.goal.GoalDraftChoice;
import dev.agaminggod.arenaagents.server.goal.GoalSubmission;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.commands.arguments.coordinates.Vec3Argument;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.phys.Vec3;
import dev.agaminggod.arenaagents.server.voice.VoiceConsentRegistry;
import dev.agaminggod.arenaagents.server.voice.VoiceCue;
import dev.agaminggod.arenaagents.server.voice.VoiceDirector;
import dev.agaminggod.arenaagents.server.voice.VoiceDirectorSavedData;
import dev.agaminggod.arenaagents.server.voice.VoiceProfile;
import dev.agaminggod.arenaagents.server.voice.VoiceScript;
import dev.agaminggod.arenaagents.server.voice.VoiceSubsystemRuntime;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class CodexAgentCommands {
	private static final Logger LOGGER = LoggerFactory.getLogger(CodexAgentCommands.class);
	private static final String DEFAULT_MODEL = "gpt-5.6-luna";
	private static final String DEFAULT_REASONING = "xhigh";
	private static final String DEFAULT_CODEX_SERVICE_TIER = "fast";
	private static final String PROVIDER_CODEX = "codex";
	private static final String PROVIDER_GEMINI = "gemini";
	private static final String PROVIDER_CLAUDE = "claude";
	private static final String PROVIDER_KIMI = "kimi";
	private static final String PROVIDER_CURSOR = "cursor";
	private static final List<String> VOICE_PROFILE_IDS = List.of(
			VoiceProfile.DEFAULT_PROFILE_ID,
			"voice.moss.v1", "voice.flint.v1", "voice.ember.v1", "voice.wren.v1", "voice.cedar.v1",
			"voice.sable.v1", "voice.quill.v1", "voice.rook.v1", "voice.juniper.v1", "voice.vale.v1",
			"voice.kestrel.v1", "voice.sol.v1", "voice.reed.v1", "voice.nova.v1", "voice.ash.v1", "voice.piper.v1");
	private static final List<String> VOICE_TONES = List.of(
			"neutral", "warm", "excited", "serious", "dramatic", "whisper", "robotic", "angry");
	private static final String ARGUMENT_AGENT = "agent";
	private static final String ARGUMENT_GAME_MODE = "game_mode";
	private static final String ARGUMENT_DRAFT = "draft_id";
	private static final String ARGUMENT_GROUP = "group";
	private static final String ARGUMENT_GROUP_MEMBERS = "members";
	private static final String ARGUMENT_MODEL = "model";
	private static final String ARGUMENT_NAME = "name";
	private static final String ARGUMENT_MESSAGE = "message";
	private static final String ARGUMENT_PROVIDER = "provider";
	private static final String ARGUMENT_PROMPT = "prompt";
	private static final String ARGUMENT_REASONING = "reasoning";
	private static final String ARGUMENT_SERVICE_TIER = "speed_mode";
	private static final DynamicCommandExceptionType COMMAND_FAILURE = new DynamicCommandExceptionType(
			message -> Component.literal(String.valueOf(message))
	);

	private CodexAgentCommands() {
	}

	public static void register() {
		CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) -> register(dispatcher));
	}

	static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
		dispatcher.register(Commands.literal("agent").then(goalDraftCommands()));
		dispatcher.register(
				Commands.literal("verbose")
						.requires(GoalControl::mayControl)
						.then(Commands.literal("on").executes(context -> verbose(context, true)))
						.then(Commands.literal("off").executes(context -> verbose(context, false)))
		);
		dispatcher.register(
				Commands.literal("codex")
						.then(Commands.literal("summon")
								.requires(GoalControl::mayControl)
								.executes(context -> summon(context, PROVIDER_CODEX, DEFAULT_MODEL, DEFAULT_REASONING, Optional.empty()))
								.then(providerSummon(PROVIDER_GEMINI))
								.then(providerSummon(PROVIDER_KIMI))
								.then(providerSummon(PROVIDER_CURSOR))
								.then(Commands.argument(ARGUMENT_MODEL, AgentModelArgumentType.model())
										.then(Commands.argument(ARGUMENT_REASONING, StringArgumentType.word())
												.executes(context -> summon(
												context,
												PROVIDER_CODEX,
												StringArgumentType.getString(context, ARGUMENT_MODEL),
														StringArgumentType.getString(context, ARGUMENT_REASONING),
														Optional.empty()
												))
												.then(Commands.argument(ARGUMENT_NAME, StringArgumentType.string())
														.executes(context -> summon(
														context,
														PROVIDER_CODEX,
														StringArgumentType.getString(context, ARGUMENT_MODEL),
																StringArgumentType.getString(context, ARGUMENT_REASONING),
																Optional.of(StringArgumentType.getString(context, ARGUMENT_NAME))
														))))))
						.then(configuredSummon())
						.then(Commands.literal("dm")
								.then(agentArgument().then(
										Commands.argument(ARGUMENT_MESSAGE, StringArgumentType.greedyString())
												.executes(CodexAgentCommands::directMessage)
								)))
						.then(Commands.literal("group")
								.requires(GoalControl::mayControl)
								.then(Commands.literal("save")
										.then(Commands.argument(ARGUMENT_GROUP, StringArgumentType.string())
												.then(Commands.argument(ARGUMENT_GROUP_MEMBERS, StringArgumentType.greedyString())
														.executes(CodexAgentCommands::saveGroup))))
								.then(Commands.literal("spawn")
										.then(groupArgument().executes(CodexAgentCommands::spawnGroup)))
								.then(Commands.literal("delete")
										.then(groupArgument().executes(CodexAgentCommands::deleteGroup))))
						.then(Commands.literal("voice-consent")
								.then(Commands.literal("on").executes(context -> voiceConsent(context, true)))
								.then(Commands.literal("off").executes(context -> voiceConsent(context, false)))
								.then(Commands.literal("status").executes(CodexAgentCommands::voiceConsentStatus)))
						.then(goalDraftCommands())
						.then(goalPromptCommand("start", GoalSubmission.Operation.START))
						.then(agentCommand("stop", CodexAgentManager::stop))
						.then(agentCommand("resume", CodexAgentManager::resume))
						.then(Commands.literal("respawn")
								.requires(GoalControl::mayControl)
								.then(agentArgument().executes(CodexAgentCommands::respawn)))
						.then(goalPromptCommand("queue", GoalSubmission.Operation.QUEUE))
						.then(promptCommand("steer", (manager, selector, prompt, ignored) -> manager.steer(selector, prompt)))
						.then(Commands.literal("status")
								.requires(GoalControl::mayControl)
								.executes(CodexAgentCommands::statusAll)
								.then(agentArgument().executes(CodexAgentCommands::statusOne)))
						.then(Commands.literal("list").requires(GoalControl::mayControl).executes(CodexAgentCommands::statusAll))
						.then(Commands.literal("remove")
								.requires(GoalControl::mayControl)
								.then(agentArgument().executes(CodexAgentCommands::remove)))
						.then(Commands.literal("auto")
								.requires(GoalControl::mayControl)
								.then(agentArgument().executes(CodexAgentCommands::toggleAutomatic)))
						.then(skitCommands())
						);
	}

	private static com.mojang.brigadier.builder.LiteralArgumentBuilder<CommandSourceStack> skitCommands() {
		var skit = Commands.literal("skit").requires(GoalControl::mayControl);
		skit.then(Commands.literal("on").executes(context -> toggleSkit(context, true)));
		skit.then(Commands.literal("off").executes(context -> toggleSkit(context, false)));
		skit.then(Commands.literal("status").executes(CodexAgentCommands::skitStatus));

		var summon = Commands.literal("summon");
		var provider = Commands.argument("provider", StringArgumentType.word())
				.suggests((context, builder) -> SharedSuggestionProvider.suggest(
						List.of(PROVIDER_CODEX, PROVIDER_GEMINI, PROVIDER_CLAUDE, PROVIDER_KIMI, PROVIDER_CURSOR), builder));
		provider.then(Commands.argument("name", StringArgumentType.string()).executes(CodexAgentCommands::skitSummon));
		provider.then(Commands.literal("model")
				.then(Commands.argument(ARGUMENT_MODEL, AgentModelArgumentType.model())
						.suggests((context, builder) -> SharedSuggestionProvider.suggest(
							skitModels(StringArgumentType.getString(context, ARGUMENT_PROVIDER)), builder))
						.then(Commands.argument("name", StringArgumentType.string()).executes(CodexAgentCommands::skitSummon))));
		summon.then(provider);
		skit.then(summon);

		var at = Commands.literal("at");
		var position = Commands.argument("position", Vec3Argument.vec3(false));
		var yaw = Commands.argument("yaw", FloatArgumentType.floatArg(-360.0F, 360.0F));
		yaw.then(Commands.argument("pitch", FloatArgumentType.floatArg(-90.0F, 90.0F))
				.executes(CodexAgentCommands::placeAt));
		position.then(yaw);
		at.then(position);
		var relative = Commands.literal("relative")
				.then(Commands.argument("right", DoubleArgumentType.doubleArg(-128.0D, 128.0D))
						.then(Commands.argument("up", DoubleArgumentType.doubleArg(-128.0D, 128.0D))
								.then(Commands.argument("forward", DoubleArgumentType.doubleArg(-128.0D, 128.0D))
										.executes(CodexAgentCommands::placeRelative))));
		var lookAt = Commands.literal("look_at")
				.then(Commands.argument("target", Vec3Argument.vec3(false))
						.executes(CodexAgentCommands::placeLookingAt));
		var place = Commands.literal("place");
		place.then(agentArgument()
				.then(Commands.literal("here").executes(CodexAgentCommands::placeHere))
				.then(at)
				.then(relative)
				.then(lookAt));
		skit.then(place);

		var create = Commands.literal("create");
		create.then(Commands.argument("script", StringArgumentType.word())
				.then(agentArgument().executes(CodexAgentCommands::createSkitScript)));
		var add = Commands.literal("add");
		var addScript = Commands.argument("script", StringArgumentType.word());
		var delay = Commands.argument("delay", IntegerArgumentType.integer(0, SkitStep.MAX_DELAY_TICKS));
		var addPosition = Commands.argument("position", Vec3Argument.vec3(false));
		var addYaw = Commands.argument("yaw", FloatArgumentType.floatArg(-360.0F, 360.0F));
		addYaw.then(Commands.argument("pitch", FloatArgumentType.floatArg(-90.0F, 90.0F))
				.executes(CodexAgentCommands::addSkitStep));
		addPosition.then(addYaw);
		delay.then(addPosition);
		addScript.then(delay);
		add.then(addScript);
		var play = Commands.literal("play");
		var playScript = Commands.argument("script", StringArgumentType.word())
				.executes(context -> playSkitScript(context, null));
		playScript.then(agentArgument().executes(context -> playSkitScript(
				context, StringArgumentType.getString(context, ARGUMENT_AGENT))));
		play.then(playScript);
		var script = Commands.literal("script");
		script.then(create).then(add).then(Commands.literal("list").executes(CodexAgentCommands::listSkitScripts))
				.then(Commands.literal("delete").then(Commands.argument("script", StringArgumentType.word())
						.executes(CodexAgentCommands::deleteSkitScript)))
				.then(play).then(Commands.literal("stop")
						.then(agentArgument().executes(CodexAgentCommands::stopSkitScript)))
				.then(actionCommand());
		skit.then(script);
		skit.then(voiceCommands());
		return skit;
	}

	private static com.mojang.brigadier.builder.LiteralArgumentBuilder<CommandSourceStack> actionCommand() {
		var action = Commands.literal("action");
		var script = Commands.argument("script", StringArgumentType.word());
		var type = Commands.argument("action", StringArgumentType.word())
				.executes(CodexAgentCommands::addSkitAction)
				.then(Commands.argument("args", StringArgumentType.greedyString())
						.executes(CodexAgentCommands::addSkitAction));
		script.then(type);
		action.then(script);
		return action;
	}

	private static com.mojang.brigadier.builder.LiteralArgumentBuilder<CommandSourceStack> voiceCommands() {
		var voice = Commands.literal("voice");
		var profile = Commands.literal("profile")
				.then(agentArgument().then(Commands.argument("profile", StringArgumentType.word())
						.suggests((context, builder) -> SharedSuggestionProvider.suggest(VOICE_PROFILE_IDS, builder))
						.executes(CodexAgentCommands::setVoiceProfile)
						.then(Commands.argument("tone", StringArgumentType.word())
							.suggests((context, builder) -> SharedSuggestionProvider.suggest(VOICE_TONES, builder))
							.executes(CodexAgentCommands::setVoiceProfile)
								.then(Commands.argument("speed", DoubleArgumentType.doubleArg(0.5D, 2.0D))
										.executes(CodexAgentCommands::setVoiceProfile)
										.then(Commands.argument("radius", IntegerArgumentType.integer(1, 128))
												.executes(CodexAgentCommands::setVoiceProfile))))));
		voice.then(profile);
		voice.then(Commands.literal("profiles").executes(CodexAgentCommands::listVoiceProfiles));

		var say = Commands.literal("say")
				.then(agentArgument().then(Commands.argument("text", StringArgumentType.greedyString())
						.executes(CodexAgentCommands::sayVoice)));
		voice.then(say);

		var script = Commands.literal("script");
		script.then(Commands.literal("create")
				.then(Commands.argument("name", StringArgumentType.word())
						.then(agentArgument().executes(CodexAgentCommands::createVoiceScript))));
		var add = Commands.literal("add")
				.then(Commands.argument("name", StringArgumentType.word())
						.then(Commands.argument("delay", IntegerArgumentType.integer(0, VoiceCue.MAX_DELAY_TICKS))
								.then(Commands.argument("text", StringArgumentType.greedyString())
										.executes(CodexAgentCommands::addVoiceCue))));
		script.then(add);
		script.then(Commands.literal("play")
				.then(Commands.argument("name", StringArgumentType.word())
						.executes(context -> playVoiceScript(context, null))
						.then(agentArgument().executes(context -> playVoiceScript(
								context, StringArgumentType.getString(context, ARGUMENT_AGENT))))));
		script.then(Commands.literal("list").executes(CodexAgentCommands::listVoiceScripts));
		script.then(Commands.literal("stop")
				.then(agentArgument().executes(CodexAgentCommands::stopVoiceScript)));
		voice.then(script);
		return voice;
	}

	private static com.mojang.brigadier.builder.LiteralArgumentBuilder<CommandSourceStack> goalDraftCommands() {
		return Commands.literal("goal")
				.then(goalDraftChoice("confirm", GoalDraftChoice.CONFIRM))
				.then(goalDraftChoice("replace", GoalDraftChoice.REPLACE))
				.then(goalDraftChoice("queue", GoalDraftChoice.QUEUE))
				.then(goalDraftChoice("cancel", GoalDraftChoice.CANCEL))
				.then(Commands.literal("complete")
						.requires(GoalControl::mayControl)
						.then(agentArgument().executes(CodexAgentCommands::confirmCompletion)));
	}

	private static int confirmCompletion(CommandContext<CommandSourceStack> context) throws CommandSyntaxException {
		try {
			AgentRecord record = manager(context).resolve(StringArgumentType.getString(context, ARGUMENT_AGENT));
			CodexAgentServerRuntime.confirmCurrentGoal(context.getSource().getServer(), record.agentId());
			context.getSource().sendSuccess(
					() -> Component.literal("Confirmed completion for " + manager(context).displayName(record) + "."),
					false
			);
			return 1;
		} catch (AgentDomainException exception) {
			throw commandFailure(exception);
		} catch (RuntimeException exception) {
			throw unexpectedFailure("goal completion confirmation", exception);
		}
	}

	private static com.mojang.brigadier.builder.LiteralArgumentBuilder<CommandSourceStack> goalDraftChoice(
			String literal,
			GoalDraftChoice choice
	) {
		return Commands.literal(literal).then(
				Commands.argument(ARGUMENT_DRAFT, StringArgumentType.word())
						.executes(context -> resolveGoalDraft(context, choice))
		);
	}

	private static int resolveGoalDraft(
			CommandContext<CommandSourceStack> context,
			GoalDraftChoice choice
	) throws CommandSyntaxException {
		try {
			if (choice != GoalDraftChoice.CANCEL) {
				CodexAgentServerRuntime.requireAutomation(context.getSource().getServer());
			}
			UUID draftId = UUID.fromString(StringArgumentType.getString(context, ARGUMENT_DRAFT));
			UUID actorId = context.getSource().getEntity() instanceof ServerPlayer player
					? player.getUUID()
					: new UUID(0L, 0L);
			Optional<CodexAgentManager.GoalDraftResult> resolved = manager(context).resolveGoalDraft(
					draftId, actorId, GoalControl.mayControl(context.getSource()), choice);
			if (resolved.isEmpty()) {
				context.getSource().sendSuccess(() -> Component.literal("Goal draft was already resolved."), false);
				return 0;
			}
			CodexAgentManager.GoalDraftResult result = resolved.orElseThrow();
			result.transition().ifPresent(transition -> reportTransition(
					context,
					switch (result.operation()) {
						case START -> "start";
						case REPLACE -> "replace";
						case QUEUE -> "queue";
						case CANCEL -> "cancel";
					},
					transition
			));
			if (result.operation() == dev.agaminggod.arenaagents.server.goal.GoalDraftResolution.Operation.CANCEL) {
				context.getSource().sendSuccess(() -> Component.literal("Cancelled goal draft " + draftId + "."), false);
			}
			return 1;
		} catch (AgentDomainException exception) {
			throw commandFailure(exception);
		} catch (IllegalArgumentException exception) {
			throw COMMAND_FAILURE.create("INVALID_GOAL_DRAFT_ID: Draft ID must be a UUID");
		} catch (RuntimeException exception) {
			throw unexpectedFailure("goal draft", exception);
		}
	}

	private static int verbose(CommandContext<CommandSourceStack> context, boolean enabled) {
		CodexAgentServerRuntime.setVerbose(context.getSource().getServer(), enabled);
		context.getSource().sendSuccess(
				() -> Component.literal("Verbose agent activity " + (enabled ? "enabled" : "disabled")
						+ " for operators for this server session."),
				false
		);
		return 1;
	}

	private static int toggleSkit(CommandContext<CommandSourceStack> context, boolean enabled) {
		boolean actual = SkitModeRuntime.setEnabled(context.getSource().getServer(), enabled);
		context.getSource().sendSuccess(() -> Component.literal("Agent skit mode " + (actual ? "enabled" : "disabled") + "."), false);
		return actual ? 1 : 0;
	}

	private static int skitStatus(CommandContext<CommandSourceStack> context) {
		SkitModeSavedData data = SkitModeSavedData.get(context.getSource().getServer());
		context.getSource().sendSuccess(() -> Component.literal("Agent skit mode is " + (data.enabled() ? "enabled" : "disabled")
				+ ". Saved placements: " + data.placements().size() + ", scripts: " + data.scripts().size() + "."), false);
		return data.enabled() ? 1 : 0;
	}

	private static int skitSummon(CommandContext<CommandSourceStack> context) throws CommandSyntaxException {
		try {
			SkitModeRuntime.requireEnabled(context.getSource().getServer());
			String provider = StringArgumentType.getString(context, "provider").toLowerCase(java.util.Locale.ROOT);
			String name = StringArgumentType.getString(context, "name");
			Vec3 position = context.getSource().getPosition();
			String model = explicitModel(context).orElseGet(() -> switch (provider) {
				case PROVIDER_CODEX -> DEFAULT_MODEL;
				case PROVIDER_GEMINI -> "gemini-3.1-pro";
				case PROVIDER_CLAUDE -> "claude-sonnet-4-6";
				case PROVIDER_KIMI -> "kimi-code/k3";
				case PROVIDER_CURSOR -> "composer-2.5";
				default -> throw new AgentDomainException("INVALID_PROVIDER", "Unsupported skit provider: " + provider);
			});
			String managerProvider = PROVIDER_CLAUDE.equals(provider) ? PROVIDER_GEMINI : provider;
			String reasoning = AgentControlCatalog.defaultReasoning(managerProvider, model);
			AgentRecord record = manager(context).summon(
					context.getSource().getLevel(), position, managerProvider, model, reasoning,
					PROVIDER_CODEX.equals(provider) ? DEFAULT_CODEX_SERVICE_TIER : "priority",
					Optional.of(name), AgentGameMode.SURVIVAL);
			context.getSource().sendSuccess(() -> Component.literal("Creating skit agent " + manager(context).displayName(record) + "."), false);
			return 1;
		} catch (AgentDomainException exception) {
			throw commandFailure(exception);
		} catch (RuntimeException exception) {
			throw unexpectedFailure("skit summon", exception);
		}
	}

	private static Optional<String> explicitModel(CommandContext<CommandSourceStack> context) {
		try {
			return Optional.of(context.getArgument(ARGUMENT_MODEL, String.class));
		} catch (IllegalArgumentException ignored) {
			return Optional.empty();
		}
	}

	private static List<String> skitModels(String provider) {
		String catalogProvider = PROVIDER_CLAUDE.equals(provider) ? PROVIDER_GEMINI : provider;
		try {
			return AgentControlCatalog.models(catalogProvider);
		} catch (IllegalArgumentException ignored) {
			return List.of();
		}
	}

	private static int placeHere(CommandContext<CommandSourceStack> context) throws CommandSyntaxException {
		try {
			if (!(context.getSource().getEntity() instanceof ServerPlayer player)) {
				throw new AgentDomainException("PLAYER_REQUIRED", "The 'here' placement must be run by an in-game player");
			}
			Vec3 position = player.position();
			SkitModeRuntime.place(manager(context), StringArgumentType.getString(context, ARGUMENT_AGENT),
					(ServerLevel) player.level(), position.x, position.y, position.z, player.getYRot(), player.getXRot());
			context.getSource().sendSuccess(() -> Component.literal("Placed agent at your current position and facing."), false);
			return 1;
		} catch (AgentDomainException exception) {
			throw commandFailure(exception);
		} catch (RuntimeException exception) {
			throw unexpectedFailure("skit place", exception);
		}
	}

	private static int placeAt(CommandContext<CommandSourceStack> context) throws CommandSyntaxException {
		try {
			Vec3 position = Vec3Argument.getVec3(context, "position");
			float yaw = FloatArgumentType.getFloat(context, "yaw");
			float pitch = FloatArgumentType.getFloat(context, "pitch");
			SkitModeRuntime.place(manager(context), StringArgumentType.getString(context, ARGUMENT_AGENT),
					context.getSource().getLevel(), position.x, position.y, position.z, yaw, pitch);
			context.getSource().sendSuccess(() -> Component.literal("Placed agent at " + position.x + " " + position.y + " " + position.z + "."), false);
			return 1;
		} catch (AgentDomainException exception) {
			throw commandFailure(exception);
		} catch (RuntimeException exception) {
			throw unexpectedFailure("skit place", exception);
		}
	}

	private static int placeRelative(CommandContext<CommandSourceStack> context) throws CommandSyntaxException {
		try {
			if (!(context.getSource().getEntity() instanceof ServerPlayer player)) {
				throw new AgentDomainException("PLAYER_REQUIRED", "Relative placement must be run by an in-game player");
			}
			SkitModeRuntime.placeRelative(manager(context), StringArgumentType.getString(context, ARGUMENT_AGENT), player,
					DoubleArgumentType.getDouble(context, "right"), DoubleArgumentType.getDouble(context, "up"),
					DoubleArgumentType.getDouble(context, "forward"));
			context.getSource().sendSuccess(() -> Component.literal("Placed agent relative to your position and facing."), false);
			return 1;
		} catch (AgentDomainException exception) {
			throw commandFailure(exception);
		} catch (RuntimeException exception) {
			throw unexpectedFailure("skit place relative", exception);
		}
	}

	private static int placeLookingAt(CommandContext<CommandSourceStack> context) throws CommandSyntaxException {
		try {
			if (!(context.getSource().getEntity() instanceof ServerPlayer player)) {
				throw new AgentDomainException("PLAYER_REQUIRED", "Look-at placement must be run by an in-game player");
			}
			SkitModeRuntime.placeLookingAt(manager(context), StringArgumentType.getString(context, ARGUMENT_AGENT), player,
					Vec3Argument.getVec3(context, "target"));
			context.getSource().sendSuccess(() -> Component.literal("Placed agent facing the target point."), false);
			return 1;
		} catch (AgentDomainException exception) {
			throw commandFailure(exception);
		} catch (RuntimeException exception) {
			throw unexpectedFailure("skit place look_at", exception);
		}
	}

	private static int addSkitAction(CommandContext<CommandSourceStack> context) throws CommandSyntaxException {
		try {
			String scriptName = StringArgumentType.getString(context, "script");
			String actionName = StringArgumentType.getString(context, "action").toLowerCase(java.util.Locale.ROOT);
			String rawArgs = context.getNodes().stream().anyMatch(node -> "args".equals(node.getNode().getName()))
					? StringArgumentType.getString(context, "args") : "";
			SkitScript script = SkitModeSavedData.get(context.getSource().getServer()).script(scriptName);
			if (script == null) throw new AgentDomainException("SKIT_SCRIPT_NOT_FOUND", "No skit script named " + scriptName);
			SkitPlacement endpoint = SkitModeRuntime.savedPlacement(manager(context), script.agentSelector())
					.orElseThrow(() -> new AgentDomainException("SKIT_PLACEMENT_NOT_FOUND", "Place the agent before adding an action"));
			SkitAction action = parseSkitAction(actionName, rawArgs);
			SkitModeRuntime.addAction(context.getSource().getServer(), scriptName, endpoint, action);
			context.getSource().sendSuccess(() -> Component.literal("Added " + actionName + " action to " + scriptName + "."), false);
			return 1;
		} catch (AgentDomainException exception) {
			throw commandFailure(exception);
		} catch (RuntimeException exception) {
			throw unexpectedFailure("skit script action", exception);
		}
	}

	private static SkitAction parseSkitAction(String name, String rawArgs) {
		List<String> args = rawArgs == null || rawArgs.isBlank() ? List.of() : Arrays.asList(rawArgs.strip().split("\\s+"));
		return switch (name) {
			case "move" -> SkitAction.move(integerArg(args, 0, "duration"), floatArg(args, 1, 1.0F), floatArg(args, 2, 0.0F), boolArg(args, 3, false));
			case "wait" -> SkitAction.waitTicks(integerArg(args, 0, "duration"));
			case "jump" -> SkitAction.jump();
			case "equip" -> SkitAction.equip(stringArg(args, 0, "item"));
			case "use" -> SkitAction.use(integerOptional(args, 0, 1));
			case "swing" -> SkitAction.swing();
			case "emote" -> SkitAction.emote(integerOptional(args, 0, 20), boolArg(args, 1, false));
			default -> throw new AgentDomainException("SKIT_ACTION_UNKNOWN", "Unknown action '" + name + "'. Use move, wait, jump, equip, use, swing, or emote.");
		};
	}

	private static int integerArg(List<String> args, int index, String name) {
		if (index >= args.size()) throw new AgentDomainException("SKIT_ACTION_ARGUMENT", name + " is required");
		try { return Integer.parseInt(args.get(index)); }
		catch (NumberFormatException exception) { throw new AgentDomainException("SKIT_ACTION_ARGUMENT", name + " must be an integer"); }
	}

	private static int integerOptional(List<String> args, int index, int fallback) {
		if (index >= args.size()) return fallback;
		try { return Integer.parseInt(args.get(index)); }
		catch (NumberFormatException exception) { throw new AgentDomainException("SKIT_ACTION_ARGUMENT", "duration must be an integer"); }
	}

	private static float floatArg(List<String> args, int index, float fallback) {
		if (index >= args.size()) return fallback;
		try { return Float.parseFloat(args.get(index)); }
		catch (NumberFormatException exception) { throw new AgentDomainException("SKIT_ACTION_ARGUMENT", "movement values must be numbers"); }
	}

	private static boolean boolArg(List<String> args, int index, boolean fallback) {
		if (index >= args.size()) return fallback;
		if ("true".equalsIgnoreCase(args.get(index))) return true;
		if ("false".equalsIgnoreCase(args.get(index))) return false;
		throw new AgentDomainException("SKIT_ACTION_ARGUMENT", "boolean values must be true or false");
	}

	private static String stringArg(List<String> args, int index, String name) {
		if (index >= args.size() || args.get(index).isBlank()) throw new AgentDomainException("SKIT_ACTION_ARGUMENT", name + " is required");
		return args.get(index);
	}

	private static int setVoiceProfile(CommandContext<CommandSourceStack> context) throws CommandSyntaxException {
		try {
			String selector = StringArgumentType.getString(context, ARGUMENT_AGENT);
			String profileId = StringArgumentType.getString(context, "profile");
			if (!VOICE_PROFILE_IDS.contains(profileId)) {
				throw new AgentDomainException("VOICE_PROFILE_UNKNOWN", "Unknown voice profile. Use /codex skit voice profiles");
			}
			String tone = getOptionalString(context, "tone", VoiceProfile.DEFAULT_TONE);
			double speed = getOptionalDouble(context, "speed", VoiceProfile.DEFAULT_SPEED);
			int radius = getOptionalInt(context, "radius", VoiceProfile.DEFAULT_RADIUS);
			AgentId agentId = manager(context).resolve(selector).agentId();
			VoiceDirector.setProfile(context.getSource().getServer(), agentId, new VoiceProfile(profileId, tone, speed, radius));
			context.getSource().sendSuccess(() -> Component.literal("Voice profile set for " + selector + "."), false);
			return 1;
		} catch (AgentDomainException exception) {
			throw commandFailure(exception);
		} catch (RuntimeException exception) {
			throw unexpectedFailure("skit voice profile", exception);
		}
	}

	private static int sayVoice(CommandContext<CommandSourceStack> context) throws CommandSyntaxException {
		try {
			AgentId agentId = manager(context).resolve(StringArgumentType.getString(context, ARGUMENT_AGENT)).agentId();
			VoiceDirector.say(context.getSource().getServer(), agentId, StringArgumentType.getString(context, "text"));
			return 1;
		} catch (AgentDomainException exception) {
			throw commandFailure(exception);
		} catch (RuntimeException exception) {
			throw unexpectedFailure("skit voice say", exception);
		}
	}

	private static int listVoiceProfiles(CommandContext<CommandSourceStack> context) {
		context.getSource().sendSuccess(() -> Component.literal("Voice catalog: " + String.join(", ", VOICE_PROFILE_IDS)), false);
		return 1;
	}

	private static int createVoiceScript(CommandContext<CommandSourceStack> context) throws CommandSyntaxException {
		try {
			VoiceDirector.createScript(context.getSource().getServer(), StringArgumentType.getString(context, "name"),
					StringArgumentType.getString(context, ARGUMENT_AGENT));
			context.getSource().sendSuccess(() -> Component.literal("Created voice script."), false);
			return 1;
		} catch (AgentDomainException exception) {
			throw commandFailure(exception);
		} catch (RuntimeException exception) {
			throw unexpectedFailure("skit voice script create", exception);
		}
	}

	private static int addVoiceCue(CommandContext<CommandSourceStack> context) throws CommandSyntaxException {
		try {
			VoiceDirector.addCue(context.getSource().getServer(), StringArgumentType.getString(context, "name"),
					new VoiceCue(IntegerArgumentType.getInteger(context, "delay"), StringArgumentType.getString(context, "text")));
			context.getSource().sendSuccess(() -> Component.literal("Added voice cue."), false);
			return 1;
		} catch (AgentDomainException exception) {
			throw commandFailure(exception);
		} catch (RuntimeException exception) {
			throw unexpectedFailure("skit voice script add", exception);
		}
	}

	private static int playVoiceScript(CommandContext<CommandSourceStack> context, String selectorOverride) throws CommandSyntaxException {
		try {
			VoiceScript script = VoiceDirector.play(manager(context), StringArgumentType.getString(context, "name"), selectorOverride);
			context.getSource().sendSuccess(() -> Component.literal("Playing voice script " + script.name() + "."), false);
			return 1;
		} catch (AgentDomainException exception) {
			throw commandFailure(exception);
		} catch (RuntimeException exception) {
			throw unexpectedFailure("skit voice script play", exception);
		}
	}

	private static int listVoiceScripts(CommandContext<CommandSourceStack> context) {
		List<VoiceScript> scripts = VoiceDirectorSavedData.get(context.getSource().getServer()).scripts();
		if (scripts.isEmpty()) {
			context.getSource().sendSuccess(() -> Component.literal("No voice scripts saved."), false);
			return 0;
		}
		for (VoiceScript script : scripts) context.getSource().sendSuccess(
				() -> Component.literal(script.name() + " | " + script.agentSelector() + " | " + script.cues().size() + " cues"), false);
		return scripts.size();
	}

	private static int stopVoiceScript(CommandContext<CommandSourceStack> context) throws CommandSyntaxException {
		try {
			AgentId agentId = manager(context).resolve(StringArgumentType.getString(context, ARGUMENT_AGENT)).agentId();
			VoiceDirector.stop(context.getSource().getServer(), agentId);
			context.getSource().sendSuccess(() -> Component.literal("Stopped voice playback."), false);
			return 1;
		} catch (AgentDomainException exception) {
			throw commandFailure(exception);
		} catch (RuntimeException exception) {
			throw unexpectedFailure("skit voice script stop", exception);
		}
	}

	private static String getOptionalString(CommandContext<CommandSourceStack> context, String name, String fallback) {
		try { return StringArgumentType.getString(context, name); }
		catch (IllegalArgumentException ignored) { return fallback; }
	}

	private static double getOptionalDouble(CommandContext<CommandSourceStack> context, String name, double fallback) {
		try { return DoubleArgumentType.getDouble(context, name); }
		catch (IllegalArgumentException ignored) { return fallback; }
	}

	private static int getOptionalInt(CommandContext<CommandSourceStack> context, String name, int fallback) {
		try { return IntegerArgumentType.getInteger(context, name); }
		catch (IllegalArgumentException ignored) { return fallback; }
	}

	private static int createSkitScript(CommandContext<CommandSourceStack> context) throws CommandSyntaxException {
		try {
			String name = StringArgumentType.getString(context, "script");
			String agent = StringArgumentType.getString(context, ARGUMENT_AGENT);
			SkitModeRuntime.createScript(context.getSource().getServer(), name, agent);
			context.getSource().sendSuccess(() -> Component.literal("Created skit script " + name + " for " + agent + "."), false);
			return 1;
		} catch (AgentDomainException exception) {
			throw commandFailure(exception);
		} catch (RuntimeException exception) {
			throw unexpectedFailure("skit script create", exception);
		}
	}

	private static int addSkitStep(CommandContext<CommandSourceStack> context) throws CommandSyntaxException {
		try {
			Vec3 position = Vec3Argument.getVec3(context, "position");
			SkitStep step = new SkitStep(IntegerArgumentType.getInteger(context, "delay"), new SkitPlacement(
					context.getSource().getLevel().dimension().identifier().toString(), position.x, position.y, position.z,
					FloatArgumentType.getFloat(context, "yaw"), FloatArgumentType.getFloat(context, "pitch")));
			SkitScript script = SkitModeRuntime.addStep(context.getSource().getServer(),
					StringArgumentType.getString(context, "script"), step);
			context.getSource().sendSuccess(() -> Component.literal("Added step " + script.steps().size() + " to " + script.name() + "."), false);
			return script.steps().size();
		} catch (AgentDomainException exception) {
			throw commandFailure(exception);
		} catch (RuntimeException exception) {
			throw unexpectedFailure("skit script add", exception);
		}
	}

	private static int listSkitScripts(CommandContext<CommandSourceStack> context) {
		List<SkitScript> scripts = SkitModeSavedData.get(context.getSource().getServer()).scripts();
		if (scripts.isEmpty()) {
			context.getSource().sendSuccess(() -> Component.literal("No skit scripts saved."), false);
			return 0;
		}
		for (SkitScript script : scripts) context.getSource().sendSuccess(
				() -> Component.literal(script.name() + " | " + script.agentSelector() + " | " + script.steps().size() + " steps"), false);
		return scripts.size();
	}

	private static int deleteSkitScript(CommandContext<CommandSourceStack> context) throws CommandSyntaxException {
		try {
			String name = StringArgumentType.getString(context, "script");
			SkitModeRuntime.deleteScript(context.getSource().getServer(), name);
			context.getSource().sendSuccess(() -> Component.literal("Deleted skit script " + name + "."), false);
			return 1;
		} catch (AgentDomainException exception) {
			throw commandFailure(exception);
		} catch (RuntimeException exception) {
			throw unexpectedFailure("skit script delete", exception);
		}
	}

	private static int playSkitScript(CommandContext<CommandSourceStack> context, String selectorOverride) throws CommandSyntaxException {
		try {
			String name = StringArgumentType.getString(context, "script");
			SkitScript script = SkitModeRuntime.play(manager(context), name, selectorOverride);
			context.getSource().sendSuccess(() -> Component.literal("Playing skit " + script.name() + "."), false);
			return 1;
		} catch (AgentDomainException exception) {
			throw commandFailure(exception);
		} catch (RuntimeException exception) {
			throw unexpectedFailure("skit script play", exception);
		}
	}

	private static int stopSkitScript(CommandContext<CommandSourceStack> context) throws CommandSyntaxException {
		try {
			String selector = StringArgumentType.getString(context, ARGUMENT_AGENT);
			SkitModeRuntime.stop(context.getSource().getServer(), selector);
			context.getSource().sendSuccess(() -> Component.literal("Stopped skit playback for " + selector + "."), false);
			return 1;
		} catch (AgentDomainException exception) {
			throw commandFailure(exception);
		} catch (RuntimeException exception) {
			throw unexpectedFailure("skit script stop", exception);
		}
	}

	private static com.mojang.brigadier.builder.LiteralArgumentBuilder<CommandSourceStack> configuredSummon() {
		return Commands.literal("summon-configured").requires(GoalControl::mayControl).then(
				Commands.argument(ARGUMENT_PROVIDER, StringArgumentType.word())
						.suggests((context, builder) -> SharedSuggestionProvider.suggest(
								List.of(PROVIDER_CODEX, PROVIDER_GEMINI, PROVIDER_KIMI, PROVIDER_CURSOR), builder))
						.then(Commands.argument(ARGUMENT_MODEL, AgentModelArgumentType.model()).then(
								Commands.argument(ARGUMENT_REASONING, StringArgumentType.word()).then(
										Commands.argument(ARGUMENT_SERVICE_TIER, StringArgumentType.word())
												.suggests((context, builder) -> SharedSuggestionProvider.suggest(
														List.of("priority", "fast"), builder))
												.then(Commands.argument(ARGUMENT_GAME_MODE, StringArgumentType.word())
												.suggests((context, builder) -> SharedSuggestionProvider.suggest(
														List.of("survival", "creative", "adventure"), builder))
												.then(Commands.argument(ARGUMENT_NAME, StringArgumentType.string())
														.executes(context -> summon(
																context,
																StringArgumentType.getString(context, ARGUMENT_PROVIDER),
														StringArgumentType.getString(context, ARGUMENT_MODEL),
														StringArgumentType.getString(context, ARGUMENT_REASONING),
														StringArgumentType.getString(context, ARGUMENT_SERVICE_TIER),
														Optional.of(StringArgumentType.getString(context, ARGUMENT_NAME)).filter(value -> !value.isBlank()),
														AgentGameMode.parse(StringArgumentType.getString(context, ARGUMENT_GAME_MODE))
												)))))
		)));
	}
	private static com.mojang.brigadier.builder.LiteralArgumentBuilder<CommandSourceStack> providerSummon(String provider) {
		return Commands.literal(provider).then(
				Commands.argument(ARGUMENT_MODEL, AgentModelArgumentType.model()).then(
						Commands.argument(ARGUMENT_REASONING, StringArgumentType.word())
								.executes(context -> summon(
										context,
										provider,
										StringArgumentType.getString(context, ARGUMENT_MODEL),
										StringArgumentType.getString(context, ARGUMENT_REASONING),
										Optional.empty()
								))
								.then(Commands.argument(ARGUMENT_NAME, StringArgumentType.string())
										.executes(context -> summon(
												context,
												provider,
												StringArgumentType.getString(context, ARGUMENT_MODEL),
												StringArgumentType.getString(context, ARGUMENT_REASONING),
												Optional.of(StringArgumentType.getString(context, ARGUMENT_NAME))
										)))
				)
		);
	}

	private static com.mojang.brigadier.builder.LiteralArgumentBuilder<CommandSourceStack> promptCommand(
			String literal,
			PromptOperation operation
	) {
		return Commands.literal(literal).requires(GoalControl::mayControl).then(
				agentArgument().then(
						Commands.argument(ARGUMENT_PROMPT, StringArgumentType.greedyString())
								.executes(context -> runPromptOperation(context, literal, operation))
				)
		);
	}

	private static com.mojang.brigadier.builder.LiteralArgumentBuilder<CommandSourceStack> goalPromptCommand(
			String literal,
			GoalSubmission.Operation operation
	) {
		return Commands.literal(literal).requires(GoalControl::mayControl).then(
				agentArgument().then(
						Commands.argument(ARGUMENT_PROMPT, StringArgumentType.greedyString())
								.executes(context -> runGoalPromptOperation(context, literal, operation))
				)
		);
	}

	private static com.mojang.brigadier.builder.LiteralArgumentBuilder<CommandSourceStack> agentCommand(
			String literal,
			AgentOperation operation
	) {
		return Commands.literal(literal).requires(GoalControl::mayControl).then(
				agentArgument().executes(context -> runAgentOperation(context, literal, operation))
		);
	}

	private static com.mojang.brigadier.builder.RequiredArgumentBuilder<CommandSourceStack, String> agentArgument() {
		return Commands.argument(ARGUMENT_AGENT, StringArgumentType.word())
				.suggests((context, builder) -> SharedSuggestionProvider.suggest(
						manager(context).selectors(),
						builder
				));
	}

	private static int summon(
			CommandContext<CommandSourceStack> context,
			String provider,
			String model,
			String reasoning,
			Optional<String> userName
	)
			throws CommandSyntaxException {
		return summon(context, provider, model, reasoning, userName, AgentGameMode.SURVIVAL);
	}

	private static int summon(
			CommandContext<CommandSourceStack> context,
			String provider,
			String model,
			String reasoning,
			Optional<String> userName,
			AgentGameMode gameMode
	)
			throws CommandSyntaxException {
		return summon(context, provider, model, reasoning,
				PROVIDER_CODEX.equals(provider) ? DEFAULT_CODEX_SERVICE_TIER : "priority", userName, gameMode);
	}

	private static int summon(
			CommandContext<CommandSourceStack> context,
			String provider,
			String model,
			String reasoning,
			String serviceTier,
			Optional<String> userName,
			AgentGameMode gameMode
	)
			throws CommandSyntaxException {
		try {
			Vec3 summonPosition = context.getSource().getPosition();
			if (context.getSource().getEntity() != null) {
				summonPosition = AgentSpawnPlacement.availableNear(
						context.getSource().getLevel(),
						summonPosition,
						context.getSource().getEntity().getLookAngle()
				);
			}
			CodexAgentManager manager = manager(context);
			AgentRecord record = manager.summon(
					context.getSource().getLevel(),
					summonPosition,
					provider,
					model,
					reasoning,
					serviceTier,
					userName,
					gameMode
			);
			context.getSource().sendSuccess(
					() -> Component.literal("Creating " + manager.displayName(record) + ". It will be ready when its player joins."),
					false
			);
			return 1;
		} catch (AgentDomainException exception) {
			throw commandFailure(exception);
		} catch (RuntimeException exception) {
			throw unexpectedFailure("summon", exception);
		}
	}

	private static int runPromptOperation(
			CommandContext<CommandSourceStack> context,
			String operationName,
			PromptOperation operation
	) throws CommandSyntaxException {
		try {
			CodexAgentServerRuntime.requireAutomation(context.getSource().getServer());
			String selector = StringArgumentType.getString(context, ARGUMENT_AGENT);
			String prompt = StringArgumentType.getString(context, ARGUMENT_PROMPT);
			AgentTransition transition = operation.apply(manager(context), selector, prompt, context.getSource().getLevel());
			reportTransition(context, operationName, transition);
			return 1;
		} catch (AgentDomainException exception) {
			throw commandFailure(exception);
		} catch (RuntimeException exception) {
			throw unexpectedFailure(operationName, exception);
		}
	}

	private static int runGoalPromptOperation(
			CommandContext<CommandSourceStack> context,
			String operationName,
			GoalSubmission.Operation operation
	) throws CommandSyntaxException {
		try {
			CodexAgentServerRuntime.requireAutomation(context.getSource().getServer());
			String selector = StringArgumentType.getString(context, ARGUMENT_AGENT);
			String prompt = StringArgumentType.getString(context, ARGUMENT_PROMPT);
			Optional<UUID> requester = context.getSource().getEntity() instanceof ServerPlayer player
					? Optional.of(player.getUUID()) : Optional.empty();
			GoalSubmission submission = CodexAgentServerRuntime.submitGoal(
					context.getSource().getServer(), selector, prompt, context.getSource().getLevel(), requester, operation);
			if (submission.transition().isPresent()) {
				reportTransition(context, operationName, submission.transition().orElseThrow());
			} else {
				var draft = submission.pendingDraft().orElseThrow();
				String name = manager(context).displayName(manager(context).registry().require(draft.agentId()));
				context.getSource().sendSuccess(() -> Component.literal(
						"The coordinator is interpreting the goal for " + name
								+ ". It will " + operationName + " automatically after server validation."), false);
			}
			return 1;
		} catch (AgentDomainException exception) {
			throw commandFailure(exception);
		} catch (RuntimeException exception) {
			throw unexpectedFailure(operationName, exception);
		}
	}

	private static com.mojang.brigadier.builder.RequiredArgumentBuilder<CommandSourceStack, String> groupArgument() {
		return Commands.argument(ARGUMENT_GROUP, StringArgumentType.string())
				.suggests((context, builder) -> SharedSuggestionProvider.suggest(
						manager(context).groups().stream()
								.map(AgentGroup::name)
								.map(StringArgumentType::escapeIfRequired)
								.toList(),
						builder
				));
	}

	private static int directMessage(CommandContext<CommandSourceStack> context) throws CommandSyntaxException {
		try {
			if (!(context.getSource().getEntity() instanceof ServerPlayer player)) {
				throw new AgentDomainException("PLAYER_REQUIRED", "Only an in-game player can send an agent DM");
			}
			CodexAgentManager manager = manager(context);
			AgentRecord target = manager.resolve(StringArgumentType.getString(context, ARGUMENT_AGENT));
			CodexAgentServerRuntime.sendDirectMessage(
					context.getSource().getServer(),
					player,
					target.agentId(),
					StringArgumentType.getString(context, ARGUMENT_MESSAGE)
			);
			return 1;
		} catch (AgentDomainException exception) {
			throw commandFailure(exception);
		} catch (RuntimeException exception) {
			throw unexpectedFailure("dm", exception);
		}
	}

	private static int saveGroup(CommandContext<CommandSourceStack> context) throws CommandSyntaxException {
		try {
			List<AgentId> members = Arrays.stream(StringArgumentType.getString(context, ARGUMENT_GROUP_MEMBERS).strip().split("\\s+"))
					.filter(value -> !value.isBlank())
					.map(AgentId::parse)
					.toList();
			AgentGroup group = manager(context).saveGroup(
					StringArgumentType.getString(context, ARGUMENT_GROUP),
					members
			);
			context.getSource().sendSuccess(
					() -> Component.literal("Saved " + group.name() + " with " + group.memberIds().size()
							+ (group.memberIds().size() == 1 ? " agent." : " agents.")),
					false
			);
			return group.memberIds().size();
		} catch (AgentDomainException exception) {
			throw commandFailure(exception);
		} catch (RuntimeException exception) {
			throw unexpectedFailure("group save", exception);
		}
	}

	private static int spawnGroup(CommandContext<CommandSourceStack> context) throws CommandSyntaxException {
		try {
			String name = StringArgumentType.getString(context, ARGUMENT_GROUP);
			AgentGroupSpawnCoordinator.Result result = manager(context).spawnGroup(name);
			String missing = result.missingIds().isEmpty() ? ""
					: " Missing: " + String.join(", ", result.missingIds().stream().map(AgentId::shortValue).toList()) + ".";
			context.getSource().sendSuccess(
					() -> Component.literal("Group " + name + ": " + result.present() + " already present, "
							+ result.restoring() + " restoring." + missing),
					false
			);
			return result.present() + result.restoring();
		} catch (AgentDomainException exception) {
			throw commandFailure(exception);
		} catch (RuntimeException exception) {
			throw unexpectedFailure("group spawn", exception);
		}
	}

	private static int deleteGroup(CommandContext<CommandSourceStack> context) throws CommandSyntaxException {
		try {
			AgentGroup group = manager(context).deleteGroup(StringArgumentType.getString(context, ARGUMENT_GROUP));
			context.getSource().sendSuccess(() -> Component.literal("Deleted saved group " + group.name() + "."), false);
			return 1;
		} catch (AgentDomainException exception) {
			throw commandFailure(exception);
		} catch (RuntimeException exception) {
			throw unexpectedFailure("group delete", exception);
		}
	}

	private static int voiceConsent(CommandContext<CommandSourceStack> context, boolean enabled) throws CommandSyntaxException {
		ServerPlayer player = requirePlayer(context);
		requireVoiceAvailable(context.getSource());
		if (enabled) VoiceConsentRegistry.grant(context.getSource().getServer(), player.getUUID());
		else VoiceConsentRegistry.revoke(context.getSource().getServer(), player.getUUID());
		context.getSource().sendSuccess(
				() -> Component.literal(voiceConsentConfirmation(enabled)),
				false
		);
		return enabled ? 1 : 0;
	}

	static String voiceConsentConfirmation(boolean enabled) {
		return "Agent voice transcription " + (enabled ? "enabled for this session." : "disabled.");
	}

	private static int voiceConsentStatus(CommandContext<CommandSourceStack> context) throws CommandSyntaxException {
		ServerPlayer player = requirePlayer(context);
		requireVoiceAvailable(context.getSource());
		boolean enabled = VoiceConsentRegistry.granted(context.getSource().getServer(), player.getUUID());
		context.getSource().sendSuccess(
				() -> Component.literal("Agent voice transcription is " + (enabled ? "enabled." : "disabled.")),
				false
		);
		return enabled ? 1 : 0;
	}

	private static void requireVoiceAvailable(CommandSourceStack source) throws CommandSyntaxException {
		if (!VoiceSubsystemRuntime.available(source.getServer())) {
			throw COMMAND_FAILURE.create(voiceUnavailableMessage());
		}
	}

	static String voiceUnavailableMessage() {
		return "VOICE_UNAVAILABLE: Install and start the Arena Agents Voice add-on with Simple Voice Chat";
	}

	private static ServerPlayer requirePlayer(CommandContext<CommandSourceStack> context) throws CommandSyntaxException {
		if (context.getSource().getEntity() instanceof ServerPlayer player) return player;
		throw COMMAND_FAILURE.create("PLAYER_REQUIRED: Only an in-game player can change voice consent");
	}

	private static int runAgentOperation(
			CommandContext<CommandSourceStack> context,
			String operationName,
			AgentOperation operation
	) throws CommandSyntaxException {
		try {
			if (operationName.equals("resume")) {
				CodexAgentServerRuntime.requireAutomation(context.getSource().getServer());
			}
			String selector = StringArgumentType.getString(context, ARGUMENT_AGENT);
			AgentTransition transition = operation.apply(manager(context), selector);
			reportTransition(context, operationName, transition);
			return 1;
		} catch (AgentDomainException exception) {
			throw commandFailure(exception);
		} catch (RuntimeException exception) {
			throw unexpectedFailure(operationName, exception);
		}
	}

	private static int statusOne(CommandContext<CommandSourceStack> context) throws CommandSyntaxException {
		try {
			AgentRecord record = manager(context).resolve(StringArgumentType.getString(context, ARGUMENT_AGENT));
			context.getSource().sendSuccess(() -> Component.literal(formatStatus(record)), false);
			return 1;
		} catch (AgentDomainException exception) {
			throw commandFailure(exception);
		} catch (RuntimeException exception) {
			throw unexpectedFailure("status", exception);
		}
	}

	private static int statusAll(CommandContext<CommandSourceStack> context) {
		List<AgentRecord> records = manager(context).records();
		if (records.isEmpty()) {
			context.getSource().sendSuccess(() -> Component.literal("You have not created any agents yet."), false);
			return 0;
		}
		for (AgentRecord record : records) {
			context.getSource().sendSuccess(() -> Component.literal(formatStatus(record)), false);
		}
		return records.size();
	}

	private static int remove(CommandContext<CommandSourceStack> context) throws CommandSyntaxException {
		try {
			CodexAgentManager manager = manager(context);
			AgentRecord removed = manager.remove(StringArgumentType.getString(context, ARGUMENT_AGENT));
			context.getSource().sendSuccess(
					() -> Component.literal("Removed " + manager.displayName(removed) + "."),
					false
			);
			return 1;
		} catch (AgentDomainException exception) {
			throw commandFailure(exception);
		} catch (RuntimeException exception) {
			throw unexpectedFailure("remove", exception);
		}
	}

	private static int respawn(CommandContext<CommandSourceStack> context) throws CommandSyntaxException {
		try {
			CodexAgentManager manager = manager(context);
			AgentRecord record = manager.requestRespawn(StringArgumentType.getString(context, ARGUMENT_AGENT));
			context.getSource().sendSuccess(
					() -> Component.literal("Respawning " + manager.displayName(record) + "..."),
					false
			);
			return 1;
		} catch (AgentDomainException exception) {
			throw commandFailure(exception);
		} catch (RuntimeException exception) {
			throw unexpectedFailure("respawn", exception);
		}
	}

	private static int toggleAutomatic(CommandContext<CommandSourceStack> context) throws CommandSyntaxException {
		try {
			String selector = StringArgumentType.getString(context, ARGUMENT_AGENT);
			boolean enabled = manager(context).toggleAutomaticProgress(selector);
			context.getSource().sendSuccess(
					() -> Component.literal("Automatic agent progress " + (enabled ? "enabled" : "disabled")),
					false
			);
			return enabled ? 1 : 0;
		} catch (AgentDomainException exception) {
			throw commandFailure(exception);
		} catch (RuntimeException exception) {
			throw unexpectedFailure("auto", exception);
		}
	}

	private static void reportTransition(
			CommandContext<CommandSourceStack> context,
			String operation,
			AgentTransition transition
	) {
		String name = manager(context).displayName(transition.after());
		String message = switch (operation) {
			case "start" -> "Starting a task for " + name + "...";
			case "queue" -> "Added a task to " + name + "'s queue.";
			case "replace" -> "Replacing " + name + "'s current task...";
			case "steer" -> "Updating " + name + "'s current task...";
			case "stop" -> "Paused " + name + ".";
			case "resume" -> "Resuming " + name + "...";
			default -> "Updated " + name + ".";
		};
		context.getSource().sendSuccess(
				() -> Component.literal(message),
				false
		);
	}

	private static String formatStatus(AgentRecord record) {
		String currentGoal = record.currentGoal().map(goal -> goal.prompt()).orElse("none");
		return dev.agaminggod.arenaagents.agent.AgentIdentity.displayName(record.agentId(), record.profile())
				+ " | " + dev.agaminggod.arenaagents.control.AgentControlPresentation.stateLabel(record.state().name())
				+ ". Current task: " + currentGoal
				+ ". Queued tasks: " + record.queuedGoals().size() + ".";
	}

	private static CodexAgentManager manager(CommandContext<CommandSourceStack> context) {
		return CodexAgentManager.get(context.getSource().getServer());
	}

	private static CommandSyntaxException commandFailure(AgentDomainException exception) {
		return COMMAND_FAILURE.create(exception.code() + ": " + exception.getMessage());
	}

	private static CommandSyntaxException unexpectedFailure(String operation, RuntimeException exception) {
		LOGGER.error("Unexpected /codex {} failure", operation, exception);
		return COMMAND_FAILURE.create("INTERNAL_ERROR: " + operation + " failed; see the server log");
	}

	@FunctionalInterface
	private interface PromptOperation {
		AgentTransition apply(CodexAgentManager manager, String selector, String prompt, ServerLevel sourceLevel);
	}

	@FunctionalInterface
	private interface AgentOperation {
		AgentTransition apply(CodexAgentManager manager, String selector);
	}
}
