import test from 'node:test';
import assert from 'node:assert/strict';

import { ACTION_FIELDS, MAX_CONVERSATION_LENGTH } from '../src/constants.mjs';
import { validateAction } from '../src/schema.mjs';
import { ActionRuntime, SUPPORTED_SIMULATOR_ACTIONS } from '../src/simulator/action-runtime.mjs';
import { MAX_PLAYER_INVENTORY_ITEMS, VirtualWorld } from '../src/simulator/virtual-world.mjs';

const PLAYER = 'alice';
const MOB = '00000000-0000-4000-8000-000000000001';

function command(actionId, actionType, argumentsValue, overrides = {}) {
	return {
		agentId: PLAYER,
		goalRevision: 1,
		actionId,
		actionType,
		arguments: argumentsValue,
		provenance: {
			provider: 'simulator',
			model: 'fixture',
			reasoningEffort: 'low',
			serviceTier: 'fast',
			programId: 'simulator',
			programVersion: 1,
			sourceStepId: actionId,
			eventSequence: 1,
		},
		...overrides,
	};
}

function world(overrides = {}) {
	return VirtualWorld.fromScenario({
		seed: 7,
		agents: { [PLAYER]: { position: { x: 0, y: 1, z: 0 }, onGround: true } },
		blocks: [0, 1, 2].map((x) => ({ x, y: 0, z: 0, blockId: 'minecraft:stone' })),
		items: [],
		entities: [],
		...overrides,
	});
}

function run(runtime, simulationWorld, actionId, limit = 200) {
	let result;
	for (let index = 0; index < limit && !result; index += 1) {
		runtime.tick(simulationWorld);
		result = runtime.resultFor(actionId);
	}
	return result;
}

test('action runtime validates exact production action fields and rejects unknown fields', () => {
	assert.deepEqual(ACTION_FIELDS.move_to, ['x', 'y', 'z', 'tolerance', 'sprint']);
	const runtime = new ActionRuntime();
	assert.throws(() => runtime.accept(command('bad', 'move_to', { x: 1, y: 1, z: 0, tolerance: 0.1, sprint: false, extra: true })), /UNKNOWN_FIELD|INVALID_ACTION/);
});

test('movement is a multi-tick action with displacement and a bounded yaw rate', () => {
	const simulationWorld = world();
	const runtime = new ActionRuntime();
	runtime.accept(command('walk', 'move_to', { x: 2, y: 1, z: 0, tolerance: 0.1, sprint: true }));
	const start = simulationWorld.playerState(PLAYER);
	const first = runtime.tick(simulationWorld);
	const afterFirst = simulationWorld.playerState(PLAYER);
	assert.deepEqual(first, []);
	assert.equal(runtime.snapshot().active[0].actionType, 'move_to');
	assert.ok(afterFirst.position.x >= start.position.x, 'movement must displace over ticks');
	assert.ok(Math.abs(afterFirst.yaw - start.yaw) <= 12, 'yaw must be rate limited');
	const result = run(runtime, simulationWorld, 'walk');
	assert.equal(result.state, 'SUCCEEDED');
	assert.ok(simulationWorld.playerState(PLAYER).position.x >= 1.8);
});

test('movement rejects a destination whose actor body intersects a solid block', () => {
	const simulationWorld = world({
		blocks: [
			{ x: 0, y: 0, z: 0, blockId: 'minecraft:stone' },
			{ x: 1, y: 0, z: 0, blockId: 'minecraft:stone' },
			{ x: 2, y: 0, z: 0, blockId: 'minecraft:stone' },
			{ x: 1, y: 1, z: 0, blockId: 'minecraft:stone' },
		],
	});
	const runtime = new ActionRuntime();
	runtime.accept(command('blocked-destination', 'navigate_to', {
		x: 1.2, y: 1, z: 0, tolerance: 1, sprint: false, timeoutMs: 1_000,
	}));
	const result = run(runtime, simulationWorld, 'blocked-destination');
	assert.equal(result.state, 'FAILED');
	assert.equal(result.reasonCode, 'DESTINATION_BLOCKED');
	assert.ok(simulationWorld.playerState(PLAYER).position.x < 1, 'failed movement must not enter the wall');
});

