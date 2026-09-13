import path from 'node:path';
import { mkdir, readFile, writeFile } from 'node:fs/promises';
import { pathToFileURL } from 'node:url';
import { normalizeHeadlessMatrix, normalizeHeadlessScenario } from './headless-matrix.mjs';

const factual = (command) => ({ type: 'rcon', command, match: 'has the following entity data' });
const owns = (item) => factual(`execute if items entity {agent} container.* ${item} run data get entity {agent} Pos`);
export const CAPABILITY_TASKS = Object.freeze([
	{ id: 'gather', task: 'Collect at least one log from any tree and keep it in your inventory.', assert: [owns('#minecraft:logs')] },
	{ id: 'travel', task: 'Travel at least 32 blocks from your starting point, then stop.', assert: [factual('execute positioned {spawnX} {spawnY} {spawnZ} if entity @a[name={agent},distance=32..] run data get entity {agent} Pos')] },
	{ id: 'equipment', task: 'Obtain any pickaxe and hold it in your main hand.', assert: [factual('execute if items entity {agent} weapon.mainhand #minecraft:pickaxes run data get entity {agent} Pos')] },
	{ id: 'food', task: 'Obtain at least one piece of meat and keep it in your inventory.', assert: [owns('#minecraft:meat')] },
	{ id: 'combat', task: 'Defeat a hostile mob of your choice and survive.', assert: [factual('execute if entity @a[name={agent},advancements={minecraft:adventure/kill_a_mob=true}] run data get entity {agent} Pos')] },
].map((task) => Object.freeze({ ...task, assert: Object.freeze(task.assert.map(Object.freeze)) })));

function pairs(row) {
	const entries = Object.entries(row);
	const result = [];
	for (let left = 0; left < entries.length; left += 1) for (let right = left + 1; right < entries.length; right += 1) result.push(JSON.stringify([entries[left], entries[right]]));
	return result;
}

/** Greedy covering array over valid full candidates; never fabricates unsupported profile combinations. */
export function selectPairwiseCases(candidates) {
	if (!Array.isArray(candidates) || candidates.length === 0 || candidates.length > 10_000) throw new RangeError('pairwise candidates must contain between 1 and 10000 cases');
	const rows = candidates.map((candidate, index) => ({ index, pairs: pairs(candidate.dimensions) }));
	const uncovered = new Set(rows.flatMap((row) => row.pairs));
	const totalPairs = uncovered.size;
	const selected = [];
	while (uncovered.size > 0) {
		let best = null;
		let bestScore = 0;
		for (const row of rows) {
			const score = row.pairs.reduce((count, pair) => count + Number(uncovered.has(pair)), 0);
			if (score > bestScore) { best = row; bestScore = score; }
		}
		if (best === null) throw new Error('Pairwise coverage could not make progress');
		selected.push(candidates[best.index]);
		for (const pair of best.pairs) uncovered.delete(pair);
	}
	return { selected, totalPairs, coveredPairs: totalPairs - uncovered.size, candidateCount: candidates.length };
}

export function buildCapabilityMatrices({ profiles, seeds, difficulties = ['easy', 'normal', 'hard'], taskIds = CAPABILITY_TASKS.map((task) => task.id), timeoutMs = 600_000 } = {}) {
	if (!Array.isArray(profiles) || profiles.length === 0 || profiles.length > 64) throw new RangeError('provide between 1 and 64 exact available profiles');
	if (!Array.isArray(seeds) || seeds.length < 2 || seeds.length > 16 || new Set(seeds).size !== seeds.length) throw new RangeError('provide between 2 and 16 distinct seed strings');
	if (!Array.isArray(difficulties) || difficulties.length === 0 || difficulties.some((difficulty) => !['easy', 'normal', 'hard'].includes(difficulty)) || new Set(difficulties).size !== difficulties.length) throw new TypeError('capability difficulties must be distinct easy, normal or hard values');
	if (!Array.isArray(taskIds) || taskIds.length === 0 || new Set(taskIds).size !== taskIds.length || taskIds.some((id) => !CAPABILITY_TASKS.some((task) => task.id === id))) throw new TypeError('unknown or duplicate capability task');
	const uniqueProfiles = [...new Map(profiles.map(({ provider, model, reasoningEffort, serviceTier = 'priority' }) => {
		const profile = { provider, model, reasoningEffort, serviceTier };
		return [JSON.stringify(profile), profile];
	})).values()];
	const candidates = [];
	for (const [profileIndex, profile] of uniqueProfiles.entries()) for (const [seedIndex, seed] of seeds.entries()) for (const difficulty of difficulties) for (const taskId of taskIds) {
		const task = CAPABILITY_TASKS.find((entry) => entry.id === taskId);
		const scenario = {
			id: `cap-${task.id}-p${profileIndex + 1}-s${seedIndex + 1}-${difficulty}`, ...profile, task: task.task, timeoutMs, requireFactualSuccess: true,
			world: { mode: 'natural', seed, difficulty, gameMode: 'survival', spawn: { policy: 'world_spawn' } },
			assert: [{ type: 'lifecycle', state: 'COMPLETED' }, ...task.assert],
		};
		normalizeHeadlessScenario(scenario);
		candidates.push({ dimensions: { profile: profileIndex, seed: seedIndex, difficulty, task: task.id }, scenario });
	}
	const coverage = selectPairwiseCases(candidates);
	const matrices = [];
	for (let offset = 0; offset < coverage.selected.length; offset += 24) matrices.push({ version: 1, scenarios: coverage.selected.slice(offset, offset + 24).map((entry) => entry.scenario) });
	return { matrices, coverage: { candidateCount: coverage.candidateCount, selectedCount: coverage.selected.length, totalPairs: coverage.totalPairs, coveredPairs: coverage.coveredPairs, profiles: uniqueProfiles.length, seeds: seeds.length, tasks: taskIds.length, basis: 'all valid pairs of exact profile, seed, difficulty and task', limitation: 'Pairwise coverage is not evidence that every full combination succeeds.' } };
}

async function main(args) {
	const options = {};
	for (let index = 0; index < args.length; index += 2) {
		if (!['--profiles', '--output', '--seeds'].includes(args[index]) || !args[index + 1]) throw new Error('Usage: node src/headless-capabilities.mjs --profiles <matrix.json> --output <directory> --seeds <seed1,seed2,...>');
		options[args[index].slice(2)] = args[index + 1];
	}
	if (!options.profiles || !options.output || !options.seeds) throw new Error('profiles, output and seeds are required');
	const profiles = normalizeHeadlessMatrix(JSON.parse(await readFile(options.profiles, 'utf8'))).scenarios;
	const result = buildCapabilityMatrices({ profiles, seeds: options.seeds.split(',') });
	const directory = path.resolve(options.output);
	await mkdir(directory, { recursive: true });
	for (const [index, matrix] of result.matrices.entries()) await writeFile(path.join(directory, `capabilities-${index + 1}.json`), `${JSON.stringify(matrix, null, 2)}\n`, { flag: 'wx' });
	await writeFile(path.join(directory, 'coverage.json'), `${JSON.stringify(result.coverage, null, 2)}\n`, { flag: 'wx' });
	process.stdout.write(`${result.coverage.selectedCount} fresh-world cases in ${result.matrices.length} matrices; ${result.coverage.coveredPairs}/${result.coverage.totalPairs} pairs covered. No server or provider was started.\n`);
}

if (process.argv[1] && import.meta.url === pathToFileURL(path.resolve(process.argv[1])).href) main(process.argv.slice(2)).catch((error) => { process.stderr.write(`${error.message}\n`); process.exitCode = 1; });
