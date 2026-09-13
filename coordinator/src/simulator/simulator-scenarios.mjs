const COMBAT_TARGET_ID = '00000000-0000-4000-8000-000000000001';
const DM_RECIPIENT_ID = '00000000-0000-4000-8000-000000000002';

const scenarioList = [
	{
		id: 'stone-tool-gathering',
		title: 'Gather stone and craft tools',
		agentId: 'stone-agent',
		goal: 'Gather stone and craft a stone pickaxe.',
		world: baseWorld({ agentId: 'stone-agent', inventory: [{ itemId: 'minecraft:stick', count: 2, slot: 0 }, { itemId: 'minecraft:cobblestone', count: 3, slot: 1 }], blocks: [{ x: 0, y: 0, z: 0, blockId: 'minecraft:stone' }, { x: 1, y: 0, z: 0, blockId: 'minecraft:stone' }, { x: 1, y: 1, z: 0, blockId: 'minecraft:stone' }] }),
		commands: [
			{ actionId: 'stone-navigate', actionType: 'navigate_to', arguments: { x: 0, y: 1, z: 1, tolerance: 0.2, sprint: true, timeoutMs: 1_000 } },
			{ actionId: 'stone-mine', actionType: 'break_block', arguments: { x: 1, y: 1, z: 0, expectedBlockId: 'minecraft:stone', timeoutMs: 1_000 } },
			{ actionId: 'stone-craft', actionType: 'craft_inventory', arguments: { recipeId: 'minecraft:stone_pickaxe', count: 1, timeoutMs: 1_000 } },
		],
		events: [],
		expected: { actionIds: ['stone-navigate', 'stone-mine', 'stone-craft'], toolItemId: 'minecraft:stone_pickaxe', dropItemId: 'minecraft:cobblestone' },
		success: (state) => resultsSucceeded(state, ['stone-navigate', 'stone-mine', 'stone-craft']) && itemCount(state, 'minecraft:stone_pickaxe') === 1 && itemCount(state, 'minecraft:cobblestone') === 1,
	},
	{
		id: 'obstacle-navigation',
		title: 'Navigate around a solid obstacle',
		agentId: 'navigation-agent',
		goal: 'Navigate around the solid obstacle using the declared waypoints.',
		world: baseWorld({ agentId: 'navigation-agent', blocks: [
			{ x: 0, y: 0, z: 0, blockId: 'minecraft:stone' }, { x: 1, y: 0, z: 0, blockId: 'minecraft:stone' }, { x: 2, y: 0, z: 0, blockId: 'minecraft:stone' },
			{ x: 0, y: 0, z: 1, blockId: 'minecraft:stone' }, { x: 1, y: 0, z: 1, blockId: 'minecraft:stone' }, { x: 2, y: 0, z: 1, blockId: 'minecraft:stone' },
			{ x: 0, y: 0, z: 2, blockId: 'minecraft:stone' }, { x: 1, y: 0, z: 2, blockId: 'minecraft:stone' }, { x: 2, y: 0, z: 2, blockId: 'minecraft:stone' }, { x: 3, y: 0, z: 2, blockId: 'minecraft:stone' },
			{ x: 3, y: 0, z: 1, blockId: 'minecraft:stone' }, { x: 3, y: 0, z: 0, blockId: 'minecraft:stone' },
			{ x: 1, y: 1, z: 0, blockId: 'minecraft:stone' },
		] }),
		commands: [
			{ actionId: 'navigate-obstacle-waypoint-1', actionType: 'navigate_to', arguments: { x: 0, y: 1, z: 2, tolerance: 0.2, sprint: false, timeoutMs: 2_000 } },
			{ actionId: 'navigate-obstacle-waypoint-2', actionType: 'navigate_to', arguments: { x: 3, y: 1, z: 2, tolerance: 0.2, sprint: false, timeoutMs: 2_000 } },
			{ actionId: 'navigate-obstacle-waypoint-3', actionType: 'navigate_to', arguments: { x: 3, y: 1, z: 0, tolerance: 0.2, sprint: false, timeoutMs: 2_000 } },
		],
		events: [],
		expected: {
			actionIds: ['navigate-obstacle-waypoint-1', 'navigate-obstacle-waypoint-2', 'navigate-obstacle-waypoint-3'],
			obstacle: { x: 1, y: 1, z: 0, blockId: 'minecraft:stone' },
			waypoints: [{ x: 0, y: 1, z: 2 }, { x: 3, y: 1, z: 2 }, { x: 3, y: 1, z: 0 }],
			tolerance: 0.2,
		},
		success: (state) => resultsSucceeded(state, ['navigate-obstacle-waypoint-1', 'navigate-obstacle-waypoint-2', 'navigate-obstacle-waypoint-3']) && obstacleRouteMatches(state, {
			obstacle: { x: 1, y: 1, z: 0, blockId: 'minecraft:stone' },
			waypoints: [{ x: 0, y: 1, z: 2 }, { x: 3, y: 1, z: 2 }, { x: 3, y: 1, z: 0 }],
		}),
	},
	{
		id: 'inventory-crafting',
		title: 'Craft an exact inventory count',
		agentId: 'craft-agent',
		goal: 'Craft planks in inventory, craft a table, place it, then craft sticks at the table.',
		world: baseWorld({ agentId: 'craft-agent', inventory: [{ itemId: 'minecraft:oak_log', count: 2, slot: 0 }] }),
		commands: [
			{ actionId: 'craft-planks', actionType: 'craft_inventory', arguments: { recipeId: 'minecraft:planks', count: 8, timeoutMs: 1_000 } },
			{ actionId: 'craft-table', actionType: 'craft_inventory', arguments: { recipeId: 'minecraft:crafting_table', count: 1, timeoutMs: 1_000 } },
			{ actionId: 'place-table', actionType: 'place_block', arguments: { x: 1, y: 1, z: 0, face: 'up', itemId: 'minecraft:crafting_table', desiredState: null } },
			{ actionId: 'craft-sticks-at-table', actionType: 'craft_table', arguments: { recipeId: 'minecraft:sticks', x: 1, y: 1, z: 0, count: 1, timeoutMs: 1_000 } },
		],
		events: [],
		expected: { actionIds: ['craft-planks', 'craft-table', 'place-table', 'craft-sticks-at-table'], outputItemId: 'minecraft:oak_planks', outputCount: 8, consumedItemId: 'minecraft:oak_log', consumedCount: 2, tableBlock: { x: 1, y: 1, z: 0, blockId: 'minecraft:crafting_table' }, stickItemId: 'minecraft:stick', stickCount: 1, finalPlanks: 2 },
		success: (state) => resultsSucceeded(state, ['craft-planks', 'craft-table', 'place-table', 'craft-sticks-at-table']) && exactInventoryDelta(state, 'minecraft:oak_log', -2) && exactInventoryDelta(state, 'minecraft:oak_planks', 2) && exactInventoryDelta(state, 'minecraft:stick', 1) && state.state?.block?.x === 1 && state.state.block.y === 1 && state.state.block.z === 0 && state.state.block.blockId === 'minecraft:crafting_table',
	},
	{
		id: 'block-placement',
		title: 'Place and verify a block',
		agentId: 'placement-agent',
		goal: 'Place one cobblestone block on the marked support with the requested state.',
		world: baseWorld({ agentId: 'placement-agent', inventory: [{ itemId: 'minecraft:cobblestone', count: 1, slot: 0 }] }),
		commands: [{ actionId: 'place-exact', actionType: 'place_block', arguments: { x: 1, y: 1, z: 0, face: 'up', itemId: 'minecraft:cobblestone', desiredState: 'facing=north' } }],
		events: [],
		expected: { actionIds: ['place-exact'], position: { x: 1, y: 1, z: 0 }, blockId: 'minecraft:cobblestone', desiredState: 'facing=north', consumedItemId: 'minecraft:cobblestone', consumedCount: 1 },
		success: (state) => resultsSucceeded(state, ['place-exact']) && exactInventoryDelta(state, 'minecraft:cobblestone', -1) && state.state?.block?.x === 1 && state.state.block.y === 1 && state.state.block.z === 0 && state.state.block.blockId === 'minecraft:cobblestone' && state.state.block.desiredState === 'facing=north',
	},
	{
		id: 'hostile-mob-combat',
		title: 'Fight a hostile mob with cooldown-limited attacks',
		agentId: 'combat-agent',
		goal: 'Defeat the declared hostile target.',
		world: baseWorld({ agentId: 'combat-agent', entities: [{ id: COMBAT_TARGET_ID, type: 'minecraft:zombie', position: { x: 1, y: 1, z: 0 }, health: 3, maxHealth: 3 }] }),
		commands: [{ actionId: 'combat-attack', actionType: 'attack', arguments: { targetId: COMBAT_TARGET_ID, timeoutMs: 1_000 } }],
		events: [],
		expected: { actionIds: ['combat-attack'], targetId: COMBAT_TARGET_ID, targetDefeated: true },
		success: (state) => resultsSucceeded(state, ['combat-attack']) && state.state?.target?.id === COMBAT_TARGET_ID && state.state.target.dead === true,
	},
	{
		id: 'lava-damage-reaction',
		title: 'React to environmental lava damage',
		agentId: 'lava-agent',
		goal: 'Recognize the lava hazard and react by leaving it or respawning at the checkpoint.',
		world: baseWorld({ agentId: 'lava-agent', position: { x: 0, y: 1, z: 1 }, health: 20, checkpoint: { x: 3, y: 1, z: 1 }, blocks: [{ x: 0, y: 0, z: 1, blockId: 'minecraft:lava' }, { x: 0, y: 0, z: 0, blockId: 'minecraft:stone' }, { x: 1, y: 0, z: 0, blockId: 'minecraft:stone' }, { x: 2, y: 0, z: 0, blockId: 'minecraft:stone' }, { x: 3, y: 0, z: 0, blockId: 'minecraft:stone' }] }),
		commands: [
			{ actionId: 'lava-wait', actionType: 'wait', arguments: { durationMs: 250 } },
			{ actionId: 'lava-leave', actionType: 'navigate_to', arguments: { x: 3, y: 1, z: 1, tolerance: 0.2, sprint: true, timeoutMs: 2_000 } },
		],
		events: [{ eventId: 'lava-hazard-1', type: 'hazard', hazardType: 'lava', position: { x: 0, y: 0, z: 1 } }],
		expected: { actionIds: ['lava-wait', 'lava-leave'], hazardEventId: 'lava-hazard-1', hazardType: 'lava', reaction: 'leave_hazard', healthBefore: 20, requiresDamage: true },
		success: (state) => resultsSucceeded(state, ['lava-wait', 'lava-leave']) && state.state?.hazard?.eventId === 'lava-hazard-1' && state.state.hazard.hazardType === 'lava' && state.state.hazard.healthAfter < state.state.hazard.healthBefore && state.state.reaction === 'leave_hazard' && atTarget(state, { x: 3, y: 1, z: 1 }, 0.2),
	},
	{
		id: 'checkpoint-respawn',
		title: 'Respawn at the recorded checkpoint',
		agentId: 'respawn-agent',
		goal: 'Respawn the dead player at the recorded checkpoint.',
		world: baseWorld({ agentId: 'respawn-agent', health: 0, dead: true, checkpoint: { x: 9, y: 1, z: 9 } }),
		commands: [{ actionId: 'checkpoint-respawn', actionType: 'respawn', arguments: {} }],
		events: [],
		expected: { actionIds: ['checkpoint-respawn'], checkpoint: { x: 9, y: 1, z: 9 }, health: 20 },
		success: (state) => resultsSucceeded(state, ['checkpoint-respawn']) && samePosition(state.state?.position, { x: 9, y: 1, z: 9 }) && state.state?.health === 20,
	},
	{
		id: 'direct-message-wake',
		title: 'Wake an idle agent through a direct message',
		agentId: 'message-agent',
		goal: 'Send a direct message and process the recipient wake acknowledgement.',
		world: baseWorld({ agentId: 'message-agent', agents: { 'message-agent': { position: { x: 0, y: 1, z: 0 }, onGround: true }, [DM_RECIPIENT_ID]: { position: { x: 1, y: 1, z: 0 }, onGround: true } } }),
		commands: [{ actionId: 'direct-message-1', actionType: 'chat', arguments: { message: 'Please respond.', audience: 'direct', recipientId: DM_RECIPIENT_ID } }],
		events: [{ eventId: 'conversation-1', kind: 'agent_message', sourceId: 'message-agent', recipientId: DM_RECIPIENT_ID, wakeAcknowledged: true, processed: true }],
		expected: { actionIds: ['direct-message-1'], eventId: 'conversation-1', sourceId: 'message-agent', recipientId: DM_RECIPIENT_ID, wakeAcknowledged: true, processed: true },
		success: (state) => resultsSucceeded(state, ['direct-message-1']) && hasMatchingMessageEvent(state, { eventId: 'conversation-1', sourceId: 'message-agent', recipientId: DM_RECIPIENT_ID, wakeAcknowledged: true, processed: true }),
	},
	{
		id: 'stalled-action',
		title: 'Terminate a stalled action at its virtual tick deadline',
		agentId: 'stalled-agent',
		goal: 'Stop a navigation action that cannot reach its target before its deadline.',
		world: baseWorld({ agentId: 'stalled-agent' }),
		commands: [{ actionId: 'stall-navigation', actionType: 'navigate_to', arguments: { x: 50, y: 1, z: 0, tolerance: 0.1, sprint: false, timeoutMs: 1 } }],
		events: [],
		expected: { actionIds: ['stall-navigation'], terminalState: 'TIMED_OUT', reasonCode: 'ACTION_TIMEOUT' },
		success: (state) => resultsMatch(state, ['stall-navigation'], 'TIMED_OUT', 'ACTION_TIMEOUT'),
	},
	{
		id: 'invalid-decision-correction',
		title: 'Correct an invalid provider decision',
		agentId: 'correction-agent',
		goal: 'Accept the corrected decision after rejecting the invalid provider decision.',
		world: baseWorld({ agentId: 'correction-agent' }),
		commands: [],
		events: [{ eventId: 'invalid-decision-1', type: 'invalid_decision', accepted: false }, { eventId: 'corrected-decision-1', type: 'corrected_decision', invalidDecisionId: 'invalid-decision-1', accepted: true }],
		expected: { invalidDecisionId: 'invalid-decision-1', correctedDecisionId: 'corrected-decision-1', accepted: true },
		success: (state) => hasMatchingCorrection(state, { invalidDecisionId: 'invalid-decision-1', correctedDecisionId: 'corrected-decision-1', accepted: true }),
	},
];

