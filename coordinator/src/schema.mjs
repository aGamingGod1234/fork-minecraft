import {
	ACTION_FIELDS,
	OPTIONAL_ACTION_FIELDS,
	CONTROL_BRANCH_CONDITIONS,
	CONTROL_THRESHOLD_MAXIMA,
	BLOCK_FACES,
	MAX_BLOCKS,
	MAX_CONVERSATION_LENGTH,
	MAX_COMMAND_ID_LENGTH,
	MAX_DESIRED_STATE_LENGTH,
	MAX_DURATION_MS,
	MAX_EFFECTS,
	MAX_ENTITIES,
	MAX_IDENTIFIER_LENGTH,
	MAX_INVENTORY_SUMMARIES,
	MAX_MOVEMENT_TOLERANCE,
	MAX_PROVENANCE_TEXT_LENGTH,
	MAX_REASON_CODE_LENGTH,
	MAX_RESULT_MESSAGE_LENGTH,
	MAX_TARGET_SELECTOR_LENGTH,
	MAX_TARGET_ID_LENGTH,
	MAX_VOICE_TEXT_LENGTH,
	MIN_DURATION_MS,
	MIN_MOVEMENT_TOLERANCE,
	PROTOCOL_VERSION,
	TERMINAL_ACTION_STATES,
} from './constants.mjs';

const ENVELOPE_KEYS = ['protocolVersion', 'agentId', 'type', 'messageId'];
const ACTION_TYPES = new Set(Object.keys(ACTION_FIELDS));
const TERMINAL_STATES = new Set(TERMINAL_ACTION_STATES);
const FACES = new Set(BLOCK_FACES);
const TRUSTED_ACTIONS = new WeakSet();
export const MAX_ACTION_ARGUMENT_BYTES = 32_768;
const INTEGER_MIN = -2_147_483_648;
const INTEGER_MAX = 2_147_483_647;

export class ValidationError extends Error {
	constructor(code, message) {
		super(message);
		this.name = 'ValidationError';
		this.code = code;
	}
}

