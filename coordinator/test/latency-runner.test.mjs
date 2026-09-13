import assert from 'node:assert/strict';
import { mkdir, mkdtemp, readFile, rename, rm, writeFile } from 'node:fs/promises';
import os from 'node:os';
import path from 'node:path';
import test from 'node:test';

import { runLatencyMatrix, normalizeLatencyMatrix } from '../src/benchmark/latency-runner.mjs';
import { BenchmarkRecorder } from '../src/benchmark/benchmark-recorder.mjs';
import { createReplayProvider, createReplayRecord } from '../src/benchmark/provider-replay.mjs';
import { getSimulatorScenario } from '../src/simulator/simulator-scenarios.mjs';
import { compileScenarioDecision } from '../src/benchmark/scenario-program.mjs';
import { VIRTUAL_TICK_MS, VirtualWorld } from '../src/simulator/virtual-world.mjs';
import { VirtualMinecraftBridge } from '../src/simulator/virtual-minecraft-bridge.mjs';

const PROFILE = Object.freeze({ provider: 'instant', model: 'deterministic-v1', reasoningEffort: 'fixed', serviceTier: 'local' });
const SOURCE = 'program.onUnhandledAttention("continue_and_notify"); await player.wait(1); program.finish("done");';

function matrix(overrides = {}) {
	return {
		version: 1,
		benchmarkVersion: 'latency-ab-v1',
		protocolVersion: 2,
		fixedSeeds: [42],
		agentLoads: [1, 4, 8, 16],
		trials: [1, 4, 8, 16].map((agentLoad) => ({
			id: `instant-${agentLoad}`, mode: 'instant', scenarioId: 'fixture-wait', seed: 42, agentLoad,
			providerProfile: PROFILE, repetitions: 1, turnBudgetMs: 100, trialBudgetMs: 1_000, turnCap: 2,
			providerAvailabilityRequired: false,
		})),
		...overrides,
	};
}

function instantProvider() {
	return {
		available: true,
		async start() {},
		async stop() {},
		async createAgent() {
			return { async setGoalRevision() {}, async decide() { return fixtureDecision(); } };
		},
	};
}

function fixtureScenario() {
	return {
		id: 'fixture-wait', seed: 42, agentId: 'agent-a', goal: 'Wait.',
		world: { seed: 42, agents: { 'agent-a': { position: { x: 0, y: 1, z: 0 }, onGround: true } }, blocks: [{ x: 0, y: 0, z: 0, blockId: 'minecraft:stone' }] },
		commands: [], events: [], expected: {},
	};
}

function movementScenario() {
	return {
		id: 'fixture-movement', seed: 42, agentId: 'agent-a', goal: 'Move.',
		world: { seed: 42, agents: { 'agent-a': { position: { x: 0, y: 1, z: 0 }, onGround: true } }, blocks: [{ x: 0, y: 0, z: 0, blockId: 'minecraft:stone' }] },
		commands: [{ actionId: 'move-1', actionType: 'navigate_to', arguments: { x: 1, y: 1, z: 0, tolerance: 0.2, sprint: false, timeoutMs: 5_000 } }], events: [], expected: {},
	};
}

function fixtureDecision({ summary = 'done', source = SOURCE, scenario = fixtureScenario() } = {}) {
	void scenario;
	return { summary, directive: 'replace', source };
}

function movementProvider() {
	return {
		available: true,
		async start() {},
		async stop() {},
		async createAgent() {
			return { async setGoalRevision() {}, async decide() { return fixtureDecision({ summary: 'move', source: 'program.onUnhandledAttention("continue_and_notify"); await player.navigateTo({ x: 1, y: 1, z: 0, tolerance: 0.2, sprint: false, timeoutMs: 5_000 }); program.finish("done");', scenario: movementScenario() }); } };
		},
	};
}

test('strictly normalizes matrix identity, budgets, loads, and unique trial IDs', () => {
	const normalized = normalizeLatencyMatrix(matrix());
	assert.deepEqual(normalized.agentLoads, [1, 4, 8, 16]);
	assert.deepEqual(normalized.fixedSeeds, [42]);
	assert.equal(normalized.trials.length, 4);
	assert.ok(Object.isFrozen(normalized));
	assert.throws(() => normalizeLatencyMatrix(matrix({ trials: [matrix().trials[0], { ...matrix().trials[0], id: 'instant-1' }] })), /unique/i);
	assert.throws(() => normalizeLatencyMatrix(matrix({ trials: [{ ...matrix().trials[0], turnBudgetMs: Infinity }] })), /finite/i);
	assert.throws(() => normalizeLatencyMatrix(matrix({ trials: [{ ...matrix().trials[0], agentLoad: 2 }] })), /1, 4, 8, 16/);
	assert.throws(() => normalizeLatencyMatrix(matrix({ trials: [{
		...matrix().trials[0], id: 'disabled-gemini', mode: 'live',
		providerProfile: { provider: 'gemini', model: 'gemini-3.1-pro', reasoningEffort: 'high', serviceTier: 'priority' },
	}] })), /Codex or Kimi/);
});

