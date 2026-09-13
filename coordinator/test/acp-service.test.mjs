import assert from 'node:assert/strict';
import { EventEmitter } from 'node:events';
import test from 'node:test';

import { AcpProviderService, buildAcpLaunch } from '../src/acp-service.mjs';
import { profileFingerprint } from '../src/provider-session.mjs';
import { replaceDecisionJson } from './provider-decision-fixtures.mjs';

const DECISION = replaceDecisionJson();

class FakeAcpTransport extends EventEmitter {
	constructor(configOptions, { configOptionsAfterModel = null } = {}) {
		super();
		this.configOptions = configOptions;
		this.configOptionsAfterModel = configOptionsAfterModel;
		this.calls = [];
		this.started = false;
		this.message = DECISION;
		this.hiddenMessage = null;
		this.promptResponse = { stopReason: 'end_turn' };
		this.promptGate = null;
	}

	async start() { this.started = true; }
	async stop() { this.started = false; }
	notify(method, params) { this.calls.push({ kind: 'notification', method, params }); }
	async request(method, params) {
		this.calls.push({ kind: 'request', method, params });
		if (method === 'initialize') return { protocolVersion: 1, agentInfo: { name: 'fake-acp', version: '1' } };
		if (method === 'session/new') return { sessionId: 'session-1', configOptions: this.configOptions };
		if (method === 'session/set_config_option') {
			if (params.configId === 'model' && this.configOptionsAfterModel !== null) {
				this.configOptions = this.configOptionsAfterModel;
			}
			this.configOptions = this.configOptions.map((option) => option.id === params.configId ? { ...option, currentValue: params.value } : option);
			return { configOptions: this.configOptions };
		}
		if (method === 'session/prompt') {
			if (this.hiddenMessage !== null) queueMicrotask(() => this.emit('notification', {
				method: 'session/update',
				params: { sessionId: 'session-1', update: { sessionUpdate: 'agent_thought_chunk', content: { type: 'text', text: this.hiddenMessage } } },
			}));
			queueMicrotask(() => this.emit('notification', {
				method: 'session/update',
				params: { sessionId: 'session-1', update: { sessionUpdate: 'agent_message_chunk', content: { type: 'text', text: this.message } } },
			}));
			if (this.promptGate !== null) await this.promptGate;
			await new Promise((resolve) => setImmediate(resolve));
			return this.promptResponse;
		}
		throw new Error(`Unexpected ACP method ${method}`);
	}
}

function options({ thinkingValues = ['low', 'medium', 'high'] } = {}) {
	return [
		{ id: 'model', category: 'model', type: 'select', currentValue: 'auto', options: [{ value: 'auto', name: 'Auto' }, { value: 'gemini-pro', name: 'Gemini Pro' }] },
		{ id: 'thinking', category: 'thought_level', type: 'select', currentValue: thinkingValues[0], options: thinkingValues.map((value) => ({ value, name: value })) },
	];
}

test('ACP reports confirmed settings and keeps returned metadata independent of the session', async () => {
	const service = new AcpProviderService({ provider: 'gemini', cwd: 'C:\\workspace', models: ['gemini-pro'] }, { transportFactory: () => new FakeAcpTransport(options()) });
	const selected = { agentId: 'settings', provider: 'gemini', model: 'gemini-pro', reasoningEffort: 'high', serviceTier: 'priority' };
	const agent = await service.createAgent(selected);
	const settings = agent.executionSettings;
	assert.deepEqual(settings.effective, { provider: 'gemini', model: 'gemini-pro', reasoningEffort: 'high', serviceTier: null, thinkingMode: null });
	assert.equal(settings.evidence.reasoningEffort, 'provider_reported');
	settings.requested.model = 'other';
	assert.equal(agent.executionSettings.requested.model, selected.model);
	const replacement = await service.replaceAgent(selected);
	assert.deepEqual(replacement.executionSettings, agent.executionSettings);
	await service.stop();
});

test('ACP refuses a provider-reported model substitution instead of changing the selected profile', async () => {
	const transport = new FakeAcpTransport(options());
	const request = transport.request.bind(transport);
	transport.request = async (method, params) => method === 'session/set_config_option' ? { configOptions: transport.configOptions } : request(method, params);
	const service = new AcpProviderService({ provider: 'gemini', cwd: 'C:\\workspace', models: ['gemini-pro'] }, { transportFactory: () => transport });
	await assert.rejects(service.createAgent({ agentId: 'mismatch', provider: 'gemini', model: 'gemini-pro', reasoningEffort: 'high' }), (error) => error.code === 'PROVIDER_SETTINGS_MISMATCH');
	assert.equal(transport.started, false);
	assert.equal(service.getAgent('mismatch'), null);
	await service.stop();
});

