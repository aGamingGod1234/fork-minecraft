import { ArenaScriptInterpreter, freezeQueryResult } from './interpreter.mjs';
import { changedInterpreterFactDomains, createInterpreterFacts } from './facts.mjs';
import { ALL_FACT_DOMAINS } from './fact-domains.mjs';
import { SCRIPT_BINDINGS } from './minecraft-api.mjs';

const ORDINARY_PRIORITY = 'ordinary';
const URGENT_PRIORITY = 'urgent';
const DEFAULT_ATTENTION_TRIGGER = 'attention';

/** Runs one provenanced ArenaScript program without adding gameplay decisions. */
export class ArenaScriptEngine {
	#callbacks; #vm = null; #program = null; #facts = null; #eventSequence = -1; #factsSequence = -1; #generation = 0; #lifecycleEpoch = 0; #continuationEpoch = 0;
	#active = null; #pendingResult = null; #boundary = []; #boundaryByWatcher = new Map(); #watcherTruth = new Map(); #cancelling = null;
	#watcherMetadata = [];
	#transition = null; #pendingRequest = null; #coalescedRequest = null; #pendingReplacement = null; #suspendedResult = null; #resumableUnhandled = false; #requestUpdate = null; #completed = new Map(); #deferredBase = null; #continuationRequired = false; #status = 'IDLE';

	constructor({ dispatch, cancel, requestModel, inspect = null, trace = () => {} } = {}) {
		if (typeof dispatch !== 'function' || typeof cancel !== 'function' || typeof requestModel !== 'function') throw new TypeError('ArenaScriptEngine callbacks dispatch, cancel, and requestModel are required');
		if (typeof trace !== 'function') throw new TypeError('ArenaScriptEngine trace callback must be a function');
		if (inspect !== null && typeof inspect !== 'function') throw new TypeError('ArenaScriptEngine inspect callback must be a function');
		this.#callbacks = { dispatch, cancel, requestModel, inspect, trace };
	}

