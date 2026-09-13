import assert from 'node:assert/strict';
import test from 'node:test';
import { types as utilTypes } from 'node:util';

import { DynamicAgentState } from '../src/agent-registry.mjs';
import { BestEffortDiagnosticQueue } from '../src/best-effort-diagnostic-queue.mjs';
import { PlanningScheduler } from '../src/planning-scheduler.mjs';
import { ProviderService } from '../src/provider-service.mjs';
import { WorkLeaseSupervisor } from '../src/work-lease-supervisor.mjs';
import { createNativeGoalHarness } from './fixtures/native-goal-harness.mjs';

const PROFILE = Object.freeze({
	agentId: 'fault-agent',
	provider: 'codex',
	model: 'gpt-5.6-sol',
	reasoningEffort: 'high',
	serviceTier: 'fast',
});
const PROFILE_FINGERPRINT = `sha256:${'a'.repeat(64)}`;
const RESOURCE_NAMES = Object.freeze([
	'leases', 'sessions', 'actions', 'timers', 'promises', 'childProcesses', 'listeners',
]);
const TRUSTED_EVIDENCE = new WeakSet();

const scenarios = [
	['provider startup hang', providerStartupHang],
	['provider outage and deadline restoration', providerOutageAndRestoration],
	['ignored abort releases scheduler capacity', ignoredAbortReleasesCapacity],
	['disconnect fences an outstanding action replay', disconnectFencesOutstandingActionReplay],
	['diagnostic hang and rejection', diagnosticHangAndRejection],
	['exact profile session and lease preservation', exactProfileSessionAndLeasePreservation],
];

for (const [name, run] of scenarios) {
	test(`recovery fault matrix: ${name}`, async () => {
		const result = await run();
		assertCompleteEvidence(result);
		assert.equal(result.recovery.healthy, true, 'the preferred path returns healthy automatically');
		assert.equal(result.recovery.permanentLatch, false, 'the fault cannot create a permanent latch');
		assertNoDomainFailure(result.states);
		assertExactProfile(result.profile);
		assertBoundedResources(result.resources);
	});
}

test('recovery matrix evidence contract rejects omitted, null, and fabricated measurements', () => {
	assert.throws(() => assertCompleteEvidence({}), /recovery evidence is required/);
	const maliciousHelper = (category, value) => ({ kind: 'observed', category, value, source: 'forged helper measurement' });
	assert.throws(() => assertEvidenceEntry(maliciousHelper('promises', { pending: 0, maximum: 0 }), 'promises'), /trusted category-bound entry/);
	const genuinePromises = promiseEvidence(0, 0, 'genuine promise lifecycle sampler');
	assert.throws(() => assertEvidenceEntry(structuredClone(genuinePromises), 'promises'), /trusted category-bound entry/);
	assert.throws(() => assertEvidenceEntry(genuinePromises, 'timers'), /cannot reuse 'promises' evidence/);
	const mutableSession = { current: 0, maximum: 1, profile: { model: 'before' } };
	const immutableSession = sessionEvidence(mutableSession, 'mutable session snapshot sampler');
	mutableSession.profile.model = 'after';
	assert.equal(immutableSession.value.profile.model, 'before');
	assert.equal(Object.isFrozen(immutableSession.value.profile), true);
	let getterCalls = 0;
	const accessor = {};
	Object.defineProperty(accessor, 'pending', { enumerable: true, get() { getterCalls += 1; return 0; } });
	assert.throws(() => sessionEvidence(accessor, 'accessor rejection sampler'), /cannot contain accessors/);
	assert.equal(getterCalls, 0, 'evidence rejection never invokes an accessor');
	const cyclic = {};
	cyclic.self = cyclic;
	assert.throws(() => sessionEvidence(cyclic, 'cyclic rejection sampler'), (error) => error instanceof TypeError && /cannot contain cycles/.test(error.message));
	let proxyTrapCalls = 0;
	const proxy = new Proxy({}, {
		getPrototypeOf() { proxyTrapCalls += 1; throw new Error('proxy trap must not execute'); },
		ownKeys() { proxyTrapCalls += 1; throw new Error('proxy trap must not execute'); },
	});
	assert.throws(() => sessionEvidence(proxy, 'proxy rejection sampler'), (error) => error instanceof TypeError && /cannot contain proxies/.test(error.message));
	assert.equal(proxyTrapCalls, 0, 'proxy detection rejects before reflective traps');
	assert.throws(() => sessionEvidence(Object.create({ hostile: true }), 'prototype rejection sampler'), /plain arrays and records/);
	const invalid = {
		recovery: { healthy: true, permanentLatch: false, stateBefore: 'fault', stateAfter: 'ready', nextProbeAtEpochMs: null, attemptTimes: [], probeDeadlines: [], retryDelays: [], attemptCount: 0 },
		states: statesEvidence([], 'test registry sampler'),
		profile: notApplicable('profile', 'No profile is selected in this contract test.'),
		resources: Object.fromEntries(RESOURCE_NAMES.map((name) => [name, notApplicable(name, `${name} is deliberately absent from this evidence-contract-only fixture.`)])),
	};
	delete invalid.resources.timers;
	assert.throws(() => assertCompleteEvidence(invalid), /timers evidence is required/);
	invalid.resources.timers = { kind: 'observed', category: 'timers', value: null, source: 'malicious all-null fixture' };
	assert.throws(() => assertCompleteEvidence(invalid), /trusted category-bound entry/);
	invalid.resources.timers = { kind: 'notApplicable' };
	assert.throws(() => assertCompleteEvidence(invalid), /trusted category-bound entry/);
});

for (const order of ['startup-first', 'catalog-first']) {
	test(`provider startup ownership is deduplicated across concurrent catalog and startup callers (${order})`, async () => {
		const fixture = await overlappingProviderFailures(order);
		const { router, timers, startTimes, catalogTimes } = fixture;
		try {
			let recovery = router.recoverySnapshot().find(({ provider }) => provider === 'codex');
			assert.equal(recovery.boundary, 'startup');
			assert.equal(recovery.failureCode, 'SHARED_START_FAILURE');
			assert.equal(recovery.nextProbeAtEpochMs, 1_000);
			assert.equal(timers.peekNextDeadline(), recovery.nextProbeAtEpochMs);
			assert.equal(recovery.totalFailureCount, 1, 'one shared backend start publishes one failure');
			assert.deepEqual(recovery.boundaryFailureCounts, { startup: 1 });
			assert.deepEqual(startTimes, [0], 'both callers consume one physical provider start');
			assert.deepEqual(catalogTimes, [], 'catalog discovery cannot run after its shared startup failed');
			const staleFirstCallback = timers.peekNextCallback();

			timers.advanceTo(100);
			const laterStartup = await router.start(['codex']);
			assert.equal(laterStartup[0].reason?.code, 'LATER_START_FAILURE');
			recovery = router.recoverySnapshot().find(({ provider }) => provider === 'codex');
			assert.equal(recovery.boundary, 'startup');
			assert.equal(recovery.nextProbeAtEpochMs, 2_100);
			assert.equal(timers.peekNextDeadline(), recovery.nextProbeAtEpochMs);
			assert.equal(recovery.totalFailureCount, 2);
			assert.deepEqual(recovery.boundaryFailureCounts, { startup: 2 });
			await staleFirstCallback();
			assert.deepEqual(startTimes, [0, 100], 'a replaced startup deadline cannot launch a stale probe');

			timers.advanceTo(2_099);
			await flush();
			assert.deepEqual(startTimes, [0, 100], 'no probe runs before the advertised deadline');
			await timers.runNext();
			recovery = router.recoverySnapshot().find(({ provider }) => provider === 'codex');
			assert.equal(recovery.state, 'live');
			assert.equal(recovery.totalFailureCount, 0);
			assert.deepEqual(recovery.boundaryFailureCounts, {});
			assert.deepEqual(startTimes, [0, 100, 2_100], 'automatic recovery performs exactly one physical start at the deadline');
			assert.equal(timers.snapshot().pending, 0, 'successful startup clears the shared failure without a no-op follow-up probe');
		} finally {
			await router.stop();
		}
		assert.equal(timers.snapshot().pending, 0);
	});
}

test('catalog probe atomically restores a failed shared startup without a no-op startup interval', async () => {
	const timers = new ManualTimers();
	const services = providerServices();
	let startAttempts = 0;
	let rejectShared;
	let catalogAttempts = 0;
	services.codex.start = () => {
		startAttempts += 1;
		if (startAttempts === 1) return new Promise((_, reject) => { rejectShared = reject; });
	};
	services.codex.catalog.refresh = async () => {
		catalogAttempts += 1;
		return catalog('codex', PROFILE.model);
	};
	const router = new ProviderService(services, { operationTimeoutMs: 10_000, scheduleTimeout: timers.schedule, cancelTimeout: timers.cancel, now: () => timers.now });
	try {
		const startup = router.start(['codex']);
		const discovery = router.catalog.refresh({ providers: ['codex'] });
		await flush();
		rejectShared(Object.assign(new Error('shared failure'), { code: 'SHARED_START_FAILURE' }));
		await Promise.all([startup, discovery]);
		assert.equal(startAttempts, 1);
		assert.equal(router.recoverySnapshot().find(({ provider }) => provider === 'codex').totalFailureCount, 1);

		const restored = await router.catalog.refresh({ providers: ['codex'] });
		assert.equal(restored.source, 'live');
		assert.equal(startAttempts, 2, 'the catalog probe owns one restored physical startup');
		assert.equal(catalogAttempts, 1);
		assert.equal(router.recoverySnapshot().find(({ provider }) => provider === 'codex').state, 'live');
		assert.equal(timers.snapshot().pending, 0, 'startup and catalog success clear recovery in one probe');
	} finally {
		await router.stop();
	}
});

