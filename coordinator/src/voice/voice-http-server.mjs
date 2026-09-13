import { createServer } from 'node:http';
import { createHash, createHmac, randomBytes, timingSafeEqual } from 'node:crypto';

import { NoSttProvider } from './deepgram-stt-provider.mjs';
import { resampleS16leMono } from './pcm-audio.mjs';
import { TtsCache } from './tts-cache.mjs';
import { providerCacheNamespace, synthesisCacheNamespace } from './tts-cache-identity.mjs';
import { builtInVoiceProfiles } from './voice-profile-store.mjs';

const MAX_REQUEST_BYTES = 8 * 1024;
const DEFAULT_REQUEST_TIMEOUT_MS = 30_000;
const DEFAULT_INITIAL_PROBE_DELAY_MS = 1_000;
const DEFAULT_MAX_PROBE_DELAY_MS = 30_000;
const DEFAULT_PROBE_TIMEOUT_MS = 10_000;
const PROBE_PROFILE = builtInVoiceProfiles()[0];
const AUTH_VERSION = 'arena-voice-v1';
const AUTH_MAX_CLOCK_SKEW_MS = 30_000;
const AUTH_NONCE_BYTES = 24;
const MAX_AUTH_NONCES = 2_048;
const DEFAULT_STT_PLAYER_MIN_INTERVAL_MS = 1_000;

