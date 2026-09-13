import test from 'node:test';
import assert from 'node:assert/strict';

import { AgentRegistry, DynamicAgentState } from '../src/agent-registry.mjs';
import { goalSpecFingerprint } from '../src/goal-spec.mjs';
import { ProgramRuntimeManager } from '../src/program-runtime-manager.mjs';

function goalSpec(originalRequest = 'craft a wooden pickaxe') {
	const fields = {
		originalRequest,
		predicate: { type: 'inventory_contains', itemId: 'minecraft:wooden_pickaxe', count: 1 },
		createdAtTick: 1,
	};
	return Object.freeze({ ...fields, fingerprint: goalSpecFingerprint(fields) });
}

function record() {
	const currentGoalSpec = goalSpec();
	return { agentId: 'agent-a', provider: 'codex', model: 'gpt-5.6-sol', reasoningEffort: 'high', serviceTier: 'priority', state: DynamicAgentState.STARTING, goalRevision: 1, currentGoal: currentGoalSpec.originalRequest, currentGoalSpec, queue: [] };
}

function observation() {
	return { player: { x: 0, y: 64, z: 0, health: 20 }, inventory: { items: [], tagCounts: {} }, items: [], entities: [], blocks: [] };
}

test('finish is only a verification request and cannot enter COMPLETED before Minecraft accepts it', async () => {
	const registry = new AgentRegistry();
	registry.register(record());
	const requests = [];
	const manager = new ProgramRuntimeManager({
		registry,
		bridge: { send: async () => {} },
		planner: { requestPlan: async () => ({ directive: 'continue', summary: 'continue' }) },
		onCompletionRequested: (request) => requests.push(request),
	});
	await manager.installDecision(registry.get('agent-a'), {
		summary: 'Claimed pickaxe.', directive: 'replace',
		source: 'program.onUnhandledAttention("continue_and_notify"); program.finish("done");',
	}, { observation: observation(), eventSequence: 1 });
	await new Promise((resolve) => setImmediate(resolve));
	assert.equal(requests.length, 1);
	assert.equal(registry.get('agent-a').state, DynamicAgentState.STARTING);
	const request = requests[0];
	assert.equal(request.goalFingerprint, registry.get('agent-a').currentGoalSpec.fingerprint);
	assert.equal(manager.onCompletionResult(registry.get('agent-a'), {
		goalRevision: 1, traceId: request.traceId, goalFingerprint: request.goalFingerprint,
		verified: false, reasonCode: 'PREDICATE_FAILED', facts: [{ type: 'inventory_contains', satisfied: false, expectedValue: 'minecraft:wooden_pickaxe x1', observedValue: '0' }],
	}), true);
	assert.notEqual(registry.get('agent-a').state, DynamicAgentState.COMPLETED);
	assert.equal(manager.onCompletionResult(registry.get('agent-a'), {
		goalRevision: 1, traceId: request.traceId, goalFingerprint: request.goalFingerprint,
		verified: true, reasonCode: 'COMPLETION_VERIFIED', facts: [],
	}), false, 'the rejected request is no longer authoritative after correction begins');
	assert.notEqual(registry.get('agent-a').state, DynamicAgentState.COMPLETED);
});

test('a model-supplied completion contract cannot replace the immutable server goal fingerprint', async () => {
	const registry = new AgentRegistry();
	registry.register(record());
	const requests = [];
	const manager = new ProgramRuntimeManager({
		registry,
		bridge: { send: async () => {} },
		planner: { requestPlan: async () => ({ directive: 'continue', summary: 'continue' }) },
		onCompletionRequested: (request) => requests.push(request),
	});
	await manager.installDecision(registry.get('agent-a'), {
		summary: 'Try to weaken proof.', directive: 'replace',
		source: 'program.onUnhandledAttention("continue_and_notify"); program.finish("done");',
		completionContract: { goalRevision: 1, predicates: [{ type: 'position_within', x: 0, y: 64, z: 0, radius: 100 }] },
	}, { observation: observation(), eventSequence: 1 });
	await new Promise((resolve) => setImmediate(resolve));
	assert.equal(requests.length, 1);
	assert.equal(requests[0].goalFingerprint, registry.get('agent-a').currentGoalSpec.fingerprint);
	assert.equal(Object.hasOwn(requests[0], 'completionContract'), false);
});
