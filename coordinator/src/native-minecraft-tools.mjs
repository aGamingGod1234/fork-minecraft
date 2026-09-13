import {
	ACTION_FIELDS,
	OPTIONAL_ACTION_FIELDS,
	CONTROL_BRANCH_CONDITIONS,
	MAX_CHAT_LENGTH,
	MAX_DURATION_MS,
	MAX_IDENTIFIER_LENGTH,
	MIN_DURATION_MS,
} from './constants.mjs';
import { MAX_ACTION_ARGUMENT_BYTES, validateAction } from './schema.mjs';
import { ARENA_SCRIPT_API_REFERENCE } from './prompts.mjs';

const MAX_TOOL_RESULT_BYTES = 16_384;
const COORDINATE_LIMIT = 30_000_000;
const MAX_SEQUENCE_ACTIONS = 8;
const MAX_LOOK_AROUND_STEPS = 8;
const MAX_LOOK_AROUND_TICKS = 20;
const MAX_PROGRAM_SOURCE_BYTES = 65_536;
const NATIVE_ACTION_TYPES = Object.freeze(Object.keys(ACTION_FIELDS));
export const INSPECTION_SECTIONS = Object.freeze(['inventory', 'menu', 'entities', 'blocks', 'landmarks', 'nearby_containers', 'item', 'block', 'events', 'recipes', 'mechanics']);

export function minecraftCapabilities({ section = 'all' } = {}) {
	if (section === 'program') return { version: 1, section: 'program', engine: 'ArenaScript', reference: ARENA_SCRIPT_API_REFERENCE };
	return {
		version: 1,
		actions: Object.entries(ACTION_FIELDS).map(([actionType, fields]) => ({ actionType, fields: [...fields], requiredFields: fields.filter((field) => !(OPTIONAL_ACTION_FIELDS[actionType] ?? []).includes(field)), optionalFields: [...(OPTIONAL_ACTION_FIELDS[actionType] ?? [])] })),
		controlConditions: { ...CONTROL_BRANCH_CONDITIONS },
		inspectionSections: [...INSPECTION_SECTIONS],
		programReference: { tool: 'capabilities', arguments: { section: 'program' } },
		limits: { sequenceActions: MAX_SEQUENCE_ACTIONS, inspectionPage: 32, resultBytes: MAX_TOOL_RESULT_BYTES, actionArgumentBytes: MAX_ACTION_ARGUMENT_BYTES, programSourceBytes: MAX_PROGRAM_SOURCE_BYTES, programActions: 256, programTimeoutMs: 120_000 },
	};
}

export const NATIVE_AGENT_INSTRUCTIONS = `You control one live Minecraft player and choose every action.

Choose the next step from current observations, the user's goal, and your own reasoning. A death does not change the active goal. Historical inventory and death records describe past evidence.

Use capabilities for supported fields, observe for a fresh sample, and inspect for focused pages. Respect freshness and coverage: omitted or unobserved facts are unknown. exploreFrontier returns candidates; you choose a destination and call moveTo. Use control for precise inputs and sequence for safe steps that need no new facts. startAction returns a handle; actionStatus, cancelAction, and replaceAction require its exact identity. Use act with control_sequence for bounded model-authored tick programs. notebook stores your notes; queryMemory retrieves notes and factual receipts. Mine only observed blocks with their exact blockId. goalSpec is the immutable completion contract. finish requests factual verification. Never claim an effect without evidence. conversation_only uses say only. Plain text is not visible. Nearby speech should be brief; speech playback is asynchronous.`;