test('runs deterministic instant full-path trials at every declared load', async () => {
	// This checks coordinator behavior; shared CI runners need scheduling headroom.
	const trials = matrix().trials.map((trial) => ({ ...trial, turnBudgetMs: 1_000, trialBudgetMs: 10_000 }));
	const result = await runLatencyMatrix({
		matrix: matrix({ trials }),
		scenarioResolver: () => fixtureScenario(),
		providerFactories: { instant: () => instantProvider() },
		artifactDirectory: null,
	});
	assert.equal(result.status, 'PASSED', JSON.stringify(result.trials.map(({ id, status, error }) => ({ id, status, error }))));
	assert.deepEqual(result.trials.map((trial) => trial.agentLoad), [1, 4, 8, 16]);
	assert.ok(result.trials.every((trial) => trial.status === 'PASSED'));
	assert.ok(result.trials.every((trial) => trial.outcomeHash.startsWith('sha256:')));
	for (const trial of result.trials) {
		assert.equal(trial.benchmark.traces.length, trial.agentLoad);
		for (const trace of trial.benchmark.traces) {
			assert.equal(trace.complete, true);
			assert.deepEqual(trace.phases.map((phase) => phase.phase), [
				'queue_wait', 'provider_first_byte', 'provider_final_byte', 'parse',
				'first_command_dispatch', 'first_world_action', 'completion_verification',
			]);
		}
	}
	assert.equal(result.cleanup.ok, true);
});

test('reports explicit injected wall-clock and virtual-clock measurements', async () => {
	let wall = 0;
	const result = await runLatencyMatrix({
		matrix: matrix({ trials: [{ ...matrix().trials[0], id: 'measurement', scenarioId: 'fixture-movement', agentLoad: 1 }] }),
		scenarioResolver: () => movementScenario(),
		providerFactories: { instant: () => movementProvider() },
		wallClock: () => (wall += 100),
		artifactDirectory: null,
	});
	const metrics = result.trials[0].metrics;
	assert.equal(result.status, 'PASSED');
	assert.deepEqual(metrics.clockBasis, { wall: 'injected_monotonic_ms', virtual: 'virtual_world_ms' });
	assert.ok(Number.isFinite(metrics.result.goalWallTimestampMs));
	assert.ok(Number.isFinite(metrics.result.initialObservationWallTimestampMs));
	assert.ok(metrics.result.firstActionCommandAcceptanceWallLatencyMs >= 0);
	assert.ok(metrics.result.firstAuthoritativePhysicalDisplacementWallLatencyMs >= 0);
	assert.ok(metrics.result.firstAuthoritativePhysicalDisplacementVirtualLatencyMs >= 0);
	assert.ok(metrics.result.taskCompletionWallDurationMs >= 0);
	assert.ok(metrics.result.taskCompletionVirtualDurationMs > 0);
	assert.ok(metrics.result.totalVirtualWorldDurationMs >= metrics.result.taskCompletionVirtualDurationMs);
	assert.equal(metrics.result.tick.over50MsCount, metrics.result.tick.count);
	assert.equal(metrics.result.tick.maxMs >= 50, true);
	assert.equal(metrics.result.tick.basis, 'monotonic_wall_duration_ms');
	assert.equal(metrics.result.tick.cpuWallDurationP95Ms, undefined);
	assert.ok(metrics.raw.ticks.length > 0);
	assert.equal(metrics.raw.ticks.every((tick) => tick.cpuWallDurationMs === undefined), true);
	assert.ok(metrics.raw.providerPlanningWait.length > 0);
});

test('aggregates acceptance and physical displacement across every agent at load four', async () => {
	const result = await runLatencyMatrix({
		matrix: matrix({ trials: [{ ...matrix().trials[0], id: 'movement-load-four', scenarioId: 'fixture-movement', agentLoad: 4 }] }),
		scenarioResolver: () => movementScenario(),
		providerFactories: { instant: () => movementProvider() },
		artifactDirectory: null,
	});
	const metrics = result.trials[0].metrics;
	assert.equal(result.trials[0].status, 'PASSED');
	assert.equal(metrics.raw.actionCommandAcceptance.length, 4);
	assert.equal(metrics.raw.physicalDisplacement.length, 4);
	assert.equal(metrics.result.actionCommandAcceptance.count, 4);
	assert.equal(metrics.result.actionCommandAcceptance.wallLatencySamplesMs.length, 4);
	assert.ok(metrics.result.actionCommandAcceptance.wallLatencyP50Ms !== null);
	assert.ok(metrics.result.actionCommandAcceptance.wallLatencyP95Ms !== null);
	assert.ok(metrics.result.actionCommandAcceptance.wallLatencyP99Ms !== null);
	assert.ok(metrics.result.actionCommandAcceptance.wallLatencyMaxMs !== null);
	assert.equal(metrics.result.authoritativePhysicalDisplacement.count, 4);
	assert.equal(metrics.result.authoritativePhysicalDisplacement.wallLatencySamplesMs.length, 4);
	assert.ok(metrics.result.authoritativePhysicalDisplacement.wallLatencyP50Ms !== null);
	assert.ok(metrics.result.firstActionCommandAcceptance.agentId);
	assert.ok(metrics.result.firstAuthoritativePhysicalDisplacement.agentId);
	assert.ok(metrics.result.firstAuthoritativePhysicalDisplacement.actionId);
	assert.ok(['move_to', 'navigate_to'].includes(metrics.result.firstAuthoritativePhysicalDisplacement.actionType));
});

