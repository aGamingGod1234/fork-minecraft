import assert from 'node:assert/strict';
import { readFile, mkdtemp, readdir } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import path from 'node:path';
import test from 'node:test';

import {
	TASK9_ADAPTIVE_CONTROLLER_V1,
	TASK9_FIXED_CONCURRENCIES,
	TASK9_LUNA_PICKAXE_PROFILE,
	TASK9_REQUIRED_PHASES,
	buildTask9TrialReport,
	createFairAbRows,
	createSchedulerSweepRows,
	createTask9RunManifest,
	normalizeTask9Matrix,
	runTask9SimulatorMatrix,
	validateTask9TrialReport,
} from '../src/benchmark/task9-harness.mjs';

const profile = { provider: 'replay', model: 'controlled-v1', reasoningEffort: 'fixed', serviceTier: 'synthetic-delayed' };
const trial = (overrides = {}) => ({ id: 'stone', mode: 'replay', scenarioId: 'stone-tool-gathering', seed: 20260821, agentLoad: 4, repetitions: 5, providerProfile: profile, turnBudgetMs: 100, trialBudgetMs: 500, turnCap: 8, ...overrides });
const matrix = (overrides = {}) => ({ schemaVersion: 3, fixedSeeds: [20260821, 20260822], agentLoads: [1, 4, 8, 16], trials: [trial(overrides)] });
const phases = TASK9_REQUIRED_PHASES.map((phase, index) => ({ phase, sequence: index + 1, monotonicMs: index, durationMs: 1 }));

test('normalizes the schema-3 deterministic matrix and rejects live trials', () => {
	const normalized = normalizeTask9Matrix(matrix());
	assert.equal(normalized.schemaVersion, 3);
	assert.deepEqual(normalized.agentLoads, [1, 4, 8, 16]);
	assert.throws(() => normalizeTask9Matrix(matrix({ mode: 'live' })), /instant or replay/i);
});

test('creates fair A/B rows with identical cells and a separately versioned scheduler sweep', () => {
	const fair = createFairAbRows({ trials: matrix(), sourceCommits: { baseline: 'base', optimized: 'tip' }, fixedConcurrency: 16 });
	assert.equal(fair.length, 10);
	for (let index = 0; index < fair.length; index += 2) {
		assert.equal(fair[index].cellId, fair[index + 1].cellId);
		assert.equal(fair[index].scheduler.fixedConcurrency, 16);
		assert.equal(fair[index].seed, fair[index + 1].seed);
		assert.equal(fair[index].agentLoad, fair[index + 1].agentLoad);
	}
	const sweep = createSchedulerSweepRows({ trials: matrix(), sourceCommit: 'tip' });
	assert.deepEqual(sweep.slice(0, TASK9_FIXED_CONCURRENCIES.length).map((row) => row.scheduler.fixedConcurrency), [...TASK9_FIXED_CONCURRENCIES]);
	assert.equal(sweep.at(-1).scheduler.mode, 'adaptive');
	assert.deepEqual(sweep.at(-1).scheduler.controller, TASK9_ADAPTIVE_CONTROLLER_V1);
});

test('does not call live providers and keeps factual/cleanup evidence separate from latency', () => {
	const report = buildTask9TrialReport({
		identity: { runId: 'r', trialId: 't', repetition: 1, cellId: 'c', scenarioId: 'stone', seed: 1, agentLoad: 1, providerProfile: profile },
		status: 'PASSED', events: phases, cpuSamples: [3, 1, 2], rssSamples: [30, 10, 20], tickSamples: [4, 2, 3], factualSuccess: true,
		cleanup: { ok: true, processTreeClean: true, listenersClosed: true },
	});
	assert.equal(report.resources.cpu.p95, 3);
	assert.equal(report.resources.cpu.basis, 'process_cpu_interval_delta_ms');
	assert.equal(report.resources.minecraftTick.p99, 4);
	assert.deepEqual(report.missingPhases, []);
	assert.equal(validateTask9TrialReport(report), true);
	const incomplete = buildTask9TrialReport({ identity: report, status: 'PASSED', events: [], factualSuccess: false, cleanup: { ok: true } });
	assert.equal(incomplete.status, 'FAILED');
	assert.ok(incomplete.missingPhases.length > 0);
});

