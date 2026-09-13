package dev.agaminggod.arenaagents.verification;

import com.google.gson.JsonObject;
import dev.agaminggod.arenaagents.agent.AgentRegistryVerification;
import dev.agaminggod.arenaagents.agent.GoalSpecVerification;
import dev.agaminggod.arenaagents.agent.AgentIdentityVerification;
import dev.agaminggod.arenaagents.server.AgentActivityPresentationVerification;
import dev.agaminggod.arenaagents.server.AgentVerbosePresentationVerification;
import dev.agaminggod.arenaagents.server.AgentDeathCaptureVerification;
import dev.agaminggod.arenaagents.server.AgentControlSyncVerification;
import dev.agaminggod.arenaagents.server.ChunkedSavedPayloadVerification;
import dev.agaminggod.arenaagents.server.OfflineAgentProfileLookupVerification;
import dev.agaminggod.arenaagents.server.VoiceConsentCommandVerification;
import dev.agaminggod.arenaagents.server.SkitModeVerification;
import dev.agaminggod.arenaagents.client.ArenaSpectatorStateVerification;
import dev.agaminggod.arenaagents.client.camera.CameraPathVerification;
import dev.agaminggod.arenaagents.client.gui.SkitDirectorLayoutVerification;
import dev.agaminggod.arenaagents.client.gui.AgentControlLayoutVerification;
import dev.agaminggod.arenaagents.client.gui.AgentControlSubmissionVerification;
import dev.agaminggod.arenaagents.client.gui.AgentRosterGridLayoutVerification;
import dev.agaminggod.arenaagents.client.gui.AgentRosterGridVerification;
import dev.agaminggod.arenaagents.client.gui.ConsoleThemeVerification;
import dev.agaminggod.arenaagents.client.gui.LiveArenaLayoutVerification;
import dev.agaminggod.arenaagents.client.gui.LocalizationVerification;
import dev.agaminggod.arenaagents.client.gui.ScenarioResultsLayoutVerification;
import dev.agaminggod.arenaagents.client.gui.scenario.ScenarioSetupStateVerification;
import dev.agaminggod.arenaagents.client.gui.scenario.ScenarioSetupLayoutVerification;
import dev.agaminggod.arenaagents.client.control.AgentClientPresentationVerification;
import dev.agaminggod.arenaagents.client.navigation.LocalPathfinderVerification;
import dev.agaminggod.arenaagents.control.AgentControlVerification;
import dev.agaminggod.arenaagents.control.AgentControlSelectionStateVerification;
import dev.agaminggod.arenaagents.control.AgentRosterViewStateVerification;
import dev.agaminggod.arenaagents.protocol.ActionCommand;
import dev.agaminggod.arenaagents.protocol.ActionResult;
import dev.agaminggod.arenaagents.protocol.ActionState;
import dev.agaminggod.arenaagents.protocol.ActionType;
import dev.agaminggod.arenaagents.protocol.ProtocolCodec;
import dev.agaminggod.arenaagents.protocol.ProtocolConstants;
import dev.agaminggod.arenaagents.protocol.ProtocolException;
import dev.agaminggod.arenaagents.server.GoalControlVerification;
import dev.agaminggod.arenaagents.server.GoalDraftAdvancementRevalidationVerification;
import dev.agaminggod.arenaagents.server.QueuedGoalRevalidationVerification;
import dev.agaminggod.arenaagents.server.AgentSavedDataGoalDraftVerification;
import dev.agaminggod.arenaagents.server.goal.GoalCompilerVerification;
import dev.agaminggod.arenaagents.server.goal.GoalInventoryCapacityVerification;
import dev.agaminggod.arenaagents.server.conversation.AgentConversationRouterVerification;
import dev.agaminggod.arenaagents.server.conversation.NativeAgentWhisperTargetsVerification;
import dev.agaminggod.arenaagents.server.group.AgentGroupRegistryVerification;
import dev.agaminggod.arenaagents.server.AgentModelArgumentVerification;
import dev.agaminggod.arenaagents.server.CoordinatorRecoveryFaultMatrixVerification;
import dev.agaminggod.arenaagents.server.BundledCoordinatorInstallerVerification;
import dev.agaminggod.arenaagents.server.CoordinatorProcessOwnershipVerification;
import dev.agaminggod.arenaagents.server.CoordinatorStartupSmokeVerification;
import dev.agaminggod.arenaagents.server.CoordinatorLogRotationVerification;
import dev.agaminggod.arenaagents.server.NodeRuntimeLocatorVerification;
import dev.agaminggod.arenaagents.server.CoordinatorLaunchPolicyVerification;
import dev.agaminggod.arenaagents.server.CoordinatorVoiceEndpointVerification;
import dev.agaminggod.arenaagents.server.CoordinatorVoiceEndpointRefreshVerification;
import dev.agaminggod.arenaagents.server.CoordinatorVoiceTimeoutLifecycleVerification;
import dev.agaminggod.arenaagents.server.CodexAgentServerRuntimeVoiceStartVerification;
import dev.agaminggod.arenaagents.server.AgentSpawnPlacementVerification;
import dev.agaminggod.arenaagents.server.OfflineAgentPlayersVerification;
import dev.agaminggod.arenaagents.server.PendingSpawnCancellationLedgerVerification;
import dev.agaminggod.arenaagents.server.bridge.BridgeEnvelopeCodecVerification;
import dev.agaminggod.arenaagents.server.bridge.BoundedServerTaskQueueVerification;
import dev.agaminggod.arenaagents.server.bridge.ProgramActionLedgerVerification;
import dev.agaminggod.arenaagents.server.bridge.TerminalResultLedgerVerification;
import dev.agaminggod.arenaagents.server.bridge.CoordinatorStatusVerification;
import dev.agaminggod.arenaagents.server.bridge.MultiplexedServerBridgeVerification;
import dev.agaminggod.arenaagents.server.bridge.AgentVerboseVerification;
import dev.agaminggod.arenaagents.server.bridge.SingleBrainBoundaryVerification;
import dev.agaminggod.arenaagents.server.AgentRecoverySpawnPolicyVerification;
import dev.agaminggod.arenaagents.server.AgentRespawnSpawnPolicyVerification;
import dev.agaminggod.arenaagents.server.perception.BlockObservationLazyVisibilityVerification;
import dev.agaminggod.arenaagents.server.perception.BlockObservationOrderingVerification;
import dev.agaminggod.arenaagents.server.perception.InventoryObservationSlotsVerification;
import dev.agaminggod.arenaagents.server.perception.ObservationBudgetVerification;
import dev.agaminggod.arenaagents.server.perception.ServerObservationRayTargetVerification;
import dev.agaminggod.arenaagents.server.perception.ServerObservationTagCacheVerification;
import dev.agaminggod.arenaagents.server.perception.ServerObservationInventorySnapshotVerification;
import dev.agaminggod.arenaagents.server.perception.ServerObservationWireBudgetVerification;
import dev.agaminggod.arenaagents.server.runtime.ActionProgressTrackerVerification;
import dev.agaminggod.arenaagents.server.runtime.AdvancedInteractionRollbackVerification;
import dev.agaminggod.arenaagents.server.runtime.BlockPlacementPostconditionVerification;
import dev.agaminggod.arenaagents.server.runtime.BlockPlacementAttemptPolicyVerification;
import dev.agaminggod.arenaagents.server.runtime.DesiredBlockStateVerification;
import dev.agaminggod.arenaagents.server.runtime.ResourceLeaseManagerVerification;
import dev.agaminggod.arenaagents.server.runtime.RecipeActionVerification;
import dev.agaminggod.arenaagents.server.runtime.ServerActionExecutorVerification;
import dev.agaminggod.arenaagents.server.goal.GoalVerificationRuntimeVerification;
import dev.agaminggod.arenaagents.server.runtime.ActionSuccessLedgerVerification;
import dev.agaminggod.arenaagents.server.runtime.input.InputStateVerification;
import dev.agaminggod.arenaagents.server.perception.AttentionHazardVerification;
import dev.agaminggod.arenaagents.server.runtime.menu.MenuCapabilityRegistryVerification;
import dev.agaminggod.arenaagents.server.voice.VoiceSubsystemVerification;
import dev.agaminggod.arenaagents.server.voice.VoiceDirectorVerification;
import dev.agaminggod.arenaagents.server.runtime.transaction.EquipmentAndUseVerification;
import dev.agaminggod.arenaagents.server.runtime.transaction.TransactionPostconditionVerification;
import dev.agaminggod.arenaagents.server.runtime.transaction.TransactionProtocolVerification;
import dev.agaminggod.arenaagents.server.runtime.controller.ServerPathPlannerVerification;
import dev.agaminggod.arenaagents.server.runtime.controller.MinecraftNavigationWorldVerification;
import dev.agaminggod.arenaagents.server.runtime.controller.NavigationProgressVerification;
import dev.agaminggod.arenaagents.server.runtime.controller.SurvivalReflexVerification;
import dev.agaminggod.arenaagents.server.runtime.controller.ItemPickupProgressVerification;
import dev.agaminggod.arenaagents.scenario.ScenarioCoreVerification;
import dev.agaminggod.arenaagents.scenario.ScenarioActivationFailurePolicyVerification;
import dev.agaminggod.arenaagents.scenario.ArenaSpectatorSnapshotVerification;
import dev.agaminggod.arenaagents.scenario.ScenarioBuildProgressVerification;
import dev.agaminggod.arenaagents.scenario.ScenarioLaunchRuntimeVerification;
import dev.agaminggod.arenaagents.scenario.ScenarioMatchResultVerification;
import dev.agaminggod.arenaagents.scenario.ScenarioPreflightVerification;
import dev.agaminggod.arenaagents.scenario.ScenarioRecoveryVerification;
import dev.agaminggod.arenaagents.scenario.ScenarioArenaModuleVerification;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

