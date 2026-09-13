package dev.agaminggod.arenaagents.voiceaddon;

import dev.agaminggod.arenaagents.server.voice.VoiceSubsystemConfiguration;
import java.util.Map;
import java.util.Objects;
import java.util.WeakHashMap;

/** Keeps each configured Minecraft server paired with its current voice-chat lifecycle generation. */
final class VoicechatServerBindings<S, O> {
	private final Map<S, Association> associations = new WeakHashMap<>();
	private final ServerSpeechCaptureRegistry<S, O> captures;
	private Registration pending;
	private long generation;

	VoicechatServerBindings(ServerSpeechCaptureRegistry.CaptureFactory captureFactory) {
		captures = new ServerSpeechCaptureRegistry<>(captureFactory);
	}

	synchronized void started(O owner) {
		Objects.requireNonNull(owner, "voice-chat API must not be null");
		if (pending != null && pending.live && pending.owner == owner) return;
		for (Association association : associations.values()) {
			if (association.registration.live && association.registration.owner == owner) return;
		}
		if (pending != null) pending.live = false;
		pending = new Registration(owner, ++generation);
	}

	synchronized Binding<O> configure(S server, VoiceSubsystemConfiguration configuration) {
		Objects.requireNonNull(server, "server must not be null");
		Objects.requireNonNull(configuration, "voice configuration must not be null");
		Association association = associations.get(server);
		if (association == null) {
			if (pending == null || !pending.live) {
				throw new IllegalStateException("Simple Voice Chat has no active server registration");
			}
			association = new Association(pending);
			associations.put(server, association);
			pending = null;
		} else if (!association.registration.live) {
			if (!adoptPending(association)) {
				throw new IllegalStateException("Simple Voice Chat has no active server registration");
			}
		}

		captures.configure(server, association.registration.owner, configuration);
		association.revision++;
		association.configured = true;
		association.configuration = configuration;
		return new ConfiguredBinding(server, association, association.revision);
	}

	void accept(S server, O owner, MicrophonePacketSnapshot packet) {
		synchronized (this) {
			Association association = associations.get(server);
			if (association != null && association.configured && !association.registration.live
					&& pending != null && pending.owner == owner && adoptPending(association)) {
				captures.configure(server, association.registration.owner, association.configuration);
			}
			if (association == null || !association.configured || !association.registration.live
					|| association.registration.owner != owner) return;
		}
		captures.accept(server, owner, packet);
	}

	synchronized S configuredServer(O owner) {
		S match = null;
		for (Map.Entry<S, Association> candidate : associations.entrySet()) {
			Association association = candidate.getValue();
			boolean ownsCurrent = association.configured && association.registration.live
					&& association.registration.owner == owner;
			boolean canAdoptPending = association.configured && !association.registration.live
					&& pending != null && pending.live && pending.owner == owner;
			if (!ownsCurrent && !canAdoptPending) continue;
			if (match != null && match != candidate.getKey()) return null;
			match = candidate.getKey();
		}
		return match;
	}

	void cancel(S server, java.util.UUID playerId) {
		captures.cancel(server, playerId);
	}

	synchronized void stopped(O owner) {
		if (pending != null && pending.owner == owner) {
			pending.live = false;
			pending = null;
		}
		for (Association association : associations.values()) {
			if (association.registration.owner == owner) {
				association.registration.live = false;
				association.stoppedAtGeneration = generation;
			}
		}
		captures.clearOwner(owner);
	}

	private synchronized boolean active(S server, Association expected, long revision) {
		Association association = associations.get(server);
		if (association != expected || !association.configured || association.revision != revision) return false;
		if (!association.registration.live && adoptPending(association)) {
			captures.configure(server, association.registration.owner, association.configuration);
		}
		return association.registration.live;
	}

	private synchronized O owner(S server, Association expected, long revision) {
		Association association = associations.get(server);
		if (association != expected || !association.configured || association.revision != revision) {
			throw new IllegalStateException("Simple Voice Chat configuration is no longer current");
		}
		if (!association.registration.live && adoptPending(association)) {
			captures.configure(server, association.registration.owner, association.configuration);
		}
		if (!association.registration.live) {
			throw new IllegalStateException("Simple Voice Chat has no active server registration");
		}
		return association.registration.owner;
	}

	private synchronized void clear(S server, Association expected, long revision) {
		Association association = associations.get(server);
		if (association != expected || !association.configured || association.revision != revision) return;
		captures.clear(server);
		association.configured = false;
	}

	private boolean adoptPending(Association association) {
		if (pending == null || !pending.live || pending.generation <= association.stoppedAtGeneration) return false;
		association.registration = pending;
		pending = null;
		return true;
	}

	interface Binding<O> extends AutoCloseable {
		O owner();

		boolean active();

		void cancelHumanSpeech(java.util.UUID playerId);

		@Override
		void close();
	}

	private final class ConfiguredBinding implements Binding<O> {
		private final S server;
		private final Association association;
		private final long revision;

		private ConfiguredBinding(S server, Association association, long revision) {
			this.server = server;
			this.association = association;
			this.revision = revision;
		}

		@Override
		public O owner() {
			return VoicechatServerBindings.this.owner(server, association, revision);
		}

		@Override
		public boolean active() {
			return VoicechatServerBindings.this.active(server, association, revision);
		}

		@Override
		public void cancelHumanSpeech(java.util.UUID playerId) {
			VoicechatServerBindings.this.cancel(server, playerId);
		}

		@Override
		public void close() {
			VoicechatServerBindings.this.clear(server, association, revision);
		}
	}

	private final class Association {
		private Registration registration;
		private long revision;
		private long stoppedAtGeneration;
		private boolean configured;
		private VoiceSubsystemConfiguration configuration;

		private Association(Registration registration) {
			this.registration = registration;
		}
	}

	private final class Registration {
		private final O owner;
		private final long generation;
		private boolean live = true;

		private Registration(O owner, long generation) {
			this.owner = owner;
			this.generation = generation;
		}
	}
}
