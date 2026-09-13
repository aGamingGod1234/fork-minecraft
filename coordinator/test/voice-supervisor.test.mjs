import assert from 'node:assert/strict';
import { EventEmitter } from 'node:events';
import { createServer } from 'node:http';
import test from 'node:test';

import {
	createVoiceSupervisor,
	startCoordinatorControl,
	startVoiceWorker as startVoiceWorkerRuntime,
} from '../src/dynamic-main.mjs';
import { createVoiceHttpServer, createVoiceRequestHeaders } from '../src/voice/voice-http-server.mjs';

function fetch(input, init = {}) {
	const headers = new Headers(init.headers);
	const url = new URL(typeof input === 'string' ? input : input.url);
	if (init.method === 'POST' && ['/v1/tts', '/v1/stt'].includes(url.pathname)
			&& headers.has('X-Voice-Signature')) {
		const signed = createVoiceRequestHeaders({
			secret: SECRET,
			path: url.pathname,
			contentType: headers.get('Content-Type') ?? '',
			identityHeaders: headers,
			body: init.body == null ? Buffer.alloc(0) : Buffer.from(init.body),
			timestamp: Number(headers.get('X-Voice-Timestamp')),
			nonce: headers.get('X-Voice-Nonce'),
		});
		for (const [name, value] of Object.entries(signed)) headers.set(name, value);
		return globalThis.fetch(input, { ...init, headers });
	}
	return globalThis.fetch(input, init);
}
import { VoiceProfileStore } from '../src/voice/voice-profile-store.mjs';
import { VoiceSupervisor } from '../src/voice/voice-supervisor.mjs';

const SECRET = 'voice-supervisor-test-secret';

function startVoiceWorker(config, environment = {}, dependencies = {}) {
	return startVoiceWorkerRuntime(
		{ ...config, voice: { ...config.voice, secret: config.voice?.secret ?? SECRET } },
		environment,
		dependencies,
	);
}

test('coordinator control starts before optional voice and never awaits its warmup', async () => {
	const order = [];
	let releaseVoice;
	const voiceGate = new Promise((resolve) => { releaseVoice = resolve; });
	const coordinator = { async start() { order.push('coordinator'); } };
	const voiceSupervisor = { start() { order.push('voice'); return voiceGate; }, async close() {} };

	await startCoordinatorControl(coordinator, voiceSupervisor);
	assert.deepEqual(order, ['coordinator', 'voice']);
	releaseVoice();
});

test('protocol shutdown closes the voice supervisor and its worker ownership', async () => {
	const coordinator = new EventEmitter();
	coordinator.start = async () => {};
	let closes = 0;
	const voiceSupervisor = {
		start() {},
		async close() { closes += 1; },
	};

	await startCoordinatorControl(coordinator, voiceSupervisor);
	coordinator.emit('shutdown');
	await new Promise((resolve) => setImmediate(resolve));
	assert.equal(closes, 1);
});

test('production voice supervision allows the configured local model deadline plus cleanup grace', async () => {
	const timers = new ManualTimers();
	let closes = 0;
	const supervisor = createVoiceSupervisor({ voice: { localSpeechTimeoutMs: 75 } }, {}, {
		startWorker: async () => ({
			warmup: () => new Promise(() => {}),
			async close() { closes += 1; },
			statusSnapshots: readySnapshots,
		}),
		reportFailure() {},
		supervisorOptions: {
			now: () => timers.now,
			schedule: timers.schedule,
			cancelSchedule: timers.cancel,
			startupTimeoutMs: 50,
			cleanupTimeoutMs: 50,
		},
	});
	supervisor.start();
	await flush();
	const warmupDeadline = timers.tasks.find((task) => !task.canceled);
	assert.equal(warmupDeadline.at, 1_075, 'supervision does not preempt the configured 75 ms local deadline');
	await timers.runNext();
	assert.equal(component(supervisor, 'voice').failureCode, 'VOICE_WARMUP_TIMEOUT');
	await supervisor.close();
	assert.equal(closes, 1);
});

