package dev.agaminggod.arenaagents.client.gui;

import dev.agaminggod.arenaagents.agent.AgentConstants;
import dev.agaminggod.arenaagents.agent.AgentGameMode;
import dev.agaminggod.arenaagents.agent.AgentModelNames;
import dev.agaminggod.arenaagents.agent.AgentVisualIdentity;
import dev.agaminggod.arenaagents.client.control.AgentControlClient;
import dev.agaminggod.arenaagents.client.gui.scenario.ScenarioSetupScreen;
import dev.agaminggod.arenaagents.client.gui.widget.ConsoleButton;
import dev.agaminggod.arenaagents.client.gui.widget.ConsoleCycleButton;
import dev.agaminggod.arenaagents.client.gui.widget.ConsoleEditBox;
import dev.agaminggod.arenaagents.client.presentation.ArenaHudPresentation;
import dev.agaminggod.arenaagents.client.presentation.ArenaSpectatorHud;
import dev.agaminggod.arenaagents.control.AgentControlActions;
import dev.agaminggod.arenaagents.control.AgentControlAgent;
import dev.agaminggod.arenaagents.control.AgentControlCatalog;
import dev.agaminggod.arenaagents.control.AgentControlCommandBuilder;
import dev.agaminggod.arenaagents.control.AgentControlGroup;
import dev.agaminggod.arenaagents.control.AgentControlGroupSelection;
import dev.agaminggod.arenaagents.control.AgentControlPresentation;
import dev.agaminggod.arenaagents.control.AgentControlSelectionState;
import dev.agaminggod.arenaagents.control.AgentControlSnapshot;
import dev.agaminggod.arenaagents.control.AgentRosterEntry;
import dev.agaminggod.arenaagents.control.AgentRosterFilter;
import dev.agaminggod.arenaagents.control.AgentRosterPage;
import dev.agaminggod.arenaagents.control.AgentRosterViewState;
import dev.agaminggod.arenaagents.scenario.presentation.ArenaSpectatorSnapshot;
import dev.agaminggod.arenaagents.scenario.presentation.ScenarioBuildProgress;
import dev.agaminggod.arenaagents.scenario.result.ScenarioPublicEvent;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.MultiLineEditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import org.lwjgl.glfw.GLFW;

/** Human-oriented command center. Detailed workflows live on separate pages. */
public final class AgentControlScreen extends Screen {
	private static final int PANEL = ConsoleTheme.PANEL;
	private static final int PANEL_EDGE = ConsoleTheme.BORDER;
	private static final int BACKDROP = ConsoleTheme.BACKDROP;
	private static final int NAV_SURFACE = ConsoleTheme.NAVIGATION;
	private static final int TRACK = ConsoleTheme.TRACK;
	private static final int SURFACE = ConsoleTheme.SURFACE;
	private static final int SURFACE_SELECTED = ConsoleTheme.SURFACE_SELECTED;
	private static final int TEXT = ConsoleTheme.TEXT;
	private static final int MUTED = ConsoleTheme.MUTED;
	private static final int ACCENT = ConsoleTheme.ACCENT;
	private static final int SUCCESS = ConsoleTheme.SUCCESS;
	private static final int ERROR = ConsoleTheme.ERROR;
	private static final int ROW_HEIGHT = AgentControlLayout.CONTROL_HEIGHT;
	private static final int GAP = 6;

	private AgentControlSnapshot snapshot;
	private final Screen parent;
	private final AgentControlSelectionState managementSelection = new AgentControlSelectionState();
	private final AgentRosterViewState rosterState = new AgentRosterViewState();
	private AgentRosterFilter rosterFilter = AgentRosterFilter.all();
	private List<AgentRosterEntry> rosterEntries = List.of();
	private Map<String, AgentVisualIdentity.Resolved> rosterVisuals = Map.of();
	private AgentRosterGrid rosterGrid;
	private AgentRosterPage visibleRosterPage;
	private String provider;
	private String model;
	private String reasoning;
	private String serviceTier = "priority";
	private AgentGameMode gameMode = AgentGameMode.SURVIVAL;
	private String name = "";
	private String prompt = "";
	private String savedGroupName = "";
	private String selectedSavedGroupName = "";
	private String feedback = "";
	private boolean feedbackError;
	private final SingleSubmissionGate summonSubmission = new SingleSubmissionGate();
	private int liveScroll;
	private int liveFeedScroll;
	private boolean compactGroupComposer;
	private ConsoleMutationState mutationState = ConsoleMutationState.initial(0L);
	private Page page = Page.OVERVIEW;
	private ConsoleEditBox nameInput;
	private ConsoleEditBox groupNameInput;
	private MultiLineEditBox promptInput;

	public AgentControlScreen() {
		this(null);
	}

	public AgentControlScreen(Screen parent) {
		this(parent, Page.OVERVIEW);
	}

	@Override
	public boolean isPauseScreen() {
		return false;
	}

	private AgentControlScreen(Screen parent, Page initialPage) {
		super(Minecraft.getInstance(), ConsoleFont.create(Minecraft.getInstance()),
				Component.translatable("screen.arenaagents.controls.title"));
		this.parent = parent;
		this.page = initialPage;
		AgentControlClient.Preferences preferences = AgentControlClient.preferences();
		CreateSelection normalized = normalizeCreateSelection(
				preferences.provider(), preferences.model(), preferences.reasoning(), preferences.serviceTier());
		provider = normalized.provider();
		model = normalized.model();
		reasoning = normalized.reasoning();
		serviceTier = normalized.serviceTier();
		AgentControlClient.snapshot().ifPresent(this::replaceSnapshot);
	}

	public static AgentControlScreen live(Screen parent) {
		return new AgentControlScreen(parent, Page.LIVE);
	}

	public static AgentControlScreen group(Screen parent) {
		return new AgentControlScreen(parent, Page.GROUP);
	}

	public void acceptSnapshot(AgentControlSnapshot nextSnapshot) {
		acceptSnapshot(nextSnapshot, 0L, true);
	}

	public void acceptSnapshot(
			AgentControlSnapshot nextSnapshot,
			long acknowledgedMutationId,
			boolean contentChanged
	) {
		AgentControlSnapshot checkedSnapshot = Objects.requireNonNull(nextSnapshot, "nextSnapshot must not be null");
		if (!mutationState.accepts(checkedSnapshot.generatedAtEpochMs())) return;
		boolean mutationWasPending = mutationState.mutationPending();
		mutationState = mutationState.accept(checkedSnapshot.generatedAtEpochMs(), acknowledgedMutationId);
		if (!contentChanged) {
			snapshot = checkedSnapshot;
			if (mutationWasPending && !mutationState.mutationPending()) {
				if (!feedbackError) feedback = "";
				if (minecraft != null && !textEditorFocused()) rebuildWidgets();
			}
			return;
		}
		AgentControlSnapshot previous = snapshot;
		normalizeCreateSelection();
		List<AgentRosterEntry> candidateEntries = rosterEntries(checkedSnapshot);
		Map<String, AgentVisualIdentity.Resolved> candidateVisuals = rosterVisuals(checkedSnapshot);
		managementSelection.reconcile(checkedSnapshot.agents());
		rosterState.reconcile(candidateEntries);
		snapshot = checkedSnapshot;
		rosterEntries = candidateEntries;
		rosterVisuals = candidateVisuals;
		String resolvedGroup = AgentControlGroupSelection.resolve(selectedSavedGroupName, snapshot.groups());
		if (!resolvedGroup.equals(selectedSavedGroupName)) selectSavedGroup(resolvedGroup);
		if (previous != null && snapshot.generatedAtEpochMs() > previous.generatedAtEpochMs() && !feedbackError) {
			feedback = "";
		}
		if (!AgentControlLayout.rosterFiltersVisible(snapshot.agents().size())) {
			setRosterFilterWithoutRebuild(AgentRosterFilter.all());
		} else {
			normalizeRosterFilterOptions();
		}
		rosterState.focus(managementSelection.selectedAgentId());
		if (page == Page.GROUP && snapshot.agents().size() < 2) {
			page = Page.OVERVIEW;
			compactGroupComposer = false;
		}
		if (minecraft != null && !textEditorFocused()) rebuildWidgets();
	}

	private boolean textEditorFocused() {
		return getFocused() instanceof ConsoleEditBox || getFocused() instanceof MultiLineEditBox;
	}

	private void replaceSnapshot(AgentControlSnapshot nextSnapshot) {
		List<AgentRosterEntry> candidateEntries = rosterEntries(nextSnapshot);
		Map<String, AgentVisualIdentity.Resolved> candidateVisuals = rosterVisuals(nextSnapshot);
		managementSelection.reconcile(nextSnapshot.agents());
		rosterState.reconcile(candidateEntries);
		rosterState.focus(managementSelection.selectedAgentId());
		snapshot = nextSnapshot;
		mutationState = ConsoleMutationState.initial(nextSnapshot.generatedAtEpochMs());
		rosterEntries = candidateEntries;
		rosterVisuals = candidateVisuals;
		selectSavedGroup(AgentControlGroupSelection.resolve("", snapshot.groups()));
	}

	@Override
	protected void init() {
		nameInput = null;
		groupNameInput = null;
		promptInput = null;
		rosterGrid = null;
		visibleRosterPage = null;
		addNavigation();
		switch (page) {
			case OVERVIEW -> initOverview();
			case GROUP -> initGroup();
			case LIVE -> initLive();
			case CREATE -> initCreate();
			case TASK -> initTask();
			case MANAGE -> initManage();
			case REMOVE_CONFIRM -> initRemoveConfirm();
		}
	}

	@Override
	public Component getNarrationMessage() {
		MutableComponent narration = getTitle().copy();
		if (snapshot == null) {
			appendNarration(narration, Component.literal(AgentControlClient.snapshotError()
					.orElse("Loading agent roster")));
		} else if (!snapshot.canControl()) {
			appendNarration(narration, Component.literal("View only. Operator permission is required to make changes"));
		}
		switch (page) {
			case OVERVIEW -> appendAgentNarration(narration, selectedAgent(), true);
			case GROUP -> appendGroupNarration(narration);
			case LIVE -> appendLiveNarration(narration);
			case CREATE -> appendNarration(narration,
					Component.literal("Create an agent, " + capitalize(provider) + ", "
							+ AgentControlCatalog.displayName(provider, model)));
			case TASK, MANAGE, REMOVE_CONFIRM -> appendAgentNarration(narration, selectedAgent(), false);
		}
		if (!feedback.isBlank()) appendNarration(narration, Component.literal(feedback));
		return narration;
	}

