import { createHash, randomUUID } from 'node:crypto';
import { mkdir, open, readFile, rename, unlink } from 'node:fs/promises';
import { resolve, join } from 'node:path';

export const CELL_SIZE = 8;
const SESSION_WORLD = `session:${randomUUID()}`;
const MAX_FILE_BYTES = 4_194_304;

export function observationWorldId(observation) {
	const value = observation?.world?.worldId ?? observation?.worldId;
	return typeof value === 'string' && value.length > 0 && value.length <= 256 ? value : SESSION_WORLD;
}

export function observationDimension(observation) {
	const value = observation?.world?.dimension ?? observation?.world?.dimensionId;
	return typeof value === 'string' && value.length > 0 ? (value.includes(':') ? value : `minecraft:${value}`) : 'minecraft:overworld';
}

export function observationPosition(observation) {
	const value = observation?.position ?? observation?.player?.position ?? observation?.player;
	return value && [value.x, value.y, value.z].every(Number.isFinite) ? { x: value.x, y: value.y, z: value.z } : null;
}

export function spatialCellKey(x, y, z) {
	return [x, y, z].map((value) => Math.floor(value / CELL_SIZE)).join(',');
}

/** One coordinator instance owns each agent file; writes replace complete bounded records. */
export class AtomicAgentStore {
	#directory; #namespace; #writes = new Map();
	constructor({ directory = null, namespace }) {
		this.#directory = directory === null ? null : resolve(directory);
		this.#namespace = namespace;
	}
	async read(agentId) {
		if (this.#directory === null) return null;
		await this.#writes.get(agentId);
		try {
			const bytes = await readFile(this.#path(agentId));
			if (bytes.length > MAX_FILE_BYTES) throw new Error('MEMORY_FILE_TOO_LARGE');
			return JSON.parse(bytes.toString('utf8'));
		} catch (error) { if (error.code === 'ENOENT') return null; throw error; }
	}
	write(agentId, value) {
		if (this.#directory === null) return Promise.resolve();
		const encoded = JSON.stringify(value);
		if (Buffer.byteLength(encoded) > MAX_FILE_BYTES) return Promise.reject(new Error('MEMORY_FILE_TOO_LARGE'));
		const previous = this.#writes.get(agentId) ?? Promise.resolve();
		const pending = previous.catch(() => {}).then(async () => {
			await mkdir(this.#directory, { recursive: true });
			const target = this.#path(agentId);
			const temporary = `${target}.${randomUUID()}.tmp`;
			try {
				const file = await open(temporary, 'wx', 0o600);
				try { await file.writeFile(encoded, 'utf8'); await file.sync(); } finally { await file.close(); }
				await rename(temporary, target);
			} finally { await unlink(temporary).catch((error) => { if (error.code !== 'ENOENT') throw error; }); }
		});
		this.#writes.set(agentId, pending);
		return pending.finally(() => { if (this.#writes.get(agentId) === pending) this.#writes.delete(agentId); });
	}
	#path(agentId) {
		if (typeof agentId !== 'string' || agentId.length === 0 || agentId.length > 256) throw new TypeError('agentId must be bounded text');
		return join(this.#directory, `${this.#namespace}-${createHash('sha256').update(agentId).digest('hex')}.json`);
	}
}

/** Retains only observations and action failures, partitioned by world, dimension and height. */
export class ObservedMemoryStore {
	#agents = new Map(); #loads = new Map(); #loaded = new Set(); #epochs = new Map(); #disk; #maximumCells; #maximumBlocks; #maximumScopes; #staleAfterTicks; #blockedForTicks;
	constructor({ directory = null, maximumCells = 256, maximumBlocks = 1024, maximumScopes = 16, staleAfterTicks = 1200, blockedForTicks = 200 } = {}) {
		for (const value of [maximumCells, maximumBlocks, maximumScopes, staleAfterTicks, blockedForTicks]) {
			if (!Number.isSafeInteger(value) || value < 1) throw new TypeError('memory bounds must be positive integers');
		}
		if (maximumCells > 2048 || maximumBlocks > 2048 || maximumScopes > 32) throw new TypeError('memory capacity exceeds the durable record budget');
		this.#disk = new AtomicAgentStore({ directory, namespace: 'observed' });
		this.#maximumCells = maximumCells; this.#maximumBlocks = maximumBlocks; this.#maximumScopes = maximumScopes;
		this.#staleAfterTicks = staleAfterTicks; this.#blockedForTicks = blockedForTicks;
	}
	load(agentId) {
		if (this.#loaded.has(agentId)) return Promise.resolve();
		if (this.#loads.has(agentId)) return this.#loads.get(agentId);
		const pending = this.#load(agentId, this.#epochs.get(agentId) ?? 0);
		this.#loads.set(agentId, pending);
		return pending.finally(() => { if (this.#loads.get(agentId) === pending) this.#loads.delete(agentId); });
	}
	async #load(agentId, epoch) {
		const saved = await this.#disk.read(agentId);
		if ((this.#epochs.get(agentId) ?? 0) !== epoch) return;
		if (saved === null) { this.#loaded.add(agentId); return; }
		if (saved.version !== 1 || !Array.isArray(saved.scopes)) throw new Error('INVALID_OBSERVED_MEMORY');
		const scopes = new Map();
		for (const scope of saved.scopes.slice(-this.#maximumScopes)) {
			if (typeof scope.worldId !== 'string' || typeof scope.dimension !== 'string' || !Array.isArray(scope.cells) || !Array.isArray(scope.blocks) || !Number.isSafeInteger(scope.tick)) throw new Error('INVALID_OBSERVED_MEMORY');
			if (scope.worldId.startsWith('session:')) continue;
			const cells = new Map(scope.cells.filter(validCell).slice(-this.#maximumCells).map((cell) => [cell.key, projectCell(cell)]));
			const blocks = new Map(scope.blocks.filter(validBlock).slice(-this.#maximumBlocks).map((block) => [block.key, projectBlock(block)]));
			scopes.set(scopeKey(scope.worldId, scope.dimension), { worldId: scope.worldId, dimension: scope.dimension, tick: scope.tick, position: observationPosition({ position: scope.position }), cells, blocks });
		}
		const live = this.#agents.get(agentId);
		if (live) for (const [key, scope] of live.scopes) {
			const previous = scopes.get(key);
			const merged = previous && scope.tick >= previous.tick ? {
				...scope, cells: new Map([...previous.cells, ...scope.cells]), blocks: new Map([...previous.blocks, ...scope.blocks]),
			} : scope;
			scopes.delete(key); scopes.set(key, merged);
		}
		trim(scopes, this.#maximumScopes);
		const agent = { scopes, current: live?.current ?? null };
		this.#bound(agent);
		this.#agents.set(agentId, agent);
		this.#loaded.add(agentId);
	}
	async flush(agentId) {
		const epoch = this.#epochs.get(agentId) ?? 0;
		await this.load(agentId);
		if ((this.#epochs.get(agentId) ?? 0) !== epoch) return;
		const agent = this.#agents.get(agentId);
		return this.#disk.write(agentId, { version: 1, scopes: agent ? [...agent.scopes.values()].filter((scope) => !scope.worldId.startsWith('session:')).map((scope) => ({ ...scope, cells: [...scope.cells.values()], blocks: [...scope.blocks.values()] })) : [] });
	}
	ingest(agentId, observation) {
		const worldId = observationWorldId(observation), dimension = observationDimension(observation);
		const agent = this.#agent(agentId);
		const key = scopeKey(worldId, dimension);
		let scope = agent.scopes.get(key);
		const tick = Number.isSafeInteger(observation?.world?.gameTime) && observation.world.gameTime >= 0 ? observation.world.gameTime : (scope?.tick ?? 0) + 1;
		if (!scope || tick < scope.tick) scope = { worldId, dimension, tick, position: null, cells: new Map(), blocks: new Map() };
		scope.tick = tick;
		agent.current = key;
		agent.scopes.delete(key); agent.scopes.set(key, scope);
		trim(agent.scopes, this.#maximumScopes);
		const position = observationPosition(observation);
		if (position !== null) {
			scope.position = position;
			this.#cell(scope, position, { visited: true });
		}
		for (const block of observedBlocks(observation)) {
			const key = `${block.x},${block.y},${block.z}`;
			const previous = scope.blocks.get(key);
			const changed = previous !== undefined && (previous.blockId !== block.blockId || previous.blockState !== block.blockState);
			this.#cell(scope, block, { seen: true, changed });
			scope.blocks.delete(key);
			scope.blocks.set(key, { key, x: block.x, y: block.y, z: block.z, blockId: block.blockId, ...(block.blockState ? { blockState: block.blockState } : {}), firstSeenTick: previous?.firstSeenTick ?? tick, lastSeenTick: tick });
		}
		trim(scope.blocks, this.#maximumBlocks);
		this.#bound(agent);
		return this.query(agentId, { worldId, dimension });
	}
	markBlocked(agentId, { worldId, dimension, x, y, z, tick, reasonCode = 'PATH_BLOCKED' }) {
		const agent = this.#agents.get(agentId);
		const scope = agent?.scopes.get(scopeKey(worldId, dimension));
		if (!scope || ![x, y, z].every(Number.isFinite)) return;
		const cell = this.#cell(scope, { x, y, z }, {});
		cell.blockedAtTick = Number.isSafeInteger(tick) ? tick : scope.tick;
		cell.blockedUntilTick = cell.blockedAtTick + this.#blockedForTicks;
		cell.reasonCode = String(reasonCode).slice(0, 128);
		this.#bound(agent);
	}
	query(agentId, { worldId, dimension, nowTick, blockId = null, limit = 1024 } = {}) {
		if (nowTick !== undefined && (!Number.isSafeInteger(nowTick) || nowTick < 0)) throw new TypeError('nowTick must be a nonnegative integer');
		if (!Number.isSafeInteger(limit) || limit < 0) throw new TypeError('limit must be a nonnegative integer');
		const agent = this.#agents.get(agentId);
		const current = agent?.scopes.get(agent.current);
		worldId ??= current?.worldId ?? SESSION_WORLD;
		dimension ??= current?.dimension ?? 'minecraft:overworld';
		const scope = agent?.scopes.get(scopeKey(worldId, dimension));
		const tick = nowTick ?? scope?.tick ?? 0;
		const cells = scope ? [...scope.cells.values()].map((cell) => ({ ...cell, blocked: (cell.blockedUntilTick ?? -1) > tick, stale: tick - cell.lastObservedTick > this.#staleAfterTicks })) : [];
		const blocks = scope && limit > 0 ? [...scope.blocks.values()].filter((block) => blockId === null || block.blockId === blockId).slice(-Math.min(limit, this.#maximumBlocks)).map((block) => ({ ...block, stale: tick - block.lastSeenTick > this.#staleAfterTicks })) : [];
		return { worldId, dimension, tick, position: scope?.position ? { ...scope.position } : null, cells, blocks, knownCells: cells.length, seenCells: cells.filter((cell) => cell.seen).length, visitedCells: cells.filter((cell) => cell.visited).length, blockedCells: cells.filter((cell) => cell.blocked).length };
	}
	clear(agentId) {
		for (const id of agentId === undefined ? new Set([...this.#agents.keys(), ...this.#loads.keys(), ...this.#loaded]) : [agentId]) {
			this.#agents.delete(id); this.#loaded.delete(id); this.#loads.delete(id);
			this.#epochs.set(id, (this.#epochs.get(id) ?? 0) + 1);
		}
	}
	#bound(agent) {
		for (const [field, maximum] of [['cells', this.#maximumCells], ['blocks', this.#maximumBlocks]]) {
			let excess = [...agent.scopes.values()].reduce((sum, scope) => sum + scope[field].size, 0) - maximum;
			for (const scope of agent.scopes.values()) {
				while (excess > 0 && scope[field].size > 0) { scope[field].delete(scope[field].keys().next().value); excess--; }
				if (excess <= 0) break;
			}
		}
	}
	#agent(agentId) {
		let agent = this.#agents.get(agentId);
		if (!agent) { agent = { scopes: new Map(), current: null }; this.#agents.set(agentId, agent); }
		return agent;
	}
	#cell(scope, position, { seen = false, visited = false, changed = false }) {
		const key = spatialCellKey(position.x, position.y, position.z);
		const cell = scope.cells.get(key) ?? { key, seen: false, visited: false, firstObservedTick: scope.tick };
		cell.seen ||= seen; cell.visited ||= visited;
		cell.lastObservedTick = scope.tick;
		if (seen) cell.lastSeenTick = scope.tick;
		if (visited) cell.lastVisitedTick = scope.tick;
		if (visited || changed) { delete cell.blockedAtTick; delete cell.blockedUntilTick; delete cell.reasonCode; }
		scope.cells.delete(key); scope.cells.set(key, cell); trim(scope.cells, this.#maximumCells);
		return cell;
	}
}

export function observedBlocks(observation) {
	const result = new Map();
	for (const list of [observation?.blocks, observation?.landmarks, observation?.nearbyContainers, observation?.nearby?.blocks]) {
		for (const block of Array.isArray(list) ? list : []) {
			const position = block?.position ?? block;
			if (!position || ![position.x, position.y, position.z].every(Number.isFinite) || typeof block.blockId !== 'string' || block.blockId.length > 256 || block.visible === false) continue;
			result.set(`${position.x},${position.y},${position.z}`, { x: position.x, y: position.y, z: position.z, blockId: block.blockId, ...(typeof block.blockState === 'string' ? { blockState: block.blockState.slice(0, 1024) } : {}) });
		}
	}
	return [...result.values()];
}

function scopeKey(worldId, dimension) { return JSON.stringify([worldId, dimension]); }
function trim(map, maximum) { while (map.size > maximum) map.delete(map.keys().next().value); }
function validCell(cell) { return cell && typeof cell.key === 'string' && /^-?\d+,-?\d+,-?\d+$/.test(cell.key) && Number.isSafeInteger(cell.lastObservedTick) && typeof cell.seen === 'boolean' && typeof cell.visited === 'boolean'; }
function validBlock(block) { return block && typeof block.key === 'string' && [block.x, block.y, block.z].every(Number.isFinite) && typeof block.blockId === 'string' && block.blockId.length <= 256 && Number.isSafeInteger(block.lastSeenTick); }
function projectCell(cell) {
	return Object.fromEntries(['key', 'seen', 'visited', 'firstObservedTick', 'lastObservedTick', 'lastSeenTick', 'lastVisitedTick', 'blockedAtTick', 'blockedUntilTick', 'reasonCode'].filter((field) => cell[field] !== undefined).map((field) => [field, cell[field]]));
}
function projectBlock(block) {
	return { key: block.key, x: block.x, y: block.y, z: block.z, blockId: block.blockId, ...(typeof block.blockState === 'string' ? { blockState: block.blockState.slice(0, 1024) } : {}), firstSeenTick: block.firstSeenTick, lastSeenTick: block.lastSeenTick };
}
