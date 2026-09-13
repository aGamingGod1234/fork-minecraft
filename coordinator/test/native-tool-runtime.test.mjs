import assert from 'node:assert/strict';
import test from 'node:test';

import { goalSpecFingerprint } from '../src/goal-spec.mjs';
import { nativeObservationSignature } from '../src/dynamic-main.mjs';
import { constrainGoalBoundNavigation, NativeToolRuntime } from '../src/native-tool-runtime.mjs';
import { ModelNotebook } from '../src/model-notebook.mjs';
import { adaptObservation } from '../src/observation-adapter.mjs';

function record(overrides = {}) {
	const fields = {
		originalRequest: 'get one stone',
		predicate: { type: 'inventory_contains', itemId: 'minecraft:stone', count: 1 },
		createdAtTick: 10,
	};
	return {
		agentId: 'agent-a', provider: 'codex', model: 'gpt-5.6-sol', reasoningEffort: 'xhigh', serviceTier: 'fast',
		goalRevision: 3, currentGoal: 'get one stone', currentGoalSpec: { ...fields, fingerprint: goalSpecFingerprint(fields) }, ...overrides,
	};
}

const testRegistry = { get: (agentId) => record({ agentId }) };

for (const kind of ['action', 'finish']) {
	test(`cancelling ${kind} settles before its bridge publication`, async () => {
		let releasePublication;
		const publication = new Promise((resolve) => { releasePublication = resolve; });
		const runtime = new NativeToolRuntime({ registry: testRegistry, bridge: { send: (type) => type === 'action_cancel' ? Promise.resolve() : publication } });
		let cancellation;
		const pending = runtime.execute({
			agentId: 'agent-a', goalRevision: 3, turnId: 'turn-cancel', callId: 'call-cancel',
			tool: kind === 'action' ? { kind, actionType: 'wait', arguments: { durationMs: 1 } } : { kind, summary: 'done' },
		}, record()).catch((error) => { cancellation = error.code; });
		await runtime.dispose('agent-a', 'bridge_disconnected');
		for (let index = 0; index < 8; index += 1) await Promise.resolve();
		const cancellationBeforeDelivery = cancellation;
		releasePublication();
		await pending;
		assert.match(cancellationBeforeDelivery ?? '', /^NATIVE_(ACTION|COMPLETION)_CANCELLED$/);
	});

	test(`a late ${kind} publication failure cannot erase replacement work`, async () => {
		const published = [];
		let rejectOldPublication;
		const oldPublication = new Promise((resolve, reject) => { rejectOldPublication = reject; });
		const publicationType = kind === 'action' ? 'action_command' : 'goal_completed';
		const runtime = new NativeToolRuntime({ registry: testRegistry, bridge: { send: (type, agentId, payload) => {
			if (type !== publicationType) return Promise.resolve();
			published.push(payload);
			return published.length === 1 ? oldPublication : Promise.resolve();
		} } });
		const request = {
			agentId: 'agent-a', goalRevision: 3, turnId: 'turn-race', callId: 'call-race',
			tool: kind === 'action' ? { kind, actionType: 'wait', arguments: { durationMs: 1 } } : { kind, summary: 'done' },
		};
		const oldResult = runtime.execute(request, record()).catch((error) => error);
		await runtime.dispose('agent-a', 'bridge_disconnected');
		const replacement = runtime.execute({ ...request, callId: 'call-replacement' }, record()).catch((error) => error);
		rejectOldPublication(new Error('old connection failed late'));
		assert.match((await oldResult).code, /^NATIVE_(ACTION|COMPLETION)_CANCELLED$/);
		assert.equal(published.length, 2);
		const accepted = kind === 'action'
			? runtime.onActionResult(record(), { actionId: published[1].actionId, goalRevision: 3, state: 'SUCCEEDED', reasonCode: 'DONE' })
			: runtime.onCompletionResult(record(), { traceId: published[1].traceId, goalFingerprint: published[1].goalFingerprint, goalRevision: 3, verified: false, reasonCode: 'INVENTORY_MISSING', facts: [] });
		assert.equal(accepted, true, 'late failure must only remove its own pending publication');
		assert.equal((await replacement).state, kind === 'action' ? 'SUCCEEDED' : 'ACTIVE');
	});
}

test('native telemetry exceptions cannot interrupt body dispatch or completion', async () => {
	const sent = [];
	const runtime = new NativeToolRuntime({
		registry: testRegistry, bridge: { send: async (...args) => sent.push(args) },
		trace: () => { throw new Error('telemetry unavailable'); },
	});
	const pending = runtime.execute({
		agentId: 'agent-a', goalRevision: 3, turnId: 'turn-telemetry', callId: 'call-telemetry',
		tool: { kind: 'action', actionType: 'wait', arguments: { durationMs: 1 } },
	}, record()).catch((error) => error);
	await Promise.resolve();
	assert.equal(sent.length, 1);
	assert.equal(runtime.onActionProgress(record(), { actionId: sent[0][2].actionId, progress: 0.5 }), true);
	assert.equal(runtime.onActionResult(record(), { actionId: sent[0][2].actionId, state: 'SUCCEEDED', reasonCode: 'DONE' }), true);
	assert.equal((await pending).state, 'SUCCEEDED');
});

test('rejected asynchronous native telemetry never escapes as an unhandled rejection', async () => {
	const sent = [];
	const traces = [];
	const runtime = new NativeToolRuntime({
		registry: testRegistry, bridge: { send: async (...args) => sent.push(args) },
		trace: async (event) => {
			traces.push(event);
			throw new Error('telemetry storage unavailable');
		},
	});
	const pending = runtime.execute({
		agentId: 'agent-a', goalRevision: 3, turnId: 'turn-async-telemetry', callId: 'call-async-telemetry',
		tool: { kind: 'action', actionType: 'wait', arguments: { durationMs: 1 } },
	}, record());
	await new Promise((resolve) => setImmediate(resolve));
	const actionId = sent[0][2].actionId;
	assert.equal(runtime.onActionProgress(record(), { actionId, progress: 0.5 }), true);
	assert.equal(runtime.onActionResult(record(), { actionId, state: 'SUCCEEDED', reasonCode: 'DONE' }), true);
	assert.equal((await pending).state, 'SUCCEEDED');
	await new Promise((resolve) => setImmediate(resolve));
	assert.deepEqual(traces, [
		'native_tool_dispatch_started', 'native_tool_command_sent',
		'native_tool_action_progress', 'native_tool_action_completed',
	]);
});

test('native action is rejected before bridge enqueue when the registry has advanced', async () => {
	const sent = [];
	const runtime = new NativeToolRuntime({
		registry: { get: (agentId) => record({ agentId, goalRevision: 4 }) },
		bridge: { send: async (...args) => sent.push(args) },
	});

	await assert.rejects(runtime.execute({
		agentId: 'agent-a', goalRevision: 3, turnId: 'turn-stale', callId: 'call-stale',
		tool: { kind: 'action', actionType: 'wait', arguments: { durationMs: 1 } },
	}, record()), (error) => error?.code === 'STALE_PLAN');
	assert.equal(sent.some(([type]) => type === 'action_command'), false);
});

test('native body dispatches one correlated action and resolves only its matching result', async () => {
	const sent = [];
	const runtime = new NativeToolRuntime({ registry: testRegistry, bridge: { send: async (...args) => sent.push(args) } });
	runtime.updateObservation(record(), { player: { position: { x: 0, y: 64, z: 0 } }, inventory: [] }, { eventSequence: 8 });

	const result = runtime.execute({
		agentId: 'agent-a', goalRevision: 3, turnId: 'turn-1', callId: 'call-1',
		tool: { kind: 'action', actionType: 'navigate_to', arguments: { x: 2, y: 64, z: 1, tolerance: 1, sprint: true, timeoutMs: 30_000 } },
	}, record());
	await Promise.resolve();
	assert.equal(sent.length, 1);
	assert.equal(sent[0][0], 'action_command');
	assert.equal(sent[0][1], 'agent-a');
	assert.equal(sent[0][2].actionType, 'navigate_to');
	assert.equal(sent[0][2].provenance.model, 'gpt-5.6-sol');
	assert.equal(sent[0][2].provenance.sourceStepId, 'call-1');

	assert.equal(runtime.onActionResult(record(), { goalRevision: 3, actionId: 'wrong', state: 'SUCCEEDED' }), false);
	const actionId = sent[0][2].actionId;
	assert.equal(runtime.onActionResult(record(), { goalRevision: 3, actionId, state: 'SUCCEEDED', reasonCode: '', executionStarted: true }), true);
	assert.deepEqual(await result, { state: 'SUCCEEDED', reasonCode: '', executionStarted: true });
});

test('native action results do not attach stale recovery without a fresh observation', async () => {
	const sent = [];
	const runtime = new NativeToolRuntime({ registry: testRegistry, bridge: { send: async (...args) => sent.push(args) } });
	const current = record();
	runtime.updateObservation(current, {
		player: { x: 0, y: 64, z: 0, dead: false },
		inventory: { items: [{ itemId: 'minecraft:iron_pickaxe', count: 1 }] },
	}, { eventSequence: 1 });
	const pending = runtime.execute({
		agentId: 'agent-a', goalRevision: 3, turnId: 'turn-stale-recovery', callId: 'call-stale-recovery',
		tool: { kind: 'action', actionType: 'wait', arguments: { durationMs: 1 } },
	}, current);
	await Promise.resolve();
	runtime.onActionResult(current, { goalRevision: 3, actionId: sent[0][2].actionId, state: 'SUCCEEDED', reasonCode: 'ACTION_COMPLETED' });
	const result = await pending;
	assert.equal(result.recovery, undefined);
	});