export function validateAction(value) {
	if (value !== null && typeof value === 'object' && TRUSTED_ACTIONS.has(value)) return value;
	const action = structuredClone(requireObject(value, 'action'));
	const type = requireText(action.type, 'action.type', MAX_REASON_CODE_LENGTH);
	if (!ACTION_TYPES.has(type)) throw invalid('UNKNOWN_ACTION', `Unsupported action '${type}'`);
	const optionalFields = OPTIONAL_ACTION_FIELDS[type] ?? [];
	const requiredFields = ACTION_FIELDS[type].filter((field) => !optionalFields.includes(field));
	requireKeys(action, ['type', ...ACTION_FIELDS[type]], 'action', ['type', ...requiredFields]);

	switch (type) {
		case 'move_to':
			requireCoordinates(action, false, 'action');
			requireFiniteRange(action.tolerance, 'action.tolerance', MIN_MOVEMENT_TOLERANCE, MAX_MOVEMENT_TOLERANCE);
			requireBoolean(action.sprint, 'action.sprint');
			break;
		case 'control':
			requireFiniteRange(action.forward, 'action.forward', -1, 1);
			requireFiniteRange(action.strafe, 'action.strafe', -1, 1);
			for (const field of ['jump', 'sneak', 'sprint', 'attack', 'use']) requireBoolean(action[field], `action.${field}`);
			requireFiniteRange(action.yaw, 'action.yaw', -180, 180);
			requireFiniteRange(action.pitch, 'action.pitch', -90, 90);
			requireIntRange(action.selectedSlot, 'action.selectedSlot', 0, 8);
			requireOneOf(action.hand, 'action.hand', ['main', 'off']);
			requireIntRange(action.ticks, 'action.ticks', 1, 200);
			break;
		case 'control_sequence':
			if (!Array.isArray(action.frames) || action.frames.length < 1 || action.frames.length > 64) throw invalid('INVALID_FIELD', 'action.frames must contain 1 to 64 frames');
			requireIntRange(action.maxTicks, 'action.maxTicks', 1, 2000);
			for (const frame of action.frames) {
				requireObject(frame, 'frame');
				requireKeys(frame, [...ACTION_FIELDS.control, 'branches'], 'frame', ACTION_FIELDS.control);
				const { branches, ...input } = frame;
				validateAction({ type: 'control', ...input });
				if (branches === undefined) continue;
				if (!Array.isArray(branches) || branches.length > 16) throw invalid('INVALID_FIELD', 'frame.branches must contain at most 16 conditions');
				for (const branch of branches) {
					requireObject(branch, 'branch');
					requireKeys(branch, ['condition', 'value', 'nextFrame'], 'branch');
					requireOneOf(branch.condition, 'branch.condition', Object.keys(CONTROL_BRANCH_CONDITIONS));
					if (CONTROL_BRANCH_CONDITIONS[branch.condition] === 'number') requireFiniteRange(branch.value, 'branch.value', 0, CONTROL_THRESHOLD_MAXIMA[branch.condition]);
					else requireBoolean(branch.value, 'branch.value');
					requireIntRange(branch.nextFrame, 'branch.nextFrame', 0, action.frames.length);
				}
			}
			break;
		case 'navigate_to':
			requireCoordinates(action, false, 'action');
			requireFiniteRange(action.tolerance, 'action.tolerance', MIN_MOVEMENT_TOLERANCE, MAX_MOVEMENT_TOLERANCE);
			requireBoolean(action.sprint, 'action.sprint');
			requireDuration(action.timeoutMs, 'action.timeoutMs');
			break;
		case 'look_at':
			requireCoordinates(action, false, 'action');
			break;
		case 'attack':
			requireTargetId(action.targetId, 'action.targetId');
			requireDuration(action.timeoutMs, 'action.timeoutMs');
			break;
		case 'transfer_container':
			requireCoordinates(action, true, 'action');
			requireOneOf(action.sourceKind, 'action.sourceKind', ['player', 'container']);
			requireIntRange(action.sourceSlot, 'action.sourceSlot', 0, INTEGER_MAX);
			requireOneOf(action.destinationKind, 'action.destinationKind', ['player', 'container']);
			requireIntRange(action.destinationSlot, 'action.destinationSlot', 0, INTEGER_MAX);
			requireIntRange(action.count, 'action.count', 1, 64);
			requireText(action.expectedItemId, 'action.expectedItemId', MAX_IDENTIFIER_LENGTH);
			requireDuration(action.timeoutMs, 'action.timeoutMs');
			break;
		case 'craft_inventory':
			requireText(action.recipeId, 'action.recipeId', MAX_IDENTIFIER_LENGTH);
			requireIntRange(action.count, 'action.count', 1, 64);
			requireDuration(action.timeoutMs, 'action.timeoutMs');
			break;
		case 'craft_table':
			requireText(action.recipeId, 'action.recipeId', MAX_IDENTIFIER_LENGTH);
			requireCoordinates(action, true, 'action');
			requireIntRange(action.count, 'action.count', 1, 64);
			requireDuration(action.timeoutMs, 'action.timeoutMs');
			break;
		case 'furnace_transaction':
			requireCoordinates(action, true, 'action');
			requireOneOf(action.operation, 'action.operation', ['insert_input', 'insert_fuel', 'take_output']);
			requireIntRange(action.inventorySlot, 'action.inventorySlot', 0, INTEGER_MAX);
			requireIntRange(action.count, 'action.count', 1, 64);
			requireText(action.expectedItemId, 'action.expectedItemId', MAX_IDENTIFIER_LENGTH);
			requireDuration(action.timeoutMs, 'action.timeoutMs');
			break;
		case 'equip_item':
			requireIntRange(action.sourceSlot, 'action.sourceSlot', 0, 35);
			requireOneOf(action.targetSlot, 'action.targetSlot', ['head', 'chest', 'legs', 'feet', 'offhand']);
			requireText(action.expectedItemId, 'action.expectedItemId', MAX_IDENTIFIER_LENGTH);
			break;
		case 'select_tool':
			requireIntRange(action.sourceSlot, 'action.sourceSlot', 0, 35);
			requireIntRange(action.hotbarSlot, 'action.hotbarSlot', 0, 8);
			requireText(action.expectedItemId, 'action.expectedItemId', MAX_IDENTIFIER_LENGTH);
			requireIntRange(action.minRemainingDurability, 'action.minRemainingDurability', 0, INTEGER_MAX);
			break;
		case 'block_with_shield':
			requireDuration(action.durationMs, 'action.durationMs');
			break;
		case 'use_ranged':
			requireTargetId(action.targetId, 'action.targetId');
			requireDuration(action.drawDurationMs, 'action.drawDurationMs');
			requireDuration(action.timeoutMs, 'action.timeoutMs');
			break;
		case 'select_item':
			requireText(action.itemId, 'action.itemId', MAX_IDENTIFIER_LENGTH);
			break;
		case 'use_item':
			if (action.hand !== undefined) requireOneOf(action.hand, 'action.hand', ['main', 'off']);
			if (action.expectedItemId !== undefined) requireText(action.expectedItemId, 'action.expectedItemId', MAX_IDENTIFIER_LENGTH);
			requireDuration(action.durationMs, 'action.durationMs');
			break;
		case 'wait':
			requireDuration(action.durationMs, 'action.durationMs');
			break;
		case 'break_block':
			requireCoordinates(action, true, 'action');
			requireText(action.expectedBlockId, 'action.expectedBlockId', MAX_IDENTIFIER_LENGTH);
			if (action.expectedBlockId.endsWith(':air') || action.expectedBlockId === 'minecraft:cave_air' || action.expectedBlockId === 'minecraft:void_air') {
				throw invalid('INVALID_FIELD', 'action.expectedBlockId must identify a non-air block');
			}
			requireDuration(action.timeoutMs, 'action.timeoutMs');
			break;
		case 'pick_up_item':
			requireTargetId(action.targetSelector, 'action.targetSelector');
			break;
		case 'place_block':
			requireCoordinates(action, true, 'action');
			if (!FACES.has(action.face)) throw invalid('INVALID_FIELD', `action.face must be one of ${BLOCK_FACES.join(', ')}`);
			requireText(action.itemId, 'action.itemId', MAX_IDENTIFIER_LENGTH);
			if (action.desiredState !== undefined && action.desiredState !== null) {
				requireText(action.desiredState, 'action.desiredState', MAX_DESIRED_STATE_LENGTH);
			}
			break;
		case 'chat':
			requireCodePointText(action.message, 'action.message', MAX_CONVERSATION_LENGTH);
			{
				const audience = action.audience ?? 'public';
				requireOneOf(audience, 'action.audience', ['public', 'direct', 'proximity']);
				if (audience === 'proximity') requireCodePointText(action.message, 'action.message', MAX_VOICE_TEXT_LENGTH);
				if (audience === 'direct') requireTargetId(action.recipientId, 'action.recipientId');
				else if (action.recipientId !== undefined && action.recipientId !== null) {
					throw invalid('INVALID_FIELD', 'action.recipientId is only valid for direct chat');
				}
			}
			break;
		case 'interact_block':
			validateHitOffsets(action, 0, 1);
			requireCoordinates(action, true, 'action');
			if (!FACES.has(action.face)) throw invalid('INVALID_FIELD', `action.face must be one of ${BLOCK_FACES.join(', ')}`);
			requireOneOf(action.hand, 'action.hand', ['main', 'off']);
			requireText(action.expectedItemId, 'action.expectedItemId', MAX_IDENTIFIER_LENGTH);
			break;
		case 'interact_entity':
			validateHitOffsets(action, -16, 16);
			requireTargetId(action.targetId, 'action.targetId');
			requireOneOf(action.hand, 'action.hand', ['main', 'off']);
			requireText(action.expectedItemId, 'action.expectedItemId', MAX_IDENTIFIER_LENGTH);
			break;
		case 'dismount':
		case 'start_fall_flying':
		case 'wake_up':
			break;
		case 'set_flight':
			requireBoolean(action.enabled, 'action.enabled');
			break;
		case 'write_sign':
			requireCoordinates(action, true, 'action');
			requireBoolean(action.front, 'action.front');
			for (const field of ['lines', 'expectedLines']) {
				if (!Array.isArray(action[field]) || action[field].length !== 4 || action[field].some((line) => typeof line !== 'string' || line.length > 384)) throw invalid('INVALID_FIELD', `${field} requires four lines of at most 384 characters`);
			}
			break;
		case 'edit_book':
			if (![0, 1, 2, 3, 4, 5, 6, 7, 8, 40].includes(action.slot)) throw invalid('INVALID_FIELD', 'Book must be in hotbar or offhand');
			if (!Array.isArray(action.pages) || action.pages.length > 100 || action.pages.some((page) => typeof page !== 'string' || page.length > 1024)) throw invalid('INVALID_FIELD', 'Book supports at most 100 pages of 1024 characters');
			if (action.title !== undefined) requireText(action.title, 'action.title', 32);
			requireText(action.expectedFingerprint, 'action.expectedFingerprint', 256);
			break;
		case 'menu_click':
		case 'menu_close':
		case 'beacon_effects':
			requireText(action.menuId, 'action.menuId', MAX_IDENTIFIER_LENGTH);
			requireIntRange(action.containerId, 'action.containerId', 0, INTEGER_MAX);
			requireIntRange(action.stateId, 'action.stateId', 0, INTEGER_MAX);
			if (type === 'beacon_effects') {
				requireText(action.primaryEffectId, 'action.primaryEffectId', MAX_IDENTIFIER_LENGTH);
				requireText(action.secondaryEffectId, 'action.secondaryEffectId', MAX_IDENTIFIER_LENGTH);
			}
			if (type === 'menu_click') {
				requireIntRange(action.slot, 'action.slot', -999, 255);
				if (action.slot < 0 && action.slot !== -999) throw invalid('INVALID_FIELD', 'action.slot must be -999 or a nonnegative slot');
				requireIntRange(action.button, 'action.button', 0, 40);
				requireOneOf(action.clickType, 'action.clickType', ['PICKUP', 'QUICK_MOVE', 'SWAP', 'CLONE', 'THROW', 'QUICK_CRAFT', 'PICKUP_ALL']);
				requireText(action.expectedItemId, 'action.expectedItemId', MAX_IDENTIFIER_LENGTH);
				requireIntRange(action.expectedCount, 'action.expectedCount', 0, INTEGER_MAX);
				if (action.expectedFingerprint !== undefined && (typeof action.expectedFingerprint !== 'string' || action.expectedFingerprint.length > 256)) throw invalid('INVALID_FIELD', 'Invalid stack fingerprint');
			}
			break;
		case 'menu_transfer':
			validateOptionalMenuSession(action);
			requireText(action.menuId, 'action.menuId', MAX_IDENTIFIER_LENGTH);
			requireIntRange(action.sourceSlot, 'action.sourceSlot', 0, 255);
			requireIntRange(action.destinationSlot, 'action.destinationSlot', 0, 255);
			requireIntRange(action.count, 'action.count', 1, 64);
			requireText(action.expectedItemId, 'action.expectedItemId', MAX_IDENTIFIER_LENGTH);
			requireDuration(action.timeoutMs, 'action.timeoutMs');
			break;
		case 'menu_button':
			validateOptionalMenuSession(action);
			requireText(action.menuId, 'action.menuId', MAX_IDENTIFIER_LENGTH);
			requireIntRange(action.buttonId, 'action.buttonId', 0, 255);
			requireDuration(action.timeoutMs, 'action.timeoutMs');
			break;
		case 'anvil_rename':
			validateOptionalMenuSession(action);
			requireText(action.menuId, 'action.menuId', MAX_IDENTIFIER_LENGTH);
			requireText(action.name, 'action.name', 50);
			requireDuration(action.timeoutMs, 'action.timeoutMs');
			break;
		case 'set_door':
			requireCoordinates(action, true, 'action');
			requireBoolean(action.open, 'action.open');
			break;
		case 'drop_item':
			requireInt32(action.slot, 'action.slot');
			requireInt32(action.count, 'action.count');
			if (action.slot < 0 || action.slot > 35) throw invalid('INVALID_FIELD', 'action.slot must be between 0 and 35');
			if (action.count < 1 || action.count > 64) throw invalid('INVALID_FIELD', 'action.count must be between 1 and 64');
			break;
	}
	const { type: ignoredType, ...argumentsOnly } = action;
	const encodedArguments = JSON.stringify(argumentsOnly).replace(/\u2028/g, '\\u2028').replace(/\u2029/g, '\\u2029');
	if (Buffer.byteLength(encodedArguments, 'utf8') > MAX_ACTION_ARGUMENT_BYTES) {
		throw invalid('ACTION_ARGUMENTS_TOO_LARGE', `Action arguments exceed ${MAX_ACTION_ARGUMENT_BYTES} serialized UTF-8 bytes`);
	}
	const normalized = deepFreeze(action);
	TRUSTED_ACTIONS.add(normalized);
	return normalized;
}

