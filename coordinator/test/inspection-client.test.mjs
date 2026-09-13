import assert from 'node:assert/strict';
import test from 'node:test';

import { InspectionClient } from '../src/inspection-client.mjs';

const record = (agentId = 'agent-a', goalRevision = 7) => ({ agentId, goalRevision });
const dispatched = () => new Promise((resolve) => setImmediate(resolve));

function fixture(t, send = null) {
	const sent = [];
	const client = new InspectionClient({
		send(type, agentId, payload, options) {
			const message = { type, agentId, payload, options };
			sent.push(message);
			return send?.(message);
		},
	});
	t.after(() => client.cancel());
	return { client, sent };
}

function reply(request, result = { section: 'player', health: 20 }, overrides = {}) {
	return {
		agentId: request.agentId,
		payload: { requestId: request.payload.requestId, goalRevision: request.payload.goalRevision, result },
		...overrides,
	};
}

test('inspection replies correlate by request id, agent and goal revision', async (t) => {
	const { client, sent } = fixture(t);
	const pending = client.request(record(), { section: 'player' });
	await dispatched();
	assert.equal(sent[0].type, 'inspection_request');
	assert.equal(client.accept(reply(sent[0], {}, { agentId: 'agent-b' })), false);
	assert.equal(client.accept({ agentId: 'agent-a', payload: { requestId: sent[0].payload.requestId, goalRevision: 8, result: {} } }), false);
	assert.equal(client.accept({ agentId: 'agent-a', payload: { requestId: 'unrelated', goalRevision: 7, result: {} } }), false);
	const result = { section: 'player', health: 13, observedAtEpochMs: 1_234 };
	assert.equal(client.accept(reply(sent[0], result)), true);
	assert.deepEqual(await pending, result);
	assert.equal(client.accept(reply(sent[0])), false);
});

test('concurrent inspections resolve out of order without exchanging samples', async (t) => {
	const { client, sent } = fixture(t);
	const first = client.request(record(), { section: 'player' });
	const second = client.request(record('agent-b'), { section: 'inventory' });
	await dispatched();
	assert.notEqual(sent[0].payload.requestId, sent[1].payload.requestId);
	client.accept(reply(sent[1], { items: ['minecraft:stone'] }));
	client.accept(reply(sent[0], { health: 17 }));
	assert.deepEqual(await first, { health: 17 });
	assert.deepEqual(await second, { items: ['minecraft:stone'] });
});

test('server inspection errors reject once and retire the correlation', async (t) => {
	const { client, sent } = fixture(t);
	const pending = client.request(record(), { section: 'block', x: 1, y: 64, z: 2 });
	const rejected = assert.rejects(pending, { code: 'TARGET_NOT_VISIBLE', message: 'Target is occluded' });
	await dispatched();
	const message = { agentId: 'agent-a', payload: { requestId: sent[0].payload.requestId, goalRevision: 7, error: { code: 'TARGET_NOT_VISIBLE', message: 'Target is occluded' } } };
	assert.equal(client.accept(message), true);
	await rejected;
	assert.equal(client.accept(message), false);
});

test('cancelling one agent rejects its inspections and ignores their late replies', async (t) => {
	const { client, sent } = fixture(t);
	const first = client.request(record(), { section: 'player' });
	const cancelled = assert.rejects(first, { code: 'GOAL_REPLACED' });
	const other = client.request(record('agent-b'), { section: 'player' });
	await dispatched();
	client.cancel('agent-a', 'GOAL_REPLACED');
	await cancelled;
	assert.equal(client.accept(reply(sent[0])), false);
	assert.equal(client.accept(reply(sent[1], { health: 12 })), true);
	assert.deepEqual(await other, { health: 12 });
});

test('cancellation before dispatch never sends the retired query', async (t) => {
	const { client, sent } = fixture(t);
	const pending = client.request(record(), { section: 'player' });
	const rejected = assert.rejects(pending, { code: 'INSPECTION_CANCELLED' });
	client.cancel('agent-a');
	await rejected;
	await dispatched();
	assert.equal(sent.length, 0);
});

