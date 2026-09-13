import { EventEmitter } from 'node:events';

import { PROVIDER_IDS, assertProviderServiceTier, normalizeProviderId } from './provider-identity.mjs';
const DEFAULT_SERVICE_TIER = 'priority';
const PROFILE_KEYS = Object.freeze(['agentId', 'provider', 'model', 'reasoningEffort', 'serviceTier']);
const DEFAULT_OPERATION_TIMEOUT_MS = 60_000;
const AUTOMATIC_RECOVERY_BOUNDARIES = new Set(['startup', 'catalog']);

export class ProviderProfileConflictError extends Error {
	constructor() {
		super('Agent profile is immutable for the active provider session');
		this.name = 'ProviderProfileConflictError';
		this.code = 'AGENT_PROFILE_CONFLICT';
	}
}

export class ProviderService extends EventEmitter {
	#services;
	#assignments = new Map();
	#acceptedAgents = new Map();
	#agentMutationTokens = new Map();
	#creating = new Map();
	#replacing = new Map();
	#removing = new Map();
	#repairing = new Map();
	#reconcilingAgents = new Map();
	#starting = new Map();
	#startedProviders = new Set();
	#inFlight = new Set();
	#recovery = new Map();
	#recoveryTimers = new Map();
	#outcomeAttempts = new Map();
	#outcomeAttemptSequence = 0;
	#failureSequence = 0;
	#failureBoundaries = new Map();
	#reconciliationGeneration = 0;
	#activeReconciliations = new Map();
	#turnRecorder;
	#operationTimeoutMs;
	#scheduleTimeout;
	#cancelTimeout;
	#now;
	#stopped = false;
	#lifecycleGeneration = 0;
	#stopPromise = null;

