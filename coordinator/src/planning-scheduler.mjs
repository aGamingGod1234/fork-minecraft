import { DEFAULT_AGENT_CAP, MAX_IDENTIFIER_LENGTH } from './constants.mjs';
import { normalizeErrorCode } from './provider-turn-telemetry.mjs';

const DEFAULT_MAX_CONCURRENT = 4;
const DEFAULT_URGENT_BURST = 3;
const MIN_ADAPTIVE_CONCURRENCY = 4;
const MAX_ADAPTIVE_CONCURRENCY = 16;
const DEFAULT_URGENT_RESERVE = 1;
const DEFAULT_MAX_AUXILIARY_PENDING = DEFAULT_AGENT_CAP;
const DEFAULT_SETTLEMENT_GRACE_MS = 5_000;
const HEALTHY_WINDOW = 4;
const TICK_PRESSURE_WINDOW = 3;
const DEFAULT_LANE = 'default';
const ORDINARY_PRIORITY = 'ordinary';
const URGENT_PRIORITY = 'urgent';
const PRIORITIES = new Set([ORDINARY_PRIORITY, URGENT_PRIORITY]);
const PLANNING_MODES = new Set(['fixed', 'adaptive']);
const CAPACITY_CLASSES = new Set(['default', 'auxiliary']);
const PRESSURE_REASONS = new Map([
	['RATE_LIMITED', 'provider_rate_limit'],
	['TOO_MANY_REQUESTS', 'provider_rate_limit'],
	['HTTP_429', 'provider_rate_limit'],
	['RESOURCE_EXHAUSTED', 'provider_rate_limit'],
	['OVERLOADED', 'provider_overload'],
	['PROVIDER_OVERLOADED', 'provider_overload'],
	['HTTP_529', 'provider_overload'],
	['PLANNING_TIMEOUT', 'provider_timeout'],
]);

export const ADAPTIVE_CONCURRENCY = Object.freeze({
	min: MIN_ADAPTIVE_CONCURRENCY,
	max: MAX_ADAPTIVE_CONCURRENCY,
	urgentReserve: DEFAULT_URGENT_RESERVE,
	healthyWindow: HEALTHY_WINDOW,
	tickPressureWindow: TICK_PRESSURE_WINDOW,
});

/**
 * Small deterministic state machine that changes only future scheduler admission.
 * It intentionally has no provider or agent-selection responsibilities.
 */
export class AdaptiveAdmissionController {
	#mode;
	#configuredTarget;
	#minConcurrency;
	#maxConcurrency;
	#urgentReserve;
	#target;
	#healthyCompletions = 0;
	#tickPressureSamples = 0;
	#growthCount = 0;
	#backoffCount = 0;
	#lastChangeReason = 'initial';
	#onTargetChanged;

	constructor({
		mode = 'fixed',
		planningMode = mode,
		configuredTarget = DEFAULT_MAX_CONCURRENT,
		minConcurrency = MIN_ADAPTIVE_CONCURRENCY,
		maxConcurrency = MAX_ADAPTIVE_CONCURRENCY,
		urgentReserve = DEFAULT_URGENT_RESERVE,
		onTargetChanged = () => {},
	} = {}) {
		if (!PLANNING_MODES.has(planningMode)) throw new TypeError("planningMode must be 'fixed' or 'adaptive'");
		if (!Number.isSafeInteger(configuredTarget) || configuredTarget < 1) throw new TypeError('configuredTarget must be a positive safe integer');
		const minimumAllowed = planningMode === 'adaptive' ? MIN_ADAPTIVE_CONCURRENCY : 1;
		if (!Number.isSafeInteger(minConcurrency) || minConcurrency < minimumAllowed) throw new TypeError(`minConcurrency must be at least ${minimumAllowed}`);
		if (!Number.isSafeInteger(maxConcurrency) || maxConcurrency > MAX_ADAPTIVE_CONCURRENCY || maxConcurrency < minConcurrency) throw new TypeError(`maxConcurrency must be in [${minConcurrency}, ${MAX_ADAPTIVE_CONCURRENCY}]`);
		if (planningMode === 'adaptive' && (configuredTarget < minConcurrency || configuredTarget > maxConcurrency)) throw new TypeError(`adaptive configuredTarget must be in [${minConcurrency}, ${maxConcurrency}]`);
		if (!Number.isSafeInteger(urgentReserve) || urgentReserve < 0 || urgentReserve > maxConcurrency) throw new TypeError('urgentReserve must be a non-negative safe integer within maxConcurrency');
		if (typeof onTargetChanged !== 'function') throw new TypeError('onTargetChanged must be a function');
		this.#mode = planningMode;
		this.#configuredTarget = configuredTarget;
		this.#minConcurrency = minConcurrency;
		this.#maxConcurrency = maxConcurrency;
		this.#urgentReserve = urgentReserve;
		this.#target = planningMode === 'adaptive' ? configuredTarget : configuredTarget;
		this.#onTargetChanged = onTargetChanged;
	}

