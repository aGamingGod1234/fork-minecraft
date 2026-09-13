import { monitorEventLoopDelay } from 'node:perf_hooks';
import { sanitizeDiagnosticErrorMessage } from '../diagnostic-sanitizer.mjs';

const DEFAULT_INTERVAL_MS = 50;
export const MAX_SUMMARY_SAMPLES = 10_000;
const DEFAULT_MAX_SAMPLES = MAX_SUMMARY_SAMPLES;
const MAX_REPORTED_ERRORS = 128;

/**
 * Observational process/scheduler sampler. Every side effect is injected for
 * tests; sampling failures become bounded null fields and never change the
 * benchmark result.
 */
export class SystemSampler {
	#clock;
	#intervalMs;
	#maxSamples;
	#timer;
	#processReader;
	#eventLoopDelayReader;
	#schedulerReader;
	#childProcessReader;
	#eventLoopMonitorFactory;
	#onError;
	#eventLoopMonitor;
	#handle = null;
	#active = false;
	#generation = 0;
	#samples = [];
	#droppedSamples = 0;
	#sequence = 0;
	#errors = [];

	constructor(options = {}) {
		if (options === null || typeof options !== 'object' || Array.isArray(options)) throw new TypeError('system sampler options must be an object');
		this.#clock = options.clock ?? (() => performance.now());
		if (typeof this.#clock !== 'function') throw new TypeError('system sampler clock must be a function');
		this.#intervalMs = positiveFinite(options.intervalMs ?? DEFAULT_INTERVAL_MS, 'intervalMs');
		this.#maxSamples = boundedPositiveInteger(options.maxSamples ?? DEFAULT_MAX_SAMPLES, 'maxSamples', MAX_SUMMARY_SAMPLES);
		this.#timer = options.timer ?? { setInterval: globalThis.setInterval, clearInterval: globalThis.clearInterval };
		if (typeof this.#timer.setInterval !== 'function' || typeof this.#timer.clearInterval !== 'function') throw new TypeError('system sampler timer must provide setInterval and clearInterval');
		this.#processReader = options.processReader ?? defaultProcessReader;
		this.#eventLoopDelayReader = options.eventLoopDelayReader ?? null;
		const configuredMonitor = options.eventLoopMonitor;
		this.#eventLoopMonitorFactory = options.eventLoopMonitorFactory
			?? (configuredMonitor === undefined ? () => monitorEventLoopDelay({ resolution: 10 }) : () => configuredMonitor);
		if (typeof this.#eventLoopMonitorFactory !== 'function') throw new TypeError('eventLoopMonitorFactory must be a function');
		this.#onError = options.onError ?? null;
		if (this.#onError !== null && typeof this.#onError !== 'function') throw new TypeError('system sampler onError must be a function');
		this.#schedulerReader = options.schedulerReader ?? (() => ({ active: 0, pending: 0 }));
		this.#childProcessReader = options.childProcessReader ?? (() => 0);
		for (const [name, reader] of Object.entries({ processReader: this.#processReader, schedulerReader: this.#schedulerReader, childProcessReader: this.#childProcessReader })) {
			if (typeof reader !== 'function' && typeof reader?.read !== 'function') throw new TypeError(`${name} must be a function or provide read()`);
		}
		if (this.#eventLoopDelayReader !== null && typeof this.#eventLoopDelayReader !== 'function' && typeof this.#eventLoopDelayReader?.read !== 'function') throw new TypeError('eventLoopDelayReader must be a function or provide read()');
	}

	get active() { return this.#active; }
	get handle() { return this.#handle; }
	get sampleCount() { return this.#samples.length; }
	get errors() { return Object.freeze(this.#errors.slice()); }

	start() {
		if (this.#active) return this;
		this.#active = true;
		const generation = ++this.#generation;
		if (this.#eventLoopDelayReader === null) {
			try {
				this.#eventLoopMonitor = this.#eventLoopMonitorFactory();
				if (this.#eventLoopMonitor === null || typeof this.#eventLoopMonitor?.enable !== 'function') throw new TypeError('event loop monitor must provide enable()');
				this.#eventLoopMonitor.enable();
			} catch (error) {
				this.#recordError('eventLoopMonitor.enable', error);
				this.#eventLoopMonitor = null;
			}
		}
		try {
			this.#handle = this.#timer.setInterval(() => {
				if (this.#active && this.#generation === generation) this.sample();
			}, this.#intervalMs);
		} catch (error) {
			this.#active = false;
			++this.#generation;
			this.#disableEventLoopMonitor();
			throw error;
		}
		return this;
	}

	sample() {
		if (this.#samples.length >= this.#maxSamples) {
			this.#droppedSamples += 1;
			return null;
		}
		const sample = deepFreeze({
			sequence: ++this.#sequence,
			monotonicMs: safeClock(this.#clock, this.#samples.at(-1)?.monotonicMs ?? 0),
			...normalizeProcess(readValue(this.#processReader)),
			eventLoopDelay: normalizeEventLoopDelay(this.#readEventLoopDelay()),
			scheduler: normalizeScheduler(readValue(this.#schedulerReader)),
			childProcessCount: normalizeCount(readValue(this.#childProcessReader)),
		});
		this.#samples.push(sample);
		return sample;
	}

	stop() {
		if (!this.#active) return this;
		this.#active = false;
		++this.#generation;
		if (this.#handle !== null) {
			const handle = this.#handle;
			this.#handle = null;
			try { this.#timer.clearInterval(handle); } catch (error) { this.#recordError('timer.clearInterval', error); }
		}
		this.#disableEventLoopMonitor();
		return this;
	}

	snapshot() {
		const samples = this.#samples.slice();
		Object.defineProperties(samples, {
			samples: { configurable: false, enumerable: false, value: samples, writable: false },
			droppedSamples: { configurable: false, enumerable: false, value: this.#droppedSamples, writable: false },
			maxSamples: { configurable: false, enumerable: false, value: this.#maxSamples, writable: false },
			errors: { configurable: false, enumerable: false, value: this.errors, writable: false },
		});
		return Object.freeze(samples);
	}

	summary() { return summarizeSystemSamples(this.snapshot()); }

	#readEventLoopDelay() {
		if (this.#eventLoopDelayReader !== null) return readValue(this.#eventLoopDelayReader);
		if (this.#eventLoopMonitor === null) return null;
		try {
			const monitor = this.#eventLoopMonitor;
			const nanosecondsToMs = (value) => Number.isFinite(value) ? value / 1e6 : null;
			return { meanMs: nanosecondsToMs(monitor.mean), p95Ms: nanosecondsToMs(monitor.percentile(95)), maxMs: nanosecondsToMs(monitor.max) };
		} catch { return null; }
	}

	#disableEventLoopMonitor() {
		if (this.#eventLoopMonitor === null) return;
		try { this.#eventLoopMonitor.disable(); } catch (error) { this.#recordError('eventLoopMonitor.disable', error); }
		this.#eventLoopMonitor = null;
	}

	#recordError(phase, error) {
		const entry = deepFreeze({ code: 'SYSTEM_SAMPLER_CLEANUP_FAILED', phase, message: boundedError(error) });
		if (this.#errors.length < MAX_REPORTED_ERRORS) this.#errors.push(entry);
		try { this.#onError?.(entry); } catch { /* error reporting must not affect sampling */ }
	}
}

export function summarizeSystemSamples(input) {
	const sourceSamples = Array.isArray(input) ? input : (Array.isArray(input?.samples) ? input.samples : []);
	if (sourceSamples.length > MAX_SUMMARY_SAMPLES) throw new RangeError(`system sample summary accepts at most ${MAX_SUMMARY_SAMPLES} samples`);
	const samples = sourceSamples;
	return deepFreeze({
		sampleCount: samples.length,
		droppedSamples: nonNegativeInteger(input?.droppedSamples) ?? 0,
		maxSamples: nonNegativeInteger(input?.maxSamples) ?? MAX_SUMMARY_SAMPLES,
		errors: Array.isArray(input?.errors) ? input.errors.map((error) => ({ ...error })) : [],
		cpu: {
			userMs: seriesSummary(samples.map((sample) => sample?.cpu?.userMs)),
			systemMs: seriesSummary(samples.map((sample) => sample?.cpu?.systemMs)),
			totalMs: seriesSummary(samples.map((sample) => sample?.cpu?.totalMs)),
		},
		memory: {
			rssBytes: seriesSummary(samples.map((sample) => sample?.memory?.rssBytes)),
			heapUsedBytes: seriesSummary(samples.map((sample) => sample?.memory?.heapUsedBytes)),
		},
		eventLoopDelay: {
			meanMs: seriesSummary(samples.map((sample) => sample?.eventLoopDelay?.meanMs)),
			p95Ms: seriesSummary(samples.map((sample) => sample?.eventLoopDelay?.p95Ms)),
			maxMs: seriesSummary(samples.map((sample) => sample?.eventLoopDelay?.maxMs)),
		},
		scheduler: {
			active: seriesSummary(samples.map((sample) => sample?.scheduler?.active)),
			pending: seriesSummary(samples.map((sample) => sample?.scheduler?.pending)),
		},
		childProcessCount: seriesSummary(samples.map((sample) => sample?.childProcessCount)),
	});
}

function defaultProcessReader() {
	const usage = process.resourceUsage?.() ?? {};
	const memory = process.memoryUsage?.() ?? {};
	return {
		cpuUserMs: finiteOrNull(usage.userCPUTime === undefined ? null : usage.userCPUTime / 1_000),
		cpuSystemMs: finiteOrNull(usage.systemCPUTime === undefined ? null : usage.systemCPUTime / 1_000),
		rssBytes: finiteOrNull(memory.rss),
		heapUsedBytes: finiteOrNull(memory.heapUsed),
		heapTotalBytes: finiteOrNull(memory.heapTotal),
		externalBytes: finiteOrNull(memory.external),
	};
}

function normalizeProcess(value) { return { cpu: normalizeCpu(value), memory: normalizeMemory(value) }; }

function readValue(reader, options = undefined) {
	try {
		const value = typeof reader === 'function' ? reader(options) : reader.read(options);
		return value;
	} catch { return null; }
}

function normalizeCpu(value) {
	const source = value ?? {};
	const userMs = finiteOrNull(source.userMs ?? source.cpuUserMs);
	const systemMs = finiteOrNull(source.systemMs ?? source.cpuSystemMs);
	const totalMs = finiteOrNull(source.totalMs ?? (userMs === null || systemMs === null ? null : userMs + systemMs));
	return { userMs, systemMs, totalMs };
}

function normalizeMemory(value) {
	const source = value ?? {};
	return {
		rssBytes: finiteOrNull(source.rssBytes ?? source.rss),
		heapUsedBytes: finiteOrNull(source.heapUsedBytes ?? source.heapUsed),
		heapTotalBytes: finiteOrNull(source.heapTotalBytes ?? source.heapTotal),
		externalBytes: finiteOrNull(source.externalBytes ?? source.external),
	};
}

function normalizeEventLoopDelay(value) {
	const source = value ?? {};
	return { meanMs: finiteOrNull(source.meanMs ?? source.mean), p95Ms: finiteOrNull(source.p95Ms ?? source.p95), maxMs: finiteOrNull(source.maxMs ?? source.max) };
}

function normalizeScheduler(value) {
	const source = value ?? {};
	return { active: normalizeCount(source.active), pending: normalizeCount(source.pending) };
}

function normalizeCount(value) { return Number.isSafeInteger(value) && value >= 0 ? value : null; }
function finiteOrNull(value) { return Number.isFinite(value) && value >= 0 ? value : null; }
function safeClock(clock, previous) {
	try {
		const value = clock();
		return Number.isFinite(value) && value >= previous ? value : previous;
	} catch { return previous; }
}

function seriesSummary(values) {
	const finite = values.filter((value) => Number.isFinite(value) && value >= 0).sort((left, right) => left - right);
	if (finite.length === 0) return { min: null, max: null, p50: null, p95: null, p99: null };
	return { min: finite[0], max: finite.at(-1), p50: percentile(finite, 0.50), p95: percentile(finite, 0.95), p99: percentile(finite, 0.99) };
}

function percentile(sorted, fraction) { return sorted[Math.max(0, Math.ceil(sorted.length * fraction) - 1)]; }
function positiveInteger(value, field) { if (!Number.isSafeInteger(value) || value < 1) throw new TypeError(`${field} must be a positive safe integer`); return value; }
function boundedPositiveInteger(value, field, maximum) { const result = positiveInteger(value, field); if (result > maximum) throw new RangeError(`${field} must be at most ${maximum}`); return result; }
function positiveFinite(value, field) { if (!Number.isFinite(value) || value <= 0) throw new TypeError(`${field} must be a positive finite number`); return value; }
function nonNegativeInteger(value) { return Number.isSafeInteger(value) && value >= 0 ? value : null; }
function boundedError(error) { return sanitizeDiagnosticErrorMessage(error, { maxBytes: 256 }); }

function deepFreeze(value, seen = new WeakSet()) {
	if (value === null || typeof value !== 'object' || seen.has(value)) return value;
	seen.add(value);
	for (const child of Object.values(value)) deepFreeze(child, seen);
	return Object.freeze(value);
}
