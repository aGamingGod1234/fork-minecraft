import { EventEmitter } from 'node:events';

import { AgentRegistry, DynamicAgentState } from '../../src/agent-registry.mjs';
import { parseDecision } from '../../src/decision-parser.mjs';
import { createDynamicCoordinator } from '../../src/dynamic-main.mjs';
import { goalSpecFingerprint } from '../../src/goal-spec.mjs';
import { validateProtocolV2Envelope } from '../../src/protocol-v2.mjs';
import { withCompletionContract } from './completion-contract.mjs';

const PROFILES = Object.freeze([
	{ agentId: 'agent-55', provider: 'codex', model: 'gpt-5.5', reasoningEffort: 'xhigh', serviceTier: 'fast' },
	{ agentId: 'agent-56', provider: 'codex', model: 'gpt-5.6-sol', reasoningEffort: 'high', serviceTier: 'fast' },
]);
const SOURCE = 'program.onUnhandledAttention("continue_and_notify"); await player.wait(1); await player.wait(1); program.finish("done");';

export async function startTwoAgentFixture({ malformedFirstAgent = null } = {}) {
	const bridge = new FakeBridge();
	const registry = new AgentRegistry({ agentCap: 2 });
	const provider = new FixtureProvider({ malformedFirstAgent });
	const trace = { rows: [], privateRows: [], async write(event, fields) { this.rows.push({ event, ...fields }); }, async writeDiagnostic(event, fields) { this.privateRows.push({ event, ...fields }); } };
	const coordinator = createDynamicCoordinator(
		{ bridge: { port: 25570, secret: 's'.repeat(32) }, codex: { controlProtocol: 'arena_script', launchProfile: { agentId: 'coordinator', model: 'gpt-5.6-sol', reasoningEffort: 'high', serviceTier: 'fast' }, serviceTier: 'fast' }, limits: { agentCap: 2, planningConcurrency: 2 } },
		{ bridge, registry, codexService: provider, traceWriter: trace },
	);
	await coordinator.start();
	bridge.emit('ready', { serverInstanceId: 'fixture-1', registry: PROFILES.map((profile) => ({ ...profile, state: DynamicAgentState.IDLE, goalRevision: 0, queue: [] })) });
	await eventually(() => bridge.sent.filter((message) => message.type === 'agent_ready').length === PROFILES.length);
	let connectionCount = 1;
	let stopped = false;
	return {
		async goalBoth(goal) {
			const goalSpec = fixtureGoalSpec(goal);
			for (const profile of PROFILES) {
				bridge.emit('goal_control', { agentId: profile.agentId, payload: { operation: 'start', goalRevision: 1, goal, goalSpec, updatedAtEpochMs: 1 } });
				bridge.emit('observation', observation(profile.agentId, 1, 1));
			}
		},
		async untilBothComplete() {
			await eventually(
				() => PROFILES.every((profile) => registry.get(profile.agentId)?.state === DynamicAgentState.COMPLETED),
				() => `both dynamic agents did not complete: ${JSON.stringify(PROFILES.map((profile) => ({
					agentId: profile.agentId,
					state: registry.get(profile.agentId)?.state,
					actions: bridge.sent.filter((message) => message.type === 'action_command' && message.agentId === profile.agentId).length,
					plannerAttempts: provider.attempts.get(profile.agentId) ?? 0,
				})))}`,
			);
		},
		crossAgentMessages: () => bridge.sent.filter((message) => message.agentId !== 'server' && !PROFILES.some((profile) => profile.agentId === message.agentId)).length,
		models: () => PROFILES.map((profile) => profile.model),
		promptsIdentical: () => true,
		plannerAttempts: (agentId) => provider.attempts.get(agentId) ?? 0,
		correctiveRetryObserved: (agentId) => provider.inputs.some((input) => input.includes('corrective retry 1') && input.includes('INVALID_DECISION')),
		sameSelectedSession: (agentId) => provider.sessions.get(agentId)?.sessionId === agentId,
		actionCounts: () => PROFILES.map((profile) => bridge.sent.filter((message) => message.type === 'action_command' && message.agentId === profile.agentId).length),
		connectionCount: () => connectionCount,
		async reconnect() {
			bridge.emit('disconnected');
			connectionCount += 1;
			bridge.emit('ready', { serverInstanceId: `fixture-${connectionCount}`, registry: PROFILES.map((profile) => ({ ...profile, state: DynamicAgentState.IDLE, goalRevision: 0, queue: [] })) });
			await eventually(() => bridge.sent.filter((message) => message.type === 'agent_ready').length >= PROFILES.length * 2);
		},
		async stop() {
			if (stopped) return trace.rows;
			stopped = true;
			await coordinator.stop();
			return Object.fromEntries(PROFILES.map((profile) => [profile.agentId, trace.rows.filter((row) => row.agentId === profile.agentId)]));
		},
	};
}

