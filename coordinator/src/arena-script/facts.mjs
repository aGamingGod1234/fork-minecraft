import { types as nodeTypes } from 'node:util';

import { ALL_FACT_DOMAINS, FACT_DOMAIN } from './fact-domains.mjs';
import { MAX_LINE_BYTES } from '../constants.mjs';

const FORBIDDEN_KEYS = new Set(['__proto__', 'constructor', 'prototype']);
const OBSERVED_SETS = new WeakSet();
const CANDIDATE_ORIGINS = new WeakMap();
const INTERPRETER_FACTS = new WeakSet();
const FACT_STATS = new WeakMap();
const TRUST_LIMITS = Object.freeze({ depth: 256, nodes: 4_096, keys: 4_096, stringBytes: 16_384, factBytes: MAX_LINE_BYTES * 2, arrayLength: 256 });
const EMPTY_FACT_STATS = Object.freeze({ nodes: 0, keys: 0, bytes: 0, depth: -1 });

/** Builds an immutable, observation-only fact view. */
export function createFactView(observation) {
	const data = createInterpreterFacts(observation);
	const candidates = new WeakSet([data.world.items, data.world.entities, data.world.blocks]);
	const query = (list, criteria = {}) => {
		const filtered = filterObserved(list, criteria);
		candidates.add(filtered);
		return filtered;
	};
	return freezeRecord({
		player: data.player,
		world: freezeRecord({
			state: () => data.world.state,
			menu: () => data.world.menu,
			items: (criteria = {}) => query(data.world.items, criteria),
			entities: (criteria = {}) => query(data.world.entities, criteria),
			blocks: (criteria = {}) => query(data.world.blocks, criteria),
			nearest: (list, origin = data.player) => {
				if (!candidates.has(list)) throw new TypeError('nearest requires an observed candidate set');
				return nearest(list, origin);
			},
		}),
		inventory: freezeRecord({
			count: (itemId) => countInventory(data.inventory.items, itemId), countTag: (tag) => data.inventory.tagCounts[tag] ?? 0,
			slots: (criteria = {}) => filterObserved(data.inventory.items, criteria), state: () => data.inventory.state,
		}),
	});
}

/** Serializable frozen facts used by the interpreter. */
export function createInterpreterFacts(observation = {}, previousFacts = null) {
	const previous = INTERPRETER_FACTS.has(previousFacts) ? previousFacts : null;
	const source = ownDataRecord(observation, 'observation');
	const playerSource = ownDataRecord(source.player ?? Object.create(null), 'observation.player');
	const frozenPlayer = reuseEqual(previous?.player, copyFactData(playerSource, 'observation.player'));
	const inventorySource = ownDataRecord(source.inventory ?? Object.create(null), 'observation.inventory');
	const tagCounts = Object.create(null);
	for (const [tag, count] of Object.entries(ownDataRecord(inventorySource.tagCounts ?? Object.create(null), 'observation.inventory.tagCounts'))) {
		if (validKey(tag) && nonNegativeInteger(count)) tagCounts[tag] = count;
	}
	const worldItems = reuseEqual(previous?.world.items, copyCandidates(source.items, 'item'));
	const worldEntities = reuseEqual(previous?.world.entities, copyCandidates(source.entities, 'entity'));
	const worldBlocks = reuseEqual(previous?.world.blocks, copyCandidates(source.blocks, 'block'));
	const inventoryItems = reuseEqual(previous?.inventory.items, copyInventory(inventorySource.items));
	const inventoryTagCounts = reuseEqual(previous?.inventory.tagCounts, freezeRecord(tagCounts));
	const worldSource = ownDataRecord(source.world ?? Object.create(null), 'observation.world');
	for (const key of ['coverage', 'observedAtEpochMs', 'worldTick', 'interaction', 'recipes', 'events', 'perception', 'dimension', 'capabilities']) {
		if (Object.hasOwn(source, key)) worldSource[key] = source[key];
	}
	const worldState = reuseEqual(previous?.world.state, copyFactData(worldSource, 'observation.world'));
	const interaction = ownDataRecord(source.interaction ?? Object.create(null), 'observation.interaction');
	const menu = reuseEqual(previous?.world.menu, copyFactData(interaction.menu ?? source.menu ?? null, 'observation.menu'));
	const inventoryMetadata = Object.fromEntries(Object.entries(inventorySource).filter(([key]) => !['items', 'tagCounts'].includes(key)));
	const inventoryState = reuseEqual(previous?.inventory.state, copyFactData(inventoryMetadata, 'observation.inventory'));
	if (previous !== null
		&& frozenPlayer === previous.player
		&& worldItems === previous.world.items
		&& worldEntities === previous.world.entities
		&& worldBlocks === previous.world.blocks
		&& inventoryItems === previous.inventory.items
		&& inventoryTagCounts === previous.inventory.tagCounts
		&& worldState === previous.world.state && menu === previous.world.menu
		&& inventoryState === previous.inventory.state) return previous;
	const facts = freezeRecord({
		player: frozenPlayer,
		world: freezeRecord({ items: worldItems, entities: worldEntities, blocks: worldBlocks, state: worldState, menu }),
		inventory: freezeRecord({ items: inventoryItems, tagCounts: inventoryTagCounts, state: inventoryState }),
	});
	if (withinInterpreterFactLimits(facts)) INTERPRETER_FACTS.add(facts);
	return facts;
}

