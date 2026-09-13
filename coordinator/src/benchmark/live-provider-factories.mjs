import path from 'node:path';

import { AcpProviderService } from '../acp-service.mjs';
import { AgentWorkspaceManager } from '../agent-workspace.mjs';
import { CodexService } from '../codex-service.mjs';
import { createProviderChildEnvironment } from '../provider-environment.mjs';
import { sanitizeDiagnosticErrorCode, sanitizeDiagnosticErrorMessage, sanitizeDiagnosticText } from '../diagnostic-sanitizer.mjs';

const PROVIDERS = new Set(['codex', 'kimi']);
const DEFAULT_BRIDGE_SECRET_ENVIRONMENT_VARIABLE = 'ARENA_AGENT_BRIDGE_SECRET';
const DEFAULT_PREFLIGHT_TIMEOUT_MS = 15_000;
const PREFLIGHT_AGENT_ID = '__latency_preflight__';

/**
 * Build an exact-provider factory for a live latency trial.
 *
 * The returned function performs a bounded local preflight, then returns a
 * provider adapter suitable for the latency runner. Provider services,
 * transports, catalog discovery, environment, and workspaces are injectable
 * so tests never need to launch a real CLI.
 */
export function createLiveProviderFactory(providerValue, options = {}) {
	const provider = requireProvider(providerValue);
	if (!isRecord(options)) throw new TypeError('live provider factory options must be an object');
	const environment = options.environment ?? options.env ?? process.env;
	if (!isRecord(environment)) throw new TypeError('live provider environment must be an object');
	const bridgeSecretEnvironmentVariable = options.bridgeSecretEnvironmentVariable
		?? options.config?.bridge?.secretEnvironmentVariable
		?? DEFAULT_BRIDGE_SECRET_ENVIRONMENT_VARIABLE;
	const providerEnvironment = createProviderChildEnvironment(provider, environment, bridgeSecretEnvironmentVariable);
	const config = providerConfig(provider, options.config, options);
	const workspaceManager = options.workspaceManager
		?? new AgentWorkspaceManager(options.workspaceRoot ?? options.config?.workspaceRoot ?? path.join(process.cwd(), 'runtime', 'agent-workspaces'), options.workspaceDependencies);
	const secrets = collectSecrets(environment, options.credentials, options.config);
	const createService = options.serviceFactory ?? ((context) => createDefaultService(provider, context, options));

	return async function liveProviderFactory(profileValue, context = {}) {
		if (!isRecord(context)) throw new TypeError('live provider factory context must be an object');
		const profile = exactProfile(profileValue, provider);
		const timeoutMs = positiveInt(
			context.preflightTimeoutMs ?? options.preflightTimeoutMs ?? config.planningTimeoutMs ?? DEFAULT_PREFLIGHT_TIMEOUT_MS,
			'preflightTimeoutMs',
		);
		const agentId = profile.agentId ?? `${PREFLIGHT_AGENT_ID}-${provider}`;
		let service;
		try {
			service = options.service ?? await createService({
				provider,
				config,
				environment: providerEnvironment,
				workspaceManager,
				context,
			});
			validateService(service, provider);
		} catch (error) {
			return unavailableResult(provider, agentId, 'startup', error, false, secrets);
		}
		let phase = 'startup';
		let agent = null;
		let catalogFallback = service.catalog?.stale === true;
		let succeeded = false;
		try {
			await runBounded((signal) => service.start(), context.signal, timeoutMs);

			phase = 'startup';
			if (typeof service.catalog?.refresh === 'function') {
				await runBounded((signal) => service.catalog.refresh(), context.signal, timeoutMs);
				catalogFallback = service.catalog.stale === true;
			}

			phase = 'session';
			agent = await runBounded((signal) => service.createAgent({ ...profile, agentId, provider }, { signal, controlProtocol: 'arena_script' }), context.signal, timeoutMs);
			if (agent === null || typeof agent !== 'object') throw coded('INVALID_SESSION', 'provider returned no live agent session');

			phase = typeof options.probe === 'function' ? 'first_turn' : 'session';
			if (typeof options.probe === 'function') {
				await runBounded((signal) => options.probe({ provider, profile: { ...profile, agentId, provider }, agent, signal, timeoutMs, context }), context.signal, timeoutMs);
			}

			await removeAgent(service, agentId, agent);
			agent = null;
			succeeded = true;
			return createAvailableProvider({
				provider,
				profile,
				service,
				catalogFallback,
				preflight: { provider, agentId, phase, realAvailability: true, catalogFallback },
			});
		} catch (error) {
			return unavailableResult(provider, agentId, phase, error, catalogFallback, secrets);
		} finally {
			if (agent !== null) await settle(() => removeAgent(service, agentId, agent));
			if (!succeeded) await settle(() => service.stop());
		}
	};
}

