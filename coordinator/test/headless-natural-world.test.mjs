import assert from 'node:assert/strict';
import { spawnSync } from 'node:child_process';
import { mkdtemp, readFile, rm } from 'node:fs/promises';
import os from 'node:os';
import path from 'node:path';
import test from 'node:test';
import { buildCapabilityMatrices, CAPABILITY_TASKS, selectPairwiseCases } from '../src/headless-capabilities.mjs';
import { normalizeHeadlessScenario, runHeadlessMatrix, runHeadlessScenario } from '../src/headless-matrix.mjs';
import { claimNaturalWorld, normalizeHeadlessWorld, parsePlayerPosition, parseServerSeed, summarizeProviderAttestation, validateNaturalWorldManifest } from '../src/headless-world.mjs';

const profile = { provider: 'codex', model: 'fixture', reasoningEffort: 'low', serviceTier: 'priority' };
const makeScenario = (overrides = {}) => normalizeHeadlessScenario({
	id: 'natural-case', ...profile, task: 'Collect a log', timeoutMs: 10_000, requireFactualSuccess: true,
	world: { mode: 'natural', seed: '9223372036854775807' },
	assert: [{ type: 'lifecycle', state: 'COMPLETED' }, ...CAPABILITY_TASKS[0].assert], ...overrides,
});
const manifest = (scenario) => ({
	version: 1, scenarioId: scenario.id, fresh: true, worldId: 'headless-isolated-fixture', world: scenario.world,
	savedSpawn: { source: 'level.dat', dimension: 'minecraft:overworld', x: 10, y: 65, z: -4 },
	spawnLoading: { operation: 'temporary_spawn_chunk_loading', x: scenario.world.spawn.x ?? 10, z: scenario.world.spawn.z ?? -4, ready: true, elapsedMs: 50, terrainModified: false, inventoryModified: false },
});

test('natural world preserves exact 64-bit seeds and rejects invalid settings or synthetic setup', () => {
	const scenario = makeScenario();
	assert.equal(scenario.world.seed, '9223372036854775807');
	assert.equal(scenario.world.gameMode, 'survival');
	assert.deepEqual(scenario.world.spawn, { policy: 'world_spawn' });
	assert.equal(validateNaturalWorldManifest(manifest(scenario), scenario.world, scenario.id).fresh, true);
	for (const seed of [1, '9223372036854775808', '-9223372036854775809', '1e9', '01', '12\nstop']) assert.throws(() => makeScenario({ world: { mode: 'natural', seed } }), /seed/);
	for (const overrides of [{ repetitions: 2 }, { rosterSize: 8 }, { setupBlocks: [{ x: 0, y: 64, z: 0, blockId: 'minecraft:stone' }] }, { requireFactualSuccess: false }]) assert.throws(() => makeScenario(overrides), /natural evaluations/);
	assert.throws(() => makeScenario({ world: { mode: 'natural', seed: '0', rules: { 'bad rule': true } } }), /rules/);
	assert.throws(() => normalizeHeadlessWorld({ mode: 'arena', seed: '0' }), /arena/);
	assert.throws(() => validateNaturalWorldManifest({ ...manifest(scenario), fresh: false }, scenario.world, scenario.id), /fresh isolated/);
	assert.throws(() => validateNaturalWorldManifest({ ...manifest(scenario), world: { mode: 'natural', seed: '1' } }, scenario.world, scenario.id), /do not match/);
	assert.equal(parseServerSeed('Seed: [9223372036854775807]'), '9223372036854775807');
	assert.deepEqual(parsePlayerPosition('player has the following entity data: [-2.5d, 71.0d, 4.5d]'), { x: -2.5, y: 71, z: 4.5 });
});

