import assert from 'node:assert/strict';
import test from 'node:test';

import { AgentPlanner } from '../src/agent-planner.mjs';
import { DynamicAgentState } from '../src/agent-registry.mjs';
import { ControlLatencyRegistry } from '../src/control-latency-registry.mjs';
import { PlanningScheduler } from '../src/planning-scheduler.mjs';
import { profileFingerprint } from '../src/provider-session.mjs';
import { createExecutionSettings } from '../src/provider-identity.mjs';

const AGENT_ID = 'agent-1';
const GOAL_REVISION = 7;
const RECORD = Object.freeze({
	agentId: AGENT_ID,
	provider: 'kimi',
	model: 'kimi-code/k3',
	reasoningEffort: 'high',
	serviceTier: 'fast',
	goalRevision: GOAL_REVISION,
});
const VALID_DECISION = Object.freeze({
	summary: 'Wait safely',
	directive: 'replace',
	source: 'program.onUnhandledAttention("continue_and_notify"); await player.wait(100);',
});

test('native planner renews healthy tool work without letting provider events shorten its body budget', async () => {
	let now = 0;
	const timers = [];
	const scheduler = new PlanningScheduler({
		maxConcurrent: 1, maxPending: 0, now: () => now,
		scheduleTimeout(callback, delay) { const timer = { callback, delay }; timers.push(timer); return timer; }, cancelTimeout() {},
	});
	const record = { ...RECORD, provider: 'codex', model: 'gpt-5.6-sol' };
	let options;
	let finishTurn;
	let finishBody;
	const body = new Promise((resolve) => { finishBody = resolve; });
	const reportedProgress = [];
	const agent = {
		async setGoalRevision() {},
		act(_input, value) { options = value; return new Promise((resolve) => { finishTurn = resolve; }); },
	};
	const planner = new AgentPlanner({
		registry: { assertCurrentRevision: () => record, setState() {} }, scheduler,
		codexService: { async createAgent() { return agent; }, getAgent() { return agent; } },
	});
	const run = planner.requestNativeTurn({ agentId: AGENT_ID, input: 'perform selected action', goalRevision: GOAL_REVISION, executeTool: () => body, onProgress: (event) => reportedProgress.push(event) });
	await new Promise((resolve) => setImmediate(resolve));
	assert.deepEqual(reportedProgress, [], 'scheduler admission does not invent provider progress');
	now = 120_000;
	options.onProgress({ phase: 'provider' });
	const beforeBody = timers.at(-1);
	const executing = options.executeTool({ tool: { kind: 'action', actionType: 'wait', arguments: { durationMs: 200_000 } } });
	const bodyTimer = timers.at(-1);
	assert.equal(bodyTimer.delay, 205_000);
	options.onProgress({ phase: 'provider' });
	assert.equal(timers.at(-1), bodyTimer);
	assert.deepEqual(reportedProgress, [{ phase: 'provider' }, { phase: 'provider' }]);
	now = 250_000;
	beforeBody.callback();
	assert.equal(options.signal.aborted, false, 'the healthy body survives the old planning deadline');
	finishBody({ state: 'SUCCEEDED' });
	await executing;
	assert.equal(timers.at(-1).delay, 125_000);
	finishTurn({ status: 'completed', toolCalls: 1 });
	assert.equal((await run).status, 'completed');
	options.onProgress({ phase: 'provider' });
	assert.equal(reportedProgress.length, 2, 'settled turns cannot renew outer supervision');
	scheduler.close();
});

test('provider profile fingerprints include the exact agent identity', () => {
	assert.notEqual(
		profileFingerprint(RECORD),
		profileFingerprint({ ...RECORD, agentId: 'agent-b' }),
	);
});

test('goal spec translation uses an isolated structured provider session without changing lifecycle state', async () => {
	const registry = new FakeRegistry();
	const calls = [];
	const turnRecorder = { record() {} };
	const service = {
		async createAgent(profile, options) {
			calls.push({ type: 'create', profile, options });
			return {
				async setGoalRevision(revision) { calls.push({ type: 'revision', revision }); },
				async decide(input, decisionOptions) {
					calls.push({ type: 'decide', input, decisionOptions });
					return {
						requestId: '00000000-0000-4000-8000-000000000001', summary: 'Obtain an iron pickaxe',
						predicate: { type: 'inventory_contains', itemId: 'minecraft:iron_pickaxe', count: 1 },
					};
				},
			};
		},
		getAgent() { return null; },
		async removeAgent(agentId) { calls.push({ type: 'remove', agentId }); return true; },
	};
	const planner = createPlannerForService(registry, service, 1, { turnRecorder });
	const proposal = await planner.requestGoalSpec({
		agentId: AGENT_ID,
		request: {
			requestId: '00000000-0000-4000-8000-000000000001', originalRequest: 'Get a good pickaxe',
			candidateIds: ['minecraft:iron_pickaxe', 'minecraft:diamond_pickaxe'],
		},
	});
	assert.equal(proposal.requestId, '00000000-0000-4000-8000-000000000001');
	assert.equal(calls.find(call => call.type === 'create').options.controlProtocol, 'goal_spec');
	assert.notEqual(calls.find(call => call.type === 'create').profile.agentId, AGENT_ID);
	assert.equal(typeof calls.find(call => call.type === 'decide').decisionOptions.parseOutput, 'function');
	assert.equal(calls.find(call => call.type === 'decide').decisionOptions.turnRecorder, turnRecorder);
	assert.equal(calls.find(call => call.type === 'decide').decisionOptions.attempt, 1);
	assert.equal(calls.find(call => call.type === 'decide').decisionOptions.retry, false);
	assert.equal(calls.at(-1).type, 'remove');
	assert.deepEqual(registry.states, []);
});

test('ArenaScript planning explicitly creates an ArenaScript provider session', async () => {
	const registry = new FakeRegistry();
	const optionsSeen = [];
	const planner = createPlannerForService(registry, {
		async createAgent(_record, options) {
			optionsSeen.push(options);
			return { async setGoalRevision() {}, async decide() { return VALID_DECISION; } };
		},
		getAgent() { return null; },
		async removeAgent() { return false; },
	});
	await planner.requestPlan({ agentId: AGENT_ID, input: 'authoritative state', goalRevision: GOAL_REVISION });
	assert.deepEqual(optionsSeen, [{ recoverySummary: null, controlProtocol: 'arena_script' }]);
});

