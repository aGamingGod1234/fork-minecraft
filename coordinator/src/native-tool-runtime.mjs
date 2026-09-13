import { createHash, randomUUID } from 'node:crypto';
import { validateTraceId } from './control-latency-registry.mjs';
import { ExplorationOccupancy, observationWorldId } from './explore-frontier.mjs';
import { hasDurableObservationFacts, RecoveryProgressStore } from './recovery-progress-wrap.mjs';
import { classifyBodyFailure, composeTwoCallView } from './two-call-llm-wrap.mjs';
import { minecraftCapabilities, normalizeMinecraftToolCall, toolResultContent } from './native-minecraft-tools.mjs';
import { NativeProgramExecutor } from './native-program-executor.mjs';

const FORGET_REASONS = /agent_removed|server_replaced|coordinator_stopped/;
const TERMINAL_ACTION_STATES = new Set(['SUCCEEDED', 'FAILED', 'CANCELLED', 'TIMED_OUT']);


export class NativeToolRuntime {
	#bridge;
	#registry;
	#onFinish;
	#trace;
	#observations = new Map();
	#lastLive = new Map();
	#actions = new Map();
	#completions = new Map();
	#staleActions = new Map();
	#occupancy;
	#memoryLoads = new Map();
	#memoryReady = new Set();
	#pendingSpatial = new Map();
	#recovery = new RecoveryProgressStore();
	#decorateObservation;
	#requestObservation;
	#inspectObservation;
	#inspectedTargets = new Map();
	#notebook;
	#executionSettings;
	#memoryOperation;
	#programExecutor;
	#programRuns = new Map();
	#sessionId;
	#receipts = new Map();
	#executionEpochs = new Map();
	#sequence = 0;

