import test from 'node:test';
import assert from 'node:assert/strict';

import { VirtualWorld } from '../src/simulator/virtual-world.mjs';
import { VirtualMinecraftBridge } from '../src/simulator/virtual-minecraft-bridge.mjs';

function scenario() {
	return {
		seed: 9,
		agents: { alice: { position: { x: 0, y: 1, z: 0 } } },
		blocks: [{ x: 0, y: 0, z: 0, blockId: 'minecraft:stone' }],
	};
}

function command(actionId, actionType = 'move_to', args = { x: 1, y: 1, z: 0, tolerance: 0.1, sprint: false }) {
	return {
		traceId: 'trace-alice',
		goalRevision: 1,
		actionId,
		actionType,
		arguments: args,
		provenance: {
			provider: 'test',
			model: 'test-model',
			reasoningEffort: 'low',
			serviceTier: 'fast',
			programId: 'program-1-1',
			programVersion: 1,
			sourceStepId: 'step-1',
			eventSequence: 1,
			traceId: 'trace-alice',
		},
	};
}

function managerEvents() {
	const events = [];
	return {
		events,
		onObservation(_record, payload) { events.push({ type: 'observation', payload }); },
		onActionProgress(_record, payload) { events.push({ type: 'progress', payload }); },
		onActionResult(_record, payload) { events.push({ type: 'result', payload }); },
	};
}

test('accepted command produces RUNNING, changed observation, then one terminal result', async () => {
	const world = VirtualWorld.fromScenario(scenario());
	const bridge = new VirtualMinecraftBridge({ world, agentRecords: { alice: { agentId: 'alice', goalRevision: 1 } } });
	const manager = managerEvents();
	bridge.attach(manager);
	await bridge.send('action_command', 'alice', command('walk-1'));
	world.stepTicks(20);
	await bridge.flush();
	assert.deepEqual(bridge.events.map((event) => event.type), ['accepted', 'progress', 'observation', 'result']);
	assert.equal(manager.events.map((event) => event.type).join(','), 'progress,observation,result');
	assert.equal(manager.events.at(-1).payload.state, 'SUCCEEDED');
	assert.ok(manager.events[1].payload.observation.player.x > 0);
	assert.equal(bridge.validatedOutbound, 1);
	assert.equal(bridge.validatedInbound, 3);
});

test('missing action trace is rejected instead of synthesized by the virtual bridge', async () => {
	const world = VirtualWorld.fromScenario(scenario());
	const bridge = new VirtualMinecraftBridge({ world, agentRecords: { alice: { agentId: 'alice', goalRevision: 1 } } });
	await assert.rejects(bridge.send('action_command', 'alice', { ...command('missing-trace'), traceId: undefined }), /trace/i);
});

test('routine completion keeps its authoritative observation non-attentive before action_result', async () => {
	const world = VirtualWorld.fromScenario(scenario());
	const bridge = new VirtualMinecraftBridge({ world, agentRecords: { alice: { agentId: 'alice', goalRevision: 1 } } });
	const manager = managerEvents();
	bridge.attach(manager);
	await bridge.send('action_command', 'alice', command('routine-wait', 'wait', { durationMs: 50 }));
	world.stepTicks(1);
	await bridge.flush();

	const observation = bridge.events.find((event) => event.type === 'observation');
	const result = bridge.events.find((event) => event.type === 'result');
	assert.equal(observation.envelope.payload.attention, false);
	assert.deepEqual(observation.envelope.payload.changedFacts, []);
	assert.equal(observation.envelope.payload.lastResult.present, true);
	assert.equal(observation.envelope.payload.currentAction.active, false);
	assert.ok(observation.eventSequence < result.eventSequence);
	assert.deepEqual(manager.events.map((event) => event.type), ['progress', 'observation', 'result']);
});

test('completion retains attention for health, fire, and attacker hazard facts only', async () => {
	const world = VirtualWorld.fromScenario({
		agents: { alice: { position: { x: 0, y: 1, z: 1 }, onGround: true } },
		blocks: [{ x: 0, y: 0, z: 0, blockId: 'minecraft:stone' }, { x: 0, y: 0, z: 1, blockId: 'minecraft:lava' }],
	});
	const bridge = new VirtualMinecraftBridge({ world, agentRecords: { alice: { agentId: 'alice', goalRevision: 1 } } });
	await bridge.send('action_command', 'alice', command('hazard-wait', 'wait', { durationMs: 100 }));
	world.stepTicks(1);
	world.damage('alice', 1, { uuid: 'mob-1', type: 'minecraft:zombie', distance: 2 });
	world.stepTicks(1);
	await bridge.flush();

	const observation = bridge.events.find((event) => event.type === 'observation');
	assert.equal(observation.envelope.payload.attention, true);
	assert.deepEqual(observation.envelope.payload.changedFacts, ['player.health', 'player.lastAttacker', 'player.onFire']);
	assert.equal(observation.envelope.payload.currentAction.active, false);
});

