const MAX_TRIALS = 10_000;
const MAX_BOOTSTRAP_SAMPLES = 10_000;
const DEFAULT_BOOTSTRAP_SAMPLES = 2_000;
const DEFAULT_ANALYSIS_SEED = 0x1a2b3c4d;
const DEFAULT_CONFIDENCE_LEVEL = 0.95;
const SMALL_SAMPLE_THRESHOLD = 30;
const MAX_TEXT_LENGTH = 256;
const MAX_SAFE_MAGNITUDE = Number.MAX_SAFE_INTEGER;

const DANGEROUS_IDENTIFIERS = new Set(['__proto__', 'prototype', 'constructor']);
const STATUS_ALIASES = new Map([
	['passed', 'passed'],
	['pass', 'passed'],
	['failed', 'failed'],
	['failure', 'failed'],
	['skipped', 'skipped'],
	['skip', 'skipped'],
	['timed_out', 'timedOut'],
	['timed-out', 'timedOut'],
	['timedout', 'timedOut'],
	['timeout', 'timedOut'],
]);

const ERROR_CATEGORIES = [
	'authentication_catalog_rejection',
	'process_startup',
	'session_initialization',
	'transport_failure',
	'timeout',
	'empty_response',
	'invalid_decision',
	'arenascript_compile_failure',
	'scheduler_pressure',
	'stale_work',
	'simulator_failure',
	'action_failure',
	'cleanup_failure',
	'skipped',
	'unknown',
];

const ERROR_CATEGORY_ALIASES = new Map([
	['authentication_catalog_rejection', 'authentication_catalog_rejection'],
	['authentication_rejection', 'authentication_catalog_rejection'],
	['authentication', 'authentication_catalog_rejection'],
	['catalog_rejection', 'authentication_catalog_rejection'],
	['catalog', 'authentication_catalog_rejection'],
	['auth', 'authentication_catalog_rejection'],
	['process_startup', 'process_startup'],
	['startup', 'process_startup'],
	['process_start', 'process_startup'],
	['session_initialization', 'session_initialization'],
	['session_init', 'session_initialization'],
	['initialization', 'session_initialization'],
	['transport_failure', 'transport_failure'],
	['transport', 'transport_failure'],
	['network', 'transport_failure'],
	['timeout', 'timeout'],
	['timed_out', 'timeout'],
	['timed_out_error', 'timeout'],
	['empty_response', 'empty_response'],
	['empty', 'empty_response'],
	['invalid_decision', 'invalid_decision'],
	['decision_invalid', 'invalid_decision'],
	['arenascript_compile_failure', 'arenascript_compile_failure'],
	['arena_script_compile_failure', 'arenascript_compile_failure'],
	['compile_failure', 'arenascript_compile_failure'],
	['scheduler_pressure', 'scheduler_pressure'],
	['scheduler', 'scheduler_pressure'],
	['stale_work', 'stale_work'],
	['stale', 'stale_work'],
	['simulator_failure', 'simulator_failure'],
	['simulator', 'simulator_failure'],
	['action_failure', 'action_failure'],
	['action', 'action_failure'],
	['cleanup_failure', 'cleanup_failure'],
	['cleanup', 'cleanup_failure'],
	['skipped', 'skipped'],
	['skip', 'skipped'],
	['unknown', 'unknown'],
]);

const hasOwn = (value, key) => Object.prototype.hasOwnProperty.call(value, key);

/**
 * Analyze two normalized, paired trial arrays without depending on the runner.
 * A trial has a pairingKey (or key/trialId/id), status, optional latencyMs, and
 * optional error metadata. Latencies include measured failures and timeouts;
 * skipped trials without a measurement never become zero-millisecond samples.
 */
