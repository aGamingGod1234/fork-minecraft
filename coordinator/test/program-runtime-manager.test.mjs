import assert from 'node:assert/strict';
import test from 'node:test';

import { AgentRegistry, DynamicAgentState } from '../src/agent-registry.mjs';
import { ControlLatencyRegistry } from '../src/control-latency-registry.mjs';
import { ProgramRuntimeManager as ProductionProgramRuntimeManager } from '../src/program-runtime-manager.mjs';
import { PlanningScheduler } from '../src/planning-scheduler.mjs';
import { validateProtocolV2Payload } from '../src/protocol-v2.mjs';
import { goalSpecFingerprint } from '../src/goal-spec.mjs';
import { withCompletionContract } from './fixtures/completion-contract.mjs';

class ProgramRuntimeManager extends ProductionProgramRuntimeManager {
	installDecision(record, decision, context) {
		return super.installDecision(record, withCompletionContract(decision, record.goalRevision), context);
	}
}

const SOURCE = 'program.onUnhandledAttention("continue_and_notify"); await player.wait(1); await player.wait(2);';
const DEATH = Object.freeze({
	cause: 'fell from a high place', dimensionId: 'minecraft:overworld', x: 0, y: 64, z: 0,
	respawnDimensionId: 'minecraft:overworld', respawnX: 100.5, respawnY: 70, respawnZ: -20.5,
	respawnYaw: 37.5, respawnPitch: -12.25, respawnForced: true, gameMode: 'survival', diedAtEpochMs: 2,
});

function record(agentId = 'agent-a') {
	const fields = { originalRequest: 'wait', predicate: { type: 'operator_confirmed' }, createdAtTick: 1 };
	return { agentId, provider: 'codex', model: 'gpt-5.6-sol', reasoningEffort: 'high', serviceTier: 'fast', state: DynamicAgentState.STARTING, goalRevision: 1, currentGoal: 'wait', currentGoalSpec: { ...fields, fingerprint: goalSpecFingerprint(fields) }, queue: [] };
}

function observation(overrides = {}) {
	return { player: { x: 0, y: 64, z: 0, health: 20 }, items: [], entities: [], blocks: [], inventory: { items: [], tagCounts: {} }, ...overrides };
}

function actionCommands(messages) {
	return messages.filter((message) => message.type === 'action_command');
}

function harness(options = {}) {
	const registry = new AgentRegistry();
	registry.register(record());
	const sent = [];
	const requests = [];
	const errors = [];
	let manager;
	const completionRequests = [];
	manager = new ProgramRuntimeManager({
		registry,
		bridge: { send: options.bridgeSend ?? (async (type, agentId, payload) => sent.push({ type, agentId, payload })) },
		planner: { requestPlan: async (request) => { requests.push(request); return withCompletionContract({ summary: 'Continue.', directive: 'continue' }, request.goalRevision); } },
		reportError: (agentId, error) => errors.push({ agentId, error }),
		onCompleted: options.onCompleted,
		onCompletionRequested: options.onCompletionRequested ?? ((request) => {
			completionRequests.push(request);
			queueMicrotask(() => manager.onCompletionResult(registry.get(request.record.agentId), {
				goalRevision: request.record.goalRevision,
				traceId: request.traceId,
				goalFingerprint: request.goalFingerprint,
				facts: [],
				verified: true,
				reasonCode: 'COMPLETION_VERIFIED',
			}));
		}),
		benchmarkRecorder: options.benchmarkRecorder,
		latencyRegistry: options.latencyRegistry,
		completionRetryDelayMs: options.completionRetryDelayMs,
		completionRetryLimit: options.completionRetryLimit,
		setTimeoutFn: options.setTimeoutFn,
		clearTimeoutFn: options.clearTimeoutFn,
		inspectObservation: options.inspectObservation,
		memoryOperation: options.memoryOperation,
		sessionId: options.sessionId,
	});
	const installDecision = manager.installDecision.bind(manager);
	manager.installDecision = (target, decision, context) => installDecision(target, withCompletionContract(decision, target.goalRevision), context);
	return { manager, registry, sent, requests, completionRequests, errors };
}

test('inspection uses the injected read-only broker and returns exact menu state to model-authored actions', async () => {
	const inspected = [];
	const run = harness({ inspectObservation: async (record, query) => {
		inspected.push({ record, query });
		return { state: 'SUCCEEDED', reasonCode: 'INSPECTED', menu: { menuId: 'minecraft:generic_9x3', containerId: 4, stateId: 8 } };
	} });
	await run.manager.installDecision(run.registry.get('agent-a'), { directive: 'replace', source: `
		program.onUnhandledAttention("continue_and_notify");
		const page = await world.inspect({ section: "menu", offset: 0, limit: 16 });
		await player.menuClose({ menuId: page.menu.menuId, containerId: page.menu.containerId, stateId: page.menu.stateId });
	` }, { observation: observation(), eventSequence: 1 });
	await new Promise((resolve) => setImmediate(resolve));
	assert.equal(inspected.length, 1);
	assert.equal(inspected[0].record.provider, 'codex');
	assert.deepEqual({ ...inspected[0].query }, { section: 'menu', offset: 0, limit: 16 });
	assert.equal(actionCommands(run.sent).length, 1);
	assert.equal(actionCommands(run.sent)[0].payload.actionType, 'menu_close');
	assert.equal(actionCommands(run.sent)[0].payload.arguments.stateId, 8);
	assert.deepEqual(run.errors, []);
});

test('failed and disposed inspection requests never synthesize fallback actions', async () => {
	const run = harness({ inspectObservation: async () => { throw Object.assign(new Error('unavailable'), { code: 'INSPECTION_TIMEOUT' }); } });
	await run.manager.installDecision(run.registry.get('agent-a'), { directive: 'replace', source: `
		program.onUnhandledAttention("continue_and_notify");
		const result = await world.inspect({ section: "inventory" });
		if (result.state === "FAILED") program.checkpoint(result.reasonCode);
		await player.wait(1);
	` }, { observation: observation(), eventSequence: 1 });
	await new Promise((resolve) => setImmediate(resolve));
	assert.equal(actionCommands(run.sent).length, 0);
	let release;
	const disposed = harness({ inspectObservation: () => new Promise((resolve) => { release = resolve; }) });
	await disposed.manager.installDecision(disposed.registry.get('agent-a'), { directive: 'replace', source: 'program.onUnhandledAttention("continue_and_notify"); await world.inspect({ section: "inventory" }); await player.wait(1);' }, { observation: observation(), eventSequence: 1 });
	disposed.manager.dispose('agent-a');
	release({ state: 'SUCCEEDED', entries: [] });
	await new Promise((resolve) => setImmediate(resolve));
	assert.equal(actionCommands(disposed.sent).length, 0);
	assert.equal(disposed.sent.some((message) => message.type === 'action_cancel'), false);
});

test('focused entity inspection retains its causal target authority while newer compact observations arrive', async () => {
	const uuid = '11111111-1111-1111-1111-111111111111';
	let release;
	const run = harness({ inspectObservation: () => new Promise((resolve) => { release = resolve; }) });
	await run.manager.installDecision(run.registry.get('agent-a'), { directive: 'replace', source: `
		program.onUnhandledAttention("continue_and_notify");
		const page = await world.inspect({ section: "entities", offset: 16, limit: 16 });
		let target = null;
		for (const candidate of page.entries) target = candidate;
		await player.attack({ targetId: target.uuid, timeoutMs: 1000 });
	` }, { observation: observation(), eventSequence: 1 });
	await run.manager.onObservation(run.registry.get('agent-a'), { observation: observation(), eventSequence: 3 });
	release({ section: 'entities', state: 'SUCCEEDED', reasonCode: 'INSPECTED', eventSequence: 2, entries: [{ uuid, type: 'minecraft:zombie' }] });
	await new Promise((resolve) => setImmediate(resolve));
	const action = actionCommands(run.sent)[0];
	assert.equal(action.payload.arguments.targetId, uuid);
	assert.equal(action.payload.provenance.eventSequence, 2);
	assert.equal(action.payload.provenance.provider, 'codex');
	assert.match(action.payload.provenance.sourceStepId, /^step-/);
	assert.deepEqual(run.errors, []);
});

test('wire action ids are bounded and unique across fresh coordinator runtimes', async () => {
	const first = harness(), second = harness();
	for (const run of [first, second]) await run.manager.installDecision(run.registry.get('agent-a'), { directive: 'replace', source: SOURCE }, { observation: observation(), eventSequence: 1 });
	const one = actionCommands(first.sent)[0].payload.actionId;
	const two = actionCommands(second.sent)[0].payload.actionId;
	assert.notEqual(one, two);
	assert.match(one, /^program:[a-f0-9-]{36}:[a-f0-9]{64}$/);
	assert.ok(one.length <= 256);
	const replayOne = harness({ sessionId: 'synthetic-replay-1' });
	const replayTwo = harness({ sessionId: 'synthetic-replay-1' });
	for (const run of [replayOne, replayTwo]) await run.manager.installDecision(run.registry.get('agent-a'), { directive: 'replace', source: SOURCE }, { observation: observation(), eventSequence: 1 });
	assert.equal(actionCommands(replayOne.sent)[0].payload.actionId, actionCommands(replayTwo.sent)[0].payload.actionId);
	assert.throws(() => harness({ sessionId: 'unsafe id' }), /sessionId/);
});

test('model notebook calls share bounded validation and carry the exact author without changing world state', async () => {
	const calls = [];
	const run = harness({ memoryOperation: async (record, request) => {
		calls.push({ record, request });
		return request.operation === 'write' ? { state: 'SUCCEEDED', reasonCode: 'NOTE_SAVED' }
			: { state: 'SUCCEEDED', entries: [{ source: 'model_authored', key: 'observation-plan', text: 'Inspect the nearby container.' }] };
	} });
	await run.manager.installDecision(run.registry.get('agent-a'), { directive: 'replace', source: `
		program.onUnhandledAttention("continue_and_notify");
		await world.remember({ key: "observation-plan", text: "Inspect the nearby container." });
		const saved = await world.queryMemory({ kind: "notes", limit: 4, offset: 8 });
		if (saved.entries.length > 0) program.checkpoint("notes available");
	` }, { observation: observation(), eventSequence: 1 });
	await new Promise((resolve) => setImmediate(resolve));
	assert.equal(calls.length, 2);
	assert.equal(calls[0].request.operation, 'write');
	assert.deepEqual(calls[0].request.arguments, { key: 'observation-plan', text: 'Inspect the nearby container.' });
	assert.equal(calls[0].request.provenance.model, 'gpt-5.6-sol');
	assert.equal(calls[0].request.provenance.provider, 'codex');
	assert.equal(calls[0].request.provenance.goalRevision, 1);
	assert.match(calls[0].request.provenance.sourceStepId, /^step-/);
	assert.equal(calls[1].request.arguments.kind, 'notes');
	assert.equal(calls[1].request.arguments.offset, 8);
	assert.equal(actionCommands(run.sent).length, 0);
	assert.deepEqual(run.errors, []);
});

test('invalid memory requests return factual errors before entering the notebook', async () => {
	let calls = 0;
	const run = harness({ memoryOperation: async () => { calls += 1; return { state: 'SUCCEEDED' }; } });
	await run.manager.installDecision(run.registry.get('agent-a'), { directive: 'replace', source: `
		program.onUnhandledAttention("continue_and_notify");
		const result = await world.queryMemory({ kind: "hidden-world", limit: 1000 });
		if (result.state === "FAILED") program.checkpoint(result.reasonCode);
	` }, { observation: observation(), eventSequence: 1 });
	await new Promise((resolve) => setImmediate(resolve));
	assert.equal(calls, 0);
	assert.equal(actionCommands(run.sent).length, 0);
});

test('invalid broker results release the query with a factual failure for the authored continuation', async () => {
	const run = harness({ inspectObservation: async () => ({ menu: { stateId: 1 } }) });
	await run.manager.installDecision(run.registry.get('agent-a'), { directive: 'replace', source: `
		program.onUnhandledAttention("continue_and_notify");
		const result = await world.inspect({ section: "menu" });
		if (result.reasonCode === "INVALID_QUERY_RESULT") program.checkpoint(result.reasonCode);
		await player.wait(1);
	` }, { observation: observation(), eventSequence: 1 });
	await new Promise((resolve) => setImmediate(resolve));
	assert.equal(actionCommands(run.sent).length, 0);
	assert.equal(run.registry.get('agent-a').state, DynamicAgentState.PAUSED);
	assert.deepEqual(run.errors, []);
});

