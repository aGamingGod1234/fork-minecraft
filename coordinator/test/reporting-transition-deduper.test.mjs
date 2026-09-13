import assert from 'node:assert/strict';
import test from 'node:test';

import { ReportingTransitionDeduper } from '../src/reporting-transition-deduper.mjs';

test('identical failures are suppressed until a distinct recovery transition occurs', () => {
	const deduper = new ReportingTransitionDeduper();
	const failed = {
		agentId: 'luna', goalRevision: 4, component: 'provider', boundary: 'planning', code: 'PLANNING_TIMEOUT', state: 'retrying',
	};
	assert.equal(deduper.accept(failed), true);
	assert.equal(deduper.accept({ ...failed }), false);
	assert.equal(deduper.accept({ ...failed, code: 'PROVIDER_RECOVERED', state: 'ready' }), true);
	assert.equal(deduper.accept(failed), true, 'a real recovery makes a later recurrence visible');
});

test('transition state is bounded and clearable on disable, removal, revision, and close', () => {
	const deduper = new ReportingTransitionDeduper({ maximumAgents: 2 });
	for (const agentId of ['a', 'b', 'c']) {
		assert.equal(deduper.accept({ agentId, goalRevision: 1, component: 'lifecycle', boundary: 'goal', code: 'START', state: 'ready' }), true);
	}
	assert.equal(deduper.size, 2);
	deduper.clear('c');
	assert.equal(deduper.size, 1);
	assert.equal(deduper.accept({ agentId: 'b', goalRevision: 2, component: 'lifecycle', boundary: 'goal', code: 'START', state: 'ready' }), true);
	deduper.clearAll();
	assert.equal(deduper.size, 0);
});

test('unrelated conversation transitions do not make a repeated timeout visible again', () => {
	const deduper = new ReportingTransitionDeduper();
	const timeout = { agentId: 'sol', goalRevision: 9, component: 'provider', boundary: 'planning', code: 'PLANNING_TIMEOUT', state: 'retrying' };
	assert.equal(deduper.accept(timeout), true);
	assert.equal(deduper.accept({ agentId: 'sol', goalRevision: 9, component: 'conversation', boundary: 'reply', code: 'REPLIED', state: 'ready' }), true);
	assert.equal(deduper.accept(timeout), false);
	assert.equal(deduper.accept({ ...timeout, code: 'PROVIDER_RECOVERED', state: 'ready' }), true);
	assert.equal(deduper.accept(timeout), true);
	assert.equal(deduper.accept({ ...timeout, goalRevision: 10 }), true, 'a new goal revision resets transition identity');
});

test('per-agent component histories are bounded', () => {
	const deduper = new ReportingTransitionDeduper({ maximumTransitionsPerAgent: 2 });
	for (const boundary of ['a', 'b', 'c']) {
		deduper.accept({ agentId: 'luna', goalRevision: 1, component: 'provider', boundary, code: 'FAILED', state: 'retrying' });
	}
	assert.equal(deduper.accept({ agentId: 'luna', goalRevision: 1, component: 'provider', boundary: 'a', code: 'FAILED', state: 'retrying' }), true);
});
