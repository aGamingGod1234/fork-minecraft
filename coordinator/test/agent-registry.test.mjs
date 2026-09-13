import assert from 'node:assert/strict';
import test from 'node:test';

import { AgentRegistry, AgentRegistryError, DynamicAgentState, decodeAgentRegistrySnapshot, encodeAgentRegistrySnapshot, normalizeAgentRecord } from '../src/agent-registry.mjs';
import { goalSpecFingerprint } from '../src/goal-spec.mjs';

function record(agentId, overrides = {}) {
	return {
		agentId,
		provider: 'codex',
		model: 'gpt-5.6-sol',
		reasoningEffort: 'high',
		serviceTier: 'priority',
		state: DynamicAgentState.IDLE,
		goalRevision: 0,
		queue: [],
		...overrides,
	};
}

test('registry dynamically adds, snapshots, and removes agents without exposing mutable records', () => {
	const registry = new AgentRegistry({ agentCap: 2 });
	const added = registry.register(record('agent-a'));
	added.model = 'mutated';
	assert.equal(registry.get('agent-a').model, 'gpt-5.6-sol');
	registry.register(record('agent-b'));
	assert.throws(() => registry.register(record('agent-c')), (error) => error instanceof AgentRegistryError && error.code === 'AGENT_CAP_REACHED');
	assert.equal(registry.remove('agent-a').agentId, 'agent-a');
	assert.equal(registry.remove('agent-a'), null);
});

test('goal controls enforce monotonic revisions, queue bounds, and FIFO promotion', () => {
	const registry = new AgentRegistry({ queueCap: 2 });
	registry.register(record('agent-a'));
	registry.applyGoalControl('agent-a', { operation: 'start', goalRevision: 1, goal: 'Build shelter.' });
	registry.applyGoalControl('agent-a', { operation: 'queue', goalRevision: 1, goal: 'Find food.' });
	registry.applyGoalControl('agent-a', { operation: 'queue', goalRevision: 1, goal: 'Plant wheat.' });
	assert.throws(
		() => registry.applyGoalControl('agent-a', { operation: 'queue', goalRevision: 1, goal: 'Mine iron.' }),
		(error) => error.code === 'GOAL_QUEUE_FULL',
	);
	const satisfied = registry.applyGoalControl('agent-a', { operation: 'complete', goalRevision: 2 });
	assert.equal(satisfied.currentGoal, 'Build shelter.');
	assert.equal(satisfied.state, DynamicAgentState.COMPLETED);
	const promoted = registry.applyGoalControl('agent-a', { operation: 'start', goalRevision: 3, goal: 'Find food.' });
	assert.equal(promoted.currentGoal, 'Find food.');
	assert.equal(promoted.queue[0].goal, 'Plant wheat.');
	assert.equal(promoted.state, DynamicAgentState.STARTING);
	assert.throws(
		() => registry.applyGoalControl('agent-a', { operation: 'stop', goalRevision: 3 }),
		(error) => error.code === 'STALE_GOAL_REVISION',
	);
});

test('server start promotions consume exactly the queued head through exhaustion', () => {
	const registry = new AgentRegistry({ queueCap: 2 });
	registry.register(record('agent-a'));
	registry.applyGoalControl('agent-a', { operation: 'start', goalRevision: 1, goal: 'A' });
	registry.applyGoalControl('agent-a', { operation: 'queue', goalRevision: 1, goal: 'B' });
	registry.applyGoalControl('agent-a', { operation: 'queue', goalRevision: 1, goal: 'C' });

	registry.setState('agent-a', DynamicAgentState.PLANNING, { goalRevision: 1 });
	registry.setState('agent-a', DynamicAgentState.ACTING, { goalRevision: 1 });
	const promotedB = registry.applyGoalControl('agent-a', { operation: 'start', goalRevision: 2, goal: 'B' });
	assert.equal(promotedB.currentGoal, 'B');
	assert.deepEqual(promotedB.queue.map((entry) => entry.goal), ['C']);

	registry.setState('agent-a', DynamicAgentState.PLANNING, { goalRevision: 2 });
	registry.setState('agent-a', DynamicAgentState.ACTING, { goalRevision: 2 });
	const promotedC = registry.applyGoalControl('agent-a', { operation: 'start', goalRevision: 3, goal: 'C' });
	assert.equal(promotedC.currentGoal, 'C');
	assert.deepEqual(promotedC.queue, []);

	registry.setState('agent-a', DynamicAgentState.PLANNING, { goalRevision: 3 });
	registry.setState('agent-a', DynamicAgentState.ACTING, { goalRevision: 3 });
	const exhausted = registry.applyGoalControl('agent-a', { operation: 'complete', goalRevision: 4 });
	assert.equal(exhausted.currentGoal, 'C');
	assert.deepEqual(exhausted.queue, []);
	assert.equal(exhausted.state, DynamicAgentState.COMPLETED);
});