export function createVoiceHttpServer({
	provider,
	sttProvider = null,
	profileStore,
	secret,
	cache = new TtsCache(),
	host = '127.0.0.1',
	port = 8_766,
	maxConcurrent = 5,
	reservedStt = maxConcurrent > 1 ? 1 : 0,
	sttPlayerMinIntervalMs = DEFAULT_STT_PLAYER_MIN_INTERVAL_MS,
	requestTimeoutMs = DEFAULT_REQUEST_TIMEOUT_MS,
	now = Date.now,
	scheduleProbe = defaultSchedule,
	cancelProbe = clearTimeout,
	initialProbeDelayMs = DEFAULT_INITIAL_PROBE_DELAY_MS,
	maxProbeDelayMs = DEFAULT_MAX_PROBE_DELAY_MS,
	probeTimeoutMs = DEFAULT_PROBE_TIMEOUT_MS,
	onDiagnostic = null,
} = {}) {
	if (provider !== null && typeof provider?.synthesize !== 'function') throw new TypeError('provider.synthesize is required');
	if (profileStore === null || typeof profileStore?.resolve !== 'function') throw new TypeError('profileStore.resolve is required');
	if (typeof secret !== 'string' || secret.length < 16) throw new TypeError('voice secret must contain at least 16 characters');
	if (host !== '127.0.0.1' && host !== '::1') throw new TypeError('voice server must bind to loopback');
	if (!Number.isSafeInteger(port) || port < 0 || port > 65_535) throw new TypeError('port is invalid');
	if (!Number.isSafeInteger(maxConcurrent) || maxConcurrent < 1 || maxConcurrent > 5) throw new TypeError('maxConcurrent must be between 1 and 5');
	if (!Number.isSafeInteger(reservedStt) || reservedStt < 0 || reservedStt >= maxConcurrent) {
		throw new TypeError('reservedStt must be between 0 and maxConcurrent - 1');
	}
	if (!Number.isSafeInteger(sttPlayerMinIntervalMs) || sttPlayerMinIntervalMs < 1 || sttPlayerMinIntervalMs > 60_000) {
		throw new TypeError('sttPlayerMinIntervalMs must be between 1 and 60000');
	}
	if (!Number.isSafeInteger(requestTimeoutMs) || requestTimeoutMs < 1 || requestTimeoutMs > 600_000) throw new TypeError('requestTimeoutMs must be between 1 and 600000');
	if (typeof now !== 'function' || typeof scheduleProbe !== 'function' || typeof cancelProbe !== 'function') {
		throw new TypeError('voice probe clock and scheduler must be functions');
	}
	if (onDiagnostic !== null && typeof onDiagnostic !== 'function') throw new TypeError('onDiagnostic must be a function');
	for (const [name, value] of Object.entries({ initialProbeDelayMs, maxProbeDelayMs, probeTimeoutMs })) {
		if (!Number.isSafeInteger(value) || value < 1 || value > 120_000) throw new TypeError(`${name} must be between 1 and 120000`);
	}
	if (maxProbeDelayMs < initialProbeDelayMs) throw new TypeError('maxProbeDelayMs must not be less than initialProbeDelayMs');

	let active = 0;
	let activeTts = 0;
	let activeProviderTts = 0;
	const maxProviderTts = maxConcurrent * 2;
	let providerTtsStalled = false;
	let activeStt = 0;
	let pendingAuthentication = 0;
	const controllers = new Set();
	const inFlightTts = new Map();
	const preauthenticationRequests = new Set();
	const failureListeners = new Set();
	let startPromise = null;
	let closePromise = null;
	let live = false;
	let closing = false;
	let terminalFailure = null;
	let reportedTtsProvider = null;
	const authenticatedNonces = new Map();
	const activeSttPlayers = new Set();
	const sttNextAllowedAt = new Map();
	const lifecycleOptions = {
		now, schedule: scheduleProbe, cancelSchedule: cancelProbe,
		initialRetryMs: initialProbeDelayMs, maxRetryMs: maxProbeDelayMs, probeTimeoutMs,
	};
	const ttsLifecycle = new VoiceChannelLifecycle({
		component: 'voice:tts', boundary: 'voice_tts_provider',
		probe: provider === null ? null : (signal) => probeTts(provider, signal),
		onStalled: (error) => recordTerminalFailure(error),
		initialFailureCode: provider === null ? 'TTS_UNAVAILABLE' : null,
		...lifecycleOptions,
	});
	const sttUnavailable = sttProvider === null || sttProvider instanceof NoSttProvider;
	const effectiveReservedStt = sttUnavailable ? 0 : reservedStt;
	const sttLifecycle = new VoiceChannelLifecycle({
		component: 'voice:stt', boundary: 'voice_stt_provider',
		probe: sttUnavailable ? null : (signal) => probeStt(sttProvider, signal),
		onStalled: (error) => recordTerminalFailure(error),
		initialFailureCode: sttUnavailable ? 'STT_UNAVAILABLE' : null,
		...lifecycleOptions,
	});
	const server = createServer(async (request, response) => {
		if (request.method === 'GET' && request.url === '/health') {
			const admittedReservedStt = currentReservedStt();
			respondJson(response, 200, {
				ready: true,
				active,
				activeTts,
				activeStt,
				pendingAuthentication,
				maxConcurrent,
				reservedStt: admittedReservedStt,
			});
			return;
		}
		if (request.method !== 'POST' || !['/v1/tts', '/v1/stt'].includes(request.url)) {
			respondJsonAndClose(request, response, 404, { code: 'NOT_FOUND' });
			return;
		}
		const preparedAuthentication = prepareRequestAuthentication(request, authenticatedNonces, Date.now());
		if (preparedAuthentication === null) {
			respondJsonAndClose(request, response, 401, { code: 'UNAUTHORIZED' });
			return;
		}
		const maximumBytes = request.url === '/v1/stt' ? 48_000 * 2 * 20 : MAX_REQUEST_BYTES;
		try {
			validateDeclaredContentLength(request.headers['content-length'], maximumBytes);
		} catch (error) {
			respondJsonAndClose(request, response, statusFor(error), {
				code: String(error?.code ?? 'INVALID_REQUEST').slice(0, 64),
				message: String(error?.message ?? error).slice(0, 256),
			});
			return;
		}
		if (pendingAuthentication >= maxConcurrent) {
			respondJsonAndClose(request, response, 429, { code: 'VOICE_AUTH_CAPACITY' });
			return;
		}
		pendingAuthentication += 1;
		preauthenticationRequests.add(request);
		let requestBody;
		let authentication = null;
		try {
			requestBody = await readRequestBody(request, maximumBytes, requestTimeoutMs);
			authentication = authenticateRequest(
				request, secret, authenticatedNonces, preparedAuthentication, requestBody,
			);
		} catch (error) {
			if (!response.headersSent && !response.destroyed) respondJson(response, statusFor(error), {
				code: String(error?.code ?? 'INVALID_REQUEST').slice(0, 64),
				message: String(error?.message ?? error).slice(0, 256),
			});
			return;
		} finally {
			pendingAuthentication = Math.max(0, pendingAuthentication - 1);
			preauthenticationRequests.delete(request);
		}
		if (authentication === null) {
			respondJson(response, 401, { code: 'UNAUTHORIZED' });
			return;
		}
		const channel = request.url === '/v1/stt' ? 'stt' : 'tts';
		const ttsLimit = maxConcurrent - currentReservedStt();
		if (active >= maxConcurrent || (channel === 'tts' && activeTts >= ttsLimit)) {
			respondAuthenticatedJson(response, 429, {
				code: channel === 'stt' ? 'STT_CAPACITY' : 'TTS_CAPACITY',
			}, secret, authentication.nonce);
			return;
		}
		const controller = new AbortController();
		controllers.add(controller);
		let released = false;
		let providerOperation = null;
		let completeSharedTtsRequest = null;
		let sttPlayerId = null;
		active += 1;
		if (channel === 'stt') activeStt += 1;
		else activeTts += 1;
		const release = () => {
			if (released) return;
			released = true;
			active -= 1;
			if (channel === 'stt') activeStt -= 1;
			else activeTts -= 1;
			if (sttPlayerId !== null) activeSttPlayers.delete(sttPlayerId);
			controllers.delete(controller);
		};
		const onRequestAborted = () => controller.abort();
		const onResponseClosed = () => {
			if (!response.writableFinished) controller.abort();
		};
		request.once('aborted', onRequestAborted);
		response.once('close', onResponseClosed);
		const timeout = setTimeout(() => {
			const error = typedError(request.url === '/v1/stt' ? 'STT_TIMEOUT' : 'TTS_TIMEOUT', 'Voice provider request timed out');
			error.name = 'TimeoutError';
			controller.abort(error);
		}, requestTimeoutMs);
		let attemptedLifecycle = null;
		try {
			if (request.url === '/v1/stt') {
				requireContentType(request.headers['content-type'], 'audio/l16;rate=48000;channels=1');
				if (sttProvider === null || typeof sttProvider.transcribe !== 'function') {
					throw typedError('STT_UNAVAILABLE', 'Speech recognition is not configured');
				}
				const metadata = validateSttHeaders(request.headers);
				const playerKey = metadata.playerId.toLowerCase();
				const currentTime = Date.now();
				for (const [candidate, retryAt] of sttNextAllowedAt) {
					if (retryAt <= currentTime && !activeSttPlayers.has(candidate)) sttNextAllowedAt.delete(candidate);
				}
				if (activeSttPlayers.has(playerKey)) {
					throw typedError('STT_PLAYER_BUSY', 'A transcription is already active for this player');
				}
				if (currentTime < (sttNextAllowedAt.get(playerKey) ?? 0)) {
					throw typedError('STT_RATE_LIMITED', 'Player transcription rate limit exceeded');
				}
				activeSttPlayers.add(playerKey);
				sttPlayerId = playerKey;
				sttNextAllowedAt.set(playerKey, currentTime + sttPlayerMinIntervalMs);
				const pcm = validatePcmBody(requestBody);
				attemptedLifecycle = sttLifecycle;
				providerOperation = Promise.resolve().then(() => sttProvider.transcribe({
					pcm,
					signal: controller.signal,
				}));
				const result = validateTranscriptResult(await awaitAbortable(providerOperation, controller.signal));
				sttLifecycle.recordReady();
				respondAuthenticatedJson(response, 200, {
					playerId: metadata.playerId,
					utteranceSequence: metadata.utteranceSequence,
					whispering: metadata.whispering,
					transcript: result.transcript,
					confidence: result.confidence,
				}, secret, authentication.nonce);
				return;
			}
			requireJsonContentType(request.headers['content-type']);
			const payload = validateRequest(parseJson(requestBody));
			if (provider === null) {
				attemptedLifecycle = ttsLifecycle;
				const error = typedError('TTS_UNAVAILABLE', 'Speech synthesis is not configured');
				error.httpStatus = 503;
				throw error;
			}
			const selectedProfile = typeof profileStore.resolveRequested === 'function'
				? profileStore.resolveRequested(payload.agentId, payload.profileId)
				: profileStore.resolve(payload.agentId);
			const profile = Object.freeze({
				...selectedProfile,
				speed: payload.profileId === 'voice.auto.v1' ? selectedProfile.speed : payload.speed,
			});
			const requestedProvider = effectiveProviderNamespace(provider);
			const cacheKey = synthesisCacheKey(profile, payload, requestedProvider);
			const cached = cache.get(cacheKey);
			let synthesis;
			if (cached === null) {
				attemptedLifecycle = ttsLifecycle;
				const joined = joinTtsSynthesis({
					cacheKey,
					profile,
					payload,
					signal: controller.signal,
				});
				attemptedLifecycle = null;
				completeSharedTtsRequest = joined.complete;
				synthesis = await joined.waiter;
			} else {
				synthesis = Object.freeze({ pcm: cached, effectiveProvider: requestedProvider });
			}
			reportEffectiveTtsProvider(synthesis.effectiveProvider);
			respondAuthenticatedBytes(response, 200, synthesis.pcm, 'audio/l16', secret, authentication.nonce, {
				'X-Audio-Sample-Rate': '48000',
				'X-Audio-Channels': '1',
				'X-Voice-Profile': profile.profileId,
				'X-Voice-Synthesizer': synthesis.effectiveProvider,
				'Cache-Control': 'private, immutable',
			});
		} catch (error) {
			const recordLifecycle = attemptedLifecycle !== null && error?.name !== 'AbortError';
			if (!response.headersSent) respondAuthenticatedJson(response, statusFor(error), {
				code: String(error?.code ?? 'TTS_ERROR').slice(0, 64),
				message: String(error?.message ?? error).slice(0, 256),
			}, secret, authentication.nonce, retryAfterHeaders(error));
			if (recordLifecycle) attemptedLifecycle.recordFailure(error);
		} finally {
			clearTimeout(timeout);
			request.off('aborted', onRequestAborted);
			response.off('close', onResponseClosed);
			if (completeSharedTtsRequest !== null) completeSharedTtsRequest(release);
			else if (providerOperation === null) release();
			else providerOperation.then(release, release);
		}
	});

	function currentReservedStt() {
		return effectiveReservedStt > 0 && sttLifecycle.snapshot().state === 'ready' ? effectiveReservedStt : 0;
	}

	function joinTtsSynthesis({ cacheKey, profile, payload, signal }) {
		let entry = inFlightTts.get(cacheKey);
		if (entry === undefined) {
			if (providerTtsStalled || activeProviderTts >= maxProviderTts) {
				providerTtsStalled = true;
				ttsLifecycle.recordCapacityStalled();
				throw typedError('TTS_CAPACITY', 'Speech synthesis provider did not acknowledge cancellation; this worker is fail-closed');
			}
			const providerController = new AbortController();
			entry = {
				providerController,
				waiters: 0,
				settled: false,
				failed: false,
				failure: undefined,
				failureRecorded: false,
				operation: null,
			};
			activeProviderTts += 1;
			const current = entry;
			entry.operation = Promise.resolve().then(async () => {
				try {
					const synthesized = validateSynthesis(await provider.synthesize({
						text: payload.text,
						voiceId: profile.voiceId,
						speed: profile.speed,
						tone: payload.tone,
						signal: providerController.signal,
					}));
					const output = resampleS16leMono(synthesized.pcm, synthesized.sampleRateHz, 48_000, 20);
					if (output.length === 0) throw typedError('TTS_MALFORMED_AUDIO', 'TTS output was empty');
					const completedProvider = effectiveSynthesisNamespace(synthesized, provider);
					if (!providerController.signal.aborted && synthesized.cacheable !== false) {
						const completedKey = synthesisCacheKey(
							profile,
							payload,
							completedProvider,
						);
						cache.set(completedKey, output);
					}
					if (!providerController.signal.aborted) ttsLifecycle.recordReady();
					return Object.freeze({ pcm: output, effectiveProvider: completedProvider });
				} catch (error) {
					current.failed = true;
					current.failure = error;
					throw error;
				} finally {
					activeProviderTts -= 1;
					current.settled = true;
					if (inFlightTts.get(cacheKey) === current) inFlightTts.delete(cacheKey);
					if (current.waiters === 0) recordTtsFlightFailure(current);
				}
			});
			entry.operation.catch(() => {});
			inFlightTts.set(cacheKey, entry);
		}
		entry.waiters += 1;
		const current = entry;
		const waiter = awaitAbortable(current.operation, signal);
		let completed = false;
		return {
			waiter,
			complete(release) {
				if (completed) return;
				completed = true;
				current.waiters -= 1;
				if (current.waiters === 0 && !current.settled) {
					if (inFlightTts.get(cacheKey) === current) inFlightTts.delete(cacheKey);
					current.providerController.abort(abortError('All synthesis waiters cancelled'));
				}
				release();
				if (current.waiters === 0 && current.settled) recordTtsFlightFailure(current);
			},
		};
	}

	function reportEffectiveTtsProvider(effectiveProvider) {
		if (effectiveProvider === reportedTtsProvider) return;
		reportedTtsProvider = effectiveProvider;
		try {
			onDiagnostic?.(Object.freeze({ code: 'VOICE_TTS_EFFECTIVE_PROVIDER', effectiveProvider }));
		} catch { /* provider diagnostics are observational */ }
	}

	function ttsStatusSnapshot() {
		return Object.freeze({
			...ttsLifecycle.snapshot(),
			effectiveProvider: effectiveProviderNamespace(provider),
		});
	}

	function recordTtsFlightFailure(entry) {
		if (entry.failureRecorded || !entry.failed || entry.failure?.name === 'AbortError') return;
		entry.failureRecorded = true;
		ttsLifecycle.recordFailure(entry.failure);
	}
	const notifyFailure = (error) => {
		for (const listener of [...failureListeners]) {
			try { listener(error); } catch { /* optional lifecycle listeners are isolated */ }
		}
	};
	const recordTerminalFailure = (error) => {
		if (terminalFailure !== null || closing) return;
		terminalFailure = error;
		live = false;
		notifyFailure(error);
	};
	server.on('error', (error) => {
		if (live) recordTerminalFailure(error);
	});
	server.on('close', () => {
		if (live) recordTerminalFailure(typedError('VOICE_SERVER_CLOSED', 'Voice HTTP server closed unexpectedly'));
		live = false;
	});
	server.on('listening', () => {
		if (closing || terminalFailure !== null) {
			try { server.close(); } catch { /* a canceled late bind must not survive cleanup */ }
		}
	});

	return Object.freeze({
		server,
		removeAgent(agentId) { return profileStore.remove?.(agentId) ?? false; },
		onFailure(listener) {
			if (typeof listener !== 'function') throw new TypeError('voice failure listener must be a function');
			if (terminalFailure !== null) {
				try { listener(terminalFailure); } catch { /* replay cannot escape lifecycle subscription */ }
				return () => {};
			}
			if (closing) return () => {};
			failureListeners.add(listener);
			let subscribed = true;
			return () => {
				if (!subscribed) return;
				subscribed = false;
				failureListeners.delete(listener);
			};
		},
		statusSnapshot() {
			return aggregateVoiceStatus(ttsStatusSnapshot(), sttLifecycle.snapshot());
		},
		statusSnapshots() {
			const tts = ttsStatusSnapshot();
			const stt = sttLifecycle.snapshot();
			return Object.freeze([aggregateVoiceStatus(tts, stt), tts, stt]);
		},
		async start({ signal } = {}) {
			if (closePromise !== null) throw typedError('VOICE_WORKER_CLOSED', 'Voice worker has been closed');
			if (terminalFailure !== null) throw terminalFailure;
			if (signal?.aborted) throw abortReason(signal);
			if (server.listening) return server.address();
			if (startPromise !== null) return startPromise;
			startPromise = new Promise((resolve, reject) => {
				const cleanup = () => {
					server.off('error', onError);
					server.off('listening', onListening);
					signal?.removeEventListener('abort', onAbort);
				};
				const onError = (error) => {
					cleanup();
					reject(error);
				};
				const onListening = () => {
					cleanup();
					live = true;
					resolve(server.address());
				};
				const onAbort = () => {
					cleanup();
					try { server.close(); } catch { /* bind cancellation is best effort */ }
					reject(abortReason(signal));
				};
				server.once('error', onError);
				server.once('listening', onListening);
				signal?.addEventListener('abort', onAbort, { once: true });
				server.listen(port, host);
			});
			try {
				const address = await startPromise;
				return address;
			} catch (error) {
				throw error;
			} finally {
				startPromise = null;
			}
		},
		async close() {
			if (closePromise !== null) return closePromise;
			closing = true;
			failureListeners.clear();
			closePromise = (async () => {
				ttsLifecycle.close();
				sttLifecycle.close();
				if (startPromise !== null) {
					try { await startPromise; }
					catch { /* close still flushes assignments after a failed bind */ }
				}
				for (const controller of controllers) controller.abort();
				for (const request of preauthenticationRequests) request.destroy(typedError('VOICE_WORKER_CLOSED', 'Voice worker has been closed'));
				preauthenticationRequests.clear();
				authenticatedNonces.clear();
				activeSttPlayers.clear();
				sttNextAllowedAt.clear();
				if (server.listening) {
					await new Promise((resolve, reject) => server.close((error) => error ? reject(error) : resolve()));
				}
				if (typeof profileStore.close === 'function') await profileStore.close();
				else if (typeof profileStore.flush === 'function') await profileStore.flush();
			})();
			return closePromise;
		},
	});
}

