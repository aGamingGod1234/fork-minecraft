import assert from 'node:assert/strict';
import test from 'node:test';

import { analyzePairedTrials } from '../src/benchmark/paired-analysis.mjs';

function trial(pairingKey, status, latencyMs, category) {
	const value = { pairingKey, status };
	if (latencyMs !== undefined) value.latencyMs = latencyMs;
	if (category !== undefined) value.error = { category };
	return value;
}

test('analyzes matching trials with status counts, quantiles, paired deltas, rates, and taxonomy', () => {
	const result = analyzePairedTrials({
		baseline: [
			trial('a', 'passed', 100),
			trial('b', 'failed', 200, 'transport_failure'),
			trial('c', 'skipped'),
			trial('d', 'timed-out', 300, 'timeout'),
		],
		optimized: [
			trial('d', 'timed_out', 240, 'timeout'),
			trial('c', 'skipped'),
			trial('b', 'failed', 180, 'transport_failure'),
			trial('a', 'passed', 80),
		],
		bootstrapSamples: 200,
		analysisSeed: 42,
	});

	assert.deepEqual(result.counts.baseline, { total: 4, passed: 1, failed: 1, skipped: 1, timedOut: 1 });
	assert.deepEqual(result.counts.optimized, { total: 4, passed: 1, failed: 1, skipped: 1, timedOut: 1 });
	assert.deepEqual(result.latency.baseline, {
		sampleCount: 3,
		p50Ms: 200,
		p95Ms: 300,
		p99Ms: 300,
		provisional: true,
		sampleLabel: 'provisional',
	});
	assert.deepEqual(result.latency.optimized, {
		sampleCount: 3,
		p50Ms: 180,
		p95Ms: 240,
		p99Ms: 240,
		provisional: true,
		sampleLabel: 'provisional',
	});
	assert.equal(result.paired.count, 4);
	assert.equal(result.paired.latencyCount, 3);
	assert.equal(result.paired.percentageCount, 3);
	assert.equal(result.paired.absoluteDeltaMs.p50Ms, -20);
	assert.equal(result.paired.absoluteDeltaMs.p95Ms, -20);
	assert.equal(result.paired.percentageDelta.p50Percent, -20);
	assert.equal(result.paired.percentageDelta.p95Percent, -10);
	assert.equal(result.successRate.baseline.rate, 0.25);
	assert.equal(result.successRate.optimized.rate, 0.25);
	assert.equal(result.successRate.difference, 0);
	assert.equal(result.errorTaxonomy.baseline.byCategory.transport_failure, 1);
	assert.equal(result.errorTaxonomy.baseline.byCategory.timeout, 1);
	assert.equal(result.errorTaxonomy.baseline.total, 3);
	assert.equal(result.provisional, true);
});

test('pairs by key independent of arm order and rejects missing, extra, or duplicate keys', () => {
	const baseline = [trial('a', 'passed', 10), trial('b', 'passed', 20)];
	const optimized = [trial('b', 'passed', 18), trial('a', 'passed', 9)];
	assert.equal(analyzePairedTrials({ baseline, optimized }).paired.absoluteDeltaMs.p50Ms, -2);

	assert.throws(
		() => analyzePairedTrials({ baseline, optimized: [trial('a', 'passed', 9)] }),
		/mismatch/i,
	);
	assert.throws(
		() => analyzePairedTrials({ baseline, optimized: [trial('a', 'passed', 9), trial('c', 'passed', 18)] }),
		/mismatch/i,
	);
	assert.throws(
		() => analyzePairedTrials({ baseline: [trial('a', 'passed', 10), trial('a', 'passed', 11)], optimized }),
		/duplicate/i,
	);
});

test('keeps skipped trials out of latency samples and includes timed-out latency without treating either as success', () => {
	const result = analyzePairedTrials({
		baseline: [trial('skip', 'skipped'), trial('timeout', 'timed_out', 500)],
		optimized: [trial('skip', 'skipped'), trial('timeout', 'timed_out', 450)],
	});

	assert.equal(result.latency.baseline.sampleCount, 1);
	assert.equal(result.latency.baseline.p50Ms, 500);
	assert.equal(result.successRate.baseline.rate, 0);
	assert.equal(result.successRate.baseline.attemptedRate, 0);
	assert.equal(result.errorTaxonomy.baseline.byCategory.skipped, 1);
	assert.equal(result.errorTaxonomy.baseline.byCategory.timeout, 1);
});