export const SIMULATOR_SCENARIOS = deepFreeze(Object.fromEntries(scenarioList.map((scenario) => [scenario.id, scenario])));
export const SCENARIO_MANIFESTS = SIMULATOR_SCENARIOS;

export function listSimulatorScenarios() { return scenarioList.map((scenario) => scenario.id); }
export function getSimulatorScenario(id) { return Object.hasOwn(SIMULATOR_SCENARIOS, id) ? SIMULATOR_SCENARIOS[id] : undefined; }

export function runScenarioSuccess(manifestOrId, state) {
	const manifest = typeof manifestOrId === 'string' ? getSimulatorScenario(manifestOrId) : manifestOrId;
	if (!manifest || typeof manifest.success !== 'function') throw new TypeError('unknown simulator scenario manifest');
	return manifest.success(state ?? {}) === true;
}

function baseWorld({ agentId, agents = undefined, position = { x: 0, y: 1, z: 0 }, inventory = [], blocks = undefined, entities = [], ...playerOverrides }) {
	return {
		seed: 20260821,
		agents: agents ?? { [agentId]: { position, onGround: true, inventory: Array.isArray(inventory) ? { items: inventory } : inventory, ...playerOverrides } },
		blocks: blocks ?? [{ x: 0, y: 0, z: 0, blockId: 'minecraft:stone' }, { x: 1, y: 0, z: 0, blockId: 'minecraft:stone' }, { x: 2, y: 0, z: 0, blockId: 'minecraft:stone' }],
		items: [],
		entities,
	};
}