public final class VerificationMain {
	private static final long ISSUED_AT_EPOCH_MS = 1_750_000_000_000L;
	private static final String COMMAND_ID = "command-1";
	private static final String DESIRED_OAK_STAIRS_STATE =
			"minecraft:oak_stairs[facing=north,half=bottom,shape=straight,waterlogged=false]";
	private static int passedAssertions;

	private VerificationMain() {
	}

	public static void main(String[] args) throws Exception {
		configureExplicitNodeForVerification();
		ProtocolCodec codec = new ProtocolCodec();

		passedAssertions += AgentVerbosePresentationVerification.verify();
		verifyProtocolConstants();
		verifyActionWireNames();
		verifyValidActionUnion(codec);
		verifyBriefExamples(codec);
		verifyEnvelopeValidation(codec);
		verifyDirectCommandSchemaValidation();
		verifyBoundedValidation(codec);
		verifyStrictArguments(codec);
		verifyDesiredStateProtocol(codec);
		verifyBuildSequenceProtocol(codec);
		verifyCommandImmutability();
		verifyCommandEncodingRoundTrip(codec);
		verifyActionResultContract();
		passedAssertions += LocalPathfinderVerification.verify();
		passedAssertions += GoalControlVerification.verify();
		passedAssertions += GoalDraftAdvancementRevalidationVerification.verify();
		passedAssertions += QueuedGoalRevalidationVerification.verify();
		passedAssertions += AgentSavedDataGoalDraftVerification.verify();
		passedAssertions += GoalCompilerVerification.verify();
		passedAssertions += GoalInventoryCapacityVerification.verify();
		passedAssertions += AgentConversationRouterVerification.verify();
		passedAssertions += NativeAgentWhisperTargetsVerification.verify();
		passedAssertions += AgentGroupRegistryVerification.verify();
		passedAssertions += AgentModelArgumentVerification.verify();
		passedAssertions += BundledCoordinatorInstallerVerification.verify();
		passedAssertions += CoordinatorRecoveryFaultMatrixVerification.verify();
		passedAssertions += CoordinatorLogRotationVerification.verify();
		passedAssertions += NodeRuntimeLocatorVerification.verify();
		passedAssertions += CoordinatorLaunchPolicyVerification.verify();
		passedAssertions += CoordinatorProcessOwnershipVerification.verify();
		passedAssertions += CoordinatorStartupSmokeVerification.verify();
		passedAssertions += CoordinatorVoiceTimeoutLifecycleVerification.verify();
		passedAssertions += CoordinatorVoiceEndpointVerification.verify();
		passedAssertions += CoordinatorVoiceEndpointRefreshVerification.verify();
		passedAssertions += CodexAgentServerRuntimeVoiceStartVerification.verify();
		passedAssertions += AgentActivityPresentationVerification.verify();
		passedAssertions += AgentDeathCaptureVerification.verify();
		passedAssertions += AgentControlSyncVerification.verify();
		passedAssertions += VoiceConsentCommandVerification.verify();
		passedAssertions += SkitModeVerification.verify();
		passedAssertions += CameraPathVerification.verify();
		passedAssertions += SkitDirectorLayoutVerification.verify();
		passedAssertions += AgentSpawnPlacementVerification.verify();
		passedAssertions += ChunkedSavedPayloadVerification.verify();
		passedAssertions += OfflineAgentProfileLookupVerification.verify();
		passedAssertions += OfflineAgentPlayersVerification.verify();
		passedAssertions += PendingSpawnCancellationLedgerVerification.verify();
		passedAssertions += AgentRegistryVerification.verify();
		passedAssertions += GoalSpecVerification.verify();
		passedAssertions += AgentIdentityVerification.verify();
		passedAssertions += AgentControlVerification.verify();
		passedAssertions += AgentClientPresentationVerification.verify();
		passedAssertions += AgentControlSelectionStateVerification.verify();
		passedAssertions += AgentRosterViewStateVerification.verify();
		passedAssertions += AgentControlLayoutVerification.verify();
		passedAssertions += AgentControlSubmissionVerification.verify();
		passedAssertions += BlockObservationOrderingVerification.verify();
		passedAssertions += BlockObservationLazyVisibilityVerification.verify();
		passedAssertions += InventoryObservationSlotsVerification.verify();
		passedAssertions += ServerObservationTagCacheVerification.verify();
		passedAssertions += ServerObservationInventorySnapshotVerification.verify();
		passedAssertions += ObservationBudgetVerification.verify();
		passedAssertions += dev.agaminggod.arenaagents.world.WorldMutationRevisionsVerification.verify();
		passedAssertions += ServerObservationRayTargetVerification.verify();
		passedAssertions += ServerObservationWireBudgetVerification.verify();
		passedAssertions += ActionProgressTrackerVerification.verify();
		passedAssertions += AdvancedInteractionRollbackVerification.verify();
		passedAssertions += BlockPlacementPostconditionVerification.verify();
		passedAssertions += BlockPlacementAttemptPolicyVerification.verify();
		passedAssertions += DesiredBlockStateVerification.verify();
		passedAssertions += ResourceLeaseManagerVerification.verify();
		passedAssertions += RecipeActionVerification.verify();
		passedAssertions += TransactionProtocolVerification.verify();
		passedAssertions += TransactionPostconditionVerification.verify();
		passedAssertions += EquipmentAndUseVerification.verify();
		passedAssertions += ServerActionExecutorVerification.verify();
		passedAssertions += GoalVerificationRuntimeVerification.verify();
		passedAssertions += dev.agaminggod.arenaagents.server.perception.ObservationPageVerification.verify();
		passedAssertions += dev.agaminggod.arenaagents.server.perception.PlayerObservationEventsVerification.verify();
		passedAssertions += dev.agaminggod.arenaagents.server.runtime.input.RidingJumpInputVerification.verify();
		passedAssertions += ActionSuccessLedgerVerification.verify();
		passedAssertions += InputStateVerification.verify();
		passedAssertions += AttentionHazardVerification.verify();
		passedAssertions += MenuCapabilityRegistryVerification.verify();
		passedAssertions += VoiceSubsystemVerification.verify();
		passedAssertions += VoiceDirectorVerification.verify();
		passedAssertions += ServerPathPlannerVerification.verify();
		passedAssertions += MinecraftNavigationWorldVerification.verify();
		passedAssertions += NavigationProgressVerification.verify();
		passedAssertions += SurvivalReflexVerification.verify();
		passedAssertions += ItemPickupProgressVerification.verify();
		passedAssertions += ScenarioCoreVerification.verify();
		passedAssertions += ScenarioActivationFailurePolicyVerification.verify();
		passedAssertions += ScenarioBuildProgressVerification.verify();
		passedAssertions += ScenarioLaunchRuntimeVerification.verify();
		passedAssertions += ScenarioMatchResultVerification.verify();
		passedAssertions += ScenarioPreflightVerification.verify();
		passedAssertions += ScenarioRecoveryVerification.verify();
		passedAssertions += ScenarioArenaModuleVerification.verify();
		passedAssertions += ArenaSpectatorSnapshotVerification.verify();
		passedAssertions += ArenaSpectatorStateVerification.verify();
		passedAssertions += ScenarioSetupStateVerification.verify();
		passedAssertions += AgentRosterGridLayoutVerification.verify();
		passedAssertions += AgentRosterGridVerification.verify();
		passedAssertions += ConsoleThemeVerification.verify();
		passedAssertions += LiveArenaLayoutVerification.verify();
		passedAssertions += LocalizationVerification.verify();
		passedAssertions += ScenarioResultsLayoutVerification.verify();
		passedAssertions += ScenarioSetupLayoutVerification.verify();
		passedAssertions += BridgeEnvelopeCodecVerification.verify();
		passedAssertions += BoundedServerTaskQueueVerification.verify();
		passedAssertions += ProgramActionLedgerVerification.verify();
		passedAssertions += TerminalResultLedgerVerification.verify();
		passedAssertions += CoordinatorStatusVerification.verify();
		passedAssertions += AgentRecoverySpawnPolicyVerification.verify();
		passedAssertions += AgentRespawnSpawnPolicyVerification.verify();
		passedAssertions += MultiplexedServerBridgeVerification.verify();
		passedAssertions += AgentVerboseVerification.verify();
		passedAssertions += SingleBrainBoundaryVerification.verify();
		verifyJsonLineFraming(codec);

		System.out.printf("PASS: %d protocol and core assertions%n", passedAssertions);
	}