test('retries a completion publication that was temporarily unavailable', async () => {
	let attempts = 0;
	const run = harness({
		completionRetryDelayMs: 1,
		onCompletionRequested: (request) => {
			attempts += 1;
			if (attempts === 1) throw Object.assign(new Error('bridge unavailable'), { code: 'BRIDGE_NOT_READY' });
			queueMicrotask(() => run.manager.onCompletionResult(run.registry.get(request.record.agentId), {
				goalRevision: request.record.goalRevision,
				traceId: request.traceId,
				goalFingerprint: request.goalFingerprint,
				facts: [],
				verified: true,
				reasonCode: 'COMPLETION_VERIFIED',
			}));
		},
	});
	await run.manager.installDecision(run.registry.get('agent-a'), {
		summary: 'Done.', directive: 'replace', source: 'program.onUnhandledAttention("continue_and_notify"); program.finish("done");',
	}, { observation: observation(), eventSequence: 1 });
	await new Promise((resolve) => setTimeout(resolve, 20));
	assert.equal(attempts, 2);
	assert.equal(run.errors.length, 0, 'a retryable bridge outage must not publish an agent failure');
	assert.equal(run.registry.get('agent-a').state, DynamicAgentState.COMPLETED);
});

test('routes failed factual completion back through the selected brain', async () => {
	let completionRequest;
	const run = harness({ onCompletionRequested: (request) => { completionRequest = request; } });
	await run.manager.installDecision(run.registry.get('agent-a'), {
		summary: 'Done.', directive: 'replace', source: 'program.onUnhandledAttention("continue_and_notify"); program.finish("done");',
	}, { observation: observation(), eventSequence: 1 });
	assert.equal(run.manager.onCompletionResult(run.registry.get('agent-a'), {
		goalRevision: 1,
		traceId: completionRequest.traceId,
		goalFingerprint: completionRequest.goalFingerprint,
		verified: false,
		reasonCode: 'INVENTORY_MISSING',
		facts: [
			{ type: 'inventory_contains', satisfied: false, expectedValue: 'minecraft:iron_pickaxe x1', observedValue: 'minecraft:iron_pickaxe x0' },
			{ type: 'position_within', satisfied: true, expectedValue: '0,64,0 radius=2', observedValue: '0,64,1.25 stableTicks=2' },
		],
	}), true);
	await new Promise((resolve) => setImmediate(resolve));
	assert.equal(run.requests.length, 1);
	assert.match(run.requests[0].input, /"decisionContext":"completion_verification_failed"/);
	assert.match(run.requests[0].input, /"reasonCode":"INVENTORY_MISSING"/);
	assert.match(run.requests[0].input, /"expectedValue":"minecraft:iron_pickaxe x1"/);
	assert.match(run.requests[0].input, /"observedValue":"minecraft:iron_pickaxe x0"/);
});

test('completion verification infrastructure failure stays active and retries once from fresh facts', async () => {
	let completionRequest;
	const registry = new AgentRegistry();
	registry.register(record());
	const errors = [];
	const recoveries = [];
	const requests = [];
	const manager = new ProgramRuntimeManager({
		registry,
		bridge: { send: async () => {} },
		planner: { requestPlan: async (request) => {
			requests.push(request);
			if (requests.length === 1) throw Object.assign(new Error('provider timed out'), { code: 'REQUEST_TIMEOUT' });
			return withCompletionContract({ summary: 'Continue from verified facts.', directive: 'replace', source: SOURCE }, request.goalRevision);
		} },
		reportError: (_agentId, error) => errors.push(error),
		requestRecovery: (request) => recoveries.push(request),
		onCompletionRequested: (request) => { completionRequest = request; },
	});
	await manager.installDecision(registry.get('agent-a'), withCompletionContract({
		summary: 'Done.', directive: 'replace', source: 'program.onUnhandledAttention("continue_and_notify"); program.finish("done");',
	}, 1), { observation: observation(), eventSequence: 1 });
	manager.onCompletionResult(registry.get('agent-a'), {
		goalRevision: 1,
		traceId: completionRequest.traceId,
		goalFingerprint: completionRequest.goalFingerprint,
		verified: false,
		reasonCode: 'PREDICATE_FAILED',
		facts: [{ type: 'inventory_contains', satisfied: false, expectedValue: 'minecraft:iron_pickaxe x1', observedValue: 'minecraft:iron_pickaxe x0' }],
	});
	await new Promise((resolve) => setImmediate(resolve));
	assert.equal(registry.get('agent-a').state, DynamicAgentState.ACTING);
	assert.equal(recoveries.length, 1);
	assert.equal(errors.length, 0);
	await manager.onObservation(registry.get('agent-a'), { observation: observation({ player: { health: 19 } }), eventSequence: 2 });
	for (let attempt = 0; attempt < 10 && requests.length < 2; attempt += 1) await new Promise((resolve) => setImmediate(resolve));
	assert.equal(requests.length, 2);
	assert.equal(recoveries.length, 1);
	assert.equal(registry.get('agent-a').state, DynamicAgentState.ACTING);
});

test('a bounded completion correction rejection cannot turn an unverified goal into ERROR', async () => {
	let completionRequest;
	const registry = new AgentRegistry();
	registry.register(record());
	const errors = [];
	const manager = new ProgramRuntimeManager({
		registry,
		bridge: { send: async () => {} },
		planner: { requestPlan: async () => { throw Object.assign(new Error('unsupported correction protocol'), { code: 'UNSUPPORTED_PROTOCOL' }); } },
		reportError: (_agentId, error) => errors.push(error),
		onCompletionRequested: (request) => { completionRequest = request; },
	});
	await manager.installDecision(registry.get('agent-a'), withCompletionContract({
		summary: 'Done.', directive: 'replace', source: 'program.onUnhandledAttention("continue_and_notify"); program.finish("done");',
	}, 1), { observation: observation(), eventSequence: 1 });
	manager.onCompletionResult(registry.get('agent-a'), {
		goalRevision: 1,
		traceId: completionRequest.traceId,
		goalFingerprint: completionRequest.goalFingerprint,
		verified: false,
		reasonCode: 'PREDICATE_FAILED',
		facts: [{ type: 'inventory_contains', satisfied: false, expectedValue: 'minecraft:iron_pickaxe x1', observedValue: 'minecraft:iron_pickaxe x0' }],
	});
	for (let attempt = 0; attempt < 10 && errors.length === 0; attempt += 1) await new Promise((resolve) => setImmediate(resolve));
	assert.equal(errors.at(-1)?.code, 'COMPLETION_CORRECTION_FAILED');
	assert.notEqual(registry.get('agent-a').state, DynamicAgentState.ERROR);
	assert.notEqual(registry.get('agent-a').state, DynamicAgentState.PAUSED);
});

test('clears a scheduled completion retry before a newer publication and correction', async () => {
	const timers = [];
	let attempts = 0;
	let latestRequest;
	const run = harness({
		completionRetryDelayMs: 1_000,
		setTimeoutFn: (callback) => {
			const timer = { callback, cleared: false, unref() {} };
			timers.push(timer);
			return timer;
		},
		clearTimeoutFn: (timer) => { timer.cleared = true; },
		onCompletionRequested: (request) => {
			attempts += 1;
			if (attempts === 1) throw Object.assign(new Error('bridge unavailable'), { code: 'BRIDGE_NOT_READY' });
			latestRequest = request;
		},
	});
	await run.manager.installDecision(run.registry.get('agent-a'), {
		summary: 'Done.', directive: 'replace', source: 'program.onUnhandledAttention("continue_and_notify"); program.finish("done");',
	}, { observation: observation(), eventSequence: 1 });
	await new Promise((resolve) => setImmediate(resolve));
	assert.equal(timers.length, 1);
	await run.manager.onObservation(run.registry.get('agent-a'), { observation: observation(), eventSequence: 2 });
	assert.equal(attempts, 2);
	assert.equal(timers[0].cleared, true, 'a fresh publication cancels its obsolete retry');
	assert.equal(run.manager.onCompletionResult(run.registry.get('agent-a'), {
		goalRevision: 1,
		traceId: latestRequest.traceId,
		goalFingerprint: latestRequest.goalFingerprint,
		verified: false,
		reasonCode: 'PREDICATE_FAILED',
	}), true);
	assert.equal(attempts, 2, 'correction does not republish the rejected contract');
});

test('reports a permanent completion protocol rejection without retrying forever', async () => {
	const timers = [];
	let attempts = 0;
	const run = harness({
		setTimeoutFn: (callback) => { timers.push(callback); return { unref() {} }; },
		clearTimeoutFn() {},
		onCompletionRequested: () => {
			attempts += 1;
			throw Object.assign(new Error('invalid completion payload'), {
				code: 'COMPLETION_SEND_FAILED',
				cause: Object.assign(new Error('invalid payload'), { code: 'INVALID_PAYLOAD' }),
			});
		},
	});
	await run.manager.installDecision(run.registry.get('agent-a'), {
		summary: 'Done.', directive: 'replace', source: 'program.onUnhandledAttention("continue_and_notify"); program.finish("done");',
	}, { observation: observation(), eventSequence: 1 });
	await new Promise((resolve) => setImmediate(resolve));
	assert.equal(timers.length, 0);
	assert.equal(run.errors.length, 1);
	assert.equal(run.errors[0].error.code, 'COMPLETION_SEND_FAILED');
	await run.manager.onObservation(run.registry.get('agent-a'), { observation: observation(), eventSequence: 2 });
	await new Promise((resolve) => setImmediate(resolve));
	assert.equal(attempts, 1, 'fresh facts do not retry a permanent protocol rejection');
});

test('caps retryable completion publication attempts', async () => {
	const timers = [];
	let attempts = 0;
	const run = harness({
		completionRetryDelayMs: 1_000,
		completionRetryLimit: 2,
		setTimeoutFn: (callback) => {
			const timer = { callback, unref() {} };
			timers.push(timer);
			return timer;
		},
		clearTimeoutFn() {},
		onCompletionRequested: () => {
			attempts += 1;
			throw Object.assign(new Error('bridge unavailable'), { code: 'BRIDGE_NOT_READY' });
		},
	});
	await run.manager.installDecision(run.registry.get('agent-a'), {
		directive: 'replace', source: 'program.onUnhandledAttention("continue_and_notify"); program.finish("done");',
	}, { observation: observation(), eventSequence: 1 });
	await new Promise((resolve) => setImmediate(resolve));
	assert.equal(timers.length, 1);
	timers[0].callback();
	await new Promise((resolve) => setImmediate(resolve));
	assert.equal(timers.length, 2);
	timers[1].callback();
	await new Promise((resolve) => setImmediate(resolve));
	assert.equal(attempts, 3, 'the initial publication receives only two retries');
	assert.equal(timers.length, 2, 'exhaustion cannot schedule another timer');
	assert.equal(run.errors.at(-1)?.error.code, 'COMPLETION_PUBLICATION_EXHAUSTED');
});

test('fresh authoritative facts rearm completion publication after retry exhaustion', async () => {
	const timers = [];
	let attempts = 0;
	let recovered = false;
	const run = harness({
		completionRetryDelayMs: 1_000,
		completionRetryLimit: 1,
		setTimeoutFn: (callback) => {
			const timer = { callback, unref() {} };
			timers.push(timer);
			return timer;
		},
		clearTimeoutFn() {},
		onCompletionRequested: () => {
			attempts += 1;
			if (!recovered) throw Object.assign(new Error('bridge unavailable'), { code: 'BRIDGE_NOT_READY' });
		},
	});
	await run.manager.installDecision(run.registry.get('agent-a'), {
		directive: 'replace', source: 'program.onUnhandledAttention("continue_and_notify"); program.finish("done");',
	}, { observation: observation(), eventSequence: 1 });
	await new Promise((resolve) => setImmediate(resolve));
	timers[0].callback();
	await new Promise((resolve) => setImmediate(resolve));
	assert.equal(attempts, 2);
	assert.equal(timers.length, 1);
	await run.manager.onObservation(run.registry.get('agent-a'), { observation: observation(), eventSequence: 1 });
	assert.equal(attempts, 2, 'duplicate facts cannot rearm exhausted publication');
	recovered = true;
	await run.manager.onObservation(run.registry.get('agent-a'), { observation: observation(), eventSequence: 2 });
	await new Promise((resolve) => setImmediate(resolve));
	assert.equal(attempts, 3, 'one fresh observation starts one new publication attempt');
	assert.equal(timers.length, 1, 'successful rearm does not create another retry timer');
});

