import { extractDimension } from './explore-frontier.mjs';

const MAX_PLACED = 16;
const MAX_DROPPED = 16;
const MAX_INVENTORY = 32;
export const MAX_EVER_POSSESSED = 128;
const WORKSTATION_BLOCKS = new Set([
	'minecraft:crafting_table',
	'minecraft:furnace',
	'minecraft:blast_furnace',
	'minecraft:smoker',
	'minecraft:chest',
	'minecraft:trapped_chest',
	'minecraft:barrel',
	'minecraft:ender_chest',
	'minecraft:enchanting_table',
	'minecraft:anvil',
	'minecraft:chipped_anvil',
	'minecraft:damaged_anvil',
	'minecraft:smithing_table',
	'minecraft:grindstone',
	'minecraft:brewing_stand',
	'minecraft:lodestone',
	'minecraft:respawn_anchor',
	'minecraft:end_portal_frame',
]);

/**
 * Coordinator-side recovery wrap for the two-call native LLM.
 * Minecraft remains authoritative for inventory, drops, and placed blocks.
 * This store only remembers the last live snapshot across death/respawn so
 * observe/say/mine/moveTo results can name last death and currently evidenced progress.
 */
export class RecoveryProgressStore {
	#agents = new Map();

	remember(agentId, goalRevision, observation = {}) {
		const id = requireAgentId(agentId);
		if (!Number.isSafeInteger(goalRevision) || goalRevision < 0) throw new TypeError('goalRevision must be a nonnegative safe integer');
		const previous = this.#agents.get(id) ?? emptyRecord(goalRevision);
		const next = previous.goalRevision === goalRevision ? cloneRecord(previous) : emptyRecord(goalRevision, previous.lastDeath);
		if (hasDurableObservationFacts(observation)) ingestObservation(next, observation);
		this.#agents.set(id, next);
		return this.snapshot(id, observation);
	}

	wrap(agentId, goalRevision, observation = {}) {
		if (Number.isSafeInteger(goalRevision) && goalRevision >= 0) this.remember(agentId, goalRevision, observation);
		return attachRecovery(observation, this.snapshot(agentId, observation));
	}

	snapshot(agentId, observation = {}) {
		const record = this.#agents.get(requireAgentId(agentId));
		if (record === undefined) return deriveRecovery(null, observation);
		return deriveRecovery(record, observation);
	}

	forget(agentId) {
		this.#agents.delete(requireAgentId(agentId));
	}

	clear() {
		this.#agents.clear();
	}
}

export function wrapObservationWithRecovery(observation, snapshot) {
	return attachRecovery(observation, snapshot);
}

export function deriveRecoveryFacts(record, observation = {}) {
	return deriveRecovery(record, observation);
}

/** True when the payload carries world facts worth ingesting into recovery memory. */
export function hasDurableObservationFacts(observation) {
	if (observation === null || typeof observation !== 'object') return false;
	return observation.death != null
		|| observation.inventory !== undefined
		|| observation.player !== undefined
		|| observation.blocks !== undefined
		|| observation.items !== undefined
		|| observation.status !== undefined
		|| observation.position !== undefined
		|| observation.world !== undefined;
}

/** Compatibility field only. Crafting decisions belong to the selected model. */
export function doNotRedoFor() { return []; }

function ingestObservation(record, observation) {
	if (observation?.death !== undefined && observation.death !== null) {
		const nextDeath = normalizeDeath(observation.death);
		const lost = lostInventoryAtDeath(record, observation);
		const isNewDeath = record.lastDeath === null || !sameDeath(record.lastDeath, nextDeath);
		if (isNewDeath || record.inventory.length > 0) {
			record.lastLostInventory = lost;
		}
		record.lastDeath = nextDeath;
		record.everPossessed = uniqueIds([...record.everPossessed, ...lost.map((item) => item.itemId), ...record.lastLostInventory.map((item) => item.itemId)]);
		record.inventory = [];
		return;
	}
	const inventory = inventoryItems(observation);
	const dropped = droppedItems(observation);
	const placed = placedAssets(observation);
	const alive = observation?.player?.dead !== true && observation?.ready !== false;
	if (inventory.length > 0) {
		record.everPossessed = uniqueIds([...record.everPossessed, ...inventory.map((item) => item.itemId)]);
	}
	if (alive) {
		record.inventory = inventory;
		if (inventory.length > 0 && record.lastLostInventory.length > 0) {
			record.lastLostInventory = subtractRecovered(record.lastLostInventory, inventory);
		}
	}
	if (dropped.length > 0) record.dropped = dropped;
	if (placed.length > 0) {
		mergePlaced(record, placed);
		record.everPossessed = uniqueIds([...record.everPossessed, ...placed.map((item) => item.blockId)]);
	}
}

