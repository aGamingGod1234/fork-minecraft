import assert from 'node:assert/strict';
import test from 'node:test';

import {
	providerCacheNamespace,
	synthesisCacheNamespace,
	tagSynthesisCacheNamespace,
} from '../src/voice/tts-cache-identity.mjs';

test('nested cache identity tags preserve the completed provider namespace', () => {
	const fishOutput = tagSynthesisCacheNamespace({ pcm: Buffer.alloc(2) }, 'fish/test');
	const runtimeOutput = tagSynthesisCacheNamespace(fishOutput, 'windows/test');

	assert.equal(synthesisCacheNamespace(runtimeOutput, null, 'profile/fallback'), 'fish/test');
	assert.notEqual(runtimeOutput, fishOutput, 'wrappers still receive an immutable output copy');
	assert.equal(Object.isFrozen(runtimeOutput), true);
});

test('cache identity falls back through untagged providers without accepting invalid wrapper namespaces', () => {
	const output = tagSynthesisCacheNamespace({ pcm: Buffer.alloc(2) }, 'local/test');
	assert.equal(synthesisCacheNamespace(output, { cacheNamespace: () => 'fish/test' }, 'profile/fallback'), 'local/test');
	assert.equal(providerCacheNamespace({ cacheNamespace: 'windows/test' }, 'profile/fallback'), 'windows/test');
	assert.equal(providerCacheNamespace({}, 'profile/fallback'), 'profile/fallback');
	assert.throws(() => tagSynthesisCacheNamespace(output, ' '), /nonblank string/);
});
