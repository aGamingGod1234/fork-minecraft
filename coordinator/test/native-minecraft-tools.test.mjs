import assert from 'node:assert/strict';
import { readFile } from 'node:fs/promises';
import test from 'node:test';

import {
	MINECRAFT_DYNAMIC_TOOLS,
	NATIVE_AGENT_INSTRUCTIONS,
	normalizeMinecraftToolCall,
	toolResultContent,
	minecraftCapabilities,
} from '../src/native-minecraft-tools.mjs';
import { ACTION_FIELDS } from '../src/constants.mjs';
import { parseArenaScript } from '../src/arena-script/parser.mjs';

test('capabilities reflect the shared action contract without inventing fields', () => {
	assert.deepEqual(minecraftCapabilities().actions.map(({ actionType, fields }) => ({ actionType, fields })), Object.entries(ACTION_FIELDS).map(([actionType, fields]) => ({ actionType, fields: [...fields] })));
	assert.deepEqual(minecraftCapabilities().actions.find(({ actionType }) => actionType === 'use_item').optionalFields, ['hand', 'expectedItemId']);
	const copy = minecraftCapabilities();
	copy.actions[0].fields.push('invented');
	assert.ok(!minecraftCapabilities().actions[0].fields.includes('invented'));
	assert.deepEqual(normalizeMinecraftToolCall('capabilities', {}), { kind: 'capabilities' });
	const reference = minecraftCapabilities({ section: 'program' });
	assert.deepEqual(normalizeMinecraftToolCall('capabilities', { section: 'program' }), { kind: 'capabilities', section: 'program' });
	assert.equal(JSON.parse(toolResultContent(reference).contentItems[0].text).reference, reference.reference);
	assert.match(reference.reference, /program\.watch/);
});

test('inspection validates the exact server page and target contract', () => {
	assert.deepEqual(normalizeMinecraftToolCall('inspect', { section: 'inventory', offset: 32 }), { kind: 'inspect', section: 'inventory', offset: 32, limit: 32 });
	assert.deepEqual(normalizeMinecraftToolCall('inspect', { section: 'item', slot: 7, limit: 1 }), { kind: 'inspect', section: 'item', slot: 7, offset: 0, limit: 1 });
	assert.deepEqual(normalizeMinecraftToolCall('inspect', { section: 'block', x: 1, y: 64, z: -2 }), { kind: 'inspect', section: 'block', x: 1, y: 64, z: -2, offset: 0, limit: 32 });
	assert.equal(normalizeMinecraftToolCall('inspect', { section: 'recipes', recipeId: 'minecraft:crafting_table' }).recipeId, 'minecraft:crafting_table');
	for (const args of [{ section: 'seed' }, { section: 'blocks', limit: 33 }, { section: 'blocks', offset: 4097 }, { section: 'item' }, { section: 'inventory', slot: 2 }, { section: 'block', x: 1, y: 64 }, { section: 'recipes', recipeId: 'invalid id' }, { section: 'menu', recipeId: 'minecraft:a' }]) {
		assert.throws(() => normalizeMinecraftToolCall('inspect', args), { code: 'INVALID_MINECRAFT_TOOL_ARGUMENTS' });
	}
});

test('action handles and memory queries reject ambiguous or unbounded requests', () => {
	assert.deepEqual(normalizeMinecraftToolCall('startAction', { actionType: 'wait', arguments: { durationMs: 5 } }), { kind: 'start_action', actionType: 'wait', arguments: { durationMs: 5 } });
	assert.deepEqual(normalizeMinecraftToolCall('cancelAction', { actionId: 'action-1', goalRevision: 4 }), { kind: 'cancel_action', actionId: 'action-1', goalRevision: 4 });
	assert.deepEqual(normalizeMinecraftToolCall('queryMemory', {}), { kind: 'query_memory', memoryKind: 'all', offset: 0, limit: 20 });
	assert.equal(normalizeMinecraftToolCall('notebook', { key: 'boundary', text: 'x'.repeat(2048) }).text.length, 2048);
	for (const [name, args] of [['cancelAction', { actionId: 'action-1' }], ['replaceAction', { actionId: 'action-1', goalRevision: 4, actionType: 'teleport', arguments: {} }], ['notebook', { key: 'a', text: 'x'.repeat(2049) }], ['queryMemory', { kind: 'secret' }], ['queryMemory', { offset: -1 }], ['exploreFrontier', { seek: 'nether' }]]) {
		assert.throws(() => normalizeMinecraftToolCall(name, args), { code: 'INVALID_MINECRAFT_TOOL_ARGUMENTS' });
	}
});

