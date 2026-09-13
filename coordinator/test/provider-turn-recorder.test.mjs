import assert from 'node:assert/strict';
import test from 'node:test';

import { ControlLatencyRegistry } from '../src/control-latency-registry.mjs';
import { ProviderTurnRecorder } from '../src/provider-turn-recorder.mjs';
import { createProviderTurnTelemetry } from '../src/provider-turn-telemetry.mjs';
import { createExecutionSettings } from '../src/provider-identity.mjs';

test('preserves bounded requested versus effective execution evidence without filling unknown settings', async () => {
	const privateRows = [];
	const publicRows = [];
	const recorder = new ProviderTurnRecorder({
		runId: 'settings', scenarioId: 'native', privatePath: 'private.jsonl',
		appendFile: async (_path, text) => privateRows.push(JSON.parse(text)), publicSink: (row) => publicRows.push(row),
	});
	const executionSettings = createExecutionSettings({ provider: 'kimi', model: 'kimi-code/k3', reasoningEffort: 'xhigh', serviceTier: 'priority' }, {
		transport: 'acp', controlProtocol: 'arena_script', effective: { model: 'kimi-for-coding', thinkingMode: 'on' },
		evidence: { model: 'provider_reported', reasoningEffort: 'process_environment', serviceTier: 'not_supported' },
		limitations: ['effort_not_reported_by_provider'],
	});
	executionSettings.unrecognized = { token: 'never-copy' };
	await recorder.record({ provider: 'kimi', model: 'kimi-code/k3', executionSettings });
	await recorder.close();
	delete executionSettings.unrecognized;
	assert.deepEqual(privateRows[0].executionSettings, executionSettings);
	assert.deepEqual(publicRows[0].executionSettings, executionSettings);
	assert.equal(publicRows[0].executionSettings.effective.reasoningEffort, null);
	assert.equal(JSON.stringify(privateRows).includes('never-copy'), false);
});

test('prepares the private provider-turn artifact before appending', async () => {
	const prepared = [];
	const writes = [];
	const recorder = new ProviderTurnRecorder({
		runId: 'run-private-path', scenarioId: 'scenario-private-path', privatePath: 'private.jsonl',
		preparePrivateArtifact: async (filePath) => { prepared.push(filePath); },
		appendFile: async (_filePath, _text, options) => { writes.push(options); },
	});
	await recorder.record({ provider: 'codex', model: 'm' });
	await recorder.close();
	assert.deepEqual(prepared, ['private.jsonl']);
	assert.deepEqual(writes, [{ encoding: 'utf8', flag: 'a', mode: 0o600 }]);
});

