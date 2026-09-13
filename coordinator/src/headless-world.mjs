const WORLD_KEYS = new Set(['mode', 'seed', 'generator', 'difficulty', 'gameMode', 'rules', 'spawn']);
const GENERATORS = new Set(['minecraft:normal', 'minecraft:large_biomes', 'minecraft:amplified']);
const DIFFICULTIES = new Set(['peaceful', 'easy', 'normal', 'hard']);
const GAME_MODES = new Set(['survival', 'creative', 'adventure']);

function objectKeys(value, keys, label) {
	if (!value || typeof value !== 'object' || Array.isArray(value)) throw new TypeError(`${label} must be an object`);
	for (const key of Object.keys(value)) if (!keys.has(key)) throw new TypeError(`${label} has unknown key '${key}'`);
}

export function normalizeHeadlessWorld(value) {
	if (value === undefined) return Object.freeze({ mode: 'arena' });
	objectKeys(value, WORLD_KEYS, 'world');
	if (value.mode === 'arena') {
		if (Object.keys(value).length !== 1) throw new TypeError('arena world does not accept natural-world settings');
		return Object.freeze({ mode: 'arena' });
	}
	if (value.mode !== 'natural') throw new TypeError('world.mode must be arena or natural');
	if (typeof value.seed !== 'string' || !/^(?:0|-?[1-9][0-9]{0,18})$/.test(value.seed)
		|| BigInt(value.seed) < -(2n ** 63n) || BigInt(value.seed) > 2n ** 63n - 1n) {
		throw new TypeError('natural world.seed must be an exact signed 64-bit decimal string');
	}
	const generator = value.generator ?? 'minecraft:normal';
	const difficulty = value.difficulty ?? 'normal';
	const gameMode = value.gameMode ?? 'survival';
	if (!GENERATORS.has(generator)) throw new TypeError('unsupported natural world.generator');
	if (!DIFFICULTIES.has(difficulty)) throw new TypeError('unsupported natural world.difficulty');
	if (!GAME_MODES.has(gameMode)) throw new TypeError('unsupported natural world.gameMode');
	const rules = value.rules ?? {};
	if (!rules || typeof rules !== 'object' || Array.isArray(rules) || Object.keys(rules).length > 16) throw new TypeError('world.rules must contain at most 16 boolean gamerules');
	for (const [key, setting] of Object.entries(rules)) {
		if (!/^(?:minecraft:)?[a-zA-Z][a-zA-Z0-9_]{0,63}$/.test(key) || typeof setting !== 'boolean') throw new TypeError('world.rules requires gamerule identifiers and boolean values');
	}
	const spawn = value.spawn ?? { policy: 'world_spawn' };
	objectKeys(spawn, new Set(['policy', 'x', 'z']), 'world.spawn');
	if (!['world_spawn', 'surface'].includes(spawn.policy)) throw new TypeError('world.spawn.policy must be world_spawn or surface');
	if (spawn.policy === 'world_spawn' && Object.keys(spawn).length !== 1) throw new TypeError('world_spawn does not accept coordinates');
	if (spawn.policy === 'surface') {
		for (const axis of ['x', 'z']) if (!Number.isSafeInteger(spawn[axis]) || Math.abs(spawn[axis]) > 29_999_984) throw new RangeError(`world.spawn.${axis} must be a bounded block coordinate`);
	}
	return Object.freeze({ mode: 'natural', seed: value.seed, generator, difficulty, gameMode, rules: Object.freeze({ ...rules }), spawn: Object.freeze({ ...spawn }) });
}

/** The launcher owns these facts. They are never added to the agent task or observation. */
export function validateNaturalWorldManifest(manifest, world, scenarioId) {
	if (!manifest || manifest.version !== 1 || manifest.fresh !== true || manifest.scenarioId !== scenarioId
		|| typeof manifest.worldId !== 'string' || !/^headless-[a-zA-Z0-9_-]{1,200}$/.test(manifest.worldId)) {
		throw new Error('NATURAL_WORLD_IDENTITY: fresh isolated launcher world manifest is required');
	}
	const actual = normalizeHeadlessWorld(manifest.world);
	if (JSON.stringify(actual) !== JSON.stringify(world)) throw new Error('NATURAL_WORLD_IDENTITY: launcher world settings do not match the scenario');
	return Object.freeze({ worldId: manifest.worldId, fresh: true, ...world });
}

export async function claimNaturalWorld(manifestPath, worldId) {
	let file;
	try { file = await open(`${manifestPath}.claimed`, 'wx', 0o600); }
	catch (error) {
		if (error.code === 'EEXIST') throw new Error('NATURAL_WORLD_REUSED: create a fresh isolated server for each evaluation attempt');
		throw error;
	}
	try { await file.writeFile(`${JSON.stringify({ worldId, claimedAt: new Date().toISOString() })}\n`); }
	finally { await file.close(); }
}

export function parseServerSeed(value) {
	const match = String(value).match(/(?:Seed:\s*\[?|seed\s*(?:is|:)\s*\[?)(-?\d+)/i);
	return match ? match[1] : null;
}

export function parsePlayerPosition(value) {
	const match = String(value).match(/\[\s*(-?\d+(?:\.\d+)?)[dDfF]?\s*,\s*(-?\d+(?:\.\d+)?)[dDfF]?\s*,\s*(-?\d+(?:\.\d+)?)[dDfF]?\s*\]/);
	if (!match) throw new Error('NATURAL_SPAWN_EVIDENCE: server did not return an authoritative player position');
	return Object.freeze({ x: Number(match[1]), y: Number(match[2]), z: Number(match[3]) });
}

export function classifyHeadlessFailure(classification) {
	if (classification === 'PASSED') return 'passed';
	if (['SKIPPED_PROFILE', 'REQUIRED_PROFILE_UNAVAILABLE', 'PROFILE_MISMATCH'].includes(classification)) return 'profile';
	if (['TIMEOUT', 'BUDGET_EXHAUSTED'].includes(classification)) return 'budget';
	if (classification === 'DEAD') return 'death';
	if (['ASSERTION_MISMATCH', 'FAILED_USER_OBJECTIVE'].includes(classification)) return 'objective';
	return 'infrastructure';
}

export function summarizeProviderAttestation(rows, requested) {
	const records = rows.map((row) => row?.executionSettings).filter((record) => record?.effective && record?.evidence);
	const latest = records.at(-1);
	const fields = ['model', 'reasoningEffort', 'serviceTier'];
	const mismatches = [...new Set(records.flatMap((record) => fields.filter((field) => record.evidence[field] === 'provider_reported'
		&& typeof record.effective[field] === 'string' && record.effective[field] !== requested[field])))];
	return { effective: latest ? { ...latest.effective } : null, evidence: latest ? { ...latest.evidence } : null, mismatches };
}
import { open } from 'node:fs/promises';
