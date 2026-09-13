import assert from 'node:assert/strict';
import test from 'node:test';
import { ExplorationOccupancy, cellKey, extractPosition } from '../src/explore-frontier.mjs';

function observation({ x = 0, y = 64, z = 0, blocks = [], landmarks = [], dimension = 'minecraft:overworld', gameTime = 20, worldId = 'world-a' } = {}) {
	return { position: { x, y, z }, world: { dimension, worldId, gameTime }, blocks, landmarks };
}

test('candidate queries return facts without selecting a destination or mutating remembered state', () => {
	const occupancy = new ExplorationOccupancy();
	const view = observation({ blocks: [{ blockId: 'minecraft:obsidian', x: 12, y: 64, z: 0 }] });
	const before = occupancy.snapshot('a');
	const first = occupancy.candidates('a', view);
	assert.equal(first.kind, 'candidates');
	assert.equal(first.destination, null);
	assert.ok(first.candidates.some((entry) => entry.kind === 'observed_block'));
	assert.ok(first.candidates.some((entry) => entry.kind === 'unknown_cell'));
	assert.ok(first.candidates.every((entry) => entry.reachability === 'unknown'));
	assert.deepEqual(occupancy.candidates('a', view), first);
	assert.deepEqual(occupancy.snapshot('a'), before);
});

test('goals and historical seek keywords never favor portal or fortress material', () => {
	const occupancy = new ExplorationOccupancy();
	const view = observation({ blocks: [{ blockId: 'minecraft:obsidian', x: 12, y: 64, z: 0 }, { blockId: 'minecraft:stone', x: 1, y: 64, z: 0 }] });
	const result = occupancy.select('a', view, { seek: 'nether' });
	assert.deepEqual(result, occupancy.select('a', view, { seek: 'village' }));
	const blocks = result.candidates.filter((entry) => entry.kind === 'observed_block');
	assert.equal(blocks[0].blockId, 'minecraft:stone');
	assert.equal(blocks[1].class, undefined);
	assert.equal(blocks[1].weight, undefined);
});

test('distance includes altitude and far vertical landmarks are outside the radius', () => {
	const occupancy = new ExplorationOccupancy();
	const view = observation({ landmarks: [{ blockId: 'minecraft:chest', x: 0, y: 84, z: 0 }, { blockId: 'minecraft:gold_block', x: 0, y: 120, z: 0 }] });
	const result = occupancy.candidates('a', view);
	const chest = result.candidates.find((entry) => entry.blockId === 'minecraft:chest');
	assert.ok(chest.distance > 20);
	assert.equal(chest.position.y, 84.5);
	assert.equal(result.candidates.some((entry) => entry.blockId === 'minecraft:gold_block'), false);
});

test('candidate pages are bounded and literal block filters retain no unknown guesses', () => {
	const occupancy = new ExplorationOccupancy();
	const result = occupancy.candidates('a', observation(), { limit: 1 });
	assert.equal(result.candidates.length, 1);
	assert.equal(result.truncated, true);
	const view = observation({ landmarks: [{ blockId: 'minecraft:chest', x: 8, y: 64, z: 0 }] });
	const filtered = occupancy.candidates('a', view, { blockId: 'minecraft:chest' });
	assert.equal(filtered.candidates.length, 1);
	assert.equal(filtered.candidates[0].blockId, 'minecraft:chest');
});

test('missing player position returns no destination, including compatibility select', () => {
	const result = new ExplorationOccupancy().select('a', { player: { health: 20 } });
	assert.equal(result.kind, 'no_observation');
	assert.equal(result.destination, null);
	assert.deepEqual(result.candidates, []);
});

test('seen landmarks do not count as visited and height layers remain independent', () => {
	const occupancy = new ExplorationOccupancy();
	occupancy.ingest('a', observation({ landmarks: [{ blockId: 'minecraft:stone', x: 0, y: 80, z: 0 }] }));
	const result = occupancy.snapshot('a');
	assert.equal(result.knownCells, 2);
	assert.equal(result.visitedCells, 1);
	assert.equal(result.seenCells, 1);
	assert.notEqual(cellKey(0, 64, 0), cellKey(0, 80, 0));
});

test('position adapters accept current wire and nested player shapes', () => {
	assert.deepEqual(extractPosition({ position: { x: 1, y: 2, z: 3 } }), { x: 1, y: 2, z: 3 });
	assert.deepEqual(extractPosition({ player: { position: { x: 1, y: 2, z: 3 } } }), { x: 1, y: 2, z: 3 });
	assert.equal(extractPosition({ player: { health: 20 } }), null);
});
