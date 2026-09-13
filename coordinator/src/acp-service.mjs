import { AcpProtocolError, AcpStdioTransport, buildAcpLaunch } from './acp-transport.mjs';
import { parseDecision } from './decision-parser.mjs';
import { recordProviderTurn } from './provider-turn-recorder.mjs';
import { discoverKimiCatalog } from './provider-catalog-discovery.mjs';
import { buildProviderPlannerPrompt } from './prompts.mjs';
import { createProviderChildEnvironment } from './provider-environment.mjs';
import { createSessionMetadata, profileFingerprint } from './provider-session.mjs';
import { reportVisibleOutput } from './verbose-output.mjs';
import { createExecutionSettings } from './provider-identity.mjs';

const DEFAULT_PLANNING_TIMEOUT_MS = 45_000;
const DEFAULT_DISCOVERY_TIMEOUT_MS = 15_000;
const DEFAULT_MAX_DECISION_BYTES = 256 * 1_024;
const DEFAULT_SERVICE_TIER = 'priority';
const PROFILE_KEYS = Object.freeze(['agentId', 'provider', 'model', 'reasoningEffort', 'serviceTier']);
const CLIENT_INFO = Object.freeze({ name: 'arena-agents-coordinator', title: 'Minecraft AI Agents', version: '2.1.0' });
const CLIENT_CAPABILITIES = Object.freeze({ fs: { readTextFile: false, writeTextFile: false }, terminal: false });

export { AcpProtocolError, buildAcpLaunch };

export class AcpProviderService {
	#config;
	#transportFactory;
	#workspaceManager;
	#agents = new Map();
	#creating = new Map();
	#replacing = new Map();
	#sessionGenerations = new Map();
	#lifecycleGeneration = 0;

