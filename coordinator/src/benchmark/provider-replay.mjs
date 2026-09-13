import { createHash } from 'node:crypto';

import { parseDecision } from '../decision-parser.mjs';

export const REPLAY_PROTOCOL_VERSION = 2;
const MAX_ID_LENGTH = 128;
const MAX_RECORDINGS = 65_536;
const MAX_TURNS = 128;
const MAX_REPLAY_DELAY_MS = 300_000;
const MAX_DEPTH = 8;
const MAX_NODES = 8_192;
const MAX_KEYS = 128;
const MAX_ARRAY_ITEMS = 4_096;
const MAX_STRING_BYTES = 65_536;
const MAX_CANONICAL_BYTES = 1_048_576;
const FORBIDDEN_KEYS = new Set(['__proto__', 'prototype', 'constructor']);

/** Canonical, bounded identity hashing. Prompts are hashed and never retained. */
export function hashIdentity(value) {
	return `sha256:${createHash('sha256').update(canonicalJson(value), 'utf8').digest('hex')}`;
}

export function canonicalJson(value) {
	const json = JSON.stringify(canonicalize(value));
	if (Buffer.byteLength(json, 'utf8') > MAX_CANONICAL_BYTES) throw codedError('REPLAY_VALUE_TOO_LARGE', 'replay identity exceeds the bounded byte limit');
	return json;
}

export function canonicalize(value) {
	return canonicalValue(value, { nodes: 0, stack: new WeakSet() }, 0);
}

/** Remove executable scenario predicates while retaining the authoritative data identity. */
export function projectScenarioIdentity(value) {
	return projectScenarioValue(value, { nodes: 0, stack: new WeakSet() }, 0, 'scenario');
}

/** Normalize and validate the trusted planner decision envelope. */
export function normalizeDecision(value) {
	const parsed = typeof value === 'string' ? parseDecision(value) : parseDecision(canonicalJson(value));
	return deepFreeze(structuredClone(parsed));
}

export function decisionHash(decision) {
	return hashIdentity(normalizeDecision(decision));
}

export function createReplayRecord({
	trialId,
	agentId,
	agentLoad,
	prompt,
	prompts,
	providerProfile,
	scenario,
	protocolVersion = 2,
	decision,
	decisions,
	delayMs,
	delaysMs,
	replayVersion = REPLAY_PROTOCOL_VERSION,
} = {}) {
	const id = requireIdentifier(trialId, 'trialId');
	const selectedAgentId = agentId === undefined ? null : requireIdentifier(agentId, 'agentId');
	const selectedAgentLoad = agentLoad === undefined ? null : positiveInteger(agentLoad, 'agentLoad');
	const selectedPrompts = normalizePrompts(prompts ?? [prompt]);
	const selectedDecisions = normalizeDecisions(decisions ?? [decision]);
	if (selectedPrompts.length !== selectedDecisions.length) throw codedError('REPLAY_TURN_COUNT_MISMATCH', 'replay prompts and decisions must contain the same number of turns');
	const selectedDelays = normalizeDelays(delaysMs ?? [delayMs ?? 0], selectedDecisions.length);
	const selectedProtocolVersion = positiveInteger(protocolVersion, 'protocolVersion');
	const selectedReplayVersion = positiveInteger(replayVersion, 'replayVersion');
	const profile = normalizeProfile(providerProfile);
	const scenarioHash = hashIdentity(projectScenarioIdentity(scenario));
	const promptHashes = selectedPrompts.map(hashIdentity);
	const decisionHashes = selectedDecisions.map(decisionHash);
	return deepFreeze({
		replayVersion: selectedReplayVersion,
		trialId: id,
		...(selectedAgentId === null ? {} : { agentId: selectedAgentId }),
		...(selectedAgentLoad === null ? {} : { agentLoad: selectedAgentLoad }),
		protocolVersion: selectedProtocolVersion,
		promptHash: promptHashes[0],
		promptHashes,
		profileHash: hashIdentity(profile),
		scenarioHash,
		decisionHash: decisionHashes[0],
		decisionHashes,
		decision: selectedDecisions[0],
		decisions: selectedDecisions,
		delayMs: selectedDelays[0],
		delaysMs: selectedDelays,
		timingHash: hashIdentity(selectedDelays),
	});
}

