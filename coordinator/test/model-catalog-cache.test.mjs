import assert from 'node:assert/strict';
import test from 'node:test';

import { ModelCatalogCache } from '../src/model-catalog-cache.mjs';

const MODEL = {
	id: 'gpt-5.6-sol',
	model: 'gpt-5.6-sol',
	supportedReasoningEfforts: [{ reasoningEffort: 'high' }, { reasoningEffort: 'xhigh' }],
	serviceTiers: [{ id: 'priority' }],
};

test('catalog refreshes once, normalizes capabilities, and reconciles profiles', async () => {
	let calls = 0;
	let now = 100;
	const cache = new ModelCatalogCache(async () => { calls += 1; return [MODEL]; }, { ttlMs: 50, now: () => now });
	await Promise.all([cache.refresh(), cache.refresh()]);
	assert.equal(calls, 1);
	assert.deepEqual(cache.find('gpt-5.6-sol').reasoningEfforts, ['high', 'xhigh']);
	const result = cache.reconcileProfiles([
		{ agentId: 'valid', model: 'gpt-5.6-sol', reasoningEffort: 'high' },
		{ agentId: 'invalid', model: 'missing', reasoningEffort: 'high' },
	]);
	assert.deepEqual(result.valid.map((entry) => entry.agentId), ['valid']);
	assert.deepEqual(result.invalid.map((entry) => entry.agentId), ['invalid']);
	now = 151;
	assert.equal(cache.stale, true);
});

test('catalog preserves Codex speed tiers and raw catalog naming variants', async () => {
	const cache = new ModelCatalogCache(async () => [{
		slug: 'gpt-5.6-sol',
		display_name: 'GPT-5.6-Sol',
		supported_reasoning_levels: [{ effort: 'low' }, { effort: 'ultra' }],
		service_tiers: [{ id: 'priority' }],
		additional_speed_tiers: ['fast'],
	}]);
	await cache.refresh();
	assert.deepEqual(cache.find('gpt-5.6-sol'), {
		id: 'gpt-5.6-sol',
		model: 'gpt-5.6-sol',
		displayName: 'GPT-5.6-Sol',
		reasoningEfforts: ['low', 'ultra'],
		serviceTiers: ['priority', 'fast'],
	});
	assert.equal(cache.assertSupported('gpt-5.6-sol', 'ultra', 'fast').id, 'gpt-5.6-sol');
});

test('catalog cache does not retain decision-like provider output fields', async () => {
	const cache = new ModelCatalogCache(async () => [{
		...MODEL,
		source: 'program.onUnhandledAttention("continue_and_notify");',
		decision: { directive: 'finish', status: 'completed' },
		completion: { claim: 'done' },
	}]);
	await cache.refresh();
	assert.deepEqual(cache.find(MODEL.id), {
		id: MODEL.id,
		model: MODEL.model,
		displayName: MODEL.id,
		reasoningEfforts: ['high', 'xhigh'],
		serviceTiers: ['priority'],
	});
});

test('catalog exposes a stable operator sequence and omits hidden provider models', async () => {
	const cache = new ModelCatalogCache(async () => [
		{
			id: 'gpt-5.6-sol', model: 'gpt-5.6-sol', hidden: false,
			supportedReasoningEfforts: ['ultra', 'low', 'xhigh', 'medium', 'high', 'max'],
			serviceTiers: ['fast', 'priority'],
		},
		{
			id: 'gpt-reserve', model: 'gpt-reserve', hidden: true,
			supportedReasoningEfforts: ['high'], serviceTiers: ['priority'],
		},
		{
			id: 'gpt-5.6-luna', model: 'gpt-5.6-luna', hidden: false,
			supportedReasoningEfforts: ['max', 'xhigh', 'high', 'medium', 'low'],
			serviceTiers: ['fast', 'priority'],
		},
		{
			id: 'gpt-5.6-terra', model: 'gpt-5.6-terra', hidden: false,
			supportedReasoningEfforts: ['high', 'low', 'max', 'medium', 'xhigh', 'ultra'],
			serviceTiers: ['priority', 'fast'],
		},
	]);
	const snapshot = await cache.refresh();
	assert.deepEqual(snapshot.models.map((model) => model.id), [
		'gpt-5.6-luna', 'gpt-5.6-terra', 'gpt-5.6-sol',
	]);
	assert.deepEqual(cache.find('gpt-5.6-luna').reasoningEfforts, ['low', 'medium', 'high', 'xhigh', 'max']);
	assert.deepEqual(cache.find('gpt-5.6-sol').serviceTiers, ['priority', 'fast']);
	assert.equal(cache.find('gpt-reserve'), null);
});

