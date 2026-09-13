import assert from 'node:assert/strict';
import test from 'node:test';
import { gzipSync } from 'node:zlib';
import { parseSavedWorldSpawn, prepareSpawnChunk } from '../src/headless-world-spawn.mjs';

const int = (value) => { const bytes = Buffer.alloc(4); bytes.writeInt32BE(value); return bytes; };
const text = (value) => { const bytes = Buffer.from(value); const size = Buffer.alloc(2); size.writeUInt16BE(bytes.length); return Buffer.concat([size, bytes]); };
const tag = (type, name, payload) => Buffer.concat([Buffer.from([type]), text(name), payload]);
const compound = (name, entries) => tag(10, name, Buffer.concat([...entries, Buffer.from([0])]));
const saved = (entries) => gzipSync(compound('', [compound('Data', entries)]));
const modern = (dimension = 'minecraft:overworld') => compound('spawn', [tag(11, 'pos', Buffer.concat([int(3), int(-48), int(77), int(112)])), tag(8, 'dimension', text(dimension))]);

test('reads the installed modern saved spawn format and legacy spawn fields without leaking other metadata', () => {
	const expected = { source: 'level.dat', dimension: 'minecraft:overworld', x: -48, y: 77, z: 112 };
	assert.deepEqual(parseSavedWorldSpawn(saved([modern(), tag(8, 'unrelated', text('private fixture metadata'))])), expected);
	assert.deepEqual(parseSavedWorldSpawn(saved([tag(3, 'SpawnX', int(-48)), tag(3, 'SpawnY', int(77)), tag(3, 'SpawnZ', int(112))])), expected);
});

test('saved spawn reader fails closed on missing coordinates, bad dimensions and malformed or oversized NBT', () => {
	assert.throws(() => parseSavedWorldSpawn(saved([])), /spawn is missing/);
	assert.throws(() => parseSavedWorldSpawn(saved([modern('minecraft:the_nether')])), /spawn is missing/);
	assert.throws(() => parseSavedWorldSpawn(gzipSync(Buffer.from([10, 0]))), /length/);
	assert.throws(() => parseSavedWorldSpawn(Buffer.alloc(8 * 1024 * 1024 + 1)), /metadata limit/);
	assert.throws(() => parseSavedWorldSpawn(gzipSync(Buffer.alloc(8 * 1024 * 1024 + 1))), /larger than/);
	assert.throws(() => parseSavedWorldSpawn(saved([tag(7, 'too-many', int(1_000_001))])), /array/);
	let nested = modern();
	for (let index = 0; index < 65; index++) nested = compound('nested', [nested]);
	assert.throws(() => parseSavedWorldSpawn(saved([nested])), /complex/);
});

test('spawn loader waits for a real loaded response and retains one ticket for the joining player', async () => {
	const commands = [];
	let queries = 0;
	let elapsed = 0;
	const result = await prepareSpawnChunk({ spawn: { x: -48, z: 112 }, now: () => elapsed, poll: async () => { elapsed += 50; }, rcon: { command: async (command) => {
		commands.push(command);
		return { text: command.includes('if loaded') && ++queries > 1 ? 'The time is 127' : 'Test failed' };
	} } });
	assert.equal(result.ready, true);
	assert.equal(result.elapsedMs, 50);
	assert.equal(result.terrainModified, false);
	assert.equal(result.inventoryModified, false);
	assert.deepEqual(commands, ['execute in minecraft:overworld run forceload add -48 112', 'execute in minecraft:overworld if loaded -48 0 112 run time query gametime', 'execute in minecraft:overworld if loaded -48 0 112 run time query gametime']);
});

test('spawn loader times out and releases only its ticket for unloaded and hung command failures', async () => {
	for (const hung of [false, true]) {
		const commands = [];
		await assert.rejects(() => prepareSpawnChunk({ spawn: { x: -48, z: 112 }, timeoutMs: 20, now: () => 0, poll: async () => {}, rcon: { command: async (command) => {
			commands.push(command);
			if (hung && command.includes('if loaded')) return new Promise(() => {});
			return { text: 'Test failed' };
		} } }), /NATURAL_SPAWN_LOADING_TIMEOUT/);
		assert.equal(commands.at(-1), 'execute in minecraft:overworld run forceload remove -48 112');
		assert.equal(commands.some((command) => /\b(fill|setblock|give|clear|teleport)\b/.test(command)), false);
	}
});
