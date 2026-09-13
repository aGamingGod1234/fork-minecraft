import { createHash } from 'node:crypto';
import { resolve, sep } from 'node:path';
import { pathToFileURL } from 'node:url';
import { performance } from 'node:perf_hooks';

const RUNS = 3;
const WARMUP_OBSERVATIONS = 5;
const OBSERVATIONS = 50;
const moduleRoot = process.argv[2] === undefined
	? new URL('../../', import.meta.url)
	: pathToFileURL(`${resolve(process.argv[2])}${sep}`);
const targetLabel = process.argv[3] ?? 'working-tree';
const [{ parseArenaScript }, { createInterpreterFacts }, { ArenaScriptEngine }] = await Promise.all([
	import(new URL('src/arena-script/parser.mjs', moduleRoot)),
	import(new URL('src/arena-script/facts.mjs', moduleRoot)),
	import(new URL('src/arena-script/program-engine.mjs', moduleRoot)),
]);

function candidate(index, kind = 'entity') {
	const common = { stableId: `candidate-${index}`, x: index, y: 64, z: -index, distance: index, tags: ['minecraft:mineable/pickaxe', 'minecraft:base_stone_overworld'] };
	return kind === 'block' ? { ...common, blockId: 'minecraft:stone' } : { ...common, type: 'minecraft:zombie' };
}

const observation = {
	player: { x: 0, y: 64, z: 0, health: 20, food: 20, yaw: 0, pitch: 0, onGround: true, name: 'benchmark-agent' },
	items: [],
	entities: Array.from({ length: 64 }, (_unused, index) => candidate(index)),
	blocks: Array.from({ length: 128 }, (_unused, index) => candidate(index + 64, 'block')),
	inventory: { items: [], tagCounts: {} },
};

function programWithWatchers(watcherCount) {
	const watchers = Array.from({ length: watcherCount }, (_unused, index) =>
		`program.watch(() => player.state().health < ${index + 1}, { mode: "boundary" }, async () => { await player.wait(1); });`).join('\n');
	return parseArenaScript(`program.onUnhandledAttention("continue_and_notify");\n${watchers}\nawait player.wait(60_000);`);
}

function measureFactReuse() {
	let facts = createInterpreterFacts(observation);
	for (let index = 0; index < WARMUP_OBSERVATIONS; index += 1) facts = createInterpreterFacts(observation, facts);
	let reused = 0;
	const startedAt = performance.now();
	for (let index = 0; index < OBSERVATIONS; index += 1) {
		const next = createInterpreterFacts(observation, facts);
		if (next === facts) reused += 1;
		facts = next;
	}
	return { durationMs: performance.now() - startedAt, reused };
}

function measureStablePlayerIngest(watcherCount) {
	const engine = new ArenaScriptEngine({ dispatch() {}, cancel() {}, requestModel() {} });
	engine.install({
		agentId: 'benchmark-agent', goalRevision: 1, modelIdentity: 'benchmark-model', programId: 'benchmark-program', version: 1,
		compiled: programWithWatchers(watcherCount), observation, eventSequence: 1,
	});
	let eventSequence = 2;
	for (let index = 0; index < WARMUP_OBSERVATIONS; index += 1) engine.ingestObservation({ observation, eventSequence: eventSequence++ });
	const startedAt = performance.now();
	for (let index = 0; index < OBSERVATIONS; index += 1) engine.ingestObservation({ observation, eventSequence: eventSequence++ });
	return performance.now() - startedAt;
}

function median(values) {
	const ordered = values.toSorted((left, right) => left - right);
	return ordered[Math.floor(ordered.length / 2)];
}

measureFactReuse();
const factSamples = Array.from({ length: RUNS }, measureFactReuse);
const factMedianMs = median(factSamples.map((sample) => sample.durationMs));
const watcherCells = [1, 4, 16].map((watcherCount) => {
	measureStablePlayerIngest(watcherCount);
	const samples = Array.from({ length: RUNS }, () => measureStablePlayerIngest(watcherCount));
	const medianMs = median(samples);
	return { watcherCount, medianMs: Number(medianMs.toFixed(3)), stableObservationsPerSecond: Math.round(OBSERVATIONS * 1_000 / medianMs) };
});

console.log(JSON.stringify({
	benchmark: 'arena-script-stable-observation-reuse',
	mode: 'indicative_non_gating_microbenchmark',
	scope: 'createInterpreterFacts identity reuse and ArenaScriptEngine ingest of unchanged player facts across watcher counts',
	target: targetLabel,
	node: process.version,
	runs: RUNS,
	warmupObservations: WARMUP_OBSERVATIONS,
	observationsPerRun: OBSERVATIONS,
	inputSha256: createHash('sha256').update(JSON.stringify(observation)).digest('hex'),
	factReuse: { medianMs: Number(factMedianMs.toFixed(3)), reusedReferencesPerRun: Math.min(...factSamples.map((sample) => sample.reused)), expectedReferencesPerRun: OBSERVATIONS },
	stableObservationIngest: watcherCells,
}, null, 2));