test('preserves bounded pairing context and reports per-trial process deltas', async () => {
	const processSamples = [
		{ cpuUserMs: 10, cpuSystemMs: 4, rssBytes: 100, heapUsedBytes: 50 },
		{ cpuUserMs: 17, cpuSystemMs: 6, rssBytes: 130, heapUsedBytes: 70 },
	];
	const recorder = new BenchmarkRecorder({ clock: () => 1 });
	const result = await runLatencyMatrix({
		matrix: matrix({ trials: [{ ...matrix().trials[0], id: 'context' }] }),
		scenarioResolver: () => fixtureScenario(),
		providerFactories: { instant: () => instantProvider() },
		baseContext: { arm: 'baseline', runId: 'run-1', sourceHash: 'sha256:source', configHash: 'sha256:config', pairingKey: 'pair-1', ignored: 'not-public' },
		recorder,
		systemSamplerOptions: { processReader: () => processSamples.shift() ?? { cpuUserMs: 17, cpuSystemMs: 6, rssBytes: 130, heapUsedBytes: 70 } },
		artifactDirectory: null,
	});
	const trial = result.trials[0];
	assert.equal(trial.arm, 'baseline');
	assert.equal(trial.runId, 'run-1');
	assert.equal(trial.sourceHash, 'sha256:source');
	assert.equal(trial.configHash, 'sha256:config');
	assert.equal(trial.pairingKey, 'pair-1');
	assert.equal(trial.ignored, undefined);
	assert.ok(recorder.snapshot().every((event) => event.arm === 'baseline' && event.runId === 'run-1' && event.sourceHash === 'sha256:source' && event.configHash === 'sha256:config' && event.pairingKey === 'pair-1'));
	assert.deepEqual(trial.systemSummary.cpuDelta, { basis: 'process_resource_usage_delta_ms', userMs: 7, systemMs: 2, totalMs: 9 });
	assert.deepEqual(trial.systemSummary.memoryDelta, { basis: 'process_memory_sample_delta_bytes', rssBytes: 30, heapUsedBytes: 20 });
	assert.equal(trial.systemSummary.memoryPeak.rssBytes, 130);
	assert.equal(trial.timingScope, 'full_path');
	assert.ok(trial.durationMs >= 0);
	assert.ok(trial.taskDurationMs >= 0);
	assert.ok(trial.setupDurationMs >= 0);
	assert.equal(trial.totalDurationMs, trial.durationMs);
	assert.ok(trial.durationMs >= trial.taskDurationMs + trial.setupDurationMs - 1);
	assert.ok(Number.isFinite(trial.setupSpansMs.coordinatorStart));
});

test('records only declared hazard and direct-message event timing', async () => {
	const base = matrix().trials[0];
	const result = await runLatencyMatrix({
		matrix: matrix({ trials: [
			{ ...base, id: 'lava-metrics', scenarioId: 'lava-damage-reaction', turnCap: 20 },
			{ ...base, id: 'message-metrics', scenarioId: 'direct-message-wake', turnCap: 20 },
		] }),
		scenarioResolver: (id) => getSimulatorScenario(id),
		wallClock: () => 1,
		artifactDirectory: null,
	});
	const lava = result.trials.find((trial) => trial.scenarioId === 'lava-damage-reaction');
	const message = result.trials.find((trial) => trial.scenarioId === 'direct-message-wake');
	assert.equal(lava.status, 'PASSED');
	assert.equal(lava.metrics.raw.hazardReaction.length, 1);
	assert.equal(lava.metrics.raw.hazardReaction[0].eventId, 'lava-hazard-1');
	assert.equal(lava.metrics.result.hazardReaction.reactionActionType, 'navigate_to');
	assert.equal(lava.metrics.raw.hazardReaction[0].reactionWallLatencyMs, 0);
	assert.equal(message.status, 'PASSED');
	assert.equal(message.metrics.result.directMessageReaction.eventId, 'conversation-1');
	assert.equal(message.metrics.result.directMessageReaction.reactionWallLatencyMs, null);
	const respawnScenario = {
		id: 'respawn-metrics-fixture', agentId: 'respawn-agent', goal: 'Respawn.',
		world: { seed: 42, agents: { 'respawn-agent': { position: { x: 0, y: 1, z: 0 }, health: 0, dead: true, checkpoint: { x: 3, y: 1, z: 3 } } }, blocks: [{ x: 0, y: 0, z: 0, blockId: 'minecraft:stone' }] },
		commands: [], events: [], expected: {},
	};
	const respawn = await runLatencyMatrix({
		matrix: matrix({ trials: [{ ...base, id: 'respawn-metrics', scenarioId: respawnScenario.id }] }),
		scenarioResolver: () => respawnScenario,
		providerFactories: { instant: () => ({ available: true, async createAgent() { return { async setGoalRevision() {}, async decide() { return fixtureDecision({ summary: 'respawn', source: 'program.onUnhandledAttention("continue_and_notify"); await player.respawn(); program.finish("done");' }); } }; } }) },
		artifactDirectory: null,
	});
	assert.equal(respawn.status, 'PASSED');
	assert.equal(respawn.trials[0].metrics.raw.physicalDisplacement.length, 0);
});

