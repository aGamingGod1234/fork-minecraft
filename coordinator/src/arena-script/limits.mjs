export const DEFAULT_ARENA_SCRIPT_LIMITS = Object.freeze({
	sourceBytes: 65_536,
	astNodes: 4_096,
	watchers: 16,
	operationsPerResume: 1_024,
	loopIterationsPerYield: 128,
	commandsPerProgram: 256,
});

const LIMIT_NAMES = Object.keys(DEFAULT_ARENA_SCRIPT_LIMITS);

export function normalizeArenaScriptLimits(limits = DEFAULT_ARENA_SCRIPT_LIMITS) {
	if (limits === null || typeof limits !== 'object' || Array.isArray(limits)) {
		throw new TypeError('ArenaScript limits must be an object');
	}
	const normalized = {};
	for (const name of LIMIT_NAMES) {
		const value = limits[name] ?? DEFAULT_ARENA_SCRIPT_LIMITS[name];
		if (!Number.isSafeInteger(value) || value <= 0) {
			throw new RangeError(`ArenaScript limit ${name} must be a positive safe integer`);
		}
		normalized[name] = value;
	}
	return Object.freeze(normalized);
}