export const MINECRAFT_DYNAMIC_TOOLS = Object.freeze([
	tool('observe', 'Request a fresh player observation. Read freshness and coverage; an unavailable freshness barrier returns explicitly stale cached facts.', objectSchema({})),
	tool('capabilities', 'List action fields, query sections, limits, and runtime support. Request section program for the shared ArenaScript language and API reference before writing a program.', objectSchema({ section: { type: 'string', enum: ['all', 'program'] } })),
	tool('inspect', 'Request a focused page of player-accessible facts. Item queries need a slot; block queries need visible x/y/z coordinates. Read coverage and freshness.', objectSchema({
		section: { type: 'string', enum: INSPECTION_SECTIONS }, offset: integerSchema(0, 4_096), limit: integerSchema(1, 32),
		slot: integerSchema(0, 255), x: integerSchema(-COORDINATE_LIMIT, COORDINATE_LIMIT), y: integerSchema(-2_048, 2_048), z: integerSchema(-COORDINATE_LIMIT, COORDINATE_LIMIT),
		afterSequence: integerSchema(0, Number.MAX_SAFE_INTEGER),
		recipeId: { type: 'string', minLength: 1, maxLength: 256, pattern: '^[a-z0-9_.-]+:[a-z0-9_./-]+$' },
	}, ['section'])),
	tool('actionStatus', 'Inspect the active action or a retained terminal receipt without changing the player.', objectSchema({ actionId: { type: 'string', minLength: 1, maxLength: 128 } })),
	tool('cancelAction', 'Cancel the exact active handle and wait for its authoritative terminal result. A stale handle cannot cancel another action.', objectSchema({ actionId: { type: 'string', minLength: 1, maxLength: 128 }, goalRevision: integerSchema(0, Number.MAX_SAFE_INTEGER) }, ['actionId', 'goalRevision'])),
	tool('replaceAction', 'Cancel the exact active handle, wait for acknowledgement, then execute your replacement. No replacement runs after uncertain cancellation.', objectSchema({
		actionId: { type: 'string', minLength: 1, maxLength: 128 }, goalRevision: integerSchema(0, Number.MAX_SAFE_INTEGER),
		actionType: { type: 'string', enum: NATIVE_ACTION_TYPES }, arguments: { type: 'object' },
	}, ['actionId', 'goalRevision', 'actionType', 'arguments'])),
	tool('startAction', 'Start one model-chosen action and return its handle immediately. Poll actionStatus for the factual result or cancel the exact handle.', objectSchema({ actionType: { type: 'string', enum: NATIVE_ACTION_TYPES }, arguments: { type: 'object' } }, ['actionType', 'arguments'])),
	tool('notebook', 'Save or replace one model-written note of up to 2048 characters in this agent and world. Notes are hypotheses or plans, never authoritative game evidence.', objectSchema({ key: { type: 'string', minLength: 1, maxLength: 128 }, text: { type: 'string', minLength: 1, maxLength: 2048 } }, ['key', 'text'])),
	tool('queryMemory', 'Read this agent and world\'s saved notes and action receipts, including unresolved dispatches. Continue pages with nextOffset. Historical receipts do not establish current world state.', objectSchema({ kind: { type: 'string', enum: ['all', 'notes', 'receipts', 'unresolved'] }, text: { type: 'string', minLength: 1, maxLength: 256 }, offset: integerSchema(0, Number.MAX_SAFE_INTEGER), limit: integerSchema(1, 64) })),
	tool('runProgram', 'Run bounded ArenaScript that you author using player actions, observed facts, inspections, memory, and explicit watchers. Returns at completion or attention; no other model chooses its behavior.', objectSchema({ source: { type: 'string', minLength: 1, maxLength: MAX_PROGRAM_SOURCE_BYTES }, maxActions: integerSchema(1, 256), timeoutMs: integerSchema(1, 120_000) }, ['source'])),
	tool('lookAround', 'Turn the player through 2 to 8 short camera steps; call observe afterward to inspect the newly visible landmarks.', objectSchema({
		centerYaw: numberSchema(-180, 180),
		pitch: numberSchema(-90, 90),
		steps: integerSchema(2, MAX_LOOK_AROUND_STEPS),
		ticksPerStep: integerSchema(1, MAX_LOOK_AROUND_TICKS),
	}, ['centerYaw', 'pitch', 'steps', 'ticksPerStep'])),
	tool('control', 'Hold one complete player input frame for 1 to 200 server ticks. Use for precise movement, jumps, attacks, item use, view, and hotbar control.', objectSchema({
		forward: numberSchema(-1, 1),
		strafe: numberSchema(-1, 1),
		jump: { type: 'boolean' },
		sneak: { type: 'boolean' },
		sprint: { type: 'boolean' },
		attack: { type: 'boolean' },
		use: { type: 'boolean' },
		yaw: numberSchema(-180, 180),
		pitch: numberSchema(-90, 90),
		selectedSlot: integerSchema(0, 8),
		hand: { type: 'string', enum: ['main', 'off'] },
		ticks: integerSchema(1, 200),
	}, ['forward', 'strafe', 'jump', 'sneak', 'sprint', 'attack', 'use', 'yaw', 'pitch', 'selectedSlot', 'hand', 'ticks'])),
	tool('moveTo', 'Navigate toward one short, confirmed waypoint through bounded loaded safe waypoints; use control for ordinary exploration.', objectSchema({
		x: numberSchema(-COORDINATE_LIMIT, COORDINATE_LIMIT),
		y: numberSchema(-2_048, 2_048),
		z: numberSchema(-COORDINATE_LIMIT, COORDINATE_LIMIT),
		tolerance: numberSchema(0.01, 16),
		sprint: { type: 'boolean' },
		timeoutMs: integerSchema(1, 120_000),
	}, ['x', 'y', 'z'])),
	tool('exploreFrontier', 'List factual observed or unknown adjacent-space candidates. This tool never chooses or executes a destination; choose explicitly with moveTo.', objectSchema({
		radius: integerSchema(8, 32),
		limit: integerSchema(1, 64),
		blockId: { type: 'string', minLength: 1, maxLength: MAX_IDENTIFIER_LENGTH },
	})),
	tool('mine', 'Mine one observed, visible, in-range block coordinate with its exact current blockId.', objectSchema({
		x: integerSchema(-COORDINATE_LIMIT, COORDINATE_LIMIT),
		y: integerSchema(-2_048, 2_048),
		z: integerSchema(-COORDINATE_LIMIT, COORDINATE_LIMIT),
		expectedBlockId: { type: 'string', minLength: 1, maxLength: MAX_IDENTIFIER_LENGTH },
		timeoutMs: integerSchema(1, 120_000),
	}, ['x', 'y', 'z', 'expectedBlockId'])),
	tool('say', 'Send public chat, a private message, or nearby proximity speech.', objectSchema({
		message: { type: 'string', minLength: 1, maxLength: MAX_CHAT_LENGTH },
		audience: { type: 'string', enum: ['public', 'direct', 'proximity'] },
		recipientId: { type: 'string', minLength: 1, maxLength: MAX_IDENTIFIER_LENGTH },
	}, ['message'])),
	tool('wait', 'Pause briefly and wait for the body result.', objectSchema({
		durationMs: integerSchema(MIN_DURATION_MS, MAX_DURATION_MS),
	}, ['durationMs'])),
	tool('act', 'Execute one supported advanced player action. Supply exactly the fields required by that actionType.', objectSchema({
		actionType: { type: 'string', enum: NATIVE_ACTION_TYPES },
		arguments: { type: 'object' },
	}, ['actionType', 'arguments'])),
	tool('sequence', 'Prefer sequence for safe 2+ action chains. Execute 2 to 8 exact model-authored actions in order, stopping on the first factual failure; use separate calls when a later step needs fresh facts.', objectSchema({
		actions: {
			type: 'array', minItems: 2, maxItems: MAX_SEQUENCE_ACTIONS,
			items: objectSchema({ actionType: { type: 'string', enum: NATIVE_ACTION_TYPES }, arguments: { type: 'object' } }, ['actionType', 'arguments']),
		},
	}, ['actions'])),
	tool('finish', 'Ask Minecraft to verify the immutable active goal. A failed check keeps the goal active.', objectSchema({
		summary: { type: 'string', minLength: 1, maxLength: 512 },
	}, ['summary'])),
]);