	private static void verifyProtocolConstants() {
		assertEquals(1, ProtocolConstants.PROTOCOL_VERSION, "protocol version");
		assertEquals(65_536, ProtocolConstants.MAX_LINE_BYTES, "line byte limit");
		assertEquals(600_000L, ProtocolConstants.MAX_DURATION_MS, "duration limit");
	}

	private static void verifyActionWireNames() {
		List<String> expectedWireNames = List.of(
				"move_to",
				"control",
				"control_sequence",
				"look_at",
				"attack",
				"select_item",
				"use_item",
				"break_block",
				"place_block",
				"build_sequence",
				"chat",
				"wait",
				"set_door",
				"pick_up_item",
				"drop_item",
				"navigate_to",
				"fight_target",
				"flee_from",
				"follow_entity",
				"transfer_container",
				"craft_inventory",
				"craft_table",
				"furnace_transaction",
				"equip_item",
				"select_tool",
				"block_with_shield",
				"use_ranged",
				"interact_block",
				"interact_entity",
				"dismount",
				"start_fall_flying",
				"wake_up",
				"set_flight",
				"write_sign",
				"edit_book",
				"menu_click",
				"menu_close",
				"beacon_effects",
				"menu_transfer",
				"menu_button",
				"anvil_rename",
				"respawn",
				"complete_goal"
		);
		List<String> actualWireNames = List.of(ActionType.values()).stream()
				.map(ActionType::wireName)
				.toList();

		assertEquals(expectedWireNames, actualWireNames, "exhaustive action wire names");
		assertEquals(ActionType.WAIT, ActionType.fromWireName("wait").orElseThrow(), "wire action lookup");
		assertTrue(ActionType.fromWireName("WAIT").isEmpty(), "wire action lookup is case-sensitive");
		assertTrue(ActionType.fromWireName(null).isEmpty(), "null wire action lookup is empty");
	}

