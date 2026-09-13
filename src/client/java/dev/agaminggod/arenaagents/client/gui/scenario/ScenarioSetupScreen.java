package dev.agaminggod.arenaagents.client.gui.scenario;

import dev.agaminggod.arenaagents.agent.AgentConstants;
import dev.agaminggod.arenaagents.agent.AgentGameMode;
import dev.agaminggod.arenaagents.agent.AgentModelNames;
import dev.agaminggod.arenaagents.agent.AgentVisualIdentity;
import dev.agaminggod.arenaagents.client.gui.AgentControlScreen;
import dev.agaminggod.arenaagents.client.gui.AgentRosterGrid;
import dev.agaminggod.arenaagents.client.gui.AgentRosterGridLayout;
import dev.agaminggod.arenaagents.client.gui.ConsoleFont;
import dev.agaminggod.arenaagents.client.gui.ConsoleTheme;
import dev.agaminggod.arenaagents.client.gui.ConsoleFocusIdentity;
import dev.agaminggod.arenaagents.client.gui.ConsoleText;
import dev.agaminggod.arenaagents.client.control.AgentControlClient;
import dev.agaminggod.arenaagents.client.gui.widget.ConsoleButton;
import dev.agaminggod.arenaagents.client.gui.widget.ConsoleCycleButton;
import dev.agaminggod.arenaagents.client.gui.widget.ConsoleEditBox;
import dev.agaminggod.arenaagents.client.gui.widget.ConsoleScenarioTile;
import dev.agaminggod.arenaagents.control.AgentControlCatalog;
import dev.agaminggod.arenaagents.control.AgentControlPresentation;
import dev.agaminggod.arenaagents.control.AgentControlSnapshot;
import dev.agaminggod.arenaagents.control.AgentRosterEntry;
import dev.agaminggod.arenaagents.control.AgentRosterFilter;
import dev.agaminggod.arenaagents.control.AgentRosterPage;
import dev.agaminggod.arenaagents.control.AgentRosterViewState;
import dev.agaminggod.arenaagents.scenario.ScenarioPlacementMode;
import dev.agaminggod.arenaagents.scenario.presentation.ScenarioBuildProgress;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;

/** Responsive Arena tab for the in-game command center. */
public final class ScenarioSetupScreen extends Screen {
	private static final int PANEL_COLOR = ConsoleTheme.PANEL;
	private static final int PANEL_EDGE = ConsoleTheme.BORDER;
	private static final int SURFACE_COLOR = ConsoleTheme.SURFACE;
	private static final int GOLD = ConsoleTheme.ACCENT;
	private static final int TEXT = ConsoleTheme.TEXT;
	private static final int MUTED = ConsoleTheme.MUTED;
	private static final int ERROR = ConsoleTheme.ERROR;
	private static final int SUCCESS = ConsoleTheme.SUCCESS;
	private static final int ROW_HEIGHT = ScenarioSetupLayout.ROW_HEIGHT;
	private static final int GAP = 5;

	private ScenarioSetupState state;
	private final AgentRosterViewState rosterView = new AgentRosterViewState();
	private AgentRosterFilter rosterFilter = AgentRosterFilter.all();
	private List<AgentRosterEntry> rosterEntries = List.of();
	private Map<String, AgentVisualIdentity.Resolved> rosterVisuals = Map.of();
	private AgentRosterGrid rosterGrid;
	private AgentRosterPage visibleRosterPage;
	private boolean compactEditorOpen;
	private boolean queuedInspectorRebuild;
	private int compactEditorSection;
	private String feedback = "";
	private boolean feedbackError;
	private boolean launchPending;
	private boolean firstInitialization = true;
	private Boolean navigationGroupAvailable;
	private ConsoleEditBox nameInput;

	public ScenarioSetupScreen() {
		this(defaultState());
	}

	@Override
	public boolean isPauseScreen() {
		return false;
	}

	private static ScenarioSetupState defaultState() {
		AgentControlClient.Preferences preferences = AgentControlClient.preferences();
		ScenarioBuildProgress progress = AgentControlClient.buildProgressState().progress().orElse(null);
		if (progress != null && progress.status() != ScenarioBuildProgress.Status.READY) {
			var retainedPlan = ScenarioLaunchRegistry.lastAcceptedPlan();
			if (retainedPlan.isPresent()) {
				RetainedPlanResolution retained = resolveRetainedPlan(
						retainedPlan.orElseThrow(), AgentControlClient.catalogAuthoritative());
				if (retained.state().isPresent()) return retained.state().orElseThrow();
				if (retained.discard()) ScenarioLaunchRegistry.clearRetainedPlan();
			}
		}
		return safeDefaults(preferences);
	}

	private static ScenarioSetupState safeDefaults(AgentControlClient.Preferences preferences) {
		try {
			return ScenarioSetupState.defaults(
					preferences.provider(), preferences.model(), preferences.reasoning());
		} catch (IllegalArgumentException | IllegalStateException exception) {
			return ScenarioSetupState.defaults();
		}
	}

	static RetainedPlanResolution resolveRetainedPlan(ScenarioLaunchPlan plan, boolean authoritativeCatalog) {
		java.util.Optional<ScenarioSetupState> restored;
		try {
			restored = java.util.Optional.of(ScenarioSetupState.fromLaunchPlan(plan));
		} catch (IllegalArgumentException | IllegalStateException exception) {
			restored = java.util.Optional.empty();
		}
		return new RetainedPlanResolution(restored, authoritativeCatalog && restored.isEmpty());
	}

	ScenarioSetupScreen(ScenarioSetupState state) {
		super(Minecraft.getInstance(), ConsoleFont.create(Minecraft.getInstance()),
				Component.translatable("screen.arenaagents.setup.title"));
		this.state = state;
	}

	@Override
	protected void init() {
		nameInput = null;
		rosterGrid = null;
		visibleRosterPage = null;
		refreshDraftRoster();
		if (firstInitialization) {
			firstInitialization = false;
			ScenarioBuildProgress progress = AgentControlClient.buildProgressState().progress().orElse(null);
			if (progress != null && progress.status() != ScenarioBuildProgress.Status.READY) {
				for (ScenarioPreset preset : ScenarioPreset.values()) {
					if (preset.title().equals(progress.scenarioTitle())) state.selectScenario(preset);
				}
				state.showBuildDashboard();
				launchPending = true;
			}
		}
		ScenarioSetupLayout layout = layout();
		navigationGroupAvailable = groupAvailable();
		addTabs(layout);
		switch (state.step()) {
			case ARENA -> initArena(layout);
			case ROSTER -> initRoster(layout);
			case REVIEW -> initReview(layout);
		}
	}

	@Override
	public Component getNarrationMessage() {
		MutableComponent narration = getTitle().copy();
		if (launchPending) {
			appendBuildNarration(narration,
					AgentControlClient.buildProgressState().progress().orElse(null));
		} else {
			switch (state.step()) {
				case ARENA -> appendArenaNarration(narration);
				case ROSTER -> appendRosterNarration(narration);
				case REVIEW -> appendReviewNarration(narration);
			}
		}
		if (!feedback.isBlank()) appendNarration(narration, Component.literal(feedback));
		return narration;
	}