export function verifyReplayDecision(recording, decision, turnIndex = 0) {
	const record = normalizeRecord(recording);
	if (!Number.isSafeInteger(turnIndex) || turnIndex < 0 || turnIndex >= record.decisions.length) throw codedError('REPLAY_EXHAUSTED', `Replay decision sequence for '${record.trialId}' is exhausted`);
	const normalized = normalizeDecision(decision);
	if (decisionHash(normalized) !== record.decisionHashes[turnIndex] || canonicalJson(normalized) !== canonicalJson(record.decisions[turnIndex])) {
		throw codedError('REPLAY_DECISION_MISMATCH', `Replay decision for '${record.trialId}' does not match recorded turn ${turnIndex + 1}`);
	}
	return true;
}

/** Bounded provider adapter that returns only exact recorded decisions. */
export class ReplayProvider {
	#records;
	#trialId;
	#prompt;
	#providerProfile;
	#scenarioHash;
	#protocolVersion;
	#agentLoad;
	#sleep;
	#sessions = new Map();
	#stopped = false;
	calls = 0;

	constructor({ recordings, recording, trialId, prompt, providerProfile, scenario, agentLoad, protocolVersion = 2, sleep = delay } = {}) {
		const values = recording === undefined ? recordings : [recording];
		if (!Array.isArray(values) || values.length === 0 || values.length > MAX_RECORDINGS) throw new TypeError('replay recordings must be a non-empty bounded array');
		this.#records = values.map(normalizeRecord);
		const keys = this.#records.map((entry) => `${entry.trialId}\u0000${entry.agentId ?? '*'}`);
		if (new Set(keys).size !== keys.length) throw codedError('DUPLICATE_REPLAY_RECORD', 'replay recordings contain duplicate trial and agent identities');
		this.#trialId = requireIdentifier(trialId, 'trialId');
		this.#prompt = requirePrompt(prompt);
		this.#providerProfile = normalizeProfile(providerProfile);
		this.#scenarioHash = hashIdentity(projectScenarioIdentity(scenario));
		this.#protocolVersion = positiveInteger(protocolVersion, 'protocolVersion');
		this.#agentLoad = agentLoad === undefined ? null : positiveInteger(agentLoad, 'agentLoad');
		if (typeof sleep !== 'function') throw new TypeError('replay sleep must be a function');
		this.#sleep = sleep;
		this.available = true;
		this.synthetic = true;
	}

