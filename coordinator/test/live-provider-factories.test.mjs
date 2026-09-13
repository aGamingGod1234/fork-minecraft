import assert from 'node:assert/strict';
import test from 'node:test';

import { createLiveProviderFactory } from '../src/benchmark/live-provider-factories.mjs';

const PROFILE = Object.freeze({
	provider: 'kimi',
	model: 'kimi-code/k3',
	reasoningEffort: 'high',
	serviceTier: 'fast',
});

function fakeService({ provider = 'kimi', catalogStale = false, startError = null, createError = null } = {}) {
	const calls = [];
	const createOptions = [];
	const agents = new Map();
	return {
		provider,
		calls,
		createOptions,
		catalog: {
			stale: catalogStale,
			async refresh() { calls.push('catalog.refresh'); return { provider, models: [] }; },
		},
		async start() { calls.push('start'); if (startError) throw startError; },
		async stop() { calls.push('stop'); },
		async createAgent(profile, options) {
			calls.push(['createAgent', profile]);
			createOptions.push(options);
			if (createError) throw createError;
			const agent = { provider, agentId: profile.agentId, async setGoalRevision() {}, async dispose() { calls.push(['agent.dispose', profile.agentId]); } };
			agents.set(profile.agentId, agent);
			return agent;
		},
		async removeAgent(agentId) { calls.push(['removeAgent', agentId]); agents.delete(agentId); return true; },
		getAgent(agentId) { return agents.get(agentId) ?? null; },
	};
}

test('creates a Codex preflight session with the ArenaScript control protocol', async () => {
	const service = fakeService({ provider: 'codex' });
	const factory = createLiveProviderFactory('codex', { service });

	const provider = await factory({ provider: 'codex', model: 'gpt-5.6-sol', reasoningEffort: 'high', serviceTier: 'fast' });

	assert.equal(provider.available, true);
	assert.equal(service.createOptions[0].controlProtocol, 'arena_script');
	await provider.stop();
});

test('creates a Codex benchmark session with the ArenaScript control protocol', async () => {
	const service = fakeService({ provider: 'codex' });
	const factory = createLiveProviderFactory('codex', { service });
	const profile = { provider: 'codex', model: 'gpt-5.6-sol', reasoningEffort: 'high', serviceTier: 'fast' };
	const provider = await factory(profile);

	await provider.createAgent({ ...profile, agentId: 'benchmark-agent' });

	assert.equal(service.createOptions.at(-1)?.controlProtocol, 'arena_script');
	await provider.stop();
});

test('routes an exact provider to only the selected injected service', async () => {
	let selected;
	const service = fakeService();
	const factory = createLiveProviderFactory('kimi', {
		serviceFactory(context) { selected = context; return service; },
		probe: async () => {},
	});

	const provider = await factory(PROFILE);

	assert.equal(provider.available, true);
	assert.equal(provider.provider, PROFILE.provider);
	assert.equal(provider.model, PROFILE.model);
	assert.equal(provider.reasoningEffort, PROFILE.reasoningEffort);
	assert.equal(provider.serviceTier, PROFILE.serviceTier);
	assert.deepEqual(provider.providerProfile, PROFILE);
	assert.equal(selected.provider, 'kimi');
	assert.equal(selected.environment.ARENA_AGENT_BRIDGE_SECRET, undefined);
	const createCall = service.calls.find((entry) => Array.isArray(entry) && entry[0] === 'createAgent');
	assert.equal(createCall[1].provider, 'kimi');
	assert.equal(createCall[1].model, PROFILE.model);
	assert.equal(createCall[1].reasoningEffort, PROFILE.reasoningEffort);
	assert.ok(service.calls.some((entry) => Array.isArray(entry) && entry[0] === 'removeAgent'));
	await provider.stop();
});

test('rejects a mismatched profile without fallback or service construction', async () => {
	let constructed = false;
	const factory = createLiveProviderFactory('codex', {
		serviceFactory() { constructed = true; return fakeService({ provider: 'codex' }); },
	});

	await assert.rejects(() => factory(PROFILE), (error) => error.code === 'PROVIDER_MISMATCH');
	assert.equal(constructed, false);
});

