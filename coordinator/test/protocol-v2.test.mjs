import assert from 'node:assert/strict';
import { EventEmitter, once } from 'node:events';
import test from 'node:test';

import { createActionResultReplayProof, createBridgeAuthenticationProof, createProtocolV2Envelope, MultiplexedServerBridge, ProtocolV2Error, validateProtocolV2Envelope, validateProtocolV2Payload } from '../src/protocol-v2.mjs';
import { completionContractFingerprint } from '../src/goal-contract.mjs';
import { goalSpecFingerprint } from '../src/goal-spec.mjs';
import { adaptObservation } from '../src/observation-adapter.mjs';

const SECRET = 's'.repeat(32);
const LAUNCH_ID = '00000000-0000-0000-0000-000000000123';
const TRACE_ID = 'trace-wire-1';
const DESIRED_OAK_STAIRS_STATE = 'minecraft:oak_stairs[facing=north,half=bottom,shape=straight,waterlogged=false]';
const PROVENANCE = Object.freeze({
	provider: 'codex', model: 'gpt-5.6-sol', reasoningEffort: 'high', serviceTier: 'priority',
	programId: 'program-1-1', programVersion: 1, sourceStepId: 'step-80-126', eventSequence: 4,
});

const VERBOSE_STAGES = Object.freeze([
	'conversation', 'lifecycle', 'planner', 'provider', 'output', 'decision',
	'action', 'progress', 'result', 'retry', 'error',
]);

class FakeSocket extends EventEmitter {
	writes = [];
	authenticationWrites = [];
	destroyed = false;
	writable = true;
	paused = false;
	autoAuthenticate;

	constructor({ autoAuthenticate = true } = {}) {
		super();
		this.autoAuthenticate = autoAuthenticate;
	}

	write(value) {
		const encoded = String(value);
		const envelope = JSON.parse(encoded);
		if (envelope.type === 'auth_challenge') {
			this.authenticationWrites.push(encoded);
			if (!this.autoAuthenticate) return this.writable;
			const serverNonce = Buffer.alloc(32, 7).toString('base64url');
			this.emit('data', `${JSON.stringify(serverEnvelope('auth_response', 'server', 'server-auth-response', {
				replyTo: envelope.messageId,
				clientNonce: envelope.payload.clientNonce,
				serverNonce,
				proof: createBridgeAuthenticationProof(SECRET, 'server', {
					clientNonce: envelope.payload.clientNonce,
					serverNonce,
					serverInstanceId: 'server-instance',
				}),
			}))}\n`);
			return this.writable;
		}
		this.writes.push(encoded);
		return this.writable;
	}

	setNoDelay() {}
	pause() { this.paused = true; }
	resume() { this.paused = false; }

	destroy() {
		if (this.destroyed) return;
		this.destroyed = true;
		this.emit('close');
	}
}

test('coordinator rejects an unauthenticated server before sending a secret-derived proof', async (t) => {
	const socket = new FakeSocket({ autoAuthenticate: false });
	const bridge = new MultiplexedServerBridge({ port: 25570, secret: SECRET }, {
		socketFactory: () => socket, schedule: () => 1, cancelSchedule: () => {},
	});
	t.after(() => bridge.stop());
	bridge.start();
	socket.emit('connect');
	const challenge = JSON.parse(socket.authenticationWrites[0]);
	assert.equal(challenge.payload.secret, undefined);
	const rejected = once(bridge, 'protocolError');
	socket.emit('data', `${JSON.stringify(serverEnvelope('auth_response', 'server', 'forged-server-proof', {
		replyTo: challenge.messageId,
		clientNonce: challenge.payload.clientNonce,
		serverNonce: Buffer.alloc(32, 9).toString('base64url'),
		proof: Buffer.alloc(32, 8).toString('base64url'),
	}))}\n`);
	const [error] = await rejected;
	assert.equal(error.code, 'SERVER_AUTHENTICATION_FAILED');
	assert.deepEqual(socket.writes, [], 'no coordinator proof is sent to an unauthenticated peer');
});

class ManualTimerQueue {
	#nextId = 0;
	#timers = new Map();

