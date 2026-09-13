import assert from 'node:assert/strict';
import test from 'node:test';

import { ANTIGRAVITY_INTERNAL_TEST_MODE, AntigravityProviderService } from '../src/antigravity-service.mjs';
import { ProviderService } from '../src/provider-service.mjs';

class FakeService {
	constructor(provider) {
		this.provider = provider; this.created = []; this.removed = []; this.stopped = false;
		this.catalog = { refresh: async () => ({ provider, refreshedAtEpochMs: 1, models: [] }), assertSupported: () => {} };
	}
	async start() {}
	async stop() { this.stopped = true; }
	async createAgent(profile) { this.created.push(profile); return { agentId: profile.agentId, provider: this.provider }; }
	async replaceAgent(profile) { this.removed.push(profile.agentId); return this.createAgent(profile); }
	getAgent(agentId) { return this.created.some((profile) => profile.agentId === agentId) ? { agentId, provider: this.provider } : null; }
	async removeAgent(agentId) { this.removed.push(agentId); return true; }
	async reconcile(records) { return { valid: records, invalid: [], removed: [], catalog: { provider: this.provider, models: [] } }; }
}

test('provider catalog exposes enforced adapter unavailability without advertising Gemini models', async () => {
	const services = Object.fromEntries(['codex', 'gemini', 'kimi'].map((provider) => [provider, new FakeService(provider)]));
	services.gemini = new AntigravityProviderService({ cwd: 'C:\\workspace' });
	const router = new ProviderService(services);
	const catalog = await router.catalog.refresh({ providers: ['gemini'] });
	assert.deepEqual(catalog.models, []);
	assert.equal(catalog.availability.find((value) => value.provider === 'gemini').playable, false);
	assert.equal(catalog.availability.find((value) => value.provider === 'gemini').reasonCode, 'PROVIDER_UNAVAILABLE');
	assert.equal(router.getExecutionSettings('missing'), null);
	await router.stop();
});

test('provider router coalesces one exact-profile replacement and rejects tier mutation', async () => {
	const services = Object.fromEntries(['codex', 'gemini', 'kimi'].map((provider) => [provider, new FakeService(provider)]));
	let release;
	const gate = new Promise((resolve) => { release = resolve; });
	services.codex.replaceAgent = async (value) => {
		services.codex.removed.push(value.agentId);
		await gate;
		return services.codex.createAgent(value);
	};
	const router = new ProviderService(services);
	const selected = profile('codex');
	await router.createAgent(selected);
	const first = router.replaceAgent(selected, { expectedSessionGeneration: 1 });
	const second = router.replaceAgent(selected, { expectedSessionGeneration: 1 });
	await assert.rejects(
		router.replaceAgent({ ...selected, serviceTier: 'priority' }),
		(error) => error?.code === 'AGENT_PROFILE_CONFLICT',
	);
	release();
	assert.equal(await first, await second);
	assert.deepEqual(services.codex.removed, [selected.agentId]);
	assert.equal(services.codex.created.length, 2);
	await router.stop();
});

test('replacement requests during initial creation coalesce into one replacement generation', async () => {
	const services = Object.fromEntries(['codex', 'gemini', 'kimi'].map((provider) => [provider, new FakeService(provider)]));
	let releaseCreate;
	const createGate = new Promise((resolve) => { releaseCreate = resolve; });
	let creates = 0;
	let replacements = 0;
	services.codex.createAgent = async (value) => {
		creates += 1;
		services.codex.created.push(value);
		if (creates === 1) await createGate;
		return { agentId: value.agentId, provider: value.provider, sessionGeneration: creates };
	};
	services.codex.replaceAgent = async (value) => {
		replacements += 1;
		return services.codex.createAgent(value);
	};
	const router = new ProviderService(services);
	const selected = profile('codex');
	const initial = router.createAgent(selected);
	const first = router.replaceAgent(selected, { expectedSessionGeneration: 1 });
	const second = router.replaceAgent(selected, { expectedSessionGeneration: 1 });
	releaseCreate();
	assert.equal((await initial).sessionGeneration, 1);
	const [replacement, duplicate] = await Promise.all([first, second]);
	assert.equal(replacement, duplicate);
	assert.equal(replacement.sessionGeneration, 2);
	assert.equal(replacements, 1);
	assert.equal(creates, 2);
	await router.stop();
});

