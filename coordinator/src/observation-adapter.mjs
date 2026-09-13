import { types as nodeTypes } from 'node:util';

import { MAX_BLOCKS, MAX_EFFECTS, MAX_ENTITIES, MAX_INVENTORY_SUMMARIES, MAX_LANDMARKS, MAX_OBSERVATION_TAGS, MAX_TAG_COUNT_ENTRIES } from './constants.mjs';

const FORBIDDEN_KEYS = new Set(['__proto__', 'constructor', 'prototype']);
const COORDINATE_FIELDS = ['x', 'y', 'z'];

/** Converts one validated protocol-v2 observation into the narrow ArenaScript fact shape. */
export function adaptObservation(value) {
	const source = ownDataRecord(value, 'wire observation');
	if (source.ready === false) return { ...emptyFacts(source.status === 'PLAYER_DEAD'), ready: false, status: optionalIdentifier(source.status, 'wire observation.status') ?? 'unavailable' };
	if (source.ready !== true) throw new TypeError('wire observation.ready must be boolean');

	const position = vector(source.position, 'wire observation.position');
	const view = vector(source.view, 'wire observation.view', ['yaw', 'pitch']);
	const playerSource = ownDataRecord(source.player, 'wire observation.player');
	const player = {
		x: position.x,
		y: position.y,
		z: position.z,
		yaw: view.yaw,
		pitch: view.pitch,
		dead: false,
	};
	copyNumber(playerSource, player, 'health');
	copyNumber(playerSource, player, 'maxHealth');
	copyNumber(playerSource, player, 'foodLevel', 'hunger');
	copyNumber(playerSource, player, 'foodLevel');
	copyNumber(playerSource, player, 'armor');
	copyNumber(playerSource, player, 'saturation');
	copyNumber(playerSource, player, 'air');
	copyNumber(playerSource, player, 'maxAir');
	copyBoolean(playerSource, player, 'onFire', 'fire');
	copyBoolean(playerSource, player, 'onFire');
	copyBoolean(playerSource, player, 'onGround');
	copyBoolean(playerSource, player, 'inWater');
	copyBoolean(playerSource, player, 'suffocating');
	copyNumber(playerSource, player, 'fallDistance');
	if (Object.hasOwn(playerSource, 'gameMode')) player.gameMode = identifier(playerSource.gameMode, 'player.gameMode');
	if (Object.hasOwn(playerSource, 'effects')) player.effects = effectFacts(playerSource.effects);
	if (Object.hasOwn(playerSource, 'lastAttacker')) player.lastAttacker = attackerFacts(playerSource.lastAttacker);
	for (const field of ['swimming', 'gliding', 'sprinting', 'crouching', 'onClimbable', 'inLava', 'horizontalCollision', 'verticalCollision', 'passenger']) copyBoolean(playerSource, player, field);
	copyExtensions(playerSource, player, ['pose', 'vehicle']);

	const entities = boundedDataArray(source.entities, 'entities', MAX_ENTITIES)
		.map((value, index) => entityFacts(value, index));
	const entityIds = new Set();
	for (const entity of entities) {
		if (entityIds.has(entity.stableId)) throw new TypeError(`duplicate entity identity '${entity.stableId}'`);
		entityIds.add(entity.stableId);
	}
	const items = entities
		.filter((entity) => entity.type === 'minecraft:item')
		.map(({ stableId, itemId, count, distance, tags, x, y, z }) => ({ stableId, itemId, count, ...(distance === undefined ? {} : { distance }), ...(tags === undefined ? {} : { tags }), x, y, z }));

	const blocks = boundedDataArray(source.blocks, 'blocks', MAX_BLOCKS)
		.map((value, index) => blockFacts(value, index));
	const blockIds = new Set();
	for (const block of blocks) {
		if (blockIds.has(block.stableId)) throw new TypeError(`duplicate block identity '${block.stableId}'`);
		blockIds.add(block.stableId);
	}
	const landmarks = Object.hasOwn(source, 'landmarks')
		? boundedDataArray(source.landmarks, 'landmarks', MAX_LANDMARKS).map((value, index) => landmarkFacts(value, index))
		: undefined;

	const inventorySource = ownDataRecord(source.inventory, 'wire observation.inventory');
	const inventoryItems = boundedDataArray(inventorySource.items, 'inventory.items', MAX_INVENTORY_SUMMARIES)
		.map((value, index) => inventoryFacts(value, index));

	const tagCounts = tagCountsFacts(inventorySource.tagCounts);
	return {
		ready: true,
		...(Object.hasOwn(source, 'observedAtEpochMs') ? { observedAtEpochMs: nonNegativeInteger(source.observedAtEpochMs, 'observedAtEpochMs') } : {}),
		...(Object.hasOwn(source, 'coverage') ? { coverage: extensionValue(source.coverage, 'coverage') } : {}),
		...(Object.hasOwn(source, 'perception') ? { perception: extensionValue(source.perception, 'perception') } : {}),
		...(Object.hasOwn(source, 'status') ? { status: identifier(source.status, 'wire observation.status') } : {}),
		...(Object.hasOwn(source, 'velocity') ? { velocity: vector(source.velocity, 'wire observation.velocity') } : {}),
		player,
		items,
		entities,
		blocks,
		...(landmarks === undefined ? {} : { landmarks }),
		inventory: {
			items: inventoryItems,
			...(Object.hasOwn(inventorySource, 'selectedItem') ? { selectedItem: identifier(inventorySource.selectedItem, 'inventory.selectedItem') } : {}),
			...(tagCounts === undefined ? {} : { tagCounts }),
		},
		...(Object.hasOwn(source, 'nearbyContainers') ? { nearbyContainers: nearbyContainerFacts(source.nearbyContainers) } : {}),
		...(Object.hasOwn(source, 'world') ? { world: worldFacts(source.world) } : {}),
		...(Object.hasOwn(source, 'currentAction') ? { currentAction: currentActionFacts(source.currentAction) } : {}),
		...(Object.hasOwn(source, 'lastResult') ? { lastResult: lastResultFacts(source.lastResult) } : {}),
		...(Object.hasOwn(source, 'interaction') ? { interaction: interactionFacts(source.interaction) } : {}),
	};
}

