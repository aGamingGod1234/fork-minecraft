import { createHash } from 'node:crypto';
import { mkdir, readFile, rename, rm, writeFile } from 'node:fs/promises';
import os from 'node:os';
import path from 'node:path';
import { EventEmitter } from 'node:events';

import { createDynamicCoordinator } from '../dynamic-main.mjs';
import { DynamicAgentState } from '../agent-registry.mjs';
import { VirtualMinecraftBridge } from '../simulator/virtual-minecraft-bridge.mjs';
import { VIRTUAL_TICK_MS, VirtualWorld } from '../simulator/virtual-world.mjs';
import { getSimulatorScenario } from '../simulator/simulator-scenarios.mjs';
import { createReplayProvider } from './provider-replay.mjs';
import { BenchmarkRecorder } from './benchmark-recorder.mjs';
import { summarizeBenchmark } from './benchmark-report.mjs';
import { PlanningScheduler } from '../planning-scheduler.mjs';
import { ControlLatencyRegistry } from '../control-latency-registry.mjs';
import { SystemSampler } from './system-sampler.mjs';
import { createLiveProviderFactory } from './live-provider-factories.mjs';
import { buildAuthoritativeScenarioOutcome, captureScenarioInitialSnapshot, compileScenarioDecision, runAuthoritativeScenarioSuccess } from './scenario-program.mjs';
import { goalSpecFingerprint } from '../goal-spec.mjs';
import { sanitizeDiagnosticErrorCode, sanitizeDiagnosticErrorMessage, sanitizeDiagnosticText } from '../diagnostic-sanitizer.mjs';

const LOADS = Object.freeze([1, 4, 8, 16]);
const MODES = new Set(['instant', 'replay', 'live']);
const PROVIDERS = new Set(['codex', 'gemini', 'kimi', 'instant', 'replay']);
const MAX_TRIALS = 4_096;
const MAX_REPETITIONS = 1_024;
const MAX_METRIC_SAMPLES = 4_096;
const DISABLED_BENCHMARK_RECORDER = Object.freeze({ record: () => null, snapshot: () => [] });
const DISABLED_LATENCY_REGISTRY = Object.freeze({
	record: () => null,
	recordTracePhase: () => null,
	recordPhase: () => null,
	invalidateTrace: () => null,
	completeTrace: () => Object.freeze({ complete: false, totalMs: null, phases: [] }),
	traceSnapshot: () => [],
	traces: () => [],
	snapshot: () => [],
	performanceSnapshot: () => [],
});

function createDefaultVirtualBridge(options) {
	return new VirtualMinecraftBridge(options);
}

/** Strictly validate and freeze a latency matrix before executing it. */
export function normalizeLatencyMatrix(value) {
	if (!isRecord(value)) throw new TypeError('latency matrix must be an object');
	const version = positiveInt(value.version ?? 1, 'version');
	const benchmarkVersion = identifier(value.benchmarkVersion ?? 'latency-ab-v1', 'benchmarkVersion');
	const protocolVersion = positiveInt(value.protocolVersion ?? 2, 'protocolVersion');
	const fixedSeeds = normalizedSeeds(value.fixedSeeds);
	const agentLoads = normalizedLoads(value.agentLoads);
	if (agentLoads.length !== LOADS.length || LOADS.some((load) => !agentLoads.includes(load))) throw new TypeError('agentLoads must include 1, 4, 8, and 16');
	if (!Array.isArray(value.trials) || value.trials.length === 0 || value.trials.length > MAX_TRIALS) throw new TypeError('latency matrix trials must be a bounded non-empty array');
	const ids = new Set();
	const trials = value.trials.map((trial, index) => {
		if (!isRecord(trial)) throw new TypeError(`trial ${index} must be an object`);
		const id = identifier(trial.id, `trials[${index}].id`);
		if (ids.has(id)) throw new TypeError(`trial IDs must be unique: ${id}`);
		ids.add(id);
		const mode = identifier(trial.mode, `${id}.mode`).toLowerCase();
		if (!MODES.has(mode)) throw new TypeError(`${id}.mode must be instant, replay, or live`);
		const scenarioId = identifier(trial.scenarioId, `${id}.scenarioId`);
		const seed = safeInt(trial.seed, `${id}.seed`);
		if (!fixedSeeds.includes(seed)) throw new TypeError(`${id}.seed must be one of fixedSeeds`);
		const agentLoad = safeInt(trial.agentLoad, `${id}.agentLoad`);
		if (!LOADS.includes(agentLoad) || !agentLoads.includes(agentLoad)) throw new TypeError(`${id}.agentLoad must be one of 1, 4, 8, 16`);
		const providerProfile = normalizeProfile(trial.providerProfile, id);
		if (mode === 'instant' && providerProfile.provider !== 'instant') throw new TypeError(`${id}.instant trials require providerProfile.provider instant`);
		if (mode === 'live' && !['codex', 'kimi'].includes(providerProfile.provider)) throw new TypeError(`${id}.live trials require a Codex or Kimi provider`);
		const repetitions = positiveInt(trial.repetitions, `${id}.repetitions`);
		if (repetitions > MAX_REPETITIONS) throw new TypeError(`${id}.repetitions is too large`);
		return Object.freeze({
			id, mode, scenarioId, seed, agentLoad, providerProfile, repetitions,
			turnBudgetMs: positiveFinite(trial.turnBudgetMs, `${id}.turnBudgetMs`),
			trialBudgetMs: positiveFinite(trial.trialBudgetMs, `${id}.trialBudgetMs`),
			turnCap: positiveInt(trial.turnCap, `${id}.turnCap`),
			providerAvailabilityRequired: requireBoolean(trial.providerAvailabilityRequired, `${id}.providerAvailabilityRequired`),
		});
	});
	return deepFreeze({ version, benchmarkVersion, protocolVersion, fixedSeeds, agentLoads, trials });
}

/** Run the selected latency matrix without changing provider/model identity. */
export async function runLatencyMatrix(options = {}) {
	if (!isRecord(options)) throw new TypeError('latency runner options must be an object');
	const matrix = normalizeLatencyMatrix(await loadMatrix(options.matrix ?? options.matrixPath));
	const scenarioResolver = options.scenarioResolver ?? ((id) => getSimulatorScenario(id));
	if (typeof scenarioResolver !== 'function') throw new TypeError('scenarioResolver must be a function');
	const providerFactories = options.providerFactories ?? {};
	if (!isRecord(providerFactories)) throw new TypeError('providerFactories must be an object');
	const virtualBridgeFactory = options.virtualBridgeFactory ?? createDefaultVirtualBridge;
	if (typeof virtualBridgeFactory !== 'function') throw new TypeError('virtualBridgeFactory must be a function');
	const measurementContext = normalizeBenchmarkContext(options);
	const wallClockBasis = options.wallClock !== undefined || options.wallNow !== undefined ? 'injected_monotonic_ms' : 'process_monotonic_ms';
	const wallClock = createMonotonicClock(options.wallClock ?? options.wallNow ?? (() => performance.now()), 'wall clock');
	const measurementsEnabled = options.measurements !== false && options.instrumentation !== false && options.collectMetrics !== false;
	const recorder = measurementsEnabled ? options.recorder ?? new BenchmarkRecorder({ maxEvents: options.maxEvents ?? 10_000, baseContext: measurementContext, clock: options.recorderClock ?? wallClock }) : DISABLED_BENCHMARK_RECORDER;
	const results = [];
	let requiredFailure = null;
	const cleanups = [];
	try {
		for (const trial of matrix.trials) {
			for (let repetition = 1; repetition <= trial.repetitions; repetition += 1) {
				const result = await runTrial({ ...options, matrix, trial, repetition, scenarioResolver, providerFactories, virtualBridgeFactory, recorder, measurementContext, wallClock, wallClockBasis, measurementsEnabled });
				results.push(result);
				if (result.status === 'FAILED' && result.error?.code === 'PROVIDER_UNAVAILABLE' && trial.providerAvailabilityRequired) requiredFailure = result.error;
			}
		}
	} finally {
		for (const cleanup of cleanups.reverse()) await settle(cleanup);
	}
	const status = requiredFailure ? 'FAILED' : results.some((trial) => ['FAILED', 'TIMED_OUT'].includes(trial.status) || trial.cleanup?.ok === false) ? 'FAILED' : 'PASSED';
	const output = {
		status,
		...(Object.keys(measurementContext).length > 0 ? { context: measurementContext } : {}),
		matrix: { version: matrix.version, benchmarkVersion: matrix.benchmarkVersion, protocolVersion: matrix.protocolVersion },
		trials: results,
		cleanup: { ok: results.every((trial) => trial.cleanup?.ok !== false), activeActions: results.reduce((sum, trial) => sum + (trial.cleanup?.activeActions ?? 0), 0), listeners: results.reduce((sum, trial) => sum + (trial.cleanup?.listeners ?? 0), 0) },
		summary: recorder.snapshot ? recorder.snapshot().length : 0,
		benchmarkSummary: summarizeBenchmark(recorder.snapshot ? recorder.snapshot() : []),
		...(options.includeRawEvents ? { rawEvents: recorder.snapshot ? recorder.snapshot() : [] } : {}),
	};
	if (options.artifactDirectory) await writeArtifacts(options.artifactDirectory, output, recorder, options.artifactFs);
	if (requiredFailure) throw Object.assign(new Error(requiredFailure.message), requiredFailure, { result: output });
	return deepFreeze(output);
}


