import test from 'node:test';
import assert from 'node:assert/strict';

import { validateAction } from '../src/schema.mjs';
import { ActionRuntime } from '../src/simulator/action-runtime.mjs';
import { VirtualWorld } from '../src/simulator/virtual-world.mjs';
import {
	SIMULATOR_SCENARIOS,
	getSimulatorScenario,
	listSimulatorScenarios,
	runScenarioSuccess,
} from '../src/simulator/simulator-scenarios.mjs';

test('scenario manifests are fixed, immutable, and cover the complete benchmark surface', () => {
	const expected = [
		'stone-tool-gathering', 'obstacle-navigation', 'inventory-crafting',
		'block-placement', 'hostile-mob-combat', 'lava-damage-reaction',
		'checkpoint-respawn', 'direct-message-wake', 'stalled-action',
		'invalid-decision-correction',
	];
	assert.deepEqual(listSimulatorScenarios(), expected);
	assert.ok(Object.isFrozen(SIMULATOR_SCENARIOS));
	assert.ok(Object.isFrozen(getSimulatorScenario('stone-tool-gathering')));
	assert.throws(() => { getSimulatorScenario('stone-tool-gathering').id = 'mutated'; }, TypeError);
});

test('scenario lookup rejects unknown manifests and success is authoritative', () => {
	assert.equal(getSimulatorScenario('missing'), undefined);
	const manifest = getSimulatorScenario('stalled-action');
	assert.equal(typeof manifest.success, 'function');
	assert.equal(runScenarioSuccess(manifest, { result: { actionId: 'stall-navigation', state: 'TIMED_OUT', reasonCode: 'ACTION_TIMEOUT' } }), true);
	assert.equal(runScenarioSuccess(manifest, { result: { actionId: 'stall-navigation', state: 'SUCCEEDED' } }), false);
	const correction = getSimulatorScenario('invalid-decision-correction');
	assert.equal(runScenarioSuccess(correction, { events: [{ invalidDecisionId: correction.expected.invalidDecisionId, correctedDecisionId: correction.expected.correctedDecisionId, accepted: true }] }), true);
});

test('every scenario is an executable immutable fixture with valid deterministic inputs and strong near-miss rejection', () => {
	for (const id of listSimulatorScenarios()) {
		const manifest = getSimulatorScenario(id);
		assert.equal(typeof manifest.agentId, 'string', `${id} needs a fixed agentId`);
		assert.equal(typeof manifest.goal, 'string', `${id} needs a fixed goal`);
		assert.ok(Array.isArray(manifest.commands), `${id} needs deterministic commands`);
		assert.ok(Array.isArray(manifest.events), `${id} needs deterministic events`);
		assert.ok(manifest.expected && typeof manifest.expected === 'object', `${id} needs explicit expected postconditions`);
		assert.doesNotThrow(() => VirtualWorld.fromScenario(manifest.world), `${id} world must be accepted by VirtualWorld`);
		for (const command of manifest.commands) {
			assert.doesNotThrow(() => validateAction({ type: command.actionType, ...command.arguments }), `${id} command must use production schema`);
		}
		assert.equal(runScenarioSuccess(manifest, {}), false, `${id} must not succeed from empty state`);
		assert.equal(runScenarioSuccess(manifest, { result: { state: 'FAILED' }, state: {}, events: [] }), false, `${id} must reject a failed near miss`);
	}
});

test('every command-bearing fixture reaches a deterministic terminal result in VirtualWorld', () => {
	for (const id of listSimulatorScenarios()) {
		const manifest = getSimulatorScenario(id);
		if (manifest.commands.length === 0) continue;
		const simulationWorld = VirtualWorld.fromScenario(manifest.world);
		const runtime = new ActionRuntime();
		for (const fixtureCommand of manifest.commands) {
			runtime.accept({ ...fixtureCommand, agentId: manifest.agentId, goalRevision: 1 });
			for (let tick = 0; tick < 2_000 && runtime.resultFor(fixtureCommand.actionId) === undefined; tick += 1) runtime.tick(simulationWorld);
			const result = runtime.resultFor(fixtureCommand.actionId);
			assert.ok(result && ['SUCCEEDED', 'FAILED', 'CANCELLED', 'TIMED_OUT'].includes(result.state), `${id}/${fixtureCommand.actionId} must terminate`);
		}
	}
});

