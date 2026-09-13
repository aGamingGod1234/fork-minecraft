import { createHash } from 'node:crypto';

import { MAX_GOAL_LENGTH, MIN_MOVEMENT_TOLERANCE } from './constants.mjs';

const IDENTIFIER = /^[a-z0-9_.-]+:[a-z0-9_./-]+$/;
const FINGERPRINT = /^[0-9a-f]{64}$/;
const UUID = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i;
const MAX_CANDIDATES = 64;
const MAX_LEAVES = 16;
const MAX_DEPTH = 4;

export class GoalSpecError extends Error {
	constructor(code, message) {
		super(message);
		this.name = 'GoalSpecError';
		this.code = code;
	}
}

const predicateVariants = [
	objectSchema(['type', 'itemId', 'count'], {
		type: { type: 'string', const: 'inventory_contains' }, itemId: { type: 'string' }, count: { type: 'integer', minimum: 1 },
	}),
	objectSchema(['type', 'itemIds', 'count'], {
		type: { type: 'string', const: 'inventory_contains_any' },
		itemIds: { type: 'array', minItems: 1, maxItems: MAX_CANDIDATES, items: { type: 'string', pattern: IDENTIFIER.source, minLength: 3, maxLength: 256 } },
		count: { type: 'integer', minimum: 1, maximum: 2_147_483_647 },
	}),
	objectSchema(['type', 'dimensionId', 'x', 'y', 'z', 'radius', 'stableTicks'], {
		type: { type: 'string', const: 'position_within' }, dimensionId: { type: ['string', 'null'] }, x: { type: 'number' }, y: { type: 'number' }, z: { type: 'number' },
		radius: { type: 'number', minimum: MIN_MOVEMENT_TOLERANCE }, stableTicks: { type: 'integer', minimum: 1 },
	}),
	objectSchema(['type', 'advancementId'], { type: { type: 'string', const: 'advancement_granted' }, advancementId: { type: 'string' } }),
	objectSchema(['type', 'entityType', 'afterGoalStart'], {
		type: { type: 'string', const: 'entity_killed_by_agent' }, entityType: { type: 'string' }, afterGoalStart: { type: 'boolean', const: true },
	}),
	objectSchema(['type', 'dimensionId', 'x', 'y', 'z', 'blockId', 'properties'], {
		type: { type: 'string', const: 'block_matches' }, dimensionId: { type: ['string', 'null'] }, x: { type: 'integer' }, y: { type: 'integer' }, z: { type: 'integer' },
		blockId: { type: 'string' }, properties: {
			type: 'array', maxItems: 16, items: objectSchema(['name', 'value'], {
				name: { type: 'string' }, value: { type: 'string' },
			}),
		},
	}),
	objectSchema(['type', 'ticks'], { type: { type: 'string', const: 'survive_duration' }, ticks: { type: 'integer', minimum: 1 } }),
	objectSchema(['type'], { type: { type: 'string', const: 'operator_confirmed' } }),
	objectSchema(['type', 'predicates'], {
		type: { type: 'string', const: 'all_of' }, predicates: { type: 'array', minItems: 1, maxItems: MAX_LEAVES, items: { $ref: '#/$defs/predicate' } },
	}),
	objectSchema(['type', 'predicates'], {
		type: { type: 'string', const: 'any_of' }, predicates: { type: 'array', minItems: 1, maxItems: MAX_LEAVES, items: { $ref: '#/$defs/predicate' } },
	}),
];

export const GOAL_PREDICATE_SCHEMA = deepFreeze({
	anyOf: predicateVariants,
	$defs: { predicate: { anyOf: predicateVariants } },
});

export const GOAL_SPEC_PROPOSAL_SCHEMA = deepFreeze({
	type: 'object',
	additionalProperties: false,
	required: ['requestId', 'summary', 'predicate'],
	$defs: { predicate: { anyOf: predicateVariants } },
	properties: {
		requestId: { type: 'string', pattern: UUID.source, minLength: 36, maxLength: 36 },
		summary: { type: 'string', minLength: 1, maxLength: 256 },
		predicate: { $ref: '#/$defs/predicate' },
	},
});

export function parseGoalSpecRequest(value) {
	requireObject(value, 'goal spec request');
	exactKeys(value, ['requestId', 'originalRequest', 'candidateIds'], 'goal spec request');
	const candidateIds = requireArray(value.candidateIds, 'candidateIds', MAX_CANDIDATES)
		.map((candidate, index) => identifier(candidate, `candidateIds[${index}]`));
	if (new Set(candidateIds).size !== candidateIds.length) fail('INVALID_GOAL_SPEC_REQUEST', 'candidateIds contains a duplicate identifier');
	return deepFreeze({
		requestId: requestId(value.requestId),
		originalRequest: text(value.originalRequest, 'originalRequest', MAX_GOAL_LENGTH),
		candidateIds,
	});
}