test('retries one malformed planner decision with bounded corrective feedback', async () => {
	const registry = new FakeRegistry();
	const inputs = [];
	const invalid = Object.assign(new Error('planner output was not JSON'), { code: 'MALFORMED_DECISION' });
	const agent = {
		async setGoalRevision(revision) { assert.equal(revision, GOAL_REVISION); },
		async decide(input) {
			inputs.push(input);
			if (inputs.length === 1) throw invalid;
			return VALID_DECISION;
		},
	};
	const planner = createPlanner(registry, agent, 1);

	const result = await planner.requestPlan({
		agentId: AGENT_ID,
		input: 'authoritative state',
		goalRevision: GOAL_REVISION,
	});

	assert.deepEqual(result, { ...VALID_DECISION, goalRevision: GOAL_REVISION });
	assert.equal(inputs.length, 2);
	assert.equal(inputs[0], 'authoritative state');
	assert.match(inputs[1], /corrective retry 1/);
	assert.match(inputs[1], /MALFORMED_DECISION/);
	assert.equal(registry.states.at(-1).state, DynamicAgentState.PLANNING);
});

test('streams safe planner, provider, output, retry, and decision events without letting callback failures affect planning', async () => {
	const registry = new FakeRegistry();
	const events = [];
	let calls = 0;
	const invalid = Object.assign(new Error('planner output was not JSON'), { code: 'MALFORMED_DECISION' });
	const agent = {
		async setGoalRevision() {},
		async decide(_input, options) {
			options.onVerbose('output', 'Visible provider response');
			calls += 1;
			if (calls === 1) throw invalid;
			return VALID_DECISION;
		},
	};
	const planner = createPlanner(registry, agent, 1);

	const result = await planner.requestPlan({
		agentId: AGENT_ID,
		input: 'authoritative state',
		goalRevision: GOAL_REVISION,
		onVerbose(stage, message) {
			events.push({ stage, message });
			throw new Error('verbose consumer unavailable');
		},
	});

	assert.deepEqual(result, { ...VALID_DECISION, goalRevision: GOAL_REVISION });
	assert.deepEqual(new Set(events.map(({ stage }) => stage)), new Set(['planner', 'provider', 'output', 'retry', 'decision']));
	assert.equal(events.every(({ message }) => message.length <= 256), true);

	agent.decide = async () => { throw Object.assign(new Error('authorization: Bearer provider-secret; stderr contained a private response body'), { code: 'FATAL_PROVIDER_ERROR' }); };
	const failureEvents = [];
	await assert.rejects(planner.requestPlan({
		agentId: AGENT_ID,
		input: 'authoritative state',
		goalRevision: GOAL_REVISION,
		onVerbose(stage, message) { failureEvents.push({ stage, message }); },
	}), (error) => error?.code === 'FATAL_PROVIDER_ERROR');
	assert.equal(failureEvents.some(({ stage }) => stage === 'error'), true, 'terminal planning errors are observable');
	const errorMessages = failureEvents.filter(({ stage }) => stage === 'error').map(({ message }) => message).join(' ');
	assert.match(errorMessages, /FATAL_PROVIDER_ERROR/);
	assert.doesNotMatch(errorMessages, /provider-secret|private response body|authorization|stderr/i);
});

test('keeps one trace across a malformed decision retry and records queue/provider/parse boundaries once', async () => {
	const registry = new FakeRegistry();
	const rows = [];
	const times = [100, 104, 110, 118, 121, 130, 136, 140, 141];
	let attempt = 0;
	const invalid = Object.assign(new Error('invalid output'), { code: 'MALFORMED_DECISION' });
	const agent = {
		async setGoalRevision() {},
		async decide() {
			attempt += 1;
			if (attempt === 1) throw invalid;
			return VALID_DECISION;
		},
	};
	const planner = new AgentPlanner({
		registry,
		invalidDecisionRetries: 1,
		now: () => times.shift(),
		benchmarkRecorder: { record(stage, context, fields) { rows.push({ stage, context, fields }); } },
		scheduler: {
			schedule(_agentId, operation) { return operation({ signal: new AbortController().signal }); },
			cancel() { return false; },
		},
		codexService: {
			async createAgent() { return agent; },
			getAgent() { return null; },
			async removeAgent() { return false; },
		},
	});

	const result = await planner.requestPlan({
		agentId: AGENT_ID,
		input: 'authoritative state',
		goalRevision: GOAL_REVISION,
		traceId: 'trace-planner-retry',
	});

	assert.equal(result.traceId, 'trace-planner-retry');
	assert.equal(new Set(rows.map((row) => row.context.traceId)).size, 1);
	assert.equal(rows.every((row) => row.context.traceId === 'trace-planner-retry'), true);
	assert.equal(rows.filter((row) => row.stage === 'queue_wait').length, 1);
	const phases = new Set(['queue_wait', 'provider_first_byte', 'provider_final_byte', 'parse']);
	assert.deepEqual(rows.filter((row) => phases.has(row.stage)).map((row) => row.stage), [
		'queue_wait', 'provider_first_byte', 'provider_final_byte', 'parse',
	]);
	assert.equal(rows.find((row) => row.stage === 'parse').fields.retryReason, 'MALFORMED_DECISION');
});

test('clock regression never emits a regressing raw phase and poisons the trace', async () => {
	const registry = new FakeRegistry();
	const latencyRegistry = new ControlLatencyRegistry();
	const rows = [];
	const times = [100, 90, 91, 92, 93, 94];
	const planner = new AgentPlanner({
		registry,
		latencyRegistry,
		now: () => times.shift(),
		benchmarkRecorder: { record(stage, context, fields) { rows.push({ stage, context, fields }); } },
		scheduler: {
			schedule(_agentId, operation) { return operation({ signal: new AbortController().signal }); },
			cancel() { return false; },
		},
		codexService: {
			async createAgent() { return { async setGoalRevision() {}, async decide() { return VALID_DECISION; } }; },
			getAgent() { return null; },
			async removeAgent() { return false; },
		},
	});

	await planner.requestPlan({ agentId: AGENT_ID, input: 'authoritative state', goalRevision: GOAL_REVISION, traceId: 'trace-clock-regression' });
	assert.equal(rows.some((row) => row.stage === 'queue_wait' && row.fields.startMonotonicMs > row.fields.endMonotonicMs), false);
	const summary = latencyRegistry.completeTrace('trace-clock-regression');
	assert.equal(summary.complete, false);
	assert.equal(summary.totalMs, null);
});