export function normalizeMinecraftToolCall(name, value) {
	const args = requireObject(value);
	switch (name) {
		case 'observe':
			requireExactKeys(args, []);
			return { kind: 'observe' };
		case 'capabilities':
			requireExactKeys(args, ['section']);
			if (args.section !== undefined && !['all', 'program'].includes(args.section)) invalid('capability section is not supported');
			return { kind: 'capabilities', ...(args.section === undefined ? {} : { section: args.section }) };
		case 'inspect': {
			requireExactKeys(args, ['section', 'offset', 'limit', 'slot', 'x', 'y', 'z', 'afterSequence', 'recipeId']);
			if (!INSPECTION_SECTIONS.includes(args.section)) invalid('section is not supported');
			const query = { kind: 'inspect', section: args.section, offset: optionalInteger(args.offset, 0, 'offset', 0, 4_096), limit: optionalInteger(args.limit, 32, 'limit', 1, 32) };
			if (args.section === 'item') query.slot = integer(args.slot, 'slot', 0, 255);
			else if (args.slot !== undefined) invalid('slot is only valid for item inspection');
			if (args.section === 'block') {
				query.x = integer(args.x, 'x', -COORDINATE_LIMIT, COORDINATE_LIMIT);
				query.y = integer(args.y, 'y', -2_048, 2_048);
				query.z = integer(args.z, 'z', -COORDINATE_LIMIT, COORDINATE_LIMIT);
			} else if (args.x !== undefined || args.y !== undefined || args.z !== undefined) invalid('coordinates are only valid for block inspection');
			if (args.afterSequence !== undefined) {
				if (args.section !== 'events') invalid('afterSequence is only valid for event inspection');
				query.afterSequence = integer(args.afterSequence, 'afterSequence', 0, Number.MAX_SAFE_INTEGER);
			}
			if (args.recipeId !== undefined) {
				if (args.section !== 'recipes' || typeof args.recipeId !== 'string' || args.recipeId.length > 256 || !/^[a-z0-9_.-]+:[a-z0-9_./-]+$/.test(args.recipeId)) invalid('recipeId must be a namespaced recipe identifier for recipe inspection');
				query.recipeId = args.recipeId;
			}
			return query;
		}
		case 'actionStatus':
			requireExactKeys(args, ['actionId']);
			return { kind: 'action_status', ...(args.actionId === undefined ? {} : { actionId: boundedText(args.actionId, 'actionId', 128) }) };
		case 'cancelAction':
			requireExactKeys(args, ['actionId', 'goalRevision']);
			return { kind: 'cancel_action', actionId: boundedText(args.actionId, 'actionId', 128), goalRevision: integer(args.goalRevision, 'goalRevision', 0, Number.MAX_SAFE_INTEGER) };
		case 'replaceAction': {
			requireExactKeys(args, ['actionId', 'goalRevision', 'actionType', 'arguments']);
			const action = normalizeMinecraftToolCall('act', { actionType: args.actionType, arguments: args.arguments });
			return { ...action, kind: 'replace_action', actionId: boundedText(args.actionId, 'actionId', 128), goalRevision: integer(args.goalRevision, 'goalRevision', 0, Number.MAX_SAFE_INTEGER) };
		}
		case 'startAction': {
			const action = normalizeMinecraftToolCall('act', args);
			return { ...action, kind: 'start_action' };
		}
		case 'notebook':
			requireExactKeys(args, ['key', 'text']);
			return { kind: 'notebook', key: boundedText(args.key, 'key', 128), text: boundedText(args.text, 'text', 2048) };
		case 'queryMemory':
			requireExactKeys(args, ['kind', 'text', 'limit', 'offset']);
			if (args.kind !== undefined && !['all', 'notes', 'receipts', 'unresolved'].includes(args.kind)) invalid('kind is not supported');
			return { kind: 'query_memory', memoryKind: args.kind ?? 'all', offset: optionalInteger(args.offset, 0, 'offset', 0, Number.MAX_SAFE_INTEGER), limit: optionalInteger(args.limit, 20, 'limit', 1, 64), ...(args.text === undefined ? {} : { text: boundedText(args.text, 'text', 256) }) };
		case 'runProgram': {
			requireExactKeys(args, ['source', 'maxActions', 'timeoutMs']);
			const source = boundedText(args.source, 'source', MAX_PROGRAM_SOURCE_BYTES);
			if (Buffer.byteLength(source, 'utf8') > MAX_PROGRAM_SOURCE_BYTES) invalid('source must fit 65536 UTF-8 bytes');
			return { kind: 'run_program', source, maxActions: optionalInteger(args.maxActions, 64, 'maxActions', 1, 256), timeoutMs: optionalInteger(args.timeoutMs, 30_000, 'timeoutMs', 1, 120_000) };
		}
		case 'lookAround':
			requireExactKeys(args, ['centerYaw', 'pitch', 'steps', 'ticksPerStep']);
			return {
				kind: 'lookAround',
				centerYaw: finiteNumber(args.centerYaw, 'centerYaw', -180, 180),
				pitch: finiteNumber(args.pitch, 'pitch', -90, 90),
				steps: integer(args.steps, 'steps', 2, MAX_LOOK_AROUND_STEPS),
				ticksPerStep: integer(args.ticksPerStep, 'ticksPerStep', 1, MAX_LOOK_AROUND_TICKS),
			};
		case 'control':
			requireExactKeys(args, ['forward', 'strafe', 'jump', 'sneak', 'sprint', 'attack', 'use', 'yaw', 'pitch', 'selectedSlot', 'hand', 'ticks']);
			try {
				return {
					kind: 'action',
					actionType: 'control',
					arguments: stripActionType(validateAction({ type: 'control', ...args })),
				};
			} catch (error) {
				invalid(error?.message ?? 'invalid control arguments');
			}
			break;
		case 'moveTo':
			requireExactKeys(args, ['x', 'y', 'z', 'tolerance', 'sprint', 'timeoutMs']);
			return {
				kind: 'action',
				actionType: 'navigate_to',
				arguments: {
					x: finiteNumber(args.x, 'x', -COORDINATE_LIMIT, COORDINATE_LIMIT),
					y: finiteNumber(args.y, 'y', -2_048, 2_048),
					z: finiteNumber(args.z, 'z', -COORDINATE_LIMIT, COORDINATE_LIMIT),
					tolerance: optionalNumber(args.tolerance, 1, 'tolerance', 0.01, 16),
					sprint: optionalBoolean(args.sprint, true, 'sprint'),
					timeoutMs: optionalInteger(args.timeoutMs, 30_000, 'timeoutMs', 1, 120_000),
				},
			};
		case 'exploreFrontier': {
			requireExactKeys(args, ['radius', 'limit', 'blockId']);
			return {
				kind: 'explore_frontier',
				arguments: {
					radius: optionalInteger(args.radius, 24, 'radius', 8, 32),
					limit: optionalInteger(args.limit, 32, 'limit', 1, 64),
					...(args.blockId === undefined ? {} : { blockId: boundedText(args.blockId, 'blockId', MAX_IDENTIFIER_LENGTH) }),
				},
			};
		}
		case 'mine':
			requireExactKeys(args, ['x', 'y', 'z', 'expectedBlockId', 'timeoutMs']);
			try {
				const action = validateAction({
					type: 'break_block',
					x: integer(args.x, 'x', -COORDINATE_LIMIT, COORDINATE_LIMIT),
					y: integer(args.y, 'y', -2_048, 2_048),
					z: integer(args.z, 'z', -COORDINATE_LIMIT, COORDINATE_LIMIT),
					expectedBlockId: boundedText(args.expectedBlockId, 'expectedBlockId', MAX_IDENTIFIER_LENGTH),
					timeoutMs: optionalInteger(args.timeoutMs, 15_000, 'timeoutMs', 1, 120_000),
				});
				return { kind: 'action', actionType: action.type, arguments: stripActionType(action) };
			} catch (error) {
				invalid(error?.message ?? 'invalid mining arguments');
			}
			break;
		case 'say': {
			requireExactKeys(args, ['message', 'audience', 'recipientId']);
			const message = boundedText(args.message, 'message', MAX_CHAT_LENGTH);
			const recipientId = args.recipientId === undefined ? undefined : boundedText(args.recipientId, 'recipientId', MAX_IDENTIFIER_LENGTH);
			const audience = args.audience === undefined
				? recipientId === undefined ? 'public' : 'direct'
				: args.audience;
			if (!['public', 'direct', 'proximity'].includes(audience)) invalid('audience is not supported');
			if (audience === 'direct' && recipientId === undefined) invalid('direct speech requires recipientId');
			if (audience !== 'direct' && recipientId !== undefined) invalid(`${audience} speech cannot use recipientId`);
			return { kind: 'action', actionType: 'chat', arguments: audience === 'direct'
				? { message, audience, recipientId }
				: { message, audience } };
		}
		case 'wait':
			requireExactKeys(args, ['durationMs']);
			return { kind: 'action', actionType: 'wait', arguments: { durationMs: integer(args.durationMs, 'durationMs', MIN_DURATION_MS, MAX_DURATION_MS) } };
		case 'act': {
			requireExactKeys(args, ['actionType', 'arguments']);
			if (typeof args.actionType !== 'string' || !NATIVE_ACTION_TYPES.includes(args.actionType)) invalid('actionType is not supported');
			const actionArguments = requireObject(args.arguments);
			if (Object.hasOwn(actionArguments, 'type')) invalid('arguments.type is reserved; use actionType');
			if (args.actionType === 'break_block') {
				const normalized = normalizeMinecraftToolCall('mine', actionArguments);
				return { kind: 'action', actionType: normalized.actionType, arguments: normalized.arguments };
			}
			try {
				const normalizedArguments = stripActionType(validateAction({ type: args.actionType, ...actionArguments }));
				return { kind: 'action', actionType: args.actionType, arguments: normalizedArguments };
			} catch (error) {
				invalid(error?.message ?? 'invalid action arguments');
			}
			break;
		}
		case 'sequence': {
			requireExactKeys(args, ['actions']);
			if (!Array.isArray(args.actions) || args.actions.length < 2 || args.actions.length > MAX_SEQUENCE_ACTIONS) {
				invalid(`actions must contain 2 to ${MAX_SEQUENCE_ACTIONS} entries`);
			}
			return {
				kind: 'sequence',
				actions: args.actions.map(normalizeSequenceAction),
			};
		}
		case 'finish':
			requireExactKeys(args, ['summary']);
			return {
				kind: 'finish',
				summary: boundedText(args.summary, 'summary', 512),
			};
		default:
			throw codedError('UNKNOWN_MINECRAFT_TOOL', `Unknown Minecraft tool '${String(name)}'`);
	}
}

