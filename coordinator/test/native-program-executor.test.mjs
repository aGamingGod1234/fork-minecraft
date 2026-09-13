import assert from 'node:assert/strict';
import test from 'node:test';
import { NativeProgramExecutor } from '../src/native-program-executor.mjs';
import { validateAction } from '../src/schema.mjs';

const record = { agentId: 'agent-a', goalRevision: 1, provider: 'codex', model: 'gpt-5.6-sol', reasoningEffort: 'high', serviceTier: 'priority' };
const prefix = 'program.onUnhandledAttention("continue_and_notify");';
function observation(health = 20) { return { player: { x: 0, y: 64, z: 0, health }, entities: [], items: [], blocks: [], inventory: { items: [], tagCounts: {} } }; }
function turn() { return new Promise((resolve) => setImmediate(resolve)); }
function setup(overrides = {}, options = {}) {
	let sequence = 1;
	const commands = [], cancels = [];
	const executor = new NativeProgramExecutor(options);
	const context = { observation: observation(), eventSequence: sequence,
		executeAction: async (command) => { commands.push(command); return { state: 'SUCCEEDED', reasonCode: 'DONE' }; },
		cancelAction: async (actionId, reason) => { cancels.push({ actionId, reason }); },
		refreshObservation: async () => ({ observation: observation(), eventSequence: ++sequence }), ...overrides };
	return { executor, context, commands, cancels };
}

test('native programs use the same candidate arithmetic and exact authored commands, returning to their caller at exhaustion', async () => {
	const run = setup({ observation: { ...observation(), entities: [
		{ stableId: '11111111-1111-1111-1111-111111111111', type: 'minecraft:pig', x: 2, y: 64, z: 2, velocity: { x: 1, y: 0, z: 0 } },
	] } });
	const result = await run.executor.run(record, { source: `${prefix}
		let target = null;
		for (const candidate of world.entities()) if (candidate.velocity.x > 0) target = candidate;
		await player.control({ forward: 0, strafe: 1, jump: false, sneak: true, sprint: false, attack: false, use: false,
			yaw: math.atan2(target.z, target.x) * 180 / 3.141592653589793, pitch: 0, selectedSlot: 0, hand: "main", ticks: 2 });
		await player.wait(2);
	` }, run.context);
	assert.equal(result.reasonCode, 'PROGRAM_EXHAUSTED');
	assert.equal(result.actions, 2);
	assert.equal(run.commands[0].action.arguments.yaw, 45);
	assert.deepEqual(run.commands[1].action, { type: 'wait', arguments: { durationMs: 2 } });
	for (const command of run.commands) {
		validateAction({ type: command.action.type, ...command.action.arguments });
		assert.equal(command.provenance.model, record.model);
		assert.equal(command.provenance.reasoningEffort, record.reasoningEffort);
		assert.match(command.provenance.programId, /^native-program-/);
		assert.match(command.provenance.stepId, /^step-/);
	}
	assert.equal(result.receipts[0].state, 'SUCCEEDED');
	assert.equal(run.cancels.length, 0);
});

test('queries and notes retain model authorship without acquiring physical action identity', async () => {
	const queries = [], memories = [];
	const run = setup({ inspect: async (query) => { queries.push(query); return { state: 'SUCCEEDED', menu: { menuId: 'minecraft:generic_9x3', containerId: 3, stateId: 9 } }; },
		memoryOperation: async (request) => { memories.push(request); return { state: 'SUCCEEDED', entries: [] }; } });
	const result = await run.executor.run(record, { source: `${prefix}
		const page = await world.inspect({ section: "menu" });
		await world.remember({ key: "container", text: "Inspected the nearby container." });
		await world.queryMemory({ kind: "notes", offset: 2, limit: 4 });
		await player.menuClose({ menuId: page.menu.menuId, containerId: page.menu.containerId, stateId: page.menu.stateId });
	` }, run.context);
	assert.equal(result.reasonCode, 'PROGRAM_EXHAUSTED');
	assert.equal(run.commands.length, 1);
	assert.equal(run.commands[0].action.arguments.stateId, 9);
	assert.equal(queries.length, 1);
	assert.equal(memories[0].provenance.model, record.model);
	assert.match(memories[0].provenance.sourceStepId, /^step-/);
	assert.equal(memories[0].provenance.actionId, undefined);
	assert.equal(memories[1].arguments.offset, 2);
});