test('retains the planning trace through factual completion verification', async () => {
	const rows = [];
	const run = harness({ benchmarkRecorder: { record(stage, context, fields) { rows.push({ stage, context, fields }); } } });
	const traceId = 'trace-runtime-1';
	const decision = {
		summary: 'Wait once.', directive: 'replace', traceId,
		source: 'program.onUnhandledAttention("continue_and_notify"); await player.wait(1); program.finish("done");',
	};
	await run.manager.installDecision(run.registry.get('agent-a'), decision, { observation: observation(), eventSequence: 1 });
	const command = run.sent.find((message) => message.type === 'action_command');
	assert.equal(command.payload.traceId, traceId);
	assert.equal(command.payload.provenance.traceId, traceId);
	await run.manager.onActionProgress(run.registry.get('agent-a'), {
		traceId, actionId: command.payload.actionId, eventSequence: 2, state: 'RUNNING', progress: 0.1,
	});
	await run.manager.onActionResult(run.registry.get('agent-a'), {
		traceId, actionId: command.payload.actionId, eventSequence: 3, state: 'SUCCEEDED', reasonCode: 'DONE',
	});
	await run.manager.onObservation(run.registry.get('agent-a'), {
		observation: observation(), eventSequence: 3, attention: false,
	});
	await new Promise((resolve) => setImmediate(resolve));
	assert.equal(run.completionRequests.length, 1, 'finished program must request factual completion verification');
	assert.equal(rows.every((row) => row.context.traceId === traceId), true);
	assert.equal(rows.some((row) => row.stage === 'first_command_dispatch'), true);
	assert.equal(rows.some((row) => row.stage === 'first_world_action'), true);
	const verification = rows.find((row) => row.stage === 'completion_verification');
	assert.equal(verification?.fields.outcome, 'completed');
	assert.equal(Object.hasOwn(verification?.fields ?? {}, 'retryReason'), false);
	assert.equal(rows.some((row) => row.stage === 'completion_verification' && row.fields.outcome === 'skipped'), false);
});

test('bridge pre-execution rejection does not count as first world action', async () => {
	const rows = [];
	const latencyRegistry = new ControlLatencyRegistry();
	const run = harness({ latencyRegistry, benchmarkRecorder: { record(stage, context, fields) { rows.push({ stage, context, fields }); } } });
	const traceId = 'trace-rejected-before-execution';
	await run.manager.installDecision(run.registry.get('agent-a'), {
		summary: 'Wait once.', directive: 'replace', traceId,
		source: 'program.onUnhandledAttention("continue_and_notify"); await player.wait(1);',
	}, { observation: observation(), eventSequence: 1 });
	const command = run.sent.find((message) => message.type === 'action_command');
	await run.manager.onActionResult(run.registry.get('agent-a'), {
		traceId, actionId: command.payload.actionId, eventSequence: 2, state: 'FAILED', reasonCode: 'STALE_REVISION',
		executionStarted: false, physicalAttempted: false,
	});
	assert.equal(rows.some((row) => row.stage === 'first_world_action'), false);
	assert.equal(latencyRegistry.completeTrace(traceId).phases.some((phase) => phase.phase === 'first_world_action'), false);
});

test('installs a model-authored program and dispatches its next primitive without a provider turn', async () => {
	const run = harness();
	await run.manager.installDecision(run.registry.get('agent-a'), { summary: 'Wait twice.', directive: 'replace', source: SOURCE }, { observation: observation(), eventSequence: 1 });
	const first = actionCommands(run.sent)[0];
	assert.equal(actionCommands(run.sent).length, 1);
	assert.equal(first.payload.actionType, 'wait');
	assert.deepEqual(first.payload.arguments, { durationMs: 1 });
	assert.equal(first.payload.provenance.programId, 'program-1-1');
	const wire = validateProtocolV2Payload('action_command', first.payload);
	assert.deepEqual(wire.provenance, { ...first.payload.provenance });
	assert.match(wire.provenance.sourceStepId, /^step-\d+-\d+$/);
	await run.manager.onActionResult(run.registry.get('agent-a'), { actionId: first.payload.actionId, state: 'SUCCEEDED', reasonCode: 'DONE' });
	await run.manager.onObservation(run.registry.get('agent-a'), { observation: observation(), eventSequence: 2 });
	assert.equal(actionCommands(run.sent).length, 2);
	assert.equal(run.requests.length, 0, 'pre-authored continuation must not call the provider');
});

test('ArenaScript combined controls reach the wire and hold the body until completion', async () => {
	const frame = {
		forward: 1, strafe: -0.5, jump: true, sneak: false, sprint: true,
		attack: false, use: true, yaw: 90, pitch: -15, selectedSlot: 2, hand: 'off', ticks: 20,
	};
	for (const provider of ['kimi', 'cursor']) {
		const run = harness();
		const agent = run.registry.get('agent-a');
		agent.provider = provider;
		await run.manager.installDecision(agent, {
			summary: 'Move, jump and use the offhand together.', directive: 'replace',
			source: `program.onUnhandledAttention("continue_and_notify"); await player.control(${JSON.stringify(frame)}); await player.wait(1);`,
		}, { observation: observation(), eventSequence: 1 });
		assert.equal(actionCommands(run.sent).length, 1, `${provider} must dispatch one complete frame`);
		const first = actionCommands(run.sent)[0];
		const wire = validateProtocolV2Payload('action_command', first.payload);
		assert.equal(wire.actionType, 'control');
		assert.deepEqual(wire.arguments, frame);
		assert.match(wire.provenance.sourceStepId, /^step-\d+-\d+$/);
		await run.manager.onObservation(agent, { observation: observation(), eventSequence: 2 });
		assert.equal(actionCommands(run.sent).length, 1, 'a fresh observation cannot overlap body actions');
		await run.manager.onActionResult(agent, { actionId: first.payload.actionId, state: 'SUCCEEDED', reasonCode: 'DONE' });
		await run.manager.onObservation(agent, { observation: observation(), eventSequence: 3 });
		assert.equal(actionCommands(run.sent).length, 2);
		assert.equal(actionCommands(run.sent)[1].payload.actionType, 'wait');
		assert.equal(run.requests.length, 0, 'authored continuation must not spend another model turn');
		assert.equal(run.errors.length, 0);
	}
});

test('scripted control frames are validated at the production protocol boundary', async () => {
	const frame = {
		forward: 1, strafe: 0, jump: false, sneak: false, sprint: true,
		attack: false, use: false, yaw: 0, pitch: 0, selectedSlot: 0, hand: 'main', ticks: 20,
	};
	for (const invalidFrame of [{ ...frame, ticks: 201 }, { ...frame, hand: 'both' }, { ...frame, type: 'wait' }, { forward: 1 }]) {
		const dispatched = [];
		const run = harness({ bridgeSend: async (type, agentId, payload) => {
			if (type === 'action_command') dispatched.push(validateProtocolV2Payload(type, payload));
		} });
		await run.manager.installDecision(run.registry.get('agent-a'), {
			summary: 'Control.', directive: 'replace',
			source: `program.onUnhandledAttention("continue_and_notify"); await player.control(${JSON.stringify(invalidFrame)});`,
		}, { observation: observation(), eventSequence: 1 });
		assert.equal(dispatched.length, 0, 'malformed frames must not reach Minecraft');
		assert.equal(run.errors.length, 1);
		assert.match(run.errors[0].error.code, /^INVALID_(ACTION|PAYLOAD_FIELD)$/);
	}
});

test('corrects an acknowledgement-only program before dispatching it for a physical goal', async () => {
	const registry = new AgentRegistry();
	const goalFields = { originalRequest: 'Get an iron pickaxe', predicate: { type: 'inventory_contains', itemId: 'minecraft:iron_pickaxe', count: 1 }, createdAtTick: 1 };
	registry.register({ ...record(), currentGoal: goalFields.originalRequest, currentGoalSpec: { ...goalFields, fingerprint: goalSpecFingerprint(goalFields) } });
	const sent = [];
	const requests = [];
	const errors = [];
	const manager = new ProductionProgramRuntimeManager({
		registry,
		bridge: { send: async (type, agentId, payload) => sent.push({ type, agentId, payload }) },
		planner: { requestPlan: async (request) => {
			requests.push(request);
			return {
				summary: 'Begin gathering materials.',
				directive: 'replace',
			source: 'program.onUnhandledAttention("continue_and_notify"); await player.mine({ x: 1, y: 64, z: 1, expectedBlockId: "minecraft:stone", timeoutMs: 30000 });',
			};
		} },
		reportError: (_agentId, error) => errors.push(error),
	});

	await manager.installDecision(registry.get('agent-a'), {
		summary: "I'm on it.",
		directive: 'replace',
		source: 'program.onUnhandledAttention("continue_and_notify"); await player.chat({ message: "I am on it.", audience: "proximity" });',
	}, { observation: observation(), eventSequence: 1 });

	assert.equal(requests.length, 1, 'the selected model receives one correction request');
	assert.match(requests[0].input, /ACKNOWLEDGEMENT_ONLY_PROGRAM/);
	assert.deepEqual(actionCommands(sent).map((message) => message.payload.actionType), ['break_block']);
	assert.equal(errors.length, 0);
});

test('keeps the agent acting while a successful exhausted program is replaced', async () => {
	const registry = new AgentRegistry();
	registry.register(record());
	const sent = [];
	const requests = [];
	const manager = new ProgramRuntimeManager({
		registry,
		bridge: { send: async (type, agentId, payload) => sent.push({ type, agentId, payload }) },
		planner: { requestPlan: async (request) => {
			requests.push(request);
			return withCompletionContract({
				summary: 'Continue with the next bounded action.',
				directive: 'replace',
				source: 'program.onUnhandledAttention("continue_and_notify"); await player.wait(2);',
			}, request.goalRevision);
		} },
	});
	await manager.installDecision(registry.get('agent-a'), {
		directive: 'replace',
		source: 'program.onUnhandledAttention("continue_and_notify"); await player.wait(1);',
	}, { observation: observation(), eventSequence: 1 });
	await manager.onActionResult(registry.get('agent-a'), {
		actionId: sent[0].payload.actionId,
		state: 'SUCCEEDED',
		reasonCode: 'DONE',
		eventSequence: 2,
	});
	await manager.onObservation(registry.get('agent-a'), { observation: observation(), eventSequence: 2 });
	for (let attempt = 0; attempt < 10 && actionCommands(sent).length < 2; attempt += 1) await new Promise((resolve) => setImmediate(resolve));

	assert.equal(requests.length, 1);
	assert.match(requests[0].input, /"attentionTrigger":"program_exhausted"/);
	assert.equal(actionCommands(sent).length, 2, 'the replacement program dispatches without operator intervention');
	assert.deepEqual(actionCommands(sent)[1].payload.arguments, { durationMs: 2 });
	assert.equal(registry.get('agent-a').state, DynamicAgentState.ACTING);
});

test('keeps the immutable server goal when an exhausted program is replaced', async () => {
	const registry = new AgentRegistry();
	registry.register(record());
	const sent = [];
	const errors = [];
	const manager = new ProductionProgramRuntimeManager({
		registry,
		bridge: { send: async (type, agentId, payload) => sent.push({ type, agentId, payload }) },
		planner: { requestPlan: async () => ({
			summary: 'Continue after mining.',
			directive: 'replace',
			source: 'program.onUnhandledAttention("continue_and_notify"); await player.wait(2);',
		}) },
		reportError: (_agentId, error) => errors.push(error),
	});
	await manager.installDecision(registry.get('agent-a'), {
		summary: 'Mine one block.',
		directive: 'replace',
		source: 'program.onUnhandledAttention("continue_and_notify"); await player.wait(1);',
	}, { observation: observation(), eventSequence: 1 });
	await manager.onActionResult(registry.get('agent-a'), {
		actionId: actionCommands(sent)[0].payload.actionId,
		state: 'SUCCEEDED',
		reasonCode: 'DONE',
		eventSequence: 2,
	});
	await manager.onObservation(registry.get('agent-a'), { observation: observation(), eventSequence: 2 });
	for (let attempt = 0; attempt < 10 && actionCommands(sent).length < 2 && errors.length === 0; attempt += 1) {
		await new Promise((resolve) => setImmediate(resolve));
	}

	assert.equal(errors.length, 0, 'a continuation cannot mutate or invalidate the server-owned goal');
	assert.equal(actionCommands(sent).length, 2, 'the replacement continues automatically');
	assert.deepEqual(actionCommands(sent)[1].payload.arguments, { durationMs: 2 });
	assert.equal(registry.get('agent-a').state, DynamicAgentState.ACTING);
});