/** Alias kept explicit for callers that want to document the protocol boundary. */
export const adaptWireObservation = adaptObservation;

function emptyFacts(dead = false) {
	return { player: { dead }, items: [], entities: [], blocks: [], inventory: { items: [] } };
}

function entityFacts(value, index) {
	const source = ownDataRecord(value, `entities[${index}]`);
	const stableId = immutableIdentity(source, `entities[${index}]`);
	const point = coordinateSource(source, `entities[${index}]`);
	const type = identifier(source.type, `entities[${index}].type`);
	const result = { stableId, type, x: point.x, y: point.y, z: point.z };
	if (Object.hasOwn(source, 'name')) result.name = boundedText(source.name, `entities[${index}].name`, 256, true);
	if (Object.hasOwn(source, 'isPlayer')) result.isPlayer = boolean(source.isPlayer, `entities[${index}].isPlayer`);
	if (Object.hasOwn(source, 'distance')) result.distance = finiteNumber(source.distance, `entities[${index}].distance`);
	if (Object.hasOwn(source, 'tags')) result.tags = tags(source.tags, `entities[${index}].tags`);
	if (type === 'minecraft:item') {
		result.itemId = identifier(source.itemId, `entities[${index}].itemId`);
		result.count = positiveInteger(source.count, `entities[${index}].count`);
	}
	copyExtensions(source, result, ['velocity', 'yaw', 'pitch', 'pose', 'bounds', 'equipment', 'usingItem', 'onFire']);
	return result;
}

function blockFacts(value, index) {
	const source = ownDataRecord(value, `blocks[${index}]`);
	const point = coordinateSource(source, `blocks[${index}]`);
	const stableId = `${point.x},${point.y},${point.z}`;
	const result = {
		stableId,
		blockId: identifier(source.blockId, `blocks[${index}].blockId`),
		...(Object.hasOwn(source, 'placeableFaces') ? { placeableFaces: identifierList(source.placeableFaces, `blocks[${index}].placeableFaces`, 6) } : {}),
		...(Object.hasOwn(source, 'tags') ? { tags: tags(source.tags, `blocks[${index}].tags`) } : {}),
		x: point.x, y: point.y, z: point.z,
	};
	copyExtensions(source, result, ['state', 'bounds', 'boundsTruncated', 'replaceable', 'fluid', 'text']);
	return result;
}

