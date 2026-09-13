import assert from 'node:assert/strict';
import { mkdtemp, rm, writeFile } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import path from 'node:path';
import test from 'node:test';

import { startVoiceWorker as startVoiceWorkerRuntime } from '../src/dynamic-main.mjs';
import { DeepgramSttProvider } from '../src/voice/deepgram-stt-provider.mjs';
import { FishTtsProvider } from '../src/voice/fish-tts-provider.mjs';
import { createVoiceRequestHeaders } from '../src/voice/voice-http-server.mjs';

const SECRET = 'voice-bootstrap-test-secret';
const VOICE_SECRET = 'dedicated-voice-bootstrap-secret';
const PLAYER = '10000000-0000-4000-8000-000000000001';

function fetch(input, init = {}) {
	const headers = new Headers(init.headers);
	const url = new URL(typeof input === 'string' ? input : input.url);
	if (init.method === 'POST' && ['/v1/tts', '/v1/stt'].includes(url.pathname)
			&& headers.has('X-Voice-Signature')) {
		const signed = createVoiceRequestHeaders({
			secret: VOICE_SECRET,
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

function voiceAuth(pathname) {
	return createVoiceRequestHeaders({ secret: VOICE_SECRET, path: pathname });
}

function startVoiceWorker(config, environment = {}, dependencies = {}) {
	return startVoiceWorkerRuntime(
		{ ...config, voice: { ...config.voice, secret: config.voice?.secret ?? SECRET } },
		environment,
		dependencies,
	);
}

test('voice bootstrap fails closed when the dedicated voice secret is unavailable', async () => {
	await assert.rejects(startVoiceWorkerRuntime({
		bridge: { secret: SECRET },
		voice: { port: 8_766, secretFile: 'missing-voice-secret.txt' },
	}, {}, {
		platform: 'linux',
		createLocalSpeechProvider: async () => ({ async close() {} }),
		readVoiceSecret: async () => { throw new Error('missing'); },
	}), (error) => error.code === 'VOICE_SECRET_UNAVAILABLE');
});

test('voice bootstrap uses credential-free Windows speech when Fish is not configured', async () => {
	let profileLoads = 0;
	let localProviders = 0;
	let starts = 0;
	const serverWorker = {
		async start() { starts++; return { port: 8766 }; },
		async close() {},
	};
	const created = await startVoiceWorker({
		bridge: { secret: SECRET },
		voice: { secret: VOICE_SECRET, port: 0 },
		fishApiKey: 'config-must-not-be-used',
	}, {}, {
		platform: 'win32',
		loadProfileStore: async () => { profileLoads++; return { store: { resolve() {} } }; },
		createWindowsTtsProvider: () => { localProviders++; return { synthesize: async () => ({}) }; },
		createVoiceServer: ({ provider }) => {
			assert.equal(typeof provider.synthesize, 'function');
			return serverWorker;
		},
	});

	assert.equal(created, serverWorker);
	assert.equal(profileLoads, 1);
	assert.equal(localProviders, 1);
	assert.equal(starts, 1);
	await created.close();
});

test('voice bootstrap remains disabled without a provider on non-Windows hosts', async () => {
	let profileLoads = 0;
	const worker = await startVoiceWorker({
		bridge: { secret: SECRET },
		voice: { secret: VOICE_SECRET, port: 0 },
	}, {}, {
		platform: 'linux',
		loadProfileStore: async () => { profileLoads++; return { store: { resolve() {} } }; },
	});

	assert.equal(worker, null);
	assert.equal(profileLoads, 0);
});

test('voice bootstrap starts Deepgram-only STT with no TTS provider on non-Windows hosts', async () => {
	let transcriptions = 0;
	const root = await mkdtemp(path.join(tmpdir(), 'arena-deepgram-only-'));
	const profilePath = path.join(root, 'voice-profile-assignments.json');
	await writeFile(profilePath, '{ malformed assignments', 'utf8');
	const created = await startVoiceWorker({
		bridge: { secret: SECRET },
		voice: { secret: VOICE_SECRET, port: 0 },
	}, { DEEPGRAM_API_KEY: 'deepgram-only-key' }, {
		platform: 'linux',
		profilePath,
		createLocalSpeechProvider: async () => null,
		createWindowsTtsProvider: () => { throw new Error('Windows TTS must not be created on Linux'); },
		createSttProvider: ({ apiKey }) => {
			assert.equal(apiKey, 'deepgram-only-key');
			return { async transcribe() {
				transcriptions += 1;
				return { transcript: 'heard', confidence: 1 };
			} };
		},
	});

	try {
		assert.equal(created.server.listening, true);
		const snapshots = created.statusSnapshots();
		assert.equal(snapshots.find(({ component }) => component === 'voice:tts').failureCode, 'TTS_UNAVAILABLE');
		assert.equal(snapshots.find(({ component }) => component === 'voice:stt').state, 'ready');
		const ttsResponse = await fetch(`http://127.0.0.1:${created.server.address().port}/v1/tts`, {
			method: 'POST',
			headers: { ...voiceAuth('/v1/tts'), 'Content-Type': 'application/json' },
			body: JSON.stringify({
				agentId: PLAYER,
				conversationSequence: 1,
				profileId: 'voice.auto.v1',
				radius: 32,
				text: 'No TTS is configured.',
			}),
		});
		assert.equal(ttsResponse.status, 503);
		assert.equal((await ttsResponse.json()).code, 'TTS_UNAVAILABLE');
		const response = await fetch(`http://127.0.0.1:${created.server.address().port}/v1/stt`, {
			method: 'POST',
			headers: {
				...voiceAuth('/v1/stt'),
				'Content-Type': 'audio/l16;rate=48000;channels=1',
				'X-Player-Id': PLAYER,
				'X-Utterance-Sequence': '1',
				'X-Whispering': 'false',
			},
			body: Buffer.alloc(2),
		});
		assert.equal(response.status, 200);
		assert.equal((await response.json()).transcript, 'heard');
		assert.equal(transcriptions, 1);
	} finally {
		await created.close();
		await rm(root, { recursive: true, force: true });
	}
});

test('voice bootstrap keeps malformed profile assignments fatal when TTS is available', async () => {
	const root = await mkdtemp(path.join(tmpdir(), 'arena-tts-profiles-'));
	const profilePath = path.join(root, 'voice-profile-assignments.json');
	await writeFile(profilePath, '{ malformed assignments', 'utf8');
	try {
		await assert.rejects(
			startVoiceWorker({
				bridge: { secret: SECRET },
				voice: { secret: VOICE_SECRET, port: 0 },
			}, { FISH_AUDIO_API_KEY: 'fish-key' }, {
				platform: 'linux',
				profilePath,
				createLocalSpeechProvider: async () => null,
				createTtsProvider: () => ({ async synthesize() { return {}; } }),
			}),
			SyntaxError,
		);
	} finally {
		await rm(root, { recursive: true, force: true });
	}
});

test('voice bootstrap reads Fish and optional STT credentials from environment only', async () => {
	const captured = {};
	const worker = {
		async start() { captured.started = true; return { port: 8766 }; },
		async close() { captured.closed = (captured.closed ?? 0) + 1; },
	};
	const profiles = { store: { resolve() {} }, flush: async () => {} };
	const created = await startVoiceWorker({
		bridge: { secret: SECRET },
		voice: { secret: VOICE_SECRET, port: 8766, maxConcurrent: 2 },
		fishApiKey: 'config-must-not-be-used',
	}, {
		FISH_AUDIO_API_KEY: '',
		FISH_API_KEY: 'fish-from-environment',
		DEEPGRAM_API_KEY: 'deepgram-from-environment',
	}, {
		loadProfileStore: async (filePath) => {
			captured.profilePath = filePath;
			return profiles;
		},
		createTtsProvider: ({ apiKey }) => {
			captured.fishApiKey = apiKey;
			return { synthesize: async () => ({}) };
		},
		createSttProvider: ({ apiKey }) => {
			captured.deepgramApiKey = apiKey;
			return { transcribe: async () => ({ transcript: '', confidence: 0 }) };
		},
		createVoiceServer: (options) => {
			captured.serverOptions = options;
			return worker;
		},
		profilePath: 'test-profile-assignments.json',
	});

	assert.equal(created, worker);
	assert.equal(captured.fishApiKey, 'fish-from-environment');
	assert.equal(captured.deepgramApiKey, 'deepgram-from-environment');
	assert.equal(captured.serverOptions.secret, VOICE_SECRET);
	assert.equal(captured.serverOptions.port, 8766);
	assert.equal(captured.serverOptions.maxConcurrent, 2);
	assert.equal(captured.started, true);
	assert.equal(captured.profilePath, 'test-profile-assignments.json');
	assert.doesNotMatch(JSON.stringify(captured.serverOptions), /fish-from-environment|deepgram-from-environment/);

	await created.close();
	assert.equal(captured.closed, 1);
});

test('Windows voice bootstrap falls back to local speech when Fish rejects a stale credential', async () => {
	const rejected = new Error('Fish rejected stale key must-not-reach-output');
	rejected.code = 'TTS_PROVIDER_ERROR';
	let provider;
	let localCalls = 0;
	const worker = await startVoiceWorker({
		bridge: { secret: SECRET },
		voice: { secret: VOICE_SECRET, port: 8_766 },
	}, { FISH_AUDIO_API_KEY: 'stale-fish-credential' }, {
		platform: 'win32',
		loadProfileStore: async () => ({ store: { resolve() {} } }),
		createTtsProvider: () => ({ async synthesize() { throw rejected; } }),
		createWindowsTtsProvider: () => ({ async synthesize() {
			localCalls++;
			return { sampleRateHz: 16_000, channels: 1, sampleFormat: 's16le', pcm: Buffer.alloc(2) };
		} }),
		createVoiceServer: (options) => {
			provider = options.provider;
			return { async start() {}, async close() {} };
		},
	});

	const result = await provider.synthesize({ text: 'Hello.', voiceId: 'ignored', speed: 1 });
	assert.equal(result.sampleRateHz, 16_000);
	assert.equal(result.cacheable, false, 'fallback audio cannot populate the Fish profile cache');
	assert.equal(localCalls, 1);
	assert.doesNotMatch(JSON.stringify(result), /stale-fish-credential|must-not-reach-output/);
	await worker.close();
});

test('Windows Fish fallback opens a bounded circuit and recovers through one half-open probe', async () => {
	const rejected = Object.assign(new Error('Fish is unavailable'), { code: 'TTS_PROVIDER_ERROR' });
	let now = 0;
	let fishCalls = 0;
	let windowsCalls = 0;
	let fishHealthy = false;
	let cancelFish = false;
	let pendingProbe = null;
	let provider;
	const worker = await startVoiceWorker({
		bridge: { secret: SECRET },
		voice: { secret: VOICE_SECRET, port: 8_766 },
	}, { FISH_AUDIO_API_KEY: 'configured-fish-credential' }, {
		platform: 'win32',
		voiceFallbackNow: () => now,
		voiceFallbackBaseDelayMs: 10,
		voiceFallbackMaxDelayMs: 40,
		loadProfileStore: async () => ({ store: { resolve() {} } }),
		createTtsProvider: () => ({
			async synthesize() {
				fishCalls += 1;
				if (cancelFish) {
					const error = new Error('request cancelled');
					error.name = 'AbortError';
					throw error;
				}
				if (pendingProbe !== null) await pendingProbe.promise;
				if (!fishHealthy) throw rejected;
				return { sampleRateHz: 44_100, channels: 1, sampleFormat: 's16le', pcm: Buffer.from([1, 1]) };
			},
		}),
		createWindowsTtsProvider: () => ({
			async synthesize() {
				windowsCalls += 1;
				return { sampleRateHz: 16_000, channels: 1, sampleFormat: 's16le', pcm: Buffer.from([2, 2]) };
			},
		}),
		createVoiceServer: (options) => {
			provider = options.provider;
			return { async start() {}, async close() {} };
		},
	});
	const request = (text) => provider.synthesize({ text, voiceId: 'ignored', speed: 1 });

	assert.equal((await request('failure zero')).cacheable, false);
	now = 9;
	assert.equal((await request('open zero')).cacheable, false);
	assert.equal(fishCalls, 1, 'open circuit bypasses Fish until the first probe');
	now = 10;
	assert.equal((await request('failure one')).cacheable, false);
	now = 29;
	await request('open one');
	assert.equal(fishCalls, 2);
	now = 30;
	await request('failure two');
	now = 69;
	await request('open two');
	assert.equal(fishCalls, 3);

	now = 70;
	pendingProbe = Promise.withResolvers();
	const halfOpen = request('half-open failure');
	assert.equal(fishCalls, 4);
	const concurrent = await request('concurrent fallback');
	assert.equal(concurrent.cacheable, false);
	assert.equal(fishCalls, 4, 'concurrent traffic cannot stampede the half-open Fish probe');
	pendingProbe.resolve();
	await halfOpen;
	pendingProbe = null;
	now = 109;
	await request('bounded open interval');
	assert.equal(fishCalls, 4, 'the exponential delay is capped at the configured maximum');

	now = 110;
	fishHealthy = true;
	const recovered = await request('recovery probe');
	assert.equal(recovered.sampleRateHz, 44_100);
	assert.equal(recovered.cacheable, undefined, 'healthy Fish output keeps normal cache semantics');
	await request('closed circuit');
	assert.equal(fishCalls, 6, 'a successful half-open probe restores normal Fish traffic');
	assert.equal(windowsCalls, 9);
	cancelFish = true;
	await assert.rejects(request('cancelled request'), (error) => error.name === 'AbortError');
	assert.equal(windowsCalls, 9, 'request cancellation does not route stale work through Windows');
	cancelFish = false;
	await request('healthy after cancellation');
	assert.equal(fishCalls, 8, 'request cancellation does not open the Fish circuit');
	await worker.close();
});

test('Fish fetch failures open the Windows circuit without treating invalid requests as outages', async () => {
	let fetchCalls = 0;
	let windowsCalls = 0;
	let provider;
	const worker = await startVoiceWorker({ bridge: { secret: SECRET }, voice: { secret: VOICE_SECRET, port: 8_766 } }, {
		FISH_AUDIO_API_KEY: 'configured-fish-credential',
	}, {
		platform: 'win32',
		voiceFallbackNow: () => 0,
		voiceFallbackBaseDelayMs: 100,
		voiceFallbackMaxDelayMs: 100,
		loadProfileStore: async () => ({ store: { resolve() {} } }),
		createTtsProvider: ({ apiKey }) => new FishTtsProvider({
			apiKey,
			fetchImpl: async () => {
				fetchCalls += 1;
				throw new TypeError('fetch failed');
			},
		}),
		createWindowsTtsProvider: () => ({
			async synthesize() {
				windowsCalls += 1;
				return { sampleRateHz: 16_000, channels: 1, sampleFormat: 's16le', pcm: Buffer.alloc(2) };
			},
		}),
		createVoiceServer: (options) => {
			provider = options.provider;
			return { async start() {}, async close() {} };
		},
	});
	try {
		await assert.rejects(
			provider.synthesize({ text: '', voiceId: 'voice-id', speed: 1 }),
			(error) => error instanceof TypeError,
		);
		assert.equal(fetchCalls, 0, 'invalid requests fail before the Fish transport');
		assert.equal(windowsCalls, 0, 'invalid requests do not use Windows speech');
		await provider.synthesize({ text: 'network failure', voiceId: 'voice-id', speed: 1 });
		await provider.synthesize({ text: 'open circuit', voiceId: 'voice-id', speed: 1 });
		assert.equal(fetchCalls, 1, 'the network failure opens the Fish circuit');
		assert.equal(windowsCalls, 2);
	} finally {
		await worker.close();
	}
});

test('voice bootstrap closes a worker when binding fails', async () => {
	let closes = 0;
	await assert.rejects(
		startVoiceWorker({ bridge: { secret: SECRET }, voice: { secret: VOICE_SECRET, port: 0 } }, { FISH_AUDIO_API_KEY: 'fish-from-environment' }, {
			loadProfileStore: async () => ({ store: { resolve() {} } }),
			createVoiceServer: () => ({
				async start() { throw new Error('bind failed'); },
				async close() { closes++; },
			}),
		}),
		/bind failed/,
	);
	assert.equal(closes, 1);
});

test('voice bootstrap closes a prepared local provider when later setup fails', async () => {
	let closes = 0;
	const local = {
		async synthesize() { return {}; },
		async transcribe() { return { transcript: '', confidence: 0 }; },
		async close() { closes += 1; },
	};
	await assert.rejects(
		startVoiceWorker({ bridge: { secret: SECRET }, voice: { secret: VOICE_SECRET, port: 8_766 } }, {}, {
			platform: 'win32',
			createLocalSpeechProvider: async () => local,
			loadProfileStore: async () => { throw new Error('profile setup failed'); },
		}),
		/profile setup failed/,
	);
	assert.equal(closes, 1, 'partially prepared provider is not leaked between retries');
});

test('voice bootstrap prefers one local speech runtime for both expressive TTS and STT', async () => {
	const captured = {};
	let localCloses = 0;
	let warmups = 0;
	let serverCloses = 0;
	const local = {
		async warmup() { warmups++; },
		async synthesize() { return {}; },
		async transcribe() { return { transcript: '', confidence: 0 }; },
		async close() { localCloses++; },
	};
	const created = await startVoiceWorker({
		bridge: { secret: SECRET },
		voice: { secret: VOICE_SECRET, port: 8_766 },
	}, {}, {
		platform: 'win32',
		loadProfileStore: async () => ({ store: { resolve() {} } }),
		createLocalSpeechProvider: async () => local,
		createWindowsTtsProvider: () => { throw new Error('Windows fallback must not replace an available local provider'); },
		createVoiceServer: (options) => {
			captured.options = options;
			return { async start() {}, async close() { serverCloses++; } };
		},
	});

	assert.notEqual(captured.options.provider, local, 'local speech is wrapped so a failed warmup can fail over');
	assert.notEqual(captured.options.sttProvider, local, 'local STT is wrapped so a failed warmup can fail over');
	assert.equal(warmups, 0, 'worker bind does not await optional model warmup');
	await created.warmup();
	assert.equal(warmups, 1);
	await created.close();
	assert.equal(serverCloses, 1);
	assert.equal(localCloses, 1);
});

test('configured expressive Fish TTS and low-latency Deepgram STT stay primary over local speech', async () => {
	let active;
	let localTtsCalls = 0;
	let localSttCalls = 0;
	let fishCalls = 0;
	let deepgramCalls = 0;
	const local = {
		async synthesize() { localTtsCalls += 1; return { provider: 'local-chatterbox' }; },
		async transcribe() { localSttCalls += 1; return { transcript: 'local whisper', confidence: 1 }; },
		async close() {},
	};
	const worker = await startVoiceWorker({ bridge: { secret: SECRET }, voice: { secret: VOICE_SECRET, port: 8_766 } }, {
		FISH_AUDIO_API_KEY: 'fish-key',
		DEEPGRAM_API_KEY: 'deepgram-key',
	}, {
		platform: 'win32',
		createLocalSpeechProvider: async () => local,
		loadProfileStore: async () => ({ store: { resolve() {} } }),
		createTtsProvider: () => ({
			async synthesize() { fishCalls += 1; return { provider: 'fish', model: 's2.1-pro-free' }; },
		}),
		createSttProvider: () => ({
			async transcribe() { deepgramCalls += 1; return { transcript: 'deepgram', confidence: 1 }; },
		}),
		createWindowsTtsProvider: () => ({ async synthesize() { return { provider: 'windows' }; } }),
		createVoiceServer: (options) => {
			active = options;
			return { async start() {}, async close() {} };
		},
	});
	try {
		assert.equal((await active.provider.synthesize({ text: 'hello' })).provider, 'fish');
		assert.equal((await active.sttProvider.transcribe({ pcm: Buffer.alloc(2) })).transcript, 'deepgram');
		assert.deepEqual([fishCalls, deepgramCalls, localTtsCalls, localSttCalls], [1, 1, 0, 0]);
	} finally {
		await worker.close();
	}
});

test('missing Fish credentials emit a bounded startup diagnostic with the actual local provider', async () => {
	const diagnostics = [];
	const local = {
		cacheNamespace: () => 'local-chatterbox/chatterbox-v1',
		async synthesize() { return { provider: 'local' }; },
		async transcribe() { return { transcript: '', confidence: 0 }; },
		async close() {},
	};
	const worker = await startVoiceWorker({ bridge: { secret: SECRET }, voice: { secret: VOICE_SECRET, port: 8_766 } }, {}, {
		platform: 'linux',
		createLocalSpeechProvider: async () => local,
		loadProfileStore: async () => ({ store: { resolve() {} } }),
		reportVoiceDiagnostic: (event) => diagnostics.push(event),
		createVoiceServer: () => ({ async start() {}, async close() {} }),
	});
	try {
		assert.deepEqual(diagnostics, [{
			code: 'VOICE_TTS_REMOTE_UNCONFIGURED',
			effectiveProvider: 'local-chatterbox/chatterbox-v1',
			reason: 'fish_credential_missing',
		}]);
		assert.ok(JSON.stringify(diagnostics).length <= 256);
	} finally {
		await worker.close();
	}
});

test('Fish failure reports the fallback reason and the HTTP seam exposes local synthesis', async () => {
	const diagnostics = [];
	const local = {
		cacheNamespace: () => 'local-chatterbox/chatterbox-v1',
		async synthesize() {
			return { sampleRateHz: 48_000, channels: 1, sampleFormat: 's16le', pcm: Buffer.alloc(960, 1) };
		},
		async transcribe() { return { transcript: '', confidence: 0 }; },
		async close() {},
	};
	const worker = await startVoiceWorker({ bridge: { secret: SECRET }, voice: { secret: VOICE_SECRET, port: 0 } }, {
		FISH_AUDIO_API_KEY: 'must-never-appear-in-diagnostics',
	}, {
		platform: 'linux',
		createLocalSpeechProvider: async () => local,
		loadProfileStore: async () => ({ store: { resolve() { return {
			profileId: 'voice.test', provider: 'fish', model: 's2.1-pro-free', voiceId: 'fish-id', revision: 1, speed: 1,
		}; } } }),
		createTtsProvider: () => ({
			cacheNamespace: () => 'fish/s2.1-pro-free',
			async synthesize() { throw Object.assign(new Error('remote rejected a credential'), { code: 'TTS_AUTHENTICATION_FAILED' }); },
		}),
		reportVoiceDiagnostic: (event) => diagnostics.push(event),
	});
	try {
		const body = JSON.stringify({
			agentId: PLAYER, conversationSequence: 1, profileId: 'voice.auto.v1', radius: 48, text: 'Fallback truthfully.',
		});
		const response = await fetch(`http://127.0.0.1:${worker.server.address().port}/v1/tts`, {
			method: 'POST', headers: { ...voiceAuth('/v1/tts'), 'Content-Type': 'application/json' }, body,
		});
		assert.equal(response.status, 200);
		assert.equal(response.headers.get('x-voice-synthesizer'), 'local-chatterbox/chatterbox-v1');
		await response.arrayBuffer();
		assert.equal(
			worker.statusSnapshots().find(({ component }) => component === 'voice:tts').effectiveProvider,
			'local-chatterbox/chatterbox-v1',
		);
		assert.ok(diagnostics.some((event) => event.code === 'VOICE_TTS_FALLBACK_ACTIVATED'
			&& event.primaryProvider === 'fish/s2.1-pro-free'
			&& event.effectiveProvider === 'local-chatterbox/chatterbox-v1'
			&& event.failureCode === 'TTS_AUTHENTICATION_FAILED'));
		assert.ok(diagnostics.some((event) => event.code === 'VOICE_TTS_EFFECTIVE_PROVIDER'
			&& event.effectiveProvider === 'local-chatterbox/chatterbox-v1'));
		assert.doesNotMatch(JSON.stringify(diagnostics), /must-never-appear-in-diagnostics|remote rejected a credential/);
	} finally {
		await worker.close();
	}
});

test('configured Fish handles concurrent TTS while local STT remains available as the independent channel', async () => {
	let localTtsCalls = 0;
	let localCloses = 0;
	let fishProviders = 0;
	let fishCalls = 0;
	let fishCloses = 0;
	let profileCloses = 0;
	const local = {
		async synthesize() {
			localTtsCalls += 1;
			await new Promise((resolve) => setImmediate(resolve));
			throw Object.assign(new Error('local CUDA allocation failed'), { code: 'LOCAL_TTS_ERROR' });
		},
		async transcribe() { return { transcript: 'local hearing remains active', confidence: 1 }; },
		async close() { localCloses += 1; },
	};
	const worker = await startVoiceWorker({ bridge: { secret: SECRET }, voice: { secret: VOICE_SECRET, port: 0 } }, {
		FISH_AUDIO_API_KEY: 'fish-key',
	}, {
		platform: 'linux',
		createLocalSpeechProvider: async () => local,
		loadProfileStore: async () => ({
			store: { resolve() { return {
				profileId: 'voice.test', provider: 'fish', model: 'test', voiceId: 'fish-id', revision: 1, speed: 1,
			}; } },
			async close() { profileCloses += 1; },
		}),
		createTtsProvider: () => {
			fishProviders += 1;
			return {
				async synthesize() {
					fishCalls += 1;
					return { sampleRateHz: 24_000, channels: 1, sampleFormat: 's16le', pcm: Buffer.alloc(960) };
				},
				async close() { fishCloses += 1; },
			};
		},
	});
	try {
		const baseUrl = `http://127.0.0.1:${worker.server.address().port}`;
		const request = (text, conversationSequence) => fetch(`${baseUrl}/v1/tts`, {
			method: 'POST',
			headers: { ...voiceAuth('/v1/tts'), 'Content-Type': 'application/json' },
			body: JSON.stringify({
				agentId: '00000000-0000-4000-8000-000000000001',
				conversationSequence,
				profileId: 'voice.auto.v1',
				radius: 48,
				text,
			}),
		});
		const responses = await Promise.all([request('first request', 1), request('second request', 2)]);
		assert.deepEqual(responses.map(({ status }) => status), [200, 200], 'the failed local requests recover in the same HTTP attempts');
		assert.equal(fishProviders, 1, 'concurrent local failures create one shared fallback provider');
		assert.equal(localTtsCalls, 0, 'configured Fish stays primary instead of waiting for local TTS to fail');
		assert.equal(fishCalls, 2);
		assert.equal(localCloses, 0, 'local speech remains alive for STT after a TTS-only failover');
		assert.equal((await local.transcribe()).transcript, 'local hearing remains active');
	} finally {
		await worker.close();
	}
	assert.equal(localCloses, 1);
	assert.equal(fishCloses, 1);
	assert.equal(profileCloses, 1);
});

test('configured Fish cache never returns local speech', async () => {
	let localCalls = 0;
	let fishCalls = 0;
	const local = {
		async synthesize({ text }) {
			localCalls += 1;
			if (text === 'switch provider') {
				throw Object.assign(new Error('local TTS failed'), { code: 'LOCAL_TTS_ERROR' });
			}
			return {
				sampleRateHz: 24_000, channels: 1, sampleFormat: 's16le', pcm: Buffer.from([1, 0, 1, 0]),
				provider: 'local-chatterbox', model: 'chatterbox-v1', voiceId: 'local.voice',
			};
		},
		async transcribe() { return { transcript: '', confidence: 0 }; },
		async close() {},
	};
	const worker = await startVoiceWorker({ bridge: { secret: SECRET }, voice: { secret: VOICE_SECRET, port: 0 } }, {
		FISH_AUDIO_API_KEY: 'fish-key',
	}, {
		platform: 'linux',
		createLocalSpeechProvider: async () => local,
		loadProfileStore: async () => ({ store: { resolve() { return {
			profileId: 'voice.test', provider: 'fish', model: 's2.1-pro-free', voiceId: 'fish-id', revision: 1, speed: 1,
		}; } } }),
		createTtsProvider: () => ({
			async synthesize() {
				fishCalls += 1;
				return {
					sampleRateHz: 24_000, channels: 1, sampleFormat: 's16le', pcm: Buffer.from([2, 0, 2, 0]),
					provider: 'fish', model: 's2.1-pro-free', voiceId: 'fish-id',
				};
			},
		}),
	});
	try {
		const baseUrl = `http://127.0.0.1:${worker.server.address().port}`;
		const request = async (text, conversationSequence) => fetch(`${baseUrl}/v1/tts`, {
			method: 'POST',
			headers: { ...voiceAuth('/v1/tts'), 'Content-Type': 'application/json' },
			body: JSON.stringify({
				agentId: '00000000-0000-4000-8000-000000000001', conversationSequence,
				profileId: 'voice.auto.v1', radius: 48, text,
			}),
		});
		assert.equal((await request('repeat me', 1)).status, 200);
		assert.equal((await request('switch provider', 2)).status, 200);
		assert.equal((await request('repeat me', 3)).status, 200);
		assert.equal(localCalls, 0);
		assert.equal(fishCalls, 2, 'the repeated utterance is synthesized by Fish after failover');
	} finally {
		await worker.close();
	}
});

test('nested runtime fallback caches a successful Fish probe only as Fish audio', async () => {
	const fishFailure = Object.assign(new Error('Fish is unavailable'), { code: 'TTS_PROVIDER_ERROR' });
	let now = 0;
	let fishHealthy = false;
	let localCalls = 0;
	let fishCalls = 0;
	let windowsCalls = 0;
	const local = {
		async synthesize() {
			localCalls += 1;
			throw Object.assign(new Error('local TTS failed'), { code: 'LOCAL_TTS_ERROR' });
		},
		async transcribe() { return { transcript: '', confidence: 0 }; },
		async close() {},
	};
	const worker = await startVoiceWorker({ bridge: { secret: SECRET }, voice: { secret: VOICE_SECRET, port: 0 } }, {
		FISH_AUDIO_API_KEY: 'fish-key',
	}, {
		platform: 'win32',
		voiceFallbackNow: () => now,
		voiceFallbackBaseDelayMs: 10,
		voiceFallbackMaxDelayMs: 10,
		createLocalSpeechProvider: async () => local,
		loadProfileStore: async () => ({ store: { resolve() { return {
			profileId: 'voice.test', provider: 'fish', model: 's2.1-pro-free', voiceId: 'fish-id', revision: 1, speed: 1,
		}; } } }),
		createTtsProvider: () => ({
			cacheNamespace: () => 'fish/test',
			async synthesize() {
				fishCalls += 1;
				if (!fishHealthy) throw fishFailure;
				return { sampleRateHz: 48_000, channels: 1, sampleFormat: 's16le', pcm: Buffer.from([1, 0, 1, 0]) };
			},
		}),
		createWindowsTtsProvider: () => ({
			cacheNamespace: () => 'windows/test',
			async synthesize() {
				windowsCalls += 1;
				return { sampleRateHz: 48_000, channels: 1, sampleFormat: 's16le', pcm: Buffer.from([2, 0, 2, 0]) };
			},
		}),
	});
	try {
		const baseUrl = `http://127.0.0.1:${worker.server.address().port}`;
		let sequence = 0;
		const request = (text) => fetch(`${baseUrl}/v1/tts`, {
			method: 'POST',
			headers: { ...voiceAuth('/v1/tts'), 'Content-Type': 'application/json' },
			body: JSON.stringify({
				agentId: '00000000-0000-4000-8000-000000000001', conversationSequence: ++sequence,
				profileId: 'voice.auto.v1', radius: 48, text,
			}),
		});

		assert.equal((await request('open the circuit')).status, 200);
		assert.deepEqual([localCalls, fishCalls, windowsCalls], [1, 1, 1]);

		now = 10;
		fishHealthy = true;
		assert.equal((await request('repeat me')).status, 200, 'the half-open Fish probe succeeds');
		assert.equal((await request('repeat me')).status, 200);
		assert.equal(fishCalls, 2, 'closed-circuit Fish cache lookup reuses the probe result');

		fishHealthy = false;
		assert.equal((await request('reopen the circuit')).status, 200);
		assert.equal((await request('repeat me')).status, 200);
		assert.equal(windowsCalls, 3, 'the reopened circuit cannot return Fish audio from the Windows cache namespace');
	} finally {
		await worker.close();
	}
});

test('configured Deepgram handles concurrent STT while local TTS stays available', async () => {
	let active;
	let localSttCalls = 0;
	let localCloses = 0;
	let deepgramProviders = 0;
	let deepgramCalls = 0;
	const local = {
		async synthesize() { return { provider: 'local' }; },
		async transcribe() {
			localSttCalls += 1;
			await new Promise((resolve) => setImmediate(resolve));
			throw Object.assign(new Error('local Whisper inference failed'), { code: 'LOCAL_STT_ERROR' });
		},
		async close() { localCloses += 1; },
	};
	const worker = await startVoiceWorker({ bridge: { secret: SECRET }, voice: { secret: VOICE_SECRET, port: 8_766 } }, {
		DEEPGRAM_API_KEY: 'deepgram-key',
	}, {
		platform: 'linux',
		createLocalSpeechProvider: async () => local,
		loadProfileStore: async () => ({ store: { resolve() {} } }),
		createSttProvider: () => {
			deepgramProviders += 1;
			return {
				async transcribe() {
					deepgramCalls += 1;
					return { transcript: 'remote transcript', confidence: 1 };
				},
			};
		},
		createVoiceServer: (options) => {
			active = options;
			return { async start() {}, async close() {} };
		},
	});
	try {
		const results = await Promise.all([
			active.sttProvider.transcribe({ pcm: Buffer.alloc(2) }),
			active.sttProvider.transcribe({ pcm: Buffer.alloc(2) }),
		]);
		assert.deepEqual(results.map(({ transcript }) => transcript), ['remote transcript', 'remote transcript']);
		assert.equal(localSttCalls, 0, 'configured Deepgram stays primary instead of waiting for local Whisper to fail');
		assert.equal(deepgramProviders, 1, 'concurrent local failures create one shared Deepgram provider');
		assert.equal(deepgramCalls, 2);
		assert.equal((await active.provider.synthesize({ text: 'still local' })).provider, 'local');
		assert.equal(localCloses, 0, 'STT-only failover cannot stop local TTS');
	} finally {
		await worker.close();
	}
	assert.equal(localCloses, 1);
});

test('Deepgram transport failure falls back to local hearing', async () => {
	let active;
	let localSttCalls = 0;
	const local = {
		async synthesize() { return { provider: 'local' }; },
		async transcribe() {
			localSttCalls += 1;
			return { transcript: 'local fallback', confidence: 1 };
		},
		async close() {},
	};
	const worker = await startVoiceWorker({ bridge: { secret: SECRET }, voice: { secret: VOICE_SECRET, port: 8_766 } }, {
		DEEPGRAM_API_KEY: 'deepgram-key',
	}, {
		platform: 'linux',
		createLocalSpeechProvider: async () => local,
		loadProfileStore: async () => ({ store: { resolve() {} } }),
		createSttProvider: () => new DeepgramSttProvider({
			apiKey: 'deepgram-key',
			fetchImpl: async () => { throw new TypeError('fetch failed'); },
		}),
		createVoiceServer: (options) => {
			active = options;
			return { async start() {}, async close() {} };
		},
	});
	try {
		const result = await active.sttProvider.transcribe({ pcm: Buffer.alloc(2) });
		assert.equal(result.transcript, 'local fallback');
		assert.equal(localSttCalls, 1, 'a transient remote transport failure keeps speech recognition available');
	} finally {
		await worker.close();
	}
});

test('voice bootstrap applies the configured local inference deadline to live HTTP requests', async () => {
	let localOptions;
	let serverOptions;
	const worker = await startVoiceWorker({
		bridge: { secret: SECRET },
		voice: { secret: VOICE_SECRET, port: 8_766, localSpeechTimeoutMs: 91_234 },
	}, {}, {
		platform: 'win32',
		createLocalSpeechProvider: async (options) => {
			localOptions = options;
			return {
				async synthesize() { return {}; },
				async transcribe() { return { transcript: '', confidence: 0 }; },
				async close() {},
			};
		},
		loadProfileStore: async () => ({ store: { resolve() {} } }),
		createVoiceServer: (options) => {
			serverOptions = options;
			return { async start() {}, async close() {} };
		},
	});
	try {
		assert.equal(localOptions.timeoutMs, 91_234);
		assert.equal(serverOptions.requestTimeoutMs, 91_234);
	} finally {
		await worker.close();
	}
});

test('runtime local TTS failure uses credential-free Windows speech when Fish is absent', async () => {
	let provider;
	let localCloses = 0;
	let windowsProviders = 0;
	const local = {
		async synthesize() { throw Object.assign(new Error('local inference failed'), { code: 'LOCAL_TTS_ERROR' }); },
		async transcribe() { return { transcript: 'still local', confidence: 1 }; },
		async close() { localCloses += 1; },
	};
	const worker = await startVoiceWorker({ bridge: { secret: SECRET }, voice: { secret: VOICE_SECRET, port: 8_766 } }, {}, {
		platform: 'win32',
		createLocalSpeechProvider: async () => local,
		loadProfileStore: async () => ({ store: { resolve() {} } }),
		createWindowsTtsProvider: () => {
			windowsProviders += 1;
			return { async synthesize() { return { provider: 'windows' }; } };
		},
		createVoiceServer: (options) => {
			provider = options.provider;
			return { async start() {}, async close() {} };
		},
	});
	try {
		assert.equal((await provider.synthesize({ text: 'hello' })).provider, 'windows');
		assert.equal(windowsProviders, 1);
		assert.equal(localCloses, 0, 'TTS failover cannot stop the shared local STT process');
	} finally {
		await worker.close();
	}
	assert.equal(localCloses, 1);
});

test('replacement workers release each profile owner exactly once', async () => {
	let profileLoads = 0;
	let profileCloses = 0;
	let serverCloses = 0;
	for (let generation = 1; generation <= 2; generation += 1) {
		const worker = await startVoiceWorker({ bridge: { secret: SECRET }, voice: { secret: VOICE_SECRET, port: 8_766 } }, {
			FISH_AUDIO_API_KEY: 'fish-key',
		}, {
			platform: 'linux',
			createLocalSpeechProvider: async () => null,
			loadProfileStore: async () => {
				profileLoads += 1;
				return { store: { resolve() {} }, async close() { profileCloses += 1; } };
			},
			createTtsProvider: () => ({ async synthesize() { return {}; } }),
			createVoiceServer: ({ profileStore }) => ({
				async start() {},
				async close() {
					serverCloses += 1;
					await Promise.all([profileStore.close(), profileStore.close()]);
				},
			}),
		});
		await worker.close();
		assert.equal(profileCloses, generation);
	}
	assert.equal(profileLoads, 2);
	assert.equal(profileCloses, 2);
	assert.equal(serverCloses, 2);
});

test('pre-worker bootstrap failures close every loaded profile owner across retries', async () => {
	let activeProfileOwners = 0;
	let profileCloses = 0;
	for (let attempt = 1; attempt <= 3; attempt += 1) {
		await assert.rejects(
			startVoiceWorker({ bridge: { secret: SECRET }, voice: { secret: VOICE_SECRET, port: 8_766 } }, {
				FISH_AUDIO_API_KEY: 'fish-key',
			}, {
				platform: 'linux',
				createLocalSpeechProvider: async () => null,
				loadProfileStore: async () => {
					activeProfileOwners += 1;
					let closed = false;
					return {
						store: { resolve() {} },
						async close() {
							if (closed) return;
							closed = true;
							activeProfileOwners -= 1;
							profileCloses += 1;
						},
					};
				},
				createTtsProvider: () => { throw new Error(`provider construction failed ${attempt}`); },
			}),
			new RegExp(`provider construction failed ${attempt}`),
		);
		assert.equal(activeProfileOwners, 0, `retry ${attempt} did not retain a profile owner`);
	}
	assert.equal(profileCloses, 3);
});

test('voice bootstrap exposes slow local warmup without delaying the bound worker', async () => {
	let releaseWarmup;
	const warmupGate = new Promise((resolve) => { releaseWarmup = resolve; });
	const local = {
		async warmup() { await warmupGate; },
		async synthesize() { return {}; },
		async transcribe() { return { transcript: '', confidence: 0 }; },
		async close() {},
	};
	const worker = await startVoiceWorker({ bridge: { secret: SECRET }, voice: { secret: VOICE_SECRET, port: 8_766 } }, {}, {
		platform: 'win32',
		loadProfileStore: async () => ({ store: { resolve() {} } }),
		createLocalSpeechProvider: async () => local,
		createVoiceServer: () => ({ async start() {}, async close() {} }),
	});
	let settled = false;
	const warming = worker.warmup().then(() => { settled = true; });
	await new Promise((resolve) => setImmediate(resolve));
	assert.equal(settled, false);
	releaseWarmup();
	await warming;
	assert.equal(settled, true);
	await worker.close();
});

test('local model warmup failure switches both channels to configured remote providers', async () => {
	let active;
	let localCloses = 0;
	const local = {
		async warmup() { throw Object.assign(new Error('Chatterbox import failed'), { code: 'LOCAL_SPEECH_WARMUP_FAILED' }); },
		async synthesize() { throw new Error('local TTS must be replaced'); },
		async transcribe() { throw new Error('local STT must be replaced'); },
		async close() { localCloses += 1; },
	};
	const fish = { async synthesize() { return {}; } };
	const deepgram = { async transcribe() { return { transcript: 'fallback', confidence: 1 }; } };
	const worker = await startVoiceWorker({ bridge: { secret: SECRET }, voice: { secret: VOICE_SECRET, port: 8_766 } }, {
		FISH_AUDIO_API_KEY: 'fish-key',
		DEEPGRAM_API_KEY: 'deepgram-key',
	}, {
		platform: 'linux',
		createLocalSpeechProvider: async () => local,
		loadProfileStore: async () => ({ store: { resolve() { return { profileId: 'voice.test', provider: 'fish', model: 'test', voiceId: 'fish-id', revision: 1, speed: 1 }; } } }),
		createTtsProvider: () => fish,
		createSttProvider: () => deepgram,
		createVoiceServer: (options) => {
			active = options;
			return { async start() {}, async close() {} };
		},
	});
	try {
		await worker.warmup();
		assert.equal(localCloses, 0, 'the local process remains available as a runtime fallback');
		assert.notEqual(active.provider, local);
		assert.notEqual(active.sttProvider, local);
		assert.equal((await active.sttProvider.transcribe({ pcm: Buffer.alloc(2) })).transcript, 'fallback');
	} finally {
		await worker.close();
	}
	assert.equal(localCloses, 1);
});

test('TTS-only warmup failure keeps ready local STT while routing speech to Fish', async () => {
	let active;
	let localCloses = 0;
	let fishProviders = 0;
	let warmupCalls = 0;
	const local = {
		async warmup() {
			warmupCalls += 1;
			await new Promise((resolve) => setImmediate(resolve));
			return { sttReady: true, ttsReady: false };
		},
		async synthesize() { throw new Error('failed local TTS must not receive traffic'); },
		async transcribe() { return { transcript: 'local Whisper stayed ready', confidence: 1 }; },
		async close() { localCloses += 1; },
	};
	const worker = await startVoiceWorker({ bridge: { secret: SECRET }, voice: { secret: VOICE_SECRET, port: 8_766 } }, {
		FISH_AUDIO_API_KEY: 'fish-key',
	}, {
		platform: 'linux',
		createLocalSpeechProvider: async () => local,
		loadProfileStore: async () => ({ store: { resolve() {} } }),
		createTtsProvider: () => {
			fishProviders += 1;
			return { async synthesize() { return { provider: 'fish' }; } };
		},
		createSttProvider: () => { throw new Error('Deepgram must not be created without a credential'); },
		createVoiceServer: (options) => {
			active = options;
			return { async start() {}, async close() {} };
		},
	});
	try {
		await Promise.all([worker.warmup(), worker.warmup()]);
		assert.equal(warmupCalls, 1, 'concurrent warmup calls share one channel transition');
		assert.equal(fishProviders, 1);
		assert.equal((await active.provider.synthesize({ text: 'hello' })).provider, 'fish');
		assert.equal((await active.sttProvider.transcribe({ pcm: Buffer.alloc(2) })).transcript, 'local Whisper stayed ready');
		assert.equal(localCloses, 0, 'the shared worker remains owned by the ready STT channel');
	} finally {
		await worker.close();
	}
	assert.equal(localCloses, 1);
});

test('STT-only warmup failure keeps ready local TTS while routing speech to Deepgram', async () => {
	let active;
	let localCloses = 0;
	let deepgramProviders = 0;
	let warmupCalls = 0;
	const local = {
		async warmup() {
			warmupCalls += 1;
			return { sttReady: false, ttsReady: true };
		},
		async synthesize() { return { provider: 'local' }; },
		async transcribe() { throw new Error('failed local STT must not receive traffic'); },
		async close() { localCloses += 1; },
	};
	const worker = await startVoiceWorker({ bridge: { secret: SECRET }, voice: { port: 8_766 } }, {
		DEEPGRAM_API_KEY: 'deepgram-key',
	}, {
		platform: 'linux',
		createLocalSpeechProvider: async () => local,
		loadProfileStore: async () => ({ store: { resolve() {} } }),
		createSttProvider: () => {
			deepgramProviders += 1;
			return { async transcribe() { return { transcript: 'deepgram', confidence: 1 }; } };
		},
		createVoiceServer: (options) => {
			active = options;
			return { async start() {}, async close() {} };
		},
	});
	try {
		await worker.warmup();
		assert.equal(warmupCalls, 1, 'a configured external STT fallback does not wait on a local-only retry');
		assert.equal(deepgramProviders, 1);
		assert.equal((await active.provider.synthesize({ text: 'hello' })).provider, 'local');
		assert.equal((await active.sttProvider.transcribe({ pcm: Buffer.alloc(2) })).transcript, 'deepgram');
		assert.equal(localCloses, 0, 'the ready local TTS channel retains the shared worker');
	} finally {
		await worker.close();
	}
	assert.equal(localCloses, 1);
});

test('independent runtime channel failovers release the shared local worker exactly once', async () => {
	let active;
	let localCloses = 0;
	let deepgramProviders = 0;
	let fishProviders = 0;
	let fishCloses = 0;
	let deepgramCloses = 0;
	const local = {
		async synthesize() { throw Object.assign(new Error('local TTS failed'), { code: 'LOCAL_TTS_ERROR' }); },
		async transcribe() {
			await new Promise((resolve) => setImmediate(resolve));
			throw Object.assign(new Error('local STT failed'), { code: 'LOCAL_STT_ERROR' });
		},
		async close() { localCloses += 1; },
	};
	const worker = await startVoiceWorker({ bridge: { secret: SECRET }, voice: { secret: VOICE_SECRET, port: 8_766 } }, {
		FISH_AUDIO_API_KEY: 'fish-key',
		DEEPGRAM_API_KEY: 'deepgram-key',
	}, {
		platform: 'linux',
		createLocalSpeechProvider: async () => local,
		loadProfileStore: async () => ({ store: { resolve() {} } }),
		createTtsProvider: () => {
			fishProviders += 1;
			return {
				async synthesize() { return { provider: 'fish' }; },
				async close() { fishCloses += 1; },
			};
		},
		createSttProvider: () => {
			deepgramProviders += 1;
			return {
				async transcribe() { return { transcript: 'deepgram', confidence: 1 }; },
				async close() { deepgramCloses += 1; },
			};
		},
		createVoiceServer: (options) => {
			active = options;
			return { async start() {}, async close() {} };
		},
	});
	try {
		assert.equal((await active.provider.synthesize({ text: 'hello' })).provider, 'fish');
		assert.equal(fishProviders, 1);
		assert.equal(localCloses, 0, 'local STT still owns the shared worker');

		const transcripts = await Promise.all([
			active.sttProvider.transcribe({ pcm: Buffer.alloc(2) }),
			active.sttProvider.transcribe({ pcm: Buffer.alloc(2) }),
		]);
		assert.deepEqual(transcripts.map(({ transcript }) => transcript), ['deepgram', 'deepgram']);
		assert.equal(deepgramProviders, 1, 'concurrent failures share one STT fallback');
		assert.equal(localCloses, 0, 'the local model remains available as a fallback for either remote channel');
	} finally {
		await Promise.all([worker.close(), worker.close()]);
	}
	assert.equal(localCloses, 1);
	assert.equal(fishCloses, 1);
	assert.equal(deepgramCloses, 1);
});

test('local model warmup failure preserves Deepgram-only STT on non-Windows hosts', async () => {
	let active;
	let localCloses = 0;
	let deepgramProviders = 0;
	const local = {
		async warmup() { throw Object.assign(new Error('local models failed'), { code: 'LOCAL_SPEECH_WARMUP_FAILED' }); },
		async synthesize() { throw new Error('failed local TTS must not receive traffic'); },
		async transcribe() { throw new Error('failed local STT must not receive traffic'); },
		async close() { localCloses += 1; },
	};
	const worker = await startVoiceWorker({ bridge: { secret: SECRET }, voice: { secret: VOICE_SECRET, port: 8_766 } }, {
		DEEPGRAM_API_KEY: 'deepgram-key',
	}, {
		platform: 'linux',
		createLocalSpeechProvider: async () => local,
		loadProfileStore: async () => ({ store: { resolve() {} } }),
		createTtsProvider: () => { throw new Error('Fish TTS must not be created without a credential'); },
		createWindowsTtsProvider: () => { throw new Error('Windows TTS must not be created on Linux'); },
		createSttProvider: ({ apiKey }) => {
			assert.equal(apiKey, 'deepgram-key');
			assert.equal(localCloses, 0, 'Deepgram starts as the primary STT provider');
			deepgramProviders += 1;
			return { async transcribe() { return { transcript: 'remote', confidence: 1 }; } };
		},
		createVoiceServer: (options) => {
			active = options;
			return { async start() {}, async close() {} };
		},
	});
	try {
		await worker.warmup();
		assert.equal(localCloses, 0, 'failed local TTS cannot disable primary Deepgram hearing');
		assert.equal(deepgramProviders, 1);
		assert.equal((await active.sttProvider.transcribe({ pcm: Buffer.alloc(2) })).transcript, 'remote');
		await assert.rejects(
			active.provider.synthesize({ text: 'hi' }),
			/failed local TTS/,
		);
	} finally {
		await worker.close();
	}
	assert.equal(localCloses, 1);
});

test('malformed TTS profile state cannot block Deepgram after local warmup fails', async () => {
	const root = await mkdtemp(path.join(tmpdir(), 'arena-unproven-local-profile-'));
	const profilePath = path.join(root, 'voice-profile-assignments.json');
	await writeFile(profilePath, '{ malformed assignments', 'utf8');
	let active;
	const local = {
		async warmup() { return { sttReady: false, ttsReady: false }; },
		async synthesize() { throw new Error('failed local TTS must not receive traffic'); },
		async transcribe() { throw new Error('failed local STT must not receive traffic'); },
		async close() {},
	};
	try {
		const worker = await startVoiceWorker({ bridge: { secret: SECRET }, voice: { secret: VOICE_SECRET, port: 8_766 } }, {
			DEEPGRAM_API_KEY: 'deepgram-key',
		}, {
			platform: 'linux',
			profilePath,
			createLocalSpeechProvider: async () => local,
			createSttProvider: () => ({ async transcribe() { return { transcript: 'remote hearing', confidence: 1 }; } }),
			createVoiceServer: (options) => {
				active = options;
				return { async start() {}, async close() {} };
			},
		});
		try {
			await worker.warmup();
			assert.equal((await active.sttProvider.transcribe({ pcm: Buffer.alloc(2) })).transcript, 'remote hearing');
		} finally {
			await worker.close();
		}
	} finally {
		await rm(root, { recursive: true, force: true });
	}
});

test('local cleanup failure cannot roll back a successful external fallback switch', async () => {
	let active;
	const local = {
		async warmup() { throw Object.assign(new Error('local models failed'), { code: 'LOCAL_SPEECH_WARMUP_FAILED' }); },
		async synthesize() { throw new Error('closed local TTS must not receive traffic'); },
		async transcribe() { throw new Error('closed local STT must not receive traffic'); },
		async close() { throw new Error('local process already exited'); },
	};
	const worker = await startVoiceWorker({ bridge: { secret: SECRET }, voice: { secret: VOICE_SECRET, port: 8_766 } }, {
		FISH_AUDIO_API_KEY: 'fish-key',
		DEEPGRAM_API_KEY: 'deepgram-key',
	}, {
		platform: 'linux',
		createLocalSpeechProvider: async () => local,
		loadProfileStore: async () => ({ store: { resolve() {} } }),
		createTtsProvider: () => ({ async synthesize() { return { provider: 'fish' }; } }),
		createSttProvider: () => ({ async transcribe() { return { transcript: 'remote', confidence: 1 }; } }),
		createVoiceServer: (options) => {
			active = options;
			return { async start() {}, async close() {} };
		},
	});
	try {
		await worker.warmup();
		assert.equal((await active.provider.synthesize({ text: 'hi' })).provider, 'fish');
		assert.equal((await active.sttProvider.transcribe({ pcm: Buffer.alloc(2) })).transcript, 'remote');
	} finally {
		await worker.close();
	}
});

test('voice bootstrap propagates startup cancellation into provider discovery', async () => {
	const controller = new AbortController();
	let observedSignal = null;
	const starting = startVoiceWorker({ bridge: { secret: SECRET }, voice: { secret: VOICE_SECRET, port: 8_766 } }, {}, {
		platform: 'win32',
		signal: controller.signal,
		createLocalSpeechProvider: async ({ signal }) => {
			observedSignal = signal;
			if (signal === undefined) throw new Error('startup signal was not propagated');
			return new Promise((resolve, reject) => signal.addEventListener('abort', () => {
				const error = new Error('provider discovery aborted');
				error.name = 'AbortError';
				reject(error);
			}, { once: true }));
		},
	});
	controller.abort();
	await assert.rejects(starting, (error) => error.name === 'AbortError');
	assert.equal(observedSignal, controller.signal);
});

test('voice bootstrap propagates startup cancellation into HTTP binding', async () => {
	const controller = new AbortController();
	let observedSignal = null;
	let markStartEntered;
	const startEntered = new Promise((resolve) => { markStartEntered = resolve; });
	const starting = startVoiceWorker({ bridge: { secret: SECRET }, voice: { secret: VOICE_SECRET, port: 8_766 } }, {
		FISH_API_KEY: 'test-key',
	}, {
		signal: controller.signal,
		platform: 'linux',
		loadProfileStore: async () => ({ store: { resolve() {} } }),
		createTtsProvider: () => ({ async synthesize() { return {}; } }),
		createVoiceServer: () => ({
			start: ({ signal }) => {
				observedSignal = signal;
				markStartEntered();
				return new Promise((resolve, reject) => signal.addEventListener('abort', () => {
					const error = new Error('bind aborted');
					error.name = 'AbortError';
					reject(error);
				}, { once: true }));
			},
			async close() {},
		}),
	});
	await startEntered;
	controller.abort();
	await assert.rejects(starting, (error) => error.name === 'AbortError');
	assert.equal(observedSignal, controller.signal);
});

test('voice bootstrap keeps supervisor ownership over the production profile read seam', async () => {
	const controller = new AbortController();
	const unrelated = new AbortController();
	let observedSignal = null;
	const worker = await startVoiceWorker({ bridge: { secret: SECRET }, voice: { secret: VOICE_SECRET, port: 8_766 } }, {
		FISH_API_KEY: 'test-key',
	}, {
		signal: controller.signal,
		platform: 'linux',
		localSpeechAccess: async () => { throw Object.assign(new Error('not installed'), { code: 'ENOENT' }); },
		voiceProfileIo: {
			signal: unrelated.signal,
			readFile: async (filePath, options) => {
				observedSignal = options.signal;
				throw Object.assign(new Error('new store'), { code: 'ENOENT' });
			},
		},
		createTtsProvider: () => ({ async synthesize() { return {}; } }),
		createVoiceServer: () => ({ async start() {}, async close() {} }),
	});
	try {
		assert.equal(observedSignal, controller.signal);
	} finally {
		await worker.close();
	}
});