	constructor(config, dependencies = {}) {
		this.#config = validateServiceConfig(config);
		const catalogEnvironment = createProviderChildEnvironment(
			this.#config.provider,
			this.#config.environment ?? process.env,
			this.#config.bridgeSecretEnvironmentVariable,
		);
		this.#transportFactory = dependencies.transportFactory ?? ((profile) => new AcpStdioTransport({ ...this.#config, ...profile }));
		this.#workspaceManager = dependencies.workspaceManager ?? null;
		if (this.#workspaceManager !== null && typeof this.#workspaceManager.prepare !== 'function') {
			throw new TypeError('workspaceManager must expose prepare(provider, agentId)');
		}
		this.catalog = new AcpCatalog(this.#config, {
			discover: dependencies.discoverCatalog ?? discoverKimiCatalog,
			execFile: dependencies.execFile,
			environment: catalogEnvironment,
		});
	}

	async start() {}
	get agentIds() { return [...this.#agents.keys()]; }
	getAgent(agentId) { return this.#agents.get(agentId) ?? null; }

	async createAgent(profileValue, { recoverySummary = null } = {}) {
		const lifecycleGeneration = this.#lifecycleGeneration;
		const requested = profileIdentity(profileValue, this.#config);
		const replacing = this.#replacing.get(requested.agentId);
		if (replacing !== undefined) {
			if (!profilesMatch(replacing.profile, requested)) throw profileConflict();
			return replacing.promise;
		}
		const existing = this.#agents.get(requested.agentId);
		if (existing !== undefined) {
			if (!existing.matchesProfile(requested)) throw profileConflict();
			return existing;
		}
		const creating = this.#creating.get(requested.agentId);
		if (creating !== undefined) {
			if (!profilesMatch(creating.profile, requested)) throw profileConflict();
			return creating.promise;
		}
		await this.catalog.refresh();
		assertLifecycleActive(lifecycleGeneration, this.#lifecycleGeneration, this.#config.provider);
		const profile = validateProfile(profileValue, this.#config);
		const promise = this.#createAgentOnce(profile, recoverySummary, lifecycleGeneration);
		this.#creating.set(profile.agentId, { profile, promise });
		try { return await promise; } finally { this.#creating.delete(profile.agentId); }
	}

	async replaceAgent(profileValue, { recoverySummary = null, expectedSessionGeneration = null } = {}) {
		const profile = validateProfile(profileValue, this.#config);
		const replacing = this.#replacing.get(profile.agentId);
		if (replacing !== undefined) {
			if (!profilesMatch(replacing.profile, profile)) throw profileConflict();
			return replacing.promise;
		}
		const existing = this.#agents.get(profile.agentId);
		if (existing !== undefined) {
			if (!existing.matchesProfile(profile)) throw profileConflict();
			if (expectedSessionGeneration !== null && existing.sessionGeneration !== expectedSessionGeneration) throw new AcpProtocolError('STALE_SESSION_GENERATION', `${profile.provider} replacement target is no longer current`);
			this.#agents.delete(profile.agentId);
		}
		const lifecycleGeneration = this.#lifecycleGeneration;
		const promise = Promise.resolve().then(() => existing?.dispose()).then(() => this.#createAgentOnce(profile, recoverySummary, lifecycleGeneration));
		const entry = { profile, promise };
		this.#replacing.set(profile.agentId, entry);
		try { return await promise; }
		finally { if (this.#replacing.get(profile.agentId) === entry) this.#replacing.delete(profile.agentId); }
	}

	async #createAgentOnce(profile, recoverySummary, lifecycleGeneration) {
		const cwd = this.#workspaceManager === null
			? this.#config.cwd
			: await this.#workspaceManager.prepare(profile.provider, profile.agentId);
		assertLifecycleActive(lifecycleGeneration, this.#lifecycleGeneration, this.#config.provider);
		const transport = this.#transportFactory({ ...profile, cwd });
		const sessionGeneration = (this.#sessionGenerations.get(profile.agentId) ?? 0) + 1;
		this.#sessionGenerations.set(profile.agentId, sessionGeneration);
		let agent;
		agent = new AcpAgent(profile, transport, {
			planningTimeoutMs: this.#config.planningTimeoutMs,
			maxDecisionBytes: this.#config.maxDecisionBytes,
			recoverySummary: normalizeRecoverySummary(recoverySummary),
			sessionGeneration,
			resetReason: sessionGeneration > 1 ? 'session_replaced' : null,
			onInvalidated: (error) => this.#invalidateAgent(profile.agentId, agent, error),
		});
		try {
			await agent.start(cwd);
			assertLifecycleActive(lifecycleGeneration, this.#lifecycleGeneration, this.#config.provider);
		} catch (error) {
			await transport.stop();
			if (error?.code === 'PROVIDER_STOPPED') throw error;
			if (error instanceof AcpProtocolError && ['UNSUPPORTED_MODEL', 'UNSUPPORTED_THINKING', 'PROVIDER_SETTINGS_MISMATCH'].includes(error.code)) throw error;
			throw new AcpProtocolError('PROVIDER_UNAVAILABLE', `${profile.provider} CLI could not create an ACP session: ${error.message}`, { cause: error });
		}
		this.#agents.set(profile.agentId, agent);
		return agent;
	}

	async removeAgent(agentId) {
		const agent = this.#agents.get(agentId);
		if (agent === undefined) return false;
		this.#agents.delete(agentId);
		await agent.dispose();
		return true;
	}

	async reconcile(records, { signal } = {}) {
		if (!Array.isArray(records)) throw new TypeError(`${this.#config.provider} reconciliation records must be an array`);
		assertReconciliationActive(signal, this.#config.provider);
		await this.catalog.refresh();
		assertReconciliationActive(signal, this.#config.provider);
		const desiredIds = new Set(records.map((record) => record.agentId));
		const removed = [];
		for (const agentId of this.#agents.keys()) if (!desiredIds.has(agentId)) {
			assertReconciliationActive(signal, this.#config.provider);
			await this.removeAgent(agentId);
			assertReconciliationActive(signal, this.#config.provider);
			removed.push(agentId);
		}
		const valid = [];
		const invalid = [];
		for (const record of records) {
			try { valid.push(validateProfile(record, this.#config)); } catch (error) { invalid.push({ profile: record, code: error.code ?? 'INVALID_PROFILE', message: error.message }); }
		}
		const catalog = await this.catalog.refresh();
		assertReconciliationActive(signal, this.#config.provider);
		return { valid, invalid, removed, catalog };
	}

	async stop() {
		this.#lifecycleGeneration += 1;
		await Promise.allSettled([...this.#creating.values()].map((entry) => entry.promise));
		this.#creating.clear();
		this.#replacing.clear();
		const agents = [...this.#agents.values()];
		this.#agents.clear();
		await Promise.allSettled(agents.map((agent) => agent.dispose()));
	}

	#invalidateAgent(agentId, agent, error) {
		if (this.#agents.get(agentId) === agent) this.#agents.delete(agentId);
		agent.invalidateTransport(error);
	}
}

function assertReconciliationActive(signal, provider) {
	if (signal?.aborted) throw new AcpProtocolError('STALE_RECONCILIATION', `${provider} reconciliation was superseded`);
}

function assertLifecycleActive(expected, current, provider) {
	if (expected !== current) throw new AcpProtocolError('PROVIDER_STOPPED', `${provider} service lifecycle was stopped`);
}

class AcpAgent {
	#profile;
	#transport;
	#planningTimeoutMs;
	#maxDecisionBytes;
	#recoverySummary;
	#sessionGeneration;
	#sessionState = 'cold';
	#plannerInstructionsInstalled = false;
	#resetReason;
	#sessionId = null;
	#goalRevision = 0;
	#active = false;
	#disposed = false;
	#invalidationError = null;
	#onInvalidated;
	#executionSettings;

	constructor(profile, transport, { planningTimeoutMs, maxDecisionBytes, recoverySummary, sessionGeneration = 1, resetReason = null, onInvalidated = () => {} }) {
		this.#profile = structuredClone(profile);
		this.#transport = transport;
		this.#planningTimeoutMs = planningTimeoutMs;
		this.#maxDecisionBytes = maxDecisionBytes;
		this.#recoverySummary = recoverySummary;
		this.#sessionGeneration = sessionGeneration;
		this.#resetReason = resetReason ?? null;
		this.#onInvalidated = onInvalidated;
		this.#executionSettings = createExecutionSettings(profile, {
			transport: 'acp', controlProtocol: 'arena_script',
			evidence: { reasoningEffort: profile.provider === 'kimi' ? 'process_environment' : 'unreported', serviceTier: 'not_supported' },
		});
		this.#transport.on?.('exit', (error) => this.#onInvalidated(sessionInvalidated(error, this.provider)));
		this.#transport.on?.('protocolError', (error) => this.#onInvalidated(sessionInvalidated(error, this.provider)));
	}

	get agentId() { return this.#profile.agentId; }
	get provider() { return this.#profile.provider; }
	get serviceTier() { return this.#profile.serviceTier; }
	get sessionGeneration() { return this.#sessionGeneration; }
	get profileFingerprint() { return profileFingerprint(this.#profile); }
	get executionSettings() { return structuredClone(this.#executionSettings); }
	sessionMetadata() {
		return createSessionMetadata(this.#profile, { sessionGeneration: this.#sessionGeneration, sessionState: this.#sessionState, continuation: 'durable', durability: 'proven', resetReason: this.#resetReason });
	}
	matchesProfile(profile) { return profilesMatch(this.#profile, profile); }

	async start(cwd) {
		this.#assertAvailable();
		await this.#transport.start();
		this.#assertAvailable();
		const initialized = await this.#transport.request('initialize', { protocolVersion: 1, clientCapabilities: CLIENT_CAPABILITIES, clientInfo: CLIENT_INFO });
		this.#assertAvailable();
		if (initialized?.protocolVersion !== 1) throw new AcpProtocolError('UNSUPPORTED_PROTOCOL', `${this.provider} ACP did not negotiate protocol version 1`);
		const session = await this.#transport.request('session/new', { cwd, mcpServers: [] });
		this.#assertAvailable();
		if (typeof session?.sessionId !== 'string' || session.sessionId.length === 0) throw new AcpProtocolError('INVALID_SESSION', `${this.provider} ACP session/new returned no sessionId`);
		this.#sessionId = session.sessionId;
		await this.#applyConfig(session.configOptions ?? []);
		this.#assertAvailable();
	}

	#assertAvailable() {
		if (this.#disposed) throw this.#invalidationError ?? new AcpProtocolError('AGENT_DISPOSED', `${this.provider} agent '${this.agentId}' is disposed`);
	}

	async #applyConfig(configOptions) {
		let currentOptions = configOptions;
		let requestedModel = this.#profile.model;
		if (this.#profile.model !== 'auto') {
			const model = findOption(currentOptions, 'model');
			requestedModel = resolveKimiApiKeyModel(this.provider, this.#profile.model, model);
			assertOptionValue(model, requestedModel, 'UNSUPPORTED_MODEL', `${this.provider} model`);
			if (model.currentValue !== requestedModel) {
				currentOptions = await this.#setConfig(model.id, requestedModel, currentOptions);
			}
		}
		this.#executionSettings.modelSelector = requestedModel;
		this.#recordEffectiveSetting('model', findOption(currentOptions, 'model', { optional: true }), requestedModel === 'auto' ? null : requestedModel);
		const thinking = findOption(currentOptions, 'thought_level', { optional: this.provider === 'kimi' });
		if (thinking === null) {
			this.#executionSettings.limitations.push('effort_not_reported_by_provider');
			return;
		}
		const requested = this.#profile.reasoningEffort;
		if (this.provider === 'kimi' && isBooleanThinkingOption(thinking)) {
			if (thinking.currentValue !== 'on') currentOptions = await this.#setConfig(thinking.id, 'on', currentOptions);
			const confirmedThinking = findOption(currentOptions, 'thought_level', { optional: true });
			if (confirmedThinking?.currentValue === 'off') throw new AcpProtocolError('PROVIDER_SETTINGS_MISMATCH', 'kimi did not enable the selected thinking mode');
			this.#executionSettings.effective.thinkingMode = confirmedThinking?.currentValue === 'on' ? 'on' : null;
			this.#executionSettings.limitations.push('effort_not_reported_by_provider');
			return;
		}
		assertOptionValue(thinking, requested, 'UNSUPPORTED_THINKING', `${this.provider} thinking`);
		if (thinking.currentValue !== requested) currentOptions = await this.#setConfig(thinking.id, requested, currentOptions);
		this.#recordEffectiveSetting('reasoningEffort', findOption(currentOptions, 'thought_level', { optional: true }), requested);
	}

	#recordEffectiveSetting(field, option, requested) {
		const value = option?.currentValue;
		const reported = typeof value === 'string' && value.length > 0 && value.length <= 256 && !(field === 'model' && value === 'auto') ? value : null;
		if (reported !== null && requested !== null && reported !== requested) {
			throw new AcpProtocolError('PROVIDER_SETTINGS_MISMATCH', `${this.provider} did not confirm the selected ${field}`);
		}
		this.#executionSettings.effective[field] = reported;
		this.#executionSettings.evidence[field] = reported === null ? 'submitted' : 'provider_reported';
	}

	async #setConfig(configId, value, fallbackOptions) {
		const response = await this.#transport.request('session/set_config_option', { sessionId: this.#sessionId, configId, value });
		return Array.isArray(response?.configOptions) ? response.configOptions : fallbackOptions.map((option) => option.id === configId ? { ...option, currentValue: null } : option);
	}

	async setGoalRevision(revision) {
		if (!Number.isSafeInteger(revision) || revision < 0) throw new TypeError('goalRevision must be a nonnegative safe integer');
		if (revision < this.#goalRevision) throw new AcpProtocolError('STALE_GOAL_REVISION', `Goal revision ${revision} is older than ${this.#goalRevision}`);
		if (revision !== this.#goalRevision && this.#active) this.interrupt();
		this.#goalRevision = revision;
	}

	async decide(input, {
		goalRevision, signal, turnRecorder = null, attempt = 1, retry = false, queueWaitMs, onVerbose = null,
		parseOutput = parseDecision, systemPrompt,
	} = {}) {
		if (this.#disposed) throw this.#invalidationError ?? new AcpProtocolError('AGENT_DISPOSED', `${this.provider} agent '${this.agentId}' is disposed`);
		if (this.#active) throw new AcpProtocolError('TURN_IN_PROGRESS', `${this.provider} agent '${this.agentId}' already has an active turn`);
		if (typeof input !== 'string' || input.trim().length === 0) throw new TypeError('planner input must be nonblank');
		if (typeof parseOutput !== 'function') throw new TypeError('parseOutput must be a function');
		if (systemPrompt !== undefined && typeof systemPrompt !== 'string') throw new TypeError('systemPrompt must be a string');
		if (goalRevision !== this.#goalRevision) throw new AcpProtocolError('STALE_GOAL_REVISION', `Goal revision ${String(goalRevision)} does not match ${this.#goalRevision}`);
		if (signal?.aborted) throw signal.reason ?? new AcpProtocolError('PLAN_CANCELLED', 'Planning was cancelled');
		const turnStartedAt = performance.now();
		const chunks = [];
		let decisionBytes = 0;
		let outputLimitError = null;
		let rejectOutputLimit;
		const outputLimit = new Promise((_, reject) => { rejectOutputLimit = reject; });
		void outputLimit.catch(() => { /* the decision awaits this promise in the request race */ });
		const onNotification = ({ method, params }) => {
			if (method !== 'session/update' || params?.sessionId !== this.#sessionId) return;
			const update = params.update;
			if (outputLimitError !== null || update?.sessionUpdate !== 'agent_message_chunk' || update.content?.type !== 'text' || typeof update.content.text !== 'string') return;
			const chunk = update.content.text;
			const chunkBytes = Buffer.byteLength(chunk, 'utf8');
			if (decisionBytes + chunkBytes > this.#maxDecisionBytes) {
				outputLimitError = new AcpProtocolError('PLANNER_OUTPUT_LIMIT', `${this.provider} planner output exceeded ${this.#maxDecisionBytes} bytes`);
				rejectOutputLimit(outputLimitError);
				try { this.interrupt(); } catch { /* the bounded failure remains authoritative */ }
				return;
			}
			decisionBytes += chunkBytes;
			chunks.push(chunk);
			reportVisibleOutput(onVerbose, chunk);
		};
		const abort = () => this.interrupt();
		this.#active = true;
		this.#transport.on('notification', onNotification);
		signal?.addEventListener('abort', abort, { once: true });
		let rawOutput = '';
		let outputHandled = false;
		const usesPlannerContract = systemPrompt === undefined;
		const prompt = usesPlannerContract
			? buildProviderPlannerPrompt(input, {
				instructionsInstalled: this.#plannerInstructionsInstalled,
				recoverySummary: this.#recoverySummary,
			})
			: `${systemPrompt}${systemPrompt.length === 0 ? '' : '\n\n'}${input}`;
		try {
			const response = await withTimeout(Promise.race([this.#transport.request('session/prompt', {
				sessionId: this.#sessionId,
				prompt: [{ type: 'text', text: prompt }],
			}, { timeoutMs: this.#planningTimeoutMs }), outputLimit]), this.#planningTimeoutMs);
			if (this.#disposed) throw this.#invalidationError ?? new AcpProtocolError('SESSION_INVALIDATED', `${this.provider} session was invalidated`);
			if (signal?.aborted || goalRevision !== this.#goalRevision) throw new AcpProtocolError('STALE_PLAN', `${this.provider} result belongs to an obsolete goal`);
			if (response?.stopReason !== 'end_turn') throw new AcpProtocolError('INCOMPLETE_TURN', `${this.provider} ACP stopped with '${String(response?.stopReason)}'`);
			if (usesPlannerContract) this.#plannerInstructionsInstalled = true;
			this.#sessionState = 'warm';
			const decisionText = chunks.join('');
			rawOutput = decisionText;
			if (this.provider === 'kimi' && decisionText.trim().length === 0) {
				throw new AcpProtocolError(
					'PROVIDER_UNAVAILABLE',
					'Kimi ended the turn without a response. Verify the Kimi CLI login and membership entitlement.',
				);
			}
			let decision;
			let parseError = null;
			try { decision = parseOutput(decisionText); }
			catch (error) {
				parseError = new AcpProtocolError(
					error?.code ?? 'INVALID_DECISION',
					`${this.provider} returned an invalid planner decision: ${error?.message ?? String(error)} [output=${decisionExcerpt(decisionText)}]`,
					{ cause: error },
				);
				parseError.category = 'decision_parse';
			}
			outputHandled = true;
			const tokens = acpTokenUsage(response?.usage) ?? (this.provider === 'gemini' ? geminiQuotaTokenUsage(response?._meta) : null);
			recordProviderTurn(turnRecorder, {
				executionSettings: this.executionSettings,
				agentId: this.agentId,
				provider: this.provider, model: this.#profile.model, reasoningEffort: this.#profile.reasoningEffort,
				goalRevision, attempt, retry, input: prompt, output: parseError === null ? decisionText : '', error: structuredProviderError(parseError),
				timing: providerTiming(Math.max(0, performance.now() - turnStartedAt), null, queueWaitMs),
				...(tokens === null ? {} : { tokens }),
			});
			if (parseError !== null) throw parseError;
			return decision;
		} catch (error) {
			if (!outputHandled) recordProviderTurn(turnRecorder, {
				executionSettings: this.executionSettings,
				agentId: this.agentId,
				provider: this.provider, model: this.#profile.model, reasoningEffort: this.#profile.reasoningEffort,
				goalRevision, attempt, retry, input: prompt, output: rawOutput, error,
				timing: providerTiming(Math.max(0, performance.now() - turnStartedAt), null, queueWaitMs),
				...(isRateLimitError(error) ? { rateLimited: true } : {}),
			});
			throw error;
		} finally {
			this.#active = false;
			this.#transport.off('notification', onNotification);
			signal?.removeEventListener('abort', abort);
		}
	}

	interrupt() {
		if (this.#sessionId === null) return;
		try { this.#transport.notify('session/cancel', { sessionId: this.#sessionId }); }
		catch { /* cancellation is best effort; the bounded turn remains authoritative */ }
	}
	async dispose() { if (this.#disposed) return; this.#disposed = true; if (this.#active) this.interrupt(); await this.#transport.stop(); }
	invalidateTransport(error) {
		if (this.#invalidationError !== null) return;
		this.#invalidationError = error;
		this.#disposed = true;
		this.#sessionId = null;
		this.#active = false;
		void Promise.resolve(this.#transport.stop()).catch(() => {});
	}
}

function sessionInvalidated(error, provider) {
	return new AcpProtocolError('SESSION_INVALIDATED', `${provider} provider transport was lost`, { cause: error instanceof Error ? error : undefined });
}

function resolveKimiApiKeyModel(provider, requestedModel, modelOption) {
	if (provider !== 'kimi') return requestedModel;
	const apiKeyModel = {
		'kimi-code/k3': 'moonshot-ai/kimi-k3',
		'kimi-code/kimi-for-coding': 'moonshot-ai/kimi-k2.7-code',
		'kimi-code/kimi-for-coding-highspeed': 'moonshot-ai/kimi-k2.7-code-highspeed',
	}[requestedModel];
	if (apiKeyModel === undefined) return requestedModel;
	return Array.isArray(modelOption?.options)
		&& modelOption.options.some((option) => option?.value === apiKeyModel)
		? apiKeyModel
		: requestedModel;
}

function decisionExcerpt(value) {
	return JSON.stringify(String(value).replace(/[\u0000-\u001f\u007f]/g, ' ').slice(0, 512));
}

function acpTokenUsage(value) {
	if (value === null || typeof value !== 'object' || Array.isArray(value)) return null;
	return {
		input: nativeToken(value.inputTokens), output: nativeToken(value.outputTokens), reasoning: nativeToken(value.thoughtTokens),
		cached: nativeToken(value.cachedReadTokens), cacheWrite: nativeToken(value.cachedWriteTokens),
	};
}

function nativeToken(value) { return Number.isSafeInteger(value) && value >= 0 ? value : null; }
function providerTiming(durationMs, apiDurationMs, queueWaitMs) { return { durationMs, apiDurationMs, ...(Number.isFinite(queueWaitMs) && queueWaitMs >= 0 ? { queueWaitMs } : {}) }; }
function geminiQuotaTokenUsage(value) {
	const counts = value?.quota?.token_count;
	if (counts === null || typeof counts !== 'object' || Array.isArray(counts)) return null;
	return { input: nativeToken(counts.input_tokens), output: nativeToken(counts.output_tokens), reasoning: null, cached: null, cacheWrite: null };
}

function isRateLimitError(error) {
	return [error?.code, error?.status, error?.statusCode, error?.httpStatusCode, error?.data?.status, error?.data?.httpStatusCode].some((value) => value === 429);
}

function structuredProviderError(error) {
	return error === null ? null : { code: typeof error.code === 'string' ? error.code : 'PROVIDER_ERROR', category: error.category === 'decision_parse' ? 'decision_parse' : 'provider' };
}

class AcpCatalog {
	#config;
	#dependencies;
	#snapshot = null;
	#refreshPromise = null;

	constructor(config, dependencies) {
		this.#config = config;
		this.#dependencies = dependencies;
		this.stale = config.catalogDiscovery === true;
	}

	async refresh({ force = false } = {}) {
		if (!force && !this.stale && this.#snapshot !== null) return structuredClone(this.#snapshot);
		if (this.#refreshPromise !== null) return structuredClone(await this.#refreshPromise);
		const refreshPromise = this.#refreshOnce();
		this.#refreshPromise = refreshPromise;
		try { return structuredClone(await refreshPromise); }
		finally { if (this.#refreshPromise === refreshPromise) this.#refreshPromise = null; }
	}

	async #refreshOnce() {
		let models = null;
		let discoveryFailed = false;
		if (this.#config.catalogDiscovery === true && this.#config.provider === 'kimi') {
			try {
				models = await this.#dependencies.discover({
					executable: this.#config.executable ?? 'kimi',
					execFile: this.#dependencies.execFile,
					environment: this.#dependencies.environment,
					timeoutMs: this.#config.catalogDiscoveryTimeoutMs,
				});
			} catch {
				discoveryFailed = true;
				if (this.#snapshot !== null) {
					this.stale = true;
					return this.#snapshot;
				}
			}
		}
		if (!Array.isArray(models) || models.length === 0) models = configuredModels(this.#config);
		this.#config.models = models.map((model) => model.id);
		this.#config.modelReasoningEfforts = Object.fromEntries(models.map((model) => [model.id, [...model.reasoningEfforts]]));
		this.#snapshot = {
			provider: this.#config.provider,
			refreshedAtEpochMs: Date.now(),
			models: models.map((model) => ({ ...model, reasoningEfforts: [...model.reasoningEfforts], serviceTiers: [...(model.serviceTiers ?? [])] })),
		};
		this.stale = discoveryFailed;
		return this.#snapshot;
	}

	assertSupported(model, reasoningEffort) {
		if (!this.#config.models.includes(model)) throw new AcpProtocolError('UNSUPPORTED_MODEL', `${this.#config.provider} model '${model}' is not configured`);
		if (!this.#config.modelReasoningEfforts[model]?.includes(reasoningEffort)) throw new AcpProtocolError('UNSUPPORTED_THINKING', `${this.#config.provider} model '${model}' does not support thinking '${reasoningEffort}'`);
	}
}

function configuredModels(config) {
	return config.models.map((id) => ({
		id,
		model: id,
		displayName: id,
		reasoningEfforts: [...config.modelReasoningEfforts[id]],
		serviceTiers: [],
	}));
}

function validateServiceConfig(config) {
	if (config === null || typeof config !== 'object' || Array.isArray(config)) throw new TypeError('ACP service config must be an object');
	const provider = config.provider;
	if (!['gemini', 'kimi'].includes(provider)) throw new TypeError('ACP provider must be gemini or kimi');
	const defaults = provider === 'kimi'
		? { models: ['kimi-code/k3', 'kimi-code/k3-256k', 'kimi-code/kimi-for-coding', 'kimi-code/kimi-for-coding-highspeed'], reasoningEfforts: ['low', 'high', 'max'], modelReasoningEfforts: { 'kimi-code/k3': ['low', 'high', 'max'], 'kimi-code/k3-256k': ['low', 'high', 'max'], 'kimi-code/kimi-for-coding': ['high'], 'kimi-code/kimi-for-coding-highspeed': ['high'] } }
		: { models: ['auto'], reasoningEfforts: ['low', 'medium', 'high'] };
	const models = requireStringArray(config.models ?? defaults.models, 'models');
	const reasoningEfforts = requireStringArray(config.reasoningEfforts ?? defaults.reasoningEfforts, 'reasoningEfforts');
	const configuredEfforts = config.modelReasoningEfforts ?? defaults.modelReasoningEfforts ?? Object.fromEntries(models.map((model) => [model, reasoningEfforts]));
	return {
		...config,
		provider,
		cwd: requireText(config.cwd, 'cwd'),
		models,
		reasoningEfforts,
		modelReasoningEfforts: Object.fromEntries(models.map((model) => [model, requireStringArray(configuredEfforts[model] ?? reasoningEfforts, `modelReasoningEfforts.${model}`)])),
		catalogDiscovery: config.catalogDiscovery === true,
		catalogDiscoveryTimeoutMs: positiveInteger(config.catalogDiscoveryTimeoutMs ?? DEFAULT_DISCOVERY_TIMEOUT_MS, 'catalogDiscoveryTimeoutMs'),
		planningTimeoutMs: positiveInteger(config.planningTimeoutMs ?? DEFAULT_PLANNING_TIMEOUT_MS, 'planningTimeoutMs'),
		maxDecisionBytes: positiveInteger(config.maxDecisionBytes ?? DEFAULT_MAX_DECISION_BYTES, 'maxDecisionBytes'),
	};
}

function validateProfile(value, config) {
	if (value === null || typeof value !== 'object' || Array.isArray(value)) throw new TypeError('agent profile must be an object');
	const profile = {
		agentId: requireText(value.agentId, 'agentId'),
		provider: value.provider ?? 'codex',
		model: requireText(value.model, 'model'),
		reasoningEffort: requireText(value.reasoningEffort, 'reasoningEffort'),
		serviceTier: requireText(value.serviceTier ?? config.serviceTier ?? DEFAULT_SERVICE_TIER, 'serviceTier'),
	};
	if (profile.provider !== config.provider) throw new AcpProtocolError('PROVIDER_MISMATCH', `Expected ${config.provider} profile, received ${profile.provider}`);
	if (!config.models.includes(profile.model)) throw new AcpProtocolError('UNSUPPORTED_MODEL', `${config.provider} model '${profile.model}' is not configured`);
	if (!config.modelReasoningEfforts[profile.model]?.includes(profile.reasoningEffort)) {
		throw new AcpProtocolError('UNSUPPORTED_THINKING', `${config.provider} model '${profile.model}' does not support thinking '${profile.reasoningEffort}'`);
	}
	return profile;
}

function profilesMatch(left, right) {
	return PROFILE_KEYS.every((key) => left[key] === right[key]);
}

function profileIdentity(value, config) {
	if (value === null || typeof value !== 'object' || Array.isArray(value)) throw new TypeError('agent profile must be an object');
	return {
		agentId: requireText(value.agentId, 'agentId'),
		provider: value.provider ?? 'codex',
		model: requireText(value.model, 'model'),
		reasoningEffort: requireText(value.reasoningEffort, 'reasoningEffort'),
		serviceTier: requireText(value.serviceTier ?? config.serviceTier ?? DEFAULT_SERVICE_TIER, 'serviceTier'),
	};
}

function profileConflict() {
	return new AcpProtocolError('AGENT_PROFILE_CONFLICT', 'Agent profile is immutable for the active ACP session');
}

function findOption(options, category, { optional = false } = {}) {
	if (!Array.isArray(options)) throw new AcpProtocolError('INVALID_CONFIG_OPTIONS', 'ACP configOptions must be an array');
	const option = options.find((entry) => entry?.category === category || entry?.id === (category === 'thought_level' ? 'thinking' : category));
	if (option === undefined && optional) return null;
	if (option === undefined) throw new AcpProtocolError(category === 'model' ? 'UNSUPPORTED_MODEL' : 'UNSUPPORTED_THINKING', `ACP session did not expose a ${category} configuration option`);
	return option;
}

function assertOptionValue(option, value, code, label) {
	const values = Array.isArray(option.options) ? option.options.map((entry) => typeof entry === 'string' ? entry : entry?.value) : [];
	if (!values.includes(value)) throw new AcpProtocolError(code, `${label} '${value}' is not supported by this ACP session`);
}

function isBooleanThinkingOption(option) {
	const values = Array.isArray(option.options)
		? option.options.map((entry) => typeof entry === 'string' ? entry : entry?.value).filter((value) => typeof value === 'string')
		: [];
	return values.length > 0 && values.every((value) => ['on', 'off'].includes(value));
}

function requireStringArray(value, field) { if (!Array.isArray(value) || value.length === 0) throw new TypeError(`${field} must be a nonempty array`); return [...new Set(value.map((entry) => requireText(entry, field)))]; }
function normalizeRecoverySummary(value) { if (value === null || value === undefined || value === '') return null; if (typeof value !== 'string' || value.length > 2_048) throw new TypeError('recoverySummary must be at most 2048 characters'); return value; }
function requireText(value, field) { if (typeof value !== 'string' || value.trim().length === 0) throw new TypeError(`${field} must be nonblank`); return value.trim(); }
function positiveInteger(value, field) { if (!Number.isSafeInteger(value) || value <= 0) throw new TypeError(`${field} must be a positive safe integer`); return value; }
function withTimeout(promise, timeoutMs) { return new Promise((resolve, reject) => { const timer = setTimeout(() => reject(new AcpProtocolError('PLANNING_TIMEOUT', `ACP planning timed out after ${timeoutMs} ms`)), timeoutMs); promise.then((value) => { clearTimeout(timer); resolve(value); }, (error) => { clearTimeout(timer); reject(error); }); }); }
