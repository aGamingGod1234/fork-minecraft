package dev.agaminggod.arenaagents.client.presentation;

import dev.agaminggod.arenaagents.scenario.presentation.ArenaSpectatorSnapshot;
import dev.agaminggod.arenaagents.client.gui.AgentControlLayout;
import dev.agaminggod.arenaagents.client.gui.ConsoleFont;
import dev.agaminggod.arenaagents.client.gui.ScenarioResultsLayout;
import dev.agaminggod.arenaagents.client.gui.widget.ConsoleButton;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import org.lwjgl.glfw.GLFW;

public final class ScenarioResultsScreen extends Screen {
	private static final int PANEL = 0xF21A1D23;
	private static final int EDGE = 0xFF3A3F4B;
	private static final int SURFACE = 0xFF222B36;
	private static final int TRACK = 0xFF10161C;
	private static final int ACCENT = 0xFFF2BD58;
	private static final int FAILURE = 0xFFFF737A;
	private static final int TEXT = 0xFFF2F3F5;
	private static final int MUTED = 0xFFABB1BC;
	private final ArenaSpectatorSnapshot snapshot;
	private final Runnable dismiss;
	private boolean dismissed;
	private int standingScroll;

	public ScenarioResultsScreen(ArenaSpectatorSnapshot snapshot, Runnable dismiss) {
		super(Minecraft.getInstance(), ConsoleFont.create(Minecraft.getInstance()),
				Component.translatable("screen.arenaagents.results.title"));
		this.snapshot = Objects.requireNonNull(snapshot, "snapshot must not be null");
		if (!snapshot.terminal()) throw new IllegalArgumentException("results require a terminal snapshot");
		this.dismiss = Objects.requireNonNull(dismiss, "dismiss must not be null");
	}

	public static List<String> resultLines(ArenaSpectatorSnapshot snapshot) {
		Objects.requireNonNull(snapshot, "snapshot must not be null");
		if (!snapshot.terminal()) throw new IllegalArgumentException("results require a terminal snapshot");
		ArrayList<String> lines = new ArrayList<>();
		lines.add(failed(snapshot) ? "Match failed"
				: snapshot.standings().isEmpty() ? "No winner recorded"
				: "Winner: " + snapshot.standings().getFirst().displayName());
		for (ArenaSpectatorSnapshot.Standing standing : snapshot.standings()) {
			lines.add(ArenaSpectatorHud.standingLabel(standing));
		}
		lines.add("Outcome: " + snapshot.phaseTitle() + " after " + snapshot.elapsedTick() + " ticks");
		for (var event : snapshot.feed().reversed().stream().limit(2).toList()) {
			lines.add("Decisive event: " + event.message());
		}
		lines.add(snapshot.scenarioTitle());
		lines.add("Run: " + snapshot.runId());
		lines.add("Map: " + snapshot.scenarioId() + " @ " + snapshot.mapVersion());
		lines.add("Seeds: world=" + snapshot.worldSeed() + " event=" + snapshot.eventSeed());
		lines.add("Result SHA-256: " + snapshot.resultHash());
		return List.copyOf(lines);
	}

	public static String headline(ArenaSpectatorSnapshot snapshot) {
		Objects.requireNonNull(snapshot, "snapshot must not be null");
		return failed(snapshot) ? "MATCH FAILED" : "MATCH COMPLETE";
	}

	private static boolean failed(ArenaSpectatorSnapshot snapshot) {
		return snapshot.phaseTitle().equalsIgnoreCase("failed");
	}

	@Override
	public Component getNarrationMessage() {
		MutableComponent narration = getTitle().copy();
		List<String> lines = resultLines(snapshot);
		for (String line : lines.subList(0, Math.min(lines.size(), snapshot.standings().size() + 4))) {
			narration.append(". ").append(Component.literal(line));
		}
		if (snapshot.standings().size() > layout().visibleStandings()) {
			narration.append(". ").append(Component.literal(scrollPositionLabel()));
		}
		return narration;
	}