function landmarkFacts(value, index) {
	const source = ownDataRecord(value, `landmarks[${index}]`);
	const point = coordinateSource(source, `landmarks[${index}]`);
	return {
		stableId: `${point.x},${point.y},${point.z}`,
		blockId: identifier(source.blockId, `landmarks[${index}].blockId`),
		distance: nonNegativeNumber(source.distance, `landmarks[${index}].distance`),
		bearing: boundedNumber(source.bearing, `landmarks[${index}].bearing`, -180, 180),
		elevation: boundedNumber(source.elevation, `landmarks[${index}].elevation`, -90, 90),
		...(Object.hasOwn(source, 'tags') ? { tags: tags(source.tags, `landmarks[${index}].tags`) } : {}),
		x: point.x, y: point.y, z: point.z,
	};
}

function inventoryFacts(value, index) {
	const source = ownDataRecord(value, `inventory.items[${index}]`);
	const slot = source.slot;
	if (!(Number.isSafeInteger(slot) && slot >= 0) && typeof slot !== 'string') throw new TypeError(`inventory.items[${index}].slot must be a slot identifier`);
	const result = {
		itemId: identifier(source.itemId, `inventory.items[${index}].itemId`),
		count: nonNegativeInteger(source.count, `inventory.items[${index}].count`),
		slot,
		...(Object.hasOwn(source, 'tags') ? { tags: tags(source.tags, `inventory.items[${index}].tags`) } : {}),
	};
	if (Object.hasOwn(source, 'damage')) result.damage = nonNegativeInteger(source.damage, `inventory.items[${index}].damage`);
	if (Object.hasOwn(source, 'maxDamage')) result.maxDamage = nonNegativeInteger(source.maxDamage, `inventory.items[${index}].maxDamage`);
	if (Object.hasOwn(source, 'hotbar')) result.hotbar = boolean(source.hotbar, `inventory.items[${index}].hotbar`);
	copyExtensions(source, result, ['displayName', 'fingerprint', 'maxStackSize', 'tooltip', 'tooltipTruncated']);
	return result;
}

function effectFacts(value) {
	return boundedDataArray(value, 'player.effects', MAX_EFFECTS).map((entry, index) => {
		const source = ownDataRecord(entry, `player.effects[${index}]`);
		return {
			effectId: identifier(source.effectId, `player.effects[${index}].effectId`),
			amplifier: nonNegativeInteger(source.amplifier, `player.effects[${index}].amplifier`),
			duration: nonNegativeInteger(source.duration, `player.effects[${index}].duration`),
		};
	});
}

function nearbyContainerFacts(value) {
	return boundedDataArray(value, 'nearbyContainers', 16).map((entry, index) => {
		const source = ownDataRecord(entry, `nearbyContainers[${index}]`);
		return {
			x: integer(source.x, `nearbyContainers[${index}].x`),
			y: integer(source.y, `nearbyContainers[${index}].y`),
			z: integer(source.z, `nearbyContainers[${index}].z`),
			blockId: identifier(source.blockId, `nearbyContainers[${index}].blockId`),
			distance: finiteNumber(source.distance, `nearbyContainers[${index}].distance`),
			withinInteractionRange: boolean(source.withinInteractionRange, `nearbyContainers[${index}].withinInteractionRange`),
			capabilities: identifierList(source.capabilities, `nearbyContainers[${index}].capabilities`, 32),
		};
	});
}

function worldFacts(value) {
	const source = ownDataRecord(value, 'wire observation.world');
	return {
		dimension: identifier(source.dimension, 'world.dimension'),
		...(Object.hasOwn(source, 'worldId') ? { worldId: identifier(source.worldId, 'world.worldId') } : {}),
		gameTime: nonNegativeInteger(source.gameTime, 'world.gameTime'),
		dayTime: nonNegativeInteger(source.dayTime, 'world.dayTime'),
		raining: boolean(source.raining, 'world.raining'),
		thundering: boolean(source.thundering, 'world.thundering'),
	};
}

function currentActionFacts(value) {
	const source = ownDataRecord(value, 'wire observation.currentAction');
	const active = boolean(source.active, 'currentAction.active');
	return active ? {
		active,
		actionId: identifier(source.actionId, 'currentAction.actionId'),
		actionType: identifier(source.actionType, 'currentAction.actionType'),
	} : { active };
}

