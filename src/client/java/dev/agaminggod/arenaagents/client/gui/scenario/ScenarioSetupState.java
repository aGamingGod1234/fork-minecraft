package dev.agaminggod.arenaagents.client.gui.scenario;

import dev.agaminggod.arenaagents.agent.AgentGameMode;
import dev.agaminggod.arenaagents.agent.AgentIdentity;
import dev.agaminggod.arenaagents.agent.AgentModelNames;
import dev.agaminggod.arenaagents.agent.AgentVisualIdentity;
import dev.agaminggod.arenaagents.control.AgentControlCatalog;
import dev.agaminggod.arenaagents.control.AgentControlModelOption;
import dev.agaminggod.arenaagents.control.AgentRosterEntry;
import dev.agaminggod.arenaagents.scenario.ScenarioPlacementMode;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

public final class ScenarioSetupState {
	private ScenarioWizardStep step = ScenarioWizardStep.ARENA;
	private ScenarioPreset selectedScenario = ScenarioPreset.LAST_VALLEY;
	private final List<ScenarioAgentConfig> roster = new ArrayList<>();
	private int selectedIndex;
	private boolean deterministicEvents = true;
	private ScenarioPlacementMode placementMode = ScenarioPlacementMode.IN_FRONT_OF_PLAYER;

	private ScenarioSetupState(ScenarioAgentConfig initialConfig) {
		roster.add(Objects.requireNonNull(initialConfig, "initialConfig must not be null"));
	}

	public static ScenarioSetupState defaults() {
		return new ScenarioSetupState(ScenarioAgentConfig.defaults(AgentGameMode.SURVIVAL));
	}

	public static ScenarioSetupState defaults(String provider, String model, String reasoning) {
		return new ScenarioSetupState(ScenarioAgentConfig.configuredDefaults(
				provider, model, reasoning, AgentGameMode.SURVIVAL));
	}

	public static ScenarioSetupState fromLaunchPlan(ScenarioLaunchPlan plan) {
		Objects.requireNonNull(plan, "plan must not be null");
		ScenarioPreset preset = java.util.Arrays.stream(ScenarioPreset.values())
				.filter(candidate -> candidate.id().equals(plan.scenarioId()))
				.findFirst()
				.orElseThrow(() -> new IllegalArgumentException("unknown arena preset: " + plan.scenarioId()));
		List<ScenarioLaunchPlan.Agent> ordered = plan.roster().stream()
				.sorted(java.util.Comparator.comparingInt(ScenarioLaunchPlan.Agent::slot))
				.toList();
		if (ordered.isEmpty()) throw new IllegalArgumentException("launch plan roster must not be empty");
		ScenarioSetupState restored = new ScenarioSetupState(configFrom(ordered.getFirst()));
		restored.roster.clear();
		for (ScenarioLaunchPlan.Agent agent : ordered) restored.roster.add(configFrom(agent));
		restored.selectedScenario = preset;
		restored.deterministicEvents = plan.deterministicEvents();
		restored.placementMode = plan.placementMode();
		restored.step = ScenarioWizardStep.REVIEW;
		return restored;
	}

	private static ScenarioAgentConfig configFrom(ScenarioLaunchPlan.Agent agent) {
		return new ScenarioAgentConfig(
				agent.provider(), agent.model(), agent.reasoning(), agent.serviceTier(),
				agent.displayName(), agent.team(), agent.gameMode()
		);
	}

	public ScenarioWizardStep step() {
		return step;
	}

	public ScenarioPreset selectedScenario() {
		return selectedScenario;
	}

	public List<ScenarioAgentConfig> roster() {
		return List.copyOf(roster);
	}

	public int selectedIndex() {
		return selectedIndex;
	}

	public String slotIdAt(int index) {
		checkIndex(index);
		return "scenario-slot:" + (index + 1);
	}

	public void selectSlot(String exactId) {
		if (exactId == null) return;
		for (int index = 0; index < roster.size(); index++) {
			if (slotIdAt(index).equals(exactId)) {
				selectedIndex = index;
				return;
			}
		}
	}

	public boolean deterministicEvents() {
		return deterministicEvents;
	}