test('retries an exhausted program after a provider failure on fresh facts', async () => {
	const registry = new AgentRegistry();
	registry.register(record());
	const sent = [];
	const requests = [];
	const errors = [];
	const manager = new ProgramRuntimeManager({
		registry,
		bridge: { send: async (type, agentId, payload) => sent.push({ type, agentId, payload }) },
		planner: { requestPlan: async (request) => {
			requests.push(request);
			if (requests.length === 1) throw Object.assign(new Error('provider unavailable'), { code: 'PROVIDER_OFFLINE' });
			return withCompletionContract({
				summary: 'Recovered with the next action.',
				directive: 'replace',
				source: 'program.onUnhandledAttention("continue_and_notify"); await player.wait(2);',
			}, request.goalRevision);
		} },
		reportError: (_agentId, error) => errors.push(error),
	});
	await manager.installDecision(registry.get('agent-a'), {
		directive: 'replace',
		source: 'program.onUnhandledAttention("continue_and_notify"); await player.wait(1);',
	}, { observation: observation(), eventSequence: 1 });
	await manager.onActionResult(registry.get('agent-a'), {
		actionId: sent[0].payload.actionId,
		state: 'SUCCEEDED',
		reasonCode: 'DONE',
		eventSequence: 2,
	});
	await manager.onObservation(registry.get('agent-a'), { observation: observation(), eventSequence: 2 });
	for (let attempt = 0; attempt < 10 && errors.length < 1; attempt += 1) await new Promise((resolve) => setImmediate(resolve));
	await manager.onObservation(registry.get('agent-a'), { observation: observation(), eventSequence: 3 });
	for (let attempt = 0; attempt < 10 && actionCommands(sent).length < 2; attempt += 1) await new Promise((resolve) => setImmediate(resolve));

	assert.equal(errors[0]?.code, 'PROVIDER_OFFLINE');
	assert.equal(requests.length, 2, 'fresh facts retry the exhausted continuation after provider recovery');
	assert.equal(actionCommands(sent).length, 2);
	assert.equal(registry.get('agent-a').state, DynamicAgentState.ACTING);
});

test('asks again when continue cannot resume an exhausted program', async () => {
	const registry = new AgentRegistry();
	registry.register(record());
	const sent = [];
	const requests = [];
	const manager = new ProgramRuntimeManager({
		registry,
		bridge: { send: async (type, agentId, payload) => sent.push({ type, agentId, payload }) },
		planner: { requestPlan: async (request) => {
			requests.push(request);
			if (requests.length === 1) return withCompletionContract({ summary: 'Continue.', directive: 'continue' }, request.goalRevision);
			return withCompletionContract({
				summary: 'Install the missing continuation.',
				directive: 'replace',
				source: 'program.onUnhandledAttention("continue_and_notify"); await player.wait(2);',
			}, request.goalRevision);
		} },
	});
	await manager.installDecision(registry.get('agent-a'), {
		directive: 'replace',
		source: 'program.onUnhandledAttention("continue_and_notify"); await player.wait(1);',
	}, { observation: observation(), eventSequence: 1 });
	await manager.onActionResult(registry.get('agent-a'), {
		actionId: sent[0].payload.actionId,
		state: 'SUCCEEDED',
		reasonCode: 'DONE',
		eventSequence: 2,
	});
	await manager.onObservation(registry.get('agent-a'), { observation: observation(), eventSequence: 2 });
	for (let attempt = 0; attempt < 10 && requests.length < 1; attempt += 1) await new Promise((resolve) => setImmediate(resolve));
	await manager.onObservation(registry.get('agent-a'), { observation: observation(), eventSequence: 3 });
	for (let attempt = 0; attempt < 10 && actionCommands(sent).length < 2; attempt += 1) await new Promise((resolve) => setImmediate(resolve));

	assert.equal(requests.length, 2);
	assert.equal(actionCommands(sent).length, 2, 'a later replacement resumes action without an operator click');
	assert.equal(registry.get('agent-a').state, DynamicAgentState.ACTING);
});

test('reports a completed ArenaScript program after its final action result', async () => {
	const changes = [];
	const run = harness({ onCompleted: (changed) => changes.push(changed) });
	await run.manager.installDecision(run.registry.get('agent-a'), {
		summary: 'Wait, then finish.',
		directive: 'replace',
		source: 'program.onUnhandledAttention("continue_and_notify"); await player.wait(1); program.finish("done");',
	}, { observation: observation(), eventSequence: 1 });
	const command = run.sent[0];
	await run.manager.onActionResult(run.registry.get('agent-a'), { actionId: command.payload.actionId, state: 'SUCCEEDED', reasonCode: 'DONE' });
	await run.manager.onObservation(run.registry.get('agent-a'), { observation: observation(), eventSequence: 2 });
	assert.equal(run.registry.get('agent-a').state, DynamicAgentState.COMPLETED);
	assert.equal(changes.at(-1)?.state, DynamicAgentState.COMPLETED, 'terminal state is reported to the bridge owner');
});

test('requests an authoritative post-action observation instead of leaving a completed action pending forever', async () => {
	const run = harness();
	await run.manager.installDecision(run.registry.get('agent-a'), {
		summary: 'Wait twice.', directive: 'replace', source: SOURCE,
	}, { observation: observation(), eventSequence: 1 });
	const first = run.sent.find((message) => message.type === 'action_command');

	assert.equal(await run.manager.onActionResult(run.registry.get('agent-a'), {
		actionId: first.payload.actionId,
		state: 'SUCCEEDED',
		reasonCode: 'DONE',
	}), true);
	await new Promise((resolve) => setImmediate(resolve));

	assert.deepEqual(
		run.sent.filter((message) => message.type === 'request_observation').map((message) => message.payload),
		[{ goalRevision: 1 }],
		'a terminal action explicitly requests the fresh facts needed to resume its continuation',
	);
});

test('reports a completed ArenaScript program that needs no physical action', async () => {
	const changes = [];
	const run = harness({ onCompleted: (changed) => changes.push(changed) });
	await run.manager.installDecision(run.registry.get('agent-a'), {
		summary: 'Nothing else is required.',
		directive: 'replace',
		source: 'program.onUnhandledAttention("continue_and_notify"); program.finish("done");',
	}, { observation: observation(), eventSequence: 1 });

	assert.equal(run.sent.length, 0, 'zero-action completion must not fabricate a bridge command');
	assert.equal(run.registry.get('agent-a').state, DynamicAgentState.COMPLETED);
	assert.equal(changes.at(-1)?.state, DynamicAgentState.COMPLETED, 'zero-action completion is reported to the bridge owner');
});

test('treats a terminal dead-state model decision as handled without changing authoritative death', async () => {
	const registry = new AgentRegistry();
	registry.register({
		...record(), state: DynamicAgentState.DEAD, currentGoal: null, goalRevision: 2, death: DEATH,
	});
	const manager = new ProgramRuntimeManager({
		registry,
		bridge: { send: async () => assert.fail('terminal decision must not dispatch an action') },
		planner: { requestPlan: async () => assert.fail('terminal decision must not request another plan') },
	});

	const dead = registry.get('agent-a');
	await manager.installDecision(dead, {
		summary: 'No active goal to resume.', directive: 'finish', status: 'impossible',
	}, { observation: { death: DEATH }, eventSequence: 0 });

	assert.equal(registry.get('agent-a').state, DynamicAgentState.DEAD);
	assert.equal(manager.hasCurrent(dead), true, 'the handled turn prevents duplicate dead-state planning');
});

test('emits a provenance-bearing coordinate-free respawn primitive only from authored player code', async () => {
	const run = harness();
	await run.manager.installDecision(run.registry.get('agent-a'), {
		summary: 'Respawn at the vanilla target.', directive: 'replace', source: 'program.onUnhandledAttention("continue_and_notify"); await player.respawn();',
	}, { observation: observation(), eventSequence: 9 });
	assert.equal(run.sent.length, 1);
	assert.equal(run.sent[0].type, 'action_command');
	assert.equal(run.sent[0].payload.actionType, 'respawn');
	assert.deepEqual(run.sent[0].payload.arguments, {});
	assert.equal(run.sent[0].payload.provenance.provider, 'codex');
	assert.equal(run.sent[0].payload.provenance.model, 'gpt-5.6-sol');
	assert.equal(run.sent[0].payload.provenance.eventSequence, 9);
});

test('routes invalid source correction to the selected agent planner without a local replacement', async () => {
	const run = harness();
	await run.manager.installDecision(run.registry.get('agent-a'), { summary: 'Bad.', directive: 'replace', source: 'not valid ArenaScript {' }, { observation: observation(), eventSequence: 1 });
	assert.equal(run.sent.filter((message) => message.type === 'action_command').length, 0);
	assert.equal(run.requests.length, 1);
	assert.equal(run.requests[0].agentId, 'agent-a');
	assert.equal(run.requests[0].preserveState, true);
	assert.match(run.requests[0].input, /ArenaScript compiler correction/);
});

test('corrects an omitted mine argument through the selected model before bridge dispatch', async () => {
	const registry = new AgentRegistry();
	registry.register(record());
	const sent = [];
	const requests = [];
	const manager = new ProgramRuntimeManager({
		registry,
		bridge: { send: async (type, agentId, payload) => sent.push({ type, agentId, payload }) },
		planner: {
			requestPlan: async (request) => {
				requests.push(request);
				return withCompletionContract({
					summary: 'Use a valid primitive argument.',
					directive: 'replace',
					source: 'program.onUnhandledAttention("continue_and_notify"); await player.wait(1);',
				}, request.goalRevision);
			},
		},
	});

	await manager.installDecision(registry.get('agent-a'), {
		summary: 'Mine a block.',
		directive: 'replace',
		source: 'program.onUnhandledAttention("continue_and_notify"); await player.mine();',
	}, { observation: observation(), eventSequence: 1 });
	await new Promise((resolve) => setImmediate(resolve));

	assert.equal(requests.length, 1);
	assert.equal(requests[0].agentId, 'agent-a');
	assert.equal(requests[0].preserveState, true);
	assert.match(requests[0].input, /ArenaScript compiler correction/);
	assert.equal(sent.length, 1);
	assert.equal(sent[0].payload.actionType, 'wait');
});

test('uses an authored watcher before asking the provider for unmatched attention', async () => {
	const run = harness();
	await run.manager.installDecision(run.registry.get('agent-a'), {
		summary: 'Watch health.', directive: 'replace',
		source: 'program.onUnhandledAttention("continue_and_notify"); program.watch(() => player.state().health < 20, { mode: "boundary" }, async () => { await player.wait(9); }); await player.wait(1);',
	}, { observation: observation(), eventSequence: 1 });
	const first = run.sent[0];
	await run.manager.onObservation(run.registry.get('agent-a'), { observation: observation({ player: { x: 0, y: 64, z: 0, health: 19 } }), eventSequence: 37, attention: true });
	await run.manager.onActionResult(run.registry.get('agent-a'), { actionId: first.payload.actionId, state: 'SUCCEEDED', reasonCode: 'DONE', eventSequence: 38 });
	await run.manager.onObservation(run.registry.get('agent-a'), { observation: observation({ player: { x: 0, y: 64, z: 0, health: 19 } }), eventSequence: 38, attention: false });
	assert.equal(run.sent.at(-1).payload.arguments.durationMs, 9);
	assert.equal(run.sent.at(-1).payload.provenance.eventSequence, 37, 'watcher command repeats the triggering server event identity');
	assert.equal(run.requests.length, 0);
});

test('wires full watcher provenance and preserves the exact profile on reactive wake', async () => {
	const run = harness();
	const traceId = 'trace-watcher-1';
	await run.manager.installDecision(run.registry.get('agent-a'), {
		summary: 'Watch health.', directive: 'replace', traceId,
		source: 'program.onUnhandledAttention("continue_and_notify"); program.watch(() => player.state().health < 20, { mode: "boundary" }, async () => { await player.wait(9); }); await player.wait(1);',
	}, { observation: observation(), eventSequence: 1 });
	const first = run.sent[0].payload;
	await run.manager.onObservation(run.registry.get('agent-a'), { observation: observation({ player: { health: 19 } }), eventSequence: 2, attention: true });
	await run.manager.onActionResult(run.registry.get('agent-a'), { actionId: first.actionId, traceId, state: 'SUCCEEDED', reasonCode: 'DONE', eventSequence: 3 });
	await run.manager.onObservation(run.registry.get('agent-a'), { observation: observation({ player: { health: 19 } }), eventSequence: 3, attention: false });
	const watcher = run.sent.at(-1).payload;
	assert.equal(watcher.provenance.provider, 'codex');
	assert.equal(watcher.provenance.model, 'gpt-5.6-sol');
	assert.equal(watcher.provenance.reasoningEffort, 'high');
	assert.equal(watcher.provenance.serviceTier, 'fast');
	assert.equal(watcher.provenance.traceId, traceId);
	assert.equal(watcher.provenance.watcherId, 'watcher-0');
});

