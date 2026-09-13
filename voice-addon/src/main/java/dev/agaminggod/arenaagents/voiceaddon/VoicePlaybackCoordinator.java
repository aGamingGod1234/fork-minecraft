package dev.agaminggod.arenaagents.voiceaddon;

import dev.agaminggod.arenaagents.agent.AgentId;
import dev.agaminggod.arenaagents.server.voice.VoiceReceipt;
import dev.agaminggod.arenaagents.server.voice.VoiceRequest;
import java.net.ConnectException;
import java.net.http.HttpTimeoutException;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

final class VoicePlaybackCoordinator implements AutoCloseable {
	private static final Logger LOGGER = LoggerFactory.getLogger(VoicePlaybackCoordinator.class);
	private static final int MAX_DIAGNOSTIC_AGENTS = 64;
	private static final Supplier<VoiceReceipt> CANCELLED_RECEIPT = resolveCancelledReceipt();
	private final Synthesizer synthesizer;
	private final Executor playbackExecutor;
	private final Transport transport;
	private final Consumer<OutputLatency> latencyObserver;
	private final Consumer<DiagnosticTransition> diagnosticObserver;
	private final Map<AgentId, UUID> entities = new LinkedHashMap<>();
	private final Map<AgentId, Playback> players = new LinkedHashMap<>();
	private final Map<AgentId, CompletableFuture<VoiceReceipt>> pending = new LinkedHashMap<>();
	private final Map<AgentId, CompletableFuture<short[]>> syntheses = new LinkedHashMap<>();
	private final Map<AgentId, ActiveDiagnostic> diagnostics = new LinkedHashMap<>();
	private boolean closed;

	VoicePlaybackCoordinator(Synthesizer synthesizer, Executor playbackExecutor, Transport transport) {
		this(synthesizer, playbackExecutor, transport, ignored -> { });
	}

	VoicePlaybackCoordinator(
			Synthesizer synthesizer,
			Executor playbackExecutor,
			Transport transport,
			Consumer<OutputLatency> latencyObserver
	) {
		this(synthesizer, playbackExecutor, transport, latencyObserver,
				VoicePlaybackCoordinator::logDiagnosticTransition);
	}

	VoicePlaybackCoordinator(
			Synthesizer synthesizer,
			Executor playbackExecutor,
			Transport transport,
			Consumer<OutputLatency> latencyObserver,
			Consumer<DiagnosticTransition> diagnosticObserver
	) {
		this.synthesizer = Objects.requireNonNull(synthesizer, "synthesizer must not be null");
		this.playbackExecutor = Objects.requireNonNull(playbackExecutor, "playbackExecutor must not be null");
		this.transport = Objects.requireNonNull(transport, "transport must not be null");
		this.latencyObserver = Objects.requireNonNull(latencyObserver, "latencyObserver must not be null");
		this.diagnosticObserver = Objects.requireNonNull(diagnosticObserver, "diagnosticObserver must not be null");
	}

	synchronized boolean available() {
		return !closed && transport.available();
	}

	void registerAgent(AgentId agentId, UUID entityId) {
		Objects.requireNonNull(agentId, "agentId must not be null");
		Objects.requireNonNull(entityId, "entityId must not be null");
		boolean changed;
		synchronized (this) {
			changed = !entityId.equals(entities.get(agentId));
		}
		if (changed) stop(agentId);
		synchronized (this) {
			if (!closed) entities.put(agentId, entityId);
		}
	}

	void unregisterAgent(AgentId agentId) {
		stop(agentId);
		synchronized (this) {
			entities.remove(agentId);
			diagnostics.remove(agentId);
		}
	}