export function analyzePairedTrials(input, optimizedArgument, optionsArgument) {
	const { baseline, optimized, options } = readArguments(input, optimizedArgument, optionsArgument);
	const normalizedOptions = normalizeOptions(options);
	const baselineTrials = normalizeTrials(baseline, 'baseline');
	const optimizedTrials = normalizeTrials(optimized, 'optimized');
	const pairs = pairTrials(baselineTrials, optimizedTrials);

	const baselineCounts = countStatuses(baselineTrials);
	const optimizedCounts = countStatuses(optimizedTrials);
	const baselineLatency = summarizeLatency(baselineTrials.map((trial) => trial.latencyMs).filter((value) => value !== null));
	const optimizedLatency = summarizeLatency(optimizedTrials.map((trial) => trial.latencyMs).filter((value) => value !== null));
	const absoluteDeltas = [];
	const percentageDeltas = [];
	let zeroBaselineCount = 0;
	let incompleteCount = 0;
	for (const [baselineTrial, optimizedTrial] of pairs) {
		if (baselineTrial.latencyMs === null || optimizedTrial.latencyMs === null) {
			incompleteCount += 1;
			continue;
		}
		const absoluteDelta = safeDelta(optimizedTrial.latencyMs - baselineTrial.latencyMs, 'paired absolute delta');
		absoluteDeltas.push(absoluteDelta);
		if (baselineTrial.latencyMs === 0) {
			zeroBaselineCount += 1;
			continue;
		}
		const percentageDelta = safeDelta(((optimizedTrial.latencyMs - baselineTrial.latencyMs) / baselineTrial.latencyMs) * 100, 'paired percentage delta');
		percentageDeltas.push(percentageDelta);
	}

	const rng = createRandom(normalizedOptions.analysisSeed);
	const pairedAbsolute = summarizeDelta(absoluteDeltas, 'Ms', normalizedOptions, rng);
	const pairedPercentage = summarizeDelta(percentageDeltas, 'Percent', normalizedOptions, rng);
	const baselineErrors = summarizeErrors(baselineTrials);
	const optimizedErrors = summarizeErrors(optimizedTrials);
	const provisional = Math.min(
		baselineLatency.sampleCount,
		optimizedLatency.sampleCount,
		absoluteDeltas.length,
	) < SMALL_SAMPLE_THRESHOLD;

	return deepFreeze({
		analysisSeed: normalizedOptions.analysisSeed,
		bootstrap: {
			analysisSeed: normalizedOptions.analysisSeed,
			resamples: normalizedOptions.bootstrapSamples,
			confidenceLevel: normalizedOptions.confidenceLevel,
			method: 'paired-mean-percentile',
		},
		provisional,
		sampleLabel: provisional ? 'provisional' : 'informative',
		pairCount: pairs.length,
		counts: {
			baseline: baselineCounts,
			optimized: optimizedCounts,
		},
		latency: {
			baseline: baselineLatency,
			optimized: optimizedLatency,
		},
		paired: {
			count: pairs.length,
			latencyCount: absoluteDeltas.length,
			percentageCount: percentageDeltas.length,
			incompleteCount,
			zeroBaselineCount,
			absoluteDeltaMs: pairedAbsolute,
			percentageDelta: pairedPercentage,
		},
		successRate: {
			baseline: summarizeSuccessRate(baselineTrials, baselineCounts),
			optimized: summarizeSuccessRate(optimizedTrials, optimizedCounts),
			difference: rateDifference(baselineCounts, optimizedCounts, 'rate'),
			percentagePoints: rateDifference(baselineCounts, optimizedCounts, 'rate', 100),
			attemptedDifference: rateDifference(baselineCounts, optimizedCounts, 'attemptedRate'),
			attemptedPercentagePoints: rateDifference(baselineCounts, optimizedCounts, 'attemptedRate', 100),
		},
		errorTaxonomy: {
			baseline: baselineErrors,
			optimized: optimizedErrors,
		},
	});
}