/** Returns true only for fact trees fully validated and frozen by this module. */
export function isTrustedInterpreterFacts(value) {
	return INTERPRETER_FACTS.has(value);
}

/** Compares trusted fact domains by identity after createInterpreterFacts structural sharing. */
export function changedInterpreterFactDomains(previous, next) {
	if (!INTERPRETER_FACTS.has(previous) || !INTERPRETER_FACTS.has(next)) return ALL_FACT_DOMAINS;
	let changed = 0;
	if (previous.player !== next.player) changed |= FACT_DOMAIN.player;
	if (previous.world.items !== next.world.items) changed |= FACT_DOMAIN.worldItems;
	if (previous.world.entities !== next.world.entities) changed |= FACT_DOMAIN.worldEntities;
	if (previous.world.blocks !== next.world.blocks) changed |= FACT_DOMAIN.worldBlocks;
	if (previous.inventory.items !== next.inventory.items) changed |= FACT_DOMAIN.inventoryItems;
	if (previous.inventory.tagCounts !== next.inventory.tagCounts) changed |= FACT_DOMAIN.inventoryTagCounts;
	if (previous.world.state !== next.world.state) changed |= FACT_DOMAIN.worldState;
	if (previous.world.menu !== next.world.menu) changed |= FACT_DOMAIN.menu;
	if (previous.inventory.state !== next.inventory.state) changed |= FACT_DOMAIN.inventoryState;
	return changed;
}

export function filterObserved(candidates, criteria = {}) {
	if (!OBSERVED_SETS.has(candidates)) throw new TypeError('candidate set is not an observed fact set');
	const record = ownDataRecord(criteria, 'criteria');
	const entries = Object.entries(record);
	if (entries.some(([key]) => !validKey(key))) throw new TypeError('criteria contains an unsafe key');
	return observedList(candidates.filter((candidate) => entries.every(([key, value]) => matches(candidate, key, value))), candidates);
}

export function nearest(candidates, origin) {
	if (!OBSERVED_SETS.has(candidates)) throw new TypeError('nearest requires an observed candidate set');
	if (candidates.length === 0) return null;
	const point = pointOf(origin, 'origin');
	let closest = candidates[0];
	let closestDistance = distanceSquared(closest, point);
	for (let index = 1; index < candidates.length; index += 1) {
		const candidate = candidates[index];
		const candidateDistance = distanceSquared(candidate, point);
		if (candidateDistance < closestDistance
			|| (candidateDistance === closestDistance && codePointCompare(candidate.stableId, closest.stableId) < 0)) {
			closest = candidate;
			closestDistance = candidateDistance;
		}
	}
	return closest;
}

export function nearestFromCurrent(candidates, origin, currentSets) {
	if (!Array.isArray(currentSets) || !currentSets.includes(CANDIDATE_ORIGINS.get(candidates))) throw new TypeError('nearest candidates are not from the current observation');
	return nearest(candidates, origin);
}

export function markObservedCandidateSet(candidates) {
	if (!Array.isArray(candidates) || !Object.isFrozen(candidates)) throw new TypeError('observed candidate set must be a frozen array');
	OBSERVED_SETS.add(candidates);
	return candidates;
}

function copyCandidates(values, kind) {
	if (values === undefined) return observedList([]);
	return observedList(denseDataArray(values, `observation ${kind}s`).map((value) => copyCandidate(value, kind)));
}