test('server promotion after coordinator completion consumes the queued head', () => {
	const registry = new AgentRegistry({ queueCap: 2 });
	registry.register(record('agent-a'));
	registry.applyGoalControl('agent-a', { operation: 'start', goalRevision: 1, goal: 'A' });
	registry.applyGoalControl('agent-a', { operation: 'queue', goalRevision: 1, goal: 'B' });
	registry.applyGoalControl('agent-a', { operation: 'queue', goalRevision: 1, goal: 'C' });
	registry.setState('agent-a', DynamicAgentState.PLANNING, { goalRevision: 1 });
	registry.setState('agent-a', DynamicAgentState.ACTING, { goalRevision: 1 });
	registry.setState('agent-a', DynamicAgentState.COMPLETED, { goalRevision: 1 });

	const promoted = registry.applyGoalControl('agent-a', { operation: 'start', goalRevision: 2, goal: 'B' });
	assert.equal(promoted.state, DynamicAgentState.STARTING);
	assert.equal(promoted.currentGoal, 'B');
	assert.deepEqual(promoted.queue.map((entry) => entry.goal), ['C']);
});

test('server queue rejections synchronize each exact head before promoting later work', () => {
	const registry = new AgentRegistry({ queueCap: 2 });
	const removedFields = { originalRequest: 'Removed block goal', predicate: { type: 'operator_confirmed' }, createdAtTick: 2 };
	const removedSpec = { ...removedFields, fingerprint: goalSpecFingerprint(removedFields) };
	const laterFields = { originalRequest: 'C', predicate: { type: 'operator_confirmed' }, createdAtTick: 3 };
	const laterSpec = { ...laterFields, fingerprint: goalSpecFingerprint(laterFields) };
	registry.register(record('agent-a'));
	registry.applyGoalControl('agent-a', { operation: 'start', goalRevision: 1, goal: 'A' });
	registry.applyGoalControl('agent-a', { operation: 'queue', goalRevision: 1, goal: 'Removed block goal', goalSpec: removedSpec });
	registry.applyGoalControl('agent-a', { operation: 'queue', goalRevision: 1, goal: 'C', goalSpec: laterSpec });
	registry.setState('agent-a', DynamicAgentState.COMPLETED, { goalRevision: 1 });

	assert.throws(
		() => registry.applyGoalControl('agent-a', {
			operation: 'dequeue', goalRevision: 1, goal: 'C', goalSpec: laterSpec, updatedAtEpochMs: 4,
		}),
		(error) => error instanceof AgentRegistryError && error.code === 'QUEUED_GOAL_MISMATCH',
	);
	assert.throws(
		() => registry.applyGoalControl('agent-a', {
			operation: 'dequeue', goalRevision: 1, goal: 'Removed block goal', goalSpec: laterSpec, updatedAtEpochMs: 4,
		}),
		(error) => error instanceof AgentRegistryError && error.code === 'QUEUED_GOAL_MISMATCH',
	);
	const rejected = registry.applyGoalControl('agent-a', {
		operation: 'dequeue', goalRevision: 1, goal: 'Removed block goal', goalSpec: removedSpec, updatedAtEpochMs: 5,
	});
	assert.equal(rejected.currentGoal, 'A');
	assert.equal(rejected.state, DynamicAgentState.COMPLETED);
	assert.equal(rejected.goalRevision, 1);
	assert.deepEqual(rejected.queue.map((entry) => entry.goal), ['C']);

	const promoted = registry.applyGoalControl('agent-a', {
		operation: 'start', goalRevision: 2, goal: 'C', updatedAtEpochMs: 6,
	});
	assert.equal(promoted.currentGoal, 'C');
	assert.deepEqual(promoted.queue, []);
});

