import assert from 'node:assert/strict';
import { mkdtemp, readFile, readdir, rename as fsRename, writeFile as fsWriteFile } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import path from 'node:path';
import test from 'node:test';

import { BenchmarkRecorder, summarizeBenchmark } from '../src/benchmark/benchmark-recorder.mjs';
import { classifyBenchmarkError } from '../src/benchmark/benchmark-report.mjs';
import { AgentRegistry, DynamicAgentState } from '../src/agent-registry.mjs';
import { ProgramRuntimeManager } from '../src/program-runtime-manager.mjs';
import { AgentPlanner } from '../src/agent-planner.mjs';
import { PlanningScheduler } from '../src/planning-scheduler.mjs';
import { FakeMinecraftBridge, commandPayloads, observation } from './fixtures/fake-minecraft-bridge.mjs';
import { withCompletionContract } from './fixtures/completion-contract.mjs';

function context(overrides = {}) {
	return {
		runId: 'run-1',
		trialId: 'trial-1',
		scenarioId: 'stone-tools',
		seed: 42,
		arm: 'baseline',
		providerProfile: { provider: 'codex', model: 'gpt-5.6-sol', reasoningEffort: 'high' },
		agentId: 'agent-a',
		goalRevision: 1,
		...overrides,
	};
}

test('records immutable bounded rows with monotonic timestamps', () => {
	const times = [10, 9, 12];
	const recorder = new BenchmarkRecorder({ clock: () => times.shift(), maxEvents: 4, maxIdentityLength: 16 });
	const fields = { durationMs: 3, nested: { value: 'before' } };
	const first = recorder.record('provider_response', context(), fields);
	fields.nested.value = 'after';
	const second = recorder.record('provider_response', context({ agentId: 'agent-b' }), { durationMs: 8 });

	assert.equal(first.monotonicMs, 10);
	assert.equal(second.monotonicMs, 10, 'a regressing injected clock is clamped');
	assert.equal(first.fields.nested.value, 'before');
	assert.equal(first.agentId, 'agent-a');
	assert.throws(() => { first.fields.durationMs = 99; }, TypeError);

	const snapshot = recorder.snapshot();
	assert.equal(snapshot.length, 2);
	assert.equal(snapshot.events, snapshot, 'snapshot is directly consumable as an event list');
	assert.equal(snapshot.droppedEvents, 0);
	assert.ok(Object.isFrozen(snapshot));
});

test('bounds identities and event storage without throwing away control flow', () => {
	const recorder = new BenchmarkRecorder({ clock: () => 1, maxEvents: 2, maxIdentityLength: 8 });
	recorder.record('long-stage-name', { agentId: 'agent-abcdefghijk' }, { durationMs: 1 });
	recorder.record('second', { agentId: 'agent-b' }, { durationMs: 2 });
	const dropped = recorder.record('third', { agentId: 'agent-c' }, { durationMs: 3 });

	assert.equal(dropped, null);
	const snapshot = recorder.snapshot();
	assert.equal(snapshot.length, 2);
	assert.equal(snapshot.droppedEvents, 1);
	assert.ok(snapshot[0].stage.length <= 8);
	assert.ok(snapshot[0].agentId.length <= 8);
});

test('recursively redacts credential-shaped keys and values', () => {
	const recorder = new BenchmarkRecorder({ clock: () => 1 });
	recorder.record('provider_response', context(), {
		apiKey: 'fixture-api-key-not-real',
		nested: {
			authorization: 'Bearer fixture-bearer-not-real',
			cookie: 'session=fixture-cookie-not-real',
			note: 'token=fixture-inline-not-real',
			oauth: { accessToken: 'fixture-oauth-not-real' },
		},
	});

	const encoded = JSON.stringify(recorder.snapshot());
	for (const secret of ['fixture-api-key-not-real', 'fixture-bearer-not-real', 'fixture-cookie-not-real', 'fixture-inline-not-real', 'fixture-oauth-not-real']) {
		assert.equal(encoded.includes(secret), false, `secret ${secret} is absent`);
	}
	assert.match(encoded, /REDACTED/);
});

