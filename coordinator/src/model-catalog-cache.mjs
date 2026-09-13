import { DEFAULT_SERVICE_TIER } from './constants.mjs';

const DEFAULT_CATALOG_TTL_MS = 60_000;
const DEFAULT_REFRESH_TIMEOUT_MS = 15_000;
const PREFERRED_CODEX_MODELS = [
	'gpt-5.6-luna',
	'gpt-5.6-terra',
	'gpt-5.6-sol',
	'gpt-5.6-sol-wm',
	'gpt-5.5',
	'gpt-5.4',
	'gpt-5.4-mini',
	'gpt-5.3-codex-spark',
];
const PREFERRED_REASONING_EFFORTS = ['low', 'medium', 'high', 'xhigh', 'max', 'ultra'];
const PREFERRED_SERVICE_TIERS = ['priority', 'fast'];

export class ModelCatalogError extends Error {
	constructor(code, message, options) {
		super(message, options);
		this.name = 'ModelCatalogError';
		this.code = code;
	}
}

export class ModelCatalogCache {
	#loader;
	#ttlMs;
	#now;
	#models = [];
	#refreshedAtEpochMs = 0;
	#refreshPromise = null;
	#refreshGeneration = 0;
	#refreshTimeoutMs;
	#scheduleTimeout;
	#cancelTimeout;
	#source = null;
	#failureCount = 0;
	#failureCode = null;
	#builtinModels = [];

	constructor(loader, { ttlMs = DEFAULT_CATALOG_TTL_MS, now = Date.now, builtinModels = [], refreshTimeoutMs = DEFAULT_REFRESH_TIMEOUT_MS, scheduleTimeout = setTimeout, cancelTimeout = clearTimeout } = {}) {
		if (typeof loader !== 'function') throw new TypeError('model catalog loader must be a function');
		if (!Number.isSafeInteger(ttlMs) || ttlMs <= 0) throw new TypeError('catalog ttlMs must be a positive safe integer');
		if (typeof now !== 'function') throw new TypeError('catalog now dependency must be a function');
		if (!Number.isSafeInteger(refreshTimeoutMs) || refreshTimeoutMs <= 0) throw new TypeError('catalog refreshTimeoutMs must be a positive safe integer');
		if (typeof scheduleTimeout !== 'function' || typeof cancelTimeout !== 'function') throw new TypeError('catalog timeout dependencies must be functions');
		this.#loader = loader;
		this.#ttlMs = ttlMs;
		this.#now = now;
		this.#refreshTimeoutMs = refreshTimeoutMs;
		this.#scheduleTimeout = scheduleTimeout;
		this.#cancelTimeout = cancelTimeout;
		if (!Array.isArray(builtinModels)) throw new TypeError('catalog builtinModels must be an array');
		if (builtinModels.length > 0) {
			this.#builtinModels = normalizeCatalog(builtinModels);
			this.#models = this.#builtinModels;
			this.#source = 'builtin';
		}
	}

	get stale() {
		return this.#source !== 'live' || this.#models.length === 0 || this.#now() - this.#refreshedAtEpochMs >= this.#ttlMs;
	}