test('replace installs a new authoritative goal without consuming queued work', () => {
	const registry = new AgentRegistry({ queueCap: 2 });
	registry.register(record('agent-a'));
	registry.applyGoalControl('agent-a', { operation: 'start', goalRevision: 1, goal: 'Old goal.' });
	registry.applyGoalControl('agent-a', { operation: 'queue', goalRevision: 1, goal: 'Queued goal.' });
	const replacementFields = {
		originalRequest: 'New goal.',
		predicate: { type: 'operator_confirmed' },
		createdAtTick: 2,
	};
	const replacementSpec = { ...replacementFields, fingerprint: goalSpecFingerprint(replacementFields) };
	const replaced = registry.applyGoalControl('agent-a', {
		operation: 'replace', goalRevision: 2, goal: 'New goal.', goalSpec: replacementSpec,
	});
	assert.equal(replaced.currentGoal, 'New goal.');
	assert.deepEqual(replaced.currentGoalSpec, replacementSpec);
	assert.deepEqual(replaced.queue.map((entry) => entry.goal), ['Queued goal.']);
	assert.equal(replaced.state, DynamicAgentState.STARTING);
});

test('a goal may complete during planning without fabricating a physical action', () => {
	const registry = new AgentRegistry();
	registry.register(record('agent-a'));
	registry.applyGoalControl('agent-a', { operation: 'start', goalRevision: 1, goal: 'Inspect current facts' });
	registry.setState('agent-a', DynamicAgentState.PLANNING, { goalRevision: 1 });
	registry.setState('agent-a', DynamicAgentState.COMPLETED, { goalRevision: 1 });
	assert.equal(registry.get('agent-a').state, DynamicAgentState.COMPLETED);
});

test('server promotion after coordinator completion consumes the queued head', () => {
	const registry = new AgentRegistry({ queueCap: 2 });
	registry.register(record('agent-a'));
	registry.applyGoalControl('agent-a', { operation: 'start', goalRevision: 1, goal: 'A' });
	registry.applyGoalControl('agent-a', { operation: 'queue', goalRevision: 1, goal: 'B' });
	registry.applyGoalControl('agent-a', { operation: 'queue', goalRevision: 1, goal: 'C' });
	registry.setState('agent-a', DynamicAgentState.PLANNING, { goalRevision: 1 });
	registry.setState('agent-a', DynamicAgentState.ACTING, { goalRevision: 1 });
	registry.setState('agent-a', DynamicAgentState.COMPLETED, { goalRevision: 1 });

	const promoted = registry.applyGoalControl('agent-a', { operation: 'start', goalRevision: 2, goal: 'B' });
	assert.equal(promoted.state, DynamicAgentState.STARTING);
	assert.equal(promoted.currentGoal, 'B');
	assert.deepEqual(promoted.queue.map((entry) => entry.goal), ['C']);
});

test('a goal may complete during planning without fabricating a physical action', () => {
	const registry = new AgentRegistry();
	registry.register(record('agent-a'));
	registry.applyGoalControl('agent-a', { operation: 'start', goalRevision: 1, goal: 'Inspect current facts' });
	registry.setState('agent-a', DynamicAgentState.PLANNING, { goalRevision: 1 });
	registry.setState('agent-a', DynamicAgentState.COMPLETED, { goalRevision: 1 });
	assert.equal(registry.get('agent-a').state, DynamicAgentState.COMPLETED);
});

test('server start promotions reject a goal that is not the queued head', () => {
	const registry = new AgentRegistry({ queueCap: 2 });
	registry.register(record('agent-a'));
	registry.applyGoalControl('agent-a', { operation: 'start', goalRevision: 1, goal: 'A' });
	registry.applyGoalControl('agent-a', { operation: 'queue', goalRevision: 1, goal: 'B' });
	registry.applyGoalControl('agent-a', { operation: 'queue', goalRevision: 1, goal: 'C' });
	registry.setState('agent-a', DynamicAgentState.PLANNING, { goalRevision: 1 });
	registry.setState('agent-a', DynamicAgentState.ACTING, { goalRevision: 1 });

	assert.throws(
		() => registry.applyGoalControl('agent-a', { operation: 'start', goalRevision: 2, goal: 'C' }),
		(error) => error instanceof AgentRegistryError && error.code === 'PROMOTED_GOAL_MISMATCH',
	);
});

