import assert from 'node:assert/strict';
import { after } from 'node:test';
import test from 'node:test';

import { AgentRegistry, DynamicAgentState } from '../src/agent-registry.mjs';
import { AgentPlanner } from '../src/agent-planner.mjs';
import { ControlLatencyRegistry } from '../src/control-latency-registry.mjs';
import { PlanningScheduler } from '../src/planning-scheduler.mjs';
import { ProgramRuntimeManager } from '../src/program-runtime-manager.mjs';
import { goalSpecFingerprint } from '../src/goal-spec.mjs';
import { validateProtocolV2Payload } from '../src/protocol-v2.mjs';
import { FakeMinecraftBridge, SELECTED_PROFILE, assertCommandProvenance, commandPayloads, observation } from './fixtures/fake-minecraft-bridge.mjs';
import { withCompletionContract } from './fixtures/completion-contract.mjs';

const latency = new ControlLatencyRegistry({ windowSize: 20_000 });
const providerLatencyMs = [];
const scenarioResults = [];
const PICKUP_RADIUS = 1.5;

const LOG_DROP = (stableId, count, x = 8) => ({ stableId, itemId: 'minecraft:oak_log', count, x, y: 64, z: 0 });
const LOG_TREE = (stableId, x) => ({ stableId, blockId: 'minecraft:oak_log', x, y: 64, z: 0 });

test('collects eight logs across two trees using fresh inventory facts and provenance', async () => {
	const harness = createHarness({
		initialObservation: observation({ blocks: [LOG_TREE('tree-one', 4)] }),
		onAction: async (command) => {
			const count = harnessCount(command, harness);
			if (command.actionType === 'break_block' && count === 1) return { observation: observation({ blocks: [LOG_TREE('tree-two', 10)], inventory: { items: [{ itemId: 'minecraft:oak_log', count: 5 }], tagCounts: { '#minecraft:logs': 5 } } }) };
			return { observation: observation({ inventory: { items: [{ itemId: 'minecraft:oak_log', count: 8 }], tagCounts: { '#minecraft:logs': 8 } } }) };
		},
	});
	await harness.install(`
		program.onUnhandledAttention("continue_and_notify");
		await program.repeatUntil(() => inventory.count("minecraft:oak_log") >= 8, { maxIterations: 8 }, async () => {
			const tree = world.nearest(world.blocks({ blockId: "minecraft:oak_log" }));
			if (tree !== null) await player.mine({ x: tree.x, y: tree.y, z: tree.z, expectedBlockId: tree.blockId, timeoutMs: 1 });
		});
		program.finish("Collected eight logs");
	`);
	await eventually(() => harness.managerState() === DynamicAgentState.COMPLETED);
	const commands = commandPayloads(harness.bridge);
	assert.deepEqual(commands.map((command) => command.actionType), ['break_block', 'break_block']);
	assert.deepEqual(commands.filter((command) => command.actionType === 'break_block').map(({ arguments: args }) => ({ x: args.x, y: args.y, z: args.z })), [
		{ x: 4, y: 64, z: 0 },
		{ x: 10, y: 64, z: 0 },
	]);
	assert.notDeepEqual(commands.filter((command) => command.actionType === 'break_block').map(({ arguments: args }) => ({ x: args.x, y: args.y, z: args.z })), [
		{ x: 4, y: 64, z: 0 },
		{ x: 4, y: 64, z: 0 },
	], 'repeating the first tree coordinate must not produce the second tree drop');
	assertCommandProvenance(commands, SELECTED_PROFILE, 'program-1-1');
	assert.ok(harness.bridge.validatedOutbound >= 2);
	assert.ok(harness.bridge.validatedInbound >= 4, 'progress, observation, and result traffic used protocol-v2 translation');
	for (let index = 1; index < commands.length; index += 1) {
		const previousResult = harness.bridge.traffic.findIndex((entry) =>
				entry.type === 'action_result' && entry.actionId === commands[index - 1].actionId);
		const authoritativeObservation = harness.bridge.traffic.findIndex((entry, trafficIndex) =>
				trafficIndex > previousResult && entry.type === 'observation');
		const nextCommand = harness.bridge.traffic.findIndex((entry) =>
				entry.type === 'action_command' && entry.actionId === commands[index].actionId);
		assert.ok(previousResult >= 0 && previousResult < authoritativeObservation && authoritativeObservation < nextCommand,
				`command ${index + 1} follows result then authoritative observation`);
	}
	recordScenario('eight_logs_two_trees', harness);
});

