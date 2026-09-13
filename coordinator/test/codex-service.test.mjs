import assert from 'node:assert/strict';
import { EventEmitter } from 'node:events';
import test from 'node:test';

import { CodexService } from '../src/codex-service.mjs';
import { profileFingerprint } from '../src/provider-session.mjs';
import { MINECRAFT_DYNAMIC_TOOLS } from '../src/native-minecraft-tools.mjs';
import { finishDecisionJson } from './provider-decision-fixtures.mjs';

const MODEL = {
	id: 'gpt-5.6-sol',
	model: 'gpt-5.6-sol',
	supportedReasoningEfforts: [{ reasoningEffort: 'high' }],
	serviceTiers: [{ id: 'fast' }, { id: 'priority' }],
};

class FakeSharedTransport extends EventEmitter {
	calls = [];
	threadSequence = 0;
	turnSequence = 0;
	autoComplete = true;
	rejectInterrupt = false;
	holdTurnStart = false;
	turnStartResolvers = [];
	message = finishDecisionJson();

	async start() { this.calls.push({ method: '$start' }); }
	async stop() { this.calls.push({ method: '$stop' }); }
	notify(method, params) { this.calls.push({ method, params }); }
	respond(id, result) { this.calls.push({ method: '$respond', id, result }); }

	async request(method, params, options) {
		this.calls.push({ method, params, options });
		if (method === 'initialize') return { userAgent: 'fake' };
		if (method === 'model/list') return { data: [MODEL], nextCursor: null };
		if (method === 'thread/start') return { thread: { id: `thread-${++this.threadSequence}` } };
		if (method === 'turn/start') {
			if (this.holdTurnStart) {
				return new Promise((resolve) => this.turnStartResolvers.push(resolve));
			}
			const turnId = `turn-${++this.turnSequence}`;
			if (this.autoComplete) queueMicrotask(() => this.complete(params.threadId, turnId));
			return { turn: { id: turnId } };
		}
		if (method === 'turn/interrupt') {
			if (this.rejectInterrupt) throw Object.assign(new Error('turn already completed'), { code: 'RPC_ERROR' });
			return {};
		}
		if (method === 'turn/steer') return { turnId: params.expectedTurnId };
		throw new Error(`Unexpected method ${method}`);
	}

	complete(threadId, turnId) {
		this.emit('notification', { method: 'item/completed', params: { threadId, turnId, item: { type: 'agentMessage', text: this.message } } });
		this.emit('notification', { method: 'turn/completed', params: { threadId, turnId, turn: { id: turnId, status: 'completed' } } });
	}
}

test('Codex goal-spec turns use the supplied closed schema and parser without Minecraft tools', async () => {
	const transport = new FakeSharedTransport();
	transport.message = '{"requestId":"draft-1","summary":"Obtain iron.","predicate":{"type":"inventory_contains","itemId":"minecraft:iron_ingot","count":1}}';
	const service = new CodexService({ cwd: 'C:\\workspace' }, { transport });
	const agent = await service.createAgent(profile('goal-spec-agent'), { controlProtocol: 'goal_spec' });
	const outputSchema = { type: 'object', additionalProperties: false };
	const result = await agent.decide('translate exactly', {
		goalRevision: 0,
		outputSchema,
		parseOutput: JSON.parse,
		systemPrompt: '',
	});
	assert.equal(result.requestId, 'draft-1');
	const threadStart = transport.calls.find((call) => call.method === 'thread/start');
	assert.deepEqual(threadStart.params.dynamicTools, []);
	const turnStart = transport.calls.find((call) => call.method === 'turn/start');
	assert.equal(turnStart.params.outputSchema, outputSchema);
	await service.stop();
});

function profile(agentId) {
	return { agentId, model: 'gpt-5.6-sol', reasoningEffort: 'high', serviceTier: 'fast' };
}

test('Codex distinguishes submitted settings from provider-confirmed values', async () => {
	const transport = new FakeSharedTransport();
	const request = transport.request.bind(transport);
	transport.request = async (method, params, options) => {
		const result = await request(method, params, options);
		if (method === 'thread/start') return { ...result, model: params.model, serviceTier: params.serviceTier, reasoningEffort: 'low' };
		return result;
	};
	const service = new CodexService({ cwd: 'C:\\workspace' }, { transport });
	const agent = await service.createAgent(profile('confirmed'), { controlProtocol: 'arena_script' });
	assert.equal(agent.executionSettings.effective.model, 'gpt-5.6-sol');
	assert.equal(agent.executionSettings.effective.serviceTier, 'fast');
	assert.equal(agent.executionSettings.effective.reasoningEffort, null, 'the thread default is not the requested turn effort');
	await agent.decide('state', { goalRevision: 0 });
	assert.equal(agent.executionSettings.requested.reasoningEffort, 'high');
	assert.equal(agent.executionSettings.effective.reasoningEffort, null);
	assert.equal(agent.executionSettings.evidence.reasoningEffort, 'submitted');
	assert.equal(agent.executionSettings.transport, 'codex_app_server');
	const returned = agent.executionSettings;
	returned.requested.model = 'other';
	assert.equal(agent.executionSettings.requested.model, 'gpt-5.6-sol');
	await service.stop();
});

test('Codex rejects a confirmed different model before installing a session', async () => {
	const transport = new FakeSharedTransport();
	const request = transport.request.bind(transport);
	transport.request = async (method, params, options) => {
		const result = await request(method, params, options);
		return method === 'thread/start' ? { ...result, model: 'different-model' } : result;
	};
	const service = new CodexService({ cwd: 'C:\\workspace' }, { transport });
	await assert.rejects(service.createAgent(profile('mismatch')), (error) => error.code === 'PROVIDER_SETTINGS_MISMATCH');
	assert.equal(service.getAgent('mismatch'), null);
	await service.stop();
});

test('Codex service defaults dynamic profiles to the priority app-server tier', async () => {
	const transport = new FakeSharedTransport();
	const service = new CodexService({ cwd: 'C:\\workspace' }, { transport });
	await service.createAgent({ agentId: 'agent-default', model: 'gpt-5.6-sol', reasoningEffort: 'high' });
	const threadStart = transport.calls.find((call) => call.method === 'thread/start');
	assert.equal(threadStart.params.serviceTier, 'priority');
	await service.stop();
});

test('Codex catalog fallback contains only the configured exact launch profile', async () => {
	const transport = new FakeSharedTransport();
	transport.request = async (method, params, options) => {
		transport.calls.push({ method, params, options });
		if (method === 'initialize') return { userAgent: 'fake' };
		if (method === 'model/list') throw Object.assign(new Error('catalog offline'), { code: 'PROVIDER_UNAVAILABLE' });
		throw new Error(`Unexpected method ${method}`);
	};
	const service = new CodexService({
		cwd: 'C:\\workspace',
		launchProfile: { model: 'gpt-5.6-terra', reasoningEffort: 'xhigh', serviceTier: 'priority' },
	}, { transport });
	await service.start();
	try {
		assert.equal(transport.calls.some(({ method }) => method === 'model/list'), false);
		const snapshot = service.catalog.snapshot();
		assert.equal(snapshot.source, 'builtin');
		assert.deepEqual(snapshot.models.map(({ id, reasoningEfforts, serviceTiers }) => ({ id, reasoningEfforts, serviceTiers })), [{
			id: 'gpt-5.6-terra', reasoningEfforts: ['xhigh'], serviceTiers: ['priority'],
		}]);
	} finally {
		await service.stop();
	}
});

test('configured Codex profile creates a thread without waiting for live catalog discovery', async () => {
	const transport = new FakeSharedTransport();
	let catalogRequested = false;
	transport.request = async (method, params, options) => {
		transport.calls.push({ method, params, options });
		if (method === 'initialize') return { userAgent: 'fake' };
		if (method === 'model/list') {
			catalogRequested = true;
			return new Promise(() => {});
		}
		if (method === 'thread/start') return { thread: { id: 'thread-fast-start' } };
		throw new Error(`Unexpected method ${method}`);
	};
	const service = new CodexService({
		cwd: 'C:\\workspace',
		launchProfile: { model: 'gpt-5.6-sol', reasoningEffort: 'high', serviceTier: 'fast' },
	}, { transport });
	try {
		const agent = await service.createAgent(profile('configured-fast-start'));
		const bootstrapped = await service.bootstrapCatalog([profile('configured-fast-start')]);
		const reconciled = await service.reconcile([profile('configured-fast-start')]);
		assert.equal(agent.agentId, 'configured-fast-start');
		assert.equal(bootstrapped.source, 'builtin');
		assert.deepEqual(reconciled.valid.map(({ agentId }) => agentId), ['configured-fast-start']);
		assert.equal(catalogRequested, false);
		assert.equal(transport.calls.some(({ method }) => method === 'thread/start'), true);
	} finally {
		await service.stop();
	}
});