async function fixture({ scenario = makeScenario(), seed = scenario.world.seed, inventory = '[]', outcome = true, snapshot = true, effectiveProfile = profile, executionSettings = null, worldManifest = manifest(scenario), afterStart = null, loaded = true, pendingJoin = false } = {}) {
	const commands = [];
	let name = '';
	let started = false;
	const report = await runHeadlessScenario({
		scenario, worldManifest, runDirectory: path.resolve('test-natural-world-fixture'), providerTurnsPath: 'provider-turns.private.jsonl', now: () => 100,
		readFile: async (file) => {
			if (String(file).endsWith('protocol.jsonl') && name && snapshot) return `${JSON.stringify({ direction: 'server_to_coordinator', envelope: { type: 'agent_snapshot', agentId: 'fixture-agent', payload: { agentId: 'fixture-agent', name, ...effectiveProfile } } })}\n`;
			if (file === 'provider-turns.private.jsonl' && name && executionSettings) return `${JSON.stringify({ ...profile, agentId: 'fixture-agent', executionSettings })}\n`;
			return '';
		},
		fileSize: async () => 0, writeFile: async () => {}, poll: async () => {},
		rcon: {
			close: async () => {},
			command: async (command) => {
				commands.push(command);
				if (command === 'seed') return { text: `Seed: [${seed}]` };
				if (command === 'difficulty') return { text: `The difficulty is ${scenario.world.difficulty}` };
				if (command.includes(' if loaded ')) return { text: loaded ? 'The time is 500' : 'Test failed' };
				if (command.startsWith('gamerule ')) return { text: 'The gamerule is true' };
				if (command.includes('summon-configured')) { name = command.split(' ').at(-1); return { text: pendingJoin ? `Creating ${name}. It will be ready when its player joins.` : `Created ${name}. It is ready for a task.` }; }
				if (command.startsWith('codex start ')) { started = true; afterStart?.(); return { text: 'started' }; }
				if (command.startsWith('codex status ')) return { text: pendingJoin && !started ? `${name} | READY.` : 'state=COMPLETED' };
				if (command === `data get entity ${name} Inventory`) return { text: `${name} has the following entity data: ${inventory}` };
				if (command === `data get entity ${name} Pos` || command.startsWith('execute if ') || command.startsWith('execute positioned ')) return { text: command.startsWith('data get ') || outcome ? `${name} has the following entity data: [10.5d, 65.0d, -3.5d]` : 'Test failed' };
				return { text: 'ok' };
			},
		},
	});
	return { report, commands };
}

test('natural runner preserves terrain and inventory, scopes exact settings, and never sends evaluator facts in the task', async () => {
	const { report, commands } = await fixture();
	assert.equal(report.status, 'PASSED');
	assert.equal(report.factualSuccess, true);
	assert.equal(report.world.seed, '9223372036854775807');
	assert.equal(report.world.fresh, true);
	assert.deepEqual(report.world.spawnPosition, { x: 10.5, y: 65, z: -3.5 });
	assert.deepEqual(report.settings, { requested: profile, configured: profile, configuredVerified: true, effective: null, evidence: null });
	assert.deepEqual(report.budget, { wallClockMs: 10_000 });
	assert.equal(commands.some((command) => /\b(?:fill|setblock|give|clear|teleport|tp)\b/.test(command)), false);
	const task = commands.find((command) => command.startsWith('codex start '));
	assert.match(task, / Collect a log$/);
	assert.doesNotMatch(task, /922337|manifest|seed|assert|profile/);
	assert.ok(commands.some((command) => command.includes('positioned 10.5 65 -3.5 run codex summon-configured')));
	const release = commands.indexOf('execute in minecraft:overworld run forceload remove 10 -4');
	assert.ok(release > commands.findIndex((command) => command.endsWith(' Inventory')));
	assert.ok(release < commands.findIndex((command) => command.startsWith('codex start ')));
	assert.equal(report.world.setupInterventions[0].released, true);
});

test('natural spawn readiness fails closed and releases its exact ticket without changing terrain', async () => {
	const { report, commands } = await fixture({ loaded: false });
	assert.equal(report.status, 'FAILED');
	assert.equal(commands.some((command) => command.includes('summon-configured')), false);
	assert.equal(commands.at(-1), 'execute in minecraft:overworld run forceload remove 10 -4');
	assert.equal(report.world.setupInterventions[0].released, true);
	const missing = await fixture({ worldManifest: { ...manifest(makeScenario()), savedSpawn: undefined } });
	assert.equal(missing.report.status, 'FAILED');
	assert.equal(missing.commands.some((command) => command.includes('summon-configured')), false);
	const surface = await fixture({ scenario: makeScenario({ world: { mode: 'natural', seed: '1', spawn: { policy: 'surface', x: -20, z: 48 } } }) });
	assert.equal(surface.report.status, 'PASSED');
	assert.ok(surface.commands.some((command) => command.includes('positioned -19.5 0 48.5 positioned over world_surface run codex summon-configured')));
	assert.ok(surface.commands.includes('execute in minecraft:overworld run forceload remove -20 48'));
	const joining = await fixture({ pendingJoin: true });
	assert.equal(joining.report.status, 'PASSED');
	assert.ok(joining.commands.findIndex((command) => command.startsWith('codex status ')) < joining.commands.indexOf('execute in minecraft:overworld run forceload remove 10 -4'));
});