function profile(provider, overrides = {}) {
	return {
		agentId: 'shared-agent',
		provider,
		model: `${provider}-model`,
		reasoningEffort: 'high',
		serviceTier: ['codex', 'cursor'].includes(provider) ? 'fast' : 'priority',
		...overrides,
	};
}

function manualTimeouts() {
	const timeouts = [];
	return {
		options: {
			scheduleTimeout(callback) {
				const timeout = { active: true, callback, unref() {} };
				timeouts.push(timeout);
				return timeout;
			},
			cancelTimeout(timeout) { timeout.active = false; },
		},
		expireNext() {
			const timeout = timeouts.find((candidate) => candidate.active);
			assert.ok(timeout, 'a provider timeout must be scheduled');
			timeout.active = false;
			timeout.callback();
		},
	};
}

test('provider router defaults legacy profiles to Codex and isolates each backend', async () => {
	const services = Object.fromEntries(['codex', 'gemini', 'kimi', 'cursor'].map((provider) => [provider, new FakeService(provider)]));
	const router = new ProviderService(services);
	assert.equal((await router.createAgent({ agentId: 'legacy' })).provider, 'codex');
	assert.equal((await router.createAgent({ agentId: 'g', provider: 'gemini' })).provider, 'gemini');
	assert.equal((await router.createAgent({ agentId: 'k', provider: 'kimi' })).provider, 'kimi');
	assert.equal((await router.createAgent({ agentId: 'u', provider: 'cursor' })).provider, 'cursor');
	assert.deepEqual(services.codex.created.map((profile) => profile.provider), ['codex']);
	assert.equal(router.getAgent('g').provider, 'gemini');
	await router.removeAgent('k');
	assert.deepEqual(services.kimi.removed, ['k']);
	await router.stop();
	assert.equal(services.gemini.stopped, true);
});

test('provider router rejects every profile mutation for an existing agent ID', async () => {
	const services = Object.fromEntries(['codex', 'gemini', 'kimi'].map((provider) => [provider, new FakeService(provider)]));
	const router = new ProviderService(services);
	const selected = profile('codex');
	const agent = await router.createAgent(selected);
	assert.equal(agent.provider, 'codex');

	for (const mutation of [
		{ model: 'codex-other-model' },
		{ reasoningEffort: 'low' },
		{ serviceTier: 'priority' },
	]) {
		await assert.rejects(
			router.createAgent({ ...selected, ...mutation }),
			(error) => error?.code === 'AGENT_PROFILE_CONFLICT'
				&& error?.message.length <= 256
				&& !error?.message.includes('secret'),
		);
	}
	await assert.rejects(router.createAgent({ ...selected, provider: 'gemini' }), /fast is available only/);
	assert.equal(services.codex.created.length, 1);
	assert.equal(services.gemini.created.length, 0);
	assert.equal(services.kimi.created.length, 0);
	await router.stop();
});

test('provider reconciliation retains a Gemini priority profile and rejects a fast tier', async () => {
	const gemini = new AntigravityProviderService({
		provider: 'gemini',
		cwd: 'C:\\workspace',
		models: ['gemini-3.1-pro'],
		modelReasoningEfforts: { 'gemini-3.1-pro': ['high', 'low'] },
		testOnlyMode: ANTIGRAVITY_INTERNAL_TEST_MODE,
	});
	const router = new ProviderService({
		codex: new FakeService('codex'),
		gemini,
		kimi: new FakeService('kimi'),
	});
	const selected = profile('gemini', {
		agentId: 'gemini-session',
		model: 'gemini-3.1-pro',
		serviceTier: 'priority',
	});
	const agent = await router.createAgent(selected);
	const reconciliation = await router.reconcile([selected]);
	assert.deepEqual(reconciliation.valid, [selected], 'reconciliation preserves the complete Gemini profile');
	assert.equal(await router.createAgent(selected), agent, 'same profile reuses the existing Gemini session');
	await assert.rejects(
		router.createAgent({ ...selected, serviceTier: 'fast' }),
		/fast is available only/,
	);
	await router.stop();
});

