import test from 'node:test';
import assert from 'node:assert/strict';

import { VirtualWorld, MAX_PLAYER_INVENTORY_ITEMS, MAX_WORLD_BLOCKS, MAX_WORLD_ENTITIES, MAX_WORLD_ITEMS } from '../src/simulator/virtual-world.mjs';

function scenario(overrides = {}) {
	return {
		seed: 42,
		dimension: 'minecraft:overworld',
		agents: {
			'alice': {
				position: { x: 0, y: 1, z: 0 },
				health: 20,
			},
		},
		blocks: [
			{ x: 0, y: 0, z: 0, blockId: 'minecraft:stone' },
		],
		items: [],
		entities: [],
		...overrides,
	};
}

test('equal seeds produce equal observations and different seeds affect only declared random events', () => {
	const base = scenario({
		randomEvents: [{
			tick: 1,
			type: 'spawn_item',
			itemId: 'minecraft:apple',
			count: 1,
			positions: [{ x: 2, y: 1, z: 0 }, { x: 3, y: 1, z: 0 }],
		}],
	});
	const first = VirtualWorld.fromScenario(base);
	const second = VirtualWorld.fromScenario(base);
	const different = VirtualWorld.fromScenario({ ...base, seed: 45 });
	first.stepTicks(1);
	second.stepTicks(1);
	different.stepTicks(1);
	assert.deepEqual(first.observation('alice'), second.observation('alice'));
	assert.deepEqual(first.observation('alice').blocks, different.observation('alice').blocks);
	assert.deepEqual(first.observation('alice').player, different.observation('alice').player);
	assert.notDeepEqual(first.observation('alice').entities, different.observation('alice').entities);
});

test('exactly 20 ticks advance one simulated second', () => {
	const world = VirtualWorld.fromScenario(scenario());
	world.stepTicks(20);
	assert.equal(world.tickCount, 20);
	assert.equal(world.timeMs, 1_000);
	assert.equal(world.observation('alice').world.gameTime, 20);
});

test('continuous player state cannot penetrate a solid voxel', () => {
	const world = VirtualWorld.fromScenario(scenario({
		agents: { alice: { position: { x: 0.25, y: 1, z: 0 }, velocity: { x: 1, y: 0, z: 0 } } },
		blocks: [
			{ x: 0, y: 0, z: 0, blockId: 'minecraft:stone' },
			{ x: 1, y: 1, z: 0, blockId: 'minecraft:stone' },
		],
	}));
	world.stepTicks(5);
	assert.ok(world.observation('alice').position.x < 1, 'player must remain on the near side of the wall');
});

test('lava damages, drops pick up only within radius, and checkpoint respawn restores the player', () => {
	const world = VirtualWorld.fromScenario(scenario({
		agents: { alice: { position: { x: 0, y: 1, z: 0 }, health: 5 } },
		blocks: [
			{ x: 0, y: 0, z: 0, blockId: 'minecraft:lava' },
		],
		items: [
			{ id: 'near-drop', itemId: 'minecraft:cobblestone', count: 2, position: { x: 1, y: 1, z: 0 } },
			{ id: 'far-drop', itemId: 'minecraft:stick', count: 1, position: { x: 2, y: 1, z: 0 } },
		],
	}));
	world.recordCheckpoint('alice', { x: 5, y: 1, z: 5 });
	world.stepTicks(1);
	const damaged = world.observation('alice');
	assert.ok(damaged.player.health < 5);
	assert.equal(damaged.inventory.items.some((item) => item.itemId === 'minecraft:cobblestone'), true);
	assert.equal(damaged.inventory.items.some((item) => item.itemId === 'minecraft:stick'), false);
	world.damage('alice', 100);
	world.stepTicks(1);
	assert.equal(world.observation('alice').ready, false);
	assert.equal(world.observation('alice').status, 'PLAYER_DEAD');
	assert.equal(world.respawn('alice'), true);
	const respawned = world.observation('alice');
	assert.equal(respawned.ready, true);
	assert.deepEqual(respawned.position, { x: 5, y: 1, z: 5 });
	assert.equal(respawned.player.health, 20);
});

test('scenario input is immutable and start/stop use an idempotent injected scheduler', () => {
	const input = scenario();
	const original = structuredClone(input);
	let nextHandle = 0;
	let scheduled = 0;
	let cancelled = 0;
	const scheduler = {
		setInterval() { scheduled += 1; return ++nextHandle; },
		clearInterval() { cancelled += 1; },
	};
	const world = VirtualWorld.fromScenario(input, { scheduler });
	world.start();
	world.start();
	world.stop();
	world.stop();
	world.stepTicks(2);
	assert.equal(scheduled, 1);
	assert.equal(cancelled, 1);
	assert.deepEqual(input, original);
});

