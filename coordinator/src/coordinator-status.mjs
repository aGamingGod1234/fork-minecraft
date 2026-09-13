import { DEFAULT_SERVICE_TIER } from './constants.mjs';
import { types as nodeTypes } from 'node:util';

const RUNTIME_GENERATION = /^[0-9a-f]{64}$/;
const COMPONENT_STATES = new Set(['ready', 'degraded', 'backoff', 'blocked_retryable', 'unknown']);

/** Builds the bounded public recovery snapshot without trusting optional components. */
export function buildCoordinatorStatus({
	reconciled,
	records,
	supportedAgentIds,
	readyStates,
	pressure,
	healthSnapshots,
	latencies,
	bridgeSessionEpoch,
	runtimeGeneration,
	components = [],
}) {
	const profiles = records
		.filter((record) => supportedAgentIds.has(record.agentId))
		.map((record) => ({
			agentId: record.agentId,
			provider: record.provider,
			model: record.model,
			reasoningEffort: record.reasoningEffort,
			serviceTier: record.serviceTier ?? DEFAULT_SERVICE_TIER,
		}))
		.sort((left, right) => left.agentId.localeCompare(right.agentId));
	const recovery = new Map();
	recovery.set('bridge', bridgeComponent(Boolean(reconciled), bridgeSessionEpoch));
	for (const candidate of safeOptionalArray(components, 32)) {
			const component = safeComponent(candidate);
			if (component !== null && component.component !== 'bridge') recovery.set(component.component, component);
	}
	return {
		reconciled: Boolean(reconciled),
		profiles,
		supportedProfileCount: profiles.length,
		rosterReadyCount: records.filter((record) => supportedAgentIds.has(record.agentId) && readyStates.has(record.state)).length,
		rosterCount: records.length,
		scheduler: { ...pressure },
		circuits: safeOptionalArray(healthSnapshots, 32).map(safeCircuit).filter(Boolean),
		latencies: safeOptionalArray(latencies, 16).map(safeLatency).filter(Boolean),
		bridgeSessionEpoch: nonnegativeIntegerOrZero(bridgeSessionEpoch),
		runtimeGeneration: typeof runtimeGeneration === 'string' && RUNTIME_GENERATION.test(runtimeGeneration) ? runtimeGeneration : null,
		components: [...recovery.values()].sort((left, right) => left.component.localeCompare(right.component)).slice(0, 32),
	};
}

export function providerRecoveryComponents(value) {
	const result = [];
	for (const recovery of safeOptionalArray(value, 16)) {
		const own = safeOwnDataRecord(recovery);
		if (own === null) continue;
		const provider = boundedOptionalText(own.provider, false);
		if (provider === null) continue;
		const component = safeComponent({
			component: `provider:${provider}`,
			state: own.state === 'live' ? 'ready' : own.state === 'idle' ? 'unknown' : own.state,
			fallbackMode: own.fallbackMode ?? null,
			boundary: own.boundary ?? null,
			failureCode: own.failureCode ?? null,
			consecutiveFailureCount: own.consecutiveFailureCount ?? 0,
			nextProbeAtEpochMs: own.nextProbeAtEpochMs ?? null,
			generation: own.generation ?? 0,
			lastRecoveryAtEpochMs: own.lastRecoveryAtEpochMs ?? null,
		});
		if (component !== null) result.push(component);
	}
	return result;
}

function bridgeComponent(reconciled, generation) {
	return {
		component: 'bridge',
		state: reconciled ? 'ready' : 'degraded',
		fallbackMode: reconciled ? null : 'waiting',
		boundary: reconciled ? null : 'bridge_reconciliation',
		failureCode: reconciled ? null : 'RECONCILING',
		consecutiveFailureCount: 0,
		nextProbeAtEpochMs: null,
		generation: nonnegativeIntegerOrZero(generation),
		lastRecoveryAtEpochMs: null,
	};
}