test('explicit publish preserves valid non-active attention facts', async () => {
	const world = VirtualWorld.fromScenario(scenario());
	const bridge = new VirtualMinecraftBridge({ world, agentRecords: { alice: { agentId: 'alice', goalRevision: 1 } } });
	await bridge.publish('alice', { attention: true, changedFacts: ['blocks.1,1,0'] });
	await bridge.flush();

	const observation = bridge.events.find((event) => event.type === 'observation');
	assert.equal(observation.envelope.payload.attention, true);
	assert.deepEqual(observation.envelope.payload.changedFacts, ['blocks.1,1,0']);
});

test('cancellation emits one terminal cancellation and suppresses stale completion', async () => {
	const world = VirtualWorld.fromScenario(scenario());
	const bridge = new VirtualMinecraftBridge({ world, agentRecords: { alice: { agentId: 'alice', goalRevision: 1 } } });
	const manager = managerEvents();
	bridge.attach(manager);
	await bridge.send('action_command', 'alice', command('walk-2', 'move_to', { x: 15, y: 1, z: 0, tolerance: 0.1, sprint: false }));
	world.stepTicks(1);
	await bridge.send('action_cancel', 'alice', { goalRevision: 1, actionId: 'walk-2' });
	world.stepTicks(100);
	await bridge.flush();
	const results = manager.events.filter((event) => event.type === 'result');
	assert.equal(results.length, 1);
	assert.equal(results[0].payload.state, 'CANCELLED');
	assert.equal(bridge.events.filter((event) => event.type === 'result').length, 1);
});

test('a synchronous progress listener cancelling an action fences the stale executor', async () => {
	const world = VirtualWorld.fromScenario(scenario());
	const bridge = new VirtualMinecraftBridge({ world, agentRecords: { alice: { agentId: 'alice', goalRevision: 1 } } });
	const manager = managerEvents();
	manager.onActionProgress = (_record, payload) => {
		manager.events.push({ type: 'progress', payload });
		void bridge.send('action_cancel', 'alice', { goalRevision: 1, actionId: 'look-1' });
	};
	bridge.attach(manager);
	await bridge.send('action_command', 'alice', command('look-1', 'look_at', { x: 2, y: 1, z: 0 }));
	world.stepTicks(1);
	await bridge.flush();
	const results = manager.events.filter((event) => event.type === 'result');
	assert.equal(results.length, 1);
	assert.equal(results[0].payload.state, 'CANCELLED');
	assert.equal(results[0].payload.reasonCode, 'CANCELLED');
	assert.equal(bridge.events.filter((event) => event.type === 'result').length, 1);
});

test('cancellation requires the exact goal revision of the active action', async () => {
	const world = VirtualWorld.fromScenario(scenario());
	const bridge = new VirtualMinecraftBridge({ world, agentRecords: { alice: { agentId: 'alice', goalRevision: 2 } } });
	const manager = managerEvents();
	bridge.attach(manager);
	await bridge.send('action_command', 'alice', { ...command('walk-3'), goalRevision: 2 });
	await bridge.send('action_cancel', 'alice', { goalRevision: 1, actionId: 'walk-3' });
	world.stepTicks(1);
	assert.deepEqual(bridge.activeActionIds, ['alice']);
	assert.equal(manager.events.filter((event) => event.type === 'result').length, 0);
	await bridge.send('action_cancel', 'alice', { goalRevision: 2, actionId: 'walk-3' });
	await bridge.flush();
	assert.equal(manager.events.filter((event) => event.type === 'result').length, 1);
	assert.equal(manager.events.at(-1).payload.state, 'CANCELLED');
});

test('action setup rollback clears runtime and bridge state after a world setup failure', async () => {
	const world = VirtualWorld.fromScenario(scenario());
	const originalSetActiveAction = world.setActiveAction.bind(world);
	let failSetup = true;
	world.setActiveAction = (agentId, actionId, actionType) => {
		if (failSetup) {
			failSetup = false;
			throw new Error('simulated active-action setup failure');
		}
		return originalSetActiveAction(agentId, actionId, actionType);
	};
	const bridge = new VirtualMinecraftBridge({ world, agentRecords: { alice: { agentId: 'alice', goalRevision: 1 } } });
	await assert.rejects(bridge.send('action_command', 'alice', command('setup-fails', 'wait', { durationMs: 1 })), /simulated active-action setup failure/);
	assert.deepEqual(bridge.activeActionIds, []);
	assert.deepEqual(bridge.actionRuntimeSnapshot().runtime.active, []);
	await bridge.send('action_command', 'alice', command('setup-succeeds', 'wait', { durationMs: 1 }));
	world.stepTicks(1);
	await bridge.flush();
	assert.equal(bridge.actionRuntimeSnapshot().bridgeActiveAgentIds.length, 0);
});

