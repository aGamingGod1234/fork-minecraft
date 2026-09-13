const CACHE_NAMESPACE = Symbol('arena.tts-cache-namespace');

export function providerCacheNamespace(provider, fallback) {
	const candidate = typeof provider?.cacheNamespace === 'function'
		? provider.cacheNamespace()
		: provider?.cacheNamespace;
	return validNamespace(candidate) ? candidate : requireNamespace(fallback);
}

export function tagSynthesisCacheNamespace(output, namespace) {
	const wrapperNamespace = requireNamespace(namespace);
	const completedNamespace = output?.[CACHE_NAMESPACE];
	const tagged = { ...output };
	Object.defineProperty(tagged, CACHE_NAMESPACE, {
		value: validNamespace(completedNamespace) ? completedNamespace : wrapperNamespace,
	});
	return Object.freeze(tagged);
}

export function synthesisCacheNamespace(output, provider, fallback) {
	const tagged = output?.[CACHE_NAMESPACE];
	return validNamespace(tagged) ? tagged : providerCacheNamespace(provider, fallback);
}

function requireNamespace(value) {
	if (!validNamespace(value)) throw new TypeError('TTS cache namespace must be a nonblank string');
	return value;
}

function validNamespace(value) {
	return typeof value === 'string' && value.trim() !== '' && value.length <= 128;
}
