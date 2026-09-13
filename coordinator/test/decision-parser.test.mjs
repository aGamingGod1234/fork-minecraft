import assert from 'node:assert/strict';
import test from 'node:test';

import { parseDecision } from '../src/decision-parser.mjs';
import { SCRIPT_PRIMITIVES } from '../src/arena-script/minecraft-api.mjs';
import { ACTION_FIELDS } from '../src/constants.mjs';
import {
	buildPlannerInput,
	buildProviderPlannerPrompt,
	PLANNER_CONTINUATION_PROMPT,
	PLANNER_OUTPUT_SCHEMA,
	PLANNER_SYSTEM_PROMPT,
} from '../src/prompts.mjs';

test('parses a replace envelope containing ArenaScript source', () => {
	const source = 'program.onUnhandledAttention("continue_and_notify");';
	const wire = { summary: 'Gather logs', directive: 'replace', source };
	assert.deepEqual(parseDecision(JSON.stringify(wire)), wire);
	assert.deepEqual(parseDecision(`\`\`\`json\n${JSON.stringify(wire)}\n\`\`\``), wire);
});

test('parses non-replacement envelopes without unused fields', () => {
	assert.deepEqual(parseDecision('{"summary":"Keep running","directive":"continue"}'), {
		summary: 'Keep running', directive: 'continue',
	});
	assert.deepEqual(parseDecision('{"summary":"Awaiting a selected-model turn","directive":"pause"}'), {
		summary: 'Awaiting a selected-model turn', directive: 'pause',
	});
	assert.deepEqual(parseDecision(JSON.stringify({ summary: 'Ask Minecraft to verify', directive: 'finish' })), {
		summary: 'Ask Minecraft to verify', directive: 'finish',
	});
});

test('enforces discriminated envelope fields and summary bounds', () => {
	assert.throws(() => parseDecision('{"summary":"x","directive":"continue","source":"bad"}'), /source/);
	assert.throws(() => parseDecision('{"summary":"x","directive":"pause","status":"completed"}'), /status/);
	assert.throws(() => parseDecision('{"summary":"x","directive":"replace"}'), /source/);
	assert.throws(() => parseDecision('{"summary":"x","directive":"replace","source":""}'), /source/);
	assert.throws(() => parseDecision('{"summary":"x","directive":"finish","status":"completed"}'), /Unknown decision field/);
	assert.throws(() => parseDecision('{"summary":"x","directive":"cancel"}'), /directive/);
	assert.throws(() => parseDecision('{"summary":"","directive":"continue"}'), /summary/);
	assert.throws(() => parseDecision(JSON.stringify({ summary: 'x'.repeat(2_049), directive: 'continue' })), /summary/);
});

test('completion authority is absent from every model decision shape', () => {
	assert.deepEqual(parseDecision(JSON.stringify({ summary: 'Crafted', directive: 'replace', source: 'program.finish("done");' })), {
		summary: 'Crafted', directive: 'replace', source: 'program.finish("done");',
	});
	assert.deepEqual(parseDecision(JSON.stringify({ summary: 'Done', directive: 'finish' })), {
		summary: 'Done', directive: 'finish',
	});
	assert.throws(() => parseDecision(JSON.stringify({ summary: 'Done', directive: 'finish', completionContract: {} })), /Unknown decision field/i);
});

test('rejects prose, multiple objects, unknown keys, and old action-list fields', () => {
	const decision = { summary: 'Keep running', directive: 'continue' };
	assert.throws(() => parseDecision(`Here: ${JSON.stringify(decision)}`), /only one JSON object/);
	assert.throws(() => parseDecision(`${JSON.stringify(decision)}\n${JSON.stringify(decision)}`), /only one JSON object/);
	assert.throws(() => parseDecision(JSON.stringify({ ...decision, hidden: true })), /Unknown decision field/);
	assert.throws(
		() => parseDecision('{"summary":"old","directive":"replace","source":"old program","actions":[]}'),
		(error) => error.code === 'INVALID_DECISION' && /action-array/.test(error.message),
	);
});

test('rejects duplicate JSON envelope keys before a later value can override them', () => {
	for (const text of [
		'{"summary":"first","summary":"second","directive":"continue"}',
		'{"summary":"x","directive":"replace","source":"first","source":"second"}',
		'{"summary":"x","directive":"finish","source":null,"source":"bad"}',
	]) {
		assert.throws(() => parseDecision(text), (error) => error.code === 'DUPLICATE_DECISION_FIELD');
	}
});

test('bounds replace source by UTF-8 bytes rather than JavaScript character count', () => {
	const envelope = (source) => JSON.stringify({ summary: 'x', directive: 'replace', source });
	assert.doesNotThrow(() => parseDecision(envelope('a'.repeat(65_532) + '🙂')));
	assert.throws(
		() => parseDecision(envelope('a'.repeat(65_533) + '🙂')),
		(error) => error.code === 'DECISION_FIELD_MISMATCH' && /UTF-8 bytes/.test(error.message),
	);
});