function validateHitOffsets(action, minimum, maximum) {
	const present = ['hitX', 'hitY', 'hitZ'].filter((field) => action[field] !== undefined);
	if (present.length !== 0 && present.length !== 3) throw invalid('INVALID_FIELD', 'hitX, hitY and hitZ must be supplied together');
	for (const field of present) requireFiniteRange(action[field], `action.${field}`, minimum, maximum);
}

function validateOptionalMenuSession(action) {
	if ((action.containerId === undefined) !== (action.stateId === undefined)) throw invalid('INVALID_FIELD', 'containerId and stateId must be supplied together');
	if (action.containerId === undefined) return;
	for (const field of ['containerId', 'stateId']) requireIntRange(action[field], field, 0, INTEGER_MAX);
}

export function createActionCommand(actionValue, { commandId, issuedAtEpochMs }) {
	const action = validateAction(actionValue);
	requireText(commandId, 'commandId', MAX_COMMAND_ID_LENGTH);
	requirePositiveSafeInteger(issuedAtEpochMs, 'issuedAtEpochMs');
	return {
		protocolVersion: PROTOCOL_VERSION,
		commandId,
		type: action.type,
		issuedAtEpochMs,
		...Object.fromEntries(ACTION_FIELDS[action.type].map((field) => [field, action[field]])),
	};
}

