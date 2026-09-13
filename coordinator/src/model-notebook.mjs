import { AtomicAgentStore } from './observed-memory-store.mjs';
import { normalizeProviderId, assertProviderServiceTier } from './provider-identity.mjs';
import { MAX_ACTION_ARGUMENT_BYTES, validateAction } from './schema.mjs';
import { isDeepStrictEqual, types as nodeTypes } from 'node:util';
const TERMINAL_STATES = new Set(['SUCCEEDED', 'FAILED', 'CANCELLED', 'TIMED_OUT']);

/** Model notes and server receipts remain distinct records; neither can assert current world truth. */
export class ModelNotebook {
	#disk; #agents = new Map(); #pending = new Map(); #loads = new Map(); #maximumNotes; #maximumReceipts; #maximumBytes;
	constructor({ directory = null, maximumNotes = 64, maximumReceipts = 128, maximumBytes = 3_500_000 } = {}) {
		for (const value of [maximumNotes, maximumReceipts]) if (!Number.isSafeInteger(value) || value < 1 || value > 1024) throw new TypeError('notebook bounds must be 1..1024');
		if (!Number.isSafeInteger(maximumBytes) || maximumBytes < 4096 || maximumBytes > 4_000_000) throw new TypeError('notebook byte budget must be 4096..4000000');
		this.#maximumNotes = maximumNotes; this.#maximumReceipts = maximumReceipts;
		this.#maximumBytes = maximumBytes;
		this.#disk = new AtomicAgentStore({ directory, namespace: 'notebook' });
	}
	writeNote(agentId, note) {
		const record = { kind: 'note', source: 'model_authored', worldId: text(note.worldId, 'worldId', 256), key: text(note.key, 'key', 128), text: text(note.text, 'text', 2048), ...optionalInteger(note, 'goalRevision'), ...noteProvenance(note) };
		return this.#mutate(agentId, (state) => {
			const index = state.notes.findIndex((entry) => entry.worldId === record.worldId && entry.key === record.key);
			if (index !== -1 && samePayload(state.notes[index], record)) return state.notes[index];
			const next = { ...record, revision: ++state.revision };
			if (index !== -1) state.notes.splice(index, 1);
			state.notes.push(next);
			return next;
		});
	}
	recordReceipt(agentId, receipt) {
		if (!TERMINAL_STATES.has(receipt.state)) throw new TypeError('Authoritative receipts require a terminal server action state');
		return this.#recordAction(agentId, actionRecord(receipt, 'server_action_result', receipt.state, receipt.reasonCode));
	}
	recordDispatch(agentId, receipt) {
		return this.#recordAction(agentId, actionRecord(receipt, 'coordinator_dispatch', 'DISPATCHED', 'AWAITING_AUTHORITATIVE_RESULT'));
	}
	recordUnknown(agentId, receipt) {
		return this.#recordAction(agentId, actionRecord(receipt, 'coordinator_uncertain', 'UNKNOWN', receipt.reasonCode ?? 'AUTHORITATIVE_RESULT_UNKNOWN'));
	}
	async findReceipt(agentId, { actionId, worldId } = {}) {
		actionId = text(actionId, 'actionId', 256);
		if (worldId !== undefined) worldId = text(worldId, 'worldId', 256);
		await this.#pending.get(agentId);
		const state = await this.#load(agentId);
		const matches = state.receipts.filter((entry) => entry.actionId === actionId && (worldId === undefined || entry.worldId === worldId));
		if (matches.length > 1) throw new Error('RECEIPT_WORLD_REQUIRED');
		return matches.length === 0 ? null : structuredClone(matches[0]);
	}
	listUnresolved(agentId, options) { return this.query(agentId, { ...options, kind: 'unresolved' }); }
	#recordAction(agentId, record) {
		return this.#mutate(agentId, (state) => {
			const index = state.receipts.findIndex((entry) => entry.worldId === record.worldId && entry.actionId === record.actionId);
			const existing = state.receipts[index];
			if (existing) {
				for (const field of ['actionType', 'goalRevision', 'dimension']) if (existing[field] !== undefined && record[field] !== undefined && existing[field] !== record[field]) throw new Error('RECEIPT_CONFLICT');
				if (existing.arguments !== undefined && record.arguments !== undefined && !isDeepStrictEqual(existing.arguments, record.arguments)) throw new Error('RECEIPT_CONFLICT');
				if (existing.source === 'server_action_result') {
					if (record.source !== 'server_action_result') return existing;
					if (existing.state !== record.state || existing.reasonCode !== record.reasonCode) throw new Error('RECEIPT_CONFLICT');
					for (const field of ['executionStarted', 'physicalAttempted', 'actionObservation']) if (existing[field] !== undefined && record[field] !== undefined && !isDeepStrictEqual(existing[field], record[field])) throw new Error('RECEIPT_CONFLICT');
					if (['arguments', 'executionStarted', 'physicalAttempted', 'actionObservation'].every((field) => existing[field] !== undefined || record[field] === undefined)) return existing;
				}
				if (existing.source === 'coordinator_uncertain' && record.source === 'coordinator_dispatch') return existing;
				const { revision: _revision, ...merged } = { ...existing, ...record };
				if (samePayload(existing, merged)) return existing;
			}
			const next = { ...existing, ...record, revision: ++state.revision };
			if (index !== -1) state.receipts.splice(index, 1);
			state.receipts.push(next);
			return next;
		});
	}
	async query(agentId, { worldId, kind = 'all', text: search = '', limit = 20, offset = 0 } = {}) {
		worldId = text(worldId, 'worldId', 256);
		if (!['all', 'note', 'receipt', 'notes', 'receipts', 'unresolved'].includes(kind)) throw new TypeError('kind must be all, note, receipt or unresolved');
		if (typeof search !== 'string' || search.length > 256) throw new TypeError('query text must be at most 256 characters');
		if (!Number.isSafeInteger(limit) || limit < 1 || limit > 64 || !Number.isSafeInteger(offset) || offset < 0) throw new TypeError('invalid query page');
		await this.#pending.get(agentId);
		const state = await this.#load(agentId);
		const normalizedKind = kind.replace(/s$/, '');
		const matchesKind = (entry) => kind === 'all' || (kind === 'unresolved'
			? entry.kind === 'receipt' && entry.source !== 'server_action_result'
			: entry.kind === normalizedKind);
		const searchText = search.toLowerCase();
		const entries = [...state.notes, ...state.receipts].filter((entry) => entry.worldId === worldId && matchesKind(entry) && JSON.stringify(entry).toLowerCase().includes(searchText)).sort((left, right) => right.revision - left.revision);
		return { worldId, revision: state.revision, total: entries.length, entries: structuredClone(entries.slice(offset, offset + limit)), nextOffset: offset + limit < entries.length ? offset + limit : null, evictedReceipts: state.evictedReceipts, evictedNotes: state.evictedNotes };
	}
	clear(agentId, { worldId, key } = {}) {
		worldId = text(worldId, 'worldId', 256);
		if (key !== undefined) key = text(key, 'key', 128);
		return this.#mutate(agentId, (state) => {
			const before = state.notes.length;
			state.notes = state.notes.filter((entry) => entry.worldId !== worldId || key !== undefined && entry.key !== key);
			const removed = before - state.notes.length;
			if (removed > 0) state.revision++;
			return { removed, revision: state.revision };
		});
	}
	#mutate(agentId, operation) {
		const previous = this.#pending.get(agentId) ?? Promise.resolve();
		const pending = previous.catch(() => {}).then(async () => {
			const state = structuredClone(await this.#load(agentId));
			const result = operation(state);
			this.#bound(state, result);
			await this.#disk.write(agentId, { ...state, notes: state.notes.filter(persistentWorld), receipts: state.receipts.filter(persistentWorld) });
			this.#agents.set(agentId, state);
			return structuredClone(result);
		});
		this.#pending.set(agentId, pending);
		return pending.finally(() => { if (this.#pending.get(agentId) === pending) this.#pending.delete(agentId); });
	}
	async #load(agentId) {
		if (this.#agents.has(agentId)) return this.#agents.get(agentId);
		let pending = this.#loads.get(agentId);
		if (!pending) {
			pending = this.#disk.read(agentId).then((saved) => {
				const state = saved === null ? { version: 1, revision: 0, notes: [], receipts: [], evictedReceipts: 0, evictedNotes: 0 } : validateSaved(saved);
				this.#bound(state);
				this.#agents.set(agentId, state);
				return state;
			});
			this.#loads.set(agentId, pending);
		}
		try { return await pending; } finally { this.#loads.delete(agentId); }
	}
	#bound(state, retained = null) {
		while (state.notes.length > this.#maximumNotes) { state.notes.shift(); state.evictedNotes++; }
		while (state.receipts.length > this.#maximumReceipts) { state.receipts.shift(); state.evictedReceipts++; }
		while (Buffer.byteLength(JSON.stringify(state), 'utf8') > this.#maximumBytes) {
			const receipt = state.receipts.find((entry) => entry !== retained);
			const note = state.notes.find((entry) => entry !== retained);
			if (receipt && (!note || receipt.revision <= note.revision)) {
				state.receipts.splice(state.receipts.indexOf(receipt), 1); state.evictedReceipts++;
			} else if (note) {
				state.notes.splice(state.notes.indexOf(note), 1); state.evictedNotes++;
			} else throw new Error('RECORD_EXCEEDS_NOTEBOOK_BUDGET');
		}
	}
}

function persistentWorld(entry) { return !entry.worldId.startsWith('session:'); }
function samePayload(existing, next) { const { revision: _revision, ...payload } = existing; return isDeepStrictEqual(payload, next); }
function text(value, field, maximum) {
	if (typeof value !== 'string' || value.trim().length === 0 || value.length > maximum) throw new TypeError(`${field} must be nonblank text up to ${maximum} characters`);
	return value;
}
function optionalText(source, field, maximum) { return source[field] === undefined ? {} : { [field]: text(source[field], field, maximum) }; }
function optionalInteger(source, field) {
	if (source[field] === undefined) return {};
	if (!Number.isSafeInteger(source[field]) || source[field] < 0) throw new TypeError(`${field} must be a nonnegative integer`);
	return { [field]: source[field] };
}
function actionRecord(receipt, source, state, reasonCode) {
	return {
		kind: 'receipt', source, worldId: text(receipt.worldId, 'worldId', 256),
		actionId: text(receipt.actionId, 'actionId', 256), state: text(state, 'state', 64), reasonCode: reasonText(reasonCode),
		...optionalText(receipt, 'actionType', 128), ...optionalText(receipt, 'dimension', 128),
		...optionalInteger(receipt, 'goalRevision'), ...optionalInteger(receipt, 'tick'),
		...actionEvidence(receipt, source),
	};
}
function actionEvidence(receipt, source) {
	const evidence = {};
	if (receipt.arguments !== undefined) {
		const args = boundedOwnJson(receipt.arguments, MAX_ACTION_ARGUMENT_BYTES);
		if (args === null || typeof args !== 'object' || Array.isArray(args) || Object.hasOwn(args, 'type')) throw new TypeError('Action arguments must be a record without type');
		const { type: _type, ...validated } = validateAction({ ...args, type: receipt.actionType });
		evidence.arguments = validated;
	}
	for (const field of ['executionStarted', 'physicalAttempted']) if (receipt[field] !== undefined) {
		if (source !== 'server_action_result') throw new TypeError('Only server results carry execution flags');
		if (typeof receipt[field] !== 'boolean') throw new TypeError(`${field} must be a boolean`);
		evidence[field] = receipt[field];
	}
	if (evidence.physicalAttempted === true && evidence.executionStarted !== true) throw new TypeError('physicalAttempted requires executionStarted');
	if (receipt.actionObservation !== undefined) {
		if (source !== 'server_action_result') throw new TypeError('Only server results carry action observations');
		evidence.actionObservation = actionObservation(receipt.actionObservation);
	}
	return evidence;
}
function actionObservation(value) {
	const source = boundedOwnJson(value, 16_384);
	if (source === null || typeof source !== 'object' || Array.isArray(source)) throw new TypeError('Action observation must be an object');
	const result = {};
	for (const field of ['worldTick', 'observedAtEpochMs', 'yaw', 'pitch']) if (source[field] !== undefined) {
		if (!Number.isFinite(source[field]) || ['worldTick', 'observedAtEpochMs'].includes(field) && (!Number.isSafeInteger(source[field]) || source[field] < 0)) throw new TypeError(`Invalid action observation ${field}`);
		result[field] = source[field];
	}
	const shapes = {
		position: { x: 'number', y: 'number', z: 'number' }, velocity: { x: 'number', y: 'number', z: 'number' },
		collision: { horizontal: 'boolean', vertical: 'boolean', inWall: 'boolean' },
		lookedAt: { type: 'string', id: 'string', face: 'string', hitDistance: 'number', position: 'vector' },
		reach: { distance: 'number', max: 'number', within: 'boolean' },
		target: { kind: 'string', position: 'vector', expectedId: 'string', currentId: 'string', beforeId: 'string', afterId: 'string', worldChanged: 'boolean', distanceRemaining: 'number', tolerance: 'number', standable: 'boolean' },
		progress: { value: 'number', basis: 'string', verified: 'boolean' },
	};
	for (const [field, shape] of Object.entries(shapes)) if (source[field] !== undefined) result[field] = projectEvidence(source[field], shape);
	return result;
}
function projectEvidence(source, shape) {
	if (source === null || typeof source !== 'object' || Array.isArray(source)) throw new TypeError('Action evidence must be an object');
	const result = {};
	for (const [field, type] of Object.entries(shape)) if (source[field] !== undefined) {
		if (type === 'vector') { result[field] = projectEvidence(source[field], { x: 'number', y: 'number', z: 'number' }); continue; }
		if (typeof source[field] !== type || type === 'number' && !Number.isFinite(source[field]) || type === 'string' && source[field].length > 256) throw new TypeError(`Invalid action evidence ${field}`);
		result[field] = source[field];
	}
	return result;
}
function boundedOwnJson(value, maximumBytes) {
	const visit = (item, depth) => {
		if (depth > 12) throw new TypeError('Receipt data is too deeply nested');
		if (item === null || typeof item === 'string' || typeof item === 'boolean' || typeof item === 'number' && Number.isFinite(item)) return;
		if (typeof item !== 'object' || nodeTypes.isProxy(item) || ![Object.prototype, Array.prototype, null].includes(Object.getPrototypeOf(item))) throw new TypeError('Receipt data must contain only JSON values');
		for (const key of Reflect.ownKeys(item)) {
			if (Array.isArray(item) && key === 'length') continue;
			const descriptor = Object.getOwnPropertyDescriptor(item, key);
			if (typeof key !== 'string' || ['__proto__', 'prototype', 'constructor'].includes(key) || !descriptor?.enumerable || !Object.hasOwn(descriptor, 'value')) throw new TypeError('Receipt data must contain only own data');
			visit(descriptor.value, depth + 1);
		}
	};
	visit(value, 0);
	const encoded = JSON.stringify(value);
	if (Buffer.byteLength(encoded, 'utf8') > maximumBytes) throw new TypeError('Receipt data exceeds byte limit');
	return JSON.parse(encoded);
}
function reasonText(value) {
	if (typeof value !== 'string' || value.length > 128) throw new TypeError('reasonCode must be text up to 128 characters');
	return value;
}
function noteProvenance(note) {
	if (note.provenance === undefined) return {};
	const source = boundedOwnJson(note.provenance, 8192);
	const required = ['provider', 'model', 'reasoningEffort', 'serviceTier', 'goalRevision'];
	const allowed = [...required, 'programId', 'programVersion', 'sourceStepId', 'turnId', 'callId'];
	if (source === null || typeof source !== 'object' || Array.isArray(source) || Object.keys(source).some((field) => !allowed.includes(field)) || required.some((field) => !Object.hasOwn(source, field))) throw new TypeError('Invalid note provenance fields');
	const provider = normalizeProviderId(source.provider);
	const provenance = { provider, model: text(source.model, 'model', 256), reasoningEffort: text(source.reasoningEffort, 'reasoningEffort', 64), serviceTier: assertProviderServiceTier(provider, source.serviceTier), ...optionalInteger(source, 'goalRevision') };
	if (note.goalRevision !== undefined && source.goalRevision !== note.goalRevision) throw new TypeError('Note provenance goalRevision must match the note');
	for (const field of ['programId', 'sourceStepId', 'turnId', 'callId']) Object.assign(provenance, optionalText(source, field, 256));
	Object.assign(provenance, optionalInteger(source, 'programVersion'));
	return { provenance };
}
function validateSaved(saved) {
	if (saved.version !== 1 || !Number.isSafeInteger(saved.revision) || saved.revision < 0 || !Array.isArray(saved.notes) || !Array.isArray(saved.receipts)) throw new Error('INVALID_NOTEBOOK');
	const notes = saved.notes.map((note) => ({ kind: 'note', source: 'model_authored', worldId: text(note.worldId, 'worldId', 256), key: text(note.key, 'key', 128), text: text(note.text, 'text', 2048), ...optionalInteger(note, 'goalRevision'), ...noteProvenance(note), ...optionalInteger(note, 'revision') }));
	const receipts = saved.receipts.map((receipt) => {
		const valid = receipt.source === 'server_action_result' && TERMINAL_STATES.has(receipt.state)
			|| receipt.source === 'coordinator_dispatch' && receipt.state === 'DISPATCHED'
			|| receipt.source === 'coordinator_uncertain' && receipt.state === 'UNKNOWN';
		if (!valid) throw new Error('INVALID_NOTEBOOK_RECEIPT');
		return { ...actionRecord(receipt, receipt.source, receipt.state, receipt.reasonCode), ...optionalInteger(receipt, 'revision') };
	});
	return { version: 1, revision: saved.revision, notes, receipts, evictedReceipts: Number.isSafeInteger(saved.evictedReceipts) && saved.evictedReceipts >= 0 ? saved.evictedReceipts : 0, evictedNotes: Number.isSafeInteger(saved.evictedNotes) && saved.evictedNotes >= 0 ? saved.evictedNotes : 0 };
}
