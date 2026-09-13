import path from 'node:path';
import { spawn as nodeSpawn } from 'node:child_process';

import { AcpProtocolError } from './acp-transport.mjs';
import { terminateChildProcess } from './child-process-lifecycle.mjs';
import { parseDecision } from './decision-parser.mjs';
import { createProviderChildEnvironment } from './provider-environment.mjs';
import { recordProviderTurn } from './provider-turn-recorder.mjs';
import { buildProviderPlannerPrompt } from './prompts.mjs';
import { createSessionMetadata, profileFingerprint } from './provider-session.mjs';
import { reportVisibleOutput } from './verbose-output.mjs';
import { createExecutionSettings } from './provider-identity.mjs';

const DEFAULT_MODELS = Object.freeze(['composer-2.5', 'grok-4.5', 'grok-4.6']);
const DEFAULT_REASONING = Object.freeze({
	'composer-2.5': Object.freeze(['high']),
	'grok-4.5': Object.freeze(['low', 'medium', 'high']),
	'grok-4.6': Object.freeze(['low', 'medium', 'high', 'xhigh']),
});
const DEFAULT_PLANNING_TIMEOUT_MS = 120_000;
const DEFAULT_DISCOVERY_TIMEOUT_MS = 15_000;
const DEFAULT_STDOUT_LIMIT_BYTES = 1_024 * 1_024;
const DEFAULT_STDERR_LIMIT_BYTES = 64 * 1_024;

export class CursorProviderService {
	#config;
	#dependencies;
	#workspaceManager;
	#agents = new Map();
	#creating = new Map();
	#replacing = new Map();
	#sessionGenerations = new Map();
	#lifecycleGeneration = 0;

	constructor(config, dependencies = {}) {
		const platform = dependencies.platform ?? process.platform;
		const environment = dependencies.environment ?? config?.environment ?? process.env;
		this.#config = validateServiceConfig(config, { platform, environment });
		this.#dependencies = {
			spawn: dependencies.spawn ?? nodeSpawn,
			terminate: dependencies.terminate ?? terminateChildProcess,
			discoverCatalog: dependencies.discoverCatalog ?? discoverCursorCatalog,
			platform,
		};
		this.#workspaceManager = dependencies.workspaceManager ?? null;
		if (this.#workspaceManager !== null && typeof this.#workspaceManager.prepare !== 'function') {
			throw new TypeError('workspaceManager must expose prepare(provider, agentId)');
		}
		this.catalog = new CursorCatalog(this.#config, this.#dependencies);
	}

