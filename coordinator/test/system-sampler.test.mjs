import assert from 'node:assert/strict';
import test from 'node:test';

import { MAX_SUMMARY_SAMPLES, SystemSampler, summarizeSystemSamples } from '../src/benchmark/system-sampler.mjs';

test('captures bounded observational process and scheduler samples through injected readers', () => {
	let now = 0;
	const scheduled = new Map();
	let nextHandle = 0;
	let processReads = 0;
	const sampler = new SystemSampler({
		intervalMs: 10,
		maxSamples: 2,
		clock: () => now,
		eventLoopDelayReader: () => ({ meanMs: 1.5, p95Ms: 2.5, maxMs: 3.5 }),
		processReader: (options) => {
			processReads += 1;
			assert.equal(options, undefined);
			return { cpuUserMs: 4, cpuSystemMs: 2, rssBytes: 128, heapUsedBytes: 64 };
		},
		schedulerReader: () => ({ active: 2, pending: 3 }),
		childProcessReader: () => 1,
		timer: {
			setInterval(callback) { const handle = ++nextHandle; scheduled.set(handle, callback); return handle; },
			clearInterval(handle) { scheduled.delete(handle); },
		},
	});

	assert.equal(sampler.start(), sampler);
	now = 10;
	scheduled.get(1)();
	now = 20;
	scheduled.get(1)();
	now = 30;
	scheduled.get(1)();
	assert.equal(sampler.snapshot().length, 2);
	assert.equal(sampler.snapshot().droppedSamples, 1);
	assert.equal(sampler.summary().maxSamples, 2);
	assert.equal(sampler.summary().droppedSamples, 1);
	assert.equal(processReads, 2, 'one process read supplies both CPU and memory metrics');
	assert.deepEqual(sampler.snapshot()[0].cpu, { userMs: 4, systemMs: 2, totalMs: 6 });
	assert.deepEqual(sampler.snapshot()[0].memory, { rssBytes: 128, heapUsedBytes: 64, heapTotalBytes: null, externalBytes: null });
	assert.deepEqual(sampler.snapshot()[0].scheduler, { active: 2, pending: 3 });
	assert.equal(sampler.snapshot()[0].childProcessCount, 1);
	assert.equal(sampler.stop(), sampler);
	assert.equal(scheduled.size, 0);
	assert.equal(sampler.active, false);
});

test('stale interval callbacks cannot sample after stop or a later restart', () => {
	const callbacks = [];
	const sampler = new SystemSampler({
		processReader: () => ({ cpuUserMs: 1, cpuSystemMs: 2, rssBytes: 3, heapUsedBytes: 4 }),
		eventLoopDelayReader: () => null,
		timer: {
			setInterval(callback) { callbacks.push(callback); return callbacks.length; },
			clearInterval() {},
		},
	});

	sampler.start();
	const firstCallback = callbacks[0];
	firstCallback();
	assert.equal(sampler.sampleCount, 1);
	sampler.stop();
	firstCallback();
	assert.equal(sampler.sampleCount, 1, 'a timer residue after stop is inert');

	sampler.start();
	const secondCallback = callbacks[1];
	firstCallback();
	assert.equal(sampler.sampleCount, 1, 'a stale callback remains inert after restart');
	secondCallback();
	assert.equal(sampler.sampleCount, 2);
	sampler.stop();
});

test('reader failures preserve the sample shape with null observational values', () => {
	const sampler = new SystemSampler({
		processReader: () => { throw new Error('process unavailable'); },
		eventLoopDelayReader: () => { throw new Error('event loop unavailable'); },
		schedulerReader: () => { throw new Error('scheduler unavailable'); },
		childProcessReader: () => { throw new Error('child process unavailable'); },
	});

	const sample = sampler.sample();
	assert.deepEqual(sample.cpu, { userMs: null, systemMs: null, totalMs: null });
	assert.deepEqual(sample.memory, { rssBytes: null, heapUsedBytes: null, heapTotalBytes: null, externalBytes: null });
	assert.deepEqual(sample.eventLoopDelay, { meanMs: null, p95Ms: null, maxMs: null });
	assert.deepEqual(sample.scheduler, { active: null, pending: null });
	assert.equal(sample.childProcessCount, null);
});

test('cleanup failures are reported without changing observational sampling behavior', () => {
	const monitor = {
		mean: 1_000_000,
		max: 2_000_000,
		percentile: () => 1_500_000,
		enable() {},
		disable() { throw new Error('monitor disable failed'); },
	};
	const timerErrors = [];
	const sampler = new SystemSampler({
		eventLoopMonitorFactory: () => monitor,
		processReader: () => ({ cpuUserMs: 1, cpuSystemMs: 2, rssBytes: 3, heapUsedBytes: 4 }),
		timer: {
			setInterval() { return 1; },
			clearInterval() { throw new Error('timer cleanup failed'); },
		},
		onError(error) { timerErrors.push(error); },
	});

	sampler.start();
	const sample = sampler.sample();
	assert.equal(sample.cpu.totalMs, 3);
	assert.doesNotThrow(() => sampler.stop());
	assert.deepEqual(timerErrors.map((error) => error.phase), ['timer.clearInterval', 'eventLoopMonitor.disable']);
	assert.deepEqual(sampler.errors.map((error) => error.phase), ['timer.clearInterval', 'eventLoopMonitor.disable']);
	assert.equal(sampler.summary().errors.length, 2);
});

test('summarizes CPU, memory, event-loop, scheduler, and child-process series', () => {
	const summary = summarizeSystemSamples([
		{ cpu: { totalMs: 2 }, memory: { rssBytes: 100, heapUsedBytes: 50 }, eventLoopDelay: { p95Ms: 4 }, scheduler: { active: 1, pending: 2 }, childProcessCount: 3 },
		{ cpu: { totalMs: 6 }, memory: { rssBytes: 200, heapUsedBytes: 70 }, eventLoopDelay: { p95Ms: 8 }, scheduler: { active: 3, pending: 1 }, childProcessCount: 1 },
	]);
	assert.equal(summary.sampleCount, 2);
	assert.deepEqual(summary.cpu.totalMs, { min: 2, max: 6, p50: 2, p95: 6, p99: 6 });
	assert.deepEqual(summary.memory.rssBytes, { min: 100, max: 200, p50: 100, p95: 200, p99: 200 });
	assert.deepEqual(summary.scheduler.active, { min: 1, max: 3, p50: 1, p95: 3, p99: 3 });
});

test('summarizes nearest-rank percentiles without interpolation', () => {
	const summary = summarizeSystemSamples([
		{ cpu: { totalMs: 1 } },
		{ cpu: { totalMs: 2 } },
		{ cpu: { totalMs: 3 } },
		{ cpu: { totalMs: 4 } },
	]);

	assert.deepEqual(summary.cpu.totalMs, { min: 1, max: 4, p50: 2, p95: 4, p99: 4 });
});

test('rejects summaries larger than the documented bounded input', () => {
	assert.throws(
		() => summarizeSystemSamples(Array.from({ length: MAX_SUMMARY_SAMPLES + 1 }, () => ({ cpu: { totalMs: 1 } }))),
		/at most|bounded|max/i,
	);
});
