package dev.agaminggod.arenaagents.client.gui.scenario;

import dev.agaminggod.arenaagents.agent.AgentGameMode;
import dev.agaminggod.arenaagents.scenario.ScenarioPresets;
import java.util.Arrays;

public enum ScenarioPreset {
	LAST_VALLEY(
			'A',
			"last-valley",
			"25–40 min",
			"Cooperative or solo",
			AgentGameMode.SURVIVAL
	),
	IMPOSSIBLE_BRIEF(
			'B',
			"impossible-brief",
			"15–30 min",
			"Individual or team",
			AgentGameMode.CREATIVE
	),
	CITADEL_COLLAPSE(
			'C',
			"citadel-collapse",
			"8–15 min",
			"Competitive",
			AgentGameMode.SURVIVAL
	),
	THINKING_TOWER(
			'D',
			"thinking-tower",
			"6–12 min",
			"Parallel individual lanes",
			AgentGameMode.ADVENTURE
	);

	private final char letter;
	private final String duration;
	private final String format;
	private final AgentGameMode defaultGameMode;
	private final dev.agaminggod.arenaagents.scenario.ScenarioPreset corePreset;

	ScenarioPreset(
			char letter,
			String id,
			String duration,
			String format,
			AgentGameMode defaultGameMode
	) {
		this.letter = letter;
		this.duration = duration;
		this.format = format;
		this.defaultGameMode = defaultGameMode;
		this.corePreset = ScenarioPresets.require(id);
	}

	public char letter() {
		return letter;
	}

	public String id() {
		return corePreset.id();
	}

	public String title() {
		return corePreset.title();
	}

	public String category() {
		return switch (corePreset.category()) {
			case SURVIVAL -> "Dynamic survival";
			case BUILDING -> "Building";
			case PVP -> "PvP";
			case PARKOUR -> "Parkour and route reasoning";
		};
	}

	public String duration() {
		return duration;
	}

	public int minimumAgents() {
		return corePreset.minimumAgents();
	}

	public int maximumAgents() {
		return corePreset.maximumAgents();
	}

	public String format() {
		return format;
	}

	public String description() {
		return corePreset.subtitle();
	}

	public AgentGameMode defaultGameMode() {
		return defaultGameMode;
	}

	public boolean gameModeLocked() {
		return !corePreset.requiredGameMode().equalsIgnoreCase("CONFIGURABLE");
	}

	public String mapVersion() {
		return corePreset.mapVersion();
	}

	public int accentColor() {
		return 0xFF000000 | Integer.parseUnsignedInt(corePreset.accentColor().substring(1), 16);
	}

	public static ScenarioPreset fromLetter(int keyCode) {
		char letter = Character.toUpperCase((char) keyCode);
		return Arrays.stream(values()).filter(value -> value.letter == letter).findFirst().orElse(null);
	}
}