	@Override
	protected void init() {
		ScenarioResultsLayout layout = layout();
		addRenderableWidget(new ConsoleButton(font, (width - 140) / 2, layout.footerY(), 140,
				AgentControlLayout.CONTROL_HEIGHT,
				Component.translatable("screen.arenaagents.results.return"), false, ACCENT,
				ConsoleButton.Tone.PRIMARY, this::onClose));
	}

	@Override
	public boolean mouseScrolled(double mouseX, double mouseY, double horizontalAmount, double verticalAmount) {
		ScenarioResultsLayout layout = layout();
		int maximum = Math.max(0, snapshot.standings().size() - layout.visibleStandings());
		int step = layout.columns();
		int next = Math.clamp(standingScroll + (verticalAmount > 0.0D ? -step : step), 0, maximum);
		if (next == standingScroll) return super.mouseScrolled(mouseX, mouseY, horizontalAmount, verticalAmount);
		standingScroll = next;
		return true;
	}

	@Override
	public boolean keyPressed(KeyEvent event) {
		if (super.keyPressed(event)) return true;
		ScenarioResultsLayout layout = layout();
		int maximum = Math.max(0, snapshot.standings().size() - layout.visibleStandings());
		int next = switch (event.key()) {
			case GLFW.GLFW_KEY_UP -> standingScroll - layout.columns();
			case GLFW.GLFW_KEY_DOWN -> standingScroll + layout.columns();
			case GLFW.GLFW_KEY_PAGE_UP -> standingScroll - layout.visibleStandings();
			case GLFW.GLFW_KEY_PAGE_DOWN -> standingScroll + layout.visibleStandings();
			case GLFW.GLFW_KEY_HOME -> 0;
			case GLFW.GLFW_KEY_END -> maximum;
			default -> standingScroll;
		};
		next = Math.clamp(next, 0, maximum);
		if (next == standingScroll) return false;
		standingScroll = next;
		return true;
	}

	private String scrollPositionLabel() {
		ScenarioResultsLayout layout = layout();
		int end = Math.min(snapshot.standings().size(), standingScroll + layout.visibleStandings());
		return "Standings " + (standingScroll + 1) + " to " + end + " of " + snapshot.standings().size()
				+ ". Use arrow or page keys to scroll.";
	}

	@Override
	public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
		ScenarioResultsLayout layout = layout();
		int panelWidth = layout.panelWidth();
		int left = layout.panelLeft();
		int top = layout.panelTop();
		int bottom = layout.panelBottom();
		graphics.fill(0, 0, width, height, 0xC00B1016);
		graphics.fill(left - 1, top - 1, layout.panelRight() + 1, bottom + 1, EDGE);
		graphics.fill(left, top, layout.panelRight(), bottom, PANEL);
		int resultTone = failed(snapshot) ? FAILURE : ACCENT;
		graphics.fill(left, top, left + panelWidth, top + 3, resultTone);
		graphics.text(font, headline(snapshot), left + 14, top + 12, resultTone, false);
		String winner = failed(snapshot)
				? "NO WINNER  RUN FAILED"
				: snapshot.standings().isEmpty()
				? snapshot.scenarioTitle()
				: "WINNER  " + snapshot.standings().getFirst().displayName();
		graphics.text(font, fit(winner, panelWidth - 150), left + 14, top + 27,
				failed(snapshot) ? FAILURE : TEXT, false);
		String duration = ArenaHudPresentation.timeLabel(snapshot.elapsedTick(), snapshot.durationTicks(), true);
		graphics.text(font, duration, left + panelWidth - font.width(duration) - 14, top + 27, TEXT, false);