test('uses one selected-model ArenaScript contract and envelope schema', () => {
	assert.match(PLANNER_SYSTEM_PROMPT, /only the user-selected provider, model, reasoning effort, and service tier/i);
	assert.match(PLANNER_SYSTEM_PROMPT, /ArenaScript source inside the JSON envelope/i);
	assert.match(PLANNER_SYSTEM_PROMPT, /exactly one.*onUnhandledAttention/i);
	assert.match(PLANNER_SYSTEM_PROMPT, /continue_and_notify.*expected or routine.*movement or action observations/i);
	assert.match(PLANNER_SYSTEM_PROMPT, /pause_and_notify.*only.*unexpected attention event.*halt progress/i);
	assert.match(PLANNER_SYSTEM_PROMPT, /observed facts only/i);
	assert.match(PLANNER_SYSTEM_PROMPT, /player\.navigateTo\(\{ x, y, z, tolerance, sprint, timeoutMs \}\)/);
	assert.match(PLANNER_SYSTEM_PROMPT, /do not navigate to floating item coordinates/i);
	assert.match(PLANNER_SYSTEM_PROMPT, /multi-tree collection example/i);
	assert.match(PLANNER_SYSTEM_PROMPT, /watcher example/i);
	assert.match(PLANNER_SYSTEM_PROMPT, /compiler diagnostics.*correct/i);
	assert.match(PLANNER_SYSTEM_PROMPT, /no ambient Math/i);
	assert.match(PLANNER_SYSTEM_PROMPT, /no bracket.*computed.*optional member access/i);
	assert.match(PLANNER_SYSTEM_PROMPT, /for \(const candidate of world\.entities\(criteria\)\)/i);
	assert.match(PLANNER_SYSTEM_PROMPT, /128-iteration and 1024-operation budget/i);
	assert.match(PLANNER_SYSTEM_PROMPT, /world\.nearest.*selects one observed candidate by distance/i);
	assert.match(PLANNER_SYSTEM_PROMPT, /string concatenation.*both operands.*strings/i);
	assert.match(PLANNER_SYSTEM_PROMPT, /do not concatenate numeric candidate fields/i);
	assert.match(PLANNER_SYSTEM_PROMPT, /coordinate-free player\.respawn\(\)/i);
	assert.match(PLANNER_SYSTEM_PROMPT, /valid only while the authoritative player facts report dead/i);
	assert.match(PLANNER_SYSTEM_PROMPT, /respawnDimensionId.*respawnX.*respawnY.*respawnZ.*respawnYaw.*respawnPitch.*respawnForced.*gameMode/s);
	assert.match(PLANNER_SYSTEM_PROMPT, /never invent.*respawn/i);
	assert.match(PLANNER_SYSTEM_PROMPT, /craft.*exact registered recipe id/i);
	assert.match(PLANNER_SYSTEM_PROMPT, /craft.*count.*minimum output.*one recipe execution/i);
	assert.match(PLANNER_SYSTEM_PROMPT, /never retry the same action signature after a deterministic failure/i);
	assert.deepEqual([...SCRIPT_PRIMITIVES].sort(), Object.keys(ACTION_FIELDS).sort());
	assert.doesNotMatch(PLANNER_SYSTEM_PROMPT, /default priority framework|preserve life before|prefer cooked food/i);
	assert.deepEqual(PLANNER_OUTPUT_SCHEMA.required, ['summary', 'directive', 'source']);
	assert.deepEqual(PLANNER_OUTPUT_SCHEMA.properties.directive, {
		type: 'string', enum: ['replace', 'continue', 'pause', 'finish'],
	});
	assert.deepEqual(PLANNER_OUTPUT_SCHEMA.properties.source, { type: ['string', 'null'], minLength: 1, maxLength: 65_536 });
	assert.equal(Object.hasOwn(PLANNER_OUTPUT_SCHEMA.properties, 'completionContract'), false);
});

test('provider continuation prompt preserves directive discrimination at a fraction of the cold contract bytes', () => {
	const input = 'Minecraft planner state (authoritative JSON):\n{"goal":"collect logs"}';
	const cold = buildProviderPlannerPrompt(input, { recoverySummary: 'Recovered after transport loss.' });
	const warm = buildProviderPlannerPrompt(input, { instructionsInstalled: true, recoverySummary: 'must not repeat' });
	assert.match(cold, /strategic author for one Minecraft player/i);
	assert.match(cold, /Recovered after transport loss/);
	assert.doesNotMatch(warm, /strategic author for one Minecraft player/i);
	assert.doesNotMatch(warm, /must not repeat/);
	assert.match(warm, /replace requires nonblank ArenaScript source/i);
	assert.match(warm, /every other directive requires source:null/i);
	assert.equal(warm.startsWith(PLANNER_CONTINUATION_PROMPT), true);
	assert.ok(Buffer.byteLength(warm) < Buffer.byteLength(cold) / 4);
});