test('movement times out after physics stops the actor at a wall', () => {
	const simulationWorld = world({
		blocks: [0, 1, 2, 3].map((x) => ({ x, y: 0, z: 0, blockId: 'minecraft:stone' })).concat([
			{ x: 1, y: 1, z: 0, blockId: 'minecraft:stone' },
		]),
	});
	const runtime = new ActionRuntime();
	runtime.accept(command('wall-stall', 'navigate_to', {
		x: 3, y: 1, z: 0, tolerance: 0.1, sprint: false, timeoutMs: 1_000,
	}));
	const result = run(runtime, simulationWorld, 'wall-stall');
	assert.equal(result.state, 'TIMED_OUT');
	assert.equal(result.reasonCode, 'ACTION_TIMEOUT');
	assert.ok(simulationWorld.playerState(PLAYER).position.x <= 0.7, 'physics must keep the actor on the near side of the wall');
});

test('mining takes simulated time, then removes the block and creates the declared drop', () => {
	const simulationWorld = world({ blocks: [{ x: 1, y: 1, z: 0, blockId: 'minecraft:stone' }] });
	const runtime = new ActionRuntime();
	runtime.accept(command('mine', 'break_block', { x: 1, y: 1, z: 0, expectedBlockId: 'minecraft:stone', timeoutMs: 1_000 }));
	runtime.tick(simulationWorld);
	assert.ok(simulationWorld.blockAt(1, 1, 0), 'target must remain until mining completes');
	assert.equal(runtime.resultFor('mine'), undefined);
	const result = run(runtime, simulationWorld, 'mine');
	assert.equal(result.state, 'SUCCEEDED');
	assert.equal(simulationWorld.blockAt(1, 1, 0), null);
	assert.equal(simulationWorld.observation(PLAYER).inventory.items.find((item) => item.itemId === 'minecraft:cobblestone')?.count, 1);
});

test('crafting consumes exact ingredients and emits exact requested output count', () => {
	const simulationWorld = world({ agents: { [PLAYER]: {
		position: { x: 0, y: 1, z: 0 },
		onGround: true,
		inventory: { items: [{ itemId: 'minecraft:oak_log', count: 2, slot: 0 }] },
	} } });
	const runtime = new ActionRuntime();
	runtime.accept(command('craft', 'craft_inventory', { recipeId: 'minecraft:planks', count: 8, timeoutMs: 1_000 }));
	const result = run(runtime, simulationWorld, 'craft');
	assert.equal(result.state, 'SUCCEEDED');
	const items = simulationWorld.observation(PLAYER).inventory.items;
	assert.equal(items.find((item) => item.itemId === 'minecraft:oak_log')?.count ?? 0, 0);
	assert.equal(items.find((item) => item.itemId === 'minecraft:oak_planks')?.count, 8);
});

test('placement consumes the item and verifies the final block postcondition', () => {
	const simulationWorld = world({ agents: { [PLAYER]: {
		position: { x: 0, y: 1, z: 0 },
		onGround: true,
		inventory: { items: [{ itemId: 'minecraft:cobblestone', count: 1, slot: 0 }] },
	} } });
	const runtime = new ActionRuntime();
	runtime.accept(command('place', 'place_block', { x: 1, y: 1, z: 0, face: 'up', itemId: 'minecraft:cobblestone' }));
	const result = run(runtime, simulationWorld, 'place');
	assert.equal(result.state, 'SUCCEEDED');
	assert.equal(simulationWorld.blockAt(1, 1, 0).blockId, 'minecraft:cobblestone');
	assert.equal(simulationWorld.observation(PLAYER).inventory.items.find((item) => item.itemId === 'minecraft:cobblestone')?.count ?? 0, 0);
});