function deriveRecovery(record, observation) {
	const currentInventory = observation?.inventory !== undefined
		? inventoryItems(observation)
		: [...(record?.inventory ?? [])];
	const currentDropped = droppedItems(observation);
	const currentPlaced = placedAssets(observation);
	const lastDeath = observation?.death !== undefined && observation.death !== null
		? normalizeDeath(observation.death)
		: record?.lastDeath ?? null;
	const lastLostInventory = record?.lastLostInventory ?? [];
	const rememberedPlaced = record?.placed ?? [];
	const alreadyHaveFacts = compactAlreadyHave([
		...currentInventory.map((item) => ({ kind: 'inventory', ...item })),
		...currentDropped.map((item) => ({ kind: 'dropped', ...item })),
		...currentPlaced.map((item) => ({ kind: 'placed', ...item })),
		...rememberedPlaced
			.filter((item) => placedInCurrentDimension(item, observation) && !currentPlaced.some((current) => samePlaced(current, item)))
			.map((item) => ({ kind: 'placed', ...item, remembered: true })),
	]);
	const alreadyHave = uniqueIds(alreadyHaveFacts.map((entry) => entry.itemId ?? entry.blockId));
	const doNotRedo = doNotRedoFor(alreadyHave);
	if (lastDeath === null && alreadyHave.length === 0 && lastLostInventory.length === 0 && doNotRedo.length === 0) return null;
	return Object.freeze({
		...(lastDeath === null ? {} : { lastDeath }),
		...(lastLostInventory.length === 0 ? {} : { lastLostInventory: lastLostInventory.map(cloneItem) }),
		alreadyHave,
		alreadyHaveFacts,
		doNotRedo,
	});
}

function attachRecovery(observation, recovery) {
	const source = observation === null || observation === undefined ? {} : observation;
	if (recovery === null) return source;
	return { ...source, recovery };
}

function inventoryItems(observation) {
	const raw = observation?.inventory;
	const items = Array.isArray(raw) ? raw : Array.isArray(raw?.items) ? raw.items : [];
	return items.map(summarizeStack).filter(Boolean).slice(0, MAX_INVENTORY);
}

function droppedItems(observation) {
	const items = Array.isArray(observation?.items) ? observation.items : [];
	const fromEntities = Array.isArray(observation?.entities)
		? observation.entities.filter((entity) => entity?.type === 'minecraft:item')
		: [];
	return [...items, ...fromEntities].map(summarizeDrop).filter(Boolean).slice(0, MAX_DROPPED);
}

function placedAssets(observation) {
	const blocks = Array.isArray(observation?.blocks) ? observation.blocks : [];
	const containers = Array.isArray(observation?.nearbyContainers) ? observation.nearbyContainers : [];
	const placed = [];
	for (const block of [...blocks, ...containers]) {
		const blockId = typeof block?.blockId === 'string' ? block.blockId : null;
		if (blockId === null || !WORKSTATION_BLOCKS.has(blockId)) continue;
		if (!Number.isFinite(block.x) || !Number.isFinite(block.y) || !Number.isFinite(block.z)) continue;
		placed.push(Object.freeze({
			blockId,
			x: block.x,
			y: block.y,
			z: block.z,
			dimension: extractDimension(observation),
		}));
	}
	return uniquePlaced(placed).slice(0, MAX_PLACED);
}

function mergePlaced(record, placed) {
	record.placed = uniquePlaced([...placed, ...record.placed]).slice(0, MAX_PLACED);
}

function summarizeStack(value) {
	const itemId = typeof value?.itemId === 'string' && value.itemId.length > 0 ? value.itemId : null;
	if (itemId === null || itemId === 'minecraft:air') return null;
	const count = Number.isSafeInteger(value.count) && value.count > 0 ? value.count : 1;
	return Object.freeze({ itemId, count });
}

function summarizeDrop(value) {
	const stack = summarizeStack(value);
	if (stack === null) return null;
	return Object.freeze({
		...stack,
		...(typeof value.stableId === 'string' ? { stableId: value.stableId } : {}),
		...(Number.isFinite(value.x) ? { x: value.x, y: value.y, z: value.z } : {}),
	});
}