	private static void verifyValidActionUnion(ProtocolCodec codec) throws ProtocolException {
		assertDecodedType(codec, "move_to", "\"x\":1.5,\"y\":64,\"z\":-2.5,\"tolerance\":0.75,\"sprint\":true", ActionType.MOVE_TO);
		assertDecodedType(codec, "control", "\"forward\":1,\"strafe\":-0.5,\"jump\":true,\"sneak\":false,"
				+ "\"sprint\":true,\"attack\":false,\"use\":true,\"yaw\":90,\"pitch\":-15,\"selectedSlot\":2,\"hand\":\"off\",\"ticks\":20",
				ActionType.CONTROL);
		assertDecodedType(codec, "look_at", "\"x\":1,\"y\":65.25,\"z\":3", ActionType.LOOK_AT);
		assertDecodedType(codec, "attack", "\"targetId\":\"00000000-0000-0000-0000-000000000001\",\"timeoutMs\":5000", ActionType.ATTACK);
		assertDecodedType(codec, "select_item", "\"itemId\":\"minecraft:diamond_sword\"", ActionType.SELECT_ITEM);
		assertDecodedType(codec, "use_item", "\"durationMs\":1250", ActionType.USE_ITEM);
		assertDecodedType(codec, "break_block", "\"x\":1,\"y\":64,\"z\":-2,\"timeoutMs\":5000", ActionType.BREAK_BLOCK);
		assertDecodedType(codec, "place_block", "\"x\":1,\"y\":64,\"z\":-2,\"face\":\"up\",\"itemId\":\"minecraft:stone\",\"desiredState\":null", ActionType.PLACE_BLOCK);
		assertDecodedType(codec, "build_sequence", "\"placements\":[{\"x\":1,\"y\":64,\"z\":-2,\"face\":\"up\",\"itemId\":\"minecraft:stone\",\"desiredState\":null}],\"timeoutMs\":60000", ActionType.BUILD_SEQUENCE);
		assertDecodedType(codec, "chat", "\"message\":\"Ready.\"", ActionType.CHAT);
		ActionCommand directChat = codec.decodeCommand(commandJson(
				"chat",
				"\"message\":\"Meet behind the tower.\",\"audience\":\"direct\","
						+ "\"recipientId\":\"00000000-0000-0000-0000-000000000001\""
		));
		assertEquals("direct", directChat.arguments().get("audience").getAsString(), "direct chat audience");
		assertEquals(
				"00000000-0000-0000-0000-000000000001",
				directChat.arguments().get("recipientId").getAsString(),
				"direct chat recipient"
		);
		assertDecodedType(codec, "wait", "\"durationMs\":250", ActionType.WAIT);
		assertDecodedType(codec, "set_door", "\"x\":1,\"y\":64,\"z\":-2,\"open\":true", ActionType.SET_DOOR);
		assertDecodedType(codec, "pick_up_item", "\"targetSelector\":\"minecraft:item\"", ActionType.PICK_UP_ITEM);
		assertDecodedType(codec, "drop_item", "\"slot\":0,\"count\":1", ActionType.DROP_ITEM);
		assertDecodedType(codec, "navigate_to", "\"x\":10,\"y\":64,\"z\":-5,\"tolerance\":1.25,\"sprint\":true,\"timeoutMs\":30000", ActionType.NAVIGATE_TO);
		assertDecodedType(codec, "fight_target", "\"targetSelector\":\"nearest_hostile\",\"desiredRange\":2.5,\"timeoutMs\":15000", ActionType.FIGHT_TARGET);
		assertDecodedType(codec, "flee_from", "\"targetSelector\":\"last_attacker\",\"distance\":16,\"timeoutMs\":10000", ActionType.FLEE_FROM);
		assertDecodedType(codec, "follow_entity", "\"targetSelector\":\"player:Lucas\",\"distance\":3,\"timeoutMs\":30000", ActionType.FOLLOW_ENTITY);
		assertDecodedType(codec, "interact_block", "\"x\":1,\"y\":64,\"z\":-2,\"face\":\"north\",\"hand\":\"main\",\"expectedItemId\":\"minecraft:air\"", ActionType.INTERACT_BLOCK);
		assertDecodedType(codec, "interact_entity", "\"targetId\":\"00000000-0000-0000-0000-000000000001\",\"hand\":\"off\",\"expectedItemId\":\"minecraft:lead\"", ActionType.INTERACT_ENTITY);
		assertDecodedType(codec, "dismount", "", ActionType.DISMOUNT);
		assertDecodedType(codec, "start_fall_flying", "", ActionType.START_FALL_FLYING);
		assertDecodedType(codec, "menu_transfer", "\"menuId\":\"minecraft:smithing\",\"sourceSlot\":3,\"destinationSlot\":4,\"count\":1,\"expectedItemId\":\"minecraft:netherite_sword\",\"timeoutMs\":5000", ActionType.MENU_TRANSFER);
		assertDecodedType(codec, "menu_button", "\"menuId\":\"minecraft:enchantment\",\"buttonId\":1,\"timeoutMs\":5000", ActionType.MENU_BUTTON);
		assertDecodedType(codec, "anvil_rename", "\"menuId\":\"minecraft:anvil\",\"name\":\"Explorer\",\"timeoutMs\":5000", ActionType.ANVIL_RENAME);
		assertDecodedType(codec, "respawn", "", ActionType.RESPAWN);
		assertDecodedType(codec, "complete_goal", "\"summary\":\"Reached the arena.\"", ActionType.COMPLETE_GOAL);
	}