export const analyzePairedExperiment = analyzePairedTrials;
export const MAX_ANALYSIS_TRIALS = MAX_TRIALS;
export const MAX_ANALYSIS_BOOTSTRAP_SAMPLES = MAX_BOOTSTRAP_SAMPLES;
export const DEFAULT_ANALYSIS_SEED_VALUE = DEFAULT_ANALYSIS_SEED;

function readArguments(input, optimizedArgument, optionsArgument) {
	if (Array.isArray(input)) {
		return { baseline: input, optimized: optimizedArgument, options: optionsArgument ?? {} };
	}
	if (!isPlainRecord(input)) throw new TypeError('analysis input must be an object or baseline array');
	return {
		baseline: input.baseline,
		optimized: input.optimized,
		options: input.options === undefined ? input : input.options,
	};
}

function normalizeOptions(value) {
	if (!isPlainRecord(value)) throw new TypeError('analysis options must be an object');
	const bootstrapSamples = value.bootstrapSamples === undefined ? DEFAULT_BOOTSTRAP_SAMPLES : value.bootstrapSamples;
	if (!Number.isSafeInteger(bootstrapSamples) || bootstrapSamples < 0 || bootstrapSamples > MAX_BOOTSTRAP_SAMPLES) {
		throw new RangeError(`bootstrapSamples must be an integer in [0, ${MAX_BOOTSTRAP_SAMPLES}]`);
	}
	const analysisSeed = value.analysisSeed === undefined ? DEFAULT_ANALYSIS_SEED : value.analysisSeed;
	if (!Number.isSafeInteger(analysisSeed) || analysisSeed < 0 || analysisSeed > 0xffffffff) {
		throw new RangeError('analysisSeed must be a non-negative 32-bit safe integer');
	}
	const confidenceLevel = value.confidenceLevel === undefined ? DEFAULT_CONFIDENCE_LEVEL : value.confidenceLevel;
	if (!Number.isFinite(confidenceLevel) || confidenceLevel <= 0 || confidenceLevel >= 1) {
		throw new RangeError('confidenceLevel must be finite and in (0, 1)');
	}
	return { bootstrapSamples, analysisSeed, confidenceLevel };
}

function normalizeTrials(value, arm) {
	if (!Array.isArray(value)) throw new TypeError(`${arm} trials must be an array`);
	if (value.length > MAX_TRIALS) throw new RangeError(`${arm} trials exceed the bounded maximum of ${MAX_TRIALS}`);
	const normalized = [];
	for (let index = 0; index < value.length; index += 1) {
		if (!hasOwn(value, index)) throw new TypeError(`${arm} trials must not contain sparse entries`);
		normalized.push(normalizeTrial(value[index], arm, index));
	}
	return normalized;
}

function normalizeTrial(value, arm, index) {
	if (!isPlainRecord(value)) throw new TypeError(`${arm} trial ${index} must be a plain object`);
	for (const key of Object.keys(value)) {
		if (isDangerousIdentifier(key)) throw new TypeError(`${arm} trial ${index} contains an unsafe field`);
	}

	const pairingKey = readRequiredAlias(value, ['pairingKey', 'trialId', 'key', 'id'], `${arm} trial ${index} pairing key`);
	const statusValue = readRequiredAlias(value, ['status'], `${arm} trial ${index} status`);
	const status = normalizeStatus(statusValue, `${arm} trial ${index} status`);
	const latencyMs = normalizeLatency(readOptionalAlias(value, ['latencyMs', 'durationMs']), `${arm} trial ${index} latencyMs`);
	const { category, hasMetadata } = normalizeErrorMetadata(value, status, `${arm} trial ${index}`);
	if (status === 'passed' && hasMetadata) throw new TypeError(`${arm} trial ${index} has contradictory success and error metadata`);
	return Object.freeze({ pairingKey, status, latencyMs, errorCategory: category });
}

function readRequiredAlias(value, aliases, label) {
	const present = aliases.filter((key) => hasOwn(value, key) && value[key] !== undefined && value[key] !== null);
	if (present.length === 0) throw new TypeError(`${label} is required`);
	const normalized = present.map((key) => requireText(value[key], label));
	if (new Set(normalized).size !== 1) throw new TypeError(`${label} aliases disagree`);
	return normalized[0];
}

