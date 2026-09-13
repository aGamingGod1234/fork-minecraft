import { createHash } from 'node:crypto';
import { mkdir, writeFile } from 'node:fs/promises';
import os from 'node:os';
import path from 'node:path';

import { runLatencyMatrix } from './latency-runner.mjs';

export const TASK9_SCHEMA_VERSION = 3;
export const TASK9_LOADS = Object.freeze([1, 4, 8, 16]);
export const TASK9_FIXED_CONCURRENCIES = Object.freeze([4, 8, 12, 16]);
export const TASK9_REQUIRED_PHASES = Object.freeze([
	'goal_received', 'observation_published', 'planner_requested', 'scheduler_queued',
	'scheduler_admitted', 'provider_started', 'provider_completed_or_failed',
	'decision_parsed', 'dispatch_sent', 'action_accepted', 'action_progress',
	'action_completed_or_failed', 'verification_started', 'verification_completed',
	'goal_completed_or_failed',
]);

export const TASK9_LUNA_PICKAXE_PROFILE = Object.freeze({
	id: 'codex-luna-xhigh-fast-wooden-pickaxe',
	provider: 'codex', model: 'gpt-5.6-luna', reasoningEffort: 'xhigh', serviceTier: 'fast',
	task: 'Gather wood and craft a wooden pickaxe', repetitions: 3,
	planningTimeoutMs: 120_000, scenarioTimeoutMs: 240_000, planningConcurrency: 4,
});

export const TASK9_ADAPTIVE_CONTROLLER_V1 = Object.freeze({
	mode: 'adaptive', minConcurrency: 1, maxConcurrency: 16, urgentReserve: 1,
	controlIntervalMs: 250, windowCompletions: 8, scaleUpConsecutiveWindows: 3,
	scaleDownConsecutiveWindows: 2, holdAfterScaleMs: 500,
	scaleUpIf: Object.freeze({ queueWaitP95Ms: 50, cpuPercent: 70, eventLoopP99Ms: 10, rssPercent: 80 }),
	scaleDownIf: Object.freeze({ queueWaitP95Ms: 250, cpuPercent: 85, eventLoopP99Ms: 25, rssPercent: 90 }),
});

const STATUS_VALUES = new Set(['PASSED', 'FAILED', 'TIMED_OUT', 'SKIPPED']);
const SCHEDULER_MODES = new Set(['fixed', 'adaptive']);

export function normalizeTask9Matrix(value) {
	if (!isRecord(value)) throw new TypeError('Task 9 matrix must be an object');
	if (value.schemaVersion !== TASK9_SCHEMA_VERSION) throw new TypeError(`Task 9 matrix schemaVersion must be ${TASK9_SCHEMA_VERSION}`);
	const fixedSeeds = normalizePositiveInts(value.fixedSeeds, 'fixedSeeds');
	const agentLoads = normalizeLoads(value.agentLoads);
	if (!Array.isArray(value.trials) || value.trials.length === 0) throw new TypeError('Task 9 matrix trials must be a non-empty array');
	const ids = new Set();
	const trials = value.trials.map((trial, index) => {
		if (!isRecord(trial)) throw new TypeError(`trials[${index}] must be an object`);
		const id = boundedText(trial.id, `trials[${index}].id`);
		if (ids.has(id)) throw new TypeError(`duplicate Task 9 trial id '${id}'`);
		ids.add(id);
		const mode = boundedText(trial.mode, `${id}.mode`).toLowerCase();
		if (!['instant', 'replay'].includes(mode)) throw new TypeError(`${id}.mode must be instant or replay for deterministic harness runs`);
		const scenarioId = boundedText(trial.scenarioId, `${id}.scenarioId`);
		const seed = requireInt(trial.seed, `${id}.seed`);
		if (!fixedSeeds.includes(seed)) throw new TypeError(`${id}.seed must be listed in fixedSeeds`);
		const agentLoad = requireInt(trial.agentLoad, `${id}.agentLoad`);
		if (!agentLoads.includes(agentLoad)) throw new TypeError(`${id}.agentLoad must be listed in agentLoads`);
		const repetitions = requireInt(trial.repetitions, `${id}.repetitions`);
		if (repetitions < 1 || repetitions > 1024) throw new RangeError(`${id}.repetitions must be in 1..1024`);
		return freeze({
			id, mode, scenarioId, seed, agentLoad, repetitions,
			providerProfile: normalizeProfile(trial.providerProfile, id),
			turnBudgetMs: requirePositiveNumber(trial.turnBudgetMs, `${id}.turnBudgetMs`),
			trialBudgetMs: requirePositiveNumber(trial.trialBudgetMs, `${id}.trialBudgetMs`),
			turnCap: requireInt(trial.turnCap, `${id}.turnCap`),
		});
	});
	return freeze({ schemaVersion: TASK9_SCHEMA_VERSION, benchmarkVersion: boundedText(value.benchmarkVersion ?? 'task9-performance-v1', 'benchmarkVersion'), protocolVersion: requireInt(value.protocolVersion ?? 2, 'protocolVersion'), fixedSeeds, agentLoads, trials });
}

