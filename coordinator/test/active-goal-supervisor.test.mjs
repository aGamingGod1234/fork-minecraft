import assert from 'node:assert/strict';
import test from 'node:test';

import { ActiveGoalSupervisor } from '../src/active-goal-supervisor.mjs';

const key = Object.freeze({
	agentId: 'luna',
	goalRevision: 4,
	lifecycleGeneration: 2,
	sessionEpoch: 7,
	profileFingerprint: `sha256:${'a'.repeat(64)}`,
});

function timerFixture(requests = []) {
	let nextId = 0;
	const timers = new Map();
	const supervisor = new ActiveGoalSupervisor({
		clock: () => 0,
		requestObservation: (requested, reason) => requests.push({ requested, reason }),
		schedule(callback, delay) {
			const handle = { id: ++nextId };
			timers.set(handle.id, { callback, delay });
			return handle;
		},
		cancelSchedule: (handle) => timers.delete(handle?.id),
	});
	return { supervisor, timers };
}

test('an idle active goal owns exactly one scheduled recovery lease', () => {
	const { supervisor } = timerFixture();
	assert.equal(supervisor.activate(key), true);
	assert.deepEqual(supervisor.snapshot(key).leases.map((lease) => lease.kind), ['scheduled']);
	supervisor.ensure(key, 'duplicate signal');
	assert.deepEqual(supervisor.snapshot(key).leases.map((lease) => lease.kind), ['scheduled']);
});

test('overlapping provider and physical work suppress scheduled recovery until both end', () => {
	const { supervisor } = timerFixture();
	supervisor.activate(key);
	const provider = supervisor.begin(key, 'provider');
	const action = supervisor.begin(key, 'action');
	assert.deepEqual(supervisor.snapshot(key).leases.map((lease) => lease.kind), ['provider', 'action']);
	assert.equal(supervisor.end(action, { progress: true }), true);
	assert.deepEqual(supervisor.snapshot(key).leases.map((lease) => lease.kind), ['provider']);
	assert.equal(supervisor.end(provider, { progress: true }), true);
	assert.deepEqual(supervisor.snapshot(key).leases.map((lease) => lease.kind), ['scheduled']);
});

test('recoverable failure enters one bounded recovery lease', () => {
	const requests = [];
	const { supervisor } = timerFixture(requests);
	supervisor.activate(key);
	const provider = supervisor.begin(key, 'provider');
	supervisor.end(provider, { scheduleRecovery: false });
	assert.equal(supervisor.recover(key, { errorCode: 'PLANNING_TIMEOUT' }), true);
	assert.equal(requests.length, 0);
	assert.equal(supervisor.snapshot(key).state, 'recovering');
	assert.deepEqual(supervisor.snapshot(key).leases.map((lease) => lease.kind), ['scheduled']);
});

test('explicit suspension cancels every owned timer and lease', () => {
	const { supervisor, timers } = timerFixture();
	supervisor.activate(key);
	supervisor.begin(key, 'provider');
	assert.equal(supervisor.suspend(key), true);
	assert.equal(timers.size, 0);
	assert.deepEqual(supervisor.snapshot(key).leases, []);
});

test('begin forwards an explicit native timeout and progress keeps that timeout', () => {
	const { supervisor, timers } = timerFixture();
	supervisor.activate(key);
	const token = supervisor.begin(key, 'provider', { timeoutMs: 900_000 });
	assert.equal([...timers.values()][0].delay, 900_000);
	assert.equal(supervisor.progress(token), true);
	assert.equal([...timers.values()][0].delay, 900_000);
	assert.equal(supervisor.snapshot(key).leases[0].timeoutMs, 900_000);
	supervisor.close();
});

test('stale tokens cannot settle work belonging to a newer fenced goal', () => {
	const { supervisor } = timerFixture();
	supervisor.activate(key);
	const staleToken = supervisor.begin(key, 'provider');
	supervisor.activate({ ...key, goalRevision: 5, lifecycleGeneration: 3 });
	assert.equal(supervisor.end(staleToken, { progress: true }), false);
	assert.deepEqual(supervisor.snapshot({ ...key, goalRevision: 5, lifecycleGeneration: 3 }).leases.map((lease) => lease.kind), ['scheduled']);
});