test('an authored interrupt watcher cancels the exact body command before issuing its authored response', async () => {
	let release;
	let sequence = 2;
	const commands = [], cancelled = [];
	const run = setup({ executeAction: (command) => {
		commands.push(command);
		return commands.length === 1 ? new Promise((resolve) => { release = resolve; }) : Promise.resolve({ state: 'SUCCEEDED', reasonCode: 'DONE' });
	}, cancelAction: async (actionId) => { cancelled.push(actionId); release({ state: 'CANCELLED', reasonCode: 'INPUT_RELEASED' }); },
	refreshObservation: async () => ({ observation: observation(4), eventSequence: ++sequence }) });
	const pending = run.executor.run(record, { source: `${prefix}
		program.watch(() => player.state().health < 10, { mode: "interrupt" }, async () => { await player.wait(9); });
		await player.wait(100);
	` }, run.context);
	run.executor.onObservation(record, { observation: observation(4), eventSequence: 2, attention: true, priority: 'urgent', trigger: 'health_changed' });
	const result = await pending;
	assert.deepEqual(commands.map((command) => command.action.arguments.durationMs), [100, 9]);
	assert.deepEqual(cancelled, [commands[0].actionId]);
	assert.equal(result.receipts[0].state, 'CANCELLED');
	assert.equal(result.reasonCode, 'PROGRAM_IDLE');
});

test('continue attention waits for the current action and returns before any further command', async () => {
	let release;
	const commands = [];
	const run = setup({ executeAction: (command) => { commands.push(command); return new Promise((resolve) => { release = resolve; }); } });
	const pending = run.executor.run(record, { source: `${prefix} await player.wait(100); await player.wait(2);` }, run.context);
	run.executor.onObservation(record, { observation: observation(9), eventSequence: 2, attention: true, priority: 'urgent', trigger: 'health_changed' });
	assert.equal(run.cancels.length, 0);
	release({ state: 'SUCCEEDED', reasonCode: 'DONE' });
	const result = await pending;
	assert.equal(result.reasonCode, 'MODEL_DECISION_REQUIRED');
	assert.equal(result.trigger, 'health_changed');
	assert.equal(commands.length, 1);
	assert.equal(run.cancels.length, 0);
});

test('pause attention honors the model-authored cancellation policy', async () => {
	let release;
	const run = setup({ executeAction: () => new Promise((resolve) => { release = resolve; }),
		cancelAction: async () => { release({ state: 'CANCELLED', reasonCode: 'INPUT_RELEASED' }); } });
	const pending = run.executor.run(record, { source: 'program.onUnhandledAttention("pause_and_notify"); await player.wait(100); await player.wait(2);' }, run.context);
	run.executor.onObservation(record, { observation: observation(9), eventSequence: 2, attention: true, priority: 'urgent', trigger: 'health_changed' });
	const result = await pending;
	assert.equal(result.reasonCode, 'MODEL_DECISION_REQUIRED');
	assert.equal(result.actions, 1);
	assert.equal(result.receipts[0].state, 'CANCELLED');
});

test('action budget and missing fresh facts stop execution without adding a strategy or retry', async () => {
	const bounded = setup();
	const limited = await bounded.executor.run(record, { source: `${prefix} await player.wait(1); await player.wait(2);`, maxActions: 1 }, bounded.context);
	assert.equal(limited.reasonCode, 'PROGRAM_ACTION_LIMIT');
	assert.equal(bounded.commands.length, 1);
	const stale = setup({ refreshObservation: async () => ({ observation: observation(), eventSequence: 1 }) });
	const stopped = await stale.executor.run(record, { source: `${prefix} await player.wait(1); await player.wait(2);` }, stale.context);
	assert.equal(stopped.reasonCode, 'FRESH_OBSERVATION_REQUIRED');
	assert.equal(stale.commands.length, 1);
	assert.equal(stopped.receipts[0].state, 'SUCCEEDED');
});