test('scenario success predicates require authoritative postconditions, not provider prose', () => {
	const obstacle = getSimulatorScenario('obstacle-navigation');
	assert.equal(runScenarioSuccess(obstacle, {
		results: obstacle.expected.actionIds.map((actionId) => ({ actionId, state: 'SUCCEEDED' })),
		state: { waypoints: obstacle.expected.waypoints, obstacle: obstacle.expected.obstacle },
	}), true);
	assert.equal(runScenarioSuccess(obstacle, {
		results: obstacle.expected.actionIds.map((actionId) => ({ actionId, state: 'SUCCEEDED' })),
		state: { position: obstacle.expected.waypoints.at(-1), obstacle: obstacle.expected.obstacle },
	}), false);

	const craft = getSimulatorScenario('inventory-crafting');
	assert.equal(runScenarioSuccess(craft, {
		results: craft.expected.actionIds.map((actionId) => ({ actionId, state: 'SUCCEEDED' })),
		state: {
			inventoryBefore: [{ itemId: 'minecraft:oak_log', count: 2 }],
			inventoryAfter: [{ itemId: 'minecraft:oak_planks', count: 2 }, { itemId: 'minecraft:stick', count: 1 }],
			block: { x: 1, y: 1, z: 0, blockId: 'minecraft:crafting_table' },
		},
	}), true);
	assert.equal(runScenarioSuccess(craft, { results: craft.expected.actionIds.map((actionId) => ({ actionId, state: 'SUCCEEDED' })), state: { inventoryAfter: [{ itemId: 'minecraft:oak_planks', count: 7 }] } }), false);

	const placement = getSimulatorScenario('block-placement');
	assert.equal(runScenarioSuccess(placement, {
		result: { actionId: 'place-exact', state: 'SUCCEEDED' },
		state: { inventoryBefore: [{ itemId: 'minecraft:cobblestone', count: 1 }], inventoryAfter: [], block: { x: 1, y: 1, z: 0, blockId: 'minecraft:cobblestone', desiredState: 'facing=north' } },
	}), true);
	assert.equal(runScenarioSuccess(placement, { result: { actionId: 'place-exact', state: 'SUCCEEDED' }, state: { block: { blockId: 'minecraft:cobblestone' } } }), false);

	const combat = getSimulatorScenario('hostile-mob-combat');
	assert.equal(runScenarioSuccess(combat, { result: { actionId: 'combat-attack', state: 'SUCCEEDED' }, state: { target: { id: combat.expected.targetId, dead: true } } }), true);
	assert.equal(runScenarioSuccess(combat, { result: { actionId: 'combat-attack', state: 'SUCCEEDED' }, state: { target: { id: 'other', dead: true } } }), false);

	const directMessage = getSimulatorScenario('direct-message-wake');
	assert.equal(runScenarioSuccess(directMessage, { result: { actionId: 'direct-message-1', state: 'SUCCEEDED' }, events: [{ eventId: directMessage.expected.eventId, sourceId: directMessage.expected.sourceId, recipientId: directMessage.expected.recipientId, wakeAcknowledged: true, processed: true }] }), true);
	assert.equal(runScenarioSuccess(directMessage, { result: { actionId: 'direct-message-1', state: 'SUCCEEDED' }, events: [{ eventId: directMessage.expected.eventId, sourceId: 'other', recipientId: directMessage.expected.recipientId, wakeAcknowledged: true, processed: true }] }), false);

	const correction = getSimulatorScenario('invalid-decision-correction');
	assert.equal(runScenarioSuccess(correction, { events: [{ invalidDecisionId: correction.expected.invalidDecisionId, correctedDecisionId: correction.expected.correctedDecisionId, accepted: true }] }), true);
	assert.equal(runScenarioSuccess(correction, { events: [{ invalidDecisionId: 'other', correctedDecisionId: correction.expected.correctedDecisionId, accepted: true }] }), false);

	const lava = getSimulatorScenario('lava-damage-reaction');
	assert.equal(runScenarioSuccess(lava, {
		results: lava.expected.actionIds.map((actionId) => ({ actionId, state: 'SUCCEEDED' })),
		state: { hazard: { eventId: lava.expected.hazardEventId, hazardType: 'lava', healthBefore: 20, healthAfter: 19 }, reaction: 'leave_hazard', position: { x: 3, y: 1, z: 1 } },
	}), true);
	assert.equal(runScenarioSuccess(lava, {
		results: lava.expected.actionIds.map((actionId) => ({ actionId, state: 'SUCCEEDED' })),
		state: { hazard: { eventId: lava.expected.hazardEventId, hazardType: 'lava', healthBefore: 20, healthAfter: 20 }, reaction: 'leave_hazard', position: { x: 3, y: 1, z: 1 } },
	}), false);
});