test('retries compact envelope validation mismatches with corrective feedback', async () => {
	for (const invalid of [
		{ code: 'DECISION_FIELD_MISMATCH', message: 'replace directive requires nonblank source' },
		{ code: 'DECISION_FIELD_MISMATCH', message: 'finish directive requires status completed or impossible' },
	]) {
		const registry = new FakeRegistry();
		const inputs = [];
		const error = Object.assign(new Error(invalid.message), { code: invalid.code });
		const agent = {
			async setGoalRevision(revision) { assert.equal(revision, GOAL_REVISION); },
			async decide(input) {
				inputs.push(input);
				if (inputs.length === 1) throw error;
				return VALID_DECISION;
			},
		};
		const planner = createPlanner(registry, agent, 1);

		const result = await planner.requestPlan({
			agentId: AGENT_ID,
			input: 'authoritative state',
			goalRevision: GOAL_REVISION,
		});

		assert.deepEqual(result, { ...VALID_DECISION, goalRevision: GOAL_REVISION });
		assert.equal(inputs.length, 2);
		assert.match(inputs[1], new RegExp(`corrective retry 1[\\s\\S]*${invalid.code}`));
		assert.equal(registry.states.at(-1).state, DynamicAgentState.PLANNING);
	}
});

test('retries a duplicate decision envelope through the same provider agent', async () => {
	const registry = new FakeRegistry();
	const inputs = [];
	let creates = 0;
	const duplicate = Object.assign(new Error("Duplicate decision field 'source'"), { code: 'DUPLICATE_DECISION_FIELD' });
	const agent = {
		async setGoalRevision(revision) { assert.equal(revision, GOAL_REVISION); },
		async decide(input) {
			inputs.push(input);
			if (inputs.length === 1) throw duplicate;
			return VALID_DECISION;
		},
	};
	const planner = createPlannerForService(registry, {
		async createAgent() { creates += 1; return agent; },
		getAgent() { return null; }, async removeAgent() { return false; },
	}, 1);
	const result = await planner.requestPlan({
		agentId: AGENT_ID, input: 'authoritative state', goalRevision: GOAL_REVISION,
	});
	assert.deepEqual(result, { ...VALID_DECISION, goalRevision: GOAL_REVISION });
	assert.equal(creates, 1, 'corrective retry must retain the selected provider agent session');
	assert.equal(inputs.length, 2);
	assert.match(inputs[1], /DUPLICATE_DECISION_FIELD/);
});

test('returns a legacy action-array rejection to the same selected model as correction feedback', async () => {
	const registry = new FakeRegistry();
	const inputs = [];
	let creates = 0;
	const legacy = Object.assign(new Error('Legacy action-array decisions are not supported'), { code: 'INVALID_DECISION' });
	const agent = {
		async setGoalRevision(revision) { assert.equal(revision, GOAL_REVISION); },
		async decide(input) {
			inputs.push(input);
			if (inputs.length === 1) throw legacy;
			return VALID_DECISION;
		},
	};
	const planner = createPlannerForService(registry, {
		async createAgent() { creates += 1; return agent; },
		getAgent() { return null; }, async removeAgent() { return false; },
	}, 1);

	const result = await planner.requestPlan({
		agentId: AGENT_ID, input: '{"summary":"old","actions":[]}', goalRevision: GOAL_REVISION,
	});

	assert.deepEqual(result, { ...VALID_DECISION, goalRevision: GOAL_REVISION });
	assert.equal(creates, 1);
	assert.equal(inputs.length, 2);
	assert.match(inputs[1], /INVALID_DECISION/);
});

test('retries one transient provider failure without changing authoritative input', async () => {
	const registry = new FakeRegistry();
	const inputs = [];
	const transient = Object.assign(new Error('provider timed out'), { code: 'PLANNING_TIMEOUT' });
	const agent = {
		async setGoalRevision(revision) { assert.equal(revision, GOAL_REVISION); },
		async decide(input) {
			inputs.push(input);
			if (inputs.length === 1) throw transient;
			return VALID_DECISION;
		},
	};
	const planner = createPlanner(registry, agent, 1);

	const result = await planner.requestPlan({
		agentId: AGENT_ID,
		input: 'authoritative state',
		goalRevision: GOAL_REVISION,
	});

	assert.deepEqual(result, { ...VALID_DECISION, goalRevision: GOAL_REVISION });
	assert.deepEqual(inputs, ['authoritative state', 'authoritative state']);
	assert.equal(registry.states.at(-1).state, DynamicAgentState.PLANNING);
});