	CompletionStage<VoiceReceipt> speak(VoiceRequest request) {
		Objects.requireNonNull(request, "request must not be null");
		long requestedNanos = System.nanoTime();
		synchronized (this) {
			VoiceReceipt unavailable = availabilityFailure(request);
			if (unavailable != null) return CompletableFuture.completedFuture(unavailable);
		}
		stop(request.agentId());
		CompletableFuture<VoiceReceipt> result = new CompletableFuture<>();
		synchronized (this) {
			VoiceReceipt unavailable = availabilityFailure(request);
			if (unavailable != null) return CompletableFuture.completedFuture(unavailable);
			pending.put(request.agentId(), result);
		}
		CompletableFuture<short[]> synthesis;
		try {
			synthesis = Objects.requireNonNull(
					Objects.requireNonNull(synthesizer.synthesize(request), "synthesizer returned null")
							.toCompletableFuture(),
					"synthesizer returned a null future"
			);
		} catch (RuntimeException exception) {
			completeFallback(request, result, Boundary.SYNTHESIS, failureDiagnostic(Boundary.SYNTHESIS, exception));
			return result;
		}
		synchronized (this) {
			if (pending.get(request.agentId()) != result) {
				synthesis.cancel(true);
				return result;
			}
			syntheses.put(request.agentId(), synthesis);
		}
		synthesis.whenComplete((samples, failure) -> {
			long synthesisCompletedNanos = System.nanoTime();
			synchronized (this) {
				syntheses.remove(request.agentId(), synthesis);
			}
			if (failure != null) {
				completeFallback(request, result, Boundary.SYNTHESIS, failureDiagnostic(Boundary.SYNTHESIS, failure));
				return;
			}
			try {
				playbackExecutor.execute(() -> startPlayback(
						request, samples, result, requestedNanos, synthesisCompletedNanos
				));
			} catch (RuntimeException exception) {
				completeFallback(request, result, Boundary.PLAYBACK, failureDiagnostic(Boundary.PLAYBACK, exception));
			}
		});
		return result;
	}

	private void startPlayback(
			VoiceRequest request,
			short[] samples,
			CompletableFuture<VoiceReceipt> result,
			long requestedNanos,
			long synthesisCompletedNanos
	) {
		UUID entityId;
		synchronized (this) {
			if (pending.get(request.agentId()) != result) return;
			if (closed) {
				completeDegraded(request, result, Boundary.PLAYBACK, new Diagnostic(
						"VOICE_PLAYBACK_CLOSED", "Voice playback closed before audio could start"
				));
				return;
			}
			if (!transport.available()) {
				completeDegraded(request, result, Boundary.PLAYBACK, new Diagnostic(
						"VOICE_TRANSPORT_UNAVAILABLE", "Simple Voice Chat is unavailable before playback"
				));
				return;
			}
			entityId = entities.get(request.agentId());
			if (entityId == null) {
				completeDegraded(request, result, Boundary.PLAYBACK, new Diagnostic(
						"VOICE_AGENT_UNREGISTERED", "Agent voice entity is not registered"
				));
				return;
			}
		}
		Playback playback;
		try {
			playback = Objects.requireNonNull(transport.create(
					request.agentId(), entityId, request.radius(), samples,
					() -> completePlayed(request.agentId(), result),
					() -> completeDegraded(request, result, Boundary.PLAYBACK, new Diagnostic(
							"VOICE_PLAYBACK_STREAM_FAILED", "Voice playback stopped before all audio was sent"
					))
			), "transport returned null playback");
		} catch (UnavailableException exception) {
			completeDegraded(request, result, Boundary.PLAYBACK, new Diagnostic(
					"VOICE_PLAYBACK_UNAVAILABLE", exception.getMessage()
			));
			return;
		} catch (RuntimeException exception) {
			completeFallback(request, result, Boundary.PLAYBACK, failureDiagnostic(Boundary.PLAYBACK, exception));
			return;
		}
		synchronized (this) {
			if (closed || pending.get(request.agentId()) != result) {
				playback.stop();
				return;
			}
			players.put(request.agentId(), playback);
		}
		try {
			playback.start();
			reportLatency(request, requestedNanos, synthesisCompletedNanos, System.nanoTime());
		} catch (RuntimeException exception) {
			completeFallback(request, result, Boundary.PLAYBACK, failureDiagnostic(Boundary.PLAYBACK, exception));
			try {
				playback.stop();
			} catch (RuntimeException ignored) {
			}
		}
	}

	private boolean complete(AgentId agentId, CompletableFuture<VoiceReceipt> expected, VoiceReceipt receipt) {
		synchronized (this) {
			if (pending.get(agentId) != expected) return false;
			pending.remove(agentId);
			players.remove(agentId);
		}
		expected.complete(receipt);
		return true;
	}

	private void completePlayed(AgentId agentId, CompletableFuture<VoiceReceipt> expected) {
		if (!complete(agentId, expected, new VoiceReceipt(
				VoiceReceipt.Status.PLAYED, "Speech playback finished"
		))) return;
		ActiveDiagnostic recovered;
		synchronized (this) {
			recovered = diagnostics.remove(agentId);
		}
		if (recovered != null) publishDiagnostic(new DiagnosticTransition(
				agentId, recovered.diagnostic().code(), recovered.boundary().label, true
		));
	}