test('cancel-send rejection keeps the old action fenced until its terminal result arrives', async () => {
	const registry = new AgentRegistry(); registry.register(record());
	const sent = [];
	const errors = [];
	const recoveries = [];
	const manager = new ProgramRuntimeManager({
		registry,
		bridge: { send: async (type, agentId, payload) => { sent.push({ type, agentId, payload }); if (type === 'action_cancel') throw Object.assign(new Error('cancel unavailable'), { code: 'CANCEL_UNAVAILABLE' }); } },
		planner: { requestPlan: async () => ({ directive: 'continue', summary: 'continue' }) },
		reportError: (_agentId, error) => errors.push(error),
		requestRecovery: (request) => recoveries.push(request),
	});
	await manager.installDecision(registry.get('agent-a'), { directive: 'replace', source: 'program.onUnhandledAttention("pause_and_notify"); await player.wait(1);' }, { observation: observation(), eventSequence: 1 });
	const active = sent[0].payload;
	await manager.onObservation(registry.get('agent-a'), { observation: observation(), eventSequence: 2, attention: true, priority: 'urgent', trigger: 'damage' });
	for (let attempt = 0; attempt < 5 && recoveries.length === 0; attempt += 1) await new Promise((resolve) => setImmediate(resolve));
	assert.equal(registry.get('agent-a').state, DynamicAgentState.ACTING);
	assert.equal(recoveries.length, 1);
	assert.equal(sent.filter((message) => message.type === 'action_cancel').length, 1);
	assert.equal(manager.isActionResultStale(registry.get('agent-a'), { actionId: active.actionId }), false);
	assert.equal(sent.filter((message) => message.type === 'action_command').length, 1,
		'unconfirmed cancellation cannot overlap the old server action with replacement work');
	assert.equal(await manager.onActionResult(registry.get('agent-a'), {
		actionId: active.actionId, state: 'CANCELLED', reasonCode: 'LATE', eventSequence: 3,
	}), true);
	assert.equal(errors.at(-1)?.code, 'CANCEL_UNAVAILABLE');
});

test('a missing cancellation acknowledgement retries without forgetting the old action fence', async () => {
	const registry = new AgentRegistry(); registry.register(record());
	const sent = [];
	const timers = [];
	const recoveries = [];
	const manager = new ProgramRuntimeManager({
		registry,
		bridge: { send: async (type, agentId, payload) => sent.push({ type, agentId, payload }) },
		planner: { requestPlan: async () => ({ directive: 'continue' }) },
		requestRecovery: (request) => recoveries.push(request),
		cancellationAckTimeoutMs: 25,
		setTimeoutFn: (callback, delay) => {
			const timer = { callback, delay, cleared: false, unref() {} };
			timers.push(timer);
			return timer;
		},
		clearTimeoutFn: (timer) => { timer.cleared = true; },
	});
	await manager.installDecision(registry.get('agent-a'), {
		directive: 'replace',
		source: 'program.onUnhandledAttention("pause_and_notify"); await player.wait(1);',
	}, { observation: observation(), eventSequence: 1 });
	const active = sent[0].payload;
	await manager.onObservation(registry.get('agent-a'), {
		observation: observation(), eventSequence: 2, attention: true, priority: 'urgent', trigger: 'damage',
	});
	assert.equal(timers.length, 1);
	assert.equal(timers[0].delay, 25);
	timers[0].callback();
	await new Promise((resolve) => setImmediate(resolve));
	assert.equal(manager.isActionResultStale(registry.get('agent-a'), { actionId: active.actionId }), false);
	assert.equal(sent.filter((message) => message.type === 'action_cancel').length, 2);
	assert.equal(sent.filter((message) => message.type === 'action_command').length, 1,
		'watchdog expiry cannot authorize overlapping replacement work');
	for (let attempt = 0; attempt < 5 && recoveries.length === 0; attempt += 1) await new Promise((resolve) => setImmediate(resolve));
	assert.equal(recoveries.length, 1);
	assert.equal(registry.get('agent-a').state, DynamicAgentState.ACTING);
	assert.equal(await manager.onActionResult(registry.get('agent-a'), {
		actionId: active.actionId, state: 'CANCELLED', reasonCode: 'WATCHDOG_RETRY', eventSequence: 3,
	}), true);
});

test('replans from bounded failed-action context instead of pausing after a repeated deterministic failure', async () => {
	const registry = new AgentRegistry(); registry.register(record());
	const sent = [];
	const requests = [];
	const manager = new ProgramRuntimeManager({
		registry,
		bridge: { send: async (type, agentId, payload) => sent.push({ type, agentId, payload }) },
		planner: { requestPlan: async (request) => {
			requests.push(request);
			return withCompletionContract({
				summary: 'Use a different action.',
				directive: 'replace',
				source: 'program.onUnhandledAttention("continue_and_notify"); await player.wait(1);',
			}, request.goalRevision);
		} },
	});
	await manager.installDecision(registry.get('agent-a'), {
		directive: 'replace',
		source: `
			program.onUnhandledAttention("continue_and_notify");
			await program.repeatUntil(() => false, { maxIterations: 8 }, async () => {
				await player.craftInventory({ recipeId: "minecraft:planks", count: 1, timeoutMs: 5000 });
			});
		`,
	}, { observation: observation(), eventSequence: 1 });
	await manager.onActionResult(registry.get('agent-a'), { actionId: actionCommands(sent)[0].payload.actionId, state: 'FAILED', reasonCode: 'RECIPE_NOT_FOUND' });
	await manager.onObservation(registry.get('agent-a'), { observation: observation(), eventSequence: 2 });
	await manager.onActionResult(registry.get('agent-a'), { actionId: actionCommands(sent)[1].payload.actionId, state: 'FAILED', reasonCode: 'RECIPE_NOT_FOUND' });
	await manager.onObservation(registry.get('agent-a'), { observation: observation(), eventSequence: 3 });
	for (let attempt = 0; attempt < 10 && actionCommands(sent).length < 3; attempt += 1) await new Promise((resolve) => setImmediate(resolve));
	assert.equal(requests.length, 1);
	assert.match(requests[0].input, /"decisionContext":"program_action_failure"/);
	assert.match(requests[0].input, /"recipeId":"minecraft:planks"/);
	assert.equal(actionCommands(sent).length, 3);
	assert.equal(actionCommands(sent)[2].payload.actionType, 'wait');
});

test('reactive provider suspension stays active and retries once after fresh authoritative facts', async () => {
	const registry = new AgentRegistry(); registry.register(record());
	const sent = [];
	const requests = [];
	const recoveries = [];
	const timeout = Object.assign(new Error('provider timed out'), { code: 'PLANNING_TIMEOUT' });
	const manager = new ProgramRuntimeManager({
		registry,
		bridge: { send: async (type, agentId, payload) => sent.push({ type, agentId, payload }) },
		planner: { requestPlan: async (request) => {
			requests.push(request);
			if (requests.length === 1) throw timeout;
			return withCompletionContract({
				summary: 'Recover with fresh facts.',
				directive: 'replace',
				source: 'program.onUnhandledAttention("continue_and_notify"); await player.wait(1);',
			}, request.goalRevision);
		} },
		requestRecovery: (request) => recoveries.push(request),
	});
	await manager.installDecision(registry.get('agent-a'), {
		directive: 'replace',
		source: `
			program.onUnhandledAttention("continue_and_notify");
			await program.repeatUntil(() => false, { maxIterations: 8 }, async () => {
				await player.craftInventory({ recipeId: "minecraft:planks", count: 1, timeoutMs: 5000 });
			});
		`,
	}, { observation: observation(), eventSequence: 1 });
	await manager.onActionResult(registry.get('agent-a'), { actionId: actionCommands(sent)[0].payload.actionId, state: 'FAILED', reasonCode: 'RECIPE_NOT_FOUND' });
	await manager.onObservation(registry.get('agent-a'), { observation: observation(), eventSequence: 2 });
	await manager.onActionResult(registry.get('agent-a'), { actionId: actionCommands(sent)[1].payload.actionId, state: 'FAILED', reasonCode: 'RECIPE_NOT_FOUND' });
	await manager.onObservation(registry.get('agent-a'), { observation: observation(), eventSequence: 3 });
	for (let attempt = 0; attempt < 10 && recoveries.length === 0; attempt += 1) await new Promise((resolve) => setImmediate(resolve));
	assert.equal(requests.length, 1);
	assert.equal(recoveries.length, 1);
	assert.equal(recoveries[0].reason, 'reactive_provider_failure');
	assert.equal(registry.get('agent-a').state, DynamicAgentState.ACTING);

	await manager.onObservation(registry.get('agent-a'), { observation: observation({ player: { health: 19 } }), eventSequence: 4 });
	for (let attempt = 0; attempt < 10 && requests.length < 2; attempt += 1) await new Promise((resolve) => setImmediate(resolve));
	assert.equal(requests.length, 2);
	assert.equal(recoveries.length, 1, 'one failure schedules only one recovery before fresh facts');
	assert.equal(registry.get('agent-a').state, DynamicAgentState.ACTING);
	for (let attempt = 0; attempt < 10 && actionCommands(sent).at(-1)?.payload.actionType !== 'wait'; attempt += 1) await new Promise((resolve) => setImmediate(resolve));
	assert.equal(actionCommands(sent).at(-1).payload.actionType, 'wait');
});

test('reactive infrastructure recovery rearms on a later fresh fact without retry looping', async () => {
	const registry = new AgentRegistry(); registry.register(record());
	const requests = [];
	const recoveries = [];
	const manager = new ProgramRuntimeManager({
		registry,
		bridge: { send: async () => {} },
		planner: { requestPlan: async (request) => {
			requests.push(request);
			if (requests.length <= 2) throw Object.assign(new Error('provider still timed out'), { code: 'REQUEST_TIMEOUT' });
			return withCompletionContract({
				summary: 'Recovered from later facts.',
				directive: 'replace',
				source: 'program.onUnhandledAttention("continue_and_notify"); await player.wait(1);',
			}, request.goalRevision);
		} },
		requestRecovery: (request) => recoveries.push(request),
	});
	await manager.installDecision(registry.get('agent-a'), {
		directive: 'replace',
		source: 'program.onUnhandledAttention("continue_and_notify"); await player.wait(1);',
	}, { observation: observation(), eventSequence: 1 });
	await manager.onObservation(registry.get('agent-a'), {
		observation: observation(), eventSequence: 2, attention: true, priority: 'urgent', trigger: 'conversation',
	});
	for (let attempt = 0; attempt < 10 && recoveries.length === 0; attempt += 1) await new Promise((resolve) => setImmediate(resolve));
	assert.equal(requests.length, 1);
	assert.equal(recoveries.length, 1);

	await manager.onObservation(registry.get('agent-a'), {
		observation: observation({ player: { health: 19 } }), eventSequence: 3,
	});
	for (let attempt = 0; attempt < 10 && requests.length < 2; attempt += 1) await new Promise((resolve) => setImmediate(resolve));
	await new Promise((resolve) => setImmediate(resolve));
	assert.equal(requests.length, 2);
	assert.equal(recoveries.length, 2, 'the second failed cycle requests one future recovery lease');
	await manager.onObservation(registry.get('agent-a'), {
		observation: observation({ player: { health: 17 } }), eventSequence: 3,
	});
	await new Promise((resolve) => setImmediate(resolve));
	assert.equal(requests.length, 2, 'a failed cycle cannot retry again from a duplicate authoritative sequence');

	await manager.onObservation(registry.get('agent-a'), {
		observation: observation({ player: { health: 18 } }), eventSequence: 4,
	});
	for (let attempt = 0; attempt < 10 && requests.length < 3; attempt += 1) await new Promise((resolve) => setImmediate(resolve));
	await new Promise((resolve) => setImmediate(resolve));
	assert.equal(requests.length, 3);
	assert.match(requests[2].input, /"eventSequence":4/, 'the resumed request uses the latest accepted fact identity');
	assert.equal(recoveries.length, 2);
	assert.equal(registry.get('agent-a').state, DynamicAgentState.ACTING);
});

