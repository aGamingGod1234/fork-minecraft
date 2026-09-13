package dev.agaminggod.arenaagents.scenario;

import dev.agaminggod.arenaagents.agent.AgentGameMode;
import java.util.List;
import java.util.Optional;

public final class ScenarioLaunchRuntimeVerification {
	private ScenarioLaunchRuntimeVerification() {
	}

	public static void main(String[] args) {
		int assertions = verify();
		System.out.printf("PASS: %d scenario launch runtime assertions%n", assertions);
	}

	public static int verify() {
		ScenarioLaunchRequest request = new ScenarioLaunchRequest(
				"citadel-collapse",
				ScenarioPresets.require("citadel-collapse").mapVersion(),
				true,
				ScenarioPlacementMode.AT_PLAYER,
				List.of(
						new ScenarioAgentSpec(1, "Codex", "codex", "gpt-5.6-sol", "high", "fast",
								Optional.of("amber"), AgentGameMode.SURVIVAL),
						new ScenarioAgentSpec(2, "Gemini", "gemini", "gemini-3.1-pro", "high", "priority",
								Optional.of("azure"), AgentGameMode.SURVIVAL)
				)
		);
		assertEquals(request, ScenarioLaunchCodec.decode(ScenarioLaunchCodec.encode(request)),
				"scenario launch codec round trip");
		ScenarioLaunchRequest confirmed = new ScenarioLaunchRequest(
				request.scenarioId(), request.mapVersion(), request.deterministicEvents(),
				request.placementMode(), request.roster(), "confirm-123");
		assertEquals("confirm-123", ScenarioLaunchCodec.decode(ScenarioLaunchCodec.encode(confirmed)).confirmationToken(),
				"one-time site confirmation token survives the launch payload");
		ScenarioLaunchRequest normalizedNames = new ScenarioLaunchRequest(
				"citadel-collapse",
				ScenarioPresets.require("citadel-collapse").mapVersion(),
				true,
				ScenarioPlacementMode.AT_PLAYER,
				List.of(
						new ScenarioAgentSpec(1, "GPT-5.6-Sol", "codex", "gpt-5.6-sol", "high", "fast",
								Optional.of("amber"), AgentGameMode.SURVIVAL),
						new ScenarioAgentSpec(2, "GPT 5.6 Sol", "codex", "gpt-5.6-sol", "high", "fast",
								Optional.of("azure"), AgentGameMode.SURVIVAL)
				)
		);
		assertEquals("GPT_5_6_Sol", normalizedNames.roster().get(0).displayName(),
				"scenario launch boundary materializes a Minecraft-safe public name");
		assertEquals("GPT_5_6_Sol2", normalizedNames.roster().get(1).displayName(),
				"scenario launch boundary resolves case-insensitive canonical collisions");
		expectIllegalArgument(
				() -> new ScenarioLaunchRequest(
						"thinking-tower",
						ScenarioPresets.require("thinking-tower").mapVersion(),
						true,
						ScenarioPlacementMode.FIXED_LANE,
						List.of(new ScenarioAgentSpec(
								1,
								"Codex",
								"codex",
								"gpt-5.6-sol",
								"high",
								"priority",
								Optional.empty(),
								AgentGameMode.SURVIVAL
						))
				),
				"locked parkour mode rejects survival"
		);

		return 5;
	}

	private static void expectIllegalArgument(Runnable action, String message) {
		try {
			action.run();
		} catch (IllegalArgumentException expected) {
			return;
		}
		throw new AssertionError(message + " (expected IllegalArgumentException)");
	}

	private static void assertEquals(Object expected, Object actual, String message) {
		if (!expected.equals(actual)) {
			throw new AssertionError(message + " (expected=" + expected + ", actual=" + actual + ")");
		}
	}

}