test('combat uses cooldown-limited hits and shield mitigation', () => {
	const simulationWorld = world({
		agents: { [PLAYER]: { position: { x: 0, y: 1, z: 0 }, onGround: true } },
		entities: [{ id: MOB, type: 'minecraft:zombie', position: { x: 1, y: 1, z: 0 }, health: 10, maxHealth: 10 }],
	});
	const runtime = new ActionRuntime();
	runtime.accept(command('shield', 'block_with_shield', { durationMs: 200 }));
	runtime.tick(simulationWorld);
	const before = simulationWorld.playerState(PLAYER).health;
	simulationWorld.damage(PLAYER, 4, { uuid: MOB, type: 'minecraft:zombie', distance: 1 });
	assert.ok(simulationWorld.playerState(PLAYER).health >= before - 2, 'shield must mitigate damage');
	runtime.cancel('shield');
	runtime.accept(command('attack', 'attack', { targetId: MOB, timeoutMs: 1_000 }));
	const result = run(runtime, simulationWorld, 'attack', 100);
	assert.equal(result.state, 'SUCCEEDED');
	assert.ok(simulationWorld.entityState(MOB).health < 10);
});

test('death and respawn use the recorded checkpoint, and timeout is measured in virtual ticks', () => {
	const simulationWorld = world({ agents: { [PLAYER]: { position: { x: 0, y: 1, z: 0 }, onGround: true, health: 1 } } });
	simulationWorld.recordCheckpoint(PLAYER, { x: 8, y: 1, z: 8 });
	simulationWorld.damage(PLAYER, 5);
	const runtime = new ActionRuntime();
	runtime.accept(command('respawn', 'respawn', {}));
	const respawnResult = run(runtime, simulationWorld, 'respawn');
	assert.equal(respawnResult.state, 'SUCCEEDED');
	assert.deepEqual(simulationWorld.playerState(PLAYER).position, { x: 8, y: 1, z: 8 });

	const stalled = new ActionRuntime();
	stalled.accept(command('timeout', 'navigate_to', { x: 50, y: 1, z: 0, tolerance: 0.1, sprint: false, timeoutMs: 1 }));
	const timedOut = run(stalled, simulationWorld, 'timeout', 10);
	assert.equal(timedOut.state, 'TIMED_OUT');
	assert.equal(timedOut.reasonCode, 'ACTION_TIMEOUT');
});

test('cancellation fences stale completion and unsupported actions fail explicitly', () => {
	const simulationWorld = world();
	const runtime = new ActionRuntime();
	runtime.accept(command('cancel-me', 'navigate_to', { x: 30, y: 1, z: 0, tolerance: 0.1, sprint: false, timeoutMs: 1_000 }));
	runtime.tick(simulationWorld);
	assert.equal(runtime.cancel('cancel-me').state, 'CANCELLED');
	runtime.tick(simulationWorld);
	assert.equal(runtime.resultFor('cancel-me').state, 'CANCELLED');
	runtime.accept(command('unknown', 'set_door', { x: 1, y: 1, z: 0, open: true }));
	runtime.tick(simulationWorld);
	assert.equal(runtime.resultFor('unknown').reasonCode, 'SIMULATOR_UNSUPPORTED_ACTION');
});

test('mining keeps the target block and inventory bytes unchanged when its drop cannot fit', () => {
	const inventory = Array.from({ length: MAX_PLAYER_INVENTORY_ITEMS }, (_, slot) => ({ itemId: `minecraft:fixture_${slot}`, count: 1, slot }));
	const simulationWorld = world({
		agents: { [PLAYER]: { position: { x: 0, y: 1, z: 0 }, onGround: true, inventory: { items: inventory } } },
		blocks: [{ x: 0, y: 0, z: 0, blockId: 'minecraft:stone' }, { x: 1, y: 1, z: 0, blockId: 'minecraft:stone' }],
	});
	const before = simulationWorld.inventories(PLAYER);
	const runtime = new ActionRuntime();
	runtime.accept(command('full-mine', 'break_block', { x: 1, y: 1, z: 0, expectedBlockId: 'minecraft:stone', timeoutMs: 1_000 }));
	const result = run(runtime, simulationWorld, 'full-mine');
	assert.equal(result.state, 'FAILED');
	assert.equal(result.reasonCode, 'WORLD_CAPACITY_EXCEEDED');
	assert.deepEqual(simulationWorld.blockAt(1, 1, 0).blockId, 'minecraft:stone');
	assert.deepEqual(simulationWorld.inventories(PLAYER), before);
});

