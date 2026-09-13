import assert from 'node:assert/strict';
import { mkdtemp, readFile, rm, writeFile } from 'node:fs/promises';
import os from 'node:os';
import path from 'node:path';
import test from 'node:test';

import { evaluateLatencyAcceptance, normalizeLatencyAcceptancePolicy } from '../src/benchmark/latency-acceptance.mjs';
import { compareInstrumentationRuns, runInstrumentationComparison } from '../src/benchmark/instrumentation-comparison.mjs';
import { main as acceptanceMain } from '../src/benchmark/latency-acceptance-cli.mjs';

function evidence(latencyScale = 1, overrides = {}) {
	const trials = [];
	for (const sessionState of ['cold', 'warm']) for (const agentLoad of [1, 8, 16]) for (let repetition = 1; repetition <= 5; repetition += 1) trials.push({
		trialId: `${sessionState}-${agentLoad}`, repetition, scenarioId: `scenario-${agentLoad}`, seed: 41 + repetition, agentLoad, sessionState,
		mode: 'live', timingScope: 'full_path', evidenceSource: 'fabric-headless', workloadConfigHash: 'workload-v1', sourceHash: 'implementation-v1',
		providerProfile: { provider: 'codex', model: 'gpt-5', reasoningEffort: 'high', serviceTier: 'priority' },
		status: 'PASSED', factualSuccess: true, synthetic: false,
		latencyMs: (100 + repetition) * latencyScale, tickP95Ms: 20, spans: { actionMs: (40 + repetition) * latencyScale, voiceMs: (20 + repetition) * latencyScale },
		...overrides,
	});
	return { trials };
}

function instrumentation(status = 'PASSED') {
	return { status, checks: [
		{ code: 'INSTRUMENTATION_SAMPLE_COUNT', status },
		{ code: 'INSTRUMENTATION_BEHAVIOR_PARITY', status },
		{ code: 'INSTRUMENTATION_P95_OVERHEAD', status, observedRatio: 1.02 },
	] };
}

test('certifies 2x only with cold and warm live evidence at 1, 8, and 16 agents', () => {
	const result = evaluateLatencyAcceptance({ baseline: evidence(1), optimized: evidence(0.5), instrumentationComparison: instrumentation() });
	assert.equal(result.status, 'PASSED');
	assert.equal(result.claimCertified, true);
	assert.equal(result.checks.filter((check) => check.code === 'VOICE_SPAN').every((check) => check.status === 'PASSED'), true);
	assert.equal(result.checks.filter((check) => check.code === 'ACTION_SPAN').every((check) => check.status === 'PASSED'), true);
});

test('fails closed on synthetic, missing warm, one-sample, factual, tick, or instrumentation evidence', () => {
	const baseline = evidence(1);
	const optimized = evidence(0.5);
	optimized.trials = optimized.trials.filter((trial) => trial.sessionState === 'cold' && trial.repetition === 1);
	optimized.trials[0] = { ...optimized.trials[0], synthetic: true, factualSuccess: false, tickP95Ms: 51 };
	const result = evaluateLatencyAcceptance({ baseline, optimized, instrumentationComparison: instrumentation('FAILED') });
	assert.equal(result.status, 'FAILED');
	for (const code of ['SAMPLE_COUNT', 'P95_SPEEDUP', 'FACTUAL_SUCCESS_PARITY', 'TICK_BUDGET', 'LIVE_PROVIDER_EVIDENCE', 'INSTRUMENTATION_COMPARISON']) assert.ok(result.checks.some((check) => check.code === code && check.status === 'FAILED'), code);
});

test('fails when a span exists in only one arm instead of hiding lost instrumentation', () => {
	const baseline = evidence(1);
	const optimized = evidence(0.5);
	optimized.trials = optimized.trials.map((trial) => ({ ...trial, spans: {} }));
	const result = evaluateLatencyAcceptance({ baseline, optimized, instrumentationComparison: instrumentation() });
	assert.equal(result.status, 'FAILED');
	assert.equal(result.checks.filter((check) => check.code === 'ACTION_SPAN').every((check) => check.status === 'FAILED'), true);
	assert.equal(result.checks.filter((check) => check.code === 'REQUIRED_SPAN_EVIDENCE').every((check) => check.status === 'FAILED'), true);
});

