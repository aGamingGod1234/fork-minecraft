import assert from 'node:assert/strict';
import { mkdtemp, readFile, rm, writeFile } from 'node:fs/promises';
import os from 'node:os';
import path from 'node:path';
import { spawn } from 'node:child_process';
import test from 'node:test';
import { fileURLToPath } from 'node:url';

import { runNativeToolCli } from '../src/native-tool-cli-boundary.mjs';

const coordinatorRoot = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');
const privateMissingModule = 'C:\\private\\api-token-secret.mjs';
const maximumDiagnosticBytes = 8 * 1_024;

function runCli(script, args, options = {}) {
	return new Promise((resolve, reject) => {
		const child = spawn(process.execPath, [path.join(coordinatorRoot, 'src', script), ...args], {
			cwd: coordinatorRoot,
			env: { ...process.env, ...options.env },
			stdio: ['ignore', 'pipe', 'pipe'],
			windowsHide: true,
		});
		let stdout = '';
		let stderr = '';
		const timeout = setTimeout(() => child.kill(), 10_000);
		child.stdout.on('data', (chunk) => { stdout += chunk; });
		child.stderr.on('data', (chunk) => { stderr += chunk; });
		child.on('error', reject);
		child.on('close', (code, signal) => {
			clearTimeout(timeout);
			resolve({ code, signal, stdout, stderr });
		});
	});
}

function assertBoundedSanitizedFailure(result, expectedCode = null) {
	assert.equal(result.signal, null);
	assert.equal(result.code, 1);
	const output = `${result.stdout}${result.stderr}`;
	assert.ok(Buffer.byteLength(output, 'utf8') <= maximumDiagnosticBytes, `diagnostic was ${Buffer.byteLength(output, 'utf8')} bytes`);
	assert.doesNotMatch(output, /C:\\private|api-token-secret/i);
	assert.doesNotMatch(output, /Users\\lucas|\.worktrees|coordinator\\src/i);
	assert.equal(result.stderr, '');
	const lines = result.stdout.trim().split(/\r?\n/).filter(Boolean);
	assert.equal(lines.length, 1);
	const failure = JSON.parse(lines[0]);
	assert.equal(failure.status, 'FAILED');
	assert.match(failure.code, /^[A-Z][A-Z0-9_:-]{0,63}$/);
	if (expectedCode !== null) assert.equal(failure.code, expectedCode);
	assert.equal(typeof failure.message, 'string');
	assert.equal(typeof failure.stack, 'string');
}

async function writeFixtureService(filePath, { resultExpression = '{ toolCalls: 1 }', stopBody = '' } = {}) {
	await writeFile(filePath, `
export class CodexService {
 async start() {}
 async stop() { ${stopBody} }
 async createAgent() {
  return {
   async setGoalRevision() {},
   async act(input, context) {
    const call = async (actionType) => context.executeTool({ tool: { kind: 'action', actionType, ...(actionType === 'break_block' ? { arguments: { x: 2, y: 64, z: 1, expectedBlockId: 'minecraft:stone', timeoutMs: 15_000 } } : {}) } });
    if (input.includes('mine the known')) { await call('navigate_to'); await call('break_block'); return ${resultExpression}; }
    if (input.includes('craft_inventory')) { await call('craft_inventory'); return ${resultExpression}; }
    await call('chat'); return ${resultExpression};
   },
  };
 }
}
`, 'utf8');
}

test('native A/B trial contains missing service-module failures at the CLI root', async () => {
	const first = await runCli('native-tool-ab-trial.mjs', [privateMissingModule, 'baseline', '1']);
	const second = await runCli('native-tool-ab-trial.mjs', [privateMissingModule, 'baseline', '1']);
	assertBoundedSanitizedFailure(first, 'ERR_MODULE_NOT_FOUND');
	assertBoundedSanitizedFailure(second, 'ERR_MODULE_NOT_FOUND');
});