test('voice supervisor reports only bounded redacted failure codes during one outage', async () => {
	const timers = new ManualTimers();
	const reports = [];
	let attempts = 0;
	const codes = ['EADDRINUSE', 'EADDRINUSE', 'LOCAL_SPEECH_WARMUP_FAILED', 'TTS_UNAVAILABLE', 'STT_UNAVAILABLE'];
	const supervisor = new VoiceSupervisor({
		startWorker: async () => {
			const error = new Error(`provider-secret-${attempts}`);
			error.code = codes[Math.min(attempts, codes.length - 1)];
			attempts += 1;
			throw error;
		},
		onFailure: (report) => reports.push(report),
		now: () => timers.now,
		schedule: timers.schedule,
		cancelSchedule: timers.cancel,
		initialRetryMs: 1,
		maxRetryMs: 1,
		startupTimeoutMs: 100,
		warmupTimeoutMs: 100,
	});
	supervisor.start();
	await flush();
	for (let index = 0; index < 4; index += 1) await timers.runNext();
	assert.deepEqual(reports.map(({ failureCode }) => failureCode), [
		'EADDRINUSE',
		'LOCAL_SPEECH_WARMUP_FAILED',
		'TTS_UNAVAILABLE',
	]);
	assert.doesNotMatch(JSON.stringify(reports), /provider-secret/);
	await supervisor.close();
});

test('voice supervisor retries an occupied port and recovers after release without coordinator restart', async () => {
	const blocker = createServer();
	await new Promise((resolve) => blocker.listen(0, '127.0.0.1', resolve));
	const port = blocker.address().port;
	const supervisor = new VoiceSupervisor({
		startWorker: () => startVoiceWorker({ bridge: { secret: SECRET }, voice: { port } }, {
			FISH_API_KEY: 'test-key',
		}, {
			platform: 'linux',
			loadProfileStore: async () => ({ store: { resolve: () => probeProfile() } }),
			createTtsProvider: () => ({ async synthesize() { return validSynthesis(); } }),
		}),
		initialRetryMs: 10,
		maxRetryMs: 20,
		startupTimeoutMs: 500,
		warmupTimeoutMs: 500,
	});
	try {
		supervisor.start();
		await eventually(() => component(supervisor, 'voice').state === 'degraded');
		assert.ok(Number.isSafeInteger(component(supervisor, 'voice').nextProbeAtEpochMs));
		await new Promise((resolve, reject) => blocker.close((error) => error ? reject(error) : resolve()));
		await eventually(() => component(supervisor, 'voice:tts').state === 'ready');
		assert.ok(Number.isSafeInteger(component(supervisor, 'voice:tts').lastRecoveryAtEpochMs));
		assert.equal((await fetch(`http://127.0.0.1:${port}/health`)).status, 200);
	} finally {
		if (blocker.listening) await new Promise((resolve) => blocker.close(resolve));
		await supervisor.close();
	}
});

test('voice supervisor uses capped retries and close fences a late startup success', async () => {
	const timers = new ManualTimers();
	let attempts = 0;
	const supervisor = new VoiceSupervisor({
		startWorker: async () => {
			attempts += 1;
			throw Object.assign(new Error('bind failed'), { code: 'EADDRINUSE' });
		},
		now: () => timers.now,
		schedule: timers.schedule,
		cancelSchedule: timers.cancel,
		initialRetryMs: 100,
		maxRetryMs: 200,
		startupTimeoutMs: 1_000,
		warmupTimeoutMs: 1_000,
	});
	supervisor.start();
	await flush();
	assert.equal(attempts, 1);
	assert.equal(component(supervisor, 'voice').nextProbeAtEpochMs, 100);
	await timers.runNext();
	assert.equal(attempts, 2);
	assert.equal(component(supervisor, 'voice').nextProbeAtEpochMs, 300);
	await timers.runNext();
	assert.equal(attempts, 3);
	assert.equal(component(supervisor, 'voice').nextProbeAtEpochMs, 500, 'retry delay is capped');
	await supervisor.close();
	await timers.runNextEvenIfCancelled();
	assert.equal(attempts, 3, 'closed supervisor ignores captured retry callbacks');

	let releaseStartup;
	const startup = new Promise((resolve) => { releaseStartup = resolve; });
	let lateCloses = 0;
	const late = new VoiceSupervisor({
		startWorker: () => startup,
		now: () => timers.now,
		schedule: timers.schedule,
		cancelSchedule: timers.cancel,
		startupTimeoutMs: 1_000,
		warmupTimeoutMs: 1_000,
	});
	late.start();
	await flush();
	await late.close();
	releaseStartup({ async close() { lateCloses += 1; }, statusSnapshots: readySnapshots });
	await flush();
	assert.equal(lateCloses, 1, 'late worker is closed exactly once instead of being promoted');
});