export function validateEnvelope(value, extraKeys) {
	const message = requireObject(value, 'message');
	const inferredExtraKeys = extraKeys ?? envelopeExtraKeys(message.type);
	requireKeys(message, [...ENVELOPE_KEYS, ...inferredExtraKeys], 'message');
	if (!Number.isSafeInteger(message.protocolVersion) || message.protocolVersion !== PROTOCOL_VERSION) {
		throw invalid('UNSUPPORTED_VERSION', `Unsupported protocolVersion ${String(message.protocolVersion)}; expected ${PROTOCOL_VERSION}`);
	}
	requireText(message.agentId, 'message.agentId', MAX_COMMAND_ID_LENGTH);
	requireText(message.type, 'message.type', MAX_COMMAND_ID_LENGTH);
	requireText(message.messageId, 'message.messageId', MAX_COMMAND_ID_LENGTH);
	if (message.type === 'hello_ack') requireText(message.replyTo, 'message.replyTo', MAX_COMMAND_ID_LENGTH);
	return structuredClone(message);
}

export function validateObservation(value) {
	const observation = validateEnvelope(value, [
		'ready', 'status', 'position', 'velocity', 'view', 'player', 'inventory', 'entities', 'blocks',
		'world', 'currentAction', 'lastResult',
	]);
	if (observation.type !== 'observation') throw invalid('INVALID_FIELD', "message.type must be 'observation'");
	requireBoolean(observation.ready, 'ready');
	requireText(observation.status, 'status', MAX_REASON_CODE_LENGTH);
	if (observation.ready && observation.status !== 'ready') throw invalid('INVALID_FIELD', "ready observation status must be 'ready'");
	validateVector(observation.position, 'position', ['x', 'y', 'z']);
	validateVector(observation.velocity, 'velocity', ['x', 'y', 'z']);
	validateVector(observation.view, 'view', ['yaw', 'pitch']);
	validatePlayer(observation.player);
	validateInventory(observation.inventory);
	validateArray(observation.entities, 'entities', MAX_ENTITIES, validateEntity);
	validateArray(observation.blocks, 'blocks', MAX_BLOCKS, validateBlock);
	validateWorld(observation.world);
	validateActionStatus(observation.currentAction);
	validateResultStatus(observation.lastResult);
	return observation;
}