	private void appendAgentNarration(MutableComponent narration, AgentControlAgent agent, boolean detailed) {
		if (agent == null) return;
		String identity = detailed
				? agent.displayName() + ", " + AgentControlPresentation.profileLabel(agent)
				: agent.displayName();
		appendNarration(narration, Component.literal(identity + ", "
				+ AgentControlPresentation.stateLabel(agent.state())));
		if (!detailed) return;
		appendNarration(narration, Component.literal(agent.currentGoal().isBlank()
				? "No current task" : "Task: " + agent.currentGoal()));
		if (!agent.lastError().isBlank()) {
			appendNarration(narration, Component.literal("Needs attention: " + agent.lastError()));
		}
	}

	private void appendGroupNarration(MutableComponent narration) {
		int selected = rosterState.selectedIds().size();
		int hidden = visibleRosterPage == null ? 0 : visibleRosterPage.hiddenSelectedCount();
		appendNarration(narration, hidden > 0
				? Component.translatable("screen.arenaagents.roster.scope.hidden", selected, hidden)
				: Component.translatable("screen.arenaagents.roster.scope.selected", selected));
		appendRosterPageNarration(narration, visibleRosterPage);
	}

	private void appendLiveNarration(MutableComponent narration) {
		ArenaSpectatorSnapshot arena = AgentControlClient.spectatorState().snapshot().orElse(null);
		ScenarioBuildProgress build = AgentControlClient.buildProgressState().progress().orElse(null);
		if (AgentControlClient.buildProgressState().shouldDisplayBeforeMatch(arena != null) || arena == null) {
			appendBuildNarration(narration, build);
			return;
		}
		appendNarration(narration, Component.literal(arena.scenarioTitle() + ", " + arena.phaseTitle()));
		if (!arena.standings().isEmpty()) {
			int index = Math.clamp(liveScroll, 0, arena.standings().size() - 1);
			ArenaSpectatorSnapshot.Standing standing = arena.standings().get(index);
			appendNarration(narration, Component.literal("Rank " + standing.rank() + ", "
					+ standing.displayName() + ", score " + ArenaSpectatorHud.scoreText(standing.score())
					+ ", health " + standing.healthPercent() + "%, "
					+ ArenaHudPresentation.statusLabel(standing.status())));
		}
		if (!arena.feed().isEmpty()) {
			int feedIndex = Math.clamp(arena.feed().size() - 1 - liveFeedScroll, 0, arena.feed().size() - 1);
			appendNarration(narration, Component.literal("Match activity: " + arena.feed().get(feedIndex).message()));
		}
	}

	private static void appendBuildNarration(MutableComponent narration, ScenarioBuildProgress build) {
		if (build == null) {
			appendNarration(narration, Component.literal("No active match or arena build"));
			return;
		}
		String status = switch (build.status()) {
			case BUILDING -> "Building arena";
			case CONFIRMATION_REQUIRED -> "Arena confirmation required";
			case READY -> "Arena ready";
			case FAILED -> "Build failed";
			case CANCELLED -> "Arena build cancelled";
		};
		appendNarration(narration, Component.literal(status + ", " + build.scenarioTitle() + ", "
				+ build.percent() + "%, " + build.humanPhase() + ", "
				+ build.completed() + " of " + build.total()));
		if (build.status() != ScenarioBuildProgress.Status.BUILDING
				&& build.status() != ScenarioBuildProgress.Status.READY && !build.detail().isBlank()) {
			appendNarration(narration, Component.literal(build.detail()));
		}
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
		AgentControlLayout layout = layout();
		graphics.fill(0, 0, width, height, BACKDROP);
		graphics.fill(layout.panelLeft() - 1, layout.panelTop() - 1,
				layout.panelRight() + 1, layout.panelBottom() + 1, PANEL_EDGE);
		graphics.fill(layout.panelLeft(), layout.panelTop(), layout.panelRight(), layout.panelBottom(), PANEL);
		renderShell(graphics);
		renderHeader(graphics);
		renderInputSurfaces(graphics);
		switch (page) {
			case OVERVIEW -> renderOverview(graphics);
			case GROUP -> renderGroup(graphics);
			case LIVE -> renderLive(graphics);
			case CREATE -> renderCreate(graphics);
			case TASK -> renderTask(graphics);
			case MANAGE -> renderManage(graphics);
			case REMOVE_CONFIRM -> renderRemoveConfirm(graphics);
		}
		if (rosterGrid != null) rosterGrid.extractRenderState(graphics);
		super.extractRenderState(graphics, mouseX, mouseY, partialTick);
		renderStatus(graphics);
	}

	@Override
	public boolean mouseScrolled(double mouseX, double mouseY, double horizontalAmount, double verticalAmount) {
		if ((page == Page.OVERVIEW || page == Page.GROUP) && rosterGrid != null
				&& rosterGrid.mouseScrolled(mouseX, mouseY, horizontalAmount, verticalAmount)) {
			rebuildWidgets();
			return true;
		}
		if (page == Page.LIVE) {
			ArenaSpectatorSnapshot arena = AgentControlClient.spectatorState().snapshot().orElse(null);
			if (arena != null) {
				AgentControlLayout shell = layout();
				LiveArenaLayout live = LiveArenaLayout.calculate(
						shell.contentWidth(), shell.contentHeight(), arena.standings().size());
				int feedTop = liveFeedTop(shell, live, Math.min(live.visibleCount(), arena.standings().size()));
				if (mouseY >= feedTop && feedTop < shell.contentBottom() - 14 && !arena.feed().isEmpty()) {
					LiveFeedViewport viewport = LiveFeedViewport.calculate(
							arena.feed().size(), shell.contentBottom() - feedTop - 14, liveFeedScroll);
					int nextFeed = viewport.scrollBy(verticalAmount > 0.0D ? -1 : 1);
					if (nextFeed != liveFeedScroll) {
						liveFeedScroll = nextFeed;
						return true;
					}
				}
				int next = Math.clamp(liveScroll + (verticalAmount > 0.0D ? -live.columns() : live.columns()),
						0, live.maximumScroll());
				if (next != liveScroll) {
					liveScroll = next;
					return true;
				}
			}
		}
		return super.mouseScrolled(mouseX, mouseY, horizontalAmount, verticalAmount);
	}

	@Override
	public boolean keyPressed(KeyEvent event) {
		if (page == Page.LIVE && handleLiveKeyboardScroll(event.key())) return true;
		if (event.key() == GLFW.GLFW_KEY_ESCAPE) {
			if (page == Page.OVERVIEW || page == Page.LIVE) return super.keyPressed(event);
			navigateBack();
			return true;
		}
		return super.keyPressed(event);
	}

	private boolean handleLiveKeyboardScroll(int key) {
		ArenaSpectatorSnapshot arena = AgentControlClient.spectatorState().snapshot().orElse(null);
		if (arena == null) return false;
		AgentControlLayout shell = layout();
		LiveArenaLayout live = LiveArenaLayout.calculate(
				shell.contentWidth(), shell.contentHeight(), arena.standings().size());
		if (key == GLFW.GLFW_KEY_UP || key == GLFW.GLFW_KEY_DOWN) {
			int direction = key == GLFW.GLFW_KEY_UP ? -live.columns() : live.columns();
			int next = Math.clamp(liveScroll + direction, 0, live.maximumScroll());
			if (next == liveScroll) return false;
			liveScroll = next;
			return true;
		}
		int feedTop = liveFeedTop(shell, live, Math.min(live.visibleCount(), arena.standings().size()));
		LiveFeedViewport feed = LiveFeedViewport.calculate(
				arena.feed().size(), Math.max(20, shell.contentBottom() - feedTop - 14), liveFeedScroll);
		int nextFeed = switch (key) {
			case GLFW.GLFW_KEY_PAGE_UP -> feed.pageUp();
			case GLFW.GLFW_KEY_PAGE_DOWN -> feed.pageDown();
			case GLFW.GLFW_KEY_HOME -> 0;
			case GLFW.GLFW_KEY_END -> feed.maximumScroll();
			default -> liveFeedScroll;
		};
		if (nextFeed == liveFeedScroll) return false;
		liveFeedScroll = nextFeed;
		return true;
	}

	private void navigateBack() {
		if (page == Page.REMOVE_CONFIRM) {
			show(Page.MANAGE);
			return;
		}
		if (page == Page.GROUP && compactGroupComposer) {
			compactGroupComposer = false;
			rebuildWidgets();
			return;
		}
		show(Page.OVERVIEW);
	}

	private void addNavigation() {
		AgentControlLayout layout = layout();
		boolean groupAvailable = snapshot != null && snapshot.groupAvailable();
		boolean agentWorkflow = page == Page.OVERVIEW || page == Page.CREATE || page == Page.TASK
				|| page == Page.MANAGE || page == Page.REMOVE_CONFIRM;
		if (layout.sideNavigation()) {
			int left = layout.panelLeft() + 10;
			int navigationWidth = layout.contentLeft() - layout.panelLeft() - 24;
			int y = layout.navigationTop();
			addRenderableWidget(consoleButton(Component.translatable("screen.arenaagents.navigation.agents"),
					left, y, navigationWidth, ROW_HEIGHT, agentWorkflow,
					() -> show(Page.OVERVIEW)));
			if (groupAvailable) {
				addRenderableWidget(consoleButton(Component.translatable("screen.arenaagents.navigation.group"),
						left, y + 31, navigationWidth, ROW_HEIGHT, page == Page.GROUP,
						() -> show(Page.GROUP)));
			}
			addRenderableWidget(consoleButton(Component.translatable("screen.arenaagents.navigation.live"),
					left, y + (groupAvailable ? 62 : 31), navigationWidth, ROW_HEIGHT, page == Page.LIVE,
					() -> show(Page.LIVE)));
			addRenderableWidget(consoleButton(Component.translatable("screen.arenaagents.navigation.build"),
					left, y + (groupAvailable ? 93 : 62), navigationWidth, ROW_HEIGHT, false,
					this::openArenaSetup));
			return;
		}
		List<String> labels = groupAvailable
				? List.of("Agents", "Group", "Live", "Build") : List.of("Agents", "Live", "Build");
		List<Boolean> selected = groupAvailable
				? List.of(agentWorkflow, page == Page.GROUP, page == Page.LIVE, false)
				: List.of(agentWorkflow, page == Page.LIVE, false);
		List<Runnable> actions = groupAvailable
				? List.of(() -> show(Page.OVERVIEW), () -> show(Page.GROUP), () -> show(Page.LIVE), this::openArenaSetup)
				: List.of(() -> show(Page.OVERVIEW), () -> show(Page.LIVE), this::openArenaSetup);
		int available = layout.contentWidth() - GAP * (labels.size() - 1);
		int buttonWidth = available / labels.size();
		for (int index = 0; index < labels.size(); index++) {
			int x = layout.contentLeft() + index * (buttonWidth + GAP);
			int actualWidth = index == labels.size() - 1 ? layout.contentRight() - x : buttonWidth;
			addRenderableWidget(consoleButton(labels.get(index), x, layout.navigationTop(), actualWidth, ROW_HEIGHT,
					selected.get(index), actions.get(index)));
		}
	}

