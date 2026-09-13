import test from 'node:test';
import assert from 'node:assert/strict';

import {
	bindCompletionContract,
	completionContractFingerprint,
	completionContractCanonicalText,
	parseCompletionContract,
} from '../src/goal-contract.mjs';

const PICKAXE = {
	goalRevision: 7,
	predicates: [{ type: 'inventory_min', itemId: 'minecraft:wooden_pickaxe', count: 1 }],
};

const CROSS_LANGUAGE_CONTRACT = {
	goalRevision: 7,
	predicates: [
		{ type: 'inventory_min', itemId: 'minecraft:wooden_pickaxe', count: 1 },
		{ type: 'position_within', x: 0.5, y: 64, z: -2.5, radius: 2 },
		{ type: 'block_matches', x: 0, y: 64, z: -2, blockId: 'minecraft:crafting_table' },
		{ type: 'entity_state', entityId: '00000000-0000-4000-8000-000000000001', state: 'alive' },
	],
};

test('normalizes the factual wooden-pickaxe contract and binds revision/profile/trace', () => {
	const contract = parseCompletionContract(PICKAXE);
	assert.deepEqual(contract, PICKAXE);
	const bound = bindCompletionContract(contract, {
		goalRevision: 7,
		traceId: 'trace-agent-7',
		profile: { provider: 'codex', model: 'gpt-5.6-sol', reasoningEffort: 'high', serviceTier: 'priority' },
	});
	assert.equal(bound.goalRevision, 7);
	assert.equal(bound.traceId, 'trace-agent-7');
	assert.deepEqual(bound.profile, { provider: 'codex', model: 'gpt-5.6-sol', reasoningEffort: 'high', serviceTier: 'priority' });
	assert.equal(bound.contractHash, completionContractFingerprint(contract));
});

test('uses an explicit language-neutral completion contract fingerprint', () => {
	assert.equal(completionContractCanonicalText(CROSS_LANGUAGE_CONTRACT), [
		'arena-completion-v1',
		'7',
		'inventory_min|bWluZWNyYWZ0Ondvb2Rlbl9waWNrYXhl|1',
		'position_within|3fe0000000000000|4050000000000000|c004000000000000|4000000000000000',
		'block_matches|0|64|-2|bWluZWNyYWZ0OmNyYWZ0aW5nX3RhYmxl',
		'entity_state|MDAwMDAwMDAtMDAwMC00MDAwLTgwMDAtMDAwMDAwMDAwMDAx|YWxpdmU',
	].join('\n'));
	assert.equal(completionContractFingerprint(CROSS_LANGUAGE_CONTRACT), 'sha256:b2ba25ecc8d315903a11a7fe14063839480492056211d8617437125e24db7182');
});

test('rejects stale, empty, unknown, and malformed factual predicates', () => {
	assert.throws(() => parseCompletionContract({ ...PICKAXE, goalRevision: 6 }, { goalRevision: 7 }), /goalRevision/i);
	assert.throws(() => parseCompletionContract({ ...PICKAXE, predicates: [] }), /predicate/i);
	assert.throws(() => parseCompletionContract({ goalRevision: 7, predicates: [{ type: 'inventory_min', itemId: 'wooden_pickaxe', count: 1 }] }), /itemId/i);
	assert.throws(() => parseCompletionContract({ goalRevision: 7, predicates: [{ type: 'unknown', itemId: 'minecraft:stone', count: 1 }] }), /type/i);
	assert.throws(() => parseCompletionContract({ goalRevision: 7, predicates: [{ type: 'position_within', x: 0, y: 0, z: 0, radius: -1 }] }), /radius/i);
	assert.throws(() => parseCompletionContract({ goalRevision: 7, predicates: [{ type: 'block_matches', x: 2_147_483_648, y: 0, z: 0, blockId: 'minecraft:stone' }] }), /coordinate/i);
	assert.throws(() => parseCompletionContract({ goalRevision: 7, predicates: [{ type: 'action_success_count', actionType: 'craft_inventory', count: 1 }] }), /type/i);
});

test('requires an exact binding and rejects profile or trace mutation', () => {
	const contract = parseCompletionContract(PICKAXE);
	assert.throws(() => bindCompletionContract(contract, { goalRevision: 6, traceId: 'trace-agent-7', profile: {} }), /goalRevision/i);
	assert.throws(() => bindCompletionContract(contract, { goalRevision: 7, traceId: '', profile: {} }), /traceId/i);
});
