import assert from 'node:assert/strict';
import test from 'node:test';
import { normalizeInspectionQuery, validateProtocolV2Payload } from '../src/protocol-v2.mjs';

test('installed knowledge and visible surface pages use the shared inspection transport', () => {
	for (const section of ['recipes', 'mechanics', 'landmarks', 'nearby_containers']) {
		assert.deepEqual(normalizeInspectionQuery({ section }), { section, offset: 0, limit: 16 });
	}
	assert.deepEqual(validateProtocolV2Payload('inspection_request', {
		goalRevision: 1, requestId: 'recipe-query', query: { section: 'recipes', recipeId: 'minecraft:oak_planks', offset: 3, limit: 1 },
	}).query, { section: 'recipes', recipeId: 'minecraft:oak_planks', offset: 3, limit: 1 });
});

test('recipe lookup validates the installed identifier and page boundaries before dispatch', () => {
	for (const recipeId of [null, 1, '', 'oak_planks', 'Minecraft:oak_planks', 'minecraft:oak planks', `m:${'x'.repeat(255)}`]) {
		assert.throws(() => normalizeInspectionQuery({ section: 'recipes', recipeId }));
	}
	assert.equal(normalizeInspectionQuery({ section: 'recipes', recipeId: `m:${'x'.repeat(254)}` }).recipeId.length, 256);
	assert.doesNotThrow(() => normalizeInspectionQuery({ section: 'recipes', offset: 4096, limit: 32 }));
	for (const page of [{ offset: 4097 }, { offset: -1 }, { offset: 0.5 }, { offset: null }, { limit: 33 }, { limit: 0 }, { limit: null }]) {
		assert.throws(() => normalizeInspectionQuery({ section: 'recipes', ...page }));
	}
});

test('inspection parameters stay specific to the selected section across runtimes', () => {
	for (const query of [
		{ section: 'mechanics', recipeId: 'minecraft:oak_planks' },
		{ section: 'recipes', slot: 0 }, { section: 'mechanics', x: 0 },
		{ section: 'landmarks', afterSequence: 1 }, { section: 'inventory', z: 0 },
		{ section: 'events', afterSequence: null },
		{ section: 'block', x: 0, y: 2049, z: 0 },
		{ section: 'block', x: 0, y: -2049, z: 0 },
	]) assert.throws(() => normalizeInspectionQuery(query));
	assert.doesNotThrow(() => normalizeInspectionQuery({ section: 'block', x: -30_000_000, y: -2048, z: 30_000_000 }));
});