	constructor({
		bridge,
		registry = null,
		onFinish = async () => ({ state: 'FINISH_REQUESTED' }),
		trace = () => {},
		decorateObservation = null,
		requestObservation = null,
		inspectObservation = null,
		notebook = null,
		executionSettings = null,
		memoryOperation = null,
		programExecutor = null,
		sessionId = randomUUID(),
		occupancy = new ExplorationOccupancy(),
	} = {}) {
		if (typeof bridge?.send !== 'function') throw new TypeError('bridge.send must be a function');
		if (registry !== null && typeof registry?.get !== 'function') throw new TypeError('registry.get must be a function');
		if (typeof onFinish !== 'function') throw new TypeError('onFinish must be a function');
		if (typeof trace !== 'function') throw new TypeError('trace must be a function');
		if (decorateObservation !== null && typeof decorateObservation !== 'function') throw new TypeError('decorateObservation must be a function');
		if (requestObservation !== null && typeof requestObservation !== 'function') throw new TypeError('requestObservation must be a function');
		if (inspectObservation !== null && typeof inspectObservation !== 'function') throw new TypeError('inspectObservation must be a function');
		if (notebook !== null && ['writeNote', 'query', 'recordReceipt'].some((method) => typeof notebook[method] !== 'function')) throw new TypeError('notebook must support writeNote, query, and recordReceipt');
		if (executionSettings !== null && typeof executionSettings !== 'function') throw new TypeError('executionSettings must be a function');
		if (memoryOperation !== null && typeof memoryOperation !== 'function') throw new TypeError('memoryOperation must be a function');
		if (programExecutor !== null && ['run', 'onObservation', 'cancel'].some((method) => typeof programExecutor?.[method] !== 'function')) throw new TypeError('programExecutor must support run, onObservation, and cancel');
		if (typeof sessionId !== 'string' || !/^[a-zA-Z0-9._-]{1,128}$/.test(sessionId)) throw new TypeError('sessionId must be 1..128 safe identifier characters');
		if (['ingest', 'candidates', 'load', 'flush', 'clear'].some((method) => typeof occupancy?.[method] !== 'function')) throw new TypeError('occupancy must support observed-memory lifecycle and candidate queries');
		this.#bridge = bridge;
		this.#registry = registry;
		this.#onFinish = onFinish;
		this.#trace = (event, fields) => {
			try {
				const completion = trace(event, fields);
				if (completion !== undefined) Promise.resolve(completion).catch(() => {});
			} catch { /* diagnostics cannot interrupt gameplay */ }
		};
		this.#decorateObservation = decorateObservation;
		this.#requestObservation = requestObservation;
		this.#inspectObservation = inspectObservation;
		this.#notebook = notebook;
		this.#executionSettings = executionSettings;
		this.#memoryOperation = memoryOperation;
		this.#programExecutor = programExecutor ?? new NativeProgramExecutor({ sessionId });
		this.#sessionId = sessionId.length <= 36 ? sessionId : createHash('sha256').update(sessionId).digest('hex').slice(0, 32);
		this.#occupancy = occupancy;
	}

	async initializeMemory(agentId) {
		if (this.#memoryReady.has(agentId)) return;
		let pending = this.#memoryLoads.get(agentId);
		if (pending === undefined) {
			pending = Promise.resolve().then(() => this.#occupancy.load(agentId)).then(() => {
				for (const observation of this.#pendingSpatial.get(agentId) ?? []) this.#occupancy.ingest(agentId, observation);
				this.#pendingSpatial.delete(agentId);
				this.#memoryReady.add(agentId);
			});
			this.#memoryLoads.set(agentId, pending);
		}
		try { await pending; }
		finally { if (this.#memoryLoads.get(agentId) === pending) this.#memoryLoads.delete(agentId); }
	}

	#rememberSpatial(agentId, observation) {
		if (this.#memoryReady.has(agentId)) { this.#occupancy.ingest(agentId, observation); return; }
		const queued = this.#pendingSpatial.get(agentId) ?? [];
		queued.push(observation);
		this.#pendingSpatial.set(agentId, queued.slice(-32));
		this.initializeMemory(agentId).catch((error) => this.#trace('native_spatial_memory_failed', { agentId, reasonCode: error?.code ?? 'MEMORY_LOAD_FAILED' }));
	}

	async #flushSpatial(agentId) {
		await this.initializeMemory(agentId);
		await this.#occupancy.flush(agentId);
	}

	updateObservation(record, observation, options = {}) {
		return this.#storeObservation(record, observation, options);
	}

	#storeObservation(record, observation, { eventSequence = 0, conversation = undefined, force = false, attention = false, priority, trigger } = {}, reuseWorldFacts = false) {
		validateRecord(record);
		if (!Number.isSafeInteger(eventSequence) || eventSequence < 0) throw new TypeError('eventSequence must be a nonnegative safe integer');
		if (force !== true && force !== false) throw new TypeError('force must be a boolean');
		const latest = this.#observations.get(record.agentId);
		if (!force && latest?.goalRevision === record.goalRevision && eventSequence <= latest.eventSequence) return false;
		const storedSequence = force && latest?.goalRevision === record.goalRevision
			? Math.max(eventSequence, latest.eventSequence)
			: eventSequence;
		const raw = mergeDeathObservation(observation ?? {}, this.#lastLive.get(record.agentId));
		// Keep one owned raw snapshot for both internal consumers. Public snapshot
		// methods still clone at their boundaries, so sharing here does not expose
		// mutable coordinator state while avoiding a duplicate deep copy per update.
		const storedObservation = structuredClone(raw);
		if (hasDurableObservationFacts(raw) && raw.ready !== false && raw.death == null && raw.status !== 'PLAYER_DEAD' && raw.player?.dead !== true) {
			this.#lastLive.set(record.agentId, {
				observation: storedObservation,
				eventSequence: storedSequence,
				goalRevision: record.goalRevision,
			});
		}
		if (!reuseWorldFacts) {
			this.#recovery.remember(record.agentId, record.goalRevision, raw);
			this.#rememberSpatial(record.agentId, storedObservation);
		}
		this.#observations.set(record.agentId, {
			goalRevision: record.goalRevision,
			eventSequence: storedSequence,
			goal: record.currentGoal ?? null,
			goalSpec: record.currentGoalSpec ?? null,
			observation: storedObservation,
			...(conversation === undefined ? {} : { conversation: structuredClone(conversation) }),
		});
		this.#programExecutor.onObservation(record, { observation: storedObservation, eventSequence: storedSequence, attention, ...(priority === undefined ? {} : { priority }), ...(trigger === undefined ? {} : { trigger }) });
		return true;
	}

	/**
	 * Reuses durable facts when the actionable signature matches, but refreshes raw
	 * observations because clocks, effect durations, and cooldowns are excluded from it.
	 */
	refreshObservation(record, observation, { eventSequence = 0, conversation = undefined } = {}) {
		validateRecord(record);
		if (!Number.isSafeInteger(eventSequence) || eventSequence < 0) throw new TypeError('eventSequence must be a nonnegative safe integer');
		const latest = this.#observations.get(record.agentId);
		if (latest === undefined || latest.goalRevision !== record.goalRevision || eventSequence <= latest.eventSequence) return false;
		return this.#storeObservation(record, observation, {
			eventSequence,
			conversation: conversation ?? latest.conversation,
		}, true);
	}

	snapshotLive(agentId) {
		const live = this.#lastLive.get(agentId);
		return live === undefined ? null : structuredClone(live);
	}

	decorateObservation(record, observation = {}) {
		validateRecord(record);
		const latest = this.#observations.get(record.agentId);
		const cached = latest?.goalRevision === record.goalRevision ? latest.observation : undefined;
		const live = this.#lastLive.get(record.agentId)?.observation;
		const source = resolveDecorateSource(observation, cached, live);
		// Cached and live snapshots share one owned raw object. Give callers an
		// isolated view when decoration falls back to either store (including death
		// merging), while the normal durable-observation path remains allocation-free.
		const ownedSource = source === cached || source === live || isSparseDeathObservation(observation)
			? structuredClone(source)
			: source;
		if (hasDurableObservationFacts(observation) && !isSparseDeathObservation(observation)) {
			this.#recovery.remember(record.agentId, record.goalRevision, observation);
		} else if (isSparseDeathObservation(observation) && hasDurableObservationFacts(source)) {
			this.#recovery.remember(record.agentId, record.goalRevision, source);
		}
		return composeTwoCallView(ownedSource, this.#recovery.snapshot(record.agentId, ownedSource), {
			occupancy: this.#occupancy,
			agentId: record.agentId,
			goal: record.currentGoal ?? record.currentGoalSpec?.originalRequest ?? null,
		});
	}

	hasCurrent(record) {
		const latest = this.#observations.get(record.agentId);
		return latest?.goalRevision === record.goalRevision;
	}

	async execute(request, record, { lifecycleGeneration = null } = {}) {
		validateRecord(record);
		validateRequest(request, record);
		if (lifecycleGeneration !== null && (!Number.isSafeInteger(lifecycleGeneration) || lifecycleGeneration < 0)) throw new TypeError('lifecycleGeneration must be a nonnegative safe integer or null');
		if (request.tool.kind === 'observe') return this.#observe(record);
		if (request.tool.kind === 'inspect') return this.#inspect(request.tool, record);
		if (request.tool.kind === 'capabilities') {
			if (request.tool.section === 'program') return { ...minecraftCapabilities({ section: 'program' }), ...await this.#executionMetadata(record) };
			return { ...minecraftCapabilities(), ...await this.#executionMetadata(record), ...await this.#memorySummary(record), runtime: { freshObservations: this.#requestObservation !== null, focusedInspection: this.#inspectObservation !== null, notebook: this.#notebook !== null || this.#memoryOperation !== null, asynchronousActions: true, cancellation: true, reactivePrograms: { available: true, engine: 'ArenaScript', modelAuthored: true, plannerCalls: false } } };
		}
		if (request.tool.kind === 'action_status') return this.#actionStatus(record, request.tool.actionId);
		if (request.tool.kind === 'cancel_action') return this.#cancelAction(record, request.tool);
		if (request.tool.kind === 'notebook' || request.tool.kind === 'query_memory') return this.#memory(request, record);
		if (this.#programRuns.has(record.agentId)) throw codedError('NATIVE_PROGRAM_IN_PROGRESS', 'A model-authored program already owns this player; cancel its exact active action before issuing another body operation');
		if (request.tool.kind === 'run_program') return this.#runProgram(request, record);
		if (request.tool.kind === 'replace_action') {
			const epoch = this.#executionEpoch(record.agentId);
			const cancelled = await this.#cancelAction(record, request.tool);
			if (this.#executionEpoch(record.agentId) !== epoch + 1) throw codedError('STALE_NATIVE_TOOL', 'Lifecycle changed while cancelling the replaced action');
			if (cancelled.state !== 'CANCELLED') return { state: 'REPLACEMENT_NOT_STARTED', reasonCode: 'ACTION_FINISHED_BEFORE_CANCEL', previous: cancelled };
			return this.#executeAction(request, record, { ...request.tool, kind: 'action' });
		}
		if (request.tool.kind === 'start_action') return this.#executeAction(request, record, { ...request.tool, kind: 'action' }, null, false);
		if (request.tool.kind === 'finish') return this.#finish(request, record, lifecycleGeneration);
		if (request.tool.kind === 'explore_frontier') return this.#exploreFrontier(request, record);
		const tool = constrainGoalBoundNavigation(request.tool, record.currentGoalSpec);
		if (tool.kind === 'sequence') return this.#executeSequence({ ...request, tool }, record, this.#executionEpoch(record.agentId));
		if (tool.kind === 'lookAround') return this.#executeLookAround(request, record, tool, this.#executionEpoch(record.agentId));
		if (tool.kind !== 'action') throw codedError('INVALID_NATIVE_TOOL', 'Native tool did not normalize to an action');
		return this.#executeAction(request, record, tool);
	}

	async #observe(record) {
		const epoch = this.#executionEpoch(record.agentId);
		await this.initializeMemory(record.agentId);
		if (this.#executionEpoch(record.agentId) !== epoch) throw codedError('STALE_NATIVE_TOOL', 'Observation request outlived its lifecycle');
		const previous = this.#observations.get(record.agentId);
		const afterEventSequence = previous?.goalRevision === record.goalRevision ? previous.eventSequence : 0;
		let freshness = { fresh: false, reasonCode: 'FRESH_OBSERVATION_UNAVAILABLE' };
		if (this.#requestObservation !== null) {
			const sampled = await this.#requestObservation(record, { afterEventSequence });
			this.#assertCurrent(record);
			if (this.#executionEpoch(record.agentId) !== epoch) throw codedError('STALE_NATIVE_TOOL', 'Observation request outlived its lifecycle');
			if (!Number.isSafeInteger(sampled?.eventSequence) || sampled.eventSequence <= afterEventSequence || sampled.observation === null || typeof sampled.observation !== 'object' || Array.isArray(sampled.observation)) throw codedError('FRESH_OBSERVATION_REQUIRED', 'Observation callback did not return a newer server sample');
			this.updateObservation(record, sampled.observation, { eventSequence: sampled.eventSequence, conversation: sampled.conversation });
			freshness = { fresh: true, afterEventSequence };
		}
		const latest = this.#observations.get(record.agentId);
		const facts = latest?.goalRevision === record.goalRevision
			? structuredClone(latest)
			: { eventSequence: 0, goal: record.currentGoal ?? null, goalSpec: record.currentGoalSpec ?? null, observation: {} };
		delete facts.goalRevision;
		facts.observation = this.#decorate(record, facts.observation ?? {});
		return { ...facts, ...await this.#executionMetadata(record), ...await this.#memorySummary(record), freshness: { ...freshness, eventSequence: facts.eventSequence, observedAtEpochMs: facts.observation.observedAtEpochMs ?? null, ...(facts.observation.continuity?.rememberedSections === undefined ? {} : { rememberedSections: [...facts.observation.continuity.rememberedSections] }) } };
	}

	async #memorySummary(record) {
		const worldId = this.#worldId(record);
		if (worldId === null || typeof this.#notebook?.listUnresolved !== 'function') return {};
		const page = await this.#notebook.listUnresolved(record.agentId, { worldId, offset: 0, limit: 4 });
		this.#assertCurrent(record);
		return { unresolvedActions: { worldId, total: page.total, nextOffset: page.nextOffset, ...(page.evictedReceipts === undefined ? {} : { evictedReceipts: page.evictedReceipts }), entries: (page.entries ?? []).map(({ actionId, actionType, goalRevision, state, reasonCode }) => ({ actionId, actionType, goalRevision, state, reasonCode })), historical: true, query: { kind: 'unresolved', offset: 0, limit: 20 } } };
	}

	async #executionMetadata(record) {
		if (this.#executionSettings === null) return {};
		const executionSettings = await this.#executionSettings(record);
		this.#assertCurrent(record);
		return { executionSettings: structuredClone(executionSettings) };
	}

	async #runProgram(request, record) {
		if (this.#actions.has(record.agentId) || this.#completions.has(record.agentId)) throw codedError('NATIVE_ACTION_IN_PROGRESS', 'An action or completion verification already owns this player');
		const latest = this.#observations.get(record.agentId);
		if (latest?.goalRevision !== record.goalRevision) throw codedError('CURRENT_OBSERVATION_REQUIRED', 'A current player observation is required before running a program');
		const run = { epoch: this.#executionEpoch(record.agentId), request };
		this.#programRuns.set(record.agentId, run);
		try {
			return await this.#programExecutor.run(record, { source: request.tool.source, maxActions: request.tool.maxActions, timeoutMs: request.tool.timeoutMs, provenance: nativeMemoryProvenance(request, record) }, {
				observation: structuredClone(latest.observation), eventSequence: latest.eventSequence,
				executeAction: async (command) => {
					if (this.#programRuns.get(record.agentId) !== run || this.#executionEpoch(record.agentId) !== run.epoch) throw codedError('NATIVE_PROGRAM_CANCELLED', 'Program no longer has execution authority');
					const result = await this.#executeAction(request, record, { kind: 'action', actionType: command.action.type, arguments: command.action.arguments }, null, true, command);
					const receipt = this.#receipts.get(record.agentId)?.findLast((entry) => entry.engineActionId === command.actionId);
					return { ...result, ...(receipt === undefined ? {} : { actionId: receipt.actionId }) };
				},
				cancelAction: (commandId) => {
					const active = this.#actions.get(record.agentId);
					if (active?.engineActionId !== commandId) return { state: 'NO_MATCHING_ACTION' };
					if (active.cancelling) return active.result;
					return this.#cancelAction(record, { actionId: active.actionId, goalRevision: active.goalRevision }, { invalidateProgram: false });
				},
				inspect: async (query) => ({ state: 'SUCCEEDED', reasonCode: 'INSPECTED', ...await this.#inspect(normalizeMinecraftToolCall('inspect', query), record) }),
				refreshObservation: async () => {
					const facts = await this.#observe(record);
					if (facts.freshness.fresh !== true) throw codedError('FRESH_OBSERVATION_REQUIRED', 'Program continuation needs a new authoritative player observation');
					return { observation: facts.observation, eventSequence: facts.eventSequence };
				},
				memoryOperation: (operation) => this.#programMemory(request, record, operation),
			});
		} finally { if (this.#programRuns.get(record.agentId) === run) this.#programRuns.delete(record.agentId); }
	}

	async #programMemory(request, record, operation) {
		if (this.#memoryOperation !== null) return this.#memoryOperation(record, operation);
		if (this.#notebook === null) throw codedError('MEMORY_UNAVAILABLE', 'Durable agent memory is unavailable');
		const worldId = this.#worldId(record);
		if (worldId === null) throw codedError('WORLD_ID_REQUIRED', 'A current observed world identity is required for durable memory');
		const tool = normalizeMinecraftToolCall(operation.operation === 'write' ? 'notebook' : 'queryMemory', operation.arguments);
		if (tool.kind === 'notebook') return { state: 'SUCCEEDED', reasonCode: 'NOTE_WRITTEN', note: await this.#notebook.writeNote(record.agentId, { worldId, key: tool.key, text: tool.text, goalRevision: record.goalRevision, provenance: operation.provenance ?? nativeMemoryProvenance(request, record) }) };
		return { state: 'SUCCEEDED', reasonCode: 'MEMORY_QUERIED', ...await this.#notebook.query(record.agentId, { worldId, kind: tool.memoryKind, offset: tool.offset, limit: tool.limit, ...(tool.text === undefined ? {} : { text: tool.text }) }) };
	}

	async #inspect(tool, record) {
		if (this.#inspectObservation === null) throw codedError('INSPECTION_UNAVAILABLE', 'Focused server inspection is unavailable; observe returns its explicit coverage limits');
		const epoch = this.#executionEpoch(record.agentId);
		const { kind: _kind, ...query } = tool;
		const result = JSON.parse(toolResultContent(await this.#inspectObservation(record, query)).contentItems[0].text);
		this.#assertCurrent(record);
		if (this.#executionEpoch(record.agentId) !== epoch) throw codedError('STALE_NATIVE_TOOL', 'Inspection request outlived its lifecycle');
		if (Number.isSafeInteger(result?.eventSequence) && result.eventSequence >= 0 && tool.section === 'entities') {
			const targets = this.#inspectedTargets.get(record.agentId) ?? new Map();
			for (const entry of result.entries ?? []) {
				const targetId = entry.uuid ?? entry.stableId;
				if (typeof targetId !== 'string') continue;
				targets.delete(targetId);
				targets.set(targetId, { goalRevision: record.goalRevision, eventSequence: result.eventSequence });
			}
			while (targets.size > 128) targets.delete(targets.keys().next().value);
			this.#inspectedTargets.set(record.agentId, targets);
		}
		return structuredClone(result);
	}

	#actionStatus(record, actionId) {
		const active = this.#actions.get(record.agentId);
		if (active?.goalRevision === record.goalRevision && (actionId === undefined || active.actionId === actionId)) {
			return { actionId: active.actionId, goalRevision: active.goalRevision, actionType: active.actionType, state: !active.dispatched ? 'PREPARING' : active.cancelling ? 'CANCELLING' : active.cancellationUncertain ? 'CANCELLATION_UNCONFIRMED' : 'RUNNING', ...(active.progress === undefined ? {} : { progress: structuredClone(active.progress) }) };
		}
		if (actionId !== undefined) {
			const receipt = this.#receipts.get(record.agentId)?.findLast((entry) => entry.actionId === actionId && entry.goalRevision === record.goalRevision);
			return receipt === undefined ? { state: 'UNKNOWN_ACTION', actionId, goalRevision: record.goalRevision } : structuredClone(receipt);
		}
		const lastResult = this.#receipts.get(record.agentId)?.findLast((entry) => entry.goalRevision === record.goalRevision);
		return { state: 'IDLE', goalRevision: record.goalRevision, ...(lastResult === undefined ? {} : { lastResult: structuredClone(lastResult) }) };
	}

	#retainReceipt(record, active, result) {
		const existing = this.#receipts.get(record.agentId)?.findLast((entry) => entry.actionId === active.actionId);
		if (existing?.source === 'server_action_result' && result.source !== 'server_action_result') return existing;
		const receipt = { actionId: active.actionId, goalRevision: active.goalRevision, actionType: active.actionType, ...(active.engineActionId === undefined ? {} : { engineActionId: active.engineActionId }), ...result };
		const receipts = (this.#receipts.get(record.agentId) ?? []).filter((entry) => entry.actionId !== active.actionId);
		receipts.push(receipt);
		this.#receipts.set(record.agentId, receipts.slice(-64));
		return receipt;
	}

	async #journal(method, agentId, active, details = {}) {
		if (active.worldId == null || typeof this.#notebook?.[method] !== 'function') return;
		return this.#notebook[method](agentId, { worldId: active.worldId, actionId: active.actionId, goalRevision: active.goalRevision, actionType: active.actionType, ...details });
	}

	async #cancelAction(record, tool, { invalidateProgram = true } = {}) {
		const active = this.#actions.get(record.agentId);
		if (tool.goalRevision !== record.goalRevision || active?.goalRevision !== tool.goalRevision || active.actionId !== tool.actionId) throw codedError('STALE_ACTION', 'Cancellation handle does not match the active action');
		if (active.cancelling) throw codedError('CANCELLATION_IN_PROGRESS', 'The exact action is already being cancelled');
		active.cancelling = true;
		active.cancellationUncertain = false;
		if (invalidateProgram) {
			this.#executionEpochs.set(record.agentId, this.#executionEpoch(record.agentId) + 1);
			if (this.#programRuns.has(record.agentId)) Promise.resolve(this.#programExecutor.cancel(record.agentId, 'MODEL_CANCELLED')).catch((error) => this.#trace('native_program_cancel_failed', { agentId: record.agentId, reasonCode: error?.code ?? 'PROGRAM_CANCEL_FAILED' }));
		}
		if (!active.dispatched) {
			active.cancelledBeforeDispatch = true;
			this.#actions.delete(record.agentId);
			const result = { state: 'CANCELLED', reasonCode: 'CANCELLED_BEFORE_DISPATCH', executionStarted: false, physicalAttempted: false, source: 'coordinator_before_dispatch' };
			this.#retainReceipt(record, active, result);
			active.resolve(result);
			await this.#journal('recordUnknown', record.agentId, active, { reasonCode: 'CANCELLED_BEFORE_DISPATCH' });
			return result;
		}
		try {
			await this.#bridge.send('action_cancel', record.agentId, { goalRevision: active.goalRevision, actionId: active.actionId });
		} catch (error) {
			active.cancelling = false;
			throw error;
		}
		try {
			return await withDeadline(active.result, 10_000, 'CANCEL_ACK_TIMEOUT', 'Cancellation has no authoritative acknowledgement; the action may still be running');
		} catch (error) {
			active.cancelling = false;
			active.cancellationUncertain = error?.code === 'CANCEL_ACK_TIMEOUT';
			throw error;
		}
	}

	async #memory(request, record) {
		const tool = request.tool;
		if (this.#memoryOperation !== null) {
			const operation = tool.kind === 'notebook' ? 'write' : 'query';
			const args = operation === 'write' ? { key: tool.key, text: tool.text } : { kind: tool.memoryKind, offset: tool.offset ?? 0, limit: tool.limit, ...(tool.text === undefined ? {} : { text: tool.text }) };
			return this.#memoryOperation(record, { operation, arguments: args, provenance: nativeMemoryProvenance(request, record) });
		}
		if (this.#notebook === null) throw codedError('MEMORY_UNAVAILABLE', 'Durable agent memory is unavailable');
		const worldId = this.#worldId(record);
		if (worldId === null) throw codedError('WORLD_ID_REQUIRED', 'A current observed world identity is required for durable memory');
		if (tool.kind === 'notebook') return this.#notebook.writeNote(record.agentId, { worldId, key: tool.key, text: tool.text, goalRevision: record.goalRevision, provenance: nativeMemoryProvenance(request, record) });
		return this.#notebook.query(record.agentId, { worldId, kind: tool.memoryKind, offset: tool.offset ?? 0, limit: tool.limit, ...(tool.text === undefined ? {} : { text: tool.text }) });
	}

	#worldId(record) {
		const latest = this.#observations.get(record.agentId);
		const worldId = latest?.goalRevision === record.goalRevision ? observationWorldId(latest.observation) : null;
		return typeof worldId === 'string' && worldId.length > 0 ? worldId : null;
	}

	#assertCurrent(record) {
		const current = this.#registry?.get(record.agentId);
		if (this.#registry !== null && (current == null || current.goalRevision !== record.goalRevision)) throw codedError('STALE_NATIVE_TOOL', 'Agent goal changed while waiting for tool data');
	}

	#decorate(record, observation = {}) {
		if (this.#decorateObservation !== null) return this.#decorateObservation(record, observation);
		return this.decorateObservation(record, observation);
	}

	async #exploreFrontier(request, record) {
		const facts = await this.#observe(record);
		await this.#flushSpatial(record.agentId);
		return { ...this.#occupancy.candidates(record.agentId, facts.observation, request.tool.arguments ?? {}), freshness: facts.freshness };
	}

	async #executeLookAround(request, record, tool, executionEpoch) {
		const source = this.#observations.get(record.agentId)?.observation ?? {};
		const input = source.interaction?.input ?? {};
		const selectedSlot = Number.isSafeInteger(input.selectedSlot) && input.selectedSlot >= 0 && input.selectedSlot <= 8
			? input.selectedSlot : 0;
		const hand = input.hand === 'off_hand' ? 'off' : 'main';
		const results = [];
		for (let index = 0; index < tool.steps; index += 1) {
			if (this.#executionEpoch(record.agentId) !== executionEpoch) throw codedError('NATIVE_ACTION_CANCELLED', 'Camera sweep cancelled before its next step');
			const action = {
				kind: 'action',
				actionType: 'control',
				arguments: {
					forward: 0, strafe: 0, jump: false, sneak: false, sprint: false,
					attack: false, use: false,
					yaw: wrapDegrees(tool.centerYaw + ((index + 1) * 360) / tool.steps),
					pitch: tool.pitch, selectedSlot, hand, ticks: tool.ticksPerStep,
				},
			};
			const result = await this.#executeAction(request, record, action, index);
			results.push({ actionType: action.actionType, ...result });
			if (result.state !== 'SUCCEEDED') return { state: result.state, completed: results.length, failedAt: index, results };
		}
		return { state: 'SUCCEEDED', completed: results.length, results };
	}

	async #executeSequence(request, record, executionEpoch) {
		const results = [];
		for (let index = 0; index < request.tool.actions.length; index += 1) {
			if (this.#executionEpoch(record.agentId) !== executionEpoch) throw codedError('NATIVE_ACTION_CANCELLED', 'Native sequence cancelled before its next action');
			const action = request.tool.actions[index];
			const result = await this.#executeAction(request, record, { kind: 'action', ...action }, index);
			results.push({ actionType: action.actionType, ...result });
			if (result.state !== 'SUCCEEDED') return { state: result.state, completed: results.length, failedAt: index, results };
		}
		return { state: 'SUCCEEDED', completed: results.length, results };
	}

	async #executeAction(request, record, tool, sequenceIndex = null, waitForCompletion = true, programCommand = null) {
		if (this.#actions.has(record.agentId)) throw codedError('NATIVE_ACTION_IN_PROGRESS', 'The Minecraft body is already executing an action');
		if (this.#completions.has(record.agentId)) throw codedError('NATIVE_COMPLETION_IN_PROGRESS', 'Goal completion verification is already running');

		const ordinal = ++this.#sequence;
		const identity = `${this.#sessionId}:${ordinal}:${safeSegment(record.agentId).slice(0, 32)}:${record.goalRevision}`;
		const traceId = validateTraceId(`native-${identity.replaceAll(':', '-')}`);
		const actionId = `native:${identity}`;
		const targetId = tool.arguments?.targetId ?? tool.arguments?.targetSelector;
		const latest = this.#observations.get(record.agentId);
		const currentTarget = latest?.goalRevision === record.goalRevision
			&& !latest.observation.continuity?.rememberedSections?.includes('entities')
			&& latest.observation.entities?.some((entry) => (entry.uuid ?? entry.stableId) === targetId) === true;
		const inspected = currentTarget ? undefined : this.#inspectedTargets.get(record.agentId)?.get(targetId);
		const eventSequence = inspected?.goalRevision === record.goalRevision ? inspected.eventSequence : latest?.eventSequence ?? 0;
		const payload = {
			traceId,
			goalRevision: record.goalRevision,
			actionId,
			actionType: tool.actionType,
			arguments: structuredClone(tool.arguments),
			provenance: {
				provider: record.provider,
				model: record.model,
				reasoningEffort: record.reasoningEffort,
				serviceTier: record.serviceTier ?? 'priority',
				traceId,
				programId: `native-${safeSegment(request.turnId)}`.slice(0, 256),
				programVersion: 1,
				sourceStepId: `${safeSegment(request.callId)}${sequenceIndex === null ? '' : `:${sequenceIndex + 1}`}`.slice(0, 256),
				eventSequence,
			},
		};
		if (programCommand !== null) {
			const provenance = programCommand.provenance;
			payload.provenance = { provider: provenance.provider, model: provenance.model ?? provenance.modelIdentity, reasoningEffort: provenance.reasoningEffort, serviceTier: provenance.serviceTier, traceId: provenance.traceId, programId: provenance.programId, programVersion: provenance.version, sourceStepId: provenance.stepId, eventSequence: inspected?.goalRevision === record.goalRevision ? inspected.eventSequence : provenance.authorizingEventSequence ?? provenance.eventSequence, ...(provenance.watcherId == null ? {} : { watcherId: provenance.watcherId }) };
		}
		let resolveAction;
		let rejectAction;
		const result = new Promise((resolve, reject) => { resolveAction = resolve; rejectAction = reject; });
		result.catch(() => {});
		const active = { actionId, goalRevision: record.goalRevision, actionType: tool.actionType, arguments: structuredClone(tool.arguments), worldId: this.#worldId(record), result, resolve: resolveAction, reject: rejectAction, dispatched: false, ...(programCommand === null ? {} : { engineActionId: programCommand.actionId }) };
		this.#actions.set(record.agentId, active);
		const failPublication = async (error) => {
			if (this.#actions.get(record.agentId) === active) this.#actions.delete(record.agentId);
			const completed = this.#receipts.get(record.agentId)?.findLast((entry) => entry.actionId === actionId && entry.source === 'server_action_result');
			if (completed !== undefined) return;
			const reasonCode = active.dispatched ? 'DISPATCH_RESULT_UNKNOWN' : 'DISPATCH_NOT_SENT';
			this.#retainReceipt(record, active, { state: 'UNKNOWN', reasonCode, source: 'coordinator_uncertain', worldId: active.worldId });
			try { await this.#journal('recordUnknown', record.agentId, active, { reasonCode }); }
			catch (journalError) { this.#trace('native_receipt_persistence_failed', { agentId: record.agentId, actionId, reasonCode: journalError?.code ?? 'MEMORY_WRITE_FAILED' }); }
			Object.assign(error, { actionId, goalRevision: record.goalRevision });
			active.publicationError = error;
			rejectAction(error);
		};
		let publication = Promise.resolve();
		this.#trace('native_tool_dispatch_started', { agentId: record.agentId, goalRevision: record.goalRevision, traceId, actionId, actionType: payload.actionType });
		try {
			if (typeof this.#notebook?.recordDispatch === 'function' && active.worldId !== null) await this.#journal('recordDispatch', record.agentId, active, { arguments: active.arguments });
			if (this.#actions.get(record.agentId) !== active) return waitForCompletion ? result : this.#actionStatus(record, actionId);
			const current = this.#registry?.get(record.agentId);
			if (this.#registry !== null && (current === null || current === undefined || current.goalRevision !== record.goalRevision)) {
				throw codedError('STALE_PLAN', 'Native action became stale before bridge send');
			}
			active.dispatched = true;
			publication = Promise.resolve(this.#bridge.send('action_command', record.agentId, payload)).then(() => {
				this.#trace('native_tool_command_sent', { agentId: record.agentId, goalRevision: record.goalRevision, traceId, actionId, actionType: payload.actionType });
			}, failPublication);
		} catch (error) {
			await failPublication(error);
		}
		if (!waitForCompletion) {
			await Promise.race([publication, result]);
			if (active.publicationError !== undefined) throw active.publicationError;
		}
		return waitForCompletion ? result : this.#actionStatus(record, actionId);
	}

	onActionProgress(record, payload = {}) {
		const active = this.#actions.get(record.agentId);
		if (active === undefined || active.goalRevision !== record.goalRevision || active.actionId !== payload.actionId) return false;
		if (payload.goalRevision !== undefined && payload.goalRevision !== active.goalRevision) return false;
		const observation = payload.actionObservation === undefined ? undefined : structuredClone(payload.actionObservation);
		active.progress = { ...(payload.progress === undefined ? {} : { value: payload.progress }), ...(payload.elapsedMs === undefined ? {} : { elapsedMs: payload.elapsedMs }), ...(observation === undefined ? {} : { actionObservation: observation }) };
		this.#trace('native_tool_action_progress', {
			agentId: record.agentId,
			goalRevision: record.goalRevision,
			actionId: active.actionId,
			...(payload.progress === undefined ? {} : { progress: payload.progress }),
			...(payload.elapsedMs === undefined ? {} : { elapsedMs: payload.elapsedMs }),
			...(observation === undefined ? {} : { actionObservation: observation }),
		});
		return true;
	}

	onActionResult(record, payload = {}) {
		if (!TERMINAL_ACTION_STATES.has(payload.state)) return false;
		let active = this.#actions.get(record.agentId);
		if (active === undefined || active.actionId !== payload.actionId) {
			const unresolved = this.#receipts.get(record.agentId)?.findLast((entry) => entry.actionId === payload.actionId && entry.goalRevision === record.goalRevision && entry.state === 'UNKNOWN');
			active = unresolved === undefined ? undefined : { ...unresolved, resolve: () => {} };
		}
		if (active === undefined || active.goalRevision !== record.goalRevision || active.actionId !== payload.actionId) return false;
		if (payload.goalRevision !== undefined && payload.goalRevision !== active.goalRevision) return false;
		if (this.#actions.get(record.agentId) === active) this.#actions.delete(record.agentId);
		const observation = payload.actionObservation;
		const recovery = hasAuthoritativeActionObservation(observation)
			? this.#recovery.snapshot(record.agentId, observation)
			: null;
		const state = String(payload.state ?? 'FAILED').slice(0, 64);
		const reasonCode = String(payload.reasonCode ?? '').slice(0, 128);
		const failureClass = classifyBodyFailure(reasonCode, state);
		const result = {
			state,
			reasonCode,
			...(payload.message === undefined ? {} : { message: String(payload.message).slice(0, 2_048) }),
			...(payload.executionStarted === undefined ? {} : { executionStarted: payload.executionStarted === true }),
			...(payload.physicalAttempted === undefined ? {} : { physicalAttempted: payload.physicalAttempted === true }),
			...(payload.actionObservation === undefined ? {} : { actionObservation: structuredClone(payload.actionObservation) }),
			...(recovery === null ? {} : { recovery }),
			...(failureClass === null ? {} : { failureClass }),
		};
		this.#retainReceipt(record, active, { ...result, source: 'server_action_result' });
		if (this.#notebook !== null && active.worldId != null) {
			try {
				Promise.resolve(this.#notebook.recordReceipt(record.agentId, terminalReceipt(active, payload))).catch((error) => this.#trace('native_receipt_persistence_failed', { agentId: record.agentId, actionId: active.actionId, reasonCode: error?.code ?? 'MEMORY_WRITE_FAILED' }));
			} catch (error) { this.#trace('native_receipt_persistence_failed', { agentId: record.agentId, actionId: active.actionId, reasonCode: error?.code ?? 'MEMORY_WRITE_FAILED' }); }
		}
		this.#flushSpatial(record.agentId).catch((error) => this.#trace('native_spatial_memory_failed', { agentId: record.agentId, reasonCode: error?.code ?? 'MEMORY_WRITE_FAILED' }));
		this.#trace('native_tool_action_completed', { agentId: record.agentId, goalRevision: record.goalRevision, actionId: active.actionId, ...result });
		active.resolve(result);
		return true;
	}

	async reconcileActionReceipt(agentId, payload = {}) {
		if (typeof payload.actionId !== 'string' || !payload.actionId.startsWith('native:') || !TERMINAL_ACTION_STATES.has(payload.state) || typeof this.#notebook?.findReceipt !== 'function') return false;
		const existing = await this.#notebook.findReceipt(agentId, { actionId: payload.actionId });
		if (existing === null || existing.goalRevision !== payload.goalRevision || payload.actionType !== undefined && existing.actionType !== payload.actionType) return false;
		await this.#notebook.recordReceipt(agentId, terminalReceipt(existing, payload));
		return true;
	}

	isActionResultStale(record, payload = {}) {
		const stale = this.#staleActions.get(record.agentId);
		return stale?.has(actionResultKey(payload.goalRevision, payload.actionId)) === true;
	}

	onCompletionResult(record, payload = {}) {
		const active = this.#completions.get(record.agentId);
		if (active === undefined || active.goalRevision !== record.goalRevision) return false;
		if (payload.traceId !== active.traceId || payload.goalFingerprint !== active.goalFingerprint) return false;
		this.#completions.delete(record.agentId);
		active.resolve({
			state: payload.verified === true ? 'COMPLETED' : 'ACTIVE',
			verified: payload.verified === true,
			reasonCode: String(payload.reasonCode ?? '').slice(0, 128),
			facts: structuredClone(Array.isArray(payload.facts) ? payload.facts : []),
		});
		return true;
	}

	async dispose(agentId, reason = 'disposed') {
		this.#executionEpochs.set(agentId, this.#executionEpoch(agentId) + 1);
		this.#programRuns.delete(agentId);
		Promise.resolve(this.#programExecutor.cancel(agentId, reason)).catch((error) => this.#trace('native_program_cancel_failed', { agentId, reasonCode: error?.code ?? 'PROGRAM_CANCEL_FAILED' }));
		// Physical authority is released before waiting on persistence below.
		if (FORGET_REASONS.test(String(reason))) {
			this.#recovery.forget(agentId);
			this.#lastLive.delete(agentId);
			this.#receipts.delete(agentId);
		}
		this.#observations.delete(agentId);
		this.#inspectedTargets.delete(agentId);
		const active = this.#actions.get(agentId);
		const completion = this.#completions.get(agentId);
		if (completion !== undefined) {
			this.#completions.delete(agentId);
			completion.reject(codedError('NATIVE_COMPLETION_CANCELLED', `Native completion cancelled: ${String(reason).slice(0, 128)}`));
		}
		if (active !== undefined) {
			this.#actions.delete(agentId);
			this.#rememberStaleAction(agentId, active);
			active.reject(codedError('NATIVE_ACTION_CANCELLED', `Native action cancelled: ${String(reason).slice(0, 128)}`));
			if (active.dispatched && !active.cancelling) {
				try { await this.#bridge.send('action_cancel', agentId, { goalRevision: active.goalRevision, actionId: active.actionId }); }
				catch {}
			}
			try { await this.#journal('recordUnknown', agentId, active, { reasonCode: active.dispatched ? 'LIFECYCLE_ENDED_BEFORE_RESULT' : 'CANCELLED_BEFORE_DISPATCH' }); }
			catch (error) { this.#trace('native_receipt_persistence_failed', { agentId, actionId: active.actionId, reasonCode: error?.code ?? 'MEMORY_WRITE_FAILED' }); }
		}
		try { await this.#flushSpatial(agentId); }
		catch (error) { this.#trace('native_spatial_memory_failed', { agentId, reasonCode: error?.code ?? 'MEMORY_WRITE_FAILED' }); }
		if (FORGET_REASONS.test(String(reason))) {
			this.#occupancy.clear(agentId);
			this.#memoryReady.delete(agentId);
			this.#pendingSpatial.delete(agentId);
		}
		return active !== undefined || completion !== undefined;
	}

	#executionEpoch(agentId) { return this.#executionEpochs.get(agentId) ?? 0; }

	#rememberStaleAction(agentId, active) {
		let stale = this.#staleActions.get(agentId);
		if (stale === undefined) {
			stale = new Set();
			this.#staleActions.set(agentId, stale);
		}
		stale.add(actionResultKey(active.goalRevision, active.actionId));
		while (stale.size > 32) stale.delete(stale.values().next().value);
	}

	async disposeAll(reason = 'coordinator_stopped') {
		const agentIds = new Set([
			...this.#observations.keys(),
			...this.#actions.keys(),
			...this.#completions.keys(),
			...this.#lastLive.keys(),
		]);
		await Promise.allSettled([...agentIds].map((agentId) => this.dispose(agentId, reason)));
		if (FORGET_REASONS.test(String(reason))) this.#recovery.clear();
	}

	async #finish(request, record, lifecycleGeneration) {
		if (this.#actions.has(record.agentId)) throw codedError('NATIVE_ACTION_IN_PROGRESS', 'The Minecraft body is already executing an action');
		if (this.#completions.has(record.agentId)) throw codedError('NATIVE_COMPLETION_IN_PROGRESS', 'Goal completion verification is already running');
		const goalFingerprint = record.currentGoalSpec?.fingerprint;
		if (typeof goalFingerprint !== 'string' || !/^[0-9a-f]{64}$/.test(goalFingerprint)) {
			throw codedError('GOAL_SPEC_REQUIRED', 'Minecraft has not supplied an immutable goal specification');
		}
		const ordinal = ++this.#sequence;
		const traceId = validateTraceId(`native-complete-${this.#sessionId}-${ordinal}-${safeSegment(record.agentId).slice(0, 32)}-${record.goalRevision}`);
		const profile = {
			provider: record.provider,
			model: record.model,
			reasoningEffort: record.reasoningEffort,
			serviceTier: record.serviceTier ?? 'priority',
		};
		let resolveCompletion;
		let rejectCompletion;
		const completion = new Promise((resolve, reject) => { resolveCompletion = resolve; rejectCompletion = reject; });
		const pending = {
			goalRevision: record.goalRevision,
			traceId,
			goalFingerprint,
			resolve: resolveCompletion,
			reject: rejectCompletion,
		};
		this.#completions.set(record.agentId, pending);
		const failPublication = (error) => {
			if (this.#completions.get(record.agentId) === pending) this.#completions.delete(record.agentId);
			rejectCompletion(error);
		};
		try {
			Promise.resolve(this.#bridge.send('goal_completed', record.agentId, {
				goalRevision: record.goalRevision,
				goalFingerprint,
				traceId,
				profile,
			})).catch(failPublication);
		} catch (error) {
			failPublication(error);
		}
		const result = await completion;
		if (result.verified) await this.#onFinish({ record, request, result, lifecycleGeneration });
		return result;
	}
}

export function constrainGoalBoundNavigation(tool, goalSpec) {
	if (tool?.kind === 'sequence') {
		return { ...tool, actions: tool.actions.map((action) => constrainNavigationAction(action, goalSpec)) };
	}
	if (tool?.kind === 'action') return constrainNavigationAction(tool, goalSpec);
	return tool;
}

function constrainNavigationAction(action, goalSpec) {
	if (action?.actionType !== 'navigate_to') return action;
	const args = action.arguments ?? {};
	const radius = matchingPositionRadius(goalSpec?.predicate, args);
	if (radius === undefined) return action;
	if (radius < 0.01) {
		throw codedError('GOAL_TOLERANCE_UNREPRESENTABLE', 'The active position goal radius is below the navigation tool minimum');
	}
	if (args.tolerance <= radius) return action;
	return { ...action, arguments: { ...args, tolerance: radius } };
}

function matchingPositionRadius(predicate, args) {
	if (predicate?.type === 'position_within') {
		return args.x === predicate.x && args.y === predicate.y && args.z === predicate.z
			? predicate.radius
			: undefined;
	}
	let radius;
	for (const child of predicate?.predicates ?? []) {
		const childRadius = matchingPositionRadius(child, args);
		if (childRadius !== undefined) radius = radius === undefined ? childRadius : Math.min(radius, childRadius);
	}
	return radius;
}

function validateRecord(record) {
	if (record === null || typeof record !== 'object') throw new TypeError('record must be an object');
	for (const field of ['agentId', 'provider', 'model', 'reasoningEffort']) {
		if (typeof record[field] !== 'string' || record[field].length === 0) throw new TypeError(`record.${field} must be nonblank`);
	}
	if (!Number.isSafeInteger(record.goalRevision) || record.goalRevision < 0) throw new TypeError('record.goalRevision must be a nonnegative safe integer');
}

function validateRequest(request, record) {
	if (request === null || typeof request !== 'object') throw new TypeError('native tool request must be an object');
	if (request.agentId !== record.agentId || request.goalRevision !== record.goalRevision) throw codedError('STALE_NATIVE_TOOL', 'Native tool request does not match the active agent goal');
	if (typeof request.turnId !== 'string' || request.turnId.length === 0) throw new TypeError('native tool turnId must be nonblank');
	if (typeof request.callId !== 'string' || request.callId.length === 0) throw new TypeError('native tool callId must be nonblank');
	if (request.tool === null || typeof request.tool !== 'object') throw new TypeError('native tool must be normalized');
}

function hasAuthoritativeActionObservation(observation) {
	return observation !== null && typeof observation === 'object'
		&& (observation.inventory !== undefined || observation.death != null);
}

function resolveDecorateSource(observation, cached, live) {
	if (isSparseDeathObservation(observation) && hasDurableObservationFacts(cached ?? {})) return cached;
	if (isSparseDeathObservation(observation) && hasDurableObservationFacts(live ?? {})) {
		return mergeDeathObservation(observation, { observation: live });
	}
	if (hasDurableObservationFacts(observation)) return observation;
	return cached ?? live ?? observation;
}

function isSparseDeathObservation(observation) {
	return observation?.death != null
		&& observation.inventory === undefined
		&& observation.player === undefined
		&& observation.world === undefined;
}

function mergeDeathObservation(observation, lastLive) {
	if (observation?.death == null || lastLive == null) return observation;
	const live = lastLive.observation ?? lastLive;
	if (live === null || typeof live !== 'object') return observation;
	return {
		...live,
		...observation,
		ready: false,
		status: 'PLAYER_DEAD',
		player: {
			...(typeof live.player === 'object' && live.player !== null ? live.player : {}),
			...(typeof observation.player === 'object' && observation.player !== null ? observation.player : {}),
			dead: true,
			health: 0,
			x: observation.death.x,
			y: observation.death.y,
			z: observation.death.z,
		},
		inventory: observation.inventory ?? { items: [] },
		lastLiveInventory: live.inventory ?? null,
		continuity: { sameGoal: true, phase: 'dead', rememberedSections: ['position', 'velocity', 'view', 'blocks', 'landmarks', 'entities', 'nearbyContainers', 'interaction', 'world'].filter((section) => live[section] !== undefined && observation[section] === undefined) },
		death: observation.death,
	};
}

function safeSegment(value) { return String(value).replace(/[^A-Za-z0-9._:-]/g, '_') || 'item'; }
function terminalReceipt(active, payload) {
	return {
		worldId: active.worldId, actionId: active.actionId, goalRevision: active.goalRevision, actionType: active.actionType,
		state: payload.state, reasonCode: String(payload.reasonCode ?? '').slice(0, 128),
		...(typeof payload.executionStarted === 'boolean' ? { executionStarted: payload.executionStarted } : {}),
		...(typeof payload.physicalAttempted === 'boolean' ? { physicalAttempted: payload.physicalAttempted } : {}),
		...(payload.actionObservation === undefined ? {} : { actionObservation: structuredClone(payload.actionObservation) }),
		...(Number.isSafeInteger(payload.actionObservation?.worldTick) ? { tick: payload.actionObservation.worldTick } : {}),
	};
}
function nativeMemoryProvenance(request, record) { return { provider: record.provider, model: record.model, reasoningEffort: record.reasoningEffort, serviceTier: record.serviceTier ?? 'priority', goalRevision: record.goalRevision, turnId: request.turnId, callId: request.callId }; }
function wrapDegrees(value) {
	const wrapped = ((value + 180) % 360 + 360) % 360 - 180;
	return wrapped === -180 ? 180 : wrapped;
}
function actionResultKey(goalRevision, actionId) { return `${goalRevision}:${String(actionId ?? '')}`; }
function codedError(code, message) { return Object.assign(new Error(message), { code }); }

async function withDeadline(promise, timeoutMs, code, message) {
	let timer;
	try {
		return await Promise.race([promise, new Promise((_, reject) => { timer = setTimeout(() => reject(codedError(code, message)), timeoutMs); timer.unref?.(); })]);
	} finally {
		clearTimeout(timer);
	}
}