test('Gemini ACP sessions apply the exact model and thinking level and parse planner output', async () => {
	const transport = new FakeAcpTransport(options());
	const service = new AcpProviderService({ provider: 'gemini', cwd: 'C:\\workspace', models: ['auto', 'gemini-pro'] }, { transportFactory: () => transport });
	const agent = await service.createAgent(
		{ agentId: 'gemini-a', provider: 'gemini', model: 'gemini-pro', reasoningEffort: 'high' },
		{ recoverySummary: 'Previous movement timed out.' },
	);
	await agent.setGoalRevision(2);
	const decision = await agent.decide('authoritative state', { goalRevision: 2 });
	assert.equal(decision.directive, 'replace');
	assert.deepEqual(transport.calls.filter((call) => call.method === 'session/set_config_option').map((call) => call.params), [
		{ sessionId: 'session-1', configId: 'model', value: 'gemini-pro' },
		{ sessionId: 'session-1', configId: 'thinking', value: 'high' },
	]);
	const prompt = transport.calls.find((call) => call.method === 'session/prompt').params.prompt[0].text;
	assert.match(prompt, /strategic author for one Minecraft player/i);
	assert.match(prompt, /authoritative state/);
	assert.match(prompt, /Previous movement timed out/);
	await service.stop();
});

test('ACP structured turns use an isolated prompt and caller-supplied parser', async () => {
	const transport = new FakeAcpTransport(options());
	transport.message = '{"requestId":"draft-1"}';
	const service = new AcpProviderService({ provider: 'gemini', cwd: 'C:\\workspace', models: ['auto', 'gemini-pro'] }, { transportFactory: () => transport });
	const agent = await service.createAgent({ agentId: 'gemini-structured', provider: 'gemini', model: 'gemini-pro', reasoningEffort: 'high' });
	const result = await agent.decide('translate exactly', { goalRevision: 0, systemPrompt: '', parseOutput: JSON.parse });
	assert.deepEqual(result, { requestId: 'draft-1' });
	const prompt = transport.calls.find((call) => call.method === 'session/prompt').params.prompt[0].text;
	assert.equal(prompt, 'translate exactly');
	assert.doesNotMatch(prompt, /strategic author/i);
	transport.message = DECISION;
	await agent.decide('first gameplay state', { goalRevision: 0 });
	const gameplayPrompt = transport.calls.filter((call) => call.method === 'session/prompt')[1].params.prompt[0].text;
	assert.match(gameplayPrompt, /strategic author for one Minecraft player/i, 'an isolated structured turn must not suppress the cold gameplay contract');
	await service.stop();
});

test('ACP reports only bounded visible agent-message chunks through the verbose adapter contract', async () => {
	const transport = new FakeAcpTransport(options());
	transport.hiddenMessage = 'hidden ACP thought';
	const service = new AcpProviderService({ provider: 'gemini', cwd: 'C:\\workspace', models: ['auto', 'gemini-pro'] }, { transportFactory: () => transport });
	const agent = await service.createAgent({ agentId: 'gemini-verbose', provider: 'gemini', model: 'gemini-pro', reasoningEffort: 'high' });
	await agent.setGoalRevision(1);
	const events = [];
	await agent.decide('authoritative state', {
		goalRevision: 1,
		onVerbose(stage, message) { events.push({ stage, message }); },
	});
	assert.equal(events.every(({ stage, message }) => stage === 'output' && message.length <= 256), true);
	assert.equal(events.map(({ message }) => message).join(''), DECISION);
	assert.doesNotMatch(JSON.stringify(events), /hidden ACP thought/);
	await service.stop();
});