	async start() {}
	get agentIds() { return [...this.#agents.keys()]; }
	getAgent(agentId) { return this.#agents.get(agentId) ?? null; }

	async createAgent(profileValue, { recoverySummary = null } = {}) {
		const lifecycleGeneration = this.#lifecycleGeneration;
		const requested = profileIdentity(profileValue);
		const replacing = this.#replacing.get(requested.agentId);
		if (replacing !== undefined) {
			if (!profilesMatch(replacing.profile, requested)) throw new AcpProtocolError('AGENT_PROFILE_CONFLICT', `cursor agent '${requested.agentId}' is being replaced with a different profile`);
			return replacing.promise;
		}
		const existing = this.#agents.get(requested.agentId);
		if (existing !== undefined) {
			if (!existing.matchesProfile(requested)) throw new AcpProtocolError('AGENT_PROFILE_CONFLICT', `cursor agent '${requested.agentId}' already has a different profile`);
			return existing;
		}
		const creating = this.#creating.get(requested.agentId);
		if (creating !== undefined) {
			if (!profilesMatch(creating.profile, requested)) throw new AcpProtocolError('AGENT_PROFILE_CONFLICT', `cursor agent '${requested.agentId}' is being created with a different profile`);
			return creating.promise;
		}
		await this.catalog.refresh();
		assertLifecycleActive(lifecycleGeneration, this.#lifecycleGeneration);
		const profile = validateProfile(profileValue, this.#config);
		const promise = this.#createAgentOnce(profile, recoverySummary, lifecycleGeneration);
		this.#creating.set(profile.agentId, { profile, promise });
		try { return await promise; } finally { this.#creating.delete(profile.agentId); }
	}

	async replaceAgent(profileValue, { recoverySummary = null, expectedSessionGeneration = null } = {}) {
		const lifecycleGeneration = this.#lifecycleGeneration;
		await this.catalog.refresh();
		assertLifecycleActive(lifecycleGeneration, this.#lifecycleGeneration);
		const profile = validateProfile(profileValue, this.#config);
		const replacing = this.#replacing.get(profile.agentId);
		if (replacing !== undefined) {
			if (!profilesMatch(replacing.profile, profile)) throw new AcpProtocolError('AGENT_PROFILE_CONFLICT', `cursor agent '${profile.agentId}' is being replaced with a different profile`);
			return replacing.promise;
		}
		const creating = this.#creating.get(profile.agentId);
		if (creating !== undefined && !profilesMatch(creating.profile, profile)) throw new AcpProtocolError('AGENT_PROFILE_CONFLICT', `cursor agent '${profile.agentId}' is being created with a different profile`);
		const existing = this.#agents.get(profile.agentId);
		if (existing !== undefined && !existing.matchesProfile(profile)) throw new AcpProtocolError('AGENT_PROFILE_CONFLICT', `cursor agent '${profile.agentId}' already has a different profile`);
		const currentGeneration = existing?.sessionGeneration ?? this.#sessionGenerations.get(profile.agentId) ?? 0;
		if (expectedSessionGeneration !== null && expectedSessionGeneration !== currentGeneration) {
			throw new AcpProtocolError('SESSION_GENERATION_MISMATCH', `Cursor session generation ${currentGeneration} does not match expected ${expectedSessionGeneration}`);
		}
		const promise = (async () => {
			if (creating !== undefined) await creating.promise;
			const owned = this.#agents.get(profile.agentId);
			if (owned !== undefined) {
				this.#agents.delete(profile.agentId);
				owned.invalidateSession(new AcpProtocolError('SESSION_INVALIDATED', 'Cursor session was replaced'));
				await owned.dispose();
			}
			return this.#createAgentOnce(profile, recoverySummary, lifecycleGeneration);
		})();
		this.#replacing.set(profile.agentId, { profile, promise });
		try { return await promise; } finally {
			if (this.#replacing.get(profile.agentId)?.promise === promise) this.#replacing.delete(profile.agentId);
		}
	}

	async #createAgentOnce(profile, recoverySummary, lifecycleGeneration) {
		let cwd;
		try {
			cwd = this.#workspaceManager === null
				? this.#config.cwd
				: await this.#workspaceManager.prepare(profile.provider, profile.agentId);
		} catch (error) {
			throw new AcpProtocolError('PROVIDER_UNAVAILABLE', `Could not prepare the Cursor agent workspace: ${error?.message ?? String(error)}`, { cause: error });
		}
		assertLifecycleActive(lifecycleGeneration, this.#lifecycleGeneration);
		const sessionGeneration = (this.#sessionGenerations.get(profile.agentId) ?? 0) + 1;
		this.#sessionGenerations.set(profile.agentId, sessionGeneration);
		let agent;
		agent = new CursorAgent(profile, cwd, {
			...this.#dependencies,
			config: this.#config,
			recoverySummary: normalizeRecoverySummary(recoverySummary),
			sessionGeneration,
			resetReason: sessionGeneration > 1 ? 'session_replaced' : null,
			onInvalidated: () => this.#invalidateAgent(agent),
		});
		if (lifecycleGeneration !== this.#lifecycleGeneration) {
			await agent.dispose();
			throw new AcpProtocolError('PROVIDER_STOPPED', 'Cursor service lifecycle was stopped');
		}
		this.#agents.set(profile.agentId, agent);
		return agent;
	}

	#invalidateAgent(agent) {
		if (this.#agents.get(agent.agentId) === agent) this.#agents.delete(agent.agentId);
	}

	async removeAgent(agentId) {
		const agent = this.#agents.get(agentId);
		if (agent === undefined) return false;
		this.#agents.delete(agentId);
		await agent.dispose();
		return true;
	}

	async reconcile(records, { signal } = {}) {
		if (!Array.isArray(records)) throw new TypeError('cursor reconciliation records must be an array');
		assertReconciliationActive(signal);
		await this.catalog.refresh();
		assertReconciliationActive(signal);
		const desiredIds = new Set(records.map((record) => record.agentId));
		const removed = [];
		for (const agentId of this.#agents.keys()) {
			assertReconciliationActive(signal);
			if (!desiredIds.has(agentId)) {
				await this.removeAgent(agentId);
				assertReconciliationActive(signal);
				removed.push(agentId);
			}
		}
		const valid = [];
		const invalid = [];
		for (const record of records) {
			try { valid.push(validateProfile(record, this.#config)); }
			catch (error) { invalid.push({ profile: record, code: error.code ?? 'INVALID_PROFILE', message: error.message }); }
		}
		const catalog = await this.catalog.refresh();
		assertReconciliationActive(signal);
		return { valid, invalid, removed, catalog };
	}

	async stop() {
		this.#lifecycleGeneration += 1;
		await Promise.allSettled([
			...[...this.#creating.values()].map((entry) => entry.promise),
			...[...this.#replacing.values()].map((entry) => entry.promise),
		]);
		this.#creating.clear();
		this.#replacing.clear();
		const agents = [...this.#agents.values()];
		this.#agents.clear();
		await Promise.allSettled(agents.map((agent) => agent.dispose()));
	}
}

function assertReconciliationActive(signal) {
	if (signal?.aborted) throw new AcpProtocolError('STALE_RECONCILIATION', 'Cursor reconciliation was superseded');
}

function assertLifecycleActive(expected, current) {
	if (expected !== current) throw new AcpProtocolError('PROVIDER_STOPPED', 'Cursor service lifecycle was stopped');
}

class CursorAgent {
	#profile;
	#cwd;
	#config;
	#spawn;
	#terminate;
	#platform;
	#recoverySummary;
	#goalRevision = 0;
	#sessionId = null;
	#activeOperation = null;
	#disposed = false;
	#sessionGeneration;
	#sessionState = 'cold';
	#plannerInstructionsInstalled = false;
	#resetReason;
	#invalidationError = null;
	#onInvalidated;

	constructor(profile, cwd, { config, spawn, terminate, platform, recoverySummary, sessionGeneration = 1, resetReason = null, onInvalidated = null }) {
		this.#profile = structuredClone(profile);
		this.#cwd = cwd;
		this.#config = config;
		this.#spawn = spawn;
		this.#terminate = terminate;
		this.#platform = platform;
		this.#recoverySummary = recoverySummary;
		this.#sessionGeneration = sessionGeneration;
		this.#resetReason = resetReason;
		this.#onInvalidated = onInvalidated;
	}

	get agentId() { return this.#profile.agentId; }
	get provider() { return this.#profile.provider; }
	get serviceTier() { return this.#profile.serviceTier; }
	get sessionGeneration() { return this.#sessionGeneration; }
	get profileFingerprint() { return profileFingerprint(this.#profile); }
	get executionSettings() {
		return createExecutionSettings(this.#profile, {
			transport: 'cursor_cli', controlProtocol: 'arena_script', modelSelector: cursorModelSpec(this.#profile),
			evidence: { model: 'launch_argument', reasoningEffort: 'launch_argument', serviceTier: 'launch_argument' },
			limitations: ['effective_settings_not_reported_by_provider'],
		});
	}
	sessionMetadata() {
		return createSessionMetadata(this.#profile, { sessionGeneration: this.#sessionGeneration, sessionState: this.#sessionState, continuation: 'durable', durability: 'provider', resetReason: this.#resetReason });
	}
	matchesProfile(profile) { return profilesMatch(this.#profile, profile); }

	async setGoalRevision(revision) {
		if (!Number.isSafeInteger(revision) || revision < 0) throw new TypeError('goalRevision must be a nonnegative safe integer');
		if (revision < this.#goalRevision) throw new AcpProtocolError('STALE_GOAL_REVISION', `Goal revision ${revision} is older than ${this.#goalRevision}`);
		if (revision !== this.#goalRevision && this.#activeOperation !== null) await this.interrupt();
		this.#goalRevision = revision;
	}

	async decide(input, {
		goalRevision, signal, turnRecorder = null, attempt = 1, retry = false, queueWaitMs, onVerbose = null,
		parseOutput = parseDecision, systemPrompt,
	} = {}) {
		if (this.#disposed) throw this.#invalidationError ?? new AcpProtocolError('AGENT_DISPOSED', `cursor agent '${this.agentId}' is disposed`);
		if (this.#activeOperation !== null) throw new AcpProtocolError('TURN_IN_PROGRESS', `cursor agent '${this.agentId}' already has an active turn`);
		if (typeof input !== 'string' || input.trim().length === 0) throw new TypeError('planner input must be nonblank');
		if (typeof parseOutput !== 'function') throw new TypeError('parseOutput must be a function');
		if (systemPrompt !== undefined && typeof systemPrompt !== 'string') throw new TypeError('systemPrompt must be a string');
		if (goalRevision !== this.#goalRevision) throw new AcpProtocolError('STALE_GOAL_REVISION', `Goal revision ${String(goalRevision)} does not match ${this.#goalRevision}`);
		if (signal?.aborted) throw signal.reason ?? new AcpProtocolError('PLAN_CANCELLED', 'Planning was cancelled');

		const usesPlannerContract = systemPrompt === undefined;
		const prompt = usesPlannerContract
			? buildProviderPlannerPrompt(input, {
				instructionsInstalled: this.#plannerInstructionsInstalled,
				recoverySummary: this.#recoverySummary,
			})
			: `${systemPrompt}${systemPrompt.length === 0 ? '' : '\n\n'}${input}`;
		const launch = buildCursorLaunch(this.#profile, this.#config, {
			cwd: this.#cwd,
			platform: this.#platform,
			sessionId: this.#sessionId,
		});
		const operation = runCursorProcess(prompt, launch, {
			spawn: this.#spawn,
			terminate: this.#terminate,
			planningTimeoutMs: this.#config.planningTimeoutMs,
			stdoutLimitBytes: this.#config.stdoutLimitBytes,
			stderrLimitBytes: this.#config.stderrLimitBytes,
		});
		this.#activeOperation = operation;
		const abort = () => { void operation.cancel(new AcpProtocolError('PLAN_CANCELLED', 'Planning was cancelled')); };
		signal?.addEventListener('abort', abort, { once: true });
		let rawOutput = '';
		let timing = null;
		let outputHandled = false;
		try {
			const result = await operation.promise;
			rawOutput = result.result;
			timing = providerTiming(result.durationMs, result.apiDurationMs, queueWaitMs);
			if (this.#disposed) throw this.#invalidationError ?? new AcpProtocolError('SESSION_INVALIDATED', 'Cursor session was invalidated');
			if (signal?.aborted || goalRevision !== this.#goalRevision) throw new AcpProtocolError('STALE_PLAN', 'cursor result belongs to an obsolete goal');
			this.#sessionId = result.sessionId;
			if (usesPlannerContract) this.#plannerInstructionsInstalled = true;
			this.#sessionState = 'warm';
			reportVisibleOutput(onVerbose, result.result);
			let decision;
			let parseError = null;
			try { decision = parseOutput(result.result.trim()); }
			catch (error) {
				parseError = new AcpProtocolError(error?.code ?? 'INVALID_DECISION', 'cursor returned an invalid planner decision', { cause: error });
				parseError.category = 'decision_parse';
			}
			outputHandled = true;
			recordProviderTurn(turnRecorder, {
				executionSettings: this.executionSettings,
				agentId: this.agentId,
				provider: 'cursor', model: this.#profile.model, reasoningEffort: this.#profile.reasoningEffort,
				goalRevision, attempt, retry, input: prompt, output: parseError === null ? result.result : '', error: structuredProviderError(parseError), timing,
				...(result.tokens === null ? {} : { tokens: result.tokens }),
			});
			if (parseError !== null) throw parseError;
			return decision;
		} catch (error) {
			if (!outputHandled) recordProviderTurn(turnRecorder, {
				executionSettings: this.executionSettings,
				agentId: this.agentId,
				provider: 'cursor', model: this.#profile.model, reasoningEffort: this.#profile.reasoningEffort,
				goalRevision, attempt, retry, input: prompt, output: rawOutput, error, timing,
			});
			if (signal?.aborted || goalRevision !== this.#goalRevision) throw new AcpProtocolError('STALE_PLAN', 'cursor result belongs to an obsolete goal', { cause: error });
			if (isSessionFailure(error)) this.invalidateSession(error);
			throw error;
		} finally {
			signal?.removeEventListener('abort', abort);
			if (this.#activeOperation === operation) this.#activeOperation = null;
		}
	}

	async interrupt() {
		if (this.#activeOperation !== null) await this.#activeOperation.cancel(new AcpProtocolError('PLAN_CANCELLED', 'Planning was cancelled'));
	}

	async dispose() {
		this.#disposed = true;
		await this.interrupt();
	}

	invalidateSession(cause) {
		if (this.#invalidationError !== null) return;
		this.#invalidationError = new AcpProtocolError('SESSION_INVALIDATED', 'Cursor session transport is no longer usable', { cause });
		this.#disposed = true;
		this.#sessionId = null;
		this.#sessionState = 'cold';
		this.#onInvalidated?.(this);
	}
}

const SESSION_FAILURE_CODES = new Set([
	'SPAWN_FAILED', 'PROVIDER_UNAVAILABLE', 'PLANNING_TIMEOUT', 'PROCESS_TERMINATION_FAILED', 'OUTPUT_LIMIT_EXCEEDED', 'INVALID_PROVIDER_OUTPUT',
]);

function isSessionFailure(error) {
	return SESSION_FAILURE_CODES.has(error?.code);
}

function providerTiming(durationMs, apiDurationMs, queueWaitMs) { return { durationMs, apiDurationMs, ...(Number.isFinite(queueWaitMs) && queueWaitMs >= 0 ? { queueWaitMs } : {}) }; }
function structuredProviderError(error) { return error === null ? null : { code: typeof error.code === 'string' ? error.code : 'PROVIDER_ERROR', category: error.category === 'decision_parse' ? 'decision_parse' : 'provider' }; }

export function buildCursorLaunch(profile, configValue = {}, dependencies = {}) {
	const platform = dependencies.platform ?? process.platform;
	const config = validateServiceConfig({ provider: 'cursor', cwd: dependencies.cwd ?? configValue.cwd ?? process.cwd(), ...configValue }, {
		platform,
		environment: dependencies.env ?? configValue.environment ?? process.env,
	});
	const checkedProfile = validateProfile(profile, config);
	const command = platform === 'win32' ? 'powershell.exe' : config.executable;
	const launcherArgs = platform === 'win32'
		? ['-NoProfile', '-NonInteractive', '-ExecutionPolicy', 'Bypass', '-File', config.executable]
		: [];
	return {
		command,
		args: [
			...launcherArgs,
			'--print', '--output-format', 'json', '--mode', 'ask',
			...(platform === 'win32' ? [] : ['--sandbox', 'enabled']),
			'--trust',
			'--model', cursorModelSpec(checkedProfile),
			...(dependencies.sessionId === null || dependencies.sessionId === undefined ? [] : ['--resume', requireSessionId(dependencies.sessionId)]),
		],
		options: {
			cwd: dependencies.cwd ?? config.cwd,
			env: createProviderChildEnvironment('cursor', dependencies.env ?? config.environment ?? process.env, config.bridgeSecretEnvironmentVariable),
			stdio: ['pipe', 'pipe', 'pipe'],
			windowsHide: true,
		},
	};
}

export function parseCursorModelList(output) {
	if (typeof output !== 'string') throw new TypeError('Cursor model output must be text');
	const models = new Map();
	for (const rawLine of output.replace(/\u001b\[[0-?]*[ -\/]*[@-~]/g, '').split(/\r?\n/)) {
		const line = rawLine.trim().replace(/^[*›>✓✔•-]+\s*/, '');
		const match = /^(?<id>[a-z0-9._-]+)\s+-\s+(?<display>\S.*)$/i.exec(line)
			?? /^(?<id>[a-z0-9._-]+)\s{2,}(?<display>\S.*)$/i.exec(line)
			?? /^(?<id>[a-z0-9._-]+)$/i.exec(line);
		if (match === null) continue;
		const id = normalizeCursorCatalogModel(match.groups.id);
		if (id === null || models.has(id)) continue;
		models.set(id, { id, model: id, displayName: readableModel(id) });
	}
	return [...models.values()];
}

async function discoverCursorCatalog({ config, dependencies }) {
	const launch = buildCursorCommand(config, dependencies.platform, ['models']);
	const output = await runCursorTextProcess(launch, {
		spawn: dependencies.spawn,
		terminate: dependencies.terminate,
		timeoutMs: config.catalogDiscoveryTimeoutMs,
		stdoutLimitBytes: config.stdoutLimitBytes,
		stderrLimitBytes: config.stderrLimitBytes,
	});
	return parseCursorModelList(output);
}

class CursorCatalog {
	#config;
	#dependencies;
	#snapshot = null;
	#refreshPromise = null;

	constructor(config, dependencies) {
		this.#config = config;
		this.#dependencies = dependencies;
		this.stale = config.catalogDiscovery;
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
		let discovered = null;
		let discoveryFailed = false;
		if (this.#config.catalogDiscovery) {
			try { discovered = await this.#dependencies.discoverCatalog({ config: this.#config, dependencies: this.#dependencies }); }
			catch { discoveryFailed = true; }
		}
		const discoveredById = new Map((Array.isArray(discovered) ? discovered : []).map((model) => [model.id, model]));
		const models = this.#config.models
			.filter((id) => !this.#config.catalogDiscovery || discoveredById.has(id))
			.map((id) => ({
				id,
				model: id,
				displayName: discoveredById.get(id)?.displayName ?? readableModel(id),
				reasoningEfforts: [...this.#config.modelReasoningEfforts[id]],
				serviceTiers: ['priority', 'fast'],
			}));
		if (models.length === 0 && this.#snapshot !== null) { this.stale = true; return this.#snapshot; }
		this.#snapshot = { provider: 'cursor', refreshedAtEpochMs: Date.now(), models };
		this.stale = discoveryFailed || models.length === 0;
		return this.#snapshot;
	}

	assertSupported(model, reasoningEffort, serviceTier = 'priority') {
		if (!this.#config.models.includes(model)) throw new AcpProtocolError('UNSUPPORTED_MODEL', `cursor model '${model}' is not configured`);
		if (!this.#config.modelReasoningEfforts[model]?.includes(reasoningEffort)) throw new AcpProtocolError('UNSUPPORTED_THINKING', `cursor model '${model}' does not support effort '${reasoningEffort}'`);
		if (!['priority', 'fast'].includes(serviceTier)) throw new AcpProtocolError('UNSUPPORTED_SERVICE_TIER', `cursor service tier '${serviceTier}' is not supported`);
	}
}

function runCursorProcess(prompt, launch, limits) {
	const base = runChild(launch, limits, prompt);
	return {
		cancel: base.cancel,
		promise: base.promise.then((stdout) => parseCursorResult(stdout)),
	};
}

function runCursorTextProcess(launch, limits) {
	return runChild(launch, {
		spawn: limits.spawn,
		terminate: limits.terminate,
		planningTimeoutMs: limits.timeoutMs,
		stdoutLimitBytes: limits.stdoutLimitBytes,
		stderrLimitBytes: limits.stderrLimitBytes,
	}, null).promise;
}

function runChild(launch, { spawn, terminate, planningTimeoutMs, stdoutLimitBytes, stderrLimitBytes }, stdinText) {
	let child;
	try { child = spawn(launch.command, launch.args, launch.options); }
	catch (error) {
		return { promise: Promise.reject(new AcpProtocolError('SPAWN_FAILED', `Could not start Cursor CLI: ${error.message}`, { cause: error })), cancel: async () => {} };
	}
	let cancellationError = null;
	let termination = null;
	let timer;
	let settle;
	const stdout = [];
	const stderr = [];
	let stdoutBytes = 0;
	let stderrBytes = 0;
	const promise = new Promise((resolve, reject) => {
		let settled = false;
		settle = (error, value) => {
			if (settled) return;
			settled = true;
			clearTimeout(timer);
			if (error === null) resolve(value); else reject(error);
		};
		const overflow = (stream, limit) => { void cancel(new AcpProtocolError('OUTPUT_LIMIT_EXCEEDED', `Cursor ${stream} exceeded ${limit} bytes`)); };
		child.stdout?.on('data', (chunkValue) => {
			const chunk = Buffer.from(chunkValue); stdoutBytes += chunk.length;
			if (stdoutBytes > stdoutLimitBytes) { overflow('stdout', stdoutLimitBytes); return; }
			stdout.push(chunk);
		});
		child.stderr?.on('data', (chunkValue) => {
			const chunk = Buffer.from(chunkValue); stderrBytes += chunk.length;
			if (stderrBytes > stderrLimitBytes) { overflow('stderr', stderrLimitBytes); return; }
			stderr.push(chunk);
		});
		child.once('error', (error) => settle(new AcpProtocolError('SPAWN_FAILED', `Could not start Cursor CLI: ${error.message}`, { cause: error })));
		child.once('close', (exitCode, signalCode) => {
			if (cancellationError !== null) { settle(cancellationError); return; }
			if (exitCode !== 0) {
				settle(new AcpProtocolError('PROVIDER_UNAVAILABLE', `Cursor CLI exited with code ${String(exitCode)} and signal ${String(signalCode)} [stderr=${excerpt(Buffer.concat(stderr).toString('utf8'))}]`));
				return;
			}
			settle(null, Buffer.concat(stdout).toString('utf8'));
		});
		timer = setTimeout(() => { void cancel(new AcpProtocolError('PLANNING_TIMEOUT', `Cursor planning timed out after ${planningTimeoutMs} ms`)); }, planningTimeoutMs);
		if (stdinText === null) child.stdin?.end?.();
		else child.stdin?.end?.(stdinText, 'utf8');
	});

	async function cancel(error) {
		if (cancellationError === null) cancellationError = error;
		if (termination === null) {
			termination = Promise.resolve(terminate(child)).catch((terminationError) => {
				settle(new AcpProtocolError('PROCESS_TERMINATION_FAILED', `Could not terminate Cursor CLI: ${terminationError.message}`, { cause: terminationError }));
			});
		}
		await termination;
		if (child.exitCode !== null || child.signalCode !== null) settle(cancellationError);
	}

	return { promise, cancel };
}

function parseCursorResult(output) {
	let document;
	try { document = JSON.parse(String(output).trim()); }
	catch (error) { throw new AcpProtocolError('INVALID_PROVIDER_OUTPUT', 'Cursor CLI returned invalid JSON', { cause: error }); }
	if (document?.type !== 'result' || document?.subtype !== 'success' || document?.is_error === true || typeof document?.result !== 'string') {
		throw new AcpProtocolError('PROVIDER_UNAVAILABLE', `Cursor CLI returned an unsuccessful result [output=${excerpt(output)}]`);
	}
	return {
		result: document.result,
		sessionId: requireSessionId(document.session_id),
		durationMs: duration(document.duration_ms, 'duration_ms'),
		apiDurationMs: duration(document.duration_api_ms, 'duration_api_ms'),
		tokens: cursorTokenUsage(document.usage),
	};
}

function cursorTokenUsage(value) {
	if (value === null || value === undefined) return null;
	if (typeof value !== 'object' || Array.isArray(value)) throw new AcpProtocolError('INVALID_PROVIDER_OUTPUT', 'Cursor usage must be an object');
	return {
		input: optionalNativeToken(value.inputTokens, 'usage.inputTokens'),
		output: optionalNativeToken(value.outputTokens, 'usage.outputTokens'),
		reasoning: null,
		cached: optionalNativeToken(value.cacheReadTokens, 'usage.cacheReadTokens'),
		cacheWrite: optionalNativeToken(value.cacheWriteTokens, 'usage.cacheWriteTokens'),
	};
}

function optionalNativeToken(value, field) {
	if (value === null || value === undefined) return null;
	if (!Number.isSafeInteger(value) || value < 0) throw new AcpProtocolError('INVALID_PROVIDER_OUTPUT', `Cursor ${field} must be a nonnegative safe integer`);
	return value;
}

function buildCursorCommand(config, platform, cliArgs) {
	return {
		command: platform === 'win32' ? 'powershell.exe' : config.executable,
		args: [
			...(platform === 'win32' ? ['-NoProfile', '-NonInteractive', '-ExecutionPolicy', 'Bypass', '-File', config.executable] : []),
			...cliArgs,
		],
		options: {
			cwd: config.cwd,
			env: createProviderChildEnvironment('cursor', config.environment ?? process.env, config.bridgeSecretEnvironmentVariable),
			stdio: ['ignore', 'pipe', 'pipe'],
			windowsHide: true,
		},
	};
}

function validateServiceConfig(config, { platform = process.platform, environment = process.env } = {}) {
	if (config === null || typeof config !== 'object' || Array.isArray(config)) throw new TypeError('Cursor service config must be an object');
	if ((config.provider ?? 'cursor') !== 'cursor') throw new TypeError('Cursor provider must be cursor');
	const models = requireStringArray(config.models ?? DEFAULT_MODELS, 'models');
	for (const model of models) if (!isCursorModel(model)) throw new TypeError(`Cursor model '${model}' must be a Composer or Grok model`);
	const configuredEfforts = config.modelReasoningEfforts ?? DEFAULT_REASONING;
	return {
		...config,
		provider: 'cursor',
		cwd: requireText(config.cwd, 'cwd'),
		executable: requireText(config.executable ?? defaultCursorExecutable(environment, platform), 'executable'),
		environment,
		models,
		modelReasoningEfforts: Object.fromEntries(models.map((model) => [model, requireStringArray(configuredEfforts[model], `modelReasoningEfforts.${model}`)])),
		catalogDiscovery: config.catalogDiscovery !== false,
		catalogDiscoveryTimeoutMs: positiveInteger(config.catalogDiscoveryTimeoutMs ?? DEFAULT_DISCOVERY_TIMEOUT_MS, 'catalogDiscoveryTimeoutMs'),
		planningTimeoutMs: positiveInteger(config.planningTimeoutMs ?? DEFAULT_PLANNING_TIMEOUT_MS, 'planningTimeoutMs'),
		stdoutLimitBytes: positiveInteger(config.stdoutLimitBytes ?? DEFAULT_STDOUT_LIMIT_BYTES, 'stdoutLimitBytes'),
		stderrLimitBytes: positiveInteger(config.stderrLimitBytes ?? DEFAULT_STDERR_LIMIT_BYTES, 'stderrLimitBytes'),
	};
}

function validateProfile(value, config) {
	const profile = profileIdentity(value);
	if (profile.provider !== 'cursor') throw new AcpProtocolError('PROVIDER_MISMATCH', `Expected cursor profile, received ${profile.provider}`);
	if (!config.models.includes(profile.model)) throw new AcpProtocolError('UNSUPPORTED_MODEL', `cursor model '${profile.model}' is not configured`);
	if (!config.modelReasoningEfforts[profile.model]?.includes(profile.reasoningEffort)) throw new AcpProtocolError('UNSUPPORTED_THINKING', `cursor model '${profile.model}' does not support effort '${profile.reasoningEffort}'`);
	if (!['priority', 'fast'].includes(profile.serviceTier)) throw new AcpProtocolError('UNSUPPORTED_SERVICE_TIER', `cursor service tier '${profile.serviceTier}' is not supported`);
	return profile;
}

function profileIdentity(value) {
	if (value === null || typeof value !== 'object' || Array.isArray(value)) throw new TypeError('agent profile must be an object');
	return {
		agentId: requireText(value.agentId, 'agentId'),
		provider: value.provider ?? 'codex',
		model: requireModel(value.model),
		reasoningEffort: requireToken(value.reasoningEffort, 'reasoningEffort'),
		serviceTier: requireToken(value.serviceTier ?? 'priority', 'serviceTier'),
	};
}

function defaultCursorExecutable(environment, platform) {
	if (platform !== 'win32') return 'agent';
	const localAppData = typeof environment?.LOCALAPPDATA === 'string' ? environment.LOCALAPPDATA.trim() : '';
	return localAppData === '' ? 'agent.ps1' : path.join(localAppData, 'cursor-agent', 'agent.ps1');
}

function cursorModelSpec(profile) {
	if (profile.model === 'composer-2.5') return `composer-2.5${profile.serviceTier === 'fast' ? '-fast' : ''}`;
	return `cursor-${profile.model}-${profile.reasoningEffort}${profile.serviceTier === 'fast' ? '-fast' : ''}`;
}

function normalizeCursorCatalogModel(value) {
	const id = String(value).toLowerCase();
	if (/^composer-2\.5(?:-fast)?$/.test(id)) return 'composer-2.5';
	if (/^grok-4\.[56]$/.test(id)) return id;
	return /^cursor-(grok-4\.[56])-(?:low|medium|high|xhigh)(?:-fast)?$/.exec(id)?.[1] ?? null;
}

function isCursorModel(value) { return /^(?:composer-2\.5|grok-4\.[56])$/i.test(value); }
function profilesMatch(left, right) { return ['agentId', 'provider', 'model', 'reasoningEffort', 'serviceTier'].every((key) => left[key] === right[key]); }
function readableModel(value) { return value.split('-').map((part) => part.length === 0 ? '' : part[0].toUpperCase() + part.slice(1)).filter(Boolean).join(' '); }
function excerpt(value) { return JSON.stringify(String(value).replace(/[\u0000-\u001f\u007f]/g, ' ').slice(0, 512)); }
function normalizeRecoverySummary(value) {
	if (value === null || value === undefined || value === '') return null;
	if (typeof value !== 'string' || value.length > 2_048) throw new TypeError('recoverySummary must be at most 2048 characters');
	return value;
}
function requireText(value, field) { if (typeof value !== 'string' || value.trim().length === 0) throw new TypeError(`${field} must be nonblank`); return value.trim(); }
function requireModel(value) { const model = requireText(value, 'model'); if (!/^[a-z0-9._-]+$/i.test(model)) throw new TypeError('model contains unsupported characters'); return model; }
function requireToken(value, field) { const token = requireText(value, field); if (!/^[a-z0-9_-]+$/i.test(token)) throw new TypeError(`${field} contains unsupported characters`); return token; }
function requireSessionId(value) { const id = requireText(value, 'session_id'); if (id.length > 256 || /[\u0000-\u001f\u007f]/.test(id)) throw new AcpProtocolError('INVALID_PROVIDER_OUTPUT', 'Cursor session_id is invalid'); return id; }
function requireStringArray(value, field) { if (!Array.isArray(value) || value.length === 0) throw new TypeError(`${field} must be a nonempty array`); return [...new Set(value.map((entry) => requireText(entry, field)))]; }
function positiveInteger(value, field) { if (!Number.isSafeInteger(value) || value <= 0) throw new TypeError(`${field} must be a positive safe integer`); return value; }
function duration(value, field) { if (!Number.isFinite(value) || value < 0) throw new AcpProtocolError('INVALID_PROVIDER_OUTPUT', `Cursor ${field} must be a nonnegative number`); return Math.round(value); }