function normalizeSequenceAction(value) {
	const action = requireObject(value);
	requireExactKeys(action, ['actionType', 'arguments']);
	if (action.actionType === 'navigate_to') {
		const normalized = normalizeMinecraftToolCall('moveTo', action.arguments);
		return { actionType: normalized.actionType, arguments: normalized.arguments };
	}
	if (action.actionType === 'break_block') {
		const normalized = normalizeMinecraftToolCall('mine', action.arguments);
		return { actionType: normalized.actionType, arguments: normalized.arguments };
	}
	const normalized = normalizeMinecraftToolCall('act', action);
	return { actionType: normalized.actionType, arguments: normalized.arguments };
}

export function toolResultContent(value, success = true) {
	let text = JSON.stringify(value ?? null);
	if (Buffer.byteLength(text, 'utf8') > MAX_TOOL_RESULT_BYTES) {
		const candidates = [
			...(Array.isArray(value?.entries) ? [compactInspectionResult(value)] : []),
			...(isSequenceResult(value) ? [compactSequenceResult(value)] : []),
			...(Array.isArray(value?.receipts) && typeof value?.programId === 'string' ? [compactProgramResult(value)] : []),
			compactToolResult(value),
			{ state: 'TRUNCATED', ...resultMetadata(value), ...survivalFacts(value, 8), detail: 'Details exceeded the result limit. Use inspect for focused pages.' },
			{ state: 'TRUNCATED', ...resultMetadata(value), ...survivalFacts(value, 2), detail: 'Details exceeded the result limit. Use inspect for focused pages.' },
			{ state: 'TRUNCATED', detail: 'Tool result exceeded the coordinator limit. Use inspect for focused facts; omitted data is unknown.' },
		];
		for (const candidate of candidates) {
			text = JSON.stringify(candidate);
			if (Buffer.byteLength(text, 'utf8') <= MAX_TOOL_RESULT_BYTES) break;
		}
	}
	return { success, contentItems: [{ type: 'inputText', text }] };
}