test('routes planning through the provider lane and preserves priority and provider identity across retries', async () => {
	const registry = new FakeRegistry();
	const scheduleCalls = [];
	const createdRecords = [];
	const transient = Object.assign(new Error('provider timed out'), { code: 'PLANNING_TIMEOUT' });
	let replacements = 0;
	const replacementAgent = {
		sessionGeneration: 2,
		async setGoalRevision() {},
		async decide() { return VALID_DECISION; },
	};
	const staleAgent = {
		sessionGeneration: 1,
		async setGoalRevision() {},
		async decide() { throw transient; },
	};
	const planner = new AgentPlanner({
		registry,
		scheduler: {
			schedule(agentId, operation, options) {
				scheduleCalls.push({ agentId, options });
				return operation({ signal: new AbortController().signal });
			},
			cancel() { return false; },
		},
		codexService: {
			async createAgent(record) {
				createdRecords.push(record);
				return staleAgent;
			},
			async replaceAgent(record, options) {
				replacements += 1;
				assert.deepEqual(record, RECORD);
				assert.equal(options.expectedSessionGeneration, 1);
				return replacementAgent;
			},
			getAgent() { return null; },
			async removeAgent() { return false; },
		},
	});

	const result = await planner.requestPlan({
		agentId: AGENT_ID,
		input: 'authoritative state',
		goalRevision: GOAL_REVISION,
		priority: 'urgent',
	});

	assert.deepEqual(result, { ...VALID_DECISION, goalRevision: GOAL_REVISION });
	assert.equal(scheduleCalls.length, 1);
	assert.equal(scheduleCalls[0].agentId, AGENT_ID);
	assert.equal(scheduleCalls[0].options.lane, RECORD.provider);
	assert.equal(scheduleCalls[0].options.priority, 'urgent');
	assert.equal(scheduleCalls[0].options.leaseTimeoutMs, 125_000);
	assert.equal(typeof scheduleCalls[0].options.onLeaseExpired, 'function');
	assert.equal(createdRecords.length, 1);
	assert.deepEqual(createdRecords[0], RECORD, 'urgent provider retry retains the exact selected profile');
	assert.equal(replacements, 1, 'provider retry replaces the failed exact session once');
});

test('provider recovery fails closed instead of reusing a mismatched Sol-to-Terra session', async () => {
	const registry = new FakeRegistry();
	const timeout = Object.assign(new Error('Sol transport timed out'), { code: 'PLANNING_TIMEOUT' });
	const selectedFingerprint = profileFingerprint(RECORD);
	const terraFingerprint = profileFingerprint({ ...RECORD, model: 'terra' });
	const staleSol = {
		sessionGeneration: 1,
		sessionMetadata: () => ({ profileFingerprint: selectedFingerprint, sessionGeneration: 1 }),
		async setGoalRevision() {},
		async decide() { throw timeout; },
	};
	let mismatchedTurns = 0;
	const currentTerra = {
		sessionGeneration: 2,
		sessionMetadata: () => ({ profileFingerprint: terraFingerprint, sessionGeneration: 2 }),
		async setGoalRevision() {},
		async decide() { mismatchedTurns += 1; return VALID_DECISION; },
	};
	let replacements = 0;
	const planner = createPlannerForService(registry, {
		async createAgent() { return staleSol; },
		getAgent() { return currentTerra; },
		async replaceAgent() { replacements += 1; return currentTerra; },
		async removeAgent() { return false; },
	}, 1);
	await assert.rejects(
		planner.requestPlan({ agentId: AGENT_ID, input: 'authoritative state', goalRevision: GOAL_REVISION }),
		(error) => error?.code === 'SESSION_PROFILE_MISMATCH',
	);
	assert.equal(mismatchedTurns, 0, 'Terra must never execute work captured for Sol');
	assert.equal(replacements, 0, 'a mismatched current owner is not replaced by a stale callback');
});

test('retries one empty Codex turn without changing authoritative input', async () => {
	const registry = new FakeRegistry();
	const inputs = [];
	const emptyTurn = Object.assign(new Error('Codex turn completed without an agent message'), {
		code: 'MISSING_AGENT_MESSAGE',
	});
	const agent = {
		async setGoalRevision(revision) { assert.equal(revision, GOAL_REVISION); },
		async decide(input) {
			inputs.push(input);
			if (inputs.length === 1) throw emptyTurn;
			return VALID_DECISION;
		},
	};
	const planner = createPlanner(registry, agent, 1);

	const result = await planner.requestPlan({
		agentId: AGENT_ID,
		input: 'authoritative state',
		goalRevision: GOAL_REVISION,
	});

	assert.deepEqual(result, { ...VALID_DECISION, goalRevision: GOAL_REVISION });
	assert.deepEqual(inputs, ['authoritative state', 'authoritative state']);
});

test('exhausted empty Codex turns remain planning for quiet observation retry', async () => {
	const registry = new FakeRegistry();
	const emptyTurn = Object.assign(new Error('Codex turn completed without an agent message'), {
		code: 'MISSING_AGENT_MESSAGE',
	});
	const agent = {
		async setGoalRevision() {},
		async decide() { throw emptyTurn; },
	};
	const planner = createPlanner(registry, agent, 1);
	await assert.rejects(planner.requestPlan({
		agentId: AGENT_ID,
		input: 'authoritative state',
		goalRevision: GOAL_REVISION,
	}), (error) => error === emptyTurn);
	assert.equal(registry.states.at(-1).state, DynamicAgentState.PLANNING);
});

test('exhausted recovery-policy failures remain active for observation-driven recovery', async () => {
	for (const code of [
		'PROVIDER_TIMEOUT', 'REQUEST_TIMEOUT', 'PLANNING_TIMEOUT', 'PROCESS_TERMINATION_FAILED',
		'PROVIDER_UNAVAILABLE', 'PROVIDER_STOPPED', 'SESSION_INVALIDATED', 'TRANSPORT_STOPPED',
		'PROVIDER_CIRCUIT_OPEN', 'SCHEDULER_CAPACITY', 'AUTHENTICATION_REQUIRED', 'MISSING_CREDENTIALS',
		'AGENT_NOT_STARTED', 'AGENT_PROFILE_CONFLICT', 'INCOMPLETE_TURN', 'INVALID_CATALOG',
		'INVALID_PROVIDER_OUTPUT', 'MODEL_PROFILE_UNAVAILABLE', 'PROCESS_EXITED', 'PROVIDER_DOWN',
		'PROVIDER_OVERLOADED', 'REQUEST_ID_EXHAUSTED', 'SESSION_GENERATION_MISMATCH',
		'SESSION_PROFILE_MISMATCH', 'STALE_PROVIDER_START', 'STALE_RECONCILIATION',
		'STALE_SESSION_GENERATION', 'TURN_IN_PROGRESS', 'TURN_INTERRUPTED', 'TURN_NOT_ACTIVE',
		'UNKNOWN_RESPONSE_ID', 'CONTROL_PROTOCOL_MISMATCH', 'INVALID_CONFIG_OPTIONS', 'MODEL_UNAVAILABLE',
		'NATIVE_TOOLS_UNAVAILABLE', 'PROVIDER_MISMATCH', 'REASONING_EFFORT_UNAVAILABLE',
		'SERVICE_TIER_UNAVAILABLE', 'UNSUPPORTED_MODEL', 'UNSUPPORTED_SERVICE_TIER', 'UNSUPPORTED_THINKING',
	]) {
		const registry = new FakeRegistry();
		const failure = Object.assign(new Error(`provider failure ${code}`), { code });
		const agent = {
			async setGoalRevision() {},
			async decide() { throw failure; },
		};
		const planner = createPlanner(registry, agent, 1);
		await assert.rejects(planner.requestPlan({
			agentId: AGENT_ID, input: 'authoritative state', goalRevision: GOAL_REVISION,
		}), (error) => error === failure, code);
		assert.equal(registry.states.at(-1).state, DynamicAgentState.PLANNING, code);
		assert.equal(registry.states.some(({ state }) => state === DynamicAgentState.ERROR), false, code);
	}
});