	get provider() { return this.#providerProfile.provider; }
	get model() { return this.#providerProfile.model; }
	get recording() {
		const candidates = this.#records.filter((entry) => entry.trialId === this.#trialId);
		return candidates.length === 1 ? candidates[0] : null;
	}

	async start() {
		if (this.#stopped) throw codedError('PROVIDER_STOPPED', 'replay provider is stopped');
	}

	async createAgent(profileValue) {
		if (this.#stopped) throw codedError('PROVIDER_STOPPED', 'replay provider is stopped');
		const profile = normalizeProfile(profileValue);
		if (hashIdentity(profile) !== hashIdentity(this.#providerProfile)) throw codedError('REPLAY_IDENTITY_MISMATCH', 'replay provider profile does not match the recording');
		const agentId = requireIdentifier(profileValue?.agentId, 'agentId');
		const requestedLoad = profileValue?.agentLoad ?? this.#agentLoad;
		if (requestedLoad !== null && requestedLoad !== undefined) positiveInteger(requestedLoad, 'agentLoad');
		let session = this.#sessions.get(agentId);
		if (session !== undefined) return session;
		const record = this.#selectRecord(agentId);
		this.#assertStaticIdentity(record, requestedLoad);
		let turn = 0;
		session = {
			goalRevision: 0,
			async setGoalRevision(revision) {
				if (!Number.isSafeInteger(revision) || revision < 0) throw new TypeError('goalRevision must be a nonnegative safe integer');
				this.goalRevision = revision;
			},
			decide: async (input, options = {}) => {
				if (options.signal?.aborted) throw options.signal.reason ?? codedError('PLAN_CANCELLED', 'replay decision was cancelled');
				const legacyRepeat = record.decisions.length === 1 && record.promptHashes.length === 1 && record.agentId === null;
				const index = legacyRepeat ? 0 : turn;
				if (index >= record.decisions.length) throw codedError('REPLAY_EXHAUSTED', `Replay decision sequence for '${record.trialId}/${agentId}' is exhausted`);
				const actualPromptHash = hashIdentity(requirePrompt(input ?? this.#prompt));
				if (actualPromptHash !== record.promptHashes[index]) throw codedError('REPLAY_PROMPT_MISMATCH', `Replay prompt for '${record.trialId}/${agentId}' does not match recorded turn ${index + 1}`);
				await this.#sleep(record.delaysMs[index], options.signal);
				if (options.signal?.aborted) throw options.signal.reason ?? codedError('PLAN_CANCELLED', 'replay decision was cancelled');
				if (!legacyRepeat) turn += 1;
				this.calls += 1;
				return structuredClone(record.decisions[index]);
			},
			interrupt: async () => {},
		};
		this.#sessions.set(agentId, session);
		return session;
	}

	getAgent(agentId) { return this.#sessions.get(agentId) ?? null; }
	async removeAgent(agentId) { return this.#sessions.delete(agentId); }
	async stop() { this.#stopped = true; this.#sessions.clear(); }

	#selectRecord(agentId) {
		const candidates = this.#records.filter((entry) => entry.trialId === this.#trialId);
		if (candidates.length === 0) throw codedError('REPLAY_IDENTITY_MISMATCH', `replay trial '${this.#trialId}' does not match any recording`);
		const exact = candidates.find((entry) => entry.agentId === agentId);
		const fallback = candidates.find((entry) => entry.agentId === null);
		const record = exact ?? fallback;
		if (record === undefined) throw codedError('REPLAY_RECORD_NOT_FOUND', `no replay recording exists for '${this.#trialId}/${agentId}'`);
		return record;
	}

	#assertStaticIdentity(record, requestedLoad) {
		if (record.trialId !== this.#trialId || record.protocolVersion !== this.#protocolVersion
			|| record.profileHash !== hashIdentity(this.#providerProfile) || record.scenarioHash !== this.#scenarioHash) {
			throw codedError('REPLAY_IDENTITY_MISMATCH', `replay identity for '${this.#trialId}' does not match the recording`);
		}
		if (record.agentLoad !== null && requestedLoad !== null && requestedLoad !== undefined && record.agentLoad !== requestedLoad) {
			throw codedError('REPLAY_IDENTITY_MISMATCH', `replay load for '${this.#trialId}' does not match the recording`);
		}
		if (record.agentId === null && hashIdentity(this.#prompt) !== record.promptHashes[0]) {
			throw codedError('REPLAY_IDENTITY_MISMATCH', `replay configured prompt for '${this.#trialId}' does not match the recording`);
		}
	}
}

export function createReplayProvider(options) { return new ReplayProvider(options); }

function normalizeRecord(value) {
	const source = requirePlainDataRecord(value, 'replay recording');
	const trialId = requireIdentifier(source.trialId, 'recording.trialId');
	const agentId = source.agentId === undefined ? null : requireIdentifier(source.agentId, 'recording.agentId');
	const agentLoad = source.agentLoad === undefined ? null : positiveInteger(source.agentLoad, 'recording.agentLoad');
	const protocolVersion = positiveInteger(source.protocolVersion, 'recording.protocolVersion');
	const replayVersion = positiveInteger(source.replayVersion ?? 1, 'recording.replayVersion');
	const promptHashes = normalizeHashes(source.promptHashes ?? [source.promptHash], 'recording.promptHashes');
	const decisions = normalizeDecisions(source.decisions ?? [source.decision]);
	const decisionHashes = normalizeHashes(source.decisionHashes ?? [source.decisionHash], 'recording.decisionHashes');
	const delays = normalizeDelays(source.delaysMs ?? [source.delayMs ?? 0], decisions.length);
	const timingHash = source.timingHash === undefined ? hashIdentity(delays) : requireHash(source.timingHash, 'recording.timingHash');
	if (timingHash !== hashIdentity(delays)) throw codedError('REPLAY_RECORD_INVALID', 'recorded timing hash does not match the delay sequence');
	if (promptHashes.length !== decisions.length || decisionHashes.length !== decisions.length) throw codedError('REPLAY_RECORD_INVALID', 'recorded prompt and decision sequences must have equal lengths');
	for (let index = 0; index < decisions.length; index += 1) {
		if (decisionHash(decisions[index]) !== decisionHashes[index]) throw codedError('REPLAY_RECORD_INVALID', `recorded decision hash does not match turn ${index + 1}`);
	}
	for (const key of ['profileHash', 'scenarioHash']) requireHash(source[key], `recording.${key}`);
	return deepFreeze({
		replayVersion, trialId, agentId, agentLoad, protocolVersion,
		promptHash: promptHashes[0], promptHashes,
		profileHash: source.profileHash, scenarioHash: source.scenarioHash,
		decisionHash: decisionHashes[0], decisionHashes,
		decision: decisions[0], decisions,
		delayMs: delays[0], delaysMs: delays,
		timingHash,
	});
}

function normalizeDecisions(value) {
	if (!Array.isArray(value) || value.length === 0 || value.length > MAX_TURNS) throw new TypeError(`replay decisions must contain 1-${MAX_TURNS} turns`);
	return value.map(normalizeDecision);
}

function normalizePrompts(value) {
	if (!Array.isArray(value) || value.length === 0 || value.length > MAX_TURNS) throw new TypeError(`replay prompts must contain 1-${MAX_TURNS} turns`);
	return value.map(requirePrompt);
}

function normalizeDelays(value, turnCount) {
	if (!Array.isArray(value) || value.length === 0 || value.length > MAX_TURNS) throw new TypeError(`replay delays must contain 1-${MAX_TURNS} turns`);
	const normalized = value.map((entry, index) => {
		if (!Number.isFinite(entry) || entry < 0 || entry > MAX_REPLAY_DELAY_MS) throw new TypeError(`replay delay ${index + 1} must be finite and in [0, ${MAX_REPLAY_DELAY_MS}]`);
		return entry;
	});
	if (normalized.length === 1 && turnCount > 1 && normalized[0] === 0) return Array(turnCount).fill(0);
	if (normalized.length !== turnCount) throw codedError('REPLAY_TURN_COUNT_MISMATCH', 'replay delays and decisions must contain the same number of turns');
	return normalized;
}

function normalizeHashes(value, field) {
	if (!Array.isArray(value) || value.length === 0 || value.length > MAX_TURNS) throw new TypeError(`${field} must be a bounded non-empty array`);
	return value.map((entry, index) => requireHash(entry, `${field}[${index}]`));
}

function normalizeProfile(value) {
	const source = requirePlainDataRecord(value, 'providerProfile');
	const profile = Object.create(null);
	for (const key of ['provider', 'model', 'reasoningEffort', 'serviceTier']) profile[key] = requireIdentifier(source[key], `providerProfile.${key}`);
	return profile;
}

function canonicalValue(value, context, depth) {
	countNode(context);
	if (depth > MAX_DEPTH) throw codedError('REPLAY_VALUE_TOO_DEEP', 'replay identity exceeds the maximum depth');
	if (value === null || typeof value === 'boolean') return value;
	if (typeof value === 'string') { requireBoundedString(value); return value; }
	if (typeof value === 'number') {
		if (!Number.isFinite(value)) throw codedError('REPLAY_INVALID_VALUE', 'replay identity contains a non-finite number');
		return Object.is(value, -0) ? 0 : value;
	}
	if (typeof value !== 'object') throw codedError('REPLAY_INVALID_VALUE', 'replay identity contains an unsupported value');
	if (context.stack.has(value)) throw codedError('REPLAY_CYCLE', 'replay identity contains a cycle');
	context.stack.add(value);
	try {
		if (Array.isArray(value)) {
			if (value.length > MAX_ARRAY_ITEMS) throw codedError('REPLAY_ARRAY_TOO_LARGE', 'replay identity array exceeds the bounded item limit');
			const descriptors = safeDescriptors(value);
			const symbolCount = safeSymbols(value).length;
			if (symbolCount > 0) throw codedError('REPLAY_INVALID_VALUE', 'replay identity arrays cannot contain symbol properties');
			const allowedKeys = new Set(['length', ...Array.from({ length: value.length }, (_, index) => String(index))]);
			if (Object.keys(descriptors).some((key) => !allowedKeys.has(key))) throw codedError('REPLAY_INVALID_KEY', 'replay identity arrays cannot contain custom properties');
			for (let index = 0; index < value.length; index += 1) {
				const descriptor = descriptors[String(index)];
				if (descriptor === undefined || !Object.hasOwn(descriptor, 'value')) throw codedError('REPLAY_ACCESSOR_REJECTED', 'replay identity arrays must contain own data elements');
			}
			return Array.from({ length: value.length }, (_, index) => canonicalValue(descriptors[String(index)].value, context, depth + 1));
		}
		const descriptors = plainDescriptors(value);
		const keys = Object.keys(descriptors).filter((key) => descriptors[key].enumerable);
		if (keys.length > MAX_KEYS) throw codedError('REPLAY_TOO_MANY_KEYS', 'replay identity object exceeds the bounded key limit');
		const output = Object.create(null);
		for (const key of keys.sort()) {
			requireSafeKey(key);
			const descriptor = descriptors[key];
			if (!Object.hasOwn(descriptor, 'value')) throw codedError('REPLAY_ACCESSOR_REJECTED', 'replay identity cannot contain accessors');
			output[key] = canonicalValue(descriptor.value, context, depth + 1);
		}
		return output;
	} finally {
		context.stack.delete(value);
	}
}

function projectScenarioValue(value, context, depth, field) {
	countNode(context);
	if (depth > MAX_DEPTH) throw codedError('REPLAY_VALUE_TOO_DEEP', `${field} exceeds the maximum depth`);
	if (value === null || typeof value === 'boolean' || typeof value === 'number' || typeof value === 'string') return canonicalValue(value, { nodes: context.nodes - 1, stack: context.stack }, depth);
	if (typeof value === 'function' || typeof value === 'undefined') return undefined;
	if (typeof value !== 'object') throw codedError('REPLAY_INVALID_VALUE', `${field} contains an unsupported value`);
	if (context.stack.has(value)) throw codedError('REPLAY_CYCLE', `${field} contains a cycle`);
	context.stack.add(value);
	try {
		if (Array.isArray(value)) {
			if (value.length > MAX_ARRAY_ITEMS) throw codedError('REPLAY_ARRAY_TOO_LARGE', `${field} exceeds the bounded item limit`);
			const descriptors = safeDescriptors(value);
			const allowedKeys = new Set(['length', ...Array.from({ length: value.length }, (_, index) => String(index))]);
			if (Object.keys(descriptors).some((key) => !allowedKeys.has(key)) || safeSymbols(value).length > 0) throw codedError('REPLAY_INVALID_KEY', `${field} arrays cannot contain custom properties`);
			const output = [];
			for (let index = 0; index < value.length; index += 1) {
				const descriptor = descriptors[String(index)];
				if (descriptor === undefined || !Object.hasOwn(descriptor, 'value')) throw codedError('REPLAY_ACCESSOR_REJECTED', `${field} cannot contain accessors or sparse items`);
				const projected = projectScenarioValue(descriptor.value, context, depth + 1, `${field}[${index}]`);
				if (projected === undefined) throw codedError('REPLAY_INVALID_VALUE', `${field} arrays cannot contain executable values`);
				output.push(projected);
			}
			return output;
		}
		const descriptors = plainDescriptors(value);
		const keys = Object.keys(descriptors).filter((key) => descriptors[key].enumerable);
		if (keys.length > MAX_KEYS) throw codedError('REPLAY_TOO_MANY_KEYS', `${field} exceeds the bounded key limit`);
		const output = Object.create(null);
		for (const key of keys.sort()) {
			requireSafeKey(key);
			const descriptor = descriptors[key];
			if (!Object.hasOwn(descriptor, 'value')) throw codedError('REPLAY_ACCESSOR_REJECTED', `${field} cannot contain accessors`);
			const projected = projectScenarioValue(descriptor.value, context, depth + 1, `${field}.${key}`);
			if (projected !== undefined) output[key] = projected;
		}
		return output;
	} finally {
		context.stack.delete(value);
	}
}

function safeDescriptors(value) {
	try { return Object.getOwnPropertyDescriptors(value); }
	catch { throw codedError('REPLAY_UNSAFE_OBJECT', 'replay identity object could not be inspected safely'); }
}

function safeSymbols(value) {
	try { return Object.getOwnPropertySymbols(value); }
	catch { throw codedError('REPLAY_UNSAFE_OBJECT', 'replay identity object could not be inspected safely'); }
}

function plainDescriptors(value) {
	let prototype;
	try { prototype = Object.getPrototypeOf(value); }
	catch { throw codedError('REPLAY_UNSAFE_OBJECT', 'replay identity object could not be inspected safely'); }
	if (prototype !== Object.prototype && prototype !== null) throw codedError('REPLAY_UNSAFE_OBJECT', 'replay identity must contain only plain objects');
	if (safeSymbols(value).length > 0) throw codedError('REPLAY_INVALID_VALUE', 'replay identity cannot contain symbol properties');
	const descriptors = safeDescriptors(value);
	for (const [key, descriptor] of Object.entries(descriptors)) {
		requireSafeKey(key);
		if (!descriptor.enumerable) throw codedError('REPLAY_INVALID_VALUE', 'replay identity objects cannot hide non-enumerable properties');
	}
	return descriptors;
}

function requirePlainDataRecord(value, field) {
	if (value === null || typeof value !== 'object' || Array.isArray(value)) throw new TypeError(`${field} must be an object`);
	const descriptors = plainDescriptors(value);
	for (const descriptor of Object.values(descriptors)) if (descriptor.enumerable && !Object.hasOwn(descriptor, 'value')) throw codedError('REPLAY_ACCESSOR_REJECTED', `${field} cannot contain accessors`);
	return Object.fromEntries(Object.entries(descriptors).filter(([, descriptor]) => descriptor.enumerable).map(([key, descriptor]) => [key, descriptor.value]));
}

function requireSafeKey(key) {
	if (Buffer.byteLength(key, 'utf8') > MAX_ID_LENGTH || FORBIDDEN_KEYS.has(key)) throw codedError('REPLAY_INVALID_KEY', 'replay identity contains a forbidden or oversized key');
}

function requireBoundedString(value) {
	if (Buffer.byteLength(value, 'utf8') > MAX_STRING_BYTES) throw codedError('REPLAY_STRING_TOO_LARGE', 'replay identity string exceeds the bounded byte limit');
}

function requirePrompt(value) {
	if (typeof value !== 'string' || value.trim().length === 0) throw new TypeError('prompt must be nonblank');
	requireBoundedString(value);
	return value;
}

function requireIdentifier(value, field) {
	if (typeof value !== 'string' || value.trim().length === 0 || value.length > MAX_ID_LENGTH) throw new TypeError(`${field} must be nonblank and at most ${MAX_ID_LENGTH} characters`);
	return value.trim();
}

function requireHash(value, field) {
	if (typeof value !== 'string' || !/^sha256:[a-f0-9]{64}$/.test(value)) throw new TypeError(`${field} must be a sha256 hash`);
	return value;
}

function positiveInteger(value, field) {
	if (!Number.isSafeInteger(value) || value < 1) throw new TypeError(`${field} must be a positive safe integer`);
	return value;
}

function countNode(context) {
	context.nodes += 1;
	if (context.nodes > MAX_NODES) throw codedError('REPLAY_VALUE_TOO_LARGE', 'replay identity exceeds the bounded node limit');
}

function codedError(code, message) { return Object.assign(new Error(message), { code }); }

function delay(milliseconds, signal) {
	if (milliseconds === 0) return Promise.resolve();
	if (signal?.aborted) return Promise.reject(signal.reason ?? codedError('PLAN_CANCELLED', 'replay decision was cancelled'));
	return new Promise((resolve, reject) => {
		const complete = () => { signal?.removeEventListener('abort', abort); resolve(); };
		const handle = setTimeout(complete, milliseconds);
		const abort = () => { clearTimeout(handle); signal?.removeEventListener('abort', abort); reject(signal.reason ?? codedError('PLAN_CANCELLED', 'replay decision was cancelled')); };
		signal?.addEventListener('abort', abort, { once: true });
	});
}

function deepFreeze(value, seen = new WeakSet()) {
	if (value === null || typeof value !== 'object' || seen.has(value)) return value;
	seen.add(value);
	for (const child of Object.values(value)) deepFreeze(child, seen);
	return Object.freeze(value);
}