test('reconciliation disconnects in-flight persisted agents, preserves explicit pauses, and rejects duplicate identities', () => {
	const registry = new AgentRegistry();
	const result = registry.reconcile([
		record('agent-a', { state: DynamicAgentState.STARTING, currentGoal: 'Start.', goalRevision: 5 }),
		record('agent-b', { state: DynamicAgentState.PLANNING, currentGoal: 'Plan.', goalRevision: 6 }),
		record('agent-c', { state: DynamicAgentState.ACTING, currentGoal: 'Act.', goalRevision: 7 }),
		record('agent-d', { state: DynamicAgentState.DISCONNECTED, currentGoal: 'Reconnect.', goalRevision: 8 }),
		record('agent-e', { state: DynamicAgentState.PAUSED, currentGoal: 'Pause.', goalRevision: 9 }),
	]);
	assert.deepEqual(result.added, ['agent-a', 'agent-b', 'agent-c', 'agent-d', 'agent-e']);
	assert.deepEqual(
		registry.list().map((agent) => agent.state),
		[
			DynamicAgentState.DISCONNECTED,
			DynamicAgentState.DISCONNECTED,
			DynamicAgentState.DISCONNECTED,
			DynamicAgentState.DISCONNECTED,
			DynamicAgentState.PAUSED,
		],
	);
	assert.throws(() => registry.reconcile([record('agent-a'), record('agent-a')]), (error) => error.code === 'DUPLICATE_AGENT');
});

test('recovery reconciliation re-arms active goals without changing revisions or exact profiles', () => {
	const registry = new AgentRegistry({ now: () => 99 });
	const snapshot = [
		record('agent-a', { state: DynamicAgentState.STARTING, currentGoal: 'Start.', goalRevision: 5, provider: 'kimi', model: 'kimi-code/k3', reasoningEffort: 'max', serviceTier: 'priority' }),
		record('agent-b', { state: DynamicAgentState.PLANNING, currentGoal: 'Plan.', goalRevision: 6 }),
		record('agent-c', { state: DynamicAgentState.ACTING, currentGoal: 'Act.', goalRevision: 7 }),
		record('agent-d', { state: DynamicAgentState.DISCONNECTED, currentGoal: 'Reconnect.', goalRevision: 8 }),
		record('agent-e', { state: DynamicAgentState.PAUSED, currentGoal: 'Pause.', goalRevision: 9 }),
	];

	registry.reconcile(snapshot, { recovery: true });
	assert.deepEqual(registry.list().map(({ state }) => state), [
		DynamicAgentState.STARTING,
		DynamicAgentState.STARTING,
		DynamicAgentState.STARTING,
		DynamicAgentState.STARTING,
		DynamicAgentState.PAUSED,
	]);
	assert.deepEqual(
		pickRecoveryIdentity(registry.get('agent-a')),
		{ currentGoal: 'Start.', goalRevision: 5, provider: 'kimi', model: 'kimi-code/k3', reasoningEffort: 'max', serviceTier: 'priority' },
	);
	const first = registry.snapshot();
	registry.reconcile(first, { recovery: true });
	assert.deepEqual(registry.snapshot(), first, 'duplicate recovery observations converge on the same records');
});

test('recovery reconciliation rejects a profile with no provider', () => {
	const registry = new AgentRegistry();
	const missingProvider = record('agent-a', { state: DynamicAgentState.STARTING, currentGoal: 'Recover.', goalRevision: 1 });
	delete missingProvider.provider;

	assert.throws(() => registry.reconcile([missingProvider], { recovery: true }), /provider/i);
});

test('recovery reconciliation rejects a profile with no service tier', () => {
	const registry = new AgentRegistry();
	const missingServiceTier = record('agent-a', { state: DynamicAgentState.STARTING, currentGoal: 'Recover.', goalRevision: 1 });
	delete missingServiceTier.serviceTier;

	assert.throws(() => registry.reconcile([missingServiceTier], { recovery: true }), /serviceTier/i);
});

function pickRecoveryIdentity(value) {
	return {
		currentGoal: value.currentGoal,
		goalRevision: value.goalRevision,
		provider: value.provider,
		model: value.model,
		reasoningEffort: value.reasoningEffort,
		serviceTier: value.serviceTier,
	};
}

