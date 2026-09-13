import { readFile } from 'node:fs/promises';
import { gunzipSync } from 'node:zlib';
import { pathToFileURL } from 'node:url';
import { HeadlessRconClient } from './headless-rcon.mjs';

const MAX_BYTES = 8 * 1024 * 1024;

/** Reads evaluator spawn metadata from the isolated world's saved NBT, without changing it. */
export function parseSavedWorldSpawn(compressed) {
	if (!Buffer.isBuffer(compressed) || compressed.length > MAX_BYTES) throw new Error('NATURAL_SPAWN_EVIDENCE: level.dat exceeds the metadata limit');
	const bytes = gunzipSync(compressed, { maxOutputLength: MAX_BYTES });
	let offset = 0;
	let nodes = 0;
	const take = (length) => {
		if (!Number.isSafeInteger(length) || length < 0 || offset + length > bytes.length) throw new Error('NATURAL_SPAWN_EVIDENCE: invalid level.dat length');
		const start = offset; offset += length; return start;
	};
	const byte = () => bytes.readUInt8(take(1));
	const int = () => bytes.readInt32BE(take(4));
	const text = () => { const length = bytes.readUInt16BE(take(2)); return bytes.toString('utf8', take(length), offset); };
	const size = () => { const length = int(); if (length < 0 || length > 1_000_000) throw new Error('NATURAL_SPAWN_EVIDENCE: invalid level.dat array'); return length; };
	const value = (type, depth = 0) => {
		if (++nodes > 100_000 || depth > 64) throw new Error('NATURAL_SPAWN_EVIDENCE: level.dat is too complex');
		switch (type) {
			case 1: return bytes.readInt8(take(1));
			case 2: return bytes.readInt16BE(take(2));
			case 3: return int();
			case 4: return bytes.readBigInt64BE(take(8)).toString();
			case 5: return bytes.readFloatBE(take(4));
			case 6: return bytes.readDoubleBE(take(8));
			case 7: { const length = size(); take(length); return null; }
			case 8: return text();
			case 9: { const child = byte(); const length = size(); return Array.from({ length }, () => value(child, depth + 1)); }
			case 10: {
				const result = Object.create(null);
				for (let child = byte(); child !== 0; child = byte()) {
					const name = text();
					if (Object.hasOwn(result, name)) throw new Error('NATURAL_SPAWN_EVIDENCE: duplicate level.dat field');
					result[name] = value(child, depth + 1);
				}
				return result;
			}
			case 11: { const length = size(); return Array.from({ length }, () => int()); }
			case 12: { const length = size(); take(length * 8); return null; }
			default: throw new Error('NATURAL_SPAWN_EVIDENCE: invalid level.dat tag');
		}
	};
	if (byte() !== 10) throw new Error('NATURAL_SPAWN_EVIDENCE: level.dat root must be a compound');
	text();
	const root = value(10);
	if (offset !== bytes.length) throw new Error('NATURAL_SPAWN_EVIDENCE: trailing level.dat bytes');
	const data = root.Data;
	const saved = data?.spawn;
	const position = saved === undefined ? [data?.SpawnX, data?.SpawnY, data?.SpawnZ] : saved.pos;
	const dimension = saved?.dimension ?? 'minecraft:overworld';
	if (dimension !== 'minecraft:overworld' || !Array.isArray(position) || position.length !== 3
		|| position.some((axis) => !Number.isSafeInteger(axis)) || Math.abs(position[0]) > 29_999_984 || Math.abs(position[2]) > 29_999_984 || position[1] < -2048 || position[1] > 2048) {
		throw new Error('NATURAL_SPAWN_EVIDENCE: saved overworld spawn is missing or invalid');
	}
	return Object.freeze({ source: 'level.dat', dimension, x: position[0], y: position[1], z: position[2] });
}

