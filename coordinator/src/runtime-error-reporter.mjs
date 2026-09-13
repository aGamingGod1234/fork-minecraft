import { BestEffortDiagnosticQueue } from './best-effort-diagnostic-queue.mjs';
import { sanitizeDiagnosticText } from './diagnostic-sanitizer.mjs';

const MAX_CODE_LENGTH = 128;
const MAX_MESSAGE_LENGTH = 512;
const MAX_STACK_LENGTH = 4_096;
const MAX_STACK_LINES = 16;
const MAX_CONTEXT_VALUE_LENGTH = 128;
const REFUSED_CODE = 'ECONNREFUSED';
const GENERIC_RUNTIME_ERROR = '[dynamic-coordinator] ERROR: runtime diagnostic unavailable\nError: runtime diagnostic unavailable\n';

const CONTEXT_FIELDS = Object.freeze([
	'agentId',
	'goalRevision',
	'lifecycleGeneration',
	'activeWorkKind',
	'provider',
	'model',
	'operation',
	'phase',
]);

/** Writes bounded, secret-free diagnostics for coordinator runtime failures. */
export class RuntimeErrorReporter {
	#refused = 0;
	#write;
	#queue;
	#incidents = new Map();

	constructor({ write = (line) => process.stderr.write(line), ...queueOptions } = {}) {
		if (typeof write !== 'function') throw new TypeError('runtime error reporter write must be a function');
		this.#write = write;
		this.#queue = new BestEffortDiagnosticQueue(queueOptions);
	}

	report(error, context = {}) {
		if (safeProperty(error, 'code') === REFUSED_CODE) {
			this.#refused += 1;
			if (this.#refused === 1) this.#emit('[dynamic-coordinator] ECONNREFUSED: Minecraft bridge is unavailable; retrying\n');
			return;
		}
		try {
			const line = formatUnexpectedRuntimeError(error, context);
			const incident = this.#incidents.get(line);
			if (incident !== undefined) {
				incident.count = Math.min(1_000_000, incident.count + 1);
				return;
			}
			if (this.#incidents.size >= 16) this.#incidents.delete(this.#incidents.keys().next().value);
			this.#incidents.set(line, { count: 1 });
			this.#emit(line);
		}
		catch { this.#emit(GENERIC_RUNTIME_ERROR); }
	}

	recovered() {
		if (this.#refused > 0) {
			this.#emit(`[dynamic-coordinator] BRIDGE_RECONNECTED after ${this.#refused} refused connection attempts\n`);
			this.#refused = 0;
		}
		this.#emitIncidentRecovery();
	}

	close() {
		return this.#queue.close();
	}

	#emit(line) {
		try { this.#queue.submit(() => this.#write(line)); } catch { /* diagnostics must not interrupt coordinator work */ }
	}

	#emitIncidentRecovery() {
		if (this.#incidents.size === 0) return;
		let repeated = 0;
		for (const incident of this.#incidents.values()) repeated += Math.max(0, incident.count - 1);
		this.#incidents.clear();
		this.#emit(`[dynamic-coordinator] RUNTIME_RECOVERED; ${repeated} repeated diagnostics suppressed\n`);
	}
}

export function formatUnexpectedRuntimeError(error, context = {}) {
	try {
		const code = safeCode(safeProperty(error, 'code'));
		const rawMessage = safeProperty(error, 'message');
		const message = diagnosticText(rawMessage ?? error ?? 'unknown runtime error', MAX_MESSAGE_LENGTH);
		const fields = formatContext(context);
		const summary = `[dynamic-coordinator] ${code}: ${message}${fields.length === 0 ? '' : ` ${fields}`}`;
		const stack = formatStack(safeProperty(error, 'stack'), message);
		return `${summary}\n${stack}\n`;
	} catch {
		return GENERIC_RUNTIME_ERROR;
	}
}

function safeProperty(value, field) {
	if (value === null || (typeof value !== 'object' && typeof value !== 'function')) return undefined;
	try { return value[field]; } catch { return undefined; }
}

function safeCode(value) {
	if (typeof value !== 'string' && typeof value !== 'number') return 'ERROR';
	const normalized = diagnosticText(value, MAX_CODE_LENGTH).replace(/\s+/g, '_');
	return normalized || 'ERROR';
}

function formatContext(context) {
	if (context === null || typeof context !== 'object' || Array.isArray(context)) return '';
	const fields = [];
	try {
		for (const field of CONTEXT_FIELDS) {
			let present;
			try { present = Object.hasOwn(context, field); } catch { continue; }
			if (!present) continue;
			let value;
			try { value = context[field]; } catch { continue; }
			if (value === null || value === undefined || typeof value === 'object' || typeof value === 'function') continue;
			const normalized = diagnosticText(value, MAX_CONTEXT_VALUE_LENGTH);
			if (normalized.length > 0) fields.push(`${field}=${normalized}`);
		}
	} catch {
		return fields.join(' ');
	}
	return fields.join(' ');
}

function formatStack(value, message) {
	const source = typeof value === 'string' && value.trim() !== '' ? value : `Error: ${message}`;
	const lines = source.split(/\r?\n/, MAX_STACK_LINES).map((line) => sanitizeStackLine(line));
	return truncate(lines.join('\n'), MAX_STACK_LENGTH);
}

function sanitizeStackLine(value) {
	let line = diagnosticText(value, MAX_STACK_LENGTH);
	line = line.replace(/\((?:file:\/\/|https?:\/\/|[A-Za-z]:[\\/]|\/)[^)]*\)/g, '(location redacted)');
	line = line.replace(/(?:file:\/\/|[A-Za-z]:[\\/]|\/)[^\s)]*\.(?:m?js|cjs|json|py|java)(?::\d+(?::\d+)?)?/gi, '[location redacted]');
	return line;
}

function diagnosticText(value, limit) {
	return sanitizeDiagnosticText(value, { maxBytes: limit });
}

function truncate(value, limit) {
	if (value.length <= limit) return value;
	return `${value.slice(0, Math.max(0, limit - 3))}...`;
}