test('retries one transient provider initialization failure', async () => {
	const registry = new FakeRegistry();
	let createAttempts = 0;
	const transient = Object.assign(new Error('CLI did not start'), { code: 'SPAWN_FAILED' });
	const agent = {
		async setGoalRevision() {},
		async decide() { return VALID_DECISION; },
	};
	const planner = createPlannerForService(registry, {
		async createAgent() {
			createAttempts += 1;
			if (createAttempts === 1) throw transient;
			return agent;
		},
		getAgent() { return null; },
		async removeAgent() { return false; },
	});

	const result = await planner.requestPlan({
		agentId: AGENT_ID,
		input: 'authoritative state',
		goalRevision: GOAL_REVISION,
	});

	assert.deepEqual(result, { ...VALID_DECISION, goalRevision: GOAL_REVISION });
	assert.equal(createAttempts, 2);
});

test('keeps blocked authentication initialization active for credential recovery', async () => {
	const registry = new FakeRegistry();
	const failure = Object.assign(new Error('login required'), { code: 'AUTHENTICATION_REQUIRED' });
	const planner = createPlannerForService(registry, {
		async createAgent() { throw failure; },
		getAgent() { return null; },
		async removeAgent() { return false; },
	});

	await assert.rejects(() => planner.requestPlan({
		agentId: AGENT_ID,
		input: 'authoritative state',
		goalRevision: GOAL_REVISION,
	}), failure);

	assert.deepEqual(registry.states.at(-1), {
		state: DynamicAgentState.PLANNING,
		options: { goalRevision: GOAL_REVISION },
	});
});

test('enters error only after malformed decision retries are exhausted', async () => {
	const registry = new FakeRegistry();
	let attempts = 0;
	const invalid = Object.assign(new Error('invalid output'), { code: 'MALFORMED_DECISION' });
	const agent = {
		async setGoalRevision() {},
		async decide() {
			attempts += 1;
			throw invalid;
		},
	};
	const planner = createPlanner(registry, agent, 1);

	await assert.rejects(() => planner.requestPlan({
		agentId: AGENT_ID,
		input: 'authoritative state',
		goalRevision: GOAL_REVISION,
	}), invalid);

	assert.equal(attempts, 2);
	assert.deepEqual(registry.states.at(-1), {
		state: DynamicAgentState.ERROR,
		options: {
			goalRevision: GOAL_REVISION,
			error: { code: 'MALFORMED_DECISION', message: 'invalid output' },
		},
	});
});

test('records strict provider-attempt telemetry without planner input or output', async () => {
	const registry = new FakeRegistry();
	const telemetry = [];
	let time = 100;
	const planner = new AgentPlanner({
		registry,
		invalidDecisionRetries: 1,
		now: () => (time += 10),
		telemetrySink: (row) => telemetry.push(row),
		healthRegistry: { canAttempt: () => true, record: () => {} },
		scheduler: {
			schedule(_agentId, operation) { return operation({ signal: new AbortController().signal }); },
			cancel() { return false; },
		},
		codexService: {
			async createAgent() {
				return {
					async setGoalRevision() {},
					async decide() { return VALID_DECISION; },
						sessionMetadata() {
						return { profileFingerprint: `sha256:${'a'.repeat(64)}`, sessionGeneration: 3, sessionState: 'warm', sessionReuse: true, resetReason: null };
					},
				};
			},
			getAgent() { return null; },
			async removeAgent() { return false; },
		},
	});

	await planner.requestPlan({ agentId: AGENT_ID, input: 'private prompt value', goalRevision: GOAL_REVISION });
	assert.deepEqual(telemetry.map((row) => row.operation), ['create_agent', 'decide']);
	assert.ok(telemetry.every((row) => row.provider === 'kimi' && row.model === 'kimi-code/k3'));
	assert.equal(JSON.stringify(telemetry).includes('private prompt value'), false);
	assert.ok(telemetry.every((row) => row.durationMs >= 0 && row.queueWaitMs >= 0));
	assert.ok(telemetry.every((row) => row.profileFingerprint === `sha256:${'a'.repeat(64)}` && row.sessionGeneration === 3));
	assert.equal(telemetry.find((row) => row.operation === 'decide').sessionReuse, true);
});

test('feeds redacted provider telemetry to the scheduler after health recording', async () => {
	const registry = new FakeRegistry();
	const order = [];
	let observed = null;
	const healthRegistry = {
		canAttempt: () => true,
		record: (row) => { order.push(`health:${row.operation}`); },
	};
	const scheduler = {
		schedule(_agentId, operation) { return operation({ signal: new AbortController().signal }); },
		cancel() { return false; },
		pressureSnapshot: { pendingOrdinary: 1 },
		observeProviderTelemetry(row, snapshot) {
			order.push(`scheduler:${row.operation}`);
			observed = { row, snapshot };
		},
	};
	const telemetry = [];
	const planner = new AgentPlanner({
		registry,
		healthRegistry,
		scheduler,
		telemetrySink: (row) => { order.push(`sink:${row.operation}`); telemetry.push(row); },
		codexService: {
			async createAgent() { return { async setGoalRevision() {}, async decide() { return VALID_DECISION; } }; },
			getAgent() { return null; },
			async removeAgent() { return false; },
		},
	});

	await planner.requestPlan({ agentId: AGENT_ID, input: 'state', goalRevision: GOAL_REVISION });
	assert.deepEqual(order, ['health:create_agent', 'scheduler:create_agent', 'sink:create_agent', 'health:decide', 'scheduler:decide', 'sink:decide']);
	assert.equal(observed.row.operation, 'decide');
	assert.deepEqual(observed.snapshot, { pendingOrdinary: 1 });
	assert.equal(telemetry.at(-1).errorCode, null);
});