test('reports configured catalog fallback separately from a successful live session', async () => {
	const service = fakeService({ catalogStale: true });
	const factory = createLiveProviderFactory('kimi', { service, probe: async () => {} });

	const provider = await factory(PROFILE);

	assert.equal(provider.available, true);
	assert.equal(provider.catalogFallback, true);
	assert.equal(provider.preflight.phase, 'first_turn');
	assert.equal(provider.preflight.realAvailability, true);
	await provider.stop();
});

test('rejects production-disabled Gemini before constructing a live service', () => {
	let constructed = false;
	assert.throws(() => createLiveProviderFactory('gemini', {
		serviceFactory() { constructed = true; return fakeService({ provider: 'gemini' }); },
	}), /codex, kimi/i);
	assert.equal(constructed, false);
});

test('classifies startup failures through shared diagnostic redaction and cleans up', async () => {
	const secret = 'fixture-start-token-123';
	const service = fakeService({ startError: new Error(`Authorization: Bearer ${secret} at C:\\private\\provider.json`) });
	const factory = createLiveProviderFactory('kimi', {
		service,
		environment: { KIMI_API_KEY: secret, ARENA_AGENT_BRIDGE_SECRET: 'bridge-secret' },
	});

	const result = await factory(PROFILE);

	assert.equal(result.available, false);
	assert.equal(result.code, 'PROVIDER_UNAVAILABLE');
	assert.equal(result.preflight.phase, 'startup');
	assert.equal(result.preflight.realAvailability, false);
	assert.match(result.reason, /REDACTED/);
	assert.equal(result.reason.includes(secret), false);
	assert.doesNotMatch(result.reason, /private/);
	assert.ok(Buffer.byteLength(result.reason, 'utf8') <= 512);
	assert.deepEqual(service.calls, ['start', 'stop']);
});

test('classifies session creation failures and stops the service', async () => {
	const service = fakeService({ createError: Object.assign(new Error('ACP session/new failed'), { code: 'INVALID_SESSION' }) });
	const factory = createLiveProviderFactory('kimi', { service, probe: async () => {} });

	const result = await factory(PROFILE);

	assert.equal(result.available, false);
	assert.equal(result.preflight.phase, 'session');
	assert.equal(result.preflight.errorCode, 'INVALID_SESSION');
	assert.deepEqual(service.calls, ['start', 'catalog.refresh', ['createAgent', { ...PROFILE, agentId: result.preflight.agentId }], 'stop']);
});

test('classifies first-turn probe failures and removes the probe agent', async () => {
	const service = fakeService();
	const factory = createLiveProviderFactory('kimi', {
		service,
		probe: async () => { throw Object.assign(new Error('provider turn failed'), { code: 'TURN_FAILED' }); },
	});

	const result = await factory(PROFILE);

	assert.equal(result.available, false);
	assert.equal(result.preflight.phase, 'first_turn');
	assert.equal(result.preflight.errorCode, 'TURN_FAILED');
	assert.ok(service.calls.some((entry) => Array.isArray(entry) && entry[0] === 'removeAgent'));
	assert.equal(service.calls.at(-1), 'stop');
});

test('aborting a bounded probe still removes the session and stops the service', async () => {
	const service = fakeService();
	const controller = new AbortController();
	const factory = createLiveProviderFactory('kimi', {
		service,
		probe: ({ signal }) => new Promise((resolve, reject) => {
			signal.addEventListener('abort', () => reject(signal.reason), { once: true });
		}),
	});
	const pending = factory(PROFILE, { signal: controller.signal });
	await new Promise((resolve) => setImmediate(resolve));
	controller.abort(new Error('benchmark aborted'));
	const result = await pending;

	assert.equal(result.available, false);
	assert.equal(result.preflight.phase, 'first_turn');
	assert.equal(result.preflight.errorCode, 'ABORT_ERR');
	assert.ok(service.calls.some((entry) => Array.isArray(entry) && entry[0] === 'removeAgent'));
	assert.equal(service.calls.at(-1), 'stop');
});
