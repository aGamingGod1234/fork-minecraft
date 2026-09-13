import assert from 'node:assert/strict';
import test from 'node:test';

import {
	GOAL_PREDICATE_SCHEMA,
	GOAL_SPEC_PROPOSAL_SCHEMA,
	parseGoalSpec,
	parseGoalSpecProposal,
	parseGoalSpecRequest,
	goalSpecFingerprint,
	goalPredicateIdentifiers,
} from '../src/goal-spec.mjs';

const REQUEST_ID = '00000000-0000-4000-8000-000000000001';

test('compact inventory categories preserve a bounded unique identifier set and count as one factual leaf', () => {
	const itemIds = Array.from({ length: 64 }, (_unused, index) => `minecraft:category_member_${index}`);
	const predicate = { type: 'inventory_contains_any', itemIds, count: 3 };
	const proposal = { requestId: REQUEST_ID, summary: 'Collect three eligible items in total', predicate };
	const parsed = parseGoalSpecProposal(proposal);
	assert.deepEqual(parsed, proposal);
	assert.ok(Object.isFrozen(parsed.predicate.itemIds));
	assert.deepEqual(goalPredicateIdentifiers(predicate), itemIds);
	const sixteenLeaves = { type: 'all_of', predicates: [predicate, ...Array.from({ length: 15 }, (_unused, index) => ({ type: 'inventory_contains', itemId: `minecraft:exact_item_${index}`, count: 1 }))] };
	assert.equal(parseGoalSpecProposal({ ...proposal, predicate: sixteenLeaves }).predicate.predicates.length, 16);
	assert.throws(() => parseGoalSpecProposal({ ...proposal, predicate: { type: 'all_of', predicates: [sixteenLeaves, { type: 'operator_confirmed' }] } }),
		error => error?.code === 'GOAL_PREDICATE_LIMIT_EXCEEDED');
	for (const invalid of [[], new Array(1), [...itemIds, 'minecraft:extra'], ['minecraft:oak_log', 'minecraft:oak_log'], ['minecraft:oak_log', ' minecraft:oak_log '], ['missing_namespace']]) {
		assert.throws(() => parseGoalSpecProposal({ ...proposal, predicate: { ...predicate, itemIds: invalid } }));
	}
	for (const count of [0, -1, 1.5, NaN, 2_147_483_648]) assert.throws(() => parseGoalSpecProposal({ ...proposal, predicate: { ...predicate, count } }));
	assert.equal(parseGoalSpecProposal({ ...proposal, predicate: { ...predicate, count: 2_147_483_647 } }).predicate.count, 2_147_483_647);
	assert.throws(() => parseGoalSpecProposal({ ...proposal, predicate: { ...predicate, sourceTag: '#minecraft:logs' } }), error => error?.code === 'UNKNOWN_GOAL_FIELD');
	for (const schema of [GOAL_PREDICATE_SCHEMA, GOAL_SPEC_PROPOSAL_SCHEMA.$defs.predicate]) {
		const variant = schema.anyOf.find(entry => entry.properties.type.const === 'inventory_contains_any');
		assert.equal(variant.properties.itemIds.minItems, 1);
		assert.equal(variant.properties.itemIds.maxItems, 64);
		assert.equal(variant.properties.count.maximum, 2_147_483_647);
	}
});

test('compact inventory groups share an aggregate sixty-four reference budget across compound branches', () => {
	const itemIds = Array.from({ length: 33 }, (_unused, index) => `minecraft:category_member_${index}`);
	const group = { type: 'inventory_contains_any', itemIds: itemIds.slice(0, 32), count: 1 };
	const proposal = { requestId: REQUEST_ID, summary: 'Collect accepted variants', predicate: { type: 'all_of', predicates: [group, group] } };
	assert.equal(parseGoalSpecProposal(proposal).predicate.predicates.length, 2);
	for (const type of ['all_of', 'any_of']) {
		assert.throws(() => parseGoalSpecProposal({ ...proposal, predicate: { type, predicates: [group, { ...group, itemIds }] } }),
			error => error?.code === 'GOAL_PREDICATE_LIMIT_EXCEEDED');
	}
});

test('compact inventory goal persistence matches the Java item_ids canonical fingerprint and retains array order', () => {
	const fields = { originalRequest: 'Collect any logs', predicate: { type: 'inventory_contains_any', itemIds: ['minecraft:oak_log', 'minecraft:birch_log'], count: 3 }, createdAtTick: 1200 };
	const fingerprint = 'f83b10c17263df853e9ff267009f9944df8f660818b7037396c607eea8581c90';
	assert.equal(goalSpecFingerprint(fields), fingerprint);
	assert.deepEqual(parseGoalSpec({ ...fields, fingerprint }), { ...fields, fingerprint });
	assert.notEqual(goalSpecFingerprint({ ...fields, predicate: { ...fields.predicate, itemIds: [...fields.predicate.itemIds].reverse() } }), fingerprint);
});