export function createTask9RunManifest({
	runId, capturedAt = new Date().toISOString(), arm = null, sourceCommit, sourceHash,
	matrixHash, configHash, pairingKey, providerProfile, scheduler, order = [], host = {}, artifactPolicy = {},
} = {}) {
	const normalizedScheduler = normalizeScheduler(scheduler);
	const manifest = {
		schemaVersion: TASK9_SCHEMA_VERSION,
		runId: boundedText(runId, 'runId'), capturedAt: boundedText(capturedAt, 'capturedAt'),
		host: {
			name: boundedText(host.name ?? os.hostname(), 'host.name'), os: boundedText(host.os ?? `${process.platform}-${process.arch}`, 'host.os'),
			cpuCount: requireNonNegativeInt(host.cpuCount ?? os.cpus().length, 'host.cpuCount'),
			memoryBytes: requireNonNegativeInt(host.memoryBytes ?? os.totalmem(), 'host.memoryBytes'),
		},
		...(arm === null ? {} : { arm: boundedText(arm, 'arm') }),
		sourceCommit: boundedText(sourceCommit, 'sourceCommit'), sourceHash: boundedText(sourceHash, 'sourceHash'),
		matrixHash: boundedText(matrixHash, 'matrixHash'), configHash: boundedText(configHash, 'configHash'), pairingKey: boundedText(pairingKey, 'pairingKey'),
		providerProfile: normalizeProfile(providerProfile, 'providerProfile'), scheduler: normalizedScheduler,
		clockBasis: { wall: 'monotonic_ms', virtual: 'virtual_world_ms' }, order: order.map((entry) => boundedText(entry, 'order entry')),
		artifactPolicy: { rawProviderText: false, credentials: false, ...artifactPolicy },
	};
	if (manifest.artifactPolicy.rawProviderText !== false || manifest.artifactPolicy.credentials !== false) throw new TypeError('Task 9 artifacts must not publish raw provider text or credentials');
	return freeze(manifest);
}

export function createFairAbRows({ trials, sourceCommits, sourceHashes = {}, fixedConcurrency = 16 } = {}) {
	const matrix = normalizeTask9Matrix(trials);
	if (!isRecord(sourceCommits) || typeof sourceCommits.baseline !== 'string' || typeof sourceCommits.optimized !== 'string') throw new TypeError('sourceCommits.baseline and optimized are required');
	const concurrency = requireInt(fixedConcurrency, 'fixedConcurrency');
	if (concurrency < 1 || concurrency > 16) throw new RangeError('fixedConcurrency must be in 1..16');
	const rows = [];
	for (const trial of matrix.trials) {
		for (let repetition = 1; repetition <= trial.repetitions; repetition += 1) {
			const cellId = `${trial.id}/seed-${trial.seed}/load-${trial.agentLoad}/rep-${repetition}`;
			for (const arm of ['baseline', 'optimized']) rows.push(freeze({
				cellId, trialId: trial.id, repetition, arm, seed: trial.seed, agentLoad: trial.agentLoad,
				scenarioId: trial.scenarioId, providerProfile: trial.providerProfile, sourceCommit: sourceCommits[arm], sourceHash: sourceHashes[arm] ?? null,
				scheduler: { mode: 'fixed', fixedConcurrency: concurrency, maxPending: Math.max(0, trial.agentLoad - Math.min(trial.agentLoad, concurrency)), urgentReserve: 1 },
			}));
		}
	}
	return rows;
}