test('does not attribute a sender follow-up action as a direct-message recipient reaction', async () => {
	const base = getSimulatorScenario('direct-message-wake');
	const scenario = {
		...base,
		id: 'sender-only-direct-message',
		commands: [...base.commands, { actionId: 'sender-follow-up', actionType: 'wait', arguments: { durationMs: 50 } }],
		expected: { ...base.expected, actionIds: ['direct-message-1', 'sender-follow-up'] },
	};
	const trial = { ...matrix().trials[0], id: 'sender-only-direct-message', scenarioId: scenario.id, turnCap: 20 };
	const result = await runLatencyMatrix({ matrix: matrix({ trials: [trial] }), scenarioResolver: () => scenario, artifactDirectory: null });
	const reaction = result.trials[0].metrics.result.directMessageReaction;
	assert.equal(result.trials[0].status, 'PASSED');
	assert.equal(reaction.eventId, 'conversation-1');
	assert.equal(reaction.reactionWallLatencyMs, null);
	assert.equal(reaction.reactionVirtualLatencyMs, null);
});

test('measurement instrumentation does not change authoritative action command bytes', async () => {
	const run = (measurements) => runLatencyMatrix({
		matrix: matrix({ trials: [{ ...matrix().trials[0], id: measurements ? 'metrics-on' : 'metrics-off', scenarioId: 'fixture-movement', agentLoad: 1 }] }),
		scenarioResolver: () => movementScenario(),
		providerFactories: { instant: () => movementProvider() },
		measurements,
		artifactDirectory: null,
	});
	const enabled = await run(true);
	const disabled = await run(false);
	assert.match(enabled.trials[0].debug.actionCommandHash, /^sha256:/);
	assert.match(disabled.trials[0].debug.actionCommandHash, /^sha256:/);
	assert.equal(enabled.trials[0].debug.actionCommandHash, disabled.trials[0].debug.actionCommandHash);
	assert.equal(enabled.trials[0].status, disabled.trials[0].status);
	assert.equal(disabled.trials[0].metrics, null);
	assert.equal(disabled.trials[0].systemSummary, null);
	assert.equal(disabled.trials[0].benchmark.eventCount, 0);
	assert.deepEqual(disabled.trials[0].benchmark.traces, []);
	assert.equal(disabled.summary, 0);
});

test('polls conversation history only while a declared chat event is pending', async () => {
	const original = VirtualWorld.prototype.conversationEvents;
	let calls = 0;
	VirtualWorld.prototype.conversationEvents = function measuredConversationEvents() { calls += 1; return original.call(this); };
	try {
		const base = matrix().trials[0];
		const result = await runLatencyMatrix({
			matrix: matrix({ trials: [{ ...base, id: 'message-scan', scenarioId: 'direct-message-wake', turnCap: 40 }] }),
			scenarioResolver: (id) => {
				const scenario = getSimulatorScenario(id);
				return { ...scenario, commands: [{ actionId: 'scan-delay', actionType: 'wait', arguments: { durationMs: 1_000 } }, ...scenario.commands] };
			},
			artifactDirectory: null,
		});
		assert.equal(result.trials[0].status, 'PASSED');
		assert.equal(result.trials[0].metrics.raw.directMessageReaction.length, 1);
		assert.ok(calls > 0);
		assert.ok(calls < result.trials[0].metrics.raw.ticks.length, `conversation history was read ${calls} times for ${result.trials[0].metrics.raw.ticks.length} ticks`);
	} finally {
		VirtualWorld.prototype.conversationEvents = original;
	}
});

test('marks synthetic identity and returns scoped benchmark and system summaries', async () => {
	const recorder = new BenchmarkRecorder({ clock: () => 1 });
	const result = await runLatencyMatrix({
		matrix: matrix({ trials: [{ ...matrix().trials[0], id: 'summary-trial' }] }),
		scenarioResolver: () => fixtureScenario(),
		providerFactories: { instant: () => instantProvider() },
		recorder,
		artifactDirectory: null,
	});
	const trial = result.trials[0];
	assert.deepEqual(trial.providerIdentity, { provider: 'instant', synthetic: true });
	assert.ok(result.benchmarkSummary.eventCount > 0);
	assert.ok(trial.benchmark.eventCount > 0);
	assert.ok(trial.systemSummary.sampleCount >= 2);
	assert.ok(trial.systemSummary.immediateSample);
	assert.ok(trial.systemSummary.finalSample);
	assert.ok(trial.systemSummary.scheduler.active.p50 !== null);
	assert.ok(trial.systemSummary.scheduler.pending.p50 !== null);
	assert.ok(recorder.snapshot().every((row) => row.trialId === 'summary-trial'));
});

test('shipped default matrix proves physical stone-tool success for every isolated load', async () => {
	const result = await runLatencyMatrix({ artifactDirectory: null });
	assert.equal(result.status, 'PASSED');
	assert.equal(result.trials.length, 20);
	assert.ok(result.trials.every((trial) => trial.status === 'PASSED'));
	assert.deepEqual([...new Set(result.trials.map((trial) => trial.repetition))], [1, 2, 3, 4, 5]);
	assert.ok(result.trials.every((trial) => trial.debug?.scenarioPassed === true));
	assert.ok(result.trials.every((trial) => trial.debug?.turnCount <= trial.agentLoad * 2));
	assert.ok(result.trials.every((trial) => trial.debug?.scenarioEvidence?.every((agent) => agent.actionResults === 3 && agent.succeeded === 3 && agent.cancelled === 0 && agent.failed === 0)));
	assert.ok(result.trials.every((trial) => trial.debug?.scenarioDigest?.startsWith('sha256:')));
});

