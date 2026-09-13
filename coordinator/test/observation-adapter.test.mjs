import assert from 'node:assert/strict';
import test from 'node:test';

import { adaptObservation } from '../src/observation-adapter.mjs';

function wireObservation(overrides = {}) {
	return {
		ready: true,
		position: { x: 0, y: 64, z: 0 },
		view: { yaw: 10, pitch: -2 },
		player: { health: 20, foodLevel: 18, onFire: false, air: 300, fallDistance: 0 },
		inventory: { items: [{ itemId: 'minecraft:oak_log', count: 2, slot: 0, tags: ['#minecraft:logs'] }], tagCounts: { '#minecraft:logs': 2 } },
		entities: [{
			uuid: '00000000-0000-0000-0000-000000000001', type: 'minecraft:item',
			position: { x: 2, y: 64, z: 0 }, itemId: 'minecraft:oak_log', count: 1, distance: 2, tags: ['#minecraft:item']
		}],
		blocks: [{ x: 4, y: 64, z: 0, blockId: 'minecraft:oak_log', tags: ['#minecraft:logs'] }],
		...overrides,
	};
}

test('adapts protocol entities and blocks into bounded factual candidate records', () => {
	const adapted = adaptObservation(wireObservation());
	assert.deepEqual(adapted.player, { x: 0, y: 64, z: 0, yaw: 10, pitch: -2, dead: false, health: 20, hunger: 18, foodLevel: 18, air: 300, fire: false, onFire: false, fallDistance: 0 });
	assert.deepEqual(adapted.items, [{ stableId: '00000000-0000-0000-0000-000000000001', itemId: 'minecraft:oak_log', count: 1, distance: 2, tags: ['#minecraft:item'], x: 2, y: 64, z: 0 }]);
	assert.deepEqual(adapted.entities, [{ stableId: '00000000-0000-0000-0000-000000000001', type: 'minecraft:item', distance: 2, tags: ['#minecraft:item'], x: 2, y: 64, z: 0, itemId: 'minecraft:oak_log', count: 1 }]);
	assert.deepEqual(adapted.blocks, [{ stableId: '4,64,0', blockId: 'minecraft:oak_log', tags: ['#minecraft:logs'], x: 4, y: 64, z: 0 }]);
	assert.equal(Object.hasOwn(adapted, 'landmarks'), false);
	assert.deepEqual(adapted.inventory, { items: [{ itemId: 'minecraft:oak_log', count: 2, slot: 0, tags: ['#minecraft:logs'] }], tagCounts: { '#minecraft:logs': 2 } });
	assert.equal(Object.hasOwn(adapted.items[0], 'reachable'), false);
	assert.deepEqual(adapted.items[0].tags, ['#minecraft:item']);
});

