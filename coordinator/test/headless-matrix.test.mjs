import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import test from 'node:test';
import {
	normalizeHeadlessMatrix,
	normalizeHeadlessScenario,
	selectHeadlessScenarios,
	scenarioReport,
	writeHeadlessCliFailure,
} from '../src/headless-matrix.mjs';

const validScenario = (overrides = {}) => ({
	id: 'codex-chat-completion', provider: 'codex', model: 'gpt-5.6-sol',
	reasoningEffort: 'high', serviceTier: 'fast', task: 'Send HEADLESS_PASS',
	timeoutMs: 180000, assert: [{ type: 'lifecycle', state: 'COMPLETED' }], ...overrides,
});

test('headless CLI exception writer redacts and bounds the stack it emits', () => {
	const writes = [];
	const error = new Error('Authorization: Bearer cli-secret');
	error.stack = `Error: Authorization: Bearer cli-secret\n at C:\\private\\headless.mjs:1:2\n${'x'.repeat(8_000)}`;
	writeHeadlessCliFailure(error, (line) => writes.push(line));
	assert.equal(writes.length, 1);
	assert.doesNotMatch(writes[0], /cli-secret|private/);
	assert.ok(Buffer.byteLength(writes[0], 'utf8') <= 4_097);
});

test('normalizes one bounded real-provider scenario', () => {
	const matrix = normalizeHeadlessMatrix({ version: 1, scenarios: [validScenario({ setupBlocks: [{ x: 2, y: 201, z: 0, blockId: 'minecraft:oak_log' }] })] });
	assert.deepEqual(matrix.scenarios[0].assertions, [{ type: 'lifecycle', state: 'COMPLETED' }]);
	assert.deepEqual(matrix.scenarios[0].setupBlocks, [{ x: 2, y: 201, z: 0, blockId: 'minecraft:oak_log' }]);
	assert.equal(matrix.scenarios[0].rosterSize, 1);
	assert.equal(matrix.version, 1);
	assert.ok(Object.isFrozen(matrix));
	assert.ok(Object.isFrozen(matrix.scenarios[0]));
	assert.throws(() => normalizeHeadlessScenario(validScenario({ provider: 'gemini' })), /unsupported provider/i);
});

test('normalizes only supported concurrent roster sizes', () => {
	for (const rosterSize of [1, 8, 16]) {
		assert.equal(normalizeHeadlessScenario(validScenario({ rosterSize })).rosterSize, rosterSize);
	}
	for (const rosterSize of [0, 2, 7, 9, 17, '8']) {
		assert.throws(() => normalizeHeadlessScenario(validScenario({ rosterSize })), /rosterSize/i);
	}
});

test('accepts Cursor Composer and Grok scenarios through the same matrix schema', () => {
	const matrix = normalizeHeadlessMatrix({ version: 1, scenarios: [
		validScenario({ id: 'cursor-composer', provider: 'cursor', model: 'composer-2.5', reasoningEffort: 'high', serviceTier: 'priority' }),
		validScenario({ id: 'cursor-grok', provider: 'cursor', model: 'grok-4.6', reasoningEffort: 'high', serviceTier: 'fast' }),
	] });
	assert.deepEqual(matrix.scenarios.map((scenario) => scenario.provider), ['cursor', 'cursor']);
});

