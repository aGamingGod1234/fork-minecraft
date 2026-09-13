import assert from 'node:assert/strict';
import test from 'node:test';

import { parseArenaScript } from '../src/arena-script/parser.mjs';
import { ArenaScriptEngine } from '../src/arena-script/program-engine.mjs';

function observation(overrides = {}) {
	return {
		player: { x: 0, y: 64, z: 0, health: 20 },
		items: [], entities: [], blocks: [],
		inventory: { items: [], tagCounts: { '#minecraft:logs': 0 } },
		...overrides,
	};
}

function engineFor(source, callbacks = {}) {
	const dispatched = [];
	const modelRequests = [];
	const cancelled = [];
	const engine = new ArenaScriptEngine({
		dispatch: (command) => dispatched.push(command),
		cancel: (actionId) => cancelled.push(actionId),
		requestModel: (context) => modelRequests.push(context),
		...callbacks,
	});
	engine.install({
		agentId: 'agent-a', goalRevision: 1, modelIdentity: 'model-a', programId: 'program-a', version: 1,
		compiled: parseArenaScript(source), observation: callbacks.initialObservation ?? observation(), eventSequence: 1,
	});
	return { engine, dispatched, cancelled, modelRequests };
}

function acknowledge(engine, dispatched, observationValue, eventSequence) {
	const command = dispatched.at(-1);
	engine.ingestActionResult({ actionId: command.actionId, state: 'SUCCEEDED', reasonCode: 'DONE', eventSequence });
	engine.ingestObservation({ observation: observationValue, eventSequence, attention: false });
}

test('inspection results resume their exact continuation without physical commands or fabricated provenance', () => {
	const queries = [];
	const run = engineFor(`program.onUnhandledAttention("continue_and_notify");
		const page = await world.inspect({ section: "inventory", offset: 0, limit: 16 });
		await player.wait(page.entries.length);
	`, { inspect: (query) => queries.push(query) });
	assert.equal(run.dispatched.length, 0);
	assert.equal(run.engine.snapshot().activeActionId, null);
	assert.equal(run.engine.snapshot().activeQueryId, queries[0].queryId);
	assert.equal(queries[0].provenance, undefined);
	run.engine.notifyAttention({ priority: 'urgent', trigger: 'inspection_attention' });
	assert.equal(run.modelRequests[0].activeActionId, null);
	run.engine.applyDirective({ ...run.modelRequests[0], directive: 'continue' });
	run.engine.ingestQueryResult({ queryId: 'stale', value: { state: 'SUCCEEDED', entries: [1] } });
	assert.equal(run.dispatched.length, 0);
	run.engine.ingestQueryResult({ queryId: queries[0].queryId, value: { state: 'SUCCEEDED', reasonCode: 'INSPECTED', entries: [1, 2] } });
	assert.equal(run.dispatched[0].action.arguments, 2);
	assert.equal(run.engine.snapshot().activeQueryId, null);
	run.engine.ingestQueryResult({ queryId: queries[0].queryId, value: { state: 'SUCCEEDED', entries: [1] } });
	assert.equal(run.dispatched.length, 1);
});

test('replacing a pending inspection fences its late result without cancelling a Minecraft action', () => {
	const queries = [];
	const run = engineFor('program.onUnhandledAttention("continue_and_notify"); await world.inspect({ section: "item", slot: 1 }); await player.wait(9);', { inspect: (query) => queries.push(query) });
	run.engine.install({ agentId: 'agent-a', goalRevision: 1, modelIdentity: 'model-a', programId: 'replacement', version: 2,
		compiled: parseArenaScript('program.onUnhandledAttention("continue_and_notify"); await player.wait(3);'), observation: observation(), eventSequence: 2 });
	assert.deepEqual(run.cancelled, []);
	assert.equal(run.dispatched[0].action.arguments, 3);
	run.engine.ingestQueryResult({ queryId: queries[0].queryId, value: { state: 'SUCCEEDED', item: { count: 1 } } });
	assert.equal(run.dispatched.length, 1);
});

test('a model-authored interrupt watcher can replace an in-flight read without body cancellation', () => {
	const queries = [];
	const run = engineFor(`program.onUnhandledAttention("continue_and_notify");
		program.watch(() => player.state().health < 10, { mode: "interrupt" }, async () => { await player.wait(4); });
		await world.inspect({ section: "inventory" });
	`, { inspect: (query) => queries.push(query) });
	run.engine.ingestObservation({ observation: observation({ player: { x: 0, y: 64, z: 0, health: 8 } }), eventSequence: 2 });
	assert.deepEqual(run.cancelled, []);
	assert.equal(run.dispatched[0].action.arguments, 4);
	assert.equal(run.dispatched[0].provenance.watcherId, 'watcher-0');
	run.engine.ingestQueryResult({ queryId: queries[0].queryId, value: { state: 'SUCCEEDED', entries: [] } });
	assert.equal(run.dispatched.length, 1);
});

test('inspection rejects unsafe result properties before reading them or losing its continuation', () => {
	const queries = [];
	const run = engineFor('program.onUnhandledAttention("continue_and_notify"); await world.inspect({ section: "menu" }); await player.wait(1);', { inspect: (query) => queries.push(query) });
	let reads = 0;
	const hostile = Object.defineProperty({}, 'state', { enumerable: true, get() { reads += 1; return 'SUCCEEDED'; } });
	assert.throws(() => run.engine.ingestQueryResult({ queryId: queries[0].queryId, value: hostile }), (error) => error.code === 'INVALID_FACTS');
	assert.equal(reads, 0);
	assert.equal(run.engine.snapshot().activeQueryId, queries[0].queryId);
	run.engine.ingestQueryResult({ queryId: queries[0].queryId, value: { state: 'FAILED', reasonCode: 'INVALID_INSPECTION' } });
	assert.equal(run.dispatched.length, 1);
});

test('a failed factual completion reopens a finished program for an urgent correction', () => {
	const run = engineFor('program.onUnhandledAttention("continue_and_notify"); program.finish("done");');
	assert.equal(run.engine.snapshot().status, 'FINISHED');
	run.engine.requestCorrection({
		trigger: 'completion_verification_failed',
		actionFailure: { actionType: 'complete_goal', state: 'FAILED', reasonCode: 'INVENTORY_MISSING' },
	});
	assert.equal(run.engine.snapshot().status, 'SUSPENDED');
	assert.equal(run.modelRequests.length, 1);
	assert.equal(run.modelRequests[0].priority, 'urgent');
	assert.equal(run.modelRequests[0].trigger, 'completion_verification_failed');
	assert.equal(run.modelRequests[0].actionFailure.reasonCode, 'INVENTORY_MISSING');
	run.engine.applyDirective({ ...run.modelRequests[0], directive: 'continue' });
	assert.equal(run.engine.snapshot().status, 'FINISHED', 'continuing a terminal correction re-arms factual verification');
	assert.equal(run.engine.snapshot().pendingRequestTrigger, null);
});