test('provider recovery stop fences a captured overlapping-boundary callback', async () => {
	const { router, timers, startTimes, catalogTimes } = await overlappingProviderFailures('startup-first');
	const staleCallback = timers.peekNextCallback();
	await router.stop();
	assert.equal(timers.snapshot().pending, 0);
	await staleCallback();
	assert.deepEqual(startTimes, [0]);
	assert.deepEqual(catalogTimes, []);
	assert.ok(router.recoverySnapshot().every(({ state }) => state === 'idle'));
});

test('non-automatic provider failures publish no fictional probe deadline or timer', async () => {
	const timers = new ManualTimers();
	const services = providerServices();
	let createAvailable = false;
	let catalogAvailable = false;
	let catalogAttempts = 0;
	services.codex.createAgent = async (profile) => {
		if (!createAvailable) throw Object.assign(new Error('create unavailable'), { code: 'CREATE_UNAVAILABLE' });
		return { profile };
	};
	services.codex.catalog.refresh = async () => {
		catalogAttempts += 1;
		if (!catalogAvailable) throw Object.assign(new Error('catalog unavailable'), { code: 'CATALOG_UNAVAILABLE' });
		return catalog('codex', PROFILE.model);
	};
	const router = new ProviderService(services, { operationTimeoutMs: 100, scheduleTimeout: timers.schedule, cancelTimeout: timers.cancel, now: () => timers.now });
	try {
		await assert.rejects(router.createAgent(PROFILE), (error) => error?.code === 'CREATE_UNAVAILABLE');
		let recovery = router.recoverySnapshot().find(({ provider }) => provider === 'codex');
		assert.equal(recovery.boundary, 'create');
		assert.equal(recovery.failureCode, 'CREATE_UNAVAILABLE');
		assert.equal(recovery.nextProbeAtEpochMs, null);
		assert.deepEqual(recovery.latestNonAutomaticFailure, { boundary: 'create', failureCode: 'CREATE_UNAVAILABLE', count: 1 });
		assert.equal(timers.snapshot().pending, 0, 'caller-owned create recovery does not claim an automatic timer');

		await router.catalog.refresh({ providers: ['codex'] });
		recovery = router.recoverySnapshot().find(({ provider }) => provider === 'codex');
		assert.equal(recovery.boundary, 'catalog', 'an automatic failure owns the primary status while mixed with nonautomatic diagnostics');
		assert.equal(recovery.nextProbeAtEpochMs, 1_000);
		assert.deepEqual(recovery.boundaryFailureCounts, { catalog: 1, create: 1 });
		assert.deepEqual(recovery.latestNonAutomaticFailure, { boundary: 'create', failureCode: 'CREATE_UNAVAILABLE', count: 1 });
		assert.equal(timers.peekNextDeadline(), recovery.nextProbeAtEpochMs);

		catalogAvailable = true;
		timers.advanceTo(999);
		await flush();
		assert.equal(catalogAttempts, 1);
		await timers.runNext();
		recovery = router.recoverySnapshot().find(({ provider }) => provider === 'codex');
		assert.equal(recovery.boundary, 'create');
		assert.equal(recovery.failureCode, 'CREATE_UNAVAILABLE');
		assert.equal(recovery.nextProbeAtEpochMs, null);
		assert.deepEqual(recovery.boundaryFailureCounts, { create: 1 });
		assert.deepEqual(recovery.latestNonAutomaticFailure, { boundary: 'create', failureCode: 'CREATE_UNAVAILABLE', count: 1 });
		assert.equal(timers.snapshot().pending, 0);

		createAvailable = true;
		await router.createAgent(PROFILE);
		recovery = router.recoverySnapshot().find(({ provider }) => provider === 'codex');
		assert.equal(recovery.state, 'live');
		assert.equal(recovery.latestNonAutomaticFailure, null);
	} finally {
		await router.stop();
	}
});

for (const operation of ['create', 'replace']) {
	for (const settlement of ['resolve', 'reject']) {
		test(`${operation} timeout fences old ${settlement} after a newer operation failure`, async () => {
			await assertNonAutomaticOutcomeFence({ operation, settlement });
		});
	}
}

async function assertNonAutomaticOutcomeFence({ operation, settlement }) {
	const timers = new ManualTimers();
	const services = providerServices();
	const initialAgent = { agentId: PROFILE.agentId, profile: PROFILE, sessionGeneration: 1 };
	let backendCurrent = null;
	let settleOld;
	let attempt = 0;
	if (operation === 'create') {
		services.codex.createAgent = async () => {
			attempt += 1;
			if (attempt === 2) throw Object.assign(new Error('newer create failure'), { code: 'SECOND_CREATE_FAILURE' });
			return new Promise((resolve, reject) => {
				settleOld = () => {
					if (settlement === 'reject') return reject(Object.assign(new Error('late create rejection'), { code: 'LATE_CREATE_REJECTION' }));
					backendCurrent = { agentId: PROFILE.agentId, profile: PROFILE, sessionGeneration: 99 };
					resolve(backendCurrent);
				};
			});
		};
	} else {
		services.codex.createAgent = async () => {
			backendCurrent = initialAgent;
			return initialAgent;
		};
		services.codex.replaceAgent = async () => {
			attempt += 1;
			if (attempt === 2) throw Object.assign(new Error('newer replace failure'), { code: 'SECOND_REPLACE_FAILURE' });
			return new Promise((resolve, reject) => {
				settleOld = () => {
					if (settlement === 'reject') return reject(Object.assign(new Error('late replace rejection'), { code: 'LATE_REPLACE_REJECTION' }));
					backendCurrent = { agentId: PROFILE.agentId, profile: PROFILE, sessionGeneration: 99 };
					resolve(backendCurrent);
				};
			});
		};
	}
	services.codex.getAgent = (agentId) => backendCurrent?.agentId === agentId ? backendCurrent : null;
	const router = new ProviderService(services, { operationTimeoutMs: 25, scheduleTimeout: timers.schedule, cancelTimeout: timers.cancel, now: () => timers.now });
	try {
		if (operation === 'replace') await router.createAgent(PROFILE);
		const first = operation === 'create' ? router.createAgent(PROFILE) : router.replaceAgent(PROFILE);
		const firstRejected = assert.rejects(first, (error) => error?.code === 'PROVIDER_TIMEOUT');
		await flush();
		await timers.runNext();
		await firstRejected;
		const second = operation === 'create' ? router.createAgent(PROFILE) : router.replaceAgent(PROFILE);
		await assert.rejects(second, (error) => error?.code === `SECOND_${operation.toUpperCase()}_FAILURE`);
		let recovery = router.recoverySnapshot().find(({ provider }) => provider === 'codex');
		assert.equal(recovery.failureCode, `SECOND_${operation.toUpperCase()}_FAILURE`);
		assert.equal(recovery.boundaryFailureCounts[operation], 2, 'timeout and newer physical failure are each counted once');
		assert.equal(recovery.nextProbeAtEpochMs, null);
		settleOld();
		await flush();
		recovery = router.recoverySnapshot().find(({ provider }) => provider === 'codex');
		assert.equal(recovery.failureCode, `SECOND_${operation.toUpperCase()}_FAILURE`, 'late old settlement cannot overwrite the newer failure');
		assert.equal(recovery.boundaryFailureCounts[operation], 2, 'late old settlement cannot count twice');
		assert.equal(router.getAgent(PROFILE.agentId), operation === 'replace' ? initialAgent : null, 'late old settlement cannot replace the accepted provider session');
	} finally {
		settleOld?.();
		await router.stop();
	}
}

test('existing-session create cannot publish after a newer replacement generation', async () => {
	const timers = new ManualTimers();
	const services = providerServices();
	let current = null;
	let createAttempt = 0;
	let releaseStaleCreate;
	let staleDisposals = 0;
	let displacedDisposals = 0;
	let finalDisposals = 0;
	let replacementGeneration = 2;
	services.codex.createAgent = async () => {
		createAttempt += 1;
		if (createAttempt === 1) return (current = mutationAgent(1));
		return new Promise((resolve) => {
			let released = false;
			releaseStaleCreate = () => {
				if (released) return;
				released = true;
				current = mutationAgent(2, () => { staleDisposals += 1; });
				resolve(current);
			};
		});
	};
	services.codex.replaceAgent = async () => {
		replacementGeneration += 1;
		current = mutationAgent(replacementGeneration, replacementGeneration === 3 ? () => { displacedDisposals += 1; } : () => { finalDisposals += 1; });
		return current;
	};
	services.codex.getAgent = (agentId) => current?.agentId === agentId ? current : null;
	services.codex.removeAgent = async (agentId) => {
		if (current?.agentId !== agentId) return false;
		const removed = current;
		current = null;
		await removed.dispose();
		return true;
	};
	services.codex.stop = async () => {
		const owned = current;
		current = null;
		await owned?.dispose();
	};
	const router = new ProviderService(services, { operationTimeoutMs: 25, scheduleTimeout: timers.schedule, cancelTimeout: timers.cancel, now: () => timers.now });
	try {
		await router.createAgent(PROFILE);
		const staleCreate = router.createAgent(PROFILE);
		const staleRejected = assert.rejects(staleCreate, (error) => error?.code === 'PROVIDER_TIMEOUT');
		const replacement = router.replaceAgent(PROFILE);
		await flush();
		await timers.runNext();
		await staleRejected;
		const generationThree = await replacement;
		assert.equal(generationThree.sessionGeneration, 3);
		assert.equal(router.recoverySnapshot().find(({ provider }) => provider === 'codex').state, 'live', 'successful replacement clears the older session-mutation timeout');
		releaseStaleCreate();
		await eventually(() => router.getAgent(PROFILE.agentId)?.sessionGeneration === 4);
		const repaired = router.getAgent(PROFILE.agentId);
		assert.equal(current, repaired, 'backend and wrapper converge on the exact repaired session');
		assert.equal(repaired.sessionGeneration, 4, 'physical repair advances monotonically beyond the displaced accepted generation');
		assert.equal(staleDisposals, 1, 'the stale produced session is disposed exactly once');
		assert.equal(displacedDisposals, 1, 'the displaced accepted generation is disposed after repair');
	} finally {
		releaseStaleCreate?.();
		await router.stop();
	}
	assert.equal(current, null);
	assert.equal(finalDisposals, 1, 'provider stop disposes the final repaired current session');
});