	private void appendArenaNarration(MutableComponent narration) {
		ScenarioPreset preset = state.selectedScenario();
		appendNarration(narration, Component.literal(preset.title() + ", " + preset.category() + ", "
				+ preset.duration() + ", " + preset.minimumAgents() + " to "
				+ preset.maximumAgents() + " agents, " + state.placementMode().displayName()));
	}

	private void appendRosterNarration(MutableComponent narration) {
		appendNarration(narration, Component.translatable("screen.arenaagents.roster.event_capacity",
				state.roster().size(), state.selectedScenario().maximumAgents()));
		ScenarioAgentConfig focused = selectedConfig();
		if (focused != null) {
			String reason = state.unavailableReasonAt(state.selectedIndex());
			appendNarration(narration, Component.literal("Agent " + (state.selectedIndex() + 1) + " of "
					+ state.roster().size() + ", " + state.displayNameAt(state.selectedIndex()) + ", "
					+ capitalize(focused.provider()) + ", "
					+ AgentModelNames.displayName(focused.provider(), focused.model()) + ", "
					+ (reason.isBlank() ? "Ready" : "Unavailable")));
			if (!reason.isBlank()) {
				appendNarration(narration,
						Component.translatable("screen.arenaagents.roster.unavailable", reason));
			}
		}
		appendRosterPageNarration(narration, visibleRosterPage);
	}

	private void appendReviewNarration(MutableComponent narration) {
		ScenarioPreset preset = state.selectedScenario();
		appendNarration(narration, Component.literal(preset.title() + ", "
				+ state.placementMode().displayName() + ", "
				+ (state.deterministicEvents() ? "Deterministic" : "Randomized")));
		appendNarration(narration, Component.translatable("screen.arenaagents.roster.event_capacity",
				state.roster().size(), preset.maximumAgents()));
		String blocker = !ScenarioLaunchRegistry.isAvailable()
				? "Launch blocked: arena launches are unavailable"
				: state.validationErrors().isEmpty() ? "Ready to launch"
				: "Launch blocked: " + state.validationErrors().getFirst();
		appendNarration(narration, Component.literal(blocker));
		if (!state.roster().isEmpty()) {
			appendNarration(narration, Component.literal("Focused lineup entry, "
					+ state.displayNameAt(state.selectedIndex())));
		}
	}

	private static void appendBuildNarration(MutableComponent narration, ScenarioBuildProgress progress) {
		if (progress == null) {
			appendNarration(narration, Component.literal("Build request sent"));
			return;
		}
		String status = switch (progress.status()) {
			case BUILDING -> "Building arena";
			case CONFIRMATION_REQUIRED -> "Destructive site confirmation required";
			case READY -> "Arena ready";
			case FAILED -> "Build failed";
			case CANCELLED -> "Build cancelled";
		};
		appendNarration(narration, Component.literal(status + ", " + progress.percent() + "%, "
				+ progress.humanPhase() + ", " + progress.completed() + " of " + progress.total()));
		if (!progress.detail().isBlank()) appendNarration(narration, Component.literal(progress.detail()));
	}

	private static void appendRosterPageNarration(MutableComponent narration, AgentRosterPage page) {
		if (page == null || page.totalFiltered() == 0) return;
		appendNarration(narration, Component.translatable("screen.arenaagents.roster.page",
				page.firstIndex(), page.lastIndex(), page.totalFiltered()));
	}

	private static void appendNarration(MutableComponent narration, Component context) {
		if (context == null || context.getString().isBlank()) return;
		narration.append(". ").append(context);
	}

	@Override
	protected void rebuildWidgets() {
		String focusKey = getFocused() instanceof AbstractWidget widget ? ConsoleFocusIdentity.of(widget) : "";
		super.rebuildWidgets();
		if (focusKey.isBlank()) return;
		children().stream()
				.filter(AbstractWidget.class::isInstance)
				.map(AbstractWidget.class::cast)
				.filter(widget -> ConsoleFocusIdentity.of(widget).equals(focusKey))
				.findFirst()
				.ifPresent(this::setInitialFocus);
	}