test('provider circuit rejects work before allocating a provider session', async () => {
	const registry = new FakeRegistry();
	let creates = 0;
	let identity;
	const planner = new AgentPlanner({
		registry,
		healthRegistry: {
			canAttempt: (value) => { identity = value; return false; },
			snapshot: () => ({ nextProbeAtEpochMs: 12_345 }),
			record: () => { throw new Error('must not record'); },
		},
		scheduler: {
			schedule(_agentId, operation) { return operation({ signal: new AbortController().signal }); },
			cancel() { return false; },
		},
		codexService: {
			async createAgent() { creates += 1; throw new Error('must not create'); },
			getAgent() { return null; },
			async removeAgent() { return false; },
		},
	});

	await assert.rejects(
		planner.requestPlan({ agentId: AGENT_ID, input: 'state', goalRevision: GOAL_REVISION }),
		(error) => error?.code === 'PROVIDER_CIRCUIT_OPEN',
	);
	assert.equal(creates, 0);
	assert.deepEqual(identity, {
		provider: RECORD.provider,
		model: RECORD.model,
		operation: 'create_agent',
		profileFingerprint: profileFingerprint(RECORD),
	});
	assert.equal(registry.states.some(({ state }) => state === DynamicAgentState.ERROR), false);
});

test('failed provider creation records the exact fingerprint admitted by the circuit', async () => {
	const registry = new FakeRegistry();
	const admitted = [];
	const recorded = [];
	const failure = Object.assign(new Error('login required'), { code: 'AUTHENTICATION_REQUIRED' });
	const planner = new AgentPlanner({
		registry,
		healthRegistry: {
			canAttempt(identity) { admitted.push(identity); return true; },
			record(telemetry) { recorded.push(telemetry); },
		},
		scheduler: {
			schedule(_agentId, operation) { return operation({ signal: new AbortController().signal }); },
			cancel() { return false; },
		},
		codexService: {
			async createAgent() { throw failure; },
			getAgent() { return null; },
			async removeAgent() { return false; },
		},
	});

	await assert.rejects(
		planner.requestPlan({ agentId: AGENT_ID, input: 'state', goalRevision: GOAL_REVISION }),
		failure,
	);
	assert.equal(admitted.length, 1);
	assert.equal(recorded.length, 1);
	assert.equal(recorded[0].operation, 'create_agent');
	assert.equal(recorded[0].profileFingerprint, admitted[0].profileFingerprint);
});

test('failed goal-spec creation records the exact fingerprint admitted by the circuit', async () => {
	const registry = new FakeRegistry();
	const admitted = [];
	const recorded = [];
	const failure = Object.assign(new Error('provider unavailable'), { code: 'PROVIDER_UNAVAILABLE' });
	const planner = new AgentPlanner({
		registry,
		healthRegistry: {
			canAttempt(identity) { admitted.push(identity); return true; },
			record(telemetry) { recorded.push(telemetry); },
		},
		scheduler: {
			schedule(_agentId, operation) { return operation({ signal: new AbortController().signal }); },
			cancel() { return false; },
		},
		codexService: {
			async createAgent() { throw failure; },
			getAgent() { return null; },
			async removeAgent() { return false; },
		},
	});

	await assert.rejects(planner.requestGoalSpec({
		agentId: AGENT_ID,
		request: {
			requestId: '00000000-0000-4000-8000-000000000002',
			originalRequest: 'Get a good pickaxe',
			candidateIds: ['minecraft:iron_pickaxe'],
		},
	}), failure);
	assert.equal(admitted.length, 1);
	assert.equal(recorded.length, 1);
	assert.equal(recorded[0].operation, 'goal_spec_create');
	assert.equal(recorded[0].profileFingerprint, admitted[0].profileFingerprint);
});

test('planning lease expiry tears down only the matching exact provider generation', async () => {
	const registry = new FakeRegistry();
	let scheduleOptions;
	const agent = {
		sessionGeneration: 4,
		async setGoalRevision() {},
		async decide() { return VALID_DECISION; },
	};
	const replacements = [];
	const planner = new AgentPlanner({
		registry,
		planningLeaseTimeoutMs: 25,
		scheduler: {
			schedule(_agentId, operation, options) {
				scheduleOptions = options;
				return operation({ signal: new AbortController().signal });
			},
			cancel() { return false; },
		},
		codexService: {
			async createAgent() { return agent; },
			getAgent() { return agent; },
			async replaceAgent(record, options) { replacements.push({ record, options }); return agent; },
			async removeAgent() { return false; },
		},
	});

	await planner.requestPlan({ agentId: AGENT_ID, input: 'state', goalRevision: GOAL_REVISION });
	assert.equal(scheduleOptions.leaseTimeoutMs, 25);
	await scheduleOptions.onLeaseExpired();
	assert.deepEqual(replacements, [{
		record: RECORD,
		options: { recoverySummary: 'planning_lease_expired', controlProtocol: 'arena_script', expectedSessionGeneration: 4 },
	}]);
});