test('native A/B runner contains child module failures at the CLI root', async () => {
	const root = await mkdtemp(path.join(os.tmpdir(), 'native-ab-cli-failure-'));
	try {
		const outputPath = path.join(root, 'private-result.json');
		const first = await runCli('native-tool-ab-runner.mjs', [privateMissingModule, privateMissingModule, outputPath, '2']);
		const second = await runCli('native-tool-ab-runner.mjs', [privateMissingModule, privateMissingModule, outputPath, '2']);
		for (const result of [first, second]) {
			assert.equal(result.signal, null);
			assert.equal(result.code, 1);
			assert.equal(result.stderr, '');
			assert.ok(Buffer.byteLength(result.stdout, 'utf8') <= maximumDiagnosticBytes);
			assert.doesNotMatch(result.stdout, /C:\\private|api-token-secret/i);
			assert.doesNotMatch(result.stdout, /Users\\lucas|\.worktrees|coordinator\\src/i);
			const lines = result.stdout.trim().split(/\r?\n/).filter(Boolean).map((line) => JSON.parse(line));
			assert.equal(lines.length, 5);
			assert.equal(lines.at(-1).status, 'FAILED');
		}
		const artifact = JSON.parse(await readFile(outputPath, 'utf8'));
		assert.equal(artifact.failed, 4);
		assert.ok(artifact.rows.every((row) => row.code === 'ERR_MODULE_NOT_FOUND'));
		assert.doesNotMatch(JSON.stringify(artifact), /C:\\private|api-token-secret/i);
	} finally {
		await rm(root, { recursive: true, force: true });
	}
});

test('native load probe contains setup validation failures at the CLI root', async () => {
	const first = await runCli('native-tool-load-probe.mjs', [privateMissingModule, 'low', 'fast', '0']);
	const second = await runCli('native-tool-load-probe.mjs', [privateMissingModule, 'low', 'fast', '0']);
	assertBoundedSanitizedFailure(first, 'ERROR');
	assertBoundedSanitizedFailure(second, 'ERROR');
});

test('native tool probe contains setup failures at the CLI root', async () => {
	const root = await mkdtemp(path.join(os.tmpdir(), 'native-probe-cli-failure-'));
	try {
		const blockedTemp = path.join(root, 'api-token-secret.mjs');
		await writeFile(blockedTemp, 'not a directory', 'utf8');
		const first = await runCli('native-tool-probe.mjs', ['fixture-model', 'low', 'fast'], {
			env: { TEMP: blockedTemp, TMP: blockedTemp, TMPDIR: blockedTemp },
		});
		assertBoundedSanitizedFailure(first, 'ENOTDIR');
	} finally {
		await rm(root, { recursive: true, force: true });
	}
});

test('native A/B runner success reports only the artifact basename', async () => {
	const root = await mkdtemp(path.join(os.tmpdir(), 'native-ab-cli-success-'));
	try {
		const fixture = path.join(root, 'fixture-service.mjs');
		const outputPath = path.join(root, 'private-artifacts', 'ab-result.json');
		await writeFixtureService(fixture);
		const result = await runCli('native-tool-ab-runner.mjs', [fixture, fixture, outputPath, '2']);
		assert.equal(result.code, 0, result.stderr || result.stdout);
		assert.equal(result.stderr, '');
		const lines = result.stdout.trim().split(/\r?\n/).filter(Boolean);
		const summary = JSON.parse(lines.at(-1));
		assert.equal(summary.status, 'PASSED');
		assert.equal(summary.outputPath, 'ab-result.json');
		assert.doesNotMatch(result.stdout, new RegExp(root.replace(/[.*+?^${}()|[\]\\]/g, '\\$&'), 'i'));
		assert.doesNotMatch(result.stdout, /private-artifacts/i);
		assert.equal(JSON.parse(await readFile(outputPath, 'utf8')).failed, 0);
	} finally {
		await rm(root, { recursive: true, force: true });
	}
});