test('redacts credential-shaped values even when their keys are ordinary', () => {
	const recorder = new BenchmarkRecorder({ clock: () => 1 });
	recorder.record('provider_response', context(), {
		message: 'cookie=fixture_cookie_secret_not_real',
		header: 'Cookie: fixture_cookie_header_not_real',
		fish: 'sk-fish-fixture-key-not-real',
		openai: 'sk-fixture-key-not-real',
		login: 'provider-login=fixture_login_secret_not_real',
		loginText: 'provider-login fixture_login_text_secret_not_real',
		oauthText: 'OAuth token=fixture_oauth_secret_not_real',
		oauthBearerText: 'OAuth bearer fixture_oauth_bearer_secret_not_real',
		bearerText: 'Bearer fixture_bearer_secret_not_real',
	});

	const encoded = JSON.stringify(recorder.snapshot());
	for (const secret of [
		'fixture_cookie_secret_not_real',
		'fixture_cookie_header_not_real',
		'sk-fish-fixture-key-not-real',
		'sk-fixture-key-not-real',
		'fixture_login_secret_not_real',
		'fixture_login_text_secret_not_real',
		'fixture_oauth_secret_not_real',
		'fixture_oauth_bearer_secret_not_real',
		'fixture_bearer_secret_not_real',
	]) assert.equal(encoded.includes(secret), false, `credential-shaped value ${secret} is absent`);
});

test('summarizes raw duration samples with deterministic p50, p95, and p99', () => {
	const events = [1, 2, 3, 4, 5].map((durationMs, index) => ({
		stage: 'action_completion', sequence: index + 1, monotonicMs: index, durationMs,
	}));
	const summary = summarizeBenchmark(events);
	const metric = summary.stages.action_completion;
	assert.deepEqual(metric.durationSamplesMs, [1, 2, 3, 4, 5]);
	assert.equal(metric.p50Ms, 3);
	assert.equal(metric.p95Ms, 5);
	assert.equal(metric.p99Ms, 5);
	assert.equal(metric.count, 5);
	assert.equal(classifyBenchmarkError('PLANNING_TIMEOUT'), 'timeout');
	assert.equal(classifyBenchmarkError('ARENA_SCRIPT_COMPILER_EXHAUSTED'), 'compile');
});

test('writes JSONL and summary artifacts through cleaned staging files', async () => {
	const recorder = new BenchmarkRecorder({ clock: () => 7 });
	recorder.record('tick', context(), { durationMs: 50, errorCode: null });
	const directory = await mkdtemp(path.join(tmpdir(), 'benchmark-recorder-'));
	const result = await recorder.writeArtifacts(directory);
	const events = await readFile(result.eventsPath, 'utf8');
	const summary = JSON.parse(await readFile(result.summaryPath, 'utf8'));
	const names = await readdir(directory);

	assert.equal(events.trim().split('\n').length, 1);
	assert.equal(summary.eventCount, 1);
	assert.equal(summary.stages.tick.p50Ms, 50);
	assert.equal(names.some((name) => name.includes('.tmp')), false);
});

test('cleans both staging files when an atomic rename fails', async () => {
	const unlinked = [];
	const recorder = new BenchmarkRecorder({
		clock: () => 1,
		dependencies: {
			rename: async () => { throw new Error('fixture rename failure'); },
			unlink: async (filePath) => { unlinked.push(filePath); },
		},
	});
	recorder.record('tick', context(), { durationMs: 50 });
	const directory = await mkdtemp(path.join(tmpdir(), 'benchmark-recorder-failure-'));
	await assert.rejects(() => recorder.writeArtifacts(directory), /fixture rename failure/);
	assert.equal(unlinked.length, 4);
	assert.equal(unlinked.filter((filePath) => filePath.endsWith('.tmp')).length, 2);
	assert.equal(unlinked.filter((filePath) => filePath.endsWith('.bak')).length, 2);
});