test('native action preserves authoritative progress and terminal observation evidence', async () => {
	const sent = [];
	const traces = [];
	const runtime = new NativeToolRuntime({ registry: testRegistry, bridge: { send: async (...args) => sent.push(args) }, trace: (...args) => traces.push(args) });
	const pending = runtime.execute({
		agentId: 'agent-a', goalRevision: 3, turnId: 'turn-evidence', callId: 'call-evidence',
		tool: { kind: 'action', actionType: 'mine', arguments: { x: 2, y: 64, z: 1, timeoutMs: 10_000 } },
	}, record());
	await Promise.resolve();
	const actionId = sent[0][2].actionId;
	const actionObservation = { worldTick: 9, observedAtEpochMs: 100, target: { kind: 'block', position: { x: 2, y: 64, z: 1 }, currentId: 'minecraft:oak_log' }, progress: { value: 0.25, basis: 'block_damage', verified: true } };
	assert.equal(runtime.onActionProgress(record(), { goalRevision: 3, actionId, progress: 0.25, actionObservation }), true);
	const progressTrace = traces.find(([event]) => event === 'native_tool_action_progress');
	assert.ok(progressTrace);
	const resultObservation = { ...actionObservation, worldTick: 10, target: { ...actionObservation.target, currentId: 'minecraft:air', worldChanged: true }, progress: { value: 1, basis: 'world_mutation', verified: true } };
	assert.equal(runtime.onActionResult(record(), {
		goalRevision: 3, actionId, state: 'SUCCEEDED', reasonCode: 'BLOCK_BROKEN', executionStarted: true, physicalAttempted: true,
		actionObservation: resultObservation,
	}), true);
	assert.deepEqual(await pending, {
		state: 'SUCCEEDED', reasonCode: 'BLOCK_BROKEN', executionStarted: true, physicalAttempted: true, actionObservation: resultObservation,
	});
	assert.deepEqual(progressTrace[1].actionObservation, actionObservation);
});

test('native observe returns latest compact facts without sending a body command', async () => {
	const sent = [];
	const runtime = new NativeToolRuntime({ registry: testRegistry, bridge: { send: async (...args) => sent.push(args) } });
	runtime.updateObservation(record(), { player: { health: 18 }, blocks: [{ blockId: 'minecraft:stone', x: 1, y: 63, z: 1 }] }, { eventSequence: 4 });
	const result = await runtime.execute({ agentId: 'agent-a', goalRevision: 3, turnId: 'turn-1', callId: 'observe-1', tool: { kind: 'observe' } }, record());
	assert.equal(result.freshness.fresh, false);
	assert.equal(result.freshness.reasonCode, 'FRESH_OBSERVATION_UNAVAILABLE');
	delete result.freshness;
	delete result.observation.exploration;
	assert.deepEqual(result, {
		eventSequence: 4,
		goal: 'get one stone',
		goalSpec: record().currentGoalSpec,
		observation: { player: { health: 18 }, blocks: [{ blockId: 'minecraft:stone', x: 1, y: 63, z: 1 }] },
	});
	assert.deepEqual(sent, []);
});

test('identical heartbeat refresh advances sequence without re-ingesting world state', async () => {
	const runtime = new NativeToolRuntime({ bridge: { send: async () => {} } });
	const current = record();
	const conversation = {
		mode: 'history',
		nextSequence: 4,
		entries: [{ sequence: 4, kind: 'agent_message', sourceId: 'agent-b', recipientId: 'agent-a', text: 'hello' }],
	};
	const observation = {
		player: { x: 0, y: 64, z: 0, health: 20, dead: false },
		inventory: { items: [{ itemId: 'minecraft:stone', count: 1 }] },
	};
	runtime.updateObservation(current, observation, { eventSequence: 1, conversation });
	assert.equal(runtime.refreshObservation(current, observation, { eventSequence: 2 }), true);
	const result = await runtime.execute({
		agentId: 'agent-a', goalRevision: 3, turnId: 'turn-refresh', callId: 'observe-refresh', tool: { kind: 'observe' },
	}, current);
	assert.equal(result.eventSequence, 2);
	assert.equal(result.observation.exploration.destination, null);
	delete result.observation.exploration;
	assert.deepEqual(result.observation, {
		...observation,
		recovery: {
			alreadyHave: ['minecraft:stone'],
			alreadyHaveFacts: [{ kind: 'inventory', itemId: 'minecraft:stone', count: 1 }],
			facts: 'Currently evidenced: minecraft:stone.',
		},
	});
	assert.deepEqual(result.conversation, conversation);
	assert.equal(runtime.refreshObservation(current, observation, { eventSequence: 2 }), false, 'duplicate sequence is ignored');
});

test('unchanged actionable heartbeat still refreshes clocks, cooldowns, effects, and live evidence', async () => {
	const runtime = new NativeToolRuntime({ bridge: { send: async () => {} } });
	const current = record();
	const initial = {
		player: { x: 0, y: 64, z: 0, dead: false, effects: [{ id: 'speed', duration: 100 }] },
		inventory: { items: [{ itemId: 'minecraft:stone', count: 1 }] },
		world: { gameTime: 100, dayTime: 100 },
		interaction: { attackCooldown: 0.1, useRemainingTicks: 20 },
	};
	const latest = structuredClone(initial);
	latest.player.effects[0].duration = 90;
	latest.world = { gameTime: 110, dayTime: 110 };
	latest.interaction = { attackCooldown: 0.9, useRemainingTicks: 10 };
	assert.equal(nativeObservationSignature(initial), nativeObservationSignature(latest));
	runtime.updateObservation(current, initial, { eventSequence: 1 });
	assert.equal(runtime.refreshObservation(current, latest, { eventSequence: 2 }), true);
	const result = await runtime.execute({
		agentId: 'agent-a', goalRevision: 3, turnId: 'turn-refresh', callId: 'observe-refresh', tool: { kind: 'observe' },
	}, current);
	assert.equal(result.eventSequence, 2);
	assert.deepEqual(result.observation.world, latest.world);
	assert.deepEqual(result.observation.interaction, latest.interaction);
	assert.deepEqual(result.observation.player.effects, latest.player.effects);
	assert.deepEqual(runtime.snapshotLive(current.agentId), { observation: latest, eventSequence: 2, goalRevision: 3 });
	latest.world.gameTime = 999;
	assert.equal(runtime.snapshotLive(current.agentId).observation.world.gameTime, 110, 'snapshot owns its raw facts');
});

test('lookAround turns the real player in bounded steps and preserves the observed hand and slot', async () => {
	const sent = [];
	const runtime = new NativeToolRuntime({ bridge: { send: async (...args) => sent.push(args) } });
	runtime.updateObservation(record(), {
		interaction: { input: { selectedSlot: 3, hand: 'off_hand' } },
	}, { eventSequence: 1 });
	const pending = runtime.execute({
		agentId: 'agent-a', goalRevision: 3, turnId: 'turn-look', callId: 'look-1',
		tool: { kind: 'lookAround', centerYaw: 0, pitch: 5, steps: 4, ticksPerStep: 2 },
	}, record());
	for (const [index, yaw] of [90, 180, -90, 0].entries()) {
		await new Promise((resolve) => setImmediate(resolve));
		assert.equal(sent.length, index + 1);
		assert.deepEqual(sent[index][2].arguments, {
			forward: 0, strafe: 0, jump: false, sneak: false, sprint: false,
			attack: false, use: false, yaw, pitch: 5, selectedSlot: 3, hand: 'off', ticks: 2,
		});
		runtime.onActionResult(record(), { actionId: sent[index][2].actionId, state: 'SUCCEEDED', reasonCode: '' });
	}
	assert.deepEqual(await pending, {
		state: 'SUCCEEDED', completed: 4,
		results: [0, 1, 2, 3].map((index) => ({ actionType: 'control', state: 'SUCCEEDED', reasonCode: '' })),
	});
});

test('native lifecycle disposal cancels an outstanding body action and rejects the tool', async () => {
	const sent = [];
	const runtime = new NativeToolRuntime({ registry: testRegistry, bridge: { send: async (...args) => sent.push(args) } });
	const pending = runtime.execute({
		agentId: 'agent-a', goalRevision: 3, turnId: 'turn-1', callId: 'call-1',
		tool: { kind: 'action', actionType: 'wait', arguments: { durationMs: 1_000 } },
	}, record());
	await Promise.resolve();
	await runtime.dispose('agent-a', 'goal_steered');
	await assert.rejects(pending, (error) => error?.code === 'NATIVE_ACTION_CANCELLED');
	assert.equal(sent.at(-1)[0], 'action_cancel');
	assert.equal(runtime.isActionResultStale(record(), { goalRevision: 3, actionId: sent[0][2].actionId }), true);
	assert.equal(runtime.isActionResultStale(record(), { goalRevision: 3, actionId: 'unknown' }), false);
});

