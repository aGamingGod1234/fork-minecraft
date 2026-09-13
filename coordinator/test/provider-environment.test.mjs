import assert from 'node:assert/strict';
import test from 'node:test';

import { createProviderChildEnvironment } from '../src/provider-environment.mjs';

test('provider environments retain only OS bootstrap and the selected provider credentials', () => {
	const parent = {
		Path: 'C:\\Windows\\System32',
		APPDATA: 'C:\\Users\\lucas\\AppData\\Roaming',
		OPENAI_API_KEY: 'openai-key',
		GEMINI_API_KEY: 'gemini-key',
		KIMI_API_KEY: 'kimi-key',
		CURSOR_API_KEY: 'cursor-key',
		FISH_AUDIO_API_KEY: 'voice-key',
		DEEPGRAM_API_KEY: 'speech-key',
		AWS_SECRET_ACCESS_KEY: 'cloud-key',
		HTTPS_PROXY: 'http://proxy-user:proxy-password@proxy.internal:8443',
		HTTP_PROXY: 'http://proxy.internal:8080',
		NO_PROXY: '127.0.0.1,localhost',
		ARENA_AGENT_BRIDGE_SECRET: 'default-secret',
		ARENA_AGENT_BRIDGE_SECRET_FILE: 'C:\\runtime\\default.secret',
		CUSTOM_BRIDGE_SECRET: 'custom-secret',
	};

	for (const [provider, credential] of [
		['codex', 'OPENAI_API_KEY'],
		['gemini', 'GEMINI_API_KEY'],
		['kimi', 'KIMI_API_KEY'],
		['cursor', 'CURSOR_API_KEY'],
	]) {
		const environment = createProviderChildEnvironment(provider, parent, 'CUSTOM_BRIDGE_SECRET');
		assert.equal(environment.PATH, 'C:\\Windows\\System32');
		assert.equal(environment.APPDATA, 'C:\\Users\\lucas\\AppData\\Roaming');
		assert.equal(environment[credential], parent[credential]);
		assert.equal(environment.FISH_AUDIO_API_KEY, undefined);
		assert.equal(environment.DEEPGRAM_API_KEY, undefined);
		assert.equal(environment.AWS_SECRET_ACCESS_KEY, undefined);
		assert.equal(environment.HTTPS_PROXY, undefined);
		assert.equal(environment.HTTP_PROXY, undefined);
		assert.equal(environment.NO_PROXY, undefined);
		assert.equal(environment.ARENA_AGENT_BRIDGE_SECRET, undefined);
		assert.equal(environment.ARENA_AGENT_BRIDGE_SECRET_FILE, undefined);
		assert.equal(environment.CUSTOM_BRIDGE_SECRET, undefined);
		for (const otherCredential of ['OPENAI_API_KEY', 'GEMINI_API_KEY', 'KIMI_API_KEY', 'CURSOR_API_KEY']) {
			if (otherCredential !== credential) assert.equal(environment[otherCredential], undefined);
		}
	}
});

test('proxy forwarding requires opt-in and omits URLs containing credentials', () => {
	const environment = createProviderChildEnvironment('codex', {
		HTTPS_PROXY: 'http://proxy-user:proxy-password@proxy.internal:8443',
		HTTP_PROXY: 'http://proxy.internal:8080',
		ALL_PROXY: 'not-a-url',
		NO_PROXY: '127.0.0.1,localhost',
	}, null, { forwardProxyEnvironment: true });

	assert.equal(environment.HTTP_PROXY, 'http://proxy.internal:8080');
	assert.equal(environment.NO_PROXY, '127.0.0.1,localhost');
	assert.equal(environment.HTTPS_PROXY, undefined);
	assert.equal(environment.ALL_PROXY, undefined);
});

test('provider environment rejects unknown providers and non-object sources', () => {
	assert.throws(() => createProviderChildEnvironment('unknown', {}), /provider must be one of/);
	assert.throws(() => createProviderChildEnvironment('codex', null), /must be an object/);
	assert.throws(() => createProviderChildEnvironment('codex', {}, null, { forwardProxyEnvironment: 'yes' }), /must be a boolean/);
});