test('publishes goal and factual verification phase events for deterministic harness reports', async () => {
	const result = await runLatencyMatrix({ matrix: matrix({ trials: [{ ...matrix().trials[0], id: 'phase-events' }] }), scenarioResolver: () => fixtureScenario(), includeRawEvents: true, artifactDirectory: null });
	const stages = new Set(result.rawEvents.map((event) => event.stage ?? event.phase));
	for (const stage of ['goal_received', 'verification_started', 'verification_completed', 'goal_completed']) assert.equal(stages.has(stage), true, `missing ${stage}`);
});

test('delayed stone-tool pacing completes in one planner turn without reactive completion requests', async () => {
	const profile = { provider: 'codex', model: 'stone-fixture', reasoningEffort: 'fixed', serviceTier: 'local' };
	const scenario = getSimulatorScenario('stone-tool-gathering');
	const decision = compileScenarioDecision(scenario);
	let turns = 0;
	const recorder = new BenchmarkRecorder();
	const result = await runLatencyMatrix({
		matrix: matrix({
			trials: [{
				...matrix().trials[0], id: 'stone-delayed-completion', mode: 'live', scenarioId: scenario.id,
				providerProfile: profile, trialBudgetMs: 5_000, turnBudgetMs: 1_000, turnCap: 4,
			}],
		}),
		scenarioResolver: () => scenario,
		providerFactories: {
			codex: () => ({
				available: true, provider: profile.provider, model: profile.model,
				reasoningEffort: profile.reasoningEffort, serviceTier: profile.serviceTier, providerProfile: profile,
				async createAgent() {
					return {
						async setGoalRevision() {},
						async decide() {
							turns += 1;
							return turns === 1 ? decision : { directive: 'continue', summary: 'continue' };
						},
					};
				},
				async stop() {},
			}),
		},
		recorder,
		artifactDirectory: null,
	});

	assert.equal(result.trials[0].status, 'PASSED');
	assert.equal(result.trials[0].debug.turnCount, 1);
	assert.equal(turns, 1);
	assert.equal(recorder.snapshot().filter((event) => event.stage === 'planner_requested').length, 1);
});

test('skips optional unavailable providers, fails required providers, and never substitutes', async () => {
	const unavailable = () => ({ available: false, reason: 'fixture unavailable' });
	const optional = await runLatencyMatrix({ matrix: matrix({ trials: [{ ...matrix().trials[0], id: 'optional', mode: 'live', providerProfile: { provider: 'codex', model: 'fixture', reasoningEffort: 'high', serviceTier: 'fast' }, providerAvailabilityRequired: false }] }), scenarioResolver: () => fixtureScenario(), providerFactories: { codex: unavailable }, artifactDirectory: null });
	assert.equal(optional.trials[0].status, 'SKIPPED');
	assert.equal(optional.trials[0].providerProfile.provider, 'codex');
	await assert.rejects(() => runLatencyMatrix({ matrix: matrix({ trials: [{ ...matrix().trials[0], id: 'required', mode: 'live', providerProfile: { provider: 'codex', model: 'fixture', reasoningEffort: 'high', serviceTier: 'fast' }, providerAvailabilityRequired: true }] }), scenarioResolver: () => fixtureScenario(), providerFactories: { codex: unavailable }, artifactDirectory: null }), (error) => error.code === 'PROVIDER_UNAVAILABLE');
});

test('writes artifacts before throwing for a required unavailable provider', async () => {
	const directory = await mkdtemp(path.join(os.tmpdir(), 'latency-required-artifacts-'));
	try {
		await assert.rejects(() => runLatencyMatrix({
			matrix: matrix({ trials: [{ ...matrix().trials[0], id: 'required-artifact', providerAvailabilityRequired: true }] }),
			scenarioResolver: () => fixtureScenario(),
			providerFactories: { instant: () => ({ available: false, reason: 'fixture unavailable' }) },
			artifactDirectory: directory,
		}), (error) => error.code === 'PROVIDER_UNAVAILABLE' && error.result?.status === 'FAILED');
		const manifest = JSON.parse(await readFile(path.join(directory, 'latency-manifest.json'), 'utf8'));
		assert.equal(manifest.status, 'FAILED');
		assert.equal(manifest.trials[0].error.code, 'PROVIDER_UNAVAILABLE');
	} finally {
		await rm(directory, { recursive: true, force: true });
	}
});

test('requires an exact declared identity for live providers', async () => {
	const liveProfile = { provider: 'codex', model: 'fixture-model', reasoningEffort: 'high', serviceTier: 'fast' };
	const liveTrial = { ...matrix().trials[0], id: 'live-identity', mode: 'live', providerProfile: liveProfile, providerAvailabilityRequired: true };
	const session = { async setGoalRevision() {}, async decide() { return fixtureDecision(); } };
	for (const provider of [
		{ available: true, async createAgent() { return session; }, async stop() {} },
		{ available: true, provider: 'gemini', async createAgent() { return session; }, async stop() {} },
		{ available: true, provider: 'codex', model: 'different-model', async createAgent() { return session; }, async stop() {} },
	]) {
		const result = await runLatencyMatrix({
			matrix: matrix({ trials: [liveTrial] }),
			scenarioResolver: () => fixtureScenario(),
			providerFactories: { codex: () => provider },
			artifactDirectory: null,
		});
		assert.equal(result.trials[0].status, 'FAILED');
		assert.equal(result.trials[0].error.code, 'PROVIDER_MISMATCH');
	}
});