test('requests a continuation when the traced one-action mining program falls off its end', () => {
	const { engine, dispatched, modelRequests } = engineFor(`
		program.onUnhandledAttention("continue_and_notify");
		const mined = await tryResult(player.mine({ x: 47, y: 93, z: -23, expectedBlockId: "minecraft:stone", timeoutMs: 30_000 }));
		if (!mined.succeeded) program.checkpoint("mining failed");
		if (!mined.succeeded) program.checkpoint("mining still failed");
	`);
	assert.equal(dispatched.length, 1);
	assert.equal(dispatched[0].action.type, 'break_block');
	acknowledge(engine, dispatched, observation(), 2);
	assert.equal(dispatched.length, 1, 'the successful action must not be replayed after program exhaustion');
	assert.equal(engine.snapshot().status, 'ACTIVE');
	assert.equal(engine.snapshot().pendingRequestTrigger, 'program_exhausted');
	assert.equal(modelRequests.length, 1);
	assert.equal(modelRequests[0].trigger, 'program_exhausted');
	assert.equal(modelRequests[0].priority, 'urgent');
});

test('requests a continuation when a fresh program falls through before its first action', () => {
	const { engine, dispatched, modelRequests } = engineFor(`
		program.onUnhandledAttention("continue_and_notify");
		if (inventory.countTag("#minecraft:logs") > 0) program.finish("already has logs");
	`);
	assert.equal(dispatched.length, 0);
	assert.equal(engine.snapshot().status, 'ACTIVE');
	assert.equal(engine.snapshot().pendingRequestTrigger, 'program_exhausted');
	assert.equal(modelRequests.length, 1);
	assert.equal(modelRequests[0].trigger, 'program_exhausted');
});

test('picks up an observed dropped item by its stable identity', () => {
	const droppedItemId = '00000000-0000-0000-0000-000000000099';
	const { dispatched } = engineFor(`
		program.onUnhandledAttention("continue_and_notify");
		const droppedLog = world.nearest(world.items({ tag: "#minecraft:logs" }));
		if (droppedLog !== null) {
			await player.pickUpItem({ targetSelector: droppedLog.stableId });
		}
	`, { initialObservation: observation({
		items: [{ stableId: droppedItemId, itemId: 'minecraft:spruce_log', count: 1, x: 2, y: 64, z: 0, tags: ['#minecraft:logs'] }],
	}) });
	assert.equal(dispatched.length, 1);
	assert.equal(dispatched[0].action.type, 'pick_up_item');
	assert.deepEqual(Object.fromEntries(Object.entries(dispatched[0].action.arguments)), { targetSelector: droppedItemId });
});

test('measures a multi-tree pickup loop instead of assuming a tree yield or pickup range', () => {
	const source = `
		program.onUnhandledAttention("continue_and_notify");
		await program.repeatUntil(
			() => inventory.countTag("#minecraft:logs") >= 8,
			{ maxIterations: 8 },
			async () => {
				const tree = world.nearest(world.blocks({ tag: "#minecraft:logs" }));
				if (tree !== null) await player.mine({ x: tree.x, y: tree.y, z: tree.z, expectedBlockId: tree.blockId });
			}
		);
		program.finish("Collected at least eight logs");
	`;
	const { engine, dispatched } = engineFor(source, { initialObservation: observation({
		blocks: [{ stableId: 'tree-one', blockId: 'minecraft:oak_log', x: 4, y: 64, z: 0, tags: ['#minecraft:logs'] }],
	}) });
	assert.equal(dispatched.at(-1).action.type, 'break_block');
	acknowledge(engine, dispatched, observation({
		blocks: [{ stableId: 'tree-two', blockId: 'minecraft:oak_log', x: 10, y: 64, z: 0, tags: ['#minecraft:logs'] }],
		inventory: { items: [{ itemId: 'minecraft:oak_log', count: 5 }], tagCounts: { '#minecraft:logs': 5 } },
	}), 2);
	assert.equal(dispatched.at(-1).action.type, 'break_block');
	acknowledge(engine, dispatched, observation({
		inventory: { items: [{ itemId: 'minecraft:oak_log', count: 8 }], tagCounts: { '#minecraft:logs': 8 } },
	}), 3);
	assert.deepEqual(dispatched.map((row) => row.action.type), ['break_block', 'break_block']);
	assert.equal(engine.snapshot().status, 'FINISHED');
});

test('watchers fire on false-to-true edges and boundary handlers wait for the action result', () => {
	const { engine, dispatched } = engineFor(`
		program.onUnhandledAttention("continue_and_notify");
		program.watch(() => player.state().health < 20, { mode: "boundary" }, async () => { await player.wait(1); });
		await player.navigateTo({ x: 4, y: 64, z: 0, tolerance: 1, sprint: false, timeoutMs: 5_000 });
	`);
	engine.ingestObservation({ observation: observation({ player: { x: 0, y: 64, z: 0, health: 19 } }), eventSequence: 2, attention: true });
	engine.ingestObservation({ observation: observation({ player: { x: 0, y: 64, z: 0, health: 19 } }), eventSequence: 3, attention: true });
	assert.deepEqual(dispatched.map((row) => row.action.type), ['navigate_to']);
	acknowledge(engine, dispatched, observation({ player: { x: 4, y: 64, z: 0, health: 19 } }), 4);
	assert.deepEqual(dispatched.map((row) => row.action.type), ['navigate_to', 'wait']);
});

