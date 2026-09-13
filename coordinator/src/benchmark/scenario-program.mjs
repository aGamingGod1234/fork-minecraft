import { DEFAULT_ARENA_SCRIPT_LIMITS, normalizeArenaScriptLimits } from '../arena-script/limits.mjs';
import { parseArenaScript } from '../arena-script/parser.mjs';
import { PLAYER_MEMBER_PRIMITIVES } from '../arena-script/minecraft-api.mjs';
import { validateAction } from '../schema.mjs';
import { SUPPORTED_SIMULATOR_ACTIONS } from '../simulator/action-runtime.mjs';
import { runScenarioSuccess } from '../simulator/simulator-scenarios.mjs';

const PRIMITIVE_MEMBERS = Object.freeze(Object.fromEntries(
	Object.entries(PLAYER_MEMBER_PRIMITIVES).map(([member, primitive]) => [primitive, member]),
));
const FORBIDDEN_JSON_KEYS = new Set(['__proto__', 'prototype', 'constructor']);
const POSITION_KEYS = ['x', 'y', 'z'];

/**
 * Compile a fixed simulator manifest into the same decision envelope a provider
 * would return. The source contains no provider-controlled identifiers or code.
 */
export function compileScenarioProgram(manifest, { limits = DEFAULT_ARENA_SCRIPT_LIMITS, summary = undefined } = {}) {
	const normalizedLimits = normalizeArenaScriptLimits(limits);
	const commands = normalizeManifestCommands(manifest, normalizedLimits);
	const lines = [
		'program.onUnhandledAttention("continue_and_notify");',
		...commands.map(({ member, arguments: args }) => `await player.${member}(${member === 'respawn' ? '' : stableJson(args)});`),
		'program.finish("scenario-complete");',
	];
	const source = lines.join('\n');
	if (Buffer.byteLength(source, 'utf8') > normalizedLimits.sourceBytes) {
		throw codedError('SOURCE_TOO_LARGE', `scenario program exceeds ${normalizedLimits.sourceBytes} UTF-8 bytes`);
	}
	const compiled = parseArenaScript(source, { limits: normalizedLimits });
	const decision = Object.freeze({
		summary: typeof summary === 'string' && summary.trim() ? summary : `Execute ${manifest.id ?? 'scenario'}`,
		directive: 'replace',
		source,
	});
	return deepFreeze({ source, decision, compiled, commands });
}

/** Return only the provider-compatible decision envelope. */
export function compileScenarioDecision(manifest, options = {}) {
	return compileScenarioProgram(manifest, options).decision;
}

/** Return only deterministic ArenaScript source for callers that do not need an envelope. */
export function compileScenarioSource(manifest, options = {}) {
	return compileScenarioProgram(manifest, options).source;
}

/**
 * Capture the pre-run state needed to prove inventory deltas, health changes,
 * and entity/block postconditions. Call this before dispatching any command.
 */
export function captureScenarioInitialSnapshot({ manifest, world, agentId = manifest?.agentId } = {}) {
	if (!manifest || typeof manifest !== 'object') throw new TypeError('manifest must be an object');
	assertWorld(world);
	if (typeof agentId !== 'string' || agentId.length === 0) throw new TypeError('agentId must be a non-empty string');
	const playerState = world.playerState(agentId);
	const inventory = world.inventories(agentId);
	const blockPositions = relevantBlockPositions(manifest);
	const blocks = blockPositions.map((position) => ({ ...position, block: world.blockAt(position.x, position.y, position.z) }));
	const entityIds = relevantEntityIds(manifest);
	const entities = entityIds.map((entityId) => ({
		entityId,
		entity: readEntity(world, entityId),
	}));
	const conversationEvents = typeof world.conversationEvents === 'function' ? world.conversationEvents() : [];
	return deepFreeze({
		agentId,
		playerState,
		inventory,
		blocks,
		entities,
		conversationEvents,
	});
}