test('natural runner fails before agent start for missing identity, wrong seed or nonempty inventory', async () => {
	for (const options of [{ worldManifest: null }, { seed: '123' }, { inventory: '[{id:"minecraft:diamond",count:1}]' }]) {
		const { report, commands } = await fixture(options);
		assert.equal(report.status, 'FAILED');
		assert.equal(report.failureCategory, 'infrastructure');
		assert.equal(commands.some((command) => command.startsWith('codex start ')), false);
	}
});

test('natural success requires factual outcomes and authoritative exact-profile evidence', async () => {
	assert.equal((await fixture({ outcome: false })).report.classification, 'ASSERTION_MISMATCH');
	assert.equal((await fixture({ snapshot: false })).report.status, 'FAILED');
	assert.equal((await fixture({ effectiveProfile: { ...profile, reasoningEffort: 'high' } })).report.status, 'FAILED');
});

test('configured model settings never substitute for missing provider attestation', () => {
	assert.deepEqual(summarizeProviderAttestation([], profile), { effective: null, evidence: null, mismatches: [] });
	const unknown = { effective: { provider: 'codex', model: null, reasoningEffort: null, serviceTier: null }, evidence: { model: 'submitted', reasoningEffort: 'process_environment', serviceTier: 'submitted' } };
	assert.deepEqual(summarizeProviderAttestation([{ executionSettings: unknown }], profile).mismatches, []);
	assert.deepEqual(summarizeProviderAttestation([{ executionSettings: { effective: { ...profile, reasoningEffort: 'high' }, evidence: { reasoningEffort: 'provider_reported' } } }], profile).mismatches, ['reasoningEffort']);
});

test('natural runner retains provider attestation and rejects a positively reported model substitution', async () => {
	const unknown = await fixture({ executionSettings: { effective: { provider: 'codex', model: null, reasoningEffort: null, serviceTier: null }, evidence: { model: 'submitted', reasoningEffort: 'process_environment', serviceTier: 'unreported' } } });
	assert.equal(unknown.report.status, 'PASSED');
	assert.equal(unknown.report.settings.effective.reasoningEffort, null);
	assert.equal(unknown.report.settings.evidence.reasoningEffort, 'process_environment');
	const substituted = await fixture({ executionSettings: { effective: { ...profile, model: 'substituted-model' }, evidence: { model: 'provider_reported', reasoningEffort: 'submitted', serviceTier: 'submitted' } } });
	assert.equal(substituted.report.classification, 'PROFILE_MISMATCH');
	assert.equal(substituted.report.failureCategory, 'profile');
	assert.equal(substituted.report.settings.effective.model, 'substituted-model');
});

test('spawn-relative and inventory/advancement assertions remain bounded read-only server queries', async () => {
	for (const capability of CAPABILITY_TASKS) {
		const scenario = makeScenario({ assert: [{ type: 'lifecycle', state: 'COMPLETED' }, ...capability.assert] });
		const { report, commands } = await fixture({ scenario });
		assert.equal(report.status, 'PASSED', `${capability.id}: ${report.diagnostics}`);
		if (capability.id === 'travel') assert.ok(commands.some((command) => command.startsWith('execute positioned 10.5 65 -3.5 if entity ')));
	}
	for (const command of [
		'execute if items entity {agent} container.* #minecraft:logs run give {agent} minecraft:diamond',
		'execute if entity @a[name={agent},advancements={minecraft:adventure/kill_a_mob=true}] run kill {agent}',
	]) {
		const { report, commands } = await fixture({ scenario: makeScenario({ assert: [{ type: 'rcon', command, match: 'ok' }] }) });
		assert.equal(report.status, 'FAILED');
		assert.equal(commands.some((entry) => /run (give|kill) /.test(entry)), false);
	}
});

