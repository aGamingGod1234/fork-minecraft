import { EventEmitter } from 'node:events';
import { createProtocolV2Envelope, validateProtocolV2Envelope, validateProtocolV2Payload } from '../../src/protocol-v2.mjs';
import { adaptObservation } from '../../src/observation-adapter.mjs';
import { goalSpecFingerprint } from '../../src/goal-spec.mjs';

export const SELECTED_PROFILE = Object.freeze({
	agentId: 'task10-agent',
	provider: 'codex',
	model: 'gpt-5.6-sol',
	reasoningEffort: 'high',
	serviceTier: 'fast',
});

export function observation(overrides = {}) {
	return {
		player: { x: 0, y: 64, z: 0, health: 20, dead: false, ...overrides.player },
		items: overrides.items ?? [],
		entities: overrides.entities ?? [],
		blocks: overrides.blocks ?? [],
		inventory: { items: [], tagCounts: { '#minecraft:logs': 0 }, ...overrides.inventory },
	};
}

/** Deterministic loopback bridge that executes only commands accepted by protocol v2. */
export class FakeMinecraftBridge {
	#record;
	#manager;
	#onAction;
	#onCancel;
	#observation;
	#eventSequence;
	#clock;
	#recorder;
	#pending = new Map();
	#messageSequence = 0;
	#serverInstanceId = 'task10-fake-server';

	sent = [];
	progress = [];
	results = [];
	traffic = [];
	validatedInbound = 0;
	validatedOutbound = 0;

