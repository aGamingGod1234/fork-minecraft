import { EventEmitter } from 'node:events';

import {
	MAX_CONVERSATION_LENGTH,
	MAX_DESIRED_STATE_LENGTH,
	MAX_EFFECTS,
	MAX_INVENTORY_SUMMARIES,
	MAX_OBSERVATION_TAGS,
	MAX_TAG_COUNT_ENTRIES,
} from '../constants.mjs';
import { SeededRandom } from './seeded-random.mjs';

export const VIRTUAL_TICK_HZ = 20;
export const VIRTUAL_TICK_MS = 1_000 / VIRTUAL_TICK_HZ;
export const DEFAULT_PICKUP_RADIUS = 1.5;
export const MAX_WORLD_BLOCKS = 256;
export const MAX_WORLD_ITEMS = 128;
export const MAX_WORLD_ENTITIES = 64;
export const MAX_PLAYER_INVENTORY_ITEMS = MAX_INVENTORY_SUMMARIES;

const PLAYER_HALF_WIDTH = 0.3;
const PLAYER_HEIGHT = 1.8;
const GRAVITY = 0.08;
const AIR_DRAG = 0.91;
const MAX_OBSERVED_BLOCKS = 128;
const MAX_OBSERVED_ENTITIES = 64;
const SOLID_EXCEPTIONS = new Set(['minecraft:air', 'minecraft:cave_air', 'minecraft:void_air', 'minecraft:water', 'minecraft:lava']);

/**
 * Authoritative, wall-clock-independent Minecraft-like world used by the benchmark.
 * Physics is deliberately small; production action breadth belongs to the bridge/runtime.
 */
export class VirtualWorld extends EventEmitter {
	#dimension;
	#worldId;
	#seed;
	#random;
	#pickupRadius;
	#lavaDamagePerTick;
	#players = new Map();
	#blocks = new Map();
	#entities = new Map();
	#items = new Map();
	#inputs = new Map();
	#observationSequences = new Map();
	#conversationEvents = [];
	#tickCount = 0;
	#timeMs = 0;
	#scheduler;
	#intervalHandle = null;
	#schedulerGeneration = 0;
	#started = false;
	#randomEvents;
	#raining;
	#thundering;

	static fromScenario(scenario, dependencies = {}) {
		return new VirtualWorld(scenario, dependencies);
	}

	constructor(scenario, dependencies = {}) {
		super();
		const source = cloneScenario(scenario);
		if (!isRecord(source)) throw new TypeError('scenario must be a plain object');
		this.#dimension = identifier(source.dimension ?? 'minecraft:overworld', 'dimension');
		this.#worldId = source.worldId === undefined ? undefined : identifier(source.worldId, 'worldId');
		this.#seed = source.seed ?? 0;
		this.#random = new SeededRandom(this.#seed);
		this.#pickupRadius = finiteNonnegative(source.pickupRadius ?? DEFAULT_PICKUP_RADIUS, 'pickupRadius');
		this.#lavaDamagePerTick = finiteNonnegative(source.lavaDamagePerTick ?? 1, 'lavaDamagePerTick');
		this.#raining = source.raining === true;
		this.#thundering = source.thundering === true;
		this.#scheduler = dependencies.scheduler ?? source.scheduler ?? {
			setInterval: globalThis.setInterval,
			clearInterval: globalThis.clearInterval,
		};
		if (typeof this.#scheduler?.setInterval !== 'function' || typeof this.#scheduler?.clearInterval !== 'function') {
			throw new TypeError('scheduler must provide setInterval and clearInterval');
		}
		this.#randomEvents = normalizeArray(source.randomEvents ?? source.random?.events);
		for (const [agentId, value] of entries(source.agents ?? source.players, 'agents')) this.#addPlayer(agentId, value);
		if (this.#players.size === 0) throw new TypeError('scenario must declare at least one agent');
		for (const block of normalizeBlocks(source.blocks)) this.#insertBlock(block);
		for (const entity of normalizeEntities(source.entities)) this.#insertEntity(entity);
		for (const item of normalizeItems(source.items ?? source.drops)) this.#insertItem(item);
	}