test('live discovery cannot evict the exact configured Codex launch profile', async () => {
	const transport = new FakeSharedTransport();
	transport.request = async (method, params, options) => {
		transport.calls.push({ method, params, options });
		if (method === 'initialize') return { userAgent: 'fake' };
		if (method === 'model/list') return {
			data: [{
				id: 'gpt-other', model: 'gpt-other',
				supportedReasoningEfforts: [{ reasoningEffort: 'medium' }], serviceTiers: [{ id: 'priority' }],
			}],
			nextCursor: null,
		};
		if (method === 'thread/start') return { thread: { id: 'thread-exact-after-refresh' } };
		throw new Error(`Unexpected method ${method}`);
	};
	const service = new CodexService({
		cwd: 'C:\\workspace',
		launchProfile: { model: 'gpt-5.6-sol', reasoningEffort: 'high', serviceTier: 'fast' },
	}, { transport });
	try {
		await service.start();
		const live = await service.catalog.refresh({ force: true });
		const agent = await service.createAgent(profile('exact-after-live-refresh'));
		assert.equal(live.source, 'live');
		assert.deepEqual(live.models.map(({ id }) => id), ['gpt-5.6-sol', 'gpt-other']);
		assert.equal(agent.agentId, 'exact-after-live-refresh');
	} finally {
		await service.stop();
	}
});

test('Codex initialization forwards its bounded startup deadline to the transport', async () => {
	const transport = new FakeSharedTransport();
	const service = new CodexService({ cwd: 'C:\\workspace', startupTimeoutMs: 321 }, { transport });
	await service.start();
	try {
		assert.deepEqual(transport.calls.find(({ method }) => method === 'initialize').options, { timeoutMs: 321 });
	} finally {
		await service.stop();
	}
});

test('Codex startup owns its deadline and a later probe starts a fresh transport attempt', async () => {
	const transport = new FakeSharedTransport();
	const releases = [];
	let starts = 0;
	let stops = 0;
	const liveAttempts = new Set();
	transport.start = () => new Promise((resolve) => {
		const attempt = ++starts;
		releases.push(() => { liveAttempts.add(attempt); resolve(); });
	});
	transport.stop = async () => { stops += 1; liveAttempts.clear(); };
	const catalog = {
		stale: false,
		refresh: async () => ({ models: [MODEL], source: 'live' }),
		assertSupported() {},
		reconcileProfiles: (records) => ({ valid: records, invalid: [] }),
	};
	const service = new CodexService({ cwd: 'C:\\workspace', startupTimeoutMs: 100 }, { transport, catalog });
	try {
		const first = await Promise.race([
			service.start().then(() => 'fulfilled', (error) => error?.code),
			new Promise((resolve) => setTimeout(() => resolve('outer-timeout'), 1_000)),
		]);
		assert.equal(first, 'PROVIDER_START_TIMEOUT');
		const second = service.start();
		await new Promise((resolve) => setImmediate(resolve));
		assert.equal(starts, 2);
		releases[0]();
		await new Promise((resolve) => setImmediate(resolve));
		assert.equal(service.started, false, 'the obsolete startup cannot mark the backend live');
		releases[1]();
		await second;
		assert.equal(service.started, true);
		assert.ok(stops >= 1);
	} finally {
		for (const release of releases) release();
		await service.stop();
	}
});

test('late cleanup from a timed-out startup cannot stop a newer shared-transport initialization', async () => {
	const transport = new FakeSharedTransport();
	let startCalls = 0;
	let releaseFirstStart;
	let releaseInitialize;
	let live = false;
	transport.start = async () => {
		startCalls += 1;
		if (startCalls === 1) {
			await new Promise((resolve) => { releaseFirstStart = resolve; });
		}
		live = true;
	};
	transport.stop = async () => { live = false; };
	transport.request = async (method, params, options) => {
		transport.calls.push({ method, params, options });
		if (method === 'initialize') {
			await new Promise((resolve) => { releaseInitialize = resolve; });
			if (!live) throw Object.assign(new Error('shared transport was stopped'), { code: 'TRANSPORT_STOPPED' });
			return { userAgent: 'fake' };
		}
		if (method === 'model/list') {
			if (!live) throw Object.assign(new Error('shared transport was stopped'), { code: 'TRANSPORT_STOPPED' });
			return { data: [MODEL], nextCursor: null };
		}
		throw new Error(`Unexpected method ${method}`);
	};
	const service = new CodexService({ cwd: 'C:\\workspace', startupTimeoutMs: 5 }, { transport });
	try {
		await assert.rejects(service.start(), (error) => error?.code === 'PROVIDER_START_TIMEOUT');
		const replacement = service.start();
		await new Promise((resolve) => setImmediate(resolve));
		assert.equal(startCalls, 2);
		assert.equal(typeof releaseInitialize, 'function', 'replacement reached authentication');

		releaseFirstStart();
		await new Promise((resolve) => setImmediate(resolve));
		releaseInitialize();
		await replacement;
		assert.equal(service.started, true);
	} finally {
		releaseFirstStart?.();
		releaseInitialize?.();
		await service.stop();
	}
});

test('Codex stop fences an already-entered thread creation from installing a late agent', async () => {
	const transport = new FakeSharedTransport();
	let releaseThread;
	const originalRequest = transport.request.bind(transport);
	transport.request = async (method, params, options) => {
		if (method !== 'thread/start') return originalRequest(method, params, options);
		transport.calls.push({ method, params, options });
		return new Promise((resolve) => { releaseThread = () => resolve({ thread: { id: 'late-thread' } }); });
	};
	const service = new CodexService({ cwd: 'C:\\workspace' }, { transport });
	const creating = service.createAgent(profile('late-agent'));
	await new Promise((resolve) => setImmediate(resolve));
	const stopping = service.stop();
	releaseThread();
	await assert.rejects(creating, (error) => error?.code === 'PROVIDER_STOPPED');
	await stopping;
	assert.equal(service.getAgent('late-agent'), null);
});

test('Codex service forwards app-server diagnostics to the coordinator error log', (t) => {
	const diagnostics = [];
	t.mock.method(console, 'error', (...values) => diagnostics.push(values.join(' ')));
	const transport = new FakeSharedTransport();
	new CodexService({ cwd: 'C:\\workspace' }, { transport });
	transport.emit('diagnostic', 'failed to spawn required runtime sidecar');
	assert.deepEqual(diagnostics, ['[codex-app-server] failed to spawn required runtime sidecar']);
});

test('Codex service sanitizes forwarded transport diagnostics at the console boundary', (t) => {
	const diagnostics = [];
	t.mock.method(console, 'error', (...values) => diagnostics.push(values.join(' ')));
	const transport = new FakeSharedTransport();
	new CodexService({ cwd: 'C:\\workspace' }, { transport });
	transport.emit('diagnostic', 'Authorization: Bearer forwarded-secret at C:\\private\\provider.log ' + 'x'.repeat(8_000));
	assert.equal(diagnostics.length, 1);
	assert.doesNotMatch(diagnostics[0], /forwarded-secret|private/);
	assert.ok(Buffer.byteLength(diagnostics[0], 'utf8') <= 4_128);
});

test('direct Codex callers default to native Minecraft tools when protocol is omitted', async () => {
	const transport = new FakeSharedTransport();
	let prepareCalls = 0;
	const service = new CodexService({ cwd: 'C:\\workspace' }, {
		transport,
		minecraftWorkspace: {
			async prepare() {
				prepareCalls += 1;
				return {
					cwd: 'C:\\shared\\minecraft-agent',
					selectedCapabilityRoots: [{ id: 'minecraft-control', location: { type: 'environment', environmentId: 'local', path: 'C:\\shared\\minecraft-agent\\.codex\\skills\\minecraft-control' } }],
				};
			},
		},
	});
	await service.createAgent(profile('agent-direct-default'));
	const threadStart = transport.calls.find((call) => call.method === 'thread/start');
	assert.equal(prepareCalls, 1);
	assert.equal(threadStart.params.cwd, 'C:\\shared\\minecraft-agent');
	assert.equal(threadStart.params.dynamicTools.some((tool) => tool.name === 'observe'), true);
	assert.deepEqual(threadStart.params.selectedCapabilityRoots, [{ id: 'minecraft-control', location: { type: 'environment', environmentId: 'local', path: 'C:\\shared\\minecraft-agent\\.codex\\skills\\minecraft-control' } }]);
	await service.stop();
});