	async refresh({ force = false } = {}) {
		if (!force && !this.stale) return this.snapshot();
		if (this.#refreshPromise !== null) return this.#refreshPromise;
		const generation = ++this.#refreshGeneration;
		const controller = new AbortController();
		const loading = Promise.resolve().then(() => this.#loader({ signal: controller.signal, generation }));
		const refresh = withRefreshDeadline(loading, {
			controller,
			timeoutMs: this.#refreshTimeoutMs,
			scheduleTimeout: this.#scheduleTimeout,
			cancelTimeout: this.#cancelTimeout,
		});
		this.#refreshPromise = refresh
			.then((models) => {
				if (generation !== this.#refreshGeneration) throw new ModelCatalogError('STALE_CATALOG_REFRESH', 'Model catalog refresh was superseded');
				const normalized = normalizeCatalog(models);
				if (normalized.length === 0) throw new ModelCatalogError('INVALID_CATALOG', 'Model catalog must contain at least one visible model');
				this.#models = mergeRequiredModels(normalized, this.#builtinModels);
				this.#refreshedAtEpochMs = this.#now();
				this.#source = 'live';
				this.#failureCount = 0;
				this.#failureCode = null;
				return this.snapshot();
			})
			.catch((error) => {
				this.#failureCount = Math.min(1_000_000, this.#failureCount + 1);
				this.#failureCode = boundedFailureCode(error);
				if (this.#models.length === 0) throw error;
				if (this.#source !== 'builtin') this.#source = 'last_valid';
				return this.snapshot();
			})
			.finally(() => { if (generation === this.#refreshGeneration) this.#refreshPromise = null; });
		return this.#refreshPromise;
	}

	snapshot() {
		return {
			refreshedAtEpochMs: this.#refreshedAtEpochMs,
			models: structuredClone(this.#models),
			source: this.#source,
			recovery: {
				state: this.#source === 'live' ? 'live' : 'degraded',
				failureCode: this.#failureCode,
				consecutiveFailureCount: this.#failureCount,
			},
		};
	}

	find(modelId) {
		const model = this.#models.find((entry) => entry.id === modelId || entry.model === modelId);
		return model === undefined ? null : structuredClone(model);
	}

	assertSupported(modelId, reasoningEffort, serviceTier = DEFAULT_SERVICE_TIER) {
		const model = this.find(modelId);
		if (model === null) throw new ModelCatalogError('MODEL_UNAVAILABLE', `Model '${modelId}' is not present in the current Codex catalog`);
		if (!model.reasoningEfforts.includes(reasoningEffort)) throw new ModelCatalogError('REASONING_EFFORT_UNAVAILABLE', `Model '${modelId}' does not support reasoning effort '${reasoningEffort}'`);
		if (model.serviceTiers.length > 0 && !model.serviceTiers.includes(serviceTier)) throw new ModelCatalogError('SERVICE_TIER_UNAVAILABLE', `Model '${modelId}' does not support service tier '${serviceTier}'`);
		return model;
	}

	reconcileProfiles(profiles) {
		if (!Array.isArray(profiles)) throw new TypeError('model profiles must be an array');
		const valid = [];
		const invalid = [];
		for (const profile of profiles) {
			try {
				this.assertSupported(profile.model, profile.reasoningEffort, profile.serviceTier ?? DEFAULT_SERVICE_TIER);
				valid.push(structuredClone(profile));
			} catch (error) {
				invalid.push({ agentId: profile.agentId ?? null, code: error.code ?? 'INVALID_PROFILE', message: error.message });
			}
		}
		return { valid, invalid };
	}
}

function mergeRequiredModels(models, requiredModels) {
	const merged = models.map((model) => ({ ...model }));
	for (const required of requiredModels) {
		const index = merged.findIndex((model) => model.id === required.id || model.model === required.model);
		if (index < 0) {
			merged.push({ ...required });
			continue;
		}
		const discovered = merged[index];
		merged[index] = {
			...discovered,
			reasoningEfforts: orderValues(
				[...new Set([...discovered.reasoningEfforts, ...required.reasoningEfforts])],
				PREFERRED_REASONING_EFFORTS,
			),
			serviceTiers: discovered.serviceTiers.length === 0
				? []
				: orderValues([...new Set([...discovered.serviceTiers, ...required.serviceTiers])], PREFERRED_SERVICE_TIERS),
		};
	}
	return orderModels(merged);
}

export function normalizeCatalog(value) {
	if (!Array.isArray(value)) throw new ModelCatalogError('INVALID_CATALOG', 'Model catalog must be an array');
	const seen = new Set();
	const models = value.filter((entry) => entry?.hidden !== true).map((entry) => {
		if (entry === null || typeof entry !== 'object' || Array.isArray(entry)) throw new ModelCatalogError('INVALID_CATALOG', 'Each model catalog entry must be an object');
		const id = requireText(entry.id ?? entry.model ?? entry.slug, 'model id');
		if (seen.has(id)) throw new ModelCatalogError('INVALID_CATALOG', `Duplicate model '${id}'`);
		seen.add(id);
		return {
			id,
			model: requireText(entry.model ?? entry.slug ?? id, 'model'),
			displayName: requireDisplayName(entry.displayName ?? entry.display_name, id),
			reasoningEfforts: orderValues(normalizeStringList(
				entry.supportedReasoningEfforts ?? entry.supportedReasoningLevels ?? entry.supported_reasoning_levels,
				'reasoningEffort',
			), PREFERRED_REASONING_EFFORTS),
			serviceTiers: normalizeServiceTiers(entry),
		};
	});
	return orderModels(models);
}

function normalizeServiceTiers(entry) {
	return orderValues([...new Set([
		...normalizeStringList(entry.serviceTiers ?? entry.service_tiers, 'id'),
		...normalizeStringList(entry.additionalSpeedTiers ?? entry.additional_speed_tiers, 'id'),
	])], PREFERRED_SERVICE_TIERS);
}

function orderModels(models) {
	const preferredRank = new Map(PREFERRED_CODEX_MODELS.map((model, index) => [model, index]));
	return models.toSorted((left, right) => {
		const leftRank = preferredRank.get(left.id) ?? Number.MAX_SAFE_INTEGER;
		const rightRank = preferredRank.get(right.id) ?? Number.MAX_SAFE_INTEGER;
		return leftRank - rightRank || left.id.localeCompare(right.id);
	});
}

function orderValues(values, preferred) {
	const preferredRank = new Map(preferred.map((value, index) => [value, index]));
	return values.toSorted((left, right) => {
		const leftRank = preferredRank.get(left) ?? Number.MAX_SAFE_INTEGER;
		const rightRank = preferredRank.get(right) ?? Number.MAX_SAFE_INTEGER;
		return leftRank - rightRank || left.localeCompare(right);
	});
}

function normalizeStringList(value, objectKey) {
	if (value === undefined || value === null) return [];
	if (!Array.isArray(value)) throw new ModelCatalogError('INVALID_CATALOG', 'Model capability lists must be arrays');
	return [...new Set(value.map((entry) => requireText(
		typeof entry === 'string' ? entry : entry?.[objectKey] ?? (objectKey === 'reasoningEffort' ? entry?.effort : undefined),
		objectKey,
	)))];
}

function requireDisplayName(value, fallback) {
	return typeof value === 'string' && value.trim().length > 0 ? value.trim() : fallback;
}

function requireText(value, field) {
	if (typeof value !== 'string' || value.trim().length === 0) throw new ModelCatalogError('INVALID_CATALOG', `${field} must be nonblank`);
	return value;
}

function boundedFailureCode(error) {
	const value = typeof error?.code === 'string' && error.code.trim().length > 0 ? error.code : 'CATALOG_REFRESH_FAILED';
	return value.slice(0, 128);
}

function withRefreshDeadline(promise, { controller, timeoutMs, scheduleTimeout, cancelTimeout }) {
	let handle;
	const timeout = new Promise((_, reject) => {
		handle = scheduleTimeout(() => {
			controller.abort();
			reject(new ModelCatalogError('CATALOG_REFRESH_TIMEOUT', `Model catalog refresh timed out after ${timeoutMs} ms`));
		}, timeoutMs);
		handle?.unref?.();
	});
	return Promise.race([promise, timeout]).finally(() => cancelTimeout(handle));
}