test('timed-out startup generation closes a late worker and cannot replace its retry', async () => {
	const timers = new ManualTimers();
	let releaseStartup;
	const startup = new Promise((resolve) => { releaseStartup = resolve; });
	let closes = 0;
	const supervisor = new VoiceSupervisor({
		startWorker: () => startup,
		now: () => timers.now,
		schedule: timers.schedule,
		cancelSchedule: timers.cancel,
		initialRetryMs: 100,
		maxRetryMs: 100,
		startupTimeoutMs: 50,
		warmupTimeoutMs: 50,
	});
	supervisor.start();
	await flush();
	await timers.runNext();
	assert.equal(component(supervisor, 'voice').failureCode, 'VOICE_START_TIMEOUT');
	releaseStartup({ async close() { closes += 1; }, statusSnapshots: readySnapshots });
	await flush();
	assert.equal(closes, 1, 'late timed-out worker is closed exactly once');
	assert.equal(component(supervisor, 'voice').state, 'degraded', 'late success cannot clear retry state');
	await supervisor.close();
});

test('hung warmup is bounded, closes its candidate, and enters automatic retry', async () => {
	const timers = new ManualTimers();
	let closes = 0;
	const supervisor = new VoiceSupervisor({
		startWorker: async () => ({
			warmup: () => new Promise(() => {}),
			async close() { closes += 1; },
			statusSnapshots: readySnapshots,
		}),
		now: () => timers.now,
		schedule: timers.schedule,
		cancelSchedule: timers.cancel,
		initialRetryMs: 100,
		maxRetryMs: 100,
		startupTimeoutMs: 50,
		warmupTimeoutMs: 25,
	});
	supervisor.start();
	await flush();
	await timers.runNext();
	assert.equal(closes, 1);
	assert.equal(component(supervisor, 'voice').failureCode, 'VOICE_WARMUP_TIMEOUT');
	assert.equal(component(supervisor, 'voice').nextProbeAtEpochMs, 125);
	await supervisor.close();
});

test('post-bind server failure stays handled, replaces the exact worker, and fences stale events', async () => {
	const workers = [];
	const supervisor = new VoiceSupervisor({
		startWorker: async () => {
			const worker = createVoiceHttpServer({
				provider: { async synthesize() { return validSynthesis(); } },
				sttProvider: { async transcribe() { return { transcript: '', confidence: 1 }; } },
				profileStore: new VoiceProfileStore(),
				secret: SECRET,
				port: 0,
			});
			await worker.start();
			workers.push(worker);
			return worker;
		},
		initialRetryMs: 25,
		maxRetryMs: 25,
		startupTimeoutMs: 500,
		warmupTimeoutMs: 500,
		cleanupTimeoutMs: 25,
	});
	try {
		supervisor.start();
		await eventually(() => workers.length === 1 && component(supervisor, 'voice').state === 'ready');
		await new Promise((resolve, reject) => workers[0].server.close((error) => error ? reject(error) : resolve()));
		await eventually(() => workers.length === 2 && component(supervisor, 'voice').state === 'ready');
		workers[1].server.emit('error', Object.assign(new Error('live listener failure'), { code: 'VOICE_SERVER_FAILED' }));
		assert.equal(component(supervisor, 'voice').failureCode, 'VOICE_SERVER_FAILED');
		await eventually(() => workers.length === 3 && component(supervisor, 'voice').state === 'ready');
		workers[0].server.emit('error', Object.assign(new Error('stale server failure'), { code: 'VOICE_STALE_FAILURE' }));
		await new Promise((resolve) => setTimeout(resolve, 20));
		assert.equal(workers.length, 3, 'stale server events cannot replace the current generation');
	} finally {
		await supervisor.close();
	}
});