export function createSchedulerSweepRows({ trials, sourceCommit, sourceHash = null } = {}) {
	const matrix = normalizeTask9Matrix(trials);
	const rows = [];
	for (const trial of matrix.trials) {
		for (let repetition = 1; repetition <= trial.repetitions; repetition += 1) {
			for (const fixedConcurrency of TASK9_FIXED_CONCURRENCIES) rows.push(freeze({
				cellId: `${trial.id}/seed-${trial.seed}/load-${trial.agentLoad}/rep-${repetition}/fixed-${fixedConcurrency}`,
				trialId: trial.id, repetition, seed: trial.seed, agentLoad: trial.agentLoad, scenarioId: trial.scenarioId,
				arm: 'optimized', sourceCommit, sourceHash, providerProfile: trial.providerProfile,
				scheduler: { mode: 'fixed', fixedConcurrency, maxPending: Math.max(0, trial.agentLoad - Math.min(trial.agentLoad, fixedConcurrency)), urgentReserve: 1 },
			}));
			rows.push(freeze({
				cellId: `${trial.id}/seed-${trial.seed}/load-${trial.agentLoad}/rep-${repetition}/adaptive-v1`,
				trialId: trial.id, repetition, seed: trial.seed, agentLoad: trial.agentLoad, scenarioId: trial.scenarioId,
				arm: 'optimized', sourceCommit, sourceHash, providerProfile: trial.providerProfile,
				scheduler: { mode: 'adaptive', controller: structuredClone(TASK9_ADAPTIVE_CONTROLLER_V1) },
			}));
		}
	}
	return rows;
}

export function normalizeTask9PhaseEvent(value, index = 0) {
	if (!isRecord(value)) throw new TypeError(`phase event ${index} must be an object`);
	const phase = boundedText(value.phase, `phase event ${index}.phase`);
	const sequence = requirePositiveInt(value.sequence, `phase event ${index}.sequence`);
	const monotonicMs = requireNonNegativeNumber(value.monotonicMs, `phase event ${index}.monotonicMs`);
	const durationMs = value.durationMs === undefined ? null : requireNonNegativeNumber(value.durationMs, `phase event ${index}.durationMs`);
	return freeze({ ...value, phase, sequence, monotonicMs, ...(durationMs === null ? {} : { durationMs }) });
}

export function summarizeTask9Samples(values) {
	if (!Array.isArray(values)) throw new TypeError('samples must be an array');
	const samples = values.filter((value) => Number.isFinite(value) && value >= 0).map(Number).sort((a, b) => a - b);
	return { raw: samples.slice(), count: samples.length, p50: percentile(samples, 0.50), p95: percentile(samples, 0.95), p99: percentile(samples, 0.99) };
}

export function buildTask9TrialReport({ identity, status, events = [], cpuSamples = [], rssSamples = [], tickSamples = [], factualSuccess = false, fairness = {}, cleanup = {}, correctness = {}, retries = {} } = {}) {
	if (!isRecord(identity)) throw new TypeError('trial identity is required');
	if (!STATUS_VALUES.has(status)) throw new TypeError('trial status is invalid');
	const normalizedEvents = events.map(normalizeTask9PhaseEvent);
	const phaseNames = new Set(normalizedEvents.map((event) => event.phase));
	const missingPhases = TASK9_REQUIRED_PHASES.filter((phase) => !phaseNames.has(phase));
	const resourceSamples = {
		cpu: { basis: 'process_cpu_interval_delta_ms', ...summarizeTask9Samples(cpuSamples) },
		rss: { basis: 'process_resident_set_bytes', ...summarizeTask9Samples(rssSamples) },
		minecraftTick: { basis: 'monotonic_wall_duration_ms', ...summarizeTask9Samples(tickSamples) },
	};
	let previousSequence = 0;
	let previousMonotonicMs = 0;
	for (const event of normalizedEvents) {
		if (event.sequence <= previousSequence) throw new Error('Task 9 phase sequence must be strictly increasing');
		if (event.monotonicMs < previousMonotonicMs) throw new Error('Task 9 phase monotonic clock must not regress');
		previousSequence = event.sequence;
		previousMonotonicMs = event.monotonicMs;
	}
	const result = {
		...identity, status, phaseEvents: normalizedEvents, missingPhases, resources: resourceSamples,
		correctness: { factualSuccess: factualSuccess === true, falseCompletion: status === 'PASSED' && factualSuccess !== true, ...correctness },
		fairness: { starvationCount: 0, urgentEventLossCount: 0, ...fairness }, retries,
		cleanup: { processTreeClean: false, listenersClosed: false, activeActions: 0, inputLeases: 0, providerSessions: 0, workspaces: 0, ok: false, ...cleanup },
	};
	if (result.status === 'PASSED' && (missingPhases.length > 0 || result.correctness.factualSuccess !== true)) result.status = 'FAILED';
	if (result.cleanup.ok !== true || result.cleanup.processTreeClean !== true || result.cleanup.listenersClosed !== true || result.cleanup.activeActions !== 0 || result.cleanup.inputLeases !== 0 || result.cleanup.providerSessions !== 0 || result.cleanup.workspaces !== 0) result.status = result.status === 'SKIPPED' ? 'SKIPPED' : 'FAILED';
	return freeze(result);
}

