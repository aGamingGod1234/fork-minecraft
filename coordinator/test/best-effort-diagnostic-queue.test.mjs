import assert from 'node:assert/strict';
import test from 'node:test';

import { BestEffortDiagnosticQueue } from '../src/best-effort-diagnostic-queue.mjs';

test('diagnostic sink invocation is deferred outside the control call stack', async () => {
	let calls = 0;
	const queue = new BestEffortDiagnosticQueue();
	assert.equal(queue.submit(() => { calls += 1; }), true);
	assert.equal(calls, 0, 'submit must not invoke an observational sink inline');
	await queue.close();
	assert.equal(calls, 1);
});

test('diagnostic queue bounds a hung sink and drops overflow without blocking submitters', async () => {
	let calls = 0;
	const queue = new BestEffortDiagnosticQueue({
		maxPending: 2,
		operationTimeoutMs: 20,
		closeTimeoutMs: 10,
	});
	const hung = () => {
		calls += 1;
		return new Promise(() => {});
	};

	assert.equal(queue.submit(hung), true);
	assert.equal(queue.submit(hung), true);
	for (let index = 0; index < 100; index += 1) assert.equal(queue.submit(hung), false);
	assert.equal(queue.droppedCount, 100);
	await new Promise((resolve) => setImmediate(resolve));
	assert.equal(calls, 1, 'only the active sink operation starts while it is hung');

	const firstClose = queue.close();
	assert.strictEqual(queue.close(), firstClose, 'cleanup is idempotent');
	await firstClose;
	assert.equal(queue.submit(() => {}), false, 'closed diagnostics remain best effort');
});

test('diagnostic queue observes sync throws and async rejection then drains later work', async () => {
	const completed = [];
	const queue = new BestEffortDiagnosticQueue({ operationTimeoutMs: 50, closeTimeoutMs: 100 });
	queue.submit(() => { throw new Error('sync sink failure'); });
	queue.submit(async () => { throw new Error('async sink failure'); });
	queue.submit(() => { completed.push('healthy'); });

	await queue.close();
	assert.deepEqual(completed, ['healthy']);
});

test('diagnostic queue contains hostile then getters and continues draining', async () => {
	const completed = [];
	const queue = new BestEffortDiagnosticQueue({ operationTimeoutMs: 50, closeTimeoutMs: 100 });
	const hostileThenable = Object.create(null, {
		then: {
			get() { throw new Error('hostile then getter'); },
		},
	});

	queue.submit(() => hostileThenable);
	queue.submit(() => { completed.push('healthy'); });

	await queue.close();
	assert.deepEqual(completed, ['healthy']);
	assert.equal(queue.statusSnapshot().state, 'ready');
});

test('timed-out sink ownership stays bounded while later healthy work can recover', async () => {
	let hungCalls = 0;
	const completed = [];
	const timers = [];
	const queue = new BestEffortDiagnosticQueue({
		maxPending: 8,
		maxDetachedOperations: 2,
		operationTimeoutMs: 10,
		closeTimeoutMs: 100,
		dispatch: (callback) => callback(),
		schedule: (callback) => {
			const timer = { active: true, callback };
			timers.push(timer);
			return timer;
		},
		cancel: (timer) => { timer.active = false; },
	});
	const expireNext = () => {
		const timer = timers.find((candidate) => candidate.active);
		assert.ok(timer, 'a sink timeout must be scheduled');
		timer.active = false;
		timer.callback();
	};
	queue.submit(() => { hungCalls += 1; return new Promise(() => {}); });
	queue.submit(() => { completed.push('healthy'); });
	for (let index = 0; index < 10; index += 1) {
		queue.submit(() => { hungCalls += 1; return new Promise(() => {}); });
	}
	expireNext();
	await Promise.resolve();
	assert.deepEqual(completed, ['healthy']);
	assert.equal(hungCalls, 2, 'detached hung sink ownership is capped');
	expireNext();
	await Promise.resolve();
	assert.ok(queue.droppedCount > 0);
	await queue.close();
});