	public void setDeterministicEvents(boolean deterministicEvents) {
		this.deterministicEvents = deterministicEvents;
	}

	public ScenarioPlacementMode placementMode() {
		return placementMode;
	}

	public void setPlacementMode(ScenarioPlacementMode placementMode) {
		this.placementMode = Objects.requireNonNull(placementMode, "placementMode must not be null");
	}

	public void choosePresetWorkflow() {
		step = ScenarioWizardStep.ARENA;
	}

	public void showBuildDashboard() {
		step = ScenarioWizardStep.REVIEW;
	}

	public void selectScenario(ScenarioPreset preset) {
		selectedScenario = Objects.requireNonNull(preset, "preset must not be null");
		setAgentCount(Math.clamp(roster.size(), preset.minimumAgents(), preset.maximumAgents()));
		if (preset.gameModeLocked()) {
			for (int index = 0; index < roster.size(); index++) {
				roster.set(index, roster.get(index).withGameMode(preset.defaultGameMode()));
			}
		}
	}

	public void next() {
		step = switch (step) {
			case ARENA -> ScenarioWizardStep.ROSTER;
			case ROSTER -> validationErrors().isEmpty() ? ScenarioWizardStep.REVIEW : ScenarioWizardStep.ROSTER;
			case REVIEW -> ScenarioWizardStep.REVIEW;
		};
	}

	public void previous() {
		step = switch (step) {
			case ARENA -> ScenarioWizardStep.ARENA;
			case ROSTER -> ScenarioWizardStep.ARENA;
			case REVIEW -> ScenarioWizardStep.ROSTER;
		};
	}

	public void setAgentCount(int requestedCount) {
		int count = Math.clamp(requestedCount, selectedScenario.minimumAgents(), selectedScenario.maximumAgents());
		while (roster.size() < count) {
			ScenarioAgentConfig template = roster.isEmpty()
					? ScenarioAgentConfig.defaults(selectedScenario.defaultGameMode())
					: roster.getLast().withName("");
			roster.add(selectedScenario.gameModeLocked()
					? template.withGameMode(selectedScenario.defaultGameMode())
					: template);
		}
		while (roster.size() > count) {
			roster.removeLast();
		}
		selectedIndex = Math.clamp(selectedIndex, 0, roster.size() - 1);
	}

	public boolean addDraftSlot() {
		if (roster.size() >= selectedScenario.maximumAgents()) return false;
		ScenarioAgentConfig current = roster.get(selectedIndex);
		ScenarioAgentConfig template;
		if (catalogSupports(current)) {
			template = current.withName("");
		} else {
			List<AgentControlModelOption> options = AgentControlCatalog.currentOptions();
			if (options.isEmpty()) return false;
			AgentControlModelOption option = options.getFirst();
			String reasoning = option.reasoningEfforts().contains("high")
					? "high" : option.reasoningEfforts().getFirst();
			String serviceTier = option.serviceTiers().contains("priority") ? "priority"
					: option.serviceTiers().isEmpty() ? "priority" : option.serviceTiers().getFirst();
			template = new ScenarioAgentConfig(
					option.provider(), option.model(), reasoning, serviceTier, "", current.team(), current.gameMode());
		}
		roster.add(selectedScenario.gameModeLocked()
				? template.withGameMode(selectedScenario.defaultGameMode())
				: template);
		selectedIndex = roster.size() - 1;
		return true;
	}

	public boolean removeFocusedDraftSlot() {
		if (roster.size() <= selectedScenario.minimumAgents()) return false;
		roster.remove(selectedIndex);
		selectedIndex = Math.clamp(selectedIndex, 0, roster.size() - 1);
		return true;
	}

	public void selectOnly(int index) {
		checkIndex(index);
		selectedIndex = index;
	}

	public void applyProvider(String provider) {
		AgentControlCatalog.requireProvider(provider);
		updateSelected(roster.get(selectedIndex).withProvider(provider));
	}

	public void applyModel(String model) {
		updateSelected(roster.get(selectedIndex).withModel(model));
	}

	public void applyReasoning(String reasoning) {
		updateSelected(roster.get(selectedIndex).withReasoning(reasoning));
	}