function compactToolResult(value) {
	if (value === null || typeof value !== 'object' || Array.isArray(value)) {
		return { state: 'TRUNCATED', detail: 'Tool result exceeded the coordinator limit. Call observe for fresh compact facts.' };
	}
	const observation = value.observation !== null && typeof value.observation === 'object' ? value.observation : value;
	const hasMinecraftFacts = observation.player !== undefined
		|| observation.inventory !== undefined
		|| observation.death !== undefined
		|| observation.recovery !== undefined
		|| value.state !== undefined
		|| value.reasonCode !== undefined;
	if (!hasMinecraftFacts) {
		return { state: 'TRUNCATED', detail: 'Tool result exceeded the coordinator limit. Call observe for fresh compact facts.' };
	}
	return {
		...resultMetadata(value),
		truncated: true,
		detail: 'Observation details were omitted by the result limit. Use inspect for focused pages.',
		...(value.state === undefined ? {} : { state: value.state }),
		...(value.reasonCode === undefined ? {} : { reasonCode: value.reasonCode }),
		...(value.eventSequence === undefined ? {} : { eventSequence: value.eventSequence }),
		...(value.goal === undefined ? {} : { goal: value.goal }),
		observation: {
			...resultMetadata(observation),
			player: observation.player ?? {},
			inventory: { items: asToolArray(observation.inventory?.items).slice(0, 16) },
			...(observation.position === undefined ? {} : { position: observation.position }),
			...(observation.velocity === undefined ? {} : { velocity: observation.velocity }),
			...(observation.view === undefined ? {} : { view: observation.view }),
			...(observation.interaction === undefined ? {} : { interaction: compactInteraction(observation.interaction) }),
			resultCoverage: { inventory: { retained: Math.min(asToolArray(observation.inventory?.items).length, 16), availableInSnapshot: asToolArray(observation.inventory?.items).length }, omittedSections: ['blocks', 'landmarks', 'entities', 'nearbyContainers'].filter((section) => observation[section] !== undefined) },
			...(observation.death === undefined ? {} : { death: observation.death }),
			...(observation.recovery === undefined ? {} : { recovery: compactRecovery(observation.recovery, 8) }),
			...(observation.failureClass === undefined ? {} : { failureClass: observation.failureClass }),
			...(observation.lastResult === undefined ? {} : { lastResult: compactLastResult(observation.lastResult) }),
			...(observation.world === undefined ? {} : { world: compactWorld(observation.world) }),
			...(observation.continuity === undefined ? {} : { continuity: observation.continuity }),
			...(observation.lastLiveInventory === undefined ? {} : { lastLiveInventory: observation.lastLiveInventory }),
		},
		...survivalFacts(value),
	};
}