async function runTrial({ matrix, trial, repetition, scenarioResolver, providerFactories, virtualBridgeFactory, recorder, measurementContext = {}, wallClock, wallClockBasis = 'process_monotonic_ms', measurementsEnabled = true, ...options }) {
	const startedAt = performance.now();
	const deadline = startedAt + trial.trialBudgetMs;
	let measurementStartedAt = null;
	const setupSpansMs = { providerStart: null, coordinatorStart: null };
	let provider = null;
	let coordinator = null;
	let bridge = null;
	let world = null;
	let sampler = null;
	let initialSnapshots = new Map();
	const runtimeErrors = [];
	let result = null;
	const cleanup = [];
	const cleanupErrors = [];
	let turnCount = 0;
	let providerStopped = false;
	let providerCleanupRegistered = false;
	let trialRecorder = null;
	let scheduler = null;
	let records = [];
	const latencyRegistry = measurementsEnabled ? new ControlLatencyRegistry({ traceCap: Math.max(64, trial.agentLoad * 2) }) : DISABLED_LATENCY_REGISTRY;
	let systemSummary = null;
	const metrics = measurementsEnabled ? new LatencyMetricsTracker({ wallClock, wallClockBasis, maxSamples: options.maxMetricSamples, context: measurementContext }) : null;
	const cleanupTimeoutMs = 1_000;
	try {
		const rawScenario = await runWithDeadline(() => scenarioResolver(trial.scenarioId, trial), deadline);
		if (!rawScenario) throw coded('SCENARIO_NOT_FOUND', `Unknown simulator scenario '${trial.scenarioId}'`);
		const scenario = cloneScenarioForLoad(rawScenario, trial.agentLoad, trial.seed);
		const defaultLiveFactory = trial.mode === 'live' && !providerFactories[trial.providerProfile.provider]
			? createLiveProviderFactory(trial.providerProfile.provider, options.liveProviderOptions ?? {})
			: null;
		const factory = providerFactories[trial.providerProfile.provider]
			?? (trial.mode === 'instant' ? defaultInstantFactory : null)
			?? (trial.mode === 'replay' ? defaultReplayFactory : null)
			?? defaultLiveFactory;
		if (factory === null && trial.mode !== 'live') throw coded('PROVIDER_UNAVAILABLE', `No factory is configured for ${trial.providerProfile.provider}`);
		if (factory !== null) provider = await runWithDeadline(() => factory(trial.providerProfile, { trial, repetition, mode: trial.mode, options, matrix, scenario: rawScenario, loadScenario: scenario, deadline }), deadline);
		const stopProvider = async () => {
			if (providerStopped) return;
			providerStopped = true;
			await provider?.stop?.();
		};
		const stopProviderAfterTimeout = () => {
			void withTimeout(Promise.resolve().then(stopProvider), cleanupTimeoutMs, 'CLEANUP_TIMEOUT').catch(() => {});
			return Promise.resolve();
		};
		const registerProviderCleanup = () => {
			if (!providerCleanupRegistered && provider && typeof provider.stop === 'function') {
				providerCleanupRegistered = true;
				cleanup.push(stopProvider);
			}
		};
		registerProviderCleanup();
		if (provider === null || provider === undefined || provider.available === false) {
			const error = coded('PROVIDER_UNAVAILABLE', boundedError(provider?.reason ?? 'provider is unavailable'));
			result = trialResult(trial, repetition, trial.providerAvailabilityRequired ? 'FAILED' : 'SKIPPED', error, startedAt, null, null, measurementContext, { measurementStartedAt, setupSpansMs });
			return result;
		}
		if (trial.mode === 'replay' && !provider.createAgent) provider = createReplayProvider({ ...options, ...trial.replay, recordings: options.replayRecordings, trialId: trial.id, providerProfile: trial.providerProfile, scenario: rawScenario, prompt: trial.prompt ?? options.replayPrompt ?? 'latency-replay-prompt', protocolVersion: matrix.protocolVersion });
		registerProviderCleanup();
		validateProviderIdentity(provider, trial);
		if (typeof provider.start === 'function') {
			const providerStartedAt = performance.now();
			try { await runWithDeadline(() => provider.start(), deadline); }
			catch (error) { await stopProviderAfterTimeout(); throw error; }
			finally { setupSpansMs.providerStart = Math.max(0, performance.now() - providerStartedAt); }
		}

		const goal = scenario.goal ?? `Complete ${trial.scenarioId}`;
		const goalSpec = benchmarkGoalSpec(goal);
		records = scenario.agentIds.map((agentId) => ({ agentId, provider: internalProvider(trial.providerProfile.provider), model: trial.providerProfile.model, reasoningEffort: trial.providerProfile.reasoningEffort, serviceTier: trial.providerProfile.serviceTier, state: DynamicAgentState.IDLE, currentGoal: null, currentGoalSpec: null, goalRevision: 0, queue: [] }));
		const virtualRecords = records.map((record) => ({ ...record, state: DynamicAgentState.STARTING, currentGoal: goal, currentGoalSpec: goalSpec, goalRevision: 1 }));
		const fixtureId = `fixture-${hash({ trialId: trial.id, repetition, scenarioId: trial.scenarioId, seed: trial.seed, agentLoad: trial.agentLoad, providerProfile: trial.providerProfile }).slice(7, 35)}`;
		world = new VirtualWorld({ ...scenario.world, worldId: scenario.world.worldId ?? fixtureId }, { scheduler: manualScheduler() });
		metrics?.attachWorld(world, scenario, scenario.agentIds);
		initialSnapshots = new Map(scenario.agentIds.map((agentId) => [agentId, captureScenarioInitialSnapshot({ manifest: scenario.agentManifests?.[agentId] ?? rawScenario, world, agentId })]));
		const virtual = virtualBridgeFactory({ world, agentRecords: virtualRecords, serverInstanceId: `latency-${trial.id}-${repetition}`, trial, repetition });
		bridge = new VirtualMinecraftBridgeAdapter(virtual, records, {
			scenario,
			initialSnapshots,
			recordPhase: (stage, context, fields) => trialRecorder?.record(stage, context, fields),
		});
		metrics?.attachVirtualBridge(virtual);
		metrics?.attachBridgeAdapter(bridge);
		if (metrics) cleanup.push(() => metrics.close());
		cleanup.push(() => bridge?.stop());
		trialRecorder = createTrialRecorder(recorder, { trial, repetition, measurementContext, metrics, enabled: measurementsEnabled });
		scheduler = createTrialScheduler(trial, trialRecorder, options);
		cleanup.push(() => scheduler?.close?.('latency trial cleanup'));
		const providerService = factory !== null ? createInjectedProviderService(provider, trial, turnBudget(trial), deadline, () => ++turnCount, stopProvider, stopProviderAfterTimeout) : null;
		const config = benchmarkCoordinatorConfig(trial.agentLoad, options.planningConcurrency ?? trial.agentLoad);
		coordinator = createDynamicCoordinator(config, {
			memoryDirectory: null,
			...(trial.mode !== 'live' || provider.synthetic === true ? { runtimeSessionId: fixtureId } : {}),
			bridge,
			scheduler,
			benchmarkRecorder: trialRecorder,
			latencyRegistry,
			...(providerService ? { providerService } : {}),
			setStatusInterval: () => null,
			clearStatusInterval: () => {},
			controlNow: () => world.timeMs,
			plannerNow: () => world.timeMs,
			epochNow: () => world.timeMs,
		});
		const runtimeListener = (error) => { if (runtimeErrors.length < 64) runtimeErrors.push({ code: sanitizeDiagnosticErrorCode(error), message: boundedError(error) }); };
		coordinator.on('runtimeError', runtimeListener);
		cleanup.push(() => coordinator?.off?.('runtimeError', runtimeListener));
		cleanup.push(() => coordinator?.stop());
		const reconciled = new Promise((resolve) => coordinator.once('reconciled', resolve));
		const coordinatorStartedAt = performance.now();
		try {
			await runWithDeadline(() => coordinator.start(), deadline);
			await runWithDeadline(() => reconciled, deadline);
		} finally {
			setupSpansMs.coordinatorStart = Math.max(0, performance.now() - coordinatorStartedAt);
		}
		if (measurementsEnabled) {
			const samplerOptions = options.systemSamplerOptions ?? {};
			sampler = options.systemSampler
				?? options.systemSamplerFactory?.({ ...samplerOptions, schedulerReader: options.schedulerReader ?? samplerOptions.schedulerReader ?? (() => schedulerSnapshot(scheduler)), processReader: options.processReader ?? samplerOptions.processReader, childProcessReader: options.childProcessReader ?? samplerOptions.childProcessReader ?? (() => null) })
				?? new SystemSampler({ ...samplerOptions, schedulerReader: options.schedulerReader ?? samplerOptions.schedulerReader ?? (() => schedulerSnapshot(scheduler)), processReader: options.processReader ?? samplerOptions.processReader, childProcessReader: options.childProcessReader ?? samplerOptions.childProcessReader ?? (() => null) });
			sampler.start();
			sampler.sample?.();
			cleanup.push(() => sampler?.stop());
		}
		measurementStartedAt = performance.now();
		for (const agentId of scenario.agentIds) {
			trialRecorder.record('goal_received', { agentId, goalRevision: 1 });
			bridge.startAgent(agentId, goal, goalSpec);
		}
		await Promise.resolve();
		for (const agentId of scenario.agentIds) await runWithDeadline(() => bridge.publish(agentId), deadline);
		await runVirtualTicks({ world, bridge, coordinator, records, cap: trial.turnCap, deadline, mode: trial.mode, pacing: options.virtualTickPacing, metrics });
		const statuses = records.map((record) => coordinator.registry.get(record.agentId)?.state);
		const runtimeError = runtimeErrors.find((entry) => entry.code);
		const runtimeTimedOut = isTimeoutErrorCode(runtimeError?.code);
		const registryStatus = runtimeTimedOut ? 'TIMED_OUT' : statuses.every((value) => value === DynamicAgentState.COMPLETED) ? 'PASSED' : statuses.some((value) => value === DynamicAgentState.ERROR) ? 'FAILED' : 'TIMED_OUT';
		const scenarioRequired = typeof rawScenario.success === 'function';
		const scenarioOutcomes = scenarioRequired ? scenario.agentIds.map((agentId) => buildAuthoritativeScenarioOutcome({ manifest: scenario.agentManifests?.[agentId] ?? rawScenario, world, actionCommands: authoritativeCommands(virtual, agentId), actionResults: authoritativeResults(virtual, agentId), initialSnapshot: initialSnapshots.get(agentId) })) : [];
		const scenarioPassed = !scenarioRequired || scenario.agentIds.every((agentId) => runAuthoritativeScenarioSuccess({ manifest: scenario.agentManifests?.[agentId] ?? rawScenario, world, bridge: virtual, actionCommands: authoritativeCommands(virtual, agentId), actionResults: authoritativeResults(virtual, agentId), initialSnapshot: initialSnapshots.get(agentId) }));
		const scenarioDigest = scenarioOutcomes.length > 0 ? hash(scenarioOutcomes.map(normalizeScenarioOutcome)) : null;
		const status = runtimeError ? (runtimeTimedOut ? 'TIMED_OUT' : 'FAILED') : registryStatus === 'PASSED' && scenarioPassed ? 'PASSED' : registryStatus === 'TIMED_OUT' ? 'TIMED_OUT' : 'FAILED';
		const error = runtimeError ? coded(runtimeError.code, runtimeError.message) : status === 'TIMED_OUT' ? coded('TURN_CAP', `trial exceeded the ${trial.turnCap}-turn cap`) : !scenarioPassed ? coded('SCENARIO_ASSERTION_FAILED', 'authoritative scenario outcome did not satisfy its success predicate') : null;
		metrics?.markTaskCompletion({ status, agentIds: scenario.agentIds });
		const outcomeHash = hash({ trialId: trial.id, repetition, status, scenarioId: trial.scenarioId, seed: trial.seed, agentLoad: trial.agentLoad, providerProfile: trial.providerProfile, statuses, turnCount, scenarioDigest });
		result = trialResult(trial, repetition, status, error, startedAt, outcomeHash, cleanupSnapshot(bridge, sampler), measurementContext, { measurementStartedAt, setupSpansMs });
		result.benchmark = { eventCount: trialRecorder?.count ?? 0, traces: latencyRegistry.traceSnapshot() };
		result.debug = { statuses, turnCount, runtimeErrors, scenarioPassed, scenarioDigest, scenarioEvidence: scenarioEvidence(virtual, scenario.agentIds), actionCommandHash: hash(authoritativeCommands(virtual)) };
	} catch (error) {
		if (error?.code === 'TRIAL_TIMEOUT') await new Promise((resolve) => setImmediate(resolve));
		let typed = normalizeTrialError(error);
		if (typed.code === 'TRIAL_TIMEOUT' && runtimeErrors.length > 0) {
			const providerError = runtimeErrors.find((entry) => ['TURN_TIMEOUT', 'PROVIDER_EXIT', 'PROVIDER_UNAVAILABLE', 'TURN_CAP', 'REPLAY_IDENTITY_MISMATCH', 'REPLAY_DECISION_MISMATCH'].includes(entry.code));
			const runtimeError = providerError ?? runtimeErrors.find((entry) => entry.code && !['TRIAL_TIMEOUT'].includes(entry.code));
			if (runtimeError) typed = coded(runtimeError.code, runtimeError.message);
		}
		result = trialResult(trial, repetition, isTimeoutErrorCode(typed.code) ? 'TIMED_OUT' : 'FAILED', typed, startedAt, null, cleanupSnapshot(bridge, sampler), measurementContext, { measurementStartedAt, setupSpansMs });
		result.benchmark = { eventCount: trialRecorder?.count ?? 0, traces: latencyRegistry.traceSnapshot() };
		result.debug = {
			runtimeErrors,
			turnCount,
			statuses: records.map((record) => coordinator?.registry?.get?.(record.agentId)?.state ?? null),
			actionCommandHash: null,
		};
	} finally {
		try { sampler?.sample?.(); } catch {}
		for (const close of cleanup.reverse()) {
			try { await withTimeout(Promise.resolve().then(() => close?.()), cleanupTimeoutMs, 'CLEANUP_TIMEOUT'); }
			catch (error) { cleanupErrors.push({ code: 'CLEANUP_FAILED', message: boundedError(error) }); }
		}
		if (result !== null) {
			try { systemSummary = summarizeTrialSystem(sampler); } catch { systemSummary = null; }
			result.cleanup = cleanupSnapshot(bridge, sampler, cleanupErrors);
			result.systemSummary = systemSummary;
			result.metrics = metrics?.finalize({ world, agentIds: world?.agentIds ?? [] }) ?? null;
			if (!result.cleanup.ok) {
				result.status = 'FAILED';
				result.error = { code: 'CLEANUP_FAILED', message: 'trial cleanup left resources or reported an error' };
			}
		}
	}
	return result;
}