test('oversized observations retain input identity, freshness and explicit omissions', () => {
	const raw = {
		eventSequence: 8, freshness: { fresh: true, eventSequence: 8 },
		observation: {
			player: { health: 15 }, inventory: { items: [] }, revision: 10,
			coverage: { entities: { returned: 64, total: 80 } },
			interaction: { attackCooldown: 0.8, menu: { menuId: 'menu-9', stateRevision: 3, slots: [] }, input: { active: true, hand: 'off_hand' } },
			entities: Array.from({ length: 64 }, (_, id) => ({ id, name: 'x'.repeat(500) })),
		},
	};
	const result = JSON.parse(toolResultContent(raw).contentItems[0].text);
	assert.equal(result.truncated, true);
	assert.deepEqual(result.freshness, raw.freshness);
	assert.equal(result.observation.interaction.menu.stateRevision, 3);
	assert.equal(result.observation.interaction.input.hand, 'off_hand');
	assert.deepEqual(result.observation.coverage, raw.observation.coverage);
	assert.ok(result.observation.resultCoverage.omittedSections.includes('entities'));
});

test('oversized inspection pages keep whole entries and a truthful continuation offset', () => {
	const raw = { section: 'inventory', revision: 7, offset: 10, coverage: { total: 40, returned: 20 }, entries: Array.from({ length: 20 }, (_, index) => ({ slot: index + 10, components: { text: 'x'.repeat(1500) } })) };
	const content = toolResultContent(raw).contentItems[0].text;
	const result = JSON.parse(content);
	assert.ok(Buffer.byteLength(content) <= 16_384);
	assert.ok(result.entries.length > 0 && result.entries.length < raw.entries.length);
	assert.deepEqual(result.entries, raw.entries.slice(0, result.entries.length));
	assert.equal(result.nextOffset, 10 + result.entries.length);
	assert.equal(result.revision, 7);
	assert.equal(result.coverage.resultTruncated, true);
});

test('Minecraft control guidance examples are valid executor tool calls', async () => {
	const skill = await readFile(new URL('../config/minecraft-agent/.codex/skills/minecraft-control/SKILL.md', import.meta.url), 'utf8');
	const turns = [...skill.matchAll(/```json executor-calls\s+([\s\S]*?)```/g)]
		.map((match) => JSON.parse(match[1]));
	const calls = [...skill.matchAll(/```json executor-call\s+([\s\S]*?)```/g)]
		.map((match) => JSON.parse(match[1]));
	assert.ok(calls.length >= 5, 'expected at least five executor-call examples');
	assert.ok(turns.some(({ calls: turnCalls }) => turnCalls.length >= 2), 'expected a multi-call turn example');

	const normalized = [...calls, ...turns.flatMap(({ calls: turnCalls }) => turnCalls)].map(({ tool, arguments: args }) => ({
		tool,
		result: normalizeMinecraftToolCall(tool, args),
	}));
	assert.ok(normalized.some(({ tool }) => tool === 'say'));
	assert.ok(normalized.some(({ tool }) => tool === 'mine'));
	assert.ok(normalized.some(({ tool, result }) => tool === 'act' && result.actionType === 'pick_up_item'));
	assert.ok(normalized.some(({ tool, result }) => tool === 'act' && result.actionType === 'craft_inventory'));
	assert.ok(normalized.some(({ tool }) => tool === 'finish'));
});