test('a stalled channel probe replaces the exact worker and recovers without overlapping physical probes', async () => {
	const workers = [];
	let unsettledProbes = 0;
	let maxUnsettledProbes = 0;
	const supervisor = new VoiceSupervisor({
		startWorker: async () => {
			let synthesisCalls = 0;
			let releaseProbe;
			const probe = new Promise((resolve) => { releaseProbe = resolve; });
			const worker = createVoiceHttpServer({
				provider: {
					async synthesize() {
						synthesisCalls += 1;
						if (synthesisCalls === 1 || synthesisCalls === 3) {
							throw Object.assign(new Error('temporary TTS failure'), { code: 'TTS_UNAVAILABLE' });
						}
						return validSynthesis();
					},
					probe: () => {
						unsettledProbes += 1;
						maxUnsettledProbes = Math.max(maxUnsettledProbes, unsettledProbes);
						return probe.finally(() => { unsettledProbes -= 1; });
					},
				},
				profileStore: new VoiceProfileStore(),
				secret: SECRET,
				initialProbeDelayMs: 5,
				maxProbeDelayMs: 5,
				probeTimeoutMs: 500,
				port: 0,
			});
			const address = await worker.start();
			const owned = {
				...worker,
				baseUrl: `http://127.0.0.1:${address.port}`,
				async close() {
					releaseProbe();
					await worker.close();
				},
			};
			workers.push(owned);
			return owned;
		},
		initialRetryMs: 5,
		maxRetryMs: 5,
		startupTimeoutMs: 100,
		warmupTimeoutMs: 100,
		cleanupTimeoutMs: 50,
	});
	try {
		supervisor.start();
		await eventually(() => workers.length === 1 && component(supervisor, 'voice:tts').state === 'ready');
		const first = workers[0];
		const request = (text, sequence) => fetch(`${first.baseUrl}/v1/tts`, {
			method: 'POST',
			headers: {
				...createVoiceRequestHeaders({ secret: SECRET, path: '/v1/tts' }),
				'Content-Type': 'application/json',
			},
			body: JSON.stringify({
				agentId: '00000000-0000-4000-8000-000000000001', text,
				profileId: 'voice.auto.v1', radius: 48, conversationSequence: sequence,
			}),
		});
		assert.equal((await request('first failure', 1)).status, 502);
		await eventually(() => unsettledProbes === 1);
		assert.equal((await request('real recovery', 2)).status, 200);
		assert.equal((await request('second failure', 3)).status, 502);
		await eventually(() => workers.length === 2 && component(supervisor, 'voice:tts').state === 'ready');
		assert.equal(maxUnsettledProbes, 1);
		assert.equal(unsettledProbes, 0);
	} finally {
		await supervisor.close();
	}
});

test('startup timeout aborts its one owned attempt before any replacement begins', async () => {
	let attempts = 0;
	let aborts = 0;
	const supervisor = new VoiceSupervisor({
		startWorker: ({ signal }) => new Promise(() => {
			attempts += 1;
			signal.addEventListener('abort', () => { aborts += 1; }, { once: true });
		}),
		initialRetryMs: 5,
		maxRetryMs: 5,
		startupTimeoutMs: 10,
		warmupTimeoutMs: 10,
		cleanupTimeoutMs: 10,
	});
	try {
		supervisor.start();
		await new Promise((resolve) => setTimeout(resolve, 50));
		assert.equal(attempts, 1, 'an abort-ignoring startup retains ownership instead of accumulating calls');
		assert.equal(aborts, 1, 'startup timeout aborts the underlying generation');
	} finally {
		await supervisor.close();
	}
});

test('hung live worker close cannot block retry or supervisor shutdown', async () => {
	let attempts = 0;
	let failLive;
	const supervisor = new VoiceSupervisor({
		startWorker: async () => {
			attempts += 1;
			return {
				close: () => new Promise(() => {}),
				onFailure(listener) { if (attempts === 1) failLive = listener; return () => {}; },
				statusSnapshots: readySnapshots,
			};
		},
		initialRetryMs: 5,
		maxRetryMs: 5,
		startupTimeoutMs: 50,
		warmupTimeoutMs: 50,
		cleanupTimeoutMs: 10,
	});
	supervisor.start();
	await eventually(() => typeof failLive === 'function');
	failLive(Object.assign(new Error('live transport failed'), { code: 'VOICE_SERVER_FAILED' }));
	await eventually(() => attempts === 2);
	await Promise.race([
		supervisor.close(),
		new Promise((_, reject) => setTimeout(() => reject(new Error('supervisor close remained hung')), 100)),
	]);
});

test('hung candidate close cannot block startup failure retry', async () => {
	let attempts = 0;
	const supervisor = new VoiceSupervisor({
		startWorker: async () => {
			attempts += 1;
			if (attempts === 1) return {
				warmup: async () => { throw Object.assign(new Error('warmup failed'), { code: 'VOICE_WARMUP_FAILED' }); },
				close: () => new Promise(() => {}),
				statusSnapshots: readySnapshots,
			};
			return { async close() {}, statusSnapshots: readySnapshots };
		},
		initialRetryMs: 5,
		maxRetryMs: 5,
		startupTimeoutMs: 50,
		warmupTimeoutMs: 50,
		cleanupTimeoutMs: 10,
	});
	try {
		supervisor.start();
		await eventually(() => attempts === 2 && component(supervisor, 'voice').state === 'ready');
	} finally {
		await supervisor.close();
	}
});

