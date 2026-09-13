import { createHash } from 'node:crypto';
import { types as nodeTypes } from 'node:util';
import { appendFile, mkdir } from 'node:fs/promises';
import path from 'node:path';

import { BestEffortDiagnosticQueue } from './best-effort-diagnostic-queue.mjs';
import { DIAGNOSTIC_REDACTED, isOperationalTokenMetric, isSensitiveDiagnosticKey, sanitizeDiagnosticText, truncateDiagnosticUtf8 } from './diagnostic-sanitizer.mjs';
import { preparePrivateArtifact } from './private-artifact-permissions.mjs';
import { RotatingJsonlSink } from './rotating-jsonl-sink.mjs';

const REDACTED = DIAGNOSTIC_REDACTED;
const UNSAFE = '[UNSAFE_OBJECT]';
const BOUNDED = '[BOUNDED]';
const MAX_TRACE_STRING = 2_048;
const MAX_PRIVATE_SOURCE = 65_536;
const MAX_TRACE_ENTRIES = 64;
const MAX_TRACE_DEPTH = 8;
const MAX_TRACE_NODES = 512;
const MAX_TRACE_BYTES = 262_144;
// The JSONL newline consumes one byte, so the serialized object reserves it.
const MAX_TRACE_ROW_BYTES = MAX_TRACE_BYTES - 1;

/** Append-only bounded traces. Public rows never contain source text or credentials. */
export class TraceWriter {
	#filePath;
	#diagnosticFilePath;
	#sinks;
	#mkdir;
	#preparePrivateArtifact;
	#ready;
	#queue;
	#closed = false;
	#closePromise = null;

