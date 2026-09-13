import { createHash } from 'node:crypto';

const MAX_PREDICATES = 16;
const MAX_CONTRACT_BYTES = 4_096;
const PREDICATE_TYPES = new Set([
	'inventory_min', 'position_within', 'block_matches', 'entity_state',
]);
const UUID = /^[0-9a-f]{8}-[0-9a-f]{4}-[1-5][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/i;
const NAMESPACED_ID = /^[a-z0-9_.-]+:[a-z0-9_./-]+$/;
const PROFILE_FIELDS = ['provider', 'model', 'reasoningEffort', 'serviceTier'];

export class GoalContractError extends Error {
	constructor(code, message) {
		super(message);
		this.name = 'GoalContractError';
		this.code = code;
	}
}

export function parseCompletionContract(value, { goalRevision = null } = {}) {
	if (!isPlainObject(value)) fail('MALFORMED_CONTRACT', 'completionContract must be an object');
	exactKeys(value, ['goalRevision', 'predicates']);
	const revision = safeRevision(value.goalRevision);
	if (goalRevision !== null && revision !== goalRevision) fail('STALE_CONTRACT', 'completionContract goalRevision does not match the active goal');
	if (!Array.isArray(value.predicates) || value.predicates.length < 1 || value.predicates.length > MAX_PREDICATES) {
		fail('INVALID_CONTRACT', `completionContract predicates must contain 1-${MAX_PREDICATES} entries`);
	}
	const predicates = value.predicates.map((predicate, index) => normalizePredicate(predicate, index));
	const contract = { goalRevision: revision, predicates };
	if (Buffer.byteLength(JSON.stringify(contract), 'utf8') > MAX_CONTRACT_BYTES) fail('CONTRACT_TOO_LARGE', 'completionContract is too large');
	return contract;
}

export function bindCompletionContract(contract, { goalRevision, traceId, profile } = {}) {
	const normalized = parseCompletionContract(contract, { goalRevision });
	const boundTraceId = boundedText(traceId, 'traceId', 128);
	if (!isPlainObject(profile)) fail('INVALID_CONTRACT_BINDING', 'completionContract profile binding must be an object');
	exactKeys(profile, PROFILE_FIELDS);
	const boundProfile = Object.fromEntries(PROFILE_FIELDS.map((field) => [field, boundedText(profile[field], `profile.${field}`, 256)]));
	return {
		...normalized,
		traceId: boundTraceId,
		profile: boundProfile,
		contractHash: completionContractFingerprint(normalized),
	};
}

export function completionContractFingerprint(contract) {
	return `sha256:${createHash('sha256').update(completionContractCanonicalText(contract), 'utf8').digest('hex')}`;
}

export function completionContractCanonicalText(contract) {
	const normalized = parseCompletionContract(contract);
	return [
		'arena-completion-v1',
		String(normalized.goalRevision),
		...normalized.predicates.map(canonicalPredicate),
	].join('\n');
}

function canonicalPredicate(predicate) {
	switch (predicate.type) {
		case 'inventory_min': return `inventory_min|${base64Url(predicate.itemId)}|${predicate.count}`;
		case 'position_within': return `position_within|${doubleHex(predicate.x)}|${doubleHex(predicate.y)}|${doubleHex(predicate.z)}|${doubleHex(predicate.radius)}`;
		case 'block_matches': return `block_matches|${predicate.x}|${predicate.y}|${predicate.z}|${base64Url(predicate.blockId)}`;
		case 'entity_state': return `entity_state|${base64Url(predicate.entityId)}|${base64Url(predicate.state)}`;
		default: throw new GoalContractError('INVALID_PREDICATE', 'Unsupported completion predicate');
	}
}

function base64Url(value) {
	return Buffer.from(value, 'utf8').toString('base64url');
}

function doubleHex(value) {
	const bytes = Buffer.allocUnsafe(8);
	bytes.writeDoubleBE(value === 0 ? 0 : value);
	return bytes.toString('hex');
}

function normalizePredicate(value, index) {
	if (!isPlainObject(value)) fail('INVALID_PREDICATE', `completionContract predicate ${index} must be an object`);
	const type = boundedText(value.type, `predicates[${index}].type`, 64);
	if (!PREDICATE_TYPES.has(type)) fail('INVALID_PREDICATE', `Unsupported completion predicate type '${type}'`);
	switch (type) {
		case 'inventory_min':
			exactKeys(value, ['type', 'itemId', 'count']);
			return { type, itemId: namespacedId(value.itemId, `predicates[${index}].itemId`), count: positiveCount(value.count, index) };
		case 'position_within':
			exactKeys(value, ['type', 'x', 'y', 'z', 'radius']);
			return { type, x: finite(value.x, `predicates[${index}].x`), y: finite(value.y, `predicates[${index}].y`), z: finite(value.z, `predicates[${index}].z`), radius: nonnegative(value.radius, `predicates[${index}].radius`) };
		case 'block_matches':
			exactKeys(value, ['type', 'x', 'y', 'z', 'blockId']);
			return { type, x: coordinate(value.x, `predicates[${index}].x`), y: coordinate(value.y, `predicates[${index}].y`), z: coordinate(value.z, `predicates[${index}].z`), blockId: namespacedId(value.blockId, `predicates[${index}].blockId`) };
		case 'entity_state':
			exactKeys(value, ['type', 'entityId', 'state']);
			if (typeof value.entityId !== 'string' || !UUID.test(value.entityId)) fail('INVALID_PREDICATE', `predicates[${index}].entityId must be a canonical UUID`);
			if (value.state !== 'alive' && value.state !== 'dead') fail('INVALID_PREDICATE', `predicates[${index}].state must be alive or dead`);
			return { type, entityId: value.entityId.toLowerCase(), state: value.state };
		default:
			throw new GoalContractError('INVALID_PREDICATE', 'Unsupported completion predicate');
	}
}

function exactKeys(value, required) {
	const allowed = new Set(required);
	for (const key of Object.keys(value)) if (!allowed.has(key)) fail('UNKNOWN_CONTRACT_FIELD', `Unknown completion contract field '${key}'`);
	for (const key of required) if (!Object.hasOwn(value, key)) fail('MISSING_CONTRACT_FIELD', `Missing completion contract field '${key}'`);
}

function safeRevision(value) {
	if (!Number.isSafeInteger(value) || value < 0) fail('INVALID_CONTRACT', 'completionContract goalRevision must be a nonnegative safe integer');
	return value;
}

function positiveCount(value, index) {
	if (!Number.isSafeInteger(value) || value < 1 || value > 2_147_483_647) fail('INVALID_PREDICATE', `predicates[${index}].count must be a positive safe integer`);
	return value;
}

function coordinate(value, label) {
	if (!Number.isSafeInteger(value) || value < -2_147_483_648 || value > 2_147_483_647) fail('INVALID_PREDICATE', `${label} must be a 32-bit integer coordinate`);
	return value;
}

function finite(value, label) {
	if (typeof value !== 'number' || !Number.isFinite(value)) fail('INVALID_PREDICATE', `${label} must be finite`);
	return value;
}

function nonnegative(value, label) {
	const number = finite(value, label);
	if (number < 0 || number > 1_000_000) fail('INVALID_PREDICATE', `${label} must be nonnegative and bounded`);
	return number;
}

function namespacedId(value, label) {
	if (typeof value !== 'string' || value.length > 256 || !NAMESPACED_ID.test(value)) fail('INVALID_PREDICATE', `${label} must be a canonical namespaced ID`);
	return value;
}

function boundedText(value, label, max) {
	if (typeof value !== 'string' || value.trim().length === 0 || value.length > max || [...value].some((character) => {
		const codePoint = character.codePointAt(0);
		return codePoint < 0x20 || codePoint === 0x7f;
	})) fail('INVALID_CONTRACT_BINDING', `${label} must be bounded, nonblank text`);
	return value;
}

function isPlainObject(value) {
	return value !== null && typeof value === 'object' && !Array.isArray(value) && Object.getPrototypeOf(value) === Object.prototype;
}

function fail(code, message) { throw new GoalContractError(code, message); }