test('native turn keeps scheduler and selected Codex profile while delegating body execution', async () => {
	const nativeRecord = { ...RECORD, provider: 'codex', model: 'gpt-5.6-sol', reasoningEffort: 'xhigh' };
	const executionSettings = createExecutionSettings(nativeRecord, { transport: 'app_server', controlProtocol: 'native_tools' });
	const turns = [];
	const traceRows = [];
	const states = [];
	const registry = {
		assertCurrentRevision(agentId, goalRevision) {
			assert.equal(agentId, AGENT_ID);
			assert.equal(goalRevision, GOAL_REVISION);
			return nativeRecord;
		},
		setState(_agentId, state, options) { states.push({ state, options }); },
	};
	const calls = [];
	const executeTool = async () => ({ state: 'SUCCEEDED' });
	const agent = {
		get executionSettings() { return executionSettings; },
		async setGoalRevision(revision) { calls.push(['revision', revision]); },
		async act(input, options) {
			executionSettings.effective.model = nativeRecord.model;
			executionSettings.evidence.model = 'provider_reported';
			calls.push(['act', input, options.goalRevision, options.executeTool]);
			return { status: 'completed', toolCalls: 2 };
		},
	};
	const planner = new AgentPlanner({
		registry,
		turnRecorder: { record(row) { turns.push(row); } },
		recorder: { record(stage, _context, fields) { traceRows.push({ stage, ...fields }); } },
		scheduler: {
			schedule(_agentId, operation, options) { calls.push(['schedule', options]); return operation({ signal: new AbortController().signal }); },
			cancel() { return false; },
		},
		codexService: {
			async createAgent(profile, options) { calls.push(['create', profile, options]); return agent; },
			getAgent() { return null; },
			async removeAgent() { return false; },
		},
	});

	assert.deepEqual(await planner.requestNativeTurn({
		agentId: AGENT_ID,
		goalRevision: GOAL_REVISION,
		input: 'event: goal started',
		executeTool,
		priority: 'urgent',
	}), { status: 'completed', toolCalls: 2 });
	assert.deepEqual(calls.find((call) => call[0] === 'create')[2], { recoverySummary: null, controlProtocol: 'native_tools' });
	assert.equal(typeof calls.find((call) => call[0] === 'act')[3], 'function');
	assert.equal(states[0].state, DynamicAgentState.PLANNING);
	assert.equal(turns.length, 1);
	assert.equal(turns[0].executionSettings.effective.model, nativeRecord.model);
	assert.equal(turns[0].executionSettings.effective.reasoningEffort, null);
	assert.equal(traceRows.findLast((row) => row.stage === 'provider_response_completed').executionSettings.evidence.model, 'provider_reported');
	executionSettings.effective.model = null;
	assert.equal(turns[0].executionSettings.effective.model, nativeRecord.model, 'recorded attestation is a detached snapshot');
});

test('hung native turn releases scheduler capacity without tearing down a newer exact generation', async () => {
	const nativeRecord = { ...RECORD, provider: 'codex', model: 'gpt-5.6-sol', reasoningEffort: 'xhigh' };
	const fingerprint = profileFingerprint(nativeRecord);
	const scheduler = new PlanningScheduler({
		maxConcurrent: 1,
		maxPending: 1,
		settlementGraceMs: 5,
		scheduleTimeout: (callback, delay) => setTimeout(callback, delay),
	});
	let actStarted;
	const started = new Promise((resolve) => { actStarted = resolve; });
	const first = {
		sessionGeneration: 1,
		sessionMetadata: () => ({ profileFingerprint: fingerprint, sessionGeneration: 1 }),
		async setGoalRevision() {},
		async act() { actStarted(); return new Promise(() => {}); },
	};
	const second = {
		sessionGeneration: 2,
		sessionMetadata: () => ({ profileFingerprint: fingerprint, sessionGeneration: 2 }),
	};
	let current = first;
	const replacements = [];
	const planner = new AgentPlanner({
		registry: {
			assertCurrentRevision() { return nativeRecord; },
			setState() {},
		},
		scheduler,
		planningLeaseTimeoutMs: 15,
		codexService: {
			async createAgent() { return first; },
			getAgent() { return current; },
			async replaceAgent(record, options) { replacements.push({ record, options }); return second; },
			async removeAgent() { return false; },
		},
	});
	const turn = planner.requestNativeTurn({ agentId: AGENT_ID, input: 'keep working', goalRevision: GOAL_REVISION, executeTool: async () => ({ state: 'SUCCEEDED' }) });
	await started;
	current = second;
	await assert.rejects(
		Promise.race([
			turn,
			new Promise((_, reject) => setTimeout(() => reject(Object.assign(new Error('native lease did not expire'), { code: 'TEST_TIMEOUT' })), 75)),
		]),
		(error) => error?.code === 'PLANNING_LEASE_EXPIRED',
	);
	assert.equal(await scheduler.schedule('agent-2', async () => 'capacity released', { lane: 'codex' }), 'capacity released');
	assert.equal(replacements.length, 0, 'generation-one expiry cannot replace generation two');
	scheduler.close();
});

test('a native lease expiring during initial creation cannot replace a newly installed generation', async () => {
	const nativeRecord = { ...RECORD, provider: 'codex', model: 'gpt-5.6-sol', reasoningEffort: 'xhigh' };
	const fingerprint = profileFingerprint(nativeRecord);
	const scheduler = new PlanningScheduler({ maxConcurrent: 1, maxPending: 1 });
	let creationStarted;
	const started = new Promise((resolve) => { creationStarted = resolve; });
	const second = {
		sessionGeneration: 2,
		sessionMetadata: () => ({ profileFingerprint: fingerprint, sessionGeneration: 2 }),
	};
	let current = null;
	const replacements = [];
	const planner = new AgentPlanner({
		registry: {
			assertCurrentRevision() { return nativeRecord; },
			setState() {},
		},
		scheduler,
		planningLeaseTimeoutMs: 15,
		codexService: {
			async createAgent() { creationStarted(); return new Promise(() => {}); },
			getAgent() { return current; },
			async replaceAgent(record, options) { replacements.push({ record, options }); return second; },
			async removeAgent() { return false; },
		},
	});
	const turn = planner.requestNativeTurn({ agentId: AGENT_ID, input: 'keep working', goalRevision: GOAL_REVISION, executeTool: async () => ({ state: 'SUCCEEDED' }) });
	await started;
	current = second;
	await assert.rejects(
		Promise.race([
			turn,
			new Promise((_, reject) => setTimeout(() => reject(Object.assign(new Error('native lease did not expire'), { code: 'TEST_TIMEOUT' })), 75)),
		]),
		(error) => error?.code === 'PLANNING_LEASE_EXPIRED',
	);
	assert.equal(replacements.length, 0, 'an unowned creation lease cannot tear down a later current generation');
	scheduler.close();
});