	constructor(services, { turnRecorder = null, operationTimeoutMs = DEFAULT_OPERATION_TIMEOUT_MS, scheduleTimeout = setTimeout, cancelTimeout = clearTimeout, now = Date.now } = {}) {
		super();
		if (services === null || typeof services !== 'object') throw new TypeError('provider services are required');
		if (turnRecorder !== null && (typeof turnRecorder !== 'object' || typeof turnRecorder.record !== 'function')) throw new TypeError('turnRecorder must provide record or be null');
		if (!Number.isSafeInteger(operationTimeoutMs) || operationTimeoutMs <= 0) throw new TypeError('operationTimeoutMs must be a positive safe integer');
		if (typeof scheduleTimeout !== 'function' || typeof cancelTimeout !== 'function') throw new TypeError('provider timeout dependencies must be functions');
		if (typeof now !== 'function') throw new TypeError('provider now dependency must be a function');
		this.#turnRecorder = turnRecorder;
		this.#operationTimeoutMs = operationTimeoutMs;
		this.#scheduleTimeout = scheduleTimeout;
		this.#cancelTimeout = cancelTimeout;
		this.#now = now;
		this.#services = new Map(PROVIDER_IDS.filter((provider) => services[provider] !== undefined).map((provider) => {
			const service = services[provider];
			if (service === null || service === undefined) throw new TypeError(`${provider} service is required`);
			return [provider, service];
		}));
		for (const provider of ['codex', 'gemini', 'kimi']) if (!this.#services.has(provider)) throw new TypeError(`${provider} service is required`);
		this.catalog = new CombinedProviderCatalog(this.#services, {
			execute: (provider, operation) => this.#execute(provider, operation, 'catalog', { recordSuccess: false }),
			recordOutcome: (provider, source, recovery) => this.#recordCatalogOutcome(provider, source, recovery),
			recovery: () => this.recoverySnapshot(),
			availability: () => this.availabilitySnapshot(),
			now: this.#now,
		});
	}

	async start(providers = []) {
		const selected = normalizeProviderSelection(providers, this.#services, { defaultToAll: false });
		return Promise.allSettled(selected.map((provider) => this.#startProvider(provider)));
	}
	stop() {
		if (this.#stopPromise !== null) return this.#stopPromise;
		this.#stopped = true;
		this.#lifecycleGeneration += 1;
		this.#reconciliationGeneration += 1;
		for (const timer of this.#recoveryTimers.values()) this.#cancelTimeout(timer.handle);
		this.#recoveryTimers.clear();
		for (const attempt of this.#outcomeAttempts.values()) attempt.acceptOutcome = false;
		this.#outcomeAttempts.clear();
		for (const { controller } of this.#activeReconciliations.values()) controller.abort(providerError('STALE_RECONCILIATION', 'Provider reconciliation was stopped'));
		this.#starting.clear();
		this.#stopPromise = this.#stopOnce();
		return this.#stopPromise;
	}

	async #stopOnce() {
		const operations = [...this.#inFlight];
		const backendStops = [...this.#services.entries()].map(([provider, service]) => withTimeout(
			Promise.resolve().then(() => service.stop()),
			this.#operationTimeoutMs,
			this.#scheduleTimeout,
			this.#cancelTimeout,
			provider,
		));
		await Promise.allSettled([...backendStops, ...operations]);
		await Promise.allSettled([...this.#services.entries()].map(([provider, service]) => withTimeout(
			Promise.resolve().then(() => service.stop()),
			this.#operationTimeoutMs,
			this.#scheduleTimeout,
			this.#cancelTimeout,
			provider,
		)));
		this.#assignments.clear();
		this.#acceptedAgents.clear();
		this.#agentMutationTokens.clear();
		this.#creating.clear();
		this.#replacing.clear();
		this.#removing.clear();
		this.#repairing.clear();
		this.#reconcilingAgents.clear();
		this.#starting.clear();
		this.#startedProviders.clear();
		this.#inFlight.clear();
		this.#activeReconciliations.clear();
		this.#failureBoundaries.clear();
		this.#recovery.clear();
	}
	async bootstrapCatalog(recordsOrProviders = undefined) {
		const providers = normalizeProviderSelection(recordsOrProviders ?? [], this.#services, { defaultToAll: recordsOrProviders === undefined });
		const profileRecords = Array.isArray(recordsOrProviders)
			&& recordsOrProviders.every((value) => value !== null && typeof value === 'object' && !Array.isArray(value))
			? recordsOrProviders
			: undefined;
		return this.catalog.refresh({ providers, profileRecords });
	}

	recoverySnapshot() {
		return [...this.#services.keys()].map((provider) => {
			const record = this.#recovery.get(provider);
			return record === undefined
				? { provider, state: 'idle', fallbackMode: null, boundary: null, failureCode: null, consecutiveFailureCount: 0, totalFailureCount: 0, boundaryFailureCounts: {}, latestNonAutomaticFailure: null, nextProbeAtEpochMs: null, generation: 0, lastRecoveryAtEpochMs: null }
				: { provider, ...record, boundaryFailureCounts: { ...record.boundaryFailureCounts }, latestNonAutomaticFailure: record.latestNonAutomaticFailure === null ? null : { ...record.latestNonAutomaticFailure } };
		});
	}

	async createAgent(profileValue, options) {
		const lifecycleGeneration = this.#lifecycleGeneration;
		this.#assertActive(lifecycleGeneration);
		const profile = freezeProfile(profileValue);
		const repairing = this.#repairing.get(profile.agentId);
		if (repairing !== undefined) {
			await repairing.promise;
			this.#assertActive(lifecycleGeneration);
			return this.createAgent(profile, options);
		}
		const reconciling = this.#reconcilingAgents.get(profile.agentId);
		if (reconciling !== undefined) {
			await reconciling.promise;
			this.#assertActive(lifecycleGeneration);
			return this.createAgent(profile, options);
		}
		const removing = this.#removing.get(profile.agentId);
		if (removing !== undefined) {
			try { await removing.promise; } catch { /* a new create is ordered after terminal cleanup even if cleanup failed */ }
			this.#assertActive(lifecycleGeneration);
			return this.createAgent(profile, options);
		}
		const replacing = this.#replacing.get(profile.agentId);
		if (replacing !== undefined) {
			assertSameProfile(replacing.profile, profile);
			return replacing.promise;
		}
		const creating = this.#creating.get(profile.agentId);
		if (creating !== undefined) {
			assertSameProfile(creating.profile, profile);
			return creating.promise;
		}
		const existing = this.#assignments.get(profile.agentId);
		if (existing !== undefined) assertSameProfile(existing, profile);
		const service = this.#services.get(profile.provider);
		if (service === undefined) throw new TypeError(`${profile.provider} service is unavailable`);
		const mutation = this.#beginAgentMutation(profile.agentId, lifecycleGeneration);
		const promise = (async () => {
			const agent = await this.#execute(profile.provider, () => service.createAgent(existing ?? profile, this.#creationOptions(options)), 'create', {
				recordSuccess: false,
				outcomeIdentity: profileIdentity(profile),
				acceptResult: () => this.#acceptsAgentMutation(mutation),
				onStaleResult: (result) => this.#disposeStaleAgent(profile.provider, profile.agentId, result, mutation),
			});
			this.#assertActive(lifecycleGeneration);
			if (!this.#acceptsAgentMutation(mutation)) {
				await this.#disposeStaleAgent(profile.provider, profile.agentId, agent, mutation);
				throw providerError('STALE_PROVIDER_OUTCOME', 'Agent creation was superseded by a newer session mutation');
			}
			this.#assignments.set(profile.agentId, existing ?? profile);
			this.#acceptedAgents.set(profile.agentId, agent);
			this.#recordSessionMutationLive(profile.provider);
			return agent;
		})();
		const entry = { profile, mutation, promise };
		this.#creating.set(profile.agentId, entry);
		try {
			return await promise;
		} finally {
			if (this.#creating.get(profile.agentId) === entry) this.#creating.delete(profile.agentId);
			this.#pruneAgentMutation(mutation);
		}
	}

	async replaceAgent(profileValue, options = {}) {
		const lifecycleGeneration = this.#lifecycleGeneration;
		this.#assertActive(lifecycleGeneration);
		const profile = freezeProfile(profileValue);
		const repairing = this.#repairing.get(profile.agentId);
		if (repairing !== undefined) {
			await repairing.promise;
			this.#assertActive(lifecycleGeneration);
			return this.replaceAgent(profile, options);
		}
		const reconciling = this.#reconcilingAgents.get(profile.agentId);
		if (reconciling !== undefined) {
			await reconciling.promise;
			this.#assertActive(lifecycleGeneration);
			return this.replaceAgent(profile, options);
		}
		const removing = this.#removing.get(profile.agentId);
		if (removing !== undefined) {
			try { await removing.promise; } catch { /* replacement is ordered after terminal cleanup */ }
			this.#assertActive(lifecycleGeneration);
			return this.replaceAgent(profile, options);
		}
		const replacing = this.#replacing.get(profile.agentId);
		if (replacing !== undefined) {
			assertSameProfile(replacing.profile, profile);
			return replacing.promise;
		}
		const creating = this.#creating.get(profile.agentId);
		if (creating !== undefined) assertSameProfile(creating.profile, profile);
		const service = this.#services.get(profile.provider);
		if (service === undefined) throw new TypeError(`${profile.provider} service is unavailable`);
		let mutation = null;
		const promise = (async () => {
			if (creating !== undefined) {
				try { await creating.promise; } catch { /* replacement recreates a failed attempt */ }
				this.#assertActive(lifecycleGeneration);
			}
			mutation = this.#beginAgentMutation(profile.agentId, lifecycleGeneration);
			if (!this.#acceptsAgentMutation(mutation)) throw providerError('STALE_PROVIDER_OUTCOME', 'Agent replacement was superseded by a newer session mutation');
			const assigned = this.#assignments.get(profile.agentId);
			if (assigned !== undefined) assertSameProfile(assigned, profile);
			const agent = await this.#execute(profile.provider, async () => {
				const agent = typeof service.replaceAgent === 'function'
					? await service.replaceAgent(profile, this.#creationOptions(options))
					: (await service.removeAgent(profile.agentId), await service.createAgent(profile, this.#creationOptions(options)));
				return agent;
			}, 'replace', {
				recordSuccess: false,
				outcomeIdentity: profileIdentity(profile),
				acceptResult: () => this.#acceptsAgentMutation(mutation),
				onStaleResult: (result) => this.#disposeStaleAgent(profile.provider, profile.agentId, result, mutation),
			});
			this.#assertActive(lifecycleGeneration);
			if (!this.#acceptsAgentMutation(mutation)) {
				await this.#disposeStaleAgent(profile.provider, profile.agentId, agent, mutation);
				throw providerError('STALE_PROVIDER_OUTCOME', 'Agent replacement was superseded by a newer session mutation');
			}
			this.#assignments.set(profile.agentId, profile);
			this.#acceptedAgents.set(profile.agentId, agent);
			this.#recordSessionMutationLive(profile.provider);
			return agent;
		})();
		const entry = { profile, promise, get mutation() { return mutation; } };
		this.#replacing.set(profile.agentId, entry);
		try { return await promise; }
		finally {
			if (this.#replacing.get(profile.agentId) === entry) this.#replacing.delete(profile.agentId);
			this.#pruneAgentMutation(mutation);
		}
	}

	getAgent(agentId) {
		if (this.#stopped) return null;
		return this.#acceptedAgents.get(agentId) ?? null;
	}

	getExecutionSettings(agentId) {
		const settings = this.getAgent(agentId)?.executionSettings;
		return settings === undefined ? null : structuredClone(settings);
	}

	availabilitySnapshot() {
		return [...this.#services].map(([provider, service]) => ({ provider, ...(service.availability ?? { playable: true, reasonCode: null, reason: null }) }));
	}

	async removeAgent(agentId) {
		const repairing = this.#repairing.get(agentId);
		if (repairing !== undefined) {
			await repairing.promise;
			return this.removeAgent(agentId);
		}
		const reconciling = this.#reconcilingAgents.get(agentId);
		if (reconciling !== undefined) {
			await reconciling.promise;
			return this.removeAgent(agentId);
		}
		const existingRemoval = this.#removing.get(agentId);
		if (existingRemoval !== undefined) return existingRemoval.promise;
		const lifecycleGeneration = this.#lifecycleGeneration;
		this.#assertActive(lifecycleGeneration);
		const replacing = this.#replacing.get(agentId);
		const creating = this.#creating.get(agentId);
		const hadOwnedSession = replacing !== undefined || creating !== undefined || this.#assignments.has(agentId) || this.#acceptedAgents.has(agentId);
		let mutation = null;
		const promise = (async () => {
			await Promise.allSettled([replacing?.promise, creating?.promise].filter(Boolean));
			this.#assertActive(lifecycleGeneration);
			mutation = this.#beginAgentMutation(agentId, lifecycleGeneration);
			if (!this.#acceptsAgentMutation(mutation)) throw providerError('STALE_PROVIDER_OUTCOME', 'Agent removal was superseded by a newer session mutation');
			const assigned = this.#assignments.get(agentId);
			this.#assignments.delete(agentId);
			this.#acceptedAgents.delete(agentId);
			let removed;
			if (assigned !== undefined) {
				removed = await this.#execute(assigned.provider, () => this.#services.get(assigned.provider).removeAgent(agentId), 'remove', {
					recordSuccess: false,
					outcomeIdentity: String(agentId),
					acceptResult: () => this.#acceptsAgentMutation(mutation),
					onStaleResult: () => this.#repairAfterStaleMutation(assigned.provider, agentId, mutation),
					onStaleError: () => this.#repairAfterStaleMutation(assigned.provider, agentId, mutation),
				});
			} else {
				const results = await Promise.all([...this.#startedProviders].map((provider) => this.#execute(provider, () => this.#services.get(provider).removeAgent(agentId), 'remove', {
					recordSuccess: false,
					outcomeIdentity: String(agentId),
					acceptResult: () => this.#acceptsAgentMutation(mutation),
					onStaleResult: () => this.#repairAfterStaleMutation(provider, agentId, mutation),
					onStaleError: () => this.#repairAfterStaleMutation(provider, agentId, mutation),
				})));
				removed = results.some(Boolean);
			}
			if (!this.#acceptsAgentMutation(mutation)) throw providerError('STALE_PROVIDER_OUTCOME', 'Agent removal was superseded by a newer session mutation');
			if (assigned !== undefined) this.#recordSessionMutationLive(assigned.provider);
			else for (const provider of this.#startedProviders) this.#recordSessionMutationLive(provider);
			return Boolean(removed || hadOwnedSession);
		})();
		const entry = { promise, get mutation() { return mutation; } };
		this.#removing.set(agentId, entry);
		try { return await promise; }
		finally {
			if (this.#removing.get(agentId) === entry) this.#removing.delete(agentId);
			this.#pruneAgentMutation(mutation);
		}
	}

	async reconcile(records) {
		const lifecycleGeneration = this.#lifecycleGeneration;
		this.#assertActive(lifecycleGeneration);
		if (!Array.isArray(records)) throw new TypeError('provider reconciliation records must be an array');
		const reconciliationGeneration = ++this.#reconciliationGeneration;
		for (const { controller } of this.#activeReconciliations.values()) controller.abort(providerError('STALE_RECONCILIATION', 'Provider reconciliation was superseded'));
		const controller = new AbortController();
		this.#activeReconciliations.set(reconciliationGeneration, { controller });
		let agentFence = null;
		let mutationTokens = [];
		try {
		const availableProviders = [...this.#services.keys()];
		const groups = new Map(availableProviders.map((provider) => [provider, []]));
		for (const record of records) {
			const provider = normalizeProvider(record?.provider);
			if (!groups.has(provider)) throw new TypeError(`${provider} service is unavailable`);
			groups.get(provider).push({ ...record, provider });
		}
		const affectedAgentIds = new Set([
			...this.#assignments.keys(),
			...this.#creating.keys(),
			...this.#replacing.keys(),
			...this.#removing.keys(),
			...this.#repairing.keys(),
			...this.#agentMutationTokens.keys(),
			...records.map((record) => record?.agentId).filter((agentId) => agentId !== undefined),
		]);
		const blockers = new Set();
		for (const agentId of affectedAgentIds) {
			for (const entries of [this.#creating, this.#replacing, this.#removing, this.#repairing]) {
				const entry = entries.get(agentId);
				if (entry !== undefined) blockers.add(entry.promise);
			}
		}
		let releaseAgentFence;
		agentFence = { promise: new Promise((resolve) => { releaseAgentFence = resolve; }), release: releaseAgentFence };
		for (const agentId of affectedAgentIds) this.#reconcilingAgents.set(agentId, agentFence);
		mutationTokens = [...affectedAgentIds].map((agentId) => this.#beginAgentMutation(agentId, lifecycleGeneration));
		const mutationByAgentId = new Map(mutationTokens.map((token) => [token.agentId, token]));
		await Promise.allSettled([...blockers]);
		this.#assertReconciliationCurrent(reconciliationGeneration, lifecycleGeneration);
		if (mutationTokens.some((token) => !this.#acceptsAgentMutation(token))) throw providerError('STALE_RECONCILIATION', 'Provider reconciliation session mutations were superseded');
		const assignedProviders = new Set([...this.#assignments.values()].map(({ provider }) => provider));
		const selectedProviders = availableProviders.filter((provider) => groups.get(provider).length > 0 || assignedProviders.has(provider));
		const affectedByProvider = new Map(availableProviders.map((provider) => [provider, new Set()]));
		for (const agentId of affectedAgentIds) {
			const profile = this.#assignments.get(agentId) ?? this.#creating.get(agentId)?.profile ?? this.#replacing.get(agentId)?.profile
				?? records.find((record) => record?.agentId === agentId);
			if (profile?.provider !== undefined && affectedByProvider.has(profile.provider)) affectedByProvider.get(profile.provider).add(agentId);
			else for (const provider of availableProviders) affectedByProvider.get(provider).add(agentId);
		}
		const settled = await Promise.all(selectedProviders.map(async (provider) => {
			try {
				return { provider, result: await this.#execute(provider, () => this.#services.get(provider).reconcile(groups.get(provider), {
					signal: controller.signal,
					generation: reconciliationGeneration,
				}), 'reconcile', {
					recordSuccess: false,
					outcomeIdentity: 'provider-roster',
					onStaleResult: () => Promise.allSettled([...affectedByProvider.get(provider)].map((agentId) => this.#repairAfterStaleMutation(provider, agentId, mutationByAgentId.get(agentId)))),
					onStaleError: () => Promise.allSettled([...affectedByProvider.get(provider)].map((agentId) => this.#repairAfterStaleMutation(provider, agentId, mutationByAgentId.get(agentId)))),
				}) };
			} catch (error) {
				return { provider, error };
			}
		}));
		this.#assertReconciliationCurrent(reconciliationGeneration, lifecycleGeneration);
		const previousAssignments = this.#assignments;
		const previousAgents = this.#acceptedAgents;
		const nextAssignments = new Map();
		const nextAgents = new Map();
		for (const { provider, result, error } of settled) {
			if (error !== undefined) {
				const desiredAgentIds = new Set(groups.get(provider).map(({ agentId }) => agentId));
				for (const profileValue of groups.get(provider)) {
					const profile = freezeProfile(profileValue);
					const previous = previousAssignments.get(profile.agentId);
					if (previous !== undefined && profilesMatch(previous, profile)) {
						nextAssignments.set(profile.agentId, previous);
						const agent = previousAgents.get(profile.agentId);
						if (agent !== undefined) nextAgents.set(profile.agentId, agent);
					}
				}
				for (const [agentId, previous] of previousAssignments) {
					if (previous.provider === provider && !desiredAgentIds.has(agentId)) nextAssignments.set(agentId, previous);
				}
				continue;
			}
			for (const profile of result.valid ?? []) {
				const normalized = freezeProfile(profile);
				const existing = previousAssignments.get(normalized.agentId) ?? nextAssignments.get(normalized.agentId);
				if (existing !== undefined) {
					assertSameProfile(existing, normalized);
					nextAssignments.set(normalized.agentId, existing);
					const agent = this.#services.get(provider).getAgent?.(normalized.agentId);
					if (agent !== null && agent !== undefined) nextAgents.set(normalized.agentId, agent);
					continue;
				}
				nextAssignments.set(normalized.agentId, normalized);
				const agent = this.#services.get(provider).getAgent?.(normalized.agentId);
				if (agent !== null && agent !== undefined) nextAgents.set(normalized.agentId, agent);
			}
		}
		const failures = settled.filter(({ error }) => error !== undefined);
		const catalog = await this.catalog.refresh({ providers: selectedProviders, fallbackProviders: failures.map(({ provider }) => provider) });
		this.#assertReconciliationCurrent(reconciliationGeneration, lifecycleGeneration);
		if (mutationTokens.some((token) => !this.#acceptsAgentMutation(token))) throw providerError('STALE_RECONCILIATION', 'Provider reconciliation session mutations were superseded');
		this.#assignments = nextAssignments;
		this.#acceptedAgents = nextAgents;
		for (const { provider, result } of settled) if (result !== undefined) this.#recordLive(provider, 'reconcile');
		return {
			valid: settled.flatMap(({ result }) => result?.valid ?? []),
			invalid: [
				...settled.flatMap(({ result }) => result?.invalid ?? []),
				...failures.flatMap(({ provider, error }) => groups.get(provider).map((profile) => providerFailure(profile, error))),
			],
			removed: settled.flatMap(({ result }) => result?.removed ?? []),
			catalog,
			recovery: this.recoverySnapshot(),
		};
		} finally {
			agentFence?.release();
			if (agentFence !== null) for (const [agentId, entry] of this.#reconcilingAgents) if (entry === agentFence) this.#reconcilingAgents.delete(agentId);
			for (const token of mutationTokens) this.#pruneAgentMutation(token);
			this.#activeReconciliations.delete(reconciliationGeneration);
		}
	}

	async #execute(provider, operation, boundary, { recordSuccess = true, outcomeIdentity = boundary, acceptResult = () => true, onStaleResult = null, onStaleError = null } = {}) {
		if (this.#stopped) throw providerError('PROVIDER_STOPPED', 'Provider service is stopped');
		const lifecycleGeneration = this.#lifecycleGeneration;
		const startup = this.#ensureStarted(provider, lifecycleGeneration);
		if (startup !== null) await startup.promise;
		this.#assertActive(lifecycleGeneration);
		const outcomeAttempt = this.#beginOutcomeAttempt(provider, boundary, lifecycleGeneration, outcomeIdentity);
		const task = Promise.resolve().then(() => {
			this.#assertActive(lifecycleGeneration);
			return operation();
		});
		const observed = task.then(async (result) => {
			this.#assertActive(lifecycleGeneration);
			if (!this.#acceptsOutcome(outcomeAttempt) || !acceptResult()) {
				try { await onStaleResult?.(result); } catch { /* stale cleanup is best effort and cannot revive the outcome */ }
				throw providerError('STALE_PROVIDER_OUTCOME', 'Provider outcome attempt was superseded');
			}
			if (recordSuccess) this.#recordLive(provider, boundary);
			return result;
		}, async (error) => {
			const accepts = this.#acceptsOutcome(outcomeAttempt) && acceptResult();
			if (!accepts) {
				try { await onStaleError?.(error); } catch { /* stale repair cannot change the original outcome */ }
			} else if (!isLifecycleFenceError(error)) this.#recordDegraded(provider, error, boundary);
			throw error;
		});
		const bounded = withTimeout(observed, this.#operationTimeoutMs, this.#scheduleTimeout, this.#cancelTimeout, provider, {
			onTimeout: () => { outcomeAttempt.acceptOutcome = false; },
		});
		this.#inFlight.add(bounded);
		try {
			return await bounded;
		} catch (error) {
			if (error?.code === 'PROVIDER_TIMEOUT' && this.#ownsOutcomeAttempt(outcomeAttempt) && acceptResult()) {
				this.#recordDegraded(provider, error, boundary);
			}
			throw error;
		} finally {
			this.#inFlight.delete(bounded);
			this.#finishOutcomeAttempt(outcomeAttempt);
		}
	}

	#beginOutcomeAttempt(provider, boundary, lifecycleGeneration, outcomeIdentity) {
		const key = `${provider}:${boundary}:${String(outcomeIdentity)}`;
		const attempt = { key, provider, boundary, lifecycleGeneration, generation: ++this.#outcomeAttemptSequence, acceptOutcome: true };
		if (key !== null) {
			const previous = this.#outcomeAttempts.get(key);
			if (previous !== undefined) previous.acceptOutcome = false;
			this.#outcomeAttempts.set(key, attempt);
		}
		return attempt;
	}

	#ownsOutcomeAttempt(attempt) {
		return !this.#stopped && attempt.lifecycleGeneration === this.#lifecycleGeneration
			&& this.#outcomeAttempts.get(attempt.key) === attempt;
	}

	#acceptsOutcome(attempt) {
		return attempt.acceptOutcome && this.#ownsOutcomeAttempt(attempt);
	}

	#finishOutcomeAttempt(attempt) {
		attempt.acceptOutcome = false;
		if (this.#outcomeAttempts.get(attempt.key) === attempt) this.#outcomeAttempts.delete(attempt.key);
	}

	#beginAgentMutation(agentId, lifecycleGeneration) {
		const previous = this.#agentMutationTokens.get(agentId);
		const token = {
			agentId,
			generation: (previous?.generation ?? 0) + 1,
			lifecycleGeneration,
		};
		this.#agentMutationTokens.set(agentId, token);
		return token;
	}

	#acceptsAgentMutation(token) {
		return !this.#stopped && token.lifecycleGeneration === this.#lifecycleGeneration
			&& this.#agentMutationTokens.get(token.agentId) === token;
	}

	#pruneAgentMutation(token) {
		if (token === null || token === undefined || this.#agentMutationTokens.get(token.agentId) !== token) return;
		const agentId = token.agentId;
		if (this.#assignments.has(agentId) || this.#acceptedAgents.has(agentId) || this.#reconcilingAgents.has(agentId)) return;
		if ([this.#creating, this.#replacing, this.#removing, this.#repairing].some((owners) => owners.has(agentId))) return;
		this.#agentMutationTokens.delete(agentId);
	}

	async #disposeStaleAgent(provider, agentId, agent, staleMutation) {
		await this.#discardPhysicalAgent(provider, agentId, agent);
		await this.#repairAfterStaleMutation(provider, agentId, staleMutation);
	}

	async #repairAfterStaleMutation(provider, agentId, staleMutation) {
		const owner = this.#newerAgentOwnerPromise(agentId, staleMutation);
		if (owner !== null) {
			void Promise.resolve(owner).catch(() => {}).then(() => this.#repairPhysicalInvariant(provider, agentId)).catch(() => {});
			return;
		}
		await this.#repairPhysicalInvariant(provider, agentId);
	}

	#newerAgentOwnerPromise(agentId, staleMutation) {
		const reconciliation = this.#reconcilingAgents.get(agentId);
		if (reconciliation !== undefined) return reconciliation.promise;
		const repair = this.#repairing.get(agentId);
		if (repair !== undefined) return repair.promise;
		for (const owners of [this.#creating, this.#replacing, this.#removing]) {
			const owner = owners.get(agentId);
			if (owner !== undefined && owner.mutation !== staleMutation) return owner.promise;
		}
		return null;
	}

	async #repairPhysicalInvariant(provider, agentId) {
		if (this.#stopped) return null;
		const existing = this.#repairing.get(agentId);
		if (existing !== undefined) return existing.promise;
		const lifecycleGeneration = this.#lifecycleGeneration;
		const mutation = this.#beginAgentMutation(agentId, lifecycleGeneration);
		const promise = this.#repairPhysicalInvariantOnce(provider, agentId, mutation, lifecycleGeneration)
			.catch(() => null);
		const entry = { mutation, promise };
		this.#repairing.set(agentId, entry);
		try { return await promise; }
		finally {
			if (this.#repairing.get(agentId) === entry) this.#repairing.delete(agentId);
			this.#pruneAgentMutation(mutation);
		}
	}

	async #repairPhysicalInvariantOnce(provider, agentId, mutation, lifecycleGeneration) {
		const service = this.#services.get(provider);
		if (service === undefined) return null;
		this.#assertActive(lifecycleGeneration);
		const profile = this.#assignments.get(agentId);
		const accepted = this.#acceptedAgents.get(agentId);
		let backend = service.getAgent?.(agentId) ?? null;
		if (profile === undefined || accepted === undefined || profile.provider !== provider) {
			if (profile === undefined || accepted === undefined) {
				this.#assignments.delete(agentId);
				this.#acceptedAgents.delete(agentId);
			}
			if (backend !== null) await this.#discardPhysicalAgent(provider, agentId, backend);
			return null;
		}
		if (backend === accepted) return accepted;
		try {
			const repaired = await this.#execute(provider, () => service.replaceAgent(profile, this.#creationOptions({
				...(Number.isSafeInteger(backend?.sessionGeneration) ? { expectedSessionGeneration: backend.sessionGeneration } : {}),
			})), 'replace', {
				recordSuccess: false,
				outcomeIdentity: profileIdentity(profile),
				acceptResult: () => this.#acceptsAgentMutation(mutation),
				onStaleResult: (result) => this.#discardPhysicalAgent(provider, agentId, result),
			});
			this.#assertActive(lifecycleGeneration);
			if (!this.#acceptsAgentMutation(mutation)) {
				await this.#discardPhysicalAgent(provider, agentId, repaired);
				return null;
			}
			backend = service.getAgent?.(agentId) ?? repaired;
			if (backend !== repaired) {
				await this.#discardPhysicalAgent(provider, agentId, repaired);
				throw providerError('PROVIDER_SESSION_DIVERGED', 'Provider repair did not install its produced session');
			}
			this.#acceptedAgents.set(agentId, repaired);
			this.#assignments.set(agentId, profile);
			if (accepted !== repaired) await this.#disposeDetachedAgent(provider, accepted);
			this.#recordSessionMutationLive(provider);
			return repaired;
		} catch {
			if (!this.#acceptsAgentMutation(mutation)) return null;
			backend = service.getAgent?.(agentId) ?? null;
			if (backend === accepted) return accepted;
			this.#assignments.delete(agentId);
			this.#acceptedAgents.delete(agentId);
			if (backend !== null) await this.#discardPhysicalAgent(provider, agentId, backend);
			await this.#disposeDetachedAgent(provider, accepted);
			return null;
		}
	}

	async #discardPhysicalAgent(provider, agentId, agent) {
		if (agent === null || typeof agent !== 'object') return;
		const service = this.#services.get(provider);
		const cleanup = Promise.resolve().then(async () => {
			if (service?.getAgent?.(agentId) === agent && typeof service.removeAgent === 'function') await service.removeAgent(agentId);
			else await agent.dispose?.();
		});
		try { await withTimeout(cleanup, this.#operationTimeoutMs, this.#scheduleTimeout, this.#cancelTimeout, provider); }
		catch { /* exact stale cleanup is bounded and cannot regain authority */ }
	}

	async #disposeDetachedAgent(provider, agent) {
		if (agent === null || typeof agent !== 'object') return;
		const cleanup = Promise.resolve().then(() => agent.dispose?.());
		try { await withTimeout(cleanup, this.#operationTimeoutMs, this.#scheduleTimeout, this.#cancelTimeout, provider); }
		catch { /* detached accepted sessions cannot block repair convergence */ }
	}

	#recordSessionMutationLive(provider) {
		if (provider === undefined) return;
		for (const boundary of ['create', 'replace', 'remove', 'reconcile']) this.#recordLive(provider, boundary);
	}

	async #startProvider(provider) {
		if (this.#stopped) throw providerError('PROVIDER_STOPPED', 'Provider service is stopped');
		const lifecycleGeneration = this.#lifecycleGeneration;
		const starting = this.#ensureStarted(provider, lifecycleGeneration);
		if (starting !== null) await starting.promise;
	}

	#ensureStarted(provider, lifecycleGeneration) {
		if (this.#startedProviders.has(provider)) return null;
		const pending = this.#starting.get(provider);
		if (pending !== undefined) return pending;
		const service = this.#services.get(provider);
		if (service === undefined) throw new TypeError(`${provider} service is unavailable`);
		const outcomeAttempt = this.#beginOutcomeAttempt(provider, 'startup', lifecycleGeneration, 'physical-start');
		const starting = { promise: null };
		const task = Promise.resolve()
			.then(() => this.#assertActive(lifecycleGeneration))
			.then(() => service.start())
			.then(() => {
				this.#assertActive(lifecycleGeneration);
				if (this.#starting.get(provider) !== starting || !this.#acceptsOutcome(outcomeAttempt)) throw providerError('STALE_PROVIDER_START', 'Provider startup generation was superseded');
				this.#startedProviders.add(provider);
				this.#recordLive(provider, 'startup');
			}, (error) => {
				if (!isLifecycleFenceError(error) && this.#acceptsOutcome(outcomeAttempt)) this.#recordDegraded(provider, error, 'startup');
				throw error;
			});
		const bounded = withTimeout(task, this.#operationTimeoutMs, this.#scheduleTimeout, this.#cancelTimeout, provider, {
			onTimeout: () => { outcomeAttempt.acceptOutcome = false; },
		});
		this.#inFlight.add(bounded);
		starting.promise = bounded.catch((error) => {
			if (error?.code === 'PROVIDER_TIMEOUT' && this.#ownsOutcomeAttempt(outcomeAttempt)) this.#recordDegraded(provider, error, 'startup');
			throw error;
		}).finally(() => {
			this.#inFlight.delete(bounded);
			this.#finishOutcomeAttempt(outcomeAttempt);
			if (this.#starting.get(provider) === starting) this.#starting.delete(provider);
		});
		this.#starting.set(provider, starting);
		return starting;
	}

	#creationOptions(options) {
		return this.#turnRecorder === null ? options : { ...options, turnRecorder: this.#turnRecorder };
	}

	#assertActive(lifecycleGeneration) {
		if (this.#stopped || lifecycleGeneration !== this.#lifecycleGeneration) throw providerError('PROVIDER_STOPPED', 'Provider service is stopped');
	}

	#assertReconciliationCurrent(reconciliationGeneration, lifecycleGeneration) {
		this.#assertActive(lifecycleGeneration);
		if (reconciliationGeneration !== this.#reconciliationGeneration) throw providerError('STALE_RECONCILIATION', 'Provider reconciliation was superseded');
	}

	#recordCatalogOutcome(provider, source, recovery) {
		if (source === 'live') {
			this.#recordLive(provider, 'catalog');
			return;
		}
		const code = recovery?.failureCode ?? (source === 'builtin' ? 'CATALOG_BUILTIN_FALLBACK' : 'CATALOG_STALE_FALLBACK');
		this.#recordDegraded(provider, providerError(code, `Provider catalog is using ${source}`), 'catalog', source);
	}

	#recordLive(provider, boundary) {
		if (this.#stopped) return;
		const previous = this.#recovery.get(provider);
		const failures = this.#failureBoundaries.get(provider);
		failures?.delete(boundary);
		if (failures?.size === 0) this.#failureBoundaries.delete(provider);
		const remaining = this.#failureBoundaries.get(provider);
		if (remaining?.size > 0) {
			this.#recovery.set(provider, degradedRecoveryRecord(remaining, previous, { enteringDegraded: false }));
			this.#syncRecoveryTimer(provider);
			return;
		}
		const recovered = previous?.state === 'degraded';
		this.#recovery.set(provider, {
			state: 'live', fallbackMode: null, boundary: null, failureCode: null, consecutiveFailureCount: 0,
			totalFailureCount: 0, boundaryFailureCounts: {}, latestNonAutomaticFailure: null,
			nextProbeAtEpochMs: null, generation: previous?.generation ?? 1,
			lastRecoveryAtEpochMs: recovered ? safeNow(this.#now) : previous?.lastRecoveryAtEpochMs ?? null,
		});
		this.#syncRecoveryTimer(provider);
		if (recovered) this.emit('providerRestored', { provider });
	}

	#recordDegraded(provider, error, boundary, fallbackMode = 'last_valid') {
		if (this.#stopped) return;
		const previous = this.#recovery.get(provider);
		const failures = this.#failureBoundaries.get(provider) ?? new Map();
		const priorBoundary = failures.get(boundary);
		const failureCount = Math.min(1_000_000, (priorBoundary?.count ?? 0) + 1);
		const now = safeNow(this.#now);
		const failure = {
			count: failureCount,
			sequence: ++this.#failureSequence,
			failureCode: boundedFailureCode(error),
			fallbackMode,
			nextProbeAtEpochMs: !AUTOMATIC_RECOVERY_BOUNDARIES.has(boundary) || now === null
				? null
				: now + Math.min(30_000, 1_000 * (2 ** Math.min(5, failureCount - 1))),
		};
		failures.set(boundary, failure);
		this.#failureBoundaries.set(provider, failures);
		this.#recovery.set(provider, degradedRecoveryRecord(failures, previous, { enteringDegraded: previous?.state !== 'degraded' }));
		this.#syncRecoveryTimer(provider);
	}

	#syncRecoveryTimer(provider) {
		const failures = this.#failureBoundaries.get(provider);
		const next = selectRecoveryBoundary(failures, { automaticOnly: true, requireDeadline: true });
		const existing = this.#recoveryTimers.get(provider);
		if (next === undefined || this.#stopped) {
			if (existing !== undefined) this.#cancelTimeout(existing.handle);
			this.#recoveryTimers.delete(provider);
			return;
		}
		const [boundary, failure] = next;
		if (existing?.boundary === boundary && existing.deadline === failure.nextProbeAtEpochMs) return;
		if (existing !== undefined) this.#cancelTimeout(existing.handle);
		const now = safeNow(this.#now);
		if (now === null) return;
		const token = {
			boundary,
			deadline: failure.nextProbeAtEpochMs,
			generation: this.#lifecycleGeneration,
			handle: null,
		};
		token.handle = this.#scheduleTimeout(() => this.#runRecoveryProbe(provider, token), Math.max(0, token.deadline - now));
		token.handle?.unref?.();
		this.#recoveryTimers.set(provider, token);
	}

	async #runRecoveryProbe(provider, token) {
		if (this.#recoveryTimers.get(provider) !== token) return;
		this.#recoveryTimers.delete(provider);
		if (this.#stopped || token.generation !== this.#lifecycleGeneration) return;
		const now = safeNow(this.#now);
		if (now !== null && now < token.deadline) {
			this.#syncRecoveryTimer(provider);
			return;
		}
		try {
			if (token.boundary === 'startup') await this.#startProvider(provider);
			else await this.catalog.refresh({ providers: [provider] });
		} catch { /* the failed operation records and schedules its own next retry */ }
		finally { this.#syncRecoveryTimer(provider); }
	}
}

function degradedRecoveryRecord(failures, previous, { enteringDegraded }) {
	const selected = selectRecoveryBoundary(failures, { automaticOnly: true, requireDeadline: true }) ?? selectLatestFailure(failures);
	const [boundary, failure] = selected;
	const latestNonAutomatic = selectLatestFailure(new Map([...failures.entries()].filter(([name]) => !AUTOMATIC_RECOVERY_BOUNDARIES.has(name))));
	const boundaryFailureCounts = Object.fromEntries([...failures.entries()]
		.sort(([left], [right]) => left.localeCompare(right))
		.map(([name, entry]) => [name, entry.count]));
	const totalFailureCount = Object.values(boundaryFailureCounts)
		.reduce((sum, count) => Math.min(1_000_000, sum + count), 0);
	return {
		state: 'degraded',
		fallbackMode: failure.fallbackMode,
		boundary,
		failureCode: failure.failureCode,
		consecutiveFailureCount: totalFailureCount,
		totalFailureCount,
		boundaryFailureCounts,
		latestNonAutomaticFailure: latestNonAutomatic === undefined ? null : {
			boundary: latestNonAutomatic[0],
			failureCode: latestNonAutomatic[1].failureCode,
			count: latestNonAutomatic[1].count,
		},
		nextProbeAtEpochMs: failure.nextProbeAtEpochMs,
		generation: (previous?.generation ?? 0) + (enteringDegraded ? 1 : 0),
		lastRecoveryAtEpochMs: previous?.lastRecoveryAtEpochMs ?? null,
	};
}

function selectLatestFailure(failures) {
	if (!(failures instanceof Map)) return undefined;
	return [...failures.entries()]
		.sort(([leftBoundary, left], [rightBoundary, right]) => right.sequence - left.sequence || leftBoundary.localeCompare(rightBoundary))[0];
}

function selectRecoveryBoundary(failures, { automaticOnly = false, requireDeadline = false } = {}) {
	if (!(failures instanceof Map)) return undefined;
	return [...failures.entries()]
		.filter(([boundary, failure]) => (!automaticOnly || AUTOMATIC_RECOVERY_BOUNDARIES.has(boundary)) && (!requireDeadline || Number.isSafeInteger(failure.nextProbeAtEpochMs)))
		.sort(([leftBoundary, left], [rightBoundary, right]) => {
			const leftDeadline = Number.isSafeInteger(left.nextProbeAtEpochMs) ? left.nextProbeAtEpochMs : Number.POSITIVE_INFINITY;
			const rightDeadline = Number.isSafeInteger(right.nextProbeAtEpochMs) ? right.nextProbeAtEpochMs : Number.POSITIVE_INFINITY;
			return leftDeadline - rightDeadline || leftBoundary.localeCompare(rightBoundary);
		})[0];
}

function freezeProfile(value) {
	if (value === null || typeof value !== 'object' || Array.isArray(value)) throw new TypeError('provider agent profile must be an object');
	const provider = normalizeProvider(value.provider);
	const serviceTier = value.serviceTier ?? DEFAULT_SERVICE_TIER;
	assertProviderServiceTier(provider, serviceTier);
	return Object.freeze({
		...value,
		provider,
		serviceTier,
	});
}

function assertSameProfile(existing, requested) {
	if (!PROFILE_KEYS.every((key) => existing[key] === requested[key])) throw new ProviderProfileConflictError();
}

class CombinedProviderCatalog {
	constructor(services, { execute, recordOutcome, recovery, availability, now }) {
		this.services = services;
		this.execute = execute;
		this.recordOutcome = recordOutcome;
		this.recovery = recovery;
		this.availability = availability;
		this.now = now;
		this.lastValid = new Map();
		this.stale = false;
	}
	async refresh({ providers = undefined, fallbackProviders = [], profileRecords = undefined, ...options } = {}) {
		const selected = normalizeProviderSelection(providers ?? [], this.services, { defaultToAll: providers === undefined });
		const fallbackOnly = new Set(normalizeProviderSelection(fallbackProviders, this.services, { defaultToAll: false }));
		const settled = await Promise.all(selected.map(async (provider) => {
			const service = this.services.get(provider);
			if (fallbackOnly.has(provider)) {
				const retained = this.lastValid.get(provider);
				return retained === undefined ? null : { provider, ...structuredClone(retained), source: 'last_valid' };
			}
			try {
				const providerRecords = profileRecords?.filter((record) => normalizeProvider(record.provider) === provider);
				const load = providerRecords !== undefined && typeof service.bootstrapCatalog === 'function'
					? () => service.bootstrapCatalog(providerRecords)
					: () => service.catalog.refresh(options);
				const raw = await this.execute(provider, async () => validateCatalogSnapshot(await load(), provider));
				const source = catalogSource(raw.source, service.catalog.stale, this.lastValid.has(provider));
				this.recordOutcome(provider, source, raw.recovery);
				if (source === 'live') this.lastValid.set(provider, raw);
				if (source === 'last_valid') {
					if (!this.lastValid.has(provider)) this.lastValid.set(provider, raw);
					return { provider, ...structuredClone(this.lastValid.get(provider)), source };
				}
				return { provider, ...raw, source };
			} catch {
				const retained = this.lastValid.get(provider);
				return retained === undefined ? null : { provider, ...structuredClone(retained), source: 'last_valid' };
			}
		}));
		const snapshots = settled.filter((snapshot) => snapshot !== null);
		this.stale = snapshots.length !== selected.length || snapshots.some(({ source }) => source !== 'live');
		return {
			refreshedAtEpochMs: safeNow(this.now) ?? 0,
			models: snapshots.flatMap((snapshot) => snapshot.models.map((model) => ({ ...model, provider: model.provider ?? snapshot.provider }))),
			source: combinedSource(snapshots, selected.length),
			recovery: this.recovery(),
			availability: this.availability(),
		};
	}
	assertSupported(provider, model, reasoningEffort, serviceTier) {
		const normalized = normalizeProvider(provider);
		return this.services.get(normalized).catalog.assertSupported(model, reasoningEffort, serviceTier);
	}
}

function normalizeProvider(value) {
	const provider = value ?? 'codex';
	return normalizeProviderId(provider);
}

function normalizeProviderSelection(values, services, { defaultToAll }) {
	if (!Array.isArray(values)) throw new TypeError('provider selection must be an array');
	if (values.length === 0 && defaultToAll) return [...services.keys()];
	const selected = [];
	for (const value of values) {
		const provider = normalizeProvider(typeof value === 'string' ? value : value?.provider);
		if (!services.has(provider)) throw new TypeError(`${provider} service is unavailable`);
		if (!selected.includes(provider)) selected.push(provider);
	}
	return selected;
}

function profilesMatch(left, right) {
	return PROFILE_KEYS.every((key) => left[key] === right[key]);
}

function profileIdentity(profile) {
	return JSON.stringify(PROFILE_KEYS.map((key) => profile[key] ?? null));
}

function providerFailure(profile, error) {
	const code = boundedFailureCode(error);
	return {
		agentId: profile.agentId ?? null,
		profile: structuredClone(profile),
		code,
		message: `${profile.provider ?? 'AI'} provider is temporarily unavailable (${code}).`,
	};
}

function withTimeout(promise, timeoutMs, scheduleTimeout, cancelTimeout, provider, { onTimeout = null } = {}) {
	let handle;
	const timeout = new Promise((_, reject) => {
		handle = scheduleTimeout(() => {
			onTimeout?.();
			reject(providerError('PROVIDER_TIMEOUT', `${provider} provider operation timed out after ${timeoutMs} ms`));
		}, timeoutMs);
		handle?.unref?.();
	});
	return Promise.race([promise, timeout]).finally(() => cancelTimeout(handle));
}

function isLifecycleFenceError(error) {
	return ['PROVIDER_STOPPED', 'STALE_PROVIDER_START', 'STALE_RECONCILIATION', 'STALE_PROVIDER_OUTCOME'].includes(error?.code);
}

function providerError(code, message) {
	return Object.assign(new Error(message), { code });
}

function boundedFailureCode(error) {
	return String(error?.code ?? 'PROVIDER_UNAVAILABLE').replace(/[^A-Za-z0-9._:-]/g, '_').slice(0, 128) || 'PROVIDER_UNAVAILABLE';
}

function safeNow(now) {
	try {
		const value = now();
		return Number.isSafeInteger(value) && value >= 0 ? value : null;
	} catch { return null; }
}

function validateCatalogSnapshot(value, provider) {
	if (value === null || typeof value !== 'object' || Array.isArray(value) || !Array.isArray(value.models)) {
		throw providerError('INVALID_CATALOG', `${provider} catalog snapshot must contain a models array`);
	}
	if (value.models.length === 0 && value.availability?.playable !== false) throw providerError('INVALID_CATALOG', `${provider} catalog snapshot must contain at least one model`);
	for (const model of value.models) {
		if (model === null || typeof model !== 'object' || Array.isArray(model)) throw providerError('INVALID_CATALOG', `${provider} catalog model must be an object`);
		for (const [field, fieldValue] of [['id', model.id], ['model', model.model], ['displayName', model.displayName]]) {
			if (typeof fieldValue !== 'string' || fieldValue.trim().length === 0) throw providerError('INVALID_CATALOG', `${provider} catalog model ${field} must be nonblank`);
		}
		for (const [field, values] of [['reasoningEfforts', model.reasoningEfforts], ['serviceTiers', model.serviceTiers]]) {
			if (!Array.isArray(values) || values.some((entry) => typeof entry !== 'string' || entry.trim().length === 0)) throw providerError('INVALID_CATALOG', `${provider} catalog model ${field} must contain strings`);
		}
	}
	return {
		refreshedAtEpochMs: value.refreshedAtEpochMs ?? 0,
		models: structuredClone(value.models),
		...(value.source === undefined ? {} : { source: value.source }),
		...(value.recovery === undefined ? {} : { recovery: normalizeCatalogRecovery(value.recovery) }),
	};
}

function normalizeCatalogRecovery(value) {
	if (value === null || typeof value !== 'object' || Array.isArray(value)) return { state: 'degraded', failureCode: 'CATALOG_REFRESH_FAILED', consecutiveFailureCount: 1 };
	return {
		state: value.state === 'live' ? 'live' : 'degraded',
		failureCode: value.failureCode === null ? null : boundedFailureCode({ code: value.failureCode }),
		consecutiveFailureCount: Number.isSafeInteger(value.consecutiveFailureCount) && value.consecutiveFailureCount >= 0
			? Math.min(1_000_000, value.consecutiveFailureCount)
			: 1,
	};
}

function catalogSource(value, stale, hasLastValid) {
	if (value === 'live') return 'live';
	if (value === 'last_valid') return 'last_valid';
	if (value === 'builtin') return 'builtin';
	return stale === true ? (hasLastValid ? 'last_valid' : 'builtin') : 'live';
}

function combinedSource(snapshots, expectedCount) {
	if (snapshots.length < expectedCount || snapshots.some(({ source }) => source === 'last_valid')) return 'last_valid';
	if (snapshots.some(({ source }) => source === 'builtin')) return 'builtin';
	return 'live';
}