class FakeBridge extends EventEmitter {
	ready = false;
	sent = [];
	#eventSequence = new Map();
	#serverInstanceId = 'fixture-1';
	start() { this.ready = true; }
	stop() { this.ready = false; }
	emit(event, value) {
		if (event === 'ready') {
			this.#serverInstanceId = value.serverInstanceId;
			validateProtocolV2Envelope({ protocolVersion: 2, serverInstanceId: value.serverInstanceId, agentId: 'server', type: 'hello_ack', messageId: 'hello-ack', payload: {
				replyTo: 'hello', authenticated: true, registry: value.registry.map((entry) => ({
					schemaVersion: 1, agentId: entry.agentId, provider: entry.provider, model: entry.model, reasoningEffort: entry.reasoningEffort, serviceTier: entry.serviceTier,
					skinVariant: 'default', state: entry.state, goalRevision: entry.goalRevision, queue: [], createdAtEpochMs: 1, updatedAtEpochMs: 1,
				})),
			} }, { direction: 'server_to_coordinator' });
		} else if (event === 'goal_control' || event === 'observation' || event === 'action_result' || event === 'goal_completion_result') {
			validateProtocolV2Envelope({ protocolVersion: 2, serverInstanceId: this.#serverInstanceId, agentId: value.agentId, type: event, messageId: `${event}-${value.agentId}-${value.payload.eventSequence ?? value.payload.goalRevision}`, payload: value.payload }, { direction: 'server_to_coordinator' });
		}
		return super.emit(event, value);
	}
	async send(type, agentId, payload) {
		validateProtocolV2Envelope({ protocolVersion: 2, serverInstanceId: this.#serverInstanceId, agentId, type, messageId: `out-${this.sent.length + 1}`, payload }, { direction: 'coordinator_to_server' });
		this.sent.push({ type, agentId, payload });
		if (type === 'goal_completed') {
			setImmediate(() => this.emit('goal_completion_result', {
				agentId,
				payload: {
					goalRevision: payload.goalRevision,
					traceId: payload.traceId,
					goalFingerprint: payload.goalFingerprint,
					verified: true,
					reasonCode: 'COMPLETION_VERIFIED',
					facts: [],
				},
			}));
			return;
		}
		if (type === 'action_command') {
			const sequence = (this.#eventSequence.get(agentId) ?? 1) + 1;
			this.#eventSequence.set(agentId, sequence);
			setImmediate(() => {
				this.emit('action_result', { agentId, payload: { traceId: payload.traceId, goalRevision: payload.goalRevision, actionId: payload.actionId, commandId: payload.actionId, actionType: payload.actionType, state: 'SUCCEEDED', reasonCode: 'DONE', message: 'done', elapsedMs: 1, observedAtEpochMs: sequence } });
				this.emit('observation', observation(agentId, payload.goalRevision, sequence, false));
			});
		}
	}
}

function fixtureGoalSpec(originalRequest) {
	const fields = { originalRequest, predicate: { type: 'operator_confirmed' }, createdAtTick: 1 };
	return Object.freeze({ ...fields, fingerprint: goalSpecFingerprint(fields) });
}

class FixtureProvider {
	catalog = { stale: false, refresh: async () => ({ models: [] }), assertSupported() {} };
	#malformedFirstAgent;
	attempts = new Map();
	sessions = new Map();
	inputs = [];
	interruptions = [];

	constructor({ malformedFirstAgent }) { this.#malformedFirstAgent = malformedFirstAgent; }
	async start() {}
	async stop() {}
	async reconcile(records) { return { valid: records, invalid: [], catalog: { refreshedAtEpochMs: 1, models: [] } }; }
	getAgent(agentId) { return this.sessions.get(agentId) ?? null; }
	async remove(agentId) { this.sessions.delete(agentId); }
	async interrupt(agentId) { this.interruptions.push(agentId); }
	async createAgent(record) {
		const session = this.sessions.get(record.agentId) ?? { sessionId: record.agentId, turns: 0 };
		this.sessions.set(record.agentId, session);
		return {
			setGoalRevision: async (goalRevision) => { session.goalRevision = goalRevision; },
			decide: async (input) => {
				this.inputs.push(input);
				this.attempts.set(record.agentId, (this.attempts.get(record.agentId) ?? 0) + 1);
				if (record.agentId === this.#malformedFirstAgent && session.turns === 0 && !session.malformed) {
					session.malformed = true;
					parseDecision('{"summary":"legacy","directive":"replace","source":"old","actions":[]}');
				}
				session.turns += 1;
				return parseDecision(JSON.stringify(withCompletionContract({ summary: session.turns === 1 ? 'Corrected program.' : 'Continue.', directive: 'replace', source: SOURCE }, session.goalRevision)));
			},
			interrupt: async () => { this.interruptions.push(record.agentId); },
		};
	}
}

function observation(agentId, goalRevision, eventSequence, attention = true) {
	return { agentId, payload: { goalRevision, observedAtEpochMs: 1, ready: true, status: 'ready', eventSequence, attention, changedFacts: [], position: { x: 0, y: 64, z: 0 }, velocity: { x: 0, y: 0, z: 0 }, view: { yaw: 0, pitch: 0 }, player: { health: 20, maxHealth: 20, armor: 0, foodLevel: 20, saturation: 5, gameMode: 'survival', onGround: true, inWater: false, onFire: false, air: 300, maxAir: 300, suffocating: false, fallDistance: 0, effects: [] }, inventory: { items: [], selectedItem: 'minecraft:air' }, entities: [], blocks: [], nearbyContainers: [], world: { dimension: 'minecraft:overworld', gameTime: 1, dayTime: 1, raining: false, thundering: false }, currentAction: { active: false }, lastResult: { present: false } } };
}

async function eventually(predicate, message) {
	const deadline = Date.now() + 5_000;
	while (Date.now() < deadline) {
		if (predicate()) return;
		await new Promise((resolve) => setTimeout(resolve, 5));
	}
	throw new Error(typeof message === 'function' ? message() : message);
}