test('Codex service shares one initialized transport across isolated agent threads', async () => {
	const transport = new FakeSharedTransport();
	const service = new CodexService({ cwd: 'C:\\workspace' }, { transport });
	const first = await service.createAgent(profile('agent-a'), { controlProtocol: 'arena_script' });
	const second = await service.createAgent(profile('agent-b'), { controlProtocol: 'arena_script' });
	assert.equal(transport.calls.filter((call) => call.method === 'initialize').length, 1);
	assert.equal(transport.calls.find((call) => call.method === 'model/list').params.includeHidden, false);
	assert.equal(transport.calls.filter((call) => call.method === 'thread/start').length, 2);
	await first.setGoalRevision(1);
	await second.setGoalRevision(3);
	const [firstDecision, secondDecision] = await Promise.all([
		first.decide('First observation.', { goalRevision: 1 }),
		second.decide('Second observation.', { goalRevision: 3 }),
	]);
	assert.equal(firstDecision.directive, 'finish');
	assert.equal(secondDecision.directive, 'finish');
	await service.stop();
});

test('Codex recovery reuses one exact profile and session and rejects profile mutation', async () => {
	const transport = new FakeSharedTransport();
	const service = new CodexService({ cwd: 'C:\\workspace' }, { transport });
	const selected = profile('agent-profile');
	const agent = await service.createAgent(selected, { recoverySummary: 'recover through the same session' });
	assert.equal(agent.sessionGeneration, 1);
	assert.equal(agent.profileFingerprint, profileFingerprint({ ...selected, provider: 'codex' }));
	assert.equal(await service.createAgent(selected, { recoverySummary: 'same profile retry' }), agent);
	for (const mutation of [
		{ model: 'gpt-5.6-other' },
		{ reasoningEffort: 'low' },
		{ serviceTier: 'priority' },
	]) {
		await assert.rejects(
			service.createAgent({ ...selected, ...mutation }),
			(error) => error?.code === 'AGENT_PROFILE_CONFLICT'
				&& error?.message.length <= 256
				&& !error?.message.includes('secret'),
		);
	}
	assert.equal(transport.calls.filter((call) => call.method === 'thread/start').length, 1);
	await service.stop();
});

test('Codex session metadata stays warm on the same exact profile across sequential turns', async () => {
	const transport = new FakeSharedTransport();
	const service = new CodexService({ cwd: 'C:\\workspace' }, { transport });
	const selected = profile('agent-session');
	const agent = await service.createAgent(selected, { controlProtocol: 'arena_script' });
	await agent.setGoalRevision(1);
	await agent.decide('first authoritative state', { goalRevision: 1 });
	const first = agent.sessionMetadata();
	await agent.decide('second authoritative state', { goalRevision: 1 });
	const second = agent.sessionMetadata();
	assert.equal(first.sessionGeneration, 1);
	assert.equal(first.sessionState, 'warm');
	assert.equal(first.continuation, 'durable');
	assert.deepEqual(second, first);
	await service.stop();
});

test('Codex session replacement increments generation and reports a reset reason without changing profile', async () => {
	const transport = new FakeSharedTransport();
	const service = new CodexService({ cwd: 'C:\\workspace' }, { transport });
	const selected = profile('agent-reset');
	const first = await service.createAgent(selected);
	await service.removeAgent(selected.agentId);
	const replacement = await service.createAgent(selected);
	assert.equal(replacement.sessionGeneration, 2);
	assert.equal(replacement.profileFingerprint, first.profileFingerprint);
	assert.equal(replacement.sessionMetadata().resetReason, 'session_replaced');
	await service.stop();
});

test('Codex transport loss fences the owning threads and coalesces one exact replacement generation', async () => {
	const transport = new FakeSharedTransport();
	const service = new CodexService({ cwd: 'C:\\workspace' }, { transport });
	const selected = profile('agent-transport-loss');
	const stale = await service.createAgent(selected, { controlProtocol: 'arena_script' });
	transport.emit('exit', Object.assign(new Error('app server exited'), { code: 'PROCESS_EXITED' }));
	await new Promise((resolve) => setImmediate(resolve));
	assert.equal(service.getAgent(selected.agentId), null);
	await assert.rejects(stale.decide('late work', { goalRevision: 0 }), (error) => error?.code === 'SESSION_INVALIDATED');
	const first = service.replaceAgent(selected, { controlProtocol: 'arena_script', expectedSessionGeneration: 1 });
	const second = service.replaceAgent(selected, { controlProtocol: 'arena_script', expectedSessionGeneration: 1 });
	const [replacement, duplicate] = await Promise.all([first, second]);
	assert.equal(replacement, duplicate);
	assert.equal(replacement.sessionGeneration, 2);
	assert.equal(replacement.profileFingerprint, stale.profileFingerprint);
	await service.stop();
});

test('Codex service gives concurrent thread creation enough time for a full arena roster', async () => {
	const transport = new FakeSharedTransport();
	const service = new CodexService({ cwd: 'C:\\workspace' }, { transport });
	await Promise.all(Array.from({ length: 16 }, (_, index) => service.createAgent(profile(`agent-${index + 1}`))));
	const starts = transport.calls.filter((call) => call.method === 'thread/start');
	assert.equal(starts.length, 16);
	assert.deepEqual(starts.map((call) => call.options), Array(16).fill({ timeoutMs: 60_000 }));
	await service.stop();
});

test('Codex service accepts the streamed agent-message contract when no completed message item arrives', async () => {
	const transport = new FakeSharedTransport();
	transport.autoComplete = false;
	const service = new CodexService({ cwd: 'C:\\workspace' }, { transport });
	const agent = await service.createAgent(profile('agent-streamed'), { controlProtocol: 'arena_script' });
	await agent.setGoalRevision(1);
	const decisionPromise = agent.decide('Observation.', { goalRevision: 1 });
	await Promise.resolve();
	const text = finishDecisionJson();
	transport.emit('notification', { method: 'item/agentMessage/delta', params: { threadId: 'thread-1', turnId: 'turn-1', itemId: 'message-1', delta: text } });
	transport.emit('notification', { method: 'turn/completed', params: { threadId: 'thread-1', turn: { id: 'turn-1', status: 'completed' } } });
	assert.equal((await decisionPromise).directive, 'finish');
	await service.stop();
});

test('Codex streams only visible agent output in real time and isolates verbose callback failures', async () => {
	const transport = new FakeSharedTransport();
	transport.autoComplete = false;
	const service = new CodexService({ cwd: 'C:\\workspace' }, { transport });
	const agent = await service.createAgent(profile('agent-verbose-output'), { controlProtocol: 'arena_script' });
	await agent.setGoalRevision(1);
	const events = [];
	const decisionPromise = agent.decide('Observation.', {
		goalRevision: 1,
		onVerbose(stage, message) {
			events.push({ stage, message });
			throw new Error('verbose callback failed');
		},
	});
	await new Promise((resolve) => setImmediate(resolve));
	transport.emit('notification', { method: 'item/reasoning/delta', params: { threadId: 'thread-1', turnId: 'turn-1', delta: 'hidden chain of thought' } });
	const text = finishDecisionJson();
	transport.emit('notification', { method: 'item/agentMessage/delta', params: { threadId: 'thread-1', turnId: 'turn-1', itemId: 'message-1', delta: text } });
	assert.equal(events.every(({ stage, message }) => stage === 'output' && message.length <= 256), true);
	assert.equal(events.map(({ message }) => message).join(''), text, 'bounded chunks preserve the full visible output before completion');
	const streamedEventCount = events.length;
	transport.emit('notification', { method: 'item/completed', params: { threadId: 'thread-1', turnId: 'turn-1', item: { type: 'agentMessage', text } } });
	transport.emit('notification', { method: 'item/completed', params: { threadId: 'thread-1', turnId: 'turn-1', item: { type: 'agentMessage', text } } });
	assert.equal(events.length, streamedEventCount, 'completed items do not repeat output that was already streamed');
	transport.emit('notification', { method: 'turn/completed', params: { threadId: 'thread-1', turn: { id: 'turn-1', status: 'completed' } } });
	assert.equal((await decisionPromise).directive, 'finish');
	await service.stop();
});

test('Codex malformed output records one final error row for the attempt', async () => {
	const transport = new FakeSharedTransport();
	transport.complete = function (threadId, turnId) {
		this.emit('notification', { method: 'item/completed', params: { threadId, turnId, item: { type: 'agentMessage', text: 'not-json' } } });
		this.emit('notification', { method: 'turn/completed', params: { threadId, turnId, turn: { id: turnId, status: 'completed' } } });
	};
	const service = new CodexService({ cwd: 'C:\\workspace' }, { transport });
	const agent = await service.createAgent(profile('agent-malformed-record'), { controlProtocol: 'arena_script' });
	await agent.setGoalRevision(1);
	const rows = [];
	const turnRecorder = { async record(row) { rows.push(row); } };
	await assert.rejects(agent.decide('Observation.', { goalRevision: 1, turnRecorder, attempt: 3, retry: true }), (error) => error?.code === 'MALFORMED_DECISION');
	assert.equal(rows.length, 1);
	assert.equal(rows[0].error?.code, 'MALFORMED_DECISION');
	assert.equal(rows[0].output, 'not-json');
	assert.equal(rows[0].attempt, 3);
	assert.ok(rows[0].timing.durationMs >= 0);
	assert.equal(rows[0].timing.apiDurationMs, null);
	assert.equal(rows[0].retry, true);
	await service.stop();
});

