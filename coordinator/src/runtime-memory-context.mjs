import { randomUUID } from 'node:crypto';
import { types as nodeTypes } from 'node:util';
import { ModelNotebook } from './model-notebook.mjs';
import { normalizeMinecraftToolCall } from './native-minecraft-tools.mjs';
import { validateAction } from './schema.mjs';

const TERMINAL_STATES = new Set(['SUCCEEDED', 'FAILED', 'CANCELLED', 'TIMED_OUT']);
const AUTHOR_FIELDS = ['provider', 'model', 'reasoningEffort', 'serviceTier'];
const AUTHOR_IDS = ['programId', 'sourceStepId', 'turnId', 'callId'];

/** Shares scoped model notes and factual action receipts across execution modes. */
export class RuntimeMemoryContext {
	#notebook; #sessionId; #contexts = new Map(); #dispatches = new Map();
	constructor({ notebook = new ModelNotebook(), sessionId = randomUUID() } = {}) {
		for (const method of ['writeNote', 'query', 'recordDispatch', 'recordUnknown', 'recordReceipt', 'findReceipt']) {
			if (typeof notebook?.[method] !== 'function') throw new TypeError(`notebook.${method} is required`);
		}
		this.#notebook = notebook;
		this.#sessionId = boundedText(sessionId, 'sessionId', 128);
	}
	get notebook() { return this.#notebook; }
	get sessionId() { return this.#sessionId; }

	observe(record, observation) {
		const { agentId, goalRevision } = recordIdentity(record);
		const source = ownRecord(observation, 'observation');
		const world = source.world === undefined ? {} : ownRecord(source.world, 'observation.world');
		const previous = this.#contexts.get(agentId);
		const suppliedWorld = world.worldId ?? source.worldId;
		const worldId = suppliedWorld === undefined
			? previous?.goalRevision === goalRevision ? previous.worldId : `session:${this.#sessionId}`
			: boundedText(suppliedWorld, 'worldId', 256);
		const dimension = world.dimension ?? world.dimensionId ?? source.dimension;
		const context = { goalRevision, worldId,
			...(typeof dimension === 'string' ? { dimension: boundedText(dimension, 'dimension', 128) } : {}),
			...(Number.isSafeInteger(source.worldTick) && source.worldTick >= 0 ? { tick: source.worldTick } : {}),
		};
		this.#contexts.set(agentId, context);
		return { ...context };
	}
	worldId(record) {
		const { agentId, goalRevision } = recordIdentity(record);
		const context = this.#contexts.get(agentId);
		return context?.goalRevision === goalRevision ? context.worldId : null;
	}
	forget(agentId) { this.#contexts.delete(boundedText(agentId, 'agentId', 256)); }
	unresolved(record, page = {}) { return this.execute(record, { operation: 'query', arguments: { ...ownRecord(page, 'memory page'), kind: 'unresolved' } }); }

	async execute(record, request) {
		const { agentId, goalRevision } = recordIdentity(record);
		const worldId = this.worldId(record);
		if (worldId === null) throw codedError('WORLD_ID_REQUIRED', 'Observe the current agent world before accessing memory');
		const source = ownRecord(request, 'memory request');
		if (!['write', 'query'].includes(source.operation)) throw codedError('INVALID_MEMORY_OPERATION', 'Memory operation must be write or query');
		const normalized = normalizeMinecraftToolCall(source.operation === 'write' ? 'notebook' : 'queryMemory', source.arguments);
		if (source.operation === 'write') {
			const provenance = noteAuthor(record, source.provenance);
			const note = await this.#notebook.writeNote(agentId, { worldId, goalRevision, key: normalized.key, text: normalized.text, provenance });
			return { state: 'SUCCEEDED', reasonCode: 'NOTE_WRITTEN', note };
		}
		const result = await this.#notebook.query(agentId, { worldId, kind: normalized.memoryKind, limit: normalized.limit,
			...(normalized.offset === undefined ? {} : { offset: normalized.offset }), ...(normalized.text === undefined ? {} : { text: normalized.text }) });
		return { state: 'SUCCEEDED', reasonCode: 'MEMORY_QUERIED', ...result };
	}

	async recordDispatch(record, payload) {
		const { agentId, goalRevision } = recordIdentity(record);
		const context = this.#contexts.get(agentId);
		if (context?.goalRevision !== goalRevision) throw codedError('WORLD_ID_REQUIRED', 'Observe the current agent world before dispatch');
		const source = ownRecord(payload, 'action dispatch');
		if (source.goalRevision !== goalRevision) throw codedError('STALE_GOAL', 'Dispatch does not match the current goal');
		const actionId = boundedText(source.actionId, 'actionId', 256);
		const args = ownRecord(source.arguments, 'action arguments');
		if (Object.hasOwn(args, 'type')) throw codedError('INVALID_ACTION', 'Action arguments cannot override action type');
		const { type: actionType, ...argumentsValue } = validateAction({ ...args, type: source.actionType });
		const entry = { ...context, actionId, actionType, arguments: argumentsValue };
		const dispatches = this.#dispatches.get(agentId) ?? new Map();
		const previous = dispatches.get(actionId);
		if (previous !== undefined) {
			if (JSON.stringify(previous.entry) !== JSON.stringify(entry)) throw codedError('RECEIPT_CONFLICT', 'Action identity is already dispatched in another context');
			return previous.pending;
		}
		if (dispatches.size >= 256) throw codedError('RECEIPT_LIMIT', 'Too many unreconciled actions');
		const pending = this.#notebook.recordDispatch(agentId, entry);
		dispatches.set(actionId, { entry, pending });
		this.#dispatches.set(agentId, dispatches);
		try { return await pending; }
		catch (error) { dispatches.delete(actionId); throw error; }
	}

	async recordResult(record, payload) {
		const { agentId } = recordIdentity(record);
		const source = ownRecord(payload, 'action result');
		if (!TERMINAL_STATES.has(source.state)) return false;
		const actionId = boundedText(source.actionId, 'actionId', 256);
		const active = this.#dispatches.get(agentId)?.get(actionId);
		if (active !== undefined) await active.pending;
		const previous = active?.entry ?? await this.#notebook.findReceipt(agentId, { actionId });
		if (previous === null || previous === undefined) return false;
		if (source.goalRevision !== undefined && source.goalRevision !== previous.goalRevision) return false;
		const observation = source.actionObservation === undefined ? {} : ownRecord(source.actionObservation, 'action observation');
		if (typeof source.reasonCode !== 'string' || source.reasonCode.length > 128) throw new TypeError('reasonCode must be text up to 128 characters');
		const terminal = { worldId: previous.worldId, actionId, state: source.state, reasonCode: source.reasonCode,
			...pick(previous, ['goalRevision', 'actionType', 'dimension', 'arguments']),
			...pick(source, ['executionStarted', 'physicalAttempted']),
			...(source.actionObservation === undefined ? {} : { actionObservation: observation }),
			...(Number.isSafeInteger(observation.worldTick) && observation.worldTick >= 0 ? { tick: observation.worldTick } : {}),
		};
		await this.#notebook.recordReceipt(agentId, terminal);
		this.#dispatches.get(agentId)?.delete(actionId);
		return true;
	}

	async markUnknown(agentId = undefined, reason = 'RUNTIME_DISCONNECTED') {
		const reasonCode = boundedText(reason, 'reasonCode', 128);
		const agents = agentId === undefined ? [...this.#dispatches.keys()] : [boundedText(agentId, 'agentId', 256)];
		const results = [];
		for (const id of agents) {
			const dispatches = this.#dispatches.get(id);
			for (const active of [...dispatches?.values() ?? []]) {
				const { entry, pending } = active;
				await pending;
				results.push(await this.#notebook.recordUnknown(id, { ...entry, reasonCode }));
				if (dispatches.get(entry.actionId) === active) dispatches.delete(entry.actionId);
			}
		}
		return results;
	}
}

function noteAuthor(record, value) {
	const source = ownRecord(value, 'note provenance');
	const provenance = { goalRevision: record.goalRevision };
	for (const key of AUTHOR_FIELDS) {
		const expected = record[key] ?? (key === 'serviceTier' ? 'priority' : undefined);
		if (source[key] !== expected) throw codedError('INVALID_MEMORY_PROVENANCE', 'Note author does not match the active model');
		provenance[key] = boundedText(source[key], key, 256);
	}
	if (source.goalRevision !== record.goalRevision) throw codedError('INVALID_MEMORY_PROVENANCE', 'Note author does not match the active goal');
	for (const key of AUTHOR_IDS) if (source[key] !== undefined) provenance[key] = boundedText(source[key], key, 256);
	if (source.programVersion !== undefined) provenance.programVersion = nonnegativeInteger(source.programVersion, 'programVersion');
	return provenance;
}
function recordIdentity(record) {
	const source = ownRecord(record, 'agent record');
	return { agentId: boundedText(source.agentId, 'agentId', 256), goalRevision: nonnegativeInteger(source.goalRevision, 'goalRevision') };
}
function ownRecord(value, label) {
	if (value === null || typeof value !== 'object' || Array.isArray(value) || nodeTypes.isProxy(value) || ![null, Object.prototype].includes(Object.getPrototypeOf(value))) throw new TypeError(`${label} must be a plain record`);
	const result = {};
	for (const key of Reflect.ownKeys(value)) {
		const descriptor = Object.getOwnPropertyDescriptor(value, key);
		if (typeof key !== 'string' || ['__proto__', 'constructor', 'prototype'].includes(key) || !descriptor?.enumerable || !Object.hasOwn(descriptor, 'value')) throw new TypeError(`${label} must contain only own data`);
		result[key] = descriptor.value;
	}
	return result;
}
function boundedText(value, field, maximum) {
	if (typeof value !== 'string' || value.trim().length === 0 || value.length > maximum) throw new TypeError(`${field} must be nonblank text up to ${maximum} characters`);
	return value;
}
function nonnegativeInteger(value, field) {
	if (!Number.isSafeInteger(value) || value < 0) throw new TypeError(`${field} must be a nonnegative integer`);
	return value;
}
function pick(source, keys) { return Object.fromEntries(keys.filter((key) => source[key] !== undefined).map((key) => [key, source[key]])); }
function codedError(code, message) { return Object.assign(new Error(message), { code }); }