export function validateActionResult(value) {
	const result = validateEnvelope(value, ['commandId', 'state', 'reasonCode', 'message', 'completedAtEpochMs']);
	if (result.type !== 'action_result') throw invalid('INVALID_FIELD', "message.type must be 'action_result'");
	requireText(result.commandId, 'commandId', MAX_COMMAND_ID_LENGTH);
	if (!TERMINAL_STATES.has(result.state)) throw invalid('INVALID_FIELD', 'action result state must be terminal');
	requireText(result.reasonCode, 'reasonCode', MAX_REASON_CODE_LENGTH);
	requireText(result.message, 'message', MAX_RESULT_MESSAGE_LENGTH, true);
	requirePositiveSafeInteger(result.completedAtEpochMs, 'completedAtEpochMs');
	return result;
}

export function validateActionProgress(value) {
	const progress = validateEnvelope(value, ['commandId', 'actionType', 'state', 'elapsedMs', 'message', 'observedAtEpochMs']);
	if (progress.type !== 'action_progress') throw invalid('INVALID_FIELD', "message.type must be 'action_progress'");
	requireText(progress.commandId, 'commandId', MAX_COMMAND_ID_LENGTH);
	if (!ACTION_TYPES.has(progress.actionType)) throw invalid('UNKNOWN_ACTION', `Unsupported action '${progress.actionType}'`);
	if (progress.state !== 'RUNNING') throw invalid('INVALID_FIELD', "action progress state must be 'RUNNING'");
	requireNonNegativeSafeInteger(progress.elapsedMs, 'elapsedMs');
	requireText(progress.message, 'message', MAX_RESULT_MESSAGE_LENGTH, true);
	requirePositiveSafeInteger(progress.observedAtEpochMs, 'observedAtEpochMs');
	return progress;
}

function envelopeExtraKeys(type) {
	if (type === 'hello_ack') return ['replyTo'];
	return [];
}

function validatePlayer(value) {
	const player = requireObject(value, 'player');
	requireKeys(player, ['health', 'maxHealth', 'hunger', 'armor', 'effects'], 'player');
	requireNonNegativeFinite(player.health, 'player.health');
	requireNonNegativeFinite(player.maxHealth, 'player.maxHealth');
	requireNonNegativeSafeInteger(player.hunger, 'player.hunger');
	requireNonNegativeSafeInteger(player.armor, 'player.armor');
	validateArray(player.effects, 'player.effects', MAX_EFFECTS, (effect, path) => {
		effect = requireObject(effect, path);
		requireKeys(effect, ['effectId', 'amplifier', 'durationTicks', 'ambient', 'visible'], path);
		requireText(effect.effectId, `${path}.effectId`, MAX_IDENTIFIER_LENGTH);
		requireNonNegativeSafeInteger(effect.amplifier, `${path}.amplifier`);
		requireNonNegativeSafeInteger(effect.durationTicks, `${path}.durationTicks`);
		requireBoolean(effect.ambient, `${path}.ambient`);
		requireBoolean(effect.visible, `${path}.visible`);
	});
}