	private static void verifyBriefExamples(ProtocolCodec codec) throws ProtocolException {
		expectProtocolException(
				() -> codec.decodeCommand("{\"type\":\"unknown\"}"),
				"UNKNOWN_ACTION",
				"unknown",
				"unknown action"
		);
		expectProtocolException(
				() -> codec.decodeCommand("{\"type\":\"wait\",\"durationMs\":600001}"),
				"OUT_OF_RANGE",
				"durationMs",
				"overlong wait"
		);
		expectProtocolException(
				() -> codec.decodeCommand(commandJson(
						"control",
						"\"forward\":0,\"strafe\":0,\"jump\":false,\"sneak\":false,\"sprint\":false,"
								+ "\"attack\":false,\"use\":false,\"yaw\":0,\"pitch\":0,\"selectedSlot\":0,\"hand\":\"left\",\"ticks\":1"
				)),
				"INVALID_FIELD",
				"hand",
				"control hand selector"
		);

		ActionCommand command = codec.decodeCommand(commandJson("wait", "\"durationMs\":250"));
		assertEquals(ActionType.WAIT, command.type(), "wait action type");
	}

	private static void verifyEnvelopeValidation(ProtocolCodec codec) {
		expectProtocolException(
				() -> codec.decodeCommand("{not-json"),
				"MALFORMED_JSON",
				"JSON",
				"malformed JSON"
		);
		expectProtocolException(
				() -> codec.decodeCommand("[]"),
				"MALFORMED_JSON",
				"object",
				"non-object JSON"
		);
		expectProtocolException(
				() -> codec.decodeCommand("{\"type\":\"wait\",\"durationMs\":1,\"commandId\":\"command-1\",\"issuedAtEpochMs\":1}"),
				"MISSING_FIELD",
				"protocolVersion",
				"required protocol version"
		);
		expectProtocolException(
				() -> codec.decodeCommand("{\"protocolVersion\":2,\"commandId\":\"command-1\",\"type\":\"wait\",\"issuedAtEpochMs\":1,\"durationMs\":1}"),
				"UNSUPPORTED_VERSION",
				"2",
				"unsupported protocol version"
		);
		expectProtocolException(
				() -> codec.decodeCommand("{\"protocolVersion\":1,\"type\":\"wait\",\"issuedAtEpochMs\":1,\"durationMs\":1}"),
				"MISSING_FIELD",
				"commandId",
				"required command id"
		);
		expectProtocolException(
				() -> codec.decodeCommand("{\"protocolVersion\":1,\"commandId\":\"command-1\",\"issuedAtEpochMs\":1}"),
				"MISSING_FIELD",
				"type",
				"required action type"
		);
		expectProtocolException(
				() -> codec.decodeCommand("{\"protocolVersion\":1,\"commandId\":\"command-1\",\"type\":\"wait\",\"durationMs\":1}"),
				"MISSING_FIELD",
				"issuedAtEpochMs",
				"required issue timestamp"
		);
		expectProtocolException(
				() -> codec.decodeCommand(commandJson("wait", "\"durationMs\":1,\"extra\":true")),
				"UNKNOWN_FIELD",
				"extra",
				"unknown action field"
		);
	}