test('Codex records authoritative identity, scheduler wait, native token usage, and compaction', async () => {
	const transport = new FakeSharedTransport();
	transport.complete = function (threadId, turnId) {
		this.emit('notification', { method: 'item/completed', params: { threadId, turnId, item: { type: 'agentMessage', text: '{"summary":"Done","directive":"finish","source":null}' } } });
		this.emit('notification', { method: 'thread/tokenUsage/updated', params: { threadId, turnId, tokenUsage: { last: {
			inputTokens: 101, outputTokens: 23, reasoningOutputTokens: 7, cachedInputTokens: 41, cacheWriteInputTokens: 5, totalTokens: 131,
		} } } });
		this.emit('notification', { method: 'item/completed', params: { threadId, turnId, item: { type: 'contextCompaction' } } });
		this.emit('notification', { method: 'turn/completed', params: { threadId, turnId, turn: { id: turnId, status: 'completed' } } });
	};
	const service = new CodexService({ cwd: 'C:\\workspace' }, { transport });
	const agent = await service.createAgent(profile('agent-native-metrics'), { controlProtocol: 'arena_script' });
	await agent.setGoalRevision(1);
	const rows = [];
	await agent.decide('Observation.', { goalRevision: 1, queueWaitMs: 37, turnRecorder: { async record(row) { rows.push(row); } } });
	assert.equal(rows[0].agentId, 'agent-native-metrics');
	assert.equal(rows[0].timing.queueWaitMs, 37);
	assert.deepEqual(rows[0].tokens, { input: 101, output: 23, reasoning: 7, cached: 41, cacheWrite: 5 });
	assert.equal(rows[0].compaction, true);
	await service.stop();
});

test('Codex marks an explicit provider 429 as rate limited without inventing token usage', async () => {
	const transport = new FakeSharedTransport();
	transport.complete = function (threadId, turnId) {
		this.emit('notification', { method: 'turn/completed', params: { threadId, turnId, turn: { id: turnId, status: 'failed', error: { codexErrorInfo: 'usageLimitExceeded', message: 'quota unavailable' } } } });
	};
	const service = new CodexService({ cwd: 'C:\\workspace' }, { transport });
	const agent = await service.createAgent(profile('agent-rate-limited'), { controlProtocol: 'arena_script' });
	await agent.setGoalRevision(1);
	const rows = [];
	await assert.rejects(agent.decide('Observation.', { goalRevision: 1, attempt: 2, retry: true, turnRecorder: { async record(row) { rows.push(row); } } }));
	assert.equal(rows[0].rateLimited, true);
	assert.equal(rows[0].retry, true);
	assert.equal(Object.hasOwn(rows[0], 'tokens'), false);
	await service.stop();
});

test('Codex does not label overloaded or prose-only failures as rate limited', async () => {
	const transport = new FakeSharedTransport();
	transport.complete = function (threadId, turnId) {
		this.emit('notification', { method: 'turn/completed', params: { threadId, turnId, turn: { id: turnId, status: 'failed', error: { codexErrorInfo: 'serverOverloaded', message: 'prose mentions HTTP 429 and too many requests' } } } });
	};
	const service = new CodexService({ cwd: 'C:\\workspace' }, { transport });
	const agent = await service.createAgent(profile('agent-not-rate-limited'), { controlProtocol: 'arena_script' });
	await agent.setGoalRevision(1);
	const rows = [];
	await assert.rejects(agent.decide('Observation.', { goalRevision: 1, turnRecorder: { async record(row) { rows.push(row); } } }));
	assert.equal(Object.hasOwn(rows[0], 'rateLimited'), false);
	await service.stop();
});

test('Codex service cancels an over-budget streamed planner decision before parsing it', async () => {
	const transport = new FakeSharedTransport();
	transport.autoComplete = false;
	const service = new CodexService({ cwd: 'C:\\workspace', maxDecisionBytes: 32 }, { transport });
	const agent = await service.createAgent(profile('agent-bounded'), { controlProtocol: 'arena_script' });
	await agent.setGoalRevision(1);
	const events = [];
	const decisionPromise = agent.decide('Observation.', {
		goalRevision: 1,
		onVerbose(stage, message) { events.push({ stage, message }); },
	});
	await Promise.resolve();
	transport.emit('notification', { method: 'item/agentMessage/delta', params: { threadId: 'thread-1', turnId: 'turn-1', itemId: 'message-1', delta: 'x'.repeat(33) } });
	await assert.rejects(decisionPromise, (error) => error?.code === 'TURN_OUTPUT_LIMIT');
	assert.deepEqual(events, [], 'an over-budget provider body is rejected before it becomes visible output');
	assert.ok(transport.calls.some((call) => call.method === 'turn/interrupt' && call.params.turnId === 'turn-1'));
	await service.stop();
});

test('Codex threads use provider-scoped per-agent workspaces', async () => {
	const transport = new FakeSharedTransport();
	const prepared = [];
	const workspaceManager = {
		async prepare(provider, agentId) {
			prepared.push({ provider, agentId });
			return `C:\\\\agents\\\\${provider}\\\\${agentId}`;
		},
	};
	const service = new CodexService({ cwd: 'C:\\\\workspace' }, { transport, workspaceManager });
	await service.createAgent(profile('agent-a'), { controlProtocol: 'arena_script' });
	await service.createAgent(profile('agent-b'), { controlProtocol: 'arena_script' });

	assert.deepEqual(prepared, [
		{ provider: 'codex', agentId: 'agent-a' },
		{ provider: 'codex', agentId: 'agent-b' },
	]);
	assert.deepEqual(
		transport.calls.filter((call) => call.method === 'thread/start').map((call) => call.params.cwd),
		['C:\\\\agents\\\\codex\\\\agent-a', 'C:\\\\agents\\\\codex\\\\agent-b'],
	);
	await service.stop();
});

test('native Codex threads share the Minecraft workspace and selected skill root', async () => {
	const transport = new FakeSharedTransport();
	const prepared = [];
	const workspaceManager = {
		async prepare(provider, agentId) {
			return `C:\\agents\\${provider}\\${agentId}`;
		},
	};
	const minecraftWorkspace = {
		async prepare() {
			prepared.push('prepared');
			return {
				cwd: 'C:\\shared\\minecraft-agent',
				instructions: 'Minecraft-only instructions: use the native tools.',
				selectedCapabilityRoots: [{ id: 'minecraft-control', location: { type: 'environment', environmentId: 'local', path: 'C:\\shared\\minecraft-agent\\.codex\\skills\\minecraft-control' } }],
			};
		},
	};
	const service = new CodexService({ cwd: 'C:\\workspace' }, { transport, workspaceManager, minecraftWorkspace });
	await service.createAgent(profile('native-a'), { controlProtocol: 'native_tools' });
	await service.createAgent(profile('native-b'), { controlProtocol: 'native_tools' });

	assert.deepEqual(prepared, ['prepared', 'prepared']);
	for (const call of transport.calls.filter((entry) => entry.method === 'thread/start')) {
		assert.equal(call.params.cwd, 'C:\\shared\\minecraft-agent');
		assert.deepEqual(call.params.runtimeWorkspaceRoots, ['C:\\shared\\minecraft-agent']);
		assert.deepEqual(call.params.selectedCapabilityRoots, [{ id: 'minecraft-control', location: { type: 'environment', environmentId: 'local', path: 'C:\\shared\\minecraft-agent\\.codex\\skills\\minecraft-control' } }]);
		assert.equal(call.params.sandbox, 'read-only');
		assert.match(call.params.baseInstructions, /Minecraft-only instructions: use the native tools\./);
	}
	await service.stop();
});

test('prepares the isolated Minecraft Codex home before transport startup', async () => {
	const transport = new FakeSharedTransport();
	const order = [];
	transport.setEnvironment = (environment) => {
		order.push('environment');
		assert.equal(environment.CODEX_HOME, 'C:\\isolated\\minecraft\\.codex-home');
		assert.equal(environment.OPENAI_API_KEY, 'openai-key');
	};
	transport.start = async () => { order.push('start'); };
	const minecraftWorkspace = {
		async prepare() {
			order.push('prepare');
			return { cwd: 'C:\\isolated\\minecraft', codexHome: 'C:\\isolated\\minecraft\\.codex-home', selectedCapabilityRoots: [] };
		},
	};
	const service = new CodexService({ cwd: 'C:\\workspace', environment: { CODEX_HOME: 'C:\\Users\\lucas\\.codex', OPENAI_API_KEY: 'openai-key' } }, { transport, minecraftWorkspace });
	await service.start();
	assert.deepEqual(order, ['prepare', 'environment', 'start']);
	await service.stop();
});

