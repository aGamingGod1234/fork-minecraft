const BLOCKED_RETRYABLE_CODES = new Set([
	'AUTHENTICATION_REQUIRED',
	'AUTHENTICATION_FAILED',
	'CONTROL_PROTOCOL_MISMATCH',
	'INVALID_CONFIG_OPTIONS',
	'LOGIN_REQUIRED',
	'MISSING_API_KEY',
	'MISSING_CREDENTIALS',
	'MODEL_UNAVAILABLE',
	'NATIVE_TOOLS_UNAVAILABLE',
	'NOT_AUTHENTICATED',
	'PROVIDER_MISMATCH',
	'PROVIDER_NOT_AUTHENTICATED',
	'REASONING_EFFORT_UNAVAILABLE',
	'SERVICE_TIER_UNAVAILABLE',
	'UNSUPPORTED_MODEL',
	'UNSUPPORTED_SERVICE_TIER',
	'UNSUPPORTED_THINKING',
]);

const INFRASTRUCTURE_CODES = new Set([
	'AGENT_NOT_STARTED',
	'AGENT_PROFILE_CONFLICT',
	'AGENT_DISPOSED',
	'INCOMPLETE_TURN',
	'INVALID_CATALOG',
	'INVALID_PROVIDER_OUTPUT',
	'INVALID_RESPONSE',
	'INVALID_SESSION',
	'MISSING_AGENT_MESSAGE',
	'MISSING_FINAL_MESSAGE',
	'MODEL_PROFILE_UNAVAILABLE',
	'OUTPUT_LIMIT_EXCEEDED',
	'PLANNER_OUTPUT_LIMIT',
	'PLANNING_LEASE_EXPIRED',
	'PLANNING_TIMEOUT',
	'PROCESS_EXITED',
	'PROCESS_TERMINATION_FAILED',
	'PROVIDER_CIRCUIT_OPEN',
	'PROVIDER_DOWN',
	'PROVIDER_ERROR',
	'PROVIDER_OVERLOADED',
	'PROVIDER_START_TIMEOUT',
	'PROVIDER_STOPPED',
	'PROVIDER_TIMEOUT',
	'PROVIDER_UNAVAILABLE',
	'REQUEST_TIMEOUT',
	'REQUEST_ID_EXHAUSTED',
	'RPC_ERROR',
	'SCHEDULER_CAPACITY',
	'SCHEDULER_CLOSED',
	'SESSION_INVALIDATED',
	'SESSION_GENERATION_MISMATCH',
	'SESSION_PROFILE_MISMATCH',
	'SPAWN_FAILED',
	'STALE_PROVIDER_START',
	'STALE_RECONCILIATION',
	'STALE_SESSION_GENERATION',
	'TIMEOUT',
	'TRANSPORT_NOT_RUNNING',
	'TRANSPORT_STOPPED',
	'TURN_FAILED',
	'TURN_IN_PROGRESS',
	'TURN_INTERRUPTED',
	'TURN_NOTIFICATION_OVERFLOW',
	'TURN_NOT_ACTIVE',
	'TURN_OUTPUT_LIMIT',
	'UNKNOWN_RESPONSE_ID',
]);

const QUIET_CODES = new Set(['MISSING_AGENT_MESSAGE', 'MISSING_FINAL_MESSAGE', 'REQUEST_TIMEOUT']);
const IMMEDIATE_RETRY_CODES = new Set([
	'MISSING_AGENT_MESSAGE',
	'MISSING_FINAL_MESSAGE',
	'PLANNING_TIMEOUT',
	'PROVIDER_UNAVAILABLE',
	'SPAWN_FAILED',
]);

export function classifyRecoveryFailure(errorOrCode) {
	const error = typeof errorOrCode === 'string' ? null : errorOrCode;
	const code = normalizeCode(typeof errorOrCode === 'string' ? errorOrCode : error?.code);
	const kind = BLOCKED_RETRYABLE_CODES.has(code)
		? 'blocked_retryable'
		: (INFRASTRUCTURE_CODES.has(code) ? 'infrastructure' : 'not_recoverable');
	const nextProbeAtEpochMs = finiteDeadline(error?.nextProbeAtEpochMs);
	return Object.freeze({
		code,
		kind,
		retryable: kind !== 'not_recoverable',
		blocked: kind === 'blocked_retryable',
		quiet: QUIET_CODES.has(code),
		immediateRetry: IMMEDIATE_RETRY_CODES.has(code),
		...(nextProbeAtEpochMs === null ? {} : { nextProbeAtEpochMs }),
	});
}

export function isRecoveryFailure(errorOrCode) {
	return classifyRecoveryFailure(errorOrCode).retryable;
}

function normalizeCode(value) {
	return typeof value === 'string' && /^[A-Z0-9_]{1,128}$/.test(value) ? value : 'UNKNOWN_ERROR';
}

function finiteDeadline(value) {
	return Number.isFinite(value) && value >= 0 ? value : null;
}
