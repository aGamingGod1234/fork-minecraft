import assert from 'node:assert/strict';
import test from 'node:test';

import { buildNativeEventInput } from '../src/dynamic-main.mjs';

const record = {
	currentGoal: 'Beat Minecraft',
	currentGoalSpec: { originalRequest: 'Beat Minecraft' },
	goalRevision: 4,
};

test('native death input keeps last live inventory, recovery, and empty current items', () => {
	const input = buildNativeEventInput(record, {
		event: 'player_death',
		trigger: 'player_death',
		observation: {
			death: { cause: 'lava', x: 12, y: 64, z: -8, dimensionId: 'minecraft:overworld' },
			player: { dead: true, health: 0, x: 12, y: 64, z: -8 },
			inventory: { items: [] },
			continuity: { sameGoal: true, phase: 'dead' },
			lastLiveInventory: { items: [{ itemId: 'minecraft:stone_pickaxe', count: 1 }] },
			recovery: {
				lastDeath: { cause: 'lava', x: 12, y: 64, z: -8, dimensionId: 'minecraft:overworld' },
				lastLostInventory: [{ itemId: 'minecraft:stone_pickaxe', count: 1 }],
				alreadyHave: ['minecraft:crafting_table'],
				facts: 'Current inventory is empty. Lost on death: minecraft:stone_pickaxe.',
			},
			failureClass: 'recover',
			options: [{ id: 'recover_corpse', feasible: true, moveTo: { x: 12, y: 64, z: -8 } }],
			world: { dimension: 'minecraft:overworld' },
		},
	});
	assert.match(input, /smallest useful tool now/i);
	assert.match(input, /"phase":"dead"/);
	assert.match(input, /lastLostInventory/);
	assert.match(input, /minecraft:stone_pickaxe/);
	assert.match(input, /Current inventory is empty/);
	assert.match(input, /recover_corpse/);
});

test('oversized native event input keeps death recovery instead of dropping it', () => {
	const input = buildNativeEventInput(record, {
		event: 'player_death',
		trigger: 'player_death',
		observation: {
			death: { cause: 'lava', x: 1, y: 64, z: 1, dimensionId: 'minecraft:overworld' },
			player: { dead: true, health: 0 },
			inventory: { items: Array.from({ length: 64 }, (_, index) => ({ itemId: `minecraft:filler_${index}`, count: 64 })) },
			recovery: {
				lastDeath: { cause: 'lava', x: 1, y: 64, z: 1, dimensionId: 'minecraft:overworld' },
				lastLostInventory: [{ itemId: 'minecraft:iron_pickaxe', count: 1 }],
				alreadyHave: ['minecraft:crafting_table'],
				facts: 'Lost on death: minecraft:iron_pickaxe.',
			},
			failureClass: 'recover',
			world: { dimension: 'minecraft:overworld', extra: 'n'.repeat(20_000) },
			blocks: Array.from({ length: 200 }, (_, index) => ({ blockId: 'minecraft:stone', x: index, y: 64, z: 0 })),
			continuity: { sameGoal: true, phase: 'dead' },
		},
	});
	assert.match(input, /lastDeath/);
	assert.match(input, /iron_pickaxe/);
	assert.match(input, /"phase":"dead"/);
	assert.ok(Buffer.byteLength(input, 'utf8') <= 20_000);
});