test('preserves every protocol-v2 fact needed by native observe and action tools', () => {
	const adapted = adaptObservation(wireObservation({
		velocity: { x: 0.25, y: -0.1, z: 0 },
		player: {
			health: 18, maxHealth: 20, armor: 7, foodLevel: 16, saturation: 3.5, gameMode: 'survival',
			onGround: false, inWater: true, onFire: false, air: 250, maxAir: 300, suffocating: false,
			fallDistance: 1.5, effects: [{ effectId: 'minecraft:speed', amplifier: 1, duration: 40 }],
		},
		inventory: {
			selectedItem: 'minecraft:iron_pickaxe', tagCounts: { '#minecraft:tools': 1 },
			items: [{ itemId: 'minecraft:iron_pickaxe', count: 1, damage: 12, maxDamage: 250, slot: 2, hotbar: true, tags: ['#minecraft:tools'] }],
		},
		entities: [{ uuid: 'mob-1', type: 'minecraft:zombie', name: 'Zombie', distance: 3, position: { x: 3, y: 64, z: 0 }, isPlayer: false }],
		blocks: [{ x: 1, y: 64, z: 0, blockId: 'minecraft:chest', placeableFaces: ['up', 'north'] }],
		landmarks: [{ x: 18, y: 66, z: 4, blockId: 'minecraft:oak_log', distance: 18.5, bearing: 32, elevation: 4, tags: ['#minecraft:logs'] }],
		nearbyContainers: [{ x: 1, y: 64, z: 0, blockId: 'minecraft:chest', distance: 1, withinInteractionRange: true, capabilities: ['transfer'] }],
		world: { dimension: 'minecraft:overworld', gameTime: 10, dayTime: 10, raining: true, thundering: false },
		currentAction: { active: true, actionId: 'action-1', actionType: 'navigate_to' },
		lastResult: { present: true, actionId: 'action-0', actionType: 'break_block', state: 'SUCCEEDED', reasonCode: 'DONE', message: 'Mined' },
		interaction: {
			mainHandItemId: 'minecraft:iron_pickaxe', offHandItemId: 'minecraft:shield', usingItem: false,
			activeHand: 'none', useRemainingTicks: 0, attackCooldown: 1,
			input: { active: true, forward: 1, strafe: 0, jump: false, sneak: false, sprint: true, attack: false, use: false, yaw: 10, pitch: -2, selectedSlot: 2, hand: 'main_hand' },
			menu: { type: 'none', cursor: { itemId: 'minecraft:air', count: 0 }, slots: [], capabilities: [] },
			rayTarget: { type: 'block', x: 1, y: 64, z: 0, face: 'north', blockId: 'minecraft:chest' },
		},
	}));

	assert.deepEqual(adapted.velocity, { x: 0.25, y: -0.1, z: 0 });
	assert.equal(adapted.player.effects[0].effectId, 'minecraft:speed');
	assert.deepEqual(adapted.inventory.items[0], { itemId: 'minecraft:iron_pickaxe', count: 1, slot: 2, tags: ['#minecraft:tools'], damage: 12, maxDamage: 250, hotbar: true });
	assert.equal(adapted.entities[0].name, 'Zombie');
	assert.deepEqual(adapted.blocks[0].placeableFaces, ['up', 'north']);
	assert.deepEqual(adapted.landmarks[0], {
		stableId: '18,66,4', blockId: 'minecraft:oak_log', distance: 18.5, bearing: 32, elevation: 4,
		tags: ['#minecraft:logs'], x: 18, y: 66, z: 4,
	});
	assert.equal(adapted.nearbyContainers[0].withinInteractionRange, true);
	assert.equal(adapted.world.dimension, 'minecraft:overworld');
	assert.equal(adapted.currentAction.actionId, 'action-1');
	assert.equal(adapted.lastResult.reasonCode, 'DONE');
	assert.equal(adapted.interaction.rayTarget.blockId, 'minecraft:chest');
});

test('preserves the authoritative last attacker for damage watchers', () => {
	const adapted = adaptObservation(wireObservation({
		player: {
			health: 18,
			foodLevel: 18,
			onFire: false,
			air: 300,
			fallDistance: 0,
			lastAttacker: { uuid: 'mob-1', type: 'minecraft:zombie', distance: 2.5 },
		},
	}));
	assert.deepEqual(adapted.player.lastAttacker, {
		uuid: 'mob-1',
		type: 'minecraft:zombie',
		distance: 2.5,
	});
	assert.throws(() => adaptObservation(wireObservation({
		player: {
			health: 18,
			foodLevel: 18,
			onFire: false,
			air: 300,
			fallDistance: 0,
			lastAttacker: { uuid: 'mob-1', type: 'minecraft:zombie', distance: Number.NaN },
		},
	})), /finite number/);
});

test('rejects malformed, duplicate, and over-bound authoritative tags', () => {
	assert.throws(() => adaptObservation(wireObservation({ blocks: [{ x: 1, y: 64, z: 0, blockId: 'minecraft:stone', tags: ['#minecraft:logs', '#minecraft:logs'] }] })), /tags must be unique/);
	assert.throws(() => adaptObservation(wireObservation({ blocks: [{ x: 1, y: 64, z: 0, blockId: 'minecraft:stone', tags: ['minecraft:logs'] }] })), /tag/);
	assert.throws(() => adaptObservation(wireObservation({ inventory: { items: [], tagCounts: Object.fromEntries(Array.from({ length: 129 }, (_, i) => [`#minecraft:t${i}`, 1])) } })), /tagCounts exceeds bound/);
});

test('rejects accessors, inherited data, and proxies before reading observation facts', () => {
	let accessed = false;
	const accessorObservation = wireObservation();
	Object.defineProperty(accessorObservation, 'entities', { enumerable: true, get() { accessed = true; return []; } });
	assert.throws(() => adaptObservation(accessorObservation), /own data/);
	assert.equal(accessed, false);
	assert.throws(() => adaptObservation(Object.create(wireObservation())), /plain data record/);
	assert.throws(() => adaptObservation(new Proxy(wireObservation(), {})), /plain data record/);
});