function lastResultFacts(value) {
	const source = ownDataRecord(value, 'wire observation.lastResult');
	const present = boolean(source.present, 'lastResult.present');
	return present ? {
		present,
		actionId: identifier(source.actionId, 'lastResult.actionId'),
		actionType: identifier(source.actionType, 'lastResult.actionType'),
		state: identifier(source.state, 'lastResult.state'),
		reasonCode: identifier(source.reasonCode, 'lastResult.reasonCode'),
		message: boundedText(source.message, 'lastResult.message', 2_048, true),
	} : { present };
}

function interactionFacts(value) {
	const source = ownDataRecord(value, 'wire observation.interaction');
	const input = ownDataRecord(source.input, 'interaction.input');
	const menu = ownDataRecord(source.menu, 'interaction.menu');
	const cursor = ownDataRecord(menu.cursor, 'interaction.menu.cursor');
	const ray = ownDataRecord(source.rayTarget, 'interaction.rayTarget');
	return {
		mainHandItemId: identifier(source.mainHandItemId, 'interaction.mainHandItemId'),
		offHandItemId: identifier(source.offHandItemId, 'interaction.offHandItemId'),
		usingItem: boolean(source.usingItem, 'interaction.usingItem'),
		activeHand: identifier(source.activeHand, 'interaction.activeHand'),
		useRemainingTicks: nonNegativeInteger(source.useRemainingTicks, 'interaction.useRemainingTicks'),
		attackCooldown: finiteNumber(source.attackCooldown, 'interaction.attackCooldown'),
		input: {
			active: boolean(input.active, 'interaction.input.active'),
			forward: finiteNumber(input.forward, 'interaction.input.forward'),
			strafe: finiteNumber(input.strafe, 'interaction.input.strafe'),
			jump: boolean(input.jump, 'interaction.input.jump'),
			sneak: boolean(input.sneak, 'interaction.input.sneak'),
			sprint: boolean(input.sprint, 'interaction.input.sprint'),
			attack: boolean(input.attack, 'interaction.input.attack'),
			use: boolean(input.use, 'interaction.input.use'),
			yaw: finiteNumber(input.yaw, 'interaction.input.yaw'),
			pitch: finiteNumber(input.pitch, 'interaction.input.pitch'),
			selectedSlot: nonNegativeInteger(input.selectedSlot, 'interaction.input.selectedSlot'),
			hand: identifier(input.hand, 'interaction.input.hand'),
		},
		menu: {
			type: identifier(menu.type, 'interaction.menu.type'),
			cursor: stackDetails(cursor, 'interaction.menu.cursor'),
			slots: boundedDataArray(menu.slots, 'interaction.menu.slots', 64).map((entry, index) => {
				const slot = ownDataRecord(entry, `interaction.menu.slots[${index}]`);
				return { slot: nonNegativeInteger(slot.slot, `interaction.menu.slots[${index}].slot`), ...stackDetails(slot, `interaction.menu.slots[${index}]`) };
			}),
			capabilities: identifierList(menu.capabilities, 'interaction.menu.capabilities', 8),
			...extensionFields(menu, ['containerId', 'stateId', 'slotCount', 'offset', 'hasMore', 'details']),
		},
		rayTarget: ray.type === 'block' ? {
			type: 'block', x: integer(ray.x, 'interaction.rayTarget.x'), y: integer(ray.y, 'interaction.rayTarget.y'), z: integer(ray.z, 'interaction.rayTarget.z'),
			face: identifier(ray.face, 'interaction.rayTarget.face'), blockId: identifier(ray.blockId, 'interaction.rayTarget.blockId'),
		} : { type: identifier(ray.type, 'interaction.rayTarget.type') },
	};
}

function stackDetails(source, label) {
	return {
		itemId: identifier(source.itemId, `${label}.itemId`),
		count: nonNegativeInteger(source.count, `${label}.count`),
		...extensionFields(source, ['displayName', 'fingerprint', 'damage', 'maxDamage', 'maxStackSize', 'tooltip', 'tooltipTruncated', 'x', 'y', 'pickupAllowed', 'slotLimit']),
	};
}