	install(input) {
		const target = normalizeInstall(input, this.#facts);
		const baseline = this.#transition?.kind === 'install' ? this.#transition.target : this.#program;
		const relation = baseline ? installationRelation(target, baseline) : 1;
		if (relation < 0) return this.snapshot();
		if (relation === 0) return this.#refreshInstalledFacts(target);
		this.#invalidateLifecycleRequests();
		if (this.#active) {
			this.#transition = { kind: 'install', target };
			this.#cancelActive('replace');
			return this.snapshot();
		}
		return this.#activate(target);
	}

	ingestObservation({ observation, eventSequence, attention = false, priority = ORDINARY_PRIORITY, trigger = DEFAULT_ATTENTION_TRIGGER } = {}) {
		if (!this.#isLive() || !Number.isSafeInteger(eventSequence) || eventSequence < 0 || eventSequence < this.#eventSequence) return this.snapshot();
		const mayResume = this.#pendingResult && eventSequence >= this.#pendingResult.eventSequence;
		if (eventSequence === this.#eventSequence && !mayResume && this.#factsSequence >= eventSequence) return this.snapshot();
		const previousFacts = this.#facts;
		this.#facts = createInterpreterFacts(observation, previousFacts);
		const changedFactDomains = changedInterpreterFactDomains(previousFacts, this.#facts);
		this.#factsSequence = eventSequence;
		this.#eventSequence = Math.max(this.#eventSequence, eventSequence);
		this.#fencePendingRequest();
		this.#tryPendingReplacement();
		const edges = this.#updateWatchers(changedFactDomains);
		if (this.#pendingResult && !this.#cancelling && eventSequence >= this.#pendingResult.eventSequence) this.#resumeOrRunBoundary();
		else if (!this.#active && !this.#cancelling && this.#boundary.length > 0) this.#runBoundary();
		if (attention && edges === 0) this.#requestModel(null, { priority, trigger });
		this.#requestExhaustedContinuation();
		return this.snapshot();
	}

	/** Queues an external attention trigger against the current factual snapshot. */
	notifyAttention({ priority = URGENT_PRIORITY, trigger = DEFAULT_ATTENTION_TRIGGER } = {}) {
		if (!this.#isLive() || this.#facts === null) return this.snapshot();
		this.#requestModel(null, { priority, trigger });
		return this.snapshot();
	}

	/** Reopens a factually rejected terminal decision so the selected brain can correct it. */
	requestCorrection({ trigger = 'completion_verification_failed', actionFailure } = {}) {
		if (this.#status !== 'FINISHED' || this.#program === null || this.#facts === null) return this.snapshot();
		this.#status = 'SUSPENDED';
		this.#requestModel(freezeRecord(actionFailure), { priority: URGENT_PRIORITY, trigger, decisionContext: 'completion_verification_failed' });
		return this.snapshot();
	}

	ingestActionResult({ actionId, state, reasonCode, eventSequence } = {}) {
		if (!Number.isSafeInteger(eventSequence) || eventSequence < 0 || typeof actionId !== 'string' || typeof state !== 'string' || typeof reasonCode !== 'string') return this.snapshot();
		const signature = `${state}\u0000${reasonCode}\u0000${eventSequence}`;
		if (!this.#active || this.#active.kind === 'query' || actionId !== this.#active.actionId || eventSequence < this.#active.actionDispatchEventSequence) {
			if (this.#completed.has(actionId) && this.#completed.get(actionId) !== signature) return this.snapshot();
			return this.snapshot();
		}
		return this.#settleActiveResult(Object.freeze({ stateToken: this.#active.stateToken, state, reasonCode }), eventSequence, signature);
	}

	ingestQueryResult({ queryId, value } = {}) {
		if (!this.#active || this.#active.kind !== 'query' || this.#active.actionId !== queryId) return this.snapshot();
		value = freezeQueryResult(value);
		if (!['SUCCEEDED', 'FAILED', 'CANCELLED', 'TIMED_OUT'].includes(value.state)) throw new TypeError('query result requires a factual terminal state');
		const state = value.state;
		const reasonCode = typeof value?.reasonCode === 'string' ? value.reasonCode : 'INSPECTED';
		return this.#settleActiveResult(Object.freeze({ stateToken: this.#active.stateToken, state, reasonCode, value }), this.#eventSequence, `query:${queryId}`);
	}

	#settleActiveResult(result, eventSequence, signature) {
		const { state } = result;
		const active = this.#active;
		this.#active = null;
		this.#eventSequence = Math.max(this.#eventSequence, eventSequence);
		this.#continuationEpoch += 1;
		this.#fencePendingRequest();
		this.#completed.set(active.actionId, signature);
		if (this.#transition || this.#cancelling) {
			const cancelling = this.#cancelling;
			this.#cancelling = null;
			if (this.#transition) { this.#transition.result = Object.freeze({ result, eventSequence, authority: active.authority, executionFactsSequence: active.executionFactsSequence }); return this.#completeTransition(); }
			if (cancelling?.kind === 'watcher') {
				if (state === 'CANCELLED') {
					this.#vm.abortPendingCommand(result.stateToken);
					this.#handleYield(this.#vm.runWatcherHandler(cancelling.latch.watcherId, cancelling.latch.facts), watcherExecution(cancelling.latch));
					return this.snapshot();
				}
				this.#queueBoundary(cancelling.latch, true);
				this.#pendingResult = Object.freeze({ result, eventSequence, authority: active.authority, executionFactsSequence: active.executionFactsSequence });
				if (this.#factsSequence >= eventSequence) this.#resumeOrRunBoundary();
				return this.snapshot();
			}
			this.#status = 'SUSPENDED';
			return this.snapshot();
		}
		this.#pendingResult = Object.freeze({ result, eventSequence, authority: active.authority, executionFactsSequence: active.executionFactsSequence });
		if (this.#factsSequence >= eventSequence) this.#resumeOrRunBoundary();
		return this.snapshot();
	}

	/** Converges a failed cancel transport without allowing old work to resume. */
	failCancellation({ actionId, eventSequence, reasonCode = 'CANCEL_SEND_FAILED' } = {}) {
		if (!this.#active || !this.#cancelling || this.#active.actionId !== actionId || this.#cancelling.actionId !== actionId) return this.snapshot();
		if (!Number.isSafeInteger(eventSequence) || eventSequence < 0) return this.snapshot();
		this.#completed.set(actionId, `FAILED\u0000${reasonCode}\u0000${eventSequence}`);
		this.#active = null;
		this.#cancelling = null;
		this.#transition = null;
		this.#pendingResult = null;
		this.#deferredBase = null;
		this.#boundary = [];
		this.#boundaryByWatcher.clear();
		this.#invalidateLifecycleRequests();
		this.#eventSequence = Math.max(this.#eventSequence, eventSequence);
		this.#status = 'SUSPENDED';
		this.#emitTrace('program_cancel_failed', { actionId, eventSequence, reasonCode });
		return this.snapshot();
	}

	applyDirective(directive = {}) {
		const request = this.#pendingRequest;
		if (!request || !sameRequest(directive, request)) return this.snapshot();
		if (this.#coalescedRequest && !sameRequest(this.#coalescedRequest, request)) {
			this.#pendingRequest = this.#coalescedRequest;
			this.#callbacks.requestModel(this.#pendingRequest);
			return this.snapshot();
		}
		this.#pendingRequest = null;
		this.#coalescedRequest = null;
		this.#requestUpdate = null;
		if (directive.directive === 'continue') {
			if (request.decisionContext === 'completion_verification_failed') this.#status = 'FINISHED';
			else if (this.#transition?.kind === 'terminal' && this.#transition.reason === 'unhandled_attention') this.#transition = { kind: 'resume' };
			else if (this.#status === 'SUSPENDED' && (this.#suspendedResult || this.#resumableUnhandled)) {
				this.#pendingResult = this.#suspendedResult; this.#suspendedResult = null; this.#resumableUnhandled = false; this.#status = 'ACTIVE';
				if (this.#pendingResult && this.#factsSequence >= this.#pendingResult.eventSequence) this.#resumeOrRunBoundary();
			}
			return this.snapshot();
		}
		if (directive.directive === 'replace') {
			try {
				const replacement = normalizeDirectiveReplacement(directive.install, this.#program, directive.traceId);
				if (!isNewer(replacement, this.#program)) return this.snapshot();
				const requiredSequence = Math.max(request.eventSequence, this.#eventSequence);
				if (this.#factsSequence < requiredSequence) { this.#pendingReplacement = freezeRecord({ replacement, requiredSequence, lifecycleEpoch: this.#lifecycleEpoch }); return this.snapshot(); }
				this.#beginReplacement(replacement);
				return this.snapshot();
			} catch { return this.snapshot(); }
		} else if (directive.directive === 'pause' || directive.directive === 'finish') {
			this.#continuationRequired = false;
			this.#transition = { kind: 'terminal', status: directive.directive === 'pause' ? 'SUSPENDED' : 'FINISHED' };
		} else return this.snapshot();
		if (this.#active) this.#cancelActive(`directive:${directive.directive}`);
		else this.#completeTransition();
		return this.snapshot();
	}

	/** Releases only this exact model request without applying a gameplay directive. */
	failDirectiveRequest(request = {}) {
		if (!this.#pendingRequest || !sameRequest(request, this.#pendingRequest)) return this.snapshot();
		const newest = this.#coalescedRequest;
		this.#pendingRequest = null;
		this.#coalescedRequest = null;
		this.#requestUpdate = null;
		if (newest && !sameRequest(newest, request)) {
			this.#pendingRequest = newest;
			this.#callbacks.requestModel(newest);
		}
		return this.snapshot();
	}

	suspend(reason = 'suspended') {
		this.#invalidateLifecycleRequests();
		if (!this.#isLive()) return this.snapshot();
		this.#transition = { kind: 'terminal', status: 'SUSPENDED', reason };
		if (this.#active) this.#cancelActive(reason); else this.#completeTransition();
		return this.snapshot();
	}

	dispose() {
		this.#invalidateLifecycleRequests();
		if (this.#active) { this.#transition = { kind: 'dispose' }; this.#cancelActive('dispose'); return; }
		this.#clear();
	}

	/** Promotes the latest coalesced request for a recovery retry without issuing another model call. */
	refreshDirectiveRequest() {
		if (this.#pendingRequest === null) return null;
		const latest = this.#coalescedRequest ?? this.#pendingRequest;
		this.#pendingRequest = latest;
		this.#coalescedRequest = latest;
		return latest;
	}

	snapshot() { const pending = this.#coalescedRequest ?? this.#pendingRequest; return Object.freeze({ status: this.#status, eventSequence: this.#eventSequence, factsSequence: this.#factsSequence, generation: this.#generation, lifecycleEpoch: this.#lifecycleEpoch, continuationEpoch: this.#continuationEpoch, activeActionId: this.#activeActionId(), activeQueryId: this.#active?.kind === 'query' ? this.#active.actionId : null, programId: this.#program?.programId ?? null, version: this.#program?.version ?? null, pendingRequestPriority: pending?.priority ?? null, pendingRequestTrigger: pending?.trigger ?? null }); }

	#activate(target) {
		const latestFacts = this.#facts && this.#factsSequence > target.factsSequence ? this.#facts : target.facts;
		const latestFactsSequence = Math.max(this.#factsSequence, target.factsSequence);
		const latestSequence = Math.max(this.#eventSequence, target.eventSequence);
		this.#clear(false);
		this.#generation += 1;
		this.#program = freezeRecord({ agentId: target.agentId, provider: target.provider, modelIdentity: target.modelIdentity, reasoningEffort: target.reasoningEffort, serviceTier: target.serviceTier, traceId: target.traceId, goalRevision: target.goalRevision, programId: target.programId, version: target.version, compiled: target.compiled, eventSequence: target.eventSequence, factsSequence: target.factsSequence });
		this.#facts = latestFacts;
		this.#factsSequence = latestFactsSequence;
		this.#eventSequence = latestSequence;
		this.#vm = new ArenaScriptInterpreter(target.compiled, SCRIPT_BINDINGS);
		this.#watcherMetadata = watcherMetadata(target.compiled);
		this.#status = 'ACTIVE';
		this.#handleYield(this.#vm.start(this.#facts), 'step');
		for (let index = 0; index < target.compiled.watcherCount && this.#isLive(); index += 1) {
			const watcherId = this.#watcherMetadata[index].id;
			this.#watcherTruth.set(watcherId, this.#vm.evaluateWatcher(watcherId, this.#facts));
		}
		return this.snapshot();
	}

	#clear(resetGeneration = true) {
		this.#vm = null; this.#program = null; this.#facts = null; this.#eventSequence = -1; this.#factsSequence = -1; this.#active = null; this.#pendingResult = null; this.#watcherMetadata = [];
		this.#boundary = []; this.#boundaryByWatcher.clear(); this.#watcherTruth.clear(); this.#cancelling = null; this.#transition = null; this.#pendingRequest = null; this.#coalescedRequest = null; this.#pendingReplacement = null; this.#suspendedResult = null; this.#resumableUnhandled = false; this.#requestUpdate = null; this.#completed.clear(); this.#deferredBase = null; this.#continuationRequired = false; this.#status = 'IDLE';
		if (resetGeneration) this.#generation += 1;
	}

	#activeActionId() { return this.#active?.kind === 'query' ? null : this.#active?.actionId ?? null; }
	#isLive() { return this.#vm !== null && ['ACTIVE', 'SUSPENDING', 'REPLACING', 'FINISHING'].includes(this.#status); }
	#cancelActive(reason) { if (!this.#active || this.#cancelling) return; this.#cancelling = { kind: 'transition', actionId: this.#active.actionId, reason }; this.#status = reason === 'replace' ? 'REPLACING' : 'SUSPENDING'; this.#cancelCurrentOperation(); }
	#cancelCurrentOperation() {
		if (this.#active.kind === 'query') this.ingestQueryResult({ queryId: this.#active.actionId, value: { state: 'CANCELLED', reasonCode: 'QUERY_CANCELLED' } });
		else this.#callbacks.cancel(this.#active.actionId);
	}
	#completeTransition() {
		const transition = this.#transition; this.#transition = null;
		if (!transition) return this.snapshot();
		if (transition.kind === 'install') return this.#activate(transition.target);
		if (transition.kind === 'dispose') { this.#clear(); return this.snapshot(); }
		if (transition.kind === 'resume') {
			this.#status = 'ACTIVE'; this.#pendingResult = transition.result;
			if (this.#pendingResult && this.#factsSequence >= this.#pendingResult.eventSequence) this.#resumeOrRunBoundary();
			return this.snapshot();
		}
		if (transition.status === 'SUSPENDED' && transition.reason === 'unhandled_attention') { this.#suspendedResult = transition.result ?? null; this.#resumableUnhandled = true; }
		this.#status = transition.status;
		return this.snapshot();
	}

	#updateWatchers(changedFactDomains = ALL_FACT_DOMAINS) {
		let edges = 0;
		for (let index = 0; index < this.#program.compiled.watcherCount; index += 1) {
			const metadata = this.#watcherMetadata[index];
			if (Number.isSafeInteger(metadata?.factDependencyMask) && (metadata.factDependencyMask & changedFactDomains) === 0) continue;
			const watcherId = metadata.id;
			const trueNow = this.#vm.evaluateWatcher(watcherId, this.#facts);
			const wasTrue = this.#watcherTruth.get(watcherId) === true;
			this.#watcherTruth.set(watcherId, trueNow);
			if (!trueNow || wasTrue) continue;
			edges += 1;
			const latch = freezeRecord({ watcherId, mode: metadata.mode, eventSequence: this.#eventSequence, generation: this.#generation, facts: this.#facts });
			this.#emitTrace('watcher_fired', { watcherId, mode: latch.mode, eventSequence: this.#eventSequence, generation: this.#generation });
			if (latch.mode === 'interrupt' && this.#active && !this.#cancelling) {
				this.#cancelling = { kind: 'watcher', actionId: this.#active.actionId, latch };
				this.#cancelCurrentOperation();
			} else this.#queueBoundary(latch);
		}
		return edges;
	}

	#resumeOrRunBoundary() {
		const pending = this.#pendingResult; this.#pendingResult = null;
		if (!pending.authority && this.#boundary.length > 0) { this.#pendingResult = pending; return this.#runBoundary(); }
		const yielded = this.#vm.resume(pending.result, this.#facts);
		this.#handleYield(yielded, pending.authority ? watcherExecution(pending.authority, this.#factsSequence) : 'step');
	}

	#queueBoundary(latch, front = false) {
		const prior = this.#boundaryByWatcher.get(latch.watcherId);
		if (prior !== undefined) {
			const index = this.#boundary.indexOf(prior);
			if (index >= 0) this.#boundary[index] = latch;
			this.#boundaryByWatcher.set(latch.watcherId, latch);
			this.#emitTrace('watcher_coalesced', {
				watcherId: latch.watcherId,
				previousEventSequence: prior.eventSequence,
				eventSequence: latch.eventSequence,
				generation: latch.generation,
			});
			return;
		}
		if (this.#boundaryByWatcher.size >= Math.max(1, this.#program?.compiled?.watcherCount ?? 1)) {
			this.#emitTrace('watcher_dropped', { watcherId: latch.watcherId, eventSequence: latch.eventSequence, generation: latch.generation, reason: 'PENDING_LATCH_LIMIT' });
			return;
		}
		this.#boundaryByWatcher.set(latch.watcherId, latch);
		if (front) this.#boundary.unshift(latch);
		else this.#boundary.push(latch);
	}

	#runBoundary() {
		if (this.#active || this.#boundary.length === 0) return;
		const latch = this.#boundary.shift();
		this.#boundaryByWatcher.delete(latch.watcherId);
		if (this.#deferredBase) {
			this.#handleYield(this.#vm.runWatcherHandler(latch.watcherId, latch.facts), watcherExecution(latch));
		} else if (this.#pendingResult) {
			this.#deferredBase = this.#pendingResult;
			this.#pendingResult = null;
			this.#handleYield(this.#vm.runWatcherHandlerBeforeResume(latch.watcherId, latch.facts), watcherExecution(latch));
		} else this.#handleYield(this.#vm.runWatcherHandler(latch.watcherId, latch.facts), watcherExecution(latch));
	}

	#requestModel(actionFailure = null, { priority = ORDINARY_PRIORITY, trigger = DEFAULT_ATTENTION_TRIGGER, decisionContext = null } = {}) {
		const context = requestContext(this.#program, this.#generation, this.#lifecycleEpoch, this.#continuationEpoch, this.#activeActionId(), this.#eventSequence, this.#factsSequence, this.#facts, actionFailure, { priority, trigger, decisionContext });
		if (this.#pendingRequest) {
			this.#coalescedRequest = mergeRequestContexts(this.#coalescedRequest ?? this.#pendingRequest, context);
			return;
		}
		this.#pendingRequest = context;
		this.#coalescedRequest = context;
		if (actionFailure === null) this.#emitTrace('attention_unhandled', { eventSequence: this.#eventSequence, factsSequence: this.#factsSequence, programId: this.#program.programId, version: this.#program.version });
		else this.#emitTrace('program_action_failure', { eventSequence: this.#eventSequence, factsSequence: this.#factsSequence, programId: this.#program.programId, version: this.#program.version, actionFailure });
		this.#callbacks.requestModel(context);
		if (this.#program.compiled.unhandledPolicy === 'pause_and_notify') this.#suspendUnhandledAttention();
	}

	#invalidateLifecycleRequests() { this.#lifecycleEpoch += 1; this.#pendingRequest = null; this.#coalescedRequest = null; this.#pendingReplacement = null; this.#suspendedResult = null; this.#resumableUnhandled = false; }
	#suspendUnhandledAttention() {
		this.#transition = { kind: 'terminal', status: 'SUSPENDED', reason: 'unhandled_attention' };
		if (this.#active) this.#cancelActive('unhandled_attention'); else this.#completeTransition();
	}
	#fencePendingRequest() {
		if (!this.#pendingRequest) return;
		const pending = this.#coalescedRequest ?? this.#pendingRequest;
		this.#coalescedRequest = requestContext(
			this.#program,
			this.#generation,
			this.#lifecycleEpoch,
			this.#continuationEpoch,
			this.#activeActionId(),
			this.#eventSequence,
			this.#factsSequence,
			this.#facts,
			pending.actionFailure ?? null,
			{ priority: pending.priority, trigger: pending.trigger },
		);
	}
	#refreshInstalledFacts(target) {
		if (target.eventSequence < this.#eventSequence || target.factsSequence < this.#factsSequence) return this.snapshot();
		const changedFactDomains = changedInterpreterFactDomains(this.#facts, target.facts);
		this.#facts = target.facts; this.#factsSequence = target.factsSequence;
		this.#eventSequence = target.eventSequence;
		this.#program = freezeRecord({ ...this.#program, eventSequence: target.eventSequence, factsSequence: target.factsSequence });
		this.#fencePendingRequest();
		if (!this.#isLive()) return this.snapshot();
		this.#tryPendingReplacement();
		this.#updateWatchers(changedFactDomains);
		if (this.#pendingResult && !this.#cancelling && this.#factsSequence >= this.#pendingResult.eventSequence) this.#resumeOrRunBoundary();
		else if (!this.#active && !this.#cancelling && this.#boundary.length > 0) this.#runBoundary();
		this.#requestExhaustedContinuation();
		return this.snapshot();
	}
	#beginReplacement(replacement) {
		const target = freezeRecord({ ...replacement, facts: this.#facts, factsSequence: this.#factsSequence, eventSequence: this.#eventSequence });
		this.#transition = { kind: 'install', target };
		if (this.#active) this.#cancelActive('directive:replace'); else this.#completeTransition();
	}
	#tryPendingReplacement() {
		const pending = this.#pendingReplacement;
		if (!pending || pending.lifecycleEpoch !== this.#lifecycleEpoch || this.#factsSequence < Math.max(pending.requiredSequence, this.#eventSequence)) return;
		this.#pendingReplacement = null;
		if (this.#program && isNewer(pending.replacement, this.#program)) this.#beginReplacement(pending.replacement);
	}

	#handleYield(yielded, source) {
		if (yielded.kind === 'query') {
			const queryId = `${this.#program.programId}:${this.#program.version}:${this.#generation}:${yielded.stateToken}`;
			const authority = source?.authority ?? null;
			this.#active = { kind: 'query', actionId: queryId, stateToken: yielded.stateToken, generation: this.#generation, source, authority, executionFactsSequence: this.#factsSequence };
			this.#continuationEpoch += 1;
			this.#fencePendingRequest();
			if (this.#callbacks.inspect === null) this.ingestQueryResult({ queryId, value: { state: 'FAILED', reasonCode: 'INSPECTION_UNAVAILABLE' } });
			else this.#callbacks.inspect(Object.freeze({ queryId, operation: yielded.operation, query: yielded.query,
				authorship: Object.freeze({ provider: this.#program.provider, model: this.#program.modelIdentity, reasoningEffort: this.#program.reasoningEffort,
					serviceTier: this.#program.serviceTier, goalRevision: this.#program.goalRevision, programId: this.#program.programId,
					programVersion: this.#program.version, sourceStepId: yielded.stepId }) }));
			return;
		}
		if (yielded.kind === 'command') {
			const actionId = `${this.#program.programId}:${this.#program.version}:${this.#generation}:${yielded.stateToken}`;
			const authority = source?.authority ?? (source && typeof source === 'object' && typeof source.watcherId === 'string' ? source : null);
			const executionFactsSequence = source?.executionFactsSequence ?? this.#factsSequence;
			const command = freezeRecord({ actionId, action: freezeRecord({ type: yielded.call.primitive, arguments: yielded.call.arguments }), provenance: freezeRecord({ agentId: this.#program.agentId, provider: this.#program.provider, model: this.#program.modelIdentity, reasoningEffort: this.#program.reasoningEffort, serviceTier: this.#program.serviceTier, traceId: this.#program.traceId, goalRevision: this.#program.goalRevision, modelIdentity: this.#program.modelIdentity, programId: this.#program.programId, version: this.#program.version, generation: this.#generation, source: authority ? `watcher:${authority.watcherId}` : source, watcherId: authority?.watcherId ?? null, authorizingEventSequence: authority?.eventSequence ?? null, executionFactsSequence, factsEventSequence: executionFactsSequence, stepId: yielded.stepId, eventSequence: this.#eventSequence }) });
			this.#continuationEpoch += 1;
			this.#active = { actionId, stateToken: yielded.stateToken, generation: this.#generation, source, authority, executionFactsSequence, actionDispatchEventSequence: this.#eventSequence };
			this.#fencePendingRequest();
			this.#callbacks.dispatch(command);
			return;
		}
		if (yielded.kind === 'finish' || yielded.kind === 'checkpoint') {
			if (this.#deferredBase) { this.#vm.discardDeferredCommand(); this.#deferredBase = null; }
			this.#status = yielded.kind === 'finish' ? 'FINISHED' : 'PAUSED';
			this.#emitTrace(yielded.kind === 'finish' ? 'program_finished' : 'program_checkpoint', {
				programId: this.#program.programId,
				version: this.#program.version,
				eventSequence: this.#eventSequence,
				status: this.#status,
			});
			return;
		}
		if (yielded.kind === 'replan') {
			if (this.#deferredBase) { this.#vm.discardDeferredCommand(); this.#deferredBase = null; }
			this.#status = 'SUSPENDED';
			this.#requestModel(freezeRecord({
				sourceStepId: yielded.stepId,
				actionType: yielded.failure.actionType,
				arguments: yielded.failure.arguments,
				state: yielded.failure.state,
				reasonCode: yielded.failure.reasonCode,
			}), { priority: URGENT_PRIORITY, trigger: 'action_failure' });
			return;
		}
		if (yielded.kind === 'idle' && !this.#active && this.#boundary.length > 0) return this.#runBoundary();
		if (yielded.kind === 'idle' && this.#deferredBase) {
			if (this.#boundary.length > 0) return this.#runBoundary();
			const deferred = this.#deferredBase; this.#deferredBase = null;
			this.#handleYield(this.#vm.resumeDeferredCommand(deferred.result, this.#facts), 'step');
			return;
		}
		if (yielded.kind === 'idle' && source === 'step') {
			this.#continuationRequired = true;
			this.#requestExhaustedContinuation();
		}
	}

	#requestExhaustedContinuation() {
		if (!this.#continuationRequired || this.#pendingRequest || !this.#isLive() || this.#facts === null) return;
		this.#requestModel(null, { priority: URGENT_PRIORITY, trigger: 'program_exhausted' });
	}

	#emitTrace(event, fields) {
		try { this.#callbacks.trace(event, fields); } catch { /* diagnostics cannot interrupt the interpreter */ }
	}
}

function normalizeInstall(input, previousFacts = null) {
	if (!input || typeof input !== 'object' || !input.compiled?.ast || !Object.isFrozen(input.compiled)) throw new TypeError('ArenaScriptEngine requires a frozen compiled program');
	const { agentId, modelIdentity, goalRevision } = input;
	for (const [field, value] of [['agentId', agentId], ['provider', input.provider ?? 'unknown-provider'], ['modelIdentity', modelIdentity], ['reasoningEffort', input.reasoningEffort ?? 'unknown-reasoning'], ['serviceTier', input.serviceTier ?? 'unknown-tier'], ['traceId', input.traceId ?? defaultTraceId(agentId, goalRevision, input.version)], ['programId', input.programId]]) if (typeof value !== 'string' || value.trim().length === 0) throw new TypeError(`ArenaScriptEngine ${field} must be a nonempty string`);
	for (const [field, value] of [['goalRevision', goalRevision], ['version', input.version], ['eventSequence', input.eventSequence]]) if (!Number.isSafeInteger(value) || value < 0) throw new TypeError(`ArenaScriptEngine ${field} must be a nonnegative safe integer`);
	const facts = createInterpreterFacts(input.observation, previousFacts);
	return freezeRecord({ agentId: agentId.trim(), provider: (input.provider ?? 'unknown-provider').trim(), goalRevision, modelIdentity: modelIdentity.trim(), reasoningEffort: (input.reasoningEffort ?? 'unknown-reasoning').trim(), serviceTier: (input.serviceTier ?? 'unknown-tier').trim(), traceId: normalizeTraceId(input.traceId ?? defaultTraceId(agentId, goalRevision, input.version)), programId: input.programId.trim(), version: input.version, compiled: input.compiled, facts, factsSequence: input.eventSequence, eventSequence: input.eventSequence });
}
function normalizeDirectiveReplacement(input, current, requestedTraceId = undefined) {
	if (!current || !input || typeof input !== 'object') throw new TypeError('ArenaScriptEngine replacement requires an active authenticated program');
	for (const field of ['agentId', 'goalRevision', 'modelIdentity', 'observation', 'eventSequence']) if (Object.hasOwn(input, field)) throw new TypeError(`ArenaScriptEngine directive install may not supply ${field}`);
	if (!input.compiled?.ast || !Object.isFrozen(input.compiled)) throw new TypeError('ArenaScriptEngine requires a frozen compiled program');
	for (const [field, value] of [['programId', input.programId]]) if (typeof value !== 'string' || value.trim().length === 0) throw new TypeError(`ArenaScriptEngine ${field} must be a nonempty string`);
	if (!Number.isSafeInteger(input.version) || input.version < 0) throw new TypeError('ArenaScriptEngine version must be a nonnegative safe integer');
	const traceId = requestedTraceId === undefined ? current.traceId : normalizeTraceId(requestedTraceId);
	return freezeRecord({ agentId: current.agentId, provider: current.provider, goalRevision: current.goalRevision, modelIdentity: current.modelIdentity, reasoningEffort: current.reasoningEffort, serviceTier: current.serviceTier, traceId, programId: input.programId.trim(), version: input.version, compiled: input.compiled });
}
function isNewer(next, current) { return next.goalRevision > current.goalRevision || (next.goalRevision === current.goalRevision && next.version > current.version); }
function installationRelation(next, current) {
	if (next.goalRevision !== current.goalRevision) return next.goalRevision > current.goalRevision ? 1 : -1;
	if (next.version !== current.version) return next.version > current.version ? 1 : -1;
	if (!sameImmutableProgram(next, current)) return -1;
	return next.eventSequence > current.eventSequence ? 0 : -1;
}
function sameImmutableProgram(next, current) { return next.agentId === current.agentId && next.modelIdentity === current.modelIdentity && next.programId === current.programId && next.compiled === current.compiled && next.compiled.source === current.compiled.source; }
function defaultTraceId(agentId, goalRevision, version) { return `trace-${String(agentId).replace(/[^A-Za-z0-9._:-]/g, '_')}-${goalRevision}-${version}`.slice(0, 128); }
function normalizeTraceId(value) {
	if (typeof value !== 'string' || value.trim().length === 0 || Buffer.byteLength(value, 'utf8') > 128 || [...value].some((character) => /[\u0000-\u001f\u007f]/u.test(character))) throw new TypeError('ArenaScriptEngine traceId must be a bounded string');
	return value.trim();
}
function requestContext(program, generation, lifecycleEpoch, continuationEpoch, activeActionId, eventSequence, factsSequence, observation, actionFailure = null, { priority = ORDINARY_PRIORITY, trigger = DEFAULT_ATTENTION_TRIGGER, decisionContext = null } = {}) {
	return freezeRecord({
		agentId: program.agentId,
		provider: program.provider,
		goalRevision: program.goalRevision,
		modelIdentity: program.modelIdentity,
		reasoningEffort: program.reasoningEffort,
		serviceTier: program.serviceTier,
		traceId: program.traceId,
		programId: program.programId,
		version: program.version,
		generation,
		lifecycleEpoch,
		continuationEpoch,
		activeActionId,
		eventSequence,
		factsSequence,
		observation,
		priority: normalizePriority(priority),
		trigger: normalizeTrigger(trigger),
		...(actionFailure === null ? {} : { decisionContext: decisionContext ?? 'program_action_failure', actionFailure }),
	});
}
function mergeRequestContexts(previous, next) {
	const priority = previous.priority === URGENT_PRIORITY || next.priority === URGENT_PRIORITY ? URGENT_PRIORITY : ORDINARY_PRIORITY;
	const winner = next.priority === priority ? next : previous;
	const merged = { ...next, priority, trigger: winner.trigger };
	if (winner.actionFailure !== undefined) {
		merged.decisionContext = winner.decisionContext;
		merged.actionFailure = winner.actionFailure;
	} else if (priority === ORDINARY_PRIORITY) {
		delete merged.decisionContext;
		delete merged.actionFailure;
	}
	return freezeRecord(merged);
}
function sameRequest(value, request) {
	return value && [
		'agentId', 'goalRevision', 'modelIdentity', 'programId', 'version', 'generation', 'lifecycleEpoch',
		'continuationEpoch', 'activeActionId', 'eventSequence', 'factsSequence', 'priority', 'trigger',
	].every((key) => value[key] === request[key]);
}
function watcherExecution(authority, executionFactsSequence = authority.eventSequence) { return freezeRecord({ authority, executionFactsSequence }); }
function watcherMetadata(compiled) {
	if (Array.isArray(compiled.watchers) && compiled.watchers.length === compiled.watcherCount) return compiled.watchers;
	const watches = [];
	for (const statement of compiled.ast.body) {
		const call = statement.type === 'ExpressionStatement' ? statement.expression : null;
		if (call?.type === 'CallExpression' && call.callee.type === 'MemberExpression' && call.callee.object.name === 'program' && call.callee.property.name === 'watch') watches.push(call);
	}
	return Object.freeze(Array.from({ length: compiled.watcherCount }, (_unused, index) => freezeRecord({
		id: `watcher-${index}`,
		mode: watches[index]?.arguments[1]?.properties?.find((property) => property.key.name === 'mode')?.value?.value ?? 'boundary',
		factDependencyMask: null,
	})));
}
function freezeRecord(values) { return Object.freeze(Object.assign(Object.create(null), values)); }
function normalizePriority(value) { return value === URGENT_PRIORITY ? URGENT_PRIORITY : ORDINARY_PRIORITY; }
function normalizeTrigger(value) { return typeof value === 'string' && value.trim().length > 0 ? value.trim().slice(0, 128) : DEFAULT_ATTENTION_TRIGGER; }