function validateInventory(value) {
	const inventory = requireObject(value, 'inventory');
	requireKeys(inventory, ['selectedSlot', 'selectedItemId', 'selectedItemCount', 'items'], 'inventory');
	if (!Number.isSafeInteger(inventory.selectedSlot) || inventory.selectedSlot < 0 || inventory.selectedSlot > 8) throw invalid('OUT_OF_RANGE', 'inventory.selectedSlot must be between 0 and 8');
	requireText(inventory.selectedItemId, 'inventory.selectedItemId', MAX_IDENTIFIER_LENGTH);
	requireNonNegativeSafeInteger(inventory.selectedItemCount, 'inventory.selectedItemCount');
	validateArray(inventory.items, 'inventory.items', MAX_INVENTORY_SUMMARIES, (item, path) => {
		item = requireObject(item, path);
		requireKeys(item, ['itemId', 'count'], path);
		requireText(item.itemId, `${path}.itemId`, MAX_IDENTIFIER_LENGTH);
		requirePositiveSafeInteger(item.count, `${path}.count`);
	});
}

function validateEntity(value, path) {
	const entity = requireObject(value, path);
	requireKeys(entity, ['stableId', 'typeId', 'name', 'x', 'y', 'z', 'distanceSquared', 'health', 'maxHealth', 'hostile'], path);
	requireText(entity.stableId, `${path}.stableId`, MAX_IDENTIFIER_LENGTH);
	requireText(entity.typeId, `${path}.typeId`, MAX_IDENTIFIER_LENGTH);
	requireText(entity.name, `${path}.name`, MAX_IDENTIFIER_LENGTH, true);
	for (const field of ['x', 'y', 'z']) requireFinite(entity[field], `${path}.${field}`);
	for (const field of ['distanceSquared', 'health', 'maxHealth']) requireNonNegativeFinite(entity[field], `${path}.${field}`);
	requireBoolean(entity.hostile, `${path}.hostile`);
}

function validateBlock(value, path) {
	const block = requireObject(value, path);
	requireKeys(block, ['x', 'y', 'z', 'blockId', 'fluidId', 'collisionShapeEmpty', 'fullCollisionBlock', 'distanceSquared'], path);
	for (const field of ['x', 'y', 'z']) requireInt32(block[field], `${path}.${field}`);
	requireText(block.blockId, `${path}.blockId`, MAX_IDENTIFIER_LENGTH);
	requireText(block.fluidId, `${path}.fluidId`, MAX_IDENTIFIER_LENGTH);
	requireBoolean(block.collisionShapeEmpty, `${path}.collisionShapeEmpty`);
	requireBoolean(block.fullCollisionBlock, `${path}.fullCollisionBlock`);
	requireNonNegativeFinite(block.distanceSquared, `${path}.distanceSquared`);
}

function validateWorld(value) {
	const world = requireObject(value, 'world');
	requireKeys(world, ['dimensionId', 'gameTime', 'defaultClockTime', 'raining', 'thundering'], 'world');
	requireText(world.dimensionId, 'world.dimensionId', MAX_IDENTIFIER_LENGTH, true);
	requireSafeInteger(world.gameTime, 'world.gameTime');
	requireSafeInteger(world.defaultClockTime, 'world.defaultClockTime');
	requireBoolean(world.raining, 'world.raining');
	requireBoolean(world.thundering, 'world.thundering');
}

function validateActionStatus(value) {
	const status = requireObject(value, 'currentAction');
	requireKeys(status, ['present', 'commandId', 'type', 'state'], 'currentAction');
	requireBoolean(status.present, 'currentAction.present');
	requirePresenceText(status.commandId, 'currentAction.commandId', status.present, MAX_COMMAND_ID_LENGTH);
	requirePresenceText(status.type, 'currentAction.type', status.present, MAX_REASON_CODE_LENGTH);
	requirePresenceText(status.state, 'currentAction.state', status.present, MAX_REASON_CODE_LENGTH);
}

function validateResultStatus(value) {
	const result = requireObject(value, 'lastResult');
	requireKeys(result, ['present', 'commandId', 'state', 'reasonCode', 'message', 'completedAtEpochMs'], 'lastResult');
	requireBoolean(result.present, 'lastResult.present');
	requirePresenceText(result.commandId, 'lastResult.commandId', result.present, MAX_COMMAND_ID_LENGTH);
	requirePresenceText(result.state, 'lastResult.state', result.present, MAX_REASON_CODE_LENGTH);
	requirePresenceText(result.reasonCode, 'lastResult.reasonCode', result.present, MAX_REASON_CODE_LENGTH);
	requireText(result.message, 'lastResult.message', MAX_RESULT_MESSAGE_LENGTH, true);
	if (result.present) requirePositiveSafeInteger(result.completedAtEpochMs, 'lastResult.completedAtEpochMs');
	else if (result.completedAtEpochMs !== 0) throw invalid('INVALID_FIELD', 'absent result must have a zero completion timestamp');
}

function validateVector(value, path, fields) {
	const vector = requireObject(value, path);
	requireKeys(vector, fields, path);
	for (const field of fields) requireFinite(vector[field], `${path}.${field}`);
}

function validateArray(value, path, maximum, validator) {
	if (!isExactArray(value)) throw invalid('INVALID_FIELD', `${path} must be a dense native array without custom properties`);
	if (value.length > maximum) throw invalid('OUT_OF_RANGE', `${path} must contain at most ${maximum} entries`);
	for (let index = 0; index < value.length; index += 1) validator(value[index], `${path}[${index}]`);
}