/**
 * Project only authoritative simulator and bridge evidence into the shape
 * consumed by Task 4's runScenarioSuccess predicates.
 */
export function buildAuthoritativeScenarioOutcome({
	manifest,
	world,
	bridge = undefined,
	initialSnapshot = undefined,
	actionCommands = undefined,
	actionResults = undefined,
	events = undefined,
	allowEventOnly = true,
} = {}) {
	if (!manifest || typeof manifest !== 'object') throw new TypeError('manifest must be an object');
	assertWorld(world);
	const snapshot = initialSnapshot ?? captureScenarioInitialSnapshot({ manifest, world });
	const history = collectBridgeHistory({ bridge, actionCommands, actionResults });
	const commandMapping = mapDeclaredCommands(manifest.commands ?? [], history.commands, manifest.agentId);
	const results = mapResults(history.results, commandMapping);
	const player = world.playerState(manifest.agentId);
	const inventoryAfter = world.inventories(manifest.agentId);
	const declaredEvents = Array.isArray(manifest.events) ? manifest.events : [];
	const conversationEvents = typeof world.conversationEvents === 'function' ? world.conversationEvents() : [];
	const runtimeEvents = Array.isArray(events) ? events : [];
	const actualEvents = conversationEvents.length > 0 ? conversationEvents : (allowEventOnly ? runtimeEvents : []);
	const state = {
		position: clone(player.position),
		health: player.health,
		inventoryBefore: clone(snapshot.inventory?.items ?? []),
		inventoryAfter: clone(inventoryAfter.items ?? []),
		events: clone(actualEvents),
	};

	const expected = manifest.expected ?? {};
	const blockState = readExpectedBlockState(world, manifest, expected);
	if (blockState !== undefined) state.block = blockState;
	const targetState = readExpectedTargetState(world, expected);
	if (targetState !== undefined) state.target = targetState;
	const obstacleState = readExpectedObstacleState(world, manifest, expected);
	if (obstacleState !== undefined) state.obstacle = obstacleState;
	const waypoints = readPhysicalWaypoints({ manifest, expected, history, commandMapping });
	if (waypoints !== undefined) state.waypoints = waypoints;
	const hazard = readHazardState({ manifest, expected, snapshot, player, declaredEvents });
	if (hazard !== undefined) {
		state.hazard = hazard;
		state.reaction = reactionFor({ manifest, expected, player, hazard });
	}
	const mappedEvents = allowEventOnly && runtimeEvents.length > 0 && conversationEvents.length === 0 ? runtimeEvents : conversationEvents;
	const outcome = {
		results,
		state,
		events: clone(mappedEvents),
		commandMapping,
		physical: {
			player: clone(player),
			inventory: clone(inventoryAfter),
			blocks: clone(blockState === undefined ? [] : [blockState]),
			entities: clone(targetState === undefined ? [] : [targetState]),
			conversationEvents: clone(conversationEvents),
		},
	};
	return deepFreeze(outcome);
}

/** Evaluate only the authoritative outcome; provider summaries/finish flags are ignored. */
export function runAuthoritativeScenarioSuccess(input = {}) {
	const outcome = buildAuthoritativeScenarioOutcome(input);
	if (outcome.commandMapping.valid !== true && (input.manifest?.commands?.length ?? 0) > 0) return false;
	return runScenarioSuccess(input.manifest, outcome) === true;
}

// Names used by runner integrations; all aliases retain the same evidence boundary.
export const deriveScenarioOutcome = buildAuthoritativeScenarioOutcome;
export const scenarioOutcome = buildAuthoritativeScenarioOutcome;
export const evaluateScenarioSuccess = runAuthoritativeScenarioSuccess;