test('empty reconciliation fences a pending initial create and cleans its late session', async () => {
	const services = providerServices();
	let current = null;
	let releaseCreate;
	let disposals = 0;
	services.codex.createAgent = async () => new Promise((resolve) => {
		let released = false;
		releaseCreate = () => {
			if (released) return;
			released = true;
			current = mutationAgent(1, () => { disposals += 1; });
			resolve(current);
		};
	});
	services.codex.getAgent = (agentId) => current?.agentId === agentId ? current : null;
	services.codex.removeAgent = async (agentId) => {
		if (current?.agentId !== agentId) return false;
		const removed = current;
		current = null;
		await removed.dispose();
		return true;
	};
	services.codex.reconcile = async (records) => ({ valid: records, invalid: [], removed: [], catalog: catalog('codex', PROFILE.model) });
	const router = new ProviderService(services, { operationTimeoutMs: 100 });
	try {
		const create = router.createAgent(PROFILE);
		const createRejected = assert.rejects(create, (error) => error?.code === 'STALE_PROVIDER_OUTCOME');
		await flush();
		const reconcile = router.reconcile([]);
		await flush();
		releaseCreate();
		await createRejected;
		const result = await reconcile;
		assert.deepEqual(result.valid, []);
		assert.equal(router.getAgent(PROFILE.agentId), null);
		assert.equal(current, null);
		assert.equal(disposals, 1);
		assert.notEqual(router.recoverySnapshot().find(({ provider }) => provider === 'codex').state, 'degraded', 'stale physical creation cannot latch provider health');
	} finally {
		releaseCreate?.();
		await router.stop();
	}
});

for (const settlement of ['resolve', 'reject']) {
	test(`timed-out remove late ${settlement} repairs the newer accepted physical session`, async () => {
		await assertLateRemoveRepair(settlement);
	});
}

async function assertLateRemoveRepair(settlement) {
	const timers = new ManualTimers();
	const services = providerServices();
	let current = null;
	let generation = 0;
	let removeAttempt = 0;
	let settleOldRemove;
	let finalDisposals = 0;
	services.codex.createAgent = async () => {
		await current?.dispose();
		current = mutationAgent(++generation);
		return current;
	};
	services.codex.replaceAgent = async () => {
		await current?.dispose();
		current = mutationAgent(++generation, generation === 3 ? () => { finalDisposals += 1; } : undefined);
		return current;
	};
	services.codex.getAgent = (agentId) => current?.agentId === agentId ? current : null;
	services.codex.removeAgent = async (agentId) => {
		removeAttempt += 1;
		if (removeAttempt > 1) {
			if (current?.agentId !== agentId) return false;
			const removed = current;
			current = null;
			await removed.dispose();
			return true;
		}
		return new Promise((resolve, reject) => {
			let settled = false;
			settleOldRemove = async () => {
				if (settled) return;
				settled = true;
				const removed = current;
				current = null;
				await removed?.dispose();
				if (settlement === 'reject') reject(Object.assign(new Error('late remove failure'), { code: 'LATE_REMOVE_FAILURE' }));
				else resolve(true);
			};
		});
	};
	services.codex.stop = async () => {
		const owned = current;
		current = null;
		await owned?.dispose();
	};
	const router = new ProviderService(services, { operationTimeoutMs: 25, scheduleTimeout: timers.schedule, cancelTimeout: timers.cancel, now: () => timers.now });
	try {
		await router.createAgent(PROFILE);
		const oldRemove = router.removeAgent(PROFILE.agentId);
		const oldRejected = assert.rejects(oldRemove, (error) => error?.code === 'PROVIDER_TIMEOUT');
		await flush();
		await timers.runNext();
		await oldRejected;
		const generationTwo = await router.createAgent(PROFILE);
		assert.equal(generationTwo.sessionGeneration, 2);
		await settleOldRemove();
		await eventually(() => router.getAgent(PROFILE.agentId)?.sessionGeneration === 3);
		assert.equal(current, router.getAgent(PROFILE.agentId));
		assert.equal(current.sessionGeneration, 3);
		assert.equal(router.recoverySnapshot().find(({ provider }) => provider === 'codex').state, 'live');
	} finally {
		await settleOldRemove?.();
		await router.stop();
	}
	assert.equal(current, null);
	assert.equal(finalDisposals, 1, 'stop disposes the repaired final session');
}

for (const settlement of ['resolve', 'reject']) {
	test(`timed-out reconcile late ${settlement} repairs every partially mutated agent`, async () => {
		await assertLateReconcileRepair(settlement);
	});
}

async function assertLateReconcileRepair(settlement) {
	const timers = new ManualTimers();
	const services = providerServices();
	const profiles = [PROFILE, Object.freeze({ ...PROFILE, agentId: `${PROFILE.agentId}-b` })];
	const current = new Map();
	const generations = new Map();
	let settleOldReconcile;
	let reconcileAttempts = 0;
	services.codex.createAgent = async (profile) => {
		await current.get(profile.agentId)?.dispose();
		const generation = (generations.get(profile.agentId) ?? 0) + 1;
		generations.set(profile.agentId, generation);
		const agent = mutationAgentFor(profile, generation);
		current.set(profile.agentId, agent);
		return agent;
	};
	services.codex.replaceAgent = services.codex.createAgent;
	services.codex.getAgent = (agentId) => current.get(agentId) ?? null;
	services.codex.removeAgent = async (agentId) => {
		const agent = current.get(agentId);
		if (agent === undefined) return false;
		current.delete(agentId);
		await agent.dispose();
		return true;
	};
	services.codex.reconcile = async (records) => {
		reconcileAttempts += 1;
		if (reconcileAttempts > 1) return { valid: records, invalid: [], removed: [], catalog: catalog('codex', PROFILE.model) };
		return new Promise((resolve, reject) => {
			let settled = false;
			settleOldReconcile = async () => {
				if (settled) return;
				settled = true;
				const mutated = settlement === 'resolve' ? profiles : profiles.slice(0, 1);
				for (const profile of mutated) {
					const agent = current.get(profile.agentId);
					current.delete(profile.agentId);
					await agent?.dispose();
				}
				if (settlement === 'reject') reject(Object.assign(new Error('late partial reconcile failure'), { code: 'LATE_RECONCILE_FAILURE' }));
				else resolve({ valid: records, invalid: [], removed: [], catalog: catalog('codex', PROFILE.model) });
			};
		});
	};
	services.codex.stop = async () => {
		const agents = [...current.values()];
		current.clear();
		await Promise.all(agents.map((agent) => agent.dispose()));
	};
	const router = new ProviderService(services, { operationTimeoutMs: 25, scheduleTimeout: timers.schedule, cancelTimeout: timers.cancel, now: () => timers.now });
	try {
		for (const profile of profiles) await router.createAgent(profile);
		const oldReconcile = router.reconcile(profiles);
		await flush();
		await timers.runNext();
		await oldReconcile;
		for (const profile of profiles) assert.equal((await router.createAgent(profile)).sessionGeneration, 2);
		await settleOldReconcile();
		const repairedIds = settlement === 'resolve' ? profiles.map(({ agentId }) => agentId) : [profiles[0].agentId];
		await eventually(() => repairedIds.every((agentId) => router.getAgent(agentId)?.sessionGeneration === 3));
		for (const profile of profiles) {
			assert.equal(current.get(profile.agentId), router.getAgent(profile.agentId), `backend and wrapper converge for ${profile.agentId}`);
			assert.equal(router.getAgent(profile.agentId).sessionGeneration, repairedIds.includes(profile.agentId) ? 3 : 2);
		}
		assert.equal(router.recoverySnapshot().find(({ provider }) => provider === 'codex').state, 'live');
	} finally {
		await settleOldReconcile?.();
		await router.stop();
	}
	assert.equal(current.size, 0);
}

test('a newer terminal remove suppresses stale remove repair resurrection', async () => {
	const timers = new ManualTimers();
	const services = providerServices();
	let current = null;
	let generation = 0;
	let removeAttempt = 0;
	let settleOldRemove;
	let repairReplacements = 0;
	services.codex.createAgent = async () => (current = mutationAgent(++generation));
	services.codex.replaceAgent = async () => {
		repairReplacements += 1;
		return (current = mutationAgent(++generation));
	};
	services.codex.getAgent = (agentId) => current?.agentId === agentId ? current : null;
	services.codex.removeAgent = async (agentId) => {
		removeAttempt += 1;
		if (removeAttempt === 1) return new Promise((resolve) => { settleOldRemove = () => resolve(true); });
		if (current?.agentId !== agentId) return false;
		const removed = current;
		current = null;
		await removed.dispose();
		return true;
	};
	const router = new ProviderService(services, { operationTimeoutMs: 25, scheduleTimeout: timers.schedule, cancelTimeout: timers.cancel, now: () => timers.now });
	try {
		await router.createAgent(PROFILE);
		const stale = router.removeAgent(PROFILE.agentId);
		const staleRejected = assert.rejects(stale, (error) => error?.code === 'PROVIDER_TIMEOUT');
		await flush();
		await timers.runNext();
		await staleRejected;
		await router.createAgent(PROFILE);
		assert.equal(await router.removeAgent(PROFILE.agentId), true);
		settleOldRemove();
		await flush();
		assert.equal(router.getAgent(PROFILE.agentId), null);
		assert.equal(current, null);
		assert.equal(repairReplacements, 0, 'intentional terminal absence cannot trigger resurrection repair');
	} finally {
		settleOldRemove?.();
		await router.stop();
	}
});