function readOptionalAlias(value, aliases) {
	const present = aliases.filter((key) => hasOwn(value, key) && value[key] !== undefined && value[key] !== null);
	if (present.length === 0) return null;
	const values = present.map((key) => value[key]);
	if (values.some((item) => typeof item !== 'number')) return values[0];
	if (new Set(values).size !== 1) throw new TypeError(`${aliases.join('/')} aliases disagree`);
	return values[0];
}

function normalizeStatus(value, label) {
	const status = requireText(value, label).toLowerCase().replace(/\s+/g, '_');
	const normalized = STATUS_ALIASES.get(status);
	if (normalized === undefined) throw new TypeError(`${label} must be passed, failed, skipped, or timed-out`);
	return normalized;
}

function normalizeLatency(value, label) {
	if (value === null || value === undefined) return null;
	if (typeof value !== 'number' || !Number.isFinite(value) || value < 0 || Math.abs(value) > MAX_SAFE_MAGNITUDE) {
		throw new TypeError(`${label} must be a non-negative finite safe number`);
	}
	return value === 0 ? 0 : value;
}

function normalizeErrorMetadata(value, status, label) {
	let rawCategory;
	let hasMetadata = false;
	if (hasOwn(value, 'error') && value.error !== null && value.error !== undefined) {
		hasMetadata = true;
		if (typeof value.error === 'string') {
			rawCategory = value.error;
		} else if (isPlainRecord(value.error)) {
			for (const key of Object.keys(value.error)) {
				if (isDangerousIdentifier(key)) throw new TypeError(`${label} error contains an unsafe field`);
			}
			rawCategory = firstPresent(value.error, ['category', 'errorCategory', 'code', 'kind', 'reason']);
		} else {
			throw new TypeError(`${label} error must be a string or plain object`);
		}
	}
	for (const field of ['errorCategory', 'errorCode']) {
		if (hasOwn(value, field) && value[field] !== null && value[field] !== undefined) {
			hasMetadata = true;
			if (rawCategory === undefined) rawCategory = value[field];
		}
	}
	if (rawCategory === undefined || rawCategory === null || rawCategory === '') {
		return { category: status === 'skipped' ? 'skipped' : status === 'timedOut' ? 'timeout' : 'unknown', hasMetadata };
	}
	const normalizedCategory = normalizeCategory(rawCategory, `${label} error category`);
	return { category: normalizedCategory, hasMetadata: true };
}

function firstPresent(value, fields) {
	for (const field of fields) {
		if (hasOwn(value, field) && value[field] !== null && value[field] !== undefined && value[field] !== '') return value[field];
	}
	return undefined;
}

function normalizeCategory(value, label) {
	const text = requireText(value, label).toLowerCase();
	const normalized = text.replace(/[^a-z0-9]+/g, '_').replace(/^_+|_+$/g, '');
	if (isDangerousIdentifier(normalized)) throw new TypeError(`${label} contains an unsafe identifier`);
	const direct = ERROR_CATEGORY_ALIASES.get(normalized);
	if (direct !== undefined) return direct;
	if (normalized.includes('timeout') || normalized.includes('timed_out')) return 'timeout';
	if (normalized.includes('auth') || normalized.includes('catalog')) return 'authentication_catalog_rejection';
	if (normalized.includes('startup') || normalized.includes('spawn')) return 'process_startup';
	if (normalized.includes('session') || normalized.includes('initialize') || normalized.includes('initialization')) return 'session_initialization';
	if (normalized.includes('transport') || normalized.includes('network')) return 'transport_failure';
	if (normalized.includes('empty') && normalized.includes('response')) return 'empty_response';
	if (normalized.includes('invalid') && normalized.includes('decision')) return 'invalid_decision';
	if (normalized.includes('compile')) return 'arenascript_compile_failure';
	if (normalized.includes('scheduler') || normalized.includes('queue')) return 'scheduler_pressure';
	if (normalized.includes('stale')) return 'stale_work';
	if (normalized.includes('simulator')) return 'simulator_failure';
	if (normalized.includes('action')) return 'action_failure';
	if (normalized.includes('cleanup')) return 'cleanup_failure';
	return 'unknown';
}