function createAvailableProvider({ provider, profile, service, catalogFallback, preflight }) {
	return {
		available: true,
		provider,
		model: profile.model,
		reasoningEffort: profile.reasoningEffort,
		serviceTier: profile.serviceTier,
		providerProfile: Object.freeze({
			provider,
			model: profile.model,
			reasoningEffort: profile.reasoningEffort,
			serviceTier: profile.serviceTier,
		}),
		catalogFallback,
		preflight,
		catalog: service.catalog,
		async start() { return service.start(); },
		async stop() { return service.stop(); },
		async createAgent(profile, options) {
			const exact = exactProfile(profile, provider);
			const serviceOptions = provider === 'codex'
				? { ...options, controlProtocol: options?.controlProtocol ?? 'arena_script' }
				: options;
			return service.createAgent({ ...exact, provider }, serviceOptions);
		},
		getAgent(agentId) { return service.getAgent?.(agentId) ?? null; },
		async removeAgent(agentId) { return service.removeAgent?.(agentId) ?? false; },
	};
}

function createDefaultService(provider, { config, environment, workspaceManager }, options) {
	const dependencies = { workspaceManager };
	if (provider === 'codex') {
		if (options.codexTransport !== undefined) dependencies.transport = options.codexTransport;
		if (options.now !== undefined) dependencies.now = options.now;
		const launchProfile = config.launchProfile === undefined
			? undefined
			: { ...config.launchProfile, cwd: config.cwd, environment, bridgeSecretEnvironmentVariable: config.bridgeSecretEnvironmentVariable };
		return new (options.CodexService ?? CodexService)({
			...config,
			...(launchProfile === undefined ? {} : { launchProfile }),
			environment,
			bridgeSecretEnvironmentVariable: config.bridgeSecretEnvironmentVariable,
		}, dependencies);
	}
	if (options.kimiTransportFactory !== undefined) dependencies.transportFactory = options.kimiTransportFactory;
	if (options.kimiDiscoverCatalog !== undefined) dependencies.discoverCatalog = options.kimiDiscoverCatalog;
	if (options.execFile !== undefined) dependencies.execFile = options.execFile;
	return new (options.AcpProviderService ?? AcpProviderService)({ ...config, provider, environment, bridgeSecretEnvironmentVariable: config.bridgeSecretEnvironmentVariable }, dependencies);
}

function providerConfig(provider, rootValue, options) {
	const root = isRecord(rootValue) ? rootValue : {};
	const selected = isRecord(root[provider]) ? root[provider] : root;
	const cwd = options.cwd ?? selected.cwd ?? root.codex?.cwd ?? process.cwd();
	if (typeof cwd !== 'string' || cwd.trim().length === 0) throw new TypeError('live provider cwd must be nonblank');
	return {
		...selected,
		cwd,
		bridgeSecretEnvironmentVariable: options.bridgeSecretEnvironmentVariable
			?? root.bridge?.secretEnvironmentVariable
			?? DEFAULT_BRIDGE_SECRET_ENVIRONMENT_VARIABLE,
	};
}

function exactProfile(value, provider) {
	if (!isRecord(value)) throw new TypeError('live provider profile must be an object');
	if (value.provider !== provider) throw coded('PROVIDER_MISMATCH', `Expected ${provider} profile, received ${String(value.provider)}`);
	for (const field of ['model', 'reasoningEffort']) {
		if (typeof value[field] !== 'string' || value[field].trim().length === 0) throw new TypeError(`live provider profile ${field} must be nonblank`);
	}
	return { ...value };
}

function requireProvider(value) {
	if (typeof value !== 'string' || !PROVIDERS.has(value)) throw new TypeError(`live provider must be one of ${[...PROVIDERS].join(', ')}`);
	return value;
}

