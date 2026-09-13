package dev.agaminggod.arenaagents.voiceaddon;

import de.maxhenkel.voicechat.api.VoicechatConnection;
import de.maxhenkel.voicechat.api.VoicechatServerApi;
import de.maxhenkel.voicechat.api.audiosender.AudioSender;
import de.maxhenkel.voicechat.api.events.VoiceDistanceEvent;
import de.maxhenkel.voicechat.api.opus.OpusEncoder;
import dev.agaminggod.arenaagents.agent.AgentId;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.LockSupport;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Sends synthesized speech as the managed fake player's microphone stream. */
final class SyntheticPlayerVoiceTransport implements VoicePlaybackCoordinator.Transport, AutoCloseable {
	private static final Logger LOGGER = LoggerFactory.getLogger(SyntheticPlayerVoiceTransport.class);
	private static final int SAMPLES_PER_FRAME = 960;
	private static final long FRAME_NANOS = 20_000_000L;
	private static final Map<UUID, Integer> PLAYBACK_DISTANCES = new ConcurrentHashMap<>();
	private static final Map<UUID, Object> PLAYBACK_OWNERS = new ConcurrentHashMap<>();

	private final Supplier<VoicechatServerApi> apiSupplier;
	private final Map<AgentId, UUID> entities = new LinkedHashMap<>();
	private final Map<AgentId, SenderBinding> bindings = new LinkedHashMap<>();
	private boolean closed;

	SyntheticPlayerVoiceTransport(Supplier<VoicechatServerApi> apiSupplier) {
		this.apiSupplier = Objects.requireNonNull(apiSupplier, "API supplier must not be null");
	}

	@Override
	public synchronized boolean available() {
		return !closed && apiSupplier.get() != null;
	}

	synchronized void registerAgent(AgentId agentId, UUID entityId) {
		Objects.requireNonNull(agentId, "agentId must not be null");
		Objects.requireNonNull(entityId, "entityId must not be null");
		if (closed) return;
		UUID previous = entities.put(agentId, entityId);
		if (!entityId.equals(previous)) {
			closeBinding(agentId);
		}
		try {
			ensureBinding(agentId, entityId);
		} catch (VoicePlaybackCoordinator.UnavailableException unavailable) {
			LOGGER.debug("Agent voice sender will retry when speech starts: {}", unavailable.getMessage());
		}
	}

	synchronized void unregisterAgent(AgentId agentId) {
		Objects.requireNonNull(agentId, "agentId must not be null");
		entities.remove(agentId);
		closeBinding(agentId);
	}

	@Override
	public synchronized VoicePlaybackCoordinator.Playback create(
			AgentId agentId,
			UUID entityId,
			int radius,
			short[] samples,
			Runnable onStopped,
			Runnable onFailed
	) {
		Objects.requireNonNull(agentId, "agentId must not be null");
		Objects.requireNonNull(entityId, "entityId must not be null");
		Objects.requireNonNull(samples, "samples must not be null");
		Objects.requireNonNull(onStopped, "onStopped must not be null");
		Objects.requireNonNull(onFailed, "onFailed must not be null");
		if (closed) throw unavailable("Voice sender transport is closed");
		if (!entityId.equals(entities.get(agentId))) {
			throw unavailable("Agent voice identity is stale");
		}
		if (samples.length == 0) throw unavailable("Synthesized speech is empty");
		SenderBinding binding = ensureBinding(agentId, entityId);
		OpusEncoder encoder = binding.api().createEncoder();
		if (encoder == null) throw unavailable("Simple Voice Chat rejected the Opus encoder");
		SyntheticPlayback playback = new SyntheticPlayback(agentId, binding, encoder, samples.clone(), onStopped, onFailed);
		binding.rememberPlaybackDistance(playback, radius);
		return playback;
	}

	static Integer playbackDistance(UUID entityId) {
		return PLAYBACK_DISTANCES.get(entityId);
	}

	static void applyPlaybackDistance(VoiceDistanceEvent event) {
		if (event == null || event.getSenderConnection() == null || event.getSenderConnection().getPlayer() == null) {
			return;
		}
		Integer distance = PLAYBACK_DISTANCES.get(event.getSenderConnection().getPlayer().getUuid());
		if (distance != null) event.setDistance(distance.floatValue());
	}

