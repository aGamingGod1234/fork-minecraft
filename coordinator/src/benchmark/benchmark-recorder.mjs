import { randomUUID } from 'node:crypto';
import { access, mkdir, rename, unlink, writeFile } from 'node:fs/promises';
import path from 'node:path';

import { redact } from '../trace-writer.mjs';
import { classifyBenchmarkError, normalizeBenchmarkErrorCode, summarizeBenchmark } from './benchmark-report.mjs';

const DEFAULT_MAX_EVENTS = 100_000;
const DEFAULT_MAX_IDENTITY_LENGTH = 128;
const DEFAULT_MAX_FIELD_STRING_LENGTH = 2_048;
const MAX_EVENT_CAP = 1_000_000;
const REDACTED = '[REDACTED]';

export const BENCHMARK_EVENTS_FILENAME = 'benchmark-events.jsonl';
export const BENCHMARK_SUMMARY_FILENAME = 'benchmark-summary.json';

/** Bounded, append-only raw benchmark event recorder. */
export class BenchmarkRecorder {
	#clock;
	#maxEvents;
	#maxIdentityLength;
	#maxFieldStringLength;
	#baseContext;
	#events = [];
	#droppedEvents = 0;
	#sequence = 0;
	#lastTimestamp = null;
	#write;

	constructor(options = {}, dependencies = {}) {
		if (options === null || typeof options !== 'object' || Array.isArray(options)) throw new TypeError('benchmark recorder options must be an object');
		const deps = options.dependencies ?? dependencies;
		this.#clock = options.clock ?? (() => performance.now());
		if (typeof this.#clock !== 'function') throw new TypeError('benchmark recorder clock must be a function');
		this.#maxEvents = positiveInteger(options.maxEvents ?? DEFAULT_MAX_EVENTS, 'maxEvents', MAX_EVENT_CAP);
		this.#maxIdentityLength = positiveInteger(options.maxIdentityLength ?? DEFAULT_MAX_IDENTITY_LENGTH, 'maxIdentityLength', 1_024);
		this.#maxFieldStringLength = positiveInteger(options.maxFieldStringLength ?? DEFAULT_MAX_FIELD_STRING_LENGTH, 'maxFieldStringLength', 65_536);
		this.#baseContext = normalizeValue(options.baseContext ?? options.context ?? {}, this.#maxIdentityLength, this.#maxFieldStringLength);
		this.#write = {
			mkdir: deps.mkdir ?? mkdir,
			writeFile: deps.writeFile ?? writeFile,
			rename: deps.rename ?? rename,
			unlink: deps.unlink ?? unlink,
			access: deps.access ?? access,
			randomUUID: deps.randomUUID ?? randomUUID,
		};
		for (const [name, fn] of Object.entries(this.#write)) if (typeof fn !== 'function') throw new TypeError(`benchmark recorder ${name} dependency must be a function`);
	}

	record(stage, context = {}, fields = {}) {
		const normalizedStage = normalizeIdentity(stage, this.#maxIdentityLength, 'stage');
		if (context === null || typeof context !== 'object' || Array.isArray(context)) throw new TypeError('benchmark event context must be an object');
		if (fields === null || typeof fields !== 'object' || Array.isArray(fields)) throw new TypeError('benchmark event fields must be an object');
		if (this.#events.length >= this.#maxEvents) {
			this.#droppedEvents += 1;
			return null;
		}

		const safeContext = mergeContext(this.#baseContext, normalizeValue(context, this.#maxIdentityLength, this.#maxFieldStringLength));
		const safeFields = normalizeValue(fields, this.#maxIdentityLength, this.#maxFieldStringLength);
		const errorCode = normalizeBenchmarkErrorCode(safeFields.errorCode ?? safeFields.error);
		if (errorCode !== null) {
			safeFields.errorCode = errorCode;
			safeFields.errorCategory = safeFields.errorCategory === undefined
				? classifyBenchmarkError(errorCode)
				: normalizeIdentity(String(safeFields.errorCategory), 64, 'errorCategory');
		}
		const timestamp = this.#timestamp();
		const sequence = ++this.#sequence;
		const row = {
			...safeContext,
			...safeFields,
			sequence,
			stage: normalizedStage,
			monotonicMs: timestamp,
			fields: safeFields,
		};
		const frozen = deepFreeze(row);
		this.#events.push(frozen);
		return frozen;
	}

	/** Returns a frozen copy that can also be passed directly to summarizeBenchmark. */
	snapshot() {
		const events = this.#events.slice();
		Object.defineProperties(events, {
			events: { configurable: false, enumerable: false, value: events, writable: false },
			droppedEvents: { configurable: false, enumerable: false, value: this.#droppedEvents, writable: false },
			maxEvents: { configurable: false, enumerable: false, value: this.#maxEvents, writable: false },
		});
		return Object.freeze(events);
	}

	async writeArtifacts(directory) {
		if (typeof directory !== 'string' || directory.trim().length === 0) throw new TypeError('benchmark artifact directory must be nonblank');
		const targetDirectory = path.resolve(directory);
		const eventsPath = path.join(targetDirectory, BENCHMARK_EVENTS_FILENAME);
		const summaryPath = path.join(targetDirectory, BENCHMARK_SUMMARY_FILENAME);
		const nonce = this.#write.randomUUID();
		const eventsTempPath = path.join(targetDirectory, `.${BENCHMARK_EVENTS_FILENAME}.${process.pid}.${nonce}.tmp`);
		const summaryTempPath = path.join(targetDirectory, `.${BENCHMARK_SUMMARY_FILENAME}.${process.pid}.${nonce}.tmp`);
		const eventsBackupPath = path.join(targetDirectory, `.${BENCHMARK_EVENTS_FILENAME}.${process.pid}.${nonce}.bak`);
		const summaryBackupPath = path.join(targetDirectory, `.${BENCHMARK_SUMMARY_FILENAME}.${process.pid}.${nonce}.bak`);
		const events = this.snapshot();
		const summary = summarizeBenchmark(events);
		const jsonl = events.length === 0 ? '' : `${events.map((event) => JSON.stringify(event)).join('\n')}\n`;
		const state = {
			events: { backupPath: eventsBackupPath, backedUp: false, published: false },
			summary: { backupPath: summaryBackupPath, backedUp: false, published: false },
		};
		try {
			await this.#write.mkdir(targetDirectory, { recursive: true });
			await this.#write.writeFile(eventsTempPath, jsonl, { encoding: 'utf8' });
			await this.#write.writeFile(summaryTempPath, `${JSON.stringify(summary, null, 2)}\n`, { encoding: 'utf8' });
			await this.#backup(eventsPath, state.events);
			await this.#backup(summaryPath, state.summary);
			await this.#write.rename(eventsTempPath, eventsPath);
			state.events.published = true;
			await this.#write.rename(summaryTempPath, summaryPath);
			state.summary.published = true;
			await Promise.allSettled([this.#write.unlink(eventsBackupPath), this.#write.unlink(summaryBackupPath)]);
			return Object.freeze({ eventsPath, summaryPath, eventCount: events.length, droppedEvents: events.droppedEvents });
		} catch (error) {
			await this.#rollback(eventsPath, eventsTempPath, state.events);
			await this.#rollback(summaryPath, summaryTempPath, state.summary);
			throw error;
		}
	}

	async #backup(finalPath, state) {
		if (!await this.#exists(finalPath)) return;
		await this.#write.rename(finalPath, state.backupPath);
		state.backedUp = true;
	}

	async #rollback(finalPath, tempPath, state) {
		if (state.published) await this.#safeUnlink(finalPath);
		if (state.backedUp) {
			if (await this.#exists(finalPath)) await this.#safeUnlink(finalPath);
			try { await this.#write.rename(state.backupPath, finalPath); }
			catch { /* preserve the backup when the filesystem cannot restore it */ }
		} else if (state.published) {
			await this.#safeUnlink(finalPath);
		}
		await this.#safeUnlink(tempPath);
		if (!state.backedUp || await this.#exists(finalPath)) await this.#safeUnlink(state.backupPath);
	}

	async #safeUnlink(filePath) {
		try { await this.#write.unlink(filePath); } catch { /* cleanup is best effort */ }
	}

	async #exists(filePath) {
		try { await this.#write.access(filePath); return true; }
		catch { return false; }
	}

	#timestamp() {
		let candidate;
		try { candidate = this.#clock(); } catch { candidate = null; }
		if (!Number.isFinite(candidate) || candidate < 0) candidate = this.#lastTimestamp ?? 0;
		if (this.#lastTimestamp !== null && candidate < this.#lastTimestamp) candidate = this.#lastTimestamp;
		this.#lastTimestamp = candidate;
		return candidate;
	}
}

export { classifyBenchmarkError, normalizeBenchmarkErrorCode, summarizeBenchmark };

function positiveInteger(value, field, max) {
	if (!Number.isSafeInteger(value) || value < 1 || value > max) throw new TypeError(`${field} must be a positive safe integer at most ${max}`);
	return value;
}

function normalizeIdentity(value, limit, field) {
	if (typeof value !== 'string') throw new TypeError(`${field} must be a string`);
	const normalized = value.trim();
	if (normalized.length === 0) throw new TypeError(`${field} must be nonblank`);
	return normalized.slice(0, limit);
}

function mergeContext(base, current) {
	return { ...base, ...current };
}

function normalizeValue(value, identityLimit, fieldStringLimit) {
	const redacted = redact(value);
	return boundTree(redacted, identityLimit, fieldStringLimit);
}

function boundTree(value, identityLimit, fieldStringLimit, key = null, depth = 0) {
	if (depth > 8) return '[BOUNDED]';
	if (key !== null && isSensitiveKey(key)) return REDACTED;
	if (typeof value === 'string') {
		const limit = key !== null && isIdentityKey(key) ? identityLimit : fieldStringLimit;
		return sanitizeCredentialText(value).slice(0, limit);
	}
	if (value === null || typeof value !== 'object') return value;
	if (Array.isArray(value)) return value.map((child) => boundTree(child, identityLimit, fieldStringLimit, key, depth + 1));
	const result = Object.create(null);
	for (const [childKey, child] of Object.entries(value)) result[childKey.slice(0, identityLimit)] = boundTree(child, identityLimit, fieldStringLimit, childKey, depth + 1);
	return result;
}

function isIdentityKey(key) {
	return /(?:^|[_-])(id|revision|run|trial|scenario|seed|arm|agent|provider|model|operation|stage|profile|effort|tier)(?:$|[_-])/i.test(key)
		|| /^(?:id|runId|trialId|scenarioId|seed|arm|agentId|provider|model|reasoningEffort|serviceTier)$/i.test(key);
}

function isSensitiveKey(key) {
	return /(?:authorization|api[_-]?key|access[_-]?token|refresh[_-]?token|client[_-]?secret|secret|password|cookie|token|credential|oauth|bearer|login)/i.test(key);
}

function sanitizeCredentialText(value) {
	return value
		.replace(/\b(cookie|authorization|provider[-_ ]?login|oauth)(?:\s+(?:token|bearer))?\s*(?::|=|\s)\s*([^\s,;)}\]"']+)/gi, (_match, label) => `${label}: [REDACTED]`)
		.replace(/\bBearer\s+([A-Za-z0-9._~+/=-]+)/gi, 'Bearer [REDACTED]')
		.replace(/\bsk-(?:fish-)?[A-Za-z0-9][A-Za-z0-9._~+/=-]{8,}/g, '[REDACTED]');
}

function deepFreeze(value, seen = new WeakSet()) {
	if (value === null || typeof value !== 'object' || seen.has(value)) return value;
	seen.add(value);
	for (const child of Object.values(value)) deepFreeze(child, seen);
	return Object.freeze(value);
}