test('terminal close and error between bind and subscription replay into exact startup retries', async () => {
	const workers = [];
	const supervisor = new VoiceSupervisor({
		startWorker: async () => {
			const worker = createVoiceHttpServer({
				provider: { async synthesize() { return validSynthesis(); } },
				sttProvider: { async transcribe() { return { transcript: '', confidence: 1 }; } },
				profileStore: new VoiceProfileStore(),
				secret: SECRET,
				port: 0,
			});
			await worker.start();
			workers.push(worker);
			if (workers.length === 1) {
				await new Promise((resolve, reject) => worker.server.close((error) => error ? reject(error) : resolve()));
			} else if (workers.length === 2) {
				worker.server.emit('error', Object.assign(new Error('failed before subscription'), { code: 'VOICE_EARLY_FAILURE' }));
			}
			return worker;
		},
		initialRetryMs: 5,
		maxRetryMs: 5,
		startupTimeoutMs: 100,
		warmupTimeoutMs: 100,
		cleanupTimeoutMs: 20,
	});
	try {
		supervisor.start();
		await eventually(() => workers.length === 3 && component(supervisor, 'voice').state === 'ready');
		workers[0].server.emit('error', Object.assign(new Error('stale replay'), { code: 'VOICE_STALE_FAILURE' }));
		await new Promise((resolve) => setTimeout(resolve, 20));
		assert.equal(workers.length, 3, 'stale terminal events cannot invalidate the recovered worker');
	} finally {
		await supervisor.close();
	}
});

test('default local discovery aborts a stalled filesystem generation and retries cleanly', async () => {
	let accessCalls = 0;
	let aborts = 0;
	const lateResolvers = [];
	const supervisor = new VoiceSupervisor({
		startWorker: ({ signal }) => startVoiceWorker({ bridge: { secret: SECRET }, voice: { port: 0 } }, {
			FISH_API_KEY: 'test-key',
		}, {
			signal,
			platform: 'linux',
			localSpeechAccess: (filePath, accessSignal) => {
				accessCalls += 1;
				if (accessCalls > 2) throw Object.assign(new Error('not installed'), { code: 'ENOENT' });
				accessSignal.addEventListener('abort', () => { aborts += 1; }, { once: true });
				return new Promise((resolve) => { lateResolvers.push(resolve); });
			},
			loadProfileStore: async () => ({ store: { resolve: () => probeProfile() } }),
			createTtsProvider: () => ({ async synthesize() { return validSynthesis(); } }),
		}),
		initialRetryMs: 5,
		maxRetryMs: 5,
		startupTimeoutMs: 10,
		warmupTimeoutMs: 100,
		cleanupTimeoutMs: 20,
	});
	try {
		supervisor.start();
		await eventually(() => accessCalls >= 3 && component(supervisor, 'voice:tts').state === 'ready');
		assert.equal(aborts, 2, 'both stalled default access operations observe cancellation');
		for (const resolve of lateResolvers) resolve();
		await new Promise((resolve) => setImmediate(resolve));
		assert.equal(component(supervisor, 'voice:tts').state, 'ready', 'late filesystem success cannot replace recovery');
	} finally {
		await supervisor.close();
	}
});

function component(supervisor, name) {
	return supervisor.statusSnapshots().find(({ component: candidate }) => candidate === name);
}

function readySnapshots() {
	return [
		status('voice', 'ready'),
		status('voice:tts', 'ready'),
		status('voice:stt', 'ready'),
	];
}

function status(componentName, state) {
	return {
		component: componentName,
		state,
		fallbackMode: state === 'ready' ? null : 'text',
		boundary: null,
		failureCode: null,
		consecutiveFailureCount: 0,
		nextProbeAtEpochMs: null,
		generation: 1,
		lastRecoveryAtEpochMs: null,
	};
}

class ManualTimers {
	now = 0;
	tasks = [];

	schedule = (callback, delay) => {
		const task = { callback, at: this.now + delay, canceled: false };
		this.tasks.push(task);
		return task;
	};

	cancel = (task) => { task.canceled = true; };

	async runNext() {
		const task = this.tasks.find((candidate) => !candidate.canceled);
		if (task === undefined) throw new Error('no scheduled timer');
		task.canceled = true;
		this.now = task.at;
		task.callback();
		await flush();
	}

	async runNextEvenIfCancelled() {
		const task = this.tasks.at(-1);
		if (task === undefined) return;
		this.now = Math.max(this.now, task.at);
		task.callback();
		await flush();
	}
}

function probeProfile() {
	return { provider: 'test', model: 'test', voiceId: 'test', revision: 1, speed: 1, profileId: 'voice.test' };
}

function validSynthesis() {
	return { sampleRateHz: 44_100, channels: 1, sampleFormat: 's16le', pcm: Buffer.alloc(4) };
}

async function flush() {
	await new Promise((resolve) => setImmediate(resolve));
}

async function eventually(predicate) {
	for (let attempt = 0; attempt < 200; attempt += 1) {
		if (predicate()) return;
		await new Promise((resolve) => setTimeout(resolve, 5));
	}
	throw new Error('condition did not become true');
}
