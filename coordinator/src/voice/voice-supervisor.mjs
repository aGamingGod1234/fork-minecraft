const DEFAULT_INITIAL_RETRY_MS = 1_000;
const DEFAULT_MAX_RETRY_MS = 30_000;
const DEFAULT_STARTUP_TIMEOUT_MS = 10_000;
const DEFAULT_WARMUP_TIMEOUT_MS = 30_000;
const DEFAULT_CLEANUP_TIMEOUT_MS = 1_000;
const MAX_FAILURE_REPORTS_PER_OUTAGE = 3;
const MAX_PENDING_REMOVALS = 4_096;

export class VoiceSupervisor {
	#startWorker;
	#now;
	#schedule;
	#cancelSchedule;
	#initialRetryMs;
	#maxRetryMs;
	#startupTimeoutMs;
	#warmupTimeoutMs;
	#cleanupTimeoutMs;
	#started = false;
	#closed = false;
	#closePromise = null;
	#attempt = null;
	#live = null;
	#retryTimer = null;
	#epoch = 0;
	#statusGeneration = 0;
	#failures = 0;
	#nextRetryAt = null;
	#failureCode = 'VOICE_NOT_STARTED';
	#lastRecoveryAt = null;
	#workerCloses = new WeakMap();
	#onFailure;
	#reportedFailureCodes = new Set();
	#pendingRemovals = new Set();

	constructor({
		startWorker,
		now = Date.now,
		schedule = defaultSchedule,
		cancelSchedule = clearTimeout,
		initialRetryMs = DEFAULT_INITIAL_RETRY_MS,
		maxRetryMs = DEFAULT_MAX_RETRY_MS,
		startupTimeoutMs = DEFAULT_STARTUP_TIMEOUT_MS,
		warmupTimeoutMs = DEFAULT_WARMUP_TIMEOUT_MS,
		cleanupTimeoutMs = DEFAULT_CLEANUP_TIMEOUT_MS,
		onFailure = null,
	} = {}) {
		if (typeof startWorker !== 'function') throw new TypeError('startWorker must be a function');
		if (typeof now !== 'function' || typeof schedule !== 'function' || typeof cancelSchedule !== 'function') {
			throw new TypeError('voice supervisor clock and scheduler must be functions');
		}
		for (const [name, value] of Object.entries({
			initialRetryMs, maxRetryMs, startupTimeoutMs, warmupTimeoutMs, cleanupTimeoutMs,
		})) {
			if (!Number.isSafeInteger(value) || value < 1) throw new TypeError(`${name} must be positive`);
		}
		if (maxRetryMs < initialRetryMs) throw new TypeError('maxRetryMs must not be less than initialRetryMs');
		if (onFailure !== null && typeof onFailure !== 'function') throw new TypeError('onFailure must be a function or null');
		this.#startWorker = startWorker;
		this.#now = now;
		this.#schedule = schedule;
		this.#cancelSchedule = cancelSchedule;
		this.#initialRetryMs = initialRetryMs;
		this.#maxRetryMs = maxRetryMs;
		this.#startupTimeoutMs = startupTimeoutMs;
		this.#warmupTimeoutMs = warmupTimeoutMs;
		this.#cleanupTimeoutMs = cleanupTimeoutMs;
		this.#onFailure = onFailure;
	}