test('urgent non-observation attention rearms dormant reactive recovery once', async () => {
	const registry = new AgentRegistry(); registry.register(record());
	const requests = [];
	const recoveries = [];
	const manager = new ProgramRuntimeManager({
		registry,
		bridge: { send: async () => {} },
		planner: { requestPlan: async (request) => {
			requests.push(request);
			if (requests.length === 1) throw Object.assign(new Error('provider timed out'), { code: 'REQUEST_TIMEOUT' });
			return withCompletionContract({ summary: 'Heard the operator.', directive: 'continue' }, request.goalRevision);
		} },
		requestRecovery: (request) => recoveries.push(request),
	});
	await manager.installDecision(registry.get('agent-a'), {
		directive: 'replace', source: 'program.onUnhandledAttention("continue_and_notify"); await player.wait(1);',
	}, { observation: observation(), eventSequence: 1 });
	await manager.onObservation(registry.get('agent-a'), {
		observation: observation(), eventSequence: 2, attention: true, priority: 'urgent', trigger: 'damage',
	});
	for (let attempt = 0; attempt < 10 && recoveries.length < 1; attempt += 1) await new Promise((resolve) => setImmediate(resolve));
	assert.equal(requests.length, 1);
	assert.equal(recoveries.length, 1);

	manager.notifyAttention(registry.get('agent-a'), { priority: 'urgent', trigger: 'conversation' });
	manager.notifyAttention(registry.get('agent-a'), { priority: 'urgent', trigger: 'conversation' });
	for (let attempt = 0; attempt < 10 && requests.length < 2; attempt += 1) await new Promise((resolve) => setImmediate(resolve));
	await new Promise((resolve) => setImmediate(resolve));
	assert.equal(requests.length, 2, 'duplicate attention coalesces into the one rearmed cycle');
	assert.equal(recoveries.length, 1);
	assert.match(requests[1].input, /"attentionTrigger":"conversation"/);
	assert.equal(registry.get('agent-a').state, DynamicAgentState.ACTING);
});

test('urgent non-observation attention rearms dormant compiler correction once', async () => {
	const registry = new AgentRegistry(); registry.register(record());
	const requests = [];
	const recoveries = [];
	const manager = new ProgramRuntimeManager({
		registry,
		bridge: { send: async () => {} },
		planner: { requestPlan: async (request) => {
			requests.push(request);
			if (requests.length === 1) return withCompletionContract({ summary: 'Invalid replacement.', directive: 'replace', source: 'broken {' }, request.goalRevision);
			if (requests.length === 2) throw Object.assign(new Error('provider timed out'), { code: 'REQUEST_TIMEOUT' });
			return withCompletionContract({ summary: 'Corrected.', directive: 'replace', source: SOURCE }, request.goalRevision);
		} },
		requestRecovery: (request) => recoveries.push(request),
	});
	await manager.installDecision(registry.get('agent-a'), {
		directive: 'replace', source: 'program.onUnhandledAttention("continue_and_notify"); await player.wait(1);',
	}, { observation: observation(), eventSequence: 1 });
	await manager.onObservation(registry.get('agent-a'), {
		observation: observation(), eventSequence: 2, attention: true, priority: 'urgent', trigger: 'damage',
	});
	for (let attempt = 0; attempt < 10 && recoveries.length < 1; attempt += 1) await new Promise((resolve) => setImmediate(resolve));
	assert.equal(requests.length, 2);
	assert.equal(recoveries.length, 1);

	manager.notifyAttention(registry.get('agent-a'), { priority: 'urgent', trigger: 'conversation' });
	manager.notifyAttention(registry.get('agent-a'), { priority: 'urgent', trigger: 'conversation' });
	for (let attempt = 0; attempt < 10 && requests.length < 3; attempt += 1) await new Promise((resolve) => setImmediate(resolve));
	await new Promise((resolve) => setImmediate(resolve));
	assert.equal(requests.length, 3, 'duplicate attention coalesces into one compiler-recovery cycle');
	assert.equal(recoveries.length, 1);
	assert.match(requests[2].input, /ArenaScript compiler correction/);
	assert.equal(registry.get('agent-a').state, DynamicAgentState.ACTING);
});

test('serializes coalesced reactive planner requests for one program', async () => {
	const registry = new AgentRegistry(); registry.register(record());
	const sent = [];
	const requests = [];
	const errors = [];
	const traces = [];
	const scheduler = new PlanningScheduler({ maxConcurrent: 1, maxPending: 0 });
	let releaseFirst;
	const firstPlan = new Promise((resolve) => { releaseFirst = resolve; });
	const manager = new ProgramRuntimeManager({
		registry,
		bridge: { send: async (_type, _agentId, payload) => sent.push(payload) },
		planner: {
			requestPlan: (request) => scheduler.schedule(request.agentId, async () => {
				requests.push(request);
				if (requests.length === 1) return firstPlan;
				return { directive: 'continue', summary: 'continue' };
			}),
		},
		reportError: (_agentId, error) => errors.push(error),
		trace: (event, fields) => traces.push({ event, ...fields }),
	});
	await manager.installDecision(registry.get('agent-a'), {
		directive: 'replace',
		source: 'program.onUnhandledAttention("continue_and_notify"); await player.wait(1);',
	}, { observation: observation(), eventSequence: 1 });
	await manager.onObservation(registry.get('agent-a'), { observation: observation(), eventSequence: 2, attention: true });
	await manager.onObservation(registry.get('agent-a'), { observation: observation(), eventSequence: 3, attention: true });
	await new Promise((resolve) => setImmediate(resolve));
	assert.equal(requests.length, 1, 'coalesced attention must not start a second planner request before the first response is applied');
	releaseFirst(withCompletionContract({
		directive: 'replace', summary: 'replace stale context',
		source: 'program.onUnhandledAttention("continue_and_notify"); await player.wait(9);',
	}, 1));
	for (let attempt = 0; attempt < 5 && requests.length < 2; attempt += 1) await new Promise((resolve) => setImmediate(resolve));
	assert.equal(requests.length, 2, 'the newest coalesced context is still serviced after the first response');
	assert.equal(traces.some((entry) => entry.event === 'program_replaced' && entry.version === 2), false,
		'a replacement rejected by the engine request fence must not be reported as installed');
	assert.equal(traces.some((entry) => entry.event === 'program_replacement_rejected' && entry.result?.code === 'STALE_MODEL_REQUEST'), true,
		'a replacement rejected by the engine request fence is explicitly diagnosed');
	assert.equal(errors.filter((error) => error.code === 'PLAN_ALREADY_ACTIVE').length, 0, 'the scheduler must not reject the coalesced request as a duplicate active turn');
	scheduler.close();
});

test('passes urgent trigger metadata through a coalesced reactive planner turn', async () => {
	const registry = new AgentRegistry(); registry.register(record());
	const requests = [];
	let release;
	const first = new Promise((resolve) => { release = resolve; });
	const manager = new ProgramRuntimeManager({
		registry,
		bridge: { send: async () => {} },
		planner: { requestPlan: async (request) => { requests.push(request); if (requests.length === 1) return first; return { directive: 'continue', summary: 'continue' }; } },
	});
	await manager.installDecision(registry.get('agent-a'), { directive: 'replace', source: SOURCE }, { observation: observation(), eventSequence: 1 });
	await manager.onObservation(registry.get('agent-a'), { observation: observation(), eventSequence: 2, attention: true, priority: 'ordinary', trigger: 'observation' });
	await manager.onObservation(registry.get('agent-a'), { observation: observation(), eventSequence: 3, attention: true, priority: 'urgent', trigger: 'damage' });
	release({ directive: 'continue', summary: 'continue' });
	for (let attempt = 0; attempt < 10 && requests.length < 2; attempt += 1) await new Promise((resolve) => setImmediate(resolve));
	assert.equal(requests.length, 2);
	assert.equal(requests[1].planningPriority, 'urgent');
	assert.equal(requests[1].priority, 'urgent');
	assert.match(requests[1].input, /"attentionPriority":"urgent"/);
	assert.match(requests[1].input, /"attentionTrigger":"damage"/);
});

test('urgent attention interrupts stale ordinary reactive planning before installing its result', async () => {
	const registry = new AgentRegistry(); registry.register(record());
	const requests = [];
	let rejectOrdinary;
	const planner = {
		requestPlan: (request) => {
			requests.push(request);
			if (requests.length === 1) return new Promise((_resolve, reject) => { rejectOrdinary = reject; });
			return Promise.resolve(withCompletionContract({ directive: 'continue', summary: 'Handle damage now.' }, request.goalRevision));
		},
		interrupt: async () => rejectOrdinary(Object.assign(new Error('superseded'), { code: 'PLAN_CANCELLED' })),
	};
	const errors = [];
	const manager = new ProgramRuntimeManager({
		registry,
		bridge: { send: async () => {} },
		planner,
		reportError: (_agentId, error) => errors.push(error),
	});
	await manager.installDecision(registry.get('agent-a'), { directive: 'replace', source: SOURCE }, { observation: observation(), eventSequence: 1 });
	await manager.onObservation(registry.get('agent-a'), { observation: observation(), eventSequence: 2, attention: true, priority: 'ordinary', trigger: 'observation' });
	await new Promise((resolve) => setImmediate(resolve));
	await manager.onObservation(registry.get('agent-a'), {
		observation: observation({ player: { health: 18 } }), eventSequence: 3, attention: true, priority: 'urgent', trigger: 'damage',
	});
	for (let attempt = 0; attempt < 10 && requests.length < 2; attempt += 1) await new Promise((resolve) => setImmediate(resolve));
	assert.equal(requests.length, 2);
	assert.equal(requests[1].priority, 'urgent');
	assert.match(requests[1].input, /"attentionTrigger":"damage"/);
	assert.equal(errors.length, 0);
});

test('measures one thousand watcher branches with the real monotonic clock', {
	skip: process.env.CI ? 'host timing benchmark runs outside shared CI' : false,
}, async () => {
	const registry = new AgentRegistry();
	for (const agentId of ['agent-a', 'agent-b', 'agent-c', 'agent-d']) registry.register(record(agentId));
	const latencies = new ControlLatencyRegistry({ windowSize: 1_000 });
	const sent = [];
	const manager = new ProgramRuntimeManager({
		registry,
		bridge: { send: async (type, agentId, payload) => sent.push({ type, agentId, payload }) },
		planner: { requestPlan: async () => ({ directive: 'continue', summary: 'continue' }) },
		latencyRegistry: latencies,
		clock: performance.now.bind(performance),
	});
	for (const agentId of ['agent-a', 'agent-b', 'agent-c', 'agent-d']) {
		await manager.installDecision(registry.get(agentId), { directive: 'replace', source: 'program.onUnhandledAttention("continue_and_notify"); program.watch(() => player.state().health < 20, { mode: "boundary" }, async () => { await player.wait(1); }); await player.wait(1);' }, { observation: observation(), eventSequence: 1 });
		await new Promise((resolve) => setImmediate(resolve));
		let sequence = 2;
		for (let event = 0; event < 250; event++) {
			await manager.onObservation(registry.get(agentId), { observation: observation({ player: { health: 19 } }), receiptMonotonicMs: performance.now(), receiptEpochMs: Date.now(), observedAtEpochMs: Date.now() - 1, eventSequence: sequence++, attention: true });
			await new Promise((resolve) => setImmediate(resolve));
			await manager.onActionResult(registry.get(agentId), { actionId: sent.at(-1).payload.actionId, state: 'SUCCEEDED', reasonCode: 'DONE', eventSequence: sequence++ });
			await new Promise((resolve) => setImmediate(resolve));
			await manager.onObservation(registry.get(agentId), { observation: observation({ player: { health: 20 } }), receiptMonotonicMs: performance.now(), receiptEpochMs: Date.now(), observedAtEpochMs: Date.now() - 1, eventSequence: sequence++, attention: false });
		}
	}
	const snapshot = latencies.snapshot();
	console.log(`REAL_TIMER_LATENCY_SUMMARY ${JSON.stringify({
		basis: 'performance_now_monotonic_clock',
		segments: snapshot.filter(({ operation }) => ['event_receipt_to_branch', 'branch_to_bridge_send'].includes(operation)),
	})}`);
	for (const operation of ['event_receipt_to_branch', 'branch_to_bridge_send']) {
		const metric = snapshot.find((entry) => entry.operation === operation);
		assert.ok(metric, `${operation} is recorded`);
		assert.equal(metric.count, 1_000, `${operation} has exactly one sample per watcher branch`);
		assert.ok(Number.isFinite(metric.p95Ms) && metric.p95Ms > 0 && metric.p95Ms < 5, `${operation} p95 is positive and stays below 5ms`);
	}
	assert.equal(actionCommands(sent).filter((message) => message.payload.provenance.eventSequence > 1).length, 1_000, 'each watcher event produces exactly one command');
	assert.equal(snapshot.find((entry) => entry.operation === 'branch_to_bridge_send').count, 1_000, 'each watcher command contributes one branch-to-send sample');
});