test('crafting restores exact inventory bytes when output insertion fails at capacity', () => {
	const inventory = [{ itemId: 'minecraft:oak_log', count: 1, slot: 0 }, ...Array.from({ length: MAX_PLAYER_INVENTORY_ITEMS - 1 }, (_, index) => ({ itemId: `minecraft:fixture_${index}`, count: 1, slot: index + 1 }))];
	const simulationWorld = world({ agents: { [PLAYER]: { position: { x: 0, y: 1, z: 0 }, onGround: true, inventory: { items: inventory } } } });
	const before = simulationWorld.inventories(PLAYER);
	const originalAdd = simulationWorld.addInventoryItem.bind(simulationWorld);
	simulationWorld.addInventoryItem = (agentId, itemId, count) => itemId === 'minecraft:oak_planks' ? (() => { throw Object.assign(new Error('fixture output capacity'), { code: 'WORLD_CAPACITY_EXCEEDED' }); })() : originalAdd(agentId, itemId, count);
	const runtime = new ActionRuntime();
	runtime.accept(command('full-craft', 'craft_inventory', { recipeId: 'minecraft:planks', count: 4, timeoutMs: 1_000 }));
	const result = run(runtime, simulationWorld, 'full-craft');
	assert.equal(result.state, 'FAILED');
	assert.deepEqual(simulationWorld.inventories(PLAYER), before);
});

test('placement requires solid face support and an authoritative desired state without consuming on failure', () => {
	const simulationWorld = world({ agents: { [PLAYER]: { position: { x: 0, y: 1, z: 0 }, onGround: true, inventory: { items: [{ itemId: 'minecraft:cobblestone', count: 1, slot: 0 }] } } } });
	const before = simulationWorld.inventories(PLAYER);
	const unsupported = new ActionRuntime();
	unsupported.accept(command('bad-support', 'place_block', { x: 4, y: 1, z: 0, face: 'up', itemId: 'minecraft:cobblestone' }));
	const unsupportedResult = run(unsupported, simulationWorld, 'bad-support');
	assert.equal(unsupportedResult.reasonCode, 'PLACEMENT_UNSUPPORTED');
	assert.deepEqual(simulationWorld.inventories(PLAYER), before);

	const mismatchWorld = world({ agents: { [PLAYER]: { position: { x: 0, y: 1, z: 0 }, onGround: true, inventory: { items: [{ itemId: 'minecraft:cobblestone', count: 1, slot: 0 }] } } } });
	const originalBlockAt = mismatchWorld.blockAt.bind(mismatchWorld);
	mismatchWorld.blockAt = (...args) => {
		const block = originalBlockAt(...args);
		return block?.blockId === 'minecraft:cobblestone' ? { ...block, desiredState: 'mismatch' } : block;
	};
	const mismatch = new ActionRuntime();
	mismatch.accept(command('bad-state', 'place_block', { x: 1, y: 1, z: 0, face: 'up', itemId: 'minecraft:cobblestone', desiredState: 'facing=north' }));
	const mismatchResult = run(mismatch, mismatchWorld, 'bad-state');
	assert.equal(mismatchResult.reasonCode, 'PLACEMENT_STATE_MISMATCH');
	assert.equal(mismatchWorld.blockAt(1, 1, 0), null);
	assert.equal(mismatchWorld.inventories(PLAYER).items.find((item) => item.itemId === 'minecraft:cobblestone')?.count, 1);
});

