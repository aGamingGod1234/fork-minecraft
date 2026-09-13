import assert from 'node:assert/strict';
import test from 'node:test';

import { AgentRegistry, DynamicAgentState } from '../src/agent-registry.mjs';
import { ProgramRuntimeManager } from '../src/program-runtime-manager.mjs';
import { withCompletionContract } from './fixtures/completion-contract.mjs';

const SOURCE = 'program.onUnhandledAttention("continue_and_notify"); await player.wait(1);';

test('sixteen independent agents install distinct model-authored programs and actions', async () => {
	const registry = new AgentRegistry({ agentCap: 16 });
	const sent = [];
	const manager = new ProgramRuntimeManager({
		registry,
		bridge: { send: async (type, agentId, payload) => sent.push({ type, agentId, payload }) },
		planner: { requestPlan: async () => { throw new Error('pre-authored programs must not request a provider turn'); } },
	});
	const agentIds = Array.from({ length: 16 }, (_, index) => `agent-${index + 1}`);
	for (const agentId of agentIds) registry.register({ agentId, provider: 'codex', model: 'gpt-5.6-sol', reasoningEffort: 'high', state: DynamicAgentState.STARTING, goalRevision: 1, currentGoal: `Goal ${agentId}`, queue: [] });
	await Promise.all(agentIds.map((agentId, index) => manager.installDecision(registry.get(agentId), withCompletionContract({ summary: `Wait ${agentId}.`, directive: 'replace', source: SOURCE }, 1), {
		observation: { player: { x: index, y: 64, z: 0, health: 20 }, items: [], entities: [], blocks: [], inventory: { items: [], tagCounts: {} } },
		eventSequence: 1,
	})));
	assert.equal(sent.filter((row) => row.type === 'action_command').length, 16);
	const commands = sent.filter((row) => row.type === 'action_command');
	assert.equal(new Set(commands.map((row) => `${row.agentId}:${row.payload.provenance.programId}`)).size, 16);
	assert.equal(new Set(commands.map((row) => row.payload.actionId)).size, 16);
	for (const command of commands) {
		assert.equal(command.payload.provenance.model, 'gpt-5.6-sol');
		assert.equal(command.payload.provenance.programId, 'program-1-1');
		assert.match(command.payload.provenance.sourceStepId, /^step-/);
		assert.equal(Number.isSafeInteger(command.payload.provenance.eventSequence), true);
	}
	console.log(`TASK10_SIXTEEN_AGENT_SUMMARY ${JSON.stringify({ scenario: 'sixteen_agent_program_soak', passed: true, agents: agentIds.length, commands: commands.length })}`);
});