function resultMetadata(value) {
	if (value === null || typeof value !== 'object') return {};
	return Object.fromEntries(['actionId', 'goalRevision', 'eventSequence', 'freshness', 'coverage', 'revision', 'sectionRevisions', 'observedAtEpochMs', 'executionSettings', 'unresolvedActions'].filter((key) => value[key] !== undefined).map((key) => [key, value[key]]));
}

function compactInteraction(interaction) {
	if (interaction === null || typeof interaction !== 'object') return interaction;
	const menu = interaction.menu;
	return { ...interaction, ...(menu == null ? {} : { menu: { ...menu, ...(Array.isArray(menu.slots) ? { slots: menu.slots.slice(0, 8), resultCoverage: { retained: Math.min(menu.slots.length, 8), availableInSnapshot: menu.slots.length } } : {}) } }) };
}

function compactInspectionResult(value) {
	const result = { ...value, entries: [], truncated: true, detail: 'Inspection entries exceeded the result limit; continue at nextOffset.', coverage: { ...value.coverage, resultTruncated: true } };
	for (const entry of value.entries) {
		const candidate = { ...result, entries: [...result.entries, entry] };
		if (Buffer.byteLength(JSON.stringify(candidate), 'utf8') > MAX_TOOL_RESULT_BYTES - 128) break;
		result.entries.push(entry);
	}
	const offset = Number.isSafeInteger(value.offset) ? value.offset : Number.isSafeInteger(value.coverage?.offset) ? value.coverage.offset : 0;
	result.nextOffset = offset + result.entries.length;
	result.coverage.returned = result.entries.length;
	result.coverage.nextOffset = result.nextOffset;
	result.coverage.complete = false;
	if (result.entries.length === 0) {
		result.reasonCode = 'ENTRY_EXCEEDS_RESULT_LIMIT';
		result.detail = 'One inspection entry exceeds the result limit. Its contents remain unknown.';
		result.nextOffset = null;
		result.coverage.nextOffset = null;
	}
	return result;
}