test('fact-domain watcher pruning matches always-evaluate edge and interrupt ordering', () => {
	const runSequence = (condition, prefix = '') => {
		const traces = [];
		const run = engineFor(`
			program.onUnhandledAttention("continue_and_notify");
			${prefix}
			program.watch(() => ${condition}, { mode: "interrupt" }, async () => { await player.wait(9); });
			await player.wait(1);
		`, { trace: (event, fields) => { if (event === 'watcher_fired') traces.push(fields.eventSequence); } });
		run.engine.ingestObservation({ observation: observation({ entities: [{ stableId: 'mob-1', type: 'minecraft:zombie', x: 2, y: 64, z: 0 }] }), eventSequence: 2 });
		run.engine.ingestObservation({ observation: observation({ player: { x: 0, y: 64, z: 0, health: 19 } }), eventSequence: 3 });
		run.engine.ingestActionResult({ actionId: run.dispatched[0].actionId, state: 'CANCELLED', reasonCode: 'INTERRUPTED', eventSequence: 4 });
		run.engine.ingestActionResult({ actionId: run.dispatched[1].actionId, state: 'SUCCEEDED', reasonCode: 'DONE', eventSequence: 5 });
		run.engine.ingestObservation({ observation: observation(), eventSequence: 5 });
		run.engine.ingestObservation({ observation: observation({ blocks: [{ stableId: 'block-1', blockId: 'minecraft:stone', x: 1, y: 64, z: 0 }] }), eventSequence: 6 });
		run.engine.ingestObservation({ observation: observation({ player: { x: 0, y: 64, z: 0, health: 19 } }), eventSequence: 7 });
		return {
			traces,
			cancelled: run.cancelled.length,
			actions: run.dispatched.map(({ action }) => ({ type: action.type, arguments: { ...action.arguments } })),
		};
	};
	const pruned = runSequence('player.state().health < 20');
	const alwaysEvaluated = runSequence('player.state().health < threshold', 'const threshold = 20;');
	assert.deepEqual(pruned, alwaysEvaluated);
	assert.deepEqual(pruned.traces, [3, 7]);
});

test('coalesces one pending latch per watcher and preserves the newest facts sequence', () => {
	const traces = [];
	const { engine, dispatched } = engineFor(`
		program.onUnhandledAttention("continue_and_notify");
		program.watch(() => player.state().health < 20, { mode: "boundary" }, async () => { await player.wait(9); });
		await player.wait(1);
	`, { trace: (event, fields) => traces.push({ event, ...fields }) });
	const base = dispatched.at(-1);
	engine.ingestObservation({ observation: observation({ player: { health: 19 } }), eventSequence: 2, attention: true });
	engine.ingestObservation({ observation: observation({ player: { health: 20 } }), eventSequence: 3, attention: false });
	engine.ingestObservation({ observation: observation({ player: { health: 19 } }), eventSequence: 4, attention: true });
	engine.ingestActionResult({ actionId: base.actionId, state: 'SUCCEEDED', reasonCode: 'DONE', eventSequence: 5 });
	engine.ingestObservation({ observation: observation({ player: { health: 19 } }), eventSequence: 5, attention: false });
	assert.deepEqual(dispatched.map((row) => row.action.arguments), [1, 9]);
	assert.equal(dispatched.at(-1).provenance.authorizingEventSequence, 4);
	assert.equal(traces.some((entry) => entry.event === 'watcher_coalesced' && entry.watcherId === 'watcher-0'), true);
});

test('cancellation transport failure converges to a paused fence and ignores late results', () => {
	const dispatched = [];
	const cancelled = [];
	const engine = new ArenaScriptEngine({
		dispatch: (command) => dispatched.push(command),
		cancel: (actionId) => cancelled.push(actionId),
		requestModel() {},
	});
	engine.install({
		agentId: 'agent-a', goalRevision: 1, modelIdentity: 'model-a', programId: 'program-a', version: 1,
		compiled: parseArenaScript('program.onUnhandledAttention("pause_and_notify"); await player.wait(1);'), observation: observation(), eventSequence: 1,
	});
	const active = dispatched[0];
	engine.suspend('operator');
	assert.deepEqual(cancelled, [active.actionId]);
	engine.failCancellation({ actionId: active.actionId, eventSequence: 2, reasonCode: 'CANCEL_SEND_FAILED' });
	assert.equal(engine.snapshot().status, 'SUSPENDED');
	assert.equal(engine.snapshot().activeActionId, null);
	engine.ingestActionResult({ actionId: active.actionId, state: 'CANCELLED', reasonCode: 'LATE', eventSequence: 3 });
	assert.equal(dispatched.length, 1);
});

test('carries the exact profile, trace, and watcher identity into watcher commands', () => {
	const dispatched = [];
	const engine = new ArenaScriptEngine({ dispatch: (command) => dispatched.push(command), cancel() {}, requestModel() {} });
	engine.install({
		agentId: 'agent-a', provider: 'codex', model: 'gpt-5.6-sol', reasoningEffort: 'high', serviceTier: 'fast',
		goalRevision: 1, modelIdentity: 'gpt-5.6-sol', traceId: 'trace-watch-1', programId: 'program-a', version: 1,
		compiled: parseArenaScript('program.onUnhandledAttention("continue_and_notify"); program.watch(() => player.state().health < 20, { mode: "boundary" }, async () => { await player.wait(9); }); await player.wait(1);'),
		observation: observation(), eventSequence: 1,
	});
	const base = dispatched[0];
	engine.ingestObservation({ observation: observation({ player: { health: 19 } }), eventSequence: 2, attention: true });
	engine.ingestActionResult({ actionId: base.actionId, state: 'SUCCEEDED', reasonCode: 'DONE', eventSequence: 2 });
	const watcher = dispatched.at(-1);
	assert.equal(watcher.provenance.provider, 'codex');
	assert.equal(watcher.provenance.model, 'gpt-5.6-sol');
	assert.equal(watcher.provenance.reasoningEffort, 'high');
	assert.equal(watcher.provenance.serviceTier, 'fast');
	assert.equal(watcher.provenance.traceId, 'trace-watch-1');
	assert.equal(watcher.provenance.watcherId, 'watcher-0');
});

test('interrupt watchers wait for cancellation acknowledgement and unmatched attention follows the authored policy', () => {
	const { engine, dispatched, cancelled, modelRequests } = engineFor(`
		program.onUnhandledAttention("pause_and_notify");
		program.watch(() => player.state().health < 20, { mode: "interrupt" }, async () => { await player.wait(1); });
		await player.navigateTo({ x: 4, y: 64, z: 0, tolerance: 1, sprint: false, timeoutMs: 5_000 });
	`);
	const active = dispatched.at(-1);
	engine.ingestObservation({ observation: observation({ player: { x: 0, y: 64, z: 0, health: 19 } }), eventSequence: 2, attention: true });
	assert.deepEqual(cancelled, [active.actionId]);
	assert.deepEqual(dispatched.map((row) => row.action.type), ['navigate_to']);
	engine.ingestActionResult({ actionId: active.actionId, state: 'CANCELLED', reasonCode: 'DAMAGE', eventSequence: 2 });
	assert.deepEqual(dispatched.map((row) => row.action.type), ['navigate_to', 'wait']);
	engine.ingestObservation({ observation: observation({ player: { x: 0, y: 64, z: 0, health: 20 } }), eventSequence: 3, attention: true });
	assert.equal(modelRequests.length, 1);
	assert.equal(engine.snapshot().status, 'SUSPENDING');
});