function resultsFor(state) { return Array.isArray(state?.results) ? state.results : state?.result ? [state.result] : []; }
function resultsSucceeded(state, actionIds) {
	const results = resultsFor(state);
	return actionIds.every((actionId) => results.some((result) => result.actionId === actionId && result.state === 'SUCCEEDED'));
}
function resultsMatch(state, actionIds, terminalState, reasonCode) {
	const results = resultsFor(state);
	return actionIds.every((actionId) => results.some((result) => result.actionId === actionId && result.state === terminalState && result.reasonCode === reasonCode));
}
function inventoryItems(state, key = 'inventoryAfter') { return state?.state?.[key] ?? state?.[key] ?? state?.inventory?.items ?? []; }
function itemCount(state, itemId) { return inventoryItems(state).filter((item) => item.itemId === itemId).reduce((total, item) => total + item.count, 0); }
function exactInventoryDelta(state, itemId, expectedDelta) {
	const before = (state?.state?.inventoryBefore ?? state?.inventoryBefore ?? []).filter((item) => item.itemId === itemId).reduce((total, item) => total + item.count, 0);
	const after = (state?.state?.inventoryAfter ?? state?.inventoryAfter ?? []).filter((item) => item.itemId === itemId).reduce((total, item) => total + item.count, 0);
	return after - before === expectedDelta;
}
function atTarget(state, target, tolerance) {
	const position = state?.state?.position ?? state?.position;
	return Boolean(position) && Math.hypot(position.x - target.x, position.y - target.y, position.z - target.z) <= tolerance;
}
function obstacleRouteMatches(state, expected) {
	const waypoints = state?.state?.waypoints ?? state?.waypoints;
	const obstacle = state?.state?.obstacle ?? state?.obstacle;
	if (!Array.isArray(waypoints) || waypoints.length !== expected.waypoints.length || !samePosition(obstacle, expected.obstacle) || obstacle.blockId !== expected.obstacle.blockId) return false;
	return expected.waypoints.every((waypoint, index) => samePosition(waypoints[index], waypoint) && !samePosition(waypoints[index], expected.obstacle));
}
function samePosition(left, right) { return Boolean(left && right) && left.x === right.x && left.y === right.y && left.z === right.z; }
function hasMatchingMessageEvent(state, expected) {
	return (state?.events ?? []).some((event) => event.eventId === expected.eventId && event.sourceId === expected.sourceId && event.recipientId === expected.recipientId && event.wakeAcknowledged === true && event.processed === true);
}
function hasMatchingCorrection(state, expected) {
	return (state?.events ?? []).some((event) => event.invalidDecisionId === expected.invalidDecisionId && event.correctedDecisionId === expected.correctedDecisionId && event.accepted === true);
}

function deepFreeze(value) {
	if (value === null || typeof value !== 'object' || Object.isFrozen(value)) return value;
	for (const child of Object.values(value)) deepFreeze(child);
	return Object.freeze(value);
}