	constructor({ record, initialObservation = observation(), onAction = () => ({}), onCancel = () => ({}), recorder = null, benchmarkRecorder = null } = {}) {
		this.#record = record;
		this.#onAction = onAction;
		this.#onCancel = onCancel;
		this.#recorder = recorder ?? benchmarkRecorder;
		if (this.#recorder !== null && typeof this.#recorder.record !== 'function') throw new TypeError('recorder.record must be a function');
		this.#observation = adaptObservation(validateProtocolV2Payload('observation', toWireObservation(initialObservation, record.goalRevision, 1, false, 1)));
		this.#eventSequence = 1;
		this.#clock = 1;
	}

	attach(manager) {
		this.#manager = manager;
	}

	get currentObservation() {
		return this.#observation;
	}

	get eventSequence() {
		return this.#eventSequence;
	}

	async send(type, agentId, payload) {
		if (agentId !== this.#record.agentId) throw new Error(`unexpected agent ${agentId}`);
		const envelope = createProtocolV2Envelope({
			serverInstanceId: this.#serverInstanceId,
			agentId,
			type,
			messageId: `out-${++this.#messageSequence}`,
			payload,
		});
		validateProtocolV2Envelope(envelope, { direction: 'coordinator_to_server' });
		this.validatedOutbound += 1;
		const normalized = envelope.payload;
		this.#recordBenchmark('bridge_command_accepted', { type, actionType: normalized.actionType ?? null, actionId: normalized.actionId ?? null });
		if (type === 'action_command') {
			this.sent.push(envelope);
			this.traffic.push({ type, actionId: normalized.actionId });
			queueMicrotask(() => { void this.#execute(normalized); });
			return;
		}
		if (type === 'action_cancel') {
			this.sent.push(envelope);
			queueMicrotask(() => { void this.#cancel(normalized.actionId); });
			return;
		}
		this.sent.push(envelope);
	}

	async publish(nextObservation, { attention = false, eventSequence = this.#nextSequence(), observedAtEpochMs = this.#clock } = {}) {
		const inbound = createProtocolV2Envelope({
			serverInstanceId: this.#serverInstanceId,
			agentId: this.#record.agentId,
			type: 'observation',
			messageId: `in-${++this.#messageSequence}`,
			payload: toWireObservation(nextObservation, this.#record.goalRevision, eventSequence, attention, observedAtEpochMs),
		});
		const normalized = validateProtocolV2Envelope(inbound, { direction: 'server_to_coordinator' });
		this.validatedInbound += 1;
		this.traffic.push({ type: 'observation', eventSequence: normalized.payload.eventSequence });
		this.#observation = adaptObservation(normalized.payload);
		this.#recordBenchmark('observation_published', { eventSequence: normalized.payload.eventSequence, attention: normalized.payload.attention === true });
		this.#eventSequence = Math.max(this.#eventSequence, eventSequence);
		if (!this.#manager) throw new Error('FakeMinecraftBridge is not attached to a manager');
		return this.#manager.onObservation(this.#record, {
			observation: this.#observation,
			eventSequence: normalized.payload.eventSequence,
			attention: normalized.payload.attention,
			observedAtEpochMs: normalized.payload.observedAtEpochMs,
			receiptMonotonicMs: ++this.#clock,
			receiptEpochMs: observedAtEpochMs + 1,
		});
	}

	async #execute(command) {
		const plan = await this.#onAction(command, this);
		if (plan?.defer === true) this.#pending.set(command.actionId, command);
		if (plan?.attentionObservation !== undefined) {
			await this.publish(plan.attentionObservation, { attention: true, eventSequence: this.#nextSequence() });
		}
		if (plan?.defer === true) {
			return;
		}
		await this.#finish(command, plan);
	}

	async #cancel(actionId) {
		const command = this.#pending.get(actionId) ?? this.sent.find((entry) => entry.type === 'action_command' && entry.payload.actionId === actionId)?.payload;
		if (!command) return;
		this.#pending.delete(actionId);
		const plan = await this.#onCancel(command, this);
		await this.#finish(command, { state: 'CANCELLED', reasonCode: plan?.reasonCode ?? 'CANCELLED', observation: plan?.observation ?? this.#observation });
	}

	async #finish(command, plan = {}) {
		const progressSequence = this.#nextSequence();
		this.progress.push({ actionId: command.actionId, eventSequence: progressSequence });
		if (this.#manager) {
			const inbound = createProtocolV2Envelope({ serverInstanceId: this.#serverInstanceId, agentId: this.#record.agentId, type: 'action_progress', messageId: `in-${++this.#messageSequence}`, payload: {
				traceId: command.traceId,
				goalRevision: command.goalRevision,
				actionId: command.actionId,
				commandId: command.actionId,
				state: 'RUNNING',
				message: 'progress',
				progress: 0.5,
				elapsedMs: 1,
				observedAtEpochMs: this.#clock,
			} });
			const normalized = validateProtocolV2Envelope(inbound, { direction: 'server_to_coordinator' });
			this.validatedInbound += 1;
			await this.#manager.onActionProgress(this.#record, normalized.payload);
		}
		if (plan.observation !== undefined) this.#observation = plan.observation;
		const result = {
			goalRevision: command.goalRevision,
			actionId: command.actionId,
			state: plan.state ?? 'SUCCEEDED',
			reasonCode: plan.reasonCode ?? 'DONE',
		};
		const observationSequence = this.#nextSequence();
		this.results.push(result);
		this.#recordBenchmark('bridge_action_completed', { actionId: result.actionId, actionType: command.actionType, eventSequence: observationSequence, state: result.state, reasonCode: result.reasonCode });
		if (this.#manager) {
			const inbound = createProtocolV2Envelope({ serverInstanceId: this.#serverInstanceId, agentId: this.#record.agentId, type: 'action_result', messageId: `in-${++this.#messageSequence}`, payload: {
				...result,
				traceId: command.traceId,
				commandId: result.actionId,
				actionType: command.actionType,
				message: result.reasonCode,
				executionStarted: true,
				physicalAttempted: true,
				elapsedMs: 1,
				observedAtEpochMs: this.#clock,
			} });
			const normalized = validateProtocolV2Envelope(inbound, { direction: 'server_to_coordinator' });
			this.validatedInbound += 1;
			this.traffic.push({ type: 'action_result', actionId: normalized.payload.actionId });
			await this.#manager.onActionResult(this.#record, normalized.payload);
		}
		await this.publish(this.#observation, { eventSequence: observationSequence, observedAtEpochMs: this.#clock });
	}

	#nextSequence() {
		this.#eventSequence += 1;
		return this.#eventSequence;
	}

	#recordBenchmark(stage, fields) {
		if (this.#recorder === null) return;
		try { this.#recorder.record(stage, { agentId: this.#record.agentId, goalRevision: this.#record.goalRevision }, fields); }
		catch { /* benchmark telemetry cannot affect fixture execution */ }
	}
}

function toWireObservation(value, goalRevision, eventSequence, attention, observedAtEpochMs) {
	const player = value.player ?? {};
	if (player.dead === true) return { goalRevision, observedAtEpochMs, ready: false, status: 'PLAYER_DEAD', eventSequence, attention: false, changedFacts: [] };
	const position = { x: player.x ?? 0, y: player.y ?? 64, z: player.z ?? 0 };
	return {
		goalRevision,
		observedAtEpochMs,
		ready: player.dead !== true,
		status: player.dead === true ? 'PLAYER_DEAD' : 'ready',
		eventSequence,
		attention,
		changedFacts: attention ? ['player.health'] : [],
		position,
		velocity: { x: 0, y: 0, z: 0 },
		view: { yaw: 0, pitch: 0 },
		player: {
			health: player.health ?? 20, maxHealth: 20, armor: 0, foodLevel: 20, saturation: 5,
			gameMode: 'survival', onGround: true, inWater: false, onFire: player.fire === true,
			air: 300, maxAir: 300, suffocating: false, fallDistance: player.fallDistance ?? 0, effects: [],
		},
		inventory: { items: (value.inventory?.items ?? []).map((item, index) => ({ itemId: item.itemId, count: item.count, damage: 0, maxDamage: 0, slot: item.slot ?? index, ...(item.tags ? { tags: item.tags } : {}) })), selectedItem: 'minecraft:air', ...(value.inventory?.tagCounts ? { tagCounts: value.inventory.tagCounts } : {}) },
		entities: (value.items ?? []).map((item) => ({ uuid: item.stableId, type: 'minecraft:item', name: 'drop', distance: Math.hypot(item.x - position.x, item.y - position.y, item.z - position.z), position: { x: item.x, y: item.y, z: item.z }, itemId: item.itemId, count: item.count, ...(item.tags ? { tags: item.tags } : {}) })),
		blocks: (value.blocks ?? []).map((block) => ({ x: block.x, y: block.y, z: block.z, blockId: block.blockId, placeableFaces: ['up', 'down', 'north', 'south', 'east', 'west'], ...(block.tags ? { tags: block.tags } : {}) })),
		nearbyContainers: [], world: { dimension: 'minecraft:overworld', gameTime: 1, dayTime: 1, raining: false, thundering: false },
		currentAction: { active: false }, lastResult: { present: false },
	};
}

export function commandPayloads(bridge) {
	return bridge.sent.filter((entry) => entry.type === 'action_command').map((entry) => entry.payload);
}

export function assertCommandProvenance(commands, profile = SELECTED_PROFILE, expectedProgramId = null) {
	for (const command of commands) {
		const payload = command.payload ?? command;
		const provenance = payload.provenance;
		if (provenance === null || typeof provenance !== 'object') throw new Error('command is missing model-program provenance');
		if (provenance.model !== profile.model) throw new Error(`command used ${provenance.model}, expected ${profile.model}`);
		if (expectedProgramId !== null && provenance.programId !== expectedProgramId) throw new Error(`command used ${provenance.programId}, expected ${expectedProgramId}`);
		if (!/^program-\d+-\d+$/.test(provenance.programId)) throw new Error(`invalid program id ${provenance.programId}`);
		if (!/^step-/.test(provenance.sourceStepId)) throw new Error(`invalid source step ${provenance.sourceStepId}`);
		if (!Number.isSafeInteger(provenance.eventSequence)) throw new Error('command event sequence is not a safe integer');
	}
}

/**
 * Event-emitting bridge for coordinator-level native-turn tests. It is the
 * external socket/world boundary: tests inject failures here, never into
 * coordinator private state or supervisor timers.
 */
export class FaultInjectingMinecraftBridge extends EventEmitter {
	#scenario;
	#record;
	#serverInstanceId = 'native-fault-fixture-1';
	#eventSequence = 1;
	#actionNumber = 0;
	#completionNumber = 0;
	#connected = false;
	#stopped = false;
	#world;
	#reconnectTimer = null;
	#actionResults = [];
	#connectionEpoch = 0;
	#deferredActionResults = new Map();
	#activePhysicalActions = 0;

	sent = [];
	states = [];
	recoveries = [];
	maxRecoveryHandles = 0;
	validatedOutbound = 0;
	validatedInbound = 0;
	recoveryHandles = 0;
	recoveryDispatches = 0;
	completionEvaluations = [];
	goalControls = [];
	actionEffects = [];
	actionAttempts = [];
	deliveredActionResults = [];
	staleActionReplays = [];
	maxConcurrentPhysicalActions = 0;

	constructor(scenario = {}) {
		super();
		this.#scenario = scenario;
		this.#record = {
			agentId: 'native-fault-agent',
			provider: 'codex',
			model: 'gpt-5.6-luna',
			reasoningEffort: 'xhigh',
			serviceTier: 'fast',
			goalRevision: 0,
		};
		this.#world = {
			position: { x: 0, y: 64, z: 0 },
			dead: false,
			health: 20,
			inventory: new Map(Object.entries(scenario.initialInventory ?? {})),
			blocks: new Map([['0,64,0', 'minecraft:oak_log']]),
			entities: new Map(),
			drop: { stableId: '00000000-0000-4000-8000-000000000001', itemId: 'minecraft:oak_log', count: 1, x: 1, y: 64, z: 0 },
		};
	}

	get record() { return { ...this.#record }; }
	get connected() { return this.#connected; }
	get actionCount() { return this.#actionNumber; }
	get completionCount() { return this.#completionNumber; }
	get inventory() { return new Map(this.#world.inventory); }
	get world() { return structuredClone({ ...this.#world, inventory: Object.fromEntries(this.#world.inventory) }); }
	get goalSpec() { return this.#record.goalSpec === undefined ? null : structuredClone(this.#record.goalSpec); }
	get connectionEpoch() { return this.#connectionEpoch; }
	get actionDispatches() { return this.sent.filter(({ type }) => type === 'action_command').map((entry) => structuredClone(entry)); }
	get deferredActionCount() { return this.#deferredActionResults.size; }

	start() {
		this.#stopped = false;
		this.#connected = true;
	}

	stop() {
		this.#stopped = true;
		this.#connected = false;
		if (this.#reconnectTimer !== null) this.#reconnectTimer.cancelled = true;
		this.#reconnectTimer = null;
	}

	ready({ revision = this.#record.goalRevision, state = 'IDLE' } = {}) {
		this.#connected = true;
		this.#connectionEpoch += 1;
		this.#record.goalRevision = revision;
		const profile = {
			schemaVersion: 1,
			agentId: this.#record.agentId,
			provider: this.#record.provider,
			model: this.#record.model,
			reasoningEffort: this.#record.reasoningEffort,
			serviceTier: this.#record.serviceTier,
			skinVariant: 'default',
			state,
			goalRevision: revision,
			currentGoal: state === 'IDLE' ? null : this.#scenario.goal ?? 'gather wood and craft a wooden pickaxe',
			currentGoalSpec: this.#record.goalSpec ?? null,
			queue: [],
			createdAtEpochMs: 1,
			updatedAtEpochMs: 1,
		};
		this.emit('ready', { connectionEpoch: this.#connectionEpoch, serverInstanceId: this.#serverInstanceId, registry: [profile] });
	}

	startGoal(goal = this.#scenario.goal ?? 'gather wood and craft a wooden pickaxe', revision = 1) {
		this.#record.goalRevision = revision;
		this.#record.goal = goal;
		const fields = {
			originalRequest: goal,
			predicate: this.#scenario.goalPredicate ?? { type: 'inventory_contains', itemId: 'minecraft:wooden_pickaxe', count: 1 },
			createdAtTick: revision,
		};
		this.#record.goalSpec = { ...fields, fingerprint: goalSpecFingerprint(fields) };
		const control = {
			agentId: this.#record.agentId,
			payload: { operation: 'start', goalRevision: revision, goal, goalSpec: this.#record.goalSpec, updatedAtEpochMs: revision },
		};
		this.goalControls.push(structuredClone(control));
		this.emit('goal_control', { ...control, connectionEpoch: this.#connectionEpoch });
		return this.publishObservation({ attention: true });
	}

	async send(type, agentId, payload) {
		const envelope = createProtocolV2Envelope({
			serverInstanceId: this.#serverInstanceId,
			agentId,
			type,
			messageId: `native-fault-out-${this.sent.length + 1}`,
			payload,
		});
		validateProtocolV2Envelope(envelope, { direction: 'coordinator_to_server' });
		this.validatedOutbound += 1;
		this.sent.push({ type, agentId, payload: envelope.payload, connectionEpoch: this.#connectionEpoch });
		if (type === 'planning_state') this.states.push(envelope.payload.state);
		if (type === 'agent_error') this.recoveries.push(envelope.payload.code);
		if (type === 'agent_error' && this.#scenario.unsupportedProfile) {
			queueMicrotask(() => this.#emitGoalControl('fail', this.#record.goalRevision + 1));
		}
		if (type === 'request_observation') {
			this.recoveries.push('REQUEST_OBSERVATION');
			this.recoveryHandles += 1;
			this.maxRecoveryHandles = Math.max(this.maxRecoveryHandles, this.recoveryHandles);
			this.recoveryDispatches += 1;
			queueMicrotask(() => {
				try { this.publishObservation({ attention: true }); }
				finally { this.recoveryHandles = Math.max(0, this.recoveryHandles - 1); }
			});
		}
		if (type === 'inspection_request') {
			const dispatchEpoch = this.#connectionEpoch;
			queueMicrotask(() => {
				if (dispatchEpoch !== this.#connectionEpoch || envelope.payload.goalRevision !== this.#record.goalRevision) return;
				const { requestId, goalRevision, query } = envelope.payload;
				if (query.section !== 'observation') {
					this.#emitInbound('inspection_result', { requestId, goalRevision, error: { code: 'UNSUPPORTED_INSPECTION', message: 'This fault fixture supports fresh observations only' } }, dispatchEpoch);
					return;
				}
				const observation = structuredClone(this.publishObservation({ attention: false }));
				this.#emitInbound('inspection_result', { requestId, goalRevision, result: { section: 'observation', eventSequence: observation.eventSequence, observation } }, dispatchEpoch);
			});
		}
		if (type === 'action_command') {
			const dispatchEpoch = this.#connectionEpoch;
			queueMicrotask(() => { void this.#executeAction(envelope.payload, dispatchEpoch); });
		}
		if (type === 'goal_completed') queueMicrotask(() => { void this.#completeGoal(envelope.payload); });
		if (type === 'action_cancel') this.recoveries.push('ACTION_CANCELLED');
	}

	publishObservation({ attention = true, revision = this.#record.goalRevision, stale = false } = {}) {
		const observedRevision = stale ? Math.max(0, revision - 1) : revision;
		const payload = wireObservation(this.#world, observedRevision, ++this.#eventSequence, attention);
		const envelope = createProtocolV2Envelope({
			serverInstanceId: this.#serverInstanceId,
			agentId: this.#record.agentId,
			type: 'observation',
			messageId: `native-fault-in-${this.#eventSequence}`,
			payload,
		});
		validateProtocolV2Envelope(envelope, { direction: 'server_to_coordinator' });
		this.validatedInbound += 1;
		if (this.#connected && !this.#stopped) this.emit('observation', { agentId: this.#record.agentId, payload: envelope.payload, connectionEpoch: this.#connectionEpoch });
		return envelope.payload;
	}

	pause() {
		const revision = this.#record.goalRevision + 1;
		this.#record.goalRevision = revision;
		this.emit('goal_control', {
			agentId: this.#record.agentId,
			payload: { operation: 'stop', goalRevision: revision, updatedAtEpochMs: revision },
			connectionEpoch: this.#connectionEpoch,
		});
	}

	releaseDeferredActionResults() {
		const deferred = [...this.#deferredActionResults.values()];
		this.#deferredActionResults.clear();
		for (const entry of deferred) {
			this.staleActionReplays.push({
				actionId: entry.result.actionId,
				originConnectionEpoch: entry.connectionEpoch,
				replayConnectionEpoch: this.#connectionEpoch,
				goalRevision: entry.result.goalRevision,
			});
			this.#emitInbound('action_result', entry.result, entry.connectionEpoch);
		}
		return deferred.length;
	}

	#emitGoalControl(operation, revision, extra = {}) {
		this.#record.goalRevision = revision;
		const control = {
			agentId: this.#record.agentId,
			payload: { operation, goalRevision: revision, updatedAtEpochMs: revision, ...extra },
		};
		this.goalControls.push(structuredClone(control));
		this.emit('goal_control', { ...control, connectionEpoch: this.#connectionEpoch });
	}

	async #executeAction(command, connectionEpoch) {
		if (!this.#connected || this.#stopped) return;
		this.#actionNumber += 1;
		const actionFault = this.#scenario.actionResults?.[this.#actionNumber - 1] ?? 'SUCCEEDED';
		const actionType = command.actionType;
		const succeeded = actionFault === 'SUCCEEDED';
		const disconnectOutstanding = this.#scenario.disconnectWhileActionOutstandingAtAction === this.#actionNumber;
		if (succeeded) {
			this.#activePhysicalActions += 1;
			this.maxConcurrentPhysicalActions = Math.max(this.maxConcurrentPhysicalActions, this.#activePhysicalActions);
			this.actionAttempts.push({ actionId: command.actionId, actionType, goalRevision: command.goalRevision, connectionEpoch });
			try { this.#applySuccessfulAction(command, connectionEpoch); }
			finally { this.#activePhysicalActions -= 1; }
		}
		const respawned = succeeded && actionType === 'respawn';
		const died = this.#scenario.dieAtAction === this.#actionNumber;
		if (died) {
			this.#world.dead = true;
			this.#world.health = 0;
		}
		const state = succeeded ? 'SUCCEEDED' : 'FAILED';
		const result = {
			traceId: command.traceId,
			goalRevision: command.goalRevision,
			actionId: command.actionId,
			commandId: command.actionId,
			actionType,
			state,
			reasonCode: actionFault,
			message: actionFault,
			elapsedMs: 1,
			observedAtEpochMs: this.#eventSequence,
			executionStarted: true,
			physicalAttempted: true,
		};
		this.#actionResults.push(result);
		this.recoveries.push(actionFault);
		this.#emitInbound('action_progress', {
			traceId: command.traceId,
			goalRevision: command.goalRevision,
			actionId: command.actionId,
			commandId: command.actionId,
			actionType,
			state: 'RUNNING',
			message: 'fixture progress',
			progress: 0.5,
			elapsedMs: 1,
			observedAtEpochMs: this.#eventSequence,
		});
		if (disconnectOutstanding) {
			this.#deferredActionResults.set(command.actionId, { command, result, connectionEpoch });
			this.#disconnectAndResume();
			return;
		}
		this.#emitInbound('action_result', result);
		if (respawned) this.#emitGoalControl('respawn', this.#record.goalRevision, { resumeGoal: true });
		if (died) this.#emitGoalControl('dead', this.#record.goalRevision, { death: {
			cause: 'fixture',
			dimensionId: 'minecraft:overworld',
			x: this.#world.position.x,
			y: this.#world.position.y,
			z: this.#world.position.z,
			respawnDimensionId: 'minecraft:overworld',
			respawnX: this.#world.position.x,
			respawnY: this.#world.position.y,
			respawnZ: this.#world.position.z,
			respawnYaw: 0,
			respawnPitch: 0,
			respawnForced: false,
			gameMode: 'survival',
			diedAtEpochMs: this.#eventSequence,
		} });
		if (this.#scenario.disconnectAtAction === this.#actionNumber) this.#disconnectAndResume();
		if (this.#scenario.staleCallbackAfterRevision && this.#actionNumber === 1) {
			queueMicrotask(() => { this.publishObservation({ revision: command.goalRevision, stale: true }); });
		}
		if (!this.#world.dead && this.#connected) this.publishObservation({ attention: actionFault !== 'SUCCEEDED' });
	}

	async #completeGoal(request) {
		this.#completionNumber += 1;
		const scriptedResult = this.#scenario.completionResults?.[this.#completionNumber - 1];
		const evaluation = this.#evaluateGoalPredicate(this.#record.goalSpec?.predicate);
		const verified = request.goalFingerprint === this.#record.goalSpec?.fingerprint && evaluation.satisfied
			&& (scriptedResult === undefined || scriptedResult === true);
		this.completionEvaluations.push({
			goalRevision: request.goalRevision,
			goalFingerprint: request.goalFingerprint,
			verified,
			facts: structuredClone(evaluation.facts),
		});
		if (!verified) this.recoveries.push('COMPLETION_REJECTED');
		this.#emitInbound('goal_completion_result', {
			goalRevision: request.goalRevision,
			traceId: request.traceId,
			goalFingerprint: request.goalFingerprint,
			verified,
			reasonCode: verified ? 'COMPLETION_VERIFIED' : 'COMPLETION_REJECTED',
			facts: evaluation.facts,
		});
	}

	#applySuccessfulAction(command, connectionEpoch) {
		const actionType = command.actionType;
		const args = command.arguments ?? {};
		let changed = false;
		if (actionType === 'break_block' || actionType === 'mine') {
			changed = this.#world.blocks.delete(`${args.x},${args.y},${args.z}`);
			if (this.#world.drop !== null) {
				const nextX = this.#scenario.moveDropBeforePickup ? 3 : this.#world.drop.x;
				changed ||= nextX !== this.#world.drop.x;
				this.#world.drop = {
					...this.#world.drop,
					x: nextX,
				};
			}
		}
		if (actionType === 'navigate_to') {
			changed = this.#world.position.x !== args.x || this.#world.position.y !== args.y || this.#world.position.z !== args.z;
			this.#world.position = { x: args.x, y: args.y, z: args.z };
		}
		if (actionType === 'pick_up_item') {
			const drop = this.#world.drop;
			if (drop !== null && args.targetSelector === drop.stableId) {
				changed = true;
				this.#world.inventory.set(drop.itemId, (this.#world.inventory.get(drop.itemId) ?? 0) + drop.count);
				this.#world.drop = null;
			}
		}
		if (actionType === 'craft_inventory') {
			changed = true;
			const itemId = args.recipeId ?? 'minecraft:wooden_pickaxe';
			this.#world.inventory.set(itemId, (this.#world.inventory.get(itemId) ?? 0) + (args.count ?? 1));
		}
		if (actionType === 'respawn') {
			changed = this.#world.dead || this.#world.health !== 20;
			this.#world.dead = false;
			this.#world.health = 20;
		}
		if (changed) this.actionEffects.push({ actionId: command.actionId, actionType, goalRevision: command.goalRevision, connectionEpoch });
	}

	#evaluateGoalPredicate(predicate) {
		if (predicate === null || typeof predicate !== 'object') return { satisfied: false, facts: [] };
		if (predicate.type === 'all_of' || predicate.type === 'any_of') {
			const children = predicate.predicates.map((child) => this.#evaluateGoalPredicate(child));
			return {
				satisfied: predicate.type === 'all_of' ? children.every((child) => child.satisfied) : children.some((child) => child.satisfied),
				facts: children.flatMap((child) => child.facts),
			};
		}
		let satisfied = false;
		let expectedValue = predicate.type;
		let observedValue = 'unsupported';
		switch (predicate.type) {
			case 'inventory_contains': {
				const count = this.#world.inventory.get(predicate.itemId) ?? 0;
				satisfied = count >= predicate.count;
				expectedValue = `${predicate.itemId} x${predicate.count}`;
				observedValue = `${predicate.itemId} x${count}`;
				break;
			}
			case 'position_within': {
				const distance = Math.hypot(this.#world.position.x - predicate.x, this.#world.position.y - predicate.y, this.#world.position.z - predicate.z);
				satisfied = distance <= predicate.radius;
				expectedValue = `${predicate.x},${predicate.y},${predicate.z} radius=${predicate.radius}`;
				observedValue = `${this.#world.position.x},${this.#world.position.y},${this.#world.position.z}`;
				break;
			}
			case 'block_matches': {
				const observed = this.#world.blocks.get(`${predicate.x},${predicate.y},${predicate.z}`) ?? 'minecraft:air';
				satisfied = observed === predicate.blockId;
				expectedValue = predicate.blockId;
				observedValue = observed;
				break;
			}
			case 'operator_confirmed':
				expectedValue = 'operator confirmation';
				observedValue = this.#scenario.operatorConfirmed === true ? 'confirmed' : 'not confirmed';
				satisfied = this.#scenario.operatorConfirmed === true;
				break;
			default:
				break;
		}
		return { satisfied, facts: [{ type: predicate.type, satisfied, expectedValue, observedValue }] };
	}

	#entityState(entityId) {
		if (this.#world.drop?.stableId?.toLowerCase() === entityId.toLowerCase()) return 'alive';
		return this.#world.entities.get(entityId.toLowerCase()) ?? null;
	}

	#disconnectAndResume() {
		this.#connected = false;
		this.recoveries.push('BRIDGE_DISCONNECTED');
		this.emit('disconnected', { connectionEpoch: this.#connectionEpoch });
		if (this.#stopped) return;
		const reconnect = { cancelled: false };
		this.#reconnectTimer = reconnect;
		queueMicrotask(() => {
			if (reconnect.cancelled || this.#reconnectTimer !== reconnect) return;
			this.#reconnectTimer = null;
			if (this.#stopped) return;
			const nextRevision = this.#record.goalRevision + 1;
			this.#serverInstanceId = `native-fault-fixture-${nextRevision}`;
			this.ready({ revision: nextRevision, state: 'DISCONNECTED' });
			this.#emitGoalControl('resume', nextRevision + 1, { goal: this.#record.goal ?? this.#scenario.goal ?? 'gather wood and craft a wooden pickaxe' });
			this.publishObservation({ attention: true, revision: this.#record.goalRevision });
		});
	}

	#emitInbound(type, payload, connectionEpoch = this.#connectionEpoch) {
		const envelope = createProtocolV2Envelope({
			serverInstanceId: this.#serverInstanceId,
			agentId: this.#record.agentId,
			type,
			messageId: `native-fault-in-${++this.#eventSequence}`,
			payload,
		});
		validateProtocolV2Envelope(envelope, { direction: 'server_to_coordinator' });
		this.validatedInbound += 1;
		if (type === 'action_result') this.deliveredActionResults.push({ actionId: envelope.payload.actionId, connectionEpoch });
		if (this.#connected && !this.#stopped) this.emit(type, { agentId: this.#record.agentId, payload: envelope.payload, connectionEpoch });
	}
}

function wireObservation(world, goalRevision, eventSequence, attention) {
	const item = world.drop;
	return {
		goalRevision,
		observedAtEpochMs: eventSequence,
		ready: true,
		status: 'ready',
		eventSequence,
		attention,
		changedFacts: attention ? ['inventory', 'entities'] : [],
		position: { ...world.position },
		velocity: { x: 0, y: 0, z: 0 },
		view: { yaw: 0, pitch: 0 },
		player: {
			health: world.health, maxHealth: 20, armor: 0, foodLevel: 20, saturation: 5,
			gameMode: 'survival', onGround: true, inWater: false, onFire: false,
			air: 300, maxAir: 300, suffocating: false, fallDistance: 0, effects: [],
		},
		inventory: {
			items: [...world.inventory].map(([itemId, count], slot) => ({ itemId, count, damage: 0, maxDamage: 0, slot })),
			selectedItem: 'minecraft:air',
		},
		entities: item === null ? [] : [{ uuid: item.stableId, type: 'minecraft:item', name: 'drop', distance: 1, position: { x: item.x, y: item.y, z: item.z }, itemId: item.itemId, count: item.count }],
		blocks: [...world.blocks].map(([key, blockId]) => {
			const [x, y, z] = key.split(',').map(Number);
			return { x, y, z, blockId, placeableFaces: ['up', 'down', 'north', 'south', 'east', 'west'] };
		}),
		nearbyContainers: [],
		world: { dimension: 'minecraft:overworld', gameTime: eventSequence, dayTime: eventSequence, raining: false, thundering: false },
		currentAction: { active: false },
		lastResult: { present: false },
	};
}