test('coalesces unmatched continue policy notifications and ignores stale events without commands', () => {
	const { engine, dispatched, modelRequests } = engineFor('program.onUnhandledAttention("continue_and_notify"); await player.wait(1);');
	engine.ingestObservation({ observation: observation(), eventSequence: 2, attention: true });
	engine.ingestObservation({ observation: observation(), eventSequence: 3, attention: true });
	engine.ingestObservation({ observation: observation(), eventSequence: 1, attention: true });
	assert.equal(modelRequests.length, 1);
	assert.equal(modelRequests[0].eventSequence, 2);
	assert.deepEqual(dispatched.map((row) => row.action.type), ['wait']);
});

test('coalesced attention keeps the highest-priority trigger metadata', () => {
	const { engine, modelRequests } = engineFor('program.onUnhandledAttention("continue_and_notify"); await player.wait(1);');
	engine.ingestObservation({ observation: observation(), eventSequence: 2, attention: true, priority: 'urgent', trigger: 'damage' });
	engine.ingestObservation({ observation: observation(), eventSequence: 3, attention: true, priority: 'ordinary', trigger: 'observation' });
	assert.equal(modelRequests.length, 1);
	assert.equal(modelRequests[0].priority, 'urgent');
	assert.equal(modelRequests[0].trigger, 'damage');
	engine.applyDirective({ directive: 'continue', ...modelRequests[0] });
	assert.equal(modelRequests.length, 2);
	assert.equal(modelRequests[1].priority, 'urgent');
	assert.equal(modelRequests[1].trigger, 'damage');
	assert.equal(modelRequests[1].eventSequence, 3);
});

test('requests one selected-model recovery after an identical deterministic action failure repeats', () => {
	const { engine, dispatched, modelRequests } = engineFor(`
		program.onUnhandledAttention("continue_and_notify");
		await program.repeatUntil(() => false, { maxIterations: 8 }, async () => {
			await player.craftInventory({ recipeId: "minecraft:planks", count: 1, timeoutMs: 5000 });
		});
	`);
	const first = dispatched.at(-1);
	engine.ingestActionResult({ actionId: first.actionId, state: 'FAILED', reasonCode: 'RECIPE_NOT_FOUND', eventSequence: 2 });
	engine.ingestObservation({ observation: observation(), eventSequence: 2, attention: false });
	const second = dispatched.at(-1);
	assert.notEqual(second.actionId, first.actionId);
	engine.ingestActionResult({ actionId: second.actionId, state: 'FAILED', reasonCode: 'RECIPE_NOT_FOUND', eventSequence: 3 });
	engine.ingestObservation({ observation: observation(), eventSequence: 3, attention: false });
	assert.equal(dispatched.length, 2, 'the failed command is not dispatched a third time');
	assert.equal(engine.snapshot().status, 'SUSPENDED');
	assert.equal(modelRequests.length, 1);
	assert.equal(modelRequests[0].decisionContext, 'program_action_failure');
	assert.equal(modelRequests[0].priority, 'urgent');
	assert.equal(modelRequests[0].trigger, 'action_failure');
	assert.deepEqual({ ...modelRequests[0].actionFailure, arguments: { ...modelRequests[0].actionFailure.arguments } }, {
		sourceStepId: second.provenance.stepId,
		actionType: 'craft_inventory',
		arguments: { recipeId: 'minecraft:planks', count: 1, timeoutMs: 5000 },
		state: 'FAILED',
		reasonCode: 'RECIPE_NOT_FOUND',
	});
});

test('fences a replacement behind cancellation and rejects an old action result by generation', () => {
	const { engine, dispatched, cancelled } = engineFor('program.onUnhandledAttention("continue_and_notify"); await player.wait(1);');
	const old = dispatched.at(-1);
	engine.install({
		agentId: 'agent-a', goalRevision: 1, modelIdentity: 'model-a', programId: 'program-b', version: 2,
		compiled: parseArenaScript('program.onUnhandledAttention("continue_and_notify"); await player.wait(2);'), observation: observation(), eventSequence: 2,
	});
	assert.deepEqual(cancelled, [old.actionId]);
	assert.equal(dispatched.length, 1);
	engine.ingestActionResult({ actionId: old.actionId, state: 'CANCELLED', reasonCode: 'REPLACED', eventSequence: 2 });
	assert.equal(dispatched.length, 2);
	assert.notEqual(dispatched[1].actionId, old.actionId);
	engine.ingestActionResult({ actionId: old.actionId, state: 'SUCCEEDED', reasonCode: 'LATE', eventSequence: 3 });
	assert.equal(dispatched.length, 2);
});

test('holds an action result until an authoritative observation at or after its result sequence', () => {
	const { engine, dispatched } = engineFor('program.onUnhandledAttention("continue_and_notify"); await player.wait(1); await player.wait(2);');
	const first = dispatched.at(-1);
	engine.ingestActionResult({ actionId: first.actionId, state: 'SUCCEEDED', reasonCode: 'DONE', eventSequence: 5 });
	engine.ingestObservation({ observation: observation(), eventSequence: 4, attention: false });
	assert.equal(dispatched.length, 1);
	engine.ingestObservation({ observation: observation(), eventSequence: 5, attention: false });
	assert.equal(dispatched.length, 2);
});

test('runs a latched boundary watcher before the base continuation and retains a transient edge', () => {
	const { engine, dispatched } = engineFor(`
		program.onUnhandledAttention("continue_and_notify");
		program.watch(() => player.state().health < 20, { mode: "boundary" }, async () => { await player.wait(9); });
		await player.wait(1); await player.wait(2);
	`);
	const first = dispatched.at(-1);
	engine.ingestObservation({ observation: observation({ player: { x: 0, y: 64, z: 0, health: 19 } }), eventSequence: 2, attention: true });
	engine.ingestObservation({ observation: observation({ player: { x: 0, y: 64, z: 0, health: 20 } }), eventSequence: 3, attention: false });
	engine.ingestActionResult({ actionId: first.actionId, state: 'SUCCEEDED', reasonCode: 'DONE', eventSequence: 3 });
	engine.ingestObservation({ observation: observation(), eventSequence: 3, attention: false });
	assert.deepEqual(dispatched.map((command) => command.action.arguments), [1, 9]);
});

