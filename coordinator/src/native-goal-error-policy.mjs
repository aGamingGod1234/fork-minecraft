const TERMINAL_CODES = new Set([
	'AGENT_PROFILE_CONFLICT',
	'CONTROL_PROTOCOL_MISMATCH',
	'INVALID_CATALOG',
	'MODEL_PROFILE_UNAVAILABLE',
	'MODEL_UNAVAILABLE',
	'REASONING_EFFORT_UNAVAILABLE',
	'SERVICE_TIER_UNAVAILABLE',
	'NATIVE_TOOLS_UNAVAILABLE',
]);
const STALE_CODES = new Set([
	'AGENT_DISPOSED',
	'PLAN_CANCELLED',
	'STALE_GOAL_REVISION',
	'STALE_NATIVE_TOOL',
	'STALE_PLAN',
	'TURN_INTERRUPTED',
]);

export function classifyNativeGoalError(error) {
	const code = String(error?.code ?? 'NATIVE_TURN_FAILED');
	if (STALE_CODES.has(code)) return 'stale';
	if (error instanceof TypeError || TERMINAL_CODES.has(code)) return 'terminal';
	return 'recoverable';
}