test('dispatch snapshots agent authority and nested query arguments before its microtask', async (t) => {
	const { client, sent } = fixture(t);
	const mutableRecord = record();
	const query = { section: 'blocks', filters: { blockIds: ['minecraft:stone'] } };
	const pending = client.request(mutableRecord, query, { connectionEpoch: 3 });
	mutableRecord.agentId = 'agent-b';
	mutableRecord.goalRevision = 8;
	query.filters.blockIds[0] = 'minecraft:diamond_ore';
	await dispatched();
	assert.equal(sent[0].agentId, 'agent-a');
	assert.equal(sent[0].payload.goalRevision, 7);
	assert.deepEqual(sent[0].payload.query, { section: 'blocks', filters: { blockIds: ['minecraft:stone'] } });
	assert.deepEqual(sent[0].options, { connectionEpoch: 3 });
	client.accept(reply(sent[0]));
	await pending;
});

test('a replaced connection rejects an inspection before the bridge writes it', async (t) => {
	let activeEpoch = 3;
	const written = [];
	const client = new InspectionClient({ send(type, agentId, payload, { connectionEpoch }) {
		if (connectionEpoch !== activeEpoch) throw Object.assign(new Error('Connection was replaced'), { code: 'STALE_CONNECTION_EPOCH' });
		written.push({ type, agentId, payload });
	} });
	t.after(() => client.cancel());
	const pending = client.request(record(), { section: 'observation' }, { connectionEpoch: activeEpoch });
	const rejected = assert.rejects(pending, { code: 'STALE_CONNECTION_EPOCH' });
	activeEpoch = 4;
	await rejected;
	assert.deepEqual(written, []);
});

test('timeout releases a query and late samples cannot revive it', async (t) => {
	const { client, sent } = fixture(t);
	const keepAlive = setTimeout(() => {}, 1_000);
	t.after(() => clearTimeout(keepAlive));
	const pending = client.request(record(), { section: 'player' }, { timeoutMs: 5 });
	await assert.rejects(pending, { code: 'INSPECTION_TIMEOUT' });
	assert.equal(sent.length, 1);
	assert.equal(client.accept(reply(sent[0])), false);
});

test('send rejection retires the pending request even if a response later arrives', async (t) => {
	const { client, sent } = fixture(t, () => Promise.reject(Object.assign(new Error('Socket closed'), { code: 'BRIDGE_DISCONNECTED' })));
	await assert.rejects(client.request(record(), { section: 'player' }), { code: 'BRIDGE_DISCONNECTED' });
	assert.equal(client.accept(reply(sent[0])), false);
});

test('cancellation remains final when an in-flight bridge send fails later', async (t) => {
	let rejectSend;
	const { client, sent } = fixture(t, () => new Promise((_resolve, reject) => { rejectSend = reject; }));
	const pending = client.request(record(), { section: 'player' });
	const rejected = assert.rejects(pending, { code: 'BRIDGE_DISCONNECTED' });
	await dispatched();
	client.cancel(undefined, 'BRIDGE_DISCONNECTED');
	rejectSend(new Error('Late socket failure'));
	await rejected;
	await dispatched();
	assert.equal(client.accept(reply(sent[0])), false);
});

test('pending-query capacity is recovered after lifecycle cancellation', async (t) => {
	const { client, sent } = fixture(t);
	const pending = Array.from({ length: 64 }, () => client.request(record(), { section: 'player' }).catch((error) => error.code));
	await assert.rejects(client.request(record('agent-b'), { section: 'player' }), { code: 'INSPECTION_BUSY' });
	client.cancel();
	assert.deepEqual(await Promise.all(pending), Array(64).fill('INSPECTION_CANCELLED'));
	const next = client.request(record('agent-b'), { section: 'player' });
	await dispatched();
	client.accept(reply(sent.at(-1), { health: 19 }));
	assert.deepEqual(await next, { health: 19 });
});