test('turn and trial timeouts produce typed bounded failures and clean provider lifecycle', async () => {
	let stopped = 0;
	const hanging = () => ({
		async start() {}, async stop() { stopped += 1; },
		async createAgent() { return { async setGoalRevision() {}, decide() { return new Promise(() => {}); } }; },
	});
	const result = await runLatencyMatrix({
		matrix: matrix({ trials: [{ ...matrix().trials[0], id: 'timeout', turnBudgetMs: 50, trialBudgetMs: 5_000 }] }),
		scenarioResolver: () => fixtureScenario(), providerFactories: { instant: hanging }, artifactDirectory: null,
	});
	assert.equal(result.trials[0].status, 'TIMED_OUT');
	assert.equal(result.trials[0].error.code, 'TURN_TIMEOUT');
	assert.equal(stopped, 1);
	assert.equal(result.cleanup.ok, true);
});

test('createAgent is bounded by the trial deadline and stops the provider', async () => {
	let stopped = 0;
	const result = await runLatencyMatrix({
		matrix: matrix({ trials: [{ ...matrix().trials[0], id: 'create-agent-timeout', trialBudgetMs: 300 }] }),
		scenarioResolver: () => fixtureScenario(),
		providerFactories: {
			instant: () => ({
				available: true,
				provider: 'instant',
				synthetic: true,
				async createAgent() { return new Promise(() => {}); },
				async stop() { stopped += 1; },
			}),
		},
		artifactDirectory: null,
	});
	assert.equal(result.trials[0].status, 'TIMED_OUT');
	assert.equal(result.trials[0].error.code, 'TRIAL_TIMEOUT');
	assert.equal(stopped, 1);
	assert.equal(result.cleanup.ok, true);
});

test('provider stop after a timeout is bounded by cleanup policy', async () => {
	const startedAt = performance.now();
	const result = await runLatencyMatrix({
		matrix: matrix({ trials: [{ ...matrix().trials[0], id: 'bounded-stop-timeout', trialBudgetMs: 100 }] }),
		scenarioResolver: () => fixtureScenario(),
		providerFactories: {
			instant: () => ({
				available: true,
				provider: 'instant',
				synthetic: true,
				async createAgent() { return new Promise(() => {}); },
				async stop() { await new Promise((resolve) => setTimeout(resolve, 250)); },
			}),
		},
		artifactDirectory: null,
	});
	assert.equal(result.trials[0].status, 'TIMED_OUT');
	assert.ok(performance.now() - startedAt < 220, 'timeout cleanup must not wait for an unbounded provider stop');
});

test('malformed planner/runtime decisions remain typed failures instead of timeout results', async () => {
	const result = await runLatencyMatrix({
		matrix: matrix({ trials: [{ ...matrix().trials[0], id: 'malformed-decision' }] }),
		scenarioResolver: () => fixtureScenario(),
		providerFactories: {
			instant: () => ({
				available: true,
				provider: 'instant',
				synthetic: true,
				async createAgent() {
					return { async setGoalRevision() {}, async decide() { throw Object.assign(new Error('malformed fixture decision'), { code: 'INVALID_DECISION' }); } };
				},
			}),
		},
		artifactDirectory: null,
	});
	assert.equal(result.trials[0].status, 'FAILED');
	assert.equal(result.trials[0].error.code, 'INVALID_DECISION');
});

test('provider process exit is typed and leaves the coordinator path clean', async () => {
	let stopped = 0;
	const providerFactory = () => ({
		available: true,
		async stop() { stopped += 1; },
		async createAgent() { throw Object.assign(new Error('provider child exited'), { code: 'PROVIDER_EXIT' }); },
	});
	const result = await runLatencyMatrix({
		matrix: matrix({ trials: [{ ...matrix().trials[0], id: 'provider-exit', providerAvailabilityRequired: true }] }),
		scenarioResolver: () => fixtureScenario(), providerFactories: { instant: providerFactory }, artifactDirectory: null,
	});
	assert.equal(result.trials[0].status, 'FAILED');
	assert.equal(result.trials[0].error.code, 'PROVIDER_EXIT');
	assert.equal(result.trials[0].cleanup.ok, true);
	assert.equal(stopped, 1);
});

test('startup failure detaches virtual relays and stops a provider exactly once', async () => {
	let stopped = 0;
	const result = await runLatencyMatrix({
		matrix: matrix({ trials: [{ ...matrix().trials[0], id: 'startup-failure' }] }),
		scenarioResolver: () => fixtureScenario(),
		providerFactories: { instant: () => ({ available: true, async stop() { stopped += 1; }, async createAgent() { return { async setGoalRevision() {}, async decide() { return { summary: 'done', directive: 'replace', source: SOURCE }; } }; } }) },
		systemSamplerFactory: () => ({ active: false, start() { throw Object.assign(new Error('sampler startup failed'), { code: 'SAMPLER_START_FAILED' }); }, stop() {} }),
		artifactDirectory: null,
	});
	assert.equal(result.trials[0].status, 'FAILED');
	assert.equal(result.trials[0].error.code, 'SAMPLER_START_FAILED');
	assert.equal(result.trials[0].cleanup.ok, true);
	assert.equal(result.trials[0].cleanup.relays, 0);
	assert.equal(result.trials[0].cleanup.listeners, 0);
	assert.equal(stopped, 1);
});