test('reports an unreachable observed block as a typed model-visible failure', async () => {
	const unreachable = createHarness({
		initialObservation: observation({ blocks: [LOG_TREE('far-tree', 12)] }),
		onAction: async () => ({ state: 'FAILED', reasonCode: 'PATH_UNAVAILABLE', observation: observation({ blocks: [LOG_TREE('far-tree', 12)] }) }),
	});
	await unreachable.install(`
		program.onUnhandledAttention("continue_and_notify");
		const tree = world.nearest(world.blocks({ blockId: "minecraft:oak_log" }));
		const result = await tryResult(player.navigateTo({ x: tree.x, y: tree.y, z: tree.z, tolerance: 1, sprint: false, timeoutMs: 5_000 }));
		if (!result.succeeded) program.checkpoint(result.reason);
		program.finish("picked up");
	`);
	await eventually(() => unreachable.managerState() === DynamicAgentState.PAUSED);
	assert.equal(unreachable.bridge.results[0].reasonCode, 'PATH_UNAVAILABLE');
	assertCommandProvenance(commandPayloads(unreachable.bridge), SELECTED_PROFILE, 'program-1-1');
	recordScenario('unreachable_target', unreachable);
});

test('reports a disappearing observed block as a typed model-visible failure', async () => {
	const disappeared = createHarness({
		initialObservation: observation({ blocks: [LOG_TREE('vanishing-tree', 6)] }),
		onAction: async () => ({ observation: observation() }),
	});
	await disappeared.install(`
		program.onUnhandledAttention("continue_and_notify");
		const tree = world.nearest(world.blocks({ blockId: "minecraft:oak_log" }));
		const result = await tryResult(player.mine({ x: tree.x, y: tree.y, z: tree.z, expectedBlockId: tree.blockId, timeoutMs: 5_000 }));
		if (!result.succeeded) program.checkpoint(result.reason);
		if (inventory.count("minecraft:oak_log") < 1) program.checkpoint("BLOCK_DROPPED_NO_LOG");
		program.finish("picked up");
	`);
	await eventually(() => disappeared.managerState() === DynamicAgentState.PAUSED);
	assertCommandProvenance(commandPayloads(disappeared.bridge), SELECTED_PROFILE, 'program-1-1');
	recordScenario('disappearing_target', disappeared);
});

test('keeps inventory unchanged until a drop enters the modeled pickup radius', async () => {
	const moves = [];
	const harness = createHarness({
		initialObservation: observation({ items: [LOG_DROP('range-drop', 1, 8)] }),
		onAction: async (command, bridge) => {
			if (command.actionType !== 'navigate_to') return { observation: bridge.currentObservation };
			const target = command.arguments;
			const drop = bridge.currentObservation.items[0];
			const pickupDistance = distance(target, drop);
			moves.push({ target, pickupDistance, inventoryCount: inventoryCount(bridge.currentObservation.inventory, 'minecraft:oak_log') });
			const collected = pickupDistance <= PICKUP_RADIUS;
			return { observation: observation({ player: target, items: collected ? [] : [drop], inventory: collected ? { items: [{ itemId: 'minecraft:oak_log', count: 1 }], tagCounts: { '#minecraft:logs': 1 } } : bridge.currentObservation.inventory }) };
		},
	});
	await harness.install('program.onUnhandledAttention("continue_and_notify"); await player.navigateTo({ x: 4, y: 64, z: 0, tolerance: 1, sprint: false, timeoutMs: 5_000 }); if (inventory.count("minecraft:oak_log") < 1) await player.navigateTo({ x: 8, y: 64, z: 0, tolerance: 1, sprint: false, timeoutMs: 5_000 }); program.finish("picked up");');
	await eventually(() => harness.managerState() === DynamicAgentState.COMPLETED);
	assert.equal(moves.length, 2);
	assert.equal(moves[0].pickupDistance, 4);
	assert.equal(moves[0].inventoryCount, 0);
	assert.equal(moves[1].pickupDistance, 0);
	assert.equal(moves[1].inventoryCount, 0);
	assert.equal(inventoryCount(harness.bridge.currentObservation.inventory, 'minecraft:oak_log'), 1);
	assertCommandProvenance(commandPayloads(harness.bridge), SELECTED_PROFILE, 'program-1-1');
	recordScenario('drop_outside_pickup_radius', harness);
});