	private void openArenaSetup() {
		if (minecraft == null) return;
		if (parent instanceof ScenarioSetupScreen setup) minecraft.setScreen(setup);
		else minecraft.setScreen(new ScenarioSetupScreen());
	}

	@Override
	public void onClose() {
		if (minecraft != null && parent != null) minecraft.setScreen(parent);
		else super.onClose();
	}

	private void initOverview() {
		AgentControlLayout layout = layout();
		boolean filtersVisible = filtersVisible();
		if (layout.splitWorkspace()) {
			addRosterFilters(layout.canvasLeft(), layout.contentTop(), layout.canvasWidth());
			addRosterGrid(layout.rosterBounds(filtersVisible, false), AgentRosterGrid.Mode.FOCUS_ONLY);
			addOverviewActions(layout.contextLeft(), layout.contentTop(), layout.contextWidth());
			addOverviewFooter();
		} else if (layout.sideNavigation()) {
			addRosterFilters(layout.canvasLeft(), layout.contentTop(), layout.canvasWidth());
			addRosterGrid(layout.rosterBounds(filtersVisible, false), AgentRosterGrid.Mode.FOCUS_ONLY);
			addCompactOverviewActions(layout.contentLeft(), layout.footerY(), layout.contentWidth());
		} else {
			addRosterFilters(layout.canvasLeft(), layout.contentTop(), layout.canvasWidth());
			addRosterGrid(layout.rosterBounds(filtersVisible, true), AgentRosterGrid.Mode.FOCUS_ONLY);
			addCompactOverviewActions(layout.contentLeft(), layout.contentBottom() - ROW_HEIGHT, layout.contentWidth());
			addOverviewFooter();
		}
	}

	private void initGroup() {
		if (snapshot != null && snapshot.agents().size() < 2 && snapshot.groups().isEmpty()) {
			page = Page.OVERVIEW;
			initOverview();
			return;
		}
		AgentControlLayout layout = layout();
		int bodyTop = addSavedGroupToolbar(layout.contentLeft(), layout.contentTop(), layout.contentWidth()) + GAP;
		boolean columns = layout.groupColumns();
		if (columns) {
			int listX = layout.canvasLeft();
			int listWidth = layout.splitWorkspace()
					? layout.canvasWidth()
					: (layout.contentWidth() - 12) * 45 / 100;
			int controlX = layout.splitWorkspace()
					? layout.contextLeft()
					: listX + listWidth + 12;
			int controlWidth = layout.contentRight() - controlX;
			addRosterFilters(listX, bodyTop, listWidth);
			int gridTop = bodyTop + (filtersVisible() ? 30 : 0);
			addRosterGrid(new AgentControlLayout.Bounds(
					listX, gridTop, listX + listWidth, layout.contentBottom()),
					AgentRosterGrid.Mode.MULTI_SELECT);
			promptInput = multiLineInput(controlX, bodyTop, controlWidth, 66,
					"Group task", "Describe one shared outcome for the selected agents");
			addRenderableWidget(promptInput);
			addVerticalGroupActions(controlX, bodyTop + 74, controlWidth);
		} else {
			if (compactGroupComposer) {
				AgentControlLayout.Bounds composer = new AgentControlLayout.Bounds(
						layout.contentLeft(), bodyTop, layout.contentRight(), layout.workspaceActionBounds().top());
				promptInput = multiLineInput(composer.left(), composer.top(), composer.width(), composer.height(),
						"Group task", "Tell the selected group what to do");
				addRenderableWidget(promptInput);
				addCompactComposerActions(layout.workspaceActionBounds());
			} else {
				addRosterFilters(layout.contentLeft(), bodyTop, layout.contentWidth());
				int gridTop = bodyTop + (filtersVisible() ? 30 : 0);
				addRosterGrid(new AgentControlLayout.Bounds(
						layout.contentLeft(), gridTop, layout.contentRight(), layout.workspaceActionBounds().top() - GAP),
						AgentRosterGrid.Mode.MULTI_SELECT);
				addCompactSelectionActions(layout.workspaceActionBounds());
			}
		}
		addOverviewFooter();
	}

	private int addSavedGroupToolbar(int x, int y, int width) {
		List<AgentControlGroup> groups = snapshot == null ? List.of() : snapshot.groups();
		int gapCount = groups.isEmpty() ? 3 : 4;
		int selectorWidth = groups.isEmpty() ? 0 : Math.max(56, width * 20 / 100);
		int nameWidth = Math.max(68, width * 24 / 100);
		int remaining = Math.max(90, width - selectorWidth - nameWidth - GAP * gapCount);
		int buttonWidth = remaining / 3;
		int cursor = x;
		if (!groups.isEmpty()) {
			AgentControlGroup selected = selectedSavedGroup().orElse(groups.getFirst());
			addRenderableWidget(new ConsoleCycleButton<>(
					font, cursor, y, selectorWidth, ROW_HEIGHT, Component.literal("Saved"),
					groups, selected, group -> Component.literal(group.name()), group -> {
						selectSavedGroup(group.name());
						rebuildWidgets();
					}));
			cursor += selectorWidth + GAP;
		}
		groupNameInput = consoleEditBox(
				cursor, y, nameWidth, ROW_HEIGHT, "saved-group-name", Component.literal("Group name"));
		groupNameInput.setMaxLength(AgentControlGroup.MAX_NAME_CODE_POINTS);
		groupNameInput.setValue(savedGroupName);
		groupNameInput.setResponder(value -> savedGroupName = value);
		addRenderableWidget(groupNameInput);
		cursor += nameWidth + GAP;

		ConsoleButton save = primaryButton("Save", cursor, y, buttonWidth, ROW_HEIGHT, this::saveSelectedGroup);
		save.active = canControl() && !rosterState.selectedIds().isEmpty();
		addRenderableWidget(save);
		cursor += buttonWidth + GAP;
		ConsoleButton spawn = consoleButton("Spawn", cursor, y, buttonWidth, ROW_HEIGHT, false,
				this::spawnSelectedGroup);
		spawn.active = canControl() && selectedSavedGroup().isPresent();
		addRenderableWidget(spawn);
		cursor += buttonWidth + GAP;
		ConsoleButton delete = dangerButton("Delete", cursor, y, x + width - cursor, ROW_HEIGHT,
				this::deleteSelectedGroup);
		delete.active = canControl() && selectedSavedGroup().isPresent();
		addRenderableWidget(delete);
		return y + ROW_HEIGHT;
	}

	private void selectSavedGroup(String name) {
		selectedSavedGroupName = Objects.requireNonNullElse(name, "");
		savedGroupName = selectedSavedGroupName;
		if (snapshot == null || selectedSavedGroupName.isBlank()) return;
		Set<String> presentIds = snapshot.agents().stream()
				.map(AgentControlAgent::agentId)
				.collect(java.util.stream.Collectors.toSet());
		rosterState.clearSelection();
		for (String agentId : AgentControlGroupSelection.members(selectedSavedGroupName, snapshot.groups())) {
			if (presentIds.contains(agentId)) rosterState.toggle(agentId);
		}
	}

	private Optional<AgentControlGroup> selectedSavedGroup() {
		if (snapshot == null || selectedSavedGroupName.isBlank()) return Optional.empty();
		return snapshot.groups().stream()
				.filter(group -> group.name().equalsIgnoreCase(selectedSavedGroupName))
				.findFirst();
	}

	private void saveSelectedGroup() {
		try {
			String normalizedName = AgentControlGroup.normalizeName(savedGroupName);
			if (rosterState.selectedIds().isEmpty()) throw new IllegalArgumentException("Select at least one agent");
			selectedSavedGroupName = normalizedName;
			savedGroupName = normalizedName;
			send(AgentControlCommandBuilder.saveGroup(normalizedName, List.copyOf(rosterState.selectedIds())),
					"Saving group " + normalizedName + "...");
		} catch (IllegalArgumentException exception) {
			setFeedback(exception.getMessage(), true);
		}
	}

	private void spawnSelectedGroup() {
		selectedSavedGroup().ifPresentOrElse(
				group -> send(AgentControlCommandBuilder.spawnGroup(group.name()),
						"Restoring group " + group.name() + "..."),
				() -> setFeedback("Choose a saved group first", true));
	}

	private void deleteSelectedGroup() {
		selectedSavedGroup().ifPresentOrElse(group -> {
			send(AgentControlCommandBuilder.deleteGroup(group.name()), "Deleting saved group " + group.name() + "...");
			selectedSavedGroupName = "";
			savedGroupName = "";
		}, () -> setFeedback("Choose a saved group first", true));
	}

	private void addVerticalGroupActions(int x, int y, int width) {
		List<AgentControlAgent> selected = selectedGroupAgents();
		for (int index = 0; index < 3; index++) {
			String operation = List.of("start", "queue", "steer").get(index);
			String label = List.of("Start now", "Add to queue", "Adjust task").get(index);
			ConsoleButton action = index == 0
					? primaryButton(label, x, y + index * 31, width, ROW_HEIGHT, () -> submitGroupPrompt(operation))
					: consoleButton(label, x, y + index * 31, width, ROW_HEIGHT, false,
							() -> submitGroupPrompt(operation));
			action.active = canUseAutomation() && !selected.isEmpty();
			addRenderableWidget(action);
		}
		addRenderableWidget(consoleButton("Clear selection", x, y + 93, width, ROW_HEIGHT, false,
				this::clearGroupSelection));
	}

	private void addHorizontalGroupActions(int x, int y, int width) {
		List<AgentControlAgent> selected = selectedGroupAgents();
		String[] operations = {"start", "queue", "steer"};
		String[] labels = {"Start", "Queue", "Adjust", "Clear"};
		int buttonWidth = (width - GAP * 3) / 4;
		for (int index = 0; index < labels.length; index++) {
			int buttonX = x + index * (buttonWidth + GAP);
			int actualWidth = index == labels.length - 1 ? x + width - buttonX : buttonWidth;
			if (index == labels.length - 1) {
				addRenderableWidget(consoleButton(labels[index], buttonX, y, actualWidth, ROW_HEIGHT, false,
						this::clearGroupSelection));
				continue;
			}
			String operation = operations[index];
			ConsoleButton action = index == 0
					? primaryButton(labels[index], buttonX, y, actualWidth, ROW_HEIGHT,
							() -> submitGroupPrompt(operation))
					: consoleButton(labels[index], buttonX, y, actualWidth, ROW_HEIGHT, false,
							() -> submitGroupPrompt(operation));
			action.active = canUseAutomation() && !selected.isEmpty();
			addRenderableWidget(action);
		}
	}

