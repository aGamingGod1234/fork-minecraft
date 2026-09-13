import os from 'node:os';
import path from 'node:path';
import { mkdir, readFile } from 'node:fs/promises';

import { runNativeToolCli } from './native-tool-cli-boundary.mjs';

async function main() {
const [model = 'gpt-5.6-luna', reasoningEffort = 'low', serviceTier = 'fast', agentCountValue = '16', includeSamplesValue = 'false'] = process.argv.slice(2);
const agentCount = Number(agentCountValue);
if (!Number.isSafeInteger(agentCount) || agentCount < 1 || agentCount > 16) throw new TypeError('agentCount must be an integer from 1 to 16');
const includeSamples = includeSamplesValue === 'true';

const [{ AgentWorkspaceManager }, { CodexService }, { PlanningScheduler }] = await Promise.all([
	import('./agent-workspace.mjs'),
	import('./codex-service.mjs'),
	import('./planning-scheduler.mjs'),
]);

const runIdentity = `${Date.now()}-${process.pid}`;
const probeRoot = path.join(os.tmpdir(), 'arena-native-load-probe', runIdentity);
await mkdir(probeRoot, { recursive: true });
const service = new CodexService({
	cwd: probeRoot,
	planningTimeoutMs: 180_000,
	serviceTier,
	launchProfile: { model, reasoningEffort, serviceTier, cwd: probeRoot },
}, {
	workspaceManager: new AgentWorkspaceManager(path.join(probeRoot, 'workspaces')),
});
const productionConfig = JSON.parse(await readFile(new URL('../config/dynamic-agents.json', import.meta.url), 'utf8'));
const scheduler = new PlanningScheduler({
	maxConcurrent: productionConfig.limits.planningConcurrency,
	maxPending: Math.max(0, productionConfig.limits.agentCap - productionConfig.limits.planningConcurrency),
	planningMode: productionConfig.limits.planningMode,
	urgentReserve: productionConfig.limits.urgentReserve,
});

const startedAt = performance.now();
try {
	await service.start();
	const agents = await Promise.all(Array.from({ length: agentCount }, async (_, index) => {
		const agent = await service.createAgent({
			agentId: `native-load-${String(index + 1).padStart(2, '0')}`,
			provider: 'codex', model, reasoningEffort, serviceTier,
		}, { controlProtocol: 'native_tools' });
		await agent.setGoalRevision(1);
		return agent;
	}));
	const threadsReadyAt = performance.now();
	await Promise.all(agents.map((agent) => agent.prewarm({ goalRevision: 1 })));
	const readyAt = performance.now();

	const runPhase = async (name, input, expectedTools, priority = 'ordinary') => {
		const phaseStartedAt = performance.now();
		const samples = await Promise.all(agents.map((agent) => scheduler.schedule(agent.agentId, async () => {
			const turnStartedAt = performance.now();
			const calls = [];
			const result = await agent.act(input, {
				goalRevision: 1,
				executeTool: async (request) => {
					const tool = request.tool.kind === 'sequence'
						? `sequence:${request.tool.actions.map((action) => action.actionType).join(',')}`
						: request.tool.actionType ?? request.tool.kind;
					calls.push({ tool, atMs: Math.round(performance.now() - turnStartedAt) });
					return { state: 'SUCCEEDED', reasonCode: '', executionStarted: true };
				},
			});
			const tools = calls.map((call) => call.tool);
			return {
				agentId: agent.agentId,
				firstToolMs: calls[0]?.atMs ?? null,
				lastToolMs: calls.at(-1)?.atMs ?? null,
				totalMs: Math.round(performance.now() - turnStartedAt),
				toolCount: result.toolCalls,
				tools,
				passed: JSON.stringify(tools) === JSON.stringify(expectedTools),
			};
		}, { lane: 'codex', priority })));
		return {
			name,
			wallMs: Math.round(performance.now() - phaseStartedAt),
			passed: samples.filter((sample) => sample.passed).length,
			total: samples.length,
			firstToolMs: summarize(samples.map((sample) => sample.firstToolMs)),
			lastToolMs: summarize(samples.map((sample) => sample.lastToolMs)),
			totalMs: summarize(samples.map((sample) => sample.totalMs)),
			...(includeSamples ? { samples } : {}),
		};
	};

	const firstDm = await runPhase(
		'first_dm_after_prewarm',
		'event: Lucas sent a DM saying "hi". Call say exactly once with a two-word friendly reply, then end this turn.',
		['chat'],
		'urgent',
	);
	const warmDm = await runPhase(
		'warm_dm',
		'event: Lucas sent a DM saying "ready?". Call say exactly once with a two-word reply, then end this turn.',
		['chat'],
		'urgent',
	);
	const moveThenMine = await runPhase(
		'move_then_mine',
		'event: probe only. You are at x=0,y=64,z=0 and a stone block is at x=2,y=64,z=1. Call moveTo near it, use the successful result, then call mine on that exact block. End this turn after mine succeeds.',
		['navigate_to', 'break_block'],
	);
	const sequenceMoveThenMine = await runPhase(
		'sequence_move_then_mine',
		'event: probe only. You are at x=0,y=64,z=0 and an observed minecraft:stone block is at x=2,y=64,z=1. Call sequence exactly once with navigate_to near the block followed by break_block on that exact block with expectedBlockId minecraft:stone. End this turn after the sequence result.',
		['sequence:navigate_to,break_block'],
	);

	return {
		status: firstDm.passed === agentCount && warmDm.passed === agentCount && moveThenMine.passed === agentCount && sequenceMoveThenMine.passed === agentCount ? 'PASSED' : 'FAILED',
		profile: { model, reasoningEffort, serviceTier },
		agentCount,
		scheduler: { planningConcurrency: scheduler.maxConcurrent, urgentReserve: scheduler.urgentReserve },
		threadInitializationMs: Math.round(threadsReadyAt - startedAt),
		prewarmMs: Math.round(readyAt - threadsReadyAt),
		initializationMs: Math.round(readyAt - startedAt),
		totalMs: Math.round(performance.now() - startedAt),
		phases: { firstDm, warmDm, moveThenMine, sequenceMoveThenMine },
	};
} finally {
	scheduler.close();
	await service.stop();
}

function summarize(values) {
	const sorted = values.filter(Number.isFinite).sort((left, right) => left - right);
	if (sorted.length === 0) return { p50: null, p95: null, max: null };
	return { p50: percentile(sorted, 0.5), p95: percentile(sorted, 0.95), max: sorted.at(-1) };
}

function percentile(sorted, fraction) {
	return sorted[Math.max(0, Math.ceil(sorted.length * fraction) - 1)];
}
}

process.exitCode = await runNativeToolCli(main);