test('native finish asks Minecraft to verify the immutable server goal before reporting success', async () => {
	const sent = [];
	const finished = [];
	const runtime = new NativeToolRuntime({
		bridge: { send: async (...args) => sent.push(args) },
		onFinish: async (request) => finished.push(request),
	});
	const pending = runtime.execute({
		agentId: 'agent-a', goalRevision: 3, turnId: 'turn-1', callId: 'finish-1',
		tool: { kind: 'finish', summary: 'Stone acquired.' },
	}, record(), { lifecycleGeneration: 7 });
	await Promise.resolve();
	assert.equal(sent[0][0], 'goal_completed');
	assert.equal(sent[0][2].goalFingerprint, record().currentGoalSpec.fingerprint);
	assert.equal(Object.hasOwn(sent[0][2], 'completionContract'), false);
	assert.equal(runtime.onCompletionResult(record(), {
		goalRevision: 3,
		traceId: sent[0][2].traceId,
		goalFingerprint: sent[0][2].goalFingerprint,
		verified: true,
		reasonCode: 'COMPLETION_VERIFIED',
		facts: [{ type: 'inventory_contains', satisfied: true, expectedValue: 'minecraft:stone x1', observedValue: 'minecraft:stone x1' }],
	}), true);
	assert.deepEqual(await pending, {
		state: 'COMPLETED', verified: true, reasonCode: 'COMPLETION_VERIFIED',
		facts: [{ type: 'inventory_contains', satisfied: true, expectedValue: 'minecraft:stone x1', observedValue: 'minecraft:stone x1' }],
	});
	assert.equal(finished.length, 1);
	assert.equal(finished[0].lifecycleGeneration, 7);
});

test('native observe ignores a stale observation event sequence', async () => {
	const runtime = new NativeToolRuntime({ registry: testRegistry, bridge: { send: async () => {} } });
	const current = record();
	runtime.updateObservation(current, { player: { health: 20 } }, { eventSequence: 8 });
	runtime.updateObservation(current, { player: { health: 10 } }, { eventSequence: 7 });
	const result = await runtime.execute({
		agentId: 'agent-a', goalRevision: 3, turnId: 'turn-1', callId: 'observe-stale', tool: { kind: 'observe' },
	}, current);
	assert.equal(result.freshness.fresh, false);
	delete result.freshness;
	delete result.observation.exploration;
	assert.deepEqual(result, {
		eventSequence: 8,
		goal: 'get one stone',
		goalSpec: record().currentGoalSpec,
		observation: { player: { health: 20 } },
	});
});

test('goal-bound navigation cannot succeed outside the immutable position radius', async () => {
	const sent = [];
	const fields = {
		originalRequest: 'Move to 12 64 12',
		predicate: { type: 'position_within', x: 12, y: 64, z: 12, radius: 1, stableTicks: 20 },
		createdAtTick: 10,
	};
	const positioned = record({
		currentGoal: fields.originalRequest,
		currentGoalSpec: { ...fields, fingerprint: goalSpecFingerprint(fields) },
	});
	const runtime = new NativeToolRuntime({ bridge: { send: async (...args) => sent.push(args) } });
	const pending = runtime.execute({
		agentId: 'agent-a', goalRevision: 3, turnId: 'turn-position', callId: 'move-position',
		tool: { kind: 'action', actionType: 'navigate_to', arguments: { x: 12, y: 64, z: 12, tolerance: 4, sprint: true, timeoutMs: 30_000 } },
	}, positioned);
	await Promise.resolve();
	assert.equal(sent[0][2].arguments.tolerance, 1);
	runtime.onActionResult(positioned, {
		goalRevision: 3,
		actionId: sent[0][2].actionId,
		state: 'FAILED',
		reasonCode: 'PATH_BLOCKED',
		message: 'Navigation could not recover from repeated stalls',
		executionStarted: true,
	});
	assert.deepEqual(await pending, {
		state: 'FAILED', reasonCode: 'PATH_BLOCKED',
		message: 'Navigation could not recover from repeated stalls', executionStarted: true,
		failureClass: 'path',
	});
});

test('goal-bound navigation honors a matching position nested in a compound goal', async () => {
	const sent = [];
	const fields = {
		originalRequest: 'Move to 12 64 12 and survive for a minute',
		predicate: {
			type: 'all_of',
			predicates: [
				{ type: 'survive_duration', ticks: 1_200 },
				{ type: 'position_within', x: 12, y: 64, z: 12, radius: 0.01, stableTicks: 20 },
			],
		},
		createdAtTick: 10,
	};
	const positioned = record({
		currentGoal: fields.originalRequest,
		currentGoalSpec: { ...fields, fingerprint: goalSpecFingerprint(fields) },
	});
	const runtime = new NativeToolRuntime({ bridge: { send: async (...args) => sent.push(args) } });
	const pending = runtime.execute({
		agentId: 'agent-a', goalRevision: 3, turnId: 'turn-position', callId: 'move-position',
		tool: { kind: 'action', actionType: 'navigate_to', arguments: { x: 12, y: 64, z: 12, tolerance: 1, sprint: true, timeoutMs: 30_000 } },
	}, positioned);
	await Promise.resolve();
	assert.equal(sent[0][2].arguments.tolerance, 0.01);
	runtime.onActionResult(positioned, {
		goalRevision: 3,
		actionId: sent[0][2].actionId,
		state: 'FAILED',
		reasonCode: 'PATH_BLOCKED',
		executionStarted: true,
	});
	await pending;
});

test('nested position constraints do not clamp navigation to unrelated coordinates', () => {
	const goalSpec = {
		predicate: {
			type: 'any_of',
			predicates: [
				{ type: 'position_within', x: 12, y: 64, z: 12, radius: 0.5, stableTicks: 20 },
				{
					type: 'all_of',
					predicates: [
						{ type: 'position_within', x: 99, y: 70, z: -4, radius: 0.01, stableTicks: 20 },
						{ type: 'survive_duration', ticks: 1_200 },
					],
				},
			],
		},
	};
	const matching = constrainGoalBoundNavigation({
		kind: 'action', actionType: 'navigate_to',
		arguments: { x: 12, y: 64, z: 12, tolerance: 1, sprint: true, timeoutMs: 30_000 },
	}, goalSpec);
	const unrelated = constrainGoalBoundNavigation({
		kind: 'action', actionType: 'navigate_to',
		arguments: { x: 20, y: 64, z: 20, tolerance: 1, sprint: true, timeoutMs: 30_000 },
	}, goalSpec);
	assert.equal(matching.arguments.tolerance, 0.5);
	assert.equal(unrelated.arguments.tolerance, 1);
});

test('a false finish stays active and returns Minecraft evidence to the same turn', async () => {
	const finished = [];
	const sent = [];
	const runtime = new NativeToolRuntime({
		bridge: { send: async (...args) => sent.push(args) },
		onFinish: async (request) => finished.push(request),
	});
	const pending = runtime.execute({
		agentId: 'agent-a', goalRevision: 3, turnId: 'turn-1', callId: 'finish-false',
		tool: { kind: 'finish', summary: 'I made the pickaxe.' },
	}, record());
	await Promise.resolve();
	assert.equal(runtime.onCompletionResult(record(), {
		goalRevision: 3, traceId: sent[0][2].traceId, goalFingerprint: sent[0][2].goalFingerprint,
		verified: false, reasonCode: 'PREDICATE_FAILED',
		facts: [{ type: 'inventory_contains', satisfied: false, expectedValue: 'minecraft:iron_pickaxe x1', observedValue: 'minecraft:stone_pickaxe x1' }],
	}), true);
	assert.deepEqual(await pending, {
		state: 'ACTIVE', verified: false, reasonCode: 'PREDICATE_FAILED',
		facts: [{ type: 'inventory_contains', satisfied: false, expectedValue: 'minecraft:iron_pickaxe x1', observedValue: 'minecraft:stone_pickaxe x1' }],
	});
	assert.deepEqual(finished, []);
});

test('native completion cannot overlap an active physical action', async () => {
	const sent = [];
	const runtime = new NativeToolRuntime({ bridge: { send: async (...args) => sent.push(args) } });
	const action = runtime.execute({
		agentId: 'agent-a', goalRevision: 3, turnId: 'turn-1', callId: 'action-1',
		tool: { kind: 'action', actionType: 'wait', arguments: { durationMs: 1_000 } },
	}, record());
	await Promise.resolve();
	await assert.rejects(runtime.execute({
		agentId: 'agent-a', goalRevision: 3, turnId: 'turn-1', callId: 'finish-1',
		tool: { kind: 'finish', summary: 'Done.' },
	}, record()), (error) => error?.code === 'NATIVE_ACTION_IN_PROGRESS');
	assert.deepEqual(sent.map(([type]) => type), ['action_command']);
	runtime.onActionResult(record(), { goalRevision: 3, actionId: sent[0][2].actionId, state: 'SUCCEEDED', reasonCode: '' });
	await action;
});