	private static void verifyBoundedValidation(ProtocolCodec codec) {
		String oversizedUtf8Line = "{\"type\":\"chat\",\"message\":\"" + "\u00e9".repeat(ProtocolConstants.MAX_LINE_BYTES / 2) + "\"}";
		expectProtocolException(
				() -> codec.decodeCommand(oversizedUtf8Line),
				"LINE_TOO_LARGE",
				Integer.toString(ProtocolConstants.MAX_LINE_BYTES),
				"UTF-8 line byte limit"
		);
		expectProtocolException(
				() -> codec.decodeCommand(commandJson("look_at", "\"x\":1e309,\"y\":64,\"z\":0")),
				"OUT_OF_RANGE",
				"x",
				"finite coordinate"
		);
		expectProtocolException(
				() -> codec.decodeCommand(commandJson("break_block", "\"x\":1.5,\"y\":64,\"z\":0,\"timeoutMs\":1")),
				"OUT_OF_RANGE",
				"x",
				"integral block coordinate"
		);
		expectProtocolException(
				() -> codec.decodeCommand(commandJson(
						"break_block",
						"\"x\":1.00000000000000001,\"y\":64,\"z\":0,\"timeoutMs\":1"
				)),
				"OUT_OF_RANGE",
				"x",
				"exact break block coordinate"
		);
		expectProtocolException(
				() -> codec.decodeCommand(commandJson(
						"place_block",
						"\"x\":0,\"y\":64,\"z\":-2.00000000000000001,\"face\":\"up\",\"itemId\":\"minecraft:stone\""
				)),
				"OUT_OF_RANGE",
				"z",
				"exact place block coordinate"
		);
		expectProtocolException(
				() -> codec.decodeCommand(commandJson("wait", "\"durationMs\":0")),
				"OUT_OF_RANGE",
				"durationMs",
				"minimum duration"
		);
		expectProtocolException(
				() -> codec.decodeCommand(commandJson("chat", "\"message\":\"" + "a".repeat(ProtocolConstants.MAX_CHAT_LENGTH + 1) + "\"")),
				"OUT_OF_RANGE",
				"message",
				"chat text limit"
		);
		expectProtocolException(
				() -> codec.decodeCommand(commandJson("chat", "\"message\":\"Missing target.\",\"audience\":\"direct\"")),
				"MISSING_FIELD",
				"recipientId",
				"direct chat recipient"
		);
		expectProtocolException(
				() -> codec.decodeCommand(commandJson(
						"chat",
						"\"message\":\"Wrong target.\",\"audience\":\"public\","
								+ "\"recipientId\":\"00000000-0000-0000-0000-000000000001\""
				)),
				"INVALID_FIELD",
				"recipientId",
				"public chat recipient"
		);
	}

	private static void verifyStrictArguments(ProtocolCodec codec) throws ProtocolException {
		expectProtocolException(
				() -> codec.decodeCommand(commandJson("wait", "")),
				"MISSING_FIELD",
				"durationMs",
				"required action argument"
		);
		expectProtocolException(
				() -> codec.decodeCommand(commandJson("move_to", "\"x\":0,\"y\":64,\"z\":0,\"tolerance\":1,\"sprint\":\"yes\"")),
				"INVALID_FIELD",
				"sprint",
				"boolean action argument"
		);
		expectProtocolException(
				() -> codec.decodeCommand(commandJson("place_block", "\"x\":0,\"y\":64,\"z\":0,\"face\":\"forward\",\"itemId\":\"minecraft:stone\"")),
				"INVALID_FIELD",
				"face",
				"block face"
		);

		ActionCommand shortestWait = codec.decodeCommand(commandJson("wait", "\"durationMs\":1"));
		ActionCommand longestWait = codec.decodeCommand(commandJson("wait", "\"durationMs\":" + ProtocolConstants.MAX_DURATION_MS));
		assertEquals(1L, shortestWait.arguments().get("durationMs").getAsLong(), "minimum valid duration");
		assertEquals(ProtocolConstants.MAX_DURATION_MS, longestWait.arguments().get("durationMs").getAsLong(), "maximum valid duration");
	}

