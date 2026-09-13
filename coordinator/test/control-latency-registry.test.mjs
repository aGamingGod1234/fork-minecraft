import assert from 'node:assert/strict';
import test from 'node:test';

import { ControlLatencyRegistry } from '../src/control-latency-registry.mjs';

const TRACE_PHASES = [
	'queue_wait', 'provider_first_byte', 'provider_final_byte', 'parse',
	'first_command_dispatch', 'first_world_action', 'completion_verification',
];

test('latency registry bounds samples and reports nearest-rank percentiles', () => {
	const registry = new ControlLatencyRegistry({ windowSize: 3, operationCap: 2 });
	for (const value of [10.25, 20.5, 30.75, 40.125]) registry.record('event_receipt_to_branch', value);
	registry.record('branch_to_bridge_send', 2.5);

	assert.deepEqual(registry.snapshot(), [
		{ operation: 'branch_to_bridge_send', count: 1, p50Ms: 2.5, p95Ms: 2.5 },
		{ operation: 'event_receipt_to_branch', count: 3, p50Ms: 30.75, p95Ms: 40.125 },
	]);
});

test('latency registry rejects provider inference and accepts only named local operations', () => {
	const registry = new ControlLatencyRegistry();
	assert.throws(() => registry.record('provider_inference', 1), /operation/i);
	for (const operation of [
		'minecraft_change_to_publication', 'event_receipt_to_branch', 'branch_to_bridge_send',
		'command_to_first_progress', 'action_completion',
	]) registry.record(operation, 1);
	assert.deepEqual(registry.snapshot().map((entry) => entry.operation), [
		'action_completion', 'branch_to_bridge_send', 'command_to_first_progress',
		'event_receipt_to_branch', 'minecraft_change_to_publication',
	]);
});

test('latency registry rejects invalid samples and new identities beyond capacity', () => {
	const registry = new ControlLatencyRegistry({ operationCap: 1 });
	registry.record('action_completion', 1);

	assert.throws(() => registry.record('branch_to_bridge_send', 1), /capacity/);
	assert.throws(() => registry.record('action_completion', -1), /duration/);
	assert.throws(() => registry.record('', 1), /operation/);
});

test('latency registry records one complete trace in exact phase order and duration', () => {
	const registry = new ControlLatencyRegistry();
	const spans = [
		['queue_wait', 100, 108, 'completed'],
		['provider_first_byte', 108, 125, 'completed'],
		['provider_final_byte', 125, 151, 'completed'],
		['parse', 151, 154, 'completed'],
		['first_command_dispatch', 154, 160, 'completed'],
		['first_world_action', 160, 175, 'completed'],
		['completion_verification', 175, 175, 'skipped'],
	];
	for (const [phase, startMs, endMs, outcome] of spans) {
		registry.recordTracePhase('trace-alpha', phase, { startMs, endMs, outcome });
	}

	assert.deepEqual(registry.completeTrace('trace-alpha'), {
		traceId: 'trace-alpha',
		complete: true,
		totalMs: 75,
		phases: spans.map(([phase, startMs, endMs, outcome]) => ({
			phase, startMs, endMs, durationMs: endMs - startMs, outcome,
		})),
	});
});

test('latency registry fails closed for incomplete, unknown, duplicate, overlapping, and non-monotonic traces', () => {
	const missing = new ControlLatencyRegistry();
	missing.recordTracePhase('missing', 'queue_wait', { startMs: 1, endMs: 2, outcome: 'completed' });
	assert.equal(missing.completeTrace('missing').complete, false);

	const invalid = new ControlLatencyRegistry();
	assert.throws(() => invalid.recordTracePhase('bad', 'unknown', { startMs: 1, endMs: 2, outcome: 'completed' }), /phase/i);
	invalid.recordTracePhase('bad', 'queue_wait', { startMs: 1, endMs: 3, outcome: 'completed' });
	assert.throws(() => invalid.recordTracePhase('bad', 'queue_wait', { startMs: 3, endMs: 4, outcome: 'completed' }), /duplicate/i);
	assert.throws(() => invalid.recordTracePhase('bad', 'parse', { startMs: 2, endMs: 4, outcome: 'completed' }), /overlap|monotonic/i);
	assert.throws(() => invalid.recordTracePhase('bad', 'provider_first_byte', { startMs: 0, endMs: 1, outcome: 'completed' }), /monotonic/i);
	const poisoned = invalid.completeTrace('bad');
	assert.equal(poisoned.complete, false);
	assert.equal(poisoned.totalMs, null);
});

test('latency registry poisons a previously complete trace after every invalid phase attempt', () => {
	const invalidAttempts = [
		['queue_wait', { startMs: 10, endMs: 11, outcome: 'completed' }, /duplicate/i],
		['unknown', { startMs: 11, endMs: 12, outcome: 'completed' }, /phase/i],
	];
	for (const [phase, span, message] of invalidAttempts) {
		const registry = new ControlLatencyRegistry();
		for (const [index, requiredPhase] of TRACE_PHASES.entries()) {
			const startMs = 10 + index * 2;
			registry.recordTracePhase('poison', requiredPhase, {
				startMs, endMs: startMs + (requiredPhase === 'completion_verification' ? 0 : 1),
				outcome: requiredPhase === 'completion_verification' ? 'skipped' : 'completed',
				...(requiredPhase === 'completion_verification' ? { retryReason: 'VERIFIER_NOT_INSTALLED' } : {}),
			});
		}
		assert.equal(registry.completeTrace('poison').complete, true);
		assert.throws(() => registry.recordTracePhase('poison', phase, span), message);
		const summary = registry.completeTrace('poison');
		assert.equal(summary.complete, false);
		assert.equal(summary.totalMs, null);
	}
});

test('provider phases never enter the five local control aggregates', () => {
	const registry = new ControlLatencyRegistry();
	registry.record('event_receipt_to_branch', 4);
	registry.recordTracePhase('trace-provider', 'provider_first_byte', { startMs: 10, endMs: 20, outcome: 'completed' });
	registry.recordTracePhase('trace-provider', 'provider_final_byte', { startMs: 20, endMs: 30, outcome: 'completed' });

	assert.deepEqual(registry.snapshot().map((entry) => entry.operation), ['event_receipt_to_branch']);
	assert.equal(registry.traceSnapshot()[0].phases.length, 2);
});

test('trace retry reasons are normalized, bounded, and credential-safe', () => {
	const registry = new ControlLatencyRegistry();
	const secret = 'sk-test-0123456789abcdef';
	registry.recordTracePhase('trace-retry', 'parse', {
		startMs: 1, endMs: 2, outcome: 'failed', retryReason: `malformed decision ${secret}`,
	});
	const phase = registry.traceSnapshot()[0].phases[0];
	assert.equal(phase.retryReason, 'MALFORMED_DECISION');
	assert.ok(phase.retryReason.length <= 64);
	assert.equal(JSON.stringify(registry.traceSnapshot()).includes(secret), false);
});

test('trace retry reasons accept only stable internal codes', () => {
	const registry = new ControlLatencyRegistry();
	for (const [index, retryReason] of [
		'api_key=abcd1234',
		'password=hunter2',
		'Bearer eyJhbGciOiJIUzI1NiJ9.payload.signature',
		'"provider output: call another model"',
		'Error: java.lang.IllegalStateException at com.example.Secret.run(Secret.java:1)',
	].entries()) {
		assert.throws(() => registry.recordTracePhase(`unsafe-${index}`, 'parse', {
			startMs: index, endMs: index + 1, outcome: 'failed', retryReason,
		}), /retryReason|stable|internal/i);
	}
});