function normalizeManifestCommands(manifest, limits) {
	if (!manifest || typeof manifest !== 'object' || Array.isArray(manifest)) throw new TypeError('manifest must be an object');
	if (!Array.isArray(manifest.commands)) throw new TypeError('manifest.commands must be an array');
	if (manifest.commands.length > limits.commandsPerProgram) throw codedError('COMMAND_LIMIT', `manifest declares ${manifest.commands.length} commands; limit is ${limits.commandsPerProgram}`);
	const seenIds = new Set();
	return manifest.commands.map((command, index) => {
		if (!command || typeof command !== 'object' || Array.isArray(command)) throw new TypeError(`manifest.commands[${index}] must be an object`);
		const actionId = command.actionId;
		if (typeof actionId !== 'string' || actionId.length === 0) throw codedError('INVALID_COMMAND', `manifest.commands[${index}].actionId must be a non-empty string`);
		if (seenIds.has(actionId)) throw codedError('INVALID_COMMAND', `duplicate manifest command id '${actionId}'`);
		seenIds.add(actionId);
		const actionType = command.actionType ?? command.type;
		const member = PRIMITIVE_MEMBERS[actionType];
		if (!member) throw codedError('UNSUPPORTED_MAPPING', `unsupported ArenaScript mapping for action '${String(actionType)}'`);
		if (!SUPPORTED_SIMULATOR_ACTIONS.includes(actionType)) throw codedError('SIMULATOR_UNSUPPORTED_ACTION', `simulator does not support action '${actionType}'`);
		const args = command.arguments ?? command.args;
		assertJsonObject(args, `manifest.commands[${index}].arguments`);
		if (Object.hasOwn(args, 'type')) throw codedError('INVALID_COMMAND', 'arguments.type is reserved; use actionType');
		try { validateAction({ type: actionType, ...args }); }
		catch (error) { throw codedError(error.code ?? 'INVALID_COMMAND', error.message); }
		return Object.freeze({ actionId, actionType, member, arguments: deepClone(args) });
	});
}

function isCoordinatePosition(value) {
	return value !== null && typeof value === 'object' && Number.isFinite(value.x) && Number.isFinite(value.y) && Number.isFinite(value.z);
}

function stableJson(value) {
	assertJsonValue(value, 'arguments');
	return JSON.stringify(sortJson(value))
		.replace(/\u2028/g, '\\u2028')
		.replace(/\u2029/g, '\\u2029');
}

function assertJsonObject(value, label) {
	if (value === null || typeof value !== 'object' || Array.isArray(value) || !isPlainObject(value)) throw codedError('INVALID_JSON_ARGUMENTS', `${label} must be a plain JSON object`);
	assertJsonValue(value, label);
}

function assertJsonValue(value, label) {
	if (value === null || typeof value === 'string' || typeof value === 'boolean') return;
	if (typeof value === 'number') {
		if (!Number.isFinite(value)) throw codedError('INVALID_JSON_ARGUMENTS', `${label} contains a non-finite number`);
		return;
	}
	if (typeof value === 'undefined' || typeof value === 'bigint' || typeof value === 'function' || typeof value === 'symbol') throw codedError('INVALID_JSON_ARGUMENTS', `${label} contains a value that is not JSON serializable`);
	if (Array.isArray(value)) {
		for (let index = 0; index < value.length; index += 1) {
			if (!Object.hasOwn(value, index)) throw codedError('INVALID_JSON_ARGUMENTS', `${label} contains a sparse array`);
			assertJsonValue(value[index], `${label}[${index}]`);
		}
		return;
	}
	if (!isPlainObject(value)) throw codedError('INVALID_JSON_ARGUMENTS', `${label} must contain only plain JSON values`);
	for (const key of Object.keys(value)) {
		if (FORBIDDEN_JSON_KEYS.has(key)) throw codedError('INVALID_JSON_ARGUMENTS', `${label} contains forbidden key '${key}'`);
		assertJsonValue(value[key], `${label}.${key}`);
	}
}

function sortJson(value) {
	if (Array.isArray(value)) return value.map(sortJson);
	if (value !== null && typeof value === 'object') return Object.fromEntries(Object.keys(value).sort().map((key) => [key, sortJson(value[key])]));
	return value;
}