test('records bounded redacted private turns and hash/excerpt-only public rows', async () => {
	const privateRows = [];
	const publicRows = [];
	const recorder = new ProviderTurnRecorder({
		runId: 'run-1', scenarioId: 'scenario-1', privatePath: 'private.jsonl',
		appendFile: async (_path, text) => privateRows.push(JSON.parse(text)),
		publicSink: (row) => publicRows.push(row), now: () => 1234,
	});
	await recorder.record({
		provider: 'codex', model: 'gpt-5.6-sol', reasoningEffort: 'high', goalRevision: 4, attempt: 2, retry: true,
		timing: { durationMs: 1234, apiDurationMs: 987 },
		input: 'prompt authorization: Bearer abc123 token=secret-token password=hunter2 SECRET_SHAPED=supersecret ' + '🙂'.repeat(100_000),
		output: '{"directive":"finish"}' + '漢'.repeat(40_000),
	});
	await recorder.close();

	assert.equal(privateRows.length, 1);
	assert.equal(publicRows.length, 1);
	const privateRow = privateRows[0];
	assert.equal(privateRow.runId, 'run-1');
	assert.equal(privateRow.scenarioId, 'scenario-1');
	assert.equal(privateRow.provider, 'codex');
	assert.equal(privateRow.model, 'gpt-5.6-sol');
	assert.equal(privateRow.reasoningEffort, 'high');
	assert.equal(privateRow.goalRevision, 4);
	assert.equal(privateRow.attempt, 2);
	assert.equal(privateRow.retry, true);
	assert.equal(privateRow.outcome, 'success');
	assert.equal(privateRow.timestamp, 1234);
	assert.deepEqual(privateRow.timing, { durationMs: 1234, apiDurationMs: 987 });
	assert.match(privateRow.output, /^\{"directive":"finish"\}/);
	assert.ok(Buffer.byteLength(JSON.stringify(privateRow), 'utf8') <= 262_144);
	assert.ok(Buffer.byteLength(privateRow.input, 'utf8') <= 65_536);
	assert.ok(Buffer.byteLength(privateRow.output, 'utf8') <= 65_536);
	assert.equal(privateRow.input.includes('abc123'), false);
	assert.equal(privateRow.input.includes('hunter2'), false);
	assert.equal(privateRow.input.includes('supersecret'), false);
	assert.equal(typeof publicRows[0].inputHash, 'string');
	assert.equal(typeof publicRows[0].outputHash, 'string');
	assert.equal(typeof publicRows[0].inputExcerpt, 'string');
	assert.equal(typeof publicRows[0].outputExcerpt, 'string');
	assert.ok(Buffer.byteLength(publicRows[0].inputExcerpt, 'utf8') <= 512);
	assert.ok(Buffer.byteLength(publicRows[0].outputExcerpt, 'utf8') <= 512);
	assert.equal(Object.hasOwn(publicRows[0], 'input'), false);
	assert.deepEqual(publicRows[0].timing, { durationMs: 1234, apiDurationMs: 987 });
	assert.equal(JSON.stringify(publicRows[0]).includes('secret-token'), false);
});

test('redacts quoted JSON credential keys and values in both private and public records', async () => {
	const privateRows = [];
	const publicRows = [];
	const recorder = new ProviderTurnRecorder({
		runId: 'run-json', scenarioId: 'scenario-json', privatePath: 'private.jsonl',
		appendFile: async (_path, text) => privateRows.push(JSON.parse(text)), publicSink: (row) => publicRows.push(row),
	});
	const quotedSecrets = '{"token":"TOKENSECRET","password":"PASSSECRET","client_secret":"CLIENTSECRET","authorization":"Bearer BEARERSECRET"}';
	await recorder.record({ provider: 'codex', model: 'm', reasoningEffort: 'high', goalRevision: 1, attempt: 1, retry: false, input: quotedSecrets, output: quotedSecrets });
	await recorder.close();

	assert.equal(privateRows.length, 1);
	assert.equal(JSON.stringify(privateRows[0]).includes('TOKENSECRET'), false);
	assert.equal(JSON.stringify(privateRows[0]).includes('PASSSECRET'), false);
	assert.equal(JSON.stringify(privateRows[0]).includes('CLIENTSECRET'), false);
	assert.equal(JSON.stringify(privateRows[0]).includes('BEARERSECRET'), false);
	assert.equal(JSON.stringify(publicRows[0]).includes('TOKENSECRET'), false);
	assert.equal(JSON.stringify(publicRows[0]).includes('PASSSECRET'), false);
	assert.equal(JSON.stringify(publicRows[0]).includes('CLIENTSECRET'), false);
	assert.equal(JSON.stringify(publicRows[0]).includes('BEARERSECRET'), false);
});

test('preserves wall-clock timing when a provider has no native API duration', async () => {
	const rows = [];
	const recorder = new ProviderTurnRecorder({
		runId: 'run-timing', scenarioId: 'scenario-timing', privatePath: 'private.jsonl',
		appendFile: async (_path, text) => rows.push(JSON.parse(text)),
	});
	await recorder.record({ provider: 'codex', model: 'm', reasoningEffort: 'low', timing: { durationMs: 42, apiDurationMs: null } });
	await recorder.close();
	assert.deepEqual(rows[0].timing, { durationMs: 42, apiDurationMs: null });
});