test('a physical action cannot overlap native completion verification', async () => {
	const sent = [];
	const runtime = new NativeToolRuntime({ bridge: { send: async (...args) => sent.push(args) } });
	const completion = runtime.execute({
		agentId: 'agent-a', goalRevision: 3, turnId: 'turn-1', callId: 'finish-1',
		tool: { kind: 'finish', summary: 'Done.' },
	}, record());
	await Promise.resolve();
	await assert.rejects(runtime.execute({
		agentId: 'agent-a', goalRevision: 3, turnId: 'turn-1', callId: 'action-1',
		tool: { kind: 'action', actionType: 'wait', arguments: { durationMs: 1_000 } },
	}, record()), (error) => error?.code === 'NATIVE_COMPLETION_IN_PROGRESS');
	assert.deepEqual(sent.map(([type]) => type), ['goal_completed']);
	runtime.onCompletionResult(record(), {
		goalRevision: 3, traceId: sent[0][2].traceId, goalFingerprint: sent[0][2].goalFingerprint, verified: true, reasonCode: 'COMPLETION_VERIFIED', facts: [],
	});
	await completion;
});

test('native body isolates sixteen concurrent agents and their action results', async () => {
	const sent = [];
	const runtime = new NativeToolRuntime({ bridge: { send: async (...args) => sent.push(args) } });
	const records = Array.from({ length: 16 }, (_, index) => record({ agentId: `agent-${index + 1}` }));
	const pending = records.map((entry, index) => runtime.execute({
		agentId: entry.agentId,
		goalRevision: entry.goalRevision,
		turnId: `turn-${index + 1}`,
		callId: `call-${index + 1}`,
		tool: { kind: 'action', actionType: 'wait', arguments: { durationMs: 1 } },
	}, entry));
	await Promise.resolve();
	assert.equal(sent.length, 16);
	for (let index = 0; index < records.length; index += 1) {
		assert.equal(runtime.onActionResult(records[index], {
			goalRevision: 3,
			actionId: sent[index][2].actionId,
			state: 'SUCCEEDED',
			reasonCode: '',
		}), true);
	}
	assert.equal((await Promise.all(pending)).every((result) => result.state === 'SUCCEEDED'), true);
	assert.equal(new Set(sent.map((entry) => entry[2].actionId)).size, 16);
});

test('native sequence executes model-authored actions in order and returns every factual result', async () => {
	const sent = [];
	const runtime = new NativeToolRuntime({ bridge: { send: async (...args) => sent.push(args) } });
	const pending = runtime.execute({
		agentId: 'agent-a', goalRevision: 3, turnId: 'turn-sequence', callId: 'sequence-1',
		tool: {
			kind: 'sequence',
			actions: [
				{ actionType: 'navigate_to', arguments: { x: 2, y: 64, z: 1, tolerance: 1, sprint: true, timeoutMs: 30_000 } },
				{ actionType: 'break_block', arguments: { x: 2, y: 64, z: 1, expectedBlockId: 'minecraft:stone', timeoutMs: 15_000 } },
			],
		},
	}, record());
	await new Promise((resolve) => setImmediate(resolve));
	assert.deepEqual(sent.map((entry) => entry[2].actionType), ['navigate_to']);
	runtime.onActionResult(record(), { actionId: sent[0][2].actionId, state: 'SUCCEEDED', reasonCode: '', executionStarted: true });
	await new Promise((resolve) => setImmediate(resolve));
	assert.deepEqual(sent.map((entry) => entry[2].actionType), ['navigate_to', 'break_block']);
	runtime.onActionResult(record(), { actionId: sent[1][2].actionId, state: 'SUCCEEDED', reasonCode: '', executionStarted: true });
	assert.deepEqual(await pending, {
		state: 'SUCCEEDED',
		completed: 2,
		results: [
			{ actionType: 'navigate_to', state: 'SUCCEEDED', reasonCode: '', executionStarted: true },
			{ actionType: 'break_block', state: 'SUCCEEDED', reasonCode: '', executionStarted: true },
		],
	});
});

test('native sequence stops before later actions after the first factual failure', async () => {
	const sent = [];
	const runtime = new NativeToolRuntime({ bridge: { send: async (...args) => sent.push(args) } });
	const pending = runtime.execute({
		agentId: 'agent-a', goalRevision: 3, turnId: 'turn-sequence-fail', callId: 'sequence-fail',
		tool: {
			kind: 'sequence',
			actions: [
				{ actionType: 'navigate_to', arguments: { x: 2, y: 64, z: 1, tolerance: 1, sprint: true, timeoutMs: 30_000 } },
				{ actionType: 'break_block', arguments: { x: 2, y: 64, z: 1, expectedBlockId: 'minecraft:stone', timeoutMs: 15_000 } },
			],
		},
	}, record());
	await new Promise((resolve) => setImmediate(resolve));
	runtime.onActionResult(record(), { actionId: sent[0][2].actionId, state: 'FAILED', reasonCode: 'NO_PATH', executionStarted: true });
	assert.deepEqual(await pending, {
		state: 'FAILED',
		completed: 1,
		failedAt: 0,
		results: [{ actionType: 'navigate_to', state: 'FAILED', reasonCode: 'NO_PATH', executionStarted: true, failureClass: 'path' }],
	});
	assert.equal(sent.length, 1);
});

test('exploreFrontier returns observed candidates without choosing or executing a route', async () => {
	const sent = [];
	const runtime = new NativeToolRuntime({ bridge: { send: async (...args) => sent.push(args) } });
	runtime.updateObservation(record(), {
		position: { x: 0, y: 64, z: 0 }, world: { worldId: 'world-a', dimension: 'minecraft:overworld' },
		blocks: [{ x: 8, y: 64, z: 0, blockId: 'minecraft:stone' }],
	}, { eventSequence: 1 });
	const result = await runtime.execute(nativeCall({ kind: 'explore_frontier', arguments: { radius: 24, limit: 32 } }), record());
	assert.equal(result.kind, 'candidates');
	assert.equal(result.destination, null);
	assert.ok(result.candidates.some((entry) => entry.kind === 'observed_block'));
	assert.equal(result.freshness.fresh, false);
	assert.deepEqual(sent, []);
});

test('exploreFrontier uses a new sample and gives the AI explicitly unknown candidates', async () => {
	const sent = [];
	const runtime = new NativeToolRuntime({
		bridge: { send: async (...args) => sent.push(args) },
		requestObservation: async () => ({ eventSequence: 5, observation: { position: { x: 20, y: 70, z: 2 }, world: { worldId: 'world-a', dimension: 'minecraft:the_nether' } } }),
	});
	const result = await runtime.execute(nativeCall({ kind: 'explore_frontier', arguments: { radius: 24, limit: 4 } }), record());
	assert.equal(result.destination, null);
	assert.equal(result.dimension, 'minecraft:the_nether');
	assert.equal(result.freshness.fresh, true);
	assert.ok(result.candidates.length > 0);
	assert.ok(result.candidates.every((entry) => entry.reachability === 'unknown'));
	assert.deepEqual(sent, []);
});

test('exploreFrontier reports missing position without inventing a route', async () => {
	const runtime = new NativeToolRuntime({ bridge: { send: async () => assert.fail('read-only query dispatched movement') } });
	const result = await runtime.execute(nativeCall({ kind: 'explore_frontier', arguments: {} }), record());
	assert.equal(result.kind, 'no_observation');
	assert.equal(result.destination, null);
	assert.deepEqual(result.candidates, []);
});

test('action results omit recovery until a fresh observation arrives', async () => {
	const sent = [];
	const runtime = new NativeToolRuntime({ registry: testRegistry, bridge: { send: async (...args) => sent.push(args) } });
	runtime.updateObservation(record(), {
		player: { x: 0, y: 64, z: 0, dead: false },
		inventory: { items: [{ itemId: 'minecraft:oak_log', count: 4 }] },
		world: { dimension: 'minecraft:overworld' },
	}, { eventSequence: 2 });
	const pending = runtime.execute({
		agentId: 'agent-a', goalRevision: 3, turnId: 'turn-craft', callId: 'craft-1',
		tool: { kind: 'action', actionType: 'wait', arguments: { durationMs: 1 } },
	}, record());
	await Promise.resolve();
	runtime.onActionResult(record(), {
		goalRevision: 3, actionId: sent[0][2].actionId, state: 'SUCCEEDED', reasonCode: '', executionStarted: true,
	});
	const result = await pending;
	assert.equal(result.recovery, undefined);
});