function effectiveProviderNamespace(provider) {
	try {
		return boundedProviderNamespace(providerCacheNamespace(provider, 'tts/unspecified'));
	} catch {
		return 'tts/unspecified';
	}
}

function effectiveSynthesisNamespace(synthesis, provider) {
	try {
		return boundedProviderNamespace(synthesisCacheNamespace(synthesis, provider, 'tts/unspecified'));
	} catch {
		return 'tts/unspecified';
	}
}

function boundedProviderNamespace(value) {
	return typeof value === 'string' && /^[a-z0-9][a-z0-9._/-]{0,127}$/.test(value)
		? value
		: 'tts/unspecified';
}

function synthesisCacheKey(profile, payload, synthesizer) {
	return TtsCache.key({
		synthesizer,
		provider: profile.provider,
		model: profile.model,
		voiceId: profile.voiceId,
		profileRevision: profile.revision,
		text: payload.text.normalize('NFC').trim(),
		speed: profile.speed,
		tone: payload.tone,
		format: 's16le',
		sampleRate: 48_000,
	});
}

class VoiceChannelLifecycle {
	#component;
	#boundary;
	#probe;
	#onStalled;
	#now;
	#schedule;
	#cancelSchedule;
	#initialRetryMs;
	#maxRetryMs;
	#probeTimeoutMs;
	#state = 'ready';
	#failureCode = null;
	#failures = 0;
	#nextProbeAt = null;
	#generation = 1;
	#lastRecoveryAt = null;
	#epoch = 0;
	#timer = null;
	#probeToken = null;
	#retryEnabled;
	#closed = false;
	#broken = false;