function asToolArray(value) {
	return Array.isArray(value) ? value : [];
}

function survivalFacts(value, maxStacks = 16) {
	const observation = value?.observation !== null && typeof value?.observation === 'object' ? value.observation : value;
	return {
		...(observation?.death === undefined ? {} : { death: observation.death }),
		...(observation?.recovery === undefined ? {} : { recovery: compactRecovery(observation.recovery, maxStacks) }),
		...(value?.recovery === undefined ? {} : { recovery: compactRecovery(value.recovery, maxStacks) }),
		...(observation?.failureClass === undefined && value?.failureClass === undefined ? {} : { failureClass: observation?.failureClass ?? value.failureClass }),
	};
}

function compactRecovery(recovery, maxStacks = 16) {
	if (recovery === null || typeof recovery !== 'object') return recovery;
	const lostCap = Math.min(16, maxStacks);
	const haveCap = Math.min(32, Math.max(2, maxStacks * 2));
	return {
		...(recovery.lastDeath === undefined ? {} : { lastDeath: recovery.lastDeath }),
		...(Array.isArray(recovery.lastLostInventory) ? { lastLostInventory: recovery.lastLostInventory.slice(0, lostCap) } : {}),
		...(Array.isArray(recovery.alreadyHave) ? { alreadyHave: recovery.alreadyHave.slice(-haveCap) } : {}),
		...(typeof recovery.facts === 'string' ? { facts: recovery.facts.slice(0, maxStacks <= 2 ? 160 : 512) } : {}),
	};
}

function compactLastResult(lastResult) {
	if (lastResult === null || typeof lastResult !== 'object') return lastResult;
	return {
		...(lastResult.state === undefined ? {} : { state: lastResult.state }),
		...(lastResult.reasonCode === undefined ? {} : { reasonCode: lastResult.reasonCode }),
	};
}

function compactWorld(world) {
	if (world === null || typeof world !== 'object') return world;
	return {
		...(world.worldId === undefined ? {} : { worldId: world.worldId }),
		...(world.gameTime === undefined ? {} : { gameTime: world.gameTime }),
		...(world.dimension === undefined ? {} : { dimension: world.dimension }),
		...(world.dimensionId === undefined ? {} : { dimensionId: world.dimensionId }),
	};
}
function isSequenceResult(value) {
	return value !== null && typeof value === 'object' && !Array.isArray(value) && Array.isArray(value.results);
}