test('Minecraft control reference covers every executor tool and action with accepted and rejected examples', async () => {
	const skill = await readFile(new URL('../config/minecraft-agent/.codex/skills/minecraft-control/SKILL.md', import.meta.url), 'utf8');
	const parseExamples = (label) => [...skill.matchAll(new RegExp('```json ' + label + '\\s+([\\s\\S]*?)```', 'g'))]
		.map((match) => JSON.parse(match[1]));
	const goodCalls = parseExamples('executor-call');
	const badCalls = parseExamples('executor-bad-call');
	const expectedTools = MINECRAFT_DYNAMIC_TOOLS.map(({ name }) => name);
	const expectedActions = MINECRAFT_DYNAMIC_TOOLS
		.find(({ name }) => name === 'act')
		.inputSchema.properties.actionType.enum;

	assert.deepEqual([...new Set(goodCalls.map(({ tool }) => tool))].sort(), [...expectedTools].sort());
	assert.deepEqual(
		[...new Set(goodCalls.filter(({ tool }) => tool === 'act').map(({ arguments: args }) => args.actionType))].sort(),
		[...expectedActions].sort(),
	);
	for (const { tool, arguments: args } of goodCalls) normalizeMinecraftToolCall(tool, args);
	for (const { arguments: args } of goodCalls.filter(({ tool }) => tool === 'runProgram')) parseArenaScript(args.source);
	assert.ok(badCalls.length >= 6, 'examples cover distinct malformed requests');
	for (const { tool, arguments: args } of badCalls) {
		assert.throws(() => normalizeMinecraftToolCall(tool, args), (error) => (
			error?.code === 'INVALID_MINECRAFT_TOOL_ARGUMENTS' || error?.code === 'UNKNOWN_MINECRAFT_TOOL'
		));
	}
});

test('program calls bound source bytes, action count and execution time', () => {
	const source = 'program.onUnhandledAttention("pause_and_notify"); await player.wait(1);';
	assert.deepEqual(normalizeMinecraftToolCall('runProgram', { source }), { kind: 'run_program', source, maxActions: 64, timeoutMs: 30000 });
	for (const args of [{ source, maxActions: 257 }, { source, timeoutMs: 120001 }, { source: '😀'.repeat(20000) }, { source, planner: 'another-model' }]) assert.throws(() => normalizeMinecraftToolCall('runProgram', args), { code: 'INVALID_MINECRAFT_TOOL_ARGUMENTS' });
});

test('oversized program results preserve factual status, body receipt references and omissions', () => {
	const raw = { state: 'YIELDED', reasonCode: 'PROGRAM_EXHAUSTED', programId: 'native-program-test', actions: 64, eventSequence: 100, receipts: Array.from({ length: 64 }, (_, index) => ({ actionId: `engine:${index}`, bodyActionId: `native:${index}`, actionType: 'wait', sourceStepId: `step-${index}`, state: index === 63 ? 'FAILED' : 'SUCCEEDED', reasonCode: index === 63 ? 'INPUT_REJECTED' : '', executionStarted: true })), observation: { player: { health: 20 }, detail: 'x'.repeat(30000) } };
	const result = JSON.parse(toolResultContent(raw).contentItems[0].text);
	assert.equal(result.programId, raw.programId);
	assert.equal(result.state, 'YIELDED');
	assert.equal(result.receipts.at(-1).bodyActionId, 'native:63');
	assert.equal(result.receipts.at(-1).state, 'FAILED');
	assert.equal(result.omittedReceipts + result.receipts.length, 64);
	assert.ok(Buffer.byteLength(JSON.stringify(result)) <= 16384);
});

