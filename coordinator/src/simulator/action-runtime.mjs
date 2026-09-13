import { VIRTUAL_TICK_MS } from './virtual-world.mjs';
import { validateAction } from '../schema.mjs';

const SUPPORTED_ACTIONS = new Set([
	'move_to', 'navigate_to', 'look_at', 'wait', 'use_item', 'block_with_shield',
	'attack', 'respawn', 'select_item', 'break_block', 'place_block', 'drop_item',
	'craft_inventory', 'craft_table', 'chat',
]);

/**
 * Wall-clock-independent action state machine for VirtualWorld scenarios.
 * World mutation remains authoritative in VirtualWorld.performSimulationAction.
 */
export class ActionRuntime {
	#defaultTimeoutTicks;
	#active = new Map();
	#results = new Map();
	#agentActions = new Map();
	#tickCount = 0;
	#events = [];

	constructor({ defaultTimeoutTicks = 600 } = {}) {
		if (!Number.isSafeInteger(defaultTimeoutTicks) || defaultTimeoutTicks < 1) throw new RangeError('defaultTimeoutTicks must be a positive safe integer');
		this.#defaultTimeoutTicks = defaultTimeoutTicks;
	}

	get tickCount() { return this.#tickCount; }
	get activeActionIds() { return [...this.#active.keys()]; }
	get events() { return this.#events.map(clone); }

	accept(command) {
		const normalized = normalizeCommand(command, this.#defaultTimeoutTicks);
		if (this.#results.has(normalized.actionId)) throw codedError('ACTION_ID_REUSED', `action '${normalized.actionId}' already completed`);
		if (this.#active.has(normalized.actionId)) throw codedError('ACTION_BUSY', `action '${normalized.actionId}' is already active`);
		if (this.#agentActions.has(normalized.agentId)) throw codedError('ACTION_BUSY', `agent '${normalized.agentId}' already has an active action`);
		const active = {
			...normalized,
			acceptedAtTick: this.#tickCount,
			elapsedTicks: 0,
			terminal: false,
			world: null,
		};
		this.#active.set(active.actionId, active);
		this.#agentActions.set(active.agentId, active.actionId);
		this.#events.push(Object.freeze({ type: 'accepted', actionId: active.actionId, agentId: active.agentId, actionType: active.actionType, tick: this.#tickCount }));
		return freezeRecord(publicCommand(active));
	}

	tick(world, { advanceWorld = true } = {}) {
		if (!world || (typeof world.performSimulationAction !== 'function' && typeof world.performAction !== 'function') || typeof world.tick !== 'function') throw new TypeError('world must provide tick and performAction');
		this.#tickCount += 1;
		if (advanceWorld) world.tick();
		const terminal = [];
		for (const active of [...this.#active.values()]) {
			if (this.#active.get(active.actionId) !== active || active.terminal) continue;
			active.world = world;
			world.setActiveAction?.(active.agentId, active.actionId, active.actionType);
			this.#events.push(Object.freeze({ type: 'progress', actionId: active.actionId, agentId: active.agentId, actionType: active.actionType, tick: this.#tickCount, elapsedTicks: active.elapsedTicks }));
			let outcome;
			if (!SUPPORTED_ACTIONS.has(active.actionType)) {
				outcome = { done: true, state: 'FAILED', reasonCode: 'SIMULATOR_UNSUPPORTED_ACTION', changed: false };
			} else {
				try {
					const perform = world.performSimulationAction ?? world.performAction;
					outcome = perform.call(world, active.agentId, { type: active.actionType, arguments: active.arguments }, { elapsedTicks: active.elapsedTicks });
				} catch (error) {
					outcome = { done: true, state: 'FAILED', reasonCode: error.code === 'WORLD_CAPACITY_EXCEEDED' ? error.code : 'SIMULATOR_ACTION_ERROR', changed: false, message: error.message };
				}
			}
			active.elapsedTicks += 1;
			if (outcome?.done) terminal.push(this.#finish(active, outcome));
			else if (active.elapsedTicks >= active.timeoutTicks) terminal.push(this.#finish(active, { state: 'TIMED_OUT', reasonCode: 'ACTION_TIMEOUT', changed: false }));
		}
		return terminal;
	}

	cancel(actionId) {
		const id = requireIdentifier(actionId, 'actionId');
		const active = this.#active.get(id);
		if (!active) return this.#results.get(id) ? clone(this.#results.get(id)) : undefined;
		active.world?.setShield?.(active.agentId, false);
		return this.#finish(active, { state: 'CANCELLED', reasonCode: 'CANCELLED', changed: false });
	}

	resultFor(actionId) {
		const result = this.#results.get(requireIdentifier(actionId, 'actionId'));
		return result === undefined ? undefined : clone(result);
	}

	snapshot() {
		return {
			tickCount: this.#tickCount,
			active: [...this.#active.values()].map((active) => publicActive(active)),
			results: [...this.#results.values()].map(clone),
		};
	}

	#finish(active, outcome) {
		if (active.terminal) return clone(this.#results.get(active.actionId));
		active.terminal = true;
		this.#active.delete(active.actionId);
		this.#agentActions.delete(active.agentId);
		active.world?.setActiveAction?.(active.agentId, null);
		if (active.actionType === 'block_with_shield') active.world?.setShield?.(active.agentId, false);
		const result = Object.freeze({
			actionId: active.actionId,
			agentId: active.agentId,
			actionType: active.actionType,
			state: outcome.state ?? 'FAILED',
			reasonCode: outcome.reasonCode ?? 'SIMULATOR_ACTION_ERROR',
			message: typeof outcome.message === 'string' ? outcome.message : (outcome.reasonCode ?? 'SIMULATOR_ACTION_ERROR'),
			elapsedTicks: active.elapsedTicks,
			elapsedMs: Math.trunc(active.elapsedTicks * VIRTUAL_TICK_MS),
		});
		this.#results.set(active.actionId, result);
		this.#events.push(Object.freeze({ type: 'result', ...result, tick: this.#tickCount }));
		return clone(result);
	}
}

export const SUPPORTED_SIMULATOR_ACTIONS = Object.freeze([...SUPPORTED_ACTIONS]);

function normalizeCommand(command, defaultTimeoutTicks) {
	if (!command || typeof command !== 'object' || Array.isArray(command)) throw codedError('INVALID_ACTION', 'command must be an object');
	const agentId = requireIdentifier(command.agentId ?? command.agent?.id, 'agentId');
	const actionId = requireIdentifier(command.actionId, 'actionId');
	const actionType = requireIdentifier(command.actionType ?? command.action?.type, 'actionType');
	const sourceArguments = command.arguments ?? (command.action ? withoutType(command.action) : undefined);
	if (!sourceArguments || typeof sourceArguments !== 'object' || Array.isArray(sourceArguments)) throw codedError('INVALID_ACTION', 'command.arguments must be an object');
	const argumentsValue = clone(sourceArguments);
	if (Object.hasOwn(argumentsValue, 'type')) throw codedError('INVALID_ACTION', 'arguments.type is reserved; use actionType');
	try { validateAction({ type: actionType, ...argumentsValue }); }
	catch (error) { throw codedError(error.code ?? 'INVALID_ACTION', error.message); }
	const timeoutMs = argumentsValue.timeoutMs;
	const timeoutTicks = timeoutMs === undefined ? defaultTimeoutTicks : Math.max(1, Math.ceil(timeoutMs / VIRTUAL_TICK_MS));
	return { agentId, actionId, actionType, arguments: freezeRecord(argumentsValue), timeoutTicks };
}

function withoutType(action) {
	const { type: _type, ...argumentsValue } = action;
	return argumentsValue;
}

function publicCommand(active) {
	return { agentId: active.agentId, actionId: active.actionId, actionType: active.actionType, arguments: clone(active.arguments), timeoutTicks: active.timeoutTicks };
}

function publicActive(active) {
	return { ...publicCommand(active), elapsedTicks: active.elapsedTicks, acceptedAtTick: active.acceptedAtTick };
}

function requireIdentifier(value, field) {
	if (typeof value !== 'string' || value.length === 0 || value.length > 256) throw codedError('INVALID_ACTION', `${field} must be a non-empty string`);
	return value;
}

function codedError(code, message) { return Object.assign(new Error(`${code}: ${message}`), { code }); }
function clone(value) { return value === undefined ? undefined : structuredClone(value); }
function freezeRecord(value) { return Object.freeze(value); }