test('keeps an immutable request identity seen by a one-argument model callback', () => {
	const requests = [];
	const { engine } = engineFor('program.onUnhandledAttention("continue_and_notify"); await player.wait(1);', {
		requestModel: (context) => { requests.push(context); },
	});
	engine.ingestObservation({ observation: observation(), eventSequence: 2, attention: true });
	engine.ingestObservation({ observation: observation(), eventSequence: 3, attention: true });
	assert.equal(requests.length, 1);
	assert.ok(Object.isFrozen(requests[0]));
	assert.equal(requests[0].eventSequence, 2);
	engine.applyDirective({ directive: 'pause', ...requests[0] });
	assert.equal(requests.length, 2);
	assert.equal(requests[1].eventSequence, 3);
	assert.equal(engine.snapshot().status, 'ACTIVE');
});

test('rejects incomplete provenance before creating a VM or dispatching', () => {
	const engine = new ArenaScriptEngine({ dispatch() { assert.fail('must not dispatch'); }, cancel() {}, requestModel() {} });
	assert.throws(() => engine.install({ agentId: '', goalRevision: 1, modelIdentity: 'model-a', programId: 'p', version: 1, compiled: parseArenaScript('program.onUnhandledAttention("continue_and_notify");'), observation: observation(), eventSequence: 1 }), TypeError);
	assert.equal(engine.snapshot().status, 'IDLE');
});

test('rearmer watcher edges after a false observation and disposal waits for cancellation', () => {
	const { engine, dispatched, cancelled } = engineFor(`
		program.onUnhandledAttention("continue_and_notify");
		program.watch(() => player.state().health < 20, { mode: "interrupt" }, async () => { await player.wait(9); });
		await player.wait(1);
	`);
	const base = dispatched.at(-1);
	engine.ingestObservation({ observation: observation({ player: { x: 0, y: 64, z: 0, health: 19 } }), eventSequence: 2, attention: true });
	engine.ingestActionResult({ actionId: base.actionId, state: 'CANCELLED', reasonCode: 'DAMAGE', eventSequence: 2 });
	const firstReaction = dispatched.at(-1);
	engine.ingestActionResult({ actionId: firstReaction.actionId, state: 'SUCCEEDED', reasonCode: 'DONE', eventSequence: 3 });
	engine.ingestObservation({ observation: observation({ player: { x: 0, y: 64, z: 0, health: 20 } }), eventSequence: 3, attention: false });
	engine.ingestObservation({ observation: observation({ player: { x: 0, y: 64, z: 0, health: 19 } }), eventSequence: 4, attention: true });
	assert.equal(dispatched.filter((command) => command.action.arguments === 9).length, 2);
	const active = dispatched.at(-1);
	engine.dispose();
	assert.deepEqual(cancelled, [base.actionId, active.actionId]);
	assert.notEqual(engine.snapshot().status, 'IDLE');
	engine.ingestActionResult({ actionId: active.actionId, state: 'CANCELLED', reasonCode: 'DISPOSED', eventSequence: 4 });
	assert.equal(engine.snapshot().status, 'IDLE');
});

test('drains multiple boundary watchers in edge order before resuming the base continuation', () => {
	const { engine, dispatched } = engineFor(`
		program.onUnhandledAttention("continue_and_notify");
		program.watch(() => player.state().health < 20, { mode: "boundary" }, async () => { await player.wait(9); });
		program.watch(() => player.state().health < 19, { mode: "boundary" }, async () => { await player.wait(8); });
		await player.wait(1); await player.wait(2);
	`);
	const base = dispatched.at(-1);
	engine.ingestObservation({ observation: observation({ player: { x: 0, y: 64, z: 0, health: 10 } }), eventSequence: 2, attention: true });
	engine.ingestActionResult({ actionId: base.actionId, state: 'SUCCEEDED', reasonCode: 'DONE', eventSequence: 2 });
	engine.ingestObservation({ observation: observation({ player: { x: 0, y: 64, z: 0, health: 10 } }), eventSequence: 2, attention: false });
	const first = dispatched.at(-1);
	engine.ingestActionResult({ actionId: first.actionId, state: 'SUCCEEDED', reasonCode: 'DONE', eventSequence: 3 });
	engine.ingestObservation({ observation: observation({ player: { x: 0, y: 64, z: 0, health: 10 } }), eventSequence: 3, attention: false });
	const second = dispatched.at(-1);
	engine.ingestActionResult({ actionId: second.actionId, state: 'SUCCEEDED', reasonCode: 'DONE', eventSequence: 4 });
	engine.ingestObservation({ observation: observation({ player: { x: 0, y: 64, z: 0, health: 10 } }), eventSequence: 4, attention: false });
	assert.deepEqual(dispatched.map((command) => command.action.arguments), [1, 9, 8, 2]);
	assert.match(first.provenance.source, /^watcher:watcher-0$/);
	assert.match(second.provenance.source, /^watcher:watcher-1$/);
});

test('reissues exactly the newest unmatched attention request when an older response arrives', () => {
	const { engine, modelRequests } = engineFor('program.onUnhandledAttention("continue_and_notify"); await player.wait(1);');
	engine.ingestObservation({ observation: observation(), eventSequence: 2, attention: true });
	engine.ingestObservation({ observation: observation(), eventSequence: 3, attention: true });
	assert.equal(modelRequests.length, 1);
	engine.applyDirective({ directive: 'continue', ...modelRequests[0] });
	assert.equal(modelRequests.length, 2);
	assert.equal(modelRequests[1].eventSequence, 3);
	assert.notEqual(modelRequests[0], modelRequests[1]);
});

test('late continue resumes a cancelled pause-and-notify command only after a fresh observation', () => {
	const { engine, dispatched, modelRequests } = engineFor('program.onUnhandledAttention("pause_and_notify"); await player.wait(1); await player.wait(2);');
	const first = dispatched.at(-1);
	engine.ingestObservation({ observation: observation(), eventSequence: 2, attention: true });
	engine.ingestActionResult({ actionId: first.actionId, state: 'CANCELLED', reasonCode: 'ATTENTION', eventSequence: 2 });
	assert.equal(engine.snapshot().status, 'SUSPENDED');
	engine.applyDirective({ directive: 'continue', ...modelRequests[0] });
	assert.equal(modelRequests.length, 2);
	engine.applyDirective({ directive: 'continue', ...modelRequests[1] });
	assert.equal(engine.snapshot().status, 'ACTIVE');
	assert.deepEqual(dispatched.map((command) => command.action.arguments), [1, 2]);
	engine.ingestObservation({ observation: observation(), eventSequence: 2, attention: false });
	assert.deepEqual(dispatched.map((command) => command.action.arguments), [1, 2]);
});

