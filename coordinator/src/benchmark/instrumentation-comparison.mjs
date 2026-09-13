import { mkdir, writeFile } from 'node:fs/promises';
import path from 'node:path';

import { runLatencyMatrix } from './latency-runner.mjs';

const MINIMUM_COMPARISON_SAMPLES = 5;
const MAX_COMPARISON_TRIALS = 10_000;

export async function runInstrumentationComparison(options = {}) {
	const runner = options.runMatrix ?? runLatencyMatrix;
	if (typeof runner !== 'function') throw new TypeError('runMatrix must be a function');
	const common = { ...options };
	for (const key of ['runMatrix', 'first', 'maxP95Ratio', 'minimumSamples', 'artifactDirectory']) delete common[key];
	const first = options.first === 'enabled' ? 'enabled' : 'disabled';
	const orders = first === 'enabled' ? [['enabled', 'disabled'], ['disabled', 'enabled']] : [['disabled', 'enabled'], ['enabled', 'disabled']];
	const runs = { enabled: [], disabled: [] };
	for (let round = 0; round < orders.length; round += 1) {
		for (const arm of orders[round]) {
			const artifactDirectory = options.artifactDirectory ? path.join(path.resolve(options.artifactDirectory), `round-${round + 1}-${arm}`) : null;
			runs[arm].push(await runner({ ...common, ...(artifactDirectory ? { artifactDirectory } : {}), measurements: arm === 'enabled', instrumentation: arm === 'enabled', collectMetrics: arm === 'enabled' }));
		}
	}
	const comparison = compareInstrumentationRuns({ enabled: runs.enabled, disabled: runs.disabled, maxP95Ratio: options.maxP95Ratio, minimumSamples: options.minimumSamples, orders });
	if (options.artifactDirectory) {
		await mkdir(path.resolve(options.artifactDirectory), { recursive: true });
		await writeFile(path.join(path.resolve(options.artifactDirectory), 'instrumentation-comparison.json'), `${JSON.stringify(comparison, null, 2)}\n`, 'utf8');
	}
	return comparison;
}