async function runVirtualTicks({ world, bridge, coordinator, records, cap, deadline, mode = 'instant', pacing, metrics = null }) {
	const pacer = createVirtualTickPacer({ mode, pacing, deadline });
	try {
		for (let tick = 0; tick < 10_000; tick += 1) {
			await pacer?.wait(tick);
			if (performance.now() >= deadline) throw coded('TRIAL_TIMEOUT', 'trial budget elapsed');
			const tickStartedAt = metrics?.beginTick();
			world.tick();
			metrics?.endTick(tickStartedAt, world);
			await new Promise((resolve) => setImmediate(resolve));
			const states = records.map((record) => coordinator.registry.get(record.agentId)?.state);
			if (states.every((state) => [DynamicAgentState.COMPLETED, DynamicAgentState.ERROR, DynamicAgentState.PAUSED].includes(state))) return;
			if (performance.now() >= deadline) throw coded('TRIAL_TIMEOUT', 'trial budget elapsed');
		}
		throw coded('TRIAL_TIMEOUT', 'trial did not reach a terminal state before its budget');
	} finally {
		pacer?.close();
	}
}

function createVirtualTickPacer({ mode, pacing, deadline }) {
	if (!['replay', 'live'].includes(mode)) return null;
	const configuration = pacing === undefined ? {} : pacing;
	if (!isRecord(configuration)) throw new TypeError('virtualTickPacing must be an object');
	const now = configuration.now ?? (() => performance.now());
	if (typeof now !== 'function') throw new TypeError('virtualTickPacing.now must be a function');
	const timer = configuration.timer ?? globalThis;
	if (!isRecord(timer) || typeof timer.setTimeout !== 'function' || typeof timer.clearTimeout !== 'function') throw new TypeError('virtualTickPacing.timer must provide setTimeout and clearTimeout');
	const tickMs = configuration.tickMs ?? VIRTUAL_TICK_MS;
	if (!Number.isFinite(tickMs) || tickMs <= 0) throw new TypeError('virtualTickPacing.tickMs must be a positive finite number');
	const sleep = configuration.sleep;
	if (sleep !== undefined && typeof sleep !== 'function') throw new TypeError('virtualTickPacing.sleep must be a function');
	let anchor = readPacingClock(now);
	const pending = new Set();
	return {
		async wait(tick) {
			let current = readPacingClock(now);
			let target = anchor + tick * tickMs;
			if (current - target > tickMs) {
				anchor = current - tick * tickMs;
				target = current;
			}
			const delay = target - current;
			if (delay > 0) {
				const remaining = deadline - performance.now();
				if (remaining <= 0) throw coded('TRIAL_TIMEOUT', 'trial budget elapsed');
				await (sleep ? sleep(Math.min(delay, remaining)) : waitWithPacingTimer(Math.min(delay, remaining), timer, pending));
				if (performance.now() >= deadline) throw coded('TRIAL_TIMEOUT', 'trial budget elapsed');
			}
		},
		close() {
			for (const handle of pending) {
				try { timer.clearTimeout(handle); } catch {}
			}
			pending.clear();
		},
	};
}

function readPacingClock(now) {
	let value;
	try { value = now(); } catch { value = performance.now(); }
	return Number.isFinite(value) ? value : performance.now();
}

function waitWithPacingTimer(delay, timer, pending) {
	return new Promise((resolve, reject) => {
		let handle = null;
		let settled = false;
		const finish = (callback, value) => {
			if (settled) return;
			settled = true;
			if (handle !== null) pending.delete(handle);
			callback(value);
		};
		try {
			handle = timer.setTimeout(() => finish(resolve), delay);
			pending.add(handle);
		} catch (error) {
			finish(reject, error);
		}
	});
}

