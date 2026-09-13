import assert from 'node:assert/strict';
import { mkdtemp, readFile, rm, writeFile } from 'node:fs/promises';
import os from 'node:os';
import path from 'node:path';
import test from 'node:test';

import {
	EXIT_CODES,
	parseLatencyRunnerArgs,
	runLatencyRunnerCli,
} from '../src/benchmark/latency-runner-cli.mjs';

const MATRIX = JSON.stringify({ version: 1, trials: [] });
const RECORDINGS = JSON.stringify([{ trialId: 'fixture', promptHash: 'sha256:' + 'a'.repeat(64) }]);

async function fixtureFiles() {
	const root = await mkdtemp(path.join(os.tmpdir(), 'latency-runner-cli-'));
	const matrix = path.join(root, 'matrix.json');
	const recordings = path.join(root, 'recordings.json');
	const prompt = path.join(root, 'prompt.txt');
	const artifacts = path.join(root, 'artifacts');
	await writeFile(matrix, MATRIX, 'utf8');
	await writeFile(recordings, RECORDINGS, 'utf8');
	await writeFile(prompt, 'private replay prompt', 'utf8');
	return { root, matrix, recordings, prompt, artifacts };
}

function capture() {
	const stdout = [];
	const stderr = [];
	return { stdout, stderr, out: (value) => stdout.push(String(value)), err: (value) => stderr.push(String(value)) };
}

test('rejects missing, relative, duplicate, unknown, and unbounded CLI arguments', async () => {
	const files = await fixtureFiles();
	try {
		assert.throws(() => parseLatencyRunnerArgs([]), /matrix/i);
		assert.throws(() => parseLatencyRunnerArgs(['--matrix', 'matrix.json', '--artifact-directory', files.artifacts]), /absolute/i);
		assert.throws(() => parseLatencyRunnerArgs(['--matrix', files.matrix, '--matrix', files.matrix, '--artifact-directory', files.artifacts]), /duplicate/i);
		assert.throws(() => parseLatencyRunnerArgs(['--matrix', files.matrix, '--artifact-directory', files.artifacts, '--unknown', 'x']), /unknown/i);
		assert.throws(() => parseLatencyRunnerArgs(['--matrix', files.matrix, '--artifact-directory', files.artifacts, '--planning-concurrency', '17']), /planning-concurrency/i);
		assert.throws(() => parseLatencyRunnerArgs(['--matrix', files.matrix, '--artifact-directory', files.artifacts, '--arm', 'x'.repeat(257)]), /bounded|length/i);
	} finally {
		await rm(files.root, { recursive: true, force: true });
	}
});

test('loads private matrix, replay, and prompt files and passes bounded metadata to the runner', async () => {
	const files = await fixtureFiles();
	const seen = [];
	const io = capture();
	try {
		const exitCode = await runLatencyRunnerCli([
			'--matrix', files.matrix,
			'--artifact-directory', files.artifacts,
			'--replay-recordings', files.recordings,
			'--replay-prompt-file', files.prompt,
			'--arm', 'baseline',
			'--runId', 'run-123',
			'--sourceHash', 'source-v1',
			'--configHash', 'config-v1',
			'--pairingKey', 'private-pairing-key',
			'--planning-concurrency', '4',
		], {
			runLatencyMatrix: async (options) => {
				seen.push(options);
				return { status: 'PASSED', matrix: { version: 1 }, trials: [], cleanup: { ok: true }, summary: 0 };
			},
			stdout: io.out,
			stderr: io.err,
		});
		assert.equal(exitCode, EXIT_CODES.PASSED);
		assert.equal(seen.length, 1);
		assert.deepEqual(seen[0].matrix, JSON.parse(MATRIX));
		assert.deepEqual(seen[0].replayRecordings, JSON.parse(RECORDINGS));
		assert.equal(seen[0].replayPrompt, 'private replay prompt');
		assert.equal(seen[0].artifactDirectory, files.artifacts);
		assert.equal(seen[0].arm, 'baseline');
		assert.equal(seen[0].runId, 'run-123');
		assert.equal(seen[0].sourceHash, 'source-v1');
		assert.equal(seen[0].configHash, 'config-v1');
		assert.equal(seen[0].pairingKey, 'private-pairing-key');
		assert.deepEqual(seen[0].baseContext, { arm: 'baseline', runId: 'run-123', sourceHash: 'source-v1', configHash: 'config-v1', pairingKey: 'private-pairing-key' });
		assert.equal(seen[0].planningConcurrency, 4);
		assert.equal(io.stdout.length, 1);
		const result = JSON.parse(io.stdout[0]);
		assert.equal(result.status, 'PASSED');
		assert.equal(result.metadata.arm, 'baseline');
		assert.equal(result.metadata.planningConcurrency, 4);
		assert.equal(JSON.stringify(result).includes('private replay prompt'), false);
		assert.equal(JSON.stringify(result).includes('private-pairing-key'), false);
		assert.equal(io.stderr.length, 0);
	} finally {
		await rm(files.root, { recursive: true, force: true });
	}
});

