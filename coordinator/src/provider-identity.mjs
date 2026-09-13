export const PROVIDER_IDS = Object.freeze(['codex', 'gemini', 'kimi', 'cursor']);

const PROVIDER_SET = new Set(PROVIDER_IDS);
const FAST_TIER_PROVIDERS = new Set(['codex', 'cursor']);

export function createExecutionSettings(profile, { transport, controlProtocol, effective = {}, evidence = {}, limitations = [], modelSelector = null }) {
	return {
		requested: { provider: profile.provider ?? 'codex', model: profile.model, reasoningEffort: profile.reasoningEffort, serviceTier: profile.serviceTier ?? 'priority' },
		effective: { provider: profile.provider ?? 'codex', model: null, reasoningEffort: null, serviceTier: null, thinkingMode: null, ...effective },
		transport,
		controlProtocol,
		modelSelector,
		evidence: { model: 'unreported', reasoningEffort: 'unreported', serviceTier: 'unreported', ...evidence },
		limitations: [...limitations],
	};
}

export function normalizeProviderId(value, field = 'provider', { ErrorType = TypeError, code = null } = {}) {
	if (typeof value !== 'string' || value.trim() === '') throw providerError(ErrorType, code, `${field} must be nonblank`);
	const provider = value.toLowerCase();
	if (!PROVIDER_SET.has(provider)) {
		throw providerError(ErrorType, code, `${field} must be one of ${PROVIDER_IDS.join(', ')}`);
	}
	return provider;
}

export function assertProviderServiceTier(providerValue, serviceTier, field = 'serviceTier', options = {}) {
	const provider = normalizeProviderId(providerValue, 'provider', options);
	if (!['priority', 'fast'].includes(serviceTier)) {
		throw providerError(options.ErrorType ?? TypeError, options.code ?? null, `${field} must be priority or fast`);
	}
	if (serviceTier === 'fast' && !FAST_TIER_PROVIDERS.has(provider)) {
		throw providerError(options.ErrorType ?? TypeError, options.code ?? null, `${field} fast is available only for codex and cursor`);
	}
	return serviceTier;
}

function providerError(ErrorType, code, message) {
	if (code !== null) return new ErrorType(code, message);
	return new ErrorType(message);
}