function mutationAgentFor(profile, sessionGeneration, onDispose = () => {}) {
	let disposed = false;
	return {
		agentId: profile.agentId,
		profile,
		sessionGeneration,
		get disposed() { return disposed; },
		async dispose() {
			if (disposed) return;
			disposed = true;
			onDispose();
		},
	};
}

test('remove waits for a timed-out create and stale production cannot undo terminal cleanup', async () => {
	const timers = new ManualTimers();
	const services = providerServices();
	let current = null;
	let releaseStaleCreate;
	let staleDisposals = 0;
	let physicalRemovals = 0;
	services.codex.createAgent = async () => new Promise((resolve) => {
		let released = false;
		releaseStaleCreate = () => {
			if (released) return;
			released = true;
			current = mutationAgent(1, () => { staleDisposals += 1; });
			resolve(current);
		};
	});
	services.codex.getAgent = (agentId) => current?.agentId === agentId ? current : null;
	services.codex.removeAgent = async (agentId) => {
		physicalRemovals += 1;
		if (current?.agentId !== agentId) return false;
		const removed = current;
		current = null;
		await removed.dispose();
		return true;
	};
	const router = new ProviderService(services, { operationTimeoutMs: 25, scheduleTimeout: timers.schedule, cancelTimeout: timers.cancel, now: () => timers.now });
	try {
		const create = router.createAgent(PROFILE);
		const createRejected = assert.rejects(create, (error) => error?.code === 'PROVIDER_TIMEOUT');
		const remove = router.removeAgent(PROFILE.agentId);
		await flush();
		await timers.runNext();
		await createRejected;
		assert.equal(await remove, true, 'cleanup of an owned pending creation is idempotent success');
		assert.equal(router.recoverySnapshot().find(({ provider }) => provider === 'codex').state, 'live');
		assert.equal(physicalRemovals, 1);
		releaseStaleCreate();
		await flush();
		assert.equal(router.getAgent(PROFILE.agentId), null);
		assert.equal(staleDisposals, 1);
		assert.equal(physicalRemovals, 2, 'late physical production receives one exact stale-session cleanup');
	} finally {
		releaseStaleCreate?.();
		await router.stop();
	}
});

test('remove waits for a timed-out replacement and late replacement cannot recreate the session', async () => {
	const timers = new ManualTimers();
	const services = providerServices();
	let current = null;
	let releaseStaleReplace;
	let staleDisposals = 0;
	services.codex.createAgent = async () => (current = mutationAgent(1));
	services.codex.replaceAgent = async () => new Promise((resolve) => {
		let released = false;
		releaseStaleReplace = () => {
			if (released) return;
			released = true;
			current = mutationAgent(2, () => { staleDisposals += 1; });
			resolve(current);
		};
	});
	services.codex.getAgent = (agentId) => current?.agentId === agentId ? current : null;
	services.codex.removeAgent = async (agentId) => {
		if (current?.agentId !== agentId) return false;
		const removed = current;
		current = null;
		await removed.dispose();
		return true;
	};
	const router = new ProviderService(services, { operationTimeoutMs: 25, scheduleTimeout: timers.schedule, cancelTimeout: timers.cancel, now: () => timers.now });
	try {
		await router.createAgent(PROFILE);
		const replace = router.replaceAgent(PROFILE);
		const replaceRejected = assert.rejects(replace, (error) => error?.code === 'PROVIDER_TIMEOUT');
		const remove = router.removeAgent(PROFILE.agentId);
		await flush();
		await timers.runNext();
		await replaceRejected;
		assert.equal(await remove, true);
		assert.equal(router.recoverySnapshot().find(({ provider }) => provider === 'codex').state, 'live');
		releaseStaleReplace();
		await flush();
		assert.equal(router.getAgent(PROFILE.agentId), null);
		assert.equal(staleDisposals, 1);
	} finally {
		releaseStaleReplace?.();
		await router.stop();
	}
});

test('concurrent removal coalesces one physical cleanup and a new create runs afterward', async () => {
	const services = providerServices();
	const events = [];
	let current = null;
	let generation = 0;
	let releaseRemoval;
	let removalCalls = 0;
	services.codex.createAgent = async () => {
		events.push('create');
		current = mutationAgent(++generation);
		return current;
	};
	services.codex.getAgent = (agentId) => current?.agentId === agentId ? current : null;
	services.codex.removeAgent = async (agentId) => {
		removalCalls += 1;
		events.push('remove:start');
		await new Promise((resolve) => { releaseRemoval = resolve; });
		if (current?.agentId !== agentId) return false;
		const removed = current;
		current = null;
		await removed.dispose();
		events.push('remove:end');
		return true;
	};
	const router = new ProviderService(services);
	try {
		await router.createAgent(PROFILE);
		const first = router.removeAgent(PROFILE.agentId);
		const second = router.removeAgent(PROFILE.agentId);
		const recreated = router.createAgent(PROFILE);
		await flush();
		assert.equal(removalCalls, 1, 'duplicate remove calls share one backend operation');
		assert.deepEqual(events, ['create', 'remove:start']);
		releaseRemoval();
		assert.deepEqual(await Promise.all([first, second]), [true, true]);
		const agent = await recreated;
		assert.equal(agent.sessionGeneration, 2);
		assert.equal(removalCalls, 1);
		assert.deepEqual(events, ['create', 'remove:start', 'remove:end', 'create']);
		assert.equal(router.getAgent(PROFILE.agentId), agent);
	} finally {
		releaseRemoval?.();
		await router.stop();
	}
});

function mutationAgent(sessionGeneration, onDispose = () => {}) {
	let disposed = false;
	return {
		agentId: PROFILE.agentId,
		profile: PROFILE,
		sessionGeneration,
		async dispose() {
			if (disposed) return;
			disposed = true;
			onDispose();
		},
	};
}

for (const boundary of ['startup', 'catalog']) {
	for (const settlement of ['resolve', 'reject']) {
		test(`${boundary} timeout fences old ${settlement} after a newer successful recovery`, async () => {
			await assertTimedOutOutcomeFence({ boundary, settlement, settleBeforeRecovery: false });
		});
	}
	for (const settlement of ['resolve', 'reject']) {
		test(`${boundary} timeout observes an old ${settlement} before recovery without double-counting`, async () => {
			await assertTimedOutOutcomeFence({ boundary, settlement, settleBeforeRecovery: true });
		});
	}
}

async function assertTimedOutOutcomeFence({ boundary, settlement, settleBeforeRecovery }) {
	const timers = new ManualTimers();
	const services = providerServices();
	const attemptTimes = [];
	let settleOld;
	let attempt = 0;
	const controlled = () => {
		attempt += 1;
		attemptTimes.push(timers.now);
		if (attempt > 1) return Promise.resolve(boundary === 'catalog' ? catalog('codex', PROFILE.model) : undefined);
		return new Promise((resolve, reject) => {
			settleOld = () => settlement === 'resolve'
				? resolve(boundary === 'catalog' ? catalog('codex', 'stale-old-model') : undefined)
				: reject(Object.assign(new Error('late old failure'), { code: 'LATE_OLD_FAILURE' }));
		});
	};
	if (boundary === 'startup') services.codex.start = controlled;
	else services.codex.catalog.refresh = controlled;
	const router = new ProviderService(services, { operationTimeoutMs: 25, scheduleTimeout: timers.schedule, cancelTimeout: timers.cancel, now: () => timers.now });
	try {
		const first = boundary === 'startup' ? router.start(['codex']) : router.catalog.refresh({ providers: ['codex'] });
		await flush();
		await timers.runNext();
		await first;
		let recovery = router.recoverySnapshot().find(({ provider }) => provider === 'codex');
		assert.equal(recovery.state, 'degraded');
		assert.equal(recovery.boundary, boundary);
		assert.equal(recovery.failureCode, 'PROVIDER_TIMEOUT');
		assert.equal(recovery.totalFailureCount, 1, 'one physical timeout is counted exactly once');
		assert.equal(recovery.nextProbeAtEpochMs, 1_025);
		const deadline = recovery.nextProbeAtEpochMs;

		if (settleBeforeRecovery) {
			settleOld();
			await flush();
			recovery = router.recoverySnapshot().find(({ provider }) => provider === 'codex');
			assert.equal(recovery.totalFailureCount, 1);
			assert.equal(recovery.failureCode, 'PROVIDER_TIMEOUT');
			assert.equal(recovery.nextProbeAtEpochMs, deadline);
		}

		timers.advanceTo(deadline - 1);
		await flush();
		assert.deepEqual(attemptTimes, [0]);
		await timers.runNext();
		recovery = router.recoverySnapshot().find(({ provider }) => provider === 'codex');
		assert.equal(recovery.state, 'live');
		assert.equal(recovery.totalFailureCount, 0);
		assert.deepEqual(attemptTimes, [0, deadline]);

		if (!settleBeforeRecovery) {
			settleOld();
			await flush();
		}
		recovery = router.recoverySnapshot().find(({ provider }) => provider === 'codex');
		assert.equal(recovery.state, 'live');
		assert.equal(recovery.totalFailureCount, 0);
		assert.equal(recovery.nextProbeAtEpochMs, null);
		assert.equal(timers.snapshot().pending, 0);
		if (boundary === 'catalog' && settlement === 'resolve') {
			services.codex.catalog.refresh = async () => { throw Object.assign(new Error('fallback inspection'), { code: 'INSPECTION_FAILURE' }); };
			const fallback = await router.catalog.refresh({ providers: ['codex'] });
			assert.deepEqual(fallback.models.map(({ model }) => model), [PROFILE.model], 'late old catalog resolution cannot overwrite the newer retained catalog');
		}
	} finally {
		settleOld?.();
		await router.stop();
	}
}