test('native Minecraft tools expose the common fast path plus one validated advanced body operation', () => {
	assert.deepEqual(MINECRAFT_DYNAMIC_TOOLS.map((tool) => tool.name), [
		'observe', 'capabilities', 'inspect', 'actionStatus', 'cancelAction', 'replaceAction', 'startAction', 'notebook', 'queryMemory', 'runProgram', 'lookAround', 'control', 'moveTo', 'exploreFrontier', 'mine', 'say', 'wait', 'act', 'sequence', 'finish',
	]);
	assert.ok(MINECRAFT_DYNAMIC_TOOLS.every((tool) => tool.type === 'function'));
	assert.ok(NATIVE_AGENT_INSTRUCTIONS.length < 1_500);
	assert.match(NATIVE_AGENT_INSTRUCTIONS, /you.*choose every action/i);
	assert.match(MINECRAFT_DYNAMIC_TOOLS.find((tool) => tool.name === 'sequence').description, /Prefer sequence for safe 2\+ action chains/i);
	assert.match(NATIVE_AGENT_INSTRUCTIONS, /speech playback is asynchronous/i);
	assert.match(NATIVE_AGENT_INSTRUCTIONS, /exploreFrontier/);
	assert.match(NATIVE_AGENT_INSTRUCTIONS, /death does not change the active goal/i);
	assert.match(NATIVE_AGENT_INSTRUCTIONS, /omitted or unobserved facts are unknown/i);
});

test('advertised native actions exactly match Java model-authored dispatch', async () => {
	const executor = await readFile(new URL('../../src/main/java/dev/agaminggod/arenaagents/server/runtime/ServerActionExecutor.java', import.meta.url), 'utf8');
	const allowlist = executor.match(/ARENA_SCRIPT_PRIMITIVES\s*=\s*Set\.of\(([\s\S]*?)\);/)?.[1] ?? '';
	const javaActions = [...allowlist.matchAll(/ActionType\.([A-Z_]+)/g)]
		.map(([, name]) => name.toLowerCase())
		.sort();
	const advertisedActions = MINECRAFT_DYNAMIC_TOOLS
		.find(({ name }) => name === 'act')
		.inputSchema.properties.actionType.enum
		.toSorted();

	assert.deepEqual(advertisedActions, javaActions);
	assert.ok(advertisedActions.includes('pick_up_item'), 'working Java pickup controller remains reachable');
	for (const unsupportedComposite of ['build_sequence', 'fight_target', 'flee_from', 'follow_entity']) {
		assert.ok(!advertisedActions.includes(unsupportedComposite), `${unsupportedComposite} is not advertised without native dispatch`);
	}
});