	public void applyServiceTier(String serviceTier) {
		updateSelected(roster.get(selectedIndex).withServiceTier(serviceTier));
	}

	public void applyTeam(String team) {
		updateSelected(roster.get(selectedIndex).withTeam(team));
	}

	public void applyGameMode(AgentGameMode gameMode) {
		if (selectedScenario.gameModeLocked() && gameMode != selectedScenario.defaultGameMode()) {
			throw new IllegalArgumentException(selectedScenario.title() + " requires "
					+ selectedScenario.defaultGameMode().displayName());
		}
		updateSelected(roster.get(selectedIndex).withGameMode(gameMode));
	}

	public void renameSelected(String name) {
		updateSelected(roster.get(selectedIndex).withName(name));
	}

	public String displayNameAt(int index) {
		checkIndex(index);
		ArrayList<String> allocated = new ArrayList<>();
		for (int current = 0; current <= index; current++) {
			ScenarioAgentConfig config = roster.get(current);
			String preferred = config.name().isBlank()
					? AgentIdentity.defaultPublicName(config.provider(), config.model()) : config.name();
			String publicName = AgentIdentity.allocatePublicName(preferred, allocated);
			if (current == index) return publicName;
			allocated.add(publicName);
		}
		throw new IllegalStateException("scenario roster index was not allocated");
	}

	public List<AgentRosterEntry> rosterEntries() {
		List<AgentRosterEntry> entries = new ArrayList<>(roster.size());
		for (int index = 0; index < roster.size(); index++) {
			ScenarioAgentConfig config = roster.get(index);
			String unavailableReason = unavailableReasonAt(index);
			entries.add(new AgentRosterEntry(
					slotIdAt(index),
					displayNameAt(index),
					config.provider(),
					AgentModelNames.shortLabel(config.provider(), config.model()),
					unavailableReason.isEmpty() ? "Ready" : "Unavailable",
					unavailableReason.isEmpty(),
					unavailableReason
			));
		}
		return List.copyOf(entries);
	}

	public String unavailableReasonAt(int index) {
		checkIndex(index);
		ScenarioAgentConfig config = roster.get(index);
		if (selectedScenario.gameModeLocked() && config.gameMode() != selectedScenario.defaultGameMode()) {
			return "Game mode must be " + selectedScenario.defaultGameMode().displayName();
		}
		if (!AgentControlCatalog.providers().contains(config.provider())) {
			return "Provider " + config.provider() + " is unavailable";
		}
		if (!AgentControlCatalog.models(config.provider()).contains(config.model())) {
			return "Model " + AgentModelNames.displayName(config.provider(), config.model()) + " is unavailable";
		}
		if (!AgentControlCatalog.reasoningEfforts(config.provider(), config.model()).contains(config.reasoning())) {
			return "Thinking level " + config.reasoning() + " is unavailable";
		}
		if (!AgentControlCatalog.serviceTiers(config.provider(), config.model()).contains(config.serviceTier())) {
			return "Speed mode " + config.serviceTier() + " is unavailable";
		}
		return "";
	}

	public AgentVisualIdentity.Resolved previewVisualIdentityAt(int index) {
		checkIndex(index);
		ScenarioAgentConfig config = roster.get(index);
		int oneBasedSlot = index + 1;
		int previewVariant = Math.floorMod(oneBasedSlot - 1, AgentVisualIdentity.INDIVIDUAL_VARIANT_COUNT);
		return AgentVisualIdentity.resolve(config.provider(), config.model(), previewVariant);
	}

	public boolean repairFocusedSlot() {
		return repairFocusedSlot(AgentControlCatalog.currentOptions());
	}

