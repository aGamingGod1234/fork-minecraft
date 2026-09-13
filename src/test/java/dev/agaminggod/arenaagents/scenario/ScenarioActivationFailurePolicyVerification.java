package dev.agaminggod.arenaagents.scenario;

import dev.agaminggod.arenaagents.agent.AgentDomainException;
import dev.agaminggod.arenaagents.scenario.runtime.ScenarioActivationFailurePolicy;
import dev.agaminggod.arenaagents.scenario.runtime.ScenarioArenaResetJob;
import dev.agaminggod.arenaagents.scenario.runtime.ScenarioCancellationPolicy;

public final class ScenarioActivationFailurePolicyVerification {
	private ScenarioActivationFailurePolicyVerification() {
	}

	public static int verify() {
		assertTrue(ScenarioActivationFailurePolicy.retryWhenCoordinatorReturns(
				new AgentDomainException("COORDINATOR_DISCONNECTED", "not authenticated")),
				"temporary coordinator disconnect waits without invalidating the arena build");
		assertTrue(ScenarioActivationFailurePolicy.retryWhenCoordinatorReturns(
				new AgentDomainException("AUTOMATION_UNAVAILABLE", "waiting")),
				"automation startup waits without rebuilding the arena");
		assertTrue(!ScenarioActivationFailurePolicy.retryWhenCoordinatorReturns(
				new AgentDomainException("INVALID_MODEL_PROFILE", "bad model")),
				"permanent roster errors are not retried forever");
		assertTrue(!ScenarioActivationFailurePolicy.retryWhenCoordinatorReturns(
				new IllegalStateException("world failure")),
				"unrelated runtime failures remain actionable");
		assertTrue(!ScenarioActivationFailurePolicy.mayReplacePendingLaunch(false, true, false),
				"a completed arena waiting for automation requires explicit cancellation before replacement");
		assertTrue(!ScenarioActivationFailurePolicy.mayReplacePendingLaunch(true, true, false),
				"an arena still mutating the world cannot be replaced concurrently");
		assertTrue(!ScenarioActivationFailurePolicy.mayReplacePendingLaunch(false, true, true),
				"a live scenario cannot be replaced through pending-launch cleanup");
		assertTrue(!ScenarioCancellationPolicy.requiresSafeReset(ScenarioArenaResetJob.Phase.CANONICALIZE, false),
				"cancellation before mutation can release immediately");
		assertTrue(ScenarioCancellationPolicy.requiresSafeReset(ScenarioArenaResetJob.Phase.CLEAR, false),
				"cancellation after clearing begins must converge the site");
		assertTrue(ScenarioCancellationPolicy.requiresSafeReset(ScenarioArenaResetJob.Phase.LOAD_CHUNKS, true),
				"an exception forces safe recovery even if the phase normally precedes mutation");
		return 10;
	}

	private static void assertTrue(boolean condition, String label) {
		if (!condition) throw new AssertionError(label);
	}
}
