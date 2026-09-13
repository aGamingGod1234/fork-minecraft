import assert from 'node:assert/strict';
import test from 'node:test';
import { mkdtemp, rm } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { ModelNotebook } from '../src/model-notebook.mjs';
import { RuntimeMemoryContext } from '../src/runtime-memory-context.mjs';

const record = { agentId: 'agent-a', goalRevision: 1, provider: 'codex', model: 'gpt-5.6-sol', reasoningEffort: 'high', serviceTier: 'priority' };
const provenance = { provider: record.provider, model: record.model, reasoningEffort: record.reasoningEffort, serviceTier: record.serviceTier,
	goalRevision: 1, programId: 'program-1', programVersion: 1, sourceStepId: 'step-4' };
const observation = { world: { worldId: 'world-one', dimension: 'minecraft:overworld' }, worldTick: 10 };
const dispatch = { actionId: 'session-one:action-1', actionType: 'wait', goalRevision: 1, arguments: { durationMs: 1 } };
function note(key = 'plan', text = 'Inspect the east bank.') { return { operation: 'write', arguments: { key, text }, provenance }; }

test('shared notes retain the selected author and support bounded pagination across modes and goal revisions', async () => {
	const memory = new RuntimeMemoryContext();
	memory.observe(record, observation);
	for (let index = 0; index < 5; index++) await memory.execute(record, note(`plan-${index}`));
	const nativePage = await memory.notebook.query(record.agentId, { worldId: memory.worldId(record), kind: 'notes', limit: 2 });
	assert.equal(nativePage.entries[0].provenance.model, record.model);
	assert.equal(nativePage.entries[0].provenance.sourceStepId, 'step-4');
	const later = { ...record, goalRevision: 2 };
	memory.observe(later, observation);
	const page = await memory.execute(later, { operation: 'query', arguments: { kind: 'notes', offset: nativePage.nextOffset, limit: 2 } });
	assert.equal(page.state, 'SUCCEEDED');
	assert.equal(page.total, 5);
	assert.deepEqual(page.entries.map((entry) => entry.key), ['plan-2', 'plan-1']);
	assert.equal(page.nextOffset, 4);
	assert.equal((await memory.notebook.query('another-agent', { worldId: 'world-one' })).total, 0);
});

test('note validation prevents profile spoofing, overlong content and hostile accessors before storage', async () => {
	const memory = new RuntimeMemoryContext();
	memory.observe(record, observation);
	await assert.rejects(memory.execute(record, { ...note(), provenance: { ...provenance, model: 'another-model' } }), { code: 'INVALID_MEMORY_PROVENANCE' });
	await assert.rejects(memory.execute(record, { ...note(), provenance: { ...provenance, goalRevision: 2 } }), { code: 'INVALID_MEMORY_PROVENANCE' });
	await assert.rejects(memory.execute(record, note('k'.repeat(129))));
	await assert.rejects(memory.execute(record, note('plan', 't'.repeat(2049))));
	await assert.rejects(memory.execute(record, { operation: 'query', arguments: { offset: -1 } }));
	let reads = 0;
	const unsafe = Object.defineProperty({}, 'provider', { enumerable: true, get() { reads++; return 'codex'; } });
	await assert.rejects(memory.execute(record, { ...note(), provenance: unsafe }));
	assert.equal(reads, 0);
	assert.equal((await memory.notebook.query(record.agentId, { worldId: 'world-one' })).total, 0);
	await memory.execute(record, note('max', 't'.repeat(2048)));
});

test('unknown worlds use unique runtime namespaces and stale or forgotten contexts cannot access notes', async () => {
	const notebook = new ModelNotebook();
	const first = new RuntimeMemoryContext({ notebook });
	const second = new RuntimeMemoryContext({ notebook });
	await assert.rejects(first.execute(record, note()), { code: 'WORLD_ID_REQUIRED' });
	first.observe(record, {}); second.observe(record, {});
	assert.notEqual(first.worldId(record), second.worldId(record));
	await first.execute(record, note());
	assert.equal((await second.execute(record, { operation: 'query', arguments: {} })).total, 0);
	assert.equal(first.worldId({ ...record, goalRevision: 2 }), null);
	first.forget(record.agentId);
	assert.equal(first.worldId(record), null);
});

test('receipts capture the dispatch world, remain unknown after disconnect, and accept only matching terminal evidence', async () => {
	const memory = new RuntimeMemoryContext();
	memory.observe(record, observation);
	assert.equal((await memory.recordDispatch(record, dispatch)).state, 'DISPATCHED');
	assert.equal((await memory.markUnknown(record.agentId, 'BRIDGE_DISCONNECTED'))[0].state, 'UNKNOWN');
	const unresolved = await memory.unresolved(record);
	assert.equal(unresolved.entries[0].actionId, dispatch.actionId);
	assert.deepEqual(unresolved.entries[0].arguments, dispatch.arguments);
	assert.equal(unresolved.entries[0].source, 'coordinator_uncertain');
	assert.equal(await memory.recordResult(record, { ...dispatch, actionId: 'unseen', state: 'SUCCEEDED', reasonCode: '' }), false);
	assert.equal(await memory.recordResult(record, { ...dispatch, goalRevision: 9, state: 'SUCCEEDED', reasonCode: '' }), false);
	assert.equal(await memory.recordResult(record, { ...dispatch, state: 'RUNNING', reasonCode: '' }), false);
	const later = { ...record, goalRevision: 2 };
	memory.observe(later, { world: { worldId: 'world-two' } });
	const terminal = { actionId: dispatch.actionId, goalRevision: 1, state: 'SUCCEEDED', reasonCode: '', executionStarted: true, physicalAttempted: false, actionObservation: { worldTick: 20 } };
	assert.equal(await memory.recordResult(later, terminal), true);
	const first = await memory.notebook.findReceipt(record.agentId, { actionId: dispatch.actionId });
	assert.equal(first.worldId, 'world-one');
	assert.equal(first.source, 'server_action_result');
	assert.equal(first.tick, 20);
	assert.equal(first.executionStarted, true);
	assert.equal(first.physicalAttempted, false);
	assert.equal(await memory.recordResult(later, terminal), true);
	assert.deepEqual(await memory.notebook.findReceipt(record.agentId, { actionId: dispatch.actionId }), first);
	assert.equal((await memory.execute(later, { operation: 'query', arguments: { kind: 'receipts' } })).total, 0);
});

