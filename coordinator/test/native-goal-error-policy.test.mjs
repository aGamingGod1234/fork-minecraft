import assert from 'node:assert/strict';
import test from 'node:test';

import { classifyNativeGoalError } from '../src/native-goal-error-policy.mjs';

test('classifies stale native goal exits separately from lifecycle failures', () => {
	for (const code of ['STALE_PLAN', 'PLAN_CANCELLED', 'STALE_GOAL_REVISION', 'STALE_NATIVE_TOOL', 'TURN_INTERRUPTED', 'AGENT_DISPOSED']) {
		assert.equal(classifyNativeGoalError(Object.assign(new Error(code), { code })), 'stale');
	}
});

test('classifies local, profile, and configuration native errors as terminal', () => {
	for (const code of [
		'AGENT_PROFILE_CONFLICT',
		'CONTROL_PROTOCOL_MISMATCH',
		'INVALID_CATALOG',
		'MODEL_PROFILE_UNAVAILABLE',
		'MODEL_UNAVAILABLE',
		'REASONING_EFFORT_UNAVAILABLE',
		'SERVICE_TIER_UNAVAILABLE',
		'NATIVE_TOOLS_UNAVAILABLE',
	]) {
		assert.equal(classifyNativeGoalError(Object.assign(new Error(code), { code })), 'terminal');
	}
	assert.equal(classifyNativeGoalError(new TypeError('invalid native turn input')), 'terminal');
});

test('classifies provider and runtime failures as recoverable by default', () => {
	for (const error of [
		Object.assign(new Error('provider timed out'), { code: 'PLANNING_TIMEOUT' }),
		Object.assign(new Error('provider unavailable'), { code: 'PROVIDER_UNAVAILABLE' }),
		Object.assign(new Error('action failed'), { code: 'ACTION_TIMEOUT' }),
		Object.assign(new Error('provider transport reset'), { code: 'RPC_ERROR' }),
		new Error('uncoded runtime failure'),
	]) {
		assert.equal(classifyNativeGoalError(error), 'recoverable');
	}
});