	get tickCount() { return this.#tickCount; }
	get timeMs() { return this.#timeMs; }
	get running() { return this.#started; }
	get seed() { return this.#seed; }
	get agentIds() { return [...this.#players.keys()]; }

	start() {
		if (this.#started) return this;
		this.#started = true;
		const generation = ++this.#schedulerGeneration;
		let handle = null;
		const callback = () => {
			if (handle === null || !this.#started || this.#schedulerGeneration !== generation || this.#intervalHandle !== handle) return;
			this.tick();
		};
		try { handle = this.#scheduler.setInterval(callback, VIRTUAL_TICK_MS); }
		catch (error) { this.#started = false; throw error; }
		this.#intervalHandle = handle;
		return this;
	}

	stop() {
		if (!this.#started) return this;
		this.#schedulerGeneration += 1;
		this.#scheduler.clearInterval(this.#intervalHandle);
		this.#intervalHandle = null;
		this.#started = false;
		return this;
	}

	tick() {
		this.#tickCount += 1;
		this.#timeMs = this.#tickCount * VIRTUAL_TICK_MS;
		this.#applyRandomEvents();
		for (const [agentId, player] of this.#players) {
			if (player.dead) continue;
			this.#applyInput(player, this.#inputs.get(agentId));
			this.#integratePlayer(player);
			this.#applyEnvironment(player);
			this.#pickupItems(player);
		}
		this.emit('tick', { tick: this.#tickCount, timeMs: this.#timeMs });
		return this.#tickCount;
	}

	stepTicks(count) {
		if (!Number.isSafeInteger(count) || count < 0 || count > 1_000_000) throw new RangeError('count must be an integer between 0 and 1000000');
		for (let index = 0; index < count; index += 1) this.tick();
		return this.#tickCount;
	}

	observation(agentId, { attention = false, changedFacts = [] } = {}) {
		const player = this.#requirePlayer(agentId);
		const eventSequence = this.#nextObservationSequence(agentId);
		const facts = attention ? [...new Set(changedFacts)] : [];
		if (player.dead) {
			return {
				goalRevision: player.goalRevision,
				observedAtEpochMs: this.#timeMs,
				ready: false,
				status: 'PLAYER_DEAD',
				eventSequence,
				attention,
				changedFacts: facts,
			};
		}
		const position = clone(player.position);
		const entities = [
			...this.#entities.values(),
			...this.#items.values().map((item) => ({
				id: item.id,
				type: 'minecraft:item',
				name: item.itemId,
				position: clone(item.position),
				itemId: item.itemId,
				count: item.count,
			})),
		]
			.map((entity) => ({
				uuid: entity.id,
				type: entity.type,
				name: entity.name ?? entity.type,
				distance: distance(position, entity.position),
				position: clone(entity.position),
				...(entity.type === 'minecraft:item' ? { itemId: entity.itemId, count: entity.count } : {}),
				...(entity.isPlayer !== undefined ? { isPlayer: entity.isPlayer } : {}),
				...(entity.tags !== undefined ? { tags: [...entity.tags] } : {}),
			}))
			.slice(0, MAX_OBSERVED_ENTITIES);
		const blocks = [...this.#blocks.values()].slice(0, MAX_OBSERVED_BLOCKS).map((block) => ({
				x: block.x,
				y: block.y,
				z: block.z,
				blockId: block.blockId,
				placeableFaces: ['down', 'up', 'north', 'south', 'west', 'east'],
				...(block.tags !== undefined ? { tags: [...block.tags] } : {}),
			}));
		return {
			goalRevision: player.goalRevision,
			observedAtEpochMs: this.#timeMs,
			ready: true,
			status: 'ready',
			eventSequence,
			attention,
			changedFacts: facts,
			position,
			velocity: clone(player.velocity),
			view: { yaw: player.yaw, pitch: player.pitch },
			player: {
				health: player.health,
				maxHealth: player.maxHealth,
				armor: player.armor,
				foodLevel: player.foodLevel,
				saturation: player.saturation,
				gameMode: player.gameMode,
				onGround: player.onGround,
				inWater: player.inWater,
				onFire: player.onFire,
				air: player.air,
				maxAir: player.maxAir,
				suffocating: player.suffocating,
				fallDistance: player.fallDistance,
				effects: player.effects.map(clone),
				...(player.lastAttacker === undefined ? {} : { lastAttacker: clone(player.lastAttacker) }),
			},
			inventory: {
				items: player.inventory.items.map(clone),
				selectedItem: player.selectedItem,
				...(Object.keys(player.inventory.tagCounts).length === 0 ? {} : { tagCounts: clone(player.inventory.tagCounts) }),
			},
			entities,
			blocks,
			nearbyContainers: [],
			world: {
				...(this.#worldId === undefined ? {} : { worldId: this.#worldId }),
				dimension: this.#dimension,
				gameTime: this.#tickCount,
				dayTime: this.#tickCount % 24_000,
				 raining: this.#raining,
				thundering: this.#thundering,
			},
			currentAction: player.activeAction === null ? { active: false } : {
				active: true,
				actionId: player.activeAction.actionId,
				actionType: player.activeAction.actionType,
			},
			lastResult: player.lastResult === null ? { present: false } : clone(player.lastResult),
		};
	}

	playerState(agentId) {
		const player = this.#requirePlayer(agentId);
		return clone(player);
	}

	blockAt(x, y, z) {
		const block = this.#blocks.get(blockKey(Math.trunc(x), Math.trunc(y), Math.trunc(z)));
		return block === undefined ? null : clone(block);
	}

	entityState(entityId) {
		const id = identifier(entityId, 'entityId');
		const entity = this.#entities.get(id);
		if (entity === undefined) throw new RangeError(`unknown entity '${id}'`);
		return clone(entity);
	}

	damageEntity(entityId, amount, source = undefined) {
		const id = identifier(entityId, 'entityId');
		const entity = this.#entities.get(id);
		if (entity === undefined) throw new RangeError(`unknown entity '${id}'`);
		const damageAmount = finiteNonnegative(amount, 'entity damage');
		if (entity.dead || damageAmount === 0) return entity.health;
		entity.health = Math.max(0, entity.health - damageAmount);
		if (source !== undefined) entity.lastAttacker = clone(source);
		if (entity.health === 0) entity.dead = true;
		return entity.health;
	}

	inventories(agentId) {
		return clone(this.#requirePlayer(agentId).inventory);
	}

	countInventoryItem(agentId, itemId) {
		const id = identifier(itemId, 'itemId');
		return this.#requirePlayer(agentId).inventory.items
			.filter((item) => item.itemId === id)
			.reduce((total, item) => total + item.count, 0);
	}

	removeInventoryItem(agentId, itemId, count, slot = undefined) {
		const player = this.#requirePlayer(agentId);
		const id = identifier(itemId, 'itemId');
		const quantity = positiveInteger(count, 'count');
		if (this.countInventoryItem(agentId, id) < quantity) return false;
		let remaining = quantity;
		for (const item of player.inventory.items) {
			if (item.itemId !== id || (slot !== undefined && item.slot !== slot) || remaining === 0) continue;
			const used = Math.min(item.count, remaining);
			item.count -= used;
			remaining -= used;
		}
		player.inventory.items = player.inventory.items.filter((item) => item.count > 0);
		return remaining === 0;
	}

	addInventoryItem(agentId, itemId, count) {
		const player = this.#requirePlayer(agentId);
		const id = identifier(itemId, 'itemId');
		const quantity = positiveInteger(count, 'count');
		const existing = player.inventory.items.find((item) => item.itemId === id);
		if (existing) existing.count += quantity;
		else {
			if (player.inventory.items.length >= MAX_PLAYER_INVENTORY_ITEMS) throw capacityError('inventory items', MAX_PLAYER_INVENTORY_ITEMS);
			player.inventory.items.push({ itemId: id, count: quantity, damage: 0, maxDamage: 0, slot: nextInventorySlot(player.inventory.items) });
		}
		return this.countInventoryItem(agentId, id);
	}

	setShield(agentId, active) {
		const player = this.#requirePlayer(agentId);
		player.shieldActive = active === true;
		return player.shieldActive;
	}

	isShielding(agentId) { return this.#requirePlayer(agentId).shieldActive === true; }

	recordDirectMessage(agentId, recipientId, message) {
		const sender = identifier(agentId, 'agentId');
		const recipient = identifier(recipientId, 'recipientId');
		const text = conversationText(message);
		const sequence = this.#conversationEvents.length + 1;
		const event = Object.freeze({
			eventId: `conversation-${sequence}`,
			sequence,
			tick: this.#tickCount,
			kind: 'agent_message',
			scope: 'direct',
			sourceId: sender,
			senderId: sender,
			recipientId: recipient,
			message: text,
			wakeAcknowledged: true,
			processed: true,
		});
		this.#conversationEvents.push(event);
		return event;
	}

	conversationEvents() { return clone(this.#conversationEvents); }

	setVelocity(agentId, velocity) {
		const player = this.#requirePlayer(agentId);
		player.velocity = vector(velocity, 'velocity');
		return this;
	}

	setInput(agentId, input = {}) {
		this.#requirePlayer(agentId);
		this.#inputs.set(agentId, normalizeInput(input));
		return this;
	}

	applyInput(agentId, input = {}) { return this.setInput(agentId, input); }

	recordCheckpoint(agentId, position = undefined) {
		const player = this.#requirePlayer(agentId);
		player.checkpoint = vector(position ?? player.position, 'checkpoint');
		return clone(player.checkpoint);
	}

	setCheckpoint(agentId, position = undefined) { return this.recordCheckpoint(agentId, position); }

	checkpoint(agentId) {
		const checkpoint = this.#requirePlayer(agentId).checkpoint;
		return checkpoint === null ? null : clone(checkpoint);
	}

	setActiveAction(agentId, actionId, actionType) {
		const player = this.#requirePlayer(agentId);
		player.activeAction = actionId === null ? null : { actionId: identifier(actionId, 'actionId'), actionType: identifier(actionType, 'actionType') };
		return this;
	}

	setLastResult(agentId, result = null) {
		const player = this.#requirePlayer(agentId);
		player.lastResult = normalizeLastResult(result);
		return this;
	}

	damage(agentId, amount, source = undefined) {
		const player = this.#requirePlayer(agentId);
		const rawDamage = finiteNonnegative(amount, 'damage');
		const damageAmount = player.shieldActive ? rawDamage * 0.5 : rawDamage;
		if (player.dead || damageAmount === 0) return player.health;
		player.health = Math.max(0, player.health - damageAmount);
		if (source !== undefined) player.lastAttacker = normalizeAttacker(source);
		if (player.health === 0) this.#kill(player);
		return player.health;
	}

	respawn(agentId) {
		const player = this.#requirePlayer(agentId);
		if (!player.dead) return false;
		player.dead = false;
		player.health = player.maxHealth;
		player.position = clone(player.checkpoint ?? player.spawnPosition);
		player.velocity = { x: 0, y: 0, z: 0 };
		player.onGround = false;
		player.onFire = false;
		player.fallDistance = 0;
		player.shieldActive = false;
		player.lastAttacker = undefined;
		player.lastResult = null;
		return true;
	}

	/** Keeps the Task 3 bridge contract stable while the richer runtime uses the simulator entry point below. */
	performAction(agentId, action, options = {}) {
		const normalized = normalizeAction(action);
		if (normalized.type === 'craft_inventory' || normalized.type === 'craft_table') {
			return { done: true, state: 'FAILED', reasonCode: 'SIMULATOR_UNSUPPORTED_ACTION', changed: false };
		}
		return this.performSimulationAction(agentId, action, options);
	}

	/** Executes one deterministic slice of a complete simulator action. */
	performSimulationAction(agentId, action, { elapsedTicks = 0 } = {}) {
		const player = this.#requirePlayer(agentId);
		const normalized = normalizeAction(action);
		if (player.dead && normalized.type !== 'respawn') return { done: true, state: 'FAILED', reasonCode: 'PLAYER_DEAD', changed: false };
		const args = normalized.arguments;
		switch (normalized.type) {
			case 'move_to':
			case 'navigate_to': {
				const target = { x: args.x, y: args.y, z: args.z };
				const remaining = distance(player.position, target);
				if (remaining <= args.tolerance) {
					if (!this.#isPositionClear(target)) {
						return { done: true, state: 'FAILED', reasonCode: 'DESTINATION_BLOCKED', changed: false };
					}
					player.velocity = { x: 0, y: 0, z: 0 };
					return { done: true, state: 'SUCCEEDED', reasonCode: 'ARRIVED', changed: true };
				}
				const horizontal = Math.hypot(target.x - player.position.x, target.z - player.position.z) || 1;
				const speed = args.sprint ? 0.22 : 0.14;
				player.velocity.x = ((target.x - player.position.x) / horizontal) * speed;
				player.velocity.z = ((target.z - player.position.z) / horizontal) * speed;
				if (Math.abs(target.y - player.position.y) > args.tolerance && player.onGround && target.y > player.position.y) player.velocity.y = 0.42;
				player.yaw = approachAngle(player.yaw, Math.atan2(target.z - player.position.z, target.x - player.position.x) * 180 / Math.PI - 90, 12);
				return { done: false, changed: true };
			}
			case 'look_at': {
				const dx = args.x - player.position.x;
				const dy = args.y - (player.position.y + 1.62);
				const dz = args.z - player.position.z;
				player.yaw = Math.atan2(dz, dx) * 180 / Math.PI - 90;
				player.pitch = -Math.atan2(dy, Math.hypot(dx, dz)) * 180 / Math.PI;
				return { done: true, state: 'SUCCEEDED', reasonCode: 'LOOKED', changed: true };
			}
			case 'wait':
			case 'use_item': {
				const requiredTicks = Math.max(1, Math.ceil(args.durationMs / VIRTUAL_TICK_MS));
				return elapsedTicks + 1 >= requiredTicks
					? { done: true, state: 'SUCCEEDED', reasonCode: 'DONE', changed: false }
					: { done: false, changed: false };
			}
			case 'block_with_shield': {
				player.shieldActive = true;
				const requiredTicks = Math.max(1, Math.ceil(args.durationMs / VIRTUAL_TICK_MS));
				if (elapsedTicks + 1 < requiredTicks) return { done: false, changed: false };
				player.shieldActive = false;
				return { done: true, state: 'SUCCEEDED', reasonCode: 'SHIELDING_DONE', changed: true };
			}
			case 'attack': {
				const entity = this.#entities.get(args.targetId);
				if (entity === undefined) return { done: true, state: 'FAILED', reasonCode: 'TARGET_NOT_FOUND', changed: false };
				if (entity.dead) return { done: true, state: 'FAILED', reasonCode: 'TARGET_DEAD', changed: false };
				if (distance(player.position, entity.position) > 4.5) return { done: true, state: 'FAILED', reasonCode: 'TARGET_OUT_OF_RANGE', changed: false };
				if (entity.cooldownUntilTick > this.#tickCount) return { done: false, changed: false };
				entity.cooldownUntilTick = this.#tickCount + 10;
				this.damageEntity(entity.id, 3, { uuid: player.id, type: 'minecraft:player', distance: distance(player.position, entity.position) });
				return { done: true, state: 'SUCCEEDED', reasonCode: entity.dead ? 'TARGET_DEFEATED' : 'HIT', changed: true };
			}
			case 'respawn':
				return this.respawn(agentId)
					? { done: true, state: 'SUCCEEDED', reasonCode: 'RESPAWNED', changed: true }
					: { done: true, state: 'FAILED', reasonCode: 'NOT_DEAD', changed: false };
			case 'select_item':
				player.selectedItem = args.itemId;
				return { done: true, state: 'SUCCEEDED', reasonCode: 'SELECTED', changed: true };
			case 'break_block': {
				const key = blockKey(args.x, args.y, args.z);
				const block = this.#blocks.get(key);
				if (!block) return { done: true, state: 'FAILED', reasonCode: 'BLOCK_NOT_FOUND', changed: false };
				if (elapsedTicks + 1 < miningDurationTicks(block.blockId)) return { done: false, changed: false };
				const inventoryBefore = clone(player.inventory);
				try {
					this.#blocks.delete(key);
					this.addInventoryItem(agentId, blockDrop(block.blockId), 1);
				} catch (error) {
					this.#blocks.set(key, block);
					player.inventory = inventoryBefore;
					if (error.code === 'WORLD_CAPACITY_EXCEEDED') return { done: true, state: 'FAILED', reasonCode: error.code, changed: false };
					throw error;
				}
				return { done: true, state: 'SUCCEEDED', reasonCode: 'BROKEN', changed: true };
			}
			case 'place_block': {
				const targetKey = blockKey(args.x, args.y, args.z);
				if (this.#blocks.has(targetKey)) return { done: true, state: 'FAILED', reasonCode: 'BLOCK_OCCUPIED', changed: false };
				const support = placementSupport(args.x, args.y, args.z, args.face);
				if (support === null || !isSolid(this.#blocks.get(blockKey(support.x, support.y, support.z))?.blockId)) {
					return { done: true, state: 'FAILED', reasonCode: 'PLACEMENT_UNSUPPORTED', changed: false };
				}
				const inventoryBefore = clone(player.inventory);
				try {
					const block = normalizeBlock({ x: args.x, y: args.y, z: args.z, blockId: args.itemId, desiredState: args.desiredState });
					if (!this.removeInventoryItem(agentId, args.itemId, 1)) return { done: true, state: 'FAILED', reasonCode: 'ITEM_NOT_FOUND', changed: false };
					this.#insertBlock(block);
					const placed = this.blockAt(args.x, args.y, args.z);
					if (placed?.blockId !== args.itemId || (args.desiredState !== undefined && args.desiredState !== null && placed.desiredState !== args.desiredState)) {
						this.#blocks.delete(targetKey);
						player.inventory = inventoryBefore;
						return { done: true, state: 'FAILED', reasonCode: 'PLACEMENT_STATE_MISMATCH', changed: false };
					}
				} catch (error) {
					this.#blocks.delete(targetKey);
					player.inventory = inventoryBefore;
					if (error.code === 'WORLD_CAPACITY_EXCEEDED') return { done: true, state: 'FAILED', reasonCode: error.code, changed: false };
					throw error;
				}
				return { done: true, state: 'SUCCEEDED', reasonCode: 'PLACED', changed: true };
			}
			case 'craft_inventory':
			case 'craft_table': {
				if (normalized.type === 'craft_table' && this.#blocks.get(blockKey(args.x, args.y, args.z))?.blockId !== 'minecraft:crafting_table') {
					return { done: true, state: 'FAILED', reasonCode: 'CRAFTING_TABLE_NOT_FOUND', changed: false };
				}
				const recipe = craftRecipe(args.recipeId, args.count);
				if (!recipe) return { done: true, state: 'FAILED', reasonCode: 'RECIPE_NOT_FOUND', changed: false };
				for (const [itemId, count] of Object.entries(recipe.inputs)) {
					if (this.countInventoryItem(agentId, itemId) < count) return { done: true, state: 'FAILED', reasonCode: 'INGREDIENTS_MISSING', changed: false };
				}
				if (elapsedTicks + 1 < 2) return { done: false, changed: false };
				const inventoryBefore = clone(player.inventory);
				try {
					for (const [itemId, count] of Object.entries(recipe.inputs)) this.removeInventoryItem(agentId, itemId, count);
					this.addInventoryItem(agentId, recipe.output, args.count);
				} catch (error) {
					player.inventory = inventoryBefore;
					if (error.code === 'WORLD_CAPACITY_EXCEEDED') return { done: true, state: 'FAILED', reasonCode: error.code, changed: false };
					throw error;
				}
				return { done: true, state: 'SUCCEEDED', reasonCode: 'CRAFTED', changed: true };
			}
			case 'chat': {
				if ((args.audience ?? 'public') === 'direct') this.recordDirectMessage(agentId, args.recipientId, args.message);
				return { done: true, state: 'SUCCEEDED', reasonCode: 'MESSAGE_SENT', changed: true };
			}
			case 'drop_item': {
				const held = player.inventory.items.find((item) => item.slot === args.slot && item.count >= args.count);
				if (!held) return { done: true, state: 'FAILED', reasonCode: 'ITEM_NOT_FOUND', changed: false };
				if (this.#items.size >= MAX_WORLD_ITEMS) return { done: true, state: 'FAILED', reasonCode: 'WORLD_CAPACITY_EXCEEDED', changed: false };
				held.count -= args.count;
				if (held.count === 0) player.inventory.items = player.inventory.items.filter((item) => item !== held);
				const id = `drop-${agentId}-${this.#tickCount}-${this.#items.size + 1}`;
				this.#insertItem({ id, itemId: held.itemId, count: args.count, position: clone(player.position) });
				return { done: true, state: 'SUCCEEDED', reasonCode: 'DROPPED', changed: true };
			}
			default:
				return { done: true, state: 'FAILED', reasonCode: 'SIMULATOR_UNSUPPORTED_ACTION', changed: false };
		}
	}

	addItem(item) {
		const normalized = normalizeItem(item, this.#items.size + 1);
		this.#insertItem(normalized);
		return normalized.id;
	}

	addBlock(block) {
		const normalized = normalizeBlock(block);
		this.#insertBlock(normalized);
		return this;
	}

	addEntity(entity) {
		const normalized = normalizeEntity(entity, this.#entities.size);
		this.#insertEntity(normalized);
		return this;
	}

	#addPlayer(agentId, value = {}) {
		const id = identifier(agentId, 'agentId');
		if (this.#players.has(id)) throw new TypeError(`duplicate agent '${id}'`);
		const source = isRecord(value) ? value : {};
		const position = vector(source.position ?? source, 'agent.position', { x: 0, y: 64, z: 0 });
		const maxHealth = finitePositive(source.maxHealth ?? 20, 'agent.maxHealth');
		const inventory = normalizeInventory(source.inventory);
		this.#players.set(id, {
			id,
			goalRevision: nonnegativeInteger(source.goalRevision ?? 1, 'agent.goalRevision'),
			position,
			spawnPosition: clone(position),
			checkpoint: source.checkpoint === undefined || source.checkpoint === null ? null : vector(source.checkpoint, 'agent.checkpoint'),
			velocity: vector(source.velocity, 'agent.velocity', { x: 0, y: 0, z: 0 }),
			yaw: finite(source.yaw ?? 0, 'agent.yaw'),
			pitch: finite(source.pitch ?? 0, 'agent.pitch'),
			health: finiteNonnegative(source.health ?? maxHealth, 'agent.health'),
			maxHealth,
			armor: nonnegativeInteger(source.armor ?? 0, 'agent.armor'),
			foodLevel: nonnegativeInteger(source.foodLevel ?? 20, 'agent.foodLevel'),
			saturation: finiteNonnegative(source.saturation ?? 5, 'agent.saturation'),
			gameMode: identifier(source.gameMode ?? 'survival', 'agent.gameMode'),
			onGround: source.onGround === undefined ? false : source.onGround === true,
			inWater: false,
			onFire: source.onFire === true,
			air: nonnegativeInteger(source.air ?? 300, 'agent.air'),
			maxAir: nonnegativeInteger(source.maxAir ?? 300, 'agent.maxAir'),
			suffocating: false,
			fallDistance: finiteNonnegative(source.fallDistance ?? 0, 'agent.fallDistance'),
			effects: normalizeEffects(source.effects),
			lastAttacker: source.lastAttacker === undefined ? undefined : normalizeAttacker(source.lastAttacker),
			selectedItem: identifier(source.selectedItem ?? inventory.selectedItem, 'agent.selectedItem'),
			inventory,
			shieldActive: source.shieldActive === true,
			dead: source.dead === true || (source.health ?? maxHealth) <= 0,
			activeAction: null,
			lastResult: null,
		});
	}

	#applyRandomEvents() {
		for (const event of this.#randomEvents) {
			if (!isRecord(event) || event.tick !== this.#tickCount) continue;
			const type = event.type ?? event.kind;
			if (type === 'spawn_item') {
				const positions = normalizeArray(event.positions).map((position) => vector(position, 'random event position'));
				if (positions.length === 0) continue;
				const position = clone(this.#random.pick(positions));
				const id = identifier(event.id ?? `random-${this.#tickCount}-${this.#items.size + 1}`, 'random item id');
				this.#insertItem({
					id,
					itemId: identifier(event.itemId ?? 'minecraft:stone', 'random itemId'),
					count: positiveInteger(event.count ?? 1, 'random item count'),
					position,
				});
			} else if (type === 'damage') {
				const ids = normalizeArray(event.agentIds ?? this.agentIds);
				if (ids.length === 0) continue;
				this.damage(this.#random.pick(ids), event.amount ?? 1);
			}
		}
	}

	#applyInput(player, input) {
		if (!input) return;
		const yaw = player.yaw * Math.PI / 180;
		const forward = input.forward;
		const strafe = input.strafe;
		const magnitude = Math.hypot(forward, strafe);
		if (magnitude > 0) {
			const speed = input.sprint ? 0.22 : 0.14;
			const nx = (Math.sin(yaw) * forward + Math.cos(yaw) * strafe) / magnitude;
			const nz = (Math.cos(yaw) * forward - Math.sin(yaw) * strafe) / magnitude;
			player.velocity.x = nx * speed;
			player.velocity.z = nz * speed;
		}
		if (input.jump && player.onGround) player.velocity.y = 0.42;
		if (input.yaw !== undefined) player.yaw = input.yaw;
		if (input.pitch !== undefined) player.pitch = input.pitch;
	}

	#integratePlayer(player) {
		if (!player.onGround) player.velocity.y -= GRAVITY;
		const next = clone(player.position);
		let onGround = false;
		for (const axis of ['x', 'y', 'z']) {
			const delta = player.velocity[axis];
			if (delta === 0) continue;
			next[axis] += delta;
			for (const block of this.#blocks.values()) {
				if (!isSolid(block.blockId) || !playerIntersectsBlock(next, block)) continue;
				if (axis === 'x') next.x = delta > 0 ? block.x - PLAYER_HALF_WIDTH : block.x + 1 + PLAYER_HALF_WIDTH;
				if (axis === 'y') {
					if (delta > 0) next.y = block.y - PLAYER_HEIGHT;
					else { next.y = block.y + 1; onGround = true; }
				}
				if (axis === 'z') next.z = delta > 0 ? block.z - PLAYER_HALF_WIDTH : block.z + 1 + PLAYER_HALF_WIDTH;
				player.velocity[axis] = 0;
			}
		}
		player.position = next;
		player.onGround = onGround || this.#hasSupport(player);
		if (player.onGround && player.velocity.y < 0) player.velocity.y = 0;
		player.velocity.x *= AIR_DRAG;
		player.velocity.z *= AIR_DRAG;
		if (player.velocity.y !== 0) player.velocity.y *= 0.98;
	}

	#hasSupport(player) {
		const probe = clone(player.position);
		probe.y -= 0.01;
		for (const block of this.#blocks.values()) if (isSolid(block.blockId) && playerIntersectsBlock(probe, block)) return true;
		return false;
	}

	#isPositionClear(position) {
		for (const block of this.#blocks.values()) {
			if (isSolid(block.blockId) && playerIntersectsBlock(position, block)) return false;
		}
		return true;
	}

	#applyEnvironment(player) {
		player.inWater = this.#intersectsMaterial(player, 'minecraft:water');
		const inLava = this.#intersectsMaterial(player, 'minecraft:lava');
		if (inLava) {
			player.onFire = true;
			this.damage(player.id, this.#lavaDamagePerTick);
		} else if (!player.onFire) {
			player.onFire = false;
		}
	}

	#intersectsMaterial(player, blockId) {
		for (const block of this.#blocks.values()) {
			if (block.blockId !== blockId) continue;
			const horizontal = player.position.x + PLAYER_HALF_WIDTH > block.x && player.position.x - PLAYER_HALF_WIDTH < block.x + 1
				&& player.position.z + PLAYER_HALF_WIDTH > block.z && player.position.z - PLAYER_HALF_WIDTH < block.z + 1;
			const vertical = player.position.y + PLAYER_HEIGHT > block.y && player.position.y <= block.y + 1;
			if (horizontal && vertical) return true;
		}
		return false;
	}

	#pickupItems(player) {
		for (const [id, item] of this.#items) {
			if (distance(player.position, item.position) > this.#pickupRadius) continue;
			const existing = player.inventory.items.find((entry) => entry.itemId === item.itemId);
			if (existing) existing.count += item.count;
			else {
				if (player.inventory.items.length >= MAX_PLAYER_INVENTORY_ITEMS) continue;
				player.inventory.items.push({ itemId: item.itemId, count: item.count, damage: 0, maxDamage: 0, slot: nextInventorySlot(player.inventory.items) });
			}
			this.#items.delete(id);
		}
	}

	#kill(player) {
		player.health = 0;
		player.dead = true;
		player.velocity = { x: 0, y: 0, z: 0 };
		player.onGround = false;
		player.activeAction = null;
	}

	#insertBlock(block) {
		const key = blockKey(block.x, block.y, block.z);
		if (!this.#blocks.has(key) && this.#blocks.size >= MAX_WORLD_BLOCKS) throw capacityError('blocks', MAX_WORLD_BLOCKS);
		this.#blocks.set(key, block);
	}

	#insertItem(item) {
		if (!this.#items.has(item.id) && this.#items.size >= MAX_WORLD_ITEMS) throw capacityError('items', MAX_WORLD_ITEMS);
		this.#items.set(item.id, item);
	}

	#insertEntity(entity) {
		if (!this.#entities.has(entity.id) && this.#entities.size >= MAX_WORLD_ENTITIES) throw capacityError('entities', MAX_WORLD_ENTITIES);
		this.#entities.set(entity.id, entity);
	}

	#requirePlayer(agentId) {
		const player = this.#players.get(identifier(agentId, 'agentId'));
		if (!player) throw new RangeError(`unknown agent '${agentId}'`);
		return player;
	}

	#nextObservationSequence(agentId) {
		const next = (this.#observationSequences.get(agentId) ?? 0) + 1;
		this.#observationSequences.set(agentId, next);
		return next;
	}
}

function clone(value) { return value === undefined ? undefined : structuredClone(value); }
function cloneScenario(value) { try { return structuredClone(value); } catch (error) { throw new TypeError(`scenario must be cloneable: ${error.message}`); } }
function isRecord(value) { return value !== null && typeof value === 'object' && !Array.isArray(value); }
function identifier(value, field) { if (typeof value !== 'string' || value.length === 0 || value.length > 256) throw new TypeError(`${field} must be a non-empty string`); return value; }
function finite(value, field) { if (typeof value !== 'number' || !Number.isFinite(value)) throw new TypeError(`${field} must be finite`); return value; }
function finiteNonnegative(value, field) { value = finite(value, field); if (value < 0) throw new RangeError(`${field} must be nonnegative`); return value; }
function finitePositive(value, field) { value = finite(value, field); if (value <= 0) throw new RangeError(`${field} must be positive`); return value; }
function nonnegativeInteger(value, field) { if (!Number.isSafeInteger(value) || value < 0) throw new TypeError(`${field} must be a nonnegative integer`); return value; }
function positiveInteger(value, field) { if (!Number.isSafeInteger(value) || value <= 0) throw new TypeError(`${field} must be a positive integer`); return value; }
function vector(value, field, fallback = undefined) {
	const source = value === undefined || value === null ? fallback : value;
	if (!isRecord(source)) throw new TypeError(`${field} must be an object`);
	return { x: finite(source.x, `${field}.x`), y: finite(source.y, `${field}.y`), z: finite(source.z, `${field}.z`) };
}
function normalizeArray(value, field = 'array') {
	if (value === undefined || value === null) return [];
	if (!Array.isArray(value)) throw new TypeError(`${field} must be an array`);
	for (let index = 0; index < value.length; index += 1) if (!Object.hasOwn(value, index)) throw new TypeError(`${field} must not contain holes`);
	return value;
}
function entries(value, field) {
	if (value === undefined || value === null) return [];
	if (Array.isArray(value)) return value.map((entry, index) => [entry?.agentId ?? entry?.id ?? `agent-${index + 1}`, entry]);
	if (!isRecord(value)) throw new TypeError(`${field} must be an array or object`);
	return Object.entries(value);
}
function blockKey(x, y, z) { return `${x},${y},${z}`; }
function parsePosition(value, fallback = { x: 0, y: 0, z: 0 }) { return vector(value?.position ?? value, 'position', fallback); }
function normalizeBlock(value) {
	if (!isRecord(value)) throw new TypeError('block must be an object');
	const source = value;
	const position = parsePosition(source);
	return {
		x: Math.trunc(position.x),
		y: Math.trunc(position.y),
		z: Math.trunc(position.z),
		blockId: identifier(source.blockId ?? source.id ?? 'minecraft:stone', 'blockId'),
		...(source.desiredState === undefined || source.desiredState === null ? {} : { desiredState: desiredState(source.desiredState) }),
		...(source.tags === undefined ? {} : { tags: normalizeTags(source.tags, 'block.tags') }),
	};
}
function normalizeBlocks(value) {
	if (value === undefined || value === null) return [];
	if (Array.isArray(value)) return value.map(normalizeBlock);
	if (isRecord(value)) return Object.entries(value).map(([key, block]) => {
		const parts = key.split(',').map(Number);
		return normalizeBlock({ ...(isRecord(block) ? block : { blockId: block }), x: parts[0], y: parts[1], z: parts[2] });
	});
	throw new TypeError('blocks must be an array or object');
}
function normalizeEntity(value, index) {
	if (!isRecord(value)) throw new TypeError('entity must be an object');
	const source = value;
	if (source.isPlayer !== undefined && typeof source.isPlayer !== 'boolean') throw new TypeError('entity.isPlayer must be boolean');
	const maxHealth = finitePositive(source.maxHealth ?? 20, 'entity.maxHealth');
	const health = finiteNonnegative(source.health ?? maxHealth, 'entity.health');
	return {
		id: identifier(source.id ?? source.uuid ?? `entity-${index + 1}`, 'entity.id'),
		type: identifier(source.type ?? 'minecraft:zombie', 'entity.type'),
		name: identifier(source.name ?? source.type ?? 'entity', 'entity.name'),
		position: parsePosition(source),
		health,
		maxHealth,
		dead: source.dead === true || health <= 0,
		cooldownUntilTick: nonnegativeInteger(source.cooldownUntilTick ?? 0, 'entity.cooldownUntilTick'),
		...(source.isPlayer === undefined ? {} : { isPlayer: source.isPlayer }),
		...(source.tags === undefined ? {} : { tags: normalizeTags(source.tags, 'entity.tags') }),
	};
}
function normalizeEntities(value) { return normalizeArray(value, 'entities').map(normalizeEntity); }
function normalizeItem(value, index) {
	if (!isRecord(value)) throw new TypeError('item must be an object');
	const source = value;
	return { id: identifier(source.id ?? source.uuid ?? source.stableId ?? `item-${index}`, 'item.id'), itemId: identifier(source.itemId ?? 'minecraft:stone', 'item.itemId'), count: positiveInteger(source.count ?? 1, 'item.count'), position: parsePosition(source) };
}
function normalizeItems(value) { return normalizeArray(value, 'items').map((item, index) => normalizeItem(item, index + 1)); }
function normalizeInventory(value) {
	if (value !== undefined && value !== null && !isRecord(value)) throw new TypeError('inventory must be an object');
	const source = value ?? {};
	const rawItems = normalizeArray(source.items, 'inventory.items');
	if (rawItems.length > MAX_PLAYER_INVENTORY_ITEMS) throw capacityError('inventory items', MAX_PLAYER_INVENTORY_ITEMS);
	const items = rawItems.map((item, index) => {
		if (!isRecord(item)) throw new TypeError(`inventory.items[${index}] must be an object`);
		const entry = item;
		const slot = entry.slot === undefined ? index : nonnegativeInteger(entry.slot, `inventory.items[${index}].slot`);
		return { itemId: identifier(entry.itemId ?? 'minecraft:air', 'inventory.itemId'), count: nonnegativeInteger(entry.count ?? 0, 'inventory.count'), damage: nonnegativeInteger(entry.damage ?? 0, 'inventory.damage'), maxDamage: nonnegativeInteger(entry.maxDamage ?? 0, 'inventory.maxDamage'), slot, ...(entry.tags === undefined ? {} : { tags: normalizeTags(entry.tags, `inventory.items[${index}].tags`) }) };
	});
	return { items, selectedItem: identifier(source.selectedItem ?? items[0]?.itemId ?? 'minecraft:air', 'inventory.selectedItem'), tagCounts: normalizeTagCounts(source.tagCounts) };
}
function normalizeEffects(value) {
	const effects = normalizeArray(value, 'effects');
	if (effects.length > MAX_EFFECTS) throw capacityError('effects', MAX_EFFECTS);
	return effects.map((effect, index) => {
		if (!isRecord(effect)) throw new TypeError(`effects[${index}] must be an object`);
		return {
			effectId: identifier(effect.effectId, `effects[${index}].effectId`),
			amplifier: nonnegativeInteger(effect.amplifier, `effects[${index}].amplifier`),
			duration: nonnegativeInteger(effect.duration ?? effect.durationTicks, `effects[${index}].duration`),
		};
	});
}
function normalizeAttacker(value) {
	if (!isRecord(value)) throw new TypeError('lastAttacker must be an object');
	return {
		uuid: identifier(value.uuid, 'lastAttacker.uuid'),
		type: identifier(value.type, 'lastAttacker.type'),
		distance: finiteNonnegative(value.distance, 'lastAttacker.distance'),
	};
}
function normalizeLastResult(value) {
	if (value === null || value === undefined) return null;
	if (!isRecord(value)) throw new TypeError('lastResult must be an object');
	if (value.present === false) return null;
	if (value.present !== true) throw new TypeError('lastResult.present must be boolean');
	return {
		present: true,
		actionId: identifier(value.actionId, 'lastResult.actionId'),
		actionType: identifier(value.actionType, 'lastResult.actionType'),
		state: identifier(value.state, 'lastResult.state'),
		reasonCode: identifier(value.reasonCode, 'lastResult.reasonCode'),
		message: typeof value.message === 'string' ? value.message.slice(0, 2_048) : (() => { throw new TypeError('lastResult.message must be a string'); })(),
	};
}
function normalizeTags(value, field) {
	const tags = normalizeArray(value, field);
	if (tags.length > MAX_OBSERVATION_TAGS) throw capacityError(field, MAX_OBSERVATION_TAGS);
	const normalized = tags.map((tag, index) => {
		if (typeof tag !== 'string' || !tag.startsWith('#') || tag.length < 2 || tag.length > 256) throw new TypeError(`${field}[${index}] must be a tag identifier`);
		return tag;
	});
	if (new Set(normalized).size !== normalized.length) throw new TypeError(`${field} must contain unique tags`);
	return normalized;
}
function normalizeTagCounts(value) {
	if (value === undefined || value === null) return {};
	if (!isRecord(value)) throw new TypeError('inventory.tagCounts must be an object');
	const keys = Object.keys(value);
	if (keys.length > MAX_TAG_COUNT_ENTRIES) throw capacityError('inventory.tagCounts', MAX_TAG_COUNT_ENTRIES);
	const normalized = {};
	for (const key of keys) {
		if (!key.startsWith('#') || key.length < 2 || key.length > 256) throw new TypeError('inventory.tagCounts keys must be tag identifiers');
		normalized[key] = nonnegativeInteger(value[key], `inventory.tagCounts.${key}`);
	}
	return normalized;
}
function capacityError(resource, maximum) {
	return Object.assign(new RangeError(`${resource} capacity ${maximum} exceeded`), { code: 'WORLD_CAPACITY_EXCEEDED', resource, maximum });
}
function nextInventorySlot(items) { const used = new Set(items.map((item) => item.slot)); for (let slot = 0; slot < 36; slot += 1) if (!used.has(slot)) return slot; return 35; }
function normalizeInput(value) { const source = isRecord(value) ? value : {}; return { forward: finite(source.forward ?? 0, 'input.forward'), strafe: finite(source.strafe ?? 0, 'input.strafe'), jump: source.jump === true, sprint: source.sprint === true, yaw: source.yaw === undefined ? undefined : finite(source.yaw, 'input.yaw'), pitch: source.pitch === undefined ? undefined : finite(source.pitch, 'input.pitch') }; }
function normalizeAction(action) {
	const source = isRecord(action) ? action : {};
	const type = identifier(source.type ?? source.actionType, 'action.type');
	const args = isRecord(source.arguments) ? source.arguments : source;
	return { type, arguments: args };
}
function distance(left, right) { return Math.hypot(left.x - right.x, left.y - right.y, left.z - right.z); }
function approachAngle(current, target, maximumDelta) {
	let delta = ((target - current + 540) % 360) - 180;
	if (delta > maximumDelta) delta = maximumDelta;
	if (delta < -maximumDelta) delta = -maximumDelta;
	return current + delta;
}
function miningDurationTicks(blockId) {
	if (blockId.includes('obsidian')) return 40;
	if (blockId.includes('stone') || blockId.includes('ore')) return 5;
	if (blockId.includes('log') || blockId.includes('wood')) return 4;
	return 3;
}
function blockDrop(blockId) {
	if (blockId === 'minecraft:stone') return 'minecraft:cobblestone';
	if (blockId === 'minecraft:coal_ore') return 'minecraft:coal';
	return blockId;
}
function placementSupport(x, y, z, face) {
	const offsets = {
		down: { x: 0, y: 1, z: 0 },
		up: { x: 0, y: -1, z: 0 },
		north: { x: 0, y: 0, z: 1 },
		south: { x: 0, y: 0, z: -1 },
		west: { x: 1, y: 0, z: 0 },
		east: { x: -1, y: 0, z: 0 },
	};
	const offset = offsets[face];
	return offset === undefined ? null : { x: Math.trunc(x) + offset.x, y: Math.trunc(y) + offset.y, z: Math.trunc(z) + offset.z };
}
function desiredState(value) {
	if (typeof value !== 'string' || value.length === 0) throw new TypeError('block.desiredState must be a non-empty string');
	if ([...value].length > MAX_DESIRED_STATE_LENGTH) throw capacityError('block.desiredState', MAX_DESIRED_STATE_LENGTH);
	return value;
}
function conversationText(value) {
	if (typeof value !== 'string' || value.length === 0) throw new TypeError('message must be a non-empty string');
	if ([...value].length > MAX_CONVERSATION_LENGTH) throw Object.assign(new RangeError(`MESSAGE_TOO_LARGE: message exceeds ${MAX_CONVERSATION_LENGTH} code points`), { code: 'MESSAGE_TOO_LARGE' });
	return value;
}
function craftRecipe(recipeId, count) {
	const recipes = {
		'minecraft:planks': { output: 'minecraft:oak_planks', inputs: { 'minecraft:oak_log': Math.ceil(count / 4) } },
		'minecraft:oak_planks': { output: 'minecraft:oak_planks', inputs: { 'minecraft:oak_log': Math.ceil(count / 4) } },
		'minecraft:sticks': { output: 'minecraft:stick', inputs: { 'minecraft:oak_planks': Math.ceil(count / 4) * 2 } },
		'minecraft:crafting_table': { output: 'minecraft:crafting_table', inputs: { 'minecraft:oak_planks': count * 4 } },
		'minecraft:stone_pickaxe': { output: 'minecraft:stone_pickaxe', inputs: { 'minecraft:cobblestone': count * 3, 'minecraft:stick': count * 2 } },
		'minecraft:stone_axe': { output: 'minecraft:stone_axe', inputs: { 'minecraft:cobblestone': count * 3, 'minecraft:stick': count * 2 } },
		'minecraft:stone_sword': { output: 'minecraft:stone_sword', inputs: { 'minecraft:cobblestone': count * 2, 'minecraft:stick': count } },
	};
	return recipes[recipeId] ?? null;
}
function isSolid(blockId) { return typeof blockId === 'string' && !SOLID_EXCEPTIONS.has(blockId); }
function playerIntersectsBlock(position, block) {
	return position.x + PLAYER_HALF_WIDTH > block.x && position.x - PLAYER_HALF_WIDTH < block.x + 1
		&& position.y + PLAYER_HEIGHT > block.y && position.y < block.y + 1
		&& position.z + PLAYER_HALF_WIDTH > block.z && position.z - PLAYER_HALF_WIDTH < block.z + 1;
}