test('stop cleanup is idempotent while new commands still require newer revisions', () => {
	const registry = new AgentRegistry();
	registry.register(record('agent-a', { state: DynamicAgentState.ACTING, currentGoal: 'Explore.', goalRevision: 1 }));
	const stopped = registry.applyGoalControl('agent-a', { operation: 'stop', goalRevision: 2 });
	const repeated = registry.applyGoalControl('agent-a', { operation: 'stop', goalRevision: 2 });
	assert.deepEqual(repeated, stopped);
	assert.throws(() => registry.applyGoalControl('agent-a', { operation: 'resume', goalRevision: 2 }), (error) => error.code === 'STALE_GOAL_REVISION');
});

test('runtime state changes reject illegal lifecycle transitions', () => {
	const registry = new AgentRegistry({ now: () => 99 });
	registry.register(record('agent-a'));
	assert.throws(
		() => registry.applyGoalControl('agent-a', { operation: 'stop', goalRevision: 1 }),
		/PAUSED agents require a current goal/,
	);
	assert.equal(registry.get('agent-a').state, DynamicAgentState.IDLE);
	assert.throws(() => registry.setState('agent-a', DynamicAgentState.ACTING, { goalRevision: 0 }), (error) => error.code === 'ILLEGAL_STATE_TRANSITION');
	registry.applyGoalControl('agent-a', { operation: 'start', goalRevision: 1, goal: 'Explore.', updatedAtEpochMs: 1 });
	const planning = registry.setState('agent-a', DynamicAgentState.PLANNING, { goalRevision: 1 });
	assert.equal(planning.updatedAtEpochMs, 99);
});

test('record normalization excludes transient coordinator handles', () => {
	const normalized = normalizeAgentRecord({ ...record('agent-a'), socket: {}, threadId: 'thread-secret', activeTurnId: 'turn-secret' });
	assert.equal(Object.hasOwn(normalized, 'socket'), false);
	assert.equal(Object.hasOwn(normalized, 'threadId'), false);
	assert.equal(Object.hasOwn(normalized, 'activeTurnId'), false);
});

test('dead record normalization preserves every authoritative vanilla respawn fact', () => {
	const death = {
		cause: 'fell from a high place', dimensionId: 'minecraft:the_nether', x: 12.5, y: 64, z: -3.5,
		respawnDimensionId: 'minecraft:overworld', respawnX: 100.5, respawnY: 70, respawnZ: -20.5,
		respawnYaw: 37.5, respawnPitch: -12.25, respawnForced: true, gameMode: 'spectator', diedAtEpochMs: 2_000,
	};
	const normalized = normalizeAgentRecord(record('agent-a', {
		state: DynamicAgentState.DEAD, currentGoal: 'Survive.', goalRevision: 2, death,
	}));
	assert.deepEqual(normalized.death, death);
	assert.throws(
		() => normalizeAgentRecord(record('agent-a', {
			state: DynamicAgentState.DEAD, currentGoal: 'Survive.', goalRevision: 2,
			death: { ...death, respawnX: null },
		})),
		/present together/,
	);
});

test('respawn clears death and resumes only an explicitly requested live goal', () => {
	const death = {
		cause: 'fell from a high place', dimensionId: 'minecraft:overworld', x: 0, y: 64, z: 0,
		respawnDimensionId: 'minecraft:overworld', respawnX: 10, respawnY: 70, respawnZ: 10,
		respawnYaw: 0, respawnPitch: 0, respawnForced: false, gameMode: 'spectator', diedAtEpochMs: 2,
	};
	const paused = new AgentRegistry();
	paused.register(record('paused', { state: DynamicAgentState.DEAD, currentGoal: 'Keep building.', goalRevision: 2, death }));
	const pausedRespawn = paused.applyGoalControl('paused', { operation: 'respawn', goalRevision: 2 });
	assert.equal(pausedRespawn.state, DynamicAgentState.PAUSED);
	assert.equal(pausedRespawn.death, null);

	const resumed = new AgentRegistry();
	resumed.register(record('resumed', { state: DynamicAgentState.DEAD, currentGoal: 'Keep building.', goalRevision: 2, death }));
	const resumedRespawn = resumed.applyGoalControl('resumed', { operation: 'respawn', goalRevision: 2, resumeGoal: true });
	assert.equal(resumedRespawn.state, DynamicAgentState.STARTING);
	assert.equal(resumedRespawn.currentGoal, 'Keep building.');
	assert.equal(resumedRespawn.death, null);

	const idle = new AgentRegistry();
	idle.register(record('idle', { state: DynamicAgentState.DEAD, currentGoal: null, goalRevision: 2, death }));
	assert.equal(idle.applyGoalControl('idle', { operation: 'respawn', goalRevision: 2, resumeGoal: true }).state, DynamicAgentState.IDLE);
});

