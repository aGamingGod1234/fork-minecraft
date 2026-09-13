import {
	DEFAULT_AGENT_CAP,
	DEFAULT_GOAL_QUEUE_CAP,
	MAX_GOAL_LENGTH,
	MAX_IDENTIFIER_LENGTH,
	MAX_REASON_CODE_LENGTH,
	MAX_RESULT_MESSAGE_LENGTH,
} from './constants.mjs';
import { normalizeProviderId } from './provider-identity.mjs';
import { REGISTERED_AGENT_SCHEMA_VERSION, validateRegisteredAgentContract } from './registered-agent-contract.mjs';

export const DynamicAgentState = Object.freeze({
	IDLE: 'IDLE',
	STARTING: 'STARTING',
	PLANNING: 'PLANNING',
	ACTING: 'ACTING',
	PAUSED: 'PAUSED',
	COMPLETED: 'COMPLETED',
	ERROR: 'ERROR',
	DEAD: 'DEAD',
	DISCONNECTED: 'DISCONNECTED',
});

const DYNAMIC_AGENT_STATES = new Set(Object.values(DynamicAgentState));
const DISCONNECT_ON_RELOAD = new Set([
	DynamicAgentState.STARTING,
	DynamicAgentState.PLANNING,
	DynamicAgentState.ACTING,
	DynamicAgentState.DISCONNECTED,
]);
const PROMOTION_SOURCE_STATES = new Set([
	DynamicAgentState.STARTING,
	DynamicAgentState.PLANNING,
	DynamicAgentState.ACTING,
]);
const GOAL_OPERATIONS = new Set(['start', 'replace', 'stop', 'queue', 'dequeue', 'steer', 'resume', 'complete', 'fail', 'disconnect', 'dead', 'respawn']);
const ALLOWED_STATE_TRANSITIONS = Object.freeze({
	[DynamicAgentState.IDLE]: new Set([DynamicAgentState.STARTING, DynamicAgentState.ERROR, DynamicAgentState.DEAD, DynamicAgentState.DISCONNECTED]),
	[DynamicAgentState.STARTING]: new Set([DynamicAgentState.PLANNING, DynamicAgentState.COMPLETED, DynamicAgentState.PAUSED, DynamicAgentState.ERROR, DynamicAgentState.DEAD, DynamicAgentState.DISCONNECTED]),
	[DynamicAgentState.PLANNING]: new Set([DynamicAgentState.ACTING, DynamicAgentState.COMPLETED, DynamicAgentState.PAUSED, DynamicAgentState.ERROR, DynamicAgentState.DEAD, DynamicAgentState.DISCONNECTED]),
	[DynamicAgentState.ACTING]: new Set([DynamicAgentState.PLANNING, DynamicAgentState.COMPLETED, DynamicAgentState.PAUSED, DynamicAgentState.ERROR, DynamicAgentState.DEAD, DynamicAgentState.DISCONNECTED]),
	[DynamicAgentState.PAUSED]: new Set([DynamicAgentState.STARTING, DynamicAgentState.IDLE, DynamicAgentState.ERROR, DynamicAgentState.DEAD, DynamicAgentState.DISCONNECTED]),
	[DynamicAgentState.COMPLETED]: new Set([DynamicAgentState.STARTING, DynamicAgentState.IDLE, DynamicAgentState.ERROR, DynamicAgentState.DEAD, DynamicAgentState.DISCONNECTED]),
	[DynamicAgentState.ERROR]: new Set([DynamicAgentState.STARTING, DynamicAgentState.PAUSED, DynamicAgentState.IDLE, DynamicAgentState.DEAD, DynamicAgentState.DISCONNECTED]),
	[DynamicAgentState.DEAD]: new Set([DynamicAgentState.IDLE, DynamicAgentState.PAUSED, DynamicAgentState.DISCONNECTED]),
	[DynamicAgentState.DISCONNECTED]: new Set([DynamicAgentState.STARTING, DynamicAgentState.PAUSED, DynamicAgentState.IDLE, DynamicAgentState.ERROR, DynamicAgentState.DEAD]),
});