test('an authoritative terminal replay reconciles a persisted dispatch after coordinator restart', async (t) => {
	const directory = await mkdtemp(join(tmpdir(), 'runtime-memory-'));
	t.after(() => rm(directory, { recursive: true, force: true }));
	const first = new RuntimeMemoryContext({ notebook: new ModelNotebook({ directory }) });
	first.observe(record, observation);
	await first.recordDispatch(record, dispatch);
	await first.markUnknown(undefined, 'COORDINATOR_STOPPED');
	const restored = new RuntimeMemoryContext({ notebook: new ModelNotebook({ directory }) });
	assert.notEqual(restored.sessionId, first.sessionId);
	assert.equal(await restored.recordResult(record, { actionId: dispatch.actionId, goalRevision: 1, state: 'CANCELLED', reasonCode: 'INPUT_RELEASED' }), true);
	const result = await restored.notebook.findReceipt(record.agentId, { actionId: dispatch.actionId });
	assert.equal(result.state, 'CANCELLED');
	assert.equal(result.source, 'server_action_result');
	assert.equal(result.worldId, 'world-one');
});

test('persisted unknown receipts release dispatch capacity and retained receipts accept late results', async () => {
	const memory = new RuntimeMemoryContext();
	memory.observe(record, observation);
	for (let index = 0; index < 300; index++) {
		const action = { ...dispatch, actionId: `lost-action-${index}` };
		await memory.recordDispatch(record, action);
		const unknown = await memory.markUnknown(record.agentId);
		assert.equal(unknown.length, 1);
		assert.equal(unknown[0].actionId, action.actionId);
		assert.equal(unknown[0].state, 'UNKNOWN');
	}
	assert.deepEqual(await memory.markUnknown(record.agentId), []);
	const unresolved = await memory.unresolved(record);
	assert.equal(unresolved.total, 128);
	assert.equal(unresolved.evictedReceipts, 172);
	assert.equal(await memory.recordResult(record, { actionId: 'lost-action-299', goalRevision: 1,
		state: 'SUCCEEDED', reasonCode: 'DONE' }), true);
	assert.equal((await memory.notebook.findReceipt(record.agentId, { actionId: 'lost-action-299' })).source, 'server_action_result');
	assert.equal((await memory.unresolved(record)).total, 127);
});

test('failed unknown persistence retains the dispatch for a subsequent retry', async () => {
	const notebook = new ModelNotebook();
	const persistUnknown = notebook.recordUnknown.bind(notebook);
	let fail = true;
	notebook.recordUnknown = (...args) => fail ? Promise.reject(new Error('disk unavailable')) : persistUnknown(...args);
	const memory = new RuntimeMemoryContext({ notebook });
	memory.observe(record, observation);
	await memory.recordDispatch(record, dispatch);
	await assert.rejects(memory.markUnknown(record.agentId), /disk unavailable/);
	assert.equal((await notebook.findReceipt(record.agentId, { actionId: dispatch.actionId })).state, 'DISPATCHED');
	await assert.rejects(memory.recordDispatch(record, { ...dispatch, arguments: { durationMs: 2 } }), { code: 'RECEIPT_CONFLICT' });
	fail = false;
	assert.equal((await memory.markUnknown(record.agentId))[0].state, 'UNKNOWN');
	assert.deepEqual(await memory.markUnknown(record.agentId), []);
});

test('a terminal result arriving during unknown persistence remains authoritative', async () => {
	const notebook = new ModelNotebook();
	const persistUnknown = notebook.recordUnknown.bind(notebook);
	const entered = Promise.withResolvers();
	const release = Promise.withResolvers();
	notebook.recordUnknown = async (...args) => {
		entered.resolve();
		await release.promise;
		return persistUnknown(...args);
	};
	const memory = new RuntimeMemoryContext({ notebook });
	memory.observe(record, observation);
	await memory.recordDispatch(record, dispatch);
	const unknown = memory.markUnknown(record.agentId);
	await entered.promise;
	assert.equal(await memory.recordResult(record, { ...dispatch, state: 'SUCCEEDED', reasonCode: 'DONE' }), true);
	release.resolve();
	assert.equal((await unknown)[0].state, 'SUCCEEDED');
	assert.deepEqual(await memory.markUnknown(record.agentId), []);
	assert.equal((await notebook.findReceipt(record.agentId, { actionId: dispatch.actionId })).source, 'server_action_result');
});
