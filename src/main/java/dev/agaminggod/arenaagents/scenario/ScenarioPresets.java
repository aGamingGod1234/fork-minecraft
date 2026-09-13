package dev.agaminggod.arenaagents.scenario;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public final class ScenarioPresets {
	private static final List<ScenarioPreset> BUILT_INS = List.of(
			lastValley(),
			impossibleBrief(),
			citadelCollapse(),
			thinkingTower()
	);
	private static final Map<String, ScenarioPreset> BY_ID = index(BUILT_INS);

	private ScenarioPresets() {
	}

	public static List<ScenarioPreset> all() {
		return BUILT_INS;
	}

	public static ScenarioPreset require(String id) {
		String normalizedId = ScenarioValidators.text(id, "scenario id", 64).toLowerCase(Locale.ROOT);
		ScenarioPreset preset = BY_ID.get(normalizedId);
		if (preset == null) {
			throw ScenarioValidators.failure("UNKNOWN_SCENARIO", "unknown scenario " + normalizedId);
		}
		return preset;
	}

	public static List<ScenarioPreset> forCategory(ScenarioCategory category) {
		return BUILT_INS.stream().filter(preset -> preset.category() == category).toList();
	}

	private static Map<String, ScenarioPreset> index(List<ScenarioPreset> presets) {
		LinkedHashMap<String, ScenarioPreset> indexed = new LinkedHashMap<>();
		for (ScenarioPreset preset : presets) {
			if (indexed.putIfAbsent(preset.id(), preset) != null) {
				throw ScenarioValidators.failure("DUPLICATE_SCENARIO_ID", "duplicate built-in scenario " + preset.id());
			}
		}
		return Map.copyOf(indexed);
	}

	private static ScenarioPreset lastValley() {
		return new ScenarioPreset(
				"last-valley",
				1,
				"The Last Valley",
				ScenarioCategory.SURVIVAL,
				"Establish, adapt, survive the night, then choose when to extract.",
				"Build a sustainable foothold while preserving health, responding to a seeded storm, and reaching sunrise extraction.",
				"1.0.0",
				1,
				16,
				36_000L,
				true,
				true,
				"SURVIVAL",
				"#63B56C",
				List.of(
						"Symmetric outer forest sectors",
						"Coal and iron stone ridge",
						"Animal meadow",
						"High-risk cave entrance",
						"Contested central ruin",
						"Water route and high-ground lookout"
				),
				List.of(
						"Tree fall blocks a common route",
						"Announced limited supply crate",
						"Wandering trader with constrained offers",
						"Localized cave-in or flooded passage",
						"Injured neutral mob or stranded villager choice"
				),
				List.of("survival-story", "weather", "resource-strategy", "extraction"),
				List.of(
						phase("dawn", "Dawn | Establish", 0L, 7_200L, "Gather, scout, craft, cooperate, or contest the ruin."),
						phase("forecast", "Forecast | Decide", 7_200L, 3_600L, "Visible warnings reveal the approaching storm."),
						phase("storm", "Storm | Adapt", 10_800L, 7_200L, "Rain, lightning risk, reduced visibility, and displaced mobs."),
						phase("night", "Night | Survive", 18_000L, 10_800L, "Bounded hostile pressure tests shelter and judgment."),
						phase("sunrise", "Sunrise | Extract", 28_800L, 7_200L, "Reach extraction or keep collecting score at increasing risk.")
				),
				List.of(
						rule("survival", "Survival and extraction", 20, "survival"),
						rule("health-hunger", "Health and hunger", 15, "vitals"),
						rule("progression", "Tool progression", 15, "advancement"),
						rule("food-security", "Food security", 10, "food"),
						rule("shelter", "Shelter safety and lighting", 15, "shelter"),
						rule("resources", "Resources retained", 10, "inventory-value"),
						rule("objectives", "Optional rescue and exploration", 5, "objective"),
						rule("recovery", "Recovery after setbacks", 10, "recovery")
				)
		);
	}

	private static ScenarioPreset impossibleBrief() {
		return new ScenarioPreset(
				"impossible-brief",
				1,
				"The Impossible Brief",
				ScenarioCategory.BUILDING,
				"A seeded creative constraint becomes a visible design story.",
				"Satisfy the generated theme, purpose, material, function, and spatial constraints before adapting to a midpoint twist.",
				"1.0.0",
				1,
				16,
				36_000L,
				true,
				false,
				"CONFIGURABLE",
				"#F2A65A",
				List.of(
						"Equal radial build plots",
						"Central review pavilion",
						"Identical test fixtures",
						"Mirrored palette access",
						"Continuous camera rail"
				),
				List.of(
						"Seeded theme and purpose combination",
						"Required material palette",
						"Functional interaction requirement",
						"Spatial constraint",
						"Midpoint surprise requirement"
				),
				List.of("build-reveal", "creativity", "constraint-solving", "timelapse"),
				List.of(
						phase("briefing", "Briefing", 0L, 2_400L, "Inspect the plot, palette, fixtures, and generated brief."),
						phase("foundation", "Foundation", 2_400L, 8_400L, "Commit to a plan and establish usable structure."),
						phase("construction", "Construction", 10_800L, 10_800L, "Develop form, function, traversal, and visual language."),
						phase("twist", "Midpoint Twist", 21_600L, 7_200L, "Adapt the build to one newly revealed requirement."),
						phase("finish", "Finish and Explain", 28_800L, 7_200L, "Test, clean up, finish details, and explain the design.")
				),
				List.of(
						rule("required-features", "Required features", 20, "feature"),
						rule("completion", "Completion and usable volume", 15, "completion"),
						rule("traversability", "Traversability and safety", 10, "traversal"),
						rule("lighting", "Lighting", 5, "lighting"),
						rule("functionality", "Functional tests", 15, "function"),
						rule("materials", "Material constraints", 10, "material"),
						rule("diversity", "Deliberate block patterns", 10, "block-pattern"),
						rule("efficiency", "Efficiency and cleanup", 15, "efficiency")
				)
		);
	}

	private static ScenarioPreset citadelCollapse() {
		return new ScenarioPreset(
				"citadel-collapse",
				1,
				"Citadel Collapse",
				ScenarioCategory.PVP,
				"Symmetric starts, unequal choices, and a fortress that closes around the survivors.",
				"Outlast the field through combat, looting, negotiation, objective control, and intelligent disengagement.",
				"2.0.0",
				2,
				16,
				24_000L,
				true,
				true,
				"SURVIVAL",
				"#D95D5D",
				List.of(
						"Hexagonal ruined citadel",
						"Safe outer loot",
						"Exposed central vault",
						"High-ground bow route",
						"Potion undercroft",
						"Breakable barricades",
						"Spectator ring and camera anchors"
				),
				List.of(
						"Early contested loot routes",
						"Contested vault reveal",
						"Recorded outer-sector collapse",
						"Final citadel convergence",
						"Bounded supply refresh"
				),
				List.of("pvp", "alliances", "betrayal", "combat-story"),
				List.of(
						phase("conflict", "Open Conflict", 0L, 9_600L, "PvP, looting, movement, and objectives are available immediately."),
						phase("supply", "Supply Reveal", 9_600L, 4_800L, "A contested vault or supply point is announced."),
						phase("collapse", "Collapse", 14_400L, 6_000L, "Outer sectors become unsafe in visible recorded phases."),
						phase("final", "Final Citadel", 20_400L, 3_600L, "Remaining agents converge without forced target selection.")
				),
				List.of(
						rule("placement", "Final placement", 25, "placement"),
						rule("kills-assists", "Kills and assists", 15, "elimination"),
						rule("damage-efficiency", "Damage efficiency", 10, "damage"),
						rule("objective-control", "Objective control", 15, "objective"),
						rule("equipment", "Valuable equipment", 10, "equipment"),
						rule("disengagement", "Low-health disengagement", 10, "disengagement"),
						rule("survival-time", "Survival time", 10, "survival-time"),
						rule("discipline", "Valid target discipline", 5, "discipline")
				)
		);
	}

	private static ScenarioPreset thinkingTower() {
		return new ScenarioPreset(
				"thinking-tower",
				1,
				"The Thinking Tower",
				ScenarioCategory.PARKOUR,
				"Parallel lanes reveal perception, route choice, learning, and risk tolerance.",
				"Reach the finish of your dedicated lane. Move forward over each platform, use glowing checkpoints, and choose whether to use vanilla respawn after a lava fall.",
				"1.0.0",
				1,
				16,
				24_000L,
				false,
				false,
				"ADVENTURE",
				"#8D79D6",
				List.of(
						"Identical parallel lanes",
						"Safe long routes",
						"Observation puzzle routes",
						"Short high-risk routes",
						"Visible checkpoint beacons",
						"Connected audience sightlines"
				),
				List.of("Verified checkpoint progress after lava falls"),
				List.of("parkour", "learning", "route-choice", "comeback"),
				List.of(
						phase("calibration", "Calibration", 0L, 2_400L, "Simple jumps establish movement behavior."),
						phase("ascent", "Branching Ascent", 2_400L, 6_000L, "Safe, medium, and high-risk routes become meaningful."),
						phase("observation", "Observation Puzzle", 8_400L, 4_800L, "Notice and manipulate the environment to proceed."),
						phase("adaptive", "Adaptive Section", 13_200L, 4_800L, "One seeded change invalidates the obvious route."),
						phase("escape", "Timed Escape", 18_000L, 6_000L, "Descend or exit using mechanics learned during the climb.")
				),
				List.of(
						rule("completion-time", "Completion time", 25, "completion-time"),
						rule("checkpoint", "Highest checkpoint", 20, "checkpoint"),
						rule("route-difficulty", "Route difficulty", 15, "route"),
						rule("falls", "Falls and retries", 10, "falls"),
						rule("shortcut", "Shortcut discovery", 10, "shortcut"),
						rule("puzzle", "Puzzle completion", 10, "puzzle"),
						rule("improvement", "Improvement after failure", 10, "improvement")
				)
		);
	}

	private static ScenarioPhase phase(String id, String title, long start, long duration, String description) {
		return new ScenarioPhase(id, title, start, duration, description);
	}

	private static ScenarioScoreRule rule(String id, String title, double weight, String evidenceMetric) {
		return new ScenarioScoreRule(
				id,
				title,
				"Server-authoritative evidence for " + title.toLowerCase(Locale.ROOT) + ".",
				weight,
				evidenceMetric
		);
	}
}