test('concurrent stop paths interrupt a Codex turn exactly once and reject its late result', async () => {
	const transport = new FakeSharedTransport();
	transport.autoComplete = false;
	const service = new CodexService({ cwd: 'C:\\workspace' }, { transport });
	const agent = await service.createAgent(profile('agent-a'), { controlProtocol: 'arena_script' });
	await agent.setGoalRevision(1);
	const decision = agent.decide('Observation.', { goalRevision: 1 });
	await Promise.resolve();
	await Promise.all([agent.interrupt(), agent.interrupt(), agent.setGoalRevision(2)]);
	transport.complete('thread-1', 'turn-1');
	await assert.rejects(decision, (error) => error.code === 'STALE_PLAN');
	assert.equal(transport.calls.filter((call) => call.method === 'turn/interrupt').length, 1);
	await service.stop();
});

test('an abort-time stale interrupt rejection cannot escape as an unhandled process error', async () => {
	const transport = new FakeSharedTransport();
	transport.autoComplete = false;
	transport.rejectInterrupt = true;
	const service = new CodexService({ cwd: 'C:\\workspace' }, { transport });
	const agent = await service.createAgent(profile('agent-abort-race'), { controlProtocol: 'arena_script' });
	await agent.setGoalRevision(1);
	const controller = new AbortController();
	const decision = agent.decide('Observation.', { goalRevision: 1, signal: controller.signal });
	await Promise.resolve();
	controller.abort();
	transport.complete('thread-1', 'turn-1');
	await assert.rejects(decision, (error) => error.code === 'STALE_PLAN');
	await new Promise((resolve) => setImmediate(resolve));
	transport.rejectInterrupt = false;
	await service.stop();
});

test('Codex turn start is bounded by the planning timeout', async () => {
	const transport = new FakeSharedTransport();
	transport.holdTurnStart = true;
	const scheduled = [];
	const service = new CodexService({
		cwd: 'C:\\workspace',
		planningTimeoutMs: 25,
		schedule(callback, timeoutMs) {
			scheduled.push({ callback, timeoutMs });
			return callback;
		},
		cancelSchedule() {},
	}, { transport });
	const agent = await service.createAgent(profile('agent-turn-start-timeout'), { controlProtocol: 'arena_script' });
	await agent.setGoalRevision(1);
	const decision = agent.decide('Observation.', { goalRevision: 1 });
	await new Promise((resolve) => setImmediate(resolve));
	assert.deepEqual(transport.calls.find((call) => call.method === 'turn/start').options, { timeoutMs: 25 });
	assert.equal(scheduled.length, 1);
	assert.equal(scheduled[0].timeoutMs, 25);
	scheduled[0].callback();
	await assert.rejects(decision, (error) => error.code === 'PLANNING_TIMEOUT');
	await service.stop();
});

test('aborting before turn start settles the decision promptly', async () => {
	const transport = new FakeSharedTransport();
	transport.holdTurnStart = true;
	const service = new CodexService({ cwd: 'C:\\workspace' }, { transport });
	const agent = await service.createAgent(profile('agent-abort-before-turn-id'), { controlProtocol: 'arena_script' });
	await agent.setGoalRevision(1);
	const controller = new AbortController();
	const decision = agent.decide('Observation.', { goalRevision: 1, signal: controller.signal });
	await new Promise((resolve) => setImmediate(resolve));
	controller.abort();
	const result = await Promise.race([
		decision.then(() => 'resolved', (error) => error),
		new Promise((resolve) => setTimeout(() => resolve('timeout'), 50)),
	]);
	assert.notEqual(result, 'timeout');
	assert.equal(result.code, 'STALE_PLAN');
	await service.stop();
});

test('disposing before turn start settles the decision promptly', async () => {
	const transport = new FakeSharedTransport();
	transport.holdTurnStart = true;
	const service = new CodexService({ cwd: 'C:\\workspace' }, { transport });
	const agent = await service.createAgent(profile('agent-dispose-before-turn-id'), { controlProtocol: 'arena_script' });
	await agent.setGoalRevision(1);
	const decision = agent.decide('Observation.', { goalRevision: 1 });
	await new Promise((resolve) => setImmediate(resolve));
	await agent.dispose();
	const result = await Promise.race([
		decision.then(() => 'resolved', (error) => error),
		new Promise((resolve) => setTimeout(() => resolve('timeout'), 50)),
	]);
	assert.notEqual(result, 'timeout');
	assert.equal(result.code, 'AGENT_DISPOSED');
	await service.stop();
});

test('a completed notification cannot be mistaken for a delayed turn-start response', async () => {
	const transport = new FakeSharedTransport();
	transport.holdTurnStart = true;
	transport.autoComplete = false;
	const service = new CodexService({ cwd: 'C:\\workspace' }, { transport });
	const agent = await service.createAgent(profile('agent-notification-before-start-response'), { controlProtocol: 'arena_script' });
	await agent.setGoalRevision(1);
	const decision = agent.decide('Observation.', { goalRevision: 1 });
	await new Promise((resolve) => setImmediate(resolve));
	transport.complete('thread-1', 'turn-delayed');
	transport.turnStartResolvers[0]({ turn: { id: 'turn-delayed' } });
	assert.equal((await decision).directive, 'finish');
	await service.stop();
});

test('a late prior-turn completion cannot satisfy a new turn before its ID is known', async () => {
	const transport = new FakeSharedTransport();
	transport.holdTurnStart = true;
	transport.autoComplete = false;
	const service = new CodexService({ cwd: 'C:\\workspace' }, { transport });
	const agent = await service.createAgent(profile('agent-stale-notification'), { controlProtocol: 'arena_script' });
	await agent.setGoalRevision(1);
	const decision = agent.decide('Observation.', { goalRevision: 1 });
	await new Promise((resolve) => setImmediate(resolve));
	const staleText = finishDecisionJson({ summary: 'Stale prior turn' });
	transport.emit('notification', { method: 'item/completed', params: { threadId: 'thread-1', turnId: 'turn-old', item: { type: 'agentMessage', text: staleText } } });
	transport.emit('notification', { method: 'turn/completed', params: { threadId: 'thread-1', turnId: 'turn-old', turn: { id: 'turn-old', status: 'completed' } } });
	transport.turnStartResolvers[0]({ turn: { id: 'turn-new' } });
	await new Promise((resolve) => setImmediate(resolve));
	const currentText = finishDecisionJson({ summary: 'Current turn' });
	transport.emit('notification', { method: 'item/completed', params: { threadId: 'thread-1', turnId: 'turn-new', item: { type: 'agentMessage', text: currentText } } });
	transport.emit('notification', { method: 'turn/completed', params: { threadId: 'thread-1', turnId: 'turn-new', turn: { id: 'turn-new', status: 'completed' } } });
	assert.equal((await decision).summary, 'Current turn');
	await service.stop();
});

test('an empty completion before the turn-start response rejects without an unhandled process error', async (t) => {
	const transport = new FakeSharedTransport();
	transport.holdTurnStart = true;
	transport.autoComplete = false;
	const service = new CodexService({ cwd: 'C:\\workspace' }, { transport });
	t.after(() => service.stop());
	const agent = await service.createAgent(profile('agent-empty-before-start-response'), { controlProtocol: 'arena_script' });
	await agent.setGoalRevision(1);
	const decision = agent.decide('Observation.', { goalRevision: 1 });
	await new Promise((resolve) => setImmediate(resolve));
	transport.emit('notification', {
		method: 'turn/completed',
		params: { threadId: 'thread-1', turnId: 'turn-delayed', turn: { id: 'turn-delayed', status: 'completed' } },
	});
	await new Promise((resolve) => setImmediate(resolve));
	transport.turnStartResolvers[0]({ turn: { id: 'turn-delayed' } });
	await assert.rejects(decision, (error) => error.code === 'MISSING_AGENT_MESSAGE');
});

test('interrupting before turn start settles the decision promptly', async (t) => {
	const transport = new FakeSharedTransport();
	transport.holdTurnStart = true;
	const service = new CodexService({ cwd: 'C:\\workspace' }, { transport });
	t.after(() => service.stop());
	const agent = await service.createAgent(profile('agent-interrupt-before-turn-id'), { controlProtocol: 'arena_script' });
	await agent.setGoalRevision(1);
	const decision = agent.decide('Observation.', { goalRevision: 1 });
	await new Promise((resolve) => setImmediate(resolve));
	await agent.interrupt();
	const result = await Promise.race([
		decision.then(() => 'resolved', (error) => error),
		new Promise((resolve) => setTimeout(() => resolve('timeout'), 50)),
	]);
	assert.notEqual(result, 'timeout');
	assert.equal(result.code, 'STALE_PLAN');
	await service.stop();
});