	start() {
		if (this.#closed || this.#started) return;
		this.#started = true;
		queueMicrotask(() => this.#beginAttempt());
	}

	statusSnapshots() {
		if (this.#live !== null) {
			try {
				const snapshots = this.#live.worker.statusSnapshots?.();
				if (Array.isArray(snapshots)) return snapshots.slice(0, 3).map((snapshot) => this.#withHistory(snapshot));
				const snapshot = this.#live.worker.statusSnapshot?.();
				if (snapshot !== null && typeof snapshot === 'object') return [this.#withHistory(snapshot)];
			} catch { /* optional voice status is observational */ }
		}
		const state = this.#failures === 0 ? 'unknown' : 'degraded';
		return ['voice', 'voice:tts', 'voice:stt'].map((component) => Object.freeze({
			component,
			state,
			fallbackMode: 'text',
			boundary: 'voice_start',
			failureCode: this.#failureCode,
			consecutiveFailureCount: this.#failures,
			nextProbeAtEpochMs: this.#nextRetryAt,
			generation: this.#statusGeneration,
			lastRecoveryAtEpochMs: this.#lastRecoveryAt,
		}));
	}

	removeAgent(agentId) {
		if (typeof agentId !== 'string' || agentId.trim().length === 0) throw new TypeError('agentId must be a nonblank string');
		this.#pendingRemovals.add(agentId);
		while (this.#pendingRemovals.size > MAX_PENDING_REMOVALS) {
			this.#pendingRemovals.delete(this.#pendingRemovals.values().next().value);
		}
		return this.#drainRemoval(agentId);
	}

	close() {
		if (this.#closePromise !== null) return this.#closePromise;
		this.#closed = true;
		this.#epoch += 1;
		if (this.#retryTimer !== null) this.#cancelSchedule(this.#retryTimer);
		this.#retryTimer = null;
		this.#nextRetryAt = null;
		const attempt = this.#attempt;
		const live = this.#live;
		attempt?.controller.abort(abortError('Voice supervisor closed'));
		attempt?.unsubscribe?.();
		live?.unsubscribe?.();
		this.#live = null;
		this.#closePromise = Promise.allSettled([
			this.#closeWorker(attempt?.candidate),
			this.#closeWorker(live?.worker),
		]).then(() => undefined);
		return this.#closePromise;
	}

	#beginAttempt() {
		if (this.#closed || this.#live !== null || this.#attempt !== null) return;
		this.#retryTimer = null;
		this.#nextRetryAt = null;
		const token = {
			epoch: ++this.#epoch,
			controller: new AbortController(),
			phase: 'start',
			timer: null,
			candidate: null,
			unsubscribe: null,
			failureRecorded: false,
		};
		this.#attempt = token;
		const startup = Promise.resolve().then(() => this.#startWorker({ signal: token.controller.signal }));
		token.timer = this.#schedule(() => this.#timeoutAttempt(token, 'VOICE_START_TIMEOUT'), this.#startupTimeoutMs);
		startup.then(
			(worker) => { void this.#workerStarted(token, worker); },
			(error) => { void this.#attemptRejected(token, error); },
		);
	}

	async #workerStarted(token, worker) {
		this.#cancelTokenTimer(token);
		if (!this.#ownsAttempt(token) || token.controller.signal.aborted || this.#closed) {
			await this.#closeWorker(worker);
			this.#releaseAttempt(token);
			return;
		}
		if (worker === null || typeof worker !== 'object' || typeof worker.close !== 'function') {
			await this.#failAttempt(token, voiceError('VOICE_UNAVAILABLE', 'Voice worker is unavailable'));
			return;
		}
		token.candidate = worker;
		try {
			token.unsubscribe = this.#subscribeFailure(worker, (error) => this.#workerFailed(token, worker, error));
		} catch (error) {
			await this.#failAttempt(token, error);
			return;
		}
		if (!this.#ownsAttempt(token) || token.controller.signal.aborted || token.failureRecorded) {
			token.unsubscribe?.();
			token.unsubscribe = null;
			if (this.#ownsAttempt(token)) await this.#failAttempt(token, null);
			return;
		}
		if (typeof worker.warmup !== 'function') {
			this.#promote(token, worker);
			return;
		}
		token.phase = 'warmup';
		const warming = Promise.resolve().then(() => worker.warmup({ signal: token.controller.signal }));
		token.timer = this.#schedule(() => this.#timeoutAttempt(token, 'VOICE_WARMUP_TIMEOUT'), this.#warmupTimeoutMs);
		warming.then(
			() => this.#warmupFinished(token, worker),
			(error) => { void this.#attemptRejected(token, error); },
		);
	}

	#warmupFinished(token, worker) {
		this.#cancelTokenTimer(token);
		if (!this.#ownsAttempt(token) || token.controller.signal.aborted || this.#closed) return;
		this.#promote(token, worker);
	}

	async #attemptRejected(token, error) {
		if (!this.#ownsAttempt(token)) return;
		this.#cancelTokenTimer(token);
		await this.#failAttempt(token, token.failureRecorded ? null : error);
	}

	#timeoutAttempt(token, code) {
		if (!this.#ownsAttempt(token)) return;
		token.timer = null;
		const error = voiceError(code, 'Voice lifecycle operation timed out');
		token.failureRecorded = true;
		this.#recordFailure(error);
		token.controller.abort(error);
		if (token.phase === 'warmup') void this.#failAttempt(token, null);
		// A start operation retains ownership until it acknowledges cancellation or returns.
	}

	async #failAttempt(token, error) {
		if (!this.#ownsAttempt(token)) return;
		this.#cancelTokenTimer(token);
		if (error !== null && !token.failureRecorded && !this.#closed) {
			token.failureRecorded = true;
			this.#recordFailure(error);
		}
		token.controller.abort(error ?? abortError('Voice startup failed'));
		token.unsubscribe?.();
		token.unsubscribe = null;
		await this.#closeWorker(token.candidate);
		this.#releaseAttempt(token);
	}

	#promote(token, worker) {
		if (!this.#ownsAttempt(token) || this.#closed) return;
		this.#cancelTokenTimer(token);
		this.#attempt = null;
		this.#live = { epoch: token.epoch, worker, unsubscribe: token.unsubscribe };
		token.candidate = null;
		token.unsubscribe = null;
		if (this.#failures > 0) this.#lastRecoveryAt = this.#now();
		this.#failures = 0;
		this.#reportedFailureCodes.clear();
		this.#failureCode = null;
		this.#nextRetryAt = null;
		this.#statusGeneration += 1;
		for (const agentId of this.#pendingRemovals) void this.#drainRemoval(agentId);
	}

	async #drainRemoval(agentId) {
		const worker = this.#live?.worker;
		if (worker === undefined || typeof worker.removeAgent !== 'function') return false;
		try {
			await worker.removeAgent(agentId);
			this.#pendingRemovals.delete(agentId);
			return true;
		} catch {
			return false;
		}
	}

	#workerFailed(owner, worker, error) {
		if (this.#attempt === owner && owner.candidate === worker) {
			void this.#failAttempt(owner, error);
			return;
		}
		const live = this.#live;
		if (live === null || live.epoch !== owner.epoch || live.worker !== worker || this.#closed) return;
		this.#live = null;
		live.unsubscribe?.();
		this.#recordFailure(error);
		void this.#closeWorker(worker);
		this.#scheduleRetry();
	}

	#releaseAttempt(token) {
		if (!this.#ownsAttempt(token)) return;
		this.#attempt = null;
		token.candidate = null;
		token.unsubscribe = null;
		if (!this.#closed && this.#live === null && token.failureRecorded) this.#scheduleRetry();
	}

	#recordFailure(error) {
		if (this.#closed) return;
		this.#failures = Math.min(1_000_000, this.#failures + 1);
		this.#failureCode = failureCode(error);
		this.#statusGeneration += 1;
		if (this.#onFailure !== null
				&& this.#reportedFailureCodes.size < MAX_FAILURE_REPORTS_PER_OUTAGE
				&& !this.#reportedFailureCodes.has(this.#failureCode)) {
			this.#reportedFailureCodes.add(this.#failureCode);
			try {
				this.#onFailure(Object.freeze({
					failureCode: this.#failureCode,
					consecutiveFailureCount: this.#failures,
				}));
			} catch { /* diagnostics cannot break recovery */ }
		}
	}

	#scheduleRetry() {
		if (this.#closed || this.#live !== null || this.#attempt !== null || this.#retryTimer !== null) return;
		const delay = Math.min(this.#maxRetryMs, this.#initialRetryMs * 2 ** Math.min(20, this.#failures - 1));
		this.#nextRetryAt = this.#now() + delay;
		const epoch = this.#epoch;
		this.#retryTimer = this.#schedule(() => {
			if (this.#closed || epoch !== this.#epoch) return;
			this.#retryTimer = null;
			this.#beginAttempt();
		}, delay);
	}

	#subscribeFailure(worker, listener) {
		if (typeof worker.onFailure !== 'function') return () => {};
		const unsubscribe = worker.onFailure(listener);
		return typeof unsubscribe === 'function' ? once(unsubscribe) : () => {};
	}

	#closeWorker(worker) {
		if (worker === null || typeof worker !== 'object' || typeof worker.close !== 'function') return Promise.resolve();
		const existing = this.#workerCloses.get(worker);
		if (existing !== undefined) return existing;
		let timer;
		const raw = Promise.resolve().then(() => worker.close()).then(() => undefined, () => undefined);
		const deadline = new Promise((resolve) => {
			timer = this.#schedule(resolve, this.#cleanupTimeoutMs);
		});
		const bounded = Promise.race([raw, deadline]).finally(() => this.#cancelSchedule(timer));
		this.#workerCloses.set(worker, bounded);
		return bounded;
	}

	#cancelTokenTimer(token) {
		if (token.timer === null) return;
		this.#cancelSchedule(token.timer);
		token.timer = null;
	}

	#ownsAttempt(token) {
		return this.#attempt === token && token.epoch === this.#epoch;
	}

	#withHistory(snapshot) {
		if (snapshot === null || typeof snapshot !== 'object') return snapshot;
		const workerGeneration = Number.isSafeInteger(snapshot.generation) && snapshot.generation >= 0
			? snapshot.generation
			: 0;
		const workerRecovery = Number.isSafeInteger(snapshot.lastRecoveryAtEpochMs)
			? snapshot.lastRecoveryAtEpochMs
			: null;
		return Object.freeze({
			...snapshot,
			generation: workerGeneration + this.#statusGeneration,
			lastRecoveryAtEpochMs: workerRecovery === null
				? this.#lastRecoveryAt
				: this.#lastRecoveryAt === null ? workerRecovery : Math.max(workerRecovery, this.#lastRecoveryAt),
		});
	}
}

function once(callback) {
	let called = false;
	return () => {
		if (called) return;
		called = true;
		try { callback(); } catch { /* lifecycle detachment is best effort */ }
	};
}

function defaultSchedule(callback, delay) {
	const timer = setTimeout(callback, delay);
	timer.unref?.();
	return timer;
}

function failureCode(error) {
	return typeof error?.code === 'string' && /^[A-Z][A-Z0-9_]{0,63}$/.test(error.code)
		? error.code
		: 'VOICE_UNAVAILABLE';
}

function voiceError(code, message) {
	const error = new Error(message);
	error.code = code;
	return error;
}

function abortError(message) {
	const error = voiceError('VOICE_OPERATION_CANCELLED', message);
	error.name = 'AbortError';
	return error;
}