test('native Minecraft tool calls normalize to exact existing body actions', () => {
	assert.deepEqual(normalizeMinecraftToolCall('control', {
		forward: 1, strafe: -0.5, jump: true, sneak: false, sprint: true,
		attack: false, use: true, yaw: 90, pitch: -15, selectedSlot: 2, hand: 'off', ticks: 20,
	}), {
		kind: 'action', actionType: 'control',
		arguments: { forward: 1, strafe: -0.5, jump: true, sneak: false, sprint: true, attack: false, use: true, yaw: 90, pitch: -15, selectedSlot: 2, hand: 'off', ticks: 20 },
	});
	assert.deepEqual(normalizeMinecraftToolCall('lookAround', {
		centerYaw: 170, pitch: 0, steps: 4, ticksPerStep: 3,
	}), {
		kind: 'lookAround', centerYaw: 170, pitch: 0, steps: 4, ticksPerStep: 3,
	});
	assert.deepEqual(normalizeMinecraftToolCall('moveTo', { x: 1, y: 64, z: -2 }), {
		kind: 'action', actionType: 'navigate_to', arguments: { x: 1, y: 64, z: -2, tolerance: 1, sprint: true, timeoutMs: 30_000 },
	});
	assert.deepEqual(normalizeMinecraftToolCall('exploreFrontier', {}), {
		kind: 'explore_frontier', arguments: { radius: 24, limit: 32 },
	});
	assert.deepEqual(normalizeMinecraftToolCall('exploreFrontier', { blockId: 'minecraft:stone', radius: 24, limit: 16 }), {
		kind: 'explore_frontier', arguments: { blockId: 'minecraft:stone', radius: 24, limit: 16 },
	});
	assert.deepEqual(normalizeMinecraftToolCall('mine', { x: 2, y: 63, z: 4, expectedBlockId: 'minecraft:stone' }), {
		kind: 'action', actionType: 'break_block', arguments: { x: 2, y: 63, z: 4, expectedBlockId: 'minecraft:stone', timeoutMs: 15_000 },
	});
	assert.deepEqual(normalizeMinecraftToolCall('say', { message: 'hi', recipientId: 'agent-b' }), {
		kind: 'action', actionType: 'chat', arguments: { message: 'hi', audience: 'direct', recipientId: 'agent-b' },
	});
	assert.deepEqual(normalizeMinecraftToolCall('say', { message: 'On it.', audience: 'proximity' }), {
		kind: 'action', actionType: 'chat', arguments: { message: 'On it.', audience: 'proximity' },
	});
	assert.deepEqual(normalizeMinecraftToolCall('finish', {
		summary: 'Stone acquired.',
	}), {
		kind: 'finish', summary: 'Stone acquired.',
	});
	assert.deepEqual(normalizeMinecraftToolCall('act', {
		actionType: 'craft_inventory',
		arguments: { recipeId: 'minecraft:oak_planks', count: 4, timeoutMs: 15_000 },
	}), {
		kind: 'action', actionType: 'craft_inventory',
		arguments: { recipeId: 'minecraft:oak_planks', count: 4, timeoutMs: 15_000 },
	});
	assert.deepEqual(normalizeMinecraftToolCall('act', {
		actionType: 'pick_up_item',
		arguments: { targetSelector: '550e8400-e29b-41d4-a716-446655440000' },
	}), {
		kind: 'action', actionType: 'pick_up_item',
		arguments: { targetSelector: '550e8400-e29b-41d4-a716-446655440000' },
	});
	assert.deepEqual(normalizeMinecraftToolCall('sequence', {
		actions: [
			{ actionType: 'navigate_to', arguments: { x: 2, y: 64, z: 1 } },
			{ actionType: 'break_block', arguments: { x: 2, y: 64, z: 1, expectedBlockId: 'minecraft:stone' } },
		],
	}), {
		kind: 'sequence',
		actions: [
			{ actionType: 'navigate_to', arguments: { x: 2, y: 64, z: 1, tolerance: 1, sprint: true, timeoutMs: 30_000 } },
			{ actionType: 'break_block', arguments: { x: 2, y: 64, z: 1, expectedBlockId: 'minecraft:stone', timeoutMs: 15_000 } },
		],
	});
});

