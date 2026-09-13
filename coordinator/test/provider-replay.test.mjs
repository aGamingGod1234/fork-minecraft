import assert from 'node:assert/strict';
import test from 'node:test';

import {
	ReplayProvider,
	canonicalJson,
	canonicalize,
	createReplayRecord,
	decisionHash,
	hashIdentity,
	normalizeDecision,
	projectScenarioIdentity,
	verifyReplayDecision,
} from '../src/benchmark/provider-replay.mjs';
import { getSimulatorScenario } from '../src/simulator/simulator-scenarios.mjs';
import { replaceDecision } from './provider-decision-fixtures.mjs';

const PROFILE = Object.freeze({
	provider: 'codex', model: 'gpt-5.6-sol', reasoningEffort: 'high', serviceTier: 'fast',
});
const SCENARIO = Object.freeze({ id: 'wait', seed: 42, commands: [{ actionType: 'wait', arguments: { durationMs: 1 } }] });
const DECISION = Object.freeze(replaceDecision({
	summary: 'wait',
	source: 'program.onUnhandledAttention("continue_and_notify"); await player.wait(1);',
}));

function record(overrides = {}) {
	return createReplayRecord({
		trialId: 'trial-1', prompt: 'prompt-v1', providerProfile: PROFILE, scenario: SCENARIO,
		protocolVersion: 2, decision: DECISION, ...overrides,
	});
}

test('records only bounded normalized decisions and exact identity hashes', () => {
	const result = record();
	assert.equal(result.trialId, 'trial-1');
	assert.equal(result.protocolVersion, 2);
	assert.equal(result.promptHash, hashIdentity('prompt-v1'));
	assert.equal(result.profileHash, hashIdentity(PROFILE));
	assert.equal(result.scenarioHash, hashIdentity(SCENARIO));
	assert.equal(result.decisionHash, decisionHash(DECISION));
	assert.deepEqual(result.decision, normalizeDecision(DECISION));
	assert.ok(Object.isFrozen(result));
	assert.equal(JSON.stringify(result).includes('prompt-v1'), false);
});

test('replays exact decisions and rejects identity drift before execution', async () => {
	const recording = record();
	const provider = new ReplayProvider({
		recordings: [recording], trialId: 'trial-1', prompt: 'prompt-v1',
		providerProfile: PROFILE, scenario: SCENARIO, protocolVersion: 2,
	});
	const session = await provider.createAgent({ agentId: 'agent-a', ...PROFILE });
	await session.setGoalRevision(1);
	assert.deepEqual(await session.decide('prompt-v1', { goalRevision: 1 }), DECISION);
	assert.deepEqual(await session.decide('prompt-v1', { goalRevision: 1 }), DECISION);
	assert.equal(provider.calls, 2);

	for (const drift of [
		{ prompt: 'prompt-v2' },
		{ providerProfile: { ...PROFILE, model: 'different-model' } },
		{ scenario: { ...SCENARIO, seed: 43 } },
		{ protocolVersion: 3 },
		{ trialId: 'trial-other' },
	]) {
		const drifted = new ReplayProvider({ recordings: [recording], trialId: 'trial-1', prompt: 'prompt-v1', providerProfile: PROFILE, scenario: SCENARIO, protocolVersion: 2, ...drift });
		await assert.rejects(async () => {
			const driftSession = await drifted.createAgent({ agentId: 'agent-a', ...PROFILE });
			await driftSession.setGoalRevision(1);
			await driftSession.decide('prompt-v1', { goalRevision: 1 });
		}, (error) => error.code === 'REPLAY_IDENTITY_MISMATCH');
	}
});

test('rejects a replay decision mismatch rather than accepting provider drift', () => {
	const recording = record();
	assert.throws(
		() => verifyReplayDecision(recording, { ...DECISION, source: 'program.onUnhandledAttention("continue_and_notify"); await player.wait(2);' }),
		(error) => error.code === 'REPLAY_DECISION_MISMATCH',
	);
});