async function overlappingProviderFailures(order) {
	const timers = new ManualTimers();
	const services = providerServices();
	const startTimes = [];
	const catalogTimes = [];
	let rejectShared;
	let startAttempt = 0;
	services.codex.start = () => {
		startAttempt += 1;
		startTimes.push(timers.now);
		if (startAttempt === 1) return new Promise((_, reject) => { rejectShared = reject; });
		if (startAttempt === 2) throw Object.assign(new Error('later startup failure'), { code: 'LATER_START_FAILURE' });
		return Promise.resolve();
	};
	services.codex.catalog.refresh = async () => {
		catalogTimes.push(timers.now);
		return catalog('codex', PROFILE.model);
	};
	const router = new ProviderService(services, { operationTimeoutMs: 10_000, scheduleTimeout: timers.schedule, cancelTimeout: timers.cancel, now: () => timers.now });
	const calls = {
		startup: () => router.start(['codex']),
		catalog: () => router.catalog.refresh({ providers: ['codex'] }),
	};
	const first = order === 'startup-first' ? calls.startup() : calls.catalog();
	const second = order === 'startup-first' ? calls.catalog() : calls.startup();
	await flush();
	rejectShared(Object.assign(new Error('shared startup failure'), { code: 'SHARED_START_FAILURE' }));
	await Promise.all([first, second]);
	return { router, timers, startTimes, catalogTimes };
}

async function providerStartupHang() {
	const timers = new ManualTimers();
	const services = providerServices();
	const attemptTimes = [];
	let pendingStarts = 0;
	let maxPendingStarts = 0;
	let releaseHung;
	services.codex.start = () => {
		attemptTimes.push(timers.now);
		pendingStarts += 1;
		maxPendingStarts = Math.max(maxPendingStarts, pendingStarts);
		if (attemptTimes.length === 1) {
			return new Promise((resolve) => {
				let released = false;
				releaseHung = () => {
					if (released) return;
					released = true;
					pendingStarts -= 1;
					resolve();
				};
			});
		}
		pendingStarts -= 1;
		return Promise.resolve();
	};
	const router = new ProviderService(services, {
		operationTimeoutMs: 25,
		scheduleTimeout: timers.schedule,
		cancelTimeout: timers.cancel,
		now: () => timers.now,
	});
	const listeners = new ListenerGauge(router);
	let session = null;
	let degraded;
	let restored;
	try {
		const first = router.start(['codex']);
		await flush();
		await timers.runNext();
		const firstOutcome = await first;
		assert.equal(firstOutcome[0].reason?.code, 'PROVIDER_TIMEOUT');
		degraded = router.recoverySnapshot().find(({ provider }) => provider === 'codex');
		assert.equal(degraded.state, 'degraded');
		assert.equal(degraded.nextProbeAtEpochMs, 1_025);

		timers.advanceTo(degraded.nextProbeAtEpochMs - 1);
		await flush();
		assert.deepEqual(attemptTimes, [0], 'no startup probe runs before the advertised deadline');
		await timers.runNext();
		restored = router.recoverySnapshot().find(({ provider }) => provider === 'codex');
		assert.equal(restored.state, 'live');
		assert.deepEqual(attemptTimes, [0, 1_025], 'one startup probe runs at the deadline');
		assert.equal(maxPendingStarts, 2, 'the timed-out start remains owned until its underlying promise settles');
		assert.equal(pendingStarts, 1, 'logical timeout does not fabricate settlement of the old start');
		session = await router.createAgent(PROFILE);
		releaseHung();
		await flush();
	} finally {
		releaseHung?.();
		await router.stop();
		listeners.sample();
	}
	const sessions = services.codex.sessionStats();
	return evidence({
		recovery: { healthy: restored?.state === 'live', permanentLatch: false, stateBefore: degraded?.state, stateAfter: restored?.state, nextProbeAtEpochMs: degraded?.nextProbeAtEpochMs, attemptTimes, probeDeadlines: [degraded?.nextProbeAtEpochMs], retryDelays: [1_000], attemptCount: attemptTimes.length },
		states: notApplicable('states', 'Provider startup has no authority to mutate an agent domain lifecycle.'),
		profile: profileEvidence(PROFILE, session?.profile, 'ProviderService exact session snapshot'),
		resources: resources({
			leases: notApplicable('leases', 'No work lease is allocated during provider-only startup.'),
			sessions: sessionEvidence(sessions, 'fake backend current-session counter around ProviderService'),
			actions: notApplicable('actions', 'Provider startup cannot dispatch Minecraft actions.'),
			timers: timers.evidence('ProviderService injected timeout and recovery scheduler'),
			promises: promiseEvidence(pendingStarts, maxPendingStarts, 'backend start-promise ownership counter'),
			childProcesses: notApplicable('childProcesses', 'This in-process provider fixture has no child-process creation capability.'),
			listeners: listeners.evidence('ProviderService EventEmitter listener sampler'),
		}),
	});
}

async function providerOutageAndRestoration() {
	const timers = new ManualTimers();
	const services = providerServices();
	let available = false;
	const refreshTimes = [];
	let pendingRefreshes = 0;
	let maxPendingRefreshes = 0;
	services.codex.catalog.refresh = () => trackedOperation(async () => {
		refreshTimes.push(timers.now);
		if (!available) throw Object.assign(new Error('temporary outage'), { code: 'PROVIDER_UNAVAILABLE' });
		return catalog('codex', PROFILE.model);
	}, (delta) => {
		pendingRefreshes += delta;
		maxPendingRefreshes = Math.max(maxPendingRefreshes, pendingRefreshes);
	});
	const router = new ProviderService(services, {
		operationTimeoutMs: 100,
		scheduleTimeout: timers.schedule,
		cancelTimeout: timers.cancel,
		now: () => timers.now,
	});
	const listeners = new ListenerGauge(router);
	let session = null;
	let degraded;
	let restored;
	try {
		const expectedRetryDelays = [1_000, 2_000, 4_000, 8_000, 16_000, 30_000, 30_000];
		const probeDeadlines = [];
		await router.catalog.refresh({ providers: ['codex'] });
		for (let index = 0; index < expectedRetryDelays.length; index += 1) {
			const expectedDelay = expectedRetryDelays[index];
			degraded = router.recoverySnapshot().find(({ provider }) => provider === 'codex');
			assert.equal(degraded.state, 'degraded');
			assert.equal(degraded.nextProbeAtEpochMs - timers.now, expectedDelay);
			probeDeadlines.push(degraded.nextProbeAtEpochMs);
			timers.advanceTo(degraded.nextProbeAtEpochMs - 1);
			await flush();
			assert.equal(refreshTimes.length, index + 1, 'catalog recovery cannot spin before its deadline');
			if (index === expectedRetryDelays.length - 1) available = true;
			await timers.runNext();
			assert.equal(refreshTimes.length, index + 2, 'production recovery executes exactly one probe at the deadline');
		}
		restored = router.recoverySnapshot().find(({ provider }) => provider === 'codex');
		assert.equal(restored.state, 'live');
		assert.deepEqual(refreshTimes, [0, ...probeDeadlines]);
		session = await router.createAgent(PROFILE);
		degraded = { ...degraded, probeDeadlines, retryDelays: expectedRetryDelays };
	} finally {
		await router.stop();
		listeners.sample();
	}
	return evidence({
		recovery: { healthy: restored?.state === 'live', permanentLatch: false, stateBefore: degraded?.state, stateAfter: restored?.state, nextProbeAtEpochMs: degraded?.nextProbeAtEpochMs, attemptTimes: refreshTimes, probeDeadlines: degraded?.probeDeadlines, retryDelays: degraded?.retryDelays, attemptCount: refreshTimes.length },
		states: notApplicable('states', 'Catalog recovery has no authority to mutate an agent domain lifecycle.'),
		profile: profileEvidence(PROFILE, session?.profile, 'restored ProviderService session snapshot'),
		resources: resources({
			leases: notApplicable('leases', 'Catalog refresh owns bounded operations, not goal work leases.'),
			sessions: sessionEvidence(services.codex.sessionStats(), 'backend session counter after router cleanup'),
			actions: notApplicable('actions', 'Catalog refresh cannot dispatch Minecraft actions.'),
			timers: timers.evidence('ProviderService injected timeout and recovery scheduler'),
			promises: promiseEvidence(pendingRefreshes, maxPendingRefreshes, 'catalog refresh-promise ownership counter'),
			childProcesses: notApplicable('childProcesses', 'This in-process catalog fixture has no child-process creation capability.'),
			listeners: listeners.evidence('ProviderService EventEmitter listener sampler'),
		}),
	});
}