test('accepts a bounded inline replay prompt without exposing it in result or diagnostics', async () => {
	const files = await fixtureFiles();
	const io = capture();
	const prompt = 'prompt-that-must-stay-private';
	try {
		const exitCode = await runLatencyRunnerCli([
			'--matrix', files.matrix, '--artifact-directory', files.artifacts, '--replay-prompt', prompt,
		], {
			runLatencyMatrix: async (options) => {
				assert.equal(options.replayPrompt, prompt);
				return { status: 'PASSED', trials: [], cleanup: { ok: true } };
			},
			stdout: io.out, stderr: io.err,
		});
		assert.equal(exitCode, EXIT_CODES.PASSED);
		assert.equal(JSON.stringify(JSON.parse(io.stdout[0])).includes(prompt), false);
		assert.equal(io.stderr.length, 0);
	} finally {
		await rm(files.root, { recursive: true, force: true });
	}
});

test('treats an absolute --replay-prompt value naming a file as private prompt input', async () => {
	const files = await fixtureFiles();
	const io = capture();
	try {
		await runLatencyRunnerCli([
			'--matrix', files.matrix, '--artifact-directory', files.artifacts, '--replay-prompt', files.prompt,
		], {
			runLatencyMatrix: async (options) => {
				assert.equal(options.replayPrompt, 'private replay prompt');
				return { status: 'PASSED', trials: [], cleanup: { ok: true } };
			},
			stdout: io.out, stderr: io.err,
		});
		assert.equal(io.stdout.length, 1);
		assert.equal(JSON.stringify(JSON.parse(io.stdout[0])).includes('private replay prompt'), false);
	} finally {
		await rm(files.root, { recursive: true, force: true });
	}
});

test('returns skipped success for optional unavailable trials', async () => {
	const files = await fixtureFiles();
	const io = capture();
	try {
		const exitCode = await runLatencyRunnerCli(['--matrix', files.matrix, '--artifact-directory', files.artifacts], {
			runLatencyMatrix: async () => ({ status: 'PASSED', trials: [{ trialId: 'optional', status: 'SKIPPED' }], cleanup: { ok: true } }),
			stdout: io.out, stderr: io.err,
		});
		assert.equal(exitCode, EXIT_CODES.SKIPPED);
		assert.equal(JSON.parse(io.stdout[0]).status, 'SKIPPED');
	} finally {
		await rm(files.root, { recursive: true, force: true });
	}
});

test('returns failed exit status for a completed runner failure', async () => {
	const files = await fixtureFiles();
	const io = capture();
	try {
		const exitCode = await runLatencyRunnerCli(['--matrix', files.matrix, '--artifact-directory', files.artifacts], {
			runLatencyMatrix: async () => ({ status: 'FAILED', trials: [{ trialId: 'failed', status: 'FAILED', error: { code: 'SCENARIO_ASSERTION_FAILED', message: 'private response' } }], cleanup: { ok: true } }),
			stdout: io.out, stderr: io.err,
		});
		assert.equal(exitCode, EXIT_CODES.FAILED);
		assert.equal(JSON.parse(io.stdout[0]).status, 'FAILED');
		assert.deepEqual(io.stderr, ['latency-runner: SCENARIO_ASSERTION_FAILED\n']);
	} finally {
		await rm(files.root, { recursive: true, force: true });
	}
});

test('maps an invalid injected runner dependency to the internal-error exit code', async () => {
	const files = await fixtureFiles();
	const io = capture();
	try {
		const exitCode = await runLatencyRunnerCli(['--matrix', files.matrix, '--artifact-directory', files.artifacts], {
			runLatencyMatrix: 'not-a-function', stdout: io.out, stderr: io.err,
		});
		assert.equal(exitCode, EXIT_CODES.INTERNAL);
		assert.equal(JSON.parse(io.stdout[0]).error.code, 'CLI_INTERNAL');
	} finally {
		await rm(files.root, { recursive: true, force: true });
	}
});