test('interrupting as turn-start resolves still cleans up the late provider turn', async (t) => {
	const transport = new FakeSharedTransport();
	transport.holdTurnStart = true;
	transport.autoComplete = false;
	const service = new CodexService({ cwd: 'C:\\workspace' }, { transport });
	t.after(() => service.stop());
	const agent = await service.createAgent(profile('agent-interrupt-at-turn-start'), { controlProtocol: 'arena_script' });
	await agent.setGoalRevision(1);
	const decision = agent.decide('Observation.', { goalRevision: 1 });
	await new Promise((resolve) => setImmediate(resolve));
	transport.turnStartResolvers[0]({ turn: { id: 'turn-late' } });
	await agent.interrupt();
	await assert.rejects(decision, (error) => error.code === 'STALE_PLAN');
	await new Promise((resolve) => setImmediate(resolve));
	assert.equal(transport.calls.filter((call) => call.method === 'turn/interrupt').length, 1);
});

test('Codex shared transport supports sixteen concurrent native turns without listener warnings', () => {
	const transport = new FakeSharedTransport();
	new CodexService({ cwd: 'C:\\workspace' }, { transport });
	assert.equal(transport.getMaxListeners() >= 20, true);
});

test('native Codex turn executes a Minecraft tool and returns its result before turn completion', async () => {
	const transport = new FakeSharedTransport();
	transport.autoComplete = false;
	const service = new CodexService({ cwd: 'C:\\workspace' }, { transport });
	const agent = await service.createAgent(profile('agent-native'), { controlProtocol: 'native_tools' });
	await agent.setGoalRevision(1);

	const threadStart = transport.calls.find((call) => call.method === 'thread/start').params;
	assert.deepEqual(threadStart.dynamicTools, MINECRAFT_DYNAMIC_TOOLS);
	assert.equal(threadStart.baseInstructions.length < 1_500, true);

	const executed = [];
	const turn = agent.act('event: DM from Lucas: hi', {
		goalRevision: 1,
		executeTool: async (request) => {
			executed.push(request);
			return { state: 'SUCCEEDED', delivered: true };
		},
	});
	await Promise.resolve();
	transport.emit('serverRequest', {
		id: 71,
		method: 'item/tool/call',
		params: { threadId: 'thread-1', turnId: 'turn-1', callId: 'call-1', tool: 'say', arguments: { message: 'Hi Lucas!' } },
	});
	await new Promise((resolve) => setImmediate(resolve));

	assert.deepEqual(executed, [{
		agentId: 'agent-native', goalRevision: 1, threadId: 'thread-1', turnId: 'turn-1', callId: 'call-1',
		tool: { kind: 'action', actionType: 'chat', arguments: { message: 'Hi Lucas!', audience: 'public' } },
	}]);
	assert.deepEqual(transport.calls.find((call) => call.method === '$respond'), {
		method: '$respond', id: 71,
		result: { success: true, contentItems: [{ type: 'inputText', text: '{"state":"SUCCEEDED","delivered":true}' }] },
	});
	assert.equal(transport.calls.some((call) => call.method === 'turn/interrupt'), false);

	transport.emit('notification', { method: 'turn/completed', params: { threadId: 'thread-1', turn: { id: 'turn-1', status: 'completed' } } });
	assert.deepEqual(await turn, { status: 'completed', toolCalls: 1 });
	const turnStart = transport.calls.find((call) => call.method === 'turn/start').params;
	assert.equal(Object.hasOwn(turnStart, 'outputSchema'), false);
	await service.stop();
});

test('native Codex drains an in-flight tool before completing its turn', async (t) => {
	const transport = new FakeSharedTransport();
	transport.autoComplete = false;
	const service = new CodexService({ cwd: 'C:\\workspace' }, { transport });
	const agent = await service.createAgent(profile('agent-native-drain'), { controlProtocol: 'native_tools' });
	t.after(async () => { release?.(); await service.stop(); });
	await agent.setGoalRevision(1);
	let release;
	const gate = new Promise((resolve) => { release = resolve; });
	let started = 0;
	const turn = agent.act('event: wait for the action', {
		goalRevision: 1,
		executeTool: async () => {
			started += 1;
			await gate;
			return { state: 'SUCCEEDED' };
		},
	});
	await new Promise((resolve) => setImmediate(resolve));
	transport.emit('serverRequest', {
		id: 101,
		method: 'item/tool/call',
		params: { threadId: 'thread-1', turnId: 'turn-1', callId: 'drain-call', tool: 'wait', arguments: { durationMs: 1 } },
	});
	await new Promise((resolve) => setImmediate(resolve));
	transport.emit('notification', { method: 'turn/completed', params: { threadId: 'thread-1', turn: { id: 'turn-1', status: 'completed' } } });
	assert.equal(await Promise.race([
		turn.then(() => 'resolved'),
		new Promise((resolve) => setImmediate(() => resolve('pending'))),
	]), 'pending');
	assert.equal(started, 1);
	release();
	assert.deepEqual(await turn, { status: 'completed', toolCalls: 1 });
	assert.equal(transport.calls.some((call) => call.method === '$respond' && call.id === 101), true);
	await service.stop();
});

test('native Codex pauses the provider-silence deadline while Minecraft owns an accepted tool', async (t) => {
	const transport = new FakeSharedTransport();
	transport.autoComplete = false;
	const timers = [];
	const service = new CodexService({
		cwd: 'C:\\workspace',
		planningTimeoutMs: 25,
		schedule(callback, timeoutMs) {
			const handle = { callback, timeoutMs, cancelled: false };
			timers.push(handle);
			return handle;
		},
		cancelSchedule(handle) { handle.cancelled = true; },
	}, { transport });
	let releaseTool;
	const toolGate = new Promise((resolve) => { releaseTool = resolve; });
	t.after(async () => { releaseTool(); await service.stop(); });
	const agent = await service.createAgent(profile('agent-native-tool-deadline'), { controlProtocol: 'native_tools' });
	await agent.setGoalRevision(1);
	const turn = agent.act('event: perform a long physical action', {
		goalRevision: 1,
		executeTool: async () => { await toolGate; return { state: 'SUCCEEDED' }; },
	});
	await new Promise((resolve) => setImmediate(resolve));
	transport.emit('serverRequest', {
		id: 106,
		method: 'item/tool/call',
		params: { threadId: 'thread-1', turnId: 'turn-1', callId: 'long-tool', tool: 'moveTo', arguments: { x: 50, y: 64, z: 0, timeoutMs: 120_000 } },
	});
	await new Promise((resolve) => setImmediate(resolve));
	transport.emit('notification', { method: 'turn/completed', params: { threadId: 'thread-1', turn: { id: 'turn-1', status: 'completed' } } });
	assert.equal(timers.filter(({ timeoutMs, cancelled }) => timeoutMs === 25 && !cancelled).length, 0, 'provider silence has no live timer during the physical tool');
	for (const timer of timers.filter(({ timeoutMs }) => timeoutMs === 25)) timer.callback();
	assert.equal(await Promise.race([turn.then(() => 'settled', () => 'settled'), new Promise((resolve) => setImmediate(() => resolve('pending')))]), 'pending');
	releaseTool();
	assert.deepEqual(await turn, { status: 'completed', toolCalls: 1 });
});

test('native Codex serializes two tool calls within one turn', async (t) => {
	const transport = new FakeSharedTransport();
	transport.autoComplete = false;
	const service = new CodexService({ cwd: 'C:\\workspace' }, { transport });
	const agent = await service.createAgent(profile('agent-native-serial'), { controlProtocol: 'native_tools' });
	t.after(async () => { releaseFirst?.(); await service.stop(); });
	await agent.setGoalRevision(1);
	let releaseFirst;
	const firstGate = new Promise((resolve) => { releaseFirst = resolve; });
	let active = 0;
	let maxActive = 0;
	const started = [];
	const turn = agent.act('event: perform two actions', {
		goalRevision: 1,
		executeTool: async ({ callId }) => {
			started.push(callId);
			active += 1;
			maxActive = Math.max(maxActive, active);
			try {
				if (callId === 'serial-first') await firstGate;
				return { state: 'SUCCEEDED', callId };
			} finally {
				active -= 1;
			}
		},
	});
	await new Promise((resolve) => setImmediate(resolve));
	for (const [id, callId] of [[102, 'serial-first'], [103, 'serial-second']]) {
		transport.emit('serverRequest', {
			id,
			method: 'item/tool/call',
			params: { threadId: 'thread-1', turnId: 'turn-1', callId, tool: 'wait', arguments: { durationMs: 1 } },
		});
	}
	await new Promise((resolve) => setImmediate(resolve));
	assert.deepEqual(started, ['serial-first']);
	assert.equal(maxActive, 1);
	releaseFirst();
	await new Promise((resolve) => setImmediate(resolve));
	assert.deepEqual(started, ['serial-first', 'serial-second']);
	assert.equal(maxActive, 1);
	transport.emit('notification', { method: 'turn/completed', params: { threadId: 'thread-1', turn: { id: 'turn-1', status: 'completed' } } });
	assert.deepEqual(await turn, { status: 'completed', toolCalls: 2 });
	const responses = transport.calls
		.filter((call) => call.method === '$respond')
		.map((call) => ({ id: call.id, callId: JSON.parse(call.result.contentItems[0].text).callId }));
	assert.deepEqual(responses, [{ id: 102, callId: 'serial-first' }, { id: 103, callId: 'serial-second' }]);
	await service.stop();
});