	schedule = (callback, delay) => {
		const handle = { id: ++this.#nextId };
		this.#timers.set(handle.id, { handle, callback, delay });
		return handle;
	};

	cancel = (handle) => this.#timers.delete(handle?.id);

	pendingId(delay) {
		return [...this.#timers.values()].find((candidate) => candidate.delay === delay)?.handle.id ?? null;
	}

	async runDelay(delay) {
		const timer = [...this.#timers.values()].find((candidate) => candidate.delay === delay);
		if (timer === undefined) throw new Error(`no ${delay}ms timer is pending`);
		this.#timers.delete(timer.handle.id);
		await timer.callback();
	}
}

test('optional launch identity is authenticated without weakening manual coordinators', async () => {
	const clientNonce = Buffer.alloc(32, 1).toString('base64url');
	const serverNonce = Buffer.alloc(32, 2).toString('base64url');
	const proof = Buffer.alloc(32, 3).toString('base64url');
	assert.deepEqual(validateProtocolV2Payload('auth_challenge', { clientNonce }), { clientNonce });
	assert.deepEqual(validateProtocolV2Payload('hello', { replyTo: 'server-auth', clientNonce, serverNonce, proof, launchId: LAUNCH_ID }), {
		replyTo: 'server-auth', clientNonce, serverNonce, proof,
		launchId: LAUNCH_ID,
	});
	assert.deepEqual(validateProtocolV2Payload('hello_ack', {
		replyTo: 'coordinator-v2-1', authenticated: true, registry: [], launchId: LAUNCH_ID,
	}), { replyTo: 'coordinator-v2-1', authenticated: true, registry: [], launchId: LAUNCH_ID });

	const socket = new FakeSocket();
	const bridge = new MultiplexedServerBridge({ port: 25570, secret: SECRET, launchId: LAUNCH_ID }, {
		socketFactory: () => socket,
		schedule: () => 1,
		cancelSchedule: () => {},
		currentRevision: () => 4,
	});
	bridge.start();
	socket.emit('connect');
	const hello = JSON.parse(socket.writes[0]);
	assert.equal(hello.payload.launchId, LAUNCH_ID);
	assert.equal(hello.payload.secret, undefined);
	assert.equal(JSON.parse(socket.authenticationWrites[0]).payload.secret, undefined);
	const ready = once(bridge, 'ready');
	socket.emit('data', `${JSON.stringify(serverEnvelope('hello_ack', 'server', 'server-launch-ack', {
		replyTo: hello.messageId,
		authenticated: true,
		registry: [],
		launchId: LAUNCH_ID,
	}))}\n`);
	const [connection] = await ready;
	assert.equal(connection.launchId, LAUNCH_ID);
	bridge.stop();

	const staleSocket = new FakeSocket();
	const staleBridge = new MultiplexedServerBridge({ port: 25570, secret: SECRET, launchId: LAUNCH_ID }, {
		socketFactory: () => staleSocket,
		schedule: () => 1,
		cancelSchedule: () => {},
		currentRevision: () => 4,
	});
	staleBridge.start();
	staleSocket.emit('connect');
	const staleHello = JSON.parse(staleSocket.writes[0]);
	const rejected = once(staleBridge, 'protocolError');
	staleSocket.emit('data', `${JSON.stringify(serverEnvelope('hello_ack', 'server', 'server-stale-launch-ack', {
		replyTo: staleHello.messageId,
		authenticated: true,
		registry: [],
		launchId: '00000000-0000-0000-0000-000000000999',
	}))}\n`);
	const [error] = await rejected;
	assert.equal(error.code, 'LAUNCH_ID_MISMATCH');
	staleBridge.stop();
});

function serverEnvelope(type, agentId, messageId, payload = {}) {
	return { protocolVersion: 2, serverInstanceId: 'server-instance', agentId, type, messageId, payload };
}

test('verbose control and events use strict authenticated scopes, stages, revisions, and message bounds', () => {
	assert.deepEqual(validateProtocolV2Payload('verbose_control', { enabled: true }), { enabled: true });
	assert.throws(() => validateProtocolV2Payload('verbose_control', { enabled: true, agentId: 'agent-a' }), /field/i);
	assert.throws(() => validateProtocolV2Payload('verbose_control', { enabled: 'true' }), /boolean/i);
	assert.deepEqual(
		validateProtocolV2Envelope(serverEnvelope('verbose_control', 'server', 'verbose-on', { enabled: true }), { direction: 'server_to_coordinator' }).payload,
		{ enabled: true },
	);
	assert.throws(
		() => validateProtocolV2Envelope(serverEnvelope('verbose_control', 'agent-a', 'verbose-agent', { enabled: true }), { direction: 'server_to_coordinator' }),
		/agentId 'server'/i,
	);

	for (const stage of VERBOSE_STAGES) {
		assert.deepEqual(validateProtocolV2Payload('verbose_event', { goalRevision: 4, stage, message: 'Visible progress.' }), {
			goalRevision: 4, stage, message: 'Visible progress.',
		});
	}
	assert.throws(() => validateProtocolV2Payload('verbose_event', { goalRevision: 4, stage: 'reasoning', message: 'hidden' }), /stage/i);
	assert.throws(() => validateProtocolV2Payload('verbose_event', { goalRevision: 4, stage: 'planner', message: 'x'.repeat(257) }), /message/i);
	assert.throws(() => validateProtocolV2Payload('verbose_event', { goalRevision: 4, stage: 'planner', message: 'ok', detail: 'private' }), /field/i);
	assert.throws(
		() => validateProtocolV2Envelope(serverEnvelope('verbose_event', 'server', 'verbose-server', { goalRevision: 4, stage: 'planner', message: 'ok' }), { direction: 'coordinator_to_server' }),
		/agent id|agent scope/i,
	);
});

test('coordinator status is strict, bounded, and excludes private planner data', () => {
	const payload = {
		reconciled: true,
		profiles: [{ agentId: 'agent-a', provider: 'codex', model: 'gpt-5.6-sol', reasoningEffort: 'high' }],
		supportedProfileCount: 1,
		rosterReadyCount: 1,
		rosterCount: 1,
		scheduler: { active: 1, pending: 0, maxConcurrent: 4, maxPending: 12, warning: false },
		circuits: [{ provider: 'codex', model: 'gpt-5.6-sol', operation: 'decide', count: 2, p50Ms: 100, p95Ms: 200, failureRate: 0, circuit: 'closed' }],
	};
	assert.deepEqual(validateProtocolV2Payload('coordinator_status', payload), { ...payload, latencies: [] });
	const extended = {
		...payload,
		profiles: [{ ...payload.profiles[0], serviceTier: 'priority' }],
		bridgeSessionEpoch: 3,
		runtimeGeneration: 'a'.repeat(64),
		components: [{
			component: 'provider:codex', state: 'degraded', fallbackMode: 'last_valid', boundary: 'create',
			failureCode: 'PROVIDER_TIMEOUT', consecutiveFailureCount: 2, nextProbeAtEpochMs: 4_000,
			generation: 3, lastRecoveryAtEpochMs: null,
		}],
	};
	assert.deepEqual(validateProtocolV2Payload('coordinator_status', extended), { ...extended, latencies: [] });
	assert.throws(() => validateProtocolV2Payload('coordinator_status', {
		...extended,
		components: [{ ...extended.components[0], privatePath: 'C:\\private\\secret.txt' }],
	}), /field/i);
	assert.deepEqual(validateProtocolV2Payload('coordinator_status', {
		...payload,
		scheduler: { ...payload.scheduler, active: 3, target: 2 },
	}).scheduler, { ...payload.scheduler, active: 3, target: 2 });
	assert.deepEqual(validateProtocolV2Payload('coordinator_status', {
		...payload,
		scheduler: {
			...payload.scheduler,
			active: 5,
			mode: 'adaptive',
			configuredTarget: 4,
			target: 5,
			minConcurrency: 4,
			maxConcurrency: 16,
		},
	}).scheduler.active, 5);
	assert.throws(() => validateProtocolV2Payload('coordinator_status', {
		...payload,
		scheduler: { ...payload.scheduler, active: 5 },
	}), /counts exceed capacity/i);
	const latency = { operation: 'observation_to_plan', count: 8, p50Ms: 25.25, p95Ms: 80.75 };
	assert.deepEqual(
		validateProtocolV2Payload('coordinator_status', { ...payload, latencies: [latency] }).latencies,
		[latency],
	);
	assert.throws(() => validateProtocolV2Payload('coordinator_status', { ...payload, prompt: 'secret' }), /field/i);
	assert.throws(() => validateProtocolV2Payload('coordinator_status', { ...payload, supportedProfileCount: 2 }), /inconsistent/i);
	assert.throws(() => validateProtocolV2Payload('coordinator_status', { ...payload, profiles: [{ ...payload.profiles[0], provider: 7 }] }), /nonblank/i);
	assert.throws(() => validateProtocolV2Payload('coordinator_status', {
		...payload,
		latencies: [{ ...latency, privatePrompt: 'secret' }],
	}), /field/i);
});

test('protocol v2 carries a revision/profile/trace-bound server goal verification request', () => {
	const goalFingerprint = 'a'.repeat(64);
	const payload = {
		goalRevision: 4,
		goalFingerprint,
		traceId: TRACE_ID,
		profile: { provider: 'codex', model: 'gpt-5.6-sol', reasoningEffort: 'high', serviceTier: 'priority' },
	};
	assert.deepEqual(validateProtocolV2Payload('goal_completed', payload), payload);
	assert.throws(
		() => validateProtocolV2Payload('goal_completed', { goalRevision: 4 }),
		/required/i,
		'goal verification requires the immutable server fingerprint and provenance',
	);
	assert.throws(() => validateProtocolV2Payload('goal_completed', { ...payload, goalFingerprint: 'wrong' }), /goalFingerprint/i);
	const completionResult = {
		goalRevision: 4, traceId: TRACE_ID, goalFingerprint, verified: false, reasonCode: 'PREDICATE_FAILED',
		facts: [
			{ type: 'inventory_contains', satisfied: false, expectedValue: 'minecraft:wooden_pickaxe x1', observedValue: 'minecraft:wooden_pickaxe x0' },
			{ type: 'position_within', satisfied: true, expectedValue: '0.0,64.0,0.0 radius=2.0', observedValue: '0.0,64.0,1.25 stableTicks=2' },
		],
	};
	assert.deepEqual(validateProtocolV2Payload('goal_completion_result', completionResult), completionResult);
	assert.throws(
		() => validateProtocolV2Payload('goal_completion_result', { ...completionResult, facts: Array.from({ length: 17 }, () => completionResult.facts[0]) }),
		/facts/i,
	);
});

test('protocol v2 carries one acknowledged conversation wake transaction', () => {
	const event = {
		sequence: 7, kind: 'player_message', sourceId: 'player-a', recipientId: 'agent-a', scope: 'direct',
		text: 'Can you respond?', goalRevision: 3, observedAtEpochMs: 20,
	};
	const control = { operation: 'start', goalRevision: 4, updatedAtEpochMs: 21, goal: 'Respond to the player.' };
	const payload = { transactionId: 'wake-00000001', event, control };
	assert.deepEqual(validateProtocolV2Payload('conversation_wake', payload), payload);
	assert.deepEqual(
		validateProtocolV2Payload('conversation_wake_ack', { transactionId: payload.transactionId, goalRevision: 4 }),
		{ transactionId: payload.transactionId, goalRevision: 4 },
	);
	assert.throws(
		() => validateProtocolV2Payload('conversation_wake', { ...payload, control: { ...control, goalRevision: 5 } }),
		/revision/i,
		'composite wake revisions must be consecutive',
	);
	assert.throws(
		() => validateProtocolV2Envelope(serverEnvelope('conversation_wake', 'agent-b', 'wake-1', payload), { direction: 'server_to_coordinator' }),
		/recipientId|scope/i,
		'the nested conversation recipient must match the envelope agent',
	);
});

test('protocol v2 carries bounded goal translation requests, proposals, and results', () => {
	const request = {
		requestId: '00000000-0000-0000-0000-000000000201',
		originalRequest: 'Get a good pickaxe',
		candidateIds: ['minecraft:iron_pickaxe', 'minecraft:diamond_pickaxe'],
	};
	const proposal = {
		requestId: request.requestId,
		summary: 'Obtain an iron or diamond pickaxe',
		predicate: {
			type: 'any_of',
			predicates: request.candidateIds.map(itemId => ({ type: 'inventory_contains', itemId, count: 1 })),
		},
	};
	assert.deepEqual(validateProtocolV2Payload('goal_spec_request', request), request);
	assert.deepEqual(validateProtocolV2Payload('goal_spec_proposal', proposal), proposal);
	assert.deepEqual(validateProtocolV2Payload('goal_spec_result', {
		requestId: request.requestId, status: 'accepted', reasonCode: 'PROPOSAL_STAGED',
	}), { requestId: request.requestId, status: 'accepted', reasonCode: 'PROPOSAL_STAGED' });
	assert.throws(() => validateProtocolV2Payload('goal_spec_request', { ...request, extra: true }), /field/i);
	assert.throws(() => validateProtocolV2Payload('goal_spec_proposal', {
		...proposal, predicate: { type: 'action_success_count', count: 1 },
	}), error => error?.code === 'UNKNOWN_GOAL_PREDICATE');
	assert.throws(() => validateProtocolV2Payload('goal_spec_result', {
		requestId: request.requestId, status: 'maybe', reasonCode: 'UNKNOWN',
	}), /status/i);
	assert.equal(validateProtocolV2Envelope(serverEnvelope(
		'goal_spec_request', 'agent-a', 'goal-spec-request-1', request,
	), { direction: 'server_to_coordinator' }).type, 'goal_spec_request');
	assert.throws(() => validateProtocolV2Envelope(serverEnvelope(
		'goal_spec_proposal', 'agent-a', 'goal-spec-proposal-wrong-way', proposal,
	), { direction: 'server_to_coordinator' }), /message type/i);
});

test('goal lifecycle messages retain the full immutable server-authored goal specification', () => {
	const goalSpec = goalSpecFixture({
		originalRequest: 'Get an iron pickaxe',
		predicate: { type: 'inventory_contains', itemId: 'minecraft:iron_pickaxe', count: 1 },
		createdAtTick: 1200,
	});
	const queuedSpec = goalSpecFixture({
		originalRequest: 'Get a diamond pickaxe',
		predicate: { type: 'inventory_contains', itemId: 'minecraft:diamond_pickaxe', count: 1 },
		createdAtTick: 1200,
	});
	const control = validateProtocolV2Payload('goal_control', {
		operation: 'start', goalRevision: 1, updatedAtEpochMs: 2, goal: goalSpec.originalRequest, goalSpec,
	});
	assert.deepEqual(control.goalSpec, goalSpec);
	assert.throws(() => { control.goalSpec.predicate.count = 2; }, TypeError);
	const replacement = validateProtocolV2Payload('goal_control', {
		operation: 'replace', goalRevision: 2, updatedAtEpochMs: 3, goal: goalSpec.originalRequest, goalSpec,
	});
	assert.equal(replacement.operation, 'replace');
	assert.deepEqual(replacement.goalSpec, goalSpec);
	const dequeued = validateProtocolV2Payload('goal_control', {
		operation: 'dequeue', goalRevision: 2, updatedAtEpochMs: 4, goal: queuedSpec.originalRequest, goalSpec: queuedSpec,
	});
	assert.equal(dequeued.operation, 'dequeue');
	assert.deepEqual(dequeued.goalSpec, queuedSpec);
	assert.throws(() => validateProtocolV2Payload('goal_control', {
		operation: 'replace', goalRevision: 2, updatedAtEpochMs: 3,
	}), /requires goal/i);
	assert.throws(() => validateProtocolV2Payload('goal_control', {
		operation: 'dequeue', goalRevision: 2, updatedAtEpochMs: 4,
	}), /requires goal/i);
	const registered = validateProtocolV2Payload('hello_ack', {
		replyTo: 'coordinator-1', authenticated: true,
		registry: [{
			...registeredRecord(), state: 'PAUSED', currentGoal: goalSpec.originalRequest, currentGoalSpec: goalSpec,
			queue: ['Get a diamond pickaxe'], queueGoalSpecs: [queuedSpec],
		}],
	}).registry[0];
	assert.deepEqual(registered.currentGoalSpec, goalSpec);
	assert.equal(registered.queue[0].goalSpec.predicate.itemId, 'minecraft:diamond_pickaxe');
});

function goalSpecFixture(fields) {
	return { ...fields, fingerprint: goalSpecFingerprint(fields) };
}

test('protocol v2 requires immutable provenance on every action command form', () => {
	const payload = {
		traceId: TRACE_ID, goalRevision: 1, actionId: 'action-1', actionType: 'wait', arguments: { durationMs: 25 }, provenance: PROVENANCE,
	};
	const normalized = validateProtocolV2Payload('action_command', payload);
	assert.deepEqual(normalized.provenance, PROVENANCE);
	assert.throws(() => { normalized.provenance.programId = 'forged'; }, TypeError);
	assert.equal(PROVENANCE.programId, 'program-1-1');
	assert.throws(
		() => validateProtocolV2Payload('action_command', { ...payload, provenance: { ...PROVENANCE, traceId: 'trace-other' } }),
		(error) => error.code === 'INVALID_PAYLOAD' && /traceId/.test(error.message),
		'provenance trace IDs cannot diverge from the command trace',
	);
	assert.throws(() => validateProtocolV2Payload('action_command', { ...payload, provenance: undefined }), /provenance/);
	assert.throws(() => validateProtocolV2Payload('action_command', {
		traceId: TRACE_ID,
		goalRevision: 1, actionId: 'action-1', actionType: 'wait', arguments: { durationMs: 25 },
	}), /provenance/);
	for (const alias of ['commandId', 'command', 'type', 'action']) {
		assert.throws(
			() => validateProtocolV2Payload('action_command', { ...payload, [alias]: alias === 'action' ? { type: 'wait' } : 'forged' }),
			(error) => error.code === 'INVALID_PAYLOAD_FIELD',
			`${alias} is not a canonical action command field`,
		);
	}
	const inheritedProvenance = Object.create(PROVENANCE);
	assert.throws(
		() => validateProtocolV2Payload('action_command', { ...payload, provenance: inheritedProvenance }),
		(error) => error.code === 'INVALID_FIELD',
		'custom/inherited provenance objects are rejected',
	);
	assert.throws(
		() => validateProtocolV2Payload('action_command', { ...payload, arguments: { durationMs: 25, type: 'fight_target' } }),
		(error) => error.code === 'INVALID_PAYLOAD_FIELD',
		'arguments cannot override the outer action type',
	);
	const sparse = []; sparse.length = 1;
	assert.throws(
		() => validateProtocolV2Payload('action_command', { ...payload, arguments: { durationMs: 25, extra: sparse } }),
		(error) => error.code === 'INVALID_PAYLOAD' || error.code === 'INVALID_ACTION',
		'sparse/custom arrays are rejected before schema normalization',
	);
});

test('protocol v2 carries a bounded watcher identity with the selected trace', () => {
	const payload = {
		traceId: TRACE_ID, goalRevision: 1, actionId: 'action-watcher', actionType: 'wait', arguments: { durationMs: 25 },
		provenance: { ...PROVENANCE, traceId: TRACE_ID, watcherId: 'watcher-0' },
	};
	assert.equal(validateProtocolV2Payload('action_command', payload).provenance.watcherId, 'watcher-0');
	assert.throws(
		() => validateProtocolV2Payload('action_command', { ...payload, provenance: { ...payload.provenance, traceId: undefined } }),
		/traceId/i,
		'watcher provenance must select a trace',
	);
	assert.throws(
		() => validateProtocolV2Payload('action_command', { ...payload, provenance: { ...payload.provenance, watcherId: 'x'.repeat(129) } }),
		/watcherId/i,
	);
});

test('traced action commands, progress, and results round-trip one bounded trace ID', () => {
	const traceId = 'trace-wire-1';
	const command = validateProtocolV2Payload('action_command', {
		traceId, goalRevision: 1, actionId: 'action-trace-1', actionType: 'wait', arguments: { durationMs: 25 }, provenance: PROVENANCE,
	});
	const progress = validateProtocolV2Payload('action_progress', {
		traceId, goalRevision: 1, actionId: 'action-trace-1', commandId: 'action-trace-1', actionType: 'wait', state: 'RUNNING', progress: 0.5,
	});
	const result = validateProtocolV2Payload('action_result', {
		traceId, goalRevision: 1, actionId: 'action-trace-1', commandId: 'action-trace-1', actionType: 'wait', state: 'SUCCEEDED', reasonCode: 'DONE', message: '', elapsedMs: 10, observedAtEpochMs: 20,
	});
	assert.equal(command.traceId, traceId);
	assert.equal(progress.traceId, traceId);
	assert.equal(result.traceId, traceId);
	for (const type of ['action_command', 'action_progress', 'action_result']) {
		const payload = type === 'action_command' ? { traceId, goalRevision: 1, actionId: 'action-trace-1', actionType: 'wait', arguments: { durationMs: 25 }, provenance: PROVENANCE }
			: type === 'action_progress' ? { traceId, goalRevision: 1, actionId: 'action-trace-1', state: 'RUNNING' }
			: { traceId, goalRevision: 1, actionId: 'action-trace-1', commandId: 'action-trace-1', actionType: 'wait', state: 'SUCCEEDED', reasonCode: 'DONE', message: '', elapsedMs: 10, observedAtEpochMs: 20 };
		assert.throws(() => validateProtocolV2Payload(type, { ...payload, traceId: '' }), /traceId/i, `${type} rejects blank trace IDs`);
		assert.throws(() => validateProtocolV2Payload(type, { ...payload, traceId: '🙂'.repeat(40) }), /traceId/i, `${type} rejects overlong UTF-8 trace IDs`);
	}
	assert.throws(() => validateProtocolV2Payload('action_command', {
		traceId: undefined, goalRevision: 1, actionId: 'action-trace-1', actionType: 'wait', arguments: { durationMs: 25 }, provenance: PROVENANCE,
	}), /traceId/i);
});

test('action observations are optional but strictly validate authoritative movement and mining facts', () => {
	const actionObservation = {
		worldTick: 42,
		observedAtEpochMs: 1_750_000_000_250,
		position: { x: 1.25, y: 64, z: -2.5 },
		velocity: { x: 0, y: 0, z: 0.1 },
		yaw: 90,
		pitch: 12,
		collision: { horizontal: false, vertical: true, inWall: false },
		lookedAt: { type: 'block', position: { x: 2, y: 64, z: -2 }, id: 'minecraft:oak_log', face: 'west', hitDistance: 2.75 },
		reach: { distance: 2.75, max: 4.5, within: true },
		target: {
			kind: 'block', position: { x: 2, y: 64, z: -2 }, expectedId: 'minecraft:oak_log', currentId: 'minecraft:oak_log',
			beforeId: 'minecraft:oak_log', afterId: 'minecraft:oak_log', worldChanged: false,
		},
		progress: { value: 0.25, basis: 'block_damage', verified: true },
	};
	const progress = validateProtocolV2Payload('action_progress', {
		traceId: TRACE_ID, goalRevision: 1, actionId: 'action-observed', actionType: 'mine', progress: 0.25,
		actionObservation,
	});
	const result = validateProtocolV2Payload('action_result', {
		traceId: TRACE_ID, goalRevision: 1, actionId: 'action-observed', commandId: 'action-observed', actionType: 'mine',
		state: 'SUCCEEDED', reasonCode: 'BLOCK_BROKEN', message: '', elapsedMs: 10, observedAtEpochMs: 20,
		actionObservation: { ...actionObservation, target: { ...actionObservation.target, currentId: 'minecraft:air', afterId: 'minecraft:air', worldChanged: true }, progress: { value: 1, basis: 'world_mutation', verified: true } },
	});
	assert.equal(progress.actionObservation.progress.basis, 'block_damage');
	assert.equal(result.actionObservation.target.worldChanged, true);
	assert.throws(() => validateProtocolV2Payload('action_progress', {
		traceId: TRACE_ID, goalRevision: 1, actionId: 'action-observed', actionObservation: { ...actionObservation, progress: { value: 0.5, basis: 'timer', verified: true } },
	}), /basis/i);
	assert.throws(() => validateProtocolV2Payload('action_result', {
		traceId: TRACE_ID, goalRevision: 1, actionId: 'action-observed', commandId: 'action-observed', actionType: 'mine', state: 'SUCCEEDED', reasonCode: 'DONE', message: '', elapsedMs: 10, observedAtEpochMs: 20,
		actionObservation: { ...actionObservation, reach: { distance: -1, max: 4.5, within: false } },
	}), /distance/i);
});

test('terminal result retries are delivered until the application acknowledges them', async (t) => {
	assert.deepEqual(
		validateProtocolV2Payload('action_result_ack', { goalRevision: 4, actionId: 'action-ack-1' }),
		{ goalRevision: 4, actionId: 'action-ack-1' },
	);
	assert.throws(
		() => validateProtocolV2Envelope(serverEnvelope('action_result_ack', 'server', 'ack-server', { goalRevision: 4, actionId: 'action-ack-1' }), { direction: 'coordinator_to_server' }),
		(error) => error.code === 'INVALID_AGENT_SCOPE',
	);

	const socket = new FakeSocket();
	const bridge = new MultiplexedServerBridge({ port: 25570, secret: SECRET }, {
		socketFactory: () => socket,
		schedule: () => 1,
		cancelSchedule: () => {},
		currentRevision: () => 4,
	});
	t.after(() => bridge.stop());
	bridge.start();
	socket.emit('connect');
	const hello = JSON.parse(socket.writes[0]);
	const ready = once(bridge, 'ready');
	socket.emit('data', `${JSON.stringify(serverEnvelope('hello_ack', 'server', 'server-ack-ready', {
		replyTo: hello.messageId, authenticated: true, registry: [registeredRecord()],
	}))}\n`);
	await ready;
	const payload = actionResult('action-ack-1');
	await bridge.send('action_command', 'agent-a', actionCommand('action-ack-1'));
	const delivered = [];
	const errors = [];
	bridge.on('action_result', (event) => {
		delivered.push(event);
		if (delivered.length === 1) event.waitUntil(Promise.reject(new Error('application processing failed')));
	});
	bridge.on('protocolError', (error) => errors.push(error));
	socket.emit('data', `${JSON.stringify(serverEnvelope('action_result', 'agent-a', 'server-result-ack-1', payload))}\n`);
	await new Promise((resolve) => setImmediate(resolve));
	assert.equal(delivered.length, 1);
	socket.emit('data', `${JSON.stringify(serverEnvelope('action_result', 'agent-a', 'server-result-ack-replay', payload))}\n`);
	await new Promise((resolve) => setImmediate(resolve));
	assert.equal(delivered.length, 2, 'an unacknowledged result is retried through application processing');
	assert.equal(socket.writes.filter((line) => JSON.parse(line).type === 'action_result_ack').length, 0);
	await bridge.acknowledgeActionResult('agent-a', payload, { connectionEpoch: 1 });
	assert.equal(JSON.parse(socket.writes.at(-1)).type, 'action_result_ack');
	socket.emit('data', `${JSON.stringify(serverEnvelope('action_result', 'agent-a', 'server-result-ack-after-processing', payload))}\n`);
	await new Promise((resolve) => setImmediate(resolve));
	assert.equal(delivered.length, 2, 'only an application-acknowledged replay is suppressed');
	assert.deepEqual(errors, [], 'acknowledged replay does not tear down the bridge');
	assert.equal(socket.writes.filter((line) => JSON.parse(line).type === 'action_result_ack').length, 2, 'replay is re-acknowledged');
});

test('unacknowledged terminal result replay is redelivered until explicitly acknowledged', async (t) => {
	const socket = new FakeSocket();
	const bridge = new MultiplexedServerBridge({ port: 25570, secret: SECRET }, {
		socketFactory: () => socket,
		schedule: () => 1,
		cancelSchedule: () => {},
		currentRevision: () => 4,
	});
	t.after(() => bridge.stop());
	bridge.start();
	socket.emit('connect');
	const hello = JSON.parse(socket.writes[0]);
	const ready = once(bridge, 'ready');
	socket.emit('data', `${JSON.stringify(serverEnvelope('hello_ack', 'server', 'server-unack-ready', {
		replyTo: hello.messageId, authenticated: true,
		registry: [{ ...registeredRecord(), state: 'STARTING', currentGoal: 'Wait.', goalRevision: 4 }],
	}))}\n`);
	await ready;

	const payload = actionResult('action-unacknowledged');
	await bridge.send('action_command', 'agent-a', actionCommand('action-unacknowledged'));
	const delivered = [];
	bridge.on('action_result', (event) => delivered.push(event));
	socket.emit('data', `${JSON.stringify(serverEnvelope('action_result', 'agent-a', 'server-unack-result-1', payload))}\n`);
	socket.emit('data', `${JSON.stringify(serverEnvelope('action_result', 'agent-a', 'server-unack-result-2', payload))}\n`);
	await new Promise((resolve) => setImmediate(resolve));

	assert.equal(delivered.length, 2, 'a replay remains application-visible until commit is acknowledged');
	assert.equal(socket.writes.filter((line) => JSON.parse(line).type === 'action_result_ack').length, 0);
	await bridge.acknowledgeActionResult('agent-a', payload);
	socket.emit('data', `${JSON.stringify(serverEnvelope('action_result', 'agent-a', 'server-unack-result-3', payload))}\n`);
	await new Promise((resolve) => setImmediate(resolve));
	assert.equal(delivered.length, 2, 'an explicitly acknowledged replay is suppressed');
	assert.equal(socket.writes.filter((line) => JSON.parse(line).type === 'action_result_ack').length, 2, 'the explicit acknowledgement and replay acknowledgement are both sent');
});

test('retained terminal results cross a disconnect revision for application correlation only', async (t) => {
	const socket = new FakeSocket();
	const bridge = new MultiplexedServerBridge({ port: 25570, secret: SECRET }, {
		socketFactory: () => socket,
		schedule: () => 1,
		cancelSchedule: () => {},
		currentRevision: () => 2,
	});
	t.after(() => bridge.stop());
	bridge.start();
	socket.emit('connect');
	const challenge = JSON.parse(socket.authenticationWrites[0]);
	const hello = JSON.parse(socket.writes[0]);
	const ready = once(bridge, 'ready');
	socket.emit('data', `${JSON.stringify(serverEnvelope('hello_ack', 'server', 'server-recovery-ready', {
		replyTo: hello.messageId, authenticated: true,
		registry: [{ ...registeredRecord(), state: 'DISCONNECTED', currentGoal: 'Wait.', goalRevision: 2 }],
	}))}\n`);
	await ready;

	const retainedPayload = sessionProvenActionResult('action-before-disconnect', 1, challenge);
	const retained = once(bridge, 'action_result');
	socket.emit('data', `${JSON.stringify(serverEnvelope(
		'action_result', 'agent-a', 'server-retained-result', retainedPayload,
	))}\n`);
	assert.equal((await retained)[0].payload.goalRevision, 1);
	assert.equal(socket.destroyed, false, 'the application receives the retained result for action correlation');

	const failed = once(bridge, 'protocolError');
	socket.emit('data', `${JSON.stringify(serverEnvelope('action_progress', 'agent-a', 'server-stale-progress', {
		traceId: TRACE_ID, goalRevision: 1, actionId: 'action-before-disconnect', state: 'RUNNING',
	}))}\n`);
	assert.equal((await failed)[0].code, 'STALE_GOAL_REVISION', 'non-terminal stale events remain fenced by the bridge');
	assert.equal(socket.destroyed, true);
});

test('terminal results for actions not issued by the coordinator fail closed', async (t) => {
	const socket = new FakeSocket();
	const bridge = new MultiplexedServerBridge({ port: 25570, secret: SECRET }, {
		socketFactory: () => socket, schedule: () => 1, cancelSchedule: () => {}, currentRevision: () => 4,
	});
	t.after(() => bridge.stop());
	bridge.start();
	socket.emit('connect');
	const hello = JSON.parse(socket.writes[0]);
	socket.emit('data', `${JSON.stringify(serverEnvelope('hello_ack', 'server', 'server-ready-unissued', {
		replyTo: hello.messageId, authenticated: true, registry: [registeredRecord()],
	}))}\n`);
	let delivered = false;
	bridge.on('action_result', () => { delivered = true; });
	const rejected = once(bridge, 'protocolError');
	socket.emit('data', `${JSON.stringify(serverEnvelope('action_result', 'agent-a', 'server-unissued-result', actionResult('server-chosen-action')))}\n`);
	const [error] = await rejected;
	assert.equal(error.code, 'UNISSUED_ACTION_RESULT');
	assert.equal(delivered, false);
	assert.equal(socket.destroyed, true);
});

test('a replacement coordinator accepts only session-proven retained terminal results', async (t) => {
	const socket = new FakeSocket();
	const bridge = new MultiplexedServerBridge({ port: 25570, secret: SECRET }, {
		socketFactory: () => socket, schedule: () => 1, cancelSchedule: () => {}, currentRevision: () => 4,
	});
	t.after(() => bridge.stop());
	bridge.start();
	socket.emit('connect');
	const challenge = JSON.parse(socket.authenticationWrites[0]);
	const hello = JSON.parse(socket.writes[0]);
	const ready = once(bridge, 'ready');
	socket.emit('data', `${JSON.stringify(serverEnvelope('hello_ack', 'server', 'server-ready-retained', {
		replyTo: hello.messageId, authenticated: true, registry: [registeredRecord()],
	}))}\n`);
	await ready;

	const actionId = 'action-before-process-restart';
	const payload = {
		...actionResult(actionId),
		replayProof: createActionResultReplayProof(SECRET, {
			clientNonce: challenge.payload.clientNonce,
			serverNonce: Buffer.alloc(32, 7).toString('base64url'),
			serverInstanceId: 'server-instance',
			agentId: 'agent-a',
			goalRevision: 4,
			actionId,
		}),
	};
	const delivered = once(bridge, 'action_result');
	socket.emit('data', `${JSON.stringify(serverEnvelope('action_result', 'agent-a', 'server-retained-result', payload))}\n`);
	const [message] = await delivered;
	assert.deepEqual(message.payload, payload);
	await bridge.acknowledgeActionResult('agent-a', payload, { connectionEpoch: 1 });
	assert.equal(JSON.parse(socket.writes.at(-1)).type, 'action_result_ack');
});

test('protocol v2 accepts only coordinate-free respawn arguments', () => {
	const payload = {
		traceId: TRACE_ID, goalRevision: 7, actionId: 'respawn-1', actionType: 'respawn', arguments: {}, provenance: PROVENANCE,
	};
	assert.deepEqual(validateProtocolV2Payload('action_command', payload).arguments, {});
	assert.throws(
		() => validateProtocolV2Payload('action_command', { ...payload, arguments: { x: 1, y: 64, z: 1 } }),
		(error) => error.code === 'INVALID_ACTION',
		'respawn never accepts a model supplied position',
	);
});

test('protocol v2 accepts exact death facts only on dead lifecycle control', () => {
	const death = {
		cause: 'fell from a high place', dimensionId: 'minecraft:overworld', x: 12.5, y: 64, z: -4.25,
		respawnDimensionId: 'minecraft:the_nether', respawnX: 4.5, respawnY: 31, respawnZ: -8.5,
		respawnYaw: 37.5, respawnPitch: -12.25, respawnForced: true, gameMode: 'spectator', diedAtEpochMs: 17,
	};
	assert.deepEqual(
		validateProtocolV2Payload('goal_control', { operation: 'dead', goalRevision: 8, updatedAtEpochMs: 18, death }).death,
		death,
	);
	assert.throws(
		() => validateProtocolV2Payload('goal_control', {
			operation: 'dead', goalRevision: 8, updatedAtEpochMs: 18,
			death: { ...death, respawnPitch: undefined },
		}),
		/missing|respawnPitch/i,
		'death facts require every captured respawn field in the bounded wire shape',
	);
	const noConfiguredRespawn = {
		...death,
		respawnDimensionId: null, respawnX: null, respawnY: null, respawnZ: null,
		respawnYaw: null, respawnPitch: null, respawnForced: null,
	};
	assert.deepEqual(
		validateProtocolV2Payload('goal_control', { operation: 'dead', goalRevision: 8, updatedAtEpochMs: 18, death: noConfiguredRespawn }).death,
		noConfiguredRespawn,
		'absence of a configured vanilla respawn remains explicit without inventing a target',
	);
	assert.throws(
		() => validateProtocolV2Payload('goal_control', {
			operation: 'dead', goalRevision: 8, updatedAtEpochMs: 18,
			death: { ...death, respawnX: null },
		}),
		/present together/,
		'partial respawn facts are rejected instead of being completed locally',
	);
	assert.throws(
		() => validateProtocolV2Payload('goal_control', { operation: 'dead', goalRevision: 8, updatedAtEpochMs: 18 }),
		/requires death facts/,
	);
	assert.throws(
		() => validateProtocolV2Payload('goal_control', { operation: 'start', goalRevision: 8, updatedAtEpochMs: 18, goal: 'run', death }),
		/must not include death/,
	);
});

test('goal respawn control accepts only an optional strict resumeGoal flag and defaults it off', () => {
	const control = { operation: 'respawn', goalRevision: 9, updatedAtEpochMs: 20 };
	assert.deepEqual(validateProtocolV2Payload('goal_control', control), { ...control, resumeGoal: false });
	assert.deepEqual(validateProtocolV2Payload('goal_control', { ...control, resumeGoal: true }), { ...control, resumeGoal: true });
	assert.deepEqual(validateProtocolV2Payload('goal_control', { ...control, resumeGoal: false }), { ...control, resumeGoal: false });
	assert.throws(
		() => validateProtocolV2Payload('goal_control', { ...control, resumeGoal: 'true' }),
		/boolean/i,
	);
	assert.throws(
		() => validateProtocolV2Payload('goal_control', { operation: 'resume', goalRevision: 9, updatedAtEpochMs: 20, resumeGoal: true }),
		/resumeGoal|respawn/i,
	);
});

test('hello acknowledgement retains death facts for restart reconciliation', () => {
	const death = {
		cause: 'burned in lava', dimensionId: 'minecraft:the_nether', x: 4.5, y: 31, z: -8.5,
		respawnDimensionId: 'minecraft:overworld', respawnX: 10.5, respawnY: 65, respawnZ: -2.5,
		respawnYaw: 90, respawnPitch: 0, respawnForced: false, gameMode: 'survival', diedAtEpochMs: 23,
	};
	const dead = { ...registeredRecord(), state: 'DEAD', currentGoal: 'Escape the Nether.', goalRevision: 7, death };
	const payload = validateProtocolV2Payload('hello_ack', {
		replyTo: 'coordinator-1', authenticated: true, registry: [dead],
	});
	assert.deepEqual(payload.registry[0].death, death);
	assert.throws(
		() => validateProtocolV2Payload('hello_ack', {
			replyTo: 'coordinator-1', authenticated: true,
			registry: [{ ...dead, death: undefined }],
		}),
		/death facts/,
	);
});

test('goal lifecycle wire text uses Java-compatible UTF-16 code-unit limits', () => {
	for (const accepted of ['x'.repeat(4_096), '\u{1f642}'.repeat(2_048)]) {
		assert.equal(validateProtocolV2Payload('goal_control', {
			operation: 'start', goalRevision: 1, updatedAtEpochMs: 2, goal: accepted,
		}).goal.length, 4_096);
	}
	for (const rejected of ['x'.repeat(4_097), '\u{1f642}'.repeat(2_049)]) {
		assert.throws(() => validateProtocolV2Payload('goal_control', {
			operation: 'start', goalRevision: 1, updatedAtEpochMs: 2, goal: rejected,
		}), /4096/);
	}
});

test('wire registry rejects states that cannot round-trip through the Java agent domain', () => {
	assert.throws(
		() => validateProtocolV2Payload('agent_registered', { ...registeredRecord(), state: 'ACTING' }),
		/ACTING agents require a current goal/,
	);
	assert.throws(
		() => validateProtocolV2Payload('agent_registered', { ...registeredRecord(), currentGoal: 'Impossible.' }),
		/IDLE agents cannot have a current goal/,
	);
	assert.throws(
		() => validateProtocolV2Payload('agent_registered', { ...registeredRecord(), provider: 'kimi', serviceTier: 'fast' }),
		/fast is available only/,
	);
	assert.throws(
		() => validateProtocolV2Payload('agent_registered', { ...registeredRecord(), updatedAtEpochMs: 0 }),
		/timestamps are invalid/,
	);
});

function registeredRecord(agentId = 'agent-a') {
	return {
		schemaVersion: 1,
		agentId,
		model: 'gpt-5.6-sol',
		reasoningEffort: 'high',
		serviceTier: 'fast',
		gameMode: 'survival',
		skinVariant: 'teal',
		state: 'IDLE',
		goalRevision: 0,
		queue: [],
		createdAtEpochMs: 1,
		updatedAtEpochMs: 1,
	};
}

function actionResult(actionId, goalRevision = 4) {
	return {
		traceId: `trace-${actionId}`,
		goalRevision,
		actionId,
		commandId: actionId,
		actionType: 'wait',
		state: 'SUCCEEDED',
		reasonCode: 'DONE',
		message: '',
		elapsedMs: 10,
		observedAtEpochMs: 20,
	};
}

function sessionProvenActionResult(actionId, goalRevision, challenge, agentId = 'agent-a') {
	return {
		...actionResult(actionId, goalRevision),
		replayProof: createActionResultReplayProof(SECRET, {
			clientNonce: challenge.payload.clientNonce,
			serverNonce: Buffer.alloc(32, 7).toString('base64url'),
			serverInstanceId: 'server-instance',
			agentId,
			goalRevision,
			actionId,
		}),
	};
}

function actionCommand(actionId, goalRevision = 4) {
	return {
		traceId: `trace-${actionId}`,
		goalRevision,
		actionId,
		actionType: 'wait',
		arguments: { durationMs: 25 },
		provenance: PROVENANCE,
	};
}

function readyServerObservation(goalRevision = 4) {
	return {
		goalRevision,
		observedAtEpochMs: 20,
		eventSequence: 7,
		attention: false,
		changedFacts: [],
		ready: true,
		status: 'PLANNING',
		position: { x: 10.5, y: 64, z: -3.5 },
		velocity: { x: 0, y: 0, z: 0 },
		view: { yaw: 90, pitch: 0 },
		player: {
			health: 18,
			maxHealth: 20,
			armor: 6,
			foodLevel: 14,
			saturation: 2.5,
			gameMode: 'survival',
			onGround: true,
			inWater: false,
			onFire: false,
			air: 300,
			maxAir: 300,
			suffocating: false,
			fallDistance: 0,
			lastAttacker: {
				uuid: '00000000-0000-0000-0000-000000000001',
				type: 'minecraft:zombie',
				distance: 3.25,
			},
			effects: [{ effectId: 'minecraft:speed', amplifier: 1, duration: 120 }],
		},
		interaction: {
			mainHandItemId: 'minecraft:bread',
			offHandItemId: 'minecraft:shield',
			usingItem: false,
			activeHand: 'none',
			useRemainingTicks: 0,
			attackCooldown: 1,
			input: {
				active: true, forward: 1, strafe: 0, jump: false, sneak: false, sprint: true,
				attack: false, use: false, yaw: 90, pitch: 0, selectedSlot: 2, hand: 'main_hand',
			},
			menu: {
				type: 'minecraft:inventory',
				cursor: { itemId: 'minecraft:air', count: 0 },
				slots: [{ slot: 0, itemId: 'minecraft:air', count: 0 }],
				capabilities: [],
			},
			rayTarget: { type: 'block', x: 11, y: 64, z: -3, face: 'north', blockId: 'minecraft:oak_log' },
		},
		inventory: {
			items: [
				{ itemId: 'minecraft:iron_chestplate', count: 1, damage: 0, maxDamage: 240, slot: 'chest', tags: ['#minecraft:trimmable_armor'] },
				{ itemId: 'minecraft:bread', count: 4, damage: 0, maxDamage: 0, slot: 2, hotbar: true, tags: ['#minecraft:food'] },
			],
			tagCounts: { '#minecraft:trimmable_armor': 1, '#minecraft:food': 4 },
			selectedItem: 'minecraft:bread',
		},
		entities: [{
			uuid: '00000000-0000-0000-0000-000000000001',
			type: 'minecraft:zombie',
			name: 'Zombie',
			distance: 3.25,
			tags: ['#minecraft:hostile'],
			position: { x: 12, y: 64, z: -2 },
		}, {
			uuid: '00000000-0000-0000-0000-000000000002',
			type: 'minecraft:player',
			name: 'Operator',
			distance: 5,
			position: { x: 15, y: 64, z: -3 },
			isPlayer: true,
		}, {
			uuid: '00000000-0000-0000-0000-000000000003',
			type: 'minecraft:item',
			name: 'Oak Log',
			distance: 1.5,
			position: { x: 10.5, y: 64, z: -2.5 },
			itemId: 'minecraft:oak_log',
			count: 1,
			tags: ['#minecraft:item'],
		}],
		blocks: [{
			x: 11, y: 64, z: -3, blockId: 'minecraft:oak_log', placeableFaces: ['up', 'north'], tags: ['#minecraft:logs'],
		}],
		landmarks: [{
			x: 28, y: 66, z: -1, blockId: 'minecraft:oak_log', distance: 17.8, bearing: 24, elevation: 3, tags: ['#minecraft:logs'],
		}],
		nearbyContainers: [{
			x: 12,
			y: 64,
			z: -4,
			blockId: 'minecraft:chest',
			distance: 2.5,
			withinInteractionRange: true,
			capabilities: ['transfer_container'],
		}],
		world: {
			dimension: 'minecraft:overworld',
			gameTime: 200,
			dayTime: 200,
			raining: false,
			thundering: false,
		},
		currentAction: { active: false },
		lastResult: { present: false },
	};
}

test('protocol v2 envelope is strict and directional', () => {
	const message = serverEnvelope('catalog_request', 'server', 'server-1');
	const validated = validateProtocolV2Envelope(message, { direction: 'server_to_coordinator' });
	assert.equal(validated.protocolVersion, 2);
	assert.notStrictEqual(validated, message);
	assert.equal(Object.isFrozen(validated), true);
	assert.equal(Object.isFrozen(validated.payload), true);
	assert.strictEqual(validateProtocolV2Envelope(validated, { direction: 'server_to_coordinator' }), validated);
	assert.throws(() => { validated.payload.changed = true; }, TypeError);
	assert.throws(
		() => validateProtocolV2Envelope({ ...message, unexpected: true }),
		(error) => error instanceof ProtocolV2Error && error.code === 'INVALID_FIELD',
	);
	assert.throws(
		() => validateProtocolV2Envelope(message, { direction: 'coordinator_to_server' }),
		(error) => error.code === 'INVALID_MESSAGE_TYPE',
	);
	const outbound = createProtocolV2Envelope({
		serverInstanceId: 'server-instance', agentId: 'agent-a', type: 'planning_state', messageId: 'planning-1',
		payload: { goalRevision: 1, state: 'thinking' },
	});
	assert.strictEqual(validateProtocolV2Envelope(outbound, { direction: 'coordinator_to_server' }), outbound);
	assert.throws(
		() => validateProtocolV2Envelope(outbound, { direction: 'server_to_coordinator' }),
		(error) => error.code === 'INVALID_MESSAGE_TYPE',
	);
});

test('multiplexed bridge authenticates once and learns the complete registry snapshot', async () => {
	const socket = new FakeSocket();
	const bridge = new MultiplexedServerBridge({ port: 25570, secret: SECRET }, {
		socketFactory: () => socket,
		schedule: () => 1,
		cancelSchedule: () => {},
		currentRevision: () => 4,
	});
	bridge.start();
	socket.emit('connect');
	const hello = JSON.parse(socket.writes[0]);
	assert.equal(hello.type, 'hello');
	assert.equal(hello.payload.secret, undefined);
	const ready = once(bridge, 'ready');
	socket.emit('data', `${JSON.stringify(serverEnvelope('hello_ack', 'server', 'server-1', {
		replyTo: hello.messageId,
		authenticated: true,
		registry: [registeredRecord()],
	}))}\n`);
	const [snapshot] = await ready;
	assert.equal(snapshot.registry[0].reasoningEffort, 'high');
	assert.equal(snapshot.registry[0].serviceTier, 'fast');
	assert.equal(snapshot.registry[0].gameMode, 'survival');
	assert.deepEqual(bridge.knownAgentIds, ['agent-a']);
	await bridge.send('planning_state', 'agent-a', { goalRevision: 4, state: 'PLANNING' });
	assert.equal(JSON.parse(socket.writes.at(-1)).agentId, 'agent-a');
	bridge.stop();
});

test('authenticated inbound work pauses reads and fails closed at aggregate capacity', async (t) => {
	const socket = new FakeSocket();
	const bridge = new MultiplexedServerBridge({
		port: 25570, secret: SECRET, inboundConnectionQueueCap: 2, inboundAgentQueueCap: 2,
	}, {
		socketFactory: () => socket, schedule: () => 1, cancelSchedule: () => {}, currentRevision: () => 4,
	});
	t.after(() => bridge.stop());
	bridge.on('action_progress', (event) => event.waitUntil(new Promise(() => {})));
	bridge.start();
	socket.emit('connect');
	const hello = JSON.parse(socket.writes[0]);
	socket.emit('data', `${JSON.stringify(serverEnvelope('hello_ack', 'server', 'server-ready-backpressure', {
		replyTo: hello.messageId, authenticated: true, registry: [{ ...registeredRecord(), goalRevision: 4 }],
	}))}\n`);
	for (let index = 0; index < 2; index++) {
		socket.emit('data', `${JSON.stringify(serverEnvelope('action_progress', 'agent-a', `progress-${index}`, {
			traceId: TRACE_ID, goalRevision: 4, actionId: `action-${index}`, state: 'RUNNING', progress: 0.5,
		}))}\n`);
	}
	assert.equal(socket.paused, true);
	const rejected = once(bridge, 'protocolError');
	socket.emit('data', `${JSON.stringify(serverEnvelope('action_progress', 'agent-a', 'progress-overflow', {
		traceId: TRACE_ID, goalRevision: 4, actionId: 'action-overflow', state: 'RUNNING', progress: 0.5,
	}))}\n`);
	const [error] = await rejected;
	assert.equal(error.code, 'CONNECTION_INBOUND_BACKPRESSURE');
	assert.equal(socket.destroyed, true);
});

test('unused coordinator wake requests are not part of protocol v2', () => {
	assert.throws(
		() => validateProtocolV2Payload('conversation_wake_request', { goalRevision: 1, kind: 'player_message' }),
		/unsupported protocol v2 payload type/i,
	);
});

test('multiplexed bridge audits validated detached inbound and outbound envelopes', async () => {
	const socket = new FakeSocket();
	const audit = [];
	let receivedObservation;
	const bridge = new MultiplexedServerBridge({ port: 25570, secret: SECRET }, {
		audit: (direction, envelope) => audit.push({ direction, envelope }),
		socketFactory: () => socket, schedule: () => 1, cancelSchedule: () => {}, currentRevision: () => 4,
	});
	bridge.start();
	bridge.on('observation', (envelope) => { receivedObservation = envelope; });
	socket.emit('connect');
	const hello = JSON.parse(socket.writes[0]);
	const ready = once(bridge, 'ready');
	socket.emit('data', `${JSON.stringify(serverEnvelope('hello_ack', 'server', 'server-1', {
		replyTo: hello.messageId, authenticated: true, registry: [registeredRecord()],
	}))}\n`);
	await ready;
	socket.emit('data', `${JSON.stringify(serverEnvelope('observation', 'agent-a', 'server-2', readyServerObservation(4)))}\n`);
	await bridge.send('agent_ready', 'agent-a', { goalRevision: 4 });
	await bridge.send('action_command', 'agent-a', {
		traceId: TRACE_ID,
		goalRevision: 4, actionId: 'action-1', actionType: 'wait', arguments: { durationMs: 25 }, provenance: PROVENANCE,
	});
	assert.deepEqual(audit.map(({ direction, envelope }) => [direction, envelope.messageId, envelope.type, envelope.agentId]), [
		['coordinator_to_server', 'coordinator-v2-1', 'auth_challenge', 'server'],
		['server_to_coordinator', 'server-auth-response', 'auth_response', 'server'],
		['coordinator_to_server', 'coordinator-v2-2', 'hello', 'server'],
		['server_to_coordinator', 'server-1', 'hello_ack', 'server'],
		['server_to_coordinator', 'server-2', 'observation', 'agent-a'],
		['coordinator_to_server', 'coordinator-v2-3', 'agent_ready', 'agent-a'],
		['coordinator_to_server', 'coordinator-v2-4', 'action_command', 'agent-a'],
	]);
	assert.equal(JSON.parse(socket.writes[0]).payload.secret, undefined);
	assert.equal(audit[0].envelope.type, 'auth_challenge');
	assert.equal(audit[0].envelope.payload.secret, undefined);
	assert.doesNotMatch(JSON.stringify(audit), new RegExp(SECRET));
	audit[4].envelope.payload.position.x = 999;
	assert.equal(receivedObservation.payload.position.x, 10.5);
	bridge.stop();
});

test('audit callback failures never interrupt bridge delivery', async () => {
	const socket = new FakeSocket();
	const bridge = new MultiplexedServerBridge({ port: 25570, secret: SECRET }, {
		audit: async () => { throw new Error('audit unavailable'); },
		socketFactory: () => socket, schedule: () => 1, cancelSchedule: () => {}, currentRevision: () => 4,
	});
	bridge.start();
	socket.emit('connect');
	const hello = JSON.parse(socket.writes[0]);
	const ready = once(bridge, 'ready');
	socket.emit('data', `${JSON.stringify(serverEnvelope('hello_ack', 'server', 'server-1', {
		replyTo: hello.messageId, authenticated: true, registry: [registeredRecord()],
	}))}\n`);
	await ready;
	await bridge.send('agent_ready', 'agent-a', { goalRevision: 4 });
	assert.equal(JSON.parse(socket.writes.at(-1)).type, 'agent_ready');
	bridge.stop();
});

test('bridge preserves ready and disconnected events while exposing authenticated recovery', async (t) => {
	const sockets = [];
	let scheduledReconnect = null;
	const bridge = new MultiplexedServerBridge({ port: 25570, secret: SECRET, reconnectDelayMs: 1 }, {
		socketFactory: () => {
			const socket = new FakeSocket();
			sockets.push(socket);
			return socket;
		},
		schedule: (callback) => { scheduledReconnect = callback; return 1; },
		cancelSchedule: () => {},
		currentRevision: () => 4,
	});
	t.after(() => bridge.stop());
	const readyEvents = [];
	const deliveredResults = [];
	const activeRecord = { ...registeredRecord(), goalRevision: 4 };
	const disconnected = once(bridge, 'disconnected');
	bridge.on('ready', (event) => readyEvents.push(event));
	bridge.on('action_result', (event) => deliveredResults.push(event));
	const recovered = once(bridge, 'recovered');

	bridge.start();
	sockets[0].emit('connect');
	const firstHello = JSON.parse(sockets[0].writes[0]);
	sockets[0].emit('data', `${JSON.stringify(serverEnvelope('hello_ack', 'server', 'server-1', {
		replyTo: firstHello.messageId, authenticated: true, registry: [activeRecord],
	}))}\n`);
	await new Promise((resolve) => setImmediate(resolve));
	const reconnectResult = actionResult('action-before-reconnect');
	await bridge.send('action_command', 'agent-a', actionCommand('action-before-reconnect'));
	sockets[0].emit('data', `${JSON.stringify(serverEnvelope('action_result', 'agent-a', 'server-result-before-reconnect', reconnectResult))}\n`);
	await new Promise((resolve) => setImmediate(resolve));
	sockets[0].destroy();
	const [disconnectedEvent] = await disconnected;
	assert.equal(disconnectedEvent.connectionEpoch, 1);
	assert.equal(typeof scheduledReconnect, 'function');

	scheduledReconnect();
	sockets[1].emit('connect');
	const secondHello = JSON.parse(sockets[1].writes[0]);
	sockets[1].emit('data', `${JSON.stringify(serverEnvelope('hello_ack', 'server', 'server-2', {
		replyTo: secondHello.messageId, authenticated: true, registry: [activeRecord],
	}))}\n`);
	const [recovery] = await recovered;

	assert.equal(readyEvents.length, 2);
	assert.deepEqual(readyEvents.map(({ connectionEpoch }) => connectionEpoch), [1, 2]);
	assert.equal(recovery.connectionEpoch, 2);
	assert.equal(recovery.serverInstanceId, 'server-instance');
	assert.equal(recovery.registry.length, 1);
	assert.equal(recovery.registry[0].agentId, 'agent-a');
	sockets[1].emit('data', `${JSON.stringify(serverEnvelope('action_result', 'agent-a', 'server-result-replayed', actionResult('action-before-reconnect')))}\n`);
	await new Promise((resolve) => setImmediate(resolve));
	assert.equal(deliveredResults.length, 2, 'socket-drop replay remains visible until the application acknowledges it');
	await bridge.acknowledgeActionResult('agent-a', actionResult('action-before-reconnect'), { connectionEpoch: 2 });
	assert.equal(JSON.parse(sockets[1].writes.at(-1)).type, 'action_result_ack');
});

test('reconnect accepts retained terminal results for agents absent from the new registry', async (t) => {
	const sockets = [];
	let reconnect = null;
	const bridge = new MultiplexedServerBridge({ port: 25570, secret: SECRET, reconnectDelayMs: 1 }, {
		socketFactory: () => {
			const socket = new FakeSocket();
			sockets.push(socket);
			return socket;
		},
		schedule: (callback) => { reconnect = callback; return 1; },
		cancelSchedule: () => {},
		currentRevision: () => 4,
	});
	t.after(() => bridge.stop());
	bridge.start();
	sockets[0].emit('connect');
	const firstHello = JSON.parse(sockets[0].writes[0]);
	const firstReady = once(bridge, 'ready');
	sockets[0].emit('data', `${JSON.stringify(serverEnvelope('hello_ack', 'server', 'server-retired-first-ready', {
		replyTo: firstHello.messageId, authenticated: true, registry: [registeredRecord()],
	}))}\n`);
	await firstReady;
	sockets[0].destroy();
	await new Promise((resolve) => setImmediate(resolve));
	reconnect();
	sockets[1].emit('connect');
	const secondChallenge = JSON.parse(sockets[1].authenticationWrites[0]);
	const secondHello = JSON.parse(sockets[1].writes[0]);
	const recovered = once(bridge, 'recovered');
	sockets[1].emit('data', `${JSON.stringify(serverEnvelope('hello_ack', 'server', 'server-retired-second-ready', {
		replyTo: secondHello.messageId, authenticated: true, registry: [],
	}))}\n`);
	await recovered;

	const retained = once(bridge, 'action_result');
	sockets[1].emit('data', `${JSON.stringify(serverEnvelope(
		'action_result', 'agent-a', 'server-retired-replay', sessionProvenActionResult('retained-after-reconnect', 4, secondChallenge),
	))}\n`);
	const [event] = await retained;
	await bridge.acknowledgeActionResult(event.agentId, event.payload, { connectionEpoch: event.connectionEpoch });
	assert.equal(sockets[1].destroyed, false);
	assert.equal(JSON.parse(sockets[1].writes.at(-1)).type, 'action_result_ack');
});

test('bridge destroys and reconnects a connected peer that misses the handshake deadline', async (t) => {
	const socket = new FakeSocket();
	const deadlines = new ManualTimerQueue();
	let reconnect = null;
	const bridge = new MultiplexedServerBridge({
		port: 25570,
		secret: SECRET,
		reconnectDelayMs: 7,
		handshakeTimeoutMs: 11,
		heartbeatIntervalMs: 13,
		heartbeatTimeoutMs: 29,
	}, {
		socketFactory: () => socket,
		schedule: (callback) => { reconnect = callback; return 1; },
		cancelSchedule: () => {},
		scheduleDeadline: deadlines.schedule,
		cancelDeadline: deadlines.cancel,
		currentRevision: () => 0,
	});
	t.after(() => bridge.stop());
	const errors = [];
	bridge.on('protocolError', (error) => errors.push(error));
	bridge.start();
	socket.emit('connect');

	await deadlines.runDelay(11);
	assert.equal(socket.destroyed, true);
	assert.equal(errors.at(-1)?.code, 'HANDSHAKE_TIMEOUT');
	assert.equal(typeof reconnect, 'function');
});

test('bridge probes an authenticated peer and reconnects when heartbeat silence reaches its deadline', async (t) => {
	const socket = new FakeSocket();
	const deadlines = new ManualTimerQueue();
	let reconnect = null;
	const bridge = new MultiplexedServerBridge({
		port: 25570,
		secret: SECRET,
		reconnectDelayMs: 7,
		handshakeTimeoutMs: 11,
		heartbeatIntervalMs: 13,
		heartbeatTimeoutMs: 29,
	}, {
		socketFactory: () => socket,
		schedule: (callback) => { reconnect = callback; return 1; },
		cancelSchedule: () => {},
		scheduleDeadline: deadlines.schedule,
		cancelDeadline: deadlines.cancel,
		currentRevision: () => 0,
	});
	t.after(() => bridge.stop());
	const errors = [];
	bridge.on('protocolError', (error) => errors.push(error));
	bridge.start();
	socket.emit('connect');
	const hello = JSON.parse(socket.writes[0]);
	socket.emit('data', `${JSON.stringify(serverEnvelope('hello_ack', 'server', 'server-1', {
		replyTo: hello.messageId, authenticated: true, registry: [registeredRecord()],
	}))}\n`);
	await deadlines.runDelay(13);
	assert.equal(JSON.parse(socket.writes.at(-1)).type, 'heartbeat');

	await deadlines.runDelay(29);
	assert.equal(socket.destroyed, true);
	assert.equal(errors.at(-1)?.code, 'HEARTBEAT_TIMEOUT');
	assert.equal(typeof reconnect, 'function');
});

test('only a heartbeat response refreshes the authenticated peer deadline', async (t) => {
	const socket = new FakeSocket();
	const deadlines = new ManualTimerQueue();
	const bridge = new MultiplexedServerBridge({
		port: 25570,
		secret: SECRET,
		handshakeTimeoutMs: 11,
		heartbeatIntervalMs: 13,
		heartbeatTimeoutMs: 29,
	}, {
		socketFactory: () => socket,
		schedule: () => 1,
		cancelSchedule: () => {},
		scheduleDeadline: deadlines.schedule,
		cancelDeadline: deadlines.cancel,
		currentRevision: () => 0,
	});
	t.after(() => bridge.stop());
	bridge.start();
	socket.emit('connect');
	const hello = JSON.parse(socket.writes[0]);
	const ready = once(bridge, 'ready');
	socket.emit('data', `${JSON.stringify(serverEnvelope('hello_ack', 'server', 'server-heartbeat-ready', {
		replyTo: hello.messageId, authenticated: true, registry: [registeredRecord()],
	}))}\n`);
	await ready;

	const initialDeadline = deadlines.pendingId(29);
	socket.emit('data', `${JSON.stringify(serverEnvelope('verbose_control', 'server', 'server-unrelated-traffic', { enabled: true }))}\n`);
	assert.equal(deadlines.pendingId(29), initialDeadline, 'unrelated inbound traffic cannot mask a failed coordinator-to-server path');

	socket.emit('data', `${JSON.stringify(serverEnvelope('heartbeat', 'server', 'server-heartbeat-response', {}))}\n`);
	assert.notEqual(deadlines.pendingId(29), initialDeadline, 'the server heartbeat response proves the round trip and renews the deadline');
});

test('an application listener failure does not tear down the authenticated transport', async (t) => {
	const socket = new FakeSocket();
	const bridge = new MultiplexedServerBridge({ port: 25570, secret: SECRET }, {
		socketFactory: () => socket,
		schedule: () => 1,
		cancelSchedule: () => {},
		currentRevision: () => 1,
	});
	t.after(() => bridge.stop());
	bridge.start();
	socket.emit('connect');
	const hello = JSON.parse(socket.writes[0]);
	const ready = once(bridge, 'ready');
	socket.emit('data', `${JSON.stringify(serverEnvelope('hello_ack', 'server', 'server-listener-ready', {
		replyTo: hello.messageId, authenticated: true, registry: [registeredRecord()],
	}))}\n`);
	await ready;

	const listenerErrors = [];
	let laterListenerRan = false;
	bridge.on('listenerError', (error, context) => listenerErrors.push({ error, context }));
	bridge.on('action_result', () => { throw new Error('application handler failed'); });
	bridge.on('action_result', () => { laterListenerRan = true; });
	await bridge.send('action_command', 'agent-a', actionCommand('listener-action', 1));
	socket.emit('data', `${JSON.stringify(serverEnvelope('action_result', 'agent-a', 'server-listener-result', actionResult('listener-action', 1)))}\n`);
	await new Promise((resolve) => setImmediate(resolve));

	assert.equal(socket.destroyed, false);
	assert.equal(listenerErrors.length, 1);
	assert.equal(listenerErrors[0].error.message, 'application handler failed');
	assert.equal(listenerErrors[0].context.eventName, 'action_result');
	assert.equal(laterListenerRan, true, 'one failed application listener cannot suppress later listeners');
	assert.equal(socket.writes.some((wire) => JSON.parse(wire).type === 'action_result_ack'), false, 'listener failure leaves the result unacknowledged for replay');
});

test('terminal action retention keeps bounded replay proofs after payload rollover', async (t) => {
	const socket = new FakeSocket();
	const bridge = new MultiplexedServerBridge({ port: 25570, secret: SECRET, trackedTerminalActionIdCap: 2 }, {
		socketFactory: () => socket,
		schedule: () => 1,
		cancelSchedule: () => {},
		currentRevision: () => 0,
	});
	t.after(() => bridge.stop());
	bridge.start();
	socket.emit('connect');
	const hello = JSON.parse(socket.writes[0]);
	socket.emit('data', `${JSON.stringify(serverEnvelope('hello_ack', 'server', 'server-1', {
		replyTo: hello.messageId, authenticated: true, registry: [registeredRecord()],
	}))}\n`);
	const delivered = [];
	bridge.on('action_result', (event) => delivered.push(event));
	for (let index = 0; index < 3; index += 1) {
		const actionId = `action-${index}`;
		await bridge.send('action_command', 'agent-a', actionCommand(actionId, 0));
		socket.emit('data', `${JSON.stringify(serverEnvelope('action_result', 'agent-a', `server-result-${index}`, actionResult(actionId, 0)))}\n`);
	}
	socket.emit('data', `${JSON.stringify(serverEnvelope('action_result', 'agent-a', 'server-result-replay', actionResult('action-0', 0)))}\n`);
	await new Promise((resolve) => setImmediate(resolve));
	assert.equal(delivered.length, 4, 'an exact unacknowledged retired result is restored and redelivered');

	await bridge.acknowledgeActionResult('agent-a', actionResult('action-0', 0));
	for (let index = 3; index < 5; index += 1) {
		const actionId = `action-${index}`;
		await bridge.send('action_command', 'agent-a', actionCommand(actionId, 0));
		socket.emit('data', `${JSON.stringify(serverEnvelope('action_result', 'agent-a', `server-result-${index}`, actionResult(actionId, 0)))}\n`);
	}
	const acknowledgementsBeforeReplay = socket.writes.filter((line) => JSON.parse(line).type === 'action_result_ack').length;
	socket.emit('data', `${JSON.stringify(serverEnvelope('action_result', 'agent-a', 'server-result-acknowledged-replay', actionResult('action-0', 0)))}\n`);
	await new Promise((resolve) => setImmediate(resolve));
	assert.equal(delivered.length, 6, 'an acknowledged retired result is not redelivered');
	assert.equal(
		socket.writes.filter((line) => JSON.parse(line).type === 'action_result_ack').length,
		acknowledgementsBeforeReplay + 1,
		'an acknowledged retired replay is acknowledged idempotently',
	);

	const rejected = once(bridge, 'protocolError');
	socket.emit('data', `${JSON.stringify(serverEnvelope('action_result', 'agent-a', 'server-result-mutated-replay', {
		...actionResult('action-0', 0), message: 'mutated result',
	}))}\n`);
	const [error] = await rejected;
	assert.equal(error.code, 'DUPLICATE_TERMINAL_RESULT');
	assert.equal(socket.destroyed, true, 'a retired identity cannot be reused with a changed terminal payload');
});

test('multiplexed bridge rejects stale revisions before writing', async () => {
	const socket = new FakeSocket();
	const bridge = new MultiplexedServerBridge({ port: 25570, secret: SECRET }, {
		socketFactory: () => socket,
		schedule: () => 1,
		cancelSchedule: () => {},
		currentRevision: () => 9,
	});
	bridge.start();
	socket.emit('connect');
	const hello = JSON.parse(socket.writes[0]);
	const ready = once(bridge, 'ready');
	socket.emit('data', `${JSON.stringify(serverEnvelope('hello_ack', 'server', 'server-1', { replyTo: hello.messageId, authenticated: true, registry: [registeredRecord()] }))}\n`);
	await ready;
	await assert.rejects(bridge.send('action_command', 'agent-a', {
		traceId: TRACE_ID,
		goalRevision: 8,
		actionId: 'action-1',
		actionType: 'wait',
		arguments: { durationMs: 25 },
		provenance: PROVENANCE,
	}), (error) => error.code === 'STALE_GOAL_REVISION');
	await assert.rejects(
		bridge.send('verbose_event', 'agent-a', { goalRevision: 8, stage: 'planner', message: 'Stale planner event.' }),
		(error) => error.code === 'STALE_GOAL_REVISION',
	);
	assert.equal(socket.writes.length, 1);
	bridge.stop();
});

test('an inbound lifecycle revision orders the following observation before async registry work', async () => {
	const socket = new FakeSocket();
	const bridge = new MultiplexedServerBridge({ port: 25570, secret: SECRET }, {
		socketFactory: () => socket,
		schedule: () => 1,
		cancelSchedule: () => {},
		currentRevision: () => 3,
	});
	bridge.start();
	socket.emit('connect');
	const hello = JSON.parse(socket.writes[0]);
	const ready = once(bridge, 'ready');
	socket.emit('data', `${JSON.stringify(serverEnvelope('hello_ack', 'server', 'server-1', {
		replyTo: hello.messageId,
		authenticated: true,
		registry: [{ ...registeredRecord(), state: 'PAUSED', currentGoal: 'Walk east.', goalRevision: 3 }],
	}))}\n`);
	await ready;
	const received = [];
	bridge.on('goal_control', (message) => received.push(message.type));
	bridge.on('observation', (message) => received.push(message.type));
	const lifecycle = serverEnvelope('goal_control', 'agent-a', 'server-2', {
		operation: 'resume', goalRevision: 4, updatedAtEpochMs: 10,
	});
	const observation = serverEnvelope('observation', 'agent-a', 'server-3', {
		goalRevision: 4, observedAtEpochMs: 11, ready: false, status: 'PLAYER_UNAVAILABLE',
	});
	socket.emit('data', `${JSON.stringify(lifecycle)}\n${JSON.stringify(observation)}\n`);
	assert.deepEqual(received, ['goal_control', 'observation']);
	assert.equal(socket.destroyed, false);
	bridge.stop();
});

test('multiplexed bridge bounds queued messages per agent while socket is backpressured', async () => {
	const socket = new FakeSocket();
	const bridge = new MultiplexedServerBridge({ port: 25570, secret: SECRET, connectionQueueCap: 2, agentQueueCap: 1 }, {
		socketFactory: () => socket,
		schedule: () => 1,
		cancelSchedule: () => {},
		currentRevision: () => 1,
	});
	bridge.start();
	socket.emit('connect');
	const hello = JSON.parse(socket.writes[0]);
	const ready = once(bridge, 'ready');
	socket.emit('data', `${JSON.stringify(serverEnvelope('hello_ack', 'server', 'server-1', { replyTo: hello.messageId, authenticated: true, registry: [registeredRecord()] }))}\n`);
	await ready;
	socket.writable = false;
	const blocked = bridge.send('planning_state', 'agent-a', { goalRevision: 1, state: 'PLANNING' });
	const queued = bridge.send('planning_state', 'agent-a', { goalRevision: 1, state: 'PLANNING' });
	await assert.rejects(bridge.send('planning_state', 'agent-a', { goalRevision: 1, state: 'PLANNING' }), (error) => error.code === 'AGENT_BACKPRESSURE');
	socket.writable = true;
	socket.emit('drain');
	await Promise.all([blocked, queued]);
	bridge.stop();
});

test('a backpressured reliable write rejects if the socket disconnects before drain', async (t) => {
	const socket = new FakeSocket();
	const bridge = new MultiplexedServerBridge({ port: 25570, secret: SECRET }, {
		socketFactory: () => socket,
		schedule: () => 1,
		cancelSchedule: () => {},
		currentRevision: () => 1,
	});
	t.after(() => bridge.stop());
	bridge.start();
	socket.emit('connect');
	const hello = JSON.parse(socket.writes[0]);
	const ready = once(bridge, 'ready');
	socket.emit('data', `${JSON.stringify(serverEnvelope('hello_ack', 'server', 'server-backpressure-ready', {
		replyTo: hello.messageId, authenticated: true, registry: [registeredRecord()],
	}))}\n`);
	await ready;

	socket.writable = false;
	const pending = bridge.send('planning_state', 'agent-a', { goalRevision: 1, state: 'PLANNING' });
	let settled = false;
	void pending.then(() => { settled = true; }, () => { settled = true; });
	await new Promise((resolve) => setImmediate(resolve));
	assert.equal(settled, false, 'write acceptance remains pending until the socket drains');

	const rejected = assert.rejects(pending, (error) => error.code === 'BRIDGE_DISCONNECTED');
	socket.destroy();
	await rejected;
});

test('lossy verbose traffic cannot consume control capacity while the socket is backpressured', async (t) => {
	const socket = new FakeSocket();
	const bridge = new MultiplexedServerBridge({ port: 25570, secret: SECRET, connectionQueueCap: 1, agentQueueCap: 1 }, {
		socketFactory: () => socket,
		schedule: () => 1,
		cancelSchedule: () => {},
		currentRevision: () => 1,
	});
	bridge.start();
	t.after(() => bridge.stop());
	socket.emit('connect');
	const hello = JSON.parse(socket.writes[0]);
	const ready = once(bridge, 'ready');
	socket.emit('data', `${JSON.stringify(serverEnvelope('hello_ack', 'server', 'server-1', {
		replyTo: hello.messageId, authenticated: true, registry: [registeredRecord()],
	}))}\n`);
	await ready;

	socket.writable = false;
	const blocked = bridge.send('planning_state', 'agent-a', { goalRevision: 1, state: 'PLANNING' });
	const verboseResults = Array.from({ length: 8 }, (_, index) => bridge.send('verbose_event', 'agent-a', {
		goalRevision: 1, stage: 'output', message: `visible-${index}`,
	}).catch((error) => error));
	const actionResult = bridge.send('action_command', 'agent-a', {
		traceId: TRACE_ID,
		goalRevision: 1,
		actionId: 'action-after-verbose',
		actionType: 'wait',
		arguments: { durationMs: 25 },
		provenance: PROVENANCE,
	}).catch((error) => error);

	socket.writable = true;
	socket.emit('drain');
	await blocked;
	assert.equal((await Promise.all(verboseResults)).some((value) => value instanceof Error), false);
	assert.equal(await actionResult instanceof Error, false);
	const wires = socket.writes.map((wire) => JSON.parse(wire));
	assert.equal(wires.some(({ type }) => type === 'verbose_event'), false, 'blocked verbose events are dropped');
	assert.equal(wires.some(({ type }) => type === 'action_command'), true, 'control traffic keeps the reserved queue slot');
});

test('a newer lifecycle revision removes queued stale action commands under backpressure', async (t) => {
	const socket = new FakeSocket();
	const bridge = new MultiplexedServerBridge({ port: 25570, secret: SECRET }, {
		socketFactory: () => socket,
		schedule: () => 1,
		cancelSchedule: () => {},
		currentRevision: () => 1,
	});
	bridge.start();
	t.after(() => bridge.stop());
	socket.emit('connect');
	const hello = JSON.parse(socket.writes[0]);
	const ready = once(bridge, 'ready');
	socket.emit('data', `${JSON.stringify(serverEnvelope('hello_ack', 'server', 'server-1', { replyTo: hello.messageId, authenticated: true, registry: [registeredRecord()] }))}\n`);
	await ready;

	socket.writable = false;
	const blocked = bridge.send('planning_state', 'agent-a', { goalRevision: 1, state: 'PLANNING' });
	const staleCommand = bridge.send('action_command', 'agent-a', {
		traceId: TRACE_ID,
		goalRevision: 1,
		actionId: 'action-stale',
		actionType: 'wait',
		arguments: { durationMs: 25 },
		provenance: PROVENANCE,
	});
	let staleError = null;
	void staleCommand.catch((error) => { staleError = error; });
	socket.emit('data', `${JSON.stringify(serverEnvelope('goal_control', 'agent-a', 'server-2', {
		operation: 'stop', goalRevision: 2, updatedAtEpochMs: 10,
	}))}\n`);
	await new Promise((resolve) => setImmediate(resolve));
	assert.equal(staleError?.code, 'STALE_GOAL_REVISION');
	socket.writable = true;
	socket.emit('drain');
	await blocked;
	assert.equal(socket.writes.some((wire) => JSON.parse(wire).type === 'action_command'), false);
	bridge.stop();
});

test('a newer lifecycle revision removes queued stale agent readiness under backpressure', async (t) => {
	const socket = new FakeSocket();
	const bridge = new MultiplexedServerBridge({ port: 25570, secret: SECRET }, {
		socketFactory: () => socket,
		schedule: () => 1,
		cancelSchedule: () => {},
		currentRevision: () => 1,
	});
	bridge.start();
	t.after(() => bridge.stop());
	socket.emit('connect');
	const hello = JSON.parse(socket.writes[0]);
	const ready = once(bridge, 'ready');
	socket.emit('data', `${JSON.stringify(serverEnvelope('hello_ack', 'server', 'server-1', { replyTo: hello.messageId, authenticated: true, registry: [registeredRecord()] }))}\n`);
	await ready;

	socket.writable = false;
	const blocked = bridge.send('planning_state', 'agent-a', { goalRevision: 1, state: 'PLANNING' });
	const staleReady = bridge.send('agent_ready', 'agent-a', { goalRevision: 1 });
	let staleError = null;
	void staleReady.catch((error) => { staleError = error; });
	socket.emit('data', `${JSON.stringify(serverEnvelope('goal_control', 'agent-a', 'server-2', {
		operation: 'stop', goalRevision: 2, updatedAtEpochMs: 10,
	}))}\n`);
	await new Promise((resolve) => setImmediate(resolve));
	assert.equal(staleError?.code, 'STALE_GOAL_REVISION');
	socket.writable = true;
	socket.emit('drain');
	await blocked;
	assert.equal(socket.writes.some((wire) => JSON.parse(wire).type === 'agent_ready'), false);
});

test('strict payload validators accept every current wire shape and reject unknown fields', () => {
	const catalog = { refreshedAtEpochMs: 1, models: [{ id: 'gpt-5.6-sol', model: 'gpt-5.6-sol', displayName: 'GPT 5.6 Sol', reasoningEfforts: ['high'], serviceTiers: ['fast'] }] };
	const goalFingerprint = 'a'.repeat(64);
	const clientNonce = Buffer.alloc(32, 1).toString('base64url');
	const serverNonce = Buffer.alloc(32, 2).toString('base64url');
	const proof = Buffer.alloc(32, 3).toString('base64url');
	const messages = [
		['auth_challenge', { clientNonce }],
		['auth_response', { replyTo: 'coordinator-1', clientNonce, serverNonce, proof }],
		['hello', { replyTo: 'server-1', clientNonce, serverNonce, proof }],
		['hello_ack', { replyTo: 'coordinator-1', authenticated: true, registry: [registeredRecord()] }],
		['catalog_request', {}],
		['catalog_snapshot', catalog],
		['agent_registered', registeredRecord()],
		['agent_removed', { goalRevision: 1 }],
		['goal_control', { operation: 'start', goalRevision: 1, updatedAtEpochMs: 2, goal: 'Build shelter.' }],
		['observation', { goalRevision: 1, observedAtEpochMs: 2, ready: false, status: 'ENTITY_UNAVAILABLE' }],
		['action_progress', { traceId: TRACE_ID, goalRevision: 1, actionId: 'action-1', state: 'RUNNING', progress: 0.5 }],
		['action_result', actionResult('action-1', 1)],
		['agent_ready', { goalRevision: 1, reconciled: true }],
		['planning_state', { goalRevision: 1, state: 'PLANNING' }],
		['goal_completed', { goalRevision: 1, goalFingerprint, traceId: TRACE_ID, profile: { provider: 'codex', model: 'gpt-5.6-sol', reasoningEffort: 'high', serviceTier: 'priority' } }],
		['goal_completion_result', { goalRevision: 1, traceId: TRACE_ID, goalFingerprint, verified: false, reasonCode: 'PREDICATE_FAILED', facts: [] }],
		['action_command', { traceId: TRACE_ID, goalRevision: 1, actionId: 'action-1', actionType: 'wait', arguments: { durationMs: 25 }, provenance: PROVENANCE }],
		['action_cancel', { goalRevision: 1, actionId: 'action-1' }],
		['agent_error', { goalRevision: 1, code: 'FAILED', message: 'Planner failed.' }],
		['heartbeat', {}],
		['shutdown', { reason: 'server_stopping' }],
	];
	for (const [type, payload] of messages) assert.doesNotThrow(() => validateProtocolV2Payload(type, payload), type);
	assert.deepEqual(validateProtocolV2Payload('agent_ready', { goalRevision: 2 }), { goalRevision: 2 });
	for (const [type, payload] of messages) assert.throws(() => validateProtocolV2Payload(type, { ...payload, unexpected: true }), (error) => error.code === 'INVALID_PAYLOAD_FIELD', type);
	assert.throws(() => validateProtocolV2Payload('hello_ack', { replyTo: 'x', authenticated: true, registry: Array(1_025).fill(registeredRecord()) }), /at most 1024/);
});

test('action cancellation requires an exact goal revision and action identity', () => {
	assert.deepEqual(
		validateProtocolV2Payload('action_cancel', { goalRevision: 4, actionId: 'action-9' }),
		{ goalRevision: 4, actionId: 'action-9' },
	);
	assert.throws(
		() => validateProtocolV2Payload('action_cancel', { goalRevision: 4 }),
		(error) => error.code === 'MISSING_FIELD',
	);
	assert.throws(
		() => validateProtocolV2Payload('action_cancel', { goalRevision: 4, actionId: 'action-9', reason: 'danger' }),
		(error) => error.code === 'INVALID_PAYLOAD_FIELD',
	);
});

test('post-action observation requests carry only the active goal revision', () => {
	assert.deepEqual(
		validateProtocolV2Payload('request_observation', { goalRevision: 4 }),
		{ goalRevision: 4 },
	);
	assert.throws(
		() => validateProtocolV2Payload('request_observation', { goalRevision: 4, actionId: 'action-9' }),
		(error) => error.code === 'INVALID_PAYLOAD_FIELD',
	);
});

test('event inspection uses bounded pages and an explicit event sequence cursor', () => {
	assert.deepEqual(validateProtocolV2Payload('inspection_request', { goalRevision: 4, requestId: 'inspect-1', query: { section: 'events', afterSequence: 17, limit: 8 } }), {
		goalRevision: 4, requestId: 'inspect-1', query: { section: 'events', afterSequence: 17, offset: 0, limit: 8 },
	});
	for (const query of [{ section: 'events', afterSequence: -2 }, { section: 'events', afterSequence: 0.5 }, { section: 'inventory', afterSequence: 1 }, { section: 'events', limit: 33 }]) {
		assert.throws(() => validateProtocolV2Payload('inspection_request', { goalRevision: 4, requestId: 'inspect-1', query }), ProtocolV2Error);
	}
});

test('player packet observations preserve approximate sounds, visible bars, and event attention', () => {
	const observation = readyServerObservation();
	observation.attention = true;
	observation.changedFacts = ['perception'];
	observation.perception = { latestSequence: 2, earliestSequence: 1, events: [
		{ type: 'sound', sequence: 1, gameTime: 20, observedAtEpochMs: 100, dimension: 'minecraft:overworld', soundId: 'minecraft:entity.zombie.ambient', direction: 'back_left', range: 'near', elevation: 'below' },
		{ type: 'action_bar', sequence: 2, gameTime: 21, observedAtEpochMs: 150, dimension: 'minecraft:overworld', text: 'Door locked', textTruncated: false },
	], bossBars: [{ barId: 'bar-1', name: 'Visible boss', progress: 0.45 }] };
	const normalized = validateProtocolV2Payload('observation', observation);
	assert.deepEqual(normalized.perception, observation.perception);
	assert.deepEqual(normalized.changedFacts, ['perception']);
	const hiddenCoordinate = structuredClone(observation);
	hiddenCoordinate.perception.events[0].x = 123;
	assert.throws(() => validateProtocolV2Payload('observation', hiddenCoordinate), /unknown|field/i);
	const outOfOrder = structuredClone(observation);
	outOfOrder.perception.events[1].sequence = 1;
	assert.throws(() => validateProtocolV2Payload('observation', outOfOrder), /increasing/);
	const badBar = structuredClone(observation);
	badBar.perception.bossBars[0].progress = 2;
	assert.throws(() => validateProtocolV2Payload('observation', badBar), /between 0 and 1/);
});

test('protocol v2 validates raw transaction arguments before normalizing action commands', () => {
	const validTransfer = {
		x: 1, y: 64, z: -2,
		sourceKind: 'player', sourceSlot: 0, destinationKind: 'container', destinationSlot: 4,
		count: 3, expectedItemId: 'minecraft:oak_log', timeoutMs: 5_000,
	};
	const normalized = validateProtocolV2Payload('action_command', {
		traceId: TRACE_ID,
		goalRevision: 4,
		actionId: 'action-transaction-1',
		actionType: 'transfer_container',
		arguments: validTransfer,
		provenance: PROVENANCE,
	});
	assert.deepEqual(normalized.arguments, validTransfer);
	assert.throws(
		() => validateProtocolV2Payload('action_command', {
			traceId: TRACE_ID,
			goalRevision: 4,
			actionId: 'action-transaction-2',
			actionType: 'transfer_container',
			arguments: { ...validTransfer, extra: true },
			provenance: PROVENANCE,
		}),
		(error) => error.code === 'INVALID_ACTION' && /Unknown/.test(error.message),
	);
});

test('protocol v2 preserves nullable desired block state and defers block-id matching to execution', () => {
	const placeArguments = {
		x: 1, y: 64, z: -2, face: 'up', itemId: 'minecraft:oak_stairs', desiredState: DESIRED_OAK_STAIRS_STATE,
	};
	const normalized = validateProtocolV2Payload('action_command', {
		traceId: TRACE_ID,
		goalRevision: 4,
		actionId: 'action-place-1',
		actionType: 'place_block',
		arguments: placeArguments,
		provenance: PROVENANCE,
	});
	assert.deepEqual(normalized.arguments, placeArguments);
	assert.equal(
		validateProtocolV2Payload('action_command', {
			traceId: TRACE_ID,
			goalRevision: 4, actionId: 'action-place-2', actionType: 'place_block',
			arguments: { ...placeArguments, desiredState: null },
			provenance: PROVENANCE,
		}).arguments.desiredState,
		null,
	);
	assert.equal(
		validateProtocolV2Payload('action_command', {
			traceId: TRACE_ID,
			goalRevision: 4, actionId: 'action-place-3', actionType: 'place_block',
			arguments: { ...placeArguments, desiredState: 'minecraft:stone[facing=north]' },
			provenance: PROVENANCE,
		}).arguments.desiredState,
		'minecraft:stone[facing=north]',
	);
	assert.throws(
		() => validateProtocolV2Payload('action_command', {
			traceId: TRACE_ID,
			goalRevision: 4, actionId: 'action-place-4', actionType: 'place_block',
			arguments: { ...placeArguments, desiredState: 'x'.repeat(513) },
			provenance: PROVENANCE,
		}),
		(error) => error.code === 'INVALID_ACTION' && /512/.test(error.message),
	);
});

test('protocol v2 rejects retired high-level controller action types', () => {
	for (const actionType of ['build_sequence', 'fight_target', 'flee_from', 'follow_entity', 'complete_goal']) {
		assert.throws(
			() => validateProtocolV2Payload('action_command', {
				traceId: TRACE_ID,
				goalRevision: 4, actionId: `retired-${actionType}`, actionType, arguments: {}, provenance: PROVENANCE,
			}),
			(error) => error.code === 'INVALID_ACTION' && /Unsupported action/.test(error.message),
		);
	}
});

test('protocol v2 carries an exact observed dropped-item identity to Minecraft', () => {
	const targetSelector = '550e8400-e29b-41d4-a716-446655440000';
	assert.deepEqual(validateProtocolV2Payload('action_command', {
		traceId: TRACE_ID,
		goalRevision: 1,
		actionId: 'pickup-1',
		actionType: 'pick_up_item',
		arguments: { targetSelector },
		provenance: PROVENANCE,
	}), {
		traceId: TRACE_ID,
		goalRevision: 1,
		actionId: 'pickup-1',
		actionType: 'pick_up_item',
		arguments: { targetSelector },
		provenance: PROVENANCE,
	});
});

function currentCollectorObservation() {
	const payload = readyServerObservation();
	Object.assign(payload.player, { pose: 'standing', swimming: false, gliding: false, sprinting: false, crouching: false,
		onClimbable: false, inLava: false, horizontalCollision: false, verticalCollision: false, passenger: false });
	payload.world.worldId = '00000000-0000-0000-0000-000000000999';
	Object.assign(payload.interaction.menu, { containerId: 0, stateId: 1, slotCount: 46, offset: 0, hasMore: true });
	Object.assign(payload.interaction.menu.cursor, { damage: 0, maxDamage: 0, maxStackSize: 64, displayName: 'Air', fingerprint: '' });
	Object.assign(payload.inventory.items[0], { maxStackSize: 1, displayName: 'Iron Chestplate', fingerprint: 'a'.repeat(64) });
	Object.assign(payload.entities[0], { velocity: { x: 0, y: 0, z: 0 }, yaw: 90, pitch: 0, pose: 'standing',
		bounds: { minX: 0, minY: 64, minZ: 0, maxX: 0.6, maxY: 65.95, maxZ: 0.6 }, equipment: [], usingItem: false, onFire: false });
	Object.assign(payload.blocks[0], { state: { axis: 'y' }, bounds: [{ minX: 0, minY: 0, minZ: 0, maxX: 1, maxY: 1, maxZ: 1 }], boundsTruncated: false, replaceable: false });
	payload.coverage = { mode: 'sampled_visible', complete: false, blocksRadius: 6, landmarkDistanceLimit: 256, entitiesDistanceLimit: 128,
		sections: Object.fromEntries(['entities', 'blocks', 'landmarks', 'nearbyContainers'].map((field) => [field, { returned: payload[field].length, complete: false }])) };
	payload.perception = { latestSequence: 0, earliestSequence: 1, events: [], bossBars: [] };
	return payload;
}

test('accepts the exact rich ready observation emitted by ServerObservationCollector', () => {
	const payload = currentCollectorObservation();
	const normalized = validateProtocolV2Payload('observation', payload);
	assert.equal(normalized.eventSequence, 7);
	assert.equal(normalized.attention, false);
	assert.deepEqual(normalized.changedFacts, []);
	assert.equal(Object.hasOwn(normalized.player, 'dangerousFall'), false);
	assert.equal(normalized.player.foodLevel, 14);
	assert.equal(normalized.player.lastAttacker.type, 'minecraft:zombie');
	assert.equal(normalized.player.effects[0].duration, 120);
	assert.equal(normalized.interaction.input.sprint, true);
	assert.equal(normalized.interaction.rayTarget.blockId, 'minecraft:oak_log');
	assert.equal(normalized.inventory.items[0].slot, 'chest');
	assert.equal(normalized.inventory.items[1].slot, 2);
	assert.equal(normalized.inventory.selectedItem, 'minecraft:bread');
	assert.deepEqual(
		Object.keys(normalized.entities[1]).sort(),
		['distance', 'isPlayer', 'name', 'position', 'type', 'uuid'],
		'entity observations expose only visually available identity and position facts',
	);
	assert.equal(normalized.entities[2].itemId, 'minecraft:oak_log');
	assert.equal(normalized.entities[2].count, 1);
	assert.deepEqual(normalized.blocks[0].placeableFaces, ['up', 'north']);
	assert.deepEqual(normalized.landmarks[0], {
		x: 28, y: 66, z: -1, blockId: 'minecraft:oak_log', distance: 17.8, bearing: 24, elevation: 3, tags: ['#minecraft:logs'],
	});
	assert.deepEqual(normalized.nearbyContainers[0].capabilities, ['transfer_container']);
	assert.deepEqual(normalized.interaction.menu.cursor, payload.interaction.menu.cursor);
	const adapted = adaptObservation(normalized);
	assert.equal(adapted.interaction.menu.cursor.maxDamage, 0);
	assert.equal(adapted.interaction.menu.slotCount, 46);
	assert.equal(adapted.player.pose, 'standing');
	assert.equal(adapted.world.worldId, payload.world.worldId);
});

test('menu cursor durability stays typed and dimension changes remain factual', () => {
	const payload = currentCollectorObservation();
	payload.interaction.menu.cursor = { itemId: 'minecraft:iron_sword', count: 1, damage: 12, maxDamage: 250 };
	payload.interaction.menu.slots[0] = { slot: 0, itemId: 'minecraft:iron_sword', count: 1, damage: 13, maxDamage: 250 };
	payload.attention = true;
	payload.changedFacts = ['world.dimension'];
	const adapted = adaptObservation(validateProtocolV2Payload('observation', payload));
	assert.equal(adapted.interaction.menu.cursor.damage, 12);
	assert.equal(adapted.interaction.menu.slots[0].damage, 13);
	for (const damage of [-1, 0.5, '12']) {
		payload.interaction.menu.cursor.damage = damage;
		assert.throws(() => validateProtocolV2Payload('observation', payload), /damage/);
	}
});

test('ready observation requires factual delta metadata and rejects decision labels', () => {
	const payload = readyServerObservation();
	const { eventSequence: _eventSequence, ...withoutEventSequence } = payload;
	assert.throws(() => validateProtocolV2Payload('observation', withoutEventSequence), /eventSequence/i);
	assert.throws(() => validateProtocolV2Payload('observation', { ...payload, changedFacts: ['danger'] }), /changedFacts/i);
	assert.deepEqual(
		validateProtocolV2Payload('observation', { ...payload, attention: true, changedFacts: ['player.health', 'entities.00000000-0000-0000-0000-000000000001'] }).changedFacts,
		['player.health', 'entities.00000000-0000-0000-0000-000000000001'],
	);
});

test('ready observation accepts bounded factual aggregate paths at maximum entity and block churn', () => {
	const payload = readyServerObservation();
	const changedFacts = [
		...Array.from({ length: 64 }, (_value, index) => `entities.00000000-0000-0000-0000-${String(index).padStart(12, '0')}`),
		...Array.from({ length: 128 }, (_value, index) => `blocks.${index},64,0`),
	];
	assert.equal(validateProtocolV2Payload('observation', { ...payload, attention: true, changedFacts }).changedFacts.length, 192);
	assert.deepEqual(
		validateProtocolV2Payload('observation', { ...payload, attention: true, changedFacts: ['entities', 'blocks'] }).changedFacts,
		['entities', 'blocks'],
	);
});

test('delivers the rich server observation without tearing down the authenticated bridge', async () => {
	const socket = new FakeSocket();
	const bridge = new MultiplexedServerBridge({ port: 25570, secret: SECRET }, {
		socketFactory: () => socket,
		schedule: () => 1,
		cancelSchedule: () => {},
		currentRevision: () => 4,
	});
	bridge.start();
	socket.emit('connect');
	const hello = JSON.parse(socket.writes[0]);
	const ready = once(bridge, 'ready');
	socket.emit('data', `${JSON.stringify(serverEnvelope('hello_ack', 'server', 'server-1', {
		replyTo: hello.messageId,
		authenticated: true,
		registry: [registeredRecord()],
	}))}\n`);
	await ready;
	const delivered = once(bridge, 'observation');
	socket.emit('data', `${JSON.stringify(serverEnvelope(
		'observation',
		'agent-a',
		'server-observation-1',
		currentCollectorObservation(),
	))}\n`);
	const [message] = await delivered;
	assert.equal(message.payload.player.foodLevel, 14);
	assert.equal(socket.destroyed, false);
	bridge.stop();
});

test('valid agent_removed is delivered before its identity is removed from the bridge registry', async () => {
	const socket = new FakeSocket();
	const bridge = new MultiplexedServerBridge({ port: 25570, secret: SECRET }, { socketFactory: () => socket, schedule: () => 1, cancelSchedule: () => {}, currentRevision: () => 4 });
	bridge.start();
	socket.emit('connect');
	const hello = JSON.parse(socket.writes[0]);
	const ready = once(bridge, 'ready');
	socket.emit('data', `${JSON.stringify(serverEnvelope('hello_ack', 'server', 'server-1', { replyTo: hello.messageId, authenticated: true, registry: [registeredRecord()] }))}\n`);
	await ready;
	const removed = once(bridge, 'agent_removed');
	socket.emit('data', `${JSON.stringify(serverEnvelope('agent_removed', 'agent-a', 'server-2', { goalRevision: 4 }))}\n`);
	const [message] = await removed;
	assert.equal(message.agentId, 'agent-a');
	assert.deepEqual(bridge.knownAgentIds, []);
	bridge.stop();
});

test('a retained terminal result for a removed agent remains deliverable and acknowledgeable', async (t) => {
	const socket = new FakeSocket();
	const bridge = new MultiplexedServerBridge({ port: 25570, secret: SECRET }, {
		socketFactory: () => socket,
		schedule: () => 1,
		cancelSchedule: () => {},
		currentRevision: () => 4,
	});
	t.after(() => bridge.stop());
	bridge.start();
	socket.emit('connect');
	const hello = JSON.parse(socket.writes[0]);
	const ready = once(bridge, 'ready');
	socket.emit('data', `${JSON.stringify(serverEnvelope('hello_ack', 'server', 'server-retired-ready', {
		replyTo: hello.messageId, authenticated: true, registry: [registeredRecord()],
	}))}\n`);
	await ready;
	await bridge.send('action_command', 'agent-a', actionCommand('retained-action', 4));

	const removed = once(bridge, 'agent_removed');
	socket.emit('data', `${JSON.stringify(serverEnvelope('agent_removed', 'agent-a', 'server-retired-removed', { goalRevision: 4 }))}\n`);
	await removed;
	const retained = once(bridge, 'action_result');
	socket.emit('data', `${JSON.stringify(serverEnvelope(
		'action_result', 'agent-a', 'server-retired-result', actionResult('retained-action', 4),
	))}\n`);
	const [event] = await retained;
	await bridge.acknowledgeActionResult(event.agentId, event.payload, { connectionEpoch: event.connectionEpoch });

	assert.equal(socket.destroyed, false);
	assert.equal(socket.writes.some((wire) => {
		const envelope = JSON.parse(wire);
		return envelope.type === 'action_result_ack' && envelope.payload.actionId === 'retained-action';
	}), true);
});

test('malformed action results fail before terminal-result tracking or delivery', async () => {
	const socket = new FakeSocket();
	const bridge = new MultiplexedServerBridge({ port: 25570, secret: SECRET }, { socketFactory: () => socket, schedule: () => 1, cancelSchedule: () => {}, currentRevision: () => 4 });
	bridge.start();
	socket.emit('connect');
	const hello = JSON.parse(socket.writes[0]);
	const ready = once(bridge, 'ready');
	socket.emit('data', `${JSON.stringify(serverEnvelope('hello_ack', 'server', 'server-1', { replyTo: hello.messageId, authenticated: true, registry: [registeredRecord()] }))}\n`);
	await ready;
	let delivered = false;
	bridge.on('action_result', () => { delivered = true; });
	const failed = once(bridge, 'protocolError');
	socket.emit('data', `${JSON.stringify(serverEnvelope('action_result', 'agent-a', 'server-2', { ...actionResult('action-1'), unexpected: true }))}\n`);
	const [error] = await failed;
	assert.equal(error.code, 'INVALID_PAYLOAD_FIELD');
	assert.equal(delivered, false);
});

test('validates strict targeted conversation events with Unicode code-point limits', () => {
	const payload = {
		sequence: 18,
		kind: 'agent_message',
		sourceId: 'agent-source',
		recipientId: 'agent-a',
		scope: 'direct',
		text: '\ud83d\ude80'.repeat(512),
		goalRevision: 4,
		observedAtEpochMs: 1_787_184_000_000,
	};
	assert.deepEqual(validateProtocolV2Payload('conversation_event', payload), payload);
	assert.throws(() => validateProtocolV2Payload('conversation_event', { ...payload, text: '\ud83d\ude80'.repeat(513) }), /512 code points/);
	assert.throws(() => validateProtocolV2Payload('conversation_event', { ...payload, extra: true }), /Unknown/);
	assert.throws(() => validateProtocolV2Payload('conversation_event', { ...payload, sequence: -1 }), /sequence/);
	assert.throws(() => validateProtocolV2Envelope(serverEnvelope('conversation_event', 'agent-a', 'server-2', { ...payload, recipientId: 'agent-b' }), { direction: 'server_to_coordinator' }), /recipientId/);
});

test('delivers a conversation event once through an authenticated bridge', async () => {
	const socket = new FakeSocket();
	const bridge = new MultiplexedServerBridge(
		{ port: 25570, secret: SECRET },
		{ socketFactory: () => socket, schedule: () => 1, cancelSchedule: () => {}, currentRevision: () => 4 },
	);
	bridge.start();
	socket.emit('connect');
	const hello = JSON.parse(socket.writes[0]);
	const ready = once(bridge, 'ready');
	socket.emit('data', `${JSON.stringify(serverEnvelope('hello_ack', 'server', 'server-1', { replyTo: hello.messageId, authenticated: true, registry: [registeredRecord()] }))}\n`);
	await ready;
	const payload = {
		sequence: 1, kind: 'player_message', sourceId: 'player-a', recipientId: 'agent-a', scope: 'direct',
		text: 'Meet at spawn.', goalRevision: 4, observedAtEpochMs: 1_787_184_000_000,
	};
	const delivered = once(bridge, 'conversation_event');
	socket.emit('data', `${JSON.stringify(serverEnvelope('conversation_event', 'agent-a', 'server-2', payload))}\n`);
	const [message] = await delivered;
	assert.deepEqual(message.payload, payload);
	bridge.stop();
});

test('authenticated bridge accepts same-revision conversation wake replay and its acknowledgement', async () => {
	const socket = new FakeSocket();
	const bridge = new MultiplexedServerBridge({ port: 25570, secret: SECRET }, {
		socketFactory: () => socket,
		schedule: () => 1,
		cancelSchedule: () => {},
		currentRevision: () => 1,
	});
	bridge.start();
	socket.emit('connect');
	const hello = JSON.parse(socket.writes[0]);
	const ready = once(bridge, 'ready');
	socket.emit('data', `${JSON.stringify(serverEnvelope('hello_ack', 'server', 'server-1', {
		replyTo: hello.messageId,
		authenticated: true,
		registry: [{ ...registeredRecord(), state: 'STARTING', currentGoal: 'Respond.', goalRevision: 1 }],
	}))}\n`);
	await ready;
	const payload = {
		transactionId: 'wake-replay-1',
		event: {
			sequence: 1, kind: 'player_message', sourceId: 'player-a', recipientId: 'agent-a', scope: 'direct',
			text: 'Hello?', goalRevision: 0, observedAtEpochMs: 10,
		},
		control: { operation: 'start', goalRevision: 1, updatedAtEpochMs: 11, goal: 'Respond.' },
	};
	const delivered = once(bridge, 'conversation_wake');
	socket.emit('data', `${JSON.stringify(serverEnvelope('conversation_wake', 'agent-a', 'server-2', payload))}\n`);
	assert.deepEqual((await delivered)[0].payload, payload);
	await bridge.send('conversation_wake_ack', 'agent-a', { transactionId: payload.transactionId, goalRevision: 1 });
	assert.equal(JSON.parse(socket.writes.at(-1)).type, 'conversation_wake_ack');
	assert.equal(socket.destroyed, false);
	bridge.stop();
});


test('catalog snapshots carry Cursor Composer and Grok profiles', () => {
	const model = {
		provider: 'cursor', id: 'cursor:composer-2.5', model: 'composer-2.5', displayName: 'Composer 2.5',
		reasoningEfforts: ['low', 'high'], serviceTiers: ['priority', 'fast'],
	};
	assert.deepEqual(
		validateProtocolV2Payload('catalog_snapshot', { refreshedAtEpochMs: 1, models: [model] }).models,
		[model],
	);
});