test('adapts deterministic runner output into raw evidence artifacts', async () => {
	const root = await mkdtemp(path.join(tmpdir(), 'task9-harness-'));
	const output = await runTask9SimulatorMatrix({
		matrix: matrix({ repetitions: 1 }), artifactDirectory: root, runId: 'run-1', arm: 'optimized', sourceHash: 'sha256:source', configHash: 'sha256:config',
		runMatrix: async (options) => {
			assert.equal(options.includeRawEvents, true);
			assert.equal(options.matrix.trials[0].providerAvailabilityRequired, true);
			return {
				status: 'PASSED', rawEvents: phases.map((event) => ({ ...event, trialId: 'stone', repetition: 1 })),
				trials: [{ trialId: 'stone', repetition: 1, status: 'PASSED', scenarioId: 'stone-tool-gathering', seed: 20260821, agentLoad: 4, providerProfile: profile, metrics: { raw: { ticks: [{ wallDurationMs: 2 }] } }, systemSummary: { rawSamples: [{ cpu: { totalMs: 10 }, memory: { rssBytes: 2 } }, { cpu: { totalMs: 14 }, memory: { rssBytes: 3 } }] }, debug: { scenarioPassed: true }, cleanup: { ok: true, activeActions: 0, listeners: 0, relays: 0 } }],
			};
		},
	});
	assert.equal(output.status, 'PASSED');
	assert.equal(output.trials[0].correctness.factualSuccess, true);
	assert.deepEqual(output.trials[0].resources.cpu.raw, [4]);
	assert.deepEqual(output.trials[0].missingPhases, []);
	const files = await readdir(root);
	assert.ok(files.includes('run-manifest.json'));
	assert.ok(files.includes('events.jsonl'));
	assert.ok(files.some((name) => name.startsWith('trial-stone-1')));
});

test('pins the exact Luna xhigh fast Desktop scenario without performing a live run', async () => {
	const config = JSON.parse(await readFile(new URL('../config/headless-provider-matrix.json', import.meta.url), 'utf8'));
	const scenario = config.scenarios.find((entry) => entry.id === TASK9_LUNA_PICKAXE_PROFILE.id);
	assert.deepEqual({ provider: scenario.provider, model: scenario.model, reasoningEffort: scenario.reasoningEffort, serviceTier: scenario.serviceTier }, { provider: TASK9_LUNA_PICKAXE_PROFILE.provider, model: TASK9_LUNA_PICKAXE_PROFILE.model, reasoningEffort: TASK9_LUNA_PICKAXE_PROFILE.reasoningEffort, serviceTier: TASK9_LUNA_PICKAXE_PROFILE.serviceTier });
	assert.equal(scenario.repetitions, 3);
	assert.equal(scenario.requireFactualSuccess, true);
	assert.match(scenario.assert[1].command, /\{agent\}/);
	assert.equal(createTask9RunManifest({ runId: 'r', sourceCommit: 'a', sourceHash: 'b', matrixHash: 'c', configHash: 'd', pairingKey: 'p', providerProfile: profile, scheduler: { mode: 'fixed', fixedConcurrency: 16 } }).clockBasis.wall, 'monotonic_ms');
	const deterministic = JSON.parse(await readFile(new URL('../config/task9-performance-matrix.json', import.meta.url), 'utf8'));
	const normalized = normalizeTask9Matrix(deterministic);
	assert.equal(normalized.trials.length, 19);
	assert.equal(normalized.trials.every((entry) => entry.repetitions === 5), true);
});