test('unavailable observations preserve live inventory for death recovery and resume live updates when ready', async () => {
	const runtime = new NativeToolRuntime({ registry: testRegistry, bridge: { send: async () => {} } });
	const current = record();
	const live = {
		ready: true,
		player: { x: 8, y: 64, z: 2, health: 18, dead: false },
		inventory: { items: [{ itemId: 'minecraft:iron_pickaxe', count: 1 }] },
		world: { dimension: 'minecraft:overworld' },
	};
	runtime.updateObservation(current, live, { eventSequence: 1 });
	const unavailable = adaptObservation({ ready: false, status: 'PLAYER_UNAVAILABLE' });
	runtime.updateObservation(current, unavailable, { eventSequence: 2 });
	runtime.refreshObservation(current, unavailable, { eventSequence: 3 });
	const observed = await runtime.execute({
		agentId: current.agentId, goalRevision: current.goalRevision,
		turnId: 'turn-unavailable', callId: 'observe-unavailable', tool: { kind: 'observe' },
	}, current);
	assert.equal(observed.observation.ready, false);
	assert.equal(observed.observation.status, 'PLAYER_UNAVAILABLE');
	assert.equal(observed.eventSequence, 3);
	assert.deepEqual(runtime.snapshotLive(current.agentId).observation, live);
	const death = { cause: 'lava', x: 8, y: 64, z: 2, dimensionId: 'minecraft:overworld' };
	runtime.updateObservation(current, { death }, { eventSequence: 3, force: true });
	const dead = runtime.decorateObservation(current, { death });
	assert.deepEqual(dead.recovery.lastLostInventory, live.inventory.items);
	assert.equal(dead.recovery.alreadyHave.includes('minecraft:iron_pickaxe'), false);
	const resumed = { ...live, inventory: { items: [{ itemId: 'minecraft:oak_log', count: 4 }] } };
	runtime.updateObservation(current, resumed, { eventSequence: 4 });
	assert.deepEqual(runtime.snapshotLive(current.agentId).observation, resumed);
	assert.equal(runtime.decorateObservation(current, {}).recovery.alreadyHave.includes('minecraft:oak_log'), true);
});

test('death force-updates the observation cache and keeps last live inventory as lost, not held', async () => {
	const runtime = new NativeToolRuntime({ registry: testRegistry, bridge: { send: async () => {} } });
	const current = record();
	runtime.updateObservation(current, {
		player: { x: 8, y: 64, z: 2, health: 18, dead: false },
		inventory: { items: [{ itemId: 'minecraft:stone_pickaxe', count: 1 }] },
		blocks: [{ blockId: 'minecraft:crafting_table', x: 9, y: 64, z: 2 }],
		world: { dimension: 'minecraft:overworld' },
	}, { eventSequence: 6 });
	const death = { cause: 'lava', x: 8, y: 64, z: 2, dimensionId: 'minecraft:overworld' };
	assert.equal(runtime.updateObservation(current, { death }, { eventSequence: 6, force: true }), true);
	const decorated = runtime.decorateObservation(current, { death });
	assert.equal(decorated.continuity.phase, 'dead');
	assert.equal(decorated.failureClass, 'lifecycle');
	assert.equal(decorated.inventory.items.length, 0);
	assert.equal(decorated.recovery.lastLostInventory[0].itemId, 'minecraft:stone_pickaxe');
	assert.equal(decorated.recovery.alreadyHave.includes('minecraft:stone_pickaxe'), false);
	assert.match(decorated.recovery.facts, /Current inventory is empty/);
	assert.equal(decorated.options, undefined, 'recovery facts do not prescribe a strategy');
});

test('bridge disconnect keeps recovery memory until the agent is removed', async () => {
	const runtime = new NativeToolRuntime({ registry: testRegistry, bridge: { send: async () => {} } });
	const current = record();
	runtime.updateObservation(current, {
		player: { x: 1, y: 64, z: 1, dead: false },
		inventory: { items: [{ itemId: 'minecraft:iron_pickaxe', count: 1 }] },
		world: { dimension: 'minecraft:overworld' },
	}, { eventSequence: 3 });
	await runtime.dispose('agent-a', 'bridge_disconnected');
	const decorated = runtime.decorateObservation(current, {
		player: { x: 1, y: 64, z: 1, dead: false },
		inventory: { items: [{ itemId: 'minecraft:iron_pickaxe', count: 1 }] },
		world: { dimension: 'minecraft:overworld' },
	});
	assert.ok(decorated.recovery.alreadyHave.includes('minecraft:iron_pickaxe'));
	assert.equal(decorated.recovery.doNotRedo, undefined);
	await runtime.dispose('agent-a', 'agent_removed');
	const forgotten = runtime.decorateObservation(current, {
		player: { x: 1, y: 64, z: 1, dead: false },
		inventory: { items: [] },
		world: { dimension: 'minecraft:overworld' },
	});
	assert.equal(forgotten.recovery?.alreadyHave?.includes('minecraft:iron_pickaxe') === true, false);
});

test('empty decorate payloads do not wipe live inventory memory', async () => {
	const runtime = new NativeToolRuntime({ registry: testRegistry, bridge: { send: async () => {} } });
	const current = record();
	runtime.updateObservation(current, {
		player: { x: 0, y: 64, z: 0, dead: false },
		inventory: { items: [{ itemId: 'minecraft:diamond_pickaxe', count: 1 }] },
		world: { dimension: 'minecraft:overworld' },
	}, { eventSequence: 2 });
	const decorated = runtime.decorateObservation(current, {});
	assert.ok(decorated.recovery.alreadyHave.includes('minecraft:diamond_pickaxe'));
	assert.equal(decorated.inventory.items[0].itemId, 'minecraft:diamond_pickaxe');
	decorated.inventory.items[0].count = 99;
	assert.equal(runtime.snapshotLive(current.agentId).observation.inventory.items[0].count, 1,
		'fallback decoration owns its nested facts when raw stores share a snapshot');
});

test('native sequence is cancelled if the lifecycle is disposed between steps', async () => {
	const sent = [];
	const runtime = new NativeToolRuntime({ bridge: { send: async (...args) => sent.push(args) } });
	const pending = runtime.execute({
		agentId: 'agent-a', goalRevision: 3, turnId: 'turn-sequence-dispose', callId: 'sequence-dispose',
		tool: {
			kind: 'sequence',
			actions: [
				{ actionType: 'wait', arguments: { durationMs: 1 } },
				{ actionType: 'wait', arguments: { durationMs: 1 } },
			],
		},
	}, record());
	await new Promise((resolve) => setImmediate(resolve));
	runtime.onActionResult(record(), { actionId: sent[0][2].actionId, state: 'SUCCEEDED', reasonCode: '' });
	await runtime.dispose('agent-a', 'goal_replaced');
	await assert.rejects(pending, (error) => error?.code === 'NATIVE_ACTION_CANCELLED');
	assert.equal(sent.length, 1);
});

function nativeCall(tool, overrides = {}) { return { agentId: 'agent-a', goalRevision: 3, turnId: 'turn-new', callId: 'call-new', tool, ...overrides }; }

test('observe waits for a newer server sample and rejects a cached freshness claim', async () => {
	let sampled;
	let barrier;
	const runtime = new NativeToolRuntime({
		bridge: { send: async () => {} },
		requestObservation: async (_record, options) => { barrier = options; return new Promise((resolve) => { sampled = resolve; }); },
	});
	runtime.updateObservation(record(), { player: { health: 12 }, observedAtEpochMs: 10 }, { eventSequence: 4 });
	let returned = false;
	const pending = runtime.execute(nativeCall({ kind: 'observe' }), record()).then((result) => { returned = true; return result; });
	await new Promise((resolve) => setImmediate(resolve));
	assert.equal(returned, false);
	assert.deepEqual(barrier, { afterEventSequence: 4 });
	sampled({ eventSequence: 5, observation: { player: { health: 9 }, observedAtEpochMs: 20 } });
	const result = await pending;
	assert.equal(result.observation.player.health, 9);
	assert.deepEqual(result.freshness, { fresh: true, afterEventSequence: 4, eventSequence: 5, observedAtEpochMs: 20 });
	const stale = runtime.execute(nativeCall({ kind: 'observe' }), record());
	await new Promise((resolve) => setImmediate(resolve));
	sampled({ eventSequence: 5, observation: { player: { health: 9 } } });
	await assert.rejects(stale, { code: 'FRESH_OBSERVATION_REQUIRED' });
});

test('fresh observation cannot repopulate a disposed lifecycle', async () => {
	let sampled;
	const runtime = new NativeToolRuntime({ bridge: { send: async () => {} }, requestObservation: async () => new Promise((resolve) => { sampled = resolve; }) });
	const pending = runtime.execute(nativeCall({ kind: 'observe' }), record());
	await new Promise((resolve) => setImmediate(resolve));
	await runtime.dispose('agent-a', 'goal_stopped');
	sampled({ eventSequence: 1, observation: { player: { health: 20 } } });
	await assert.rejects(pending, { code: 'STALE_NATIVE_TOOL' });
	assert.equal(runtime.hasCurrent(record()), false);
});

test('inspect uses the focused server query and preserves revisions and coverage', async () => {
	const calls = [];
	const response = { section: 'block', block: { blockId: 'minecraft:oak_sign', text: ['Turn left'] }, revision: 9, gameTime: 50, coverage: { returned: 1, accessible: true } };
	const runtime = new NativeToolRuntime({ bridge: { send: async () => assert.fail('inspection mutated player') }, inspectObservation: async (current, query) => { calls.push({ current, query }); return response; } });
	const query = { kind: 'inspect', section: 'block', x: 1, y: 64, z: 2, offset: 0, limit: 1 };
	const result = await runtime.execute(nativeCall(query), record());
	assert.deepEqual(calls[0].query, { section: 'block', x: 1, y: 64, z: 2, offset: 0, limit: 1 });
	assert.deepEqual(result, response);
	result.block.text[0] = 'changed';
	assert.equal(response.block.text[0], 'Turn left');
	const unavailable = new NativeToolRuntime({ bridge: { send: async () => {} } });
	await assert.rejects(unavailable.execute(nativeCall(query), record()), { code: 'INSPECTION_UNAVAILABLE' });
});