test('native Codex ignores a late tool call after turn completion while draining', async (t) => {
	const transport = new FakeSharedTransport();
	transport.autoComplete = false;
	const service = new CodexService({ cwd: 'C:\\workspace' }, { transport });
	const agent = await service.createAgent(profile('agent-native-late-tool'), { controlProtocol: 'native_tools' });
	t.after(async () => { release?.(); await service.stop(); });
	await agent.setGoalRevision(1);
	let release;
	const gate = new Promise((resolve) => { release = resolve; });
	const started = [];
	const turn = agent.act('event: wait for the current action', {
		goalRevision: 1,
		executeTool: async ({ callId }) => {
			started.push(callId);
			await gate;
			return { state: 'SUCCEEDED', callId };
		},
	});
	await new Promise((resolve) => setImmediate(resolve));
	transport.emit('serverRequest', {
		id: 104,
		method: 'item/tool/call',
		params: { threadId: 'thread-1', turnId: 'turn-1', callId: 'current-call', tool: 'wait', arguments: { durationMs: 1 } },
	});
	await new Promise((resolve) => setImmediate(resolve));
	transport.emit('notification', { method: 'turn/completed', params: { threadId: 'thread-1', turn: { id: 'turn-1', status: 'completed' } } });
	transport.emit('serverRequest', {
		id: 105,
		method: 'item/tool/call',
		params: { threadId: 'thread-1', turnId: 'turn-1', callId: 'late-call', tool: 'wait', arguments: { durationMs: 1 } },
	});
	await new Promise((resolve) => setImmediate(resolve));
	assert.deepEqual(started, ['current-call']);
	assert.equal(await Promise.race([
		turn.then(() => 'resolved'),
		new Promise((resolve) => setImmediate(() => resolve('pending'))),
	]), 'pending');
	release();
	await turn;
	assert.equal(transport.calls.some((call) => call.method === '$respond' && call.id === 105), false);
	await service.stop();
});

test('native Codex exposes one complete public agent-message candidate without streaming raw chunks', async () => {
	const transport = new FakeSharedTransport();
	transport.autoComplete = false;
	const service = new CodexService({ cwd: 'C:\\workspace' }, { transport });
	const agent = await service.createAgent(profile('agent-native-output'), { controlProtocol: 'native_tools' });
	await agent.setGoalRevision(1);
	const events = [];
	const publicMessage = `${'x'.repeat(250)} actionId=native:agent-a:1:7`;
	const turn = agent.act('event: wait', {
		goalRevision: 1,
		executeTool: async () => ({ state: 'SUCCEEDED' }),
		onVerbose(stage, message) { events.push({ stage, message }); },
	});
	await new Promise((resolve) => setImmediate(resolve));
	transport.emit('notification', { method: 'item/reasoning/delta', params: {
		threadId: 'thread-1', turnId: 'turn-1', itemId: 'reasoning-1', delta: 'hidden reasoning',
	} });
	transport.emit('notification', { method: 'item/agentMessage/delta', params: {
		threadId: 'thread-1', turnId: 'turn-1', itemId: 'message-1', delta: publicMessage,
	} });
	transport.emit('notification', { method: 'item/completed', params: {
		threadId: 'thread-1', turnId: 'turn-1', item: { id: 'message-1', type: 'agentMessage', text: publicMessage },
	} });
	transport.emit('notification', { method: 'item/completed', params: {
		threadId: 'thread-1', turnId: 'turn-1', item: { id: 'message-2', type: 'agentMessage', text: 'Second completed-only item.' },
	} });
	transport.emit('notification', { method: 'item/completed', params: {
		threadId: 'thread-1', turnId: 'turn-1', item: { id: 'message-2', type: 'agentMessage', text: 'Second completed-only item.' },
	} });
	transport.emit('notification', { method: 'turn/completed', params: {
		threadId: 'thread-1', turn: { id: 'turn-1', status: 'completed' },
	} });
	assert.deepEqual(await turn, { status: 'completed', toolCalls: 0 });
	assert.deepEqual(events, [{ stage: 'agent_message', message: publicMessage }]);
	assert.doesNotMatch(JSON.stringify(events), /hidden reasoning/);
	await service.stop();
});

test('native Codex bounds the raw completed agent message before public preprocessing', async () => {
	const transport = new FakeSharedTransport();
	transport.autoComplete = false;
	const service = new CodexService({ cwd: 'C:\\workspace' }, { transport });
	const agent = await service.createAgent(profile('agent-native-bounded-output'), { controlProtocol: 'native_tools' });
	await agent.setGoalRevision(1);
	const events = [];
	const publicMessage = `Safe ${' '.repeat(100_000)}00000000-0000-0000-0000-000000000000`;
	const turn = agent.act('event: wait', {
		goalRevision: 1,
		executeTool: async () => ({ state: 'SUCCEEDED' }),
		onVerbose(stage, message) { events.push({ stage, message }); },
	});
	await new Promise((resolve) => setImmediate(resolve));
	transport.emit('notification', { method: 'item/completed', params: {
		threadId: 'thread-1', turnId: 'turn-1', item: { id: 'message-1', type: 'agentMessage', text: publicMessage },
	} });
	transport.emit('notification', { method: 'turn/completed', params: {
		threadId: 'thread-1', turn: { id: 'turn-1', status: 'completed' },
	} });
	assert.deepEqual(await turn, { status: 'completed', toolCalls: 0 });
	assert.deepEqual(events, [{ stage: 'agent_message', message: publicMessage.slice(0, 1_280) }]);
	await service.stop();
});

test('urgent input steers an active native turn without replacing its turn or tool executor', async (t) => {
	const transport = new FakeSharedTransport();
	transport.autoComplete = false;
	const service = new CodexService({ cwd: 'C:\\workspace' }, { transport });
	t.after(() => service.stop());
	const agent = await service.createAgent(profile('agent-native-steer'), { controlProtocol: 'native_tools' });
	await agent.setGoalRevision(1);
	const executed = [];
	const turn = agent.act('event: move toward stone', {
		goalRevision: 1,
		executeTool: async (request) => { executed.push(request.tool); return { state: 'SUCCEEDED' }; },
	});
	void turn.catch(() => {});
	await new Promise((resolve) => setImmediate(resolve));
	await agent.steer('urgent event: Lucas said stop and reply', { goalRevision: 1 });
	assert.deepEqual(transport.calls.find((call) => call.method === 'turn/steer')?.params, {
		threadId: 'thread-1',
		expectedTurnId: 'turn-1',
		input: [{ type: 'text', text: 'urgent event: Lucas said stop and reply' }],
	});
	transport.emit('serverRequest', {
		id: 81,
		method: 'item/tool/call',
		params: { threadId: 'thread-1', turnId: 'turn-1', callId: 'steered-say', tool: 'say', arguments: { message: 'Stopping now.' } },
	});
	await new Promise((resolve) => setImmediate(resolve));
	transport.emit('notification', { method: 'turn/completed', params: { threadId: 'thread-1', turn: { id: 'turn-1', status: 'completed' } } });
	assert.deepEqual(await turn, { status: 'completed', toolCalls: 1 });
	assert.equal(transport.calls.filter((call) => call.method === 'turn/start').length, 1);
	assert.deepEqual(executed, [{ kind: 'action', actionType: 'chat', arguments: { message: 'Stopping now.', audience: 'public' } }]);
});