test('ignores duplicate server observations and omits skewed epoch telemetry', async () => {
	const registry = new AgentRegistry(); registry.register(record());
	const latencies = new ControlLatencyRegistry();
	let now = 100;
	const manager = new ProgramRuntimeManager({
		registry,
		bridge: { send: async () => {} }, planner: { requestPlan: async () => ({ directive: 'continue' }) },
		latencyRegistry: latencies, clock: () => now++,
	});
	await manager.installDecision(registry.get('agent-a'), { directive: 'replace', source: SOURCE }, { observation: observation(), eventSequence: 1 });
	await manager.onObservation(registry.get('agent-a'), { observation: observation(), eventSequence: 2, receiptMonotonicMs: 100, receiptEpochMs: 1_000, observedAtEpochMs: 2_000, attention: false });
	await manager.onObservation(registry.get('agent-a'), { observation: observation({ player: { health: 19 } }), eventSequence: 2, receiptMonotonicMs: 101, receiptEpochMs: 900, observedAtEpochMs: 800, attention: true });
	assert.equal(latencies.snapshot().some((entry) => entry.operation === 'event_receipt_to_branch'), false, 'duplicate server event is ignored before branch timing');
	assert.equal(latencies.snapshot().some((entry) => entry.operation === 'minecraft_change_to_publication'), false, 'duplicate future/skewed event is omitted instead of coerced to zero');
});

test('records completion from bridge send even when no progress arrives', async () => {
	const registry = new AgentRegistry(); registry.register(record());
	const latencies = new ControlLatencyRegistry();
	let now = 10;
	const sent = [];
	const manager = new ProgramRuntimeManager({
		registry,
		bridge: { send: async (_type, _agentId, payload) => sent.push(payload) }, planner: { requestPlan: async () => ({ directive: 'continue' }) },
		latencyRegistry: latencies, clock: () => now++,
	});
	await manager.installDecision(registry.get('agent-a'), { directive: 'replace', source: SOURCE }, { observation: observation(), eventSequence: 1 });
	await new Promise((resolve) => setImmediate(resolve));
	await manager.onActionResult(registry.get('agent-a'), { actionId: sent[0].actionId, state: 'SUCCEEDED', reasonCode: 'DONE' });
	const completion = latencies.snapshot().find((entry) => entry.operation === 'action_completion');
	assert.equal(completion.count, 1);
	assert.equal(latencies.snapshot().some((entry) => entry.operation === 'command_to_first_progress'), false);
});

test('records only the first progress event for an action', async () => {
	const registry = new AgentRegistry(); registry.register(record());
	const latencies = new ControlLatencyRegistry();
	let now = 10;
	const sent = [];
	const manager = new ProgramRuntimeManager({
		registry,
		bridge: { send: async (_type, _agentId, payload) => sent.push(payload) }, planner: { requestPlan: async () => ({ directive: 'continue' }) },
		latencyRegistry: latencies, clock: () => now++,
	});
	await manager.installDecision(registry.get('agent-a'), { directive: 'replace', source: SOURCE }, { observation: observation(), eventSequence: 1 });
	await new Promise((resolve) => setImmediate(resolve));
	assert.equal(manager.onActionProgress(registry.get('agent-a'), { actionId: sent[0].actionId }), true);
	assert.equal(manager.onActionProgress(registry.get('agent-a'), { actionId: sent[0].actionId }), true);
	assert.equal(latencies.snapshot().find((entry) => entry.operation === 'command_to_first_progress').count, 1);
});

test('telemetry clock faults omit samples without interrupting program control', async () => {
	const registry = new AgentRegistry(); registry.register(record());
	const sent = [];
	const samples = [Number.NaN, -1, 10, 9, new Error('clock unavailable')];
	const manager = new ProgramRuntimeManager({
		registry,
		bridge: { send: async (type, _agentId, payload) => sent.push({ type, payload }) },
		planner: { requestPlan: async () => ({ directive: 'continue' }) },
		latencyRegistry: new ControlLatencyRegistry(),
		clock: () => {
			const value = samples.shift();
			if (value instanceof Error) throw value;
			return value ?? 10;
		},
	});
	await manager.installDecision(registry.get('agent-a'), { directive: 'replace', source: 'program.onUnhandledAttention("continue_and_notify"); await player.wait(1); await player.wait(1); await player.wait(1);' }, { observation: observation(), eventSequence: 1 });
	await new Promise((resolve) => setImmediate(resolve));
	assert.equal(await manager.onActionResult(registry.get('agent-a'), { actionId: actionCommands(sent)[0].payload.actionId, state: 'SUCCEEDED', reasonCode: 'DONE' }), true);
	await manager.onObservation(registry.get('agent-a'), { observation: observation(), eventSequence: 2 });
	await new Promise((resolve) => setImmediate(resolve));
	assert.equal(await manager.onActionResult(registry.get('agent-a'), { actionId: actionCommands(sent)[1].payload.actionId, state: 'SUCCEEDED', reasonCode: 'DONE' }), true);
	await manager.onObservation(registry.get('agent-a'), { observation: observation(), eventSequence: 3 });
	await new Promise((resolve) => setImmediate(resolve));
	assert.equal(actionCommands(sent).length, 3, 'NaN, negative, regressing, and throwing clock reads cannot stop commands');
	assert.ok(await manager.onObservation(registry.get('agent-a'), {
		observation: observation(), eventSequence: 4, attention: true,
		receiptMonotonicMs: -1, receiptEpochMs: Number.NaN, observedAtEpochMs: 1,
	}), 'invalid receipt timestamps cannot reject a valid observation');
});

test('disposes an old goal program so late action results cannot advance it', async () => {
	const run = harness();
	await run.manager.installDecision(run.registry.get('agent-a'), { summary: 'Wait.', directive: 'replace', source: SOURCE }, { observation: observation(), eventSequence: 1 });
	const first = run.sent[0];
	run.manager.onGoalControl(run.registry.get('agent-a'), 'steer');
	assert.equal(run.sent.at(-1).type, 'action_cancel');
	await run.manager.onActionResult(run.registry.get('agent-a'), { actionId: first.payload.actionId, state: 'SUCCEEDED', reasonCode: 'LATE' });
	assert.equal(run.sent.filter((message) => message.type === 'action_command').length, 1);
});

test('late cancel rejection from a disposed runtime cannot mutate the replacement goal', async () => {
	const registry = new AgentRegistry(); registry.register(record());
	const errors = [];
	let rejectCancel;
	const manager = new ProgramRuntimeManager({
		registry,
		bridge: {
			send: async (type) => {
				if (type === 'action_cancel') return new Promise((_resolve, reject) => { rejectCancel = reject; });
			},
		},
		planner: { requestPlan: async () => ({ directive: 'continue' }) },
		reportError: (_agentId, error) => errors.push(error),
	});
	await manager.installDecision(registry.get('agent-a'), { directive: 'replace', source: SOURCE }, { observation: observation(), eventSequence: 1 });
	await new Promise((resolve) => setImmediate(resolve));
	manager.dispose('agent-a');
	const replacementFields = { originalRequest: 'wait somewhere else', predicate: { type: 'operator_confirmed' }, createdAtTick: 2 };
	registry.applyGoalControl('agent-a', {
		operation: 'steer',
		goalRevision: 2,
		goal: replacementFields.originalRequest,
		goalSpec: { ...replacementFields, fingerprint: goalSpecFingerprint(replacementFields) },
		updatedAtEpochMs: 2,
	});
	assert.equal(registry.get('agent-a').state, DynamicAgentState.STARTING);
	rejectCancel(Object.assign(new Error('late cancellation failure'), { code: 'BRIDGE_DISCONNECTED' }));
	await new Promise((resolve) => setImmediate(resolve));
	assert.equal(registry.get('agent-a').state, DynamicAgentState.STARTING);
	assert.deepEqual(errors, []);
});

test('does not turn an ordinary active-action observation into unmatched attention', async () => {
	const run = harness();
	await run.manager.installDecision(run.registry.get('agent-a'), { summary: 'Wait.', directive: 'replace', source: SOURCE }, { observation: observation(), eventSequence: 1 });
	await run.manager.onObservation(run.registry.get('agent-a'), { observation: observation(), attention: false });
	assert.equal(run.requests.length, 0);
});

test('keeps versions and external action identities monotonic across remove and recreate', async () => {
	const run = harness();
	await run.manager.installDecision(run.registry.get('agent-a'), { summary: 'Wait.', directive: 'replace', source: SOURCE }, { observation: observation(), eventSequence: 1 });
	const old = run.sent[0].payload;
	run.manager.dispose('agent-a');
	run.registry.remove('agent-a');
	run.registry.register(record());
	await run.manager.installDecision(run.registry.get('agent-a'), { summary: 'Wait.', directive: 'replace', source: SOURCE }, { observation: observation(), eventSequence: 1 });
	const replacement = run.sent.at(-1).payload;
	assert.equal(replacement.provenance.programId, 'program-1-2');
	assert.notEqual(replacement.actionId, old.actionId);
	assert.equal(await run.manager.onActionResult(run.registry.get('agent-a'), { actionId: old.actionId, state: 'SUCCEEDED', reasonCode: 'LATE' }), false);
});

test('caps recursive compiler correction and reports exhaustion without a fallback action', async () => {
	const registry = new AgentRegistry(); registry.register(record());
	const errors = []; let requests = 0;
	const manager = new ProgramRuntimeManager({ registry, bridge: { send: async () => assert.fail('must not dispatch') }, planner: { requestPlan: async (request) => { requests += 1; return withCompletionContract({ summary: 'Still bad.', directive: 'replace', source: 'broken {' }, request.goalRevision); } }, reportError: (_agentId, error) => errors.push(error), compilerCorrectionLimit: 1 });
	await manager.installDecision(registry.get('agent-a'), { summary: 'Bad.', directive: 'replace', source: 'broken {' }, { observation: observation(), eventSequence: 1 });
	assert.equal(requests, 1);
	assert.equal(errors.at(-1).code, 'ARENA_SCRIPT_COMPILER_EXHAUSTED');
});

test('provider, session, scheduler, and blocked authentication failures share active recovery policy', async () => {
	for (const code of [
		'PROVIDER_TIMEOUT', 'REQUEST_TIMEOUT', 'PLANNING_TIMEOUT', 'PROCESS_TERMINATION_FAILED',
		'PROVIDER_UNAVAILABLE', 'PROVIDER_STOPPED', 'SESSION_INVALIDATED', 'TRANSPORT_STOPPED',
		'PROVIDER_CIRCUIT_OPEN', 'SCHEDULER_CAPACITY', 'AUTHENTICATION_REQUIRED', 'MISSING_CREDENTIALS',
		'AGENT_NOT_STARTED', 'AGENT_PROFILE_CONFLICT', 'INCOMPLETE_TURN', 'INVALID_CATALOG',
		'INVALID_PROVIDER_OUTPUT', 'MODEL_PROFILE_UNAVAILABLE', 'PROCESS_EXITED', 'PROVIDER_DOWN',
		'PROVIDER_OVERLOADED', 'REQUEST_ID_EXHAUSTED', 'SESSION_GENERATION_MISMATCH',
		'SESSION_PROFILE_MISMATCH', 'STALE_PROVIDER_START', 'STALE_RECONCILIATION',
		'STALE_SESSION_GENERATION', 'TURN_IN_PROGRESS', 'TURN_INTERRUPTED', 'TURN_NOT_ACTIVE',
		'UNKNOWN_RESPONSE_ID', 'CONTROL_PROTOCOL_MISMATCH', 'INVALID_CONFIG_OPTIONS', 'MODEL_UNAVAILABLE',
		'NATIVE_TOOLS_UNAVAILABLE', 'PROVIDER_MISMATCH', 'REASONING_EFFORT_UNAVAILABLE',
		'SERVICE_TIER_UNAVAILABLE', 'UNSUPPORTED_MODEL', 'UNSUPPORTED_SERVICE_TIER', 'UNSUPPORTED_THINKING',
	]) {
		const registry = new AgentRegistry(); registry.register(record());
		const recoveries = [];
		const errors = [];
		const manager = new ProgramRuntimeManager({
			registry,
			bridge: { send: async () => assert.fail('invalid source must not dispatch') },
			planner: { requestPlan: async () => { throw Object.assign(new Error(`failure ${code}`), { code }); } },
			requestRecovery: (request) => recoveries.push(request),
			reportError: (_agentId, error) => errors.push(error),
		});
		await manager.installDecision(registry.get('agent-a'), withCompletionContract({ summary: 'Invalid.', directive: 'replace', source: 'broken {' }, 1), { observation: observation(), eventSequence: 1 });
		assert.equal(registry.get('agent-a').state === DynamicAgentState.ERROR || registry.get('agent-a').state === DynamicAgentState.PAUSED, false, code);
		assert.equal(recoveries.length, 1, `${code} requests one bounded recovery`);
		assert.equal(recoveries[0].errorCode, code);
		assert.equal(errors.length, 0, `${code} is not published as a domain failure`);
	}
});