	boolean repairFocusedSlot(List<AgentControlModelOption> options) {
		List<AgentControlModelOption> available = List.copyOf(
				Objects.requireNonNull(options, "catalog options must not be null"));
		if (available.isEmpty()) return false;
		if (unavailableReasonAt(selectedIndex).isEmpty()) return false;
		ScenarioAgentConfig current = roster.get(selectedIndex);
		String provider = available.stream().anyMatch(option -> option.provider().equals(current.provider()))
				? current.provider() : available.getFirst().provider();
		List<AgentControlModelOption> providerOptions = available.stream()
				.filter(option -> option.provider().equals(provider)).toList();
		AgentControlModelOption selectedOption = providerOptions.stream()
				.filter(option -> option.model().equals(current.model()))
				.findFirst().orElse(providerOptions.getFirst());
		String model = selectedOption.model();
		List<String> reasoningEfforts = selectedOption.reasoningEfforts();
		String reasoning = reasoningEfforts.contains(current.reasoning())
				? current.reasoning() : reasoningEfforts.contains("high") ? "high" : reasoningEfforts.getFirst();
		List<String> serviceTiers = selectedOption.serviceTiers().isEmpty()
				? List.of("priority") : selectedOption.serviceTiers();
		String serviceTier = serviceTiers.contains(current.serviceTier()) ? current.serviceTier()
				: serviceTiers.contains("priority") ? "priority" : serviceTiers.getFirst();
		AgentGameMode gameMode = selectedScenario.gameModeLocked()
				? selectedScenario.defaultGameMode() : current.gameMode();
		roster.set(selectedIndex, new ScenarioAgentConfig(
				provider, model, reasoning, serviceTier, current.name(), current.team(), gameMode));
		return true;
	}

	public List<String> validationErrors() {
		List<String> errors = new ArrayList<>();
		if (roster.size() < selectedScenario.minimumAgents() || roster.size() > selectedScenario.maximumAgents()) {
			errors.add("Agent count is outside the arena's supported range");
		}
		for (int index = 0; index < roster.size(); index++) {
			String unavailableReason = unavailableReasonAt(index);
			if (!unavailableReason.isEmpty()) errors.add("Agent " + (index + 1) + ": " + unavailableReason);
		}
		return List.copyOf(errors);
	}

	public boolean canLaunch() {
		return step == ScenarioWizardStep.REVIEW && validationErrors().isEmpty();
	}

	public ScenarioLaunchPlan launchPlan() {
		return launchPlan("");
	}

	public ScenarioLaunchPlan launchPlan(String confirmationToken) {
		List<String> errors = validationErrors();
		if (!errors.isEmpty()) {
			throw new IllegalStateException(String.join("; ", errors));
		}
		List<ScenarioLaunchPlan.Agent> agents = new ArrayList<>(roster.size());
		for (int index = 0; index < roster.size(); index++) {
			ScenarioAgentConfig config = roster.get(index);
			agents.add(new ScenarioLaunchPlan.Agent(
					index + 1,
					displayNameAt(index),
					config.provider(),
					config.model(),
					config.reasoning(),
					config.serviceTier(),
					config.team(),
					config.gameMode()
			));
		}
		return new ScenarioLaunchPlan(
				selectedScenario.id(),
				selectedScenario.title(),
				selectedScenario.mapVersion(),
				deterministicEvents,
				placementMode,
				agents,
				Objects.requireNonNull(confirmationToken, "confirmationToken must not be null")
		);
	}

	private static String modelDisplayName(ScenarioAgentConfig config) {
		if (AgentControlCatalog.providers().contains(config.provider())
				&& AgentControlCatalog.models(config.provider()).contains(config.model())) {
			return AgentControlCatalog.displayName(config.provider(), config.model());
		}
		return AgentModelNames.displayName(config.provider(), config.model());
	}

	private static boolean catalogSupports(ScenarioAgentConfig config) {
		if (!AgentControlCatalog.providers().contains(config.provider())) return false;
		if (!AgentControlCatalog.models(config.provider()).contains(config.model())) return false;
		if (!AgentControlCatalog.reasoningEfforts(config.provider(), config.model()).contains(config.reasoning())) {
			return false;
		}
		return AgentControlCatalog.serviceTiers(config.provider(), config.model()).contains(config.serviceTier());
	}

	private void updateSelected(ScenarioAgentConfig config) {
		roster.set(selectedIndex, Objects.requireNonNull(config, "config must not be null"));
	}

	private void checkIndex(int index) {
		if (index < 0 || index >= roster.size()) {
			throw new IndexOutOfBoundsException("agent index: " + index);
		}
	}
}