test('unsupported but protocol-valid actions fail with a typed simulator reason', () => {
	const world = VirtualWorld.fromScenario(scenario());
	const result = world.performAction('alice', {
		type: 'craft_inventory',
		arguments: { recipeId: 'minecraft:stick', count: 1, timeoutMs: 1_000 },
	});
	assert.deepEqual(result, {
		done: true,
		state: 'FAILED',
		reasonCode: 'SIMULATOR_UNSUPPORTED_ACTION',
		changed: false,
	});
});

test('observation-bound effects, attacker, and tags are normalized before publication', () => {
	const world = VirtualWorld.fromScenario(scenario({
		agents: {
			alice: {
				position: { x: 0, y: 1, z: 0 },
				effects: [{ effectId: 'minecraft:speed', amplifier: 1, durationTicks: 20 }],
			},
		},
		blocks: [{ x: 0, y: 0, z: 0, blockId: 'minecraft:stone', tags: ['#minecraft:mineable/pickaxe'] }],
		entities: [{ id: 'mob-1', type: 'minecraft:zombie', name: 'zombie', position: { x: 2, y: 1, z: 0 }, tags: ['#minecraft:hostile'] }],
	}));
	world.damage('alice', 1, { uuid: 'mob-1', type: 'minecraft:zombie', distance: 2 });
	const observation = world.observation('alice');
	assert.deepEqual(observation.player.effects, [{ effectId: 'minecraft:speed', amplifier: 1, duration: 20 }]);
	assert.deepEqual(observation.player.lastAttacker, { uuid: 'mob-1', type: 'minecraft:zombie', distance: 2 });
	assert.deepEqual(observation.blocks[0].tags, ['#minecraft:mineable/pickaxe']);
	assert.deepEqual(observation.entities[0].tags, ['#minecraft:hostile']);
	assert.throws(() => world.damage('alice', 1, { uuid: 'mob-1' }), /lastAttacker|attacker/);
	assert.throws(() => VirtualWorld.fromScenario(scenario({ agents: { alice: { position: { x: 0, y: 1, z: 0 }, effects: [{ effectId: 'minecraft:speed', amplifier: 1, duration: 'bad' }] } } })), /effects\[0\]\.duration/);
	assert.throws(() => VirtualWorld.fromScenario(scenario({ blocks: [{ x: 0, y: 0, z: 0, blockId: 'minecraft:stone', tags: ['not-a-tag'] }] })), /tag identifier/);
});

test('world insertion APIs reject bounded-capacity overflow without relying on observation slicing', () => {
	const blocks = Array.from({ length: MAX_WORLD_BLOCKS }, (_, index) => ({ x: index, y: 0, z: 0, blockId: 'minecraft:stone' }));
	const items = Array.from({ length: MAX_WORLD_ITEMS }, (_, index) => ({ id: `item-${index}`, itemId: 'minecraft:stick', count: 1, position: { x: index, y: 1, z: 0 } }));
	const entities = Array.from({ length: MAX_WORLD_ENTITIES }, (_, index) => ({ id: `entity-${index}`, type: 'minecraft:zombie', position: { x: index, y: 1, z: 0 } }));
	const world = VirtualWorld.fromScenario(scenario({ blocks, items, entities }));
	assert.throws(() => world.addBlock({ x: MAX_WORLD_BLOCKS + 1, y: 0, z: 0, blockId: 'minecraft:stone' }), (error) => error.code === 'WORLD_CAPACITY_EXCEEDED');
	assert.throws(() => world.addItem({ id: 'overflow-item', itemId: 'minecraft:stick', count: 1, position: { x: 0, y: 1, z: 0 } }), (error) => error.code === 'WORLD_CAPACITY_EXCEEDED');
	assert.throws(() => world.addEntity({ id: 'overflow-entity', type: 'minecraft:zombie', position: { x: 0, y: 1, z: 0 } }), (error) => error.code === 'WORLD_CAPACITY_EXCEEDED');

	const inventory = Array.from({ length: MAX_PLAYER_INVENTORY_ITEMS + 1 }, (_, slot) => ({ itemId: 'minecraft:stick', count: 1, slot }));
	assert.throws(() => VirtualWorld.fromScenario(scenario({ agents: { alice: { position: { x: 0, y: 1, z: 0 }, inventory: { items: inventory } } } })), (error) => error.code === 'WORLD_CAPACITY_EXCEEDED');
});

test('a captured scheduler callback cannot advance the world after stop', () => {
	let callback;
	let nextHandle = 0;
	const scheduler = {
		setInterval(next) { callback = next; return ++nextHandle; },
		clearInterval() {},
	};
	const world = VirtualWorld.fromScenario(scenario(), { scheduler });
	world.start();
	world.stop();
	callback();
	assert.equal(world.tickCount, 0);
});