test('canonicalization is bounded, prototype-safe, and never invokes accessors', () => {
	const nullPrototype = Object.create(null);
	nullPrototype.z = 1;
	nullPrototype.a = [true, 'ok'];
	const normalized = canonicalize(nullPrototype);
	assert.equal(Object.getPrototypeOf(normalized), null);
	assert.equal(Array.isArray(normalized.a), true);
	assert.equal(canonicalJson(nullPrototype), '{"a":[true,"ok"],"z":1}');

	let invoked = false;
	const accessor = {};
	Object.defineProperty(accessor, 'secret', { enumerable: true, get() { invoked = true; return 'do-not-read'; } });
	assert.throws(() => hashIdentity(accessor), (error) => error.code === 'REPLAY_ACCESSOR_REJECTED');
	assert.equal(invoked, false);

	let proxyTrapInvoked = false;
	const hostileProxy = new Proxy({ value: 1 }, {
		ownKeys() { proxyTrapInvoked = true; throw new Error('proxy trap'); },
	});
	assert.throws(() => hashIdentity(hostileProxy), (error) => error.code === 'REPLAY_UNSAFE_OBJECT');
	assert.equal(proxyTrapInvoked, true);

	const tooManyKeys = Object.fromEntries(Array.from({ length: 129 }, (_, index) => [`key-${index}`, index]));
	assert.throws(() => hashIdentity(tooManyKeys), (error) => error.code === 'REPLAY_TOO_MANY_KEYS');
	assert.throws(() => hashIdentity('x'.repeat(70_000)), (error) => error.code === 'REPLAY_STRING_TOO_LARGE');
	assert.throws(() => hashIdentity('x'.repeat(70_000)), /bounded/i);
});

test('projects shipped function-bearing manifests without serializing executable values', () => {
	const stone = getSimulatorScenario('stone-tool-gathering');
	const recording = createReplayRecord({
		trialId: 'stone-projection', prompt: 'stone prompt', providerProfile: PROFILE, scenario: stone,
		decision: DECISION,
	});
	assert.equal(typeof stone.success, 'function');
	assert.equal(recording.scenarioHash, hashIdentity(projectScenarioIdentity(stone)));
	assert.equal(JSON.stringify(recording).includes('success'), false);
	assert.equal(JSON.stringify(recording).includes('stone prompt'), false);
});

test('replays exact independent decision sequences for translated agents in one load', async () => {
	const decisionA1 = replaceDecision({ summary: 'a1', source: SOURCE_FOR('a1') });
	const decisionA2 = { summary: 'a2', directive: 'continue' };
	const decisionB1 = replaceDecision({ summary: 'b1', source: SOURCE_FOR('b1') });
	const decisionB2 = { summary: 'b2', directive: 'continue' };
	const recordingA = createReplayRecord({
		trialId: 'multi-turn-load-2', agentId: 'agent-a', agentLoad: 2, prompt: 'a-turn-1',
		prompts: ['a-turn-1', 'a-turn-2'], providerProfile: PROFILE, scenario: SCENARIO,
		decisions: [decisionA1, decisionA2],
	});
	const recordingB = createReplayRecord({
		trialId: 'multi-turn-load-2', agentId: 'agent-b', agentLoad: 2, prompt: 'b-turn-1',
		prompts: ['b-turn-1', 'b-turn-2'], providerProfile: PROFILE, scenario: SCENARIO,
		decisions: [decisionB1, decisionB2],
	});
	const provider = new ReplayProvider({ recordings: [recordingA, recordingB], trialId: 'multi-turn-load-2', prompt: 'a-turn-1', providerProfile: PROFILE, scenario: SCENARIO, agentLoad: 2, protocolVersion: 2 });
	const sessionA = await provider.createAgent({ agentId: 'agent-a', agentLoad: 2, ...PROFILE });
	const sessionB = await provider.createAgent({ agentId: 'agent-b', agentLoad: 2, ...PROFILE });
	assert.deepEqual(await sessionA.decide('a-turn-1'), decisionA1);
	assert.deepEqual(await sessionA.decide('a-turn-2'), decisionA2);
	await assert.rejects(() => sessionA.decide('a-turn-3'), (error) => error.code === 'REPLAY_EXHAUSTED');
	assert.deepEqual(await sessionB.decide('b-turn-1'), decisionB1);
	assert.deepEqual(await sessionB.decide('b-turn-2'), decisionB2);
	await assert.rejects(() => sessionB.decide('b-turn-3'), (error) => error.code === 'REPLAY_EXHAUSTED');
	assert.equal(provider.calls, 4);
});