async function ignoredAbortReleasesCapacity() {
	const timers = new ManualTimers();
	let releaseIgnored;
	let releaseReplacement;
	let activePromises = 0;
	let maxActivePromises = 0;
	const scheduler = new PlanningScheduler({
		maxConcurrent: 1,
		maxPending: 1,
		settlementGraceMs: 5,
		scheduleTimeout: timers.schedule,
		cancelTimeout: timers.cancel,
	});
	const schedulerLeases = new SchedulerLeaseGauge(scheduler);
	const ignored = scheduler.schedule('fault-agent', async () => {
		activePromises += 1;
		maxActivePromises = Math.max(maxActivePromises, activePromises);
		try { await new Promise((resolve) => { releaseIgnored = resolve; }); }
		finally { activePromises -= 1; }
	}, { leaseTimeoutMs: 20 });
	const replacement = scheduler.schedule('healthy-agent', async () => {
		activePromises += 1;
		maxActivePromises = Math.max(maxActivePromises, activePromises);
		try {
			await new Promise((resolve) => { releaseReplacement = resolve; });
			return 'healthy';
		} finally { activePromises -= 1; }
	});
	const ignoredOutcome = assert.rejects(ignored, (error) => error?.code === 'PLANNING_LEASE_EXPIRED');
	await flush();
	schedulerLeases.sample();
	assert.equal(scheduler.activeCount, 1);
	assert.equal(timers.snapshot().requestedDelays[0], 20);
	await timers.runNext();
	await ignoredOutcome;
	assert.equal(scheduler.activeCount, 0, 'the ignored-abort turn retains the physical slot during grace');
	assert.equal(scheduler.pendingCount, 1, 'replacement work remains queued during grace');
	assert.equal(scheduler.pressureSnapshot.settling, 1);
	assert.equal(activePromises, 1, 'replacement work cannot overlap during grace');
	assert.equal(maxActivePromises, 1);
	assert.equal(timers.snapshot().requestedDelays[1], 5);
	await timers.runNext();
	assert.equal(scheduler.activeCount, 1, 'the replacement owns the released scheduler slot');
	assert.equal(activePromises, 2, 'forced release permits replacement after the bounded grace');
	schedulerLeases.sample();
	releaseReplacement();
	assert.equal(await replacement, 'healthy');
	releaseIgnored();
	await flush();
	scheduler.close();
	schedulerLeases.sample();
	return evidence({
		recovery: { healthy: scheduler.activeCount === 0 && scheduler.pendingCount === 0, permanentLatch: false, stateBefore: 'lease_expired', stateAfter: 'capacity_available', nextProbeAtEpochMs: 25, attemptTimes: [0, 25], probeDeadlines: [20, 25], retryDelays: [20, 5], attemptCount: 2 },
		states: notApplicable('states', 'PlanningScheduler does not own agent domain lifecycle state.'),
		profile: notApplicable('profile', 'Scheduler capacity is provider-profile agnostic and cannot mutate a profile.'),
		resources: resources({
			leases: schedulerLeases.evidence('PlanningScheduler active lease sampler'),
			sessions: notApplicable('sessions', 'The scheduler test deliberately uses no provider session.'),
			actions: notApplicable('actions', 'Planning tasks do not invoke the Minecraft action bridge in this scenario.'),
			timers: timers.evidence('PlanningScheduler injected lease and settlement timers'),
			promises: promiseEvidence(activePromises, maxActivePromises, 'underlying abort-ignoring task ownership counter'),
			childProcesses: notApplicable('childProcesses', 'This in-process scheduler fixture has no child-process creation capability.'),
			listeners: notApplicable('listeners', 'PlanningScheduler exposes no event-listener surface.'),
		}),
	});
}

async function disconnectFencesOutstandingActionReplay() {
	const harness = createNativeGoalHarness({
		disconnectWhileActionOutstandingAtAction: 1,
		turns: [['mine'], ['mine']],
		actionResults: ['SUCCEEDED', 'SUCCEEDED'],
		timeoutMs: 1_000,
	});
	const running = harness.run();
	await eventually(() => harness.bridge.connectionEpoch === 2 && harness.bridge.actionDispatches.length === 2);
	assert.equal(harness.bridge.deferredActionCount, 1, 'the old terminal action callback is still outstanding');
	const replacementEpoch = harness.bridge.connectionEpoch;
	const replacementBeforeReplay = harness.bridge.actionDispatches.filter(({ connectionEpoch }) => connectionEpoch === replacementEpoch);
	assert.equal(replacementBeforeReplay.length, 1, 'the new epoch owns exactly one replacement action');
	assert.equal(harness.bridge.releaseDeferredActionResults(), 1);
	const result = await running;
	const oldDispatch = result.actionDispatches.find(({ connectionEpoch }) => connectionEpoch === 1);
	const replacementDispatches = result.actionDispatches.filter(({ connectionEpoch }) => connectionEpoch === replacementEpoch);
	assert.equal(result.staleActionReplays.length, 1);
	assert.equal(result.staleActionReplays[0].actionId, oldDispatch.payload.actionId);
	assert.equal(result.staleActionReplays[0].originConnectionEpoch, oldDispatch.connectionEpoch);
	assert.equal(result.staleActionReplays[0].replayConnectionEpoch, replacementEpoch);
	assert.equal(replacementDispatches.length, 1);
	assert.equal(result.actionAttempts.length, 2, 'the old command and one replacement each reach the physical boundary');
	assert.equal(result.actionEffects.length, 1);
	assert.equal(result.actionEffects[0].actionId, oldDispatch.payload.actionId, 'the first dispatched command honestly mutates the world before disconnect');
	assert.deepEqual(result.acceptedActionResults, [{ actionId: replacementDispatches[0].payload.actionId, connectionEpoch: replacementEpoch }], 'the replacement result is accepted exactly once');
	assert.equal(result.acceptedActionResults.some(({ actionId }) => actionId === oldDispatch.payload.actionId), false, 'the explicitly old-epoch result is rejected by the coordinator fence');
	assert.equal(result.staleDispatches, 0, 'no old-epoch terminal result crosses the coordinator fence');
	assert.equal(result.deliveredActionResults.find(({ actionId }) => actionId === oldDispatch.payload.actionId)?.connectionEpoch, oldDispatch.connectionEpoch);
	const duplicateDispatches = result.actionDispatches.length - new Set(result.actionDispatches.map(({ payload }) => payload.actionId)).size;
	assert.equal(duplicateDispatches, 0);
	assert.equal(result.providerSessions.created, 1);
	assert.equal(result.leaseStats.maxByKind.provider, 1);
	assert.equal(result.leaseStats.maxByKind.action, 1);
	assert.equal(result.listenerStats.maximum, 21, 'listener gauge includes inspection and samples the live bridge and coordinator registrations across reconnect');
	assert.equal(result.listenerStats.current, 0, 'listener gauge samples final cleanup separately from the lifecycle maximum');
	return evidence({
		recovery: { healthy: result.connectionEpoch === 2 && replacementDispatches.length === 1, permanentLatch: false, stateBefore: 'bridge_disconnected', stateAfter: result.finalState, nextProbeAtEpochMs: null, attemptTimes: [1, 2], probeDeadlines: [], retryDelays: [], attemptCount: 2 },
		states: statesEvidence(result.states, 'AgentRegistry snapshots sampled throughout native goal execution'),
		profile: profileEvidence(result.providerSessions.profile, result.profile, 'provider session and final AgentRegistry snapshots'),
		resources: resources({
			leases: leaseEvidence(result.leaseStats, 'tracking wrapper around the production ActiveGoalSupervisor'),
			sessions: sessionEvidence(result.providerSessions, 'scripted provider exact-session counters'),
			actions: actionEvidence({
				maxConcurrent: result.maxConcurrentPhysicalActions,
				effects: result.actionEffects.length,
				duplicates: duplicateDispatches,
				staleEffects: result.acceptedActionResults.filter(({ connectionEpoch }) => connectionEpoch !== replacementEpoch).length,
				pending: harness.bridge.deferredActionCount,
				attempts: result.actionAttempts.length,
				postFenceAccepted: result.acceptedActionResults.length,
			}, 'FaultInjectingMinecraftBridge physical ledger and coordinator actionResult acceptance events'),
			timers: timerEvidence(combinedTimerSnapshot(result.goalScheduler, result.stuckScheduler), 'injected work-lease and factual-progress schedulers'),
			promises: promiseEvidence(result.activeWork, result.providerSessions.maxTurnsPending, 'scripted provider unsettled-turn ownership counter'),
			childProcesses: notApplicable('childProcesses', 'This in-process native-provider fixture has no child-process creation capability.'),
			listeners: listenerEvidence(result.listenerStats, 'bridge and coordinator EventEmitter listener sampler'),
		}),
	});
}

async function diagnosticHangAndRejection() {
	const timers = new ManualTimers();
	let releaseHung;
	let pendingOperations = 0;
	let maxPendingOperations = 0;
	const completed = [];
	const queue = new BestEffortDiagnosticQueue({ maxPending: 4, operationTimeoutMs: 5, closeTimeoutMs: 50, maxDetachedOperations: 2, schedule: timers.schedule, cancel: timers.cancel });
	const trackDiagnostic = (operation) => trackedOperation(operation, (delta) => {
		pendingOperations += delta;
		maxPendingOperations = Math.max(maxPendingOperations, pendingOperations);
	});
	queue.submit(() => trackDiagnostic(() => new Promise((resolve) => { releaseHung = resolve; })));
	queue.submit(() => trackDiagnostic(async () => { throw Object.assign(new Error('disk offline'), { code: 'EIO' }); }));
	queue.submit(() => trackDiagnostic(() => { completed.push('healthy'); }));
	await flush();
	assert.equal(queue.statusSnapshot().state, 'ready');
	await timers.runNext({ flushAfter: false });
	await Promise.resolve();
	const degradedState = queue.statusSnapshot().state;
	assert.equal(degradedState, 'degraded');
	await eventually(() => completed.length === 1);
	assert.equal(queue.statusSnapshot().state, 'ready');
	assert.equal(pendingOperations, 1, 'the detached timed-out write remains owned until the sink settles');
	releaseHung();
	await flush();
	await queue.close();
	return evidence({
		recovery: { healthy: queue.statusSnapshot().state === 'ready', permanentLatch: false, stateBefore: degradedState, stateAfter: queue.statusSnapshot().state, nextProbeAtEpochMs: null, attemptTimes: timers.firedDeadlines, probeDeadlines: timers.firedDeadlines, retryDelays: [5], attemptCount: 3 },
		states: notApplicable('states', 'Diagnostics are observational and cannot mutate agent domain lifecycle.'),
		profile: notApplicable('profile', 'Diagnostics cannot select or mutate an AI provider profile.'),
		resources: resources({
			leases: notApplicable('leases', 'DiagnosticQueue owns bounded sink operations rather than goal leases.'),
			sessions: notApplicable('sessions', 'Diagnostics create no provider sessions.'),
			actions: notApplicable('actions', 'Diagnostics cannot dispatch Minecraft actions.'),
			timers: timers.evidence('BestEffortDiagnosticQueue injected operation scheduler'),
			promises: promiseEvidence(pendingOperations, maxPendingOperations, 'diagnostic sink-promise ownership counter'),
			childProcesses: notApplicable('childProcesses', 'This in-process diagnostic fixture has no child-process creation capability.'),
			listeners: notApplicable('listeners', 'BestEffortDiagnosticQueue exposes no listener surface.'),
		}),
	});
}