	private static void verifyDesiredStateProtocol(ProtocolCodec codec) throws ProtocolException {
		ActionCommand command = codec.decodeCommand(commandJson(
				"place_block",
				"\"x\":1,\"y\":64,\"z\":-2,\"face\":\"up\",\"itemId\":\"minecraft:oak_stairs\",\"desiredState\":\""
						+ DESIRED_OAK_STAIRS_STATE + "\""
		));
		assertEquals(
			DESIRED_OAK_STAIRS_STATE,
			command.arguments().get("desiredState").getAsString(),
			"desired block state survives Java protocol validation"
		);

		ActionCommand nullable = codec.decodeCommand(commandJson(
				"place_block",
				"\"x\":1,\"y\":64,\"z\":-2,\"face\":\"up\",\"itemId\":\"minecraft:oak_stairs\",\"desiredState\":null"
		));
		assertTrue(nullable.arguments().get("desiredState").isJsonNull(), "null desired block state is accepted");

		ActionCommand mismatchedBlockId = codec.decodeCommand(commandJson(
				"place_block",
				"\"x\":1,\"y\":64,\"z\":-2,\"face\":\"up\",\"itemId\":\"minecraft:oak_stairs\",\"desiredState\":\"minecraft:stone[facing=north]\""
		));
		assertEquals(
			"minecraft:stone[facing=north]",
			mismatchedBlockId.arguments().get("desiredState").getAsString(),
			"block-id mismatch is deferred to execution validation"
		);

		expectProtocolException(
				() -> codec.decodeCommand(commandJson(
						"place_block",
						"\"x\":1,\"y\":64,\"z\":-2,\"face\":\"up\",\"itemId\":\"minecraft:oak_stairs\",\"desiredState\":\""
								+ "x".repeat(513) + "\""
				)),
				"OUT_OF_RANGE",
				"desiredState",
				"desired block state length"
		);
	}

	private static void verifyBuildSequenceProtocol(ProtocolCodec codec) throws ProtocolException {
		String placement = "{\"x\":1,\"y\":64,\"z\":-2,\"face\":\"up\","
				+ "\"itemId\":\"minecraft:stone\",\"desiredState\":null}";
		String placements32 = java.util.stream.IntStream.range(0, 32)
				.mapToObj(ignored -> placement)
				.collect(java.util.stream.Collectors.joining(","));
		ActionCommand command = codec.decodeCommand(commandJson(
				"build_sequence", "\"placements\":[" + placements32 + "],\"timeoutMs\":60000"));
		assertEquals(32, command.arguments().getAsJsonArray("placements").size(),
				"build sequence preserves 32 ordered placements");
		expectProtocolException(
				() -> codec.decodeCommand(commandJson(
						"build_sequence", "\"placements\":[" + placements32 + "," + placement + "],\"timeoutMs\":60000")),
				"OUT_OF_RANGE",
				"placements",
				"build sequence placement limit"
		);
	}

	private static void verifyDirectCommandSchemaValidation() {
		JsonObject validArguments = new JsonObject();
		validArguments.addProperty("durationMs", 250);
		ActionCommand validCommand = new ActionCommand(
				COMMAND_ID,
				ActionType.WAIT,
				validArguments,
				ISSUED_AT_EPOCH_MS
		);
		assertEquals(250L, validCommand.arguments().get("durationMs").getAsLong(), "direct valid command arguments");

		expectProtocolException(
				() -> new ActionCommand(
						COMMAND_ID,
						ActionType.WAIT,
						new JsonObject(),
						ISSUED_AT_EPOCH_MS
				),
				"MISSING_FIELD",
				"durationMs",
				"direct command required argument"
		);

		JsonObject unknownArguments = validArguments.deepCopy();
		unknownArguments.addProperty("extra", true);
		expectProtocolException(
				() -> new ActionCommand(
						COMMAND_ID,
						ActionType.WAIT,
						unknownArguments,
						ISSUED_AT_EPOCH_MS
				),
				"UNKNOWN_FIELD",
				"extra",
				"direct command unknown argument"
		);

		JsonObject outOfRangeArguments = new JsonObject();
		outOfRangeArguments.addProperty("durationMs", ProtocolConstants.MAX_DURATION_MS + 1L);
		expectProtocolException(
				() -> new ActionCommand(
						COMMAND_ID,
						ActionType.WAIT,
						outOfRangeArguments,
						ISSUED_AT_EPOCH_MS
				),
				"OUT_OF_RANGE",
				"durationMs",
				"direct command bounded argument"
		);
	}

	private static void verifyCommandImmutability() {
		JsonObject sourceArguments = new JsonObject();
		sourceArguments.addProperty("durationMs", 250);
		ActionCommand command = new ActionCommand(COMMAND_ID, ActionType.WAIT, sourceArguments, ISSUED_AT_EPOCH_MS);

		sourceArguments.addProperty("durationMs", 500);
		JsonObject returnedArguments = command.arguments();
		returnedArguments.addProperty("durationMs", 750);

		assertEquals(250L, command.arguments().get("durationMs").getAsLong(), "action arguments are defensively copied");
		expectProtocolException(
				() -> new ActionCommand(
						"a".repeat(ProtocolConstants.MAX_COMMAND_ID_LENGTH + 1),
						ActionType.WAIT,
						sourceArguments,
						ISSUED_AT_EPOCH_MS
				),
				"OUT_OF_RANGE",
				"commandId",
				"record command id limit"
		);
	}

