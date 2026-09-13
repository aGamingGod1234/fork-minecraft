import os from 'node:os';
import path from 'node:path';
import { mkdir } from 'node:fs/promises';

import { runNativeToolCli } from './native-tool-cli-boundary.mjs';

async function main() {
const [model = 'gpt-5.6-luna', reasoningEffort = 'low', serviceTier = 'fast'] = process.argv.slice(2);
const [{ CodexService }, { MinecraftAgentWorkspace }] = await Promise.all([
	import('./codex-service.mjs'),
	import('./minecraft-agent-workspace.mjs'),
]);
const startedAt = performance.now();
const probeRoot = path.join(os.tmpdir(), 'arena-native-probe');
await mkdir(probeRoot, { recursive: true });
const service = new CodexService({
	cwd: probeRoot,
	planningTimeoutMs: 90_000,
	serviceTier,
	launchProfile: { model, reasoningEffort, serviceTier, cwd: probeRoot },
}, {
	minecraftWorkspace: new MinecraftAgentWorkspace({
		root: path.join(probeRoot, 'minecraft-agent'),
		templateRoot: path.resolve('config', 'minecraft-agent'),
	}),
});

try {
	await service.start();
	const agent = await service.createAgent({ agentId: 'native-probe', provider: 'codex', model, reasoningEffort, serviceTier }, { controlProtocol: 'native_tools' });
	await agent.setGoalRevision(1);
	const readyAt = performance.now();
	const prewarmAgent = await service.createAgent({ agentId: 'native-prewarm-probe', provider: 'codex', model, reasoningEffort, serviceTier }, { controlProtocol: 'native_tools' });
	await prewarmAgent.setGoalRevision(1);
	const prewarmStartedAt = performance.now();
	const warming = prewarmAgent.prewarm({ goalRevision: 1 });
	let takeoverFirstToolAt = null;
	const takeoverCalls = [];
	const takeoverResult = await prewarmAgent.act('URGENT real event replacing initialization: Lucas sent a DM saying "hi". Call say exactly once with a short friendly reply, then end this turn.', {
		goalRevision: 1,
		executeTool: async (request) => {
			takeoverFirstToolAt ??= performance.now();
			takeoverCalls.push({ atMs: Math.round(performance.now() - prewarmStartedAt), tool: request.tool });
			return { state: 'SUCCEEDED', delivered: true };
		},
	});
	await warming;
	const prewarmTakeover = {
		firstToolMs: takeoverFirstToolAt === null ? null : Math.round(takeoverFirstToolAt - prewarmStartedAt),
		totalMs: Math.round(performance.now() - prewarmStartedAt),
		calls: takeoverCalls,
		result: takeoverResult,
	};
	const runTurn = async (text) => {
		const turnStartedAt = performance.now();
		let firstToolAt = null;
		const calls = [];
		const result = await agent.act(`event: Lucas sent a DM saying ${JSON.stringify(text)}. Call say exactly once with a short friendly reply, then end this turn.`, {
			goalRevision: 1,
			executeTool: async (request) => {
				firstToolAt ??= performance.now();
				calls.push({ atMs: Math.round(performance.now() - turnStartedAt), tool: request.tool });
				return { state: 'SUCCEEDED', delivered: true };
			},
		});
		return {
			firstToolMs: firstToolAt === null ? null : Math.round(firstToolAt - turnStartedAt),
			totalMs: Math.round(performance.now() - turnStartedAt),
			calls,
			result,
		};
	};
	const coldTurn = await runTurn('hi');
	const warmTurn = await runTurn('how are you?');
	const chainStartedAt = performance.now();
	let chainFirstToolAt = null;
	const chainCalls = [];
	const chainResult = await agent.act('event: active goal is to mine the known stone block at x=2,y=64,z=1. You are at x=0,y=64,z=0. Call moveTo near it, use the successful result, then call mine on that exact block. Do not finish this goal in this probe.', {
		goalRevision: 1,
		executeTool: async (request) => {
			chainFirstToolAt ??= performance.now();
			chainCalls.push({ atMs: Math.round(performance.now() - chainStartedAt), tool: request.tool });
			return { state: 'SUCCEEDED', reasonCode: '', executionStarted: true };
		},
	});
	const chainTurn = {
		firstToolMs: chainFirstToolAt === null ? null : Math.round(chainFirstToolAt - chainStartedAt),
		totalMs: Math.round(performance.now() - chainStartedAt),
		calls: chainCalls,
		result: chainResult,
	};
	const advancedStartedAt = performance.now();
	let advancedFirstToolAt = null;
	const advancedCalls = [];
	const advancedResult = await agent.act('event: probe only. Call act exactly once with actionType craft_inventory and arguments recipeId minecraft:oak_planks, count 4, timeoutMs 15000. End this turn after the successful result.', {
		goalRevision: 1,
		executeTool: async (request) => {
			advancedFirstToolAt ??= performance.now();
			advancedCalls.push({ atMs: Math.round(performance.now() - advancedStartedAt), tool: request.tool });
			return { state: 'SUCCEEDED', reasonCode: '', executionStarted: true };
		},
	});
	const advancedTurn = {
		firstToolMs: advancedFirstToolAt === null ? null : Math.round(advancedFirstToolAt - advancedStartedAt),
		totalMs: Math.round(performance.now() - advancedStartedAt),
		calls: advancedCalls,
		result: advancedResult,
	};
	const deferred = Promise.withResolvers();
	const waitStarted = Promise.withResolvers();
	const activeSteerStartedAt = performance.now();
	let steerAcceptedAt = null;
	let steeredSayAt = null;
	const activeSteerCalls = [];
	const activeTurnPromise = agent.act('Call wait exactly once with durationMs 30000 to represent an ongoing body action. After its successful result, end the turn.', {
		goalRevision: 1,
		executeTool: async (request) => {
			activeSteerCalls.push({ atMs: Math.round(performance.now() - activeSteerStartedAt), tool: request.tool });
			if (request.tool.actionType === 'wait') {
				waitStarted.resolve();
				return deferred.promise;
			}
			if (request.tool.actionType === 'chat') steeredSayAt ??= performance.now();
			return { state: 'SUCCEEDED', delivered: true };
		},
	});
	await waitStarted.promise;
	const steerRequestedAt = performance.now();
	await agent.steer('URGENT DM from Lucas: "respond now". Keep the current body action running and call say exactly once with "I hear you" now.', { goalRevision: 1 });
	steerAcceptedAt = performance.now();
	const reactionBeforeBodyResult = await Promise.race([
		new Promise((resolve) => {
			const poll = setInterval(() => {
				if (steeredSayAt !== null) {
					clearInterval(poll);
					resolve(true);
				}
			}, 10);
			setTimeout(() => { clearInterval(poll); resolve(false); }, 10_000);
		}),
	]);
	deferred.resolve({ state: 'SUCCEEDED', executionStarted: true });
	const activeSteerResult = await activeTurnPromise;
	const activeSteer = {
		steerAckMs: Math.round(steerAcceptedAt - steerRequestedAt),
		reactionBeforeBodyResult,
		reactionMs: steeredSayAt === null ? null : Math.round(steeredSayAt - steerRequestedAt),
		totalMs: Math.round(performance.now() - activeSteerStartedAt),
		calls: activeSteerCalls,
		result: activeSteerResult,
	};
	return {
		status: 'PASSED',
		profile: { model, reasoningEffort, serviceTier },
		initializationMs: Math.round(readyAt - startedAt),
		totalMs: Math.round(performance.now() - startedAt),
		prewarmTakeover,
		coldTurn,
		warmTurn,
		chainTurn,
		advancedTurn,
		activeSteer,
	};
} finally {
	await service.stop();
}
}

process.exitCode = await runNativeToolCli(main);