test('preserves agent isolation and provider-native usage without estimating missing categories', async () => {
	const rows = [];
	const recorder = new ProviderTurnRecorder({
		runId: 'run-metrics', scenarioId: 'scenario-metrics', privatePath: 'private.jsonl',
		appendFile: async (_path, text) => rows.push(JSON.parse(text)),
	});
	await recorder.record({
		agentId: 'agent-8', provider: 'codex', model: 'm', reasoningEffort: 'high', retry: true,
		timing: { durationMs: 80, apiDurationMs: 60, queueWaitMs: 20 },
		tokens: { input: 100, output: 25, reasoning: 10, cached: 40 },
		rateLimited: true, compaction: true,
	});
	await recorder.close();

	assert.equal(rows[0].agentId, 'agent-8');
	assert.deepEqual(rows[0].timing, { durationMs: 80, apiDurationMs: 60, queueWaitMs: 20 });
	assert.deepEqual(rows[0].tokens, { input: 100, output: 25, reasoning: 10, cached: 40, cacheWrite: null });
	assert.equal(rows[0].rateLimited, true);
	assert.equal(rows[0].compaction, true);
});

test('optional complete provider telemetry remains a strict bounded allowlist', () => {
	const telemetry = createProviderTurnTelemetry({
		provider: 'codex', model: 'm', operation: 'decide', durationMs: 50,
		tokens: { input: 12, output: null, reasoning: 3, cached: null, cacheWrite: 4 },
		rateLimited: true, compaction: false, privatePrompt: 'never retain this',
	});
	assert.deepEqual(telemetry.tokens, { input: 12, output: null, reasoning: 3, cached: null, cacheWrite: 4 });
	assert.equal(telemetry.rateLimited, true);
	assert.equal(telemetry.compaction, false);
	assert.equal(JSON.stringify(telemetry).includes('privatePrompt'), false);
});

test('complete latency snapshots include a nearest-rank p99 without changing legacy status snapshots', () => {
	const registry = new ControlLatencyRegistry({ windowSize: 100 });
	for (let value = 1; value <= 100; value += 1) registry.record('action_completion', value);
	assert.deepEqual(registry.performanceSnapshot(), [{
		operation: 'action_completion', count: 100, p50Ms: 50, p95Ms: 95, p99Ms: 99,
	}]);
	assert.deepEqual(registry.snapshot(), [{ operation: 'action_completion', count: 100, p50Ms: 50, p95Ms: 95 }]);
});

test('redacts escaped and delimiter-rich quoted JSON credential values', async () => {
	const privateRows = [];
	const publicRows = [];
	const recorder = new ProviderTurnRecorder({
		runId: 'run-json-rich', scenarioId: 'scenario-json-rich', privatePath: 'private.jsonl',
		appendFile: async (_path, text) => privateRows.push(JSON.parse(text)), publicSink: (row) => publicRows.push(row),
	});
	const quotedSecrets = [
		'{"token":"TOKEN SECRET"}',
		'{"token":"TOKEN\\nSECRET"}',
		'{"password": "my password"}',
		'{"client_secret":"CLIENT,SECRET"}',
		'{"authorization":"secret } value"}',
	].join(' ');
	await recorder.record({ provider: 'codex', model: 'm', reasoningEffort: 'high', goalRevision: 1, attempt: 1, retry: false, input: quotedSecrets, output: quotedSecrets });
	await recorder.close();

	const privateText = JSON.stringify(privateRows[0]);
	const publicText = JSON.stringify(publicRows[0]);
	for (const secret of ['TOKEN SECRET', 'TOKEN\\nSECRET', 'my password', 'CLIENT,SECRET', 'secret } value']) {
		assert.equal(privateText.includes(secret), false, `private record leaked ${secret}`);
		assert.equal(publicText.includes(secret), false, `public record leaked ${secret}`);
	}
});