function compactProgramResult(value) {
	const result = { state: boundedResultField(value.state, 64), reasonCode: boundedResultField(value.reasonCode, 128), programId: boundedResultField(value.programId, 256), actions: safeResultInteger(value.actions), eventSequence: safeResultInteger(value.eventSequence), ...(value.finishRequested === true ? { finishRequested: true } : {}), receipts: [], truncated: true, detail: 'Program observations were omitted. Query historical receipts by bodyActionId and observe for current facts.' };
	for (const receipt of [...value.receipts].reverse()) {
		const next = Object.fromEntries(['actionId', 'bodyActionId', 'actionType', 'sourceStepId', 'state', 'reasonCode'].filter((field) => receipt[field] !== undefined).map((field) => [field, boundedResultField(receipt[field], field === 'reasonCode' ? 128 : 256)]));
		for (const flag of ['executionStarted', 'physicalAttempted']) if (typeof receipt[flag] === 'boolean') next[flag] = receipt[flag];
		if (Buffer.byteLength(JSON.stringify({ ...result, receipts: [next, ...result.receipts] }), 'utf8') > MAX_TOOL_RESULT_BYTES - 128) break;
		result.receipts.unshift(next);
	}
	result.omittedReceipts = Math.max(0, value.omittedReceipts ?? 0) + value.receipts.length - result.receipts.length;
	return result;
}

function compactSequenceResult(value) {
	const sourceResults = value.results.slice(0, MAX_SEQUENCE_ACTIONS);
	const result = {
		state: boundedResultField(value.state, 64),
		completed: safeResultInteger(value.completed),
		...(safeResultInteger(value.failedAt) === null ? {} : { failedAt: safeResultInteger(value.failedAt) }),
		results: sourceResults.map((step) => ({
			actionType: boundedResultField(step?.actionType, 64),
			state: boundedResultField(step?.state, 64),
			reasonCode: boundedResultField(step?.reasonCode, 128),
			...(step?.executionStarted === undefined ? {} : { executionStarted: step.executionStarted === true }),
			...(step?.physicalAttempted === undefined ? {} : { physicalAttempted: step.physicalAttempted === true }),
		})),
	};
	let omittedObservations = sourceResults.length !== value.results.length;
	for (let index = 0; index < sourceResults.length; index += 1) {
		const observation = sourceResults[index]?.actionObservation;
		if (observation === undefined) continue;
		const candidate = { ...result, results: result.results.map((step, stepIndex) => stepIndex === index ? { ...step, actionObservation: observation } : step) };
		if (Buffer.byteLength(JSON.stringify(candidate), 'utf8') <= MAX_TOOL_RESULT_BYTES) result.results[index] = candidate.results[index];
		else omittedObservations = true;
	}
	if (omittedObservations) result.detail = 'Some per-action observations were omitted by the coordinator result limit; call observe for fresh compact facts.';
	return result;
}

function boundedResultField(value, maximum) { return String(value ?? '').slice(0, maximum); }
function safeResultInteger(value) { return Number.isSafeInteger(value) ? value : null; }

function tool(name, description, inputSchema) {
	return Object.freeze({ type: 'function', name, description, inputSchema: Object.freeze(inputSchema) });
}

function objectSchema(properties, required = []) {
	return { type: 'object', properties, required, additionalProperties: false };
}

function numberSchema(minimum, maximum) { return { type: 'number', minimum, maximum }; }
function integerSchema(minimum, maximum) { return { type: 'integer', minimum, maximum }; }

function requireObject(value) {
	if (value === null || typeof value !== 'object' || Array.isArray(value)) invalid('arguments must be an object');
	return value;
}

function requireExactKeys(value, allowed) {
	const allowedSet = new Set(allowed);
	for (const key of Object.keys(value)) if (!allowedSet.has(key)) invalid(`unexpected argument '${key}'`);
}

function boundedText(value, field, maximum) {
	if (typeof value !== 'string' || value.trim().length === 0 || value.length > maximum) invalid(`${field} must be 1 to ${maximum} characters`);
	return value;
}

function finiteNumber(value, field, minimum, maximum) {
	if (!Number.isFinite(value) || value < minimum || value > maximum) invalid(`${field} must be between ${minimum} and ${maximum}`);
	return value;
}

function integer(value, field, minimum, maximum) {
	if (!Number.isSafeInteger(value)) invalid(`${field} must be an integer`);
	return finiteNumber(value, field, minimum, maximum);
}

function optionalNumber(value, fallback, field, minimum, maximum) {
	return value === undefined ? fallback : finiteNumber(value, field, minimum, maximum);
}

function optionalInteger(value, fallback, field, minimum, maximum) {
	return value === undefined ? fallback : integer(value, field, minimum, maximum);
}

function optionalBoolean(value, fallback, field) {
	if (value === undefined) return fallback;
	if (typeof value !== 'boolean') invalid(`${field} must be a boolean`);
	return value;
}

function stripActionType(action) {
	const { type: _type, ...argumentsValue } = action;
	return argumentsValue;
}

function invalid(message) { throw codedError('INVALID_MINECRAFT_TOOL_ARGUMENTS', message); }
function codedError(code, message) { return Object.assign(new Error(message), { code }); }