		int end = Math.min(snapshot.standings().size(), standingScroll + layout.visibleStandings());
		for (int sourceIndex = standingScroll; sourceIndex < end; sourceIndex++) {
			int visibleIndex = sourceIndex - standingScroll;
			ArenaSpectatorSnapshot.Standing standing = snapshot.standings().get(sourceIndex);
			int cardLeft = layout.gridLeft() + (visibleIndex % layout.columns())
					* (layout.cardWidth() + ScenarioResultsLayout.CARD_GAP);
			int cardTop = layout.gridTop() + (visibleIndex / layout.columns())
					* (ScenarioResultsLayout.CARD_HEIGHT + ScenarioResultsLayout.CARD_GAP);
			renderStanding(graphics, standing, cardLeft, cardTop, layout.cardWidth());
		}
		int detailsTop = layout.detailsTop();
		if (snapshot.standings().size() > layout.visibleStandings()) {
			String range = "STANDINGS " + (standingScroll + 1) + "-" + end + " OF " + snapshot.standings().size()
					+ " | WHEEL, ARROWS, OR PAGE KEYS";
			graphics.text(font, range, left + 14, detailsTop - 11, ACCENT, false);
		}
		graphics.text(font, "OUTCOME", left + 14, detailsTop, resultTone, false);
		graphics.text(font, fit(snapshot.phaseTitle() + " after " + snapshot.elapsedTick() + " ticks", panelWidth - 28),
				left + 14, detailsTop + 12, failed(snapshot) ? FAILURE : TEXT, false);
		String decisiveEvent = snapshot.feed().isEmpty()
				? "No decisive event recorded"
				: "Decisive  " + snapshot.feed().getLast().message();
		graphics.text(font, fit(decisiveEvent, panelWidth - 28), left + 14, detailsTop + 25, TEXT, false);
		graphics.text(font, fit("Run " + snapshot.runId() + " | Map " + snapshot.scenarioId()
				+ " @ " + snapshot.mapVersion(), panelWidth - 28), left + 14, detailsTop + 38, MUTED, false);
		super.extractRenderState(graphics, mouseX, mouseY, partialTick);
	}

	private void renderStanding(
			GuiGraphicsExtractor graphics,
			ArenaSpectatorSnapshot.Standing standing,
			int left,
			int top,
			int width
	) {
		int provider = ArenaSpectatorHud.providerColor(standing.providerFamily());
		graphics.fill(left, top, left + width, top + 36, EDGE);
		graphics.fill(left + 1, top + 1, left + width - 1, top + 35, SURFACE);
		graphics.text(font, "#" + standing.rank(), left + 8, top + 7, provider, false);
		String score = ArenaSpectatorHud.scoreText(standing.score()) + " pts";
		int nameWidth = Math.max(30, width - 47 - font.width(score));
		graphics.text(font, fit(standing.displayName(), nameWidth), left + 31, top + 7, TEXT, false);
		graphics.text(font, score, left + width - font.width(score) - 8, top + 7, provider, false);
		String healthLabel = "HP " + standing.healthPercent() + "%";
		int barLeft = left + 31;
		int barRight = left + width - font.width(healthLabel) - 12;
		graphics.fill(barLeft, top + 23, barRight, top + 28, TRACK);
		graphics.fill(barLeft, top + 23, barLeft + (barRight - barLeft) * standing.healthPercent() / 100,
				top + 28, ArenaHudPresentation.healthColor(standing.healthPercent()));
		graphics.text(font, healthLabel, barRight + 6, top + 21,
				ArenaHudPresentation.healthColor(standing.healthPercent()), false);
	}

	private ScenarioResultsLayout layout() {
		return ScenarioResultsLayout.calculate(width, height, snapshot.standings().size());
	}

	private String fit(String value, int available) {
		if (font.width(value) <= available) return value;
		String suffix = "...";
		return font.plainSubstrByWidth(value, Math.max(1, available - font.width(suffix))) + suffix;
	}

	@Override
	public void onClose() {
		if (!dismissed) {
			dismissed = true;
			dismiss.run();
		}
		super.onClose();
	}

	@Override
	public boolean isPauseScreen() {
		return false;
	}
}