test('preserves runner result on required-provider failure and maps its exit code', async () => {
	const files = await fixtureFiles();
	const io = capture();
	try {
		const error = Object.assign(new Error('provider unavailable: token=must-not-leak'), {
			code: 'PROVIDER_UNAVAILABLE',
			result: { status: 'FAILED', trials: [{ trialId: 'required', status: 'FAILED', error: { code: 'PROVIDER_UNAVAILABLE', message: 'token=must-not-leak' } }], cleanup: { ok: true } },
		});
		const exitCode = await runLatencyRunnerCli(['--matrix', files.matrix, '--artifact-directory', files.artifacts], {
			runLatencyMatrix: async () => { throw error; }, stdout: io.out, stderr: io.err,
		});
		assert.equal(exitCode, EXIT_CODES.REQUIRED_PROVIDER);
		assert.equal(io.stdout.length, 1);
		const result = JSON.parse(io.stdout[0]);
		assert.equal(result.status, 'FAILED');
		assert.equal(result.trials[0].error.code, 'PROVIDER_UNAVAILABLE');
		assert.equal(JSON.stringify(result).includes('must-not-leak'), false);
		assert.deepEqual(io.stderr, ['latency-runner: PROVIDER_UNAVAILABLE\n']);
	} finally {
		await rm(files.root, { recursive: true, force: true });
	}
});

test('rejects oversized replay files and malformed JSON before invoking the runner', async () => {
	const files = await fixtureFiles();
	const io = capture();
	let calls = 0;
	try {
		await writeFile(files.recordings, '{"not": "an array"}', 'utf8');
		const malformedExit = await runLatencyRunnerCli(['--matrix', files.matrix, '--artifact-directory', files.artifacts, '--replay-recordings', files.recordings], {
			runLatencyMatrix: async () => { calls += 1; return { status: 'PASSED' }; }, stdout: io.out, stderr: io.err,
		});
		assert.equal(malformedExit, EXIT_CODES.USAGE);
		assert.equal(calls, 0);
		await writeFile(files.recordings, 'x'.repeat(1_048_577), 'utf8');
		const oversizedExit = await runLatencyRunnerCli(['--matrix', files.matrix, '--artifact-directory', files.artifacts, '--replay-recordings', files.recordings], {
			runLatencyMatrix: async () => { calls += 1; return { status: 'PASSED' }; }, stdout: io.out, stderr: io.err,
		});
		assert.equal(oversizedExit, EXIT_CODES.USAGE);
		assert.equal(calls, 0);
		assert.equal(io.stdout.length, 2);
	} finally {
		await rm(files.root, { recursive: true, force: true });
	}
});

test('derives exact live-provider launch configuration without forwarding private CLI values', async () => {
	const files = await fixtureFiles();
	const liveMatrix = {
		version: 1, fixedSeeds: [42], agentLoads: [1, 4, 8, 16],
		trials: [
			{ id: 'live-codex', mode: 'live', scenarioId: 'fixture', seed: 42, agentLoad: 1, providerProfile: { provider: 'codex', model: 'fixture-codex', reasoningEffort: 'high', serviceTier: 'fast' }, repetitions: 1, turnBudgetMs: 100, trialBudgetMs: 500, turnCap: 2, providerAvailabilityRequired: false },
			{ id: 'live-kimi', mode: 'live', scenarioId: 'fixture', seed: 42, agentLoad: 1, providerProfile: { provider: 'kimi', model: 'kimi-code/k3', reasoningEffort: 'max', serviceTier: 'local' }, repetitions: 1, turnBudgetMs: 250, trialBudgetMs: 500, turnCap: 2, providerAvailabilityRequired: false },
		],
	};
	await writeFile(files.matrix, JSON.stringify(liveMatrix), 'utf8');
	const io = capture();
	const seen = [];
	const privatePrompt = 'private-provider-prompt';
	const privatePairingKey = 'credential-like-pairing-value';
	try {
		const exitCode = await runLatencyRunnerCli([
			'--matrix', files.matrix, '--artifact-directory', files.artifacts,
			'--replay-prompt', privatePrompt, '--pairing-key', privatePairingKey,
			'--planning-timeout-ms', '200',
		], {
			runLatencyMatrix: async (options) => {
				seen.push(options);
				const cwd = process.cwd();
				assert.deepEqual(options.liveProviderOptions.config.codex.launchProfile, { model: 'fixture-codex', reasoningEffort: 'high', serviceTier: 'fast' });
				assert.equal(options.liveProviderOptions.config.codex.cwd, cwd);
				assert.equal(options.liveProviderOptions.config.kimi.cwd, cwd);
				assert.equal(options.liveProviderOptions.config.kimi.executable, 'kimi');
				assert.equal(options.liveProviderOptions.config.kimi.catalogDiscovery, true);
				assert.deepEqual(options.liveProviderOptions.config.kimi.modelReasoningEfforts, { 'kimi-code/k3': ['max'] });
				assert.equal(options.liveProviderOptions.config.codex.planningTimeoutMs, 100, 'trial budget bounds CLI timeout');
				assert.equal(options.preflightTimeoutMs, 100);
				assert.equal(JSON.stringify(options.liveProviderOptions).includes(privatePrompt), false);
				assert.equal(JSON.stringify(options.liveProviderOptions).includes(privatePairingKey), false);
				return { status: 'PASSED', trials: [], cleanup: { ok: true } };
			},
			stdout: io.out, stderr: io.err,
		});
		assert.equal(exitCode, EXIT_CODES.PASSED);
		assert.equal(seen.length, 1);
		assert.equal(JSON.stringify(JSON.parse(io.stdout[0])).includes(privatePrompt), false);
		assert.equal(JSON.stringify(JSON.parse(io.stdout[0])).includes(privatePairingKey), false);
	} finally {
		await rm(files.root, { recursive: true, force: true });
	}
});