test('runs a matching damage watcher without a provider turn and follows both unmatched policies', async () => {
	const matching = createHarness({
		onAction: async (command) => command.actionType === 'break_block'
			? { attentionObservation: observation({ player: { health: 19 } }), defer: true }
			: { observation: observation({ player: { health: 19 } }) },
	});
	await matching.install(`
		program.onUnhandledAttention("continue_and_notify");
		program.watch(() => player.state().health < 20, { mode: "interrupt" }, async () => { await player.wait(1); });
		await player.mine({ x: 1, y: 64, z: 0, expectedBlockId: "minecraft:oak_log", timeoutMs: 1 });
		program.finish("damage handled");
	`);
	await eventually(() => matching.bridge.sent.filter((entry) => entry.type === 'action_command').some((entry) => entry.payload.actionType === 'wait'));
	assert.equal(matching.plannerCalls.length, 0);
	assert.ok(matching.bridge.sent.some((entry) => entry.type === 'action_cancel'));
	assertCommandProvenance(commandPayloads(matching.bridge), SELECTED_PROFILE, 'program-1-1');

	const continueHarness = createHarness({
		onAction: async () => ({ attentionObservation: observation({ player: { health: 19 } }) }),
	});
	await continueHarness.install('program.onUnhandledAttention("continue_and_notify"); await player.wait(1); program.finish("continued");');
	await eventually(() => continueHarness.managerState() === DynamicAgentState.COMPLETED);
	assert.equal(continueHarness.plannerCalls.length, 1);
	assert.match(continueHarness.plannerCalls[0].input, /program_attention/);
	assertCommandProvenance(commandPayloads(continueHarness.bridge), SELECTED_PROFILE, 'program-1-1');

	const pauseHarness = createHarness({
		plannerDecision: (request) => request.input.includes('program_attention') ? { summary: 'Pause.', directive: 'pause' } : null,
		onAction: async () => ({ attentionObservation: observation({ player: { health: 19 } }), defer: true }),
	});
	await pauseHarness.install('program.onUnhandledAttention("pause_and_notify"); await player.wait(1); program.finish("paused");');
	await eventually(() => pauseHarness.managerState() === DynamicAgentState.PAUSED);
	assert.equal(pauseHarness.plannerCalls.length, 1);
	assert.ok(pauseHarness.bridge.sent.some((entry) => entry.type === 'action_cancel'));
	assertCommandProvenance(commandPayloads(pauseHarness.bridge), SELECTED_PROFILE, 'program-1-1');
	recordScenario('matching_and_unmatched_damage', matching, continueHarness, pauseHarness);
});

test('runs pre-authored falling and lava interrupts without another provider turn', async () => {
	const falling = createHarness({
		initialObservation: observation({ player: { fallDistance: 0 } }),
		onAction: async (command) => command.actionType === 'break_block'
			? { attentionObservation: observation({ player: { fallDistance: 4 } }), defer: true }
			: { observation: observation({ player: { fallDistance: 4 } }) },
	});
	await falling.install(`
		program.onUnhandledAttention("continue_and_notify");
		program.watch(() => player.state().fallDistance > 3, { mode: "interrupt" }, async () => { await player.wait(1); });
		await player.mine({ x: 1, y: 64, z: 0, expectedBlockId: "minecraft:oak_log", timeoutMs: 1 });
	`);
	await eventually(() => falling.bridge.sent.filter((entry) => entry.type === 'action_command').some((entry) => entry.payload.actionType === 'wait'));
	assert.equal(falling.plannerCalls.length, 0);
	assertCommandProvenance(commandPayloads(falling.bridge), SELECTED_PROFILE, 'program-1-1');

	const lava = createHarness({
		initialObservation: observation({ player: { fire: false } }),
		onAction: async (command) => command.actionType === 'break_block'
			? { attentionObservation: observation({ player: { fire: true } }), defer: true }
			: { observation: observation({ player: { fire: true } }) },
	});
	await lava.install('program.onUnhandledAttention("continue_and_notify"); program.watch(() => player.state().fire === true, { mode: "interrupt" }, async () => { await player.wait(1); }); await player.mine({ x: 1, y: 64, z: 0, expectedBlockId: "minecraft:oak_log", timeoutMs: 1 });');
	await eventually(() => lava.bridge.sent.filter((entry) => entry.type === 'action_command').some((entry) => entry.payload.actionType === 'wait'));
	assert.equal(lava.plannerCalls.length, 0);
	assertCommandProvenance(commandPayloads(lava.bridge), SELECTED_PROFILE, 'program-1-1');
	recordScenario('preauthored_falling_and_lava_interrupt', falling, lava);
});