	private SenderBinding ensureBinding(AgentId agentId, UUID entityId) {
		VoicechatServerApi api = apiSupplier.get();
		if (api == null) throw unavailable("Simple Voice Chat server API is unavailable");
		SenderBinding existing = bindings.get(agentId);
		if (existing != null
				&& existing.api() == api
				&& existing.entityId().equals(entityId)
				&& existing.canSend()) {
			return existing;
		}
		closeBinding(agentId);
		VoicechatConnection connection = api.getConnectionOf(entityId);
		if (connection == null) throw unavailable("Simple Voice Chat has not registered the agent player yet");
		if (connection.isInstalled()) {
			throw unavailable("Agent player unexpectedly owns a real voice-chat client");
		}
		AudioSender sender = api.createAudioSender(connection);
		if (sender == null || !api.registerAudioSender(sender)) {
			throw unavailable("Simple Voice Chat rejected the agent audio sender");
		}
		if (!sender.canSend()) {
			api.unregisterAudioSender(sender);
			throw unavailable("Simple Voice Chat agent audio sender cannot send");
		}
		try {
			sender.whispering(false);
			connection.setDisabled(false);
			connection.setConnected(true);
		} catch (RuntimeException failure) {
			api.unregisterAudioSender(sender);
			throw failure;
		}
		SenderBinding created = new SenderBinding(api, entityId, connection, sender);
		bindings.put(agentId, created);
		return created;
	}

	private void closeBinding(AgentId agentId) {
		SenderBinding binding = bindings.remove(agentId);
		if (binding == null) return;
		PLAYBACK_DISTANCES.remove(binding.entityId());
		PLAYBACK_OWNERS.remove(binding.entityId());
		try {
			binding.close();
		} catch (RuntimeException failure) {
			LOGGER.warn("Agent voice sender cleanup failed for {}", agentId, failure);
		}
	}

	@Override
	public synchronized void close() {
		if (closed) return;
		closed = true;
		for (Map.Entry<AgentId, SenderBinding> entry : bindings.entrySet()) {
			PLAYBACK_DISTANCES.remove(entry.getValue().entityId());
			PLAYBACK_OWNERS.remove(entry.getValue().entityId());
			try {
				entry.getValue().close();
			} catch (RuntimeException failure) {
				LOGGER.warn("Agent voice sender cleanup failed for {}", entry.getKey(), failure);
			}
		}
		bindings.clear();
		entities.clear();
	}

	private static VoicePlaybackCoordinator.UnavailableException unavailable(String message) {
		return new VoicePlaybackCoordinator.UnavailableException(message);
	}

	private static final class SenderBinding implements AutoCloseable {
		private final VoicechatServerApi api;
		private final UUID entityId;
		private final VoicechatConnection connection;
		private final AudioSender sender;
		private final AtomicReference<SyntheticPlayback> active = new AtomicReference<>();
		private volatile boolean closed;

		private SenderBinding(
				VoicechatServerApi api,
				UUID entityId,
				VoicechatConnection connection,
				AudioSender sender
		) {
			this.api = api;
			this.entityId = entityId;
			this.connection = connection;
			this.sender = sender;
		}

		private void rememberPlaybackDistance(Object playback, int radius) {
			PLAYBACK_OWNERS.put(entityId, playback);
			PLAYBACK_DISTANCES.put(entityId, radius);
		}

		private void forgetPlaybackDistance(Object playback) {
			if (PLAYBACK_OWNERS.remove(entityId, playback)) {
				PLAYBACK_DISTANCES.remove(entityId);
			}
		}

		private VoicechatServerApi api() {
			return api;
		}

		private UUID entityId() {
			return entityId;
		}

		private boolean canSend() {
			return !closed && sender.canSend();
		}

		private boolean start(SyntheticPlayback playback, byte[] firstFrame) {
			if (closed || !sender.canSend()) return false;
			SyntheticPlayback previous = active.getAndSet(playback);
			if (previous != null && previous != playback) previous.cancelFromBinding();
			try {
				sender.reset();
				if (closed || active.get() != playback || !sender.send(firstFrame)) {
					failStart(playback);
					return false;
				}
				if (!closed && active.get() == playback) return true;
				failStart(playback);
				return false;
			} catch (RuntimeException failure) {
				failStart(playback);
				throw failure;
			}
		}

		private void failStart(SyntheticPlayback playback) {
			active.compareAndSet(playback, null);
			forgetPlaybackDistance(playback);
			try {
				sender.reset();
			} catch (RuntimeException failure) {
				LOGGER.debug("Agent voice sender reset failed after rejected start", failure);
			}
		}

		private boolean send(SyntheticPlayback playback, byte[] frame) {
			if (closed || active.get() != playback || !sender.canSend()) return false;
			boolean accepted = sender.send(frame);
			return accepted && !closed && active.get() == playback;
		}

		private void finish(SyntheticPlayback playback) {
			if (!active.compareAndSet(playback, null)) return;
			forgetPlaybackDistance(playback);
			try {
				sender.reset();
			} catch (RuntimeException failure) {
				LOGGER.debug("Agent voice sender reset failed after playback", failure);
			}
		}