test('filters one exact trial without mutating the source matrix', async () => {
	const files = await fixtureFiles();
	const sourceMatrix = {
		version: 1, fixedSeeds: [42], agentLoads: [1, 4, 8, 16],
		trials: [
			{ id: 'cell-a', mode: 'instant', scenarioId: 'fixture-a', seed: 42, agentLoad: 1 },
			{ id: 'cell-b', mode: 'instant', scenarioId: 'fixture-b', seed: 42, agentLoad: 4 },
		],
	};
	await writeFile(files.matrix, JSON.stringify(sourceMatrix), 'utf8');
	const io = capture();
	try {
		const exitCode = await runLatencyRunnerCli(['--matrix', files.matrix, '--artifact-directory', files.artifacts, '--trial-id', 'cell-b'], {
			runLatencyMatrix: async (options) => {
				assert.deepEqual(options.matrix.trials.map((trial) => trial.id), ['cell-b']);
				assert.deepEqual(options.matrix.fixedSeeds, sourceMatrix.fixedSeeds);
				assert.deepEqual(options.matrix.agentLoads, sourceMatrix.agentLoads);
				options.matrix.trials[0].id = 'mutated-only-in-runner';
				return { status: 'PASSED', trials: [{ trialId: 'cell-b', status: 'PASSED' }], cleanup: { ok: true } };
			},
			stdout: io.out, stderr: io.err,
		});
		assert.equal(exitCode, EXIT_CODES.PASSED);
		assert.equal(JSON.parse(io.stdout[0]).metadata.trialId, 'cell-b');
		assert.deepEqual(JSON.parse(await readFile(files.matrix, 'utf8')), sourceMatrix);
	} finally {
		await rm(files.root, { recursive: true, force: true });
	}
});

test('rejects an unknown trial id before invoking the runner', async () => {
	const files = await fixtureFiles();
	const io = capture();
	let calls = 0;
	try {
		const exitCode = await runLatencyRunnerCli(['--matrix', files.matrix, '--artifact-directory', files.artifacts, '--trial-id', 'missing'], {
			runLatencyMatrix: async () => { calls += 1; return { status: 'PASSED' }; }, stdout: io.out, stderr: io.err,
		});
		assert.equal(exitCode, EXIT_CODES.USAGE);
		assert.equal(calls, 0);
		assert.equal(JSON.parse(io.stdout[0]).error.code, 'CLI_USAGE');
	} finally {
		await rm(files.root, { recursive: true, force: true });
	}
});

