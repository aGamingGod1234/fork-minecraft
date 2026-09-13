package dev.agaminggod.arenaagents.scenario;

import java.util.Locale;

public enum ScenarioPlacementMode {
	IN_FRONT_OF_PLAYER("In front of me", "Build the arena center 80 blocks ahead so construction stays in view"),
	AT_PLAYER("At my position", "Build on the surface at your current X/Z coordinates"),
	FIXED_LANE("Fixed arena lane", "Use the deterministic preset lane coordinates");

	private final String displayName;
	private final String description;

	ScenarioPlacementMode(String displayName, String description) {
		this.displayName = displayName;
		this.description = description;
	}

	public String displayName() {
		return displayName;
	}

	public String description() {
		return description;
	}

	public String wireName() {
		return name().toLowerCase(Locale.ROOT);
	}

	public static ScenarioPlacementMode parse(String value) {
		if (value == null) throw new IllegalArgumentException("arena placement mode is required");
		try {
			return valueOf(value.strip().toUpperCase(Locale.ROOT));
		} catch (IllegalArgumentException exception) {
			throw new IllegalArgumentException("unsupported arena placement mode: " + value, exception);
		}
	}
}