test('pauses when model-authored placement loses its support', async () => {
	const harness = createHarness({
		onAction: async () => ({ state: 'FAILED', reasonCode: 'PLACEMENT_SUPPORT_GONE', observation: observation({ blocks: [] }) }),
	});
	await harness.install(`
		program.onUnhandledAttention("continue_and_notify");
		const result = await tryResult(player.place({ x: 1, y: 64, z: 0, face: "up", itemId: "minecraft:stone" }));
		if (!result.succeeded) program.checkpoint(result.reason);
		program.finish("placed");
	`);
	await eventually(() => harness.managerState() === DynamicAgentState.PAUSED);
	assert.equal(harness.bridge.results[0].reasonCode, 'PLACEMENT_SUPPORT_GONE');
	assertCommandProvenance(commandPayloads(harness.bridge), SELECTED_PROFILE, 'program-1-1');
	recordScenario('disappearing_placement_support', harness);
});

test('pauses on path failure and timeout without selecting a replacement destination', async () => {
	const harness = createHarness({
		onAction: async () => ({ state: 'TIMED_OUT', reasonCode: 'PATH_TIMEOUT', observation: observation() }),
	});
	await harness.install(`
		program.onUnhandledAttention("continue_and_notify");
		const result = await tryResult(player.navigateTo({ x: 5, y: 64, z: 0, tolerance: 1, sprint: false, timeoutMs: 1 }));
		if (!result.succeeded) program.checkpoint(result.reason);
		program.finish("arrived");
	`);
	await eventually(() => harness.managerState() === DynamicAgentState.PAUSED);
	assert.equal(harness.bridge.results[0].state, 'TIMED_OUT');
	assert.equal(commandPayloads(harness.bridge).length, 1);
	assertCommandProvenance(commandPayloads(harness.bridge), SELECTED_PROFILE, 'program-1-1');
	recordScenario('path_failure_and_timeout', harness);
});

test('retains the selected model session and performs only model-commanded respawn', async () => {
	const harness = createProviderHarness({ initialObservation: observation({ player: { dead: true, health: 0 } }), decisions: [{ summary: 'Respawn.', directive: 'replace', source: 'program.onUnhandledAttention("continue_and_notify"); if (player.state().dead === true) await player.respawn(); program.finish("respawned");' }] });
	await harness.installFromProvider();
	await eventually(() => harness.managerState() === DynamicAgentState.COMPLETED);
	const commands = commandPayloads(harness.bridge);
	assert.deepEqual(commands.map((command) => command.actionType), ['respawn']);
	assert.equal(harness.profile.model, SELECTED_PROFILE.model);
	assert.equal(harness.providerCalls, 1);
	assert.strictEqual(harness.session, harness.sessionIdentity);
	assert.strictEqual(harness.providerService.getAgent(SELECTED_PROFILE.agentId), harness.sessionIdentity);
	assert.equal(harness.providerRecord.agentId, SELECTED_PROFILE.agentId);
	assert.equal(harness.providerRecord.model, SELECTED_PROFILE.model);
	assertCommandProvenance(commands, SELECTED_PROFILE, 'program-1-1');
	recordScenario('death_retention_and_model_respawn', harness);
});

test('corrects invalid source through the same selected model', async () => {
	const harness = createProviderHarness({ decisions: [{ invalid: true }, { summary: 'Corrected.', directive: 'replace', source: 'program.onUnhandledAttention("continue_and_notify"); await player.wait(1); program.finish("corrected");' }] });
	await harness.install('program.onUnhandledAttention("continue_and_notify"); await player.wait(');
	await eventually(() => harness.managerState() === DynamicAgentState.COMPLETED, `correction did not complete: providerCalls=${harness.providerCalls}, inputs=${harness.sessionInputs.length}`);
	assert.equal(harness.providerCalls, 2);
	assert.strictEqual(harness.session, harness.sessionIdentity);
	assert.strictEqual(harness.providerService.getAgent(SELECTED_PROFILE.agentId), harness.sessionIdentity);
	assert.equal(harness.providerRecord.agentId, SELECTED_PROFILE.agentId);
	assert.equal(harness.providerRecord.model, SELECTED_PROFILE.model);
	assert.equal(harness.sessionInputs.length, 2);
	assertCommandProvenance(commandPayloads(harness.bridge), SELECTED_PROFILE, 'program-1-1');
	recordScenario('same_model_source_correction', harness);
});

