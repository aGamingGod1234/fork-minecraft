package dev.agaminggod.arenaagents.client.presentation;

import dev.agaminggod.arenaagents.client.gui.ConsoleTheme;
import dev.agaminggod.arenaagents.scenario.presentation.ArenaSpectatorSnapshot;
import java.math.BigDecimal;
import java.util.Objects;

/** Shared arena formatting without a persistent in-world overlay. */
public final class ArenaSpectatorHud {
	private ArenaSpectatorHud() {
	}

	public static void register(
			ArenaSpectatorState spectatorState,
			ScenarioBuildProgressState scenarioBuildState
	) {
		Objects.requireNonNull(spectatorState, "spectatorState must not be null");
		Objects.requireNonNull(scenarioBuildState, "scenarioBuildState must not be null");
		// Construction is rendered through the action bar above the hotbar. Detailed
		// standings and agent state remain available on demand in G > Live.
	}

	public static int providerColor(String providerFamily) {
		return ConsoleTheme.providerColor(Objects.requireNonNull(
				providerFamily, "providerFamily must not be null"));
	}

	public static String standingLabel(ArenaSpectatorSnapshot.Standing standing) {
		Objects.requireNonNull(standing, "standing must not be null");
		return "#" + standing.rank() + " " + standing.displayName()
				+ " [" + standing.providerFamily() + "] " + scoreText(standing.score())
				+ " | HP " + standing.healthPercent() + "% | " + standing.status();
	}

	public static String scoreText(double score) {
		return (score == 0.0D ? BigDecimal.ZERO : BigDecimal.valueOf(score).stripTrailingZeros()).toPlainString();
	}
}