function isExactArray(value) {
	if (!Array.isArray(value) || Object.getPrototypeOf(value) !== Array.prototype) return false;
	const ownKeys = Reflect.ownKeys(value);
	if (ownKeys.some((key) => typeof key !== 'string')) return false;
	const expected = new Set(['length', ...Array.from({ length: value.length }, (_, index) => String(index))]);
	if (ownKeys.length !== expected.size || ownKeys.some((key) => !expected.has(key))) return false;
	const length = Object.getOwnPropertyDescriptor(value, 'length');
	if (!length || !Object.hasOwn(length, 'value') || length.enumerable || length.configurable || !length.writable) return false;
	for (let index = 0; index < value.length; index += 1) {
		const entry = Object.getOwnPropertyDescriptor(value, String(index));
		if (!entry || !Object.hasOwn(entry, 'value') || !entry.enumerable || !entry.configurable || !entry.writable) return false;
	}
	return true;
}

function requireCoordinates(value, integral, path) {
	for (const field of ['x', 'y', 'z']) {
		if (integral) requireInt32(value[field], `${path}.${field}`);
		else requireFinite(value[field], `${path}.${field}`);
	}
}

function requireDuration(value, path) {
	if (!Number.isSafeInteger(value) || value < MIN_DURATION_MS || value > MAX_DURATION_MS) throw invalid('OUT_OF_RANGE', `${path} must be an integer between ${MIN_DURATION_MS} and ${MAX_DURATION_MS}`);
}

function requirePresenceText(value, path, present, maximum) {
	requireText(value, path, maximum, !present);
	if (present === (value.length === 0)) throw invalid('INVALID_FIELD', `${path} presence must match its presence flag`);
}

function requireObject(value, path) {
	if (value === null || typeof value !== 'object' || Array.isArray(value)
			|| ![Object.prototype, null].includes(Object.getPrototypeOf(value))) throw invalid('INVALID_FIELD', `${path} must be an object`);
	return value;
}

function requireTargetId(value, path) {
	requireText(value, path, MAX_TARGET_ID_LENGTH);
	if (!/^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i.test(value)) {
		throw invalid('INVALID_FIELD', `${path} must be a canonical UUID`);
	}
}

function requireKeys(value, expected, path, required = expected) {
	const allowed = new Set(expected);
	for (const key of Object.keys(value)) if (!allowed.has(key)) throw invalid('UNKNOWN_FIELD', `Unknown field '${path === 'message' || path === 'action' ? key : `${path}.${key}`}'`);
	for (const key of required) if (!Object.hasOwn(value, key)) throw invalid('MISSING_FIELD', `Required field '${path}.${key}' is missing`);
}

export function validateActionCommandPayload(value) {
	const command = requireObject(value, 'action_command');
	requireKeys(
		command,
		['goalRevision', 'actionId', 'action', 'provenance'],
		'action_command',
		['goalRevision', 'actionId', 'action', 'provenance'],
	);
	requireNonNegativeSafeInteger(command.goalRevision, 'goalRevision');
	const normalized = {
		goalRevision: command.goalRevision,
		actionId: requireText(command.actionId, 'actionId', MAX_COMMAND_ID_LENGTH),
		action: validateAction(command.action),
		provenance: validateActionProvenance(command.provenance),
	};
	return deepFreeze(normalized);
}

export function validateActionProvenance(value) {
	const provenance = requireObject(value, 'provenance');
	if (provenance.watcherId !== undefined && provenance.traceId === undefined) {
		throw invalid('MISSING_FIELD', 'Required field provenance.traceId is missing for watcher provenance');
	}
	requireKeys(
		provenance,
		['provider', 'model', 'reasoningEffort', 'serviceTier', 'programId', 'programVersion', 'sourceStepId', 'eventSequence', 'traceId', 'watcherId'],
		'provenance',
		['provider', 'model', 'reasoningEffort', 'serviceTier', 'programId', 'programVersion', 'sourceStepId', 'eventSequence'],
	);
	requirePositiveSafeInteger(provenance.programVersion, 'provenance.programVersion');
	requireNonNegativeSafeInteger(provenance.eventSequence, 'provenance.eventSequence');
	return deepFreeze({
		provider: requireText(provenance.provider, 'provenance.provider', MAX_PROVENANCE_TEXT_LENGTH),
		model: requireText(provenance.model, 'provenance.model', MAX_PROVENANCE_TEXT_LENGTH),
		reasoningEffort: requireText(provenance.reasoningEffort, 'provenance.reasoningEffort', MAX_PROVENANCE_TEXT_LENGTH),
		serviceTier: requireText(provenance.serviceTier, 'provenance.serviceTier', MAX_PROVENANCE_TEXT_LENGTH),
		programId: requireText(provenance.programId, 'provenance.programId', MAX_PROVENANCE_TEXT_LENGTH),
		programVersion: provenance.programVersion,
		sourceStepId: requireText(provenance.sourceStepId, 'provenance.sourceStepId', MAX_PROVENANCE_TEXT_LENGTH),
		eventSequence: provenance.eventSequence,
		...(provenance.traceId === undefined ? {} : { traceId: requireTraceId(provenance.traceId) }),
		...(provenance.watcherId === undefined ? {} : { watcherId: requireText(provenance.watcherId, 'provenance.watcherId', 128) }),
	});
}