test('provider router reserves an in-flight agent ID before recovery can mutate its profile', async () => {
	let release;
	const pending = new Promise((resolve) => { release = resolve; });
	const services = Object.fromEntries(['codex', 'gemini', 'kimi'].map((provider) => [provider, new FakeService(provider)]));
	services.codex.createAgent = async (value) => {
		services.codex.created.push(value);
		await pending;
		return { agentId: value.agentId, provider: value.provider };
	};
	const router = new ProviderService(services);
	const selected = profile('codex');
	const creating = router.createAgent(selected, { recoverySummary: 'same brain recovery' });
	await new Promise((resolve) => setImmediate(resolve));
	const mutation = router.createAgent({ ...selected, serviceTier: 'priority' }, { recoverySummary: 'mutated recovery' });
	release();
	await creating;
	await assert.rejects(mutation, (error) => error?.code === 'AGENT_PROFILE_CONFLICT');
	assert.equal(services.codex.created.length, 1);
	await router.stop();
});

test('provider reconciliation groups profiles and preserves an unavailable provider as an invalid subset', async () => {
	const codex = new FakeService('codex');
	const gemini = new FakeService('gemini');
	const kimi = new FakeService('kimi');
	const cursor = new FakeService('cursor');
	gemini.reconcile = async (records) => ({ valid: [], invalid: records.map((record) => ({ profile: record, code: 'PROVIDER_UNAVAILABLE', message: 'login rejected' })), removed: [], catalog: { provider: 'gemini', models: [] } });
	const router = new ProviderService({ codex, gemini, kimi, cursor });
	const result = await router.reconcile([
		{ agentId: 'c', provider: 'codex' }, { agentId: 'g', provider: 'gemini' }, { agentId: 'k', provider: 'kimi' },
		{ agentId: 'u', provider: 'cursor' },
	]);
	assert.deepEqual(result.valid.map((record) => record.agentId), ['c', 'k', 'u']);
	assert.deepEqual(result.invalid.map((entry) => entry.profile.agentId), ['g']);
});

test('combined catalog refreshes independent provider CLIs concurrently', async () => {
	const services = Object.fromEntries(['codex', 'gemini', 'kimi', 'cursor'].map((provider) => [provider, new FakeService(provider)]));
	const started = [];
	const releases = new Map();
	for (const [provider, service] of Object.entries(services)) {
		service.catalog.refresh = () => new Promise((resolve) => {
			started.push(provider);
			releases.set(provider, () => resolve({ provider, refreshedAtEpochMs: 1, models: [] }));
		});
	}
	const router = new ProviderService(services);
	const refreshing = router.catalog.refresh();
	await new Promise((resolve) => setImmediate(resolve));
	assert.deepEqual(started, ['codex', 'gemini', 'kimi', 'cursor']);
	for (const release of releases.values()) release();
	await refreshing;
});

test('reconciles independent providers concurrently', async () => {
	const services = Object.fromEntries(['codex', 'gemini', 'kimi'].map((provider) => [provider, new FakeService(provider)]));
	const started = [];
	const releases = new Map();
	for (const [provider, service] of Object.entries(services)) {
		service.reconcile = (records) => new Promise((resolve) => {
			started.push(provider);
			releases.set(provider, () => resolve({ valid: records, invalid: [], removed: [], catalog: { models: [] } }));
		});
	}
	const router = new ProviderService(services);
	const reconciling = router.reconcile([
		{ agentId: 'c', provider: 'codex' }, { agentId: 'g', provider: 'gemini' }, { agentId: 'k', provider: 'kimi' },
	]);
	await new Promise((resolve) => setImmediate(resolve));
	assert.deepEqual(started, ['codex', 'gemini', 'kimi']);
	for (const release of releases.values()) release();
	const result = await reconciling;
	assert.deepEqual(result.valid.map((profile) => profile.agentId), ['c', 'g', 'k']);
});

test('bootstrap catalog is complete before mixed-provider profiles can be accepted', async () => {
	const services = Object.fromEntries(['codex', 'gemini', 'kimi'].map((provider) => [provider, new FakeService(provider)]));
	for (const [provider, service] of Object.entries(services)) {
		service.catalog.refresh = async () => ({
			refreshedAtEpochMs: 42,
			models: [{ id: `${provider}-model`, model: `${provider}-model`, displayName: `${provider} model`, reasoningEfforts: ['high'], serviceTiers: [] }],
		});
	}
	const router = new ProviderService(services);

	const snapshot = await router.bootstrapCatalog();
	assert.deepEqual(snapshot.models.map((model) => [model.provider, model.id]), [
		['codex', 'codex-model'],
		['gemini', 'gemini-model'],
		['kimi', 'kimi-model'],
	]);
});