test('artifact output is staged, bounded, and redacted on provider failure', async () => {
	const directory = await mkdtemp(path.join(os.tmpdir(), 'latency-artifacts-'));
	try {
		const secret = 'fixture-secret-token-123456';
		const privatePath = 'C:\\private\\benchmark.json';
		const result = await runLatencyMatrix({
			matrix: matrix({ trials: [{ ...matrix().trials[0], id: 'redacted', providerAvailabilityRequired: true }] }),
			scenarioResolver: () => fixtureScenario(),
			providerFactories: { instant: () => ({ available: true, async start() { throw new Error(`authorization token=${secret} at ${privatePath}`); }, async stop() {} }) },
			artifactDirectory: directory,
		});
		assert.equal(result.trials[0].status, 'FAILED');
		const manifest = await readFile(path.join(directory, 'latency-manifest.json'), 'utf8');
		assert.equal(manifest.includes(secret), false);
		assert.equal(manifest.includes(privatePath), false);
		assert.ok(manifest.length < 100_000);
	} finally {
		await rm(directory, { recursive: true, force: true });
	}
});

test('artifact rollback preserves the only prior copy when restoration fails', async () => {
	const root = await mkdtemp(path.join(os.tmpdir(), 'latency-rollback-'));
	const directory = path.join(root, 'artifacts');
	await mkdir(directory, { recursive: true });
	await writeFile(path.join(directory, 'old.txt'), 'prior artifact');
	let backupPath = null;
	let renameCount = 0;
	const artifactFs = {
		mkdir,
		writeFile,
		rm,
		async rename(source, target) {
			renameCount += 1;
			if (renameCount === 1) { backupPath = target; return rename(source, target); }
			if (renameCount === 2) throw Object.assign(new Error('publish swap failed'), { code: 'SWAP_FAILED' });
			throw Object.assign(new Error('restore failed'), { code: 'RESTORE_FAILED' });
		},
	};
	try {
		await assert.rejects(() => runLatencyMatrix({ matrix: matrix(), scenarioResolver: () => fixtureScenario(), artifactDirectory: directory, artifactFs }), (error) => error.code === 'ARTIFACT_ROLLBACK_FAILED');
		assert.ok(backupPath);
		assert.equal(await readFile(path.join(backupPath, 'old.txt'), 'utf8'), 'prior artifact');
	} finally {
		await rm(root, { recursive: true, force: true });
	}
});

test('replay mode uses the same full coordinator path and rejects prompt drift', async () => {
	const profile = { provider: 'codex', model: 'fixture-model', reasoningEffort: 'high', serviceTier: 'fast' };
	const source = 'program.onUnhandledAttention("continue_and_notify"); await player.wait(1); program.finish("done");';
	let prompt = null;
	const scenario = fixtureScenario();
	const liveMatrix = matrix({ trials: [{ ...matrix().trials[0], id: 'replay-path', mode: 'live', providerProfile: profile }] });
	const providerFactory = () => ({ available: true, synthetic: true, provider: 'codex', model: profile.model, reasoningEffort: profile.reasoningEffort, serviceTier: profile.serviceTier, providerProfile: profile, async createAgent() { return { async setGoalRevision() {}, async decide(input) { prompt = input; return fixtureDecision(); } }; }, async stop() {} });
	const first = await runLatencyMatrix({ matrix: liveMatrix, scenarioResolver: () => scenario, providerFactories: { codex: providerFactory }, artifactDirectory: null });
	assert.equal(first.trials[0].status, 'PASSED');
	assert.match(prompt, /"worldId":"fixture-[a-f0-9]{28}"/);
	const recording = createReplayRecord({ trialId: 'replay-path', prompt, providerProfile: profile, scenario, protocolVersion: 2, decision: fixtureDecision() });
	const replayMatrix = matrix({ trials: [{ ...liveMatrix.trials[0], mode: 'replay' }] });
	const replay = await runLatencyMatrix({
		matrix: replayMatrix, scenarioResolver: () => scenario,
		providerFactories: { codex: () => createReplayProvider({ recording, trialId: 'replay-path', prompt, providerProfile: profile, scenario, protocolVersion: 2 }) }, artifactDirectory: null,
	});
	assert.equal(replay.trials[0].status, 'PASSED');
	assert.deepEqual(replay.trials[0].providerIdentity, { provider: 'codex', synthetic: true });
	const drift = await runLatencyMatrix({
		matrix: replayMatrix, scenarioResolver: () => scenario,
		providerFactories: { codex: () => createReplayProvider({ recording, trialId: 'replay-path', prompt: `${prompt}-drift`, providerProfile: profile, scenario, protocolVersion: 2 }) }, artifactDirectory: null,
	});
	assert.equal(drift.trials[0].error.code, 'REPLAY_IDENTITY_MISMATCH');
});