test('rejects duplicate repetitions before percentiles can count copied samples', () => {
	const baseline = evidence(1);
	baseline.trials[1] = { ...baseline.trials[0] };
	assert.throws(() => evaluateLatencyAcceptance({ baseline, optimized: evidence(0.5), instrumentationComparison: instrumentation() }), /duplicate repetition/);
});

test('fails workload pairing when provider, source, configuration, or scenario differs', () => {
	for (const mutation of [
		(trial) => ({ ...trial, scenarioId: 'different-scenario' }),
		(trial) => ({ ...trial, providerProfile: { ...trial.providerProfile, model: 'different-model' } }),
		(trial) => ({ ...trial, evidenceSource: 'different-runner' }),
		(trial) => ({ ...trial, workloadConfigHash: 'different-config' }),
	]) {
		const optimized = evidence(0.5);
		optimized.trials[0] = mutation(optimized.trials[0]);
		const result = evaluateLatencyAcceptance({ baseline: evidence(1), optimized, instrumentationComparison: instrumentation() });
		assert.equal(result.status, 'FAILED');
		assert.ok(result.checks.some((check) => check.code === 'WORKLOAD_PAIRING' && check.status === 'FAILED'));
	}
});

test('rejects post-setup timing scopes and MSPT is not accepted as tick p95', () => {
	const postSetup = evidence(0.5, { timingScope: 'task_only' });
	assert.throws(() => evaluateLatencyAcceptance({ baseline: evidence(1), optimized: postSetup, instrumentationComparison: instrumentation() }), /timingScope must be full_path/);
	const optimized = evidence(0.5);
	optimized.trials = optimized.trials.map(({ tickP95Ms: _tick, ...trial }) => ({ ...trial, metrics: { resources: { minecraftMspt: 20 } } }));
	const result = evaluateLatencyAcceptance({ baseline: evidence(1), optimized, instrumentationComparison: instrumentation() });
	assert.equal(result.status, 'FAILED');
	assert.ok(result.checks.some((check) => check.code === 'REQUIRED_SPAN_EVIDENCE' && check.status === 'FAILED'));
});

test('normalizes a machine-readable acceptance policy and derives the p95 ratio', () => {
	const policy = normalizeLatencyAcceptancePolicy({ minimumSpeedup: 2.5, requiredLoads: [1, 16], requiredSessionStates: ['cold'], minimumSamplesPerCell: 7 });
	assert.equal(policy.maximumP95Ratio, 0.4);
	assert.deepEqual(policy.requiredLoads, [1, 16]);
	assert.equal(policy.minimumSamplesPerCell, 7);
});

test('instrumentation comparison checks action parity, sample count, and measured overhead', () => {
	const run = (scale, changed = false) => ({ trials: Array.from({ length: 5 }, (_, index) => ({
		trialId: 'cell', repetition: index + 1, status: 'PASSED', durationMs: 100 * scale,
		cleanup: { ok: true },
		debug: { actionCommandHash: changed && index === 0 ? 'changed' : 'same', scenarioDigest: 'facts' },
	})) });
	const passing = compareInstrumentationRuns({ enabled: run(1.02), disabled: run(1), maxP95Ratio: 1.05 });
	assert.equal(passing.status, 'PASSED');
	assert.equal(passing.checks.find((check) => check.code === 'INSTRUMENTATION_P95_OVERHEAD').observedRatio, 1.02);
	const failing = compareInstrumentationRuns({ enabled: run(1.1, true), disabled: run(1), maxP95Ratio: 1.05 });
	assert.equal(failing.status, 'FAILED');
	assert.equal(failing.checks.find((check) => check.code === 'INSTRUMENTATION_BEHAVIOR_PARITY').status, 'FAILED');
});