test('startAction exposes a handle, progress and an exact terminal receipt', async () => {
	const sent = [];
	const runtime = new NativeToolRuntime({ bridge: { send: async (...args) => sent.push(args) } });
	const handle = await runtime.execute(nativeCall({ kind: 'start_action', actionType: 'wait', arguments: { durationMs: 100 } }), record());
	assert.equal(handle.state, 'RUNNING');
	assert.equal(handle.actionId, sent[0][2].actionId);
	assert.equal(handle.goalRevision, 3);
	runtime.onActionProgress(record(), { actionId: handle.actionId, progress: 0.5, elapsedMs: 50 });
	const progress = await runtime.execute(nativeCall({ kind: 'action_status', actionId: handle.actionId }), record());
	assert.deepEqual(progress.progress, { value: 0.5, elapsedMs: 50 });
	runtime.onActionResult(record(), { actionId: handle.actionId, state: 'SUCCEEDED', reasonCode: 'ACTION_COMPLETED' });
	const result = await runtime.execute(nativeCall({ kind: 'action_status', actionId: handle.actionId }), record());
	assert.equal(result.actionId, handle.actionId);
	assert.equal(result.state, 'SUCCEEDED');
	assert.equal(result.reasonCode, 'ACTION_COMPLETED');
	assert.equal((await runtime.execute(nativeCall({ kind: 'action_status' }), record())).state, 'IDLE');
});

test('cancel rejects mismatched handles and waits for exact acknowledgement', async () => {
	const sent = [];
	const runtime = new NativeToolRuntime({ bridge: { send: async (...args) => sent.push(args) } });
	const handle = await runtime.execute(nativeCall({ kind: 'start_action', actionType: 'wait', arguments: { durationMs: 100 } }), record());
	await assert.rejects(runtime.execute(nativeCall({ kind: 'cancel_action', actionId: 'wrong', goalRevision: 3 }), record()), { code: 'STALE_ACTION' });
	await assert.rejects(runtime.execute(nativeCall({ kind: 'cancel_action', actionId: handle.actionId, goalRevision: 2 }), record()), { code: 'STALE_ACTION' });
	assert.equal(sent.length, 1);
	let resolved = false;
	const pending = runtime.execute(nativeCall({ kind: 'cancel_action', actionId: handle.actionId, goalRevision: 3 }), record()).then((result) => { resolved = true; return result; });
	await Promise.resolve();
	assert.deepEqual(sent[1], ['action_cancel', 'agent-a', { actionId: handle.actionId, goalRevision: 3 }]);
	assert.equal(resolved, false);
	assert.equal((await runtime.execute(nativeCall({ kind: 'action_status' }), record())).state, 'CANCELLING');
	assert.equal(runtime.onActionResult(record(), { actionId: 'other', state: 'CANCELLED' }), false);
	runtime.onActionResult(record(), { actionId: handle.actionId, state: 'CANCELLED', reasonCode: 'ACTION_CANCELLED' });
	assert.equal((await pending).state, 'CANCELLED');
});

test('replace dispatches only after acknowledged cancellation and keeps model provenance', async () => {
	const sent = [];
	const runtime = new NativeToolRuntime({ bridge: { send: async (...args) => sent.push(args) } });
	const handle = await runtime.execute(nativeCall({ kind: 'start_action', actionType: 'wait', arguments: { durationMs: 100 } }), record());
	const pending = runtime.execute(nativeCall({ kind: 'replace_action', actionId: handle.actionId, goalRevision: 3, actionType: 'look_at', arguments: { x: 2, y: 64, z: 3 } }), record());
	await Promise.resolve();
	assert.equal(sent.filter(([type]) => type === 'action_command').length, 1);
	runtime.onActionResult(record(), { actionId: handle.actionId, state: 'CANCELLED', reasonCode: 'ACTION_CANCELLED' });
	await new Promise((resolve) => setImmediate(resolve));
	assert.equal(sent[2][2].actionType, 'look_at');
	assert.equal(sent[2][2].provenance.sourceStepId, 'call-new');
	runtime.onActionResult(record(), { actionId: sent[2][2].actionId, state: 'SUCCEEDED', reasonCode: 'ACTION_COMPLETED' });
	assert.equal((await pending).state, 'SUCCEEDED');
});

test('replacement stays unstarted if the previous action finishes before cancellation', async () => {
	const sent = [];
	const runtime = new NativeToolRuntime({ bridge: { send: async (...args) => sent.push(args) } });
	const handle = await runtime.execute(nativeCall({ kind: 'start_action', actionType: 'wait', arguments: { durationMs: 100 } }), record());
	const pending = runtime.execute(nativeCall({ kind: 'replace_action', actionId: handle.actionId, goalRevision: 3, actionType: 'wait', arguments: { durationMs: 1 } }), record());
	runtime.onActionResult(record(), { actionId: handle.actionId, state: 'SUCCEEDED', reasonCode: 'ACTION_COMPLETED' });
	assert.equal((await pending).state, 'REPLACEMENT_NOT_STARTED');
	assert.equal(sent.filter(([type]) => type === 'action_command').length, 1);
});

test('notebook scopes model notes and authoritative receipts to the observed world', async () => {
	const writes = [];
	const queries = [];
	const receipts = [];
	const sent = [];
	const runtime = new NativeToolRuntime({ bridge: { send: async (...args) => sent.push(args) }, notebook: {
		writeNote: async (...args) => { writes.push(args); return { saved: true, provenance: 'model_note' }; },
		query: async (...args) => { queries.push(args); return { notes: [], receipts: [] }; },
		recordReceipt: async (...args) => { receipts.push(args); },
	} });
	runtime.updateObservation(record(), { world: { worldId: 'world-a', dimension: 'minecraft:overworld' } }, { eventSequence: 1 });
	await runtime.execute(nativeCall({ kind: 'notebook', key: 'return-route', text: 'Bridge may be east.' }), record());
	assert.deepEqual(writes[0], ['agent-a', { worldId: 'world-a', key: 'return-route', text: 'Bridge may be east.', goalRevision: 3, provenance: { provider: 'codex', model: 'gpt-5.6-sol', reasoningEffort: 'xhigh', serviceTier: 'fast', goalRevision: 3, turnId: 'turn-new', callId: 'call-new' } }]);
	await runtime.execute(nativeCall({ kind: 'query_memory', memoryKind: 'notes', offset: 5, limit: 20, text: 'Bridge' }), record());
	assert.deepEqual(queries[0], ['agent-a', { worldId: 'world-a', kind: 'notes', offset: 5, limit: 20, text: 'Bridge' }]);
	const handle = await runtime.execute(nativeCall({ kind: 'start_action', actionType: 'wait', arguments: { durationMs: 1 } }), record());
	runtime.onActionResult(record(), { actionId: handle.actionId, state: 'SUCCEEDED', reasonCode: 'ACTION_COMPLETED', actionObservation: { worldTick: 18 } });
	await Promise.resolve();
	assert.deepEqual(receipts[0], ['agent-a', { worldId: 'world-a', actionId: handle.actionId, goalRevision: 3, actionType: 'wait', state: 'SUCCEEDED', reasonCode: 'ACTION_COMPLETED', actionObservation: { worldTick: 18 }, tick: 18 }]);
});

test('spatial hydration replays queued observations before checkpoint persistence', async () => {
	let hydrate;
	const calls = [];
	const runtime = new NativeToolRuntime({ bridge: { send: async () => {} }, occupancy: {
		load: async () => { calls.push('load'); await new Promise((resolve) => { hydrate = resolve; }); calls.push('loaded'); },
		ingest: (_agentId, observation) => calls.push(`ingest:${observation.world.gameTime}`),
		flush: async () => calls.push('flush'),
		clear: () => calls.push('clear'),
		candidates: () => ({ kind: 'candidates', candidates: [], destination: null }),
	} });
	runtime.updateObservation(record(), { world: { gameTime: 1 } }, { eventSequence: 1 });
	runtime.updateObservation(record(), { world: { gameTime: 2 } }, { eventSequence: 2 });
	await Promise.resolve();
	assert.deepEqual(calls, ['load']);
	hydrate();
	await runtime.initializeMemory('agent-a');
	assert.deepEqual(calls, ['load', 'loaded', 'ingest:1', 'ingest:2']);
	await runtime.dispose('agent-a', 'coordinator_stopped');
	assert.deepEqual(calls.slice(-2), ['flush', 'clear']);
});

test('native action identities remain unique across runtime restarts and long agent identifiers', async () => {
	const current = record({ agentId: 'agent-'.repeat(40) });
	const handles = [];
	for (let index = 0; index < 2; index++) {
		const runtime = new NativeToolRuntime({ bridge: { send: async () => {} } });
		const handle = await runtime.execute(nativeCall({ kind: 'start_action', actionType: 'wait', arguments: { durationMs: 1 } }, { agentId: current.agentId }), current);
		assert.ok(handle.actionId.length <= 128);
		handles.push(handle.actionId);
		runtime.onActionResult(current, { actionId: handle.actionId, state: 'SUCCEEDED', reasonCode: 'ACTION_COMPLETED' });
	}
	assert.notEqual(handles[0], handles[1]);
});

