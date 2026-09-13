package dev.agaminggod.arenaagents.client.gui.scenario;

import dev.agaminggod.arenaagents.agent.AgentGameMode;
import dev.agaminggod.arenaagents.control.AgentControlCatalog;
import dev.agaminggod.arenaagents.control.AgentControlModelOption;
import dev.agaminggod.arenaagents.scenario.ScenarioPlacementMode;
import java.util.List;

public final class ScenarioSetupStateVerification {
	private ScenarioSetupStateVerification() {
	}

	public static int verify() {
		int assertions = 0;

		ScenarioSetupState state = ScenarioSetupState.defaults();
		assertEquals(ScenarioWizardStep.ARENA, state.step(), "arena tab starts at preset selection");
		assertEquals(ScenarioPreset.LAST_VALLEY, state.selectedScenario(), "survival preset is the safe default");
		assertEquals(1, state.roster().size(), "wizard starts with one agent");
		assertEquals("GPT_5_6_Luna", state.displayNameAt(0), "default model has a Minecraft-safe public name");
		assertEquals(AgentGameMode.SURVIVAL, state.roster().getFirst().gameMode(), "survival is the default game mode");
		assertions += 5;

		ScenarioSetupState preferred = ScenarioSetupState.defaults("gemini", "gemini-3.1-pro", "low");
		assertEquals("gemini", preferred.roster().getFirst().provider(),
				"arena setup inherits the operator's last provider preference");
		assertEquals("gemini-3.1-pro", preferred.roster().getFirst().model(),
				"arena setup inherits the operator's last model preference");
		assertEquals("low", preferred.roster().getFirst().reasoning(),
				"arena setup inherits the operator's last thinking preference");
		preferred.showBuildDashboard();
		assertEquals(ScenarioWizardStep.REVIEW, preferred.step(),
				"an active server build can reopen directly on its progress dashboard");
		assertions += 4;

		state.selectScenario(ScenarioPreset.CITADEL_COLLAPSE);
		assertEquals(2, state.roster().size(), "PvP enforces its two-agent minimum");
		assertEquals("GPT_5_6_Luna", state.displayNameAt(0), "first duplicate has no suffix");
		assertEquals("GPT_5_6_Luna2", state.displayNameAt(1), "second duplicate receives the smallest suffix");
		assertions += 3;

		state.setAgentCount(4);
		assertEquals(4, state.roster().size(), "agent count expands exactly");
		state.selectOnly(1);
		state.applyProvider("gemini");
		assertEquals("gemini", state.roster().get(1).provider(), "provider applies to the configured agent");
		assertEquals("codex", state.roster().get(3).provider(), "provider leaves the next agent unchanged");
		assertEquals("codex", state.roster().get(0).provider(), "provider leaves the previous agent unchanged");
		assertEquals("gemini-3.1-pro", state.roster().get(1).model(), "provider change selects a valid default model");
		assertEquals("high", state.roster().get(1).reasoning(), "provider change selects a valid reasoning level");
		state.selectOnly(2);
		state.applyReasoning("medium");
		assertEquals("high", state.roster().get(1).reasoning(), "moving next preserves the previous agent config");
		assertions += 6;

		state.next();
		assertEquals(ScenarioWizardStep.ROSTER, state.step(), "arena advances to roster");
		state.next();
		assertEquals(ScenarioWizardStep.REVIEW, state.step(), "roster advances to review");
		assertTrue(state.validationErrors().isEmpty(), "valid roster has no launch blockers");
		assertTrue(state.canLaunch(), "valid review can launch");
		ScenarioLaunchPlan plan = state.launchPlan();
		assertEquals("citadel-collapse", plan.scenarioId(), "launch plan preserves scenario id");
		assertEquals(ScenarioPlacementMode.IN_FRONT_OF_PLAYER, plan.placementMode(),
				"launch defaults to a visible build in front of the operator");
		assertEquals(4, plan.roster().size(), "launch plan preserves roster count");
		assertEquals(List.of(1, 2, 3, 4), plan.roster().stream().map(ScenarioLaunchPlan.Agent::slot).toList(),
				"launch plan uses stable one-based slots");
		assertTrue(!ScenarioLaunchRegistry.isAvailable(), "launch bridge is unavailable until runtime registers");
		ScenarioLaunchRegistry.register(request -> new ScenarioLaunchRegistry.Result(
				true,
				"Queued " + request.scenarioTitle()
		));
		assertTrue(ScenarioLaunchRegistry.isAvailable(), "registered runtime makes launch available");
		ScenarioLaunchRegistry.Result result = ScenarioLaunchRegistry.launch(plan);
		assertTrue(result.accepted(), "registered runtime accepts launch");
		assertEquals("Queued Citadel Collapse", result.message(), "launch result preserves runtime feedback");
		assertEquals(plan, ScenarioLaunchRegistry.lastAcceptedPlan().orElseThrow(),
				"an accepted build remains available when the failed-build screen is reopened");
		ScenarioSetupState resumed = ScenarioSetupState.fromLaunchPlan(
				ScenarioLaunchRegistry.lastAcceptedPlan().orElseThrow());
		assertEquals(plan, resumed.launchPlan(), "retry reconstruction preserves the entire configured roster");
		ScenarioLaunchRegistry.clearRetainedPlan();
		assertTrue(ScenarioLaunchRegistry.isAvailable(),
				"clearing session state keeps the registered launch transport available");
		assertTrue(ScenarioLaunchRegistry.lastAcceptedPlan().isEmpty(),
				"disconnect cleanup removes only the retained launch plan");
		ScenarioLaunchRegistry.clear();
		assertTrue(!ScenarioLaunchRegistry.isAvailable(), "clearing runtime disables launch");
		assertTrue(ScenarioLaunchRegistry.lastAcceptedPlan().isEmpty(),
				"clearing runtime also clears the session-scoped retained launch plan");
		assertions += 18;

		try {
			AgentControlCatalog.installRuntimeCatalog(List.of(new AgentControlModelOption(
					"codex", "gpt-future", "GPT Future", List.of("medium"), List.of("priority")
			)));
			ScenarioSetupState runtimeOnly = ScenarioSetupState.defaults("codex", "gpt-future", "medium");
			runtimeOnly.next();
			runtimeOnly.next();
			ScenarioLaunchPlan runtimeOnlyPlan = runtimeOnly.launchPlan();
			AgentControlCatalog.resetRuntimeCatalog();
			ScenarioSetupScreen.RetainedPlanResolution deferred = ScenarioSetupScreen.resolveRetainedPlan(
					runtimeOnlyPlan, false);
			assertTrue(deferred.state().isEmpty(), "fallback catalog cannot yet restore the runtime-only roster");
			assertTrue(!deferred.discard(), "fallback catalog defers retained-roster invalidation");

			AgentControlCatalog.installRuntimeCatalog(List.of(new AgentControlModelOption(
					"codex", "gpt-future", "GPT Future", List.of("medium"), List.of("priority")
			)));
			ScenarioSetupScreen.RetainedPlanResolution restored = ScenarioSetupScreen.resolveRetainedPlan(
					runtimeOnlyPlan, true);
			assertEquals(runtimeOnlyPlan, restored.state().orElseThrow().launchPlan(),
					"authoritative catalog restores the deferred roster exactly");
			assertTrue(!restored.discard(), "successfully restored roster remains retained");

			ScenarioSetupState staleReview = ScenarioSetupState.defaults(
					"codex", "gpt-future", "medium");
			staleReview.next();
			staleReview.next();
			assertTrue(staleReview.canLaunch(), "review is launchable before its catalog selection disappears");
			AgentControlCatalog.resetRuntimeCatalog();
			assertTrue(ScenarioSetupScreen.resolveRetainedPlan(runtimeOnlyPlan, true).discard(),
					"authoritative incompatible catalog invalidates the retained roster");
			ScenarioLaunchRegistry.Result blocked = ScenarioSetupScreen.launchSafely(staleReview);
			assertTrue(!blocked.accepted(), "catalog-invalid review is rejected without throwing");
			assertTrue(blocked.message().contains("unavailable"),
					"catalog-invalid review explains why launch was blocked");
		} finally {
			AgentControlCatalog.resetRuntimeCatalog();
			ScenarioLaunchRegistry.clear();
		}
		assertions += 8;

		state.previous();
		assertEquals(ScenarioWizardStep.ROSTER, state.step(), "previous returns to roster");
		state.setAgentCount(1);
		assertEquals(2, state.roster().size(), "PvP count cannot fall below two");
		state.selectScenario(ScenarioPreset.THINKING_TOWER);
		state.setAgentCount(1);
		assertEquals(1, state.roster().size(), "single-agent parkour is allowed");
		assertEquals(AgentGameMode.ADVENTURE, state.roster().getFirst().gameMode(),
				"scenario-required game mode is visibly enforced");
		state.selectScenario(ScenarioPreset.IMPOSSIBLE_BRIEF);
		state.applyGameMode(AgentGameMode.SURVIVAL);
		assertEquals(AgentGameMode.SURVIVAL, state.roster().getFirst().gameMode(),
				"configurable building arena accepts survival mode");
		state.selectScenario(ScenarioPreset.LAST_VALLEY);
		assertThrows(
				() -> state.applyGameMode(AgentGameMode.CREATIVE),
				"The Last Valley requires Survival",
				"locked survival arena rejects creative mode"
		);
		assertions += 6;

		return assertions;
	}

	private static void assertTrue(boolean actual, String label) {
		if (!actual) {
			throw new AssertionError(label + ": expected true");
		}
	}

	private static void assertEquals(Object expected, Object actual, String label) {
		if (!java.util.Objects.equals(expected, actual)) {
			throw new AssertionError(label + ": expected=" + expected + ", actual=" + actual);
		}
	}

	private static void assertThrows(Runnable operation, String expectedMessage, String label) {
		try {
			operation.run();
			throw new AssertionError(label + ": expected exception");
		} catch (IllegalArgumentException exception) {
			assertEquals(expectedMessage, exception.getMessage(), label + " message");
		}
	}
}