test('translated movement and kill predicates enforce executable post-activation invariants', () => {
	const position = {
		requestId: REQUEST_ID,
		summary: 'Reach the position',
		predicate: { type: 'position_within', x: 12, y: 64, z: -8, radius: 0.01, stableTicks: 1 },
	};
	assert.deepEqual(parseGoalSpecProposal(position), position);
	for (const radius of [0, 0.009, -1]) {
		assert.throws(
			() => parseGoalSpecProposal({ ...position, predicate: { ...position.predicate, radius } }),
			error => error?.code === 'INVALID_GOAL_PREDICATE' && /radius/i.test(error.message),
		);
	}

	const kill = {
		requestId: REQUEST_ID,
		summary: 'Kill the zombie',
		predicate: { type: 'entity_killed_by_agent', entityType: 'minecraft:zombie', afterGoalStart: true },
	};
	assert.deepEqual(parseGoalSpecProposal(kill), kill);
	assert.throws(
		() => parseGoalSpecProposal({ ...kill, predicate: { ...kill.predicate, afterGoalStart: false } }),
		error => error?.code === 'INVALID_GOAL_PREDICATE' && /afterGoalStart/i.test(error.message),
	);
	const killSpecFields = { originalRequest: 'Kill the zombie', predicate: kill.predicate, createdAtTick: 1_200 };
	const killSpec = { ...killSpecFields, fingerprint: goalSpecFingerprint(killSpecFields) };
	assert.deepEqual(parseGoalSpec(killSpec), killSpec);

	for (const schema of [GOAL_PREDICATE_SCHEMA, GOAL_SPEC_PROPOSAL_SCHEMA.$defs.predicate]) {
		const positionSchema = schema.anyOf.find(entry => entry.properties.type.const === 'position_within');
		const killSchema = schema.anyOf.find(entry => entry.properties.type.const === 'entity_killed_by_agent');
		assert.equal(positionSchema.properties.radius.minimum, 0.01);
		assert.deepEqual(positionSchema.properties.dimensionId.type, ['string', 'null']);
		assert.equal(positionSchema.required.includes('dimensionId'), true);
		assert.equal(killSchema.properties.afterGoalStart.const, true);
	}
	assert.equal(JSON.stringify(GOAL_SPEC_PROPOSAL_SCHEMA).includes('"oneOf"'), false);
	assert.equal(JSON.stringify(GOAL_SPEC_PROPOSAL_SCHEMA).includes('"additionalProperties":{"type":"string"}'), false);
	for (const variant of GOAL_SPEC_PROPOSAL_SCHEMA.$defs.predicate.anyOf) {
		assert.equal(variant.properties.type.type, 'string');
	}
});

test('goal spec proposal accepts only the closed authoritative predicate schema', () => {
	const proposal = {
		requestId: REQUEST_ID,
		summary: 'Obtain one iron pickaxe',
		predicate: { type: 'inventory_contains', itemId: 'minecraft:iron_pickaxe', count: 1 },
	};
	assert.deepEqual(parseGoalSpecProposal(proposal), proposal);
	assert.throws(() => parseGoalSpecProposal({
		requestId: REQUEST_ID,
		summary: 'weak',
		predicate: { type: 'action_success_count', actionType: 'craft_inventory', count: 1 },
	}), error => error?.code === 'UNKNOWN_GOAL_PREDICATE');
	assert.throws(() => parseGoalSpecProposal({ ...proposal, predicate: { ...proposal.predicate, count: 0 } }), /count/i);
	assert.throws(() => parseGoalSpecProposal({ ...proposal, extra: true }), /field/i);
});

test('dimension-bound position and block predicates survive parsing and Java-compatible fingerprinting', () => {
	const proposal = {
		requestId: REQUEST_ID,
		summary: 'Build a crafting table in the Nether',
		predicate: {
			type: 'all_of',
			predicates: [
				{ type: 'position_within', dimensionId: 'minecraft:the_nether', x: 12, y: 64, z: -8, radius: 1, stableTicks: 20 },
				{ type: 'block_matches', dimensionId: 'minecraft:the_nether', x: 12, y: 64, z: -8, blockId: 'minecraft:crafting_table', properties: {} },
			],
		},
	};
	assert.deepEqual(parseGoalSpecProposal(proposal), proposal);
	const fields = { originalRequest: 'Build in the Nether', predicate: proposal.predicate, createdAtTick: 1_200 };
	const spec = { ...fields, fingerprint: goalSpecFingerprint(fields) };
	assert.equal(spec.fingerprint, '48fcb7e75395224706e214bbf3f6e86ffbd7ff318ade762218664f80bb51b7d3');
	assert.deepEqual(parseGoalSpec(spec), spec);
});