	private void clearGroupSelection() {
		rosterState.clearSelection();
		rebuildWidgets();
	}

	private void addRosterFilters(int x, int y, int width) {
		if (!filtersVisible()) return;
		int controlWidth = (width - GAP * 2) / 3;
		ConsoleEditBox query = new ConsoleEditBox(font, x, y, controlWidth, ROW_HEIGHT,
				Component.translatable("screen.arenaagents.roster.search"),
				Component.translatable("screen.arenaagents.roster.search_hint"), "roster-query");
		query.setMaxLength(80);
		query.setValue(rosterFilter.query());
		query.setResponder(value -> applyRosterFilter(
				new AgentRosterFilter(value, rosterFilter.provider(), rosterFilter.state())));
		addRenderableWidget(query);

		List<String> providers = providerOptions();
		addRenderableWidget(new ConsoleCycleButton<>(
				font, x + controlWidth + GAP, y, controlWidth, ROW_HEIGHT,
				Component.translatable("screen.arenaagents.roster.filter.provider"),
				providers, rosterFilter.provider(), value -> value.isBlank()
						? Component.translatable("screen.arenaagents.roster.filter.all_providers")
						: Component.literal(capitalize(value)),
				value -> applyRosterFilter(new AgentRosterFilter(rosterFilter.query(), value, rosterFilter.state()))));
		List<String> states = stateOptions();
		int stateX = x + (controlWidth + GAP) * 2;
		addRenderableWidget(new ConsoleCycleButton<>(
				font, stateX, y, x + width - stateX, ROW_HEIGHT,
				Component.translatable("screen.arenaagents.roster.filter.status"),
				states, rosterFilter.state(), value -> value.isBlank()
						? Component.translatable("screen.arenaagents.roster.filter.all_statuses")
						: Component.literal(value),
				value -> applyRosterFilter(new AgentRosterFilter(rosterFilter.query(), rosterFilter.provider(), value))));
	}