test('instrumentation parity fails closed when behavior hashes are absent', () => {
	const run = { trials: Array.from({ length: 5 }, (_, index) => ({ trialId: 'cell', repetition: index + 1, status: 'PASSED', durationMs: 100, cleanup: { ok: true }, debug: {} })) };
	const result = compareInstrumentationRuns({ enabled: run, disabled: run });
	assert.equal(result.status, 'FAILED');
	assert.equal(result.checks.find((check) => check.code === 'INSTRUMENTATION_BEHAVIOR_PARITY').status, 'FAILED');
});

test('instrumentation comparison excludes failed or unclean trials from parity and overhead samples', () => {
	const run = (status, cleanupOk) => ({ trials: Array.from({ length: 5 }, (_, index) => ({
		trialId: 'cell', repetition: index + 1, status, durationMs: 100, cleanup: { ok: cleanupOk },
		debug: { actionCommandHash: 'same', scenarioDigest: 'facts' },
	})) });
	for (const [enabled, disabled] of [
		[run('FAILED', true), run('FAILED', true)],
		[run('PASSED', false), run('PASSED', false)],
	]) {
		const result = compareInstrumentationRuns({ enabled, disabled });
		assert.equal(result.status, 'FAILED');
		assert.equal(result.checks.find((check) => check.code === 'INSTRUMENTATION_SAMPLE_COUNT').observed.successfulPairs, 0);
		assert.equal(result.checks.find((check) => check.code === 'INSTRUMENTATION_P95_OVERHEAD').observedRatio, null);
		assert.ok(result.pairs.every((pair) => pair.behaviorParity === false && pair.durationRatio === null));
	}
});

test('instrumentation comparison counterbalances execution order', async () => {
	const observed = [];
	const result = await runInstrumentationComparison({
		minimumSamples: 2,
		runMatrix: async ({ instrumentation }) => {
			observed.push(instrumentation ? 'enabled' : 'disabled');
			return { trials: [{ trialId: 'cell', repetition: 1, status: 'PASSED', durationMs: instrumentation ? 102 : 100, cleanup: { ok: true }, debug: { actionCommandHash: 'actions', scenarioDigest: 'scenario' } }] };
		},
	});
	assert.deepEqual(observed, ['disabled', 'enabled', 'enabled', 'disabled']);
	assert.equal(result.status, 'PASSED');
	assert.deepEqual(result.orders, [['disabled', 'enabled'], ['enabled', 'disabled']]);
});

test('acceptance CLI writes a machine-readable report and returns claim status', async () => {
	const root = await mkdtemp(path.join(os.tmpdir(), 'latency-acceptance-'));
	const files = { baseline: path.join(root, 'baseline.json'), optimized: path.join(root, 'optimized.json'), policy: path.join(root, 'policy.json'), instrumentation: path.join(root, 'instrumentation.json'), output: path.join(root, 'acceptance.json') };
	await Promise.all([
		writeFile(files.baseline, JSON.stringify(evidence(1))), writeFile(files.optimized, JSON.stringify(evidence(0.5))),
		writeFile(files.policy, JSON.stringify({ minimumSpeedup: 2 })), writeFile(files.instrumentation, JSON.stringify(instrumentation())),
	]);
	const originalWrite = process.stdout.write;
	process.stdout.write = () => true;
	try {
		const code = await acceptanceMain(['--baseline', files.baseline, '--optimized', files.optimized, '--policy', files.policy, '--instrumentation', files.instrumentation, '--output', files.output]);
		assert.equal(code, 0);
		assert.equal(JSON.parse(await readFile(files.output, 'utf8')).claimCertified, true);
	} finally {
		process.stdout.write = originalWrite;
		await rm(root, { recursive: true, force: true });
	}
});

test('shipped latency matrices do not default any cell to one sample', async () => {
	for (const name of ['latency-matrix.json', 'latency-experiment-matrix.json']) {
		const matrix = JSON.parse(await readFile(new URL(`../config/${name}`, import.meta.url), 'utf8'));
		assert.equal(matrix.trials.every((trial) => trial.repetitions >= 5), true, name);
	}
});