test('malformed refresh retains the previous valid catalog as last_valid', async () => {
	let response = [MODEL];
	const cache = new ModelCatalogCache(async () => response);
	const live = await cache.refresh();
	assert.equal(live.source, 'live');
	response = [{ ...MODEL }, { ...MODEL }];
	const retained = await cache.refresh({ force: true });
	assert.equal(retained.source, 'last_valid');
	assert.deepEqual(retained.models.map(({ id }) => id), ['gpt-5.6-sol']);
	assert.equal(cache.stale, true, 'a retained snapshot remains eligible for automatic refresh');
});

test('catalog retains its deterministic exact profile when live discovery recovers', async () => {
	let available = false;
	const builtin = {
		id: 'gpt-5.6-terra', model: 'gpt-5.6-terra', displayName: 'GPT-5.6 Terra',
		supportedReasoningEfforts: ['xhigh'], serviceTiers: ['priority'],
	};
	const liveModel = {
		id: 'gpt-5.6-sol', model: 'gpt-5.6-sol', displayName: 'GPT-5.6 Sol',
		supportedReasoningEfforts: ['high'], serviceTiers: ['fast'],
	};
	const cache = new ModelCatalogCache(async () => {
		if (!available) throw Object.assign(new Error('offline'), { code: 'PROVIDER_UNAVAILABLE' });
		return [liveModel];
	}, { builtinModels: [builtin] });

	const fallback = await cache.refresh();
	assert.equal(fallback.source, 'builtin');
	assert.deepEqual(fallback.models.map(({ id, reasoningEfforts, serviceTiers }) => ({ id, reasoningEfforts, serviceTiers })), [{
		id: 'gpt-5.6-terra', reasoningEfforts: ['xhigh'], serviceTiers: ['priority'],
	}]);
	assert.equal(cache.find('gpt-5.6-sol'), null, 'fallback never invents or substitutes another profile');

	available = true;
	const live = await cache.refresh({ force: true });
	assert.equal(live.source, 'live');
	assert.deepEqual(live.models.map(({ id }) => id), ['gpt-5.6-terra', 'gpt-5.6-sol']);
	assert.equal(cache.assertSupported('gpt-5.6-terra', 'xhigh', 'priority').id, 'gpt-5.6-terra');
});

test('a timed-out catalog attempt is evicted and its late result cannot replace a fresh generation', async () => {
	const releases = [];
	const deadlines = [];
	let loaderCalls = 0;
	const cache = new ModelCatalogCache(({ signal } = {}) => new Promise((resolve) => {
		loaderCalls += 1;
		const call = loaderCalls;
		releases.push(() => resolve([{ ...MODEL, displayName: `attempt-${call}`, aborted: signal?.aborted === true }]));
	}), {
		builtinModels: [MODEL],
		refreshTimeoutMs: 5,
		scheduleTimeout(callback) {
			const deadline = { callback, cancelled: false };
			deadlines.push(deadline);
			return deadline;
		},
		cancelTimeout(deadline) { deadline.cancelled = true; },
	});
	try {
		const firstAttempt = cache.refresh({ force: true });
		await new Promise((resolve) => setImmediate(resolve));
		deadlines[0].callback();
		const first = await firstAttempt;
		assert.equal(first.source, 'builtin');
		assert.equal(deadlines[0].cancelled, true);

		const secondAttempt = cache.refresh({ force: true });
		await new Promise((resolve) => setImmediate(resolve));
		assert.equal(loaderCalls, 2, 'a timed-out loader promise is not cached into the next probe');
		releases[0]();
		await new Promise((resolve) => setImmediate(resolve));
		assert.equal(cache.snapshot().source, 'builtin', 'the obsolete loader cannot promote its late result live');
		releases[1]();
		assert.equal((await secondAttempt).source, 'live');
		assert.equal(deadlines[1].cancelled, true);
	} finally {
		for (const release of releases) release();
	}
});
