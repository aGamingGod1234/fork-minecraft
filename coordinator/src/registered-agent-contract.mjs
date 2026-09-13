export const REGISTERED_AGENT_SCHEMA_VERSION = 1;

export const REGISTERED_AGENT_STATES = Object.freeze([
	'IDLE', 'STARTING', 'PLANNING', 'ACTING', 'PAUSED', 'COMPLETED', 'ERROR', 'DEAD', 'DISCONNECTED',
]);

const STATE_SET = new Set(REGISTERED_AGENT_STATES);
const GOAL_REQUIRED_STATES = new Set(['STARTING', 'PLANNING', 'ACTING', 'PAUSED', 'DISCONNECTED']);

export function validateRegisteredAgentContract(value, fail = (message) => new TypeError(message)) {
	if (value.schemaVersion !== REGISTERED_AGENT_SCHEMA_VERSION) {
		throw fail(`schemaVersion must be ${REGISTERED_AGENT_SCHEMA_VERSION}`);
	}
	if (!STATE_SET.has(value.state)) throw fail(`state '${String(value.state)}' is not supported`);
	if (value.state === 'IDLE' && value.currentGoal !== null) throw fail('IDLE agents cannot have a current goal');
	if (GOAL_REQUIRED_STATES.has(value.state) && value.currentGoal === null) {
		throw fail(`${value.state} agents require a current goal`);
	}
	if (!Number.isSafeInteger(value.createdAtEpochMs) || value.createdAtEpochMs <= 0
			|| !Number.isSafeInteger(value.updatedAtEpochMs) || value.updatedAtEpochMs < value.createdAtEpochMs) {
		throw fail('agent timestamps are invalid');
	}
	return value;
}
