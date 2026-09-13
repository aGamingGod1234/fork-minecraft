import { EventEmitter } from 'node:events';

import { adaptObservation } from '../observation-adapter.mjs';
import { createProtocolV2Envelope, validateProtocolV2Envelope } from '../protocol-v2.mjs';
import { ActionRuntime } from './action-runtime.mjs';
import { VIRTUAL_TICK_MS } from './virtual-world.mjs';

const ACTIVE_ACTION_PLAYER_FACTS = Object.freeze(['health', 'maxHealth', 'gameMode', 'onFire', 'suffocating']);
const ACTIVE_ACTION_ATTENTION_FACTS = new Set([
	...ACTIVE_ACTION_PLAYER_FACTS.map((field) => `player.${field}`),
	'player.lastAttacker',
	'world',
]);

/**
 * Deterministic protocol-v2 bridge backed by VirtualWorld and the complete action runtime.
 */
export class VirtualMinecraftBridge extends EventEmitter {
	#world;
	#serverInstanceId;
	#records = new Map();
	#manager = null;
	#actionRuntime;
	#messageSequence = 0;
	#eventSequences = new Map();
	#active = new Map();
	#deliveryTail = Promise.resolve();
	#worldTickListener;
	#closed = false;

	sent = [];
	events = [];
	validatedInbound = 0;
	validatedOutbound = 0;

