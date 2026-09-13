import test from 'node:test';
import assert from 'node:assert/strict';

import { parseArenaScript } from '../src/arena-script/parser.mjs';
import { VirtualMinecraftBridge } from '../src/simulator/virtual-minecraft-bridge.mjs';
import { VirtualWorld } from '../src/simulator/virtual-world.mjs';
import { ActionRuntime } from '../src/simulator/action-runtime.mjs';
import { getSimulatorScenario, listSimulatorScenarios } from '../src/simulator/simulator-scenarios.mjs';
import {
	captureScenarioInitialSnapshot,
	compileScenarioDecision,
	compileScenarioProgram,
	buildAuthoritativeScenarioOutcome,
	runAuthoritativeScenarioSuccess,
} from '../src/benchmark/scenario-program.mjs';

test('compiles the stone-tool manifest into bounded deterministic ArenaScript', () => {
	const manifest = getSimulatorScenario('stone-tool-gathering');
	const compiled = compileScenarioProgram(manifest);
	assert.equal(compiled.commands.length, manifest.commands.length);
	assert.equal(compiled.commands.map((command) => command.actionType).join(','), 'navigate_to,break_block,craft_inventory');
	assert.match(compiled.source, /player\.navigateTo\(\{[^}]*\"x\":0/);
	assert.match(compiled.source, /player\.mine\(\{[^}]*\"x\":1/);
	assert.match(compiled.source, /player\.craftInventory\(\{[^}]*\"recipeId\":\"minecraft:stone_pickaxe\"/);
	assert.match(compiled.source, /program\.finish\("scenario-complete"\);/);
	assert.doesNotThrow(() => parseArenaScript(compiled.source));
	assert.deepEqual(compileScenarioDecision(manifest), compiled.decision);
	assert.equal(Object.hasOwn(compiled.decision, 'completionContract'), false);

	const unsafe = { ...manifest, commands: [{ actionId: 'bad', actionType: 'chat', arguments: { message: '</script>', audience: 'public' } }] };
	const unsafeCompiled = compileScenarioProgram(unsafe);
	assert.equal(JSON.parse(unsafeCompiled.source.match(/player\.chat\((\{.*\})\);/)[1]).message, '</script>');
});

test('never gives benchmark provider decisions completion authority', () => {
	const commandless = {
		id: 'wait-fixture', agentId: 'fixture-agent',
		world: { agents: { 'fixture-agent': { position: { x: 2, y: 3, z: 4 } } } },
		commands: [], events: [], expected: {},
	};
	assert.equal(Object.hasOwn(compileScenarioDecision(commandless), 'completionContract'), false);
	const terminal = {
		...commandless,
		id: 'terminal-fixture',
		commands: [{ actionId: 'stall', actionType: 'navigate_to', arguments: { x: 50, y: 3, z: 4, tolerance: 0.1, sprint: false, timeoutMs: 1 } }],
		expected: { terminalState: 'TIMED_OUT' },
	};
	assert.equal(Object.hasOwn(compileScenarioDecision(terminal), 'completionContract'), false);
});

test('rejects unsupported or over-limit manifest commands before compiling', () => {
	const manifest = getSimulatorScenario('stone-tool-gathering');
	assert.throws(() => compileScenarioProgram({ ...manifest, commands: [{ actionId: 'unknown', actionType: 'provider_prose', arguments: {} }] }), /unsupported.*mapping/i);
	assert.throws(() => compileScenarioProgram({ ...manifest, commands: Array.from({ length: 257 }, (_, index) => ({ ...manifest.commands[0], actionId: `command-${index}` })) }), /command.*limit/i);
	assert.throws(() => compileScenarioProgram({ ...manifest, commands: [{ ...manifest.commands[0], arguments: { ...manifest.commands[0].arguments, bad: undefined } }] }), /JSON|serializ/i);
});

test('benchmark and simulator reject nested action discriminators before dispatch', () => {
	const command = { agentId: 'agent-a', actionId: 'bad', actionType: 'respawn', arguments: { type: 'wait', durationMs: 1 } };
	assert.throws(() => compileScenarioProgram({ id: 'discriminator', commands: [command] }), /arguments\.type is reserved/);
	const runtime = new ActionRuntime();
	assert.throws(() => runtime.accept(command), /arguments\.type is reserved/);
	assert.deepEqual(runtime.activeActionIds, []);
});

test('scenario compilation rejects valid production actions the simulator cannot execute', () => {
	const commands = [
		{ actionId: 'frame', actionType: 'control', arguments: { forward: 1, strafe: 0, jump: false, sneak: false, sprint: false, attack: false, use: false, yaw: 0, pitch: 0, selectedSlot: 0, hand: 'main', ticks: 1 } },
		{ actionId: 'dismount', actionType: 'dismount', arguments: {} },
	];
	for (const command of commands) {
		const argumentsSource = Object.keys(command.arguments).length === 0 ? '' : JSON.stringify(command.arguments);
		const source = `program.onUnhandledAttention("continue_and_notify"); await player.${command.actionType}(${argumentsSource});`;
		assert.doesNotThrow(() => parseArenaScript(source));
		assert.throws(() => compileScenarioProgram({ id: 'unsupported-action', commands: [command] }), { code: 'SIMULATOR_UNSUPPORTED_ACTION' });
	}
});

async function executeManifest(manifest, commandIndexes = manifest.commands.map((_, index) => index)) {
	const world = VirtualWorld.fromScenario(manifest.world);
	const bridge = new VirtualMinecraftBridge({ world, actionRuntime: new ActionRuntime() });
	const initialSnapshot = captureScenarioInitialSnapshot({ manifest, world });
	const traceId = `scenario-${manifest.agentId}`;
	for (const index of commandIndexes) {
		const command = manifest.commands[index];
		await bridge.send('action_command', manifest.agentId, {
			traceId,
			goalRevision: 1,
			actionId: `provider-generated-${index + 1}`,
			actionType: command.actionType,
			arguments: command.arguments,
			provenance: { provider: 'fixture', model: 'fixture', reasoningEffort: 'none', serviceTier: 'fixture', programId: 'scenario', programVersion: 1, sourceStepId: `step-${index + 1}`, eventSequence: index + 1, traceId },
		});
		for (let tick = 0; tick < 200 && bridge.activeActionIds.length > 0; tick += 1) world.stepTicks(1);
		await bridge.flush();
	}
	return { world, bridge, initialSnapshot };
}

test('derives stone-tool success from physical state and ordered bridge history', async () => {
	const manifest = getSimulatorScenario('stone-tool-gathering');
	const execution = await executeManifest(manifest);
	const outcome = buildAuthoritativeScenarioOutcome({ manifest, ...execution });
	assert.equal(outcome.commandMapping.valid, true);
	assert.equal(outcome.state.inventoryAfter.find((item) => item.itemId === 'minecraft:stone_pickaxe')?.count, 1);
	assert.equal(runAuthoritativeScenarioSuccess({ manifest, ...execution }), true);
});

test('rejects near misses and a provider finish with no physical commands', async () => {
	const manifest = getSimulatorScenario('stone-tool-gathering');
	const nearMiss = await executeManifest(manifest, [0, 1]);
	assert.equal(runAuthoritativeScenarioSuccess({ manifest, ...nearMiss, providerFinished: true }), false);

	const empty = await executeManifest(manifest, []);
	assert.equal(runAuthoritativeScenarioSuccess({ manifest, ...empty, providerFinished: true }), false);
});

test('supports every command-bearing manifest through physical outcome derivation', async () => {
	for (const id of listSimulatorScenarios()) {
		const manifest = getSimulatorScenario(id);
		if (manifest.commands.length === 0) continue;
		assert.doesNotThrow(() => compileScenarioProgram(manifest), `${id} must compile for the simulator`);
		const execution = await executeManifest(manifest);
		assert.equal(runAuthoritativeScenarioSuccess({ manifest, ...execution }), true, `${id} must pass from simulator state`);
	}

	const correction = getSimulatorScenario('invalid-decision-correction');
	const correctionExecution = await executeManifest(correction);
	assert.equal(runAuthoritativeScenarioSuccess({
		manifest: correction,
		...correctionExecution,
		events: [{ invalidDecisionId: correction.expected.invalidDecisionId, correctedDecisionId: correction.expected.correctedDecisionId, accepted: true }],
	}), true);
});