test('restores the exact prior artifact pair when the second publish rename fails', async () => {
	const directory = await mkdtemp(path.join(tmpdir(), 'benchmark-recorder-transaction-'));
	const eventsPath = path.join(directory, 'benchmark-events.jsonl');
	const summaryPath = path.join(directory, 'benchmark-summary.json');
	const oldEvents = '{"old":"events"}\n';
	const oldSummary = '{"old":"summary"}\n';
	await fsWriteFile(eventsPath, oldEvents, 'utf8');
	await fsWriteFile(summaryPath, oldSummary, 'utf8');
	let publishSummaryAttempts = 0;
	const recorder = new BenchmarkRecorder({
		clock: () => 1,
		dependencies: {
			rename: async (from, to) => {
				if (to === summaryPath && from.endsWith('.tmp')) {
					publishSummaryAttempts += 1;
					throw new Error('fixture second publish failure');
				}
				return fsRename(from, to);
			},
		},
	});
	recorder.record('tick', context(), { durationMs: 50 });

	await assert.rejects(() => recorder.writeArtifacts(directory), /fixture second publish failure/);
	assert.equal(publishSummaryAttempts, 1);
	assert.equal(await readFile(eventsPath, 'utf8'), oldEvents);
	assert.equal(await readFile(summaryPath, 'utf8'), oldSummary);
	assert.deepEqual((await readdir(directory)).filter((name) => name.includes('.tmp') || name.includes('.bak')), []);
});

test('a throwing recorder never changes planner, scheduler, or runtime behavior', async () => {
	const recorder = { record() { throw new Error('telemetry fixture failure'); } };
	const scheduler = new PlanningScheduler({ maxConcurrent: 1, maxPending: 0, recorder });
	const registry = new AgentRegistry();
	const record = { agentId: 'agent-a', provider: 'codex', model: 'gpt-5.6-sol', reasoningEffort: 'high', state: DynamicAgentState.STARTING, goalRevision: 1, currentGoal: 'wait', queue: [] };
	registry.register(record);
	const planner = new AgentPlanner({
		registry,
		scheduler,
		recorder,
		codexService: {
			async createAgent() {
				return {
					async setGoalRevision() {},
					async decide() { return { directive: 'continue', summary: 'continue' }; },
				};
			},
		},
	});
	assert.deepEqual(await planner.requestPlan({ agentId: 'agent-a', input: 'fixture', goalRevision: 1 }), { directive: 'continue', summary: 'continue', goalRevision: 1 });

	const sent = [];
	const runtime = new ProgramRuntimeManager({
		registry,
		recorder,
		bridge: { send: async (...args) => sent.push(args) },
		planner: { requestPlan: async () => ({ directive: 'continue' }) },
	});
	await runtime.installDecision(record, withCompletionContract({ directive: 'replace', source: 'program.onUnhandledAttention("continue_and_notify"); await player.wait(1);' }), { observation: observation(), eventSequence: 1 });
	assert.equal(sent.length, 1);
	scheduler.close();
});

test('enabled telemetry does not alter fixture action command bytes', async () => {
	const run = async (recorder) => {
		const registry = new AgentRegistry();
		const record = { agentId: 'agent-a', provider: 'codex', model: 'gpt-5.6-sol', reasoningEffort: 'high', state: DynamicAgentState.STARTING, goalRevision: 1, currentGoal: 'wait', queue: [] };
		registry.register(record);
		const bridge = new FakeMinecraftBridge({ record, recorder });
		const manager = new ProgramRuntimeManager({
			sessionId: 'fixture-telemetry-action-bytes',
			registry,
			bridge,
			planner: { requestPlan: async () => ({ directive: 'continue' }) },
			recorder,
		});
		bridge.attach(manager);
		await manager.installDecision(record, withCompletionContract({ directive: 'replace', source: 'program.onUnhandledAttention("continue_and_notify"); await player.wait(1); await player.wait(2);' }), { observation: observation(), eventSequence: 1 });
		await manager.onActionResult(record, { actionId: bridge.sent[0].payload.actionId, state: 'SUCCEEDED', reasonCode: 'DONE' });
		await new Promise((resolve) => setImmediate(resolve));
		return { bytes: JSON.stringify(commandPayloads(bridge)), events: recorder?.snapshot() ?? [] };
	};

	const disabled = await run(null);
	const enabled = await run(new BenchmarkRecorder({ clock: () => 1 }));
	assert.equal(enabled.bytes, disabled.bytes);
	assert.ok(enabled.events.length > 0);
});
