import { types as nodeTypes } from 'node:util';

export const DIAGNOSTIC_REDACTED = '[REDACTED]';
export const DIAGNOSTIC_UNSAFE = '[UNSAFE_OBJECT]';

const CREDENTIAL_KEY = '[A-Za-z0-9_-]{0,64}(?:authorization|api[_-]?key|access[_-]?token|refresh[_-]?token|client[_-]?secret|secret|password|token|credential|oauth|launcher[_-]?account|account[_-]?data)[A-Za-z0-9_-]{0,64}';
const ASSIGNMENT_VALUE = '("(?:\\\\.|[^"\\\\])*"|\'(?:\\\\.|[^\'\\\\])*\'|[^\\s,;)}\\]]+)';
const QUOTED_ASSIGNMENT = new RegExp(`(["'])(${CREDENTIAL_KEY})\\1(\\s*[:=]\\s*)${ASSIGNMENT_VALUE}`, 'gi');
const BARE_ASSIGNMENT = new RegExp(`\\b(${CREDENTIAL_KEY})(\\s*[:=]\\s*)${ASSIGNMENT_VALUE}`, 'gi');
const AUTHORIZATION = /\b(authorization\s*[:=]\s*)(Basic|Bearer)\s+("(?:\\.|[^"\\])*"|'(?:\\.|[^'\\])*'|[A-Za-z0-9._~+/=-]+)/gi;
const BEARER = /\b(Bearer)\s+("(?:\\.|[^"\\])*"|'(?:\\.|[^'\\])*'|[A-Za-z0-9._~+/=-]+)/gi;
const RAW_PROMPT = /(?:raw\s+)?prompt\s*[:=]\s*[^\r\n]*/gi;
const QUOTED_FILE_URI = /(["'])file:\/\/(?=\/|[^\/\s"']+\/)[^"'\r\n]+\1/gi;
const FILE_URI = /(^|[\s(=\[])file:\/\/(?:\/(?:[A-Za-z]:[\\/])?|[^\/\s,;)}\]"']+[\\/])[^\s,;)}\]"']+/gi;
const QUOTED_ABSOLUTE_PATH = /(["'])((?:[A-Za-z]:[\\/]|\\\\[^\\/\r\n"']+[\\/][^\\/\r\n"']+[\\/]|\/(?!\/))[^"'\r\n]+)\1/g;
const WINDOWS_PATH = /\b[A-Za-z]:[\\/][^\s,;)}\]"']+/g;
const UNC_PATH = /\\\\[^\\/\s]+[\\/][^\s,;)}\]"']+/g;
const POSIX_PATH = /(^|[\s(=\[])(\/(?!\/)[^\s,;)}\]"']+)/g;

export function isSensitiveDiagnosticKey(value) {
	if (typeof value !== 'string') return false;
	const normalized = normalizeDiagnosticKey(value);
	if (operationalTokenMetricKind(value) !== null) return true;
	return /(?:^|_)(?:authorization|api_key|access_token|refresh_token|client_secret|secret|password|token|credential|oauth)(?:_|$)/.test(normalized)
		|| normalized === 'tokens'
		|| normalized === 'launcher_account'
		|| normalized === 'launcheraccount'
		|| normalized === 'account_data'
		|| normalized === 'accountdata';
}

export function sanitizeDiagnosticText(value, { maxBytes = 2_048, redactPaths = true } = {}) {
	if (!Number.isSafeInteger(maxBytes) || maxBytes < 0) throw new TypeError('maxBytes must be a nonnegative safe integer');
	let text;
	try { text = String(value ?? ''); } catch { text = '[unavailable]'; }
	text = text
		.replace(AUTHORIZATION, (_match, prefix, scheme) => `${prefix}${scheme} ${DIAGNOSTIC_REDACTED}`)
		.replace(BEARER, (_match, scheme) => `${scheme} ${DIAGNOSTIC_REDACTED}`)
		.replace(QUOTED_ASSIGNMENT, redactQuotedSensitiveAssignment)
		.replace(BARE_ASSIGNMENT, redactBareSensitiveAssignment)
		.replace(RAW_PROMPT, DIAGNOSTIC_REDACTED);
	if (redactPaths) {
		text = text
			.replace(QUOTED_FILE_URI, '[location redacted]')
			.replace(FILE_URI, (_match, prefix) => `${prefix}[location redacted]`)
			.replace(QUOTED_ABSOLUTE_PATH, '[location redacted]')
			.replace(UNC_PATH, '[location redacted]')
			.replace(WINDOWS_PATH, '[location redacted]')
			.replace(POSIX_PATH, (_match, prefix) => `${prefix}[location redacted]`);
	}
	text = text.replace(/[\u0000-\u001f\u007f]+/g, ' ').trim();
	return truncateDiagnosticUtf8(text, maxBytes);
}

export function sanitizeDiagnosticValue(value, {
	maxDepth = 8,
	maxEntries = 64,
	maxNodes = 512,
	maxStringBytes = 2_048,
	redactPaths = true,
} = {}) {
	const seen = new WeakSet();
	let nodes = 0;
	const visit = (input, depth, key = null) => {
		if (isSensitiveDiagnosticKey(key) && !isOperationalTokenMetric(key, input)) return DIAGNOSTIC_REDACTED;
		if (typeof input === 'string') return sanitizeDiagnosticText(input, { maxBytes: maxStringBytes, redactPaths });
		if (input === null || ['boolean', 'number'].includes(typeof input)) return input;
		if (['undefined', 'bigint', 'function', 'symbol'].includes(typeof input)) return DIAGNOSTIC_UNSAFE;
		if (depth > maxDepth || nodes >= maxNodes) return '[BOUNDED]';
		nodes += 1;
		if (nodeTypes.isProxy(input)) return DIAGNOSTIC_UNSAFE;
		if (seen.has(input)) return '[CIRCULAR]';
		seen.add(input);
		let keys;
		try { keys = Reflect.ownKeys(input); } catch { return DIAGNOSTIC_UNSAFE; }
		const output = Array.isArray(input) ? [] : Object.create(null);
		let entries = 0;
		for (const property of keys) {
			if (typeof property !== 'string' || entries >= maxEntries) continue;
			let descriptor;
			try { descriptor = Object.getOwnPropertyDescriptor(input, property); } catch { continue; }
			if (!descriptor?.enumerable || !Object.hasOwn(descriptor, 'value')) continue;
			entries += 1;
			output[property] = visit(descriptor.value, depth + 1, property);
		}
		return output;
	};
	return visit(value, 0);
}

export function sanitizeDiagnosticErrorMessage(error, {
	fallback = 'unknown error',
	maxBytes = 2_048,
	redactPaths = true,
} = {}) {
	const direct = typeof error === 'string' || typeof error === 'number' || typeof error === 'boolean'
		? error
		: ownDataProperty(error, 'message');
	return sanitizeDiagnosticText(direct ?? fallback, { maxBytes, redactPaths });
}

export function sanitizeDiagnosticErrorStack(error, {
	fallback = 'unknown error',
	maxBytes = 4_096,
	redactPaths = true,
} = {}) {
	const stack = ownDataProperty(error, 'stack');
	if (typeof stack === 'string') return sanitizeDiagnosticText(stack, { maxBytes, redactPaths });
	return sanitizeDiagnosticErrorMessage(error, { fallback, maxBytes, redactPaths });
}

export function sanitizeDiagnosticErrorCode(error, { fallback = 'UNKNOWN', maxBytes = 128 } = {}) {
	return sanitizeDiagnosticCode(ownDataProperty(error, 'code'), { fallback, maxBytes });
}

export function sanitizeDiagnosticCode(value, { fallback = 'UNKNOWN', maxBytes = 128 } = {}) {
	if (typeof fallback !== 'string' || !/^[A-Z][A-Z0-9_:-]*$/.test(fallback)) throw new TypeError('diagnostic error code fallback must be an uppercase code');
	if (!Number.isSafeInteger(maxBytes) || maxBytes < 1) throw new TypeError('diagnostic error code maxBytes must be a positive safe integer');
	if (typeof value !== 'string' || !/^[A-Z][A-Z0-9_:-]*$/.test(value) || Buffer.byteLength(value, 'utf8') > maxBytes) return fallback;
	return value;
}

function ownDataProperty(value, property) {
	if (value === null || (typeof value !== 'object' && typeof value !== 'function') || nodeTypes.isProxy(value)) return undefined;
	let descriptor;
	try { descriptor = Object.getOwnPropertyDescriptor(value, property); } catch { return undefined; }
	return descriptor !== undefined && Object.hasOwn(descriptor, 'value') ? descriptor.value : undefined;
}

export function isOperationalTokenMetric(key, value) {
	const kind = operationalTokenMetricKind(key);
	if (kind === null) return false;
	if (kind === 'latency') return typeof value === 'number' && Number.isFinite(value) && value >= 0 && value <= Number.MAX_SAFE_INTEGER;
	if (kind === 'integer') return Number.isSafeInteger(value) && value >= 0;
	if (value === null || typeof value !== 'object' || Array.isArray(value) || nodeTypes.isProxy(value)) return false;
	let keys;
	try { keys = Reflect.ownKeys(value); } catch { return false; }
	const categories = new Set(['input', 'output', 'reasoning', 'cached', 'cacheWrite']);
	for (const category of keys) {
		if (typeof category !== 'string' || !categories.has(category)) return false;
		let descriptor;
		try { descriptor = Object.getOwnPropertyDescriptor(value, category); } catch { return false; }
		if (!descriptor?.enumerable || !Object.hasOwn(descriptor, 'value')) return false;
		const count = descriptor.value;
		if (count !== null && !(Number.isSafeInteger(count) && count >= 0)) return false;
	}
	return true;
}

function operationalTokenMetricKind(key) {
	if (typeof key !== 'string') return null;
	const normalized = normalizeDiagnosticKey(key);
	if (normalized === 'tokens') return 'object';
	if (normalized === 'token_latency_ms') return 'latency';
	if (/^(?:(?:input|output|prompt|completion|cached|reasoning|total)_tokens?(?:_count)?|tokens?_count|token_(?:bucket|budget|limit|usage|remaining))$/.test(normalized)) return 'integer';
	return null;
}

function normalizeDiagnosticKey(value) {
	return value
		.replace(/([a-z0-9])([A-Z])/g, '$1_$2')
		.replace(/[\s-]+/g, '_')
		.toLowerCase();
}

export function truncateDiagnosticUtf8(value, maxBytes) {
	if (Buffer.byteLength(value, 'utf8') <= maxBytes) return value;
	return Buffer.from(value, 'utf8').subarray(0, maxBytes).toString('utf8').replace(/\uFFFD$/u, '');
}

function redactQuotedSensitiveAssignment(match, quote, key, separator, rawValue) {
	if (!isSensitiveDiagnosticKey(key)) return match;
	if (isOperationalTokenTextMetric(key, rawValue)) return match;
	const valueQuote = rawValue[0] === '"' || rawValue[0] === "'" ? rawValue[0] : '';
	return `${quote}${key}${quote}${separator}${valueQuote}${DIAGNOSTIC_REDACTED}${valueQuote}`;
}

function redactBareSensitiveAssignment(match, key, separator, rawValue) {
	if (!isSensitiveDiagnosticKey(key)) return match;
	if (isOperationalTokenTextMetric(key, rawValue)) return match;
	const valueQuote = rawValue[0] === '"' || rawValue[0] === "'" ? rawValue[0] : '';
	return `${key}${separator}${valueQuote}${DIAGNOSTIC_REDACTED}${valueQuote}`;
}

function isOperationalTokenTextMetric(key, rawValue) {
	if (typeof rawValue !== 'string' || /^['"]/.test(rawValue)) return false;
	const kind = operationalTokenMetricKind(key);
	if (kind === 'integer') {
		if (!/^(?:0|[1-9]\d*)$/.test(rawValue)) return false;
		return isOperationalTokenMetric(key, Number(rawValue));
	}
	if (kind === 'latency') {
		if (!/^(?:0|[1-9]\d*)(?:\.\d+)?$/.test(rawValue)) return false;
		return isOperationalTokenMetric(key, Number(rawValue));
	}
	return false;
}
