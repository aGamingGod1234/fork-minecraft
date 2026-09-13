import assert from 'node:assert/strict';
import test from 'node:test';

import { classifyObservationTrigger, detectMovementLoop } from '../src/dynamic-main.mjs';

const observation = { player: { fire: false }, blocks: [] };

test('movement loop signal requires two repeated positions', () => {
	assert.equal(detectMovementLoop(['0,64,0', '1,64,0', '0,64,0', '1,64,0']), true);
	assert.equal(detectMovementLoop(['0,64,0', '1,64,0', '2,64,0', '3,64,0']), false);
	assert.equal(detectMovementLoop(['0,64,0', '1,64,0', '0,64,0']), false);
});

test('movement loop is promoted to urgent native attention', () => {
	assert.deepEqual(classifyObservationTrigger(
		{ attention: false },
		observation,
		{ movementLoop: true },
	), { attention: true, priority: 'urgent', trigger: 'movement_loop' });
});

test('new observed resource is promoted without misclassifying ordinary heartbeats', () => {
	assert.deepEqual(classifyObservationTrigger(
		{ attention: false },
		observation,
		{ resourceDiscovery: true },
	), { attention: true, priority: 'urgent', trigger: 'resource_discovery' });
	assert.deepEqual(classifyObservationTrigger({ attention: false }, observation), {
		attention: false,
		priority: 'ordinary',
		trigger: 'observation',
	});
});