	constructor(filePath, dependencies = {}) {
		if (typeof filePath !== 'string' || filePath.trim().length === 0) throw new TypeError('trace file path must be nonblank');
		this.#filePath = path.resolve(filePath);
		const privatePath = dependencies.diagnosticFilePath ?? dependencies.privateFilePath ?? null;
		if (privatePath !== null && (typeof privatePath !== 'string' || privatePath.trim().length === 0)) throw new TypeError('diagnostic trace file path must be nonblank');
		this.#diagnosticFilePath = privatePath === null ? null : path.resolve(privatePath);
		const append = dependencies.appendFile ?? appendFile;
		this.#mkdir = dependencies.mkdir ?? mkdir;
		this.#preparePrivateArtifact = dependencies.preparePrivateArtifact
			?? (dependencies.appendFile === undefined && dependencies.mkdir === undefined
				? preparePrivateArtifact : async () => {});
		this.#sinks = new Map([this.#filePath, this.#diagnosticFilePath].filter(Boolean).map((filePath) => [filePath, new RotatingJsonlSink(filePath, {
			appendFile: append,
			stat: dependencies.stat,
			rename: dependencies.rename,
			unlink: dependencies.unlink,
			maxFileBytes: dependencies.maxFileBytes,
			maxFileAgeMs: dependencies.maxFileAgeMs,
			retainedGenerations: dependencies.retainedGenerations,
			now: dependencies.now,
			inspect: dependencies.appendFile === undefined || dependencies.stat !== undefined
				|| dependencies.maxFileBytes !== undefined || dependencies.maxFileAgeMs !== undefined,
		})]));
		this.#queue = new BestEffortDiagnosticQueue({
			maxPending: dependencies.maxPending,
			operationTimeoutMs: dependencies.operationTimeoutMs,
			closeTimeoutMs: dependencies.closeTimeoutMs,
			schedule: dependencies.schedule,
			cancel: dependencies.cancel,
			dispatch: dependencies.dispatch,
			now: dependencies.now,
		});
		const directories = [path.dirname(this.#filePath), this.#diagnosticFilePath === null ? null : path.dirname(this.#diagnosticFilePath)].filter(Boolean);
		this.#ready = Promise.all([...new Set(directories)].map((directory) => this.#mkdir(directory, { recursive: true, mode: 0o700 })))
			.then(() => Promise.all([this.#filePath, this.#diagnosticFilePath].filter(Boolean).map((filePath) => this.#preparePrivateArtifact(filePath))))
			.then(() => true, () => false);
	}

	write(eventOrRow, fields = {}) {
		if (this.#closed) return Promise.resolve();
		try {
			const row = normalizeRow(eventOrRow, fields);
			this.#enqueue(this.#filePath, publicTraceRow(row));
		} catch { /* invalid diagnostics are dropped at this boundary */ }
		return Promise.resolve();
	}

	/** Writes bounded source for the agent-private diagnostic trace only. */
	writeDiagnostic(eventOrRow, fields = {}) {
		if (this.#closed) return Promise.resolve();
		if (this.#diagnosticFilePath === null) return Promise.resolve();
		try {
			const row = normalizeRow(eventOrRow, fields);
			this.#enqueue(this.#diagnosticFilePath, privateTraceRow(row));
		} catch { /* invalid diagnostics are dropped at this boundary */ }
		return Promise.resolve();
	}

	close() {
		if (this.#closePromise !== null) return this.#closePromise;
		this.#closed = true;
		this.#closePromise = this.#queue.close();
		return this.#closePromise;
	}

	statusSnapshot() {
		return this.#queue.statusSnapshot('diagnostics');
	}

	#enqueue(filePath, row) {
		const encoded = `${JSON.stringify(row)}\n`;
		this.#queue.submit(async () => {
			if (!await this.#ready) throw new Error('trace sink directory is unavailable');
			await this.#sinks.get(filePath).append(encoded, { encoding: 'utf8', flag: 'a', mode: 0o600 });
		});
	}
}

export function observationHash(observation) {
	if (observation === null || observation === undefined) return null;
	return createHash('sha256').update(JSON.stringify(sanitizeValue(observation, context(false, false))), 'utf8').digest('hex');
}

/** Returns a safe, null-prototype, own-data-only redacted copy. */
export function redact(value) {
	return sanitizeValue(value, context(false, false));
}

function normalizeRow(eventOrRow, fields) {
	if (typeof eventOrRow === 'string') {
		if (eventOrRow.trim().length === 0) throw new TypeError('trace event must be nonblank');
		if (fields === null || typeof fields !== 'object' || Array.isArray(fields)) throw new TypeError('trace fields must be an object');
		return Object.assign(Object.create(null), { event: eventOrRow }, ownData(fields));
	}
	if (eventOrRow === null || typeof eventOrRow !== 'object' || Array.isArray(eventOrRow)) throw new TypeError('trace row must be an object or event name');
	return ownData(eventOrRow);
}

function ownData(value) {
	if (nodeTypes.isProxy(value)) return Object.assign(Object.create(null), { event: UNSAFE });
	const result = Object.create(null);
	let keys;
	try { keys = Reflect.ownKeys(value); } catch { return Object.assign(result, { event: UNSAFE }); }
	for (const key of keys) {
		if (typeof key !== 'string') continue;
		let descriptor;
		try { descriptor = Object.getOwnPropertyDescriptor(value, key); } catch { continue; }
		if (!descriptor?.enumerable || !Object.hasOwn(descriptor, 'value')) continue;
		result[key] = descriptor.value;
		if (Object.keys(result).length >= MAX_TRACE_ENTRIES) break;
	}
	return result;
}

function publicTraceRow(row) {
	const source = ownData(row).source;
	const sourceHash = typeof source === 'string' ? `sha256:${hashSource(source)}` : null;
	const result = sanitizeValue(row, context(false, true));
	if (result && typeof result === 'object' && !Array.isArray(result)) {
		delete result.source;
		if (sourceHash !== null) result.sourceHash = sourceHash;
	}
	return boundSerializedRow(result);
}

function privateTraceRow(row) {
	const source = ownData(row).source;
	const sourceHash = typeof source === 'string' ? `sha256:${hashSource(source)}` : null;
	const result = sanitizeValue(row, context(true, true));
	if (result && typeof result === 'object' && !Array.isArray(result) && sourceHash !== null) result.sourceHash = sourceHash;
	return boundSerializedRow(result);
}

function context(allowSource, redactPaths) {
	return { allowSource, redactPaths, seen: new WeakSet(), nodes: 0, bytes: 0 };
}

function sanitizeValue(value, state, depth = 0, key = null) {
	const root = { value: undefined };
	const work = [{ value, assign: (result) => { root.value = result; }, depth, key }];
	while (work.length > 0) {
		const task = work.pop();
		const input = task.value;
		if (typeof input === 'string') {
			task.assign(sanitizeString(input, state, task.key === 'source' && state.allowSource));
			continue;
		}
		if (input === null || typeof input !== 'object') {
			task.assign(['bigint', 'function', 'symbol'].includes(typeof input) ? UNSAFE : input);
			continue;
		}
		if (task.depth > MAX_TRACE_DEPTH || state.nodes++ >= MAX_TRACE_NODES) {
			task.assign(BOUNDED);
			continue;
		}
		if (nodeTypes.isProxy(input)) {
			task.assign(UNSAFE);
			continue;
		}
		if (state.seen.has(input)) {
			task.assign('[CIRCULAR]');
			continue;
		}
		state.seen.add(input);
		let keys;
		try { keys = Reflect.ownKeys(input); } catch { task.assign(UNSAFE); continue; }
		const output = Array.isArray(input) ? [] : Object.create(null);
		task.assign(output);
		const children = [];
		let entries = 0;
		for (const property of keys) {
			if (typeof property !== 'string' || entries >= MAX_TRACE_ENTRIES) continue;
			let descriptor;
			try { descriptor = Object.getOwnPropertyDescriptor(input, property); } catch { continue; }
			if (!descriptor?.enumerable || !Object.hasOwn(descriptor, 'value')) continue;
			entries += 1;
			if (isSensitiveDiagnosticKey(property) && !isOperationalTokenMetric(property, descriptor.value)) output[property] = REDACTED;
			else if (property === 'source' && !state.allowSource) continue;
			else children.push({ value: descriptor.value, assign: (result) => { Object.defineProperty(output, property, { enumerable: true, configurable: true, writable: true, value: result }); }, depth: task.depth + 1, key: property });
		}
		for (let index = children.length - 1; index >= 0; index -= 1) {
			work.push(children[index]);
		}
	}
	return root.value;
}

function boundSerializedRow(row) {
	let encoded;
	try { encoded = JSON.stringify(row); } catch { encoded = null; }
	if (encoded !== null && Buffer.byteLength(encoded, 'utf8') <= MAX_TRACE_ROW_BYTES) return row;
	const bounded = Object.create(null);
	Object.defineProperty(bounded, 'truncated', { enumerable: true, configurable: true, writable: true, value: BOUNDED });
	for (const key of Object.keys(row)) {
		if (key !== 'event' && !['string', 'number', 'boolean'].includes(typeof row[key]) && row[key] !== null) continue;
		Object.defineProperty(bounded, key, { enumerable: true, configurable: true, writable: true, value: row[key] });
		let candidate;
		try { candidate = JSON.stringify(bounded); } catch { delete bounded[key]; continue; }
		if (Buffer.byteLength(candidate, 'utf8') > MAX_TRACE_ROW_BYTES) delete bounded[key];
	}
	return bounded;
}

function sanitizeString(value, state, allowLongSource) {
	const limit = allowLongSource ? MAX_PRIVATE_SOURCE : MAX_TRACE_STRING;
	const remaining = Math.max(0, MAX_TRACE_BYTES - state.bytes);
	const boundedLimit = Math.min(limit, remaining);
	if (boundedLimit <= 3) return '';
	const redacted = sanitizeDiagnosticText(value, { maxBytes: boundedLimit, redactPaths: state.redactPaths });
	const candidate = Buffer.byteLength(redacted, 'utf8') < Buffer.byteLength(value, 'utf8')
		? truncateDiagnosticUtf8(`${redacted}...`, boundedLimit) : redacted;
	const result = truncateUtf8(candidate, remaining);
	state.bytes += Buffer.byteLength(result, 'utf8');
	return result;
}

function truncateUtf8(value, limit) {
	return truncateDiagnosticUtf8(value, limit);
}

function hashSource(source) {
	return createHash('sha256').update(source, 'utf8').digest('hex');
}