test('native turn leaves lifecycle error decisions to the coordinator', async () => {
	const nativeRecord = { ...RECORD, provider: 'codex', model: 'gpt-5.6-sol', reasoningEffort: 'xhigh' };
	const states = [];
	const failure = Object.assign(new Error('provider timed out'), { code: 'PLANNING_TIMEOUT' });
	const planner = new AgentPlanner({
		registry: {
			assertCurrentRevision() { return nativeRecord; },
			setState(_agentId, state, options) { states.push({ state, options }); },
		},
		scheduler: {
			schedule(_agentId, operation) { return operation({ signal: new AbortController().signal }); },
			cancel() { return false; },
		},
		codexService: {
			async createAgent() {
				return {
					async setGoalRevision() {},
					async act() { throw failure; },
				};
			},
			getAgent() { return null; },
			async removeAgent() { return false; },
		},
	});

	await assert.rejects(planner.requestNativeTurn({
		agentId: AGENT_ID,
		goalRevision: GOAL_REVISION,
		input: 'event: goal started',
		executeTool: async () => ({ state: 'SUCCEEDED' }),
	}), (error) => error === failure);
	assert.deepEqual(states.map(({ state }) => state), [DynamicAgentState.PLANNING]);
});

test('urgent native steering reuses the active selected-model turn without scheduler admission', async () => {
	const nativeRecord = { ...RECORD, provider: 'codex', model: 'gpt-5.6-sol', reasoningEffort: 'xhigh' };
	const calls = [];
	const planner = new AgentPlanner({
		registry: {
			assertCurrentRevision(agentId, goalRevision) {
				assert.equal(agentId, AGENT_ID);
				assert.equal(goalRevision, GOAL_REVISION);
				return nativeRecord;
			},
		},
		scheduler: {
			schedule() { throw new Error('steering must not consume another scheduler slot'); },
			cancel() { return false; },
		},
		codexService: {
			getAgent(agentId) {
				assert.equal(agentId, AGENT_ID);
				return { async steer(input, options) { calls.push({ input, options }); return { turnId: 'turn-active' }; } };
			},
			async createAgent() { throw new Error('steering must not create another agent session'); },
			async removeAgent() { return false; },
		},
	});

	assert.deepEqual(await planner.steerNativeTurn({
		agentId: AGENT_ID,
		goalRevision: GOAL_REVISION,
		input: 'urgent event: direct message from Lucas',
	}), { turnId: 'turn-active' });
	assert.deepEqual(calls, [{
		input: 'urgent event: direct message from Lucas',
		options: { goalRevision: GOAL_REVISION },
	}]);
});

function createPlanner(registry, agent, invalidDecisionRetries) {
	return createPlannerForService(registry, {
		async createAgent() { return agent; },
		getAgent() { return null; },
		async removeAgent() { return false; },
	}, invalidDecisionRetries);
}

function createPlannerForService(registry, codexService, invalidDecisionRetries = 1, options = {}) {
	return new AgentPlanner({
		...options,
		registry,
		invalidDecisionRetries,
		scheduler: {
			schedule(_agentId, operation) {
				return operation({ signal: new AbortController().signal });
			},
			cancel() { return false; },
		},
		codexService,
	});
}

class FakeRegistry {
	states = [];

	assertCurrentRevision(agentId, goalRevision) {
		assert.equal(agentId, AGENT_ID);
		assert.equal(goalRevision, GOAL_REVISION);
		return RECORD;
	}

	get(agentId) {
		return agentId === AGENT_ID ? RECORD : null;
	}

	setState(_agentId, state, options) {
		this.states.push({ state, options });
	}
}


test('passes the exact optional turn recorder to the selected provider without changing the decision attempt', async () => {
	const registry = new FakeRegistry();
	const recorder = { record: async () => { throw new Error('recorder unavailable'); } };
	const optionsSeen = [];
	let clock = 100;
	let attempts = 0;
	const agent = {
		async setGoalRevision() {},
		async decide(input, options) {
			attempts += 1;
			optionsSeen.push({ input, options });
			try { await options.turnRecorder.record({ input }); } catch { /* provider hooks are observational */ }
			return VALID_DECISION;
		},
	};
	const planner = new AgentPlanner({
		registry,
		turnRecorder: recorder,
		now: () => clock,
		scheduler: { schedule(_id, operation) { clock = 137; return operation({ signal: new AbortController().signal }); }, cancel() { return false; } },
		codexService: { async createAgent() { return agent; }, getAgent() { return null; }, async removeAgent() { return false; } },
	});

	const result = await planner.requestPlan({ agentId: AGENT_ID, input: 'state', goalRevision: GOAL_REVISION });
	assert.deepEqual(result, { ...VALID_DECISION, goalRevision: GOAL_REVISION });
	assert.equal(attempts, 1);
	assert.equal(optionsSeen[0].options.turnRecorder, recorder);
	assert.equal(optionsSeen[0].options.queueWaitMs, 37);
});

test('preserves attempt and retry metadata through corrective provider retries', async () => {
	const registry = new FakeRegistry();
	const records = [];
	const recorder = { async record(row) { records.push(row); } };
	let calls = 0;
	const invalid = Object.assign(new Error('bad decision'), { code: 'MALFORMED_DECISION' });
	const agent = {
		async setGoalRevision() {},
		async decide(input, options) {
			await options.turnRecorder.record({ attempt: options.attempt, retry: options.retry, input });
			calls += 1;
			if (calls === 1) throw invalid;
			return VALID_DECISION;
		},
	};
	const planner = new AgentPlanner({
		registry,
		turnRecorder: recorder,
		scheduler: { schedule(_id, operation) { return operation({ signal: new AbortController().signal }); }, cancel() { return false; } },
		codexService: { async createAgent() { return agent; }, getAgent() { return null; }, async removeAgent() { return false; } },
	});

	await planner.requestPlan({ agentId: AGENT_ID, input: 'state', goalRevision: GOAL_REVISION });
	assert.deepEqual(records.map(({ attempt, retry }) => ({ attempt, retry })), [
		{ attempt: 1, retry: false },
		{ attempt: 2, retry: true },
	]);
});