export function parseGoalSpecProposal(value) {
	requireObject(value, 'goal spec proposal');
	exactKeys(value, ['requestId', 'summary', 'predicate'], 'goal spec proposal');
	const budget = { leaves: 0 };
	const predicate = parsePredicate(value.predicate, 0, budget);
	requirePostActivationKills(predicate);
	return deepFreeze({
		requestId: requestId(value.requestId),
		summary: text(value.summary, 'summary', 256),
		predicate,
	});
}

export function parseGoalSpec(value) {
	requireObject(value, 'goal spec');
	exactKeys(value, ['originalRequest', 'predicate', 'createdAtTick', 'fingerprint'], 'goal spec');
	const fingerprint = text(value.fingerprint, 'fingerprint', 64);
	if (!FINGERPRINT.test(fingerprint)) fail('INVALID_GOAL_FINGERPRINT', 'fingerprint must be 64 lowercase hexadecimal characters');
	const spec = {
		originalRequest: canonicalWireText(value.originalRequest, 'originalRequest', MAX_GOAL_LENGTH),
		predicate: parsePredicate(value.predicate, 0, { leaves: 0 }),
		createdAtTick: nonnegativeInteger(value.createdAtTick, 'createdAtTick'),
		fingerprint,
	};
	if (goalSpecFingerprint(spec) !== fingerprint) {
		fail('GOAL_FINGERPRINT_MISMATCH', 'fingerprint does not match the immutable goal fields');
	}
	return deepFreeze(spec);
}

export function goalSpecFingerprint({ originalRequest, predicate, createdAtTick }) {
	return createHash('sha256').update(canonicalGoalFields(
		canonicalWireText(originalRequest, 'originalRequest', MAX_GOAL_LENGTH),
		parsePredicate(predicate, 0, { leaves: 0 }),
		nonnegativeInteger(createdAtTick, 'createdAtTick'),
	)).digest('hex');
}

export function goalPredicateIdentifiers(predicate) {
	const parsed = parsePredicate(predicate, 0, { leaves: 0 });
	const result = [];
	visitPredicate(parsed, value => {
		if (value.type === 'inventory_contains_any') result.push(...value.itemIds);
		for (const field of ['itemId', 'advancementId', 'entityType', 'blockId']) {
			if (value[field] !== undefined) result.push(value[field]);
		}
	});
	return Object.freeze([...new Set(result)]);
}