	get mode() { return this.#mode; }
	get planningMode() { return this.#mode; }
	get configuredTarget() { return this.#configuredTarget; }
	get target() { return this.#target; }
	get minConcurrency() { return this.#minConcurrency; }
	get maxConcurrency() { return this.#maxConcurrency; }
	get urgentReserve() { return this.#urgentReserve; }
	get healthyCompletions() { return this.#healthyCompletions; }
	get growthCount() { return this.#growthCount; }
	get backoffCount() { return this.#backoffCount; }
	get lastChangeReason() { return this.#lastChangeReason; }

	observeProviderTelemetry(value, schedulerSnapshot = {}) {
		if (this.#mode !== 'adaptive' || value === null || typeof value !== 'object') return this.snapshot();
		const operation = typeof value.operation === 'string' ? value.operation : '';
		const errorCode = normalizeErrorCode(value.errorCode ?? value.error?.code ?? null);
		const pressureReason = PRESSURE_REASONS.get(errorCode);
		if (operation !== 'decide') return this.snapshot();
		if (pressureReason !== undefined) {
			this.#healthyCompletions = 0;
			this.#tickPressureSamples = 0;
			this.#decrement(pressureReason);
			return this.snapshot();
		}
		if (errorCode !== null) {
			this.#healthyCompletions = 0;
			return this.snapshot();
		}
		const pendingOrdinary = Number(schedulerSnapshot?.pendingOrdinary ?? 0);
		if (!Number.isFinite(pendingOrdinary) || pendingOrdinary <= 0) {
			this.#healthyCompletions = 0;
			return this.snapshot();
		}
		this.#healthyCompletions += 1;
		if (this.#healthyCompletions >= HEALTHY_WINDOW) {
			this.#healthyCompletions = 0;
			this.#increment('healthy_growth');
		}
		return this.snapshot();
	}

	observeSystemHealth(value) {
		if (this.#mode !== 'adaptive') return this.snapshot();
		const sample = value !== null && typeof value === 'object' ? value : {};
		const pressured = sample.missed50MsBudget === true
			|| (Number.isFinite(sample.tickP95Ms) && sample.tickP95Ms > 50);
		if (!pressured) {
			this.#tickPressureSamples = 0;
			return this.snapshot();
		}
		this.#tickPressureSamples += 1;
		if (this.#tickPressureSamples >= TICK_PRESSURE_WINDOW) {
			this.#tickPressureSamples = 0;
			this.#healthyCompletions = 0;
			this.#decrement('tick_pressure');
		}
		return this.snapshot();
	}

	snapshot() {
		return Object.freeze({
			mode: this.#mode,
			configuredTarget: this.#configuredTarget,
			target: this.#target,
			minConcurrency: this.#minConcurrency,
			maxConcurrency: this.#maxConcurrency,
			urgentReserve: this.#urgentReserve,
			growthCount: this.#growthCount,
			backoffCount: this.#backoffCount,
			lastChangeReason: this.#lastChangeReason,
			healthyCompletions: this.#healthyCompletions,
		});
	}

	#increment(reason) {
		if (this.#target >= this.#maxConcurrency) return;
		const previousTarget = this.#target;
		this.#target += 1;
		this.#growthCount += 1;
		this.#lastChangeReason = reason;
		this.#notify(previousTarget, reason);
	}

	#decrement(reason) {
		if (this.#target <= this.#minConcurrency) return;
		const previousTarget = this.#target;
		this.#target -= 1;
		this.#backoffCount += 1;
		this.#lastChangeReason = reason;
		this.#notify(previousTarget, reason);
	}

	#notify(previousTarget, reason) {
		try {
			this.#onTargetChanged({
				mode: this.#mode,
				previousTarget,
				target: this.#target,
				reason,
				minConcurrency: this.#minConcurrency,
				maxConcurrency: this.#maxConcurrency,
				urgentReserve: this.#urgentReserve,
			});
		} catch { /* metrics cannot affect admission */ }
	}
}

export class PlanningSchedulerError extends Error {
	constructor(code, message, options) {
		super(message, options);
		this.name = 'PlanningSchedulerError';
		this.code = code;
	}
}

export class PlanningScheduler {
	#maxConcurrent;
	#maxPending;
	#maxUrgentBurst;
	#onPressure;
	#warning = false;
	#pending = new Map();
	#lanes = new Map();
	#laneOrder = [];
	#lastLane = null;
	#urgentStreak = 0;
	#active = new Map();
	#settling = new Map();
	#closed = false;
	#recorder;
	#controller;
	#urgentReserve;
	#ordinaryReservationRejections = 0;
	#urgentReservationRejections = 0;
	#scheduleTimeout;
	#cancelTimeout;
	#maxAuxiliaryPending;
	#settlementGraceMs;
	#now;

	constructor({
		maxConcurrent = DEFAULT_MAX_CONCURRENT,
		maxPending = Math.max(0, DEFAULT_AGENT_CAP - maxConcurrent),
		maxAuxiliaryPending = DEFAULT_MAX_AUXILIARY_PENDING,
		maxUrgentBurst = DEFAULT_URGENT_BURST,
		urgentBurstLimit = maxUrgentBurst,
		mode,
		planningMode = mode,
		minConcurrency = MIN_ADAPTIVE_CONCURRENCY,
		maxConcurrency = MAX_ADAPTIVE_CONCURRENCY,
		urgentReserve,
		onPressure = () => {},
		recorder = null,
		benchmarkRecorder = null,
		settlementGraceMs = DEFAULT_SETTLEMENT_GRACE_MS,
		scheduleTimeout = defaultScheduleTimeout,
		cancelTimeout = clearTimeout,
		now = () => performance.now(),
	} = {}) {
		if (!Number.isSafeInteger(maxConcurrent) || maxConcurrent <= 0) throw new TypeError('maxConcurrent must be a positive safe integer');
		if (maxConcurrent > MAX_ADAPTIVE_CONCURRENCY) throw new TypeError(`maxConcurrent must not exceed ${MAX_ADAPTIVE_CONCURRENCY}`);
		if (!Number.isSafeInteger(maxPending) || maxPending < 0) throw new TypeError('maxPending must be a non-negative safe integer');
		if (!Number.isSafeInteger(maxAuxiliaryPending) || maxAuxiliaryPending < 0 || maxAuxiliaryPending > DEFAULT_AGENT_CAP) throw new TypeError(`maxAuxiliaryPending must be in [0, ${DEFAULT_AGENT_CAP}]`);
		if (maxConcurrent + maxPending > DEFAULT_AGENT_CAP) throw new TypeError(`planning capacity must not exceed ${DEFAULT_AGENT_CAP}`);
		if (!Number.isSafeInteger(urgentBurstLimit) || urgentBurstLimit <= 0) throw new TypeError('urgentBurstLimit must be a positive safe integer');
		if (!Number.isSafeInteger(settlementGraceMs) || settlementGraceMs <= 0) throw new TypeError('settlementGraceMs must be a positive safe integer');
		if (typeof onPressure !== 'function') throw new TypeError('onPressure must be a function');
		if (typeof scheduleTimeout !== 'function') throw new TypeError('scheduleTimeout must be a function');
		if (typeof cancelTimeout !== 'function') throw new TypeError('cancelTimeout must be a function');
		if (typeof now !== 'function') throw new TypeError('now must be a function');
		const selectedRecorder = recorder ?? benchmarkRecorder;
		if (selectedRecorder !== null && typeof selectedRecorder.record !== 'function') throw new TypeError('recorder.record must be a function');
		this.#maxConcurrent = maxConcurrent;
		this.#maxPending = maxPending;
		this.#maxAuxiliaryPending = maxAuxiliaryPending;
		this.#maxUrgentBurst = urgentBurstLimit;
		this.#settlementGraceMs = settlementGraceMs;
		this.#onPressure = onPressure;
		this.#recorder = selectedRecorder;
		this.#scheduleTimeout = scheduleTimeout;
		this.#cancelTimeout = cancelTimeout;
		this.#now = now;
		const selectedMode = planningMode ?? 'fixed';
		const selectedReserve = urgentReserve ?? ((mode !== undefined || planningMode !== undefined) && maxConcurrent >= MIN_ADAPTIVE_CONCURRENCY ? DEFAULT_URGENT_RESERVE : 0);
		const selectedMinConcurrency = selectedMode === 'adaptive' ? minConcurrency : Math.min(minConcurrency, maxConcurrency);
		this.#controller = new AdaptiveAdmissionController({
			planningMode: selectedMode,
			configuredTarget: maxConcurrent,
			minConcurrency: selectedMinConcurrency,
			maxConcurrency,
			urgentReserve: selectedReserve,
			onTargetChanged: (change) => {
				const snapshot = this.#snapshot();
				this.#record('scheduler_target_changed', null, {
					mode: change.mode,
					previousTarget: change.previousTarget,
					target: change.target,
					reason: change.reason,
					minConcurrency: change.minConcurrency,
					maxConcurrency: change.maxConcurrency,
					urgentReserve: change.urgentReserve,
					ordinaryActiveLimit: snapshot.ordinaryActiveLimit,
					active: snapshot.active,
					activeOrdinary: snapshot.activeOrdinary,
					activeUrgent: snapshot.activeUrgent,
					pending: snapshot.pending,
					pendingOrdinary: snapshot.pendingOrdinary,
					pendingUrgent: snapshot.pendingUrgent,
				});
				this.#drain();
			},
		});
		this.#urgentReserve = selectedReserve;
	}

	get maxConcurrent() { return this.#maxConcurrent; }
	get planningMode() { return this.#controller.planningMode; }
	get mode() { return this.#controller.mode; }
	get target() { return this.#controller.target; }
	get effectiveTarget() { return this.#controller.target; }
	get configuredTarget() { return this.#controller.configuredTarget; }
	get minConcurrency() { return this.#controller.minConcurrency; }
	get maxConcurrency() { return this.#controller.maxConcurrency; }
	get urgentReserve() { return this.#urgentReserve; }
	get maxPending() { return this.#maxPending; }
	get maxAuxiliaryPending() { return this.#maxAuxiliaryPending; }
	get maxUrgentBurst() { return this.#maxUrgentBurst; }
	get urgentBurstLimit() { return this.#maxUrgentBurst; }
	get totalCapacity() { return this.#maxConcurrent + this.#maxPending; }
	get activeCount() { return this.#active.size; }
	get pendingCount() { return this.#pending.size; }
	get activeOrdinaryCount() { return this.#countActivePriority(ORDINARY_PRIORITY); }
	get activeUrgentCount() { return this.#countActivePriority(URGENT_PRIORITY); }
	get pendingOrdinaryCount() { return this.#countPendingPriority(ORDINARY_PRIORITY); }
	get pendingUrgentCount() { return this.#countPendingPriority(URGENT_PRIORITY); }
	get pendingAuxiliaryCount() { return this.#countPendingCapacityClass('auxiliary'); }
	get growthCount() { return this.#controller.growthCount; }
	get backoffCount() { return this.#controller.backoffCount; }
	get lastChangeReason() { return this.#controller.lastChangeReason; }
	get healthyCompletions() { return this.#controller.healthyCompletions; }
	get ordinaryReservationRejections() { return this.#ordinaryReservationRejections; }
	get urgentReservationRejections() { return this.#urgentReservationRejections; }
	get activeAgentIds() { return [...this.#active.keys()]; }
	get pendingAgentIds() { return [...this.#pending.keys()]; }
	get pressureSnapshot() { return this.#snapshot(); }
	observeProviderTelemetry(value, snapshot = this.#snapshot()) {
		const result = this.#controller.observeProviderTelemetry(value, snapshot);
		this.#drain();
		return result;
	}
	observeSystemHealth(value) {
		const result = this.#controller.observeSystemHealth(value);
		this.#drain();
		return result;
	}
	hasScheduled(agentIdValue) {
		const agentId = requireAgentId(agentIdValue);
		return this.#pending.has(agentId) || this.#active.has(agentId) || this.#settling.has(agentId);
	}

	schedule(agentIdValue, task, options = {}) {
		const agentId = requireAgentId(agentIdValue);
		if (typeof task !== 'function') throw new TypeError('planning task must be a function');
		const { lane, priority, capacityClass, leaseTimeoutMs, maxLeaseDurationMs, onLeaseExpired } = normalizeScheduleOptions(options);
		this.#record('scheduler_admission_requested', agentId, { lane, priority, ...this.#snapshot() });
		if (this.#closed) return this.#reject('SCHEDULER_CLOSED', 'Planning scheduler is closed', agentId, lane, priority);
		if (this.#pending.has(agentId)) return this.#reject('PLAN_ALREADY_QUEUED', `Agent '${agentId}' already has a queued planning turn`, agentId, lane, priority);
		if (this.#active.has(agentId)) return this.#reject('PLAN_ALREADY_ACTIVE', `Agent '${agentId}' already has an active planning turn`, agentId, lane, priority);
		if (this.#settling.has(agentId)) return this.#reject('PLAN_CANCELLING', `Agent '${agentId}' is still settling a cancelled planning turn`, agentId, lane, priority);
		if (this.#wouldExceedCapacity(priority, capacityClass)) {
			if (priority === ORDINARY_PRIORITY) this.#ordinaryReservationRejections += 1;
			else this.#urgentReservationRejections += 1;
			return this.#reject('SCHEDULER_CAPACITY', `Planning scheduler capacity ${this.totalCapacity} is full`, agentId, lane, priority);
		}

		const promise = new Promise((resolve, reject) => {
			const entry = { agentId, task, lane, priority, capacityClass, leaseTimeoutMs, maxLeaseDurationMs, onLeaseExpired, resolve, reject };
			this.#pending.set(agentId, entry);
			this.#lane(lane)[priority].push(entry);
		});
		this.#record('scheduler_queued', agentId, { lane, priority, ...this.#snapshot() });
		this.#drain();
		this.#notifyPressure();
		return promise;
	}

	cancel(agentIdValue, reason = 'Planning turn cancelled') {
		const agentId = requireAgentId(agentIdValue);
		const pending = this.#pending.get(agentId);
		if (pending !== undefined) {
			this.#pending.delete(agentId);
			const queue = this.#lane(pending.lane)[pending.priority];
			const index = queue.indexOf(pending);
			if (index >= 0) queue.splice(index, 1);
			pending.reject(new PlanningSchedulerError('PLAN_CANCELLED', reason));
		}
		const active = this.#active.get(agentId);
		if (active !== undefined) {
			const error = new PlanningSchedulerError('PLAN_CANCELLED', reason);
			active.cancelError = error;
			active.controller.abort(error);
			this.#cancelLeaseTimeout(active);
			this.#active.delete(agentId);
			this.#settling.set(agentId, active);
			this.#scheduleSettlementDeadline(active);
			this.#record('scheduler_cancelled', agentId, { lane: active.lane, priority: active.priority, ...this.#snapshot() });
		}
		this.#drain();
		this.#notifyPressure();
		return pending !== undefined || active !== undefined;
	}

	close(reason = 'Planning scheduler closed') {
		if (this.#closed) return;
		this.#closed = true;
		for (const agentId of [...this.#pending.keys(), ...this.#active.keys()]) this.cancel(agentId, reason);
		for (const [agentId, entry] of [...this.#settling.entries()]) this.#forceRelease(agentId, entry.controller, 'scheduler_closed');
	}

	#lane(lane) {
		let queues = this.#lanes.get(lane);
		if (queues === undefined) {
			queues = { [URGENT_PRIORITY]: [], [ORDINARY_PRIORITY]: [] };
			this.#lanes.set(lane, queues);
			this.#laneOrder.push(lane);
		}
		return queues;
	}

	#drain() {
		while (!this.#closed && this.#physicallyOccupiedCount() < this.#controller.target && this.#pending.size > 0) {
			const entry = this.#nextEntry();
			if (entry === null) break;
			this.#pending.delete(entry.agentId);
			const controller = new AbortController();
			const active = {
				...entry,
				controller,
				leaseTimeoutHandle: null,
				leaseGeneration: 0,
				leasePhase: 'provider',
				phaseTimeoutMs: entry.leaseTimeoutMs,
				leaseDeadline: entry.maxLeaseDurationMs === null ? null : this.#now() + entry.maxLeaseDurationMs,
				settlementTimeoutHandle: null,
				cancelError: null,
				promiseSettled: false,
			};
			this.#active.set(entry.agentId, active);
			if (entry.leaseTimeoutMs !== null) {
				this.#armLease(active, entry.leaseTimeoutMs);
			}
			this.#record('scheduler_admitted', entry.agentId, {
				lane: entry.lane,
				priority: entry.priority,
				...this.#snapshot(),
			});
			Promise.resolve()
				.then(() => {
					if (controller.signal.aborted) throw controller.signal.reason;
					return entry.task({ agentId: entry.agentId, signal: controller.signal, lane: entry.lane, priority: entry.priority,
						renewLease: (options) => this.#renewLease(active, options) });
				})
				.then(
					(value) => this.#settle(entry.agentId, controller, null, value),
					(error) => this.#settle(entry.agentId, controller, error),
				);
		}
		this.#notifyPressure();
	}

	#nextEntry() {
		let priority = this.#nextPriority();
		const physicallyActiveOrdinary = this.activeOrdinaryCount + this.#countSettlingPriority(ORDINARY_PRIORITY);
		if (priority === ORDINARY_PRIORITY && physicallyActiveOrdinary >= this.#ordinaryActiveLimit()) {
			if (!this.#hasPendingPriority(URGENT_PRIORITY)) return null;
			priority = URGENT_PRIORITY;
		}
		const lane = this.#nextLane(priority);
		if (lane === null) return null;
		const entry = this.#lanes.get(lane)[priority].shift() ?? null;
		if (entry !== null) {
			this.#lastLane = lane;
			if (priority === URGENT_PRIORITY) this.#urgentStreak += 1;
			else this.#urgentStreak = 0;
		}
		return entry;
	}

	#nextPriority() {
		const hasUrgent = this.#hasPendingPriority(URGENT_PRIORITY);
		const hasOrdinary = this.#hasPendingPriority(ORDINARY_PRIORITY);
		if (hasUrgent && (!hasOrdinary || this.#urgentStreak < this.#maxUrgentBurst)) return URGENT_PRIORITY;
		if (hasOrdinary) return ORDINARY_PRIORITY;
		return URGENT_PRIORITY;
	}

	#hasPendingPriority(priority) {
		return this.#laneOrder.some((lane) => this.#lanes.get(lane)[priority].length > 0);
	}

	#nextLane(priority) {
		if (this.#laneOrder.length === 0) return null;
		const lastIndex = this.#lastLane === null ? -1 : this.#laneOrder.indexOf(this.#lastLane);
		const start = (lastIndex + 1 + this.#laneOrder.length) % this.#laneOrder.length;
		for (let offset = 0; offset < this.#laneOrder.length; offset += 1) {
			const lane = this.#laneOrder[(start + offset) % this.#laneOrder.length];
			if (this.#lanes.get(lane)[priority].length > 0) return lane;
		}
		return null;
	}

	#settle(agentId, controller, error, value = undefined) {
		const active = this.#active.get(agentId);
		const settling = this.#settling.get(agentId);
		const entry = active?.controller === controller ? active : (settling?.controller === controller ? settling : null);
		if (entry === null) return;
		this.#cancelLeaseTimeout(entry);
		this.#cancelSettlementTimeout(entry);
		if (active === entry) this.#active.delete(agentId);
		if (settling === entry) this.#settling.delete(agentId);
		this.#record('scheduler_released', agentId, {
			lane: entry.lane,
			priority: entry.priority,
			...this.#snapshot(),
		});
		this.#drain();
		if (!entry.promiseSettled) {
			entry.promiseSettled = true;
			if (entry.cancelError !== null) entry.reject(entry.cancelError);
			else if (error !== null) entry.reject(error);
			else entry.resolve(value);
		}
	}

	#armLease(entry, timeoutMs) {
		this.#cancelLeaseTimeout(entry);
		entry.phaseTimeoutMs = timeoutMs;
		const generation = entry.leaseGeneration;
		entry.leaseTimeoutHandle = this.#scheduleTimeout(() => {
			if (entry.leaseGeneration === generation) this.#expire(entry.agentId, entry.controller);
		}, timeoutMs);
	}

	#renewLease(entry, { phase = 'provider', timeoutMs = entry.leaseTimeoutMs } = {}) {
		if (!['provider', 'tool'].includes(phase)) throw new TypeError('planning lease phase must be provider or tool');
		if (!Number.isSafeInteger(timeoutMs) || timeoutMs <= 0) throw new TypeError('planning renewal timeoutMs must be a positive safe integer');
		if (entry.leaseDeadline === null || this.#active.get(entry.agentId) !== entry || entry.controller.signal.aborted) return false;
		const remainingMs = entry.leaseDeadline - this.#now();
		if (remainingMs <= 0) {
			this.#expire(entry.agentId, entry.controller);
			return false;
		}
		entry.leasePhase = phase;
		this.#armLease(entry, Math.min(timeoutMs, remainingMs));
		return true;
	}

	#expire(agentId, controller) {
		const active = this.#active.get(agentId);
		const settling = this.#settling.get(agentId);
		const entry = active?.controller === controller ? active : (settling?.controller === controller ? settling : null);
		if (entry === null || entry.cancelError !== null) return;
		const error = new PlanningSchedulerError('PLANNING_LEASE_EXPIRED', `Planning ${entry.leasePhase} lease for '${agentId}' expired after ${entry.phaseTimeoutMs} ms`);
		error.phase = entry.leasePhase;
		error.budgetExhausted = entry.leaseDeadline !== null && this.#now() >= entry.leaseDeadline;
		controller.abort(error);
		this.#cancelLeaseTimeout(entry);
		if (active === entry) {
			this.#active.delete(agentId);
			this.#settling.set(agentId, entry);
		}
		if (!entry.promiseSettled) {
			entry.promiseSettled = true;
			entry.reject(entry.cancelError ?? error);
		}
		try {
			void Promise.resolve(entry.onLeaseExpired?.({
				agentId,
				lane: entry.lane,
				priority: entry.priority,
				signal: controller.signal,
				error,
			})).catch(() => undefined);
		} catch { /* recovery cannot retain scheduler capacity */ }
		this.#scheduleSettlementDeadline(entry);
		this.#record('scheduler_lease_expired', agentId, { lane: entry.lane, priority: entry.priority, ...this.#snapshot() });
		this.#drain();
	}

	#scheduleSettlementDeadline(entry) {
		if (this.#settling.get(entry.agentId) !== entry || entry.settlementTimeoutHandle !== null) return;
		entry.settlementTimeoutHandle = this.#scheduleTimeout(
			() => this.#forceRelease(entry.agentId, entry.controller, 'settlement_grace_expired'),
			this.#settlementGraceMs,
		);
	}

	#forceRelease(agentId, controller, reason) {
		const entry = this.#settling.get(agentId);
		if (entry?.controller !== controller) return;
		this.#cancelLeaseTimeout(entry);
		this.#cancelSettlementTimeout(entry);
		this.#settling.delete(agentId);
		this.#record('scheduler_forced_release', agentId, {
			lane: entry.lane,
			priority: entry.priority,
			reason,
			...this.#snapshot(),
		});
		this.#drain();
		this.#notifyPressure();
		if (!entry.promiseSettled) {
			entry.promiseSettled = true;
			entry.reject(entry.cancelError ?? new PlanningSchedulerError('PLAN_CANCELLED', 'Planning turn settlement grace expired'));
		}
	}

	#cancelLeaseTimeout(entry) {
		entry.leaseGeneration += 1;
		if (entry.leaseTimeoutHandle === null) return;
		this.#cancelTimeout(entry.leaseTimeoutHandle);
		entry.leaseTimeoutHandle = null;
	}

	#cancelSettlementTimeout(entry) {
		if (entry.settlementTimeoutHandle === null) return;
		this.#cancelTimeout(entry.settlementTimeoutHandle);
		entry.settlementTimeoutHandle = null;
	}

	#snapshot() {
		const used = this.#physicallyOccupiedCount() + this.#pending.size;
		const controller = this.#controller?.snapshot() ?? {
			mode: 'fixed', configuredTarget: this.#maxConcurrent, target: this.#maxConcurrent,
			minConcurrency: MIN_ADAPTIVE_CONCURRENCY, maxConcurrency: MAX_ADAPTIVE_CONCURRENCY,
			urgentReserve: this.#urgentReserve, growthCount: 0, backoffCount: 0,
			lastChangeReason: 'initial', healthyCompletions: 0,
		};
		return Object.freeze({
			active: this.#active.size,
			pending: this.#pending.size,
			used,
			maxConcurrent: this.#maxConcurrent,
			maxPending: this.#maxPending,
			maxAuxiliaryPending: this.#maxAuxiliaryPending,
			totalCapacity: this.totalCapacity,
			settling: this.#settling.size,
			mode: controller.mode,
			planningMode: controller.mode,
			configuredTarget: controller.configuredTarget,
			target: controller.target,
			effectiveTarget: controller.target,
			minConcurrency: controller.minConcurrency,
			maxConcurrency: controller.maxConcurrency,
			urgentReserve: this.#urgentReserve,
			ordinaryActiveLimit: this.#ordinaryActiveLimit(),
			activeOrdinary: this.activeOrdinaryCount,
			activeUrgent: this.activeUrgentCount,
			pendingOrdinary: this.pendingOrdinaryCount,
			pendingUrgent: this.pendingUrgentCount,
			pendingAuxiliary: this.pendingAuxiliaryCount,
			growthCount: controller.growthCount,
			backoffCount: controller.backoffCount,
			lastChangeReason: controller.lastChangeReason,
			healthyCompletions: controller.healthyCompletions,
			ordinaryReservationRejections: this.#ordinaryReservationRejections,
			urgentReservationRejections: this.#urgentReservationRejections,
			warning: used >= Math.ceil(this.totalCapacity * 0.75),
			full: used >= this.totalCapacity,
		});
	}

	#countActivePriority(priority) {
		let count = 0;
		for (const entry of this.#active.values()) if (entry.priority === priority) count += 1;
		return count;
	}

	#countSettlingPriority(priority, capacityClass = null) {
		let count = 0;
		for (const entry of this.#settling.values()) {
			if (entry.priority === priority && (capacityClass === null || entry.capacityClass === capacityClass)) count += 1;
		}
		return count;
	}

	#physicallyOccupiedCount() {
		return this.#active.size + this.#settling.size;
	}

	#countPendingPriority(priority, capacityClass = null) {
		let count = 0;
		for (const entry of this.#pending.values()) {
			if (entry.priority === priority && (capacityClass === null || entry.capacityClass === capacityClass)) count += 1;
		}
		return count;
	}

	#countPendingCapacityClass(capacityClass) {
		let count = 0;
		for (const entry of this.#pending.values()) if (entry.capacityClass === capacityClass) count += 1;
		return count;
	}

	#ordinaryActiveLimit() {
		return Math.max(0, this.#controller.target - this.#urgentReserve);
	}

	#wouldExceedCapacity(priority, capacityClass) {
		if (capacityClass === 'auxiliary') return this.pendingAuxiliaryCount >= this.#maxAuxiliaryPending;
		const used = this.#physicallyOccupiedCount() + this.#pending.size - this.pendingAuxiliaryCount;
		if (used >= this.totalCapacity) return true;
		if (priority !== ORDINARY_PRIORITY || this.#urgentReserve === 0) return false;
		const ordinaryUsed = this.activeOrdinaryCount + this.#countSettlingPriority(ORDINARY_PRIORITY, 'default')
			+ this.#countPendingPriority(ORDINARY_PRIORITY, 'default');
		const urgentUsed = this.activeUrgentCount + this.#countSettlingPriority(URGENT_PRIORITY) + this.pendingUrgentCount;
		const ordinaryLimit = this.totalCapacity - Math.max(this.#urgentReserve, urgentUsed);
		return ordinaryUsed >= ordinaryLimit;
	}

	#notifyPressure() {
		const snapshot = this.#snapshot();
		if (snapshot.warning === this.#warning) return;
		this.#warning = snapshot.warning;
		this.#record('scheduler_pressure', null, snapshot);
		try { this.#onPressure(snapshot); } catch { /* monitoring cannot break scheduling */ }
	}

	#reject(code, message, agentId, lane, priority) {
		this.#record('scheduler_rejected', agentId, { lane, priority, errorCode: code, ...this.#snapshot() });
		return Promise.reject(new PlanningSchedulerError(code, message));
	}

	#record(stage, agentId, fields = {}) {
		if (this.#recorder === null) return;
		try { this.#recorder.record(stage, agentId === null ? {} : { agentId }, fields); }
		catch { /* benchmark telemetry cannot affect scheduling */ }
	}
}

function normalizeScheduleOptions(options) {
	if (options === null || typeof options !== 'object' || Array.isArray(options)) throw new TypeError('planning schedule options must be an object');
	const leaseTimeoutMs = options.leaseTimeoutMs ?? null;
	if (leaseTimeoutMs !== null && (!Number.isSafeInteger(leaseTimeoutMs) || leaseTimeoutMs <= 0)) throw new TypeError('planning leaseTimeoutMs must be a positive safe integer');
	const maxLeaseDurationMs = options.maxLeaseDurationMs ?? null;
	if (maxLeaseDurationMs !== null && (leaseTimeoutMs === null || !Number.isSafeInteger(maxLeaseDurationMs) || maxLeaseDurationMs < leaseTimeoutMs)) throw new TypeError('planning maxLeaseDurationMs must be a safe integer at least leaseTimeoutMs');
	if (options.onLeaseExpired !== undefined && typeof options.onLeaseExpired !== 'function') throw new TypeError('planning onLeaseExpired must be a function');
	return {
		lane: requireLane(options.lane ?? DEFAULT_LANE),
		priority: requirePriority(options.priority ?? ORDINARY_PRIORITY),
		capacityClass: requireCapacityClass(options.capacityClass ?? 'default'),
		leaseTimeoutMs,
		maxLeaseDurationMs,
		onLeaseExpired: options.onLeaseExpired ?? null,
	};
}

function requireCapacityClass(value) {
	if (typeof value !== 'string' || !CAPACITY_CLASSES.has(value)) throw new TypeError("planning capacityClass must be 'default' or 'auxiliary'");
	return value;
}

function defaultScheduleTimeout(callback, delay) {
	const handle = setTimeout(callback, delay);
	handle.unref?.();
	return handle;
}

function requireLane(value) {
	if (typeof value !== 'string' || value.trim().length === 0 || value.length > MAX_IDENTIFIER_LENGTH) throw new TypeError(`planning lane must be nonblank and at most ${MAX_IDENTIFIER_LENGTH} characters`);
	return value;
}

function requirePriority(value) {
	if (typeof value !== 'string' || !PRIORITIES.has(value)) throw new TypeError(`planning priority must be '${URGENT_PRIORITY}' or '${ORDINARY_PRIORITY}'`);
	return value;
}

function requireAgentId(value) {
	if (typeof value !== 'string' || value.trim().length === 0 || value.length > MAX_IDENTIFIER_LENGTH) throw new TypeError(`agentId must be nonblank and at most ${MAX_IDENTIFIER_LENGTH} characters`);
	return value;
}