test('native Minecraft boundary rejects unknown, oversized, and malformed calls', () => {
	assert.throws(() => normalizeMinecraftToolCall('attack', {}), (error) => error?.code === 'UNKNOWN_MINECRAFT_TOOL');
	assert.throws(() => normalizeMinecraftToolCall('moveTo', { x: '1', y: 2, z: 3 }), (error) => error?.code === 'INVALID_MINECRAFT_TOOL_ARGUMENTS');
	assert.throws(() => normalizeMinecraftToolCall('mine', { x: 1, y: 64, z: 2 }), (error) => error?.code === 'INVALID_MINECRAFT_TOOL_ARGUMENTS');
	assert.throws(() => normalizeMinecraftToolCall('control', { forward: 1 }), (error) => error?.code === 'INVALID_MINECRAFT_TOOL_ARGUMENTS');
	assert.throws(() => normalizeMinecraftToolCall('lookAround', { centerYaw: 0, pitch: 0, steps: 1, ticksPerStep: 3 }), (error) => error?.code === 'INVALID_MINECRAFT_TOOL_ARGUMENTS');
	assert.throws(() => normalizeMinecraftToolCall('say', { message: 'x'.repeat(257) }), (error) => error?.code === 'INVALID_MINECRAFT_TOOL_ARGUMENTS');
	assert.throws(() => normalizeMinecraftToolCall('say', { message: 'hi', audience: 'direct' }), (error) => error?.code === 'INVALID_MINECRAFT_TOOL_ARGUMENTS');
	assert.throws(() => normalizeMinecraftToolCall('say', { message: 'hi', audience: 'proximity', recipientId: 'agent-b' }), (error) => error?.code === 'INVALID_MINECRAFT_TOOL_ARGUMENTS');
	assert.throws(() => normalizeMinecraftToolCall('finish', { summary: 'done', completionContract: {} }), (error) => error?.code === 'INVALID_MINECRAFT_TOOL_ARGUMENTS');
	assert.throws(() => normalizeMinecraftToolCall('act', { actionType: 'craft_inventory', arguments: { recipeId: 'minecraft:oak_planks' } }), (error) => error?.code === 'INVALID_MINECRAFT_TOOL_ARGUMENTS');
	assert.throws(() => normalizeMinecraftToolCall('act', { actionType: 'pick_up_item', arguments: { targetSelector: 'nearest_item' } }), (error) => error?.code === 'INVALID_MINECRAFT_TOOL_ARGUMENTS');
	assert.throws(() => normalizeMinecraftToolCall('act', { actionType: 'fight_target', arguments: { targetSelector: 'zombie', desiredRange: 20, timeoutMs: 1_000 } }), (error) => error?.code === 'INVALID_MINECRAFT_TOOL_ARGUMENTS');
	assert.throws(() => normalizeMinecraftToolCall('act', { actionType: 'build_sequence', arguments: { placements: [], timeoutMs: 1_000 } }), (error) => error?.code === 'INVALID_MINECRAFT_TOOL_ARGUMENTS');
	assert.throws(() => normalizeMinecraftToolCall('act', { actionType: 'flee_from', arguments: { targetSelector: 'target', distance: 8, timeoutMs: 1_000 } }), (error) => error?.code === 'INVALID_MINECRAFT_TOOL_ARGUMENTS');
	assert.throws(() => normalizeMinecraftToolCall('act', { actionType: 'follow_entity', arguments: { targetSelector: 'target', distance: 3, timeoutMs: 1_000 } }), (error) => error?.code === 'INVALID_MINECRAFT_TOOL_ARGUMENTS');
	assert.throws(() => normalizeMinecraftToolCall('sequence', { actions: [{ actionType: 'wait', arguments: { durationMs: 1 } }] }), (error) => error?.code === 'INVALID_MINECRAFT_TOOL_ARGUMENTS');
	assert.throws(() => normalizeMinecraftToolCall('sequence', { actions: Array.from({ length: 9 }, () => ({ actionType: 'wait', arguments: { durationMs: 1 } })) }), (error) => error?.code === 'INVALID_MINECRAFT_TOOL_ARGUMENTS');
});

test('advanced actions cannot override their discriminator through nested arguments', () => {
	for (const type of ['wait', 'attack']) {
		const action = { actionType: 'attack', arguments: { type, durationMs: 100 } };
		assert.throws(() => normalizeMinecraftToolCall('act', action), { code: 'INVALID_MINECRAFT_TOOL_ARGUMENTS' });
		assert.throws(() => normalizeMinecraftToolCall('sequence', {
			actions: [{ actionType: 'wait', arguments: { durationMs: 1 } }, action],
		}), { code: 'INVALID_MINECRAFT_TOOL_ARGUMENTS' });
	}
});

test('all native mining paths reject air before dispatch', () => {
	for (const expectedBlockId of ['minecraft:air', 'minecraft:cave_air', 'minecraft:void_air']) {
		const args = { x: 2, y: 63, z: 4, expectedBlockId, timeoutMs: 15_000 };
		const action = { actionType: 'break_block', arguments: args };
		assert.throws(() => normalizeMinecraftToolCall('mine', args), { code: 'INVALID_MINECRAFT_TOOL_ARGUMENTS' });
		assert.throws(() => normalizeMinecraftToolCall('act', action), { code: 'INVALID_MINECRAFT_TOOL_ARGUMENTS' });
		assert.throws(() => normalizeMinecraftToolCall('sequence', {
			actions: [{ actionType: 'wait', arguments: { durationMs: 1 } }, action],
		}), { code: 'INVALID_MINECRAFT_TOOL_ARGUMENTS' });
	}
});