test('bootstrap catalog delegates profile-aware fast paths to the selected provider', async () => {
	const services = Object.fromEntries(['codex', 'gemini', 'kimi'].map((provider) => [provider, new FakeService(provider)]));
	let receivedRecords = null;
	services.codex.bootstrapCatalog = async (records) => {
		receivedRecords = records;
		return {
			refreshedAtEpochMs: 0,
			models: [{ id: 'codex-model', model: 'codex-model', displayName: 'Codex model', reasoningEfforts: ['high'], serviceTiers: ['fast'] }],
			source: 'builtin',
		};
	};
	services.codex.catalog.refresh = async () => { throw new Error('generic refresh bypassed provider fast path'); };
	const router = new ProviderService(services);

	const record = profile('codex');
	const snapshot = await router.bootstrapCatalog([record]);
	assert.deepEqual(receivedRecords, [record]);
	assert.deepEqual(snapshot.models.map(({ provider, id }) => ({ provider, id })), [{ provider: 'codex', id: 'codex-model' }]);
});

test('concurrent creation coalesces one lazy startup for the selected provider', async () => {
	const services = Object.fromEntries(['codex', 'gemini', 'kimi'].map((provider) => [provider, new FakeService(provider)]));
	let releaseStart;
	let startCalls = 0;
	services.codex.start = async () => {
		startCalls += 1;
		await new Promise((resolve) => { releaseStart = resolve; });
	};
	const router = new ProviderService(services);
	const first = router.createAgent(profile('codex', { agentId: 'codex-a' }));
	const second = router.createAgent(profile('codex', { agentId: 'codex-b' }));
	await new Promise((resolve) => setImmediate(resolve));
	try {
		assert.equal(startCalls, 1);
		assert.equal(services.codex.created.length, 0, 'agent creation waits for the shared provider startup');
	} finally {
		releaseStart?.();
		await Promise.allSettled([first, second]);
		await router.stop();
	}
});

test('a timed-out provider start is evicted so a later probe starts a fresh generation', async () => {
	const services = Object.fromEntries(['codex', 'gemini', 'kimi'].map((provider) => [provider, new FakeService(provider)]));
	const releases = [];
	let startCalls = 0;
	services.codex.start = () => new Promise((resolve) => {
		startCalls += 1;
		releases.push(resolve);
	});
	const timeouts = manualTimeouts();
	const router = new ProviderService(services, { operationTimeoutMs: 5, ...timeouts.options });
	try {
		const firstAttempt = router.start(['codex']);
		await new Promise((resolve) => setImmediate(resolve));
		timeouts.expireNext();
		const first = await firstAttempt;
		assert.equal(first[0].status, 'rejected');
		const secondAttempt = router.start(['codex']);
		await new Promise((resolve) => setImmediate(resolve));
		assert.equal(startCalls, 2, 'the timed-out startup promise is not cached forever');

		releases[0]();
		await new Promise((resolve) => setImmediate(resolve));
		let secondSettled = false;
		void secondAttempt.then(() => { secondSettled = true; }, () => { secondSettled = true; });
		await Promise.resolve();
		assert.equal(secondSettled, false, 'a late obsolete start cannot resurrect or satisfy the replacement generation');

		releases[1]();
		const second = await secondAttempt;
		assert.equal(second[0].status, 'fulfilled');
	} finally {
		for (const release of releases) release();
		await router.stop();
	}
});

test('stop fences a start-delayed creation before the backend can create or assign it', async () => {
	const services = Object.fromEntries(['codex', 'gemini', 'kimi'].map((provider) => [provider, new FakeService(provider)]));
	let releaseStart;
	services.codex.start = () => new Promise((resolve) => { releaseStart = resolve; });
	services.codex.stop = async () => {
		services.codex.stopped = true;
		releaseStart();
	};
	const router = new ProviderService(services, { operationTimeoutMs: 1_000 });
	const creating = router.createAgent(profile('codex', { agentId: 'late-create' }));
	const creationRejected = assert.rejects(creating, (error) => error?.code === 'PROVIDER_STOPPED');
	await new Promise((resolve) => setImmediate(resolve));
	await router.stop();
	await creationRejected;
	assert.equal(services.codex.created.length, 0, 'the continuation after the released start is fenced');
	assert.equal(router.getAgent('late-create'), null);
});