test('fixture session identity is explicit and production runtimes remain random', async () => {
	const runtime = new NativeToolRuntime({ sessionId: 'fixture-replay', bridge: { send: async () => {} } });
	const handle = await runtime.execute(nativeCall({ kind: 'start_action', actionType: 'wait', arguments: { durationMs: 1 } }), record());
	assert.match(handle.actionId, /^native:fixture-replay:1:/);
	runtime.onActionResult(record(), { actionId: handle.actionId, state: 'SUCCEEDED', reasonCode: '' });
	assert.throws(() => new NativeToolRuntime({ sessionId: 'unsafe session', bridge: { send: async () => {} } }), /sessionId/);
	const handles = [];
	for (let index = 0; index < 2; index++) {
		const fixture = new NativeToolRuntime({ sessionId: 'fixture-'.padEnd(128, 'a'), bridge: { send: async () => {} } });
		const result = await fixture.execute(nativeCall({ kind: 'start_action', actionType: 'wait', arguments: { durationMs: 1 } }), record());
		handles.push(result.actionId);
		assert.ok(result.actionId.length <= 128);
		fixture.onActionResult(record(), { actionId: result.actionId, state: 'SUCCEEDED', reasonCode: '' });
	}
	assert.equal(handles[0], handles[1]);
});

test('capabilities and observe disclose effective settings without rewriting the selected profile', async () => {
	const settings = { requested: { reasoningEffort: 'medium' }, effective: { reasoningEffort: 'high' }, mapping: 'provider supported level' };
	const runtime = new NativeToolRuntime({ bridge: { send: async () => {} }, executionSettings: (current) => { assert.equal(current.model, record().model); return settings; } });
	for (const kind of ['capabilities', 'observe']) {
		const result = await runtime.execute(nativeCall({ kind }), record());
		assert.deepEqual(result.executionSettings, settings);
		result.executionSettings.effective.reasoningEffort = 'changed';
		assert.equal(settings.effective.reasoningEffort, 'high');
	}
});

test('native memory operations share the program helper contract and model provenance', async () => {
	const calls = [];
	const runtime = new NativeToolRuntime({ bridge: { send: async () => {} }, memoryOperation: async (current, operation) => { calls.push({ current, operation }); return { state: 'SUCCEEDED', reasonCode: 'MEMORY_QUERIED' }; } });
	await runtime.execute(nativeCall({ kind: 'query_memory', memoryKind: 'receipts', offset: 64, limit: 32 }), record());
	assert.deepEqual(calls[0].operation.arguments, { kind: 'receipts', offset: 64, limit: 32 });
	assert.equal(calls[0].operation.operation, 'query');
	assert.equal(calls[0].operation.provenance.model, record().model);
	assert.equal(calls[0].operation.provenance.callId, 'call-new');
});

test('durable preparation fences dispatch and exact cancellation prevents a later send', async () => {
	let release;
	const sent = [];
	const entries = [];
	const runtime = new NativeToolRuntime({ bridge: { send: async (...args) => sent.push(args) }, notebook: {
		writeNote: async () => {}, query: async () => ({}), recordReceipt: async () => {},
		recordDispatch: async (_agentId, entry) => { entries.push(entry); await new Promise((resolve) => { release = resolve; }); },
		recordUnknown: async (_agentId, entry) => entries.push(entry),
	} });
	runtime.updateObservation(record(), { world: { worldId: 'world-a' } }, { eventSequence: 1 });
	const pending = runtime.execute(nativeCall({ kind: 'start_action', actionType: 'wait', arguments: { durationMs: 100 } }), record());
	await Promise.resolve();
	assert.equal(sent.length, 0);
	const handle = await runtime.execute(nativeCall({ kind: 'action_status' }), record());
	assert.equal(handle.state, 'PREPARING');
	const cancelled = await runtime.execute(nativeCall({ kind: 'cancel_action', actionId: handle.actionId, goalRevision: 3 }), record());
	assert.equal(cancelled.executionStarted, false);
	release();
	assert.equal((await pending).reasonCode, 'CANCELLED_BEFORE_DISPATCH');
	assert.equal(sent.length, 0);
	assert.deepEqual(entries[0].arguments, { durationMs: 100 });
});

test('unknown delivery is inspectable and late terminal evidence cannot cancel a newer action', async () => {
	const notebook = new ModelNotebook();
	let fail = true;
	const runtime = new NativeToolRuntime({ notebook, bridge: { send: async () => { if (fail) throw new Error('socket closed'); } } });
	runtime.updateObservation(record(), { world: { worldId: 'world-a' } }, { eventSequence: 1 });
	let unknownId;
	await assert.rejects(runtime.execute(nativeCall({ kind: 'start_action', actionType: 'wait', arguments: { durationMs: 100 } }), record()), (error) => { unknownId = error.actionId; return error.message === 'socket closed'; });
	assert.equal((await notebook.findReceipt('agent-a', { actionId: unknownId })).state, 'UNKNOWN');
	const capabilities = await runtime.execute(nativeCall({ kind: 'capabilities' }), record());
	assert.equal(capabilities.unresolvedActions.total, 1);
	assert.equal(capabilities.unresolvedActions.entries[0].actionId, unknownId);
	fail = false;
	const next = await runtime.execute(nativeCall({ kind: 'start_action', actionType: 'wait', arguments: { durationMs: 1 } }), record());
	assert.equal(runtime.onActionResult(record(), { actionId: unknownId, goalRevision: 3, state: 'SUCCEEDED', reasonCode: '', executionStarted: true }), true);
	assert.equal((await runtime.execute(nativeCall({ kind: 'action_status' }), record())).actionId, next.actionId);
	assert.equal((await notebook.findReceipt('agent-a', { actionId: unknownId })).state, 'SUCCEEDED');
	runtime.onActionResult(record(), { actionId: next.actionId, goalRevision: 3, state: 'SUCCEEDED', reasonCode: '' });
});

test('restart reconciliation updates only matching durable native identities without execution', async () => {
	const notebook = new ModelNotebook();
	const actionId = 'native:previous-session:1:agent-a:2';
	await notebook.recordDispatch('agent-a', { worldId: 'original-world', actionId, goalRevision: 2, actionType: 'wait', arguments: { durationMs: 100 } });
	const sent = [];
	const runtime = new NativeToolRuntime({ notebook, bridge: { send: async (...args) => sent.push(args) } });
	const payload = { actionId, goalRevision: 2, actionType: 'wait', state: 'SUCCEEDED', reasonCode: '', executionStarted: true, actionObservation: { worldTick: 31 } };
	assert.equal(await runtime.reconcileActionReceipt('agent-a', { ...payload, actionId: 'native:unknown' }), false);
	assert.equal(await runtime.reconcileActionReceipt('agent-a', { ...payload, goalRevision: 3 }), false);
	assert.equal(await runtime.reconcileActionReceipt('agent-a', payload), true);
	assert.equal(await runtime.reconcileActionReceipt('agent-a', payload), true);
	const saved = await notebook.findReceipt('agent-a', { actionId });
	assert.equal(saved.worldId, 'original-world');
	assert.equal(saved.state, 'SUCCEEDED');
	assert.equal(saved.executionStarted, true);
	assert.deepEqual(saved.arguments, { durationMs: 100 });
	assert.equal(sent.length, 0);
});

test('authoritative result wins over a later bridge send error', async () => {
	let runtime;
	runtime = new NativeToolRuntime({ bridge: { send: async (_type, _agentId, payload) => {
		runtime.onActionResult(record(), { actionId: payload.actionId, state: 'SUCCEEDED', reasonCode: '' });
		throw new Error('late socket error');
	} } });
	const result = await runtime.execute(nativeCall({ kind: 'start_action', actionType: 'wait', arguments: { durationMs: 1 } }), record());
	assert.equal(result.state, 'SUCCEEDED');
	assert.equal((await runtime.execute(nativeCall({ kind: 'action_status', actionId: result.actionId }), record())).state, 'SUCCEEDED');
});

test('focused visible target retains its delivered causal baseline for exact body references', async () => {
	const sent = [];
	const targetId = '24f7bbba-c3a7-41e7-831b-bec7291dbb23';
	const runtime = new NativeToolRuntime({ bridge: { send: async (...args) => sent.push(args) }, inspectObservation: async () => ({ eventSequence: 8, entries: [{ uuid: targetId }] }) });
	runtime.updateObservation(record(), { world: { worldId: 'world-a' }, entities: [] }, { eventSequence: 7 });
	await runtime.execute(nativeCall({ kind: 'inspect', section: 'entities', offset: 20, limit: 10 }), record());
	runtime.updateObservation(record(), { world: { worldId: 'world-a' }, entities: [] }, { eventSequence: 9 });
	const handle = await runtime.execute(nativeCall({ kind: 'start_action', actionType: 'attack', arguments: { targetId, timeoutMs: 1000 } }), record());
	assert.equal(sent[0][2].provenance.eventSequence, 8);
	runtime.onActionResult(record(), { actionId: handle.actionId, state: 'FAILED', reasonCode: 'TARGET_NOT_VISIBLE' });
});