test('serializes records and swallows public sink failures without blocking close', async () => {
	const writes = [];
	let release;
	const recorder = new ProviderTurnRecorder({
		runId: 'run', scenarioId: 'scenario', privatePath: 'private.jsonl',
		appendFile: async (_path, text) => {
			writes.push(JSON.parse(text));
			if (writes.length === 1) await new Promise((resolve) => { release = resolve; });
		},
		publicSink: () => { throw new Error('sink down'); },
	});
	const first = recorder.record({ provider: 'codex', model: 'm', reasoningEffort: 'low', goalRevision: 1, attempt: 1, retry: false, input: 'a', output: 'b' });
	const second = recorder.record({ provider: 'codex', model: 'm', reasoningEffort: 'low', goalRevision: 1, attempt: 2, retry: true, input: 'c', output: 'd' });
	await new Promise((resolve) => setImmediate(resolve));
	assert.equal(writes.length, 1);
	release();
	await Promise.all([first, second, recorder.close()]);
	assert.deepEqual(writes.map((row) => row.attempt), [1, 2]);
});

test('error rows retain only allowlisted structured fields', async () => {
	const rows = [];
	const recorder = new ProviderTurnRecorder({
		runId: 'run', scenarioId: 'scenario', privatePath: 'private.jsonl', appendFile: async (_path, text) => rows.push(JSON.parse(text)),
	});
	const error = Object.assign(new Error('ARBITRARY_PROVIDER_SECRET at C:\\Users\\lucas\\secret\\provider.js token=env-value'), { code: 'PROVIDER_UNAVAILABLE', category: 'provider', stack: 'Error\n at C:\\Users\\lucas\\secret\\provider.js' });
	await recorder.record({ provider: 'gemini', model: 'm', reasoningEffort: 'high', goalRevision: 2, attempt: 1, retry: false, input: 'prompt', output: 'partial output', error });
	await recorder.close();
	assert.equal(rows[0].outcome, 'error');
	assert.equal(rows[0].error.code, 'PROVIDER_UNAVAILABLE');
	assert.deepEqual(rows[0].error, { code: 'PROVIDER_UNAVAILABLE', category: 'provider' });
	assert.equal(JSON.stringify(rows[0]).includes('ARBITRARY_PROVIDER_SECRET'), false);
	assert.equal(JSON.stringify(rows[0]).includes('C:\\Users\\lucas\\secret'), false);
	assert.equal(JSON.stringify(rows[0]).includes('env-value'), false);
});

test('hung provider diagnostics never delay control and close remains bounded and idempotent', async () => {
	const recorder = new ProviderTurnRecorder({
		runId: 'run-hung', scenarioId: 'scenario-hung', privatePath: 'private.jsonl',
		appendFile: () => new Promise(() => {}),
		maxPending: 2, operationTimeoutMs: 20, closeTimeoutMs: 30,
	});
	const startedAt = Date.now();
	for (let index = 0; index < 100; index += 1) {
		await recorder.record({ provider: 'codex', model: 'm', attempt: index });
	}
	assert.ok(Date.now() - startedAt < 100, 'recording must only transfer bounded ownership');
	assert.ok(recorder.statusSnapshot().droppedCount > 0);
	const firstClose = recorder.close();
	assert.strictEqual(recorder.close(), firstClose);
	await firstClose;
	assert.ok(Date.now() - startedAt < 200, 'hung diagnostics must not hang shutdown');
});

test('provider recorder survives a rejected sink and writes later records', async () => {
	const attempts = [];
	let calls = 0;
	const recorder = new ProviderTurnRecorder({
		runId: 'run-recovery', scenarioId: 'scenario-recovery', privatePath: 'private.jsonl',
		appendFile: async (_path, text) => {
			calls += 1;
			if (calls === 1) throw new Error('temporary sink failure');
			attempts.push(JSON.parse(text).attempt);
		},
	});
	await recorder.record({ provider: 'codex', model: 'm', attempt: 1 });
	await recorder.record({ provider: 'codex', model: 'm', attempt: 2 });
	await recorder.close();
	assert.deepEqual(attempts, [2]);
});