async function exactProfileSessionAndLeasePreservation() {
	const timers = new ManualTimers();
	const supervisor = new WorkLeaseSupervisor({ clock: () => timers.now, schedule: timers.schedule, cancelSchedule: timers.cancel, stuckSchedule: timers.schedule, cancelStuckSchedule: timers.cancel });
	const leases = new LeaseSnapshotGauge();
	const first = leaseKey(1);
	const second = leaseKey(2);
	assert.equal(supervisor.activate(first), true);
	leases.sample(supervisor.snapshot(first));
	const staleProvider = supervisor.acquire(first, 'provider');
	leases.sample(supervisor.snapshot(first));
	assert.equal(supervisor.activate(second), true);
	leases.sample(supervisor.snapshot(second));
	const action = supervisor.acquire(second, 'action');
	leases.sample(supervisor.snapshot(second));
	assert.equal(supervisor.release(staleProvider), false);
	assert.equal(supervisor.activate({ ...second, profileFingerprint: `sha256:${'b'.repeat(64)}` }), false);
	const live = supervisor.snapshot(second);
	assert.equal(live.leases.length, 1);
	assert.equal(live.leases[0].kind, 'action');
	assert.equal(supervisor.release(action), true);
	const recovered = supervisor.snapshot(second);
	assert.equal(recovered.leases.length, 1);
	assert.equal(recovered.leases[0].kind, 'scheduled');
	leases.sample(recovered);
	supervisor.close();
	leases.sample(null);
	return evidence({
		recovery: { healthy: recovered.state === 'active', permanentLatch: false, stateBefore: 'session_epoch_1', stateAfter: 'session_epoch_2', nextProbeAtEpochMs: recovered.leases[0].deadline, attemptTimes: [first.sessionEpoch, second.sessionEpoch], probeDeadlines: [recovered.leases[0].deadline], retryDelays: [recovered.leases[0].deadline - timers.now], attemptCount: 2 },
		states: notApplicable('states', 'WorkLeaseSupervisor cannot mutate the AgentRegistry lifecycle.'),
		profile: profileEvidence({ fingerprint: PROFILE_FINGERPRINT }, { fingerprint: recovered.key.profileFingerprint }, 'production WorkLeaseSupervisor key snapshots'),
		resources: resources({
			leases: leases.evidence('production WorkLeaseSupervisor lifecycle snapshots'),
			sessions: notApplicable('sessions', 'Lease fencing uses session keys but does not create provider sessions.'),
			actions: notApplicable('actions', 'Action lease fencing does not dispatch a physical Minecraft action.'),
			timers: timers.evidence('WorkLeaseSupervisor injected lease scheduler'),
			promises: notApplicable('promises', 'WorkLeaseSupervisor lease operations are synchronous and own no promises.'),
			childProcesses: notApplicable('childProcesses', 'WorkLeaseSupervisor has no child-process creation capability.'),
			listeners: notApplicable('listeners', 'WorkLeaseSupervisor exposes no event-listener surface.'),
		}),
	});
}

function evidence(value) {
	assertCompleteEvidence(value);
	return value;
}

function resources(value) {
	for (const name of RESOURCE_NAMES) if (!Object.hasOwn(value, name)) throw new TypeError(`${name} evidence is required`);
	return Object.freeze(value);
}

function notApplicable(category, reason) {
	if (typeof reason !== 'string' || reason.trim().length < 16) throw new TypeError('notApplicable evidence requires a specific reason');
	return issueEvidence(category, 'notApplicable', { reason }, `${category} applicability decision`);
}

function assertCompleteEvidence(result) {
	if (result?.recovery === null || typeof result?.recovery !== 'object') throw new TypeError('recovery evidence is required');
	for (const key of ['healthy', 'permanentLatch', 'stateBefore', 'stateAfter', 'nextProbeAtEpochMs', 'attemptTimes', 'probeDeadlines', 'retryDelays', 'attemptCount']) {
		if (!Object.hasOwn(result.recovery, key)) throw new TypeError(`recovery.${key} evidence is required`);
	}
	assertEvidenceEntry(result.states, 'states');
	assertEvidenceEntry(result.profile, 'profile');
	if (result.resources === null || typeof result.resources !== 'object') throw new TypeError('resources evidence is required');
	for (const name of RESOURCE_NAMES) {
		if (!Object.hasOwn(result.resources, name)) throw new TypeError(`${name} evidence is required`);
		assertEvidenceEntry(result.resources[name], name);
	}
}

function assertEvidenceEntry(entry, name) {
	if (entry === null || typeof entry !== 'object' || !TRUSTED_EVIDENCE.has(entry)) throw new TypeError(`${name} evidence requires a trusted category-bound entry`);
	if (entry.category !== name) throw new TypeError(`${name} evidence cannot reuse '${entry.category}' evidence`);
	if (entry?.kind === 'observed') {
		if (!Object.hasOwn(entry, 'value') || entry.value === null || entry.value === undefined) throw new TypeError(`${name} evidence requires a non-null instrumented value`);
		if (typeof entry.source !== 'string' || entry.source.trim().length < 8) throw new TypeError(`${name} evidence source is required`);
		return;
	}
	if (entry?.kind === 'notApplicable') {
		if (typeof entry.value?.reason !== 'string' || entry.value.reason.trim().length < 16) throw new TypeError(`${name} notApplicable reason is required`);
		return;
	}
	throw new TypeError(`${name} evidence must be observed or explicitly notApplicable`);
}

function issueEvidence(category, kind, value, source) {
	if (![...RESOURCE_NAMES, 'states', 'profile'].includes(category)) throw new TypeError('evidence category is invalid');
	if (value === null || value === undefined || typeof value !== 'object') throw new TypeError('sampler produced no evidence value');
	const snapshot = cloneEvidenceValue(value);
	const entry = Object.freeze({ kind, category, value: snapshot, source });
	TRUSTED_EVIDENCE.add(entry);
	return entry;
}

function statesEvidence(states, source) {
	if (!Array.isArray(states)) throw new TypeError('state sampler requires an array');
	return issueEvidence('states', 'observed', states, source);
}

function profileEvidence(before, after, source) {
	return issueEvidence('profile', 'observed', { before, after }, source);
}

function sessionEvidence(stats, source) {
	return issueEvidence('sessions', 'observed', stats, source);
}

function leaseEvidence(stats, source) {
	return issueEvidence('leases', 'observed', stats, source);
}

function actionEvidence(stats, source) {
	return issueEvidence('actions', 'observed', stats, source);
}

function timerEvidence(stats, source) {
	return issueEvidence('timers', 'observed', stats, source);
}

function promiseEvidence(pending, maximum, source) {
	return issueEvidence('promises', 'observed', { pending, maximum }, source);
}

function listenerEvidence(stats, source) {
	return issueEvidence('listeners', 'observed', { current: stats.current, maximum: stats.maximum }, source);
}

function cloneEvidenceValue(root) {
	const clones = new WeakMap();
	const active = new WeakSet();
	let entries = 0;
	const clone = (value, depth) => {
		if (value === null || ['string', 'number', 'boolean', 'undefined'].includes(typeof value)) return value;
		if (typeof value !== 'object') throw new TypeError('evidence values must contain only data');
		if (utilTypes.isProxy(value)) throw new TypeError('evidence values cannot contain proxies');
		if (depth > 32) throw new TypeError('evidence value exceeds maximum depth');
		if (active.has(value)) throw new TypeError('evidence values cannot contain cycles');
		if (clones.has(value)) return clones.get(value);
		const prototype = Object.getPrototypeOf(value);
		const array = Array.isArray(value);
		if (prototype !== (array ? Array.prototype : Object.prototype) && prototype !== null) throw new TypeError('evidence values must contain only plain arrays and records');
		const keys = Reflect.ownKeys(value);
		if (keys.some((key) => typeof key === 'symbol')) throw new TypeError('evidence values cannot contain symbol properties');
		entries += keys.length - (array && keys.includes('length') ? 1 : 0);
		if (entries > 10_000) throw new TypeError('evidence value exceeds maximum entries');
		const descriptors = Object.getOwnPropertyDescriptors(value);
		const copy = array ? [] : Object.create(prototype);
		clones.set(value, copy);
		active.add(value);
		for (const key of keys) {
			if (array && key === 'length') continue;
			const descriptor = descriptors[key];
			if (!Object.hasOwn(descriptor, 'value')) throw new TypeError('evidence values cannot contain accessors');
			Object.defineProperty(copy, key, {
				value: clone(descriptor.value, depth + 1),
				enumerable: descriptor.enumerable,
				configurable: true,
				writable: true,
			});
		}
		active.delete(value);
		return copy;
	};
	const snapshot = clone(root, 0);
	const frozen = new WeakSet();
	const freeze = (value) => {
		if (value === null || typeof value !== 'object' || frozen.has(value)) return;
		frozen.add(value);
		for (const descriptor of Object.values(Object.getOwnPropertyDescriptors(value))) if (Object.hasOwn(descriptor, 'value')) freeze(descriptor.value);
		Object.freeze(value);
	};
	freeze(snapshot);
	return snapshot;
}