export function validateTask9TrialReport(report) {
	if (!isRecord(report)) throw new TypeError('trial report must be an object');
	if (!STATUS_VALUES.has(report.status)) throw new TypeError('trial report status is invalid');
	if (!Array.isArray(report.phaseEvents) || !Array.isArray(report.missingPhases)) throw new TypeError('trial report phase evidence is missing');
	if (!isRecord(report.resources) || !isRecord(report.correctness) || !isRecord(report.cleanup)) throw new TypeError('trial report evidence sections are missing');
	if (report.status === 'PASSED' && (report.missingPhases.length > 0 || report.correctness.factualSuccess !== true || report.cleanup.ok !== true || report.cleanup.processTreeClean !== true || report.cleanup.listenersClosed !== true)) throw new Error('Task 9 passed trial lacks required factual/cleanup evidence');
	return true;
}

export async function runTask9SimulatorMatrix({ matrix, runMatrix = runLatencyMatrix, artifactDirectory = null, ...options } = {}) {
	const normalized = normalizeTask9Matrix(matrix);
	if (typeof runMatrix !== 'function') throw new TypeError('runMatrix must be a function');
	const liveTrial = normalized.trials.find((trial) => trial.mode === 'live');
	if (liveTrial) throw new Error(`Task 9 deterministic harness rejects live trial '${liveTrial.id}'; use the isolated Desktop gate separately`);
	const run = await runMatrix({ ...options, matrix: { version: 1, benchmarkVersion: normalized.benchmarkVersion, protocolVersion: normalized.protocolVersion, fixedSeeds: normalized.fixedSeeds, agentLoads: normalized.agentLoads, trials: normalized.trials.map((trial) => ({ ...trial, providerAvailabilityRequired: true })) }, includeRawEvents: true });
	const trials = (run?.trials ?? []).map((trial) => {
		const raw = trial.metrics?.raw ?? {};
		const samples = trial.systemSummary?.rawSamples ?? [];
		const events = (trial.rawEvents ?? run?.rawEvents ?? [])
			.filter((event) => event?.trialId === trial.trialId && Number(event?.repetition) === Number(trial.repetition))
			.map(normalizeRawPhaseEvent)
		const identity = {
			runId: options.runId ?? 'task9-run', trialId: trial.trialId, repetition: trial.repetition, arm: options.arm ?? null,
			cellId: `${trial.trialId}/rep-${trial.repetition}`, scenarioId: trial.scenarioId, seed: trial.seed, agentLoad: trial.agentLoad,
			providerProfile: trial.providerProfile, sourceHash: options.sourceHash ?? null, configHash: options.configHash ?? null,
		};
		return buildTask9TrialReport({ identity, status: trial.status, events, cpuSamples: processCpuIntervalDeltas(samples), rssSamples: samples.map((sample) => sample.memory?.rssBytes).filter(Number.isFinite), tickSamples: raw.ticks?.map((sample) => sample.wallDurationMs).filter(Number.isFinite) ?? [], factualSuccess: trial.debug?.scenarioPassed === true, fairness: trial.fairness, cleanup: normalizeTask9Cleanup(trial.cleanup), correctness: { authoritative: trial.debug?.scenarioEvidence ?? null }, retries: trial.retries ?? {} });
	});
	const output = { schemaVersion: TASK9_SCHEMA_VERSION, status: trials.some((trial) => trial.status === 'FAILED' || trial.status === 'TIMED_OUT') ? 'FAILED' : run?.status ?? 'FAILED', runManifest: createTask9RunManifest({ runId: options.runId ?? 'task9-run', arm: options.arm ?? null, sourceCommit: options.sourceCommit ?? 'unknown', sourceHash: options.sourceHash ?? 'unknown', matrixHash: hashJson(normalized), configHash: options.configHash ?? hashJson(normalized), pairingKey: options.pairingKey ?? 'task9', providerProfile: normalized.trials[0]?.providerProfile ?? { provider: 'replay', model: 'unknown', reasoningEffort: 'fixed', serviceTier: 'synthetic-delayed' }, scheduler: options.scheduler ?? { mode: 'fixed', fixedConcurrency: options.planningConcurrency ?? 16 }, order: trials.map((trial) => `${trial.cellId}/${trial.arm ?? 'unknown'}`) }), trials };
	for (const trial of trials) validateTask9TrialReport(trial);
	if (artifactDirectory) await writeTask9Artifacts(artifactDirectory, output, run?.rawEvents ?? []);
	return freeze(output);
}