	constructor({
		component, boundary, probe, onStalled, initialFailureCode = null,
		now, schedule, cancelSchedule, initialRetryMs, maxRetryMs, probeTimeoutMs,
	}) {
		if (typeof onStalled !== 'function') throw new TypeError('voice stalled probe callback must be a function');
		this.#component = component;
		this.#boundary = boundary;
		this.#probe = probe;
		this.#onStalled = onStalled;
		this.#retryEnabled = typeof probe === 'function';
		this.#now = now;
		this.#schedule = schedule;
		this.#cancelSchedule = cancelSchedule;
		this.#initialRetryMs = initialRetryMs;
		this.#maxRetryMs = maxRetryMs;
		this.#probeTimeoutMs = probeTimeoutMs;
		if (initialFailureCode !== null) {
			this.#state = 'degraded';
			this.#failureCode = initialFailureCode;
			this.#failures = 1;
		}
	}

	recordFailure(error) {
		if (this.#closed) return;
		if (this.#broken) return;
		if (this.#probeToken?.invalidated) {
			this.#markStalled();
			return;
		}
		this.#state = 'degraded';
		this.#failureCode = voiceFailureCode(error);
		this.#failures = Math.min(1_000_000, this.#failures + 1);
		this.#generation += 1;
		if (this.#retryEnabled && this.#timer === null && this.#probeToken === null) this.#scheduleRetry();
	}

	recordCapacityStalled() {
		if (this.#closed || this.#broken) return;
		this.#broken = true;
		this.#retryEnabled = false;
		this.#epoch += 1;
		if (this.#timer !== null) this.#cancelSchedule(this.#timer);
		this.#timer = null;
		if (this.#probeToken !== null) {
			this.#probeToken.invalidated = true;
			if (this.#probeToken.timeout !== null) this.#cancelSchedule(this.#probeToken.timeout);
			this.#probeToken.timeout = null;
			this.#probeToken.controller.abort();
		}
		this.#state = 'degraded';
		this.#failureCode = 'TTS_PROVIDER_CAPACITY_STALLED';
		this.#failures = Math.min(1_000_000, this.#failures + 1);
		this.#nextProbeAt = null;
		this.#generation += 1;
		// An in-process promise cannot be forcibly reclaimed. Do not request a replacement generation.
	}

	recordReady() {
		if (this.#closed || this.#broken) return;
		const recovered = this.#state !== 'ready';
		this.#epoch += 1;
		if (this.#timer !== null) this.#cancelSchedule(this.#timer);
		this.#timer = null;
		if (this.#probeToken !== null) {
			const staleProbe = this.#probeToken;
			staleProbe.invalidated = true;
			if (staleProbe.timeout !== null) this.#cancelSchedule(staleProbe.timeout);
			staleProbe.timeout = null;
			staleProbe.controller.abort();
		}
		this.#state = 'ready';
		this.#failureCode = null;
		this.#failures = 0;
		this.#nextProbeAt = null;
		if (recovered) {
			this.#lastRecoveryAt = this.#now();
			this.#generation += 1;
		}
	}

	snapshot() {
		return Object.freeze({
			component: this.#component,
			state: this.#state,
			fallbackMode: this.#state === 'ready' ? null : 'text',
			boundary: this.#state === 'ready' ? null : this.#boundary,
			failureCode: this.#failureCode,
			consecutiveFailureCount: this.#failures,
			nextProbeAtEpochMs: this.#nextProbeAt,
			generation: this.#generation,
			lastRecoveryAtEpochMs: this.#lastRecoveryAt,
		});
	}

	close() {
		if (this.#closed) return;
		this.#closed = true;
		this.#epoch += 1;
		if (this.#timer !== null) this.#cancelSchedule(this.#timer);
		if (this.#probeToken?.timeout != null) this.#cancelSchedule(this.#probeToken.timeout);
		this.#probeToken?.controller.abort();
		this.#timer = null;
	}

	#scheduleRetry() {
		const delay = Math.min(this.#maxRetryMs, this.#initialRetryMs * 2 ** Math.min(20, this.#failures - 1));
		this.#nextProbeAt = this.#now() + delay;
		const epoch = ++this.#epoch;
		this.#timer = this.#schedule(() => {
			if (this.#closed || epoch !== this.#epoch) return;
			this.#timer = null;
			void this.#runProbe(epoch);
		}, delay);
	}

	async #runProbe(epoch) {
		if (this.#closed || epoch !== this.#epoch || this.#probeToken !== null || !this.#retryEnabled) return;
		const controller = new AbortController();
		const token = { epoch, controller, timeout: null, timedOut: false, invalidated: false };
		this.#probeToken = token;
		const raw = Promise.resolve().then(() => this.#probe(controller.signal));
		token.timeout = this.#schedule(() => {
			if (this.#closed || this.#probeToken !== token || token.epoch !== this.#epoch) return;
			token.timeout = null;
			token.timedOut = true;
			this.#markStalled();
			const error = typedError(`${this.#component === 'voice:stt' ? 'STT' : 'TTS'}_TIMEOUT`, 'Voice health probe timed out');
			error.name = 'TimeoutError';
			controller.abort(error);
		}, this.#probeTimeoutMs);
		raw.then(
			() => this.#settleProbe(token, null),
			(error) => this.#settleProbe(token, error),
		);
	}

	#settleProbe(token, failure) {
		if (token.timeout !== null) this.#cancelSchedule(token.timeout);
		token.timeout = null;
		if (this.#probeToken !== token) return;
		this.#probeToken = null;
		if (this.#closed) return;
		if (token.invalidated) {
			if (!this.#broken && this.#state === 'degraded' && this.#retryEnabled && this.#timer === null) this.#scheduleRetry();
			return;
		}
		if (token.timedOut) {
			if (this.#state === 'degraded' && this.#timer === null) this.#scheduleRetry();
			return;
		}
		if (failure === null) this.recordReady();
		else this.recordFailure(failure);
	}

	#markStalled() {
		if (this.#closed || this.#broken) return;
		this.#broken = true;
		this.#retryEnabled = false;
		this.#state = 'degraded';
		this.#failureCode = `${this.#component === 'voice:stt' ? 'STT' : 'TTS'}_PROVIDER_STALLED`;
		this.#failures = Math.min(1_000_000, this.#failures + 1);
		this.#nextProbeAt = null;
		this.#generation += 1;
		const error = typedError(this.#failureCode, 'Voice provider ignored cancellation and requires replacement');
		this.#onStalled(error);
	}
}

function aggregateVoiceStatus(tts, stt) {
	const failed = [tts, stt].find((snapshot) => snapshot.state !== 'ready');
	if (failed === undefined) {
		return Object.freeze({
			component: 'voice', state: 'ready', fallbackMode: null, boundary: null, failureCode: null,
			effectiveTtsProvider: tts.effectiveProvider ?? null,
			consecutiveFailureCount: 0, nextProbeAtEpochMs: null,
			generation: tts.generation + stt.generation,
			lastRecoveryAtEpochMs: latestRecovery(tts, stt),
		});
	}
	return Object.freeze({
		component: 'voice', state: failed.state, fallbackMode: 'text', boundary: 'voice_provider',
		effectiveTtsProvider: tts.effectiveProvider ?? null,
		failureCode: failed.failureCode,
		consecutiveFailureCount: Math.max(tts.consecutiveFailureCount, stt.consecutiveFailureCount),
		nextProbeAtEpochMs: earliestProbe(tts, stt),
		generation: tts.generation + stt.generation,
		lastRecoveryAtEpochMs: latestRecovery(tts, stt),
	});
}

async function probeTts(provider, signal) {
	if (typeof provider.probe === 'function') return provider.probe({ signal });
	if (typeof provider.warmup === 'function') return provider.warmup({ signal });
	validateSynthesis(await provider.synthesize({ text: '.', voiceId: PROBE_PROFILE.voiceId, speed: PROBE_PROFILE.speed, signal }));
}

async function probeStt(provider, signal) {
	if (provider === null || typeof provider?.transcribe !== 'function') {
		throw typedError('STT_UNAVAILABLE', 'Speech recognition is not configured');
	}
	if (typeof provider.probe === 'function') return provider.probe({ signal });
	if (typeof provider.warmup === 'function') return provider.warmup({ signal });
	validateTranscriptResult(await provider.transcribe({ pcm: Buffer.alloc(1_920), signal }));
}

function earliestProbe(...snapshots) {
	const values = snapshots.map((snapshot) => snapshot.nextProbeAtEpochMs).filter(Number.isSafeInteger);
	return values.length === 0 ? null : Math.min(...values);
}

function latestRecovery(...snapshots) {
	const values = snapshots.map((snapshot) => snapshot.lastRecoveryAtEpochMs).filter(Number.isSafeInteger);
	return values.length === 0 ? null : Math.max(...values);
}

function defaultSchedule(callback, delay) {
	const timer = setTimeout(callback, delay);
	timer.unref?.();
	return timer;
}

function voiceFailureCode(error) {
	const value = error?.code;
	return typeof value === 'string' && /^[A-Z][A-Z0-9_]{0,63}$/.test(value) ? value : 'VOICE_UNAVAILABLE';
}

function parseJson(body) {
	try {
		return JSON.parse(body.toString('utf8'));
	} catch {
		throw typedError('INVALID_JSON', 'Voice request must be valid JSON');
	}
}

async function readRequestBody(request, maximum, deadlineMs) {
	const chunks = [];
	let bytes = 0;
	let timedOut = false;
	const timeout = setTimeout(() => {
		timedOut = true;
		request.destroy(typedError('REQUEST_TIMEOUT', 'Voice request body timed out'));
	}, deadlineMs);
	timeout.unref?.();
	try {
		for await (const chunk of request) {
			bytes += chunk.length;
			if (bytes > maximum) throw typedError('REQUEST_TOO_LARGE', 'Voice request exceeds its byte limit');
			chunks.push(chunk);
		}
		return Buffer.concat(chunks);
	} catch (error) {
		if (timedOut) throw typedError('REQUEST_TIMEOUT', 'Voice request body timed out');
		throw error;
	} finally {
		clearTimeout(timeout);
	}
}

function validateDeclaredContentLength(value, maximum) {
	if (value === undefined) return;
	if (typeof value !== 'string' || !/^\d{1,16}$/.test(value)) {
		throw typedError('INVALID_CONTENT_LENGTH', 'Content-Length is invalid');
	}
	const length = Number(value);
	if (!Number.isSafeInteger(length)) throw typedError('INVALID_CONTENT_LENGTH', 'Content-Length is invalid');
	if (length > maximum) throw typedError('REQUEST_TOO_LARGE', 'Voice request exceeds its byte limit');
}

function respondJsonAndClose(request, response, status, payload) {
	response.setHeader('Connection', 'close');
	response.once('finish', () => request.destroy());
	respondJson(response, status, payload);
}

function validatePcmBody(body) {
	if (body.length === 0 || body.length % 2 !== 0) throw typedError('STT_MALFORMED_AUDIO', 'STT audio is invalid');
	return body;
}

function validateSttHeaders(headers) {
	const playerId = headers['x-player-id'];
	const sequence = Number(headers['x-utterance-sequence']);
	const whispering = headers['x-whispering'];
	if (typeof playerId !== 'string' || !/^[0-9a-f-]{36}$/i.test(playerId)) throw typedError('INVALID_REQUEST', 'X-Player-Id must be a UUID');
	if (!Number.isSafeInteger(sequence) || sequence < 1) throw typedError('INVALID_REQUEST', 'X-Utterance-Sequence is invalid');
	if (!['true', 'false'].includes(whispering)) throw typedError('INVALID_REQUEST', 'X-Whispering is invalid');
	return { playerId, utteranceSequence: sequence, whispering: whispering === 'true' };
}

function requireJsonContentType(value) {
	if (typeof value !== 'string' || value.split(';', 1)[0].trim().toLowerCase() !== 'application/json') {
		throw typedError('INVALID_REQUEST', 'Content-Type must be application/json');
	}
}

function requireContentType(value, expected) {
	if (typeof value !== 'string' || value.replaceAll(' ', '').toLowerCase() !== expected) {
		throw typedError('INVALID_REQUEST', `Content-Type must be ${expected}`);
	}
}

function validateSynthesis(value) {
	if (value === null || typeof value !== 'object' || !Buffer.isBuffer(value.pcm)
			|| !Number.isSafeInteger(value.sampleRateHz) || value.sampleRateHz < 8_000 || value.sampleRateHz > 192_000
			|| value.channels !== 1 || value.sampleFormat !== 's16le'
			|| value.pcm.length === 0 || value.pcm.length % 2 !== 0
			|| value.pcm.length > value.sampleRateHz * 2 * 20) {
		throw typedError('TTS_MALFORMED_AUDIO', 'TTS provider returned invalid mono signed 16-bit PCM');
	}
	return value;
}

function validateTranscriptResult(value) {
	if (value === null || typeof value !== 'object' || typeof value.transcript !== 'string'
			|| !Number.isFinite(value.confidence)) {
		throw typedError('STT_PROVIDER_RESPONSE', 'STT provider returned an invalid transcript');
	}
	return Object.freeze({
		transcript: [...value.transcript.trim()].slice(0, 512).join(''),
		confidence: Math.max(0, Math.min(1, value.confidence)),
	});
}

function validateRequest(value) {
	if (value === null || typeof value !== 'object' || Array.isArray(value)) throw typedError('INVALID_REQUEST', 'Voice request must be an object');
	const keys = Object.keys(value).sort();
	const keyShape = keys.join(',');
	if (keyShape !== 'agentId,conversationSequence,profileId,radius,speed,text,tone'
			&& keyShape !== 'agentId,conversationSequence,profileId,radius,text') throw typedError('INVALID_REQUEST', 'Voice request fields are invalid');
	if (keyShape === 'agentId,conversationSequence,profileId,radius,text') {
		value = { ...value, speed: 1, tone: 'neutral' };
	}
	if (!/^[0-9a-f-]{36}$/i.test(value.agentId)) throw typedError('INVALID_REQUEST', 'agentId must be a UUID');
	if (typeof value.text !== 'string' || value.text.trim() === '' || [...value.text].length > 280) throw typedError('INVALID_REQUEST', 'text must contain 1 to 280 code points');
	if (typeof value.profileId !== 'string' || !/^voice\.[a-z0-9_.-]+\.v1$/.test(value.profileId)) throw typedError('INVALID_REQUEST', 'profileId is invalid');
	if (value.profileId !== 'voice.auto.v1' && !builtInVoiceProfiles().some((profile) => profile.profileId === value.profileId)) {
		throw typedError('INVALID_REQUEST', 'profileId is not in the installed voice catalog');
	}
	if (!Number.isSafeInteger(value.radius) || value.radius < 1 || value.radius > 128) throw typedError('INVALID_REQUEST', 'radius is invalid');
	if (!Number.isSafeInteger(value.conversationSequence) || value.conversationSequence < 0) throw typedError('INVALID_REQUEST', 'conversationSequence is invalid');
	if (typeof value.speed !== 'number' || !Number.isFinite(value.speed) || value.speed < 0.5 || value.speed > 2) throw typedError('INVALID_REQUEST', 'speed is invalid');
	if (typeof value.tone !== 'string' || !/^[A-Za-z0-9_.:-]{1,32}$/.test(value.tone)) throw typedError('INVALID_REQUEST', 'tone is invalid');
	return value;
}

function statusFor(error) {
	if (error?.httpStatus === 503) return 503;
	if (error?.name === 'AbortError' || error?.name === 'TimeoutError') return 504;
	if (error?.code === 'TTS_RATE_LIMITED' || error?.code === 'TTS_CAPACITY') return 429;
	if (error?.code === 'STT_RATE_LIMITED' || error?.code === 'STT_CAPACITY' || error?.code === 'STT_PLAYER_BUSY') return 429;
	if (error?.code === 'STT_UNAVAILABLE') return 503;
	if (error?.code === 'REQUEST_TIMEOUT') return 408;
	if (['INVALID_REQUEST', 'INVALID_JSON', 'INVALID_CONTENT_LENGTH', 'REQUEST_TOO_LARGE'].includes(error?.code)) return 400;
	return 502;
}

function respondJson(response, status, value, headers = {}) {
	const body = Buffer.from(JSON.stringify(value));
	response.writeHead(status, { ...headers, 'Content-Type': 'application/json', 'Content-Length': body.length });
	response.end(body);
}

function retryAfterHeaders(error) {
	if (error?.code !== 'STT_RATE_LIMITED' && error?.code !== 'TTS_RATE_LIMITED') return {};
	const value = typeof error?.retryAfter === 'string' ? error.retryAfter.trim() : '';
	return /^\d{1,6}$/.test(value) ? { 'Retry-After': value } : {};
}

function respondAuthenticatedJson(response, status, value, secret, requestNonce, headers = {}) {
	respondAuthenticatedBytes(
		response, status, Buffer.from(JSON.stringify(value)), 'application/json', secret, requestNonce,
		headers,
	);
}

function respondAuthenticatedBytes(response, status, body, contentType, secret, requestNonce, headers = {}) {
	const signature = signResponse(secret, requestNonce, status, contentType, body);
	response.writeHead(status, {
		...headers,
		'Content-Type': contentType,
		'Content-Length': body.length,
		'X-Voice-Response-Signature': signature,
	});
	response.end(body);
}

export function createVoiceRequestHeaders({
	secret,
	method = 'POST',
	path,
	contentType = '',
	identityHeaders = {},
	body = Buffer.alloc(0),
	timestamp = Date.now(),
	nonce = randomBytes(AUTH_NONCE_BYTES).toString('base64url'),
} = {}) {
	if (typeof secret !== 'string' || secret.length < 16) throw new TypeError('voice secret must contain at least 16 characters');
	if (method !== 'POST' || !['/v1/tts', '/v1/stt'].includes(path)) throw new TypeError('voice request target is invalid');
	if (!Number.isSafeInteger(timestamp) || timestamp < 0) throw new TypeError('voice request timestamp is invalid');
	if (!isAuthenticationNonce(nonce)) throw new TypeError('voice request nonce is invalid');
	if (!Buffer.isBuffer(body) && typeof body !== 'string' && !(body instanceof Uint8Array)) {
		throw new TypeError('voice request body must be bytes or a string');
	}
	const normalizedMethod = method.toUpperCase();
	return Object.freeze({
		'X-Voice-Nonce': nonce,
		'X-Voice-Timestamp': String(timestamp),
		'X-Voice-Signature': signRequest(
			secret, normalizedMethod, path, timestamp, nonce, contentType, identityHeaders, Buffer.from(body),
		),
	});
}

function prepareRequestAuthentication(request, nonces, currentTime) {
	const nonce = request.headers['x-voice-nonce'];
	const rawTimestamp = request.headers['x-voice-timestamp'];
	const signature = request.headers['x-voice-signature'];
	if (!isAuthenticationNonce(nonce)
			|| typeof rawTimestamp !== 'string'
			|| !/^\d{1,16}$/.test(rawTimestamp)
			|| typeof signature !== 'string') return null;
	const timestamp = Number(rawTimestamp);
	if (!Number.isSafeInteger(timestamp) || Math.abs(currentTime - timestamp) > AUTH_MAX_CLOCK_SKEW_MS) return null;
	for (const [candidate, expiresAt] of nonces) {
		if (expiresAt <= currentTime) nonces.delete(candidate);
	}
	if (nonces.has(nonce) || nonces.size >= MAX_AUTH_NONCES) return null;
	return { nonce, timestamp, signature, authenticatedAt: currentTime };
}

function authenticateRequest(request, secret, nonces, authentication, body) {
	const { nonce, timestamp, signature, authenticatedAt } = authentication;
	if (nonces.has(nonce)) return null;
	const expected = signRequest(
		secret, request.method, request.url, timestamp, nonce,
		request.headers['content-type'], request.headers, body,
	);
	if (!safeSignatureEquals(signature, expected)) return null;
	nonces.set(nonce, authenticatedAt + AUTH_MAX_CLOCK_SKEW_MS);
	return { nonce };
}

function signRequest(secret, method, path, timestamp, nonce, contentType, identityHeaders, body) {
	const digest = createHash('sha256').update(body).digest('hex');
	return createHmac('sha256', secret)
		.update(`${AUTH_VERSION}\nrequest\n${method}\n${path}\n${timestamp}\n${nonce}\n${canonicalContentType(contentType)}\n${canonicalIdentity(identityHeaders)}\n${digest}`, 'utf8')
		.digest('base64url');
}

function canonicalContentType(value) {
	return typeof value === 'string' ? value.trim().toLowerCase().replace(/\s+/g, '') : '';
}

function canonicalIdentity(headers) {
	return `player-id=${canonicalHeader(headers, 'x-player-id', true)}\n`
		+ `utterance-sequence=${canonicalHeader(headers, 'x-utterance-sequence', false)}\n`
		+ `whispering=${canonicalHeader(headers, 'x-whispering', true)}`;
}

function canonicalHeader(headers, name, lowercase) {
	let value;
	if (headers instanceof Headers) value = headers.get(name);
	else if (headers !== null && typeof headers === 'object') {
		const entry = Object.entries(headers).find(([candidate]) => candidate.toLowerCase() === name);
		value = entry?.[1];
	}
	if (typeof value !== 'string') return '';
	const normalized = value.trim();
	return lowercase ? normalized.toLowerCase() : normalized;
}

function signResponse(secret, requestNonce, status, contentType, body) {
	const digest = createHash('sha256').update(body).digest('hex');
	return createHmac('sha256', secret)
		.update(`${AUTH_VERSION}\nresponse\n${requestNonce}\n${status}\n${contentType}\n${digest}`, 'utf8')
		.digest('base64url');
}

function safeSignatureEquals(actual, expected) {
	if (!/^[A-Za-z0-9_-]{43}$/.test(actual)) return false;
	const actualBytes = Buffer.from(actual, 'base64url');
	const expectedBytes = Buffer.from(expected, 'base64url');
	return actualBytes.length === expectedBytes.length && timingSafeEqual(actualBytes, expectedBytes);
}

function isAuthenticationNonce(value) {
	return typeof value === 'string' && /^[A-Za-z0-9_-]{32}$/.test(value);
}

function typedError(code, message) {
	const error = new Error(message);
	error.code = code;
	return error;
}

function awaitAbortable(value, signal) {
	if (signal.aborted) return Promise.reject(abortReason(signal));
	return new Promise((resolve, reject) => {
		let settled = false;
		const finish = (operation, result) => {
			if (settled) return;
			settled = true;
			signal.removeEventListener('abort', onAbort);
			operation(result);
		};
		const onAbort = () => finish(reject, abortReason(signal));
		signal.addEventListener('abort', onAbort, { once: true });
		Promise.resolve(value).then(
			(result) => signal.aborted ? onAbort() : finish(resolve, result),
			(error) => finish(reject, error),
		);
	});
}

function abortReason(signal) {
	if (signal.reason instanceof Error) return signal.reason;
	const error = new Error('Voice provider request was cancelled');
	error.name = 'AbortError';
	return error;
}

function abortError(message) {
	const error = new Error(message);
	error.name = 'AbortError';
	return error;
}