function assertNoDomainFailure(entry) {
	if (entry.kind === 'notApplicable') return;
	assert.ok(Array.isArray(entry.value));
	assert.equal(entry.value.includes(DynamicAgentState.PAUSED), false, 'infrastructure cannot pause player work');
	assert.equal(entry.value.includes(DynamicAgentState.ERROR), false, 'infrastructure cannot create a domain error');
}

function assertExactProfile(entry) {
	if (entry.kind === 'notApplicable') return;
	const { before, after } = entry.value;
	if (before?.fingerprint !== undefined || after?.fingerprint !== undefined) {
		assert.equal(after?.fingerprint, before?.fingerprint, 'recovery preserves the exact profile fingerprint');
		return;
	}
	for (const key of ['agentId', 'provider', 'model', 'reasoningEffort', 'serviceTier']) assert.equal(after?.[key], before?.[key], `recovery preserves profile.${key}`);
}

function assertBoundedResources(resourcesValue) {
	const leases = valueOf(resourcesValue.leases);
	if (leases !== null) {
		for (const count of Object.values(leases.maxByKind ?? {})) assert.ok(count <= 1, 'each work kind has one live lease owner');
		assert.equal(leases.pending, 0, 'no work lease remains after cleanup');
	}
	const sessions = valueOf(resourcesValue.sessions);
	if (sessions !== null) {
		assert.ok(sessions.maxCurrent <= 1, 'one exact profile owns at most one current session');
		assert.equal(sessions.current, 0, 'no provider session remains after cleanup');
	}
	const actions = valueOf(resourcesValue.actions);
	if (actions !== null) {
		assert.ok(actions.maxConcurrent <= 1, 'one physical action executes at a time');
		assert.equal(actions.duplicates, 0, 'recovery creates no duplicate physical effect');
		assert.equal(actions.staleEffects, 0, 'stale callbacks create no physical effect');
		assert.equal(actions.pending, 0, 'no action result remains pending');
	}
	const timers = valueOf(resourcesValue.timers);
	if (timers !== null) assert.equal(timers.pending, 0, 'no timer remains after cleanup');
	for (const name of ['promises', 'childProcesses', 'listeners']) {
		const resource = valueOf(resourcesValue[name]);
		if (resource !== null) assert.equal(resource.pending ?? resource.current, 0, `no ${name} remain after cleanup`);
	}
}

function valueOf(entry) { return entry.kind === 'observed' ? entry.value : null; }

function providerServices() {
	return Object.fromEntries(['codex', 'gemini', 'kimi'].map((provider) => {
		let current = null;
		let currentSessions = 0;
		let maxCurrentSessions = 0;
		let createdSessions = 0;
		let lastProfile = null;
		const service = {
			catalog: { stale: false, refresh: async () => catalog(provider, `${provider}-model`), assertSupported() {} },
			async start() {},
			async stop() { current = null; currentSessions = 0; },
			async createAgent(profile) {
				if (current === null) {
					createdSessions += 1;
					currentSessions += 1;
					maxCurrentSessions = Math.max(maxCurrentSessions, currentSessions);
					lastProfile = Object.freeze({ ...profile });
					current = { agentId: profile.agentId, profile: lastProfile, sessionGeneration: createdSessions };
				}
				return current;
			},
			async replaceAgent(profile) {
				lastProfile = Object.freeze({ ...profile });
				current = { agentId: profile.agentId, profile: lastProfile, sessionGeneration: ++createdSessions };
				currentSessions = 1;
				maxCurrentSessions = Math.max(maxCurrentSessions, currentSessions);
				return current;
			},
			getAgent: (agentId) => current?.agentId === agentId ? current : null,
			async removeAgent(agentId) {
				if (current?.agentId !== agentId) return false;
				current = null;
				currentSessions = 0;
				return true;
			},
			async reconcile(records) { return { valid: records, invalid: [], removed: [], catalog: catalog(provider, `${provider}-model`) }; },
			sessionStats: () => ({ created: createdSessions, current: currentSessions, maxCurrent: maxCurrentSessions, profile: lastProfile }),
		};
		return [provider, service];
	}));
}

function catalog(provider, model) {
	return { refreshedAtEpochMs: 1, models: [{ id: model, model, displayName: model, reasoningEfforts: ['high'], serviceTiers: ['fast'], provider }] };
}

function leaseKey(sessionEpoch) {
	return { agentId: PROFILE.agentId, goalRevision: 1, lifecycleGeneration: 1, sessionEpoch, profileFingerprint: PROFILE_FINGERPRINT };
}

function listenerCount(emitter) {
	return emitter.eventNames().reduce((sum, name) => sum + emitter.listenerCount(name), 0);
}

function combinedTimerSnapshot(...snapshots) {
	return {
		pending: snapshots.reduce((sum, snapshot) => sum + snapshot.pending, 0),
		requestedDelays: snapshots.flatMap((snapshot) => snapshot.scheduledDelays),
		firedDelays: snapshots.flatMap((snapshot) => snapshot.firedDelays),
		maximum: Math.max(...snapshots.map((snapshot) => snapshot.maxPending)),
	};
}

function trackedOperation(operation, update) {
	update(1);
	return Promise.resolve().then(operation).finally(() => update(-1));
}

class ListenerGauge {
	#emitter;
	#maximum = 0;
	#current = 0;

	constructor(emitter) {
		this.#emitter = emitter;
		this.sample();
	}

	sample() {
		this.#current = listenerCount(this.#emitter);
		this.#maximum = Math.max(this.#maximum, this.#current);
	}

	evidence(source) { return listenerEvidence({ current: this.#current, maximum: this.#maximum }, source); }
}

class SchedulerLeaseGauge {
	#scheduler;
	#maximum = 0;
	#current = 0;

	constructor(scheduler) {
		this.#scheduler = scheduler;
		this.sample();
	}

	sample() {
		this.#current = this.#scheduler.activeCount;
		this.#maximum = Math.max(this.#maximum, this.#current);
	}

	evidence(source) { return leaseEvidence({ maxByKind: { provider: this.#maximum }, pending: this.#current }, source); }
}

class LeaseSnapshotGauge {
	#maximumByKind = new Map();
	#pending = 0;

	sample(snapshot) {
		const leases = snapshot?.leases ?? [];
		this.#pending = leases.length;
		for (const { kind } of leases) {
			const current = leases.filter((lease) => lease.kind === kind).length;
			this.#maximumByKind.set(kind, Math.max(this.#maximumByKind.get(kind) ?? 0, current));
		}
	}

	evidence(source) { return leaseEvidence({ maxByKind: Object.fromEntries(this.#maximumByKind), pending: this.#pending }, source); }
}

class ManualTimers {
	now = 0;
	#sequence = 0;
	#timers = new Map();
	#history = [];
	#maxPending = 0;

	get firedDeadlines() { return this.#history.filter(({ fired }) => fired).map(({ deadline }) => deadline); }

	schedule = (callback, delay = 0) => {
		const handle = { id: ++this.#sequence };
		const entry = { handle, callback, requestedAt: this.now, delay, deadline: this.now + delay, fired: false, cancelled: false };
		this.#timers.set(handle.id, entry);
		this.#history.push(entry);
		this.#maxPending = Math.max(this.#maxPending, this.#timers.size);
		return handle;
	};

	cancel = (handle) => {
		const entry = this.#timers.get(handle?.id);
		if (entry !== undefined) entry.cancelled = true;
		return this.#timers.delete(handle?.id);
	};

	advanceTo(target) {
		if (!Number.isFinite(target) || target < this.now) throw new TypeError('timer clock cannot move backwards');
		this.now = target;
	}

	peekNextCallback() {
		const entry = [...this.#timers.values()].sort((left, right) => left.deadline - right.deadline || left.handle.id - right.handle.id)[0];
		if (entry === undefined) throw new Error('no deterministic timer is pending');
		return entry.callback;
	}

	peekNextDeadline() {
		const entry = [...this.#timers.values()].sort((left, right) => left.deadline - right.deadline || left.handle.id - right.handle.id)[0];
		if (entry === undefined) throw new Error('no deterministic timer is pending');
		return entry.deadline;
	}

	async runNext({ flushAfter = true } = {}) {
		const entry = [...this.#timers.values()].sort((left, right) => left.deadline - right.deadline || left.handle.id - right.handle.id)[0];
		if (entry === undefined) throw new Error('no deterministic timer is pending');
		this.#timers.delete(entry.handle.id);
		this.now = Math.max(this.now, entry.deadline);
		entry.fired = true;
		await entry.callback();
		if (flushAfter) await flush();
	}

	snapshot() {
		return {
			pending: this.#timers.size,
			requestedDelays: this.#history.map(({ delay }) => delay),
			requestedDeadlines: this.#history.map(({ deadline }) => deadline),
			firedDeadlines: this.firedDeadlines,
			cancelled: this.#history.filter(({ cancelled }) => cancelled).length,
			maximum: this.#maxPending,
		};
	}

	evidence(source) { return timerEvidence(this.snapshot(), source); }
}

async function flush() {
	await Promise.resolve();
	await new Promise((resolve) => setImmediate(resolve));
}

async function eventually(predicate, timeoutMs = 500) {
	const deadline = Date.now() + timeoutMs;
	while (Date.now() < deadline) {
		if (predicate()) return;
		await flush();
	}
	throw new Error('fault scenario did not reach its healthy boundary');
}