	private static void verifyCommandEncodingRoundTrip(ProtocolCodec codec) throws ProtocolException {
		JsonObject arguments = new JsonObject();
		arguments.addProperty("durationMs", 250);
		ActionCommand original = new ActionCommand(COMMAND_ID, ActionType.WAIT, arguments, ISSUED_AT_EPOCH_MS);

		String encoded = codec.encode(original);
		ActionCommand decoded = codec.decodeCommand(encoded);

		assertTrue(encoded.contains("\"protocolVersion\":1"), "encoded command protocol version");
		assertTrue(encoded.contains("\"type\":\"wait\""), "encoded action wire name");
		assertTrue(!encoded.contains("\"arguments\""), "encoded action arguments are flattened");
		assertEquals(original, decoded, "command encoding round trip");
	}

	private static void verifyActionResultContract() throws ProtocolException {
		ActionResult result = new ActionResult(
				COMMAND_ID,
				ActionState.SUCCEEDED,
				"COMPLETED",
				"Goal completed.",
				ISSUED_AT_EPOCH_MS + 250
		);

		assertEquals(ActionState.SUCCEEDED, result.state(), "action result state");
		assertTrue(result.state().isTerminal(), "successful action is terminal");
		assertTrue(!ActionState.RUNNING.isTerminal(), "running action is non-terminal");
		expectProtocolException(
				() -> new ActionResult(COMMAND_ID, ActionState.RUNNING, "RUNNING", "Still running.", ISSUED_AT_EPOCH_MS),
				"INVALID_FIELD",
				"terminal",
				"result rejects non-terminal state"
		);

		String encoded = new ProtocolCodec().encode(result);
		assertTrue(encoded.contains("\"protocolVersion\":1"), "encoded result protocol version");
		assertTrue(encoded.contains("\"state\":\"SUCCEEDED\""), "encoded terminal state");
	}

	private static void verifyJsonLineFraming(ProtocolCodec codec) throws IOException {
		String json = "{\"message\":\"ready\"}";
		ByteArrayOutputStream output = new ByteArrayOutputStream();
		codec.writeLine(output, json);
		assertEquals(json, codec.readLine(new ByteArrayInputStream(output.toByteArray())), "UTF-8 JSONL round trip");
		byte[] oversizedLine = ("a".repeat(ProtocolConstants.MAX_LINE_BYTES + 1) + "\n")
				.getBytes(StandardCharsets.UTF_8);
		expectProtocolException(
				() -> codec.readLine(new ByteArrayInputStream(oversizedLine)),
				"LINE_TOO_LARGE",
				Integer.toString(ProtocolConstants.MAX_LINE_BYTES),
				"framing line byte limit"
		);
	}


	private static void configureExplicitNodeForVerification() throws IOException {
		if (System.getProperty("arenaagents.nodePath") != null) return;
		String executableName = System.getProperty("os.name", "")
				.toLowerCase(java.util.Locale.ROOT).contains("win") ? "node.exe" : "node";
		String path = System.getenv("PATH");
		if (path != null) {
			for (String entry : path.split(java.util.regex.Pattern.quote(java.io.File.pathSeparator), -1)) {
				if (entry.isBlank()) continue;
				Path candidate = Path.of(entry).resolve(executableName).toAbsolutePath().normalize();
				if (Files.isRegularFile(candidate) && Files.isExecutable(candidate)) {
					System.setProperty("arenaagents.nodePath", candidate.toString());
					return;
				}
			}
		}
		throw new IOException("Node 22+ is required for the integrated verification fixture");
	}























	private static void assertDecodedType(
			ProtocolCodec codec,
			String wireType,
			String actionFields,
			ActionType expectedType
	) throws ProtocolException {
		ActionCommand command = codec.decodeCommand(commandJson(wireType, actionFields));
		assertEquals(expectedType, command.type(), wireType + " action type");
		assertEquals(COMMAND_ID, command.commandId(), wireType + " command id");
		assertEquals(ISSUED_AT_EPOCH_MS, command.issuedAtEpochMs(), wireType + " issue timestamp");
	}

	private static String commandJson(String wireType, String actionFields) {
		String actionSuffix = actionFields.isEmpty() ? "" : "," + actionFields;
		return "{\"protocolVersion\":1,\"commandId\":\"" + COMMAND_ID
				+ "\",\"type\":\"" + wireType
				+ "\",\"issuedAtEpochMs\":" + ISSUED_AT_EPOCH_MS
				+ actionSuffix + "}";
	}
















	private static void expectProtocolException(
			ThrowingRunnable action,
			String expectedCode,
			String expectedMessagePart,
			String label
	) {
		try {
			action.run();
		} catch (ProtocolException exception) {
			assertEquals(expectedCode, exception.code(), label + " code");
			assertTrue(exception.getMessage().contains(expectedMessagePart), label + " message");
			return;
		} catch (Exception exception) {
			throw new AssertionError(label + " threw " + exception.getClass().getSimpleName(), exception);
		}

		throw new AssertionError(label + " did not throw ProtocolException");
	}

	private static void assertEquals(Object expected, Object actual, String label) {
		if (!expected.equals(actual)) {
			throw new AssertionError(label + ": expected <" + expected + "> but was <" + actual + ">");
		}
		pass(label);
	}

	private static void assertTrue(boolean condition, String label) {
		if (!condition) {
			throw new AssertionError(label);
		}
		pass(label);
	}

	private static void assertDoesNotThrow(ThrowingRunnable action, String label) {
		try {
			action.run();
		} catch (Exception exception) {
			throw new AssertionError(label + " threw " + exception.getClass().getSimpleName(), exception);
		}
		pass(label);
	}

	private static void pass(String label) {
		passedAssertions++;
		System.out.println("PASS: " + label);
	}



	@FunctionalInterface
	private interface ThrowingRunnable {
		void run() throws Exception;
	}
}
