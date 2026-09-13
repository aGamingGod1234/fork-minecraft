import assert from 'node:assert/strict';
import test from 'node:test';

import { GoalSpecTranslator, buildGoalSpecTranslatorPrompt } from '../src/goal-spec-translator.mjs';

const REQUEST = Object.freeze({
	requestId: '00000000-0000-4000-8000-000000000001',
	originalRequest: 'Get a good pickaxe',
	candidateIds: ['minecraft:iron_pickaxe', 'minecraft:diamond_pickaxe'],
});

test('translator prompt contains only the request, candidates, and allowlisted schema', () => {
	const prompt = buildGoalSpecTranslatorPrompt(REQUEST);
	assert.match(prompt, /Get a good pickaxe/);
	assert.match(prompt, /minecraft:iron_pickaxe/);
	assert.match(prompt, /inventory_contains/);
	assert.match(prompt, /dimensionId/);
	assert.doesNotMatch(prompt, /finish tool|completion tool/i);
});

test('translator receives bounded Minecraft rejection feedback without changing the authoritative request', async () => {
	const rejectedProposal = {
		requestId: REQUEST.requestId,
		summary: 'Stand at the requested position',
		predicate: { type: 'position_within', dimensionId: 'minecraft:the_nether', x: 0, y: 1_000, z: 0, radius: 1, stableTicks: 20 },
	};
	const translator = new GoalSpecTranslator({
		generate: async ({ request, prompt }) => {
			assert.deepEqual(request, REQUEST);
			assert.match(prompt, /Correction attempt 1/);
			assert.match(prompt, /INVALID_GOAL_PREDICATE/);
			assert.match(prompt, /"y":1000/);
			return { requestId: REQUEST.requestId, summary: 'Ask the operator', predicate: { type: 'operator_confirmed' } };
		},
	});
	await translator.translate(REQUEST, {
		correctiveFeedback: { attempt: 1, reasonCode: 'INVALID_GOAL_PREDICATE', rejectedProposal },
	});
	await assert.rejects(() => translator.translate(REQUEST, {
		correctiveFeedback: { attempt: 4, reasonCode: 'INVALID_GOAL_PREDICATE', rejectedProposal },
	}), /between 1 and 3/);
});

test('translator accepts a constrained proposal from the provider adapter', async () => {
	const translator = new GoalSpecTranslator({
		generate: async () => ({
			requestId: '00000000-0000-4000-8000-000000000001',
			summary: 'Obtain an iron or diamond pickaxe',
			predicate: {
				type: 'any_of',
				predicates: [
					{ type: 'inventory_contains', itemId: 'minecraft:iron_pickaxe', count: 1 },
					{ type: 'inventory_contains', itemId: 'minecraft:diamond_pickaxe', count: 1 },
				],
			},
		}),
	});
	const proposal = await translator.translate(REQUEST);
	assert.equal(proposal.requestId, REQUEST.requestId);
	assert.equal(proposal.predicate.type, 'any_of');
});

test('translator normalizes strict structured-output nulls and block property entries', async () => {
	const request = {
		requestId: '00000000-0000-4000-8000-000000000012',
		originalRequest: 'Find the powered lever at 1 2 3',
		candidateIds: ['minecraft:lever'],
	};
	const translator = new GoalSpecTranslator({
		generate: async ({ schema }) => {
			const blockSchema = schema.$defs.predicate.anyOf.find(entry => entry.properties.type.const === 'block_matches');
			assert.equal(blockSchema.required.includes('dimensionId'), true);
			assert.equal(blockSchema.properties.properties.type, 'array');
			return {
				requestId: request.requestId,
				summary: 'Find the powered lever',
				predicate: {
					type: 'block_matches', dimensionId: null, x: 1, y: 2, z: 3,
					blockId: 'minecraft:lever', properties: [{ name: 'powered', value: 'true' }],
				},
			};
		},
	});
	const proposal = await translator.translate(request);
	assert.deepEqual(proposal.predicate, {
		type: 'block_matches', x: 1, y: 2, z: 3,
		blockId: 'minecraft:lever', properties: { powered: 'true' },
	});
});

test('translator rejects duplicate structured block property names', async () => {
	const request = {
		requestId: '00000000-0000-4000-8000-000000000013',
		originalRequest: 'Find the powered lever at 1 2 3',
		candidateIds: ['minecraft:lever'],
	};
	const translator = new GoalSpecTranslator({ generate: async () => ({
		requestId: request.requestId,
		summary: 'Find the lever',
		predicate: {
			type: 'block_matches', dimensionId: null, x: 1, y: 2, z: 3, blockId: 'minecraft:lever',
			properties: [{ name: 'powered', value: 'true' }, { name: 'powered', value: 'false' }],
		},
	}) });
	await assert.rejects(() => translator.translate(request), error => error?.code === 'MALFORMED_GOAL_SPEC_PROPOSAL');
});

test('translator rejects malformed structured block property entries', async () => {
	const request = {
		requestId: '00000000-0000-4000-8000-000000000014',
		originalRequest: 'Find the powered lever at 1 2 3',
		candidateIds: ['minecraft:lever'],
	};
	const malformed = [
		[{}],
		[{ name: 'powered' }],
		[{ name: ' ', value: 'true' }],
		[{ name: 'powered', value: '' }],
	];
	for (const properties of malformed) {
		const translator = new GoalSpecTranslator({ generate: async () => ({
			requestId: request.requestId,
			summary: 'Find the lever',
			predicate: {
				type: 'block_matches', dimensionId: null, x: 1, y: 2, z: 3, blockId: 'minecraft:lever',
				properties,
			},
		}) });
		await assert.rejects(() => translator.translate(request), error => error?.code === 'MALFORMED_GOAL_SPEC_PROPOSAL');
	}
});

