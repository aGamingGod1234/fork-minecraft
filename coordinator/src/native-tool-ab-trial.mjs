import os from 'node:os';
import path from 'node:path';
import { mkdir } from 'node:fs/promises';
import { pathToFileURL } from 'node:url';

import { runNativeToolCli } from './native-tool-cli-boundary.mjs';

async function main() {
const [serviceModulePath, variant, trialText, model = 'gpt-5.6-luna', reasoningEffort = 'xhigh', serviceTier = 'fast'] = process.argv.slice(2);
if (!serviceModulePath || !variant || !trialText) throw new TypeError('service module, variant, and trial are required');
const trial = Number.parseInt(trialText, 10);
if (!Number.isSafeInteger(trial) || trial < 1) throw new TypeError('trial must be a positive integer');

const [{ AgentWorkspaceManager }, { CodexService }] = await Promise.all([
	import('./agent-workspace.mjs'),
	import(pathToFileURL(path.resolve(serviceModulePath)).href),
]);
const probeRoot = path.join(os.tmpdir(), 'arena-native-ab', `trial-${trial}`);
await mkdir(probeRoot, { recursive: true });
const profile = { agentId: `native-ab-trial-${trial}`, provider: 'codex', model, reasoningEffort, serviceTier };
const startedAt = performance.now();
const service = new CodexService({
	cwd: probeRoot,
	planningTimeoutMs: 90_000,
	serviceTier,
	launchProfile: { model, reasoningEffort, serviceTier, cwd: probeRoot },
}, {
	workspaceManager: new AgentWorkspaceManager(path.join(probeRoot, 'workspaces')),
});

const runTurn = async (agent, input) => {
	const turnStartedAt = performance.now();
	let firstToolAt = null;
	let lastToolAt = null;
	const calls = [];
	const result = await agent.act(input, {
		goalRevision: 1,
		executeTool: async (request) => {
			const now = performance.now();
			firstToolAt ??= now;
			lastToolAt = now;
			calls.push(request.tool.actionType ?? request.tool.kind);
			return { state: 'SUCCEEDED', reasonCode: '', executionStarted: true, delivered: true };
		},
	});
	return {
		firstToolMs: firstToolAt === null ? null : Math.round(firstToolAt - turnStartedAt),
		lastToolMs: lastToolAt === null ? null : Math.round(lastToolAt - turnStartedAt),
		totalMs: Math.round(performance.now() - turnStartedAt),
		calls,
		result,
	};
};

try {
	await service.start();
	const agent = await service.createAgent(profile, { controlProtocol: 'native_tools' });
	await agent.setGoalRevision(1);
	const readyAt = performance.now();
	const coldDm = await runTurn(agent, 'event: Lucas sent a DM saying "hi". Call say exactly once with a short friendly reply, then end this turn.');
	const warmDm = await runTurn(agent, 'event: Lucas sent a DM saying "how are you?". Call say exactly once with a short friendly reply, then end this turn.');
	const moveMine = await runTurn(agent, 'event: active goal is to mine the known stone block at x=2,y=64,z=1. You are at x=0,y=64,z=0. Call moveTo near it, use the successful result, then call mine on that exact block. Do not finish this goal in this probe.');
	const craft = await runTurn(agent, 'event: probe only. Call act exactly once with actionType craft_inventory and arguments recipeId minecraft:oak_planks, count 4, timeoutMs 15000. End this turn after the successful result.');
	return {
		status: 'PASSED', variant, trial, profile: { model, reasoningEffort, serviceTier },
		initializationMs: Math.round(readyAt - startedAt),
		totalMs: Math.round(performance.now() - startedAt),
		coldDm, warmDm, moveMine, craft,
	};
} finally {
	await service.stop();
}
}

process.exitCode = await runNativeToolCli(main);