test('placement treats null desiredState as absent and rolls back normalize or insert failures exactly', () => {
	const simulationWorld = world({ agents: { [PLAYER]: { position: { x: 0, y: 1, z: 0 }, onGround: true, inventory: { items: [{ itemId: 'minecraft:cobblestone', count: 1, slot: 0 }] } } } });
	const runtime = new ActionRuntime();
	runtime.accept(command('place-null-state', 'place_block', { x: 1, y: 1, z: 0, face: 'up', itemId: 'minecraft:cobblestone', desiredState: null }));
	const result = run(runtime, simulationWorld, 'place-null-state');
	assert.equal(result.state, 'SUCCEEDED');
	assert.equal(simulationWorld.blockAt(1, 1, 0).desiredState, undefined);
	assert.equal(simulationWorld.inventories(PLAYER).items.find((item) => item.itemId === 'minecraft:cobblestone')?.count ?? 0, 0);

	const invalidWorld = world({ agents: { [PLAYER]: { position: { x: 0, y: 1, z: 0 }, onGround: true, inventory: { items: [{ itemId: 'minecraft:cobblestone', count: 1, slot: 0 }] } } } });
	const beforeInvalid = invalidWorld.inventories(PLAYER);
	assert.throws(() => invalidWorld.performSimulationAction(PLAYER, { type: 'place_block', arguments: { x: 1, y: 1, z: 0, face: 'up', itemId: 'minecraft:cobblestone', desiredState: '' } }), /desiredState/);
	assert.deepEqual(invalidWorld.inventories(PLAYER), beforeInvalid);
	assert.equal(invalidWorld.blockAt(1, 1, 0), null);

	const fullWorld = world({
		agents: { [PLAYER]: { position: { x: 0, y: 1, z: 0 }, onGround: true, inventory: { items: [{ itemId: 'minecraft:cobblestone', count: 1, slot: 0 }] } } },
		blocks: Array.from({ length: 256 }, (_, index) => ({ x: index, y: 0, z: 0, blockId: 'minecraft:stone' })),
	});
	const beforeInsert = fullWorld.inventories(PLAYER);
	const insertResult = fullWorld.performSimulationAction(PLAYER, { type: 'place_block', arguments: { x: 255, y: 1, z: 0, face: 'up', itemId: 'minecraft:cobblestone', desiredState: null } });
	assert.equal(insertResult.reasonCode, 'WORLD_CAPACITY_EXCEEDED');
	assert.deepEqual(fullWorld.inventories(PLAYER), beforeInsert);
	assert.equal(fullWorld.blockAt(255, 1, 0), null);
});

test('direct messages accept the production code-point maximum and reject one beyond it', () => {
	const simulationWorld = world();
	const maximum = '😀'.repeat(MAX_CONVERSATION_LENGTH);
	assert.equal([...maximum].length, MAX_CONVERSATION_LENGTH);
	const action = { type: 'chat', message: maximum, audience: 'direct', recipientId: MOB };
	const overMaximum = `${maximum}${[...maximum][0]}`;
	assert.doesNotThrow(() => validateAction(action));
	assert.throws(() => validateAction({ ...action, message: overMaximum }), /OUT_OF_RANGE|MAX_CONVERSATION_LENGTH|message/);
	assert.equal(simulationWorld.recordDirectMessage(PLAYER, 'bob', maximum).message, maximum);
	assert.throws(() => simulationWorld.recordDirectMessage(PLAYER, 'bob', `${maximum}😀`), /MESSAGE_TOO_LARGE/);
});