class VirtualMinecraftBridgeAdapter extends EventEmitter {
	#virtual;
	#records;
	#scenario;
	#initialSnapshots;
	#recordPhase;
	#relays = [];
	#pendingObservations = new Map();
	#started = false;
	constructor(virtual, records, { scenario = null, initialSnapshots = new Map(), recordPhase = null } = {}) {
		super(); this.#virtual = virtual; this.#records = records; this.#scenario = scenario; this.#initialSnapshots = initialSnapshots; this.#recordPhase = typeof recordPhase === 'function' ? recordPhase : () => {};
		for (const [event, type] of [['observation', 'observation'], ['progress', 'action_progress'], ['result', 'action_result']]) {
			const relay = (entry) => {
				const message = { agentId: entry.envelope.agentId, payload: entry.envelope.payload };
				if (type === 'observation' && entry.envelope.payload.lastResult?.present === true) {
					this.#pendingObservations.set(message.agentId, message);
					return;
				}
				this.emit(type, message);
				if (type === 'action_result') {
					const pending = this.#pendingObservations.get(message.agentId);
					this.#pendingObservations.delete(message.agentId);
					if (pending) this.emit('observation', pending);
				}
			};
			virtual.on(event, relay);
			this.#relays.push([event, relay]);
		}
	}
	start() { this.#started = true; queueMicrotask(() => this.emit('ready', { serverInstanceId: this.#virtual.serverInstanceId, registry: this.#records })); }
	stop() { this.#started = false; for (const [event, relay] of this.#relays) this.#virtual.off(event, relay); this.#relays = []; this.#pendingObservations.clear(); this.#virtual.stop(); }
	get ready() { return this.#started; }
	get relayCount() { return this.#relays.length; }
	get pendingObservationCount() { return this.#pendingObservations.size; }
	get listenerResidue() { return this.eventNames().reduce((count, event) => count + this.listenerCount(event), 0); }
	async send(type, agentId, payload) {
		if (agentId === 'server') return;
		await this.#virtual.send(type, agentId, payload);
		if (type === 'goal_completed') this.#publishCompletionResult(agentId, payload);
	}
	#publishCompletionResult(agentId, payload) {
		const manifest = this.#scenario?.agentManifests?.[agentId] ?? this.#scenario;
		const verified = typeof manifest?.success !== 'function' || runAuthoritativeScenarioSuccess({
			manifest,
			world: this.#virtual.world,
			bridge: this.#virtual,
			actionCommands: authoritativeCommands(this.#virtual, agentId),
			actionResults: authoritativeResults(this.#virtual, agentId),
			initialSnapshot: this.#initialSnapshots.get(agentId),
		});
		this.#recordPhase('verification_started', { agentId, goalRevision: payload.goalRevision }, { traceId: payload.traceId });
		queueMicrotask(() => {
			const reasonCode = verified ? 'COMPLETION_VERIFIED' : 'PREDICATE_FAILED';
			this.#recordPhase('verification_completed', { agentId, goalRevision: payload.goalRevision }, { traceId: payload.traceId, reasonCode });
			this.#recordPhase(verified ? 'goal_completed' : 'goal_failed', { agentId, goalRevision: payload.goalRevision }, { traceId: payload.traceId, reasonCode });
			this.emit('goal_completion_result', {
				agentId,
				payload: {
					goalRevision: payload.goalRevision,
					traceId: payload.traceId,
					goalFingerprint: payload.goalFingerprint,
					verified,
					reasonCode,
					facts: [],
				},
			});
		});
	}
	publish(agentId, options) { return this.#virtual.publish(agentId, options); }
	flush() { return this.#virtual.flush(); }
	startAgent(agentId, goal, goalSpec) { this.emit('goal_control', { agentId, payload: { operation: 'start', goalRevision: 1, goal, goalSpec, updatedAtEpochMs: 1 } }); }
	get activeActionIds() { return this.#virtual.activeActionIds; }
}

function benchmarkGoalSpec(originalRequest) {
	const fields = { originalRequest, predicate: { type: 'operator_confirmed' }, createdAtTick: 1 };
	return Object.freeze({ ...fields, fingerprint: goalSpecFingerprint(fields) });
}

/**
 * Measurement-only state for one trial. All timestamps are monotonic wall-clock
 * readings supplied by the runner; world timestamps remain virtual-world time.
 */
class LatencyMetricsTracker {
	#wallClock;
	#enabled;
	#maxSamples;
	#wallClockBasis;
	#context;
	#world = null;
	#scenario = null;
	#virtual = null;
	#agentIds = [];
	#cleanups = [];
	#raw = {
		goals: [],
		initialObservations: [],
		providerPlanningWait: [],
		actionCommandAcceptance: [],
		physicalDisplacement: [],
		taskCompletion: [],
		hazardReaction: [],
		directMessageReaction: [],
		ticks: [],
		planningIntervals: [],
	};
	#goals = new Map();
	#initialObservations = new Map();
	#acceptances = new Map();
	#movementActions = new Map();
	#displacements = new Map();
	#hazards = new Map();
	#directMessages = new Map();
	#planning = new Map();
	#baseline = new Map();
	#seenConversationEvents = new Set();
	#conversationCursor = 0;
	#conversationPollPending = false;
	#tickDurations = [];
	#taskCompletion = null;
	#lastTimestamp = null;
	#finalized = false;

	constructor({ wallClock, wallClockBasis = 'process_monotonic_ms', enabled = true, maxSamples = MAX_METRIC_SAMPLES, context = {} } = {}) {
		if (typeof wallClock !== 'function') throw new TypeError('latency metrics wall clock must be a function');
		if (!Number.isSafeInteger(maxSamples) || maxSamples < 1 || maxSamples > MAX_METRIC_SAMPLES) throw new TypeError(`maxMetricSamples must be between 1 and ${MAX_METRIC_SAMPLES}`);
		this.#wallClock = wallClock;
		this.#wallClockBasis = boundedText(wallClockBasis, 64) ?? 'process_monotonic_ms';
		this.#enabled = enabled === true;
		this.#maxSamples = maxSamples;
		this.#context = context;
	}

	attachWorld(world, scenario, agentIds) {
		if (!this.#enabled) return;
		this.#world = world;
		this.#scenario = scenario;
		this.#agentIds = [...agentIds];
		for (const agentId of this.#agentIds) {
			const state = safePlayerState(world, agentId);
			if (state !== null) {
				this.#baseline.set(agentId, state);
			}
		}
	}

	attachVirtualBridge(virtual) {
		if (!this.#enabled || !virtual?.on) return;
		const listeners = [
			['accepted', (event) => this.#onAccepted(event)],
			['observation', (event) => this.#onObservation(event)],
			['result', (event) => this.#onResult(event)],
		];
		for (const [event, listener] of listeners) { virtual.on(event, listener); this.#cleanups.push(() => virtual.off?.(event, listener)); }
		this.#virtual = virtual;
	}

	attachBridgeAdapter(bridge) {
		if (!this.#enabled || !bridge?.on) return;
		const listener = (event) => this.#onGoal(event);
		bridge.on('goal_control', listener);
		this.#cleanups.push(() => bridge.off?.('goal_control', listener));
	}

	close() {
		for (const cleanup of this.#cleanups.splice(0).reverse()) {
			try { cleanup(); } catch { /* measurement cleanup cannot affect the trial */ }
		}
	}

	beginTick() { return this.#enabled ? this.#now() : null; }

	endTick(startedAt, world) {
		if (!this.#enabled || startedAt === null) return;
		const endedAt = this.#now();
		const durationMs = Math.max(0, endedAt - startedAt);
		const tick = Number.isSafeInteger(world?.tickCount) ? world.tickCount : this.#raw.ticks.length + 1;
		const virtualTimestampMs = finiteOrNull(world?.timeMs);
		this.#push(this.#raw.ticks, { tick, wallDurationMs: durationMs, virtualTimestampMs });
		this.#push(this.#tickDurations, durationMs);
		this.#observeWorld(world, endedAt, virtualTimestampMs);
	}

	observeRecorderStage(stage, context = {}, fields = {}) {
		if (!this.#enabled) return;
		const timestamp = this.#now();
		const agentId = typeof context.agentId === 'string' ? context.agentId : null;
		if (stage === 'planner_requested' && agentId !== null) {
			this.#startPlanning(agentId, timestamp, context.goalRevision);
			return;
		}
		if (agentId === null) return;
		const plan = this.#planning.get(agentId);
		if (stage === 'scheduler_admitted') {
			if (plan) plan.admittedWallTimestampMs = timestamp;
			return;
		}
		if (stage === 'provider_request_started') {
			if (plan) plan.providerStartedWallTimestampMs = timestamp;
			return;
		}
		if (stage === 'provider_response_completed' || stage === 'provider_response_failed') {
			if (plan?.providerStartedWallTimestampMs !== null && plan?.providerStartedWallTimestampMs !== undefined) {
				const providerWaitWallMs = Math.max(0, timestamp - plan.providerStartedWallTimestampMs);
				this.#push(this.#raw.providerPlanningWait, {
					agentId,
					goalRevision: context.goalRevision ?? null,
					operation: boundedText(fields.operation, 64),
					requestWallTimestampMs: plan.providerStartedWallTimestampMs,
					responseWallTimestampMs: timestamp,
					schedulerWaitWallMs: plan.admittedWallTimestampMs === null ? null : Math.max(0, plan.admittedWallTimestampMs - plan.startedWallTimestampMs),
					planningWaitWallMs: Math.max(0, plan.providerStartedWallTimestampMs - plan.startedWallTimestampMs),
					providerWaitWallMs,
					totalPlanningWallMs: Math.max(0, timestamp - plan.startedWallTimestampMs),
					errorCode: stage === 'provider_response_failed' ? boundedText(fields.errorCode, 64) : null,
				});
				plan.providerStartedWallTimestampMs = null;
			}
			return;
		}
		if (stage === 'planner_decision_completed' || stage === 'planner_failed') this.#finishPlanning(agentId, timestamp, stage === 'planner_failed' ? boundedText(fields.errorCode, 64) : null);
	}

	markTaskCompletion({ status, agentIds = this.#agentIds } = {}) {
		if (!this.#enabled || status !== 'PASSED' || this.#taskCompletion !== null) return;
		const wallTimestampMs = this.#now();
		const virtualTimestampMs = finiteOrNull(this.#world?.timeMs);
		const initial = this.#firstInitialObservation(agentIds);
		if (initial === null || virtualTimestampMs === null) return;
		const value = {
			wallTimestampMs,
			virtualTimestampMs,
			wallDurationMs: Math.max(0, wallTimestampMs - initial.wallTimestampMs),
			virtualDurationMs: Math.max(0, virtualTimestampMs - initial.virtualTimestampMs),
			goalWallDurationMs: this.#firstGoal(agentIds) === null ? null : Math.max(0, wallTimestampMs - this.#firstGoal(agentIds).wallTimestampMs),
		};
		this.#taskCompletion = value;
		this.#push(this.#raw.taskCompletion, value);
	}

	finalize({ world = this.#world, agentIds = this.#agentIds } = {}) {
		if (this.#finalized) return this.#buildMetrics(world, agentIds);
		if (this.#enabled) {
			const timestamp = this.#now();
			for (const agentId of [...this.#planning.keys()]) this.#finishPlanning(agentId, timestamp, null);
		}
		this.#finalized = true;
		return this.#buildMetrics(world, agentIds);
	}

	#onGoal(event) {
		const agentId = event?.agentId;
		if (typeof agentId !== 'string' || this.#goals.has(agentId)) return;
		const wallTimestampMs = this.#now();
		const value = { agentId, wallTimestampMs, virtualTimestampMs: finiteOrNull(this.#world?.timeMs) ?? 0 };
		this.#goals.set(agentId, value);
		this.#push(this.#raw.goals, value);
	}

	#onObservation(event) {
		const payload = event?.envelope?.payload;
		const agentId = event?.envelope?.agentId;
		if (typeof agentId !== 'string' || !payload || this.#initialObservations.has(agentId)) return;
		const wallTimestampMs = this.#now();
		const value = { agentId, wallTimestampMs, virtualTimestampMs: finiteOrNull(payload.observedAtEpochMs) ?? finiteOrNull(this.#world?.timeMs) ?? 0 };
		this.#initialObservations.set(agentId, value);
		this.#push(this.#raw.initialObservations, value);
	}

	#onAccepted(event) {
		const payload = event?.envelope?.payload;
		const agentId = event?.envelope?.agentId;
		if (typeof agentId !== 'string' || !payload) return;
		const wallTimestampMs = this.#now();
		const value = {
			agentId,
			actionId: boundedText(payload.actionId, 128),
			actionType: boundedText(payload.actionType, 64),
			wallTimestampMs,
			virtualTimestampMs: finiteOrNull(this.#world?.timeMs) ?? 0,
		};
		const initial = this.#initialObservations.get(agentId);
		if (initial !== undefined) {
			value.wallLatencyMs = Math.max(0, wallTimestampMs - initial.wallTimestampMs);
			value.virtualLatencyMs = Math.max(0, value.virtualTimestampMs - initial.virtualTimestampMs);
		}
		const goal = this.#goals.get(agentId);
		if (goal !== undefined) {
			value.goalWallLatencyMs = Math.max(0, wallTimestampMs - goal.wallTimestampMs);
			value.goalVirtualLatencyMs = Math.max(0, value.virtualTimestampMs - goal.virtualTimestampMs);
		}
		this.#push(this.#raw.actionCommandAcceptance, value);
		if (!this.#acceptances.has(agentId)) this.#acceptances.set(agentId, value);
		if (isPhysicalMovementAction(value.actionType)) this.#movementActions.set(agentId, { ...value, completedTick: null });
		if (value.actionType === 'chat' && this.#declaredEvents().some((event) => /message|agent_message|direct/i.test(String(event?.kind ?? event?.type ?? '')))) this.#conversationPollPending = true;
		this.#advancePlanning(agentId, wallTimestampMs, true);
		this.#recordReaction(agentId, value);
	}

	#onResult(event) {
		const agentId = event?.envelope?.agentId;
		if (typeof agentId !== 'string') return;
		const payload = event?.envelope?.payload;
		const movement = this.#movementActions.get(agentId);
		if (movement && payload?.actionId === movement.actionId) movement.completedTick = Number.isSafeInteger(this.#world?.tickCount) ? this.#world.tickCount : null;
		this.#advancePlanning(agentId, this.#now(), false);
	}

	#observeWorld(world, wallTimestampMs, virtualTimestampMs) {
		for (const agentId of this.#agentIds) {
			const state = safePlayerState(world, agentId);
			if (state === null) continue;
			const initial = this.#initialObservations.get(agentId);
			const acceptance = this.#acceptances.get(agentId);
			const baseline = this.#baseline.get(agentId);
			const movement = this.#movementActions.get(agentId);
			const movementActive = movement !== undefined && (this.#virtual?.activeActionIds?.includes(agentId) === true || movement.completedTick === world?.tickCount);
			if (initial && acceptance && baseline && movementActive && !this.#displacements.has(agentId) && distanceBetween(baseline.position, state.position) > 1e-9) {
				const value = {
					agentId,
					actionId: movement.actionId,
					actionType: movement.actionType,
					wallTimestampMs,
					virtualTimestampMs,
					wallLatencyMs: Math.max(0, wallTimestampMs - initial.wallTimestampMs),
					virtualLatencyMs: Math.max(0, virtualTimestampMs - initial.virtualTimestampMs),
					fromAcceptanceWallLatencyMs: Math.max(0, wallTimestampMs - movement.wallTimestampMs),
					fromAcceptanceVirtualLatencyMs: Math.max(0, virtualTimestampMs - movement.virtualTimestampMs),
				};
				this.#displacements.set(agentId, value);
				this.#push(this.#raw.physicalDisplacement, value);
			}
			if (movement?.completedTick !== null && movement?.completedTick !== world?.tickCount) this.#movementActions.delete(agentId);
			this.#observeDeclaredEvents(agentId, state, wallTimestampMs, virtualTimestampMs, initial);
		}
		if (this.#conversationPollPending) this.#observeConversationEvents(world, wallTimestampMs, virtualTimestampMs);
	}

	#observeConversationEvents(world, wallTimestampMs, virtualTimestampMs) {
		const conversationHistory = world?.conversationEvents?.() ?? [];
		const newConversationEvents = Array.isArray(conversationHistory) ? conversationHistory.slice(this.#conversationCursor) : [];
		this.#conversationCursor += newConversationEvents.length;
		for (const event of newConversationEvents) {
			if (!event?.eventId || this.#seenConversationEvents.has(event.eventId)) continue;
			const declared = this.#declaredEvent(event.eventId, event.kind ?? event.type);
			if (!declared || !/message|agent_message|direct/i.test(String(declared.kind ?? declared.type ?? event.kind ?? event.type))) continue;
			this.#seenConversationEvents.add(event.eventId);
			const initial = this.#firstInitialObservation(this.#agentIds);
			const value = { eventId: boundedText(event.eventId, 128), agentId: event.sourceId ?? null, recipientId: event.recipientId ?? null, wallTimestampMs, virtualTimestampMs, wallLatencyMs: initial ? Math.max(0, wallTimestampMs - initial.wallTimestampMs) : null, virtualLatencyMs: initial ? Math.max(0, virtualTimestampMs - initial.virtualTimestampMs) : null, reactionWallLatencyMs: null, reactionVirtualLatencyMs: null };
			this.#directMessages.set(event.eventId, value);
			this.#push(this.#raw.directMessageReaction, { ...value, reactionWallLatencyMs: null, reactionVirtualLatencyMs: null });
		}
		const declaredMessageIds = this.#declaredEvents().filter((event) => /message|agent_message|direct/i.test(String(event?.kind ?? event?.type ?? ''))).map((event) => event?.eventId).filter(Boolean);
		this.#conversationPollPending = declaredMessageIds.some((eventId) => !this.#seenConversationEvents.has(eventId));
	}

	#observeDeclaredEvents(agentId, state, wallTimestampMs, virtualTimestampMs, initial) {
		const hazard = this.#declaredEvents().find((event) => (event?.type ?? event?.kind) === 'hazard' && (event.hazardType === undefined || String(event.hazardType).toLowerCase() === 'lava'));
		if (!hazard || this.#hazards.has(hazard.eventId)) return;
		const baseline = this.#baseline.get(agentId);
		if (!baseline || !(state.health < baseline.health || state.onFire === true && baseline.onFire !== true)) return;
		const value = { eventId: boundedText(hazard.eventId, 128), agentId, hazardType: boundedText(hazard.hazardType ?? hazard.type, 64), wallTimestampMs, virtualTimestampMs, wallLatencyMs: initial ? Math.max(0, wallTimestampMs - initial.wallTimestampMs) : null, virtualLatencyMs: initial ? Math.max(0, virtualTimestampMs - initial.virtualTimestampMs) : null, reactionWallLatencyMs: null, reactionVirtualLatencyMs: null };
		this.#hazards.set(hazard.eventId, value);
		this.#push(this.#raw.hazardReaction, value);
	}

	#recordReaction(agentId, acceptance) {
		for (const value of this.#hazards.values()) {
			if (value.agentId !== agentId || value.reactionWallLatencyMs !== null || acceptance.wallTimestampMs < value.wallTimestampMs) continue;
			if (!isProtectiveReactionAction(acceptance.actionType)) continue;
			value.reactionWallLatencyMs = Math.max(0, acceptance.wallTimestampMs - value.wallTimestampMs);
			value.reactionVirtualLatencyMs = Math.max(0, acceptance.virtualTimestampMs - value.virtualTimestampMs);
			value.reactionActionId = acceptance.actionId;
			value.reactionActionType = acceptance.actionType;
		}
		for (const value of this.#directMessages.values()) {
			if (value.recipientId !== agentId || value.reactionWallLatencyMs !== null || acceptance.wallTimestampMs < value.wallTimestampMs) continue;
			value.reactionWallLatencyMs = Math.max(0, acceptance.wallTimestampMs - value.wallTimestampMs);
			value.reactionVirtualLatencyMs = Math.max(0, acceptance.virtualTimestampMs - value.virtualTimestampMs);
			value.reactionActionId = acceptance.actionId;
			value.reactionActionType = acceptance.actionType;
		}
		for (const row of this.#raw.directMessageReaction) {
			const event = this.#directMessages.get(row.eventId);
			if (event && event.reactionWallLatencyMs !== undefined) {
				row.reactionWallLatencyMs = event.reactionWallLatencyMs;
				row.reactionVirtualLatencyMs = event.reactionVirtualLatencyMs;
				if (event.reactionActionId !== undefined) row.reactionActionId = event.reactionActionId;
				if (event.reactionActionType !== undefined) row.reactionActionType = event.reactionActionType;
			}
		}
	}

	#startPlanning(agentId, wallTimestampMs, goalRevision) {
		if (this.#planning.has(agentId)) this.#finishPlanning(agentId, wallTimestampMs, 'superseded');
		this.#planning.set(agentId, { agentId, goalRevision: goalRevision ?? null, startedWallTimestampMs: wallTimestampMs, lastWallTimestampMs: wallTimestampMs, localActiveWallDurationMs: 0, planningIdleWallDurationMs: 0, active: this.#isActive(agentId), admittedWallTimestampMs: null, providerStartedWallTimestampMs: null });
	}

	#finishPlanning(agentId, wallTimestampMs, reasonCode) {
		const plan = this.#planning.get(agentId);
		if (!plan) return;
		this.#advancePlanning(agentId, wallTimestampMs, this.#isActive(agentId));
		this.#planning.delete(agentId);
		this.#push(this.#raw.planningIntervals, { agentId, goalRevision: plan.goalRevision, startedWallTimestampMs: plan.startedWallTimestampMs, endedWallTimestampMs: wallTimestampMs, planningWallDurationMs: Math.max(0, wallTimestampMs - plan.startedWallTimestampMs), localActiveWallDurationMs: plan.localActiveWallDurationMs, planningIdleWallDurationMs: plan.planningIdleWallDurationMs, ...(reasonCode ? { reasonCode } : {}) });
	}

	#advancePlanning(agentId, wallTimestampMs, active) {
		const plan = this.#planning.get(agentId);
		if (!plan) return;
		const elapsedMs = Math.max(0, wallTimestampMs - plan.lastWallTimestampMs);
		if (plan.active) plan.localActiveWallDurationMs += elapsedMs;
		else plan.planningIdleWallDurationMs += elapsedMs;
		plan.active = active === true;
		plan.lastWallTimestampMs = wallTimestampMs;
	}

	#isActive(agentId) { return this.#virtual?.activeActionIds?.includes(agentId) === true; }

	#declaredEvents() { return Array.isArray(this.#scenario?.events) ? this.#scenario.events : []; }
	#declaredEvent(eventId, kind) { return this.#declaredEvents().find((event) => event?.eventId === eventId || (event?.kind ?? event?.type) === kind) ?? null; }
	#firstInitialObservation(agentIds) { return [...agentIds].map((agentId) => this.#initialObservations.get(agentId)).find(Boolean) ?? null; }
	#firstGoal(agentIds) { return [...agentIds].map((agentId) => this.#goals.get(agentId)).find(Boolean) ?? null; }
	#now() {
		let value;
		try { value = this.#wallClock(); } catch { value = this.#lastTimestamp ?? 0; }
		if (!Number.isFinite(value) || value < 0) value = this.#lastTimestamp ?? 0;
		if (this.#lastTimestamp !== null && value < this.#lastTimestamp) value = this.#lastTimestamp;
		this.#lastTimestamp = value;
		return value;
	}
	#push(target, value) { if (target.length < this.#maxSamples) target.push(value); }
	#buildMetrics(world, agentIds) {
		const initialObservation = this.#firstInitialObservation(agentIds);
		const goal = this.#firstGoal(agentIds);
		const acceptances = [...this.#acceptances.values()].slice(0, this.#maxSamples);
		const displacements = [...this.#displacements.values()].slice(0, this.#maxSamples);
		const acceptance = acceptances[0] ?? null;
		const displacement = displacements[0] ?? null;
		const planningIdleWallDurationMs = this.#raw.planningIntervals.reduce((sum, row) => sum + row.planningIdleWallDurationMs, 0);
		const localActiveWallDurationMs = this.#raw.planningIntervals.reduce((sum, row) => sum + row.localActiveWallDurationMs, 0);
		const tick = summarizeTickDurations(this.#tickDurations, this.#raw.ticks);
		const providerPlanningWait = summarizePlanningWait(this.#raw.providerPlanningWait);
		const totalVirtualWorldDurationMs = finiteOrNull(world?.timeMs) ?? 0;
		const result = {
			goal: goal ? { ...goal } : null,
			initialObservation: initialObservation ? { ...initialObservation } : null,
			firstActionCommandAcceptance: acceptance ? { ...acceptance } : null,
			firstAuthoritativePhysicalDisplacement: displacement ? { ...displacement } : null,
			taskCompletion: this.#taskCompletion ? { ...this.#taskCompletion } : null,
			goalWallTimestampMs: goal?.wallTimestampMs ?? null,
			goalVirtualTimestampMs: goal?.virtualTimestampMs ?? null,
			initialObservationWallTimestampMs: initialObservation?.wallTimestampMs ?? null,
			initialObservationVirtualTimestampMs: initialObservation?.virtualTimestampMs ?? null,
			firstActionCommandAcceptanceWallLatencyMs: acceptance?.wallLatencyMs ?? null,
			firstActionCommandAcceptanceVirtualLatencyMs: acceptance?.virtualLatencyMs ?? null,
			firstActionCommandAcceptanceGoalWallLatencyMs: acceptance?.goalWallLatencyMs ?? null,
			firstActionCommandAcceptanceGoalVirtualLatencyMs: acceptance?.goalVirtualLatencyMs ?? null,
			actionCommandAcceptance: summarizeLatencyRows(acceptances),
			firstAuthoritativePhysicalDisplacementWallLatencyMs: displacement?.wallLatencyMs ?? null,
			firstAuthoritativePhysicalDisplacementVirtualLatencyMs: displacement?.virtualLatencyMs ?? null,
			authoritativePhysicalDisplacement: summarizeLatencyRows(displacements),
			taskCompletionWallDurationMs: this.#taskCompletion?.wallDurationMs ?? null,
			taskCompletionGoalWallDurationMs: this.#taskCompletion?.goalWallDurationMs ?? null,
			taskCompletionVirtualDurationMs: this.#taskCompletion?.virtualDurationMs ?? null,
			totalVirtualWorldDurationMs,
			providerPlanningWait,
			hazardReaction: firstValue(this.#hazards),
			directMessageReaction: firstValue(this.#directMessages),
			tick,
			planningIdle: { localActiveWallDurationMs, planningIdleWallDurationMs, intervals: this.#raw.planningIntervals.slice() },
		};
		return {
			version: 1,
			enabled: this.#enabled,
			context: { ...this.#context },
			clockBasis: { wall: this.#wallClockBasis, virtual: 'virtual_world_ms' },
			raw: cloneMetricTree(this.#raw),
			result,
		};
	}
}

function summarizeTickDurations(values, rawTicks) {
	const sorted = [...values].sort((left, right) => left - right);
	const maxMs = sorted.length === 0 ? null : sorted.at(-1);
	return {
		count: sorted.length,
		basis: 'monotonic_wall_duration_ms',
		wallDurationSamplesMs: rawTicks.map((row) => row.wallDurationMs),
		p50Ms: percentile(sorted, 0.50),
		p95Ms: percentile(sorted, 0.95),
		p99Ms: percentile(sorted, 0.99),
		maxMs,
		over50MsCount: sorted.filter((value) => value > 50).length,
	};
}

function summarizePlanningWait(rows) {
	const planning = rows.map((row) => row.planningWaitWallMs).filter((value) => Number.isFinite(value)).sort((left, right) => left - right);
	const provider = rows.map((row) => row.providerWaitWallMs).filter((value) => Number.isFinite(value)).sort((left, right) => left - right);
	const scheduler = rows.map((row) => row.schedulerWaitWallMs).filter((value) => Number.isFinite(value)).sort((left, right) => left - right);
	return {
		count: rows.length,
		schedulerWaitWallDurationSamplesMs: rows.map((row) => row.schedulerWaitWallMs),
		planningWaitWallDurationSamplesMs: rows.map((row) => row.planningWaitWallMs),
		providerWaitWallDurationSamplesMs: rows.map((row) => row.providerWaitWallMs),
		schedulerWaitWallP50Ms: percentile(scheduler, 0.50),
		schedulerWaitWallP95Ms: percentile(scheduler, 0.95),
		schedulerWaitWallP99Ms: percentile(scheduler, 0.99),
		planningWaitWallP50Ms: percentile(planning, 0.50),
		planningWaitWallP95Ms: percentile(planning, 0.95),
		planningWaitWallP99Ms: percentile(planning, 0.99),
		providerWaitWallP50Ms: percentile(provider, 0.50),
		providerWaitWallP95Ms: percentile(provider, 0.95),
		providerWaitWallP99Ms: percentile(provider, 0.99),
	};
}

function summarizeLatencyRows(rows) {
	const wall = rows.map((row) => row.wallLatencyMs).filter((value) => Number.isFinite(value)).sort((left, right) => left - right);
	const virtual = rows.map((row) => row.virtualLatencyMs).filter((value) => Number.isFinite(value)).sort((left, right) => left - right);
	return {
		count: rows.length,
		wallLatencySamplesMs: rows.map((row) => finiteOrNull(row.wallLatencyMs)),
		wallLatencyP50Ms: percentile(wall, 0.50),
		wallLatencyP95Ms: percentile(wall, 0.95),
		wallLatencyP99Ms: percentile(wall, 0.99),
		wallLatencyMaxMs: wall.length === 0 ? null : wall.at(-1),
		virtualLatencySamplesMs: rows.map((row) => finiteOrNull(row.virtualLatencyMs)),
		virtualLatencyP50Ms: percentile(virtual, 0.50),
		virtualLatencyP95Ms: percentile(virtual, 0.95),
		virtualLatencyP99Ms: percentile(virtual, 0.99),
		virtualLatencyMaxMs: virtual.length === 0 ? null : virtual.at(-1),
	};
}

function cloneMetricTree(value) {
	try { return structuredClone(value); } catch { return {}; }
}

function firstValue(map) { return map.values().next().value ?? null; }
function safePlayerState(world, agentId) { try { return world?.playerState?.(agentId) ?? null; } catch { return null; } }
function distanceBetween(left, right) { return left && right ? Math.hypot((left.x ?? 0) - (right.x ?? 0), (left.y ?? 0) - (right.y ?? 0), (left.z ?? 0) - (right.z ?? 0)) : 0; }
function finiteOrNull(value) { return Number.isFinite(value) ? value : null; }
function isPhysicalMovementAction(actionType) { return actionType === 'move_to' || actionType === 'navigate_to'; }
function isProtectiveReactionAction(actionType) { return isPhysicalMovementAction(actionType) || actionType === 'respawn'; }

function createInjectedProviderService(provider, trial, budget, deadline, incrementTurn, stopProvider = async () => provider.stop?.(), stopProviderAfterTimeout = stopProvider) {
	const sessions = new Map();
	const agentTurns = new Map();
	return {
		catalog: { stale: false, async refresh() { return { models: [] }; }, assertSupported() {} },
		async start() {}, async stop() { await stopProvider(); sessions.clear(); },
		async bootstrapCatalog() { return { models: [] }; },
		async createAgent(record, options) {
			const profile = { ...record, ...trial.providerProfile, provider: trial.providerProfile.provider };
			let session;
			try { session = await runWithDeadline(() => provider.createAgent(profile, options), deadline); }
			catch (error) { if (error?.code === 'TRIAL_TIMEOUT') await stopProviderAfterTimeout(); throw error; }
			if (!session || typeof session.decide !== 'function') throw coded('PROVIDER_EXIT', 'provider returned no decision session');
			const wrapped = { ...session, async decide(input, options = {}) {
				const turns = agentTurns.get(record.agentId) ?? 0;
				if (turns >= trial.turnCap) throw coded('TURN_CAP', 'turn cap exceeded');
				agentTurns.set(record.agentId, turns + 1);
				incrementTurn();
				const remaining = deadline - performance.now();
				if (remaining <= 0) { await stopProviderAfterTimeout(); throw coded('TRIAL_TIMEOUT', 'trial deadline elapsed before provider turn'); }
				try {
					return await withTimeout(Promise.resolve().then(() => session.decide(input, options)), Math.min(budget, remaining), remaining <= budget ? 'TRIAL_TIMEOUT' : 'TURN_TIMEOUT');
				} catch (error) {
					if (error?.code === 'TRIAL_TIMEOUT' || error?.code === 'TURN_TIMEOUT') await stopProviderAfterTimeout();
					throw error;
				}
			} };
			sessions.set(record.agentId, wrapped); return wrapped;
		},
		getAgent(agentId) { return sessions.get(agentId) ?? null; },
		async removeAgent(agentId) { sessions.delete(agentId); return true; },
		async reconcile(records) { return { valid: records, invalid: [], removed: [], catalog: { models: [] } }; },
	};
}

function defaultInstantFactory(_profile, context = {}) {
	const manifests = context.loadScenario?.agentManifests ?? {};
	const defaultManifest = context.scenario;
	const fallbackDecision = defaultManifest ? compileScenarioDecision(defaultManifest) : null;
	const decisions = new Map(Object.entries(manifests).map(([agentId, manifest]) => [agentId, manifest ? compileScenarioDecision(manifest) : fallbackDecision]));
	const initialized = new Set();
	return { available: true, provider: 'instant', synthetic: true, async createAgent(record) { const decision = decisions.get(record.agentId) ?? fallbackDecision; if (!decision) throw coded('SCENARIO_NOT_FOUND', 'instant fixture requires a scenario manifest'); return { async setGoalRevision() {}, async decide() { if (!initialized.has(record.agentId)) { initialized.add(record.agentId); return decision; } return { directive: 'continue', summary: 'continue deterministic program' }; } }; } };
}

async function defaultReplayFactory(profile, context = {}) {
	const recordings = context.options?.replayRecordings ?? context.replayRecordings;
	if (!Array.isArray(recordings) || recordings.length === 0) return { available: false, reason: 'no replay recordings were supplied' };
	return createReplayProvider({
		recordings,
		trialId: context.trial?.id ?? context.trialId,
		prompt: context.trial?.prompt ?? context.options?.replayPrompt ?? 'latency-replay-prompt',
		providerProfile: profile,
		scenario: context.scenario ?? context.trial?.scenario ?? {},
		protocolVersion: context.matrix?.protocolVersion ?? 2,
	});
}

function benchmarkCoordinatorConfig(agentCap, planningConcurrency = agentCap) {
	const effectiveConcurrency = Math.max(1, Math.min(agentCap, planningConcurrency));
	return { bridge: { secret: 'latency-fixture' }, codex: { cwd: process.cwd(), controlProtocol: 'arena_script' }, limits: { agentCap, goalQueueCap: 8, planningConcurrency: effectiveConcurrency, invalidDecisionRetries: 0 }, workspaceRoot: path.join(os.tmpdir(), 'arena-latency-workspaces') };
}

function cloneScenarioForLoad(source, load, seed) {
	const sourceScenario = clonePreservingFunctions(source);
	const sourceWorld = structuredClone(source.world ?? source);
	const sourceAgents = sourceWorld.agents ?? sourceWorld.players ?? {};
	const sourceAgentIds = Object.keys(sourceAgents);
	const firstAgentId = source.agentId ?? sourceAgentIds[0];
	const firstAgent = sourceAgents[firstAgentId] ?? sourceAgents[sourceAgentIds[0]];
	if (!firstAgent) throw new TypeError('scenario must include a world agent');
	const agentIds = Array.from({ length: load }, (_, index) => index === 0 ? firstAgentId : `agent-${index + 1}`);
	const agentManifests = {};
	const worldBlocks = [];
	const worldEntities = [];
	const worldItems = [];
	const worldAgents = {};
	for (let index = 0; index < agentIds.length; index += 1) {
		const agentId = agentIds[index];
		const offset = index * 64;
		for (const block of sourceWorld.blocks ?? []) worldBlocks.push(translatePositioned(block, offset));
		for (const entity of sourceWorld.entities ?? []) worldEntities.push({ ...translatePositioned(entity, offset), ...(index === 0 ? {} : { id: `${entity.id ?? entity.uuid ?? 'entity'}-${index + 1}` }) });
		for (const item of sourceWorld.items ?? sourceWorld.drops ?? []) worldItems.push({ ...translatePositioned(item, offset), ...(index === 0 ? {} : { id: `${item.id ?? item.uuid ?? 'item'}-${index + 1}` }) });
		worldAgents[agentId] = translatePositioned(structuredClone(firstAgent), offset);
		const manifest = clonePreservingFunctions(sourceScenario);
		manifest.agentId = agentId;
		manifest.commands = (sourceScenario.commands ?? []).map((command) => ({ ...structuredClone(command), arguments: translateArguments(command.arguments ?? command.args, offset) }));
		manifest.expected = translateExpected(sourceScenario.expected, offset);
		manifest.world = { ...structuredClone(sourceWorld), seed, agents: { [agentId]: structuredClone(worldAgents[agentId]) }, blocks: sourceWorld.blocks?.map((block) => translatePositioned(block, offset)) ?? [] };
		agentManifests[agentId] = manifest;
	}
	if (sourceAgentIds.length > 1) {
		for (const id of sourceAgentIds.slice(1)) worldAgents[id] = structuredClone(sourceAgents[id]);
	}
	const world = { ...sourceWorld, seed, agents: worldAgents, blocks: worldBlocks, entities: worldEntities, items: worldItems };
	return { ...sourceScenario, agentIds, agentManifests, goal: sourceScenario.goal ?? 'Complete the deterministic scenario.', world };
}

function clonePreservingFunctions(value) {
	if (value === null || typeof value !== 'object') return value;
	return Object.fromEntries(Object.entries(value).map(([key, child]) => [key, typeof child === 'function' ? child : structuredClone(child)]));
}

function translatePositioned(value, offset) {
	const result = structuredClone(value);
	if (result && typeof result === 'object') {
		if (Number.isFinite(result.x)) result.x += offset;
		if (Number.isFinite(result.z)) result.z += offset;
		if (result.position && typeof result.position === 'object') result.position = translatePositioned(result.position, offset);
		if (result.checkpoint && typeof result.checkpoint === 'object') result.checkpoint = translatePositioned(result.checkpoint, offset);
	}
	return result;
}

function translateArguments(value, offset) {
	if (value === undefined) return undefined;
	const result = structuredClone(value);
	if (result && typeof result === 'object') {
		if (Number.isFinite(result.x)) result.x += offset;
		if (Number.isFinite(result.z)) result.z += offset;
	}
	return result;
}

function translateExpected(value, offset) {
	if (!value || typeof value !== 'object') return value;
	const result = structuredClone(value);
	for (const key of ['position', 'tableBlock', 'obstacle', 'checkpoint']) if (result[key]) result[key] = translatePositioned(result[key], offset);
	if (Array.isArray(result.waypoints)) result.waypoints = result.waypoints.map((point) => translatePositioned(point, offset));
	return result;
}

function normalizeProfile(value, id) {
	if (!isRecord(value)) throw new TypeError(`${id}.providerProfile must be an object`);
	const provider = identifier(value.provider, `${id}.providerProfile.provider`).toLowerCase();
	if (!PROVIDERS.has(provider)) throw new TypeError(`${id}.providerProfile.provider is unsupported`);
	return Object.freeze({ provider, model: identifier(value.model, `${id}.providerProfile.model`), reasoningEffort: identifier(value.reasoningEffort, `${id}.providerProfile.reasoningEffort`), serviceTier: identifier(value.serviceTier, `${id}.providerProfile.serviceTier`) });
}

function normalizeBenchmarkContext(options) {
	if (options.baseContext !== undefined && !isRecord(options.baseContext)) throw new TypeError('baseContext must be an object');
	if (options.context !== undefined && !isRecord(options.context)) throw new TypeError('context must be an object');
	const source = isRecord(options.baseContext) ? options.baseContext : isRecord(options.context) ? options.context : {};
	const result = {};
	for (const key of ['arm', 'runId', 'sourceHash', 'configHash', 'pairingKey']) {
		const value = options[key] ?? source[key];
		if (value === undefined || value === null) continue;
		if (typeof value !== 'string' || value.trim().length === 0 || value.length > 256) throw new TypeError(`${key} must be a bounded nonblank string`);
		result[key] = boundedContextText(value);
	}
	return result;
}

function boundedContextText(value) {
	return String(value)
		.replace(/(?:api[_-]?key|token|secret|password|authorization)\s*[:=]\s*\S+/gi, '$1=[REDACTED]')
		.slice(0, 256);
}

function createMonotonicClock(clock, field) {
	if (typeof clock !== 'function') throw new TypeError(`${field} must be a function`);
	let last = null;
	return () => {
		let value;
		try { value = clock(); } catch { value = last ?? 0; }
		if (!Number.isFinite(value) || value < 0) value = last ?? 0;
		if (last !== null && value < last) value = last;
		last = value;
		return value;
	};
}

function percentile(sorted, fraction) {
	if (sorted.length === 0) return null;
	return sorted[Math.max(0, Math.ceil(sorted.length * fraction) - 1)];
}

function normalizedSeeds(value) { if (!Array.isArray(value) || value.length === 0) throw new TypeError('fixedSeeds must be a non-empty array'); return [...new Set(value.map((seed) => safeInt(seed, 'fixedSeeds')))].sort((a, b) => a - b); }
function normalizedLoads(value) { if (!Array.isArray(value) || value.length === 0 || new Set(value).size !== value.length || value.some((load) => !LOADS.includes(load))) throw new TypeError('agentLoads must contain unique values from 1, 4, 8, 16'); return [...value]; }
async function loadMatrix(value) { if (value === undefined) return JSON.parse(await readFile(new URL('../../config/latency-matrix.json', import.meta.url), 'utf8')); if (typeof value === 'string') return JSON.parse(await readFile(value, 'utf8')); return value; }
function turnBudget(trial) { return trial.turnBudgetMs; }
function runWithDeadline(task, deadline, code = 'TRIAL_TIMEOUT') {
	const remaining = deadline - performance.now();
	if (remaining <= 0) return Promise.reject(coded(code, 'trial deadline elapsed'));
	return withTimeout(Promise.resolve().then(task), remaining, code);
}
function createTrialRecorder(recorder, { trial, repetition, measurementContext = {}, metrics = null, enabled = true }) {
	if (!enabled) return DISABLED_BENCHMARK_RECORDER;
	let count = 0;
	return {
		record(stage, context = {}, fields = {}) {
			const eventContext = { ...measurementContext, trialId: trial.id, repetition, scenarioId: trial.scenarioId, seed: trial.seed, agentLoad: trial.agentLoad, mode: trial.mode, synthetic: trial.mode !== 'live', provider: trial.providerProfile.provider, model: trial.providerProfile.model, reasoningEffort: trial.providerProfile.reasoningEffort, serviceTier: trial.providerProfile.serviceTier, ...context };
			metrics?.observeRecorderStage(stage, eventContext, fields);
			const row = recorder.record(stage, eventContext, fields);
			if (row !== null) count += 1;
			return row;
		},
		get count() { return count; },
	};
}
function createTrialScheduler(trial, recorder, options) {
	if (options.planningScheduler !== undefined) return options.planningScheduler;
	if (typeof options.planningSchedulerFactory === 'function') return options.planningSchedulerFactory({ trial, recorder });
	const configuredConcurrency = options.planningConcurrency ?? trial.agentLoad;
	const maxConcurrent = Math.max(1, Math.min(trial.agentLoad, configuredConcurrency));
	return new PlanningScheduler({ maxConcurrent, maxPending: Math.max(0, trial.agentLoad - maxConcurrent), benchmarkRecorder: recorder });
}
function schedulerSnapshot(scheduler) { return { active: scheduler?.activeCount ?? null, pending: scheduler?.pendingCount ?? null }; }
function summarizeTrialSystem(sampler) {
	const summary = sampler?.summary?.() ?? null;
	if (summary === null || typeof summary !== 'object' || Array.isArray(summary)) return summary;
	const samples = typeof sampler?.snapshot === 'function' ? sampler.snapshot() : [];
	const immediateSample = samples[0] ?? null;
	const finalSample = samples.at(-1) ?? null;
	const cpuSeries = summary.cpu;
	const memorySeries = summary.memory;
	const cpuDelta = {
		basis: 'process_resource_usage_delta_ms',
		userMs: deltaMetric(finalSample?.cpu?.userMs, immediateSample?.cpu?.userMs),
		systemMs: deltaMetric(finalSample?.cpu?.systemMs, immediateSample?.cpu?.systemMs),
		totalMs: deltaMetric(finalSample?.cpu?.totalMs, immediateSample?.cpu?.totalMs),
	};
	return {
		...summary,
		cpu: { ...cpuSeries, interpretation: 'cumulative process sample values; use cpuDelta for this trial' },
		memory: { ...memorySeries, interpretation: 'process memory sample series; use memoryDelta and memoryPeak for this trial' },
		processCpuSeries: cpuSeries,
		processMemorySeries: memorySeries,
		cpuDelta,
		memoryDelta: {
			basis: 'process_memory_sample_delta_bytes',
			rssBytes: signedDeltaMetric(finalSample?.memory?.rssBytes, immediateSample?.memory?.rssBytes),
			heapUsedBytes: signedDeltaMetric(finalSample?.memory?.heapUsedBytes, immediateSample?.memory?.heapUsedBytes),
		},
		memoryPeak: {
			rssBytes: maxMetric(samples.map((sample) => sample?.memory?.rssBytes)),
			heapUsedBytes: maxMetric(samples.map((sample) => sample?.memory?.heapUsedBytes)),
		},
		immediateSample,
		finalSample,
		rawSamples: samples.map((sample) => ({ ...sample })),
	};
}
function deltaMetric(finalValue, initialValue) { return Number.isFinite(finalValue) && Number.isFinite(initialValue) ? Math.max(0, finalValue - initialValue) : null; }
function signedDeltaMetric(finalValue, initialValue) { return Number.isFinite(finalValue) && Number.isFinite(initialValue) ? finalValue - initialValue : null; }
function maxMetric(values) { const finite = values.filter((value) => Number.isFinite(value)); return finite.length === 0 ? null : Math.max(...finite); }
function validateProviderIdentity(provider, trial) {
	const expected = trial.providerProfile;
	if (trial.mode !== 'live') {
		if (provider?.provider !== undefined && provider.provider !== expected.provider) throw coded('PROVIDER_MISMATCH', `Provider '${String(provider.provider)}' does not match selected '${expected.provider}'`);
		if (provider?.synthetic === false) throw coded('PROVIDER_MISMATCH', `${trial.mode} provider must be synthetic`);
		return { provider: provider?.provider ?? expected.provider, synthetic: true };
	}
	if (provider === null || typeof provider !== 'object' || Array.isArray(provider) || typeof provider.provider !== 'string' || provider.provider.trim().length === 0) {
		throw coded('PROVIDER_MISMATCH', `Live provider identity is missing; expected '${expected.provider}'`);
	}
	if (provider.provider !== expected.provider) throw coded('PROVIDER_MISMATCH', `Provider '${String(provider.provider)}' does not match selected '${expected.provider}'`);
	for (const field of ['model', 'reasoningEffort', 'serviceTier']) {
		if (typeof provider[field] !== 'string' || provider[field].trim().length === 0 || provider[field] !== expected[field]) throw coded('PROVIDER_MISMATCH', `Live provider ${field} does not match selected profile`);
	}
	if (provider.providerProfile !== undefined) {
		if (!isRecord(provider.providerProfile)) throw coded('PROVIDER_MISMATCH', 'Live provider profile identity is invalid');
		for (const field of ['provider', 'model', 'reasoningEffort', 'serviceTier']) {
			if (provider.providerProfile[field] !== expected[field]) throw coded('PROVIDER_MISMATCH', `Live provider profile ${field} does not match selected profile`);
		}
	}
	return { provider: expected.provider, synthetic: false };
}
function isTimeoutErrorCode(code) { return typeof code === 'string' && (code === 'TURN_CAP' || code.includes('TIMEOUT')); }
function internalProvider(provider) { return PROVIDERS.has(provider) && ['codex', 'gemini', 'kimi'].includes(provider) ? provider : 'codex'; }
function manualScheduler() { const handles = new Set(); return { setInterval(callback) { const handle = { callback }; handles.add(handle); return handle; }, clearInterval(handle) { handles.delete(handle); } }; }
function authoritativeCommands(virtual, agentId) { return (virtual?.sent ?? []).filter((event) => event?.type === 'action_command' && event.agentId === agentId).map((event) => ({ ...event.payload, agentId })); }
function authoritativeResults(virtual, agentId) { return (virtual?.events ?? []).filter((event) => event?.type === 'result' && event.envelope?.agentId === agentId).map((event) => ({ ...event.envelope.payload, agentId })); }
function scenarioEvidence(virtual, agentIds) {
	const counts = new Map(agentIds.map((agentId) => [agentId, { actionResults: 0, succeeded: 0, failed: 0, cancelled: 0 }]));
	for (const event of virtual?.events ?? []) {
		if (event?.type !== 'result') continue;
		const entry = counts.get(event.envelope?.agentId);
		if (!entry) continue;
		entry.actionResults += 1;
		if (event.envelope.payload.state === 'SUCCEEDED') entry.succeeded += 1;
		if (event.envelope.payload.state === 'CANCELLED') entry.cancelled += 1;
		if (event.envelope.payload.state === 'FAILED' || event.envelope.payload.state === 'TIMED_OUT') entry.failed += 1;
	}
	return agentIds.map((agentId) => ({ agentId, ...counts.get(agentId) }));
}
function normalizeScenarioOutcome(outcome) {
	const state = outcome?.state ?? {};
	return {
		commandMappingValid: outcome?.commandMapping?.valid === true,
		results: (outcome?.results ?? []).slice(0, 32).map((result) => ({ actionId: boundedText(result.commandId ?? result.actionId, 128), actionType: boundedText(result.actionType, 64), state: boundedText(result.state, 32), reasonCode: boundedText(result.reasonCode, 64) })),
		state: {
			position: finitePosition(state.position),
			health: Number.isFinite(state.health) ? state.health : null,
			inventoryBefore: normalizeItems(state.inventoryBefore),
			inventoryAfter: normalizeItems(state.inventoryAfter),
			block: normalizeRecord(state.block),
			target: normalizeRecord(state.target),
			obstacle: normalizeRecord(state.obstacle),
			waypoints: Array.isArray(state.waypoints) ? state.waypoints.slice(0, 32).map(finitePosition) : [],
			hazard: normalizeRecord(state.hazard),
			reaction: boundedText(state.reaction, 64),
			events: Array.isArray(state.events) ? state.events.slice(0, 32).map(normalizeRecord) : [],
		},
	};
}
function normalizeItems(items) { return Array.isArray(items) ? items.slice(0, 256).map((item) => ({ itemId: boundedText(item?.itemId, 128), count: Number.isSafeInteger(item?.count) ? item.count : null, slot: Number.isSafeInteger(item?.slot) ? item.slot : null })) : []; }
function normalizeRecord(value) { return value && typeof value === 'object' ? Object.fromEntries(Object.entries(value).filter(([key]) => ['id', 'entityId', 'blockId', 'desiredState', 'eventId', 'type', 'kind', 'sourceId', 'recipientId', 'wakeAcknowledged', 'processed', 'dead', 'health', 'x', 'y', 'z', 'position'].includes(key)).slice(0, 32).map(([key, child]) => [key, key === 'position' ? finitePosition(child) : typeof child === 'string' ? boundedText(child, 128) : child])) : null; }
function finitePosition(value) { return value && Number.isFinite(value.x) && Number.isFinite(value.y) && Number.isFinite(value.z) ? { x: value.x, y: value.y, z: value.z } : null; }
function boundedText(value, maximum) { return typeof value === 'string' ? value.slice(0, maximum) : null; }
function cleanupSnapshot(bridge, sampler, cleanupErrors = []) {
	const activeActions = bridge?.activeActionIds?.length ?? 0;
	const listeners = bridge?.listenerResidue ?? 0;
	const relays = bridge?.relayCount ?? 0;
	const pendingObservations = bridge?.pendingObservationCount ?? 0;
	const samplerActive = sampler?.active === true;
	const samplerErrors = sampler?.errors?.length ?? 0;
	return { ok: activeActions === 0 && listeners === 0 && relays === 0 && pendingObservations === 0 && !samplerActive && cleanupErrors.length === 0, activeActions, listeners, relays, pendingObservations, samplerActive, samplerErrors, errors: cleanupErrors.slice(0, 16) };
}
function trialResult(trial, repetition, status, error, startedAt, outcomeHash, cleanup, context = {}, timing = {}) {
	const endedAt = performance.now();
	const measurementStartedAt = Number.isFinite(timing.measurementStartedAt) ? timing.measurementStartedAt : null;
	const setupSpansMs = isRecord(timing.setupSpansMs) ? timing.setupSpansMs : {};
	return {
		...context,
		trialId: trial.id, repetition, status, scenarioId: trial.scenarioId, seed: trial.seed, agentLoad: trial.agentLoad, mode: trial.mode,
		providerProfile: trial.providerProfile, providerIdentity: { provider: trial.providerProfile.provider, synthetic: trial.mode !== 'live' },
		...(outcomeHash ? { outcomeHash } : {}),
		...(error ? { error: { code: sanitizeDiagnosticErrorCode(error, { fallback: 'TRIAL_FAILED' }), message: boundedError(error) } } : {}),
		cleanup: cleanup ?? { ok: true, activeActions: 0, listeners: 0 },
		timingScope: 'full_path',
		durationMs: Math.max(0, Math.round(endedAt - startedAt)),
		taskDurationMs: measurementStartedAt === null ? null : Math.max(0, Math.round(endedAt - measurementStartedAt)),
		setupDurationMs: Math.max(0, Math.round((measurementStartedAt ?? endedAt) - startedAt)),
		totalDurationMs: Math.max(0, Math.round(endedAt - startedAt)),
		setupSpansMs: {
			providerStart: finiteOrNull(setupSpansMs.providerStart),
			coordinatorStart: finiteOrNull(setupSpansMs.coordinatorStart),
		},
	};
}
function cleanupError() { return null; }
async function writeArtifacts(directory, output, recorder, artifactFs = {}) {
	const fs = { mkdir, rename, rm, writeFile, ...artifactFs };
	const target = path.resolve(directory);
	const temp = `${target}.tmp-${process.pid}-${Date.now()}`;
	const backup = `${target}.bak-${process.pid}-${Date.now()}`;
	await fs.mkdir(temp, { recursive: true });
	let preserveBackup = false;
	try {
		await fs.writeFile(path.join(temp, 'latency-manifest.json'), `${JSON.stringify(redactOutput(output), null, 2)}\n`);
		if (recorder?.writeArtifacts) await recorder.writeArtifacts(temp);
		let backedUp = false;
		try { await fs.rename(target, backup); backedUp = true; } catch (error) { if (error.code !== 'ENOENT') throw error; }
		try {
			await fs.rename(temp, target);
			if (backedUp) { await fs.rm(backup, { recursive: true, force: true }); backedUp = false; }
		} catch (error) {
			try { await fs.rm(target, { recursive: true, force: true }); }
			catch (removeError) { preserveBackup = backedUp; throw coded('ARTIFACT_ROLLBACK_FAILED', boundedError(removeError)); }
			if (backedUp) {
				try { await fs.rename(backup, target); backedUp = false; }
				catch (restoreError) { preserveBackup = true; throw coded('ARTIFACT_ROLLBACK_FAILED', boundedError(restoreError)); }
			}
			throw error;
		}
	} finally {
		await fs.rm(temp, { recursive: true, force: true });
		if (!preserveBackup) await fs.rm(backup, { recursive: true, force: true });
	}
}
function redactOutput(output) { return { ...output, trials: output.trials.map((trial) => ({ ...trial, error: trial.error ? { code: trial.error.code, message: boundedError(trial.error.message) } : undefined })) }; }
function normalizeTrialError(error) {
	const code = sanitizeDiagnosticErrorCode(error);
	if (code !== 'UNKNOWN') return coded(code, boundedError(error));
	const message = boundedError(error);
	if (/provider|process|spawn|exit|transport/i.test(message)) return coded('PROVIDER_EXIT', message);
	return coded('TRIAL_FAILED', message);
}
function boundedError(value) {
	if (value !== null && typeof value === 'object') return sanitizeDiagnosticErrorMessage(value, { maxBytes: 512 });
	return sanitizeDiagnosticText(value ?? 'unknown error', { maxBytes: 512 });
}
function withTimeout(promise, milliseconds, code) {
	let handle;
	const timeout = new Promise((_, reject) => { handle = setTimeout(() => reject(coded(code, `${code.toLowerCase()} after ${milliseconds}ms`)), milliseconds); });
	return Promise.race([promise, timeout]).finally(() => clearTimeout(handle));
}
async function settle(callback) { try { await callback?.(); } catch {} }
function hash(value) { return `sha256:${createHash('sha256').update(JSON.stringify(value)).digest('hex')}`; }
function coded(code, message) { return Object.assign(new Error(message), { code }); }
function isRecord(value) { return value !== null && typeof value === 'object' && !Array.isArray(value); }
function identifier(value, field) { if (typeof value !== 'string' || value.trim() === '' || value.length > 128) throw new TypeError(`${field} must be a bounded nonblank string`); return value.trim(); }
function safeInt(value, field) { if (!Number.isSafeInteger(value)) throw new TypeError(`${field} must be a safe integer`); return value; }
function positiveInt(value, field) { if (!Number.isSafeInteger(value) || value <= 0) throw new TypeError(`${field} must be a positive safe integer`); return value; }
function positiveFinite(value, field) { if (!Number.isFinite(value) || value <= 0) throw new TypeError(`${field} must be a positive finite number`); return value; }
function requireBoolean(value, field) { if (typeof value !== 'boolean') throw new TypeError(`${field} must be a boolean`); return value; }
function deepFreeze(value, seen = new WeakSet()) { if (value === null || typeof value !== 'object' || seen.has(value)) return value; seen.add(value); for (const child of Object.values(value)) deepFreeze(child, seen); return Object.freeze(value); }
