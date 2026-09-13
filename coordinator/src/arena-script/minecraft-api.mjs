import { ACTION_FIELDS } from '../constants.mjs';

export const SCRIPT_PRIMITIVES = Object.freeze(new Set(Object.keys(ACTION_FIELDS)));

export const PLAYER_MEMBER_PRIMITIVES = Object.freeze({
	...Object.fromEntries(Object.keys(ACTION_FIELDS).map((primitive) => [primitive.replace(/_([a-z])/g, (_match, letter) => letter.toUpperCase()), primitive])),
	navigateTo: 'navigate_to', lookAt: 'look_at', attack: 'attack',
	selectItem: 'select_item', useItem: 'use_item', mine: 'break_block', pickUpItem: 'pick_up_item', place: 'place_block',
	chat: 'chat', wait: 'wait', setDoor: 'set_door', dropItem: 'drop_item',
	transferContainer: 'transfer_container', craftInventory: 'craft_inventory', craftTable: 'craft_table',
	furnaceTransaction: 'furnace_transaction', equipItem: 'equip_item', selectTool: 'select_tool',
	blockWithShield: 'block_with_shield', useRanged: 'use_ranged',
	interactBlock: 'interact_block', interactEntity: 'interact_entity',
	dismount: 'dismount', startFallFlying: 'start_fall_flying',
	menuTransfer: 'menu_transfer', menuButton: 'menu_button', anvilRename: 'anvil_rename',
	respawn: 'respawn',
});

export const EXACT_TARGET_ACTIONS = Object.freeze({
	attack: Object.freeze(['targetId', 'timeoutMs']),
	pickUpItem: Object.freeze(['targetSelector']),
	useRanged: Object.freeze(['targetId', 'drawDurationMs', 'timeoutMs']),
	interactEntity: Object.freeze(['targetId', 'hand', 'expectedItemId']),
});

export const FACTUAL_API_PATHS = Object.freeze(new Set([
	'player.state', 'world.items', 'world.entities', 'world.blocks', 'world.nearest',
	'world.state', 'world.menu', 'inventory.count', 'inventory.countTag', 'inventory.slots', 'inventory.state',
]));

export const MATH_METHODS = Object.freeze({
	abs: [Math.abs, 1], floor: [Math.floor, 1], ceil: [Math.ceil, 1], round: [Math.round, 1],
	sqrt: [Math.sqrt, 1], sin: [Math.sin, 1], cos: [Math.cos, 1], atan2: [Math.atan2, 2],
	min: [Math.min, 1, 16], max: [Math.max, 1, 16], hypot: [Math.hypot, 1, 16],
});
export const PURE_API_PATHS = Object.freeze(new Set([
	...FACTUAL_API_PATHS, ...Object.keys(MATH_METHODS).map((name) => `math.${name}`),
]));

export const SCRIPT_API_CALL_PATHS = Object.freeze(new Set([
	...Object.keys(PLAYER_MEMBER_PRIMITIVES).map((name) => `player.${name}`),
	...PURE_API_PATHS,
	'world.inspect',
	'world.remember', 'world.queryMemory',
]));

export const SCRIPT_BINDINGS = freezeRecord({
	player: freezeRecord(Object.fromEntries(Object.entries(PLAYER_MEMBER_PRIMITIVES).map(([name, primitive]) => [name, freezeRecord({ primitive })]))),
});

function freezeRecord(values) { return Object.freeze(Object.assign(Object.create(null), values)); }