test('goal spec request is exact, bounded, and deduplicates no candidate IDs', () => {
	const request = {
		requestId: REQUEST_ID,
		originalRequest: 'Get a good pickaxe',
		candidateIds: ['minecraft:iron_pickaxe', 'minecraft:diamond_pickaxe'],
	};
	assert.deepEqual(parseGoalSpecRequest(request), request);
	assert.throws(() => parseGoalSpecRequest({ ...request, candidateIds: [...request.candidateIds, request.candidateIds[0]] }), /duplicate/i);
	assert.throws(() => parseGoalSpecRequest({ ...request, candidateIds: Array.from({ length: 65 }, (_, i) => `test:item_${i}`) }), /candidateIds/i);
	assert.equal(parseGoalSpecRequest({ ...request, originalRequest: 'x'.repeat(4_096) }).originalRequest.length, 4_096);
	assert.throws(() => parseGoalSpecRequest({ ...request, originalRequest: 'x'.repeat(4_097) }), /originalRequest/i);
	assert.equal(parseGoalSpecRequest({ ...request, originalRequest: '\u{1f642}'.repeat(2_048) }).originalRequest.length, 4_096);
	assert.throws(() => parseGoalSpecRequest({ ...request, originalRequest: '\u{1f642}'.repeat(2_049) }), /originalRequest/i);
});

test('goal persistence and fingerprints use Java-compatible UTF-16 length boundaries', () => {
	const originalRequest = '\u{1f642}'.repeat(2_048);
	const fields = { originalRequest, predicate: { type: 'operator_confirmed' }, createdAtTick: 7 };
	const fingerprint = goalSpecFingerprint(fields);
	assert.equal(fingerprint, '59f44cbb5e6425ac14c77166d18bf2f541276e8baa59c281ee03eb3b5df48637');
	assert.deepEqual(parseGoalSpec({ ...fields, fingerprint }), { ...fields, fingerprint });
	assert.throws(
		() => goalSpecFingerprint({ ...fields, originalRequest: `${originalRequest}\u{1f642}` }),
		/originalRequest/i,
	);
});

test('wire goal spec retains immutable predicate, creation tick, and fingerprint', () => {
	const spec = {
		originalRequest: 'Get an iron pickaxe',
		predicate: { type: 'inventory_contains', itemId: 'minecraft:iron_pickaxe', count: 1 },
		createdAtTick: 1_200,
		fingerprint: '',
	};
	spec.fingerprint = goalSpecFingerprint(spec);
	assert.deepEqual(parseGoalSpec(spec), spec);
	assert.throws(() => parseGoalSpec({ ...spec, fingerprint: 'a'.repeat(63) }), /fingerprint/i);
	assert.throws(() => parseGoalSpec({ ...spec, originalRequest: 'Get a stone pickaxe' }), error => error?.code === 'GOAL_FINGERPRINT_MISMATCH');
	assert.throws(() => parseGoalSpec({ ...spec, predicate: { type: 'operator_confirmed', extra: true } }), /field/i);
});

test('goal fingerprints match Java canonical double encoding', () => {
	assert.equal(goalSpecFingerprint({
		originalRequest: 'Go to 12',
		predicate: { type: 'position_within', x: 12, y: 64.5, z: -0, radius: 1, stableTicks: 20 },
		createdAtTick: 1_200,
	}), '8576d9a940ee8561be9dfe2c6230ab0e620a227246c214824b230f5561e6ee88');
});

test('server-canonical Unicode whitespace is preserved while checking fingerprints', () => {
	const fields = {
		originalRequest: 'Get\u00a0iron',
		predicate: { type: 'inventory_contains', itemId: 'minecraft:iron_pickaxe', count: 1 },
		createdAtTick: 1_200,
	};
	const spec = { ...fields, fingerprint: goalSpecFingerprint(fields) };
	assert.equal(parseGoalSpec(spec).originalRequest, fields.originalRequest);
	assert.notEqual(spec.fingerprint, goalSpecFingerprint({ ...fields, originalRequest: 'Get iron' }));
});

test('compound goal predicates are bounded by depth and leaf count', () => {
	const leaf = { type: 'operator_confirmed' };
	assert.throws(() => parseGoalSpecProposal({
		requestId: REQUEST_ID, summary: 'too many',
		predicate: { type: 'all_of', predicates: Array.from({ length: 17 }, () => leaf) },
	}), /leaf|predicate/i);
	let nested = leaf;
	for (let i = 0; i < 6; i += 1) nested = { type: 'all_of', predicates: [nested] };
	assert.throws(() => parseGoalSpecProposal({ requestId: REQUEST_ID, summary: 'too deep', predicate: nested }), /depth/i);
});