test('rejects a physical command without model-program provenance before bridge acceptance', async () => {
	const harness = createHarness();
	await assert.rejects(
		() => harness.bridge.send('action_command', SELECTED_PROFILE.agentId, { traceId: 'trace-unauthorised', goalRevision: 1, actionId: 'unauthorised', actionType: 'wait', arguments: { durationMs: 1 } }),
		(error) => /provenance|MISSING_FIELD/i.test(error.message),
	);
	assert.equal(commandPayloads(harness.bridge).length, 0);
	assert.throws(
		() => validateProtocolV2Payload('action_command', { traceId: 'trace-unauthorised', goalRevision: 1, actionId: 'unauthorised', actionType: 'wait', arguments: { durationMs: 1 } }),
		/provenance|MISSING_FIELD/i,
	);
	scenarioResults.push({ name: 'provenance_rejection', passed: true });
});

function createHarness({ initialObservation = observation(), onAction = async () => ({ observation: initialObservation }), plannerDecision = () => null } = {}) {
	const registry = new AgentRegistry({ agentCap: 1 });
	const profile = { ...SELECTED_PROFILE };
	const currentGoalSpec = fixtureGoalSpec('Task 10 E2E');
	registry.register({ ...profile, state: DynamicAgentState.STARTING, goalRevision: 1, currentGoal: 'Task 10 E2E', currentGoalSpec, queue: [] });
	const record = registry.get(profile.agentId);
	const plannerCalls = [];
	let plannerTime = 0;
	const planner = {
		requestPlan: async (request) => {
			plannerTime += 4;
			providerLatencyMs.push(plannerTime);
			const call = { ...request, model: record.model };
			plannerCalls.push(call);
			return plannerDecision(request) ?? { summary: 'Continue.', directive: 'continue' };
		},
	};
	const harness = { profile, registry, record, plannerCalls, manager: null, bridge: null, count: new Map(), totalCommands: 0, movementEvidence: [], install: null, managerState: () => registry.get(profile.agentId).state };
	const bridge = new FakeMinecraftBridge({ record, initialObservation, onAction: async (command, fakeBridge) => {
		harness.count.set(command.actionType, (harness.count.get(command.actionType) ?? 0) + 1);
		harness.totalCommands += 1;
		return onAction(command, fakeBridge);
	} });
	const manager = new ProgramRuntimeManager({
		registry,
		bridge,
		planner,
		latencyRegistry: latency,
		clock: () => ++harness.clock,
		onCompletionRequested: (request) => queueMicrotask(() => harness.manager.onCompletionResult(record, {
			goalRevision: request.record.goalRevision,
			traceId: request.traceId,
			goalFingerprint: request.goalFingerprint,
			verified: true,
			reasonCode: 'COMPLETION_VERIFIED',
		})),
	});
	harness.clock = 0;
	bridge.attach(manager);
	harness.manager = manager;
	harness.bridge = bridge;
	harness.install = (source) => harness.manager.installDecision(record, withCompletionContract({ summary: 'Task 10 source', directive: 'replace', source }, record.goalRevision), { observation: bridge.currentObservation, eventSequence: 1 });
	return harness;
}

