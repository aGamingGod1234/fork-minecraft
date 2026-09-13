import assert from 'node:assert/strict';
import test from 'node:test';

import { FishTtsProvider } from '../src/voice/fish-tts-provider.mjs';
import { DeepgramSttProvider } from '../src/voice/deepgram-stt-provider.mjs';
import { resampleS16leMono } from '../src/voice/pcm-audio.mjs';
import { TtsCache } from '../src/voice/tts-cache.mjs';
import { createVoiceHttpServer, createVoiceRequestHeaders } from '../src/voice/voice-http-server.mjs';

const VOICE_TEST_SECRET = 'voice-test-secret-value';

function fetch(input, init = {}) {
	const headers = new Headers(init.headers);
	const url = new URL(typeof input === 'string' ? input : input.url);
	if (init.method === 'POST' && ['/v1/tts', '/v1/stt'].includes(url.pathname)
			&& headers.has('X-Voice-Signature')) {
		const signed = createVoiceRequestHeaders({
			secret: VOICE_TEST_SECRET,
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
import { builtInVoiceProfiles, VoiceProfileStore } from '../src/voice/voice-profile-store.mjs';

const AGENT_ONE = '00000000-0000-4000-8000-000000000001';
const AGENT_TWO = '00000000-0000-4000-8000-000000000002';

test('resamples bounded signed 16-bit mono PCM from 44.1 kHz to 48 kHz', () => {
	const input = Buffer.alloc(44_100 * 2);
	for (let index = 0; index < 44_100; index++) input.writeInt16LE(index % 20_000, index * 2);
	const output = resampleS16leMono(input);
	assert.equal(output.length, 48_000 * 2);
	assert.notEqual(output.readInt16LE(100), 0);
	assert.throws(() => resampleS16leMono(Buffer.alloc(3)), /complete/);
});

test('assigns sixteen stable recognizable Fish profiles without collisions', () => {
	const profiles = builtInVoiceProfiles();
	assert.equal(profiles.length, 16);
	assert.equal(new Set(profiles.map((profile) => profile.voiceId)).size, 16);
	assert.ok(profiles.every((profile) => profile.identityAnchors.length >= 3 && profile.avoid.length >= 2));
	const store = new VoiceProfileStore();
	const first = store.resolve(AGENT_ONE);
	assert.equal(store.resolve(AGENT_ONE), first);
	assert.notEqual(store.resolve(AGENT_TWO).profileId, first.profileId);
});

test('Fish provider sends the free model and strict PCM request', async () => {
	let captured;
	const provider = new FishTtsProvider({
		apiKey: 'fish-test-token',
		fetchImpl: async (url, request) => {
			captured = { url, request, body: JSON.parse(request.body) };
			return new Response(Buffer.alloc(4), { status: 200, headers: { 'content-length': '4' } });
		},
	});
	const result = await provider.synthesize({ text: 'Hello', voiceId: 'voice-id', speed: 0.95 });
	assert.equal(captured.url, 'https://api.fish.audio/v1/tts');
	assert.equal(captured.request.headers.model, 's2.1-pro-free');
	assert.equal(captured.body.format, 'pcm');
	assert.equal(captured.body.sample_rate, 44_100);
	assert.equal(captured.body.reference_id, 'voice-id');
	assert.equal(result.pcm.length, 4);
});

test('loopback voice worker authenticates, caches, and emits 48 kHz PCM', async () => {
	let calls = 0;
	const provider = {
		async synthesize() {
			calls++;
			return { sampleRateHz: 44_100, channels: 1, sampleFormat: 's16le', pcm: Buffer.alloc(4_410 * 2, 1) };
		},
	};
	const worker = createVoiceHttpServer({
		provider,
		profileStore: new VoiceProfileStore(),
		secret: 'voice-test-secret-value',
		cache: new TtsCache({ maxBytes: 1024 * 1024 }),
		port: 0,
	});
	const address = await worker.start();
	const url = `http://127.0.0.1:${address.port}/v1/tts`;
	const body = JSON.stringify({
		agentId: AGENT_ONE,
		text: 'Testing spatial speech.',
		profileId: 'voice.auto.v1',
		radius: 48,
		conversationSequence: 1,
	});
	try {
		assert.equal((await fetch(url, { method: 'POST', body })).status, 401);
		assert.equal((await fetch(url, {
			method: 'POST', headers: { Authorization: 'Bearer voice-test-secret-value' }, body,
		})).status, 401);
		let lastHeaders;
		for (let index = 0; index < 2; index++) {
			lastHeaders = {
				...createVoiceRequestHeaders({ secret: 'voice-test-secret-value', path: '/v1/tts' }),
				'Content-Type': 'application/json',
			};
			const response = await fetch(url, {
				method: 'POST',
				headers: lastHeaders,
				body,
			});
			assert.equal(response.status, 200);
			assert.equal(response.headers.get('x-audio-sample-rate'), '48000');
			assert.equal((await response.arrayBuffer()).byteLength, 4_800 * 2);
		}
		assert.equal((await fetch(url, { method: 'POST', headers: lastHeaders, body })).status, 401);
		assert.equal(calls, 1);
	} finally {
		await worker.close();
	}
});

test('Fish provider identifies a rejected credential without exposing response details', async () => {
	const provider = new FishTtsProvider({
		apiKey: 'secret-test-token',
		fetchImpl: async () => new Response('credential details must stay private', { status: 401 }),
	});
	await assert.rejects(
		provider.synthesize({ text: 'Hello', voiceId: 'voice-id' }),
		(error) => error?.code === 'TTS_AUTHENTICATION_FAILED'
			&& error.message === 'Fish TTS failed with HTTP 401'
			&& !error.message.includes('credential details'),
	);
});

test('TTS responses expose the effective synthesizer namespace instead of profile metadata', async () => {
	let calls = 0;
	const diagnostics = [];
	const provider = {
		cacheNamespace: () => 'fish/s2.1-pro-free',
		async synthesize() {
			calls += 1;
			return { sampleRateHz: 48_000, channels: 1, sampleFormat: 's16le', pcm: Buffer.alloc(960, 1) };
		},
	};
	const worker = createVoiceHttpServer({
		provider,
		profileStore: new VoiceProfileStore(),
		secret: VOICE_TEST_SECRET,
		cache: new TtsCache({ maxBytes: 1024 * 1024 }),
		port: 0,
		onDiagnostic: (event) => diagnostics.push(event),
	});
	const address = await worker.start();
	const body = JSON.stringify({
		agentId: AGENT_ONE,
		text: 'Report the real synthesizer.',
		profileId: 'voice.auto.v1',
		radius: 48,
		conversationSequence: 1,
	});
	try {
		for (let index = 0; index < 2; index++) {
			const response = await fetch(`http://127.0.0.1:${address.port}/v1/tts`, {
				method: 'POST',
				headers: {
					...createVoiceRequestHeaders({ secret: VOICE_TEST_SECRET, path: '/v1/tts' }),
					'Content-Type': 'application/json',
				},
				body,
			});
			assert.equal(response.status, 200);
			assert.equal(response.headers.get('x-voice-synthesizer'), 'fish/s2.1-pro-free');
			await response.arrayBuffer();
		}
		assert.equal(calls, 1, 'the cached response preserves the completed synthesizer identity');
		assert.equal(
			worker.statusSnapshots().find(({ component }) => component === 'voice:tts').effectiveProvider,
			'fish/s2.1-pro-free',
		);
		assert.deepEqual(diagnostics, [{
			code: 'VOICE_TTS_EFFECTIVE_PROVIDER',
			effectiveProvider: 'fish/s2.1-pro-free',
		}]);
	} finally {
		await worker.close();
	}
});

test('Deepgram provider sends bounded linear PCM and returns only the final transcript', async () => {
	let captured;
	const provider = new DeepgramSttProvider({
		apiKey: 'deepgram-test-token',
		fetchImpl: async (url, request) => {
			captured = { url, request };
			return Response.json({ results: { channels: [{ alternatives: [{ transcript: '  Follow me.  ', confidence: 0.91 }] }] } });
		},
	});
	const result = await provider.transcribe({ pcm: Buffer.alloc(1_920) });
	assert.match(captured.url, /model=nova-3/);
	assert.equal(captured.request.headers.Authorization, 'Token deepgram-test-token');
	assert.equal(captured.request.headers['Content-Type'], 'audio/l16;rate=48000;channels=1');
	assert.deepEqual(result, { transcript: 'Follow me.', confidence: 0.91 });
});

test('loopback speech route preserves player identity, utterance order, and whisper state', async () => {
	const worker = createVoiceHttpServer({
		provider: { async synthesize() { throw new Error('not used'); } },
		sttProvider: { async transcribe({ pcm }) { return { transcript: `heard ${pcm.length}`, confidence: 0.8 }; } },
		profileStore: new VoiceProfileStore(),
		secret: 'voice-test-secret-value',
		port: 0,
	});
	const address = await worker.start();
	try {
		const response = await fetch(`http://127.0.0.1:${address.port}/v1/stt`, {
			method: 'POST',
			headers: {
				...createVoiceRequestHeaders({ secret: 'voice-test-secret-value', path: '/v1/stt' }),
				'Content-Type': 'audio/l16;rate=48000;channels=1',
				'X-Player-Id': AGENT_TWO,
				'X-Utterance-Sequence': '7',
				'X-Whispering': 'true',
			},
			body: Buffer.alloc(1_920),
		});
		assert.equal(response.status, 200);
		assert.deepEqual(await response.json(), {
			playerId: AGENT_TWO,
			utteranceSequence: 7,
			whispering: true,
			transcript: 'heard 1920',
			confidence: 0.8,
		});
	} finally {
		await worker.close();
	}
});