function parsePredicate(value, depth, budget) {
	if (depth > MAX_DEPTH) fail('GOAL_PREDICATE_DEPTH_EXCEEDED', `Goal predicate depth exceeds ${MAX_DEPTH}`);
	requireObject(value, 'predicate');
	const type = text(value.type, 'predicate.type', 64);
	let result;
	switch (type) {
		case 'inventory_contains':
			exactKeys(value, ['type', 'itemId', 'count'], type);
			result = { type, itemId: identifier(value.itemId, 'itemId'), count: positiveInteger(value.count, 'count') };
			break;
		case 'inventory_contains_any': {
			exactKeys(value, ['type', 'itemIds', 'count'], type);
			const itemIds = Array.from(requireArray(value.itemIds, 'itemIds', MAX_CANDIDATES, 1), (itemId, index) => identifier(itemId, `itemIds[${index}]`));
			if (new Set(itemIds).size !== itemIds.length) fail('INVALID_GOAL_PREDICATE', 'itemIds must contain unique identifiers');
			budget.itemReferences = (budget.itemReferences ?? 0) + itemIds.length;
			if (budget.itemReferences > MAX_CANDIDATES) fail('GOAL_PREDICATE_LIMIT_EXCEEDED', 'Combined inventory category item references exceed 64');
			const count = positiveInteger(value.count, 'count');
			if (count > 2_147_483_647) fail('INVALID_GOAL_PREDICATE', 'count exceeds the server integer limit');
			result = { type, itemIds, count };
			break;
		}
		case 'position_within':
			exactKeys(value, value.dimensionId === undefined
				? ['type', 'x', 'y', 'z', 'radius', 'stableTicks']
				: ['type', 'dimensionId', 'x', 'y', 'z', 'radius', 'stableTicks'], type);
			result = {
				type, ...(value.dimensionId === undefined ? {} : { dimensionId: identifier(value.dimensionId, 'dimensionId') }),
				x: finiteNumber(value.x, 'x'), y: finiteNumber(value.y, 'y'), z: finiteNumber(value.z, 'z'),
				radius: positionRadius(value.radius), stableTicks: positiveInteger(value.stableTicks, 'stableTicks'),
			};
			break;
		case 'advancement_granted':
			exactKeys(value, ['type', 'advancementId'], type);
			result = { type, advancementId: identifier(value.advancementId, 'advancementId') };
			break;
		case 'entity_killed_by_agent':
			exactKeys(value, ['type', 'entityType', 'afterGoalStart'], type);
			if (typeof value.afterGoalStart !== 'boolean') fail('INVALID_GOAL_PREDICATE', 'afterGoalStart must be a boolean');
			result = { type, entityType: identifier(value.entityType, 'entityType'), afterGoalStart: value.afterGoalStart };
			break;
		case 'block_matches': {
			exactKeys(value, value.dimensionId === undefined
				? ['type', 'x', 'y', 'z', 'blockId', 'properties']
				: ['type', 'dimensionId', 'x', 'y', 'z', 'blockId', 'properties'], type);
			requireObject(value.properties, 'properties');
			const entries = Object.entries(value.properties);
			if (entries.length > 16) fail('INVALID_GOAL_PREDICATE', 'properties exceeds 16 entries');
			const properties = Object.fromEntries(entries.sort(([left], [right]) => left.localeCompare(right)).map(([key, entry]) => [
				text(key, 'property name', 64), text(entry, `property '${key}'`, 128),
			]));
			result = {
				type, ...(value.dimensionId === undefined ? {} : { dimensionId: identifier(value.dimensionId, 'dimensionId') }),
				x: integer(value.x, 'x'), y: integer(value.y, 'y'), z: integer(value.z, 'z'),
				blockId: identifier(value.blockId, 'blockId'), properties,
			};
			break;
		}
		case 'survive_duration':
			exactKeys(value, ['type', 'ticks'], type);
			result = { type, ticks: positiveInteger(value.ticks, 'ticks') };
			break;
		case 'operator_confirmed':
			exactKeys(value, ['type'], type);
			result = { type };
			break;
		case 'all_of':
		case 'any_of': {
			exactKeys(value, ['type', 'predicates'], type);
			const children = requireArray(value.predicates, 'predicates', MAX_LEAVES, 1);
			result = { type, predicates: children.map(child => parsePredicate(child, depth + 1, budget)) };
			return result;
		}
		default:
			fail('UNKNOWN_GOAL_PREDICATE', `Unknown goal predicate '${type}'`);
	}
	budget.leaves += 1;
	if (budget.leaves > MAX_LEAVES) fail('GOAL_PREDICATE_LIMIT_EXCEEDED', `Goal predicate leaf count exceeds ${MAX_LEAVES}`);
	return result;
}

function visitPredicate(predicate, visitor) {
	visitor(predicate);
	for (const child of predicate.predicates ?? []) visitPredicate(child, visitor);
}

function requestId(value) {
	const checked = text(value, 'requestId', 36);
	if (!UUID.test(checked)) fail('INVALID_GOAL_SPEC_REQUEST_ID', 'requestId must be a UUID');
	return checked;
}

function canonicalGoalFields(originalRequest, predicate, createdAtTick) {
	return `{"original_request":${JSON.stringify(originalRequest)},"completion":${canonicalPredicate(predicate)},"created_at_tick":${createdAtTick}}`;
}

function canonicalPredicate(predicate) {
	switch (predicate.type) {
		case 'inventory_contains':
			return `{"type":"inventory_contains","item_id":${JSON.stringify(predicate.itemId)},"count":${predicate.count}}`;
		case 'inventory_contains_any':
			return `{"type":"inventory_contains_any","item_ids":${JSON.stringify(predicate.itemIds)},"count":${predicate.count}}`;
		case 'position_within':
			return `{"type":"position_within",${canonicalDimension(predicate)}"x":${javaDouble(predicate.x)},"y":${javaDouble(predicate.y)},"z":${javaDouble(predicate.z)},"radius":${javaDouble(predicate.radius)},"stable_ticks":${predicate.stableTicks}}`;
		case 'advancement_granted':
			return `{"type":"advancement_granted","advancement_id":${JSON.stringify(predicate.advancementId)}}`;
		case 'entity_killed_by_agent':
			return `{"type":"entity_killed_by_agent","entity_type":${JSON.stringify(predicate.entityType)},"after_goal_start":${predicate.afterGoalStart}}`;
		case 'block_matches': {
			const properties = Object.entries(predicate.properties)
				.map(([key, value]) => `${JSON.stringify(key)}:${JSON.stringify(value)}`).join(',');
			return `{"type":"block_matches",${canonicalDimension(predicate)}"x":${predicate.x},"y":${predicate.y},"z":${predicate.z},"block_id":${JSON.stringify(predicate.blockId)},"properties":{${properties}}}`;
		}
		case 'survive_duration':
			return `{"type":"survive_duration","ticks":${predicate.ticks}}`;
		case 'operator_confirmed':
			return '{"type":"operator_confirmed"}';
		case 'all_of':
		case 'any_of':
			return `{"type":${JSON.stringify(predicate.type)},"predicates":[${predicate.predicates.map(canonicalPredicate).join(',')}]}`;
		default:
			throw new GoalSpecError('UNKNOWN_GOAL_PREDICATE', `Unknown goal predicate '${predicate.type}'`);
	}
}