function safeComponent(value) {
	const own = safeOwnDataRecord(value);
	if (own === null) return null;
	const component = boundedOptionalText(own.component, false);
	const state = boundedOptionalText(own.state, false);
	if (component === null || state === null || !COMPONENT_STATES.has(state)) return null;
	return {
		component,
		state,
		fallbackMode: boundedOptionalText(own.fallbackMode),
		boundary: boundedOptionalText(own.boundary),
		failureCode: boundedOptionalText(own.failureCode),
		consecutiveFailureCount: nonnegativeIntegerOrZero(own.consecutiveFailureCount),
		nextProbeAtEpochMs: nullableNonnegativeInteger(own.nextProbeAtEpochMs),
		generation: nonnegativeIntegerOrZero(own.generation),
		lastRecoveryAtEpochMs: nullableNonnegativeInteger(own.lastRecoveryAtEpochMs),
	};
}

function safeOptionalArray(value, maximum) {
	try {
		if (!Array.isArray(value) || nodeTypes.isProxy(value)) return [];
		const result = [];
		const length = Math.min(value.length, maximum);
		for (let index = 0; index < length; index += 1) {
			let descriptor;
			try { descriptor = Object.getOwnPropertyDescriptor(value, String(index)); } catch { continue; }
			if (!descriptor || !Object.hasOwn(descriptor, 'value') || nodeTypes.isProxy(descriptor.value)) continue;
			result.push(descriptor.value);
		}
		return result;
	} catch {
		return [];
	}
}

function safeOwnDataRecord(value) {
	try {
		if (value === null || typeof value !== 'object' || Array.isArray(value) || nodeTypes.isProxy(value)) return null;
		const prototype = Object.getPrototypeOf(value);
		if (prototype !== Object.prototype && prototype !== null) return null;
		const keys = Reflect.ownKeys(value);
		if (keys.some((key) => typeof key !== 'string')) return null;
		const result = Object.create(null);
		for (const key of keys) {
			const descriptor = Object.getOwnPropertyDescriptor(value, key);
			if (!descriptor || !Object.hasOwn(descriptor, 'value')) return null;
			if (descriptor.enumerable) result[key] = descriptor.value;
		}
		return result;
	} catch {
		return null;
	}
}

function safeCircuit(value) {
	const own = safeOwnDataRecord(value);
	if (own === null) return null;
	const provider = boundedOptionalText(own.provider, false);
	const model = boundedOptionalText(own.model, false);
	const operation = boundedOptionalText(own.operation, false);
	if (provider === null || model === null || operation === null) return null;
	if (!Number.isSafeInteger(own.count) || own.count < 0
		|| !Number.isSafeInteger(own.p50Ms) || own.p50Ms < 0
		|| !Number.isSafeInteger(own.p95Ms) || own.p95Ms < 0
		|| !Number.isFinite(own.failureRate) || own.failureRate < 0 || own.failureRate > 1
		|| !['closed', 'open', 'half_open'].includes(own.circuit)) return null;
	return { provider, model, operation, count: own.count, p50Ms: own.p50Ms, p95Ms: own.p95Ms, failureRate: own.failureRate, circuit: own.circuit };
}

function safeLatency(value) {
	const own = safeOwnDataRecord(value);
	if (own === null) return null;
	const operation = boundedOptionalText(own.operation, false);
	if (operation === null || !Number.isSafeInteger(own.count) || own.count < 0
		|| !Number.isFinite(own.p50Ms) || own.p50Ms < 0
		|| !Number.isFinite(own.p95Ms) || own.p95Ms < 0) return null;
	return { operation, count: own.count, p50Ms: own.p50Ms, p95Ms: own.p95Ms };
}

function boundedOptionalText(value, nullable = true) {
	if (value === null || value === undefined) return nullable ? null : null;
	if (typeof value !== 'string' || value.trim().length === 0 || value.length > 128) return null;
	return value;
}

function nullableNonnegativeInteger(value) {
	return value === null || value === undefined ? null : nonnegativeIntegerOrZero(value);
}

function nonnegativeIntegerOrZero(value) {
	return Number.isSafeInteger(value) && value >= 0 ? value : 0;
}