test('translator rejects candidate IDs the server did not supply', async () => {
	const translator = new GoalSpecTranslator({
		generate: async () => ({
			requestId: '00000000-0000-4000-8000-000000000001',
			summary: 'Obtain a netherite pickaxe',
			predicate: { type: 'inventory_contains', itemId: 'minecraft:netherite_pickaxe', count: 1 },
		}),
	});
	await assert.rejects(() => translator.translate(REQUEST), error => error?.code === 'UNLISTED_GOAL_IDENTIFIER');
});

test('natural category requests preserve eligible inventory alternatives without weakening the server identifier boundary', async () => {
	const request = { requestId: REQUEST.requestId, originalRequest: 'Collect at least one log from any tree and keep it in your inventory.',
		candidateIds: ['oak', 'spruce', 'birch', 'jungle', 'acacia', 'dark_oak', 'mangrove', 'cherry', 'pale_oak']
			.flatMap((wood) => [`minecraft:${wood}_log`, `minecraft:stripped_${wood}_log`]) };
	const translator = new GoalSpecTranslator({ generate: async ({ prompt }) => {
		assert.match(prompt, /any eligible member of a requested category/);
		assert.match(prompt, /Category ambiguity alone is not a subjective outcome/);
		assert.match(prompt, /minimum SUM of inventory counts across matching variants and stacks/);
		assert.match(prompt, /one factual leaf/);
		return { requestId: request.requestId, summary: 'Keep at least one eligible log in inventory',
			predicate: { type: 'inventory_contains_any', itemIds: request.candidateIds, count: 1 } };
	} });
	const proposal = await translator.translate(request);
	assert.equal(proposal.predicate.type, 'inventory_contains_any');
	assert.equal(proposal.predicate.itemIds.length, 18);
	assert.deepEqual(proposal.predicate.itemIds, request.candidateIds);
	assert.equal(proposal.predicate.count, 1);
	await assert.rejects(() => translator.translate({ ...request, candidateIds: [] }), error => error?.code === 'UNLISTED_GOAL_IDENTIFIER');
	await assert.rejects(() => translator.translate({ ...request, candidateIds: request.candidateIds.slice(0, -1) }), error => error?.code === 'UNLISTED_GOAL_IDENTIFIER');
});

test('a larger registry candidate catalog cannot bypass the sixteen-leaf predicate budget', async () => {
	const candidateIds = Array.from({ length: 17 }, (_unused, index) => `minecraft:category_member_${index}`);
	const request = { requestId: REQUEST.requestId, originalRequest: 'Collect one member of this category.', candidateIds };
	const leaves = candidateIds.map((itemId) => ({ type: 'inventory_contains', itemId, count: 1 }));
	let predicate = { type: 'any_of', predicates: leaves.slice(0, 16) };
	const translator = new GoalSpecTranslator({ generate: async () => ({ requestId: request.requestId, summary: 'Match an eligible item', predicate }) });
	assert.equal((await translator.translate(request)).predicate.predicates.length, 16);
	predicate = { type: 'any_of', predicates: [{ type: 'any_of', predicates: leaves.slice(0, 16) }, leaves[16]] };
	await assert.rejects(() => translator.translate(request), error => error?.code === 'GOAL_PREDICATE_LIMIT_EXCEEDED');
});

test('translator rejects mismatched request identities', async () => {
	const translator = new GoalSpecTranslator({
		generate: async () => ({
			requestId: '00000000-0000-0000-0000-000000000002',
			summary: 'wrong request',
			predicate: { type: 'operator_confirmed' },
		}),
	});
	await assert.rejects(() => translator.translate(REQUEST), error => error?.code === 'GOAL_SPEC_REQUEST_MISMATCH');
});

test('translator preserves compound item and kill requests as bounded all-of leaves', async () => {
	const request = {
		requestId: '00000000-0000-4000-8000-000000000011',
		originalRequest: 'Get an iron pickaxe and kill a zombie',
		candidateIds: ['minecraft:iron_pickaxe', 'minecraft:zombie'],
	};
	const translator = new GoalSpecTranslator({
		generate: async ({ prompt }) => {
			assert.match(prompt, /use all_of/i);
			assert.match(prompt, /at most 16 factual leaves/i);
			assert.match(prompt, /kill counts by repeating the kill leaf/i);
			assert.match(prompt, /every any_of branch must preserve all factual quantities/i);
			assert.match(prompt, /operator_confirmed.*cannot replace/i);
			return {
				requestId: request.requestId,
				summary: 'Get the pickaxe and defeat the zombie',
				predicate: { type: 'all_of', predicates: [
					{ type: 'inventory_contains', itemId: 'minecraft:iron_pickaxe', count: 1 },
					{ type: 'entity_killed_by_agent', entityType: 'minecraft:zombie', afterGoalStart: true },
				] },
			};
		},
	});
	const proposal = await translator.translate(request);
	assert.equal(proposal.predicate.type, 'all_of');
	assert.equal(proposal.predicate.predicates.length, 2);
});