		@Override
		public void close() {
			if (closed) return;
			closed = true;
			forgetPlaybackDistance(active.get());
			SyntheticPlayback current = active.getAndSet(null);
			if (current != null) current.cancelFromBinding();
			RuntimeException firstFailure = null;
			try {
				sender.reset();
			} catch (RuntimeException failure) {
				firstFailure = failure;
			}
			try {
				api.unregisterAudioSender(sender);
			} catch (RuntimeException failure) {
				if (firstFailure == null) firstFailure = failure;
				else firstFailure.addSuppressed(failure);
			}
			try {
				connection.setConnected(false);
			} catch (RuntimeException failure) {
				if (firstFailure == null) firstFailure = failure;
				else firstFailure.addSuppressed(failure);
			}
			if (firstFailure != null) throw firstFailure;
		}
	}

	private static final class SyntheticPlayback implements VoicePlaybackCoordinator.Playback {
		private final AgentId agentId;
		private final SenderBinding binding;
		private final OpusEncoder encoder;
		private final short[] samples;
		private final Runnable onStopped;
		private final Runnable onFailed;
		private final AtomicBoolean cancelled = new AtomicBoolean();
		private final AtomicBoolean finished = new AtomicBoolean();
		private volatile Thread worker;
		private volatile boolean started;

		private SyntheticPlayback(
				AgentId agentId,
				SenderBinding binding,
				OpusEncoder encoder,
				short[] samples,
				Runnable onStopped,
				Runnable onFailed
		) {
			this.agentId = agentId;
			this.binding = binding;
			this.encoder = encoder;
			this.samples = samples;
			this.onStopped = onStopped;
			this.onFailed = onFailed;
		}

		@Override
		public synchronized void start() {
			if (started) throw new IllegalStateException("Synthetic voice playback already started");
			if (cancelled.get()) throw unavailable("Synthetic voice playback was cancelled before start");
			byte[] firstFrame = encoder.encode(frameAt(0));
			if (!binding.start(this, firstFrame)) {
				closeEncoder();
				throw unavailable("Simple Voice Chat stopped accepting agent microphone packets");
			}
			started = true;
			long startedNanos = System.nanoTime();
			worker = Thread.ofVirtual()
					.name("arena-agent-voice-" + agentId)
					.start(() -> streamRemaining(startedNanos));
		}

		private void streamRemaining(long startedNanos) {
			int offset = SAMPLES_PER_FRAME;
			int sentFrames = 1;
			boolean completed = false;
			try {
				while (!cancelled.get() && offset < samples.length) {
					waitUntil(startedNanos + sentFrames * FRAME_NANOS);
					if (cancelled.get()) break;
					byte[] encoded = encoder.encode(frameAt(offset));
					if (!binding.send(this, encoded)) return;
					offset += SAMPLES_PER_FRAME;
					sentFrames++;
				}
				if (!cancelled.get()) {
					waitUntil(startedNanos + sentFrames * FRAME_NANOS);
					completed = !cancelled.get();
				}
			} catch (RuntimeException failure) {
				LOGGER.warn("Agent microphone stream failed for {}", agentId, failure);
			} finally {
				finish(completed ? Completion.STOPPED
						: cancelled.get() ? Completion.CANCELLED : Completion.FAILED);
			}
		}

		private short[] frameAt(int offset) {
			int end = Math.min(samples.length, offset + SAMPLES_PER_FRAME);
			return Arrays.copyOf(Arrays.copyOfRange(samples, offset, end), SAMPLES_PER_FRAME);
		}

		private void waitUntil(long deadlineNanos) {
			while (!cancelled.get()) {
				long remaining = deadlineNanos - System.nanoTime();
				if (remaining <= 0L) return;
				LockSupport.parkNanos(remaining);
				if (Thread.interrupted() && cancelled.get()) return;
			}
		}

		@Override
		public synchronized void stop() {
			cancelled.set(true);
			Thread current = worker;
			if (current != null) current.interrupt();
			binding.finish(this);
			if (!started) finish(Completion.CANCELLED);
		}

		private void cancelFromBinding() {
			cancelled.set(true);
			Thread current = worker;
			if (current != null) current.interrupt();
		}

		private void finish(Completion completion) {
			if (!finished.compareAndSet(false, true)) return;
			binding.finish(this);
			closeEncoder();
			if (completion == Completion.STOPPED) onStopped.run();
			else if (completion == Completion.FAILED) onFailed.run();
		}

		private void closeEncoder() {
			if (!encoder.isClosed()) encoder.close();
		}

		private enum Completion {
			STOPPED,
			FAILED,
			CANCELLED
		}
	}
}
