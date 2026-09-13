package dev.agaminggod.arenaagents.server.runtime.transaction;

import com.google.gson.JsonObject;
import dev.agaminggod.arenaagents.server.runtime.ElapsedTimeAccumulator;
import dev.agaminggod.arenaagents.server.runtime.ServerActionRequest;
import java.util.Objects;
import net.minecraft.server.level.ServerPlayer;

public interface ServerTransactionAdapter {
	ActiveTransaction begin(ServerPlayer player, ServerActionRequest request, JsonObject arguments);

	interface ActiveTransaction {
		TickResult tick(long nowEpochMs);

		void cancel(String reason);

		void cleanup();
	}

	enum TickState {
		RUNNING,
		SUCCEEDED,
		FAILED,
		CANCELLED,
		TIMED_OUT;

		public boolean terminal() {
			return this != RUNNING;
		}
	}

	record TickResult(TickState state, String reasonCode, String message) {
		public TickResult {
			Objects.requireNonNull(state, "state must not be null");
			if (reasonCode == null || reasonCode.isBlank()) {
				throw new IllegalArgumentException("reasonCode must not be blank");
			}
			message = Objects.requireNonNull(message, "message must not be null");
		}

		public static TickResult running() {
			return new TickResult(TickState.RUNNING, "RUNNING", "Transaction is running");
		}

		public static TickResult succeeded(String reasonCode, String message) {
			return new TickResult(TickState.SUCCEEDED, reasonCode, message);
		}

		public static TickResult failed(String reasonCode, String message) {
			return new TickResult(TickState.FAILED, reasonCode, message);
		}

		public static TickResult cancelled(String message) {
			return new TickResult(TickState.CANCELLED, "ACTION_CANCELLED", message);
		}

		public static TickResult timedOut(String reasonCode, String message) {
			return new TickResult(TickState.TIMED_OUT, reasonCode, message);
		}

		public boolean terminal() {
			return state.terminal();
		}
	}

	final class TerminalGate {
		private TickResult terminal;
		private boolean cleaned;

		public synchronized TickResult finish(TickResult candidate) {
			Objects.requireNonNull(candidate, "candidate must not be null");
			if (!candidate.terminal()) throw new IllegalArgumentException("terminal result required");
			if (terminal == null) terminal = candidate;
			return terminal;
		}

		public synchronized TickResult terminalResult() {
			return terminal;
		}

		public synchronized boolean cleanupComplete() {
			return cleaned;
		}

		public synchronized void cleanupOnce(Runnable cleanup) {
			Objects.requireNonNull(cleanup, "cleanup must not be null");
			if (cleaned) return;
			cleanup.run();
			cleaned = true;
		}
	}

	final class ConfirmedUseTimer {
		private long startedAtEpochMs = -1L;
		private ElapsedTimeAccumulator elapsed;

		public void observeStarted(boolean observedUsing, long nowEpochMs) {
			if (observedUsing && elapsed == null) {
				startedAtEpochMs = nowEpochMs;
				elapsed = new ElapsedTimeAccumulator(nowEpochMs);
			}
		}

		public boolean durationElapsed(long nowEpochMs, long requestedDurationMs) {
			return elapsed != null && elapsed.advance(nowEpochMs) >= Math.max(1L, requestedDurationMs);
		}

		public long startedAtEpochMs() {
			return startedAtEpochMs;
		}
	}

	static void runBestEffort(Runnable... steps) {
		Objects.requireNonNull(steps, "steps must not be null");
		RuntimeException first = null;
		for (Runnable step : steps) {
			try {
				Objects.requireNonNull(step, "cleanup step must not be null").run();
			} catch (RuntimeException exception) {
				if (first == null) first = exception;
				else first.addSuppressed(exception);
			}
		}
		if (first != null) throw first;
	}
}