test('every production action type is implemented or terminates with an explicit simulator failure', () => {
	const uuid = MOB;
	const inputFrame = { forward: 1, strafe: 0, jump: false, sneak: false, sprint: true, attack: false, use: false, yaw: 0, pitch: 0, selectedSlot: 0, hand: 'main', ticks: 1 };
	const validArguments = {
		move_to: { x: 1, y: 1, z: 0, tolerance: 0.1, sprint: false },
		control: inputFrame,
		control_sequence: { frames: [inputFrame], maxTicks: 1 },
		look_at: { x: 1, y: 1, z: 0 },
		attack: { targetId: uuid, timeoutMs: 1_000 },
		select_item: { itemId: 'minecraft:stick' },
		use_item: { durationMs: 1 },
		break_block: { x: 1, y: 1, z: 0, expectedBlockId: 'minecraft:stone', timeoutMs: 1_000 },
		pick_up_item: { targetSelector: uuid },
		place_block: { x: 1, y: 1, z: 0, face: 'up', itemId: 'minecraft:cobblestone' },
		chat: { message: 'hi', audience: 'direct', recipientId: uuid },
		wait: { durationMs: 1 },
		set_door: { x: 1, y: 1, z: 0, open: true },
		drop_item: { slot: 0, count: 1 },
		navigate_to: { x: 1, y: 1, z: 0, tolerance: 0.1, sprint: false, timeoutMs: 1_000 },
		transfer_container: { x: 1, y: 1, z: 0, sourceKind: 'player', sourceSlot: 0, destinationKind: 'container', destinationSlot: 0, count: 1, expectedItemId: 'minecraft:stick', timeoutMs: 1_000 },
		craft_inventory: { recipeId: 'minecraft:planks', count: 1, timeoutMs: 1_000 },
		craft_table: { recipeId: 'minecraft:planks', x: 1, y: 0, z: 0, count: 1, timeoutMs: 1_000 },
		furnace_transaction: { x: 1, y: 0, z: 0, operation: 'insert_input', inventorySlot: 0, count: 1, expectedItemId: 'minecraft:stick', timeoutMs: 1_000 },
		equip_item: { sourceSlot: 0, targetSlot: 'head', expectedItemId: 'minecraft:stick' },
		select_tool: { sourceSlot: 0, hotbarSlot: 0, expectedItemId: 'minecraft:stick', minRemainingDurability: 0 },
		block_with_shield: { durationMs: 1 },
		use_ranged: { targetId: uuid, drawDurationMs: 1, timeoutMs: 1_000 },
		interact_block: { x: 1, y: 0, z: 0, face: 'up', hand: 'main', expectedItemId: 'minecraft:stick' },
		interact_entity: { targetId: uuid, hand: 'main', expectedItemId: 'minecraft:stick' },
		dismount: {},
		start_fall_flying: {},
		wake_up: {},
		set_flight: { enabled: true },
		write_sign: { x: 1, y: 1, z: 0, front: true, lines: ['marker', '', '', ''], expectedLines: ['', '', '', ''] },
		edit_book: { slot: 0, pages: ['field notes'], expectedFingerprint: 'fixture-fingerprint' },
		menu_click: { menuId: 'menu', containerId: 1, stateId: 0, slot: 0, button: 0, clickType: 'PICKUP', expectedItemId: 'minecraft:stick', expectedCount: 1 },
		menu_close: { menuId: 'menu', containerId: 1, stateId: 0 },
		beacon_effects: { menuId: 'menu', containerId: 1, stateId: 0, primaryEffectId: 'minecraft:speed', secondaryEffectId: 'minecraft:regeneration' },
		menu_transfer: { menuId: 'menu', sourceSlot: 0, destinationSlot: 1, count: 1, expectedItemId: 'minecraft:stick', timeoutMs: 1_000 },
		menu_button: { menuId: 'menu', buttonId: 0, timeoutMs: 1_000 },
		anvil_rename: { menuId: 'menu', name: 'name', timeoutMs: 1_000 },
		respawn: {},
	};
	assert.deepEqual(Object.keys(validArguments).sort(), Object.keys(ACTION_FIELDS).sort(), 'each production action needs a valid fixture before simulator support can be evaluated');
	for (const actionType of Object.keys(ACTION_FIELDS)) {
		const simulationWorld = world({ entities: [{ id: uuid, type: 'minecraft:zombie', position: { x: 1, y: 1, z: 0 }, health: 3, maxHealth: 3 }], blocks: [{ x: 0, y: 0, z: 0, blockId: 'minecraft:stone' }, { x: 1, y: 0, z: 0, blockId: 'minecraft:crafting_table' }, { x: 1, y: 1, z: 0, blockId: 'minecraft:stone' }], agents: { [PLAYER]: { position: { x: 0, y: 1, z: 0 }, onGround: true, inventory: { items: [{ itemId: 'minecraft:stick', count: 4, slot: 0 }, { itemId: 'minecraft:cobblestone', count: 4, slot: 1 }, { itemId: 'minecraft:oak_log', count: 4, slot: 2 }] } } } });
		const runtime = new ActionRuntime();
		const actionId = `coverage-${actionType}`;
		runtime.accept(command(actionId, actionType, validArguments[actionType]));
		runtime.tick(simulationWorld);
		if (runtime.resultFor(actionId) === undefined) runtime.cancel(actionId);
		const result = runtime.resultFor(actionId);
		assert.ok(result && ['SUCCEEDED', 'FAILED', 'CANCELLED', 'TIMED_OUT'].includes(result.state), `${actionType} must terminate or be explicitly cancellable`);
		if (!SUPPORTED_SIMULATOR_ACTIONS.includes(actionType)) assert.equal(result.reasonCode, 'SIMULATOR_UNSUPPORTED_ACTION', `${actionType} must not silently succeed`);
	}
});
