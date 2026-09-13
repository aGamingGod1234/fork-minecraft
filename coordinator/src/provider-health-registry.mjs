import { createProviderTurnTelemetry } from './provider-turn-telemetry.mjs';

const DEFAULT_WINDOW_SIZE = 50;
const DEFAULT_MINIMUM_SAMPLES = 5;
const DEFAULT_FAILURE_RATE = 0.6;
const DEFAULT_COOLDOWN_MS = 30_000;
const DEFAULT_PROBE_TIMEOUT_MS = 125_000;

export class ProviderHealthRegistry {
	#windowSize;
	#minimumSamples;
	#failureRateToOpen;
	#cooldownMs;
	#probeTimeoutMs;
	#now;
	#operations = new Map();

	constructor({
		windowSize = DEFAULT_WINDOW_SIZE,
		minimumSamples = DEFAULT_MINIMUM_SAMPLES,
		failureRateToOpen = DEFAULT_FAILURE_RATE,
		cooldownMs = DEFAULT_COOLDOWN_MS,
		probeTimeoutMs = DEFAULT_PROBE_TIMEOUT_MS,
		now = Date.now,
	} = {}) {
		if (!Number.isSafeInteger(windowSize) || windowSize < 1) throw new TypeError('windowSize must be a positive safe integer');
		if (!Number.isSafeInteger(minimumSamples) || minimumSamples < 1 || minimumSamples > windowSize) throw new TypeError('minimumSamples must be between 1 and windowSize');
		if (!Number.isFinite(failureRateToOpen) || failureRateToOpen <= 0 || failureRateToOpen > 1) throw new TypeError('failureRateToOpen must be in (0, 1]');
		if (!Number.isSafeInteger(cooldownMs) || cooldownMs < 1) throw new TypeError('cooldownMs must be a positive safe integer');
		if (!Number.isSafeInteger(probeTimeoutMs) || probeTimeoutMs < 1) throw new TypeError('probeTimeoutMs must be a positive safe integer');
		if (typeof now !== 'function') throw new TypeError('now must be a function');
		this.#windowSize = windowSize;
		this.#minimumSamples = minimumSamples;
		this.#failureRateToOpen = failureRateToOpen;
		this.#cooldownMs = cooldownMs;
		this.#probeTimeoutMs = probeTimeoutMs;
		this.#now = now;
	}

	record(value) {
		const telemetry = createProviderTurnTelemetry({
			...value,
			model: value?.model ?? 'unknown',
			operation: value?.operation ?? 'unknown',
			attempt: value?.attempt ?? 1,
			queueWaitMs: value?.queueWaitMs ?? 0,
			timeout: value?.timeout ?? false,
			retry: value?.retry ?? false,
			restart: value?.restart ?? false,
		});
		const state = this.#state(telemetry);
		if (isNeutralOutcome(telemetry.errorCode)) {
			if (state.circuit === 'half_open') {
				state.probeInFlight = false;
				state.probeDeadlineAt = null;
			}
			return telemetry;
		}
		const failed = telemetry.errorCode !== null;
		if (state.circuit === 'half_open') {
			state.probeInFlight = false;
			state.probeDeadlineAt = null;
			if (failed) {
				this.#open(state);
			} else {
				state.circuit = 'closed';
				state.openedAt = null;
				state.samples = [];
			}
		}
		state.samples.push(Object.freeze({ durationMs: telemetry.durationMs, failed }));
		if (state.samples.length > this.#windowSize) state.samples.splice(0, state.samples.length - this.#windowSize);
		if (state.circuit === 'closed' && this.#shouldOpen(state.samples)) this.#open(state);
		return telemetry;
	}

	canAttempt(identityValue, nowValue = this.#now()) {
		const identity = requireIdentity(identityValue);
		if (!Number.isFinite(nowValue)) throw new TypeError('now must be finite');
		const state = this.#state(identity);
		if (state.circuit === 'closed') return true;
		if (state.circuit === 'open') {
			if (nowValue - state.openedAt < this.#cooldownMs) return false;
			state.circuit = 'half_open';
		}
		if (state.probeInFlight && nowValue < state.probeDeadlineAt) return false;
		if (state.probeInFlight) state.probeInFlight = false;
		state.probeInFlight = true;
		state.probeDeadlineAt = nowValue + this.#probeTimeoutMs;
		return true;
	}

	snapshot(identityValue) {
		const identity = requireIdentity(identityValue);
		const state = this.#state(identity);
		const durations = state.samples.map((sample) => sample.durationMs).sort((left, right) => left - right);
		const failures = state.samples.reduce((count, sample) => count + (sample.failed ? 1 : 0), 0);
		return Object.freeze({
			...identity,
			count: durations.length,
			p50Ms: percentile(durations, 0.5),
			p95Ms: percentile(durations, 0.95),
			failureRate: durations.length === 0 ? 0 : failures / durations.length,
			circuit: state.circuit,
			...(state.circuit === 'open' && state.openedAt !== null ? { nextProbeAtEpochMs: state.openedAt + this.#cooldownMs } : {}),
			...(state.circuit === 'half_open' && state.probeDeadlineAt !== null ? { nextProbeAtEpochMs: state.probeDeadlineAt } : {}),
		});
	}

	reset() {
		this.#operations.clear();
	}

	#state(identityValue) {
		const identity = requireIdentity(identityValue);
		const key = JSON.stringify([identity.profileFingerprint ?? `legacy:${identity.provider}:${identity.model}`, identity.operation]);
		let state = this.#operations.get(key);
		if (state === undefined) {
			state = { samples: [], circuit: 'closed', openedAt: null, probeInFlight: false, probeDeadlineAt: null };
			this.#operations.set(key, state);
		}
		return state;
	}

	#shouldOpen(samples) {
		if (samples.length < this.#minimumSamples) return false;
		return samples.filter((sample) => sample.failed).length / samples.length >= this.#failureRateToOpen;
	}

	#open(state) {
		state.circuit = 'open';
		state.openedAt = this.#now();
		state.probeInFlight = false;
		state.probeDeadlineAt = null;
	}
}

function requireIdentity(value) {
	if (value === null || typeof value !== 'object' || Array.isArray(value)) throw new TypeError('provider health identity must be an object');
	return Object.freeze({
		provider: requirePart(value.provider, 'provider'),
		model: requirePart(value.model, 'model'),
		operation: requirePart(value.operation, 'operation'),
		...(value.profileFingerprint === undefined ? {} : { profileFingerprint: requireFingerprint(value.profileFingerprint) }),
	});
}

function requireFingerprint(value) {
	if (typeof value !== 'string' || !/^sha256:[0-9a-f]{64}$/.test(value)) throw new TypeError('profileFingerprint must be a sha256 fingerprint');
	return value;
}

function requirePart(value, field) {
	if (typeof value !== 'string' || value.trim().length === 0 || value.length > 128) throw new TypeError(`${field} must be nonblank and at most 128 characters`);
	return value.trim();
}

function isNeutralOutcome(errorCode) {
	return errorCode === 'PLAN_CANCELLED'
		|| errorCode === 'STALE_PLAN'
		|| errorCode === 'MISSING_AGENT_MESSAGE'
		|| errorCode === 'MISSING_FINAL_MESSAGE';
}

function percentile(sorted, fraction) {
	if (sorted.length === 0) return 0;
	return sorted[Math.max(0, Math.ceil(sorted.length * fraction) - 1)];
}