export function compareInstrumentationRuns({ enabled, disabled, maxP95Ratio = 1.05, minimumSamples = MINIMUM_COMPARISON_SAMPLES, orders = [['disabled', 'enabled'], ['enabled', 'disabled']], order } = {}) {
	if (!Number.isFinite(maxP95Ratio) || maxP95Ratio < 1) throw new TypeError('maxP95Ratio must be at least 1');
	if (!Number.isSafeInteger(minimumSamples) || minimumSamples < 1) throw new TypeError('minimumSamples must be a positive integer');
	const normalizedOrders = order === undefined ? orders : [order];
	const enabledTrials = normalizeTrials(enabled, 'enabled');
	const disabledTrials = normalizeTrials(disabled, 'disabled');
	const disabledByKey = indexUnique(disabledTrials, 'disabled');
	const pairs = [];
	for (const trial of enabledTrials) {
		const key = keyFor(trial);
		const peer = disabledByKey.get(key);
		if (!peer) throw new TypeError(`instrumentation comparison is missing disabled trial '${printableKey(key)}'`);
		const enabledDurationMs = finiteDuration(trial.durationMs);
		const disabledDurationMs = finiteDuration(peer.durationMs);
		const successful = trial.status === 'PASSED' && peer.status === 'PASSED'
			&& trial.cleanup?.ok === true && peer.cleanup?.ok === true;
		const actionHash = nonblankHash(trial.debug?.actionCommandHash) && trial.debug.actionCommandHash === peer.debug?.actionCommandHash;
		const scenarioHash = nonblankHash(trial.debug?.scenarioDigest) && trial.debug.scenarioDigest === peer.debug?.scenarioDigest;
		pairs.push({
			key: printableKey(key),
			successful,
			behaviorParity: successful && actionHash && scenarioHash,
			enabledStatus: trial.status,
			disabledStatus: peer.status,
			enabledCleanupOk: trial.cleanup?.ok === true,
			disabledCleanupOk: peer.cleanup?.ok === true,
			enabledDurationMs,
			disabledDurationMs,
			durationRatio: !successful || enabledDurationMs === null || disabledDurationMs === null || disabledDurationMs === 0 ? null : enabledDurationMs / disabledDurationMs,
		});
		disabledByKey.delete(key);
	}
	if (disabledByKey.size > 0) throw new TypeError(`instrumentation comparison has extra disabled trial '${printableKey(disabledByKey.keys().next().value)}'`);
	const successfulPairs = pairs.filter((pair) => pair.successful);
	const enabledDurations = successfulPairs.map((pair) => pair.enabledDurationMs).filter(Number.isFinite).sort((a, b) => a - b);
	const disabledDurations = successfulPairs.map((pair) => pair.disabledDurationMs).filter(Number.isFinite).sort((a, b) => a - b);
	const durationRatios = pairs.map((pair) => pair.durationRatio).filter(Number.isFinite).sort((a, b) => a - b);
	const enabledP95Ms = percentile(enabledDurations, 0.95);
	const disabledP95Ms = percentile(disabledDurations, 0.95);
	const p95Ratio = percentile(durationRatios, 0.95);
	const checks = [
		{ code: 'INSTRUMENTATION_SAMPLE_COUNT', status: successfulPairs.length >= minimumSamples && durationRatios.length >= minimumSamples ? 'PASSED' : 'FAILED', observed: { pairs: pairs.length, successfulPairs: successfulPairs.length, pairedRatios: durationRatios.length }, required: minimumSamples },
		{ code: 'INSTRUMENTATION_BEHAVIOR_PARITY', status: pairs.length > 0 && pairs.every((pair) => pair.behaviorParity) ? 'PASSED' : 'FAILED', mismatches: pairs.filter((pair) => !pair.behaviorParity).map((pair) => pair.key) },
		{ code: 'INSTRUMENTATION_P95_OVERHEAD', status: p95Ratio !== null && p95Ratio <= maxP95Ratio ? 'PASSED' : 'FAILED', observedRatio: p95Ratio, maximumRatio: maxP95Ratio, enabledP95Ms, disabledP95Ms, basis: 'nearest-rank p95 of paired enabled/disabled full-path duration ratios' },
	];
	return Object.freeze({ schemaVersion: 1, status: checks.every((check) => check.status === 'PASSED') ? 'PASSED' : 'FAILED', orders: normalizedOrders.map((value) => [...value]), minimumSamples, maxP95Ratio, checks: checks.map(Object.freeze), pairs: pairs.map(Object.freeze) });
}

function normalizeTrials(value, label) {
	const runs = Array.isArray(value) ? value : [value];
	const trials = [];
	for (let round = 0; round < runs.length; round += 1) {
		if (!runs[round] || typeof runs[round] !== 'object' || !Array.isArray(runs[round].trials)) throw new TypeError(`${label} run ${round + 1} must contain trials`);
		for (const trial of runs[round].trials) trials.push({ ...trial, comparisonRound: round + 1 });
	}
	if (trials.length > MAX_COMPARISON_TRIALS) throw new RangeError(`${label} runs exceed ${MAX_COMPARISON_TRIALS} trials`);
	return trials;
}

function indexUnique(trials, label) {
	const indexed = new Map();
	for (const trial of trials) {
		const key = keyFor(trial);
		if (indexed.has(key)) throw new TypeError(`${label} instrumentation run contains duplicate trial '${printableKey(key)}'`);
		indexed.set(key, trial);
	}
	return indexed;
}

function keyFor(trial) { return `${Number(trial?.comparisonRound ?? 0)}\u0000${String(trial?.trialId ?? '')}\u0000${Number(trial?.repetition ?? 0)}`; }
function printableKey(key) { return key.split('\u0000').join('/'); }
function nonblankHash(value) { return typeof value === 'string' && value.trim().length > 0; }
function finiteDuration(value) { return Number.isFinite(value) && value >= 0 ? value : null; }
function percentile(sorted, fraction) { return sorted.length === 0 ? null : sorted[Math.max(0, Math.ceil(sorted.length * fraction) - 1)]; }