function extensionFields(source, fields) {
	const result = {};
	copyExtensions(source, result, fields);
	return result;
}

function copyExtensions(source, target, fields) {
	for (const field of fields) if (Object.hasOwn(source, field)) target[field] = extensionValue(source[field], field);
}

/** Preserve bounded protocol extension data without invoking accessors or accepting executable values. */
function extensionValue(value, label, depth = 0, budget = { bytes: 0 }) {
	if (depth > 10) throw new TypeError(`${label} exceeds nesting bound`);
	if (value === null || typeof value === 'boolean') return value;
	if (typeof value === 'number') return finiteNumber(value, label);
	if (typeof value === 'string') {
		budget.bytes += Buffer.byteLength(value, 'utf8');
		if (value.length > 8_192 || budget.bytes > 32_768) throw new TypeError(`${label} exceeds text bound`);
		return value;
	}
	if (Array.isArray(value)) return boundedDataArray(value, label, 256)
		.map((entry, index) => extensionValue(entry, `${label}[${index}]`, depth + 1, budget));
	const record = ownDataRecord(value, label);
	const keys = Object.keys(record);
	if (keys.length > 128) throw new TypeError(`${label} exceeds field bound`);
	return Object.fromEntries(keys.map((key) => [key, extensionValue(record[key], `${label}.${key}`, depth + 1, budget)]));
}

function tags(value, label) {
	const values = boundedDataArray(value, label, MAX_OBSERVATION_TAGS).map((tag, index) => {
		if (typeof tag !== 'string' || !tag.startsWith('#') || tag.length < 2 || tag.length > 256) throw new TypeError(`${label}[${index}] must be a tag identifier`);
		return tag;
	});
	if (new Set(values).size !== values.length) throw new TypeError(`${label} must be unique`);
	return values;
}

function identifierList(value, label, maximum) {
	const values = boundedDataArray(value, label, maximum).map((entry, index) => identifier(entry, `${label}[${index}]`));
	if (new Set(values).size !== values.length) throw new TypeError(`${label} must be unique`);
	return values;
}

function tagCountsFacts(value) {
	if (value === undefined) return undefined;
	const source = ownDataRecord(value, 'wire observation.inventory.tagCounts');
	const keys = Object.keys(source);
	if (keys.length > MAX_TAG_COUNT_ENTRIES) throw new TypeError(`tagCounts exceeds bound of ${MAX_TAG_COUNT_ENTRIES}`);
	const result = {};
	for (const key of keys) {
		if (!key.startsWith('#') || key.length < 2 || key.length > 256) throw new TypeError(`tagCounts key must be a tag identifier`);
		result[key] = nonNegativeInteger(source[key], `tagCounts.${key}`);
	}
	return result;
}

function coordinateSource(source, label) {
	if (Object.hasOwn(source, 'position')) return vector(source.position, `${label}.position`);
	const point = {};
	for (const field of COORDINATE_FIELDS) point[field] = finiteNumber(source[field], `${label}.${field}`);
	return point;
}

function vector(value, label, fields = COORDINATE_FIELDS) {
	const source = ownDataRecord(value, label);
	const result = {};
	for (const field of fields) result[field] = finiteNumber(source[field], `${label}.${field}`);
	return result;
}

function immutableIdentity(source, label) {
	const field = Object.hasOwn(source, 'uuid') ? 'uuid' : Object.hasOwn(source, 'id') ? 'id' : null;
	if (field === null) throw new TypeError(`${label} requires an immutable uuid or id`);
	return identifier(source[field], `${label}.${field}`);
}

function copyNumber(source, target, sourceField, targetField = sourceField) {
	if (Object.hasOwn(source, sourceField)) target[targetField] = finiteNumber(source[sourceField], `player.${sourceField}`);
}

function copyBoolean(source, target, sourceField, targetField = sourceField) {
	if (Object.hasOwn(source, sourceField)) {
		if (typeof source[sourceField] !== 'boolean') throw new TypeError(`player.${sourceField} must be boolean`);
		target[targetField] = source[sourceField];
	}
}