test('native Codex interruption cleans up a turn whose start response arrives late', async () => {
	const transport = new FakeSharedTransport();
	transport.autoComplete = false;
	transport.holdTurnStart = true;
	const service = new CodexService({ cwd: 'C:\\workspace' }, { transport });
	const agent = await service.createAgent(profile('agent-native-late'), { controlProtocol: 'native_tools' });
	await agent.setGoalRevision(1);
	const turn = agent.act('event: wait', { goalRevision: 1, executeTool: async () => ({ state: 'SUCCEEDED' }) });
	await new Promise((resolve) => setImmediate(resolve));
	await agent.interrupt();
	await assert.rejects(turn, (error) => error?.code === 'STALE_PLAN');
	transport.turnStartResolvers[0]({ turn: { id: 'turn-native-late' } });
	await new Promise((resolve) => setImmediate(resolve));
	assert.equal(transport.calls.filter((call) => call.method === 'turn/interrupt' && call.params.turnId === 'turn-native-late').length, 1);
	await service.stop();
});

test('native Codex buffers a tool call that arrives before turn/start resolves', async () => {
	const transport = new FakeSharedTransport();
	transport.autoComplete = false;
	transport.holdTurnStart = true;
	const service = new CodexService({ cwd: 'C:\\workspace' }, { transport });
	const agent = await service.createAgent(profile('agent-native-buffered'), { controlProtocol: 'native_tools' });
	await agent.setGoalRevision(1);
	let executions = 0;
	const turn = agent.act('event: observe', { goalRevision: 1, executeTool: async () => { executions += 1; return { state: 'SUCCEEDED' }; } });
	await new Promise((resolve) => setImmediate(resolve));
	transport.emit('serverRequest', {
		id: 99,
		method: 'item/tool/call',
		params: { threadId: 'thread-1', turnId: 'turn-buffered', callId: 'call-buffered', tool: 'observe', arguments: {} },
	});
	assert.equal(executions, 0);
	transport.turnStartResolvers[0]({ turn: { id: 'turn-buffered' } });
	await new Promise((resolve) => setImmediate(resolve));
	assert.equal(executions, 1);
	assert.equal(transport.calls.some((call) => call.method === '$respond' && call.id === 99), true);
	transport.emit('notification', { method: 'turn/completed', params: { threadId: 'thread-1', turn: { id: 'turn-buffered', status: 'completed' } } });
	assert.deepEqual(await turn, { status: 'completed', toolCalls: 1 });
	await service.stop();
});

test('native Codex prewarm performs an observation-only turn and warms the exact session', async () => {
	const transport = new FakeSharedTransport();
	transport.autoComplete = false;
	const service = new CodexService({ cwd: 'C:\\workspace' }, { transport });
	const warming = service.prewarmAgent(profile('agent-prewarm'), { goalRevision: 0 });
	await new Promise((resolve) => setImmediate(resolve));
	transport.emit('serverRequest', {
		id: 121,
		method: 'item/tool/call',
		params: { threadId: 'thread-1', turnId: 'turn-1', callId: 'prewarm-observe', tool: 'observe', arguments: {} },
	});
	await new Promise((resolve) => setImmediate(resolve));
	transport.emit('notification', { method: 'turn/completed', params: { threadId: 'thread-1', turn: { id: 'turn-1', status: 'completed' } } });
	const agent = await warming;
	assert.equal(agent.sessionMetadata().sessionState, 'warm');
	assert.deepEqual(transport.calls.find((call) => call.method === '$respond' && call.id === 121)?.result, {
		success: true,
		contentItems: [{ type: 'inputText', text: '{"state":"READY"}' }],
	});
	assert.equal(transport.calls.filter((call) => call.method === 'thread/start').length, 1);
	await service.stop();
});

test('a real native event takes over in-flight prewarm without waiting or starting another turn', async (t) => {
	const transport = new FakeSharedTransport();
	transport.autoComplete = false;
	const service = new CodexService({ cwd: 'C:\\workspace' }, { transport });
	t.after(() => service.stop());
	const warming = service.prewarmAgent(profile('agent-prewarm-race'), { goalRevision: 0 });
	await new Promise((resolve) => setImmediate(resolve));
	const agent = await service.createAgent(profile('agent-prewarm-race'), { controlProtocol: 'native_tools' });
	const executed = [];
	const realTurn = agent.act('event: DM from Lucas: hi', {
		goalRevision: 0,
		executeTool: async (request) => { executed.push(request.tool); return { state: 'SUCCEEDED', delivered: true }; },
	});
	void realTurn.catch(() => {});
	await new Promise((resolve) => setImmediate(resolve));
	assert.deepEqual(transport.calls.find((call) => call.method === 'turn/steer')?.params, {
		threadId: 'thread-1',
		expectedTurnId: 'turn-1',
		input: [{ type: 'text', text: 'event: DM from Lucas: hi' }],
	});
	transport.emit('serverRequest', {
		id: 131,
		method: 'item/tool/call',
		params: { threadId: 'thread-1', turnId: 'turn-1', callId: 'real-say', tool: 'say', arguments: { message: 'Hi Lucas!' } },
	});
	await new Promise((resolve) => setImmediate(resolve));
	transport.emit('notification', { method: 'turn/completed', params: { threadId: 'thread-1', turn: { id: 'turn-1', status: 'completed' } } });
	assert.deepEqual(await realTurn, { status: 'completed', toolCalls: 1 });
	await warming;
	assert.deepEqual(executed, [{ kind: 'action', actionType: 'chat', arguments: { message: 'Hi Lucas!', audience: 'public' } }]);
	assert.equal(transport.calls.filter((call) => call.method === 'turn/start').length, 1);
	assert.equal(transport.calls.filter((call) => call.method === 'thread/start').length, 1);
});

test('an adopted prewarm turn forwards current progress and obeys the new cancellation signal', async (t) => {
	const transport = new FakeSharedTransport();
	transport.autoComplete = false;
	const service = new CodexService({ cwd: 'C:\\workspace' }, { transport });
	t.after(() => service.stop());
	const warming = service.prewarmAgent(profile('adopt-cancel'), { goalRevision: 0 });
	void warming.catch(() => {});
	await new Promise((resolve) => setImmediate(resolve));
	const agent = await service.createAgent(profile('adopt-cancel'), { controlProtocol: 'native_tools' });
	const controller = new AbortController();
	const progress = [];
	const turn = agent.act('observe', { goalRevision: 0, signal: controller.signal,
		executeTool: async () => ({ state: 'READY' }), onProgress: (event) => progress.push(event) });
	await new Promise((resolve) => setImmediate(resolve));
	transport.emit('serverRequest', { id: 1, method: 'item/tool/call', params: { threadId: 'thread-1', turnId: 'obsolete', callId: 'old', tool: 'observe', arguments: {} } });
	assert.equal(progress.length, 0);
	transport.emit('notification', { method: 'item/started', params: { threadId: 'thread-1', turnId: 'turn-1' } });
	assert.deepEqual(progress, [{ phase: 'provider' }]);
	const rejected = assert.rejects(turn, (error) => error.code === 'STALE_PLAN');
	controller.abort();
	await rejected;
	assert.equal(transport.calls.some((call) => call.method === 'turn/interrupt' && call.params.turnId === 'turn-1'), true);
});

test('a real native event starts cleanly after a conversation wake supersedes prewarm revision', async (t) => {
	const transport = new FakeSharedTransport();
	transport.autoComplete = false;
	const service = new CodexService({ cwd: 'C:\\workspace' }, { transport });
	t.after(() => service.stop());
	const warming = service.prewarmAgent(profile('agent-prewarm-revision-race'), { goalRevision: 0 });
	void warming.catch(() => {});
	await new Promise((resolve) => setImmediate(resolve));
	const agent = await service.createAgent(profile('agent-prewarm-revision-race'), { controlProtocol: 'native_tools' });
	await agent.setGoalRevision(1);
	const executed = [];
	const realTurn = agent.act('event: conversation wake from Lucas', {
		goalRevision: 1,
		executeTool: async (request) => { executed.push(request.tool); return { state: 'SUCCEEDED', delivered: true }; },
	});
	void realTurn.catch(() => {});
	await new Promise((resolve) => setImmediate(resolve));
	assert.equal(transport.calls.filter((call) => call.method === 'turn/steer').length, 0);
	assert.equal(transport.calls.filter((call) => call.method === 'turn/start').length, 2);
	assert.equal(transport.calls.filter((call) => call.method === 'turn/interrupt' && call.params.turnId === 'turn-1').length, 1);
	transport.emit('serverRequest', {
		id: 141,
		method: 'item/tool/call',
		params: { threadId: 'thread-1', turnId: 'turn-2', callId: 'real-say-after-wake', tool: 'say', arguments: { message: 'Hi Lucas!' } },
	});
	await new Promise((resolve) => setImmediate(resolve));
	transport.emit('notification', { method: 'turn/completed', params: { threadId: 'thread-1', turn: { id: 'turn-2', status: 'completed' } } });
	assert.deepEqual(await realTurn, { status: 'completed', toolCalls: 1 });
	assert.deepEqual(executed, [{ kind: 'action', actionType: 'chat', arguments: { message: 'Hi Lucas!', audience: 'public' } }]);
});
