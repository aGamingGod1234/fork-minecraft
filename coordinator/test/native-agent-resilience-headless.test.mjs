import assert from 'node:assert/strict';
import test from 'node:test';

import {
	createNativeGoalHarness,
	woodenPickaxeFaultScenario,
} from './fixtures/native-goal-harness.mjs';

test('wooden pickaxe goal survives injected faults across native turns and verifies completion', async () => {
	const result = await createNativeGoalHarness(woodenPickaxeFaultScenario()).run();
	assert.equal(result.finalState, 'COMPLETED');
	assert.equal(result.inventory.get('minecraft:wooden_pickaxe'), 1);
	assert.ok(result.providerTurns >= 5);
	assert.ok(result.recoveries.includes('PATH_BLOCKED'));
	assert.ok(result.recoveries.includes('PLANNING_TIMEOUT'));
	assert.ok(result.recoveries.includes('BRIDGE_DISCONNECTED'));
	assert.equal(result.maxRecoveryHandles, 1);
	assert.equal(result.states.includes('ERROR'), false);
	assert.equal(result.states.includes('PAUSED'), false);
});