test('rejects prompt drift at the exact turn and does not consume the recorded decision', async () => {
	const recording = createReplayRecord({
		trialId: 'prompt-drift', prompt: 'turn-1', prompts: ['turn-1', 'turn-2'], providerProfile: PROFILE,
		scenario: SCENARIO, decisions: [DECISION, { summary: 'second', directive: 'continue' }],
	});
	const provider = new ReplayProvider({ recording, trialId: 'prompt-drift', prompt: 'turn-1', providerProfile: PROFILE, scenario: SCENARIO, protocolVersion: 2 });
	const session = await provider.createAgent({ agentId: 'agent-a', ...PROFILE });
	await assert.rejects(() => session.decide('wrong-turn-1'), (error) => error.code === 'REPLAY_PROMPT_MISMATCH');
	assert.deepEqual(await session.decide('turn-1'), DECISION);
	await assert.rejects(() => session.decide('wrong-turn-2'), (error) => error.code === 'REPLAY_PROMPT_MISMATCH');
	assert.deepEqual(await session.decide('turn-2'), { summary: 'second', directive: 'continue' });
});

test('replays bounded per-turn wall delays without exposing prompts', async () => {
	const sleeps = [];
	const recording = createReplayRecord({
		trialId: 'timed-replay', prompt: 'turn-1', prompts: ['turn-1', 'turn-2'], providerProfile: PROFILE,
		scenario: SCENARIO, decisions: [DECISION, { summary: 'second', directive: 'continue' }], delaysMs: [125, 250],
	});
	assert.deepEqual(recording.delaysMs, [125, 250]);
	assert.match(recording.timingHash, /^sha256:[a-f0-9]{64}$/);
	assert.equal(JSON.stringify(recording).includes('turn-1'), false);
	const provider = new ReplayProvider({
		recording, trialId: 'timed-replay', prompt: 'turn-1', providerProfile: PROFILE, scenario: SCENARIO,
		sleep: async (milliseconds) => { sleeps.push(milliseconds); },
	});
	const session = await provider.createAgent({ agentId: 'agent-a', ...PROFILE });
	assert.deepEqual(await session.decide('turn-1'), DECISION);
	assert.deepEqual(await session.decide('turn-2'), { summary: 'second', directive: 'continue' });
	assert.deepEqual(sleeps, [125, 250]);
});

test('agent-specific replay validates the real turn prompt instead of a global placeholder', async () => {
	const recording = createReplayRecord({
		trialId: 'agent-specific', agentId: 'agent-a', agentLoad: 1, prompt: 'real generated prompt',
		providerProfile: PROFILE, scenario: SCENARIO, decision: DECISION,
	});
	const provider = new ReplayProvider({
		recording, trialId: 'agent-specific', prompt: 'private placeholder', providerProfile: PROFILE,
		scenario: SCENARIO, agentLoad: 1,
	});
	const session = await provider.createAgent({ agentId: 'agent-a', agentLoad: 1, ...PROFILE });
	assert.deepEqual(await session.decide('real generated prompt'), DECISION);
});

function SOURCE_FOR(label) {
	return `program.onUnhandledAttention("continue_and_notify"); await player.wait(${label.length}); program.finish("done");`;
}