test('stop performs final backend cleanup after an already-entered create settles', async () => {
	const services = Object.fromEntries(['codex', 'gemini', 'kimi'].map((provider) => [provider, new FakeService(provider)]));
	let releaseCreate;
	let stopCalls = 0;
	services.codex.createAgent = async (value) => {
		await new Promise((resolve) => { releaseCreate = resolve; });
		services.codex.created.push(value);
		return { agentId: value.agentId, provider: value.provider };
	};
	services.codex.stop = async () => {
		stopCalls += 1;
		services.codex.created.length = 0;
	};
	const router = new ProviderService(services, { operationTimeoutMs: 100 });
	const creating = router.createAgent(profile('codex', { agentId: 'entered-create' }));
	const creationRejected = assert.rejects(creating, (error) => error?.code === 'PROVIDER_STOPPED');
	await new Promise((resolve) => setImmediate(resolve));
	const stopping = router.stop();
	await new Promise((resolve) => setImmediate(resolve));
	releaseCreate();
	await stopping;
	await creationRejected;
	assert.equal(services.codex.created.length, 0, 'no backend agent survives coordinator shutdown');
	assert.equal(stopCalls, 2, 'a final stop cleans mutations that settled after the initial abort');
});

test('an explicit empty-roster bootstrap initializes no provider', async () => {
	const services = Object.fromEntries(['codex', 'gemini', 'kimi'].map((provider) => [provider, new FakeService(provider)]));
	const started = [];
	for (const [provider, service] of Object.entries(services)) service.start = async () => { started.push(provider); };
	const router = new ProviderService(services);
	try {
		const snapshot = await router.bootstrapCatalog([]);
		assert.deepEqual(started, []);
		assert.deepEqual(snapshot.models, []);
	} finally {
		await router.stop();
	}
});

test('a hung provider is bounded while healthy provider reconciliation remains usable', async () => {
	const services = Object.fromEntries(['codex', 'gemini', 'kimi'].map((provider) => [provider, new FakeService(provider)]));
	let releaseCodex;
	services.codex.reconcile = async () => new Promise((resolve) => { releaseCodex = () => resolve({ valid: [], invalid: [], removed: [] }); });
	const router = new ProviderService(services, { operationTimeoutMs: 5 });
	const reconciling = router.reconcile([
		profile('codex', { agentId: 'codex-a' }),
		profile('gemini', { agentId: 'gemini-a' }),
	]);
	try {
		const outcome = await Promise.race([
			reconciling,
			new Promise((resolve) => setTimeout(() => resolve('still-pending'), 50)),
		]);
		assert.notEqual(outcome, 'still-pending', 'one hung provider cannot hold reconciliation forever');
		assert.deepEqual(outcome.valid.map(({ agentId }) => agentId), ['gemini-a']);
		assert.deepEqual(outcome.invalid.map((entry) => entry.profile.agentId), ['codex-a']);
		assert.equal(outcome.recovery.find(({ provider }) => provider === 'codex').state, 'degraded');
	} finally {
		releaseCodex?.();
		await Promise.allSettled([reconciling]);
		await router.stop();
	}
});