	@Override
	public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
		ScenarioSetupLayout layout = layout();
		graphics.fill(0, 0, width, height, ConsoleTheme.BACKDROP);
		graphics.fill(layout.panelLeft() - 1, layout.panelTop() - 1,
				layout.panelRight() + 1, layout.panelBottom() + 1, PANEL_EDGE);
		graphics.fill(layout.panelLeft(), layout.panelTop(), layout.panelRight(), layout.panelBottom(), PANEL_COLOR);
		renderShell(graphics, layout);
		renderStepRail(graphics, layout);
		renderStepContent(graphics, layout);
		if (rosterGrid != null) rosterGrid.extractRenderState(graphics);
		super.extractRenderState(graphics, mouseX, mouseY, partialTick);
		renderStatus(graphics, layout);
	}

	@Override
	public boolean keyPressed(KeyEvent event) {
		if (super.keyPressed(event)) return true;
		if (state.step() == ScenarioWizardStep.ARENA) {
			ScenarioPreset preset = ScenarioPreset.fromLetter(event.key());
			if (preset != null) {
				selectScenario(preset);
				setFeedback(preset.title() + " selected", false);
				rebuildWidgets();
				return true;
			}
		}
		return false;
	}

	@Override
	public boolean mouseScrolled(double mouseX, double mouseY, double horizontalAmount, double verticalAmount) {
		if (rosterGrid != null
				&& rosterGrid.mouseScrolled(mouseX, mouseY, horizontalAmount, verticalAmount)) {
			return true;
		}
		return super.mouseScrolled(mouseX, mouseY, horizontalAmount, verticalAmount);
	}

	@Override
	public void tick() {
		super.tick();
		boolean currentGroupAvailable = groupAvailable();
		if (navigationGroupAvailable != null && currentGroupAvailable != navigationGroupAvailable) {
			navigationGroupAvailable = currentGroupAvailable;
			rebuildWidgets();
			return;
		}
		if (!queuedInspectorRebuild) return;
		queuedInspectorRebuild = false;
		rebuildWidgets();
	}

	private void addTabs(ScenarioSetupLayout layout) {
		boolean showGroup = groupAvailable();
		if (layout.sideNavigation()) {
			int x = layout.panelLeft() + 10;
			int width = layout.contentLeft() - layout.panelLeft() - 24;
			int y = layout.navigationTop();
			addRenderableWidget(consoleButton(Component.translatable("screen.arenaagents.navigation.agents"),
					x, y, width, ROW_HEIGHT, false, this::openAgents));
			if (showGroup) {
				addRenderableWidget(consoleButton(Component.translatable("screen.arenaagents.navigation.group"),
						x, y + 31, width, ROW_HEIGHT, false, this::openGroup));
			}
			addRenderableWidget(consoleButton(Component.translatable("screen.arenaagents.navigation.live"),
					x, y + (showGroup ? 62 : 31), width, ROW_HEIGHT, false, this::openLiveArena));
			addRenderableWidget(consoleButton(Component.translatable("screen.arenaagents.navigation.build"),
					x, y + (showGroup ? 93 : 62), width, ROW_HEIGHT, true, () -> { }));
			return;
		}
		int x = layout.contentLeft();
		List<String> labels = showGroup
				? List.of("Agents", "Group", "Live", "Build")
				: List.of("Agents", "Live", "Build");
		List<Runnable> actions = showGroup
				? List.of(this::openAgents, this::openGroup, this::openLiveArena, () -> { })
				: List.of(this::openAgents, this::openLiveArena, () -> { });
		int available = layout.contentWidth() - GAP * (labels.size() - 1);
		int buttonWidth = available / labels.size();
		for (int index = 0; index < labels.size(); index++) {
			int buttonX = x + index * (buttonWidth + GAP);
			int actualWidth = index == labels.size() - 1 ? layout.contentRight() - buttonX : buttonWidth;
			addRenderableWidget(consoleButton(labels.get(index), buttonX, layout.navigationTop(), actualWidth,
					ROW_HEIGHT, index == labels.size() - 1, actions.get(index)));
		}
	}

	private boolean groupAvailable() {
		return AgentControlClient.snapshot().map(AgentControlSnapshot::groupAvailable).orElse(false);
	}

	private void initArena(ScenarioSetupLayout layout) {
		int left = layout.contentLeft();
		int available = layout.contentWidth();
		if (layout.compactArenaPicker()) {
			int tileWidth = (available - GAP * (ScenarioPreset.values().length - 1))
					/ ScenarioPreset.values().length;
			for (int index = 0; index < ScenarioPreset.values().length; index++) {
				ScenarioPreset preset = ScenarioPreset.values()[index];
				int x = left + index * (tileWidth + GAP);
				int width = index == ScenarioPreset.values().length - 1
						? layout.contentRight() - x : tileWidth;
				addRenderableWidget(consoleButton(String.valueOf(preset.letter()), x, layout.contentTop(), width,
						ROW_HEIGHT, state.selectedScenario() == preset, preset.accentColor(), () -> {
							selectScenario(preset);
							setFeedback(preset.title() + " selected", false);
							rebuildWidgets();
						}));
			}
			int placementY = layout.contentBottom() - ROW_HEIGHT;
			addRenderableWidget(new ConsoleCycleButton<>(font, left, placementY, available, ROW_HEIGHT,
					Component.translatable("screen.arenaagents.build.location"),
					List.of(ScenarioPlacementMode.values()), state.placementMode(),
					mode -> Component.literal(mode.displayName()), this::setPlacementMode));
			addFooter(layout, false, true, "Next");
			return;
		}
		int columns = 2;
		int cardWidth = (available - (columns - 1) * GAP) / columns;
		int rows = (ScenarioPreset.values().length + columns - 1) / columns;
		int infoHeight = 38;
		int cardsHeight = layout.contentHeight() - infoHeight - GAP;
		int cardHeight = Math.clamp((cardsHeight - (rows - 1) * GAP) / rows, 32, 64);
		for (int index = 0; index < ScenarioPreset.values().length; index++) {
			ScenarioPreset preset = ScenarioPreset.values()[index];
			int column = index % columns;
			int row = index / columns;
			int x = left + column * (cardWidth + GAP);
			int y = layout.contentTop() + row * (cardHeight + GAP);
			boolean selected = state.selectedScenario() == preset;
			addRenderableWidget(new ConsoleScenarioTile(
					font, x, y, cardWidth, cardHeight,
					Component.literal(String.valueOf(preset.letter())), Component.literal(preset.title()),
					Component.literal(preset.category() + ", " + preset.duration()),
					selected, preset.accentColor(), () -> {
						selectScenario(preset);
						setFeedback(preset.category() + ", " + preset.duration(), false);
						rebuildWidgets();
					}
			));
		}
		int placementY = layout.contentBottom() - ROW_HEIGHT;
		addRenderableWidget(new ConsoleCycleButton<>(font, left, placementY, available, ROW_HEIGHT,
				Component.translatable("screen.arenaagents.build.location"),
				List.of(ScenarioPlacementMode.values()), state.placementMode(),
				mode -> Component.literal(mode.displayName()), this::setPlacementMode));
		addFooter(layout, false, true, "Next");
	}

	private void initRoster(ScenarioSetupLayout layout) {
		if (layout.compactRoster() && compactEditorOpen) {
			ScenarioSetupLayout.Bounds editor = layout.compactEditorBounds();
			addCompactRosterEditor(editor.left(), editor.top(), editor.width());
		} else {
			addLineupControls(layout.rosterHeaderBounds());
			if (filtersVisible()) addRosterFilters(layout.filterBounds(false));
			addRosterGrid(layout.rosterBounds(filtersVisible(), false), false);
			if (!layout.compactRoster()) {
				ScenarioSetupLayout.Bounds editor = layout.editorBounds();
				addRosterEditor(editor.left(), editor.top(), editor.width(), false);
			}
		}
		addFooter(layout, true, true, "Review setup");
	}

	private void initReview(ScenarioSetupLayout layout) {
		if (launchPending) {
			addBuildFooter(layout);
			return;
		}
		ScenarioSetupLayout.Bounds controls = layout.reviewControlBounds();
		int half = (controls.width() - GAP) / 2;
		addRenderableWidget(new ConsoleCycleButton<>(font, controls.left(), controls.top(), half, ROW_HEIGHT,
				Component.literal("Event seed"), List.of(true, false), state.deterministicEvents(),
				value -> Component.literal(value ? "Deterministic" : "Randomized"), state::setDeterministicEvents));
		addRenderableWidget(new ConsoleCycleButton<>(font, controls.left() + half + GAP, controls.top(),
				controls.width() - half - GAP, ROW_HEIGHT,
				Component.translatable("screen.arenaagents.build.location"), List.of(ScenarioPlacementMode.values()),
				state.placementMode(), mode -> Component.literal(mode.displayName()), this::setPlacementMode));
		if (filtersVisible()) addRosterFilters(layout.filterBounds(true));
		addRosterGrid(layout.rosterBounds(filtersVisible(), true), true);
		addFooter(layout, true, true, "Launch arena");
	}

	private void addBuildFooter(ScenarioSetupLayout layout) {
		ScenarioBuildProgress progress = AgentControlClient.buildProgressState().progress().orElse(null);
		int buttonWidth = layout.footerButtonWidth();
		addRenderableWidget(consoleButton("Close", layout.contentLeft(), layout.footerY(), buttonWidth,
				ROW_HEIGHT, false, this::onClose));
		if (progress != null && progress.status() == ScenarioBuildProgress.Status.CONFIRMATION_REQUIRED) {
			addRenderableWidget(new ConsoleButton(font, layout.contentRight() - buttonWidth, layout.footerY(), buttonWidth,
					ROW_HEIGHT, Component.literal("Confirm overwrite"), false, GOLD, ConsoleButton.Tone.PRIMARY,
					() -> confirmLaunch(progress.confirmationToken())));
		} else if (progress != null && progress.status() == ScenarioBuildProgress.Status.FAILED) {
			addRenderableWidget(new ConsoleButton(font, layout.contentRight() - buttonWidth, layout.footerY(), buttonWidth,
					ROW_HEIGHT, Component.literal("Clean up site"), false, GOLD, ConsoleButton.Tone.PRIMARY,
					() -> cancelBuild(progress.buildId())));
		} else if (progress != null && !progress.terminal()) {
			addRenderableWidget(new ConsoleButton(font, layout.contentRight() - buttonWidth, layout.footerY(), buttonWidth,
					ROW_HEIGHT, Component.literal("Cancel build"), false, GOLD, ConsoleButton.Tone.PRIMARY,
					() -> cancelBuild(progress.buildId())));
		} else {
			addRenderableWidget(new ConsoleButton(font, layout.contentRight() - buttonWidth, layout.footerY(), buttonWidth,
					ROW_HEIGHT, Component.literal("Open live arena"), false, GOLD, ConsoleButton.Tone.PRIMARY,
					this::openLiveArena));
		}
	}

	private void addLineupControls(ScenarioSetupLayout.Bounds bounds) {
		int buttonWidth = (bounds.width() - GAP * 2) / 3;
		String countLabel = bounds.width() >= 420
				? Component.translatable("screen.arenaagents.roster.event_capacity", state.roster().size(),
						state.selectedScenario().maximumAgents()).getString()
				: state.roster().size() + " of " + state.selectedScenario().maximumAgents();
		ConsoleButton count = consoleButton(countLabel, bounds.left(), bounds.top(), buttonWidth,
				ROW_HEIGHT, false, () -> { });
		count.active = false;
		addRenderableWidget(count);
		int addX = bounds.left() + buttonWidth + GAP;
		ConsoleButton add = consoleButton("Add", addX, bounds.top(), buttonWidth, ROW_HEIGHT, false, this::addDraftSlot);
		add.active = state.roster().size() < state.selectedScenario().maximumAgents();
		addRenderableWidget(add);
		int removeX = addX + buttonWidth + GAP;
		ConsoleButton remove = consoleButton("Remove", removeX, bounds.top(), bounds.right() - removeX,
				ROW_HEIGHT, false, this::removeFocusedDraftSlot);
		remove.active = state.roster().size() > state.selectedScenario().minimumAgents();
		addRenderableWidget(remove);
	}

	private void addRosterFilters(ScenarioSetupLayout.Bounds bounds) {
		int controlWidth = (bounds.width() - GAP * 2) / 3;
		ConsoleEditBox query = new ConsoleEditBox(font, bounds.left(), bounds.top(), controlWidth, ROW_HEIGHT,
				Component.translatable("screen.arenaagents.roster.search"),
				Component.translatable("screen.arenaagents.roster.search_hint"),
				"scenario-roster-query");
		query.setMaxLength(80);
		query.setValue(rosterFilter.query());
		query.setResponder(value -> applyRosterFilter(
				new AgentRosterFilter(value, rosterFilter.provider(), rosterFilter.state())));
		addRenderableWidget(query);

		List<String> providers = providerOptions();
		addRenderableWidget(new ConsoleCycleButton<>(font, bounds.left() + controlWidth + GAP, bounds.top(),
				controlWidth, ROW_HEIGHT,
				Component.translatable("screen.arenaagents.roster.filter.provider"), providers, rosterFilter.provider(),
				value -> value.isBlank()
						? Component.translatable("screen.arenaagents.roster.filter.all_providers")
						: Component.literal(capitalize(value)),
				value -> applyRosterFilter(
						new AgentRosterFilter(rosterFilter.query(), value, rosterFilter.state()))));
		int statusX = bounds.left() + (controlWidth + GAP) * 2;
		addRenderableWidget(new ConsoleCycleButton<>(font, statusX, bounds.top(), bounds.right() - statusX,
				ROW_HEIGHT, Component.translatable("screen.arenaagents.roster.filter.status"),
				List.of("", "Ready", "Unavailable"), rosterFilter.state(),
				value -> value.isBlank()
						? Component.translatable("screen.arenaagents.roster.filter.all_statuses")
						: Component.literal(value),
				value -> applyRosterFilter(
						new AgentRosterFilter(rosterFilter.query(), rosterFilter.provider(), value))));
	}

	private void addRosterGrid(ScenarioSetupLayout.Bounds bounds, boolean review) {
		if (bounds.width() < 24 || bounds.height() < 24) return;
		AgentRosterGridLayout gridLayout = AgentRosterGridLayout.calculate(
				bounds.left(), bounds.top(), bounds.right(), bounds.bottom(), filteredCount());
		int pageSize = gridLayout.pageSize() == 0 ? 1 : gridLayout.pageSize();
		AgentRosterPage page = rosterView.page(pageSize);
		visibleRosterPage = page;
		Set<String> includedSlots = new LinkedHashSet<>();
		for (AgentRosterEntry entry : rosterEntries) includedSlots.add(entry.id());
		rosterGrid = new AgentRosterGrid(
				font, page, gridLayout, includedSlots, AgentRosterGrid.Mode.FOCUS_ONLY,
				id -> id.equals(rosterView.focusedId()), rosterVisuals::get,
				new AgentRosterGrid.Actions() {
					@Override
					public void focus(String id) {
						focusSlot(id);
						focusRosterWidget(id);
					}

					@Override
					public void open(String id) {
						focusSlot(id);
						if (review) state.previous();
						if (layout().compactRoster()) compactEditorOpen = true;
						queuedInspectorRebuild = true;
					}

					@Override
					public void toggle(String id) {
					}

					@Override
					public void selectRangeTo(String id) {
					}

					@Override
					public void selectAll() {
					}

					@Override
					public void changePage(int delta) {
						int pageSize = Math.max(1, gridLayout.pageSize());
						int before = rosterView.page(pageSize).page();
						if (delta < 0) rosterView.previousPage(pageSize);
						else if (delta > 0) rosterView.nextPage(pageSize);
						if (rosterView.page(pageSize).page() != before) rebuildWidgets();
					}
				});
		for (AbstractWidget widget : rosterGrid.widgets()) addRenderableWidget(widget);
	}

	private void focusRosterWidget(String id) {
		String identity = "agent-tile:" + id;
		children().stream()
				.filter(AbstractWidget.class::isInstance)
				.map(AbstractWidget.class::cast)
				.filter(widget -> ConsoleFocusIdentity.of(widget).equals(identity))
				.findFirst()
				.ifPresent(this::setInitialFocus);
	}

	private void addCompactRosterEditor(int x, int y, int width) {
		ScenarioAgentConfig anchor = selectedConfig();
		if (anchor == null) return;
		if (!state.unavailableReasonAt(state.selectedIndex()).isEmpty()) {
			addInvalidEditor(x, y, width);
			return;
		}
		int tabWidth = (width - GAP) / 2;
		addRenderableWidget(consoleButton(Component.translatable("screen.arenaagents.roster.model_speed"),
				x, y, tabWidth, ROW_HEIGHT,
				compactEditorSection == 0, () -> {
					compactEditorSection = 0;
					rebuildWidgets();
				}));
		addRenderableWidget(consoleButton(Component.translatable("screen.arenaagents.roster.identity_game"),
				x + tabWidth + GAP, y,
				width - tabWidth - GAP, ROW_HEIGHT, compactEditorSection == 1, () -> {
					compactEditorSection = 1;
					rebuildWidgets();
				}));
		int editorY = y + ROW_HEIGHT + 3;
		int columnWidth = (width - GAP) / 2;
		int right = x + columnWidth + GAP;
		if (compactEditorSection == 0) {
			addRenderableWidget(new ConsoleCycleButton<>(font, x, editorY, columnWidth, ROW_HEIGHT,
					Component.literal("Provider"), AgentControlCatalog.providers(), anchor.provider(),
					value -> Component.literal(capitalize(value)),
					value -> applyAndRebuild(() -> state.applyProvider(value))));
			addRenderableWidget(new ConsoleCycleButton<>(font, right, editorY, width - columnWidth - GAP, ROW_HEIGHT,
					Component.literal("Model"), AgentControlCatalog.models(anchor.provider()), anchor.model(),
					value -> Component.literal(AgentControlCatalog.displayName(anchor.provider(), value)),
					value -> applyAndRebuild(() -> state.applyModel(value))));
			int secondY = editorY + ROW_HEIGHT + 2;
			addRenderableWidget(new ConsoleCycleButton<>(font, x, secondY, columnWidth, ROW_HEIGHT,
					Component.literal("Thinking"), AgentControlCatalog.reasoningEfforts(anchor.provider(), anchor.model()),
					anchor.reasoning(), Component::literal,
					value -> applyAndRebuild(() -> state.applyReasoning(value))));
			addSpeedModeControl(anchor, right, secondY, width - columnWidth - GAP);
			return;
		}

		nameInput = consoleEditBox(x, editorY, width, "compact-agent-name");
		nameInput.setMaxLength(AgentConstants.MAX_USER_NAME_LENGTH);
		nameInput.setValue(anchor.name());
		nameInput.setResponder(value -> {
			state.renameSelected(value);
			refreshDraftRoster();
		});
		addRenderableWidget(nameInput);
		int secondY = editorY + ROW_HEIGHT + 2;
		addRenderableWidget(new ConsoleCycleButton<>(font, x, secondY, columnWidth, ROW_HEIGHT,
				Component.literal("Team"), ScenarioAgentConfig.TEAMS, anchor.team(), Component::literal,
				value -> applyAndRebuild(() -> state.applyTeam(value))));
		addRenderableWidget(new ConsoleCycleButton<>(font, right, secondY, width - columnWidth - GAP, ROW_HEIGHT,
				Component.literal("Game mode"), List.of(AgentGameMode.values()), anchor.gameMode(),
				value -> Component.literal(value.displayName()),
				value -> applyAndRebuild(() -> state.applyGameMode(value))));
	}

	private void addRosterEditor(int x, int y, int width, boolean compact) {
		ScenarioAgentConfig anchor = selectedConfig();
		if (anchor == null) return;
		if (!state.unavailableReasonAt(state.selectedIndex()).isEmpty()) {
			addInvalidEditor(x, y, width);
			return;
		}
		int columnWidth = compact ? (width - GAP) / 2 : width;
		int right = x + columnWidth + GAP;
		addRenderableWidget(new ConsoleCycleButton<>(font, x, y, columnWidth, ROW_HEIGHT,
				Component.literal("Provider"), AgentControlCatalog.providers(), anchor.provider(),
				value -> Component.literal(capitalize(value)),
				value -> applyAndRebuild(() -> state.applyProvider(value))));
		addRenderableWidget(new ConsoleCycleButton<>(font, compact ? right : x, compact ? y : y + 25,
				columnWidth, ROW_HEIGHT, Component.literal("Model"), AgentControlCatalog.models(anchor.provider()),
				anchor.model(), value -> Component.literal(AgentControlCatalog.displayName(anchor.provider(), value)),
				value -> applyAndRebuild(() -> state.applyModel(value))));
		int secondY = compact ? y + 25 : y + 50;
		addRenderableWidget(new ConsoleCycleButton<>(font, x, secondY, columnWidth, ROW_HEIGHT,
				Component.literal("Thinking"), AgentControlCatalog.reasoningEfforts(anchor.provider(), anchor.model()),
				anchor.reasoning(), Component::literal,
				value -> applyAndRebuild(() -> state.applyReasoning(value))));
		addSpeedModeControl(anchor, compact ? right : x, compact ? secondY : y + 75, columnWidth);
		addRenderableWidget(new ConsoleCycleButton<>(font, x, compact ? y + 50 : y + 100,
				columnWidth, ROW_HEIGHT, Component.literal("Team"), ScenarioAgentConfig.TEAMS, anchor.team(),
				Component::literal, value -> applyAndRebuild(() -> state.applyTeam(value))));
		int thirdY = compact ? y + 50 : y + 125;
		addRenderableWidget(new ConsoleCycleButton<>(font, compact ? right : x, thirdY, columnWidth, ROW_HEIGHT,
				Component.literal("Game mode"), List.of(AgentGameMode.values()), anchor.gameMode(),
				value -> Component.literal(value.displayName()),
				value -> applyAndRebuild(() -> state.applyGameMode(value))));
		nameInput = consoleEditBox(x, compact ? y + 75 : y + 150,
				compact ? width : columnWidth, "roster-agent-name");
		nameInput.setMaxLength(AgentConstants.MAX_USER_NAME_LENGTH);
		nameInput.setValue(anchor.name());
		nameInput.setResponder(value -> {
			state.renameSelected(value);
			refreshDraftRoster();
		});
		addRenderableWidget(nameInput);
	}

	private void addInvalidEditor(int x, int y, int width) {
		addRenderableWidget(new ConsoleButton(font, x, y + ROW_HEIGHT + GAP, width, ROW_HEIGHT,
				Component.literal("Update settings"), false, GOLD, ConsoleButton.Tone.PRIMARY,
				this::repairFocusedSlot));
	}

	private void addSpeedModeControl(ScenarioAgentConfig anchor, int x, int y, int width) {
		if (AgentControlCatalog.hasSpeedMode(anchor.provider(), anchor.model())) {
			addRenderableWidget(new ConsoleCycleButton<>(font, x, y, width, ROW_HEIGHT,
					Component.literal("Speed mode"), AgentControlCatalog.serviceTiers(anchor.provider(), anchor.model()),
					anchor.serviceTier(), value -> Component.literal(AgentControlPresentation.speedLabel(value)),
					value -> applyAndRebuild(() -> state.applyServiceTier(value))));
			return;
		}
		ConsoleButton unavailable = consoleButton(
				Component.translatable("screen.arenaagents.speed_unavailable"),
				x, y, width, ROW_HEIGHT, false, () -> { }
		);
		unavailable.active = false;
		addRenderableWidget(unavailable);
	}

	private void addFooter(ScenarioSetupLayout layout, boolean previous, boolean primary, String primaryLabel) {
		int left = layout.contentLeft();
		int right = layout.contentRight();
		int buttonWidth = layout.footerButtonWidth();
		if (previous) {
			String previousLabel = state.step() == ScenarioWizardStep.ROSTER && layout.compactRoster()
					&& compactEditorOpen ? "Back to lineup"
					: state.step() == ScenarioWizardStep.ROSTER ? "Back to arena" : "Back to lineup";
			addRenderableWidget(consoleButton(previousLabel, left, layout.footerY(),
					buttonWidth, ROW_HEIGHT, false, this::goPrevious));
		} else {
			addRenderableWidget(consoleButton("Agents", left, layout.footerY(), buttonWidth, ROW_HEIGHT,
					false, this::openAgents));
		}
		addRenderableWidget(consoleButton("Cancel", right - buttonWidth * 2 - GAP, layout.footerY(),
				buttonWidth, ROW_HEIGHT, false, this::onClose));
		ConsoleButton action = new ConsoleButton(font, right - buttonWidth, layout.footerY(), buttonWidth,
				ROW_HEIGHT, Component.literal(primaryLabel), false, GOLD,
				ConsoleButton.Tone.PRIMARY, () -> {
			if (state.step() == ScenarioWizardStep.REVIEW) launch();
			else goNext();
		});
		action.active = primary && (state.step() != ScenarioWizardStep.REVIEW
				|| !launchPending && state.canLaunch() && ScenarioLaunchRegistry.isAvailable());
		addRenderableWidget(action);
	}

	private void renderStepRail(GuiGraphicsExtractor graphics, ScenarioSetupLayout layout) {
		ScenarioWizardStep[] steps = ScenarioWizardStep.values();
		int x = layout.contentLeft();
		int y = layout.stepRailBottom() - (layout.sideNavigation() ? 20 : 14);
		int width = layout.contentWidth();
		int segmentWidth = width / steps.length;
		for (int index = 0; index < steps.length; index++) {
			ScenarioWizardStep step = steps[index];
			int segmentX = x + index * segmentWidth;
			int color = index <= state.step().ordinal() ? GOLD : 0xFF4A4F59;
			graphics.fill(segmentX, y, segmentX + segmentWidth - 3, y + 2, color);
			ConsoleText.centered(graphics, font, (index + 1) + "  " + step.displayName(),
					segmentX + (segmentWidth - 3) / 2, y + 7,
					index == state.step().ordinal() ? TEXT : MUTED);
		}
	}

	private void renderStepContent(GuiGraphicsExtractor graphics, ScenarioSetupLayout layout) {
		int x = layout.contentLeft();
		int width = layout.contentWidth();
		switch (state.step()) {
			case ARENA -> renderArenaDetails(graphics, layout, x, width);
			case ROSTER -> renderRosterBands(graphics, layout, x, width);
			case REVIEW -> renderReview(graphics, layout, x, width);
		}
	}

	private void renderShell(GuiGraphicsExtractor graphics, ScenarioSetupLayout layout) {
		if (layout.sideNavigation()) {
			int navigationRight = layout.contentLeft() - 14;
			graphics.fill(layout.panelLeft(), layout.panelTop(), navigationRight, layout.panelBottom(), 0xFF111820);
			graphics.fill(navigationRight, layout.panelTop(), navigationRight + 1, layout.panelBottom(), PANEL_EDGE);
			graphics.text(font, "Arena", layout.panelLeft() + 14, layout.panelTop() + 8, GOLD, false);
			graphics.text(font, "Agents", layout.panelLeft() + 14, layout.panelTop() + 19, TEXT, false);
			graphics.text(font, "Field Console", layout.panelLeft() + 14, layout.panelTop() + 35, MUTED, false);
			graphics.text(font, "Operations", layout.panelLeft() + 14, layout.navigationTop() - 15, MUTED, false);
			return;
		}
		graphics.fill(layout.panelLeft(), layout.panelTop(), layout.panelRight(), layout.panelTop() + 37, 0xFF111820);
		graphics.fill(layout.panelLeft(), layout.panelTop() + 37, layout.panelRight(), layout.panelTop() + 38, PANEL_EDGE);
		graphics.text(font, "Arena Agents", layout.panelLeft() + 14, layout.panelTop() + 10, TEXT, false);
	}

	private void renderArenaDetails(GuiGraphicsExtractor graphics, ScenarioSetupLayout layout, int x, int width) {
		ScenarioPreset preset = state.selectedScenario();
		if (layout.compactArenaPicker()) {
			int y = layout.contentTop() + ROW_HEIGHT + 3;
			int bottom = layout.contentBottom() - ROW_HEIGHT - 3;
			graphics.fill(x, y, x + width, bottom, SURFACE_COLOR);
			graphics.text(font, preset.letter() + ", " + fit(preset.title(), width - 34), x + 7, y + 5,
					preset.accentColor(), false);
			graphics.text(font, fit(preset.category() + ", " + preset.duration() + ", "
					+ preset.minimumAgents() + "-" + preset.maximumAgents() + " agents", width - 14),
					x + 7, y + 18, MUTED, false);
			return;
		}
		int y = layout.contentBottom() - 35;
		graphics.fill(x, y, x + width, y + 35, SURFACE_COLOR);
		graphics.text(font, preset.category() + ", " + preset.duration() + ", "
				+ preset.minimumAgents() + "-" + preset.maximumAgents() + " agents", x + 7, y + 5, TEXT, false);
		String description = fit(preset.description(), width - 14);
		graphics.text(font, description, x + 7, y + 19, MUTED, false);
	}

	private void renderRosterBands(GuiGraphicsExtractor graphics, ScenarioSetupLayout layout, int x, int width) {
		if (layout.showsRoster(compactEditorOpen)) {
			ScenarioSetupLayout.Bounds roster = layout.rosterBounds(filtersVisible(), false);
			if (filteredCount() == 0) {
				ConsoleText.centered(graphics, font, "No lineup slots match these filters",
						roster.left() + roster.width() / 2, roster.top() + 8, MUTED);
			}
		}
		if (!layout.showsEditor(compactEditorOpen)) return;
		String reason = state.unavailableReasonAt(state.selectedIndex());
		if (reason.isEmpty()) return;
		ScenarioSetupLayout.Bounds editor = layout.compactRoster()
				? layout.compactEditorBounds() : layout.editorBounds();
		graphics.text(font, fit(reason, editor.width()), editor.left(), editor.top(), ERROR, false);
	}

	private void renderReview(GuiGraphicsExtractor graphics, ScenarioSetupLayout layout, int x, int width) {
		if (launchPending) {
			renderBuildProgress(graphics, layout, x, width);
			return;
		}
		ScenarioSetupLayout.Bounds roster = layout.rosterBounds(filtersVisible(), true);
		if (filteredCount() == 0) {
			ConsoleText.centered(graphics, font, "No lineup slots match these filters",
					roster.left() + roster.width() / 2, roster.top() + 8, MUTED);
		}
	}

	private void renderBuildProgress(
			GuiGraphicsExtractor graphics,
			ScenarioSetupLayout layout,
			int x,
			int width
	) {
		ScenarioBuildProgress progress = AgentControlClient.buildProgressState().progress().orElse(null);
		int y = layout.contentTop();
		int color = progress == null ? GOLD : switch (progress.status()) {
			case FAILED -> ERROR;
			case READY -> SUCCESS;
			case CANCELLED -> MUTED;
			case BUILDING, CONFIRMATION_REQUIRED -> GOLD;
		};
		graphics.fill(x, y, x + width, layout.contentBottom(), SURFACE_COLOR);
		if (progress == null) {
			graphics.text(font, "Build request sent", x + 12, y + 12, GOLD, false);
			graphics.text(font, "Waiting for the server to publish the build location and first progress update.",
					x + 12, y + 31, TEXT, false);
			return;
		}
		String heading = switch (progress.status()) {
			case BUILDING -> "Building arena";
			case CONFIRMATION_REQUIRED -> "Confirm site overwrite";
			case READY -> "Arena ready";
			case FAILED -> "Build failed";
			case CANCELLED -> "Build cancelled";
		};
		graphics.text(font, heading, x + 12, y + 12, color, false);
		graphics.text(font, fit(progress.scenarioTitle(), width - 100), x + 12, y + 27, TEXT, false);
		String percentage = progress.percent() + "%";
		graphics.text(font, percentage, x + width - font.width(percentage) - 12, y + 12, color, false);
		int barLeft = x + 12;
		int barRight = x + width - 12;
		graphics.fill(barLeft, y + 43, barRight, y + 49, 0xFF0D1319);
		graphics.fill(barLeft, y + 43, barLeft + (barRight - barLeft) * progress.percent() / 100, y + 49, color);
		graphics.text(font, progress.humanPhase() + ", " + progress.completed() + " of " + progress.total(),
				x + 12, y + 58, TEXT, false);
		graphics.text(font, "World changes made: " + progress.changedBlocks(), x + 12, y + 72, MUTED, false);
		graphics.text(font, "Build location: " + progress.originLabel(), x + 12, y + 86, MUTED, false);
		if (layout.contentHeight() >= 112) {
			graphics.text(font, fit(progress.detail(), width - 24), x + 12, y + 100, color, false);
		}
	}

	private void renderStatus(GuiGraphicsExtractor graphics, ScenarioSetupLayout layout) {
		String message = feedback;
		int color = feedbackError ? ERROR : SUCCESS;
		if (launchPending) {
			ScenarioBuildProgress progress = AgentControlClient.buildProgressState().progress().orElse(null);
			if (progress != null) {
				message = progress.detail();
				color = progress.status() == ScenarioBuildProgress.Status.FAILED ? ERROR
						: progress.status() == ScenarioBuildProgress.Status.READY ? SUCCESS
						: progress.status() == ScenarioBuildProgress.Status.CANCELLED ? MUTED : GOLD;
			}
		} else if (message.isBlank() && state.step() == ScenarioWizardStep.ROSTER) {
			message = "Editing agent " + (state.selectedIndex() + 1) + " of " + state.roster().size()
					+ ", " + state.displayNameAt(state.selectedIndex());
			color = MUTED;
		} else if (message.isBlank() && state.step() == ScenarioWizardStep.REVIEW) {
			if (!ScenarioLaunchRegistry.isAvailable()) {
				message = "Launch blocked: the server has not registered arena launches";
				color = ERROR;
			} else if (!state.validationErrors().isEmpty()) {
				message = "Launch blocked: " + state.validationErrors().getFirst();
				color = ERROR;
			} else {
				message = "Ready, exact model settings preserved, no silent fallback";
				color = SUCCESS;
			}
		}
		if (!message.isBlank()) {
			int y = layout.sideNavigation() ? layout.statusTop() : layout.panelTop() + 22;
			ConsoleText.centered(graphics, font, fit(message, layout.panelWidth() - 28), width / 2, y, color);
		}
	}

	private void openAgents() {
		if (minecraft != null) minecraft.setScreen(new AgentControlScreen(this));
	}

	private void openGroup() {
		if (minecraft != null) minecraft.setScreen(AgentControlScreen.group(this));
	}

	private void openLiveArena() {
		if (minecraft != null) minecraft.setScreen(AgentControlScreen.live(this));
	}

	public void acceptBuildProgress() {
		if (minecraft != null && launchPending) rebuildWidgets();
	}

	public void acceptBuildProgressClear() {
		if (!launchPending) return;
		launchPending = false;
		setFeedback("", false);
		if (minecraft != null) rebuildWidgets();
	}

	public void acceptCatalogUpdate() {
		if (launchPending) {
			ScenarioLaunchRegistry.lastAcceptedPlan().ifPresent(plan -> {
				RetainedPlanResolution retained = resolveRetainedPlan(plan, true);
				retained.state().ifPresent(restored -> state = restored);
				if (retained.discard()) ScenarioLaunchRegistry.clearRetainedPlan();
			});
		}
		refreshDraftRoster();
		if (minecraft != null) rebuildWidgets();
	}

	private void goPrevious() {
		if (state.step() == ScenarioWizardStep.ROSTER && layout().compactRoster() && compactEditorOpen) {
			compactEditorOpen = false;
			setFeedback("", false);
			rebuildWidgets();
			return;
		}
		state.previous();
		compactEditorOpen = false;
		setFeedback("", false);
		rebuildWidgets();
	}

	private void goNext() {
		ScenarioWizardStep previous = state.step();
		state.next();
		if (previous == ScenarioWizardStep.ROSTER && state.step() == ScenarioWizardStep.REVIEW) {
			resetRosterFilters();
			compactEditorOpen = false;
		}
		setFeedback("", false);
		rebuildWidgets();
	}

	private void addDraftSlot() {
		if (!state.addDraftSlot()) return;
		refreshDraftRoster();
		setFeedback("Added " + state.displayNameAt(state.selectedIndex()), false);
		rebuildWidgets();
	}

	private void removeFocusedDraftSlot() {
		if (!state.removeFocusedDraftSlot()) return;
		refreshDraftRoster();
		setFeedback("Event team now has " + state.roster().size() + " agents", false);
		rebuildWidgets();
	}

	private void repairFocusedSlot() {
		if (!state.repairFocusedSlot()) return;
		refreshDraftRoster();
		setFeedback("Agent settings updated to the current catalog", false);
		rebuildWidgets();
	}

	private void launch() {
		ScenarioLaunchRegistry.Result result = launchSafely(state);
		setFeedback(result.message(), !result.accepted());
		if (result.accepted()) {
			launchPending = true;
			rebuildWidgets();
		}
	}

	private void confirmLaunch(String confirmationToken) {
		ScenarioLaunchRegistry.Result result = launchSafely(state, confirmationToken);
		setFeedback(result.message(), !result.accepted());
		if (result.accepted()) launchPending = true;
		rebuildWidgets();
	}

	private void cancelBuild(String buildId) {
		ScenarioLaunchRegistry.Result result = ScenarioLaunchRegistry.cancel(buildId);
		setFeedback(result.message(), !result.accepted());
		rebuildWidgets();
	}

	static ScenarioLaunchRegistry.Result launchSafely(ScenarioSetupState state) {
		return launchSafely(state, "");
	}

	static ScenarioLaunchRegistry.Result launchSafely(ScenarioSetupState state, String confirmationToken) {
		try {
			return ScenarioLaunchRegistry.launch(state.launchPlan(confirmationToken));
		} catch (IllegalArgumentException | IllegalStateException exception) {
			String message = exception.getMessage();
			return new ScenarioLaunchRegistry.Result(
					false,
					message == null || message.isBlank() ? "Arena setup is no longer valid" : message
			);
		}
	}

	private void applyAndRebuild(Runnable operation) {
		try {
			operation.run();
			refreshDraftRoster();
			setFeedback("Agent " + (state.selectedIndex() + 1) + " of " + state.roster().size()
					+ " updated, " + state.displayNameAt(state.selectedIndex()), false);
		} catch (IllegalArgumentException | IllegalStateException exception) {
			setFeedback(exception.getMessage(), true);
		}
		rebuildWidgets();
	}

	private void setPlacementMode(ScenarioPlacementMode mode) {
		state.setPlacementMode(mode);
		setFeedback(mode.description(), false);
	}

	private ScenarioAgentConfig selectedConfig() {
		return state.roster().get(state.selectedIndex());
	}

	private void selectScenario(ScenarioPreset preset) {
		state.selectScenario(preset);
		refreshDraftRoster();
	}

	private void refreshDraftRoster() {
		List<AgentRosterEntry> candidateEntries = state.rosterEntries();
		Map<String, AgentVisualIdentity.Resolved> candidateVisuals = new LinkedHashMap<>();
		for (int index = 0; index < candidateEntries.size(); index++) {
			candidateVisuals.put(candidateEntries.get(index).id(), state.previewVisualIdentityAt(index));
		}
		List<AgentRosterEntry> publishedEntries = List.copyOf(candidateEntries);
		Map<String, AgentVisualIdentity.Resolved> publishedVisuals = Map.copyOf(candidateVisuals);
		rosterView.reconcile(publishedEntries);
		rosterView.setFilter(rosterFilter);
		restoreRefreshFocus(state, rosterView, publishedEntries);
		rosterEntries = publishedEntries;
		rosterVisuals = publishedVisuals;
	}

	static void restoreRefreshFocus(
			ScenarioSetupState state,
			AgentRosterViewState rosterView,
			List<AgentRosterEntry> entries
	) {
		String preferredFocus = state.slotIdAt(state.selectedIndex());
		entries.stream().filter(entry -> entry.id().equals(preferredFocus)).findFirst().orElseThrow();
		rosterView.focus(preferredFocus);
	}

	private void focusSlot(String id) {
		state.selectSlot(id);
		rosterView.focus(id);
		queuedInspectorRebuild = true;
	}

	private boolean filtersVisible() {
		return rosterEntries.size() >= 9;
	}

	private int filteredCount() {
		return (int) rosterEntries.stream().filter(rosterFilter::matches).count();
	}

	private List<String> providerOptions() {
		List<String> providers = new ArrayList<>();
		providers.add("");
		for (AgentRosterEntry entry : rosterEntries) {
			if (providers.stream().noneMatch(value -> value.equalsIgnoreCase(entry.provider()))) {
				providers.add(entry.provider());
			}
		}
		return List.copyOf(providers);
	}

	private void applyRosterFilter(AgentRosterFilter filter) {
		AgentRosterFilter next = Objects.requireNonNull(filter, "roster filter must not be null");
		boolean changed = !rosterFilter.equals(next);
		rosterFilter = next;
		rosterView.setFilter(next);
		if (changed) rosterView.setPage(1, 1);
		boolean focusVisible = rosterEntries.stream()
				.anyMatch(entry -> entry.id().equals(rosterView.focusedId()) && next.matches(entry));
		if (!focusVisible) {
			rosterEntries.stream().filter(next::matches).findFirst().ifPresent(entry -> {
				rosterView.focus(entry.id());
				state.selectSlot(entry.id());
			});
		}
		rebuildWidgets();
	}

	private void resetRosterFilters() {
		rosterFilter = AgentRosterFilter.all();
		rosterView.setFilter(rosterFilter);
		rosterView.setPage(1, 1);
	}

	private ConsoleButton consoleButton(
			String label, int x, int y, int width, int height, boolean selected, Runnable action
	) {
		return consoleButton(label, x, y, width, height, selected, GOLD, action);
	}

	private ConsoleEditBox consoleEditBox(int x, int y, int width, String identity) {
		return new ConsoleEditBox(font, x, y, width, ROW_HEIGHT,
				Component.translatable("screen.arenaagents.name"),
				Component.translatable("screen.arenaagents.name_optional"), identity);
	}

	private ConsoleButton consoleButton(
			Component label, int x, int y, int width, int height, boolean selected, Runnable action
	) {
		return new ConsoleButton(font, x, y, width, height, label, selected, GOLD, action);
	}

	private ConsoleButton consoleButton(
			String label, int x, int y, int width, int height, boolean selected, int accent, Runnable action
	) {
		return new ConsoleButton(font, x, y, width, height, Component.literal(label), selected, accent, action);
	}

	private void setFeedback(String message, boolean error) {
		feedback = message == null ? "" : message;
		feedbackError = error;
	}

	private String fit(String value, int available) {
		if (font.width(value) <= available) return value;
		String suffix = "...";
		return font.plainSubstrByWidth(value, Math.max(1, available - font.width(suffix))) + suffix;
	}

	private ScenarioSetupLayout layout() {
		return ScenarioSetupLayout.calculate(width, height);
	}

	private static String capitalize(String value) {
		if (value == null || value.isBlank()) return "";
		return Character.toUpperCase(value.charAt(0)) + value.substring(1);
	}

	record RetainedPlanResolution(
			java.util.Optional<ScenarioSetupState> state,
			boolean discard
	) {
	}
}