test('checked-in live matrix covers every provider with real model and setting combinations', () => {
	const matrix = normalizeHeadlessMatrix(JSON.parse(readFileSync(new URL('../config/headless-provider-matrix.json', import.meta.url), 'utf8')));
	assert.equal(matrix.scenarios.length, 16);
	for (const provider of ['codex', 'kimi', 'cursor']) {
		const scenarios = matrix.scenarios.filter((scenario) => scenario.provider === provider);
		assert.ok(new Set(scenarios.map((scenario) => scenario.model)).size >= 2, `${provider} needs at least two models`);
		for (const model of new Set(scenarios.map((scenario) => scenario.model))) {
			assert.ok(new Set(scenarios.filter((scenario) => scenario.model === model).map((scenario) => `${scenario.reasoningEffort}/${scenario.serviceTier}`)).size >= 2, `${provider}/${model} needs two settings`);
		}
		assert.ok(scenarios.every((scenario) => ['codex-luna-xhigh-fast-mine-oak-log', 'codex-luna-xhigh-fast-wooden-pickaxe'].includes(scenario.id)
			? scenario.requireFactualSuccess && scenario.assertions.some((assertion) => assertion.type === 'rcon')
			: scenario.id === 'codex-sol-low-priority'
				? scenario.assertions.some((assertion) => assertion.type === 'action' && assertion.actionType === 'navigate_to')
			: scenario.provider === 'codex'
				? ['chat', 'action'].every((type) => scenario.assertions.some((assertion) => assertion.type === type))
				: ['chat', 'action', 'program'].every((type) => scenario.assertions.some((assertion) => assertion.type === type))));
	}
	assert.deepEqual([...new Set(matrix.scenarios.filter((scenario) => scenario.provider === 'cursor').map((scenario) => scenario.model))], ['composer-2.5', 'grok-4.5', 'grok-4.6']);
	assert.deepEqual([...new Set(matrix.scenarios.map((scenario) => scenario.rosterSize))].sort((left, right) => left - right), [1, 8, 16]);
});

test('rejects duplicate IDs, unknown assertion types, and unbounded timeouts', () => {
	assert.throws(() => normalizeHeadlessMatrix({ version: 1, scenarios: [validScenario(), validScenario()] }), /duplicate/i);
	assert.throws(() => normalizeHeadlessScenario({ ...validScenario(), id: '../escape' }, 0), /id|path|separator/i);
	assert.throws(() => normalizeHeadlessScenario({ ...validScenario(), id: 'bad\\id' }, 0), /id|path|separator/i);
	assert.throws(() => normalizeHeadlessScenario({ ...validScenario(), id: 'bad\u0000id' }, 0), /id|control/i);
	assert.throws(() => normalizeHeadlessScenario({ ...validScenario(), assert: [{ type: 'unknown' }] }, 0), /assert/i);
	assert.throws(() => normalizeHeadlessScenario({ ...validScenario(), timeoutMs: 0 }, 0), /timeout/i);
	assert.throws(() => normalizeHeadlessScenario({ ...validScenario(), timeoutMs: 900001 }, 0), /timeout/i);
});

test('supports every bounded assertion shape and rejects unknown keys', () => {
	const assertions = [
		{ type: 'lifecycle', state: 'ERROR' },
		{ type: 'chat', message: 'marker' },
		{ type: 'action', actionType: 'move', args: { x: 1 }, resultState: 'SUCCEEDED' },
		{ type: 'program', event: 'program_finished', status: 'COMPLETED' },
		{ type: 'rcon', command: 'data get entity @s Pos', match: '1.0' },
	];
	const normalized = normalizeHeadlessScenario(validScenario({ assert: assertions }), 0);
	assert.deepEqual(normalized.assertions, assertions);
	assert.throws(() => normalizeHeadlessScenario({ ...validScenario(), extra: true }, 0), /unknown|key/i);
	assert.throws(() => normalizeHeadlessScenario({ ...validScenario(), setupBlocks: [{ x: 0, y: 201, z: 0, blockId: 'minecraft:air' }] }, 0), /non-air/i);
});

test('selects all scenarios or one exact ID', () => {
	const matrix = normalizeHeadlessMatrix({ version: 1, scenarios: [validScenario(), validScenario({ id: 'codex-move-chat' })] });
	assert.equal(selectHeadlessScenarios(matrix, null).length, 2);
	assert.equal(selectHeadlessScenarios(matrix, 'codex-move-chat')[0].id, 'codex-move-chat');
	assert.throws(() => selectHeadlessScenarios(matrix, 'missing'), /scenario|id/i);
});