test('deadlines cancel exact inputs and unresolved acknowledgements are reported as unknown', async () => {
	const timers = [];
	const cancelled = [];
	const run = setup({ executeAction: () => new Promise(() => {}), cancelAction: async (actionId) => { cancelled.push(actionId); } },
		{ setTimeoutFn: (callback, ms) => { const timer = { callback, ms }; timers.push(timer); return timer; }, clearTimeoutFn: () => {} });
	const pending = run.executor.run(record, { source: `${prefix} await player.wait(100);`, timeoutMs: 10 }, run.context);
	timers[0].callback();
	assert.equal(cancelled.length, 1);
	assert.equal(timers[1].ms, 5000);
	timers[1].callback();
	const result = await pending;
	assert.equal(result.state, 'UNKNOWN');
	assert.equal(result.reasonCode, 'PROGRAM_CANCEL_ACK_TIMEOUT');
	assert.equal(result.receipts.length, 0);
});

test('cancelled query results cannot release later body actions and finish remains a model request', async () => {
	let release;
	const run = setup({ inspect: () => new Promise((resolve) => { release = resolve; }) });
	const pending = run.executor.run(record, { source: `${prefix} await world.inspect({ section: "menu" }); await player.wait(1);` }, run.context);
	await run.executor.cancel(record.agentId);
	release({ state: 'SUCCEEDED', menu: null });
	await turn();
	assert.equal((await pending).state, 'CANCELLED');
	assert.equal(run.commands.length, 0);
	assert.equal(run.cancels.length, 0);
	const finished = await run.executor.run(record, { source: `${prefix} program.finish("ready for verification");` }, run.context);
	assert.equal(finished.finishRequested, true);
	assert.equal(finished.state, 'YIELDED');
	assert.equal(finished.reasonCode, 'PROGRAM_FINISH_REQUESTED');
});

test('sandbox errors and action callback failures never invoke a replacement provider or fabricate completion', async () => {
	const run = setup({ executeAction: async () => { throw Object.assign(new Error('transport unavailable'), { code: 'BRIDGE_DISCONNECTED' }); } });
	assert.throws(() => run.executor.run(record, { source: `${prefix} globalThis.fetch("invalid");` }, run.context));
	const result = await run.executor.run(record, { source: `${prefix} await player.wait(1); await player.wait(2);` }, run.context);
	assert.equal(result.state, 'UNKNOWN');
	assert.equal(result.reasonCode, 'BRIDGE_DISCONNECTED');
	assert.equal(result.actions, 1);
	assert.equal(result.receipts.length, 0);
});

test('nonterminal body callback results release the exact inputs and remain unknown', async () => {
	const run = setup({ executeAction: async () => ({ state: 'RUNNING', reasonCode: 'STARTED' }) });
	const result = await run.executor.run(record, { source: `${prefix} await player.wait(1); await player.wait(2);` }, run.context);
	await turn();
	assert.equal(result.state, 'UNKNOWN');
	assert.equal(result.reasonCode, 'INVALID_ACTION_RESULT');
	assert.equal(result.receipts.length, 0);
	assert.equal(run.cancels.length, 1);
	assert.equal(run.cancels[0].reason, 'INVALID_ACTION_RESULT');
});

test('program identities are unique in production and reproducible only with an injected replay session', async () => {
	const source = `${prefix} program.checkpoint("ready");`;
	const first = setup(), second = setup();
	const one = await first.executor.run(record, { source }, first.context);
	const two = await second.executor.run(record, { source }, second.context);
	assert.notEqual(one.programId, two.programId);
	const replayOne = setup({}, { sessionId: 'synthetic-replay' });
	const replayTwo = setup({}, { sessionId: 'synthetic-replay' });
	assert.equal((await replayOne.executor.run(record, { source }, replayOne.context)).programId,
		(await replayTwo.executor.run(record, { source }, replayTwo.context)).programId);
});