test('only synthetic benchmark sessions reuse fixture identity and preserve an explicit world ID', async () => {
	const profile = { provider: 'codex', model: 'fixture-model', reasoningEffort: 'high', serviceTier: 'fast' };
	const scenario = fixtureScenario();
	scenario.world.worldId = 'fixture-world-session-isolation';
	const trial = { ...matrix().trials[0], mode: 'live', providerProfile: profile };
	const run = async (synthetic) => {
		let virtual;
		const result = await runLatencyMatrix({
			matrix: matrix({ trials: [trial] }),
			scenarioResolver: () => scenario,
			providerFactories: { codex: () => ({ ...profile, synthetic, available: true, async createAgent() { return { async setGoalRevision() {}, async decide() { return fixtureDecision(); } }; } }) },
			virtualBridgeFactory: (options) => { virtual = new VirtualMinecraftBridge(options); return virtual; },
			artifactDirectory: null,
		});
		assert.equal(result.status, 'PASSED');
		assert.equal(virtual.world.observation(scenario.agentId).world.worldId, scenario.world.worldId);
		return virtual.sent.find((message) => message.type === 'action_command').payload;
	};
	const fixtureA = await run(true);
	const fixtureB = await run(true);
	assert.deepEqual(fixtureA, fixtureB);
	const liveA = await run(false);
	const liveB = await run(false);
	assert.notEqual(liveA.actionId, liveB.actionId);
	assert.deepEqual(liveA.provenance, liveB.provenance);
});

test('Codex benchmark sessions receive the explicit ArenaScript protocol', async () => {
	const profile = { provider: 'codex', model: 'fixture-model', reasoningEffort: 'high', serviceTier: 'fast' };
	const optionsSeen = [];
	const scenario = fixtureScenario();
	const trial = { ...matrix().trials[0], id: 'codex-arena-script', mode: 'live', providerProfile: profile, scenarioId: scenario.id };
	const providerFactory = () => ({
		available: true,
		provider: 'codex',
		model: profile.model,
		reasoningEffort: profile.reasoningEffort,
		serviceTier: profile.serviceTier,
		providerProfile: profile,
		async createAgent(_record, options) {
			optionsSeen.push(options);
			return { async setGoalRevision() {}, async decide() { return fixtureDecision({ scenario }); } };
		},
		async stop() {},
	});

	const result = await runLatencyMatrix({
		matrix: matrix({ trials: [trial] }),
		scenarioResolver: () => scenario,
		providerFactories: { codex: providerFactory },
		artifactDirectory: null,
	});

	assert.equal(result.trials[0].status, 'PASSED');
	assert.equal(optionsSeen[0].controlProtocol, 'arena_script');
});

test('paces delayed replay ticks against wall time instead of racing virtual time ahead', async () => {
	const profile = { provider: 'codex', model: 'fixture-model', reasoningEffort: 'high', serviceTier: 'fast' };
	const scenario = fixtureScenario();
	const trial = { ...matrix().trials[0], id: 'paced-replay', mode: 'replay', providerProfile: profile, trialBudgetMs: 1_000, turnBudgetMs: 500 };
	const decision = fixtureDecision();
	let prompt = null;
	const liveProvider = () => ({
		available: true,
		synthetic: true,
		provider: profile.provider,
		model: profile.model,
		reasoningEffort: profile.reasoningEffort,
		serviceTier: profile.serviceTier,
		providerProfile: profile,
		async createAgent() { return { async setGoalRevision() {}, async decide(input) { prompt = input; return decision; } }; },
		async stop() {},
	});
	const live = await runLatencyMatrix({
		matrix: matrix({ trials: [{ ...trial, mode: 'live' }] }),
		scenarioResolver: () => scenario,
		providerFactories: { codex: liveProvider },
		artifactDirectory: null,
	});
	assert.equal(live.trials[0].status, 'PASSED');
	const recording = createReplayRecord({ trialId: trial.id, prompt, providerProfile: profile, scenario, protocolVersion: 2, decision, delayMs: 75 });
	const pacingHandles = new Set();
	const pacingTimer = {
		setTimeout(callback, delay) {
			let handle;
			handle = setTimeout(() => { pacingHandles.delete(handle); callback(); }, delay);
			pacingHandles.add(handle);
			return handle;
		},
		clearTimeout(handle) { pacingHandles.delete(handle); clearTimeout(handle); },
	};
	const startedAt = performance.now();
	const result = await runLatencyMatrix({
		matrix: matrix({ trials: [trial] }),
		scenarioResolver: () => scenario,
		providerFactories: { codex: () => createReplayProvider({ recording, trialId: trial.id, prompt, providerProfile: profile, scenario, protocolVersion: 2 }) },
		virtualTickPacing: { timer: pacingTimer },
		artifactDirectory: null,
	});
	const elapsedWallMs = performance.now() - startedAt;
	const completed = result.trials[0];
	assert.equal(completed.status, 'PASSED');
	assert.ok(completed.metrics.result.taskCompletionWallDurationMs >= VIRTUAL_TICK_MS);
	assert.ok(completed.metrics.result.totalVirtualWorldDurationMs < 500);
	assert.ok(completed.metrics.result.totalVirtualWorldDurationMs <= completed.metrics.result.taskCompletionWallDurationMs + VIRTUAL_TICK_MS * 2);
	assert.ok(elapsedWallMs < 900);
	assert.equal(pacingHandles.size, 0);
});
