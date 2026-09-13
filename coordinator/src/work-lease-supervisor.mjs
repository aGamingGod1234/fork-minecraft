export const LEASE_TIMEOUTS_MS = Object.freeze({
	provider: 45_000,
	action: 125_000,
	wait: 10_000,
	recovery: 15_000,
	scheduled: 2_000,
	completion: 15_000,
});

export const FACTUAL_PROGRESS_TIMEOUT_MS = 30_000;
export const MAX_LEASE_TIMEOUT_MS = 900_000;

const AUTOMATED_KINDS = new Set(['scheduled', 'recovery']);
const MAX_HISTORY = 8;
const MAX_RECOVERY_DELAY_MS = 600_000;

/** Fences every unfinished goal behind deadline-bound work and factual-progress leases. */
export class WorkLeaseSupervisor {
	#entries = new Map();
	#sequence = 0;
	#clock;
	#schedule;
	#cancelSchedule;
	#stuckSchedule;
	#cancelStuckSchedule;
	#requestObservation;
	#onExpire;
	#onStuck;
	#closed = false;
	#lastNow = 0;

	constructor({
		clock = Date.now,
		schedule = defaultSchedule,
		cancelSchedule = clearTimeout,
		stuckSchedule = defaultSchedule,
		cancelStuckSchedule = clearTimeout,
		requestObservation = () => {},
		onExpire = () => {},
		onStuck = () => {},
	} = {}) {
		for (const [name, dependency] of Object.entries({ clock, schedule, cancelSchedule, stuckSchedule, cancelStuckSchedule, requestObservation, onExpire, onStuck })) {
			if (typeof dependency !== 'function') throw new TypeError(`${name} must be a function`);
		}
		this.#clock = clock;
		this.#schedule = schedule;
		this.#cancelSchedule = cancelSchedule;
		this.#stuckSchedule = stuckSchedule;
		this.#cancelStuckSchedule = cancelStuckSchedule;
		this.#requestObservation = requestObservation;
		this.#onExpire = onExpire;
		this.#onStuck = onStuck;
	}