test('publishes bounded numeric metrics and CPU/memory summaries without raw private fields', async () => {
	const files = await fixtureFiles();
	const io = capture();
	try {
		await runLatencyRunnerCli(['--matrix', files.matrix, '--artifact-directory', files.artifacts], {
			runLatencyMatrix: async () => ({ status: 'PASSED', trials: [{
				trialId: 'metrics', status: 'PASSED', timingScope: 'full_path', durationMs: 20, taskDurationMs: 12, setupDurationMs: 8, totalDurationMs: 20,
				metrics: { version: 1, clockBasis: { wall: 'process_monotonic_ms', virtual: 'virtual_world_ms' }, result: { taskCompletionWallDurationMs: 12, tick: { count: 3, p95Ms: 4 }, privateNumber: 99 }, raw: { ticks: [{ prompt: 'secret', wallDurationMs: 2 }] }, context: { pairingKey: 'private' } },
				systemSummary: { sampleCount: 2, cpu: { totalMs: { p95: 7 }, privateNumber: { p95: 999 } }, memory: { rssBytes: { p95: 100 } }, privateNumber: 999, errors: [{ message: 'provider response' }] },
			}], cleanup: { ok: true } }),
			stdout: io.out, stderr: io.err,
		});
		const trial = JSON.parse(io.stdout[0]).trials[0];
		assert.equal(trial.metrics.result.taskCompletionWallDurationMs, 12);
		assert.equal(trial.timingScope, 'full_path');
		assert.equal(trial.durationMs, 20);
		assert.equal(trial.taskDurationMs, 12);
		assert.equal(trial.metrics.result.tick.p95Ms, 4);
		assert.equal(trial.metrics.result.privateNumber, undefined);
		assert.equal(trial.metrics.raw, undefined);
		assert.equal(trial.systemSummary.sampleCount, 2);
		assert.equal(trial.systemSummary.cpu.totalMs.p95, 7);
		assert.equal(trial.systemSummary.memory.rssBytes.p95, 100);
		assert.equal(trial.systemSummary.cpu.privateNumber, undefined);
		assert.equal(trial.systemSummary.privateNumber, undefined);
		assert.equal(JSON.stringify(trial).includes('provider response'), false);
	} finally {
		await rm(files.root, { recursive: true, force: true });
	}
});

test('preserves required-provider classification and CPU/memory deltas from runner-shaped trials', async () => {
	const files = await fixtureFiles();
	const requiredMatrix = {
		version: 1, fixedSeeds: [42], agentLoads: [1, 4, 8, 16],
		trials: [{ id: 'required-cell', mode: 'live', scenarioId: 'fixture', seed: 42, agentLoad: 1,
			providerProfile: { provider: 'codex', model: 'fixture', reasoningEffort: 'high', serviceTier: 'fast' },
			repetitions: 1, turnBudgetMs: 100, trialBudgetMs: 500, turnCap: 2, providerAvailabilityRequired: true }],
	};
	await writeFile(files.matrix, JSON.stringify(requiredMatrix), 'utf8');
	const io = capture();
	try {
		const exitCode = await runLatencyRunnerCli(['--matrix', files.matrix, '--artifact-directory', files.artifacts], {
			runLatencyMatrix: async () => ({
				status: 'FAILED',
				trials: [{ trialId: 'required-cell', status: 'FAILED', error: { code: 'PROVIDER_UNAVAILABLE', message: 'unavailable' }, systemSummary: {
					cpuDelta: { basis: 'process_resource_usage_delta_ms', userMs: 7, systemMs: 2, totalMs: 9, privateNumber: 99 },
					memoryDelta: { basis: 'process_memory_sample_delta_bytes', rssBytes: 30, heapUsedBytes: 20 },
					memoryPeak: { basis: 'process_memory_sample_bytes', rssBytes: 130, heapUsedBytes: 70 },
					privateNumber: 999,
				} }],
				cleanup: { ok: true },
			}),
			stdout: io.out, stderr: io.err,
		});
		assert.equal(exitCode, EXIT_CODES.REQUIRED_PROVIDER);
		const trial = JSON.parse(io.stdout[0]).trials[0];
		assert.equal(trial.providerAvailabilityRequired, true);
		assert.deepEqual(trial.systemSummary.cpuDelta, { userMs: 7, systemMs: 2, totalMs: 9 });
		assert.deepEqual(trial.systemSummary.memoryDelta, { rssBytes: 30, heapUsedBytes: 20 });
		assert.deepEqual(trial.systemSummary.memoryPeak, { rssBytes: 130, heapUsedBytes: 70 });
		assert.equal(trial.systemSummary.cpuDelta.privateNumber, undefined);
		assert.equal(trial.systemSummary.privateNumber, undefined);
	} finally {
		await rm(files.root, { recursive: true, force: true });
	}
});