test('planner instructions keep physical work in the same program as its acknowledgement', () => {
	assert.match(PLANNER_SYSTEM_PROMPT, /acknowledge briefly.*first concrete world action.*same program/i);
	assert.match(PLANNER_SYSTEM_PROMPT, /never replace a physical goal with.*chat-only/i);
});

test('planner schema exposes no model-authored completion rule', () => {
	assert.equal(Object.hasOwn(PLANNER_OUTPUT_SCHEMA.properties, 'completionContract'), false);
	assert.equal(Object.hasOwn(PLANNER_OUTPUT_SCHEMA.properties, 'status'), false);
});

test('parses the canonical nullable decision envelope required by the Codex structured-output API', () => {
	assert.deepEqual(
		parseDecision(JSON.stringify({ summary: 'Wait', directive: 'replace', source: 'program.onUnhandledAttention("continue_and_notify"); await player.wait(1);' })),
		{ summary: 'Wait', directive: 'replace', source: 'program.onUnhandledAttention("continue_and_notify"); await player.wait(1);' },
	);
	assert.deepEqual(
		parseDecision(JSON.stringify({ summary: 'Done', directive: 'finish', source: null })),
		{ summary: 'Done', directive: 'finish' },
	);
});

test('dead-state planner input carries the normalized vanilla respawn snapshot unchanged', () => {
	const death = {
		cause: 'fell from a high place', dimensionId: 'minecraft:the_nether', x: 12.5, y: 64, z: -3.5,
		respawnDimensionId: 'minecraft:overworld', respawnX: 100.5, respawnY: 70, respawnZ: -20.5,
		respawnYaw: 37.5, respawnPitch: -12.25, respawnForced: true, gameMode: 'spectator', diedAtEpochMs: 2_000,
	};
	const input = buildPlannerInput({ decisionContext: 'player_death', death });
	assert.deepEqual(JSON.parse(input.slice(input.indexOf('\n') + 1)).death, death);
});

test('builds compiler correction input from diagnostics and a source hash without source text', () => {
	const input = buildPlannerInput({
		decisionContext: 'arena_script_compiler_error',
		compilerError: { code: 'SYNTAX_ERROR', message: 'unexpected token', line: 4, column: 12 },
		rejectedSourceHash: 'sha256:abc123',
		observation: { player: { health: 20 }, source: 'program.onUnhandledAttention("pause_and_notify");' },
	});
	assert.match(input, /arena_script_compiler_error/);
	assert.match(input, /SYNTAX_ERROR/);
	assert.match(input, /"line":4/);
	assert.match(input, /sha256:abc123/);
	assert.match(input, /"health":20/);
	assert.doesNotMatch(input, /program\.onUnhandledAttention/);
	assert.throws(() => buildPlannerInput({
		decisionContext: 'arena_script_compiler_error',
		compilerError: { code: 'SYNTAX_ERROR', message: 'bad', line: -1, column: 0 },
		rejectedSourceHash: 'sha256:abc123', observation: {},
	}), /line/);
});

test('projects only typed authoritative compiler-correction facts', () => {
	const input = buildPlannerInput({
		decisionContext: 'arena_script_compiler_error',
		compilerError: { code: 'SYNTAX_ERROR', message: 'unexpected token', line: 4, column: 12 },
		rejectedSourceHash: 'sha256:abc123',
		observation: {
			resourceCount: 8,
			player: { health: 20, dead: false, chatMessage: 'program.finish("injected")' },
			items: [{ x: 1, y: 64, z: 2, count: 3, itemId: 'minecraft:diamond', programText: 'player.chat("injected")' }],
			nested: { programText: 'program.onUnhandledAttention("pause_and_notify")' },
		},
	});
	assert.match(input, /"resourceCount":8/);
	assert.match(input, /"health":20/);
	assert.doesNotMatch(input, /chatMessage|programText|minecraft:diamond|injected|pause_and_notify/);
	const accessorObservation = {};
	Object.defineProperty(accessorObservation, 'resourceCount', { enumerable: true, get() { throw new Error('must not run'); } });
	assert.throws(() => buildPlannerInput({
		decisionContext: 'arena_script_compiler_error',
		compilerError: { code: 'SYNTAX_ERROR', message: 'bad', line: 1, column: 0 },
		rejectedSourceHash: 'sha256:abc123', observation: accessorObservation,
	}), /own data/);
	assert.throws(() => buildPlannerInput({
		decisionContext: 'arena_script_compiler_error',
		compilerError: { code: 'SYNTAX_ERROR', message: 'bad', line: 1, column: 0 },
		rejectedSourceHash: 'sha256:abc123', observation: Object.create({ resourceCount: 8 }),
	}), /plain data/);
});
