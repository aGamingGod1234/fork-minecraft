import assert from 'node:assert/strict';
import test from 'node:test';

import { LEASE_TIMEOUTS_MS, MAX_LEASE_TIMEOUT_MS, WorkLeaseSupervisor } from '../src/work-lease-supervisor.mjs';

const key = Object.freeze({
	agentId: 'luna',
	goalRevision: 7,
	lifecycleGeneration: 3,
	sessionEpoch: 11,
	profileFingerprint: `sha256:${'a'.repeat(64)}`,
});

class FakeClock {
	#now = 0;
	#nextId = 0;
	#timers = new Map();

	now = () => this.#now;
	schedule = (callback, delay) => {
		const handle = { id: ++this.#nextId };
		this.#timers.set(handle.id, { handle, callback, dueAt: this.#now + delay });
		return handle;
	};
	cancel = (handle) => this.#timers.delete(handle?.id);

	advance(milliseconds) {
		this.#now += milliseconds;
	}

	async runDue() {
		while (true) {
			const timer = [...this.#timers.values()]
				.filter((candidate) => candidate.dueAt <= this.#now)
				.sort((left, right) => left.dueAt - right.dueAt || left.handle.id - right.handle.id)[0];
			if (timer === undefined) return;
			this.#timers.delete(timer.handle.id);
			await timer.callback();
		}
	}

	get pendingCount() { return this.#timers.size; }
}

function fixture() {
	const clock = new FakeClock();
	const observations = [];
	const expirations = [];
	const stuck = [];
	const supervisor = new WorkLeaseSupervisor({
		clock: clock.now,
		schedule: clock.schedule,
		cancelSchedule: clock.cancel,
		stuckSchedule: clock.schedule,
		cancelStuckSchedule: clock.cancel,
		requestObservation: (requested, reason) => observations.push({ key: requested, reason }),
		onExpire: (event) => expirations.push(event),
		onStuck: (event) => stuck.push(event),
	});
	return { clock, observations, expirations, stuck, supervisor };
}

test('work lease recovers an unfinished goal within the scheduled two-second bound', async () => {
	const { clock, observations, supervisor } = fixture();
	supervisor.activate(key);
	clock.advance(LEASE_TIMEOUTS_MS.scheduled + 1);
	await clock.runDue();
	assert.equal(observations.length, 1);
	assert.deepEqual(observations[0].key, key);
	assert.equal(observations[0].reason, 'scheduled_lease_expired');
	assert.equal(supervisor.snapshot(key).state, 'recovering');
});

test('circuit recovery preserves its absolute probe deadline before requesting fresh facts', async () => {
	const { clock, observations, supervisor } = fixture();
	supervisor.activate(key);
	assert.equal(supervisor.recover(key, { nextProbeAtEpochMs: 10_000, retryDelayMs: 9_000 }), true);
	clock.advance(LEASE_TIMEOUTS_MS.scheduled + 1);
	await clock.runDue();
	assert.equal(observations.length, 0, 'ordinary scheduled timeout cannot bypass the circuit deadline');
	clock.advance(6_999);
	await clock.runDue();
	assert.equal(observations.length, 1);
	assert.equal(observations[0].key.profileFingerprint, key.profileFingerprint);
	assert.equal(supervisor.snapshot(key).recoveryDetails.nextProbeAtEpochMs, 10_000);
});

test('provider and action leases expire independently and preserve the fenced goal key', async () => {
	const { clock, observations, expirations, supervisor } = fixture();
	supervisor.activate(key);
	const provider = supervisor.acquire(key, 'provider');
	const action = supervisor.acquire(key, 'action');
	clock.advance(LEASE_TIMEOUTS_MS.provider + 1);
	await clock.runDue();
	assert.equal(expirations.length, 1);
	assert.deepEqual(expirations[0].key, key);
	assert.equal(expirations[0].lease.kind, 'provider');
	assert.equal(supervisor.snapshot(key).leases.some((lease) => lease.operationId === action.operationId), true);
	assert.equal(observations.length, 1);
	assert.equal(supervisor.release(provider), false, 'an expired token cannot release newer work');
});

test('bounded progress renews only the matching live lease', async () => {
	const { clock, expirations, supervisor } = fixture();
	supervisor.activate(key);
	const provider = supervisor.acquire(key, 'provider');
	clock.advance(40_000);
	assert.equal(supervisor.progress(provider), true);
	clock.advance(10_000);
	await clock.runDue();
	assert.equal(expirations.length, 0);
	clock.advance(35_001);
	await clock.runDue();
	assert.equal(expirations.length, 1);
	assert.equal(expirations[0].lease.operationId, provider.operationId);
});

test('native outer lease allows queueing past 45 seconds and preserves its bounded override on progress', async () => {
	const { clock, expirations, supervisor } = fixture();
	supervisor.activate(key);
	const provider = supervisor.acquire(key, 'provider', { timeoutMs: 900_000 });
	clock.advance(125_001);
	await clock.runDue();
	assert.equal(expirations.length, 0, 'queued native work is not subject to the default 45-second lease');
	assert.equal(supervisor.progress(provider), true);
	const lease = supervisor.snapshot(key).leases[0];
	assert.equal(lease.timeoutMs, 900_000);
	assert.equal(lease.deadline, 1_025_001);
	clock.advance(899_999);
	await clock.runDue();
	assert.equal(expirations.length, 0);
	clock.advance(1);
	await clock.runDue();
	assert.equal(expirations.length, 1);
	assert.equal(expirations[0].lease.operationId, provider.operationId);
});

test('invalid timeout overrides cannot create unbounded work or remove the existing recovery lease', () => {
	const { supervisor } = fixture();
	supervisor.activate(key);
	for (const timeoutMs of [null, 0, -1, 1.5, NaN, Infinity, MAX_LEASE_TIMEOUT_MS + 1, Number.MAX_SAFE_INTEGER, '900000']) {
		assert.throws(() => supervisor.acquire(key, 'provider', { timeoutMs }), /timeoutMs/);
		assert.deepEqual(supervisor.snapshot(key).leases.map((lease) => lease.kind), ['scheduled']);
	}
});

test('a queued callback from the prior deadline cannot expire a renewed lease', () => {
	let now = 0;
	const callbacks = [];
	const expirations = [];
	const supervisor = new WorkLeaseSupervisor({ clock: () => now, schedule: (callback) => { callbacks.push(callback); return callback; }, cancelSchedule() {}, onExpire: (event) => expirations.push(event) });
	supervisor.activate(key);
	const provider = supervisor.acquire(key, 'provider', { timeoutMs: 900_000 });
	const oldCallback = callbacks.at(-1);
	now = 890_000;
	supervisor.progress(provider);
	now = 900_000;
	oldCallback();
	assert.deepEqual(expirations, []);
	assert.equal(supervisor.snapshot(key).leases[0].deadline, 1_790_000);
	supervisor.close();
});

test('releasing the final live lease always arms another scheduled-work deadline', () => {
	const { supervisor } = fixture();
	supervisor.activate(key);
	const provider = supervisor.acquire(key, 'provider');
	assert.equal(supervisor.release(provider), true);
	const snapshot = supervisor.snapshot(key);
	assert.equal(snapshot.state, 'active');
	assert.deepEqual(snapshot.leases.map((lease) => lease.kind), ['scheduled']);
});

test('factual progress resets the thirty-second stuck deadline while identical facts do not', async () => {
	const { clock, stuck, supervisor } = fixture();
	supervisor.activate(key);
	supervisor.factualProgress(key, 'position:0,64,0', { position: { x: 0, y: 64, z: 0 } });
	clock.advance(20_000);
	supervisor.factualProgress(key, 'position:1,64,0', { position: { x: 1, y: 64, z: 0 } });
	clock.advance(20_000);
	await clock.runDue();
	assert.equal(stuck.length, 0);
	supervisor.factualProgress(key, 'position:1,64,0', { position: { x: 1, y: 64, z: 0 } });
	clock.advance(10_001);
	await clock.runDue();
	assert.equal(stuck.length, 1);
	assert.deepEqual(stuck[0].key, key);
	assert.equal(stuck[0].history.length >= 2, true);
});

test('newer lifecycle keys fence callbacks and suspension cancels every lease', async () => {
	const { clock, observations, supervisor } = fixture();
	supervisor.activate(key);
	const stale = supervisor.acquire(key, 'provider');
	supervisor.activate({ ...key, lifecycleGeneration: 4 });
	assert.equal(supervisor.release(stale), false);
	assert.equal(supervisor.suspend({ ...key, lifecycleGeneration: 4 }), true);
	clock.advance(200_000);
	await clock.runDue();
	assert.deepEqual(observations, []);
	assert.equal(clock.pendingCount, 0);
});

test('recovery lease identity fences stale session epochs and exact-profile mutations', () => {
	const { supervisor } = fixture();
	supervisor.activate(key);
	const stale = supervisor.acquire(key, 'provider');
	const nextSession = { ...key, sessionEpoch: key.sessionEpoch + 1 };
	assert.equal(supervisor.activate(nextSession), true);
	assert.equal(supervisor.release(stale), false, 'an earlier bridge session cannot release replacement work');
	assert.equal(supervisor.snapshot(key), null);
	assert.equal(supervisor.snapshot(nextSession)?.key.profileFingerprint, key.profileFingerprint);
	assert.equal(supervisor.activate({ ...nextSession, profileFingerprint: `sha256:${'b'.repeat(64)}` }), false, 'same-generation profile mutation is not newer work');
});