test('ACP keeps the exact service profile for recovery and rejects profile mutation', async () => {
	const transport = new FakeAcpTransport(options());
	const service = new AcpProviderService(
		{ provider: 'gemini', cwd: 'C:\\workspace', models: ['auto', 'gemini-pro'] },
		{ transportFactory: () => transport },
	);
	const selected = { agentId: 'gemini-profile', provider: 'gemini', model: 'gemini-pro', reasoningEffort: 'high', serviceTier: 'fast' };
	const agent = await service.createAgent(selected, { recoverySummary: 'recover through the same session' });
	assert.equal(agent.sessionGeneration, 1);
	assert.equal(agent.profileFingerprint, profileFingerprint(selected));
	assert.equal(await service.createAgent(selected, { recoverySummary: 'same profile retry' }), agent);
	for (const mutation of [
		{ model: 'auto' },
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
	assert.equal(transport.calls.filter((call) => call.method === 'session/new').length, 1);
	await service.stop();
});

test('ACP transport loss clears the owning session and coalesces one exact replacement', async () => {
	const transports = [];
	const service = new AcpProviderService(
		{ provider: 'gemini', cwd: 'C:\\workspace', models: ['auto', 'gemini-pro'] },
		{ transportFactory: () => {
			const transport = new FakeAcpTransport(options());
			transports.push(transport);
			return transport;
		} },
	);
	const selected = { agentId: 'gemini-lost', provider: 'gemini', model: 'gemini-pro', reasoningEffort: 'high', serviceTier: 'fast' };
	const stale = await service.createAgent(selected);
	await stale.decide('first state', { goalRevision: 0 });
	transports[0].emit('exit', Object.assign(new Error('ACP exited'), { code: 'PROCESS_EXITED' }));
	await new Promise((resolve) => setImmediate(resolve));
	assert.equal(service.getAgent(selected.agentId), null);
	await assert.rejects(stale.decide('late work', { goalRevision: 0 }), (error) => error?.code === 'SESSION_INVALIDATED');
	const [replacement, duplicate] = await Promise.all([
		service.replaceAgent(selected, { expectedSessionGeneration: 1 }),
		service.replaceAgent(selected, { expectedSessionGeneration: 1 }),
	]);
	assert.equal(replacement, duplicate);
	assert.equal(replacement.sessionGeneration, 2);
	assert.equal(transports.length, 2);
	await replacement.decide('restored state', { goalRevision: 0 });
	const restoredPrompt = transports[1].calls.find((call) => call.method === 'session/prompt').params.prompt[0].text;
	assert.match(restoredPrompt, /strategic author for one Minecraft player/i, 'a replacement session must restore the full contract');
	await service.stop();
});

test('ACP transport loss during session creation does not install a dead agent', async () => {
	class ExitDuringSessionStartTransport extends FakeAcpTransport {
		async request(method, params) {
			const response = await super.request(method, params);
			if (method === 'session/new') {
				this.emit('exit', Object.assign(new Error('ACP exited during session creation'), { code: 'PROCESS_EXITED' }));
			}
			return response;
		}
	}

	const transport = new ExitDuringSessionStartTransport(options());
	const service = new AcpProviderService(
		{ provider: 'gemini', cwd: 'C:\\workspace', models: ['auto', 'gemini-pro'] },
		{ transportFactory: () => transport },
	);
	const selected = { agentId: 'gemini-startup-loss', provider: 'gemini', model: 'gemini-pro', reasoningEffort: 'high' };
	await assert.rejects(service.createAgent(selected), (error) => error?.code === 'PROVIDER_UNAVAILABLE');
	assert.equal(service.getAgent(selected.agentId), null);
	assert.equal(transport.started, false);
	await service.stop();
});

test('ACP transport loss rejects an in-flight response that arrives after invalidation', async () => {
	let releasePrompt;
	const transport = new FakeAcpTransport(options());
	transport.promptGate = new Promise((resolve) => { releasePrompt = resolve; });
	const service = new AcpProviderService(
		{ provider: 'gemini', cwd: 'C:\\workspace', models: ['auto', 'gemini-pro'] },
		{ transportFactory: () => transport },
	);
	const agent = await service.createAgent({ agentId: 'gemini-late', provider: 'gemini', model: 'gemini-pro', reasoningEffort: 'high', serviceTier: 'fast' });
	const decision = agent.decide('late in-flight work', { goalRevision: 0 });
	await new Promise((resolve) => setImmediate(resolve));
	transport.emit('protocolError', Object.assign(new Error('ACP protocol lost'), { code: 'PROTOCOL_LOST' }));
	releasePrompt();
	await assert.rejects(decision, (error) => error?.code === 'SESSION_INVALIDATED');
	await service.stop();
});

test('ACP session metadata stays warm and durable across sequential prompts', async () => {
	const transport = new FakeAcpTransport(options());
	const service = new AcpProviderService({ provider: 'gemini', cwd: 'C:\\workspace', models: ['auto', 'gemini-pro'] }, { transportFactory: () => transport });
	const selected = { agentId: 'gemini-session', provider: 'gemini', model: 'gemini-pro', reasoningEffort: 'high', serviceTier: 'priority' };
	const agent = await service.createAgent(selected);
	await agent.setGoalRevision(2);
	await agent.decide('first authoritative state', { goalRevision: 2 });
	const first = agent.sessionMetadata();
	await agent.decide('second authoritative state', { goalRevision: 2 });
	const second = agent.sessionMetadata();
	const prompts = transport.calls.filter((call) => call.method === 'session/prompt').map((call) => call.params.prompt[0].text);
	assert.equal(first.sessionGeneration, 1);
	assert.equal(first.sessionState, 'warm');
	assert.equal(first.continuation, 'durable');
	assert.deepEqual(second, first);
	assert.match(prompts[0], /strategic author for one Minecraft player/i);
	assert.doesNotMatch(prompts[1], /strategic author for one Minecraft player/i);
	assert.match(prompts[1], /contract already installed in this provider session/i);
	assert.ok(Buffer.byteLength(prompts[1]) < Buffer.byteLength(prompts[0]) / 4);
	await service.stop();
});

test('ACP processes and sessions use the same per-agent workspace', async () => {
	const transport = new FakeAcpTransport(options());
	let launchProfile = null;
	const workspaceManager = {
		async prepare(provider, agentId) {
			assert.equal(provider, 'gemini');
			assert.equal(agentId, 'gemini-a');
			return 'C:\\\\agents\\\\gemini\\\\gemini-a';
		},
	};
	const service = new AcpProviderService(
		{ provider: 'gemini', cwd: 'C:\\\\workspace', models: ['auto', 'gemini-pro'] },
		{
			workspaceManager,
			transportFactory(profile) {
				launchProfile = profile;
				return transport;
			},
		},
	);
	await service.createAgent({ agentId: 'gemini-a', provider: 'gemini', model: 'gemini-pro', reasoningEffort: 'high' });

	assert.equal(launchProfile.cwd, 'C:\\\\agents\\\\gemini\\\\gemini-a');
	assert.equal(transport.calls.find((call) => call.method === 'session/new').params.cwd, 'C:\\\\agents\\\\gemini\\\\gemini-a');
	await service.stop();
});

test('Kimi launches one effort-isolated process and applies the exact ACP thinking level', async () => {
	const launch = buildAcpLaunch('kimi', { reasoningEffort: 'max' }, {
		env: {
			PATH: 'test',
			KIMI_API_KEY: 'kimi-key',
			FISH_AUDIO_API_KEY: 'voice-key',
			ARENA_AGENT_BRIDGE_SECRET: 'bridge-secret',
			ARENA_AGENT_BRIDGE_SECRET_FILE: 'C:\\runtime\\bridge.secret',
		},
	});
	assert.equal(launch.command, 'kimi');
	assert.deepEqual(launch.args, ['acp']);
	assert.equal(launch.options.env.KIMI_MODEL_THINKING_EFFORT, 'max');
	assert.equal(launch.options.env.PATH, 'test');
	assert.equal(launch.options.env.KIMI_API_KEY, 'kimi-key');
	assert.equal(launch.options.env.FISH_AUDIO_API_KEY, undefined);
	assert.equal(launch.options.env.ARENA_AGENT_BRIDGE_SECRET, undefined, 'provider child cannot inherit the bridge secret');
	assert.equal(launch.options.env.ARENA_AGENT_BRIDGE_SECRET_FILE, undefined, 'provider child cannot inherit the bridge secret file path');

	const transport = new FakeAcpTransport([
		{ id: 'model', category: 'model', type: 'select', currentValue: 'kimi-code/k3', options: [{ value: 'kimi-code/k3', name: 'K3' }] },
		{ id: 'thinking', category: 'thought_level', type: 'select', currentValue: 'low', options: ['low', 'high', 'max'].map((value) => ({ value, name: value })) },
	]);
	const service = new AcpProviderService({ provider: 'kimi', cwd: 'C:\\workspace', reasoningEfforts: ['low', 'high', 'max'] }, { transportFactory: () => transport });
	await service.createAgent({ agentId: 'kimi-a', provider: 'kimi', model: 'kimi-code/k3', reasoningEffort: 'max' });
	assert.deepEqual(transport.calls.filter((call) => call.method === 'session/set_config_option').at(-1).params, {
		sessionId: 'session-1', configId: 'thinking', value: 'max',
	});
	await service.stop();
});

test('ACP cancellation is a notification and unsupported profile values fail closed', async () => {
	const transport = new FakeAcpTransport(options());
	const service = new AcpProviderService({ provider: 'gemini', cwd: 'C:\\workspace' }, { transportFactory: () => transport });
	await assert.rejects(
		service.createAgent({ agentId: 'bad', provider: 'gemini', model: 'missing', reasoningEffort: 'high' }),
		(error) => error.code === 'UNSUPPORTED_MODEL',
	);
	const agent = await service.createAgent({ agentId: 'good', provider: 'gemini', model: 'auto', reasoningEffort: 'high' });
	agent.interrupt();
	assert.equal(transport.calls.at(-1).method, 'session/cancel');
	await service.stop();
});

test('ACP cancellation remains bounded when the transport cannot send session cancel', async () => {
	const transport = new FakeAcpTransport(options());
	let releasePrompt;
	transport.promptGate = new Promise((resolve) => { releasePrompt = resolve; });
	transport.notify = () => { throw Object.assign(new Error('transport already stopped'), { code: 'TRANSPORT_NOT_RUNNING' }); };
	const service = new AcpProviderService({ provider: 'gemini', cwd: 'C:\\workspace' }, { transportFactory: () => transport });
	const agent = await service.createAgent({ agentId: 'cancel-race', provider: 'gemini', model: 'auto', reasoningEffort: 'high' });
	const controller = new AbortController();
	const decision = agent.decide('authoritative state', { goalRevision: 0, signal: controller.signal });

	controller.abort();
	releasePrompt();
	await assert.rejects(decision, (error) => error?.code === 'STALE_PLAN');
	await service.stop();
});

test('ACP rejects a streamed planner decision once its aggregate byte budget is exceeded', async () => {
	const transport = new FakeAcpTransport(options());
	transport.message = 'x'.repeat(33);
	const service = new AcpProviderService(
		{ provider: 'gemini', cwd: 'C:\\workspace', models: ['auto', 'gemini-pro'], maxDecisionBytes: 32 },
		{ transportFactory: () => transport },
	);
	const agent = await service.createAgent({ agentId: 'gemini-bounded', provider: 'gemini', model: 'gemini-pro', reasoningEffort: 'high' });
	await agent.setGoalRevision(1);
	await assert.rejects(
		agent.decide('authoritative state', { goalRevision: 1 }),
		(error) => error?.code === 'PLANNER_OUTPUT_LIMIT',
	);
	assert.equal(transport.calls.some((call) => call.kind === 'notification' && call.method === 'session/cancel'), true);
	await service.stop();
});

test('Kimi ACP treats its boolean thinking switch as enabled while the exact effort stays process-scoped', async () => {
	const launch = buildAcpLaunch('kimi', { reasoningEffort: 'low' }, { env: {} });
	assert.equal(launch.options.env.KIMI_MODEL_THINKING_EFFORT, 'low');
	const transport = new FakeAcpTransport([
		{ id: 'model', category: 'model', type: 'select', currentValue: 'kimi-code/k3', options: [{ value: 'kimi-code/k3', name: 'K3' }] },
		{ id: 'thinking', category: 'thought_level', type: 'select', currentValue: 'on', options: [{ value: 'on', name: 'On' }] },
	]);
	const service = new AcpProviderService({ provider: 'kimi', cwd: 'C:\\workspace', reasoningEfforts: ['low', 'high', 'max'] }, { transportFactory: () => transport });
	const agent = await service.createAgent({ agentId: 'kimi-low', provider: 'kimi', model: 'kimi-code/k3', reasoningEffort: 'low' });
	assert.equal(agent.executionSettings.requested.reasoningEffort, 'low');
	assert.equal(agent.executionSettings.effective.reasoningEffort, null);
	assert.equal(agent.executionSettings.effective.thinkingMode, 'on');
	assert.equal(agent.executionSettings.evidence.reasoningEffort, 'process_environment');
	assert.equal(agent.executionSettings.effective.serviceTier, null);
	assert.equal(transport.calls.some((call) => call.params?.configId === 'thinking' && call.params.value === 'low'), false);
	await service.stop();
});

test('Kimi ACP accepts sessions that expose no thinking control because effort is process-scoped', async () => {
	const transport = new FakeAcpTransport([
		{ id: 'model', category: 'model', type: 'select', currentValue: 'kimi-code/k3', options: [{ value: 'kimi-code/k3', name: 'K3' }] },
	]);
	const service = new AcpProviderService({ provider: 'kimi', cwd: 'C:\\workspace', reasoningEfforts: ['low', 'high', 'max'] }, { transportFactory: () => transport });
	const agent = await service.createAgent({ agentId: 'kimi-no-thinking-option', provider: 'kimi', model: 'kimi-code/k3', reasoningEffort: 'low' });
	assert.equal(agent.executionSettings.effective.reasoningEffort, null);
	assert.equal(agent.executionSettings.effective.thinkingMode, null);
	assert.deepEqual(agent.executionSettings.limitations, ['effort_not_reported_by_provider']);
	assert.equal(transport.calls.some((call) => call.params?.configId === 'thinking'), false);
	await service.stop();
});

test('Kimi K3 uses the API-key-backed Moonshot alias when it is available', async () => {
	const transport = new FakeAcpTransport([
		{
			id: 'model', category: 'model', type: 'select', currentValue: 'moonshot-ai/kimi-k3',
			options: [
				{ value: 'kimi-code/k3', name: 'K3 OAuth' },
				{ value: 'moonshot-ai/kimi-k3', name: 'K3 API' },
			],
		},
	]);
	const service = new AcpProviderService({ provider: 'kimi', cwd: 'C:\\workspace', reasoningEfforts: ['low', 'high', 'max'] }, { transportFactory: () => transport });
	const agent = await service.createAgent({ agentId: 'kimi-api-k3', provider: 'kimi', model: 'kimi-code/k3', reasoningEffort: 'high' });
	assert.equal(agent.executionSettings.requested.model, 'kimi-code/k3');
	assert.equal(agent.executionSettings.effective.model, 'moonshot-ai/kimi-k3');
	assert.equal(agent.executionSettings.transport, 'acp');
	assert.equal(transport.calls.some((call) => call.params?.configId === 'model'), false);
	await service.stop();
});

test('Kimi K2.7 coding aliases use their API-key-backed Moonshot equivalents', async () => {
	for (const [requested, routed] of [
		['kimi-code/kimi-for-coding', 'moonshot-ai/kimi-k2.7-code'],
		['kimi-code/kimi-for-coding-highspeed', 'moonshot-ai/kimi-k2.7-code-highspeed'],
	]) {
		const transport = new FakeAcpTransport([{
			id: 'model', category: 'model', type: 'select', currentValue: 'kimi-code/k3',
			options: [{ value: requested, name: 'OAuth' }, { value: routed, name: 'API' }],
		}]);
		const service = new AcpProviderService({
			provider: 'kimi', cwd: 'C:\\workspace', models: [requested], reasoningEfforts: ['high'],
			modelReasoningEfforts: { [requested]: ['high'] },
		}, { transportFactory: () => transport });
		await service.createAgent({ agentId: `route-${requested}`, provider: 'kimi', model: requested, reasoningEffort: 'high' });
		assert.equal(transport.calls.find((call) => call.params?.configId === 'model')?.params.value, routed);
		await service.stop();
	}
});

test('Kimi empty turns surface provider availability instead of a misleading planner parse error', async () => {
	const transport = new FakeAcpTransport([
		{ id: 'model', category: 'model', type: 'select', currentValue: 'kimi-code/k3', options: [{ value: 'kimi-code/k3', name: 'K3' }] },
	]);
	transport.message = '';
	const service = new AcpProviderService({ provider: 'kimi', cwd: 'C:\\workspace', reasoningEfforts: ['low', 'high', 'max'] }, { transportFactory: () => transport });
	const agent = await service.createAgent({ agentId: 'kimi-empty', provider: 'kimi', model: 'kimi-code/k3', reasoningEffort: 'high' });
	await agent.setGoalRevision(1);
	await assert.rejects(
		agent.decide('authoritative state', { goalRevision: 1 }),
		(error) => error?.code === 'PROVIDER_UNAVAILABLE' && /login and membership entitlement/i.test(error.message),
	);
	await service.stop();
});

test('ACP refreshes dependent capabilities after changing the model', async () => {
	const transport = new FakeAcpTransport([
		{ id: 'model', category: 'model', type: 'select', currentValue: 'auto', options: [{ value: 'auto', name: 'Auto' }, { value: 'kimi-code/k3', name: 'K3' }] },
		{ id: 'thinking', category: 'thought_level', type: 'select', currentValue: 'high', options: [{ value: 'high', name: 'High' }] },
	], { configOptionsAfterModel: [
		{ id: 'model', category: 'model', type: 'select', currentValue: 'kimi-code/k3', options: [{ value: 'auto', name: 'Auto' }, { value: 'kimi-code/k3', name: 'K3' }] },
		{ id: 'thinking', category: 'thought_level', type: 'select', currentValue: 'high', options: ['low', 'high', 'max'].map((value) => ({ value, name: value })) },
	] });
	const service = new AcpProviderService({ provider: 'kimi', cwd: 'C:\\workspace', reasoningEfforts: ['low', 'high', 'max'] }, { transportFactory: () => transport });
	await service.createAgent({ agentId: 'kimi-low', provider: 'kimi', model: 'kimi-code/k3', reasoningEffort: 'low' });
	assert.deepEqual(transport.calls.filter((call) => call.method === 'session/set_config_option').map((call) => call.params.value), ['kimi-code/k3', 'low']);
	await service.stop();
});

test('Kimi catalog retains the last discovered display names when a later CLI refresh fails', async () => {
	let fail = false;
	let discoveryCalls = 0;
	const discovered = [{
		id: 'kimi-code/kimi-for-coding',
		model: 'kimi-code/kimi-for-coding',
		displayName: 'K2.7 Coding',
		reasoningEfforts: ['high'],
		serviceTiers: [],
	}];
	const service = new AcpProviderService(
		{ provider: 'kimi', cwd: 'C:\\workspace', catalogDiscovery: true },
		{ discoverCatalog: async () => { discoveryCalls += 1; if (fail) throw new Error('offline'); return discovered; } },
	);
	const first = await service.catalog.refresh({ force: true });
	fail = true;
	const retained = await service.catalog.refresh({ force: true });
	assert.deepEqual(retained, first);
	assert.equal(retained.models[0].displayName, 'K2.7 Coding');
	assert.equal(service.catalog.stale, true);
	const transport = new FakeAcpTransport([
		{ id: 'model', category: 'model', type: 'select', currentValue: 'kimi-code/kimi-for-coding', options: [{ value: 'kimi-code/kimi-for-coding' }] },
		{ id: 'thinking', category: 'thought_level', type: 'select', currentValue: 'high', options: [{ value: 'high' }] },
	]);
	const warmService = new AcpProviderService(
		{ provider: 'kimi', cwd: 'C:\\workspace', catalogDiscovery: true },
		{ discoverCatalog: async () => { discoveryCalls += 1; if (fail) throw new Error('offline'); return discovered; }, transportFactory: () => transport },
	);
	fail = false;
	const selected = { agentId: 'kimi-warm', provider: 'kimi', model: 'kimi-code/kimi-for-coding', reasoningEffort: 'high' };
	const agent = await warmService.createAgent(selected);
	fail = true;
	await warmService.catalog.refresh({ force: true });
	const callsBeforeReuse = discoveryCalls;
	assert.equal(await warmService.createAgent(selected), agent);
	assert.equal(discoveryCalls, callsBeforeReuse, 'exact warm reuse must not wait for degraded discovery');
	await warmService.stop();
});

test('ACP coalesces concurrent stale catalog refreshes', async () => {
	let discoveryCalls = 0;
	let release;
	const gate = new Promise((resolve) => { release = resolve; });
	const discovered = [{ id: 'kimi-code/k3', model: 'kimi-code/k3', displayName: 'K3', reasoningEfforts: ['high'], serviceTiers: [] }];
	const service = new AcpProviderService(
		{ provider: 'kimi', cwd: 'C:\\workspace', catalogDiscovery: true },
		{ discoverCatalog: async () => { discoveryCalls += 1; await gate; return discovered; } },
	);
	const refreshes = [service.catalog.refresh({ force: true }), service.catalog.refresh({ force: true }), service.catalog.refresh()];
	await new Promise((resolve) => setImmediate(resolve));
	assert.equal(discoveryCalls, 1);
	release();
	const snapshots = await Promise.all(refreshes);
	assert.deepEqual(snapshots[1], snapshots[0]);
	assert.deepEqual(snapshots[2], snapshots[0]);
});

test('ACP parse correction retains the accepted session and compact planner contract', async () => {
	const transport = new FakeAcpTransport(options());
	transport.message = '{"summary":"bad","directive":"replace","source":null}';
	const service = new AcpProviderService({ provider: 'gemini', cwd: 'C:\\workspace', models: ['auto', 'gemini-pro'] }, { transportFactory: () => transport });
	const agent = await service.createAgent({ agentId: 'gemini-correction', provider: 'gemini', model: 'gemini-pro', reasoningEffort: 'high' });
	await agent.setGoalRevision(2);
	await assert.rejects(agent.decide('authoritative state', { goalRevision: 2 }), (error) => error?.code === 'DECISION_FIELD_MISMATCH');
	assert.equal(agent.sessionMetadata().sessionState, 'warm');
	transport.message = DECISION;
	await agent.decide('correct the rejected envelope', { goalRevision: 2, retry: true });
	const prompts = transport.calls.filter((call) => call.method === 'session/prompt');
	assert.equal(prompts.length, 2);
	assert.equal(prompts.every((call) => call.params.sessionId === 'session-1'), true);
	assert.match(prompts[0].params.prompt[0].text, /strategic author/i);
	assert.doesNotMatch(prompts[1].params.prompt[0].text, /strategic author/i);
	await service.stop();
});

test('Kimi catalog discovery receives only the Kimi provider environment', async () => {
	let discoveryOptions;
	const service = new AcpProviderService({
		provider: 'kimi',
		cwd: 'C:\\workspace',
		catalogDiscovery: true,
		environment: {
			PATH: 'test',
			KIMI_API_KEY: 'kimi-key',
			OPENAI_API_KEY: 'openai-key',
			FISH_AUDIO_API_KEY: 'voice-key',
			DEEPGRAM_API_KEY: 'speech-key',
		},
	}, {
		execFile(_command, _args, options, callback) {
			discoveryOptions = options;
			callback(null, JSON.stringify({ models: { 'kimi-code/k3': { supportEfforts: ['high'] } } }));
		},
	});
	await service.catalog.refresh({ force: true });
	assert.equal(discoveryOptions.env.PATH, 'test');
	assert.equal(discoveryOptions.env.KIMI_API_KEY, 'kimi-key');
	assert.equal(discoveryOptions.env.OPENAI_API_KEY, undefined);
	assert.equal(discoveryOptions.env.FISH_AUDIO_API_KEY, undefined);
	assert.equal(discoveryOptions.env.DEEPGRAM_API_KEY, undefined);
});

test('ACP malformed output records one final error row for the attempt', async () => {
	const transport = new FakeAcpTransport(options());
	transport.message = 'not-json ARBITRARY_ACP_MODEL_SECRET';
	const service = new AcpProviderService({ provider: 'gemini', cwd: 'C:\\workspace', models: ['auto', 'gemini-pro'] }, { transportFactory: () => transport });
	const agent = await service.createAgent({ agentId: 'gemini-malformed-record', provider: 'gemini', model: 'gemini-pro', reasoningEffort: 'high' });
	await agent.setGoalRevision(2);
	const rows = [];
	const turnRecorder = { async record(row) { rows.push(row); } };
	await assert.rejects(agent.decide('authoritative state', { goalRevision: 2, turnRecorder, attempt: 4, retry: true }), (error) => error?.code === 'MALFORMED_DECISION');
	assert.equal(rows.length, 1);
	assert.equal(rows[0].error?.code, 'MALFORMED_DECISION');
	assert.equal(rows[0].error?.category, 'decision_parse');
	assert.doesNotMatch(JSON.stringify(rows[0]), /ARBITRARY_ACP_MODEL_SECRET/);
	assert.equal(rows[0].attempt, 4);
	assert.equal(rows[0].retry, true);
	assert.ok(rows[0].timing.durationMs >= 0);
	assert.equal(rows[0].timing.apiDurationMs, null);
	await service.stop();
});

test('ACP records authoritative identity, scheduler wait, and native per-turn usage categories', async () => {
	const transport = new FakeAcpTransport(options());
	transport.promptResponse = { stopReason: 'end_turn', usage: {
		inputTokens: 80, outputTokens: 12, cachedReadTokens: 30, cachedWriteTokens: 4, thoughtTokens: 6, totalTokens: 98,
	} };
	const service = new AcpProviderService({ provider: 'gemini', cwd: 'C:\\workspace', models: ['auto', 'gemini-pro'] }, { transportFactory: () => transport });
	const agent = await service.createAgent({ agentId: 'gemini-native-metrics', provider: 'gemini', model: 'gemini-pro', reasoningEffort: 'high' });
	await agent.setGoalRevision(2);
	const rows = [];
	await agent.decide('authoritative state', { goalRevision: 2, queueWaitMs: 29, turnRecorder: { async record(row) { rows.push(row); } } });
	assert.equal(rows[0].agentId, 'gemini-native-metrics');
	assert.equal(rows[0].timing.queueWaitMs, 29);
	assert.deepEqual(rows[0].tokens, { input: 80, output: 12, reasoning: 6, cached: 30, cacheWrite: 4 });
	await service.stop();
});

test('Gemini ACP uses exact native quota counts only when standard ACP usage is absent', async () => {
	const transport = new FakeAcpTransport(options());
	transport.promptResponse = { stopReason: 'end_turn', _meta: { quota: { token_count: { input_tokens: 44, output_tokens: 9 } } } };
	const service = new AcpProviderService({ provider: 'gemini', cwd: 'C:\\workspace', models: ['auto', 'gemini-pro'] }, { transportFactory: () => transport });
	const agent = await service.createAgent({ agentId: 'gemini-quota', provider: 'gemini', model: 'gemini-pro', reasoningEffort: 'high' });
	await agent.setGoalRevision(2);
	const rows = [];
	await agent.decide('state', { goalRevision: 2, turnRecorder: { async record(row) { rows.push(row); } } });
	assert.deepEqual(rows[0].tokens, { input: 44, output: 9, reasoning: null, cached: null, cacheWrite: null });
	await service.stop();
});

test('ACP does not label prose as a rate limit without a structured 429', async () => {
	const transport = new FakeAcpTransport(options());
	transport.request = async function (method, params) {
		if (method !== 'session/prompt') return FakeAcpTransport.prototype.request.call(this, method, params);
		throw Object.assign(new Error('incidental prose: too many blocks near rate limit HTTP 429'), { code: 'PROVIDER_UNAVAILABLE' });
	};
	const service = new AcpProviderService({ provider: 'gemini', cwd: 'C:\\workspace', models: ['auto', 'gemini-pro'] }, { transportFactory: () => transport });
	const agent = await service.createAgent({ agentId: 'gemini-prose', provider: 'gemini', model: 'gemini-pro', reasoningEffort: 'high' });
	await agent.setGoalRevision(2);
	const rows = [];
	await assert.rejects(agent.decide('state', { goalRevision: 2, turnRecorder: { async record(row) { rows.push(row); } } }));
	assert.equal(Object.hasOwn(rows[0], 'rateLimited'), false);
	await service.stop();
});