export async function writeTask9Artifacts(directory, output, events = []) {
	const root = path.resolve(directory);
	await mkdir(root, { recursive: true });
	await writeFile(path.join(root, 'run-manifest.json'), `${JSON.stringify(output.runManifest, null, 2)}\n`, 'utf8');
	await writeFile(path.join(root, 'events.jsonl'), events.map((event) => `${JSON.stringify(event)}\n`).join(''), 'utf8');
	for (const trial of output.trials ?? []) await writeFile(path.join(root, `trial-${safeFileSegment(trial.trialId)}-${trial.repetition}.json`), `${JSON.stringify(trial, null, 2)}\n`, 'utf8');
	await writeFile(path.join(root, 'trial-summary.json'), `${JSON.stringify({ schemaVersion: TASK9_SCHEMA_VERSION, status: output.status, trials: (output.trials ?? []).map((trial) => ({ cellId: trial.cellId, status: trial.status, factualSuccess: trial.correctness?.factualSuccess === true, missingPhases: trial.missingPhases, cleanup: trial.cleanup })) }, null, 2)}\n`, 'utf8');
}

function normalizeScheduler(value) {
	if (!isRecord(value) || !SCHEDULER_MODES.has(value.mode)) throw new TypeError('scheduler.mode must be fixed or adaptive');
	if (value.mode === 'fixed') {
		const fixedConcurrency = requireInt(value.fixedConcurrency, 'scheduler.fixedConcurrency');
		if (fixedConcurrency < 1 || fixedConcurrency > 16) throw new RangeError('scheduler.fixedConcurrency must be in 1..16');
		return { mode: 'fixed', fixedConcurrency, maxPending: value.maxPending === undefined ? null : requireNonNegativeInt(value.maxPending, 'scheduler.maxPending'), urgentReserve: value.urgentReserve === undefined ? 1 : requireNonNegativeInt(value.urgentReserve, 'scheduler.urgentReserve') };
	}
	return { mode: 'adaptive', controller: structuredClone(value.controller ?? TASK9_ADAPTIVE_CONTROLLER_V1) };
}
function normalizeProfile(value, label) { if (!isRecord(value)) throw new TypeError(`${label} must be an object`); return freeze({ provider: boundedText(value.provider, `${label}.provider`), model: boundedText(value.model, `${label}.model`), reasoningEffort: boundedText(value.reasoningEffort, `${label}.reasoningEffort`), serviceTier: boundedText(value.serviceTier, `${label}.serviceTier`) }); }
function normalizeLoads(value) { if (!Array.isArray(value) || value.length !== TASK9_LOADS.length || TASK9_LOADS.some((load) => !value.includes(load))) throw new TypeError('agentLoads must include 1, 4, 8, and 16'); return value.slice(); }
function normalizePositiveInts(value, label) { if (!Array.isArray(value) || value.length === 0) throw new TypeError(`${label} must be a non-empty array`); return [...new Set(value.map((entry) => requirePositiveInt(entry, label)))]; }
function requireInt(value, label) { if (!Number.isSafeInteger(value)) throw new TypeError(`${label} must be a safe integer`); return value; }
function requirePositiveInt(value, label) { const number = requireInt(value, label); if (number < 1) throw new RangeError(`${label} must be positive`); return number; }
function requireNonNegativeInt(value, label) { const number = requireInt(value, label); if (number < 0) throw new RangeError(`${label} must be non-negative`); return number; }
function requirePositiveNumber(value, label) { if (!Number.isFinite(value) || value <= 0) throw new TypeError(`${label} must be a positive number`); return value; }
function requireNonNegativeNumber(value, label) { if (!Number.isFinite(value) || value < 0) throw new TypeError(`${label} must be a non-negative number`); return value; }
function boundedText(value, label) { if (typeof value !== 'string' || value.trim() === '' || value.length > 512 || /[\u0000-\u001f\u007f]/u.test(value)) throw new TypeError(`${label} must be bounded text`); return value.trim(); }
function isRecord(value) { return value !== null && typeof value === 'object' && !Array.isArray(value); }
function freeze(value) { if (!value || typeof value !== 'object' || Object.isFrozen(value)) return value; for (const child of Object.values(value)) freeze(child); return Object.freeze(value); }
function percentile(values, fraction) { return values.length === 0 ? null : values[Math.max(0, Math.ceil(values.length * fraction) - 1)]; }
function hashJson(value) { return `sha256:${createHash('sha256').update(JSON.stringify(value)).digest('hex')}`; }
function safeFileSegment(value) { return String(value ?? 'trial').replace(/[^A-Za-z0-9._-]/g, '_').slice(0, 120); }

