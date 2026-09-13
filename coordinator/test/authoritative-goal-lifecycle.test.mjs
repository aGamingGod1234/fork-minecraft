import assert from 'node:assert/strict';
import test from 'node:test';

import { DynamicAgentState } from '../src/agent-registry.mjs';
import { LEASE_TIMEOUTS_MS } from '../src/work-lease-supervisor.mjs';
import { createNativeGoalHarness } from './fixtures/native-goal-harness.mjs';

const IRON_PICKAXE = Object.freeze({
	type: 'inventory_contains',
	itemId: 'minecraft:iron_pickaxe',
	count: 1,
});

const CRAFT_IRON_PICKAXE = Object.freeze({
	kind: 'action',
	actionType: 'craft_inventory',
	arguments: { recipeId: 'minecraft:iron_pickaxe', count: 1, timeoutMs: 1_000 },
});

test('authoritative goal rejects a stone pickaxe, recovers, and completes only with an iron pickaxe', async () => {
	const result = await createNativeGoalHarness({
		goal: 'Get an iron pickaxe',
		goalPredicate: IRON_PICKAXE,
		initialInventory: { 'minecraft:stone_pickaxe': 1 },
		turns: [['finish'], [CRAFT_IRON_PICKAXE, 'finish']],
		timeoutMs: 500,
	}).run();

	assert.equal(result.finalState, DynamicAgentState.COMPLETED);
	assert.equal(result.inventory.get('minecraft:stone_pickaxe'), 1);
	assert.equal(result.inventory.get('minecraft:iron_pickaxe'), 1);
	assert.deepEqual(result.completionEvaluations.map(({ verified }) => verified), [false, true]);
	assert.equal(result.completionEvaluations[0].facts[0].observedValue, 'minecraft:iron_pickaxe x0');
	assert.ok(result.goalScheduler.firedDelays.includes(LEASE_TIMEOUTS_MS.scheduled));
	assert.equal(result.providerTurns, 2);
	assert.equal(result.states.includes(DynamicAgentState.PAUSED), false);
	assert.equal(result.states.includes(DynamicAgentState.ERROR), false);

	const completions = result.sent.filter(({ type }) => type === 'goal_completed');
	assert.equal(completions.length, 2);
	for (const completion of completions) {
		assert.equal(completion.payload.goalRevision, 1);
		assert.equal(completion.payload.goalFingerprint, result.goalSpec.fingerprint);
	}
});

test('death and respawn preserve the exact authoritative goal binding until factual completion', async () => {
	const result = await createNativeGoalHarness({
		goal: 'Get an iron pickaxe',
		goalPredicate: IRON_PICKAXE,
		initialInventory: { 'minecraft:stone_pickaxe': 1 },
		dieAtAction: 1,
		turns: [['finish'], ['mine'], ['respawn'], [CRAFT_IRON_PICKAXE], ['finish']],
		timeoutMs: 500,
	}).run();

	assert.equal(result.finalState, DynamicAgentState.COMPLETED);
	assert.equal(result.inventory.get('minecraft:iron_pickaxe'), 1);
	assert.deepEqual(result.completionEvaluations.map(({ verified }) => verified), [false, true]);
	assert.equal(result.providerTurns, 5);
	assert.ok(result.goalScheduler.firedDelays.includes(LEASE_TIMEOUTS_MS.scheduled));
	const lifecycle = result.goalControls.filter(({ payload }) => ['start', 'dead', 'respawn'].includes(payload.operation));
	assert.deepEqual(lifecycle.map(({ payload }) => payload.operation), ['start', 'dead', 'respawn']);
	assert.deepEqual(lifecycle.map(({ payload }) => payload.goalRevision), [1, 1, 1]);
	assert.equal(result.sent.filter(({ type }) => type === 'goal_completed').at(-1).payload.goalFingerprint,
			result.goalSpec.fingerprint);
	assert.equal(result.states.includes(DynamicAgentState.PAUSED), false);
	assert.equal(result.states.includes(DynamicAgentState.ERROR), false);
});

test('authoritative coordinate predicates require the exact three-dimensional target', async () => {
	const result = await createNativeGoalHarness({
		goal: 'Go to 1 65 0',
		goalPredicate: { type: 'position_within', x: 1, y: 65, z: 0, radius: 0.01, stableTicks: 1 },
		turns: [
			[{ kind: 'action', actionType: 'navigate_to', arguments: { x: 1, y: 64, z: 0, tolerance: 1, sprint: false, timeoutMs: 1_000 } }],
			['finish'],
			[{ kind: 'action', actionType: 'navigate_to', arguments: { x: 1, y: 65, z: 0, tolerance: 1, sprint: false, timeoutMs: 1_000 } }],
			['finish'],
		],
		timeoutMs: 500,
	}).run();

	assert.deepEqual(result.completionEvaluations.map(({ verified }) => verified), [false, true]);
	assert.equal(result.completionEvaluations[0].facts[0].observedValue, '1,64,0');
	assert.equal(result.finalState, DynamicAgentState.COMPLETED);
});