test('provider catalog aggregation settles failures and promotes a restored provider live', async () => {
	const services = Object.fromEntries(['codex', 'gemini', 'kimi'].map((provider) => [provider, new FakeService(provider)]));
	let codexAvailable = false;
	services.codex.catalog.refresh = async () => {
		if (!codexAvailable) throw Object.assign(new Error('Codex unavailable'), { code: 'PROVIDER_UNAVAILABLE' });
		return { refreshedAtEpochMs: 2, models: [{ id: 'codex-model', model: 'codex-model', displayName: 'Codex', reasoningEfforts: ['high'], serviceTiers: ['priority'] }] };
	};
	services.gemini.catalog.refresh = async () => ({
		refreshedAtEpochMs: 1,
		models: [{ id: 'gemini-model', model: 'gemini-model', displayName: 'Gemini', reasoningEfforts: ['high'], serviceTiers: [] }],
	});
	services.kimi.catalog.refresh = async () => ({ refreshedAtEpochMs: 1, models: [] });
	const router = new ProviderService(services, { operationTimeoutMs: 10 });

	const degraded = await router.catalog.refresh({ providers: ['codex', 'gemini'] });
	assert.deepEqual(degraded.models.map(({ provider, id }) => [provider, id]), [['gemini', 'gemini-model']]);
	assert.equal(degraded.recovery.find(({ provider }) => provider === 'codex').state, 'degraded');

	codexAvailable = true;
	const restored = await router.catalog.refresh({ providers: ['codex', 'gemini'], force: true });
	assert.deepEqual(restored.models.map(({ provider, id }) => [provider, id]), [
		['codex', 'codex-model'], ['gemini', 'gemini-model'],
	]);
	assert.equal(restored.recovery.find(({ provider }) => provider === 'codex').state, 'live');

	services.codex.catalog.refresh = async () => ({
		refreshedAtEpochMs: 3,
		models: [{ id: 'codex-model', model: 'codex-model' }],
	});
	const retained = await router.catalog.refresh({ providers: ['codex', 'gemini'], force: true });
	assert.deepEqual(retained.models.map(({ provider, id }) => [provider, id]), [
		['codex', 'codex-model'], ['gemini', 'gemini-model'],
	]);
	assert.equal(retained.source, 'last_valid');
	assert.equal(retained.recovery.find(({ provider }) => provider === 'codex').state, 'degraded');
	await router.stop();
});

test('builtin catalog discovery remains degraded with a bounded retry deadline', async () => {
	const services = Object.fromEntries(['codex', 'gemini', 'kimi'].map((provider) => [provider, new FakeService(provider)]));
	services.codex.catalog.stale = true;
	services.codex.catalog.refresh = async () => ({
		refreshedAtEpochMs: 0,
		models: [{ id: 'codex-model', model: 'codex-model', displayName: 'Codex', reasoningEfforts: ['high'], serviceTiers: ['priority'] }],
		source: 'builtin',
		recovery: { state: 'degraded', failureCode: 'REQUEST_TIMEOUT', consecutiveFailureCount: 1 },
	});
	const router = new ProviderService(services, { now: () => 10_000 });
	try {
		const snapshot = await router.catalog.refresh({ providers: ['codex'] });
		assert.equal(snapshot.source, 'builtin');
		const recovery = snapshot.recovery.find(({ provider }) => provider === 'codex');
		assert.equal(recovery.state, 'degraded');
		assert.equal(recovery.fallbackMode, 'builtin');
		assert.equal(recovery.failureCode, 'REQUEST_TIMEOUT');
		assert.equal(recovery.nextProbeAtEpochMs, 11_000);
	} finally {
		await router.stop();
	}
});

test('an empty live catalog is degraded and cannot replace the retained valid catalog', async () => {
	const services = Object.fromEntries(['codex', 'gemini', 'kimi'].map((provider) => [provider, new FakeService(provider)]));
	let empty = false;
	services.codex.catalog.refresh = async () => ({
		refreshedAtEpochMs: empty ? 2 : 1,
		models: empty ? [] : [{ id: 'codex-model', model: 'codex-model', displayName: 'Codex', reasoningEfforts: ['high'], serviceTiers: ['priority'] }],
		source: 'live',
	});
	const router = new ProviderService(services);
	try {
		await router.catalog.refresh({ providers: ['codex'] });
		empty = true;
		const retained = await router.catalog.refresh({ providers: ['codex'], force: true });
		assert.equal(retained.source, 'last_valid');
		assert.deepEqual(retained.models.map(({ id }) => id), ['codex-model']);
		assert.equal(retained.recovery.find(({ provider }) => provider === 'codex').state, 'degraded');
	} finally {
		await router.stop();
	}
});

