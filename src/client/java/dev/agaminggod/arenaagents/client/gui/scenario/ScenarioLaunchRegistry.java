package dev.agaminggod.arenaagents.client.gui.scenario;

import java.util.Objects;
import java.util.Optional;

public final class ScenarioLaunchRegistry {
	private static Handler handler;
	private static CancelHandler cancelHandler;
	private static ScenarioLaunchPlan lastAcceptedPlan;

	private ScenarioLaunchRegistry() {
	}

	public static synchronized void register(Handler nextHandler) {
		handler = Objects.requireNonNull(nextHandler, "nextHandler must not be null");
	}

	public static synchronized void registerCancel(CancelHandler nextHandler) {
		cancelHandler = Objects.requireNonNull(nextHandler, "nextHandler must not be null");
	}

	public static synchronized void clear() {
		handler = null;
		cancelHandler = null;
		lastAcceptedPlan = null;
	}

	public static synchronized void clearRetainedPlan() {
		lastAcceptedPlan = null;
	}

	public static synchronized boolean isAvailable() {
		return handler != null;
	}

	public static Result launch(ScenarioLaunchPlan plan) {
		Handler active;
		synchronized (ScenarioLaunchRegistry.class) {
			active = handler;
		}
		if (active == null) {
			return new Result(false, "Arena runtime is not connected");
		}
		Result result = Objects.requireNonNull(
				active.launch(Objects.requireNonNull(plan, "plan must not be null")),
				"scenario launch handler returned null"
		);
		if (result.accepted()) {
			synchronized (ScenarioLaunchRegistry.class) {
				lastAcceptedPlan = plan;
			}
		}
		return result;
	}

	public static synchronized Optional<ScenarioLaunchPlan> lastAcceptedPlan() {
		return Optional.ofNullable(lastAcceptedPlan);
	}

	public static Result cancel(String buildId) {
		CancelHandler active;
		synchronized (ScenarioLaunchRegistry.class) { active = cancelHandler; }
		if (active == null) return new Result(false, "Arena cancellation is not connected");
		return Objects.requireNonNull(active.cancel(buildId), "scenario cancel handler returned null");
	}

	@FunctionalInterface
	public interface Handler {
		Result launch(ScenarioLaunchPlan plan);
	}

	@FunctionalInterface
	public interface CancelHandler {
		Result cancel(String buildId);
	}

	public record Result(boolean accepted, String message) {
		public Result {
			message = Objects.requireNonNullElse(message, "");
		}
	}
}
