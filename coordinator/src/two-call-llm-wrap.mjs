/** Shapes factual recovery, exploration candidates and action failure categories. */

import { ExplorationOccupancy } from './explore-frontier.mjs';
import { RecoveryProgressStore, doNotRedoFor } from './recovery-progress-wrap.mjs';

const PATH_FAILURE_CODES = new Set([
	'PATH_BLOCKED',
	'DESTINATION_BLOCKED',
	'TARGET_NOT_LOADED',
	'NO_PATH',
	'NO_STANDABLE_PATH',
	'PATH_LIMIT_REACHED',
	'NO_FRONTIER',
]);

const TARGET_FAILURE_CODES = new Set([
	'TARGET_NOT_VISIBLE',
	'TARGET_OUT_OF_RANGE',
	'TARGET_TOO_FAR',
	'TARGET_CHANGED',
	'ACTION_TIMEOUT',
	'STALE_REVISION',
	'EXPECTED_BLOCK_MISMATCH',
	'NO_OBSERVATION',
]);

const SUCCESS_REASON_CODES = new Set([
	'DONE',
	'BLOCK_BROKEN',
	'DESTINATION_REACHED',
	'ACTION_COMPLETED',
	'CONTROL_SEGMENT_COMPLETED',
	'TARGET_ALREADY_SATISFIED',
	'BLOCK_PLACED',
	'ITEM_PICKED_UP',
	'CUE_IN_VIEW',
	'COMPLETION_VERIFIED',
]);

const LIFECYCLE_FAILURE_CODES = new Set([
	'PLAYER_DEAD',
	'PLAYER_UNAVAILABLE',
	'AGENT_DEAD',
]);

const CAPABILITY_FAILURE_CODES = new Set([
	'RECIPE_NOT_FOUND',
	'UNSUPPORTED_ACTION',
	'SIMULATOR_UNSUPPORTED_ACTION',
]);


export { doNotRedoFor };

/** Unit facade over recovery + occupancy. Production uses NativeToolRuntime's stores. */
export class TwoCallLlmWrap {
	#recovery = new RecoveryProgressStore();
	#occupancy = new ExplorationOccupancy();

	ingest(agentId, observation = {}, { goalRevision = 0 } = {}) {
		this.#recovery.remember(agentId, goalRevision, observation);
		this.#occupancy.ingest(agentId, observation);
		return this.#recovery.snapshot(agentId, observation);
	}

	decorate(agentId, observation = {}) {
		const snapshot = this.#recovery.snapshot(agentId, observation);
		return composeTwoCallView(observation, snapshot, {
			occupancy: this.#occupancy,
			agentId,
		});
	}

	ingestAndDecorate(agentId, observation = {}, options = {}) {
		this.ingest(agentId, observation, options);
		return this.decorate(agentId, observation, options);
	}
}

export function wrapTwoCallObservation(observation = {}, recoveryState = {}, { goal = null } = {}) {
	const recovery = recoveryFromState(observation, recoveryState);
	return composeTwoCallView(observation, recovery, {
		occupancy: null,
		agentId: 'wrap',
		goal,
	});
}

export function classifyBodyFailure(reasonCode, state = undefined) {
	const code = typeof reasonCode === 'string' ? reasonCode : '';
	if (code.length === 0) return null;
	const normalizedState = typeof state === 'string' ? state : '';
	if (normalizedState === 'SUCCEEDED' || normalizedState === 'COMPLETED') return null;
	if (SUCCESS_REASON_CODES.has(code)) return null;
	if (LIFECYCLE_FAILURE_CODES.has(code)) return 'lifecycle';
	if (PATH_FAILURE_CODES.has(code)) return 'path';
	if (CAPABILITY_FAILURE_CODES.has(code)) return 'capability';
	if (TARGET_FAILURE_CODES.has(code)) return 'target';
	if (code.length > 0) return 'action';
	return null;
}

/** Retained for old callers. The runtime no longer infers a search objective from goal prose. */
export function inferFrontierSeek() { return 'any'; }

export function composeTwoCallView(observation = {}, recovery = null, {
	occupancy = null,
	agentId = null,
} = {}) {
	const failureClass = inferFailureClass(observation);
	const sourceRecovery = normalizeRecovery(observation, recovery);
	const normalized = sourceRecovery === null ? null : Object.fromEntries(Object.entries(sourceRecovery).filter(([key]) => key !== 'doNotRedo'));
	const lastDeath = normalized?.lastDeath ?? observation.death ?? null;
	const facts = recoveryFacts({ recovery: normalized, lastDeath, observation });
	const recoveryView = normalized === null
		? (facts.length === 0 ? null : { alreadyHave: [], facts })
		: { ...normalized, facts };
	const exploration = occupancy !== null && typeof occupancy.candidates === 'function' && agentId !== null
		? occupancy.candidates(agentId, observation) : null;
	const { options: _oldOptions, failureClass: _oldFailureClass, exploration: _oldExploration, ...currentFacts } = observation;
	return {
		...currentFacts,
		...(recoveryView === null ? {} : { recovery: recoveryView }),
		...(exploration === null ? {} : { exploration }),
		...(failureClass === null ? {} : { failureClass }),
	};
}