	constructor({ world, agentRecords = undefined, record = undefined, serverInstanceId = 'virtual-server', actionRuntime = undefined } = {}) {
		super();
		if (!world || typeof world.observation !== 'function' || typeof world.on !== 'function') throw new TypeError('world must be a VirtualWorld-like event emitter');
		this.#world = world;
		this.#actionRuntime = actionRuntime ?? new ActionRuntime();
		if (typeof this.#actionRuntime.accept !== 'function' || typeof this.#actionRuntime.tick !== 'function' || typeof this.#actionRuntime.cancel !== 'function') throw new TypeError('actionRuntime must provide accept, tick, and cancel');
		this.#serverInstanceId = requireIdentifier(serverInstanceId, 'serverInstanceId');
		const source = agentRecords ?? (record === undefined ? undefined : { [record.agentId]: record });
		if (source !== undefined) {
			for (const [agentId, value] of recordEntries(source)) this.#records.set(agentId, normalizeRecord(agentId, value));
		}
		for (const agentId of world.agentIds ?? []) if (!this.#records.has(agentId)) this.#records.set(agentId, { agentId, goalRevision: 1 });
		this.#worldTickListener = () => this.#advanceActiveActions();
		this.#world.on('tick', this.#worldTickListener);
	}

	get world() { return this.#world; }
	get serverInstanceId() { return this.#serverInstanceId; }
	get activeActionIds() { return [...this.#active.keys()]; }
	actionRuntimeSnapshot() {
		const runtime = typeof this.#actionRuntime.snapshot === 'function' ? this.#actionRuntime.snapshot() : { activeActionIds: [] };
		return structuredClone({ runtime, bridgeActiveAgentIds: this.activeActionIds });
	}

	attach(manager) {
		if (manager !== null && manager !== undefined) {
			for (const method of ['onObservation', 'onActionProgress', 'onActionResult']) if (typeof manager[method] !== 'function') throw new TypeError(`manager.${method} must be a function`);
		}
		this.#manager = manager ?? null;
		return this;
	}

	async send(type, agentId, payload) {
		if (this.#closed) throw Object.assign(new Error('virtual bridge is stopped'), { code: 'BRIDGE_STOPPED' });
		const record = this.#recordFor(agentId);
		const envelope = createProtocolV2Envelope({
			serverInstanceId: this.#serverInstanceId,
			agentId,
			type,
			messageId: `virtual-out-${++this.#messageSequence}`,
			payload,
		});
		const normalized = validateProtocolV2Envelope(envelope, { direction: 'coordinator_to_server' });
		this.validatedOutbound += 1;
		this.sent.push(normalized);
		if (type === 'action_command') {
			if (this.#active.has(agentId)) throw Object.assign(new Error(`agent '${agentId}' already has an active action`), { code: 'ACTION_BUSY' });
			if (!(this.#world.agentIds ?? []).includes(agentId)) throw Object.assign(new Error(`unknown world agent '${agentId}'`), { code: 'UNKNOWN_AGENT' });
			const command = normalized.payload;
			const active = {
				agentId,
				record,
				command,
				baselineObservation: this.#world.observation(agentId),
				generation: Symbol('action'),
				elapsedTicks: 0,
				progressSent: false,
			};
			let accepted = false;
			try {
				this.#actionRuntime.accept({ agentId, ...command });
				accepted = true;
				this.#world.setActiveAction(agentId, command.actionId, command.actionType);
				this.#active.set(agentId, active);
				this.#recordEvent('accepted', normalized);
			} catch (error) {
				if (this.#active.get(agentId) === active) this.#active.delete(agentId);
				if (accepted) {
					try { this.#actionRuntime.cancel(command.actionId); }
					catch { /* rollback must preserve the original setup failure */ }
				}
				try { this.#world.setActiveAction(agentId, null); }
				catch { /* the world may have rejected the initial setup */ }
				throw error;
			}
			return;
		}
		if (type === 'action_cancel') this.#cancel(record, normalized.payload);
	}

	async publish(agentId, { attention = false, changedFacts = [] } = {}) {
		const record = this.#recordFor(agentId);
		return this.#publishObservation(record, { attention, changedFacts });
	}

	async flush() {
		await this.#deliveryTail;
	}

	stop() {
		if (this.#closed) return this;
		this.#closed = true;
		this.#world.off?.('tick', this.#worldTickListener);
		for (const [agentId, active] of this.#active) {
			this.#active.delete(agentId);
			this.#actionRuntime.cancel(active.command.actionId);
			this.#world.setActiveAction(agentId, null);
			this.#complete(active, { state: 'CANCELLED', reasonCode: 'BRIDGE_STOPPED', changedFacts: ['currentAction'] });
		}
		return this;
	}

	#advanceActiveActions() {
		if (this.#closed) return;
		for (const [agentId, active] of [...this.#active]) {
			if (this.#active.get(agentId) !== active) continue;
			const generation = active.generation;
			if (!active.progressSent) {
				active.progressSent = true;
				this.#progress(active);
			}
			if (this.#active.get(agentId) !== active || active.generation !== generation) continue;
		}
		const results = this.#actionRuntime.tick(this.#world, { advanceWorld: false });
		for (const result of results) {
			const active = this.#active.get(result.agentId);
			if (!active || active.command.actionId !== result.actionId) continue;
			active.elapsedTicks = result.elapsedTicks;
			this.#active.delete(result.agentId);
			this.#world.setActiveAction(result.agentId, null);
			this.#complete(active, result);
		}
	}

	#cancel(record, payload) {
		const active = this.#active.get(record.agentId);
		if (!active || active.command.actionId !== payload.actionId || active.command.goalRevision !== payload.goalRevision) return;
		this.#active.delete(record.agentId);
		this.#actionRuntime.cancel(active.command.actionId);
		this.#world.setActiveAction(record.agentId, null);
		this.#complete(active, { state: 'CANCELLED', reasonCode: 'CANCELLED', changedFacts: ['currentAction'] });
	}

	#progress(active) {
		const eventSequence = this.#nextEventSequence(active.agentId);
		const payload = {
			traceId: active.command.traceId,
			goalRevision: active.command.goalRevision,
			actionId: active.command.actionId,
			commandId: active.command.actionId,
			actionType: active.command.actionType,
			state: 'RUNNING',
			message: 'running',
			progress: 0.5,
			elapsedMs: Math.trunc(active.elapsedTicks * VIRTUAL_TICK_MS),
			observedAtEpochMs: this.#world.timeMs,
		};
		const envelope = this.#inbound(active.agentId, 'action_progress', payload);
		this.#recordEvent('progress', envelope, eventSequence);
		try {
			const result = this.#manager?.onActionProgress(active.record, { ...envelope.payload, eventSequence });
			if (result && typeof result.then === 'function') this.#enqueue(async () => result);
		} catch (error) {
			this.emit('deliveryError', error);
		}
	}

	#complete(active, outcome) {
		const state = outcome.state ?? 'SUCCEEDED';
		const reasonCode = outcome.reasonCode ?? 'DONE';
		this.#world.setLastResult(active.agentId, {
			present: true,
			actionId: active.command.actionId,
			actionType: active.command.actionType,
			state,
			reasonCode,
			message: reasonCode,
		});
		const completionObservation = this.#world.observation(active.agentId);
		const changedFacts = completionAttentionFacts(active.baselineObservation, completionObservation);
		this.#publishObservation(active.record, {
			attention: changedFacts.length > 0,
			changedFacts,
			observation: completionObservation,
		});
		const eventSequence = this.#nextEventSequence(active.agentId);
		const payload = {
			traceId: active.command.traceId,
			goalRevision: active.command.goalRevision,
			actionId: active.command.actionId,
			commandId: active.command.actionId,
			actionType: active.command.actionType,
			state,
			reasonCode,
			message: reasonCode,
			executionStarted: true,
			physicalAttempted: true,
			elapsedMs: Math.trunc(active.elapsedTicks * VIRTUAL_TICK_MS),
			observedAtEpochMs: this.#world.timeMs,
		};
		const envelope = this.#inbound(active.agentId, 'action_result', payload);
		this.#recordEvent('result', envelope, eventSequence);
		this.#enqueue(async () => this.#manager?.onActionResult(active.record, { ...envelope.payload, eventSequence }));
	}

	#publishObservation(record, { attention = false, changedFacts = [], observation = undefined } = {}) {
		const worldObservation = observation ?? this.#world.observation(record.agentId);
		const factualChanges = attention === true ? [...new Set(changedFacts)] : [];
		const eventSequence = this.#nextEventSequence(record.agentId);
		const payload = { ...worldObservation, eventSequence, attention: factualChanges.length > 0, changedFacts: factualChanges };
		const envelope = this.#inbound(record.agentId, 'observation', payload);
		this.#recordEvent('observation', envelope, eventSequence);
		const adapted = adaptObservation(envelope.payload);
		this.#enqueue(async () => this.#manager?.onObservation(record, {
			observation: adapted,
			eventSequence,
			attention: envelope.payload.attention,
			observedAtEpochMs: envelope.payload.observedAtEpochMs,
			receiptMonotonicMs: this.#world.timeMs,
			receiptEpochMs: this.#world.timeMs,
		}));
		return adapted;
	}

	#inbound(agentId, type, payload) {
		const envelope = createProtocolV2Envelope({
			serverInstanceId: this.#serverInstanceId,
			agentId,
			type,
			messageId: `virtual-in-${++this.#messageSequence}`,
			payload,
		});
		const normalized = validateProtocolV2Envelope(envelope, { direction: 'server_to_coordinator' });
		this.validatedInbound += 1;
		return normalized;
	}

	#recordEvent(type, envelope, eventSequence = undefined) {
		const event = { type, envelope, ...(eventSequence === undefined ? {} : { eventSequence }) };
		this.events.push(event);
		try { this.emit(type, event); }
		catch (error) {
			const index = this.events.lastIndexOf(event);
			if (index >= 0) this.events.splice(index, 1);
			throw error;
		}
	}

	#enqueue(callback) {
		this.#deliveryTail = this.#deliveryTail
			.then(async () => {
				try { await callback(); }
				catch (error) { this.emit('deliveryError', error); }
			});
		return this.#deliveryTail;
	}

	#nextEventSequence(agentId) {
		const next = (this.#eventSequences.get(agentId) ?? 0) + 1;
		this.#eventSequences.set(agentId, next);
		return next;
	}

	#recordFor(agentId) {
		const id = requireIdentifier(agentId, 'agentId');
		const record = this.#records.get(id);
		if (record === undefined) throw Object.assign(new Error(`unknown agent '${id}'`), { code: 'UNKNOWN_AGENT' });
		return record;
	}
}

function recordEntries(source) {
	if (Array.isArray(source)) return source.map((record, index) => [record?.agentId ?? record?.id ?? `agent-${index + 1}`, record]);
	if (source !== null && typeof source === 'object') return Object.entries(source);
	throw new TypeError('agentRecords must be an array or object');
}

function normalizeRecord(agentId, value) {
	const source = value !== null && typeof value === 'object' ? value : {};
	return { ...source, agentId, goalRevision: Number.isSafeInteger(source.goalRevision) && source.goalRevision >= 0 ? source.goalRevision : 1 };
}

function requireIdentifier(value, field) {
	if (typeof value !== 'string' || value.length === 0 || value.length > 256) throw new TypeError(`${field} must be a non-empty string`);
	return value;
}

function completionAttentionFacts(previous, current) {
	if (!previous || !current) return [];
	const facts = new Set();
	const previousPlayer = previous.player ?? {};
	const currentPlayer = current.player ?? {};
	for (const field of ACTIVE_ACTION_PLAYER_FACTS) {
		if (previousPlayer[field] !== currentPlayer[field]) facts.add(`player.${field}`);
	}
	if (attackerAppearedOrChanged(previousPlayer.lastAttacker, currentPlayer.lastAttacker)) facts.add('player.lastAttacker');
	if (materialWorldChanged(previous.world, current.world)) facts.add('world');
	return normalizedAttentionFacts([...facts]);
}

function normalizedAttentionFacts(changedFacts) {
	if (!Array.isArray(changedFacts)) return [];
	return [...new Set(changedFacts.filter((fact) => ACTIVE_ACTION_ATTENTION_FACTS.has(fact)))].sort();
}

function attackerAppearedOrChanged(previous, current) {
	if (!current || typeof current !== 'object' || Array.isArray(current)) return false;
	if (!previous || typeof previous !== 'object' || Array.isArray(previous)) return true;
	return previous.uuid !== current.uuid || previous.type !== current.type;
}

function materialWorldChanged(previous, current) {
	for (const field of ['dimension', 'raining', 'thundering']) if (previous?.[field] !== current?.[field]) return true;
	return false;
}