test('compiler-correction infrastructure recovery rearms on a later fresh fact without retry looping', async () => {
	const registry = new AgentRegistry(); registry.register(record());
	const requests = [];
	const recoveries = [];
	const sent = [];
	const manager = new ProgramRuntimeManager({
		registry,
		bridge: { send: async (type, agentId, payload) => sent.push({ type, agentId, payload }) },
		planner: { requestPlan: async (request) => {
			requests.push(request);
			if (requests.length <= 2) throw Object.assign(new Error('provider request timed out'), { code: 'PROVIDER_TIMEOUT' });
			return withCompletionContract({ summary: 'Corrected.', directive: 'replace', source: SOURCE }, request.goalRevision);
		} },
		requestRecovery: (request) => recoveries.push(request),
	});
	await manager.installDecision(registry.get('agent-a'), withCompletionContract({ summary: 'Invalid.', directive: 'replace', source: 'broken {' }, 1), { observation: observation(), eventSequence: 1 });
	assert.equal(requests.length, 1);
	assert.equal(recoveries.length, 1);
	assert.notEqual(registry.get('agent-a').state, DynamicAgentState.ERROR);
	await manager.onObservation(registry.get('agent-a'), { observation: observation({ player: { health: 19 } }), eventSequence: 2 });
	for (let attempt = 0; attempt < 10 && requests.length < 2; attempt += 1) await new Promise((resolve) => setImmediate(resolve));
	assert.equal(requests.length, 2);
	assert.equal(recoveries.length, 2, 'the second failed correction requests one future recovery lease');
	assert.equal(actionCommands(sent).length, 0);
	await new Promise((resolve) => setImmediate(resolve));
	assert.equal(requests.length, 2, 'the failed correction cannot retry again without newer authoritative facts');

	await manager.onObservation(registry.get('agent-a'), { observation: observation({ player: { health: 18 } }), eventSequence: 3 });
	for (let attempt = 0; attempt < 10 && actionCommands(sent).length === 0; attempt += 1) await new Promise((resolve) => setImmediate(resolve));
	assert.equal(requests.length, 3);
	assert.equal(recoveries.length, 2);
	assert.equal(actionCommands(sent).length, 1);
	assert.equal(registry.get('agent-a').state, DynamicAgentState.ACTING);
});

test('bridge send rejection unwedges the active program with a stable failed result', async () => {
	const registry = new AgentRegistry(); registry.register(record());
	const errors = [];
	const manager = new ProgramRuntimeManager({
		registry,
		bridge: { send: async () => { throw Object.assign(new Error('queue full'), { code: 'AGENT_BACKPRESSURE' }); } },
		planner: { requestPlan: async () => ({ directive: 'continue', summary: 'continue' }) },
		reportError: (_id, error) => errors.push(error),
	});
	await manager.installDecision(registry.get('agent-a'), { directive: 'replace', source: SOURCE }, { observation: observation(), eventSequence: 1 });
	await new Promise((resolve) => setImmediate(resolve));
	assert.equal(errors.at(-1).code, 'AGENT_BACKPRESSURE');
	const snapshot = await manager.onObservation(registry.get('agent-a'), { observation: observation(), eventSequence: 2, attention: false });
	assert.equal(snapshot.activeActionId, null, 'failed send is terminally acknowledged instead of wedging the engine');
});

test('ArenaScript action is not sent or reported when the registry advances during dispatch bookkeeping', async () => {
	const registry = new AgentRegistry(); registry.register(record());
	const sent = [];
	const errors = [];
	let advanced = false;
	const replacementFields = { originalRequest: 'wait somewhere else', predicate: { type: 'operator_confirmed' }, createdAtTick: 2 };
	const manager = new ProgramRuntimeManager({
		registry,
		bridge: { send: async (type, agentId, payload) => sent.push({ type, agentId, payload }) },
		planner: { requestPlan: async () => ({ directive: 'continue', summary: 'continue' }) },
		reportError: (_agentId, error) => errors.push(error),
		benchmarkRecorder: {
			record: (stage) => {
				if (stage !== 'action_dispatch_started' || advanced) return;
				advanced = true;
				registry.applyGoalControl('agent-a', {
					operation: 'steer',
					goalRevision: 2,
					goal: replacementFields.originalRequest,
					goalSpec: { ...replacementFields, fingerprint: goalSpecFingerprint(replacementFields) },
					updatedAtEpochMs: 2,
				});
			},
		},
	});

	await manager.installDecision(registry.get('agent-a'), { directive: 'replace', source: SOURCE }, { observation: observation(), eventSequence: 1 });
	await new Promise((resolve) => setImmediate(resolve));
	assert.equal(registry.get('agent-a').goalRevision, 2);
	assert.equal(actionCommands(sent).length, 0, 'the final registry fence prevents the stale bridge call');
	assert.equal(errors.length, 0, 'local staleness is lifecycle control, not a provider failure');
});

test('ArenaScript action is not sent or reported when its runtime is disposed during dispatch bookkeeping', async () => {
	const registry = new AgentRegistry(); registry.register(record());
	const sent = [];
	const errors = [];
	let manager;
	let disposed = false;
	manager = new ProgramRuntimeManager({
		registry,
		bridge: { send: async (type, agentId, payload) => sent.push({ type, agentId, payload }) },
		planner: { requestPlan: async () => ({ directive: 'continue', summary: 'continue' }) },
		reportError: (_agentId, error) => errors.push(error),
		benchmarkRecorder: {
			record: (stage) => {
				if (stage !== 'action_dispatch_started' || disposed) return;
				disposed = true;
				manager.dispose('agent-a');
			},
		},
	});

	await manager.installDecision(registry.get('agent-a'), { directive: 'replace', source: SOURCE }, { observation: observation(), eventSequence: 1 });
	await new Promise((resolve) => setImmediate(resolve));
	assert.equal(actionCommands(sent).length, 0, 'a disposed runtime cannot enqueue its captured action');
	assert.equal(errors.length, 0, 'disposal is not published as a provider failure');
});

test('bridge.send STALE_PLAN is reported because it is not local stale detection', async () => {
	const run = harness({
		bridgeSend: async () => { throw Object.assign(new Error('bridge rejected the action'), { code: 'STALE_PLAN' }); },
	});
	await run.manager.installDecision(run.registry.get('agent-a'), { directive: 'replace', source: SOURCE }, { observation: observation(), eventSequence: 1 });
	await new Promise((resolve) => setImmediate(resolve));
	assert.equal(run.errors.at(-1)?.error.code, 'STALE_PLAN');
	assert.equal(actionCommands(run.sent).length, 0);
});

test('bridge send rejection contains a model execution error and schedules active recovery', async () => {
	const registry = new AgentRegistry(); registry.register(record());
	const errors = [];
	const recoveries = [];
	const manager = new ProgramRuntimeManager({
		registry,
		bridge: { send: async () => { throw Object.assign(new Error('stale revision'), { code: 'STALE_GOAL_REVISION' }); } },
		planner: { requestPlan: async () => ({ directive: 'continue', summary: 'continue' }) },
		reportError: (_id, error) => errors.push(error),
		requestRecovery: (request) => recoveries.push(request),
	});
	await manager.installDecision(registry.get('agent-a'), {
		directive: 'replace',
		source: 'program.onUnhandledAttention("continue_and_notify"); const result = await tryResult(player.wait(1)); const invalid = result.yaw; program.finish("done");',
	}, { observation: observation(), eventSequence: 1 });
	await new Promise((resolve) => setImmediate(resolve));
	assert.equal(registry.get('agent-a').state, DynamicAgentState.ACTING);
	assert.equal(recoveries.length, 1);
	assert.equal(errors.some((error) => error.code === 'STALE_GOAL_REVISION'), true);
	assert.equal(errors.some((error) => error.code === 'UNKNOWN_MEMBER'), true);
});


test('failed and timed-out results remain pending until authoritative facts arrive', async () => {
	for (const [state, reasonCode] of [['FAILED', 'PATH_BLOCKED'], ['TIMED_OUT', 'PATH_TIMEOUT']]) {
		const run = harness();
		await run.manager.installDecision(run.registry.get('agent-a'), {
			summary: 'Checkpoint failures.', directive: 'replace',
			source: 'program.onUnhandledAttention("continue_and_notify"); const result = await tryResult(player.wait(1)); if (!result.succeeded) program.checkpoint(result.reason); program.finish("done");',
		}, { observation: observation(), eventSequence: 1 });
		const first = run.sent[0];
		assert.equal(await run.manager.onActionResult(run.registry.get('agent-a'), {
			actionId: first.payload.actionId, state, reasonCode,
		}), true);
		assert.equal(run.registry.get('agent-a').state, DynamicAgentState.ACTING, `${state} alone does not advance control`);
		await run.manager.onObservation(run.registry.get('agent-a'), {
			observation: observation(), eventSequence: 2, attention: false,
		});
		assert.equal(run.registry.get('agent-a').state, DynamicAgentState.PAUSED, `${state} is applied after fresh facts`);
	}
});

test('retains one terminal result across duplicates and releases it on a jumped observation sequence', async () => {
	const run = harness();
	await run.manager.installDecision(run.registry.get('agent-a'), {
		summary: 'Wait twice.', directive: 'replace', source: SOURCE,
	}, { observation: observation(), eventSequence: 1 });
	const first = run.sent[0];
	assert.equal(await run.manager.onActionResult(run.registry.get('agent-a'), {
		actionId: first.payload.actionId, state: 'SUCCEEDED', reasonCode: 'DONE', eventSequence: 99,
	}), true);
	assert.equal(await run.manager.onActionResult(run.registry.get('agent-a'), {
		actionId: first.payload.actionId, state: 'FAILED', reasonCode: 'DUPLICATE', eventSequence: 100,
	}), false, 'a duplicate cannot replace the retained result');
	assert.equal(actionCommands(run.sent).length, 1);
	await run.manager.onObservation(run.registry.get('agent-a'), {
		observation: observation({ player: { x: 9, y: 64, z: 0, health: 20 } }), eventSequence: 9, attention: false,
	});
	assert.equal(actionCommands(run.sent).length, 2, 'a jumped authoritative sequence releases the retained result once');
	assert.equal(actionCommands(run.sent)[1].payload.provenance.eventSequence, 9);
});

test('goal replacement clears a retained terminal result', async () => {
	const run = harness();
	await run.manager.installDecision(run.registry.get('agent-a'), {
		summary: 'Wait twice.', directive: 'replace', source: SOURCE,
	}, { observation: observation(), eventSequence: 1 });
	const first = run.sent[0];
	await run.manager.onActionResult(run.registry.get('agent-a'), {
		actionId: first.payload.actionId, state: 'SUCCEEDED', reasonCode: 'DONE',
	});
	run.manager.onGoalControl(run.registry.get('agent-a'), 'steer');
	assert.equal(await run.manager.onObservation(run.registry.get('agent-a'), {
		observation: observation(), eventSequence: 2, attention: false,
	}), null);
	assert.equal(run.sent.filter((message) => message.type === 'action_command').length, 1,
			'disposed pending work cannot dispatch under a replacement goal');
});

test('refreshes authored watcher facts across two hundred quiet movement observations without provider turns', async () => {
	const run = harness();
	await run.manager.installDecision(run.registry.get('agent-a'), {
		summary: 'Watch movement and health.', directive: 'replace',
		source: 'program.onUnhandledAttention("continue_and_notify"); program.watch(() => player.state().x >= 200 && player.state().health === 20, { mode: "boundary" }, async () => { await player.wait(7); }); await player.wait(1);',
	}, { observation: observation(), eventSequence: 1 });
	const first = run.sent[0];
	for (let index = 1; index <= 200; index += 1) {
		const sequence = index + 1;
		const snapshot = await run.manager.onObservation(run.registry.get('agent-a'), {
			observation: observation({ player: { x: index, y: 64, z: index / 2, health: 20 } }),
			eventSequence: sequence,
			attention: false,
		});
		assert.equal(snapshot.factsSequence, sequence, `quiet observation ${sequence} refreshes local facts`);
	}
	assert.equal(run.requests.length, 0, 'two hundred quiet fact updates request no provider turn');
	await run.manager.onActionResult(run.registry.get('agent-a'), {
		actionId: first.payload.actionId, state: 'SUCCEEDED', reasonCode: 'DONE', eventSequence: 202,
	});
	assert.equal(actionCommands(run.sent).length, 1, 'the result still waits after two hundred prior fact updates');
	await run.manager.onObservation(run.registry.get('agent-a'), {
		observation: observation({ player: { x: 200, y: 64, z: 100, health: 20 } }),
		eventSequence: 202,
		attention: false,
	});
	assert.equal(run.sent.at(-1).payload.arguments.durationMs, 7, 'the authored movement and health watcher uses refreshed facts locally');
	assert.equal(run.sent.at(-1).payload.provenance.eventSequence, 201, 'the watcher retains the exact triggering observation sequence');
});