function processCpuIntervalDeltas(samples) {
	const result = [];
	for (let index = 1; index < samples.length; index += 1) {
		const previous = samples[index - 1]?.cpu?.totalMs;
		const current = samples[index]?.cpu?.totalMs;
		if (Number.isFinite(previous) && Number.isFinite(current) && current >= previous) result.push(current - previous);
	}
	return result;
}

function normalizeRawPhaseEvent(event) {
	const stage = String(event?.phase ?? event?.stage ?? '');
	const phase = {
		goal_started: 'goal_received', goal_received: 'goal_received', observation_received: 'observation_published', observation_published: 'observation_published',
		planner_requested: 'planner_requested', planner_admitted: 'scheduler_admitted', scheduler_queued: 'scheduler_queued', scheduler_admitted: 'scheduler_admitted',
		provider_request_started: 'provider_started', provider_response_completed: 'provider_completed_or_failed', provider_response_failed: 'provider_completed_or_failed',
		planner_decision_completed: 'decision_parsed', action_dispatch_started: 'dispatch_sent', action_command_sent: 'action_accepted', action_progress: 'action_progress',
		action_completed: 'action_completed_or_failed', action_failed: 'action_completed_or_failed', verification_started: 'verification_started', verification_completed: 'verification_completed',
		goal_completed: 'goal_completed_or_failed', goal_failed: 'goal_completed_or_failed',
	}[stage] ?? stage;
	return { ...event, phase };
}

function normalizeTask9Cleanup(value = {}) {
	const activeActions = Number.isSafeInteger(value.activeActions) ? value.activeActions : 0;
	const listeners = Number.isSafeInteger(value.listeners) ? value.listeners : 0;
	const relays = Number.isSafeInteger(value.relays) ? value.relays : 0;
	const ok = value.ok === true;
	return {
		...value,
		processTreeClean: value.processTreeClean ?? (ok && activeActions === 0),
		listenersClosed: value.listenersClosed ?? (ok && listeners === 0 && relays === 0),
		activeActions,
		inputLeases: Number.isSafeInteger(value.inputLeases) ? value.inputLeases : 0,
		providerSessions: Number.isSafeInteger(value.providerSessions) ? value.providerSessions : 0,
		workspaces: Number.isSafeInteger(value.workspaces) ? value.workspaces : 0,
		ok,
	};
}
