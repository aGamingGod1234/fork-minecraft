import { createDynamicCoordinator } from '../../src/dynamic-main.mjs';
import { ActiveGoalSupervisor } from '../../src/active-goal-supervisor.mjs';
import { AgentRegistry, DynamicAgentState } from '../../src/agent-registry.mjs';
import { goalSpecFingerprint } from '../../src/goal-spec.mjs';
import { profileFingerprint } from '../../src/provider-session.mjs';
import { FaultInjectingMinecraftBridge } from './fake-minecraft-bridge.mjs';

const AGENT_ID = 'native-fault-agent';
const DEFAULT_GOAL = 'gather wood and craft a wooden pickaxe';

class DeterministicGoalScheduler {
	#nextHandle = 0;
	#pending = new Map();
	#ready = [];

	scheduled = 0;
	fired = 0;
	maxPending = 0;
	usesRealTimers = false;
	scheduledDelays = [];
	firedDelays = [];

	schedule(callback, delayMs) {
		if (typeof callback !== 'function') throw new TypeError('goal schedule callback must be a function');
		const handle = { id: ++this.#nextHandle, cancelled: false };
		this.#pending.set(handle.id, { handle, callback, delayMs });
		this.scheduled += 1;
		this.scheduledDelays.push(delayMs);
		this.maxPending = Math.max(this.maxPending, this.#pending.size);
		return handle;
	}

	cancel(handle) {
		if (handle !== null && typeof handle === 'object') handle.cancelled = true;
		if (Number.isSafeInteger(handle)) this.#pending.delete(handle);
		else if (handle?.id !== undefined) this.#pending.delete(handle.id);
	}

	runNext() {
		const ready = this.#ready.shift();
		if (ready !== undefined) {
			if (ready.handle.cancelled) return false;
			this.fired += 1;
			this.firedDelays.push(ready.delayMs);
			ready.callback();
			return true;
		}
		const next = this.#pending.entries().next();
		if (next.done) return false;
		const [handle, entry] = next.value;
		this.#pending.delete(handle);
		if (!entry.handle.cancelled) this.#ready.push(entry);
		return false;
	}

	snapshot() {
		return {
			scheduled: this.scheduled,
			fired: this.fired,
			maxPending: this.maxPending,
			pending: this.#pending.size + this.#ready.filter(({ handle }) => !handle.cancelled).length,
			usesRealTimers: this.usesRealTimers,
			scheduledDelays: [...this.scheduledDelays],
			firedDelays: [...this.firedDelays],
		};
	}
}

export function codedError(code, message = code) {
	return Object.assign(new Error(message), { code });
}

export function woodenPickaxeFaultScenario() {
	return {
		goal: DEFAULT_GOAL,
		turns: [
			['mine'],
			codedError('PLANNING_TIMEOUT'),
			['observe'],
			['move_to'],
			['pick_up_item'],
			['craft_inventory'],
			['finish'],
		],
		actionResults: ['SUCCEEDED', 'PATH_BLOCKED', 'SUCCEEDED', 'SUCCEEDED', 'SUCCEEDED'],
		moveDropBeforePickup: true,
		disconnectAtAction: 3,
		completionResults: [true],
	};
}

export function createNativeGoalHarness(scenario = {}) {
	const normalized = normalizeScenario(scenario);
	const registry = new AgentRegistry({ agentCap: 1, queueCap: 4 });
	const provider = new ScriptedNativeProvider(normalized);
	const bridge = new FaultInjectingMinecraftBridge(normalized);
	const goalScheduler = new DeterministicGoalScheduler();
	const stuckScheduler = new DeterministicGoalScheduler();
	const goalSupervisor = new TrackingGoalSupervisor({
		requestObservation: ({ agentId, goalRevision }) => bridge.send('request_observation', agentId, { goalRevision }),
		clock: () => 1,
		schedule: goalScheduler.schedule.bind(goalScheduler),
		cancelSchedule: goalScheduler.cancel.bind(goalScheduler),
		stuckSchedule: stuckScheduler.schedule.bind(stuckScheduler),
		cancelStuckSchedule: stuckScheduler.cancel.bind(stuckScheduler),
	});
	provider.onTurnEnd = (turn) => {
		if (normalized.pauseAfterTurn === turn) bridge.pause();
	};
	const coordinator = createDynamicCoordinator(nativeConfig(), {
		memoryDirectory: null,
		bridge,
		registry,
		providerService: provider,
		setStatusInterval: () => null,
		clearStatusInterval: () => {},
		now: () => 1,
		controlNow: () => 1,
		epochNow: () => 1,
		plannerNow: () => 1,
		healthNow: () => 1,
		healthRegistry: {
			canAttempt: () => true,
			record: (value) => value,
			snapshot: (identity) => ({ ...identity, circuit: 'closed', count: 0, failureRate: 0, p50Ms: 0, p95Ms: 0 }),
			reset: () => {},
		},
		goalSupervisor,
	});
	const acceptedActionResults = [];
	const acceptedActionResultListener = (message) => acceptedActionResults.push({
		actionId: message.payload.actionId,
		connectionEpoch: message.connectionEpoch,
	});
	const runtimeErrorListener = (error) => bridge.recoveries.push(error?.code ?? 'RUNTIME_ERROR');
	coordinator.on('actionResult', acceptedActionResultListener);
	coordinator.on('runtimeError', runtimeErrorListener);
	return new NativeGoalHarness({ coordinator, registry, bridge, provider, scenario: normalized, goalScheduler, stuckScheduler, goalSupervisor, acceptedActionResults, acceptedActionResultListener, runtimeErrorListener });
}

export class NativeGoalHarness {
	#coordinator;
	#registry;
	#bridge;
	#provider;
	#scenario;
	#goalScheduler;
	#goalSupervisor;
	#stuckScheduler;
	#acceptedActionResults;
	#acceptedActionResultListener;
	#runtimeErrorListener;
	#maxListenerCount;
	#currentListenerCount = 0;
	#observedStates = [];

	constructor({ coordinator, registry, bridge, provider, scenario, goalScheduler, stuckScheduler, goalSupervisor, acceptedActionResults, acceptedActionResultListener, runtimeErrorListener }) {
		this.#coordinator = coordinator;
		this.#registry = registry;
		this.#bridge = bridge;
		this.#provider = provider;
		this.#scenario = scenario;
		this.#goalScheduler = goalScheduler;
		this.#goalSupervisor = goalSupervisor;
		this.#stuckScheduler = stuckScheduler;
		this.#acceptedActionResults = acceptedActionResults;
		this.#acceptedActionResultListener = acceptedActionResultListener;
		this.#runtimeErrorListener = runtimeErrorListener;
		this.#maxListenerCount = 0;
		this.#sampleListeners();
	}

	get bridge() { return this.#bridge; }
	get registry() { return this.#registry; }
	get provider() { return this.#provider; }
	get coordinator() { return this.#coordinator; }

	async run({ stopAfter = null } = {}) {
		await this.#coordinator.start();
		this.#sampleListeners();
		this.#bridge.start();
		this.#bridge.ready({ revision: 0, state: DynamicAgentState.IDLE });
		this.#sampleListeners();
		await eventually(() => this.#bridge.sent.some((entry) => entry.type === 'agent_ready'));
		await this.#bridge.startGoal(this.#scenario.goal, 1);
		this.#sampleListeners();
		const deadline = Date.now() + (this.#scenario.timeoutMs ?? 2_000);
		while (Date.now() < deadline) {
			await tick();
			this.#sampleListeners();
			if (this.#provider.activeWork === 0) this.#goalScheduler.runNext();
			const record = this.#registry.get(AGENT_ID);
			if (record?.state !== undefined) this.#observedStates.push(record.state);
			if (stopAfter === 'first-turn' && this.#provider.turns >= 1) break;
			if ([DynamicAgentState.COMPLETED, DynamicAgentState.PAUSED, DynamicAgentState.ERROR].includes(record?.state)) break;
			if (this.#provider.turns >= this.#scenario.turns.length && this.#scenario.stopWhenScriptExhausted) break;
		}
		await this.#coordinator.stop();
		this.#coordinator.off('actionResult', this.#acceptedActionResultListener);
		this.#coordinator.off('runtimeError', this.#runtimeErrorListener);
		this.#sampleListeners();
		const result = this.#result();
		return { ...result, activeWork: this.#provider.activeWork, recoveryHandles: this.#bridge.recoveryHandles };
	}

	#result() {
		const record = this.#registry.get(AGENT_ID);
		const states = [...this.#provider.states, ...this.#observedStates, record?.state].filter(Boolean);
		const replayedActionIds = new Set(this.#bridge.staleActionReplays.map(({ actionId }) => actionId));
		return {
			finalState: record?.state ?? null,
			states,
			sent: [...this.#bridge.sent],
			actionCount: this.#bridge.actionCount,
			providerTurns: this.#provider.turns,
			recoveries: [...new Set([...this.#bridge.recoveries, ...this.#provider.recoveries])],
			providerScripts: [...this.#provider.executedScripts],
			world: this.#bridge.world,
			goalScheduler: this.#goalScheduler.snapshot(),
			stuckScheduler: this.#stuckScheduler.snapshot(),
			maxRecoveryHandles: this.#bridge.maxRecoveryHandles,
			recoveryDispatches: this.#bridge.recoveryDispatches,
			staleDispatches: this.#acceptedActionResults.filter(({ actionId }) => replayedActionIds.has(actionId)).length,
			actionDispatches: this.#bridge.actionDispatches,
			actionEffects: this.#bridge.actionEffects,
			actionAttempts: this.#bridge.actionAttempts,
			deliveredActionResults: this.#bridge.deliveredActionResults,
			acceptedActionResults: [...this.#acceptedActionResults],
			staleActionReplays: this.#bridge.staleActionReplays,
			connectionEpoch: this.#bridge.connectionEpoch,
			maxConcurrentPhysicalActions: this.#bridge.maxConcurrentPhysicalActions,
			providerSessions: this.#provider.sessionStats(),
			profile: this.#registry.get(AGENT_ID),
			leaseStats: this.#goalSupervisor.stats(),
			listenerStats: { current: this.#currentListenerCount, maximum: this.#maxListenerCount },
			recoveryCycles: this.#provider.recoveryCycles,
			inventory: this.#bridge.inventory,
			completionEvaluations: this.#bridge.completionEvaluations,
			goalControls: this.#bridge.goalControls,
			goalSpec: this.#bridge.goalSpec,
		};
	}

	#sampleListeners() {
		this.#currentListenerCount = emitterListenerCount(this.#bridge) + emitterListenerCount(this.#coordinator);
		this.#maxListenerCount = Math.max(this.#maxListenerCount, this.#currentListenerCount);
	}
}

class ScriptedNativeProvider {
	catalog = {
		stale: false,
		refresh: async () => ({ refreshedAtEpochMs: 1, models: [{ provider: 'codex', id: 'gpt-5.6-luna', model: 'gpt-5.6-luna', displayName: 'Fixture Luna', reasoningEfforts: ['xhigh'], serviceTiers: ['fast'] }] }),
		assertSupported() {},
	};
	turns = 0;
	recoveryCycles = 0;
	activeWork = 0;
	maxActiveWork = 0;
	maxRecoveryHandles = 0;
	states = [];
	recoveries = [];
	executedScripts = [];
	onTurnEnd = null;
	#scenario;
	#session = null;
	#sessionProfile = null;
	#lastSessionProfile = null;
	#createdSessions = 0;
	#currentSessions = 0;
	#maxCurrentSessions = 0;

	constructor(scenario) { this.#scenario = scenario; }
	async start() {}
	async stop() { this.#session = null; this.#sessionProfile = null; this.#currentSessions = 0; }
	async bootstrapCatalog() { return this.catalog.refresh(); }
	async reconcile(records) { return { valid: records, invalid: [], catalog: await this.catalog.refresh() }; }
	getAgent() { return this.#session; }
	async remove() { this.#session = null; return true; }
	async interrupt() {}

	async createAgent(record) {
		if (this.#scenario.unsupportedProfile) throw codedError('MODEL_UNAVAILABLE', 'fixture profile is unavailable');
		if (this.#session !== null) {
			if (!sameProfile(this.#sessionProfile, record)) throw codedError('AGENT_PROFILE_CONFLICT');
			return this.#session;
		}
		const provider = this;
		this.#sessionProfile = profileSnapshot(record);
		this.#lastSessionProfile = this.#sessionProfile;
		this.#createdSessions += 1;
		this.#currentSessions += 1;
		this.#maxCurrentSessions = Math.max(this.#maxCurrentSessions, this.#currentSessions);
		this.#session = {
			sessionGeneration: this.#createdSessions,
			sessionMetadata: () => ({ profileFingerprint: profileFingerprint(provider.#sessionProfile), sessionGeneration: provider.#createdSessions }),
			async setGoalRevision(goalRevision) { provider.goalRevision = goalRevision; },
			async act(_input, { executeTool }) {
				provider.turns += 1;
				provider.activeWork += 1;
				provider.maxActiveWork = Math.max(provider.maxActiveWork, provider.activeWork);
				const script = provider.#scenario.turns[provider.turns - 1];
				provider.executedScripts.push(script instanceof Error ? script.code : script);
				try {
					if (script instanceof Error) {
						provider.recoveryCycles += 1;
						provider.recoveries.push(script.code);
						throw script;
					}
					let toolCalls = 0;
					for (const action of script ?? []) {
						const request = nativeRequest(record, provider.goalRevision, provider.turns, toolCalls + 1, action);
						toolCalls += 1;
						const result = await executeTool(request);
						if (result?.state !== undefined && result.state !== 'SUCCEEDED' && result.state !== 'COMPLETED') break;
					}
					return { status: 'completed', toolCalls };
				} finally {
					provider.activeWork = Math.max(0, provider.activeWork - 1);
					provider.onTurnEnd?.(provider.turns);
				}
			},
			async interrupt() {},
		};
		return this.#session;
	}

	sessionStats() {
		return Object.freeze({
			created: this.#createdSessions,
			current: this.#currentSessions,
			maxCurrent: this.#maxCurrentSessions,
			profile: this.#lastSessionProfile === null ? null : { ...this.#lastSessionProfile },
			turnsStarted: this.turns,
			turnsPending: this.activeWork,
			maxTurnsPending: this.maxActiveWork,
		});
	}
}

class TrackingGoalSupervisor {
	#supervisor;
	#maxByKind = new Map();
	#maxTotal = 0;
	#keys = new Map();

	constructor(options) { this.#supervisor = new ActiveGoalSupervisor(options); }
	activate(key) { const result = this.#supervisor.activate(key); this.#remember(key); return result; }
	begin(key, kind) { const token = this.#supervisor.begin(key, kind); this.#remember(key); return token; }
	end(token, options) { const result = this.#supervisor.end(token, options); this.#remember(token); return result; }
	progress(token) { const result = this.#supervisor.progress(token); this.#remember(token); return result; }
	observed(key) { const result = this.#supervisor.observed(key); this.#remember(key); return result; }
	recover(key, details) { const result = this.#supervisor.recover(key, details); this.#remember(key); return result; }
	ensure(key) { const result = this.#supervisor.ensure(key); this.#remember(key); return result; }
	factualProgress(key, signature, details) { const result = this.#supervisor.factualProgress(key, signature, details); this.#remember(key); return result; }
	suspend(key) { const result = this.#supervisor.suspend(key); this.#remember(key); return result; }
	terminate(key) { const result = this.#supervisor.terminate(key); this.#keys.delete(key.agentId); return result; }
	snapshot(key) { return this.#supervisor.snapshot(key); }
	close() { this.#supervisor.close(); this.#keys.clear(); }
	stats() {
		const current = [...this.#keys.values()].map((key) => this.#supervisor.snapshot(key)).filter(Boolean);
		return Object.freeze({
			maxTotal: this.#maxTotal,
			maxByKind: Object.fromEntries(this.#maxByKind),
			pending: current.reduce((sum, snapshot) => sum + snapshot.leases.length, 0),
		});
	}
	#remember(key) {
		this.#keys.set(key.agentId, profileSnapshot(key));
		const snapshot = this.#supervisor.snapshot(key);
		if (snapshot === null) return;
		this.#maxTotal = Math.max(this.#maxTotal, snapshot.leases.length);
		for (const lease of snapshot.leases) {
			const count = snapshot.leases.filter(({ kind }) => kind === lease.kind).length;
			this.#maxByKind.set(lease.kind, Math.max(this.#maxByKind.get(lease.kind) ?? 0, count));
		}
	}
}

function profileSnapshot(value) {
	return { ...value };
}

function sameProfile(left, right) {
	return ['agentId', 'provider', 'model', 'reasoningEffort', 'serviceTier'].every((key) => left?.[key] === right?.[key]);
}

function emitterListenerCount(emitter) {
	return emitter.eventNames().reduce((sum, event) => sum + emitter.listenerCount(event), 0);
}

function nativeRequest(record, goalRevision, turn, call, action) {
	const callId = `call-${turn}-${call}`;
	const tool = typeof action === 'object' ? action : actionTool(action, goalRevision);
	return { agentId: record.agentId, goalRevision, turnId: `turn-${turn}`, callId, tool };
}

function actionTool(action, goalRevision = 1) {
	switch (action) {
		case 'observe': return { kind: 'observe' };
		case 'mine': return { kind: 'action', actionType: 'break_block', arguments: { x: 0, y: 64, z: 0, expectedBlockId: 'minecraft:stone', timeoutMs: 1_000 } };
		case 'move_to': return { kind: 'action', actionType: 'navigate_to', arguments: { x: 1, y: 64, z: 0, tolerance: 1, sprint: false, timeoutMs: 1_000 } };
		case 'pick_up_item': return { kind: 'action', actionType: 'pick_up_item', arguments: { targetSelector: '00000000-0000-4000-8000-000000000001' } };
		case 'craft_inventory': return { kind: 'action', actionType: 'craft_inventory', arguments: { recipeId: 'minecraft:wooden_pickaxe', count: 1, timeoutMs: 1_000 } };
		case 'respawn': return { kind: 'action', actionType: 'respawn', arguments: {} };
		case 'finish': return { kind: 'finish', summary: 'Factual inventory evidence is present.' };
		default: return { kind: 'observe' };
	}
}

export function immutableGoalSpec(originalRequest, predicate, createdAtTick = 1) {
	const fields = { originalRequest, predicate, createdAtTick };
	return { ...fields, fingerprint: goalSpecFingerprint(fields) };
}

function normalizeScenario(value) {
	return {
		goal: DEFAULT_GOAL,
		turns: [['observe'], ['finish']],
		stopWhenScriptExhausted: true,
		timeoutMs: 2_000,
		...value,
		turns: Array.isArray(value.turns) ? value.turns : [['observe'], ['finish']],
	};
}

function nativeConfig() {
	return {
		bridge: { port: 25570, secret: 'n'.repeat(32) },
		codex: {
			controlProtocol: 'native_tools',
			launchProfile: { agentId: 'coordinator', model: 'gpt-5.6-luna', reasoningEffort: 'xhigh', serviceTier: 'fast' },
			serviceTier: 'fast',
		},
		limits: { agentCap: 1, planningConcurrency: 1, goalQueueCap: 4 },
	};
}

async function tick() {
	await new Promise((resolve) => setImmediate(resolve));
}

async function eventually(predicate, timeoutMs = 1_000) {
	const deadline = Date.now() + timeoutMs;
	while (Date.now() < deadline) {
		if (predicate()) return;
		await tick();
	}
	throw new Error('native goal harness did not reach its ready boundary');
}
