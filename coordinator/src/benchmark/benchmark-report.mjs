const MAX_STAGE_LENGTH = 128;
const MAX_ERROR_CODE_LENGTH = 128;

/** Build deterministic, raw-sample-preserving metrics from recorder rows. */
export function summarizeBenchmark(input) {
	const events = Array.isArray(input) ? input : (Array.isArray(input?.events) ? input.events : []);
	const byStage = new Map();
	const errors = new Map();

	for (const event of events) {
		const stage = boundedString(event?.stage, MAX_STAGE_LENGTH) ?? 'unknown';
		let metric = byStage.get(stage);
		if (metric === undefined) {
			metric = { count: 0, durationSamplesMs: [], errorCounts: new Map() };
			byStage.set(stage, metric);
		}
		metric.count += 1;
		const durationMs = firstDuration(event);
		if (durationMs !== null) metric.durationSamplesMs.push(durationMs);
		const errorCode = firstErrorCode(event);
		if (errorCode !== null) {
			metric.errorCounts.set(errorCode, (metric.errorCounts.get(errorCode) ?? 0) + 1);
			errors.set(errorCode, (errors.get(errorCode) ?? 0) + 1);
		}
	}

	const stages = Object.create(null);
	for (const stage of [...byStage.keys()].sort()) {
		const metric = byStage.get(stage);
		const samples = [...metric.durationSamplesMs].sort((left, right) => left - right);
		const errorCounts = Object.fromEntries([...metric.errorCounts.entries()].sort(([left], [right]) => left.localeCompare(right)));
		stages[stage] = Object.freeze({
			count: metric.count,
			durationSampleCount: samples.length,
			durationSamplesMs: Object.freeze([...metric.durationSamplesMs]),
			p50Ms: percentile(samples, 0.50),
			p95Ms: percentile(samples, 0.95),
			p99Ms: percentile(samples, 0.99),
			errorCounts: Object.freeze(errorCounts),
		});
	}

	const summary = {
		version: 1,
		eventCount: events.length,
		droppedEvents: nonNegativeInteger(input?.droppedEvents) ?? 0,
		stages: Object.freeze(stages),
		errors: Object.freeze(Object.fromEntries([...errors.entries()].sort(([left], [right]) => left.localeCompare(right)))),
	};
	return deepFreeze(summary);
}

export function normalizeBenchmarkErrorCode(value) {
	if (value === null || value === undefined) return null;
	const source = typeof value === 'object' ? value.code : value;
	if (source === null || source === undefined) return null;
	const normalized = String(source).trim().toUpperCase().replace(/[^A-Z0-9]+/g, '_').replace(/^_+|_+$/g, '').slice(0, MAX_ERROR_CODE_LENGTH);
	return normalized.length === 0 ? null : normalized;
}

export function classifyBenchmarkError(value) {
	const code = normalizeBenchmarkErrorCode(value);
	if (code === null) return null;
	if (/(AUTH|OAUTH|CREDENTIAL|API_KEY|TOKEN|LOGIN|CATALOG)/.test(code)) return 'authentication';
	if (/TIMEOUT/.test(code)) return 'timeout';
	if (/INVALID|MALFORMED|EMPTY_DECISION|DECISION/.test(code)) return 'invalid_decision';
	if (/ARENA_SCRIPT|COMPILE|SANDBOX/.test(code)) return 'compile';
	if (/SCHEDULER|CAPACITY|PRESSURE/.test(code)) return 'scheduler_pressure';
	if (/BRIDGE|TRANSPORT|DISCONNECT|SEND/.test(code)) return 'transport';
	if (/ACTION|PATH|RECIPE|PLACEMENT/.test(code)) return 'action';
	if (/SIMULATOR|WORLD|TICK/.test(code)) return 'simulator';
	if (/CLEANUP|PROCESS/.test(code)) return 'cleanup';
	if (/PROVIDER|SPAWN|SESSION/.test(code)) return 'provider';
	return 'unknown';
}

function firstDuration(event) {
	for (const value of [event?.durationMs, event?.fields?.durationMs, event?.duration, event?.fields?.duration]) {
		if (Number.isFinite(value) && value >= 0) return value;
	}
	return null;
}

function firstErrorCode(event) {
	for (const value of [event?.errorCode, event?.fields?.errorCode, event?.error?.code, event?.fields?.error?.code]) {
		const code = normalizeBenchmarkErrorCode(value);
		if (code !== null) return code;
	}
	return null;
}

function percentile(sorted, fraction) {
	if (sorted.length === 0) return null;
	return sorted[Math.max(0, Math.ceil(sorted.length * fraction) - 1)];
}

function boundedString(value, limit) {
	if (typeof value !== 'string') return null;
	const normalized = value.trim();
	return normalized.length === 0 ? null : normalized.slice(0, limit);
}

function nonNegativeInteger(value) {
	return Number.isSafeInteger(value) && value >= 0 ? value : null;
}

function deepFreeze(value, seen = new WeakSet()) {
	if (value === null || typeof value !== 'object' || seen.has(value)) return value;
	seen.add(value);
	for (const child of Object.values(value)) deepFreeze(child, seen);
	return Object.freeze(value);
}