test('a stale reconciliation cannot remove or overwrite sessions installed by its replacement', async () => {
	const services = Object.fromEntries(['codex', 'gemini', 'kimi'].map((provider) => [provider, new FakeService(provider)]));
	let calls = 0;
	let releaseFirst;
	services.codex.reconcile = async (records, options = {}) => {
		calls += 1;
		if (calls === 1) await new Promise((resolve) => { releaseFirst = resolve; });
		if (options.signal?.aborted) throw Object.assign(new Error('stale reconciliation'), { code: 'STALE_RECONCILIATION' });
		const desired = new Set(records.map(({ agentId }) => agentId));
		for (const created of [...services.codex.created]) {
			if (!desired.has(created.agentId)) await services.codex.removeAgent(created.agentId);
		}
		return { valid: records, invalid: [], removed: [], catalog: { models: [] }, aborted: options.signal?.aborted === true };
	};
	const router = new ProviderService(services, { operationTimeoutMs: 100 });
	const oldProfile = profile('codex', { agentId: 'old-agent' });
	const newProfile = profile('codex', { agentId: 'new-agent' });
	const stale = router.reconcile([oldProfile]);
	await new Promise((resolve) => setImmediate(resolve));
	const replacement = await router.reconcile([newProfile]);
	assert.deepEqual(replacement.valid.map(({ agentId }) => agentId), ['new-agent']);
	await router.createAgent(newProfile);
	assert.notEqual(router.getAgent('new-agent'), null);
	releaseFirst();
	await assert.rejects(stale, (error) => error?.code === 'STALE_RECONCILIATION');
	assert.notEqual(router.getAgent('new-agent'), null, 'the obsolete backend pass cannot delete the replacement session');
	await router.stop();
});

test('a stale successful reconciliation cannot mark a degraded provider restored', async () => {
	const services = Object.fromEntries(['codex', 'gemini', 'kimi'].map((provider) => [provider, new FakeService(provider)]));
	services.codex.catalog.refresh = async () => ({
		refreshedAtEpochMs: 1,
		models: [{ id: 'codex-model', model: 'codex-model', displayName: 'Codex', reasoningEfforts: ['high'], serviceTiers: ['fast'] }],
		source: 'live',
	});
	let calls = 0;
	let releaseStale;
	services.codex.reconcile = async (records) => {
		calls += 1;
		if (calls === 1) {
			await new Promise((resolve) => { releaseStale = resolve; });
			return { valid: records, invalid: [], removed: [] };
		}
		throw Object.assign(new Error('replacement provider unavailable'), { code: 'PROVIDER_UNAVAILABLE' });
	};
	const router = new ProviderService(services, { operationTimeoutMs: 100 });
	let restored = 0;
	router.on('providerRestored', () => { restored += 1; });
	const stale = router.reconcile([profile('codex')]);
	await new Promise((resolve) => setImmediate(resolve));
	const replacement = await router.reconcile([profile('codex')]);
	assert.equal(replacement.invalid.length, 1);
	assert.equal(router.recoverySnapshot().find(({ provider }) => provider === 'codex').state, 'degraded');
	releaseStale();
	await assert.rejects(stale, (error) => error?.code === 'STALE_RECONCILIATION');
	assert.equal(router.recoverySnapshot().find(({ provider }) => provider === 'codex').state, 'degraded');
	assert.equal(restored, 0);
	await router.stop();
});

test('failed desired-removal reconciliation retains hidden ownership until backend cleanup succeeds', async () => {
	const services = Object.fromEntries(['codex', 'gemini', 'kimi'].map((provider) => [provider, new FakeService(provider)]));
	const backend = new Map();
	let reconcileCalls = 0;
	let failCleanup = true;
	services.codex.createAgent = async (value) => {
		const agent = { agentId: value.agentId, provider: value.provider };
		backend.set(value.agentId, agent);
		return agent;
	};
	services.codex.getAgent = (agentId) => backend.get(agentId) ?? null;
	services.codex.removeAgent = async (agentId) => backend.delete(agentId);
	services.codex.reconcile = async (records) => {
		reconcileCalls += 1;
		if (failCleanup) throw Object.assign(new Error('cleanup transport failed'), { code: 'PROVIDER_UNAVAILABLE' });
		const desired = new Set(records.map(({ agentId }) => agentId));
		const removed = [];
		for (const agentId of backend.keys()) {
			if (desired.has(agentId)) continue;
			backend.delete(agentId);
			removed.push(agentId);
		}
		return { valid: records, invalid: [], removed };
	};
	const router = new ProviderService(services);
	const selected = profile('codex', { agentId: 'cleanup-retry' });
	await router.createAgent(selected);

	const failed = await router.reconcile([]);
	assert.equal(failed.valid.length, 0);
	assert.equal(router.getAgent(selected.agentId), null, 'removed desired state is no longer exposed');
	assert.notEqual(backend.get(selected.agentId), undefined, 'failed physical cleanup remains owned for retry');

	failCleanup = false;
	const retried = await router.reconcile([]);
	assert.deepEqual(retried.removed, [selected.agentId]);
	assert.equal(reconcileCalls, 2, 'the empty roster retries the provider that still owns cleanup');
	assert.equal(backend.has(selected.agentId), false);
	await router.stop();
});