test('native trial sanitizes credential-shaped success fields through the common boundary', async () => {
	const root = await mkdtemp(path.join(os.tmpdir(), 'native-cli-sanitized-success-'));
	try {
		const fixture = path.join(root, 'fixture-service.mjs');
		await writeFixtureService(fixture);
		const result = await runCli('native-tool-ab-trial.mjs', [
			fixture,
			'C:\\private\\api-token-secret.mjs',
			'1',
			'/private/oauth-secret/model',
			'credential=private-effort',
			'api_key=private-tier',
		]);
		assert.equal(result.code, 0, result.stderr || result.stdout);
		assert.equal(result.stderr, '');
		assert.ok(Buffer.byteLength(result.stdout, 'utf8') <= maximumDiagnosticBytes);
		assert.doesNotMatch(result.stdout, /private|api-token-secret|oauth-secret|private-effort|private-tier/i);
		const outcome = JSON.parse(result.stdout);
		assert.equal(outcome.status, 'PASSED');
		assert.equal(outcome.trial, 1);
		assert.equal(outcome.profile.model, '[location redacted]');
	} finally {
		await rm(root, { recursive: true, force: true });
	}
});

test('native terminal serializer bounds escaped JSON to 8 KiB and keeps it valid', async () => {
	const root = await mkdtemp(path.join(os.tmpdir(), 'native-cli-bounded-success-'));
	try {
		const fixture = path.join(root, 'fixture-service.mjs');
		await writeFixtureService(fixture, { resultExpression: `{ toolCalls: 1, payload: ('"' + '\\\\').repeat(10_000) }` });
		const result = await runCli('native-tool-ab-trial.mjs', [fixture, 'baseline', '1']);
		assert.equal(result.code, 0, result.stderr || result.stdout);
		assert.equal(result.stderr, '');
		assert.ok(Buffer.byteLength(result.stdout, 'utf8') <= maximumDiagnosticBytes);
		const outcome = JSON.parse(result.stdout);
		assert.equal(outcome.status, 'PASSED');
		assert.equal(outcome.variant, 'baseline');
		assert.equal(outcome.profile.model, 'gpt-5.6-luna');
	} finally {
		await rm(root, { recursive: true, force: true });
	}
});

test('cleanup failure replaces success with exactly one failed terminal outcome', async () => {
	const root = await mkdtemp(path.join(os.tmpdir(), 'native-cli-cleanup-failure-'));
	try {
		const fixture = path.join(root, 'fixture-service.mjs');
		await writeFixtureService(fixture, { stopBody: `throw Object.assign(new Error('cleanup at C:\\\\private\\\\api-token-secret.mjs'), { code: 'CLEANUP_FAILED' });` });
		const result = await runCli('native-tool-ab-trial.mjs', [fixture, 'baseline', '1']);
		assert.equal(result.code, 1);
		assert.equal(result.stderr, '');
		const lines = result.stdout.trim().split(/\r?\n/).filter(Boolean);
		assert.equal(lines.length, 1);
		const outcome = JSON.parse(lines[0]);
		assert.equal(outcome.status, 'FAILED');
		assert.equal(outcome.code, 'CLEANUP_FAILED');
		assert.doesNotMatch(result.stdout, /C:\\private|api-token-secret/i);
	} finally {
		await rm(root, { recursive: true, force: true });
	}
});

test('hostile terminal status access cannot create a second outcome', async (context) => {
	for (const [name, fixture] of [
		['accessor', () => {
			let calls = 0;
			const value = Object.create(null, {
				status: { enumerable: true, get() { calls += 1; throw new Error('must not run'); } },
			});
			return { value, calls: () => calls };
		}],
		['proxy', () => {
			let calls = 0;
			const value = new Proxy({}, {
				get(_target, property) {
					if (property === 'then') return undefined;
					calls += 1;
					throw new Error('must not run');
				},
				ownKeys() { calls += 1; throw new Error('must not run'); },
			});
			return { value, calls: () => calls };
		}],
	]) await context.test(name, async () => {
		const { value, calls } = fixture();
		const stdout = [];
		const stderr = [];
		const exitCode = await runNativeToolCli(async () => value, {
			stdout: { write: (line) => stdout.push(String(line)) },
			stderr: { write: (line) => stderr.push(String(line)) },
		});
		assert.equal(exitCode, 1);
		assert.equal(calls(), 0);
		assert.equal(stdout.length, 1);
		assert.equal(stderr.length, 0);
		assert.ok(Buffer.byteLength(stdout[0], 'utf8') <= maximumDiagnosticBytes);
		assert.deepEqual(JSON.parse(stdout[0]), { status: 'FAILED', code: 'INVALID_OUTCOME' });
	});
});
