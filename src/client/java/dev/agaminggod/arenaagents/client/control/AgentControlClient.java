package dev.agaminggod.arenaagents.client.control;

import com.mojang.blaze3d.platform.InputConstants;
import dev.agaminggod.arenaagents.client.gui.AgentControlScreen;
import dev.agaminggod.arenaagents.client.gui.scenario.ScenarioLaunchPlan;
import dev.agaminggod.arenaagents.client.gui.scenario.ScenarioLaunchRegistry;
import dev.agaminggod.arenaagents.client.gui.scenario.ScenarioSetupScreen;
import dev.agaminggod.arenaagents.client.presentation.ArenaSpectatorHud;
import dev.agaminggod.arenaagents.client.presentation.ArenaSpectatorState;
import dev.agaminggod.arenaagents.client.presentation.ScenarioResultsScreen;
import dev.agaminggod.arenaagents.client.presentation.SpectatorCameraAssistant;
import dev.agaminggod.arenaagents.client.presentation.ScenarioBuildProgressState;
import dev.agaminggod.arenaagents.scenario.ScenarioAgentSpec;
import dev.agaminggod.arenaagents.scenario.ScenarioCancelPayload;
import dev.agaminggod.arenaagents.scenario.ScenarioLaunchPayload;
import dev.agaminggod.arenaagents.scenario.ScenarioLaunchRequest;
import dev.agaminggod.arenaagents.scenario.presentation.ArenaSpectatorClearPayload;
import dev.agaminggod.arenaagents.scenario.presentation.ArenaSpectatorSnapshotPayload;
import dev.agaminggod.arenaagents.scenario.presentation.ScenarioBuildProgressClearPayload;
import dev.agaminggod.arenaagents.scenario.presentation.ScenarioBuildProgressPayload;
import dev.agaminggod.arenaagents.control.AgentControlCatalog;
import dev.agaminggod.arenaagents.control.AgentControlModelOption;
import dev.agaminggod.arenaagents.control.AgentControlRequestPayload;
import dev.agaminggod.arenaagents.control.AgentControlSnapshot;
import dev.agaminggod.arenaagents.control.AgentControlSnapshotPayload;
import dev.agaminggod.arenaagents.control.AgentControlSnapshotStore;
import java.util.ArrayDeque;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.Set;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.resources.Identifier;
import org.lwjgl.glfw.GLFW;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class AgentControlClient {
	public static final int SNAPSHOT_REFRESH_TICKS = 20;
	public static final int HIDDEN_SNAPSHOT_REFRESH_TICKS = 100;
	private static final Logger LOGGER = LoggerFactory.getLogger(AgentControlClient.class);
	private static final KeyMapping.Category KEY_CATEGORY = KeyMapping.Category.register(
			Identifier.fromNamespaceAndPath("arenaagents", "controls")
	);
	private static final KeyMapping OPEN_CONTROL = KeyMappingHelper.registerKeyMapping(new KeyMapping(
			"key.arenaagents.open_controls",
			InputConstants.Type.KEYSYM,
			GLFW.GLFW_KEY_G,
			KEY_CATEGORY
	));
	private static final KeyMapping TOGGLE_CAMERA_ASSISTANT = KeyMappingHelper.registerKeyMapping(new KeyMapping(
			"key.arenaagents.toggle_camera_assistant",
			InputConstants.Type.KEYSYM,
			GLFW.GLFW_KEY_H,
			KEY_CATEGORY
	));
	private static final AgentControlSnapshotStore SNAPSHOTS = new AgentControlSnapshotStore();
	private static final ArenaSpectatorState SPECTATOR_STATE = new ArenaSpectatorState();
	private static final ScenarioBuildProgressState BUILD_PROGRESS_STATE = new ScenarioBuildProgressState();
	private static final SpectatorCameraAssistant CAMERA_ASSISTANT = new SpectatorCameraAssistant();
	private static Preferences preferences = Preferences.defaults();
	private static boolean catalogAuthoritative;
	private static boolean registered;
	private static final Set<String> HIDDEN_AGENT_IDS = new LinkedHashSet<>();
	private static final Set<String> AUTOMATIC_AGENT_IDS = new LinkedHashSet<>();
	private static final Set<String> KNOWN_AGENT_IDS = new LinkedHashSet<>();
	private static int refreshCountdown;
	private static int snapshotContentHash;
	private static String snapshotError = "";
	private static final SnapshotAcknowledgements SNAPSHOT_ACKNOWLEDGEMENTS = new SnapshotAcknowledgements();

	private AgentControlClient() {
	}

	public static synchronized void register() {
		if (registered) {
			return;
		}
		boolean receiverRegistered = ClientPlayNetworking.registerGlobalReceiver(
				AgentControlSnapshotPayload.TYPE,
				(payload, context) -> context.client().execute(() -> acceptSnapshot(payload.snapshot()))
		);
		if (!receiverRegistered) {
			throw new IllegalStateException("Arena Agents control snapshot receiver is already registered");
		}
		boolean spectatorReceiverRegistered = ClientPlayNetworking.registerGlobalReceiver(
				ArenaSpectatorSnapshotPayload.TYPE,
				(payload, context) -> context.client().execute(() -> acceptSpectatorPayload(context.client(), payload))
		);
		if (!spectatorReceiverRegistered) {
			throw new IllegalStateException("Arena Agents spectator snapshot receiver is already registered");
		}
		boolean spectatorClearReceiverRegistered = ClientPlayNetworking.registerGlobalReceiver(
				ArenaSpectatorClearPayload.TYPE,
				(payload, context) -> context.client().execute(SPECTATOR_STATE::clear)
		);
		if (!spectatorClearReceiverRegistered) {
			throw new IllegalStateException("Arena Agents spectator clear receiver is already registered");
		}
		boolean buildProgressReceiverRegistered = ClientPlayNetworking.registerGlobalReceiver(
				ScenarioBuildProgressPayload.TYPE,
				(payload, context) -> context.client().execute(() -> {
					if (!BUILD_PROGRESS_STATE.accept(payload)) return;
					if (context.client().screen instanceof dev.agaminggod.arenaagents.client.gui.scenario.ScenarioSetupScreen screen) {
						screen.acceptBuildProgress();
					}
				})
		);
		if (!buildProgressReceiverRegistered) {
			throw new IllegalStateException("Arena Agents build progress receiver is already registered");
		}
		boolean buildProgressClearReceiverRegistered = ClientPlayNetworking.registerGlobalReceiver(
				ScenarioBuildProgressClearPayload.TYPE,
				(payload, context) -> context.client().execute(() -> {
					BUILD_PROGRESS_STATE.clear();
					if (context.client().screen instanceof ScenarioSetupScreen screen) {
						screen.acceptBuildProgressClear();
					}
				})
		);
		if (!buildProgressClearReceiverRegistered) {
			throw new IllegalStateException("Arena Agents build progress clear receiver is already registered");
		}
		ArenaSpectatorHud.register(SPECTATOR_STATE, BUILD_PROGRESS_STATE);
		ClientTickEvents.END_CLIENT_TICK.register(AgentControlClient::tick);
		ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> clearConnectionState());
		ScenarioLaunchRegistry.register(AgentControlClient::launchScenario);
		ScenarioLaunchRegistry.registerCancel(AgentControlClient::cancelScenario);
		registered = true;
	}

	public static Optional<AgentControlSnapshot> snapshot() {
		return SNAPSHOTS.current();
	}

	public static Optional<dev.agaminggod.arenaagents.control.AgentControlAgent> agentForPlayer(String profileName) {
		return SNAPSHOTS.current().flatMap(snapshot -> AgentPlayerIdentity.find(snapshot, profileName));
	}

	public static boolean isAgentPlayer(String profileName) {
		return agentForPlayer(profileName).isPresent();
	}

	public static ArenaSpectatorState spectatorState() {
		return SPECTATOR_STATE;
	}

	public static ScenarioBuildProgressState buildProgressState() {
		return BUILD_PROGRESS_STATE;
	}

	public static Preferences preferences() {
		return preferences;
	}

	public static boolean catalogAuthoritative() {
		return catalogAuthoritative;
	}

	public static String openControlKeyLabel() {
		return OPEN_CONTROL.getTranslatedKeyMessage().getString();
	}

	public static Set<String> hiddenAgentIds() {
		return HIDDEN_AGENT_IDS;
	}

	public static Set<String> automaticAgentIds() {
		return AUTOMATIC_AGENT_IDS;
	}

	public static Set<String> knownAgentIds() {
		return KNOWN_AGENT_IDS;
	}

	public static Optional<String> snapshotError() {
		return Optional.of(snapshotError).filter(value -> !value.isBlank());
	}

	public static void rememberPreferences(String provider, String model, String reasoning, String serviceTier) {
		preferences = new Preferences(provider, model, reasoning, serviceTier);
	}

	public static void requestSnapshot() {
		try {
			if (ClientPlayNetworking.canSend(AgentControlRequestPayload.TYPE)) {
				snapshotError = "";
				ClientPlayNetworking.send(AgentControlRequestPayload.INSTANCE);
				SNAPSHOT_ACKNOWLEDGEMENTS.recordRequest();
			} else {
				snapshotError = "This server does not support the Field Console";
			}
		} catch (IllegalStateException exception) {
			snapshotError = "The Field Console is disconnected";
			LOGGER.debug("Arena Agents control snapshot request skipped while disconnected");
		}
	}

	public static boolean sendCommand(String command) {
		return sendCommandWithReceipt(command).isPresent();
	}

	public static OptionalLong sendCommandWithReceipt(String command) {
		Minecraft client = Minecraft.getInstance();
		if (client.getConnection() == null) {
			return OptionalLong.empty();
		}
		client.getConnection().sendCommand(Objects.requireNonNull(command, "command must not be null"));
		refreshCountdown = 2;
		return OptionalLong.of(SNAPSHOT_ACKNOWLEDGEMENTS.beginMutation());
	}

	private static ScenarioLaunchRegistry.Result launchScenario(ScenarioLaunchPlan plan) {
		try {
			if (!ClientPlayNetworking.canSend(ScenarioLaunchPayload.TYPE)) {
				return new ScenarioLaunchRegistry.Result(false, "The server does not support scenario launches");
			}
			ScenarioLaunchRequest request = new ScenarioLaunchRequest(
					plan.scenarioId(),
					plan.mapVersion(),
					plan.deterministicEvents(),
					plan.placementMode(),
					plan.roster().stream().map(agent -> new ScenarioAgentSpec(
							agent.slot(),
							agent.displayName(),
							agent.provider(),
							agent.model(),
							agent.reasoning(),
							agent.serviceTier(),
							Optional.of(agent.team()).filter(value -> !value.isBlank()),
							agent.gameMode()
					)).toList(),
					plan.confirmationToken()
			);
			BUILD_PROGRESS_STATE.clear();
			ClientPlayNetworking.send(ScenarioLaunchPayload.fromRequest(request));
			return new ScenarioLaunchRegistry.Result(
					true,
					plan.scenarioTitle() + " build request sent; live progress will appear here"
			);
		} catch (RuntimeException exception) {
			String message = exception.getMessage();
			return new ScenarioLaunchRegistry.Result(
					false,
					message == null || message.isBlank() ? exception.getClass().getSimpleName() : message
			);
		}
	}

	private static void tick(Minecraft client) {
		while (OPEN_CONTROL.consumeClick()) {
			if (client.player != null && client.level != null && client.screen == null) {
				client.setScreen(new AgentControlScreen());
				requestSnapshot();
			}
		}
		while (TOGGLE_CAMERA_ASSISTANT.consumeClick()) {
			if (!SPECTATOR_STATE.cameraDisabled()) {
				SPECTATOR_STATE.disableCamera();
				CAMERA_ASSISTANT.resetTracking();
			} else {
				long currentTick = SPECTATOR_STATE.snapshot()
						.map(snapshot -> snapshot.elapsedTick())
						.orElse(0L);
				SPECTATOR_STATE.enableCamera(
						client.screen == null && client.player != null && client.player.isSpectator(),
						currentTick
				);
				CAMERA_ASSISTANT.resetTracking();
			}
		}
		boolean connected = client.player != null && client.level != null && client.getConnection() != null;
		boolean controlsVisible = client.screen instanceof AgentControlScreen
				|| client.screen instanceof dev.agaminggod.arenaagents.client.gui.scenario.ScenarioSetupScreen;
		SnapshotRefreshPolicy.Tick refresh = SnapshotRefreshPolicy.advance(
				refreshCountdown, connected, controlsVisible,
				SNAPSHOT_REFRESH_TICKS, HIDDEN_SNAPSHOT_REFRESH_TICKS);
		refreshCountdown = refresh.nextCountdown();
		if (refresh.requestSnapshot()) requestSnapshot();
		CAMERA_ASSISTANT.tick(client, SPECTATOR_STATE);
		showResultsIfAvailable(client);
	}

	private static void acceptSnapshot(AgentControlSnapshot nextSnapshot) {
		Objects.requireNonNull(nextSnapshot, "nextSnapshot must not be null");
		long acknowledgedMutationId = SNAPSHOT_ACKNOWLEDGEMENTS.acceptSnapshot();
		AgentControlSnapshot previous = SNAPSHOTS.current().orElse(null);
		if (!SnapshotRevisionPolicy.isNewer(previous, nextSnapshot)) return;
		List<AgentControlModelOption> previousCatalog = previous == null ? List.of() : previous.catalog();
		if (!SNAPSHOTS.accept(nextSnapshot)) {
			return;
		}
		snapshotError = "";
		boolean catalogChanged = !previousCatalog.equals(nextSnapshot.catalog());
		int nextContentHash = contentHash(nextSnapshot);
		Minecraft client = Minecraft.getInstance();
		if (previous != null && snapshotContentHash == nextContentHash && sameContent(previous, nextSnapshot)) {
			if (client.screen instanceof AgentControlScreen screen) {
				screen.acceptSnapshot(nextSnapshot, acknowledgedMutationId, false);
			}
			return;
		}
		snapshotContentHash = nextContentHash;
		AgentControlCatalog.installRuntimeCatalog(nextSnapshot.catalog());
		catalogAuthoritative = true;
		normalizePreferences();
		if (client.screen instanceof AgentControlScreen screen) {
			screen.acceptSnapshot(nextSnapshot, acknowledgedMutationId, true);
		} else if (catalogChanged && client.screen instanceof ScenarioSetupScreen screen) {
			screen.acceptCatalogUpdate();
		} else if (catalogChanged && client.screen instanceof dev.agaminggod.arenaagents.client.gui.SkitDirectorScreen screen) {
			screen.acceptCatalogUpdate();
		}
	}

	private static void acceptSpectatorPayload(Minecraft client, ArenaSpectatorSnapshotPayload payload) {
		if (SPECTATOR_STATE.accept(Objects.requireNonNull(payload, "payload must not be null"))) {
			showResultsIfAvailable(client);
		}
	}

	private static void showResultsIfAvailable(Minecraft client) {
		if (client.screen != null || !SPECTATOR_STATE.resultsAvailable()) return;
		SPECTATOR_STATE.snapshot()
				.filter(snapshot -> snapshot.terminal())
				.ifPresent(snapshot -> client.setScreen(new ScenarioResultsScreen(
						snapshot,
						SPECTATOR_STATE::dismissResults
				)));
	}

	private static void clearConnectionState() {
		SNAPSHOTS.clear();
		AgentControlCatalog.resetRuntimeCatalog();
		catalogAuthoritative = false;
		SPECTATOR_STATE.clearOnDisconnect();
		BUILD_PROGRESS_STATE.clear();
		ScenarioLaunchRegistry.clearRetainedPlan();
		CAMERA_ASSISTANT.resetTracking();
		refreshCountdown = 0;
		snapshotContentHash = 0;
		snapshotError = "";
		SNAPSHOT_ACKNOWLEDGEMENTS.clear();
	}

	private static int contentHash(AgentControlSnapshot snapshot) {
		return Objects.hash(
				snapshot.canControl(), snapshot.automationAvailable(), snapshot.automationStatus(),
				snapshot.agents(), snapshot.groups(), snapshot.catalog());
	}

	private static boolean sameContent(AgentControlSnapshot left, AgentControlSnapshot right) {
		return left.canControl() == right.canControl()
				&& left.automationAvailable() == right.automationAvailable()
				&& left.automationStatus().equals(right.automationStatus())
				&& left.agents().equals(right.agents())
				&& left.groups().equals(right.groups())
				&& left.catalog().equals(right.catalog());
	}

	private static void normalizePreferences() {
		preferences = Preferences.reconcile(preferences, catalogAuthoritative);
	}

	public record Preferences(String provider, String model, String reasoning, String serviceTier) {
		public Preferences {
			provider = AgentControlCatalog.requireProvider(provider);
			model = Objects.requireNonNull(model, "model must not be null");
			reasoning = Objects.requireNonNull(reasoning, "reasoning must not be null");
			serviceTier = Objects.requireNonNull(serviceTier, "serviceTier must not be null");
		}

		public Preferences(String provider, String model, String reasoning) {
			this(provider, model, reasoning, AgentControlCatalog.defaultServiceTier(provider, model));
		}

		private static Preferences defaults() {
			String provider = AgentControlCatalog.providers().getFirst();
			String model = AgentControlCatalog.defaultModel(provider);
			return new Preferences(provider, model, AgentControlCatalog.defaultReasoning(provider, model),
					AgentControlCatalog.defaultServiceTier(provider, model));
		}

		static Preferences reconcile(Preferences current, boolean authoritative) {
			Preferences checked = Objects.requireNonNull(current, "preferences must not be null");
			if (!authoritative) return checked;
			String provider = AgentControlCatalog.providers().contains(checked.provider())
					? checked.provider() : AgentControlCatalog.providers().getFirst();
			String model = AgentControlCatalog.models(provider).contains(checked.model())
					? checked.model() : AgentControlCatalog.defaultModel(provider);
			String reasoning = AgentControlCatalog.reasoningEfforts(provider, model).contains(checked.reasoning())
					? checked.reasoning() : AgentControlCatalog.defaultReasoning(provider, model);
			List<String> serviceTiers = AgentControlCatalog.serviceTiers(provider, model);
			String serviceTier = serviceTiers.contains(checked.serviceTier())
					? checked.serviceTier() : AgentControlCatalog.defaultServiceTier(provider, model);
			return new Preferences(provider, model, reasoning, serviceTier);
		}
	}

	private static ScenarioLaunchRegistry.Result cancelScenario(String buildId) {
		try {
			if (!ClientPlayNetworking.canSend(ScenarioCancelPayload.TYPE)) {
				return new ScenarioLaunchRegistry.Result(false, "The server does not support arena cancellation");
			}
			ClientPlayNetworking.send(new ScenarioCancelPayload(buildId));
			return new ScenarioLaunchRegistry.Result(true, "Cancellation request sent");
		} catch (RuntimeException exception) {
			String message = exception.getMessage();
			return new ScenarioLaunchRegistry.Result(
					false, message == null || message.isBlank() ? exception.getClass().getSimpleName() : message);
		}
	}

	static final class SnapshotAcknowledgements {
		private final ArrayDeque<Long> requests = new ArrayDeque<>();
		private long nextMutationId;
		private long mutationForNextRequest;

		long beginMutation() {
			mutationForNextRequest = ++nextMutationId;
			return mutationForNextRequest;
		}

		void recordRequest() {
			requests.addLast(mutationForNextRequest);
			mutationForNextRequest = 0L;
		}

		long acceptSnapshot() {
			return requests.isEmpty() ? 0L : requests.removeFirst();
		}

		void clear() {
			requests.clear();
			mutationForNextRequest = 0L;
		}
	}

}
