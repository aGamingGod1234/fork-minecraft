import { CELL_SIZE, ObservedMemoryStore, observationWorldId, observationDimension, observationPosition, observedBlocks, spatialCellKey } from './observed-memory-store.mjs';

export { CELL_SIZE, observationWorldId };
export const DEFAULT_RADIUS = 24;
export const MIN_RADIUS = 8;
export const MAX_RADIUS = 32;
export const MAX_KNOWN_CELLS = 256;
export const CUE_IN_VIEW_DISTANCE = 2.5;
export const SEEK_VALUES = Object.freeze(['any', 'nether', 'cave', 'village', 'structure']);
const NEIGHBORS = [[1, 0, 0], [-1, 0, 0], [0, 1, 0], [0, -1, 0], [0, 0, 1], [0, 0, -1]];

/** Lists observed positions and unknown neighboring cells. Only the model chooses a destination. */
export class ExplorationOccupancy {
	#memory;
	constructor({ memoryStore = new ObservedMemoryStore() } = {}) { this.#memory = memoryStore; }
	ingest(agentId, observation) { return this.#memory.ingest(agentId, observation); }
	load(agentId) { return this.#memory.load(agentId); }
	flush(agentId) { return this.#memory.flush(agentId); }
	markBlocked(agentId, dimension, x, z, options = {}) {
		const current = this.#memory.query(agentId);
		this.#memory.markBlocked(agentId, { worldId: options.worldId ?? current.worldId, dimension, x, y: options.y ?? current.position?.y, z, ...options });
	}
	rememberHeading() { /* Compatibility only. Candidate facts have no preferred heading. */ }
	snapshot(agentId, dimension = null, worldId = undefined) {
		const value = this.#memory.query(agentId, { ...(dimension === null ? {} : { dimension }), worldId });
		return { worldId: value.worldId, dimension: value.dimension, knownCells: value.knownCells, seenCells: value.seenCells, visitedCells: value.visitedCells, blockedCells: value.blockedCells };
	}
	candidates(agentId, observation, { radius = DEFAULT_RADIUS, limit = 32, blockId = null } = {}) {
		if (!Number.isFinite(radius) || radius < MIN_RADIUS || radius > MAX_RADIUS) throw new TypeError('radius must be 8..32');
		if (!Number.isSafeInteger(limit) || limit < 1 || limit > 64) throw new TypeError('limit must be 1..64');
		if (blockId !== null && (typeof blockId !== 'string' || blockId.length > 256)) throw new TypeError('blockId must be bounded text');
		const position = extractPosition(observation), dimension = extractDimension(observation), worldId = observationWorldId(observation);
		const base = { worldId, dimension, radius, destination: null, cue: null };
		if (position === null) return { ...base, kind: 'no_observation', candidates: [], reason: 'No observed player position.' };
		const known = this.#memory.query(agentId, { worldId, dimension, nowTick: observation?.world?.gameTime });
		const cells = new Map(known.cells.map((cell) => [cell.key, cell]));
		cells.set(spatialCellKey(position.x, position.y, position.z), { ...(cells.get(spatialCellKey(position.x, position.y, position.z)) ?? {}), visited: true });
		const blocks = new Map(known.blocks.map((block) => [block.key, block]));
		for (const block of observedBlocks(observation)) {
			blocks.set(`${block.x},${block.y},${block.z}`, { ...block, stale: false });
			const key = spatialCellKey(block.x, block.y, block.z);
			cells.set(key, { ...cells.get(key), seen: true });
		}
		const entries = new Map();
		for (const block of blocks.values()) {
			if (block.blockId === 'minecraft:air' || blockId !== null && block.blockId !== blockId) continue;
			const target = { x: block.x + 0.5, y: block.y + 0.5, z: block.z + 0.5 };
			const distance = distanceTo(position, target);
			if (distance > radius) continue;
			const cell = cells.get(spatialCellKey(block.x, block.y, block.z));
			const id = `block:${block.x},${block.y},${block.z}`;
			entries.set(id, { id, kind: 'observed_block', position: target, blockId: block.blockId, ...(block.blockState ? { blockState: block.blockState } : {}), distance, seen: true, visited: cell?.visited === true, blocked: cell?.blocked === true, stale: block.stale === true, reachability: 'unknown' });
		}
		if (blockId === null) for (const [key, cell] of cells) {
			if (cell.blocked || cell.stale) continue;
			const [cx, cy, cz] = key.split(',').map(Number);
			for (const [dx, dy, dz] of NEIGHBORS) {
				const neighbor = `${cx + dx},${cy + dy},${cz + dz}`;
				if (cells.has(neighbor)) continue;
				const target = { x: (cx + dx + 0.5) * CELL_SIZE, y: (cy + dy + 0.5) * CELL_SIZE, z: (cz + dz + 0.5) * CELL_SIZE };
				const distance = distanceTo(position, target);
				if (distance > radius) continue;
				const id = `cell:${neighbor}`;
				entries.set(id, { id, kind: 'unknown_cell', position: target, distance, seen: false, visited: false, blocked: false, stale: false, reachability: 'unknown' });
			}
		}
		const all = [...entries.values()].sort((left, right) => left.distance - right.distance || left.id.localeCompare(right.id));
		return { ...base, kind: 'candidates', candidates: all.slice(0, limit), totalCandidates: all.length, truncated: all.length > limit, knownCells: known.knownCells, reason: 'Distance-sorted facts. Reachability is unknown; choose a target explicitly.' };
	}
	select(agentId, observation, options = {}) { return this.candidates(agentId, observation, options); }
	clear(agentId) { this.#memory.clear(agentId); }
}

export const extractPosition = observationPosition;
export const extractDimension = observationDimension;
export function cellKey(x, y, z) { return z === undefined ? `${Math.floor(x / CELL_SIZE)},${Math.floor(y / CELL_SIZE)}` : spatialCellKey(x, y, z); }
export function cueClassFor() { return null; }
function distanceTo(from, to) { return Math.hypot(to.x - from.x, to.y - from.y, to.z - from.z); }