function attackerFacts(value) {
	const source = ownDataRecord(value, 'wire observation.player.lastAttacker');
	if (Reflect.ownKeys(source).some((key) => !['uuid', 'type', 'distance'].includes(key))) {
		throw new TypeError('wire observation.player.lastAttacker has an invalid schema');
	}
	return {
		uuid: identifier(source.uuid, 'player.lastAttacker.uuid'),
		type: identifier(source.type, 'player.lastAttacker.type'),
		distance: finiteNumber(source.distance, 'player.lastAttacker.distance'),
	};
}

function identifier(value, label) {
	if (typeof value !== 'string' || value.length === 0 || value.length > 256) throw new TypeError(`${label} must be a nonblank identifier`);
	return value;
}

function optionalIdentifier(value, label) {
	return value === undefined ? undefined : identifier(value, label);
}

function boundedText(value, label, maximumCodePoints, allowEmpty = false) {
	if (typeof value !== 'string' || (!allowEmpty && value.length === 0) || [...value].length > maximumCodePoints) {
		throw new TypeError(`${label} must be bounded text`);
	}
	return value;
}

function boolean(value, label) {
	if (typeof value !== 'boolean') throw new TypeError(`${label} must be boolean`);
	return value;
}

function finiteNumber(value, label) {
	if (typeof value !== 'number' || !Number.isFinite(value)) throw new TypeError(`${label} must be a finite number`);
	return value;
}

function nonNegativeNumber(value, label) {
	const number = finiteNumber(value, label);
	if (number < 0) throw new TypeError(`${label} must be non-negative`);
	return number;
}

function boundedNumber(value, label, minimum, maximum) {
	const number = finiteNumber(value, label);
	if (number < minimum || number > maximum) throw new TypeError(`${label} must be between ${minimum} and ${maximum}`);
	return number;
}

function positiveInteger(value, label) {
	if (!Number.isSafeInteger(value) || value < 1) throw new TypeError(`${label} must be a positive integer`);
	return value;
}

function integer(value, label) {
	if (!Number.isSafeInteger(value)) throw new TypeError(`${label} must be a safe integer`);
	return value;
}

function nonNegativeInteger(value, label) {
	if (!Number.isSafeInteger(value) || value < 0) throw new TypeError(`${label} must be a non-negative integer`);
	return value;
}

function boundedDataArray(value, label, maximum) {
	if (!Array.isArray(value) || nodeTypes.isProxy(value) || Object.getPrototypeOf(value) !== Array.prototype) throw new TypeError(`${label} must be a plain array`);
	const descriptors = Object.getOwnPropertyDescriptors(value);
	const keys = Reflect.ownKeys(value);
	if (keys.some((key) => typeof key === 'symbol' || (key !== 'length' && !/^(0|[1-9]\d*)$/.test(key)))) throw new TypeError(`${label} has unsafe keys`);
	const length = descriptors.length;
	if (!length || length.get || length.set || !Number.isSafeInteger(length.value)) throw new TypeError(`${label} has invalid length`);
	if (length.value > maximum) throw new TypeError(`${label} exceeds bound of ${maximum}`);
	const copied = [];
	for (let index = 0; index < length.value; index += 1) {
		const descriptor = descriptors[String(index)];
		if (!descriptor || !descriptor.enumerable || !Object.hasOwn(descriptor, 'value') || descriptor.get || descriptor.set) throw new TypeError(`${label} must be dense own data`);
		copied.push(descriptor.value);
	}
	if (keys.length !== length.value + 1) throw new TypeError(`${label} must not have holes or custom keys`);
	return copied;
}

function ownDataRecord(value, label) {
	if (value === null || typeof value !== 'object' || Array.isArray(value) || nodeTypes.isProxy(value)) throw new TypeError(`${label} must be a plain data record`);
	const prototype = Object.getPrototypeOf(value);
	if (prototype !== null && prototype !== Object.prototype) throw new TypeError(`${label} must be a plain data record`);
	const record = Object.create(null);
	for (const key of Reflect.ownKeys(value)) {
		if (typeof key !== 'string' || FORBIDDEN_KEYS.has(key)) throw new TypeError(`${label} contains an unsafe key`);
		const descriptor = Object.getOwnPropertyDescriptor(value, key);
		if (!descriptor || !descriptor.enumerable || !Object.hasOwn(descriptor, 'value') || descriptor.get || descriptor.set) throw new TypeError(`${label}.${key} must be own data`);
		record[key] = descriptor.value;
	}
	return record;
}