	activate(key) {
		if (this.#closed) return false;
		const normalized = normalizeKey(key);
		const existing = this.#entries.get(normalized.agentId);
		if (existing !== undefined) {
			if (sameKey(existing.key, normalized)) {
				if (existing.state === 'suspended') existing.state = 'active';
				this.#ensureAutomatedLease(existing);
				return existing.state !== 'terminated';
			}
			if (!isNewer(normalized, existing.key)) return false;
			this.#discard(existing);
		}
		const entry = createEntry(normalized, this.#now());
		this.#entries.set(normalized.agentId, entry);
		this.#ensureAutomatedLease(entry);
		return true;
	}

	acquire(key, kind, { timeoutMs = LEASE_TIMEOUTS_MS[kind] } = {}) {
		if (!Object.hasOwn(LEASE_TIMEOUTS_MS, kind)) throw new TypeError(`Unsupported work lease kind '${kind}'`);
		if (AUTOMATED_KINDS.has(kind)) throw new TypeError(`Work lease kind '${kind}' is supervisor-owned`);
		if (!Number.isSafeInteger(timeoutMs) || timeoutMs < 1 || timeoutMs > MAX_LEASE_TIMEOUT_MS) throw new TypeError(`work lease timeoutMs must be a safe integer from 1 to ${MAX_LEASE_TIMEOUT_MS}`);
		const entry = this.#requireCurrent(key);
		this.#removeAutomatedLeases(entry);
		entry.state = 'active';
		return this.#createLease(entry, kind, false, timeoutMs);
	}

	progress(token) {
		const resolved = this.#resolveToken(token);
		if (resolved === null) return false;
		const { entry, lease } = resolved;
		this.#cancelLeaseTimer(lease);
		lease.lastProgressAt = this.#now();
		lease.deadline = lease.lastProgressAt + lease.timeoutMs;
		this.#armLease(entry, lease);
		return true;
	}

	release(token, { scheduleRecovery = true } = {}) {
		const resolved = this.#resolveToken(token);
		if (resolved === null) return false;
		const { entry, lease } = resolved;
		this.#removeLease(entry, lease);
		if (scheduleRecovery) this.#ensureAutomatedLease(entry);
		return true;
	}

	observed(key) {
		const entry = this.#current(key);
		if (entry === null || this.#closed) return false;
		this.#removeAutomatedLeases(entry);
		entry.state = 'active';
		this.#ensureAutomatedLease(entry);
		return true;
	}

	recover(key, details = undefined) {
		const entry = this.#current(key);
		if (entry === null || this.#closed) return false;
		this.#removeAutomatedLeases(entry);
		entry.state = 'recovering';
		entry.recoveryDetails = safeClone(details);
		this.#createLease(entry, 'scheduled', true, recoveryDelay(details));
		return true;
	}

	ensure(key) {
		const entry = this.#current(key);
		if (entry === null || this.#closed) return false;
		return this.#ensureAutomatedLease(entry);
	}

	factualProgress(key, signature, details = undefined) {
		const entry = this.#current(key);
		if (entry === null || this.#closed) return false;
		if (typeof signature !== 'string' || signature.length === 0 || signature.length > 8_192) {
			throw new TypeError('factual progress signature must be bounded nonblank text');
		}
		const now = this.#now();
		entry.history.push(Object.freeze({ at: now, signature, details: safeClone(details) }));
		if (entry.history.length > MAX_HISTORY) entry.history.splice(0, entry.history.length - MAX_HISTORY);
		if (entry.factualSignature !== signature) {
			entry.factualSignature = signature;
			entry.lastFactualProgressAt = now;
			entry.stuckReported = false;
			this.#armStuck(entry);
		}
		return true;
	}

	suspend(key) {
		const entry = this.#current(key);
		if (entry === null || this.#closed) return false;
		this.#discardTimers(entry);
		entry.leases.clear();
		entry.state = 'suspended';
		return true;
	}

	terminate(key) {
		const entry = this.#current(key);
		if (entry === null || this.#closed) return false;
		this.#discard(entry);
		entry.state = 'terminated';
		this.#entries.delete(entry.key.agentId);
		return true;
	}

	snapshot(key) {
		const entry = this.#current(key);
		if (entry === null) return null;
		return Object.freeze({
			key: entry.key,
			state: entry.state,
			lastFactualProgressAt: entry.lastFactualProgressAt,
			recoveryDetails: safeClone(entry.recoveryDetails),
			leases: Object.freeze([...entry.leases.values()]
				.sort((left, right) => left.operationId - right.operationId)
				.map(publicLease)),
		});
	}

	close() {
		if (this.#closed) return;
		this.#closed = true;
		for (const entry of this.#entries.values()) this.#discard(entry);
		this.#entries.clear();
	}

	#ensureAutomatedLease(entry) {
		if (this.#closed || !['active', 'recovering'].includes(entry.state) || entry.leases.size > 0) return false;
		this.#createLease(entry, entry.state === 'recovering' ? 'recovery' : 'scheduled', true);
		return true;
	}

	#createLease(entry, kind, automated, delayOverride = null) {
		const now = this.#now();
		const delay = delayOverride ?? LEASE_TIMEOUTS_MS[kind];
		const lease = {
			key: entry.key,
			kind,
			operationId: ++this.#sequence,
			createdAt: now,
			lastProgressAt: now,
			deadline: now + delay,
			timeoutMs: delay,
			automated,
			handle: null,
			timerGeneration: 0,
		};
		entry.leases.set(lease.operationId, lease);
		this.#armLease(entry, lease);
		return Object.freeze({ ...entry.key, kind, operationId: lease.operationId });
	}

	#armLease(entry, lease) {
		const delay = Math.max(0, lease.deadline - this.#now());
		const generation = ++lease.timerGeneration;
		lease.handle = this.#schedule(() => {
			if (lease.timerGeneration === generation) this.#expire(entry, lease);
		}, delay);
	}

	#expire(entry, lease) {
		if (!this.#isCurrentEntry(entry) || entry.leases.get(lease.operationId) !== lease) return;
		this.#removeLease(entry, lease, false);
		entry.state = 'recovering';
		const event = Object.freeze({ key: entry.key, lease: publicLease(lease) });
		try { this.#onExpire(event); } catch { }
		this.#requestRecovery(entry, `${lease.kind}_lease_expired`, event);
		this.#ensureAutomatedLease(entry);
	}

	#requestRecovery(entry, reason, details) {
		try {
			void Promise.resolve(this.#requestObservation(entry.key, reason, details)).catch(() => undefined);
		} catch { }
	}

	#armStuck(entry) {
		if (entry.stuckHandle !== null) this.#cancelStuckSchedule(entry.stuckHandle);
		const expectedProgressAt = entry.lastFactualProgressAt;
		entry.stuckHandle = this.#stuckSchedule(() => {
			entry.stuckHandle = null;
			if (!this.#isCurrentEntry(entry) || entry.state === 'suspended' || entry.lastFactualProgressAt !== expectedProgressAt) return;
			if (this.#now() < expectedProgressAt + FACTUAL_PROGRESS_TIMEOUT_MS) {
				this.#armStuck(entry);
				return;
			}
			if (!entry.stuckReported) {
				entry.stuckReported = true;
				const event = Object.freeze({ key: entry.key, inactiveMs: this.#now() - expectedProgressAt, history: Object.freeze(entry.history.map((item) => safeClone(item))) });
				try { this.#onStuck(event); } catch { }
				this.#requestRecovery(entry, 'factual_progress_stuck', event);
			}
		}, FACTUAL_PROGRESS_TIMEOUT_MS);
	}

	#removeAutomatedLeases(entry) {
		for (const lease of [...entry.leases.values()]) {
			if (lease.automated) this.#removeLease(entry, lease);
		}
	}

	#removeLease(entry, lease, cancel = true) {
		entry.leases.delete(lease.operationId);
		if (cancel) this.#cancelLeaseTimer(lease);
	}

	#cancelLeaseTimer(lease) {
		if (lease.handle === null) return;
		const handle = lease.handle;
		lease.handle = null;
		this.#cancelSchedule(handle);
	}

	#resolveToken(token) {
		if (this.#closed || token === null || typeof token !== 'object') return null;
		const entry = this.#current(token);
		if (entry === null) return null;
		const lease = entry.leases.get(token.operationId);
		return lease !== undefined && lease.kind === token.kind ? { entry, lease } : null;
	}

	#requireCurrent(key) {
		if (this.#closed) throw new Error('Work lease supervisor is closed');
		const entry = this.#current(key);
		if (entry === null || !['active', 'recovering'].includes(entry.state)) throw new Error('Work lease key is not current and active');
		return entry;
	}

	#current(key) {
		let normalized;
		try { normalized = normalizeKey(key); } catch { return null; }
		const entry = this.#entries.get(normalized.agentId);
		return entry !== undefined && sameKey(entry.key, normalized) ? entry : null;
	}

	#isCurrentEntry(entry) {
		return !this.#closed && this.#entries.get(entry.key.agentId) === entry && entry.state !== 'terminated';
	}

	#discardTimers(entry) {
		for (const lease of entry.leases.values()) this.#cancelLeaseTimer(lease);
		if (entry.stuckHandle !== null) {
			this.#cancelStuckSchedule(entry.stuckHandle);
			entry.stuckHandle = null;
		}
	}

	#discard(entry) {
		this.#discardTimers(entry);
		entry.leases.clear();
	}

	#now() {
		try {
			const value = this.#clock();
			if (Number.isFinite(value) && value >= 0) this.#lastNow = Math.max(this.#lastNow, value);
		} catch { /* a diagnostic clock cannot disable recovery supervision */ }
		return this.#lastNow;
	}
}

function createEntry(key, now) {
	return {
		key,
		state: 'active',
		leases: new Map(),
		factualSignature: null,
		lastFactualProgressAt: now,
		stuckHandle: null,
		stuckReported: false,
		history: [],
		recoveryDetails: null,
	};
}

function publicLease(lease) {
	return Object.freeze({ kind: lease.kind, operationId: lease.operationId, createdAt: lease.createdAt, lastProgressAt: lease.lastProgressAt, deadline: lease.deadline, timeoutMs: lease.timeoutMs });
}

function normalizeKey(value) {
	if (value === null || typeof value !== 'object' || Array.isArray(value)) throw new TypeError('work lease key must be an object');
	const { agentId, goalRevision, lifecycleGeneration, sessionEpoch, profileFingerprint } = value;
	if (typeof agentId !== 'string' || agentId.length === 0) throw new TypeError('work lease key agentId must be nonblank text');
	if (!Number.isSafeInteger(goalRevision) || goalRevision < 0) throw new TypeError('work lease key goalRevision must be a nonnegative safe integer');
	if (!Number.isSafeInteger(lifecycleGeneration) || lifecycleGeneration < 0) throw new TypeError('work lease key lifecycleGeneration must be a nonnegative safe integer');
	if (!Number.isSafeInteger(sessionEpoch) || sessionEpoch < 0) throw new TypeError('work lease key sessionEpoch must be a nonnegative safe integer');
	if (typeof profileFingerprint !== 'string' || !/^sha256:[a-f0-9]{64}$/i.test(profileFingerprint)) throw new TypeError('work lease key profileFingerprint must be a SHA-256 fingerprint');
	return Object.freeze({ agentId, goalRevision, lifecycleGeneration, sessionEpoch, profileFingerprint });
}

function sameKey(left, right) {
	return left.agentId === right.agentId
		&& left.goalRevision === right.goalRevision
		&& left.lifecycleGeneration === right.lifecycleGeneration
		&& left.sessionEpoch === right.sessionEpoch
		&& left.profileFingerprint === right.profileFingerprint;
}

function isNewer(next, current) {
	if (next.goalRevision !== current.goalRevision) return next.goalRevision > current.goalRevision;
	if (next.lifecycleGeneration !== current.lifecycleGeneration) return next.lifecycleGeneration > current.lifecycleGeneration;
	return next.sessionEpoch > current.sessionEpoch;
}

function safeClone(value) {
	if (value === undefined) return undefined;
	try { return structuredClone(value); } catch { return null; }
}

function recoveryDelay(details) {
	const value = details?.retryDelayMs;
	if (!Number.isFinite(value) || value < 0) return null;
	return Math.min(MAX_RECOVERY_DELAY_MS, Math.ceil(value));
}

function defaultSchedule(callback, delay) {
	const handle = setTimeout(callback, delay);
	handle.unref?.();
	return handle;
}