test('natural matrix cannot connect to an unowned or shared server without its fresh-world manifest', async () => {
	let connected = false;
	await assert.rejects(() => runHeadlessMatrix({
		configPath: 'matrix.json', runDirectory: path.resolve('fixture'), rconPasswordFile: 'password',
		readFile: async () => JSON.stringify({ version: 1, scenarios: [{ ...profile, id: 'case', task: 'task', timeoutMs: 1000, requireFactualSuccess: true, world: { mode: 'natural', seed: '1' }, assert: CAPABILITY_TASKS[0].assert }] }),
		rconFactory: () => { connected = true; },
	}), /one scenario per isolated server/);
	assert.equal(connected, false);
});

test('concurrent CLI attempts cannot reuse a natural world, including after a failed attempt', async () => {
	const directory = await mkdtemp(path.join(os.tmpdir(), 'arena-world-claim-'));
	const file = path.join(directory, 'world-manifest.json');
	try {
		const claims = await Promise.allSettled([claimNaturalWorld(file, 'headless-test'), claimNaturalWorld(file, 'headless-test')]);
		assert.equal(claims.filter((result) => result.status === 'fulfilled').length, 1);
		assert.match(claims.find((result) => result.status === 'rejected').reason.message, /NATURAL_WORLD_REUSED/);
		assert.equal(JSON.parse(await readFile(`${file}.claimed`, 'utf8')).worldId, 'headless-test');
		await assert.rejects(() => claimNaturalWorld(file, 'headless-test'), /fresh isolated server/);
	} finally { await rm(directory, { recursive: true, force: true }); }
});

test('capability scheduling covers every valid pair without changing or fabricating model settings', () => {
	const profiles = [profile, { ...profile, model: 'other', reasoningEffort: 'high', serviceTier: 'fast' }];
	const { matrices, coverage } = buildCapabilityMatrices({ profiles, seeds: ['1', '-2'], difficulties: ['easy', 'hard'] });
	const scenarios = matrices.flatMap((matrix) => matrix.scenarios);
	assert.equal(coverage.coveredPairs, coverage.totalPairs);
	assert.ok(coverage.selectedCount < coverage.candidateCount);
	assert.ok(matrices.every((matrix) => matrix.scenarios.length <= 24));
	for (const scenario of scenarios) {
		assert.ok(profiles.some((candidate) => Object.keys(profile).every((key) => candidate[key] === scenario[key])));
		assert.equal(normalizeHeadlessScenario(scenario).repetitions, 1);
		assert.equal(scenario.world.mode, 'natural');
		assert.equal(scenario.task.includes(scenario.world.seed), false);
	}
	const dimensions = scenarios.map((scenario) => ({ profile: `${scenario.model}/${scenario.reasoningEffort}/${scenario.serviceTier}`, seed: scenario.world.seed, difficulty: scenario.world.difficulty, task: scenario.task }));
	for (const left of ['profile', 'seed', 'difficulty', 'task']) for (const right of ['profile', 'seed', 'difficulty', 'task']) {
		if (left >= right) continue;
		for (const first of new Set(dimensions.map((row) => row[left]))) for (const second of new Set(dimensions.map((row) => row[right]))) assert.ok(dimensions.some((row) => row[left] === first && row[right] === second), `${left}/${right} missing pair`);
	}
	assert.deepEqual(buildCapabilityMatrices({ profiles, seeds: ['1', '-2'], difficulties: ['easy', 'hard'] }), { matrices, coverage });
	assert.throws(() => selectPairwiseCases([]), /candidates/);
});

test('PowerShell launcher writes isolated natural worlds and runs the provider-free probe without the coordinator', { skip: process.platform !== 'win32' }, () => {
	const result = spawnSync('powershell.exe', ['-NoProfile', '-ExecutionPolicy', 'Bypass', '-File', path.resolve('../scripts/test-headless-natural-world.ps1')], { encoding: 'utf8', timeout: 30_000 });
	assert.equal(result.status, 0, result.stderr);
	assert.equal((result.stdout.match(/PASS /g) ?? []).length, 3);
});