export class AgentRegistryError extends Error {
	constructor(code, message, options) {
		super(message, options);
		this.name = 'AgentRegistryError';
		this.code = code;
	}
}

export class AgentRegistry {
	#agents = new Map();
	#agentCap;
	#queueCap;
	#now;

	constructor({ agentCap = DEFAULT_AGENT_CAP, queueCap = DEFAULT_GOAL_QUEUE_CAP, now = Date.now } = {}) {
		this.#agentCap = positiveInteger(agentCap, 'agentCap');
		this.#queueCap = positiveInteger(queueCap, 'queueCap');
		if (typeof now !== 'function') throw new TypeError('registry now dependency must be a function');
		this.#now = now;
	}

	get size() { return this.#agents.size; }
	get agentCap() { return this.#agentCap; }
	get queueCap() { return this.#queueCap; }

	has(agentId) {
		return this.#agents.has(agentId);
	}

	get(agentId) {
		const record = this.#agents.get(requireIdentifier(agentId, 'agentId'));
		return record === undefined ? null : clone(record);
	}

	list() {
		return [...this.#agents.values()].map(clone).sort((left, right) => left.agentId.localeCompare(right.agentId));
	}

	register(value) {
		const record = normalizeAgentRecord(value, { queueCap: this.#queueCap });
		const existing = this.#agents.get(record.agentId);
		if (existing === undefined && this.#agents.size >= this.#agentCap) {
			throw new AgentRegistryError('AGENT_CAP_REACHED', `Agent cap of ${this.#agentCap} has been reached`);
		}
		if (existing !== undefined && record.goalRevision < existing.goalRevision) {
			throw new AgentRegistryError('STALE_GOAL_REVISION', `Agent '${record.agentId}' registry revision moved backwards`);
		}
		this.#agents.set(record.agentId, record);
		return clone(record);
	}

	remove(agentId) {
		const id = requireIdentifier(agentId, 'agentId');
		const existing = this.#agents.get(id);
		if (existing === undefined) return null;
		this.#agents.delete(id);
		return clone(existing);
	}

	applyGoalControl(agentId, value) {
		const id = requireIdentifier(agentId, 'agentId');
		const current = this.#agents.get(id);
		if (current === undefined) throw new AgentRegistryError('UNKNOWN_AGENT', `Unknown agent '${id}'`);
		const updated = validateMutation(reduceGoalControl(current, value, { queueCap: this.#queueCap }));
		this.#agents.set(id, updated);
		return clone(updated);
	}

	applyConversationWake(agentId, value) {
		const id = requireIdentifier(agentId, 'agentId');
		const current = this.#agents.get(id);
		if (current === undefined) throw new AgentRegistryError('UNKNOWN_AGENT', `Unknown agent '${id}'`);
		if (!isPlainObject(value) || value.operation !== 'start') {
			throw new AgentRegistryError('INVALID_GOAL_OPERATION', 'Conversation wake requires a start control');
		}
		const revision = nonnegativeInteger(value.goalRevision, 'goalRevision');
		if (revision > current.goalRevision) {
			const updated = validateMutation(reduceGoalControl(current, value, { queueCap: this.#queueCap }));
			this.#agents.set(id, updated);
			return clone(updated);
		}
		if (revision < current.goalRevision) {
			throw new AgentRegistryError('STALE_GOAL_REVISION', `Conversation wake revision ${revision} is older than ${current.goalRevision}`);
		}
		const goal = requireGoal(value.goal);
		if (current.currentGoal !== goal) {
			throw new AgentRegistryError('GOAL_REVISION_COLLISION', 'Conversation wake revision belongs to a different goal');
		}
		if (![DynamicAgentState.STARTING, DynamicAgentState.PLANNING, DynamicAgentState.ACTING, DynamicAgentState.PAUSED, DynamicAgentState.DISCONNECTED].includes(current.state)) {
			throw new AgentRegistryError('INVALID_AGENT_STATE', `Conversation wake cannot re-arm ${current.state}`);
		}
		if ([DynamicAgentState.PAUSED, DynamicAgentState.DISCONNECTED].includes(current.state)) {
			const updated = validateMutation({
				...current,
				state: DynamicAgentState.STARTING,
				updatedAtEpochMs: nonnegativeInteger(value.updatedAtEpochMs ?? this.#now(), 'updatedAtEpochMs'),
				lastError: null,
			});
			this.#agents.set(id, updated);
			return clone(updated);
		}
		return clone(current);
	}

	setState(agentId, state, { goalRevision, error = null } = {}) {
		const id = requireIdentifier(agentId, 'agentId');
		const current = this.#agents.get(id);
		if (current === undefined) throw new AgentRegistryError('UNKNOWN_AGENT', `Unknown agent '${id}'`);
		if (!DYNAMIC_AGENT_STATES.has(state)) throw new AgentRegistryError('INVALID_AGENT_STATE', `Unsupported state '${String(state)}'`);
		if (goalRevision !== undefined) assertCurrentGoalRevision(current, goalRevision);
		if (state !== current.state && !ALLOWED_STATE_TRANSITIONS[current.state].has(state)) throw new AgentRegistryError('ILLEGAL_STATE_TRANSITION', `Agent cannot transition from ${current.state} to ${state}`);
		const updated = validateMutation({
			...current,
			state,
			lastError: normalizeError(error),
			updatedAtEpochMs: this.#now(),
		});
		this.#agents.set(id, updated);
		return clone(updated);
	}

	assertCurrentRevision(agentId, goalRevision) {
		const id = requireIdentifier(agentId, 'agentId');
		const current = this.#agents.get(id);
		if (current === undefined) throw new AgentRegistryError('UNKNOWN_AGENT', `Unknown agent '${id}'`);
		assertCurrentGoalRevision(current, goalRevision);
		return clone(current);
	}

	reconcile(snapshot, { recovery = false } = {}) {
		if (!Array.isArray(snapshot)) throw new TypeError('registry snapshot must be an array');
		if (typeof recovery !== 'boolean') throw new TypeError('registry recovery option must be a boolean');
		if (snapshot.length > this.#agentCap) throw new AgentRegistryError('AGENT_CAP_REACHED', `Registry snapshot exceeds agent cap of ${this.#agentCap}`);
		const next = new Map();
		for (const value of snapshot) {
			const record = normalizeAgentRecord(value, { queueCap: this.#queueCap, reload: true, recovery });
			if (next.has(record.agentId)) throw new AgentRegistryError('DUPLICATE_AGENT', `Duplicate agent '${record.agentId}' in registry snapshot`);
			next.set(record.agentId, record);
		}
		const removed = [...this.#agents.keys()].filter((agentId) => !next.has(agentId));
		const added = [...next.keys()].filter((agentId) => !this.#agents.has(agentId));
		const updated = [...next.keys()].filter((agentId) => this.#agents.has(agentId));
		this.#agents = next;
		return { added, updated, removed, records: this.list() };
	}

	snapshot() {
		return this.list();
	}
}

export function normalizeAgentRecord(value, { queueCap = DEFAULT_GOAL_QUEUE_CAP, reload = false, recovery = false } = {}) {
	if (!isPlainObject(value)) throw new TypeError('agent record must be an object');
	const state = requireState(value.state ?? DynamicAgentState.IDLE);
	const normalizedState = reload && DISCONNECT_ON_RELOAD.has(state)
		? (recovery ? DynamicAgentState.STARTING : DynamicAgentState.DISCONNECTED)
		: state;
	const goalRevision = nonnegativeInteger(value.goalRevision ?? 0, 'goalRevision');
	const queue = value.queue ?? [];
	if (!Array.isArray(queue)) throw new TypeError('agent queue must be an array');
	if (queue.length > queueCap) throw new AgentRegistryError('GOAL_QUEUE_FULL', `Agent goal queue exceeds ${queueCap} entries`);
	const provider = requireProvider(recovery ? value.provider : value.provider ?? 'codex');
	const serviceTier = requireIdentifier(recovery ? value.serviceTier : value.serviceTier ?? 'priority', 'serviceTier');
	const record = {
		schemaVersion: positiveInteger(value.schemaVersion ?? REGISTERED_AGENT_SCHEMA_VERSION, 'schemaVersion'),
		agentId: requireIdentifier(value.agentId, 'agentId'),
		entityUuid: optionalIdentifier(value.entityUuid, 'entityUuid'),
		name: optionalText(value.name, 'name', MAX_IDENTIFIER_LENGTH),
		provider,
		model: requireIdentifier(value.model, 'model'),
		reasoningEffort: requireIdentifier(value.reasoningEffort, 'reasoningEffort'),
		serviceTier,
		skinVariant: requireIdentifier(value.skinVariant ?? 'default', 'skinVariant'),
		state: normalizedState,
		currentGoal: optionalGoal(value.currentGoal),
		currentGoalSpec: value.currentGoalSpec === null || value.currentGoalSpec === undefined ? null : parseGoalSpec(value.currentGoalSpec),
		goalRevision,
		queue: queue.map((entry, index) => normalizeQueuedGoal(entry, index)),
		lastSummary: optionalText(value.lastSummary, 'lastSummary', MAX_RESULT_MESSAGE_LENGTH),
		death: normalizeDeath(value.death, state),
		respawnPolicy: isPlainObject(value.respawnPolicy) ? clone(value.respawnPolicy) : {},
		createdAtEpochMs: nonnegativeInteger(value.createdAtEpochMs ?? 1, 'createdAtEpochMs'),
		updatedAtEpochMs: nonnegativeInteger(value.updatedAtEpochMs ?? value.createdAtEpochMs ?? 1, 'updatedAtEpochMs'),
		lastError: normalizeError(value.lastError),
	};
	return validateRegisteredAgentContract(record, (message) => new AgentRegistryError('INVALID_AGENT_STATE', message));
}

function validateMutation(record) {
	return validateRegisteredAgentContract(record, (message) => new AgentRegistryError('INVALID_AGENT_STATE', message));
}

function requireProvider(value) {
	return normalizeProviderId(requireIdentifier(value, 'provider'));
}

export function reduceGoalControl(recordValue, controlValue, { queueCap = DEFAULT_GOAL_QUEUE_CAP } = {}) {
	const record = normalizeAgentRecord(recordValue, { queueCap });
	if (!isPlainObject(controlValue)) throw new TypeError('goal control must be an object');
	const operation = controlValue.operation;
	if (!GOAL_OPERATIONS.has(operation)) throw new AgentRegistryError('INVALID_GOAL_OPERATION', `Unsupported goal operation '${String(operation)}'`);
	if (operation !== 'respawn' && controlValue.resumeGoal !== undefined) throw new AgentRegistryError('INVALID_GOAL_CONTROL', `Goal operation '${operation}' must not include resumeGoal`);
	const revision = nonnegativeInteger(controlValue.goalRevision, 'goalRevision');
	if (operation === 'queue') {
		if (revision !== record.goalRevision) throw new AgentRegistryError('STALE_GOAL_REVISION', `Queued goal revision ${revision} does not match current revision ${record.goalRevision}`);
		if (record.queue.length >= queueCap) throw new AgentRegistryError('GOAL_QUEUE_FULL', `Agent goal queue is limited to ${queueCap} entries`);
		return {
			...record,
			queue: [...record.queue, normalizeQueuedGoal({ goal: controlValue.goal, goalRevision: revision, goalSpec: controlValue.goalSpec }, record.queue.length)],
			updatedAtEpochMs: nonnegativeInteger(controlValue.updatedAtEpochMs ?? Date.now(), 'updatedAtEpochMs'),
		};
	}
	if (operation === 'dequeue') {
		if (revision !== record.goalRevision) throw new AgentRegistryError('STALE_GOAL_REVISION', `Dequeued goal revision ${revision} does not match current revision ${record.goalRevision}`);
		if (record.state !== DynamicAgentState.COMPLETED) throw new AgentRegistryError('INVALID_GOAL_CONTROL', `Cannot dequeue rejected work while agent is '${record.state}'`);
		const rejectedGoal = requireGoal(controlValue.goal);
		const rejectedSpec = parseGoalSpec(controlValue.goalSpec);
		const queuedHead = record.queue[0];
		if (queuedHead === undefined
				|| queuedHead.goal !== rejectedGoal
				|| queuedHead.goalSpec?.fingerprint !== rejectedSpec.fingerprint) {
			throw new AgentRegistryError('QUEUED_GOAL_MISMATCH', `Rejected goal '${rejectedGoal}' does not match the queued head`);
		}
		return {
			...record,
			queue: record.queue.slice(1),
			updatedAtEpochMs: nonnegativeInteger(controlValue.updatedAtEpochMs ?? Date.now(), 'updatedAtEpochMs'),
		};
	}
	if ((operation === 'stop' && record.state === DynamicAgentState.PAUSED && revision === record.goalRevision)
		|| (operation === 'disconnect' && record.state === DynamicAgentState.DISCONNECTED && revision === record.goalRevision)) return record;
	if (operation === 'respawn' && record.state !== DynamicAgentState.DEAD) {
		throw new AgentRegistryError('INVALID_GOAL_CONTROL', `Cannot respawn an agent from state '${record.state}'`);
	}
	const preservesGoalRevision = operation === 'dead' || operation === 'respawn';
	if (preservesGoalRevision ? revision !== record.goalRevision : revision <= record.goalRevision) {
		throw new AgentRegistryError('STALE_GOAL_REVISION', preservesGoalRevision
			? `Goal revision ${revision} does not match current revision ${record.goalRevision}`
			: `Goal revision ${revision} is not newer than ${record.goalRevision}`);
	}
	const now = nonnegativeInteger(controlValue.updatedAtEpochMs ?? Date.now(), 'updatedAtEpochMs');
	const next = { ...record, goalRevision: revision, updatedAtEpochMs: now, lastError: null };
	if (operation === 'start' || operation === 'replace' || operation === 'steer') {
		const nextGoal = requireGoal(controlValue.goal);
		const promotesQueuedGoal = PROMOTION_SOURCE_STATES.has(record.state)
			|| (record.state === DynamicAgentState.COMPLETED && record.queue.length > 0);
		if (operation === 'start' && promotesQueuedGoal) {
			const promoted = record.queue[0];
			if (promoted === undefined || promoted.goal !== nextGoal) {
				throw new AgentRegistryError(
					'PROMOTED_GOAL_MISMATCH',
					`Promoted goal '${nextGoal}' does not match the queued head`,
				);
			}
			next.queue = record.queue.slice(1);
		}
		next.currentGoal = nextGoal;
		next.currentGoalSpec = controlValue.goalSpec === undefined ? promotedGoalSpec(record, operation) : parseGoalSpec(controlValue.goalSpec);
		next.state = DynamicAgentState.STARTING;
		return next;
	}
	if (operation === 'stop') {
		next.state = DynamicAgentState.PAUSED;
		return next;
	}
	if (operation === 'resume') {
		if (next.currentGoal === null) throw new AgentRegistryError('NO_CURRENT_GOAL', 'Cannot resume an agent without a current goal');
		next.state = DynamicAgentState.STARTING;
		return next;
	}
	if (operation === 'disconnect') {
		next.state = DynamicAgentState.DISCONNECTED;
		return next;
	}
	if (operation === 'dead') {
		next.state = DynamicAgentState.DEAD;
		next.death = normalizeDeath(controlValue.death, DynamicAgentState.DEAD);
		return next;
	}
	if (operation === 'respawn') {
		if (controlValue.resumeGoal !== undefined && typeof controlValue.resumeGoal !== 'boolean') throw new TypeError('resumeGoal must be a boolean');
		next.state = next.currentGoal === null
			? DynamicAgentState.IDLE
			: controlValue.resumeGoal === true ? DynamicAgentState.STARTING : DynamicAgentState.PAUSED;
		next.death = null;
		return next;
	}
	if (operation === 'fail') {
		next.state = DynamicAgentState.ERROR;
		next.lastError = normalizeError(controlValue.error ?? { code: 'AGENT_ERROR', message: 'Agent goal failed' });
		return next;
	}
	if (operation === 'complete') {
		next.state = DynamicAgentState.COMPLETED;
		return next;
	}
	if (next.queue.length > 0) {
		const [promoted, ...remaining] = next.queue;
		next.currentGoal = promoted.goal;
		next.currentGoalSpec = promoted.goalSpec;
		next.queue = remaining;
		next.state = DynamicAgentState.STARTING;
	} else {
		next.currentGoal = null;
		next.currentGoalSpec = null;
		next.state = DynamicAgentState.IDLE;
	}
	return next;
}

export function encodeAgentRegistrySnapshot(records, { queueCap = DEFAULT_GOAL_QUEUE_CAP } = {}) {
	if (!Array.isArray(records)) throw new TypeError('registry records must be an array');
	const agents = records.map((record) => normalizeAgentRecord(record, { queueCap })).sort((left, right) => left.agentId.localeCompare(right.agentId));
	return JSON.stringify({ schemaVersion: 1, agents });
}

export function decodeAgentRegistrySnapshot(text, { agentCap = DEFAULT_AGENT_CAP, queueCap = DEFAULT_GOAL_QUEUE_CAP, reload = true } = {}) {
	if (typeof text !== 'string' || text.trim().length === 0) throw new TypeError('registry snapshot text must be nonblank');
	let document;
	try { document = JSON.parse(text); } catch (error) { throw new AgentRegistryError('INVALID_REGISTRY_SNAPSHOT', 'Registry snapshot is not valid JSON', { cause: error }); }
	if (!isPlainObject(document) || document.schemaVersion !== 1 || !Array.isArray(document.agents)) throw new AgentRegistryError('INVALID_REGISTRY_SNAPSHOT', 'Registry snapshot must contain schemaVersion 1 and an agents array');
	if (document.agents.length > positiveInteger(agentCap, 'agentCap')) throw new AgentRegistryError('AGENT_CAP_REACHED', `Registry snapshot exceeds agent cap of ${agentCap}`);
	const seen = new Set();
	return document.agents.map((value) => {
		const record = normalizeAgentRecord(value, { queueCap, reload });
		if (seen.has(record.agentId)) throw new AgentRegistryError('DUPLICATE_AGENT', `Duplicate agent '${record.agentId}' in registry snapshot`);
		seen.add(record.agentId);
		return record;
	});
}

function assertCurrentGoalRevision(record, revisionValue) {
	const revision = nonnegativeInteger(revisionValue, 'goalRevision');
	if (revision !== record.goalRevision) throw new AgentRegistryError('STALE_GOAL_REVISION', `Goal revision ${revision} does not match current revision ${record.goalRevision}`);
}

function normalizeQueuedGoal(value, index) {
	if (typeof value === 'string') return { goal: requireGoal(value), goalRevision: index + 1, goalSpec: null };
	if (!isPlainObject(value)) throw new TypeError('queued goal must be an object');
	return {
		goal: requireGoal(value.goal),
		goalRevision: nonnegativeInteger(value.goalRevision ?? index + 1, 'queued goalRevision'),
		goalSpec: value.goalSpec === null || value.goalSpec === undefined ? null : parseGoalSpec(value.goalSpec),
	};
}

function promotedGoalSpec(record, operation) {
	if (operation === 'steer') return record.currentGoalSpec;
	if (operation === 'replace') return null;
	return record.queue[0]?.goalSpec ?? null;
}

function normalizeError(value) {
	if (value === null || value === undefined) return null;
	if (!isPlainObject(value)) throw new TypeError('lastError must be an object or null');
	return {
		code: requireText(value.code, 'lastError.code', MAX_REASON_CODE_LENGTH),
		message: requireText(value.message, 'lastError.message', MAX_RESULT_MESSAGE_LENGTH),
	};
}

function normalizeDeath(value, state) {
	if (value === null || value === undefined) {
		if (state === DynamicAgentState.DEAD) throw new AgentRegistryError('MISSING_DEATH_FACTS', 'DEAD agent requires death facts');
		return null;
	}
	if (state !== DynamicAgentState.DEAD) throw new AgentRegistryError('INVALID_DEATH_FACTS', 'Death facts require DEAD state');
	if (!isPlainObject(value)) throw new TypeError('death must be an object');
	const respawnDimensionId = nullableIdentifier(value.respawnDimensionId, 'death.respawnDimensionId');
	const respawnX = nullableFiniteNumber(value.respawnX, 'death.respawnX');
	const respawnY = nullableFiniteNumber(value.respawnY, 'death.respawnY');
	const respawnZ = nullableFiniteNumber(value.respawnZ, 'death.respawnZ');
	const respawnYaw = nullableFiniteNumber(value.respawnYaw, 'death.respawnYaw');
	const respawnPitch = nullableFiniteNumber(value.respawnPitch, 'death.respawnPitch');
	const respawnForced = nullableBoolean(value.respawnForced, 'death.respawnForced');
	const respawnFacts = [respawnDimensionId, respawnX, respawnY, respawnZ, respawnYaw, respawnPitch, respawnForced];
	if (respawnFacts.some((fact) => fact === null) && respawnFacts.some((fact) => fact !== null)) {
		throw new AgentRegistryError('INVALID_DEATH_FACTS', 'death vanilla respawn facts must be present together or all null');
	}
	const gameMode = requireIdentifier(value.gameMode, 'death.gameMode');
	if (!['survival', 'creative', 'adventure', 'spectator'].includes(gameMode)) {
		throw new AgentRegistryError('INVALID_DEATH_FACTS', 'death.gameMode must be a vanilla game mode');
	}
	return {
		cause: requireText(value.cause, 'death.cause', MAX_RESULT_MESSAGE_LENGTH),
		dimensionId: requireIdentifier(value.dimensionId, 'death.dimensionId'),
		x: finiteNumber(value.x, 'death.x'), y: finiteNumber(value.y, 'death.y'), z: finiteNumber(value.z, 'death.z'),
		respawnDimensionId, respawnX, respawnY, respawnZ, respawnYaw, respawnPitch, respawnForced, gameMode,
		diedAtEpochMs: nonnegativeInteger(value.diedAtEpochMs, 'death.diedAtEpochMs'),
	};
}

function optionalGoal(value) {
	return value === null || value === undefined ? null : requireGoal(value);
}

function requireGoal(value) {
	return requireText(value, 'goal', MAX_GOAL_LENGTH);
}

function requireIdentifier(value, field) {
	return requireText(value, field, MAX_IDENTIFIER_LENGTH);
}

function nullableIdentifier(value, field) {
	return value === null ? null : requireIdentifier(value, field);
}

function nullableBoolean(value, field) {
	if (value === null) return null;
	if (typeof value !== 'boolean') throw new TypeError(`${field} must be a boolean or null`);
	return value;
}

function optionalIdentifier(value, field) {
	return value === null || value === undefined ? null : requireIdentifier(value, field);
}

function optionalText(value, field, maximum) {
	return value === null || value === undefined ? null : requireText(value, field, maximum);
}

function requireText(value, field, maximum) {
	if (typeof value !== 'string' || value.trim().length === 0 || value.length > maximum) throw new TypeError(`${field} must be nonblank and at most ${maximum} characters`);
	return value;
}

function requireState(value) {
	if (!DYNAMIC_AGENT_STATES.has(value)) throw new AgentRegistryError('INVALID_AGENT_STATE', `Unsupported state '${String(value)}'`);
	return value;
}

function positiveInteger(value, field) {
	if (!Number.isSafeInteger(value) || value <= 0) throw new TypeError(`${field} must be a positive safe integer`);
	return value;
}

function nonnegativeInteger(value, field) {
	if (!Number.isSafeInteger(value) || value < 0) throw new TypeError(`${field} must be a nonnegative safe integer`);
	return value;
}

function finiteNumber(value, field) {
	if (!Number.isFinite(value)) throw new TypeError(`${field} must be finite`);
	return value;
}

function nullableFiniteNumber(value, field) {
	return value === null ? null : finiteNumber(value, field);
}

function isPlainObject(value) {
	return value !== null && typeof value === 'object' && !Array.isArray(value);
}

function clone(value) {
	return structuredClone(value);
}
import { parseGoalSpec } from './goal-spec.mjs';