function requireTraceId(value) {
	const traceId = requireText(value, 'provenance.traceId', MAX_PROVENANCE_TEXT_LENGTH);
	if (Buffer.byteLength(traceId, 'utf8') > 128) throw invalid('OUT_OF_RANGE', 'provenance.traceId must contain at most 128 UTF-8 bytes');
	if ([...traceId].some((character) => /[\u0000-\u001f\u007f]/u.test(character))) throw invalid('INVALID_FIELD', 'provenance.traceId contains control characters');
	return traceId;
}

function requireText(value, path, maximum, emptyAllowed = false) {
	if (typeof value !== 'string') throw invalid('INVALID_FIELD', `${path} must be a string`);
	if (!emptyAllowed && isProtocolBlank(value)) throw invalid('INVALID_FIELD', `${path} must not be blank`);
	if (value.length > maximum) throw invalid('OUT_OF_RANGE', `${path} must contain at most ${maximum} characters`);
	return value;
}

function requireCodePointText(value, path, maximum) {
	if (typeof value !== 'string') throw invalid('INVALID_FIELD', `${path} must be a string`);
	if (isProtocolBlank(value)) throw invalid('INVALID_FIELD', `${path} must not be blank`);
	if ([...value].length > maximum) throw invalid('OUT_OF_RANGE', `${path} must contain at most ${maximum} code points`);
	return value;
}

function isProtocolBlank(value) {
	return [...value].every((character) => isProtocolWhitespace(character.codePointAt(0)));
}

function isProtocolWhitespace(codePoint) {
	return (codePoint >= 0x0009 && codePoint <= 0x000d)
		|| (codePoint >= 0x001c && codePoint <= 0x0020)
		|| codePoint === 0x00a0
		|| codePoint === 0x1680
		|| (codePoint >= 0x2000 && codePoint <= 0x200a)
		|| codePoint === 0x2028
		|| codePoint === 0x2029
		|| codePoint === 0x202f
		|| codePoint === 0x205f
		|| codePoint === 0x3000
		|| codePoint === 0xfeff;
}

function deepFreeze(value) {
	if (value === null || typeof value !== 'object' || Object.isFrozen(value)) return value;
	for (const child of Object.values(value)) deepFreeze(child);
	return Object.freeze(value);
}

function requireBoolean(value, path) {
	if (typeof value !== 'boolean') throw invalid('INVALID_FIELD', `${path} must be a boolean`);
}

function requireFinite(value, path) {
	if (typeof value !== 'number' || !Number.isFinite(value)) throw invalid('OUT_OF_RANGE', `${path} must be a finite number`);
}

function requireNonNegativeFinite(value, path) {
	requireFinite(value, path);
	if (value < 0) throw invalid('OUT_OF_RANGE', `${path} must not be negative`);
}

function requireFiniteRange(value, path, minimum, maximum) {
	requireFinite(value, path);
	if (value < minimum || value > maximum) throw invalid('OUT_OF_RANGE', `${path} must be between ${minimum} and ${maximum}`);
}

function requireInt32(value, path) {
	if (!Number.isInteger(value) || value < INTEGER_MIN || value > INTEGER_MAX) throw invalid('OUT_OF_RANGE', `${path} must be a 32-bit integer`);
}

function requireIntRange(value, path, minimum, maximum) {
	requireInt32(value, path);
	if (value < minimum || value > maximum) throw invalid('OUT_OF_RANGE', `${path} must be between ${minimum} and ${maximum}`);
}

function requireOneOf(value, path, allowed) {
	if (typeof value !== 'string' || !allowed.includes(value)) throw invalid('INVALID_FIELD', `${path} must be one of ${allowed.join(', ')}`);
}

function requireSafeInteger(value, path) {
	if (!Number.isSafeInteger(value)) throw invalid('OUT_OF_RANGE', `${path} must be a safe integer`);
}

function requireNonNegativeSafeInteger(value, path) {
	requireSafeInteger(value, path);
	if (value < 0) throw invalid('OUT_OF_RANGE', `${path} must not be negative`);
}

function requirePositiveSafeInteger(value, path) {
	requireSafeInteger(value, path);
	if (value <= 0) throw invalid('OUT_OF_RANGE', `${path} must be positive`);
}

function invalid(code, message) {
	return new ValidationError(code, message);
}