test('respawn rejects every current state except DEAD', () => {
	for (const state of [DynamicAgentState.PAUSED, DynamicAgentState.ERROR, DynamicAgentState.STARTING]) {
		const registry = new AgentRegistry();
		registry.register(record(state.toLowerCase(), {
			state,
			currentGoal: 'Keep building.',
			goalRevision: 2,
			...(state === DynamicAgentState.ERROR ? { lastError: { code: 'FAILED', message: 'failed' } } : {}),
		}));
		assert.throws(
			() => registry.applyGoalControl(state.toLowerCase(), { operation: 'respawn', goalRevision: 3, resumeGoal: true }),
			(error) => error instanceof AgentRegistryError && error.code === 'INVALID_GOAL_CONTROL',
			`${state} must reject respawn`,
		);
		assert.equal(registry.get(state.toLowerCase()).state, state);
		assert.equal(registry.get(state.toLowerCase()).goalRevision, 2);
	}
});

test('registry persistence codec is deterministic and disconnects active work on reload', () => {
	const encoded = encodeAgentRegistrySnapshot([
		record('agent-b'),
		record('agent-a', { state: DynamicAgentState.PLANNING, currentGoal: 'Build.', goalRevision: 2 }),
	]);
	const decoded = decodeAgentRegistrySnapshot(encoded);
	assert.deepEqual(decoded.map((entry) => entry.agentId), ['agent-a', 'agent-b']);
	assert.equal(decoded[0].state, DynamicAgentState.DISCONNECTED);
	assert.equal(decoded[0].provider, 'codex');
});

test('legacy registry snapshots migrate to the Codex provider while explicit providers round-trip', () => {
	const legacy = JSON.stringify({ schemaVersion: 1, agents: [record('legacy', { provider: undefined })] });
	assert.equal(decodeAgentRegistrySnapshot(legacy)[0].provider, 'codex');
	const encoded = encodeAgentRegistrySnapshot([record('kimi', { provider: 'kimi', model: 'kimi-code/k3', reasoningEffort: 'max' })]);
	assert.equal(decodeAgentRegistrySnapshot(encoded)[0].provider, 'kimi');
	const cursor = encodeAgentRegistrySnapshot([record('cursor', { provider: 'cursor', model: 'composer-2.5', reasoningEffort: 'high' })]);
	assert.equal(decodeAgentRegistrySnapshot(cursor)[0].provider, 'cursor');
	assert.throws(() => normalizeAgentRecord(record('bad', { provider: 'unknown' })), /provider/i);
});

test('registry goal text uses the shared 4096 UTF-16 code-unit contract', () => {
	const boundary = '\u{1f642}'.repeat(2_048);
	assert.equal(normalizeAgentRecord(record('agent-a', {
		state: DynamicAgentState.PAUSED,
		currentGoal: boundary,
		goalRevision: 1,
	})).currentGoal.length, 4_096);
	assert.throws(() => normalizeAgentRecord(record('agent-a', {
		state: DynamicAgentState.PAUSED,
		currentGoal: `${boundary}\u{1f642}`,
		goalRevision: 1,
	})), /4096/);
});

test('registered agent normalization enforces the shared Java state and identity contract', () => {
	assert.throws(() => normalizeAgentRecord(record('schema', { schemaVersion: 2 })), /schemaVersion must be 1/);
	assert.throws(() => normalizeAgentRecord(record('active', { state: DynamicAgentState.ACTING })), /ACTING agents require a current goal/);
	assert.throws(() => normalizeAgentRecord(record('idle', { currentGoal: 'Impossible.' })), /IDLE agents cannot have a current goal/);
	assert.throws(() => normalizeAgentRecord(record('time', { createdAtEpochMs: 5, updatedAtEpochMs: 4 })), /timestamps are invalid/);
	assert.equal(normalizeAgentRecord(record('legacy-defaults')).createdAtEpochMs, 1);
});
