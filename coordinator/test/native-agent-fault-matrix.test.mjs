import assert from 'node:assert/strict';
import test from 'node:test';

import { DynamicAgentState } from '../src/agent-registry.mjs';
import {
	createNativeGoalHarness,
	codedError,
	woodenPickaxeFaultScenario,
} from './fixtures/native-goal-harness.mjs';

const scenarios = [
	['one action then turn end', { turns: [['mine']], expectedActions: 1 }],
	['zero-tool turn', { turns: [[], ['observe'], ['mine']], expectedActions: 1 }],
	['provider timeout after action', {
		turns: [['mine'], codedError('PLANNING_TIMEOUT'), ['pick_up_item']],
		expectedActions: 2,
	}],
	['provider circuit open', {
		turns: [codedError('PROVIDER_CIRCUIT_OPEN'), ['observe'], ['mine']],
		expectedActions: 1,
	}],
	['blocked path', {
		actionResults: ['PATH_BLOCKED', 'SUCCEEDED'],
		turns: [['move_to'], ['observe'], ['move_to']],
		expectedActions: 2,
	}],
	['action timeout', {
		actionResults: ['ACTION_TIMEOUT', 'SUCCEEDED'],
		turns: [['mine'], ['observe'], ['mine']],
		expectedActions: 2,
	}],
	['moved drop', {
		moveDropBeforePickup: true,
		turns: [['mine'], ['observe'], ['pick_up_item']],
		expectedItem: 'minecraft:oak_log',
	}],
	['completion rejected then accepted', {
		completionResults: [false, true],
		turns: [['finish'], ['craft_inventory'], ['finish']],
		expectedCompleted: true,
	}],
	['disconnect during action', {
		disconnectAtAction: 1,
		turns: [['mine'], ['observe'], ['mine']],
		expectedActions: 2,
	}],
	['death and respawn', {
		dieAtAction: 1,
		turns: [['mine'], ['respawn'], ['observe'], ['mine']],
		expectedActions: 3,
	}],
];

for (const [name, scenario] of scenarios) {
	test(name, async () => {
		const result = await createNativeGoalHarness(scenario).run();
		assert.equal(result.states.includes(DynamicAgentState.ERROR), false);
		assert.equal(result.states.includes(DynamicAgentState.PAUSED), false);
		assert.equal(result.sent.filter((entry) => entry.type === 'agent_error').length, 0);
		assert.ok(result.maxRecoveryHandles <= 1);
		if (scenario.expectedActions !== undefined) assert.equal(result.actionCount, scenario.expectedActions);
		if (scenario.expectedCompleted === true) assert.equal(result.finalState, DynamicAgentState.COMPLETED);
		if (scenario.expectedItem !== undefined) assert.equal(result.inventory.get(scenario.expectedItem), 1);
	});
}

test('explicit pause remains paused and does not dispatch a recovery turn', async () => {
	const result = await createNativeGoalHarness({
		pauseAfterTurn: 1,
		turns: [['observe']],
	}).run();
	assert.equal(result.finalState, DynamicAgentState.PAUSED);
	assert.equal(result.recoveryDispatches, 0);
});

test('stale callbacks are fenced before they can request observation or action', async () => {
	const result = await createNativeGoalHarness({
		staleCallbackAfterRevision: true,
		turns: [['mine'], ['finish']],
	}).run();
	assert.equal(result.staleDispatches, 0);
	assert.ok(result.maxRecoveryHandles <= 1);
	assert.notEqual(result.states.at(-1), DynamicAgentState.ERROR);
});

test('temporarily unsupported profile stays active with one bounded recovery owner', async () => {
	const result = await createNativeGoalHarness({
		unsupportedProfile: true,
		turns: [['observe']],
	}).run();
	assert.equal(result.finalState, DynamicAgentState.PLANNING);
	assert.equal(result.states.includes(DynamicAgentState.ERROR), false);
	assert.equal(result.states.includes(DynamicAgentState.PAUSED), false);
	assert.equal(result.sent.filter((entry) => entry.type === 'agent_error').length, 0);
	assert.ok(result.recoveryDispatches > 0, 'the blocked-retryable profile requests recovery');
	assert.ok(result.maxRecoveryHandles <= 1, 'profile recovery owns at most one live handle');
});