test('empty-roster reconciliation does not fence finalized absent-agent history', async () => {
	const services = Object.fromEntries(['codex', 'gemini', 'kimi'].map((provider) => [provider, new FakeService(provider)]));
	services.codex.createAgent = async () => { throw Object.assign(new Error('create failed'), { code: 'PROVIDER_UNAVAILABLE' }); };
	const router = new ProviderService(services);
	for (let index = 0; index < 128; index += 1) {
		await assert.rejects(router.createAgent(profile('codex', { agentId: `absent-${index}` })));
	}

	let releaseCatalog;
	router.catalog.refresh = () => new Promise((resolve) => { releaseCatalog = resolve; });
	const reconciling = router.reconcile([]);
	await new Promise((resolve) => setImmediate(resolve));
	let createStarted = false;
	services.codex.createAgent = async (value) => {
		createStarted = true;
		return { agentId: value.agentId, provider: value.provider };
	};
	const creating = router.createAgent(profile('codex', { agentId: 'absent-0' }));
	await new Promise((resolve) => setImmediate(resolve));
	try {
		assert.equal(createStarted, true, 'finalized absent agents are not fenced behind unrelated empty-roster work');
	} finally {
		releaseCatalog({ refreshedAtEpochMs: 1, models: [], recovery: [] });
		await Promise.allSettled([reconciling, creating]);
		await router.stop();
	}
});

test('pruned mutation generations still fence a late timed-out create from the replacement session', async () => {
	const services = Object.fromEntries(['codex', 'gemini', 'kimi'].map((provider) => [provider, new FakeService(provider)]));
	const backend = new Map();
	let createCalls = 0;
	let releaseFirst;
	let repairFinished;
	const repaired = new Promise((resolve) => { repairFinished = resolve; });
	services.codex.getAgent = (agentId) => backend.get(agentId) ?? null;
	services.codex.removeAgent = async (agentId) => backend.delete(agentId);
	services.codex.createAgent = async (value) => {
		createCalls += 1;
		const agent = { agentId: value.agentId, provider: value.provider, generation: createCalls };
		if (createCalls === 1) await new Promise((resolve) => { releaseFirst = resolve; });
		backend.set(value.agentId, agent);
		return agent;
	};
	services.codex.replaceAgent = async (value) => {
		const agent = { agentId: value.agentId, provider: value.provider, generation: 3 };
		backend.set(value.agentId, agent);
		repairFinished(agent);
		return agent;
	};
	const timeouts = manualTimeouts();
	const router = new ProviderService(services, { operationTimeoutMs: 5, ...timeouts.options });
	const selected = profile('codex', { agentId: 'generation-fence' });
	try {
		const first = router.createAgent(selected);
		await new Promise((resolve) => setImmediate(resolve));
		timeouts.expireNext();
		await assert.rejects(first, (error) => error?.code === 'PROVIDER_TIMEOUT');
		const replacement = await router.createAgent(selected);
		assert.equal(replacement.generation, 2);

		releaseFirst();
		const repair = await Promise.race([
			repaired,
			new Promise((_, reject) => setTimeout(() => reject(new Error('late create was not repaired')), 100)),
		]);
		await new Promise((resolve) => setImmediate(resolve));
		assert.equal(repair.generation, 3);
		assert.equal(router.getAgent(selected.agentId), repair);
		assert.equal(backend.get(selected.agentId), repair);
	} finally {
		releaseFirst?.();
		await router.stop();
	}
});

test('provider recovery status retains the exact failing boundary', async () => {
	const services = Object.fromEntries(['codex', 'gemini', 'kimi', 'cursor'].map((provider) => [provider, new FakeService(provider)]));
	services.codex.createAgent = async () => { throw Object.assign(new Error('create failed'), { code: 'PROVIDER_UNAVAILABLE' }); };
	const router = new ProviderService(services);
	await assert.rejects(router.createAgent(profile('codex')));

	assert.equal(router.recoverySnapshot()[0].boundary, 'create');
	await router.stop();
});