test('creates immutable bounded serializable reports', () => {
	const scenario = normalizeHeadlessScenario(validScenario(), 0);
	const report = scenarioReport('PASSED', scenario, {
		elapsedMs: 12, diagnostics: 'x'.repeat(10000), assertions: [{ type: 'lifecycle', passed: true }],
	});
	assert.equal(report.status, 'PASSED');
	assert.equal(report.scenarioId, scenario.id);
	assert.ok(report.diagnostics.length <= 4096);
	assert.ok(Object.isFrozen(report));
	assert.doesNotThrow(() => JSON.stringify(report));
});

test('replaces deeply nested report values at the depth bound', () => {
	const scenario = normalizeHeadlessScenario(validScenario(), 0);
	const payload = {};
	let cursor = payload;
	for (let index = 0; index < 8; index += 1) {
		cursor.child = {};
		cursor = cursor.child;
	}
	cursor.secret = 'credential-shaped-' + 'x'.repeat(10000);
	const report = scenarioReport('PASSED', scenario, { payload });
	let bounded = report.payload;
	for (let index = 0; index < 7; index += 1) bounded = bounded.child;
	assert.equal(bounded, '[TRUNCATED]');
	assert.ok(JSON.stringify(report).length < 10000);
});

test('headless reports redact every launcher and account credential alias', () => {
	const scenario = normalizeHeadlessScenario(validScenario(), 0);
	const aliases = ['launcherAccount', 'launcher_account', 'launcher-account', 'launcheraccount', 'accountData', 'account_data', 'account-data', 'accountdata'];
	for (const [index, alias] of aliases.entries()) {
		const secret = `headless-private-value-${index}`;
		const report = scenarioReport('FAILED', scenario, { evidence: { [alias]: secret } });
		assert.equal(report.evidence[alias], '[REDACTED]', `${alias} was not redacted`);
		assert.doesNotMatch(JSON.stringify(report), new RegExp(secret));
	}
});

test('headless diagnostics redact file URIs while preserving operational text', () => {
	const scenario = normalizeHeadlessScenario(validScenario(), 0);
	for (const location of [
		'file:///C:/Users/lucas/Arena%20Agents/secret.json',
		'file:///var/lib/arena%20agents/secret.json',
		'file://server/share/Arena%20Agents/secret.json',
	]) {
		const report = scenarioReport('FAILED', scenario, { diagnostics: `failure at ${location}` });
		assert.equal(report.diagnostics.includes(location), false, `${location} leaked`);
		assert.match(report.diagnostics, /\[location redacted\]/);
	}
	const operational = 'https://example.com/file:///docs http://127.0.0.1:8766/v1/tts relative/file.txt inputTokens=4';
	assert.equal(scenarioReport('FAILED', scenario, { diagnostics: operational }).diagnostics, operational);
});

test('headless structured reports retain bounded token metrics and sanitize nested text', () => {
	const scenario = normalizeHeadlessScenario(validScenario(), 0);
	const metrics = {
		tokens: { input: 12, output: 3, reasoning: 1, cached: 2, cacheWrite: null },
		inputTokens: 12,
		output_token_count: 3,
		token_budget: 'credential-shaped-budget',
		token_latency_ms: -1,
		note: `file:///var/lib/private/${'x'.repeat(10_000)}`,
		detail: 'x'.repeat(10_000),
	};
	const report = scenarioReport('PASSED', scenario, { metrics });
	assert.deepEqual(report.metrics.tokens, metrics.tokens);
	assert.equal(report.metrics.inputTokens, 12);
	assert.equal(report.metrics.output_token_count, 3);
	assert.equal(report.metrics.token_budget, '[REDACTED]');
	assert.equal(report.metrics.token_latency_ms, '[REDACTED]');
	assert.match(report.metrics.note, /\[location redacted\]/);
	assert.ok(Buffer.byteLength(report.metrics.detail, 'utf8') <= 4096);
	assert.ok(Buffer.byteLength(JSON.stringify(report), 'utf8') < 16_384);
});