test('drains simultaneous interrupt handlers after one cancellation and carries their facts sequence', () => {
	const { engine, dispatched, cancelled } = engineFor(`
		program.onUnhandledAttention("continue_and_notify");
		program.watch(() => player.state().health < 20, { mode: "interrupt" }, async () => { await player.wait(9); await player.wait(7); });
		program.watch(() => player.state().health < 19, { mode: "interrupt" }, async () => { await player.wait(8); });
		await player.wait(1);
	`);
	const base = dispatched.at(-1);
	engine.ingestObservation({ observation: observation({ player: { x: 0, y: 64, z: 0, health: 10 } }), eventSequence: 5, attention: true });
	assert.deepEqual(cancelled, [base.actionId]);
	engine.ingestActionResult({ actionId: base.actionId, state: 'CANCELLED', reasonCode: 'DAMAGE', eventSequence: 5 });
	assert.equal(dispatched.at(-1).provenance.executionFactsSequence, 5);
	acknowledge(engine, dispatched, observation({ player: { x: 0, y: 64, z: 0, health: 10 } }), 6);
	assert.equal(dispatched.at(-1).action.arguments, 7);
	assert.equal(dispatched.at(-1).provenance.executionFactsSequence, 6);
	acknowledge(engine, dispatched, observation({ player: { x: 0, y: 64, z: 0, health: 10 } }), 7);
	assert.equal(dispatched.at(-1).action.arguments, 8);
});

test('rejects replacement authority supplied by a remote directive and retains the active program', () => {
	const { engine, dispatched, cancelled, modelRequests } = engineFor('program.onUnhandledAttention("continue_and_notify"); await player.wait(1);');
	engine.ingestObservation({ observation: observation(), eventSequence: 2, attention: true });
	engine.applyDirective({
		directive: 'replace', ...modelRequests[0], install: {
			agentId: 'spoofed-agent', goalRevision: 9, modelIdentity: 'spoofed-model', programId: 'spoofed', version: 9,
			eventSequence: 99, observation: observation(), compiled: parseArenaScript('program.onUnhandledAttention("continue_and_notify");'),
		},
	});
	assert.equal(cancelled.length, 0);
	assert.equal(dispatched.length, 1);
	assert.equal(engine.snapshot().programId, 'program-a');
});

test('trusted lifecycle installs advance goals while pending installs reject downgrades', () => {
	const { engine, dispatched, cancelled } = engineFor('program.onUnhandledAttention("continue_and_notify"); await player.wait(1);');
	const active = dispatched.at(-1);
	engine.install({
		agentId: 'agent-a', goalRevision: 2, modelIdentity: 'model-a', programId: 'goal-two', version: 0,
		compiled: parseArenaScript('program.onUnhandledAttention("continue_and_notify"); await player.wait(2);'), observation: observation(), eventSequence: 2,
	});
	assert.deepEqual(cancelled, [active.actionId]);
	engine.install({
		agentId: 'agent-a', goalRevision: 1, modelIdentity: 'model-a', programId: 'stale', version: 99,
		compiled: parseArenaScript('program.onUnhandledAttention("continue_and_notify"); await player.wait(9);'), observation: observation(), eventSequence: 3,
	});
	engine.ingestActionResult({ actionId: active.actionId, state: 'CANCELLED', reasonCode: 'REPLACED', eventSequence: 3 });
	assert.equal(engine.snapshot().programId, 'goal-two');
});

test('trusted lifecycle epochs invalidate an in-flight model response before it can override install or suspension', () => {
	const first = engineFor('program.onUnhandledAttention("continue_and_notify"); await player.wait(1);');
	first.engine.ingestObservation({ observation: observation(), eventSequence: 2, attention: true });
	const request = first.modelRequests[0];
	const active = first.dispatched.at(-1);
	first.engine.install({
		agentId: 'agent-a', goalRevision: 1, modelIdentity: 'model-a', programId: 'trusted', version: 2,
		compiled: parseArenaScript('program.onUnhandledAttention("continue_and_notify"); await player.wait(9);'), observation: observation(), eventSequence: 3,
	});
	first.engine.applyDirective({ directive: 'pause', ...request });
	first.engine.ingestActionResult({ actionId: active.actionId, state: 'CANCELLED', reasonCode: 'REPLACED', eventSequence: 3 });
	assert.equal(first.engine.snapshot().programId, 'trusted');

	const second = engineFor('program.onUnhandledAttention("continue_and_notify"); await player.wait(1);');
	second.engine.ingestObservation({ observation: observation(), eventSequence: 2, attention: true });
	const suspendedRequest = second.modelRequests[0];
	second.engine.suspend('trusted_stop');
	second.engine.applyDirective({ directive: 'continue', ...suspendedRequest });
	assert.equal(second.engine.snapshot().status, 'SUSPENDING');
});

test('an action result fences an older request and replacement waits for facts at the fenced sequence', () => {
	const { engine, dispatched, modelRequests, cancelled } = engineFor('program.onUnhandledAttention("continue_and_notify"); await player.wait(1); await player.wait(2);');
	const active = dispatched.at(-1);
	engine.ingestObservation({ observation: observation(), eventSequence: 2, attention: true });
	engine.ingestActionResult({ actionId: active.actionId, state: 'SUCCEEDED', reasonCode: 'DONE', eventSequence: 3 });
	engine.applyDirective({ directive: 'replace', ...modelRequests[0], install: { programId: 'facts-fenced', version: 2, compiled: parseArenaScript('program.onUnhandledAttention("continue_and_notify"); await player.wait(9);') } });
	assert.equal(modelRequests.length, 2);
	assert.equal(modelRequests[1].eventSequence, 3);
	engine.applyDirective({ directive: 'replace', ...modelRequests[1], install: { programId: 'facts-fenced', version: 2, compiled: parseArenaScript('program.onUnhandledAttention("continue_and_notify"); await player.wait(9);') } });
	assert.equal(cancelled.length, 0);
	assert.equal(engine.snapshot().programId, 'program-a');
	engine.ingestObservation({ observation: observation(), eventSequence: 3, attention: false });
	assert.equal(engine.snapshot().programId, 'facts-fenced');
	assert.equal(dispatched.at(-1).action.arguments, 9);
});

test('idle pause-and-notify suspension remains resumable by its exact later continue directive', () => {
	const { engine, modelRequests } = engineFor('program.onUnhandledAttention("pause_and_notify");');
	engine.ingestObservation({ observation: observation(), eventSequence: 2, attention: true });
	assert.equal(engine.snapshot().status, 'SUSPENDED');
	engine.applyDirective({ directive: 'continue', ...modelRequests[0] });
	assert.equal(engine.snapshot().status, 'ACTIVE');
});

