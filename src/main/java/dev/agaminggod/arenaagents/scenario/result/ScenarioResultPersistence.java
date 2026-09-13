package dev.agaminggod.arenaagents.scenario.result;

import java.nio.file.Path;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;

/** Tick-polled durable result latch with bounded retries and no callback mutation of server state. */
public final class ScenarioResultPersistence {
	public static final long RETRY_DELAY_TICKS = 20L;

	private final MatchResultV1 result;
	private final AsyncSink sink;
	private CompletableFuture<Path> attempt;
	private Path durableArtifact;
	private int attempts;
	private long nextAttemptTick;
	private String lastFailure = "";

	public ScenarioResultPersistence(MatchResultV1 result, AsyncSink sink) {
		this.result = Objects.requireNonNull(result, "result must not be null");
		this.sink = Objects.requireNonNull(sink, "sink must not be null");
	}

	public synchronized Status poll(long tick) {
		if (tick < 0L) throw new IllegalArgumentException("tick must not be negative");
		if (durableArtifact != null) return status();
		if (attempt != null && attempt.isDone()) {
			try {
				durableArtifact = Objects.requireNonNull(attempt.join(), "durable result path must not be null");
				lastFailure = "";
			} catch (CompletionException exception) {
				lastFailure = safeMessage(exception.getCause() == null ? exception : exception.getCause());
				attempt = null;
				nextAttemptTick = tick + RETRY_DELAY_TICKS;
			}
		}
		if (durableArtifact == null && attempt == null && tick >= nextAttemptTick) {
			attempts++;
			lastFailure = "";
			try {
				attempt = Objects.requireNonNull(sink.write(result), "sink future must not be null");
			} catch (RuntimeException exception) {
				lastFailure = safeMessage(exception);
				attempt = null;
				nextAttemptTick = tick + RETRY_DELAY_TICKS;
			}
		}
		return status();
	}

	private Status status() {
		return new Status(
				durableArtifact != null,
				attempt != null,
				attempts,
				nextAttemptTick,
				Optional.ofNullable(durableArtifact),
				lastFailure
		);
	}

	private static String safeMessage(Throwable throwable) {
		String message = throwable.getMessage();
		if (message == null || message.isBlank()) message = throwable.getClass().getSimpleName();
		return message.length() <= 320 ? message : message.substring(0, 320);
	}

	@FunctionalInterface
	public interface AsyncSink {
		CompletableFuture<Path> write(MatchResultV1 result);
	}

	public record Status(
			boolean durable,
			boolean inFlight,
			int attempts,
			long nextAttemptTick,
			Optional<Path> artifact,
			String lastFailure
	) {
		public Status {
			if (attempts < 0 || nextAttemptTick < 0L) throw new IllegalArgumentException("persistence progress is invalid");
			artifact = Objects.requireNonNull(artifact, "artifact must not be null");
			lastFailure = Objects.requireNonNull(lastFailure, "lastFailure must not be null");
			if (durable != artifact.isPresent()) throw new IllegalArgumentException("durable status requires an artifact");
		}
	}
}