test('maps runner error codes to bounded taxonomy categories without exposing raw keys', () => {
	const result = analyzePairedTrials({
		baseline: [
			trial('timeout', 'failed', 20, 'REQUEST_TIMEOUT'),
			trial('auth', 'failed', 20, 'AUTHENTICATION_FAILURE'),
		],
		optimized: [
			trial('timeout', 'failed', 20, 'REQUEST_TIMEOUT'),
			trial('auth', 'failed', 20, 'AUTHENTICATION_FAILURE'),
		],
	});
	assert.deepEqual(result.errorTaxonomy.baseline.byCategory, {
		authentication_catalog_rejection: 1,
		timeout: 1,
	});
});

test('bootstrap confidence intervals are deterministic for a fixed analysis seed', () => {
	const baseline = Array.from({ length: 8 }, (_, index) => trial(`pair-${index}`, 'passed', 100 + index * 10));
	const optimized = Array.from({ length: 8 }, (_, index) => trial(`pair-${index}`, 'passed', 90 + index * 8));
	const options = { baseline, optimized, bootstrapSamples: 300, analysisSeed: 12345 };
	const first = analyzePairedTrials(options);
	const second = analyzePairedTrials(options);

	assert.deepEqual(first, second);
	assert.equal(first.bootstrap.analysisSeed, 12345);
	assert.equal(first.paired.absoluteDeltaMs.confidenceInterval.level, 0.95);
	assert.ok(Number.isFinite(first.paired.absoluteDeltaMs.confidenceInterval.low));
	assert.ok(Number.isFinite(first.paired.absoluteDeltaMs.confidenceInterval.high));
});

test('rejects non-finite, unsafe, contradictory, and prototype-polluting input', () => {
	const optimized = [trial('a', 'passed', 1)];
	for (const latencyMs of [Number.NaN, Number.POSITIVE_INFINITY, Number.MAX_SAFE_INTEGER + 1, -1]) {
		assert.throws(
			() => analyzePairedTrials({ baseline: [trial('a', 'passed', latencyMs)], optimized }),
			/latency/i,
		);
	}
	assert.throws(
		() => analyzePairedTrials({ baseline: [trial('__proto__', 'passed', 1)], optimized: [trial('__proto__', 'passed', 1)] }),
		/safe|key|prototype/i,
	);
	assert.throws(
		() => analyzePairedTrials({ baseline: [trial('a', 'passed', 1, '__proto__')], optimized }),
		/safe|category|prototype/i,
	);
	const sparse = [];
	sparse[1] = trial('a', 'passed', 1);
	assert.throws(
		() => analyzePairedTrials({ baseline: sparse, optimized: [trial('a', 'passed', 1)] }),
		/sparse/i,
	);
	const unsafeNestedError = JSON.parse('{"__proto__":"polluted"}');
	assert.throws(
		() => analyzePairedTrials({ baseline: [{ pairingKey: 'a', status: 'failed', latencyMs: 1, error: unsafeNestedError }], optimized }),
		/safe|field|prototype/i,
	);
	assert.throws(
		() => analyzePairedTrials({ baseline: [trial('a', 'passed', 1, 'timeout')], optimized }),
		/contradictory|error/i,
	);
	const outcomeDifference = analyzePairedTrials({ baseline: [trial('a', 'passed', 1)], optimized: [trial('a', 'failed', 1)] });
	assert.equal(outcomeDifference.successRate.difference, -1);
	assert.equal({}.polluted, undefined);
});

test('bounds trial and bootstrap inputs and never emits unbounded taxonomy keys', () => {
	const tooMany = Array.from({ length: 10001 }, (_, index) => trial(`pair-${index}`, 'passed', 1));
	assert.throws(
		() => analyzePairedTrials({ baseline: tooMany, optimized: tooMany }),
		/bounded|maximum|limit|trials/i,
	);
	assert.throws(
		() => analyzePairedTrials({ baseline: [trial('a', 'passed', 1)], optimized: [trial('a', 'passed', 1)], bootstrapSamples: 10001 }),
		/bounded|maximum|limit|bootstrap/i,
	);

	const result = analyzePairedTrials({
		baseline: [trial('a', 'failed', 1, 'made-up-error')],
		optimized: [trial('a', 'failed', 1, 'made-up-error')],
	});
	assert.deepEqual(Object.keys(result.errorTaxonomy.baseline.byCategory), ['unknown']);
});
