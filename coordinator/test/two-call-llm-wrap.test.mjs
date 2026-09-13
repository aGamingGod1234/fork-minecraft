import test from 'node:test';
import assert from 'node:assert/strict';
import { ExplorationOccupancy } from '../src/explore-frontier.mjs';
import { TwoCallLlmWrap, classifyBodyFailure, composeTwoCallView, inferFrontierSeek, wrapTwoCallObservation } from '../src/two-call-llm-wrap.mjs';

const death = { cause: 'lava', dimensionId: 'minecraft:overworld', x: 12, y: 64, z: -8 };

test('recovery lists evidenced current and lost items without deciding corpse recovery or recrafting', () => {
	const view = composeTwoCallView({ player: { dead: true }, death, inventory: { items: [] } }, { lastDeath: death, lastLostInventory: [{ itemId: 'minecraft:stone_pickaxe', count: 1 }], alreadyHave: ['minecraft:crafting_table'], doNotRedo: [] });
	assert.equal(view.failureClass, 'lifecycle');
	assert.match(view.recovery.facts, /Last death: lava/);
	assert.match(view.recovery.facts, /Current inventory is empty/);
	assert.match(view.recovery.facts, /Lost on death: minecraft:stone_pickaxe/);
	assert.equal(view.recovery.alreadyHave.includes('minecraft:stone_pickaxe'), false);
	assert.equal(view.options, undefined);
	assert.doesNotMatch(view.recovery.facts, /Go back|recraft/);
});

test('goal prose does not inject game-specific search, recipe or target suggestions', () => {
	const observation = { world: { dimension: 'minecraft:overworld' }, inventory: { items: [] } };
	assert.deepEqual(wrapTwoCallObservation(observation, {}, { goal: 'Beat Minecraft and find a fortress' }), wrapTwoCallObservation(observation, {}, { goal: 'Build a cottage' }));
	assert.equal(inferFrontierSeek('find blaze rods'), 'any');
	assert.equal(inferFrontierSeek('visit a village'), 'any');
});

test('blocked paths are categorized factually without issuing another objective', () => {
	const view = composeTwoCallView({ lastResult: { state: 'FAILED', reasonCode: 'PATH_BLOCKED' } });
	assert.equal(view.failureClass, 'path');
	assert.equal(view.options, undefined);
	assert.equal(view.exploration, undefined);
	assert.equal(classifyBodyFailure('RECIPE_NOT_FOUND', 'FAILED'), 'capability');
	assert.equal(classifyBodyFailure('TARGET_NOT_VISIBLE', 'FAILED'), 'target');
	assert.equal(classifyBodyFailure('NEW_ERROR', 'FAILED'), 'action');
	assert.equal(classifyBodyFailure('BLOCK_BROKEN', 'SUCCEEDED'), null);
});

test('exploration decoration provides factual candidates and strips stale strategy options', () => {
	const occupancy = new ExplorationOccupancy();
	const observation = { position: { x: 0, y: 64, z: 0 }, world: { worldId: 'one', dimension: 'minecraft:overworld' }, landmarks: [{ blockId: 'minecraft:obsidian', x: 8, y: 64, z: 0 }], options: [{ id: 'explore_frontier', moveTo: { x: 8, y: 64, z: 0 } }] };
	const view = composeTwoCallView(observation, null, { occupancy, agentId: 'a', goal: 'Beat Minecraft' });
	assert.equal(view.options, undefined);
	assert.equal(view.exploration.destination, null);
	assert.ok(view.exploration.candidates.some((entry) => entry.blockId === 'minecraft:obsidian'));
	assert.equal(view.exploration.candidates.some((entry) => entry.class === 'portal'), false);
});

test('death recovery facts continue across later observations', () => {
	const wrap = new TwoCallLlmWrap();
	wrap.ingest('a', { player: { dead: false, x: 12, y: 64, z: -8 }, inventory: { items: [{ itemId: 'minecraft:stone_pickaxe', count: 1 }] }, world: { dimension: 'minecraft:overworld' } }, { goalRevision: 1 });
	wrap.ingest('a', { death, player: { dead: true }, inventory: { items: [] }, world: { dimension: 'minecraft:overworld' } }, { goalRevision: 1 });
	const result = wrap.decorate('a', { player: { dead: false, x: 0, y: 64, z: 0 }, inventory: { items: [] }, world: { dimension: 'minecraft:overworld' } });
	assert.equal(result.recovery.lastDeath.x, 12);
	assert.equal(result.recovery.lastLostInventory[0].itemId, 'minecraft:stone_pickaxe');
	assert.equal(result.options, undefined);
});