function validateService(service, provider) {
	if (!isRecord(service)) throw new TypeError(`${provider} live provider service must be an object`);
	for (const method of ['start', 'stop', 'createAgent']) if (typeof service[method] !== 'function') throw new TypeError(`${provider} service must expose ${method}()`);
	if (service.provider !== undefined && service.provider !== provider) throw coded('PROVIDER_MISMATCH', `Service provider '${String(service.provider)}' does not match ${provider}`);
}

async function removeAgent(service, agentId, agent) {
	if (typeof service.removeAgent === 'function') {
		await service.removeAgent(agentId);
		return;
	}
	await agent?.dispose?.();
}

function runBounded(task, externalSignal, timeoutMs) {
	if (typeof task !== 'function') return Promise.reject(new TypeError('bounded task must be a function'));
	if (externalSignal !== undefined && externalSignal !== null && typeof externalSignal.addEventListener !== 'function') return Promise.reject(new TypeError('preflight signal must be an AbortSignal'));
	const localController = new AbortController();
	let timer = null;
	let rejectAbort;
	const abortPromise = new Promise((_, reject) => { rejectAbort = reject; });
	const abort = () => {
		const error = coded('ABORT_ERR', 'live provider preflight aborted');
		localController.abort(error);
		rejectAbort(error);
	};
	if (externalSignal?.aborted) abort();
	else externalSignal?.addEventListener('abort', abort, { once: true });
	const timeoutPromise = new Promise((_, reject) => {
		timer = setTimeout(() => {
			const error = coded('PREFLIGHT_TIMEOUT', `live provider preflight exceeded ${timeoutMs} ms`);
			localController.abort(error);
			reject(error);
		}, timeoutMs);
	});
	const taskPromise = Promise.resolve().then(() => {
		if (localController.signal.aborted) throw localController.signal.reason;
		return task(localController.signal);
	});
	return Promise.race([taskPromise, abortPromise, timeoutPromise]).finally(() => {
		if (timer !== null) clearTimeout(timer);
		externalSignal?.removeEventListener('abort', abort);
	});
}

function unavailableResult(provider, agentId, phase, error, catalogFallback, secrets) {
	return {
		available: false,
		provider,
		code: 'PROVIDER_UNAVAILABLE',
		reason: safeReason(error, phase, secrets),
		catalogFallback,
		preflight: {
			provider,
			agentId,
			phase,
			realAvailability: false,
			catalogFallback,
			errorCode: safeErrorCode(error),
		},
	};
}

function collectSecrets(environment, credentials, config) {
	const values = [];
	for (const [key, value] of Object.entries(environment)) if (/(?:key|token|secret|password|authorization|credential)/i.test(key) && typeof value === 'string' && value.length > 0) values.push(value);
	if (isRecord(credentials)) for (const value of Object.values(credentials)) if (typeof value === 'string' && value.length > 0) values.push(value);
	collectConfigSecrets(config, values);
	return [...new Set(values)].sort((left, right) => right.length - left.length);
}

function collectConfigSecrets(value, target, key = '') {
	if (isRecord(value)) {
		for (const [childKey, child] of Object.entries(value)) collectConfigSecrets(child, target, childKey);
		return;
	}
	if (/(?:key|token|secret|password|authorization|credential)/i.test(key) && typeof value === 'string' && value.length > 0) target.push(value);
}

function safeReason(error, phase, secrets) {
	let message = sanitizeDiagnosticErrorMessage(error, { fallback: `${phase} preflight failed`, maxBytes: 2_048 });
	for (const secret of secrets) message = message.split(secret).join('[REDACTED]');
	return sanitizeDiagnosticText(message, { maxBytes: 512 }).replace(/\s+/g, ' ').trim() || `${phase} preflight failed`;
}

function safeErrorCode(error) {
	return sanitizeDiagnosticErrorCode(error, { fallback: 'PROVIDER_UNAVAILABLE', maxBytes: 64 });
}

function coded(code, message) {
	return Object.assign(new Error(message), { code });
}

function positiveInt(value, field) {
	if (!Number.isSafeInteger(value) || value <= 0) throw new TypeError(`${field} must be a positive safe integer`);
	return value;
}

function isRecord(value) {
	return value !== null && typeof value === 'object' && !Array.isArray(value);
}

async function settle(task) {
	try { await task(); } catch { /* cleanup must not mask the provider failure */ }
}
