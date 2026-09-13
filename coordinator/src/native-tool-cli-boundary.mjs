import {
	DIAGNOSTIC_REDACTED,
	sanitizeDiagnosticErrorCode,
	sanitizeDiagnosticErrorMessage,
	sanitizeDiagnosticErrorStack,
	sanitizeDiagnosticValue,
} from './diagnostic-sanitizer.mjs';

export const NATIVE_TOOL_CLI_FAILURE = 1;
export const NATIVE_TOOL_CLI_MAX_LINE_BYTES = 8 * 1_024;

const CREDENTIAL_SHAPED_VALUE = /(?:authorization|api[_-]?key|access[_-]?token|refresh[_-]?token|client[_-]?secret|secret|password|credential|oauth|bearer\s+|\bsk-(?:fish-)?[A-Za-z0-9][A-Za-z0-9._~+/=-]{8,}|\bgh[pousr]_[A-Za-z0-9]{8,}|\bAIza[0-9A-Za-z_-]{20,})/i;

/** Runs one native benchmark/probe CLI behind a bounded, redacted terminal boundary. */
export async function runNativeToolCli(run, { stdout = process.stdout, stderr = process.stderr } = {}) {
	try {
		const outcome = await run({
			progress: (value) => writeRecord(stdout, { type: 'PROGRESS', ...value }),
		});
		let terminal = prepareRecord(outcome);
		if (!['PASSED', 'FAILED'].includes(terminal.status)) terminal = prepareRecord({ status: 'FAILED', code: 'INVALID_OUTCOME' });
		stdout.write(terminal.line);
		return terminal.status === 'FAILED' ? NATIVE_TOOL_CLI_FAILURE : 0;
	} catch (error) {
		const failure = {
			status: 'FAILED',
			code: sanitizeDiagnosticErrorCode(error, { fallback: 'ERROR', maxBytes: 64 }),
			message: containRedactedLocation(sanitizeDiagnosticErrorMessage(error, { maxBytes: 1_024 })),
			stack: containRedactedLocation(sanitizeDiagnosticErrorStack(error, { maxBytes: 4_096 })),
		};
		try {
			writeRecord(stdout, failure);
		} catch {
			try { stderr.write('{"status":"FAILED","code":"ERROR","message":"diagnostic output unavailable","stack":"diagnostic output unavailable"}\n'); }
			catch { /* No terminal remains. The exit code still reports failure. */ }
		}
		return NATIVE_TOOL_CLI_FAILURE;
	}
}

export function serializeNativeToolCliRecord(value, { maxLineBytes = NATIVE_TOOL_CLI_MAX_LINE_BYTES } = {}) {
	return prepareRecord(value, { maxLineBytes }).line;
}

function prepareRecord(value, { maxLineBytes = NATIVE_TOOL_CLI_MAX_LINE_BYTES } = {}) {
	if (!Number.isSafeInteger(maxLineBytes) || maxLineBytes < 256) throw new TypeError('maxLineBytes must be an integer of at least 256');
	const source = sanitizeDiagnosticValue(value, {
		maxDepth: 12,
		maxEntries: 256,
		maxNodes: 8_192,
		maxStringBytes: 8_192,
	});
	let maxDepth = 12;
	let maxEntries = 256;
	let maxNodes = 8_192;
	let maxStringBytes = 8_192;
	for (let attempt = 0; attempt < 12; attempt += 1) {
		let candidate = sanitizeDiagnosticValue(source, { maxDepth, maxEntries, maxNodes, maxStringBytes });
		candidate = redactCredentialShapedValues(candidate);
		if (attempt > 0 && candidate !== null && typeof candidate === 'object' && !Array.isArray(candidate)) candidate.bounded = true;
		const line = `${JSON.stringify(candidate)}\n`;
		if (Buffer.byteLength(line, 'utf8') <= maxLineBytes) return { line, status: safeStatus(candidate) };
		maxStringBytes = Math.max(32, Math.floor(maxStringBytes / 2));
		maxEntries = Math.max(2, Math.floor(maxEntries / 2));
		maxNodes = Math.max(16, Math.floor(maxNodes / 2));
		maxDepth = Math.max(2, maxDepth - 1);
	}
	const status = source?.status === 'PASSED' ? 'PASSED' : source?.status === 'FAILED' ? 'FAILED' : undefined;
	return { line: `${JSON.stringify({ ...(status ? { status } : {}), bounded: true })}\n`, status };
}

function writeRecord(stream, value) {
	stream.write(serializeNativeToolCliRecord(value));
}

function safeStatus(value) {
	if (value === null || typeof value !== 'object' || Array.isArray(value)) return undefined;
	return Object.hasOwn(value, 'status') && ['PASSED', 'FAILED'].includes(value.status) ? value.status : undefined;
}

function redactCredentialShapedValues(value) {
	if (typeof value === 'string') return CREDENTIAL_SHAPED_VALUE.test(value) ? DIAGNOSTIC_REDACTED : value;
	if (value === null || typeof value !== 'object') return value;
	if (Array.isArray(value)) return value.map(redactCredentialShapedValues);
	const result = Object.create(null);
	for (const [key, child] of Object.entries(value)) result[key] = redactCredentialShapedValues(child);
	return result;
}

function containRedactedLocation(value) {
	const marker = '[location redacted]';
	const markerAt = value.indexOf(marker);
	return markerAt === -1 ? value : value.slice(0, markerAt + marker.length);
}