export async function prepareSpawnChunk({ rcon, spawn, timeoutMs = 120_000, now = Date.now, poll = () => new Promise((resolve) => setTimeout(resolve, 50)) }) {
	if (!Number.isSafeInteger(timeoutMs) || timeoutMs < 1 || timeoutMs > 120_000) throw new TypeError('Spawn loading timeout must be within 1..120000ms');
	for (const axis of ['x', 'z']) if (!Number.isSafeInteger(spawn?.[axis]) || Math.abs(spawn[axis]) > 29_999_984) throw new TypeError('Spawn chunk requires bounded coordinates');
	const started = now();
	const remove = `execute in minecraft:overworld run forceload remove ${spawn.x} ${spawn.z}`;
	const command = async (text, milliseconds = Math.max(1, timeoutMs - (now() - started))) => {
		let timer;
		try {
			return await Promise.race([Promise.resolve().then(() => rcon.command(text)), new Promise((_, reject) => {
				timer = setTimeout(() => reject(new Error('NATURAL_SPAWN_LOADING_TIMEOUT: RCON spawn loading did not complete')), milliseconds);
			})]);
		} finally { clearTimeout(timer); }
	};
	let retained = false;
	try {
		await command(`execute in minecraft:overworld run forceload add ${spawn.x} ${spawn.z}`);
		for (let attempt = 0; Math.max(now() - started, attempt * 50) < timeoutMs; attempt++) {
			const response = await command(`execute in minecraft:overworld if loaded ${spawn.x} 0 ${spawn.z} run time query gametime`);
			if (/\btime is \d+\b/i.test(String(response?.text ?? response))) {
				retained = true;
				return { operation: 'temporary_spawn_chunk_loading', x: spawn.x, z: spawn.z, ready: true, elapsedMs: Math.max(0, now() - started), terrainModified: false, inventoryModified: false };
			}
			await poll();
		}
		throw new Error('NATURAL_SPAWN_LOADING_TIMEOUT: generated spawn chunk did not become loaded');
	} finally {
		if (!retained) await command(remove, 10_000);
	}
}

if (process.argv[1] && import.meta.url === pathToFileURL(process.argv[1]).href) {
	let rcon;
	try {
		const [levelDat, ...args] = process.argv.slice(2);
		if (!levelDat || args.length % 2 !== 0) throw new Error('Usage: headless-world-spawn.mjs <isolated-level.dat> [--rcon-port <port> --password-file <file> --timeout-ms <ms> --surface-x <x> --surface-z <z>]');
		const options = Object.create(null);
		for (let index = 0; index < args.length; index += 2) {
			if (!['--rcon-port', '--password-file', '--timeout-ms', '--surface-x', '--surface-z'].includes(args[index]) || Object.hasOwn(options, args[index])) throw new Error('Invalid spawn metadata option');
			options[args[index]] = args[index + 1];
		}
		const savedSpawn = parseSavedWorldSpawn(await readFile(levelDat));
		let spawnLoading;
		if (args.length > 0) {
			if (!options['--password-file'] || !options['--rcon-port']) throw new Error('Spawn readiness requires the isolated RCON connection');
			const password = (await readFile(options['--password-file'], 'utf8')).trim();
			rcon = new HeadlessRconClient({ port: Number(options['--rcon-port']), password });
			await rcon.connect();
			const surface = Object.hasOwn(options, '--surface-x') || Object.hasOwn(options, '--surface-z');
			const spawn = surface ? { x: Number(options['--surface-x']), z: Number(options['--surface-z']) } : savedSpawn;
			spawnLoading = await prepareSpawnChunk({ rcon, spawn, timeoutMs: Number(options['--timeout-ms'] ?? 120_000) });
		}
		process.stdout.write(`${JSON.stringify({ savedSpawn, ...(spawnLoading ? { spawnLoading } : {}) })}\n`);
	} catch (error) {
		process.stderr.write(`${error.message}\n`);
		process.exitCode = 1;
	} finally {
		await rcon?.close();
	}
}