test('tool results are compact deterministic inputText content', () => {
	assert.deepEqual(toolResultContent({ state: 'SUCCEEDED', reasonCode: '' }), {
		success: true,
		contentItems: [{ type: 'inputText', text: '{"state":"SUCCEEDED","reasonCode":""}' }],
	});
	assert.match(toolResultContent({ detail: 'x'.repeat(20_000) }).contentItems[0].text, /TRUNCATED/);
	assert.equal(toolResultContent({ detail: 'x'.repeat(20_000) }).contentItems[0].text.length <= 16_384, true);
	const truncatedDeath = toolResultContent({
		observation: {
			player: { health: 0, dead: true },
			inventory: { items: Array.from({ length: 64 }, (_, index) => ({ itemId: `minecraft:filler_${index}`, count: 64 })) },
			death: { cause: 'lava', x: 12, y: 64, z: -8, dimensionId: 'minecraft:overworld' },
			recovery: {
				lastDeath: { cause: 'lava', x: 12, y: 64, z: -8, dimensionId: 'minecraft:overworld' },
				lastLostInventory: [{ itemId: 'minecraft:stone_pickaxe', count: 1 }],
				alreadyHave: ['minecraft:crafting_table'],
				facts: 'Current inventory is empty. Lost on death: minecraft:stone_pickaxe.',
			},
			failureClass: 'recover',
			world: { dimension: 'minecraft:overworld' },
			blocks: Array.from({ length: 400 }, (_, index) => ({ blockId: 'minecraft:stone', x: index, y: 64, z: 0 })),
		},
	});
	assert.match(truncatedDeath.contentItems[0].text, /lastDeath/);
	assert.match(truncatedDeath.contentItems[0].text, /alreadyHave/);
	assert.match(truncatedDeath.contentItems[0].text, /stone_pickaxe/);
	assert.doesNotMatch(truncatedDeath.contentItems[0].text, /"state":"TRUNCATED"/);
	const oversizedIds = {
		observation: {
			recovery: {
				lastLostInventory: Array.from({ length: 16 }, (_, index) => ({
					itemId: `minecraft:${'a'.repeat(240)}_${index}`,
					count: 64,
				})),
				alreadyHave: Array.from({ length: 32 }, (_, index) => `minecraft:${'b'.repeat(240)}_${index}`),
				doNotRedo: Array.from({ length: 24 }, (_, index) => `minecraft:${'c'.repeat(240)}_${index}`),
				facts: 'f'.repeat(8_000),
			},
		},
	};
	const bounded = toolResultContent(oversizedIds);
	assert.equal(bounded.contentItems[0].text.length <= 16_384, true);
	assert.ok(Buffer.byteLength(bounded.contentItems[0].text, 'utf8') <= 16_384);
});

test('tool result byte cap still applies when survival facts are oversized', () => {
	const text = toolResultContent({
		observation: {
			death: { cause: 'lava', x: 1, y: 64, z: 2, dimensionId: 'minecraft:overworld' },
			recovery: { facts: 'x'.repeat(40_000) },
		},
	}).contentItems[0].text;
	assert.equal(Buffer.byteLength(text, 'utf8') <= 16_384, true);
});

test('oversized sequence results retain every authoritative step status', () => {
	const content = toolResultContent({
		state: 'SUCCEEDED', completed: 8,
		results: Array.from({ length: 8 }, (_, index) => ({
			actionType: 'break_block', state: 'SUCCEEDED', reasonCode: `STEP_${index + 1}`,
			actionObservation: { detail: 'x'.repeat(8_000), step: index + 1 },
		})),
	});
	assert.equal(content.contentItems[0].text.length <= 16_384, true);
	const result = JSON.parse(content.contentItems[0].text);
	assert.equal(result.state, 'SUCCEEDED');
	assert.equal(result.results.length, 8);
	assert.deepEqual(result.results.map(({ state, reasonCode }) => ({ state, reasonCode })), Array.from({ length: 8 }, (_, index) => ({ state: 'SUCCEEDED', reasonCode: `STEP_${index + 1}` })));
});