test('same-version lifecycle input only refreshes an identical immutable compiled program', () => {
	const compiled = parseArenaScript('program.onUnhandledAttention("continue_and_notify"); await player.wait(1);');
	const dispatched = []; const cancelled = [];
	const engine = new ArenaScriptEngine({ dispatch: (command) => dispatched.push(command), cancel: (actionId) => cancelled.push(actionId), requestModel() {} });
	engine.install({ agentId: 'agent-a', goalRevision: 1, modelIdentity: 'model-a', programId: 'program-a', version: 1, compiled, observation: observation(), eventSequence: 1 });
	engine.install({ agentId: 'agent-a', goalRevision: 1, modelIdentity: 'model-a', programId: 'program-a', version: 1, compiled, observation: observation({ player: { x: 1, y: 64, z: 0, health: 20 } }), eventSequence: 2 });
	assert.equal(cancelled.length, 0);
	engine.install({ agentId: 'agent-a', goalRevision: 1, modelIdentity: 'model-a', programId: 'program-a', version: 1, compiled: parseArenaScript('program.onUnhandledAttention("continue_and_notify"); await player.wait(2);'), observation: observation(), eventSequence: 3 });
	engine.install({ agentId: 'agent-a', goalRevision: 1, modelIdentity: 'model-a', programId: 'changed-id', version: 1, compiled, observation: observation(), eventSequence: 4 });
	assert.equal(engine.snapshot().programId, 'program-a');
	assert.deepEqual(dispatched.map((command) => command.action.arguments), [1]);
});

test('a command labels the facts sequence actually available when a newer result has no matching observation', () => {
	const { engine, dispatched } = engineFor('program.onUnhandledAttention("continue_and_notify"); await player.wait(1);');
	const first = dispatched.at(-1);
	engine.ingestActionResult({ actionId: first.actionId, state: 'SUCCEEDED', reasonCode: 'DONE', eventSequence: 5 });
	engine.install({
		agentId: 'agent-a', goalRevision: 1, modelIdentity: 'model-a', programId: 'replacement', version: 2,
		compiled: parseArenaScript('program.onUnhandledAttention("continue_and_notify"); await player.wait(9);'), observation: observation(), eventSequence: 2,
	});
	assert.equal(dispatched.at(-1).provenance.eventSequence, 5);
	assert.equal(dispatched.at(-1).provenance.factsEventSequence, 2);
});

test('a facts advance at an already-fenced event reissues the request instead of accepting stale facts', () => {
	const { engine, dispatched, modelRequests } = engineFor('program.onUnhandledAttention("continue_and_notify"); await player.wait(1);');
	const active = dispatched.at(-1);
	engine.ingestObservation({ observation: observation(), eventSequence: 2, attention: true });
	engine.ingestActionResult({ actionId: active.actionId, state: 'SUCCEEDED', reasonCode: 'DONE', eventSequence: 3 });
	engine.applyDirective({ directive: 'continue', ...modelRequests[0] });
	assert.equal(modelRequests[1].eventSequence, 3);
	assert.equal(modelRequests[1].factsSequence, 2);
	engine.ingestObservation({ observation: observation(), eventSequence: 3, attention: false });
	engine.applyDirective({ directive: 'continue', ...modelRequests[1] });
	assert.equal(modelRequests.length, 3);
	assert.equal(modelRequests[2].eventSequence, 3);
	assert.equal(modelRequests[2].factsSequence, 3);
});

test('an action result resumes immediately when its authoritative observation already arrived', () => {
	const { engine, dispatched } = engineFor('program.onUnhandledAttention("continue_and_notify"); await player.wait(1); await player.wait(2);');
	const active = dispatched.at(-1);
	engine.ingestObservation({ observation: observation(), eventSequence: 2, attention: false });
	engine.ingestActionResult({ actionId: active.actionId, state: 'SUCCEEDED', reasonCode: 'DONE', eventSequence: 2 });
	assert.deepEqual(dispatched.map((command) => command.action.arguments), [1, 2]);
});

test('trusted suspend invalidates an idle pause-and-notify response even after it is already suspended', () => {
	const { engine, modelRequests } = engineFor('program.onUnhandledAttention("pause_and_notify");');
	engine.ingestObservation({ observation: observation(), eventSequence: 2, attention: true });
	const request = modelRequests[0];
	const before = engine.snapshot().lifecycleEpoch;
	engine.suspend('trusted_stop');
	assert.equal(engine.snapshot().lifecycleEpoch, before + 1);
	engine.applyDirective({ directive: 'continue', ...request });
	assert.equal(engine.snapshot().status, 'SUSPENDED');
});

test('terminal non-cancelled results complete queued install, dispose, and interrupt watcher work', () => {
	const installed = engineFor('program.onUnhandledAttention("continue_and_notify"); await player.wait(1);');
	const first = installed.dispatched.at(-1);
	installed.engine.install({ agentId: 'agent-a', goalRevision: 1, modelIdentity: 'model-a', programId: 'next', version: 2, compiled: parseArenaScript('program.onUnhandledAttention("continue_and_notify"); await player.wait(9);'), observation: observation(), eventSequence: 2 });
	installed.engine.ingestActionResult({ actionId: first.actionId, state: 'SUCCEEDED', reasonCode: 'DONE', eventSequence: 2 });
	assert.equal(installed.engine.snapshot().programId, 'next');

	const disposed = engineFor('program.onUnhandledAttention("continue_and_notify"); await player.wait(1);');
	const second = disposed.dispatched.at(-1);
	disposed.engine.dispose();
	disposed.engine.ingestActionResult({ actionId: second.actionId, state: 'FAILED', reasonCode: 'BLOCKED', eventSequence: 1 });
	assert.equal(disposed.engine.snapshot().status, 'IDLE');

	const watched = engineFor('program.onUnhandledAttention("continue_and_notify"); program.watch(() => player.state().health < 20, { mode: "interrupt" }, async () => { await player.wait(9); }); await player.wait(1);');
	const third = watched.dispatched.at(-1);
	watched.engine.ingestObservation({ observation: observation({ player: { x: 0, y: 64, z: 0, health: 19 } }), eventSequence: 2, attention: true });
	watched.engine.ingestActionResult({ actionId: third.actionId, state: 'SUCCEEDED', reasonCode: 'DONE', eventSequence: 2 });
	assert.equal(watched.dispatched.at(-1).action.arguments, 9);
});