function collectBridgeHistory({ bridge, actionCommands, actionResults }) {
	const commands = actionCommands ?? bridge?.actionCommandHistory ?? bridge?.commandHistory?.commands ?? bridge?.sent
		?.filter((event) => event?.type === 'action_command')
		.map((event) => event.payload ?? event.command ?? event) ?? [];
	const results = actionResults ?? bridge?.actionResultHistory ?? bridge?.commandHistory?.results ?? bridge?.events
		?.filter((event) => event?.type === 'result' || event?.type === 'action_result')
		.map((event) => event.envelope?.payload ?? event.payload ?? event.result ?? event) ?? [];
	const observations = bridge?.observationHistory ?? bridge?.commandHistory?.observations ?? bridge?.events
		?.filter((event) => event?.type === 'observation') ?? [];
	return { commands: clone(commands), results: clone(results), observations: clone(observations) };
}

function mapDeclaredCommands(declaredCommands, actualCommands, agentId) {
	const declared = Array.isArray(declaredCommands) ? declaredCommands : [];
	const actual = (Array.isArray(actualCommands) ? actualCommands : []).filter((command) => commandAgentId(command) === undefined || commandAgentId(command) === agentId);
	const mappings = [];
	let valid = actual.length === declared.length;
	for (let index = 0; index < Math.min(actual.length, declared.length); index += 1) {
		const generatedActionId = actionIdOf(actual[index]);
		const actualType = actionTypeOf(actual[index]);
		if (!generatedActionId || actualType !== declared[index].actionType) {
			valid = false;
			continue;
		}
		mappings.push({ generatedActionId, declaredActionId: declared[index].actionId, actionType: actualType });
	}
	if (!valid) return { valid: false, mappings: [], reason: 'COMMAND_TYPE_OR_ORDER_MISMATCH' };
	return { valid: true, mappings };
}

function mapResults(actualResults, commandMapping) {
	if (!commandMapping.valid) return [];
	const byGeneratedId = new Map(commandMapping.mappings.map((mapping) => [mapping.generatedActionId, mapping]));
	const results = [];
	for (const result of actualResults) {
		const mapping = byGeneratedId.get(actionIdOf(result));
		if (!mapping || actionTypeOf(result) !== mapping.actionType) continue;
		results.push({ ...result, actionId: mapping.declaredActionId, commandId: mapping.declaredActionId, actionType: mapping.actionType });
	}
	return results;
}

function readPhysicalWaypoints({ manifest, expected, history, commandMapping }) {
	const waypoints = expected.waypoints;
	if (!Array.isArray(waypoints)) return undefined;
	const navigateCommands = (manifest.commands ?? []).filter((command) => command.actionType === 'navigate_to' || command.actionType === 'move_to');
	if (navigateCommands.length !== waypoints.length || !commandMapping.valid) return undefined;
	const observations = observationPositions(history);
	const mapped = commandMapping.mappings.filter((mapping) => mapping.actionType === 'navigate_to' || mapping.actionType === 'move_to');
	if (mapped.length !== waypoints.length || observations.length < waypoints.length) return undefined;
	const physical = [];
	for (let index = 0; index < waypoints.length; index += 1) {
		const position = observations.find((entry) => entry.actionId === mapped[index].generatedActionId)?.position;
		if (!position || !atTarget(position, waypoints[index], expected.tolerance ?? 0.2)) return undefined;
		physical.push(clone(waypoints[index]));
	}
	return physical;
}

function observationPositions(history) {
	const entries = Array.isArray(history?.observations) ? history.observations : [];
	return entries.map((entry) => {
		const payload = entry.envelope?.payload ?? entry.payload ?? entry;
		return { actionId: payload.lastResult?.actionId, position: payload.position };
	}).filter((entry) => entry.actionId && entry.position);
}

function readExpectedBlockState(world, manifest, expected) {
	const target = expected.tableBlock ?? (expected.position && expected.blockId ? { ...expected.position, blockId: expected.blockId, desiredState: expected.desiredState } : undefined) ?? firstCommandPosition(manifest, new Set(['place_block', 'craft_table']));
	if (!target) return undefined;
	const block = world.blockAt(target.x, target.y, target.z);
	return block === null ? null : clone(block);
}