	private void addRosterGrid(AgentControlLayout.Bounds region, AgentRosterGrid.Mode mode) {
		if (snapshot == null || region.width() < 24 || region.height() < 24) return;
		AgentRosterPage probe = rosterState.page(1);
		AgentRosterGridLayout gridLayout = AgentRosterGridLayout.calculate(
				region.left(), region.top(), region.right(), region.bottom(), probe.totalFiltered());
		AgentRosterPage rosterPage = rosterState.page(Math.max(1, gridLayout.pageSize()));
		visibleRosterPage = rosterPage;
		Set<String> selected = mode == AgentRosterGrid.Mode.MULTI_SELECT
				? rosterState.selectedIds() : Set.of();
		rosterGrid = new AgentRosterGrid(
				font,
				rosterPage,
				gridLayout,
				selected,
				mode,
				id -> id.equals(rosterState.focusedId()),
				id -> rosterVisuals.get(id),
				new AgentRosterGrid.Actions() {
					@Override
					public void focus(String id) {
						selectAgent(id);
						focusRosterWidget(id);
					}

					@Override
					public void open(String id) {
						selectAgent(id);
						if (mode == AgentRosterGrid.Mode.FOCUS_ONLY) show(Page.MANAGE);
					}

					@Override
					public void toggle(String id) {
						rosterState.toggle(id);
						rebuildWidgets();
					}

					@Override
					public void selectRangeTo(String id) {
						rosterState.selectRangeTo(id);
						rebuildWidgets();
					}

					@Override
					public void selectAll() {
						rosterState.selectAll();
						rebuildWidgets();
					}

					@Override
					public void changePage(int delta) {
						if (delta < 0) rosterState.previousPage(Math.max(1, gridLayout.pageSize()));
						else if (delta > 0) rosterState.nextPage(Math.max(1, gridLayout.pageSize()));
						rebuildWidgets();
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

	private void addCompactSelectionActions(AgentControlLayout.Bounds bounds) {
		int buttonWidth = (bounds.width() - GAP) / 2;
		ConsoleButton compose = primaryButton(
				"Compose", bounds.left(), bounds.top(), buttonWidth, bounds.height(), () -> {
					compactGroupComposer = true;
					rebuildWidgets();
				});
		compose.active = !rosterState.selectedIds().isEmpty();
		addRenderableWidget(compose);
		addRenderableWidget(consoleButton(
				"Clear", bounds.left() + buttonWidth + GAP, bounds.top(),
				bounds.width() - buttonWidth - GAP, bounds.height(), false, this::clearGroupSelection));
	}

	private void addCompactComposerActions(AgentControlLayout.Bounds bounds) {
		String[] operations = {"start", "queue", "steer"};
		String[] labels = {"Start", "Queue", "Adjust", "Back"};
		int buttonWidth = (bounds.width() - GAP * 3) / 4;
		List<AgentControlAgent> selected = selectedGroupAgents();
		for (int index = 0; index < labels.length; index++) {
			int buttonX = bounds.left() + index * (buttonWidth + GAP);
			int actualWidth = index == labels.length - 1 ? bounds.right() - buttonX : buttonWidth;
			if (index == labels.length - 1) {
				addRenderableWidget(consoleButton(labels[index], buttonX, bounds.top(), actualWidth, bounds.height(),
						false, () -> {
							compactGroupComposer = false;
							rebuildWidgets();
						}));
				continue;
			}
			String operation = operations[index];
			ConsoleButton action = index == 0
					? primaryButton(labels[index], buttonX, bounds.top(), actualWidth, bounds.height(),
							() -> submitGroupPrompt(operation))
					: consoleButton(labels[index], buttonX, bounds.top(), actualWidth, bounds.height(), false,
							() -> submitGroupPrompt(operation));
			action.active = canUseAutomation() && !selected.isEmpty();
			addRenderableWidget(action);
		}
	}

	private void addOverviewActions(int x, int y, int width) {
		String[] labels={"Clinic","Workshop","Advance","Cancel","Rewind","Inspect","Compare","Director"};
		String[] commands={"fork power clinic","fork power workshop","fork advance","fork cancel","fork rewind","fork inspect","fork compare"};
		int half=(width-GAP)/2;
		for(int i=0;i<labels.length;i++) {
			int index=i;
			ConsoleButton button=consoleButton(labels[i],x+(i%2)*(half+GAP),y+(i/2)*32,half,ROW_HEIGHT,false,
					()->{if(index==7)openDirector();else sendForkCommand(commands[index]);});
			button.active=canControl();addRenderableWidget(button);
		}
	}

	private void sendForkCommand(String command) {
		if(minecraft==null||minecraft.getConnection()==null||!serverCanControl()) return;
		if(command.equals("fork rewind")) dev.agaminggod.arenaagents.client.camera.CameraDirectorClient.stopPlaybackFromGui();
		// FORK owns its own receipts; do not wait on an unrelated Arena roster revision.
		minecraft.getConnection().sendCommand(command);
		minecraft.setScreen(null);
	}

	private void addCompactOverviewActions(int x, int y, int width) {
		int buttonWidth = (width - GAP * 3) / 4;
		String[] labels={"Advance","Cancel","Inspect","Compare"};
		String[] commands={"fork advance","fork cancel","fork inspect","fork compare"};
		for(int i=0;i<4;i++) {
			int index=i;
			ConsoleButton button=consoleButton(labels[i],x+(buttonWidth+GAP)*i,y,buttonWidth,ROW_HEIGHT,false,()->sendForkCommand(commands[index]));
			button.active=canControl();addRenderableWidget(button);
		}
	}

	private void openDirector() {
		if (minecraft != null) minecraft.setScreen(new SkitDirectorScreen(this));
	}

	private void initLive() {
		int y = layout().footerY();
		addRenderableWidget(consoleButton("Refresh agents", contentLeft(), y, 118, ROW_HEIGHT, false,
				AgentControlClient::requestSnapshot));
		addRenderableWidget(consoleButton("Close", contentRight() - 96, y, 96, ROW_HEIGHT, false, this::onClose));
	}

	private void initCreate() {
		normalizeCreateSelection();
		AgentControlLayout layout = layout();
		int x = layout.contentLeft();
		int width = layout.contentWidth();
		int half = (width - GAP) / 2;
		int y = layout.contentTop() + (layout.sideNavigation() ? 22 : 0);
		addRenderableWidget(new ConsoleCycleButton<>(font, x, y, half, ROW_HEIGHT, Component.literal("Provider"),
				AgentControlCatalog.providers(), provider, value -> Component.literal(capitalize(value)), value -> {
					provider = value;
					model = AgentControlCatalog.defaultModel(provider);
					reasoning = AgentControlCatalog.defaultReasoning(provider, model);
					serviceTier = AgentControlCatalog.defaultServiceTier(provider, model);
					rememberPreferences();
					rebuildWidgets();
				}));
		addRenderableWidget(new ConsoleCycleButton<>(font, x + half + GAP, y, half, ROW_HEIGHT,
				Component.literal("Model"), AgentControlCatalog.models(provider), model,
				value -> Component.literal(AgentControlCatalog.displayName(provider, value)), value -> {
					model = value;
					reasoning = AgentControlCatalog.defaultReasoning(provider, model);
					serviceTier = AgentControlCatalog.defaultServiceTier(provider, model);
					rememberPreferences();
					rebuildWidgets();
				}));
		y += 31;
		addRenderableWidget(new ConsoleCycleButton<>(font, x, y, half, ROW_HEIGHT,
				Component.literal("Thinking depth"), AgentControlCatalog.reasoningEfforts(provider, model), reasoning,
				value -> Component.literal(capitalize(value)), value -> {
					reasoning = value;
					rememberPreferences();
				}));
		if (AgentControlCatalog.hasSpeedMode(provider, model)) {
			addRenderableWidget(new ConsoleCycleButton<>(font, x + half + GAP, y, half, ROW_HEIGHT,
					Component.literal("Speed mode"), AgentControlCatalog.serviceTiers(provider, model), serviceTier,
					value -> Component.literal(AgentControlPresentation.speedLabel(value)),
					value -> {
						serviceTier = value;
						rememberPreferences();
					}));
		} else {
			ConsoleButton unavailableSpeed = consoleButton(Component.translatable("screen.arenaagents.speed_unavailable"), x + half + GAP, y,
					half, ROW_HEIGHT, false, () -> { });
			unavailableSpeed.active = false;
			addRenderableWidget(unavailableSpeed);
		}
		y += 31;
		addRenderableWidget(new ConsoleCycleButton<>(font, x, y, half, ROW_HEIGHT,
				Component.literal("Game mode"), List.of(AgentGameMode.values()), gameMode,
				value -> Component.literal(value.displayName()), value -> gameMode = value));
		nameInput = consoleEditBox(x + half + GAP, y, half, ROW_HEIGHT,
				"agent-name", Component.translatable("screen.arenaagents.name_optional"));
		nameInput.setMaxLength(AgentConstants.MAX_USER_NAME_LENGTH);
		nameInput.setValue(name);
		nameInput.setResponder(value -> name = value);
		addRenderableWidget(nameInput);
		addCreateFooter();
	}

	private void initTask() {
		AgentControlAgent agent = selectedAgent();
		if (agent == null) {
			show(Page.OVERVIEW);
			return;
		}
		AgentControlLayout layout = layout();
		int x = layout.contentLeft();
		int width = layout.contentWidth();
		int promptY = layout.contentTop() + (layout.sideNavigation() ? 28 : 0);
		int promptHeight = Math.max(40, layout.contentBottom() - promptY - ROW_HEIGHT - 7);
		promptInput = multiLineInput(x, promptY, width, promptHeight, "Agent task",
				"Describe a clear outcome, for example: Build a safe shelter before night");
		addRenderableWidget(promptInput);
		int buttonWidth = (width - GAP * 2) / 3;
		int actionY = layout.contentBottom() - ROW_HEIGHT;
		for (int index = 0; index < 3; index++) {
			String operation = List.of("start", "queue", "steer").get(index);
			String label = List.of("Start now", "Add to queue", "Adjust current task").get(index);
			ConsoleButton action = index == 0
					? primaryButton(label,
							x + index * (buttonWidth + GAP), actionY, buttonWidth, ROW_HEIGHT,
							() -> submitPrompt(operation))
					: consoleButton(label,
							x + index * (buttonWidth + GAP), actionY, buttonWidth, ROW_HEIGHT, false,
							() -> submitPrompt(operation));
			action.active = canUseAutomation() && AgentControlActions.supports(agent, operation);
			addRenderableWidget(action);
		}
		addBackFooter();
	}

	private void initRemoveConfirm() {
		AgentControlAgent agent = selectedAgent();
		if (agent == null) {
			show(Page.OVERVIEW);
			return;
		}
		AgentControlLayout layout = layout();
		int buttonWidth = Math.min(148, (layout.contentWidth() - GAP) / 2);
		int y = layout.footerY();
		addRenderableWidget(consoleButton("Keep agent", layout.contentLeft(), y, buttonWidth, ROW_HEIGHT,
				false, () -> show(Page.MANAGE)));
		ConsoleButton remove = dangerButton("Remove permanently", layout.contentRight() - buttonWidth, y,
				buttonWidth, ROW_HEIGHT, this::submitConfirmedRemove);
		remove.active = canControl();
		addRenderableWidget(remove);
	}

	private void initManage() {
		AgentControlAgent agent = selectedAgent();
		if (agent == null) {
			show(Page.OVERVIEW);
			return;
		}
		AgentControlLayout layout = layout();
		int x = layout.contentLeft();
		int width = layout.contentWidth();
		int top = layout.contentTop() + (layout.sideNavigation() ? 28 : 0);
		int buttonWidth = (width - GAP * 2) / 3;
		for (int index = 0; index < 3; index++) {
			String operation = List.of("stop", "resume", "respawn").get(index);
			String label = List.of("Pause work", "Resume work", "Respawn").get(index);
			ConsoleButton action = consoleButton(label,
					x + index * (buttonWidth + GAP), top, buttonWidth, ROW_HEIGHT, false,
					() -> submitAgentOperation(operation));
			action.active = canControl()
					&& (!operation.equals("resume") || canUseAutomation())
					&& AgentControlActions.supports(agent, operation);
			addRenderableWidget(action);
		}
		ConsoleButton automatic = consoleButton(
				agent.automaticProgress() ? "Automatic progress: On" : "Automatic progress: Off",
				x, top + 32, (width - GAP) / 2, ROW_HEIGHT, agent.automaticProgress(),
				() -> submitAgentOperation("auto"));
		automatic.active = canControl();
		addRenderableWidget(automatic);
		ConsoleButton remove = dangerButton("Remove agent...", x + (width - GAP) / 2 + GAP, top + 32,
				(width - GAP) / 2, ROW_HEIGHT, this::confirmRemove);
		remove.active = canControl();
		addRenderableWidget(remove);
		addBackFooter();
	}

	private void addOverviewFooter() {
		int y = layout().footerY();
		int x = contentLeft();
		int buttonWidth = (contentWidth() - GAP * 2) / 3;
		addRenderableWidget(consoleButton("Refresh", x, y, buttonWidth, ROW_HEIGHT, false,
				AgentControlClient::requestSnapshot));
		ConsoleButton skit = consoleButton("Enable skit mode", x + buttonWidth + GAP, y, buttonWidth, ROW_HEIGHT, false,
				() -> send("codex skit on", "Enabling skit mode..."));
		skit.active = canControl();
		addRenderableWidget(skit);
		addRenderableWidget(consoleButton("Close", contentRight() - buttonWidth, y, buttonWidth, ROW_HEIGHT, false, this::onClose));
	}

	private void addCreateFooter() {
		int y = layout().footerY();
		addRenderableWidget(consoleButton("Back to agents", contentLeft(), y, 130, ROW_HEIGHT, false,
				this::navigateBack));
		ConsoleButton create = primaryButton("Create agent", contentRight() - 130, y, 130, ROW_HEIGHT,
				this::submitSummon);
		create.active = canControl() && !summonSubmission.claimed();
		addRenderableWidget(create);
	}

	private void addBackFooter() {
		int y = layout().footerY();
		addRenderableWidget(consoleButton("Back to agents", contentLeft(), y, 130, ROW_HEIGHT, false,
				this::navigateBack));
	}

	private void renderShell(GuiGraphicsExtractor graphics) {
		AgentControlLayout layout = layout();
		int left = layout.panelLeft();
		if (layout.sideNavigation()) {
			int navigationRight = layout.contentLeft() - 14;
			graphics.fill(left, layout.panelTop(), navigationRight, layout.panelBottom(), NAV_SURFACE);
			graphics.fill(navigationRight, layout.panelTop(), navigationRight + 1, layout.panelBottom(), PANEL_EDGE);
			graphics.fill(layout.contentLeft(), layout.panelTop() + 31, layout.panelRight(), layout.panelTop() + 32, PANEL_EDGE);
			graphics.text(font, "Arena", left + 14, layout.panelTop() + 8, ACCENT, false);
			graphics.text(font, "Agents", left + 14, layout.panelTop() + 19, TEXT, false);
			graphics.text(font, "Field Console", left + 14, layout.panelTop() + 35, MUTED, false);
			graphics.text(font, "Operations", left + 14, layout.navigationTop() - 15, MUTED, false);
		} else {
			graphics.fill(left, layout.panelTop(), layout.panelRight(), layout.panelTop() + 37, NAV_SURFACE);
			graphics.fill(left, layout.panelTop() + 37, layout.panelRight(), layout.panelTop() + 38, PANEL_EDGE);
			graphics.text(font, "Arena Agents", left + 14, layout.panelTop() + 10, TEXT, false);
			if (page != Page.GROUP) {
				graphics.text(font, "Field Console", left + 14, layout.panelTop() + 22, MUTED, false);
			}
		}
		String connection = snapshot == null ? "Syncing" : snapshot.canControl() ? "Link online" : "View only";
		int connectionColor = snapshot != null && snapshot.canControl() ? SUCCESS : ACCENT;
		if (layout.sideNavigation()) {
			int navigationRight = layout.contentLeft() - 14;
			graphics.fill(left + 10, layout.panelBottom() - 50, navigationRight - 10, layout.panelBottom() - 24, TRACK);
			graphics.fill(left + 16, layout.panelBottom() - 42, left + 21, layout.panelBottom() - 37, connectionColor);
			graphics.text(font, connection, left + 27, layout.panelBottom() - 44, connectionColor, false);
			graphics.text(font, AgentControlClient.openControlKeyLabel() + "  open console",
					left + 16, layout.panelBottom() - 32, MUTED, false);
		} else {
			int textX = layout.panelRight() - font.width(connection) - 14;
			graphics.fill(textX - 10, layout.panelTop() + 10, textX - 5, layout.panelTop() + 15, connectionColor);
			graphics.text(font, connection, textX, layout.panelTop() + 8, connectionColor, false);
		}
	}

	private void renderHeader(GuiGraphicsExtractor graphics) {
		AgentControlLayout layout = layout();
		if (!layout.sideNavigation() || page == Page.GROUP) return;
		String heading = switch (page) {
			case OVERVIEW -> "Your agents";
			case GROUP -> throw new IllegalStateException("Group page uses its dedicated header");
			case LIVE -> "Live arena";
			case CREATE -> "Create an agent";
			case TASK -> selectedAgent() == null ? "Give a task" : "Give " + selectedAgent().displayName() + " a task";
			case MANAGE -> selectedAgent() == null ? "Manage agent" : "Manage " + selectedAgent().displayName();
			case REMOVE_CONFIRM -> "Confirm agent removal";
		};
		graphics.text(font, heading, contentLeft(), layout.panelTop() + 9, TEXT, false);
		if (snapshot != null) {
			String automation = fit(snapshot.automationStatus(), Math.max(80, contentWidth() / 2));
			graphics.text(font, automation, contentRight() - font.width(automation), layout.panelTop() + 9,
					snapshot.automationAvailable() ? SUCCESS : ERROR, false);
		}
	}

	private void renderGroup(GuiGraphicsExtractor graphics) {
		AgentControlLayout layout = layout();
		int count = rosterState.selectedIds().size();
		int hidden = rosterState.page(1).hiddenSelectedCount();
		String scope = (hidden > 0
				? Component.translatable("screen.arenaagents.roster.scope.hidden", count, hidden)
				: Component.translatable("screen.arenaagents.roster.scope.selected", count)).getString();
		AgentControlLayout.Bounds scopeBounds = layout.groupScopeBounds();
		if (!layout.sideNavigation()) {
			graphics.text(font, fit(scope, scopeBounds.width()), scopeBounds.left(), scopeBounds.top() + 3,
					count == 0 ? MUTED : ACCENT, false);
			return;
		}
		graphics.text(font, "Group prompt", contentLeft(), layout.panelTop() + 9, TEXT, false);
		graphics.text(font, scope, scopeBounds.left(), scopeBounds.top() + 3,
				count == 0 ? MUTED : ACCENT, false);
		if (contentWidth() >= 560) {
			graphics.text(font, "Group selection changes messaging only. Configure agents one at a time.",
					contentLeft() + 126, layout.contentTop() + 4, MUTED, false);
		}
		if (snapshot == null || snapshot.agents().isEmpty()) {
			graphics.text(font, "Create agents before building a control group.", contentLeft(), layout.contentTop() + 28, MUTED, false);
		}
	}

	private void renderLive(GuiGraphicsExtractor graphics) {
		ArenaSpectatorSnapshot arena = AgentControlClient.spectatorState().snapshot().orElse(null);
		ScenarioBuildProgress build = AgentControlClient.buildProgressState().progress().orElse(null);
		if (AgentControlClient.buildProgressState().shouldDisplayBeforeMatch(arena != null)) {
			renderBuildProgress(graphics, build, true);
			return;
		}
		if (arena == null) {
			if (build == null) renderEmptyLiveArena(graphics);
			else renderBuildProgress(graphics, build, true);
			return;
		}
		AgentControlLayout layout = layout();
		int left = contentLeft();
		int available = contentWidth();
		int top = layout.contentTop();
		graphics.fill(left, top, contentRight(), top + 36, SURFACE);
		String clock = ArenaHudPresentation.timeLabel(arena.elapsedTick(), arena.durationTicks(), arena.terminal());
		graphics.text(font, fit(arena.scenarioTitle(), Math.max(60, available - font.width(clock) - 30)),
				left + 10, top + 7, TEXT, false);
		graphics.text(font, clock, contentRight() - font.width(clock) - 10, top + 7, TEXT, false);
		LiveArenaLayout live = LiveArenaLayout.calculate(available, layout.contentHeight(), arena.standings().size());
		liveScroll = Math.clamp(liveScroll, 0, live.maximumScroll());
		int end = Math.min(arena.standings().size(), liveScroll + live.visibleCount());
		String range = arena.standings().isEmpty() ? "No agents"
				: "Agents " + (liveScroll + 1) + " to " + end + " of " + arena.standings().size()
				+ (live.maximumScroll() > 0 ? ", scroll" : "");
		String displayedRange = fit(range, Math.max(90, available / 2));
		graphics.text(font, fit(ArenaHudPresentation.statusLabel(arena.phaseTitle()),
				Math.max(40, available - font.width(displayedRange) - 30)), left + 10, top + 20, ACCENT, false);
		graphics.text(font, displayedRange, contentRight() - font.width(displayedRange) - 10, top + 20,
				live.maximumScroll() > 0 ? ACCENT : MUTED, false);
		int progressLeft = contentRight() - 180;
		int progressRight = contentRight() - 10;
		graphics.fill(progressLeft, top + 31, progressRight, top + 34, TRACK);
		int progress = (int) Math.round((progressRight - progressLeft)
				* Math.min(1.0D, (double) arena.elapsedTick() / arena.durationTicks()));
		graphics.fill(progressLeft, top + 31, progressLeft + progress, top + 34, ACCENT);

		int gridTop = top + live.gridTopOffset();
		for (int sourceIndex = liveScroll; sourceIndex < end; sourceIndex++) {
			int visibleIndex = sourceIndex - liveScroll;
			int column = visibleIndex % live.columns();
			int row = visibleIndex / live.columns();
			renderLiveStanding(graphics, arena.standings().get(sourceIndex),
					left + column * (live.cardWidth() + LiveArenaLayout.COLUMN_GAP),
					gridTop + row * (live.cardHeight() + LiveArenaLayout.ROW_GAP),
					live.cardWidth(), live.cardHeight());
		}
		int shown = end - liveScroll;
		int feedTop = liveFeedTop(layout, live, shown);
		if (feedTop < layout.contentBottom() - 30) renderActivityFeed(graphics, arena.feed(), left, feedTop, available);
	}

	private static int liveFeedTop(AgentControlLayout layout, LiveArenaLayout live, int shownStandings) {
		int rows = (shownStandings + live.columns() - 1) / live.columns();
		return layout.contentTop() + live.gridTopOffset()
				+ rows * (live.cardHeight() + LiveArenaLayout.ROW_GAP) + 2;
	}

	private void renderBuildProgress(GuiGraphicsExtractor graphics, ScenarioBuildProgress build, boolean detailed) {
		AgentControlLayout layout = layout();
		int left = contentLeft();
		int top = layout.contentTop();
		int right = contentRight();
		int color = build.status() == ScenarioBuildProgress.Status.FAILED ? ERROR
				: build.status() == ScenarioBuildProgress.Status.READY ? SUCCESS
				: build.status() == ScenarioBuildProgress.Status.CANCELLED ? MUTED : ACCENT;
		graphics.fill(left, top, right, top + (detailed ? 104 : 38), SURFACE);
		String heading = switch (build.status()) {
			case BUILDING -> "Building arena";
			case CONFIRMATION_REQUIRED -> "Confirm arena build";
			case READY -> "Arena ready";
			case FAILED -> "Build failed";
			case CANCELLED -> "Arena build cancelled";
		};
		graphics.text(font, heading,
				left + 12, top + 10, color, false);
		graphics.text(font, fit(build.scenarioTitle(), Math.max(40, right - left - 170)), left + 12, top + 24, TEXT, false);
		String percentage = build.percent() + "%";
		graphics.text(font, percentage, right - font.width(percentage) - 12, top + 10, color, false);
		int barLeft = left + 12;
		int barRight = right - 12;
		graphics.fill(barLeft, top + 42, barRight, top + 47, TRACK);
		graphics.fill(barLeft, top + 42, barLeft + (barRight - barLeft) * build.percent() / 100, top + 47, color);
		if (!detailed) return;
		graphics.text(font, build.humanPhase() + ", " + build.completed() + " of " + build.total(),
				left + 12, top + 55, TEXT, false);
		graphics.text(font, "World changes made: " + build.changedBlocks(), left + 12, top + 69, MUTED, false);
		graphics.text(font, "Location: " + build.originLabel(), left + 12, top + 83, MUTED, false);
		graphics.text(font, fit(build.detail(), right - left - 24), left + 12, top + 95, color, false);
	}

	private void renderEmptyLiveArena(GuiGraphicsExtractor graphics) {
		int left = contentLeft();
		int top = layout().contentTop();
		graphics.fill(left, top, contentRight(), top + 86, SURFACE);
		graphics.text(font, "No active match", left + 14, top + 14, ACCENT, false);
		graphics.text(font, "Health, score, state, timer and match activity will appear here.",
				left + 14, top + 35, TEXT, false);
		graphics.text(font, "Use Build Arena to prepare a deterministic showcase and dedicated spawn stations.",
				left + 14, top + 52, MUTED, false);
	}

	private void renderLiveStanding(
			GuiGraphicsExtractor graphics,
			ArenaSpectatorSnapshot.Standing standing,
			int left,
			int top,
			int width,
			int height
	) {
		int provider = ArenaSpectatorHud.providerColor(standing.providerFamily());
		graphics.fill(left, top, left + width, top + height, PANEL_EDGE);
		graphics.fill(left + 1, top + 1, left + width - 1, top + height - 1, SURFACE);
		graphics.fill(left + 7, top + 8, left + 31, top + 32, provider);
		graphics.fill(left + 8, top + 9, left + 30, top + 31, TRACK);
		ConsoleText.centered(graphics, font, Component.literal("#" + standing.rank()), left + 19, top + 16, provider);
		int textLeft = left + 39;
		graphics.text(font, fit(standing.displayName(), width - 150), textLeft, top + 7, TEXT, false);
		String score = "Score " + ArenaSpectatorHud.scoreText(standing.score());
		graphics.text(font, score, left + width - font.width(score) - 8, top + 7, provider, false);
		graphics.text(font, capitalize(standing.providerFamily()) + ", "
				+ ArenaHudPresentation.statusLabel(standing.status()), textLeft, top + 20, MUTED, false);
		String healthLabel = "HP " + standing.healthPercent() + "%";
		int barLeft = textLeft;
		int barRight = left + width - font.width(healthLabel) - 12;
		graphics.fill(barLeft, top + 36, barRight, top + 41, TRACK);
		graphics.fill(barLeft, top + 36,
				barLeft + (barRight - barLeft) * standing.healthPercent() / 100, top + 41,
				ArenaHudPresentation.healthColor(standing.healthPercent()));
		graphics.text(font, healthLabel, barRight + 6, top + 34,
				ArenaHudPresentation.healthColor(standing.healthPercent()), false);
	}

	private void renderActivityFeed(
			GuiGraphicsExtractor graphics,
			List<ScenarioPublicEvent> feed,
			int left,
			int top,
			int width
	) {
		if (feed.isEmpty()) {
			graphics.text(font, "Recent match activity", left, top, MUTED, false);
			int y = top + 14;
			graphics.text(font, "Waiting for the first scored action.", left, y, MUTED, false);
			return;
		}
		LiveFeedViewport viewport = LiveFeedViewport.calculate(
				feed.size(), layout().contentBottom() - top - 14, liveFeedScroll);
		liveFeedScroll = viewport.scroll();
		int shown = Math.min(viewport.visibleRows(), feed.size() - viewport.scroll());
		String heading = viewport.maximumScroll() == 0 ? "Recent match activity"
				: "Match activity, " + (viewport.scroll() + 1) + " to "
						+ (viewport.scroll() + shown) + " of " + feed.size();
		graphics.text(font, fit(heading, width), left, top, MUTED, false);
		int y = top + 14;
		for (int index = 0; index < shown; index++) {
			ScenarioPublicEvent event = feed.get(viewport.sourceIndex(feed.size(), index));
			graphics.fill(left, y, left + width, y + 17, TRACK);
			graphics.text(font, fit(event.message(), width - 82), left + 7, y + 4, TEXT, false);
			String state = ArenaHudPresentation.statusLabel(event.state());
			graphics.text(font, state, left + width - font.width(state) - 7, y + 4, MUTED, false);
			y += 20;
		}
	}

	private void renderOverview(GuiGraphicsExtractor graphics) {
		AgentControlLayout layout = layout();
		if (snapshot == null) {
			Optional<String> error = AgentControlClient.snapshotError();
			graphics.text(font, error.isPresent() ? "Agent roster unavailable" : "Loading your agents...",
					contentLeft(), layout.contentTop() + 27, error.isPresent() ? ERROR : MUTED, false);
			error.ifPresent(message -> graphics.text(font, fit(message, contentWidth()), contentLeft(),
					layout.contentTop() + 43, MUTED, false));
			return;
		}
		if (snapshot.agents().isEmpty()) {
			graphics.text(font, "No agents yet", contentLeft(), layout.contentTop() + 27, TEXT, false);
			graphics.text(font, "Create one, choose its model, then give it a clear task.", contentLeft(), layout.contentTop() + 43, MUTED, false);
			return;
		}
		AgentControlAgent selected = selectedAgent();
		if (layout.splitWorkspace() && selected != null) {
			int x = layout.contextLeft();
			int detailTop = layout.contentTop() + 126;
			graphics.text(font, "Selected agent", x, detailTop, MUTED, false);
			graphics.text(font, selected.displayName(), x, detailTop + 17, TEXT, false);
			graphics.text(font, AgentControlPresentation.profileLabel(selected), x, detailTop + 31, MUTED, false);
			graphics.text(font, AgentControlPresentation.stateLabel(selected.state()), x, detailTop + 45,
					stateColor(selected.state()), false);
			String goal = selected.currentGoal().isBlank() ? "No current task" : "Task: " + selected.currentGoal();
			graphics.textWithWordWrap(font, Component.literal(goal), x, detailTop + 64,
					contentRight() - x, MUTED);
			if (!selected.lastError().isBlank()) {
				graphics.textWithWordWrap(font, Component.literal("Needs attention: " + selected.lastError()), x, detailTop + 104,
						contentRight() - x, ERROR);
			}
		}
	}

	private void renderCreate(GuiGraphicsExtractor graphics) {
		AgentControlLayout layout = layout();
		if (!layout.sideNavigation()) return;
		graphics.text(font, "Configure one agent. Use Tab to move and Left or Right to change a value.",
				contentLeft(), layout.contentTop() + 4, MUTED, false);
	}

	private void renderTask(GuiGraphicsExtractor graphics) {
		AgentControlAgent agent = selectedAgent();
		if (agent == null) return;
		AgentControlLayout layout = layout();
		if (layout.sideNavigation()) {
			graphics.text(font, "Current status: " + AgentControlPresentation.stateLabel(agent.state()), contentLeft(), layout.contentTop() + 4, MUTED, false);
			graphics.text(font, "Write one clear outcome. Follow-up work can be queued later.", contentLeft(), layout.contentTop() + 16, MUTED, false);
		}
		if (!canUseAutomation()) {
			graphics.text(font, "Tasks are disabled until automation is ready.", contentLeft(), layout.contentBottom() - 38, ERROR, false);
		}
	}

	private void renderManage(GuiGraphicsExtractor graphics) {
		AgentControlAgent agent = selectedAgent();
		if (agent == null) return;
		AgentControlLayout layout = layout();
		if (!layout.sideNavigation()) return;
		graphics.text(font, AgentControlPresentation.profileLabel(agent), contentLeft(), layout.contentTop() + 4, MUTED, false);
		graphics.text(font, "Status: " + AgentControlPresentation.stateLabel(agent.state()), contentLeft(), layout.contentTop() + 16,
				stateColor(agent.state()), false);
		graphics.text(font, "Routine controls are separated here so creating and assigning agents stays simple.",
				contentLeft(), layout.contentTop() + 94, MUTED, false);
	}

	private void renderRemoveConfirm(GuiGraphicsExtractor graphics) {
		AgentControlAgent agent = selectedAgent();
		if (agent == null) return;
		AgentControlLayout layout = layout();
		int left = layout.contentLeft();
		int top = layout.contentTop() + (layout.sideNavigation() ? 22 : 0);
		int right = layout.contentRight();
		int bottom = Math.min(layout.contentBottom(), top + 104);
		graphics.fill(left, top, right, bottom, ConsoleTheme.ERROR);
		graphics.fill(left + 2, top + 2, right - 2, bottom - 2, ConsoleTheme.SURFACE);
		graphics.text(font, "Remove " + fit(agent.displayName(), right - left - 34),
				left + 14, top + 14, ERROR, false);
		graphics.text(font, "This deletes the agent and its saved state.", left + 14, top + 37, TEXT, false);
		graphics.text(font, "This cannot be undone from the Field Console.", left + 14, top + 53, MUTED, false);
		graphics.text(font, "Choose Keep Agent to return without changing anything.", left + 14, top + 75,
				MUTED, false);
	}

	private void renderInputSurfaces(GuiGraphicsExtractor graphics) {
		if (promptInput == null) return;
		int outline = promptInput.isFocused() ? ConsoleTheme.FOCUS
				: promptInput.isHovered() ? ConsoleTheme.ACCENT : ConsoleTheme.BORDER;
		graphics.fill(promptInput.getX() - 1, promptInput.getY() - 1,
				promptInput.getRight() + 1, promptInput.getBottom() + 1, outline);
		graphics.fill(promptInput.getX(), promptInput.getY(), promptInput.getRight(), promptInput.getBottom(),
				ConsoleTheme.TRACK);
	}

	private void renderStatus(GuiGraphicsExtractor graphics) {
		if (!feedback.isBlank()) {
			AgentControlLayout layout = layout();
			int y = layout.sideNavigation() ? layout.footerY() - 14 : layout.contentTop() - 9;
			ConsoleText.centered(graphics, font, fit(feedback, layout.contentWidth()), width / 2, y,
					feedbackError ? ERROR : SUCCESS);
		} else if (mutationState.mutationPending()) {
			AgentControlLayout layout = layout();
			ConsoleText.centered(graphics, font, "Waiting for the server update...", width / 2,
					layout.sideNavigation() ? layout.footerY() - 14 : layout.contentTop() - 9, ACCENT);
		} else if (snapshot != null && !snapshot.canControl()) {
			AgentControlLayout layout = layout();
			ConsoleText.centered(graphics, font, "View only. Operator permission is required to make changes.",
					width / 2, layout.sideNavigation() ? layout.footerY() - 14 : layout.contentTop() - 9, ACCENT);
		} else if (page != Page.LIVE) {
			ScenarioBuildProgress build = AgentControlClient.buildProgressState().progress().orElse(null);
			if (build != null && build.status() == ScenarioBuildProgress.Status.BUILDING) {
				AgentControlLayout layout = layout();
				String message = "Arena build, " + build.humanPhase() + ", " + build.percent()
						+ "%, " + build.originLabel();
				ConsoleText.centered(graphics, font, fit(message, layout.contentWidth()), width / 2,
						layout.footerY() - 14, ACCENT);
			}
		}
	}

	private void submitSummon() {
		String command;
		try {
			name = nameInput.getValue();
			command = AgentControlCommandBuilder.summon(
					provider, model, reasoning, serviceTier, name, gameMode);
		} catch (IllegalArgumentException exception) {
			setFeedback(exception.getMessage(), true);
			return;
		}
		dispatchSummonOnce(
				summonSubmission,
				() -> send(command, "Creating agent..."),
				() -> {
					rememberPreferences();
					onClose();
				}
		);
	}

	private void submitPrompt(String operation) {
		AgentControlAgent agent = selectedAgent();
		if (agent == null) return;
		if (rejectPendingMutation()) return;
		if (!canUseAutomation()) {
			setFeedback(snapshot == null ? "Automation is not ready" : snapshot.automationStatus(), true);
			return;
		}
		try {
			prompt = promptInput.getValue();
			send(AgentControlCommandBuilder.prompt(operation, agent.agentId(), prompt), "Sending task...");
		} catch (IllegalArgumentException exception) {
			setFeedback(exception.getMessage(), true);
		}
	}

	private void submitGroupPrompt(String operation) {
		List<AgentControlAgent> agents = selectedGroupAgents();
		if (rejectPendingMutation()) return;
		if (!canUseAutomation()) {
			setFeedback(snapshot == null ? "Automation is not ready" : snapshot.automationStatus(), true);
			return;
		}
		var blocker = AgentControlActions.firstUnsupported(agents, operation);
		if (blocker.isPresent()) {
			AgentControlAgent agent = blocker.orElseThrow();
			setFeedback(agent.displayName() + " cannot " + operation + " while "
					+ AgentControlPresentation.stateLabel(agent.state()), true);
			return;
		}
		try {
			prompt = promptInput.getValue();
			List<String> accepted = new ArrayList<>();
			List<String> rejected = new ArrayList<>();
			long latestMutationId = 0L;
			for (AgentControlAgent agent : agents) {
				OptionalLong receipt = AgentControlClient.sendCommandWithReceipt(
						AgentControlCommandBuilder.prompt(operation, agent.agentId(), prompt));
				if (receipt.isPresent()) {
					accepted.add(agent.displayName());
					latestMutationId = receipt.getAsLong();
				} else {
					rejected.add(agent.displayName());
				}
			}
			if (latestMutationId > 0L) mutationState = mutationState.beginMutation(latestMutationId);
			setFeedback(AgentControlActions.deliverySummary(accepted, rejected), !rejected.isEmpty());
			rebuildWidgets();
		} catch (IllegalArgumentException exception) {
			setFeedback(exception.getMessage(), true);
		}
	}

	private void submitAgentOperation(String operation) {
		AgentControlAgent agent = selectedAgent();
		if (agent == null) return;
		send(AgentControlCommandBuilder.agent(operation, agent.agentId()), "Updating " + agent.displayName() + "...");
	}

	private void confirmRemove() {
		if (selectedAgent() != null) show(Page.REMOVE_CONFIRM);
	}

	private void submitConfirmedRemove() {
		AgentControlAgent agent = selectedAgent();
		if (agent == null) return;
		page = Page.OVERVIEW;
		rebuildWidgets();
		send(AgentControlCommandBuilder.agent("remove", agent.agentId()),
				"Removing " + agent.displayName() + "...");
	}

	private boolean send(String command, String pendingMessage) {
		if (rejectPendingMutation()) return false;
		if (!serverCanControl()) {
			setFeedback("View only. Operator permission is required to make changes.", true);
			return false;
		}
		OptionalLong receipt = AgentControlClient.sendCommandWithReceipt(command);
		if (receipt.isPresent()) {
			mutationState = mutationState.beginMutation(receipt.getAsLong());
			setFeedback(pendingMessage + " Awaiting server update.", false);
			rebuildWidgets();
			return true;
		}
		setFeedback("Not connected to a compatible server", true);
		return false;
	}

	private boolean rejectPendingMutation() {
		if (!mutationState.mutationPending()) return false;
		setFeedback("Wait for the current server update before sending another command.", true);
		return true;
	}

	private void show(Page next) {
		Page destination = next;
		if (next == Page.GROUP && snapshot != null && snapshot.agents().size() < 2
				&& snapshot.groups().isEmpty()) {
			destination = Page.OVERVIEW;
		}
		page = destination;
		if (page != Page.GROUP) compactGroupComposer = false;
		feedback = "";
		rebuildWidgets();
	}

	private void setFeedback(String message, boolean error) {
		feedback = Objects.requireNonNullElse(message, "");
		feedbackError = error;
	}

	private void rememberPreferences() {
		AgentControlClient.rememberPreferences(provider, model, reasoning, serviceTier);
	}

	private void normalizeCreateSelection() {
		AgentControlClient.Preferences remembered = AgentControlClient.preferences();
		CreateSelection normalized = normalizeCreateSelection(
				remembered.provider(), remembered.model(), remembered.reasoning(), remembered.serviceTier());
		provider = normalized.provider();
		model = normalized.model();
		reasoning = normalized.reasoning();
		serviceTier = normalized.serviceTier();
		if (AgentControlClient.catalogAuthoritative()) rememberPreferences();
	}

	static CreateSelection normalizeCreateSelection(
			String provider,
			String model,
			String reasoning,
			String serviceTier
	) {
		List<String> providers = AgentControlCatalog.providers();
		String normalizedProvider = providers.contains(provider) ? provider : providers.getFirst();
		List<String> models = AgentControlCatalog.models(normalizedProvider);
		String normalizedModel = models.contains(model)
				? model : AgentControlCatalog.defaultModel(normalizedProvider);
		List<String> efforts = AgentControlCatalog.reasoningEfforts(normalizedProvider, normalizedModel);
		String normalizedReasoning = efforts.contains(reasoning)
				? reasoning : AgentControlCatalog.defaultReasoning(normalizedProvider, normalizedModel);
		List<String> tiers = AgentControlCatalog.serviceTiers(normalizedProvider, normalizedModel);
		String normalizedTier = tiers.contains(serviceTier) ? serviceTier
				: tiers.contains("priority") ? "priority" : tiers.getFirst();
		return new CreateSelection(normalizedProvider, normalizedModel, normalizedReasoning, normalizedTier);
	}

	private AgentControlAgent selectedAgent() {
		if (snapshot == null || managementSelection.selectedAgentId().isBlank()) return null;
		return snapshot.agents().stream()
				.filter(agent -> agent.agentId().equals(managementSelection.selectedAgentId())).findFirst().orElse(null);
	}

	private void selectAgent(String agentId) {
		if (snapshot == null) return;
		managementSelection.select(agentId, snapshot.agents());
		rosterState.focus(managementSelection.selectedAgentId());
	}

	private List<AgentControlAgent> selectedGroupAgents() {
		if (snapshot == null) return List.of();
		return snapshot.agents().stream()
				.filter(agent -> rosterState.selectedIds().contains(agent.agentId()))
				.toList();
	}

	private boolean filtersVisible() {
		return snapshot != null && AgentControlLayout.rosterFiltersVisible(snapshot.agents().size());
	}

	private List<String> providerOptions() {
		List<String> options = new ArrayList<>();
		options.add("");
		for (AgentRosterEntry entry : rosterEntries) {
			if (options.stream().noneMatch(value -> value.equalsIgnoreCase(entry.provider()))) {
				options.add(entry.provider());
			}
		}
		return List.copyOf(options);
	}

	private List<String> stateOptions() {
		List<String> options = new ArrayList<>();
		options.add("");
		for (AgentRosterEntry entry : rosterEntries) {
			if (options.stream().noneMatch(value -> value.equalsIgnoreCase(entry.state()))) {
				options.add(entry.state());
			}
		}
		return List.copyOf(options);
	}

	private void applyRosterFilter(AgentRosterFilter filter) {
		setRosterFilterWithoutRebuild(filter);
		selectAgent(rosterState.focusedId());
		rebuildWidgets();
	}

	private void setRosterFilterWithoutRebuild(AgentRosterFilter filter) {
		AgentRosterFilter nextFilter = Objects.requireNonNull(filter, "roster filter must not be null");
		int currentPage = rosterState.page(1).page();
		int nextPage = AgentControlLayout.rosterPageAfterFilterUpdate(rosterFilter, nextFilter, currentPage);
		rosterFilter = nextFilter;
		rosterState.setFilter(rosterFilter);
		if (nextPage != currentPage) rosterState.setPage(nextPage, 1);
		boolean focusVisible = rosterEntries.stream()
				.anyMatch(entry -> entry.id().equals(rosterState.focusedId()) && rosterFilter.matches(entry));
		if (!focusVisible) {
			rosterEntries.stream().filter(rosterFilter::matches).findFirst()
					.ifPresent(entry -> rosterState.focus(entry.id()));
		}
	}

	private void normalizeRosterFilterOptions() {
		String provider = providerOptions().stream()
				.anyMatch(value -> value.equalsIgnoreCase(rosterFilter.provider())) ? rosterFilter.provider() : "";
		String state = stateOptions().stream()
				.anyMatch(value -> value.equalsIgnoreCase(rosterFilter.state())) ? rosterFilter.state() : "";
		setRosterFilterWithoutRebuild(new AgentRosterFilter(rosterFilter.query(), provider, state));
	}

	private static List<AgentRosterEntry> rosterEntries(AgentControlSnapshot snapshot) {
		List<AgentRosterEntry> entries = new ArrayList<>();
		for (AgentControlAgent agent : snapshot.agents()) {
			entries.add(new AgentRosterEntry(
					agent.agentId(),
					agent.displayName(),
					agent.provider(),
					AgentModelNames.shortLabel(agent.provider(), agent.model()),
					AgentControlPresentation.stateLabel(agent.state()),
					true,
					""
			));
		}
		return List.copyOf(entries);
	}

	private static Map<String, AgentVisualIdentity.Resolved> rosterVisuals(AgentControlSnapshot snapshot) {
		Map<String, AgentVisualIdentity.Resolved> visuals = new LinkedHashMap<>();
		for (AgentControlAgent agent : snapshot.agents()) {
			AgentVisualIdentity.Resolved previous = visuals.put(
					agent.agentId(),
					AgentVisualIdentity.resolve(agent.provider(), agent.model(), agent.skinVariant())
			);
			if (previous != null) throw new IllegalArgumentException("Duplicate agent roster ID: " + agent.agentId());
		}
		return Map.copyOf(visuals);
	}

	private MultiLineEditBox multiLineInput(
			int x,
			int y,
			int width,
			int height,
			String narration,
			String placeholder
	) {
		MultiLineEditBox input = MultiLineEditBox.builder()
				.setX(x)
				.setY(y)
				.setPlaceholder(Component.literal(placeholder))
				.setTextColor(TEXT)
				.setTextShadow(false)
				.setCursorColor(ACCENT)
				.setShowBackground(false)
				.setShowDecorations(false)
				.build(font, width, height, Component.literal(narration));
		input.setCharacterLimit(AgentConstants.MAX_PROMPT_LENGTH);
		input.setLineLimit(Math.max(2, height / 12));
		input.setValue(prompt);
		input.setValueListener(value -> prompt = value);
		return input;
	}

	private ConsoleEditBox consoleEditBox(
			int x,
			int y,
			int width,
			int height,
			String identity,
			Component placeholder
	) {
		return new ConsoleEditBox(font, x, y, width, height,
				Component.translatable("screen.arenaagents.name"), placeholder, identity);
	}

	private ConsoleButton consoleButton(
			String label,
			int x,
			int y,
			int width,
			int height,
			boolean selected,
			Runnable action
	) {
		return consoleButton(Component.literal(label), x, y, width, height, selected, action);
	}

	private ConsoleButton consoleButton(
			Component label, int x, int y, int width, int height, boolean selected, Runnable action
	) {
		return new ConsoleButton(font, x, y, width, height, label, selected, ACCENT, action);
	}

	private ConsoleButton primaryButton(
			String label, int x, int y, int width, int height, Runnable action
	) {
		return new ConsoleButton(font, x, y, width, height, Component.literal(label), false, ACCENT,
				ConsoleButton.Tone.PRIMARY, action);
	}

	private ConsoleButton dangerButton(
			String label, int x, int y, int width, int height, Runnable action
	) {
		return new ConsoleButton(font, x, y, width, height, Component.literal(label), false, ACCENT,
				ConsoleButton.Tone.DANGER, action);
	}

	private boolean canControl() {
		return serverCanControl() && !mutationState.mutationPending();
	}

	private boolean serverCanControl() {
		return snapshot != null && snapshot.canControl();
	}

	private boolean canUseAutomation() {
		return canControl() && snapshot.automationAvailable();
	}

	private int panelWidth() {
		return layout().panelWidth();
	}

	private int panelLeft() {
		return layout().panelLeft();
	}

	private int contentLeft() {
		return layout().contentLeft();
	}

	private int contentRight() {
		return layout().contentRight();
	}

	private int contentWidth() {
		return contentRight() - contentLeft();
	}

	private AgentControlLayout layout() {
		return AgentControlLayout.calculate(width, height);
	}

	private static int stateColor(String state) {
		return switch (state.toUpperCase(Locale.ROOT)) {
			case "ERROR", "DEAD", "DISCONNECTED" -> ERROR;
			case "IDLE", "COMPLETED" -> SUCCESS;
			default -> ACCENT;
		};
	}

	private static String capitalize(String value) {
		if (value == null || value.isBlank()) return "";
		return Character.toUpperCase(value.charAt(0)) + value.substring(1).toLowerCase(Locale.ROOT);
	}

	private String fit(String value, int available) {
		if (font.width(value) <= available) return value;
		String suffix = "...";
		return font.plainSubstrByWidth(value, Math.max(1, available - font.width(suffix))) + suffix;
	}

	record CreateSelection(String provider, String model, String reasoning, String serviceTier) {
	}

	static boolean dispatchSummonOnce(
			SingleSubmissionGate gate,
			BooleanSupplier dispatch,
			Runnable onAccepted
	) {
		Objects.requireNonNull(gate, "gate must not be null");
		Objects.requireNonNull(dispatch, "dispatch must not be null");
		Objects.requireNonNull(onAccepted, "accepted callback must not be null");
		if (!gate.tryClaim()) return false;
		if (!dispatch.getAsBoolean()) {
			gate.release();
			return false;
		}
		onAccepted.run();
		return true;
	}

	static final class SingleSubmissionGate {
		private final AtomicBoolean claimed = new AtomicBoolean();

		boolean tryClaim() {
			return claimed.compareAndSet(false, true);
		}

		boolean claimed() {
			return claimed.get();
		}

		void release() {
			claimed.set(false);
		}
	}

	private enum Page {
		OVERVIEW,
		GROUP,
		LIVE,
		CREATE,
		TASK,
		MANAGE,
		REMOVE_CONFIRM
	}
}