function requireText(value, label) {
	if (typeof value !== 'string') throw new TypeError(`${label} must be a string`);
	const text = value.trim();
	if (text.length === 0 || text.length > MAX_TEXT_LENGTH || /[\u0000-\u001f\u007f]/u.test(text)) {
		throw new TypeError(`${label} must be nonblank, bounded, and safe`);
	}
	if (isDangerousIdentifier(text.toLowerCase())) throw new TypeError(`${label} contains an unsafe identifier`);
	return text;
}

function isDangerousIdentifier(value) {
	return DANGEROUS_IDENTIFIERS.has(String(value).toLowerCase());
}

function isPlainRecord(value) {
	if (value === null || typeof value !== 'object' || Array.isArray(value)) return false;
	const prototype = Object.getPrototypeOf(value);
	return prototype === Object.prototype || prototype === null;
}

function pairTrials(baseline, optimized) {
	const baselineMap = indexPairs(baseline, 'baseline');
	const optimizedMap = indexPairs(optimized, 'optimized');
	if (baselineMap.size !== optimizedMap.size) throw new TypeError('baseline and optimized pairing keys mismatch');
	for (const key of baselineMap.keys()) {
		if (!optimizedMap.has(key)) throw new TypeError(`baseline and optimized pairing keys mismatch at ${key}`);
	}
	return [...baselineMap.keys()].sort(compareKeys).map((key) => [baselineMap.get(key), optimizedMap.get(key)]);
}

function indexPairs(trials, arm) {
	const indexed = new Map();
	for (const trial of trials) {
		if (indexed.has(trial.pairingKey)) throw new TypeError(`${arm} contains a duplicate pairing key`);
		indexed.set(trial.pairingKey, trial);
	}
	return indexed;
}

function compareKeys(left, right) {
	if (left < right) return -1;
	if (left > right) return 1;
	return 0;
}

function countStatuses(trials) {
	const counts = { total: trials.length, passed: 0, failed: 0, skipped: 0, timedOut: 0 };
	for (const trial of trials) counts[trial.status] += 1;
	return counts;
}

function summarizeLatency(values) {
	const sorted = [...values].sort((left, right) => left - right);
	const sampleCount = sorted.length;
	const provisional = sampleCount < SMALL_SAMPLE_THRESHOLD;
	return {
		sampleCount,
		p50Ms: percentile(sorted, 0.5),
		p95Ms: percentile(sorted, 0.95),
		p99Ms: percentile(sorted, 0.99),
		provisional,
		sampleLabel: provisional ? 'provisional' : 'informative',
	};
}

function summarizeDelta(values, unit, options, rng) {
	const sorted = [...values].sort((left, right) => left - right);
	const sampleCount = sorted.length;
	const provisional = sampleCount < SMALL_SAMPLE_THRESHOLD;
	const mean = sampleCount === 0 ? null : normalizeOutputNumber(sorted.reduce((sum, value) => sum + value, 0) / sampleCount);
	const confidenceInterval = sampleCount === 0 || options.bootstrapSamples === 0
		? null
		: bootstrapConfidenceInterval(sorted, options.bootstrapSamples, options.confidenceLevel, rng);
	const names = unit === 'Ms'
		? { p50: 'p50Ms', p95: 'p95Ms', p99: 'p99Ms', mean: 'meanMs' }
		: { p50: 'p50Percent', p95: 'p95Percent', p99: 'p99Percent', mean: 'meanPercent' };
	return {
		sampleCount,
		[names.p50]: percentile(sorted, 0.5),
		[names.p95]: percentile(sorted, 0.95),
		[names.p99]: percentile(sorted, 0.99),
		[names.mean]: mean,
		confidenceInterval,
		provisional,
		sampleLabel: provisional ? 'provisional' : 'informative',
	};
}