function copyCandidate(value, kind) {
	const source = ownDataRecord(value, `observation ${kind}`);
	const required = kind === 'item' ? ['stableId', 'itemId', 'count', 'x', 'y', 'z'] : kind === 'entity' ? ['stableId', 'type', 'x', 'y', 'z'] : ['stableId', 'blockId', 'x', 'y', 'z'];
	if (required.some((key) => !Object.hasOwn(source, key))) throw new TypeError(`observation ${kind} has an invalid schema`);
	if (!Number.isFinite(source.x) || !Number.isFinite(source.y) || !Number.isFinite(source.z)) return null;
	if (typeof source.stableId !== 'string' || source.stableId.length === 0) throw new TypeError(`observation ${kind} has invalid identity`);
	if (kind === 'item' && (typeof source.itemId !== 'string' || !nonNegativeInteger(source.count))) throw new TypeError('observation item has invalid item fields');
	if (kind === 'entity' && typeof source.type !== 'string') throw new TypeError('observation entity has invalid type');
	if (kind === 'block' && typeof source.blockId !== 'string') throw new TypeError('observation block has invalid block id');
	const copied = Object.assign(Object.create(null), copyFactData(source, `observation ${kind}`));
	if (Object.hasOwn(source, 'tags')) {
		const tags = denseDataArray(source.tags, `observation ${kind} tags`);
		if (tags.some((tag) => typeof tag !== 'string')) throw new TypeError(`observation ${kind} has invalid tags`);
		copied.tags = Object.freeze([...tags]);
	}
	copied.position = freezeRecord({ x: source.x, y: source.y, z: source.z });
	return freezeRecord(copied);
}

function copyInventory(values) {
	if (values === undefined) return observedList([]);
	return observedList(denseDataArray(values, 'observation inventory items').map((value) => {
		const source = ownDataRecord(value, 'observation inventory item');
		if (typeof source.itemId !== 'string' || !nonNegativeInteger(source.count)) throw new TypeError('observation inventory item has an invalid schema');
		const copied = Object.assign(Object.create(null), copyFactData(source, 'observation inventory item'));
		if (Object.hasOwn(source, 'tags')) {
			const tags = denseDataArray(source.tags, 'observation inventory item tags');
			if (tags.some((tag) => typeof tag !== 'string')) throw new TypeError('observation inventory item has invalid tags');
			copied.tags = Object.freeze([...tags]);
		}
		return freezeRecord(copied);
	}));
}

function copyFactData(value, label, ancestors = new Set(), depth = 0) {
	if (value === null || typeof value === 'string' || typeof value === 'boolean' || Number.isFinite(value)) return value;
	if (depth > TRUST_LIMITS.depth || ancestors.has(value)) throw new TypeError(`${label} exceeds the depth limit or contains a cycle`);
	ancestors.add(value);
	try {
		if (Array.isArray(value)) return Object.freeze(denseDataArray(value, label).map((entry) => copyFactData(entry, label, ancestors, depth + 1)));
		const source = ownDataRecord(value, label);
		return freezeRecord(Object.fromEntries(Object.entries(source).filter(([_key, entry]) => entry !== undefined)
			.map(([key, entry]) => [key, copyFactData(entry, `${label}.${key}`, ancestors, depth + 1)])));
	} finally { ancestors.delete(value); }
}

function denseDataArray(value, label) {
	if (!Array.isArray(value) || nodeTypes.isProxy(value) || Object.getPrototypeOf(value) !== Array.prototype) throw new TypeError(`${label} must be a plain array`);
	const descriptors = Object.getOwnPropertyDescriptors(value);
	const keys = Reflect.ownKeys(value);
	if (keys.some((key) => typeof key === 'symbol' || (key !== 'length' && !/^(0|[1-9]\d*)$/.test(key)))) throw new TypeError(`${label} has unsafe keys`);
	const length = descriptors.length;
	if (!length || !Object.hasOwn(length, 'value') || length.get || length.set || !Number.isSafeInteger(length.value)) throw new TypeError(`${label} has invalid length`);
	const copied = [];
	for (let index = 0; index < length.value; index += 1) {
		const descriptor = descriptors[String(index)];
		if (!descriptor || !descriptor.enumerable || !Object.hasOwn(descriptor, 'value') || descriptor.get || descriptor.set) throw new TypeError(`${label} must be dense own data`);
		copied.push(descriptor.value);
	}
	if (keys.length !== length.value + 1) throw new TypeError(`${label} must not have holes or custom keys`);
	return copied;
}