function readExpectedObstacleState(world, manifest, expected) {
	if (!expected.obstacle) return undefined;
	return world.blockAt(expected.obstacle.x, expected.obstacle.y, expected.obstacle.z);
}

function readExpectedTargetState(world, expected) {
	if (typeof expected.targetId !== 'string') return undefined;
	const target = readEntity(world, expected.targetId);
	return target === undefined ? undefined : clone({ id: expected.targetId, ...target });
}

function readHazardState({ manifest, expected, snapshot, player, declaredEvents }) {
	if (typeof expected.hazardEventId !== 'string' || expected.requiresDamage !== true) return undefined;
	const declaration = declaredEvents.find((event) => event.eventId === expected.hazardEventId);
	if (!declaration) return undefined;
	return {
		...clone(declaration),
		healthBefore: snapshot.playerState?.health,
		healthAfter: player.health,
	};
}

function reactionFor({ manifest, expected, player, hazard }) {
	if (player.dead) return 'respawn';
	const target = expected.position ?? (manifest.commands ?? []).find((command) => command.actionType === 'navigate_to' || command.actionType === 'move_to')?.arguments;
	if (expected.reaction === 'leave_hazard' && target && atTarget(player.position, target, target.tolerance ?? expected.tolerance ?? 0.2)) return 'leave_hazard';
	return undefined;
}

function relevantBlockPositions(manifest) {
	const positions = new Map();
	const add = (value) => {
		if (!value || !POSITION_KEYS.every((key) => Number.isFinite(value[key]))) return;
		const position = { x: Math.trunc(value.x), y: Math.trunc(value.y), z: Math.trunc(value.z) };
		positions.set(`${position.x},${position.y},${position.z}`, position);
	};
	for (const block of manifest.world?.blocks ?? []) add(block);
	for (const command of manifest.commands ?? []) add(command.arguments);
	for (const key of ['tableBlock', 'obstacle', 'position']) add(manifest.expected?.[key]);
	return [...positions.values()];
}

function relevantEntityIds(manifest) {
	const ids = new Set((manifest.world?.entities ?? []).map((entity) => entity.id ?? entity.uuid ?? entity.stableId).filter((id) => typeof id === 'string'));
	if (typeof manifest.expected?.targetId === 'string') ids.add(manifest.expected.targetId);
	return [...ids];
}

function firstCommandPosition(manifest, actionTypes) {
	const command = (manifest.commands ?? []).find((entry) => actionTypes.has(entry.actionType));
	return command?.arguments;
}

function readEntity(world, entityId) {
	try { return world.entityState(entityId); }
	catch { return undefined; }
}

function actionIdOf(value) { return value?.actionId ?? value?.commandId; }
function actionTypeOf(value) { return value?.actionType ?? value?.action?.type ?? value?.type; }
function commandAgentId(value) { return value?.agentId ?? value?.envelope?.agentId; }
function atTarget(position, target, tolerance) { return Boolean(position && target) && Math.hypot(position.x - target.x, position.y - target.y, position.z - target.z) <= tolerance; }
function assertWorld(world) {
	for (const method of ['playerState', 'inventories', 'blockAt']) if (typeof world?.[method] !== 'function') throw new TypeError(`world.${method} must be a function`);
}
function isPlainObject(value) { const prototype = Object.getPrototypeOf(value); return prototype === Object.prototype || prototype === null; }
function deepClone(value) { return value === undefined ? undefined : structuredClone(value); }
function clone(value) { return deepClone(value); }
function deepFreeze(value) {
	if (value === null || typeof value !== 'object' || Object.isFrozen(value)) return value;
	for (const child of Object.values(value)) deepFreeze(child);
	return Object.freeze(value);
}
function codedError(code, message) { return Object.assign(new Error(`${code}: ${message}`), { code }); }