function inferFailureClass(observation) {
	const classified = classifyBodyFailure(observation.lastResult?.reasonCode, observation.lastResult?.state);
	if (classified !== null) return classified;
	if (observation.continuity?.phase === 'dead' || observation.player?.dead === true || observation.death != null) {
		return 'lifecycle';
	}
	return null;
}

function recoveryFacts({ recovery, lastDeath, observation }) {
	const parts = [];
	if (lastDeath !== null) {
		parts.push(`Last death: ${lastDeath.cause ?? 'unknown'} at x=${lastDeath.x},y=${lastDeath.y},z=${lastDeath.z}.`);
	}
	const held = inventoryItemIds(observation ?? {});
	if (lastDeath !== null) {
		parts.push(held.length === 0 ? 'Current inventory is empty.' : `Current inventory: ${unique(held).join(', ')}.`);
	}
	const have = itemIds(recovery);
	if (have.length > 0) parts.push(`Currently evidenced: ${unique(have).join(', ')}.`);
	const lost = Array.isArray(recovery?.lastLostInventory)
		? recovery.lastLostInventory.map((entry) => entry?.itemId).filter(Boolean)
		: [];
	if (lost.length > 0) parts.push(`Lost on death: ${unique(lost).join(', ')}.`);
	return parts.join(' ');
}

function normalizeRecovery(observation, recovery) {
	if (recovery === null && observation?.recovery === undefined) {
		return recoveryFromState(observation, {});
	}
	const source = recovery ?? observation.recovery ?? null;
	if (source === null) return null;
	if (Array.isArray(source.alreadyHave) && source.alreadyHave.some((entry) => entry !== null && typeof entry === 'object')) {
		return {
			...source,
			lastDeath: source.lastDeath ?? observation.death ?? null,
		};
	}
	const alreadyHave = itemIds(source);
	const lastDeath = source.lastDeath ?? observation.death ?? null;
	const lastLostInventory = Array.isArray(source.lastLostInventory) ? source.lastLostInventory : [];
	const doNotRedo = Array.isArray(source.doNotRedo) && source.doNotRedo.length > 0
		? source.doNotRedo
		: doNotRedoFor(alreadyHave);
	if (lastDeath === null && alreadyHave.length === 0 && lastLostInventory.length === 0 && !(Array.isArray(doNotRedo) && doNotRedo.length > 0)) {
		return null;
	}
	return {
		...source,
		...(lastDeath === null ? {} : { lastDeath }),
		alreadyHave,
		doNotRedo,
		...(lastLostInventory.length === 0 ? {} : { lastLostInventory }),
	};
}

function recoveryFromState(observation, recoveryState = {}) {
	const inventoryIds = inventoryItemIds(observation);
	const alreadyHave = unique([
		...inventoryIds,
		...(Array.isArray(recoveryState.alreadyHave) ? itemIds({ alreadyHave: recoveryState.alreadyHave }) : []),
	]);
	const lastDeath = recoveryState.lastDeath ?? observation.death ?? null;
	const lastLostInventory = Array.isArray(recoveryState.lastLostInventory) ? recoveryState.lastLostInventory : [];
	const doNotRedo = doNotRedoFor(alreadyHave);
	if (lastDeath === null && alreadyHave.length === 0 && lastLostInventory.length === 0) return null;
	return {
		...(lastDeath === null ? {} : { lastDeath }),
		alreadyHave,
		doNotRedo,
		...(lastLostInventory.length === 0 ? {} : { lastLostInventory }),
	};
}

function itemIds(recovery) {
	const have = recovery?.alreadyHave ?? [];
	if (have.length === 0) return [];
	if (typeof have[0] === 'string') return have;
	return have.map((entry) => entry?.itemId ?? entry?.blockId).filter((value) => typeof value === 'string');
}

function inventoryItemIds(observation) {
	const raw = observation?.inventory;
	const items = Array.isArray(raw) ? raw : Array.isArray(raw?.items) ? raw.items : [];
	return items.map((item) => item?.itemId).filter((value) => typeof value === 'string' && value !== 'minecraft:air');
}

function unique(values) {
	return [...new Set(values.filter(Boolean))];
}