test('newly observed visible targets replace stale focused baselines for direct and program actions', async () => {
	const sent = [];
	const targetId = '24f7bbba-c3a7-41e7-831b-bec7291dbb23';
	let runtime;
	runtime = new NativeToolRuntime({ bridge: { send: async (...args) => {
		sent.push(args);
		if (args[0] === 'action_command') queueMicrotask(() => runtime.onActionResult(record(), { actionId: args[2].actionId, state: 'FAILED', reasonCode: 'OUT_OF_REACH' }));
	} }, inspectObservation: async () => ({ section: 'entities', eventSequence: 1, entries: [{ uuid: targetId }] }) });
	runtime.updateObservation(record(), { player: { health: 20 }, entities: [] }, { eventSequence: 1 });
	await runtime.execute(nativeCall({ kind: 'inspect', section: 'entities', offset: 0, limit: 1 }), record());
	for (const identity of ['uuid', 'stableId']) {
		runtime.updateObservation(record(), { player: { health: 20 }, entities: [{ [identity]: targetId }] }, { eventSequence: identity === 'uuid' ? 5000 : 5001 });
		await runtime.execute(nativeCall({ kind: 'action', actionType: 'attack', arguments: { targetId, timeoutMs: 1000 } }), record());
		assert.equal(sent.at(-1)[2].provenance.eventSequence, identity === 'uuid' ? 5000 : 5001);
	}
	await runtime.execute(nativeCall({ kind: 'run_program', source: `program.onUnhandledAttention("continue_and_notify"); await player.attack({ targetId: "${targetId}", timeoutMs: 1000 });` }), record());
	assert.equal(sent.at(-1)[2].provenance.eventSequence, 5001);
});

test('native programs preserve selected authorship and refresh before a dependent command', async () => {
	const notebook = new ModelNotebook();
	const sent = [];
	let sequence = 1;
	const observation = { world: { worldId: 'world-a' }, player: { x: 0, y: 64, z: 0, health: 20 }, blocks: [], entities: [], items: [], inventory: { items: [], tagCounts: {} } };
	let runtime;
	runtime = new NativeToolRuntime({ notebook, bridge: { send: async (...args) => {
		sent.push(args);
		if (args[0] === 'action_command') queueMicrotask(() => runtime.onActionResult(record(), { actionId: args[2].actionId, state: 'SUCCEEDED', reasonCode: '', executionStarted: true }));
	} }, requestObservation: async () => ({ observation, eventSequence: ++sequence }) });
	runtime.updateObservation(record(), observation, { eventSequence: sequence });
	const result = await runtime.execute(nativeCall({ kind: 'run_program', source: 'program.onUnhandledAttention("continue_and_notify"); await world.remember({ key: "intent", text: "I chose two waits." }); await player.wait(2); await player.wait(3);', maxActions: 4, timeoutMs: 5000 }), record());
	assert.equal(result.reasonCode, 'PROGRAM_EXHAUSTED');
	assert.deepEqual(sent.map(([, , payload]) => payload.arguments.durationMs), [2, 3]);
	assert.deepEqual(sent.map(([, , payload]) => payload.provenance.eventSequence), [1, 2]);
	assert.ok(sent.every(([, , payload]) => payload.provenance.model === record().model && payload.provenance.reasoningEffort === record().reasoningEffort && payload.provenance.serviceTier === record().serviceTier));
	assert.match(sent[0][2].provenance.programId, /^native-program-/);
	assert.match(result.receipts[0].bodyActionId, /^native:/);
	assert.equal((await notebook.query('agent-a', { worldId: 'world-a', kind: 'notes' })).entries[0].provenance.programId, sent[0][2].provenance.programId);
});

test('native programs inspect a raw page, use its facts in an action, and request finish', async () => {
	const sent = [], queries = [];
	let sequence = 1;
	const observation = { player: { x: 0, y: 64, z: 0, health: 20 }, inventory: { items: [] } };
	let runtime;
	runtime = new NativeToolRuntime({ bridge: { send: async (...args) => {
		sent.push(args);
		if (args[0] === 'action_command') queueMicrotask(() => runtime.onActionResult(record(), { actionId: args[2].actionId, state: 'SUCCEEDED', reasonCode: 'MENU_CLOSED' }));
	} }, inspectObservation: async (_record, query) => {
		queries.push(query);
		return { section: 'menu', eventSequence: 1, menu: { menuId: 'minecraft:generic_9x3', containerId: 3, stateId: 9 } };
	}, requestObservation: async () => ({ observation, eventSequence: ++sequence }) });
	runtime.updateObservation(record(), observation, { eventSequence: sequence });
	const result = await runtime.execute(nativeCall({ kind: 'run_program', source: `
		program.onUnhandledAttention("continue_and_notify");
		const page = await world.inspect({ section: "menu" });
		await player.menuClose({ menuId: page.menu.menuId, containerId: page.menu.containerId, stateId: page.menu.stateId });
		program.finish("Container closed");
	` }), record());
	assert.equal(queries.length, 1);
	assert.equal(queries[0].section, 'menu');
	assert.equal(sent.length, 1);
	assert.equal(sent[0][0], 'action_command');
	assert.deepEqual(sent[0][2].arguments, { menuId: 'minecraft:generic_9x3', containerId: 3, stateId: 9 });
	assert.equal(result.reasonCode, 'PROGRAM_FINISH_REQUESTED');
	assert.equal(result.finishRequested, true);
	assert.equal(result.actions, 1);
	assert.equal(result.receipts[0].state, 'SUCCEEDED');
});

for (const failureMode of ['result', 'throw']) {
	test(`native program inspection preserves ${failureMode} failure for the authored branch`, async () => {
		const runtime = new NativeToolRuntime({ bridge: { send: async () => assert.fail('failed inspection dispatched an action') }, inspectObservation: async () => {
			if (failureMode === 'throw') throw Object.assign(new Error('Inspection expired'), { code: 'INSPECTION_EXPIRED' });
			return { state: 'FAILED', reasonCode: 'INSPECTION_EXPIRED' };
		} });
		runtime.updateObservation(record(), { player: { health: 20 } }, { eventSequence: 1 });
		const result = await runtime.execute(nativeCall({ kind: 'run_program', source: 'program.onUnhandledAttention("continue_and_notify"); const page = await world.inspect({ section: "menu" }); if (page.state === "FAILED" && page.reasonCode === "INSPECTION_EXPIRED") { program.finish("Inspection expired"); } else { await player.wait(1); }' }), record());
		assert.equal(result.reasonCode, 'PROGRAM_FINISH_REQUESTED');
		assert.equal(result.actions, 0);
	});
}

test('native program cancellation fences its next step and disposal releases its body', async () => {
	const sent = [];
	let runtime;
	runtime = new NativeToolRuntime({ bridge: { send: async (...args) => {
		sent.push(args);
		if (args[0] === 'action_cancel') queueMicrotask(() => runtime.onActionResult(record(), { actionId: args[2].actionId, goalRevision: 3, state: 'CANCELLED', reasonCode: 'ACTION_CANCELLED' }));
	} } });
	runtime.updateObservation(record(), { player: { x: 0, y: 64, z: 0, health: 20 }, inventory: { items: [] } }, { eventSequence: 1 });
	const pending = runtime.execute(nativeCall({ kind: 'run_program', source: 'program.onUnhandledAttention("pause_and_notify"); await player.wait(100); await player.wait(3);' }), record());
	await Promise.resolve();
	await assert.rejects(runtime.execute(nativeCall({ kind: 'start_action', actionType: 'wait', arguments: { durationMs: 1 } }), record()), { code: 'NATIVE_PROGRAM_IN_PROGRESS' });
	await runtime.dispose('agent-a', 'goal_changed');
	const result = await pending;
	assert.notEqual(result.state, 'SUCCEEDED');
	assert.equal(sent.filter(([type]) => type === 'action_command').length, 1);
	assert.ok(sent.some(([type]) => type === 'action_cancel'));
});

test('focused inspection binds only the page entries actually delivered after native compaction', async () => {
	const entries = Array.from({ length: 20 }, (_, index) => ({ uuid: `00000000-0000-0000-0000-${String(index).padStart(12, '0')}`, text: 'x'.repeat(2000) }));
	const sent = [];
	const runtime = new NativeToolRuntime({ bridge: { send: async (...args) => sent.push(args) }, inspectObservation: async () => ({ eventSequence: 8, offset: 0, entries }) });
	runtime.updateObservation(record(), { player: { health: 20 } }, { eventSequence: 7 });
	const page = await runtime.execute(nativeCall({ kind: 'inspect', section: 'entities', offset: 0, limit: 20 }), record());
	assert.ok(page.entries.length > 0 && page.entries.length < entries.length);
	for (const [targetId, expected] of [[page.entries[0].uuid, 8], [entries.at(-1).uuid, 7]]) {
		const handle = await runtime.execute(nativeCall({ kind: 'start_action', actionType: 'attack', arguments: { targetId, timeoutMs: 1000 } }), record());
		assert.equal(sent.at(-1)[2].provenance.eventSequence, expected);
		runtime.onActionResult(record(), { actionId: handle.actionId, state: 'FAILED', reasonCode: 'TARGET_NOT_VISIBLE' });
	}
});