function createProviderHarness({ initialObservation = observation(), onAction = async () => ({ observation: initialObservation }), decisions = [] } = {}) {
	const harness = createHarness({ initialObservation, onAction });
	const session = {
		identity: Symbol('selected-provider-session'),
		goalRevision: 0,
		inputs: [],
		async setGoalRevision(goalRevision) { this.goalRevision = goalRevision; },
		async decide(input, options) {
			harness.providerCalls += 1;
			this.inputs.push(input);
			const next = decisions.shift();
			if (next?.invalid === true) throw Object.assign(new Error('invalid source'), { code: 'INVALID_DECISION' });
			if (!next) throw new Error('provider decision queue exhausted');
			return withCompletionContract(next, options?.goalRevision ?? this.goalRevision);
		},
	};
	const providerService = {
		async createAgent(record) { harness.providerRecord = record; return session; },
		getAgent() { return session; },
		async removeAgent() {},
	};
	const planner = new AgentPlanner({ registry: harness.registry, scheduler: new PlanningScheduler({ maxConcurrent: 1, maxPending: 0 }), codexService: providerService, now: () => 1 });
	harness.providerCalls = 0;
	harness.sessionInputs = session.inputs;
	harness.session = session;
	harness.sessionIdentity = session;
	harness.providerService = providerService;
	harness.planner = planner;
	harness.manager = new ProgramRuntimeManager({
		registry: harness.registry,
		bridge: harness.bridge,
		planner,
		latencyRegistry: latency,
		clock: () => ++harness.clock,
		onCompletionRequested: (request) => queueMicrotask(() => harness.manager.onCompletionResult(harness.record, {
			goalRevision: request.record.goalRevision,
			traceId: request.traceId,
			goalFingerprint: request.goalFingerprint,
			verified: true,
			reasonCode: 'COMPLETION_VERIFIED',
		})),
	});
	harness.bridge.attach(harness.manager);
	harness.installFromProvider = async () => {
		const decision = await planner.requestPlan({ agentId: SELECTED_PROFILE.agentId, input: 'Task 10 provider decision', goalRevision: 1 });
		return harness.manager.installDecision(harness.record, withCompletionContract(decision, harness.record.goalRevision), { observation: harness.bridge.currentObservation, eventSequence: 1 });
	};
	return harness;
}

function fixtureGoalSpec(originalRequest) {
	const fields = { originalRequest, predicate: { type: 'operator_confirmed' }, createdAtTick: 1 };
	return Object.freeze({ ...fields, fingerprint: goalSpecFingerprint(fields) });
}

function harnessCount(command, harness) {
	return harness.totalCommands;
}

function distance(left, right) {
	return Math.hypot(left.x - right.x, left.y - right.y, left.z - right.z);
}

function inventoryCount(inventory, itemId) {
	return (inventory?.items ?? []).reduce((total, item) => total + (item.itemId === itemId ? item.count : 0), 0);
}

function recordScenario(name, ...entries) {
	const details = entries.at(-1)?.subcases ? entries.pop() : {};
	const harnesses = entries;
	scenarioResults.push({ name, passed: true, commands: harnesses.reduce((total, harness) => total + commandPayloads(harness.bridge).length, 0), ...details });
}

after(() => {
	const syntheticLocal = latency.snapshot();
	const syntheticProvider = [{ operation: 'provider_inference', ...summarize(providerLatencyMs) }];
	const targetSubcases = ['unreachable_target', 'disappearing_target'];
	const scenarios = scenarioResults.filter(({ name }) => !targetSubcases.includes(name));
	const groupedSubcases = scenarioResults.filter(({ name }) => targetSubcases.includes(name));
	const firstGroupIndex = scenarioResults.findIndex(({ name }) => targetSubcases.includes(name));
	const groupedTargets = { name: 'unreachable_and_disappearing_targets', passed: groupedSubcases.every(({ passed }) => passed), commands: groupedSubcases.reduce((total, scenario) => total + (scenario.commands ?? 0), 0), subcases: groupedSubcases.map(({ name }) => name) };
	if (firstGroupIndex >= 0) scenarios.splice(Math.min(firstGroupIndex, scenarios.length), 0, groupedTargets);
	console.log(`TASK10_E2E_SUMMARY ${JSON.stringify({ scenarios, passed: scenarios.length, timing: { basis: 'deterministic_fake_clock', syntheticLocal, syntheticProvider, benchmarkRequired: true } })}`);
});

async function eventually(predicate, message = 'condition was not reached') {
	const deadline = Date.now() + 2_000;
	while (Date.now() < deadline) {
		if (predicate()) return;
		await new Promise((resolve) => setTimeout(resolve, 2));
	}
	throw new Error(message);
}

function summarize(values) {
	const sorted = [...values].sort((left, right) => left - right);
	return sorted.length === 0 ? { count: 0, p50Ms: null, p95Ms: null } : { count: sorted.length, p50Ms: percentile(sorted, 0.5), p95Ms: percentile(sorted, 0.95) };
}

function percentile(sorted, fraction) {
	return sorted[Math.max(0, Math.ceil(sorted.length * fraction) - 1)];
}