function ownDataRecord(value, label) {
	if (value === null || typeof value !== 'object' || Array.isArray(value) || nodeTypes.isProxy(value) || ![null, Object.prototype].includes(Object.getPrototypeOf(value))) throw new TypeError(`${label} must be a plain data record`);
	const record = Object.create(null);
	for (const key of Reflect.ownKeys(value)) {
		if (typeof key !== 'string' || FORBIDDEN_KEYS.has(key)) throw new TypeError(`${label} contains an unsafe key`);
		const descriptor = Object.getOwnPropertyDescriptor(value, key);
		if (!descriptor || !descriptor.enumerable || !Object.hasOwn(descriptor, 'value') || descriptor.get || descriptor.set) throw new TypeError(`${label}.${key} must be own data`);
		record[key] = descriptor.value;
	}
	return record;
}

function countInventory(items, itemId) { return typeof itemId === 'string' ? items.reduce((total, item) => total + (item.itemId === itemId ? item.count : 0), 0) : 0; }
function matches(candidate, key, value) { return key === 'tag' ? Array.isArray(candidate.tags) && candidate.tags.includes(value) : Object.hasOwn(candidate, key) && candidate[key] === value; }
function distanceSquared(candidate, origin) { const point = pointOf(candidate, 'candidate'); return (point.x - origin.x) ** 2 + (point.y - origin.y) ** 2 + (point.z - origin.z) ** 2; }
function pointOf(value, label) { const source = ownDataRecord(value, label); const point = Object.hasOwn(source, 'position') ? ownDataRecord(source.position, `${label}.position`) : source; if (![point.x, point.y, point.z].every(Number.isFinite)) throw new TypeError(`${label} requires finite coordinates`); return { x: point.x, y: point.y, z: point.z }; }
function codePointCompare(left, right) { return left === right ? 0 : left < right ? -1 : 1; }
function nonNegativeInteger(value) { return Number.isSafeInteger(value) && value >= 0; }
function validKey(key) { return !FORBIDDEN_KEYS.has(key); }
function observedList(values, origin = null) { const frozen = Object.freeze(values.filter((value) => value !== null)); OBSERVED_SETS.add(frozen); if (origin !== null) CANDIDATE_ORIGINS.set(frozen, origin); return frozen; }
function freezeRecord(values) { return Object.freeze(Object.assign(Object.create(null), values)); }

function reuseEqual(previous, next) {
	if (previous === undefined || !sameTrustedValue(previous, next)) return next;
	return previous;
}

function sameTrustedValue(left, right) {
	if (Object.is(left, right)) return true;
	if (left === null || right === null || typeof left !== 'object' || typeof right !== 'object' || Array.isArray(left) !== Array.isArray(right)) return false;
	const leftKeys = Object.keys(left);
	const rightKeys = Object.keys(right);
	if (leftKeys.length !== rightKeys.length) return false;
	for (let index = 0; index < leftKeys.length; index += 1) {
		const key = leftKeys[index];
		if (key !== rightKeys[index] || !sameTrustedValue(left[key], right[key])) return false;
	}
	return true;
}

function withinInterpreterFactLimits(root) {
	const stats = interpreterFactStats(root);
	return stats !== null
		&& stats.nodes <= TRUST_LIMITS.nodes
		&& stats.keys <= TRUST_LIMITS.keys
		&& stats.bytes <= TRUST_LIMITS.factBytes
		&& stats.depth <= TRUST_LIMITS.depth;
}

function interpreterFactStats(value) {
	if (typeof value === 'string') {
		const bytes = Buffer.byteLength(value, 'utf8');
		return bytes > TRUST_LIMITS.stringBytes ? null : { nodes: 0, keys: 0, bytes, depth: -1 };
	}
	if (value === null || typeof value !== 'object') return EMPTY_FACT_STATS;
	const cached = FACT_STATS.get(value);
	if (cached !== undefined) return cached;
	if (Array.isArray(value) && value.length > TRUST_LIMITS.arrayLength) return null;
	const keys = Object.keys(value);
	const stats = { nodes: 1, keys: keys.length, bytes: 0, depth: 0 };
	for (let index = 0; index < keys.length; index += 1) {
		const key = keys[index];
		const keyBytes = Array.isArray(value)
			? index < 10 ? 1 : index < 100 ? 2 : index < 1_000 ? 3 : String(index).length
			: Buffer.byteLength(key, 'utf8');
		if (keyBytes > TRUST_LIMITS.stringBytes) return null;
		const child = interpreterFactStats(value[key]);
		if (child === null) return null;
		stats.nodes += child.nodes;
		stats.keys += child.keys;
		stats.bytes += keyBytes + child.bytes;
		stats.depth = Math.max(stats.depth, child.depth + 1);
	}
	FACT_STATS.set(value, stats);
	return stats;
}
