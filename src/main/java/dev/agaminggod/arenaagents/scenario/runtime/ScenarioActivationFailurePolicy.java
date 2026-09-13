package dev.agaminggod.arenaagents.scenario.runtime;

import dev.agaminggod.arenaagents.agent.AgentDomainException;
import java.util.Set;

/** Distinguishes temporary coordinator startup from permanent contestant configuration failures. */
public final class ScenarioActivationFailurePolicy {
	private static final Set<String> RETRYABLE_CODES = Set.of(
			"AUTOMATION_UNAVAILABLE",
			"COORDINATOR_DISCONNECTED",
			"MODEL_CATALOG_UNAVAILABLE"
	);

	private ScenarioActivationFailurePolicy() {
	}

	public static boolean retryWhenCoordinatorReturns(Throwable failure) {
		return failure instanceof AgentDomainException domain && RETRYABLE_CODES.contains(domain.code());
	}

	/** Pending preparation keeps ownership until the operator uses the explicit cancellation path. */
	public static boolean mayReplacePendingLaunch(boolean building, boolean pendingActivation, boolean running) {
		return false;
	}
}