test('repeated recoverable provider failures keep one bounded recovery handle', async () => {
	const result = await createNativeGoalHarness({
		turns: Array.from({ length: 50 }, () => codedError('PLANNING_TIMEOUT')).concat([['mine'], ['finish']]),
		recoveryCycles: 50,
	}).run();
	assert.equal(result.recoveryCycles, 50);
	assert.ok(result.maxRecoveryHandles <= 1);
	assert.equal(result.states.includes(DynamicAgentState.ERROR), false);
	assert.equal(result.states.includes(DynamicAgentState.PAUSED), false);
});

test('shutdown leaves no active native work or orphan recovery handle', async () => {
	const harness = createNativeGoalHarness({ turns: [['mine']] });
	const result = await harness.run({ stopAfter: 'first-turn' });
	assert.equal(result.activeWork, 0);
	assert.equal(result.recoveryHandles, 0);
});

test('wooden-pickaxe fault scenario is available to the matrix runner', async () => {
	const scenario = woodenPickaxeFaultScenario();
	assert.equal(scenario.goal, 'gather wood and craft a wooden pickaxe');
	assert.ok(scenario.turns.length >= 5);
});

test('recovery scheduling is deterministic and remains bounded', async () => {
	const result = await createNativeGoalHarness({
		turns: [codedError('PLANNING_TIMEOUT'), ['mine']],
		timeoutMs: 100,
	}).run();
	assert.equal(result.goalScheduler.usesRealTimers, false);
	assert.ok(result.goalScheduler.scheduled > 0);
	assert.ok(result.goalScheduler.fired > 0);
	assert.ok(result.goalScheduler.maxPending <= 2, 'provider and body leases may overlap, but no third recovery handle may leak');
	assert.equal(result.goalScheduler.pending, 0);
});

test('completion results cannot override unsatisfied factual predicates', async () => {
	const result = await createNativeGoalHarness({
		completionResults: [true],
		turns: [['finish']],
		timeoutMs: 100,
	}).run();
	assert.equal(result.finalState, DynamicAgentState.PLANNING);
	assert.equal(result.sent.filter((entry) => entry.type === 'goal_completed').length, 1);
	assert.ok(result.recoveries.includes('COMPLETION_REJECTED'));
});

test('completion verifies the immutable compound position and block predicates', async () => {
	const result = await createNativeGoalHarness({
		goalPredicate: {
			type: 'all_of',
			predicates: [
				{ type: 'position_within', x: 1, y: 64, z: 0, radius: 0.01, stableTicks: 1 },
				{ type: 'block_matches', x: 0, y: 64, z: 0, blockId: 'minecraft:oak_log', properties: {} },
			],
		},
		turns: [
			['move_to'],
			[{ kind: 'finish', summary: 'Factual world predicates are present.' }],
		],
		timeoutMs: 100,
	}).run();
	assert.equal(result.finalState, DynamicAgentState.COMPLETED);
	assert.equal(result.recoveries.includes('COMPLETION_REJECTED'), false);
});

test('failed actions do not mutate simulated world facts', async () => {
	const result = await createNativeGoalHarness({
		actionResults: ['ACTION_TIMEOUT', 'PATH_BLOCKED', 'ACTION_TIMEOUT', 'ACTION_TIMEOUT'],
		moveDropBeforePickup: true,
		turns: [['mine'], ['move_to'], ['pick_up_item'], ['craft_inventory']],
		timeoutMs: 100,
	}).run();
	assert.deepEqual(result.world.position, { x: 0, y: 64, z: 0 });
	assert.equal(result.world.blocks.get('0,64,0'), 'minecraft:oak_log');
	assert.equal(result.world.drop.x, 1);
	assert.equal(result.world.inventory['minecraft:oak_log'] ?? 0, 0);
	assert.equal(result.world.inventory['minecraft:wooden_pickaxe'] ?? 0, 0);
});