function normalizeDeath(value) {
	if (value === null || typeof value !== 'object' || Array.isArray(value)) return null;
	const cause = typeof value.cause === 'string' && value.cause.trim().length > 0 ? value.cause.trim().slice(0, 256) : 'unknown';
	const dimensionId = typeof value.dimensionId === 'string' && value.dimensionId.length > 0 ? value.dimensionId : 'minecraft:overworld';
	const x = Number.isFinite(value.x) ? value.x : 0;
	const y = Number.isFinite(value.y) ? value.y : 0;
	const z = Number.isFinite(value.z) ? value.z : 0;
	const diedAtEpochMs = Number.isSafeInteger(value.diedAtEpochMs) && value.diedAtEpochMs > 0 ? value.diedAtEpochMs : null;
	return Object.freeze({
		cause,
		dimensionId,
		x,
		y,
		z,
		...(diedAtEpochMs === null ? {} : { diedAtEpochMs }),
	});
}

function compactAlreadyHave(entries) {
	const seen = new Set();
	const result = [];
	for (const entry of entries) {
		const key = `${entry.kind}:${entry.itemId ?? entry.blockId}:${entry.dimension ?? ''}:${entry.x ?? ''}:${entry.y ?? ''}:${entry.z ?? ''}`;
		if (seen.has(key)) continue;
		seen.add(key);
		result.push(Object.freeze({ ...entry }));
		if (result.length >= MAX_INVENTORY + MAX_DROPPED + MAX_PLACED) break;
	}
	return Object.freeze(result);
}

function uniquePlaced(entries) {
	const seen = new Set();
	const result = [];
	for (const entry of entries) {
		const key = `${entry.blockId}:${entry.dimension ?? ''}:${entry.x}:${entry.y}:${entry.z}`;
		if (seen.has(key)) continue;
		seen.add(key);
		result.push(entry);
	}
	return result;
}

function samePlaced(left, right) {
	return left.blockId === right.blockId
		&& left.x === right.x && left.y === right.y && left.z === right.z
		&& (left.dimension ?? 'minecraft:overworld') === (right.dimension ?? 'minecraft:overworld');
}

function placedInCurrentDimension(item, observation) {
	if (!observationHasDimension(observation)) return true;
	return (item.dimension ?? 'minecraft:overworld') === extractDimension(observation);
}

function observationHasDimension(observation) {
	return typeof observation?.world?.dimension === 'string' && observation.world.dimension.length > 0
		|| typeof observation?.world?.dimensionId === 'string' && observation.world.dimensionId.length > 0;
}

function lostInventoryAtDeath(record, observation) {
	if (observation.lastLiveInventory !== undefined) {
		return inventoryItems({ inventory: observation.lastLiveInventory });
	}
	return record.inventory.slice(0, MAX_INVENTORY);
}

function sameDeath(left, right) {
	if (left == null || right == null) return false;
	return left.cause === right.cause
		&& left.x === right.x && left.y === right.y && left.z === right.z
		&& (left.dimensionId ?? 'minecraft:overworld') === (right.dimensionId ?? 'minecraft:overworld')
		&& left.diedAtEpochMs === right.diedAtEpochMs;
}

function cloneItem(item) {
	return Object.freeze({ ...item });
}

function uniqueIds(values, max = MAX_EVER_POSSESSED) {
	const seen = new Set();
	const newestFirst = [];
	const source = Array.isArray(values) ? values : [];
	for (let index = source.length - 1; index >= 0; index -= 1) {
		const value = source[index];
		if (typeof value !== 'string' || value.length === 0 || seen.has(value)) continue;
		seen.add(value);
		newestFirst.push(value);
		if (newestFirst.length >= max) break;
	}
	return Object.freeze(newestFirst.reverse());
}

function subtractRecovered(lost, recovered) {
	const remaining = lost.map((item) => ({ itemId: item.itemId, count: item.count }));
	for (const stack of recovered) {
		let leftover = stack.count;
		for (const lostStack of remaining) {
			if (leftover <= 0) break;
			if (lostStack.itemId !== stack.itemId || lostStack.count <= 0) continue;
			const take = Math.min(lostStack.count, leftover);
			lostStack.count -= take;
			leftover -= take;
		}
	}
	return remaining.filter((item) => item.count > 0).slice(0, MAX_INVENTORY).map((item) => Object.freeze(item));
}

function emptyRecord(goalRevision, lastDeath = null) {
	return {
		goalRevision,
		lastDeath,
		inventory: [],
		lastLostInventory: [],
		dropped: [],
		placed: [],
		everPossessed: [],
	};
}

function cloneRecord(record) {
	return {
		goalRevision: record.goalRevision,
		lastDeath: record.lastDeath,
		inventory: record.inventory.slice(),
		lastLostInventory: record.lastLostInventory.slice(),
		dropped: record.dropped.slice(),
		placed: record.placed.slice(),
		everPossessed: record.everPossessed.slice(),
	};
}

function requireAgentId(agentId) {
	if (typeof agentId !== 'string' || agentId.trim().length === 0) throw new TypeError('agentId must be nonblank');
	return agentId;
}
