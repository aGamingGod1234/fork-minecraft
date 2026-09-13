import assert from 'node:assert/strict';
import { mkdtemp, rm } from 'node:fs/promises';
import os from 'node:os';
import path from 'node:path';
import test from 'node:test';

import { AgentPlanner } from '../src/agent-planner.mjs';
import { AgentRegistry, DynamicAgentState } from '../src/agent-registry.mjs';
import { AgentWorkspaceManager } from '../src/agent-workspace.mjs';
import { PlanningScheduler } from '../src/planning-scheduler.mjs';
import { ProviderHealthRegistry } from '../src/provider-health-registry.mjs';

const VALID_DECISION = Object.freeze({ summary: 'Advance safely.', directive: 'replace', source: 'program.onUnhandledAttention("continue_and_notify"); await player.wait(25);' });

test('eight-agent fake-provider soak preserves capacity, isolation, cancellation, and circuit recovery', async () => {
	const root = await mkdtemp(path.join(os.tmpdir(), 'arena-eight-agent-soak-'));
	try {
		const registry = new AgentRegistry({ agentCap: 9 });
		const pressures = [];
		const scheduler = new PlanningScheduler({ maxConcurrent: 4, maxPending: 4, onPressure: (snapshot) => pressures.push(snapshot) });
		const workspaceManager = new AgentWorkspaceManager(root);
		const sessions = new Map();
		const pendingDecisions = [];
		let phase = 'complete';
		const providerService = {
			async createAgent(record) {
				let session = sessions.get(record.agentId);
				if (session !== undefined) return session;
				const workspace = await workspaceManager.prepare(record.provider, record.agentId);
				session = {
					agentId: record.agentId,
					provider: record.provider,
					workspace,
					async setGoalRevision() {},
					decide(_input, { signal }) {
						if (signal.aborted) return Promise.reject(signal.reason);
						return new Promise((resolve, reject) => {
							const entry = { agentId: record.agentId, resolve, reject, settled: false };
							pendingDecisions.push(entry);
							signal.addEventListener('abort', () => {
								if (entry.settled) return;
								entry.settled = true;
								reject(signal.reason);
							}, { once: true });
							if (phase === 'complete') queueMicrotask(() => {
								if (entry.settled) return;
								entry.settled = true;
								resolve(VALID_DECISION);
							});
						});
					},
				};
				sessions.set(record.agentId, session);
				return session;
			},
			getAgent(agentId) { return sessions.get(agentId) ?? null; },
			async removeAgent(agentId) { return sessions.delete(agentId); },
		};
		let plannerTime = 0;
		const planner = new AgentPlanner({ registry, scheduler, codexService: providerService, now: () => ++plannerTime });

		for (let index = 1; index <= 9; index += 1) {
			const agentId = `soak-${index}`;
			const provider = ['codex', 'gemini', 'kimi'][(index - 1) % 3];
			registry.register({ agentId, provider, model: `${provider}-model`, reasoningEffort: 'high', state: DynamicAgentState.IDLE, goalRevision: 0, queue: [] });
			registry.applyGoalControl(agentId, { operation: 'start', goalRevision: 1, goal: `Goal ${index}` });
		}

		phase = 'block';
		const firstWave = Array.from({ length: 8 }, (_, index) => planner.requestPlan({ agentId: `soak-${index + 1}`, input: `private-${index}`, goalRevision: 1 }));
		await eventually(() => scheduler.activeCount === 4 && scheduler.pendingCount === 4);
		await assert.rejects(planner.requestPlan({ agentId: 'soak-9', input: 'must reject', goalRevision: 1 }), (error) => error?.code === 'SCHEDULER_CAPACITY');
		assert.ok(pressures.some((snapshot) => snapshot.warning), '75 percent pressure warning was emitted');

		await eventually(() => pendingDecisions.length >= 4);
		resolveCurrent(pendingDecisions);
		await eventually(() => pendingDecisions.length >= 8);
		resolveCurrent(pendingDecisions);
		await Promise.all(firstWave);
		assert.equal(sessions.size, 8);
		assert.equal(new Set([...sessions.values()].map((session) => session.workspace)).size, 8);
		assert.ok([...sessions.values()].every((session) => session.workspace.includes(session.agentId)));

		for (let index = 1; index <= 8; index += 1) registry.setState(`soak-${index}`, DynamicAgentState.ACTING, { goalRevision: 1 });
		phase = 'cancel';
		const cancellationWave = Array.from({ length: 8 }, (_, index) => planner.requestPlan({ agentId: `soak-${index + 1}`, input: 'cancel wave', goalRevision: 1 }));
		await eventually(() => scheduler.activeCount === 4 && scheduler.pendingCount === 4);
		scheduler.close('soak complete');
		const cancelled = await Promise.allSettled(cancellationWave);
		assert.ok(cancelled.every((result) => result.status === 'rejected'));
		await eventually(() => scheduler.activeCount === 0 && scheduler.pendingCount === 0);

		let healthTime = 0;
		const health = new ProviderHealthRegistry({ minimumSamples: 2, failureRateToOpen: 1, cooldownMs: 10, now: () => healthTime });
		const geminiHealth = { provider: 'gemini', model: 'gemini-model', operation: 'decide' };
		const codexHealth = { provider: 'codex', model: 'codex-model', operation: 'decide' };
		health.record(telemetry('gemini', 'PROVIDER_UNAVAILABLE'));
		health.record(telemetry('gemini', 'PROVIDER_UNAVAILABLE'));
		assert.equal(health.canAttempt(geminiHealth), false);
		healthTime = 11;
		assert.equal(health.canAttempt(geminiHealth), true);
		health.record(telemetry('gemini', null));
		assert.equal(health.snapshot(geminiHealth).circuit, 'closed');
		assert.equal(health.snapshot(codexHealth).count, 0, 'provider samples remain isolated');
		console.log(`TASK10_EIGHT_AGENT_SUMMARY ${JSON.stringify({ scenario: 'eight_agent_fake_provider_soak', passed: true, agents: 8, maxConcurrent: scheduler.maxConcurrent, maxPending: scheduler.maxPending, pressureWarning: true, cancellation: 'all-rejected', providerCircuitRecovery: true })}`);
	} finally {
		await rm(root, { recursive: true, force: true });
	}
});

function resolveCurrent(entries) {
	for (const entry of entries) {
		if (entry.settled) continue;
		entry.settled = true;
		entry.resolve(VALID_DECISION);
	}
}

function telemetry(provider, errorCode) {
	return { provider, model: `${provider}-model`, operation: 'decide', attempt: 1, queueWaitMs: 0, durationMs: 10, errorCode, timeout: false, retry: false, restart: false };
}

async function eventually(predicate, message = 'condition was not reached') {
	const deadline = Date.now() + 1_000;
	while (Date.now() < deadline) {
		if (predicate()) return;
		await new Promise((resolve) => setTimeout(resolve, 5));
	}
	throw new Error(message);
}