	private VoiceReceipt availabilityFailure(VoiceRequest request) {
		Diagnostic diagnostic;
		if (closed) {
			diagnostic = new Diagnostic("VOICE_SUBSYSTEM_CLOSED", "Voice subsystem is closed");
		} else if (!transport.available()) {
			diagnostic = new Diagnostic("VOICE_TRANSPORT_UNAVAILABLE", "Simple Voice Chat server API is unavailable");
		} else if (!entities.containsKey(request.agentId())) {
			diagnostic = new Diagnostic("VOICE_AGENT_UNREGISTERED", "Agent voice entity is not registered");
		} else {
			return null;
		}
		reportFallback(request, Boundary.AVAILABILITY, diagnostic);
		return VoiceReceipt.degraded(diagnostic.message());
	}

	private void completeFallback(
			VoiceRequest request,
			CompletableFuture<VoiceReceipt> expected,
			Boundary boundary,
			Diagnostic diagnostic
	) {
		completeFallback(request, expected, boundary, diagnostic,
				new VoiceReceipt(VoiceReceipt.Status.FAILED, diagnostic.message()));
	}

	private void completeDegraded(
			VoiceRequest request,
			CompletableFuture<VoiceReceipt> expected,
			Boundary boundary,
			Diagnostic diagnostic
	) {
		completeFallback(request, expected, boundary, diagnostic, VoiceReceipt.degraded(diagnostic.message()));
	}

	private void completeFallback(
			VoiceRequest request,
			CompletableFuture<VoiceReceipt> expected,
			Boundary boundary,
			Diagnostic diagnostic,
			VoiceReceipt receipt
	) {
		if (complete(request.agentId(), expected, receipt)) reportFallback(request, boundary, diagnostic);
	}

	private void reportFallback(VoiceRequest request, Boundary boundary, Diagnostic diagnostic) {
		recordDiagnostic(request.agentId(), boundary, diagnostic);
	}

	private void recordDiagnostic(AgentId agentId, Boundary boundary, Diagnostic diagnostic) {
		ActiveDiagnostic transition = new ActiveDiagnostic(boundary, diagnostic);
		synchronized (this) {
			if (transition.equals(diagnostics.get(agentId))) return;
			if (!diagnostics.containsKey(agentId) && diagnostics.size() >= MAX_DIAGNOSTIC_AGENTS) {
				diagnostics.remove(diagnostics.keySet().iterator().next());
			}
			diagnostics.put(agentId, transition);
		}
		publishDiagnostic(new DiagnosticTransition(agentId, diagnostic.code(), boundary.label, false));
	}

	private void publishDiagnostic(DiagnosticTransition transition) {
		try {
			diagnosticObserver.accept(transition);
		} catch (RuntimeException ignored) {
			// Diagnostics are observational and cannot reject voice or control work.
		}
	}

	private static void logDiagnosticTransition(DiagnosticTransition transition) {
		if (transition.recovered()) {
			LOGGER.info("Proximity voice recovered from [{}] at {} boundary for agent {}",
					transition.code(), transition.boundary(), transition.agentId());
		} else {
			LOGGER.warn("Proximity voice entered text fallback [{}] at {} boundary for agent {}",
					transition.code(), transition.boundary(), transition.agentId());
		}
	}

	void stop(AgentId agentId) {
		Playback player;
		CompletableFuture<VoiceReceipt> future;
		CompletableFuture<short[]> synthesis;
		synchronized (this) {
			player = players.remove(agentId);
			future = pending.remove(agentId);
			synthesis = syntheses.remove(agentId);
		}
		if (future != null) future.complete(CANCELLED_RECEIPT.get());
		if (synthesis != null) synthesis.cancel(true);
		if (player != null) {
			try {
				player.stop();
			} catch (RuntimeException exception) {
				recordDiagnostic(agentId, Boundary.PLAYBACK, new Diagnostic(
						"VOICE_PLAYBACK_CLEANUP_FAILED", "Voice playback cleanup failed"
				));
			}
		}
	}

	private static Supplier<VoiceReceipt> resolveCancelledReceipt() {
		try {
			VoiceReceipt.class.getMethod("cancelled");
			return ModernCancelledReceipt.INSTANCE;
		} catch (NoSuchMethodException legacyCore) {
			return () -> VoiceReceipt.degraded("[VOICE_CANCELLED] Speech cancelled");
		}
	}

	@Override
	public void close() {
		Set<AgentId> active;
		synchronized (this) {
			if (closed) return;
			closed = true;
			active = new LinkedHashSet<>(pending.keySet());
			active.addAll(players.keySet());
		}
		for (AgentId agentId : active) stop(agentId);
		synchronized (this) {
			entities.clear();
			diagnostics.clear();
		}
	}