test('a response for a completed action cannot cancel the successor continuation', () => {
	const { engine, dispatched, cancelled, modelRequests } = engineFor('program.onUnhandledAttention("continue_and_notify"); await player.wait(1); await player.wait(2);');
	const first = dispatched.at(-1);
	engine.ingestObservation({ observation: observation(), eventSequence: 2, attention: true });
	const firstRequest = modelRequests[0];
	engine.ingestActionResult({ actionId: first.actionId, state: 'SUCCEEDED', reasonCode: 'DONE', eventSequence: 2 });
	const second = dispatched.at(-1);
	assert.notEqual(second.actionId, first.actionId);
	engine.applyDirective({ directive: 'pause', ...firstRequest });
	assert.equal(cancelled.length, 0);
	assert.equal(modelRequests.length, 2);
	assert.equal(modelRequests[1].activeActionId, second.actionId);
	engine.applyDirective({ directive: 'pause', ...modelRequests[1] });
	assert.deepEqual(cancelled, [second.actionId]);
});

test('identical same-version facts refresh preserves the selected-model turn and rejects an older refresh', () => {
	const compiled = parseArenaScript('program.onUnhandledAttention("continue_and_notify"); await player.wait(1);');
	const requests = [];
	const engine = new ArenaScriptEngine({ dispatch() {}, cancel() {}, requestModel: (context) => requests.push(context) });
	engine.install({ agentId: 'agent-a', goalRevision: 1, modelIdentity: 'model-a', programId: 'program-a', version: 1, compiled, observation: observation(), eventSequence: 1 });
	engine.ingestObservation({ observation: observation(), eventSequence: 2, attention: true });
	engine.install({ agentId: 'agent-a', goalRevision: 1, modelIdentity: 'model-a', programId: 'program-a', version: 1, compiled, observation: observation(), eventSequence: 3 });
	engine.applyDirective({ directive: 'continue', ...requests[0] });
	assert.equal(requests.length, 2);
	assert.equal(requests[1].factsSequence, 3);
	engine.install({ agentId: 'agent-a', goalRevision: 1, modelIdentity: 'model-a', programId: 'program-a', version: 1, compiled, observation: observation(), eventSequence: 2 });
	assert.equal(requests.length, 2);
});

test('an exact interrupt cancellation acknowledgement may arrive after a newer observation', () => {
	const { engine, dispatched } = engineFor('program.onUnhandledAttention("continue_and_notify"); program.watch(() => player.state().health < 20, { mode: "interrupt" }, async () => { await player.wait(9); }); await player.wait(1);');
	const active = dispatched.at(-1);
	engine.ingestObservation({ observation: observation({ player: { x: 0, y: 64, z: 0, health: 19 } }), eventSequence: 2, attention: true });
	engine.ingestObservation({ observation: observation({ player: { x: 0, y: 64, z: 0, health: 19 } }), eventSequence: 3, attention: false });
	engine.ingestActionResult({ actionId: active.actionId, state: 'CANCELLED', reasonCode: 'DAMAGE', eventSequence: 2 });
	assert.equal(dispatched.at(-1).action.arguments, 9);
	assert.equal(engine.snapshot().eventSequence, 3);
});

test('a same-version facts refresh caches while suspended without running watcher work', () => {
	const compiled = parseArenaScript('program.onUnhandledAttention("continue_and_notify"); program.watch(() => player.state().health < 20, { mode: "boundary" }, async () => { await player.wait(9); }); await player.wait(1);');
	const dispatched = [];
	const engine = new ArenaScriptEngine({ dispatch: (command) => dispatched.push(command), cancel() {}, requestModel() {} });
	engine.install({ agentId: 'agent-a', goalRevision: 1, modelIdentity: 'model-a', programId: 'program-a', version: 1, compiled, observation: observation(), eventSequence: 1 });
	engine.suspend('operator');
	const active = dispatched.at(-1);
	engine.ingestActionResult({ actionId: active.actionId, state: 'CANCELLED', reasonCode: 'OPERATOR', eventSequence: 1 });
	assert.equal(engine.snapshot().status, 'SUSPENDED');
	engine.install({ agentId: 'agent-a', goalRevision: 1, modelIdentity: 'model-a', programId: 'program-a', version: 1, compiled, observation: observation({ player: { x: 0, y: 64, z: 0, health: 19 } }), eventSequence: 2 });
	assert.equal(engine.snapshot().factsSequence, 2);
	assert.equal(engine.snapshot().status, 'SUSPENDED');
	assert.deepEqual(dispatched.map((command) => command.action.arguments), [1]);
});

test('results must be nonnegative and cannot predate the exact action dispatch sequence', () => {
	const { engine, dispatched } = engineFor('program.onUnhandledAttention("continue_and_notify"); await player.wait(1); await player.wait(2);');
	const first = dispatched.at(-1);
	engine.ingestActionResult({ actionId: first.actionId, state: 'SUCCEEDED', reasonCode: 'NEGATIVE', eventSequence: -1 });
	assert.equal(engine.snapshot().activeActionId, first.actionId);
	engine.ingestObservation({ observation: observation(), eventSequence: 3, attention: false });
	engine.ingestActionResult({ actionId: first.actionId, state: 'SUCCEEDED', reasonCode: 'PREDATES_DISPATCH', eventSequence: 0 });
	assert.equal(engine.snapshot().activeActionId, first.actionId);
	engine.ingestActionResult({ actionId: first.actionId, state: 'SUCCEEDED', reasonCode: 'DONE', eventSequence: 1 });
	assert.deepEqual(dispatched.map((command) => command.action.arguments), [1, 2]);
	assert.equal(engine.snapshot().eventSequence, 3);
	assert.equal(engine.snapshot().factsSequence, 3);
});

test('an exact request failure never resumes a pause-and-notify program', () => {
	const { engine, dispatched, modelRequests } = engineFor('program.onUnhandledAttention("pause_and_notify"); await player.wait(1); await player.wait(2);');
	const first = dispatched.at(-1);
	engine.ingestObservation({ observation: observation(), eventSequence: 2, attention: true });
	engine.ingestActionResult({ actionId: first.actionId, state: 'CANCELLED', reasonCode: 'ATTENTION', eventSequence: 2 });
	engine.failDirectiveRequest(modelRequests[0]);
	assert.equal(engine.snapshot().status, 'SUSPENDED');
	assert.deepEqual(dispatched.map((command) => command.action.arguments), [1]);
});

test('an exact failed request promotes only its newer coalesced attention', () => {
	const { engine, modelRequests } = engineFor('program.onUnhandledAttention("continue_and_notify"); await player.wait(1);');
	engine.ingestObservation({ observation: observation(), eventSequence: 2, attention: true });
	engine.ingestObservation({ observation: observation(), eventSequence: 3, attention: true });
	engine.failDirectiveRequest(modelRequests[0]);
	assert.equal(modelRequests.length, 2);
	assert.equal(modelRequests[1].eventSequence, 3);
	engine.failDirectiveRequest(modelRequests[0]);
	assert.equal(modelRequests.length, 2);
});