test('accepted event listener failure rolls back the accepted trace with active state', async () => {
	const world = VirtualWorld.fromScenario(scenario());
	const bridge = new VirtualMinecraftBridge({ world, agentRecords: { alice: { agentId: 'alice', goalRevision: 1 } } });
	bridge.on('accepted', () => { throw new Error('accepted listener failed'); });
	await assert.rejects(bridge.send('action_command', 'alice', command('accepted-event-fails', 'wait', { durationMs: 1 })), /accepted listener failed/);
	assert.equal(bridge.events.some((event) => event.type === 'accepted' && event.envelope.payload.actionId === 'accepted-event-fails'), false);
	assert.deepEqual(bridge.activeActionIds, []);
	assert.deepEqual(bridge.actionRuntimeSnapshot().runtime.active, []);
	assert.equal(world.playerState('alice').activeAction, null);
	bridge.removeAllListeners('accepted');
	await assert.doesNotReject(bridge.send('action_command', 'alice', command('accepted-event-recovers', 'wait', { durationMs: 1 })));
});

test('a recorded agent missing from the world cannot leak an accepted action', async () => {
	const world = VirtualWorld.fromScenario({ agents: { bob: { position: { x: 0, y: 1, z: 0 } } } });
	const bridge = new VirtualMinecraftBridge({ world, agentRecords: { alice: { agentId: 'alice', goalRevision: 1 }, bob: { agentId: 'bob', goalRevision: 1 } } });
	await assert.rejects(bridge.send('action_command', 'alice', command('missing-world', 'wait', { durationMs: 1 })), (error) => error.code === 'UNKNOWN_AGENT');
	assert.deepEqual(bridge.activeActionIds, []);
	assert.deepEqual(bridge.actionRuntimeSnapshot().runtime.active, []);
	await bridge.send('action_command', 'bob', command('valid-world', 'wait', { durationMs: 1 }));
	world.stepTicks(1);
	await bridge.flush();
	assert.deepEqual(bridge.activeActionIds, []);
});

test('publish validates and adapts normalized observation-bound scenario data', async () => {
	const world = VirtualWorld.fromScenario({
		agents: {
			alice: {
				position: { x: 0, y: 1, z: 0 },
				effects: [{ effectId: 'minecraft:speed', amplifier: 1, durationTicks: 10 }],
			},
		},
		blocks: [{ x: 0, y: 0, z: 0, blockId: 'minecraft:stone', tags: ['#minecraft:mineable/pickaxe'] }],
		entities: [{ id: 'mob-1', type: 'minecraft:zombie', position: { x: 2, y: 1, z: 0 }, tags: ['#minecraft:hostile'] }],
	});
	world.damage('alice', 1, { uuid: 'mob-1', type: 'minecraft:zombie', distance: 2 });
	const bridge = new VirtualMinecraftBridge({ world, agentRecords: { alice: { agentId: 'alice', goalRevision: 1 } } });
	await assert.doesNotReject(bridge.publish('alice'));
	await bridge.flush();
	assert.equal(bridge.validatedInbound, 1);
});

test('bridge routes a protocol-valid missing recipe through the complete runtime', async () => {
	const world = VirtualWorld.fromScenario(scenario());
	const bridge = new VirtualMinecraftBridge({ world, agentRecords: { alice: { agentId: 'alice', goalRevision: 1 } } });
	const manager = managerEvents();
	bridge.attach(manager);
	await bridge.send('action_command', 'alice', command('craft-1', 'craft_inventory', { recipeId: 'minecraft:stick', count: 1, timeoutMs: 1_000 }));
	world.stepTicks(1);
	await bridge.flush();
	const result = manager.events.find((event) => event.type === 'result');
	assert.equal(result.payload.state, 'FAILED');
	assert.equal(result.payload.reasonCode, 'RECIPE_NOT_FOUND');
});

test('bridge executes a Task 4 mining action over multiple virtual ticks', async () => {
	const world = VirtualWorld.fromScenario({
		...scenario(),
		blocks: [
			{ x: 0, y: 0, z: 0, blockId: 'minecraft:stone' },
			{ x: 1, y: 1, z: 0, blockId: 'minecraft:stone' },
		],
	});
	const bridge = new VirtualMinecraftBridge({ world, agentRecords: { alice: { agentId: 'alice', goalRevision: 1 } } });
	const manager = managerEvents();
	bridge.attach(manager);
	await bridge.send('action_command', 'alice', command('mine-1', 'break_block', { x: 1, y: 1, z: 0, expectedBlockId: 'minecraft:stone', timeoutMs: 1_000 }));
	world.stepTicks(1);
	assert.ok(world.blockAt(1, 1, 0), 'the block must remain while mining is in progress');
	world.stepTicks(5);
	await bridge.flush();
	const result = manager.events.find((event) => event.type === 'result');
	assert.equal(result.payload.state, 'SUCCEEDED');
	assert.equal(world.blockAt(1, 1, 0), null);
	assert.equal(world.observation('alice').inventory.items.find((item) => item.itemId === 'minecraft:cobblestone')?.count, 1);
});