function percentile(sorted, fraction) {
	if (sorted.length === 0) return null;
	return normalizeOutputNumber(sorted[Math.max(0, Math.ceil(sorted.length * fraction) - 1)]);
}

function summarizeSuccessRate(trials, counts) {
	const attempted = counts.total - counts.skipped;
	return {
		total: counts.total,
		successes: counts.passed,
		attempted,
		skipped: counts.skipped,
		rate: counts.total === 0 ? null : counts.passed / counts.total,
		attemptedRate: attempted === 0 ? null : counts.passed / attempted,
	};
}

function rateDifference(baselineCounts, optimizedCounts, field, multiplier = 1) {
	const baseline = rateFromCounts(baselineCounts, field);
	const optimized = rateFromCounts(optimizedCounts, field);
	if (baseline === null || optimized === null) return null;
	return normalizeOutputNumber((optimized - baseline) * multiplier);
}

function rateFromCounts(counts, field) {
	if (field === 'rate') return counts.total === 0 ? null : counts.passed / counts.total;
	const attempted = counts.total - counts.skipped;
	return attempted === 0 ? null : counts.passed / attempted;
}

function summarizeErrors(trials) {
	const counts = new Map();
	for (const trial of trials) {
		if (trial.status === 'passed') continue;
		counts.set(trial.errorCategory, (counts.get(trial.errorCategory) ?? 0) + 1);
	}
	const byCategory = {};
	for (const category of ERROR_CATEGORIES) {
		const count = counts.get(category);
		if (count !== undefined) Object.defineProperty(byCategory, category, { value: count, enumerable: true, writable: false, configurable: false });
	}
	return { total: trials.length - trials.filter((trial) => trial.status === 'passed').length, byCategory };
}

function safeDelta(value, label) {
	if (!Number.isFinite(value) || Math.abs(value) > MAX_SAFE_MAGNITUDE) throw new RangeError(`${label} is not a finite safe number`);
	return normalizeOutputNumber(value);
}

function normalizeOutputNumber(value) {
	if (value === null) return null;
	if (!Number.isFinite(value) || Math.abs(value) > MAX_SAFE_MAGNITUDE) throw new RangeError('analysis produced a non-finite or unsafe number');
	return Object.is(value, -0) ? 0 : value;
}

function bootstrapConfidenceInterval(values, resamples, confidenceLevel, rng) {
	const means = new Array(resamples);
	for (let sampleIndex = 0; sampleIndex < resamples; sampleIndex += 1) {
		let total = 0;
		for (let valueIndex = 0; valueIndex < values.length; valueIndex += 1) total += values[rng(values.length)];
		means[sampleIndex] = normalizeOutputNumber(total / values.length);
	}
	means.sort((left, right) => left - right);
	const tail = (1 - confidenceLevel) / 2;
	return {
		low: percentile(means, tail),
		high: percentile(means, 1 - tail),
		level: confidenceLevel,
		method: 'bootstrap-mean-percentile',
		resamples,
	};
}

function createRandom(seed) {
	let state = seed >>> 0;
	return (bound) => {
		state = (state + 0x6d2b79f5) >>> 0;
		let value = Math.imul(state ^ (state >>> 15), 1 | state);
		value ^= value + Math.imul(value ^ (value >>> 7), 61 | value);
		const unit = ((value ^ (value >>> 14)) >>> 0) / 4_294_967_296;
		return Math.floor(unit * bound);
	};
}

function deepFreeze(value) {
	if (value === null || typeof value !== 'object' || Object.isFrozen(value)) return value;
	for (const child of Object.values(value)) deepFreeze(child);
	return Object.freeze(value);
}