function canonicalDimension(predicate) {
	return predicate.dimensionId === undefined ? '' : `"dimension_id":${JSON.stringify(predicate.dimensionId)},`;
}

function javaDouble(value) {
	if (Object.is(value, -0)) return '-0.0';
	const absolute = Math.abs(value);
	if (absolute === 0 || (absolute >= 1e-3 && absolute < 1e7)) {
		const plain = String(value);
		return Number.isInteger(value) ? `${plain}.0` : plain;
	}
	const [mantissa, exponent] = value.toExponential().split('e');
	return `${mantissa.includes('.') ? mantissa : `${mantissa}.0`}E${Number(exponent)}`;
}

function objectSchema(required, properties) {
	return { type: 'object', additionalProperties: false, required, properties };
}

function requireObject(value, field) {
	if (value === null || typeof value !== 'object' || Array.isArray(value)) fail('INVALID_GOAL_SPEC', `${field} must be an object`);
}

function exactKeys(value, expected, field) {
	const actual = Object.keys(value).sort();
	const wanted = [...expected].sort();
	if (actual.length !== wanted.length || actual.some((key, index) => key !== wanted[index])) {
		fail('UNKNOWN_GOAL_FIELD', `${field} fields differ from the closed schema`);
	}
}

function requireArray(value, field, maximum, minimum = 0) {
	if (!Array.isArray(value) || value.length < minimum || value.length > maximum) {
		fail('INVALID_GOAL_SPEC', `${field} must contain between ${minimum} and ${maximum} values`);
	}
	return value;
}

function text(value, field, maximum) {
	if (typeof value !== 'string') fail('INVALID_GOAL_SPEC', `${field} must be a string`);
	const checked = value.trim().replace(/\s+/gu, ' ');
	if (checked.length === 0 || checked.length > maximum) fail('INVALID_GOAL_SPEC', `${field} has an invalid length`);
	return checked;
}

function canonicalWireText(value, field, maximum) {
	if (typeof value !== 'string') fail('INVALID_GOAL_SPEC', `${field} must be a string`);
	if (value.length === 0 || value.length > maximum || /[\u0000-\u001f\u007f-\u009f]/u.test(value)) {
		fail('INVALID_GOAL_SPEC', `${field} is not canonical bounded server text`);
	}
	return value;
}

function identifier(value, field) {
	const checked = text(value, field, 256);
	if (!IDENTIFIER.test(checked)) fail('INVALID_GOAL_IDENTIFIER', `${field} must be a namespaced identifier`);
	return checked;
}

function integer(value, field) {
	if (!Number.isSafeInteger(value)) fail('INVALID_GOAL_PREDICATE', `${field} must be a safe integer`);
	return value;
}

function nonnegativeInteger(value, field) {
	const checked = integer(value, field);
	if (checked < 0) fail('INVALID_GOAL_SPEC', `${field} must be nonnegative`);
	return checked;
}

function positiveInteger(value, field) {
	const checked = integer(value, field);
	if (checked <= 0) fail('INVALID_GOAL_PREDICATE', `${field} must be positive`);
	return checked;
}

function finiteNumber(value, field) {
	if (typeof value !== 'number' || !Number.isFinite(value)) fail('INVALID_GOAL_PREDICATE', `${field} must be finite`);
	return value;
}

function positionRadius(value) {
	const field = 'radius';
	const checked = finiteNumber(value, field);
	if (checked < MIN_MOVEMENT_TOLERANCE) {
		fail('INVALID_GOAL_PREDICATE', `${field} must be at least ${MIN_MOVEMENT_TOLERANCE}`);
	}
	return checked;
}

function requirePostActivationKills(predicate) {
	visitPredicate(predicate, value => {
		if (value.type === 'entity_killed_by_agent' && !value.afterGoalStart) {
			fail('INVALID_GOAL_PREDICATE', 'afterGoalStart must be true for translated kill goals');
		}
	});
}

function deepFreeze(value) {
	if (value === null || typeof value !== 'object' || Object.isFrozen(value)) return value;
	for (const nested of Object.values(value)) deepFreeze(nested);
	return Object.freeze(value);
}

function fail(code, message) {
	throw new GoalSpecError(code, message);
}