	private static Diagnostic failureDiagnostic(Boundary boundary, Throwable throwable) {
		Throwable cause = unwrap(throwable);
		if (cause instanceof VoiceWorkerClient.VoiceWorkerException workerFailure) {
			return new Diagnostic(workerFailure.code(), safeWorkerReason(workerFailure));
		}
		if (cause instanceof ConnectException) {
			return new Diagnostic("VOICE_WORKER_UNAVAILABLE", "Voice worker is not reachable");
		}
		if (cause instanceof HttpTimeoutException) {
			return new Diagnostic("VOICE_WORKER_TIMEOUT", "Voice worker request timed out");
		}
		return boundary == Boundary.SYNTHESIS
				? new Diagnostic("VOICE_SYNTHESIS_FAILED", "Voice synthesis failed (" + cause.getClass().getSimpleName() + ")")
				: new Diagnostic("VOICE_PLAYBACK_FAILED", "Voice playback failed (" + cause.getClass().getSimpleName() + ")");
	}

	private static Throwable unwrap(Throwable throwable) {
		Throwable current = Objects.requireNonNull(throwable, "throwable must not be null");
		while ((current instanceof CompletionException || current instanceof ExecutionException)
				&& current.getCause() != null) {
			current = current.getCause();
		}
		return current;
	}

	private static String safeWorkerReason(VoiceWorkerClient.VoiceWorkerException failure) {
		return switch (failure.code()) {
			case "VOICE_WORKER_HTTP" -> boundedKnownReason(
					failure.getMessage(), "Voice worker request failed", "Voice worker returned HTTP "
			);
			case "VOICE_WORKER_AUDIO" -> "Voice worker returned invalid audio";
			case "VOICE_SECRET_UNAVAILABLE" -> "Voice worker authentication is unavailable";
			default -> "Voice worker request failed";
		};
	}

	private static String boundedKnownReason(String message, String fallback, String allowedPrefix) {
		if (message == null || !message.startsWith(allowedPrefix) || message.length() > 96) return fallback;
		return message;
	}

	private enum Boundary {
		AVAILABILITY("availability"),
		SYNTHESIS("synthesis"),
		PLAYBACK("playback");

		private final String label;

		Boundary(String label) {
			this.label = label;
		}
	}

	private void reportLatency(
			VoiceRequest request,
			long requestedNanos,
			long synthesisCompletedNanos,
			long playbackStartedNanos
	) {
		try {
			latencyObserver.accept(new OutputLatency(
					request.agentId(),
					request.conversationSequence(),
					TimeUnit.NANOSECONDS.toMillis(Math.max(0L, synthesisCompletedNanos - requestedNanos)),
					TimeUnit.NANOSECONDS.toMillis(Math.max(0L, playbackStartedNanos - requestedNanos))
			));
		} catch (RuntimeException ignored) {
			// Timing diagnostics must never interrupt voice playback.
		}
	}

	private record Diagnostic(String code, String reason) {
		private Diagnostic {
			Objects.requireNonNull(code, "code must not be null");
			Objects.requireNonNull(reason, "reason must not be null");
		}

		private String message() {
			return "[" + code + "] " + reason;
		}
	}

	private record ActiveDiagnostic(Boundary boundary, Diagnostic diagnostic) {
	}

	record DiagnosticTransition(AgentId agentId, String code, String boundary, boolean recovered) {
		DiagnosticTransition {
			Objects.requireNonNull(agentId, "agentId must not be null");
			Objects.requireNonNull(code, "code must not be null");
			Objects.requireNonNull(boundary, "boundary must not be null");
			if (code.length() > 64 || boundary.length() > 32) {
				throw new IllegalArgumentException("diagnostic transition fields are not bounded");
			}
		}
	}

	@FunctionalInterface
	interface Synthesizer {
		CompletionStage<short[]> synthesize(VoiceRequest request);
	}

	interface Transport {
		boolean available();

		Playback create(
				AgentId agentId,
				UUID entityId,
				int radius,
				short[] samples,
				Runnable onStopped,
				Runnable onFailed
		);
	}

	interface Playback {
		void start();

		void stop();
	}

	record OutputLatency(
			AgentId agentId,
			long conversationSequence,
			long synthesisMilliseconds,
			long firstPlaybackMilliseconds
	) {
	}

	static final class UnavailableException extends RuntimeException {
		UnavailableException(String message) {
			super(message);
		}
	}

	private static final class ModernCancelledReceipt implements Supplier<VoiceReceipt> {
		private static final ModernCancelledReceipt INSTANCE = new ModernCancelledReceipt();

		@Override
		public VoiceReceipt get() {
			return VoiceReceipt.cancelled();
		}
	}
}
