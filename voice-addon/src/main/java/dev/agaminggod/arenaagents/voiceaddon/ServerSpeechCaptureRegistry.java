package dev.agaminggod.arenaagents.voiceaddon;

import dev.agaminggod.arenaagents.server.voice.VoiceSubsystemConfiguration;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.function.LongSupplier;

/** Owns one lazily constructed speech capture for each configured server. */
final class ServerSpeechCaptureRegistry<S, O> {
	private static final long INITIAL_RETRY_NANOS = TimeUnit.SECONDS.toNanos(1L);
	private static final long MAX_RETRY_NANOS = TimeUnit.SECONDS.toNanos(30L);
	private static final org.slf4j.Logger LOGGER = org.slf4j.LoggerFactory.getLogger(
			ServerSpeechCaptureRegistry.class
	);
	private final Map<S, Entry> entries = new ConcurrentHashMap<>();
	private final CaptureFactory factory;
	private final LongSupplier monotonicNanos;
	private final Consumer<String> diagnosticObserver;

	ServerSpeechCaptureRegistry(CaptureFactory factory) {
		this(factory, System::nanoTime, ServerSpeechCaptureRegistry::logDiagnostic);
	}

	ServerSpeechCaptureRegistry(
			CaptureFactory factory,
			LongSupplier monotonicNanos,
			Consumer<String> diagnosticObserver
	) {
		this.factory = Objects.requireNonNull(factory, "capture factory must not be null");
		this.monotonicNanos = Objects.requireNonNull(monotonicNanos, "monotonic clock must not be null");
		this.diagnosticObserver = Objects.requireNonNull(diagnosticObserver, "diagnostic observer must not be null");
	}

	private static void logDiagnostic(String transition) {
		if ("CAPTURE_RECOVERED".equals(transition)) {
			LOGGER.info("Proximity speech capture recovered");
		} else {
			LOGGER.warn("Proximity speech capture transition: {}", transition);
		}
	}

	void configure(S server, O owner, VoiceSubsystemConfiguration configuration) {
		Objects.requireNonNull(server, "server must not be null");
		Entry previous = entries.put(server, new Entry(owner, configuration));
		if (previous != null) safeClose(previous);
	}

	void accept(S server, O owner, MicrophonePacketSnapshot packet) {
		Entry entry = entries.get(server);
		if (entry != null) entry.accept(owner, packet);
	}

	void cancel(S server, java.util.UUID playerId) {
		Entry entry = entries.get(server);
		if (entry != null) entry.cancel(playerId);
	}

	void clear(S server) {
		Entry removed = entries.remove(server);
		if (removed != null) safeClose(removed);
	}

	void clearOwner(O owner) {
		for (Map.Entry<S, Entry> candidate : entries.entrySet()) {
			Entry entry = candidate.getValue();
			if (entry.ownedBy(owner) && entries.remove(candidate.getKey(), entry)) safeClose(entry);
		}
	}

	private void safeClose(Entry entry) {
		try {
			entry.close();
		} catch (RuntimeException exception) {
			LOGGER.warn("Proximity speech capture cleanup failed ({}); voice recovery remains available",
					exception.getClass().getSimpleName());
		}
	}

	interface Capture {
		void accept(MicrophonePacketSnapshot packet);

		void cancel(java.util.UUID playerId);

		void close();
	}

	@FunctionalInterface
	interface CaptureFactory {
		Capture create(VoiceSubsystemConfiguration configuration);
	}

	private final class Entry {
		private O owner;
		private final VoiceSubsystemConfiguration configuration;
		private Capture capture;
		private boolean closed;
		private int consecutiveFailures;
		private long retryAfterNanos;
		private String diagnostic;

		private Entry(O owner, VoiceSubsystemConfiguration configuration) {
			this.owner = owner;
			this.configuration = Objects.requireNonNull(configuration, "voice configuration must not be null");
		}

		private synchronized void accept(O currentOwner, MicrophonePacketSnapshot packet) {
			if (closed) return;
			if (owner == null) owner = currentOwner;
			if (currentOwner != null && owner != currentOwner) return;
			long now = monotonicNanos.getAsLong();
			if (capture == null && now < retryAfterNanos) return;
			if (capture == null) {
				try {
					capture = Objects.requireNonNull(factory.create(configuration), "capture factory returned null");
				} catch (RuntimeException exception) {
					failed(now, "CAPTURE_FACTORY_FAILED");
					return;
				}
			}
			try {
				capture.accept(packet);
			} catch (RuntimeException exception) {
				Capture failed = capture;
				capture = null;
				try {
					failed.close();
				} catch (RuntimeException cleanupFailure) {
					exception.addSuppressed(cleanupFailure);
				}
				failed(now, "CAPTURE_FAILED");
				return;
			}
			recovered();
		}

		private void failed(long now, String transition) {
			consecutiveFailures = Math.min(consecutiveFailures + 1, 31);
			long multiplier = 1L << Math.min(consecutiveFailures - 1, 5);
			long delay = Math.min(MAX_RETRY_NANOS, INITIAL_RETRY_NANOS * multiplier);
			retryAfterNanos = now > Long.MAX_VALUE - delay ? Long.MAX_VALUE : now + delay;
			if (transition.equals(diagnostic)) return;
			diagnostic = transition;
			notifyDiagnostic(transition);
		}

		private void recovered() {
			consecutiveFailures = 0;
			retryAfterNanos = 0L;
			if (diagnostic == null) return;
			diagnostic = null;
			notifyDiagnostic("CAPTURE_RECOVERED");
		}

		private void notifyDiagnostic(String transition) {
			try {
				diagnosticObserver.accept(transition);
			} catch (RuntimeException ignored) {
				// Diagnostics cannot interrupt the microphone packet path.
			}
		}

		private synchronized boolean ownedBy(O candidate) {
			return owner == candidate;
		}

		private synchronized void cancel(java.util.UUID playerId) {
			if (!closed && capture != null) capture.cancel(playerId);
		}

		private synchronized void close() {
			if (closed) return;
			closed = true;
			if (capture != null) capture.close();
		}
	}
}