test('rejects duplicate immutable IDs and duplicate block coordinates', () => {
	const duplicateEntity = wireObservation({ entities: [
		{ uuid: 'same', type: 'minecraft:zombie', position: { x: 1, y: 64, z: 0 } },
		{ uuid: 'same', type: 'minecraft:zombie', position: { x: 2, y: 64, z: 0 } },
	] });
	assert.throws(() => adaptObservation(duplicateEntity), /duplicate entity identity/);
	assert.throws(() => adaptObservation(wireObservation({ blocks: [
		{ x: 1, y: 64, z: 0, blockId: 'minecraft:stone' },
		{ x: 1, y: 64, z: 0, blockId: 'minecraft:dirt' },
	] })), /duplicate block identity/);
});

test('rejects non-finite coordinates and observations over protocol bounds', () => {
	assert.throws(() => adaptObservation(wireObservation({ position: { x: Number.NaN, y: 64, z: 0 } })), /finite number/);
	assert.throws(() => adaptObservation(wireObservation({ entities: Array.from({ length: 65 }, (_, index) => ({ uuid: String(index), type: 'minecraft:zombie', position: { x: index, y: 64, z: 0 } })) })), /entities exceeds bound/);
	assert.throws(() => adaptObservation(wireObservation({ blocks: Array.from({ length: 129 }, (_, index) => ({ x: index, y: 64, z: 0, blockId: 'minecraft:stone' })) })), /blocks exceeds bound/);
	assert.throws(() => adaptObservation(wireObservation({ landmarks: Array.from({ length: 33 }, (_, index) => ({ x: index, y: 64, z: 0, blockId: 'minecraft:oak_log', distance: 1, bearing: 0, elevation: 0 })) })), /landmarks exceeds bound/);
});

test('preserves player-readable motion, geometry, stack variants, and coverage after adaptation', () => {
	const original = wireObservation({
		observedAtEpochMs: 1000,
		coverage: { complete: false, sections: { blocks: { returned: 1, omittedByWire: 7 } } },
		perception: { latestSequence: 1, earliestSequence: 1, events: [{ type: 'sound', soundId: 'minecraft:entity.zombie.ambient', direction: 'front', range: 'near' }], bossBars: [] },
		player: { swimming: true, gliding: false, horizontalCollision: true, pose: 'swimming', vehicle: { uuid: 'boat-1', type: 'minecraft:oak_boat' } },
		entities: [{ uuid: 'mob', type: 'minecraft:zombie', position: { x: 1, y: 64, z: 0 }, velocity: { x: 0.2, y: 0, z: 0 }, pose: 'standing', yaw: 90, equipment: [{ slot: 'mainhand', itemId: 'minecraft:iron_sword', enchanted: true }] }],
		blocks: [{ x: 1, y: 64, z: 0, blockId: 'minecraft:oak_door', state: { open: 'true', facing: 'north' }, bounds: [{ minX: 0, minY: 0, minZ: 0, maxX: 0.1875, maxY: 1, maxZ: 1 }], replaceable: false }],
		inventory: { items: [{ slot: 0, itemId: 'minecraft:iron_sword', count: 1, displayName: 'Named sword', fingerprint: 'abc', tooltip: ['Sharpness III'] }] },
	});
	const adapted = adaptObservation(original);
	assert.equal(adapted.observedAtEpochMs, 1000);
	assert.equal(adapted.coverage.sections.blocks.omittedByWire, 7);
	assert.equal(adapted.perception.events[0].direction, 'front');
	assert.equal(adapted.player.vehicle.uuid, 'boat-1');
	assert.equal(adapted.player.swimming, true);
	assert.equal(adapted.entities[0].velocity.x, 0.2);
	assert.equal(adapted.entities[0].equipment[0].enchanted, true);
	assert.equal(adapted.blocks[0].state.open, 'true');
	assert.equal(adapted.inventory.items[0].fingerprint, 'abc');
	original.blocks[0].state.open = 'false';
	assert.equal(adapted.blocks[0].state.open, 'true', 'adapter owns nested facts independently');
});

test('new extension facts reject nested executable, non-finite, and unbounded values', () => {
	let accessed = false;
	const geometry = {};
	Object.defineProperty(geometry, 'maxX', { enumerable: true, get() { accessed = true; return 1; } });
	assert.throws(() => adaptObservation(wireObservation({ blocks: [{ x: 0, y: 64, z: 0, blockId: 'minecraft:stone', bounds: [geometry] }] })), /own data/);
	assert.equal(accessed, false);
	assert.throws(() => adaptObservation(wireObservation({ coverage: { returned: Infinity } })), /finite number/);
	assert.throws(() => adaptObservation(wireObservation({ coverage: { source: 'x'.repeat(8193) } })), /text bound/);
});
